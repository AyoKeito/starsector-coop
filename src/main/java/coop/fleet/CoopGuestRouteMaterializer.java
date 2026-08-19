package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

/**
 * Host-side fix for "NPC fleets never appear in a system where only the guest is": vanilla keeps
 * distant fleets abstract as {@code RouteManager} routes and only turns a route into a real
 * {@link CampaignFleetAPI} when <em>the player fleet</em> is close to it. In co-op the host engine is
 * the NPC authority, and on the host the guest is only a mirror fleet ({@code $coopMirrorFleet}) --
 * not {@code Global.getSector().getPlayerFleet()} -- so nothing materialises around the guest and the
 * guest sees an empty system. The moment the host arrives, every route in that system spawns at once.
 *
 * <p><b>Exact vanilla mechanics this works around</b> (all in
 * {@code com.fs.starfarer.api.impl.campaign.fleets.RouteManager}, called once per frame from
 * {@code CoreScript.advance}):
 * <ul>
 *   <li>{@code advance(float)} -> {@code spawnAndDespawn()} -> {@code shouldSpawn(RouteData)}, which
 *       is hardcoded to {@code Global.getSector().getPlayerFleet().getLocationInHyperspace()} and the
 *       constant {@code SPAWN_DIST_LY = 1.6f}. There is no overridable "also spawn near this entity"
 *       hook and no per-route override.</li>
 *   <li>{@code shouldDespawn(RouteData)} uses the same player fleet against
 *       {@code DESPAWN_DIST_LY_FAR = 4f} / {@code DESPAWN_DIST_LY_CLOSE = 3f}, gated by the route's
 *       {@code daysSinceSeenByPlayer} (30/60 days). A route fleet that exists 4+ LY from the host is
 *       therefore despawned on the very next frame -- so force-spawning alone is not enough.</li>
 * </ul>
 *
 * <p><b>What this class does.</b> Everything is driven through public API except one field write:
 * <ol>
 *   <li>find the guest mirror and its containing star system (hyperspace is deliberately excluded --
 *       "routes in hyperspace" is effectively the whole sector and would materialise everything);</li>
 *   <li>take {@code RouteManager.getInstance().getRoutesInLocation(system)} (public) and, for each
 *       route with no active fleet and no pending delay, call {@code route.getSpawner().spawnFleet(route)}
 *       -- the exact call vanilla's {@code spawnAndDespawn()} makes;</li>
 *   <li>write the result back into {@code RouteData.activeFleet} and register the RouteManager as the
 *       fleet's event listener, again exactly as vanilla does. <b>This is what prevents duplicate
 *       spawns:</b> the route is now "occupied", so vanilla's {@code shouldSpawn} returns false for it
 *       when the host later arrives, and vanilla owns the fleet's despawn/cleanup as usual;</li>
 *   <li>keep {@code daysSinceSeenByPlayer} pinned at 0 for every route currently in the guest's
 *       system, which makes vanilla's {@code shouldDespawn} return on its first check. This is the
 *       same effect vanilla produces when the player looks at a fleet, it needs no cleanup pass, and
 *       it lapses on its own: stop refreshing (guest leaves, session ends, mod removed) and vanilla
 *       despawns the fleets normally 30 days later.</li>
 * </ol>
 *
 * <p><b>Fail-safe.</b> {@code RouteData.activeFleet} and {@code RouteData.daysSinceSeenByPlayer} are
 * protected, so they are written with {@link MethodHandles#privateLookupIn} (the sanctioned pattern
 * here -- {@code java.lang.reflect} is blocked by the script classloader; see {@code CoopBarSync}).
 * If that resolve ever fails, the whole pass disables itself permanently after one warning and the
 * game falls back to stock behaviour -- never a crash, and never a spawn without the matching
 * {@code activeFleet} write, because the handles are resolved before any fleet is created.
 *
 * <p><b>Scope.</b> This covers the {@code RouteManager} population only: market patrols
 * ({@code MilitaryBase}), trade fleets ({@code EconomyFleetRouteManager}), smugglers, mercs,
 * pilgrimages, raid/inspection/expedition fleets, Luddic Path cells. Managers that spawn relative to
 * the player without going through a route -- {@code DisposableFleetManager} /
 * {@code PlayerVisibleFleetManager} subclasses such as {@code DisposablePirateFleetManager}, and
 * {@code SourceBasedFleetManager} -- still key off the host player fleet and are out of scope.
 */
public final class CoopGuestRouteMaterializer {

    /** How often the pass runs. Routes change on a scale of in-game days; per-frame is pointless. */
    static final long INTERVAL_MILLIS = 2000L;

    /**
     * Upper bound on fleets force-spawned in one pass. Vanilla spawns a whole system's worth in a
     * single frame; we drip-feed instead so a route-dense system cannot cost one huge frame. Any
     * remainder is picked up on the next pass two seconds later.
     */
    static final int MAX_SPAWNS_PER_PASS = 4;

