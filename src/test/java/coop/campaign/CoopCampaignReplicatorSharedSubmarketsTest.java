package coop.campaign;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.LogCapture;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import coop.util.CoopLog;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32: one market open, one snapshot per shared submarket.
 *
 * <p>Three properties are pinned here that no single-submarket test could express: the host fans a
 * {@code MARKET_OPEN} out over every shared submarket it actually has (and no further), every
 * snapshot of that batch agrees on the count so the guest's gate knows when the market is done, and
 * a ship deposited into storage is never dropped on the way in even when this client cannot rebuild
 * it at full fidelity.
 */
class CoopCampaignReplicatorSharedSubmarketsTest {

    private final LogCapture appender = new LogCapture();

    @AfterEach
    void reset() {
        appender.detach();
        Global.setSector(null);
        Global.setFactory(null);
        Global.setSettings(null);
    }

    // ---- Host fan-out ----------------------------------------------------------------------------

    @Test
    void oneOpenProducesOneSnapshotPerSharedSubmarketPresent() {
        FakeMarket market = new FakeMarket("sindria")
                .with(Submarkets.SUBMARKET_OPEN)
                .with(Submarkets.SUBMARKET_BLACK)
                .with(Submarkets.GENERIC_MILITARY)
                .with(Submarkets.SUBMARKET_STORAGE)
                .storageUnlocked();
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        hostReplicator(service).handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "sindria",
                CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(List.of(Submarkets.SUBMARKET_OPEN, Submarkets.SUBMARKET_BLACK,
                        Submarkets.GENERIC_MILITARY, Submarkets.SUBMARKET_STORAGE),
                submarketIds(service));
        for (CoopMessages.Message snapshot : snapshots(service)) {
            assertEquals(4L, CoopMessages.requiredPayloadLong(snapshot, "submarketCount"),
                    "the count is the guest gate's countdown; a disagreeing one wedges the shop");
        }
    }

    @Test
    void aSubmarketTheMarketDoesNotHaveIsNotSnapshotted() {
        FakeMarket market = new FakeMarket("sindria").with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        hostReplicator(service).handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "sindria",
                CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(List.of(Submarkets.SUBMARKET_OPEN), submarketIds(service));
        assertEquals(1L, CoopMessages.requiredPayloadLong(snapshots(service).get(0), "submarketCount"));
    }

    @Test
    void eachShopIsStockedBeforeItIsCaptured() {
        // A shop the host has never docked at has never had stock generated, and publishing that as
        // canonical is what once handed the guest an empty shelf. Storage is deliberately not in the
        // count: StoragePlugin's updateCargoPrePlayerInteraction is empty and there is nothing to roll.
        FakeMarket market = new FakeMarket("sindria")
                .with(Submarkets.SUBMARKET_OPEN)
                .with(Submarkets.SUBMARKET_BLACK)
                .with(Submarkets.SUBMARKET_STORAGE)
                .storageUnlocked();
        Global.setSector(market.sector());

        hostReplicator(new RecordingNetService(CoopConnectionRole.HOST))
                .handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "sindria",
                        CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(List.of(Submarkets.SUBMARKET_OPEN, Submarkets.SUBMARKET_BLACK),
                market.stockUpdates);
    }

