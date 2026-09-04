package coop.debug;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.config.CoopOptionsRegistry;
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
 * non-number or an out-of-range port all mean the same thing: no socket is ever opened and the script
 * is not installed at all. A release build with the property unset is byte-for-byte the same
 * behaviour as a build without this class — absent or {@code 0}, nothing is logged either. A
 * non-number or an above-range number does get one WARN, because it is unambiguously a typo and a
 * silently dormant instrument someone believes they turned on is worse. The property is parsed the
 * way {@code CoopDebug} parses its own levers — a typo disables the instrument rather than taking
 * the session down.
 *
 * <p><b>Campaign thread only.</b> No threads and no {@code Selector}: the listening
 * {@link ServerSocketChannel} and every client {@link SocketChannel} are non-blocking, and accept /
 * read / dispatch / write all happen inside {@link #advance(float)}. That is the same shape
 * {@code CoopNetService} uses, for the same reason — Starsector can kill a mod-created thread without
 * surfacing it in the log, and the script sandbox refuses to load the stream and reflection packages
 * outright. Framing is hand-rolled newline-delimited UTF-8 over {@link ByteBuffer}, again mirroring
 * the coop TCP pump.
 *
 * <p><b>Bounded per frame.</b> At most {@value #MAX_COMMANDS_PER_FRAME} commands are dispatched per
 * frame <em>across all clients</em>; anything else stays buffered for the next one. The budget is
 * spent round-robin, one request per client per pass, so a client spraying requests cannot starve the
 * others. Each command is a synchronous call inside {@code CoopAgentCommands.dispatch}, which converts
 * every failure — malformed JSON, an unknown verb, a throwing handler — into an {@code ok:false} line.
 * The connection survives; so does the game.
 *
 * <p><b>Up to {@value #MAX_CLIENTS} clients.</b> The bridge used to serve exactly one, and the second
 * connection (a scripted supply drip during the 2026-09-02 QA run, while the MCP server held the line)
 * got {@code ECONNRESET}. It now accepts a small fixed number and refuses the one past the cap by
 * closing it immediately — refusal rather than eviction, because the client already being served is
 * the one with work in flight. Every client carries its own framing state, request queue and write
 * queue; a disconnect, a half-close or a write failure on one costs that client only.
 */
public final class CoopAgentBridge implements EveryFrameScript {

    /** Set to the port the bridge should listen on. Anything unparsable means dormant. */
    public static final String PORT_PROPERTY = CoopOptionsRegistry.DEBUG_BRIDGE;

    /** Dispatch budget per frame, shared by every client. Excess input stays buffered. */
    static final int MAX_COMMANDS_PER_FRAME = 4;

    /**
     * How many clients may be connected at once. Four, because the realistic load is one MCP server
     * plus one or two scripted helpers, and every extra slot costs a {@value #MAX_REQUEST_BYTES}-byte
     * framing buffer for the life of the connection.
     */
    static final int MAX_CLIENTS = 4;

    /** Sanity cap on one request line. Corruption protection, not a protocol limit. */
    static final int MAX_REQUEST_BYTES = 256 * 1024;

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    /**
     * Accept attempts per frame. The backlog is drained rather than trickled one connection per frame,
     * but the loop is still bounded so a connect storm cannot hold the campaign thread.
     */
    private static final int MAX_ACCEPTS_PER_FRAME = MAX_CLIENTS + 4;

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

    /**
     * Scratch only, and safe to share: a read pass fills it, drains it into one client's framing
     * buffer and clears it again, all inside a single synchronous call.
     */
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);

    private final List<ClientSession> clients = new ArrayList<>(MAX_CLIENTS);

    private ServerSocketChannel listener;
    private boolean listenerFailed;
    private boolean stopped;

    CoopAgentBridge(int port, CoopAgentCommands commands, CoopAgentCommands.Context context) {
        this.port = port;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Everything one connection owns. Nothing here is shared, which is what makes N clients safe. */
    private static final class ClientSession {
        /** {@code null} only for the test seam that buffers requests without a socket. */
        private final SocketChannel channel;
        private final byte[] requestFrame = new byte[MAX_REQUEST_BYTES];
        private final ArrayDeque<String> pendingRequests = new ArrayDeque<>();
        private final ArrayDeque<String> pendingResponses = new ArrayDeque<>();

        private ByteBuffer pendingWrite;
        private int requestFrameLength;
        /** The peer sent EOF; the client is kept only until what it already framed has been answered. */
        private boolean peerClosed;
        private boolean discardingOversized;

        private ClientSession(SocketChannel channel) {
            this.channel = channel;
        }

        private boolean idle() {
            return pendingRequests.isEmpty() && pendingResponses.isEmpty() && pendingWrite == null;
        }
    }

    // ---- Gating + install -----------------------------------------------------------------------

    /**
     * The configured port, or {@code 0} for dormant. A missing property, a blank one, a non-number
     * and anything outside the usable TCP range all mean dormant; a non-number and an above-range
     * number are worth a log line, and only because they are unambiguously typos rather than a flag
     * someone deliberately switched off ({@code 0} and negatives are silent for that reason).
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
                + " (" + PORT_PROPERTY + "); dev tooling, read/diff/setup verbs only, up to "
                + MAX_CLIENTS + " clients");
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
            // tool must never be the reason a session loses a frame hook. Every per-client step has
            // its own catch, so anything reaching here is not attributable to one connection: drop
            // them all and keep the listener up for the next one.
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge frame failed; dropping clients", ex);
            closeAllClients();
        }
    }

    private void pump() {
        if (!ensureListener()) {
            return;
        }
        acceptClients();
        readRequests();
        dispatchBuffered();
        flushResponses();
        closeAfterPeerHalfClose();
    }

    /**
     * Finishes half-closed clients: the peer sent EOF, so nothing more will be read, but whatever it
     * framed before closing is still owed an answer. Each closes as soon as the last of it has been
     * written (the shared dispatch budget can spread that over several frames).
     */
    private void closeAfterPeerHalfClose() {
        for (ClientSession session : snapshot()) {
            if (session.peerClosed && session.idle()) {
                CoopLog.info(CoopAgentBridge.class, "Coop agent bridge client disconnected");
                closeClient(session);
            }
        }
    }

    /** Releases every channel. Idempotent; the bridge stays inert afterwards. */
    public void shutdown() {
        stopped = true;
        closeAllClients();
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
            channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), MAX_CLIENTS);
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

    private void acceptClients() {
        for (int attempt = 0; attempt < MAX_ACCEPTS_PER_FRAME; attempt++) {
            SocketChannel accepted;
            try {
                accepted = listener.accept();
            } catch (Exception ex) {
                CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge accept failed", ex);
                return;
            }
            if (accepted == null) {
                return;
            }
            if (clients.size() >= MAX_CLIENTS) {
                // Refuse the newcomer rather than evict a client that already has work in flight.
                closeChannel(accepted);
                CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge refused a client on port "
                        + port + "; " + MAX_CLIENTS + " already connected");
                continue;
            }
            try {
                accepted.configureBlocking(false);
                accepted.socket().setTcpNoDelay(true);
            } catch (Exception ex) {
                CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge could not configure a client", ex);
                closeChannel(accepted);
                continue;
            }
            clients.add(new ClientSession(accepted));
            CoopLog.info(CoopAgentBridge.class, "Coop agent bridge client connected on port " + port
                    + " (" + clients.size() + "/" + MAX_CLIENTS + ")");
        }
    }

    private void readRequests() {
        for (ClientSession session : snapshot()) {
            readRequests(session);
        }
    }

    private void readRequests(ClientSession session) {
        SocketChannel channel = session.channel;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }
        try {
            readBuffer.clear();
            int read = channel.read(readBuffer);
            while (read > 0) {
                readBuffer.flip();
                while (readBuffer.hasRemaining()) {
                    appendRequestByte(session, readBuffer.get());
                }
                readBuffer.clear();
                read = channel.read(readBuffer);
            }
            if (read < 0) {
                // A one-shot client writes its request, half-closes its send side and waits for the
                // answer, so the framed line and the EOF arrive in the same read pass. Tearing the
                // client down here threw that request away before dispatch and the caller saw an
                // empty close it could not tell from a crash. The teardown is deferred to
                // closeAfterPeerHalfClose(), which runs once everything queued has been answered.
                session.peerClosed = true;
                if (session.idle()) {
                    CoopLog.info(CoopAgentBridge.class, "Coop agent bridge client disconnected");
                    closeClient(session);
                }
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge read failed; dropping client", ex);
            closeClient(session);
        }
    }

    private void appendRequestByte(ClientSession session, byte value) {
        int unsigned = value & 0xff;
        if (unsigned == '\n') {
            if (session.discardingOversized) {
                session.requestFrameLength = 0;
                session.discardingOversized = false;
                return;
            }
            String line = new String(session.requestFrame, 0, session.requestFrameLength,
                    StandardCharsets.UTF_8).trim();
            session.requestFrameLength = 0;
            if (!line.isEmpty()) {
                session.pendingRequests.add(line);
            }
            return;
        }
        if (unsigned == '\r' || session.discardingOversized) {
            return;
        }
        if (session.requestFrameLength >= MAX_REQUEST_BYTES) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge discarding oversized request line");
            session.requestFrameLength = 0;
            session.discardingOversized = true;
            return;
        }
        session.requestFrame[session.requestFrameLength] = (byte) unsigned;
        session.requestFrameLength++;
    }

    /**
     * Runs at most {@value #MAX_COMMANDS_PER_FRAME} buffered commands across every client and queues
     * their responses. Whatever is left stays queued for the next frame — the cap bounds how much
     * campaign-thread time one frame can spend answering, and dropping the excess would silently lose
     * a request the caller is still waiting on.
     *
     * <p>The budget is global and spent one request per client per pass: with a single client that is
     * the old behaviour exactly, and with several it means a client sending a burst cannot spend the
     * whole frame's budget while another waits.
     *
     * @return how many commands were dispatched this call
     */
    int dispatchBuffered() {
        int dispatched = 0;
        boolean progress = true;
        while (dispatched < MAX_COMMANDS_PER_FRAME && progress) {
            progress = false;
            for (ClientSession session : snapshot()) {
                if (dispatched >= MAX_COMMANDS_PER_FRAME) {
                    break;
                }
                String request = session.pendingRequests.poll();
                if (request == null) {
                    continue;
                }
                dispatched++;
                progress = true;
                session.pendingResponses.add(commands.dispatch(request, context));
            }
        }
        return dispatched;
    }

    private void flushResponses() {
        for (ClientSession session : snapshot()) {
            flushResponses(session);
        }
    }

    private void flushResponses(ClientSession session) {
        SocketChannel channel = session.channel;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }
        try {
            while (true) {
                if (session.pendingWrite == null) {
                    String next = session.pendingResponses.poll();
                    if (next == null) {
                        return;
                    }
                    session.pendingWrite =
                            ByteBuffer.wrap((next + "\n").getBytes(StandardCharsets.UTF_8));
                }
                while (session.pendingWrite.hasRemaining()) {
                    if (channel.write(session.pendingWrite) == 0) {
                        // Socket buffer full: resume this exact response next frame.
                        return;
                    }
                }
                session.pendingWrite = null;
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopAgentBridge.class, "Coop agent bridge write failed; dropping client", ex);
            closeClient(session);
        }
    }

    private void closeClient(ClientSession session) {
        clients.remove(session);
        session.pendingWrite = null;
        session.pendingRequests.clear();
        session.pendingResponses.clear();
        closeChannel(session.channel);
    }

    private void closeAllClients() {
        for (ClientSession session : snapshot()) {
            closeClient(session);
        }
    }

    /** Iteration copy: every per-client step may close its own session out of the live list. */
    private List<ClientSession> snapshot() {
        return new ArrayList<>(clients);
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

    /** How many clients are connected right now. */
    int clientCountForTesting() {
        return clients.size();
    }

    /** Drives one frame's worth of socket + dispatch work without the engine. */
    void advanceForTesting() {
        advance(1f / 60f);
    }

    /**
     * Buffers a request line as if it had arrived framed on the socket, on a socketless session.
     * {@code clientIndex} picks which one, so the round-robin budget can be tested without four real
     * connections; sessions are created on demand up to the cap.
     */
    void enqueueRequestForTesting(int clientIndex, String line) {
        while (clients.size() <= clientIndex) {
            if (clients.size() >= MAX_CLIENTS) {
                throw new IllegalArgumentException("clientIndex " + clientIndex + " is past the "
                        + MAX_CLIENTS + "-client cap");
            }
            clients.add(new ClientSession(null));
        }
        clients.get(clientIndex).pendingRequests.add(line);
    }

    /** Buffers a request line on the first socketless session. */
    void enqueueRequestForTesting(String line) {
        enqueueRequestForTesting(0, line);
    }

    /** Requests still buffered on one client, so fairness can be asserted per connection. */
    int pendingRequestCountForTesting(int clientIndex) {
        return clientIndex < clients.size() ? clients.get(clientIndex).pendingRequests.size() : 0;
    }

    int pendingRequestCountForTesting() {
        int total = 0;
        for (ClientSession session : clients) {
            total += session.pendingRequests.size();
        }
        return total;
    }

    /** Takes the responses queued so far on every client, client by client. */
    List<String> drainResponsesForTesting() {
        List<String> drained = new ArrayList<>();
        for (ClientSession session : clients) {
            drained.addAll(session.pendingResponses);
            session.pendingResponses.clear();
        }
        return drained;
    }
}
