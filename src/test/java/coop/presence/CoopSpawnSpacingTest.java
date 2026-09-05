package coop.presence;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry behind the forked DisposableFleetManager's edit 3: an ambient fleet that the vanilla
 * AI dropped on top of the co-op guest, in a system the host is not in, gets moved out to the same
 * berth vanilla gives the host - and nothing else is touched.
 */
class CoopSpawnSpacingTest {

    /** Vanilla's own minimum: Global.getSettings().getMaxSensorRange() + 500, at a typical setting. */
    private static final float MIN_DIST = 2500f;

    private static float distance(Vector2f a, Vector2f b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    @Test
    void movesAFleetThatSpawnedOnTopOfThePresenceEntity() {
        Vector2f presence = new Vector2f(6000f, 0f);
        Vector2f fleet = new Vector2f(6100f, 60f); // right in the guest's lap, as the vanilla branch leaves it

        Vector2f moved = CoopSpawnSpacing.awayFrom(fleet, presence, MIN_DIST);

        assertNotNull(moved, "a spawn inside the minimum distance has to be moved");
        assertTrue(distance(moved, presence) >= MIN_DIST,
                "moved to " + distance(moved, presence) + ", wanted at least " + MIN_DIST);
        assertEquals(MIN_DIST + CoopSpawnSpacing.PUSH_MARGIN, distance(moved, presence), 0.5f,
                "the berth should match vanilla pickLocationNotNearPlayer's minDist + 2000");
    }

    @Test
    void leavesAFleetThatSpawnedFarEnoughAwayExactlyWhereItIs() {
        Vector2f presence = new Vector2f(6000f, 0f);
        Vector2f fleet = new Vector2f(6000f, MIN_DIST + 1f);

        assertNull(CoopSpawnSpacing.awayFrom(fleet, presence, MIN_DIST));
    }

    @Test
    void treatsExactlyTheMinimumDistanceAsFarEnough() {
        Vector2f presence = new Vector2f(0f, 0f);
        Vector2f fleet = new Vector2f(MIN_DIST, 0f);

        assertNull(CoopSpawnSpacing.awayFrom(fleet, presence, MIN_DIST));
    }

    @Test
    void leavesThePositionAloneWhenThereIsNoPresenceEntity() {
        // Solo play, the guest side, and any frame before the mirror exists: the fork's presence slot
        // is null and the vanilla placement has to survive byte for byte.
        assertNull(CoopSpawnSpacing.awayFrom(new Vector2f(10f, 10f), null, MIN_DIST));
        assertNull(CoopSpawnSpacing.awayFrom(null, new Vector2f(10f, 10f), MIN_DIST));
        assertNull(CoopSpawnSpacing.awayFrom(null, null, MIN_DIST));
    }

    @Test
    void handlesTheDegenerateCaseOfIdenticalPositions() {
        Vector2f presence = new Vector2f(1200f, -800f);
        Vector2f fleet = new Vector2f(1200f, -800f);

        Vector2f moved = CoopSpawnSpacing.awayFrom(fleet, presence, MIN_DIST);

        assertNotNull(moved);
        assertEquals(MIN_DIST + CoopSpawnSpacing.PUSH_MARGIN, distance(moved, presence), 0.5f);
    }

    @Test
    void pushesTowardsTheStarRatherThanOutOfTheSystem() {
        // Presence far out from the star (the origin, in system-local coordinates) with the fleet just
        // outside it: pushing along that ray would fling the spawn past the system's edge, so the point
        // on the other side of the presence entity - same distance, nearer the star - is the one used.
        Vector2f presence = new Vector2f(9000f, 0f);
        Vector2f fleet = new Vector2f(9200f, 0f);

        Vector2f moved = CoopSpawnSpacing.awayFrom(fleet, presence, MIN_DIST);

        assertNotNull(moved);
        assertEquals(MIN_DIST + CoopSpawnSpacing.PUSH_MARGIN, distance(moved, presence), 0.5f);
        assertTrue(moved.length() < presence.length(),
                "expected the nudge to stay inside the system, ended up at radius " + moved.length());
    }
}
