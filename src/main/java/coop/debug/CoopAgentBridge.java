package coop.debug;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopNetPump;
import coop.util.CoopLog;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase 30: a dormant localhost TCP bridge that lets an external agent query and set up this running
 * instance. Dev tooling only — it exists so a two-instance smoke check can be a state diff instead of
 * a human reading two screens.
 *
 * <p><b>Dormant unless asked for.</b> {@code -Dcoop.debug.bridge=<port>}. Absent, {@code 0}, a
 * non-number or an out-of-range port all mean the same thing: no socket is ever opened, no log line
 * is ever written, and the script is not installed at all. A release build with the property unset is
 * byte-for-byte the same behaviour as a build without this class. The property is parsed the way
 * {@code CoopDebug} parses its own levers — a typo disables the instrument rather than taking the
 * session down.
 *
 * <p><b>Campaign thread only.</b> No threads and no {@code Selector}: the listening
 * {@link ServerSocketChannel} and the single client {@link SocketChannel} are both non-blocking, and
 * accept / read / dispatch / write all happen inside {@link #advance(float)}. That is the same shape
 * {@code CoopNetService} uses, for the same reason — Starsector can kill a mod-created thread without
 * surfacing it in the log, and the script sandbox refuses to load the stream and reflection packages
 * outright. Framing is hand-rolled newline-delimited UTF-8 over {@link ByteBuffer}, again mirroring
 * the coop TCP pump.
 *
 * <p><b>Bounded per frame.</b> At most {@value #MAX_COMMANDS_PER_FRAME} commands are dispatched per
 * frame; anything else the client sent stays buffered for the next one. Each command is a synchronous
 * call inside {@code CoopAgentCommands.dispatch}, which converts every failure — malformed JSON, an
 * unknown verb, a throwing handler — into an {@code ok:false} line. The connection survives; so does
 * the game.
 *
 * <p><b>One client.</b> A second connection while one is live is closed immediately. A disconnect
 * tears the client state down and the listener accepts a fresh one on the next frame.
 */
public final class CoopAgentBridge implements EveryFrameScript {

    /** Set to the port the bridge should listen on. Anything unparsable means dormant. */
    public static final String PORT_PROPERTY = "coop.debug.bridge";

    /** Dispatch budget per frame. Excess input stays buffered rather than being dropped. */
    static final int MAX_COMMANDS_PER_FRAME = 4;

    /** Sanity cap on one request line. Corruption protection, not a protocol limit. */
    static final int MAX_REQUEST_BYTES = 256 * 1024;

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    /**
     * The installed bridge, so a game reload can close the previous one's listener before the new one
     * binds the same port. Transient scripts are dropped on load but an open channel is not, and a
     * leaked listener would make every reload after the first fail to bind. Same static-seam shape
     * {@code CoopSaveCheckpoint.setActive} uses, and for the same reason: the install point has no
     * other handle on the previous instance.
     */
    private static CoopAgentBridge active;

    private final int port;
    private final CoopAgentCommands commands;
    private final CoopAgentCommands.Context context;

    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final byte[] requestFrame = new byte[MAX_REQUEST_BYTES];
    private final ArrayDeque<String> pendingRequests = new ArrayDeque<>();
    private final ArrayDeque<String> pendingResponses = new ArrayDeque<>();

    private ServerSocketChannel listener;
    private SocketChannel client;
    private ByteBuffer pendingWrite;
    private int requestFrameLength;
    private boolean discardingOversized;
    private boolean listenerFailed;
    private boolean stopped;

    CoopAgentBridge(int port, CoopAgentCommands commands, CoopAgentCommands.Context context) {
        this.port = port;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.context = Objects.requireNonNull(context, "context");
    }

    // ---- Gating + install -----------------------------------------------------------------------

    /**
     * The configured port, or {@code 0} for dormant. A missing property, a blank one, a non-number
     * and anything outside the usable TCP range all mean dormant; only an out-of-range <em>number</em>
     * is worth a log line, and only because it is unambiguously a typo rather than an absent flag.
     */
    public static int configuredPort() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            CoopLog.warn(CoopAgentBridge.class, "Ignoring non-numeric " + PORT_PROPERTY + "=" + raw);
            return 0;
        }
        if (parsed <= 0) {
            return 0;
        }
        if (parsed > 65535) {
            CoopLog.warn(CoopAgentBridge.class, "Ignoring out-of-range " + PORT_PROPERTY + "=" + parsed);
            return 0;
        }
        return parsed;
    }

    /** The bridge the property asks for, or {@code null} when it asks for nothing. */
    public static CoopAgentBridge createIfEnabled() {
        int port = configuredPort();
        if (port == 0) {
            return null;
        }
        return new CoopAgentBridge(port, new CoopAgentCommands(), new LiveContext());
    }

    /**
     * Installs the bridge as its own transient script, if and only if the property asks for one.
     *
     * <p>Transient is mandatory: XStream must never see this object, because it owns live channels.
     * Its own script, rather than a step inside the coop pump, is also mandatory — the bridge has to
     * answer before a session exists and when none ever will (a solo instance being set up for a
     * check), and the pump's steps are all gated on session state.
     *
     * <p>Returns before touching the sector at all when dormant, so "property absent" really is zero
     * observable effect.
     */
    public static void install(SectorAPI sector) {
        // Unconditional, and before the dormancy check: a bridge from the game that just unloaded
        // still owns a bound listener, and leaving it open would make the next bind fail. Silent and
        // free when there is nothing to close, which is the dormant case.
        shutdownActive();
        CoopAgentBridge bridge = createIfEnabled();
        if (bridge == null) {
            return;
        }
        Objects.requireNonNull(sector, "sector");
        sector.removeScriptsOfClass(CoopAgentBridge.class);
        sector.removeTransientScriptsOfClass(CoopAgentBridge.class);
        sector.addTransientScript(bridge);
        active = bridge;
        CoopLog.info(CoopAgentBridge.class, "Coop agent bridge armed on 127.0.0.1:" + bridge.port
                + " (" + PORT_PROPERTY + "); dev tooling, read/diff/setup verbs only");
    }

    /** Closes the previously installed bridge, if any. Safe to call when there is none. */
    public static void shutdownActive() {
        CoopAgentBridge previous = active;
        active = null;
        if (previous != null) {
            previous.shutdown();
        }
    }

    // ---- Script lifecycle -----------------------------------------------------------------------

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        // The bridge is most useful exactly when the game is stopped: a paused instance is the only
        // one whose state is not moving under a diff.
        return true;
    }

    @Override
    public void advance(float amount) {
        if (stopped) {
            return;
        }
        try {
            pump();
        } catch (RuntimeException | LinkageError ex) {
            // An exception escaping advance() kills the script for the rest of the session. A dev
            // tool must never be the reason a session loses a frame hook, so the client is dropped
            // and the listener stays up for the next connection.
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge frame failed; dropping client", ex);
            closeClient();
        }
    }

    private void pump() {
        if (!ensureListener()) {
            return;
        }
        acceptClient();
        readRequests();
        dispatchBuffered();
        flushResponses();
    }

    /** Releases both channels. Idempotent; the bridge stays inert afterwards. */
    public void shutdown() {
        stopped = true;
        closeClient();
        closeChannel(listener);
        listener = null;
        CoopLog.info(CoopAgentBridge.class, "Coop agent bridge closed on port " + port);
    }

    // ---- Socket plumbing ------------------------------------------------------------------------

    private boolean ensureListener() {
        if (listener != null && listener.isOpen()) {
            return true;
        }
        if (listenerFailed) {
            return false;
        }
        try {
            ServerSocketChannel channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().setReuseAddress(true);
            // Loopback only, always. This is an unauthenticated command channel into a running game;
            // it has no business being reachable from anywhere but this machine.
            channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
            listener = channel;
            CoopLog.info(CoopAgentBridge.class, "Coop agent bridge listening on 127.0.0.1:" + port);
            return true;
        } catch (Exception ex) {
            listenerFailed = true;
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge could not bind 127.0.0.1:" + port
                    + "; staying dormant for this session", ex);
            return false;
        }
    }

    private void acceptClient() {
        try {
            SocketChannel accepted = listener.accept();
            if (accepted == null) {
                return;
            }
            if (client != null) {
                // One client at a time (the MCP server holds exactly one connection per instance).
                closeChannel(accepted);
                CoopLog.warn(CoopAgentBridge.class,
                        "Coop agent bridge refused a second client on port " + port);
                return;
            }
            accepted.configureBlocking(false);
            accepted.socket().setTcpNoDelay(true);
            client = accepted;
            resetFraming();
            CoopLog.info(CoopAgentBridge.class, "Coop agent bridge client connected on port " + port);
        } catch (Exception ex) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge accept failed", ex);
        }
    }

    private void readRequests() {
        SocketChannel channel = client;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }
        try {
            readBuffer.clear();
            int read = channel.read(readBuffer);
            while (read > 0) {
                readBuffer.flip();
                while (readBuffer.hasRemaining()) {
                    appendRequestByte(readBuffer.get());
                }
                readBuffer.clear();
                read = channel.read(readBuffer);
            }
            if (read < 0) {
                CoopLog.info(CoopAgentBridge.class, "Coop agent bridge client disconnected");
                closeClient();
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge read failed; dropping client", ex);
            closeClient();
        }
    }

    private void appendRequestByte(byte value) {
        int unsigned = value & 0xff;
        if (unsigned == '\n') {
            if (discardingOversized) {
                requestFrameLength = 0;
                discardingOversized = false;
                return;
            }
            String line = new String(requestFrame, 0, requestFrameLength, StandardCharsets.UTF_8).trim();
            requestFrameLength = 0;
            if (!line.isEmpty()) {
                pendingRequests.add(line);
            }
            return;
        }
        if (unsigned == '\r' || discardingOversized) {
            return;
        }
        if (requestFrameLength >= MAX_REQUEST_BYTES) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge discarding oversized request line");
            requestFrameLength = 0;
            discardingOversized = true;
            return;
        }
        requestFrame[requestFrameLength] = (byte) unsigned;
        requestFrameLength++;
    }

    /**
     * Runs at most {@value #MAX_COMMANDS_PER_FRAME} buffered commands and queues their responses.
     * Whatever is left stays in the queue for the next frame — the cap bounds how much campaign-thread
     * time one frame can spend answering, and dropping the excess would silently lose a request the
     * caller is still waiting on.
     *
     * @return how many commands were dispatched this call
     */
    int dispatchBuffered() {
        int dispatched = 0;
        while (dispatched < MAX_COMMANDS_PER_FRAME) {
            String request = pendingRequests.poll();
            if (request == null) {
                break;
            }
            dispatched++;
            pendingResponses.add(commands.dispatch(request, context));
        }
        return dispatched;
    }

    private void flushResponses() {
        SocketChannel channel = client;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }
        try {
            while (true) {
                if (pendingWrite == null) {
                    String next = pendingResponses.poll();
                    if (next == null) {
                        return;
                    }
                    pendingWrite = ByteBuffer.wrap((next + "\n").getBytes(StandardCharsets.UTF_8));
                }
                while (pendingWrite.hasRemaining()) {
                    if (channel.write(pendingWrite) == 0) {
                        // Socket buffer full: resume this exact response next frame.
                        return;
                    }
                }
                pendingWrite = null;
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge write failed; dropping client", ex);
            closeClient();
        }
    }

    private void closeClient() {
        SocketChannel channel = client;
        client = null;
        pendingWrite = null;
        pendingRequests.clear();
        pendingResponses.clear();
        resetFraming();
        closeChannel(channel);
    }

    private void resetFraming() {
        requestFrameLength = 0;
        discardingOversized = false;
    }

    private static void closeChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ex) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge failed to close a channel", ex);
        }
    }

    // ---- Live context ---------------------------------------------------------------------------

    /**
     * The only place the bridge reaches for engine globals. The pump is found by scanning the
     * sector's transient scripts rather than through a static handle, because the bridge deliberately
     * does not participate in the pump's lifecycle and must keep answering when there is no pump at
     * all (returning null, which the handlers turn into a readable error).
     */
    static final class LiveContext implements CoopAgentCommands.Context {
        @Override
        public SectorAPI sector() {
            try {
                return Global.getSector();
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
        }

        @Override
        public CoopNetPump pump() {
            SectorAPI sector = sector();
            if (sector == null) {
                return null;
            }
            try {
                List<EveryFrameScript> scripts = sector.getTransientScripts();
                if (scripts == null) {
                    return null;
                }
                for (EveryFrameScript script : new ArrayList<>(scripts)) {
                    if (script instanceof CoopNetPump pump) {
                        return pump;
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
            return null;
        }
    }

    // ---- Test seams -----------------------------------------------------------------------------

    /** The port this instance was built for. */
    int portForTesting() {
        return port;
    }

    /** True once a listener is bound. */
    boolean listeningForTesting() {
        return listener != null && listener.isOpen();
    }

    /** Drives one frame's worth of socket + dispatch work without the engine. */
    void advanceForTesting() {
        advance(1f / 60f);
    }

    /** Buffers a request line as if it had arrived framed on the socket. */
    void enqueueRequestForTesting(String line) {
        pendingRequests.add(line);
    }

    int pendingRequestCountForTesting() {
        return pendingRequests.size();
    }

    /** Takes the responses queued so far, in order. */
    List<String> drainResponsesForTesting() {
        List<String> drained = new ArrayList<>(pendingResponses);
        pendingResponses.clear();
        return drained;
    }
}
