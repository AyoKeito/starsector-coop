package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phase arithmetic, plus a simulation of {@code CampaignEngine.advance}'s star-system loop that
 * proves the dodge actually keeps the engine off the driven system — and keeps every other system's
 * advance budget intact.
 */
class CoopSystemPhaseSlotsTest {

    private static final int SYSTEM_COUNT = 97;

    // ---- Slot arithmetic --------------------------------------------------------------------------

    @Test
    void hyperspaceOwnsSlotZeroSoTheFirstStarSystemIsSlotOne() {
        assertEquals(1, CoopSystemPhaseSlots.slotOf(0));
        assertEquals(60, CoopSystemPhaseSlots.slotOf(59));
    }

    @Test
    void firesOnMatchesTheEngineCondition() {
        // CampaignEngine.advance:1064 -- frame % 60 == n32 % 60, with n32 = index + 1.
        for (int index = 0; index < 130; index++) {
            for (long frame = 0; frame < 130; frame++) {
                boolean expected = frame % 60 == (index + 1) % 60;
                assertEquals(expected, CoopSystemPhaseSlots.firesOn(index, frame),
                        "index=" + index + " frame=" + frame);
            }
        }
    }

    @Test
    void indicesAStrideApartShareASlot() {
        assertTrue(CoopSystemPhaseSlots.firesOn(0, 1));
        assertTrue(CoopSystemPhaseSlots.firesOn(60, 1));
        assertTrue(CoopSystemPhaseSlots.firesOn(120, 1));
    }

    @Test
    void firesSoonCoversThisFrameAndTheNext() {
        assertTrue(CoopSystemPhaseSlots.firesSoon(0, 1));
        assertTrue(CoopSystemPhaseSlots.firesSoon(0, 0));
        assertFalse(CoopSystemPhaseSlots.firesSoon(0, 2));
    }

    @Test
    void firesOnHandlesTheFrameCounterWrapping() {
        // frame is a long that only ever increases, but the arithmetic must not depend on that.
        assertTrue(CoopSystemPhaseSlots.firesOn(0, -59L));
        assertEquals(CoopSystemPhaseSlots.firesOn(5, 6L), CoopSystemPhaseSlots.firesOn(5, 6L + 600L));
    }

    // ---- Choosing a safe slot ---------------------------------------------------------------------

    @Test
    void pickedSlotIsSafeForThisFrameAndTheNextAndIsNotWhereWeAlreadyAre() {
        for (long frame = 0; frame < 240; frame++) {
            for (int index = 0; index < SYSTEM_COUNT; index += 7) {
                int target = CoopSystemPhaseSlots.pickSafeIndex(index, SYSTEM_COUNT, frame);
                assertTrue(target >= 0, "no safe slot at frame " + frame);
                assertNotEquals(index, target);
                assertFalse(CoopSystemPhaseSlots.firesSoon(target, frame),
                        "picked a slot that fires: frame=" + frame + " target=" + target);
            }
        }
    }

    @Test
    void pickedSlotIsTheOneThatJustFiredSoSwapsStayRare() {
        // At frame 1 the counter has just passed slot 0, i.e. position 59; landing there buys a full
        // stride before the next move. That is what keeps this to about one swap per stride.
        int target = CoopSystemPhaseSlots.pickSafeIndex(0, SYSTEM_COUNT, 1L);
        assertEquals(59, target);
        assertEquals(CoopSystemPhaseSlots.STRIDE - 1,
                CoopSystemPhaseSlots.framesUntilFires(target, 1L));
    }

    @Test
    void headroomCountsFramesUntilTheEngineNextAdvancesTheSlot() {
        assertEquals(0L, CoopSystemPhaseSlots.framesUntilFires(0, 1L));
        assertEquals(1L, CoopSystemPhaseSlots.framesUntilFires(1, 1L));
        assertEquals(CoopSystemPhaseSlots.STRIDE - 1L, CoopSystemPhaseSlots.framesUntilFires(59, 1L));
        for (int index = 0; index < 130; index++) {
            for (long frame = 0; frame < 130; frame++) {
                assertEquals(CoopSystemPhaseSlots.framesUntilFires(index, frame) == 0L,
                        CoopSystemPhaseSlots.firesOn(index, frame));
                assertEquals(CoopSystemPhaseSlots.framesUntilFires(index, frame) <= 1L,
                        CoopSystemPhaseSlots.firesSoon(index, frame));
            }
        }
    }

    @Test
    void aListTooSmallToHoldASafeSlotReportsFailureRatherThanGuessing() {
        assertEquals(-1, CoopSystemPhaseSlots.pickSafeIndex(0, 1, 1L));
        assertEquals(-1, CoopSystemPhaseSlots.pickSafeIndex(0, 0, 0L));
        assertEquals(-1, CoopSystemPhaseSlots.pickSafeIndex(-1, SYSTEM_COUNT, 0L));
        assertEquals(-1, CoopSystemPhaseSlots.pickSafeIndex(SYSTEM_COUNT, SYSTEM_COUNT, 0L));
    }

    @Test
    void twoSystemsAreEnoughWhenNeitherOfTheTwoSlotsIsAboutToFire() {
        // Positions 0 and 1 are slots 1 and 2; at frame 10 neither fires, so a swap is possible.
        assertEquals(1, CoopSystemPhaseSlots.pickSafeIndex(0, 2, 10L));
    }

    // ---- End to end against a simulated engine loop ----------------------------------------------

