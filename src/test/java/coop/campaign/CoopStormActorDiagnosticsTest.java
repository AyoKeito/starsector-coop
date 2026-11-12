package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.MutableFleetStatsAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.DynamicStatsAPI;
import com.fs.starfarer.api.util.Misc;
import coop.presence.CoopPresenceRegistry;
import coop.storm.CoopStormBridge;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoopStormActorDiagnosticsTest {
    private SettingsAPI oldSettings;
    private SectorAPI oldSector;
    private float oldSneakBurn;

    @BeforeEach void setup() {
        oldSettings = Global.getSettings(); oldSector = Global.getSector();
        Global.setSettings(stub(SettingsAPI.class, (name, args) -> switch (name) {
            case "getColor" -> java.awt.Color.WHITE;
            case "getVersionString" -> CoopPresenceRegistry.PINNED_VERSION;
            default -> null;
        }));
        oldSneakBurn = Misc.SNEAK_BURN_MULT; Misc.SNEAK_BURN_MULT = .5f;
        CoopStormBridge.clear();
    }

    @AfterEach void cleanup() {
        CoopStormBridge.clear(); Misc.SNEAK_BURN_MULT = oldSneakBurn;
        Global.setSector(oldSector); Global.setSettings(oldSettings);
    }

    @Test void exposesNativeMutexCloudAndPreferredVersusExactCellGatesWithoutMutation() {
        Scene scene = new Scene();
        scene.ownerMemory.put(BaseTerrain.TERRAIN_LOCK_KEY, "private-stream");
        Map<String, Object> memoryBefore = Map.copyOf(scene.ownerMemory);
        Map<String, Object> first = scene.world.diagnostics("", 1);
        Map<String, Object> owner = actor(first, "owner"), peer = actor(first, "peer");
        assertEquals(true, owner.get("nativeGatesAvailable"));
        assertEquals("owner", owner.get("id"));
        assertEquals(false, owner.get("isMirror")); assertEquals(true, peer.get("isMirror"));
        assertEquals(true, owner.get("isPlayerFleet")); assertEquals(false, peer.get("isPlayerFleet"));
        assertEquals("private-stream", owner.get("terrainMutexValue"));
        assertEquals(true, owner.get("terrainPrevented"));
        assertEquals(false, owner.get("inClouds"));
        assertEquals(true, owner.get("locationInClouds"), "Native mutex can suppress effects despite a real cloud mask");
        assertEquals(true, peer.get("inClouds"));
        assertEquals(List.of(1, 2), owner.get("selectedTile"));
        assertEquals("STORM", nested(owner, "selectedCell").get("phase"));
        assertEquals(List.of(2, 2), owner.get("exactTile"));
        assertEquals("WAIT", nested(owner, "exactCell").get("phase"));
        assertEquals(12L, nested(owner, "selectedCell").get("epoch"));
        assertEquals(5, owner.get("goSlowBurn"), "Pinned compound assignment truncates the fractional dynamic bonus");
        assertEquals(false, owner.get("slowMoving")); assertEquals(true, peer.get("slowMoving"));
        assertEquals(8f, owner.get("currentBurn"));
        assertEquals(60f, owner.get("velocityX")); assertEquals(80f, owner.get("velocityY"));
        assertEquals(100f, owner.get("speed"));
        assertEquals(false, owner.get("transition")); assertEquals(true, peer.get("transition"));
        assertEquals(false, owner.get("inBattle")); assertEquals(true, peer.get("inBattle"));
        assertEquals(first, scene.world.diagnostics("", 1), "Repeated native gate queries are pure");
        assertEquals(memoryBefore, scene.ownerMemory);
        assertEquals(1, scene.mods.size(), "Reading go-slow must not lazily insert dynamic mods");
        assertEquals(3f, scene.terrain.getActiveCells()[2][2].wait);
        scene.ownerMemory.put(BaseTerrain.TERRAIN_LOCK_KEY, "weather");
        Map<String, Object> unlocked = actor(scene.world.diagnostics("", 1), "owner");
        assertEquals(false, unlocked.get("terrainPrevented"));
        assertEquals(true, unlocked.get("inClouds"));
    }

    @Test void missingPeerAndUnavailableWorldRemainExplicitRatherThanZeroFilledActors() {
        Scene scene = new Scene(); scene.includePeer = false;
        Map<String, Object> peer = actor(scene.world.diagnostics("", 1), "peer");
        assertEquals(false, peer.get("present")); assertEquals("peer_mirror_unavailable", peer.get("reason"));
        scene.terrains.clear();
        Map<String, Object> missingTerrain = scene.world.diagnostics("", 1);
        assertEquals(false, nested(missingTerrain, "actors").get("available"));
        assertEquals("native_hyperspace_terrain_unavailable", nested(missingTerrain, "actors").get("reason"));
        assertEquals(true, actor(missingTerrain, "owner").get("present"));
        assertEquals(false, actor(missingTerrain, "owner").get("nativeGatesAvailable"));
        scene.world.uninstall();
        Map<String, Object> uninstalled = scene.world.diagnostics("", 1);
        assertEquals(false, actor(uninstalled, "owner").get("present"));
        assertEquals("storm_world_not_current", actor(uninstalled, "owner").get("reason"));
    }

    @Test void cloudlessPointInsideGridDoesNotReportOutsideGrid() {
        Scene scene = new Scene(); scene.terrain.clearClouds();
        Map<String, Object> owner = actor(scene.world.diagnostics("", 1), "owner");
        assertNull(owner.get("selectedTile"));
        assertEquals("no_native_tile_selected", nested(owner, "selectedCell").get("reason"));
        assertEquals(List.of(2, 2), owner.get("exactTile"));
        assertEquals(true, nested(owner, "exactCell").get("present"));
    }

    @Test void absentDynamicGoSlowBonusUsesNativeZeroWithoutCreatingTheMissingEntry() {
        Scene scene = new Scene(); scene.mods.clear();
        Map<String, Object> owner = actor(scene.world.diagnostics("", 1), "owner");
        assertEquals(4, owner.get("goSlowBurn")); assertTrue(scene.mods.isEmpty());
    }

    private static Map<String, Object> actor(Map<String, Object> diagnostics, String actor) {
        return nested(nested(diagnostics, "actors"), actor);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }

    private static final class NativeTerrain extends HyperspaceTerrainPlugin {
        void clearClouds() { for (int[] row : tiles) java.util.Arrays.fill(row, -1); }

        NativeTerrain(LocationAPI hyperspace) {
            params = new TileParams("", 4, 4, "terrain", "deep_hyperspace", 1, 1, "Hyperspace");
            tiles = new int[4][4];
            for (int[] row : tiles) java.util.Arrays.fill(row, -1);
            tiles[1][2] = tiles[2][2] = 1;
            activeCells = new CellStateTracker[4][4];
            activeCells[1][2] = new CellStateTracker(1, 2, 0, 0);
            activeCells[1][2].state = CellState.STORM; activeCells[1][2].coopEpoch = 12;
            activeCells[2][2] = new CellStateTracker(2, 2, 3, 2);
            entity = stub(SectorEntityToken.class, (name, args) -> switch (name) {
                case "getId" -> "weather";
                case "getLocation" -> new Vector2f();
                case "getContainingLocation" -> hyperspace;
                default -> null;
            });
        }

        @Override public Map<String, Object> coopDiagnostics(String cells, int limit) {
            return new LinkedHashMap<>(Map.of("available", true));
        }
        @Override public void advance(float amount) { fail("Query advanced native terrain"); }
        @Override public void applyEffect(SectorEntityToken target, float days) { fail("Query applied a native effect"); }
    }

    private static final class Scene {
        final Map<String, Object> ownerMemory = new HashMap<>(), peerMemory = new HashMap<>();
        final Map<String, StatBonus> mods = new HashMap<>();
        final List<CampaignTerrainAPI> terrains = new ArrayList<>();
        final LocationAPI hyperspace;
        final NativeTerrain terrain;
        final CampaignFleetAPI player, peer;
        final SectorAPI sector;
        final CoopStormWorld world;
        boolean includePeer = true;

        Scene() {
            hyperspace = stub(LocationAPI.class, (name, args) -> name.equals("getTerrainCopy") ? terrains : null);
            terrain = new NativeTerrain(hyperspace);
            StatBonus bonus = new StatBonus(); bonus.modifyFlat("test", 1.7f);
            mods.put(Stats.MOVE_SLOW_SPEED_BONUS_MOD, bonus);
            player = fleet("owner", true, ownerMemory); peerMemory.put("$coopMirrorFleet", true);
            peer = fleet("peer", false, peerMemory);
            sector = stub(SectorAPI.class, (name, args) -> switch (name) {
                case "getPlayerFleet" -> player;
                case "getHyperspace" -> hyperspace;
                default -> null;
            });
            terrains.add(stub(CampaignTerrainAPI.class, (name, args) -> name.equals("getPlugin") ? terrain : null));
            Global.setSector(sector);
            world = new CoopStormWorld(() -> sector);
            world.install(this, false, () -> includePeer ? peer : null);
        }

        private CampaignFleetAPI fleet(String id, boolean own, Map<String, Object> values) {
            MemoryAPI memory = stub(MemoryAPI.class, (name, args) -> switch (name) {
                case "get", "getString" -> values.get(args[0]);
                case "contains" -> values.containsKey(args[0]);
                case "set", "unset" -> throw new AssertionError("Diagnostic wrote memory");
                default -> null;
            });
            DynamicStatsAPI dynamic = stub(DynamicStatsAPI.class, (name, args) -> switch (name) {
                case "getMods" -> mods;
                case "getMod", "getStat", "getValue" -> throw new AssertionError("Diagnostic used lazy dynamic getter");
                default -> null;
            });
            MutableFleetStatsAPI stats = stub(MutableFleetStatsAPI.class, (name, args) -> name.equals("getDynamic") ? dynamic : null);
            FleetDataAPI data = stub(FleetDataAPI.class, (name, args) -> name.equals("getMinBurnLevel") ? 8f : null);
            BattleAPI battle = own ? null : stub(BattleAPI.class, (name, args) -> null);
            return stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
                case "getId" -> id;
                case "getLocation" -> new Vector2f();
                case "getVelocity" -> new Vector2f(60, 80);
                case "getRadius" -> terrain.getTileSize() * .8f;
                case "getCurrBurnLevel" -> own ? 8f : 3f;
                case "getContainingLocation" -> hyperspace;
                case "getMemoryWithoutUpdate" -> memory;
                case "getStats" -> stats;
                case "getFleetData" -> data;
                case "getBattle" -> battle;
                case "isPlayerFleet" -> own;
                case "isInHyperspace" -> true;
                case "isInHyperspaceTransition" -> !own;
                default -> null;
            });
        }
    }

    @FunctionalInterface private interface Answers { Object get(String name, Object[] args); }
    private static <T> T stub(Class<T> type, Answers answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            Object value = answers.get(method.getName(), args == null ? new Object[0] : args);
            return value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
        }));
    }
}
