package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MonthlyReport;
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.util.MutableValue;
import coop.colony.CoopColonyManagement;
import coop.colony.CoopExpeditionWarning;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import coop.testing.RecordingNetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static coop.testing.ProxyDefaults.defaultValue;
import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;

/**
 * Phase 24 milestone 3 engine glue: the open/close diff the replicator drives, the host/guest sides of
 * {@code COLONY_MGMT}, the month-end income split and {@code COLONY_INCOME}, and the
 * {@code EXPEDITION_WARNING} dispatch. The decisions themselves live in {@code coop.colony} and are
 * tested there; this covers the wiring.
 */
class CoopColonyMgmtReplicatorTest {

    private FakeSector sector;

    @BeforeEach
    void stubGlobals() {
        Global.setSettings(ApiProxies.whiteSettings());
        sector = new FakeSector();
        Global.setSector(sector.proxy());
    }

    @AfterEach
    void clearGlobals() {
        Global.setSector(null);
        Global.setSettings(null);
    }

    // ---- COLONY_MGMT capture -------------------------------------------------------------------

    @Test
    void editingAColonyAndClosingItShipsTheWholeState() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        replicator.onPlayerOpenedMarket(market.proxy(), false);
        market.queue.addToEnd("mining", 60_000);
        market.freePort = true;
        replicator.onPlayerClosedMarket(market.proxy());

