package coop.debug;

import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopNetPump;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The socket half of the Phase 30 agent bridge.
 *
 * <p>Two properties matter more than anything else here and both are pinned below. The first is
 * dormancy: with {@code coop.debug.bridge} unset — which is every shipped session — nothing is
 * constructed, nothing binds, and {@code install} does not so much as look at the sector. The second
 * is that the frame loop is bounded and unkillable: at most four commands per frame, and a request
 * that fails costs its own response and nothing else.
 *
 * <p>{@code java.io} appears in this file and must not appear in the main sources; the sandbox lint
 * at the bottom is what keeps that true. The restriction is on what Starsector's script classloader
 * will load at runtime, not on the test JVM.
 */
class CoopAgentBridgeTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final long TIMEOUT_MILLIS = 5_000L;

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

    private String savedProperty;

    @BeforeEach
    void setUp() {
        savedProperty = System.getProperty(CoopAgentBridge.PORT_PROPERTY);
        System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        if (savedProperty == null) {
            System.clearProperty(CoopAgentBridge.PORT_PROPERTY);
        } else {
            System.setProperty(CoopAgentBridge.PORT_PROPERTY, savedProperty);
        }
        CoopAgentBridge.shutdownActive();
    }

    // ---- Dormancy ------------------------------------------------------------------------------

    @Test
    void withoutThePropertyNothingIsBuiltAndNothingBinds() throws IOException {
        int port = reserveLocalPort();

        assertEquals(0, CoopAgentBridge.configuredPort());
        assertNull(CoopAgentBridge.createIfEnabled(), "dormant means no instance at all");

        // install() must return before it even touches the sector, so a null one cannot NPE.
        CoopAgentBridge.install(null);

        // Nothing took the port: it is still free to bind.
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            assertTrue(probe.isBound());
        }
    }

    @Test
    void zeroAndGarbageAndOutOfRangeValuesAreAllDormant() {
        for (String raw : new String[]{"0", "-1", "", "   ", "soon", "7801x", "70000", "99999999999"}) {
            System.setProperty(CoopAgentBridge.PORT_PROPERTY, raw);
            assertEquals(0, CoopAgentBridge.configuredPort(), "'" + raw + "' must parse as dormant");
            assertNull(CoopAgentBridge.createIfEnabled(), "'" + raw + "' must not build a bridge");
        }
    }

    @Test
    void aValidPortBuildsABridgeThatHasNotBoundAnythingYet() {
        System.setProperty(CoopAgentBridge.PORT_PROPERTY, "7801");

        CoopAgentBridge bridge = CoopAgentBridge.createIfEnabled();

        assertNotNull(bridge);
        assertEquals(7801, bridge.portForTesting());
        assertFalse(bridge.listeningForTesting(),
                "construction must be side-effect free; the listener opens on the first frame");
    }

    // ---- Per-frame dispatch cap ----------------------------------------------------------------

    @Test
    void atMostFourBufferedCommandsAreDispatchedPerFrameAndTheRestWaitForTheNextOne() {
        CoopAgentBridge bridge = bridgeWithCountingRegistry(new int[1]);
        for (int i = 1; i <= 6; i++) {
            bridge.enqueueRequestForTesting("{\"id\":" + i + ",\"cmd\":\"count\"}");
        }

        assertEquals(CoopAgentBridge.MAX_COMMANDS_PER_FRAME, bridge.dispatchBuffered());
        assertEquals(2, bridge.pendingRequestCountForTesting(),
                "the excess must stay buffered; dropping it would lose a request the caller awaits");
        assertEquals(4, bridge.drainResponsesForTesting().size());

        assertEquals(2, bridge.dispatchBuffered());
        assertEquals(0, bridge.pendingRequestCountForTesting());
        assertEquals(2, bridge.drainResponsesForTesting().size());
    }

    @Test
    void theCapCountsCommandsNotSuccessesSoFailuresDoNotBuyExtraDispatchBudget() {
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put("boom", (args, context) -> {
            throw new IllegalStateException("nope");
        });
        CoopAgentBridge bridge = new CoopAgentBridge(1, new CoopAgentCommands(handlers), EMPTY_CONTEXT);
        for (int i = 0; i < 6; i++) {
            bridge.enqueueRequestForTesting("{\"id\":" + i + ",\"cmd\":\"boom\"}");
        }

        assertEquals(CoopAgentBridge.MAX_COMMANDS_PER_FRAME, bridge.dispatchBuffered());
        assertEquals(2, bridge.pendingRequestCountForTesting());
    }

    @Test
    void dispatchingAnEmptyBufferIsANoOp() {
        CoopAgentBridge bridge = bridgeWithCountingRegistry(new int[1]);

        assertEquals(0, bridge.dispatchBuffered());
        assertEquals(List.of(), bridge.drainResponsesForTesting());
    }

    // ---- Over a real localhost socket ----------------------------------------------------------

    @Test
    void aClientGetsOneResponseLinePerRequestOverTheSocket() throws IOException, JSONException {
        int port = reserveLocalPort();
        CoopAgentBridge bridge = new CoopAgentBridge(port, echoRegistry(), EMPTY_CONTEXT);

        SocketChannel client = null;
        try {
            bridge.advanceForTesting();
            assertTrue(bridge.listeningForTesting(), "the first frame must bind the listener");

            client = connect(port);
            bridge.advanceForTesting();

            write(client, "{\"id\":41,\"cmd\":\"echo\",\"args\":{\"what\":\"alpha\"}}");
            JSONObject first = new JSONObject(readLine(client, bridge));
            assertEquals(41, first.getInt("id"));
            assertTrue(first.getBoolean("ok"));
            assertEquals("alpha", first.getJSONObject("data").getString("seen"));

            write(client, "{\"id\":42,\"cmd\":\"echo\",\"args\":{\"what\":\"beta\"}}");
            JSONObject second = new JSONObject(readLine(client, bridge));
            assertEquals(42, second.getInt("id"));
            assertEquals("beta", second.getJSONObject("data").getString("seen"));
        } finally {
            close(client);
            bridge.shutdown();
        }
    }

    @Test
    void aFailingCommandDoesNotKillTheConnectionOrTheNextCommandOnIt() throws IOException, JSONException {
        int port = reserveLocalPort();
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put("boom", (args, context) -> {
            throw new IllegalStateException("engine said no");
        });
        handlers.putAll(echoHandlers());
        CoopAgentBridge bridge = new CoopAgentBridge(port, new CoopAgentCommands(handlers), EMPTY_CONTEXT);

        SocketChannel client = null;
        try {
            bridge.advanceForTesting();
            client = connect(port);
            bridge.advanceForTesting();

            write(client, "{\"id\":1,\"cmd\":\"boom\"}");
            JSONObject failed = new JSONObject(readLine(client, bridge));
            assertFalse(failed.getBoolean("ok"));
            assertEquals("IllegalStateException: engine said no", failed.getString("error"));

            write(client, "{\"id\":2,\"cmd\":\"echo\",\"args\":{\"what\":\"still here\"}}");
            JSONObject next = new JSONObject(readLine(client, bridge));
            assertTrue(next.getBoolean("ok"), "the connection must survive a failed command");
            assertEquals("still here", next.getJSONObject("data").getString("seen"));
        } finally {
            close(client);
            bridge.shutdown();
        }
    }

    @Test
    void aDisconnectedClientIsTornDownAndAFreshOneIsAcceptedNextFrame() throws IOException, JSONException {
        int port = reserveLocalPort();
        CoopAgentBridge bridge = new CoopAgentBridge(port, echoRegistry(), EMPTY_CONTEXT);

        try {
            bridge.advanceForTesting();

            SocketChannel first = connect(port);
            bridge.advanceForTesting();
            write(first, "{\"id\":1,\"cmd\":\"echo\",\"args\":{\"what\":\"one\"}}");
            assertTrue(new JSONObject(readLine(first, bridge)).getBoolean("ok"));
            close(first);

            // The read of -1 that notices the peer left happens on a frame, so give it one.
            bridge.advanceForTesting();
            bridge.advanceForTesting();

            SocketChannel second = connect(port);
            bridge.advanceForTesting();
            write(second, "{\"id\":2,\"cmd\":\"echo\",\"args\":{\"what\":\"two\"}}");
            JSONObject response = new JSONObject(readLine(second, bridge));
            assertEquals(2, response.getInt("id"));
            assertEquals("two", response.getJSONObject("data").getString("seen"));
            close(second);
        } finally {
            bridge.shutdown();
        }
    }

    @Test
    void severalRequestsArrivingInOneWriteAreFramedIndividuallyAndCappedPerFrame()
            throws IOException, JSONException {
        int port = reserveLocalPort();
        CoopAgentBridge bridge = new CoopAgentBridge(port, echoRegistry(), EMPTY_CONTEXT);

        SocketChannel client = null;
        try {
            bridge.advanceForTesting();
            client = connect(port);
            bridge.advanceForTesting();

            StringBuilder batch = new StringBuilder();
            for (int i = 1; i <= 6; i++) {
                batch.append("{\"id\":").append(i).append(",\"cmd\":\"echo\",\"args\":{\"what\":\"n")
                        .append(i).append("\"}}\n");
            }
            writeRaw(client, batch.toString());

            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ids.add(new JSONObject(readLine(client, bridge)).getInt("id"));
            }
            assertEquals(List.of(1, 2, 3, 4, 5, 6), ids,
                    "every buffered request must eventually be answered, in order");
        } finally {
            close(client);
            bridge.shutdown();
        }
    }

    @Test
    void aSecondClientIsRefusedWhileOneIsAlreadyConnected() throws IOException, JSONException {
        int port = reserveLocalPort();
        CoopAgentBridge bridge = new CoopAgentBridge(port, echoRegistry(), EMPTY_CONTEXT);

        SocketChannel first = null;
        SocketChannel second = null;
        try {
            bridge.advanceForTesting();
            first = connect(port);
            bridge.advanceForTesting();

            second = connect(port);
            bridge.advanceForTesting();
            bridge.advanceForTesting();

            // The first connection is untouched by the refusal.
            write(first, "{\"id\":3,\"cmd\":\"echo\",\"args\":{\"what\":\"mine\"}}");
            JSONObject response = new JSONObject(readLine(first, bridge));
            assertEquals("mine", response.getJSONObject("data").getString("seen"));
        } finally {
            close(first);
            close(second);
            bridge.shutdown();
        }
    }

    @Test
    void blankLinesAndCarriageReturnsAreIgnoredRatherThanAnsweredWithAParseError()
            throws IOException, JSONException {
        int port = reserveLocalPort();
        CoopAgentBridge bridge = new CoopAgentBridge(port, echoRegistry(), EMPTY_CONTEXT);

        SocketChannel client = null;
        try {
            bridge.advanceForTesting();
            client = connect(port);
            bridge.advanceForTesting();

            writeRaw(client, "\n\r\n   \n{\"id\":77,\"cmd\":\"echo\",\"args\":{\"what\":\"only me\"}}\r\n");

            JSONObject response = new JSONObject(readLine(client, bridge));
            assertEquals(77, response.getInt("id"), "keepalive newlines must not produce responses");
            assertEquals("only me", response.getJSONObject("data").getString("seen"));
        } finally {
            close(client);
            bridge.shutdown();
        }
    }

    // ---- Sandbox lint --------------------------------------------------------------------------

    @Test
    void theBridgeAvoidsTheClassesStarsectorsScriptSandboxRefusesToLoad() throws IOException {
        Path sourceRoot = PROJECT_ROOT.resolve("src/main/java/coop/debug");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        assertFalse(sources.isEmpty(), "the lint has to actually see the bridge sources");

        List<String> offenders = new ArrayList<>();
        for (Path path : sources) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            String name = PROJECT_ROOT.relativize(path).toString();
            if (source.contains("java.io.")) {
                offenders.add(name + ": java.io (blocked as file access)");
            }
            if (source.contains("java.lang.reflect")) {
                offenders.add(name + ": java.lang.reflect (blocked)");
            }
            if (source.contains("new Thread(")) {
                offenders.add(name + ": background thread (the bridge is campaign-thread only)");
            }
            if (source.contains("java.nio.channels.Selector") || source.contains("Selector.open")) {
                offenders.add(name + ": Selector (advance() polls instead)");
            }
        }

        assertEquals(List.of(), offenders, "bridge sources must stay loadable by the script sandbox");
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static CoopAgentCommands echoRegistry() {
        return new CoopAgentCommands(echoHandlers());
    }

    private static Map<String, CoopAgentCommands.Handler> echoHandlers() {
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put("echo", (args, context) -> {
            JSONObject data = new JSONObject();
            data.put("seen", args.optString("what", ""));
            return data;
        });
        return handlers;
    }

    private static CoopAgentBridge bridgeWithCountingRegistry(int[] counter) {
        Map<String, CoopAgentCommands.Handler> handlers = new LinkedHashMap<>();
        handlers.put("count", (args, context) -> {
            counter[0]++;
            JSONObject data = new JSONObject();
            data.put("calls", counter[0]);
            return data;
        });
        return new CoopAgentBridge(1, new CoopAgentCommands(handlers), EMPTY_CONTEXT);
    }

    private static int reserveLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static SocketChannel connect(int port) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.socket().setTcpNoDelay(true);
        channel.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        channel.configureBlocking(false);
        return channel;
    }

    private static void write(SocketChannel channel, String line) throws IOException {
        writeRaw(channel, line + "\n");
    }

    private static void writeRaw(SocketChannel channel, String payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /** Drives the bridge's frame loop until a whole response line is readable, or gives up loudly. */
    private String readLine(SocketChannel channel, CoopAgentBridge bridge) throws IOException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        while (System.currentTimeMillis() < deadline) {
            int index = pending.indexOf('\n');
            if (index >= 0) {
                String line = pending.substring(0, index);
                pending = pending.substring(index + 1);
                return line;
            }
            bridge.advanceForTesting();
            buffer.clear();
            int read = channel.read(buffer);
            if (read > 0) {
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                pending += new String(bytes, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("timed out waiting for a bridge response line; buffered='" + pending + "'");
    }

    /** Leftover bytes between {@link #readLine} calls; a response can arrive batched with the next. */
    private String pending = "";

    private static void close(SocketChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Test teardown; a channel that is already gone is the outcome we wanted anyway.
        }
    }
}
