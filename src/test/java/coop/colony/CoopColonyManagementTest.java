package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 milestone 3: the colony-management codec, the open/close diff, the ledger and the
 * reconcile. The engine is stood up as interface proxies (no mocking framework in this build), which
 * is enough because the reconcile is a sequence of public reads and setters.
 */
class CoopColonyManagementTest {

    /**
     * Proxying {@code Industry} makes the JDK resolve every type in its signatures — including
     * {@code MarketCMD.RaidDangerLevel}, whose static init asks the settings for highlight colors. Without
     * a stub the proxy class itself fails to initialize, so this is a prerequisite, not decoration.
     */
    @BeforeEach
    void stubSettings() {
        Global.setSettings(ApiProxies.whiteSettings());
    }

    @AfterEach
    void clearGlobals() {
        Global.setSector(null);
        Global.setSettings(null);
    }

    // ---- Codec ---------------------------------------------------------------------------------

    @Test
    void aFullManagementStateRoundTrips() {
        CoopColonyManagement.State state = new CoopColonyManagement.State("host-player:4",
                "market_planet_eos", "host-player", true, false, true, false,
                List.of(new CoopColonyManagement.IndustryState("population", "alpha_core", true,
                                CoopColonyManagement.BuildState.NONE, "", "", ""),
                        new CoopColonyManagement.IndustryState("heavyindustry", "beta_core", false,
                                CoopColonyManagement.BuildState.UPGRADING, "orbitalworks",
                                "corrupted_nanoforge", "42"),
                        new CoopColonyManagement.IndustryState("spaceport", "", false,
                                CoopColonyManagement.BuildState.BUILDING, "", "", "")),
                List.of(new CoopColonyManagement.QueueItem("mining", 60_000),
                        new CoopColonyManagement.QueueItem("farming", 30_000)));

        assertEquals(state, CoopColonyManagement.decode(state.encode()));
    }

    /** Queue order is the build order; it has to survive the wire exactly. */
    @Test
    void theConstructionQueueKeepsItsOrder() {
        CoopColonyManagement.State state = stateWithQueue("host-player:1",
                new CoopColonyManagement.QueueItem("first", 1),
                new CoopColonyManagement.QueueItem("second", 2),
                new CoopColonyManagement.QueueItem("third", 3));

        List<CoopColonyManagement.QueueItem> decoded = CoopColonyManagement.decode(state.encode()).queue();

        assertEquals(List.of("first", "second", "third"),
                decoded.stream().map(CoopColonyManagement.QueueItem::industryId).toList());
        assertEquals(2, decoded.get(1).cost());
    }

    @Test
    void idsCarryingDelimiterCharactersRoundTripExactly() {
        CoopColonyManagement.State state = new CoopColonyManagement.State("pipe|player\\:1",
                "market|planet\neos", "acting\\player", true, true, true, true,
                List.of(new CoopColonyManagement.IndustryState("ind|ustry", "core\\id", true,
                        CoopColonyManagement.BuildState.UPGRADING, "up\ngrade", "spec|ial",
                        "data\\value")),
                List.of(new CoopColonyManagement.QueueItem("queued|id", -5)));

        CoopColonyManagement.State decoded = CoopColonyManagement.decode(state.encode());

        assertEquals(state, decoded);
        assertEquals("market|planet\neos", decoded.marketId());
        assertEquals("up\ngrade", decoded.industries().get(0).upgradeId());
        assertEquals("queued|id", decoded.queue().get(0).industryId());
    }

    @Test
    void allFourColonyTogglesSurviveIndependently() {
        CoopColonyManagement.State state = new CoopColonyManagement.State("a:1", "m", "a",
                true, false, true, false, List.of(), List.of());

        CoopColonyManagement.State decoded = CoopColonyManagement.decode(state.encode());

        assertTrue(decoded.freePort());
        assertFalse(decoded.immigrationClosed());
        assertTrue(decoded.immigrationIncentives());
        assertFalse(decoded.useStockpilesForShortages());
    }

