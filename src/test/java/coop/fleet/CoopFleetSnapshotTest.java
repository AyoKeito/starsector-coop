package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoopFleetSnapshotTest {
    private static CoopFleetSnapshot.Member member(String id, String hull, String variant) {
        return new CoopFleetSnapshot.Member(id, hull, variant, "Ship " + id, "Captain " + id, 0.7f, 0.9f);
    }

    @Test
    void fleetHashIsIndependentOfMemberIterationOrder() {
        List<CoopFleetSnapshot.Member> first = List.of(
                member("m3", "onslaught", "onslaught_Standard"),
                member("m1", "wolf", "wolf_Assault"),
                member("m2", "lasher", "lasher_CS"));
        List<CoopFleetSnapshot.Member> second = List.of(
                member("m2", "lasher", "lasher_CS"),
                member("m3", "onslaught", "onslaught_Standard"),
                member("m1", "wolf", "wolf_Assault"));

        assertEquals(
                CoopFleetSnapshot.computeFleetHash(first),
                CoopFleetSnapshot.computeFleetHash(second));
    }

    @Test
    void fleetHashChangesWhenMemberHullOrVariantChanges() {
        List<CoopFleetSnapshot.Member> base = List.of(member("m1", "wolf", "wolf_Assault"));
        String baseHash = CoopFleetSnapshot.computeFleetHash(base);

        assertNotEquals(baseHash,
                CoopFleetSnapshot.computeFleetHash(List.of(member("m1", "lasher", "wolf_Assault"))));
        assertNotEquals(baseHash,
                CoopFleetSnapshot.computeFleetHash(List.of(member("m1", "wolf", "wolf_CS"))));
    }

    @Test
    void fleetHashChangesWhenRoundedCrOrHullFractionChanges() {
        List<CoopFleetSnapshot.Member> base = List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela", 0.70f, 0.90f));
        String baseHash = CoopFleetSnapshot.computeFleetHash(base);

        // Sub-percent drift collapses to the same rounded bucket -> same hash.
        assertEquals(baseHash, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela", 0.704f, 0.902f))));
        // A whole-percent change flips the hash.
        assertNotEquals(baseHash, CoopFleetSnapshot.computeFleetHash(List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela", 0.80f, 0.90f))));
    }

    @Test
    void encodeDecodeRoundTripsHeaderAndMembers() {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "player-1", "Alice", "corvus", 123.5f, -42.25f, 1.5f, -2.5f, "player", true,
                List.of(member("m1", "wolf", "wolf_Assault"), member("m2", "lasher", "lasher_CS")));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decode(snapshot.encode());

        assertEquals(snapshot, decoded);
    }

    @Test
    void encodeDecodePreservesDelimiterAndNewlineCharactersInNames() {
        CoopFleetSnapshot.Member tricky = new CoopFleetSnapshot.Member(
                "m|1", "wolf", "wolf_Assault", "ISS Pipe|Wolf", "Line1\nLine2", 0.5f, 0.5f);
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p|id", "Na|me\nBreak", "loc\nid", 0f, 0f, 0f, 0f, "fac|tion", false, List.of(tricky));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decode(snapshot.encode());

        assertEquals(snapshot, decoded);
        assertEquals("ISS Pipe|Wolf", decoded.members().get(0).shipName());
        assertEquals("Line1\nLine2", decoded.members().get(0).captainName());
    }

    @Test
    void createComputesMatchingFleetHash() {
        List<CoopFleetSnapshot.Member> members = List.of(member("m1", "wolf", "wolf_Assault"));
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(
                "p1", "Alice", "corvus", 0f, 0f, 0f, 0f, "player", true, members);

        assertEquals(CoopFleetSnapshot.computeFleetHash(members), snapshot.fleetHash());
    }
}
