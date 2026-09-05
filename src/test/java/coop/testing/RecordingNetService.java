package coop.testing;

import coop.net.CoopConnectionRole;
import coop.net.CoopDatagramStats;
import coop.net.CoopMessages;
import coop.net.CoopNetService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * The one {@link CoopNetService} fake the test tree records against.
 *
 * <p>Seventeen test classes each grew their own copy of this. They were not the same copy: the
 * replicator tests recorded nothing but {@code sent}, the battle-bridge copy added an inbound queue,
 * the mission-claim copy added {@code sendTo}, the special-stack copy added {@code lastOfType}, and
 * the pump copy added the whole transport surface (flush, datagrams, reconnect, backlog). This class
 * is their union, so every one of those assertions still reads the field or calls the method it
 * always did.
 *
 * <p>Reconciled differences, all resolved in favour of the richer behaviour because the simpler one
 * was only ever "the base class did nothing here":
 * <ul>
 *   <li>{@code isConnected()} - the small copies hardcoded {@code true}; the pump copy read a field.
 *       The field starts {@code true}, so both read the same until a test moves it.</li>
 *   <li>{@code send()} - the pump copy also queued to a pending-write buffer that only
 *       {@link #flushOutbound()} drains. That buffer is invisible unless a test looks at
 *       {@link #flushed} or {@link #outboundIdle()}, and {@code outboundIdle()} has exactly one
 *       production caller ({@code CoopNetPump.tickTerminalStop}, itself behind a pending-stop
 *       guard).</li>
 *   <li>{@code flushOutbound()} - the battle-bridge copy overrode it to a no-op purely to keep the
 *       real socket work out; draining into {@link #flushed} is the same no-op plus a record.</li>
 *   <li>{@code sendTo()} - only the mission-claim copy overrode it, and the base class already falls
 *       back to {@code send()} when it cannot resolve a peer, so the copies that did not override it
 *       recorded the message anyway. Same one entry in {@link #sent} either way.</li>
 *   <li>{@code nextSeq()} - the battle-bridge copy's {@code ++seq} yields the same 1, 2, 3... as the
 *       base class's {@code AtomicLong.incrementAndGet()}. Kept as a field so the sequence is
 *       obvious at the call site.</li>
 * </ul>
 *
 * <p>Not final, and its members are public rather than private: {@code CoopNetPumpTest} subclasses
 * it three times, and those subclasses now live in another package.
 */
public class RecordingNetService extends CoopNetService {

    private final CoopConnectionRole role;

    /** Everything handed to {@link #send} or {@link #sendTo}, in order. */
    public final List<CoopMessages.Message> sent = new ArrayList<>();

    /** Messages a test wants the next {@link #pollInbound()} calls to return. */
    public final Queue<CoopMessages.Message> inbound = new ArrayDeque<>();

    /**
     * Connection generations to stamp onto the next messages taken from {@link #inbound}, one per
     * message, in the same order (net-fix-5). Empty means "stamp whatever
     * {@link #connectionGeneration} currently reads", which is what a test that never thinks about
     * provenance wants: one connection, one generation, and every message on it.
     *
     * <p>A test only needs to fill this to model the case the stamp exists for — a poll that reads
     * the dying connection's tail and the replacement socket's first frames into the same queue.
     */
    public final Queue<Long> inboundGenerations = new ArrayDeque<>();

    /** Snapshot types {@link #drainOverflowDroppedSnapshotTypes()} should report once (net-fix-7). */
    public final java.util.Set<CoopMessages.Type> overflowDroppedSnapshotTypes =
            java.util.EnumSet.noneOf(CoopMessages.Type.class);

    /** Queued but not yet handed to a socket; {@link #flushOutbound} is what empties it. */
    private final Queue<CoopMessages.Message> pendingOutbound = new ArrayDeque<>();

    /**
     * Messages that actually reached the wire, in order. The real transport drops whatever is
     * still queued when it closes a link, so anything missing here never left this machine.
     */
    public final List<CoopMessages.Message> flushed = new ArrayList<>();

    /** Every setExpectedSessionToken call, nulls included - the clear is as load-bearing as the set. */
    public final List<String> expectedTokens = new ArrayList<>();

    /** Datagrams that went out over the real UDP path (Phase 20.1 M2 fallback tests). */
    public final List<String> datagrams = new ArrayList<>();

    /** Datagrams a test wants {@link #pollDatagram()} to return. */
    public final Queue<String> inboundDatagrams = new ArrayDeque<>();

    /** F4: reasons passed to {@link CoopNetService#stopReconnecting(String)}. */
    public final List<String> stopReconnectingReasons = new ArrayList<>();

    /** F4: {@link CoopNetService#noteLobbyRejected()} call count. */
    public int lobbyRejectBackoffs;

    private CoopDatagramStats stats = new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "");

    public boolean connected = true;

    /** Phase 29 M2 cadence input: what {@code outboundBacklogged()} is derived from. */
    public int outboundDepth;

    /**
     * A frame the kernel took only part of, parked in the peer's {@code pendingWrite}. Invisible
     * to the queue depth, which is exactly why the terminal-stop linger has to ask
     * {@link #outboundIdle()} instead.
     */
    public boolean partialWritePending;

    /** Sockets attached over this service's life; a bump with no disconnect is a half-open replace. */
    public long connectionGeneration;

    /**
     * Models the transport reading a frame and the end of the stream in one pass: the message is
     * handed over and the link is down by the time the frame's later steps look at it.
     */
    public boolean dropWhenDrained;

    private long seq;

    public RecordingNetService(CoopConnectionRole role) {
        this.role = role;
    }

    /**
     * The most recent message of {@code type}, or an {@link AssertionError} naming everything that
     * was sent instead. From the special-stack copy, where "nothing was sent" and "the wrong thing
     * was sent" needed to read differently in the failure output.
     */
    public CoopMessages.Message lastOfType(CoopMessages.Type type) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).type() == type) {
                return sent.get(i);
            }
        }
        throw new AssertionError("no " + type + " sent; got "
                + sent.stream().map(CoopMessages.Message::type).toList());
    }

    @Override
    public CoopConnectionRole role() {
        return role;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public long nextSeq() {
        return ++seq;
    }

    @Override
    public void send(CoopMessages.Message message) {
        sent.add(message);
        pendingOutbound.add(message);
    }

    @Override
    public void sendTo(String senderId, CoopMessages.Message message) {
        sent.add(message);
        pendingOutbound.add(message);
    }

    /**
     * The primitive the real service polls through, so overriding this one covers
     * {@link #pollInbound()} as well.
     */
    @Override
    public Inbound pollInboundEntry() {
        CoopMessages.Message next = inbound.poll();
        if (next == null) {
            return null;
        }
        if (dropWhenDrained && inbound.isEmpty()) {
            connected = false;
        }
        Long stamped = inboundGenerations.poll();
        return new Inbound(next, stamped == null ? connectionGeneration : stamped);
    }

    @Override
    public java.util.Set<CoopMessages.Type> drainOverflowDroppedSnapshotTypes() {
        if (overflowDroppedSnapshotTypes.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<CoopMessages.Type> drained =
                java.util.EnumSet.copyOf(overflowDroppedSnapshotTypes);
        overflowDroppedSnapshotTypes.clear();
        return drained;
    }

    /** F4: every {@code stopReconnecting} reason, so a test can prove the loop really ended. */
    @Override
    public void stopReconnecting(String reason) {
        stopReconnectingReasons.add(reason);
        // The real one closes the socket, and the disconnect edge that follows is load-bearing.
        connected = false;
        // ...and closing a link discards its queue, which is the whole defect the linger fixes:
        // a reject queued and never flushed is a reject the peer never hears.
        pendingOutbound.clear();
    }

    /** F4: how many times the retry loop was backed off to the post-reject delay. */
    @Override
    public void noteLobbyRejected() {
        lobbyRejectBackoffs++;
    }

    @Override
    public void setExpectedSessionToken(String token) {
        expectedTokens.add(token);
    }

    @Override
    public void flushOutbound() {
        CoopMessages.Message message;
        while ((message = pendingOutbound.poll()) != null) {
            flushed.add(message);
        }
    }

    @Override
    public void sendDatagram(String payload) {
        datagrams.add(payload);
    }

    @Override
    public String pollDatagram() {
        return inboundDatagrams.poll();
    }

    @Override
    public CoopDatagramStats datagramStats() {
        return stats;
    }

    @Override
    public int outboundQueueDepth() {
        return outboundDepth;
    }

    @Override
    public boolean outboundIdle() {
        return outboundDepth <= 0 && pendingOutbound.isEmpty() && !partialWritePending;
    }

    @Override
    public long connectionGeneration() {
        return connectionGeneration;
    }

    /** Whoever registered for outbound-discard reports; null until somebody does (Phase 32 add. B). */
    private coop.net.CoopOutboundDiscardListener discardListener;

    @Override
    public void setOutboundDiscardListener(coop.net.CoopOutboundDiscardListener listener) {
        super.setOutboundDiscardListener(listener);
        this.discardListener = listener;
    }

    /**
     * Fires the registered discard callback by hand. Messages sent through this fake never reach a
     * real peer queue, so the transport's own drop sites cannot produce the report here; that half is
     * covered against the real queue by {@code CoopCreditRefundTest}. What this exercises is the
     * registration: that the subscriber the production code wired up is the one that gets told.
     */
    public void reportOutboundDiscardForTest(CoopMessages.Message message, String cause) {
        if (discardListener != null) {
            discardListener.onOutboundDiscarded(message, cause);
        }
    }

    /** Backdates the one stats field the UDP-fallback tests read. */
    public void noteUdpInboundAt(long atMillis) {
        stats = new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, atMillis, "");
    }
}
