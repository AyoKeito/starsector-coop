package coop.combat;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless coverage of the Phase 14 battle bridge. The engine is reached only through {@code Proxy}
 * stubs of {@code CombatEngineAPI} / {@code ShipAPI} (the pattern {@code CoopNetPumpTest} uses), and
 * every campaign tick is called with a null sector, which the bridge treats as "no UI to drive" —
 * the banner-posting and pending-action paths need a live engine and are covered by the two-instance
 * smoke test instead. What is asserted here is the queue the banners are posted from.
 */
class CoopBattleBridgeTest {

    // ---- combat pause intent, both directions ----------------------------------------------------

    @Test
    void hostFightingAssertsTheCombatPauseImmediately() {
        Fixture fixture = Fixture.host();

        fixture.bridge.onCombatFrame(fixture.engine(List.of()), 1000L);

        // Asserted inside beginLocalBattle, not on the next pump frame: the host pump is about to
        // stop advancing for the whole battle, so a deferred assert might never run.
        assertTrue(fixture.pause.eitherInCombat());
        assertTrue(fixture.pause.effectivePaused());
        assertEquals(CoopMessages.Type.BATTLE_BEGIN, fixture.service.sent.get(0).type());
    }

    @Test
    void hostReturningFromCombatClearsTheCombatPauseAndSendsBattleEnd() {
        Fixture fixture = Fixture.host();
        fixture.bridge.onCombatFrame(fixture.engine(List.of()), 1000L);
        fixture.service.sent.clear();

        // First campaign frame after combat: Global.getCurrentState() is unavailable headless and the
        // bridge falls back to CAMPAIGN, which is exactly the post-combat state being simulated.
        fixture.bridge.tickCampaign(null, true, 2000L);

        assertFalse(fixture.pause.eitherInCombat());
        assertFalse(fixture.pause.effectivePaused());
        assertEquals(CoopMessages.Type.BATTLE_END, fixture.service.sent.get(0).type());
        assertEquals("UNKNOWN", CoopMessages.requiredPayloadString(fixture.service.sent.get(0), "outcome"));
    }

    @Test
    void hostRecordsTheGuestsBattleAsACombatPauseIntent() {
        Fixture fixture = Fixture.host();

        fixture.bridge.handle(CoopMessages.battleBegin("session-a", 1L, 0L,
                "battle-g", "guest-player", "Corvus", "Pirate Raiders", "", CoopMessages.BattleKind.PLAYER));
        fixture.bridge.tickCampaign(null, true, 1000L);

        assertTrue(fixture.pause.eitherInCombat());

        fixture.bridge.handle(CoopMessages.battleEnd("session-a", 2L, 0L,
                "battle-g", "guest-player", "WIN"));
        fixture.bridge.tickCampaign(null, true, 2000L);

        assertFalse(fixture.pause.eitherInCombat());
    }

    @Test
    void theCampaignEventOutcomeEnrichesBattleEnd() {
        Fixture fixture = Fixture.host();
        fixture.bridge.onCombatFrame(fixture.engine(List.of()), 1000L);
        fixture.service.sent.clear();

        fixture.bridge.onPlayerEngagement("DISENGAGED");
        fixture.bridge.tickCampaign(null, true, 2000L);

        assertEquals("DISENGAGED",
                CoopMessages.requiredPayloadString(fixture.service.sent.get(0), "outcome"));
    }

    // ---- battle window + eject recovery ------------------------------------------------------------

    @Test
    void aQueuedStateTransitionDoesNotEndTheBattleBeforeItStarts() {
        // startBattle queues the transition, so the campaign pump can run one more frame first
        // (observed in the Phase 14 spike). Ending there would release the partner instantly.
        assertFalse(CoopBattleBridge.isLocalBattleOver(true, false, true));
        assertTrue(CoopBattleBridge.isLocalBattleOver(true, true, true));
        assertFalse(CoopBattleBridge.isLocalBattleOver(true, true, false));
        assertFalse(CoopBattleBridge.isLocalBattleOver(false, true, true));
    }

