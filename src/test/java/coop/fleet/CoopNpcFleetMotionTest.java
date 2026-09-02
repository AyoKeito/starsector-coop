package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code NPC_FLEET_MOTION} section codec. This stream is the mod's largest by volume — every
 * eligible fleet in an occupied location, ten times a second — so its field widths and its Phase 20
 * M4 change-mask rules are worth pinning.
 */
class CoopNpcFleetMotionTest {

    private static CoopSensorSync.Profile sensors(float profile, float strength) {
        return new CoopSensorSync.Profile(profile, 0f, 0f, 1f, strength);
    }

    private static CoopNpcFleetMotion motion(String id, float x, float y,
                                             CoopSensorSync.Profile sensors) {
        return new CoopNpcFleetMotion(id, "corvus", x, y, 1f, -1f, sensors);
    }

    @Test
    void onGridFullSectionsRoundTripExactly() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("npc-1", "corvus", -14625.25f, 3080.75f, -14f, 3.25f,
                        sensors(150f, 90f)),
                new CoopNpcFleetMotion("npc-2", "askonia", 0f, 0f, 0f, 0f, sensors(650.5f, 420.3f)));

        assertEquals(motions,
                CoopNpcFleetMotion.decodeSection(CoopNpcFleetMotion.encodeFullSection(motions), null));
    }

    @Test
    void coordinatesAreQuantizedToTheQuarterUnitGridOnTheWire() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("npc-1", "corvus", -2324.3894f, 14625.111f, -14.077f,
                        3.3333333f, sensors(150f, 90f)));

        String encoded = CoopNpcFleetMotion.encodeFullSection(motions);
        List<String> fields = CoopFleetCodec.split(encoded.split("\n", -1)[1]);

        assertEquals("-2324.5", fields.get(2));
        assertEquals("14625.0", fields.get(3));
        assertEquals("-14.0", fields.get(4));
        assertEquals("3.25", fields.get(5));

        CoopNpcFleetMotion decoded = CoopNpcFleetMotion.decodeSection(encoded, null).get(0);
        assertEquals(-2324.5f, decoded.x());
        assertEquals(3.25f, decoded.velocityY());
    }

    @Test
    void anEmptySectionEncodesAndDecodesAsEmpty() {
        assertEquals(List.of(),
                CoopNpcFleetMotion.decodeSection(CoopNpcFleetMotion.encodeFullSection(List.of()), null));
    }

    @Test
    void aFullSectionAlwaysCarriesEverySensorField() {
        String encoded = CoopNpcFleetMotion.encodeFullSection(
                List.of(motion("npc-1", 10f, 20f, sensors(150f, 90f))));
        List<String> fields = CoopFleetCodec.split(encoded.split("\n", -1)[1]);

        assertEquals("F", encoded.split("\n", -1)[0]);
        assertEquals(String.valueOf(CoopSensorSync.MASK_ALL), fields.get(6));
        assertEquals(7 + CoopSensorSync.FIELD_COUNT, fields.size());
    }

    @Test
    void anUnchangedSensorProfileCostsAZeroMaskAndNoFields() {
        CoopSensorSync.Profile steady = sensors(150f, 90f);
        List<CoopNpcFleetMotion> first = List.of(motion("npc-1", 10f, 20f, steady));
        List<CoopNpcFleetMotion> second = List.of(motion("npc-1", 30f, 40f, steady));

        String delta = CoopNpcFleetMotion.encodeDeltaSection(second, first);
        List<String> fields = CoopFleetCodec.split(delta.split("\n", -1)[1]);

        assertEquals("D", delta.split("\n", -1)[0]);
        assertEquals("0", fields.get(6));
        assertEquals(7, fields.size(), "no sensor fields ride a zero mask");
        assertTrue(delta.length() < CoopNpcFleetMotion.encodeFullSection(second).length());
    }

    @Test
    void onlyTheChangedSensorFieldsAreWritten() {
        CoopNpcFleetMotion before = motion("npc-1", 10f, 20f,
                new CoopSensorSync.Profile(150f, 0f, 0f, 1f, 90f));
        // Strength (bit 4) moves; the other four terms do not.
        CoopNpcFleetMotion after = motion("npc-1", 30f, 40f,
                new CoopSensorSync.Profile(150f, 0f, 0f, 1f, 240f));

        String delta = CoopNpcFleetMotion.encodeDeltaSection(List.of(after), List.of(before));
        List<String> fields = CoopFleetCodec.split(delta.split("\n", -1)[1]);

        assertEquals(String.valueOf(1 << 4), fields.get(6));
        assertEquals(8, fields.size());
        assertEquals("240.0", fields.get(7));

        List<CoopNpcFleetMotion> decoded =
                CoopNpcFleetMotion.decodeSection(delta, List.of(before));
        assertEquals(after, decoded.get(0));
    }

    @Test
    void aQuantizedNoOpChangeIsNotConsideredAChange() {
        // 150.0 and 150.04 both encode as "150.0" on the 0.1 sensor grid, so the mask must stay clear:
        // the mask means "the receiver would decode a different number", not "the float differs".
        CoopNpcFleetMotion before = motion("npc-1", 10f, 20f, sensors(150f, 90f));
        CoopNpcFleetMotion after = motion("npc-1", 30f, 40f, sensors(150.04f, 90.02f));

        String delta = CoopNpcFleetMotion.encodeDeltaSection(List.of(after), List.of(before));

        assertEquals("0", CoopFleetCodec.split(delta.split("\n", -1)[1]).get(6));
    }

    @Test
    void aFleetAbsentFromTheBaselineIsWrittenWithAFullMask() {
        List<CoopNpcFleetMotion> baseline = List.of(motion("npc-1", 10f, 20f, sensors(150f, 90f)));
        CoopNpcFleetMotion newcomer = motion("npc-2", 50f, 60f, sensors(300f, 120f));

        String delta = CoopNpcFleetMotion.encodeDeltaSection(
                List.of(baseline.get(0), newcomer), baseline);
        String[] lines = delta.split("\n", -1);

        assertEquals("0", CoopFleetCodec.split(lines[1]).get(6), "the known fleet stays a delta");
        assertEquals(String.valueOf(CoopSensorSync.MASK_ALL), CoopFleetCodec.split(lines[2]).get(6));
        assertEquals(List.of(baseline.get(0), newcomer),
                CoopNpcFleetMotion.decodeSection(delta, baseline));
    }

    @Test
    void aDatagramsSectionsDecodeAgainstEachOtherInWireOrder() {
        List<CoopNpcFleetMotion> previous = List.of(motion("npc-1", 10f, 20f, sensors(150f, 90f)));
        List<CoopNpcFleetMotion> current = List.of(motion("npc-1", 30f, 40f, sensors(150f, 90f)));

        List<List<CoopNpcFleetMotion>> decoded = CoopNpcFleetMotion.decodeDatagram(List.of(
                CoopNpcFleetMotion.encodeFullSection(previous),
                CoopNpcFleetMotion.encodeDeltaSection(current, previous)));

        assertEquals(2, decoded.size());
        assertEquals(previous, decoded.get(0));
        assertEquals(current, decoded.get(1), "section 2 resolved its cleared mask against section 1");
        assertNotEquals(decoded.get(0), decoded.get(1));
    }

    @Test
    void aClearedMaskWithNoBaselineIsRejectedRatherThanDefaulted() {
        // Defaulting would hand the receiver Profile.UNKNOWN, whose zero profile reads as
        // "always identified" — the exact inversion CoopSensorSync exists to prevent.
        List<CoopNpcFleetMotion> baseline = List.of(motion("npc-1", 10f, 20f, sensors(150f, 90f)));
        String delta = CoopNpcFleetMotion.encodeDeltaSection(
                List.of(motion("npc-1", 30f, 40f, sensors(150f, 90f))), baseline);

        assertThrows(IllegalArgumentException.class,
                () -> CoopNpcFleetMotion.decodeSection(delta, null));
    }

    @Test
    void aMaskThatDisagreesWithTheFieldCountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopNpcFleetMotion.decodeSection("F\nnpc-1|corvus|0.0|0.0|0.0|0.0|31|150.0", null));
        assertThrows(IllegalArgumentException.class,
                () -> CoopNpcFleetMotion.decodeSection("F\nnpc-1|corvus|0.0|0.0|0.0|0.0|99", null));
    }

    @Test
    void anUnknownSectionModeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopNpcFleetMotion.decodeSection("X\nnpc-1|corvus|0|0|0|0|0", null));
    }

    @Test
    void separatorBearingIdsSurviveTheRoundTrip() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("f|1", "hyper\nspace", 0f, 0f, 0f, 0f, sensors(220.5f, 90f)));

        assertEquals(motions,
                CoopNpcFleetMotion.decodeSection(CoopNpcFleetMotion.encodeFullSection(motions), null));
    }
}
