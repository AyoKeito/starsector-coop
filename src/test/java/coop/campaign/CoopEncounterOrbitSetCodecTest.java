package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterOrbitSetCodecTest {
    @Test void oldFiveFieldAndExactGhostPoseEntriesRoundTripForOriginalSystemMembers() {
        CoopEncounterRecord record = CoopEncounterFixtures.sample("a");
        List<CoopOrbitSync.OrbitEntry> entries = List.of(
                entry("a", CoopEncounterRecord.Role.SYSTEM, 1, CoopEncounterRecord.Role.SYSTEM, 0),
                new CoopOrbitSync.OrbitEntry(id("a", CoopEncounterRecord.Role.SYSTEM, 2), id("a", CoopEncounterRecord.Role.SYSTEM, 1),
                        300, 25, 45, new CoopOrbitSync.Pose(1, -2, 3, -4, 127.5f)),
                new CoopOrbitSync.OrbitEntry(id("a", CoopEncounterRecord.Role.SYSTEM, 3), null, 300, 25, 45));
        String phases = CoopOrbitSync.encode(entries);
        String encoded = CoopEncounterOrbitSetCodec.encode(Map.of("a", phases), List.of(record));
        Map<String, String> decoded = CoopEncounterOrbitSetCodec.decode(encoded, List.of(record));
        assertEquals(Map.of("a", phases), decoded);
        assertEquals(entries, CoopOrbitSync.decode(decoded.get("a")));
        assertThrows(UnsupportedOperationException.class, () -> decoded.put("b", ""));
    }

    @Test void orderingIsDeterministicAndEmptyTablesAndEmptySetsAreExplicit() {
        List<CoopEncounterRecord> records = List.of(CoopEncounterFixtures.sample("b"), CoopEncounterFixtures.sample("a"));
        Map<String, String> phases = new LinkedHashMap<>(); phases.put("b", ""); phases.put("a", "");
        String encoded = CoopEncounterOrbitSetCodec.encode(phases, records);
        assertEquals(encoded, CoopEncounterOrbitSetCodec.encode(Map.of("a", "", "b", ""), List.of(records.get(1), records.get(0))));
        assertEquals(List.of("a", "b"), new ArrayList<>(CoopEncounterOrbitSetCodec.decode(encoded, records).keySet()));
        assertEquals(Map.of("a", "", "b", ""), CoopEncounterOrbitSetCodec.empty(records));
        String empty = CoopEncounterOrbitSetCodec.encode(Map.of(), List.of());
        assertEquals(Map.of(), CoopEncounterOrbitSetCodec.decode(empty, List.of()));
        assertEquals(Map.of(), CoopEncounterOrbitSetCodec.empty(List.of()));
    }

    @Test void instanceKeysMustExactlyMatchUniqueDesiredRecipes() {
        CoopEncounterRecord record = CoopEncounterFixtures.sample("a");
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(Map.of(), List.of(record)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(Map.of("b", ""), List.of(record)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(Map.of("a", "", "b", ""), List.of(record)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(Map.of("a", ""), List.of(record, record)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode("ENC_ORBITS|1|0", List.of(record)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode("ENC_ORBITS|1|1|b|", List.of(record)));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode("ENC_ORBITS|1|2|a||a|", List.of(record)));
        List<CoopEncounterRecord> tooMany = new ArrayList<>(); Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index <= CoopEncounterRecord.MAX_ENCOUNTERS; index++) {
            String key = "i" + index; tooMany.add(CoopEncounterFixtures.sample(key)); values.put(key, "");
        }
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(values, tooMany));
    }

    @Test void bodyAndFocusMustBeSurvivingOriginalMembersOfTheSameInstance() {
        String a = id("a", CoopEncounterRecord.Role.SYSTEM, 1), focus = id("a", CoopEncounterRecord.Role.SYSTEM, 0);
        reject(id("b", CoopEncounterRecord.Role.SYSTEM, 1) + "|" + focus + "|1|2|3");
        reject(a + "|" + id("b", CoopEncounterRecord.Role.SYSTEM, 0) + "|1|2|3");
        reject(id("a", CoopEncounterRecord.Role.JUMP_POINT, 1) + "|" + focus + "|1|2|3");
        reject(id("a", CoopEncounterRecord.Role.JUMP_POINT, 0) + "|" + focus + "|1|2|3");
        reject(a + "|" + id("a", CoopEncounterRecord.Role.ANCHOR, 0) + "|1|2|3");
        reject(id("a", CoopEncounterRecord.Role.WELL, 0) + "|" + focus + "|1|2|3");
        reject(a + "|" + id("b", CoopEncounterRecord.Role.ANCHOR, 0) + "|1|2|3");
        reject("dynamic-npc-body|" + focus + "|1|2|3");
        reject(id("a", CoopEncounterRecord.Role.SYSTEM, 99) + "|" + focus + "|1|2|3");
        reject(id("a", CoopEncounterRecord.Role.SYSTEM, 4) + "|" + focus + "|1|2|3"); // Saved consumed member.
        reject(a + "|" + id("a", CoopEncounterRecord.Role.SYSTEM, 4) + "|1|2|3");
        String line = a + "|" + focus + "|1|2|3";
        reject(line + "\n" + line);
    }

    @Test void malformedOrbitLinesCannotInheritTheLegacyDecodersSilentSkip() {
        String a = id("a", CoopEncounterRecord.Role.SYSTEM, 1), focus = id("a", CoopEncounterRecord.Role.SYSTEM, 0);
        String prefix = a + "|" + focus;
        for (String malformed : List.of("one-field", prefix + "|1|2", prefix + "|1|2|3|4",
                prefix + "|1|2|3|4|5|6", prefix + "|1|2|3|4|5|6|7", prefix + "|1|2|3|4|5|6|7|8|9",
                prefix + "|1|2|3\n", "\n" + prefix + "|1|2|3", prefix + "|1|2|3\r",
                prefix + "|1|2|\\q", prefix + "|1|2|3\\")) reject(malformed);
    }

    @Test void finiteAndBoundedOrbitGeometryIsRequired() {
        String prefix = id("a", CoopEncounterRecord.Role.SYSTEM, 1) + "|" + id("a", CoopEncounterRecord.Role.SYSTEM, 0);
        for (String geometry : List.of("NaN|2|3", "1|Infinity|3", "1|2|-Infinity", "-1|2|3", "1000001|2|3",
                "1|0|3", "1|-1|3", "1|1000001|3", "1|2|1000001", "1|2|-1000001", "word|2|3")) reject(prefix + "|" + geometry);
    }

    @Test void exactPoseIsRestrictedToTheResolvedGhostAndRetainsItsNativeBounds() {
        String focus = id("a", CoopEncounterRecord.Role.SYSTEM, 0);
        reject(id("a", CoopEncounterRecord.Role.SYSTEM, 1) + "|" + focus + "|1|2|3|4|5|6|7|8");
        String ghost = id("a", CoopEncounterRecord.Role.SYSTEM, 2) + "|" + focus + "|1|2|3|";
        reject(ghost + "0|0|0|0"); // The prior nine-column proposal was never deployed.
        for (String pose : List.of("NaN|0|0|0|0", "1000001|0|0|0|0", "0|-1000001|0|0|0", "0|0|100001|0|0", "0|0|0|-100001|0",
                "0|0|0|0|NaN", "0|0|0|0|Infinity", "0|0|0|0|-Infinity", "0|0|0|0|word", "0|0|0|0|1000001", "0|0|0|0|-1000001")) reject(ghost + pose);
    }

    @Test void unsupportedVersionsCountsEscapesAndTrailingFieldsFailBeforeApplication() {
        List<CoopEncounterRecord> none = List.of();
        for (String malformed : List.of("", "WRONG|1|0", "ENC_ORBITS|2|0", "ENC_ORBITS|1|-1", "ENC_ORBITS|1|129",
                "ENC_ORBITS|1|01", "ENC_ORBITS|1|+1", "ENC_ORBITS|1|word", "ENC_ORBITS|1|0|tail",
                "ENC_ORBITS|1|0\\", "ENC_ORBITS|1|0\\q", "ENC_ORBITS|1|0\n"))
            assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode(malformed, none), malformed);
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode(null, none));
        Map<String, String> nullValue = new LinkedHashMap<>(); nullValue.put("a", null);
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(nullValue, List.of(CoopEncounterFixtures.sample("a"))));
    }

    @Test void byteAndDoubleJsonEscapeLimitsApplyBeforeParsing() {
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode(
                "x".repeat(CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES + 1), List.of()));
        assertTrue(tooLong.getMessage().contains("oversized"));
        IllegalArgumentException unicode = assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode(
                "€".repeat(CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES / 2), List.of()));
        assertTrue(unicode.getMessage().contains("byte limit"));
        IllegalArgumentException escaped = assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode(
                "\\".repeat(300_000), List.of()));
        assertTrue(escaped.getMessage().contains("escaped JSON"));
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(Map.of("a", "x".repeat(
                CoopEncounterOrbitSetCodec.MAX_ENCODED_BYTES)), List.of(CoopEncounterFixtures.sample("a"))));
        IllegalArgumentException aggregate = assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(
                Map.of("a", "x".repeat(200_000), "b", "x".repeat(200_000)),
                List.of(CoopEncounterFixtures.sample("a"), CoopEncounterFixtures.sample("b"))));
        assertTrue(aggregate.getMessage().contains("Orbit map exceeds"));
    }

    @Test void tooManyEntriesAreRejectedEvenWhenTheirStringsFitTheByteLimit() {
        String line = "x||||";
        reject((line + "\n").repeat(CoopEncounterRecord.MAX_ENTITIES) + line);
    }

    private static void reject(String phases) {
        CoopEncounterRecord record = CoopEncounterFixtures.sample("a");
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.encode(Map.of("a", phases), List.of(record)));
        String outer = "ENC_ORBITS|1|1|a|" + CoopDelimited.field(phases);
        assertThrows(IllegalArgumentException.class, () -> CoopEncounterOrbitSetCodec.decode(outer, List.of(record)));
    }

    private static CoopOrbitSync.OrbitEntry entry(String instance, CoopEncounterRecord.Role bodyRole, int ordinal,
                                                  CoopEncounterRecord.Role focusRole, int focusOrdinal) {
        return new CoopOrbitSync.OrbitEntry(id(instance, bodyRole, ordinal), id(instance, focusRole, focusOrdinal), 300, 25, 45);
    }
    private static String id(String instance, CoopEncounterRecord.Role role, int ordinal) {
        return CoopEncounterRecord.entityId(instance, role, ordinal);
    }
}
