package coop.presence;

import org.lwjgl.util.vector.Vector2f;

/**
 * Spawn-spacing geometry for the guest-presence engine forks: "this ambient fleet just materialised
 * on top of the co-op guest; where should it go instead?".
 *
 * <p><b>Classloader contract</b> - identical to {@link CoopPresenceRegistry}, and the reason this
 * class sits in the same package: it is compiled into {@code jars/coop-forks.jar} and excluded from
 * {@code jars/coop.jar}, so the JVM system classloader owns it, the same one that owns the forked
 * {@code com.fs.starfarer.api.impl.campaign.fleets.DisposableFleetManager} that calls it. It must
 * therefore never reference a class from coop.jar. It references nothing but lwjgl's
 * {@link Vector2f}: no game API, no engine state, no statics.
 *
 * <p>It lives here rather than inline in the fork for one reason - the fork classes are not on the
 * unit-test classpath (adding them would shadow {@code starfarer.api.jar}'s own copies for every
 * other test), while this package already is, being part of the main source set as well as the forks
 * one. The fork keeps the engine calls; this keeps the arithmetic, which is the part worth testing.
 *
 * <p>Why the fork needs it at all: {@code DisposableAggroAssignmentAI.giveInitialAssignments} routes
 * through {@code Misc.pickLocationNotNearPlayer} only when the fleet lands in
 * {@code Global.getSector().getCurrentLocation()} - the HOST's system, always. In a system only the
 * guest is standing in, the other branch runs and drops the fleet at the guarded entity's radius plus
 * 100 units with no distance check against anybody, so a pirate hunter can appear in the guest's lap.
 */
public final class CoopSpawnSpacing {

    /**
     * The slack vanilla's {@code Misc.pickLocationNotNearPlayer} adds on top of its {@code minDist}
     * when it has to shove a spawn away from the player: it scales the push to
     * {@code minDist + 2000 - dist}, so the pushed point ends up {@code minDist + 2000} out. Mirrored
     * here so a guest gets the same berth the host gets, measured the same way.
     */
    public static final float PUSH_MARGIN = 2000f;

    private CoopSpawnSpacing() {
    }

    /**
     * Where to move a freshly spawned fleet so it is not sitting on the other player, or null if it
     * should not be moved at all.
     *
     * <p>Returns null - meaning "leave the vanilla placement exactly as it is" - when either position
     * is null (no co-op session: the fork's presence slot is empty) or when the fleet is already at
     * least {@code minDist} away. Otherwise returns a point {@code minDist + }{@link #PUSH_MARGIN}
     * from {@code presenceLoc}, on the line through the two positions.
     *
     * <p>Of the two points on that line, the one nearer the star (the origin, in system-local
     * coordinates) is chosen, so the nudge pushes the fleet across the system rather than flinging it
     * out past its edge; the result stays within {@code minDist + PUSH_MARGIN} of the presence entity,
     * which is itself inside the system. When the two positions coincide the direction is degenerate,
     * and an arbitrary but deterministic axis is used instead.
     *
     * @param fleetLoc    the fleet's system-local position as the vanilla AI left it, or null.
     * @param presenceLoc the other player's system-local position, or null when there is no session.
     * @param minDist     the closest the fleet may be; the fork passes vanilla's own
     *                    {@code maxSensorRange + 500}.
     */
    public static Vector2f awayFrom(Vector2f fleetLoc, Vector2f presenceLoc, float minDist) {
        if (fleetLoc == null || presenceLoc == null) {
            return null;
        }

        float dx = fleetLoc.x - presenceLoc.x;
        float dy = fleetLoc.y - presenceLoc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist >= minDist) {
            return null;
        }

        float target = minDist + PUSH_MARGIN;
        float ux;
        float uy;
        if (dist < 1f) {
            ux = 1f;
            uy = 0f;
        } else {
            ux = dx / dist;
            uy = dy / dist;
        }

        Vector2f outward = new Vector2f(presenceLoc.x + ux * target, presenceLoc.y + uy * target);
        Vector2f inward = new Vector2f(presenceLoc.x - ux * target, presenceLoc.y - uy * target);
        return lengthSquared(inward) < lengthSquared(outward) ? inward : outward;
    }

    private static float lengthSquared(Vector2f v) {
        return v.x * v.x + v.y * v.y;
    }
}
