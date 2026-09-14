package coop.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S4-F background sweep: the rotating system cursor and the jump-point-only body filter.
 *
 * <p><b>What this exists for (live session, 2026-09-14).</b> Orbit sync only ever resets the systems
 * the two players are standing in, so every other system keeps the jump-point angles it was
 * generated with — and the fringe jump-point's orbit is drawn non-deterministically, same focus,
 * same radius, same period, different angle. Magec's sat ~30k su apart between the two engines. A
 * guest that jumps into a system the host is not in lands next to its own stale copy of the jump
 * point, then one second later the first snapshot for that system moves the jump point to the host's
 * angle and the guest cannot leave. The sweep covers every system in the sector on a rotation so the
 * reset happens long before anyone flies there.
 *
 * <p>Both halves are pure: the cursor is arithmetic over a list of ids, and the filter is a
 * predicate over entities, so neither needs an engine to pin.
 */
class CoopCampaignReplicatorOrbitSweepTest {

    private static final List<String> SECTOR = List.of("corvus", "magec", "duzahk");

    // --- cursor -------------------------------------------------------------------------------

    @Test
    void oneSystemPerTickInOrderAndAFullPassTakesAsManyTicksAsSystems() {
        List<String> sent = new ArrayList<>();
        int cursor = 0;
        int passes = 0;
        for (int tick = 0; tick < SECTOR.size(); tick++) {
            CoopCampaignReplicator.OrbitSweepStep step =
                    CoopCampaignReplicator.nextOrbitSweepSystem(SECTOR, cursor, Set.of());
            sent.add(step.systemId());
            cursor = step.nextCursor();
            if (step.cycleCompleted()) {
                passes++;
            }
        }

        assertEquals(SECTOR, sent, "every system exactly once, in the sector's own order");
        assertEquals(1, passes, "the pass closes on the last system, not before");
        assertEquals(0, cursor, "and the cursor is back at the start for the next pass");
    }

    @Test
    void theCursorWrapsRatherThanRunningOffTheEnd() {
        CoopCampaignReplicator.OrbitSweepStep step =
                CoopCampaignReplicator.nextOrbitSweepSystem(SECTOR, 2, Set.of());

        assertEquals("duzahk", step.systemId());
        assertEquals(0, step.nextCursor());
        assertTrue(step.cycleCompleted());
    }

    /**
     * A system already snapshot this tick as a player location got the FULL body set, of which this
     * sweep entry would be a subset — sending it twice in one tick is pure waste. The cursor still
     * advances past it, because one advance per tick is what makes a pass take N ticks.
     */
    @Test
    void aSystemAlreadySentThisTickIsSkippedButStillConsumesItsTurn() {
        CoopCampaignReplicator.OrbitSweepStep step =
                CoopCampaignReplicator.nextOrbitSweepSystem(SECTOR, 1, Set.of("magec"));

        assertEquals(-1, step.index(), "nothing to send this tick");
        assertNull(step.systemId());
        assertEquals(2, step.nextCursor(), "and the next tick moves on rather than retrying magec");
        assertFalse(step.cycleCompleted());
    }

    @Test
    void anEmptySectorSendsNothingAndClosesNoPass() {
        CoopCampaignReplicator.OrbitSweepStep step =
                CoopCampaignReplicator.nextOrbitSweepSystem(List.of(), 0, Set.of());

        assertEquals(-1, step.index());
        assertEquals(0, step.nextCursor());
        assertFalse(step.cycleCompleted());

        assertEquals(-1, CoopCampaignReplicator.nextOrbitSweepSystem(null, 3, Set.of()).index());
    }

    /** One system is its own full pass: it is sent every tick and every tick closes the cycle. */
    @Test
    void aSingleSystemSectorSendsThatSystemEveryTick() {
        CoopCampaignReplicator.OrbitSweepStep step =
                CoopCampaignReplicator.nextOrbitSweepSystem(List.of("corvus"), 0, Set.of());

        assertEquals(0, step.index());
        assertEquals("corvus", step.systemId());
        assertEquals(0, step.nextCursor());
        assertTrue(step.cycleCompleted());
    }

