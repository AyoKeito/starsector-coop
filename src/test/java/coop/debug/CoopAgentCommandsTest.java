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
                java.util.Set.of("ability", "barpool", "colonizable", "expedition", "fleets", "give",
                        "market", "markets", "objective", "pause", "setcr", "status", "survey",
                        "surveyset", "teleport", "visibility"),
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

    private static CoopAgentCommands registryOf(String verb, CoopAgentCommands.Handler handler) {
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put(verb, handler);
        return new CoopAgentCommands(handlers);
    }
}