    @Test
    void theEngineNeverAdvancesTheDrivenSystem() {
        Sim sim = new Sim(SYSTEM_COUNT, 0);
        sim.run(6000);
        assertEquals(0, sim.engineAdvances[sim.driven],
                "the engine advanced the driven system despite the dodge");
    }

    @Test
    void theDodgeWorksFromEveryStartingPosition() {
        for (int start = 0; start < SYSTEM_COUNT; start++) {
            Sim sim = new Sim(SYSTEM_COUNT, start);
            sim.run(600);
            assertEquals(0, sim.engineAdvances[sim.driven], "failed from start position " + start);
        }
    }

    @Test
    void noOtherSystemIsEverStarved() {
        Sim sim = new Sim(SYSTEM_COUNT, 40);
        int strides = 200;
        sim.run(strides * CoopSystemPhaseSlots.STRIDE);
        for (int id = 0; id < SYSTEM_COUNT; id++) {
            if (id == sim.driven) {
                continue;
            }
            // Swapping is position-conserving: every list position still fires once per stride, so a
            // partner can only ever gain a tick, never lose one. Starving a background system would be
            // the damaging direction and must be impossible.
            assertTrue(sim.engineAdvances[id] >= strides,
                    "system " + id + " was starved: " + sim.engineAdvances[id] + " < " + strides);
        }
    }

    @Test
    void theDrivenSystemsForgoneTicksAreTheOnlyOnesRedistributed() {
        Sim sim = new Sim(SYSTEM_COUNT, 40);
        int strides = 200;
        sim.run(strides * CoopSystemPhaseSlots.STRIDE);

        int total = 0;
        int worst = 0;
        for (int id = 0; id < SYSTEM_COUNT; id++) {
            total += sim.engineAdvances[id];
            if (id != sim.driven) {
                worst = Math.max(worst, sim.engineAdvances[id] - strides);
            }
        }
        // Sector-wide the engine still performs exactly one advance per position per stride; the ticks
        // the driven system gives up are handed to whichever system sits on its vacated slot, so the
        // total is conserved and the surplus is bounded by the number of swaps.
        assertEquals(SYSTEM_COUNT * strides, total);
        assertTrue(worst <= sim.swaps, "surplus " + worst + " exceeds swap count " + sim.swaps);
        // And rotating the swap partner keeps any single system's surplus small -- a few percent of
        // its own cadence, on a system nobody is looking at.
        assertTrue(worst <= strides / 10,
                "one system absorbed too much of the surplus: " + worst + " over " + strides);
    }

    @Test
    void swapsAreRareEnoughToBeCheap() {
        Sim sim = new Sim(SYSTEM_COUNT, 12);
        int strides = 100;
        sim.run(strides * CoopSystemPhaseSlots.STRIDE);
        // About one swap per stride -- roughly once a second at 60 fps -- is the design target.
        // Materially more than that means the picked slot is landing near the counter, not away from
        // it, and every extra swap is another perturbed partner.
        assertTrue(sim.swaps <= strides + strides / 10, "too many swaps: " + sim.swaps);
        assertTrue(sim.swaps >= strides - strides / 10, "suspiciously few swaps: " + sim.swaps);
    }

    @Test
    void aPausedGameNeverLetsTheEngineCatchTheDrivenSystem() {
        // While paused CampaignEngine.advance does not increment `frame` (line 977) but still runs the
        // stride check every frame, so the same residue repeats indefinitely.
        Sim sim = new Sim(SYSTEM_COUNT, 0);
        sim.runPaused(600);
        assertEquals(0, sim.engineAdvances[sim.driven]);
    }

    @Test
    void alternatingPauseAndPlayNeverLetsTheEngineCatchTheDrivenSystem() {
        Sim sim = new Sim(SYSTEM_COUNT, 77);
        for (int block = 0; block < 60; block++) {
            sim.run(37);
            sim.runPaused(11);
        }
        assertEquals(0, sim.engineAdvances[sim.driven]);
    }

    /**
     * A faithful replay of the engine's non-current star-system loop plus this mod's end-of-frame
     * tick, so the dodge is tested against the schedule it claims to beat rather than against itself.
     */
    private static final class Sim {
        private final List<Integer> systems = new ArrayList<>();
        private final int driven;
        private final int[] engineAdvances;
        private long frame;
        private int swaps;

        private Sim(int size, int drivenPosition) {
            for (int i = 0; i < size; i++) {
                systems.add(i);
            }
            this.driven = systems.get(drivenPosition);
            this.engineAdvances = new int[size];
            // Takeover: the mod's first tick runs at the end of the current frame, guarding it and the
            // next one, which is the first frame run() will step the engine through.
            tick(frame);
        }

        private void run(int frames) {
            for (int i = 0; i < frames; i++) {
                frame++;              // CampaignEngine.advance:977, unpaused only
                engineLoop();         // lines 1062-1077
                tick(frame);          // our transient script, lines 1099-1109
            }
        }

        private void runPaused(int frames) {
            for (int i = 0; i < frames; i++) {
                engineLoop();
                tick(frame);
            }
        }

        private void engineLoop() {
            for (int p = 0; p < systems.size(); p++) {
                if (CoopSystemPhaseSlots.firesOn(p, frame)) {
                    engineAdvances[systems.get(p)]++;
                }
            }
        }

        private void tick(long atFrame) {
            int index = systems.indexOf(driven);
            if (!CoopSystemPhaseSlots.firesSoon(index, atFrame)) {
                return;
            }
            int target = CoopSystemPhaseSlots.pickSafeIndex(index, systems.size(), atFrame);
            assertTrue(target >= 0);
            Collections.swap(systems, index, target);
            swaps++;
        }
    }
}
