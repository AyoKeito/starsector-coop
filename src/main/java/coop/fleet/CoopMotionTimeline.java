package coop.fleet;

/**
 * The render cursor for one remote sender's state streams (Phase 29 M1). All of a peer's mirrors
 * share it: the cursor lives on the sender's stream-time axis ({@code CoopStreamClock} stamps), sits
 * {@link #DELAY_SECONDS} behind the newest received stamp, and advances by the local campaign frame
 * dt — so a shared pause freezes every mirror in place for free, and both peers' rates match by the
 * Phase 11 shared clock rather than any RTT estimate (no RTT/2 anywhere; see the research banner).
 *
 * <p>Drift between the cursor and its target is absorbed by <em>time-scaling with a dead zone</em>,
 * never positional blending: inside ±{@link #DEAD_ZONE_SECONDS} the timescale is exactly 1 (Mirror's
 * documented anti-ping-pong guard), outside it the cursor runs 2% fast or 4% slow — imperceptible at
 * campaign speeds. A drift beyond {@link #RESEAT_SECONDS} (reconnect outage, session hiccup) re-seats
 * the cursor outright: replaying or slow-absorbing 30 s of motion is worse than the one visible jump
 * the mirrors' own teleport guard already handles.
 *
 * <p>Pure math, no engine types; the pump owns one per remote peer (exactly one in v1).
 */
public final class CoopMotionTimeline {

    /**
     * Fixed M1 render delay: two send intervals at the 10 Hz stream rate, the Valve 2-interval rule.
     * M2 makes this adaptive from measured jitter (Mirror's formula, clamped [0.15, 0.5]).
     */
    public static final double DELAY_SECONDS = 0.200;
    /** ±1 send interval around the target where the timescale is exactly 1. */
    static final double DEAD_ZONE_SECONDS = 0.100;
    static final double CATCHUP_TIMESCALE = 1.02;
    static final double SLOWDOWN_TIMESCALE = 0.96;
    /** Drift EMA horizon, in game seconds. */
    static final double DRIFT_EMA_SECONDS = 1.0;
    /** Drift beyond this re-seats the cursor at the target instead of time-scaling toward it. */
    static final double RESEAT_SECONDS = 1.0;

    private double latestSampleTime = Double.NaN;
    private double cursor = Double.NaN;
    private double driftEma;

    /** Raises the newest-known sender stamp; older stamps (redundant sections) are ignored. */
    public void noteSample(double sampleTimeSeconds) {
        if (Double.isNaN(latestSampleTime) || sampleTimeSeconds > latestSampleTime) {
            latestSampleTime = sampleTimeSeconds;
        }
    }

    /**
     * Advances by one campaign frame (dt 0 while paused) and returns the render cursor, or NaN until
     * the first sample arrives.
     */
    public double advance(double gameDtSeconds) {
        if (Double.isNaN(latestSampleTime)) {
            return Double.NaN;
        }
        double target = latestSampleTime - DELAY_SECONDS;
        if (Double.isNaN(cursor)) {
            cursor = target;
            driftEma = 0.0;
            return cursor;
        }
        double drift = target - cursor;
        if (Math.abs(drift) > RESEAT_SECONDS) {
            cursor = target;
            driftEma = 0.0;
            return cursor;
        }
        if (gameDtSeconds > 0.0) {
            double alpha = Math.min(1.0, gameDtSeconds / DRIFT_EMA_SECONDS);
            driftEma += (drift - driftEma) * alpha;
            double timescale = 1.0;
            if (driftEma > DEAD_ZONE_SECONDS) {
                timescale = CATCHUP_TIMESCALE;
            } else if (driftEma < -DEAD_ZONE_SECONDS) {
                timescale = SLOWDOWN_TIMESCALE;
            }
            cursor += gameDtSeconds * timescale;
        }
        return cursor;
    }

    /** The cursor without advancing (diagnostics + tests). NaN until the first sample. */
    public double cursor() {
        return cursor;
    }

    /** Forgets everything (session teardown); the next sample re-bootstraps the cursor. */
    public void reset() {
        latestSampleTime = Double.NaN;
        cursor = Double.NaN;
        driftEma = 0.0;
    }
}
