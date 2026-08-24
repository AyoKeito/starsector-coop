package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMotionInterpolatorTest {

    private static final double CAP = CoopMotionInterpolator.EXTRAPOLATION_CAP_SECONDS;
    private static final double DECAY = CoopMotionInterpolator.DECAY_WINDOW_SECONDS;

    private final CoopMotionInterpolator interpolator = new CoopMotionInterpolator();

    @Test
    void emptyBufferEvaluatesToNull() {
        assertNull(interpolator.evaluate(1.0));
    }

    @Test
    void constantVelocityHermiteIsExactlyLinear() {
        // p(t) = (100 + 50t, -20t): tangents consistent with the chord collapse the cubic to the line.
        interpolator.addSample(1.0, 100f, 0f, 50f, -20f);
        interpolator.addSample(1.1, 105f, -2f, 50f, -20f);

        CoopMotionInterpolator.Pose pose = interpolator.evaluate(1.05);

        assertEquals(102.5f, pose.x(), 1e-3f);
        assertEquals(-1f, pose.y(), 1e-3f);
        assertEquals(50f, pose.velocityX(), 1e-2f);
        assertEquals(-20f, pose.velocityY(), 1e-2f);
        assertFalse(pose.parked());
    }

    @Test
    void hermiteHitsBothEndpointsExactly() {
        // A curved segment (velocities turn): endpoints must still be exact.
        interpolator.addSample(0.0, 0f, 0f, 10f, 0f);
        interpolator.addSample(0.1, 1f, 0.05f, 10f, 1f);

        CoopMotionInterpolator.Pose start = interpolator.evaluate(0.0);
        assertEquals(0f, start.x(), 1e-4f);
        assertEquals(0f, start.y(), 1e-4f);

        CoopMotionInterpolator.Pose end = interpolator.evaluate(0.1 - 1e-9);
        assertEquals(1f, end.x(), 1e-3f);
        assertEquals(0.05f, end.y(), 1e-3f);
    }

    @Test
    void wildTangentsFallBackToLerp() {
        // Velocity 100x out of scale with a 1 su chord: the Hermite would loop; the guard lerps and
        // reports the chord velocity so motion matches the rendered path.
        interpolator.addSample(0.0, 0f, 0f, 1000f, 0f);
        interpolator.addSample(0.1, 1f, 0f, 1000f, 0f);

        CoopMotionInterpolator.Pose pose = interpolator.evaluate(0.05);

        assertEquals(0.5f, pose.x(), 1e-4f);
        assertEquals(0f, pose.y(), 1e-4f);
        assertEquals(10f, pose.velocityX(), 1e-3f, "chord velocity, not the wild sample velocity");
    }

    @Test
    void cursorBehindTheBufferHoldsAtTheFirstSample() {
        interpolator.addSample(5.0, 10f, 20f, 1f, 2f);
        CoopMotionInterpolator.Pose pose = interpolator.evaluate(4.0);
        assertEquals(10f, pose.x());
        assertEquals(20f, pose.y());
        assertFalse(pose.parked());
    }

    @Test
    void nonIncreasingSampleTimesAreDropped() {
        assertFalse(interpolator.addSample(1.0, 0f, 0f, 10f, 0f));
        assertFalse(interpolator.addSample(1.0, 999f, 999f, 10f, 0f), "duplicate stamp dropped");
        assertFalse(interpolator.addSample(0.9, 999f, 999f, 10f, 0f), "older stamp dropped");
        // Still holding the original sample only.
        assertEquals(0f, interpolator.evaluate(1.0).x());
    }

    @Test
    void teleportScaleJumpRestartsTheBufferAndReportsTheCut() {
        interpolator.addSample(1.0, 0f, 0f, 10f, 0f);
        boolean teleport = interpolator.addSample(
                1.1, CoopMotionInterpolator.TELEPORT_DISTANCE + 100f, 0f, 10f, 0f);

        assertTrue(teleport, "the caller must hard-cut, never glide");
        // The buffer restarted at the jump target: a cursor before it holds there, not at the origin.
        CoopMotionInterpolator.Pose pose = interpolator.evaluate(1.05);
        assertEquals(CoopMotionInterpolator.TELEPORT_DISTANCE + 100f, pose.x());
    }

    @Test
    void starvationCoastsAtTheLastVelocityUpToTheCap() {
        interpolator.addSample(1.0, 0f, 0f, 10f, -4f);
        CoopMotionInterpolator.Pose pose = interpolator.evaluate(1.0 + CAP / 2);
        assertEquals(10f * (float) (CAP / 2), pose.x(), 1e-4f);
        assertEquals(-4f * (float) (CAP / 2), pose.y(), 1e-4f);
        assertEquals(10f, pose.velocityX());
        assertFalse(pose.parked());
    }

    @Test
    void starvationDecaysVelocityAfterTheCap() {
        interpolator.addSample(1.0, 0f, 0f, 10f, 0f);
        // Halfway through the decay window: velocity at half strength, position = integral.
        CoopMotionInterpolator.Pose pose = interpolator.evaluate(1.0 + CAP + DECAY / 2);
        assertEquals(5f, pose.velocityX(), 1e-3f);
        float travel = (float) (CAP + (DECAY / 2) - (DECAY / 2) * (DECAY / 2) / (2 * DECAY));
        assertEquals(10f * travel, pose.x(), 1e-3f);
        assertFalse(pose.parked());
    }

    @Test
    void starvationParksAfterTheDecayWindowAndNeverDivergesFurther() {
        interpolator.addSample(1.0, 0f, 0f, 10f, 0f);
        float parkedX = 10f * (float) (CAP + DECAY / 2);

        // An epsilon past the boundary: the exact sum 1.0 + CAP + DECAY lands a float ulp short.
        CoopMotionInterpolator.Pose atEnd = interpolator.evaluate(1.0 + CAP + DECAY + 1e-9);
        assertEquals(parkedX, atEnd.x(), 1e-3f);
        assertEquals(0f, atEnd.velocityX());
        assertTrue(atEnd.parked());

        // Ten seconds starved: exactly the same spot — a starved mirror never sails off.
        CoopMotionInterpolator.Pose muchLater = interpolator.evaluate(11.0);
        assertEquals(parkedX, muchLater.x(), 1e-3f);
        assertTrue(muchLater.parked());
    }

    @Test
    void velocityIsContinuousFromInterpolationIntoExtrapolation() {
        interpolator.addSample(1.0, 0f, 0f, 10f, 0f);
        interpolator.addSample(1.1, 1f, 0f, 12f, 0f);

        CoopMotionInterpolator.Pose justBefore = interpolator.evaluate(1.1 - 1e-6);
        CoopMotionInterpolator.Pose justAfter = interpolator.evaluate(1.1 + 1e-6);

        assertEquals(justBefore.velocityX(), justAfter.velocityX(), 0.1f,
                "no velocity kink at the newest sample");
        assertEquals(justBefore.x(), justAfter.x(), 0.01f);
    }

    @Test
    void bufferIsBoundedAndKeepsTheNewestSamples() {
        for (int i = 0; i < CoopMotionInterpolator.BUFFER_LIMIT * 2; i++) {
            interpolator.addSample(i * 0.1, i, 0f, 10f, 0f);
        }
        // A cursor far in the past can only reach back to the oldest retained sample.
        CoopMotionInterpolator.Pose pose = interpolator.evaluate(0.0);
        assertEquals(CoopMotionInterpolator.BUFFER_LIMIT, (int) pose.x(),
                "oldest retained sample is limit entries behind the newest");
    }

    @Test
    void consumedSamplesArePrunedAsTheCursorPasses() {
        for (int i = 0; i < 10; i++) {
            interpolator.addSample(i * 0.1, i, 0f, 10f, 0f);
        }
        // Cursor between samples 7 and 8; samples 0-6 are consumed. Evaluation stays exact.
        CoopMotionInterpolator.Pose pose = interpolator.evaluate(0.75);
        assertEquals(7.5f, pose.x(), 1e-3f);
        // And a later cursor still works (the bracketing pair survived the prune).
        assertEquals(8.5f, interpolator.evaluate(0.85).x(), 1e-3f);
    }

    @Test
    void clearEmptiesTheBuffer() {
        interpolator.addSample(1.0, 0f, 0f, 10f, 0f);
        interpolator.clear();
        assertTrue(interpolator.isEmpty());
        assertNull(interpolator.evaluate(1.0));
    }
}
