package coop.stats;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

/**
 * The static handle between the live {@link CoopSessionStats} tally, which the pump owns, and the two
 * places that need it without having a pump reference: {@code CoopModPlugin.beforeGameSave()} and the
 * "Coop Stats" intel page.
 *
 * <p>Same arrangement, for the same reason, as {@code CoopGuestSnapshotStore}: {@code beforeGameSave}
 * is a {@code ModPlugin} callback and there is no way to reach the campaign pump from it. The newest
 * pump wins — a game load replaces the previous session's tally, exactly like
 * {@code CoopSaveCheckpoint.setActive}.
 *
 * <p><b>Both roles write.</b> The host owns the canonical counters; the guest holds the last
 * {@code SESSION_STATS} it received. Both put what they hold into their own save, so the coordinated
 * pair carries the same tallies and either side can show the page after a reload. Divergence up to
 * one broadcast interval is accepted — these are cosmetic counters, and the spec says so.
 *
 * <p>Nothing here throws. A tally that cannot be embedded must not be able to fail a player's save.
 */
public final class CoopSessionStatsStore {

    private static volatile CoopSessionStats current;

    private CoopSessionStatsStore() {
    }

    /** The pump installs its live tally here, once, as soon as it has one. */
    public static void publish(CoopSessionStats stats) {
        current = stats;
    }

    /** The tally the next save would embed and the intel page renders, or null when there is none. */
    public static CoopSessionStats current() {
        return current;
    }

    /**
     * Drops the handle (game load, pump teardown). Deliberately does <em>not</em> remove the key from
     * any save: the counters already written into an existing save are that session's history.
     */
    public static void clear() {
        current = null;
    }

    /**
     * Reads the tally a previous save embedded, or null when this save has none. Used by the pump to
     * pick a session back up where the last save left it rather than restarting every counter at zero
     * on a reload.
     */
    public static CoopSessionStats readFromCurrentSector() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return null;
            }
            return CoopSessionStats.readFrom(sector.getPersistentData());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionStatsStore.class, "Could not read the coop session stats from"
                    + " this save; starting a fresh tally", ex);
            return null;
        }
    }

    /** Engine-facing form for {@code CoopModPlugin.beforeGameSave()}. Never throws. */
    public static void writeIntoCurrentSector() {
        try {
            CoopSessionStats stats = current;
            if (stats == null) {
                return;
            }
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            stats.writeInto(sector.getPersistentData());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSessionStatsStore.class,
                    "Failed to embed the coop session stats in the save", ex);
        }
    }
}