    private long nextPassAtMillis;
    private String armedSystemId = "";

    // ---- Per-pass entry point --------------------------------------------------------------------

    /**
     * Runs one materialisation pass if the interval has elapsed. Host-only; safe to call every frame.
     * Never throws: any engine failure is logged and swallowed so it cannot take the pump down.
     */
    public void tick(SectorAPI sector, long nowMillis) {
        if (sector == null || nowMillis < nextPassAtMillis) {
            return;
        }
        nextPassAtMillis = nowMillis + INTERVAL_MILLIS;
        try {
            runPass(sector);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopGuestRouteMaterializer.class,
                    "Coop guest-presence route materialization pass failed", ex);
        }
    }

    /** Session (re)start: forget the armed system so the next pass logs afresh. */
    public void reset() {
        armedSystemId = "";
        nextPassAtMillis = 0L;
    }

    /** The system id the pass is currently extending presence into, or "" when idle. Diagnostics. */
    public String armedSystemId() {
        return armedSystemId;
    }

    private void runPass(SectorAPI sector) {
        if (isPaused(sector)) {
            return;
        }
        CampaignFleetAPI mirror = findGuestMirror(sector);
        LocationAPI system = mirror == null ? null : mirror.getContainingLocation();
        if (!canExtendPresenceTo(system != null, system != null && system.isHyperspace())) {
            armedSystemId = "";
            return;
        }
        if (!resolveHandles()) {
            return;
        }

        RouteManager manager = RouteManager.getInstance();
        List<RouteData> routes = manager.getRoutesInLocation(system);
        if (routes == null) {
            routes = new ArrayList<>();
        }

        String systemId = String.valueOf(system.getId());
        if (!systemId.equals(armedSystemId)) {
            armedSystemId = systemId;
            CoopLog.info(CoopGuestRouteMaterializer.class,
                    "Coop guest-presence route materialization armed for " + system.getName()
                            + " routes=" + routes.size());
        }

        // Keep everything the guest is sitting next to alive against vanilla's host-distance despawn.
        for (RouteData route : routes) {
            markSeenByPlayer(route);
        }

        List<Integer> toSpawn = selectForcedSpawns(views(routes), MAX_SPAWNS_PER_PASS);
        int spawned = 0;
        for (int index : toSpawn) {
            if (forceSpawn(manager, routes.get(index))) {
                spawned++;
            }
        }
        if (spawned > 0) {
            CoopLog.info(CoopGuestRouteMaterializer.class,
                    "Coop force-materialized " + spawned + " route fleet(s) for guest presence in "
                            + system.getName());
        }
    }

    /**
     * Spawns one route's fleet the way {@code RouteManager.spawnAndDespawn()} would, and adopts it
     * into the route so vanilla treats it as its own from here on (no second spawn, normal despawn).
     */
    private boolean forceSpawn(RouteManager manager, RouteData route) {
        CampaignFleetAPI fleet;
        try {
            fleet = route.getSpawner().spawnFleet(route);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopGuestRouteMaterializer.class,
                    "Coop route spawner failed for source " + route.getSource(), ex);
            return false;
        }
        if (fleet == null) {
            // Same as vanilla: a spawner that declines to build a fleet retires the route.
            route.expire();
            return false;
        }
        if (!setActiveFleet(route, fleet)) {
            // Must not leave an unowned fleet behind that vanilla would then duplicate.
            removeStrandedFleet(fleet);
            return false;
        }
        fleet.addEventListener(manager);
        markSeenByPlayer(route);
        return true;
    }

    private static void removeStrandedFleet(CampaignFleetAPI fleet) {
        try {
            if (fleet.getContainingLocation() != null) {
                fleet.getContainingLocation().removeEntity(fleet);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // best-effort
        }
    }

    // ---- Pure decision logic (unit-tested) -------------------------------------------------------

    /** A route only counts as presence-extendable when the guest is inside a real star system. */
    static boolean canExtendPresenceTo(boolean locationPresent, boolean isHyperspace) {
        return locationPresent && !isHyperspace;
    }

    /**
     * Vanilla's own spawn preconditions from {@code RouteManager.shouldSpawn}, minus the player
     * distance check (the guest's presence replaces it), plus a null-spawner guard.
     */
    static boolean shouldForceSpawn(boolean hasActiveFleet, boolean hasSpawner, float delay) {
        return !hasActiveFleet && hasSpawner && delay <= 0f;
    }

    /** Indices of the routes to spawn this pass, capped at {@code maxSpawns}. */
    static List<Integer> selectForcedSpawns(List<? extends RouteView> routes, int maxSpawns) {
        List<Integer> out = new ArrayList<>();
        if (routes == null || maxSpawns <= 0) {
            return out;
        }
        for (int i = 0; i < routes.size() && out.size() < maxSpawns; i++) {
            RouteView route = routes.get(i);
            if (route != null && shouldForceSpawn(route.hasActiveFleet(), route.hasSpawner(), route.delay())) {
                out.add(i);
            }
        }
        return out;
    }

    /** Minimal view of a vanilla {@code RouteData}, so the selection above is testable headless. */
    interface RouteView {
        boolean hasActiveFleet();

        boolean hasSpawner();

        float delay();
    }

    private static List<RouteView> views(List<RouteData> routes) {
        List<RouteView> out = new ArrayList<>(routes.size());
        for (RouteData route : routes) {
            out.add(route == null ? null : new RouteDataView(route));
        }
        return out;
    }

    private record RouteDataView(RouteData route) implements RouteView {
        @Override
        public boolean hasActiveFleet() {
            return route.getActiveFleet() != null;
        }

        @Override
        public boolean hasSpawner() {
            return route.getSpawner() != null;
        }

        @Override
        public float delay() {
            return route.getDelay();
        }
    }

    // ---- Engine lookups --------------------------------------------------------------------------

    /** The Phase 8 guest-player mirror, identified by its {@code $coopMirrorFleet} memory flag. */
    static CampaignFleetAPI findGuestMirror(SectorAPI sector) {
        if (sector == null) {
            return null;
        }
        for (LocationAPI location : allLocations(sector)) {
            List<CampaignFleetAPI> fleets = location.getFleets();
            if (fleets == null) {
                continue;
            }
            for (CampaignFleetAPI fleet : fleets) {
                if (fleet == null) {
                    continue;
                }
                MemoryAPI memory = fleet.getMemoryWithoutUpdate();
                if (memory != null && memory.getBoolean(CoopNpcFleetReplicator.PLAYER_MIRROR_TAG)) {
                    return fleet;
                }
            }
        }
        return null;
    }

    private static List<LocationAPI> allLocations(SectorAPI sector) {
        List<LocationAPI> locations = new ArrayList<>();
        List<LocationAPI> all = sector.getAllLocations();
        if (all != null) {
            for (LocationAPI location : all) {
                if (location != null) {
                    locations.add(location);
                }
            }
        }
        LocationAPI hyperspace = sector.getHyperspace();
        if (hyperspace != null && !locations.contains(hyperspace)) {
            locations.add(hyperspace);
        }
        return locations;
    }

    private static boolean isPaused(SectorAPI sector) {
        try {
            return sector.isPaused();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- MethodHandles field access ---------------------------------------------------------------
    // RouteData.activeFleet and RouteData.daysSinceSeenByPlayer are protected with no setters, and the
    // script classloader blocks java.lang.reflect at runtime, so we go through java.lang.invoke -- the
    // proven CoopBarSync pattern. Lazily resolved once; a failure disables the feature for the process.

    private static volatile boolean handlesResolved;
    private static volatile boolean handlesUsable;
    private static MethodHandle activeFleetSetter;
    private static MethodHandle daysSinceSeenSetter;

    private static synchronized boolean resolveHandles() {
        if (handlesResolved) {
            return handlesUsable;
        }
        handlesResolved = true;
        try {
            MethodHandles.Lookup priv =
                    MethodHandles.privateLookupIn(RouteData.class, MethodHandles.lookup());
            activeFleetSetter = priv.findSetter(RouteData.class, "activeFleet", CampaignFleetAPI.class);
            daysSinceSeenSetter = priv.findSetter(RouteData.class, "daysSinceSeenByPlayer", float.class);
            handlesUsable = true;
        } catch (Throwable ex) {
            handlesUsable = false;
            CoopLog.warn(CoopGuestRouteMaterializer.class,
                    "Coop guest-presence route materialization unavailable (RouteData field access "
                            + "denied); NPC fleets will only spawn near the host", ex);
        }
        return handlesUsable;
    }

    private static boolean setActiveFleet(RouteData route, CampaignFleetAPI fleet) {
        try {
            activeFleetSetter.invoke(route, fleet);
            return true;
        } catch (Throwable ex) {
            CoopLog.warn(CoopGuestRouteMaterializer.class,
                    "Coop could not adopt a force-spawned fleet into its route; discarding it", ex);
            return false;
        }
    }

    /** Pins the route's "seen" timer at 0 so vanilla's host-distance despawn check bails out. */
    private static void markSeenByPlayer(RouteData route) {
        if (route == null || route.getActiveFleet() == null) {
            return;
        }
        try {
            daysSinceSeenSetter.invoke(route, 0f);
        } catch (Throwable ignored) {
            // Worst case the fleet despawns as it would in vanilla; never fatal.
        }
    }
}
