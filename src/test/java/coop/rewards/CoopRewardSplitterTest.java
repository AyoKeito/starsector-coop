package coop.rewards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 milestone 3: the share policy's arithmetic. The whole contract is "both clients compute
 * the same numbers from the same total, and the parts sum to the whole" — no credits cross the wire,
 * so agreement has to come from the rounding rule alone.
 */
class CoopRewardSplitterTest {

    @Test
    void anEvenTotalSplitsInHalf() {
        CoopRewardSplitter.Split split = CoopRewardSplitter.split(25_000L);

        assertEquals(12_500L, split.localShare());
        assertEquals(12_500L, split.remoteShare());
        assertEquals(25_000L, split.total());
    }

    @Test
    void anOddTotalStillSumsExactlyToTheTotal() {
        CoopRewardSplitter.Split split = CoopRewardSplitter.split(25_001L);

        assertEquals(12_500L, split.localShare());
        assertEquals(12_501L, split.remoteShare());
        assertEquals(25_001L, split.total());
    }

    /** A losing colony month costs each player about half, not one player all of it. */
    @Test
    void aNegativeTotalSplitsSymmetrically() {
        CoopRewardSplitter.Split loss = CoopRewardSplitter.split(-25_000L);

        assertEquals(-12_500L, loss.localShare());
        assertEquals(-12_500L, loss.remoteShare());
        assertEquals(-25_000L, loss.total());
    }

    @Test
    void anOddNegativeTotalTruncatesTowardZeroAndStillSums() {
        CoopRewardSplitter.Split loss = CoopRewardSplitter.split(-25_001L);

        assertEquals(-12_500L, loss.localShare());
        assertEquals(-12_501L, loss.remoteShare());
        assertEquals(-25_001L, loss.total());
    }

    @Test
    void zeroAndOneAreHandledWithoutSurprises() {
        assertTrue(CoopRewardSplitter.split(0L).isZero());
        assertEquals(0L, CoopRewardSplitter.split(1L).localShare());
        assertEquals(1L, CoopRewardSplitter.split(1L).remoteShare());
        assertEquals(0L, CoopRewardSplitter.split(-1L).localShare());
        assertEquals(-1L, CoopRewardSplitter.split(-1L).remoteShare());
    }

    /** Both clients round the same float the same way, so their deductions match to the credit. */
    @Test
    void creditTotalsAreRoundedOnceBeforeSplitting() {
        assertEquals(6_250L, CoopRewardSplitter.splitCredits(12_500.4f).localShare());
        assertEquals(6_250L, CoopRewardSplitter.splitCredits(12_500.6f).localShare());
        assertEquals(12_501L, CoopRewardSplitter.splitCredits(12_500.6f).total());
    }

    /** The parts always sum to the whole, whatever the total. */
    @Test
    void theSplitIsLosslessAcrossAWideRange() {
        for (long total = -5_000L; total <= 5_000L; total++) {
            assertEquals(total, CoopRewardSplitter.split(total).total(), "total " + total);
        }
    }

    @Test
    void theExplicitEvenPolicyMatchesTheDefault() {
        assertEquals(CoopRewardSplitter.split(9_999L),
                CoopRewardSplitter.split(9_999L, CoopRewardSplitter.Policy.EVEN));
    }
}
