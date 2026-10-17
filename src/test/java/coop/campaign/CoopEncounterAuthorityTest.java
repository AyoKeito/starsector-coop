package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterAuthorityTest {
    @Test
    void changedOrderDoesNotBroadcastButOverlayChangesAndResumeDo() {
        Fixture f = new Fixture();
        f.world.host = List.of(record("a"), record("b"));
        f.authority.tickHost();
        assertEquals(CoopMessages.Type.ENC_SET, f.net.sent.get(0).type());
        assertEquals(2, CoopEncounterCodec.decodeSet(CoopMessages.requiredPayloadString(
                f.net.sent.get(0), "encounters")).size());
        f.net.sent.clear();
        f.world.host = List.of(record("b"), record("a"));
        f.now.set(250);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
        String id = record("a").entityId(record("a").entities().get(0));
        f.world.host = List.of(record("a").withOverlay(new CoopEncounterRecord.Overlay(
                List.of(id), List.of())), record("b"));
        f.now.set(500);
        f.authority.tickHost();
        assertEquals(1, f.net.sent.size());
        f.net.sent.clear();
        f.authority.rearmFullBroadcast();
        f.authority.tickHost();
        assertEquals(1, f.net.sent.size());
    }

    @Test
    void unavailableScanKeepsTheWorldButEmptyScanBroadcastsRemoval() {
        Fixture f = new Fixture();
        f.world.host = List.of(record("a"));
        f.authority.tickHost();
        f.net.sent.clear();
        f.world.host = null;
        f.now.set(250);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of();
        f.now.set(500);
        f.authority.tickHost();
        assertEquals(List.of(), CoopEncounterCodec.decodeSet(CoopMessages.requiredPayloadString(
                f.net.sent.get(0), "encounters")));
    }

    @Test
    void offlineHostObservationPersistsOriginalMembershipWithoutSendingSessionTraffic() {
        Fixture f = new Fixture();
        CoopEncounterRecord original = record("offline");
        f.world.host = List.of(original);

        f.authority.observeHost();

        assertEquals(1, f.world.hostCaptures);
        assertTrue(f.net.sent.isEmpty());
        String consumed = original.entityId(original.entities().get(0));
        CoopEncounterRecord current = original.withOverlay(new CoopEncounterRecord.Overlay(List.of(consumed), List.of()));
        f.world.host = List.of(current);
        f.now.set(250);
        f.authority.observeHost();
        assertEquals(2, f.world.hostCaptures);
        assertTrue(f.net.sent.isEmpty());

        f.now.set(500);
        f.authority.tickHost();

        assertEquals(List.of(current), CoopEncounterCodec.decodeSet(CoopMessages.requiredPayloadString(
                f.net.sent.get(0), "encounters")));
        assertEquals(original.entities(), current.entities());
    }

    @Test
    void failedCreationDefersRemovalAndRetriesWithoutANewSet() {
        Fixture f = new Fixture();
        f.world.local.put("old", record("old"));
        f.world.fail = "b";
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(
                CoopEncounterCodec.encodeSet(List.of(record("a"), record("b")))));
        assertTrue(f.world.local.containsKey("old"));
        f.world.fail = null;
        f.authority.tickGuest();
        assertEquals(Set.of("a", "b"), f.world.local.keySet());
        assertEquals(List.of("old"), f.world.removed);
    }

    @Test
    void repeatedSetsApplyInPlaceAndMalformedInputCannotDeleteAnything() {
        Fixture f = new Fixture();
        String encoded = CoopEncounterCodec.encodeSet(List.of(record("same")));
        f.authority.applySet(encoded);
        f.authority.applySet(encoded);
        assertEquals(Set.of("same"), f.world.local.keySet());
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet("broken"));
        assertEquals(Set.of("same"), f.world.local.keySet());
        assertTrue(f.world.removed.isEmpty());
    }

    @Test
    void localHintChangeUsesReliableWorldDeltaAndNoBaselineEcho() {
        Fixture f = new Fixture();
        CoopEncounterRecord record = record("a");
        String id = record.entityId(record.entities().get(0));
        var before = new CoopEncounterRecord.EntityState(id, "NONE", false, List.of());
        f.world.states = List.of(before);
        f.authority.applySet(CoopEncounterCodec.encodeSet(List.of(record)));
        f.authority.tickGuest();
        assertTrue(f.net.sent.isEmpty());
        var after = new CoopEncounterRecord.EntityState(id, "FULL", true, List.of(
                new CoopWreckRecord.MemoryValue("$visited", "BOOLEAN", "true", 0)));
        f.world.states = List.of(after);
        f.now.set(250);
        f.authority.tickGuest();
        CoopMessages.Message delta = f.net.sent.get(0);
        assertEquals(CoopMessages.Type.WORLD_DELTA, delta.type());
        assertEquals("ENCOUNTER_STATE", CoopMessages.requiredPayloadString(delta, "kind"));
        assertEquals(after, CoopEncounterCodec.decodeEntityState(
                CoopMessages.requiredPayloadString(delta, "newStateJson")));
        f.now.set(500);
        f.authority.tickGuest();
        assertEquals(1, f.net.sent.size());
    }

    @Test
    void inboundSetObservesLocalEditsBeforeItOverwritesTheBaseline() {
        Fixture f = new Fixture();
        CoopEncounterRecord record = record("a");
        String id = record.entityId(record.entities().get(0));
        f.world.states = List.of(new CoopEncounterRecord.EntityState(id, "NONE", false, List.of()));
        String set = CoopEncounterCodec.encodeSet(List.of(record));
        f.authority.applySet(set);
        f.world.states = List.of(new CoopEncounterRecord.EntityState(id, "FULL", false, List.of()));
        f.authority.applySet(set);
        assertEquals(1, f.net.sent.size());
        assertEquals(CoopMessages.Type.WORLD_DELTA, f.net.sent.get(0).type());
    }

    @Test
    void savedGuestHintEditIsReportedBeforeTheFirstHostSetCanOverwriteIt() {
        Fixture f = new Fixture();
        CoopEncounterRecord record = record("saved");
        String id = record.entityId(record.entities().get(0));
        var baseline = new CoopEncounterRecord.EntityState(id, "NONE", false, List.of());
        var local = new CoopEncounterRecord.EntityState(id, "FULL", true, List.of(
                new CoopWreckRecord.MemoryValue("$visited", "BOOLEAN", "true", -1)));
        // The stored Host recipe survived the native save, while local gameplay advanced its flags.
        // No earlier in-memory desired set or guestBaseline exists on this new authority.
        f.world.baselineStates = List.of(baseline);
        f.world.states = List.of(local);
        f.world.beforeUpsert = () -> {
            assertEquals(1, f.net.sent.size(), "save recovery must happen before native overlay apply");
            assertEquals(local, CoopEncounterCodec.decodeEntityState(
                    CoopMessages.requiredPayloadString(f.net.sent.get(0), "newStateJson")));
        };

        f.authority.applySet(CoopEncounterCodec.encodeSet(List.of(record)));

        assertEquals(1, f.net.sent.size());
        assertEquals(CoopMessages.Type.WORLD_DELTA, f.net.sent.get(0).type());
        assertEquals("ENCOUNTER_STATE", CoopMessages.requiredPayloadString(f.net.sent.get(0), "kind"));
        assertEquals(id, CoopMessages.requiredPayloadString(f.net.sent.get(0), "entityId"));
    }

    @Test
    void nativeBlackHoleClearAndRevisitAreRecoveredAndReportedBeforeSavedBaselineReplay() {
        Fixture f = new Fixture(); CoopEncounterRecord record = record("blackhole");
        String id = record.entityId(record.entities().get(0));
        var original = new CoopEncounterRecord.EntityState(id, "SEEN", null, List.of(
                new CoopWreckRecord.MemoryValue("$abyssalBlackHoleReadings", "BOOLEAN", "true", -1)));
        var completed = new CoopEncounterRecord.EntityState(id, "SEEN", null, List.of(
                new CoopWreckRecord.MemoryValue("$abyssalBlackHoleReadingsRevisit", "BOOLEAN", "true", -1)));
        f.world.baselineStates = List.of(original); f.world.states = List.of(completed);
        f.world.beforeUpsert = () -> {
            assertEquals(1, f.net.sent.size());
            CoopMessages.Message delta = f.net.sent.get(0);
            assertEquals("ENCOUNTER_STATE", CoopMessages.requiredPayloadString(delta, "kind"));
            assertEquals(completed, CoopEncounterCodec.decodeEntityState(CoopMessages.requiredPayloadString(delta, "newStateJson")));
        };
        f.authority.applySet(CoopEncounterCodec.encodeSet(List.of(record.withOverlay(
                new CoopEncounterRecord.Overlay(List.of(), List.of(original))))));
        f.now.set(250); f.authority.tickGuest();
        assertEquals(1, f.net.sent.size(), "the native completion is sent once, with no baseline echo");
    }

    @Test
    void resetDropsReceiverStateAcrossCampaigns() {
        Fixture f = new Fixture();
        f.world.fail = "a";
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(
                CoopEncounterCodec.encodeSet(List.of(record("a")))));
        f.authority.reset();
        f.world.fail = null;
        f.authority.tickGuest();
        assertTrue(f.world.local.isEmpty());
        f.authority.updateGuestPresence(true);
        assertTrue(f.world.grace);
    }

    @Test
    void savedConsumptionIsReportedBeforeFirstSetAndStopsAfterHostAcknowledgment() {
        Fixture f = new Fixture();
        CoopEncounterRecord record = record("a");
        String id = record.entityId(record.entities().get(0));
        f.world.consumed = Set.of(id);
        f.authority.applySet(CoopEncounterCodec.encodeSet(List.of(record)));
        assertEquals(1, f.net.sent.size());
        assertEquals("CONSUME", CoopMessages.requiredPayloadString(f.net.sent.get(0), "kind"));
        assertEquals(id, CoopMessages.requiredPayloadString(f.net.sent.get(0), "entityId"));
        f.authority.tickGuest();
        assertEquals(1, f.net.sent.size(), "runtime reliable queue owns retries");
        f.authority.reset();
        f.net.sent.clear();
        f.authority.applySet(CoopEncounterCodec.encodeSet(List.of(record.withOverlay(
                new CoopEncounterRecord.Overlay(List.of(id), List.of())))));
        assertTrue(f.net.sent.isEmpty(), "a saved Host tombstone acknowledges the local absence");
    }

    @Test void currentOrbitsAreCapturedOnlyForAnActualSetAndRefreshOnResumeWithoutPhaseHashTraffic() {
        Fixture f = new Fixture(); CoopEncounterRecord record = record("a"); f.world.host = List.of(record);
        f.world.hostOrbits = Map.of("a", orbit(record, 45));
        f.authority.tickHost();
        CoopMessages.Message delivered = CoopMessages.decode(CoopMessages.encode(f.net.sent.get(0)));
        assertEquals(f.world.hostOrbits, CoopEncounterOrbitSetCodec.decode(
                CoopMessages.requiredPayloadString(delivered, "orbits"), List.of(record)));
        assertEquals(1, f.world.orbitCaptures);
        f.net.sent.clear(); f.world.hostOrbits = Map.of("a", orbit(record, 90)); f.now.set(250);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty()); assertEquals(1, f.world.orbitCaptures);
        f.authority.rearmFullBroadcast(); f.authority.tickHost();
        assertEquals(2, f.world.orbitCaptures);
        assertEquals(f.world.hostOrbits, CoopEncounterOrbitSetCodec.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "orbits"), List.of(record)));
    }

    @Test void currentOrbitBundleSurvivesFailedNativeReplayAndRetriesBeforeRemovingOldSystems() {
        Fixture f = new Fixture(); CoopEncounterRecord record = record("a");
        f.world.local.put("old", record("old")); f.world.fail = "a";
        Map<String, String> orbits = Map.of("a", orbit(record, 145));
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(
                CoopEncounterCodec.encodeSet(List.of(record)), CoopEncounterOrbitSetCodec.encode(orbits, List.of(record))));
        assertTrue(f.world.appliedOrbits.isEmpty()); assertTrue(f.world.local.containsKey("old"));
        f.world.fail = null; f.world.failOrbit = "a";
        assertThrows(IllegalStateException.class, f.authority::tickGuest);
        assertTrue(f.world.local.containsKey("old")); assertTrue(f.world.removed.isEmpty());
        f.now.set(250); f.world.failOrbit = null; f.authority.tickGuest();
        assertEquals(orbits, f.world.appliedOrbits); assertEquals(Set.of("a"), f.world.local.keySet());
        assertEquals(List.of("old"), f.world.removed);
    }

    @Test void malformedOrCombinedOversizeBundleFailsBeforeAnyGuestOrHostMutation() {
        Fixture f = new Fixture(); CoopEncounterRecord small = record("small");
        String recipes = CoopEncounterCodec.encodeSet(List.of(small));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet(recipes, "broken"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet(recipes,
                "x".repeat(CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES + 1)));
        assertTrue(f.world.local.isEmpty()); assertTrue(f.world.appliedOrbits.isEmpty()); assertTrue(f.net.sent.isEmpty());

        List<CoopEncounterRecord.Entity> members = new ArrayList<>(); List<CoopOrbitSync.OrbitEntry> phases = new ArrayList<>();
        for (int i = 0; i < 950; i++) {
            members.add(new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, i, "\"".repeat(256)));
            phases.add(new CoopOrbitSync.OrbitEntry(CoopEncounterRecord.entityId("large", CoopEncounterRecord.Role.SYSTEM, i),
                    null, 1, 2, 3));
        }
        CoopEncounterRecord large = new CoopEncounterRecord("large", 0, "ep", 0, 0, 0, false,
                CoopEncounterRecord.Creator.PARENT, false, members, List.of(), CoopEncounterRecord.Overlay.empty());
        List<CoopEncounterRecord> records = List.of(large);
        String encodedRecipes = CoopEncounterCodec.encodeSet(records);
        Map<String, String> orbits = Map.of("large", CoopOrbitSync.encode(phases));
        String encodedOrbits = CoopEncounterOrbitSetCodec.encode(orbits, records);
        assertFalse(CoopSlipstreamAuthority.fitsFrame(CoopMessages.encounterSet("session", 0, 0, encodedRecipes, encodedOrbits)),
                "each field fits separately, but both JSON escape layers exceed one combined frame");
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet(encodedRecipes, encodedOrbits));
        assertTrue(f.world.local.isEmpty()); assertTrue(f.world.removed.isEmpty()); assertTrue(f.net.sent.isEmpty());
        f.world.host = records; f.world.hostOrbits = orbits;
        assertThrows(IllegalStateException.class, f.authority::tickHost);
        assertTrue(f.net.sent.isEmpty(), "never send a partial encounter set after combined-size rejection");
    }

    @Test void obsoleteRemovalRetryCannotRewindAnAlreadyAppliedOrbitBundleAndNewSetRefreshesIt() {
        Fixture f = new Fixture(); CoopEncounterRecord record = record("a");
        f.world.local.put("old", record("old")); f.world.failRemove = "old";
        String recipes = CoopEncounterCodec.encodeSet(List.of(record));
        String first = CoopEncounterOrbitSetCodec.encode(Map.of("a", orbit(record, 45)), List.of(record));
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(recipes, first));
        assertEquals(1, f.world.orbitApplications);
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet(recipes, "malformed"));
        String newerPeriodicOrbit = orbit(record, 90); f.world.appliedOrbits.put("a", newerPeriodicOrbit);
        f.world.failRemove = null; f.authority.tickGuest();
        assertEquals(1, f.world.orbitApplications);
        assertEquals(newerPeriodicOrbit, f.world.appliedOrbits.get("a"));
        String refreshed = orbit(record, 145);
        f.authority.applySet(recipes, CoopEncounterOrbitSetCodec.encode(Map.of("a", refreshed), List.of(record)));
        assertEquals(2, f.world.orbitApplications); assertEquals(refreshed, f.world.appliedOrbits.get("a"));
    }

    private static String orbit(CoopEncounterRecord record, float angle) {
        return CoopOrbitSync.encode(List.of(new CoopOrbitSync.OrbitEntry(record.entityId(record.entities().get(0)), null, 1500, 50, angle)));
    }

    static CoopEncounterRecord record(String id) {
        return new CoopEncounterRecord(id, 43, "abyssal_1_2", 1000, 2000, 3, true,
                CoopEncounterRecord.Creator.PARENT, false,
                List.of(new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, 0, "planet")),
                List.of(), CoopEncounterRecord.Overlay.empty());
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final RecordingNetService net = new RecordingNetService(CoopConnectionRole.HOST);
        final World world = new World();
        final CoopEncounterAuthority authority = new CoopEncounterAuthority(net,
                TestSessions.activeHostSession(), now::get, world);
    }

    private static final class World implements CoopEncounterAuthority.EncounterWorld {
        List<CoopEncounterRecord> host = List.of();
        List<CoopEncounterRecord.EntityState> states = List.of();
        List<CoopEncounterRecord.EntityState> baselineStates = List.of();
        Runnable beforeUpsert = () -> { };
        final Map<String, CoopEncounterRecord> local = new LinkedHashMap<>();
        final List<String> removed = new ArrayList<>();
        String fail;
        String failOrbit;
        String failRemove;
        Map<String, String> hostOrbits;
        final Map<String, String> appliedOrbits = new LinkedHashMap<>();
        Set<String> consumed = Set.of();
        boolean grace;
        int hostCaptures;
        int orbitCaptures;
        int orbitApplications;
        @Override public List<CoopEncounterRecord> captureHost() { hostCaptures++; return host; }
        @Override public Set<String> taggedIds() { return Set.copyOf(local.keySet()); }
        @Override public void upsert(CoopEncounterRecord record) {
            beforeUpsert.run();
            if (record.instanceId().equals(fail)) throw new IllegalStateException("native creation failed");
            local.put(record.instanceId(), record);
        }
        @Override public void remove(String id) {
            if (id.equals(failRemove)) throw new IllegalStateException("native dialog holds obsolete system");
            removed.add(id); local.remove(id);
        }
        @Override public void updateGuestPresence(boolean value) { grace = value; }
        @Override public List<CoopEncounterRecord.EntityState> guestStates() { return states; }
        @Override public Set<String> guestConsumedIds() { return consumed; }
        @Override public List<CoopEncounterRecord.EntityState> guestBaselineStates() { return baselineStates; }
        @Override public Map<String, String> captureCurrentOrbits(List<CoopEncounterRecord> records) {
            orbitCaptures++;
            return hostOrbits == null ? CoopEncounterAuthority.EncounterWorld.super.captureCurrentOrbits(records) : hostOrbits;
        }
        @Override public void applyCurrentOrbits(CoopEncounterRecord record, String encodedOrbits) {
            if (record.instanceId().equals(failOrbit)) throw new IllegalStateException("native orbit apply failed");
            orbitApplications++;
            appliedOrbits.put(record.instanceId(), encodedOrbits);
        }
    }
}
