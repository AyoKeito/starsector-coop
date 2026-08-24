package coop.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 18: the bookkeeping that turns one lost claim into exactly one forced close and one log
 * line, instead of the claim/reject ping-pong the old reject handler produced.
 */
class CoopRejectTrackerTest {

    @Test
    void rejectedEntityIsNotReclaimableWhileItsDialogIsOpen() {
        CoopRejectTracker tracker = new CoopRejectTracker();

        assertTrue(tracker.onRejected("market-1"), "the first reject is the one worth logging");
        assertTrue(tracker.isRejected("market-1"));
        assertEquals(CoopRejectTracker.Action.DISMISS_AND_NOTIFY, tracker.onFrame("market-1"));
        assertTrue(tracker.isRejected("market-1"),
                "still rejected: the dialog is open until a frame observes it gone");
    }

    @Test
    void repeatedRejectsForTheSameEntityDoNotRelog() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");

        assertFalse(tracker.onRejected("market-1"));
        assertFalse(tracker.onRejected("market-1"));
    }

    @Test
    void onlyTheFirstFrameNotifiesAndLaterFramesJustReDismiss() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");

        assertEquals(CoopRejectTracker.Action.DISMISS_AND_NOTIFY, tracker.onFrame("market-1"));
        assertEquals(CoopRejectTracker.Action.DISMISS, tracker.onFrame("market-1"));
        assertEquals(CoopRejectTracker.Action.DISMISS, tracker.onFrame("market-1"));
        assertEquals(3, tracker.dismissAttempts());
    }

    @Test
    void aFrameWithoutTheRejectedDialogClearsTheRejection() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");
        tracker.onFrame("market-1");

        assertEquals(CoopRejectTracker.Action.NONE, tracker.onFrame(null));
        assertFalse(tracker.isRejected("market-1"));
        assertNull(tracker.rejectedEntityId());
        assertEquals(0, tracker.dismissAttempts());
    }

    @Test
    void aDialogOnAnotherEntityAlsoMeansTheRejectedOneClosed() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");

        assertEquals(CoopRejectTracker.Action.NONE, tracker.onFrame("market-2"));
        assertFalse(tracker.isRejected("market-1"));
    }

    @Test
    void neverTouchesADialogForAnUnrelatedEntity() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");

        assertEquals(CoopRejectTracker.Action.NONE, tracker.onFrame("derelict-7"));
        assertFalse(tracker.isRejected("derelict-7"));
    }

    @Test
    void aReportedCloseBeforeAnyDismissalClearsTheRejection() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");

        assertTrue(tracker.onDialogClosed("market-1"),
                "the reject landed after the player already left: nothing to force-close");
        assertFalse(tracker.isRejected("market-1"));
    }

    @Test
    void aReportedCloseCannotCancelAForcedCloseAlreadyInFlight() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");
        tracker.onFrame("market-1");

        // Vanilla reports a market close when the trade screen is left, which can happen with the
        // interaction dialog still up. That must not end the forced close.
        assertFalse(tracker.onDialogClosed("market-1"));
        assertTrue(tracker.isRejected("market-1"));
        assertEquals(CoopRejectTracker.Action.DISMISS, tracker.onFrame("market-1"));

        assertEquals(CoopRejectTracker.Action.NONE, tracker.onFrame(null));
        assertFalse(tracker.isRejected("market-1"));
    }

    @Test
    void aReportedCloseForAnotherMarketIsIgnored() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");

        assertFalse(tracker.onDialogClosed("market-2"));
        assertFalse(tracker.onDialogClosed(null));
        assertTrue(tracker.isRejected("market-1"));
    }

    @Test
    void clearDropsTheRejectionAndReportsWhetherThereWasOne() {
        CoopRejectTracker tracker = new CoopRejectTracker();

        assertFalse(tracker.clear(), "nothing tracked, nothing to log");

        tracker.onRejected("market-1");
        assertTrue(tracker.clear(), "a disconnect mid-forced-close is worth one line");
        assertFalse(tracker.isRejected("market-1"));
        assertEquals(CoopRejectTracker.Action.NONE, tracker.onFrame("market-1"));
    }

    @Test
    void aRejectForADifferentEntityReplacesTheTrackedOne() {
        CoopRejectTracker tracker = new CoopRejectTracker();
        tracker.onRejected("market-1");
        tracker.onFrame("market-1");

        assertTrue(tracker.onRejected("market-2"));
        assertFalse(tracker.isRejected("market-1"));
        assertEquals(0, tracker.dismissAttempts(), "the new rejection starts its own notify cycle");
        assertEquals(CoopRejectTracker.Action.DISMISS_AND_NOTIFY, tracker.onFrame("market-2"));
    }

    @Test
    void blankAndNullIdsAreIgnored() {
        CoopRejectTracker tracker = new CoopRejectTracker();

        assertFalse(tracker.onRejected(null));
        assertFalse(tracker.onRejected("   "));
        assertNull(tracker.rejectedEntityId());
        assertFalse(tracker.isRejected(null));
    }
}
