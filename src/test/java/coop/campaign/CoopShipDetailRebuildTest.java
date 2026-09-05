package coop.campaign;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import coop.campaign.CoopShipDetail.WeaponGroup;
import coop.testing.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of Phase 32 ship fidelity against a fake variant: what
 * {@code CoopCampaignReplicator.captureShipDetail} reads off a stored member, and what
 * {@code applyShipDetail} writes back onto a rebuilt one.
 *
 * <p>{@link CoopShipDetailTest} proves the codec; this proves the engine calls on either side of it,
 * which is where a shared storage locker actually loses a refit. The variant is a recording fake
 * rather than a game object because a real {@code ShipVariantAPI} needs a loaded install; the fake
 * answers exactly the accessors the replicator uses and records every setter, so an accessor the
 * production code stops calling shows up as a missing recording rather than as a silent pass.
 *
 * <p>The fake models three engine behaviours the rebuild leans on, because tests that did not model
 * them could not see the bugs: {@code setWingId(i, null)} does not clear a built-in bay,
 * {@code clearHullMods()} does not clear perma-mods, and {@code getNonBuiltInHullmods()} includes
 * perma-mods (which is what makes the capture's {@code refitMods = nonBuiltIn - perma} partition
 * mean anything). {@link FakeSettings} answers {@code doesVariantExist} / {@code getVariant} /
 * {@code getHullSpec} from maps and <em>throws</em> for an unknown hull id the way vanilla's does, so
 * the stock-variant, empty-hull and unresolvable-hull branches are all reachable.
 */
class CoopShipDetailRebuildTest {

    private FakeSettings settings;

    @BeforeEach
    void installSettings() {
        settings = new FakeSettings();
        Global.setSettings(settings.proxy());
        Global.setFactory(settings.factory());
    }

    @AfterEach
    void clearSettings() {
        Global.setSettings(null);
        Global.setFactory(null);
    }

    // ---- Capture -------------------------------------------------------------------------------

    @Test
    void captureCarriesWeaponGroupsHullFractionDisplayNameAndEveryModule() {
        FakeVariant top = new FakeVariant("conquest_Elite", "conquest", "Warlord");
        top.weapons.put("WS0001", "heavymauler");
        top.weapons.put("WS0002", "annihilator");
        top.groups.add(group(WeaponGroupType.LINKED, true, "WS0001", "WS0002"));
        top.groups.add(group(WeaponGroupType.ALTERNATING, false, "WS0002"));
        FakeVariant left = new FakeVariant("station_side_mod", "station_side", "Left Battery");
        left.weapons.put("WS0009", "lightac");
        left.groups.add(group(WeaponGroupType.LINKED, true, "WS0009"));
        FakeVariant right = new FakeVariant("station_side_mod", "station_side", "Right Battery");
        right.vents = 5;
        top.modules.put("MODULE1", left);
        top.modules.put("MODULE2", right);

        CoopShipDetail detail = CoopCampaignReplicator.captureShipDetail(
                member("member-91", "ISS Fortress", top, 0.63f, 0.31f));

        assertNotNull(detail);
        assertEquals("member-91", detail.memberId());
        assertEquals("ISS Fortress", detail.shipName());
        assertEquals(0.63f, detail.baseCR(), 1e-6f);
        assertEquals(0.31f, detail.hullFraction(), 1e-6f, "hull damage is what a locker must return");
        assertEquals("Warlord", detail.displayName());

        assertEquals(List.of(new WeaponGroup(List.of("WS0001", "WS0002"), false, true),
                        new WeaponGroup(List.of("WS0002"), true, false)),
                detail.weaponGroups());

        assertEquals(List.of("MODULE1", "MODULE2"), new ArrayList<>(detail.modules().keySet()));
        CoopShipDetail leftDetail = detail.modules().get("MODULE1");
        assertEquals("", leftDetail.memberId(), "a module is a variant, not a fleet member");
        assertEquals("station_side_mod", leftDetail.baseVariantId());
        assertEquals("Left Battery", leftDetail.displayName());
        assertEquals("lightac", leftDetail.weapons().get("WS0009"));
        assertEquals(List.of(new WeaponGroup(List.of("WS0009"), false, true)),
                leftDetail.weaponGroups());
        assertEquals(5, detail.modules().get("MODULE2").vents());
    }

    /**
     * The fence that moved. {@code CoopShipDetail}'s compact constructor stopped rejecting a blank
     * member id when modules arrived (a module is a variant with no fleet member behind it), and its
     * javadoc moved the guarantee to the two callers that need a real id. This is the capture half of
     * that promise: a listing nobody can name cannot be delta-removed later, so it is dropped rather
     * than shipped, and it says so.
     */
    @Test
    void captureDropsAMemberWithNoIdRatherThanShippingANamelessListing() {
        LogCapture log = LogCapture.attach(CoopCampaignReplicator.class);
        try {
            assertNull(CoopCampaignReplicator.captureShipDetail(member("  ", "ISS Anonymous",
                    new FakeVariant("hound_Standard", "hound", ""), 1f, 1f)));

            assertEquals(List.of("Coop ship listing skipped: mothballed member has no id"),
                    log.warnings(), "dropping a ship silently is what this fence is against");
        } finally {
            log.detach();
        }
    }

