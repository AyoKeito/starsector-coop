package coop.fleet;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.fleet.CoopNpcFleetSuppressor.BaseIntelCleanup;
import coop.fleet.CoopNpcFleetSuppressor.ManagerHandle;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNpcFleetSuppressorTest {

    @Test
    void plainNpcFleetIsSwept() {
        assertTrue(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, false, false));
    }

    @Test
    void localPlayerFleetIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(true, false, false, false));
    }

    @Test
    void stationIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, true, false, false));
    }

    @Test
    void remotePlayerMirrorIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, true, false));
    }

    @Test
    void npcMirrorIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, false, true));
    }

    @Test
    void suppressorRunsOnGuestOnly() {
        assertTrue(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.GUEST));
        assertFalse(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.HOST));
        assertFalse(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.NONE));
    }

    @Test
    void recognizesKnownAndSuffixedSpawnerScripts() {
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("RouteManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("MercFleetManagerV2"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("RemnantSeededFleetManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("PersonBountyManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("HegemonyPatrolFleetManager"));
    }

    @Test
    void doesNotMatchUnrelatedScripts() {
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName("CoopNetPump"));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName("CoopCampaignReplicator"));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(""));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(null));
    }

    // ---- Phase 13: explicit suppression set ----------------------------------------------------

    @Test
    void suppressesPhase13InventoryManagers() {
        for (String name : Arrays.asList(
                "PirateBaseManager",
                "PlayerRelatedPirateBaseManager",
                "LuddicPathBaseManager",
                "EncounterManager",
                "SensorGhostManager",
                "DecivTracker",
                "WarSimScript",
                "PunitiveExpeditionManager",
                "FactionHostilityManager")) {
            assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName(name),
                    name + " must be suppressed on the guest");
        }
    }

    @Test
    void suppressesStoryAndSdfFleetSpawners() {
        for (String name : Arrays.asList(
                "PersonalFleetHoracioCaden",
                "PersonalFleetOxanaHyder",
                "SDFHegemony",
                "SDFLeague",
                "SDFTriTachyon",
                "SDFLuddicChurch")) {
            assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName(name),
                    name + " spawns fleets and must be suppressed on the guest");
        }
    }

    /**
     * The other scripts {@code CoreLifecyclePluginImpl.addScriptsIfNeeded()} registers. These are
     * per-player, benign, or deliberately deferred (slipstreams = Phase 26, bar/mission sync = its own
     * phase) — suppressing them would take gameplay away from the guest for no determinism gain.
     */
    @Test
    void keepsBenignAndDeliberatelyDeferredScripts() {
        for (String name : Arrays.asList(
                "StrandedGiveTJScript",
                "SlipstreamManager",
                "OfficerManagerEvent",
                "FieldRepairsScript",
                "HostileActivityManager",
                "HTFactorTracker",
                "GenericMissionManager",
                "SmugglingScanScript",
                "HasslePlayerScript",
                "PortsideBarData",
                "BarEventManager",
                "PlaythroughLog")) {
            assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(name),
                    name + " is not a spawner and must keep running on the guest");
        }
    }

    // ---- Phase 13: cached sector-memory handles -------------------------------------------------

    @Test
    void ghostManagerHandleIsCleared() {
        ManagerHandle handle = CoopNpcFleetSuppressor.managerHandle("SensorGhostManager");
        assertEquals("$ghostManager", handle.key);
        assertTrue(handle.clearAfterRemoval);
    }

    @Test
    void handlesVanillaDereferencesUnconditionallyAreKept() {
        // Clearing these would NPE vanilla: PirateBaseManager.getInstance().getDaysSinceStart() is
        // read by Tuning/CoreCampaignPluginImpl/missions, WarSimScript.getInstance() by raid actions.
        for (Map.Entry<String, String> entry : Map.of(
                "PirateBaseManager", "$core_pirateBaseManager",
                "LuddicPathBaseManager", "$core_luddicPathBaseManager",
                "PlayerRelatedPirateBaseManager", "$core_PR_pirateBaseManager",
                "WarSimScript", "$core_warSimScript",
                "DecivTracker", "$core_decivTracker",
                "FactionHostilityManager", "$core_factionHostilityManager",
                "PunitiveExpeditionManager", "$core_punitiveExpeditionManager",
                "EncounterManager", "$encounterManager").entrySet()) {
            ManagerHandle handle = CoopNpcFleetSuppressor.managerHandle(entry.getKey());
            assertEquals(entry.getValue(), handle.key);
            assertFalse(handle.clearAfterRemoval, entry.getKey() + " handle must not be cleared");
        }
    }

    @Test
    void scriptsWithoutACachedHandleHaveNone() {
        assertNull(CoopNpcFleetSuppressor.managerHandle("MercFleetManagerV2"));
        assertNull(CoopNpcFleetSuppressor.managerHandle(null));
    }

    @Test
    void suppressionRemovesMatchedScriptsAndAppliesHandlePolicy() {
        RecordingSector sector = new RecordingSector();
        sector.scripts.add(new PirateBaseManager());
        sector.scripts.add(new SensorGhostManager());
        sector.scripts.add(new SlipstreamManager());
        sector.memory.put("$ghostManager", "stale-handle");

        new CoopNpcFleetSuppressor().tick(sector.proxy(), 0L);

        assertEquals(2, sector.removed.size(), "only the two spawners are removed");
        assertEquals(1, sector.scripts.size());
        assertEquals("SlipstreamManager", sector.scripts.get(0).getClass().getSimpleName());
        assertFalse(sector.memory.containsKey("$ghostManager"), "stale ghost handle must be unset");
        assertSame(sector.removed.get(0), sector.memory.get("$core_pirateBaseManager"),
                "the pirate base manager handle is backfilled, never cleared");
    }

    // ---- Phase 12b/13: spawner suppression retries and re-arms ---------------------------------

    @Test
    void spawnerSuppressionRetriesAfterAThrow() {
        CoopNpcFleetSuppressor suppressor = new CoopNpcFleetSuppressor();
        FailingSector sector = new FailingSector(1);

        // First tick: getScripts() throws, so suppression fails. Pre-12b the "done" flag was set
        // outside the try, permanently demoting the per-frame sweep to sole mechanism.
        suppressor.tick(sector.proxy(), 0L);
        assertEquals(1, sector.scriptAccessAttempts);

        // Second tick retries and succeeds.
        suppressor.tick(sector.proxy(), 0L);
        assertEquals(2, sector.scriptAccessAttempts);

        // Third tick: already suppressed, so no further attempts.
        suppressor.tick(sector.proxy(), 0L);
        assertEquals(2, sector.scriptAccessAttempts,
                "suppression should run once successfully, then stop retrying");
    }

    @Test
    void resetReArmsSuppressionForTheNextSession() {
        CoopNpcFleetSuppressor suppressor = new CoopNpcFleetSuppressor();
        FailingSector sector = new FailingSector(0);

        suppressor.tick(sector.proxy(), 0L);
        suppressor.tick(sector.proxy(), 0L);
        assertEquals(1, sector.scriptAccessAttempts, "suppression runs once per session");

        // Vanilla's addScriptsIfNeeded re-registers every spawner on each onGameLoad, so the pump
        // calls reset() at session (re)start and suppression must run again.
        suppressor.reset();
        suppressor.tick(sector.proxy(), 0L);
        assertEquals(2, sector.scriptAccessAttempts, "reset() re-arms suppression");
    }

    // ---- Phase 13: guest session-start base-intel cleanup --------------------------------------

    @Test
    void endsGuestBasesAndTheirActivityIntel() {
        FakeIntelCleanup cleanup = new FakeIntelCleanup();
        Object pirateBase = "pirateBase";
        Object pathBase = "pathBase";
        Object activity = "activity";
        cleanup.pirateBases.add(pirateBase);
        cleanup.pathBases.add(pathBase);
        cleanup.pirateActivity.add(activity);
        cleanup.sources.put(activity, pirateBase);

        assertEquals(3, CoopNpcFleetSuppressor.endGuestBaseIntel(cleanup));
        // Activity intel first: its notifyEnding() reads the source base's market.
        assertEquals(List.of(activity, pirateBase, pathBase), cleanup.ended);
    }

    @Test
    void endsOrphanedActivityIntel() {
        FakeIntelCleanup cleanup = new FakeIntelCleanup();
        Object orphan = "orphanActivity";
        cleanup.pirateActivity.add(orphan);

        assertEquals(1, CoopNpcFleetSuppressor.endGuestBaseIntel(cleanup));
        assertEquals(List.of(orphan), cleanup.ended);
    }

    @Test
    void leavesActivityIntelWhoseSourceSurvives() {
        FakeIntelCleanup cleanup = new FakeIntelCleanup();
        Object foreignBase = "hostMirroredBase";
        Object activity = "activity";
        cleanup.pirateActivity.add(activity);
        cleanup.sources.put(activity, foreignBase);

        assertEquals(0, CoopNpcFleetSuppressor.endGuestBaseIntel(cleanup));
        assertTrue(cleanup.ended.isEmpty());
    }

    @Test
    void toleratesEmptyAndNullIntelLists() {
        FakeIntelCleanup cleanup = new FakeIntelCleanup();
        cleanup.nullLists = true;
        assertEquals(0, CoopNpcFleetSuppressor.endGuestBaseIntel(cleanup));
    }

    // ---- The sweep gate ------------------------------------------------------------------------

    @Test
    void theFirstSweepOfASessionIsImmediate() {
        assertTrue(CoopNpcFleetSuppressor.shouldSweep(Long.MIN_VALUE, 0L));
        assertTrue(CoopNpcFleetSuppressor.shouldSweep(Long.MIN_VALUE, 1_000_000L));
    }

    @Test
    void sweepsAtMostOncePerInterval() {
        long last = 10_000L;
        assertFalse(CoopNpcFleetSuppressor.shouldSweep(last, last));
        assertFalse(CoopNpcFleetSuppressor.shouldSweep(last, last + 16L));
        assertFalse(CoopNpcFleetSuppressor.shouldSweep(last,
                last + CoopNpcFleetSuppressor.SWEEP_INTERVAL_MILLIS - 1L));
        assertTrue(CoopNpcFleetSuppressor.shouldSweep(last,
                last + CoopNpcFleetSuppressor.SWEEP_INTERVAL_MILLIS));
    }

    @Test
    void aBackwardsClockSweepsRatherThanStalling() {
        assertTrue(CoopNpcFleetSuppressor.shouldSweep(10_000L, 9_000L));
    }

    @Test
    void tickSweepsOnTheGateAndNotEveryFrame() {
        SweepSector sector = new SweepSector();
        CoopNpcFleetSuppressor suppressor = new CoopNpcFleetSuppressor();

        suppressor.tick(sector.proxy(), 1_000L);
        assertEquals(1, sector.corvus.removed.size(), "the first tick sweeps");

        sector.corvus.fleets.add(FakeSweptFleet.plain());
        suppressor.tick(sector.proxy(), 1_016L);
        suppressor.tick(sector.proxy(), 1_100L);
        assertEquals(1, sector.corvus.removed.size(), "frames inside the interval do not sweep");

        suppressor.tick(sector.proxy(), 1_000L + CoopNpcFleetSuppressor.SWEEP_INTERVAL_MILLIS);
        assertEquals(2, sector.corvus.removed.size(), "the interval elapsed, so the net catches it");
    }

    @Test
    void resetReArmsTheSweepForTheNextSession() {
        SweepSector sector = new SweepSector();
        CoopNpcFleetSuppressor suppressor = new CoopNpcFleetSuppressor();

        suppressor.tick(sector.proxy(), 1_000L);
        sector.corvus.fleets.add(FakeSweptFleet.plain());
        suppressor.reset();
        suppressor.tick(sector.proxy(), 1_016L);

        assertEquals(2, sector.corvus.removed.size(),
                "a session (re)start sweeps immediately rather than an interval later");
    }

    @Test
    void aLocationWithNothingToRemoveIsNotCopied() {
        // The whole point of the gate plus the detect-before-copy pass: on a suppressed guest almost
        // every location is empty or holds only sanctioned mirrors, and the old form allocated an
        // ArrayList copy of each one's fleet list on every frame to discover that.
        SweepSector sector = new SweepSector();
        sector.corvus.fleets.clear();
        sector.corvus.fleets.add(FakeSweptFleet.mirror());

        new CoopNpcFleetSuppressor().tick(sector.proxy(), 1_000L);

        assertTrue(sector.corvus.removed.isEmpty());
        assertEquals(1, sector.corvus.fleetListReads, "one read to check, no second read to copy");
    }

    @Test
    void coverageLineReportsBothStatuses() {
        assertTrue(CoopNpcFleetSuppressor.coverageLine("persistent", "PirateBaseManager", true)
                .contains("SUPPRESSED"));
        assertTrue(CoopNpcFleetSuppressor.coverageLine("transient", "SlipstreamManager", false)
                .contains("KEPT"));
        assertTrue(CoopNpcFleetSuppressor.coverageLine("transient", "SlipstreamManager", false)
                .contains("[transient] SlipstreamManager"));
    }

    // ---- Fakes ---------------------------------------------------------------------------------

    private static final class FakeIntelCleanup implements BaseIntelCleanup {
        private final List<Object> pirateBases = new ArrayList<>();
        private final List<Object> pathBases = new ArrayList<>();
        private final List<Object> pirateActivity = new ArrayList<>();
        private final Map<Object, Object> sources = new IdentityHashMap<>();
        private final List<Object> ended = new ArrayList<>();
        private boolean nullLists;

        @Override
        public List<Object> pirateBases() {
            return nullLists ? null : pirateBases;
        }

        @Override
        public List<Object> pathBases() {
            return nullLists ? null : pathBases;
        }

        @Override
        public List<Object> pirateActivityIntel() {
            return nullLists ? null : pirateActivity;
        }

        @Override
        public Object activitySource(Object activityIntel) {
            return sources.get(activityIntel);
        }

        @Override
        public void endAndRemove(Object intel) {
            ended.add(intel);
        }
    }

    /** Named to match the vanilla classes: the matcher keys off {@code getSimpleName()}. */
    private static class NoOpScript implements EveryFrameScript {
        @Override
        public void advance(float amount) {
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean runWhilePaused() {
            return false;
        }
    }

    private static final class PirateBaseManager extends NoOpScript {
    }

    private static final class SensorGhostManager extends NoOpScript {
    }

    private static final class SlipstreamManager extends NoOpScript {
    }

    /** A fleet the sweep either culls or preserves, depending on its coop memory tag. */
    private static final class FakeSweptFleet {
        private final Map<String, Object> memory;

        private FakeSweptFleet(Map<String, Object> memory) {
            this.memory = new HashMap<>(memory);
        }

        private static FakeSweptFleet plain() {
            return new FakeSweptFleet(Map.of());
        }

        private static FakeSweptFleet mirror() {
            return new FakeSweptFleet(Map.of(CoopNpcFleetSuppressor.NPC_MIRROR_TAG, "abc"));
        }

        private CampaignFleetAPI proxy() {
            return (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeSweptFleet" + memory;
                        case "hashCode" -> System.identityHashCode(this);
                        case "equals" -> proxy == args[0];
                        case "isStationMode" -> Boolean.FALSE;
                        case "getMemoryWithoutUpdate" -> Proxy.newProxyInstance(
                                MemoryAPI.class.getClassLoader(),
                                new Class<?>[]{MemoryAPI.class},
                                (m, mm, margs) -> switch (mm.getName()) {
                                    case "toString" -> "mem";
                                    case "hashCode" -> System.identityHashCode(m);
                                    case "equals" -> m == margs[0];
                                    case "getBoolean" ->
                                            Boolean.TRUE.equals(memory.get(String.valueOf(margs[0])));
                                    case "contains" -> memory.containsKey(String.valueOf(margs[0]));
                                    default -> null;
                                });
                        default -> null;
                    });
        }
    }

    /** One location whose fleet list the sweep walks, counting reads and recording removals. */
    private static final class SweepLocation {
        private final List<FakeSweptFleet> fleets = new ArrayList<>();
        private final List<FakeSweptFleet> removed = new ArrayList<>();
        private final Map<CampaignFleetAPI, FakeSweptFleet> proxies = new IdentityHashMap<>();
        private LocationAPI proxy;
        private int fleetListReads;

        private LocationAPI proxy() {
            if (proxy == null) {
                proxy = (LocationAPI) Proxy.newProxyInstance(
                        LocationAPI.class.getClassLoader(),
                        new Class<?>[]{LocationAPI.class},
                        (p, method, args) -> switch (method.getName()) {
                            case "toString" -> "SweepLocation";
                            case "hashCode" -> System.identityHashCode(this);
                            case "equals" -> p == args[0];
                            case "getId" -> "corvus";
                            case "getFleets" -> {
                                fleetListReads++;
                                List<CampaignFleetAPI> out = new ArrayList<>();
                                for (FakeSweptFleet fleet : fleets) {
                                    CampaignFleetAPI fleetProxy = fleet.proxy();
                                    proxies.put(fleetProxy, fleet);
                                    out.add(fleetProxy);
                                }
                                yield out;
                            }
                            case "removeEntity" -> {
                                FakeSweptFleet fleet = proxies.get((CampaignFleetAPI) args[0]);
                                fleets.remove(fleet);
                                removed.add(fleet);
                                yield null;
                            }
                            default -> null;
                        });
            }
            return proxy;
        }
    }

    /** SectorAPI with one sweepable location and no spawner scripts. */
    private static final class SweepSector {
        private final SweepLocation corvus = new SweepLocation();

        private SweepSector() {
            corvus.fleets.add(FakeSweptFleet.plain());
        }

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "SweepSector";
                        case "hashCode" -> System.identityHashCode(this);
                        case "equals" -> proxy == args[0];
                        case "getScripts", "getTransientScripts" -> new ArrayList<EveryFrameScript>();
                        case "getAllLocations" -> new ArrayList<>(List.of(corvus.proxy()));
                        default -> null;
                    });
        }
    }

    /** SectorAPI recording script removals and sector-memory writes. */
    private static final class RecordingSector {
        private final List<EveryFrameScript> scripts = new ArrayList<>();
        private final List<EveryFrameScript> removed = new ArrayList<>();
        private final Map<String, Object> memory = new HashMap<>();

        private SectorAPI proxy() {
            MemoryAPI memoryProxy = (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "memory";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "set" -> {
                            memory.put((String) args[0], args[1]);
                            yield null;
                        }
                        case "unset" -> {
                            memory.remove((String) args[0]);
                            yield null;
                        }
                        case "contains" -> memory.containsKey((String) args[0]);
                        case "getBoolean" -> Boolean.TRUE.equals(memory.get((String) args[0]));
                        default -> null;
                    });

            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getScripts" -> new ArrayList<>(scripts);
                        case "getTransientScripts" -> new ArrayList<EveryFrameScript>();
                        case "removeScript", "removeTransientScript" -> {
                            EveryFrameScript script = (EveryFrameScript) args[0];
                            if (scripts.remove(script)) {
                                removed.add(script);
                            }
                            yield null;
                        }
                        case "getMemoryWithoutUpdate" -> memoryProxy;
                        case "getAllLocations" -> new ArrayList<LocationAPI>();
                        default -> null;
                    });
        }
    }

    /** SectorAPI whose getScripts() throws for the first {@code failures} calls, then works. */
    private static final class FailingSector {
        private final int failures;
        private int scriptAccessAttempts;

        private FailingSector(int failures) {
            this.failures = failures;
        }

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "toString" -> {
                                return proxy.getClass().getName();
                            }
                            case "hashCode" -> {
                                return System.identityHashCode(proxy);
                            }
                            case "equals" -> {
                                return proxy == args[0];
                            }
                            case "getScripts" -> {
                                scriptAccessAttempts++;
                                if (scriptAccessAttempts <= failures) {
                                    throw new IllegalStateException("simulated spawner scan failure");
                                }
                                return new ArrayList<EveryFrameScript>();
                            }
                            case "getTransientScripts" -> {
                                return new ArrayList<EveryFrameScript>();
                            }
                            case "getAllLocations" -> {
                                return new ArrayList<LocationAPI>();
                            }
                            case "getHyperspace", "getPlayerFleet" -> {
                                return null;
                            }
                            default -> {
                                return null;
                            }
                        }
                    });
        }
    }
}
