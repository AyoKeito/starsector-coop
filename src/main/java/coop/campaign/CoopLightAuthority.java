package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Campaign-thread transport; native World owns all durable interaction and expiry state. */
public final class CoopLightAuthority {
    static final long POLL_INTERVAL_MILLIS = 250L;
    static final long FULL_SET_INTERVAL_MILLIS = 5_000L;
    static final long REQUEST_RETRY_MILLIS = 2_000L;

    public interface LightWorld {
        /** Null is an unavailable scan; an empty list authoritatively contains no lights. */
        List<CoopLightRecord> captureHost();
        Set<String> taggedIds();
        void upsert(CoopLightRecord record);
        void remove(String id);
        /** Called only after every member of a fully validated manifest was applied successfully. */
        void reconcileLegacy(List<CoopLightRecord> completeManifest);
        List<CoopLightCodec.Request> pendingRequests();
        /** Durable first-wins claim and replay ledger, keyed by actor and request id. */
        CoopLightCodec.Outcome applyRequest(CoopLightCodec.Request request, String actorId);
        /** Applies local effects once, then durably acknowledges the pending request. */
        void acceptOutcome(CoopLightCodec.Outcome outcome);
        void observeGuest();
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final LightWorld world;
    private final Map<String, Long> requestSentAt = new LinkedHashMap<>();
    private List<CoopLightRecord> desired;
    private String sentFingerprint;
    private long nextHostPoll;
    private long nextFullSet;
    private boolean retryApply;
    private boolean fullBroadcast = true;

    public CoopLightAuthority(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this(service, session, clock, new CoopLightWorld());
    }

    public CoopLightAuthority(CoopNetService service, CoopSessionState session,
                              LongSupplier clock, LightWorld world) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.world = Objects.requireNonNull(world, "world");
    }

    public void tickHost() {
        if (!active(CoopConnectionRole.HOST)) return;
        long now = clock.getAsLong();
        if (now < nextHostPoll) return;
        try {
            List<CoopLightRecord> records = world.captureHost();
            if (records == null) return;
            // Encoding validates the entire set, including duplicate canonical/native identities.
            String encoded = CoopLightCodec.encodeSet(records);
            String fingerprint = CoopLightCodec.staticFingerprint(records);
            if (!fullBroadcast && now < nextFullSet && fingerprint.equals(sentFingerprint)) return;
            CoopMessages.Message message = CoopMessages.lightSet(session.sessionId(), service.nextSeq(), now, encoded);
            requireFrame(message);
            service.send(message);
            sentFingerprint = fingerprint;
            fullBroadcast = false;
            nextFullSet = now + FULL_SET_INTERVAL_MILLIS;
        } finally {
            nextHostPoll = now + POLL_INTERVAL_MILLIS;
        }
    }

    public void observeGuest() { world.observeGuest(); }

    public void tickGuest() {
        if (!active(CoopConnectionRole.GUEST)) return;
        observeGuest();
        long now = clock.getAsLong();
        List<CoopLightCodec.Request> pending = List.copyOf(world.pendingRequests());
        Set<String> pendingIds = pending.stream().map(CoopLightCodec.Request::requestId).collect(Collectors.toSet());
        requestSentAt.keySet().retainAll(pendingIds);
        for (CoopLightCodec.Request request : pending) {
            Long previous = requestSentAt.get(request.requestId());
            if (previous != null && now >= previous && now - previous < REQUEST_RETRY_MILLIS) continue;
            CoopMessages.Message message = CoopMessages.lightRequest(session.sessionId(), service.nextSeq(), now,
                    CoopLightCodec.encodeRequest(request));
            requireFrame(message);
            service.send(message);
            requestSentAt.put(request.requestId(), now);
        }
        // A temporarily unavailable native recipe must not prevent an unrelated committed claim
        // from reaching Host. The manifest can keep retrying after its durable requests are sent.
        if (retryApply) reconcile();
    }

