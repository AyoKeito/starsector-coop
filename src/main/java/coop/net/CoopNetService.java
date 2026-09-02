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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The coop transport: one TCP control channel (reliable, JSON lines) plus one UDP datagram channel
 * (best-effort state stream), both non-blocking and both driven from the campaign thread — Starsector
 * kills mod-created networking threads without saying so, so everything here is a state machine the
 * pump advances, never a blocking read.
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
 * on-path and replayable from anywhere. So the host keeps a {@code validatedUdpAddress} (the send
 * target) separate from whatever address is currently talking. A token-valid datagram from a different
 * source is accepted <em>inbound</em> (the watermark defeats replay) but only becomes a candidate: the
 * host sends it a {@code PATH_PROBE} carrying a fresh random nonce and re-points the stream only when
 * that exact nonce comes back from that exact address — QUIC's {@code PATH_CHALLENGE} model
 * (RFC 9000 §8.2). Until then traffic keeps flowing to the previously validated address, so an
 * off-path attacker cannot redirect the state stream, and a genuine NAT rebind still recovers within a
 * round trip. The guest needs none of this: its target is the address the player configured.
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
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    /** Soft threshold: an outbound frame this big logs once per message type, so growth is loud. */
    private static final int WARN_FRAME_BYTES = 256 * 1024;
    /** UDP receive buffer — sized for the datagram path, not the TCP frame cap. */
    private static final int DATAGRAM_BUFFER_BYTES = 64 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;
    private static final int MAX_DATAGRAM_BYTES = 60 * 1024;
    private static final long CONNECT_RETRY_DELAY_MILLIS = 500L;
    private static final String EXTRA_CONNECTION_REJECT_REASON = "Host already has an active connection";
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

    private final Queue<CoopMessages.Message> inbound = new ConcurrentLinkedQueue<>();
    /**
     * Outbound TCP queue. A {@link java.util.LinkedList} guarded by {@link #lifecycleLock} rather
     * than a concurrent queue: coalescing has to <em>replace</em> a queued message in place, which no
     * lock-free queue offers, and every producer is the campaign thread anyway (the sandbox forbids
     * networking threads, so there was never real concurrency here to lose).
     */
    private final java.util.LinkedList<CoopMessages.Message> outbound = new java.util.LinkedList<>();
    // High-frequency state datagrams (UDP). Kept separate from the reliable TCP control queues.
    // Netty is deliberately avoided here: Starsector's script sandbox blocks Netty's reflection
    // (see CoopNetServiceSandboxCompatibilityTest), so coop networking uses java.nio throughout.
    private final Queue<String> inboundDatagrams = new ConcurrentLinkedQueue<>();
    private final Queue<String> outboundDatagrams = new ConcurrentLinkedQueue<>();
    // One warning per message type per service lifetime; guarded by lifecycleLock (flush path).
    private final java.util.Set<CoopMessages.Type> largeFrameWarned =
            java.util.EnumSet.noneOf(CoopMessages.Type.class);
    private final AtomicLong nextSeq = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final ByteBuffer datagramBuffer = ByteBuffer.allocate(DATAGRAM_BUFFER_BYTES);
    private final byte[] inboundFrame = new byte[MAX_FRAME_BYTES];
    /** Wall clock; injectable so keepalive and challenge timing are testable without sleeping. */
    private final LongSupplier clockMillis;
    private final SecureRandom nonceSource = new SecureRandom();

    private CoopConnectionRole role = CoopConnectionRole.NONE;
    private ServerSocketChannel serverChannel;
    private SocketChannel activeChannel;
    /**
     * Cached answer for {@link #isConnected()}. Derived from {@code activeChannel} by
     * {@link #refreshConnectedLocked()}, which every path that can change it calls; volatile so the
     * fast path can read it without taking {@code lifecycleLock}.
     */
    private volatile boolean connected;
    private SocketChannel pendingConnectChannel;
    private DatagramChannel udpChannel;
    /**
     * The datagram send target: the guest's configured host address, or on the host the return address
     * that has passed a {@code PATH_PROBE} challenge. Null on the host until validation completes, and
     * outbound datagrams are dropped while it is null — the state stream is latest-wins, so buffering
     * ticks nobody can receive yet only guarantees stale ones get delivered later.
     */
    private SocketAddress validatedUdpAddress;
    /** Unproven source currently being challenged (host only); at most one at a time. */
    private SocketAddress candidateUdpAddress;
    private String candidateNonce;
    private long candidateFirstSeenAtMillis;
    private long candidateLastProbeAtMillis;
    /**
     * Peer address pinned from the established TCP connection. UDP datagrams are only accepted from
     * this address — the first and cheapest of the inbound filter's three stages (see the class
     * Javadoc). The peer's UDP *port* is not pinned: it legitimately differs from the TCP port, and
     * what proves it now is the session token plus the challenge-echo rather than "first one wins".
     */
    private InetAddress pinnedPeerAddress;
    private boolean foreignDatagramWarned;
    private boolean noTokenWarned;
    private boolean tokenMismatchWarned;
    private boolean malformedDatagramWarned;
    private boolean candidateTimeoutLogged;
    /** Session token every inbound datagram must carry; null before handshake and after teardown. */
    private String expectedSessionToken;
    /** Full local player id stamped onto outbound TCP messages (Phase 20.5). */
    private String localSenderId;
    /** {@link CoopMessages#wireToken} of {@link #localSenderId}, for the datagram envelope. */
    private String localDatagramSenderId = "";
    private long lastDatagramSentAtMillis;
    private long lastInboundDatagramAtMillis;
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
    private ByteBuffer pendingWrite;
    private int inboundFrameLength;
    private String connectHost;
    private int connectPort;
    private long nextConnectAttemptAtMillis;
    private boolean connectFailureLogged;
    private boolean discardingOversizedFrame;
    private boolean datagramSendFailureLogged;
    /** One warning per service lifetime when the outbound queue first crosses the warn threshold. */
    private boolean queueDepthWarned;

    public CoopNetService() {
        this(System::currentTimeMillis);
    }

    /** Test seam: a fake clock drives the keepalive and challenge timers without real waiting. */
    CoopNetService(LongSupplier clockMillis) {
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
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
            // A fresh token means a fresh session: an address validated for the previous one proves
            // nothing about this one, and the keepalive timer restarts from now rather than firing
            // immediately on the strength of a long-idle previous session.
            if (token != null) {
                lastDatagramSentAtMillis = clockMillis.getAsLong();
            }
            forgetCandidateLocked();
        }
    }

    /** Immutable snapshot of the UDP transport counters; see {@link CoopDatagramStats}. */
    public CoopDatagramStats datagramStats() {
        synchronized (lifecycleLock) {
            return new CoopDatagramStats(droppedNoToken, droppedTokenMismatch, droppedForeignSource,
                    droppedMalformed, probesSent, probeEchoesReceived, pathValidations, keepalivesSent,
                    keepalivesReceived, icmpTransients, oversizedDatagrams, lastInboundDatagramAtMillis,
                    validatedUdpAddress == null ? "" : validatedUdpAddress.toString());
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
                CoopLog.info(CoopNetService.class, "Coop TCP host listening on port " + port);
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
     * Whether the TCP channel is up. Reads a cached flag while connected — the pump and the battle
     * bridge ask this ~11 times a frame, and every one of those calls used to run a full
     * {@link #pollNetworkLocked()} (accept + read loop + datagram receive), which is where most of the
     * measured ~3000 socket syscalls/s came from (perf audit #10).
     *
     * <p>The flag is refreshed by {@link #refreshConnectedLocked()} from every path that can change the
     * answer: every poll, and every mutation of {@code activeChannel} (attach, close, shutdown). A peer
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
     * Queues a TCP message, stamping the local {@code senderId} when the message does not already
     * carry one. Stamping here rather than in the ~50 factories keeps the id out of every call site
     * and means one seam owns "who sent this"; an explicitly stamped message (a relay, later) is left
     * alone.
     */
    public void send(CoopMessages.Message message) {
        if (message == null) {
            return;
        }
        synchronized (lifecycleLock) {
            CoopMessages.Message stamped = message.withSenderId(localSenderId);
            if (!backloggedLocked() || !replaceQueuedLocked(stamped)) {
                outbound.add(stamped);
            }
            if (outbound.size() > QUEUE_DEPTH_WARN_MESSAGES && !queueDepthWarned) {
                queueDepthWarned = true;
                CoopLog.warn(CoopNetService.class, "Coop TCP outbound queue is " + outbound.size()
                        + " messages deep (warn threshold " + QUEUE_DEPTH_WARN_MESSAGES
                        + "); the peer's socket is not draining. Nothing is dropped — superseded"
                        + " snapshots coalesce, events queue.");
            }
        }
    }

    /**
     * Depth of the outbound TCP queue. Read by the pump for {@code LINK_STATUS} and by tests; it is
     * the one honest measure of whether the peer's socket is keeping up.
     */
    public int outboundQueueDepth() {
        synchronized (lifecycleLock) {
            return outbound.size();
        }
    }

    /**
     * Backlogged means the socket is behind: either a half-written frame is parked in
     * {@link #pendingWrite} (the kernel buffer said "full") or the queue has piled up past
     * {@link #COALESCE_BACKLOG_MESSAGES}. Only then does coalescing engage.
     */
    private boolean backloggedLocked() {
        return pendingWrite != null || outbound.size() >= COALESCE_BACKLOG_MESSAGES;
    }

    /**
     * Newest-wins: replaces the queued message with the same coalescing key, keeping its <em>place</em>
     * in the queue so relative ordering against everything else is unchanged.
     *
     * @return true when an existing message was replaced, false when the caller must enqueue
     */
    private boolean replaceQueuedLocked(CoopMessages.Message message) {
        String key = coalesceKey(message);
        if (key == null) {
            return false;
        }
        // Newest first: there is only ever one message per key in the queue, and scanning from the
        // tail finds it in one step for the stream types that produce back-to-back sends.
        java.util.ListIterator<CoopMessages.Message> cursor = outbound.listIterator(outbound.size());
        while (cursor.hasPrevious()) {
            if (key.equals(coalesceKey(cursor.previous()))) {
                cursor.set(message);
                return true;
            }
        }
        return false;
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
    private static String coalesceKey(CoopMessages.Message message) {
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
            flushOutboundLocked();
            maybeChallengeCandidateLocked();
            maybeQueueKeepaliveLocked();
            flushDatagramsLocked();
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

    /** Queues a best-effort UDP datagram (high-frequency state). Dropped if no peer address known. */
    public void sendDatagram(String payload) {
        if (payload == null) {
            return;
        }
        outboundDatagrams.add(payload);
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

    public void shutdown() {
        synchronized (lifecycleLock) {
            shutdownLocked();
        }
    }

    private void pollNetworkLocked() {
        try {
            acceptHostConnectionLocked();
            progressGuestConnectionLocked();
            readAvailableLocked();
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP polling failed", ex);
            closeActiveChannelLocked(activeChannel);
            closeChannel(pendingConnectChannel);
            pendingConnectChannel = null;
        }
        readDatagramsLocked();
        refreshConnectedLocked();
    }

    /** Recomputes the {@link #isConnected()} cache from the live channel. Cheap: two field reads. */
    private void refreshConnectedLocked() {
        SocketChannel channel = activeChannel;
        connected = channel != null && channel.isOpen() && channel.isConnected();
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
            validatedUdpAddress = remoteAddress;
            lastDatagramSentAtMillis = clockMillis.getAsLong();
            datagramSendFailureLogged = false;
            CoopLog.info(CoopNetService.class, "Coop UDP datagram channel bound to " + bindAddress);
        } catch (Exception ex) {
            udpChannel = null;
            validatedUdpAddress = null;
            CoopLog.warn(CoopNetService.class, "Coop UDP datagram channel unavailable; "
                    + "campaign state stream disabled (TCP control unaffected)", ex);
        }
    }

    private void readDatagramsLocked() {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            return;
        }
        while (true) {
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
        if (!isPinnedPeerLocked(source)) {
            droppedForeignSource++;
            if (!foreignDatagramWarned) {
                foreignDatagramWarned = true;
                CoopLog.warn(CoopNetService.class, "Coop UDP ignoring datagram from non-peer source "
                        + source + " (pinned peer "
                        + (pinnedPeerAddress == null ? "<none>" : pinnedPeerAddress.getHostAddress()) + ")");
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

        lastInboundDatagramAtMillis = clockMillis.getAsLong();
        switch (header.type()) {
            case UDP_PROBE -> keepalivesReceived++;
            case PATH_PROBE -> handlePathProbeLocked(source, payload);
            // Transport-level types are handled here and never reach the pump; everything else is
            // gameplay state and goes to the drain exactly as before.
            default -> inboundDatagrams.add(payload);
        }
        noteValidatedSourceLocked(source);
    }

    /**
     * Address bookkeeping for a token-valid datagram (host only). A source that is already the send
     * target needs nothing; anything else is unproven and becomes the challenge candidate, which is
     * what stops a replayed-token packet from a hostile source redirecting the stream.
     */
    private void noteValidatedSourceLocked(SocketAddress source) {
        if (role != CoopConnectionRole.HOST || source == null) {
            return;
        }
        if (source.equals(validatedUdpAddress) || source.equals(candidateUdpAddress)) {
            return;
        }
        if (candidateUdpAddress != null) {
            // One outstanding challenge at a time: an attacker able to spray sources must not be able
            // to make us mint nonces per packet.
            return;
        }
        candidateUdpAddress = source;
        candidateNonce = newNonceLocked();
        candidateFirstSeenAtMillis = clockMillis.getAsLong();
        candidateLastProbeAtMillis = 0L;
    }

    /** Sends (or resends) the outstanding challenge, and forgets a candidate that never answers. */
    private void maybeChallengeCandidateLocked() {
        if (candidateUdpAddress == null || udpChannel == null || expectedSessionToken == null) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now - candidateFirstSeenAtMillis >= PATH_CANDIDATE_TIMEOUT_MILLIS) {
            // Once per connection: a packet flood from rotating ports would otherwise get to write a
            // log line every 5 s for as long as it keeps going.
            if (!candidateTimeoutLogged) {
                candidateTimeoutLogged = true;
                CoopLog.info(CoopNetService.class, "Coop UDP candidate " + candidateUdpAddress
                        + " never echoed its path challenge; keeping "
                        + (validatedUdpAddress == null ? "<no target>" : validatedUdpAddress.toString()));
            }
            forgetCandidateLocked();
            return;
        }
        if (candidateLastProbeAtMillis != 0L && now - candidateLastProbeAtMillis < PATH_PROBE_RESEND_MILLIS) {
            return;
        }
        candidateLastProbeAtMillis = now;
        probesSent++;
        sendDatagramToLocked(candidateUdpAddress, CoopMessages.datagram(expectedSessionToken,
                localDatagramSenderId, CoopMessages.Type.PATH_PROBE, 0L, 0L,
                PATH_CHALLENGE_PREFIX + candidateNonce));
    }

    /**
     * Answers a challenge, or completes one. The echo is only believed from the address that was
     * challenged: a nonce that comes back from somewhere else proves an on-path observer, not a peer.
     */
    private void handlePathProbeLocked(SocketAddress source, String payload) {
        String body = lastSectionBodyOrEmpty(payload);
        if (body.startsWith(PATH_CHALLENGE_PREFIX)) {
            // Answer immediately over the normal outbound path (guest: to its configured host; host:
            // to its validated target, or dropped when it has none).
            outboundDatagrams.add(CoopMessages.datagram(expectedSessionToken, localDatagramSenderId,
                    CoopMessages.Type.PATH_PROBE, 0L, 0L,
                    PATH_ECHO_PREFIX + body.substring(PATH_CHALLENGE_PREFIX.length())));
            return;
        }
        if (!body.startsWith(PATH_ECHO_PREFIX)) {
            return;
        }
        probeEchoesReceived++;
        String nonce = body.substring(PATH_ECHO_PREFIX.length());
        if (candidateUdpAddress == null || candidateNonce == null
                || !candidateNonce.equals(nonce) || !candidateUdpAddress.equals(source)) {
            return;
        }
        validatedUdpAddress = candidateUdpAddress;
        pathValidations++;
        candidateTimeoutLogged = false;
        forgetCandidateLocked();
        // Rare by construction (session start, NAT rebind, reconnect), so this logs every time: when a
        // WAN session goes quiet, "which address are we streaming to" is the first question.
        CoopLog.info(CoopNetService.class, "Coop UDP return address validated " + validatedUdpAddress);
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

    private void forgetCandidateLocked() {
        candidateUdpAddress = null;
        candidateNonce = null;
        candidateFirstSeenAtMillis = 0L;
        candidateLastProbeAtMillis = 0L;
    }

    /** Queues a keepalive when the outbound stream has gone quiet; see {@link #KEEPALIVE_IDLE_MILLIS}. */
    private void maybeQueueKeepaliveLocked() {
        if (udpChannel == null || validatedUdpAddress == null || expectedSessionToken == null) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now - lastDatagramSentAtMillis < KEEPALIVE_IDLE_MILLIS) {
            return;
        }
        keepalivesSent++;
        outboundDatagrams.add(CoopMessages.datagram(expectedSessionToken, localDatagramSenderId,
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

    /**
     * True when the datagram may be accepted from this source. Address only: the peer's UDP port
     * legitimately differs from its TCP port and can change mid-session on a NAT rebind, so what
     * proves the port is the session token plus the challenge-echo, not a first-datagram lock.
     */
    private boolean isPinnedPeerLocked(SocketAddress source) {
        if (pinnedPeerAddress == null) {
            return true;
        }
        if (!(source instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            return false;
        }
        return pinnedPeerAddress.equals(inet.getAddress());
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

    private void flushDatagramsLocked() {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            outboundDatagrams.clear();
            return;
        }
        SocketAddress remote = validatedUdpAddress;
        if (remote == null) {
            // No validated peer address yet (host before the challenge-echo completes). Drop; the next
            // 10 Hz snapshot supersedes anything queued, so there is no value in buffering stale state.
            outboundDatagrams.clear();
            return;
        }

        String payload;
        while ((payload = outboundDatagrams.poll()) != null) {
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
            sendDatagramBytesLocked(remote, bytes);
        }
    }

    /** Sends one datagram to an explicit address — the challenge path, which cannot use the queue. */
    private void sendDatagramToLocked(SocketAddress remote, String payload) {
        if (udpChannel == null || remote == null) {
            return;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        CoopWiretap.noteSend(payload, bytes.length);
        sendDatagramBytesLocked(remote, bytes);
    }

    private void sendDatagramBytesLocked(SocketAddress remote, byte[] bytes) {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            return;
        }
        try {
            channel.send(ByteBuffer.wrap(bytes), remote);
            lastDatagramSentAtMillis = clockMillis.getAsLong();
        } catch (Exception ex) {
            if (isTransientLinkException(ex)) {
                noteTransientLinkEventLocked("send", ex);
                return;
            }
            if (!datagramSendFailureLogged) {
                CoopLog.warn(CoopNetService.class, "Coop UDP send failed; dropping datagram", ex);
                datagramSendFailureLogged = true;
            }
        }
    }

    private void acceptHostConnectionLocked() throws Exception {
        if (role != CoopConnectionRole.HOST || serverChannel == null) {
            return;
        }

        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) {
            return;
        }

        if (activeChannel != null) {
            rejectExtraConnectionLocked(accepted);
            return;
        }

        if (!attachChannelLocked(accepted)) {
            CoopLog.warn(CoopNetService.class, "Coop TCP rejecting extra connection");
            rejectExtraConnectionLocked(accepted);
        }
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
        if (role != CoopConnectionRole.GUEST || activeChannel != null) {
            return;
        }

        if (pendingConnectChannel != null) {
            try {
                if (pendingConnectChannel.finishConnect()) {
                    SocketChannel connected = pendingConnectChannel;
                    pendingConnectChannel = null;
                    attachChannelLocked(connected);
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
                attachChannelLocked(channel);
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

    private boolean attachChannelLocked(SocketChannel channel) throws Exception {
        if (role == CoopConnectionRole.NONE || activeChannel != null) {
            return false;
        }

        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        activeChannel = channel;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
        pinnedPeerAddress = peerAddressOf(channel);
        foreignDatagramWarned = false;
        candidateTimeoutLogged = false;
        forgetCandidateLocked();
        if (role == CoopConnectionRole.HOST) {
            // Re-validate the guest's UDP address for this connection. The host does not run
            // shutdownLocked when a guest merely reconnects, so without this the previous connection's
            // address would stay the send target — and a reconnecting guest behind NAT almost always
            // comes back on a different port. The guest's target is configured, so it keeps its own.
            validatedUdpAddress = null;
        }
        refreshConnectedLocked();
        CoopLog.info(CoopNetService.class, "Coop TCP channel active as " + role
                + (pinnedPeerAddress == null ? "" : " (UDP pinned to " + pinnedPeerAddress.getHostAddress() + ")"));
        return true;
    }

    private void readAvailableLocked() throws Exception {
        SocketChannel channel = activeChannel;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }

        readBuffer.clear();
        int read = channel.read(readBuffer);
        while (read > 0) {
            readBuffer.flip();
            while (readBuffer.hasRemaining()) {
                appendInboundByte(readBuffer.get());
            }
            readBuffer.clear();
            read = channel.read(readBuffer);
        }

        if (read < 0) {
            closeActiveChannelLocked(channel);
        }
    }

    private void appendInboundByte(byte value) {
        int unsigned = value & 0xff;
        if (unsigned == '\n') {
            if (discardingOversizedFrame) {
                inboundFrameLength = 0;
                discardingOversizedFrame = false;
                return;
            }
            String frame = new String(inboundFrame, 0, inboundFrameLength, StandardCharsets.UTF_8);
            inboundFrameLength = 0;
            handleFrame(frame.trim());
            return;
        }

        if (unsigned == '\r' || discardingOversizedFrame) {
            return;
        }

        if (inboundFrameLength >= MAX_FRAME_BYTES) {
            CoopLog.warn(CoopNetService.class, "Coop TCP received oversized frame");
            inboundFrameLength = 0;
            discardingOversizedFrame = true;
            return;
        }

        inboundFrame[inboundFrameLength] = (byte) unsigned;
        inboundFrameLength++;
    }

    private void handleFrame(String frame) {
        if (frame.isEmpty()) {
            return;
        }
        try {
            inbound.add(CoopMessages.decode(frame));
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP received invalid frame", ex);
        }
    }

    private void flushOutboundLocked() {
        SocketChannel channel = activeChannel;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }

        try {
            if (pendingWrite != null && !writePendingLocked(channel)) {
                return;
            }
            pendingWrite = null;

            CoopMessages.Message message;
            while ((message = outbound.poll()) != null) {
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
                pendingWrite = ByteBuffer.wrap(frame);
                if (!writePendingLocked(channel)) {
                    return;
                }
                pendingWrite = null;
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to flush outbound messages", ex);
            closeActiveChannelLocked(channel);
        }
    }

    private boolean writePendingLocked(SocketChannel channel) throws Exception {
        while (pendingWrite.hasRemaining()) {
            if (channel.write(pendingWrite) == 0) {
                return false;
            }
        }
        return true;
    }

    private void closeActiveChannelLocked(SocketChannel channel) {
        if (channel == null || activeChannel != channel) {
            return;
        }

        activeChannel = null;
        connected = false;
        pendingWrite = null;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
        closeChannel(channel);
        CoopLog.info(CoopNetService.class, "Coop TCP channel inactive as " + role);
        if (role == CoopConnectionRole.GUEST) {
            nextConnectAttemptAtMillis = System.currentTimeMillis() + CONNECT_RETRY_DELAY_MILLIS;
        }
    }

    private void shutdownLocked() {
        closeChannel(serverChannel);
        closeChannel(activeChannel);
        closeChannel(pendingConnectChannel);
        closeChannel(udpChannel);
        serverChannel = null;
        activeChannel = null;
        connected = false;
        pendingConnectChannel = null;
        udpChannel = null;
        validatedUdpAddress = null;
        forgetCandidateLocked();
        expectedSessionToken = null;
        localSenderId = null;
        localDatagramSenderId = "";
        lastDatagramSentAtMillis = 0L;
        lastInboundDatagramAtMillis = 0L;
        datagramSendFailureLogged = false;
        noTokenWarned = false;
        tokenMismatchWarned = false;
        malformedDatagramWarned = false;
        candidateTimeoutLogged = false;
        inboundDatagrams.clear();
        outboundDatagrams.clear();
        // TCP queues too: a session restarted inside the same game process would otherwise replay
        // leftovers (a stale HANDSHAKE_RESULT, say) into the fresh connection.
        inbound.clear();
        outbound.clear();
        pinnedPeerAddress = null;
        foreignDatagramWarned = false;
        pendingWrite = null;
        connectHost = null;
        connectPort = 0;
        nextConnectAttemptAtMillis = 0L;
        connectFailureLogged = false;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
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
