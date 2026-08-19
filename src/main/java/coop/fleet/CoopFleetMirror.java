package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
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
 * combat, because real engagements happen on the host's authoritative copy),
 * {@code setMoveDestinationOverride} plus a periodic {@code setLocation} snap to correct interpolation
 * drift, and a roster rebuild only when the snapshot's {@code fleetHash} changes.
 */
public class CoopFleetMirror implements CoopNpcMirror {
    // 10 Hz snapshots; snap the absolute position roughly once per second to correct interpolation
    // drift without fighting the per-tick move-destination override every frame.
    private static final int LOCATION_CORRECTION_EVERY = 10;
    private static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    private static final String NPC_MIRROR_TAG = "$coopNpcFleetId";
    private static final String DEFAULT_NPC_FACTION = "independent";
    private static final String DEFAULT_NPC_NAME = "Fleet";

    private final Supplier<SectorAPI> sectorSupplier;
    private final CoopPresenceIndicator presenceIndicator;

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
    private int applyCount;
    /** True while the shield is deliberately down for the player's interaction target (edge-tracked). */
    private boolean shieldReleased;
    /**
     * Last sensor identity received for this mirror (Phase 14b). Held rather than applied once because
     * the engine rewrites the mirror's profile/strength fields every frame and local terrain re-applies
     * its own detectability mods every frame — see {@link #assertSensorState()}.
     */
    private CoopSensorSync.Profile sensors = CoopSensorSync.Profile.UNKNOWN;

    public CoopFleetMirror() {
        this(Global::getSector, new CoopPresenceIndicator());
    }

    public CoopFleetMirror(Supplier<SectorAPI> sectorSupplier, CoopPresenceIndicator presenceIndicator) {
        this.sectorSupplier = Objects.requireNonNull(sectorSupplier, "sectorSupplier");
        this.presenceIndicator = Objects.requireNonNull(presenceIndicator, "presenceIndicator");
    }

    // ---- Player mirror (Phase 8) -------------------------------------------------------------

