package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 18 storage regression fence.
 *
 * <p>A market snapshot apply is a full <em>replacement</em> of the open market's stock, so the day
 * the snapshot path is widened past {@code open_market} — the standing Phase 12c follow-up — the
 * failure mode is silent and expensive: the player's parked ships and cargo in
 * {@code SUBMARKET_STORAGE} get wiped and replaced with the host's shop roll. This pins the
 * boundary: applying a snapshot must not so much as read the storage submarket.
 */
class CoopCampaignReplicatorStorageFenceTest {

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    @Test
    void applyingAMarketSnapshotNeverTouchesStorage() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400, "fuel", 250));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala",
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "fuel", 100, 0f)))));

        assertEquals(List.of(), storage.calls,
                "the snapshot apply reached into storage: " + storage.calls);
        assertEquals(400, storage.commodities.get("supplies"), "storage supplies must survive");
        assertEquals(250, storage.commodities.get("fuel"),
                "storage must keep its own fuel, not the host's open-market quantity");
    }

    @Test
    void theSnapshotStillReplacesTheOpenMarketItself() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala",
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "fuel", 100, 0f)))));

        assertEquals(100, openMarket.commodities.get("fuel"),
                "the open market must take the host's canonical quantity");
    }

    @Test
    void onlyTheOpenSubmarketIsEverAskedFor() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala",
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "fuel", 100, 0f)))));

        assertTrue(market.requestedSubmarkets.stream().allMatch(Submarkets.SUBMARKET_OPEN::equals),
                "the snapshot path asked for a submarket other than open_market: "
                        + market.requestedSubmarkets);
    }

    // ---- Harness ---------------------------------------------------------------------------------

    private static CoopCampaignReplicator guestReplicator() {
        return new CoopCampaignReplicator(
                new SilentNetService(CoopConnectionRole.GUEST), TestSessions.activeGuestSession(),
                () -> 5678L);
    }

    /** A market with both an open submarket and a stocked storage submarket. */
    private static final class FakeMarket {
        private final String id;
        private final FakeCargo openMarket;
        private final FakeCargo storage;
        private final List<String> requestedSubmarkets = new ArrayList<>();

        private FakeMarket(String id, FakeCargo openMarket, FakeCargo storage) {
            this.id = id;
            this.openMarket = openMarket;
            this.storage = storage;
        }

        private SectorAPI sector() {
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasSubmarket" -> {
                            requestedSubmarkets.add(String.valueOf(args[0]));
                            yield true;
                        }
                        case "getSubmarket" -> {
                            requestedSubmarkets.add(String.valueOf(args[0]));
                            yield Submarkets.SUBMARKET_STORAGE.equals(args[0])
                                    ? submarket(storage) : submarket(openMarket);
                        }
                        case "getId" -> id;
                        case "toString" -> "FakeMarket[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });

            EconomyAPI economy = (EconomyAPI) Proxy.newProxyInstance(
                    EconomyAPI.class.getClassLoader(),
                    new Class<?>[]{EconomyAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> market;
                        case "toString" -> "FakeEconomy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });

            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEconomy" -> economy;
                        case "toString" -> "FakeSector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }

        private static SubmarketAPI submarket(FakeCargo cargo) {
            return (SubmarketAPI) Proxy.newProxyInstance(
                    SubmarketAPI.class.getClassLoader(),
                    new Class<?>[]{SubmarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo", "getCargoNullOk" -> cargo.proxy();
                        case "toString" -> "FakeSubmarket";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

    /** Commodity-only cargo that records every call made against it. */
    private static final class FakeCargo {
        private final Map<String, Integer> commodities = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        private FakeCargo(Map<String, Integer> initial) {
            commodities.putAll(initial);
        }

        private CargoAPI proxy() {
            return (CargoAPI) Proxy.newProxyInstance(
                    CargoAPI.class.getClassLoader(),
                    new Class<?>[]{CargoAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "toString":
                                return "FakeCargo";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                break;
                        }
                        calls.add(method.getName());
                        switch (method.getName()) {
                            case "getStacksCopy":
                                return stacks();
                            case "getCommodityQuantity":
                                return (float) commodities.getOrDefault(String.valueOf(args[0]), 0);
                            case "addCommodity":
                                commodities.merge(String.valueOf(args[0]),
                                        Math.round(toFloat(args[1])), Integer::sum);
                                return null;
                            case "removeCommodity":
                                commodities.merge(String.valueOf(args[0]),
                                        -Math.round(toFloat(args[1])), Integer::sum);
                                return null;
                            default:
                                // getMothballedShips and friends: nothing mothballed in this fake.
                                return null;
                        }
                    });
        }

        private List<CargoStackAPI> stacks() {
            List<CargoStackAPI> stacks = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : commodities.entrySet()) {
                stacks.add(commodityStack(entry.getKey(), entry.getValue()));
            }
            return stacks;
        }

        private static float toFloat(Object value) {
            return value instanceof Number number ? number.floatValue() : 0f;
        }

        private static CargoStackAPI commodityStack(String commodityId, int size) {
            return (CargoStackAPI) Proxy.newProxyInstance(
                    CargoStackAPI.class.getClassLoader(),
                    new Class<?>[]{CargoStackAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isCommodityStack" -> Boolean.TRUE;
                        // The classifier checks specials first (Phase 12c gap 2c); a null here would
                        // NPE on unboxing rather than answering "not a special".
                        case "isSpecialStack", "isWeaponStack", "isFighterWingStack" -> Boolean.FALSE;
                        case "getCommodityId" -> commodityId;
                        case "getSize" -> (float) size;
                        case "toString" -> "FakeStack[" + commodityId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

    private static final class SilentNetService extends CoopNetService {
        private final CoopConnectionRole role;

        private SilentNetService(CoopConnectionRole role) {
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
        public void send(CoopMessages.Message message) {
        }
    }
}
