package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32 addition A end to end through the replicator: every market-id-keyed message crossing the
 * wire for a mirrored hidden base names the <em>host's</em> id, and everything arriving from the
 * host lands on the guest's own copy of that base.
 *
 * <p>The setup is the one that used to be broken. The guest's pirate base lives at
 * {@code market_LOCAL} because {@code PirateBaseIntel}'s constructor minted it there with
 * {@code Misc.genUID()} ({@code PirateBaseIntel.java:173}); the host's copy of the same base is
 * {@code market_HOST}. Before the mapping, a guest {@code MARKET_OPEN} named a market the host's
 * economy could not find and a host {@code MARKET_SNAPSHOT} named one the guest's could not, so the
 * base's trade screen opened unsynced in both directions.
 */
class CoopCampaignReplicatorBaseMarketIdsTest {

    private static final String HOST_ID = "market_HOST";
    private static final String LOCAL_ID = "market_LOCAL";

    @AfterEach
    void reset() {
        Global.setSector(null);
    }

    // ---- Guest send side -----------------------------------------------------------------------

    @Test
    void aGuestMarketOpenOnALocalBaseIdReachesTheHostAsTheHostId() {
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden().with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = guestReplicator(service);
        replicator.marketIds().learn(HOST_ID, LOCAL_ID);

        replicator.onPlayerOpenedMarket(base.api(), false);

        CoopMessages.Message open = only(service, CoopMessages.Type.MARKET_OPEN);
        assertEquals(HOST_ID, CoopMessages.requiredPayloadString(open, "marketId"),
                "the host's economy has no market_LOCAL; naming it is how this used to fail");
    }

    @Test
    void anUnmappedMarketIsStillSentUnderItsOwnId() {
        // The other ~150 markets. Colony and gen-time ids agree across the two engines by
        // construction, and the translation must leave them completely alone.
        FakeMarket jangala = new FakeMarket("market_jangala").with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(jangala.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = guestReplicator(service);
        replicator.marketIds().learn(HOST_ID, LOCAL_ID);

        replicator.onPlayerOpenedMarket(jangala.api(), false);

        assertEquals("market_jangala",
                CoopMessages.requiredPayloadString(only(service, CoopMessages.Type.MARKET_OPEN), "marketId"));
    }

    // ---- Guest receive side --------------------------------------------------------------------

    @Test
    void aSnapshotForAHostBaseIdAppliesToTheLocalBaseMarket() {
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden().with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = guestReplicator(service);
        replicator.marketIds().learn(HOST_ID, LOCAL_ID);

        replicator.handle(CoopMessages.marketSnapshot("session-a", 7L, 5000L, HOST_ID,
                Submarkets.SUBMARKET_OPEN, 1, CoopMarketSync.encodeStock(List.of(
                        new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.COMMODITY, "supplies",
                                40, 0f, "")))));

        assertEquals(Map.of("supplies", 40),
                base.cargo(Submarkets.SUBMARKET_OPEN).commodities);
    }