    /**
     * The rebuild half of the same promise. A detail that arrives with no member id must not stamp an
     * empty one onto the local member: that would make the very next capture drop the listing as
     * "no id", and every later per-member delta would address a ship that cannot be found.
     */
    @Test
    void rebuildKeepsTheLocallyGeneratedIdWhenTheWireCarriesNone() {
        FakeVariant pristine = new FakeVariant("conquest_Elite", "conquest", "");
        FakeMember member = new FakeMember("local-generated-id", "Unnamed", pristine);
        CoopShipDetail nameless = new CoopShipDetail("", "ISS Fortress", "conquest_Elite",
                "conquest", 0.5f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of(), List.of(), 1f, "", Map.of());

        LogCapture log = LogCapture.attach(CoopCampaignReplicator.class);
        try {
            CoopCampaignReplicator.applyShipDetail(member.proxy(), nameless);

            assertEquals("local-generated-id", member.id,
                    "an empty id would make the next capture drop this listing entirely");
            assertEquals(1, log.warnings().size(), "and the rename that did not happen is named");
            assertTrue(log.warnings().get(0).contains("arrived with no member id"),
                    log.warnings().toString());
        } finally {
            log.detach();
        }
        assertEquals("ISS Fortress", member.shipName, "the rest of the detail still applies");
    }

    @Test
    void captureCarriesSModsSModdedBuiltInsAndTheRefitPartition() {
        FakeVariant variant = new FakeVariant("enforcer_Assault", "enforcer_dhull", "");
        variant.permaMods.addAll(List.of("dmod_engine", "heavyarmor"));
        variant.sMods.add("heavyarmor");
        variant.sModdedBuiltIns.add("ground_support");
        variant.addedMods.add("solar_shielding");
        variant.suppressedMods.add("safetyoverrides");

        CoopShipDetail detail = CoopCampaignReplicator.captureShipDetail(
                member("member-1", "ISS Regret", variant, 0.4f, 0.9f));

        assertNotNull(detail);
        assertEquals(List.of("dmod_engine", "heavyarmor"), detail.permaMods());
        assertEquals(List.of("heavyarmor"), detail.sMods());
        assertEquals(List.of("ground_support"), detail.sModdedBuiltIns());
        assertEquals(List.of("solar_shielding"), detail.refitMods(),
                "refitMods is getNonBuiltInHullmods() minus the perma-mods, not the whole set");
        assertEquals(List.of("safetyoverrides"), detail.suppressedMods());
        assertEquals("enforcer_dhull", detail.hullSpecId(), "the D-hull swap has to ride the wire");
    }

    @Test
    void captureSkipsABuiltInBayButKeepsAnOwnerFittedOneAtTheSameIndex() {
        // The prefix rule: bay 0 is the hull spec's own talon wing and comes back on its own, bay 1
        // is the owner's, and bay 2 holds a wing the owner put where a built-in one is not.
        FakeVariant variant = new FakeVariant("drover_Strike", "drover", "");
        variant.builtInWings.add("talon_wing");
        variant.wings.addAll(Arrays.asList("talon_wing", "dagger_wing", "broadsword_wing"));

        CoopShipDetail detail = CoopCampaignReplicator.captureShipDetail(
                member("member-2", "ISS Carrier", variant, 1f, 1f));

        assertNotNull(detail);
        assertEquals(Map.of("1", "dagger_wing", "2", "broadsword_wing"), detail.wings(),
                "a built-in bay is the hull spec's to refill; every other index is the owner's");
    }

    @Test
    void captureReadsEachModulesOwnHullDamageOffTheIndexedStatuses() {
        FakeVariant top = new FakeVariant("station_Base", "station", "");
        top.modules.put("MODULE1", new FakeVariant("station_side_mod", "station_side", ""));
        top.modules.put("MODULE2", new FakeVariant("station_side_mod", "station_side", ""));
        FakeMember fake = new FakeMember("member-7", "Fortress", top);
        fake.hullFraction = 0.5f;
        // Status 0 is the ship's own hull; 1 and 2 are the module slots in getModuleSlots() order.
        fake.indexedHull.put(1, 0.2f);
        fake.indexedHull.put(2, 0.75f);

        CoopShipDetail detail = CoopCampaignReplicator.captureShipDetail(fake.proxy());

        assertNotNull(detail);
        assertEquals(0.5f, detail.hullFraction(), 1e-6f);
        assertEquals(0.2f, detail.modules().get("MODULE1").hullFraction(), 1e-6f);
        assertEquals(0.75f, detail.modules().get("MODULE2").hullFraction(), 1e-6f);
    }

    @Test
    void captureFallsBackToFullHullForAModuleTheStatusListDoesNotReach() {
        FakeVariant top = new FakeVariant("station_Base", "station", "");
        top.modules.put("MODULE1", new FakeVariant("station_side_mod", "station_side", ""));
        top.modules.put("MODULE2", new FakeVariant("station_side_mod", "station_side", ""));
        FakeMember fake = new FakeMember("member-8", "Fortress", top);
        fake.numStatuses = 2; // the engine only counted the hull and the first module
        fake.indexedHull.put(1, 0.2f);

        CoopShipDetail detail = CoopCampaignReplicator.captureShipDetail(fake.proxy());

        assertNotNull(detail);
        assertEquals(0.2f, detail.modules().get("MODULE1").hullFraction(), 1e-6f);
        assertEquals(1f, detail.modules().get("MODULE2").hullFraction(), 1e-6f,
                "a module with no status of its own is undamaged, not zero-hulled");
    }