        List<CoopMessages.Message> mgmt = of(service, CoopMessages.Type.COLONY_MGMT);
        assertEquals(1, mgmt.size());
        CoopColonyManagement.State state = CoopColonyManagement.decode(
                CoopMessages.requiredPayloadString(mgmt.get(0), "mgmt"));
        assertEquals("market_planet_eos", state.marketId());
        assertEquals("host-player:1", state.reportId());
        assertTrue(state.freePort());
        assertEquals(List.of("mining"),
                state.queue().stream().map(CoopColonyManagement.QueueItem::industryId).toList());
        assertTrue(replicator.colonyMgmtLedger().isApplied("host-player:1"),
                "the ledger entry taken at capture is what kills the host's own echo");
    }

    @Test
    void aColonyVisitThatChangedNothingShipsNothing() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        replicator.onPlayerOpenedMarket(market.proxy(), false);
        replicator.onPlayerClosedMarket(market.proxy());

        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty());
        assertEquals(0, replicator.colonyMgmtDiff().baselineCount());
    }

    @Test
    void captureIsSkippedWhileApplyingARemoteReport() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.onPlayerOpenedMarket(market.proxy(), false);
        market.freePort = true;

        replicator.replayGuard().begin();
        try {
            replicator.onPlayerClosedMarket(market.proxy());
        } finally {
            replicator.replayGuard().end();
        }

        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(),
                "re-driving a remote report must not be recaptured as a fresh edit");
    }

    // ---- COLONY_MGMT poll ----------------------------------------------------------------------

    /**
     * Defect 1, the reason the poll exists: a colony managed from the command/intel UI never docks, so
     * vanilla fires neither {@code reportPlayerOpenedMarket} nor {@code reportPlayerClosedMarket} and
     * the diff can never see the edit. Nothing here opens or closes a market.
     */
    @Test
    void theManagementPollShipsAnEditNoMarketCallbackEverSaw() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        service.sent.clear();

        market.freePort = true;
        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        List<CoopMessages.Message> mgmt = of(service, CoopMessages.Type.COLONY_MGMT);
        assertEquals(1, mgmt.size());
        CoopColonyManagement.State state = CoopColonyManagement.decode(
                CoopMessages.requiredPayloadString(mgmt.get(0), "mgmt"));
        assertTrue(state.freePort());
        assertEquals("host-player:poll:2", state.reportId());
        assertTrue(replicator.colonyMgmtLedger().isApplied("host-player:poll:2"),
                "a polled send takes the same ledger entry the close path does");
    }

    @Test
    void aPollTickThatSeesNoChangeShipsNothing() {
        sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        service.sent.clear();

        clock[0] += 10 * CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();
        clock[0] += 10 * CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty());
    }

    @Test
    void thePollWaitsOutItsInterval() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        service.sent.clear();
        market.freePort = true;

        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS - 1;
        replicator.tickColonyManagement();
        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty());

        clock[0] += 1;
        replicator.tickColonyManagement();
        assertEquals(1, of(service, CoopMessages.Type.COLONY_MGMT).size());
    }

    /**
     * The reconnect baseline. The host re-sends every colony to heal whatever diverged while the
     * channel was down; the guest, whose copy is the derived one, records and stays quiet.
     */
    @Test
    void theHostBaselineSendsEveryColonyOnTheSessionEdgeAndTheGuestDoesNot() {
        sector.addColony("market_planet_eos");
        sector.addColony("market_planet_ithaca");

        RecordingNetService hostService = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator host = new CoopCampaignReplicator(
                hostService, activeHostSession(), () -> 1_000_000L);
        host.registerOn(sector.proxy());
        host.tickColonyManagement();

        assertEquals(2, of(hostService, CoopMessages.Type.COLONY_MGMT).size());

        RecordingNetService guestService = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator guest = new CoopCampaignReplicator(
                guestService, activeGuestSession(), () -> 1_000_000L);
        guest.registerOn(sector.proxy());
        guest.tickColonyManagement();

        assertTrue(of(guestService, CoopMessages.Type.COLONY_MGMT).isEmpty());
        assertEquals(2, guest.colonyMgmtPoll().syncedCount(),
                "the guest still learns what it is holding, so its next real edit ships");
    }

    /**
     * Both engines run the same colony through the same vanilla code, so an engine-driven transition
     * lands on both at once and both polls speak. The apply has to mark the market synced or the two
     * answer each other forever.
     */
    @Test
    void applyingAnInboundReportStopsThePollFromEchoingIt() {
        sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();

        replicator.handle(mgmtMessage("host-player:1"));
        service.sent.clear();

        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(),
                "what we just applied is by definition what the peer already holds");
    }

    /** Both capture routes go through one send helper, so the close path arms the poll too. */
    @Test
    void anEditReportedOnCloseIsNotReportedAgainByThePoll() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        service.sent.clear();

        replicator.onPlayerOpenedMarket(market.proxy(), false);
        market.freePort = true;
        replicator.onPlayerClosedMarket(market.proxy());
        assertEquals(1, of(service, CoopMessages.Type.COLONY_MGMT).size());

        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertEquals(1, of(service, CoopMessages.Type.COLONY_MGMT).size(),
                "the poll must not re-ship what the close already shipped");
    }

    // ---- COLONY_MGMT apply ---------------------------------------------------------------------

    @Test
    void theHostAppliesTheGuestsReportRebroadcastsItAndTheEchoDies() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        CoopMessages.Message inbound = mgmtMessage("guest-player:1");
        replicator.handle(inbound);

        assertTrue(market.freePort, "the host applies to its canonical market");
        assertTrue(market.industries.contains("mining"));
        assertEquals(1, of(service, CoopMessages.Type.COLONY_MGMT).size(),
                "the host rebroadcasts its canonical view");

        market.addIndustryCalls = 0;
        replicator.handle(inbound);

        assertEquals(0, market.addIndustryCalls, "the echo must not rebuild the colony");
        assertTrue(replicator.colonyMgmtLedger().isApplied("guest-player:1"));
        assertEquals(2, of(service, CoopMessages.Type.COLONY_MGMT).size(),
                "but the host keeps rebroadcasting: self-healing");
    }

    @Test
    void theGuestAppliesTheHostsReportAndNeverRebroadcasts() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(mgmtMessage("host-player:1"));

        assertTrue(market.freePort);
        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(), "a guest never rebroadcasts");
    }

    // ---- COLONY_MGMT apply failures --------------------------------------------------------------

    /**
     * The rollback the suppression exists to prevent. An apply that does not reach the engine leaves
     * this client holding the state the peer already moved off; marking that synced would make the
     * next poll tick report it as a fresh change, and the peer would apply their own edit away.
     */
    @Test
    void anApplyThatFailsSuppressesTheMarketInsteadOfReReportingTheStaleState() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();          // the guest baseline tick records and stays quiet
        boolean[] applyFails = {true};
        replicator.setColonyMgmtApplyForTest(state ->
                !applyFails[0] && CoopColonyManagement.applyToEngine(state));

        replicator.handle(mgmtMessage("host-player:1"));

        assertFalse(market.freePort, "nothing reached the engine");
        assertTrue(replicator.colonyMgmtLedger().isApplied("host-player:1"),
                "the report id is still deduped: a failed apply is no licence to re-run it on the echo");
        assertEquals(1, replicator.colonyMgmtPoll().pendingApplyCount());
        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(), "a guest answers nothing");

        // A local edit on top does not lift the suppression: what this engine holds still lacks the
        // industry the report carried, so shipping it would take that industry off the peer.
        market.freePort = true;
        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(),
                "the stale state must never be polled back out");
    }

    /** The normal heal: the next report for the same market applies and the market is live again. */
    @Test
    void aLaterReportThatAppliesClearsTheSuppression() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        boolean[] applyFails = {true};
        replicator.setColonyMgmtApplyForTest(state ->
                !applyFails[0] && CoopColonyManagement.applyToEngine(state));
        replicator.handle(mgmtMessage("host-player:1"));
        assertEquals(1, replicator.colonyMgmtPoll().pendingApplyCount());

        applyFails[0] = false;
        replicator.handle(mgmtMessage("host-player:2"));

        assertEquals(0, replicator.colonyMgmtPoll().pendingApplyCount());
        assertTrue(market.freePort, "the second delivery did reach the engine");

        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(),
                "and what was applied is what the peer already holds, so still nothing to say");
    }

    /**
     * The host rebroadcast is unconditional by design - it is how the report gets its canonical echo -
     * and a local apply failure does not change what the host was told.
     */
    @Test
    void theHostStillRebroadcastsAReportItsOwnEngineCouldNotApply() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();          // the host baseline ships every colony
        service.sent.clear();
        replicator.setColonyMgmtApplyForTest(state -> false);

        replicator.handle(mgmtMessage("guest-player:1"));

        assertEquals(1, of(service, CoopMessages.Type.COLONY_MGMT).size(), "the rebroadcast stands");
        assertFalse(market.freePort);

        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertEquals(1, of(service, CoopMessages.Type.COLONY_MGMT).size(),
                "but the host does not poll its stale copy back at the guest");
    }

    /** The transient this retries for: a market mid-teardown that is fine a couple of seconds later. */
    @Test
    void aFailedApplyIsRetriedOnTheNextPollTick() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        boolean[] applyFails = {true};
        replicator.setColonyMgmtApplyForTest(state ->
                !applyFails[0] && CoopColonyManagement.applyToEngine(state));
        replicator.handle(mgmtMessage("host-player:1"));

        applyFails[0] = false;
        clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
        replicator.tickColonyManagement();

        assertTrue(market.freePort, "the retry landed the report the inbound delivery dropped");
        assertTrue(market.industries.contains("mining"));
        assertEquals(0, replicator.colonyMgmtPoll().pendingApplyCount());
        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty(),
                "and the market it just caught up on has nothing to report");
    }

    @Test
    void theRetriesStopAtTheBudgetButTheSuppressionDoesNot() {
        sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        long[] clock = {1_000_000L};
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> clock[0]);
        replicator.registerOn(sector.proxy());
        replicator.tickColonyManagement();
        int[] attempts = {0};
        replicator.setColonyMgmtApplyForTest(state -> {
            attempts[0]++;
            return false;
        });

        replicator.handle(mgmtMessage("host-player:1"));
        for (int tick = 0; tick < 10; tick++) {
            clock[0] += CoopCampaignReplicator.COLONY_MGMT_POLL_INTERVAL_MILLIS;
            replicator.tickColonyManagement();
        }

        assertEquals(CoopColonyManagement.PENDING_APPLY_ATTEMPTS, attempts[0],
                "the inbound delivery plus its retries, and then it stops trying");
        assertEquals(1, replicator.colonyMgmtPoll().pendingApplyCount(),
                "the state this engine kept is no less stale for the retries having stopped");
        assertTrue(of(service, CoopMessages.Type.COLONY_MGMT).isEmpty());
    }

    /** Session teardown drops the suppression with everything else the poll holds. */
    @Test
    void sessionTeardownDropsAPendingApply() {
        sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());
        replicator.setColonyMgmtApplyForTest(state -> false);
        replicator.handle(mgmtMessage("host-player:1"));
        assertEquals(1, replicator.colonyMgmtPoll().pendingApplyCount());

        replicator.dispose(sector.proxy());

        assertEquals(0, replicator.colonyMgmtPoll().pendingApplyCount());
    }

    // ---- Income ---------------------------------------------------------------------------------

    @Test
    void theHostDeductsItsOwnHalfBannersItAndShipsTheCanonicalFigure() {
        sector.addColony("market_planet_eos");
        sector.credits.set(100_000f);
        settleReport(1_000L, 25_000f);
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        sector.listenerOfType(EconomyTickListener.class).reportEconomyMonthEnd();

        assertEquals(87_500f, sector.credits.get(), "kept exactly half of 25,000");
        assertEquals(1, replicator.pendingIncomeBannerCount());

        replicator.tickColonyIncome();

        assertEquals(0, replicator.pendingIncomeBannerCount());
        assertEquals(List.of("Coop: colony income split - kept 12,500 of 25,000 credits."),
                sector.messages);

        List<CoopMessages.Message> income = of(service, CoopMessages.Type.COLONY_INCOME);
        assertEquals(1, income.size());
        assertEquals(25_000f, CoopMessages.requiredPayloadFloat(income.get(0), "netCredits"));
        assertEquals(1L, CoopMessages.requiredPayloadLong(income.get(0), "colonyCount"));
    }

    /**
     * The whole point of the local-half model: the guest deducts its own half from its own wallet and
     * sends no money anywhere. A transfer on top of this would pay 150%.
     */
    @Test
    void theGuestDeductsItsOwnHalfAndSendsNothing() {
        sector.addColony("market_planet_eos");
        sector.credits.set(100_000f);
        settleReport(1_000L, 25_000f);
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        sector.listenerOfType(EconomyTickListener.class).reportEconomyMonthEnd();

        assertEquals(87_500f, sector.credits.get());
        assertTrue(of(service, CoopMessages.Type.COLONY_INCOME).isEmpty());
    }

    /** No colonies, no money moved, no banner: nothing to say before the first colony exists. */
    @Test
    void aMonthWithNoColoniesIsSilentAndCostsNothing() {
        sector.credits.set(100_000f);
        settleReport(1_000L, 0f);
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        sector.listenerOfType(EconomyTickListener.class).reportEconomyMonthEnd();
        replicator.tickColonyIncome();

        assertEquals(100_000f, sector.credits.get());
        assertEquals(0, replicator.pendingIncomeBannerCount());
        assertTrue(sector.messages.isEmpty());
    }

    /** The host's figure is a drift line on the guest, never a correction. */
    @Test
    void aHostIncomeFigureNeverMovesTheGuestsCredits() {
        sector.credits.set(100_000f);
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(CoopMessages.colonyIncome("session-a", 1L, 0L, 25_000f, 1L));

        assertEquals(100_000f, sector.credits.get());
        assertTrue(service.sent.isEmpty());
    }

    /** Banners are queued, so a month that ends on a frame with no campaign UI is not lost. */
    @Test
    void aBannerSurvivesAFrameWithNoCampaignUi() {
        sector.addColony("market_planet_eos");
        sector.credits.set(100_000f);
        settleReport(1_000L, 25_000f);
        sector.hasCampaignUi = false;
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());
        sector.listenerOfType(EconomyTickListener.class).reportEconomyMonthEnd();

        replicator.tickColonyIncome();
        assertEquals(1, replicator.pendingIncomeBannerCount(), "held, not dropped");

        sector.hasCampaignUi = true;
        replicator.tickColonyIncome();

        assertEquals(0, replicator.pendingIncomeBannerCount());
        assertEquals(1, sector.messages.size());
    }

    // ---- Expedition warnings ---------------------------------------------------------------------

    @Test
    void theGuestStoresAnInboundWarningSet() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(warningMessage(
                new CoopExpeditionWarning(CoopExpeditionWarning.Kind.PUNITIVE_EXPEDITION,
                        "hegemony", "market_planet_eos", "New Hope", 7,
                        CoopExpeditionWarning.Status.INBOUND)));

        assertEquals(1, replicator.desiredExpeditionWarnings().size());
        assertEquals("hegemony", replicator.desiredExpeditionWarnings().get(0).factionId());
        assertTrue(service.sent.isEmpty(), "the guest never answers a warning set");
    }

    @Test
    void anEmptyWarningSetIsALegitimateValueThatClearsEverything() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);
        replicator.handle(warningMessage(
                new CoopExpeditionWarning(CoopExpeditionWarning.Kind.RAID, "pirates",
                        "market_planet_eos", "New Hope", 2, CoopExpeditionWarning.Status.INBOUND)));

        replicator.handle(CoopMessages.expeditionWarning("session-a", 2L, 0L, ""));

        assertTrue(replicator.desiredExpeditionWarnings().isEmpty());
    }

    /** The host is authoritative here; an inbound set must not overwrite what it just scanned. */
    @Test
    void theHostIgnoresAnInboundWarningSet() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        replicator.handle(warningMessage(
                new CoopExpeditionWarning(CoopExpeditionWarning.Kind.RAID, "pirates",
                        "market_planet_eos", "New Hope", 2, CoopExpeditionWarning.Status.INBOUND)));

        assertTrue(replicator.desiredExpeditionWarnings().isEmpty());
    }

    // ---- Session lifecycle -----------------------------------------------------------------------

    @Test
    void sessionTeardownClearsEverythingAndRemovesTheMonthEndListener() {
        FakeMarket market = sector.addColony("market_planet_eos");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());
        replicator.handle(mgmtMessage("guest-player:1"));
        replicator.onPlayerOpenedMarket(market.proxy(), false);
        assertEquals(1, replicator.colonyMgmtLedger().size());
        assertEquals(1, replicator.colonyMgmtDiff().baselineCount());
        assertEquals(1, replicator.colonyMgmtPoll().syncedCount());

        replicator.dispose(sector.proxy());

        assertEquals(0, replicator.colonyMgmtLedger().size());
        assertEquals(0, replicator.colonyMgmtDiff().baselineCount());
        assertEquals(0, replicator.colonyMgmtPoll().syncedCount(),
                "a hash from a dead session says nothing about the next peer");
        assertEquals(0, replicator.pendingIncomeBannerCount());
        assertTrue(replicator.desiredExpeditionWarnings().isEmpty());
        assertNull(sector.listenerOfType(EconomyTickListener.class));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static List<CoopMessages.Message> of(RecordingNetService service, CoopMessages.Type type) {
        List<CoopMessages.Message> found = new ArrayList<>();
        for (CoopMessages.Message message : service.sent) {
            if (message.type() == type) {
                found.add(message);
            }
        }
        return found;
    }

    private static CoopMessages.Message mgmtMessage(String reportId) {
        CoopColonyManagement.State state = new CoopColonyManagement.State(reportId,
                "market_planet_eos", "guest-player", true, false, false, false,
                List.of(new CoopColonyManagement.IndustryState("population", "", false,
                                CoopColonyManagement.BuildState.NONE, "", "", ""),
                        new CoopColonyManagement.IndustryState("mining", "", false,
                                CoopColonyManagement.BuildState.NONE, "", "", "")),
                List.of());
        return CoopMessages.colonyMgmt("session-a", 1L, 0L, state.encode());
    }

    private static CoopMessages.Message warningMessage(CoopExpeditionWarning... warnings) {
        return CoopMessages.expeditionWarning("session-a", 1L, 0L,
                CoopExpeditionWarning.encodeSet(List.of(warnings)));
    }

    private void settleReport(long timestamp, float income) {
        MonthlyReport report = new MonthlyReport();
        report.setTimestamp(timestamp);
        if (income != 0f) {
            MonthlyReport.FDNode node = report.getNode(MonthlyReport.OUTPOSTS, "market_planet_eos");
            node.custom = sector.market("market_planet_eos").proxy();
            node.income = income;
        }
        report.computeTotals();
        SharedData.getData().setPreviousReport(report);
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    private static final class FakeMarket {
        private final String id;
        private boolean freePort;
        private boolean inEconomy = true;
        /** The management poll skips hyperspace, so a colony needs a location that is not it. */
        private final LocationAPI location = (LocationAPI) Proxy.newProxyInstance(
                LocationAPI.class.getClassLoader(),
                new Class<?>[]{LocationAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "star_system";
                    case "isHyperspace" -> false;
                    case "toString" -> "Location";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        private final List<String> industries = new ArrayList<>();
        private final Map<String, Industry> industryProxies = new LinkedHashMap<>();
        private final ConstructionQueue queue = new ConstructionQueue();
        private int addIndustryCalls;
        private MarketAPI cached;

        private FakeMarket(String id) {
            this.id = id;
        }

        void addIndustry(String industryId) {
            if (industries.contains(industryId)) {
                return;
            }
            industries.add(industryId);
            industryProxies.computeIfAbsent(industryId, key -> (Industry) Proxy.newProxyInstance(
                    Industry.class.getClassLoader(),
                    new Class<?>[]{Industry.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> key;
                        case "toString" -> "Industry[" + key + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    }));
            addIndustryCalls++;
        }

        MarketAPI proxy() {
            if (cached != null) {
                return cached;
            }
            cached = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getName" -> id;
                        case "isPlayerOwned" -> true;
                        case "isPlanetConditionMarketOnly" -> false;
                        case "isInEconomy" -> inEconomy;
                        case "getContainingLocation" -> location;
                        case "isFreePort" -> freePort;
                        case "setFreePort" -> {
                            freePort = (Boolean) args[0];
                            yield null;
                        }
                        case "getConstructionQueue" -> queue;
                        case "getIndustries" -> {
                            List<Industry> all = new ArrayList<>();
                            for (String industryId : industries) {
                                all.add(industryProxies.get(industryId));
                            }
                            yield all;
                        }
                        case "hasIndustry" -> industries.contains((String) args[0]);
                        case "getIndustry" -> industries.contains((String) args[0])
                                ? industryProxies.get((String) args[0]) : null;
                        case "addIndustry" -> {
                            addIndustry((String) args[0]);
                            yield null;
                        }
                        case "removeIndustry" -> {
                            industries.remove((String) args[0]);
                            yield null;
                        }
                        case "getPrimaryEntity" -> null;
                        case "toString" -> "Market[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    private static final class FakeSector {
        private final Map<String, FakeMarket> markets = new LinkedHashMap<>();
        private final Map<String, Object> persistentData = new LinkedHashMap<>();
        private final List<Object> listeners = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final MutableValue credits = new MutableValue(0f);
        private boolean hasCampaignUi = true;
        private SectorAPI cached;

        FakeMarket addColony(String marketId) {
            FakeMarket market = new FakeMarket(marketId);
            market.addIndustry("population");
            market.addIndustryCalls = 0;
            markets.put(marketId, market);
            return market;
        }

        FakeMarket market(String marketId) {
            return markets.get(marketId);
        }

        @SuppressWarnings("unchecked")
        <T> T listenerOfType(Class<T> type) {
            for (Object listener : listeners) {
                if (type.isInstance(listener)) {
                    return (T) listener;
                }
            }
            return null;
        }

        SectorAPI proxy() {
            if (cached != null) {
                return cached;
            }
            ListenerManagerAPI listenerManager = ApiProxies.listenerManager(listeners);
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> {
                            FakeMarket market = markets.get((String) args[0]);
                            yield market == null ? null : market.proxy();
                        }
                        case "getMarketsCopy" -> {
                            List<MarketAPI> all = new ArrayList<>();
                            for (FakeMarket market : markets.values()) {
                                all.add(market.proxy());
                            }
                            yield all;
                        }
                        case "toString" -> "Economy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            CargoAPI cargo = (CargoAPI) Proxy.newProxyInstance(
                    CargoAPI.class.getClassLoader(),
                    new Class<?>[]{CargoAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCredits" -> credits;
                        case "toString" -> "Cargo";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            CampaignFleetAPI fleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo" -> cargo;
                        case "toString" -> "Fleet";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            CampaignUIAPI campaignUi = (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addMessage" -> {
                            if (args[0] instanceof String text) {
                                messages.add(text);
                            }
                            yield null;
                        }
                        case "toString" -> "CampaignUI";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            cached = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getListenerManager" -> listenerManager;
                        case "getEconomy" -> economy;
                        case "getPersistentData" -> persistentData;
                        case "getPlayerFleet" -> fleet;
                        case "getCampaignUI" -> hasCampaignUi ? campaignUi : null;
                        case "getAllLocations" -> List.of();
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

}
