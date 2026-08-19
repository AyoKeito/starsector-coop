package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
