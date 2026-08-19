package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.lwjgl.util.vector.Vector2f;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Host-side Phase 9 replicator: makes the entire non-player campaign fleet population
 * host-authoritative. Each tick it enumerates every real NPC fleet (skipping the local player fleet,
 * the Phase 8 guest-player mirror, and stations) and:
 *
 * <ul>
 *   <li>emits the full {@code NPC_FLEET_SET} over reliable TCP whenever its order-independent set hash
 *       changes (existence/identity/roster parity sector-wide, including off-screen fleets);</li>
 *   <li>emits {@code NPC_FLEET_MOTION} over UDP at 10 Hz for fleets in a location where either player
 *       currently is (bounded bandwidth; off-screen mirrors keep their last set position).</li>
 * </ul>
 *
 * <p>Positions leaving here are run through {@link CoopNpcFleetMotionSmoother} unless the fleet is in
 * the host's own current location. The engine advances every other location once per 60 frames with a
 * 60x timestep, so raw positions from a guest-only system arrive as a once-a-second staircase; the
 * smoother turns that back into continuous motion at the cost of one stride of latency.
 *
 * <p>Stations are skipped: they are deterministic worldgen tied to markets and already exist
 * identically on the guest (the guest suppressor likewise preserves them).
 */
public final class CoopNpcFleetReplicator {
    static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    static final String NPC_MIRROR_TAG = "$coopNpcFleetId";
    private static final long SET_SYNC_INTERVAL_MILLIS = 1000L;
    private static final long MOTION_INTERVAL_MILLIS = 100L;

    private final CoopNetService service;
    private final CoopSessionState sessionState;
    private final LongSupplier clockMillis;
    private final CoopGuestPresence guestPresence = new CoopGuestPresence();
    private final CoopNpcFleetMotionSmoother motionSmoother = new CoopNpcFleetMotionSmoother();

    private long nextSetAtMillis;
    private long nextMotionAtMillis;
    private String lastSetHash = "";
    private int lastFleetCount;

