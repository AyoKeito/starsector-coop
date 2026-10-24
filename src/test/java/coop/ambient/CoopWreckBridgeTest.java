package coop.ambient;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.events.nearby.NearbyEventsEvent;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import coop.presence.CoopPresenceRegistry;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual pinned native classes, rather than a duplicate of their ownership rules. */
class CoopWreckBridgeTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;
    private Object previousPresenceVersion;
    private CampaignFleetAPI player;
    private final LocationAPI hyper = proxy(LocationAPI.class, Map.of("isHyperspace", true));

    @BeforeEach
    void setup() throws Exception {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        Field guard = field(CoopPresenceRegistry.class, "versionOk");
        previousPresenceVersion = guard.get(null);
        guard.set(null, null);
        CoopPresenceRegistry.clear();
        CoopWreckBridge.clear();
        version("Starsector 0.98a-RC8");
        CampaignClockAPI clock = (CampaignClockAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CampaignClockAPI.class}, (p, m, a) -> m.getName().equals("convertToDays")
                        ? a[0] : ProxyDefaults.defaultValue(m.getReturnType()));
        Global.setSector((SectorAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SectorAPI.class}, (p, m, a) -> switch (m.getName()) {
                    case "getClock" -> clock;
                    case "getPlayerFleet" -> player;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                }));
    }

    @AfterEach
    void cleanup() throws Exception {
        CoopWreckBridge.clear();
        CoopPresenceRegistry.clear();
        field(CoopPresenceRegistry.class, "versionOk").set(null, previousPresenceVersion);
        Global.setSettings(previousSettings);
        Global.setSector(previousSector);
    }

    @Test
    void roleAndCanonicalIdentityAreBothRequiredAndClearReleasesOwnership() {
        Wreck wreck = new Wreck();
        wreck.memory.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:a");
        assertFalse(CoopWreckBridge.isGuestReplica(wreck.entity));
        CoopWreckBridge.setGuestMode(true);
        assertTrue(CoopWreckBridge.isGuestReplica(wreck.entity));
        CoopWreckBridge.clear();
        assertFalse(CoopWreckBridge.isGuestReplica(wreck.entity));
    }

    @Test
    void nativeIdsEmptySuffixAndOversizedKeysNeverClaimReplicaOwnership() {
        assertFalse(CoopWreckBridge.isCanonicalId(null));
        assertFalse(CoopWreckBridge.isCanonicalId("2aa26"));
        assertFalse(CoopWreckBridge.isCanonicalId("wreck:"));
        assertFalse(CoopWreckBridge.isCanonicalId("wreck:unsafe suffix"));
        assertFalse(CoopWreckBridge.isCanonicalId("wreck:extra:colon"));
        assertFalse(CoopWreckBridge.isCanonicalId("wreck:" + "a".repeat(251)));
        assertTrue(CoopWreckBridge.isCanonicalId("wreck:" + "a".repeat(250)));
    }

    @Test
    void unsupportedVersionLeavesNativeBehaviorEnabled() {
        version("0.99a-RC1");
        CoopWreckBridge.setGuestMode(true);
        assertFalse(CoopWreckBridge.isGuestMode());
        Wreck wreck = new Wreck();
        wreck.memory.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:a");
        assertFalse(CoopWreckBridge.isGuestReplica(wreck.entity));
    }

    @Test
    void guestReplicaNativeAgeDoesNotAdvanceOrScheduleLocalExpiry() throws Exception {
        Wreck wreck = new Wreck();
        wreck.memory.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:a");
        NativeWreck plugin = wreck.plugin(25f, 26f);
        CoopWreckBridge.setGuestMode(true);
        plugin.advance(3f);
        assertEquals(26f, plugin.age());
        assertTrue(wreck.scripts.isEmpty());
        assertNull(plugin.expiring());
    }

    @Test
    void hostAndUntaggedGuestWrecksKeepTheirNativeAge() throws Exception {
        Wreck wreck = new Wreck();
        NativeWreck plugin = wreck.plugin(25f, 1f);
        plugin.advance(2f);
        assertEquals(3f, plugin.age());
        CoopWreckBridge.setGuestMode(true);
        plugin.advance(2f);
        assertEquals(5f, plugin.age());
    }

    @Test
    void guestVisibilityPreservesHostWreckUntilBothPlayersLoseSight() throws Exception {
        Wreck wreck = new Wreck();
        NativeWreck plugin = wreck.plugin(25f, 26f);
        CoopPresenceRegistry.set(fleet(true, 0f, hyper));
        wreck.guestVisibility = VisibilityLevel.COMPOSITION_DETAILS;
        plugin.advance(1f);
        assertEquals(27f, plugin.age());
        assertTrue(wreck.scripts.isEmpty());
        assertNull(plugin.expiring());

        wreck.guestVisibility = VisibilityLevel.SENSOR_CONTACT;
        plugin.advance(1f);
        assertEquals(1, wreck.scripts.size());
        assertEquals(Boolean.TRUE, plugin.expiring());
        assertTrue(wreck.tags.contains(Tags.FADING_OUT_AND_EXPIRING));
    }

    @Test
    void guestInAnotherLocationCannotPreserveAnExpiredHostWreck() throws Exception {
        Wreck wreck = new Wreck();
        NativeWreck plugin = wreck.plugin(25f, 26f);
        CoopPresenceRegistry.set(fleet(false, 0f, proxy(LocationAPI.class, Map.of())));
        wreck.guestVisibility = VisibilityLevel.COMPOSITION_AND_FACTION_DETAILS;
        plugin.advance(1f);
        assertEquals(1, wreck.scripts.size());
        assertEquals(0, wreck.guestVisibilityQueries);
    }

    @Test
    void nearbyPlayersGetOneNativeCheckAndSeparatedPlayersGetTwo() {
        NativeNearby event = new NativeNearby();
        player = fleet(true, 0f, hyper);
        CampaignFleetAPI near = fleet(true, 9999f, hyper);
        CoopPresenceRegistry.set(near);
        event.roll();
        assertEquals(List.of(player), event.anchors);
        event.anchors.clear();
        CampaignFleetAPI far = fleet(true, 10000f, hyper);
        CoopPresenceRegistry.set(far);
        event.roll();
        assertEquals(List.of(player, far), event.anchors);
    }

    @Test
    void guestStillGetsNativeCheckWhenHostIsInASystem() {
        NativeNearby event = new NativeNearby();
        player = fleet(false, 0f, proxy(LocationAPI.class, Map.of()));
        CampaignFleetAPI guest = fleet(true, 1f, hyper);
        CoopPresenceRegistry.set(guest);
        event.roll();
        assertEquals(List.of(player, guest), event.anchors);
        assertTrue(CoopWreckBridge.needsGuestSpawnCheck(false, Float.NaN));
    }

    @Test
    void guestSystemAndAbsentPresenceDoNotAddFloatingWreckChecks() {
        NativeNearby event = new NativeNearby();
        player = fleet(true, 0f, hyper);
        CoopPresenceRegistry.set(fleet(false, 20000f, proxy(LocationAPI.class, Map.of())));
        event.roll();
        assertEquals(List.of(player), event.anchors);
        event.anchors.clear();
        CoopPresenceRegistry.clear();
        event.roll();
        assertEquals(List.of(player), event.anchors);
    }

    @Test
    void guestNeverRunsFloatingWreckChecksIncludingTheExplicitNativeQaEntry() {
        NativeNearby event = new NativeNearby();
        player = fleet(true, 0f, hyper);
        CoopWreckBridge.setGuestMode(true);
        event.roll();
        assertTrue(event.anchors.isEmpty());
        assertNull(new NearbyEventsEvent().coopTrySpawnDerelictNear(player));
    }

    @Test
    void guestSuppressionLeavesTheNativeDistressIntervalRunning() {
        NativeNearby event = new NativeNearby();
        player = fleet(true, 0f, hyper);
        CoopWreckBridge.setGuestMode(true);
        event.forceIntervals();
        event.advance(0.01f);
        assertTrue(event.anchors.isEmpty());
        assertEquals(1, event.distressChecks);
    }

    @Test
    void explicitNativeQaEntryRejectsAnAnchorOutsideHyperspaceBeforeAnyRoll() {
        assertNull(new NearbyEventsEvent().coopTrySpawnDerelictNear(fleet(false, 0f, hyper)));
        assertNull(new NearbyEventsEvent().coopTrySpawnDerelictNear(null));
    }

    @Test
    void nativeForksAddNoSerializedInstanceFields() {
        assertEquals(Set.of("data", "sprite", "member", "scale", "angVel", "elapsed", "expiring"),
                instanceFields(DerelictShipEntityPlugin.class));
        assertEquals(Set.of("derelictShipInterval", "distressCallInterval", "skipForDistressCalls"),
                instanceFields(NearbyEventsEvent.class));
    }

    @Test
    void guestReplicaSkipsNativeStreamImpulseWhileOtherWrecksStayNative() {
        Wreck wreck = new Wreck();
        NativeStream stream = new NativeStream();
        wreck.memory.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:a");
        CoopWreckBridge.setGuestMode(true);
        stream.applyEffectToWreck(wreck.entity, 1f);
        assertEquals(0, stream.containsCalls);
        wreck.memory.clear();
        stream.applyEffectToWreck(wreck.entity, 1f);
        assertEquals(1, stream.containsCalls);
        wreck.memory.put(CoopWreckBridge.WRECK_ID_KEY, "wreck:a");
        CoopWreckBridge.setGuestMode(false);
        stream.applyEffectToWreck(wreck.entity, 1f);
        assertEquals(2, stream.containsCalls);
    }

    private static void version(String value) {
        Global.setSettings(proxy(SettingsAPI.class, Map.of("getVersionString", value, "getColor", Color.WHITE)));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Set<String> instanceFields(Class<?> type) {
        Set<String> result = new HashSet<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) result.add(f.getName());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> switch (m.getName()) {
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            case "toString" -> type.getSimpleName();
            default -> answers.containsKey(m.getName()) ? answers.get(m.getName())
                    : ProxyDefaults.defaultValue(m.getReturnType());
        });
    }

    private static CampaignFleetAPI fleet(boolean inHyper, float x, LocationAPI location) {
        return proxy(CampaignFleetAPI.class, Map.of("isInHyperspace", inHyper, "getLocation", new Vector2f(x, 0),
                "getContainingLocation", location));
    }

    private static final class NativeNearby extends NearbyEventsEvent {
        final List<CampaignFleetAPI> anchors = new ArrayList<>();
        int distressChecks;
        void roll() { maybeSpawnDerelictShip(); }
        void forceIntervals() {
            derelictShipInterval.forceIntervalElapsed();
            distressCallInterval.forceIntervalElapsed();
        }
        @Override protected boolean isEventStarted() { return true; }
        @Override public boolean isDone() { return false; }
        @Override protected void maybeSpawnDistressCall() { distressChecks++; }
        @Override public SectorEntityToken coopTrySpawnDerelictNear(CampaignFleetAPI anchor) {
            anchors.add(anchor);
            return null;
        }
    }

    private static final class NativeStream extends SlipstreamTerrainPlugin2 {
        int containsCalls;
        @Override public boolean containsPoint(Vector2f point, float radius) {
            containsCalls++;
            return false;
        }
    }

    private static final class NativeWreck extends DerelictShipEntityPlugin {
        NativeWreck(SectorEntityToken entity) { this.entity = entity; }
        float age() { return elapsed; }
        Boolean expiring() { return expiring; }
    }

    private final class Wreck {
        final Map<String, Object> memory = new HashMap<>();
        final Set<String> tags = new HashSet<>();
        final List<EveryFrameScript> scripts = new ArrayList<>();
        VisibilityLevel guestVisibility = VisibilityLevel.SENSOR_CONTACT;
        int guestVisibilityQueries;
        final SectorEntityToken entity = (SectorEntityToken) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SectorEntityToken.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMemoryWithoutUpdate" -> ApiProxies.memory(memory);
                    case "getContainingLocation" -> hyper;
                    case "getLocation" -> new Vector2f();
                    case "getVisibilityLevelToPlayerFleet" -> VisibilityLevel.SENSOR_CONTACT;
                    case "getVisibilityLevelTo" -> { guestVisibilityQueries++; yield guestVisibility; }
                    case "hasTag" -> tags.contains(a[0]);
                    case "addTag" -> tags.add((String) a[0]);
                    case "addScript" -> scripts.add((EveryFrameScript) a[0]);
                    case "getSensorFaderBrightness" -> 1f;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });

        NativeWreck plugin(float duration, float age) throws Exception {
            NativeWreck result = new NativeWreck(entity);
            DerelictShipEntityPlugin.DerelictShipData data =
                    new DerelictShipEntityPlugin.DerelictShipData(null, true);
            data.durationDays = duration;
            field(DerelictShipEntityPlugin.class, "data").set(result, data);
            field(DerelictShipEntityPlugin.class, "elapsed").set(result, age);
            return result;
        }
    }
}
