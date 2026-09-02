package coop.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * Everything the coop transport knows about <em>one</em> peer (Phase 20.5): its TCP channel and the
 * half-written frame parked on it, its inbound frame assembly, its outbound queues, the UDP address
 * the state stream is allowed to go to, and the warn-once flags that keep a hostile peer from writing
 * the log.
 *
 * <p><b>Why this class exists now, while gameplay is still single-guest.</b> Every one of these
 * fields used to be a field on {@link CoopNetService}, which is exactly the shape that makes "add a
 * second guest" a rewrite rather than a change: the service could only ever describe one peer, so
 * every routing decision degenerated into "the peer". With the state extracted, the service holds a
 * table of these and routing becomes a choice — broadcast to the table, or unicast to the one link
 * whose {@link #senderId()} matches. Capacity is still 1 (Phase 27 raises it); what changes here is
 * that raising it later is a constant, not a refactor.
 *
 * <p><b>Not thread-safe, and deliberately so.</b> Every method is called with the service's
 * lifecycle lock held, from the campaign thread — the sandbox forbids networking threads, so there
 * was never real concurrency here to protect.
 *
 * <p><b>Address validation lives here, not on the service.</b> The candidate/nonce/timer trio is
 * per-peer because a NAT rebind is a per-peer event: one guest re-pointing its stream says nothing
 * about another's, and a single shared candidate slot would let one peer's traffic starve another's
 * challenge. See {@link CoopNetService}'s class Javadoc for the QUIC {@code PATH_CHALLENGE} model
 * itself.
 */
public final class CoopPeerLink {

    /** Frame assembly buffer, sized by the service's corruption cap. One per peer, allocated once. */
    private final byte[] inboundFrame;
    /**
     * Outbound TCP queue. A {@link LinkedList} rather than a concurrent queue: coalescing has to
     * <em>replace</em> a queued message in place, which no lock-free queue offers.
     */
    private final LinkedList<CoopMessages.Message> outbound = new LinkedList<>();
    /** Outbound UDP payloads, drained to {@link #validatedUdpAddress} once per flush. */
    private final Queue<String> outboundDatagrams = new ArrayDeque<>();

    private final int slot;

    private SocketChannel channel;
    private ByteBuffer pendingWrite;
    private int inboundFrameLength;
    private boolean discardingOversizedFrame;
    private long lastInboundFrameAtMillis;
    /**
     * When the current channel attached (red-team A1). The handshake deadline is measured from here
     * rather than from the last byte received, because a stranger controls when bytes arrive and
     * therefore controls any clock that resets on them.
     */
    private long attachedAtMillis;
    /**
     * Bytes read from the socket but not yet framed, parked because the poll hit its frame ceiling
     * (red-team A12). They belong to this peer's stream and must survive to the next poll: the
     * ceiling exists to bound work per poll, not to punch holes in a TCP stream.
     */
    private ByteBuffer deferredInbound;

    /**
     * The peer's full player id, learned from the first TCP message it stamps (Phase 20.5). Null
     * until then, which is why every unicast path falls back to broadcast rather than dropping: a
     * message addressed to a peer we cannot name yet must still be delivered.
     */
    private String senderId;

    /**
     * Peer address pinned from the established TCP connection; inbound datagrams are only accepted
     * from it. The peer's UDP <em>port</em> is not pinned — it legitimately differs from the TCP port
     * and moves on a NAT rebind — so what proves the port is the session token plus the
     * challenge-echo.
     */
    private InetAddress pinnedPeerAddress;
    /**
     * The datagram send target: the guest's configured host address, or on the host the return
     * address that has passed a {@code PATH_PROBE} challenge. Null on the host until validation
     * completes, and outbound datagrams are dropped while it is null — the state stream is
     * latest-wins, so buffering ticks nobody can receive yet only guarantees stale ones later.
     */
    private SocketAddress validatedUdpAddress;
    /** Unproven source currently being challenged; at most one at a time per peer. */
    private SocketAddress candidateUdpAddress;
    private String candidateNonce;
    private long candidateFirstSeenAtMillis;
    private long candidateLastProbeAtMillis;

    private long lastDatagramSentAtMillis;
    private long lastInboundDatagramAtMillis;

    private boolean foreignDatagramWarned;
    private boolean candidateTimeoutLogged;
    private boolean queueDepthWarned;
    private boolean datagramSendFailureLogged;

    /**
     * Undecodable frames seen on this connection. Only ever acted on before the handshake completes
     * (see {@link CoopNetService}): a session that has proved itself is allowed the occasional
     * garbage frame, a stranger on an Internet-open port is not.
     */
    private int invalidFrames;

    CoopPeerLink(int slot, int frameCapacityBytes) {
        this.slot = slot;
        this.inboundFrame = new byte[frameCapacityBytes];
    }

    /** Slot index in the service's peer table; only ever used in log lines. */
    public int slot() {
        return slot;
    }

    // ---- TCP channel ------------------------------------------------------------------------------

    /**
     * Binds a freshly connected channel to this slot and resets everything that described the
     * previous one. Frame assembly, the garbage counter and the silence clock all restart: a
     * connection accepted seconds after the previous one died must not inherit its half-frame, its
     * strike count, or its eligibility for half-open replacement.
     *
     * @param clearValidatedUdpAddress host-side re-validation. The host does not shut the transport
     *                                 down when a guest merely reconnects, so without this the
     *                                 previous connection's return address stays the send target —
     *                                 and a reconnecting guest behind NAT almost always comes back on
     *                                 a different port. The guest's target is configured, so it keeps
     *                                 its own.
     */
    void attach(SocketChannel channel, InetAddress pinnedPeerAddress, long nowMillis,
                boolean clearValidatedUdpAddress) {
        this.channel = channel;
        this.pendingWrite = null;
        this.inboundFrameLength = 0;
        this.discardingOversizedFrame = false;
        this.lastInboundFrameAtMillis = nowMillis;
        this.attachedAtMillis = nowMillis;
        this.deferredInbound = null;
        this.pinnedPeerAddress = pinnedPeerAddress;
        this.senderId = null;
        this.invalidFrames = 0;
        this.foreignDatagramWarned = false;
        this.candidateTimeoutLogged = false;
        // Red-team C8: the warn-once flags describe a socket, not a peer. Left set, the first
        // connection's stalled queue or dead UDP path silenced the warning for every connection after
        // it - which is the run where the evidence was needed.
        this.queueDepthWarned = false;
        this.datagramSendFailureLogged = false;
        forgetCandidate();
        if (clearValidatedUdpAddress) {
            this.validatedUdpAddress = null;
        }
    }

    /** Forgets the channel and its half-written frame; the queues survive, as they always have. */
    void detach() {
        this.channel = null;
        this.pendingWrite = null;
        this.inboundFrameLength = 0;
        this.discardingOversizedFrame = false;
        this.deferredInbound = null;
    }

    SocketChannel channel() {
        return channel;
    }

    /** True when this slot holds a usable channel — the per-peer half of {@code isConnected()}. */
    boolean channelLive() {
        return channel != null && channel.isOpen() && channel.isConnected();
    }

    boolean occupied() {
        return channel != null;
    }

    long lastInboundFrameAtMillis() {
        return lastInboundFrameAtMillis;
    }

    /** When this slot's current channel attached; 0 when nothing is attached. See {@link #attach}. */
    long attachedAtMillis() {
        return attachedAtMillis;
    }

    /** Bytes carried over from a poll that hit its frame ceiling, or null. */
    ByteBuffer deferredInbound() {
        return deferredInbound;
    }

    /** Parks whatever is left of {@code buffer} for the next poll; an empty remainder parks nothing. */
    void deferInbound(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            deferredInbound = null;
            return;
        }
        // Copied, not aliased: the service reuses one read buffer for every peer in the table.
        byte[] copy = new byte[buffer.remaining()];
        buffer.get(copy);
        deferredInbound = ByteBuffer.wrap(copy);
    }

    void clearDeferredInbound() {
        deferredInbound = null;
    }

    void noteInboundBytes(long nowMillis) {
        lastInboundFrameAtMillis = nowMillis;
    }

    String senderId() {
        return senderId;
    }

    /** Learns the peer's id from the first message it stamps; later messages cannot change it. */
    void learnSenderId(String senderId) {
        if (this.senderId == null && senderId != null && !senderId.isEmpty()) {
            this.senderId = senderId;
        }
    }

    ByteBuffer pendingWrite() {
        return pendingWrite;
    }

    void setPendingWrite(ByteBuffer pendingWrite) {
        this.pendingWrite = pendingWrite;
    }

    // ---- inbound frame assembly -------------------------------------------------------------------

    /**
     * Feeds one received byte through the line framer, handing each completed frame to {@code sink}.
     * An oversized frame is discarded up to its terminator rather than closing the connection: the
     * decoder's tolerance is what lets a corrupted stream resynchronise.
     */
    void appendInboundByte(byte value, Consumer<String> sink, Runnable onOversized) {
        int unsigned = value & 0xff;
        if (unsigned == '\n') {
            if (discardingOversizedFrame) {
                inboundFrameLength = 0;
                discardingOversizedFrame = false;
                return;
            }
            String frame = new String(inboundFrame, 0, inboundFrameLength, StandardCharsets.UTF_8);
            inboundFrameLength = 0;
            sink.accept(frame.trim());
            return;
        }

        if (unsigned == '\r' || discardingOversizedFrame) {
            return;
        }

        if (inboundFrameLength >= inboundFrame.length) {
            onOversized.run();
            inboundFrameLength = 0;
            discardingOversizedFrame = true;
            return;
        }

        inboundFrame[inboundFrameLength] = (byte) unsigned;
        inboundFrameLength++;
    }

    /** @return the running total after counting this one, so the caller can apply its strike rule. */
    int noteInvalidFrame() {
        invalidFrames++;
        return invalidFrames;
    }

    int invalidFrames() {
        return invalidFrames;
    }

    // ---- outbound TCP queue -----------------------------------------------------------------------

    LinkedList<CoopMessages.Message> outbound() {
        return outbound;
    }

    int outboundDepth() {
        return outbound.size();
    }

    /**
     * Backlogged means this peer's socket is behind: either a half-written frame is parked in
     * {@link #pendingWrite} (the kernel buffer said "full") or the queue has piled up past
     * {@code threshold}. Only then does coalescing engage.
     */
    boolean backlogged(int threshold) {
        return pendingWrite != null || outbound.size() >= threshold;
    }

    /**
     * Newest-wins: replaces the queued message carrying the same coalescing key, keeping its
     * <em>place</em> in the queue so relative ordering against everything else is unchanged.
     *
     * @return true when an existing message was replaced, false when the caller must enqueue
     */
    boolean replaceQueued(CoopMessages.Message message, String key) {
        if (key == null) {
            return false;
        }
        // Newest first: there is only ever one message per key in the queue, and scanning from the
        // tail finds it in one step for the stream types that produce back-to-back sends.
        ListIterator<CoopMessages.Message> cursor = outbound.listIterator(outbound.size());
        while (cursor.hasPrevious()) {
            if (key.equals(CoopNetService.coalesceKey(cursor.previous()))) {
                cursor.set(message);
                return true;
            }
        }
        return false;
    }

    void enqueue(CoopMessages.Message message) {
        outbound.add(message);
    }

    /**
     * Discards the oldest message the coalescing whitelist calls a superseded snapshot (red-team A4).
     * Called only at the queue's hard cap, and only ever on a snapshot: past the cap something must
     * go, and the one class of message whose loss changes nothing is the one a newer copy of is
     * already queued behind it.
     *
     * @return true when a message was dropped; false when the whole queue is semantic events, which
     *         is the case the caller escalates to dropping the link
     */
    boolean dropOldestCoalescable() {
        ListIterator<CoopMessages.Message> cursor = outbound.listIterator();
        while (cursor.hasNext()) {
            if (CoopNetService.coalesceKey(cursor.next()) != null) {
                cursor.remove();
                return true;
            }
        }
        return false;
    }

    /** Discards everything queued for a link being dropped; @return how many messages were lost. */
    int discardOutbound() {
        int dropped = outbound.size();
        outbound.clear();
        return dropped;
    }

    /** One warning per link per queue-depth excursion; a stalled socket must not log per message. */
    boolean shouldWarnQueueDepth() {
        if (queueDepthWarned) {
            return false;
        }
        queueDepthWarned = true;
        return true;
    }

    // ---- outbound UDP queue -----------------------------------------------------------------------

    void enqueueDatagram(String payload) {
        outboundDatagrams.add(payload);
    }

    Queue<String> outboundDatagrams() {
        return outboundDatagrams;
    }

    boolean shouldWarnDatagramSendFailure() {
        if (datagramSendFailureLogged) {
            return false;
        }
        datagramSendFailureLogged = true;
        return true;
    }

    // ---- UDP addressing ---------------------------------------------------------------------------

    SocketAddress validatedUdpAddress() {
        return validatedUdpAddress;
    }

    void setValidatedUdpAddress(SocketAddress address) {
        this.validatedUdpAddress = address;
    }

    InetAddress pinnedPeerAddress() {
        return pinnedPeerAddress;
    }

    /**
     * True when a datagram may be accepted from this source. Address only, for the reason given on
     * {@link #pinnedPeerAddress}; an unpinned link (no TCP connection yet) accepts any source, and
     * the session token is what stops that being a hole.
     */
    boolean acceptsSource(SocketAddress source) {
        if (pinnedPeerAddress == null) {
            return true;
        }
        if (!(source instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            return false;
        }
        return pinnedPeerAddress.equals(inet.getAddress());
    }

    boolean shouldWarnForeignSource() {
        if (foreignDatagramWarned) {
            return false;
        }
        foreignDatagramWarned = true;
        return true;
    }

    SocketAddress candidateUdpAddress() {
        return candidateUdpAddress;
    }

    String candidateNonce() {
        return candidateNonce;
    }

    long candidateFirstSeenAtMillis() {
        return candidateFirstSeenAtMillis;
    }

    long candidateLastProbeAtMillis() {
        return candidateLastProbeAtMillis;
    }

    void noteCandidateProbed(long nowMillis) {
        candidateLastProbeAtMillis = nowMillis;
    }

    /**
     * Starts challenging an unproven source. Refused while a challenge is already outstanding: an
     * attacker able to spray source ports must not be able to make us mint a nonce per packet.
     *
     * @return true when this source became the candidate
     */
    boolean beginCandidate(SocketAddress source, String nonce, long nowMillis) {
        if (candidateUdpAddress != null) {
            return false;
        }
        candidateUdpAddress = source;
        candidateNonce = nonce;
        candidateFirstSeenAtMillis = nowMillis;
        candidateLastProbeAtMillis = 0L;
        return true;
    }

    /**
     * Completes a challenge. The echo is only believed from the exact address that was challenged: a
     * nonce that comes back from somewhere else proves an on-path observer, not a peer.
     *
     * @return true when the candidate was promoted to the send target
     */
    boolean completeCandidate(SocketAddress source, String nonce) {
        if (candidateUdpAddress == null || candidateNonce == null
                || !candidateNonce.equals(nonce) || !candidateUdpAddress.equals(source)) {
            return false;
        }
        validatedUdpAddress = candidateUdpAddress;
        candidateTimeoutLogged = false;
        forgetCandidate();
        return true;
    }

    void forgetCandidate() {
        candidateUdpAddress = null;
        candidateNonce = null;
        candidateFirstSeenAtMillis = 0L;
        candidateLastProbeAtMillis = 0L;
    }

    /**
     * One log line per connection for a candidate that never answers; a packet flood from rotating
     * ports would otherwise get to write one every timeout for as long as it keeps going.
     */
    boolean shouldLogCandidateTimeout() {
        if (candidateTimeoutLogged) {
            return false;
        }
        candidateTimeoutLogged = true;
        return true;
    }

    long lastDatagramSentAtMillis() {
        return lastDatagramSentAtMillis;
    }

    void noteDatagramSent(long nowMillis) {
        lastDatagramSentAtMillis = nowMillis;
    }

    long lastInboundDatagramAtMillis() {
        return lastInboundDatagramAtMillis;
    }

    void noteInboundDatagram(long nowMillis) {
        lastInboundDatagramAtMillis = nowMillis;
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /**
     * Full reset to the "never used" state, for a transport shutdown. Queues included: a session
     * restarted inside the same game process would otherwise replay the previous one's leftovers
     * (a stale {@code HANDSHAKE_RESULT}, say) into the fresh connection.
     */
    void reset() {
        channel = null;
        pendingWrite = null;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
        deferredInbound = null;
        lastInboundFrameAtMillis = 0L;
        attachedAtMillis = 0L;
        senderId = null;
        pinnedPeerAddress = null;
        validatedUdpAddress = null;
        forgetCandidate();
        lastDatagramSentAtMillis = 0L;
        lastInboundDatagramAtMillis = 0L;
        foreignDatagramWarned = false;
        candidateTimeoutLogged = false;
        queueDepthWarned = false;
        datagramSendFailureLogged = false;
        invalidFrames = 0;
        outbound.clear();
        outboundDatagrams.clear();
    }

    @Override
    public String toString() {
        return "CoopPeerLink[slot=" + slot
                + ", senderId=" + (senderId == null ? "<unknown>" : senderId)
                + ", pinned=" + (pinnedPeerAddress == null ? "<none>" : pinnedPeerAddress.getHostAddress())
                + ", target=" + (validatedUdpAddress == null ? "<none>" : validatedUdpAddress)
                + "]";
    }
}
