package coop.campaign;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.SectorAPI;

import coop.net.CoopConnectionRole;
import coop.util.CoopLog;

/**
 * Guest-side Phase 12c enforcement that the guest rolls <b>no</b> portside bar offers of its own: the
 * host's pool is the only pool ({@link CoopBarPoolCapture} / {@link CoopBarPoolInjector}).
 *
 * <p><b>Script registration only — the memory instance stays.</b> {@code BarEventManager} is
 * registered twice by {@code CoreLifecyclePluginImpl.addBarEvents}: once in the sector's script list
 * (which is what makes {@code advance} run and spawn offers) and once in sector memory under
 * {@code $core_genericBarEventManager} (which is what {@code getInstance()} returns). Only the first
 * is removed. Clearing the memory handle would NPE the contact board and the bar screen itself —
 * {@code BaseMissionHub.updateOfferedMissions}, {@code BaseMissionHub.getMissionAngle} and
 * {@code BarCMD.showOptions} all call {@code BarEventManager.getInstance()} unconditionally, and
 * {@code showOptions} needs it for the shuffle seed on every single bar visit.
 *
 * <p><b>What stopping the tick buys, besides no local offers.</b> {@code advance} also re-rolls the
 * manager seed every 20-40 days ({@code updateSeed}); with the script gone, the seed the host synced
 * stays put instead of drifting apart again on its own timer.
 *
 * <p><b>What is deliberately left running.</b> {@code PortsideBarData}'s script keeps ticking. Its
 * {@code advance} prunes on {@code shouldRemoveEvent()}, and the only vanilla events that ever return
 * true there are the two intel-backed classes the pool excludes — so it cannot delete an injected
 * offer, and it still needs to run for the guest's own locally-generated rumor events.
 *
 * <p>Like the Phase 9 fleet suppressor this mutates the live sector, not the save: vanilla
 * re-registers the script on every {@code onGameLoad}, so {@link #reset()} re-arms it at every
 * session start.
 */
public final class CoopBarGenerationSuppressor {

    /** Matched by class simple name, so no fragile internal engine import is needed. */
    static final String MANAGER_SCRIPT = "BarEventManager";

    private boolean suppressed;

    /** The suppressor only runs on the guest; the host keeps generating the canonical pool. */
    public static boolean activeForRole(CoopConnectionRole role) {
        return role == CoopConnectionRole.GUEST;
    }

    /** Session (re)start: vanilla re-added the script on load, so arm the removal again. */
    public void reset() {
        suppressed = false;
    }

    public boolean isSuppressed() {
        return suppressed;
    }

    /** Call every frame on the guest while the session is active; does its work once per session. */
    public void tick(SectorAPI sector) {
        if (sector == null || suppressed) {
            return;
        }
        try {
            int removed = removeManagerScript(sector.getScripts(), sector);
            removed += removeManagerScript(sector.getTransientScripts(), sector);
            // Inside the try, same reasoning as the fleet suppressor: if the removal throws, the flag
            // stays false and the next tick retries rather than silently leaving generation on.
            suppressed = true;
            CoopLog.info(CoopBarGenerationSuppressor.class, "Coop guest suppressed " + removed
                    + " BarEventManager script registration(s); the sector-memory instance stays");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBarGenerationSuppressor.class,
                    "Failed to suppress guest bar event generation", ex);
        }
    }

    private int removeManagerScript(List<EveryFrameScript> scripts, SectorAPI sector) {
        if (scripts == null) {
            return 0;
        }
        List<EveryFrameScript> toRemove = new ArrayList<>();
        for (EveryFrameScript script : scripts) {
            if (script != null && isManagerScript(script.getClass().getSimpleName())) {
                toRemove.add(script);
            }
        }
        for (EveryFrameScript script : toRemove) {
            sector.removeScript(script);
            sector.removeTransientScript(script);
        }
        return toRemove.size();
    }

    // ---- Pure decision function (unit-tested) --------------------------------------------------

    static boolean isManagerScript(String scriptClassSimpleName) {
        return MANAGER_SCRIPT.equals(scriptClassSimpleName);
    }
}
