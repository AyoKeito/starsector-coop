package coop.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopAllyLossApplierTest {

    /** A fleet as a map of id to [hull, cr], with every write recorded. */
    private static final class FakeOps implements CoopAllyLossApplier.PlayerFleetOps {
        final Map<String, float[]> ships = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean refuseRemoves;
        int finished;

        FakeOps ship(String id, float hull, float cr) {
            ships.put(id, new float[] {hull, cr});
            return this;
        }

        @Override
        public List<String> memberIds() {
            return new ArrayList<>(ships.keySet());
        }

        @Override
        public String describe(String memberId) {
            return "Wolf " + memberId;
        }

        @Override
        public float hullFraction(String memberId) {
            return ships.get(memberId)[0];
        }

        @Override
        public float cr(String memberId) {
            return ships.get(memberId)[1];
        }

        @Override
        public boolean remove(String memberId) {
            if (refuseRemoves) {
                return false;
            }
            writes.add("remove " + memberId);
            return ships.remove(memberId) != null;
        }

        @Override
        public boolean setHullFraction(String memberId, float value) {
            writes.add("hull " + memberId + " " + value);
            ships.get(memberId)[0] = value;
            return true;
        }

        @Override
        public boolean setCr(String memberId, float value) {
            writes.add("cr " + memberId + " " + value);
            ships.get(memberId)[1] = value;
            return true;
        }

        @Override
        public void finish() {
            finished++;
        }
    }

    private static CoopAllyBattleOutcome outcome(List<String> destroyed,
                                                 CoopAllyBattleOutcome.Survivor... survivors) {
        return new CoopAllyBattleOutcome("owner", destroyed, List.of(survivors));
    }

    @Test
    void destroyedShipsAreRemovedAndSurvivorsGetTheReportedNumbers() {
        FakeOps ops = new FakeOps().ship("a", 1f, 0.7f).ship("b", 1f, 0.7f).ship("c", 1f, 0.7f);
        CoopAllyLossApplier.Report report = CoopAllyLossApplier.apply(
                outcome(List.of("b"), new CoopAllyBattleOutcome.Survivor("a", 0.42f, 0.31f),
                        new CoopAllyBattleOutcome.Survivor("c", 1f, 0.7f)),
                ops);

        assertEquals(List.of("Wolf b"), report.removed());
        assertEquals(List.of("Wolf a"), report.damaged(), "c is untouched and not reported as damaged");
        assertTrue(report.unknownIds().isEmpty());
        assertEquals(0, report.failedWrites());
        assertEquals(List.of("a", "c"), ops.memberIds());
        assertEquals(0.42f, ops.hullFraction("a"), 1e-6);
        assertEquals(0.31f, ops.cr("a"), 1e-6);
        assertEquals(1, ops.finished);
    }

    @Test
    void aSecondApplicationOfTheSameOutcomeChangesNothing() {
        FakeOps ops = new FakeOps().ship("a", 1f, 0.7f).ship("b", 1f, 0.7f);
        CoopAllyBattleOutcome once = outcome(List.of("b"),
                new CoopAllyBattleOutcome.Survivor("a", 0.5f, 0.4f));
        CoopAllyLossApplier.apply(once, ops);
        ops.writes.clear();

        CoopAllyLossApplier.Report again = CoopAllyLossApplier.apply(once, ops);

        assertFalse(again.changedAnything());
        assertEquals(List.of("b"), again.unknownIds(), "the destroyed ship is already gone");
        assertEquals(List.of("hull a 0.5", "cr a 0.4"), ops.writes,
                "survivor values are rewritten to the same numbers, which is harmless");
        assertEquals(2, ops.finished);
    }

    @Test
    void idsTheFleetDoesNotHoldAreSkippedNeverMatchedByPosition() {
        FakeOps ops = new FakeOps().ship("a", 1f, 0.7f);
        CoopAllyLossApplier.Report report = CoopAllyLossApplier.apply(
                outcome(List.of("ghost"), new CoopAllyBattleOutcome.Survivor("phantom", 0.1f, 0.1f)),
                ops);

        assertEquals(List.of("ghost", "phantom"), report.unknownIds());
        assertFalse(report.changedAnything());
        assertEquals(List.of("a"), ops.memberIds());
        assertEquals(1f, ops.hullFraction("a"), 1e-6, "the one real ship was not touched");
        assertTrue(ops.writes.isEmpty());
    }

    @Test
    void aShipListedAsBothDestroyedAndSurvivingIsDestroyed() {
        FakeOps ops = new FakeOps().ship("a", 1f, 0.7f);
        CoopAllyLossApplier.Report report = CoopAllyLossApplier.apply(
                outcome(List.of("a"), new CoopAllyBattleOutcome.Survivor("a", 0.9f, 0.6f)), ops);

        assertEquals(List.of("Wolf a"), report.removed());
        assertTrue(ops.memberIds().isEmpty());
        assertEquals(List.of("remove a"), ops.writes, "no survivor write follows the removal");
    }

    @Test
    void duplicateIdsInTheOutcomeAreAppliedOnce() {
        FakeOps ops = new FakeOps().ship("a", 1f, 0.7f).ship("b", 1f, 0.7f);
        CoopAllyLossApplier.Report report = CoopAllyLossApplier.apply(
                outcome(List.of("b", "b"), new CoopAllyBattleOutcome.Survivor("a", 0.5f, 0.5f),
                        new CoopAllyBattleOutcome.Survivor("a", 0.9f, 0.9f)),
                ops);

        assertEquals(List.of("Wolf b"), report.removed());
        assertTrue(report.unknownIds().isEmpty(), "the repeat of b is a duplicate, not an unknown id");
        assertEquals(0.5f, ops.hullFraction("a"), 1e-6, "the first survivor entry wins");
    }

    @Test
    void aRefusedRemoveIsCountedAndTheShipStays() {
        FakeOps ops = new FakeOps().ship("a", 1f, 0.7f);
        ops.refuseRemoves = true;
        CoopAllyLossApplier.Report report = CoopAllyLossApplier.apply(outcome(List.of("a")), ops);

        assertEquals(1, report.failedWrites());
        assertTrue(report.removed().isEmpty());
        assertEquals(List.of("a"), ops.memberIds());
    }

    @Test
    void valuesThatWentUpAreWrittenButNotCalledDamage() {
        FakeOps ops = new FakeOps().ship("a", 0.5f, 0.3f);
        CoopAllyLossApplier.Report report = CoopAllyLossApplier.apply(
                outcome(List.of(), new CoopAllyBattleOutcome.Survivor("a", 0.6f, 0.35f)), ops);

        assertTrue(report.damaged().isEmpty());
        assertEquals(0.6f, ops.hullFraction("a"), 1e-6);
    }

    @Test
    void theOutcomeClampsItsNumbersAndDropsBlankIds() {
        CoopAllyBattleOutcome outcome = new CoopAllyBattleOutcome(" owner ",
                List.of("", " a ", "  "),
                List.of(new CoopAllyBattleOutcome.Survivor("b", 1.7f, Float.NaN),
                        new CoopAllyBattleOutcome.Survivor("c", -0.2f, 0.4f)));

        assertEquals("owner", outcome.ownerPlayerId());
        assertEquals(List.of("a"), outcome.destroyedMemberIds());
        assertEquals(1f, outcome.survivors().get(0).hullFraction(), 1e-6);
        assertEquals(0f, outcome.survivors().get(0).cr(), 1e-6);
        assertEquals(0f, outcome.survivors().get(1).hullFraction(), 1e-6);
        assertFalse(outcome.isEmpty());
        assertTrue(new CoopAllyBattleOutcome("owner", null, null).isEmpty());
    }

    @Test
    void theBannerNamesLossesAndDamageAndCapsLongLists() {
        CoopAllyLossApplier.Report report = new CoopAllyLossApplier.Report(
                List.of("Wolf a"),
                List.of("Lasher b", "Lasher c", "Lasher d", "Lasher e", "Lasher f", "Lasher g"),
                List.of(), 0);

        assertEquals("Your fleet fought alongside Frost Pillai. Lost: Wolf a."
                        + " Damaged: Lasher b, Lasher c, Lasher d, Lasher e and 2 more.",
                CoopAllyLossApplier.banner("Frost Pillai", report));
        assertEquals("Your fleet fought alongside your partner and came through untouched.",
                CoopAllyLossApplier.banner("  ", CoopAllyLossApplier.Report.nothing()));
    }

    @Test
    void nullInputsAreANoOp() {
        assertFalse(CoopAllyLossApplier.apply(null, new FakeOps()).changedAnything());
        assertFalse(CoopAllyLossApplier.apply(outcome(List.of("a")), null).changedAnything());
    }
}
