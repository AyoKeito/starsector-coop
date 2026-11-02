package coop.debug;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.ambient.CoopLightBridge;
import coop.campaign.CoopLightCodec;
import coop.campaign.CoopLightWorld;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.ApiProxies;
import coop.testing.RecordingNetService;
import coop.util.CoopDebug;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.*;

class CoopLightGrantFaultCommandsTest {
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000123";
    private static final String LIGHT_ID = "light:00000000-0000-0000-0000-000000000456";
    private String oldDiagnostics;
    private String oldBridge;

    @BeforeEach void setup() {
        oldDiagnostics = System.getProperty(CoopDebug.PROPERTY);
        oldBridge = System.getProperty(CoopAgentBridge.PORT_PROPERTY);
        System.setProperty(CoopDebug.PROPERTY, "true");
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "30201");
        refreshDiagnostics();
        CoopLightGrantFault.disarm("test_setup");
        Global.setSettings(ApiProxies.whiteSettings());
    }

    @AfterEach void cleanup() {
        CoopLightGrantFault.disarm("test_cleanup");
        restore(CoopDebug.PROPERTY, oldDiagnostics);
        restore(CoopAgentBridge.PORT_PROPERTY, oldBridge);
        Global.setSector(null);
        refreshDiagnostics();
        CoopLightBridge.clear();
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test void armIsExplicitAndDiagnosticsExposeItWithoutChangingSavedState() throws Exception {
        Fixture f = new Fixture();
        String before = f.saved();
        JSONObject reply = f.command("arm", REQUEST_ID);
        assertTrue(reply.getBoolean("ok"), reply.toString());
        JSONObject data = reply.getJSONObject("data");
        assertTrue(data.getBoolean("qa"));
        assertTrue(data.getBoolean("armed"));
        assertEquals(REQUEST_ID, data.getJSONObject("arm").getString("requestId"));
        assertEquals(LIGHT_ID, data.getJSONObject("arm").getString("lightId"));
        assertEquals(before, f.saved());

        JSONObject lights = CoopAgentCommands.lights(new JSONObject(), f.context);
        assertTrue(lights.getJSONObject("lightGrantFault").getBoolean("armed"),
                "read-only light diagnostics must not cancel an armed fault");
        assertTrue(f.command("status", null).getJSONObject("data").getBoolean("armed"));
        assertEquals(before, f.saved());
    }

    @Test void armingRefusesDisabledBridgeOrDiagnosticsAndDoesNotTouchTheRequest() throws Exception {
        Fixture f = new Fixture();
        String before = f.saved();
        System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
        f.assertRefusal("opt-in agent bridge");
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "30201");
        System.setProperty(CoopDebug.PROPERTY, "false");
        refreshDiagnostics();
        f.assertRefusal("debug diagnostics");
        assertEquals(before, f.saved());
    }

    @Test void armingRequiresAnActivePausedGuestSession() throws Exception {
        Fixture f = new Fixture();
        String before = f.saved();
        f.role = CoopConnectionRole.HOST;
        f.assertRefusal("guest-only");
        f.role = CoopConnectionRole.GUEST;
        f.active = false;
        f.assertRefusal("active gameplay session");
        f.active = true;
        f.paused = false;
        f.assertRefusal("while paused");
        assertEquals(before, f.saved());
    }

    @Test void malformedUnknownAndDwellerRequestsAreRefusedAndTheBridgeStillAnswers() throws Exception {
        Fixture f = new Fixture();
        for (String requestId : List.of("not-a-uuid", "00000000-0000-0000-0000-000000000999")) {
            assertFalse(f.command("arm", requestId).getBoolean("ok"));
            assertFalse(CoopLightGrantFault.status().get("armed").equals(true));
        }
        f.pending(CoopLightCodec.Action.DWELLER);
        assertFalse(f.command("arm", REQUEST_ID).getBoolean("ok"));
        assertFalse(f.command("unknown", null).getBoolean("ok"));
        assertTrue(f.command("status", null).getBoolean("ok"));
    }

    @Test void disarmingWorksAfterDiagnosticsAreDisabledAndNeverClearsAnUncertainGuard() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.command("arm", REQUEST_ID).getBoolean("ok"));
        f.memory.put(CoopLightWorld.STATE_KEY, "[" + JSONObject.quote("P" + f.request.encode())
                + "," + JSONObject.quote("U" + REQUEST_ID) + "]");
        String before = f.saved();
        System.setProperty(CoopDebug.PROPERTY, "false");
        System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
        refreshDiagnostics();
        JSONObject reply = f.command("disarm", null);
        assertTrue(reply.getBoolean("ok"), reply.toString());
        assertFalse(reply.getJSONObject("data").getBoolean("armed"));
        assertEquals(before, f.saved());
        assertTrue(f.command("disarm", null).getBoolean("ok"));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void refreshDiagnostics() {
        for (int i = 0; i < 300; i++) CoopDebug.pollFrame();
    }

    private static final class Fixture {
        final Map<String, Object> memory = new HashMap<>();
        boolean paused = true;
        boolean active = true;
        CoopConnectionRole role = CoopConnectionRole.GUEST;
        CoopLightCodec.Request request;
        final CampaignFleetAPI player = proxy(CampaignFleetAPI.class, (method, args) -> switch (method) {
            case "isAlive" -> true;
            case "getScripts" -> List.of();
            default -> null;
        });
        final CampaignClockAPI clock = proxy(CampaignClockAPI.class,
                (method, args) -> method.equals("getTimestamp") ? 123L : null);
        final MemoryAPI sectorMemory = proxy(MemoryAPI.class, (method, args) -> switch (method) {
            case "get" -> memory.get(args[0]);
            case "contains" -> memory.containsKey(args[0]);
            case "set" -> { memory.put((String) args[0], args[1]); yield null; }
            case "unset" -> { memory.remove(args[0]); yield null; }
            default -> null;
        });
        final SectorAPI sector = proxy(SectorAPI.class, (method, args) -> switch (method) {
            case "isPaused" -> paused;
            case "getPlayerFleet" -> player;
            case "getMemoryWithoutUpdate" -> sectorMemory;
            case "getClock" -> clock;
            default -> null;
        });
        final CoopNetPump pump;
        final CoopAgentCommands.Context context;

        Fixture() {
            Global.setSector(sector);
            CoopSessionState session = new CoopSessionState(() -> "guest-player");
            session.startGuest("Guest");
            session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
            session.guestAcceptHandshake("session-a");
            session.recordSeedLock(123L, "seed", "fingerprint");
            session.releaseLobby();
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST) {
                @Override public CoopConnectionRole role() { return Fixture.this.role; }
            };
            pump = new CoopNetPump(service, session, () -> 1000L) {
                @Override public boolean gameplaySessionActiveForBridge() { return Fixture.this.active; }
            };
            pump.lightWorldForBridge().installBridge(true, session.localPlayerId());
            pending(CoopLightCodec.Action.PULSE);
            context = new CoopAgentCommands.Context() {
                public SectorAPI sector() { return sector; }
                public CoopNetPump pump() { return pump; }
            };
        }

        void pending(CoopLightCodec.Action action) {
            request = new CoopLightCodec.Request(LIGHT_ID, REQUEST_ID, action);
            memory.put(CoopLightWorld.STATE_KEY, "[" + JSONObject.quote("P" + request.encode()) + "]");
        }

        String saved() { return (String) memory.get(CoopLightWorld.STATE_KEY); }

        JSONObject command(String mode, String requestId) throws Exception {
            JSONObject args = new JSONObject().put("mode", mode);
            if (requestId != null) args.put("requestId", requestId);
            String line = new JSONObject().put("id", 7).put("cmd", "lightgrantfault").put("args", args).toString();
            return new JSONObject(new CoopAgentCommands().dispatch(line, context));
        }

        void assertRefusal(String message) throws Exception {
            JSONObject reply = command("arm", REQUEST_ID);
            assertFalse(reply.getBoolean("ok"));
            assertTrue(reply.getString("error").contains(message), reply.toString());
            assertEquals(false, CoopLightGrantFault.status().get("armed"));
        }
    }

    @FunctionalInterface private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            Object result = call.invoke(m.getName(), a == null ? new Object[0] : a);
            return result == null ? defaultValue(m.getReturnType()) : result;
        }));
    }
}
