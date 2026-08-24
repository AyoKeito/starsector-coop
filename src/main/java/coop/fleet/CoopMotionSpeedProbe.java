package coop.fleet;

/**
 * {@link coop.util.CoopDebug}-gated diagnostic (Phase 29 M1): answers "do interpolated mirrors move
 * at the real fleets' speed?" with a number instead of an eyeball. Every mirror accumulates two
 * distances per window — what it actually <em>rendered</em> (sum of frame-to-frame moves out of
 * {@link CoopFleetMirror#advanceMotion}) and what the <em>authoritative samples</em> said (sum of
 * sample-to-sample moves as they arrive) — and the pump logs the ratio every
 * {@link #WINDOW_MILLIS}. On a clean link the ratio sits at ~1.00: the 200 ms render delay shifts
 * the window edges by one in-flight hop, so short windows read within a couple percent, converging
 * with window length. Under loss it legitimately dips (starvation decay parks a mirror the samples
 * say kept moving) — which makes this the shaped-loopback pass's speed instrument too.
 *
 * <p>Teleport cuts and location changes reset both trackers mirror-side before they can pollute a
 * window (a hard cut is not "speed"). Pure accumulator, no engine or clock reads: the pump supplies
 * the clock and does the logging; mirrors are the only writers, all on the campaign thread.
 */
public final class CoopMotionSpeedProbe {

    /** Shared by every mirror; the pump drains it. Campaign-thread only, like the mirrors. */
    public static final CoopMotionSpeedProbe INSTANCE = new CoopMotionSpeedProbe();

    static final long WINDOW_MILLIS = 10_000L;

    private double renderedDistance;
    private double authorityDistance;
    private int renderedMoves;
    private int authorityMoves;
    private long windowStartedAtMillis = Long.MIN_VALUE;

    public void recordRendered(double distance) {
        if (distance > 0) {
            renderedDistance += distance;
            renderedMoves++;
        }
    }

    public void recordAuthority(double distance) {
        if (distance > 0) {
            authorityDistance += distance;
            authorityMoves++;
        }
    }

    /**
     * One line describing the finished window, or null while the window is still open or held
     * nothing. Resets the accumulators when it reports (and re-arms the window either way).
     */
    public String maybeReport(long nowMillis) {
        if (windowStartedAtMillis == Long.MIN_VALUE) {
            windowStartedAtMillis = nowMillis;
            return null;
        }
        if (nowMillis - windowStartedAtMillis < WINDOW_MILLIS) {
            return null;
        }
        long windowMillis = nowMillis - windowStartedAtMillis;
        windowStartedAtMillis = nowMillis;
        if (authorityMoves == 0 && renderedMoves == 0) {
            return null;
        }
        String report = String.format(java.util.Locale.ROOT,
                "Coop motion speed probe window=%.1fs rendered=%.0fsu(%d moves)"
                        + " authority=%.0fsu(%d samples) ratio=%s",
                windowMillis / 1000.0, renderedDistance, renderedMoves,
                authorityDistance, authorityMoves,
                authorityDistance > 0
                        ? String.format(java.util.Locale.ROOT, "%.3f",
                                renderedDistance / authorityDistance)
                        : "n/a");
        renderedDistance = 0;
        authorityDistance = 0;
        renderedMoves = 0;
        authorityMoves = 0;
        return report;
    }

    /** Drops the open window and everything in it (session teardown, diagnostics toggled off). */
    public void reset() {
        renderedDistance = 0;
        authorityDistance = 0;
        renderedMoves = 0;
        authorityMoves = 0;
        windowStartedAtMillis = Long.MIN_VALUE;
    }
}
