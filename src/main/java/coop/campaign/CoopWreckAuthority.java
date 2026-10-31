package coop.campaign;

import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Host-owned hyperspace salvage. Only primitive identity/tombstone data survives a save. */
public final class CoopWreckAuthority {
    static final long POLL_INTERVAL_MILLIS = 250L;
    static final long FULL_SET_INTERVAL_MILLIS = 5_000L;
    static final int CONSUME_BATCH_SIZE = 128;

    public interface WreckWorld {
        /** Null is unavailable, whereas an empty list is an authoritative empty world. */
        List<CoopWreckRecord> captureHost();
        Set<String> taggedIds();
        void upsert(CoopWreckRecord record);
        void remove(String id);
        /** Native expiry is suppressed on replicas, so local fading/removal means salvage. */
        Set<String> locallyConsumed(Set<String> previouslyTracked);
        /** Called only after every record in a complete host manifest was applied successfully. */
        void reconcileLegacy(List<CoopWreckRecord> completeManifest);
        Set<String> consumedIds();
        void markConsumed(String id);
        default long revision(String id) { return 0L; }
        default void upsert(CoopWreckRecord record, long revision) { upsert(record); }
        default void observeMutations() { }
        default List<CoopWreckMutation> pendingMutations() { return List.of(); }
        default CoopWreckMutation.Ack applyMutation(CoopWreckMutation mutation) { throw new UnsupportedOperationException(); }
        default void acknowledgeMutation(CoopWreckMutation.Ack ack) { }
        default void discardMutations(String id) { }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final WreckWorld world;
    private final Map<String, String> knownHost = new LinkedHashMap<>();
    private final Set<String> guestTracked = new LinkedHashSet<>();
    private final Set<String> pendingConsumes = new LinkedHashSet<>();
    private final Set<String> sentConsumes = new HashSet<>();
    private final Map<String, CoopWreckRecord> manifestRecords = new LinkedHashMap<>();
    private Set<String> manifestWanted;
    private boolean loadedConsumes;
    private boolean fullBroadcast = true;
    private long nextPoll;
    private long nextFullSet;
    private final Map<String, Long> mutationSentAt = new LinkedHashMap<>();

    public CoopWreckAuthority(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this(service, session, clock, new CoopWreckWorld());
    }

    public CoopWreckAuthority(CoopNetService service, CoopSessionState session, LongSupplier clock,
                              WreckWorld world) {
        this.service = Objects.requireNonNull(service);
        this.session = Objects.requireNonNull(session);
        this.clock = Objects.requireNonNull(clock);
        this.world = Objects.requireNonNull(world);
    }

    public void tickHost() {
        long now = clock.getAsLong();
        if (now < nextPoll) return;
        try {
            List<CoopWreckRecord> records = world.captureHost();
            if (records == null) return;
            if (records.size() > CoopWreckRecord.MAX_WRECKS) {
                throw new IllegalArgumentException("Too many host wrecks: " + records.size());
            }
            Set<String> consumed = world.consumedIds();
            Map<String, String> current = new LinkedHashMap<>();
            Map<String, CoopMessages.Message> adds = new LinkedHashMap<>();
            // Validate the ENTIRE transaction before publishing its manifest. A failed record must
            // never turn a partial capture into authority to delete a guest's existing salvage.
            for (CoopWreckRecord record : records) {
                if (consumed.contains(record.id())) {
                    world.remove(record.id());
                    continue;
                }
                String encoded = record.encode();
                if (current.putIfAbsent(record.id(), encoded) != null) {
                    throw new IllegalArgumentException("Duplicate host wreck id: " + record.id());
                }
                CoopMessages.Message message = CoopMessages.wreckAdd(session.sessionId(),
                        service.nextSeq(), now, encoded, world.revision(record.id()));
                if (!CoopSlipstreamAuthority.fitsFrame(message)) {
                    throw new IllegalArgumentException("Wreck exceeds TCP frame cap: " + record.id());
                }
                adds.put(record.id(), message);
            }
            for (String id : knownHost.keySet()) {
                if (!current.containsKey(id)) world.markConsumed(id);
            }
            if (fullBroadcast || now >= nextFullSet) {
                service.send(CoopMessages.wreckSet(session.sessionId(), service.nextSeq(), now,
                        CoopWreckRecord.encodeIds(current.keySet())));
                adds.values().forEach(service::send);
                fullBroadcast = false;
                nextFullSet = now + FULL_SET_INTERVAL_MILLIS;
            } else {
                for (var entry : current.entrySet()) {
                    if (!entry.getValue().equals(knownHost.get(entry.getKey()))) {
                        service.send(adds.get(entry.getKey()));
                    }
                }
                for (String id : knownHost.keySet()) {
                    if (!current.containsKey(id)) {
                        world.markConsumed(id);
                        service.send(CoopMessages.wreckRemove(session.sessionId(), service.nextSeq(), now, id));
                    }
                }
            }
            knownHost.clear();
            knownHost.putAll(current);
        } finally {
            nextPoll = now + POLL_INTERVAL_MILLIS;
        }
    }

