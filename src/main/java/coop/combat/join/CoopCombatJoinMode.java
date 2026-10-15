package coop.combat.join;

/**
 * How a non-engaged partner should participate when the other player enters combat.
 *
 * <p>Matches the Suite phase-1 design: fleets within vanilla ally pull-in range fight side by side;
 * fleets farther away must not teleport into the battle — they get a hyperspace-comm remote option
 * instead.
 */
public enum CoopCombatJoinMode {
    /** Same-system and within {@link CoopCombatJoinRange#VANILLA_ALLY_JOIN_RADIUS_SU}. */
    PROXIMITY,
    /** Different system, or same system but out of ally pull-in range. */
    REMOTE_SUPPORT,
    /** Separation could not be measured; prefer remote so we never invent a merge. */
    UNKNOWN_FALLBACK_REMOTE;

    public boolean isRemote() {
        return this != PROXIMITY;
    }

    public CoopCombatJoinMode canonical() {
        return this == UNKNOWN_FALLBACK_REMOTE ? REMOTE_SUPPORT : this;
    }
}
