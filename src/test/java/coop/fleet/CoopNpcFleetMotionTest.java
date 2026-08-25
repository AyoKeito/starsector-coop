package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code NPC_FLEET_MOTION} batch codec. This stream is the mod's largest by volume — every fleet
 * in an occupied location, ten times a second — so its field widths are worth pinning.
 */
class CoopNpcFleetMotionTest {

    private static CoopSensorSync.Profile sensors(float profile, float strength) {
        return new CoopSensorSync.Profile(profile, 0f, 0f, 1f, strength);
    }

    @Test
    void onGridBatchesRoundTripExactly() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("npc-1", "corvus", -14625.25f, 3080.75f, -14f, 3.25f,
                        sensors(150f, 90f)),
                new CoopNpcFleetMotion("npc-2", "askonia", 0f, 0f, 0f, 0f, sensors(650.5f, 420.3f)));

        assertEquals(motions, CoopNpcFleetMotion.decodeBatch(CoopNpcFleetMotion.encodeBatch(motions)));
    }

    @Test
    void coordinatesAreQuantizedToTheQuarterUnitGridOnTheWire() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("npc-1", "corvus", -2324.3894f, 14625.111f, -14.077f,
                        3.3333333f, sensors(150f, 90f)));

        String encoded = CoopNpcFleetMotion.encodeBatch(motions);
        List<String> fields = CoopFleetCodec.split(encoded.split("\n", -1)[1]);

        assertEquals("-2324.5", fields.get(2));
        assertEquals("14625.0", fields.get(3));
        assertEquals("-14.0", fields.get(4));
        assertEquals("3.25", fields.get(5));

        CoopNpcFleetMotion decoded = CoopNpcFleetMotion.decodeBatch(encoded).get(0);
        assertEquals(-2324.5f, decoded.x());
        assertEquals(3.25f, decoded.velocityY());
    }

    @Test
    void anEmptyBatchEncodesAndDecodesAsEmpty() {
        assertEquals(List.of(), CoopNpcFleetMotion.decodeBatch(CoopNpcFleetMotion.encodeBatch(List.of())));
    }
}
