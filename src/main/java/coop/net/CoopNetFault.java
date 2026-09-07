package coop.net;

import java.util.Random;

/**
 * A deliberate, self-expiring inbound outage on <em>this</em> instance's transport, so a tester can
 * reproduce a link drop on demand instead of freezing a JVM from outside.
 *
 * <p><b>What it models.</b> The loss the 0.1.1 reliable-delivery layer exists to heal is "bytes the
 * sender's TCP stack considers delivered, but the receiver never applied". Suspending the peer
 * process does not produce that — it stops the peer from sending. Going deaf here does: the sender
 * keeps writing, its kernel keeps accepting, this side reads the bytes off the socket and throws
 * them away without framing them. Nothing downstream of {@code readAvailableLocked} ever learns the
 * frames existed.
 *
 * <p><b>Why the silence clock must not be touched.</b> Link death is declared on ~15 s of inbound
 * silence, measured from the peer's {@code lastInboundFrameAtMillis}. If discarding refreshed that
 * clock the link would stay nominally alive while applying nothing, which is a different (and
 * unrealistic) failure. Discarded bytes therefore update nothing but this object's counters, and the
 * link dies through the ordinary silence path.
 *
 * <p><b>Outbound is never affected.</b> In every mode this instance keeps sending and the peer keeps
 * hearing it. A symmetric outage is the verb run on both instances.
 *
 * <p>Pure logic on purpose: no sockets, no locks, no clock of its own. The caller supplies "now" and
 * the {@link Random}, which is what makes the loss sampling testable with a seed.
 */
public final class CoopNetFault {

    /**
     * Hard cap on a fault's lifetime. Three minutes is far past the 15 s silence rule and the 60 s
     * reconnect grace it is meant to exercise, and short enough that a fault forgotten mid-session
     * clears itself before it can be mistaken for a real defect.
     */
    public static final int MAX_SECONDS = 180;

    /** What an active fault does to inbound traffic. */
    public enum Mode {
        /** Read and throw away every inbound byte, TCP and UDP alike. */
        DISCARD,
        /** Drop a sampled fraction of inbound datagrams; TCP is untouched. */
        LOSS;

        /** Lower-case wire form, which is what the bridge verb takes and returns. */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Mode mode;
    private final int seconds;
    private final int lossPercent;
    private final long endsAtMillis;

    private long discardedBytes;
    private long droppedDatagrams;

    private CoopNetFault(Mode mode, int seconds, int lossPercent, long nowMillis) {
        this.mode = mode;
        this.seconds = seconds;
        this.lossPercent = lossPercent;
        this.endsAtMillis = nowMillis + seconds * 1000L;
    }

    /**
     * @param seconds 1..{@link #MAX_SECONDS}; anything else is refused rather than clamped, because a
     *                clamped duration would silently answer a different question than the one asked
     */
    public static CoopNetFault discard(long nowMillis, int seconds) {
        return new CoopNetFault(Mode.DISCARD, requireSeconds(seconds), 0, nowMillis);
    }

    /** @param lossPercent 1..100; 0 is refused because "a fault that does nothing" is never wanted */
    public static CoopNetFault loss(long nowMillis, int seconds, int lossPercent) {
        if (lossPercent < 1 || lossPercent > 100) {
            throw new IllegalArgumentException("lossPercent must be between 1 and 100, got " + lossPercent);
        }
        return new CoopNetFault(Mode.LOSS, requireSeconds(seconds), lossPercent, nowMillis);
    }

    private static int requireSeconds(int seconds) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("seconds must be between 1 and " + MAX_SECONDS
                    + ", got " + seconds);
        }
        return seconds;
    }

    public Mode mode() {
        return mode;
    }

    /** The duration as requested, for the log line and the "replaced" message. */
    public int seconds() {
        return seconds;
    }

    public int lossPercent() {
        return lossPercent;
    }

    public long endsAtMillis() {
        return endsAtMillis;
    }

    public long discardedBytes() {
        return discardedBytes;
    }

    public long droppedDatagrams() {
        return droppedDatagrams;
    }

    /** Inclusive at the deadline: a fault started for 1 s is over at {@code start + 1000}. */
    public boolean expiredAt(long nowMillis) {
        return nowMillis >= endsAtMillis;
    }

    /** Whole seconds left, floored at zero, which is what the bridge reports. */
    public long remainingSeconds(long nowMillis) {
        long remaining = endsAtMillis - nowMillis;
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    /** True while a discard fault is live. Loss mode leaves TCP alone by design. */
    public boolean shouldDiscardTcp(long nowMillis) {
        return mode == Mode.DISCARD && !expiredAt(nowMillis);
    }

    /**
     * Whether this inbound datagram should be thrown away. Pure: the caller records the drop through
     * {@link #noteDroppedDatagram()}, so a predicate never doubles as a counter.
     *
     * <p>Discard mode drops everything, transport traffic included — the point is that this side
     * hears nothing, and a path probe that still echoed would keep the link looking healthy.
     */
    public boolean shouldDropDatagram(long nowMillis, Random random) {
        if (expiredAt(nowMillis)) {
            return false;
        }
        if (mode == Mode.DISCARD) {
            return true;
        }
        // 100 always drops without consuming a draw the caller would have to reason about.
        return lossPercent >= 100 || random.nextInt(100) < lossPercent;
    }

    public void noteDiscardedBytes(long bytes) {
        if (bytes > 0L) {
            discardedBytes += bytes;
        }
    }

    public void noteDroppedDatagram() {
        droppedDatagrams++;
    }

    /** {@code "discarded 12345 bytes, dropped 0 datagrams"} — the tail of every end-of-fault log line. */
    public String describeCounters() {
        return "discarded " + discardedBytes + " bytes, dropped " + droppedDatagrams + " datagrams";
    }

    /** {@code "discard for 40 s"} / {@code "loss for 40 s (30% of inbound datagrams)"}. */
    public String describe() {
        if (mode == Mode.LOSS) {
            return "loss for " + seconds + " s (" + lossPercent + "% of inbound datagrams)";
        }
        return "discard for " + seconds + " s";
    }
}