    @Test
    void aBattleThatNeverReachesCombatTimesOutInsteadOfHoldingTheClock() {
        assertFalse(CoopBattleBridge.isStartTimedOut(false, CoopBattleBridge.BATTLE_START_TIMEOUT_MILLIS));
        assertTrue(CoopBattleBridge.isStartTimedOut(false, CoopBattleBridge.BATTLE_START_TIMEOUT_MILLIS + 1));
        assertFalse(CoopBattleBridge.isStartTimedOut(true, Long.MAX_VALUE));
    }

    @Test
    void combatPauseIntentIsTheOrOfBothSides() {
        assertFalse(CoopBattleBridge.combatPauseIntent(false, false));
        assertTrue(CoopBattleBridge.combatPauseIntent(true, false));
        assertTrue(CoopBattleBridge.combatPauseIntent(false, true));
        assertTrue(CoopBattleBridge.combatPauseIntent(true, true));
    }

    // ---- spectator banners (the panel was cancelled 2026-08-19) -----------------------------------

    @Test
    void thePartnersBattleQueuesABeginBannerAndThenAnOutcomeBanner() {
        Fixture fixture = Fixture.host();

        fixture.bridge.handle(CoopMessages.battleBegin("session-a", 1L, 0L,
                "battle-g", "guest-player", "Corvus", "Pirate Raiders", "", CoopMessages.BattleKind.PLAYER));

        assertEquals(List.of("Coop: Guest is fighting Pirate Raiders in Corvus."),
                pendingBannersOf(fixture.bridge));

        fixture.bridge.handle(CoopMessages.battleEnd("session-a", 2L, 0L, "battle-g", "guest-player", "WIN"));

        assertEquals(List.of("Coop: Guest is fighting Pirate Raiders in Corvus.",
                        "Coop: Guest won the battle."),
                pendingBannersOf(fixture.bridge));
    }

    @Test
    void theOutcomeBannerCarriesTheSurvivorsFromTheLastStatusReceived() {
        Fixture fixture = Fixture.host();
        fixture.bridge.handle(CoopMessages.battleBegin("session-a", 1L, 0L,
                "battle-g", "guest-player", "", "Raiders", "", CoopMessages.BattleKind.PLAYER));
        fixture.bridge.handle(statusMessage(new CoopBattleStatus("battle-g", 1L, 1000L, List.of(
                new CoopBattleStatus.ShipRecord("s1", "wolf", "Wolf", false, 1f, 0f,
                        CoopBattleStatus.ShipState.ALIVE),
                new CoopBattleStatus.ShipRecord("s2", "kite", "Kite", false, 0f, 1f,
                        CoopBattleStatus.ShipState.DISABLED),
                new CoopBattleStatus.ShipRecord("e1", "lasher", "Lasher", true, 0.5f, 0f,
                        CoopBattleStatus.ShipState.ALIVE)),
                List.of())));

        fixture.bridge.handle(CoopMessages.battleEnd("session-a", 3L, 0L, "battle-g", "guest-player",
                "DISENGAGED"));

        assertEquals("Coop: Guest disengaged (last report: 1 of 2 ships standing).",
                pendingBannersOf(fixture.bridge).get(1));
    }

    @Test
    void aDisconnectMidSpectateStillQueuesTheConnectionLostBanner() {
        Fixture fixture = Fixture.host();
        fixture.bridge.handle(CoopMessages.battleBegin("session-a", 1L, 0L,
                "battle-g", "guest-player", "Corvus", "Raiders", "", CoopMessages.BattleKind.PLAYER));

        fixture.bridge.tickCampaign(null, false, 1000L);

        List<String> banners = pendingBannersOf(fixture.bridge);
        assertEquals(2, banners.size());
        assertTrue(banners.get(1).contains("connection lost"));
        assertFalse(fixture.bridge.isAnyCoopBattleActive());
    }

    @Test
    void bannersSurviveFramesWithNoCampaignUiAndNeverGrowWithoutBound() {
        Fixture fixture = Fixture.host();
        for (int i = 0; i < CoopBattleBridge.MAX_PENDING_BANNERS + 4; i++) {
            fixture.bridge.handle(CoopMessages.battleBegin("session-a", i + 1, 0L,
                    "battle-" + i, "guest-player", "Corvus", "Raiders " + i, "",
                    CoopMessages.BattleKind.PLAYER));
            // A sector-less frame cannot post them; they must stay queued rather than be dropped.
            fixture.bridge.tickCampaign(null, true, 1000L + i);
        }

        List<String> banners = pendingBannersOf(fixture.bridge);
        assertEquals(CoopBattleBridge.MAX_PENDING_BANNERS, banners.size());
        assertTrue(banners.get(banners.size() - 1).contains("Raiders "
                + (CoopBattleBridge.MAX_PENDING_BANNERS + 3)), "the newest banner is always kept");
    }

