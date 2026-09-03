package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.MutableStatWithTempMods;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 24 milestone 1: the raid-outcome codec, its dedup ledger, and the apply-to-market path. The
 * engine is stood up as interface proxies (no mocking framework in this build), which is enough
 * because everything under test is either pure or a handful of public setters.
 */
class CoopRaidOutcomeSyncTest {

    /**
     * Proxying {@code Industry} makes the JDK initialize the proxy class, which resolves every type
     * in its signatures — including {@code MarketCMD.RaidDangerLevel}, whose static init asks the
     * settings for highlight colors ({@code MarketCMD.java:102-107}). Without a stub the proxy class
     * itself fails to initialize, so this is a prerequisite, not decoration.
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

    // ---- Codec ---------------------------------------------------------------------------------

    @Test
    void everyActKindRoundTrips() {
        for (CoopRaidOutcomeSync.Kind kind : CoopRaidOutcomeSync.Kind.values()) {
            CoopRaidOutcomeSync.Outcome outcome = new CoopRaidOutcomeSync.Outcome(
                    "guest-player:7", kind, "market_agreus", "guest-player", 5, 3,
                    "Independent raid", kind.isBombardment(), kind == CoopRaidOutcomeSync.Kind.RAID_VALUABLES,
                    false,
                    List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", 42.5f,
                                    "alpha_core", "corrupted_nanoforge", ""),
                            new CoopRaidOutcomeSync.IndustryState("spaceport", 0f, "", "", "")),
                    List.of(new CoopRaidOutcomeSync.CommodityDeficit("supplies", 12)));

            assertEquals(outcome, CoopRaidOutcomeSync.decode(outcome.encode()), "kind " + kind);
        }
    }

    @Test
    void idsCarryingDelimiterCharactersRoundTripExactly() {
        CoopRaidOutcomeSync.Outcome outcome = new CoopRaidOutcomeSync.Outcome(
                "pipe|player\\:1", CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market|with\nnewline",
                "acting\\player", 6, 2, "Line\nbreak | reason", false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("ind|ustry", 3f, "core\\a", "item|b",
                        "data\nc")),
                List.of(new CoopRaidOutcomeSync.CommodityDeficit("com|modity", 4)));

        CoopRaidOutcomeSync.Outcome decoded = CoopRaidOutcomeSync.decode(outcome.encode());

        assertEquals(outcome, decoded);
        assertEquals("market|with\nnewline", decoded.marketId());
        assertEquals("Line\nbreak | reason", decoded.unrestReason());
        assertEquals("data\nc", decoded.industries().get(0).specialItemData());
    }

    @Test
    void anOutcomeWithNoTouchedIndustriesRoundTrips() {
        CoopRaidOutcomeSync.Outcome outcome = new CoopRaidOutcomeSync.Outcome(
                "host-player:1", CoopRaidOutcomeSync.Kind.BOMBARD_SATURATION, "market_yama",
                "host-player", 3, 0, "", true, false, true, List.of(), List.of());

        assertEquals(outcome, CoopRaidOutcomeSync.decode(outcome.encode()));
    }

    @Test
    void decodeRejectsMalformedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> CoopRaidOutcomeSync.decode(""));
        assertThrows(IllegalArgumentException.class, () -> CoopRaidOutcomeSync.decode("H|a|b"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopRaidOutcomeSync.decode(outcome().encode() + "\nX|nope"));
        assertThrows(IllegalArgumentException.class, () -> CoopRaidOutcomeSync.decode(
                outcome().encode().replace("RAID_DISRUPT", "RAID_SOMETHING")));
    }

    // ---- Ledger --------------------------------------------------------------------------------

    @Test
    void theLedgerAppliesEachOutcomeOnceAndAbsorbsTheEcho() {
        CoopRaidOutcomeSync.Ledger ledger = new CoopRaidOutcomeSync.Ledger();
        CoopRaidOutcomeSync.Outcome first = outcome();

        assertTrue(ledger.apply(first), "first sighting applies");
        assertFalse(ledger.apply(first), "the host's verbatim rebroadcast is a no-op");
        assertFalse(ledger.apply(CoopRaidOutcomeSync.decode(first.encode())),
                "a re-decoded copy of the same act is still the same act");
        assertTrue(ledger.isApplied(first.outcomeId()));
        assertEquals(1, ledger.size());

        CoopRaidOutcomeSync.Outcome second = new CoopRaidOutcomeSync.Outcome(
                "guest-player:2", first.kind(), first.marketId(), first.actingPlayerId(), 5, 3,
                first.unrestReason(), false, false, false, first.industries(), first.deficits());
        assertTrue(ledger.apply(second), "a second raid on the same colony is a new act");

        ledger.clear();
        assertEquals(0, ledger.size());
        assertTrue(ledger.apply(first), "session teardown forgets everything");
    }

    // ---- Apply ---------------------------------------------------------------------------------

    @Test
    void applySetsTheReportedAbsoluteDisruptionOnTheNamedIndustries() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        FakeIndustry spaceport = market.addIndustry("spaceport");
        spaceport.disruptedDays = 11f;

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus",
                "guest-player", 6, 0, "", false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", 30f, "", "", "")),
                List.of()));

        assertEquals(30f, heavy.disruptedDays, 0.001f);
        assertEquals(11f, spaceport.disruptedDays, 0.001f,
                "an industry the act never touched is not in the payload and is left alone");
        assertEquals(1, market.reapplyIndustriesCalls);
    }

    @Test
    void applyStripsTheAiCoreAndSpecialItemARaidCarriedOff() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        heavy.aiCoreId = "alpha_core";
        heavy.specialItem = new SpecialItemData("pristine_nanoforge", null);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_VALUABLES, "market_agreus",
                "guest-player", 6, 0, "", false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", 0f, null, null, null)),
                List.of()));

        assertNull(heavy.aiCoreId);
        assertNull(heavy.specialItem);
    }

    @Test
    void applyIsIdempotentWithTheLedgerOutOfTheWay() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        CoopRaidOutcomeSync.Outcome outcome = new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus",
                "guest-player", 6, 2, "Independent raid", false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", 30f, "", "", "")),
                List.of());

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), outcome);
        int writes = heavy.setDisruptedCalls + market.reapplyIndustriesCalls;
        int unrest = market.unrestPenalty();

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), outcome);

        assertEquals(writes, heavy.setDisruptedCalls + market.reapplyIndustriesCalls,
                "re-applying an already-applied outcome must write nothing");
        assertEquals(unrest, market.unrestPenalty(), "and must not stack unrest a second time");
        assertEquals(2, unrest);
    }

    @Test
    void applyConvergesRecentUnrestOnTheReportedAbsolutePenalty() {
        FakeMarket market = new FakeMarket("market_agreus", 6);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), unrestOutcome("guest-player:1", 3));

        assertEquals(3, market.unrestPenalty());
        assertTrue(market.hasCondition(Conditions.RECENT_UNREST));
    }

    @Test
    void applyAddsOnlyTheDifferenceWhenTheMirrorAlreadyCarriesUnrest() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        market.seedUnrest(2);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), unrestOutcome("guest-player:1", 5));

        assertEquals(5, market.unrestPenalty(), "absolute post-act value, not 2 + 5");
    }

    @Test
    void applyCorrectsAMirrorThatDriftedAboveTheReportedUnrest() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        market.seedUnrest(9);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), unrestOutcome("guest-player:1", 4));

        assertEquals(4, market.unrestPenalty(), "the absolute write is what fixes drift");
    }

    @Test
    void applyNeverCreatesTheUnrestConditionJustToSayThereIsNone() {
        FakeMarket market = new FakeMarket("market_agreus", 6);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), unrestOutcome("guest-player:1", 0));

        assertFalse(market.hasCondition(Conditions.RECENT_UNREST));
    }

    @Test
    void applyAddsBombardmentPollutionAndRemovesARaidedSolarArray() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        market.conditions.add(Conditions.SOLAR_ARRAY);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_VALUABLES, "market_agreus",
                "guest-player", 6, 0, "", true, true, false, List.of(), List.of()));

        assertTrue(market.hasCondition(Conditions.POLLUTION));
        assertFalse(market.hasCondition(Conditions.SOLAR_ARRAY));

        // One-directional: a later outcome that reports neither never puts the array back.
        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:2", CoopRaidOutcomeSync.Kind.RAID_VALUABLES, "market_agreus",
                "guest-player", 6, 0, "", false, false, false, List.of(), List.of()));
        assertFalse(market.hasCondition(Conditions.SOLAR_ARRAY));
        assertTrue(market.hasCondition(Conditions.POLLUTION));
    }

    @Test
    void applyStepsTheColonyDownToASaturationBombardmentSize() {
        FakeMarket market = new FakeMarket("market_agreus", 6);

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.BOMBARD_SATURATION, "market_agreus",
                "guest-player", 5, 0, "", false, false, false, List.of(), List.of()));

        assertEquals(5, market.size);
        assertFalse(market.conditions.contains("population_6"));
        assertTrue(market.conditions.contains("population_5"),
                "vanilla's reducer swaps the population condition; a bare setSize would not");

        // Never grows a colony: an outcome reporting a larger size leaves the mirror alone.
        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:2", CoopRaidOutcomeSync.Kind.BOMBARD_SATURATION, "market_agreus",
                "guest-player", 7, 0, "", false, false, false, List.of(), List.of()));
        assertEquals(5, market.size);
    }

    @Test
    void applyHangsTheCommodityDeficitOnTheAvailabilityStat() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        FakeCommodity supplies = market.addCommodity("supplies");

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_VALUABLES, "market_agreus",
                "guest-player", 6, 0, "", false, false, false, List.of(),
                List.of(new CoopRaidOutcomeSync.CommodityDeficit("supplies", 12),
                        new CoopRaidOutcomeSync.CommodityDeficit("no_such_commodity", 3))));

        assertEquals(1, supplies.tempMods.size());
        assertEquals(-12f, supplies.tempMods.values().iterator().next(), 0.001f);
        String source = supplies.tempMods.keySet().iterator().next();

        // Deterministic source id: a second apply overwrites its own mod instead of stacking one.
        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_VALUABLES, "market_agreus",
                "guest-player", 6, 0, "", false, false, false, List.of(),
                List.of(new CoopRaidOutcomeSync.CommodityDeficit("supplies", 12))));
        assertEquals(1, supplies.tempMods.size());
        assertEquals(source, supplies.tempMods.keySet().iterator().next());
    }

    /**
     * The SAT_BOMB / DECIV non-overlap decision: when the bombardment razed the colony, everything
     * except the market-memory flag belongs to the DECIV world-delta that already landed.
     */
    @Test
    void aDecivilizedOutcomeAppliesOnlyTheBombardedFlag() {
        FakeMarket market = new FakeMarket("market_yama", 3);
        FakeIndustry spaceport = market.addIndustry("spaceport");
        market.planetConditionMarketOnly = true;

        CoopRaidOutcomeSync.applyToMarket(market.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.BOMBARD_SATURATION, "market_yama",
                "guest-player", 1, 7, "Independent bombardment", true, false, true,
                List.of(new CoopRaidOutcomeSync.IndustryState("spaceport", 90f, "", "", "")),
                List.of()));

