package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyPlayerHostileActListener;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.graid.GroundRaidObjectivePlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import coop.colony.CoopRaidOutcomeSync;
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
import java.util.HashMap;
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
 * Phase 24 milestone 1 engine glue: the hostile-act capture the replicator registers, and the
 * host/guest sides of {@code RAID_RESULT}. The decisions themselves live in
 * {@link CoopRaidOutcomeSync} and are tested there; this covers the wiring.
 */
class CoopRaidReplicatorTest {

    /**
     * Proxying {@code Industry} makes the JDK initialize the proxy class, which resolves every type
     * in its signatures — including {@code MarketCMD.RaidDangerLevel}, whose static init asks the
     * settings for highlight colors ({@code MarketCMD.java:102-107}). Without a stub the proxy class
     * itself fails to initialize, so this is a prerequisite, not decoration.
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

    @Test
    void aFinishedBombardmentIsCapturedAndSentOnce() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        heavy.disruptedDays = 40f;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        MarketCMD.TempData temp = new MarketCMD.TempData();
        temp.bombardmentTargets.add(heavy.proxy());
        sector.listenerOfType(ColonyPlayerHostileActListener.class)
                .reportTacticalBombardmentFinished(null, market.proxy(), temp);

        assertEquals(1, service.sent.size());
        assertEquals(CoopMessages.Type.RAID_RESULT, service.sent.get(0).type());
        CoopRaidOutcomeSync.Outcome outcome = CoopRaidOutcomeSync.decode(
                CoopMessages.requiredPayloadString(service.sent.get(0), "outcome"));
        assertEquals(CoopRaidOutcomeSync.Kind.BOMBARD_TACTICAL, outcome.kind());
        assertEquals("market_agreus", outcome.marketId());
        assertEquals("host-player", outcome.actingPlayerId());
        assertEquals(1, outcome.industries().size());
        assertEquals("heavyindustry", outcome.industries().get(0).industryId());
        assertEquals(40f, outcome.industries().get(0).disruptedDays(), 0.001f);
        assertFalse(outcome.decivilized());
    }

    /**
     * Vanilla fires the disrupt report once per disrupted industry ({@code MarketCMD.java:1903-1906});
     * one act must still be one outcome, or its additive effects would stack on the mirror.
     */
    @Test
    void theDisruptReportFiringPerIndustryStillProducesOneOutcome() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        FakeIndustry mining = market.addIndustry("mining");
        heavy.disruptedDays = 30f;
        mining.disruptedDays = 20f;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        MarketCMD.TempData temp = new MarketCMD.TempData();
        temp.objectives.add(disruptObjective(heavy, 3));
        temp.objectives.add(disruptObjective(mining, 2));
        // An objective nobody assigned marines to never ran and must not ride along.
        temp.objectives.add(disruptObjective(market.addIndustry("spaceport"), 0));

        ColonyPlayerHostileActListener capture =
                sector.listenerOfType(ColonyPlayerHostileActListener.class);
        capture.reportRaidToDisruptFinished(null, market.proxy(), temp, heavy.proxy());
        capture.reportRaidToDisruptFinished(null, market.proxy(), temp, mining.proxy());

        assertEquals(1, service.sent.size());
        CoopRaidOutcomeSync.Outcome outcome = CoopRaidOutcomeSync.decode(
                CoopMessages.requiredPayloadString(service.sent.get(0), "outcome"));
        assertEquals(List.of("heavyindustry", "mining"),
                outcome.industries().stream().map(CoopRaidOutcomeSync.IndustryState::industryId).toList());

