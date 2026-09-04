package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20 M4 receive side: recombining a UDP tick with the last TCP roster. The interesting cases
 * are all about the window where the two halves disagree — that window is normal (TCP and UDP race
 * every time a ship changes) and the mirror must keep moving through it without rebuilding from a
 * roster it does not have.
 */
class CoopRosterCacheTest {

    private static CoopFleetSnapshot.Member member(String id, String hull, float cr) {
        return new CoopFleetSnapshot.Member(id, hull, hull + "_Standard", "Ship " + id, "Captain",
                cr, 1f);
    }

    private static CoopFleetSnapshot snapshot(List<CoopFleetSnapshot.Member> members) {
        return CoopFleetSnapshot.create("remote-player", "Bob", "corvus", 10f, 20f, 1f, 2f,
                "player", true, new CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f), members);
    }

    private static CoopFleetSnapshot.Tick tick(CoopFleetSnapshot snapshot) {
        return CoopFleetSnapshot.Tick.of(snapshot);
    }

    @Test
    void aMatchingHashRebuildsTheFullSnapshotFromTheRoster() {
        CoopFleetSnapshot source = snapshot(List.of(member("m1", "wolf", 0.7f), member("m2", "lasher", 0.8f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(source));

        CoopFleetSnapshot composed = cache.compose(tick(source), "fallback-id", "Fallback", 1000L);

        assertEquals("remote-player", composed.playerId());
        assertEquals("Bob", composed.username());
        assertEquals("player", composed.factionId());
        assertEquals(source.fleetHash16(), composed.fleetHash());
        assertEquals(2, composed.members().size());
        assertEquals("wolf", composed.members().get(0).hullId());
        assertEquals(0.7f, composed.members().get(0).cr());
        // Motion and sensors always come from the tick.
        assertEquals(10f, composed.x());
        assertEquals(300f, composed.sensors().sensorProfile());
        assertTrue(composed.transponderOn());
    }

    @Test
    void perShipStateFromTheTickOverwritesTheRostersOwnValues() {
        CoopFleetSnapshot roster = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(roster));

        // Same roster, ship has repaired since: only cr/hull moved, so only the tick changed.
        CoopFleetSnapshot later = snapshot(List.of(member("m1", "wolf", 0.95f)));
        CoopFleetSnapshot composed = cache.compose(tick(later), "fallback-id", "Fallback", 1000L);

        assertEquals(roster.fleetHash16(), later.fleetHash16(), "cr is not structural");
        assertEquals(0.95f, composed.members().get(0).cr());
    }

    /**
     * The reorder defect (2026-09-04). The structural hash sorts its members, so a fleet the player
     * merely drags into a different order hashes identically and no new roster is sent — while the
     * tick's per-ship pairs were emitted in the sender's raw fleet order. Indexed straight into the
     * cached roster they landed on the wrong ships and stayed there for the rest of the session: on
     * this data the mirror showed the wolf at 0.2 CR and the lasher at 0.9. Both halves order by the
     * hash's own canonical order instead, so the pairing survives any reorder.
     */
    @Test
    void perShipStateFollowsTheShipsWhenTheSenderReordersItsFleet() {
        CoopFleetSnapshot source = snapshot(List.of(
                member("m-wolf", "wolf", 0.9f), member("m-lasher", "lasher", 0.2f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(source));

        // Dragged the lasher above the wolf in the fleet screen: same ships, same hash, no roster.
        CoopFleetSnapshot reordered = snapshot(List.of(
                member("m-lasher", "lasher", 0.2f), member("m-wolf", "wolf", 0.9f)));
        assertEquals(source.fleetHash16(), reordered.fleetHash16(), "a reorder is not a new ship set");

        CoopFleetSnapshot composed = cache.compose(tick(reordered), "fallback-id", "Fallback", 1000L);

        assertEquals("wolf", composed.members().get(0).hullId(), "the roster's own order is kept");
        assertEquals(0.9f, composed.members().get(0).cr(), "the wolf keeps its own CR");
        assertEquals(0.2f, composed.members().get(1).cr(), "and the lasher keeps its own");
    }

    @Test
    void aTickForAnUnknownRosterKeepsTheCachedOneAndItsHash() {
        CoopFleetSnapshot cached = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(cached));
        // The peer just gained a ship; its roster has not landed yet.
        CoopFleetSnapshot grown = snapshot(List.of(member("m1", "wolf", 0.7f), member("m2", "hound", 0.6f)));

        CoopFleetSnapshot composed = cache.compose(tick(grown), "fallback-id", "Fallback", 1000L);

        assertEquals(cached.fleetHash16(), composed.fleetHash(),
                "the mirror must not rebuild against a roster it has not got");
        assertEquals(1, composed.members().size());
        assertEquals(0.7f, composed.members().get(0).cr(), "last known state, not indexed by position");
        assertEquals(10f, composed.x(), "the mirror still moves");
    }

    @Test
    void aTickWithNoRosterAtAllComposesAnEmptyMemberList() {
        CoopFleetSnapshot source = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopRosterCache cache = new CoopRosterCache();

        CoopFleetSnapshot composed = cache.compose(tick(source), "fallback-id", "Fallback", 1000L);

        assertNull(cache.current());
        assertEquals(List.of(), composed.members(), "the mirror's empty-roster guard owns this case");
        assertEquals("fallback-id", composed.playerId());
        assertEquals("Fallback", composed.username());
        assertEquals(source.fleetHash16(), composed.fleetHash());
    }

    @Test
    void anArrivingRosterIsCachedAndAppliedByTheNextTick() {
        CoopFleetSnapshot source = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopRosterCache cache = new CoopRosterCache();

        cache.compose(tick(source), "fallback-id", "Fallback", 1000L);
        cache.accept(CoopFleetRoster.of(source));

        assertTrue(cache.matches(source.fleetHash16()));
        CoopFleetSnapshot composed = cache.compose(tick(source), "fallback-id", "Fallback", 1100L);
        assertEquals(1, composed.members().size());
        assertEquals("wolf", composed.members().get(0).hullId());
    }

    @Test
    void aStuckMismatchIsLoggedOnceAfterFiveSeconds() {
        CoopFleetSnapshot cached = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopFleetSnapshot other = snapshot(List.of(member("m9", "hound", 0.6f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(cached));

        cache.compose(tick(other), "id", "Name", 1_000L);
        assertFalse(cache.mismatchLogged(), "the first mismatch is the ordinary TCP/UDP race");
        cache.compose(tick(other), "id", "Name", 4_000L);
        assertFalse(cache.mismatchLogged());
        cache.compose(tick(other), "id", "Name", 6_500L);
        assertTrue(cache.mismatchLogged());
    }

    @Test
    void aResolvedMismatchArmsTheWarningAgainForTheNextOne() {
        CoopFleetSnapshot cached = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopFleetSnapshot other = snapshot(List.of(member("m9", "hound", 0.6f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(cached));

        cache.compose(tick(other), "id", "Name", 1_000L);
        cache.compose(tick(other), "id", "Name", 9_000L);
        assertTrue(cache.mismatchLogged());

        cache.compose(tick(cached), "id", "Name", 9_100L);
        assertFalse(cache.mismatchLogged(), "a matching tick clears the hold");
    }

    @Test
    void resetForgetsTheRosterSoANewSessionCannotInheritIt() {
        CoopFleetSnapshot source = snapshot(List.of(member("m1", "wolf", 0.7f)));
        CoopRosterCache cache = new CoopRosterCache();
        cache.accept(CoopFleetRoster.of(source));

        cache.reset();

        assertNull(cache.current());
        assertFalse(cache.matches(source.fleetHash16()));
        assertEquals(List.of(), cache.compose(tick(source), "id", "Name", 1_000L).members());
    }

    @Test
    void theRosterItselfRoundTripsThroughItsWireForm() {
        CoopFleetSnapshot source = snapshot(List.of(
                new CoopFleetSnapshot.Member("m|1", "wolf", "wolf_Assault", "Fang\nEdge", "Vela",
                        0.7f, 0.5f, "d_dmod", "s_smod", "s_builtin"),
                member("m2", "lasher", 0.8f)));

        CoopFleetRoster roster = CoopFleetRoster.of(source);
        assertEquals(roster, CoopFleetRoster.decode(roster.encode()));
        assertEquals(16, roster.fleetHash16().length());
    }
}
