package coop.campaign;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.ApiProxies;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Whatever factory was installed before this class ran. {@code PlayerMarketTransaction}'s
     * constructor reads {@code Global.getFactory()}, so the transaction tests below have to install
     * one — and {@code Global} is process-wide, so putting the previous value back rather than
     * nulling it is what keeps this class from deciding anything for the tests that run after it.
     */
    private FactoryAPI previousFactory;

    @BeforeEach
    void rememberFactory() {
        previousFactory = Global.getFactory();
        // Misc builds static Color fields from Global.getSettings() in its <clinit>, and a class
        // whose static init throws stays broken for the life of the JVM. The host rows below reach
        // Misc.getCommissionFactionId through the Phase 32 commission poll.
        Global.setSettings(ApiProxies.whiteSettings());
    }

    @AfterEach
    void reset() {
        Global.setSector(null);
        Global.setFactory(previousFactory);
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

    // ---- Red-team P0-2 / P1-3: the window before a base is paired -------------------------------

    /**
     * The data-loss case. The guest deposits into a mirrored base's locker before
     * {@code CoopBaseAuthority} has paired it: the host's economy has no market by that local
     * {@code genUID}, so it refused the transaction and its canonical locker never recorded the
     * deposit — and the first snapshot after the pairing landed is a full <em>replacement</em>, so
     * the guest's copy of the cargo was wiped too. Gone from both engines. A transaction is a delta
     * and cannot be re-derived from state later, so the only safe answer is to hold it.
     */
    @Test
    void aTransactionAtAnUnpairedHiddenBaseIsHeldThenSentUnderTheHostsId() {
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden().with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = guestReplicator(service);

        replicator.onPlayerMarketTransaction(transaction(LOCAL_ID, Submarkets.SUBMARKET_STORAGE,
                commodityStack("supplies", 40)));

        assertTrue(of(service, CoopMessages.Type.MARKET_TXN).isEmpty(),
                "naming market_LOCAL here is what the host refuses: " + service.sent);

        replicator.marketIds().learn(HOST_ID, LOCAL_ID);

        CoopMessages.Message txn = only(service, CoopMessages.Type.MARKET_TXN);
        assertEquals(HOST_ID, CoopMessages.requiredPayloadString(txn, "marketId"));
        assertEquals("supplies", CoopMessages.requiredPayloadString(txn, "itemId"));
        assertEquals(Submarkets.SUBMARKET_STORAGE,
                CoopMessages.requiredPayloadString(txn, "submarketId"));
    }

    @Test
    void aTransactionAtAnOrdinaryMarketIsNeverHeld() {
        // The hold is scoped to hidden bases with no mapping. Every other market's id agrees across
        // the two engines by construction and must go out on the spot.
        FakeMarket jangala = new FakeMarket("market_jangala").with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(jangala.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);

        guestReplicator(service).onPlayerMarketTransaction(transaction("market_jangala",
                Submarkets.SUBMARKET_OPEN, commodityStack("fuel", 10)));

        assertEquals("market_jangala", CoopMessages.requiredPayloadString(
                only(service, CoopMessages.Type.MARKET_TXN), "marketId"));
    }

    @Test
    void aGuestMarketOpenAtAnUnpairedHiddenBaseIsNotSentAtAll() {
        // Nothing can come back: the host resolves no market by this id, logs at debug and returns.
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden().with(Submarkets.SUBMARKET_OPEN);
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);

        guestReplicator(service).onPlayerOpenedMarket(base.api(), false);

        assertTrue(of(service, CoopMessages.Type.MARKET_OPEN).isEmpty(), service.sent.toString());
    }

    /**
     * The permanent one. A guest unlock reported under its own {@code genUID} wrote
     * {@code coop.storageUnlocked:<guest id>} into the host's save — a market the host does not have
     * and never will, re-broadcast in its baseline every session forever — and left the host's own
     * locker at that base shut, so the shared fee bought one side's access. The migration that
     * exists to clean such a key up only runs on the side that <em>learns</em> a mapping, and the
     * host never learns one.
     */
    @Test
    void aGuestUnlockAtAnUnpairedHiddenBaseIsNotReportedUntilTheMappingLands() {
        FakeMarket base = new FakeMarket(LOCAL_ID).hidden()
                .with(Submarkets.SUBMARKET_STORAGE).paidStorage().docked();
        Global.setSector(base.sector());
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = guestReplicator(service);

        replicator.tickWorldDeltas();

        assertTrue(worldDeltasOfKind(service, CoopWorldDelta.Kind.STORAGE_UNLOCK).isEmpty(),
                "the host must not be handed a market id it can never resolve: " + service.sent);
        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), LOCAL_ID),
                "the paying player's own locker is open regardless; only the report waits");

        replicator.marketIds().learn(HOST_ID, LOCAL_ID);
        replicator.tickWorldDeltas();

        List<CoopMessages.Message> unlocks =
                worldDeltasOfKind(service, CoopWorldDelta.Kind.STORAGE_UNLOCK);
        assertEquals(1, unlocks.size(), service.sent.toString());
        assertEquals(HOST_ID, CoopMessages.requiredPayloadString(unlocks.get(0), "entityId"));
    }

    // ---- Red-team P2-6 / P0-1: the host's session baseline --------------------------------------

    /**
     * Flag lifetime. Nothing ever removes a flag for a market that ceased to exist — pirate bases
     * are destroyed routinely, colonies decivilize — so the host used to emit one {@code WORLD_DELTA}
     * per dead market at every session start, and the receiver added its own copy of each dead key.
     * Both saves grew monotonically and the baseline burst grew with them.
     */
    @Test
    void theHostsBaselineSkipsAFlagWhoseMarketItCannotResolve() {
        FakeMarket live = new FakeMarket("market_live").with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(live.sector());
        CoopStorageUnlock.setFlag(Global.getSector(), "market_live");
        CoopStorageUnlock.setFlag(Global.getSector(), "market_destroyed_base");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);

        hostReplicator(service).tickWorldDeltas();

        assertEquals(List.of("market_live"),
                worldDeltasOfKind(service, CoopWorldDelta.Kind.STORAGE_UNLOCK).stream()
                        .map(m -> CoopMessages.requiredPayloadString(m, "entityId"))
                        .toList());
        assertTrue(CoopStorageUnlock.flagSet(Global.getSector(), "market_destroyed_base"),
                "the key is kept -- a market rebuilt under the same id is still worth opening");
    }

    /**
     * The reconnect re-baseline. A grace window is not a teardown, so the pollers kept running with
     * no peer attached and every send they made was dropped into an empty peer list <em>after</em>
     * the ledger had recorded it. Re-arming alone was not enough: the ledger would have refused the
     * resend for exactly the deltas that were lost. The baseline path records idempotently and sends
     * anyway; the ledger is not cleared, because it also holds the CONSUME set.
     */
    @Test
    void aReArmedBaselineIsResentEvenThoughTheLedgerAlreadyHoldsTheKey() {
        FakeMarket live = new FakeMarket("market_live").with(Submarkets.SUBMARKET_STORAGE);
        Global.setSector(live.sector());
        CoopStorageUnlock.setFlag(Global.getSector(), "market_live");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = hostReplicator(service);

        replicator.tickWorldDeltas();
        assertEquals(1, worldDeltasOfKind(service, CoopWorldDelta.Kind.STORAGE_UNLOCK).size());
        assertEquals(1, worldDeltasOfKind(service, CoopWorldDelta.Kind.COMMISSION).size(),
                "the commission first-poll is unconditional now, empty payload included");

        replicator.rearmSessionBaselines();
        replicator.tickWorldDeltas();

        assertEquals(2, worldDeltasOfKind(service, CoopWorldDelta.Kind.STORAGE_UNLOCK).size(),
                "the ledger already holds STORAGE_UNLOCK:market_live and must not veto the resend");
        assertEquals(2, worldDeltasOfKind(service, CoopWorldDelta.Kind.COMMISSION).size(),
                "and the commission goes with it, so an ended commission reaches the returning peer");
        assertFalse(replicator.worldLedger().isConsumed("market_live"),
                "the baseline path records without touching the consumed-entity set");
    }

    // ---- Harness -------------------------------------------------------------------------------

    private static List<CoopMessages.Message> of(RecordingNetService service, CoopMessages.Type type) {
        return service.sent.stream().filter(m -> m.type() == type).toList();
    }

    private static List<CoopMessages.Message> worldDeltasOfKind(RecordingNetService service,
                                                                CoopWorldDelta.Kind kind) {
        return of(service, CoopMessages.Type.WORLD_DELTA).stream()
                .filter(m -> kind.name().equals(CoopMessages.requiredPayloadString(m, "kind")))
                .toList();
    }

    /**
     * A confirmed purchase of {@code bought} at one submarket. {@code PlayerMarketTransaction}
     * initializes its cargo from {@code Global.getFactory()}, so a factory has to exist before the
     * constructor runs; the bought side is then overwritten and the null sold side reads as
     * "nothing sold".
     */
    private static PlayerMarketTransaction transaction(String marketId, String submarketSpecId,
                                                       CargoStackAPI bought) {
        Global.setFactory(stub(FactoryAPI.class, Map.of()));
        SubmarketAPI submarket = stub(SubmarketAPI.class,
                Map.of("getSpecId", args -> submarketSpecId));
        MarketAPI market = stub(MarketAPI.class, Map.of("getId", args -> marketId));
        PlayerMarketTransaction transaction =
                new PlayerMarketTransaction(market, submarket, CoreUITradeMode.OPEN);
        transaction.setBought(stub(CargoAPI.class,
                Map.of("getStacksCopy", args -> new ArrayList<>(List.of(bought)))));
        transaction.setCreditValue(-1000f);
        return transaction;
    }

    private static CargoStackAPI commodityStack(String commodityId, int size) {
        return stub(CargoStackAPI.class, Map.of(
                "isCommodityStack", args -> true,
                "getCommodityId", args -> commodityId,
                "getSize", args -> (float) size));
    }

    /** Answers the named methods; everything else falls through to its zero value. */
    private interface Answer {
        Object answer(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Answer> answers) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object[] safeArgs = args == null ? new Object[0] : args;
                    switch (method.getName()) {
                        case "toString":
                            return "Stub" + type.getSimpleName();
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == safeArgs[0];
                        default:
                            break;
                    }
                    Answer answer = answers.get(method.getName());
                    return answer == null
                            ? ProxyDefaults.defaultValue(method.getReturnType())
                            : answer.answer(safeArgs);
                });
    }

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
        /**
         * A real {@link StoragePlugin}, not a proxy: {@code CoopStorageUnlock.pluginPaid} reads its
         * private {@code playerPaidToUnlock} through a {@code MethodHandle}, and only the real class
         * has that field.
         */
        private final StoragePlugin storagePlugin = new StoragePlugin();
        private boolean hidden;
        private boolean docked;
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

        /** The 5000-credit fee has been paid on this engine, the way vanilla's "Pay" script does. */
        private FakeMarket paidStorage() {
            storagePlugin.setPlayerPaidToUnlock(true);
            return this;
        }

        /** The local player is standing in this market's dock dialog, which is where the poll looks. */
        private FakeMarket docked() {
            docked = true;
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
                        case "getPlugin" -> Submarkets.SUBMARKET_STORAGE.equals(specId)
                                ? storagePlugin : plugin();
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

        /** {@code getCampaignUI -> getCurrentInteractionDialog -> getInteractionTarget -> market}. */
        private CampaignUIAPI dockDialogUi() {
            MarketAPI market = api();
            SectorEntityToken target = (SectorEntityToken) Proxy.newProxyInstance(
                    SectorEntityToken.class.getClassLoader(),
                    new Class<?>[]{SectorEntityToken.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMarket" -> market;
                        case "toString" -> "FakeDockTarget";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            InteractionDialogAPI dialog = (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getInteractionTarget" -> target;
                        case "toString" -> "FakeDialog";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> ProxyDefaults.defaultValue(method.getReturnType());
                    });
            return (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCurrentInteractionDialog" -> dialog;
                        case "toString" -> "FakeCampaignUi";
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
                        case "getCampaignUI" -> docked ? dockDialogUi() : null;
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
