package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import coop.util.CoopLog;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Represents the remote player as an AI-mode {@link CampaignFleetAPI} (the "two-fleet trick" from
 * {@code COOP_MP_DESIGN.md} section 4.3) and drives it from replicated {@link CoopFleetSnapshot}s.
 *
 * <p>Per Phase 8 of {@code COOP_MP_IMPLEMENTATION_PLAN_V1.md}: the mirror is created with
 * {@code setAIMode(true)}, moved with {@code setMoveDestinationOverride} plus periodic
 * {@code setLocation} correction, kept from engaging with {@code setNoEngaging(1f)} every update,
 * and its roster is rebuilt only when the snapshot's {@code fleetHash} changes. Visibility/label/
 * color are delegated to {@link CoopPresenceIndicator}.
 */
public class CoopFleetMirror {
    // 10 Hz snapshots; snap the absolute position roughly once per second to correct interpolation
    // drift without fighting the per-tick move-destination override every frame.
    private static final int LOCATION_CORRECTION_EVERY = 10;
    private static final String MIRROR_TAG = "$coopMirrorFleet";

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
            ensureFleet(sector, snapshot, localPlayerFactionId);
            placeInLocation(location, snapshot);
            driveMovement(snapshot);
            refreshRosterIfChanged(snapshot);
            presenceIndicator.apply(mirrorFleet, snapshot.username());
            applyCount++;
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopFleetMirror.class, "Failed to apply coop fleet snapshot", ex);
        }
    }

    private void ensureFleet(SectorAPI sector, CoopFleetSnapshot snapshot, String localPlayerFactionId) {
        if (mirrorFleet != null && mirrorFleet.isAlive()) {
            return;
        }
        String factionId = CoopPresenceIndicator.presenceFactionId(localPlayerFactionId);
        String label = CoopPresenceIndicator.presenceLabel(snapshot.username());
        mirrorFleet = Global.getFactory().createEmptyFleet(factionId, label, true);
        mirrorFleet.setAIMode(true);
        mirrorFleet.setNoAutoDespawn(true);
        mirrorFleet.getMemoryWithoutUpdate().set(MIRROR_TAG, true);
        lastFleetHash = null;
        lastLocationId = null;
        applyCount = 0;
        CoopLog.info(CoopFleetMirror.class,
                "Created coop mirror fleet for playerId=" + snapshot.playerId()
                        + " username=" + label + " faction=" + factionId);
    }

    private void placeInLocation(LocationAPI location, CoopFleetSnapshot snapshot) {
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
            mirrorFleet.setLocation(snapshot.x(), snapshot.y());
            lastLocationId = targetId;
            CoopLog.info(CoopFleetMirror.class,
                    "Coop mirror fleet moved to location " + targetId);
        } else if (applyCount % LOCATION_CORRECTION_EVERY == 0) {
            mirrorFleet.setLocation(snapshot.x(), snapshot.y());
        }
    }

    private void driveMovement(CoopFleetSnapshot snapshot) {
        mirrorFleet.setMoveDestinationOverride(snapshot.x(), snapshot.y());
        mirrorFleet.setVelocity(snapshot.velocityX(), snapshot.velocityY());
        mirrorFleet.setNoEngaging(1f);
        try {
            mirrorFleet.setTransponderOn(snapshot.transponderOn());
        } catch (RuntimeException ignored) {
            // transponder state is cosmetic for the mirror
        }
    }

    private void refreshRosterIfChanged(CoopFleetSnapshot snapshot) {
        if (Objects.equals(snapshot.fleetHash(), lastFleetHash)) {
            return;
        }
        rebuildRoster(snapshot);
        lastFleetHash = snapshot.fleetHash();
    }

    private void rebuildRoster(CoopFleetSnapshot snapshot) {
        for (FleetMemberAPI existing : mirrorFleet.getFleetData().getMembersListCopy()) {
            mirrorFleet.getFleetData().removeFleetMember(existing);
        }
        for (CoopFleetSnapshot.Member member : snapshot.members()) {
            addMirrorMember(member);
        }
        mirrorFleet.getFleetData().setSyncNeeded();
        CoopLog.info(CoopFleetMirror.class,
                "Coop mirror fleet roster refreshed to " + snapshot.members().size()
                        + " ship(s) fleetHash=" + snapshot.fleetHash());
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
            lastFleetHash = null;
            lastLocationId = null;
            applyCount = 0;
        }
    }

    public boolean hasMirrorFleet() {
        return mirrorFleet != null;
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
