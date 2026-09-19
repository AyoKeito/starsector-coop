package coop.net;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.combat.CoopAllyBattleJoin;
import coop.combat.CoopAllyBattleOutcome;
import coop.combat.CoopAllyLossApplier;
import coop.fleet.CoopFleetMirror;
import coop.session.CoopSessionState;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33 pump wiring: the piloting side polls the partner's mirror and reports, the owner side
 * applies what it is told once and says so.
 *
 * <p>The fleet mirror and the owner's fleet are both stood in for, and deliberately at different
 * depths. The mirror is a stub because the other half of the phase owns what fills it in; the
 * owner's fleet is a fake {@link CoopAllyLossApplier.PlayerFleetOps} rather than a stubbed applier,
 * so the real applier still runs and these tests assert what it was actually asked to do.
 */
class CoopAllyBattlePumpTest {

    @BeforeEach
    void clearHud() {
        coop.ui.CoopHudNotice.clear();
    }

    @AfterEach
    void clearGlobals() {
        Global.setSector(null);
        coop.ui.CoopHudNotice.clear();
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    // ---- piloting side ----------------------------------------------------------------------------

    @Test
    void aMirrorJoinIsReportedToItsOwner() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = new CoopNetPump(service, activeHost(), () -> 1000L);
        StubMirror mirror = new StubMirror();
        mirror.joins.add(new CoopAllyBattleJoin("guest-player", "Hegemony Patrol"));
        pump.installFleetMirrorForTest(mirror);

        pump.advance(0f);

        CoopMessages.Message sent = onlyOfType(service, CoopMessages.Type.ALLY_BATTLE_JOIN);
        CoopMessages.AllyBattleJoin join = CoopMessages.parseAllyBattleJoin(sent);
        assertEquals("guest-player", join.ownerPlayerId());
        assertEquals("host-player", join.pilotPlayerId(), "the local player is the one flying it");
        assertEquals("Hegemony Patrol", join.enemySummary());
    }

    @Test
    void aMirrorOutcomeIsReportedWithAFreshLedgerId() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = new CoopNetPump(service, activeHost(), () -> 1000L);
        StubMirror mirror = new StubMirror();
        mirror.outcomes.add(new CoopAllyBattleOutcome("guest-player", List.of("member-1"),
                List.of(new CoopAllyBattleOutcome.Survivor("member-2", 0.5f, 0.6f))));
        mirror.outcomes.add(new CoopAllyBattleOutcome("guest-player", List.of("member-3"), List.of()));
        pump.installFleetMirrorForTest(mirror);

        pump.advance(0f);
        pump.advance(0f);