    @Test
    void bannerTextDegradesGracefullyOnMissingDetail() {
        assertEquals("Coop: Your partner is fighting an enemy fleet.",
                CoopBattleBridge.battleBeginBanner("Your partner", "", ""));
        assertEquals("Coop: Ayo finished the battle.",
                CoopBattleBridge.battleEndBanner("Ayo", "UNKNOWN", ""));
        assertEquals("Coop: Ayo lost the battle.",
                CoopBattleBridge.battleEndBanner("Ayo", "LOSS", ""));
        assertEquals("", CoopBattleBridge.survivorSummary(null));
    }

    @Test
    void theRetainedStatusStreamDigestsToOneChangeDetectedLine() {
        CoopBattleStatus status = new CoopBattleStatus("b", 3L, 0L, List.of(
                new CoopBattleStatus.ShipRecord("s1", "wolf", "Wolf", false, 1f, 0f,
                        CoopBattleStatus.ShipState.ALIVE),
                new CoopBattleStatus.ShipRecord("e1", "lasher", "Lasher", true, 0f, 0f,
                        CoopBattleStatus.ShipState.DESTROYED)),
                List.of());

        assertEquals("battleId=b partner 1/1 enemy 0/1", CoopBattleBridge.statusDigest(status));
        assertEquals("", CoopBattleBridge.statusDigest(null));
    }

    // ---- disconnect mid-combat ----------------------------------------------------------------------

    @Test
    void aBattleResultDiscardedByDisconnectIsLoggedLoudlyAndTheClockIsReleased() {
        Fixture fixture = Fixture.host();
        fixture.bridge.onCombatFrame(fixture.engine(List.of()), 1000L);
        assertTrue(fixture.pause.eitherInCombat());
        fixture.service.sent.clear();

        // Session goes away mid-battle (the pump's disconnect edge resets the session state).
        fixture.bridge.tickCampaign(null, false, 2000L);

        assertTrue(fixture.service.sent.isEmpty(), "no BATTLE_END can be sent without a session");
        assertFalse(fixture.pause.eitherInCombat(), "the shared clock must never stay stuck on a dead peer");
    }

    @Test
    void theDiscardedResultMessageNamesTheBattleAndTheReconciliationPath() {
        String message = CoopBattleBridge.discardedResultMessage("battle-7", "Pirate Raiders");

        assertTrue(message.contains("DISCARDED"));
        assertTrue(message.contains("battle-7"));
        assertTrue(message.contains("Pirate Raiders"));
        assertTrue(message.contains("BATTLE_END"));
    }

    @Test
    void theDiscardedResultMessageToleratesAnUnknownEnemy() {
        assertTrue(CoopBattleBridge.discardedResultMessage("b", "").contains("unknown enemy"));
        assertTrue(CoopBattleBridge.discardedResultMessage("b", null).contains("unknown enemy"));
    }

    // ---- status capture -------------------------------------------------------------------------

    @Test
    void statusCaptureSplitsSidesAndSkipsFighters() {
        Fixture fixture = Fixture.host();
        List<Object> ships = List.of(
                ship("s1", "onslaught", "ISS Hammer", 0, 0.8f, 0.2f, true, false, false),
                ship("f1", "talon", "Talon", 0, 1f, 0f, true, false, true),
                ship("s2", "lasher", "Pirate Lasher", 1, 0.4f, 0.6f, true, false, false));

        fixture.bridge.onCombatFrame(fixture.engine(ships), 1000L);

        CoopBattleStatus status = decodeLastStatus(fixture.service.sent);
        assertEquals(1, status.ownShips().size());
        assertEquals("ISS Hammer", status.ownShips().get(0).name());
        assertEquals(1, status.enemyShips().size());
        assertEquals("Pirate Lasher", status.enemyShips().get(0).name());
        assertEquals(0.4f, status.enemyShips().get(0).hullFraction(), 0.0001f);
        assertEquals(1L, status.statusSeq());
    }