    /** A shrinking system list (deciv removal, a mod unloading) must not index off the end. */
    @Test
    void anOutOfRangeCursorRestartsAtTheFirstSystem() {
        assertEquals("corvus", CoopCampaignReplicator.nextOrbitSweepSystem(SECTOR, 99, Set.of()).systemId());
        assertEquals("corvus", CoopCampaignReplicator.nextOrbitSweepSystem(SECTOR, -4, Set.of()).systemId());
    }

    @Test
    void aSystemWithNoIdIsSkippedLikeACoveredOne() {
        List<String> withNull = Arrays.asList("corvus", null, "duzahk");

        CoopCampaignReplicator.OrbitSweepStep step =
                CoopCampaignReplicator.nextOrbitSweepSystem(withNull, 1, Set.of());

        assertEquals(-1, step.index());
        assertEquals(2, step.nextCursor());
    }

    // --- jump-point filter --------------------------------------------------------------------

    @Test
    void onlyOrbitingJumpPointsAreSwept() {
        assertTrue(CoopCampaignReplicator.isJumpPointOrbit(
                entity(JumpPointAPI.class, "19f", true, 14800f, Set.of())),
                "the fringe jump-point is the whole reason for the sweep");
        assertTrue(CoopCampaignReplicator.isJumpPointOrbit(
                entity(SectorEntityToken.class, "scripted_jump", true, 3000f, Set.of("jump_point"))),
                "a tagged entity that is not a JumpPointAPI still counts");

        assertFalse(CoopCampaignReplicator.isJumpPointOrbit(
                entity(PlanetAPI.class, "jangala", true, 5000f, Set.of())),
                "planets are deterministic and the player-location snapshots already cover them");
        assertFalse(CoopCampaignReplicator.isJumpPointOrbit(
                entity(JumpPointAPI.class, "fixed_jump", false, 0f, Set.of())),
                "a jump point with no orbit has no angle to reset");
        assertFalse(CoopCampaignReplicator.isJumpPointOrbit(
                entity(JumpPointAPI.class, "zero_radius", true, 0f, Set.of())),
                "radius 0 is the engine's 'not really orbiting'");
        assertFalse(CoopCampaignReplicator.isJumpPointOrbit(
                entity(CampaignFleetAPI.class, "fleet", true, 200f, Set.of("jump_point"))),
                "fleets are Phase 9's, never orbit sync's");
        assertFalse(CoopCampaignReplicator.isJumpPointOrbit(null));
    }

    @Test
    void theCaptureWalksBothListsAndCountsAnEntityInBothOnlyOnce() {
        SectorEntityToken fringe = entity(JumpPointAPI.class, "19f", true, 14800f, Set.of());
        SectorEntityToken tagged = entity(SectorEntityToken.class, "custom_jump", true, 900f, Set.of("jump_point"));
        SectorEntityToken planet = entity(PlanetAPI.class, "jangala", true, 5000f, Set.of());

        List<SectorEntityToken> out = new ArrayList<>();
        CoopCampaignReplicator.addJumpPointOrbitBodies(out, List.of(fringe, planet));
        CoopCampaignReplicator.addJumpPointOrbitBodies(out, List.of(tagged, fringe));
        CoopCampaignReplicator.addJumpPointOrbitBodies(out, null);

        assertEquals(2, out.size(), "the planet is filtered and the double-listed jump point is one entry");
        assertSame(fringe, out.get(0));
        assertSame(tagged, out.get(1));
    }

    private static SectorEntityToken entity(Class<?> type, String id, boolean orbiting, float radius,
                                            Set<String> tags) {
        OrbitAPI orbit = orbiting ? (OrbitAPI) Proxy.newProxyInstance(
                OrbitAPI.class.getClassLoader(), new Class<?>[]{OrbitAPI.class},
                (p, m, a) -> ProxyDefaults.defaultValue(m.getReturnType())) : null;
        return (SectorEntityToken) Proxy.newProxyInstance(
                SectorEntityToken.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getOrbit" -> orbit;
                    case "getCircularOrbitRadius" -> radius;
                    case "hasTag" -> tags.contains((String) args[0]);
                    case "toString" -> "Entity[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }
}