        List<CoopMessages.Message> sent = ofType(service, CoopMessages.Type.ALLY_BATTLE_RESULT);
        assertEquals(2, sent.size());
        String first = CoopMessages.parseAllyBattleResult(sent.get(0)).ledgerId();
        String second = CoopMessages.parseAllyBattleResult(sent.get(1)).ledgerId();
        assertTrue(first.startsWith("session-a-host-player-"), "ledger id was " + first);
        assertFalse(first.equals(second), "each battle mints its own ledger id");
    }

    /** An empty outcome still goes: it is the owner's only word that the fight is over. */
    @Test
    void anEmptyOutcomeIsStillReported() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        StubMirror mirror = new StubMirror();
        mirror.outcomes.add(new CoopAllyBattleOutcome("host-player", List.of(), List.of()));
        pump.installFleetMirrorForTest(mirror);

        pump.advance(0f);

        CoopMessages.AllyBattleResult result = CoopMessages.parseAllyBattleResult(
                onlyOfType(service, CoopMessages.Type.ALLY_BATTLE_RESULT));
        assertTrue(result.outcome().isEmpty());
        assertEquals("host-player", result.outcome().ownerPlayerId());
        assertEquals("guest-player", result.pilotPlayerId());
    }

    @Test
    void nothingIsSentWithoutALiveSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        // Lobby-accepted only: no handshake, no seed lock, so no gameplay session.
        CoopSessionState session = new CoopSessionState(
                TestSessions.sequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new coop.session.CoopPlayerInfo("guest-player", "Guest"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);
        StubMirror mirror = new StubMirror();
        mirror.joins.add(new CoopAllyBattleJoin("guest-player", "Hegemony Patrol"));
        mirror.outcomes.add(new CoopAllyBattleOutcome("guest-player", List.of("member-1"), List.of()));
        pump.installFleetMirrorForTest(mirror);

        pump.advance(0f);

        assertTrue(ofType(service, CoopMessages.Type.ALLY_BATTLE_JOIN).isEmpty());
        assertTrue(ofType(service, CoopMessages.Type.ALLY_BATTLE_RESULT).isEmpty());
        assertEquals(0, mirror.polls, "the mirror is not even asked before the session is live");
    }

    // ---- owner side -------------------------------------------------------------------------------

    @Test
    void anInboundJoinForThisPlayerShowsTheHudLine() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        service.inbound.add(CoopMessages.allyBattleJoin("session-a", 1L, 1000L,
                "guest-player", "host-player", "Hegemony Patrol"));

        pump.advance(0f);

        assertEquals("Your fleet is fighting alongside Host against Hegemony Patrol",
                coop.ui.CoopHudNotice.current(1000L));
    }

    @Test
    void aJoinWithNoEnemyNameDropsTheAgainstClause() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        service.inbound.add(CoopMessages.allyBattleJoin("session-a", 1L, 1000L,
                "guest-player", "host-player", ""));

        pump.advance(0f);

        assertEquals("Your fleet is fighting alongside Host", coop.ui.CoopHudNotice.current(1000L));
    }

    @Test
    void aJoinForSomebodyElseIsIgnored() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        service.inbound.add(CoopMessages.allyBattleJoin("session-a", 1L, 1000L,
                "some-other-guest", "host-player", "Hegemony Patrol"));

        pump.advance(0f);

        assertEquals("", coop.ui.CoopHudNotice.current(1000L));
    }

    @Test
    void anInboundResultIsAppliedOnceAndBannersOnTheHudAndTheFeed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingUi ui = new RecordingUi();
        Global.setSector(ui.sector());
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        FakeFleet fleet = new FakeFleet();
        fleet.add("member-1", "Wolf ISS Kestrel", 1f, 0.7f);
        fleet.add("member-2", "Lasher ISS Tinker", 1f, 0.7f);
        pump.installAllyFleetOpsForTest(() -> fleet);
        service.inbound.add(result("ledger-1", List.of("member-1"),
                List.of(new CoopAllyBattleOutcome.Survivor("member-2", 0.4f, 0.3f))));

        pump.advance(0f);

        assertEquals(List.of("member-2"), fleet.memberIds(), "the destroyed ship is gone");
        assertEquals(0.4f, fleet.hullFraction("member-2"), 0.0001f);
        assertEquals(0.3f, fleet.cr("member-2"), 0.0001f);
        assertEquals(1, fleet.finishes, "the applier closed the writes out exactly once");
        assertEquals("Your fleet fought alongside Host. Lost: Wolf ISS Kestrel."
                        + " Damaged: Lasher ISS Tinker.",
                coop.ui.CoopHudNotice.current(1000L));
        assertEquals(List.of("Your fleet fought alongside Host. Lost: Wolf ISS Kestrel."
                + " Damaged: Lasher ISS Tinker."), ui.messages);
    }

    @Test
    void aDuplicateLedgerIdChangesNothing() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingUi ui = new RecordingUi();
        Global.setSector(ui.sector());
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        FakeFleet fleet = new FakeFleet();
        fleet.add("member-1", "Wolf ISS Kestrel", 1f, 0.7f);
        fleet.add("member-2", "Lasher ISS Tinker", 1f, 0.7f);
        pump.installAllyFleetOpsForTest(() -> fleet);
        service.inbound.add(result("ledger-1", List.of("member-1"), List.of()));

        pump.advance(0f);
        assertEquals(1, fleet.removes);

        // A resend of the same ledger id, on a new envelope seq so the transport's own reliable
        // dedup cannot be what stops it.
        coop.ui.CoopHudNotice.clear();
        service.inbound.add(result("ledger-1", List.of("member-2"), List.of()));

        pump.advance(0f);

        assertEquals(1, fleet.removes, "the ledger id was already applied");
        assertEquals(List.of("member-2"), fleet.memberIds());
        assertEquals("", coop.ui.CoopHudNotice.current(1000L), "no second banner");
        assertEquals(1, ui.messages.size(), "no second feed line");
    }

    @Test
    void aResultForSomebodyElseIsIgnored() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        FakeFleet fleet = new FakeFleet();
        fleet.add("member-1", "Wolf ISS Kestrel", 1f, 0.7f);
        pump.installAllyFleetOpsForTest(() -> fleet);
        service.inbound.add(CoopMessages.allyBattleResult("session-a", 1L, 1000L, "ledger-1",
                "host-player", new CoopAllyBattleOutcome("some-other-guest", List.of("member-1"),
                        List.of())));

        pump.advance(0f);

        assertEquals(0, fleet.removes);
        assertEquals("", coop.ui.CoopHudNotice.current(1000L));
    }

    /** An empty result still banners, and does not touch the feed: nothing changed to report there. */
    @Test
    void anEmptyResultBannersWithoutAFeedLine() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingUi ui = new RecordingUi();
        Global.setSector(ui.sector());
        CoopNetPump pump = new CoopNetPump(service, activeGuest(), () -> 1000L);
        FakeFleet fleet = new FakeFleet();
        fleet.add("member-1", "Wolf ISS Kestrel", 1f, 0.7f);
        pump.installAllyFleetOpsForTest(() -> fleet);
        service.inbound.add(result("ledger-1", List.of(), List.of()));

        pump.advance(0f);

        assertEquals("Your fleet fought alongside Host and came through untouched.",
                coop.ui.CoopHudNotice.current(1000L));
        assertTrue(ui.messages.isEmpty());
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private static CoopMessages.Message result(String ledgerId, List<String> destroyed,
                                               List<CoopAllyBattleOutcome.Survivor> survivors) {
        return CoopMessages.allyBattleResult("session-a", 1L, 1000L, ledgerId, "host-player",
                new CoopAllyBattleOutcome("guest-player", destroyed, survivors));
    }

    private static CoopSessionState activeHost() {
        CoopSessionState session = TestSessions.activeHostSession();
        session.releaseLobby();
        return session;
    }

    private static CoopSessionState activeGuest() {
        CoopSessionState session = TestSessions.activeGuestSession();
        session.releaseLobby();
        return session;
    }

    private static List<CoopMessages.Message> ofType(RecordingNetService service,
                                                     CoopMessages.Type type) {
        List<CoopMessages.Message> matching = new ArrayList<>();
        for (CoopMessages.Message message : service.sent) {
            if (message.type() == type) {
                matching.add(message);
            }
        }
        return matching;
    }

    private static CoopMessages.Message onlyOfType(RecordingNetService service,
                                                   CoopMessages.Type type) {
        List<CoopMessages.Message> matching = ofType(service, type);
        assertEquals(1, matching.size(), "expected exactly one " + type);
        return matching.get(0);
    }

    /** The mirror half of the phase, stubbed: it hands over what a test queued, once each. */
    private static final class StubMirror extends CoopFleetMirror {
        private final Queue<CoopAllyBattleJoin> joins = new ArrayDeque<>();
        private final Queue<CoopAllyBattleOutcome> outcomes = new ArrayDeque<>();
        private int polls;

        @Override
        public CoopAllyBattleJoin takeAllyBattleJoin() {
            polls++;
            return joins.poll();
        }

        @Override
        public CoopAllyBattleOutcome takeAllyBattleOutcome() {
            return outcomes.poll();
        }
    }

    /** The owner's real fleet, as much of it as the applier touches. */
    private static final class FakeFleet implements CoopAllyLossApplier.PlayerFleetOps {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, Float> hull = new LinkedHashMap<>();
        private final Map<String, Float> cr = new LinkedHashMap<>();
        private int removes;
        private int finishes;

        void add(String id, String name, float hullFraction, float combatReadiness) {
            names.put(id, name);
            hull.put(id, hullFraction);
            cr.put(id, combatReadiness);
        }

        @Override
        public List<String> memberIds() {
            return new ArrayList<>(names.keySet());
        }

        @Override
        public String describe(String memberId) {
            return names.getOrDefault(memberId, memberId);
        }

        @Override
        public float hullFraction(String memberId) {
            return hull.getOrDefault(memberId, 1f);
        }

        @Override
        public float cr(String memberId) {
            return cr.getOrDefault(memberId, 0f);
        }

        @Override
        public boolean remove(String memberId) {
            if (names.remove(memberId) == null) {
                return false;
            }
            hull.remove(memberId);
            cr.remove(memberId);
            removes++;
            return true;
        }

        @Override
        public boolean setHullFraction(String memberId, float value) {
            if (!names.containsKey(memberId)) {
                return false;
            }
            hull.put(memberId, value);
            return true;
        }

        @Override
        public boolean setCr(String memberId, float value) {
            if (!names.containsKey(memberId)) {
                return false;
            }
            cr.put(memberId, value);
            return true;
        }

        @Override
        public void finish() {
            finishes++;
        }
    }

    /** Just enough sector for {@link coop.ui.CoopFeed} to find a campaign UI to post into. */
    private static final class RecordingUi {
        private final List<String> messages = new ArrayList<>();

        SectorAPI sector() {
            CampaignUIAPI ui = (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> {
                        if ("addMessage".equals(method.getName()) && args != null && args.length > 0) {
                            messages.add(String.valueOf(args[0]));
                            return null;
                        }
                        return switch (method.getName()) {
                            case "toString" -> "CampaignUI";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> ProxyDefaults.defaultValue(method.getReturnType());
                        };
                    });
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCampaignUI" -> ui;
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }
    }
}