    @Test
    void aShipThatVanishesBetweenCapturesBecomesAKillFeedEntry() {
        // Destroyed ships leave engine.getShips() entirely, so the feed is a set difference.
        Fixture fixture = Fixture.host();
        Object survivor = ship("s1", "onslaught", "ISS Hammer", 0, 0.8f, 0.2f, true, false, false);
        Object doomed = ship("s2", "lasher", "Pirate Lasher", 1, 0.1f, 0.9f, true, false, false);
        fixture.bridge.onCombatFrame(fixture.engine(List.of(survivor, doomed)), 1000L);

        fixture.bridge.onCombatFrame(fixture.engine(List.of(survivor)),
                1000L + CoopBattleBridge.STATUS_INTERVAL_MILLIS);

        CoopBattleStatus status = decodeLastStatus(fixture.service.sent);
        assertEquals(List.of("Enemy Pirate Lasher destroyed"), status.killFeed());
        assertEquals(2L, status.statusSeq());
    }

    @Test
    void statusIsThrottledBetweenCaptures() {
        Fixture fixture = Fixture.host();
        List<Object> ships = List.of(ship("s1", "wolf", "Wolf", 0, 1f, 0f, true, false, false));

        fixture.bridge.onCombatFrame(fixture.engine(ships), 1000L);
        fixture.bridge.onCombatFrame(fixture.engine(ships), 1000L + CoopBattleBridge.STATUS_INTERVAL_MILLIS - 1);

        assertEquals(1, countOf(fixture.service.sent, CoopMessages.Type.BATTLE_STATUS));
    }

    @Test
    void aSimulatorFightIsNeverReportedAsACoopBattle() {
        Fixture fixture = Fixture.host();

        fixture.bridge.onCombatFrame(fixture.simulatorEngine(), 1000L);

        assertTrue(fixture.service.sent.isEmpty());
        assertFalse(fixture.pause.eitherInCombat());
    }

    // ---- stale status ------------------------------------------------------------------------------

