package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 29 M2: the tier state machine. Every case here is a wall-clock sequence with no sockets, so
 * the hysteresis is asserted on exact millisecond boundaries rather than sampled.
 */
class CoopCadenceControllerTest {

    private static final long START = 1_000L;
    /** Comfortably inside every clean threshold. */
    private static final int CLEAN_RTT = 40;

    private CoopCadenceController.Decision clean(CoopCadenceController controller, long now) {
        return controller.evaluate(now, CLEAN_RTT, 0, false, false);
    }

    @Test
    void aFreshControllerIsAtTheDefaultTier() {
        CoopCadenceController controller = new CoopCadenceController();

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier());
        assertEquals(CoopCadenceController.REASON_INITIAL, controller.reason());
    }

    @Test
    void lossPastTheThresholdDownshiftsOnTheEvaluationItAppearsOn() {
        CoopCadenceController controller = new CoopCadenceController();
        clean(controller, START);

        CoopCadenceController.Decision decision = controller.evaluate(START + 1_000L, CLEAN_RTT,
                12, false, false);

        assertEquals(CoopCadenceTier.FLOOR, decision.tier());
        assertTrue(decision.changed());
        assertEquals("loss 12%", decision.reason());
        assertEquals(200L, decision.tier().intervalMillis());
    }

    @Test
    void aMedianRttPastTheThresholdDownshifts() {
        CoopCadenceController controller = new CoopCadenceController();
        clean(controller, START);

        CoopCadenceController.Decision decision = controller.evaluate(START + 1_000L, 430, 0,
                false, false);

        assertEquals(CoopCadenceTier.FLOOR, decision.tier());
        assertEquals("rtt p50 430 ms", decision.reason());
    }

    @Test
    void anOutboundBacklogDownshifts() {
        CoopCadenceController controller = new CoopCadenceController();
        clean(controller, START);

        CoopCadenceController.Decision decision = controller.evaluate(START + 1_000L, CLEAN_RTT, 0,
                true, false);

        assertEquals(CoopCadenceTier.FLOOR, decision.tier());
        assertEquals(CoopCadenceController.REASON_BACKLOG, decision.reason());
    }

    @Test
    void theTcpFallbackPinsTheFloorAndOutranksTheOtherReasons() {
        CoopCadenceController controller = new CoopCadenceController();
        clean(controller, START);

        CoopCadenceController.Decision decision = controller.evaluate(START + 1_000L, 430, 40,
                true, true);

        assertEquals(CoopCadenceTier.FLOOR, decision.tier());
        assertEquals(CoopCadenceController.REASON_FALLBACK, decision.reason());
    }

    @Test
    void thereIsNoUpshiftBeforeThirtyContinuouslyCleanSeconds() {
        CoopCadenceController controller = new CoopCadenceController();
        controller.evaluate(START, CLEAN_RTT, 12, false, false);
        assertEquals(CoopCadenceTier.FLOOR, controller.tier());

        long cleanFrom = START + 1_000L;
        for (long t = cleanFrom; t < cleanFrom + CoopCadenceController.CLEAN_WINDOW_MILLIS;
                t += 1_000L) {
            assertFalse(clean(controller, t).changed(), "upshifted at " + (t - cleanFrom) + " ms");
            assertEquals(CoopCadenceTier.FLOOR, controller.tier());
        }

        CoopCadenceController.Decision decision =
                clean(controller, cleanFrom + CoopCadenceController.CLEAN_WINDOW_MILLIS);

        assertTrue(decision.changed());
        assertEquals(CoopCadenceTier.DEFAULT, decision.tier());
        assertEquals(CoopCadenceController.REASON_CLEAN, decision.reason());
    }

    @Test
    void oneDirtySecondInsideTheWindowRestartsIt() {
        CoopCadenceController controller = new CoopCadenceController();
        controller.evaluate(START, CLEAN_RTT, 12, false, false);
        for (long t = START + 1_000L; t <= START + 25_000L; t += 1_000L) {
            clean(controller, t);
        }

        // One bad second at t+26 s. The window has to start again from there, so the old deadline
        // (t+31 s) passes with the tier still on the floor.
        controller.evaluate(START + 26_000L, CLEAN_RTT, 40, false, false);
        for (long t = START + 27_000L; t <= START + 55_000L; t += 1_000L) {
            clean(controller, t);
        }
        assertEquals(CoopCadenceTier.FLOOR, controller.tier(),
                "the window must have restarted at the dirty second");

        // The window restarted on the first clean evaluation after the dirty one, at START + 27 s.
        clean(controller, START + 57_000L);

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier(),
                "thirty seconds after the dirty second, not after the first clean one");
    }

    @Test
    void leavingTheFallbackUnpinsIntoTheCleanWindowRatherThanStraightToDefault() {
        CoopCadenceController controller = new CoopCadenceController();
        for (long t = START; t <= START + 10_000L; t += 1_000L) {
            controller.evaluate(t, CLEAN_RTT, 0, false, true);
        }
        assertEquals(CoopCadenceTier.FLOOR, controller.tier());

        long unpinnedAt = START + 11_000L;
        clean(controller, unpinnedAt);
        assertEquals(CoopCadenceTier.FLOOR, controller.tier(),
                "the fallback clearing is the start of the window, not the end of it");

        clean(controller, unpinnedAt + CoopCadenceController.CLEAN_WINDOW_MILLIS - 1_000L);
        assertEquals(CoopCadenceTier.FLOOR, controller.tier());

        clean(controller, unpinnedAt + CoopCadenceController.CLEAN_WINDOW_MILLIS);
        assertEquals(CoopCadenceTier.DEFAULT, controller.tier());
    }

    @Test
    void theBandBetweenTheThresholdPairsNeitherDownshiftsNorAccumulatesTheWindow() {
        CoopCadenceController controller = new CoopCadenceController();
        controller.evaluate(START, CLEAN_RTT, 12, false, false);
        assertEquals(CoopCadenceTier.FLOOR, controller.tier());

        // 6% loss: past the 3% upshift threshold, short of the 10% downshift one.
        for (long t = START + 1_000L; t <= START + 90_000L; t += 1_000L) {
            controller.evaluate(t, CLEAN_RTT, 6, false, false);
        }

        assertEquals(CoopCadenceTier.FLOOR, controller.tier(),
                "a link that never becomes clean never climbs, however long it sits there");
    }

    @Test
    void aLinkAtTheDefaultTierInTheMiddleBandStaysAtTheDefaultTier() {
        CoopCadenceController controller = new CoopCadenceController();

        for (long t = START; t <= START + 60_000L; t += 1_000L) {
            controller.evaluate(t, 320, 6, false, false);
        }

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier(),
                "the middle band holds the tier where it is; only the downshift thresholds move it");
    }

    @Test
    void aRingWithAHighTailButALowMedianStaysAtTheDefaultTier() {
        // The Phase 20 WAN finding end to end: PING/PONG samples carry a frame interval per side, so
        // a frame-capped client produces a handful of very high samples. The p95 sees them; the p50,
        // which is what this controller consumes, does not.
        CoopLinkQuality quality = new CoopLinkQuality();
        quality.reset(START);
        long at = START;
        for (int i = 0; i < 30; i++) {
            at += 100L;
            quality.notePingSent(i, at);
            quality.notePongReceived(i, at + 60L);
        }
        for (int i = 30; i < 32; i++) {
            at += 100L;
            quality.notePingSent(i, at);
            quality.notePongReceived(i, at + 900L);
        }
        assertEquals(900, quality.p95RttMillis(), "the tail is genuinely there");
        assertEquals(60, quality.medianRttMillis());

        CoopCadenceController controller = new CoopCadenceController();
        for (long t = START; t <= START + 60_000L; t += 1_000L) {
            controller.evaluate(t, quality.medianRttMillis(), 0, false, false);
        }

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier(),
                "keying on the p95 here would have flapped the tier for both players");
    }

    @Test
    void anUnmeasuredRttNeitherDownshiftsNorBlocksTheClimb() {
        CoopCadenceController controller = new CoopCadenceController();
        controller.evaluate(START, null, 12, false, false);
        assertEquals(CoopCadenceTier.FLOOR, controller.tier());

        for (long t = START + 1_000L; t <= START + 32_000L; t += 1_000L) {
            controller.evaluate(t, null, 0, false, false);
        }

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier(),
                "the first seconds of every session have no PONG yet");
    }

    @Test
    void lossDrivenIsSetOnlyByALossFloor() {
        CoopCadenceController controller = new CoopCadenceController();

        controller.evaluate(START, CLEAN_RTT, 12, false, false);
        assertTrue(controller.lossDriven());

        controller.evaluate(START + 1_000L, CLEAN_RTT, 0, true, false);
        assertFalse(controller.lossDriven(), "a backlog floor must not deepen redundancy");

        controller.evaluate(START + 2_000L, 500, 0, false, false);
        assertFalse(controller.lossDriven());

        controller.evaluate(START + 3_000L, CLEAN_RTT, 0, false, true);
        assertFalse(controller.lossDriven(), "the TCP path is already reliable");

        controller.evaluate(START + 4_000L, CLEAN_RTT, 40, false, true);
        assertTrue(controller.lossDriven(),
                "loss measured under a fallback is still measured loss");
    }

    @Test
    void theTopTierStaysDarkHoweverCleanTheLinkIs() {
        assertFalse(CoopCadenceTier.TOP_TIER_ENABLED);
        CoopCadenceController controller = new CoopCadenceController();

        for (long t = START; t <= START + 600_000L; t += 1_000L) {
            clean(controller, t);
        }

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier());
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.highestEnabled());
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.DEFAULT.upshift());
    }

    @Test
    void anAnnouncedTierIsParsedAndAnUnknownOrDarkOneFallsBackToDefault() {
        assertEquals(CoopCadenceTier.FLOOR, CoopCadenceTier.fromHz(5));
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.fromHz(10));
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.fromHz(20),
                "a peer must not be able to talk this side into an uncertified rate");
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.fromHz(0));
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.fromHz(-7));
        assertEquals(CoopCadenceTier.DEFAULT, CoopCadenceTier.fromHz(999));
    }

    @Test
    void resetGoesBackToTheDefaultTierAndForgetsTheWindow() {
        CoopCadenceController controller = new CoopCadenceController();
        controller.evaluate(START, CLEAN_RTT, 12, false, false);
        for (long t = START + 1_000L; t <= START + 20_000L; t += 1_000L) {
            clean(controller, t);
        }

        controller.reset();

        assertEquals(CoopCadenceTier.DEFAULT, controller.tier());
        assertEquals(CoopCadenceController.REASON_INITIAL, controller.reason());
        assertFalse(controller.lossDriven());
        // The twenty seconds banked before the reset must not count towards a later climb.
        controller.evaluate(START + 21_000L, CLEAN_RTT, 12, false, false);
        for (long t = START + 22_000L; t <= START + 45_000L; t += 1_000L) {
            clean(controller, t);
        }
        assertEquals(CoopCadenceTier.FLOOR, controller.tier());
    }
}
