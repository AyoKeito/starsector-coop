package coop.combat.join;

/**
 * Spatial threshold for {@link CoopCombatJoinMode#PROXIMITY}.
 *
 * <p>Documented in {@code docs/player/LIMITATIONS.md} as the same 700-unit radius vanilla uses when
 * friendly patrols are pulled into a player engagement (Fight Alongside).
 */
public final class CoopCombatJoinRange {

    /** Vanilla ally pull-in radius in campaign distance units (SU). */
    public static final float VANILLA_ALLY_JOIN_RADIUS_SU = 700f;

    private CoopCombatJoinRange() {
    }

    public static boolean withinAllyPullIn(float distanceSu) {
        return distanceSu >= 0f && distanceSu <= VANILLA_ALLY_JOIN_RADIUS_SU;
    }
}
