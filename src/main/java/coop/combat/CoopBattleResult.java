package coop.combat;

import coop.campaign.CoopDelimited;
import coop.fleet.CoopFleetSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The campaign-level consequences of one finished coop battle, as reported by the client that
 * <em>fought</em> it, plus its codec (Phase 15).
 *
 * <p>Vanilla applies every battle consequence locally and self-contained on the fighting client
 * ({@code FleetEncounterContext.processEngagementResults} and
 * {@code applyAfterBattleEffectsIfThereWasABattle}, both driven from the interaction dialog's LEAVE
 * path). On the guest that happens against a <em>mirror</em> of a host-owned fleet, so the host's
 * authoritative Phase 9 set never learns the fleet died. This record is the missing report: which
 * host {@code coopFleetId}s are gone and what the survivors look like now.
 *
 * <h2>What is deliberately NOT in here</h2>
 * <ul>
 *   <li><b>No reputation delta.</b> Investigated 2026-08-19 and left out on purpose. Every player
 *       faction standing change on the guest — including the ones vanilla applies for a battle —
 *       goes through {@code CampaignEventListener.reportPlayerReputationChange}, which
 *       {@code CoopCampaignReplicator.onPlayerReputationChange} (:249) already forwards to the host
 *       as {@code GUEST_REP_DELTA} (:266); the host folds the increment into the canonical standing
 *       and rebroadcasts {@code REP_DELTA}. The Phase 14 customs spike verified that exact path end
 *       to end with no new code (see {@code CoopCustomsDialogStaging}'s class doc), and the 30 s
 *       {@code PLAYER_REP_SNAPSHOT} full overwrite is a second safety net. Carrying a rep delta here
 *       too would apply the same change twice. The plan's step text is amended with a dated note.</li>
 *   <li><b>No spoils.</b> XP, salvage, credits and recoveries belong to the client that fought, by
 *       the v1 reward rule — vanilla has already applied them locally and the mod must not touch
 *       them. There is no {@code CoopRewardSplitter} in v1 (deferred to v2/v3 joint combat).</li>
 *   <li><b>No own-fleet roster.</b> {@link #engagingFleetSize} is informational only (log/diagnostic
 *       parity check). The partner's mirror of the engaging player's fleet is refreshed by the
 *       existing Phase 8 {@code FLEET_SNAPSHOT} UDP stream, which carries the <em>full</em> roster
 *       with per-ship CR and hull at 10 Hz and rebuilds the mirror on any structural change
 *       ({@code CoopFleetMirror.refreshRosterIfChanged}). Duplicating it here would be a second,
 *       slower source of truth for state that already self-heals in 100 ms.</li>
 * </ul>
 *
 * <h2>Why survivors carry ships and not ship ids</h2>
 * A guest mirror's {@code FleetMemberAPI}s are minted locally by
 * {@code CoopFleetMirror.addMirrorMember}, so their {@code fleetMemberId}s mean nothing to the host.
 * Survivors therefore travel as the {@link CoopFleetSnapshot.Member} records the shared capture
 * produces, and {@code CoopBattleResultReconciler} matches them against the host's real roster by
 * <em>variant multiset</em> (falling back to hull id). Which particular Wolf died does not matter;
 * how many of them are left does. {@code fleetMemberId} rides along unused so the encoding stays the
 * same shape as every other roster payload.
 *
 * <p><b>Delimited body, no arrays.</b> Same constraint and same shape as {@link CoopBattleStatus}:
 * the flat {@link coop.net.CoopMessages} envelope parser has no JSON arrays, so the lists ride as one
 * string field of newline-separated, {@code |}-separated records escaped through
 * {@link CoopDelimited#field(String)}. Each line starts with a one-letter type token ({@code D} =
 * destroyed fleet, {@code F} = surviving fleet header, {@code M} = a member of the surviving fleet
 * most recently opened by an {@code F} line). Unknown tokens and extra trailing fields are ignored so
 * a later phase can extend the record without breaking an older peer.
 */
public record CoopBattleResult(String battleId, String engagingPlayerId, String outcome,
                               int engagingFleetSize,
                               List<String> destroyedFleetIds,
                               List<SurvivingFleet> survivingFleets) {

    /**
     * One host-owned NPC fleet that came out of the battle still alive, with the roster it has now.
     * An empty {@code members} list means the fleet is effectively dead and the reconciler treats it
     * as destroyed — an escape/disengage that left it damaged but crewed is the normal case.
     */
    public record SurvivingFleet(String coopFleetId, List<CoopFleetSnapshot.Member> members) {
        public SurvivingFleet {
            coopFleetId = CoopDelimited.normalize(coopFleetId);
            members = members == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(members));
        }
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final String RECORD_SEPARATOR = "\n";
    private static final String DESTROYED_TOKEN = "D";
    private static final String FLEET_TOKEN = "F";
    private static final String MEMBER_TOKEN = "M";
    /** Minimum field count of an {@code M} line; extra trailing fields are tolerated (see class doc). */
    private static final int MEMBER_FIELD_COUNT = 8;

    public CoopBattleResult {
        battleId = CoopDelimited.normalize(battleId);
        engagingPlayerId = CoopDelimited.normalize(engagingPlayerId);
        outcome = CoopDelimited.normalize(outcome);
        destroyedFleetIds = destroyedFleetIds == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(destroyedFleetIds));
        survivingFleets = survivingFleets == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(survivingFleets));
    }

    /**
     * Every host {@code coopFleetId} this battle touched, destroyed or not. Used by the guest to mark
     * its mirrors pending-reconcile and by the host to restart those fleets' engage cooldowns.
     */
    public List<String> involvedFleetIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(destroyedFleetIds);
        for (SurvivingFleet fleet : survivingFleets) {
            ids.add(fleet.coopFleetId());
        }
        ids.remove("");
        return new ArrayList<>(ids);
    }

    /**
     * True when this battle changed nothing about the host's fleets. Still sent: the host uses the
     * message to restart pacing state (engage cooldowns) deterministically, which is exactly what a
     * clean disengage needs.
     */
    public boolean isEmpty() {
        return destroyedFleetIds.isEmpty() && survivingFleets.isEmpty();
    }

    /** The self-contained delimited body that rides in the {@code body} payload field. */
    public String encodeBody() {
        List<String> lines = new ArrayList<>();
        for (String id : destroyedFleetIds) {
            lines.add(DESTROYED_TOKEN + FIELD_SEPARATOR + CoopDelimited.field(id));
        }
        for (SurvivingFleet fleet : survivingFleets) {
            lines.add(FLEET_TOKEN + FIELD_SEPARATOR + CoopDelimited.field(fleet.coopFleetId()));
            for (CoopFleetSnapshot.Member member : fleet.members()) {
                lines.add(MEMBER_TOKEN
                        + FIELD_SEPARATOR + CoopDelimited.field(member.fleetMemberId())
                        + FIELD_SEPARATOR + CoopDelimited.field(member.hullId())
                        + FIELD_SEPARATOR + CoopDelimited.field(member.variantId())
                        + FIELD_SEPARATOR + CoopDelimited.field(member.shipName())
                        + FIELD_SEPARATOR + CoopDelimited.field(member.captainName())
                        + FIELD_SEPARATOR + member.cr()
                        + FIELD_SEPARATOR + member.hullFraction());
            }
        }
        return String.join(RECORD_SEPARATOR, lines);
    }

    /** Reverses {@link #encodeBody()}. Unknown record types and extra fields are ignored. */
    public static CoopBattleResult decode(String battleId, String engagingPlayerId, String outcome,
                                          int engagingFleetSize, String body) {
        Objects.requireNonNull(body, "body");
        List<String> destroyed = new ArrayList<>();
        List<SurvivingFleet> survivors = new ArrayList<>();
        String currentFleetId = null;
        List<CoopFleetSnapshot.Member> currentMembers = null;
        for (String line : body.split(RECORD_SEPARATOR, -1)) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> fields = CoopDelimited.split(line);
            String token = fields.get(0);
            if (DESTROYED_TOKEN.equals(token)) {
                destroyed.add(fields.size() > 1 ? fields.get(1) : "");
            } else if (FLEET_TOKEN.equals(token)) {
                if (currentFleetId != null) {
                    survivors.add(new SurvivingFleet(currentFleetId, currentMembers));
                }
                currentFleetId = fields.size() > 1 ? fields.get(1) : "";
                currentMembers = new ArrayList<>();
            } else if (MEMBER_TOKEN.equals(token)) {
                if (currentMembers == null) {
                    // A member with no fleet header ahead of it: the sender's body is malformed or
                    // from a future shape. Dropping the ship is safer than inventing a fleet for it.
                    continue;
                }
                if (fields.size() < MEMBER_FIELD_COUNT) {
                    throw new IllegalArgumentException("Expected at least " + MEMBER_FIELD_COUNT
                            + " battle-result member fields, got " + fields.size());
                }
                currentMembers.add(new CoopFleetSnapshot.Member(
                        fields.get(1), fields.get(2), fields.get(3), fields.get(4), fields.get(5),
                        parseFloat(fields.get(6)), parseFloat(fields.get(7))));
            }
            // Any other token is a record type this build does not know: ignore it.
        }
        if (currentFleetId != null) {
            survivors.add(new SurvivingFleet(currentFleetId, currentMembers));
        }
        return new CoopBattleResult(battleId, engagingPlayerId, outcome, engagingFleetSize,
                destroyed, survivors);
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (RuntimeException ex) {
            return 0f;
        }
    }
}