    @Test
    void captureEncodeDecodeAndRebuildAgreeOnAModularHull() {
        // The seam CoopShipDetailTest and the rebuild tests each prove one side of: a field that
        // capture fills and decode normalises away would otherwise pass both suites.
        FakeVariant top = new FakeVariant("conquest_Elite", "conquest", "Warlord");
        top.weapons.put("WS0001", "heavymauler");
        top.groups.add(group(WeaponGroupType.ALTERNATING, true, "WS0001"));
        top.groups.add(group(WeaponGroupType.LINKED, false, "WS0001"));
        top.permaMods.add("dmod_engine");
        top.sMods.add("dmod_engine");
        top.addedMods.add("solar_shielding");
        top.suppressedMods.add("safetyoverrides");
        top.builtInWings.add("talon_wing");
        top.wings.addAll(Arrays.asList("talon_wing", "dagger_wing"));
        FakeVariant module = new FakeVariant("station_side_mod", "station_side", "Battery");
        module.weapons.put("WS0009", "lightac");
        top.modules.put("MODULE1", module);
        FakeMember stored = new FakeMember("member-4", "ISS Round Trip", top);
        stored.cr = 0.8f;
        stored.hullFraction = 0.4f;
        stored.indexedHull.put(1, 0.25f);

        CoopShipDetail captured = CoopCampaignReplicator.captureShipDetail(stored.proxy());
        CoopShipDetail overTheWire = CoopShipDetail.decode(captured.encode());
        assertEquals(captured, overTheWire, "the blob has to survive its own codec byte for byte");

        FakeVariant pristine = new FakeVariant("conquest_Elite", "conquest", "");
        pristine.builtInWings.add("talon_wing");
        pristine.wings.add("talon_wing");
        pristine.modules.put("MODULE1", new FakeVariant("station_side_mod", "station_side", ""));
        FakeMember rebuilt = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(rebuilt.proxy(), overTheWire);

        FakeVariant applied = pristine.lastClone;
        assertEquals("Warlord", applied.displayName);
        assertEquals("heavymauler", applied.weapons.get("WS0001"));
        assertEquals(2, applied.groups.size());
        assertEquals(WeaponGroupType.ALTERNATING, applied.groups.get(0).getType());
        assertEquals(List.of("dmod_engine"), applied.permaMods);
        assertEquals(List.of("dmod_engine"), applied.sMods, "the s-mod flag has to ride the wire");
        assertEquals(List.of("solar_shielding"), applied.addedMods);
        assertEquals(List.of("safetyoverrides"), applied.suppressedMods);
        assertEquals(Arrays.asList("talon_wing", "dagger_wing"), applied.wings,
                "the built-in bay refills itself and the owner's bay is restored beside it");
        assertEquals("lightac", pristine.modules.get("MODULE1").lastClone.weapons.get("WS0009"));
        assertEquals(0.4f, rebuilt.hullFraction, 1e-6f);
        assertEquals(0.25f, rebuilt.indexedHull.get(1), 1e-6f,
                "the module's own hull damage has to survive the round trip too");
    }

    // ---- Rebuild -------------------------------------------------------------------------------

    private static CoopShipDetail storedStation() {
        CoopShipDetail module = new CoopShipDetail("", "", "station_side_mod", "station_side",
                0f, 3, 4, List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("WS0009", "lightac"), Map.of(),
                List.of(new WeaponGroup(List.of("WS0009"), false, true)),
                1f, "Battery", Map.of());
        Map<String, String> weapons = new LinkedHashMap<>();
        weapons.put("WS0001", "heavymauler");
        weapons.put("WS0002", "annihilator");
        return new CoopShipDetail("member-91", "ISS Fortress", "conquest_Elite", "conquest",
                0.63f, 20, 10, List.of(), List.of(), List.of(), List.of(), List.of(),
                weapons, Map.of(),
                List.of(new WeaponGroup(List.of("WS0001", "WS0002"), false, true),
                        new WeaponGroup(List.of("WS0002"), true, false)),
                0.31f, "Warlord",
                Map.of("MODULE1", module));
    }

    @Test
    void rebuildWritesGroupsHullFractionDisplayNameAndModulesOntoTheClonedVariant() {
        FakeVariant pristine = new FakeVariant("conquest_Elite", "conquest", "");
        FakeVariant modulePristine = new FakeVariant("station_side_mod", "station_side", "");
        pristine.modules.put("MODULE1", modulePristine);
        // A distinctive group on the shared stock variant: if the rebuild ever mutated it in place
        // instead of its clone, every ship in the sector using this variant would lose it.
        pristine.groups.add(group(WeaponGroupType.LINKED, false, "STOCK_SLOT"));
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(), storedStation());

        FakeVariant applied = pristine.lastClone;
        assertNotNull(applied, "the variant must be cloned before it is touched, never mutated in place");
        assertEquals(VariantSource.REFIT, applied.source);
        assertEquals(1, pristine.groups.size(), "the shared stock variant must be left alone");
        assertEquals(List.of("STOCK_SLOT"), pristine.groups.get(0).getSlots(),
                "the shared stock variant's own group must still be its own");

        assertEquals("Warlord", applied.displayName);
        assertEquals(2, applied.groups.size());
        assertEquals(List.of("WS0001", "WS0002"), applied.groups.get(0).getSlots());
        assertEquals(WeaponGroupType.LINKED, applied.groups.get(0).getType());
        assertTrue(applied.groups.get(0).isAutofireOnByDefault());
        assertEquals(List.of("WS0002"), applied.groups.get(1).getSlots());
        assertEquals(WeaponGroupType.ALTERNATING, applied.groups.get(1).getType());
        assertFalse(applied.groups.get(1).isAutofireOnByDefault());
        assertEquals(0, applied.autoGenerated, "the owner's groups replace the autogenerated ones");

