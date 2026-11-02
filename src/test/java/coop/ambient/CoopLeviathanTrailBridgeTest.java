package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ghosts.GBIGenerateSlipstream;
import com.fs.starfarer.api.impl.campaign.ghosts.SensorGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.LeviathanCalfGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.LeviathanGhost;
import com.fs.starfarer.api.impl.campaign.ghosts.types.NoGhost;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoopLeviathanTrailBridgeTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;

    @BeforeEach void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        Global.setSettings(ApiProxies.whiteSettings());
        CoopSlipstreamFadeBridge.clear();
    }

    @AfterEach void cleanup() {
        CoopSlipstreamFadeBridge.clear();
        Global.setSettings(previousSettings);
        Global.setSector(previousSector);
    }

    @Test void nativeProducerMarksOnlyExactLeviathanAndCalfTrails() throws Exception {
        for (Class<? extends SensorGhost> kind : List.of(LeviathanGhost.class, LeviathanCalfGhost.class)) {
            Scene scene = new Scene(new SlipstreamTerrainPlugin2());
            generator(new GBIGenerateSlipstream(1, 2, 10, 30, 20, 10), ghost(kind), scene.plugin)
                    .coopMarkTrail();
            assertTrue(CoopSlipstreamFadeBridge.isNativeTrail(scene.plugin));
            assertEquals(Boolean.TRUE, scene.memory.get(CoopSlipstreamFadeBridge.TRAIL_MARKER));
            assertFalse(CoopSlipstreamFadeBridge.holdsNativeFade(scene.plugin),
                    "the Host's native producer and faders retain control");
        }
        Scene unrelated = new Scene(new SlipstreamTerrainPlugin2());
        generator(new GBIGenerateSlipstream(1, 2, 10, 30, 20, 10), ghost(NoGhost.class), unrelated.plugin)
                .coopMarkTrail();
        assertFalse(CoopSlipstreamFadeBridge.isNativeTrail(unrelated.plugin));
        generator(new CustomGenerator(), ghost(LeviathanGhost.class), unrelated.plugin).coopMarkTrail();
        assertFalse(CoopSlipstreamFadeBridge.isNativeTrail(unrelated.plugin));

        Scene custom = new Scene(new CustomTerrain());
        generator(new GBIGenerateSlipstream(1, 2, 10, 30, 20, 10), ghost(LeviathanGhost.class), custom.plugin)
                .coopMarkTrail();
        assertFalse(CoopSlipstreamFadeBridge.isNativeTrail(custom.plugin));
        custom.memory.put(CoopSlipstreamFadeBridge.TRAIL_MARKER, true);
        assertFalse(CoopSlipstreamFadeBridge.isNativeTrail(custom.plugin), "markers do not adopt custom plugins");
    }

    @Test void deserializedNativeProducerReidentifiesItsTailBeforeGuestCleanup() throws Exception {
        Scene scene = new Scene(new SlipstreamTerrainPlugin2());
        GBIGenerateSlipstream producer = generator(new GBIGenerateSlipstream(1, 2, 10, 30, 20, 10),
                ghost(LeviathanGhost.class), scene.plugin);
        scene.producerScripts.add(producer);

        CoopSlipstreamFadeBridge.markExistingNativeTrails(scene.sector);

        assertTrue(CoopSlipstreamFadeBridge.isNativeTrail(scene.plugin));
        // The producer's ghost needs no live entity: its native remaining tail is still shared.
        assertEquals(1, scene.producerScripts.size());
    }

    @Test void delayedGeometryCacheIsBoundedByPublicationAgeAndPluginIdentity() {
        Scene scene = new Scene(new SlipstreamTerrainPlugin2());
        scene.memory.put(CoopSlipstreamFadeBridge.TRAIL_MARKER, true);
        SlipstreamTerrainPlugin2.SlipstreamSegment segment = new SlipstreamTerrainPlugin2.SlipstreamSegment();
        segment.loc = new Vector2f(10, 20);
        segment.width = 200;
        segment.bMult = 1;
        segment.fader.forceIn();
        scene.plugin.getSegments().add(segment);
        CoopSlipstreamFadeBridge.rememberTrailCheckpoint(scene.plugin, scene.timestamp);
        float oldRadius = 100 + scene.plugin.getParams().edgeWidth + 100;
        segment.width = 600;
        assertTrue(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, oldRadius));
        assertFalse(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, oldRadius + 3));
        for (int i = 1; i <= 8; i++) {
            scene.timestamp++;
            segment.width = 600 + i * 20;
            CoopSlipstreamFadeBridge.rememberTrailCheckpoint(scene.plugin, scene.timestamp);
        }
        assertFalse(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, oldRadius));
        float latestRadius = segment.width * .5f + scene.plugin.getParams().edgeWidth + 100;
        assertTrue(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, latestRadius));
        scene.timestamp += 2_001; // Fixture clock maps two native seconds to 2,000 campaign ms.
        assertFalse(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, latestRadius));
        CoopSlipstreamFadeBridge.rememberTrailCheckpoint(scene.plugin, scene.timestamp);
        scene.alive = false;
        assertFalse(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, latestRadius));
        scene.alive = true;
        CoopSlipstreamFadeBridge.unregister(scene.plugin);
        assertFalse(CoopSlipstreamFadeBridge.matchesPublishedTrailClear(scene.plugin, 10, 20, latestRadius));
    }

    @Test void nativeGeneratorKeepsItsOriginalSaveFields() {
        assertEquals("0.98a-RC8/leviathan-trail-1", GBIGenerateSlipstream.coopTrailHookVersion());
        Set<String> fields = new HashSet<>();
        for (Field field : GBIGenerateSlipstream.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) fields.add(field.getName());
        }
        assertEquals(Set.of("addedScript", "minWidth", "maxWidth", "burnLevel", "ghost", "behavior",
                "widenRate", "maxSegments", "duration", "slipstream", "plugin", "prev"), fields);
    }

    private static SensorGhost ghost(Class<? extends SensorGhost> type) {
        // Model a native save loading its already-created source without starting graphics/assets.
        XStream stream = new XStream(new DomDriver());
        stream.alias("source", type);
        return (SensorGhost) stream.fromXML("<source/>");
    }

    private static GBIGenerateSlipstream generator(GBIGenerateSlipstream generator,
                                                  SensorGhost ghost, SlipstreamTerrainPlugin2 plugin)
            throws Exception {
        for (var value : Map.of("ghost", ghost, "plugin", plugin).entrySet()) {
            Field field = GBIGenerateSlipstream.class.getDeclaredField(value.getKey());
            field.setAccessible(true);
            field.set(generator, value.getValue());
        }
        return generator;
    }

    private static final class CustomGenerator extends GBIGenerateSlipstream {
        CustomGenerator() { super(1, 2, 10, 30, 20, 10); }
    }
    private static final class CustomTerrain extends SlipstreamTerrainPlugin2 { }

    private static final class Scene {
        final SlipstreamTerrainPlugin2 plugin;
        final Map<String, Object> memory = new HashMap<>();
        final List<EveryFrameScript> producerScripts = new ArrayList<>();
        final SectorAPI sector;
        long timestamp = 100;
        boolean alive = true;

        Scene(SlipstreamTerrainPlugin2 plugin) {
            this.plugin = plugin;
            MemoryAPI saved = proxy(MemoryAPI.class, (self, method, args) -> switch (method.getName()) {
                case "get" -> memory.get(args[0]);
                case "set" -> { memory.put((String) args[0], args[1]); yield null; }
                default -> throw new AssertionError(method.getName());
            });
            CampaignTerrainAPI terrain = proxy(CampaignTerrainAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getMemoryWithoutUpdate" -> saved;
                case "isAlive" -> alive;
                default -> throw new AssertionError(method.getName());
            });
            CustomCampaignEntityAPI source = proxy(CustomCampaignEntityAPI.class,
                    (self, method, args) -> switch (method.getName()) {
                        case "getScripts" -> producerScripts;
                        default -> throw new AssertionError(method.getName());
                    });
            LocationAPI hyper = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getCustomEntities" -> List.of(source);
                default -> throw new AssertionError(method.getName());
            });
            CampaignClockAPI clock = proxy(CampaignClockAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getTimestamp" -> timestamp;
                case "convertToDays" -> ((Number) args[0]).floatValue() / 86_400;
                default -> throw new AssertionError(method.getName());
            });
            sector = proxy(SectorAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getClock" -> clock;
                case "getHyperspace" -> hyper;
                default -> throw new AssertionError(method.getName());
            });
            Global.setSector(sector);
            plugin.init("slipstream", terrain, new SlipstreamTerrainPlugin2.SlipstreamParams2());
            plugin.setDynamic(true);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "equals" -> self == args[0];
                        case "hashCode" -> System.identityHashCode(self);
                        case "toString" -> type.getSimpleName() + " test proxy";
                        default -> throw new AssertionError(method.getName());
                    };
                    return handler.invoke(self, method, args);
                }));
    }
}
