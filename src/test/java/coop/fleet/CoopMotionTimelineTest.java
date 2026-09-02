package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMotionTimelineTest {

    private static final double DELAY = CoopMotionTimeline.DELAY_SECONDS;
    private static final double DEAD_ZONE = CoopMotionTimeline.DEAD_ZONE_SECONDS;

    private final CoopMotionTimeline timeline = new CoopMotionTimeline();

    @Test
    void cursorIsNanUntilTheFirstSample() {
        assertTrue(Double.isNaN(timeline.advance(0.016)));
        assertTrue(Double.isNaN(timeline.cursor()));
    }

    @Test
    void bootstrapsOneDelayBehindTheFirstSample() {
        timeline.noteSample(10.0);
        assertEquals(10.0 - DELAY, timeline.advance(0.016), 1e-9);
    }

    @Test
    void insideTheDeadZoneTheTimescaleIsExactlyOne() {
        timeline.noteSample(10.0);
        double cursor = timeline.advance(0.016);
        // Samples keep arriving at exactly the cursor's own rate: drift stays 0, dt passes through.
        for (int i = 1; i <= 10; i++) {
            timeline.noteSample(10.0 + i * 0.1);
            double before = timeline.cursor();
            timeline.advance(0.1);
            assertEquals(0.1, timeline.cursor() - before, 1e-9,
                    "no ping-pong: zero drift must not scale time");
        }
        assertEquals(cursor + 1.0, timeline.cursor(), 1e-9);
    }

    @Test
    void sustainedPositiveDriftCatchesUpAtTwoPercent() {
        timeline.noteSample(10.0);
        timeline.advance(0.016);
        // The target leaps 0.5 s ahead (burst of buffered samples) — below the re-seat limit.
        timeline.noteSample(10.5);
        // Warm the drift EMA past the dead zone, then confirm the catch-up rate.
        for (int i = 0; i < 30; i++) {
            timeline.noteSample(10.5 + (i + 1) * 0.1);
            timeline.advance(0.1);
        }
        double before = timeline.cursor();
        timeline.noteSample(14.0);
        timeline.advance(0.1);
        assertEquals(0.102, timeline.cursor() - before, 1e-6, "catch-up runs 2% fast, never jumps");
    }

    @Test
    void sustainedDriftShrinksOverTime() {
        timeline.noteSample(10.0);
        timeline.advance(0.016);
        timeline.noteSample(10.4);
        double initialDrift = (10.4 - DELAY) - timeline.cursor();
        double latest = 10.4;
        for (int i = 0; i < 200; i++) {
            latest += 0.1;
            timeline.noteSample(latest);
            timeline.advance(0.1);
        }
        double finalDrift = (latest - DELAY) - timeline.cursor();
        assertTrue(finalDrift < initialDrift - 0.15,
                "2% catch-up must have absorbed most of the burst: " + finalDrift);
        assertTrue(finalDrift > -DEAD_ZONE, "and never overshot into the past");
    }

    @Test
    void cursorAheadOfTargetSlowsDownAtFourPercent() {
        timeline.noteSample(10.0);
        timeline.advance(0.016);
        // Starved stream, frozen target: seven 0.1 s frames push the cursor ~0.7 s ahead — enough
        // for the drift EMA to cross the dead zone, still short of the re-seat limit.
        for (int i = 0; i < 7; i++) {
            timeline.advance(0.1);
        }
        double before = timeline.cursor();
        timeline.advance(0.1);
        assertEquals(0.096, timeline.cursor() - before, 1e-6, "slow-down runs 4% slow, never rewinds");
    }

    @Test
    void driftBeyondTheReseatLimitSnapsTheCursorToTheTarget() {
        timeline.noteSample(10.0);
        timeline.advance(0.016);
        // A 30 s outage ends: absorbing it at 2% would take minutes, so the cursor re-seats.
        timeline.noteSample(40.0);
        assertEquals(40.0 - DELAY, timeline.advance(0.016), 1e-9);
    }

    @Test
    void pausedFramesFreezeTheCursor() {
        timeline.noteSample(10.0);
        double cursor = timeline.advance(0.016);
        timeline.noteSample(10.1);
        assertEquals(cursor, timeline.advance(0.0), 1e-9, "dt 0 must not move the cursor");
        assertEquals(cursor, timeline.advance(0.0), 1e-9);
    }

    @Test
    void olderStampsNeverLowerTheLatest() {
        timeline.noteSample(10.0);
        timeline.advance(0.016);
        timeline.noteSample(9.0); // redundant section, already superseded
        assertEquals(10.0 - DELAY, timeline.cursor(), 1e-9);
    }

    @Test
    void resetForgetsEverything() {
        timeline.noteSample(10.0);
        timeline.advance(0.016);
        timeline.reset();
        assertTrue(Double.isNaN(timeline.advance(0.016)));
    }

    // ---- Phase 29 M2: the adaptive delay ---------------------------------------------------------

    @Test
    void theDelayStartsAtTheDefaultAndIsClampedAtBothEnds() {
        assertEquals(DELAY, timeline.delaySeconds(), 1e-9);

        timeline.setDelaySeconds(0.05);
        assertEquals(CoopMotionTimeline.MIN_DELAY_SECONDS, timeline.delaySeconds(), 1e-9);

        timeline.setDelaySeconds(2.0);
        assertEquals(CoopMotionTimeline.MAX_DELAY_SECONDS, timeline.delaySeconds(), 1e-9);

        timeline.setDelaySeconds(0.35);
        assertEquals(0.35, timeline.delaySeconds(), 1e-9);
    }

    @Test
    void resetPutsTheDelayBackToTheDefault() {
        timeline.setDelaySeconds(CoopMotionTimeline.MAX_DELAY_SECONDS);
        timeline.reset();
        assertEquals(DELAY, timeline.delaySeconds(), 1e-9);
    }

    /**
     * The tier change a floor downshift produces: 200 ms to 400 ms in one step. The cursor has to
     * absorb it through the time-scaling path and never through the re-seat, because a re-seat is the
     * visible teleport this whole mechanism exists to avoid.
     */
    /**
     * Settles the cursor with zero steady-state drift: the sample for frame N+1 is noted after frame
     * N has advanced, which is the phase a real stream and a real frame loop converge on. Returns the
     * newest sample stamp.
     */
    private double settle(double startStamp, int frames) {
        timeline.noteSample(startStamp);
        timeline.advance(0.1);
        double latest = startStamp;
        for (int i = 0; i < frames; i++) {
            timeline.advance(0.1);
            latest += 0.1;
            timeline.noteSample(latest);
        }
        return latest;
    }

    /**
     * The step a floor downshift plus jitter produces: 200 ms to 500 ms. The cursor has to absorb it
     * through the time-scaling path and never through the re-seat, because a re-seat is the visible
     * teleport this whole mechanism exists to avoid.
     */
    @Test
    void aWidenedDelayIsAbsorbedByTimeScalingAndNeverByTheReseat() {
        double latest = settle(10.0, 20);

        timeline.setDelaySeconds(0.500);

        boolean sawSlowdown = false;
        for (int i = 0; i < 100; i++) {
            double before = timeline.cursor();
            timeline.advance(0.1);
            double moved = timeline.cursor() - before;
            assertTrue(moved >= 0.1 * CoopMotionTimeline.SLOWDOWN_TIMESCALE - 1e-9
                            && moved <= 0.1 * CoopMotionTimeline.CATCHUP_TIMESCALE + 1e-9,
                    "the cursor jumped by " + moved + " - that is a re-seat, not time-scaling");
            if (Math.abs(moved - 0.1 * CoopMotionTimeline.SLOWDOWN_TIMESCALE) < 1e-9) {
                sawSlowdown = true;
            }
            latest += 0.1;
            timeline.noteSample(latest);
        }

        assertTrue(sawSlowdown, "a widened delay must be absorbed by the 0.96 path");
        double residual = (latest - 0.500) - timeline.cursor();
        assertTrue(Math.abs(residual) <= DEAD_ZONE + 1e-6,
                "the correction settles inside the dead zone; residual was " + residual);
    }

    @Test
    void aNarrowedDelayIsAbsorbedByTheCatchUpPath() {
        timeline.setDelaySeconds(0.500);
        double latest = settle(10.0, 20);

        timeline.setDelaySeconds(0.200);

        boolean sawCatchUp = false;
        for (int i = 0; i < 100; i++) {
            double before = timeline.cursor();
            timeline.advance(0.1);
            double moved = timeline.cursor() - before;
            assertTrue(moved >= 0.1 * CoopMotionTimeline.SLOWDOWN_TIMESCALE - 1e-9
                            && moved <= 0.1 * CoopMotionTimeline.CATCHUP_TIMESCALE + 1e-9,
                    "the cursor jumped by " + moved + " - that is a re-seat, not time-scaling");
            if (Math.abs(moved - 0.1 * CoopMotionTimeline.CATCHUP_TIMESCALE) < 1e-9) {
                sawCatchUp = true;
            }
            latest += 0.1;
            timeline.noteSample(latest);
        }

        assertTrue(sawCatchUp, "a narrowed delay must be absorbed by the 1.02 path");
    }

    /**
     * A one-interval delay step at the default tier is exactly one dead zone wide, so the correct
     * outcome is that nothing visible happens at all: no re-seat, no rate change, the step lands
     * inside the guard band. Asserted because "no correction" is the desirable behaviour here and
     * would otherwise read as the feature not working.
     */
    @Test
    void aOneHundredMillisecondDelayStepProducesNoCorrectionAtAll() {
        double latest = settle(10.0, 20);

        timeline.setDelaySeconds(DELAY + 0.100);

        for (int i = 0; i < 60; i++) {
            double before = timeline.cursor();
            timeline.advance(0.1);
            assertEquals(0.1, timeline.cursor() - before, 1e-9,
                    "one dead zone of delay change must not move the cursor off real time");
            latest += 0.1;
            timeline.noteSample(latest);
        }
    }
}