        FakeVariant appliedModule = modulePristine.lastClone;
        assertNotNull(appliedModule, "the module variant must be cloned too");
        assertEquals(VariantSource.REFIT, appliedModule.source);
        assertEquals("Battery", appliedModule.displayName);
        assertEquals("lightac", appliedModule.weapons.get("WS0009"));
        assertEquals(3, appliedModule.vents);
        assertSame(appliedModule.proxy(), applied.installedModules.get("MODULE1"),
                "the rebuilt module has to be hung back on the parent");

        assertEquals("member-91", member.id);
        assertEquals("ISS Fortress", member.shipName);
        assertEquals(0.63f, member.cr, 1e-6f);
        assertTrue(member.mothballed);
        assertEquals(0.31f, member.hullFraction, 1e-6f);
        assertSame(applied.proxy(), member.installedVariant);
    }

    @Test
    void rebuildStripsTheStockBaseVariantsOwnHullModsAndWings() {
        // The Drover case: createBaseMember hands back the stock variant whenever the captured
        // variant id resolves, and drover_Strike arrives with missleracks and two wings already on
        // it. An additive apply gave the owner back a hull mod they had stripped and a bay they had
        // emptied - over the OP budget, and not the ship they parked.
        FakeVariant stock = new FakeVariant("drover_Strike", "drover", "");
        stock.addedMods.add("missleracks");
        stock.permaMods.add("dmod_armor");
        stock.suppressedMods.add("safetyoverrides");
        stock.wings.addAll(Arrays.asList("broadsword_wing", "dagger_wing"));
        stock.weapons.put("WS0007", "lightac");
        FakeMember member = new FakeMember("local-id", "Unnamed", stock);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "drover_Strike", "drover", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of(), Map.of("0", "dagger_wing")));

        FakeVariant applied = stock.lastClone;
        assertEquals(List.of(), applied.addedMods, "a hull mod the owner stripped must not come back");
        assertEquals(List.of(), applied.permaMods, "nor a D-mod the blob does not carry");
        assertEquals(List.of(), applied.suppressedMods);
        assertEquals(Arrays.asList("dagger_wing", null), applied.wings,
                "bay 0 takes the blob's wing and bay 1 is emptied, not left holding the stock one");
        assertEquals(Map.of(), applied.weapons, "weapons were already exact and must stay so");
    }

    @Test
    void rebuildStripsAStockModuleVariantsOwnHullModsToo() {
        // Same asymmetry one level down: baseModuleVariant also prefers a stock variant by id.
        settings.variants.put("station_side_mod", stockModule());
        FakeVariant parent = new FakeVariant("station_Base", "station", "");
        parent.modules.put("MODULE1", new FakeVariant("other_mod", "station_side", ""));
        FakeMember member = new FakeMember("local-id", "Unnamed", parent);

        CoopShipDetail module = new CoopShipDetail("", "", "station_side_mod", "station_side",
                0f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of());
        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "station_Base", "station", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of(), Map.of(), List.of(), 1f, "", Map.of("MODULE1", module)));

        FakeVariant appliedModule = settings.variants.get("station_side_mod").lastClone;
        assertNotNull(appliedModule, "the stock module variant must be cloned, never mutated");
        assertEquals(List.of(), appliedModule.addedMods);
        assertEquals(List.of(), appliedModule.permaMods);
        assertEquals(List.of(), appliedModule.weapons.keySet().stream().toList());
    }

    private static FakeVariant stockModule() {
        FakeVariant stock = new FakeVariant("station_side_mod", "station_side", "");
        stock.addedMods.add("missleracks");
        stock.permaMods.add("dmod_armor");
        stock.weapons.put("WS0042", "lightac");
        return stock;
    }

    @Test
    void aModThatIsBothPermaAndSuppressedComesBackSuppressed() {
        // Vanilla keeps the two lists disjoint (DModManager.removeDMod does removePermaMod +
        // addSuppressedMod), but a third-party mod can produce the overlap, and the codec carries
        // both lists independently. Resolving it toward "active" handed back a live hull mod.
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                        List.of("heavyarmor"), List.of(), List.of(), List.of(),
                        List.of("heavyarmor"), Map.of(), Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertEquals(List.of("heavyarmor"), applied.permaMods);
        assertEquals(List.of("heavyarmor"), applied.suppressedMods,
                "a hull mod the owner had suppressed must not come back live");
    }

    @Test
    void sModdedBuiltInsAreRestoredWithTheSModFlagSet() {
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                        List.of("heavyarmor"), List.of("heavyarmor"), List.of("ground_support"),
                        List.of(), List.of(), Map.of(), Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertEquals(List.of("heavyarmor", "ground_support"), applied.sMods,
                "both an s-modded refit mod and an s-modded built-in have to set the flag");
        assertTrue(applied.permaMods.contains("ground_support"),
                "addPermaMod is the only public setter for the s-mod flag, so the built-in lands in"
                        + " permaMods too - the accepted drift-by-one documented on CoopShipDetail");
    }

    @Test
    void aListingWithNoGroupsFallsBackToVanillaAutogeneration() {
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of()));

        assertEquals(1, pristine.lastClone.autoGenerated);
        assertTrue(pristine.lastClone.groups.isEmpty());
    }

    @Test
    void aGroupThatLosesEverySlotIsKeptEmptySoTheLaterGroupsKeepTheirIndex() {
        // Groups are what the player's 1-5 keys are bound to. Dropping the dangling group renumbered
        // every group after it, which the owner cannot see until the shooting starts.
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of("WS0001", "heavymauler"), Map.of(),
                        List.of(new WeaponGroup(List.of("WS_MISSING"), false, false),
                                new WeaponGroup(List.of("WS0001", "WS_MISSING"), false, false)),
                        1f, "", Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertEquals(2, applied.groups.size(), "the dangling group keeps its index");
        assertEquals(List.of(), applied.groups.get(0).getSlots());
        assertEquals(List.of("WS0001"), applied.groups.get(1).getSlots(),
                "and the group the owner bound to key 2 is still key 2");
        assertEquals(0, applied.autoGenerated);
    }

    @Test
    void aListingWhoseGroupsAllLoseEverySlotStillFallsBackToAutogeneration() {
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                        List.of(new WeaponGroup(List.of("WS_MISSING"), false, false),
                                new WeaponGroup(List.of("WS_ALSO_MISSING"), false, false)),
                        1f, "", Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertEquals(1, applied.autoGenerated,
                "a variant of nothing but empty groups is worse than vanilla's autogenerated ones");
        assertTrue(applied.groups.isEmpty(), "and the empty placeholders are cleared out first");
    }

    @Test
    void aBuiltInWeaponSlotKeepsItsGroupEvenThoughTheCaptureNeverNamedIt() {
        // The Onslaught case: onslaught_Standard's group 0 is the two built-in TPCs, which
        // getNonBuiltInWeaponSlots never captures. Filtering group slots on getWeaponId alone
        // dropped the whole group off a hull rebuilt from an empty variant.
        FakeVariant pristine = new FakeVariant("onslaught_Standard", "onslaught", "");
        pristine.builtInWeapons.put("WS 016", "tpc");
        pristine.builtInWeapons.put("WS 017", "tpc");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "onslaught_Standard", "onslaught", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of("WS0001", "heavymauler"), Map.of(),
                        List.of(new WeaponGroup(List.of("WS 016", "WS 017"), false, true),
                                new WeaponGroup(List.of("WS0001"), false, true)),
                        1f, "", Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertEquals(2, applied.groups.size());
        assertEquals(List.of("WS 016", "WS 017"), applied.groups.get(0).getSlots(),
                "the built-in TPC group has to survive the rebuild");
        assertEquals(List.of("WS0001"), applied.groups.get(1).getSlots());
    }

    // ---- Unresolvable ids ----------------------------------------------------------------------

    @Test
    void aHullSpecThisClientCannotResolveLeavesTheVariantsOwnHullRatherThanThrowing() {
        // Global.getSettings().getHullSpec throws for an unknown id rather than returning null, so
        // without a catch the D-hull swap took the whole listing down instead of degrading.
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound_from_a_mod", 0.5f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of("WS0001", "heavymauler"), Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertNotNull(applied, "the listing must still rebuild");
        assertEquals("hound", applied.hullId, "the local hull spec is kept when the named one is gone");
        assertEquals("heavymauler", applied.weapons.get("WS0001"),
                "and the rest of the refit is applied rather than lost to the throw");
        assertEquals(0.5f, member.cr, 1e-6f);
    }

    @Test
    void aModuleThatNamesNoResolvableVariantOrHullIsLeftAsTheBaseVariantsModule() {
        FakeVariant parent = new FakeVariant("station_Base", "station", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", parent);

        CoopShipDetail module = new CoopShipDetail("", "", "mod_only_variant", "mod_only_hull",
                0f, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "station_Base", "station", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of(), Map.of(), List.of(), 1f, "", Map.of("MODULE1", module)));

        FakeVariant applied = parent.lastClone;
        assertNotNull(applied);
        assertTrue(applied.installedModules.isEmpty(),
                "nothing resolved, so the slot keeps whatever the base variant put there");
    }

    @Test
    void anEmptyHullVariantIsBuiltForAModuleWhoseVariantIdIsCustomButWhoseHullIsKnown() {
        settings.hulls.put("station_side", hullSpec("station_side", List.of(), Map.of()));
        FakeVariant parent = new FakeVariant("station_Base", "station", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", parent);

        CoopShipDetail module = new CoopShipDetail("", "", "runtime_variant_77", "station_side",
                0f, 7, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("WS0009", "lightac"), Map.of());
        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "station_Base", "station", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of(), Map.of(), List.of(), 1f, "", Map.of("MODULE1", module)));

        assertEquals(1, settings.created.size(), "the empty-hull fallback has to run");
        FakeVariant built = settings.created.get(0);
        assertEquals("runtime_variant_77", built.hullVariantId);
        assertEquals("lightac", built.weapons.get("WS0009"));
        assertEquals(7, built.vents);
        assertSame(built.proxy(), parent.lastClone.installedModules.get("MODULE1"));
    }

    // ---- The storage fallback ------------------------------------------------------------------

    @Test
    void theStorageFallbackKeepsTheHostsMemberIdCrAndHullDamage() {
        settings.variants.put("hound_Standard", new FakeVariant("hound_Standard", "hound", ""));
        FakeFleetData ships = new FakeFleetData();

        CoopCampaignReplicator.addBaseVariantToStorage(ships.proxy(),
                new CoopShipDetail("member-31", "ISS Battered", "hound_Standard", "hound", 0.4f,
                        0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                        Map.of(), List.of(), 0.27f, "", Map.of()));

        assertEquals(1, ships.added.size(), "a deposit is never dropped, even on the fallback path");
        FakeMember stored = ships.added.get(0);
        assertEquals("member-31", stored.id);
        assertEquals(0.4f, stored.cr, 1e-6f);
        assertTrue(stored.mothballed);
        assertEquals(0.27f, stored.hullFraction, 1e-6f,
                "a battered ship must not come back at full hull just because the refit was lost");
    }

    @Test
    void theStorageFallbackRefusesToStampAnEmptyMemberId() {
        // An empty id is legal in the record (modules have none), and stamping it here would make the
        // very next captureShipDetail drop the member as "no id" - which deletes the deposit.
        settings.variants.put("hound_Standard", new FakeVariant("hound_Standard", "hound", ""));
        FakeFleetData ships = new FakeFleetData();

        CoopCampaignReplicator.addBaseVariantToStorage(ships.proxy(),
                new CoopShipDetail("", "", "hound_Standard", "hound", 1f, 0, 0, List.of(), List.of(),
                        List.of(), List.of(), List.of(), Map.of(), Map.of()));

        assertEquals(1, ships.added.size());
        assertEquals("factory-id", ships.added.get(0).id,
                "the locally generated id is kept so the listing stays addressable");
    }

    @Test
    void theStorageFallbackWarnsRatherThanThrowsWhenNothingResolves() {
        FakeFleetData ships = new FakeFleetData();

        CoopCampaignReplicator.addBaseVariantToStorage(ships.proxy(),
                new CoopShipDetail("member-32", "", "mod_only_variant", "mod_only_hull", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of()));

        assertTrue(ships.added.isEmpty(), "nothing resolved, so there is nothing to add");
    }

    // ---- Fakes ---------------------------------------------------------------------------------

    private static WeaponGroupSpec group(WeaponGroupType type, boolean autofire, String... slots) {
        WeaponGroupSpec spec = new WeaponGroupSpec(type);
        spec.setAutofireOnByDefault(autofire);
        for (String slot : slots) {
            spec.addSlot(slot);
        }
        return spec;
    }

    private FleetMemberAPI member(String id, String name, FakeVariant variant,
                                  float baseCR, float hullFraction) {
        FakeMember fake = new FakeMember(id, name, variant);
        fake.cr = baseCR;
        fake.hullFraction = hullFraction;
        return fake.proxy();
    }

    /**
     * A {@link SettingsAPI} that answers the three lookups the rebuild makes from maps a test seeds,
     * plus {@code createEmptyVariant}, and a matching {@link FactoryAPI}.
     *
     * <p>{@code getHullSpec} throws for an unknown id rather than returning null, which is what
     * vanilla does and what makes the "this client cannot resolve that hull" branches worth having.
     */
    private static final class FakeSettings {
        final Map<String, FakeVariant> variants = new LinkedHashMap<>();
        final Map<String, ShipHullSpecAPI> hulls = new LinkedHashMap<>();
        final List<FakeVariant> created = new ArrayList<>();
        final Map<ShipVariantAPI, FakeVariant> byProxy = new IdentityHashMap<>();

        SettingsAPI proxy() {
            return (SettingsAPI) Proxy.newProxyInstance(
                    SettingsAPI.class.getClassLoader(),
                    new Class<?>[]{SettingsAPI.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "doesVariantExist" -> variants.containsKey((String) args[0]);
                        case "getVariant" -> {
                            FakeVariant variant = variants.get((String) args[0]);
                            yield variant == null ? null : register(variant);
                        }
                        case "getHullSpec" -> {
                            ShipHullSpecAPI hull = hulls.get((String) args[0]);
                            if (hull == null) {
                                throw new RuntimeException("No ship hull spec: " + args[0]);
                            }
                            yield hull;
                        }
                        case "createEmptyVariant" -> {
                            FakeVariant variant = new FakeVariant((String) args[0],
                                    ((ShipHullSpecAPI) args[1]).getHullId(), "");
                            created.add(variant);
                            yield register(variant);
                        }
                        case "getColor" -> Color.WHITE;
                        case "toString" -> "FakeSettings";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ShipVariantAPI register(FakeVariant variant) {
            byProxy.put(variant.proxy(), variant);
            return variant.proxy();
        }

        FactoryAPI factory() {
            return (FactoryAPI) Proxy.newProxyInstance(
                    FactoryAPI.class.getClassLoader(),
                    new Class<?>[]{FactoryAPI.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "createFleetMember" -> {
                            FakeVariant variant = args[1] instanceof String id
                                    ? variants.get(id) : byProxy.get((ShipVariantAPI) args[1]);
                            if (variant == null) {
                                throw new RuntimeException("No variant for " + args[1]);
                            }
                            yield new FakeMember("factory-id", "", variant).proxy();
                        }
                        case "toString" -> "FakeFactory";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /** A {@link FleetDataAPI} that records what the storage fallback hands it. */
    private static final class FakeFleetData {
        final List<FakeMember> added = new ArrayList<>();

        FleetDataAPI proxy() {
            return (FleetDataAPI) Proxy.newProxyInstance(
                    FleetDataAPI.class.getClassLoader(),
                    new Class<?>[]{FleetDataAPI.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "addFleetMember" -> {
                            added.add(FakeMember.of((FleetMemberAPI) args[0]));
                            yield null;
                        }
                        case "toString" -> "FakeFleetData";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /**
     * A {@link ShipVariantAPI} that answers the accessors the replicator reads and records every
     * setter it calls. {@code clone()} hands back a copy and remembers it as {@link #lastClone}, which
     * is how a test sees what the rebuild wrote without the production code having to expose it.
     */
    private static final class FakeVariant {
        final String hullVariantId;
        String hullId;
        String displayName;
        int vents;
        int caps;
        VariantSource source;
        final Map<String, String> weapons = new LinkedHashMap<>();
        final List<WeaponGroupSpec> groups = new ArrayList<>();
        final Map<String, FakeVariant> modules = new LinkedHashMap<>();
        final List<String> addedMods = new ArrayList<>();
        final List<String> permaMods = new ArrayList<>();
        final List<String> sMods = new ArrayList<>();
        final List<String> sModdedBuiltIns = new ArrayList<>();
        final List<String> suppressedMods = new ArrayList<>();
        final List<String> wings = new ArrayList<>();
        final List<String> builtInWings = new ArrayList<>();
        final Map<String, String> builtInWeapons = new LinkedHashMap<>();
        final Map<String, ShipVariantAPI> installedModules = new LinkedHashMap<>();
        final List<String> clearedSlots = new ArrayList<>();
        int autoGenerated;
        FakeVariant lastClone;
        private ShipVariantAPI proxy;

        FakeVariant(String hullVariantId, String hullId, String displayName) {
            this.hullVariantId = hullVariantId;
            this.hullId = hullId;
            this.displayName = displayName;
        }

        /**
         * Everything a real {@code clone()} carries over. The mod lists and the source are part of
         * that: leaving them out made the whole hull-mod half of the rebuild unobservable, which is
         * how an additive apply over a stock base variant went unnoticed.
         */
        FakeVariant copy() {
            FakeVariant copy = new FakeVariant(hullVariantId, hullId, displayName);
            copy.vents = vents;
            copy.caps = caps;
            copy.source = source;
            copy.weapons.putAll(weapons);
            copy.groups.addAll(groups);
            copy.modules.putAll(modules);
            copy.addedMods.addAll(addedMods);
            copy.permaMods.addAll(permaMods);
            copy.sMods.addAll(sMods);
            copy.sModdedBuiltIns.addAll(sModdedBuiltIns);
            copy.suppressedMods.addAll(suppressedMods);
            copy.wings.addAll(wings);
            copy.builtInWings.addAll(builtInWings);
            copy.builtInWeapons.putAll(builtInWeapons);
            return copy;
        }

        ShipVariantAPI proxy() {
            if (proxy == null) {
                proxy = (ShipVariantAPI) Proxy.newProxyInstance(
                        ShipVariantAPI.class.getClassLoader(),
                        new Class<?>[]{ShipVariantAPI.class},
                        (p, method, args) -> invoke(method.getName(), args, method.getReturnType()));
            }
            return proxy;
        }

        private Object invoke(String name, Object[] args, Class<?> returnType) {
            switch (name) {
                case "clone": {
                    lastClone = copy();
                    return lastClone.proxy();
                }
                case "getHullVariantId":
                    return hullVariantId;
                case "getDisplayName":
                    return displayName;
                case "setVariantDisplayName":
                    displayName = (String) args[0];
                    return null;
                case "getHullSpec":
                    return hullSpec(hullId, builtInWings, builtInWeapons);
                case "setHullSpecAPI":
                    hullId = ((ShipHullSpecAPI) args[0]).getHullId();
                    return null;
                case "setSource":
                    source = (VariantSource) args[0];
                    return null;
                case "getNumFluxVents":
                    return vents;
                case "getNumFluxCapacitors":
                    return caps;
                case "setNumFluxVents":
                    vents = (Integer) args[0];
                    return null;
                case "setNumFluxCapacitors":
                    caps = (Integer) args[0];
                    return null;
                case "getNonBuiltInWeaponSlots":
                    return new ArrayList<>(weapons.keySet());
                case "getWeaponId":
                    return weapons.get((String) args[0]);
                case "addWeapon":
                    weapons.put((String) args[0], (String) args[1]);
                    return null;
                case "clearSlot":
                    clearedSlots.add((String) args[0]);
                    weapons.remove(args[0]);
                    return null;
                case "getWeaponGroups":
                    return groups;
                case "addWeaponGroup":
                    groups.add((WeaponGroupSpec) args[0]);
                    return null;
                case "autoGenerateWeaponGroups":
                    autoGenerated++;
                    return null;
                case "getModuleSlots":
                    return new ArrayList<>(modules.keySet());
                case "getModuleVariant": {
                    FakeVariant module = modules.get((String) args[0]);
                    return module == null ? null : module.proxy();
                }
                case "setModuleVariant":
                    installedModules.put((String) args[0], (ShipVariantAPI) args[1]);
                    return null;
                case "addMod":
                    addedMods.add((String) args[0]);
                    return null;
                case "clearHullMods":
                    // Documented not to touch built-ins or perma-mods, which is why the rebuild has
                    // to call all three clears rather than just this one.
                    addedMods.clear();
                    return null;
                case "addPermaMod":
                    permaMods.add((String) args[0]);
                    if (args.length > 1 && Boolean.TRUE.equals(args[1])) {
                        sMods.add((String) args[0]);
                    }
                    return null;
                case "clearPermaMods":
                    permaMods.clear();
                    sMods.clear();
                    sModdedBuiltIns.clear();
                    return null;
                case "addSuppressedMod":
                    suppressedMods.add((String) args[0]);
                    return null;
                case "removeSuppressedMod":
                    suppressedMods.remove(args[0]);
                    return null;
                case "clearSuppressedMods":
                    suppressedMods.clear();
                    return null;
                case "getPermaMods":
                    return new LinkedHashSet<>(permaMods);
                case "getSMods":
                    return new LinkedHashSet<>(sMods);
                case "getSModdedBuiltIns":
                    return new LinkedHashSet<>(sModdedBuiltIns);
                case "getSuppressedMods":
                    return new LinkedHashSet<>(suppressedMods);
                case "getNonBuiltInHullmods": {
                    // The engine's answer includes perma-mods; the capture subtracts them back out to
                    // get the refit, so a fake that returned only the refit proved nothing.
                    List<String> all = new ArrayList<>(permaMods);
                    all.addAll(addedMods);
                    return all;
                }
                case "getWings":
                    return new ArrayList<>(wings);
                case "getWingId":
                    return wingAt((Integer) args[0]);
                case "setWingId": {
                    int index = (Integer) args[0];
                    String wingId = (String) args[1];
                    if (wingId == null && index < builtInWings.size()) {
                        return null; // "won't clear out built-in wings" - FleetEncounterContext:1841
                    }
                    while (wings.size() <= index) {
                        wings.add(null);
                    }
                    wings.set(index, wingId);
                    return null;
                }
                case "toString":
                    return "FakeVariant[" + hullVariantId + "]";
                case "hashCode":
                    return System.identityHashCode(this);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(returnType);
            }
        }

        private String wingAt(int index) {
            return index >= 0 && index < wings.size() ? wings.get(index) : null;
        }
    }

    private static ShipHullSpecAPI hullSpec(String hullId, List<String> builtInWings,
                                            Map<String, String> builtInWeapons) {
        return (ShipHullSpecAPI) Proxy.newProxyInstance(
                ShipHullSpecAPI.class.getClassLoader(),
                new Class<?>[]{ShipHullSpecAPI.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getHullId" -> hullId;
                    case "getBuiltInWings" -> new ArrayList<>(builtInWings);
                    case "getBuiltInWeapons" -> new java.util.HashMap<>(builtInWeapons);
                    case "toString" -> "FakeHull[" + hullId + "]";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A {@link FleetMemberAPI} whose variant, id, name, CR and hull fractions are all readable back. */
    private static final class FakeMember {
        private static final Map<FleetMemberAPI, FakeMember> BY_PROXY = new IdentityHashMap<>();

        String id;
        String shipName;
        final FakeVariant variant;
        ShipVariantAPI installedVariant;
        float cr = 1f;
        float hullFraction = 1f;
        /** Per-module hull, keyed by status index: 0 is the hull, i+1 is module slot i. */
        final Map<Integer, Float> indexedHull = new LinkedHashMap<>();
        /** -1 derives from the variant's module slots, the way the engine's own count does. */
        int numStatuses = -1;
        boolean mothballed;
        private FleetMemberAPI proxy;

        FakeMember(String id, String shipName, FakeVariant variant) {
            this.id = id;
            this.shipName = shipName;
            this.variant = variant;
        }

        static FakeMember of(FleetMemberAPI proxy) {
            return BY_PROXY.get(proxy);
        }

        FleetMemberAPI proxy() {
            if (proxy == null) {
                proxy = (FleetMemberAPI) Proxy.newProxyInstance(
                        FleetMemberAPI.class.getClassLoader(),
                        new Class<?>[]{FleetMemberAPI.class},
                        (p, method, args) -> switch (method.getName()) {
                            case "getId" -> id;
                            case "setId" -> {
                                id = (String) args[0];
                                yield null;
                            }
                            case "getShipName" -> shipName;
                            case "setShipName" -> {
                                shipName = (String) args[0];
                                yield null;
                            }
                            case "getVariant" -> variant.proxy();
                            case "setVariant" -> {
                                installedVariant = (ShipVariantAPI) args[0];
                                yield null;
                            }
                            case "getRepairTracker" -> repairTracker();
                            case "getStatus" -> status();
                            case "toString" -> "FakeMember[" + id + "]";
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals" -> p == args[0];
                            default -> defaultValue(method.getReturnType());
                        });
                BY_PROXY.put(proxy, this);
            }
            return proxy;
        }

        private RepairTrackerAPI repairTracker() {
            return (RepairTrackerAPI) Proxy.newProxyInstance(
                    RepairTrackerAPI.class.getClassLoader(),
                    new Class<?>[]{RepairTrackerAPI.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getBaseCR" -> cr;
                        case "setCR" -> {
                            cr = (Float) args[0];
                            yield null;
                        }
                        case "setMothballed" -> {
                            mothballed = (Boolean) args[0];
                            yield null;
                        }
                        case "toString" -> "FakeRepairTracker";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private FleetMemberStatusAPI status() {
            return (FleetMemberStatusAPI) Proxy.newProxyInstance(
                    FleetMemberStatusAPI.class.getClassLoader(),
                    new Class<?>[]{FleetMemberStatusAPI.class},
                    (p, method, args) -> {
                        int arity = args == null ? 0 : args.length;
                        switch (method.getName()) {
                            case "getNumStatuses":
                                return numStatuses >= 0 ? numStatuses : variant.modules.size() + 1;
                            case "getHullFraction":
                                return arity == 0 ? hullFraction
                                        : indexedHull.getOrDefault((Integer) args[0], 1f);
                            case "setHullFraction":
                                if (arity == 1) {
                                    hullFraction = (Float) args[0];
                                } else {
                                    indexedHull.put((Integer) args[0], (Float) args[1]);
                                }
                                return null;
                            case "toString":
                                return "FakeStatus";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }
}
