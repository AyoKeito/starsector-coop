package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.AbyssalLightEntityPlugin;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.campaign.CoopLightWorld;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.testing.ApiProxies;
import coop.testing.RecordingNetService;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.*;

class CoopLightAgentCommandsTest {
    private static final String ID = "light:00000000-0000-0000-0000-000000000001";

    @BeforeEach void settings() { Global.setSettings(ApiProxies.whiteSettings()); }
    @AfterEach void clear() {
        Global.setSector(null);
        coop.ambient.CoopLightBridge.clear();
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test void queryIsReadOnlyAndPreservesLongSeedAndNativeIdentity() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> memory = new HashMap<>();
        memory.put(CoopLightWorld.LIGHT_ID_KEY, ID);
        memory.put(CoopLightWorld.HOST_ENTITY_ID_KEY, "native-host");
        memory.put(MemFlags.SALVAGE_SEED, Long.MAX_VALUE);
        f.addLight("replica", memory, true);
        Map<String, Object> before = Map.copyOf(memory);
        JSONObject result = CoopAgentCommands.lights(new JSONObject(), f.context(CoopConnectionRole.HOST));
        JSONObject row = result.getJSONObject("lights").getJSONObject(ID);
        assertEquals("9223372036854775807", row.getString("salvageSeed"));
        assertEquals("native-host", row.getString("hostEntityId"));
        assertEquals(22.5, row.getDouble("remainingDays"));
        assertTrue(row.getBoolean("dweller"));
        assertEquals(123L, result.getLong("campaignTimestamp"));
        assertEquals(before, memory);
        assertTrue(f.sectorMemory.isEmpty(), "a query must not create durable light state");
    }

