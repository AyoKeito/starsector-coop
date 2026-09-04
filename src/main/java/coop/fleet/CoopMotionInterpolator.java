package coop.fleet;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Per-mirror sample buffer + trajectory evaluation (Phase 29 M1). Received position/velocity samples
 * queue ordered by sender stream time; each frame the mirror is placed at the buffered trajectory
 * evaluated at the shared render cursor ({@link CoopMotionTimeline}), which trails the newest sample
 * by ~200 ms. Between samples the path is a cubic Hermite through position + velocity — the velocity
 * is already on the wire, and linear interpolation at 10 Hz visibly pulses on curved motion (orbits;
 * see the plan's Phase 29 research banner). Past the newest sample the <em>starvation ladder</em>
 * runs: extrapolate the last velocity for at most {@link #EXTRAPOLATION_CAP_SECONDS}, then decay
 * velocity to zero over {@link #DECAY_WINDOW_SECONDS} and park — a packet-starved mirror coasts
 * briefly and stops, it never sails off.
 *
 * <p>Pure math, no engine types: unit-tested headless, driven by {@link CoopFleetMirror}.
 *
 * <p>All constants are calibration recorded in code, not configuration (no Phase 28 key). Values
 * follow the research pass: extrapolation cap per Valve's {@code cl_extrapolate_amount} 0.25 s;
 * the tangent guard is the Hermite overshoot fallback; the teleport radius is the two-radius snap
 * backstop's outer cut (a within-location jump this large must cut, never glide).
 */
public final class CoopMotionInterpolator {

    /** Hold the last coasting velocity this long, then start decaying to a stop. */
    static final double EXTRAPOLATION_CAP_SECONDS = 0.250;
    /** Linear velocity decay window after the cap. */
    static final double DECAY_WINDOW_SECONDS = 0.200;
    /**
     * Sample-to-sample distance treated as a teleport (hard cut, buffer restart). At 10 Hz even a
     * sustained-burn fleet moves tens of su per sample; jump-point or respawn relocations are
     * thousands. Tuned on the shaped harness before freeze; su.
     */
    static final float TELEPORT_DISTANCE = 2000f;
    /**
     * Hermite tangent guard: when either tangent exceeds this multiple of the chord (plus a 1 su
     * epsilon for near-zero chords), the segment falls back to lerp — mismatched tangents are the
     * documented Hermite overshoot case.
     */
    static final float TANGENT_CHORD_RATIO_LIMIT = 3f;
    /** Bounds buffer growth if the consumer stalls (Mirror uses 32 as well). */
    static final int BUFFER_LIMIT = 32;

    /** One evaluated frame; {@code parked} means the starvation ladder has fully decayed. */
    public record Pose(float x, float y, float velocityX, float velocityY, boolean parked) {
    }

    /** What {@link #addSample} did with a sample. */
    public enum AddResult {
        /** Queued normally. */
        ADDED,
        /** Queued after a teleport-scale jump restarted the buffer — the caller hard-cuts. */
        TELEPORT,
        /** Dropped: stream time not after the newest queued sample (duplicate or stale). */
        STALE
    }

    record Sample(double time, float x, float y, float velocityX, float velocityY) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /**
     * Queues one received sample. Non-increasing stream times are dropped (the datagram watermark
     * removes most; duplicate stamps from a paused sender land here). A teleport-scale jump from the
     * previous sample restarts the buffer at it — the caller hard-cuts the fleet instead of gliding.
     */
    public AddResult addSample(double timeSeconds, float x, float y, float velocityX, float velocityY) {
        Sample last = samples.peekLast();
        if (last != null && timeSeconds <= last.time()) {
            return AddResult.STALE;
        }
        boolean teleport = false;
        if (last != null) {
            float dx = x - last.x();
            float dy = y - last.y();
            if (dx * dx + dy * dy > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
                samples.clear();
                teleport = true;
            }
        }
        samples.addLast(new Sample(timeSeconds, x, y, velocityX, velocityY));
        while (samples.size() > BUFFER_LIMIT) {
            samples.removeFirst();
        }
        return teleport ? AddResult.TELEPORT : AddResult.ADDED;
    }

    /** Drops all samples (location change, teleport, mirror teardown). */
    public void clear() {
        samples.clear();
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /**
     * Stream time of the newest queued sample, or NaN while the buffer is empty. Read by the mirror to
     * recognise a record that is older than what it has already consumed — {@link #addSample} drops
     * one of those itself, but the caller has placement work it must skip too.
     */
    public double newestSampleTime() {
        Sample last = samples.peekLast();
        return last == null ? Double.NaN : last.time();
    }

    /**
     * The pose at the render cursor, or null while the buffer is empty. Consumed samples (older than
     * the segment bracketing the cursor) are pruned as the cursor passes them.
     */
    public Pose evaluate(double cursorSeconds) {
        if (samples.isEmpty()) {
            return null;
        }
        pruneConsumed(cursorSeconds);
        Sample first = samples.peekFirst();
        if (cursorSeconds <= first.time()) {
            // Cursor still behind the buffer (fresh mirror, post-cut restart): hold at the first
            // sample; its velocity keeps facing sensible while the cursor catches up.
            return new Pose(first.x(), first.y(), first.velocityX(), first.velocityY(), false);
        }
        Sample last = samples.peekLast();
        if (cursorSeconds >= last.time()) {
            return extrapolate(last, cursorSeconds - last.time());
        }
        // The prune left first.time < cursor < second.time; interpolate that segment.
        Iterator<Sample> it = samples.iterator();
        Sample s0 = it.next();
        Sample s1 = it.next();
        return interpolate(s0, s1, cursorSeconds);
    }

    /** Keeps the newest sample at or before the cursor plus everything after it. */
    private void pruneConsumed(double cursorSeconds) {
        while (samples.size() >= 2) {
            Iterator<Sample> it = samples.iterator();
            it.next();
            if (it.next().time() <= cursorSeconds) {
                samples.removeFirst();
            } else {
                return;
            }
        }
    }

    private static Pose interpolate(Sample s0, Sample s1, double cursorSeconds) {
        float h = (float) (s1.time() - s0.time());
        float s = (float) ((cursorSeconds - s0.time()) / h);
        float chordX = s1.x() - s0.x();
        float chordY = s1.y() - s0.y();
        float chord = (float) Math.sqrt(chordX * chordX + chordY * chordY);
        // Tangents in the unit-parameter form must be velocity * segment dt, or the curve overshoots.
        float m0x = s0.velocityX() * h;
        float m0y = s0.velocityY() * h;
        float m1x = s1.velocityX() * h;
        float m1y = s1.velocityY() * h;
        float limit = TANGENT_CHORD_RATIO_LIMIT * chord + 1f;
        float limitSq = limit * limit;
        if (m0x * m0x + m0y * m0y > limitSq || m1x * m1x + m1y * m1y > limitSq) {
            // Overshoot guard: tangents wildly out of scale with the chord — lerp the segment and
            // report the chord velocity so motion stays consistent with the rendered path.
            return new Pose(s0.x() + chordX * s, s0.y() + chordY * s, chordX / h, chordY / h, false);
        }
        float s2 = s * s;
        float s3 = s2 * s;
        float h00 = 2f * s3 - 3f * s2 + 1f;
        float h10 = s3 - 2f * s2 + s;
        float h01 = -2f * s3 + 3f * s2;
        float h11 = s3 - s2;
        float x = h00 * s0.x() + h10 * m0x + h01 * s1.x() + h11 * m1x;
        float y = h00 * s0.y() + h10 * m0y + h01 * s1.y() + h11 * m1y;
        // Derivative of the basis over s, divided by h to get world units per second.
        float d00 = 6f * s2 - 6f * s;
        float d10 = 3f * s2 - 4f * s + 1f;
        float d01 = -6f * s2 + 6f * s;
        float d11 = 3f * s2 - 2f * s;
        float vx = (d00 * s0.x() + d10 * m0x + d01 * s1.x() + d11 * m1x) / h;
        float vy = (d00 * s0.y() + d10 * m0y + d01 * s1.y() + d11 * m1y) / h;
        return new Pose(x, y, vx, vy, false);
    }

    /** The starvation ladder: coast at the last velocity, decay to zero, park. */
    private static Pose extrapolate(Sample last, double overSeconds) {
        if (overSeconds <= EXTRAPOLATION_CAP_SECONDS) {
            float t = (float) overSeconds;
            return new Pose(last.x() + last.velocityX() * t, last.y() + last.velocityY() * t,
                    last.velocityX(), last.velocityY(), false);
        }
        float cap = (float) EXTRAPOLATION_CAP_SECONDS;
        double decayed = overSeconds - EXTRAPOLATION_CAP_SECONDS;
        if (decayed >= DECAY_WINDOW_SECONDS) {
            // Fully decayed: parked at the analytic end of the decay (cap coast + half a window).
            float travel = cap + (float) (DECAY_WINDOW_SECONDS / 2.0);
            return new Pose(last.x() + last.velocityX() * travel, last.y() + last.velocityY() * travel,
                    0f, 0f, true);
        }
        // Inside the decay window: velocity falls linearly to zero, position is its integral.
        float t = (float) decayed;
        float window = (float) DECAY_WINDOW_SECONDS;
        float frac = 1f - t / window;
        float travel = cap + t - (t * t) / (2f * window);
        return new Pose(last.x() + last.velocityX() * travel, last.y() + last.velocityY() * travel,
                last.velocityX() * frac, last.velocityY() * frac, false);
    }
}
