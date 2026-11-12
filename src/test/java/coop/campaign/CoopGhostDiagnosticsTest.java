package coop.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ghosts.GBStayInPlace;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhostManager;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhost;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.ambient.CoopSlipstreamFadeBridge;
import coop.config.CoopOptionsRegistry;
import coop.net.CoopConnectionRole;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ProxyDefaults;
import coop.util.CoopDebug;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoopGhostDiagnosticsTest {
    private SectorAPI previousSector;
    private SettingsAPI previousSettings;
    private String previousBridge;
    private boolean previousDiagnostics;
    private Scene scene;
    private String version;

    @BeforeEach void setup() throws Exception {
        previousSector = Global.getSector();
        previousSettings = Global.getSettings();
        previousBridge = System.getProperty(CoopOptionsRegistry.DEBUG_BRIDGE);
        previousDiagnostics = diagnostics();
        diagnostics(true);
        System.setProperty(CoopOptionsRegistry.DEBUG_BRIDGE, "7802");
        version = "Starsector " + CoopPresenceRegistry.PINNED_VERSION;
        Global.setSettings(proxy(SettingsAPI.class, (name, args) -> switch (name) {
            case "getVersionString" -> version;
            case "getColor" -> Color.WHITE;
            default -> null;
        }));
        CoopSlipstreamFadeBridge.clear();
        scene = new Scene();
        Global.setSector(scene.sector);
    }

    @AfterEach void cleanup() throws Exception {
        CoopSlipstreamFadeBridge.clear();
        Global.setSector(previousSector);
        Global.setSettings(previousSettings);
        diagnostics(previousDiagnostics);
        if (previousBridge == null) System.clearProperty(CoopOptionsRegistry.DEBUG_BRIDGE);
        else System.setProperty(CoopOptionsRegistry.DEBUG_BRIDGE, previousBridge);
    }

    @Test void captureNeverPopulatesNativeLazyCacheOrChangesRegisteredScripts() {
        SensorGhostManager nativeManager = new SensorGhostManager();
        scene.persistent.add(nativeManager);
        List<EveryFrameScript> before = List.copyOf(scene.persistent);

        Map<String, Object> first = CoopGhostDiagnostics.capture(scene.sector);
        Map<String, Object> second = CoopGhostDiagnostics.capture(scene.sector);

        assertEquals(first, second);
        assertNull(first.get("cacheType"));
        assertNull(first.get("cacheIdentity"));
        assertEquals(false, first.get("ownedCachedHandle"));
        assertEquals(before, scene.persistent);
        assertTrue(scene.transientScripts.isEmpty());
        assertTrue(scene.values.isEmpty());
        assertEquals(0, scene.memoryWrites);
        Map<String, Object> row = rows(first, "managers").get(0);
        assertEquals(SensorGhostManager.class.getName(), row.get("type"));
        assertEquals(true, row.get("persistent"));
        assertEquals(false, row.get("transient"));
        assertEquals(false, row.get("cached"));
    }

    @Test void ownedCachedManagerIsDeduplicatedAndGhostBehaviorReportsAreReadOnly() {
        CoopSensorGhostManager cosmetic = CoopSensorGhostManager.install(scene.sector, true);
        NoGhost ghost = new NoGhost(cosmetic);
        ghost.addBehavior(new GBStayInPlace(1f));
        cosmetic.getGhosts().add(ghost);
        SensorGhostManager lateNative = new SensorGhostManager();
        scene.persistent.add(lateNative);
        int writes = scene.memoryWrites;

        Map<String, Object> result = CoopGhostDiagnostics.capture(scene.sector);

        assertEquals(2, result.get("managerCount"));
        assertEquals(CoopSensorGhostManager.class.getName(), result.get("cacheType"));
        assertEquals(true, result.get("ownedCachedHandle"));
        Map<String, Object> owned = rows(result, "managers").stream()
                .filter(row -> Boolean.TRUE.equals(row.get("cached"))).findFirst().orElseThrow();
        assertEquals(result.get("cacheIdentity"), owned.get("identity"));
        assertEquals(false, owned.get("persistent"));
        assertEquals(true, owned.get("transient"));
        Map<String, Object> observedGhost = rows(owned, "ghosts").get(0);
        assertEquals(NoGhost.class.getName(), observedGhost.get("type"));
        assertEquals(true, observedGhost.get("cosmeticAllowed"));
        assertEquals(false, observedGhost.get("creationFailed"));
        assertEquals(List.of(GBStayInPlace.class.getName()), observedGhost.get("behaviors"));
        assertNull(observedGhost.get("entity"));
        assertSame(cosmetic, scene.values.get("$ghostManager"));
        assertEquals(writes, scene.memoryWrites);
        assertEquals(List.of(ghost), cosmetic.getGhosts());
    }

    @Test void detachedCachedManagerRemainsVisibleWithoutBeingReregistered() {
        SensorGhostManager detached = new SensorGhostManager();
        scene.values.put("$ghostManager", detached);

        Map<String, Object> result = CoopGhostDiagnostics.capture(scene.sector);

        assertEquals(1, result.get("managerCount"));
        Map<String, Object> row = rows(result, "managers").get(0);
        assertEquals(true, row.get("cached"));
        assertEquals(false, row.get("persistent"));
        assertEquals(false, row.get("transient"));
        assertTrue(scene.persistent.isEmpty());
        assertTrue(scene.transientScripts.isEmpty());
        assertSame(detached, scene.values.get("$ghostManager"));
        assertEquals(0, scene.memoryWrites);
    }

    @Test void trailProbeKeepsObjectIdentityDiscoveryAndNativeClassificationVisible() {
        Trail nativeTrail = scene.trail(new SlipstreamTerrainPlugin2(), "native");
        nativeTrail.values.put(CoopSlipstreamFadeBridge.TRAIL_MARKER, true);
        nativeTrail.values.put("$coopStreamId", "host-route");
        nativeTrail.values.put("$coopStreamKind", "TRAIL");
        SlipstreamTerrainPlugin2.SlipstreamSegment segment = segment(10f, -20f, 300f, true);
        nativeTrail.plugin.getSegments().add(segment);
        CoopSlipstreamFadeBridge.registerTrailReplica(nativeTrail.plugin);
        Trail custom = scene.trail(new CustomTerrain(), "custom");
        custom.values.put(CoopSlipstreamFadeBridge.TRAIL_MARKER, true);

        Map<String, Object> first = CoopGhostDiagnostics.capture(scene.sector);
        Map<String, Object> oldTrail = rows(first, "trails").get(0);
        Map<String, Object> oldPoint = rows(oldTrail, "segments").get(0);
        segment.width = 330f;
        Map<String, Object> next = CoopGhostDiagnostics.capture(scene.sector);
        Map<String, Object> newTrail = rows(next, "trails").get(0);
        Map<String, Object> newPoint = rows(newTrail, "segments").get(0);

        assertEquals(oldTrail.get("identity"), newTrail.get("identity"));
        assertEquals(oldTrail.get("pluginIdentity"), newTrail.get("pluginIdentity"));
        assertEquals(oldPoint.get("identity"), newPoint.get("identity"));
        assertEquals(300f, oldPoint.get("width"));
        assertEquals(330f, newPoint.get("width"));
        assertEquals(true, newPoint.get("discovered"));
        assertEquals(10f, newPoint.get("x"));
        assertEquals(-20f, newPoint.get("y"));
        assertEquals(true, newTrail.get("nativeTrail"));
        assertEquals(true, newTrail.get("holdsNativeFade"));
        assertEquals("host-route", newTrail.get("sharedId"));
        assertEquals(false, rows(next, "trails").get(1).get("nativeTrail"));
        assertSame(segment, nativeTrail.plugin.getSegments().get(0));
        assertTrue(segment.discovered);
    }

    @Test void managerGhostAndEntityCapsReportTruncationWithoutChangingSources() {
        for (int i = 0; i < 17; i++) scene.persistent.add(new SensorGhostManager());
        SensorGhostManager first = (SensorGhostManager) scene.persistent.get(0);
        for (int i = 0; i < 257; i++) first.getGhosts().add(new NoGhost(first));
        for (int i = 0; i < 513; i++) scene.entity("entity-" + i);

        Map<String, Object> result = CoopGhostDiagnostics.capture(scene.sector);

        assertEquals(17, result.get("managerCount"));
        assertEquals(true, result.get("managersTruncated"));
        assertEquals(16, rows(result, "managers").size());
        Map<String, Object> manager = rows(result, "managers").get(0);
        assertEquals(257, manager.get("ghostCount"));
        assertEquals(true, manager.get("ghostsTruncated"));
        assertEquals(256, rows(manager, "ghosts").size());
        assertEquals(513, result.get("sensorEntityCount"));
        assertEquals(true, result.get("sensorEntitiesTruncated"));
        assertEquals(512, rows(result, "sensorEntities").size());
        assertEquals(17, scene.persistent.size());
        assertEquals(257, first.getGhosts().size());
        assertEquals(513, scene.entities.size());
    }

    @Test void nativeTrailAndGlobalSegmentCapsKeepReportedCountsAndSourcesIntact() {
        Trail first = scene.trail(new SlipstreamTerrainPlugin2(), "first");
        for (int i = 0; i < 4097; i++) first.plugin.getSegments().add(segment(i, 0, 200, false));
        for (int i = 1; i < 65; i++) {
            Trail next = scene.trail(new SlipstreamTerrainPlugin2(), "trail-" + i);
            next.plugin.getSegments().add(segment(i, 1, 100, false));
        }

        Map<String, Object> result = CoopGhostDiagnostics.capture(scene.sector);

        assertEquals(65, result.get("dynamicTerrainCount"));
        assertEquals(true, result.get("trailsTruncated"));
        List<Map<String, Object>> trails = rows(result, "trails");
        assertEquals(64, trails.size());
        assertEquals(4097, trails.get(0).get("segmentCount"));
        assertEquals(4096, rows(trails.get(0), "segments").size());
        assertEquals(true, trails.get(0).get("segmentsTruncated"));
        assertEquals(1, trails.get(1).get("segmentCount"));
        assertTrue(rows(trails.get(1), "segments").isEmpty());
        assertEquals(true, trails.get(1).get("segmentsTruncated"));
        assertEquals(4097, first.plugin.getSegments().size());
        assertEquals(65, scene.terrains.size());
    }

    @Test void setupRequiresDiagnosticsConfiguredBridgeAndActiveSessionBeforeNativeCalls() throws Exception {
        CoopSensorGhostManager manager = CoopSensorGhostManager.install(scene.sector, true);
        diagnostics(false);
        refused(manager, "diagnostics");
        diagnostics(true);
        for (String bridge : List.of("0", "-1", "65536", "bad")) {
            System.setProperty(CoopOptionsRegistry.DEBUG_BRIDGE, bridge);
            refused(manager, "configured bridge");
        }
        System.clearProperty(CoopOptionsRegistry.DEBUG_BRIDGE);
        refused(manager, "configured bridge");
        System.setProperty(CoopOptionsRegistry.DEBUG_BRIDGE, "7802");
        IllegalStateException inactive = assertThrows(IllegalStateException.class,
                () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.GUEST, false, "no_ghost"));
        assertTrue(inactive.getMessage().contains("active gameplay session"));
        assertTrue(manager.getGhosts().isEmpty());
    }

    @Test void setupRequiresCurrentPausedPinnedHyperspaceRealPlayerBeforeNativeCalls() {
        CoopSensorGhostManager manager = CoopSensorGhostManager.install(scene.sector, true);
        scene.paused = false;
        refused(manager, "current paused campaign");
        scene.paused = true;
        version = "Starsector 0.99a";
        refused(manager, "pinned Starsector");
        version = "Starsector " + CoopPresenceRegistry.PINNED_VERSION;
        scene.inHyperspace = false;
        refused(manager, "real player in hyperspace");
        scene.inHyperspace = true;
        scene.fleetInHyperspace = false;
        refused(manager, "real player in hyperspace");
        scene.fleetInHyperspace = true;
        scene.playerPresent = false;
        refused(manager, "real player in hyperspace");
        scene.playerPresent = true;
        Global.setSector(new Scene().sector);
        refused(manager, "current paused campaign");
        Global.setSector(scene.sector);
        assertThrows(IllegalStateException.class,
                () -> CoopGhostDiagnostics.spawn(null, CoopConnectionRole.GUEST, true, "no_ghost"));
    }

    @Test void setupRejectsWrongRolesUnknownKindsAndStaleManagerOwnership() {
        CoopSensorGhostManager manager = CoopSensorGhostManager.install(scene.sector, true);
        for (CoopConnectionRole role : List.of(CoopConnectionRole.HOST, CoopConnectionRole.NONE)) {
            assertThrows(IllegalStateException.class,
                    () -> CoopGhostDiagnostics.spawn(scene.sector, role, true, "no_ghost"));
        }
        for (String kind : List.of("leviathan", "calf")) {
            assertThrows(IllegalStateException.class,
                    () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.GUEST, true, kind));
        }
        for (String kind : List.of("stormtrickster", "LeviathanGhost", "java.lang.Runtime", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.GUEST, true, kind));
        }
        scene.transientScripts.clear();
        assertThrows(IllegalStateException.class,
                () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.GUEST, true, "no_ghost"));
        assertThrows(IllegalStateException.class,
                () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.HOST, true, "leviathan"));
        scene.persistent.addAll(List.of(new SensorGhostManager(), new SensorGhostManager()));
        assertThrows(IllegalStateException.class,
                () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.HOST, true, "calf"));
        assertTrue(manager.getGhosts().isEmpty());
        assertTrue(scene.entities.isEmpty());
    }

    @Test void nativeNoGhostSetupUsesRegisteredCosmeticManagerAndPreservesItsIdentity() {
        CoopSensorGhostManager manager = CoopSensorGhostManager.install(scene.sector, true);
        Object cacheBefore = scene.values.get("$ghostManager");

        Map<String, Object> result = CoopGhostDiagnostics.spawn(
                scene.sector, CoopConnectionRole.GUEST, true, "no_ghost");

        assertEquals("no_ghost", result.get("kind"));
        assertEquals(1, result.get("created"));
        assertEquals(false, result.get("refusedByNativeCreator"));
        assertEquals(CoopSensorGhostManager.class.getName(), result.get("managerType"));
        assertEquals(NoGhost.class, manager.getGhosts().get(0).getClass());
        assertSame(cacheBefore, scene.values.get("$ghostManager"));
        assertEquals(List.of(manager), scene.transientScripts);
        assertTrue(scene.persistent.isEmpty());
        assertTrue(scene.entities.isEmpty());
        Map<String, Object> observation = CoopGhostDiagnostics.capture(scene.sector);
        assertEquals(observation.get("cacheIdentity"), result.get("managerIdentity"));
        assertEquals(rows(rows(observation, "managers").get(0), "ghosts"), rows(result, "ghosts"));
    }

    private void refused(CoopSensorGhostManager manager, String reason) {
        int writes = scene.memoryWrites;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CoopGhostDiagnostics.spawn(scene.sector, CoopConnectionRole.GUEST, true, "no_ghost"));
        assertTrue(error.getMessage().contains(reason), error.getMessage());
        assertEquals(writes, scene.memoryWrites);
        assertTrue(manager.getGhosts().isEmpty());
        assertTrue(scene.entities.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> row, String key) {
        return (List<Map<String, Object>>) row.get(key);
    }

    private static SlipstreamTerrainPlugin2.SlipstreamSegment segment(float x, float y, float width,
                                                                      boolean discovered) {
        SlipstreamTerrainPlugin2.SlipstreamSegment segment = new SlipstreamTerrainPlugin2.SlipstreamSegment();
        segment.loc.set(x, y);
        segment.width = width;
        segment.discovered = discovered;
        segment.fader.forceIn();
        return segment;
    }

    private static Field diagnosticsField() throws Exception {
        Field field = CoopDebug.class.getDeclaredField("enabled");
        field.setAccessible(true);
        return field;
    }
    private static boolean diagnostics() throws Exception { return diagnosticsField().getBoolean(null); }
    private static void diagnostics(boolean value) throws Exception { diagnosticsField().setBoolean(null, value); }

    private interface Answers { Object answer(String name, Object[] arguments); }
    private static <T> T proxy(Class<T> type, Answers answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName() + " diagnostic test proxy";
            Object value = answers.answer(method.getName(), args);
            return value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
        }));
    }

    private static final class Scene {
        final Map<String, Object> values = new HashMap<>();
        final List<EveryFrameScript> persistent = new ArrayList<>();
        final List<EveryFrameScript> transientScripts = new ArrayList<>();
        final List<CustomCampaignEntityAPI> entities = new ArrayList<>();
        final List<CampaignTerrainAPI> terrains = new ArrayList<>();
        boolean paused = true;
        boolean inHyperspace = true;
        boolean fleetInHyperspace = true;
        boolean playerPresent = true;
        int memoryWrites;
        final MemoryAPI memory = proxy(MemoryAPI.class, (name, args) -> switch (name) {
            case "get" -> values.get(args[0]);
            case "set" -> { memoryWrites++; values.put((String) args[0], args[1]); yield null; }
            case "unset" -> { memoryWrites++; values.remove(args[0]); yield null; }
            default -> null;
        });
        final LocationAPI other = proxy(LocationAPI.class, (name, args) -> null);
        final LocationAPI hyper = proxy(LocationAPI.class, (name, args) -> switch (name) {
            case "isHyperspace" -> true;
            case "getCustomEntities" -> entities;
            case "getTerrainCopy" -> terrains;
            default -> null;
        });
        final CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (name, args) -> switch (name) {
            case "getContainingLocation" -> fleetInHyperspace ? hyper : other;
            default -> null;
        });
        final SectorAPI sector = proxy(SectorAPI.class, (name, args) -> switch (name) {
            case "isPaused" -> paused;
            case "getMemoryWithoutUpdate" -> memory;
            case "getScripts" -> persistent;
            case "getTransientScripts" -> transientScripts;
            case "getHyperspace" -> hyper;
            case "getCurrentLocation" -> inHyperspace ? hyper : other;
            case "getPlayerFleet" -> playerPresent ? fleet : null;
            case "getEntitiesWithTag" -> entities.stream().filter(entity -> entity.hasTag((String) args[0])).toList();
            case "addTransientScript" -> { transientScripts.add((EveryFrameScript) args[0]); yield null; }
            case "removeTransientScript" -> { transientScripts.remove(args[0]); yield null; }
            case "removeScript" -> { persistent.remove(args[0]); yield null; }
            default -> null;
        });

        void entity(String id) {
            entities.add(proxy(CustomCampaignEntityAPI.class, (name, args) -> switch (name) {
                case "getId" -> id;
                case "getCustomEntityType" -> Entities.SENSOR_GHOST;
                case "getLocation" -> new Vector2f(2f, 3f);
                case "getScripts" -> List.of();
                case "isAlive" -> true;
                case "hasTag" -> "coop_cosmetic_ghost".equals(args[0]);
                default -> null;
            }));
        }

        Trail trail(SlipstreamTerrainPlugin2 plugin, String id) {
            Trail result = new Trail(plugin, id);
            terrains.add(result.terrain);
            return result;
        }
    }

    private static final class Trail {
        final Map<String, Object> values = new HashMap<>();
        final SlipstreamTerrainPlugin2 plugin;
        final CampaignTerrainAPI terrain;
        Trail(SlipstreamTerrainPlugin2 plugin, String id) {
            this.plugin = plugin;
            MemoryAPI memory = proxy(MemoryAPI.class,
                    (name, args) -> name.equals("get") ? values.get(args[0]) : null);
            terrain = proxy(CampaignTerrainAPI.class, (name, args) -> switch (name) {
                case "getId" -> id;
                case "getPlugin" -> plugin;
                case "isAlive" -> true;
                case "getLocation" -> new Vector2f();
                case "getScripts" -> List.of();
                case "getMemoryWithoutUpdate" -> memory;
                default -> null;
            });
            plugin.init("slipstream", terrain, new SlipstreamTerrainPlugin2.SlipstreamParams2());
            plugin.setDynamic(true);
        }
    }
    private static final class CustomTerrain extends SlipstreamTerrainPlugin2 { }
}
