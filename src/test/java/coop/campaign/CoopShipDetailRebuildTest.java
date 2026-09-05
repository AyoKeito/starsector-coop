package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import coop.campaign.CoopShipDetail.WeaponGroup;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
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
 */
class CoopShipDetailRebuildTest {

    @BeforeEach
    void installSettings() {
        // Nothing in either path should need settings for these fixtures (every id resolves locally);
        // the stub is here so that a path that unexpectedly does reach for one fails on a null answer
        // rather than on a NullPointerException out of Global.
        Global.setSettings(ApiProxies.whiteSettings());
    }

    @AfterEach
    void clearSettings() {
        Global.setSettings(null);
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

    @Test
    void captureDropsAMemberWithNoIdRatherThanShippingANamelessListing() {
        assertNull(CoopCampaignReplicator.captureShipDetail(
                member("  ", "ISS Anonymous", new FakeVariant("hound_Standard", "hound", ""), 1f, 1f)));
    }

    @Test
    void captureAndRebuildAgreeOnAModularHull() {
        FakeVariant top = new FakeVariant("conquest_Elite", "conquest", "Warlord");
        top.weapons.put("WS0001", "heavymauler");
        top.groups.add(group(WeaponGroupType.ALTERNATING, true, "WS0001"));
        FakeVariant module = new FakeVariant("station_side_mod", "station_side", "Battery");
        module.weapons.put("WS0009", "lightac");
        top.modules.put("MODULE1", module);

        CoopShipDetail captured = CoopCampaignReplicator.captureShipDetail(
                member("member-4", "ISS Round Trip", top, 0.8f, 0.4f));
        // Rebuild onto a pristine copy of the same hull, exactly as a receiving client would.
        FakeVariant pristine = new FakeVariant("conquest_Elite", "conquest", "");
        pristine.modules.put("MODULE1", new FakeVariant("station_side_mod", "station_side", ""));
        FakeMember rebuilt = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(rebuilt.proxy(), captured);

        FakeVariant applied = pristine.lastClone;
        assertEquals("Warlord", applied.displayName);
        assertEquals("heavymauler", applied.weapons.get("WS0001"));
        assertEquals(1, applied.groups.size());
        assertEquals(WeaponGroupType.ALTERNATING, applied.groups.get(0).getType());
        assertEquals("lightac", pristine.modules.get("MODULE1").lastClone.weapons.get("WS0009"));
        assertEquals(0.4f, rebuilt.hullFraction, 1e-6f);
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
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(), storedStation());

        FakeVariant applied = pristine.lastClone;
        assertNotNull(applied, "the variant must be cloned before it is touched, never mutated in place");
        assertEquals(VariantSource.REFIT, applied.source);
        assertTrue(pristine.groups.isEmpty(), "the shared stock variant must be left alone");

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
    void groupSlotsWithNoWeaponOnThisClientAreDroppedTheWayVanillaDropsThem() {
        // The mod-mismatch case: the weapon id did not resolve, so the slot holds nothing. Vanilla's
        // CoreAutofitPlugin drops such slots and skips a group left empty; a group of nothing but
        // dangling slots must therefore not survive, and with no groups left we autogenerate.
        FakeVariant pristine = new FakeVariant("hound_Standard", "hound", "");
        FakeMember member = new FakeMember("local-id", "Unnamed", pristine);

        CoopCampaignReplicator.applyShipDetail(member.proxy(),
                new CoopShipDetail("m1", "", "hound_Standard", "hound", 1f, 0, 0,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        Map.of("WS0001", "heavymauler"), Map.of(),
                        List.of(new WeaponGroup(List.of("WS0001", "WS_MISSING"), false, false),
                                new WeaponGroup(List.of("WS_MISSING"), false, false)),
                        1f, "", Map.of()));

        FakeVariant applied = pristine.lastClone;
        assertEquals(1, applied.groups.size(), "the all-dangling group must not be added");
        assertEquals(List.of("WS0001"), applied.groups.get(0).getSlots());
        assertEquals(0, applied.autoGenerated);
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
     * A {@link ShipVariantAPI} that answers the accessors the replicator reads and records every
     * setter it calls. {@code clone()} hands back a copy and remembers it as {@link #lastClone}, which
     * is how a test sees what the rebuild wrote without the production code having to expose it.
     */
    private static final class FakeVariant {
        final String hullVariantId;
        final String hullId;
        String displayName;
        int vents;
        int caps;
        VariantSource source;
        final Map<String, String> weapons = new LinkedHashMap<>();
        final List<WeaponGroupSpec> groups = new ArrayList<>();
        final Map<String, FakeVariant> modules = new LinkedHashMap<>();
        final List<String> addedMods = new ArrayList<>();
        final List<String> permaMods = new ArrayList<>();
        final List<String> suppressedMods = new ArrayList<>();
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

        FakeVariant copy() {
            FakeVariant copy = new FakeVariant(hullVariantId, hullId, displayName);
            copy.vents = vents;
            copy.caps = caps;
            copy.weapons.putAll(weapons);
            copy.groups.addAll(groups);
            copy.modules.putAll(modules);
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
                    return hullSpec(hullId);
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
                case "addPermaMod":
                    permaMods.add((String) args[0]);
                    return null;
                case "addSuppressedMod":
                    suppressedMods.add((String) args[0]);
                    return null;
                case "removeSuppressedMod":
                    suppressedMods.remove(args[0]);
                    return null;
                case "getPermaMods":
                    return new LinkedHashSet<>(permaMods);
                case "getSMods":
                case "getSModdedBuiltIns":
                    return new LinkedHashSet<String>();
                case "getSuppressedMods":
                    return new LinkedHashSet<>(suppressedMods);
                case "getNonBuiltInHullmods":
                    return new ArrayList<>(addedMods);
                case "getWings":
                    return new ArrayList<String>();
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
    }

    private static ShipHullSpecAPI hullSpec(String hullId) {
        return (ShipHullSpecAPI) Proxy.newProxyInstance(
                ShipHullSpecAPI.class.getClassLoader(),
                new Class<?>[]{ShipHullSpecAPI.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getHullId" -> hullId;
                    case "getBuiltInWings" -> new ArrayList<String>();
                    case "toString" -> "FakeHull[" + hullId + "]";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A {@link FleetMemberAPI} whose variant, id, name, CR and hull fraction are all readable back. */
    private static final class FakeMember {
        String id;
        String shipName;
        final FakeVariant variant;
        ShipVariantAPI installedVariant;
        float cr = 1f;
        float hullFraction = 1f;
        boolean mothballed;
        private FleetMemberAPI proxy;

        FakeMember(String id, String shipName, FakeVariant variant) {
            this.id = id;
            this.shipName = shipName;
            this.variant = variant;
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
                    (p, method, args) -> switch (method.getName()) {
                        case "getHullFraction" -> hullFraction;
                        case "setHullFraction" -> {
                            hullFraction = (Float) args[0];
                            yield null;
                        }
                        case "toString" -> "FakeStatus";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }
}
