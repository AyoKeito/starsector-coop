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

    /**
     * Hard cap on the <em>arming</em> delay. A minute is long enough to leave a menu, close a dialog
     * and be back on the campaign map before the outage lands, and short enough that a fault armed
     * and then forgotten still fires inside the smoke step that asked for it rather than during the
     * next one.
     */
    public static final int MAX_DELAY_SECONDS = 60;

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
    private final long startsAtMillis;
    private final long endsAtMillis;

    private long discardedBytes;
    private long droppedDatagrams;
    /** Latched by {@link #announceStart}, so the armed-to-active flip is logged once, not per poll. */
    private boolean startAnnounced;

    private CoopNetFault(Mode mode, int seconds, int lossPercent, int delaySeconds, long nowMillis) {
        this.mode = mode;
        this.seconds = seconds;
        this.lossPercent = lossPercent;
        this.startsAtMillis = nowMillis + delaySeconds * 1000L;
        this.endsAtMillis = this.startsAtMillis + seconds * 1000L;
    }

    /**
     * @param seconds 1..{@link #MAX_SECONDS}; anything else is refused rather than clamped, because a
     *                clamped duration would silently answer a different question than the one asked
     */
    public static CoopNetFault discard(long nowMillis, int seconds) {
        return discard(nowMillis, seconds, 0);
    }

    /**
     * @param delaySeconds 0..{@link #MAX_DELAY_SECONDS}. Above zero the fault is <em>armed</em>: it
     *                     exists, it is reported, and it does nothing to traffic until the delay
     *                     elapses. This is for the human at the two game windows — an outage that
     *                     lands while they are still in a menu proves nothing, so the fault announces
     *                     itself first and the HUD counts it down
     */
    public static CoopNetFault discard(long nowMillis, int seconds, int delaySeconds) {
        return new CoopNetFault(Mode.DISCARD, requireSeconds(seconds), 0,
                requireDelaySeconds(delaySeconds), nowMillis);
    }

    /** @param lossPercent 1..100; 0 is refused because "a fault that does nothing" is never wanted */
    public static CoopNetFault loss(long nowMillis, int seconds, int lossPercent) {
        return loss(nowMillis, seconds, lossPercent, 0);
    }

    /** @param delaySeconds 0..{@link #MAX_DELAY_SECONDS}; see {@link #discard(long, int, int)} */
    public static CoopNetFault loss(long nowMillis, int seconds, int lossPercent, int delaySeconds) {
        if (lossPercent < 1 || lossPercent > 100) {
            throw new IllegalArgumentException("lossPercent must be between 1 and 100, got " + lossPercent);
        }
        return new CoopNetFault(Mode.LOSS, requireSeconds(seconds), lossPercent,
                requireDelaySeconds(delaySeconds), nowMillis);
    }

    private static int requireDelaySeconds(int delaySeconds) {
        if (delaySeconds < 0 || delaySeconds > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("delaySeconds must be between 0 and "
                    + MAX_DELAY_SECONDS + ", got " + delaySeconds);
        }
        return delaySeconds;
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

    /** When the outage begins. Equal to the request instant unless a delay armed it. */
    public long startsAtMillis() {
        return startsAtMillis;
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

    /**
     * True while the fault exists but has not begun. An armed fault touches nothing — it is a
     * promise with a countdown, and the countdown is the whole point of arming one.
     */
    public boolean armedAt(long nowMillis) {
        return nowMillis < startsAtMillis;
    }

    /** True exactly while the fault is affecting traffic: started, and not yet expired. */
    public boolean activeAt(long nowMillis) {
        return !armedAt(nowMillis) && !expiredAt(nowMillis);
    }

    /**
     * Whole seconds until the fault starts, rounded UP so a countdown never shows a number the
     * player has already gone past: 4.2 s away reads 5, exactly 4.0 s away reads 4, and it reaches
     * zero only once the fault is running. Zero for a fault that has already started.
     */
    public long startsInSeconds(long nowMillis) {
        long remaining = startsAtMillis - nowMillis;
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    /**
     * Whole seconds of outage left, floored at zero, which is what the bridge reports.
     *
     * <p>While armed this is the full requested duration rather than the distance to
     * {@link #endsAtMillis()}: none of the outage has run yet, and reporting "delay plus seconds"
     * would claim a longer outage than the one that was asked for.
     */
    public long remainingSeconds(long nowMillis) {
        if (armedAt(nowMillis)) {
            return seconds;
        }
        long remaining = endsAtMillis - nowMillis;
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    /**
     * Latches the armed &rarr; active flip. Returns true exactly once, on the first call at or after
     * the start instant, so a caller stepping this from a per-poll check logs the started line once
     * instead of on every poll for the rest of the outage.
     */
    public boolean announceStart(long nowMillis) {
        if (startAnnounced || armedAt(nowMillis)) {
            return false;
        }
        startAnnounced = true;
        return true;
    }

    /** True while a discard fault is live. Loss mode leaves TCP alone by design. */
    public boolean shouldDiscardTcp(long nowMillis) {
        return mode == Mode.DISCARD && activeAt(nowMillis);
    }

    /**
     * Whether this inbound datagram should be thrown away. Pure: the caller records the drop through
     * {@link #noteDroppedDatagram()}, so a predicate never doubles as a counter.
     *
     * <p>Discard mode drops everything, transport traffic included — the point is that this side
     * hears nothing, and a path probe that still echoed would keep the link looking healthy.
     */
    public boolean shouldDropDatagram(long nowMillis, Random random) {
        if (!activeAt(nowMillis)) {
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

    /**
     * {@code "discard armed, starts in 5 s for 40 s"} — the warning line, logged when the fault is
     * scheduled rather than when it lands. The loss fraction rides in the same place it does in
     * {@link #describe}, so the two lines read alike.
     */
    public String describeArmed(long nowMillis) {
        String tail = mode == Mode.LOSS ? " (" + lossPercent + "% of inbound datagrams)" : "";
        return mode.wireName() + " armed, starts in " + startsInSeconds(nowMillis) + " s for "
                + seconds + " s" + tail;
    }
}