    @Test
    void aStaleStatusNeverOverwritesANewerOne() {
        Fixture fixture = Fixture.host();
        fixture.bridge.handle(CoopMessages.battleBegin("session-a", 1L, 0L,
                "battle-g", "guest-player", "Corvus", "Raiders", "", CoopMessages.BattleKind.PLAYER));
        CoopBattleStatus newer = new CoopBattleStatus("battle-g", 5L, 5000L, List.of(), List.of("newer"));
        CoopBattleStatus older = new CoopBattleStatus("battle-g", 4L, 4000L, List.of(), List.of("older"));

        fixture.bridge.handle(statusMessage(newer));
        fixture.bridge.handle(statusMessage(older));

        assertTrue(fixture.bridge.isAnyCoopBattleActive());
        // The panel reads the bridge's stored snapshot; assert via the re-encoded body of the newest.
        assertEquals(List.of("newer"), fixture.latestRemoteKillFeed());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static CoopMessages.Message statusMessage(CoopBattleStatus status) {
        return CoopMessages.battleStatus("session-a", 1L, 0L, status.battleId(), status.statusSeq(),
                status.elapsedMillis(), status.encodeBody());
    }

    private static int countOf(List<CoopMessages.Message> sent, CoopMessages.Type type) {
        int count = 0;
        for (CoopMessages.Message message : sent) {
            if (message.type() == type) {
                count++;
            }
        }
        return count;
    }

    private static CoopBattleStatus decodeLastStatus(List<CoopMessages.Message> sent) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            CoopMessages.Message message = sent.get(i);
            if (message.type() == CoopMessages.Type.BATTLE_STATUS) {
                return CoopBattleStatus.decode(
                        CoopMessages.requiredPayloadString(message, "battleId"),
                        CoopMessages.requiredPayloadLong(message, "statusSeq"),
                        CoopMessages.requiredPayloadLong(message, "elapsedMillis"),
                        CoopMessages.requiredPayloadString(message, "ships"));
            }
        }
        throw new AssertionError("no BATTLE_STATUS was sent");
    }

    private static final class Fixture {
        private final RecordingNetService service;
        private final CoopSessionState session;
        private final CoopSharedPauseCoordinator pause = new CoopSharedPauseCoordinator();
        private final CoopBattleBridge bridge;

        private Fixture(CoopConnectionRole role) {
            service = new RecordingNetService(role);
            session = new CoopSessionState(sequencedIds("lobby-a", "host-player", "session-a"));
            session.startHost("Host");
            session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
            session.hostAcceptHandshake();
            session.recordSeedLock(1234L, "seed", "fingerprint");
            bridge = new CoopBattleBridge(service, session, () -> 0L, pause);
        }

        private static Fixture host() {
            return new Fixture(CoopConnectionRole.HOST);
        }

        private CombatEngineAPI engine(List<Object> ships) {
            return combatEngine(ships, false);
        }

        private CombatEngineAPI simulatorEngine() {
            return combatEngine(List.of(), true);
        }

        private List<String> latestRemoteKillFeed() {
            // The bridge exposes the latest snapshot to the panel through a supplier; re-create the
            // same view by rendering a panel against it.
            List<String> feed = new ArrayList<>();
            CoopBattleStatus status = remoteStatusOf(bridge);
            if (status != null) {
                feed.addAll(status.killFeed());
            }
            return feed;
        }
    }

    /** Reads the bridge's queued spectator banners without widening its public surface. */
    @SuppressWarnings("unchecked")
    private static List<String> pendingBannersOf(CoopBattleBridge bridge) {
        try {
            java.lang.reflect.Field field = CoopBattleBridge.class.getDeclaredField("pendingBanners");
            field.setAccessible(true);
            return new ArrayList<>((java.util.Collection<String>) field.get(bridge));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    /** Reads the bridge's stored spectator snapshot without widening its public surface. */
    private static CoopBattleStatus remoteStatusOf(CoopBattleBridge bridge) {
        try {
            java.lang.reflect.Field field = CoopBattleBridge.class.getDeclaredField("remoteStatus");
            field.setAccessible(true);
            return (CoopBattleStatus) field.get(bridge);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Supplier<String> sequencedIds(String... ids) {
        Iterator<String> iterator = List.of(ids).iterator();
        return () -> iterator.hasNext() ? iterator.next() : "extra-id";
    }

    private static final class RecordingNetService extends CoopNetService {
        private final CoopConnectionRole role;
        private final List<CoopMessages.Message> sent = new ArrayList<>();
        private final Queue<CoopMessages.Message> inbound = new ArrayDeque<>();
        private long seq;

        private RecordingNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public long nextSeq() {
            return ++seq;
        }

        @Override
        public void send(CoopMessages.Message message) {
            sent.add(message);
        }

        @Override
        public CoopMessages.Message pollInbound() {
            return inbound.poll();
        }

        @Override
        public void flushOutbound() {
        }
    }

    // ---- engine proxies ---------------------------------------------------------------------------

    private static CombatEngineAPI combatEngine(List<Object> ships, boolean simulation) {
        return (CombatEngineAPI) Proxy.newProxyInstance(
                CombatEngineAPI.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isInCampaign" -> !simulation;
                    case "isInCampaignSim", "isSimulation" -> simulation;
                    case "getShips" -> ships;
                    case "getContext" -> null;
                    case "getTotalElapsedTime" -> 12.5f;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "combatEngineProxy";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object ship(String id, String hullId, String name, int owner, float hull, float flux,
                               boolean alive, boolean hulk, boolean fighter) {
        ShipHullSpecAPI hullSpec = (ShipHullSpecAPI) Proxy.newProxyInstance(
                ShipHullSpecAPI.class.getClassLoader(),
                new Class<?>[]{ShipHullSpecAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullId" -> hullId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "hullSpecProxy";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return Proxy.newProxyInstance(
                ShipAPI.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getName" -> name;
                    case "getHullSpec" -> hullSpec;
                    case "getOwner" -> owner;
                    case "getHullLevel" -> hull;
                    case "getFluxLevel" -> flux;
                    case "isAlive" -> alive;
                    case "isHulk" -> hulk;
                    case "isFighter" -> fighter;
                    case "isDrone", "isShuttlePod" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "shipProxy:" + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
