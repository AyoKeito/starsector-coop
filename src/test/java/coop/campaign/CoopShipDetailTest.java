package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ship-listing blob is what makes a market hull <em>that</em> hull rather than a pristine copy of
 * its base variant, and it is nested two levels deep inside another delimited payload — so its
 * escaping has to survive characters that are structural at all three levels.
 */
class CoopShipDetailTest {

    private static CoopShipDetail battered() {
        Map<String, String> weapons = new LinkedHashMap<>();
        weapons.put("WS0001", "heavymauler");
        weapons.put("WS0002", "annihilator");
        Map<String, String> wings = new LinkedHashMap<>();
        wings.put("0", "talon_wing");
        return new CoopShipDetail("member-77", "ISS Regret", "enforcer_Assault", "enforcer_dhull",
                0.42f, 12, 8,
                List.of("dmod_engine", "dmod_armor", "heavyarmor"),
                List.of("heavyarmor"),
                List.of("ground_support"),
                List.of("solar_shielding"),
                List.of("safetyoverrides"),
                weapons, wings);
    }

    @Test
    void roundTripsEveryField() {
        CoopShipDetail back = CoopShipDetail.decode(battered().encode());

        assertEquals(battered(), back);
        assertEquals("enforcer_dhull", back.hullSpecId(),
                "the D-hull spec swap is not recoverable from the variant id and must ride the wire");
        assertEquals(0.42f, back.baseCR(), 0.0001f);
        assertEquals(List.of("dmod_engine", "dmod_armor", "heavyarmor"), back.permaMods());
        assertEquals("heavymauler", back.weapons().get("WS0001"));
        assertEquals("talon_wing", back.wings().get("0"));
    }

    @Test
    void survivesNestingInsideAStockLine() {
        // Level 1 is the stock line; the blob is one of its fields. This is the real transport, and a
        // blob whose own '|' separators leaked into the outer split would silently truncate the line.
        CoopShipDetail detail = battered();
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(
                List.of(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP,
                        detail.memberId(), 1, 0f, detail.encode()))));

        assertEquals(1, back.size());
        assertEquals(detail, CoopShipDetail.decode(back.get(0).detail()));
    }

    @Test
    void survivesDelimiterCharactersInEveryStructuralPosition() {
        Map<String, String> weapons = new LinkedHashMap<>();
        weapons.put("slot|with,pipe=and", "weap\\on,id");
        CoopShipDetail nasty = new CoopShipDetail("id\\|1", "ISS\n|Ampersand,\\= Two", "var,iant|1",
                "hull\\spec",
                1f, 0, 0,
                List.of("a,b", "c=d", "e\\f", "g|h", "i\nj"),
                List.of("a,b"),
                List.of(),
                List.of("\\\\"),
                List.of("|"),
                weapons, Map.of());

        assertEquals(nasty, CoopShipDetail.decode(nasty.encode()));
        // ...and through the enclosing stock line too, which escapes the whole thing a second time.
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(
                List.of(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP, "k", 1, 0f,
                        nasty.encode()))));
        assertEquals(nasty, CoopShipDetail.decode(back.get(0).detail()));
    }

    @Test
    void emptyListsAndMapsRoundTripAsEmptyNotAsOneBlankElement() {
        CoopShipDetail bare = new CoopShipDetail("m1", "", "hound_Standard", "hound", 0.7f, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        CoopShipDetail back = CoopShipDetail.decode(bare.encode());

        assertTrue(back.permaMods().isEmpty());
        assertTrue(back.weapons().isEmpty());
        assertTrue(back.wings().isEmpty());
        assertEquals(bare, back);
    }

    @Test
    void decimalCommaLocalesCannotCorruptCR() {
        // %.4f under Locale.ROOT, not the default locale: a de_DE host would otherwise emit "0,4200"
        // and the guest's parseFloat would throw.
        assertTrue(battered().encode().contains("0.4200"), battered().encode());
    }

    @Test
    void aTruncatedBlobIsRejectedRatherThanSilentlyShifted() {
        assertThrows(IllegalArgumentException.class, () -> CoopShipDetail.decode("a|b|c"));
    }

    @Test
    void aBlankMemberIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CoopShipDetail("  ", "n", "v", "h",
                1f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of()));
    }
}
