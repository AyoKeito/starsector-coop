package coop.storm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoopStormSnapshotTest {
    @Test void negativeCampaignEpochsAndNativeOvershootRemainValid() {
        CoopStormSnapshot snapshot = snapshot(List.of(new CoopStormSnapshot.Tracker(0, 0, 1, 4,
                -0.25f, -1f, -0.001f, 0.1f, 0.2f, 5f)));
        assertEquals(-10_000L, snapshot.commitTimestamp());
        assertEquals(-0.25f, snapshot.trackers().get(0).waitDays());
    }

    @Test void immutableCanonicalTrackerListIgnoresEngineMapIterationOrder() {
        var first = tracker(0, 0); var last = tracker(1, 1);
        List<CoopStormSnapshot.Tracker> source = new ArrayList<>(List.of(last, first));
        var snapshot = snapshot(source); source.clear();
        assertEquals(List.of(first, last), snapshot.trackers());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.trackers().clear());
        assertEquals(snapshot, snapshot(List.of(first, last)));
    }

    @Test void duplicatesOutOfGridAndInvalidNativeStateCannotAddressArbitraryCells() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(tracker(0, 0), tracker(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(tracker(2, 0))));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot.Tracker(-1, 0, 1, 1, 1, 1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot.Tracker(0, 0, 1, 5, 1, 1, 0, 1, 1, 1));
    }

    @Test void timelineOverflowFutureCommitAndNonfiniteDurationsFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot(1, 1, Long.MIN_VALUE, Long.MAX_VALUE,
                2f, 2, 2, "x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot(1, 1, 1, 0, 2f, 2, 2, "x", List.of()));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f})
            assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot(1, 1, 0, 0, invalid, 2, 2, "x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot.Tracker(0, 0, 1, 1,
                Float.NaN, 1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoopStormSnapshot.Tracker(0, 0, 1, 1,
                1, 1, 0, 1, 1, Float.POSITIVE_INFINITY));
    }

    private static CoopStormSnapshot.Tracker tracker(int i, int j) {
        return new CoopStormSnapshot.Tracker(i, j, 1, 1, 1, 0.2f, 0, 0.2f, 0, 1);
    }
    private static CoopStormSnapshot snapshot(List<CoopStormSnapshot.Tracker> trackers) {
        return new CoopStormSnapshot(2, 1, -10_000, -9_000, 2f, 2, 2,
                CoopStormGrid.encode(new byte[2][2]), trackers);
    }
}
