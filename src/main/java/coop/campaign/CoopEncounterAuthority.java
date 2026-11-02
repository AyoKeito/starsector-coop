package coop.campaign;

import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Campaign-thread delivery of complete Host encounter sets. No receiver state is saved. */
public final class CoopEncounterAuthority {
    static final long POLL_MILLIS = 250L;

    public interface EncounterWorld {
        /** Null is an unavailable scan, never an authoritative empty set. */
        List<CoopEncounterRecord> captureHost();
        Set<String> taggedIds();
        void upsert(CoopEncounterRecord record);
        void remove(String instanceId);
        void updateGuestPresence(boolean reconnectGrace);
        List<CoopEncounterRecord.EntityState> guestStates();
        /** Last Host overlay saved as primitive recipe text, before any local dialog edits. */
        default List<CoopEncounterRecord.EntityState> guestBaselineStates() { return List.of(); }
        /** Saved original members missing locally whose last Host baseline still had them alive. */
        default Set<String> guestConsumedIds() { return Set.of(); }
        /** Current circular phases are captured only when a complete recipe set is sent. */
        default Map<String, String> captureCurrentOrbits(List<CoopEncounterRecord> records) { return emptyOrbits(records); }
        /** Applies only after native replay and its overlay succeed; retained through retries. */
        default void applyCurrentOrbits(CoopEncounterRecord record, String encodedOrbits) { }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final EncounterWorld world;
    private final Map<String, String> guestBaseline = new LinkedHashMap<>();
    private final Set<String> reportedConsumed = new java.util.HashSet<>();
    private List<CoopEncounterRecord> desired;
    private Map<String, String> desiredOrbits = Map.of();
    private final Set<String> appliedDesiredOrbits = new java.util.HashSet<>();
    private List<CoopEncounterRecord> capturedHost;
    private String sentHash;
    private long nextPoll;
    private boolean retryApply;

    public CoopEncounterAuthority(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this(service, session, clock, new CoopEncounterWorld());
    }

    public CoopEncounterAuthority(CoopNetService service, CoopSessionState session,
                                  LongSupplier clock, EncounterWorld world) {
        this.service = Objects.requireNonNull(service);
        this.session = Objects.requireNonNull(session);
        this.clock = Objects.requireNonNull(clock);
        this.world = Objects.requireNonNull(world);
    }

    public void tickHost() {
        long now = clock.getAsLong();
        observeHost();
        List<CoopEncounterRecord> records = capturedHost;
        if (records == null) return;
        String hash = CoopEncounterCodec.setHash(records);
        if (hash.equals(sentHash)) return;
        String encodedOrbits = CoopEncounterOrbitSetCodec.encode(world.captureCurrentOrbits(records), records);
        CoopMessages.Message message = CoopMessages.encounterSet(session.sessionId(),
                service.nextSeq(), now, CoopEncounterCodec.encodeSet(records), encodedOrbits);
        if (!CoopSlipstreamAuthority.fitsFrame(message)) {
            throw new IllegalStateException("Encounter set exceeds the TCP frame cap");
        }
        service.send(message);
        sentHash = hash;
    }

    /** Capture original membership while playing alone, before local salvage can remove it. */
    public void observeHost() {
        long now = clock.getAsLong();
        if (now < nextPoll) return;
        nextPoll = now + POLL_MILLIS;
        capturedHost = world.captureHost();
    }

    public void applySet(String encoded) {
        applySet(encoded, null);
    }

