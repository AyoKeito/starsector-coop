package coop.fleet;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.net.CoopConnectionRole;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guest-side Phase 9 enforcement that the guest runs <b>no</b> native NPC simulation; the only fleets
 * that may exist on the guest are the local player fleet, the Phase 8 remote-player mirror
 * ({@code $coopMirrorFleet}), the host NPC mirrors ({@code $coopNpcFleetId}), and stations
 * (deterministic worldgen tied to markets). Three layers:
 *
 * <ol>
 *   <li><b>Spawner suppression (once per session):</b> removes the known sector-level NPC fleet
 *       spawner scripts so the guest stops generating its own population. Matched by class name to
 *       avoid importing fragile internal engine classes. Vanilla re-registers these on every
 *       {@code onGameLoad} ({@code CoreLifecyclePluginImpl.addScriptsIfNeeded}), so this re-runs
 *       every session — {@link #reset()} re-arms it.</li>
 *   <li><b>Session-start base-intel cleanup (Phase 13):</b> the guest spawned its own pirate/Pather
 *       bases before the base managers joined the suppression set; any that survive in a loaded save
 *       are ended and removed here.</li>
 *   <li><b>Periodic sweep (the robust net):</b> removes any fleet that is not protected by the rules
 *       above — robust because enumerating every spawner is fragile, so anything that slips through is
 *       still culled. Runs on a {@link #SWEEP_INTERVAL_MILLIS} timer rather than every frame.</li>
 * </ol>
 *
 * <p>This must only run on the guest; the host runs vanilla NPC simulation unchanged. The pump
 * role-gates the call, and {@link #activeForRole(CoopConnectionRole)} encodes the same rule.
 */
public final class CoopNpcFleetSuppressor {
    static final String PLAYER_MIRROR_TAG = CoopMirrorTags.PLAYER_MIRROR_TAG;
    static final String NPC_MIRROR_TAG = CoopMirrorTags.NPC_MIRROR_TAG;

    /**
     * Explicit suppression set. The suffix rules in {@link #isSpawnerScriptName(String)} catch the
     * bulk of vanilla's spawners; everything here is a fleet spawner or runtime-random world mutator
     * whose class name does <em>not</em> end in one of those suffixes. Sources: the Phase 13
     * inventory table plus a full pass over {@code CoreLifecyclePluginImpl.addScriptsIfNeeded()}.
     */
    private static final Set<String> KNOWN_SPAWNERS = Set.of(
            // Pre-Phase-13 set (kept: some of these also match a suffix rule, which is harmless).
            "RouteManager",
            "EconomyFleetRouteManager",
            "EconomyFleetAssignmentAI",
            "MercFleetManagerV2",
            "RemnantFleetManager",
            "RemnantSeededFleetManager",
            "PersonBountyManager",

            // Phase 13 inventory table: dynamic bases, encounters, ghosts, deciv, war sim,
            // punitive expeditions, faction hostility rolls.
            "PirateBaseManager",
            "PlayerRelatedPirateBaseManager",
            "LuddicPathBaseManager",
            "EncounterManager",
            "SensorGhostManager",
            "DecivTracker",
            "WarSimScript",
            "PunitiveExpeditionManager",
            "FactionHostilityManager",

            // Phase 13 coverage pass: story/SDF fleet spawners. All extend PersonalFleetScript
            // (unseeded `new Random()`, respawn-on-death timers) and none end in a matched suffix.
            "PersonalFleetHoracioCaden",
            "PersonalFleetOxanaHyder",
            "SDFHegemony",
            "SDFLeague",
            "SDFTriTachyon",
            "SDFLuddicChurch");

    /**
     * Sector-memory handles vanilla caches for the managers we remove, keyed by class simple name.
     *
     * <p>Two policies, and the difference matters: a removed script no longer advances, so the cached
     * handle is only dangerous when vanilla can use it to <em>resurrect</em> the manager's behaviour.
     * Clearing a handle that vanilla dereferences unconditionally would instead turn a harmless no-op
     * into a guest-side NPE — {@code PirateBaseManager.getInstance().getDaysSinceStart()} alone is
     * called from {@code Tuning}, {@code CoreCampaignPluginImpl} (every interaction dialog),
     * bounty intel and several missions.
     */
    private static final Map<String, ManagerHandle> MANAGER_HANDLES = Map.ofEntries(
            // Lazy script-scan cache with no external callers: null it so nothing keeps servicing the
            // removed manager (SensorGhostManager.getGhostManager(), key "$ghostManager").
            Map.entry("SensorGhostManager", new ManagerHandle("$ghostManager", true)),
            // Same lazy-scan pattern, but AbyssalLightEntityPlugin dereferences the result without a
            // null check; keeping the handle makes that call a no-op instead of a crash.
            Map.entry("EncounterManager", new ManagerHandle("$encounterManager", false)),
            // Constructor-set handles: vanilla reads them as plain data holders (timers, counters)
            // long after the manager stops running. Never clear these.
            Map.entry("PirateBaseManager", new ManagerHandle("$core_pirateBaseManager", false)),
            Map.entry("LuddicPathBaseManager", new ManagerHandle("$core_luddicPathBaseManager", false)),
            Map.entry("PlayerRelatedPirateBaseManager", new ManagerHandle("$core_PR_pirateBaseManager", false)),
            Map.entry("WarSimScript", new ManagerHandle("$core_warSimScript", false)),
            Map.entry("DecivTracker", new ManagerHandle("$core_decivTracker", false)),
            Map.entry("FactionHostilityManager", new ManagerHandle("$core_factionHostilityManager", false)),
            Map.entry("PunitiveExpeditionManager", new ManagerHandle("$core_punitiveExpeditionManager", false)));

    /**
     * How often the per-frame net actually sweeps. The sweep is a backstop for fleets that slip past
     * the spawner suppression, not a gate anything depends on for correctness: a native fleet that
     * exists for a quarter of a second before being culled is exactly what happened anyway before the
     * Phase 9 hardening, since a sweep always raced the spawn that produced it. What the old per-frame
     * cadence did cost was a walk of every location in the sector, every frame, on the client that can
     * least afford it.
     */
    static final long SWEEP_INTERVAL_MILLIS = 250L;

    private boolean spawnersSuppressed;
    /** Sentinel: the first tick of a session sweeps immediately (see {@link #shouldSweep}). */
    private long lastSweepAtMillis = Long.MIN_VALUE;

    /**
     * Runs all layers; call every frame on the guest while the session is active.
     *
     * @param nowMillis the pump's wall clock, so the sweep interval is the same clock everything else
     *                  in the frame is timed against
     */
    public void tick(SectorAPI sector, long nowMillis) {
        if (sector == null) {
            return;
        }
        if (!spawnersSuppressed) {
            try {
                suppressSpawners(sector);
                // Set inside the try: if suppression throws, the flag stays false and the next tick
                // retries. Setting it outside meant one failure disabled the spawner-removal layer
                // for the whole session, silently demoting the per-frame sweep from safety net to
                // sole mechanism (vanilla spawners keep producing fleets we then delete every frame).
                spawnersSuppressed = true;
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetSuppressor.class, "Failed to suppress NPC spawner scripts", ex);
            }
        }
        if (!shouldSweep(lastSweepAtMillis, nowMillis)) {
            return;
        }
        lastSweepAtMillis = nowMillis;
        try {
            sweep(sector);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcFleetSuppressor.class, "Failed to sweep guest NPC fleets", ex);
        }
    }

    /**
     * The sweep gate, pure so it is unit-tested rather than reasoned about: sweep on the first tick
     * after a (re)start, then once per {@link #SWEEP_INTERVAL_MILLIS}. A clock that moved backwards
     * sweeps rather than stalling.
     */
    static boolean shouldSweep(long lastSweepAtMillis, long nowMillis) {
        return lastSweepAtMillis == Long.MIN_VALUE
                || nowMillis < lastSweepAtMillis
                || nowMillis - lastSweepAtMillis >= SWEEP_INTERVAL_MILLIS;
    }

    /**
     * Re-arm the once-per-session spawner suppression (session (re)start). Also re-arms the sweep, so
     * the first tick of a new session culls whatever the previous one left behind immediately rather
     * than up to an interval later.
     */
    public void reset() {
        spawnersSuppressed = false;
        lastSweepAtMillis = Long.MIN_VALUE;
    }

    private void suppressSpawners(SectorAPI sector) {
        // Null unless CoopDebug is on: the coverage diagnostic is opt-in (it logs every sector script).
        List<String> coverage = CoopDebug.diagnosticsEnabled() ? new ArrayList<>() : null;
        int removed = removeSpawnerScripts(sector.getScripts(), sector, "persistent", coverage);
        removed += removeSpawnerScripts(sector.getTransientScripts(), sector, "transient", coverage);
        int endedIntel = endGuestBaseIntel(sector);
        CoopLog.info(CoopNpcFleetSuppressor.class,
                "Coop guest suppressed " + removed + " NPC spawner script(s), ended " + endedIntel
                        + " guest-spawned base intel item(s)");
        logCoverage(coverage);
    }

    private int removeSpawnerScripts(List<EveryFrameScript> scripts, SectorAPI sector,
                                     String listName, List<String> coverage) {
        if (scripts == null) {
            return 0;
        }
        List<EveryFrameScript> toRemove = new ArrayList<>();
        for (EveryFrameScript script : scripts) {
            if (script == null) {
                continue;
            }
            boolean suppressed = isSpawnerScriptName(script.getClass().getSimpleName());
            if (suppressed) {
                toRemove.add(script);
            }
            if (coverage != null) {
                coverage.add(coverageLine(listName, script.getClass().getSimpleName(), suppressed));
            }
        }
        for (EveryFrameScript script : toRemove) {
            sector.removeScript(script);
            sector.removeTransientScript(script);
            applyManagerHandlePolicy(sector, script);
        }
        return toRemove.size();
    }

    /** Clear or backfill the sector-memory handle vanilla caches for a manager we just removed. */
    private void applyManagerHandlePolicy(SectorAPI sector, EveryFrameScript script) {
        ManagerHandle handle = managerHandle(script.getClass().getSimpleName());
        if (handle == null) {
            return;
        }
        try {
            MemoryAPI memory = sector.getMemoryWithoutUpdate();
            if (memory == null) {
                return;
            }
            if (handle.clearAfterRemoval) {
                memory.unset(handle.key);
            } else if (!memory.contains(handle.key)) {
                // Backfill so vanilla's unconditional getInstance() dereference stays safe even when
                // nothing populated the lazy cache before we removed the script.
                memory.set(handle.key, script);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcFleetSuppressor.class,
                    "Failed to apply memory-handle policy for " + handle.key, ex);
        }
    }

    private void logCoverage(List<String> coverage) {
        if (coverage == null || coverage.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("Coop guest suppression coverage (")
                .append(coverage.size()).append(" sector script(s)):");
        for (String line : coverage) {
            sb.append('\n').append(line);
        }
        CoopLog.info(CoopNpcFleetSuppressor.class, sb.toString());
    }

    /**
     * Phase 13 cleanup: the guest generated its own pirate/Pather bases while the base managers were
     * unsuppressed. End and remove whatever survives in a loaded save.
     */
    private int endGuestBaseIntel(SectorAPI sector) {
        // Self-contained failure handling: spawner suppression is the load-bearing layer, and letting
        // a cleanup failure escape would leave the once-per-session flag unset (per-frame retry spam).
        try {
            IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return 0;
            }
            return endGuestBaseIntel(new SectorBaseIntelCleanup(manager));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNpcFleetSuppressor.class, "Failed to end guest-spawned base intel", ex);
            return 0;
        }
    }

    private void sweep(SectorAPI sector) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        CoopLocations.forEach(sector, loc -> {
            List<CampaignFleetAPI> fleets = loc.getFleets();
            if (fleets == null || fleets.isEmpty()) {
                return;
            }
            // Detect first, copy second. The overwhelmingly common case on a suppressed guest is a
            // location with nothing to remove — usually with no fleets at all — and the old form paid
            // for an ArrayList copy of every location's fleet list on every pass to discover that.
            if (!containsRemovable(fleets, player)) {
                return;
            }
            // Copy before removing: removeEntity mutates the live fleet list.
            for (CampaignFleetAPI fleet : new ArrayList<>(fleets)) {
                if (shouldRemove(fleet, player)) {
                    loc.removeEntity(fleet);
                }
            }
        });
    }

    private boolean containsRemovable(List<CampaignFleetAPI> fleets, CampaignFleetAPI player) {
        for (int i = 0; i < fleets.size(); i++) {
            if (shouldRemove(fleets.get(i), player)) {
                return true;
            }
        }
        return false;
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

    /** The cached sector-memory handle for a suppressed manager, or null when it has none. */
    static ManagerHandle managerHandle(String simpleName) {
        if (simpleName == null) {
            return null;
        }
        return MANAGER_HANDLES.get(simpleName);
    }

    /** One coverage-diagnostic line: every sector script, suppressed or kept. */
    static String coverageLine(String listName, String simpleName, boolean suppressed) {
        return "  " + (suppressed ? "SUPPRESSED" : "KEPT      ") + " [" + listName + "] " + simpleName;
    }

    /**
     * Ends every guest-generated base intel item, plus the pirate-activity intel derived from it.
     *
     * <p>Activity intel goes first: {@code PirateActivityIntel.notifyEnding()} reads its source base's
     * market to strip the {@code PIRATE_ACTIVITY} condition, and it does <em>not</em> self-clean
     * synchronously — its {@code advanceImpl} only calls {@code endAfterDelay()}, which needs an
     * advancing intel manager the suppressed guest cannot be relied on to provide.
     *
     * @return the number of intel items ended.
     */
    static int endGuestBaseIntel(BaseIntelCleanup cleanup) {
        List<Object> pirateBases = nonNull(cleanup.pirateBases());
        List<Object> pathBases = nonNull(cleanup.pathBases());
        Set<Object> pirateBaseIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        pirateBaseIdentity.addAll(pirateBases);

        int ended = 0;
        for (Object activity : nonNull(cleanup.pirateActivityIntel())) {
            Object source = cleanup.activitySource(activity);
            // A null source is an orphan whose own advanceImpl would NPE — end it as well.
            if (source == null || pirateBaseIdentity.contains(source)) {
                cleanup.endAndRemove(activity);
                ended++;
            }
        }
        for (Object base : pirateBases) {
            cleanup.endAndRemove(base);
            ended++;
        }
        for (Object base : pathBases) {
            cleanup.endAndRemove(base);
            ended++;
        }
        return ended;
    }

    private static List<Object> nonNull(Collection<?> source) {
        List<Object> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (Object item : source) {
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    // ---- Seams -------------------------------------------------------------------------------

    /** A manager's cached sector-memory handle and what to do with it once the script is removed. */
    static final class ManagerHandle {
        final String key;
        /**
         * True: unset the key — nothing dereferences it and a stale handle keeps the removed manager
         * reachable. False: leave it in place (backfilling if absent) — vanilla dereferences this
         * handle without a null check, so clearing it would crash the guest.
         */
        final boolean clearAfterRemoval;

        ManagerHandle(String key, boolean clearAfterRemoval) {
            this.key = key;
            this.clearAfterRemoval = clearAfterRemoval;
        }
    }

    /**
     * Seam over the engine's intel manager so {@link #endGuestBaseIntel(BaseIntelCleanup)} stays a
     * pure, unit-testable decision function. The engine-typed implementation is
     * {@code SectorBaseIntelCleanup}; tests drive a fake.
     */
    interface BaseIntelCleanup {
        /** Live {@code PirateBaseIntel} items. */
        List<Object> pirateBases();

        /** Live {@code LuddicPathBaseIntel} items. */
        List<Object> pathBases();

        /** Live {@code PirateActivityIntel} items. */
        List<Object> pirateActivityIntel();

        /** The base intel an activity item derives from, or null when it is orphaned. */
        Object activitySource(Object activityIntel);

        /** {@code endImmediately()} + {@code removeIntel()} + despawn of the base's station entity. */
        void endAndRemove(Object intel);
    }

    /** Engine-typed {@link BaseIntelCleanup}; loaded only when the guest actually runs the cleanup. */
    private static final class SectorBaseIntelCleanup implements BaseIntelCleanup {
        private final IntelManagerAPI manager;

        private SectorBaseIntelCleanup(IntelManagerAPI manager) {
            this.manager = manager;
        }

        @Override
        public List<Object> pirateBases() {
            return collect(com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel.class);
        }

        @Override
        public List<Object> pathBases() {
            return collect(com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseIntel.class);
        }

        @Override
        public List<Object> pirateActivityIntel() {
            return collect(com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel.class);
        }

        @Override
        public Object activitySource(Object activityIntel) {
            if (!(activityIntel instanceof com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel)) {
                return null;
            }
            try {
                return ((com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel) activityIntel)
                        .getSource();
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
        }

        @Override
        public void endAndRemove(Object intel) {
            // Per-item defensiveness: one misbehaving intel must not abort the rest of the cleanup
            // (and must not leave the once-per-session flag unset, which would retry forever).
            try {
                SectorEntityToken entity = baseEntity(intel);
                if (intel instanceof com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin) {
                    // notifyEnding() removes the hidden market from the economy and clears the
                    // listeners/radio chatter (PirateBaseIntel.java:687-697).
                    ((com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin) intel).endImmediately();
                }
                if (intel instanceof IntelInfoPlugin) {
                    // The guest's managers are suppressed, so nothing drains ended intel.
                    manager.removeIntel((IntelInfoPlugin) intel);
                }
                despawn(entity);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetSuppressor.class, "Failed to end guest-spawned base intel", ex);
            }
        }

        private static SectorEntityToken baseEntity(Object intel) {
            if (intel instanceof com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel) {
                return ((com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel) intel).getEntity();
            }
            if (intel instanceof com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseIntel) {
                return ((com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseIntel) intel).getEntity();
            }
            return null;
        }

        /**
         * Vanilla only despawns a base's station entity when a bounty fleet destroys it
         * (Misc.fadeAndExpire, PirateBaseIntel.java:712) — plain ending leaves it floating with a
         * de-registered market, and the per-frame sweep protects station fleets. Remove it outright.
         */
        private static void despawn(SectorEntityToken entity) {
            if (entity == null) {
                return;
            }
            LocationAPI location = entity.getContainingLocation();
            if (location != null) {
                location.removeEntity(entity);
            }
        }

        private List<Object> collect(Class<?> type) {
            List<Object> result = new ArrayList<>();
            List<IntelInfoPlugin> intel = manager.getIntel(type);
            if (intel != null) {
                result.addAll(intel);
            }
            return result;
        }
    }
}
