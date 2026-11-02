package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoopSlipstreamFadeDriverTest {
    private static final long ORIGIN = -55_644_500_923_000L;
    private static final long Q = CoopSlipstreamFadeDriver.QUANTUM_CAMPAIGN_MILLIS;

    @Test
    void differentFramePartitionsAndBackgroundBatchesExecuteTheSameNativeSteps() {
        FakePort fine = new FakePort(), coarse = new FakePort(), background = new FakePort();
        var a = driver(fine); var b = driver(coarse); var c = driver(background);
        for (int tick = 1; tick <= 120; tick++) a.advanceTo(ORIGIN + tick * Q, false);
        for (int frame = 1; frame <= 30; frame++) b.advanceTo(ORIGIN + frame * 4L * Q, false);
        c.advanceTo(ORIGIN + 120 * Q, false);
        assertEquals(fine.state, coarse.state);
        assertEquals(fine.state, background.state);
        assertEquals(120, fine.state.steps());
        assertEquals(ORIGIN, c.originCampaignMillis());
    }

    @Test
    void fractionalRemaindersNeverCommitVariableNativeSteps() {
        FakePort split = new FakePort(), whole = new FakePort();
        var a = driver(split); var b = driver(whole);
        for (int half = 1; half <= 241; half++) a.advanceTo(ORIGIN + half * Q / 2, false);
        b.advanceTo(ORIGIN + 120 * Q, false);
        assertEquals(120, a.committedTick());
        assertEquals(whole.state, split.state);
        assertEquals(ORIGIN + 120 * Q, a.committedCampaignMillis());
    }

    @Test
    void aNormalReconcilerSizedCalendarSnapAdvancesFadersAndElapsedTogether() {
        FakePort snapped = new FakePort(), uninterrupted = new FakePort();
        var a = driver(snapped); var b = driver(uninterrupted);
        a.advanceTo(ORIGIN + 30 * Q, false);
        a.advanceTo(ORIGIN + 30 * Q + 4_320_000L, false); // 0.05 campaign day.
        b.advanceTo(ORIGIN + 90 * Q, false);
        assertEquals(uninterrupted.state, snapped.state);
    }

    @Test
    void anIntraFrameSlewRebasesFromKnownStateInsteadOfAdvancingFadersBackward() {
        FakePort rewound = new FakePort(), reference = new FakePort();
        var a = driver(rewound); var b = driver(reference);
        a.advanceTo(ORIGIN + 60 * Q, false);
        a.advanceTo(ORIGIN + 120 * Q, false);
        a.advanceTo(ORIGIN + 110 * Q, false);
        b.advanceTo(ORIGIN + 110 * Q, false);
        assertEquals(reference.state, rewound.state);
        assertEquals(110, a.committedTick());
        assertTrue(rewound.smallestStep > 0f);
    }

    @Test
    void aPausedHostCheckpointDoesNotAdvanceThroughAGuestCalendarOnlyCatchup() {
        FakePort port = new FakePort();
        var timeline = new CoopSlipstreamRecord.Timeline(ORIGIN, 120, ORIGIN + 120 * Q, true, false);
        var controlled = new CoopSlipstreamFadeDriver<>(port, timeline);
        State checkpoint = port.state;
        assertEquals(0, controlled.advanceTo(ORIGIN + 180 * Q, true));
        assertEquals(checkpoint, port.state);
        assertEquals(120, controlled.committedTick());
        assertFalse(controlled.hostInHyperspace());
        assertEquals(60, controlled.advanceTo(ORIGIN + 180 * Q, false));
    }

    @Test
    void aReceivedFutureAnchorHoldsRatherThanInventingNegativeHistory() {
        FakePort port = new FakePort();
        var controlled = new CoopSlipstreamFadeDriver<>(port,
                new CoopSlipstreamRecord.Timeline(ORIGIN, 20, ORIGIN + 20 * Q, false, true));
        State checkpoint = port.state;
        assertEquals(0, controlled.advanceTo(ORIGIN + 10 * Q, false));
        assertEquals(checkpoint, port.state);
        assertEquals(20, controlled.committedTick());
    }

    @Test
    void commandAdoptionPreservesOriginAndUsesGenuineNativeInput() {
        FakePort port = new FakePort(); var controlled = driver(port);
        controlled.advanceTo(ORIGIN + 10 * Q, false);
        port.state = new State(0f, 0.7f, 0, false); // Native despawn/ability reset.
        controlled.adoptCommand(ORIGIN + 11 * Q, false, false);
        controlled.advanceTo(ORIGIN + 12 * Q, false);
        assertEquals(ORIGIN, controlled.originCampaignMillis());
        assertEquals(1, port.state.steps());
        assertFalse(port.state.mode());
        assertEquals(0.7f - CoopSlipstreamFadeDriver.STEP_SECONDS, port.state.brightness());
    }

    @Test
    void reconnectCheckpointsRetainTheirOriginalGridAndResumeAtTheNextFullTick() {
        FakePort port = new FakePort(); var controlled = driver(port);
        controlled.advanceTo(ORIGIN + 37 * Q, false);
        State checkpoint = port.state;
        var timeline = controlled.timeline(ORIGIN + 37 * Q + Q / 2, false);
        controlled.adoptCheckpoint(timeline, checkpoint);
        controlled.advanceTo(ORIGIN + 38 * Q, false);
        assertEquals(ORIGIN, controlled.originCampaignMillis());
        assertEquals(38, controlled.committedTick());
        assertEquals(38, port.state.steps());
    }

    @Test
    void largeCalendarJumpsHaveABoundedReplayBudget() {
        FakePort port = new FakePort(); var controlled = driver(port);
        long target = ORIGIN + 5000 * Q;
        assertEquals(CoopSlipstreamFadeDriver.MAX_STEPS_PER_CALL, controlled.advanceTo(target, false));
        assertEquals(4096, controlled.committedTick());
        assertEquals(904, controlled.advanceTo(target, false));
        assertEquals(5000, controlled.committedTick());
    }

    private static CoopSlipstreamFadeDriver<State> driver(FakePort port) {
        return new CoopSlipstreamFadeDriver<>(port, CoopSlipstreamRecord.Timeline.atOrigin(ORIGIN, false, true));
    }

    private record State(float elapsed, float brightness, int steps, boolean mode) { }
    private static final class FakePort implements CoopSlipstreamFadeDriver.Port<State> {
        State state = new State(0f, 1f, 0, true);
        float smallestStep = Float.POSITIVE_INFINITY;
        @Override public State capture() { return state; }
        @Override public void restore(State value) { state = value; }
        @Override public void advance(float seconds, boolean mode) {
            assertEquals(CoopSlipstreamFadeDriver.STEP_SECONDS, seconds);
            smallestStep = Math.min(smallestStep, seconds);
            state = new State(state.elapsed() + seconds, Math.max(0f, state.brightness() - seconds),
                    state.steps() + 1, mode);
        }
    }
}