    @Test void legacyQueryNeverAdoptsAndReportsTruncation() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> memory = new HashMap<>();
        f.addLight("legacy-a", memory, false);
        f.addLight("legacy-b", new HashMap<>(), false);
        JSONObject result = CoopAgentCommands.lights(new JSONObject().put("limit", 1), f.context(CoopConnectionRole.HOST));
        assertEquals(2, result.getInt("candidateCount"));
        assertEquals(1, result.getInt("count"));
        assertTrue(result.getBoolean("truncated"));
        assertFalse(result.getJSONObject("lights").getJSONObject("native:legacy-a").getBoolean("managed"));
        assertTrue(memory.isEmpty());
        assertTrue(f.sectorMemory.isEmpty());
    }

    @Test void queryRejectsFractionalOrUnboundedLimit() throws Exception {
        Fixture f = new Fixture();
        for (double limit : new double[]{0, 1.5, 513}) {
            assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.lights(
                    new JSONObject().put("limit", limit), f.context(CoopConnectionRole.HOST)));
        }
    }

    @Test void queryRejectsMalformedSavedHostIdentityWithoutWriting() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> memory = new HashMap<>();
        memory.put(CoopLightWorld.LIGHT_ID_KEY, ID);
        memory.put(CoopLightWorld.HOST_ENTITY_ID_KEY, 7);
        f.addLight("replica", memory, false);
        Map<String, Object> before = Map.copyOf(memory);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.lights(
                new JSONObject(), f.context(CoopConnectionRole.HOST)));
        assertEquals(before, memory);
        assertTrue(f.sectorMemory.isEmpty());
    }

    @Test void queryReadsExistingBonusReservesWithoutCreatingAScript() throws Exception {
        Fixture f = new Fixture();
        JSONObject absent = CoopAgentCommands.lights(new JSONObject(), f.context(CoopConnectionRole.HOST))
                .getJSONObject("actorBonus");
        assertFalse(absent.getBoolean("present"));
        assertEquals(0, absent.getDouble("burnReserve"));
        assertTrue(f.playerScripts.isEmpty());
        var bonus = new com.fs.starfarer.api.impl.campaign.AbyssalLightBonus(f.player);
        bonus.setBurnBonus(1.25f);
        bonus.setSensorBonus(2.5f);
        f.playerScripts.add(bonus);
        JSONObject present = CoopAgentCommands.lights(new JSONObject(), f.context(CoopConnectionRole.HOST))
                .getJSONObject("actorBonus");
        assertTrue(present.getBoolean("present"));
        assertEquals(1.25, present.getDouble("burnReserve"));
        assertEquals(2.5, present.getDouble("sensorReserve"));
        assertEquals(List.of(bonus), f.playerScripts);
    }

    @Test void queryRejectsDuplicateNativeBonusScripts() {
        Fixture f = new Fixture();
        f.playerScripts.add(new com.fs.starfarer.api.impl.campaign.AbyssalLightBonus(f.player));
        f.playerScripts.add(new com.fs.starfarer.api.impl.campaign.AbyssalLightBonus(f.player));
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.lights(
                new JSONObject(), f.context(CoopConnectionRole.HOST)));
    }

    @Test void nativeSpawnCannotRunOnGuestOrWithInvalidInputs() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger creations = new AtomicInteger();
        CoopAgentCommands.LightCreation creation = (creator, point) -> creations.incrementAndGet();
        JSONObject normal = new JSONObject().put("x", 1).put("y", 2);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.lightspawn(normal,
                f.context(CoopConnectionRole.GUEST), creation));
        for (JSONObject bad : List.of(new JSONObject().put("x", 1).put("y", 2).put("kind", "storm"),
                new JSONObject().put("x", 1_000_001).put("y", 2),
                new JSONObject().put("x", 1).put("y", 2).put("seed", 42))) {
            assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.lightspawn(bad,
                    f.context(CoopConnectionRole.HOST), creation));
        }
        assertEquals(0, creations.get());
    }

    @Test void nativeSpawnReportsOnlyNewLightsWithoutAssigningManagedIdentity() throws Exception {
        Fixture f = new Fixture();
        f.addLight("existing", new HashMap<>(), false);
        Map<String, Object> memory = new HashMap<>();
        JSONObject result = CoopAgentCommands.lightspawn(new JSONObject().put("x", 1).put("y", 2),
                f.context(CoopConnectionRole.HOST), (creator, point) -> {
                    try { f.addLight("fresh", memory, false); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
        assertEquals(1, result.getInt("createdCount"));
        assertEquals("fresh", result.getJSONArray("engineIds").getString(0));
        assertTrue(result.getBoolean("qa"));
        assertTrue(memory.isEmpty(), "the Host authority adopts on its ordinary poll");
    }

    private static final class Fixture {
        final List<SectorEntityToken> entities = new ArrayList<>();
        final Map<String, Object> sectorMemory = new HashMap<>();
        final List<com.fs.starfarer.api.EveryFrameScript> playerScripts = new ArrayList<>();
        final CampaignFleetAPI player = proxy(CampaignFleetAPI.class, (method, args) -> switch (method) {
            case "getLocation" -> new Vector2f();
            case "getScripts" -> playerScripts;
            case "addScript", "removeScript" -> throw new AssertionError("Read-only diagnostics changed scripts");
            default -> null;
        });
        final LocationAPI hyperspace = proxy(LocationAPI.class, (method, args) -> switch (method) {
            case "getAllEntities" -> entities;
            case "isHyperspace" -> true;
            default -> null;
        });
        final CampaignClockAPI clock = proxy(CampaignClockAPI.class,
                (method, args) -> method.equals("getTimestamp") ? 123L : null);
        final SectorAPI sector = proxy(SectorAPI.class, (method, args) -> switch (method) {
            case "getHyperspace" -> hyperspace;
            case "getClock" -> clock;
            case "getMemoryWithoutUpdate" -> memory(sectorMemory);
            case "getPlayerFleet" -> player;
            default -> null;
        });
        CoopAgentCommands.Context context(CoopConnectionRole role) {
            CoopNetPump pump = new CoopNetPump(new RecordingNetService(role), () -> 1000L);
            return new CoopAgentCommands.Context() {
                public SectorAPI sector() { return sector; }
                public CoopNetPump pump() { return pump; }
            };
        }
        void addLight(String nativeId, Map<String, Object> values, boolean dweller) throws Exception {
            AbyssalLightEntityPlugin plugin = new AbyssalLightEntityPlugin();
            var params = new AbyssalLightEntityPlugin.AbyssalLightParams();
            params.color = Color.WHITE; params.durationDays = 22.5f;
            CustomCampaignEntityAPI entity = proxy(CustomCampaignEntityAPI.class, (method, args) -> switch (method) {
                case "getId" -> nativeId;
                case "getCustomPlugin" -> plugin;
                case "getContainingLocation" -> hyperspace;
                case "getMemoryWithoutUpdate" -> memory(values);
                case "getLocation" -> new Vector2f(10, 20);
                case "getRadius" -> 100f;
                case "hasTag" -> dweller && Tags.DWELLER_LIGHT.equals(args[0]);
                default -> null;
            });
            field(plugin, "params", params); field(plugin, "entity", entity);
            entities.add(entity);
        }
    }

    @FunctionalInterface private interface Call { Object invoke(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            Object value = call.invoke(m.getName(), a == null ? new Object[0] : a);
            return value != null ? value : defaultValue(m.getReturnType());
        }));
    }
    private static MemoryAPI memory(Map<String, Object> values) {
        return proxy(MemoryAPI.class, (name, args) -> switch (name) {
            case "get" -> values.get(args[0]);
            case "contains" -> values.containsKey(args[0]);
            case "set", "unset" -> throw new AssertionError("Read-only diagnostics wrote memory");
            default -> null;
        });
    }
    private static void field(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