    public void tickGuest() {
        captureLocalConsumes();
        long now = clock.getAsLong();
        Set<String> blocked = new HashSet<>();
        for (CoopWreckMutation mutation : world.pendingMutations()) {
            if (!blocked.add(mutation.id())) continue;
            Long sent = mutationSentAt.get(mutation.mutationId());
            if (sent == null || now - sent >= 2_000L || now < sent) {
                CoopMessages.Message message = CoopMessages.wreckMutation(session.sessionId(), service.nextSeq(), now, mutation.encode());
                if (!CoopSlipstreamAuthority.fitsFrame(message)) throw new IllegalArgumentException("Wreck mutation exceeds TCP frame cap");
                service.send(message);
                mutationSentAt.put(mutation.mutationId(), now);
            }
        }
        List<String> unsent = pendingConsumes.stream().filter(id -> !sentConsumes.contains(id) && !blocked.contains(id)).toList();
        // A long-running save can contain many past tombstones. Bound each control message and
        // rate-limit replay instead of filling the transport queue during a reconnect.
        if (!unsent.isEmpty()) {
            List<String> batch = unsent.subList(0, Math.min(CONSUME_BATCH_SIZE, unsent.size()));
            service.send(CoopMessages.wreckConsume(session.sessionId(), service.nextSeq(), clock.getAsLong(),
                    CoopWreckRecord.encodeIds(batch)));
            sentConsumes.addAll(batch);
        }
    }

    /** Save the report even while a transport grace blocks sending campaign messages. */
    public void observeGuest() {
        captureLocalConsumes();
    }

    private void captureLocalConsumes() {
        // A trap activation can be followed by complete salvage in the same native dialog. Capture
        // its partial outcome before the consuming flag or the entity itself disappears.
        world.observeMutations();
        if (!loadedConsumes) {
            pendingConsumes.addAll(world.consumedIds());
            guestTracked.addAll(world.taggedIds());
            loadedConsumes = true;
        }
        Set<String> newlyConsumed = world.locallyConsumed(Set.copyOf(guestTracked));
        for (String id : newlyConsumed) {
            checkedId(id);
            world.markConsumed(id); // durable before an upsert or native removal can race this report
            pendingConsumes.add(id);
            guestTracked.remove(id);
        }
    }

    public void applySet(String encoded) {
        Set<String> wanted = new LinkedHashSet<>(CoopWreckRecord.decodeIds(encoded));
        captureLocalConsumes();
        manifestWanted = wanted;
        manifestRecords.clear();
        pendingConsumes.retainAll(wanted); // explicit host absence acknowledges prior consumption
        sentConsumes.retainAll(pendingConsumes);
        for (String id : Set.copyOf(world.taggedIds())) {
            if (!wanted.contains(id)) {
                guestTracked.remove(id); // remote removal must not become a local consume report
                world.markConsumed(id);
                world.discardMutations(id);
                world.remove(id);
            }
        }
        finishManifestIfComplete();
    }

