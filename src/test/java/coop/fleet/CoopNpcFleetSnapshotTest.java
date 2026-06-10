package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopNpcFleetSnapshotTest {
    private static CoopFleetSnapshot.Member member(String id, String hull, String variant) {
        return new CoopFleetSnapshot.Member(id, hull, variant, "Ship " + id, "Captain " + id, 0.7f, 0.9f);
    }

    @Test
    void encodeDecodeRoundTripsHeaderAndMembers() {
        CoopNpcFleetSnapshot snapshot = CoopNpcFleetSnapshot.create(
                "fleet-7", "pirates", "Pirate Raiders", "corvus", 12.5f, -3.25f, 0.5f, -1.5f, false,
                180.5f, 95.5f, "INTERCEPT player", List.of(member("m1", "wolf", "wolf_Assault"),
                        member("m2", "lasher", "lasher_CS")));

        CoopNpcFleetSnapshot decoded = CoopNpcFleetSnapshot.decode(snapshot.encode());

        assertEquals(snapshot, decoded);
    }

    @Test
    void encodeDecodePreservesDelimitersAndNewlinesInText() {
        CoopFleetSnapshot.Member tricky = new CoopFleetSnapshot.Member(
                "m|1", "wolf", "wolf_Assault", "ISS Pipe|Wolf", "Line1\nLine2", 0.5f, 0.5f);
        CoopNpcFleetSnapshot snapshot = CoopNpcFleetSnapshot.create(
                "fl|eet\n7", "pi|rates", "Na\nme|Break", "loc\nid", 0f, 0f, 0f, 0f, true,
                250f, 130f, "go to|then\nwait", List.of(tricky));

        CoopNpcFleetSnapshot decoded = CoopNpcFleetSnapshot.decode(snapshot.encode());

        assertEquals(snapshot, decoded);
        assertEquals("ISS Pipe|Wolf", decoded.members().get(0).shipName());
        assertEquals("Line1\nLine2", decoded.members().get(0).captainName());
        assertEquals("Na\nme|Break", decoded.name());
    }

    @Test
    void createReusesPhase8FleetHash() {
        List<CoopFleetSnapshot.Member> members = List.of(member("m1", "wolf", "wolf_Assault"));
        CoopNpcFleetSnapshot snapshot = CoopNpcFleetSnapshot.create(
                "fleet-1", "hegemony", "Patrol", "corvus", 0f, 0f, 0f, 0f, true, 120f, 70f, "", members);

        assertEquals(CoopFleetSnapshot.computeFleetHash(members), snapshot.fleetHash());
    }

    @Test
    void emptyFleetRoundTrips() {
        CoopNpcFleetSnapshot snapshot = CoopNpcFleetSnapshot.create(
                "fleet-empty", "independent", "Trader", "hyperspace", 1f, 2f, 3f, 4f, true, 60f, 40f, "", List.of());

        assertEquals(snapshot, CoopNpcFleetSnapshot.decode(snapshot.encode()));
    }
}