    /** Local player's faction id, used to color the mirror in the local player's own color. */
    public void apply(CoopFleetSnapshot snapshot, String localPlayerFactionId) {
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
            ensurePlayerFleet(snapshot, localPlayerFactionId);
            placeInLocation(location, snapshot.x(), snapshot.y());
            driveMovement(snapshot.x(), snapshot.y(), snapshot.velocityX(), snapshot.velocityY(),
                    snapshot.transponderOn());
            acceptSensors(snapshot.sensors());
            refreshRosterIfChanged(snapshot.fleetHash(), snapshot.members());
            presenceIndicator.apply(mirrorFleet, snapshot.username());
            applyCount++;
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop fleet snapshot", ex);
        }
    }

    // ---- NPC mirror (Phase 9) ----------------------------------------------------------------

    @Override
    public void applySnapshot(CoopNpcFleetSnapshot snapshot) {
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
            placeInLocation(location, snapshot.x(), snapshot.y());
            driveMovement(snapshot.x(), snapshot.y(), snapshot.velocityX(), snapshot.velocityY(),
                    snapshot.transponderOn());
            acceptSensors(snapshot.sensors());
            refreshRosterIfChanged(snapshot.fleetHash(), snapshot.members());
            applyCount++;
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop NPC fleet snapshot", ex);
        }
    }

    @Override
    public void applyMotion(CoopNpcFleetMotion motion) {
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
            driveMovement(motion.x(), motion.y(), motion.velocityX(), motion.velocityY(), null);
            acceptSensors(motion.sensors());
            applyCount++;
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop NPC fleet motion", ex);
        }
    }

    // ---- Fleet construction ------------------------------------------------------------------

    private void ensurePlayerFleet(CoopFleetSnapshot snapshot, String localPlayerFactionId) {
        if (mirrorFleet != null && mirrorFleet.isAlive()) {
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
            mirrorFleet.setLocation(x, y);
            lastLocationId = targetId;
            CoopLog.info(CoopFleetMirror.class, "Coop mirror fleet moved to location " + targetId);
        } else if (applyCount % LOCATION_CORRECTION_EVERY == 0) {
            mirrorFleet.setLocation(x, y);
        }
    }

    /** {@code transponderOn} may be null for motion-only updates that do not carry transponder state. */
    private void driveMovement(float x, float y, float velocityX, float velocityY, Boolean transponderOn) {
        mirrorFleet.setMoveDestinationOverride(x, y);
        mirrorFleet.setVelocity(velocityX, velocityY);
        // No shield assert here on purpose: the per-frame pump pass (assertEngagementShield) is the
        // single authoritative shield. Asserting it from the driving path as well would fight the
        // targeted release below every time a snapshot or motion record arrived (10 Hz), leaving the
        // fleet the player explicitly targeted permanently unengageable.
        if (transponderOn != null) {
            try {
                mirrorFleet.setTransponderOn(transponderOn);
            } catch (RuntimeException ignored) {
                // transponder state is best-effort for the mirror
            }
        }
    }

    /**
     * Re-asserts the ~1 s {@code noCombat} fader that keeps the mirror out of engine-formed battles.
     *
     * <p>Battle initiation between AI fleets lives only in {@code BaseLocation.advance}'s pair loop,
     * whose first gate is {@code CampaignFleet.canBeEngaged()} — false while that fader is live. The
     * mirror driving path deliberately does <em>not</em> refresh it (see {@link #driveMovement}), so
     * the pump calling this unconditionally once per frame — including while paused — is the whole
     * shield: it never depends on traffic arriving, and it survives network stalls, location
     * transitions and unresolvable locations that make the apply paths return early.
     *
     * <p>This unconditional form is what the <b>partner player mirror</b> gets on both roles: it is
     * the PvP block (neither player can engage the other's mirror) and, on the host, the block that
     * keeps the guest's mirror out of NPC battles. It is never released.
     */
    public void assertEngagementShield() {
        if (mirrorFleet == null) {
            return;
        }
        try {
            mirrorFleet.setNoEngaging(1f);
        } catch (RuntimeException ignored) {
            // hot path, once per frame: never abort the frame over the shield
        }
        shieldReleased = false;
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
    public void assertEngagementShield(Object playerInteractionTarget) {
        if (mirrorFleet == null) {
            return;
        }
        if (shouldReleaseShield(mirrorFleet, playerInteractionTarget)) {
            releaseEngagementShield();
            return;
        }
        assertEngagementShield();
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
     */
    private void refreshRosterIfChanged(String fleetHash, List<CoopFleetSnapshot.Member> members) {
        if (Objects.equals(fleetHash, lastFleetHash)) {
            // Same ship set: keep the roster and track the slow-moving repair state in place. The
            // hash is structural on purpose — CR/hull recovery used to flip it every second or two
            // per damaged fleet and the resulting rebuild storm dropped the guest to 39 fps
            // (2026-08-17); see CoopFleetSnapshot.computeFleetHash.
            updateMemberState(members);
            return;
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
     * Applies CR/hull onto the existing mirror members without a rebuild. Members are matched by
     * list position: both sides preserve fleet order (the roster was built in snapshot order and the
     * structural hash pins the same ship set), and a transient order mismatch merely paints repair
     * state onto a same-set sibling until the next structural rebuild.
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
     * apply for a fleet whose CR is not moving.
     */
    private void updateMemberState(List<CoopFleetSnapshot.Member> members) {
        List<FleetMemberAPI> current = mirrorFleet.getFleetData().getMembersListCopy();
        if (current.size() != members.size()) {
            return;
        }
        for (int i = 0; i < current.size(); i++) {
            try {
                FleetMemberAPI member = current.get(i);
                float cr = members.get(i).cr();
                boolean crChanged = crDiffers(member.getRepairTracker().getCR(), cr);
                member.getRepairTracker().setCR(cr);
                member.getStatus().setHullFraction(members.get(i).hullFraction());
                if (crChanged) {
                    member.setStatUpdateNeeded(true);
                }
            } catch (RuntimeException ignored) {
                // repair state on a mirror is display-only; never abort the update over it
            }
        }
    }

    /** CR is a 0..1 fraction; half a percent is below anything the strength formula reacts to. */
    static boolean crDiffers(float current, float incoming) {
        return Math.abs(current - incoming) > 0.005f;
    }

    /** @return true when every member in the snapshot was actually built and attached. */
    private boolean rebuildRoster(List<CoopFleetSnapshot.Member> members, String fleetHash) {
        for (FleetMemberAPI existing : mirrorFleet.getFleetData().getMembersListCopy()) {
            mirrorFleet.getFleetData().removeFleetMember(existing);
        }
        int built = 0;
        for (CoopFleetSnapshot.Member member : members) {
            if (addMirrorMember(member)) {
                built++;
            }
        }
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
            created.getRepairTracker().setCR(member.cr());
            created.getStatus().setHullFraction(member.hullFraction());
            return true;
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class,
                    "Failed to attach coop mirror member " + member.fleetMemberId(), ex);
            return false;
        }
    }

    private FleetMemberAPI createMember(CoopFleetSnapshot.Member member) {
        try {
            if (!member.variantId().isEmpty()) {
                return Global.getFactory().createFleetMember(FleetMemberType.SHIP, member.variantId());
            }
        } catch (RuntimeException ignored) {
            // Unknown variant id (e.g. a custom/empty variant); fall back to a hull default below.
        }
        try {
            if (!member.hullId().isEmpty()) {
                return Global.getFactory().createFleetMember(
                        FleetMemberType.SHIP, member.hullId() + "_Hull");
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class,
                    "Failed to create coop mirror member for hull " + member.hullId(), ex);
            return null;
        }
        // Both ids were empty (or the variant id resolved to nothing and there was no hull to fall
        // back to). Silence here is what let short rosters pass unnoticed: say so once per attempt.
        CoopLog.warn(CoopFleetMirror.class, "Coop mirror member has neither a usable variant nor hull"
                + " id, skipping it: coopFleetId=" + coopFleetId
                + " fleetMemberId=" + member.fleetMemberId()
                + " variantId=" + member.variantId() + " hullId=" + member.hullId());
        return null;
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
            mirrorFleet = null;
            resetTracking();
        }
    }

    public boolean hasMirrorFleet() {
        return mirrorFleet != null;
    }

    private void resetTracking() {
        lastFleetHash = null;
        retriedFleetHash = null;
        lastLocationId = null;
        coopFleetId = "";
        appliedName = "";
        appliedFactionId = "";
        applyCount = 0;
        shieldReleased = false;
        sensors = CoopSensorSync.Profile.UNKNOWN;
    }

    private LocationAPI resolveLocation(SectorAPI sector, String locationId) {
        if (locationId == null || locationId.isEmpty()) {
            return null;
        }
        try {
            LocationAPI hyperspace = sector.getHyperspace();
            if (hyperspace != null && locationId.equals(hyperspace.getId())) {
                return hyperspace;
            }
            for (LocationAPI location : sector.getAllLocations()) {
                if (location != null && locationId.equals(location.getId())) {
                    return location;
                }
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to resolve coop mirror location " + locationId, ex);
        }
        return null;
    }

    private SectorAPI sectorOrNull() {
        try {
            return sectorSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
