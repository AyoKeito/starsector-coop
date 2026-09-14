package coop.campaign;

import coop.campaign.CoopCampaignReplicator.ShipFieldDiff;
import coop.campaign.CoopShipDetail.WeaponGroup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoopCampaignReplicator#diffStoredHull} is the field-by-field walk behind the 2026-09-14
 * "Coop storage hull replaced ... differs: ..." log line — {@code CoopCampaignReplicator}'s storage
 * reconcile used to log "kept=0 replaced=1" with no clue which field of the listing and the stored
 * hull disagreed. A pure function so it is testable without an engine: no {@code Global}, no proxies.
 *
 * <p>One test per field {@link CoopShipDetail#sameShip} compares (see that method's javadoc), plus
 * the identical case and the two module cases (a slot added/removed, and a slot whose content
 * changed under the same id).
 */
class CoopCampaignReplicatorShipDiffTest {

    private static CoopShipDetail base() {
        Map<String, String> weapons = new LinkedHashMap<>();
        weapons.put("WS0001", "heavymauler");
        weapons.put("WS0002", "annihilator");
        Map<String, String> wings = new LinkedHashMap<>();
        wings.put("0", "talon_wing");
        return new CoopShipDetail("c_guest-player_8f9a", "ISS Grudge", "hound_Standard", "hound_dhull",
                0.75f, 3, 2,
                List.of("dmod_engine", "heavyarmor"),
                List.of("heavyarmor"),
                List.of("ground_support"),
                List.of("solar_shielding"),
                List.of("safetyoverrides"),
                weapons, wings,
                List.of(new WeaponGroup(List.of("WS0001"), false, true),
                        new WeaponGroup(List.of("WS0002"), false, false)),
                0.62f, "Nickname", Map.of());
    }

    @Test
    void identicalShipsHaveNoDiff() {
        assertEquals(List.of(), CoopCampaignReplicator.diffStoredHull(base(), base()));
    }

    @Test
    void aReorderedButOtherwiseIdenticalListingHasNoDiff() {
        // The order-free fields (hull-mod sets, weapons, wings) must not read as a difference just
        // because the two engines handed the same contents over in a different order.
        Map<String, String> reorderedWeapons = new LinkedHashMap<>();
        reorderedWeapons.put("WS0002", "annihilator");
        reorderedWeapons.put("WS0001", "heavymauler");
        CoopShipDetail base = base();
        CoopShipDetail reordered = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                List.of("heavyarmor", "dmod_engine"), base.sMods(), base.sModdedBuiltIns(),
                base.refitMods(), base.suppressedMods(), reorderedWeapons, base.wings(),
                base.weaponGroups(), base.hullFraction(), base.displayName(), base.modules());

        assertEquals(List.of(), CoopCampaignReplicator.diffStoredHull(base, reordered));
    }

    @Test
    void shipNameDiffers() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), "ISS Regret",
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "shipName", "ISS Grudge", "ISS Regret");
    }

    @Test
    void baseVariantIdDiffers() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                "hound_Assault", base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "baseVariantId", "hound_Standard", "hound_Assault");
    }

    @Test
    void hullSpecIdDiffers() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), "hound", base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "hullSpecId", "hound_dhull", "hound");
    }

    @Test
    void baseCrDiffers() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), 0.9f, base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "baseCR", "0.7500", "0.9000");
    }

    @Test
    void ventsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), 5, base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "vents", "3", "5");
    }

    @Test
    void capsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), 6,
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "caps", "2", "6");
    }

    @Test
    void permaModsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                List.of("dmod_engine"), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "permaMods");
    }

    @Test
    void sModsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), List.of(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "sMods");
    }

    @Test
    void sModdedBuiltInsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), List.of("wolfpack_tactics"), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "sModdedBuiltIns");
    }

    @Test
    void refitModsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), List.of(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "refitMods");
    }

    @Test
    void suppressedModsDiffer() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                List.of(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "suppressedMods");
    }

    @Test
    void weaponsDiffer() {
        CoopShipDetail base = base();
        Map<String, String> refitted = new LinkedHashMap<>();
        refitted.put("WS0001", "heavymauler");
        refitted.put("WS0002", "harpoonpod");
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), refitted, base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "weapons");
    }

    @Test
    void wingsDiffer() {
        CoopShipDetail base = base();
        Map<String, String> rewinged = new LinkedHashMap<>();
        rewinged.put("0", "broadsword_wing");
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), rewinged, base.weaponGroups(),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, changed, "wings");
    }

    @Test
    void weaponGroupOrderDiffers() {
        // Order is meaningful here (an ALTERNATING group fires its slots in listed order), unlike the
        // hull-mod sets and the weapon/wing maps above.
        CoopShipDetail base = base();
        CoopShipDetail reordered = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(),
                List.of(new WeaponGroup(List.of("WS0002"), false, false),
                        new WeaponGroup(List.of("WS0001"), false, true)),
                base.hullFraction(), base.displayName(), base.modules());

        assertFieldDiffers(base, reordered, "weaponGroups");
    }

    @Test
    void hullFractionDiffers() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                1f, base.displayName(), base.modules());

        assertOnlyFieldDiffers(base, changed, "hullFraction", "0.6200", "1.0000");
    }

    @Test
    void displayNameDiffers() {
        CoopShipDetail base = base();
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), "Rustbucket", base.modules());

        assertOnlyFieldDiffers(base, changed, "displayName", "Nickname", "Rustbucket");
    }

    @Test
    void aModuleSlotAddedDiffersOnModulesOnly() {
        CoopShipDetail base = base();
        CoopShipDetail module = new CoopShipDetail("", "", "hound_Standard", "hound_dhull", 1f, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        CoopShipDetail changed = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), Map.of("weapon_deck", module));

        assertFieldDiffers(base, changed, "modules");
    }

    @Test
    void sameModuleSlotWithDifferentContentDiffersOnModulesOnly() {
        CoopShipDetail base = base();
        CoopShipDetail moduleA = new CoopShipDetail("", "", "hound_Standard", "hound_dhull", 1f, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        CoopShipDetail moduleB = new CoopShipDetail("", "", "hound_Assault", "hound_dhull", 1f, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        CoopShipDetail withA = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), Map.of("weapon_deck", moduleA));
        CoopShipDetail withB = new CoopShipDetail(base.memberId(), base.shipName(),
                base.baseVariantId(), base.hullSpecId(), base.baseCR(), base.vents(), base.caps(),
                base.permaMods(), base.sMods(), base.sModdedBuiltIns(), base.refitMods(),
                base.suppressedMods(), base.weapons(), base.wings(), base.weaponGroups(),
                base.hullFraction(), base.displayName(), Map.of("weapon_deck", moduleB));

        List<ShipFieldDiff> diffs = CoopCampaignReplicator.diffStoredHull(withA, withB);

        assertEquals(1, diffs.size(), diffs.toString());
        assertEquals("modules", diffs.get(0).field());
        assertTrue(diffs.get(0).localValue().contains("same slot ids"), diffs.toString());
    }

    // ---- Harness ------------------------------------------------------------------------------

    private static void assertOnlyFieldDiffers(CoopShipDetail local, CoopShipDetail incoming,
                                                String field, String localValue, String incomingValue) {
        List<ShipFieldDiff> diffs = CoopCampaignReplicator.diffStoredHull(local, incoming);
        assertEquals(List.of(new ShipFieldDiff(field, localValue, incomingValue)), diffs);
    }

    private static void assertFieldDiffers(CoopShipDetail local, CoopShipDetail incoming, String field) {
        List<ShipFieldDiff> diffs = CoopCampaignReplicator.diffStoredHull(local, incoming);
        assertEquals(1, diffs.size(), diffs.toString());
        assertEquals(field, diffs.get(0).field());
    }
}
