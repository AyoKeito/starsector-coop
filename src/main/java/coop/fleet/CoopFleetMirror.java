package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.util.CoopLog;

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
    private String lastLocationId;
    private int applyCount;
    /** True while the shield is deliberately down for the player's interaction target (edge-tracked). */
    private boolean shieldReleased;

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
            applySensorProfile(snapshot.sensorProfile());
            applySensorStrength(snapshot.sensorStrength());
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
            applySensorProfile(motion.sensorProfile());
            applySensorStrength(motion.sensorStrength());
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
        CoopLog.info(CoopFleetMirror.class,
                "Created coop mirror fleet for playerId=" + snapshot.playerId()
                        + " username=" + label + " faction=" + factionId);
    }

    private void ensureNpcFleet(CoopNpcFleetSnapshot snapshot) {
        if (mirrorFleet != null && mirrorFleet.isAlive()) {
            return;
        }
        String factionId = snapshot.factionId().isEmpty() ? DEFAULT_NPC_FACTION : snapshot.factionId();
        String label = snapshot.name().isEmpty() ? DEFAULT_NPC_NAME : snapshot.name();
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
        CoopLog.info(CoopFleetMirror.class,
                "Created coop NPC mirror fleet coopFleetId=" + snapshot.coopFleetId()
                        + " name=" + label + " faction=" + factionId);
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
     * Replicates the host fleet's real detectability onto the mirror, frozen so the engine's per-tick
     * recompute can't drop it back to the tiny default that would make the fleet visible only at
     * point-blank range on the guest. Without this the guest's sensor range to NPC mirrors is far
     * shorter than the host's to the real fleets (the same effect the player presence mirror avoids by
     * forcing a large profile).
     */
    private void applySensorProfile(float sensorProfile) {
        if (sensorProfile <= 0f) {
            return;
        }
        try {
            mirrorFleet.setForceNoSensorProfileUpdate(true);
            mirrorFleet.setSensorProfile(sensorProfile);
        } catch (RuntimeException ignored) {
            // detectability is best-effort; never abort the mirror update over it
        }
    }

    /**
     * Replicates the host fleet's sensor reach (as an observer) onto the mirror so the engine renders
     * the fleet's detection-range ring at the correct radius — the ring a hidden player reads to judge
     * safe approach. Re-asserted every apply (no force-freeze flag exists for strength) so the engine
     * can't recompute it away. The radius itself is resolved by the engine against the guest's own real
     * sensor profile, so the ring correctly shrinks when the guest runs dark.
     */
    private void applySensorStrength(float sensorStrength) {
        if (sensorStrength <= 0f) {
            return;
        }
        try {
            mirrorFleet.setSensorStrength(sensorStrength);
        } catch (RuntimeException ignored) {
            // detection-ring radius is best-effort; never abort the mirror update over it
        }
    }

    private void refreshRosterIfChanged(String fleetHash, List<CoopFleetSnapshot.Member> members) {
        if (Objects.equals(fleetHash, lastFleetHash)) {
            // Same ship set: keep the roster and track the slow-moving repair state in place. The
            // hash is structural on purpose — CR/hull recovery used to flip it every second or two
            // per damaged fleet and the resulting rebuild storm dropped the guest to 39 fps
            // (2026-08-17); see CoopFleetSnapshot.computeFleetHash.
            updateMemberState(members);
            return;
        }
        rebuildRoster(members, fleetHash);
        lastFleetHash = fleetHash;
    }

    /**
     * Applies CR/hull onto the existing mirror members without a rebuild. Members are matched by
     * list position: both sides preserve fleet order (the roster was built in snapshot order and the
     * structural hash pins the same ship set), and a transient order mismatch merely paints repair
     * state onto a same-set sibling until the next structural rebuild.
     */
    private void updateMemberState(List<CoopFleetSnapshot.Member> members) {
        List<FleetMemberAPI> current = mirrorFleet.getFleetData().getMembersListCopy();
        if (current.size() != members.size()) {
            return;
        }
        for (int i = 0; i < current.size(); i++) {
            try {
                current.get(i).getRepairTracker().setCR(members.get(i).cr());
                current.get(i).getStatus().setHullFraction(members.get(i).hullFraction());
            } catch (RuntimeException ignored) {
                // repair state on a mirror is display-only; never abort the update over it
            }
        }
    }

    private void rebuildRoster(List<CoopFleetSnapshot.Member> members, String fleetHash) {
        for (FleetMemberAPI existing : mirrorFleet.getFleetData().getMembersListCopy()) {
            mirrorFleet.getFleetData().removeFleetMember(existing);
        }
        for (CoopFleetSnapshot.Member member : members) {
            addMirrorMember(member);
        }
        mirrorFleet.getFleetData().setSyncNeeded();
        CoopLog.info(CoopFleetMirror.class,
                "Coop mirror fleet roster refreshed to " + members.size()
                        + " ship(s) fleetHash=" + fleetHash);
    }

    private void addMirrorMember(CoopFleetSnapshot.Member member) {
        FleetMemberAPI created = createMember(member);
        if (created == null) {
            return;
        }
        try {
            mirrorFleet.getFleetData().addFleetMember(created);
            if (!member.shipName().isEmpty()) {
                created.setShipName(member.shipName());
            }
            created.getRepairTracker().setCR(member.cr());
            created.getStatus().setHullFraction(member.hullFraction());
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class,
                    "Failed to attach coop mirror member " + member.fleetMemberId(), ex);
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
        }
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
        lastLocationId = null;
        applyCount = 0;
        shieldReleased = false;
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
