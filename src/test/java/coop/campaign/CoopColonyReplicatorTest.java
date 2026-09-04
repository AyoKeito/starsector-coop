package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.campaign.listeners.PlayerColonizationListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;
import coop.colony.CoopColonyManagement;
import coop.colony.CoopColonySync;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Phase 24 milestone 2 engine glue: the colonization listener the replicator registers, the per-frame
 * drain, and the host/guest sides of {@code COLONY_FOUNDED} / {@code COLONY_ABANDONED}. The decisions
 * themselves live in {@link CoopColonySync} and are tested there; this covers the wiring.
 */
class CoopColonyReplicatorTest {

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

    @Test
    void aFoundedColonyIsCapturedOnTheTickAndSentOnce() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanet("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        sector.listenerOfType(PlayerColonizationListener.class)
                .reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));
        replicator.tickColonyLifecycle();

        assertTrue(service.sent.isEmpty(), "nothing ships until the market is really a colony");

        market.colonize();
        replicator.tickColonyLifecycle();

        assertEquals(1, service.sent.size());
        assertEquals(CoopMessages.Type.COLONY_FOUNDED, service.sent.get(0).type());
        CoopColonySync.Event event = CoopColonySync.decode(
                CoopMessages.requiredPayloadString(service.sent.get(0), "colony"));
        assertEquals("planet_eos", event.planetId());
        assertEquals("market_planet_eos", event.marketId());
        assertEquals("guest-player", event.actingPlayerId());
        assertEquals("guest-player:1", event.eventId());
        assertEquals(3, event.size());

        // Draining again must not resend: the pending entry is gone and the ledger holds the id.
        replicator.tickColonyLifecycle();
        assertEquals(1, service.sent.size());
    }

    @Test
    void anAbandonedColonyIsSentImmediately() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanet("planet_eos", "market_planet_eos");
        market.colonize();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        sector.listenerOfType(PlayerColonizationListener.class)
                .reportPlayerAbandonedColony(market.proxy());

        assertEquals(1, service.sent.size());
        assertEquals(CoopMessages.Type.COLONY_ABANDONED, service.sent.get(0).type());
        CoopColonySync.Event event = CoopColonySync.decode(
                CoopMessages.requiredPayloadString(service.sent.get(0), "colony"));
        assertEquals(CoopColonySync.Kind.ABANDONED, event.kind());
        assertEquals("planet_eos", event.planetId());
        assertEquals("host-player:1", event.eventId());
    }

    @Test
    void captureIsSkippedWhileApplyingARemoteEvent() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanet("planet_eos", "market_planet_eos");
        market.colonize();
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());

        replicator.replayGuard().begin();
        try {
            PlayerColonizationListener capture =
                    sector.listenerOfType(PlayerColonizationListener.class);
            capture.reportPlayerColonizedPlanet(sector.planetProxy("planet_eos"));
            capture.reportPlayerAbandonedColony(market.proxy());
            replicator.tickColonyLifecycle();
        } finally {
            replicator.replayGuard().end();
        }

        assertTrue(service.sent.isEmpty(),
                "re-driving a remote colony event must not be recaptured as a fresh one");
    }

    @Test
    void theHostAppliesTheGuestsColonyRebroadcastsItAndTheEchoDies() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanet("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);

        CoopMessages.Message inbound = foundedMessage("guest-player:1");
        replicator.handle(inbound);

        assertTrue(market.playerOwned, "the host builds the colony on its canonical market");
        assertEquals(3, market.size);
        assertEquals(1, service.sent.size(), "the host rebroadcasts its canonical view");
        assertEquals(CoopMessages.Type.COLONY_FOUNDED, service.sent.get(0).type());

        market.addIndustryCalls = 0;
        replicator.handle(inbound);
        assertEquals(0, market.addIndustryCalls, "the echo must not rebuild the colony");
        assertTrue(replicator.colonyLedger().isApplied("guest-player:1"));
        assertEquals(2, service.sent.size(), "but the host keeps rebroadcasting: self-healing");
    }

    /** The oscillation the latest-wins ledger exists for, driven end to end through the replicator. */
    @Test
    void theGuestAppliesFoundThenAbandonThenRefoundAndNeverRebroadcasts() {
        FakeSector sector = new FakeSector();
        FakeMarket market = sector.addPlanet("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1_000_000L);

        replicator.handle(foundedMessage("host-player:1"));
        assertTrue(market.playerOwned);

        replicator.handle(abandonedMessage("host-player:2"));
        assertFalse(market.playerOwned);
        assertTrue(market.planetConditionMarketOnly);

        replicator.handle(foundedMessage("host-player:3"));
        assertTrue(market.playerOwned, "re-founding the same planet has to work");

        assertTrue(service.sent.isEmpty(), "a guest never rebroadcasts");
        assertEquals(CoopColonySync.Kind.FOUNDED, replicator.colonyLedger().latestKind("planet_eos"));
    }

    @Test
    void sessionTeardownClearsTheLedgerAndRemovesTheListener() {
        FakeSector sector = new FakeSector();
        sector.addPlanet("planet_eos", "market_planet_eos");
        Global.setSector(sector.proxy());

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1_000_000L);
        replicator.registerOn(sector.proxy());
        replicator.handle(foundedMessage("guest-player:1"));
        assertEquals(1, replicator.colonyLedger().size());

        replicator.dispose(sector.proxy());

        assertEquals(0, replicator.colonyLedger().size());
        assertNull(sector.listenerOfType(PlayerColonizationListener.class));
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static CoopMessages.Message foundedMessage(String eventId) {
        CoopColonySync.Event event = new CoopColonySync.Event(eventId, CoopColonySync.Kind.FOUNDED,
                "planet_eos", "market_planet_eos", "guest-player", "New Hope", "player", 3, false,
                "FULL", true,
                List.of(new CoopColonySync.ConditionState("population_3", true)),
                List.of("population"), List.of("storage"),
                List.of(new CoopColonyManagement.QueueItem("spaceport", 50_000)));
        return CoopMessages.colonyFounded("session-a", 1L, 0L, event.encode());
    }

    private static CoopMessages.Message abandonedMessage(String eventId) {
        return CoopMessages.colonyAbandoned("session-a", 1L, 0L, CoopColonySync.Event.abandoned(
                eventId, "guest-player", "planet_eos", "market_planet_eos").encode());
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    /**
     * Only the surface the recipe and vanilla's teardown touch. {@code getSize} reports 1 while the
     * planet-condition flag is set and {@code setSize} is ignored, exactly like the engine's
     * {@code PlanetConditionMarket}.
     */
    private static final class FakeMarket {
        private final String id;
        private String name;
        private String factionId = "neutral";
        private int size = 1;
        private boolean planetConditionMarketOnly = true;
        private boolean playerOwned;
        private boolean inEconomy;
        private int addIndustryCalls;
        private final List<String> industries = new ArrayList<>();
        private final Map<String, Object> submarkets = new LinkedHashMap<>();
        private SectorEntityToken primary;
        private MarketAPI cached;
        private final MemoryAPI memory = (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "Memory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        private final CommDirectoryAPI commDirectory = (CommDirectoryAPI) Proxy.newProxyInstance(
                CommDirectoryAPI.class.getClassLoader(),
                new Class<?>[]{CommDirectoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "CommDirectory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });

        private FakeMarket(String id) {
            this.id = id;
            this.name = id;
        }

        void colonize() {
            planetConditionMarketOnly = false;
            playerOwned = true;
            inEconomy = true;
            factionId = "player";
            size = 3;
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
                        case "getName" -> name;
                        case "setName" -> {
                            name = (String) args[0];
                            yield null;
                        }
                        case "getFactionId" -> factionId;
                        case "setFactionId" -> {
                            factionId = (String) args[0];
                            yield null;
                        }
                        case "getSize" -> planetConditionMarketOnly ? 1 : size;
                        case "setSize" -> {
                            if (!planetConditionMarketOnly) {
                                size = (Integer) args[0];
                            }
                            yield null;
                        }
                        case "isPlanetConditionMarketOnly" -> planetConditionMarketOnly;
                        case "setPlanetConditionMarketOnly" -> {
                            planetConditionMarketOnly = (Boolean) args[0];
                            yield null;
                        }
                        case "isPlayerOwned" -> playerOwned;
                        case "setPlayerOwned" -> {
                            playerOwned = (Boolean) args[0];
                            yield null;
                        }
                        case "isInEconomy" -> inEconomy;
                        case "getConditions", "getIndustries", "getSubmarketsCopy",
                             "getPeopleCopy" -> List.of();
                        case "hasIndustry" -> industries.contains((String) args[0]);
                        case "addIndustry" -> {
                            industries.add((String) args[0]);
                            addIndustryCalls++;
                            yield null;
                        }
                        case "removeIndustry" -> {
                            industries.remove((String) args[0]);
                            yield null;
                        }
                        case "hasSubmarket" -> submarkets.containsKey((String) args[0]);
                        case "addSubmarket" -> {
                            submarkets.put((String) args[0], null);
                            yield null;
                        }
                        case "removeSubmarket" -> {
                            submarkets.remove((String) args[0]);
                            yield null;
                        }
                        case "getConnectedEntities" -> new LinkedHashSet<>(
                                primary == null ? List.<SectorEntityToken>of() : List.of(primary));
                        case "getPopulation" -> new PopulationComposition();
                        case "getPrimaryEntity" -> primary;
                        case "getMemoryWithoutUpdate", "getMemory" -> memory;
                        case "getCommDirectory" -> commDirectory;
                        case "toString" -> "Market[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return cached;
        }
    }

    private static final class FakeSector {
        private final Map<String, FakeMarket> byMarketId = new LinkedHashMap<>();
        private final Map<String, SectorEntityToken> planets = new LinkedHashMap<>();
        private final List<Object> listeners = new ArrayList<>();
        private SectorAPI cached;

        FakeMarket addPlanet(String planetId, String marketId) {
            FakeMarket market = new FakeMarket(marketId);
            byMarketId.put(marketId, market);
            SectorEntityToken planet = (SectorEntityToken) Proxy.newProxyInstance(
                    PlanetAPI.class.getClassLoader(),
                    new Class<?>[]{PlanetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> planetId;
                        case "getMarket" -> market.proxy();
                        case "toString" -> "Planet[" + planetId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            planets.put(planetId, planet);
            market.primary = planet;
            return market;
        }

        PlanetAPI planetProxy(String planetId) {
            return (PlanetAPI) planets.get(planetId);
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
            ListenerManagerAPI listenerManager = (ListenerManagerAPI) Proxy.newProxyInstance(
                    ListenerManagerAPI.class.getClassLoader(),
                    new Class<?>[]{ListenerManagerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addListener" -> {
                            listeners.add(args[0]);
                            yield null;
                        }
                        case "removeListener" -> {
                            listeners.remove(args[0]);
                            yield null;
                        }
                        case "toString" -> "ListenerManager";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> {
                            FakeMarket market = byMarketId.get((String) args[0]);
                            yield market == null ? null : market.proxy();
                        }
                        case "getMarketsCopy" -> List.<MarketAPI>of();
                        case "addMarket" -> {
                            for (FakeMarket market : byMarketId.values()) {
                                if (market.proxy() == args[0]) {
                                    market.inEconomy = true;
                                }
                            }
                            yield null;
                        }
                        case "removeMarket" -> {
                            for (FakeMarket market : byMarketId.values()) {
                                if (market.proxy() == args[0]) {
                                    market.inEconomy = false;
                                }
                            }
                            yield null;
                        }
                        case "toString" -> "Economy";
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
                        case "getEntityById" -> planets.get((String) args[0]);
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
