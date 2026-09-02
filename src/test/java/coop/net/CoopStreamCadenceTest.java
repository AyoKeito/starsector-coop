package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopStreamCadenceTest {

    @Test
    void firstPollPrimesWithoutSending() {
        CoopStreamCadence cadence = new CoopStreamCadence(100L);

        assertFalse(cadence.shouldSend(0L, 5000L, false));
        assertFalse(cadence.shouldSend(50L, 5050L, false));
        assertTrue(cadence.shouldSend(100L, 5100L, false));
    }

    @Test
    void sendsOnceEveryIntervalOfStreamTime() {
        CoopStreamCadence cadence = new CoopStreamCadence(100L);
        cadence.shouldSend(0L, 0L, false);

        int sends = 0;
        // 1 s of stream time in 16 ms steps, wall time irrelevant here.
        for (long stream = 16L; stream <= 1000L; stream += 16L) {
            if (cadence.shouldSend(stream, 999_999L, false)) {
                sends++;
            }
        }

        assertEquals(8, sends, "one send per completed 100 ms of stream time, snapped to frame steps");
    }

    @Test
    void fastForwardDoublesTheWallSendRateBecauseStreamTimeDoubles() {
        // The point of the rule: at 2x the campaign covers 200 ms of stream time per 100 ms of wall
        // time, so the 0.2 s (game time) interpolation buffer keeps the same sample depth.
        CoopStreamCadence cadence = new CoopStreamCadence(100L);
        cadence.shouldSend(0L, 0L, false);

        int sends = 0;
        for (long wall = 16L; wall <= 1000L; wall += 16L) {
            if (cadence.shouldSend(wall * 2L, wall, false)) {
                sends++;
            }
        }

        assertEquals(15, sends, "2x game time over 1 s of wall time sends ~2x as often as 1x (8)");
    }

    @Test
    void aFrozenStreamKeepsSendingOnTheWallClock() {
        // Paused campaign: stream time stands still, but the peer must keep emitting frozen-stamp
        // samples exactly as it did before the cadence moved to game time.
        CoopStreamCadence cadence = new CoopStreamCadence(100L);
        cadence.shouldSend(4200L, 0L, true);

        assertFalse(cadence.shouldSend(4200L, 99L, true));
        assertTrue(cadence.shouldSend(4200L, 100L, true));
        assertFalse(cadence.shouldSend(4200L, 199L, true));
        assertTrue(cadence.shouldSend(4200L, 250L, true));
    }

    @Test
    void anUnfrozenStreamNeverFallsBackToTheWallClock() {
        CoopStreamCadence cadence = new CoopStreamCadence(100L);
        cadence.shouldSend(0L, 0L, false);

        // A stalled-but-not-frozen frame (dt tiny) must not be rescued by wall time.
        assertFalse(cadence.shouldSend(1L, 100_000L, false));
        assertTrue(cadence.shouldSend(100L, 100_001L, false));
    }

    @Test
    void resetRePrimes() {
        CoopStreamCadence cadence = new CoopStreamCadence(100L);
        cadence.shouldSend(0L, 0L, false);
        assertTrue(cadence.shouldSend(100L, 100L, false));

        cadence.reset();

        assertFalse(cadence.shouldSend(5000L, 5000L, false));
        assertTrue(cadence.shouldSend(5100L, 5100L, false));
    }

    @Test
    void rejectsANonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new CoopStreamCadence(0L));
    }

    @Test
    void retuningTheIntervalTakesEffectWithoutStallingOrDoubleFiringTheStream() {
        CoopStreamCadence cadence = new CoopStreamCadence(100L);
        cadence.shouldSend(0L, 0L, false);
        assertTrue(cadence.shouldSend(100L, 100L, false));

        cadence.setIntervalMillis(200L);
        assertEquals(200L, cadence.intervalMillis());
        assertFalse(cadence.shouldSend(250L, 250L, false), "the new interval is measured from the last send");
        assertTrue(cadence.shouldSend(300L, 300L, false));

        cadence.setIntervalMillis(100L);
        assertTrue(cadence.shouldSend(400L, 400L, false));
    }

    @Test
    void aNonPositiveRetuneIsRejected() {
        CoopStreamCadence cadence = new CoopStreamCadence(100L);

        assertThrows(IllegalArgumentException.class, () -> cadence.setIntervalMillis(0L));
    }
}
