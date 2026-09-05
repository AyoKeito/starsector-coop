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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The submarket boundary, Phase 32 edition (this file was the Phase 18 "never touch storage" fence
 * and is now its inverse).
 *
 * <p>Storage <em>is</em> replicated now, which makes the boundary sharper rather than softer: a
 * snapshot apply is still a full <b>replacement</b>, so the submarket a snapshot names is the only
 * thing standing between the host's shop roll and the player's parked ships. These tests pin that
 * naming in every direction — storage writes reach storage and nothing else, shop writes never
 * reach storage, {@code local_resources} is never read or written at all, and a locked storage
 * submarket is not snapshotted by the host in the first place.
 */
class CoopCampaignReplicatorStorageFenceTest {

    @AfterEach
    void clearSector() {
        Global.setSector(null);
    }

    // ---- Snapshot apply: the named submarket, and only it -----------------------------------------

    @Test
    void aStorageSnapshotIsAppliedToStorageAndNotToTheOpenMarket() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400, "fuel", 250));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala", Submarkets.SUBMARKET_STORAGE, 1,
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "supplies", 900, 0f)))));

        assertEquals(900, storage.commodities.get("supplies"),
                "the host's locker is canonical, so the guest's copy takes its quantity");
        assertEquals(0, storage.commodities.get("fuel"),
                "a snapshot is a replacement: fuel the host's locker no longer holds is stripped");
        assertEquals(10, openMarket.commodities.get("fuel"),
                "and the shop shelf is not touched by a locker snapshot");
        assertEquals(List.of(), openMarket.calls,
                "a storage snapshot must not so much as read the open market: " + openMarket.calls);
    }

    @Test
    void aStorageSnapshotMaterialisesTheLockerRatherThanGivingUpOnIt() {
        // getCargoNullOk() is null until something opens the locker on this client. For a shop that
        // means "never stocked, do not invent one"; for a locker it must mean "make it", or the
        // partner's deposit lands nowhere.
        FakeCargo openMarket = new FakeCargo(Map.of());
        FakeCargo storage = new FakeCargo(Map.of());
        FakeMarket market = new FakeMarket("jangala", openMarket, storage).storageUnmaterialised();
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala", Submarkets.SUBMARKET_STORAGE, 1,
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "supplies", 42, 0f)))));

        assertEquals(42, storage.commodities.get("supplies"),
                "storage is reached with getCargo(), which builds the locker on demand");
    }

    @Test
    void anOpenMarketSnapshotNeverTouchesStorage() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400, "fuel", 250));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala", Submarkets.SUBMARKET_OPEN, 1,
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "fuel", 100, 0f)))));

        assertEquals(100, openMarket.commodities.get("fuel"),
                "the open market must take the host's canonical quantity");
        assertEquals(List.of(), storage.calls,
                "the shop's snapshot reached into the player's locker: " + storage.calls);
        assertEquals(400, storage.commodities.get("supplies"), "storage supplies must survive");
        assertEquals(250, storage.commodities.get("fuel"),
                "storage must keep its own fuel, not the host's open-market quantity");
    }

    @Test
    void localResourcesIsNeverReadOrWrittenByASnapshot() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala", Submarkets.LOCAL_RESOURCES, 1,
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "ore", 100, 0f)))));

        assertFalse(market.requestedSubmarkets.contains(Submarkets.LOCAL_RESOURCES),
                "the allowlist refuses local_resources before the market is even asked: "
                        + market.requestedSubmarkets);
        assertEquals(List.of(), openMarket.calls,
                "and it must not silently fall back to the open market: " + openMarket.calls);
        assertEquals(List.of(), storage.calls);
    }

    @Test
    void onlyTheSnapshottedSubmarketIsEverAskedFor() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        guestReplicator().handle(CoopMessages.marketSnapshot(
                "session-a", 7L, 5000L, "jangala", Submarkets.SUBMARKET_OPEN, 1,
                CoopMarketSync.encodeStock(List.of(new CoopMarketSync.StockItem(
                        CoopMarketSync.ItemKind.COMMODITY, "fuel", 100, 0f)))));

        assertTrue(market.requestedSubmarkets.stream().allMatch(Submarkets.SUBMARKET_OPEN::equals),
                "the snapshot path asked for a submarket it was not sent for: "
                        + market.requestedSubmarkets);
    }

    // ---- Transaction apply: same boundary, per line -----------------------------------------------

    @Test
    void aStorageDepositOnTheHostAddsToStorageOnly() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        // A deposit is the "sold" direction: negative qty, the item is left behind.
        hostReplicator().handle(CoopMessages.marketTxn("session-a", 7L, 5000L, "jangala",
                Submarkets.SUBMARKET_STORAGE, "COMMODITY", "supplies", -60, 0f, "guest-player"));

        assertEquals(460, storage.commodities.get("supplies"), "the deposit lands in the locker");
        assertEquals(10, openMarket.commodities.get("fuel"));
        assertEquals(List.of(), openMarket.calls,
                "a locker deposit must not invent shop stock: " + openMarket.calls);
    }

    @Test
    void aStorageWithdrawalOnTheHostRemovesFromStorageOnly() {
        FakeCargo openMarket = new FakeCargo(Map.of("supplies", 500));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        hostReplicator().handle(CoopMessages.marketTxn("session-a", 7L, 5000L, "jangala",
                Submarkets.SUBMARKET_STORAGE, "COMMODITY", "supplies", 60, 0f, "guest-player"));

        assertEquals(340, storage.commodities.get("supplies"));
        assertEquals(500, openMarket.commodities.get("supplies"),
                "deleting the host's shop stock on a locker withdrawal is exactly the old bug");
        assertEquals(List.of(), openMarket.calls);
    }

    @Test
    void aLocalResourcesTransactionIsNeverAppliedToAnything() {
        FakeCargo openMarket = new FakeCargo(Map.of("ore", 100));
        FakeCargo storage = new FakeCargo(Map.of("ore", 5));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());

        hostReplicator().handle(CoopMessages.marketTxn("session-a", 7L, 5000L, "jangala",
                Submarkets.LOCAL_RESOURCES, "COMMODITY", "ore", 30, 0f, "guest-player"));

        assertEquals(100, openMarket.commodities.get("ore"));
        assertEquals(5, storage.commodities.get("ore"));
        assertEquals(List.of(), openMarket.calls);
        assertEquals(List.of(), storage.calls);
    }

    // ---- Storage is only shared once it is unlocked -----------------------------------------------

    @Test
    void theHostDoesNotSnapshotALockedStorageSubmarket() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        new CoopCampaignReplicator(service, TestSessions.activeHostSession(), () -> 5678L)
                .handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "jangala",
                        CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(List.of(Submarkets.SUBMARKET_OPEN), snapshottedSubmarkets(service),
                "nobody has paid the 5000 credits here, so there is no shared locker to ship");
        assertEquals(List.of(), storage.calls,
                "and a locked locker is not even read: " + storage.calls);
    }

    @Test
    void theHostSnapshotsStorageOnceTheCoopUnlockFlagIsSet() {
        FakeCargo openMarket = new FakeCargo(Map.of("fuel", 10));
        FakeCargo storage = new FakeCargo(Map.of("supplies", 400));
        FakeMarket market = new FakeMarket("jangala", openMarket, storage);
        Global.setSector(market.sector());
        market.persistentData.put(CoopStorageUnlock.flagKey("jangala"), Boolean.TRUE);
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        new CoopCampaignReplicator(service, TestSessions.activeHostSession(), () -> 5678L)
                .handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "jangala",
                        CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(List.of(Submarkets.SUBMARKET_OPEN, Submarkets.SUBMARKET_STORAGE),
                snapshottedSubmarkets(service));
        for (CoopMessages.Message snapshot : snapshots(service)) {
            assertEquals(2L, CoopMessages.requiredPayloadLong(snapshot, "submarketCount"),
                    "every snapshot of one open carries the same count, or the guest's gate never"
                            + " knows the market is done");
        }
    }

    // ---- Harness ---------------------------------------------------------------------------------

    private static List<CoopMessages.Message> snapshots(RecordingNetService service) {
        return service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.MARKET_SNAPSHOT)
                .toList();
    }

    private static List<String> snapshottedSubmarkets(RecordingNetService service) {
        return snapshots(service).stream()
                .map(m -> CoopMessages.requiredPayloadString(m, "submarketId"))
                .toList();
    }

    private static CoopCampaignReplicator guestReplicator() {
        return new CoopCampaignReplicator(
                new RecordingNetService(CoopConnectionRole.GUEST), TestSessions.activeGuestSession(),
                () -> 5678L);
    }

    private static CoopCampaignReplicator hostReplicator() {
        return new CoopCampaignReplicator(
                new RecordingNetService(CoopConnectionRole.HOST), TestSessions.activeHostSession(),
                () -> 5678L);
    }

    /** A market with an open submarket and a storage submarket, and nothing else. */
    private static final class FakeMarket {
        private final String id;
        private final FakeCargo openMarket;
        private final FakeCargo storage;
        private final List<String> requestedSubmarkets = new ArrayList<>();
        private final Map<String, Object> persistentData = new HashMap<>();
        /** Models a locker this client has never opened: getCargoNullOk() is null, getCargo() builds it. */
        private boolean storageUnmaterialised;

        private FakeMarket(String id, FakeCargo openMarket, FakeCargo storage) {
            this.id = id;
            this.openMarket = openMarket;
            this.storage = storage;
        }

        private FakeMarket storageUnmaterialised() {
            this.storageUnmaterialised = true;
            return this;
        }

        private SectorAPI sector() {
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasSubmarket" -> {
                            String specId = String.valueOf(args[0]);
                            boolean present = Submarkets.SUBMARKET_OPEN.equals(specId)
                                    || Submarkets.SUBMARKET_STORAGE.equals(specId);
                            if (present) {
                                requestedSubmarkets.add(specId);
                            }
                            yield present;
                        }
                        case "getSubmarket" -> {
                            String specId = String.valueOf(args[0]);
                            requestedSubmarkets.add(specId);
                            if (Submarkets.SUBMARKET_STORAGE.equals(specId)) {
                                yield submarket(storage, storageUnmaterialised);
                            }
                            yield Submarkets.SUBMARKET_OPEN.equals(specId)
                                    ? submarket(openMarket, false) : null;
                        }
                        case "getId" -> id;
                        case "getPeopleCopy" -> List.of();
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
                        case "getPersistentData" -> persistentData;
                        case "toString" -> "FakeSector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }

        /**
         * @param lazyOnly when true {@code getCargoNullOk()} answers null and only {@code getCargo()}
         *                 hands the cargo back, which is how a never-opened submarket behaves.
         */
        private static SubmarketAPI submarket(FakeCargo cargo, boolean lazyOnly) {
            return (SubmarketAPI) Proxy.newProxyInstance(
                    SubmarketAPI.class.getClassLoader(),
                    new Class<?>[]{SubmarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo" -> cargo.proxy();
                        case "getCargoNullOk" -> lazyOnly ? null : cargo.proxy();
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
}
