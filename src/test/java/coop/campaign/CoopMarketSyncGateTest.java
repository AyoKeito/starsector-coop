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
