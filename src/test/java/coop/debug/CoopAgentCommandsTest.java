package coop.debug;

import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopNetPump;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry half of the Phase 30 agent bridge: the request/response codec and the rule that no
 * command can take the connection (or the game) down with it.
 *
 * <p>There is no sector in a unit test, so the live handlers are not exercised here — the context is
 * the seam that lets the registry be driven with fakes instead. What is under test is the contract
 * the MCP server on the other end of the socket depends on: an id echoed back, an {@code ok} flag,
 * and an error string shaped {@code "Class: message"} whichever way the command failed.
 */
class CoopAgentCommandsTest {

    /** No campaign, no pump — exactly what a handler sees at the title screen. */
    private static final CoopAgentCommands.Context EMPTY_CONTEXT = new CoopAgentCommands.Context() {
        @Override
        public SectorAPI sector() {
            return null;
        }

        @Override
        public CoopNetPump pump() {
            return null;
        }
    };

    // ---- Codec ---------------------------------------------------------------------------------

    @Test
    void aRequestRoundTripsThroughItsHandlerAndBackOutAsData() throws JSONException {
        CoopAgentCommands commands = registryOf("echo", (args, context) -> {
            JSONObject data = new JSONObject();
            data.put("seen", args.optString("what", ""));
            return data;
        });

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":17,\"cmd\":\"echo\",\"args\":{\"what\":\"hello\"}}", EMPTY_CONTEXT));

        assertEquals(17, response.getInt("id"), "the id must come back so replies can be correlated");
        assertTrue(response.getBoolean("ok"));
        assertEquals("hello", response.getJSONObject("data").getString("seen"));
    }

    @Test
    void aRequestWithoutArgsStillReachesItsHandler() throws JSONException {
        CoopAgentCommands commands = registryOf("ping", (args, context) -> {
            JSONObject data = new JSONObject();
            data.put("args", args.length());
            return data;
        });

        JSONObject response = new JSONObject(commands.dispatch("{\"id\":1,\"cmd\":\"ping\"}", EMPTY_CONTEXT));

        assertTrue(response.getBoolean("ok"));
        assertEquals(0, response.getJSONObject("data").getInt("args"),
                "a missing args object must arrive as an empty one, not as null");
    }

    @Test
    void malformedJsonIsAnErrorResponseRatherThanAThrow() throws JSONException {
        CoopAgentCommands commands = registryOf("noop", (args, context) -> new JSONObject());

        JSONObject response = new JSONObject(commands.dispatch("{not json at all", EMPTY_CONTEXT));

        assertEquals(0, response.getInt("id"), "an unparsable line has no id; 0 is the stand-in");
        assertFalse(response.getBoolean("ok"));
        assertTrue(response.getString("error").startsWith("JSONException: "),
                "error must carry the class name: " + response.getString("error"));
    }

    @Test
    void anUnknownVerbIsRefusedByName() throws JSONException {
        CoopAgentCommands commands = registryOf("noop", (args, context) -> new JSONObject());

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":4,\"cmd\":\"wiggle\"}", EMPTY_CONTEXT));

        assertEquals(4, response.getInt("id"));
        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalArgumentException: unknown command: wiggle", response.getString("error"));
    }

    @Test
    void theUiPathVerbsAreARefusalWithTheirOwnReasonRatherThanAnUnknownCommand() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        for (String verb : CoopAgentCommands.UI_PATH_VERBS) {
            JSONObject response = new JSONObject(
                    commands.dispatch("{\"id\":9,\"cmd\":\"" + verb + "\"}", EMPTY_CONTEXT));
            assertFalse(response.getBoolean("ok"), verb + " must never be implemented");
            assertTrue(response.getString("error").contains(CoopAgentCommands.UNSUPPORTED_MESSAGE),
                    verb + " must say why it is refused, not read as a typo: " + response.getString("error"));
        }
    }

    @Test
    void aVerbIsMatchedCaseInsensitivelyAndTrimmed() throws JSONException {
        CoopAgentCommands commands = registryOf("status", (args, context) -> new JSONObject());

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":2,\"cmd\":\"  STATUS \"}", EMPTY_CONTEXT));

        assertTrue(response.getBoolean("ok"));
    }

    @Test
    void aHandlerReturningNullSerializesAsAnEmptyDataObject() throws JSONException {
        CoopAgentCommands commands = registryOf("blank", (args, context) -> null);

        JSONObject response = new JSONObject(commands.dispatch("{\"id\":3,\"cmd\":\"blank\"}", EMPTY_CONTEXT));

        assertTrue(response.getBoolean("ok"));
        assertEquals(0, response.getJSONObject("data").length());
    }

    // ---- Per-command error isolation -----------------------------------------------------------

    @Test
    void aThrowingHandlerYieldsAnErrorAndTheNextCommandStillRuns() throws JSONException {
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put("boom", (args, context) -> {
            throw new IllegalStateException("engine said no");
        });
        handlers.put("fine", (args, context) -> {
            JSONObject data = new JSONObject();
            data.put("ran", true);
            return data;
        });
        CoopAgentCommands commands = new CoopAgentCommands(handlers);

        JSONObject failed = new JSONObject(commands.dispatch("{\"id\":5,\"cmd\":\"boom\"}", EMPTY_CONTEXT));
        assertEquals(5, failed.getInt("id"));
        assertFalse(failed.getBoolean("ok"));
        assertEquals("IllegalStateException: engine said no", failed.getString("error"));

        JSONObject next = new JSONObject(commands.dispatch("{\"id\":6,\"cmd\":\"fine\"}", EMPTY_CONTEXT));
        assertEquals(6, next.getInt("id"));
        assertTrue(next.getBoolean("ok"), "a previous command's failure must not poison the registry");
        assertTrue(next.getJSONObject("data").getBoolean("ran"));
    }

    @Test
    void aHandlerThrowingALinkageErrorIsAlsoContained() throws JSONException {
        CoopAgentCommands commands = registryOf("sandboxed", (args, context) -> {
            // What an engine class the script sandbox refuses to load actually looks like.
            throw new NoClassDefFoundError("com/fs/nope");
        });

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":7,\"cmd\":\"sandboxed\"}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("NoClassDefFoundError: com/fs/nope", response.getString("error"));
    }

    @Test
    void anExceptionWithNoMessageStillProducesTheClassPrefixedShape() throws JSONException {
        CoopAgentCommands commands = registryOf("silent", (args, context) -> {
            throw new IllegalStateException();
        });

        JSONObject response = new JSONObject(commands.dispatch("{\"id\":8,\"cmd\":\"silent\"}", EMPTY_CONTEXT));

        assertEquals("IllegalStateException: ", response.getString("error"));
    }

    @Test
    void handlersRunningWithoutACampaignReportThatRatherThanCrashing() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":10,\"cmd\":\"status\"}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"));
    }

    // ---- Registry shape ------------------------------------------------------------------------

    @Test
    void theLiveRegistryIsExactlyTheVersionOneCommandTable() {
        assertEquals(
                java.util.Set.of("ability", "addship", "barpool", "cargo", "colonizable", "entities",
                        "expedition", "feed", "fleets", "give", "intel", "landmarks", "mark", "market",
                        "markets", "netfault", "objective", "pause", "rep", "save", "screen", "setcr",
                        "status", "survey", "surveyset", "teleport", "visibility"),
                new CoopAgentCommands().verbs());
    }

    @Test
    void expeditionIsRegisteredAndFailsOnTheCampaignCheckRatherThanAsAnUnknownVerb()
            throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":11,\"cmd\":\"expedition\"}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                "the verb must be wired; without a sector it refuses for the same reason every"
                        + " other verb does");
    }

    @Test
    void colonizableIsRegisteredAndFailsOnTheCampaignCheckRatherThanAsAnUnknownVerb()
            throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":12,\"cmd\":\"colonizable\"}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                "the verb must be wired; without a sector it refuses for the same reason every"
                        + " other verb does");
    }

    @Test
    void landmarksIsRegisteredAndFailsOnTheCampaignCheckRatherThanAsAnUnknownVerb()
            throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":13,\"cmd\":\"landmarks\"}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                "the verb must be wired; without a sector it refuses for the same reason every"
                        + " other verb does");
    }

    @Test
    void cargoIsRegisteredAndFailsOnTheCampaignCheckRatherThanAsAnUnknownVerb() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":14,\"cmd\":\"cargo\"}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                "the verb must be wired; without a sector it refuses for the same reason every"
                        + " other verb does");
    }

    @Test
    void addshipIsRegisteredAndFailsOnTheCampaignCheckRatherThanAsAnUnknownVerb() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":15,\"cmd\":\"addship\",\"args\":{\"variantId\":\"wolf_Assault\"}}",
                        EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                "the verb must be wired; without a sector it refuses for the same reason every"
                        + " other verb does");
    }

    @Test
    void repIsRegisteredAndFailsOnTheCampaignCheckRatherThanAsAnUnknownVerb() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":16,\"cmd\":\"rep\",\"args\":{\"factionId\":\"hegemony\","
                        + "\"points\":50}}", EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                "the verb must be wired; without a sector it refuses for the same reason every"
                        + " other verb does");
    }

    // ---- netfault --------------------------------------------------------------------------------

    /**
     * Unlike every other verb, {@code netfault} has nothing to do with the campaign — it acts on the
     * transport. Without one it must say so, rather than pretending it armed a fault that will never
     * fire.
     */
    @Test
    void netfaultWithoutATransportRefusesByName() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(commands.dispatch(
                "{\"id\":20,\"cmd\":\"netfault\",\"args\":{\"mode\":\"discard\",\"seconds\":40}}",
                EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertTrue(response.getString("error").startsWith("IllegalStateException: netfault needs a"
                + " running coop transport"), response.getString("error"));
    }

    @Test
    void netfaultClearAlsoNeedsATransportRatherThanReportingAnEmptyClear() throws JSONException {
        CoopAgentCommands commands = new CoopAgentCommands();

        JSONObject response = new JSONObject(
                commands.dispatch("{\"id\":21,\"cmd\":\"netfault\",\"args\":{\"mode\":\"clear\"}}",
                        EMPTY_CONTEXT));

        assertFalse(response.getBoolean("ok"));
        assertTrue(response.getString("error").contains("running coop transport"),
                response.getString("error"));
    }

    /**
     * Argument validation runs before the transport lookup, so a malformed request is refused for
     * what is wrong with it. Every one of these is a mistake a tester makes at 2am.
     */
    @Test
    void netfaultRefusesMalformedRequestsBeforeItLooksForATransport() throws JSONException {
        assertEquals("IllegalArgumentException: netfault mode must be discard|loss|clear, got sever",
                errorOf("{\"mode\":\"sever\",\"seconds\":40}"));
        assertEquals("IllegalArgumentException: missing required argument mode", errorOf("{}"));
        assertEquals("IllegalArgumentException: netfault discard needs {\"seconds\": 1..180}",
                errorOf("{\"mode\":\"discard\"}"));
        assertEquals("IllegalArgumentException: seconds must be between 1 and 180, got 181",
                errorOf("{\"mode\":\"discard\",\"seconds\":181}"));
        assertEquals("IllegalArgumentException: seconds must be between 1 and 180, got 0",
                errorOf("{\"mode\":\"loss\",\"seconds\":0,\"lossPercent\":50}"));
        assertEquals("IllegalArgumentException: netfault loss needs {\"lossPercent\": 1..100}",
                errorOf("{\"mode\":\"loss\",\"seconds\":30}"));
        assertEquals("IllegalArgumentException: lossPercent must be between 1 and 100, got 0",
                errorOf("{\"mode\":\"loss\",\"seconds\":30,\"lossPercent\":0}"));
        assertEquals("IllegalArgumentException: lossPercent must be between 1 and 100, got 101",
                errorOf("{\"mode\":\"loss\",\"seconds\":30,\"lossPercent\":101}"));
    }

    /**
     * {@code delaySeconds} is optional where {@code seconds} is required: absent means "start now",
     * which is the shape every caller written before arming existed still sends. Out of range is
     * refused for the same reason a bad duration is — a clamped delay would fire at a time nobody
     * asked for, and the tester is watching a clock.
     */
    @Test
    void netfaultRefusesADelayOutsideItsRangeAndTreatsAnAbsentOneAsZero() throws JSONException {
        assertEquals("IllegalArgumentException: delaySeconds must be between 0 and 60, got 61",
                errorOf("{\"mode\":\"discard\",\"seconds\":40,\"delaySeconds\":61}"));
        assertEquals("IllegalArgumentException: delaySeconds must be between 0 and 60, got -1",
                errorOf("{\"mode\":\"discard\",\"seconds\":40,\"delaySeconds\":-1}"));
        assertEquals("IllegalArgumentException: delaySeconds must be between 0 and 60, got 61",
                errorOf("{\"mode\":\"loss\",\"seconds\":40,\"lossPercent\":50,\"delaySeconds\":61}"));

        // In range, and absent, both get past validation and fail on the missing transport instead.
        assertTrue(errorOf("{\"mode\":\"discard\",\"seconds\":40,\"delaySeconds\":5}")
                .contains("running coop transport"));
        assertTrue(errorOf("{\"mode\":\"discard\",\"seconds\":40,\"delaySeconds\":0}")
                .contains("running coop transport"));
        assertTrue(errorOf("{\"mode\":\"discard\",\"seconds\":40}").contains("running coop transport"),
                "an absent delay is not a missing argument, it is zero");
        assertTrue(errorOf("{\"mode\":\"discard\",\"seconds\":40,\"delaySeconds\":60}")
                .contains("running coop transport"), "the cap itself is allowed");

        // The duration is still checked first: a request wrong in two ways names the worse one.
        assertEquals("IllegalArgumentException: seconds must be between 1 and 180, got 181",
                errorOf("{\"mode\":\"discard\",\"seconds\":181,\"delaySeconds\":99}"));
    }

    /** Mixed case is a typo, not a different verb. */
    @Test
    void netfaultModeIsCaseInsensitive() throws JSONException {
        assertTrue(errorOf("{\"mode\":\"DISCARD\",\"seconds\":40}").contains("running coop transport"),
                "a recognized mode gets as far as the transport check");
    }

    /**
     * The {@code status} block a forgotten fault has to show up in. Shape first: every field is
     * always present, so a smoke script never has to distinguish "no fault" from "old mod build".
     */
    @Test
    void theStatusNetFaultBlockAlwaysCarriesEveryField() throws JSONException {
        JSONObject idle = CoopAgentCommands.netFaultBlock(CoopAgentCommands.netFaultStatusOf(null));

        assertFalse(idle.getBoolean("active"));
        assertEquals("", idle.getString("mode"));
        assertEquals(0L, idle.getLong("remainingSeconds"));
        assertEquals(0L, idle.getLong("discardedBytes"));
        assertEquals(0L, idle.getLong("droppedDatagrams"));
        assertFalse(idle.getBoolean("armed"));
        assertEquals(0L, idle.getLong("startsInSeconds"));

        JSONObject running = CoopAgentCommands.netFaultBlock(
                new coop.net.CoopNetService.NetFaultStatus(true, "discard", 37L, 1_234L, 8_192L, 3L,
                        false, 0L, 40));

        assertTrue(running.getBoolean("active"));
        assertEquals("discard", running.getString("mode"));
        assertEquals(37L, running.getLong("remainingSeconds"));
        assertEquals(8_192L, running.getLong("discardedBytes"));
        assertEquals(3L, running.getLong("droppedDatagrams"));
        assertFalse(running.getBoolean("armed"));
    }

    /**
     * The armed shape, which is the one a tester reads while waiting: a fault exists, nothing has
     * been dropped, and {@code active} must say so rather than being true because a fault is on the
     * books. A script that polls {@code active} to decide whether the link should be silent would
     * otherwise fail for the length of the delay.
     */
    @Test
    void anArmedFaultReportsArmedAndNotActive() throws JSONException {
        JSONObject armed = CoopAgentCommands.netFaultBlock(
                new coop.net.CoopNetService.NetFaultStatus(false, "discard", 40L, 46_000L, 0L, 0L,
                        true, 5L, 40));

        assertFalse(armed.getBoolean("active"), "an armed fault has dropped nothing yet");
        assertTrue(armed.getBoolean("armed"));
        assertEquals(5L, armed.getLong("startsInSeconds"));
        assertEquals("discard", armed.getString("mode"));
        assertEquals(40L, armed.getLong("remainingSeconds"),
                "the outage it promises is 40 s, not 45: the delay is not part of the outage");
    }

    // ---- 0.1.1 smoke verbs: registry membership and the refusals that need no campaign -----------

    /**
     * The four campaign-reading smoke verbs must fail on the sector check, not as unknown verbs. The
     * distinction matters at 2am: "no campaign loaded" means the mod is fine and the game is at the
     * title screen, "unknown command" means the jar on disk is not the one you built.
     */
    @Test
    void theSmokeQueryVerbsAreWiredAndRefuseForTheMissingCampaign() throws JSONException {
        for (String verb : java.util.List.of("screen", "entities", "intel", "save")) {
            JSONObject response = new JSONObject(new CoopAgentCommands()
                    .dispatch("{\"id\":30,\"cmd\":\"" + verb + "\"}", EMPTY_CONTEXT));

            assertFalse(response.getBoolean("ok"), verb + " should have been refused");
            assertEquals("IllegalStateException: no campaign loaded", response.getString("error"),
                    verb + " must be registered and refuse for the same reason every other verb does");
        }
    }

    /**
     * {@code feed} and {@code mark} are the two that deliberately need no campaign at all: the feed
     * transcript is most wanted after a session has ended, and a mark timestamps a step that may be
     * happening at the launcher.
     */
    @Test
    void feedAnswersWithNoCampaignRatherThanRefusing() throws JSONException {
        coop.ui.CoopSessionIntelFeed.uninstall();

        JSONObject response = new JSONObject(new CoopAgentCommands()
                .dispatch("{\"id\":31,\"cmd\":\"feed\"}", EMPTY_CONTEXT));

        assertTrue(response.getBoolean("ok"), response.toString());
        JSONObject data = response.getJSONObject("data");
        assertFalse(data.getBoolean("installed"),
                "no pump and no static handle is an empty feed, which is a fact and not an error");
        assertEquals(0, data.getInt("count"));
        assertEquals(20, data.getInt("limit"), "the default is one screenful");
    }

    @Test
    void markWritesOneLineAndNeedsNothingButItsText() throws JSONException {
        coop.testing.LogCapture log = coop.testing.LogCapture.attach(CoopAgentCommands.class);
        try {
            long before = System.currentTimeMillis();
            JSONObject response = new JSONObject(new CoopAgentCommands().dispatch(
                    "{\"id\":32,\"cmd\":\"mark\",\"args\":{\"text\":\"step 12 begins\"}}",
                    EMPTY_CONTEXT));
            long after = System.currentTimeMillis();

            assertTrue(response.getBoolean("ok"), response.toString());
            JSONObject data = response.getJSONObject("data");
            assertEquals("step 12 begins", data.getString("text"));
            long at = data.getLong("atMillis");
            assertTrue(at >= before && at <= after,
                    "the stamp has to be the one the log line got, or the two logs cannot be aligned");
            assertTrue(log.messages.contains("Coop MARK step 12 begins"),
                    "actual log lines: " + log.messages);
        } finally {
            log.detach();
        }
    }

    @Test
    void markRefusesAnythingThatWouldNotStayOneGreppableLine() throws JSONException {
        assertEquals("IllegalArgumentException: missing required argument text",
                markErrorOf("{}"));
        assertEquals("IllegalArgumentException: text must be a single line; it contains a newline",
                markErrorOf("{\"text\":\"first\\nsecond\"}"));
        assertEquals("IllegalArgumentException: text must be at most 200 characters, got 201",
                markErrorOf("{\"text\":\"" + "x".repeat(201) + "\"}"));
    }

    /**
     * The guest refusal, as its own predicate. Extracted for the same reason {@code rep}'s is: the
     * verb behind it needs a live campaign UI, and this rule is the part worth pinning.
     */
    @Test
    void anUnforcedGuestSaveIsRefusedAndAForcedOneIsNot() {
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> CoopAgentCommands.requireSaveAuthority(coop.net.CoopConnectionRole.GUEST, false));
        assertTrue(refused.getMessage().startsWith("guest saves are coordinated by the host's"
                + " checkpoint"), refused.getMessage());

        CoopAgentCommands.requireSaveAuthority(coop.net.CoopConnectionRole.GUEST, true);
        CoopAgentCommands.requireSaveAuthority(coop.net.CoopConnectionRole.HOST, false);
        CoopAgentCommands.requireSaveAuthority(coop.net.CoopConnectionRole.NONE, false);
    }

    /**
     * The {@code status} block a link-drop run reads. Zeroes rather than absent fields with no pump,
     * so a script reads the same shape before a session as during one.
     */
    @Test
    void theStatusReliableBlockAlwaysCarriesEveryField() throws JSONException {
        JSONObject idle = CoopAgentCommands.reliableBlock(null);

        assertEquals(0, idle.getInt("unacked"));
        assertEquals(0L, idle.getLong("duplicatesDropped"));
        assertEquals(0, idle.getInt("appliedSeqs"));
    }

    private static String markErrorOf(String argsJson) throws JSONException {
        JSONObject response = new JSONObject(new CoopAgentCommands().dispatch(
                "{\"id\":33,\"cmd\":\"mark\",\"args\":" + argsJson + "}", EMPTY_CONTEXT));
        assertFalse(response.getBoolean("ok"), argsJson + " should have been refused");
        return response.getString("error");
    }

    private static String errorOf(String argsJson) throws JSONException {
        JSONObject response = new JSONObject(new CoopAgentCommands().dispatch(
                "{\"id\":22,\"cmd\":\"netfault\",\"args\":" + argsJson + "}", EMPTY_CONTEXT));
        assertFalse(response.getBoolean("ok"), argsJson + " should have been refused");
        return response.getString("error");
    }

    private static CoopAgentCommands registryOf(String verb, CoopAgentCommands.Handler handler) {
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put(verb, handler);
        return new CoopAgentCommands(handlers);
    }
}
