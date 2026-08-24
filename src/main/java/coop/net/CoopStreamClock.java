package coop.net;

/**
 * Outbound stamping for the UDP state streams (Phase 29 M1 wire prerequisite): a monotonic per-sender
 * datagram {@code epoch} plus the sender's <em>stream time</em> — the accumulated unpaused campaign
 * seconds this process has run. Stream time is the time axis the receiver's interpolation buffer sorts
 * samples onto ({@link coop.fleet.CoopMotionInterpolator}), so its one job is to advance at the same
 * rate on both peers: 0 while paused, the campaign frame dt otherwise. The shared pause (Phase 11)
 * keeps the two processes' rates aligned; absolute offsets between peers are irrelevant by design —
 * the receiver's cursor is bootstrapped from received stamps and maintained by time-scaling, never by
 * comparing clocks (see the Phase 29 research banner: no RTT/2 anywhere).
 *
 * <p>Deliberately not the engine's campaign clock: {@code CampaignClock.advance} truncates per-frame
 * (the Phase 7c drift source), and the interpolator needs a smooth axis, not a calendar. Never reset
 * mid-process — receiver watermarks key on the session id ({@link CoopDatagramWatermark}), so a fresh
 * session gets fresh watermarks while a continuing process keeps its monotonic epochs.
 */
public final class CoopStreamClock {

    private double gameSeconds;
    private long epoch;

    /**
     * Advances stream time by one campaign frame. {@code paused} gates to zero — a paused campaign
     * emits samples with frozen stamps, which the interpolator's frozen cursor renders in place.
     */
    public void advance(float dtSeconds, boolean paused) {
        if (!paused && dtSeconds > 0f) {
            gameSeconds += dtSeconds;
        }
    }

    /** Stream time in whole milliseconds (the wire unit; see {@link CoopMessages#datagram}). */
    public long gameTimeMillis() {
        return (long) (gameSeconds * 1000.0);
    }

    /** Next per-sender datagram epoch, starting at 1. Monotonic for the life of the process. */
    public long nextEpoch() {
        return ++epoch;
    }

    /** Test seam only — production code never resets (see the class doc). */
    void reset() {
        gameSeconds = 0.0;
        epoch = 0L;
    }
}
