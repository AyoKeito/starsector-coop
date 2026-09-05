package coop.campaign;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32: a storage snapshot reconciles the locker, it never wipes it.
 *
 * <p>The wipe was the amplifier under the whole ship-detail red team. {@code clearMothballedShips}
 * ran for storage exactly as it does for a shop, so the depositor's own fleet member was destroyed
 * on every dock and replaced by a rebuild of a rebuild of its own blob — every fidelity gap in the
 * codec applied twice, permanently, to a ship nobody sold — and any hull the host's capture happened
 * to omit was deleted from the guest's engine with no log and no way back.
 *
 * <p>These tests pin the four reconcile cases by object identity, because "the ship is still there"
 * and "the ship was rebuilt from a blob" are different facts and only the first one is safe.
 */
class CoopCampaignReplicatorStorageReconcileTest {

    private final LogCapture appender = new LogCapture();

    @AfterEach
    void reset() {
        appender.detach();
        Global.setSector(null);
        Global.setFactory(null);
        Global.setSettings(null);
    }

    // ---- Reconcile: the four cases ---------------------------------------------------------------

    @Test
    void anUnchangedStoredHullIsLeftStrictlyAlone() {
        FakeMarket market = storageMarket();
        FakeShip mine = market.storage().store(new FakeShip("8f9a", "ISS Grudge", "hound_Standard",
                "hound_dhull", 0.31f, 0.62f));
        Global.setSector(market.sector());
        FleetMemberAPI original = mine.api();

        applyStorageSnapshot(market, listingFor(original, "guest-player"));

        assertEquals(1, market.storage().mothballed.size());
        assertSame(original, market.storage().mothballed.get(0),
                "the depositor's own ship must survive its own snapshot as the same object");
        assertEquals("8f9a", mine.id,
                "and keep its local identity; nothing rewrites a live member's id");
    }

    @Test
    void aStoredHullTheHostDescribesDifferentlyIsReplaced() {
        FakeMarket market = storageMarket();
        FakeShip mine = market.storage().store(new FakeShip("8f9a", "ISS Grudge", "hound_Standard",
                "hound_dhull", 0.31f, 0.62f));
        Global.setSector(market.sector());
        installRebuildFactory();
        FleetMemberAPI original = mine.api();
        // The partner repaired it while it sat in the locker: same hull, different CR.
        CoopShipDetail changed = CoopCampaignReplicator.captureShipDetail(original)
                .withMemberId(CoopMemberIds.wireId("guest-player", "8f9a"));
        changed = new CoopShipDetail(changed.memberId(), changed.shipName(), changed.baseVariantId(),
                changed.hullSpecId(), 0.9f, changed.vents(), changed.caps(), changed.permaMods(),
                changed.sMods(), changed.sModdedBuiltIns(), changed.refitMods(),
                changed.suppressedMods(), changed.weapons(), changed.wings(), changed.weaponGroups(),
                changed.hullFraction(), changed.displayName(), changed.modules());

        applyStorageSnapshot(market, shipItem(changed));

        assertEquals(1, market.storage().mothballed.size());
        assertNotSame(original, market.storage().mothballed.get(0),
                "the host is canonical, so a listing that disagrees replaces the local object");
        assertEquals("c_guest-player_8f9a", market.storage().mothballed.get(0).getId(),
                "and the rebuild carries the wire id, which no genUID can collide with");
    }

    @Test
    void aStoredHullTheSnapshotNoLongerListsIsRemoved() {
        FakeMarket market = storageMarket();
        market.storage().store(new FakeShip("8f9a", "ISS Grudge", "hound_Standard", "hound_dhull",
                0.31f, 0.62f));
        Global.setSector(market.sector());

        // The partner withdrew it on their engine; the host's locker no longer lists it.
        applyStorageSnapshot(market);

        assertEquals(List.of(), market.storage().mothballed,
                "a withdrawal on the far side must still empty the near locker");
    }

    @Test
    void aStoredHullOnlyTheHostHasIsBuiltAndAdded() {
        FakeMarket market = storageMarket();
        Global.setSector(market.sector());
        installRebuildFactory();
        FakeShip partners = new FakeShip("c_host-player_364d", "ISS Vigil", "hound_Standard",
                "hound_dhull", 0.7f, 1f);

        applyStorageSnapshot(market, listingFor(partners.api(), "host-player"));

        assertEquals(1, market.storage().mothballed.size(),
                "the partner's deposit has to materialise on this engine");
        assertEquals("c_host-player_364d", market.storage().mothballed.get(0).getId());
    }