    @Test
    void aStorageUnlockForAHostBaseIdUnlocksTheLocalBaseMarket() {
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden()
                .with(Submarkets.SUBMARKET_OPEN)
                .with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = guestReplicator(service);
        replicator.marketIds().learn(HOST_ID, LOCAL_ID);

        replicator.handle(storageUnlock(HOST_ID));

        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), LOCAL_ID),
                "the flag has to name the market this engine can actually find");
    }

    @Test
    void aStorageUnlockThatArrivesBeforeTheMappingIsMigratedWhenTheMappingLands() {
        // The BASE_SET and the world delta are independent messages and either can win. When the
        // delta lands first there is no local market to name, so applyRemote parks the flag under
        // the host's id; the mapping is what un-parks it.
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden()
                .with(Submarkets.SUBMARKET_OPEN)
                .with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(base.sector());
        CoopCampaignReplicator replicator =
                guestReplicator(new RecordingNetService(CoopConnectionRole.GUEST));

        replicator.handle(storageUnlock(HOST_ID));
        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), HOST_ID), "parked, as documented");

        replicator.marketIds().learn(HOST_ID, LOCAL_ID);

        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), LOCAL_ID));
        assertEquals(List.of(LOCAL_ID), CoopStorageUnlock.flaggedMarketIds(Global.getSector()),
                "the host-id key must not survive: flaggedMarketIds is resent as the baseline");
    }

    // ---- Host receive side ---------------------------------------------------------------------

    @Test
    void theHostResolvesAGuestMarketOpenAgainstItsOwnEconomyUntranslated() {
        // The host's table is always empty, so toLocal is the identity there and the base's own id
        // resolves straight through. Pinned because the receive path now runs a translation on both
        // roles and it must be a no-op on this one.
        FakeMarket base = new FakeMarket(HOST_ID).hidden().with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = hostReplicator(service);

        replicator.handle(CoopMessages.marketOpen("session-a", 7L, 5000L, HOST_ID,
                CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(0, replicator.marketIds().size());
        CoopMessages.Message snapshot = only(service, CoopMessages.Type.MARKET_SNAPSHOT);
        assertEquals(HOST_ID, CoopMessages.requiredPayloadString(snapshot, "marketId"));
    }

    // ---- Harness -------------------------------------------------------------------------------

    private static CoopMessages.Message storageUnlock(String marketId) {
        return CoopMessages.worldDelta("session-a", 7L, 5000L, marketId,
                CoopWorldDelta.Kind.STORAGE_UNLOCK.name(), false, "true", "host-player");
    }

    private static CoopMessages.Message only(RecordingNetService service, CoopMessages.Type type) {
        List<CoopMessages.Message> found = service.sent.stream()
                .filter(m -> m.type() == type)
                .toList();
        assertEquals(1, found.size(), "expected exactly one " + type + ", got " + service.sent);
        return found.get(0);
    }

    private static CoopCampaignReplicator hostReplicator(RecordingNetService service) {
        return new CoopCampaignReplicator(service, TestSessions.activeHostSession(), () -> 5678L);
    }

    private static CoopCampaignReplicator guestReplicator(RecordingNetService service) {
        return new CoopCampaignReplicator(service, TestSessions.activeGuestSession(), () -> 5678L);
    }

    /** A market with the submarkets a test declares; {@link #hidden()} makes it a base market. */
    private static final class FakeMarket {
        private final String id;
        private final Map<String, FakeCargo> cargos = new LinkedHashMap<>();
        private final Map<String, Object> persistentData = new HashMap<>();
        private boolean hidden;
        private MarketAPI api;

        private FakeMarket(String id) {
            this.id = id;
        }

        private FakeMarket with(String specId) {
            cargos.put(specId, new FakeCargo());
            return this;
        }

        /** Pirate and Luddic-Path base markets are {@code setHidden(true)} in their constructors. */
        private FakeMarket hidden() {
            hidden = true;
            return this;
        }

        private FakeCargo cargo(String specId) {
            FakeCargo cargo = cargos.get(specId);
            assertNotNull(cargo, "no fake cargo for " + specId);
            return cargo;
        }

        private MarketAPI api() {
            if (api == null) {
                api = (MarketAPI) Proxy.newProxyInstance(
                        MarketAPI.class.getClassLoader(),
                        new Class<?>[]{MarketAPI.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "hasSubmarket" -> cargos.containsKey(String.valueOf(args[0]));
                            case "getSubmarket" -> submarket(String.valueOf(args[0]));
                            case "getId" -> id;
                            case "isHidden" -> hidden;
                            case "getPeopleCopy" -> List.of();
                            case "toString" -> "FakeMarket[" + id + "]";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> ProxyDefaults.defaultValue(method.getReturnType());
                        });
            }
            return api;
        }

        private SubmarketAPI submarket(String specId) {
            FakeCargo cargo = cargos.get(specId);
            if (cargo == null) {
                return null;
            }
            return (SubmarketAPI) Proxy.newProxyInstance(
                    SubmarketAPI.class.getClassLoader(),
                    new Class<?>[]{SubmarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo", "getCargoNullOk" -> cargo.proxy();
                        case "getPlugin" -> plugin();
                        case "toString" -> "FakeSubmarket[" + specId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }

        private SubmarketPlugin plugin() {
            return (SubmarketPlugin) Proxy.newProxyInstance(
                    SubmarketPlugin.class.getClassLoader(),
                    new Class<?>[]{SubmarketPlugin.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakePlugin";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }

        private SectorAPI sector() {
            MarketAPI market = api();
            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> id.equals(args[0]) ? market : null;
                        case "toString" -> "FakeEconomy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEconomy" -> economy;
                        case "getPersistentData" -> persistentData;
                        case "toString" -> "FakeSector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }
    }

    /** Records the commodity quantities a snapshot apply writes into it. */
    private static final class FakeCargo {
        private final Map<String, Integer> commodities = new LinkedHashMap<>();
        private CargoAPI api;

        private CargoAPI proxy() {
            if (api == null) {
                FleetDataAPI ships = (FleetDataAPI) Proxy.newProxyInstance(
                        FleetDataAPI.class.getClassLoader(),
                        new Class<?>[]{FleetDataAPI.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getMembersListCopy" -> new ArrayList<>();
                            case "toString" -> "FakeShips";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> ProxyDefaults.defaultValue(method.getReturnType());
                        });
                api = (CargoAPI) Proxy.newProxyInstance(
                        CargoAPI.class.getClassLoader(),
                        new Class<?>[]{CargoAPI.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getStacksCopy" -> List.of();
                            case "getMothballedShips" -> ships;
                            case "addCommodity" -> {
                                commodities.merge(String.valueOf(args[0]),
                                        Math.round((Float) args[1]), Integer::sum);
                                yield null;
                            }
                            case "toString" -> "FakeCargo";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> ProxyDefaults.defaultValue(method.getReturnType());
                        });
            }
            return api;
        }
    }
}
