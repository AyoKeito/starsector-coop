package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MonthlyReport;
import com.fs.starfarer.api.impl.campaign.CoreScript;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.util.MutableValue;
import coop.rewards.CoopRewardSplitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 milestone 3: reading the settled monthly report, the month-end capture's timestamp guard,
 * and the wallet deduction. {@code MonthlyReport} is a plain data class with a public no-arg
 * constructor, so these run against the real thing rather than a fake of it.
 */
class CoopColonyIncomeTest {

    private FakeSector sector;

    @BeforeEach
    void stubGlobals() {
        Global.setSettings(fakeSettings());
        sector = new FakeSector();
        Global.setSector(sector.proxy());
    }

    @AfterEach
    void clearGlobals() {
        Global.setSector(null);
        Global.setSettings(null);
    }

    // ---- Reading the report --------------------------------------------------------------------

    @Test
    void colonyNodesAreSummedAndCounted() {
        MonthlyReport report = new MonthlyReport();
        addColonyNode(report, playerMarket("market_eos"), 30_000f, 5_000f);
        addColonyNode(report, playerMarket("market_yama"), 12_000f, 2_000f);
        report.computeTotals();

        CoopColonyIncome.MonthTotals totals = CoopColonyIncome.settledColonyTotals(report);

        assertEquals(42_000f, totals.income());
        assertEquals(7_000f, totals.upkeep());
        assertEquals(35_000f, totals.net());
        assertEquals(2, totals.colonyCount());
    }

    /**
     * Fleet upkeep, storage fees at NPC markets and the string-tagged nodes that live alongside the
     * colonies are all somebody else's money.
     */
    @Test
    void fleetStorageAndAdminNodesAreExcluded() {
        MonthlyReport report = new MonthlyReport();
        addColonyNode(report, playerMarket("market_eos"), 30_000f, 5_000f);

        // Admin salaries: a sibling of the market nodes, tagged with a String rather than a market.
        MonthlyReport.FDNode admin = report.getNode(MonthlyReport.OUTPOSTS, MonthlyReport.ADMIN);
        admin.custom = MonthlyReport.ADMIN;
        admin.upkeep = 9_000f;
        // Fleet payroll: a different subtree entirely.
        report.getNode(MonthlyReport.FLEET, MonthlyReport.CREW).upkeep = 4_000f;
        // Storage fees: a market node, but at an NPC market, in the STORAGE subtree.
        MonthlyReport.FDNode storage = report.getNode(MonthlyReport.STORAGE, "market_jangala");
        storage.custom = market("market_jangala", false);
        storage.custom2 = MonthlyReport.STORAGE;
        storage.upkeep = 1_500f;
        report.computeTotals();

        CoopColonyIncome.MonthTotals totals = CoopColonyIncome.settledColonyTotals(report);

        assertEquals(25_000f, totals.net());
        assertEquals(1, totals.colonyCount());
    }

    /** A market that stopped being player-owned mid-month is not this session's colony income. */
    @Test
    void aNonPlayerOwnedMarketUnderOutpostsIsSkipped() {
        MonthlyReport report = new MonthlyReport();
        addColonyNode(report, playerMarket("market_eos"), 10_000f, 0f);
        MonthlyReport.FDNode lost = report.getNode(MonthlyReport.OUTPOSTS, "market_gone");
        lost.custom = market("market_gone", false);
        lost.income = 99_000f;
        report.computeTotals();

        assertEquals(10_000f, CoopColonyIncome.settledColonyTotals(report).net());
        assertEquals(1, CoopColonyIncome.settledColonyTotals(report).colonyCount());
    }

    @Test
    void anEmptyOrAbsentReportIsZeroRatherThanACrash() {
        assertTrue(CoopColonyIncome.settledColonyTotals(null).isSilent());
        assertTrue(CoopColonyIncome.settledColonyTotals(new MonthlyReport()).isSilent());
    }

    /** A market node's own subtree (industries, exports) has to roll up into the market total. */
    @Test
    void perIndustryChildrenRollUpIntoTheMarketTotal() {
        MonthlyReport report = new MonthlyReport();
        MonthlyReport.FDNode marketNode = report.getNode(MonthlyReport.OUTPOSTS, "market_eos");
        marketNode.custom = playerMarket("market_eos");
        MonthlyReport.FDNode industries = report.getNode(marketNode, "industries");
        report.getNode(industries, "mining").income = 8_000f;
        report.getNode(industries, "spaceport").upkeep = 3_000f;
        report.computeTotals();

        CoopColonyIncome.MonthTotals totals = CoopColonyIncome.settledColonyTotals(report);

        assertEquals(5_000f, totals.net());
    }

