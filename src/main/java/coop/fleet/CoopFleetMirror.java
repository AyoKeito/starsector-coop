package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.DModManager;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.VariantSource;
import coop.util.CoopDebug;
import coop.util.CoopFrameProfiler;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * An AI-mode {@link CampaignFleetAPI} mirror driven by replicated snapshots. Serves two roles:
 *
 * <ul>
 *   <li><b>Player mirror (Phase 8, "two-fleet trick"):</b> {@link #apply(CoopFleetSnapshot, String)}
 *       renders the remote player in the local player's faction colour with always-visible presence
 *       styling ({@link CoopPresenceIndicator}), tagged {@code $coopMirrorFleet}.</li>
 *   <li><b>NPC mirror (Phase 9):</b> {@link #applySnapshot(CoopNpcFleetSnapshot)} renders a
 *       host-authoritative NPC fleet in its real faction, tagged {@code $coopNpcFleetId}, with no
 *       presence styling (normal sensor visibility). {@link #applyMotion(CoopNpcFleetMotion)} applies
 *       the high-frequency UDP position updates between full-set applies.</li>
 * </ul>
 *
 * <p>Both roles share the driving contract: {@code setAIMode(true)}, the per-frame engagement shield
 * ({@link #assertEngagementShield()}, driven by the pump — the mirror never autonomously starts
 * combat, because real engagements happen on the host's authoritative copy), <b>kinematic motion</b>
 * (Phase 29 M1: received samples queue in a {@link CoopMotionInterpolator}; every frame
 * {@link #advanceMotion} places the fleet at the buffered trajectory ~200 ms behind the sender —
 * {@code setMoveDestinationOverride} is never called on a mirror, because engine steering runs under
 * the <em>mirror's</em> movement stats and arrival radius, which produced the two measured ~1 Hz
 * teleport modes the plan's Phase 29 banner records), and a roster rebuild only when the snapshot's
 * {@code fleetHash} changes.
 */
public class CoopFleetMirror implements CoopNpcMirror {
    /**
     * Below this interpolated speed (su/s) the mirror keeps its last facing instead of tracking the
     * velocity direction — an orbit-drift fleet must not spin as its tiny velocity vector wanders.
     */
    static final float FACING_SPEED_FLOOR = 5f;
    /**
     * How often the {@code noEngaging} fader is refreshed. The fader the engine builds expires ~1 s
     * after the last {@code setNoEngaging} call, so 250 ms holds it with a 4x margin while cutting the
     * per-frame Fader allocation the shield used to make for every mirror.
     */
    static final long SHIELD_REASSERT_INTERVAL_MILLIS = 250L;
    /** Sentinel for "the shield has never been asserted", checked explicitly to dodge overflow. */
    static final long NEVER_ASSERTED = Long.MIN_VALUE;
    private static final String PLAYER_MIRROR_TAG = CoopMirrorTags.PLAYER_MIRROR_TAG;
    private static final String NPC_MIRROR_TAG = CoopMirrorTags.NPC_MIRROR_TAG;
    private static final String DEFAULT_NPC_FACTION = "independent";
    private static final String DEFAULT_NPC_NAME = "Fleet";

    private final Supplier<SectorAPI> sectorSupplier;
    private final CoopPresenceIndicator presenceIndicator;
    /** Phase 29 M1: the per-mirror sample buffer {@link #advanceMotion} renders from. */
    private final CoopMotionInterpolator motionInterpolator = new CoopMotionInterpolator();
    /**
     * {@link CoopMotionSpeedProbe} trackers ({@link coop.util.CoopDebug}-gated): the previous
     * rendered pose and the previous accepted sample, so each frame/sample contributes its move
     * distance to the shared probe. Both invalidate on any hard cut — a teleport is not "speed".
     */
    private boolean speedProbeHasRendered;
    private float speedProbeRenderedX;
    private float speedProbeRenderedY;
    private boolean speedProbeHasSample;
    private float speedProbeSampleX;
    private float speedProbeSampleY;

    private CampaignFleetAPI mirrorFleet;
    private String lastFleetHash;
    /**
     * A structural hash whose roster could not be built completely (an unresolvable variant/hull), kept
     * so {@link #refreshRosterIfChanged} retries it exactly <em>once</em> and then stops. Without the
     * retry a single failed rebuild latched forever — the gate commits the hash and only a change on
     * the host would ever flip it. Without the "once" it would rebuild the same unbuildable roster on
     * every set for the life of the fleet.
     */
    private String retriedFleetHash;
    private String lastLocationId;
    /** The host-side fleet id this mirror stands for; "" for the Phase 8 player mirror. Diagnostics. */
    private String coopFleetId = "";
    private String appliedName = "";
    private String appliedFactionId = "";
    /** True while the shield is deliberately down for the player's interaction target (edge-tracked). */
    private boolean shieldReleased;
    /** When {@code setNoEngaging(1f)} was last called, or {@link #NEVER_ASSERTED}. */
    private long shieldAssertedAtMillis = NEVER_ASSERTED;
    /**
     * Last sensor identity received for this mirror (Phase 14b). Held rather than applied once because
     * the engine rewrites the mirror's profile/strength fields every frame and local terrain re-applies
     * its own detectability mods every frame — see {@link #assertSensorState()}.
     */
    private CoopSensorSync.Profile sensors = CoopSensorSync.Profile.UNKNOWN;
    /**
     * Last {@code aiAssignmentSummary} pinned onto this mirror (Phase 9b). Held so the two setters run
     * only when the host's text actually moves, not on every 1 Hz set apply for every mirrored fleet.
     */
    private String appliedActionText = "";
    /** Whether the {@link CoopDebug} one-shot "which tooltip path is live" line has been printed. */
    private boolean loggedActionTextPath;
    /**
     * Whether the empty-roster guard has already logged for the current wipe episode (Phase 17). Reset
     * the moment a non-empty roster arrives, so a second wipe later in the session logs again.
     */
    private boolean emptyRosterSkipLogged;
    /**
     * Per-slot CR reference for the {@link #updateMemberState} invalidation gate: the value that last
     * <em>fired</em> the gate, not the value last written. {@code NaN} means "no gate has fired for this
     * slot yet", and the first apply seats it from the member's live CR. Indexed by list position
     * because that is the axis the CR write itself pairs on — a reference that followed the ship while
     * the write followed the slot would be wrong in exactly the transient-order case the write tolerates.
     * Null until the first apply after a rebuild; {@link #rebuildRoster} drops it so a new ship set never
     * gates against the old one's references.
     */
    private float[] crInvalidationReferences;

    /**
     * The sender-side member ids of the ships {@link #rebuildRoster} actually built, in the order it
     * built them — i.e. the mirror's live slot order. Two jobs: it pairs incoming per-ship state to
     * the slot that ship really occupies even when the sender's list order has moved under an
     * unchanged structural hash, and its size is what the same-hash fast path checks the live
     * {@code FleetData} against (see {@link #rosterStillIntact}). Empty until the first rebuild.
     */
    private List<String> builtMemberIds = List.of();
    /** {@link #builtMemberIds}'s size, or -1 while no rebuild has run yet (an empty build is 0). */
    private int builtMemberCount = -1;

    public CoopFleetMirror() {
        this(Global::getSector, new CoopPresenceIndicator());
    }

    public CoopFleetMirror(Supplier<SectorAPI> sectorSupplier, CoopPresenceIndicator presenceIndicator) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
        this.presenceIndicator = Objects.requireNonNull(presenceIndicator, "presenceIndicator");
    }

    // ---- Player mirror (Phase 8) -------------------------------------------------------------

    /**
     * Local player's faction id, used to color the mirror in the local player's own color.
     * {@code sampleTimeSeconds} is the sender's stream-time stamp from the datagram section.
     */
    public void apply(CoopFleetSnapshot snapshot, String localPlayerFactionId,
                      double sampleTimeSeconds) {
        Objects.requireNonNull(snapshot, "snapshot");
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        LocationAPI location = resolveLocation(sector, snapshot.locationId());
        if (location == null) {
            // Snapshot references a location this client cannot resolve yet; skip this tick.
            return;
        }
        try {
            if (shouldDeferPlayerFleetCreation(mirrorFleet != null && mirrorFleet.isAlive(),
                    snapshot.members().size())) {
                noteEmptyPlayerRosterSkipped(snapshot.fleetHash());
                return;
            }
            ensurePlayerFleet(snapshot, localPlayerFactionId);
            placeInLocation(location, snapshot.x(), snapshot.y());
            feedMotion(sampleTimeSeconds, snapshot.x(), snapshot.y(),
                    snapshot.velocityX(), snapshot.velocityY());
            acceptTransponder(snapshot.transponderOn());
            acceptSensors(snapshot.sensors());
            // Phase 17: the player path is the only one the empty-roster guard covers. See
            // shouldSkipRosterApply — the NPC path below must keep accepting empty rosters.
            if (shouldSkipRosterApply(true, snapshot.members().size())) {
                noteEmptyPlayerRosterSkipped(snapshot.fleetHash());
            } else {
                emptyRosterSkipLogged = false;
                refreshRosterIfChanged(snapshot.fleetHash(), snapshot.members());
            }
            presenceIndicator.apply(mirrorFleet, snapshot.username());
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop fleet snapshot", ex);
        }
    }

    // ---- NPC mirror (Phase 9) ----------------------------------------------------------------

    @Override
    public void applySnapshot(CoopNpcFleetSnapshot snapshot, double sampleTimeSeconds) {
        Objects.requireNonNull(snapshot, "snapshot");
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        LocationAPI location = resolveLocation(sector, snapshot.locationId());
        if (location == null) {
            return;
        }
        try {
            ensureNpcFleet(snapshot);
            if (!isStaleSample(motionInterpolator.newestSampleTime(), sampleTimeSeconds)) {
                placeInLocation(location, snapshot.x(), snapshot.y());
                feedMotion(sampleTimeSeconds, snapshot.x(), snapshot.y(),
                        snapshot.velocityX(), snapshot.velocityY());
            }
            acceptTransponder(snapshot.transponderOn());
            acceptSensors(snapshot.sensors());
            refreshRosterIfChanged(snapshot.fleetHash(), snapshot.members());
            applyActionText(snapshot.aiAssignmentSummary());
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop NPC fleet snapshot", ex);
        }
    }

    /**
     * Pins the host's captured tooltip action line onto the mirror (Phase 9b), so a hovered NPC fleet
     * on the guest reads "Unidentified Fleet — Unknown, travelling to Jangala" rather than just
     * "Unknown". The text is produced by {@link CoopNpcActionTextCapture} on the host, because a mirror
     * has none of the AI state the vanilla tooltip derives it from.
     *
     * <p><b>Both setters, deliberately.</b> The vanilla tooltip has two independent sources for this
     * line and which one a mirror lands on depends on whether {@code createEmptyFleet(..., true)} gave
     * it a {@code ModularFleetAI}: with one, the tooltip reads
     * {@code getAI().getActionTextOverride()} (the pursuit/assignment branches above it cannot fire —
     * the mirror's tactical module never acquires a target, see {@code assertEngagementShield}); with
     * none, it falls through to {@code fleet.getNullAIActionText()}. Setting both costs one extra
     * assignment on change and makes the display independent of that detail.
     *
     * <p><b>Empty must map to null.</b> The tooltip renders {@code name + ", " + text} whenever the
     * text is non-null, so an empty-string override would paint a dangling ", " after every idle
     * fleet's name.
     *
     * <p>Rides the 1 Hz {@code NPC_FLEET_SET} only — never the 10 Hz motion path, which carries no
     * text and must stay as cheap as it is.
     */
    private void applyActionText(String actionText) {
        String text = actionText == null ? "" : actionText;
        if (text.equals(appliedActionText)) {
            return;
        }
        appliedActionText = text;
        String value = text.isEmpty() ? null : text;
        try {
            mirrorFleet.setNullAIActionText(value);
        } catch (RuntimeException ignored) {
            // Tooltip text is display-only; never abort an apply over it.
        }
        try {
            CampaignFleetAIAPI ai = mirrorFleet.getAI();
            if (ai != null) {
                ai.setActionTextOverride(value);
            }
        } catch (RuntimeException ignored) {
            // As above.
        }
        reportActionText(value);
    }

    /**
     * {@link CoopDebug}-gated counterpart to the host's capture: what text arrived, plus — once per
     * mirror — which of the two display paths is actually live on this client. That second line is the
     * only way to tell from a log whether {@code createEmptyFleet(..., true)} built a
     * {@code ModularFleetAI} for the mirror or left it AI-less, which decides which setter above is
     * doing the work.
     */
    private void reportActionText(String value) {
        if (!CoopDebug.diagnosticsEnabled()) {
            return;
        }
        try {
            if (!loggedActionTextPath) {
                loggedActionTextPath = true;
                Object ai = mirrorFleet.getAI();
                CoopLog.info(CoopFleetMirror.class, "Coop NPC mirror action-text path coopFleetId="
                        + coopFleetId + " ai=" + (ai == null ? "null" : ai.getClass().getName()));
            }
            CoopLog.info(CoopFleetMirror.class, "Coop NPC mirror action text coopFleetId=" + coopFleetId
                    + " name=" + appliedName + " text=" + (value == null ? "(none)" : value));
        } catch (RuntimeException ignored) {
            // A diagnostic must never break the apply it is reporting on.
        }
    }

    @Override
    public void applyMotion(CoopNpcFleetMotion motion, double sampleTimeSeconds) {
        Objects.requireNonNull(motion, "motion");
        // Motion is an optimization between full-set applies; if the set has not created the fleet yet
        // there is nothing to move. The next NPC_FLEET_SET will create it.
        if (mirrorFleet == null || !mirrorFleet.isAlive()) {
            return;
        }
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        LocationAPI location = resolveLocation(sector, motion.locationId());
        if (location == null) {
            return;
        }
        try {
            placeInLocation(location, motion.x(), motion.y());
            feedMotion(sampleTimeSeconds, motion.x(), motion.y(),
                    motion.velocityX(), motion.velocityY());
            acceptSensors(motion.sensors());
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop NPC fleet motion", ex);
        }
    }

    // ---- Fleet construction ------------------------------------------------------------------

    private void ensurePlayerFleet(CoopFleetSnapshot snapshot, String localPlayerFactionId) {
        if (mirrorFleet != null && mirrorFleet.isAlive()) {
            assertIgnoresOtherFleets(mirrorFleet);
            return;
        }
        String factionId = CoopPresenceIndicator.presenceFactionId(localPlayerFactionId);
        String label = CoopPresenceIndicator.presenceLabel(snapshot.username());
        mirrorFleet = Global.getFactory().createEmptyFleet(factionId, label, true);
        mirrorFleet.setAIMode(true);
        mirrorFleet.setNoAutoDespawn(true);
        // Phase 14 removed 12b's interim FLEET_IGNORED_BY_OTHER_FLEETS here. That flag only suppresses
        // other fleets' AI target SELECTION ($cfai_ignoredByOtherFleets) and is never consulted by
        // battle formation, so it bought no protection against autoresolve while it did cost the
        // mirror all hostile attention. The real protections are the per-frame engagement shield
        // (assertEngagementShield -> canBeEngaged() false) plus the flag below, with
        // CoopNpcThreatWatcher's battle-eject as the recovery for the pull-in path.
        // The battle PULL-IN path bypasses canBeEngaged() entirely: FleetInteractionDialogPluginImpl
        // .pullInNearbyFleets runs whenever the host opens any fleet dialog and joins nearby fleets
        // honoring only THIS flag (the ignoredByOtherFleets one above is never consulted there).
        // Player-faction fleets get a 700 su join radius, so without it the guest mirror is dragged
        // into host battles in ordinary play. Costs nothing: it only affects the mirror's own target
        // selection, which the snapshot driving overrides anyway.
        mirrorFleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
        mirrorFleet.getMemoryWithoutUpdate().set(PLAYER_MIRROR_TAG, true);
        // This is the only thing in the mod that creates a player mirror, so publishing it here is
        // what lets the host's per-frame consumers stop scanning the sector for it. See
        // CoopGuestMirrorHandle.
        CoopGuestMirrorHandle.publish(mirrorFleet);
        resetTracking();
        coopFleetId = "player:" + snapshot.playerId();
        appliedName = label;
        appliedFactionId = factionId;
        CoopLog.info(CoopFleetMirror.class,
                "Created coop mirror fleet for playerId=" + snapshot.playerId()
                        + " username=" + label + " faction=" + factionId);
    }

    private void ensureNpcFleet(CoopNpcFleetSnapshot snapshot) {
        String factionId = snapshot.factionId().isEmpty() ? DEFAULT_NPC_FACTION : snapshot.factionId();
        String label = snapshot.name().isEmpty() ? DEFAULT_NPC_NAME : snapshot.name();
        if (mirrorFleet != null && mirrorFleet.isAlive()) {
            refreshIdentity(label, factionId);
            assertIgnoresOtherFleets(mirrorFleet);
            return;
        }
        mirrorFleet = Global.getFactory().createEmptyFleet(factionId, label, true);
        mirrorFleet.setAIMode(true);
        mirrorFleet.setNoAutoDespawn(true);
        // See the player-mirror path above: 12b's interim FLEET_IGNORED_BY_OTHER_FLEETS was removed
        // in Phase 14 (it gates AI target selection, never battle formation).
        // See the player-mirror path: only this flag is honored by the dialog battle pull-in
        // (FleetInteractionDialogPluginImpl.pullInNearbyFleets), which never calls canBeEngaged().
        mirrorFleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
        // Store the host-side fleet id so the per-frame guest suppressor recognizes this as a sanctioned
        // mirror and never sweeps it (see CoopNpcFleetSuppressor).
        mirrorFleet.getMemoryWithoutUpdate().set(NPC_MIRROR_TAG, snapshot.coopFleetId());
        resetTracking();
        coopFleetId = snapshot.coopFleetId();
        appliedName = label;
        appliedFactionId = factionId;
        CoopLog.info(CoopFleetMirror.class,
                "Created coop NPC mirror fleet coopFleetId=" + snapshot.coopFleetId()
                        + " name=" + label + " faction=" + factionId);
    }

    /**
     * Re-asserts the battle pull-in shield on a mirror that already exists (2026-09-04). The flag is
     * set once at creation, but it lives in the fleet's memory and the memory outlives this class's
     * knowledge of it: anything that copies, restores or rebuilds a mirror's memory — the customs
     * staging path did exactly that until {@code CoopCustomsDialogStaging.restoreEngagementShield}
     * (c5caed1) — leaves the mirror joinable by {@code FleetInteractionDialogPluginImpl
     * .pullInNearbyFleets}, which consults this flag and nothing else (never {@code canBeEngaged()}).
     * Defence in depth on the snapshot path: one memory read per apply, a write only when the flag is
     * actually gone.
     */
    static void assertIgnoresOtherFleets(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return;
        }
        try {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            if (memory != null && !memory.getBoolean(MemFlags.FLEET_IGNORES_OTHER_FLEETS)) {
                memory.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
            }
        } catch (RuntimeException ignored) {
            // A mirror that cannot answer for its own memory is not worth aborting an apply over.
        }
    }

    /**
     * Keeps a live mirror's name and faction following the host's, instead of freezing them at
     * creation. Name and faction used to be write-once, which meant any host-side rename — or a mirror
     * that outlived the fleet it was created for — kept advertising an identity its roster no longer
     * matched. That is the exact shape of the 2026-08-19 report (a mirror labelled "Patrol" carrying a
     * convoy's ships), so the two now travel together on every snapshot.
     *
     * <p>Gated on an actual change: {@code setFaction} rebuilds the fleet's faction-derived display
     * state, and this runs on every 1 Hz set apply for every mirrored fleet in the sector.
     */
    private void refreshIdentity(String label, String factionId) {
        if (!label.equals(appliedName)) {
            try {
                mirrorFleet.setName(label);
                CoopLog.info(CoopFleetMirror.class, "Coop NPC mirror renamed coopFleetId=" + coopFleetId
                        + " from=" + appliedName + " to=" + label);
                appliedName = label;
            } catch (RuntimeException ignored) {
                // Display state only: never abort an apply over it.
            }
        }
        if (!factionId.equals(appliedFactionId)) {
            try {
                mirrorFleet.setFaction(factionId);
                CoopLog.info(CoopFleetMirror.class, "Coop NPC mirror re-factioned coopFleetId="
                        + coopFleetId + " from=" + appliedFactionId + " to=" + factionId);
                appliedFactionId = factionId;
            } catch (RuntimeException ignored) {
                // As above.
            }
        }
    }

    // ---- Shared driving ----------------------------------------------------------------------

    private void placeInLocation(LocationAPI location, float x, float y) {
        String targetId = location.getId();
        boolean locationChanged = !Objects.equals(targetId, lastLocationId);
        if (locationChanged) {
            LocationAPI current = mirrorFleet.getContainingLocation();
            if (current != null && current != location) {
                current.removeEntity(mirrorFleet);
            }
            if (mirrorFleet.getContainingLocation() != location) {
                location.addEntity(mirrorFleet);
            }
            // A location change is the hard-cut signal (system jumps change location): the buffer
            // restarts here so the interpolator can never glide across a jump.
            motionInterpolator.clear();
            resetSpeedProbeTracking();
            mirrorFleet.setLocation(x, y);
            lastLocationId = targetId;
            CoopLog.info(CoopFleetMirror.class, "Coop mirror fleet moved to location " + targetId);
        }
        // No periodic setLocation snap anymore: Phase 29 M1's advanceMotion places the fleet every
        // frame from the sample buffer, so there is nothing left for a 1 Hz correction to correct —
        // that snap WAS the visible teleport (see the plan's Phase 29 banner).
    }

    /**
     * Queues one received motion sample for {@link #advanceMotion}. A teleport-scale jump inside the
     * same location (rare: respawn relocation, console moves) cuts immediately rather than gliding.
     *
     * <p>No shield assert here on purpose: the per-frame pump pass (assertEngagementShield) is the
     * single authoritative shield. Asserting it from the driving path as well would fight the
     * targeted release every time a snapshot or motion record arrived (10 Hz), leaving the fleet the
     * player explicitly targeted permanently unengageable.
     */
    private void feedMotion(double sampleTimeSeconds, float x, float y,
                            float velocityX, float velocityY) {
        CoopMotionInterpolator.AddResult result =
                motionInterpolator.addSample(sampleTimeSeconds, x, y, velocityX, velocityY);
        if (result == CoopMotionInterpolator.AddResult.STALE) {
            return;
        }
        if (result == CoopMotionInterpolator.AddResult.TELEPORT) {
            mirrorFleet.setLocation(x, y);
            resetSpeedProbeTracking();
        }
        if (CoopDebug.diagnosticsEnabled()) {
            if (speedProbeHasSample && result == CoopMotionInterpolator.AddResult.ADDED) {
                CoopMotionSpeedProbe.INSTANCE.recordAuthority(
                        Math.hypot(x - speedProbeSampleX, y - speedProbeSampleY));
            }
            speedProbeHasSample = true;
            speedProbeSampleX = x;
            speedProbeSampleY = y;
        }
    }

    /** {@code transponderOn} rides the snapshot streams; motion-only records do not carry it. */
    private void acceptTransponder(Boolean transponderOn) {
        if (transponderOn == null) {
            return;
        }
        try {
            mirrorFleet.setTransponderOn(transponderOn);
        } catch (RuntimeException ignored) {
            // transponder state is best-effort for the mirror
        }
    }

    /**
     * Phase 29 M1, once per frame from the pump: places the mirror at the buffered trajectory
     * evaluated at the shared render cursor ({@link CoopMotionTimeline}). Velocity is written every
     * frame because engine consumers read it (trails, other fleets' intercept AI); facing tracks the
     * interpolated velocity above {@link #FACING_SPEED_FLOOR} and otherwise holds, and a parked pose
     * (starvation ladder fully decayed) also holds facing.
     */
    @Override
    public void advanceMotion(double cursorSeconds) {
        if (mirrorFleet == null || !mirrorFleet.isAlive() || Double.isNaN(cursorSeconds)) {
            return;
        }
        CoopMotionInterpolator.Pose pose = motionInterpolator.evaluate(cursorSeconds);
        if (pose == null) {
            return;
        }
        try {
            mirrorFleet.setLocation(pose.x(), pose.y());
            mirrorFleet.setVelocity(pose.velocityX(), pose.velocityY());
            float speedSq = pose.velocityX() * pose.velocityX()
                    + pose.velocityY() * pose.velocityY();
            if (!pose.parked() && speedSq >= FACING_SPEED_FLOOR * FACING_SPEED_FLOOR) {
                mirrorFleet.setFacing(
                        (float) Math.toDegrees(Math.atan2(pose.velocityY(), pose.velocityX())));
            }
            if (CoopDebug.diagnosticsEnabled()) {
                if (speedProbeHasRendered) {
                    double moved = Math.hypot(pose.x() - speedProbeRenderedX,
                            pose.y() - speedProbeRenderedY);
                    // Defensive: a cursor re-seat after an outage renders one large hop; that is a
                    // cut, not speed, so it must not pollute the window.
                    if (moved < CoopMotionInterpolator.TELEPORT_DISTANCE) {
                        CoopMotionSpeedProbe.INSTANCE.recordRendered(moved);
                    }
                }
                speedProbeHasRendered = true;
                speedProbeRenderedX = pose.x();
                speedProbeRenderedY = pose.y();
            }
        } catch (RuntimeException ignored) {
            // Hot path (every frame per mirror): a transient engine hiccup must never kill the pump.
        }
    }

    /** Invalidates both speed-probe trackers (hard cuts: teleport, location change, teardown). */
    private void resetSpeedProbeTracking() {
        speedProbeHasRendered = false;
        speedProbeHasSample = false;
    }

    /**
     * Re-asserts the ~1 s {@code noCombat} fader that keeps the mirror out of engine-formed battles.
     *
     * <p>Battle initiation between AI fleets lives only in {@code BaseLocation.advance}'s pair loop,
     * whose first gate is {@code CampaignFleet.canBeEngaged()} — false while that fader is live. The
     * mirror driving path deliberately does <em>not</em> refresh it (see {@link #driveMovement}), so
     * the pump calling this from every frame — including while paused — is the whole shield: it never
     * depends on traffic arriving, and it survives network stalls, location transitions and
     * unresolvable locations that make the apply paths return early.
     *
     * <p><b>The call is rate-limited, the pass is not.</b> {@code setNoEngaging(1f)} allocates a fresh
     * {@code Fader} every time, and the fader it builds does not expire until ~1 s after the last call
     * — so doing it every frame for every mirror bought nothing and cost 43 allocations per frame in a
     * busy system (2580/s). Re-asserting every {@link #SHIELD_REASSERT_INTERVAL_MILLIS} keeps the
     * fader live with a 4x margin. The release path stays edge-triggered and, because it clears the
     * stamp, the shield goes back up on the very next frame rather than waiting out an interval.
     *
     * <p>This unconditional form is what the <b>partner player mirror</b> gets on both roles: it is
     * the PvP block (neither player can engage the other's mirror) and, on the host, the block that
     * keeps the guest's mirror out of NPC battles. It is never released.
     *
     * @param nowMillis the pump's wall clock — the same one the rest of the frame is timed on, and
     *                  one that keeps running while the campaign is paused (as this must)
     */
    public void assertEngagementShield(long nowMillis) {
        if (mirrorFleet == null) {
            return;
        }
        shieldReleased = false;
        if (!shouldReassertShield(shieldAssertedAtMillis, nowMillis)) {
            return;
        }
        shieldAssertedAtMillis = nowMillis;
        try {
            mirrorFleet.setNoEngaging(1f);
        } catch (RuntimeException ignored) {
            // hot path: never abort the frame over the shield
        }
    }

    /**
     * The re-assert gate, pure so the timing is unit-tested rather than reasoned about: assert on the
     * first call and whenever the last one is at least an interval old. A clock that moved backwards
     * re-asserts rather than stalling with the shield down.
     */
    static boolean shouldReassertShield(long lastAssertAtMillis, long nowMillis) {
        return lastAssertAtMillis == NEVER_ASSERTED
                || nowMillis < lastAssertAtMillis
                || nowMillis - lastAssertAtMillis >= SHIELD_REASSERT_INTERVAL_MILLIS;
    }

    /**
     * The NPC-mirror form of the shield: asserted every frame as above, <em>except</em> for the one
     * mirror the local player has explicitly picked as their interaction target, whose shield is
     * cleared so the player can actually engage it.
     *
     * <p>Why the release is needed: player-initiated encounters run through {@code BaseLocation
     * .advance}'s "player combat initiation" block, which requires both the player fleet and the
     * target to pass {@code canBeEngaged()}. With the shield up the block silently skips the mirror —
     * the interaction dialog is never constructed and the right-click appears to do nothing.
     *
     * <p>Why the other mirrors keep it: not because mirror-initiated battles are likely on the guest
     * (the mirrors' AI is suppressed and they are driven entirely by host snapshots), but as
     * defense-in-depth against any engine path that forms a battle around them. The release is scoped
     * as narrowly as it can be — the player's own explicit engagement intent, one fleet at a time.
     *
     * @param playerInteractionTarget the local player fleet's current interaction target, or null when
     *                                there is none (or a dialog already owns the screen, in which case
     *                                the vanilla dialog — which never reads the fader — takes over)
     */
    @Override
    public void assertEngagementShield(Object playerInteractionTarget, long nowMillis) {
        if (mirrorFleet == null) {
            return;
        }
        if (shouldReleaseShield(mirrorFleet, playerInteractionTarget)) {
            releaseEngagementShield();
            return;
        }
        assertEngagementShield(nowMillis);
    }

    /**
     * The pure decision behind {@link #assertEngagementShield(Object)}: release only for the exact
     * fleet instance the player is walking into. Static and identity-based so the registry's headless
     * fakes decide the same way the real mirror does.
     */
    static boolean shouldReleaseShield(Object mirrorFleet, Object playerInteractionTarget) {
        return mirrorFleet != null && mirrorFleet == playerInteractionTarget;
    }

    /**
     * Clears the shield with the vanilla idiom: {@code setNoEngaging(0f)} builds a zero-duration fader,
     * whose {@code fadeIn()} forces it straight to IDLE, so the engine nulls it on the mirror's next
     * advance and {@code canBeEngaged()} is true a frame later.
     *
     * <p>Edge-triggered on purpose. {@code setNoEngaging} also re-pulses the engine's {@code
     * noCombatPulse} fader, which drives the "cannot be engaged" flicker; calling it every frame while
     * the player closes in would keep that pulse lit for the whole approach.
     */
    private void releaseEngagementShield() {
        if (shieldReleased) {
            return;
        }
        shieldReleased = true;
        // Clear the stamp so re-asserting is immediate when the player stops targeting this mirror,
        // instead of leaving it engageable for up to one re-assert interval.
        shieldAssertedAtMillis = NEVER_ASSERTED;
        try {
            mirrorFleet.setNoEngaging(0f);
        } catch (RuntimeException ignored) {
            // hot path: never abort the frame over the shield
        }
        CoopLog.info(CoopFleetMirror.class, "Coop NPC mirror engagement shield released for the"
                + " player's interaction target: " + safeMirrorName());
    }

    private String safeMirrorName() {
        try {
            String name = mirrorFleet.getName();
            return name == null ? "" : name;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /**
     * Records the sensor identity that arrived with a snapshot or motion record and applies it
     * immediately. A record from a peer that could not read its own fleet ({@code !isKnown()}) is
     * ignored rather than applied, so one bad read never blanks a mirror's detectability.
     */
    private void acceptSensors(CoopSensorSync.Profile incoming) {
        if (incoming == null || !incoming.isKnown()) {
            return;
        }
        sensors = incoming;
        CoopSensorSync.apply(mirrorFleet, sensors);
    }

    /**
     * Re-asserts the last replicated sensor identity, once per frame from the pump's mirror pass
     * (Phase 14b). This is not belt-and-braces: {@code CampaignFleet.updateCounts()} rewrites the
     * mirror's {@code sensorStrength} from its roster <em>every frame</em> with no opt-out
     * ({@code CampaignFleet.java:1029}, called unconditionally from {@code advance} at :794), and the
     * local terrain plugins re-apply their own 0.1-day detectability mods to the mirror every frame
     * because it sits exactly where the fleet it mirrors sits. Applying only on the 10 Hz driving path
     * left the mirror carrying engine-derived values five frames out of six — which is what made NPC
     * mirrors flicker between faction-red and grey on the guest. Cheap: three stat reads when nothing
     * has moved. See {@link CoopSensorSync}.
     */
    @Override
    public void assertSensorState() {
        if (mirrorFleet == null || !sensors.isKnown()) {
            return;
        }
        CoopSensorSync.apply(mirrorFleet, sensors);
    }

    /**
     * The roster gate. A structural hash equal to the last one applied means "same ship set", so the
     * roster stands and only the repair state moves.
     *
     * <p><b>The gate is also a latch, which is why a bad rebuild has to be retried (2026-08-19).</b>
     * Whatever roster this commits is what the mirror wears until the <em>host</em> fleet's own roster
     * changes — a partial build (an unresolvable variant that {@link #createMember} could not turn into
     * a ship) hashes exactly the same as the good snapshot it came from, so committing it means the
     * mirror never gets another chance. One retry costs a single extra rebuild and covers the transient
     * causes; after that the hash is accepted so an genuinely unbuildable roster cannot rebuild forever.
     *
     * <p><b>The gate also has to notice the roster shrinking underneath it (2026-09-04).</b> A local
     * battle can destroy ships out of a live mirror's {@code FleetData} — the freeze
     * {@code CoopFleetMirrorRegistry} holds afterwards promises that the host's authoritative set
     * resurrects an unreported kill once the freeze times out, and that promise was not kept: the
     * thawed apply arrives with the host's unchanged hash, takes this fast path, and
     * {@link #updateMemberState} bails on the member-count mismatch, so the mirror wears its shrunken
     * roster until the host fleet's own roster changes. Comparing the live count against
     * {@link #builtMemberIds} (what the last rebuild actually produced, <em>not</em> what the snapshot
     * asked for) forces the rebuild without re-opening the unbuildable-roster storm the latch above
     * exists to prevent.
     */
    private void refreshRosterIfChanged(String fleetHash, List<CoopFleetSnapshot.Member> members) {
        if (Objects.equals(fleetHash, lastFleetHash)) {
            // One read of the live roster, shared with the update below: this runs at snapshot rate
            // for every mirrored fleet in the sector.
            List<FleetMemberAPI> current = mirrorFleet.getFleetData().getMembersListCopy();
            if (rosterStillIntact(current.size(), builtMemberCount)) {
                // Same ship set: keep the roster and track the slow-moving repair state in place. The
                // hash is structural on purpose — CR/hull recovery used to flip it every second or two
                // per damaged fleet and the resulting rebuild storm dropped the guest to 39 fps
                // (2026-08-17); see CoopFleetSnapshot.computeFleetHash.
                updateMemberState(current, members);
                return;
            }
        }
        boolean complete = rebuildRoster(members, fleetHash);
        if (shouldCommitRoster(complete, fleetHash, retriedFleetHash)) {
            lastFleetHash = fleetHash;
            retriedFleetHash = null;
        } else {
            lastFleetHash = null;
            retriedFleetHash = fleetHash;
        }
    }

    /**
     * The pure half of the gate above: commit a rebuilt roster (so the mirror stops rebuilding it) if
     * it came out complete, or if this hash has already had its one retry.
     */
    static boolean shouldCommitRoster(boolean complete, String fleetHash, String retriedFleetHash) {
        return complete || Objects.equals(fleetHash, retriedFleetHash);
    }

    /**
     * True when the live mirror still holds every ship the last rebuild built, so the same-hash fast
     * path is safe. A negative {@code liveMemberCount} means the fleet could not be read at all, which
     * is not evidence of a shrink and must not trigger a rebuild.
     */
    static boolean rosterStillIntact(int liveMemberCount, int builtMemberCount) {
        return liveMemberCount < 0 || builtMemberCount < 0 || liveMemberCount == builtMemberCount;
    }

    /**
     * The empty-roster guard (Phase 17). A wiped client streams 0-member {@code FLEET_SNAPSHOT}s for
     * the few seconds between "no ships left" and vanilla's {@code CampaignState.showShuttleDialog()}
     * replacing its fleet. Committing that roster to the partner's mirror hands the engine a 0-member
     * {@link CampaignFleetAPI}, and {@code CampaignFleet.advance()} despawns one of those with
     * {@code FleetDespawnReason.NO_MEMBERS} — a branch {@code setNoAutoDespawn(true)} does <em>not</em>
     * cover (it guards only {@code fadeAndExpire}). The 2026-08-19 live wipe survived it only because
     * the shared pause held for the whole window; at WAN latency the pause lands a round trip late and
     * the unpaused frames despawn and recreate the mirror in a loop, spraying spurious
     * {@code reportFleetDespawned} events at vanilla listeners. Skipping the apply keeps the last
     * non-empty roster on screen until the respawned fleet's snapshot arrives, which is also the better
     * thing to look at.
     *
     * <p><b>Player mirrors only, deliberately.</b> This class also backs the Phase 9 NPC mirrors, and
     * the Phase 15 battle-result teardown <em>legitimately</em> commits a 0-member roster to a
     * destroyed NPC mirror moments before removing it ({@code roster refreshed to 0 of 0 ship(s)} on
     * {@code BATTLE_END}). A blanket guard would leave those wrecks wearing their pre-battle rosters
     * for the frames before removal, so the NPC path stays unguarded.
     */
    static boolean shouldSkipRosterApply(boolean playerMirror, int memberCount) {
        return playerMirror && memberCount == 0;
    }

    /**
     * The other half of the empty-roster guard: do not <em>create</em> a player mirror out of a
     * 0-member snapshot either.
     *
     * <p>{@link #shouldSkipRosterApply} keeps a live mirror's last roster through the wipe window, but
     * when there is no mirror yet (a session that starts, resumes or rejoins mid-wipe) the same
     * snapshot used to build an empty {@link CampaignFleetAPI} and {@link #placeInLocation} added it to
     * the world before the roster was ever considered. {@code CampaignFleet.advance()} despawns a
     * member-less fleet as {@code FleetDespawnReason.NO_MEMBERS} — the branch
     * {@code setNoAutoDespawn(true)} does not cover — so the next snapshot found the mirror dead and
     * built another one: the create/despawn loop at snapshot rate, complete with a
     * {@code reportFleetDespawned} to every vanilla listener, that the guard was written to prevent.
     * Creation waits for a roster worth showing.
     */
    static boolean shouldDeferPlayerFleetCreation(boolean mirrorAlive, int memberCount) {
        return !mirrorAlive && memberCount == 0;
    }

    /**
     * True when a record's stream stamp is at or behind the newest sample already buffered, i.e. it
     * describes a moment the mirror has moved past.
     *
     * <p>The 1 Hz {@code NPC_FLEET_SET} rides TCP while the motion stream rides UDP, so a set built
     * before a jump can land after the motion datagrams that already carried the fleet through it.
     * {@link CoopMotionInterpolator#addSample} drops the stale sample by itself, but the placement in
     * front of it does not: it would move the mirror back to the older location, clear the buffer and
     * hard-set the old position, only for the next datagram to restart it all one interval later.
     * Roster, identity and sensor state are still applied from a stale set — those are latest-wins
     * facts, not positions.
     */
    static boolean isStaleSample(double newestSampleTime, double sampleTimeSeconds) {
        return !Double.isNaN(newestSampleTime) && sampleTimeSeconds <= newestSampleTime;
    }

    /** One line per wipe episode, not per 10 Hz snapshot; rare enough to stay at INFO. */
    private void noteEmptyPlayerRosterSkipped(String fleetHash) {
        if (emptyRosterSkipLogged) {
            return;
        }
        emptyRosterSkipLogged = true;
        CoopLog.info(CoopFleetMirror.class,
                "Coop player mirror kept its last roster: empty snapshot ignored (fleet wipe window)"
                        + " coopFleetId=" + coopFleetId + " fleetHash=" + fleetHash);
    }

    /**
     * Applies CR/hull onto the existing mirror members without a rebuild. Members are matched by their
     * sender-side id through {@link #builtMemberIds}, falling back to list position when the ids do not
     * line up one-for-one (a partially built roster, an id-less snapshot).
     *
     * <p><b>Position alone is not enough (2026-09-04).</b> The structural hash is order-independent, so
     * a sender that merely reorders its fleet ships the same hash with a different member order and the
     * mirror never rebuilds — a "transient" order mismatch that in fact lasts until the ship set itself
     * changes, with every ship wearing a sibling's CR and hull the whole time.
     *
     * <p><b>The CR write must be followed by {@code setStatUpdateNeeded(true)} (Phase 14b).</b>
     * {@code RepairTracker.setCR(float)} is a bare field assignment — it invalidates nothing — while
     * {@code FleetMember.getMemberStrength()} caches its CR-derived result in a {@code cachedStrength}
     * field cleared only by {@code setStatUpdateNeeded(true)}, {@code updateStats()} or
     * {@code readResolve()} ({@code probe/FleetMember.java:278-284, 640-661}). Without the
     * invalidation a mirror's engine-visible strength stayed frozen at roster-build time, so
     * {@code Misc.getMemberStrength} — and through it every {@code pickEncounterOption} strength
     * comparison a hostile makes about the guest — judged a wrecked fleet as if it were fresh. Hull
     * fraction needs no invalidation: {@code Misc.getMemberStrength} reads
     * {@code getStatus().getHullFraction()} live on every call.
     *
     * <p>Invalidation is gated on an actual change so the deferred {@code updateStats()} (a full
     * per-member stat rebuild, which then cascades into one fleet sync) does not run on every 10 Hz
     * apply for a fleet whose CR is not moving. <b>The gate compares against the last CR that fired it,
     * not the last CR written (2026-08-25).</b> CR is written on every apply — that part is a bare field
     * assignment and costs nothing — but comparing the incoming value against what the previous apply
     * wrote made the gate a ratchet rather than a filter: a ship recovering CR one wire step at a time
     * moves 0.001 per sample ({@link CoopFleetCodec#FRACTION_STEP} quantizes the wire onto that grid, so
     * "every step is under the epsilon" is the normal regime, not a fluke), never trips 0.005 on any
     * single step, and the mirror's {@code cachedStrength} stays frozen while the true drift grows
     * without bound. Holding the reference still until the gate fires keeps the storm protection —
     * per-tick noise around a stable CR still moves no reference and still invalidates nothing — while
     * making a slow monotonic recovery fire once per 0.005 of real movement.
     */
    private void updateMemberState(List<FleetMemberAPI> current,
                                   List<CoopFleetSnapshot.Member> members) {
        if (current.size() != members.size()) {
            return;
        }
        float[] references = crInvalidationReferences(current.size());
        int[] pairing = memberPairing(builtMemberIds, members);
        for (int i = 0; i < current.size(); i++) {
            try {
                FleetMemberAPI member = current.get(i);
                CoopFleetSnapshot.Member state = members.get(pairing == null ? i : pairing[i]);
                float cr = state.cr();
                if (Float.isNaN(references[i])) {
                    references[i] = member.getRepairTracker().getCR();
                }
                boolean crChanged = crDiffers(references[i], cr);
                member.getRepairTracker().setCR(cr);
                member.getStatus().setHullFraction(state.hullFraction());
                if (crChanged) {
                    member.setStatUpdateNeeded(true);
                    references[i] = cr;
                }
            } catch (RuntimeException ignored) {
                // repair state on a mirror is display-only; never abort the update over it
            }
        }
    }

    /** The per-slot reference row, seated as all-unknown on the first apply after a roster rebuild. */
    private float[] crInvalidationReferences(int size) {
        if (crInvalidationReferences == null || crInvalidationReferences.length != size) {
            crInvalidationReferences = new float[size];
            Arrays.fill(crInvalidationReferences, Float.NaN);
        }
        return crInvalidationReferences;
    }

    /**
     * CR is a 0..1 fraction; half a percent is below anything the strength formula reacts to.
     *
     * @param reference the CR that last fired the gate for this slot, <em>not</em> the CR last written
     */
    static boolean crDiffers(float reference, float incoming) {
        return Math.abs(reference - incoming) > 0.005f;
    }

    /**
     * The gate's reference advance, split out so the ratchet has a pure surface to be tested on: the
     * reference moves only when the gate fires, so sub-epsilon steps accumulate against a fixed point
     * instead of chasing the value that just arrived.
     */
    static float nextCrReference(float reference, float incoming) {
        return crDiffers(reference, incoming) ? incoming : reference;
    }

    /**
     * Which snapshot member describes each live mirror slot: {@code result[slot]} indexes
     * {@code members}. Null means "pair by position" — either because the order already is the
     * identity (the overwhelmingly common case, and no reason to walk an array for it) or because the
     * ids are not resolvable, which is what every pre-2026-09-04 build assumed unconditionally.
     *
     * <p>Null is returned rather than a partial map whenever the two sides are not a clean one-to-one
     * by id: differing sizes, a blank id, a duplicate id, or a slot whose ship is not in the snapshot
     * at all. Half a permutation would be worse than the positional fallback, because the unmapped
     * slots would silently take whatever index was left.
     */
    static int[] memberPairing(List<String> builtMemberIds, List<CoopFleetSnapshot.Member> members) {
        if (builtMemberIds == null || members == null || builtMemberIds.size() != members.size()) {
            return null;
        }
        Map<String, Integer> byId = new HashMap<>(members.size() * 2);
        for (int i = 0; i < members.size(); i++) {
            String id = members.get(i).fleetMemberId();
            if (id == null || id.isEmpty() || byId.put(id, i) != null) {
                return null;
            }
        }
        int[] pairing = new int[builtMemberIds.size()];
        boolean permuted = false;
        for (int slot = 0; slot < pairing.length; slot++) {
            Integer index = byId.get(builtMemberIds.get(slot));
            if (index == null) {
                return null;
            }
            pairing[slot] = index;
            permuted |= index != slot;
        }
        return permuted ? pairing : null;
    }

    /** @return true when every member in the snapshot was actually built and attached. */
    private boolean rebuildRoster(List<CoopFleetSnapshot.Member> members, String fleetHash) {
        // Diagnostic counter only; a no-op unless the frame profiler is enabled.
        CoopFrameProfiler.noteRosterRebuild();
        // New ship set, new slots: the CR gate must not judge them against the old set's references.
        // Dropping the row here rather than on length change covers a rebuild that keeps the count.
        crInvalidationReferences = null;
        for (FleetMemberAPI existing : mirrorFleet.getFleetData().getMembersListCopy()) {
            mirrorFleet.getFleetData().removeFleetMember(existing);
        }
        List<String> builtIds = new ArrayList<>(members.size());
        for (CoopFleetSnapshot.Member member : members) {
            if (addMirrorMember(member)) {
                builtIds.add(member.fleetMemberId());
            }
        }
        builtMemberIds = builtIds;
        builtMemberCount = builtIds.size();
        int built = builtIds.size();
        mirrorFleet.getFleetData().setSyncNeeded();
        CoopLog.info(CoopFleetMirror.class,
                "Coop mirror fleet roster refreshed to " + built + " of " + members.size()
                        + " ship(s) coopFleetId=" + coopFleetId + " fleetHash=" + fleetHash);
        reportRoster(members, fleetHash);
        return built == members.size();
    }

    /**
     * {@link CoopDebug}-gated counterpart to the host's "Coop host fleet roster" line: what the
     * snapshot <em>claimed</em> next to what this client actually created, post-fallback. The pair of
     * lines is the whole diagnostic — it separates "the host captured the wrong ships" from "the guest
     * failed to build the right ones", which is otherwise only visible by opening two saves.
     */
    private void reportRoster(List<CoopFleetSnapshot.Member> members, String fleetHash) {
        if (!CoopDebug.diagnosticsEnabled()) {
            return;
        }
        try {
            List<String> builtHullIds = new ArrayList<>();
            for (FleetMemberAPI member : mirrorFleet.getFleetData().getMembersListCopy()) {
                builtHullIds.add(member == null ? "" : member.getHullId());
            }
            CoopLog.info(CoopFleetMirror.class, "Coop mirror roster rebuilt coopFleetId=" + coopFleetId
                    + " name=" + appliedName + " faction=" + appliedFactionId
                    + " snapshot=" + members.size() + " [" + CoopRosterSummary.ofMembers(members) + "]"
                    + " built=" + builtHullIds.size() + " [" + CoopRosterSummary.ofHullIds(builtHullIds)
                    + "] fleetHash=" + fleetHash);
        } catch (RuntimeException ignored) {
            // A diagnostic must never be able to break the apply it is reporting on.
        }
    }

    /** @return true when the member was created and attached. */
    private boolean addMirrorMember(CoopFleetSnapshot.Member member) {
        FleetMemberAPI created = createMember(member);
        if (created == null) {
            return false;
        }
        try {
            mirrorFleet.getFleetData().addFleetMember(created);
            if (!member.shipName().isEmpty()) {
                created.setShipName(member.shipName());
            }
            applyReplicatedHullMods(created, member);
            created.getRepairTracker().setCR(member.cr());
            created.getStatus().setHullFraction(member.hullFraction());
            return true;
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class,
                    "Failed to attach coop mirror member " + member.fleetMemberId(), ex);
            return false;
        }
    }

    /**
     * Re-applies the sender's d-mods and S-mods on the freshly built stock ship (Phase 16). Never
     * fatal: a mirror that stays cosmetically clean is exactly the pre-Phase-16 behaviour and is
     * strictly better than a roster short by one ship.
     */
    private void applyReplicatedHullMods(FleetMemberAPI created, CoopFleetSnapshot.Member member) {
        try {
            CoopShipMods.apply(member.dmodIds(), member.sModIds(), member.sModdedBuiltInIds(),
                    engineVariantOps(created));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop mirror hullmods (dmods="
                    + member.dmodIds() + " sMods=" + member.sModIds()
                    + " sModdedBuiltIns=" + member.sModdedBuiltInIds() + ") to coopFleetId="
                    + coopFleetId + " fleetMemberId=" + member.fleetMemberId(), ex);
        }
    }

    /**
     * The engine half of the permanent-hullmod apply.
     *
     * <p><b>{@code clone()} is the invariant, and it stays whatever the engine happens to do.</b> The
     * hazard is that a member's variant can be the shared spec-store object for that stock variant, in
     * which case {@code addPermaMod} would not mod one mirror ship — it would mod every ship of that
     * variant in this player's universe, their own fleet and every market's stock included, for the
     * rest of the session. As of 0.98a the engine happens to clone it first
     * ({@code FleetMember.updateSpecIfNeeded} does {@code this.variant = this.variant.clone()} for the
     * {@code SHIP} branch, {@code tmp_ff_analysis/probe/FleetMember.java:178-184}), so this path is
     * doubly safe today — but that is an implementation detail of a method that has changed before,
     * and the cost of the copy is one variant per mirror ship per rebuild. Copy, mutate the copy,
     * install the copy. The {@code REFIT} source and the hull-swap-then-mods order mirror
     * {@code ShipRecoverySpecial}'s own recovery path
     * ({@code api_pristine/.../ShipRecoverySpecial.java:389-405, 405-428}).
     *
     * <p>{@code addSModdedBuiltIn} writes through {@code getSModdedBuiltIns()} because that is the
     * only surface the API exposes for that set — {@code HullVariantSpec} returns the live
     * {@code LinkedHashSet} and populates it exactly this way when reading a variant file, and
     * {@code addPermaMod(id, true)} is the wrong tool for a built-in (it would also push the id into
     * {@code permaMods}, which the engine reserves for non-built-in mods). It is a mirror's throwaway
     * runtime variant, never persisted gameplay state.
     */
    private static CoopShipMods.VariantOps<ShipVariantAPI> engineVariantOps(FleetMemberAPI member) {
        return new CoopShipMods.VariantOps<ShipVariantAPI>() {
            @Override
            public ShipVariantAPI currentVariant() {
                return member.getVariant();
            }

            @Override
            public ShipVariantAPI copyOf(ShipVariantAPI variant) {
                return variant.clone();
            }

            @Override
            public void setDamagedHull(ShipVariantAPI variant) {
                variant.setSource(VariantSource.REFIT);
                // The engine's own damaged-hull swap: hullSpec -> Misc.getDHullId(spec), i.e. the
                // generated "<hullId>_default_D" both installs build at load from the same ship data.
                DModManager.setDHull(variant);
            }

            @Override
            public void addDmod(ShipVariantAPI variant, String hullModId) {
                variant.removeSuppressedMod(hullModId);
                variant.addPermaMod(hullModId, false);
            }

            @Override
            public void addSMod(ShipVariantAPI variant, String hullModId) {
                variant.removeSuppressedMod(hullModId);
                variant.addPermaMod(hullModId, true);
            }

            @Override
            public void addSModdedBuiltIn(ShipVariantAPI variant, String hullModId) {
                variant.getSModdedBuiltIns().add(hullModId);
            }

            @Override
            public void install(ShipVariantAPI variant) {
                member.setVariant(variant, false, true);
            }
        };
    }

    /**
     * Builds one mirror ship, <b>checking that the id exists before asking for it</b> and checking
     * what came back afterwards.
     *
     * <p>This used to lean on exceptions — try the variant id, catch, try {@code hullId + "_Hull"},
     * catch, give up — and that is exactly why the 2026-08-19 failure was silent. The engine does not
     * reject an unknown variant id. {@code FleetMember.updateSpecIfNeeded}
     * ({@code tmp_ff_analysis/probe/FleetMember.java:177-182}) <em>constructs and prints</em> a
     * {@code RuntimeException} ("[x] is not a valid ship variant id") without throwing it, then
     * substitutes {@code settings.json}'s {@code "errorShipVariant"} — which vanilla sets to
     * {@code nebula_Standard} ({@code data/config/settings.json:143}). So a fleet the host had just
     * inflated (every ship autofit onto a runtime variant named after the <em>host's</em> fleet id,
     * see {@link CoopFleetSnapshot.Member}) arrived here, hit the first branch, threw nothing, and
     * became N copies of the placeholder hull — a patrol wearing six identical civilian freighters,
     * with no warning in the log because nothing had failed as far as this code could tell.
     *
     * <p>Two rules now. Ask only for ids this install actually has ({@link #resolveCreationId}), and
     * verify the ship that comes back is the ship that was asked for ({@link #isPlausibleHull}) —
     * belt and braces, because the substitution is silent by design and the sender's spec store is
     * only guaranteed to match ours by the handshake's manifest check.
     */
    private FleetMemberAPI createMember(CoopFleetSnapshot.Member member) {
        String creationId = resolveCreationId(member.variantId(), member.hullId(),
                CoopFleetMirror::variantExists);
        if (creationId.isEmpty()) {
            CoopLog.warn(CoopFleetMirror.class, "Coop mirror member has no variant or hull id this"
                    + " install can resolve, skipping it: coopFleetId=" + coopFleetId
                    + " fleetMemberId=" + member.fleetMemberId()
                    + " variantId=" + member.variantId() + " hullId=" + member.hullId());
            return null;
        }
        FleetMemberAPI created;
        try {
            created = Global.getFactory().createFleetMember(FleetMemberType.SHIP, creationId);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to create coop mirror member from "
                    + creationId + " (coopFleetId=" + coopFleetId + ")", ex);
            return null;
        }
        if (created == null) {
            CoopLog.warn(CoopFleetMirror.class, "Coop mirror member came back null from " + creationId
                    + " (coopFleetId=" + coopFleetId + ")");
            return null;
        }
        String createdHullId = safeHullId(created);
        if (!isPlausibleHull(createdHullId, member.hullId())) {
            CoopLog.warn(CoopFleetMirror.class, "Coop mirror member SUBSTITUTED by the engine and"
                    + " discarded: asked for " + creationId + " (hullId=" + member.hullId()
                    + ") and got hullId=" + createdHullId + " — coopFleetId=" + coopFleetId
                    + " fleetMemberId=" + member.fleetMemberId()
                    + " variantId=" + member.variantId()
                    + ". A placeholder ship reached the roster; the mirror will be short by one.");
            return null;
        }
        return created;
    }

    /**
     * Which id to hand {@code createFleetMember}: the streamed variant if this install has it, else
     * the streamed hull's empty {@code "_Hull"} variant, else the hull's non-D parent's, else nothing.
     *
     * <p><b>Every candidate is checked as a <em>variant</em> id, including the {@code "_Hull"} ones.</b>
     * That is not belt-and-braces: the auto {@code "<hullId>_Hull"} variants are built at load time
     * and, critically, a generated D hull does <em>not</em> get one. {@code ShipHullSpecLoader} clones
     * the parent spec into {@code <id>_default_D} and registers the hull
     * ({@code ShipHullSpecLoader.o00000}, CFR :113-128) but both call sites build the auto
     * {@code "_Hull"} variant from the <em>parent</em> spec (CFR :405 and
     * {@code ShipHullSpreadsheetLoader} CFR :190). So {@code falcon_default_D} is a real hull with no
     * {@code falcon_default_D_Hull} variant behind it — asking for one is another silent Nebula.
     * Checking the hull spec instead would happily wave that through; checking
     * {@code doesVariantExist} is what {@code FleetMember} itself will look up.
     */
    static String resolveCreationId(String variantId, String hullId,
                                    java.util.function.Predicate<String> variantExists) {
        if (variantId != null && !variantId.isEmpty() && variantExists.test(variantId)) {
            return variantId;
        }
        if (hullId == null || hullId.isEmpty()) {
            return "";
        }
        for (String candidate : List.of(hullId, CoopFleetSnapshotFactory.baseHullId(hullId))) {
            String hullVariantId = candidate + "_Hull";
            if (!candidate.isEmpty() && variantExists.test(hullVariantId)) {
                return hullVariantId;
            }
        }
        return "";
    }

    /**
     * Whether the ship the engine built is the ship that was asked for.
     *
     * <p>Compared on the base hull, because a legitimate build can differ by exactly the D-hull
     * suffix: the host streams the live hull ({@code falcon_default_D}, swapped in by
     * {@code DModManager.setDHull} during inflation) alongside the stock variant it was autofit from
     * ({@code falcon_Assault}), whose hull is the clean {@code falcon}. Losing the d-mods is the
     * accepted trade; getting a Nebula is not.
     */
    static boolean isPlausibleHull(String createdHullId, String requestedHullId) {
        String created = CoopFleetSnapshotFactory.baseHullId(createdHullId == null ? "" : createdHullId);
        String requested =
                CoopFleetSnapshotFactory.baseHullId(requestedHullId == null ? "" : requestedHullId);
        // Nothing to check against: the sender could not name a hull either, so accept what we got
        // rather than discard a ship over a missing cross-check.
        return requested.isEmpty() || created.equals(requested);
    }

    private static String safeHullId(FleetMemberAPI member) {
        try {
            String hullId = member.getHullId();
            return hullId == null ? "" : hullId;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * {@code doesVariantExist} is a plain null-check over the spec store plus the campaign's saved
     * variants and never throws for a missing id — unlike {@code getHullSpec}, which throws
     * {@code "Ship hull spec [x] not found!"}. The catch is for the campaign-less case: the lookup
     * dereferences {@code CampaignEngine.getInstance()} unconditionally.
     */
    private static boolean variantExists(String variantId) {
        try {
            return Global.getSettings().doesVariantExist(variantId);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Removes the mirror fleet from the world. Idempotent. */
    @Override
    public void dispose() {
        if (mirrorFleet == null) {
            return;
        }
        try {
            LocationAPI location = mirrorFleet.getContainingLocation();
            if (location != null) {
                location.removeEntity(mirrorFleet);
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to remove coop mirror fleet", ex);
        } finally {
            // Only if this instance is the one that published it: the NPC mirrors dispose constantly
            // and must never drop the player mirror's handle.
            CoopGuestMirrorHandle.clearIfSame(mirrorFleet);
            mirrorFleet = null;
            resetTracking();
        }
    }

    public boolean hasMirrorFleet() {
        return mirrorFleet != null;
    }

    /**
     * The live engine fleet this mirror drives, or null. Exposed so callers that need to <em>find</em>
     * the mirror can hold the reference the mirror already has instead of scanning the sector for its
     * memory tag; {@link CoopGuestMirrorHandle} is the static slot that does exactly that.
     */
    public CampaignFleetAPI mirrorFleetOrNull() {
        return mirrorFleet;
    }

    private void resetTracking() {
        motionInterpolator.clear();
        resetSpeedProbeTracking();
        lastFleetHash = null;
        retriedFleetHash = null;
        crInvalidationReferences = null;
        builtMemberIds = List.of();
        builtMemberCount = -1;
        lastLocationId = null;
        coopFleetId = "";
        appliedName = "";
        appliedFactionId = "";
        shieldReleased = false;
        shieldAssertedAtMillis = NEVER_ASSERTED;
        sensors = CoopSensorSync.Profile.UNKNOWN;
        appliedActionText = "";
        loggedActionTextPath = false;
        emptyRosterSkipLogged = false;
    }

    /**
     * The location an incoming snapshot or motion record names.
     *
     * <p>Two short-circuits, in cost order. The common case by a wide margin is "the same location as
     * last time": this runs once per mirrored fleet per motion record (10 Hz) and the fleet the mirror
     * stands for changes system on the scale of minutes, so when the id matches the location the mirror
     * is already sitting in, that <em>is</em> the answer — no lookup at all. Everything else goes to
     * {@link CoopLocations#byId}, which is a map hit rather than the linear scan over
     * {@code getAllLocations()} this used to do (~130 systems, two fresh ArrayLists per call).
     */
    private LocationAPI resolveLocation(SectorAPI sector, String locationId) {
        if (locationId == null || locationId.isEmpty()) {
            return null;
        }
        try {
            if (locationId.equals(lastLocationId) && mirrorFleet != null) {
                LocationAPI current = mirrorFleet.getContainingLocation();
                // Id-checked, not assumed: if the engine moved the mirror out from under us the
                // containing location is not the one the record names, and the lookup below is what
                // puts it back.
                if (current != null && locationId.equals(current.getId())) {
                    return current;
                }
            }
            return CoopLocations.byId(sector, locationId);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to resolve coop mirror location " + locationId, ex);
            return null;
        }
    }

    private SectorAPI sectorOrNull() {
        try {
            return sectorSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
