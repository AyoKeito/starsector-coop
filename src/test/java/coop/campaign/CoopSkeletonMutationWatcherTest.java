package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSkeletonMutationWatcherTest {

    // ---- Objective ownership poll --------------------------------------------------------------

    @Test
    void firstObjectivePollSeedsSilently() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();

        assertTrue(watcher.diffObjectiveOwners(owners("relay-1", "hegemony", "buoy-1", "tritachyon"))
                .isEmpty(), "the two clients start from the same skeleton; seeding must be silent");
        assertEquals("hegemony", watcher.objectiveOwner("relay-1"));
    }

    @Test
    void objectiveFlipIsReportedOnceAndOnlyForTheChangedEntity() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffObjectiveOwners(owners("relay-1", "hegemony", "buoy-1", "tritachyon"));

        List<CoopSkeletonMutationWatcher.Flip> flips =
                watcher.diffObjectiveOwners(owners("relay-1", "pirates", "buoy-1", "tritachyon"));

        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("relay-1", "pirates")), flips);
        // Unchanged on the next poll.
        assertTrue(watcher.diffObjectiveOwners(owners("relay-1", "pirates", "buoy-1", "tritachyon"))
                .isEmpty());
    }

    @Test
    void objectiveFlippingBackIsReportedAgain() {
        // The war sim swings ownership back and forth; every leg has to reach the guest.
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffObjectiveOwners(owners("relay-1", "hegemony"));

        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("relay-1", "pirates")),
                watcher.diffObjectiveOwners(owners("relay-1", "pirates")));
        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("relay-1", "hegemony")),
                watcher.diffObjectiveOwners(owners("relay-1", "hegemony")));
        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("relay-1", "pirates")),
                watcher.diffObjectiveOwners(owners("relay-1", "pirates")));
    }

    @Test
    void objectiveAppearingAfterSeedingIsRecordedButNotReported() {
        // A war-sim-built objective is a brand-new entity whose engine id is minted per client, so
        // it could never resolve on the other side; reporting it would only produce a warn there.
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffObjectiveOwners(owners("relay-1", "hegemony"));

        assertTrue(watcher.diffObjectiveOwners(owners("relay-1", "hegemony", "new-1", "pirates"))
                .isEmpty());
        assertEquals("pirates", watcher.objectiveOwner("new-1"));
    }

    @Test
    void objectiveDisappearingIsNotReportedAndLeavesTheBaseline() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffObjectiveOwners(owners("relay-1", "hegemony", "buoy-1", "tritachyon"));

        assertTrue(watcher.diffObjectiveOwners(owners("relay-1", "hegemony")).isEmpty());
        assertEquals(null, watcher.objectiveOwner("buoy-1"));
    }

    @Test
    void clearForcesTheNextPollToReseedSilently() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffObjectiveOwners(owners("relay-1", "hegemony"));
        watcher.clear();

        assertTrue(watcher.diffObjectiveOwners(owners("relay-1", "pirates")).isEmpty());
    }

    // ---- Gate poll -----------------------------------------------------------------------------

    @Test
    void gateSeedingReportsOnlyGatesAlreadyInANonDefaultState() {
        // A gate scanned before the session began (Galatia, via the academy questline) is exactly
        // the state a joining guest is missing, so the seeding pass must not swallow it.
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();

        Map<String, String> current = new LinkedHashMap<>();
        current.put("gate-untouched", CoopSkeletonMutationWatcher.encodeGateState(false, false, false));
        current.put("gate-galatia", CoopSkeletonMutationWatcher.encodeGateState(true, false, false));

        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("gate-galatia",
                        CoopSkeletonMutationWatcher.encodeGateState(true, false, false))),
                watcher.diffGateStates(current));
    }

    @Test
    void gateActivationArrivesInTwoStepsAndBothAreReported() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffGateStates(gates("gate-1", false, false, false));

        // Step one: the host scans the gate.
        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("gate-1",
                        CoopSkeletonMutationWatcher.encodeGateState(true, false, false))),
                watcher.diffGateStates(gates("gate-1", true, false, false)));

        // Step two, possibly cycles later: the host integrates a Janus device.
        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("gate-1",
                        CoopSkeletonMutationWatcher.encodeGateState(true, true, true))),
                watcher.diffGateStates(gates("gate-1", true, true, true)));

        assertTrue(watcher.diffGateStates(gates("gate-1", true, true, true)).isEmpty());
    }

    // ---- Survey / ruins poll -------------------------------------------------------------------

    @Test
    void firstSurveyPollSeedsSilently() {
        // A fresh sector is hundreds of planets at whatever level worldgen gave both clients; the
        // seeding pass must not put any of that on the wire.
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();

        assertTrue(watcher.diffSurveyLevels(levels("planet-1", "NONE", "planet-2", "FULL")).isEmpty());
        assertEquals("FULL", watcher.surveyLevel("planet-2"));
    }

    @Test
    void everySurveyStepIsReportedOnceAndOnlyForTheChangedPlanet() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffSurveyLevels(levels("planet-1", "NONE", "planet-2", "NONE"));

        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("planet-1", "SEEN")),
                watcher.diffSurveyLevels(levels("planet-1", "SEEN", "planet-2", "NONE")));
        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("planet-1", "PRELIMINARY")),
                watcher.diffSurveyLevels(levels("planet-1", "PRELIMINARY", "planet-2", "NONE")));
        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("planet-1", "FULL")),
                watcher.diffSurveyLevels(levels("planet-1", "FULL", "planet-2", "NONE")));
        assertTrue(watcher.diffSurveyLevels(levels("planet-1", "FULL", "planet-2", "NONE")).isEmpty());
    }

    @Test
    void ruinsExplorationIsReportedBecauseUnexploredPlanetsAreSeededToo() {
        // The collector feeds "false" for every ruins-bearing planet, which is what puts the entry in
        // the baseline; a map holding only the explored ones would never produce a flip at all.
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        assertTrue(watcher.diffRuinsExplored(levels("planet-1", "false")).isEmpty());

        assertEquals(List.of(new CoopSkeletonMutationWatcher.Flip("planet-1", "true")),
                watcher.diffRuinsExplored(levels("planet-1", "true")));
        assertTrue(watcher.diffRuinsExplored(levels("planet-1", "true")).isEmpty());
        assertEquals("true", watcher.ruinsExplored("planet-1"));
    }

    @Test
    void clearForcesSurveyAndRuinsToReseedSilentlyToo() {
        CoopSkeletonMutationWatcher watcher = new CoopSkeletonMutationWatcher();
        watcher.diffSurveyLevels(levels("planet-1", "NONE"));
        watcher.diffRuinsExplored(levels("planet-1", "false"));
        watcher.clear();

        assertTrue(watcher.diffSurveyLevels(levels("planet-1", "FULL")).isEmpty());
        assertTrue(watcher.diffRuinsExplored(levels("planet-1", "true")).isEmpty());
        assertEquals(null, watcher.surveyLevel("planet-2"));
    }

    // ---- Payload encodings ---------------------------------------------------------------------

    @Test
    void gateStateRoundTrips() {
        CoopSkeletonMutationWatcher.GateState decoded = CoopSkeletonMutationWatcher.decodeGateState(
                CoopSkeletonMutationWatcher.encodeGateState(true, false, true));

        assertEquals(new CoopSkeletonMutationWatcher.GateState(true, false, true), decoded);
    }

    @Test
    void gateStateDecodesDefensively() {
        assertEquals(new CoopSkeletonMutationWatcher.GateState(false, false, false),
                CoopSkeletonMutationWatcher.decodeGateState(""));
        assertEquals(new CoopSkeletonMutationWatcher.GateState(false, false, false),
                CoopSkeletonMutationWatcher.decodeGateState(null));
        assertEquals(new CoopSkeletonMutationWatcher.GateState(true, false, false),
                CoopSkeletonMutationWatcher.decodeGateState("true"));
    }

    @Test
    void gatePayloadCarriesNoJsonArray() {
        // The envelope parser is flat: a payload that is anything but a single string breaks it.
        String payload = CoopSkeletonMutationWatcher.encodeGateState(true, true, true);
        assertFalse(payload.contains("["));
        assertFalse(payload.contains("]"));
    }

    @Test
    void decivPayloadRoundTrips() {
        assertTrue(CoopSkeletonMutationWatcher.decodeDecivFullDestroy(
                CoopSkeletonMutationWatcher.encodeDeciv(true)));
        assertFalse(CoopSkeletonMutationWatcher.decodeDecivFullDestroy(
                CoopSkeletonMutationWatcher.encodeDeciv(false)));
        assertFalse(CoopSkeletonMutationWatcher.decodeDecivFullDestroy(null));
        assertFalse(CoopSkeletonMutationWatcher.decodeDecivFullDestroy(""));
    }

    // ---- Guest-apply decisions -----------------------------------------------------------------

    @Test
    void decivAppliesOnceThenSkips() {
        assertEquals(CoopSkeletonMutationWatcher.DecivDecision.DECIVILIZE,
                CoopSkeletonMutationWatcher.decideDeciv(true, false, true));
        // Re-apply: vanilla's decivilize() removes the market from the economy, so the second pass
        // cannot find it. The condition check covers the fullDestroy=false-with-market-kept path.
        assertEquals(CoopSkeletonMutationWatcher.DecivDecision.SKIP_UNKNOWN_MARKET,
                CoopSkeletonMutationWatcher.decideDeciv(false, false, true));
        assertEquals(CoopSkeletonMutationWatcher.DecivDecision.SKIP_ALREADY_DECIVILIZED,
                CoopSkeletonMutationWatcher.decideDeciv(true, true, true));
        assertEquals(CoopSkeletonMutationWatcher.DecivDecision.SKIP_NO_PRIMARY_ENTITY,
                CoopSkeletonMutationWatcher.decideDeciv(true, false, false));
    }

    @Test
    void objectiveFactionIsSetOnlyWhenItActuallyChanges() {
        assertTrue(CoopSkeletonMutationWatcher.shouldSetObjectiveFaction("hegemony", "pirates"));
        assertTrue(CoopSkeletonMutationWatcher.shouldSetObjectiveFaction(null, "pirates"));
        // Idempotent re-apply of the host's echo.
        assertFalse(CoopSkeletonMutationWatcher.shouldSetObjectiveFaction("pirates", "pirates"));
        assertFalse(CoopSkeletonMutationWatcher.shouldSetObjectiveFaction("pirates", " pirates "));
        assertFalse(CoopSkeletonMutationWatcher.shouldSetObjectiveFaction("pirates", ""));
        assertFalse(CoopSkeletonMutationWatcher.shouldSetObjectiveFaction("pirates", null));
    }

    @Test
    void gateApplyWritesOnlyTheMissingFlagsAndNeverUnsets() {
        CoopSkeletonMutationWatcher.GateState desired =
                new CoopSkeletonMutationWatcher.GateState(true, true, true);

        assertEquals(new CoopSkeletonMutationWatcher.GateApply(true, true, true),
                CoopSkeletonMutationWatcher.decideGate(desired, false, false, false));
        // Idempotent re-apply: everything already set.
        assertTrue(CoopSkeletonMutationWatcher.decideGate(desired, true, true, true).isNoOp());
        // Partially applied (the guest already carries its own Janus device).
        assertEquals(new CoopSkeletonMutationWatcher.GateApply(false, false, true),
                CoopSkeletonMutationWatcher.decideGate(desired, true, true, false));
        // A host report with the flags down never clears flags the guest already has.
        assertTrue(CoopSkeletonMutationWatcher.decideGate(
                new CoopSkeletonMutationWatcher.GateState(false, false, false), true, true, true)
                .isNoOp());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static Map<String, String> owners(String... idThenFaction) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < idThenFaction.length; i += 2) {
            map.put(idThenFaction[i], idThenFaction[i + 1]);
        }
        return map;
    }

    /** {@code id, value} pairs — survey levels or {@code $ruinsExplored} booleans. */
    private static Map<String, String> levels(String... idThenValue) {
        return owners(idThenValue);
    }

    private static Map<String, String> gates(String id, boolean scanned, boolean gatesActive,
                                             boolean canUseGates) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(id, CoopSkeletonMutationWatcher.encodeGateState(scanned, gatesActive, canUseGates));
        return map;
    }
}