        // A genuinely separate act (fresh TempData) is a new outcome with a new id.
        MarketCMD.TempData second = new MarketCMD.TempData();
        second.objectives.add(disruptObjective(heavy, 1));
        capture.reportRaidToDisruptFinished(null, market.proxy(), second, heavy.proxy());
        assertEquals(2, service.sent.size());
        assertEquals("guest-player:2", CoopRaidOutcomeSync.decode(
                CoopMessages.requiredPayloadString(service.sent.get(1), "outcome")).outcomeId());
    }

    @Test
    void captureIsSkippedWhileApplyingARemoteOutcome() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        MarketCMD.TempData temp = new MarketCMD.TempData();
        temp.bombardmentTargets.add(heavy.proxy());

        replicator.replayGuard().begin();
        try {
            sector.listenerOfType(ColonyPlayerHostileActListener.class)
                    .reportTacticalBombardmentFinished(null, market.proxy(), temp);
        } finally {
            replicator.replayGuard().end();
        }

        assertTrue(service.sent.isEmpty(),
                "re-driving a remote outcome must not be recaptured as a fresh act");
    }

    @Test
    void theHostAppliesTheGuestsRaidRebroadcastsItAndTheEchoDies() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        CoopMessages.Message inbound = raidMessage("guest-player:1", 55f);
        replicator.handle(inbound);

        assertEquals(55f, heavy.disruptedDays, 0.001f);
        assertEquals(1, service.sent.size(), "the host rebroadcasts its canonical view");
        assertEquals(CoopMessages.Type.RAID_RESULT, service.sent.get(0).type());

        // The originator's own ledger entry is what kills that echo; here we replay it at the host,
        // which must apply nothing a second time but does keep rebroadcasting (self-healing).
        heavy.setDisruptedCalls = 0;
        replicator.handle(inbound);
        assertEquals(0, heavy.setDisruptedCalls);
        assertTrue(replicator.raidLedger().isApplied("guest-player:1"));
    }

    @Test
    void theGuestAppliesAndNeverRebroadcasts() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 6);
        FakeIndustry heavy = market.addIndustry("heavyindustry");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(raidMessage("host-player:4", 12f));

        assertEquals(12f, heavy.disruptedDays, 0.001f);
        assertTrue(service.sent.isEmpty());
    }

    @Test
    void sessionTeardownClearsTheLedgerAndRemovesTheListener() {
        FakeSector sector = new FakeSector();
        sector.addMarket("market_agreus", 6).addIndustry("heavyindustry");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());
        replicator.handle(raidMessage("guest-player:1", 5f));
        assertEquals(1, replicator.raidLedger().size());

        replicator.dispose(sector.proxy());

        assertEquals(0, replicator.raidLedger().size());
        assertNull(sector.listenerOfType(ColonyPlayerHostileActListener.class));
    }

    // ---- Saturation bombardment: the decivilization rides RAID_RESULT --------------------------

    /**
     * A guest resolves a saturation bombardment entirely inside vanilla's {@code MarketCMD}, and its
     * deciv capture is host-gated, so no DECIV world-delta ever leaves it. {@code RAID_RESULT} is the
     * only report the host gets, and applying it has to drive the decivilization itself -- otherwise
     * the host keeps a colony the guest already deleted and the guest's next reconnect fails the
     * world fingerprint.
     */
    @Test
    void aGuestSaturationBombardmentDecivilizesTheHostColony() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 3);
        market.hasPrimaryEntity = true;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        replicator.handle(decivMessage("guest-player:1"));

        assertEquals(1, market.decivilizeEntries, "the host runs vanilla's own deciv routine");
        assertEquals(Boolean.TRUE, market.memory.values.get("$recentlyBombarded"),
                "the memory flag the outcome carries still rides");
    }

    /** The host's rebroadcast comes straight back at it; the raid ledger makes that a no-op. */
    @Test
    void aSecondApplyOfTheSameBombardmentDecivilizesNothing() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 3);
        market.hasPrimaryEntity = true;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        replicator.handle(decivMessage("guest-player:1"));
        replicator.handle(decivMessage("guest-player:1"));

        assertEquals(1, market.decivilizeEntries);
    }

    /**
     * The host-initiated case: the real DECIV delta already landed, so the market is gone from the
     * economy (vanilla's routine ends with {@code removeMarket}) or already carries the condition,
     * and the RAID_RESULT that follows must not fight a transition that already happened.
     */
    @Test
    void anAlreadyDecivilizedColonyIsLeftAlone() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 3);
        market.hasPrimaryEntity = true;
        market.decivilized = true;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        replicator.handle(decivMessage("guest-player:1"));

        assertEquals(0, market.decivilizeEntries);
    }

    /** An ordinary raid is untouched: nothing decivilizes, and the market state still applies. */
    @Test
    void aNonDecivilizingOutcomeNeverCallsTheDecivRoutine() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addMarket("market_agreus", 6);
        market.addIndustry("heavyindustry");
        market.hasPrimaryEntity = true;
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        replicator.handle(raidMessage("guest-player:1", 12f));

        assertEquals(0, market.decivilizeEntries);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static CoopMessages.Message decivMessage(String outcomeId) {
        CoopRaidOutcomeSync.Outcome outcome = new CoopRaidOutcomeSync.Outcome(outcomeId,
                CoopRaidOutcomeSync.Kind.BOMBARD_SATURATION, "market_agreus", "guest-player",
                3, 0, "", false, false, true, List.of(), List.of());
        return CoopMessages.raidResult("session-a", 1L, 0L, outcome.encode());
    }

    private static CoopMessages.Message raidMessage(String outcomeId, float disruptedDays) {
        CoopRaidOutcomeSync.Outcome outcome = new CoopRaidOutcomeSync.Outcome(outcomeId,
                CoopRaidOutcomeSync.Kind.RAID_DISRUPT, "market_agreus", "guest-player", 6, 0, "",
                false, false, false,
                List.of(new CoopRaidOutcomeSync.IndustryState("heavyindustry", disruptedDays,
                        "", "", "")),
                List.of());
        return CoopMessages.raidResult("session-a", 1L, 0L, outcome.encode());
    }

    private static GroundRaidObjectivePlugin disruptObjective(FakeIndustry source, int marines) {
        Industry industryProxy = source.proxy();
        return (GroundRaidObjectivePlugin) Proxy.newProxyInstance(
                GroundRaidObjectivePlugin.class.getClassLoader(),
                new Class<?>[]{GroundRaidObjectivePlugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMarinesAssigned" -> marines;
                    case "getSource" -> industryProxy;
                    case "toString" -> "Objective";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    private static final class FakeMemory {
        private final Map<String, Object> values = new HashMap<>();

        MemoryAPI proxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[]{MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            values.put((String) args[0], args[1]);
                            yield null;
                        }
                        case "unset" -> {
                            values.remove((String) args[0]);
                            yield null;
                        }
                        case "get" -> values.get((String) args[0]);
                        case "contains" -> values.containsKey((String) args[0]);
                        case "getBoolean" -> Boolean.TRUE.equals(values.get((String) args[0]));
                        case "toString" -> "Memory" + values;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class FakeIndustry {
        private final String id;
        private float disruptedDays;
        private int setDisruptedCalls;
        private Industry cached;

        private FakeIndustry(String id) {
            this.id = id;
        }

        Industry proxy() {
            if (cached != null) {
                return cached;
            }
            cached = (Industry) Proxy.newProxyInstance(
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
                        case "toString" -> "Industry[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    private static final class FakeMarket {
        private final String id;
        private final int size;
        private final Map<String, FakeIndustry> industries = new LinkedHashMap<>();
        private final FakeMemory memory = new FakeMemory();
        private MarketAPI cached;
        /** What {@code hasCondition} answers -- the applier only ever asks about DECIVILIZED here. */
        private boolean decivilized;
        private boolean hasPrimaryEntity;
        /**
         * How many times {@code DecivTracker.decivilize} was entered with this market. Counted off
         * {@code getPrimaryEntity().isDiscoverable()}, vanilla's second statement
         * ({@code DecivTracker.java:197}) -- and answering {@code true} there makes vanilla return
         * immediately, so the count is observable without running the destructive twenty-odd steps
         * that follow against proxies that cannot survive them.
         */
        private int decivilizeEntries;

        private FakeMarket(String id, int size) {
            this.id = id;
            this.size = size;
        }

        private SectorEntityToken primaryEntity() {
            return (SectorEntityToken) Proxy.newProxyInstance(
                    SectorEntityToken.class.getClassLoader(),
                    new Class<?>[]{SectorEntityToken.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isDiscoverable" -> {
                            decivilizeEntries++;
                            yield true;
                        }
                        case "toString" -> "PrimaryEntity[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        FakeIndustry addIndustry(String industryId) {
            FakeIndustry industry = new FakeIndustry(industryId);
            industries.put(industryId, industry);
            return industry;
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
                        case "hasCondition" -> decivilized;
                        case "getPrimaryEntity" -> hasPrimaryEntity ? primaryEntity() : null;
                        case "getMemoryWithoutUpdate", "getMemory" -> memoryProxy;
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
        private final List<Object> listeners = new ArrayList<>();
        private final FakeMemory memory = new FakeMemory();
        private SectorAPI cached;

        FakeMarket addMarket(String id, int size) {
            FakeMarket market = new FakeMarket(id, size);
            markets.put(id, market);
            return market;
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
                        case "getMarketsCopy" -> List.<MarketAPI>of();
                        case "toString" -> "Economy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            MemoryAPI memoryProxy = memory.proxy();
            cached = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getListenerManager" -> listenerManager;
                        case "getEconomy" -> economy;
                        case "getMemoryWithoutUpdate" -> memoryProxy;
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
