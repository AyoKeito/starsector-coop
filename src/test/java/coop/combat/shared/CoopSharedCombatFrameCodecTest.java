package coop.combat.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSharedCombatFrameCodecTest {

    @Test
    void roundTripPreservesShipKinematics() {
        List<CoopSharedCombatFrameCodec.ShipFrame> frames = List.of(
                new CoopSharedCombatFrameCodec.ShipFrame(
                        "ship-a", "onslaught", "Atlas", 0, 100.5f, -20f, 90f, 0.8f, 0.25f, true),
                new CoopSharedCombatFrameCodec.ShipFrame(
                        "ship-b", "wolf", "Wolf", 1, 0f, 0f, 0f, 0f, 1f, false));

        String encoded = CoopSharedCombatFrameCodec.encode(frames);
        List<CoopSharedCombatFrameCodec.ShipFrame> decoded = CoopSharedCombatFrameCodec.decode(encoded);

        assertEquals(2, decoded.size());
        assertEquals("ship-a", decoded.get(0).shipId());
        assertEquals("onslaught", decoded.get(0).hullId());
        assertEquals(0, decoded.get(0).owner());
        assertEquals(100.5f, decoded.get(0).x(), 0.001f);
        assertEquals(-20f, decoded.get(0).y(), 0.001f);
        assertEquals(90f, decoded.get(0).facing(), 0.001f);
        assertEquals(0.8f, decoded.get(0).hullFraction(), 0.001f);
        assertEquals(0.25f, decoded.get(0).fluxLevel(), 0.001f);
        assertTrue(decoded.get(0).alive());
        assertEquals("ship-b", decoded.get(1).shipId());
        assertEquals(false, decoded.get(1).alive());
    }

    @Test
    void emptyAndMalformedBodiesDecodeToEmpty() {
        assertTrue(CoopSharedCombatFrameCodec.decode(null).isEmpty());
        assertTrue(CoopSharedCombatFrameCodec.decode("").isEmpty());
        assertTrue(CoopSharedCombatFrameCodec.decode("not-a-frame").isEmpty());
    }
}
