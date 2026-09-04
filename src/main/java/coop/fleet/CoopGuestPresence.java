package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Host-side fix for "NPC fleets never appear in a system where only the guest is": vanilla keeps
 * distant fleets abstract as {@code RouteManager} routes and only turns a route into a real
 * {@link CampaignFleetAPI} when <em>the player fleet</em> is close to it. On the host the guest is a
 * mirror fleet ({@code $coopMirrorFleet}), never {@code Global.getSector().getPlayerFleet()}, so
 * nothing materialises around the guest and the guest sees an empty system until the host arrives.
 *
 * <p><b>How it is fixed now.</b> Not here. {@code RouteManager} is forked (see
 * {@code forks/com/fs/starfarer/api/impl/campaign/fleets/RouteManager.java}) so that its own
 * {@code shouldSpawn} / {@code isPlayerInSpawnRange} / {@code shouldDespawn} consider a second
 * "presence" entity alongside the player fleet, and the player-proximity ambient spawners
 * ({@code PlayerVisibleFleetManager} / {@code DisposableFleetManager} / {@code SourceBasedFleetManager}
 * and the two subclasses overriding their own system picker) are forked the same way. Vanilla's own
 * spawn/despawn passes then spawn, own and despawn those fleets natively -- no force-spawn, no field
 * writes, no adoption bookkeeping, no duplicate-spawn window. All this class does is publish which
 * entity that presence is.
 *
 * <p>The registry slot ({@code coop.presence.CoopPresenceRegistry}) lives in {@code coop-forks.jar} on
 * the system classloader, because that is the only place the forked engine class can see it from. The
 * slot defaults to null and every fork edit is guarded on it, so a solo game -- or a launch whose
 * vmparams were never patched with the forks jar -- behaves exactly like vanilla.
 *
 * <p><b>Re-assert, don't register-once.</b> {@link #tick(SectorAPI, long)} writes the current mirror
 * into the slot on every pass rather than on mirror creation, which costs one sector scan and makes
 * the wiring self-healing: if the mirror is disposed and rebuilt (reconnect, fleet rebuild) the slot
 * follows it without any lifecycle hook needing to fire in the right order.
 * {@link #frameBoundary()} releases the slot on the first frame after the ticks stop, which is what
 * covers session end -- the same always-on-observer pattern
 * {@link CoopFullFidelitySystemDriver#frameBoundary(float)} uses.
 *
 * <p><b>Scope.</b> The presence term covers two spawner families, six forks in all, each reading this
 * same slot. {@code RouteManager} covers the route population: market patrols ({@code MilitaryBase}),
 * trade fleets ({@code EconomyFleetRouteManager}), smugglers, mercs, pilgrimages,
 * raid/inspection/expedition fleets, Luddic Path cells. The player-proximity ambient spawners cover the
 * rest: {@code PlayerVisibleFleetManager} (visibility-based culling), {@code DisposableFleetManager}
 * plus the two subclasses that override its system picker ({@code DisposableHostileActivityFleetManager},
 * {@code DisposableThreatFleetManager}), and {@code SourceBasedFleetManager} (Remnant station garrisons).
 * Known limitation of the ambient family: it tracks one spawn system at a time in a save-serialised
 * field, so when the two players are parked in different populated systems only one of them gets ambient
 * spawns.
 */
public final class CoopGuestPresence {

    /** How often the pass runs. Routes change on a scale of in-game days; per-frame is pointless. */
    static final long INTERVAL_MILLIS = 2000L;

    private long nextPassAtMillis;
    private String armedSystemId = "";

    /**
     * Ids of the route fleets alive in the armed system as of the previous pass. Only used by the
     * {@link CoopDebug} churn line below -- it answers "are these fleets cycling?" from the log
     * instead of from someone watching the screen.
     */
    private Set<String> lastRouteFleetIds = new LinkedHashSet<>();

    /**
     * Set once {@code CoopPresenceRegistry} turns out not to be loadable, which happens when the mod
     * runs without {@code coop-forks.jar} on the JVM classpath (unpatched vmparams). One warning, then
     * silence: the game is simply in stock route-spawning behaviour.
     */
    private static boolean registryUnavailable;

    /** True while {@link #tick(SectorAPI, long)} has run since the last {@link #frameBoundary()}. */
    private static boolean tickedSinceFrameBoundary;

    /** Mirrors the registry slot, so an idle frame is a boolean test and not a write. */
    private static boolean presenceRegistered;

    /**
     * Set when a registered slot was actually released, and consumed by the next {@link
     * #tick(SectorAPI, long)} to run its pass immediately instead of waiting out
     * {@link #INTERVAL_MILLIS}.
     *
     * <p>Without it one skipped pump frame — a caught exception earlier in the frame, a tick-ordering
     * hiccup — cost up to two seconds of null slot, roughly 120 frames in which every presence fork
     * evaluates as vanilla. That is not a cosmetic window: with no presence entity the forked
     * {@code RouteManager} measures distance to the host alone, and every route fleet around a guest
     * parked more than {@code DESPAWN_DIST_LY_FAR} from the host despawns as {@code PLAYER_FAR_AWAY},
     * to be respawned at route-interpolated positions when the pass finally ran. Re-publishing is one
     * sector scan, so paying it a frame early is the cheap side of this trade.
     */
    private static boolean presenceReleased;

    // ---- Per-pass entry point --------------------------------------------------------------------

    /**
     * Re-asserts the guest presence entity if the interval has elapsed. Host-only; safe to call every
     * frame. Never throws: any engine failure is logged and swallowed so it cannot take the pump down.
     */
    public void tick(SectorAPI sector, long nowMillis) {
        tickedSinceFrameBoundary = true;
        if (sector == null || (nowMillis < nextPassAtMillis && !presenceReleased)) {
            return;
        }
        presenceReleased = false;
        nextPassAtMillis = nowMillis + INTERVAL_MILLIS;
        try {
            runPass(sector);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopGuestPresence.class, "Coop guest-presence pass failed", ex);
        }
    }

    /**
     * Called every frame from {@link CoopSystemDriveFrameHook}, whatever the session state. Releases
     * the presence slot as soon as {@link #tick(SectorAPI, long)} stops arriving (session ended, role
     * changed, pump stopped) so the fork can never keep spawning around a dead mirror.
     */
    public static void frameBoundary() {
        if (!tickedSinceFrameBoundary) {
            clearPresence();
        }
        tickedSinceFrameBoundary = false;
    }

    /** Session (re)start: forget the armed system so the next pass logs afresh. */
    public void reset() {
        armedSystemId = "";
        nextPassAtMillis = 0L;
        lastRouteFleetIds = new LinkedHashSet<>();
        clearPresence();
    }

    /** The system id the guest presence is currently registered in, or "" when idle. Diagnostics. */
    public String armedSystemId() {
        return armedSystemId;
    }

    private void runPass(SectorAPI sector) {
        // The mod's single authoritative mirror scan (see CoopGuestMirrorHandle): this pass needed one
        // anyway, and publishing its result here is what bounds how long a stale handle can survive to
        // one interval. Every other consumer reads the O(1) handle instead of scanning.
        CampaignFleetAPI mirror = CoopGuestMirrorHandle.reresolve(sector);
        LocationAPI system = mirror == null ? null : mirror.getContainingLocation();
        if (system == null) {
            if (!armedSystemId.isEmpty()) {
                CoopLog.info(CoopGuestPresence.class, "Coop guest presence released (no guest mirror)");
            }
            armedSystemId = "";
            lastRouteFleetIds = new LinkedHashSet<>();
            clearPresence();
            return;
        }

        setPresence(mirror);

        String systemId = String.valueOf(system.getId());
        if (!systemId.equals(armedSystemId)) {
            armedSystemId = systemId;
            lastRouteFleetIds = new LinkedHashSet<>();
            CoopLog.info(CoopGuestPresence.class,
                    "Coop guest presence registered in " + system.getName()
                            + "; vanilla route spawning now treats the guest as a player there");
        }
        reportChurn(system);
    }

    /**
     * Diagnostics only ({@link CoopDebug}-gated, silent otherwise). Logs how many route fleets in the
     * guest's system are gone since the last pass, which distinguishes "the fleets are alive and the
     * motion is bad" from "the fleets are being recycled underneath us" without an in-game errand.
     * Still meaningful with the fork in place: it now measures vanilla's own spawn/despawn churn
     * against the guest, i.e. whether the presence term is actually holding fleets in place.
     */
    private void reportChurn(LocationAPI system) {
        if (!CoopDebug.diagnosticsEnabled()) {
            lastRouteFleetIds = new LinkedHashSet<>();
            return;
        }
        List<RouteData> routes;
        try {
            routes = RouteManager.getInstance().getRoutesInLocation(system);
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        if (routes == null) {
            routes = new ArrayList<>();
        }
        Set<String> current = new LinkedHashSet<>();
        for (RouteData route : routes) {
            CampaignFleetAPI fleet = route == null ? null : route.getActiveFleet();
            if (fleet != null && fleet.getId() != null) {
                current.add(fleet.getId());
            }
        }
        int gone = 0;
        for (String id : lastRouteFleetIds) {
            if (!current.contains(id)) {
                gone++;
            }
        }
        if (gone > 0 || !lastRouteFleetIds.isEmpty()) {
            CoopLog.info(CoopGuestPresence.class,
                    "Coop route-fleet churn in " + system.getName() + ": active=" + current.size()
                            + " routes=" + routes.size() + " goneSinceLastPass=" + gone);
        }
        lastRouteFleetIds = current;
    }

    // ---- The registry slot (lives in coop-forks.jar, on the system classloader) --------------------

    private static void setPresence(CampaignFleetAPI mirror) {
        if (registryUnavailable) {
            return;
        }
        try {
            coop.presence.CoopPresenceRegistry.set(mirror);
            presenceRegistered = true;
        } catch (LinkageError ex) {
            registryUnavailable = true;
            CoopLog.warn(CoopGuestPresence.class,
                    "Coop guest presence unavailable: coop-forks.jar is not on the JVM classpath, so"
                            + " the spawner forks cannot be reached. NPC fleets will only spawn"
                            + " near the host. Re-run scripts/launch-host.ps1 to patch vmparams.", ex);
        }
    }

    private static void clearPresence() {
        if (registryUnavailable || !presenceRegistered) {
            return;
        }
        presenceRegistered = false;
        presenceReleased = true;
        try {
            coop.presence.CoopPresenceRegistry.clear();
        } catch (LinkageError ignored) {
            registryUnavailable = true;
        }
    }

}