    // ---- Month-end capture ---------------------------------------------------------------------

    @Test
    void aMonthEndReportsTheSettledTotalsOnce() {
        settleReport(1_000L, 25_000f, 0f);
        RecordingSink sink = new RecordingSink();
        CoopColonyIncome.MonthEndCapture capture = new CoopColonyIncome.MonthEndCapture(sink);

        capture.reportEconomyMonthEnd();

        assertEquals(1, sink.totals.size());
        assertEquals(25_000f, sink.totals.get(0).net());
    }

    /**
     * The tutorial makes {@code CoreScript} skip the rollover, so the "settled" report can be the
     * month that was already split. The timestamp guard is what stops a second deduction.
     */
    @Test
    void thePreviousMonthIsNotSplitTwice() {
        settleReport(1_000L, 25_000f, 0f);
        RecordingSink sink = new RecordingSink();
        CoopColonyIncome.MonthEndCapture capture = new CoopColonyIncome.MonthEndCapture(sink);

        capture.reportEconomyMonthEnd();
        capture.reportEconomyMonthEnd();

        assertEquals(1, sink.totals.size());

        settleReport(2_000L, 12_000f, 0f);
        capture.reportEconomyMonthEnd();

        assertEquals(2, sink.totals.size(), "a genuinely new month still lands");
        assertEquals(12_000f, sink.totals.get(1).net());
    }

    @Test
    void aNewSessionMaySplitItsFirstMonthAgain() {
        settleReport(1_000L, 25_000f, 0f);
        RecordingSink sink = new RecordingSink();
        CoopColonyIncome.MonthEndCapture capture = new CoopColonyIncome.MonthEndCapture(sink);
        capture.reportEconomyMonthEnd();

        capture.reset();
        capture.reportEconomyMonthEnd();

        assertEquals(2, sink.totals.size());
    }

    @Test
    void captureIsSkippedEntirelyWhileTheSinkSaysNo() {
        settleReport(1_000L, 25_000f, 0f);
        RecordingSink sink = new RecordingSink();
        sink.capturing = false;

        new CoopColonyIncome.MonthEndCapture(sink).reportEconomyMonthEnd();

        assertTrue(sink.totals.isEmpty());
    }

    @Test
    void economyTicksAreIgnored() {
        settleReport(1_000L, 25_000f, 0f);
        RecordingSink sink = new RecordingSink();

        new CoopColonyIncome.MonthEndCapture(sink).reportEconomyTick(9);

        assertTrue(sink.totals.isEmpty());
    }

    // ---- The wallet ----------------------------------------------------------------------------

    @Test
    void theDeductionTakesExactlyThePeersHalf() {
        sector.credits.set(100_000f);
        CoopRewardSplitter.Split split = CoopRewardSplitter.splitCredits(25_000f);

        long deducted = CoopColonyIncome.deductFromLocalPlayer(split.remoteShare());

        assertEquals(12_500L, deducted);
        assertEquals(87_500f, sector.credits.get());
    }

    /** A losing month is a refund: vanilla already charged the whole loss locally. */
    @Test
    void aNegativeShareGivesCreditsBack() {
        sector.credits.set(100_000f);
        CoopRewardSplitter.Split split = CoopRewardSplitter.splitCredits(-10_000f);

        long deducted = CoopColonyIncome.deductFromLocalPlayer(split.remoteShare());

        assertEquals(-5_000L, deducted);
        assertEquals(105_000f, sector.credits.get());
    }

    /** Vanilla never leaves the player with negative credits, and neither does this. */
    @Test
    void theDeductionIsClampedAtZero() {
        sector.credits.set(3_000f);

        long deducted = CoopColonyIncome.deductFromLocalPlayer(12_500L);

        assertEquals(3_000L, deducted);
        assertEquals(0f, sector.credits.get());
    }

    @Test
    void aZeroShareNeverTouchesTheWallet() {
        sector.credits.set(1_234f);

        assertEquals(0L, CoopColonyIncome.deductFromLocalPlayer(0L));
        assertEquals(1_234f, sector.credits.get());
    }

