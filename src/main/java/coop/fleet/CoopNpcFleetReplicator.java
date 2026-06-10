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
    }

    public String lastSetHash() {
        return lastSetHash;
    }

    public int lastFleetCount() {
        return lastFleetCount;
    }

    private void sendSetIfChanged(SectorAPI sector, long now) {
        List<CoopNpcFleetSnapshot> fleets = new ArrayList<>();
        forEachReplicatedFleet(sector, fleet -> fleets.add(toSnapshot(fleet)));
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
        forEachReplicatedFleet(sector, fleet -> {
            LocationAPI loc = fleet.getContainingLocation();
            if (loc == null || !playerLocations.contains(loc.getId())) {
                return;
            }
            Vector2f pos = fleet.getLocation();
            Vector2f vel = fleet.getVelocity();
            motions.add(new CoopNpcFleetMotion(fleet.getId(), loc.getId(),
                    pos == null ? 0f : pos.x, pos == null ? 0f : pos.y,
                    vel == null ? 0f : vel.x, vel == null ? 0f : vel.y,
                    effectiveDetectability(fleet), sensorStrength(fleet)));
        });
        if (motions.isEmpty()) {
            return;
        }
        service.sendDatagram(CoopMessages.datagram(sessionState.sessionId(),
                CoopMessages.Type.NPC_FLEET_MOTION, CoopNpcFleetMotion.encodeBatch(motions)));
    }

    private CoopNpcFleetSnapshot toSnapshot(CampaignFleetAPI fleet) {
        Vector2f pos = fleet.getLocation();
        Vector2f vel = fleet.getVelocity();
        LocationAPI loc = fleet.getContainingLocation();
        return CoopNpcFleetSnapshot.create(
                fleet.getId(),
                factionId(fleet),
                fleet.getName() == null ? "" : fleet.getName(),
                loc == null ? "" : loc.getId(),
                pos == null ? 0f : pos.x, pos == null ? 0f : pos.y,
                vel == null ? 0f : vel.x, vel == null ? 0f : vel.y,
                transponderOn(fleet),
                effectiveDetectability(fleet),
                sensorStrength(fleet),
                "",
                CoopFleetSnapshotFactory.captureMembers(fleet));
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