        assertEquals(3, market.size, "DecivTracker already gutted the market; do not re-reduce it");
        assertEquals(0, spaceport.setDisruptedCalls);
        assertEquals(0, market.unrestPenalty());
        assertFalse(market.hasCondition(Conditions.POLLUTION),
                "conditions are the deciv transition's business");
        assertTrue(market.memory.getBoolean(MemFlags.RECENTLY_BOMBARDED),
                "market memory is the one thing decivilize does not touch");
    }

    @Test
    void raidsAndBombardmentsSetTheirOwnMarketMemoryFlag() {
        FakeMarket raided = new FakeMarket("market_a", 5);
        CoopRaidOutcomeSync.applyToMarket(raided.proxy(), unrestOutcome("guest-player:1", 1));
        assertTrue(raided.memory.getBoolean(MemFlags.RECENTLY_RAIDED));
        assertFalse(raided.memory.getBoolean(MemFlags.RECENTLY_BOMBARDED));

        FakeMarket bombarded = new FakeMarket("market_b", 5);
        CoopRaidOutcomeSync.applyToMarket(bombarded.proxy(), new CoopRaidOutcomeSync.Outcome(
                "guest-player:2", CoopRaidOutcomeSync.Kind.BOMBARD_TACTICAL, "market_b",
                "guest-player", 5, 0, "", false, false, false, List.of(), List.of()));
        assertTrue(bombarded.memory.getBoolean(MemFlags.RECENTLY_BOMBARDED));
        assertFalse(bombarded.memory.getBoolean(MemFlags.RECENTLY_RAIDED));
    }

    @Test
    void applyToleratesAnIndustryTheMirrorDoesNotHave() {
        FakeMarket market = new FakeMarket("market_agreus", 6);

        assertDoesNotThrow(() -> CoopRaidOutcomeSync.applyToMarket(market.proxy(),
                new CoopRaidOutcomeSync.Outcome("guest-player:1",
                        CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus", "guest-player", 6, 0,
                        "", false, false, false,
                        List.of(new CoopRaidOutcomeSync.IndustryState("orbitalworks", 30f, "", "", "")),
                        List.of())));
        assertEquals(0, market.reapplyIndustriesCalls);
    }

    // ---- Market resolution ---------------------------------------------------------------------

    @Test
    void applyToEngineResolvesTheMarketByItsGenTimeId() {
        FakeMarket market = new FakeMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        Global.setSector(sectorWith(market));

        CoopRaidOutcomeSync.applyToEngine(new CoopRaidOutcomeSync.Outcome("guest-player:1",
                CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus", "guest-player", 6, 0, "",
                false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", 25f, "", "", "")),
                List.of()));

        assertEquals(25f, heavy.disruptedDays, 0.001f);
    }

    @Test
    void applyToEngineDropsAnUnknownMarketInsteadOfThrowing() {
        Global.setSector(sectorWith(new FakeMarket("market_agreus", 6)));

        assertDoesNotThrow(() -> CoopRaidOutcomeSync.applyToEngine(new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_nowhere",
                "guest-player", 6, 0, "", false, false, false, List.of(), List.of())));
    }

    @Test
    void applyToEngineToleratesNoSectorAtAll() {
        Global.setSector(null);

        assertDoesNotThrow(() -> CoopRaidOutcomeSync.applyToEngine(new CoopRaidOutcomeSync.Outcome(
                "guest-player:1", CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus",
                "guest-player", 6, 0, "", false, false, false, List.of(), List.of())));
    }

    // ---- Capture: the collapse guard -----------------------------------------------------------

    /**
     * Vanilla keeps <em>one</em> {@code TempData} per market for a whole visit — it lives in market
     * memory under {@code $MarketCMD_temp} with a 0-day expiry ({@code MarketCMD.java:288-298}) and an
     * open interaction dialog holds the sector paused, so nothing expires it. Collapsing on that
     * identity alone therefore swallowed the second hostile act of a visit: raid a colony, then
     * bombard it, and only the raid ever reached the peer.
     */
    @Test
    void aBombardmentAfterARaidInTheSameVisitIsStillCaptured() {
        FakeMarket market = new FakeMarket("market_agreus", 5);
        RecordingSink sink = new RecordingSink();
        CoopRaidOutcomeSync.HostileActCapture capture =
                new CoopRaidOutcomeSync.HostileActCapture(sink);
        MarketCMD.TempData visit = new MarketCMD.TempData();

        capture.capture(CoopRaidOutcomeSync.Kind.RAID_VALUABLES, market.proxy(), visit, null);
        capture.capture(CoopRaidOutcomeSync.Kind.BOMBARD_TACTICAL, market.proxy(), visit, null);

        assertEquals(List.of(CoopRaidOutcomeSync.Kind.RAID_VALUABLES,
                CoopRaidOutcomeSync.Kind.BOMBARD_TACTICAL), sink.kinds());
    }

    /**
     * The one repeat vanilla really does make: {@code reportRaidToDisruptFinished} fires once per
     * disrupted industry ({@code MarketCMD.java:1902-1906}) and a single outcome already carries them
     * all, so those still collapse.
     */
    @Test
    void repeatedDisruptCallsForOneRaidStillCollapseIntoOneOutcome() {
        FakeMarket market = new FakeMarket("market_agreus", 5);
        Industry first = market.addIndustry("heavyindustry").proxy();
        Industry second = market.addIndustry("mining").proxy();
        RecordingSink sink = new RecordingSink();
        CoopRaidOutcomeSync.HostileActCapture capture =
                new CoopRaidOutcomeSync.HostileActCapture(sink);
        MarketCMD.TempData visit = new MarketCMD.TempData();

        capture.capture(CoopRaidOutcomeSync.Kind.RAID_DISRUPT, market.proxy(), visit, first);
        capture.capture(CoopRaidOutcomeSync.Kind.RAID_DISRUPT, market.proxy(), visit, second);

        assertEquals(List.of(CoopRaidOutcomeSync.Kind.RAID_DISRUPT), sink.kinds());
    }

    /** Session teardown must not leave the guard pinned to the last act of the old session. */
    @Test
    void resetRearmsTheCollapseGuardAndRestartsTheIdCounter() {
        FakeMarket market = new FakeMarket("market_agreus", 5);
        Industry industry = market.addIndustry("heavyindustry").proxy();
        RecordingSink sink = new RecordingSink();
        CoopRaidOutcomeSync.HostileActCapture capture =
                new CoopRaidOutcomeSync.HostileActCapture(sink);
        MarketCMD.TempData visit = new MarketCMD.TempData();

        capture.capture(CoopRaidOutcomeSync.Kind.RAID_DISRUPT, market.proxy(), visit, industry);
        capture.reset();
        capture.capture(CoopRaidOutcomeSync.Kind.RAID_DISRUPT, market.proxy(), visit, industry);

        assertEquals(2, sink.captured.size());
        assertEquals("guest-player:1", sink.captured.get(1).outcomeId());
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** Collects what the capture listener hands the replicator. */
    private static final class RecordingSink implements CoopRaidOutcomeSync.Sink {
        private final List<CoopRaidOutcomeSync.Outcome> captured = new ArrayList<>();

        @Override
        public boolean shouldCaptureRaidOutcome() {
            return true;
        }

        @Override
        public String raidActingPlayerId() {
            return "guest-player";
        }

        @Override
        public void onRaidOutcomeCaptured(CoopRaidOutcomeSync.Outcome outcome) {
            captured.add(outcome);
        }

        List<CoopRaidOutcomeSync.Kind> kinds() {
            List<CoopRaidOutcomeSync.Kind> kinds = new ArrayList<>();
            for (CoopRaidOutcomeSync.Outcome outcome : captured) {
                kinds.add(outcome.kind());
            }
            return kinds;
        }
    }


    private static CoopRaidOutcomeSync.Outcome outcome() {
        return new CoopRaidOutcomeSync.Outcome("guest-player:1",
                CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus", "guest-player", 6, 2,
                "Independent raid", false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", 30f, "", "", "")),
                List.of());
    }

    private static CoopRaidOutcomeSync.Outcome unrestOutcome(String id, int penalty) {
        return new CoopRaidOutcomeSync.Outcome(id, CoopRaidOutcomeSync.Kind.RAID_DISRUPT,
                "market_a", "guest-player", 9, penalty, "Independent raid", false, false, false,
                List.of(), List.of());
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

    // ---- Engine fakes --------------------------------------------------------------------------

    static final class FakeMemory {
        final Map<String, Object> values = new HashMap<>();

        void set(String key, Object value) {
            values.put(key, value);
        }

        boolean getBoolean(String key) {
            return Boolean.TRUE.equals(values.get(key));
        }

        MemoryAPI proxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            set((String) args[0], args[1]);
                            yield null;
                        }
                        case "unset" -> {
                            values.remove((String) args[0]);
                            yield null;
                        }
                        case "get" -> values.get((String) args[0]);
                        case "contains" -> values.containsKey((String) args[0]);
                        case "getBoolean" -> getBoolean((String) args[0]);
                        case "toString" -> "Memory" + values;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    static final class FakeIndustry {
        private final String id;
        float disruptedDays;
        String aiCoreId;
        SpecialItemData specialItem;
        int setDisruptedCalls;

        FakeIndustry(String id) {
            this.id = id;
        }

        Industry proxy() {
            return (Industry) Proxy.newProxyInstance(
                    Industry.class.getClassLoader(),
                    new Class<?>[]{Industry.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getDisruptedDays" -> disruptedDays;
                        case "setDisrupted" -> {
                            disruptedDays = (Float) args[0];
                            setDisruptedCalls++;
                            yield null;
                        }
                        case "getAICoreId" -> aiCoreId;
                        case "setAICoreId" -> {
                            aiCoreId = (String) args[0];
                            yield null;
                        }
                        case "getSpecialItem" -> specialItem;
                        case "setSpecialItem" -> {
                            specialItem = (SpecialItemData) args[0];
                            yield null;
                        }
                        case "toString" -> "Industry[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    static final class FakeCommodity {
        private final String id;
        final Map<String, Float> tempMods = new LinkedHashMap<>();

        FakeCommodity(String id) {
            this.id = id;
        }

        CommodityOnMarketAPI proxy() {
            MutableStatWithTempMods stat = new MutableStatWithTempMods(0f) {
                @Override
                public void addTemporaryModFlat(float durInDays, String source, String desc, float value) {
                    tempMods.put(source, value);
                }
            };
            return (CommodityOnMarketAPI) Proxy.newProxyInstance(
                    CommodityOnMarketAPI.class.getClassLoader(),
                    new Class<?>[]{CommodityOnMarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getAvailableStat" -> stat;
                        case "toString" -> "Commodity[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /**
     * Enough of a market for the apply path: industries, conditions (including a live
     * {@link RecentUnrest} plugin behind {@code RECENT_UNREST}), commodities, size, and memory.
     */
    static final class FakeMarket {
        private final String id;
        int size;
        boolean planetConditionMarketOnly;
        final List<String> conditions = new ArrayList<>();
        final FakeMemory memory = new FakeMemory();
        private final Map<String, FakeIndustry> industries = new LinkedHashMap<>();
        private final Map<String, FakeCommodity> commodities = new LinkedHashMap<>();
        private final PopulationComposition population = new PopulationComposition();
        private RecentUnrest unrest;
        int reapplyIndustriesCalls;
        private MarketAPI cached;

        FakeMarket(String id, int size) {
            this.id = id;
            this.size = size;
            conditions.add("population_" + size);
        }

        FakeIndustry addIndustry(String industryId) {
            FakeIndustry industry = new FakeIndustry(industryId);
            industries.put(industryId, industry);
            return industry;
        }

        FakeCommodity addCommodity(String commodityId) {
            FakeCommodity commodity = new FakeCommodity(commodityId);
            commodities.put(commodityId, commodity);
            return commodity;
        }

        boolean hasCondition(String conditionId) {
            return conditions.contains(conditionId);
        }

        void seedUnrest(int penalty) {
            unrest = new RecentUnrest();
            unrest.setPenalty(penalty);
            conditions.add(Conditions.RECENT_UNREST);
        }

        int unrestPenalty() {
            return unrest == null ? 0 : unrest.getPenalty();
        }

        private MarketConditionAPI unrestCondition() {
            return (MarketConditionAPI) Proxy.newProxyInstance(
                    MarketConditionAPI.class.getClassLoader(),
                    new Class<?>[]{MarketConditionAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPlugin" -> unrest;
                        case "getId" -> Conditions.RECENT_UNREST;
                        case "getIdForPluginModifications" -> Conditions.RECENT_UNREST;
                        case "toString" -> "Condition[recent_unrest]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        MarketAPI proxy() {
            if (cached != null) {
                return cached;
            }
            MemoryAPI memoryProxy = memory.proxy();
            cached = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getName" -> id;
                        case "getSize" -> size;
                        case "setSize" -> {
                            size = (Integer) args[0];
                            yield null;
                        }
                        case "isPlanetConditionMarketOnly" -> planetConditionMarketOnly;
                        case "getPopulation" -> population;
                        case "hasCondition" -> hasCondition((String) args[0]);
                        case "addCondition" -> {
                            String conditionId = (String) args[0];
                            if (!conditions.contains(conditionId)) {
                                conditions.add(conditionId);
                            }
                            if (Conditions.RECENT_UNREST.equals(conditionId) && unrest == null) {
                                unrest = new RecentUnrest();
                            }
                            yield conditionId;
                        }
                        case "removeCondition" -> {
                            conditions.remove((String) args[0]);
                            yield null;
                        }
                        case "getCondition", "getSpecificCondition" -> {
                            String conditionId = (String) args[0];
                            yield Conditions.RECENT_UNREST.equals(conditionId) && unrest != null
                                    ? unrestCondition() : null;
                        }
                        case "getIndustry" -> {
                            FakeIndustry industry = industries.get((String) args[0]);
                            yield industry == null ? null : industry.proxy();
                        }
                        case "getIndustries" -> {
                            List<Industry> all = new ArrayList<>();
                            for (FakeIndustry industry : industries.values()) {
                                all.add(industry.proxy());
                            }
                            yield all;
                        }
                        case "getCommodityData" -> {
                            FakeCommodity commodity = commodities.get((String) args[0]);
                            yield commodity == null ? null : commodity.proxy();
                        }
                        case "reapplyIndustries" -> {
                            reapplyIndustriesCalls++;
                            yield null;
                        }
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
                        case "toString" -> "Market[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    static Object defaultValue(Class<?> type) {
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
