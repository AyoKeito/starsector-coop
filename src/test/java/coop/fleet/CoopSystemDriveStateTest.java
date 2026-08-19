package coop.fleet;

import coop.fleet.CoopSystemDriveState.Decision;
import coop.fleet.CoopSystemDriveState.Input;
import coop.fleet.CoopSystemDriveState.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every transition of the ownership state machine. This is the part that decides whether a live star
 * system is advanced twice, not at all, or by the wrong owner, so the coverage here is deliberately
 * exhaustive rather than representative.
 */
class CoopSystemDriveStateTest {

    private static final String GUEST_SYSTEM = "askonia";
    private static final String HOST_SYSTEM = "corvus";

    /** A fully healthy driving frame; individual tests knock out one input at a time. */
    private static Input driving() {
        return new Input(true, false, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false, "");
    }

    private static Input from(Input base, java.util.function.UnaryOperator<Input> change) {
        return change.apply(base);
    }

    // ---- The happy path --------------------------------------------------------------------------

    @Test
    void drivesWhenTheGuestIsAloneInASystemTheHostIsNotIn() {
        Decision decision = CoopSystemDriveState.decide(driving());
        assertEquals(Outcome.DRIVE, decision.outcome());
        assertEquals(GUEST_SYSTEM, decision.driveSystemId());
        assertTrue(decision.advanceNow());
        assertTrue(decision.owns());
        assertTrue(decision.startedDriving());
        assertFalse(decision.stoppedDriving());
    }

