package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipCondition;
import coop.campaign.CoopWreckWorld;
import coop.campaign.CoopWreckRecord;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.testing.ApiProxies;
import coop.testing.RecordingNetService;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.*;

class CoopAgentWreckTest {
    @BeforeEach void settings() { Global.setSettings(ApiProxies.whiteSettings()); }
    @AfterEach void cleanup() {
        Global.setSector(null); Global.setSettings(null); coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test void allThreeCommandsAreRegisteredAndReportTheMissingCampaign() throws Exception {
        CoopAgentCommands commands = new CoopAgentCommands();
        for (String verb : List.of("wrecks", "wreckspawn", "wreckconsume")) {
            assertTrue(commands.verbs().contains(verb));
            JSONObject reply = new JSONObject(commands.dispatch("{\"id\":7,\"cmd\":\"" + verb + "\"}", context(null, null)));
            assertFalse(reply.getBoolean("ok"));
            assertEquals("IllegalStateException: no campaign loaded", reply.getString("error"));
        }
    }

    @Test void emptyNativeQueryReturnsAnExplicitUntruncatedEmptyTable() throws Exception {
        Scene scene = new Scene();
        JSONObject out = CoopAgentCommands.wrecks(new JSONObject(), context(scene.sector, null));
        assertEquals(123456789L, out.getLong("campaignTimestamp"));
        assertEquals(0, out.getInt("count")); assertEquals(0, out.getInt("candidateCount"));
        assertFalse(out.getBoolean("truncated")); assertEquals(0, out.getJSONObject("wrecks").length());
    }

    @Test void nativeQueryIncludesManagedAndUnmanagedWrecksWithoutAssigningAnId() throws Throwable {
        Scene scene = new Scene();
        NativeWreck managed = scene.add("native-host", "wreck:a", Long.MAX_VALUE);
        NativeWreck local = scene.add("native-guest", null, Long.MIN_VALUE);
        JSONObject out = CoopAgentCommands.wrecks(new JSONObject(), context(scene.sector, null));
        assertEquals(2, out.getInt("count")); assertEquals(2, out.getInt("candidateCount"));
        assertFalse(out.getBoolean("truncated"));
        JSONObject canonical = out.getJSONObject("wrecks").getJSONObject("wreck:a");
        assertEquals("native-host", canonical.getString("engineId")); assertTrue(canonical.getBoolean("managed"));
        assertEquals(Long.toString(Long.MAX_VALUE), canonical.getString("salvageSeed"));
        assertEquals(12.5d, canonical.getDouble("x"), .0001); assertEquals(-8d, canonical.getDouble("y"), .0001);
        assertEquals(3d, canonical.getDouble("vx"), .0001); assertEquals(-2d, canonical.getDouble("vy"), .0001);
        assertEquals(0d, canonical.getDouble("elapsedDays"), .0001); assertEquals(25d, canonical.getDouble("durationDays"), .0001);
        CoopWreckRecord recipe = CoopWreckRecord.decode(canonical.getString("recipe"));
        assertEquals("wreck:a", recipe.id()); assertEquals(Long.MAX_VALUE, recipe.salvageSeed());
        JSONObject guest = out.getJSONObject("wrecks").getJSONObject("native-guest");
        assertFalse(guest.getBoolean("managed")); assertEquals("native-guest", guest.getString("engineId"));
        assertEquals(Long.toString(Long.MIN_VALUE), guest.getString("salvageSeed"));
        assertFalse(local.memory.containsKey(CoopWreckWorld.WRECK_ID));
        assertEquals(0, local.memoryWrites); assertEquals(0, managed.memoryWrites);
    }

    @Test void nativeQueryCapsRowsButReportsAllCandidates() throws Throwable {
        Scene scene = new Scene();
        for (int i = 0; i < 101; i++) scene.add("native-" + i, "wreck:query_" + i, i);
        JSONObject out = CoopAgentCommands.wrecks(new JSONObject(), context(scene.sector, null));
        assertEquals(100, out.getInt("count")); assertEquals(101, out.getInt("candidateCount"));
        assertTrue(out.getBoolean("truncated")); assertEquals(100, out.getJSONObject("wrecks").length());
    }

    @Test void nativeQueryDoesNotTreatCustomDerelictSubclassesAsSharedBasicWrecks() throws Throwable {
        Scene scene = new Scene(); NativeWreck custom = scene.add("quest-wreck", null, 1);
        custom.plugin = new DerelictShipEntityPlugin() { };
        JSONObject out = CoopAgentCommands.wrecks(new JSONObject(), context(scene.sector, null));
        assertEquals(0, out.getInt("count")); assertEquals(0, out.getInt("candidateCount"));
    }

    @Test void nativeSpawnRequiresHostAndAValidAnchor() throws Exception {
        Scene scene = new Scene();
        JSONObject args = new JSONObject(); args.put("anchor", "guest");
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.wreckspawn(args, context(scene.sector, null)));
        CoopNetPump guest = new CoopNetPump(new RecordingNetService(CoopConnectionRole.GUEST), () -> 1000L);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.wreckspawn(args, context(scene.sector, guest)));
        CoopNetPump host = new CoopNetPump(new RecordingNetService(CoopConnectionRole.HOST), () -> 1000L);
        args.put("anchor", "third-player");
        assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.wreckspawn(args, context(scene.sector, host)));
    }

    @Test void consumeRefusesAnUnmanagedNativeIdAndRequiresACoopRole() throws Throwable {
        Scene scene = new Scene(); NativeWreck local = scene.add("native-only", null, 1);
        JSONObject args = new JSONObject(); args.put("wreckId", "native-only");
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.wreckconsume(args, context(scene.sector, null)));
        CoopNetPump guest = new CoopNetPump(new RecordingNetService(CoopConnectionRole.GUEST), () -> 1000L);
        assertThrows(IllegalArgumentException.class, () -> CoopAgentCommands.wreckconsume(args, context(scene.sector, guest)));
        assertEquals(0, local.memoryWrites);
    }

    private static CoopAgentCommands.Context context(SectorAPI sector, CoopNetPump pump) {
        return new CoopAgentCommands.Context() {
            @Override public SectorAPI sector() { return sector; }
            @Override public CoopNetPump pump() { return pump; }
        };
    }

    private static final class Scene {
        final List<SectorEntityToken> entities = new ArrayList<>();
        final LocationAPI hyperspace = (LocationAPI) Proxy.newProxyInstance(LocationAPI.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "isHyperspace" -> true; case "getAllEntities" -> entities;
                    default -> defaultValue(m.getReturnType());
                });
        final CampaignClockAPI clock = (CampaignClockAPI) Proxy.newProxyInstance(CampaignClockAPI.class.getClassLoader(),
                new Class<?>[]{CampaignClockAPI.class}, (p, m, a) -> m.getName().equals("getTimestamp") ? 123456789L : defaultValue(m.getReturnType()));
        final SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(SectorAPI.class.getClassLoader(), new Class<?>[]{SectorAPI.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getHyperspace" -> hyperspace; case "getClock" -> clock;
                    default -> defaultValue(m.getReturnType());
                });
        NativeWreck add(String engineId, String canonicalId, long seed) throws Throwable {
            NativeWreck wreck = new NativeWreck(this, engineId, canonicalId, seed); entities.add(wreck.entity); return wreck;
        }
    }

    private static final class NativeWreck {
        DerelictShipEntityPlugin plugin = new DerelictShipEntityPlugin();
        final Map<String, Object> memory = new HashMap<>(); int memoryWrites;
        final CustomCampaignEntityAPI entity;
        NativeWreck(Scene scene, String engineId, String canonicalId, long seed) throws Throwable {
            PerShipData ship = new PerShipData(null, ShipCondition.BATTERED, "ISS Native", null, .05f);
            ship.variantId = "hound_luddic_path";
            DerelictShipData data = new DerelictShipData(ship, false); data.durationDays = 25;
            MethodHandles.privateLookupIn(DerelictShipEntityPlugin.class, MethodHandles.lookup())
                    .findSetter(DerelictShipEntityPlugin.class, "data", DerelictShipData.class).invoke(plugin, data);
            memory.put(MemFlags.SALVAGE_SEED, seed);
            if (canonicalId != null) memory.put(CoopWreckWorld.WRECK_ID, canonicalId);
            MemoryAPI memoryAPI = (MemoryAPI) Proxy.newProxyInstance(MemoryAPI.class.getClassLoader(), new Class<?>[]{MemoryAPI.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "contains" -> memory.containsKey(a[0]); case "get" -> memory.get(a[0]);
                        case "getLong" -> ((Number) memory.get(a[0])).longValue();
                        case "set" -> { memoryWrites++; memory.put((String) a[0], a[1]); yield null; }
                        default -> defaultValue(m.getReturnType());
                    });
            FactionAPI faction = (FactionAPI) Proxy.newProxyInstance(FactionAPI.class.getClassLoader(), new Class<?>[]{FactionAPI.class},
                    (p, m, a) -> m.getName().equals("getId") ? "neutral" : defaultValue(m.getReturnType()));
            StatBonus range = new StatBonus();
            entity = (CustomCampaignEntityAPI) Proxy.newProxyInstance(CustomCampaignEntityAPI.class.getClassLoader(),
                    new Class<?>[]{CustomCampaignEntityAPI.class}, (p, m, a) -> switch (m.getName()) {
                        case "getId" -> engineId; case "getName" -> "Derelict Ship"; case "getCustomEntityType" -> "wreck";
                        case "getCustomPlugin" -> plugin; case "getContainingLocation" -> scene.hyperspace;
                        case "getMemoryWithoutUpdate" -> memoryAPI; case "getFaction" -> faction;
                        case "getLocation" -> new Vector2f(12.5f, -8); case "getVelocity" -> new Vector2f(3, -2);
                        case "getRadius" -> 25f; case "getSensorProfile" -> 1f; case "getDetectedRangeMod" -> range;
                        case "getTags", "getDropValue", "getDropRandom" -> List.of();
                        default -> defaultValue(m.getReturnType());
                    });
        }
    }
}
