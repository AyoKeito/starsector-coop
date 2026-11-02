package coop.debug;

import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopNetPump;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.ProxyDefaults;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopAgentClockAlignTest {
    private static final long BEFORE = -55_644_500_923_000L;
    private static final long TARGET = -55_644_499_345_400L;

    @Test
    void bothRolesCanAlignAnActivePausedSessionAndRepeatTheTimestampAsANoOp() throws Exception {
        for (CoopConnectionRole role : new CoopConnectionRole[]{CoopConnectionRole.HOST, CoopConnectionRole.GUEST}) {
            Clock clock = new Clock(BEFORE);
            CoopAgentCommands.Context context = context(clock, true, sessionPump(role));
            CoopAgentCommands commands = new CoopAgentCommands();
            String request = "{\"id\":1,\"cmd\":\"clockalign\",\"args\":{\"targetTimestamp\":" + TARGET + "}}";

            JSONObject reply = new JSONObject(commands.dispatch(request, context));
            assertTrue(reply.getBoolean("ok"), reply.toString());
            JSONObject data = reply.getJSONObject("data");
            assertEquals(role.name(), data.getString("role"));
            assertEquals(BEFORE, data.getLong("beforeTimestamp"));
            assertEquals(TARGET, data.getLong("afterTimestamp"));
            assertTrue(data.getBoolean("advanced"));
            assertEquals(TARGET, clock.getTimestamp());
            assertEquals(TARGET, clock.getCal().getTimeInMillis());

            JSONObject again = new JSONObject(commands.dispatch(request, context)).getJSONObject("data");
            assertEquals(TARGET, again.getLong("beforeTimestamp"));
            assertEquals(TARGET, again.getLong("afterTimestamp"));
            assertFalse(again.getBoolean("advanced"));
        }
    }

    @Test
    void alignmentRequiresAnActiveSessionAndAHostOrGuestRoleBeforeTouchingTheClock() throws Exception {
        Clock clock = new Clock(BEFORE);
        JSONObject args = target(TARGET);
        assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.clockalign(args, context(clock, true, null)));
        CoopNetPump inactive = new CoopNetPump(new RoleService(CoopConnectionRole.GUEST));
        assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.clockalign(args, context(clock, true, inactive)));
        assertThrows(IllegalStateException.class,
                () -> CoopAgentCommands.clockalign(args, context(clock, true, sessionPump(CoopConnectionRole.NONE))));
        assertEquals(BEFORE, clock.getTimestamp());
        assertEquals(BEFORE, clock.getCal().getTimeInMillis());
    }

    @Test
    void localUnpauseAndAnIneffectiveHostSharedPauseAreRefused() throws Exception {
        Clock clock = new Clock(BEFORE);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.clockalign(target(TARGET),
                context(clock, false, sessionPump(CoopConnectionRole.GUEST))));
        CoopNetPump host = sessionPump(CoopConnectionRole.HOST);
        host.pauseCoordinatorForBridge().setHostPauseIntent(false);
        assertThrows(IllegalStateException.class, () -> CoopAgentCommands.clockalign(target(TARGET),
                context(clock, true, host)));
        assertEquals(BEFORE, clock.getTimestamp());
    }

    @Test
    void aTargetMustBePresentAndAnExactSignedLongInsteadOfATruncatedOrDefaultValue() throws Exception {
        Clock clock = new Clock(BEFORE);
        CoopAgentCommands.Context context = context(clock, true, sessionPump(CoopConnectionRole.GUEST));
        for (String args : new String[]{"{}", "{\"targetTimestamp\":null}", "{\"targetTimestamp\":true}",
                "{\"targetTimestamp\":\"" + TARGET + "\"}", "{\"targetTimestamp\":1.5}",
                "{\"targetTimestamp\":9223372036854775808}"}) {
            JSONObject reply = new JSONObject(new CoopAgentCommands().dispatch(
                    "{\"id\":2,\"cmd\":\"clockalign\",\"args\":" + args + "}", context));
            assertFalse(reply.getBoolean("ok"), args);
            assertTrue(reply.getString("error").startsWith("IllegalArgumentException:"), reply.toString());
        }
        assertEquals(BEFORE, clock.getTimestamp());
    }

    @Test
    void aBackwardTargetIsReturnedAsABridgeErrorWithoutChangingTheClock() throws Exception {
        Clock clock = new Clock(TARGET);
        JSONObject reply = new JSONObject(new CoopAgentCommands().dispatch(
                "{\"id\":3,\"cmd\":\"clockalign\",\"args\":{\"targetTimestamp\":" + BEFORE + "}}",
                context(clock, true, sessionPump(CoopConnectionRole.GUEST))));
        assertFalse(reply.getBoolean("ok"));
        assertTrue(reply.getString("error").contains("cannot move backward"), reply.toString());
        assertEquals(TARGET, clock.getTimestamp());
    }

    private static JSONObject target(long timestamp) throws Exception {
        JSONObject args = new JSONObject();
        args.put("targetTimestamp", timestamp);
        return args;
    }

    private static CoopNetPump sessionPump(CoopConnectionRole role) {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        session.releaseLobby();
        CoopNetPump pump = new CoopNetPump(new RoleService(role), session, () -> 1000L);
        if (role == CoopConnectionRole.HOST) pump.pauseCoordinatorForBridge().setHostPauseIntent(true);
        return pump;
    }

    private static CoopAgentCommands.Context context(Clock clock, boolean paused, CoopNetPump pump) {
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getClock" -> clock;
                    case "isPaused" -> paused;
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        return new CoopAgentCommands.Context() {
            @Override public SectorAPI sector() { return sector; }
            @Override public CoopNetPump pump() { return pump; }
        };
    }

    private static final class RoleService extends CoopNetService {
        private final CoopConnectionRole role;
        RoleService(CoopConnectionRole role) { this.role = role; }
        @Override public CoopConnectionRole role() { return role; }
    }

    private static final class Clock implements CampaignClockAPI {
        private long timestamp;
        private final GregorianCalendar calendar = new GregorianCalendar();
        Clock(long timestamp) {
            this.timestamp = timestamp;
            calendar.setTimeInMillis(timestamp);
        }
        @Override public long getTimestamp() { return timestamp; }
        @Override public GregorianCalendar getCal() { return calendar; }
        @Override public int getCycle() { return 0; }
        @Override public int getMonth() { return 0; }
        @Override public int getDay() { return 0; }
        @Override public int getHour() { return 0; }
        @Override public float convertToDays(float seconds) { return 0f; }
        @Override public float convertToMonths(float seconds) { return 0f; }
        @Override public float getElapsedDaysSince(long timestamp) { return 0f; }
        @Override public String getMonthString() { return ""; }
        @Override public String getShortMonthString() { return ""; }
        @Override public float getSecondsPerDay() { return 10f; }
        @Override public CampaignClockAPI createClock(long timestamp) { return new Clock(timestamp); }
        @Override public String getDateString() { return ""; }
        @Override public float convertToSeconds(float days) { return 0f; }
        @Override public String getShortDate() { return ""; }
        @Override public String getCycleString() { return ""; }
    }
}
