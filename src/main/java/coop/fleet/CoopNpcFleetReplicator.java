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
    private final CoopGuestRouteMaterializer guestRoutes = new CoopGuestRouteMaterializer();
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
        // the *player* fleet, which on the host is never the guest. Extend that presence to the guest
        // mirror so a guest-only system is actually populated (and so the fleets survive the host's
        // distance-based despawn) before this tick snapshots and ships the set.
        guestRoutes.tick(sector, now);
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
        guestRoutes.reset();
        motionSmoother.reset();
        CoopFullFidelitySystemDriver.reset();
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
        Set<String> playerLocations = playerOccupiedLocationIds(sector);
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
                    effectiveDetectability(fleet), sensorStrength(fleet)));
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
                effectiveDetectability(fleet),
                sensorStrength(fleet),
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

    private Set<String> playerOccupiedLocationIds(SectorAPI sector) {
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
        forEachLocation(sector, loc -> {
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet != null && isPlayerMirror(fleet)) {
                    ids.add(loc.getId());
                }
            }
        });
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

    /**
     * The value to assign the guest mirror's sensor profile so the guest detects it at the same range
     * the host detects the real fleet.
     *
     * <p>The engine's detection range is {@code targetProfile + observerSensorStrength +
     * targetDetectedRangeBonus} (reverse-engineered from in-game probe data; see memory
     * {@code fleet-visibility-detection-model}). We replicate the raw {@code sensorProfile} but not the
     * per-fleet detected-range bonus (which scales with fleet size), so without this a freshly built
     * mirror is detected only at {@code profile + strength} — under-detecting big fleets by 1000-2800u
     * and making them vanish on the guest while the host still sees them.
     *
     * <p>The bonus is observer-independent, so we recover {@code profile + bonus} as
     * {@code getMaxSensorRangeToDetect(player) - player.sensorStrength} using the host player as the
     * reference observer. Folding it into the mirror's profile reproduces the host's detection range on
     * the guest (with the guest's own sensor strength). Never returns below the raw profile.
     */
    private static float effectiveDetectability(CampaignFleetAPI fleet) {
        try {
            float profile = fleet.getSensorProfile();
            CampaignFleetAPI observer = observerOrNull();
            if (observer == null || observer == fleet) {
                return profile;
            }
            float effective = fleet.getMaxSensorRangeToDetect(observer) - observer.getSensorStrength();
            return effective > profile ? effective : profile;
        } catch (RuntimeException ignored) {
            return 0f;
        }
    }

    private static CampaignFleetAPI observerOrNull() {
        try {
            return Global.getSector().getPlayerFleet();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * The fleet's sensor reach as an observer, replicated so the guest renders its detection-range ring
     * (the radius a hidden player reads to judge safe approach) at the correct size. Unlike profile this
     * is taken verbatim — it is the observer-side term in the detection formula, so no bonus folding is
     * needed; the ring radius is recomputed on the guest against the guest's own real sensor profile.
     */
    private static float sensorStrength(CampaignFleetAPI fleet) {
        try {
            return fleet.getSensorStrength();
        } catch (RuntimeException ignored) {
            return 0f;
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