    @Test
    void aMissingPlayerFleetIsSurvivedRatherThanThrown() {
        sector.hasFleet = false;

        assertEquals(0L, CoopColonyIncome.deductFromLocalPlayer(500L));
    }

    // ---- Player-facing text --------------------------------------------------------------------

    @Test
    void theBannerReadsAsCreditsKeptOutOfTheWhole() {
        assertEquals("Coop: colony income split - kept 12,500 of 25,000 credits.",
                CoopColonyIncome.splitBanner(CoopRewardSplitter.split(25_000L)));
    }

    @Test
    void aLosingMonthGetsItsOwnWording() {
        assertEquals("Coop: colony losses split - paid 5,000 of 10,000 credits.",
                CoopColonyIncome.splitBanner(CoopRewardSplitter.split(-10_000L)));
    }

    @Test
    void bannerTextIsAsciiOnly() {
        String banner = CoopColonyIncome.splitBanner(CoopRewardSplitter.split(1_234_567L));

        for (int i = 0; i < banner.length(); i++) {
            assertTrue(banner.charAt(i) < 128, "non-ASCII character in banner: " + banner);
        }
        assertTrue(banner.contains("1,234,567"));
    }

    /** No colonies and no money is not worth a message. */
    @Test
    void aZeroColonyMonthIsSilent() {
        assertTrue(CoopColonyIncome.MonthTotals.EMPTY.isSilent());
        assertFalse(new CoopColonyIncome.MonthTotals(0f, 0f, 1).isSilent());
        assertFalse(new CoopColonyIncome.MonthTotals(500f, 0f, 0).isSilent());
    }

    @Test
    void theDriftLineCarriesBothSidesAndTheDifference() {
        String line = CoopColonyIncome.driftLine(
                new CoopColonyIncome.MonthTotals(30_000f, 5_000f, 2), 24_000f, 2L);

        assertTrue(line.contains("local net=25000"), line);
        assertTrue(line.contains("host net=24000"), line);
        assertTrue(line.contains("drift=1000"), line);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private void settleReport(long timestamp, float income, float upkeep) {
        MonthlyReport report = new MonthlyReport();
        report.setTimestamp(timestamp);
        addColonyNode(report, playerMarket("market_eos"), income, upkeep);
        report.computeTotals();
        SharedData.getData().setPreviousReport(report);
    }

    private static void addColonyNode(MonthlyReport report, MarketAPI market, float income,
                                      float upkeep) {
        MonthlyReport.FDNode node = report.getNode(MonthlyReport.OUTPOSTS, market.getId());
        node.custom = market;
        node.income = income;
        node.upkeep = upkeep;
    }

    private static MarketAPI playerMarket(String id) {
        return market(id, true);
    }

    private static MarketAPI market(String id, boolean playerOwned) {
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getName" -> id;
                    case "isPlayerOwned" -> playerOwned;
                    case "toString" -> "Market[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
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

    private static final class RecordingSink implements CoopColonyIncome.Sink {
        private final List<CoopColonyIncome.MonthTotals> totals = new ArrayList<>();
        private boolean capturing = true;

        @Override
        public boolean shouldSplitColonyIncome() {
            return capturing;
        }

        @Override
        public void onColonyMonthEnd(CoopColonyIncome.MonthTotals monthTotals) {
            totals.add(monthTotals);
        }
    }

    private static final class FakeSector {
        private final Map<String, Object> persistentData = new LinkedHashMap<>();
        private final MutableValue credits = new MutableValue(0f);
        private boolean hasFleet = true;
        private SectorAPI cached;

        SectorAPI proxy() {
            if (cached != null) {
                return cached;
            }
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
            cached = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPersistentData" -> persistentData;
                        case "getPlayerFleet" -> hasFleet ? fleet : null;
                        case "toString" -> "Sector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    /** Pins the key {@code SharedData} reads from, so a rename upstream fails here loudly. */
    @Test
    void theSettledReportComesFromSharedDataPreviousReport() {
        MonthlyReport report = new MonthlyReport();
        report.setTimestamp(7L);
        SharedData.getData().setPreviousReport(report);

        assertTrue(sector.persistentData.containsKey(CoreScript.SHARED_DATA_KEY));
        assertEquals(7L, CoopColonyIncome.settledReport().getTimestamp());
    }
}
