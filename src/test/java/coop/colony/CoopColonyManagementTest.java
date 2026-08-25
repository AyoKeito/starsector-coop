package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Global.setSettings(fakeSettings());
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

    // ---- Helpers -------------------------------------------------------------------------------

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

    private static FakeMarket colony() {
        FakeMarket market = new FakeMarket("market_planet_eos");
        market.addIndustry("population");
        market.addIndustry("spaceport");
        market.addIndustryCalls = 0;
        return market;
    }

    private static SettingsAPI fakeSettings() {
        return (SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColor" -> Color.WHITE;
                    case "toString" -> "Settings";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    /** Mutable industry state behind an {@link Industry} proxy. */
    private static final class FakeIndustry {
        private final String id;
        private String aiCoreId;
        private boolean improved;
        private boolean building;
        private boolean upgrading;
        private SpecialItemData special;
        private String upgradeTarget;
        private int finishCalls;
        private int startBuildCalls;
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
                        case "getAICoreId" -> aiCoreId;
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
                        case "isUpgrading" -> upgrading;
                        case "canUpgrade" -> upgradeTarget != null;
                        case "startBuilding" -> {
                            building = true;
                            startBuildCalls++;
                            yield null;
                        }
                        case "startUpgrading" -> {
                            upgrading = true;
                            yield null;
                        }
                        case "finishBuildingOrUpgrading" -> {
                            building = false;
                            upgrading = false;
                            finishCalls++;
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
                        case "isPlanetConditionMarketOnly" -> false;
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

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
