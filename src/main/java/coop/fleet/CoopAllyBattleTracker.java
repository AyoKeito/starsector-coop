package coop.fleet;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.combat.CoopAllyBattleJoin;
import coop.combat.CoopAllyBattleOutcome;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 33: watches one <em>player</em> mirror's battle and turns the two edges into the records the
 * owner needs — a {@link CoopAllyBattleJoin} when the engine pulls the mirror into a fight, and a
 * {@link CoopAllyBattleOutcome} when that fight lets go.
 *
 * <p><b>Why a freeze and not a diff (spike, 2026-09-20).</b> Both fights in the spike came back with
 * a post-battle roster identical to the pre-battle one to three decimals, CR included, on ships that
 * had visibly fought. {@code CoopFleetMirror.updateMemberState} writes every member's hull fraction
 * and CR from the owner's snapshot several times a second, so the engine's post-battle numbers were
 * overwritten inside a frame of the battle ending. Nothing can be diffed after the fact; the writes
 * have to stop for the duration. {@link #frozen()} is that stop, and it stays true after the battle
 * ends until the outcome has been taken, because the read happens on the first frame the engine
 * reports no battle and the snapshot that would erase it arrives on the same frame.
 *
 * <p><b>Edges, never frame counts.</b> The guest's per-frame mirror pass does not run while a
 * blocking screen owns the campaign — the spike's join line landed only after the encounter dialog
 * closed, 1.8 s before its {@code BATTLE_BEGIN}, while the host's came 24 s earlier with the dialog
 * still open. Everything here keys on {@code getBattle()} changing, so a pass that does not run for
 * twenty seconds costs nothing but the timestamp on a log line.
 *
 * <p>Nothing here may abort a frame: every engine read is wrapped, and a read that fails leaves the
 * tracker in the state it was in rather than half a record.
 */
final class CoopAllyBattleTracker {

    /**
     * The battle this tracker has already raised a join for, by identity. Identity rather than
     * {@code equals} because two consecutive battles could compare equal and the second would then
     * go unreported.
     */
    private Object observedBattle;
    /** Sender-side ids of the ships the mirror took into the battle, for the destroyed diff. */
    private List<String> preBattleSenderIds = List.of();
    private CoopAllyBattleJoin pendingJoin;
    private CoopAllyBattleOutcome pendingOutcome;
    /** True from the frame the battle is seen until the outcome is taken. */
    private boolean frozen;

    /**
     * Polls the mirror's battle. Idempotent and safe to call more than once per frame: it acts only
     * on the two edges.
     *
     * @param mirror        the partner's mirror fleet on this engine
     * @param ownerPlayerId the player whose real fleet pays for what happens in there
     * @param senderIdsByEngineId engine member id to the owner-side id the roster snapshot carried,
     *                            as {@code CoopFleetMirror.rebuildRoster} filled it
     */
    void poll(CampaignFleetAPI mirror, String ownerPlayerId, Map<String, String> senderIdsByEngineId) {
        if (mirror == null) {
            return;
        }
        BattleAPI battle;
        try {
            battle = mirror.getBattle();
        } catch (RuntimeException | LinkageError ignored) {
            // A mirror that cannot answer for its battle is not evidence that it left one.
            return;
        }
        if (battle == null) {
            if (observedBattle != null) {
                noteBattleEnded(mirror, ownerPlayerId, senderIdsByEngineId);
            }
            return;
        }
        if (battle == observedBattle) {
            return;
        }
        if (observedBattle != null) {
            // A new battle object before this poll ever saw the old one go null: the engine can chain
            // an encounter straight into the next (a pursuit that catches its target, a second fleet
            // engaging on the same frame). Close the first one out first, so its result is not lost
            // under the second join; the pump takes the pending outcome before the next poll.
            noteBattleEnded(mirror, ownerPlayerId, senderIdsByEngineId);
        }
        noteBattleJoined(mirror, battle, ownerPlayerId, senderIdsByEngineId);
    }

    /**
     * True while the owner's snapshot must not touch this mirror's member state or roster. Covers the
     * battle itself and the window between the battle ending and the outcome being taken.
     */
    boolean frozen() {
        return frozen;
    }

    /** The join to send, or null. Returned once. */
    CoopAllyBattleJoin takeJoin() {
        CoopAllyBattleJoin join = pendingJoin;
        pendingJoin = null;
        return join;
    }

    /**
     * The outcome to send, or null. Returned once, and taking it releases {@link #frozen()} — the
     * caller has the numbers, so the owner's snapshots may drive the mirror again.
     */
    CoopAllyBattleOutcome takeOutcome() {
        CoopAllyBattleOutcome outcome = pendingOutcome;
        if (outcome == null) {
            return null;
        }
        pendingOutcome = null;
        frozen = false;
        preBattleSenderIds = List.of();
        return outcome;
    }

    /**
     * Drops everything without producing a record. The mirror was disposed or the session reset while
     * this was frozen, so there is nobody left to tell and nothing left to freeze.
     */
    void reset() {
        observedBattle = null;
        preBattleSenderIds = List.of();
        pendingJoin = null;
        pendingOutcome = null;
        frozen = false;
    }

    private void noteBattleJoined(CampaignFleetAPI mirror, BattleAPI battle, String ownerPlayerId,
                                  Map<String, String> senderIdsByEngineId) {
        observedBattle = battle;
        frozen = true;
        preBattleSenderIds = new ArrayList<>(liveSenderIds(mirror, senderIdsByEngineId));
        pendingJoin = new CoopAllyBattleJoin(ownerPlayerId, enemySummary(mirror, battle));
        CoopLog.info(CoopAllyBattleTracker.class, "Coop ally mirror pulled into a battle owner="
                + ownerPlayerId + " against='" + pendingJoin.enemySummary() + "' ships="
                + preBattleSenderIds.size() + "; the owner's snapshot is frozen off this mirror until"
                + " the result is read");
    }

    /**
     * The one roster read of the whole feature. Everything still in {@code FleetData} is a survivor
     * at the hull fraction and CR the engine left it at; everything the mirror took in and did not
     * bring out is destroyed.
     *
     * <p>A ship the mirror was never built with cannot appear in either list: the owner addresses its
     * fleet by its own member ids, and an engine-side id this mapping does not know is a ship the
     * owner cannot act on.
     */
    private void noteBattleEnded(CampaignFleetAPI mirror, String ownerPlayerId,
                                 Map<String, String> senderIdsByEngineId) {
        observedBattle = null;
        List<CoopAllyBattleOutcome.Survivor> survivors = new ArrayList<>();
        Set<String> survived = new HashSet<>();
        try {
            for (FleetMemberAPI member : mirror.getFleetData().getMembersListCopy()) {
                CoopAllyBattleOutcome.Survivor survivor = survivorOf(member, senderIdsByEngineId);
                if (survivor == null) {
                    continue;
                }
                survivors.add(survivor);
                survived.add(survivor.memberId());
            }
        } catch (RuntimeException | LinkageError ex) {
            // Half a survivor list would read as "everything unnamed is destroyed" on the owner, so
            // an unreadable roster produces no outcome at all and the freeze lifts on the next reset.
            CoopLog.warn(CoopAllyBattleTracker.class, "Coop could not read the ally mirror's roster"
                    + " after the battle; no loss result goes to " + ownerPlayerId, ex);
            frozen = false;
            preBattleSenderIds = List.of();
            return;
        }
        List<String> destroyed = new ArrayList<>();
        for (String senderId : preBattleSenderIds) {
            if (!survived.contains(senderId)) {
                destroyed.add(senderId);
            }
        }
        pendingOutcome = new CoopAllyBattleOutcome(ownerPlayerId, destroyed, survivors);
        CoopLog.info(CoopAllyBattleTracker.class, "Coop ally mirror left its battle owner="
                + ownerPlayerId + " destroyed=" + destroyed.size() + " survivors=" + survivors.size()
                + "; the mirror stays frozen until the result is taken");
    }

    /** One surviving ship, or null when the engine will not name it or the mapping does not know it. */
    private static CoopAllyBattleOutcome.Survivor survivorOf(FleetMemberAPI member,
                                                             Map<String, String> senderIdsByEngineId) {
        if (member == null) {
            return null;
        }
        try {
            String senderId = senderIdsByEngineId.get(member.getId());
            if (senderId == null || senderId.isEmpty()) {
                return null;
            }
            return new CoopAllyBattleOutcome.Survivor(senderId,
                    member.getStatus().getHullFraction(), member.getRepairTracker().getCR());
        } catch (RuntimeException | LinkageError ignored) {
            // A ship that cannot report its own state is left out of the survivor list rather than
            // reported at a made-up number; the owner then keeps whatever it already had for it.
            return null;
        }
    }

    /** The owner-side ids of the ships the mirror holds right now, in roster order. */
    private static List<String> liveSenderIds(CampaignFleetAPI mirror,
                                              Map<String, String> senderIdsByEngineId) {
        List<String> ids = new ArrayList<>();
        try {
            for (FleetMemberAPI member : mirror.getFleetData().getMembersListCopy()) {
                if (member == null) {
                    continue;
                }
                String senderId = senderIdsByEngineId.get(member.getId());
                if (senderId != null && !senderId.isEmpty()) {
                    ids.add(senderId);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An unreadable pre-battle roster means no ship can be reported destroyed, which is the
            // conservative answer: the owner keeps every ship it has.
            return List.of();
        }
        return ids;
    }

    /**
     * What the owner's notice names as the enemy: the primary fleet of the side the mirror is not on.
     * Blank when the engine will not say, which the owner renders as a fight without a named
     * opponent rather than as no fight.
     */
    private static String enemySummary(CampaignFleetAPI mirror, BattleAPI battle) {
        try {
            List<CampaignFleetAPI> otherSide = battle.getOtherSideFor(mirror);
            if (otherSide == null || otherSide.isEmpty()) {
                return "";
            }
            CampaignFleetAPI primary = battle.getPrimary(otherSide);
            if (primary == null) {
                return "";
            }
            String name = primary.getName();
            return name == null ? "" : name;
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }
}
