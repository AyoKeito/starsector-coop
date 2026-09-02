package coop.net;

/**
 * Picks the state streams' {@link CoopCadenceTier} for one link (Phase 29 M2). Host-side: the host
 * evaluates, applies the answer to its own streams, and announces it on {@code LINK_STATUS}; the
 * guest applies what it is told and never runs this class. One decision-maker per link means the two
 * ends cannot disagree about the rate, which is what keeps the receiver's interpolation delay — sized
 * in send intervals — meaningful on both sides.
 *
 * <p><b>Fast attack, slow recovery.</b> A downshift is immediate on any one trigger; an upshift needs
 * {@link #CLEAN_WINDOW_MILLIS} of continuously clean evidence, and a single dirty evaluation restarts
 * that window from zero. The thresholds are hysteretic too — {@link #DOWNSHIFT_LOSS_PERCENT} down
 * against {@link #UPSHIFT_LOSS_PERCENT} up, {@link #DOWNSHIFT_RTT_MILLIS} against
 * {@link #UPSHIFT_RTT_MILLIS} — so a link parked exactly on a threshold cannot oscillate. This is the
 * published asymmetry (Unreal's adaptive net frequency speeds up on change and decays over seconds);
 * ours is coarser because the tiers are discrete.
 *
 * <p><b>Why the p50 and not the p95 or the EWMA.</b> RTT here is measured by TCP {@code PING}/
 * {@code PONG} across campaign frames, so every sample carries up to one frame interval per side on
 * top of the network. A client whose frame rate is capped — a driver profile, a backgrounded window —
 * inflates every sample it answers, and the tail statistic is exactly where that lands: the Phase 20
 * real-Internet smoke measured a tunnel alternating between ~53 ms and ~181 ms with the peaks tracking
 * the frame cap rather than the path. Keying the tier on the p95 would let a capped client flap the
 * rate for both players. The median is the robust reading of the same ring.
 *
 * <p>Pure logic on the caller's wall clock: no sockets, no engine types, one instance per pump.
 */
public final class CoopCadenceController {

    /** Datagram loss at or above this downshifts immediately. */
    public static final int DOWNSHIFT_LOSS_PERCENT = 10;
    /** Median RTT at or above this downshifts immediately. */
    public static final int DOWNSHIFT_RTT_MILLIS = 400;
    /** Loss must be at or below this to count as clean. */
    public static final int UPSHIFT_LOSS_PERCENT = 3;
    /** Median RTT must be strictly below this to count as clean. */
    public static final int UPSHIFT_RTT_MILLIS = 300;
    /** Continuously clean for this long before one step up the ladder. */
    public static final long CLEAN_WINDOW_MILLIS = 30_000L;

    /** Reason text for a tier that is where it is because the clean window completed. */
    public static final String REASON_CLEAN = "clean " + (CLEAN_WINDOW_MILLIS / 1000L) + " s";
    /** Reason text while the state stream is wrapped in TCP. */
    public static final String REASON_FALLBACK = "tcp fallback";
    /** Reason text while the outbound TCP queue is backed up. */
    public static final String REASON_BACKLOG = "backlog";
    /** Reason text before anything has been evaluated. */
    public static final String REASON_INITIAL = "initial";

    /**
     * One evaluation's outcome.
     *
     * @param tier    the tier in force after this evaluation
     * @param reason  short human-readable cause, for the log line and the feed
     * @param changed whether {@code tier} differs from the one before this evaluation
     */
    public record Decision(CoopCadenceTier tier, String reason, boolean changed) {
    }

    private CoopCadenceTier tier = CoopCadenceTier.DEFAULT;
    private String reason = REASON_INITIAL;
    private boolean cleanSincePrimed;
    private long cleanSinceMillis;
    private boolean lossDriven;