    public void applySet(String encoded, String encodedOrbits) {
        List<CoopEncounterRecord> decoded = CoopEncounterCodec.decodeSet(encoded);
        Map<String, String> currentOrbits = encodedOrbits == null ? emptyOrbits(decoded)
                : CoopEncounterOrbitSetCodec.decode(encodedOrbits, decoded);
        String orbitWire = encodedOrbits == null ? CoopEncounterOrbitSetCodec.encode(currentOrbits, decoded) : encodedOrbits;
        String sessionId = session.sessionId() == null ? "encounter-validation" : session.sessionId();
        if (!CoopSlipstreamAuthority.fitsFrame(CoopMessages.encounterSet(sessionId, 0, 0, encoded, orbitWire)))
            throw new IllegalArgumentException("Combined encounter set exceeds the TCP frame cap");
        if (desired == null) {
            // A saved native hint edit can outlive the process and its reliable-send queue.
            // Reconstruct it against the saved Host baseline before applying a fresh manifest.
            for (CoopEncounterRecord.EntityState state : world.guestBaselineStates()) {
                guestBaseline.put(state.entityId(), CoopEncounterCodec.encodeEntityState(state));
            }
        }
        desired = decoded;
        desiredOrbits = currentOrbits;
        appliedDesiredOrbits.clear();
        // Observe local hint edits before an incoming baseline can overwrite them.
        sendGuestConsumption();
        sendGuestChanges();
        retryApply = true;
        reconcile();
    }

    private void reconcile() {
        if (desired == null) return;
        // Construction/overlay failure keeps the prior world and retries. In particular, never
        // delete a system because another recipe failed to materialize partway through a set.
        for (CoopEncounterRecord record : desired) world.upsert(record);
        for (CoopEncounterRecord record : desired) if (!appliedDesiredOrbits.contains(record.instanceId())) {
            world.applyCurrentOrbits(record, desiredOrbits.get(record.instanceId()));
            appliedDesiredOrbits.add(record.instanceId());
        }
        Set<String> wanted = desired.stream().map(CoopEncounterRecord::instanceId)
                .collect(java.util.stream.Collectors.toSet());
        for (String id : world.taggedIds()) if (!wanted.contains(id)) world.remove(id);
        guestBaseline.clear();
        for (CoopEncounterRecord.EntityState state : world.guestStates()) {
            guestBaseline.put(state.entityId(), CoopEncounterCodec.encodeEntityState(state));
        }
        retryApply = false;
    }

    public void tickGuest() {
        long now = clock.getAsLong();
        if (now < nextPoll) return;
        nextPoll = now + POLL_MILLIS;
        sendGuestConsumption();
        sendGuestChanges();
        if (retryApply) reconcile();
    }

    private void sendGuestChanges() {
        if (desired == null || session.sessionId() == null) return;
        for (CoopEncounterRecord.EntityState state : world.guestStates()) {
            String encoded = CoopEncounterCodec.encodeEntityState(state);
            String previous = guestBaseline.put(state.entityId(), encoded);
            if (previous == null || previous.equals(encoded)) continue;
            service.send(CoopMessages.worldDelta(session.sessionId(), service.nextSeq(),
                    clock.getAsLong(), state.entityId(), CoopWorldDelta.Kind.ENCOUNTER_STATE.name(),
                    false, encoded, session.localPlayerId()));
        }
    }

    private void sendGuestConsumption() {
        if (desired == null || session.sessionId() == null) return;
        Set<String> acknowledged = desired.stream().flatMap(record -> record.overlay().consumedIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        for (String id : world.guestConsumedIds()) {
            if (acknowledged.contains(id) || !reportedConsumed.add(id)) continue;
            service.send(CoopMessages.worldDelta(session.sessionId(), service.nextSeq(), clock.getAsLong(),
                    id, CoopWorldDelta.Kind.CONSUME.name(), true, "", session.localPlayerId()));
        }
    }

    public void updateGuestPresence(boolean reconnectGrace) {
        world.updateGuestPresence(reconnectGrace);
    }

    public void rearmFullBroadcast() {
        sentHash = null;
        nextPoll = 0;
    }

    public void reset() {
        desired = null;
        desiredOrbits = Map.of();
        appliedDesiredOrbits.clear();
        capturedHost = null;
        retryApply = false;
        guestBaseline.clear();
        reportedConsumed.clear();
        rearmFullBroadcast();
    }

    private static Map<String, String> emptyOrbits(List<CoopEncounterRecord> records) {
        Map<String, String> empty = new LinkedHashMap<>();
        for (CoopEncounterRecord record : records) empty.put(record.instanceId(), "");
        return Map.copyOf(empty);
    }
}
