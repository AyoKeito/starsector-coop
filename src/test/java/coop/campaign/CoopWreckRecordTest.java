package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoopWreckRecordTest {
    @Test void standardWreckRoundTripsAllFields() {
        CoopWreckRecord sample = CoopWreckFixtures.sample("wreck:a");
        assertEquals(sample, CoopWreckRecord.decode(sample.encode()));
    }

    @Test void fullVariantCaptainSpecialAndCustomDropsSurviveNestedEscapes() {
        CoopShipDetail variant = new CoopShipDetail("", "ISS \\|\n", "custom|hull", "hound_dhull", 0,
                5, 7, List.of("dmod\\|x"), List.of("heavyarmor"), List.of(),
                List.of("solar_shielding"), List.of("safetyoverrides"), Map.of("slot|1", "gun\\"), Map.of());
        CoopPersonDetail captain = new CoopPersonDetail("captain", "A|B", "C\\D", "ANY", "portrait",
                "reckless", "captain", "fleet_commander", "neutral", 5, 77,
                CoopPersonDetail.Role.OFFICER, 0, 0, 0, 60, Map.of("helmsmanship", 2f));
        CoopWreckRecord.Ship ship = new CoopWreckRecord.Ship("WRECKED", null, variant,
                List.of(new CoopWreckRecord.VariantState(List.of(), List.of("unboardable", "always_recoverable"),
                        "REFIT", "hound|original", true, false)), captain,
                new CoopWreckRecord.CaptainState("alpha_core", List.of("officer|tag"), "HIGH", "voice\\x"),
                "name|\\\n", "member", false, false, false, 0.99f);
        CoopWreckRecord.Recovery recovery = new CoopWreckRecord.Recovery("desc|\\\n", true, false, true,
                List.of(ship, CoopWreckFixtures.sample("wreck:b").ship()));
        CoopWreckRecord sample = new CoopWreckRecord(1, "wreck:a", "native", "Wreck|\\", "neutral",
                1, 2, 3, 4, 5, -6, 7, 8, false, true, ship, Long.MIN_VALUE,
                recovery, recovery,
                List.of(new CoopWreckRecord.Drop(null, 2, 3, -1, .75f,
                        List.of(new CoopWreckRecord.DropRow("item_modspec:{tags:[a,b]}", "custom", 3, 7)))),
                List.of(), new CoopWreckRecord.ExtraCargo(true, 5f,
                List.of(new CoopWreckRecord.Cargo("SPECIAL", "modspec", "x:y|\\\n", 1))),
                CoopWreckRecord.ExtraCargo.absent(), null, null, 1, true, 25,
                List.of(new CoopWreckRecord.Modifier("MULT", "mod", "desc|", 2)), List.of("unrecoverable"),
                List.of(new CoopWreckRecord.MemoryValue("$fromGhost", "STRING", "x|\\\n", 3)));
        assertEquals(sample, CoopWreckRecord.decode(sample.encode()));
        assertEquals(sample, CoopWreckRecord.decode(CoopDelimited.split(CoopDelimited.field(sample.encode())).get(0)));
    }

    @Test void nullableNativeFlagsRemainDistinctFromFalseAndEmpty() {
        CoopWreckRecord.Ship ship = CoopWreckFixtures.sample("wreck:a").ship();
        assertNull(ship.nameAlwaysKnown());
        CoopWreckRecord decoded = CoopWreckRecord.decode(CoopWreckFixtures.sample("wreck:a").encode());
        assertNull(decoded.expiring()); assertNull(decoded.recovery().description());
        assertNull(decoded.previousRecovery()); assertNull(decoded.ship().fleetMemberId());
    }

    @Test void idsAreSortedBoundedAndUnique() {
        assertEquals(List.of("wreck:a", "wreck:z"), CoopWreckRecord.decodeIds(
                CoopWreckRecord.encodeIds(List.of("wreck:z", "wreck:a"))));
        assertEquals(List.of(), CoopWreckRecord.decodeIds(CoopWreckRecord.encodeIds(List.of())));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.encodeIds(List.of("wreck:a", "wreck:a")));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decodeIds("2|wreck:a|wreck:a"));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.encodeIds(Collections.nCopies(1025, "wreck:a")));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.encodeIds(List.of("native-id")));
    }

    @Test void malformedRecordCannotBecomePartialRecipe() {
        String encoded = CoopWreckFixtures.sample("wreck:a").encode();
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decode(encoded + "|extra"));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decode(encoded.substring(0, encoded.lastIndexOf('|'))));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decode("2" + encoded.substring(1)));
        List<String> fields = new ArrayList<>(CoopDelimited.split(encoded));
        fields.set(5, "NaN");
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decode(join(fields)));
        fields.set(5, "1000001");
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decode(join(fields)));
        fields.set(5, "1"); fields.set(15, "yes");
        assertThrows(IllegalArgumentException.class, () -> CoopWreckRecord.decode(join(fields)));
    }

    @Test void recipeListsAreImmutableAndDefensive() {
        List<CoopWreckRecord.Ship> ships = new ArrayList<>();
        ships.add(CoopWreckFixtures.sample("wreck:a").ship());
        CoopWreckRecord.Recovery data = new CoopWreckRecord.Recovery(null, null, null, null, ships);
        ships.clear(); assertEquals(1, data.ships().size());
        assertThrows(UnsupportedOperationException.class, () -> data.ships().clear());
    }

    @Test void validationRejectsUnsupportedGameplayValues() {
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckRecord.Ship("UNKNOWN", "hound", null,
                null, null, null, true, true, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckRecord.Ship("GOOD", null, null,
                null, null, null, true, true, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckRecord.Cargo("NULL", "x", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckRecord.Cargo("SPECIAL", "x", null, -1));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckRecord.MemoryValue("$x", "BOOLEAN", "yes", -1));
        assertThrows(IllegalArgumentException.class, () -> new CoopWreckRecord.MemoryValue("$x", "DOUBLE", "Infinity", -1));
    }

    private static String join(List<String> fields) {
        return fields.stream().map(CoopDelimited::field).reduce((a, b) -> a + "|" + b).orElse("");
    }
}