    @Test
    void aStorageSnapshotWithNoShipsAtAllStillLeavesTheCargoStacksReplaced() {
        // The reconcile is about hulls only; a commodity stack is fungible and keeps set semantics.
        FakeMarket market = storageMarket();
        market.storage().commodities.put("supplies", 400);
        market.storage().commodities.put("fuel", 250);
        Global.setSector(market.sector());

        applyStorageSnapshot(market, new CoopMarketSync.StockItem(
                CoopMarketSync.ItemKind.COMMODITY, "supplies", 900, 0f));

        assertEquals(900, market.storage().commodities.get("supplies"));
        assertEquals(0, market.storage().commodities.get("fuel"));
    }

    @Test
    void aCommodityListedTwiceInOneSnapshotSums() {
        // P3-11: setCommodityQuantity is set-semantics while every other kind is add-semantics, so a
        // naive per-line loop silently drops the earlier stack.
        FakeMarket market = storageMarket();
        Global.setSector(market.sector());

        applyStorageSnapshot(market,
                new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.COMMODITY, "supplies", 300, 0f),
                new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.COMMODITY, "supplies", 120, 0f));

        assertEquals(420, market.storage().commodities.get("supplies"));
    }

    // ---- Idempotence ------------------------------------------------------------------------------

    @Test
    void theSameMarketTxnDeliveredTwiceAppliesOnce() {
        // MARKET_TXN is on the pump's survives-the-drop-edge list and a detaching peer requeues a
        // partially written frame, so the same line really can arrive twice across a reconnect.
        FakeMarket market = storageMarket();
        market.storage().commodities.put("supplies", 100);
        Global.setSector(market.sector());
        CoopCampaignReplicator host = hostReplicator(new RecordingNetService(CoopConnectionRole.HOST));
        attachAppender();
        CoopMessages.Message deposit = CoopMessages.marketTxn("session-a", 7L, 5000L, "jangala",
                Submarkets.SUBMARKET_STORAGE, "COMMODITY", "supplies", -60, 0f, "guest-player");

        host.handle(deposit);
        host.handle(deposit);

        assertEquals(160, market.storage().commodities.get("supplies"),
                "a re-delivered deposit must not stack a second time");
        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("duplicate delivery")),
                "and it says so: " + appender.messages());
    }

    @Test
    void aSecondDepositOfAHullAlreadyInTheLockerIsSkipped() {
        FakeMarket market = storageMarket();
        Global.setSector(market.sector());
        installRebuildFactory();
        CoopCampaignReplicator host = hostReplicator(new RecordingNetService(CoopConnectionRole.HOST));
        attachAppender();
        String blob = listingFor(new FakeShip("c_guest-player_8f9a", "ISS Grudge", "hound_Standard",
                "hound_dhull", 0.31f, 0.62f).api(), "guest-player").detail();

        // Two different seqs, so the ledger does not catch it: this is the roster guard's case.
        host.handle(CoopMessages.marketTxn("session-a", 7L, 5000L, "jangala",
                Submarkets.SUBMARKET_STORAGE, "SHIP", "c_guest-player_8f9a", -1, 0f, "guest-player",
                blob));
        host.handle(CoopMessages.marketTxn("session-a", 8L, 5001L, "jangala",
                Submarkets.SUBMARKET_STORAGE, "SHIP", "c_guest-player_8f9a", -1, 0f, "guest-player",
                blob));

        assertEquals(1, market.storage().mothballed.size(),
                "one id must never name two hulls: a withdrawal removes the first match and the"
                        + " twin would sit in the locker forever");
        assertTrue(appender.messages().stream()
                        .anyMatch(m -> m.contains("c_guest-player_8f9a") && m.contains("duplicate add")),
                "and it says so: " + appender.messages());
    }

    // ---- The host side of a deposit ---------------------------------------------------------------

    @Test
    void aStorageTransactionAtAnUnflaggedMarketUnlocksItOnTheHost() {
        // P2-6: the guest paid the 5000 credits and the 1 s STORAGE_UNLOCK poll has not fired yet.
        // Accepting the deposit while snapshotTargets still hides storage is what leaves the same
        // hull in both lockers at once.
        FakeMarket market = storageMarket();
        Global.setSector(market.sector());
        assertFalse(market.persistentData.containsKey(CoopStorageUnlock.flagKey("jangala")));
        attachAppender();

        hostReplicator(new RecordingNetService(CoopConnectionRole.HOST)).handle(
                CoopMessages.marketTxn("session-a", 7L, 5000L, "jangala",
                        Submarkets.SUBMARKET_STORAGE, "COMMODITY", "supplies", -10, 0f,
                        "guest-player"));

        assertEquals(Boolean.TRUE, market.persistentData.get(CoopStorageUnlock.flagKey("jangala")),
                "the acceptance is the unlock");
        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("storage unlocked")),
                appender.messages().toString());
    }

    @Test
    void aTransactionForAMarketTheHostCannotResolveNamesTheWholeLine() {
        // P0-1: the guest holds a deposit until a mirrored hidden base is mapped, but a line that
        // gets here anyway is a real loss and has to be diagnosable from the log alone.
        FakeMarket market = storageMarket();
        Global.setSector(market.sector());
        attachAppender();

        hostReplicator(new RecordingNetService(CoopConnectionRole.HOST)).handle(
                CoopMessages.marketTxn("session-a", 7L, 5000L, "no-such-base",
                        Submarkets.SUBMARKET_STORAGE, "SHIP", "c_guest-player_8f9a", -1, 0f,
                        "guest-player", "blob"));

        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("no host-side market")
                        && m.contains("no-such-base") && m.contains(Submarkets.SUBMARKET_STORAGE)
                        && m.contains("SHIP:c_guest-player_8f9a") && m.contains("qty=-1")),
                "submarket, kind, item and quantity all have to be in the line: "
                        + appender.messages());
    }

    // ---- Capture never omits a stored hull silently ------------------------------------------------

    @Test
    void aStoredHullThatCannotBeCapturedIsShippedDegradedRatherThanOmitted() {
        // Ship-detail P0-1 / P2-9: captureShipDetail returns null on any throw anywhere in the
        // capture, and the failure is deterministic, so omitting the line hides the hull from the
        // partner on every snapshot from then on.
        FakeMarket market = storageMarket().storageUnlocked();
        market.storage().store(new FakeShip("364d", "ISS Opaque", "hound_Standard", "hound_dhull",
                0.5f, 1f).withUnreadableHullMods());
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        attachAppender();

        hostReplicator(service).handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "jangala",
                CoopMessages.SUBMARKET_ALL, "guest-player"));

        List<CoopMarketSync.StockItem> shipped = CoopMarketSync.decodeStock(
                CoopMessages.requiredPayloadString(onlySnapshot(service), "stock"));
        assertEquals(1, shipped.size(), "the hull must still be in the shared locker");
        assertEquals("c_host-player_364d", shipped.get(0).itemId());
        CoopShipDetail degraded = CoopShipDetail.decode(shipped.get(0).detail());
        assertEquals("ISS Opaque", degraded.shipName());
        assertEquals("hound_dhull", degraded.hullSpecId());
        assertEquals(List.of(), degraded.permaMods(), "the refit half is what was lost");
        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("degraded listing")),
                "and the loss is named: " + appender.messages());
    }

    // ---- No chunking: an oversized snapshot is refused, loudly --------------------------------------

    @Test
    void anOversizedSnapshotIsNotSentAndThePlayerIsTold() {
        // P2-8: past the transport's 1 MB frame cap the frame is dropped inside CoopNetService and
        // nothing above it learns the snapshot never went. There is no chunking, so the send is
        // refused here instead and the host's player is told which inventory did not go.
        FakeMarket market = storageMarket().storageUnlocked();
        market.storage().commodities.put("x".repeat(1_200_000), 1);
        Global.setSector(market.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        attachAppender();

        hostReplicator(service).handle(CoopMessages.marketOpen("session-a", 7L, 5000L, "jangala",
                CoopMessages.SUBMARKET_ALL, "guest-player"));

        assertEquals(List.of(), service.sent.stream()
                        .filter(m -> m.type() == CoopMessages.Type.MARKET_SNAPSHOT).toList(),
                "a frame the transport would silently drop must not be handed to it");
        assertTrue(market.feed.stream().anyMatch(m ->
                        m.startsWith("Coop: storage at Jangala is too large to share (")),
                "the host's player has to be told: " + market.feed);
        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("MARKET_SNAPSHOT refused")),
                appender.messages().toString());
    }

    // ---- Harness -----------------------------------------------------------------------------------

    private void attachAppender() {
        appender.attachTo(CoopLog.getLogger(CoopCampaignReplicator.class));
    }

    private static CoopMessages.Message onlySnapshot(RecordingNetService service) {
        List<CoopMessages.Message> snapshots = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.MARKET_SNAPSHOT)
                .toList();
        assertEquals(1, snapshots.size(), "expected exactly one snapshot, got " + snapshots.size());
        return snapshots.get(0);
    }

    /** The guest applies one {@code storage} snapshot carrying exactly these items. */
    private void applyStorageSnapshot(FakeMarket market, CoopMarketSync.StockItem... items) {
        guestReplicator().handle(CoopMessages.marketSnapshot("session-a", 7L, 5000L, "jangala",
                Submarkets.SUBMARKET_STORAGE, 1, CoopMarketSync.encodeStock(List.of(items))));
    }

    /** The listing the far engine would ship for this member, under its origin-namespaced id. */
    private static CoopMarketSync.StockItem listingFor(FleetMemberAPI member, String originPlayerId) {
        CoopShipDetail detail = CoopCampaignReplicator.captureShipDetail(member);
        return shipItem(detail.withMemberId(CoopMemberIds.wireId(originPlayerId, detail.memberId())));
    }

    private static CoopMarketSync.StockItem shipItem(CoopShipDetail detail) {
        return new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP, detail.memberId(), 1, 0f,
                detail.encode());
    }

    private static CoopCampaignReplicator guestReplicator() {
        return new CoopCampaignReplicator(new RecordingNetService(CoopConnectionRole.GUEST),
                TestSessions.activeGuestSession(), () -> 5678L);
    }

    private static CoopCampaignReplicator hostReplicator(RecordingNetService service) {
        return new CoopCampaignReplicator(service, TestSessions.activeHostSession(), () -> 5678L);
    }

    private static FakeMarket storageMarket() {
        return new FakeMarket("jangala", "Jangala");
    }

    /**
     * A factory whose members rebuild cleanly, so a "replaced" or "added" hull is a real second
     * object rather than the base-variant fallback.
     */
    private void installRebuildFactory() {
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
                    case "createFleetMember" -> new FakeShip("fresh-" + System.nanoTime(), "",
                            "hound_Standard", "hound_dhull", 0f, 1f).api();
                    case "toString" -> "FakeFactory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                }));
    }

    /** A mutable stand-in for one mothballed hull. */
    private static final class FakeShip {
        private String id;
        private String name;
        private final String variantId;
        private final String hullId;
        private float baseCR;
        private float hullFraction;
        private boolean unreadableHullMods;
        private FleetMemberAPI api;

        private FakeShip(String id, String name, String variantId, String hullId, float baseCR,
                         float hullFraction) {
            this.id = id;
            this.name = name;
            this.variantId = variantId;
            this.hullId = hullId;
            this.baseCR = baseCR;
            this.hullFraction = hullFraction;
        }

        /** A variant whose hull-mod accessor raises, which is what a modded hull looks like here. */
        private FakeShip withUnreadableHullMods() {
            this.unreadableHullMods = true;
            return this;
        }

        private FleetMemberAPI api() {
            if (api != null) {
                return api;
            }
            ShipHullSpecAPI hullSpec = (ShipHullSpecAPI) Proxy.newProxyInstance(
                    ShipHullSpecAPI.class.getClassLoader(),
                    new Class<?>[]{ShipHullSpecAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHullId" -> hullId;
                        case "toString" -> "FakeHullSpec[" + hullId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            ShipVariantAPI variant = (ShipVariantAPI) Proxy.newProxyInstance(
                    ShipVariantAPI.class.getClassLoader(),
                    new Class<?>[]{ShipVariantAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHullVariantId" -> variantId;
                        case "getHullSpec" -> hullSpec;
                        case "getPermaMods" -> {
                            if (unreadableHullMods) {
                                throw new IllegalStateException("unresolvable hull mod set");
                            }
                            yield null;
                        }
                        case "clone" -> proxy;
                        case "toString" -> "FakeVariant[" + variantId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            RepairTrackerAPI tracker = (RepairTrackerAPI) Proxy.newProxyInstance(
                    RepairTrackerAPI.class.getClassLoader(),
                    new Class<?>[]{RepairTrackerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBaseCR" -> baseCR;
                        case "setCR" -> {
                            baseCR = ((Number) args[0]).floatValue();
                            yield null;
                        }
                        case "toString" -> "FakeTracker";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            FleetMemberStatusAPI status = (FleetMemberStatusAPI) Proxy.newProxyInstance(
                    FleetMemberStatusAPI.class.getClassLoader(),
                    new Class<?>[]{FleetMemberStatusAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHullFraction" -> hullFraction;
                        case "setHullFraction" -> {
                            hullFraction = ((Number) args[0]).floatValue();
                            yield null;
                        }
                        case "toString" -> "FakeStatus";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            api = (FleetMemberAPI) Proxy.newProxyInstance(
                    FleetMemberAPI.class.getClassLoader(),
                    new Class<?>[]{FleetMemberAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "setId" -> {
                            id = String.valueOf(args[0]);
                            yield null;
                        }
                        case "getShipName" -> name;
                        case "setShipName" -> {
                            name = String.valueOf(args[0]);
                            yield null;
                        }
                        case "getVariant" -> variant;
                        case "getRepairTracker" -> tracker;
                        case "getStatus" -> status;
                        case "toString" -> "FakeMember[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            return api;
        }
    }

    /** A market whose only shared submarket is storage, plus a recording message feed. */
    private static final class FakeMarket {
        private final String id;
        private final String displayName;
        private final FakeCargo storage = new FakeCargo();
        private final Map<String, Object> persistentData = new HashMap<>();
        private final List<String> feed = new ArrayList<>();

        private FakeMarket(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        private FakeCargo storage() {
            return storage;
        }

        private FakeMarket storageUnlocked() {
            persistentData.put(CoopStorageUnlock.flagKey(id), Boolean.TRUE);
            return this;
        }

        private SectorAPI sector() {
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(),
                    new Class<?>[]{MarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasSubmarket" -> Submarkets.SUBMARKET_STORAGE.equals(args[0]);
                        case "getSubmarket" -> Submarkets.SUBMARKET_STORAGE.equals(args[0])
                                ? submarket() : null;
                        case "getId" -> id;
                        case "getName" -> displayName;
                        case "getPeopleCopy" -> List.of();
                        case "toString" -> "FakeMarket[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
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
            CampaignUIAPI ui = (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addMessage" -> {
                            feed.add(String.valueOf(args[0]));
                            yield null;
                        }
                        case "toString" -> "FakeCampaignUI";
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
                        case "getCampaignUI" -> ui;
                        case "toString" -> "FakeSector";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }

        private SubmarketAPI submarket() {
            return (SubmarketAPI) Proxy.newProxyInstance(
                    SubmarketAPI.class.getClassLoader(),
                    new Class<?>[]{SubmarketAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCargo", "getCargoNullOk" -> storage.proxy();
                        case "toString" -> "FakeSubmarket[storage]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }
    }

    /** Commodity stacks plus a real mothballed roster that supports add <em>and</em> remove. */
    private static final class FakeCargo {
        private final Map<String, Integer> commodities = new LinkedHashMap<>();
        private final List<FleetMemberAPI> mothballed = new ArrayList<>();
        private CargoAPI api;

        private FakeShip store(FakeShip ship) {
            mothballed.add(ship.api());
            return ship;
        }

        private CargoAPI proxy() {
            if (api != null) {
                return api;
            }
            FleetDataAPI ships = (FleetDataAPI) Proxy.newProxyInstance(
                    FleetDataAPI.class.getClassLoader(),
                    new Class<?>[]{FleetDataAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addFleetMember" -> {
                            mothballed.add((FleetMemberAPI) args[0]);
                            yield null;
                        }
                        case "removeFleetMember" -> {
                            mothballed.remove((FleetMemberAPI) args[0]);
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
                        case "getStacksCopy" -> stacks();
                        case "getMothballedShips" -> ships;
                        case "getCommodityQuantity" ->
                                (float) commodities.getOrDefault(String.valueOf(args[0]), 0);
                        case "addCommodity" -> {
                            commodities.merge(String.valueOf(args[0]),
                                    Math.round(((Number) args[1]).floatValue()), Integer::sum);
                            yield null;
                        }
                        case "removeCommodity" -> {
                            commodities.merge(String.valueOf(args[0]),
                                    -Math.round(((Number) args[1]).floatValue()), Integer::sum);
                            yield null;
                        }
                        case "toString" -> "FakeCargo";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            return api;
        }

        private List<CargoStackAPI> stacks() {
            List<CargoStackAPI> stacks = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : commodities.entrySet()) {
                if (entry.getValue() > 0) {
                    stacks.add(commodityStack(entry.getKey(), entry.getValue()));
                }
            }
            return stacks;
        }

        private static CargoStackAPI commodityStack(String commodityId, int size) {
            return (CargoStackAPI) Proxy.newProxyInstance(
                    CargoStackAPI.class.getClassLoader(),
                    new Class<?>[]{CargoStackAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isCommodityStack" -> Boolean.TRUE;
                        case "isSpecialStack", "isWeaponStack", "isFighterWingStack" -> Boolean.FALSE;
                        case "getCommodityId" -> commodityId;
                        case "getSize" -> (float) size;
                        case "toString" -> "FakeStack[" + commodityId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
        }
    }
}
