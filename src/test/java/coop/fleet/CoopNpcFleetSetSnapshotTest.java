package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoopNpcFleetSetSnapshotTest {
    private static CoopNpcFleetSnapshot fleet(String id, String faction, String location, String hullId) {
        return fleet(id, faction, location, hullId, true);
    }

    private static CoopNpcFleetSnapshot fleet(String id, String faction, String location, String hullId,
                                              boolean transponderOn) {
        return CoopNpcFleetSnapshot.create(id, faction, "Name " + id, location, 1f, 2f, 0f, 0f,
                transponderOn, 150f, 90f, "",
                List.of(new CoopFleetSnapshot.Member("m-" + id, hullId, hullId + "_Standard",
                        "Ship", "Cpt", 0.8f, 1.0f)));
    }

    @Test
    void setHashIsIndependentOfFleetOrder() {
        List<CoopNpcFleetSnapshot> a = List.of(
                fleet("f3", "pirates", "corvus", "wolf"),
                fleet("f1", "hegemony", "corvus", "lasher"),
                fleet("f2", "independent", "hyperspace", "kite"));
        List<CoopNpcFleetSnapshot> b = List.of(
                fleet("f2", "independent", "hyperspace", "kite"),
                fleet("f3", "pirates", "corvus", "wolf"),
                fleet("f1", "hegemony", "corvus", "lasher"));

        assertEquals(CoopNpcFleetSetSnapshot.computeSetHash(a), CoopNpcFleetSetSnapshot.computeSetHash(b));
    }

    @Test
    void setHashChangesOnMembership() {
        List<CoopNpcFleetSnapshot> base = List.of(fleet("f1", "hegemony", "corvus", "lasher"));
        String baseHash = CoopNpcFleetSetSnapshot.computeSetHash(base);

        assertNotEquals(baseHash, CoopNpcFleetSetSnapshot.computeSetHash(List.of(
                fleet("f1", "hegemony", "corvus", "lasher"),
                fleet("f2", "pirates", "corvus", "wolf"))));
    }

    @Test
    void setHashChangesOnFleetLocationOrRoster() {
        String baseHash = CoopNpcFleetSetSnapshot.computeSetHash(
                List.of(fleet("f1", "hegemony", "corvus", "lasher")));

        // Same fleet, different system -> the guest must re-apply (re-add in the new location).
        assertNotEquals(baseHash, CoopNpcFleetSetSnapshot.computeSetHash(
                List.of(fleet("f1", "hegemony", "magec", "lasher"))));
        // Same fleet, different roster.
        assertNotEquals(baseHash, CoopNpcFleetSetSnapshot.computeSetHash(
                List.of(fleet("f1", "hegemony", "corvus", "onslaught"))));
    }

    @Test
    void setHashChangesOnTransponderToggle() {
        // The set is the only carrier of transponder state (the 10 Hz motion datagram omits it), and on
        // the guest that flag decides whether a mirror is faction-identified across its whole detection
        // range or only inside 10% of it. If a toggle does not move the hash, the guest never learns.
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(
                        fleet("f1", "hegemony", "corvus", "lasher", true))),
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(
                        fleet("f1", "hegemony", "corvus", "lasher", false))));
    }

    @Test
    void encodeDecodeRoundTripsWholeSet() {
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of(
                fleet("f1", "hegemony", "corvus", "lasher"),
                fleet("f2", "pi|rates", "hyper\nspace", "wolf")));

        CoopNpcFleetSetSnapshot decoded = CoopNpcFleetSetSnapshot.decode(set.encode());

        assertEquals(set, decoded);
        assertEquals(set.setHash(), decoded.setHash());
        assertEquals("pi|rates", decoded.fleets().get(1).factionId());
    }

    @Test
    void emptySetRoundTrips() {
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of());
        assertEquals(set, CoopNpcFleetSetSnapshot.decode(set.encode()));
    }

    @Test
    void motionBatchRoundTrips() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("f1", "corvus", 10.5f, -20.25f, 1.5f, -0.5f, 220.5f, 90f),
                new CoopNpcFleetMotion("f|2", "hyper\nspace", 0f, 0f, 0f, 0f, 0f, 0f));

        List<CoopNpcFleetMotion> decoded = CoopNpcFleetMotion.decodeBatch(
                CoopNpcFleetMotion.encodeBatch(motions));

        assertEquals(motions, decoded);
    }

    @Test
    void emptyMotionBatchRoundTrips() {
        assertEquals(List.of(), CoopNpcFleetMotion.decodeBatch(CoopNpcFleetMotion.encodeBatch(List.of())));
    }
}
