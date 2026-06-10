package coop.fleet;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.net.CoopConnectionRole;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Guest-side Phase 9 enforcement that the guest runs <b>no</b> native NPC simulation; the only fleets
 * that may exist on the guest are the local player fleet, the Phase 8 remote-player mirror
 * ({@code $coopMirrorFleet}), the host NPC mirrors ({@code $coopNpcFleetId}), and stations
 * (deterministic worldgen tied to markets). Two layers:
 *
 * <ol>
 *   <li><b>Spawner suppression (once per session):</b> removes the known sector-level NPC fleet
 *       spawner scripts so the guest stops generating its own population. Matched by class name to
 *       avoid importing fragile internal engine classes.</li>
 *   <li><b>Per-frame sweep (the robust net):</b> removes any fleet that is not protected by the rules
 *       above — robust because enumerating every spawner is fragile, so anything that slips through is
 *       still culled.</li>
 * </ol>
 *
 * <p>This must only run on the guest; the host runs vanilla NPC simulation unchanged. The pump
 * role-gates the call, and {@link #activeForRole(CoopConnectionRole)} encodes the same rule.
 */
public final class CoopNpcFleetSuppressor {
    static final String PLAYER_MIRROR_TAG = "$coopMirrorFleet";
    static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    private static final Set<String> KNOWN_SPAWNERS = Set.of(
            "RouteManager",
            "EconomyFleetRouteManager",
            "EconomyFleetAssignmentAI",
            "MercFleetManagerV2",
            "RemnantFleetManager",
            "RemnantSeededFleetManager",
            "PersonBountyManager");

    private boolean spawnersSuppressed;

    /** Runs both layers; call every frame on the guest while the session is active. */
    public void tick(SectorAPI sector) {
        if (sector == null) {
            return;
        }
        if (!spawnersSuppressed) {
            try {
                suppressSpawners(sector);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetSuppressor.class, "Failed to suppress NPC spawner scripts", ex);
            }
            spawnersSuppressed = true;
        }
        try {
            sweep(sector);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcFleetSuppressor.class, "Failed to sweep guest NPC fleets", ex);
        }
    }

    /** Re-arm the once-per-session spawner suppression (session (re)start). */
    public void reset() {
        spawnersSuppressed = false;
    }

    private void suppressSpawners(SectorAPI sector) {
        int removed = removeSpawnerScripts(sector.getScripts(), sector);
        removed += removeSpawnerScripts(sector.getTransientScripts(), sector);
        CoopLog.info(CoopNpcFleetSuppressor.class,
                "Coop guest suppressed " + removed + " NPC spawner script(s)");
    }

    private int removeSpawnerScripts(List<EveryFrameScript> scripts, SectorAPI sector) {
        if (scripts == null) {
            return 0;
        }
        List<EveryFrameScript> toRemove = new ArrayList<>();
        for (EveryFrameScript script : scripts) {
            if (script != null && isSpawnerScriptName(script.getClass().getSimpleName())) {
                toRemove.add(script);
            }
        }
        for (EveryFrameScript script : toRemove) {
            sector.removeScript(script);
            sector.removeTransientScript(script);
        }
        return toRemove.size();
    }

    private void sweep(SectorAPI sector) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        forEachLocation(sector, loc -> {
            // Copy first: removeEntity mutates the live fleet list.
            for (CampaignFleetAPI fleet : new ArrayList<>(loc.getFleets())) {
                if (shouldRemove(fleet, player)) {
                    loc.removeEntity(fleet);
                }
            }
        });
    }

    private boolean shouldRemove(CampaignFleetAPI fleet, CampaignFleetAPI player) {
        if (fleet == null) {
            return false;
        }
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        boolean playerMirror = memory != null && memory.getBoolean(PLAYER_MIRROR_TAG);
        boolean npcMirror = memory != null && memory.contains(NPC_MIRROR_TAG);
        return shouldRemoveFleet(fleet == player, fleet.isStationMode(), playerMirror, npcMirror);
    }

    private void forEachLocation(SectorAPI sector, Consumer<LocationAPI> consumer) {
        for (LocationAPI loc : sector.getAllLocations()) {
            if (loc != null) {
                consumer.accept(loc);
            }
        }
        LocationAPI hyperspace = sector.getHyperspace();
        if (hyperspace != null && !sector.getAllLocations().contains(hyperspace)) {
            consumer.accept(hyperspace);
        }
    }

    // ---- Pure decision functions (unit-tested) ----------------------------------------------

    /** The suppressor only runs on the guest; the host keeps its authoritative NPC simulation. */
    public static boolean activeForRole(CoopConnectionRole role) {
        return role == CoopConnectionRole.GUEST;
    }

    /** A fleet is swept unless it is the local player, a station, or a sanctioned coop mirror. */
    static boolean shouldRemoveFleet(boolean isPlayerFleet, boolean isStation,
                                     boolean isPlayerMirror, boolean isNpcMirror) {
        return !isPlayerFleet && !isStation && !isPlayerMirror && !isNpcMirror;
    }

    /** Matches the known sector-level NPC spawner managers by class name. */
    static boolean isSpawnerScriptName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return false;
        }
        if (KNOWN_SPAWNERS.contains(simpleName)) {
            return true;
        }
        return simpleName.endsWith("FleetManager")
                || simpleName.endsWith("RouteManager")
                || simpleName.endsWith("BountyManager");
    }
}
