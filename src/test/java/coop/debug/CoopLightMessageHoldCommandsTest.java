package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.testing.ApiProxies;
import coop.testing.RecordingNetService;
import coop.util.CoopDebug;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.*;

class CoopLightMessageHoldCommandsTest {
    private static final String LIGHT = "light:01234567-89ab-4def-8123-0123456789ab";
    private String oldBridge;
    private String oldDiagnostics;
    private boolean paused = true;
    private boolean active = true;
    private CoopConnectionRole role = CoopConnectionRole.HOST;
    private int arms;
    private int releases;
    private int drops;
    private List<Object> armArgs;
    private CoopAgentCommands.Context context;

    @BeforeEach void setup() {
        oldBridge = System.getProperty(CoopAgentBridge.PORT_PROPERTY);
        oldDiagnostics = System.getProperty(CoopDebug.PROPERTY);
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "30200");
        System.setProperty(CoopDebug.PROPERTY, "true");
        refresh();
        Global.setSettings(ApiProxies.whiteSettings());
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class}, (p, method, args) -> method.getName().equals("isPaused")
                        ? paused : defaultValue(method.getReturnType()));
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST) {
            @Override public CoopConnectionRole role() { return CoopLightMessageHoldCommandsTest.this.role; }
        };
        CoopNetPump pump = new CoopNetPump(service, () -> 1000L) {
            @Override public boolean gameplaySessionActiveForBridge() { return CoopLightMessageHoldCommandsTest.this.active; }
            @Override public Map<String, Object> armLightMessageHoldForBridge(String stage, String light,
                                                                            String action, int seconds) {
                arms++;
                armArgs = Arrays.asList(stage, light, action, seconds);
                return Map.of("armed", true, "held", false);
            }
            @Override public Map<String, Object> lightMessageHoldStatusForBridge() {
                return Map.of("armed", arms > releases + drops, "held", false);
            }
            @Override public Map<String, Object> releaseLightMessageHoldForBridge() {
                releases++;
                return Map.of("releasedOriginalMessage", true);
            }
            @Override public Map<String, Object> dropLightMessageHoldForBridge(String reason) {
                drops++;
                return Map.of("reason", reason);
            }
        };
        context = new CoopAgentCommands.Context() {
            public SectorAPI sector() { return sector; }
            public CoopNetPump pump() { return pump; }
        };
    }

    @AfterEach void cleanup() {
        restore(CoopAgentBridge.PORT_PROPERTY, oldBridge);
        restore(CoopDebug.PROPERTY, oldDiagnostics);
        Global.setSector(null);
        refresh();
        coop.ambient.CoopLightBridge.clear();
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test void explicitStagesAndDefaultDeadlineReachTheOwnedPumpUnchanged() throws Exception {
        JSONObject reply = command(arm("claim_reply"));
        assertTrue(reply.getBoolean("ok"), reply.toString());
        assertTrue(reply.getJSONObject("data").getBoolean("qa"));
        assertEquals(Arrays.asList("claim_reply", LIGHT, null, 30), armArgs);
        reply = command(arm("consumption_request").put("action", "DWELLER").put("seconds", 600));
        assertTrue(reply.getBoolean("ok"), reply.toString());
        assertEquals(List.of("consumption_request", LIGHT, "DWELLER", 600), armArgs);
    }

    @Test void invalidSelectorsAndDeadlinesNeverReachTheHold() throws Exception {
        for (JSONObject args : List.of(arm("invalid"), arm("claim_reply").put("lightId", "invalid"),
                arm("claim_reply").put("action", "DWELLER"), arm("consumption_request"),
                arm("consumption_request").put("action", "invalid"), arm("claim_reply").put("seconds", 0),
                arm("claim_reply").put("seconds", 601), arm("claim_reply").put("seconds", 1.5))) {
            assertFalse(command(args).getBoolean("ok"), args.toString());
        }
        assertEquals(0, arms);
    }

    @Test void armAndReleaseRequireTheConfiguredDebugBridge() throws Exception {
        System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
        assertRefusal(arm("claim_reply"), "opt-in agent bridge");
        assertRefusal(new JSONObject().put("mode", "release"), "opt-in agent bridge");
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "30200");
        System.setProperty(CoopDebug.PROPERTY, "false");
        refresh();
        assertRefusal(arm("claim_reply"), "debug diagnostics");
        assertEquals(0, arms + releases);
    }

    @Test void armAndReleaseRequireAnActivePausedHost() throws Exception {
        for (String mode : List.of("arm", "release")) {
            JSONObject args = mode.equals("arm") ? arm("claim_reply") : new JSONObject().put("mode", mode);
            role = CoopConnectionRole.GUEST;
            assertRefusal(args, "host-only");
            role = CoopConnectionRole.HOST;
            active = false;
            assertRefusal(args, "active gameplay session");
            active = true;
            paused = false;
            assertRefusal(args, "campaign paused");
            paused = true;
        }
        assertEquals(0, arms + releases);
    }

    @Test void releaseIsExplicitAndCleanupStillWorksWithDiagnosticsOff() throws Exception {
        assertTrue(command(new JSONObject().put("mode", "release")).getJSONObject("data")
                .getBoolean("releasedOriginalMessage"));
        assertEquals(1, releases);
        System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
        System.setProperty(CoopDebug.PROPERTY, "false");
        refresh();
        role = CoopConnectionRole.GUEST;
        active = false;
        paused = false;
        assertTrue(command(new JSONObject().put("mode", "status")).getBoolean("ok"));
        JSONObject dropped = command(new JSONObject().put("mode", "drop"));
        assertEquals("bridge_command", dropped.getJSONObject("data").getString("reason"));
        assertEquals(1, drops);
        assertFalse(command(new JSONObject().put("mode", "invalid")).getBoolean("ok"));
    }

    private static JSONObject arm(String stage) throws Exception {
        return new JSONObject().put("mode", "arm").put("stage", stage).put("lightId", LIGHT);
    }
    private JSONObject command(JSONObject args) throws Exception {
        return new JSONObject(new CoopAgentCommands().dispatch(new JSONObject().put("id", 17)
                .put("cmd", "lightmessagehold").put("args", args).toString(), context));
    }
    private void assertRefusal(JSONObject args, String error) throws Exception {
        JSONObject reply = command(args);
        assertFalse(reply.getBoolean("ok"));
        assertTrue(reply.getString("error").contains(error), reply.toString());
    }
    private static void refresh() { for (int i = 0; i < 300; i++) CoopDebug.pollFrame(); }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
