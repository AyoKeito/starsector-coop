package coop.storm;

import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable parent-loader checkpoint; never stores an engine object or callback. */
public record CoopStormSnapshot(long revision, long generation, long commitTimestamp,
        long snapshotTimestamp, float intervalDurationDays, int width, int height,
        String encodedCells, List<Tracker> trackers) {
    public static final int MAX_TRACKERS = 262_144;
    public static final float MAX_TIMER_DAYS = 1_000_000f;

    public CoopStormSnapshot {
        if (revision < 0 || generation < 0) throw new IllegalArgumentException("Negative storm revision/generation");
        try {
            if (Math.subtractExact(snapshotTimestamp, commitTimestamp) < 0)
                throw new IllegalArgumentException("Storm grid commit is after snapshot");
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Storm snapshot timeline overflow", failure);
        }
        if (!Float.isFinite(intervalDurationDays) || intervalDurationDays <= 0f || intervalDurationDays > MAX_TIMER_DAYS)
            throw new IllegalArgumentException("Invalid storm interval duration");
        int cells = CoopStormGrid.cellCount(width, height);
        if (encodedCells == null || encodedCells.isEmpty() || encodedCells.length() > CoopStormGrid.MAX_ENCODED_CHARS)
            throw new IllegalArgumentException("Invalid encoded storm grid size");
        Objects.requireNonNull(trackers, "storm trackers");
        if (trackers.size() > MAX_TRACKERS || trackers.size() > cells)
            throw new IllegalArgumentException("Too many storm trackers");
        trackers = trackers.stream().sorted(Comparator.comparingInt(Tracker::j).thenComparingInt(Tracker::i)).toList();
        Set<Long> seen = new HashSet<>();
        for (Tracker tracker : trackers) {
            if (tracker.i >= width || tracker.j >= height)
                throw new IllegalArgumentException("Storm tracker outside grid");
            if (!seen.add(((long) tracker.i << 32) | tracker.j))
                throw new IllegalArgumentException("Duplicate storm tracker cell");
        }
    }

    /** State codes match native OFF, WAIT, SIGNAL, STORM, STORM_WANE (0..4). */
    public record Tracker(int i, int j, long epoch, int state, float waitDays, float signal,
            float wane, float maxSignal, float maxWane, float timeScale) {
        public Tracker {
            if (i < 0 || j < 0 || i >= CoopStormGrid.MAX_DIMENSION || j >= CoopStormGrid.MAX_DIMENSION
                    || epoch < 0 || state < 0 || state > 4)
                throw new IllegalArgumentException("Invalid storm tracker identity/state");
            for (float timer : new float[]{waitDays, signal, wane, maxSignal, maxWane})
                if (!Float.isFinite(timer) || Math.abs(timer) > MAX_TIMER_DAYS)
                    throw new IllegalArgumentException("Invalid storm tracker native timer");
            if (!Float.isFinite(timeScale) || timeScale < 0f || timeScale > 100f)
                throw new IllegalArgumentException("Invalid storm tracker time scale");
        }
    }
}
