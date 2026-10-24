package coop.campaign;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonalityAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipRecoverySpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial.SleeperPodsSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SleeperPodsSpecial.SleeperSpecialType;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.CargoManifestSpecial.CargoManifestSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SurveyDataSpecial.SurveyDataSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.SurveyDataSpecial.SurveyDataSpecialType;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BreadcrumbSpecial.BreadcrumbSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TopographicDataSpecial.TopographicDataSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BlueprintSpecial.BlueprintSpecialData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.TransmitterTrapSpecial.TransmitterTrapSpecialData;
import coop.testing.ProxyDefaults;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoopWreckCodecTest {
    @Test void nativeShipDataRoundTripsWithoutRandomNameGeneration() {
        CoopWreckRecord.Ship source = CoopWreckFixtures.sample("wreck:a").ship();
        PerShipData nativeShip = CoopWreckCodec.buildShip(source);
        assertEquals(source, CoopWreckCodec.captureShip(nativeShip));
        assertNull(nativeShip.variant); assertEquals("hound_luddic_path", nativeShip.variantId);
        assertEquals("ISS Witness", nativeShip.shipName);
    }

    @Test void recoveryKeepsMultipleShipsNullableFlagsAndPreviousSpecialDistinct() {
        CoopWreckRecord.Ship ship = CoopWreckFixtures.sample("wreck:a").ship();
        CoopWreckRecord.Recovery source = new CoopWreckRecord.Recovery("sealed|hangar", true,
                false, null, List.of(ship, ship));
        ShipRecoverySpecialData nativeData = CoopWreckCodec.buildRecovery(source);
        assertEquals(source, CoopWreckCodec.captureRecovery(nativeData));
        assertNull(CoopWreckCodec.buildRecovery(null)); assertNull(CoopWreckCodec.captureRecovery(null));
        assertThrows(IllegalStateException.class, () -> CoopWreckCodec.captureRecovery(new Object()));
    }

    @Test void everyNativeWreckSpecialFamilyRoundTripsBothCurrentAndPreviousSlots() {
        List<Object> nativeSpecials = new ArrayList<>();
        for (SleeperSpecialType type : SleeperSpecialType.values()) nativeSpecials.add(new SleeperPodsSpecialData(type, 3, 9));
        nativeSpecials.add(new CargoManifestSpecialData("organs|cargo\\", 3.25f, 9.5f));
        SurveyDataSpecialData survey = new SurveyDataSpecialData(SurveyDataSpecialType.PLANET_SURVEY_DATA);
        survey.entityId = "planet|id"; survey.secondaryId = "legacy\\id"; survey.includeRuins = false; nativeSpecials.add(survey);
        nativeSpecials.add(new SurveyDataSpecialData(null));
        BreadcrumbSpecialData breadcrumb = new BreadcrumbSpecialData("target|\\"); breadcrumb.targetName = "Name\n|"; nativeSpecials.add(breadcrumb);
        nativeSpecials.add(new TopographicDataSpecialData(137)); nativeSpecials.add(new BlueprintSpecialData());
        TransmitterTrapSpecialData trap = new TransmitterTrapSpecialData(); trap.prob = .75f;
        trap.nearbyFleetFaction = "pirates"; trap.useAllFleetsInRange = true; trap.useClosestFleetInRange = false;
        trap.params = ordinaryTrapParams(); nativeSpecials.add(trap);
        for (Object original : nativeSpecials) {
            CoopWreckRecord.Recovery captured = CoopWreckCodec.captureRecovery(original);
            CoopWreckRecord encoded = specialRecord(captured);
            CoopWreckRecord decoded = CoopWreckRecord.decode(encoded.encode());
            assertEquals(captured, decoded.recovery(), original.getClass().getSimpleName());
            assertEquals(captured, decoded.previousRecovery());
            assertEquals(captured, CoopWreckCodec.captureRecovery(CoopWreckCodec.buildSpecial(decoded.recovery())));
        }
    }

    @Test void trapFleetRecipePreservesNativeScalarsFlagsListsAndLocationWithoutRerolling() {
        FleetParamsV3 original = ordinaryTrapParams();
        original.quality = .4f; original.qualityOverride = .8f; original.averageSMods = 2;
        original.maxShipSize = 4; original.minShipSize = 2; original.withOfficers = false;
        original.ignoreMarketFleetSizeMult = false; original.onlyApplyFleetSizeToCombatShips = true;
        original.doNotPrune = true; original.officerLevelBonus = 2; original.officerNumberBonus = 3;
        original.officerNumberMult = 1.2f; original.maxOfficersToAdd = 7; original.maxNumShips = 12;
        original.onlyRetainFlagship = false; original.flagshipVariantId = "variant|\\";
        original.timestamp = Long.MAX_VALUE; original.addShips = List.of("a|b", "c\\d");
        original.allWeapons = true; original.noCommanderSkills = false; original.blockFallback = true;
        String encoded = CoopWreckCodec.encodeFleetParams(original);
        FleetParamsV3 rebuilt = CoopWreckCodec.decodeFleetParams(encoded);
        assertEquals(encoded, CoopWreckCodec.encodeFleetParams(rebuilt));
        assertEquals(original.addShips, rebuilt.addShips); assertEquals(original.locInHyper, rebuilt.locInHyper);
        assertEquals(original.timestamp, rebuilt.timestamp); assertEquals(false, rebuilt.ignoreMarketFleetSizeMult);
        assertNull(rebuilt.random); assertNull(rebuilt.commander); assertNull(rebuilt.doctrineOverride);
    }

    @Test void trapCustomRngAndStructuredRecipesAreRejectedInsteadOfSilentlyDegraded() {
        FleetParamsV3 params = ordinaryTrapParams(); params.random = new java.util.Random(123);
        assertThrows(IllegalStateException.class, () -> CoopWreckCodec.encodeFleetParams(params));
        params.random = null;
        params.commander = (PersonAPI) Proxy.newProxyInstance(PersonAPI.class.getClassLoader(), new Class<?>[]{PersonAPI.class},
                (p, m, a) -> ProxyDefaults.defaultValue(m.getReturnType()));
        assertThrows(IllegalStateException.class, () -> CoopWreckCodec.encodeFleetParams(params));
        String encoded = CoopWreckCodec.encodeFleetParams(ordinaryTrapParams());
        assertThrows(IllegalArgumentException.class, () -> CoopWreckCodec.decodeFleetParams(encoded + "|extra"));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckCodec.decodeFleetParams("true|truncated"));
    }

    @Test void generatedSleeperOfficerAndAdministratorKeepSkillsIdentityAndExceptionalFlags() {
        Global.setSettings(ApiProxies.whiteSettings());
        FactoryAPI factory = (FactoryAPI) Proxy.newProxyInstance(FactoryAPI.class.getClassLoader(), new Class<?>[]{FactoryAPI.class},
                (p, m, a) -> m.getName().equals("createPerson") ? new NativePerson().api : ProxyDefaults.defaultValue(m.getReturnType()));
        Global.setFactory(factory);
        try {
            for (SleeperSpecialType type : List.of(SleeperSpecialType.OFFICER, SleeperSpecialType.ADMIN)) {
                NativePerson person = new NativePerson(); person.id = "cryopod-" + type; person.level = 7; person.xp = 123456;
                person.skills.put("helmsmanship", 2f); person.skills.put("combat_endurance", 1f);
                person.memory.put(MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER, true);
                if (type == SleeperSpecialType.ADMIN) { person.memory.put("$ome_isAdmin", true); person.memory.put("$ome_adminTier", 2); }
                SleeperPodsSpecialData nativeData = new SleeperPodsSpecialData(type, person.api); nativeData.min = 1; nativeData.max = 1;
                CoopWreckRecord.Recovery source = CoopWreckCodec.captureRecovery(nativeData);
                CoopWreckRecord.Recovery wire = CoopWreckRecord.decode(specialRecord(source).encode()).recovery();
                assertEquals(source, wire);
                SleeperPodsSpecialData rebuilt = (SleeperPodsSpecialData) CoopWreckCodec.buildSpecial(wire);
                assertEquals(source, CoopWreckCodec.captureRecovery(rebuilt));
                assertEquals(true, rebuilt.officer.getMemoryWithoutUpdate().get(MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER));
                assertEquals(2f, wire.special().person().skills().get("helmsmanship"));
                assertEquals(type == SleeperSpecialType.ADMIN ? CoopPersonDetail.Role.ADMIN : CoopPersonDetail.Role.OFFICER,
                        wire.special().person().role());
            }
        } finally { Global.setFactory(null); Global.setSettings(null); }
    }

    @Test void salvageMutationChangesOnlyTheLootRecipeAndPreservesHostTrapAppliedFlag() throws Throwable {
        NativeEntity n = new NativeEntity(); CoopWreckRecord source = CoopWreckFixtures.sample("wreck:a"); n.configure(source);
        PerShipData originalShip = n.plugin.getData().ship;
        n.location.set(900, 800); n.velocity.set(5, 4); n.memory.put("$coopWreckTrapApplied", true);
        CoopWreckRecord target = specialRecord(CoopWreckCodec.captureRecovery(new TopographicDataSpecialData(40)));
        CoopWreckCodec.applySalvage(n.entity, target);
        assertEquals(new Vector2f(900, 800), n.location); assertEquals(new Vector2f(5, 4), n.velocity);
        assertSame(originalShip, n.plugin.getData().ship);
        assertEquals(target.recovery(), CoopWreckCodec.captureRecovery(n.memory.get(MemFlags.SALVAGE_SPECIAL_DATA)));
        assertEquals(true, n.memory.get("$coopWreckTrapApplied")); assertEquals(0, n.localSensorWrites);
    }

    private static FleetParamsV3 ordinaryTrapParams() {
        FleetParamsV3 params = new FleetParamsV3(); params.locInHyper = new Vector2f(12, -8);
        params.factionId = "pirates"; params.fleetType = "patrolSmall"; params.combatPts = 85; return params;
    }

    private static CoopWreckRecord specialRecord(CoopWreckRecord.Recovery special) {
        CoopWreckRecord r = CoopWreckFixtures.sample("wreck:a");
        return new CoopWreckRecord(r.version(), r.id(), r.hostEntityId(), r.name(), r.factionId(), r.x(), r.y(),
                r.vx(), r.vy(), r.facing(), r.angularVelocity(), r.elapsedDays(), r.durationDays(), r.expiring(),
                r.canHaveExtraCargo(), r.ship(), r.salvageSeed(), special, special, r.dropValue(), r.dropRandom(),
                r.extraSalvage(), r.temporaryExtraSalvage(), r.discoveryXP(), r.salvageXP(), r.sensorProfile(),
                r.discoverable(), r.radius(), r.detectionModifiers(), r.tags(), r.memory());
    }

    @Test void nativeCustomDropsKeepPickerWeightsAndIndependentFrequencies() {
        CoopWreckRecord.Drop source = new CoopWreckRecord.Drop(null, 2, 5, -1, .7f,
                List.of(new CoopWreckRecord.DropRow("supplies", "custom", 3, 9),
                        new CoopWreckRecord.DropRow("fuel", "custom", 4, 0)));
        List<DropData> nativeDrops = CoopWreckCodec.buildDrops(List.of(source));
        assertEquals(List.of(source), CoopWreckCodec.captureDrops(nativeDrops));
        assertEquals(9f, nativeDrops.get(0).getCustom().getWeight(0));
        assertEquals(3f, nativeDrops.get(0).getCustom().getItems().get(0).getFreq());
    }

    @Test void rangeModifiersPreserveAllThreeKindsAndDescriptions() {
        StatBonus bonus = new StatBonus(); bonus.modifyFlat("old", 999);
        List<CoopWreckRecord.Modifier> modifiers = List.of(
                new CoopWreckRecord.Modifier("FLAT", "gen", "range", 800),
                new CoopWreckRecord.Modifier("MULT", "test", null, .8f),
                new CoopWreckRecord.Modifier("PERCENT", "skill", "bonus", 15));
        CoopWreckCodec.applyModifiers(bonus, modifiers);
        assertEquals(modifiers, CoopWreckCodec.captureModifiers(bonus));
        assertFalse(bonus.getFlatBonuses().containsKey("old"));
    }

    @Test void managedGameplayMemoryDoesNotCopyLocalDiscoveryOrCoopTransportState() {
        NativeEntity n = new NativeEntity();
        n.memory.put("$fromGhost", true); n.memory.put("$salvageSpecId", "custom");
        n.memory.put("$localDiscovery", true); n.memory.put("$coopWreckId", "wreck:local");
        List<CoopWreckRecord.MemoryValue> entries = CoopWreckCodec.captureMemory(n.memoryAPI);
        assertEquals(List.of("$fromGhost", "$salvageSpecId"), entries.stream().map(CoopWreckRecord.MemoryValue::key).toList());
        CoopWreckCodec.applyMemory(n.memoryAPI,
                List.of(new CoopWreckRecord.MemoryValue("$fromGhost", "BOOLEAN", "false", 4)));
        assertEquals(false, n.memory.get("$fromGhost")); assertFalse(n.memory.containsKey("$salvageSpecId"));
        assertEquals(true, n.memory.get("$localDiscovery")); assertEquals("wreck:local", n.memory.get("$coopWreckId"));
        assertEquals(4f, n.expiry.get("$fromGhost"));
    }

    @Test void unknownMemoryKeysAreRejectedBeforeAnyManagedKeyIsRemoved() {
        NativeEntity n = new NativeEntity(); n.memory.put("$fromGhost", true);
        assertThrows(IllegalArgumentException.class, () -> CoopWreckCodec.applyMemory(n.memoryAPI,
                List.of(new CoopWreckRecord.MemoryValue("$localDiscovery", "BOOLEAN", "false", -1))));
        assertEquals(true, n.memory.get("$fromGhost"));
    }

    @Test void completeNativeSnapshotCapturesProvenanceLifetimeAndSalvageWithoutDiscovery() throws Throwable {
        NativeEntity n = new NativeEntity(); CoopWreckRecord source = CoopWreckFixtures.sample("wreck:a");
        n.configure(source);
        CoopWreckRecord captured = CoopWreckCodec.capture(n.entity, source.id());
        assertEquals(source, captured);
        assertEquals(0, n.localSensorWrites);
    }

    @Test void updatingExistingWreckPreservesIdentityAndLocalSensorState() throws Throwable {
        NativeEntity n = new NativeEntity(); CoopWreckRecord source = CoopWreckFixtures.sample("wreck:a");
        n.configure(source); n.location.set(0, 0); n.velocity.set(0, 0);
        n.memory.put("$localDiscovery", true); n.tags.add("coop_replica"); n.tags.add("stale");
        CoopWreckCodec.apply(n.entity, source);
        assertEquals(source.x(), n.location.x); assertEquals(source.vy(), n.velocity.y);
        assertEquals(true, n.memory.get("$localDiscovery")); assertEquals(0, n.localSensorWrites);
        assertTrue(n.tags.contains("coop_replica")); assertFalse(n.tags.contains("stale"));
        assertEquals(source, CoopWreckCodec.capture(n.entity, source.id()));
    }

    @Test void legacyAdoptionRequiresIdSeedAndFullRecoveryRecipe() throws Throwable {
        NativeEntity n = new NativeEntity(); CoopWreckRecord source = CoopWreckFixtures.sample("wreck:a");
        n.configure(source); assertTrue(CoopWreckCodec.matchesLegacy(n.entity, source));
        n.memory.put(MemFlags.SALVAGE_SEED, source.salvageSeed() + 1);
        assertFalse(CoopWreckCodec.matchesLegacy(n.entity, source));
        n.memory.put(MemFlags.SALVAGE_SEED, source.salvageSeed());
        n.memory.remove(MemFlags.SALVAGE_SPECIAL_DATA);
        assertFalse(CoopWreckCodec.matchesLegacy(n.entity, source));
        n.configure(source); n.id = "unrelated-native-wreck";
        assertFalse(CoopWreckCodec.matchesLegacy(n.entity, source));
    }

    @Test void heartbeatDoesNotReplayLocallyConsumedGhostDescription() throws Throwable {
        NativeEntity n = new NativeEntity(); CoopWreckRecord source = CoopWreckFixtures.sample("wreck:a");
        n.configure(source); n.memory.remove("$fromGhost");
        CoopWreckCodec.apply(n.entity, source);
        assertFalse(n.memory.containsKey("$fromGhost"));
        n.memory.put("$fromGhost", false);
        CoopWreckCodec.apply(n.entity, source);
        assertEquals(false, n.memory.get("$fromGhost"));
    }

    @Test void nativeSubclassAndMissingSeedFailLoudly() throws Throwable {
        NativeEntity n = new NativeEntity(); n.configure(CoopWreckFixtures.sample("wreck:a"));
        n.plugin = new DerelictShipEntityPlugin() { };
        assertFalse(CoopWreckCodec.isNativeWreck(n.entity));
        assertThrows(IllegalArgumentException.class, () -> CoopWreckCodec.capture(n.entity, "wreck:a"));
        n.plugin = new DerelictShipEntityPlugin(); n.configure(CoopWreckFixtures.sample("wreck:a"));
        n.memory.remove(MemFlags.SALVAGE_SEED);
        assertThrows(IllegalStateException.class, () -> CoopWreckCodec.capture(n.entity, "wreck:a"));
    }

    @Test void structuredDefenderOrDebrisOverrideCannotBeSilentlyDropped() throws Throwable {
        NativeEntity n = new NativeEntity(); n.configure(CoopWreckFixtures.sample("wreck:a"));
        for (String key : List.of(MemFlags.SALVAGE_DEFENDER_OVERRIDE, MemFlags.SALVAGE_DEBRIS_FIELD)) {
            n.memory.put(key, new Object());
            assertThrows(IllegalStateException.class, () -> CoopWreckCodec.capture(n.entity, "wreck:a"));
            n.memory.remove(key);
        }
    }

    @Test void variantMetadataIncludesBoardabilityFlagsOnRootAndModules() {
        Variant root = new Variant(), module = new Variant(); root.modules.put("station|slot", module);
        root.tags.add("unboardable"); module.tags.add("always_recoverable");
        root.source = VariantSource.STOCK; root.original = "original"; root.goal = true;
        List<CoopWreckRecord.VariantState> flags = CoopWreckCodec.captureVariantState(root.api);
        Variant target = new Variant(), targetModule = new Variant(); target.modules.put("station|slot", targetModule);
        target.tags.add("stale"); targetModule.tags.add("stale");
        CoopWreckCodec.applyVariantState(target.api, flags);
        assertEquals(flags, CoopWreckCodec.captureVariantState(target.api));
        assertEquals(Set.of("unboardable"), target.tags);
        assertEquals(Set.of("always_recoverable"), targetModule.tags);
        assertThrows(IllegalStateException.class, () -> CoopWreckCodec.applyVariantState(new Variant().api, flags));
    }

    @Test void captainMetadataPreservesAiCoreAndTags() {
        Map<String, Object> values = new HashMap<>(); Set<String> tags = new LinkedHashSet<>();
        PersonAPI person = (PersonAPI) Proxy.newProxyInstance(PersonAPI.class.getClassLoader(),
                new Class<?>[]{PersonAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getAICoreId" -> values.get("core"); case "getTags" -> tags;
                    case "getImportance" -> values.get("importance"); case "getVoice" -> values.get("voice");
                    case "setAICoreId" -> { values.put("core", a[0]); yield null; }
                    case "clearTags" -> { tags.clear(); yield null; }
                    case "addTag" -> { tags.add((String) a[0]); yield null; }
                    case "setImportance" -> { values.put("importance", a[0]); yield null; }
                    case "setVoice" -> { values.put("voice", a[0]); yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        tags.add("stale");
        CoopWreckRecord.CaptainState state = new CoopWreckRecord.CaptainState("alpha_core",
                List.of("officer_tag"), "VERY_HIGH", "ai_voice");
        CoopWreckCodec.applyCaptainState(person, state);
        assertEquals(state, CoopWreckCodec.captureCaptainState(person));
        assertEquals(PersonImportance.VERY_HIGH, values.get("importance"));
        assertFalse(tags.contains("stale"));
    }

    private static final class Variant {
        final Set<String> tags = new LinkedHashSet<>(); final Map<String, Variant> modules = new HashMap<>();
        VariantSource source = VariantSource.REFIT; String original; boolean goal, auto;
        final ShipVariantAPI api = (ShipVariantAPI) Proxy.newProxyInstance(ShipVariantAPI.class.getClassLoader(),
                new Class<?>[]{ShipVariantAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getTags" -> tags; case "getSource" -> source; case "getOriginalVariant" -> original;
                    case "isGoalVariant" -> goal; case "isMayAutoAssignWeapons" -> auto;
                    case "getModuleSlots" -> new ArrayList<>(modules.keySet());
                    case "getModuleVariant" -> modules.containsKey(a[0]) ? modules.get(a[0]).api : null;
                    case "clearTags" -> { tags.clear(); yield null; }
                    case "addTag" -> { tags.add((String) a[0]); yield null; }
                    case "setSource" -> { source = (VariantSource) a[0]; yield null; }
                    case "setOriginalVariant" -> { original = (String) a[0]; yield null; }
                    case "setGoalVariant" -> { goal = (Boolean) a[0]; yield null; }
                    case "setMayAutoAssignWeapons" -> { auto = (Boolean) a[0]; yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
    }

    private static final class NativePerson {
        String id = "officer", faction = "independent", portrait = "portrait", personality = "steady", rank = "captain", post = "fleet_commander";
        String core, voice = "officer_voice"; int level = 1; long xp;
        FullName name = new FullName("Ada", "Test", FullName.Gender.FEMALE); PersonImportance importance = PersonImportance.MEDIUM;
        final Map<String, Object> memory = new HashMap<>(); final Set<String> tags = new LinkedHashSet<>();
        final Map<String, Float> skills = new LinkedHashMap<>();
        final MemoryAPI memoryAPI = (MemoryAPI) Proxy.newProxyInstance(MemoryAPI.class.getClassLoader(), new Class<?>[]{MemoryAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "get" -> memory.get(a[0]); case "contains" -> memory.containsKey(a[0]);
                    case "getBoolean" -> Boolean.TRUE.equals(memory.get(a[0]));
                    case "getInt" -> memory.get(a[0]) instanceof Number n ? n.intValue() : 0;
                    case "getExpire" -> -1f;
                    case "set" -> { memory.put((String) a[0], a[1]); yield null; }
                    case "unset" -> { memory.remove(a[0]); yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        final MutableCharacterStatsAPI stats = (MutableCharacterStatsAPI) Proxy.newProxyInstance(MutableCharacterStatsAPI.class.getClassLoader(),
                new Class<?>[]{MutableCharacterStatsAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getLevel" -> level; case "getXP" -> xp; case "getSkillsCopy" -> skills.entrySet().stream().map(e -> skill(e.getKey(), e.getValue())).toList();
                    case "setLevel" -> { level = (Integer) a[0]; yield null; } case "setXP" -> { xp = (Long) a[0]; yield null; }
                    case "setSkillLevel" -> { skills.put((String) a[0], (Float) a[1]); yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        final PersonAPI api = (PersonAPI) Proxy.newProxyInstance(PersonAPI.class.getClassLoader(), new Class<?>[]{PersonAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getId" -> id; case "getName" -> name; case "getGender" -> name.getGender();
                    case "getPortraitSprite" -> portrait; case "getRankId" -> rank; case "getPostId" -> post;
                    case "getStats" -> stats; case "getMemoryWithoutUpdate" -> memoryAPI; case "getTags" -> tags;
                    case "getAICoreId" -> core; case "getVoice" -> voice; case "getImportance" -> importance;
                    case "getFaction" -> Proxy.newProxyInstance(FactionAPI.class.getClassLoader(), new Class<?>[]{FactionAPI.class},
                            (q, n, b) -> n.getName().equals("getId") ? faction : ProxyDefaults.defaultValue(n.getReturnType()));
                    case "getPersonalityAPI" -> Proxy.newProxyInstance(PersonalityAPI.class.getClassLoader(), new Class<?>[]{PersonalityAPI.class},
                            (q, n, b) -> n.getName().equals("getId") ? personality : ProxyDefaults.defaultValue(n.getReturnType()));
                    case "setId" -> { id = (String) a[0]; yield null; } case "setName" -> { name = (FullName) a[0]; yield null; }
                    case "setFaction" -> { faction = (String) a[0]; yield null; } case "setPortraitSprite" -> { portrait = (String) a[0]; yield null; }
                    case "setRankId" -> { rank = (String) a[0]; yield null; } case "setPostId" -> { post = (String) a[0]; yield null; }
                    case "setPersonality" -> { personality = (String) a[0]; yield null; } case "setAICoreId" -> { core = (String) a[0]; yield null; }
                    case "setVoice" -> { voice = (String) a[0]; yield null; } case "setImportance" -> { importance = (PersonImportance) a[0]; yield null; }
                    case "clearTags" -> { tags.clear(); yield null; } case "addTag" -> { tags.add((String) a[0]); yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        static MutableCharacterStatsAPI.SkillLevelAPI skill(String id, float level) {
            SkillSpecAPI spec = (SkillSpecAPI) Proxy.newProxyInstance(SkillSpecAPI.class.getClassLoader(), new Class<?>[]{SkillSpecAPI.class},
                    (p, m, a) -> m.getName().equals("getId") ? id : ProxyDefaults.defaultValue(m.getReturnType()));
            return (MutableCharacterStatsAPI.SkillLevelAPI) Proxy.newProxyInstance(MutableCharacterStatsAPI.SkillLevelAPI.class.getClassLoader(),
                    new Class<?>[]{MutableCharacterStatsAPI.SkillLevelAPI.class}, (p, m, a) -> switch (m.getName()) {
                        case "getSkill" -> spec; case "getLevel" -> level; default -> ProxyDefaults.defaultValue(m.getReturnType());
                    });
        }
    }

    private static final class NativeEntity {
        DerelictShipEntityPlugin plugin = new DerelictShipEntityPlugin();
        String id, name, faction = "neutral"; float facing, radius, sensor;
        Float discoveryXP, salvageXP; boolean discoverable; int localSensorWrites;
        final Vector2f location = new Vector2f(), velocity = new Vector2f();
        final Set<String> tags = new LinkedHashSet<>();
        final List<DropData> value = new ArrayList<>(), random = new ArrayList<>();
        final Map<String, Object> memory = new HashMap<>(); final Map<String, Float> expiry = new HashMap<>();
        final StatBonus detection = new StatBonus();
        final MemoryAPI memoryAPI = (MemoryAPI) Proxy.newProxyInstance(MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "get" -> memory.get(a[0]); case "contains" -> memory.containsKey(a[0]);
                    case "getLong" -> ((Number) memory.get(a[0])).longValue();
                    case "getExpire" -> expiry.getOrDefault(a[0], -1f);
                    case "set" -> { memory.put((String) a[0], a[1]); expiry.put((String) a[0], a.length > 2 ? (Float) a[2] : -1f); yield null; }
                    case "unset" -> { memory.remove(a[0]); expiry.remove(a[0]); yield null; }
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
        final FactionAPI factionAPI = (FactionAPI) Proxy.newProxyInstance(FactionAPI.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class}, (p, m, a) -> m.getName().equals("getId") ? faction : ProxyDefaults.defaultValue(m.getReturnType()));
        final CustomCampaignEntityAPI entity = (CustomCampaignEntityAPI) Proxy.newProxyInstance(CustomCampaignEntityAPI.class.getClassLoader(),
                new Class<?>[]{CustomCampaignEntityAPI.class}, (p, m, a) -> {
                    if (m.getName().contains("Fader") && m.getName().startsWith("set")) { localSensorWrites++; return null; }
                    return switch (m.getName()) {
                        case "getId" -> id; case "getName" -> name; case "getFaction" -> factionAPI;
                        case "getCustomEntityType" -> "wreck"; case "getCustomPlugin" -> plugin;
                        case "getLocation" -> location; case "getVelocity" -> velocity;
                        case "getMemoryWithoutUpdate" -> memoryAPI; case "getTags" -> tags;
                        case "getDropValue" -> value; case "getDropRandom" -> random;
                        case "getFacing" -> facing; case "getRadius" -> radius; case "getSensorProfile" -> sensor;
                        case "getDiscoveryXP" -> discoveryXP; case "getSalvageXP" -> salvageXP;
                        case "isDiscoverable" -> discoverable; case "getDetectedRangeMod" -> detection;
                        case "setName" -> { name = (String) a[0]; yield null; }
                        case "setFaction" -> { faction = (String) a[0]; yield null; }
                        case "setLocation" -> { location.set((Float) a[0], (Float) a[1]); yield null; }
                        case "setFacing" -> { facing = (Float) a[0]; yield null; }
                        case "setRadius" -> { radius = (Float) a[0]; yield null; }
                        case "setSensorProfile" -> { sensor = (Float) a[0]; yield null; }
                        case "setDiscoveryXP" -> { discoveryXP = (Float) a[0]; yield null; }
                        case "setSalvageXP" -> { salvageXP = (Float) a[0]; yield null; }
                        case "setDiscoverable" -> { discoverable = (Boolean) a[0]; yield null; }
                        case "addTag" -> { tags.add((String) a[0]); yield null; }
                        case "removeTag" -> { tags.remove(a[0]); yield null; }
                        default -> ProxyDefaults.defaultValue(m.getReturnType());
                    };
                });

        void configure(CoopWreckRecord r) throws Throwable {
            id = r.hostEntityId(); name = r.name(); faction = r.factionId();
            location.set(r.x(), r.y()); velocity.set(r.vx(), r.vy()); facing = r.facing(); radius = r.radius();
            sensor = r.sensorProfile(); discoverable = r.discoverable(); discoveryXP = r.discoveryXP(); salvageXP = r.salvageXP();
            DerelictShipData data = new DerelictShipData(CoopWreckCodec.buildShip(r.ship()), r.canHaveExtraCargo()); data.durationDays = r.durationDays();
            var lookup = MethodHandles.privateLookupIn(DerelictShipEntityPlugin.class, MethodHandles.lookup());
            lookup.findSetter(DerelictShipEntityPlugin.class, "data", DerelictShipData.class).invoke(plugin, data);
            lookup.findSetter(DerelictShipEntityPlugin.class, "elapsed", float.class).invoke(plugin, r.elapsedDays());
            lookup.findSetter(DerelictShipEntityPlugin.class, "angVel", float.class).invoke(plugin, r.angularVelocity());
            lookup.findSetter(DerelictShipEntityPlugin.class, "expiring", Boolean.class).invoke(plugin, r.expiring());
            memory.put(MemFlags.SALVAGE_SEED, r.salvageSeed());
            memory.put(MemFlags.SALVAGE_SPECIAL_DATA, CoopWreckCodec.buildRecovery(r.recovery()));
            value.clear(); value.addAll(CoopWreckCodec.buildDrops(r.dropValue()));
            random.clear(); random.addAll(CoopWreckCodec.buildDrops(r.dropRandom()));
            tags.clear(); tags.addAll(r.tags()); CoopWreckCodec.applyModifiers(detection, r.detectionModifiers());
            CoopWreckCodec.applyMemory(memoryAPI, r.memory());
        }
    }
}
