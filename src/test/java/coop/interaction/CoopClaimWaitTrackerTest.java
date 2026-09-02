package coop.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopClaimWaitTrackerTest {

    private static final int LOOPBACK = 0;
    private static final int WAN_P95 = 200;

    @Test
    void anUnmeasuredLinkUsesTheOneSecondFloor() {
        assertEquals(1000L, CoopClaimWaitTracker.waitThresholdMillis(LOOPBACK));
        assertEquals(1000L, CoopClaimWaitTracker.waitThresholdMillis(-5));
        // 4 x 200 ms = 800 ms, still under the floor: a good WAN link says nothing at all.
        assertEquals(1000L, CoopClaimWaitTracker.waitThresholdMillis(WAN_P95));
        // 4 x 400 ms clears it.
        assertEquals(1600L, CoopClaimWaitTracker.waitThresholdMillis(400));
    }

    @Test
    void nothingIsSaidWhileTheAnswerIsStillPlausiblyInFlight() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);

        assertNull(tracker.pollWarning(1000L, WAN_P95));
        assertNull(tracker.pollWarning(1999L, WAN_P95), "still inside max(1000, 4 x p95)");
        assertFalse(tracker.warned());
    }

    @Test
    void theNoticeIsPostedOnceAndOnlyOncePerClaim() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);

        String first = tracker.pollWarning(2000L, WAN_P95);
        assertNotNull(first);
        assertTrue(first.contains("Waiting for the host"));
        assertTrue(tracker.warned());
        assertNull(tracker.pollWarning(2001L, WAN_P95), "a per-frame poll must not repeat the notice");
        assertNull(tracker.pollWarning(9000L, WAN_P95));
    }

    @Test
    void aLateAnswerPostsNothingMore() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);
        assertNotNull(tracker.pollWarning(2000L, WAN_P95));

        assertTrue(tracker.onAnswered("market-1"), "the late accept/reject clears the wait");
        assertNull(tracker.pendingEntityId());
        assertNull(tracker.pollWarning(99_000L, WAN_P95));
    }

    @Test
    void aPromptAnswerNeverWarnsAtAll() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);

        assertTrue(tracker.onAnswered("market-1"));
        assertNull(tracker.pollWarning(50_000L, WAN_P95));
        assertFalse(tracker.warned());
    }

    @Test
    void anAnswerAboutSomeoneElsesClaimDoesNotClearOurs() {
        // The host broadcasts accepts for the other player's claims too; those say nothing about ours.
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);

        assertFalse(tracker.onAnswered("market-2"));
        assertEquals("market-1", tracker.pendingEntityId());
        assertNotNull(tracker.pollWarning(2000L, WAN_P95));
    }

    @Test
    void aNewClaimRestartsTheClockAndTheNotice() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);
        assertNotNull(tracker.pollWarning(2000L, WAN_P95));

        tracker.onClaimSent("market-2", "Kazeron", 3000L);
        assertEquals("market-2", tracker.pendingEntityId());
        assertEquals("Kazeron", tracker.pendingEntityName());
        assertNull(tracker.pollWarning(3500L, WAN_P95));
        assertNotNull(tracker.pollWarning(4000L, WAN_P95), "the second dock gets its own notice");
    }

    @Test
    void aSessionResetDropsAnOutstandingClaim() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "Jangala", 1000L);

        tracker.clear();

        assertNull(tracker.pendingEntityId());
        assertNull(tracker.pollWarning(99_000L, WAN_P95));
    }

    @Test
    void anUnnamedEntityStillGetsAReadableLabel() {
        CoopClaimWaitTracker tracker = new CoopClaimWaitTracker();
        tracker.onClaimSent("market-1", "", 1000L);

        assertEquals("this location", tracker.pendingEntityName());
    }
}
