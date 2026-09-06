package coop.combat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.fleet.CoopGuestMirrorHandle;
import coop.net.CoopConnectionRole;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2026-09-06 smoke item: an NPC engaged the guest while the guest had the game paused.
 *
 * <p>A paused {@code CampaignEngine} still advances every {@code EveryFrameScript}, so the watcher
 * kept scanning against a frozen world on a wall clock — long enough for a cooldown or the handoff
 * grace to lapse and put {@code ENGAGE_GUEST} on the wire at a player whose world was not running.
 * These tests pin both halves of the answer: the scan is held while the shared clock is stopped, and
 * the watcher's own deadlines are frozen for the duration rather than burned, so the handoff is
 * deferred to the first running frame instead of dropped or fired early.
 *
 * <p>Kept apart from {@link CoopNpcThreatWatcherTest} (the engine-free decision core) because the
 * pause is read off the sector and the hold is in {@code tick}.
 */
class CoopNpcThreatWatcherPauseTest {

    /** One paused campaign frame at 60 Hz — the step the accumulator is built for. */
    private static final long FRAME_MILLIS = 16L;

    private final RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
    private final AtomicInteger battleLeaves = new AtomicInteger();
    private final AtomicInteger fleetsInspected = new AtomicInteger();

    @AfterEach
    void tearDown() {
        // The handle is process-global; a leaked mirror would follow the next test into its scan.
        CoopGuestMirrorHandle.clear();
    }

    @Test
    void aPausedWorldHoldsTheScanButNeverTheBattleEject() {
        World world = publishedWorld();
        world.paused = true;
        CoopNpcThreatWatcher watcher = watcher();

        for (int frame = 0; frame < 30; frame++) {
            watcher.tick(world.sector, 1000L + frame * FRAME_MILLIS, false);
        }

        assertEquals(0, fleetsInspected.get(),
                "no fleet is even a candidate while the shared world is stopped");
        assertTrue(service.sent.isEmpty(), "nothing engages a paused player");
        assertEquals(30, battleLeaves.get(),
                "the pull-in recovery is per-frame and a pause does not excuse it");
    }

    @Test
    void theScanResumesOnTheFirstRunningFrame() {
        World world = publishedWorld();
        world.paused = true;
        CoopNpcThreatWatcher watcher = watcher();
        watcher.tick(world.sector, 1000L, false);
        assertEquals(0, fleetsInspected.get());

        world.paused = false;
        watcher.tick(world.sector, 1016L, false);

        assertTrue(fleetsInspected.get() > 0,
                "deferred, not dropped: the threat is still adjacent when the world runs again");
    }

    @Test
    void aClockThatCannotBeReadCountsAsPaused() {
        // Guessing "running" costs the guest an unasked-for battle; guessing "paused" costs one scan.
        World world = publishedWorld();
        world.pauseThrows = true;
        CoopNpcThreatWatcher watcher = watcher();

        watcher.tick(world.sector, 1000L, false);

        assertEquals(0, fleetsInspected.get());
    }

    @Test
    void aPausedWorldFreezesTheEngageCooldownInsteadOfBurningIt() {
        // The live shape of the defect: the guest beats a fleet, pauses to think, and the 15 s
        // cooldown lapses against a world where nothing moved. On the unpause it re-engages instantly.
        World world = publishedWorld();
        world.paused = true;
        CoopNpcThreatWatcher watcher = watcher();
        watcher.noteBattleConcluded("fleet-a", 1000L);

        long now = 1000L;
        for (int frame = 0; frame < 3000; frame++) { // 48 s of paused wall clock
            now += FRAME_MILLIS;
            watcher.tick(world.sector, now, false);
        }

        assertFalse(watcher.isEngageReady("fleet-a", now),
                "48 s of pause is 0 s of the cooldown the fleet actually owes");
        assertTrue(watcher.isPostDefeatGracePending("fleet-a"),
                "and the queued do-not-attack window has not aged out either");

        world.paused = false;
        for (int frame = 0; frame < 1000; frame++) { // 16 s of running wall clock
            now += FRAME_MILLIS;
            watcher.tick(world.sector, now, false);
        }

        assertTrue(watcher.isEngageReady("fleet-a", now),
                "the cooldown is deferred by the pause, not extended past it");
    }

    @Test
    void aGapTooLongToBeAPausedFrameIsNotCreditedToThePause() {
        // The host in its own battle stops the campaign pump outright. That silence is not a pause
        // the guest asked for, and crediting it would push every cooldown minutes into the future.
        CoopNpcThreatWatcher watcher = watcher();

        watcher.noteFrame(0L, true);
        watcher.noteFrame(60_000L, true);

        assertEquals(CoopNpcThreatWatcher.PAUSE_STEP_CAP_MILLIS, watcher.pausedMillisTotal());
    }

    @Test
    void theFirstFrameOfASessionHasNothingToMeasureAgainst() {
        CoopNpcThreatWatcher watcher = watcher();

        watcher.noteFrame(5_000_000L, true);

        assertEquals(0L, watcher.pausedMillisTotal(),
                "an absolute wall-clock stamp is not a pause duration");
    }

    @Test
    void resetForgetsTheFreezeAlongWithEveryOtherDeadline() {
        CoopNpcThreatWatcher watcher = watcher();
        watcher.noteFrame(0L, true);
        watcher.noteFrame(500L, true);
        assertEquals(500L, watcher.pausedMillisTotal());

        watcher.reset();

        assertEquals(0L, watcher.pausedMillisTotal());
    }

    private CoopNpcThreatWatcher watcher() {
        return new CoopNpcThreatWatcher(service, TestSessions.activeHostSession());
    }

    // ---- engine fakes ----------------------------------------------------------------------------

    /** A sector holding the mirror (in a battle it was pulled into) and one other fleet to scan. */
    private static final class World {
        private boolean paused;
        private boolean pauseThrows;
        private SectorAPI sector;
        private CampaignFleetAPI mirror;
    }

    /** Builds the world and publishes its mirror, which is how {@code tick} finds it. */
    private World publishedWorld() {
        World world = new World();
        BattleAPI battle = (BattleAPI) Proxy.newProxyInstance(
                BattleAPI.class.getClassLoader(),
                new Class<?>[]{BattleAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "leave" -> {
                        battleLeaves.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "battleProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        CampaignFleetAPI other = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMemoryWithoutUpdate" -> {
                        // The scan's first read of any candidate fleet (isMirror): the marker for
                        // "the scan actually walked the location this frame".
                        fleetsInspected.incrementAndGet();
                        yield null;
                    }
                    case "getName" -> "Raiders";
                    case "toString" -> "otherFleetProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        LocationAPI location = (LocationAPI) Proxy.newProxyInstance(
                LocationAPI.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFleets" -> List.of(world.mirror, other);
                    case "getName" -> "Corvus";
                    case "toString" -> "locationProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        world.mirror = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isAlive" -> true;
                    case "getContainingLocation" -> location;
                    case "getBattle" -> battle;
                    case "isTransponderOn" -> true;
                    case "getName" -> "partner Guest";
                    case "toString" -> "mirrorProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        world.sector = (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPaused" -> {
                        if (world.pauseThrows) {
                            throw new IllegalStateException("no clock");
                        }
                        yield world.paused;
                    }
                    case "getPlayerFleet" -> null;
                    case "toString" -> "sectorProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        CoopGuestMirrorHandle.publish(world.mirror);
        return world;
    }
}