    public CoopNpcFleetReplicator(CoopNetService service, CoopSessionState sessionState,
                                  LongSupplier clockMillis) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Called every frame on the host while the session is streaming. */
    public void tick() {
        long now = clockMillis.getAsLong();
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        // First, before anything samples a position: run the guest's star system at the host's real
        // frame rate instead of the engine's once-per-60-frames stride, so what we ship below is a
        // full-fidelity simulation rather than a 1 Hz one. Falls back silently to the stride (and the
        // smoother in replicatedMotion) whenever it cannot or should not run.
        CoopFullFidelitySystemDriver.tick(sector);
        // Before sampling the population: vanilla only turns RouteManager routes into real fleets near
        // the *player* fleet, which on the host is never the guest. Publish the guest mirror as a
        // second presence so the forked RouteManager spawns (and keeps) fleets around it natively,
        // before this tick snapshots and ships the set.
        guestPresence.tick(sector, now);
        if (now >= nextSetAtMillis) {
            try {
                sendSetIfChanged(sector, now);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetReplicator.class, "Failed to send NPC_FLEET_SET", ex);
            } finally {
                nextSetAtMillis = now + SET_SYNC_INTERVAL_MILLIS;
            }
        }
        if (now >= nextMotionAtMillis) {
            try {
                sendMotion(sector, now);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetReplicator.class, "Failed to send NPC_FLEET_MOTION", ex);
            } finally {
                nextMotionAtMillis = now + MOTION_INTERVAL_MILLIS;
            }
        }
    }

    /** Forget the last-sent hash so the next tick rebroadcasts the full set (session (re)start). */
    public void reset() {
        lastSetHash = "";
        lastFleetCount = 0;
        guestPresence.reset();
        motionSmoother.reset();
        CoopFullFidelitySystemDriver.reset();
    }

    /**
     * Phase 15: forget the last-sent hash so the next tick rebroadcasts the full set even if nothing
     * structural changed. Called after a {@code BATTLE_RESULT} is reconciled — a set that still
     * hashes the same (a clean disengage with no losses) is exactly the signal the guest needs to
     * release the mirrors it froze pending reconciliation
     * ({@link CoopFleetMirrorRegistry#markPendingReconcile}), so the send must not be optimised away.
     */
    public void forceResendSet() {
        lastSetHash = "";
        nextSetAtMillis = 0L;
    }

    public String lastSetHash() {
        return lastSetHash;
    }

    public int lastFleetCount() {
        return lastFleetCount;
    }

    private void sendSetIfChanged(SectorAPI sector, long now) {
        List<CoopNpcFleetSnapshot> fleets = new ArrayList<>();
        LocationAPI hostLocation = hostCurrentLocation(sector);
        forEachReplicatedFleet(sector, fleet -> fleets.add(toSnapshot(fleet, hostLocation, now)));
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(fleets);
        if (set.setHash().equals(lastSetHash)) {
            return;
        }
        service.send(CoopMessages.npcFleetSet(
                sessionState.sessionId(), service.nextSeq(), now, set.encode()));
        lastSetHash = set.setHash();
        lastFleetCount = fleets.size();
        CoopLog.info(CoopNpcFleetReplicator.class,
                "Coop sent NPC_FLEET_SET fleets=" + fleets.size());
    }

    private void sendMotion(SectorAPI sector, long now) {
        CampaignFleetAPI guestMirror = CoopGuestPresence.findGuestMirror(sector);
        Set<String> playerLocations = playerOccupiedLocationIds(sector, guestMirror);
        if (playerLocations.isEmpty()) {
            return;
        }
        List<CoopNpcFleetMotion> motions = new ArrayList<>();
        LocationAPI hostLocation = hostCurrentLocation(sector);
        forEachReplicatedFleet(sector, fleet -> {
            LocationAPI loc = fleet.getContainingLocation();
            if (loc == null || !playerLocations.contains(loc.getId())) {
                return;
            }
            CoopNpcFleetMotionSmoother.Motion motion = replicatedMotion(fleet, loc, hostLocation, now);
            motions.add(new CoopNpcFleetMotion(fleet.getId(), loc.getId(),
                    motion.x(), motion.y(), motion.velocityX(), motion.velocityY(),
                    CoopSensorSync.capture(fleet)));
        });
        if (motions.isEmpty()) {
            return;
        }
        service.sendDatagram(CoopMessages.datagram(sessionState.sessionId(),
                CoopMessages.Type.NPC_FLEET_MOTION, CoopNpcFleetMotion.encodeBatch(motions)));
    }

    private CoopNpcFleetSnapshot toSnapshot(CampaignFleetAPI fleet, LocationAPI hostLocation, long now) {
        LocationAPI loc = fleet.getContainingLocation();
        CoopNpcFleetMotionSmoother.Motion motion = replicatedMotion(fleet, loc, hostLocation, now);
        return CoopNpcFleetSnapshot.create(
                fleet.getId(),
                factionId(fleet),
                fleet.getName() == null ? "" : fleet.getName(),
                loc == null ? "" : loc.getId(),
                motion.x(), motion.y(),
                motion.velocityX(), motion.velocityY(),
                transponderOn(fleet),
                CoopSensorSync.capture(fleet),
                "",
                CoopFleetSnapshotFactory.captureMembers(fleet));
    }

    /**
     * The position/velocity to put on the wire for one fleet.
     *
     * <p>Fleets in the host's current location are reported verbatim: the engine advances that
     * location every frame at the real timestep, so the raw values are already smooth and adding an
     * interpolation delay would only cost latency. Everywhere else the engine advances the location
     * once every 60 frames with a 60x timestep (see {@link CoopNpcFleetMotionSmoother}), which is
     * what makes NPC fleets teleport on a guest standing in a system the host is not in — those go
     * through the smoother.
     *
     * <p>A system {@link CoopFullFidelitySystemDriver} is currently driving is reported verbatim for
     * the same reason the host's own location is: it is being advanced every frame at the real
     * timestep, so there is no staircase left to interpolate away and smoothing would only cost a
     * stride of latency. The smoother stays in place for every case the driver does not cover — kill
     * switch off, resolve failure, guest in hyperspace, or any other non-current location.
     */
    private CoopNpcFleetMotionSmoother.Motion replicatedMotion(CampaignFleetAPI fleet, LocationAPI loc,
                                                               LocationAPI hostLocation, long now) {
        Vector2f pos = fleet.getLocation();
        Vector2f vel = fleet.getVelocity();
        float x = pos == null ? 0f : pos.x;
        float y = pos == null ? 0f : pos.y;
        float vx = vel == null ? 0f : vel.x;
        float vy = vel == null ? 0f : vel.y;
        if (loc == null || (hostLocation != null && loc == hostLocation) || isFullFidelityDriven(loc)) {
            return new CoopNpcFleetMotionSmoother.Motion(x, y, vx, vy);
        }
        try {
            return motionSmoother.smooth(fleet.getId(), loc.getId(), x, y, vx, vy, now);
        } catch (RuntimeException | LinkageError ex) {
            return new CoopNpcFleetMotionSmoother.Motion(x, y, vx, vy);
        }
    }

    /** True while {@link CoopFullFidelitySystemDriver} is advancing this location at the real rate. */
    private static boolean isFullFidelityDriven(LocationAPI loc) {
        String driven = CoopFullFidelitySystemDriver.drivenLocationId();
        return !driven.isEmpty() && driven.equals(loc.getId());
    }

    private static LocationAPI hostCurrentLocation(SectorAPI sector) {
        try {
            return sector.getCurrentLocation();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private Set<String> playerOccupiedLocationIds(SectorAPI sector, CampaignFleetAPI guestMirror) {
        Set<String> ids = new HashSet<>();
        try {
            CampaignFleetAPI player = sector.getPlayerFleet();
            if (player != null && player.getContainingLocation() != null) {
                ids.add(player.getContainingLocation().getId());
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        // The guest's location is wherever the Phase 8 guest-player mirror ($coopMirrorFleet) is.
        try {
            if (guestMirror != null && guestMirror.getContainingLocation() != null) {
                ids.add(guestMirror.getContainingLocation().getId());
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        return ids;
    }

    /** Iterates every real NPC fleet: not the local player, not a coop mirror, not a station. */
    private void forEachReplicatedFleet(SectorAPI sector, Consumer<CampaignFleetAPI> consumer) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        forEachLocation(sector, loc -> {
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet == null || fleet == player) {
                    continue;
                }
                if (isCoopMirror(fleet) || fleet.isStationMode()) {
                    continue;
                }
                consumer.accept(fleet);
            }
        });
    }

    private void forEachLocation(SectorAPI sector, Consumer<LocationAPI> consumer) {
        for (LocationAPI loc : sector.getAllLocations()) {
            if (loc != null) {
                consumer.accept(loc);
            }
        }
        LocationAPI hyperspace = sector.getHyperspace();
        if (hyperspace != null && !sector.getAllLocations().contains(hyperspace)) {
            consumer.accept(hyperspace);
        }
    }

    private static boolean isCoopMirror(CampaignFleetAPI fleet) {
        return isPlayerMirror(fleet) || hasTag(fleet, NPC_MIRROR_TAG);
    }

    private static boolean isPlayerMirror(CampaignFleetAPI fleet) {
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        return memory != null && memory.getBoolean(PLAYER_MIRROR_TAG);
    }

    private static boolean hasTag(CampaignFleetAPI fleet, String key) {
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        return memory != null && memory.contains(key);
    }

    private static String factionId(CampaignFleetAPI fleet) {
        try {
            if (fleet.getFaction() != null) {
                return fleet.getFaction().getId();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
    }

    private static boolean transponderOn(CampaignFleetAPI fleet) {
        try {
            return fleet.isTransponderOn();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private SectorAPI sectorOrNull() {
        try {
            return Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
