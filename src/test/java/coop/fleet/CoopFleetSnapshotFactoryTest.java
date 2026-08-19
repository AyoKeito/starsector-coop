package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression cover for the 2026-08-19 "guest mirrors wore the wrong roster" investigation: the host's
 * roster capture must degrade one ship at a time, never truncate.
 */
class CoopFleetSnapshotFactoryTest {

    /** A roster where any slot can be made to throw, standing in for an engine {@code FleetMember}. */
    private static final class FakeSource implements CoopFleetSnapshotFactory.MemberSource {
        private final List<String> hullIds;
        private final List<Integer> throwingSlots;
        private final List<Integer> wingSlots;

        FakeSource(List<String> hullIds, List<Integer> throwingSlots, List<Integer> wingSlots) {
            this.hullIds = hullIds;
            this.throwingSlots = throwingSlots;
            this.wingSlots = wingSlots;
        }

        @Override
        public int size() {
            return hullIds.size();
        }

        @Override
        public boolean isFighterWing(int index) {
            return wingSlots.contains(index);
        }

        @Override
        public CoopFleetSnapshot.Member capture(int index) {
            if (throwingSlots.contains(index)) {
                throw new IllegalStateException("ship " + index + " cannot report its hull");
            }
            String hullId = hullIds.get(index);
            return new CoopFleetSnapshot.Member(hullId + index, hullId, hullId + "_Standard",
                    "Ship " + index, "", 1f, 1f);
        }
    }

    private static List<CoopFleetSnapshot.Member> capture(List<String> hullIds,
                                                          List<Integer> throwingSlots,
                                                          List<Integer> wingSlots) {
        List<CoopFleetSnapshot.Member> out = new ArrayList<>();
        CoopFleetSnapshotFactory.captureInto(out, new FakeSource(hullIds, throwingSlots, wingSlots));
        return out;
    }

    @Test
    void oneUnreadableShipCostsOnlyThatShip() {
        // The old single-try-around-the-loop form returned [hound] here and dropped everything after
        // the throw. The guest then latched that truncated roster: its structural hash is stable, so
        // CoopFleetMirror's gate accepts it once and never rebuilds.
        List<CoopFleetSnapshot.Member> members =
                capture(List.of("hound", "cerberus", "mule", "nebula"), List.of(1), List.of());
        assertEquals("hound x1, mule x1, nebula x1", CoopRosterSummary.ofMembers(members));
    }

    @Test
    void aThrowOnTheFirstShipNoLongerEmptiesTheWholeRoster() {
        // This is the case that reached the guest as "roster refreshed to 0 ship(s)".
        List<CoopFleetSnapshot.Member> members =
                capture(List.of("hound", "cerberus", "mule"), List.of(0), List.of());
        assertEquals("cerberus x1, mule x1", CoopRosterSummary.ofMembers(members));
    }

    @Test
    void skippedShipsAreCounted() {
        List<CoopFleetSnapshot.Member> out = new ArrayList<>();
        int skipped = CoopFleetSnapshotFactory.captureInto(out,
                new FakeSource(List.of("hound", "cerberus", "mule"), List.of(0, 2), List.of()));
        assertEquals(2, skipped);
        assertEquals(1, out.size());
    }

    @Test
    void fighterWingsAreStillExcludedAndDoNotCountAsFailures() {
        List<CoopFleetSnapshot.Member> out = new ArrayList<>();
        int skipped = CoopFleetSnapshotFactory.captureInto(out,
                new FakeSource(List.of("hound", "talon_wing", "mule"), List.of(), List.of(1)));
        assertEquals(0, skipped);
        assertEquals("hound x1, mule x1", CoopRosterSummary.ofMembers(out));
    }

    // ---- Resolvable-by-construction ship ids ---------------------------------------------------

    /** Stands in for this install's spec store. */
    private static Predicate<String> installHas(String... ids) {
        Set<String> known = new HashSet<>(Arrays.asList(ids));
        return known::contains;
    }

    @Test
    void anInflatedShipStreamsTheStockVariantItWasAutofitFrom() {
        // The live variant id of an inflated ship is DefaultFleetInflater's
        // createEmptyVariant(fleet.getId() + "_" + memberIndex, ...) — "905d_3" here — which exists
        // only inside the host's engine. The inflater records where it autofit from in
        // getOriginalVariant(); that is the id both installs share.
        assertEquals("falcon_Assault", CoopFleetSnapshotFactory.streamableVariantId(
                "falcon_Assault", "905d_3", "905d_3", installHas("falcon_Assault")));
    }

    @Test
    void aRuntimeVariantIdIsNeverPutOnTheWire() {
        // Nothing resolvable: send "" so the receiver takes the hull path, rather than an id that
        // makes createFleetMember substitute a placeholder ship without complaining.
        assertEquals("", CoopFleetSnapshotFactory.streamableVariantId(
                "", "905d_3", "905d_3", installHas("falcon_Assault")));
    }

    @Test
    void aStockVariantIsStreamedUnchangedWhenThereIsNoOriginal() {
        assertEquals("falcon_Assault", CoopFleetSnapshotFactory.streamableVariantId(
                "", "falcon_Assault", "falcon_Assault", installHas("falcon_Assault")));
    }

    @Test
    void aDHullIsKeptWhenItResolvesAndStrippedWhenItDoesNot() {
        // D hulls are generated at load from the same ship data on both installs, so normally they
        // resolve and the mirror keeps the damaged hull; the strip is the safety net.
        assertEquals("falcon_default_D", CoopFleetSnapshotFactory.streamableHullId(
                "falcon_default_D", installHas("falcon", "falcon_default_D")));
        assertEquals("falcon", CoopFleetSnapshotFactory.streamableHullId(
                "falcon_default_D", installHas("falcon")));
    }

    @Test
    void baseHullIdIsTheInverseOfMiscGetDHullId() {
        assertEquals("falcon", CoopFleetSnapshotFactory.baseHullId("falcon_default_D"));
        assertEquals("falcon", CoopFleetSnapshotFactory.baseHullId("falcon"));
        assertEquals("", CoopFleetSnapshotFactory.baseHullId(null));
    }
}
