package coop.campaign;

import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopLog;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Host-owned hyperspace terrain, including native Leviathan trail checkpoints. */
public final class CoopSlipstreamAuthority {
    static final long HOST_POLL_INTERVAL_MILLIS = 250L;

    /** Engine seam; saved terrain tags, rather than transport state, identify guest replicas. */
    public interface StreamWorld {
        /** Null means the scan was unavailable. An empty list means the world has no streams. */
        List<CoopSlipstreamRecord> captureHost();
        Set<String> taggedIds();
        void upsert(CoopSlipstreamRecord record);
        void despawn(CoopSlipstreamRecord.Despawn record);
        void remove(String hostId);
        /** Must run before the guest's SlipstreamManager script is suppressed. */
        void sweepSeasonal();
        /** Complete record was enqueued; native trail circles can now validate delayed Guest OFF. */
        default void published(CoopSlipstreamRecord record) { }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clockMillis;
    private final StreamWorld world;
    private final Map<String, CoopSlipstreamRecord> known = new LinkedHashMap<>();
    private final Set<String> oversizeWarnings = new java.util.HashSet<>();
    private long nextHostPollAtMillis;
    private boolean fullBroadcast = true;
    private boolean seasonalSwept;

    public CoopSlipstreamAuthority(CoopNetService service, CoopSessionState session,
                                   LongSupplier clockMillis) {
        this(service, session, clockMillis, new CoopSlipstreamWorld());
    }

    public CoopSlipstreamAuthority(CoopNetService service, CoopSessionState session,
                                   LongSupplier clockMillis, StreamWorld world) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.world = Objects.requireNonNull(world, "world");
    }

    public void tickHost() {
        long now = clockMillis.getAsLong();
        if (now < nextHostPollAtMillis) return;
        try {
            List<CoopSlipstreamRecord> captured = world.captureHost();
            if (captured == null) return;
            if (captured.size() > CoopSlipstreamRecord.MAX_STREAMS) {
                throw new IllegalArgumentException("Too many host slipstreams: " + captured.size());
            }
            Map<String, CoopSlipstreamRecord> current = new LinkedHashMap<>();
            Map<String, CoopMessages.Message> adds = new LinkedHashMap<>();
            for (CoopSlipstreamRecord record : captured) {
                if (current.containsKey(record.hostId())) {
                    throw new IllegalArgumentException("Duplicate host stream id: " + record.hostId());
                }
                CoopMessages.Message add = CoopMessages.streamAdd(session.sessionId(),
                        service.nextSeq(), now, record.encode());
                if (!fitsFrame(add)) {
                    if (oversizeWarnings.add(record.hostId())) {
                        CoopLog.warn(CoopSlipstreamAuthority.class,
                                "Skipping stream above TCP frame cap: " + record.hostId());
                    }
                    continue;
                }
                oversizeWarnings.remove(record.hostId());
                current.put(record.hostId(), record);
                adds.put(record.hostId(), add);
            }
            if (fullBroadcast) {
                // One campaign frame, ordered TCP: manifest first, then every complete record.
                service.send(CoopMessages.streamSet(session.sessionId(), service.nextSeq(), now,
                        CoopSlipstreamRecord.encodeIds(current.keySet())));
                for (CoopSlipstreamRecord record : current.values()) {
                    service.send(adds.get(record.hostId()));
                    world.published(record);
                }
                fullBroadcast = false;
            } else {
                for (CoopSlipstreamRecord record : current.values()) {
                    CoopSlipstreamRecord previous = known.get(record.hostId());
                    if (previous == null || record.kind() == CoopSlipstreamRecord.Kind.TRAIL && !record.equals(previous)
                            || despawnChanged(previous, record)
                            || activeTransition(record) || activeTransition(previous)) {
                        // A full checkpoint keeps native counters, live faders and surge cursor on
                        // the same committed grid tick. A compact DESPAWN alone cannot do that.
                        // previous-active also publishes the final settled state exactly once.
                        service.send(adds.get(record.hostId()));
                        world.published(record);
                    }
                }
                for (String id : known.keySet()) {
                    if (!current.containsKey(id)) {
                        service.send(CoopMessages.streamRemove(session.sessionId(),
                                service.nextSeq(), now, id));
                    }
                }
            }
            known.clear();
            known.putAll(current);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSlipstreamAuthority.class, "Failed to capture host slipstreams", ex);
        } finally {
            nextHostPollAtMillis = now + HOST_POLL_INTERVAL_MILLIS;
        }
    }

    private static boolean activeTransition(CoopSlipstreamRecord record) {
        if (record.fade().spawnNoise() != null || record.fade().despawnNoise() != null
                || record.unfinishedSurgeFadeIn()) return true;
        // Native background fades can stop at an endpoint before the next advance clears the enum.
        return record.segments().stream().anyMatch(segment -> {
            CoopSlipstreamRecord.Fader fader = segment.fader();
            return switch (fader.state()) {
                case IN -> fader.brightness() < 1f || fader.bounceDown();
                case OUT -> fader.brightness() > 0f || fader.bounceUp();
                case IDLE -> false;
            };
        });
    }

    private static boolean despawnChanged(CoopSlipstreamRecord previous,
                                          CoopSlipstreamRecord current) {
        CoopSlipstreamRecord.Fade old = previous.fade();
        CoopSlipstreamRecord.Fade fresh = current.fade();
        if (fresh.despawnNoise() == null) return false;
        // A slipsurge can abort its original 1.5-day hold with a new immediate fade.
        return !java.util.Arrays.equals(old.despawnNoise(), fresh.despawnNoise())
                || old.despawnDays() != fresh.despawnDays()
                || fresh.despawnElapsed() < old.despawnElapsed()
                || fresh.despawnDelay() > old.despawnDelay();
    }

    /** Includes both levels of JSON escaping and room for the transport's sender stamp. */
    static boolean fitsFrame(CoopMessages.Message message) {
        CoopMessages.Message stamped = message.withSenderId("x".repeat(256));
        return CoopMessages.encode(stamped).getBytes(StandardCharsets.UTF_8).length
                <= CoopNetService.MAX_FRAME_BYTES;
    }

    public void prepareGuest() {
        if (seasonalSwept) return;
        world.sweepSeasonal();
        seasonalSwept = true;
    }

    public void applySet(String encoded) {
        Set<String> wanted = Set.copyOf(CoopSlipstreamRecord.decodeIds(encoded));
        prepareGuest();
        for (String id : world.taggedIds()) {
            if (!wanted.contains(id)) world.remove(id);
        }
    }

    public void applyAdd(String encoded) {
        CoopSlipstreamRecord record = CoopSlipstreamRecord.decode(encoded);
        prepareGuest();
        world.upsert(record);
    }

    public void applyDespawn(String encoded) {
        world.despawn(CoopSlipstreamRecord.Despawn.decode(encoded));
    }

    public void applyRemove(String hostId) {
        // Decode through the manifest codec so malformed ids cannot address arbitrary saved state.
        String checked = CoopSlipstreamRecord.decodeIds(
                CoopSlipstreamRecord.encodeIds(List.of(hostId))).get(0);
        world.remove(checked);
    }

    /** Reconnect keeps guest terrain and its local fade progress; the host owes a fresh full set. */
    public void rearmFullBroadcast() {
        fullBroadcast = true;
        nextHostPollAtMillis = 0;
    }

    public void reset() {
        known.clear();
        oversizeWarnings.clear();
        seasonalSwept = false;
        rearmFullBroadcast();
    }
}
