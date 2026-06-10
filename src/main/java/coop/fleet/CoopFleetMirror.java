package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
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
 * <p>Both roles share the driving contract: {@code setAIMode(true)}, {@code setNoEngaging(1f)} every
 * tick (the mirror never autonomously starts combat — real engagements happen on the host's
 * authoritative copy), {@code setMoveDestinationOverride} plus a periodic {@code setLocation} snap to
 * correct interpolation drift, and a roster rebuild only when the snapshot's {@code fleetHash} changes.
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
        mirrorFleet.setNoEngaging(1f);
        if (transponderOn != null) {
            try {
                mirrorFleet.setTransponderOn(transponderOn);
            } catch (RuntimeException ignored) {
                // transponder state is best-effort for the mirror
            }
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
            return;
        }
        rebuildRoster(members, fleetHash);
        lastFleetHash = fleetHash;
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
