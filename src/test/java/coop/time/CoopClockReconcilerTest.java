package coop.time;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine is never on the test classpath, so every case here runs against the injected
 * {@code ClockPort} / wall-clock seams. The load-bearing test is
 * {@link #clockNeverMovesBackwardForAnyDrift()}: a backward write across a month boundary makes the
 * engine pay monthly income twice ({@code ReachEconomyStepper} fires month-end on
 * {@code getMonth() != prevMonth}, an inequality), which is why the monotonic rule is absolute.
 */
class CoopClockReconcilerTest {

    private static final long DAY = CoopClockReconciler.MILLIS_PER_GAME_DAY;
    /** An arbitrary but realistic starting campaign timestamp (cycle 206-ish, in calendar ms). */
    private static final long BASE = 7_452_000_000_000L;
    /** One 60 fps frame in real seconds; the clock advances 144,000 calendar-ms in it at 1x. */
    private static final float FRAME_SECONDS = 1f / 60f;
    private static final long FRAME_MILLIS = (long) (FRAME_SECONDS * CoopClockReconciler.MILLIS_PER_REAL_SECOND);

    // ---- dead zone + hysteresis -----------------------------------------------------------------

    @Test
    void driftInsideTheEntryDeadZoneNeverWritesTheClock() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, days(0.04));
        reconciler.tick(FRAME_SECONDS, false);

        assertFalse(reconciler.isCorrecting());
        assertTrue(clock.writes.isEmpty());
        assertEquals(BASE, clock.timestamp);
    }

    @Test
    void anEpisodeEnteredAboveTheEntryZoneKeepsCorrectingUntilBelowTheExitZone() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        // Enter: 0.07 day is past the 0.05 entry threshold.
        fillRing(reconciler, clock, days(0.07));
        reconciler.tick(FRAME_SECONDS, false);
        assertTrue(reconciler.isCorrecting());
        int writesAfterEntry = clock.writes.size();
        assertEquals(1, writesAfterEntry);

        // 0.03 day is BELOW the entry threshold but above the exit one: the episode continues.
        fillRing(reconciler, clock, days(0.03));
        reconciler.tick(FRAME_SECONDS, false);
        assertTrue(reconciler.isCorrecting());
        assertEquals(writesAfterEntry + 1, clock.writes.size());

        // 0.005 day is below the 0.01 exit threshold: the episode ends and nothing is written.
        fillRing(reconciler, clock, days(0.005));
        int writesBeforeExit = clock.writes.size();
        reconciler.tick(FRAME_SECONDS, false);
        assertFalse(reconciler.isCorrecting());
        assertEquals(writesBeforeExit, clock.writes.size());
    }

    // ---- slew cap, taper, no overshoot ----------------------------------------------------------

    @Test
    void slewIsCappedAtThirtyPercentWhileFarAndTenPercentOnTheFinalApproachWithoutOvershooting() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, DAY);

        boolean sawFastTier = false;
        boolean sawSlowTier = false;
        boolean episodeEnded = false;
        for (int frame = 0; frame < 4000; frame++) {
            long estimateBefore = reconciler.driftEstimateMillis();
            // The engine advances the clock itself first; the reconciler only nudges it afterwards.
            long beforeEngineAdvance = clock.timestamp;
            clock.timestamp += FRAME_MILLIS;
            reconciler.tick(FRAME_SECONDS, false);

            long correction = clock.timestamp - beforeEngineAdvance - FRAME_MILLIS;
            if (!reconciler.isCorrecting()) {
                assertEquals(0L, correction, "the frame that ends the episode must not correct");
                episodeEnded = true;
                break;
            }
            double expectedRate = Math.abs(estimateBefore) > CoopClockReconciler.TAPER_THRESHOLD_MILLIS
                    ? CoopClockReconciler.FAST_SLEW_RATE
                    : CoopClockReconciler.SLEW_RATE;
            sawFastTier |= expectedRate == CoopClockReconciler.FAST_SLEW_RATE;
            sawSlowTier |= expectedRate == CoopClockReconciler.SLEW_RATE;

            assertTrue(correction >= 0, "positive drift must never produce a backward correction");
            assertTrue(correction <= slewCap(expectedRate),
                    "correction " + correction + " exceeded the " + expectedRate + " cap on frame " + frame);
            assertTrue(reconciler.driftEstimateMillis() >= 0,
                    "slew overshot past the host on frame " + frame);
            assertTrue(reconciler.driftEstimateMillis() < estimateBefore,
                    "estimate did not shrink on frame " + frame);
        }

        assertTrue(episodeEnded, "the episode must terminate within the frame budget");
        assertTrue(sawFastTier, "a one-day drift must use the 30% tier at least once");
        assertTrue(sawSlowTier, "the final approach must taper to the 10% tier");
        assertFalse(reconciler.isCorrecting());
        assertTrue(reconciler.driftEstimateMillis() < CoopClockReconciler.EXIT_THRESHOLD_MILLIS);
    }

    // ---- monotonicity ---------------------------------------------------------------------------

    @Test
    void clockNeverMovesBackwardForAnyDrift() {
        for (long drift : new long[]{-5 * DAY, -DAY, -days(0.2), -days(0.06), days(0.06), days(0.2), DAY, 5 * DAY}) {
            for (boolean guestPaused : new boolean[]{false, true}) {
                FakeClock clock = new FakeClock(BASE);
                Wall wall = new Wall();
                CoopClockReconciler reconciler = reconciler(clock, wall);
                fillRing(reconciler, clock, drift);

                for (int frame = 0; frame < 500; frame++) {
                    long beforeEngineAdvance = clock.timestamp;
                    // The pump keeps handing out a positive amount while the sector is paused, but a
                    // paused engine does not advance its own clock — so a paused frame that wrote
                    // anything negative would be a naked backward write with nothing to offset it.
                    if (!guestPaused) {
                        clock.timestamp += FRAME_MILLIS;
                    }
                    reconciler.tick(FRAME_SECONDS, guestPaused);
                    assertTrue(clock.timestamp >= beforeEngineAdvance,
                            "drift=" + drift + " guestPaused=" + guestPaused + " frame=" + frame
                                    + " moved the clock backward: " + beforeEngineAdvance + " -> "
                                    + clock.timestamp);
                }
                if (guestPaused) {
                    assertTrue(clock.writes.isEmpty(),
                            "drift=" + drift + ": a live-paused guest must never be slewed");
                }
            }
        }
    }

    @Test
    void aLivePausedGuestIsNeverSlewedWhileTheHostRuns() {
        // (a) negative drift: the dangerous one. The engine clock is frozen, so the usual
        // "the frame's own advance covers the withheld amount" argument does not hold and any
        // correction at all would be a straight backward write.
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, -days(0.5));
        for (int frame = 0; frame < 5; frame++) {
            // The pump's amount stays positive while paused; only the engine's clock stops.
            reconciler.tick(FRAME_SECONDS, true);
        }

        assertTrue(clock.writes.isEmpty());
        assertEquals(BASE, clock.timestamp);
        // The episode is held, not abandoned: it resumes the moment the guest unpauses.
        assertTrue(reconciler.isCorrecting());
    }

    @Test
    void aLivePausedGuestDoesNotEvenTakeTheBigDriftSnapUntilItResumes() {
        // (b) positive drift, past the 2-day snap threshold with the persistence gate satisfied:
        // the live-paused guard sits AHEAD of the snap, so nothing happens until the guest runs.
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long drift = 3 * DAY;
        reconciler.onSnapshot(clock.timestamp + drift, false, false, false);
        wall.millis = 1_000L;
        reconciler.onSnapshot(clock.timestamp + drift, false, false, false);
        wall.millis = 2_500L;
        reconciler.onSnapshot(clock.timestamp + drift, false, false, false);

        for (int frame = 0; frame < 5; frame++) {
            reconciler.tick(FRAME_SECONDS, true);
        }
        assertTrue(clock.writes.isEmpty());
        assertEquals(BASE, clock.timestamp);

        // Same state, guest running: now it snaps.
        reconciler.tick(FRAME_SECONDS, false);
        assertEquals(List.of(BASE + drift), clock.writes);
    }

    @Test
    void sharedPauseSnapUsesTheLivePauseStateNotTheDiscardedSample() {
        // (c) the guest key-pauses ahead of the host's confirming snapshot: the host's paused
        // snapshot is measured against a still-running guest, so the pause-agreement gate discards
        // the SAMPLE — but hostPaused and the host timestamp are recorded before the gate, and by
        // tick time the guest is genuinely paused. That is a real shared pause and must snap.
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long drift = days(0.5);
        fillRing(reconciler, clock, drift);
        reconciler.onSnapshot(clock.timestamp + drift, true, false, false);
        assertEquals(1L, reconciler.pauseGateDiscards());
        assertEquals(CoopClockReconciler.RING_SIZE, reconciler.sampleCount());

        reconciler.tick(FRAME_SECONDS, true);

        assertEquals(List.of(BASE + drift), clock.writes);
        assertEquals(BASE + drift, clock.timestamp);
        assertEquals(0, reconciler.sampleCount());

        // ...and it is still forward-only: the same path with the guest ahead writes nothing.
        long ahead = -days(0.5);
        fillRing(reconciler, clock, ahead);
        reconciler.onSnapshot(clock.timestamp + ahead, true, false, false);
        reconciler.tick(FRAME_SECONDS, true);

        assertEquals(1, clock.writes.size(), "a guest-ahead shared pause must not write");
        assertEquals(BASE + drift, clock.timestamp);
    }

    @Test
    void aGuestThatIsAheadConvergesBySlowingDownNotByRewinding() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, -DAY);
        long beforeEngineAdvance = clock.timestamp;
        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);

        long correction = clock.timestamp - beforeEngineAdvance - FRAME_MILLIS;
        assertTrue(correction < 0, "a guest that is ahead must withhold part of the frame's advance");
        assertTrue(Math.abs(correction) < FRAME_MILLIS,
                "the withheld amount must be strictly less than the frame's own advance");
        assertTrue(clock.timestamp > beforeEngineAdvance);
    }

    // ---- snaps ----------------------------------------------------------------------------------

    @Test
    void sharedPauseAbsorbsTheWholePositiveDriftAndClearsTheRing() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long drift = days(0.5);
        fillRing(reconciler, clock, drift, true, true);
        reconciler.tick(0f, true);

        assertEquals(List.of(BASE + drift), clock.writes);
        assertEquals(BASE + drift, clock.timestamp);
        assertEquals(0, reconciler.sampleCount());
        assertEquals(0L, reconciler.driftEstimateMillis());
        assertFalse(reconciler.isCorrecting());
    }

    @Test
    void sharedPauseNeverWritesWhenTheGuestIsAhead() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, -days(0.5), true, true);
        reconciler.tick(0f, true);

        assertTrue(clock.writes.isEmpty());
        assertEquals(BASE, clock.timestamp);
        // The samples survive: nothing was stepped, so nothing needs invalidating.
        assertEquals(CoopClockReconciler.RING_SIZE, reconciler.sampleCount());
    }

    @Test
    void aSingleOverThresholdMedianNeverSnaps() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        // One sample only: an OS stall (GC pause, window drag) manufactures exactly this shape.
        reconciler.onSnapshot(clock.timestamp + 3 * DAY, false, false, false);
        wall.millis = 10_000L;
        reconciler.tick(FRAME_SECONDS, false);

        // A slew is fine and expected; a snap is not.
        long moved = clock.timestamp - BASE;
        assertTrue(moved <= slewCap(CoopClockReconciler.FAST_SLEW_RATE),
                "an unpersisted big drift snapped: moved " + moved + "ms");
    }

    @Test
    void bigDriftSnapsForwardOnceThePersistenceGateIsSatisfied() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long drift = 3 * DAY;
        reconciler.onSnapshot(clock.timestamp + drift, false, false, false);
        wall.millis = 1_000L;
        reconciler.onSnapshot(clock.timestamp + drift, false, false, false);
        wall.millis = 2_500L;
        reconciler.onSnapshot(clock.timestamp + drift, false, false, false);

        reconciler.tick(FRAME_SECONDS, false);

        assertEquals(List.of(BASE + drift), clock.writes);
        assertEquals(0, reconciler.sampleCount());
        assertEquals(0L, reconciler.driftEstimateMillis());
    }

    // ---- sample gates -----------------------------------------------------------------------------

    @Test
    void oneOutlierAmongNineDoesNotMoveTheEstimate() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long good = days(0.07);
        // Two good samples first so the outlier lands while the spike gate is still unarmed
        // (< 3 buffered) and therefore actually reaches the ring — this tests the MEDIAN, not the gate.
        reconciler.onSnapshot(clock.timestamp + good, false, false, false);
        reconciler.onSnapshot(clock.timestamp + good, false, false, false);
        reconciler.onSnapshot(clock.timestamp + 40 * DAY, false, false, false);
        for (int i = 0; i < 6; i++) {
            reconciler.onSnapshot(clock.timestamp + good, false, false, false);
        }

        assertEquals(CoopClockReconciler.RING_SIZE, reconciler.sampleCount());
        assertEquals(good, reconciler.driftEstimateMillis());
    }

    @Test
    void samplesTakenAcrossAPauseMirrorEdgeNeverEnterTheRing() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        reconciler.onSnapshot(clock.timestamp + days(0.5), true, false, false);
        reconciler.onSnapshot(clock.timestamp + days(0.5), false, false, true);

        assertEquals(0, reconciler.sampleCount());
        assertEquals(2L, reconciler.pauseGateDiscards());
        assertEquals(0L, reconciler.driftEstimateMillis());
    }

    @Test
    void theSpikeGateIsUnarmedBelowThreeSamplesAndSuppressesPopcornAboveIt() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler unarmed = reconciler(clock, wall);

        long wild = days(0.9);
        unarmed.onSnapshot(clock.timestamp + 1_000L, false, false, false);
        unarmed.onSnapshot(clock.timestamp + 1_000L, false, false, false);
        unarmed.onSnapshot(clock.timestamp + wild, false, false, false);
        assertEquals(3, unarmed.sampleCount());
        assertEquals(0L, unarmed.spikeGateDiscards());

        // Same sample, but with three already buffered. The ring's own RMS here is ~577 ms, so what
        // the sample is really measured against is the 0.05-game-day floor: only a deviation outside
        // the entry dead zone can count as a spike at all.
        CoopClockReconciler armed = reconciler(clock, wall);
        armed.onSnapshot(clock.timestamp + 1_000L, false, false, false);
        armed.onSnapshot(clock.timestamp + 1_000L, false, false, false);
        armed.onSnapshot(clock.timestamp + 2_000L, false, false, false);
        assertTrue(wild - 1_000L > CoopClockReconciler.SPIKE_GATE_FLOOR_MILLIS,
                "the wild sample must clear the floor or the gate has nothing to reject");
        armed.onSnapshot(clock.timestamp + wild, false, false, false);

        assertEquals(3, armed.sampleCount());
        assertEquals(1L, armed.spikeGateDiscards());
        assertEquals(1_000L, armed.driftEstimateMillis());
    }

    @Test
    void aDeviationInsideTheEntryDeadZoneIsNeverASpikeNoMatterHowSmallTheRingRms() {
        // The wedge the first live smoke found: a shared pause leaves nine near-identical samples,
        // the ring's RMS collapses to a few seconds of game time, and 3x that is smaller than any
        // real post-unpause sample. Without the floor the gate rejected everything, forever.
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, 1_000L);
        long insideTheDeadZone = days(0.04);
        assertTrue(insideTheDeadZone < CoopClockReconciler.SPIKE_GATE_FLOOR_MILLIS);
        reconciler.onSnapshot(clock.timestamp + insideTheDeadZone, false, false, false);

        assertEquals(0L, reconciler.spikeGateDiscards());
        assertEquals(0L, reconciler.persistentSteps());
        assertEquals(CoopClockReconciler.RING_SIZE, reconciler.sampleCount());
    }

    @Test
    void aSteadyDriftWalkAfterASharedPauseNeverWedgesTheGate() {
        // The measured shape at 1x: ~7,000 calendar-ms of fresh drift per 5 Hz snapshot. Every one of
        // these is a legitimate sample and has to reach the ring, or the median freezes at the
        // pause-time value and the reconciler goes blind exactly when drift starts accumulating.
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long start = days(0.02);
        fillRing(reconciler, clock, start);

        long walkPerSnapshot = 7_000L;
        int snapshots = 30;
        for (int i = 1; i <= snapshots; i++) {
            reconciler.onSnapshot(clock.timestamp + start + i * walkPerSnapshot, false, false, false);
        }

        assertEquals(0L, reconciler.spikeGateDiscards());
        assertEquals(0L, reconciler.persistentSteps());
        assertEquals(CoopClockReconciler.RING_SIZE, reconciler.sampleCount());

        long lastSample = start + snapshots * walkPerSnapshot;
        long ringSpan = CoopClockReconciler.RING_SIZE * walkPerSnapshot;
        assertTrue(reconciler.driftEstimateMillis() > start,
                "the estimate did not follow the walk at all");
        assertTrue(lastSample - reconciler.driftEstimateMillis() <= ringSpan,
                "median " + reconciler.driftEstimateMillis() + " fell more than one ring span behind"
                        + " the walk's last sample " + lastSample);
    }

    @Test
    void threeConsecutiveRejectionsAreTreatedAsAPersistentStepRatherThanPopcorn() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long settled = days(0.02);
        fillRing(reconciler, clock, settled);

        long popcorn = settled + 40 * DAY;
        // One far-outside sample is popcorn: dropped, and it moves nothing.
        reconciler.onSnapshot(clock.timestamp + popcorn, false, false, false);
        assertEquals(1L, reconciler.spikeGateDiscards());
        assertEquals(settled, reconciler.driftEstimateMillis());
        assertEquals(CoopClockReconciler.RING_SIZE, reconciler.sampleCount());

        // Two in a row: still popcorn.
        reconciler.onSnapshot(clock.timestamp + popcorn, false, false, false);
        assertEquals(2L, reconciler.spikeGateDiscards());
        assertEquals(0L, reconciler.persistentSteps());
        assertEquals(settled, reconciler.driftEstimateMillis());

        // Three in a row is not noise, it is the world having moved. Drop the stale ring and start a
        // fresh one from the step sample itself.
        reconciler.onSnapshot(clock.timestamp + popcorn, false, false, false);
        assertEquals(3L, reconciler.spikeGateDiscards());
        assertEquals(1L, reconciler.persistentSteps());
        assertEquals(1, reconciler.sampleCount());
        assertEquals(popcorn, reconciler.driftEstimateMillis());

        // Samples around the new level are accepted normally...
        for (int i = 1; i <= 3; i++) {
            reconciler.onSnapshot(clock.timestamp + popcorn + i * 7_000L, false, false, false);
        }
        assertEquals(4, reconciler.sampleCount());
        assertEquals(3L, reconciler.spikeGateDiscards());

        // ...and the consecutive counter really was reset: the next far-outside sample is popcorn
        // again, not an instant second step.
        reconciler.onSnapshot(clock.timestamp + popcorn + 40 * DAY, false, false, false);
        assertEquals(4L, reconciler.spikeGateDiscards());
        assertEquals(1L, reconciler.persistentSteps());
        assertEquals(4, reconciler.sampleCount());
    }

    @Test
    void aPersistentStepDropsTheRingWithoutEndingTheCorrectionEpisode() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, days(0.5));
        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);
        assertTrue(reconciler.isCorrecting());

        long stepped = reconciler.driftEstimateMillis() + 40 * DAY;
        for (int i = 0; i < CoopClockReconciler.SPIKE_GATE_MAX_CONSECUTIVE; i++) {
            reconciler.onSnapshot(clock.timestamp + stepped, false, false, false);
        }

        assertEquals(1L, reconciler.persistentSteps());
        assertEquals(1, reconciler.sampleCount());
        // resetRing() drops samples and the estimate; clearSamples() is the one that ends episodes.
        assertTrue(reconciler.isCorrecting(), "a persistent step must not abandon the episode");
    }

    @Test
    void anInducedDriftAfterASharedPauseIsMeasuredAndCorrected() {
        // Smoke step 3, end to end: the guest's process is stalled for a few seconds right after a
        // shared pause has flattened the ring, so it comes back a game-day behind. The wedged gate
        // rejected every one of those samples, so the drift was never even measured.
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, 2_000L);
        assertEquals(2_000L, reconciler.driftEstimateMillis());

        for (int i = 0; i < 3; i++) {
            reconciler.onSnapshot(clock.timestamp + DAY, false, false, false);
        }

        assertEquals(1L, reconciler.persistentSteps());
        assertEquals(DAY, reconciler.driftEstimateMillis());

        long beforeEngineAdvance = clock.timestamp;
        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);

        long correction = clock.timestamp - beforeEngineAdvance - FRAME_MILLIS;
        assertEquals(1, clock.writes.size());
        assertTrue(correction > 0, "the induced drift must produce a forward correction");
        assertTrue(correction <= slewCap(CoopClockReconciler.FAST_SLEW_RATE));
        assertTrue(reconciler.isCorrecting());
    }

    // ---- anti-windup ------------------------------------------------------------------------------

    @Test
    void anAppliedCorrectionIsSubtractedFromEveryBufferedSample() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        long drift = days(0.5);
        fillRing(reconciler, clock, drift);

        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);
        long applied = clock.timestamp - BASE - FRAME_MILLIS;
        assertTrue(applied > 0);
        assertEquals(drift - applied, reconciler.driftEstimateMillis());

        // Push ONE fresh sample measuring the real, now-smaller drift. If the eight stale samples
        // had kept their pre-correction values the median would snap back to the full drift and the
        // 5 Hz loop would re-command a correction it already issued.
        long remaining = drift - applied;
        reconciler.onSnapshot(clock.timestamp + remaining, false, false, false);
        assertEquals(remaining, reconciler.driftEstimateMillis());
    }

    @Test
    void repeatedTicksWithoutNewSamplesKeepShrinkingTheEstimateInsteadOfReapplyingIt() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, days(0.5));
        long previous = reconciler.driftEstimateMillis();
        for (int frame = 0; frame < 10; frame++) {
            clock.timestamp += FRAME_MILLIS;
            reconciler.tick(FRAME_SECONDS, false);
            long current = reconciler.driftEstimateMillis();
            assertTrue(current < previous, "estimate stalled or grew on frame " + frame);
            previous = current;
        }
    }

    // ---- guest-ahead visibility -------------------------------------------------------------------

    @Test
    void sustainedGuestAheadLogsExactlyOneWarning() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CapturingAppender appender = CapturingAppender.attach(CoopClockReconciler.class);
        try {
            CoopClockReconciler reconciler = reconciler(clock, wall);
            fillRing(reconciler, clock, -days(0.5));

            // Not yet: the condition has to persist past the 60 s window.
            wall.millis = 30_000L;
            reconciler.tick(FRAME_SECONDS, false);
            assertTrue(appender.warnings().isEmpty());

            for (int frame = 0; frame < 200; frame++) {
                wall.millis = 61_000L + frame;
                reconciler.tick(FRAME_SECONDS, false);
            }

            List<String> guestAhead = appender.matching("Coop clock guest ahead");
            assertEquals(1, guestAhead.size(), "expected exactly one warning, got " + guestAhead);
        } finally {
            appender.detach();
        }
    }

    // ---- failure handling -------------------------------------------------------------------------

    @Test
    void aThrowingPortGoesStickyUnavailableWithoutPropagating() {
        FakeClock clock = new FakeClock(BASE);
        clock.throwOnGet = true;
        Wall wall = new Wall();
        CapturingAppender appender = CapturingAppender.attach(CoopClockReconciler.class);
        try {
            CoopClockReconciler reconciler = reconciler(clock, wall);

            reconciler.onSnapshot(BASE + DAY, false, false, false);
            assertFalse(reconciler.isAvailable());
            assertEquals(1, appender.warnings().size());

            // The engine recovering does not un-stick it: one failure means uncorrected drift for
            // the rest of the session, which is exactly the pre-7c behaviour.
            clock.throwOnGet = false;
            reconciler.onSnapshot(BASE + DAY, false, false, false);
            reconciler.tick(FRAME_SECONDS, false);
            assertFalse(reconciler.isAvailable());
            assertEquals(0, reconciler.sampleCount());
            assertTrue(clock.writes.isEmpty());
            assertEquals(1, appender.warnings().size(), "the warning must be logged once, not per frame");
        } finally {
            appender.detach();
        }
    }

    @Test
    void aThrowingWriteAlsoGoesStickyUnavailable() {
        FakeClock clock = new FakeClock(BASE);
        clock.throwOnSet = true;
        Wall wall = new Wall();
        CapturingAppender appender = CapturingAppender.attach(CoopClockReconciler.class);
        try {
            CoopClockReconciler reconciler = reconciler(clock, wall);
            fillRing(reconciler, clock, DAY);
            reconciler.tick(FRAME_SECONDS, false);

            assertFalse(reconciler.isAvailable());
            assertTrue(clock.writes.isEmpty());
            assertEquals(1, appender.warnings().size());
        } finally {
            appender.detach();
        }
    }

    @Test
    void disablePropertyMakesItANoOpAndNeverResolvesTheHandle() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        boolean[] resolverCalled = {false};
        CapturingAppender appender = CapturingAppender.attach(CoopClockReconciler.class);
        try {
            CoopClockReconciler reconciler = new CoopClockReconciler(() -> {
                resolverCalled[0] = true;
                return clock;
            }, () -> wall.millis, true);

            assertFalse(reconciler.isAvailable());
            reconciler.onSnapshot(BASE + 5 * DAY, false, false, false);
            reconciler.tick(FRAME_SECONDS, false);

            assertFalse(resolverCalled[0]);
            assertTrue(clock.writes.isEmpty());
            assertEquals(0, reconciler.sampleCount());
            assertEquals(1, appender.matching(CoopClockReconciler.DISABLE_PROPERTY).size());
        } finally {
            appender.detach();
        }
    }

    @Test
    void aNullResolveIsRetriedRatherThanTreatedAsFailure() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        boolean[] ready = {false};
        CoopClockReconciler reconciler =
                new CoopClockReconciler(() -> ready[0] ? clock : null, () -> wall.millis, false);

        // No live campaign clock yet (title screen / teardown): not available, but not poisoned.
        assertFalse(reconciler.isAvailable());
        ready[0] = true;
        assertTrue(reconciler.isAvailable());

        fillRing(reconciler, clock, days(0.5));
        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);
        assertEquals(1, clock.writes.size());
    }

    @Test
    void clearSamplesInvalidatesTheRingAndEndsTheEpisode() {
        FakeClock clock = new FakeClock(BASE);
        Wall wall = new Wall();
        CoopClockReconciler reconciler = reconciler(clock, wall);

        fillRing(reconciler, clock, DAY);
        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);
        assertTrue(reconciler.isCorrecting());

        reconciler.clearSamples();

        assertEquals(0, reconciler.sampleCount());
        assertEquals(0L, reconciler.driftEstimateMillis());
        assertFalse(reconciler.isCorrecting());

        int writes = clock.writes.size();
        clock.timestamp += FRAME_MILLIS;
        reconciler.tick(FRAME_SECONDS, false);
        assertEquals(writes, clock.writes.size());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static CoopClockReconciler reconciler(FakeClock clock, Wall wall) {
        return new CoopClockReconciler(() -> clock, () -> wall.millis, false);
    }

    /** Recomputes the production clamp exactly, float widening and all, instead of approximating it. */
    private static long slewCap(double rate) {
        return (long) (rate * (FRAME_SECONDS * CoopClockReconciler.MILLIS_PER_REAL_SECOND));
    }

    private static long days(double gameDays) {
        return Math.round(gameDays * DAY);
    }

    private static void fillRing(CoopClockReconciler reconciler, FakeClock clock, long driftMillis) {
        fillRing(reconciler, clock, driftMillis, false, false);
    }

    /** Replaces the whole ring with samples of exactly {@code driftMillis}, so the median is it. */
    private static void fillRing(CoopClockReconciler reconciler, FakeClock clock, long driftMillis,
                                 boolean hostPaused, boolean guestPaused) {
        for (int i = 0; i < CoopClockReconciler.RING_SIZE; i++) {
            reconciler.onSnapshot(clock.timestamp + driftMillis, hostPaused, false, guestPaused);
        }
    }

    private static final class Wall {
        private long millis;
    }

    private static final class FakeClock implements CoopClockReconciler.ClockPort {
        private long timestamp;
        private final List<Long> writes = new ArrayList<>();
        private boolean throwOnGet;
        private boolean throwOnSet;

        private FakeClock(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public long getTimestamp() {
            if (throwOnGet) {
                throw new IllegalStateException("no campaign clock");
            }
            return timestamp;
        }

        @Override
        public void setTimestamp(long value) {
            if (throwOnSet) {
                throw new IllegalStateException("timestamp handle went stale");
            }
            timestamp = value;
            writes.add(value);
        }
    }

    private static final class CapturingAppender extends AppenderSkeleton {
        private final List<String> warnings = new ArrayList<>();
        private Logger attachedTo;

        private static CapturingAppender attach(Class<?> source) {
            CapturingAppender appender = new CapturingAppender();
            appender.attachedTo = Logger.getLogger(source);
            appender.attachedTo.addAppender(appender);
            return appender;
        }

        private void detach() {
            if (attachedTo != null) {
                attachedTo.removeAppender(this);
            }
        }

        private List<String> warnings() {
            return warnings;
        }

        private List<String> matching(String needle) {
            List<String> hits = new ArrayList<>();
            for (String warning : warnings) {
                if (warning.contains(needle)) {
                    hits.add(warning);
                }
            }
            return hits;
        }

        @Override
        protected void append(LoggingEvent event) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                warnings.add(String.valueOf(event.getMessage()));
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