    @Test
    void decodeRejectsMalformedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> CoopColonyManagement.decode(""));
        assertThrows(IllegalArgumentException.class, () -> CoopColonyManagement.decode("H|a|b"));
        assertThrows(IllegalArgumentException.class, () -> CoopColonyManagement.decode(
                emptyState("a:1").encode() + "\nX|nope"));
        assertThrows(IllegalArgumentException.class, () -> CoopColonyManagement.decode(
                emptyState("a:1").encode() + "\nI|too|few"));
        assertThrows(IllegalArgumentException.class, () -> CoopColonyManagement.decode(
                stateWithQueue("a:1", new CoopColonyManagement.QueueItem("mining", 1))
                        .encode().replace("|1", "|not-a-number")));
    }

    @Test
    void anUnknownBuildStateIsRejectedRatherThanSilentlyDefaulted() {
        String encoded = new CoopColonyManagement.State("a:1", "m", "a", false, false, false, false,
                List.of(new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.BUILDING, "", "", "")),
                List.of()).encode();

        assertThrows(IllegalArgumentException.class,
                () -> CoopColonyManagement.decode(encoded.replace("BUILDING", "DEMOLISHING")));
    }

    // ---- Ledger --------------------------------------------------------------------------------

    @Test
    void theLedgerAppliesEachReportOnceAndClearsOnTeardown() {
        CoopColonyManagement.Ledger ledger = new CoopColonyManagement.Ledger();
        CoopColonyManagement.State first = emptyState("guest-player:1");

        assertTrue(ledger.apply(first));
        assertFalse(ledger.apply(first), "the host's verbatim echo must not re-apply");
        assertTrue(ledger.apply(emptyState("guest-player:2")));
        assertTrue(ledger.isApplied("guest-player:1"));
        assertEquals(2, ledger.size());

        ledger.clear();

        assertEquals(0, ledger.size());
        assertFalse(ledger.isApplied("guest-player:1"));
    }

    // ---- Diff ----------------------------------------------------------------------------------

    @Test
    void aCloseThatChangedNothingProducesNoReport() {
        FakeMarket market = colony();
        CoopColonyManagement.Diff diff = new CoopColonyManagement.Diff();

        diff.onOpened("host-player", market.proxy());
        assertEquals(1, diff.baselineCount());

        assertNull(diff.onClosed("host-player", market.proxy()));
        assertEquals(0, diff.baselineCount(), "the baseline is consumed either way");
    }

    @Test
    void queueingAnIndustryProducesAReportWithAFreshId() {
        FakeMarket market = colony();
        CoopColonyManagement.Diff diff = new CoopColonyManagement.Diff();
        diff.onOpened("host-player", market.proxy());

        market.queue.addToEnd("mining", 60_000);
        CoopColonyManagement.State state = diff.onClosed("host-player", market.proxy());

        assertEquals("host-player:1", state.reportId());
        assertEquals("market_planet_eos", state.marketId());
        assertEquals(List.of("mining"),
                state.queue().stream().map(CoopColonyManagement.QueueItem::industryId).toList());
        assertEquals(60_000, state.queue().get(0).cost());
    }

    @Test
    void everyCapturedFieldIsAChangeTheDiffDetects() {
        assertReported(market -> market.freePort = !market.freePort, "free port");
        assertReported(market -> market.immigrationClosed = true, "immigration closed");
        assertReported(market -> market.immigrationIncentives = true, "immigration incentives");
        assertReported(market -> market.useStockpiles = true, "stockpiles for shortages");
        assertReported(market -> market.industry("population").aiCoreId = "alpha_core", "AI core");
        assertReported(market -> market.industry("population").improved = true, "improvement");
        assertReported(market -> market.industry("population").special =
                new SpecialItemData("corrupted_nanoforge", null), "special item");
        assertReported(market -> market.industry("population").building = true, "build state");
        assertReported(market -> market.addIndustry("mining"), "a new industry");
        assertReported(market -> market.industries.remove("spaceport"), "a removed industry");
    }

    /** A market that is not a player colony is Phase 12's business, not this channel's. */
    @Test
    void anNpcMarketIsNeverBaselinedOrReported() {
        FakeMarket market = colony();
        market.playerOwned = false;
        CoopColonyManagement.Diff diff = new CoopColonyManagement.Diff();

        diff.onOpened("host-player", market.proxy());

        assertEquals(0, diff.baselineCount());
        market.freePort = true;
        assertNull(diff.onClosed("host-player", market.proxy()));
    }

    /** No baseline, no report: a close this client never opened must not overwrite the peer. */
    @Test
    void aCloseWithoutAnOpenReportsNothing() {
        FakeMarket market = colony();
        CoopColonyManagement.Diff diff = new CoopColonyManagement.Diff();

        market.freePort = true;

        assertNull(diff.onClosed("host-player", market.proxy()));
    }

    @Test
    void resetDropsBaselinesAndRestartsTheIdCounter() {
        FakeMarket market = colony();
        CoopColonyManagement.Diff diff = new CoopColonyManagement.Diff();
        diff.onOpened("host-player", market.proxy());

        diff.reset();

        assertEquals(0, diff.baselineCount());
        assertNull(diff.onClosed("host-player", market.proxy()));

        diff.onOpened("host-player", market.proxy());
        market.freePort = true;
        assertEquals("host-player:1", diff.onClosed("host-player", market.proxy()).reportId());
    }

    // ---- Apply ---------------------------------------------------------------------------------

    @Test
    void applyingAReportReconcilesTheMirrorAndIsIdempotent() {
        FakeMarket market = colony();
        market.addIndustry("orbitalstation");            // present here, absent from the report
        market.industry("population").aiCoreId = "gamma_core";
        market.queue.addToEnd("stale", 1);

        CoopColonyManagement.State state = new CoopColonyManagement.State("guest-player:1",
                "market_planet_eos", "guest-player", true, false, false, true,
                List.of(new CoopColonyManagement.IndustryState("population", "alpha_core", true,
                                CoopColonyManagement.BuildState.NONE, "", "", ""),
                        new CoopColonyManagement.IndustryState("spaceport", "", false,
                                CoopColonyManagement.BuildState.NONE, "", "", ""),
                        new CoopColonyManagement.IndustryState("mining", "", false,
                                CoopColonyManagement.BuildState.BUILDING, "", "", "")),
                List.of(new CoopColonyManagement.QueueItem("farming", 30_000)));

        CoopColonyManagement.applyToMarket(market.proxy(), state);

        assertEquals(List.of("population", "spaceport", "mining"), market.industries);
        assertEquals("alpha_core", market.industry("population").aiCoreId);
        assertTrue(market.industry("population").improved);
        assertTrue(market.industry("mining").building, "a freshly added building industry starts building");
        assertTrue(market.freePort);
        assertTrue(market.useStockpiles);
        assertEquals(List.of("farming"), queueIds(market));
        assertTrue(market.reappliedIndustries);

        market.reappliedIndustries = false;
        market.addIndustryCalls = 0;
        market.removeIndustryCalls = 0;
        CoopColonyManagement.applyToMarket(market.proxy(), state);

        assertEquals(0, market.addIndustryCalls, "a second apply must write nothing");
        assertEquals(0, market.removeIndustryCalls);
        assertFalse(market.reappliedIndustries);
        assertEquals(List.of("farming"), queueIds(market));
    }

    /** The only forcing direction: the report says finished, so the lagging mirror finishes too. */
    @Test
    void anIndustryTheReportSaysIsFinishedIsFinishedOnTheMirror() {
        FakeMarket market = colony();
        market.industry("population").building = true;

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertFalse(market.industry("population").building);
        assertEquals(1, market.industry("population").finishCalls);
    }

    /**
     * The other direction is deliberately not forced: restarting a finished build would unapply a
     * working industry to re-run a timer.
     */
    @Test
    void anIndustryTheMirrorAlreadyFinishedIsNotRebuilt() {
        FakeMarket market = colony();

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.BUILDING, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertFalse(market.industry("population").building);
        assertEquals(0, market.industry("population").startBuildCalls);
    }

    @Test
    void anUpgradeInProgressStartsOnTheMirror() {
        FakeMarket market = colony();
        market.addIndustry("heavyindustry");
        market.industry("heavyindustry").upgradeTarget = "orbitalworks";

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("heavyindustry", "", false,
                        CoopColonyManagement.BuildState.UPGRADING, "orbitalworks", "", "")));

        assertTrue(market.industry("heavyindustry").upgrading);
    }

    /**
     * The upgrade race the {@code upgradeId} field exists for: the mirror already finished the
     * upgrade, so the reported industry is "missing" and the upgraded one is "extra". Without the
     * guard the reconcile would downgrade the colony.
     */
    @Test
    void aMirrorThatAlreadyFinishedTheUpgradeIsNotDowngraded() {
        FakeMarket market = colony();
        market.addIndustry("orbitalworks");

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("heavyindustry", "", false,
                        CoopColonyManagement.BuildState.UPGRADING, "orbitalworks", "", "")));

        assertTrue(market.industries.contains("orbitalworks"), "the finished upgrade survives");
        assertFalse(market.industries.contains("heavyindustry"), "and is not un-done");
    }

    /**
     * A genuine finished upgrade renames the industry, so it is reported under the upgrade target and
     * handled by the industry set difference. "Same id, report NONE, mirror UPGRADING" can therefore
     * only be the colony screen's Cancel button - which refunded the acting player - and finishing it
     * here would hand both engines a free Orbital Works nobody paid for.
     */
    @Test
    void anUpgradeTheActingPlayerCancelledIsCancelledOnTheMirrorNotCompleted() {
        FakeMarket market = colony();
        market.addIndustry("heavyindustry");
        market.industry("heavyindustry").upgradeTarget = "orbitalworks";
        market.industry("heavyindustry").upgrading = true;

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("heavyindustry", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertEquals(1, market.industry("heavyindustry").cancelUpgradeCalls);
        assertEquals(0, market.industry("heavyindustry").finishCalls,
                "finishing it would build the upgrade target for free");
        assertFalse(market.industry("heavyindustry").upgrading);
        assertTrue(market.industries.contains("heavyindustry"), "and the industry stays as it was");
        assertFalse(market.industries.contains("orbitalworks"));
    }

    /** The other NONE case is unchanged: a first-time build keeps its id, so finishing it is right. */
    @Test
    void aBuildTheReportSaysFinishedIsStillFinishedNotCancelled() {
        FakeMarket market = colony();
        market.addIndustry("mining");
        market.industry("mining").building = true;

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("mining", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertEquals(1, market.industry("mining").finishCalls);
        assertEquals(0, market.industry("mining").cancelUpgradeCalls);
    }

    /** Removal passes a null interaction mode: no credit refund, no core returned, no announcement. */
    @Test
    void removalIsSilentAndRefundsNothing() {
        FakeMarket market = colony();
        market.addIndustry("mining");

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertFalse(market.industries.contains("mining"));
        assertEquals(1, market.removeIndustryCalls);
        assertNull(market.lastRemoveMode, "null mode is what makes removeIndustry silent");
        assertFalse(market.lastRemoveForUpgrade);
    }

    @Test
    void aSpecialItemIsInstalledClearedAndLeftAloneWhenAlreadyRight() {
        FakeMarket market = colony();

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "corrupted_nanoforge", "seven"),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertEquals("corrupted_nanoforge", market.industry("population").special.getId());
        assertEquals("seven", market.industry("population").special.getData());
        assertEquals(1, market.industry("population").setSpecialCalls);

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:2",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "corrupted_nanoforge", "seven"),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));
        assertEquals(1, market.industry("population").setSpecialCalls, "an unchanged item is not rewritten");

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:3",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));
        assertNull(market.industry("population").special);
    }

    @Test
    void anIdenticalQueueIsNotRewritten() {
        FakeMarket market = colony();
        market.queue.addToEnd("mining", 60_000);
        ConstructionQueue.ConstructionQueueItem original = market.queue.getItems().get(0);

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithQueue("host-player:1",
                new CoopColonyManagement.QueueItem("mining", 60_000)));

        assertSameInstance(original, market.queue.getItems().get(0));
    }

    @Test
    void aReorderedQueueIsRewrittenInTheReportedOrder() {
        FakeMarket market = colony();
        market.queue.addToEnd("mining", 1);
        market.queue.addToEnd("farming", 2);

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithQueue("host-player:1",
                new CoopColonyManagement.QueueItem("farming", 2),
                new CoopColonyManagement.QueueItem("mining", 1)));

        assertEquals(List.of("farming", "mining"), queueIds(market));
    }

    /** A report for a market this engine has never heard of is logged and dropped, never thrown. */
    @Test
    void aReportForAnUnknownMarketIsDropped() {
        Global.setSector(null);

        CoopColonyManagement.applyToEngine(emptyState("host-player:1"));
    }

    /**
     * The abandonment race. The capture side only ever reports managed colonies, so an unmanaged
     * market on the apply side means the report lost a race with a teardown - and the planet keeps the
     * link to its planet-condition market, so {@code resolveMarket}'s fallback still finds it.
     * Reconciling would hang industries, a construction queue and a free port on an uncolonized
     * planet, and the poll skips unmanaged markets so nothing would ever converge it back.
     */
    @Test
    void aReportForAMarketThatIsNoLongerAColonyHereIsDropped() {
        FakeMarket market = colony();
        market.playerOwned = false;
        market.planetConditionMarketOnly = true;
        Global.setSector(sectorWith(market));

        CoopColonyManagement.applyToEngine(new CoopColonyManagement.State("host-player:1",
                "market_planet_eos", "host-player", true, false, false, false,
                List.of(new CoopColonyManagement.IndustryState("mining", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")),
                List.of(new CoopColonyManagement.QueueItem("heavyindustry", 200_000))));

        assertFalse(market.freePort, "an uncolonized planet is not given a free port");
        assertEquals(List.of("population", "spaceport"), List.copyOf(market.industries));
        assertEquals(List.of(), queueIds(market));
    }

    // ---- Build state: vanilla's overridden predicates ------------------------------------------

    /**
     * The live 2026-08-25 defect at the capture end. {@code PopulationAndInfrastructure} reports any
     * colony below its maximum size as upgrading, so the naive reading shipped
     * "population is UPGRADING" for every ordinary colony — and population has no upgrade at all.
     */
    @Test
    void aColonyGrowingTowardItsMaxSizeIsNotCapturedAsUpgrading() {
        FakeMarket market = colony();
        market.industry("population").growing = true;

        CoopColonyManagement.State state =
                CoopColonyManagement.capture("a:1", "host-player", market.proxy());

        assertEquals(CoopColonyManagement.BuildState.NONE, state.industries().get(0).buildState());
        assertEquals("", state.industries().get(0).upgradeId());
    }

    /** The disambiguation must not cost the two states that are real. */
    @Test
    void aRealBuildAndARealUpgradeAreStillCapturedFaithfully() {
        FakeMarket market = colony();
        market.industry("spaceport").building = true;
        market.addIndustry("heavyindustry");
        market.industry("heavyindustry").upgradeTarget = "orbitalworks";
        market.industry("heavyindustry").upgrading = true;

        CoopColonyManagement.State state =
                CoopColonyManagement.capture("a:1", "host-player", market.proxy());

        assertEquals(CoopColonyManagement.BuildState.BUILDING, state.industries().get(1).buildState());
        assertEquals(CoopColonyManagement.BuildState.UPGRADING, state.industries().get(2).buildState());
        assertEquals("orbitalworks", state.industries().get(2).upgradeId());
    }

    /**
     * The same defect at the apply end: finishing that false "build" fires vanilla's
     * "construction completed" message and pops the next construction-queue entry early.
     */
    @Test
    void aGrowingColonyIsNeverToldItsConstructionIsFinished() {
        FakeMarket market = colony();
        market.industry("population").growing = true;

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertEquals(0, market.industry("population").finishCalls);
        assertTrue(market.industry("population").growing, "vanilla's growth is not ours to end");
    }

    /**
     * The exact live state: the host had population finished and a spaceport freshly popped out of the
     * construction queue, its capture (before the fix) called that population "UPGRADING", and the
     * mirror — which held population only — threw on {@code startUpgrading} and lost the whole
     * reconcile: no spaceport, and every industry behind the thrower skipped.
     */
    @Test
    void theLiveReportThatAbortedAColonyReconcileNowCompletesIt() {
        FakeMarket market = new FakeMarket("market_penelope2");
        market.addIndustry("population");
        market.industry("population").growing = true;

        CoopColonyManagement.State inbound = new CoopColonyManagement.State(
                "67c48e97:1", "market_penelope2", "host-player", true, false, false, false,
                List.of(new CoopColonyManagement.IndustryState("population", "", false,
                                CoopColonyManagement.BuildState.UPGRADING, "", "", ""),
                        new CoopColonyManagement.IndustryState("spaceport", "", false,
                                CoopColonyManagement.BuildState.BUILDING, "", "", "")),
                List.of());

        CoopColonyManagement.applyToMarket(market.proxy(), inbound);

        assertEquals(List.of("population", "spaceport"), market.industries);
        assertTrue(market.industry("spaceport").building, "the industry behind the thrower reconciles");
        assertEquals(0, market.industry("population").startUpgradeCalls);
        assertTrue(market.freePort, "and the toggles land");
    }

    /** Same guard, stated directly: {@code canUpgrade()} is a hardcoded true and guards nothing. */
    @Test
    void anUpgradeReportedForASpecWithNoUpgradeIsNotAppliedAndDoesNotThrow() {
        FakeMarket market = colony();

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.UPGRADING, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertEquals(0, market.industry("population").startUpgradeCalls);
        assertFalse(market.industry("population").upgrading);
    }

    @Test
    void oneFailingIndustryDoesNotStarveTheRest() {
        FakeMarket market = colony();
        market.industry("population").broken = true;

        CoopColonyManagement.applyToMarket(market.proxy(), stateWithIndustries("host-player:1",
                new CoopColonyManagement.IndustryState("population", "alpha_core", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("spaceport", "beta_core", false,
                        CoopColonyManagement.BuildState.NONE, "", "", ""),
                new CoopColonyManagement.IndustryState("mining", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")));

        assertEquals("beta_core", market.industry("spaceport").aiCoreId);
        assertTrue(market.industries.contains("mining"));
    }

    // ---- Poll ----------------------------------------------------------------------------------

    @Test
    void contentHashIgnoresTheReportIdAndTheActingPlayer() {
        CoopColonyManagement.State host = new CoopColonyManagement.State("host-player:7",
                "market_planet_eos", "host-player", true, false, false, false,
                List.of(new CoopColonyManagement.IndustryState("population", "", false,
                        CoopColonyManagement.BuildState.NONE, "", "", "")),
                List.of(new CoopColonyManagement.QueueItem("mining", 60_000)));
        CoopColonyManagement.State guest = new CoopColonyManagement.State("guest-player:poll:2",
                "market_planet_eos", "guest-player", true, false, false, false,
                host.industries(), host.queue());

        assertEquals(host.contentHash(), guest.contentHash());
        assertFalse(host.contentHash().equals(withFreePortFlipped(host).contentHash()),
                "but a real content difference still shows");
    }

    /**
     * The whole point of defect 1: a colony managed from the command UI docks nowhere and fires
     * neither market callback, so nothing but a poll can ever see the edit.
     */
    @Test
    void thePollShipsARemoteEditWithNoMarketOpenOrClose() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);   // consume the baseline tick

        market.freePort = true;
        List<CoopColonyManagement.State> reports =
                poll.poll("host-player", List.of(market.proxy()), false);

        assertEquals(1, reports.size());
        assertEquals("market_planet_eos", reports.get(0).marketId());
        assertEquals("host-player:poll:1", reports.get(0).reportId());
        assertTrue(reports.get(0).freePort());
    }

    @Test
    void thePollIsSilentWhenNothingChanged() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);

        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty());
        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty());
    }

    /**
     * The suppression that matters: an engine-driven transition fires on both engines at once, so both
     * polls speak once. Applying the peer's content-identical state has to mark the market synced, or
     * the two keep answering each other forever.
     */
    @Test
    void anEngineDrivenTransitionOnBothSidesConvergesWithoutPingPong() {
        FakeMarket local = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(local.proxy()), false);

        // Both engines pop the queued spaceport into a build in the same beat.
        local.addIndustry("mining");
        local.industry("mining").building = true;
        List<CoopColonyManagement.State> ours = poll.poll("host-player", List.of(local.proxy()), false);
        assertEquals(1, ours.size(), "the local transition is reported once");
        poll.markSynced(ours.get(0));

        // The peer's report of the same transition arrives; it is content-identical.
        CoopColonyManagement.State theirs = CoopColonyManagement.capture("guest-player:poll:1",
                "guest-player", local.proxy());
        assertEquals(ours.get(0).contentHash(), theirs.contentHash());
        poll.markSynced(theirs);

        assertTrue(poll.poll("host-player", List.of(local.proxy()), false).isEmpty(),
                "nothing bounces back");
    }

    @Test
    void theHostBaselineSendsEveryColonyOnASessionEdgeAndTheGuestDoesNot() {
        FakeMarket first = colony();
        FakeMarket second = new FakeMarket("market_planet_ithaca");
        second.addIndustry("population");
        List<MarketAPI> colonies = List.of(first.proxy(), second.proxy());

        CoopColonyManagement.Poll host = new CoopColonyManagement.Poll();
        assertEquals(2, host.poll("host-player", colonies, true).size(),
                "the host heals divergence accumulated while the channel was down");
        assertTrue(host.poll("host-player", colonies, true).isEmpty(),
                "and only on the baseline tick");

        CoopColonyManagement.Poll guest = new CoopColonyManagement.Poll();
        assertTrue(guest.poll("guest-player", colonies, false).isEmpty(),
                "the host is canonical on reconnect");
        assertEquals(2, guest.syncedCount(), "but the guest still records what it saw");

        first.freePort = true;
        assertEquals(1, guest.poll("guest-player", colonies, false).size(),
                "so the guest's normal change-driven sends resume");
    }

    @Test
    void anNpcMarketIsNeverPolled() {
        FakeMarket market = colony();
        market.playerOwned = false;
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();

        assertTrue(poll.poll("host-player", List.of(market.proxy()), true).isEmpty());
        assertEquals(0, poll.syncedCount());
    }

    @Test
    void resettingThePollDropsEveryKnownSyncedHashAndRearmsTheBaseline() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);
        assertEquals(1, poll.syncedCount());

        poll.reset();

        assertEquals(0, poll.syncedCount());
        List<CoopColonyManagement.State> baseline =
                poll.poll("host-player", List.of(market.proxy()), true);
        assertEquals(1, baseline.size());
        assertEquals("host-player:poll:1", baseline.get(0).reportId(), "the id counter restarts");
    }

    // ---- Poll: a failed apply must not turn into a rollback ------------------------------------

    /**
     * The defect this suppression exists for: an inbound report that never reached the engine leaves
     * this side holding the state the peer has already moved off. If the poll reports it, the peer
     * applies it and their own edit is rolled back — a failed apply would be worse than a skipped one.
     */
    @Test
    void aMarketWithAnUnappliedReportIsNotPolledAtAll() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);

        poll.markPendingApply(peerBuiltAMine(market));

        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty(),
                "the engine is still on the pre-report state; reporting it would undo the peer's build");
        assertEquals(1, poll.pendingApplyCount());
    }

    /**
     * And it stays quiet through a local edit, because the local state still carries the half the
     * report failed to apply: shipping it would take the peer's mine away and add the toggle.
     */
    @Test
    void aSuppressedMarketStaysQuietThroughALocalEditToo() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);
        poll.markPendingApply(peerBuiltAMine(market));

        market.freePort = true;

        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty());
    }

    /** The engine getting there on its own is the second way out, and it needs no message. */
    @Test
    void anEngineThatReachesTheUnappliedContentByItselfClearsTheSuppression() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);
        poll.markPendingApply(peerBuiltAMine(market));

        market.addIndustry("mining");

        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty(),
                "that content is by definition what the peer already holds");
        assertEquals(0, poll.pendingApplyCount());

        market.freePort = true;
        List<CoopColonyManagement.State> reports =
                poll.poll("host-player", List.of(market.proxy()), false);

        assertEquals(1, reports.size(), "and normal change reporting resumes");
        assertTrue(reports.get(0).freePort());
    }

    /** A later report that does apply supersedes the one that did not. */
    @Test
    void aSuccessfulApplyForTheSameMarketClearsTheSuppression() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);
        poll.markPendingApply(peerBuiltAMine(market));

        market.addIndustry("mining");
        market.freePort = true;
        poll.markSynced(CoopColonyManagement.capture("guest-player:poll:2", "guest-player",
                market.proxy()));

        assertEquals(0, poll.pendingApplyCount());
        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty());
    }

    @Test
    void theRetryBudgetIsSpentOncePerCallAndThenTheReportIsAbandoned() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        CoopColonyManagement.State pending = peerBuiltAMine(market);
        poll.markPendingApply(pending);

        int retries = 0;
        for (int tick = 0; tick < 10; tick++) {
            retries += poll.pendingApplyRetries().size();
            poll.markPendingApply(pending);   // the retry failed again
        }

        assertEquals(CoopColonyManagement.PENDING_APPLY_ATTEMPTS - 1, retries,
                "the inbound delivery that failed is the first of the attempts");
        assertFalse(poll.canRetryPendingApply("market_planet_eos"));
        assertEquals(1, poll.pendingApplyCount(), "giving up on the apply is not giving up on the suppression");
        assertTrue(poll.poll("host-player", List.of(market.proxy()), false).isEmpty());
    }

    /** A session edge is a clean slate for the suppression exactly as it is for the hashes. */
    @Test
    void armingTheBaselineDropsEveryPendingApply() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);
        poll.markPendingApply(peerBuiltAMine(market));

        poll.armBaseline();

        assertEquals(0, poll.pendingApplyCount());
        assertEquals(1, poll.poll("host-player", List.of(market.proxy()), true).size(),
                "the host baseline still heals every colony");
    }

    @Test
    void resettingThePollDropsEveryPendingApply() {
        FakeMarket market = colony();
        CoopColonyManagement.Poll poll = new CoopColonyManagement.Poll();
        poll.poll("host-player", List.of(market.proxy()), false);
        poll.markPendingApply(peerBuiltAMine(market));

        poll.reset();

        assertEquals(0, poll.pendingApplyCount());
        assertEquals(0, poll.syncedCount());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * The state the peer reported and this engine failed to apply: this colony plus a mine. Built by
     * adding the industry, capturing, and taking it away again, so the content is byte-identical to
     * what this engine would capture if the apply had worked.
     */
    private static CoopColonyManagement.State peerBuiltAMine(FakeMarket market) {
        market.addIndustry("mining");
        CoopColonyManagement.State reported = CoopColonyManagement.capture("guest-player:poll:1",
                "guest-player", market.proxy());
        market.industries.remove("mining");
        return reported;
    }

    private static CoopColonyManagement.State withFreePortFlipped(CoopColonyManagement.State state) {
        return new CoopColonyManagement.State(state.reportId(), state.marketId(),
                state.actingPlayerId(), !state.freePort(), state.immigrationClosed(),
                state.immigrationIncentives(), state.useStockpilesForShortages(), state.industries(),
                state.queue());
    }

    private static void assertSameInstance(Object expected, Object actual) {
        assertTrue(expected == actual, "expected the same instance");
    }

    private static void assertReported(java.util.function.Consumer<FakeMarket> edit, String what) {
        FakeMarket market = colony();
        CoopColonyManagement.Diff diff = new CoopColonyManagement.Diff();
        diff.onOpened("host-player", market.proxy());

        edit.accept(market);

        assertTrue(diff.onClosed("host-player", market.proxy()) != null,
                "a change to " + what + " has to produce a report");
    }

    private static List<String> queueIds(FakeMarket market) {
        List<String> ids = new ArrayList<>();
        for (ConstructionQueue.ConstructionQueueItem item : market.queue.getItems()) {
            ids.add(item.id);
        }
        return ids;
    }

    private static CoopColonyManagement.State emptyState(String reportId) {
        return new CoopColonyManagement.State(reportId, "market_planet_eos", "host-player",
                false, false, false, false, List.of(), List.of());
    }

    private static CoopColonyManagement.State stateWithQueue(String reportId,
                                                             CoopColonyManagement.QueueItem... items) {
        return new CoopColonyManagement.State(reportId, "market_planet_eos", "host-player",
                false, false, false, false, List.of(), List.of(items));
    }

    private static CoopColonyManagement.State stateWithIndustries(
            String reportId, CoopColonyManagement.IndustryState... industries) {
        return new CoopColonyManagement.State(reportId, "market_planet_eos", "host-player",
                false, false, false, false, List.of(industries), List.of());
    }

    private static SectorAPI sectorWith(FakeMarket market) {
        MarketAPI marketProxy = market.proxy();
        EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                EconomyAPI.class.getClassLoader(),
                new Class<?>[]{EconomyAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMarket" -> market.id.equals(args[0]) ? marketProxy : null;
                    case "toString" -> "Economy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEconomy" -> economy;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static FakeMarket colony() {
        FakeMarket market = new FakeMarket("market_planet_eos");
        market.addIndustry("population");
        market.addIndustry("spaceport");
        market.addIndustryCalls = 0;
        return market;
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    /** Mutable industry state behind an {@link Industry} proxy. */
    private static final class FakeIndustry {
        private final String id;
        private String aiCoreId;
        private boolean improved;
        private boolean building;
        private boolean upgrading;
        /**
         * {@code PopulationAndInfrastructure}'s growth override: a colony below its maximum size
         * reports {@code isUpgrading() == true} with no upgrade in its spec, and (once the growth
         * fraction is above zero) {@code isBuilding() == true} as well, while nothing is being built
         * ({@code PopulationAndInfrastructure.java:606-617}).
         */
        private boolean growing;
        /** Test seam: an industry whose reconcile throws, standing in for any engine-side blow-up. */
        private boolean broken;
        private SpecialItemData special;
        private String upgradeTarget;
        private int finishCalls;
        private int startBuildCalls;
        private int startUpgradeCalls;
        private int cancelUpgradeCalls;
        private int setSpecialCalls;
        private Industry cached;

        private FakeIndustry(String id) {
            this.id = id;
        }

        Industry proxy() {
            if (cached != null) {
                return cached;
            }
            IndustrySpecAPI spec = (IndustrySpecAPI) Proxy.newProxyInstance(
                    IndustrySpecAPI.class.getClassLoader(),
                    new Class<?>[]{IndustrySpecAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getUpgrade" -> upgradeTarget;
                        case "toString" -> "Spec[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            cached = (Industry) Proxy.newProxyInstance(
                    Industry.class.getClassLoader(),
                    new Class<?>[]{Industry.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getSpec" -> spec;
                        case "getAICoreId" -> {
                            if (broken) {
                                throw new IllegalStateException("industry " + id + " is broken");
                            }
                            yield aiCoreId;
                        }
                        case "setAICoreId" -> {
                            aiCoreId = (String) args[0];
                            yield null;
                        }
                        case "isImproved" -> improved;
                        case "setImproved" -> {
                            improved = (Boolean) args[0];
                            yield null;
                        }
                        case "isBuilding" -> building || upgrading;
                        case "isUpgrading" -> upgrading || growing;
                        // BaseIndustry.canUpgrade() is a hardcoded `return true`
                        // (BaseIndustry.java:1627), so it guards nothing.
                        case "canUpgrade" -> true;
                        case "startBuilding" -> {
                            building = true;
                            startBuildCalls++;
                            yield null;
                        }
                        // BaseIndustry.startUpgrading dereferences the upgrade spec unconditionally
                        // (BaseIndustry.java:575-579), so on a spec with no upgrade it throws exactly
                        // this, which is the live failure that aborted a whole colony reconcile.
                        case "startUpgrading" -> {
                            if (upgradeTarget == null) {
                                throw new NullPointerException("Cannot invoke \"IndustrySpecAPI"
                                        + ".getBuildTime()\" because \"upgrade\" is null");
                            }
                            upgrading = true;
                            startUpgradeCalls++;
                            yield null;
                        }
                        case "finishBuildingOrUpgrading" -> {
                            building = false;
                            upgrading = false;
                            growing = false;
                            finishCalls++;
                            yield null;
                        }
                        // BaseIndustry.cancelUpgrade (BaseIndustry.java:533-537) clears building and
                        // upgradeId and leaves the industry itself exactly where it was.
                        case "cancelUpgrade" -> {
                            building = false;
                            upgrading = false;
                            cancelUpgradeCalls++;
                            yield null;
                        }
                        case "getSpecialItem" -> special;
                        case "setSpecialItem" -> {
                            special = (SpecialItemData) args[0];
                            setSpecialCalls++;
                            yield null;
                        }
                        case "toString" -> "Industry[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    /** Only the surface the capture and the reconcile touch. */
    private static final class FakeMarket {
        private final String id;
        private boolean playerOwned = true;
        private boolean planetConditionMarketOnly;
        private boolean freePort;
        private boolean immigrationClosed;
        private boolean immigrationIncentives;
        private boolean useStockpiles;
        private boolean reappliedIndustries;
        private int addIndustryCalls;
        private int removeIndustryCalls;
        private MarketAPI.MarketInteractionMode lastRemoveMode = MarketAPI.MarketInteractionMode.LOCAL;
        private boolean lastRemoveForUpgrade = true;
        private final List<String> industries = new ArrayList<>();
        private final Map<String, FakeIndustry> byId = new LinkedHashMap<>();
        private final ConstructionQueue queue = new ConstructionQueue();
        private MarketAPI cached;

        private FakeMarket(String id) {
            this.id = id;
        }

        void addIndustry(String industryId) {
            if (!industries.contains(industryId)) {
                industries.add(industryId);
                byId.computeIfAbsent(industryId, FakeIndustry::new);
                addIndustryCalls++;
            }
        }

        FakeIndustry industry(String industryId) {
            return byId.get(industryId);
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
                        case "isPlayerOwned" -> playerOwned;
                        case "isPlanetConditionMarketOnly" -> planetConditionMarketOnly;
                        case "isFreePort" -> freePort;
                        case "setFreePort" -> {
                            freePort = (Boolean) args[0];
                            yield null;
                        }
                        case "isImmigrationClosed" -> immigrationClosed;
                        case "setImmigrationClosed" -> {
                            immigrationClosed = (Boolean) args[0];
                            yield null;
                        }
                        case "isImmigrationIncentivesOn" -> immigrationIncentives;
                        case "setImmigrationIncentivesOn" -> {
                            immigrationIncentives = (Boolean) args[0];
                            yield null;
                        }
                        case "isUseStockpilesForShortages" -> useStockpiles;
                        case "setUseStockpilesForShortages" -> {
                            useStockpiles = (Boolean) args[0];
                            yield null;
                        }
                        case "getConstructionQueue" -> queue;
                        case "getIndustries" -> {
                            List<Industry> all = new ArrayList<>();
                            for (String industryId : industries) {
                                all.add(byId.get(industryId).proxy());
                            }
                            yield all;
                        }
                        case "hasIndustry" -> industries.contains((String) args[0]);
                        case "getIndustry" -> {
                            FakeIndustry industry = industries.contains((String) args[0])
                                    ? byId.get((String) args[0]) : null;
                            yield industry == null ? null : industry.proxy();
                        }
                        case "addIndustry" -> {
                            addIndustry((String) args[0]);
                            yield null;
                        }
                        case "removeIndustry" -> {
                            industries.remove((String) args[0]);
                            removeIndustryCalls++;
                            lastRemoveMode = (MarketAPI.MarketInteractionMode) args[1];
                            lastRemoveForUpgrade = (Boolean) args[2];
                            yield null;
                        }
                        case "reapplyIndustries" -> {
                            reappliedIndustries = true;
                            yield null;
                        }
                        case "toString" -> "Market[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }
}
