package coop.fleet;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.List;
import java.util.Locale;

/**
 * The observation half of the {@code -Dcoop.debug.allyPullIn} spike (debug-only, dormant unless the
 * switch is on).
 *
 * <p>Normally the partner's mirror can never end up in a battle: it is created with
 * {@code FLEET_IGNORES_OTHER_FLEETS} — the one flag vanilla's
 * {@code FleetInteractionDialogPluginImpl.pullInNearbyFleets} consults — and the host's
 * {@code CoopNpcThreatWatcher} leaves any battle it somehow joined on the very next frame. With the
 * switch on both of those step aside and this class records what the engine actually does with the
 * mirror instead: one line when it joins a battle, one when that battle lets go, with the mirror's
 * roster on both so the damage can be diffed.
 *
 * <p><b>Static state on purpose.</b> Two call sites observe the same fleet — the host's threat
 * watcher (which is where the eject used to happen) and the per-frame shield pass in
 * {@link CoopFleetMirror}, which is the only one that runs on a <em>guest</em> instance, where there
 * is no threat watcher at all. Sharing the "already logged this battle" state between them is what
 * keeps the host from printing the join line twice. Everything here runs on the campaign thread.
 *
 * <p>Nothing in here may abort a frame, so every entry point swallows {@code RuntimeException} and
 * {@code LinkageError}: a spike log line is worth strictly less than the session it is watching.
 */
public final class CoopAllyPullInSpike {

    /** Prefix of the line printed once when the mirror turns up in a battle. */
    public static final String JOIN_PREFIX = "Coop SPIKE ally pull-in: mirror is in a battle";
    /** Prefix of the line printed once when that battle is over. */
    public static final String LEAVE_PREFIX = "Coop SPIKE ally pull-in: mirror left the battle";
    /** Printed once per mirror creation, so the log proves the switch took effect on this instance. */
    public static final String ENABLED_LINE =
            "Coop SPIKE ally pull-in ENABLED: player mirror is joinable";
    /** Printed alongside {@link #ENABLED_LINE} when the second run's shield drop is also armed. */
    public static final String SHIELD_DOWN_LINE = "Coop SPIKE ally pull-in: engagement shield is DOWN"
            + " for the player mirror (allyPullInDropShield)";

    /**
     * The battle instance the join line has already been printed for, by identity. Non-null means
     * "we are inside a battle we have already described"; the transition back to null is the leave
     * line. Identity rather than {@code equals} because two consecutive battles could compare equal
     * and the second one would then go unlogged.
     */
    private static Object observedBattle;
    /** The mirror's roster as it was when the join line was printed, replayed in the leave line. */
    private static String preBattleRoster = "";

    private CoopAllyPullInSpike() {
    }

    /**
     * Announces the spike on the instance that has it armed. Called from player-mirror creation, which
     * happens once per session (and again only if the mirror is rebuilt, which is itself worth seeing).
     */
    public static void announce() {
        if (!CoopDebug.allyPullInEnabled()) {
            return;
        }
        CoopLog.warn(CoopAllyPullInSpike.class, ENABLED_LINE);
        if (CoopDebug.allyPullInDropShieldEnabled()) {
            CoopLog.warn(CoopAllyPullInSpike.class, SHIELD_DOWN_LINE);
        }
    }