    /**
     * One evaluation, run on the pump's 1 s link tick.
     *
     * @param nowMillis       wall clock
     * @param p50RttMillis    median of the RTT sample ring, or null when nothing has been measured —
     *                        an unmeasured link neither downshifts nor is held back from upshifting,
     *                        because "no PONG yet" is the first seconds of every session
     * @param lossPercent     raw datagram loss over the measurement window
     * @param outboundBacklog the transport's outbound TCP queue is past its coalescing threshold
     * @param fallbackActive  the state stream is currently wrapped in TCP
     */
    public Decision evaluate(long nowMillis, Integer p50RttMillis, int lossPercent,
                             boolean outboundBacklog, boolean fallbackActive) {
        String dirty = dirtyReason(p50RttMillis, lossPercent, outboundBacklog, fallbackActive);
        CoopCadenceTier before = tier;
        if (dirty != null) {
            cleanSincePrimed = false;
            cleanSinceMillis = 0L;
            lossDriven = lossPercent >= DOWNSHIFT_LOSS_PERCENT;
            tier = CoopCadenceTier.FLOOR;
            reason = dirty;
            return new Decision(tier, reason, tier != before);
        }

        lossDriven = false;
        if (!clean(p50RttMillis, lossPercent, outboundBacklog, fallbackActive)) {
            // The middle band: past the upshift thresholds, short of the downshift ones. The tier
            // holds where it is and the clean window does not accumulate — "continuously clean" is
            // the upshift condition, and a link sitting at 6% loss is not that.
            cleanSincePrimed = false;
            cleanSinceMillis = 0L;
            return new Decision(tier, reason, false);
        }
        if (!cleanSincePrimed) {
            cleanSincePrimed = true;
            cleanSinceMillis = nowMillis;
        }
        CoopCadenceTier ceiling = CoopCadenceTier.highestEnabled();
        if (tier.ordinal() < ceiling.ordinal()
                && nowMillis - cleanSinceMillis >= CLEAN_WINDOW_MILLIS) {
            tier = tier.upshift();
            reason = REASON_CLEAN;
            // The window restarts, so climbing two steps takes two windows. Only reachable once TOP
            // is certified; with TOP dark the ladder is one step and this line never runs twice.
            cleanSinceMillis = nowMillis;
        }
        return new Decision(tier, reason, tier != before);
    }

    /**
     * The one downshift trigger that fired, or null when none did. Order is by how much the player
     * can do about it: an unusable path first, then the two link measurements, then our own queue.
     */
    private static String dirtyReason(Integer p50RttMillis, int lossPercent,
                                      boolean outboundBacklog, boolean fallbackActive) {
        if (fallbackActive) {
            return REASON_FALLBACK;
        }
        if (lossPercent >= DOWNSHIFT_LOSS_PERCENT) {
            return "loss " + lossPercent + "%";
        }
        if (p50RttMillis != null && p50RttMillis >= DOWNSHIFT_RTT_MILLIS) {
            return "rtt p50 " + p50RttMillis + " ms";
        }
        if (outboundBacklog) {
            return REASON_BACKLOG;
        }
        return null;
    }

    /**
     * Whether the evaluation would currently count as clean — the upshift window only accumulates
     * while this holds. Split out so the band between the two threshold pairs is visible: a link at
     * 6% loss is not dirty enough to downshift and not clean enough to climb back.
     */
    private static boolean clean(Integer p50RttMillis, int lossPercent, boolean outboundBacklog,
                                 boolean fallbackActive) {
        return !fallbackActive
                && !outboundBacklog
                && lossPercent <= UPSHIFT_LOSS_PERCENT
                && (p50RttMillis == null || p50RttMillis < UPSHIFT_RTT_MILLIS);
    }

    /** The tier currently in force. */
    public CoopCadenceTier tier() {
        return tier;
    }

    /** Why the tier is what it is; the string the log line and the feed print. */
    public String reason() {
        return reason;
    }

    /**
     * Whether the current floor was chosen because of measured loss, as opposed to latency, backlog
     * or the TCP fallback. This is the redundancy-depth gate: an extra previous section is worth its
     * bytes against loss and is actively harmful against a backlog.
     */
    public boolean lossDriven() {
        return lossDriven;
    }

    /** Session edge: back to the default tier with no history, silently. */
    public void reset() {
        tier = CoopCadenceTier.DEFAULT;
        reason = REASON_INITIAL;
        cleanSincePrimed = false;
        cleanSinceMillis = 0L;
        lossDriven = false;
    }
}
