package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CoopWreckAuthorityTest {
    @Test void hostPublishesCurrentCompleteManifestBeforeRecordsAndAgainOnResume() {
        Fixture f = new Fixture();
        f.world.host = List.of(wreck("a"), wreck("b"));
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.WRECK_SET, CoopMessages.Type.WRECK_ADD,
                CoopMessages.Type.WRECK_ADD), f.types());
        assertEquals(List.of("wreck:a", "wreck:b"), CoopWreckRecord.decodeIds(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "wrecks")));
        f.net.sent.clear();
        f.world.host = List.of(wreck("b"));
        f.authority.rearmFullBroadcast();
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.WRECK_SET, CoopMessages.Type.WRECK_ADD), f.types());
        assertEquals(List.of("wreck:b"), CoopWreckRecord.decodeIds(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "wrecks")));
    }

    @Test void unavailableOrInvalidHostCaptureCannotPublishPartialDestructiveAuthority() {
        Fixture f = new Fixture();
        f.world.host = List.of(wreck("a")); f.authority.tickHost(); f.net.sent.clear();
        f.world.host = null; f.now.set(250); f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of(wreck("a"), wreck("a")); f.now.set(500);
        assertThrows(IllegalArgumentException.class, f.authority::tickHost);
        assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of(); f.now.set(750); f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.WRECK_REMOVE), f.types());
        assertEquals(Set.of("wreck:a"), f.world.consumed);
    }

    @Test void hostPollIsBoundedAndUnchangedRecordsStayQuiet() {
        Fixture f = new Fixture();
        f.world.host = List.of(wreck("a")); f.authority.tickHost(); f.net.sent.clear();
        f.world.host = List.of(); f.now.set(249); f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of(wreck("a")); f.now.set(250); f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
    }

    @Test void legacyCleanupWaitsForEverySuccessfullyAppliedRecord() {
        Fixture f = new Fixture(); f.world.legacy.add("local-only");
        f.authority.applySet(ids("a", "b"));
        f.authority.applyAdd(wreck("a").encode());
        assertEquals(List.of("local-only"), f.world.legacy);
        assertFalse(f.world.calls.contains("reconcile"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyAdd("broken"));
        assertEquals(List.of("local-only"), f.world.legacy);
        f.authority.applyAdd(wreck("b").encode());
        assertTrue(f.world.legacy.isEmpty());
        assertEquals(Set.of("wreck:a", "wreck:b"), f.world.guest.keySet());
    }

    @Test void failedMaterializationDoesNotPermitLegacyCleanupAndCanRetry() {
        Fixture f = new Fixture(); f.world.legacy.add("local-only");
        f.authority.applySet(ids("a", "b"));
        f.authority.applyAdd(wreck("a").encode()); f.world.failUpsert = "wreck:b";
        assertThrows(IllegalStateException.class, () -> f.authority.applyAdd(wreck("b").encode()));
        assertEquals(List.of("local-only"), f.world.legacy);
        f.world.failUpsert = null; f.authority.applyAdd(wreck("b").encode());
        assertTrue(f.world.legacy.isEmpty());
    }

    @Test void emptyCompleteManifestClearsLegacyAndTaggedReplicasWithoutReportingSalvage() {
        Fixture f = new Fixture(); f.world.legacy.add("local-only");
        f.authority.applyAdd(wreck("a").encode());
        f.authority.applySet(ids()); f.authority.tickGuest();
        assertTrue(f.world.guest.isEmpty()); assertTrue(f.world.legacy.isEmpty());
        assertTrue(f.net.sent.isEmpty()); assertEquals(Set.of("wreck:a"), f.world.consumed);
    }

    @Test void localSalvageFadeWinsAgainstQueuedHostUpsertBeforeNativeRemoval() {
        Fixture f = new Fixture(); f.authority.applyAdd(wreck("a").encode());
        f.world.fading.add("wreck:a");
        f.authority.applyAdd(wreck("a").encode());
        assertTrue(f.world.guest.isEmpty());
        assertEquals(Set.of("wreck:a"), f.world.consumed);
        f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
        assertEquals(List.of("wreck:a"), CoopWreckRecord.decodeIds(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "wrecks")));
    }

    @Test void observedLocalDisappearanceReportsConsumeButRemoteRemovalDoesNot() {
        Fixture f = new Fixture(); f.authority.applyAdd(wreck("a").encode());
        f.world.guest.remove("wreck:a"); f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
        Fixture remote = new Fixture(); remote.authority.applyAdd(wreck("a").encode());
        remote.authority.applyRemove("wreck:a"); remote.authority.tickGuest();
        assertTrue(remote.net.sent.isEmpty()); assertEquals(Set.of("wreck:a"), remote.world.consumed);
    }

    @Test void savedConsumptionSurvivesControllerResetAndResumeWithoutResurrection() {
        Fixture f = new Fixture(); f.world.consumed.add("wreck:a");
        f.authority.reset(); f.authority.applySet(ids("a"));
        f.authority.applyAdd(wreck("a").encode()); f.authority.tickGuest();
        assertTrue(f.world.guest.isEmpty());
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
        f.net.sent.clear(); f.authority.rearmFullBroadcast(); f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
        f.net.sent.clear(); f.authority.applyRemove("wreck:a"); f.authority.tickGuest();
        assertTrue(f.net.sent.isEmpty());
    }

    @Test void hostAbsenceAcknowledgesSavedConsumeButStaleAddStillCannotResurrect() {
        Fixture f = new Fixture(); f.world.consumed.add("wreck:a");
        f.authority.applySet(ids()); f.authority.tickGuest();
        assertTrue(f.net.sent.isEmpty());
        f.authority.applyAdd(wreck("a").encode()); f.authority.tickGuest();
        assertTrue(f.world.guest.isEmpty());
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
    }

    @Test void consumeAddressesKnownCanonicalWreckOnlyAndDuplicatesAreIdempotent() {
        Fixture f = new Fixture(); f.world.guest.put("wreck:a", wreck("a"));
        f.authority.applyConsume(ids("unknown"));
        assertEquals(Set.of("wreck:a"), f.world.guest.keySet());
        assertTrue(f.world.consumed.isEmpty()); assertTrue(f.net.sent.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyConsume("[\"native-a\"]"));
        f.authority.applyConsume(ids("a")); f.authority.applyConsume(ids("a"));
        assertTrue(f.world.guest.isEmpty()); assertEquals(Set.of("wreck:a"), f.world.consumed);
        assertEquals(List.of(CoopMessages.Type.WRECK_REMOVE, CoopMessages.Type.WRECK_REMOVE), f.types());
    }

    @Test void tombstonedHostWreckCannotBeRepublishedFromAnOldSavedEntity() {
        Fixture f = new Fixture(); f.world.host = List.of(wreck("a"));
        f.world.consumed.add("wreck:a"); f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.WRECK_SET), f.types());
        assertTrue(CoopWreckRecord.decodeIds(CoopMessages.requiredPayloadString(
                f.net.sent.get(0), "wrecks")).isEmpty());
        assertTrue(f.world.calls.contains("remove:wreck:a"));
    }

    @Test void reconnectConsumeReplayUsesBoundedBatches() {
        Fixture f = new Fixture();
        for (int i = 0; i < 300; i++) f.world.consumed.add("wreck:" + i);
        f.authority.tickGuest(); f.authority.tickGuest(); f.authority.tickGuest();
        assertEquals(List.of(128, 128, 44), f.net.sent.stream().map(m -> CoopWreckRecord.decodeIds(
                CoopMessages.requiredPayloadString(m, "wrecks")).size()).toList());
        f.authority.tickGuest(); assertEquals(3, f.net.sent.size());
    }

    @Test void heartbeatRetriesAnUnchangedPausedManifestAfterMaterializationFailure() {
        Fixture host = new Fixture(), guest = new Fixture();
        host.world.host = List.of(wreck("a")); host.authority.tickHost();
        guest.authority.applySet(ids("a")); guest.world.failUpsert = "wreck:a";
        assertThrows(IllegalStateException.class, () -> guest.authority.applyAdd(wreck("a").encode()));
        host.net.sent.clear(); host.now.set(5_000); host.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.WRECK_SET, CoopMessages.Type.WRECK_ADD), host.types());
        guest.world.failUpsert = null;
        guest.authority.applySet(CoopMessages.requiredPayloadString(host.net.sent.get(0), "wrecks"));
        guest.authority.applyAdd(CoopMessages.requiredPayloadString(host.net.sent.get(1), "wreck"));
        assertEquals(Set.of("wreck:a"), guest.world.guest.keySet());
        assertTrue(guest.world.calls.contains("reconcile"));
    }

    @Test void recoveryDuringTransportGraceIsDurableBeforeAnySend() {
        Fixture f = new Fixture(); f.authority.applyAdd(wreck("a").encode());
        f.world.fading.add("wreck:a"); f.authority.observeGuest();
        assertEquals(Set.of("wreck:a"), f.world.consumed); assertTrue(f.net.sent.isEmpty());
        f.authority.reset(); f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
    }

    @Test void partialTrapMutationIsSentBeforeTheSameWreckCanBeConsumed() {
        Fixture f = new Fixture(); f.authority.applyAdd(wreck("a").encode());
        CoopWreckMutation mutation = new CoopWreckMutation("trap-before-consume", 0, wreck("a"));
        f.world.pending.add(mutation); f.world.fading.add("wreck:a");
        f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.WRECK_MUTATION), f.types());
        assertEquals(Set.of("wreck:a"), f.world.consumed);
        f.authority.applyMutationAck(new CoopWreckMutation.Ack(mutation.mutationId(), mutation.id(),
                CoopWreckMutation.Status.ACCEPTED, 1, mutation.after()).encode());
        f.net.sent.clear(); f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.WRECK_CONSUME), f.types());
    }

    @Test void pendingPartialOutcomesRetryAfterTimeoutAndControllerResume() {
        Fixture f = new Fixture();
        f.world.pending.add(new CoopWreckMutation("durable", 0, wreck("a")));
        f.authority.tickGuest(); f.authority.tickGuest();
        assertEquals(1, f.net.sent.size());
        f.now.set(2_000); f.authority.tickGuest(); assertEquals(2, f.net.sent.size());
        f.authority.reset(); f.authority.tickGuest(); assertEquals(3, f.net.sent.size());
        assertEquals(1, f.world.pending.size());
    }

    @Test void hostMutationCommitAcknowledgesExactRequestAndRearmsFullState() {
        Fixture f = new Fixture();
        f.world.guest.put("wreck:a", wreck("a"));
        CoopWreckMutation mutation = new CoopWreckMutation("partial", 0, wreck("a"));
        f.authority.applyMutation(mutation.encode());
        assertEquals(List.of(CoopMessages.Type.WRECK_MUTATION_ACK), f.types());
        CoopWreckMutation.Ack ack = CoopWreckMutation.Ack.decode(CoopMessages.requiredPayloadString(f.net.sent.get(0), "ack"));
        assertEquals("partial", ack.mutationId()); assertEquals(CoopWreckMutation.Status.ACCEPTED, ack.status());
        assertEquals(1, ack.revision());
    }

    @Test void hostDisappearanceOnThePeriodicFullBranchStillPersistsATombstone() {
        Fixture f = new Fixture();
        f.world.host = List.of(wreck("a")); f.authority.tickHost();
        f.world.host = List.of(); f.now.set(5_000); f.authority.tickHost();
        assertEquals(Set.of("wreck:a"), f.world.consumed);
    }

    @Test void completeGuestAbsenceIsTerminalForPendingPartialOutcomesToo() {
        Fixture f = new Fixture(); f.authority.applyAdd(wreck("a").encode());
        f.world.pending.add(new CoopWreckMutation("partial", 0, wreck("a")));
        f.authority.applySet(ids());
        assertEquals(Set.of("wreck:a"), f.world.consumed);
        assertTrue(f.world.pending.isEmpty());
        f.authority.applyAdd(wreck("a").encode());
        assertTrue(f.world.guest.isEmpty());
    }

    @Test void perWreckRevisionTravelsWithItsAuthoritativeRecord() {
        Fixture f = new Fixture(); f.world.host = List.of(wreck("a"));
        f.world.revisions.put("wreck:a", 17L); f.authority.tickHost();
        assertEquals(17L, CoopMessages.requiredPayloadLong(f.net.sent.get(1), "revision"));
        Fixture guest = new Fixture(); guest.authority.applyAdd(wreck("a").encode(), 17L);
        assertEquals(17L, guest.world.revision("wreck:a"));
    }

    private static CoopWreckRecord wreck(String id) { return CoopWreckFixtures.sample("wreck:" + id); }
    private static String ids(String... ids) {
        return CoopWreckRecord.encodeIds(java.util.Arrays.stream(ids).map(x -> "wreck:" + x).toList());
    }
    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final RecordingNetService net = new RecordingNetService(CoopConnectionRole.HOST);
        final World world = new World();
        final CoopWreckAuthority authority = new CoopWreckAuthority(net,
                TestSessions.activeHostSession(), now::get, world);
        List<CoopMessages.Type> types() { return net.sent.stream().map(CoopMessages.Message::type).toList(); }
    }
    private static final class World implements CoopWreckAuthority.WreckWorld {
        List<CoopWreckRecord> host = List.of();
        final Map<String, CoopWreckRecord> guest = new LinkedHashMap<>();
        final Set<String> consumed = new LinkedHashSet<>(), fading = new LinkedHashSet<>();
        final List<String> legacy = new ArrayList<>(), calls = new ArrayList<>();
        String failUpsert;
        final List<CoopWreckMutation> pending = new ArrayList<>();
        final Map<String, Long> revisions = new LinkedHashMap<>();
        @Override public List<CoopWreckRecord> captureHost() { return host; }
        @Override public Set<String> taggedIds() { return Set.copyOf(guest.keySet()); }
        @Override public void upsert(CoopWreckRecord r) {
            if (r.id().equals(failUpsert)) throw new IllegalStateException("materialization failed");
            calls.add("upsert:" + r.id()); guest.put(r.id(), r);
        }
        @Override public void remove(String id) { calls.add("remove:" + id); guest.remove(id); fading.remove(id); }
        @Override public Set<String> locallyConsumed(Set<String> tracked) {
            Set<String> result = new LinkedHashSet<>(fading);
            for (String id : tracked) if (!guest.containsKey(id)) result.add(id);
            return result;
        }
        @Override public void reconcileLegacy(List<CoopWreckRecord> records) { calls.add("reconcile"); legacy.clear(); }
        @Override public Set<String> consumedIds() { return Set.copyOf(consumed); }
        @Override public void markConsumed(String id) { consumed.add(id); }
        @Override public long revision(String id) { return revisions.getOrDefault(id, 0L); }
        @Override public void upsert(CoopWreckRecord r, long revision) { upsert(r); revisions.put(r.id(), revision); }
        @Override public List<CoopWreckMutation> pendingMutations() { return List.copyOf(pending); }
        @Override public void discardMutations(String id) { pending.removeIf(m -> m.id().equals(id)); }
        @Override public void acknowledgeMutation(CoopWreckMutation.Ack ack) {
            pending.removeIf(m -> m.mutationId().equals(ack.mutationId()));
        }
        @Override public CoopWreckMutation.Ack applyMutation(CoopWreckMutation m) {
            CoopWreckRecord current = guest.get(m.id());
            if (current == null) return new CoopWreckMutation.Ack(m.mutationId(), m.id(), CoopWreckMutation.Status.CONSUMED, 0, null);
            long revision = revision(m.id());
            if (revision != m.baseRevision()) return new CoopWreckMutation.Ack(m.mutationId(), m.id(), CoopWreckMutation.Status.CONFLICT, revision, current);
            CoopWreckRecord merged = m.onto(current);
            guest.put(m.id(), merged); revisions.put(m.id(), revision + 1);
            return new CoopWreckMutation.Ack(m.mutationId(), m.id(), CoopWreckMutation.Status.ACCEPTED, revision + 1, merged);
        }
    }
}
