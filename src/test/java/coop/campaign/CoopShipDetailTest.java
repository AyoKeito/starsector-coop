package coop.campaign;

import coop.campaign.CoopShipDetail.WeaponGroup;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // and the guest's parseFloat would throw. The default locale has to actually be a
        // decimal-comma one for the assertion to mean anything -- on an en-US box the test passed
        // whether or not the formatting named Locale.ROOT, which is the regression it exists to catch.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String encoded = battered().encode();
            assertTrue(encoded.contains("0.4200"), encoded);
            assertEquals(0.42f, CoopShipDetail.decode(encoded).baseCR(), 1e-6f);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void aTruncatedBlobIsRejectedRatherThanSilentlyShifted() {
        assertThrows(IllegalArgumentException.class, () -> CoopShipDetail.decode("a|b|c"));
    }

    @Test
    void aBlankMemberIdNormalizesToEmptyBecauseModulesHaveNone() {
        // A module is a variant, not a fleet member, so it has no member id and the record cannot
        // reject the empty string outright. The loud check moved to the two places where a top-level
        // listing without an id is a real defect: capture drops the member, and the rebuild logs a
        // WARN and keeps the locally generated id.
        CoopShipDetail anonymous = new CoopShipDetail("  ", "n", "v", "h",
                1f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());

        assertEquals("", anonymous.memberId());
        assertEquals("", CoopShipDetail.decode(anonymous.encode()).memberId());
    }

    @Test
    void aBlankBaseVariantIdIsStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CoopShipDetail("m1", "n", " ", "h",
                1f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of()));
    }

    // ---- Phase 32: storage fidelity ------------------------------------------------------------

    /** A parent whose module carries a module of its own: two levels of the record inside itself. */
    private static CoopShipDetail modularStation() {
        CoopShipDetail innerModule = new CoopShipDetail("", "", "station_side_mod", "station_side",
                0f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("WS0009", "lightac"), Map.of(),
                List.of(new WeaponGroup(List.of("WS0009"), false, true)),
                1f, "Escort", Map.of());
        CoopShipDetail outerModule = new CoopShipDetail("", "", "station_mid_mod", "station_mid",
                0f, 3, 4, List.of("dmod_armor"), List.of(), List.of(), List.of(), List.of(),
                Map.of("WS0005", "hveldriver"), Map.of(),
                List.of(new WeaponGroup(List.of("WS0005"), true, false)),
                1f, "Battery", Map.of("INNER", innerModule));
        Map<String, String> weapons = new LinkedHashMap<>();
        weapons.put("WS0001", "heavymauler");
        weapons.put("WS0002", "annihilator");
        return new CoopShipDetail("member-91", "ISS Fortress", "station_base", "station",
                0.63f, 20, 10,
                List.of("dmod_engine"), List.of(), List.of("ground_support"),
                List.of("solar_shielding"), List.of("safetyoverrides"),
                weapons, Map.of("0", "talon_wing"),
                List.of(new WeaponGroup(List.of("WS0001", "WS0002"), false, true),
                        new WeaponGroup(List.of("WS0002"), true, false)),
                0.31f, "Warlord",
                Map.of("MODULE1", outerModule));
    }

    @Test
    void roundTripsGroupsHullFractionDisplayNameAndTwoLevelsOfModules() {
        CoopShipDetail back = CoopShipDetail.decode(modularStation().encode());

        assertEquals(modularStation(), back);
        assertEquals(0.31f, back.hullFraction(), 1e-6f);
        assertEquals("Warlord", back.displayName());
        assertEquals(List.of("WS0001", "WS0002"), back.weaponGroups().get(0).slots());
        assertFalse(back.weaponGroups().get(0).alternating());
        assertTrue(back.weaponGroups().get(0).autofire());
        assertTrue(back.weaponGroups().get(1).alternating());
        assertFalse(back.weaponGroups().get(1).autofire());

        CoopShipDetail outer = back.modules().get("MODULE1");
        assertEquals("Battery", outer.displayName());
        assertEquals(List.of("dmod_armor"), outer.permaMods());
        CoopShipDetail inner = outer.modules().get("INNER");
        assertEquals("Escort", inner.displayName());
        assertEquals("lightac", inner.weapons().get("WS0009"));
        assertEquals("", inner.memberId(), "a module is not a fleet member and carries no id");
    }

    @Test
    void modularBlobSurvivesTheEnclosingStockLineToo() {
        // The real transport. Every module level adds another backslash-doubling pass, and the stock
        // line adds one more on top; if any pass were not exactly inverted this is where it shows.
        CoopShipDetail detail = modularStation();
        List<CoopMarketSync.StockItem> back = CoopMarketSync.decodeStock(CoopMarketSync.encodeStock(
                List.of(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP,
                        detail.memberId(), 1, 0f, detail.encode()))));

        assertEquals(detail, CoopShipDetail.decode(back.get(0).detail()));
    }

    @Test
    void nastyCharactersSurviveInsideANestedModuleToo() {
        CoopShipDetail module = new CoopShipDetail("", "", "mod,var|1", "hull\\spec",
                0f, 0, 0, List.of("a,b", "c=d", "e\\f", "g|h"), List.of(), List.of(), List.of(),
                List.of(), Map.of("slot|with,pipe=and", "weap\\on,id"), Map.of(),
                List.of(new WeaponGroup(List.of("slot|with,pipe=and"), true, true)),
                0.5f, "Na,me=With\\Everything|", Map.of());
        CoopShipDetail parent = new CoopShipDetail("m|1", "ISS,Nasty", "base|var", "hull",
                1f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                List.of(), 1f, "", Map.of("SLOT,1=x", module));

        assertEquals(parent, CoopShipDetail.decode(parent.encode()));
    }

    @Test
    void aBlobWithTheNewFieldsEmptyDecodesToEmptiesRatherThanNullsOrAThrow() {
        // The append-only shape: the first fourteen fields as they always were, then four blanks.
        String[] parts = battered().encode().split("\\|", -1);
        String sparse = String.join("|", Arrays.copyOfRange(parts, 0, 14)) + "||||";

        CoopShipDetail back = CoopShipDetail.decode(sparse);

        assertTrue(back.weaponGroups().isEmpty());
        assertTrue(back.modules().isEmpty());
        assertEquals("", back.displayName());
        assertEquals(1f, back.hullFraction(), 1e-6f, "a blank hull fraction reads as undamaged");
        assertEquals(battered(), back, "every pre-Phase-32 field must still land where it did");
    }

    @Test
    void aWeaponGroupWithNoSlotsRoundTrips() {
        // Encodes as the three-field line "|0|0", whose first field is empty -- the case that would
        // collapse if the group list were joined with the plain string-list codec's skip-empties rule.
        CoopShipDetail detail = new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                List.of(new WeaponGroup(List.of(), false, false),
                        new WeaponGroup(List.of("WS0001"), true, true)),
                1f, "", Map.of());

        CoopShipDetail back = CoopShipDetail.decode(detail.encode());

        assertEquals(2, back.weaponGroups().size());
        assertTrue(back.weaponGroups().get(0).slots().isEmpty());
        assertEquals(List.of("WS0001"), back.weaponGroups().get(1).slots());
        assertEquals(detail, back);
    }

    @Test
    void hullFractionIsClampedToTheEngineRange() {
        assertEquals(1f, hulled(4.2f).hullFraction(), 1e-6f);
        assertEquals(0f, hulled(-0.5f).hullFraction(), 1e-6f);
        assertEquals(0.25f, hulled(0.25f).hullFraction(), 1e-6f);
        assertEquals(1f, hulled(Float.NaN).hullFraction(), 1e-6f);
        // ...and the clamp survives the wire, so a hand-built blob cannot smuggle an out-of-range one.
        assertEquals(1f, CoopShipDetail.decode(hulled(1f).encode().replace("|1.0000|", "|9.0000|"))
                .hullFraction(), 1e-6f);
    }

    /** Base CR is deliberately not 1, so the "1.0000" the clamp test rewrites is the hull fraction. */
    private static CoopShipDetail hulled(float fraction) {
        return new CoopShipDetail("m1", "", "hound_Standard", "hound", 0.5f, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                List.of(), fraction, "", Map.of());
    }

    @Test
    void anEmptyBaseCrCellReadsAsZeroRatherThanThrowingTheWayAnEmptyHullFractionDoesNot() {
        // The two float fields have to agree on what an empty cell means. Hull fraction's neutral
        // value is "undamaged"; base CR's is 0, because a hand-built blob must not be able to hand
        // back a mothballed hull at full combat readiness.
        String[] parts = battered().encode().split("\\|", -1);
        parts[4] = "";
        parts[15] = "";

        CoopShipDetail back = CoopShipDetail.decode(String.join("|", parts));

        assertEquals(0f, back.baseCR(), 1e-6f);
        assertEquals(1f, back.hullFraction(), 1e-6f);
    }

    @Test
    void aMalformedWeaponGroupIsRejectedWithoutDumpingTheWholeLoadoutIntoTheMessage() {
        // The exception is logged as a WARN; the other decode failures do not print the blob and
        // neither should this one - a ship's full loadout is not a diagnostic.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CoopShipDetail.WeaponGroup.decode("WS0001,WS0002|1"));

        assertFalse(ex.getMessage().contains("WS0001"),
                "the encoded group must not be echoed back: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("got 2"));
    }

    @Test
    void moduleNestingDeeperThanTheLimitIsRejectedRatherThanRecursed() {
        CoopShipDetail deepest = new CoopShipDetail("", "", "v", "h", 0f, 0, 0, List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), 1f, "", Map.of());
        CoopShipDetail nested = deepest;
        for (int i = 0; i <= CoopShipDetail.MAX_MODULE_NESTING; i++) {
            nested = new CoopShipDetail("", "", "v" + i, "h", 0f, 0, 0, List.of(), List.of(),
                    List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), 1f, "",
                    Map.of("S", nested));
        }
        String tooDeep = nested.encode();

        assertThrows(IllegalArgumentException.class, () -> CoopShipDetail.decode(tooDeep));
    }
}
