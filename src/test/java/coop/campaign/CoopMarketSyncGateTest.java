package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMarketSyncGateTest {

    @Test
    void aFreshGateHoldsNothing() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();

        assertNull(gate.pendingMarketId());
        assertFalse(gate.isBlocking(0L));
        assertFalse(gate.pollTimedOut(999_999L));
        assertFalse(gate.pollAnnounce(0L));
    }

    @Test
    void anArmedGateBlocksUntilTheSnapshotArrives() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertTrue(gate.isBlocking(1000L));
        assertTrue(gate.isBlocking(1500L), "a 400-600 ms WAN reply is exactly the window this covers");

        assertTrue(gate.onResolved("sindria"));
        assertFalse(gate.isBlocking(1500L));
        assertNull(gate.pendingMarketId());
    }

    @Test
    void aSnapshotForADifferentMarketDoesNotReleaseTheGate() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertFalse(gate.onResolved("jangala"));
        assertTrue(gate.isBlocking(1200L));
    }

    @Test
    void theGateAlwaysTimesOutSoNoOneIsLockedOutOfAShop() {
        // The host has no counterpart market for a procgen id and answers nothing at all. The player
        // must still get into the trade screens.
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("derelict-7", 1000L);

        assertFalse(gate.pollTimedOut(1000L + CoopMarketSyncGate.TIMEOUT_MILLIS - 1));
        assertTrue(gate.isBlocking(1000L + CoopMarketSyncGate.TIMEOUT_MILLIS - 1));

        assertTrue(gate.pollTimedOut(1000L + CoopMarketSyncGate.TIMEOUT_MILLIS));
        assertFalse(gate.isBlocking(1000L + CoopMarketSyncGate.TIMEOUT_MILLIS));
        assertFalse(gate.pollTimedOut(9_000_000L), "the timeout releases once, not every frame");
    }

    @Test
    void aRepeatedOpenReportForOneDockDoesNotExtendTheLockout() {
        // Vanilla reports a market open more than once per dock (the cargo-updated variant re-fires),
        // and restarting the clock there would push the timeout past its budget.
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);
        gate.onOpenRequested("sindria", 4000L);

        assertTrue(gate.pollTimedOut(1000L + CoopMarketSyncGate.TIMEOUT_MILLIS));
    }

    @Test
    void theSyncingNoticeIsPrintedOncePerArming() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertTrue(gate.pollAnnounce(1000L));
        assertTrue(gate.announced());
        assertFalse(gate.pollAnnounce(1001L), "the per-frame tick must not repeat the line");

        gate.onResolved("sindria");
        gate.onOpenRequested("jangala", 20_000L);
        assertTrue(gate.pollAnnounce(20_000L), "a second dock gets its own line");
    }

    @Test
    void aSnapshotThatBeatsTheFirstTickLeavesNoStaleNotice() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);
        gate.onResolved("sindria");

        assertFalse(gate.pollAnnounce(1001L));
        assertFalse(gate.announced());
    }

    // ---- Phase 32: one open, several submarkets ---------------------------------------------------

    @Test
    void theGateHoldsUntilEverySubmarketOfTheOpenHasApplied() {
        // Releasing on the first snapshot would put the player in a trade screen whose black market
        // and locker are still their own engine's roll.
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertFalse(gate.onResolved("sindria", "open_market", 3));
        assertTrue(gate.isBlocking(1100L));
        assertFalse(gate.onResolved("sindria", "black_market", 3));
        assertTrue(gate.isBlocking(1200L));

        assertTrue(gate.onResolved("sindria", "storage", 3), "the last one releases it");
        assertFalse(gate.isBlocking(1300L));
        assertNull(gate.pendingMarketId());
    }

    @Test
    void aDuplicateSubmarketSnapshotDoesNotCountTwice() {
        // A resend of the same submarket is not the other one arriving.
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertFalse(gate.onResolved("sindria", "open_market", 2));
        assertFalse(gate.onResolved("sindria", "open_market", 2));
        assertEquals(1, gate.appliedSubmarketCount());
        assertTrue(gate.isBlocking(1100L));

        assertTrue(gate.onResolved("sindria", "storage", 2));
    }

    @Test
    void aSingleSubmarketMarketReleasesOnItsOneSnapshot() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertTrue(gate.onResolved("sindria", "open_market", 1));
        assertNull(gate.pendingMarketId());
    }

    @Test
    void aNonsenseCountStillReleasesOnTheFirstSnapshot() {
        // A count of zero or less cannot be counted down to; reading it as "this is the whole
        // answer" opens the shop, which beats wedging it shut until the timeout.
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertTrue(gate.onResolved("sindria", "open_market", 0));
    }

    @Test
    void aSnapshotForAnotherMarketNeverCountsTowardsThePendingOne() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertFalse(gate.onResolved("jangala", "open_market", 1));
        assertEquals(0, gate.appliedSubmarketCount());
        assertTrue(gate.isBlocking(1100L));
    }

    @Test
    void aSecondDockStartsItsOwnCount() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);
        gate.onResolved("sindria", "open_market", 2);

        gate.onOpenRequested("jangala", 20_000L);

        assertEquals(0, gate.appliedSubmarketCount(),
                "the previous dock's applied set must not carry over and release this one early");
        assertFalse(gate.onResolved("jangala", "open_market", 2));
    }

    @Test
    void aBlankSubmarketIdIsNotCounted() {
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);

        assertFalse(gate.onResolved("sindria", "", 1));
        assertFalse(gate.onResolved("sindria", null, 1));
        assertEquals(0, gate.appliedSubmarketCount());
    }

    @Test
    void theDialogClosingStillDropsTheWholeGate() {
        // The one-argument overload is "nothing more is coming", not "one submarket applied".
        CoopMarketSyncGate gate = new CoopMarketSyncGate();
        gate.onOpenRequested("sindria", 1000L);
        gate.onResolved("sindria", "open_market", 3);

        assertTrue(gate.onResolved("sindria"));
        assertNull(gate.pendingMarketId());
    }

    @Test
    void theTradeOptionIdsCoverEveryVanillaRouteToASubmarketScreen() {
        // From starsector-core/data/campaign/rules.csv. Refit is on the list because the refit screen
        // buys weapons and wings out of the same open-submarket cargo a snapshot replaces.
        assertTrue(CoopMarketSyncGate.TRADE_OPTION_IDS.containsAll(java.util.List.of(
                "marketOpenCoreUI", "marketOpenCargo", "marketOpenFleet", "marketOpenRefit",
                "marketTradeCargo", "marketTradeShips", "marketRefit")));
        assertEquals(7, CoopMarketSyncGate.TRADE_OPTION_IDS.size());
    }
}