    public void applyAdd(String encoded) {
        applyAdd(encoded, 0L);
    }

    public void applyAdd(String encoded, long revision) {
        if (revision < 0) throw new IllegalArgumentException("Negative wreck revision");
        CoopWreckRecord record = CoopWreckRecord.decode(encoded);
        captureLocalConsumes();
        if (!world.consumedIds().contains(record.id())) {
            world.upsert(record, revision);
            guestTracked.add(record.id());
        } else {
            // A complete host set may have been queued just before the guest recovered this wreck.
            // Never resurrect it while its durable consume is making its way to the host.
            pendingConsumes.add(record.id());
            sentConsumes.remove(record.id());
            guestTracked.remove(record.id());
            world.remove(record.id());
        }
        if (manifestWanted != null && manifestWanted.contains(record.id())) {
            manifestRecords.put(record.id(), record);
            finishManifestIfComplete();
        }
    }

    private void finishManifestIfComplete() {
        if (manifestWanted == null || !manifestRecords.keySet().containsAll(manifestWanted)) return;
        world.reconcileLegacy(new ArrayList<>(manifestRecords.values()));
        guestTracked.clear();
        guestTracked.addAll(world.taggedIds());
        manifestWanted = null;
        manifestRecords.clear();
    }

    public void applyRemove(String id) {
        checkedId(id);
        captureLocalConsumes();
        guestTracked.remove(id);
        pendingConsumes.remove(id);
        sentConsumes.remove(id);
        world.markConsumed(id); // terminal identity: even a stale ADD must not recreate this wreck
        world.discardMutations(id);
        if (manifestWanted != null) {
            manifestWanted.remove(id);
            manifestRecords.remove(id);
        }
        world.remove(id);
        finishManifestIfComplete();
    }

    /** Guest reports can address canonical wreck identities only, never arbitrary local engine ids. */
    public void applyConsume(String encoded) {
        List<String> ids = CoopWreckRecord.decodeIds(encoded);
        Set<String> tagged = world.taggedIds();
        Set<String> consumed = world.consumedIds();
        for (String id : ids) {
            if (!tagged.contains(id) && !consumed.contains(id) && !knownHost.containsKey(id)) {
                CoopLog.warn(CoopWreckAuthority.class, "Ignoring consume for unknown host wreck " + id);
                continue;
            }
            if (!consumed.contains(id)) {
                world.markConsumed(id);
                world.remove(id);
            }
            knownHost.remove(id);
            service.send(CoopMessages.wreckRemove(session.sessionId(), service.nextSeq(), clock.getAsLong(), id));
        }
    }

    public void applyMutation(String encoded) {
        CoopWreckMutation mutation = CoopWreckMutation.decode(encoded);
        CoopWreckMutation.Ack ack = world.applyMutation(mutation);
        service.send(CoopMessages.wreckMutationAck(session.sessionId(), service.nextSeq(), clock.getAsLong(), ack.encode()));
        rearmFullBroadcast();
    }

    public void applyMutationAck(String encoded) {
        CoopWreckMutation.Ack ack = CoopWreckMutation.Ack.decode(encoded);
        world.acknowledgeMutation(ack);
        mutationSentAt.remove(ack.mutationId());
    }

    private static void checkedId(String id) {
        CoopWreckRecord.decodeIds(CoopWreckRecord.encodeIds(List.of(id)));
    }

    public void rearmFullBroadcast() {
        fullBroadcast = true;
        nextPoll = 0;
        sentConsumes.clear();
        mutationSentAt.clear();
        // Pending IDs survive a transport grace; saved IDs are re-read after a campaign load/reset.
    }

    public void reset() {
        knownHost.clear();
        guestTracked.clear();
        pendingConsumes.clear();
        sentConsumes.clear();
        manifestRecords.clear();
        manifestWanted = null;
        loadedConsumes = false;
        rearmFullBroadcast();
    }
}
