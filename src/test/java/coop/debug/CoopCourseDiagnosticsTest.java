package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import coop.fleet.CoopNpcFleetReplicator;
import coop.testing.ApiProxies;
import coop.util.CoopDebug;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoopCourseDiagnosticsTest {
    private SettingsAPI previousSettings;
    private SectorAPI previousSector;
    private String previousDiagnostics;
    private String previousBridge;
    private String version;

    @BeforeEach void setup() {
        previousSettings = Global.getSettings();
        previousSector = Global.getSector();
        previousDiagnostics = System.getProperty(CoopDebug.PROPERTY);
        previousBridge = System.getProperty(CoopAgentBridge.PORT_PROPERTY);
        System.setProperty(CoopDebug.PROPERTY, "true");
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "30201");
        Global.setSector(null);
        refreshDiagnostics();
        version = "Starsector 0.98a-RC8";
        Global.setSettings(proxy(SettingsAPI.class, (self, method, args) -> switch (method.getName()) {
            case "getVersionString" -> version;
            default -> throw new AssertionError(method.getName());
        }));
    }

    @AfterEach void cleanup() {
        restore(CoopDebug.PROPERTY, previousDiagnostics);
        restore(CoopAgentBridge.PORT_PROPERTY, previousBridge);
        Global.setSector(null);
        refreshDiagnostics();
        Global.setSettings(previousSettings);
        Global.setSector(previousSector);
    }

    @Test void invokesNativeAutopilotAndReportsItsActualTargets() {
        Scene scene = new Scene();
        Map<String, Object> result = CoopCourseDiagnostics.set(scene.sector, true, 1200f, -3400f, false);

        assertEquals(1, scene.tokenCalls);
        assertEquals(1, scene.courseCalls);
        assertEquals(0, scene.clearCalls);
        assertEquals(new Vector2f(1200f, -3400f), scene.tokenLocation);
        assertSame(scene.token, scene.courseTarget);
        assertSame(scene.token, scene.interactionTarget);
        assertEquals(true, result.get("courseTargetMatches"));
        assertEquals(true, result.get("interactionTargetMatches"));
        assertEquals(false, result.get("followingDirectCommand"));
        assertEquals(Map.of("x", 50f, "y", 60f), result.get("position"));
        assertEquals(1200f, ((Map<?, ?>) result.get("courseTarget")).get("x"));
        assertTrue(scene.memory.isEmpty());
    }

    @Test void clearsThroughTheNativeUiWithoutCreatingAnotherToken() {
        Scene scene = new Scene();
        scene.courseTarget = scene.token;
        scene.interactionTarget = scene.token;
        Map<String, Object> result = CoopCourseDiagnostics.set(scene.sector, true, null, null, true);

        assertEquals(1, scene.clearCalls);
        assertEquals(0, scene.tokenCalls);
        assertEquals(0, scene.courseCalls);
        assertNull(scene.courseTarget);
        assertNull(scene.interactionTarget);
        assertEquals(Map.of("present", false), result.get("courseTarget"));
        assertEquals(true, result.get("courseTargetMatches"));
        assertEquals(true, result.get("followingDirectCommand"));
    }

    @Test void refusesIncompleteNonfiniteAndOversizedCoordinatesBeforeMutation() {
        Scene scene = new Scene();
        assertThrows(IllegalArgumentException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, 1f, null, false));
        assertThrows(IllegalArgumentException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, null, 1f, false));
        assertThrows(IllegalArgumentException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, 1f, 2f, true));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1_000_001f, -1_000_001f}) {
            assertThrows(IllegalArgumentException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, invalid, 1f, false));
            assertThrows(IllegalArgumentException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, 1f, invalid, false));
        }
        scene.assertUntouched();
    }

    @Test void requiresDiagnosticsBridgePinnedVersionAndCurrentPausedSession() {
        Scene scene = new Scene();
        System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
        scene.assertRefused();
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "30201");
        System.setProperty(CoopDebug.PROPERTY, "false");
        refreshDiagnostics();
        scene.assertRefused();
        System.setProperty(CoopDebug.PROPERTY, "true");
        refreshDiagnostics();
        version = "0.99a";
        scene.assertRefused();
        version = "0.98a-RC8";
        assertThrows(IllegalStateException.class, () -> CoopCourseDiagnostics.set(scene.sector, false, 1f, 2f, false));
        scene.paused = false;
        scene.assertRefused();
        scene.paused = true;
        Global.setSector(null);
        scene.assertRefused();
        scene.assertUntouched();
    }

    @Test void refusesMirrorsWrongLocationJumpBattleAndDialogsBeforeMutation() {
        Scene scene = new Scene();
        scene.realPlayer = false;
        scene.assertRefused();
        scene.realPlayer = true;
        scene.wrongLocation = true;
        scene.assertRefused();
        scene.wrongLocation = false;
        scene.memory.put(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG, true);
        scene.assertRefused();
        scene.memory.clear();
        scene.memory.put(CoopNpcFleetReplicator.NPC_MIRROR_TAG, "host-fleet");
        scene.assertRefused();
        scene.memory.clear();
        scene.jumping = true;
        scene.assertRefused();
        scene.jumping = false;
        scene.battle = proxy(BattleAPI.class, (self, method, args) -> { throw new AssertionError(method.getName()); });
        scene.assertRefused();
        scene.battle = null;
        scene.dialog = true;
        scene.assertRefused();
        scene.assertUntouched();
    }

    @Test void nativeRefusalIsReportedWithoutFabricatingSuccessfulReadback() {
        Scene scene = new Scene();
        scene.acceptCourse = false;
        Map<String, Object> result = CoopCourseDiagnostics.set(scene.sector, true, 1f, 2f, false);
        assertEquals(1, scene.courseCalls);
        assertEquals(true, result.get("nativeReturned"));
        assertEquals(false, result.get("courseTargetMatches"));
        assertEquals(false, result.get("interactionTargetMatches"));
        assertEquals(Map.of("present", false), result.get("courseTarget"));
        assertNull(scene.courseTarget);
        assertNull(scene.interactionTarget);
    }

    @Test void missingOrMisplacedNativeTokenStopsBeforeLayingTheCourse() {
        Scene scene = new Scene();
        scene.noToken = true;
        assertThrows(IllegalStateException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, 1f, 2f, false));
        assertEquals(0, scene.courseCalls);
        scene.noToken = false;
        scene.wrongTokenLocation = true;
        assertThrows(IllegalStateException.class, () -> CoopCourseDiagnostics.set(scene.sector, true, 1f, 2f, false));
        assertEquals(0, scene.courseCalls);
    }

    private static void refreshDiagnostics() {
        for (int i = 0; i < 300; i++) CoopDebug.pollFrame();
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Scene {
        final Map<String, Object> memory = new HashMap<>();
        final Vector2f position = new Vector2f(50, 60);
        final Vector2f tokenLocation = new Vector2f();
        final LocationAPI location;
        final SectorEntityToken token;
        final CampaignFleetAPI fleet;
        final SectorAPI sector;
        SectorEntityToken courseTarget;
        SectorEntityToken interactionTarget;
        boolean paused = true;
        boolean realPlayer = true;
        boolean wrongLocation;
        boolean jumping;
        boolean dialog;
        boolean followingDirectCommand = true;
        boolean acceptCourse = true;
        boolean noToken;
        boolean wrongTokenLocation;
        BattleAPI battle;
        int tokenCalls;
        int courseCalls;
        int clearCalls;

        Scene() {
            location = proxy(LocationAPI.class, (self, method, args) -> switch (method.getName()) {
                case "createToken" -> {
                    tokenCalls++;
                    tokenLocation.set(((Number) args[0]).floatValue(), ((Number) args[1]).floatValue());
                    yield noToken ? null : token();
                }
                default -> throw new AssertionError(method.getName());
            });
            token = proxy(SectorEntityToken.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> null;
                case "getLocation" -> tokenLocation;
                case "getContainingLocation" -> wrongTokenLocation ? null : location;
                default -> throw new AssertionError(method.getName());
            });
            var fleetMemory = ApiProxies.memory(memory);
            fleet = proxy(CampaignFleetAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isPlayerFleet" -> realPlayer;
                case "getContainingLocation" -> wrongLocation ? null : location;
                case "getMemoryWithoutUpdate" -> fleetMemory;
                case "isInHyperspaceTransition" -> jumping;
                case "getBattle" -> battle;
                case "getInteractionTarget" -> interactionTarget;
                case "getLocation" -> position;
                default -> throw new AssertionError("Unexpected fleet mutation/read: " + method.getName());
            });
            CampaignUIAPI ui = proxy(CampaignUIAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isShowingDialog" -> dialog;
                case "isFollowingDirectCommand" -> followingDirectCommand;
                case "clearLaidInCourse" -> {
                    clearCalls++;
                    courseTarget = interactionTarget = null;
                    followingDirectCommand = true;
                    yield null;
                }
                default -> throw new AssertionError(method.getName());
            });
            PersistentUIDataAPI data = proxy(PersistentUIDataAPI.class, (self, method, args) -> switch (method.getName()) {
                case "getCourseTarget" -> courseTarget;
                default -> throw new AssertionError(method.getName());
            });
            sector = proxy(SectorAPI.class, (self, method, args) -> switch (method.getName()) {
                case "isPaused" -> paused;
                case "getPlayerFleet" -> fleet;
                case "getCurrentLocation" -> location;
                case "getMemoryWithoutUpdate" -> ApiProxies.memory(Map.of());
                case "getCampaignUI" -> ui;
                case "getUIData" -> data;
                case "layInCourseFor" -> {
                    courseCalls++;
                    if (acceptCourse) {
                        courseTarget = interactionTarget = (SectorEntityToken) args[0];
                        followingDirectCommand = false;
                    }
                    yield null;
                }
                default -> throw new AssertionError(method.getName());
            });
            Global.setSector(sector);
        }

        private SectorEntityToken token() { return token; }

        void assertRefused() {
            assertThrows(IllegalStateException.class, () -> CoopCourseDiagnostics.set(sector, true, 1f, 2f, false));
        }

        void assertUntouched() {
            assertEquals(0, tokenCalls);
            assertEquals(0, courseCalls);
            assertEquals(0, clearCalls);
        }
    }
}