    public void applySet(String encoded) {
        requireRole(CoopConnectionRole.GUEST);
        List<CoopLightRecord> records = CoopLightCodec.decodeSet(encoded);
        requireFrame(CoopMessages.lightSet(session.sessionId(), 0L, 0L, encoded));
        observeGuest();
        desired = records;
        retryApply = true;
        reconcile();
    }

    private void reconcile() {
        if (desired == null) return;
        // A construction failure must never turn part of a set into permission to delete.
        for (CoopLightRecord record : desired) world.upsert(record);
        Set<String> wanted = desired.stream().map(CoopLightRecord::id).collect(Collectors.toSet());
        for (String id : Set.copyOf(world.taggedIds())) if (!wanted.contains(id)) world.remove(id);
        world.reconcileLegacy(desired);
        retryApply = false;
    }

    /** Caller has already authenticated the wire sender; still bind it to the active Guest seat. */
    public void applyRequest(String encoded, String actorId) {
        requireRole(CoopConnectionRole.HOST);
        if (!Objects.equals(actorId, session.remotePlayerId()))
            throw new IllegalArgumentException("Light request actor is not the connected Guest");
        CoopLightCodec.Request request = CoopLightCodec.decodeRequest(encoded);
        requireFrame(CoopMessages.lightRequest(session.sessionId(), 0L, 0L, encoded));
        CoopLightCodec.Outcome outcome = Objects.requireNonNull(world.applyRequest(request, actorId), "light outcome");
        if (!request.requestId().equals(outcome.requestId()) || !request.lightId().equals(outcome.lightId())
                || request.action() != outcome.action() || !actorId.equals(outcome.actorId()))
            throw new IllegalStateException("Native light outcome does not match the authenticated request");
        CoopMessages.Message message = CoopMessages.lightResult(session.sessionId(), service.nextSeq(),
                clock.getAsLong(), CoopLightCodec.encodeOutcome(outcome));
        requireFrame(message);
        service.send(message);
        // Native tombstones change the captured static set on the next bounded Host poll.
    }

    public void applyResult(String encoded) {
        requireRole(CoopConnectionRole.GUEST);
        CoopLightCodec.Outcome outcome = CoopLightCodec.decodeOutcome(encoded);
        requireFrame(CoopMessages.lightResult(session.sessionId(), 0L, 0L, encoded));
        if (!Objects.equals(outcome.actorId(), session.localPlayerId()))
            throw new IllegalArgumentException("Light result belongs to another player");
        List<CoopLightCodec.Request> pending = List.copyOf(world.pendingRequests());
        CoopLightCodec.Request request = pending.stream().filter(r -> r.requestId().equals(outcome.requestId()))
                .findFirst().orElse(null);
        // The native ledger has already acknowledged this result, or this process never requested it.
        if (request == null) return;
        if (!request.lightId().equals(outcome.lightId()) || request.action() != outcome.action())
            throw new IllegalArgumentException("Light result does not match the pending request");
        world.acceptOutcome(outcome);
        requestSentAt.remove(outcome.requestId());
    }

    private boolean active(CoopConnectionRole role) {
        return session.role() == role && session.sessionId() != null;
    }

    private void requireRole(CoopConnectionRole role) {
        if (!active(role)) throw new IllegalStateException("Light operation requires an active " + role + " session");
    }

    private static void requireFrame(CoopMessages.Message message) {
        if (!CoopSlipstreamAuthority.fitsFrame(message)) throw new IllegalArgumentException("Light payload exceeds TCP frame cap");
    }

    public void rearmFullBroadcast() {
        fullBroadcast = true;
        nextHostPoll = 0L;
        requestSentAt.clear();
    }

    /** Session reset discards transport cursors only; pending claims and tombstones belong to World. */
    public void reset() {
        desired = null;
        sentFingerprint = null;
        nextFullSet = 0L;
        retryApply = false;
        rearmFullBroadcast();
    }
}
