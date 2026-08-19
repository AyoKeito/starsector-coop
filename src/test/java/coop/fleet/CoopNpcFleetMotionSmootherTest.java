package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNpcFleetMotionSmootherTest {

    private static final String FLEET = "fleet-1";
    private static final String ASKONIA = "askonia";
    private static final float TOL = 0.001f;

    // ---- Segment measurement ---------------------------------------------------------------------

    @Test
    void segmentIsClampedToTheEngineStride() {
        assertEquals(CoopNpcFleetMotionSmoother.MIN_SEGMENT_MILLIS,
                CoopNpcFleetMotionSmoother.clampSegment(0L));
        assertEquals(1000L, CoopNpcFleetMotionSmoother.clampSegment(1000L));
        // A fleet parked for a minute must not be given a minute-long glide when it finally moves.
        assertEquals(CoopNpcFleetMotionSmoother.MAX_SEGMENT_MILLIS,
                CoopNpcFleetMotionSmoother.clampSegment(60_000L));
    }

    @Test
    void progressSaturatesAtBothEnds() {
        assertEquals(0f, CoopNpcFleetMotionSmoother.progress(0L, 1000L), TOL);
        assertEquals(0.25f, CoopNpcFleetMotionSmoother.progress(250L, 1000L), TOL);
        assertEquals(1f, CoopNpcFleetMotionSmoother.progress(1000L, 1000L), TOL);
        assertEquals(1f, CoopNpcFleetMotionSmoother.progress(9999L, 1000L), TOL);
        assertEquals(1f, CoopNpcFleetMotionSmoother.progress(10L, 0L), TOL);
    }

    // ---- The staircase this exists to remove -----------------------------------------------------

    @Test
    void firstSampleIsReportedVerbatim() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();

        CoopNpcFleetMotionSmoother.Motion m = smoother.smooth(FLEET, ASKONIA, 100f, 200f, 5f, 6f, 0L);

        assertEquals(100f, m.x(), TOL);
        assertEquals(200f, m.y(), TOL);
        assertEquals(5f, m.velocityX(), TOL);
        assertEquals(6f, m.velocityY(), TOL);
    }

    @Test
    void aOneSecondEngineJumpIsSpreadAcrossTheFollowingSecond() {
        // The host advances a non-current system once per 60 frames with a 60x timestep, so a fleet's
        // position only changes once a second while we sample at 10 Hz. Emitted positions must walk.
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);              // first observation
        for (long t = 100L; t < 1000L; t += 100L) {                       // engine has not moved it
            smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, t);
        }
        // Engine tick: 300 units in one step.
        assertEquals(0f, smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1000L).x(), TOL);

        assertEquals(75f, smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1250L).x(), TOL);
        assertEquals(150f, smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1500L).x(), TOL);
        assertEquals(300f, smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 2000L).x(), TOL);
    }

    @Test
    void consecutiveSegmentsAreContinuous() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);
        smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1000L);
        float endOfFirst = smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1999L).x();

        // Next engine tick lands: the new segment must start where the old one ended, not jump.
        float startOfSecond = smoother.smooth(FLEET, ASKONIA, 600f, 0f, 300f, 0f, 2000L).x();

        assertEquals(300f, startOfSecond, TOL);
        assertTrue(Math.abs(startOfSecond - endOfFirst) < 1f,
                "segment boundary jumped by " + Math.abs(startOfSecond - endOfFirst));
    }

    @Test
    void emittedVelocityMatchesTheEmittedPositions() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);
        // 300 units of travel measured over a 1 s segment => 300 u/s, regardless of the raw value.
        CoopNpcFleetMotionSmoother.Motion m =
                smoother.smooth(FLEET, ASKONIA, 300f, 150f, 9999f, 9999f, 1000L);

        assertEquals(300f, m.velocityX(), TOL);
        assertEquals(150f, m.velocityY(), TOL);
    }

    @Test
    void aStationaryFleetKeepsItsEngineVelocityAndTruePosition() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 50f, 60f, 0f, 0f, 0L);

        CoopNpcFleetMotionSmoother.Motion m = smoother.smooth(FLEET, ASKONIA, 50f, 60f, 1f, 2f, 500L);

        assertEquals(50f, m.x(), TOL);
        assertEquals(60f, m.y(), TOL);
        assertEquals(1f, m.velocityX(), TOL);
        assertEquals(2f, m.velocityY(), TOL);
    }

    @Test
    void outputConvergesOnTruthWhenTheEngineStopsReporting() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);
        smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1000L);

        assertEquals(300f, smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 9000L).x(), TOL);
    }

    // ---- Snapping instead of interpolating -------------------------------------------------------

    @Test
    void aSystemChangeSnapsRatherThanInterpolatingAcrossIt() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);
        smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1000L);

        CoopNpcFleetMotionSmoother.Motion m =
                smoother.smooth(FLEET, "hyperspace", -8000f, 4000f, 12f, 13f, 1100L);

        assertEquals(-8000f, m.x(), TOL);
        assertEquals(4000f, m.y(), TOL);
        assertEquals(12f, m.velocityX(), TOL);
    }

    @Test
    void anUnidentifiedFleetIsPassedThroughUntouched() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();

        CoopNpcFleetMotionSmoother.Motion m = smoother.smooth("", ASKONIA, 7f, 8f, 9f, 10f, 0L);

        assertEquals(7f, m.x(), TOL);
        assertEquals(0, smoother.trackedCount());
        assertEquals(7f, smoother.smooth(null, ASKONIA, 7f, 8f, 9f, 10f, 0L).x(), TOL);
    }

    // ---- Bookkeeping -------------------------------------------------------------------------------

    @Test
    void tracksAreDroppedOnceAFleetStopsBeingSampled() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth("gone", ASKONIA, 0f, 0f, 0f, 0f, 0L);
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);
        assertEquals(2, smoother.trackedCount());

        long later = CoopNpcFleetMotionSmoother.STALE_TRACK_MILLIS + 10_000L;
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, later);

        assertEquals(1, smoother.trackedCount());
    }

    @Test
    void resetForgetsEveryTrackSoTheNextSampleSnaps() {
        CoopNpcFleetMotionSmoother smoother = new CoopNpcFleetMotionSmoother();
        smoother.smooth(FLEET, ASKONIA, 0f, 0f, 0f, 0f, 0L);
        smoother.smooth(FLEET, ASKONIA, 300f, 0f, 300f, 0f, 1000L);

        smoother.reset();

        assertEquals(0, smoother.trackedCount());
        assertEquals(600f, smoother.smooth(FLEET, ASKONIA, 600f, 0f, 300f, 0f, 1100L).x(), TOL);
    }
}
