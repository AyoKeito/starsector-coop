package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopStreamClockTest {

    @Test
    void accumulatesUnpausedFrameTimeOnly() {
        CoopStreamClock clock = new CoopStreamClock();
        clock.advance(0.1f, false);
        clock.advance(0.5f, true);   // paused frames contribute nothing
        clock.advance(0.1f, false);
        assertEquals(200L, clock.gameTimeMillis());
    }

    @Test
    void ignoresNonPositiveFrameTime() {
        CoopStreamClock clock = new CoopStreamClock();
        clock.advance(-0.1f, false);
        clock.advance(0f, false);
        assertEquals(0L, clock.gameTimeMillis());
    }

    @Test
    void epochsAreMonotonicFromOne() {
        CoopStreamClock clock = new CoopStreamClock();
        assertEquals(1L, clock.nextEpoch());
        assertEquals(2L, clock.nextEpoch());
        assertTrue(clock.nextEpoch() > 2L);
    }

    @Test
    void manySmallFramesDoNotTruncate() {
        // The engine clock's per-frame int truncation is the Phase 7c drift source; the stream clock
        // must not reproduce it. 600 frames of 1/60 s is exactly 10 s.
        CoopStreamClock clock = new CoopStreamClock();
        for (int i = 0; i < 600; i++) {
            clock.advance(1f / 60f, false);
        }
        assertEquals(10000L, clock.gameTimeMillis(), "600 frames of 1/60s must sum to 10s");
    }
}
