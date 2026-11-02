package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.fleet.CoopEntityIds;
import coop.fleet.CoopLocations;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CoopAgentEntityIdsTest {
    @AfterEach void cleanup() { Global.setSector(null); CoopLocations.invalidate(); }

    @Test void canonicalTeleportAndEntityMemoryUseMetadataWithoutNativeCacheLookup() throws Exception {
        Scene scene = new Scene();
        SectorEntityToken planet = scene.entity("native-planet", "coopenc_instance_system0", null);
        var target = CoopAgentCommands.teleportTarget(scene.sector,
                new JSONObject("{\"entityId\":\"coopenc_instance_system0\"}"));
        assertSame(scene.system, target.location());
        assertEquals("coopenc_instance_system0", target.entityId());
        assertSame(planet, CoopAgentCommands.findEntity(scene.sector, target.entityId()));
        JSONObject memory = CoopAgentCommands.memory(new JSONObject(
                "{\"scope\":\"entity\",\"entityId\":\"coopenc_instance_system0\",\"key\":\"visited\"}"), scene.context(null));
        assertTrue(memory.getBoolean("present")); assertTrue(memory.getBoolean("value"));
    }

    @Test void surveyReadbackReportsTheCanonicalPlanetActuallyChangedByTheReplicator() throws Exception {
        Scene scene = new Scene();
        scene.entity("native-planet", "coopenc_instance_system0", null);
        Global.setSector(scene.sector);
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.HOST), () -> 1000L);

        JSONObject result = CoopAgentCommands.surveyset(new JSONObject(
                "{\"planetId\":\"coopenc_instance_system0\",\"level\":\"PRELIMINARY\"}"), scene.context(pump));

        assertEquals(MarketAPI.SurveyLevel.PRELIMINARY, scene.survey.get());
        assertEquals("PRELIMINARY", result.getString("level"));
        assertEquals("coopenc_instance_system0", result.getString("planetId"));
        assertEquals("native-planet", result.getString("engineId"));
    }

    @Test void entityRowsKeepBothSameNativeIdMembersAndCanonicalOrbitFocus() throws Exception {
        Scene scene = new Scene();
        SectorEntityToken center = scene.entity("shared-native-id", "coopenc_instance_system0", null);
        scene.entity("shared-native-id", "coopenc_instance_system1", center);

        JSONObject result = CoopAgentCommands.entities(new JSONObject("{\"system\":\"instance\"}"), scene.context(null));

        JSONArray rows = result.getJSONArray("entities"); assertEquals(2, rows.length());
        JSONObject orbiting = null;
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getString("id").endsWith("system1"))
            orbiting = rows.getJSONObject(i);
        assertNotNull(orbiting);
        assertEquals("shared-native-id", orbiting.getString("engineId"));
        assertEquals("coopenc_instance_system0", orbiting.getString("orbitFocus"));
        assertEquals("shared-native-id", orbiting.getString("orbitFocusEngineId"));
    }

    @Test void screenReportsCanonicalInteractionTargetAndPreservesNativeDiagnosticId() throws Exception {
        Scene scene = new Scene(); scene.target = scene.entity("native-planet", "coopenc_instance_system0", null);
        JSONObject result = CoopAgentCommands.screen(new JSONObject(), scene.context(null));
        assertEquals("coopenc_instance_system0", result.getString("interactionTargetId"));
        assertEquals("native-planet", result.getString("interactionTargetEngineId"));
    }

    @Test void permanentEntityFallbackStillWalksEveryNativeLocation() {
        SectorEntityToken token = proxy(SectorEntityToken.class, (method, args) -> method.equals("getId") ? "jangala" : null);
        LocationAPI first = proxy(LocationAPI.class, (method, args) -> method.equals("getAllEntities") ? List.of() : null);
        LocationAPI second = proxy(LocationAPI.class, (method, args) -> method.equals("getAllEntities") ? List.of(token) : null);
        SectorAPI sector = proxy(SectorAPI.class, (method, args) -> method.equals("getAllLocations") ? List.of(first, second) : null);
        assertSame(token, CoopAgentCommands.findEntity(sector, "jangala"));
    }

    private interface Answer { Object value(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            Object value = answer.value(method.getName(), args);
            return value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
        }));
    }

    private static final class Scene {
        final List<SectorEntityToken> entities = new ArrayList<>();
        final MemoryAPI systemMemory = proxy(MemoryAPI.class, (method, args) -> null);
        final AtomicReference<MarketAPI.SurveyLevel> survey = new AtomicReference<>(MarketAPI.SurveyLevel.NONE);
        final MarketAPI market = proxy(MarketAPI.class, (method, args) -> switch (method) {
            case "getSurveyLevel" -> survey.get();
            case "setSurveyLevel" -> { survey.set((MarketAPI.SurveyLevel) args[0]); yield null; }
            default -> null;
        });
        final StarSystemAPI system = proxy(StarSystemAPI.class, (method, args) -> switch (method) {
            case "getId" -> "deep space"; case "getOptionalUniqueId" -> "instance";
            case "getAllEntities" -> entities;
            case "getMemoryWithoutUpdate" -> systemMemory;
            case "getEntityById" -> throw new AssertionError("canonical bridge path must not use native location cache");
            default -> null;
        });
        SectorEntityToken target;
        final InteractionDialogAPI dialog = proxy(InteractionDialogAPI.class,
                (method, args) -> method.equals("getInteractionTarget") ? target : null);
        final CampaignUIAPI ui = proxy(CampaignUIAPI.class,
                (method, args) -> method.equals("getCurrentInteractionDialog") && target != null ? dialog : null);
        final SectorAPI sector = proxy(SectorAPI.class, (method, args) -> switch (method) {
            case "getAllLocations", "getStarSystems" -> List.of(system);
            case "getCampaignUI" -> ui;
            case "getEntityById" -> throw new AssertionError("canonical bridge path reached native global cache");
            default -> null;
        });

        SectorEntityToken entity(String nativeId, String key, SectorEntityToken focus) {
            Map<String, Object> values = new HashMap<>(); values.put("$coopEncId", "instance");
            values.put(CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY, key); values.put("$visited", true);
            MemoryAPI memory = proxy(MemoryAPI.class, (method, args) -> switch (method) {
                case "get" -> values.get(args[0]); case "contains" -> values.containsKey(args[0]);
                default -> null;
            });
            SectorEntityToken entity = proxy(PlanetAPI.class, (method, args) -> switch (method) {
                case "getId" -> nativeId; case "getMemoryWithoutUpdate" -> memory;
                case "getContainingLocation" -> system; case "getLocation" -> new Vector2f(100, 200);
                case "getRadius" -> 50f; case "getMarket" -> market; case "getOrbitFocus" -> focus;
                default -> null;
            });
            entities.add(entity); return entity;
        }

        CoopAgentCommands.Context context(CoopNetPump pump) {
            return new CoopAgentCommands.Context() {
                @Override public SectorAPI sector() { return sector; }
                @Override public CoopNetPump pump() { return pump; }
            };
        }
    }
}
