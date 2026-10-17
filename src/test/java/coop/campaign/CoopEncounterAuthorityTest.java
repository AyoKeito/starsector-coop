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
        Set<String> consumed = Set.of();
        boolean grace;
        int hostCaptures;
        @Override public List<CoopEncounterRecord> captureHost() { hostCaptures++; return host; }
        @Override public Set<String> taggedIds() { return Set.copyOf(local.keySet()); }
        @Override public void upsert(CoopEncounterRecord record) {
            beforeUpsert.run();
            if (record.instanceId().equals(fail)) throw new IllegalStateException("native creation failed");
            local.put(record.instanceId(), record);
        }
        @Override public void remove(String id) { removed.add(id); local.remove(id); }
        @Override public void updateGuestPresence(boolean value) { grace = value; }
        @Override public List<CoopEncounterRecord.EntityState> guestStates() { return states; }
        @Override public Set<String> guestConsumedIds() { return consumed; }
        @Override public List<CoopEncounterRecord.EntityState> guestBaselineStates() { return baselineStates; }
    }
}
