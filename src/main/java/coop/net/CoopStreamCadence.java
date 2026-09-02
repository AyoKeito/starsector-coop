package coop.net;

/**
 * Send-cadence rule for the UDP state streams whose samples feed the receiver's interpolation buffer
 * (Phase 29 line item, landed with Phase 7b).
 *
 * <p>Those streams are stamped with {@link CoopStreamClock#gameTimeMillis()} — <em>stream time</em>,
 * the accumulated unpaused campaign seconds — and the receiver sorts them onto that axis with a fixed
 * {@code CoopMotionTimeline.DELAY_SECONDS} (0.2 s of <em>game</em> time) of buffer. A wall-clock send
 * timer therefore thins the buffer exactly when it matters: under 2x fast-forward the campaign covers
 * 200 ms of stream time in 100 ms of wall time, so a 100 ms wall timer emits one sample per 200 ms of
 * stream time and the 0.2 s delay window holds one sample instead of two.
 *
 * <p>So the rule is measured in stream time: send when stream time has advanced by the interval. The
 * one exception is a frozen stream — while the campaign is paused stream time does not advance at
 * all, and the pre-existing behaviour (keep emitting frozen-stamp samples, which the interpolator's
 * frozen cursor is documented to render in place) is preserved by falling back to the wall clock.
 *
 * <p>Pure state machine, no engine types: one instance per outbound stream.
 */
public final class CoopStreamCadence {

    private long intervalMillis;

    private boolean primed;
    private long lastStreamMillis;
    private long lastWallMillis;

    public CoopStreamCadence(long intervalMillis) {
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be positive: " + intervalMillis);
        }
        this.intervalMillis = intervalMillis;
    }

    /**
     * Polled once per frame by the sender. Returns true at most once per interval, and records the
     * send itself — a caller that then fails to build its payload still waits a full interval, which
     * is what the old {@code finally}-block timers did.
     *
     * @param streamMillis current {@link CoopStreamClock#gameTimeMillis()}
     * @param wallMillis   current wall clock (the pump's injected {@code clockMillis})
     * @param streamFrozen whether stream time is standing still this frame (campaign paused)
     */
    public boolean shouldSend(long streamMillis, long wallMillis, boolean streamFrozen) {
        if (!primed) {
            primed = true;
            lastStreamMillis = streamMillis;
            lastWallMillis = wallMillis;
            return false;
        }
        boolean due = streamMillis - lastStreamMillis >= intervalMillis
                || (streamFrozen && wallMillis - lastWallMillis >= intervalMillis);
        if (!due) {
            return false;
        }
        lastStreamMillis = streamMillis;
        lastWallMillis = wallMillis;
        return true;
    }

    /**
     * Retunes the interval (Phase 20.1 M2 UDP-blocked fallback: 100 ms on UDP, 200 ms once the stream
     * is wrapped in TCP, back to 100 ms on recovery). Deliberately does <em>not</em> reset the timer:
     * the next send is due one new interval after the last one, so a change mid-stream neither stalls
     * the stream nor fires an immediate extra send.
     */
    public void setIntervalMillis(long intervalMillis) {
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be positive: " + intervalMillis);
        }
        this.intervalMillis = intervalMillis;
    }

    /** The interval currently in force. */
    public long intervalMillis() {
        return intervalMillis;
    }

    /** Session (re)start: forget the last send so the next poll re-primes. */
    public void reset() {
        primed = false;
        lastStreamMillis = 0L;
        lastWallMillis = 0L;
    }
}
