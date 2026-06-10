package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