    @Test
    void aTargetedOpenReSnapshotsOnlyThatSubmarket() {
        FakeMarket market = new FakeMarket("sindria")
                .with(Submarkets.SUBMARKET_OPEN)
                .with(Submarkets.SUBMARKET_BLACK);
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        hostReplicator(service).handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "sindria",
                Submarkets.SUBMARKET_BLACK, "guest-player"));

        assertEquals(List.of(Submarkets.SUBMARKET_BLACK), submarketIds(service));
        assertEquals(1L, CoopMessages.requiredPayloadLong(snapshots(service).get(0), "submarketCount"));
    }

    @Test
    void anOpenAskingForANonSharedSubmarketIsAnsweredWithNothing() {
        // Answering a local_resources request with the open market's stock is exactly the silent
        // substitution the allowlist exists to prevent.
        FakeMarket market = new FakeMarket("sindria").with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        hostReplicator(service).handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "sindria",
                Submarkets.LOCAL_RESOURCES, "guest-player"));

        assertEquals(List.of(), submarketIds(service));
    }

    // ---- Guest gate ------------------------------------------------------------------------------

    @Test
    void theGuestGateOnlyOpensAfterEveryCountedSnapshotHasApplied() {
        FakeMarket market = new FakeMarket("sindria")
                .with(Submarkets.SUBMARKET_OPEN)
                .with(Submarkets.SUBMARKET_STORAGE)
                .storageUnlocked();
        Global.setSector(market.sector());
        CoopCampaignReplicator replicator = guestReplicator(
                new RecordingNetService(CoopConnectionRole.GUEST));

        replicator.onPlayerOpenedMarket(market.api(), false);
        assertEquals("sindria", replicator.marketSyncGate().pendingMarketId());

        replicator.handle(CoopMessages.marketSnapshot("session-a", 8L, 5100L, "sindria",
                Submarkets.SUBMARKET_OPEN, 2, CoopMarketSync.encodeStock(List.of())));
        assertEquals("sindria", replicator.marketSyncGate().pendingMarketId(),
                "the locker is still the guest's own, so the trade screens stay shut");

        replicator.handle(CoopMessages.marketSnapshot("session-a", 9L, 5200L, "sindria",
                Submarkets.SUBMARKET_STORAGE, 2, CoopMarketSync.encodeStock(List.of())));
        assertNull(replicator.marketSyncGate().pendingMarketId());
    }

    @Test
    void aMarketWhoseOnlySharedSubmarketIsStorageStillArmsTheGate() {
        // Presence, not unlock state: whether a storage snapshot is coming is the host's call, and
        // the gate's own timeout covers the case where none is.
        FakeMarket market = new FakeMarket("outpost").with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(market.sector());
        CoopCampaignReplicator replicator = guestReplicator(
                new RecordingNetService(CoopConnectionRole.GUEST));

        replicator.onPlayerOpenedMarket(market.api(), false);

        assertEquals("outpost", replicator.marketSyncGate().pendingMarketId());
    }

    @Test
    void aMarketWithNoSharedSubmarketAtAllIsNotGated() {
        FakeMarket market = new FakeMarket("derelict-7");
        Global.setSector(market.sector());
        CoopCampaignReplicator replicator = guestReplicator(
                new RecordingNetService(CoopConnectionRole.GUEST));

        replicator.onPlayerOpenedMarket(market.api(), false);

        assertNull(replicator.marketSyncGate().pendingMarketId());
    }

    // ---- Storage never drops a deposit ------------------------------------------------------------

    @Test
    void aStoredShipThatCannotBeRebuiltIsStoredAsItsBaseVariant() {
        FakeMarket market = new FakeMarket("sindria").with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(market.sector());
        installFactoryWhoseRefitAlwaysThrows();
        attachAppender();

        hostReplicator(new RecordingNetService(CoopConnectionRole.HOST)).handle(
                CoopMessages.marketTxn("session-a", 7L, 5000L, "sindria",
                        Submarkets.SUBMARKET_STORAGE, "SHIP", "member-1", -1, 0f, "guest-player",
                        battered("member-1").encode()));

        List<FleetMemberAPI> stored = market.cargo(Submarkets.SUBMARKET_STORAGE).mothballed;
        assertEquals(1, stored.size(), "a deposit the host cannot rebuild is still a deposit");
        assertTrue(appender.messages().stream()
                        .anyMatch(m -> m.contains("member-1") && m.contains("base variant")),
                "and the loss of fidelity is named: " + appender.messages());
    }

    @Test
    void aShopListingThatCannotBeRebuiltIsStillSkipped() {
        // The shop reroll replaces it in 30 days; a pristine hull on a shelf priced as a wreck is
        // worse than a missing one.
        FakeMarket market = new FakeMarket("sindria").with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(market.sector());
        installFactoryWhoseRefitAlwaysThrows();
        attachAppender();

        hostReplicator(new RecordingNetService(CoopConnectionRole.HOST)).handle(
                CoopMessages.marketTxn("session-a", 7L, 5000L, "sindria",
                        Submarkets.SUBMARKET_OPEN, "SHIP", "member-1", -1, 0f, "guest-player",
                        battered("member-1").encode()));

        assertEquals(List.of(), market.cargo(Submarkets.SUBMARKET_OPEN).mothballed);
        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("shop listing skipped")),
                "and it says so: " + appender.messages());
    }

    @Test
    void aStorageWithdrawalThatMatchesNothingWarnsAndNamesTheSubmarket() {
        FakeMarket market = new FakeMarket("sindria").with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(market.sector());
        attachAppender();

        hostReplicator(new RecordingNetService(CoopConnectionRole.HOST)).handle(
                CoopMessages.marketTxn("session-a", 7L, 5000L, "sindria",
                        Submarkets.SUBMARKET_STORAGE, "SHIP", "ghost-9", 1, 0f, "guest-player"));

        assertTrue(appender.messages().stream()
                        .anyMatch(m -> m.contains("ghost-9") && m.contains(Submarkets.SUBMARKET_STORAGE)),
                "the two lockers have drifted, and the next snapshot is what fixes it: "
                        + appender.messages());
    }

    // ---- Harness ---------------------------------------------------------------------------------

    private void attachAppender() {
        appender.attachTo(CoopLog.getLogger(CoopCampaignReplicator.class));
    }

    private static CoopShipDetail battered(String memberId) {
        return new CoopShipDetail(memberId, "ISS Grudge", "hound_Standard", "hound_dhull",
                0.31f, 3, 2, List.of("dmod_engine"), List.of(), List.of(), List.of(), List.of(),
                Map.of("WS0001", "lightmg"), Map.of());
    }

    private static List<CoopMessages.Message> snapshots(RecordingNetService service) {
        return service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.MARKET_SNAPSHOT)
                .toList();
    }

    private static List<String> submarketIds(RecordingNetService service) {
        return snapshots(service).stream()
                .map(m -> CoopMessages.requiredPayloadString(m, "submarketId"))
                .toList();
    }

    private static CoopCampaignReplicator hostReplicator(RecordingNetService service) {
        return new CoopCampaignReplicator(service, TestSessions.activeHostSession(), () -> 5678L);
    }

    private static CoopCampaignReplicator guestReplicator(RecordingNetService service) {
        return new CoopCampaignReplicator(service, TestSessions.activeGuestSession(), () -> 5678L);
    }

    /**
     * A factory whose members exist but whose variants refuse to be cloned, which is what a hull mod
     * or hull spec this client cannot resolve looks like from inside the rebuild. The base-variant
     * fallback never touches the variant, so it survives where the full rebuild does not.
     */
    private void installFactoryWhoseRefitAlwaysThrows() {
        Global.setSettings((SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "doesVariantExist" -> Boolean.TRUE;
                    case "toString" -> "FakeSettings";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                }));
        Global.setFactory((FactoryAPI) Proxy.newProxyInstance(
                FactoryAPI.class.getClassLoader(),
                new Class<?>[]{FactoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createFleetMember" -> unclonableMember();
                    case "toString" -> "FakeFactory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                }));
    }

    private static FleetMemberAPI unclonableMember() {
        ShipVariantAPI variant = (ShipVariantAPI) Proxy.newProxyInstance(
                ShipVariantAPI.class.getClassLoader(),
                new Class<?>[]{ShipVariantAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "clone" -> throw new IllegalStateException("unresolvable hull mod");
                    case "toString" -> "FakeVariant";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        RepairTrackerAPI tracker = (RepairTrackerAPI) Proxy.newProxyInstance(
                RepairTrackerAPI.class.getClassLoader(),
                new Class<?>[]{RepairTrackerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeTracker";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        return (FleetMemberAPI) Proxy.newProxyInstance(
                FleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{FleetMemberAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getVariant" -> variant;
                    case "getRepairTracker" -> tracker;
                    case "toString" -> "FakeMember";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
    }

    /** A market that owns exactly the submarkets a test declares, each with its own cargo. */
    private static final class FakeMarket {
        private final String id;
        private final Map<String, FakeCargo> cargos = new LinkedHashMap<>();
        private final List<String> stockUpdates = new ArrayList<>();
        private final Map<String, Object> persistentData = new HashMap<>();
        private MarketAPI api;

        private FakeMarket(String id) {
            this.id = id;
        }

        private FakeMarket with(String specId) {
            cargos.put(specId, new FakeCargo());
            return this;
        }

        private FakeMarket storageUnlocked() {
            persistentData.put(CoopStorageUnlock.flagKey(id), Boolean.TRUE);
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
                        case "getPlugin" -> plugin(specId);
                        case "toString" -> "FakeSubmarket[" + specId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }

        private com.fs.starfarer.api.campaign.SubmarketPlugin plugin(String specId) {
            return (com.fs.starfarer.api.campaign.SubmarketPlugin) Proxy.newProxyInstance(
                    com.fs.starfarer.api.campaign.SubmarketPlugin.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.campaign.SubmarketPlugin.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "updateCargoPrePlayerInteraction" -> {
                            stockUpdates.add(specId);
                            yield null;
                        }
                        case "toString" -> "FakePlugin[" + specId + "]";
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

    /** An empty cargo that records the mothballed hulls added to it. */
    private static final class FakeCargo {
        private final List<FleetMemberAPI> mothballed = new ArrayList<>();
        private CargoAPI api;

        private CargoAPI proxy() {
            if (api == null) {
                FleetDataAPI ships = (FleetDataAPI) Proxy.newProxyInstance(
                        FleetDataAPI.class.getClassLoader(),
                        new Class<?>[]{FleetDataAPI.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "addFleetMember" -> {
                                mothballed.add((FleetMemberAPI) args[0]);
                                yield null;
                            }
                            case "getMembersListCopy" -> new ArrayList<>(mothballed);
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