    @Test
    void steadyStateDrivingIsNotReportedAsATransition() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false,
                        GUEST_SYSTEM));
        assertEquals(Outcome.DRIVE, decision.outcome());
        assertTrue(decision.advanceNow());
        assertFalse(decision.startedDriving());
        assertFalse(decision.stoppedDriving());
    }

    // ---- Hard stops, in precedence order ---------------------------------------------------------

    @Test
    void killSwitchStopsEverything() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(false, false, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.DISABLED, decision.outcome());
        assertFalse(decision.owns());
        assertFalse(decision.advanceNow());
    }

    @Test
    void permanentDisableStopsEverythingEvenWithTheSwitchOn() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, true, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.DISABLED, decision.outcome());
        assertFalse(decision.owns());
    }

    @Test
    void noSessionMeansNoDrive() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, false, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.NO_SESSION, decision.outcome());
        assertFalse(decision.owns());
    }

    @Test
    void saveInProgressSuspendsTheDrive() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, true, false, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.SAVE_IN_PROGRESS, decision.outcome());
        assertFalse(decision.owns());
        assertFalse(decision.advanceNow());
    }

    @Test
    void fastAdvanceSuspendsTheDriveBecauseTheEngineRunsEverySystemItself() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, true, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.FAST_ADVANCE, decision.outcome());
        assertFalse(decision.advanceNow());
    }

    @Test
    void noGuestMirrorMeansNothingToDrive() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, "", false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.NO_GUEST_SYSTEM, decision.outcome());
        assertFalse(decision.owns());
    }

    @Test
    void guestInHyperspaceIsOutOfScope() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, "", true, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.GUEST_IN_HYPERSPACE, decision.outcome());
        assertFalse(decision.owns());
    }

    @Test
    void hyperspaceWinsOverAnIdThatSomehowCameThrough() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, "hyperspace", true, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.GUEST_IN_HYPERSPACE, decision.outcome());
        assertFalse(decision.owns());
    }

    @Test
    void hostInTheSameSystemHandsItBackToTheEngine() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, GUEST_SYSTEM, false, ""));
        assertEquals(Outcome.HOST_LOCATION, decision.outcome());
        assertFalse(decision.owns());
        assertFalse(decision.advanceNow());
    }

    // ---- Precedence: an earlier stop must beat a later condition ---------------------------------

    @Test
    void killSwitchBeatsEveryOtherInput() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(false, false, true, true, true, GUEST_SYSTEM, true, GUEST_SYSTEM, true, GUEST_SYSTEM));
        assertEquals(Outcome.DISABLED, decision.outcome());
        assertTrue(decision.stoppedDriving());
    }

    @Test
    void sessionEndBeatsSaveAndFastAdvance() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, false, true, true, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.NO_SESSION, decision.outcome());
    }

    @Test
    void saveBeatsFastAdvance() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, true, true, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.SAVE_IN_PROGRESS, decision.outcome());
    }

    @Test
    void fastAdvanceBeatsAPerfectlyGoodGuestSystem() {
        // The fast-advance branch (CampaignEngine.advance:1017-1029) advances every system every
        // frame, so driving on top of it would be a straight double advance.
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, true, GUEST_SYSTEM, false, HOST_SYSTEM, false,
                        GUEST_SYSTEM));
        assertEquals(Outcome.FAST_ADVANCE, decision.outcome());
        assertFalse(decision.advanceNow());
        assertTrue(decision.stoppedDriving());
    }

    @Test
    void hostLocationBeatsTheEngineAdvancedBackstop() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, GUEST_SYSTEM, true,
                        GUEST_SYSTEM));
        assertEquals(Outcome.HOST_LOCATION, decision.outcome());
        assertTrue(decision.stoppedDriving());
    }

    // ---- The double-advance backstop --------------------------------------------------------------

    @Test
    void engineAdvancedThisFrameKeepsOwnershipButSkipsTheAdvance() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, true,
                        GUEST_SYSTEM));
        assertEquals(Outcome.ENGINE_ADVANCED, decision.outcome());
        assertTrue(decision.owns());
        assertEquals(GUEST_SYSTEM, decision.driveSystemId());
        assertFalse(decision.advanceNow());
        assertFalse(decision.startedDriving());
        assertFalse(decision.stoppedDriving());
    }

    @Test
    void takingOverOnAFrameTheEngineAlreadyAdvancedIsNotAnAnomaly() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, true, ""));
        assertEquals(Outcome.ENGINE_ADVANCED, decision.outcome());
        assertTrue(decision.startedDriving());
        assertFalse(decision.advanceNow());
    }

    // ---- Transitions ------------------------------------------------------------------------------

    @Test
    void guestLeavingReleasesOwnership() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, "", false, HOST_SYSTEM, false, GUEST_SYSTEM));
        assertEquals(Outcome.NO_GUEST_SYSTEM, decision.outcome());
        assertTrue(decision.stoppedDriving());
        assertFalse(decision.startedDriving());
        assertFalse(decision.owns());
    }

    @Test
    void guestMovingToAnotherSystemIsAReleaseAndATakeInTheSameFrame() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, "kumari_kandam", false, HOST_SYSTEM, false,
                        GUEST_SYSTEM));
        assertEquals(Outcome.DRIVE, decision.outcome());
        assertEquals("kumari_kandam", decision.driveSystemId());
        assertTrue(decision.startedDriving());
        assertTrue(decision.stoppedDriving());
        assertTrue(decision.advanceNow());
    }

    @Test
    void hostJumpingInHandsOffAndHostLeavingTakesBack() {
        Decision handoff = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, GUEST_SYSTEM, false,
                        GUEST_SYSTEM));
        assertEquals(Outcome.HOST_LOCATION, handoff.outcome());
        assertTrue(handoff.stoppedDriving());
        assertFalse(handoff.owns());

        Decision reclaim = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false, ""));
        assertEquals(Outcome.DRIVE, reclaim.outcome());
        assertTrue(reclaim.startedDriving());
    }

    @Test
    void sessionEndFromADrivingStateReleases() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, false, false, false, GUEST_SYSTEM, false, HOST_SYSTEM, false,
                        GUEST_SYSTEM));
        assertEquals(Outcome.NO_SESSION, decision.outcome());
        assertTrue(decision.stoppedDriving());
    }

    @Test
    void releasingWhenNothingWasOwnedIsNotATransition() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, false, false, false, "", false, "", false, ""));
        assertEquals(Outcome.NO_SESSION, decision.outcome());
        assertFalse(decision.stoppedDriving());
        assertFalse(decision.startedDriving());
    }

    // ---- Input hygiene ----------------------------------------------------------------------------

    @Test
    void nullIdsAreNormalisedToEmptyAndNeverMatchEachOther() {
        Input in = new Input(true, false, true, false, false, null, false, null, false, null);
        assertEquals("", in.guestSystemId());
        assertEquals("", in.hostLocationId());
        assertEquals("", in.currentlyDrivenId());
        // An absent guest and an absent host location must not read as "guest is where the host is".
        assertEquals(Outcome.NO_GUEST_SYSTEM, CoopSystemDriveState.decide(in).outcome());
    }

    @Test
    void anUnknownHostLocationStillAllowsDriving() {
        Decision decision = CoopSystemDriveState.decide(
                new Input(true, false, true, false, false, GUEST_SYSTEM, false, "", false, ""));
        assertEquals(Outcome.DRIVE, decision.outcome());
    }

    @Test
    void everyStopOutcomeReleasesOwnershipAndForbidsAdvancing() {
        Input[] stops = {
                from(driving(), in -> copy(in, false, in.permanentlyDisabled(), in.sessionStreaming(),
                        in.saveInProgress(), in.inFastAdvance(), in.guestSystemId(),
                        in.guestInHyperspace(), in.hostLocationId())),
                from(driving(), in -> copy(in, true, true, true, false, false, in.guestSystemId(),
                        false, in.hostLocationId())),
                from(driving(), in -> copy(in, true, false, false, false, false, in.guestSystemId(),
                        false, in.hostLocationId())),
                from(driving(), in -> copy(in, true, false, true, true, false, in.guestSystemId(),
                        false, in.hostLocationId())),
                from(driving(), in -> copy(in, true, false, true, false, true, in.guestSystemId(),
                        false, in.hostLocationId())),
                from(driving(), in -> copy(in, true, false, true, false, false, "", false,
                        in.hostLocationId())),
                from(driving(), in -> copy(in, true, false, true, false, false, "", true,
                        in.hostLocationId())),
                from(driving(), in -> copy(in, true, false, true, false, false, GUEST_SYSTEM, false,
                        GUEST_SYSTEM)),
        };
        for (Input stop : stops) {
            Decision decision = CoopSystemDriveState.decide(withOwned(stop));
            assertFalse(decision.owns(), "must not own after " + decision.outcome());
            assertFalse(decision.advanceNow(), "must not advance after " + decision.outcome());
            assertTrue(decision.stoppedDriving(), "must report release for " + decision.outcome());
            assertEquals("", decision.driveSystemId());
        }
    }

    private static Input copy(Input in, boolean enabled, boolean disabled, boolean streaming,
                              boolean saving, boolean fast, String guestId, boolean hyper,
                              String hostId) {
        return new Input(enabled, disabled, streaming, saving, fast, guestId, hyper, hostId,
                in.engineAdvancedThisFrame(), in.currentlyDrivenId());
    }

    private static Input withOwned(Input in) {
        return new Input(in.featureEnabled(), in.permanentlyDisabled(), in.sessionStreaming(),
                in.saveInProgress(), in.inFastAdvance(), in.guestSystemId(), in.guestInHyperspace(),
                in.hostLocationId(), in.engineAdvancedThisFrame(), GUEST_SYSTEM);
    }
}
