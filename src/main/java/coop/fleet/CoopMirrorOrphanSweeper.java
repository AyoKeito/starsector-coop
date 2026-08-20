package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.util.CoopLog;

import java.util.ArrayList;

/**
 * Removes coop mirror fleets left behind in a save (Phase 12b).
 *
 * <p>Mirror fleets are created at runtime by {@link CoopFleetMirror} and disposed when a session
 * ends, but a save taken mid-session keeps them: they are ordinary persisted fleets carrying
 * {@code $coopMirrorFleet} or {@code $coopNpcFleetId} in memory, all with {@code setNoAutoDespawn}.
 * The registry that could dispose them is a fresh, empty object after a load, so nothing owns them.
 *
 * <p><b>This is primarily reconnect hygiene, not a solo-play feature.</b> When a guest loads its coop
 * save to <em>resume</em> the campaign (the supported flow), the saved mirrors are unknown to the new
 * {@link CoopFleetMirrorRegistry}: the {@code NPC_FLEET_SET} reconcile would build fresh mirrors for
 * the same host fleets while {@link CoopNpcFleetSuppressor} <em>preserves</em> the saved orphans
 * (they carry the sanctioned tag), so every save/load cycle would double the mirror population.
 * Cleaning a save that gets loaded solo is a side benefit.
 *
 * <p>Invoked from {@code CoopModPlugin.onGameLoad} before the pump installs. At that moment no
 * session is active by construction, so a live session's mirrors are never touched; and even if that
 * ordering ever changed, a live session rebuilds its mirrors from the next set/snapshot anyway.
 */
public final class CoopMirrorOrphanSweeper {

    static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    private CoopMirrorOrphanSweeper() {
    }

    /**
     * Scans every location (hyperspace included — see {@link CoopLocations}) and removes any fleet
     * tagged as a coop mirror. Returns the number removed; logs one line when that is non-zero and
     * stays silent otherwise (this runs on every load, including for players who have never used coop).
     */
    public static int sweep(SectorAPI sector) {
        if (sector == null) {
            return 0;
        }
        int[] removedRef = {0};
        try {
            CoopLocations.forEach(sector, location -> {
                for (CampaignFleetAPI fleet : new ArrayList<>(location.getFleets())) {
                    if (isCoopMirror(fleet)) {
                        location.removeEntity(fleet);
                        removedRef[0]++;
                    }
                }
            });
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopMirrorOrphanSweeper.class,
                    "Coop orphan mirror sweep failed after removing " + removedRef[0] + " fleet(s)", ex);
        }
        int removed = removedRef[0];
        if (removed > 0) {
            CoopLog.info(CoopMirrorOrphanSweeper.class,
                    "Coop removed " + removed + " orphaned mirror fleet(s) left by a previous session");
        }
        return removed;
    }

    private static boolean isCoopMirror(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return false;
        }
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        return isCoopMirrorMemory(memory != null && memory.getBoolean(PLAYER_MIRROR_TAG),
                memory != null && memory.contains(NPC_MIRROR_TAG));
    }

    /** Pure decision function (unit-tested): a fleet is an orphan if it carries either mirror tag. */
    static boolean isCoopMirrorMemory(boolean hasPlayerMirrorTag, boolean hasNpcMirrorTag) {
        return hasPlayerMirrorTag || hasNpcMirrorTag;
    }
}
