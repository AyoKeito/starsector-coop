package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.ambient.CoopEncounterReplayBridge;
import coop.campaign.CoopEncounterRecord;
import coop.campaign.CoopEncounterWorld;
import coop.campaign.CoopWreckFixtures;
import coop.campaign.CoopWreckRecord;
import coop.fleet.CoopEntityIds;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.testing.ApiProxies;
import coop.testing.RecordingNetService;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.*;

class CoopAgentEncountersTest {
    private SectorAPI oldSector;
    private SettingsAPI oldSettings;
    @BeforeEach void setup() {
        oldSector = Global.getSector(); oldSettings = Global.getSettings();
        Global.setSettings(ApiProxies.whiteSettings()); CoopEncounterReplayBridge.clear();
    }
    @AfterEach void cleanup() {
        CoopEncounterReplayBridge.clear(); Global.setSector(oldSector); Global.setSettings(oldSettings);
        coop.fleet.CoopLocations.invalidate();
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test void bothCommandsAreRegisteredAndRefuseAMissingCampaign() throws Exception {
        CoopAgentCommands commands = new CoopAgentCommands();
        for (String verb : List.of("encounters", "encounterspawn")) {
            assertTrue(commands.verbs().contains(verb));
            JSONObject reply = new JSONObject(commands.dispatch("{\"id\":1,\"cmd\":\"" + verb + "\"}", context(null, null)));
            assertFalse(reply.getBoolean("ok"));
            assertEquals("IllegalStateException: no campaign loaded", reply.getString("error"));
        }
    }

    @Test void queryUsesSavedCanonicalMembershipAndReportsCurrentGameplayAndLookupFailures() throws Exception {
        Scene scene = new Scene(); NativeSystem system = scene.addSystem();
        CoopEncounterRecord recipe = recipe(); system.memory.put(CoopEncounterWorld.RECIPE_KEY, recipe.encode());
        system.optionalId = "instance";
        Map<String, Object> flags = new HashMap<>(); flags.put("$visited", true); flags.put("$ruinsExplored", true);
        SectorEntityToken planet = scene.entity("coopenc_instance_system0", system.api, flags, true);
        SectorEntityToken anchor = scene.entity("coopenc_instance_anchor0", scene.hyper, Map.of(), false);
        system.entities.add(planet); scene.hyperEntities.add(anchor);
        scene.sectorLookup.put(planet.getId(), planet); // Anchor deliberately fails the sector registry check.
        JSONObject out = CoopAgentCommands.encounters(new JSONObject(), context(scene.sector, null));
        assertEquals(123456789L, out.getLong("campaignTimestamp")); assertEquals(1, out.getInt("count"));
        assertFalse(out.getBoolean("truncated"));
        JSONObject row = out.getJSONObject("encounters").getJSONObject("instance");
        assertEquals("instance", row.getString("optionalUniqueId"));
        assertEquals(Long.toString(Long.MIN_VALUE), row.getJSONObject("recipe").getString("effectiveSeed"));
        JSONObject live = row.getJSONObject("entities").getJSONObject(planet.getId());
        assertEquals("planet", live.getString("type")); assertEquals("planet:frozen", live.getString("specId"));
        assertEquals(12.5, live.getDouble("x")); assertEquals(40, live.getDouble("radius"));
        assertEquals("FULL", live.getString("surveyLevel")); assertEquals("cold", live.getJSONArray("conditions").getString(0));
        assertTrue(live.getBoolean("ruinsExplored")); assertTrue(live.getJSONObject("hintFlags").getBoolean("$visited"));
        assertTrue(live.getBoolean("canonicalResolves")); assertTrue(live.getBoolean("locationResolves"));
        assertTrue(live.getBoolean("nativeSectorResolves"));
        JSONObject link = row.getJSONObject("entities").getJSONObject(anchor.getId());
        assertTrue(link.getBoolean("canonicalResolves"));
        assertFalse(link.getBoolean("nativeSectorResolves")); assertTrue(link.getBoolean("locationResolves"));
        JSONObject consumed = row.getJSONObject("entities").getJSONObject("coopenc_instance_system1");
        assertTrue(consumed.getBoolean("consumed")); assertFalse(consumed.getBoolean("present"));
        assertEquals(recipe.encode(), system.memory.get(CoopEncounterWorld.RECIPE_KEY), "query must not update the recipe");
        assertEquals(0, row.getJSONObject("untrackedEntities").length());
    }

    @Test void compositeManifestSurvivesTheNativeSectorCastContractWithoutChangingLocalIds() throws Exception {
        Scene scene = new Scene(); NativeSystem system = scene.addSystem(); system.optionalId = "instance";
        Global.setSector(scene.sector);
        CoopEncounterRecord base = recipe();
        CoopEncounterRecord shared = new CoopEncounterRecord(base.instanceId(), base.effectiveSeed(), base.encounterPointId(),
                base.x(), base.y(), base.depth(), base.threatRollRan(), base.creator(), base.threatTag(),
                List.of(new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, 0, "planet:frozen"),
                        new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, 1, "token"),
                        new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.ANCHOR, 0, "token")), List.of(), CoopEncounterRecord.Overlay.empty());
        system.memory.put(CoopEncounterWorld.RECIPE_KEY, shared.encode());
        Map<String, Object> planetMemory = new HashMap<>();
        planetMemory.put(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, "coopenc_instance_system0");
        planetMemory.put(CoopEncounterWorld.ENCOUNTER_ID_KEY, "instance");
        SectorEntityToken planet = scene.entity("native-planet", system.api, planetMemory, true);
        SectorEntityToken center = scene.entity("native-center", system.api,
                Map.of(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, "coopenc_instance_system1", CoopEncounterWorld.ENCOUNTER_ID_KEY, "instance"), false);
        SectorEntityToken anchor = scene.entity("native-anchor", scene.hyper,
                Map.of(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, "coopenc_instance_anchor0", CoopEncounterWorld.ENCOUNTER_ID_KEY, "instance"), false);
        system.entities.add(planet); system.entities.add(center); scene.hyperEntities.add(anchor);
        scene.orbitFocus.put("native-planet", center);
        scene.sectorLookup.put("native-planet", planet);
        scene.sectorFaultIds.add("native-center"); scene.sectorFaultIds.add("native-anchor");
        JSONObject members = CoopAgentCommands.encounters(new JSONObject(), context(scene.sector, null))
                .getJSONObject("encounters").getJSONObject("instance").getJSONObject("entities");
        JSONObject currentCenter = members.getJSONObject("coopenc_instance_system1");
        assertEquals("native-center", currentCenter.getString("engineId"));
        assertTrue(currentCenter.getBoolean("canonicalResolves")); assertTrue(currentCenter.getBoolean("locationResolves"));
        assertFalse(currentCenter.getBoolean("nativeSectorResolves"));
        assertFalse(currentCenter.getBoolean("nativeSectorLookupExempt"), "an API proxy is not classified as the engine's concrete LocationToken");
        assertTrue(currentCenter.getString("nativeSectorLookupError").contains("ClassCastException"));
        assertEquals("coopenc_instance_system1", members.getJSONObject("coopenc_instance_system0").getJSONObject("orbit").getString("focusId"));
        assertTrue(members.getJSONObject("coopenc_instance_anchor0").getBoolean("canonicalResolves"));
        assertEquals("native-center", center.getId()); assertEquals("native-anchor", anchor.getId());
    }

    @Test void queryCountsLegacySystemsAndExposesUntrackedGeneratedMembers() throws Exception {
        Scene scene = new Scene(); scene.addSystem(); NativeSystem shared = scene.addSystem();
        shared.memory.put(CoopEncounterWorld.RECIPE_KEY, recipe().encode());
        shared.entities.add(scene.entity("unexpected-generated", shared.api, Map.of(), false));
        JSONObject out = CoopAgentCommands.encounters(new JSONObject(), context(scene.sector, null));
        assertEquals(1, out.getInt("legacyCount")); assertEquals(1, out.getInt("candidateCount"));
        assertTrue(out.getJSONObject("encounters").getJSONObject("instance").getJSONObject("untrackedEntities").has("unexpected-generated"));
        NativeSystem malformed = scene.addSystem(); malformed.memory.put(CoopEncounterWorld.RECIPE_KEY, 123L);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounters(new JSONObject(), context(scene.sector, null)));
    }

    @Test void queryRejectsDuplicateInstanceIdsAndStructuredDiagnosticsPreserveExactLongs() throws Exception {
        Scene scene = new Scene();
        scene.addSystem().memory.put(CoopEncounterWorld.RECIPE_KEY, recipe().encode());
        scene.addSystem().memory.put(CoopEncounterWorld.RECIPE_KEY, recipe().encode());
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounters(new JSONObject(), context(scene.sector, null)));
        JSONObject json = (JSONObject) CoopAgentCommands.diagnosticJson(recipe());
        assertEquals(Long.toString(Long.MIN_VALUE), json.getString("effectiveSeed"));
        assertEquals("SYSTEM", json.getJSONArray("entities").getJSONObject(0).getString("role"));
        assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.diagnosticJson(new Object()));
    }

    @Test void manifestExpandsNativeGhostRecoveryRecipesIntoGameplayLeavesWithoutRuntimeIntrospection() throws Exception {
        Scene scene = new Scene(); NativeSystem system = scene.addSystem(); CoopEncounterRecord base = recipe();
        String id = "coopenc_instance_system1";
        CoopWreckRecord wreck = CoopWreckFixtures.sample(CoopEncounterRecord.wreckId(id));
        CoopEncounterRecord shared = new CoopEncounterRecord(base.instanceId(), base.effectiveSeed(), base.encounterPointId(),
                base.x(), base.y(), base.depth(), base.threatRollRan(), base.creator(), base.threatTag(), base.entities(),
                List.of(new CoopEncounterRecord.Special(CoopEncounterRecord.SpecialType.GHOST_WRECK, id,
                        new CoopEncounterRecord.Orbit("coopenc_instance_system0", 45, 300, 25), wreck, List.of())), base.overlay());
        system.memory.put(CoopEncounterWorld.RECIPE_KEY, shared.encode());
        JSONObject manifest = CoopAgentCommands.encounters(new JSONObject(), context(scene.sector, null))
                .getJSONObject("encounters").getJSONObject("instance");
        JSONObject special = manifest.getJSONObject("recipe").getJSONArray("specials").getJSONObject(0);
        assertEquals("GHOST_WRECK", special.getString("type"));
        assertEquals("coopenc_instance_system0", special.getJSONObject("orbit").getString("focusId"));
        JSONObject salvage = special.getJSONObject("wreck");
        assertEquals("123456789", salvage.getString("salvageSeed"));
        assertEquals("hound_luddic_path", salvage.getJSONObject("ship").getString("variantId"));
        assertEquals("BATTERED", salvage.getJSONObject("recovery").getJSONArray("ships").getJSONObject(0).getString("condition"));
        assertEquals(1000, salvage.getJSONArray("dropValue").getJSONObject(0).getInt("value"));
        assertFalse(salvage.getJSONObject("extraSalvage").getBoolean("present"));
        assertEquals(800, salvage.getJSONArray("detectionModifiers").getJSONObject(0).getDouble("value"));
        assertEquals("$fromGhost", salvage.getJSONArray("memory").getJSONObject(0).getString("key"));
    }

    @Test void spawnRefusesGuestAndMalformedArgumentsBeforeInvokingNativeCreation() throws Exception {
        Scene scene = new Scene(); JSONObject args = spawnArgs(); int[] calls = {0};
        CoopAgentCommands.EncounterCreation noCall = (creator, point) -> calls[0]++;
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounterspawn(args, context(scene.sector, pump(CoopConnectionRole.GUEST)), noCall));
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounterspawn(args, context(scene.sector, null), noCall));
        CoopNetPump host = pump(CoopConnectionRole.HOST);
        for (String bad : List.of("{\"x\":1,\"y\":2,\"depth\":-1}", "{\"x\":1000001,\"y\":2,\"depth\":3}",
                "{\"x\":1,\"y\":2,\"depth\":3,\"creator\":\"other\"}", "{\"x\":1,\"y\":2,\"depth\":3,\"seed\":123}",
                "{\"x\":1,\"y\":2,\"depth\":3,\"seed\":\"9223372036854775808\"}")) {
            JSONObject invalid = new JSONObject(bad);
            assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.encounterspawn(invalid, context(scene.sector, host), noCall));
        }
        args.put("threatRollRan", false);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounterspawn(args, context(scene.sector, host), noCall));
        assertEquals(0, calls[0]);
    }

    @Test void spawnRequiresOneActualNewNativeSystemAndAlwaysReleasesQaSeed() throws Exception {
        Scene scene = new Scene(); CoopNetPump host = pump(CoopConnectionRole.HOST);
        JSONObject args = spawnArgs(); args.put("seed", Long.toString(Long.MIN_VALUE));
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounterspawn(args, context(scene.sector, host), (creator, point) -> { }));
        assertFalse(CoopEncounterReplayBridge.hasQaSeed());
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.encounterspawn(args, context(scene.sector, host), (creator, point) -> {
            scene.addSystem(); scene.addSystem();
        }));
        assertFalse(CoopEncounterReplayBridge.hasQaSeed());
        assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.encounterspawn(args, context(scene.sector, host), (creator, point) -> {
            assertEquals(Long.MIN_VALUE, CoopEncounterReplayBridge.qaSeed()); throw new IllegalArgumentException("native failure");
        }));
        assertFalse(CoopEncounterReplayBridge.hasQaSeed());
    }

    @Test void spawnInvokesTheActualNativeParentCapturesExactSeedAndCountsTheCreatedSystem() throws Exception {
        Scene scene = new Scene(); Global.setSector(scene.sector);
        var picker = AbyssalRogueStellarObjectEPEC.STELLAR_OBJECT_TYPES;
        var types = new ArrayList<>(picker.getItems()); List<Float> weights = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) weights.add(picker.getWeight(i));
        picker.clear(); // Native creation reaches its first content choice; no renderer is required.
        try {
            JSONObject args = spawnArgs(); args.put("seed", Long.toString(Long.MIN_VALUE));
            JSONObject out = CoopAgentCommands.encounterspawn(args, context(scene.sector, pump(CoopConnectionRole.HOST)),
                    (creator, point) -> creator.createEncounter(null, point));
            assertTrue(out.getBoolean("qa")); assertTrue(out.getBoolean("created")); assertEquals(1, out.getInt("createdCount"));
            assertEquals(1, scene.systems.size()); assertEquals(Long.toString(Long.MIN_VALUE), out.getString("effectiveSeed"));
            assertEquals(-12345.375, out.getDouble("x")); assertEquals(23456.625, out.getDouble("y"));
            assertEquals(123456789L, out.getLong("campaignTimestamp"));
            assertFalse(CoopEncounterReplayBridge.hasQaSeed());
        } finally { picker.clear(); for (int i = 0; i < types.size(); i++) picker.add(types.get(i), weights.get(i)); }
    }

    private static JSONObject spawnArgs() throws Exception {
        return new JSONObject("{\"x\":-12345.375,\"y\":23456.625,\"depth\":3}");
    }
    private static CoopNetPump pump(CoopConnectionRole role) { return new CoopNetPump(new RecordingNetService(role), () -> 1000L); }

    private static CoopAgentCommands.Context context(SectorAPI sector, CoopNetPump pump) {
        return new CoopAgentCommands.Context() { public SectorAPI sector() { return sector; } public CoopNetPump pump() { return pump; } };
    }
    private static CoopEncounterRecord recipe() {
        return new CoopEncounterRecord("instance", Long.MIN_VALUE, "abyssal_-12_23", -12345.375f, 23456.625f, 3,
                true, CoopEncounterRecord.Creator.PARENT, false,
                List.of(new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, 0, "planet:frozen"),
                        new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.SYSTEM, 1, "custom:wreck"),
                        new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.ANCHOR, 0, "token")), List.of(),
                new CoopEncounterRecord.Overlay(List.of("coopenc_instance_system1"), List.of()));
    }

    private static final class Scene {
        final List<StarSystemAPI> systems = new ArrayList<>();
        final List<SectorEntityToken> hyperEntities = new ArrayList<>();
        final Map<String, SectorEntityToken> sectorLookup = new HashMap<>();
        final Map<String, SectorEntityToken> orbitFocus = new HashMap<>();
        final Set<String> sectorFaultIds = new HashSet<>();
        final Map<String, Object> memory = new HashMap<>();
        final LocationAPI hyper = (LocationAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LocationAPI.class}, (p, m, a) -> switch (m.getName()) {
            case "isHyperspace" -> true; case "getId" -> "hyperspace"; case "getAllEntities" -> hyperEntities;
            case "getEntityById" -> hyperEntities.stream().filter(entity -> a[0].equals(entity.getId())).findFirst().orElse(null);
            default -> defaultValue(m.getReturnType());
        });
        final CampaignClockAPI clock = (CampaignClockAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CampaignClockAPI.class}, (p, m, a) -> {
            if (m.getName().equals("getTimestamp")) return 123456789L;
            if (m.getName().contains("advance") || m.getName().startsWith("set")) throw new AssertionError("QA must not change the clock");
            return defaultValue(m.getReturnType());
        });
        final SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SectorAPI.class}, (p, m, a) -> switch (m.getName()) {
            case "getStarSystems" -> systems; case "getHyperspace" -> hyper; case "getClock" -> clock;
            case "getAllLocations" -> { List<LocationAPI> locations = new ArrayList<>(systems); locations.add(hyper); yield locations; }
            case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory);
            case "getEntityById" -> {
                if (sectorFaultIds.contains(a[0])) throw new ClassCastException("BaseLocation$LocationToken cannot be cast to BaseCampaignEntity");
                yield sectorLookup.get(a[0]);
            }
            case "createStarSystem" -> addSystem().api;
            default -> defaultValue(m.getReturnType());
        });
        NativeSystem addSystem() { NativeSystem system = new NativeSystem(); systems.add(system.api); return system; }
        SectorEntityToken entity(String id, LocationAPI location, Map<String, Object> flags, boolean planet) {
            Map<String, Object> entityMemory = new HashMap<>(flags);
            if (id.startsWith("coopenc_instance_")) {
                entityMemory.put(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, id);
                entityMemory.put(CoopEncounterWorld.ENCOUNTER_ID_KEY, "instance");
            }
            MarketConditionAPI condition = (MarketConditionAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MarketConditionAPI.class},
                    (p, m, a) -> m.getName().equals("getId") ? "cold" : defaultValue(m.getReturnType()));
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MarketAPI.class}, (p, m, a) -> switch (m.getName()) {
                case "getConditions" -> List.of(condition); case "getSurveyLevel" -> MarketAPI.SurveyLevel.FULL;
                case "getMemoryWithoutUpdate" -> ApiProxies.memory(entityMemory);
                default -> defaultValue(m.getReturnType());
            });
            return (SectorEntityToken) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{planet ? PlanetAPI.class : SectorEntityToken.class}, (p, m, a) -> switch (m.getName()) {
                case "getId" -> id; case "getLocation" -> new Vector2f(12.5f, -3); case "getRadius" -> 40f;
                case "getContainingLocation" -> location; case "getMarket" -> planet ? market : null; case "getTypeId" -> "frozen";
                case "getMemoryWithoutUpdate" -> ApiProxies.memory(entityMemory);
                case "getOrbitFocus" -> orbitFocus.get(id);
                case "getOrbit" -> orbitFocus.containsKey(id) ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{OrbitAPI.class},
                        (orbit, method, arguments) -> method.getName().equals("getFocus") ? orbitFocus.get(id) : defaultValue(method.getReturnType())) : null;
                default -> defaultValue(m.getReturnType());
            });
        }
    }

    private static final class NativeSystem {
        final Map<String, Object> memory = new HashMap<>(); final Set<String> tags = new HashSet<>();
        final List<SectorEntityToken> entities = new ArrayList<>(); final Vector2f location = new Vector2f();
        String optionalId = "instance";
        final SectorEntityToken center = (SectorEntityToken) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SectorEntityToken.class},
                (p, m, a) -> defaultValue(m.getReturnType()));
        final StarSystemAPI api = (StarSystemAPI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{StarSystemAPI.class}, (p, m, a) -> switch (m.getName()) {
            case "getId" -> "deep space"; case "getOptionalUniqueId" -> optionalId;
            case "setOptionalUniqueId" -> { optionalId = (String) a[0]; yield null; }
            case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory); case "getAllEntities" -> entities;
            case "hasTag" -> tags.contains(a[0]); case "addTag" -> { tags.add((String) a[0]); yield null; }
            case "getEntityById" -> entities.stream().filter(entity -> a[0].equals(entity.getId())).findFirst().orElse(null);
            case "getLocation" -> location; case "initNonStarCenter" -> center;
            default -> defaultValue(m.getReturnType());
        });
        NativeSystem() { tags.add(Tags.TEMPORARY_LOCATION); tags.add(Tags.SYSTEM_ABYSSAL); }
    }
}
