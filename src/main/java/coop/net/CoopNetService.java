package coop.net;

import coop.util.CoopLog;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The coop transport: one TCP control channel per peer (reliable, JSON lines) plus one UDP datagram
 * channel (best-effort state stream), all non-blocking and all driven from the campaign thread —
 * Starsector kills mod-created networking threads without saying so, so everything here is a state
 * machine the pump advances, never a blocking read.
 *
 * <p><b>Peer table (Phase 20.5).</b> Per-peer state lives in {@link CoopPeerLink}; this class owns
 * the listening socket, the shared UDP channel, the session token, the local sender id, and the
 * counters. The table is sized by {@code coop.maxGuests}, which v1 clamps to 1 — the point of the
 * split is that {@link #send} is a <em>broadcast over a table</em> and {@link #sendTo} a unicast,
 * rather than both being "the peer". A guest's table is one slot holding the host.
 *
 * <p><b>UDP inbound filter (Phase 20.1), in order.</b> Every stage exists because the stage before it
 * is insufficient on a real network:
 * <ol>
 *   <li><b>Pinned source address.</b> The peer's {@link InetAddress} is taken from the established TCP
 *       connection. Address alone is weak on loopback and behind a shared NAT, where every process
 *       looks like the same host.</li>
 *   <li><b>Envelope prefix parse.</b> {@link CoopMessages#parseDatagramHeader} reads token/sender/type
 *       and nothing more, so a crafted packet cannot make us do work proportional to its size.</li>
 *   <li><b>Session token.</b> The datagram must carry this session's
 *       {@link CoopMessages#wireToken(String)}; with no expected token set (pre-handshake) everything
 *       is dropped. This is the bearer check that replaces "whatever spoke last is the peer".</li>
 * </ol>
 * Each rejection increments a counter and warns at most once per reason per service lifetime — a
 * hostile flood must be visible in {@link #datagramStats()} without being able to write the log.
 *
 * <p><b>Return-address validation (host).</b> A matching token proves the sender knows the session; it
 * does not prove the <em>source address</em> is the peer, because a plaintext token is sniffable
 * on-path and replayable from anywhere. So each link keeps a validated send target separate from
 * whatever address is currently talking. A token-valid datagram from a different source is accepted
 * <em>inbound</em> (the watermark defeats replay) but only becomes a candidate: the host sends it a
 * {@code PATH_PROBE} carrying a fresh random nonce and re-points the stream only when that exact
 * nonce comes back from that exact address — QUIC's {@code PATH_CHALLENGE} model (RFC 9000 §8.2).
 * Until then traffic keeps flowing to the previously validated address, so an off-path attacker
 * cannot redirect the state stream, and a genuine NAT rebind still recovers within a round trip. The
 * guest needs none of this: its target is the address the player configured.
 *
 * <p><b>Abuse handling on an Internet-open port (Phase 20.4).</b> Three bounded defences, all of them
 * counters first and log lines a distant second:
 * <ul>
 *   <li><b>Connection throttle.</b> More than {@link #MAX_CONNECTION_ATTEMPTS_PER_WINDOW} accepts from
 *       one address inside {@link #CONNECTION_ATTEMPT_WINDOW_MILLIS} puts that address in a
 *       {@link #CONNECTION_THROTTLE_COOLDOWN_MILLIS} cooldown, during which its connections are closed
 *       with no reject frame at all. The frame is the expensive half — writing it uses a
 *       <em>blocking</em> socket — so this is precisely the work a flood was buying.</li>
 *   <li><b>Garbage strikes.</b> A connection that has not yet completed a handshake gets
 *       {@link #PRE_SESSION_INVALID_FRAME_LIMIT} undecodable frames before it is dropped. After the
 *       handshake the tolerant decoder's old behaviour stands: a proven session is allowed to
 *       resynchronise.</li>
 *   <li><b>Per-poll ceilings.</b> At most {@link #MAX_DATAGRAMS_PER_POLL} datagrams and
 *       {@link #MAX_FRAMES_PER_POLL} frames are ingested per poll; the rest waits in the kernel
 *       buffer. Nothing is measured or dropped — the only goal is that a flood cannot starve the
 *       campaign frame.</li>
 * </ul>
 *
 * <p><b>Keepalive and ICMP.</b> When a send target exists and the stream has been quiet for
 * {@link #KEEPALIVE_IDLE_MILLIS}, a {@code UDP_PROBE} goes out — it holds the NAT binding open (the
 * literature records a 10 s timeout floor on at least one gateway) and, from the guest, is what lets a
 * host with a quiet stream learn and validate the guest's address at all. ICMP port-unreachable
 * surfaces on Windows as a plain {@code SocketException} for an unconnected channel (JDK-4676710), so
 * both it and {@code PortUnreachableException} are counted as transient link events and the channel
 * stays open; a peer rebooting must not kill the socket loop.
 */
public class CoopNetService {
    /**
     * TCP frame sanity cap. This is corruption protection, not a transport limit — TCP is a stream,
     * so any frame size survives the wire; a frame this large only ever means a corrupted length or a
     * runaway encoder. Raised from 64 KB on 2026-08-20: NPC_FLEET_SET with the Phase 16 per-member
     * hullmod fields crossed 64 KB in a busy sector, and every set rebroadcast was silently discarded
     * by the receiver — the guest's mirror population froze. Must match on both installs (handshake
     * enforces same build). {@link #WARN_FRAME_BYTES} gives the early warning before this cliff.
     */
    static final int MAX_FRAME_BYTES = 1024 * 1024;
    /** Soft threshold: an outbound frame this big logs once per message type, so growth is loud. */
    private static final int WARN_FRAME_BYTES = 256 * 1024;
    /** UDP receive buffer — sized for the datagram path, not the TCP frame cap. */
    private static final int DATAGRAM_BUFFER_BYTES = 64 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;
    /**
     * Hard cap on a composed UDP datagram, in UTF-8 bytes (Phase 20 M4). 1,200 B is the WAN payload
     * budget the whole payload diet is sized to: it clears the smallest MTU any consumer path
     * realistically presents, so a datagram is never IP-fragmented and a 1% link loss stays a 1%
     * datagram loss instead of the 3-4% that fragmenting into 3-4 packets would make it.
     *
     * <p>Nothing should ever reach this check: {@link CoopNetPump#sendStateDatagram} escalates an
     * over-budget datagram onto TCP before it is queued, and the producers pack to fit. It is the
     * backstop for a producer that grows a body without noticing, and the counter is how that shows
     * up as evidence rather than as a mirror that quietly stops moving.
     */
    public static final int MAX_DATAGRAM_BYTES = 1200;
    private static final long CONNECT_RETRY_DELAY_MILLIS = 500L;
    private static final String EXTRA_CONNECTION_REJECT_REASON = "Host already has an active connection";
    /**
     * A held connection that has delivered no inbound bytes for this long is presumed half-open, and a
     * new inbound connection replaces it rather than being rejected (Phase 20.2).
     *
     * <p>The failure this closes: after a NAT drop the host's socket is not closed, it is
     * <em>stranded</em> — the OS keeps retransmitting for one to two minutes before it reports the
     * peer gone. For that whole window the slot is occupied, so the guest's reconnect, which is
     * already knocking every 500 ms, gets "Host already has an active connection" and the grace window
     * expires waiting for a guest that was there the entire time. Ten seconds is past anything the 3 s
     * ping cadence produces on a live link and well inside the 60 s grace default.
     */
    static final long HALF_OPEN_REPLACE_MILLIS = 10_000L;
    /**
     * Idle gap before a {@code UDP_PROBE} goes out. 5 s, not the 10 s first specified: measured NAT UDP
     * timeouts are mostly ≥ 60 s, but a 34-gateway study found one device at 10 s, so 10 s sat on the
     * tail rather than under it.
     */
    static final long KEEPALIVE_IDLE_MILLIS = 5_000L;
    /** Challenge resend cadence while a candidate address keeps talking. */
    private static final long PATH_PROBE_RESEND_MILLIS = 1_000L;
    /** Give up on an unproven candidate after this long without a matching echo. */
    private static final long PATH_CANDIDATE_TIMEOUT_MILLIS = 5_000L;
    /** Rate limit for the transient-link warning; a router resetting can produce a burst of them. */
    private static final long ICMP_WARN_INTERVAL_MILLIS = 10_000L;
    /** {@code PATH_PROBE} body prefixes: challenge out, echo back. */
    private static final String PATH_CHALLENGE_PREFIX = "C:";
    private static final String PATH_ECHO_PREFIX = "R:";
    /** Nonce width in hex characters; 16 hex = 64 unpredictable bits, QUIC's PATH_CHALLENGE is 64. */
    private static final int PATH_NONCE_HEX_CHARS = 16;
    /**
     * Backlog at which newest-wins coalescing switches on (Phase 20.1 M2). Below it the queue is
     * byte-identical to what it always was — on localhost the socket drains every frame and nothing
     * is ever coalesced, so the LAN behaviour this project's whole test corpus was tuned against is
     * untouched. 32 queued messages is already several frames of arrears at the 1-5 Hz control rates.
     */
    static final int COALESCE_BACKLOG_MESSAGES = 32;
    /** Queue depth that gets a one-time warning. Nothing is dropped; this is a "look here" marker. */
    static final int QUEUE_DEPTH_WARN_MESSAGES = 1_024;

    // ---- Phase 20.4 abuse limits -------------------------------------------------------------------

    /** Accepts from one address inside {@link #CONNECT_RETRY_DELAY_MILLIS}-paced retries stay under this. */
    static final int MAX_CONNECTION_ATTEMPTS_PER_WINDOW = 5;
    /** Sliding window the attempt count is measured over. */
    static final long CONNECTION_ATTEMPT_WINDOW_MILLIS = 10_000L;
    /** How long an address stays throttled once it crosses the limit. */
    static final long CONNECTION_THROTTLE_COOLDOWN_MILLIS = 30_000L;
    /** Undecodable frames a pre-handshake connection is allowed before it is dropped. */
    static final int PRE_SESSION_INVALID_FRAME_LIMIT = 20;
    /** Inbound ceilings per poll, so a flood waits in the kernel buffer instead of in our frame. */
    static final int MAX_DATAGRAMS_PER_POLL = 256;
    static final int MAX_FRAMES_PER_POLL = 256;
    /**
     * Cap on remembered attempt records. A spray from rotating source addresses must not be able to
     * grow a map inside the campaign process; the oldest entry is evicted, which at worst forgives an
     * address that stopped knocking long enough for 256 others to knock.
     */
    private static final int MAX_ATTEMPT_RECORDS = 256;

    /** Allocated once: the frame framer takes a callback and this one carries no per-peer state. */
    private static final Runnable OVERSIZED_FRAME_WARNING =
            () -> CoopLog.warn(CoopNetService.class, "Coop TCP received oversized frame");

    private final Queue<CoopMessages.Message> inbound = new ConcurrentLinkedQueue<>();
    // High-frequency state datagrams (UDP). Kept separate from the reliable TCP control queues.
    // Netty is deliberately avoided here: Starsector's script sandbox blocks Netty's reflection
    // (see CoopNetServiceSandboxCompatibilityTest), so coop networking uses java.nio throughout.
    private final Queue<String> inboundDatagrams = new ConcurrentLinkedQueue<>();
    // One warning per message type per service lifetime; guarded by lifecycleLock (flush path).
    private final java.util.Set<CoopMessages.Type> largeFrameWarned =
            java.util.EnumSet.noneOf(CoopMessages.Type.class);
    private final AtomicLong nextSeq = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final ByteBuffer datagramBuffer = ByteBuffer.allocate(DATAGRAM_BUFFER_BYTES);
    /** Wall clock; injectable so keepalive and challenge timing are testable without sleeping. */
    private final LongSupplier clockMillis;
    private final SecureRandom nonceSource = new SecureRandom();

    /**
     * The peer table. Fixed length: slots are pre-created and reused rather than added and removed,
     * so a queue written while nothing is attached still has somewhere to live — which is exactly
     * what the pre-connection and post-drop windows rely on.
     */
    private final List<CoopPeerLink> peers;
    /** Per-source connection-attempt bookkeeping; see {@link AttemptRecord}. */
    private final Map<InetAddress, AttemptRecord> attemptsByAddress = new LinkedHashMap<>();

    private CoopConnectionRole role = CoopConnectionRole.NONE;
    private ServerSocketChannel serverChannel;
    /**
     * Cached answer for {@link #isConnected()}. Derived from the peer table by
     * {@link #refreshConnectedLocked()}, which every path that can change it calls; volatile so the
     * fast path can read it without taking {@code lifecycleLock}.
     */
    private volatile boolean connected;
    private SocketChannel pendingConnectChannel;
    private DatagramChannel udpChannel;
    private boolean noTokenWarned;
    private boolean tokenMismatchWarned;
    private boolean malformedDatagramWarned;
    /** Session token every inbound datagram must carry; null before handshake and after teardown. */
    private String expectedSessionToken;
    /** Full local player id stamped onto outbound TCP messages (Phase 20.5). */
    private String localSenderId;
    /** {@link CoopMessages#wireToken} of {@link #localSenderId}, for the datagram envelope. */
    private String localDatagramSenderId = "";
    private long lastIcmpWarnAtMillis;
    private long droppedNoToken;
    private long droppedTokenMismatch;
    private long droppedForeignSource;
    private long droppedMalformed;
    private long probesSent;
    private long probeEchoesReceived;
    private long pathValidations;
    private long keepalivesSent;
    private long keepalivesReceived;
    private long icmpTransients;
    private long oversizedDatagrams;
    /** Composed datagrams the pump rerouted onto TCP for exceeding {@link #MAX_DATAGRAM_BYTES}. */
    private long escalatedToTcp;
    private long connectionAttempts;
    private long connectionsThrottled;
    private long invalidFrames;
    private long connectionsDroppedForGarbage;
    private String connectHost;
    private int connectPort;
    private long nextConnectAttemptAtMillis;
    private boolean connectFailureLogged;
    /** Frames ingested so far in the current {@link #pollNetworkLocked()}; see {@link #MAX_FRAMES_PER_POLL}. */
    private int framesThisPoll;

    public CoopNetService() {
        this(System::currentTimeMillis);
    }

    /** Test seam: a fake clock drives the keepalive and challenge timers without real waiting. */
    CoopNetService(LongSupplier clockMillis) {
        this(clockMillis, CoopNetStartupConfig.maxGuestsFromSystemProperties());
    }

    /** Test seam: an explicit peer capacity, for the routing tests Phase 27 will grow into. */
    CoopNetService(LongSupplier clockMillis, int peerCapacity) {
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
        int capacity = Math.max(1, peerCapacity);
        List<CoopPeerLink> table = new ArrayList<>(capacity);
        for (int slot = 0; slot < capacity; slot++) {
            table.add(new CoopPeerLink(slot, MAX_FRAME_BYTES));
        }
        this.peers = List.copyOf(table);
    }

    /** How many peers this transport will hold at once; 1 in v1 (see {@code coop.maxGuests}). */
    public int peerCapacity() {
        return peers.size();
    }

    /** Test seam: frames ingested by the most recent poll, for the per-poll ceiling. */
    int framesInLastPoll() {
        synchronized (lifecycleLock) {
            return framesThisPoll;
        }
    }

    /**
     * Sets the full player id stamped onto outbound TCP messages that do not already carry one, and
     * derives the short sender id used in the datagram envelope. Called once per session start; a null
     * clears both (session teardown).
     */
    public void setLocalSenderId(String playerId) {
        synchronized (lifecycleLock) {
            localSenderId = playerId;
            localDatagramSenderId = playerId == null ? "" : CoopMessages.wireToken(playerId);
        }
    }

    /**
     * Sets the session token every inbound datagram must carry, and that every outbound transport
     * datagram is stamped with. Null (pre-handshake, post-teardown) means "drop all datagrams": before
     * a session exists there is nothing legitimate to receive, and accepting anything then is exactly
     * the hole the token closes.
     */
    public void setExpectedSessionToken(String token) {
        synchronized (lifecycleLock) {
            expectedSessionToken = token;
            long now = clockMillis.getAsLong();
            for (CoopPeerLink peer : peers) {
                // A fresh token means a fresh session: an address validated for the previous one proves
                // nothing about this one, and the keepalive timer restarts from now rather than firing
                // immediately on the strength of a long-idle previous session.
                if (token != null) {
                    peer.noteDatagramSent(now);
                }
                peer.forgetCandidate();
            }
        }
    }

    /**
     * Records that one composed datagram went out over TCP instead of UDP because it exceeded
     * {@link #MAX_DATAGRAM_BYTES} (Phase 20 M4). The decision belongs to the pump — it owns the
     * transport router — but the counter belongs here beside the other datagram evidence, so
     * {@code LINK_STATUS} and the connection doctor report one set of numbers.
     */
    public void noteDatagramEscalatedToTcp() {
        synchronized (lifecycleLock) {
            escalatedToTcp++;
        }
    }

    /** Immutable snapshot of the UDP transport counters; see {@link CoopDatagramStats}. */
    public CoopDatagramStats datagramStats() {
        synchronized (lifecycleLock) {
            long lastInbound = 0L;
            String validated = "";
            for (CoopPeerLink peer : peers) {
                lastInbound = Math.max(lastInbound, peer.lastInboundDatagramAtMillis());
                if (validated.isEmpty() && peer.validatedUdpAddress() != null) {
                    validated = peer.validatedUdpAddress().toString();
                }
            }
            return new CoopDatagramStats(droppedNoToken, droppedTokenMismatch, droppedForeignSource,
                    droppedMalformed, probesSent, probeEchoesReceived, pathValidations, keepalivesSent,
                    keepalivesReceived, icmpTransients, oversizedDatagrams, escalatedToTcp,
                    connectionAttempts, connectionsThrottled, invalidFrames, connectionsDroppedForGarbage,
                    lastInbound, validated);
        }
    }

    public void startHost(int port) {
        synchronized (lifecycleLock) {
            shutdownLocked();
            role = CoopConnectionRole.HOST;
            try {
                ServerSocketChannel channel = ServerSocketChannel.open();
                channel.configureBlocking(false);
                channel.socket().setReuseAddress(true);
                channel.bind(new InetSocketAddress(port));
                serverChannel = channel;
                openUdpLocked(new InetSocketAddress(port), null);
                CoopLog.info(CoopNetService.class, "Coop TCP host listening on port " + port
                        + " (peer capacity " + peers.size() + ")");
            } catch (Exception ex) {
                shutdownLocked();
                throw new IllegalStateException("Unable to start coop TCP host on port " + port, ex);
            }
        }
    }

    public void connect(String host, int port) {
        synchronized (lifecycleLock) {
            shutdownLocked();
            role = CoopConnectionRole.GUEST;
            connectHost = host;
            connectPort = port;
            nextConnectAttemptAtMillis = 0L;
            connectFailureLogged = false;
            // Guest binds an ephemeral UDP port and sends to the host's known address; the host
            // learns the guest's UDP address from the first datagram it receives.
            openUdpLocked(new InetSocketAddress(0), new InetSocketAddress(host, port));
            pollNetworkLocked();
        }
    }

    public CoopConnectionRole role() {
        synchronized (lifecycleLock) {
            return role;
        }
    }

    /**
     * Whether any peer's TCP channel is up. Reads a cached flag while connected — the pump and the
     * battle bridge ask this ~11 times a frame, and every one of those calls used to run a full
     * {@link #pollNetworkLocked()} (accept + read loop + datagram receive), which is where most of the
     * measured ~3000 socket syscalls/s came from (perf audit #10).
     *
     * <p>The flag is refreshed by {@link #refreshConnectedLocked()} from every path that can change the
     * answer: every poll, and every mutation of the peer table (attach, close, shutdown). A peer
     * that vanishes is only ever discovered inside a poll (a {@code read} of -1), and the pump polls at
     * the head of its frame via {@link #flushOutbound()} — immediately before {@code detectPeerDisconnect}
     * reads this — so a transition is still observed on the frame it happens.
     *
     * <p>While the flag is false this still polls, and deliberately: a connection can only be
     * <em>established</em> inside a poll (host accept, guest connect-retry), and callers spinning on
     * this to wait for a peer — including a guest reconnecting mid-battle, when the campaign pump is
     * not running to poll for it — must keep driving that.
     */
    public boolean isConnected() {
        if (connected) {
            return true;
        }
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            return connected;
        }
    }

    public long nextSeq() {
        return nextSeq.incrementAndGet();
    }

    /**
     * Broadcasts a TCP message to every peer, stamping the local {@code senderId} when the message
     * does not already carry one. Stamping here rather than in the ~50 factories keeps the id out of
     * every call site and means one seam owns "who sent this"; an explicitly stamped message (a
     * relay, later) is left alone.
     *
     * <p>With the v1 capacity of 1 this is exactly what it always was: one queue, one socket.
     */
    public void send(CoopMessages.Message message) {
        if (message == null) {
            return;
        }
        synchronized (lifecycleLock) {
            CoopMessages.Message stamped = message.withSenderId(localSenderId);
            for (CoopPeerLink peer : peers) {
                queueLocked(peer, stamped);
            }
        }
    }

    /**
     * Unicasts a TCP message to the peer whose {@link CoopPeerLink#senderId()} matches, and falls
     * back to {@link #send} when it does not match anyone.
     *
     * <p>The fallback is the load-bearing half. A request/response pair is only addressable once the
     * requester has stamped a message we have seen — which is true for everything after the lobby
     * exchange, and false for the exchange itself. Rather than make every caller reason about that,
     * an unknown or null id means "everyone", which with capacity 1 is the same peer anyway.
     */
    public void sendTo(String senderId, CoopMessages.Message message) {
        if (message == null) {
            return;
        }
        synchronized (lifecycleLock) {
            CoopPeerLink target = peerBySenderIdLocked(senderId);
            if (target != null) {
                queueLocked(target, message.withSenderId(localSenderId));
                return;
            }
        }
        // Routed through send() rather than repeating its body: send() is the one seam every
        // subclass and instrumentation hooks, and a fallback that bypassed it would make "unicast to
        // an unnamed peer" invisible to everything watching the broadcast path.
        send(message);
    }

    private void queueLocked(CoopPeerLink peer, CoopMessages.Message stamped) {
        if (!peer.backlogged(COALESCE_BACKLOG_MESSAGES)
                || !peer.replaceQueued(stamped, coalesceKey(stamped))) {
            peer.enqueue(stamped);
        }
        if (peer.outboundDepth() > QUEUE_DEPTH_WARN_MESSAGES && peer.shouldWarnQueueDepth()) {
            CoopLog.warn(CoopNetService.class, "Coop TCP outbound queue for peer slot " + peer.slot()
                    + " is " + peer.outboundDepth() + " messages deep (warn threshold "
                    + QUEUE_DEPTH_WARN_MESSAGES
                    + "); the peer's socket is not draining. Nothing is dropped — superseded"
                    + " snapshots coalesce, events queue.");
        }
    }

    private CoopPeerLink peerBySenderIdLocked(String senderId) {
        if (senderId == null || senderId.isEmpty()) {
            return null;
        }
        for (CoopPeerLink peer : peers) {
            if (senderId.equals(peer.senderId())) {
                return peer;
            }
        }
        return null;
    }

    /**
     * Deepest outbound TCP queue in the table. Read by the pump for {@code LINK_STATUS} and by tests;
     * it is the one honest measure of whether a peer's socket is keeping up, and the worst peer is
     * the one worth reporting.
     */
    public int outboundQueueDepth() {
        synchronized (lifecycleLock) {
            int deepest = 0;
            for (CoopPeerLink peer : peers) {
                deepest = Math.max(deepest, peer.outboundDepth());
            }
            return deepest;
        }
    }

    /**
     * Coalescing identity, or null for "never coalesce this".
     *
     * <p>The whitelist is only whole-state snapshots, where an older copy carries strictly less
     * information than the newer one that superseded it. Everything else — claims, deltas, results,
     * world deltas, lifecycle, handshakes — is a semantic event whose loss changes the outcome of the
     * game, and none of it is ever dropped no matter how deep the queue gets.
     *
     * <p>{@code STATE_DATAGRAM} keys on the <em>wrapped</em> datagram's type and sender, because one
     * TCP-fallback stream carries two independent streams ({@code FLEET_SNAPSHOT} and
     * {@code NPC_FLEET_MOTION}) from potentially several senders, and superseding across them would
     * silently censor one stream with another. An unparseable wrapper keys as null: something we
     * cannot identify is something we must not throw away.
     */
    static String coalesceKey(CoopMessages.Message message) {
        switch (message.type()) {
            case TIME_SNAPSHOT, NPC_FLEET_SET, PLAYER_REP_SNAPSHOT, MISSION_POOL_SNAPSHOT, LINK_STATUS -> {
                return message.type().name();
            }
            case STATE_DATAGRAM -> {
                try {
                    CoopMessages.DatagramHeader header =
                            CoopMessages.parseDatagramHeader(CoopMessages.parseStateDatagram(message));
                    return "STATE_DATAGRAM|" + header.type().name() + '|' + header.senderId();
                } catch (RuntimeException ex) {
                    return null;
                }
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Polls the network, then writes everything queued. This is the frame's poll: the pump calls it at
     * the head and the tail of {@code advance()}, so everything that arrived before the frame started
     * is already queued by the time the drains below run.
     */
    public void flushOutbound() {
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            for (CoopPeerLink peer : peers) {
                flushOutboundLocked(peer);
                maybeChallengeCandidateLocked(peer);
                maybeQueueKeepaliveLocked(peer);
                flushDatagramsLocked(peer);
            }
        }
    }

    /**
     * Next queued TCP message, or null. Drains the queue first and only polls when it runs dry, rather
     * than polling on every call (perf audit #10): the drain loop that empties a 40-message backlog
     * used to cost 40 accept+read+recvfrom passes.
     *
     * <p>The delivered sequence is unchanged. The frame's poll already happened in
     * {@link #flushOutbound()}, so everything that arrived before the frame started is in the queue;
     * the dry poll at the end of the drain is what still lets a message that landed <em>during</em> the
     * frame be processed by it, exactly as before.
     */
    public CoopMessages.Message pollInbound() {
        synchronized (lifecycleLock) {
            CoopMessages.Message queued = inbound.poll();
            if (queued != null) {
                return queued;
            }
            pollNetworkLocked();
            return inbound.poll();
        }
    }

    /** Queues a best-effort UDP datagram for every peer (high-frequency state). */
    public void sendDatagram(String payload) {
        if (payload == null) {
            return;
        }
        synchronized (lifecycleLock) {
            for (CoopPeerLink peer : peers) {
                peer.enqueueDatagram(payload);
            }
        }
    }

    /** Unicast form of {@link #sendDatagram}; an unknown sender id broadcasts, as for {@link #sendTo}. */
    public void sendDatagramTo(String senderId, String payload) {
        if (payload == null) {
            return;
        }
        synchronized (lifecycleLock) {
            CoopPeerLink target = peerBySenderIdLocked(senderId);
            if (target != null) {
                target.enqueueDatagram(payload);
                return;
            }
        }
        sendDatagram(payload);
    }

    /** Returns the next received UDP datagram payload, or null. Drains first, polls dry — see
     * {@link #pollInbound()} for why that preserves what a frame ingests. */
    public String pollDatagram() {
        synchronized (lifecycleLock) {
            String queued = inboundDatagrams.poll();
            if (queued != null) {
                return queued;
            }
            pollNetworkLocked();
            return inboundDatagrams.poll();
        }
    }

    /**
     * Closes every active TCP channel on purpose, keeping the role, the listening socket and (on the
     * guest) the retry configuration. Phase 20.2's link-death verdict calls this so the ordinary
     * disconnect edge fires <em>now</em> instead of whenever the OS finishes retransmitting into a
     * black hole — the guest's 500 ms retry then starts immediately, and the host opens its grace
     * window while the world is still worth resuming.
     *
     * <p>Deliberately routed through the same close path a peer-initiated drop takes, so nothing
     * downstream can tell the two apart and no second teardown shape has to be maintained.
     *
     * @param reason logged; the verdict's numbers, so a log reader can tell a deliberate drop from a
     *               socket the peer closed
     * @return true when at least one channel was actually closed
     */
    public boolean dropActiveConnection(String reason) {
        synchronized (lifecycleLock) {
            boolean dropped = false;
            for (CoopPeerLink peer : peers) {
                if (!peer.occupied()) {
                    continue;
                }
                CoopLog.info(CoopNetService.class, "Coop TCP dropping the active connection as " + role
                        + ": " + (reason == null || reason.isEmpty() ? "no reason given" : reason));
                closeLinkLocked(peer);
                dropped = true;
            }
            return dropped;
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            shutdownLocked();
        }
    }

    private void pollNetworkLocked() {
        framesThisPoll = 0;
        try {
            acceptHostConnectionLocked();
            progressGuestConnectionLocked();
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP polling failed", ex);
            closeChannel(pendingConnectChannel);
            pendingConnectChannel = null;
        }
        for (CoopPeerLink peer : peers) {
            readAvailableLocked(peer);
        }
        readDatagramsLocked();
        refreshConnectedLocked();
    }

    /** Recomputes the {@link #isConnected()} cache from the peer table. Cheap: one pass, two reads. */
    private void refreshConnectedLocked() {
        boolean any = false;
        for (CoopPeerLink peer : peers) {
            if (peer.channelLive()) {
                any = true;
                break;
            }
        }
        connected = any;
    }

    private void openUdpLocked(InetSocketAddress bindAddress, SocketAddress remoteAddress) {
        try {
            DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.socket().setReuseAddress(true);
            // Deliberately not connect()ed: a connected DatagramChannel would make every ICMP
            // port-unreachable a hard error on the next operation, which is the failure mode
            // the ICMP-tolerance rule exists to avoid.
            channel.bind(bindAddress);
            udpChannel = channel;
            long now = clockMillis.getAsLong();
            for (CoopPeerLink peer : peers) {
                peer.setValidatedUdpAddress(remoteAddress);
                peer.noteDatagramSent(now);
            }
            CoopLog.info(CoopNetService.class, "Coop UDP datagram channel bound to " + bindAddress);
        } catch (Exception ex) {
            udpChannel = null;
            for (CoopPeerLink peer : peers) {
                peer.setValidatedUdpAddress(null);
            }
            CoopLog.warn(CoopNetService.class, "Coop UDP datagram channel unavailable; "
                    + "campaign state stream disabled (TCP control unaffected)", ex);
        }
    }

    private void readDatagramsLocked() {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            return;
        }
        int received = 0;
        while (received < MAX_DATAGRAMS_PER_POLL) {
            SocketAddress source;
            try {
                datagramBuffer.clear();
                source = channel.receive(datagramBuffer);
            } catch (Exception ex) {
                if (isTransientLinkException(ex)) {
                    // ICMP port-unreachable and friends. The channel is fine; the peer (or a router on
                    // the way) is momentarily not. Stop draining this poll and try again next frame.
                    noteTransientLinkEventLocked("receive", ex);
                } else {
                    CoopLog.warn(CoopNetService.class, "Coop UDP receive failed", ex);
                }
                return;
            }
            if (source == null) {
                return;
            }
            received++;
            if (!datagramBuffer.hasRemaining()) {
                // Buffer filled to capacity: the datagram was at least as large as the buffer and may
                // be truncated. Decoding it would yield a corrupt payload, so discard it.
                droppedMalformed++;
                CoopLog.warn(CoopNetService.class, "Coop UDP discarding truncated datagram from " + source
                        + " (filled the " + datagramBuffer.capacity() + "-byte buffer)");
                continue;
            }
            datagramBuffer.flip();
            byte[] bytes = new byte[datagramBuffer.remaining()];
            datagramBuffer.get(bytes);
            acceptDatagramLocked(source, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /**
     * Runs the three-stage inbound filter (see the class Javadoc) and either queues the payload or
     * counts the rejection. Nothing learned from a packet — not the return address, not a candidate —
     * happens before all three stages pass.
     */
    private void acceptDatagramLocked(SocketAddress source, String payload) {
        CoopPeerLink peer = peerForSourceLocked(source);
        if (peer == null) {
            droppedForeignSource++;
            CoopPeerLink first = peers.get(0);
            if (first.shouldWarnForeignSource()) {
                CoopLog.warn(CoopNetService.class, "Coop UDP ignoring datagram from non-peer source "
                        + source + " (no peer link is pinned to it)");
            }
            return;
        }

        CoopMessages.DatagramHeader header;
        try {
            header = CoopMessages.parseDatagramHeader(payload);
        } catch (RuntimeException ex) {
            droppedMalformed++;
            if (!malformedDatagramWarned) {
                malformedDatagramWarned = true;
                CoopLog.warn(CoopNetService.class,
                        "Coop UDP dropping datagram with an unreadable envelope from " + source, ex);
            }
            return;
        }

        if (expectedSessionToken == null) {
            droppedNoToken++;
            if (!noTokenWarned) {
                noTokenWarned = true;
                CoopLog.warn(CoopNetService.class, "Coop UDP dropping datagram from " + source
                        + " before a session token exists (type " + header.type() + ")");
            }
            return;
        }
        if (!expectedSessionToken.equals(header.token())) {
            droppedTokenMismatch++;
            if (!tokenMismatchWarned) {
                tokenMismatchWarned = true;
                CoopLog.warn(CoopNetService.class, "Coop UDP dropping datagram from " + source
                        + " with a foreign session token (type " + header.type() + ")");
            }
            return;
        }

        peer.noteInboundDatagram(clockMillis.getAsLong());
        switch (header.type()) {
            case UDP_PROBE -> keepalivesReceived++;
            case PATH_PROBE -> handlePathProbeLocked(peer, source, payload);
            // Transport-level types are handled here and never reach the pump; everything else is
            // gameplay state and goes to the drain exactly as before.
            default -> inboundDatagrams.add(payload);
        }
        noteValidatedSourceLocked(peer, source);
    }

    /**
     * The link a datagram belongs to: the one pinned to its source address, or failing that an
     * unpinned link (no TCP connection yet, where the session token is the only proof available).
     */
    private CoopPeerLink peerForSourceLocked(SocketAddress source) {
        CoopPeerLink unpinned = null;
        for (CoopPeerLink peer : peers) {
            if (peer.pinnedPeerAddress() == null) {
                if (unpinned == null) {
                    unpinned = peer;
                }
                continue;
            }
            if (peer.acceptsSource(source)) {
                return peer;
            }
        }
        return unpinned;
    }

    /**
     * Address bookkeeping for a token-valid datagram (host only). A source that is already the send
     * target needs nothing; anything else is unproven and becomes the challenge candidate, which is
     * what stops a replayed-token packet from a hostile source redirecting the stream.
     */
    private void noteValidatedSourceLocked(CoopPeerLink peer, SocketAddress source) {
        if (role != CoopConnectionRole.HOST || source == null) {
            return;
        }
        if (source.equals(peer.validatedUdpAddress()) || source.equals(peer.candidateUdpAddress())) {
            return;
        }
        peer.beginCandidate(source, newNonceLocked(), clockMillis.getAsLong());
    }

    /** Sends (or resends) the outstanding challenge, and forgets a candidate that never answers. */
    private void maybeChallengeCandidateLocked(CoopPeerLink peer) {
        if (peer.candidateUdpAddress() == null || udpChannel == null || expectedSessionToken == null) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now - peer.candidateFirstSeenAtMillis() >= PATH_CANDIDATE_TIMEOUT_MILLIS) {
            if (peer.shouldLogCandidateTimeout()) {
                CoopLog.info(CoopNetService.class, "Coop UDP candidate " + peer.candidateUdpAddress()
                        + " never echoed its path challenge; keeping "
                        + (peer.validatedUdpAddress() == null ? "<no target>" : peer.validatedUdpAddress()));
            }
            peer.forgetCandidate();
            return;
        }
        if (peer.candidateLastProbeAtMillis() != 0L
                && now - peer.candidateLastProbeAtMillis() < PATH_PROBE_RESEND_MILLIS) {
            return;
        }
        peer.noteCandidateProbed(now);
        probesSent++;
        sendDatagramToLocked(peer, peer.candidateUdpAddress(), CoopMessages.datagram(expectedSessionToken,
                localDatagramSenderId, CoopMessages.Type.PATH_PROBE, 0L, 0L,
                PATH_CHALLENGE_PREFIX + peer.candidateNonce()));
    }

    /**
     * Answers a challenge, or completes one. The echo is only believed from the address that was
     * challenged: a nonce that comes back from somewhere else proves an on-path observer, not a peer.
     */
    private void handlePathProbeLocked(CoopPeerLink peer, SocketAddress source, String payload) {
        String body = lastSectionBodyOrEmpty(payload);
        if (body.startsWith(PATH_CHALLENGE_PREFIX)) {
            // Answer immediately over the normal outbound path (guest: to its configured host; host:
            // to its validated target, or dropped when it has none).
            peer.enqueueDatagram(CoopMessages.datagram(expectedSessionToken, localDatagramSenderId,
                    CoopMessages.Type.PATH_PROBE, 0L, 0L,
                    PATH_ECHO_PREFIX + body.substring(PATH_CHALLENGE_PREFIX.length())));
            return;
        }
        if (!body.startsWith(PATH_ECHO_PREFIX)) {
            return;
        }
        probeEchoesReceived++;
        if (!peer.completeCandidate(source, body.substring(PATH_ECHO_PREFIX.length()))) {
            return;
        }
        pathValidations++;
        // Rare by construction (session start, NAT rebind, reconnect), so this logs every time: when a
        // WAN session goes quiet, "which address are we streaming to" is the first question.
        CoopLog.info(CoopNetService.class, "Coop UDP return address validated " + peer.validatedUdpAddress());
    }

    /** Body of the last section, or "" — transport datagrams carry exactly one section. */
    private static String lastSectionBodyOrEmpty(String payload) {
        try {
            java.util.List<CoopMessages.DatagramSection> sections =
                    CoopMessages.parseDatagram(payload).sections();
            return sections.isEmpty() ? "" : sections.get(sections.size() - 1).body();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String newNonceLocked() {
        StringBuilder nonce = new StringBuilder(PATH_NONCE_HEX_CHARS);
        byte[] bytes = new byte[PATH_NONCE_HEX_CHARS / 2];
        nonceSource.nextBytes(bytes);
        for (byte b : bytes) {
            nonce.append(Character.forDigit((b >>> 4) & 0x0f, 16));
            nonce.append(Character.forDigit(b & 0x0f, 16));
        }
        return nonce.toString();
    }

    /** Queues a keepalive when the outbound stream has gone quiet; see {@link #KEEPALIVE_IDLE_MILLIS}. */
    private void maybeQueueKeepaliveLocked(CoopPeerLink peer) {
        if (udpChannel == null || peer.validatedUdpAddress() == null || expectedSessionToken == null) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now - peer.lastDatagramSentAtMillis() < KEEPALIVE_IDLE_MILLIS) {
            return;
        }
        keepalivesSent++;
        peer.enqueueDatagram(CoopMessages.datagram(expectedSessionToken, localDatagramSenderId,
                CoopMessages.Type.UDP_PROBE, 0L, 0L, ""));
    }

    /**
     * True for the socket errors an ICMP rejection produces. The JDK documents
     * {@code PortUnreachableException} for <em>connected</em> datagram sockets only and does not
     * guarantee it even there; on Windows the same condition surfaces as a plain
     * {@code SocketException} (JDK-4676710). Both are link weather, never a reason to close a channel.
     */
    static boolean isTransientLinkException(Throwable ex) {
        return ex instanceof java.net.PortUnreachableException || ex instanceof java.net.SocketException;
    }

    private void noteTransientLinkEventLocked(String operation, Exception ex) {
        icmpTransients++;
        long now = clockMillis.getAsLong();
        if (lastIcmpWarnAtMillis == 0L || now - lastIcmpWarnAtMillis >= ICMP_WARN_INTERVAL_MILLIS) {
            lastIcmpWarnAtMillis = now;
            CoopLog.warn(CoopNetService.class, "Coop UDP transient link error on " + operation
                    + " (" + icmpTransients + " so far); channel kept open", ex);
        }
    }

    private static InetAddress peerAddressOf(SocketChannel channel) {
        try {
            SocketAddress remote = channel.getRemoteAddress();
            return remote instanceof InetSocketAddress inet ? inet.getAddress() : null;
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop could not read TCP peer address for UDP pinning", ex);
            return null;
        }
    }

    private void flushDatagramsLocked(CoopPeerLink peer) {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            peer.outboundDatagrams().clear();
            return;
        }
        SocketAddress remote = peer.validatedUdpAddress();
        if (remote == null) {
            // No validated peer address yet (host before the challenge-echo completes). Drop; the next
            // 10 Hz snapshot supersedes anything queued, so there is no value in buffering stale state.
            peer.outboundDatagrams().clear();
            return;
        }

        String payload;
        while ((payload = peer.outboundDatagrams().poll()) != null) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_DATAGRAM_BYTES) {
                oversizedDatagrams++;
                CoopLog.warn(CoopNetService.class,
                        "Coop UDP dropping oversized datagram (" + bytes.length + " bytes)");
                continue;
            }
            // Dev wiretap (dormant unless -Dcoop.debug.wiretap=true / $coopWiretap): hooked here
            // rather than at sendDatagram so the size histogram measures datagrams that actually
            // reach the socket, at the exact byte length the wire sees. Disabled, this is one static
            // boolean read.
            CoopWiretap.noteSend(payload, bytes.length);
            sendDatagramBytesLocked(peer, remote, bytes);
        }
    }

    /** Sends one datagram to an explicit address — the challenge path, which cannot use the queue. */
    private void sendDatagramToLocked(CoopPeerLink peer, SocketAddress remote, String payload) {
        if (udpChannel == null || remote == null) {
            return;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        CoopWiretap.noteSend(payload, bytes.length);
        sendDatagramBytesLocked(peer, remote, bytes);
    }

    private void sendDatagramBytesLocked(CoopPeerLink peer, SocketAddress remote, byte[] bytes) {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            return;
        }
        try {
            channel.send(ByteBuffer.wrap(bytes), remote);
            peer.noteDatagramSent(clockMillis.getAsLong());
        } catch (Exception ex) {
            if (isTransientLinkException(ex)) {
                noteTransientLinkEventLocked("send", ex);
                return;
            }
            if (peer.shouldWarnDatagramSendFailure()) {
                CoopLog.warn(CoopNetService.class, "Coop UDP send failed; dropping datagram", ex);
            }
        }
    }

    // ---- TCP accept / connect ----------------------------------------------------------------------

    private void acceptHostConnectionLocked() throws Exception {
        if (role != CoopConnectionRole.HOST || serverChannel == null) {
            return;
        }

        // One accept per poll, as it always was: the kernel's listen backlog is a better place for a
        // flood to wait than this method is, and the pump polls twice a frame.
        SocketChannel accepted = serverChannel.accept();
        if (accepted != null) {
            handleAcceptedConnectionLocked(accepted);
        }
    }

    private void handleAcceptedConnectionLocked(SocketChannel accepted) throws Exception {
        connectionAttempts++;
        long now = clockMillis.getAsLong();
        InetAddress source = peerAddressOf(accepted);
        boolean throttled = noteConnectionAttemptLocked(source, now);

        CoopPeerLink free = freeSlotLocked();
        if (free == null) {
            CoopPeerLink stale = staleSlotLocked(now);
            if (stale != null) {
                // The held channel is presumed half-open (see HALF_OPEN_REPLACE_MILLIS): close it so
                // the new connection can attach, rather than making a reconnecting guest wait out the
                // OS retransmit timeout. A live peer cannot land here — it pings every 3 s.
                CoopLog.info(CoopNetService.class, "Coop TCP replacing a presumed half-open connection"
                        + " on peer slot " + stale.slot() + " (no inbound bytes for "
                        + (now - stale.lastInboundFrameAtMillis()) + " ms) with the newly accepted one");
                closeLinkLocked(stale);
                free = stale;
            }
        }

        if (free == null) {
            // Nothing can be done with this connection, which is exactly the case the throttle is for:
            // the reject frame below is written on a *blocking* socket, and that is the work a flood
            // was buying. A throttled address gets no frame and no work at all.
            if (throttled) {
                connectionsThrottled++;
                logThrottleOnceLocked(source, now);
                closeChannel(accepted);
                return;
            }
            CoopLog.warn(CoopNetService.class, "Coop TCP rejecting extra connection");
            rejectExtraConnectionLocked(accepted);
            return;
        }

        attachChannelLocked(free, accepted);
    }

    /**
     * Counts one accepted connection from {@code source} and reports whether that address is in its
     * cooldown. Deliberately does <em>not</em> decide the connection's fate: an attempt that can be
     * admitted into a free slot is always admitted, because a legitimate guest reconnecting every
     * 500 ms through a half-open window would otherwise throttle itself out of its own grace period.
     * The throttle only ever suppresses the reject path, which is the expensive one.
     */
    private boolean noteConnectionAttemptLocked(InetAddress source, long now) {
        if (source == null) {
            return false;
        }
        AttemptRecord record = attemptsByAddress.get(source);
        if (record == null) {
            record = new AttemptRecord();
            if (attemptsByAddress.size() >= MAX_ATTEMPT_RECORDS) {
                Iterator<Map.Entry<InetAddress, AttemptRecord>> oldest =
                        attemptsByAddress.entrySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            attemptsByAddress.put(source, record);
        }
        if (now < record.throttledUntilMillis) {
            // Deliberately not counted. Counting knocks that are already being refused would let a
            // peer that keeps retrying — which is exactly what the guest's own 500 ms reconnect loop
            // does — arrive at the end of its cooldown already over the limit, and never get out.
            return true;
        }
        if (record.throttledUntilMillis != 0L) {
            record.throttledUntilMillis = 0L;
            record.windowStartMillis = now;
            record.attempts = 0;
        }
        if (now - record.windowStartMillis >= CONNECTION_ATTEMPT_WINDOW_MILLIS) {
            record.windowStartMillis = now;
            record.attempts = 0;
        }
        record.attempts++;
        if (record.attempts > MAX_CONNECTION_ATTEMPTS_PER_WINDOW) {
            record.throttledUntilMillis = now + CONNECTION_THROTTLE_COOLDOWN_MILLIS;
            record.throttleLogged = false;
            return true;
        }
        return false;
    }

    /** One line per address per cooldown; a flood must not be able to write the log. */
    private void logThrottleOnceLocked(InetAddress source, long now) {
        AttemptRecord record = source == null ? null : attemptsByAddress.get(source);
        if (record == null || record.throttleLogged) {
            return;
        }
        record.throttleLogged = true;
        CoopLog.warn(CoopNetService.class, "Coop TCP throttling connection attempts from "
                + source.getHostAddress() + ": more than " + MAX_CONNECTION_ATTEMPTS_PER_WINDOW
                + " in " + CONNECTION_ATTEMPT_WINDOW_MILLIS + " ms. Further attempts are closed with"
                + " no reply for " + (record.throttledUntilMillis - now) + " ms.");
    }

    /** Per-source connection-attempt window plus its cooldown; see {@link #noteConnectionAttemptLocked}. */
    private static final class AttemptRecord {
        private long windowStartMillis;
        private int attempts;
        private long throttledUntilMillis;
        private boolean throttleLogged;
    }

    private CoopPeerLink freeSlotLocked() {
        for (CoopPeerLink peer : peers) {
            if (!peer.occupied()) {
                return peer;
            }
        }
        return null;
    }

    private CoopPeerLink staleSlotLocked(long now) {
        for (CoopPeerLink peer : peers) {
            if (peer.occupied() && now - peer.lastInboundFrameAtMillis() >= HALF_OPEN_REPLACE_MILLIS) {
                return peer;
            }
        }
        return null;
    }

    private void rejectExtraConnectionLocked(SocketChannel channel) {
        try {
            channel.configureBlocking(true);
            channel.socket().setTcpNoDelay(true);
            CoopMessages.Message reject = CoopMessages.lobbyReject(
                    nextSeq(),
                    System.currentTimeMillis(),
                    EXTRA_CONNECTION_REJECT_REASON);
            ByteBuffer frame = ByteBuffer.wrap((CoopMessages.encode(reject) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
            CoopLog.warn(CoopNetService.class, "Coop TCP rejected extra connection with lobby reject");
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to reject extra connection cleanly", ex);
        } finally {
            closeChannel(channel);
        }
    }

    private void progressGuestConnectionLocked() throws Exception {
        if (role != CoopConnectionRole.GUEST || freeSlotLocked() == null) {
            return;
        }

        if (pendingConnectChannel != null) {
            try {
                if (pendingConnectChannel.finishConnect()) {
                    SocketChannel established = pendingConnectChannel;
                    pendingConnectChannel = null;
                    attachChannelLocked(freeSlotLocked(), established);
                    connectFailureLogged = false;
                    CoopLog.info(CoopNetService.class,
                            "Coop TCP guest connected to " + connectHost + ":" + connectPort);
                    return;
                }
            } catch (Exception ex) {
                closeChannel(pendingConnectChannel);
                pendingConnectChannel = null;
                scheduleConnectRetryLocked(ex);
            }
        }

        long now = System.currentTimeMillis();
        if (pendingConnectChannel != null || now < nextConnectAttemptAtMillis) {
            return;
        }

        beginConnectAttemptLocked(now);
    }

    private void beginConnectAttemptLocked(long now) {
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            if (channel.connect(new InetSocketAddress(connectHost, connectPort))) {
                attachChannelLocked(freeSlotLocked(), channel);
                connectFailureLogged = false;
                CoopLog.info(CoopNetService.class,
                        "Coop TCP guest connected to " + connectHost + ":" + connectPort);
            } else {
                pendingConnectChannel = channel;
            }
        } catch (Exception ex) {
            scheduleConnectRetryLocked(ex);
            nextConnectAttemptAtMillis = now + CONNECT_RETRY_DELAY_MILLIS;
        }
    }

    private void scheduleConnectRetryLocked(Exception ex) {
        nextConnectAttemptAtMillis = System.currentTimeMillis() + CONNECT_RETRY_DELAY_MILLIS;
        if (!connectFailureLogged) {
            CoopLog.warn(CoopNetService.class,
                    "Coop TCP guest failed to connect to " + connectHost + ":" + connectPort + "; will retry", ex);
            connectFailureLogged = true;
        }
    }

    private void attachChannelLocked(CoopPeerLink peer, SocketChannel channel) throws Exception {
        if (peer == null || role == CoopConnectionRole.NONE) {
            closeChannel(channel);
            return;
        }
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        InetAddress pinned = peerAddressOf(channel);
        peer.attach(channel, pinned, clockMillis.getAsLong(), role == CoopConnectionRole.HOST);
        refreshConnectedLocked();
        CoopLog.info(CoopNetService.class, "Coop TCP channel active as " + role
                + " on peer slot " + peer.slot()
                + (pinned == null ? "" : " (UDP pinned to " + pinned.getHostAddress() + ")"));
    }

    private void readAvailableLocked(CoopPeerLink peer) {
        SocketChannel channel = peer.channel();
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }

        // Hoisted out of the byte loop: these used to be two allocations per received byte.
        java.util.function.Consumer<String> frameSink = frame -> handleFrame(peer, frame);
        Runnable oversized = OVERSIZED_FRAME_WARNING;

        try {
            readBuffer.clear();
            int read = channel.read(readBuffer);
            if (read > 0) {
                peer.noteInboundBytes(clockMillis.getAsLong());
            }
            while (read > 0) {
                readBuffer.flip();
                while (readBuffer.hasRemaining()) {
                    peer.appendInboundByte(readBuffer.get(), frameSink, oversized);
                }
                readBuffer.clear();
                if (framesThisPoll >= MAX_FRAMES_PER_POLL) {
                    // The rest waits in the kernel buffer; a flood cannot make one poll unbounded.
                    return;
                }
                read = channel.read(readBuffer);
            }

            if (read < 0) {
                closeLinkLocked(peer);
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP polling failed", ex);
            closeLinkLocked(peer);
        }
    }

    private void handleFrame(CoopPeerLink peer, String frame) {
        if (frame.isEmpty()) {
            return;
        }
        framesThisPoll++;
        CoopMessages.Message message;
        try {
            message = CoopMessages.decode(frame);
        } catch (RuntimeException ex) {
            invalidFrames++;
            int strikes = peer.noteInvalidFrame();
            // First strike only: a garbage flood must show up in the counters, not in the log.
            if (strikes == 1) {
                CoopLog.warn(CoopNetService.class, "Coop TCP received invalid frame on peer slot "
                        + peer.slot() + " (further ones are counted, not logged)", ex);
            }
            // A handshake-complete session is allowed to resynchronise; a stranger on an
            // Internet-open port is not. expectedSessionToken is set at the exact instant the
            // handshake is accepted, which makes it the honest "this peer has proved itself" test.
            if (expectedSessionToken == null && strikes >= PRE_SESSION_INVALID_FRAME_LIMIT) {
                connectionsDroppedForGarbage++;
                CoopLog.warn(CoopNetService.class, "Coop TCP dropping a pre-session connection after "
                        + strikes + " undecodable frames on peer slot " + peer.slot());
                closeLinkLocked(peer);
            }
            return;
        }
        peer.learnSenderId(message.senderId());
        inbound.add(message);
    }

    private void flushOutboundLocked(CoopPeerLink peer) {
        SocketChannel channel = peer.channel();
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }

        try {
            if (peer.pendingWrite() != null && !writePendingLocked(peer, channel)) {
                return;
            }
            peer.setPendingWrite(null);

            CoopMessages.Message message;
            while ((message = peer.outbound().poll()) != null) {
                byte[] frame = (CoopMessages.encode(message) + "\n").getBytes(StandardCharsets.UTF_8);
                if (frame.length > MAX_FRAME_BYTES) {
                    // The receiver's inbound cap would discard it anyway; dropping here keeps the
                    // failure on the sender's log where the message type is still known.
                    CoopLog.warn(CoopNetService.class, "Coop TCP dropping oversized outbound "
                            + message.type() + " frame (" + frame.length + " bytes, cap "
                            + MAX_FRAME_BYTES + ")");
                    continue;
                }
                if (frame.length > WARN_FRAME_BYTES && largeFrameWarned.add(message.type())) {
                    CoopLog.warn(CoopNetService.class, "Coop TCP outbound " + message.type()
                            + " frame is " + frame.length + " bytes (soft threshold "
                            + WARN_FRAME_BYTES + ", hard cap " + MAX_FRAME_BYTES
                            + "); consider shrinking this message before it hits the cap");
                }
                peer.setPendingWrite(ByteBuffer.wrap(frame));
                if (!writePendingLocked(peer, channel)) {
                    return;
                }
                peer.setPendingWrite(null);
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to flush outbound messages", ex);
            closeLinkLocked(peer);
        }
    }

    private boolean writePendingLocked(CoopPeerLink peer, SocketChannel channel) throws Exception {
        ByteBuffer pending = peer.pendingWrite();
        while (pending.hasRemaining()) {
            if (channel.write(pending) == 0) {
                return false;
            }
        }
        return true;
    }

    private void closeLinkLocked(CoopPeerLink peer) {
        SocketChannel channel = peer.channel();
        if (channel == null) {
            return;
        }
        peer.detach();
        closeChannel(channel);
        refreshConnectedLocked();
        CoopLog.info(CoopNetService.class, "Coop TCP channel inactive as " + role
                + " on peer slot " + peer.slot());
        if (role == CoopConnectionRole.GUEST) {
            nextConnectAttemptAtMillis = System.currentTimeMillis() + CONNECT_RETRY_DELAY_MILLIS;
        }
    }

    private void shutdownLocked() {
        closeChannel(serverChannel);
        for (CoopPeerLink peer : peers) {
            closeChannel(peer.channel());
            peer.reset();
        }
        closeChannel(pendingConnectChannel);
        closeChannel(udpChannel);
        serverChannel = null;
        connected = false;
        pendingConnectChannel = null;
        udpChannel = null;
        expectedSessionToken = null;
        localSenderId = null;
        localDatagramSenderId = "";
        noTokenWarned = false;
        tokenMismatchWarned = false;
        malformedDatagramWarned = false;
        inboundDatagrams.clear();
        // TCP queues too: a session restarted inside the same game process would otherwise replay
        // leftovers (a stale HANDSHAKE_RESULT, say) into the fresh connection.
        inbound.clear();
        attemptsByAddress.clear();
        connectHost = null;
        connectPort = 0;
        nextConnectAttemptAtMillis = 0L;
        connectFailureLogged = false;
        framesThisPoll = 0;
        role = CoopConnectionRole.NONE;
    }

    private void closeChannel(java.nio.channels.Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to close channel", ex);
        }
    }
}
