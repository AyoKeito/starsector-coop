package coop.storm;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.terrain.HyperStormBoost;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CoopStormExecutionProbeTest {
    private SectorAPI oldSector;
    private SettingsAPI oldSettings;
    private float oldMaxBurn, oldSpeedBurst, oldDuration;

    @BeforeEach void setup() {
        oldSector = Global.getSector(); oldSettings = Global.getSettings();
        Global.setSettings(stub(SettingsAPI.class, (name, args) -> switch (name) {
            case "getColor" -> java.awt.Color.WHITE;
            case "getFloat" -> 100f;
            case "getSpeedPerBurnLevel" -> 10f;
            case "getVersionString" -> CoopPresenceRegistry.PINNED_VERSION;
            default -> null;
        }));
        oldMaxBurn = HyperStormBoost.MAX_BURN;
        oldSpeedBurst = HyperStormBoost.STORM_SPEED_BURST;
        oldDuration = HyperStormBoost.DURATION_SECONDS;
        HyperStormBoost.MAX_BURN = 100f; HyperStormBoost.STORM_SPEED_BURST = 75f;
        HyperStormBoost.DURATION_SECONDS = 1.25f;
        CoopStormBridge.clear(); CoopStormBridge.configureGuest(false);
    }

    @AfterEach void cleanup() {
        CoopStormBridge.clear(); CoopStormBridge.configureGuest(false);
        HyperStormBoost.MAX_BURN = oldMaxBurn; HyperStormBoost.STORM_SPEED_BURST = oldSpeedBurst;
        HyperStormBoost.DURATION_SECONDS = oldDuration;
        Global.setSector(oldSector); Global.setSettings(oldSettings);
    }

    @Test void disabledProbeDoesNotTouchNativeFleetOrProduceEvidence() {
        Scene scene = new Scene();
        install(new Object(), false);
        CampaignFleetAPI forbidden = stub(CampaignFleetAPI.class, (name, args) -> {
            throw new AssertionError("Disabled probe touched fleet: " + name);
        });
        CoopStormExecutionProbe.observeAttempt(forbidden, 1, 2, true);
        CoopStormExecutionProbe.observeBoostInstalled(forbidden, 1, 2);
        CoopStormExecutionProbe.observeVelocityWrite(forbidden, 1, 2, 0, 0);
        CoopStormExecutionProbe.observeCrApplied(forbidden, 1, 2, "ship", .1f);
        CoopStormExecutionProbe.observeHullApplied(forbidden, 1, 2, "ship", 10);
        assertFalse((boolean) CoopStormExecutionProbe.snapshot().get("enabled"));
        assertEquals(0L, CoopStormExecutionProbe.snapshot().get("sequence"));
        assertTrue(events().isEmpty());
        assertTrue(counts().values().stream().allMatch(value -> value == 0));
    }

    @Test void observationsAreBoundedPureAndResetOnlyWithCurrentBridgeLifecycle() {
        Scene scene = new Scene(); Object firstOwner = new Object();
        install(firstOwner, true);
        for (int i = 0; i < 100; i++) {
            CoopStormExecutionProbe.observeAttempt(scene.mirror, 2, 3, false);
            CoopStormExecutionProbe.observeCrApplied(scene.player, 2, 3, "ship-" + i, .1f);
        }
        assertEquals(100L, counts().get("MIRROR.attempts"));
        assertEquals(100L, counts().get("MIRROR.blocked"));
        assertEquals(100L, counts().get("OWNER.crApplications"));
        assertEquals(64, events().size());
        assertEquals(101L, CoopStormExecutionProbe.snapshot().get("sequence"));
        Map<String, Object> snapshot = CoopStormExecutionProbe.snapshot();
        assertEquals(snapshot, CoopStormExecutionProbe.snapshot(), "Query must not advance/reset a probe");
        assertThrows(UnsupportedOperationException.class, () -> events().get(0).put("action", "changed"));
        CoopStormBridge.uninstall(new Object());
        assertEquals(snapshot, CoopStormExecutionProbe.snapshot(), "A stale owner cannot erase evidence");
        Object secondOwner = new Object(); install(secondOwner, true);
        assertTrue(events().isEmpty()); assertEquals(0L, counts().get("OWNER.crApplications"));
        CoopStormExecutionProbe.observeBoostInstalled(scene.player, 2, 3);
        CoopStormBridge.uninstall(firstOwner);
        assertEquals(1L, counts().get("OWNER.boostInstalled"));
        CoopStormBridge.uninstall(secondOwner);
        assertFalse((boolean) CoopStormExecutionProbe.snapshot().get("enabled"));
        assertEquals(0L, counts().get("OWNER.boostInstalled"));
    }

    @Test void nativeGuardRecordsActualMirrorRefusalBeforeAnyPhysicalCall() {
        Scene scene = new Scene(); install(new Object(), true);
        new NativeTerrain().strike(scene.mirror);
        assertEquals(1L, counts().get("MIRROR.attempts"));
        assertEquals(1L, counts().get("MIRROR.blocked"));
        assertEquals(0L, counts().get("MIRROR.boostInstalled"));
        assertEquals(0L, counts().get("MIRROR.velocityWrites"));
        assertEquals(0L, counts().get("MIRROR.crApplications"));
        assertEquals(0L, counts().get("MIRROR.hullApplications"));
        assertEquals(0, scene.velocityCalls);
        assertEquals("attemptBlocked", events().get(0).get("action"));
        assertEquals("mirror", events().get(0).get("fleetId"));
    }

    @Test void actualNativeBoostRecordsReturnedVelocityWriteAndRespectsItsNativeBurnCap() throws Exception {
        Scene scene = new Scene(); install(new Object(), true);
        XStream stream = new XStream(new DomDriver()); stream.alias("boost", HyperStormBoost.class);
        HyperStormBoost boost = (HyperStormBoost) stream.fromXML("<boost/>");
        set(boost, "fleet", scene.player);
        set(boost, "cell", new HyperspaceTerrainPlugin.CellStateTracker(2, 3, 0, 0));
        boost.advance(.25f);
        assertEquals(1, scene.velocityCalls);
        assertEquals(1L, counts().get("OWNER.velocityWrites"));
        Map<String, Object> event = events().get(0);
        assertEquals("velocityWrites", event.get("action"));
        assertEquals("OWNER", event.get("category"));
        assertEquals(10f, event.get("beforeX"));
        assertEquals(25f, ((Number) event.get("afterX")).floatValue(), .001f);
        assertEquals(15f, ((Number) event.get("deltaX")).floatValue(), .001f);
        assertEquals(123L, event.get("campaignTimestamp"));
        scene.burn = 100f;
        boost.advance(.1f);
        assertEquals(1, scene.velocityCalls);
        assertEquals(1L, counts().get("OWNER.velocityWrites"), "Native burn-cap refusal is not a velocity call");
    }

    @Test void probeReportsUnexpectedMirrorEffectsRatherThanHidingThemAndIgnoresUnrelatedNpcTraffic() {
        Scene scene = new Scene(); install(new Object(), true);
        CoopStormExecutionProbe.observeBoostInstalled(scene.npc, 1, 2);
        assertTrue(events().isEmpty());
        CoopStormExecutionProbe.observeBoostInstalled(scene.mirror, 1, 2);
        CoopStormExecutionProbe.observeCrApplied(scene.mirror, 1, 2, "remote-ship", .1f);
        CoopStormExecutionProbe.observeHullApplied(scene.mirror, 1, 2, "remote-ship", 42f);
        assertEquals(1L, counts().get("MIRROR.boostInstalled"));
        assertEquals(1L, counts().get("MIRROR.crApplications"));
        assertEquals(1L, counts().get("MIRROR.hullApplications"));
        assertEquals("remote-ship", events().get(2).get("memberId"));
        assertEquals(42f, events().get(2).get("amount"));
    }

    @Test void nativeBoostSavedFieldLayoutRemainsUnchanged() {
        Set<String> fields = Arrays.stream(HyperStormBoost.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())).map(Field::getName).collect(Collectors.toSet());
        assertEquals(Set.of("fleet", "elapsed", "angle", "cell"), fields);
    }

    private static void install(Object owner, boolean enabled) {
        CoopStormBridge.install(owner, false, new CoopStormBridge.Handler() {
            public CampaignFleetAPI remoteFleet() { return null; }
            public float remoteExtraRadius() { return 0; }
            public float remoteTimeScale() { return 1; }
            public void tileOff(float x, float y, float radius, float wait) { }
            public void surgeWindow(float x, float y, float extra, float scale) { }
            public void surgeSource(float x, float y) { }
            public boolean executionDiagnosticsEnabled() { return enabled; }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> counts() {
        return (Map<String, Long>) CoopStormExecutionProbe.snapshot().get("counts");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events() {
        return (List<Map<String, Object>>) CoopStormExecutionProbe.snapshot().get("events");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static final class NativeTerrain extends HyperspaceTerrainPlugin {
        void strike(CampaignFleetAPI fleet) { applyStormStrikes(new CellStateTracker(2, 3, 0, 0), fleet, 1); }
    }

    private static final class Scene {
        final Vector2f velocity = new Vector2f(10, 0);
        float burn = 5f;
        int velocityCalls;
        final CampaignFleetAPI player = fleet("owner", false);
        final CampaignFleetAPI mirror = fleet("mirror", true);
        final CampaignFleetAPI npc = fleet("npc", false);

        Scene() {
            CampaignClockAPI clock = stub(CampaignClockAPI.class, (name, args) -> name.equals("getTimestamp") ? 123L : null);
            Global.setSector(stub(SectorAPI.class, (name, args) -> switch (name) {
                case "getPlayerFleet" -> player;
                case "getClock" -> clock;
                default -> null;
            }));
        }

        CampaignFleetAPI fleet(String id, boolean mirror) {
            Map<String, Object> memory = new HashMap<>();
            if (mirror) memory.put("$coopMirrorFleet", true);
            return stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
                case "getId" -> id;
                case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory);
                case "getVelocity" -> velocity;
                case "setVelocity" -> { velocity.set(((Number) args[0]).floatValue(), ((Number) args[1]).floatValue()); velocityCalls++; yield null; }
                case "getCurrBurnLevel" -> burn;
                case "getViews" -> List.of();
                default -> null;
            });
        }
    }

    @FunctionalInterface private interface Answers { Object get(String name, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Answers answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            Object value = answers.get(method.getName(), args == null ? new Object[0] : args);
            return value == null ? ProxyDefaults.defaultValue(method.getReturnType()) : value;
        });
    }
}
