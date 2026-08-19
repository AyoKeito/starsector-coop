package coop.presence;

import com.fs.starfarer.api.campaign.SectorEntityToken;

/**
 * The one slot through which coop.jar tells the forked engine classes "there is a second player, and
 * this entity is where they are".
 *
 * <p><b>Classloader contract</b> (identical to {@code coop.rng.CoopRandom}, and the reason this class
 * lives in its own package rather than in {@code coop.fleet}): this class is bundled into
 * {@code jars/coop-forks.jar} and excluded from {@code jars/coop.jar}, so it is loaded by the JVM
 * system/application classloader -- the same one that loads {@code starfarer.api.jar} and the forked
 * {@code com.fs.starfarer.api.impl.campaign.fleets.RouteManager} that reads it. It therefore must NOT
 * reference any class from {@code coop.jar}, which loads in Starsector's child mod classloader and is
 * invisible to the parent. The write side ({@code coop.fleet.CoopGuestPresence}) is in coop.jar and can
 * see this class, because the mod classloader delegates to its parent first.
 *
 * <p>One static slot, defaulting to null. Null means "no second player" and every fork guarded on it
 * behaves exactly as vanilla -- which is what makes solo play, the guest side, and a launch without
 * coop-forks.jar all provably unchanged.
 *
 * <p>The slot holds a live engine entity, so it must be cleared when the session ends; it is
 * re-asserted every tick and released on the first frame the assert stops arriving. It is deliberately
 * <em>not</em> persisted anywhere: nothing here is written to a save.
 */
public final class CoopPresenceRegistry {

    private static volatile SectorEntityToken presence;

    private CoopPresenceRegistry() {
    }

    /**
     * Publishes the co-op guest's presence entity. Called every tick with the current guest mirror, so
     * a mirror that is disposed and rebuilt heals on the next tick rather than leaving a dead entity
     * in the slot.
     */
    public static void set(SectorEntityToken entity) {
        presence = entity;
    }

    /** Drops the presence entity. After this every fork reading it is back to vanilla behaviour. */
    public static void clear() {
        presence = null;
    }

    /** The co-op guest's presence entity, or null when there is no second player. */
    public static SectorEntityToken get() {
        return presence;
    }
}
