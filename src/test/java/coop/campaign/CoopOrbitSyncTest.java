package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoopOrbitSyncTest {

    @Test
    void stableIdDistinguishesNamedFromHexEntities() {
        assertTrue(CoopOrbitSync.isStableId("jangala"));
        assertTrue(CoopOrbitSync.isStableId("jangala_jump"));
        assertTrue(CoopOrbitSync.isStableId("corvus_relay"));
        // Pure hex ids are assigned per-instance and must not be matched by id.
        assertFalse(CoopOrbitSync.isStableId("372f"));
        assertFalse(CoopOrbitSync.isStableId("95af"));
        assertFalse(CoopOrbitSync.isStableId("1aa"));
    }

    @Test
    void signatureIsStableAcrossInstancesAndIgnoresAngle() {
        // Same orbit geometry -> same signature regardless of which client (angle not included).
        assertEquals(CoopOrbitSync.signature("corvus", 7800.0f, 400.0f),
                CoopOrbitSync.signature("corvus", 7800.0f, 400.0f));
        // Distinct radii -> distinct signatures (asteroids stay individually addressable).
        assertFalse(CoopOrbitSync.signature("1aa", 14019.1f, 911.54f)
                .equals(CoopOrbitSync.signature("1aa", 14145.2f, 911.54f)));
    }

    @Test
    void encodeDecodeRoundTrips() {
        List<CoopOrbitSync.OrbitEntry> entries = List.of(
                new CoopOrbitSync.OrbitEntry("jangala", "corvus", 4500f, 200f, 136.63f),
                new CoopOrbitSync.OrbitEntry("95af", "corvus", 7800f, 400f, 225.82f));
        List<CoopOrbitSync.OrbitEntry> back = CoopOrbitSync.decode(CoopOrbitSync.encode(entries));

        assertEquals(2, back.size());
        assertEquals("jangala", back.get(0).entityId());
        assertEquals("corvus", back.get(1).focusId());
        assertEquals(7800f, back.get(1).radius());
        assertEquals(225.82f, back.get(1).angle());
    }

    @Test
    void aFocuslessBodySurvivesTheRoundTripAsNullRatherThanEmpty() {
        // CoopDelimited writes a null field as "", so a focus-less body used to come back with
        // focusId "" -- which signature() maps to "" while the guest's own pool is keyed with
        // null -> "-", so the signature fallback could never match it, and the orbit apply's
        // "focusId != null" guard ran a sector-wide getEntityById("") once per entry.
        List<CoopOrbitSync.OrbitEntry> back = CoopOrbitSync.decode(CoopOrbitSync.encode(List.of(
                new CoopOrbitSync.OrbitEntry("1b9", null, 4500f, 200f, 136.63f))));

        assertEquals(1, back.size());
        assertNull(back.get(0).focusId());
        assertEquals(CoopOrbitSync.signature(null, 4500f, 200f),
                CoopOrbitSync.signature(back.get(0).focusId(), back.get(0).radius(), back.get(0).period()));
    }

    @Test
    void orbitSnapshotMessageRoundTrips() {
        String encoded = CoopOrbitSync.encode(List.of(
                new CoopOrbitSync.OrbitEntry("jangala", "corvus", 4500f, 200f, 136.63f)));
        CoopMessages.Message msg = CoopMessages.orbitSnapshot("s1", 1L, 10L, "corvus", encoded);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(msg));

        assertEquals(CoopMessages.Type.ORBIT_SNAPSHOT, decoded.type());
        assertEquals("corvus", CoopMessages.requiredPayloadString(decoded, "locationId"));
        List<CoopOrbitSync.OrbitEntry> back = CoopOrbitSync.decode(
                CoopMessages.requiredPayloadString(decoded, "orbits"));
        assertEquals(1, back.size());
        assertEquals(136.63f, back.get(0).angle());
    }

    @Test void optionalGhostPoseRoundTripsAlongsideUnchangedFiveColumnEntries() {
        CoopOrbitSync.OrbitEntry ordinary = new CoopOrbitSync.OrbitEntry("jangala", "corvus", 4500, 200, 136.63f);
        assertEquals("jangala|corvus|4500.0|200.0|136.63", CoopOrbitSync.encode(List.of(ordinary)));
        CoopOrbitSync.OrbitEntry ghost = new CoopOrbitSync.OrbitEntry("coopenc_alpha_system4", "coopenc_alpha_system3",
                352.4f, 29.6f, 289.36f, new CoopOrbitSync.Pose(492.1f, -757.3f, 7, -3, 150));
        List<CoopOrbitSync.OrbitEntry> entries = List.of(ordinary, ghost);
        assertEquals(entries, CoopOrbitSync.decode(CoopOrbitSync.encode(entries)));
        String encoded = CoopMessages.requiredPayloadString(CoopMessages.decode(CoopMessages.encode(
                CoopMessages.orbitSnapshot("s1", 1, 10, "alpha", CoopOrbitSync.encode(entries)))), "orbits");
        assertEquals(entries, CoopOrbitSync.decode(encoded));
        assertNull(CoopOrbitSync.decode("jangala|corvus|4500|200|136.63").get(0).pose());
    }

    @Test void ghostPoseRejectsPartialNonfiniteOutOfRangeAndNonEncounterValues() {
        String prefix = "coopenc_alpha_system4|coopenc_alpha_system3|352|29|289";
        for (String suffix : List.of("|1", "|1|2", "|1|2|3", "|1|2|3|4", "|1|2|3|4|5|6", "|NaN|2|3|4|5",
                "|1|Infinity|3|4|5", "|1000001|2|3|4|5", "|1|-1000001|3|4|5", "|1|2|100001|4|5", "|1|2|3|-100001|5",
                "|1|2|3|4|NaN", "|1|2|3|4|1000001")) {
            assertThrows(IllegalArgumentException.class, () -> CoopOrbitSync.decode(prefix + suffix), suffix);
        }
        assertThrows(IllegalArgumentException.class, () -> CoopOrbitSync.decode("ordinary|focus|1|2|3|1|2|3|4|5"));
        assertThrows(IllegalArgumentException.class, () -> new CoopOrbitSync.Pose(0, 0, Float.NaN, 0, 0));
        CoopOrbitSync.Pose bounds = new CoopOrbitSync.Pose(-1_000_000, 1_000_000, -100_000, 100_000, 1_000_000);
        CoopOrbitSync.OrbitEntry ghost = new CoopOrbitSync.OrbitEntry("coopenc_alpha_system4", null, 1, 2, 3, bounds);
        assertEquals(ghost, CoopOrbitSync.decode(CoopOrbitSync.encode(List.of(ghost))).get(0));
    }
}