    /**
     * Polls the mirror's battle. Safe to call every frame and from more than one place in the same
     * frame: it logs only on the edges (no battle -&gt; battle, battle -&gt; no battle).
     *
     * @param mirror the partner's mirror fleet on this engine, or null
     */
    public static void observe(CampaignFleetAPI mirror) {
        if (mirror == null) {
            return;
        }
        try {
            BattleAPI battle = mirror.getBattle();
            if (battle == null) {
                if (observedBattle == null) {
                    return;
                }
                // Clear the state before formatting anything: if the roster read throws, the leave
                // line is lost but the next battle is still logged, instead of one line per frame.
                observedBattle = null;
                String before = preBattleRoster;
                preBattleRoster = "";
                CoopLog.warn(CoopAllyPullInSpike.class, LEAVE_PREFIX
                        + " | post-battle roster: " + roster(mirror)
                        + " | pre-battle roster: " + before);
                return;
            }
            if (battle == observedBattle) {
                return;
            }
            observedBattle = battle;
            String before = roster(mirror);
            preBattleRoster = before;
            CoopLog.warn(CoopAllyPullInSpike.class, JOIN_PREFIX + " " + describe(mirror, battle)
                    + " | pre-battle roster: " + before);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopAllyPullInSpike.class,
                    "Coop SPIKE ally pull-in: failed to read the mirror's battle", ex);
        }
    }

    /** Drops the per-battle state, so a new session does not inherit the last one's edge. */
    public static void reset() {
        observedBattle = null;
        preBattleRoster = "";
    }

    /** True while a battle has been logged and not yet seen to end (test seam). */
    public static boolean isObservingBattle() {
        return observedBattle != null;
    }

    /**
     * Everything cheaply readable out of {@link BattleAPI} about where the mirror landed. Kept to one
     * line so the host and guest logs stay diffable.
     */
    private static String describe(CampaignFleetAPI mirror, BattleAPI battle) {
        List<CampaignFleetAPI> side = battle.getSideFor(mirror);
        List<CampaignFleetAPI> one = battle.getSideOne();
        List<CampaignFleetAPI> two = battle.getSideTwo();
        return "mirrorSide=" + sideName(mirror, one, two)
                + " mirrorSideIsPlayerSide=" + (side != null && battle.isPlayerSide(side))
                + " mirrorSideFleets=" + size(side)
                + " playerInvolved=" + battle.isPlayerInvolved()
                + " playerSideFleets=" + size(battle.getPlayerSide())
                + " sideOneFleets=" + size(one)
                + " sideTwoFleets=" + size(two)
                + " primaryOne=" + name(primary(battle, one))
                + " primaryTwo=" + name(primary(battle, two));
    }

    /**
     * Which of the two sides holds the mirror, by membership rather than by comparing the list
     * {@code getSideFor} returns: nothing in the API says that list is the same object as
     * {@code getSideOne()}/{@code getSideTwo()} rather than a copy.
     */
    private static String sideName(CampaignFleetAPI mirror, List<CampaignFleetAPI> one,
                                   List<CampaignFleetAPI> two) {
        if (contains(one, mirror)) {
            return "ONE";
        }
        if (contains(two, mirror)) {
            return "TWO";
        }
        return "none";
    }

    private static boolean contains(List<CampaignFleetAPI> side, CampaignFleetAPI fleet) {
        if (side == null) {
            return false;
        }
        for (CampaignFleetAPI member : side) {
            if (member == fleet) {
                return true;
            }
        }
        return false;
    }

    /** {@code getPrimary} is not documented to tolerate an empty or absent side, so it is not asked. */
    private static CampaignFleetAPI primary(BattleAPI battle, List<CampaignFleetAPI> side) {
        if (side == null || side.isEmpty()) {
            return null;
        }
        return battle.getPrimary(side);
    }

    private static int size(List<CampaignFleetAPI> side) {
        return side == null ? -1 : side.size();
    }

    private static String name(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return "none";
        }
        try {
            String name = fleet.getName();
            return "'" + (name == null ? "" : name) + "'";
        } catch (RuntimeException | LinkageError ex) {
            return "'?'";
        }
    }

    /**
     * The mirror's roster in the only three numbers this spike is about: what ships it has, how beaten
     * up their hulls are, and their CR. Printed on both edges so a join/leave pair shows exactly what
     * an autoresolve round did to a fleet whose owner was not in the fight.
     */
    static String roster(CampaignFleetAPI fleet) {
        try {
            List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
            if (members == null) {
                return "unreadable (no member list)";
            }
            StringBuilder out = new StringBuilder(32 + members.size() * 40);
            out.append(members.size()).append(" members [");
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                appendMember(out, members.get(i));
            }
            return out.append(']').toString();
        } catch (RuntimeException | LinkageError ex) {
            return "unreadable (" + ex + ")";
        }
    }

    private static void appendMember(StringBuilder out, FleetMemberAPI member) {
        if (member == null) {
            out.append("null");
            return;
        }
        try {
            out.append(member.getHullId())
                    .append(" hull=").append(fraction(member.getStatus().getHullFraction()))
                    .append(" cr=").append(fraction(member.getRepairTracker().getCR()));
        } catch (RuntimeException | LinkageError ex) {
            out.append("unreadable");
        }
    }

    /** Locale-independent so a host and a guest in different locales produce diffable lines. */
    private static String fraction(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
