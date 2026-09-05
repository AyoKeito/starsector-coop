package coop.campaign;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.util.CoopLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import coop.testing.LogCapture;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;

/**
 * Phase 12b: a {@code MARKET_TXN} that cannot reach the engine must say so.
 *
 * <p>The host's open-market cargo is only materialized once that client has docked there, and
 * {@code getCargoNullOk()} returns {@code null} until then. The old code returned silently on that
 * path while the caller logged "applied" unconditionally, so the log asserted success over a no-op
 * and a guest purchase that never propagated looked healthy.
 */
class CoopCampaignReplicatorMarketTxnTest {

    private final LogCapture appender = new LogCapture();

    @AfterEach
    void detachAppenderAndSector() {
        appender.detach();
        Global.setSector(null);
        Global.setFactory(null);
    }

    @Test
    void unmaterializedCargoWarnsAndDoesNotClaimApplied() {
        Global.setSector(sectorWithOpenMarketCargo(null));
        attachAppender();

        hostReplicator().handle(CoopMessages.marketTxn(
                "session-a", 7L, 5000L, "sindria", "COMMODITY", "fuel", 50, 0f, "guest-player"));

        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("not applied to engine")),
                "the unreachable-engine path must warn, naming the market and item");
        assertFalse(appender.messages().stream().anyMatch(m -> m.contains("Coop applied MARKET_TXN")),
                "\"applied\" must not be logged when the engine mutation never ran");
    }

    @Test
    void warnNamesTheMarketAndItemSoTheGapIsDiagnosable() {
        Global.setSector(sectorWithOpenMarketCargo(null));
        attachAppender();

        hostReplicator().handle(CoopMessages.marketTxn(
                "session-a", 7L, 5000L, "sindria", "COMMODITY", "fuel", 50, 0f, "guest-player"));

        String warn = appender.messages().stream()
                .filter(m -> m.contains("not applied to engine"))
                .findFirst()
                .orElseThrow();
        assertTrue(warn.contains("sindria"), "warn should name the market: " + warn);
        assertTrue(warn.contains("fuel"), "warn should name the item: " + warn);
    }

    // ---- Harness ---------------------------------------------------------------------------------

    private void attachAppender() {
        // CoopLog falls back to plain log4j when Global has no logger wired, which is the case here.
        appender.attachTo(CoopLog.getLogger(CoopCampaignReplicator.class));
    }

    /**
     * Phase 12c gap 2d. The vanilla open callback fires more than once per dock session -- once from
     * {@code CampaignState.showInteractionDialog}, again from the core Crew/Cargo screen -- and the
     * comm directory is reachable from the dock dialog in between. Dropping the hire baseline on the
     * second fire threw away the only record of what the guest had been offered, so a hire made in
     * that window was never claimed and the fresh snapshot put the hired officer back in the pool.
     */
    @Test
    void aHireMadeBeforeTheTradeUiOpensIsClaimedOnTheReopen() {
        Global.setSector(sectorWithOpenMarketCargo(null));
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 5678L);
        replicator.setHireBaselineForTest("sindria", Map.of("officer-1", CoopMarketSync.ItemKind.OFFICER));

        // Second open of the same dock session; the market's comm directory no longer offers anyone,
        // which is what "the local player hired them" looks like.
        replicator.onPlayerOpenedMarket(marketOf(Global.getSector()), true);

        List<CoopMessages.Message> hires = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.MARKET_TXN)
                .toList();
        assertEquals(1, hires.size(), "the hire claim must go out before the baseline is dropped: "
                + service.sent);
        assertEquals("officer-1", CoopMessages.requiredPayloadString(hires.get(0), "itemId"));
        assertTrue(replicator.hireBaselineForTest("sindria") == null
                        || replicator.hireBaselineForTest("sindria").isEmpty(),
                "and the baseline is still dropped, so the incoming snapshot installs a fresh one");
    }

    private static MarketAPI marketOf(SectorAPI sector) {
        return sector.getEconomy().getMarket("sindria");
    }

    private static CoopCampaignReplicator hostReplicator() {
        return new CoopCampaignReplicator(
                new SilentNetService(CoopConnectionRole.HOST), activeHostSession(), () -> 5678L);
    }

    /** Sector whose "sindria" open submarket reports the given cargo (null = never materialized). */
    private static SectorAPI sectorWithOpenMarketCargo(Object cargo) {
        SubmarketAPI submarket = (SubmarketAPI) Proxy.newProxyInstance(
                SubmarketAPI.class.getClassLoader(),
                new Class<?>[]{SubmarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCargoNullOk", "getCargo" -> cargo;
                    case "toString" -> "FakeSubmarket";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });

        MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasSubmarket" -> true;
                    case "getSubmarket" -> submarket;
                    case "getId" -> "sindria";
                    case "toString" -> "FakeMarket[sindria]";
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

    // ---- Submarket fence -------------------------------------------------------------------------
    //
    // The host applies every MARKET_TXN to the market's OPEN submarket (hostApplyMarketTxn ->
    // applyItemDeltaToEngine -> openMarketCargo). So anything the guest reports from another
    // submarket lands on the wrong stock: withdrawing 50 fuel from the guest's own storage locker
    // used to delete 50 fuel from the host's open market, and a deposit invented some. Black market
    // and generic_military are per-player shops that are deliberately not synced at all.

    @Test
    void guestStorageWithdrawalIsNotReported() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        guestReplicator(service).onPlayerMarketTransaction(
                transaction("sindria", Submarkets.SUBMARKET_STORAGE, commodityStack("fuel", 50)));

        assertTrue(marketTxns(service).isEmpty(),
                "a storage withdrawal is the player's own cargo, not the host's stock: " + service.sent);
    }

    @Test
    void guestBlackMarketPurchaseIsNotReported() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        guestReplicator(service).onPlayerMarketTransaction(
                transaction("sindria", Submarkets.SUBMARKET_BLACK, commodityStack("drugs", 10)));

        assertTrue(marketTxns(service).isEmpty(),
                "the black market is per-player and not host-synced: " + service.sent);
    }

    @Test
    void guestOpenMarketPurchaseIsReportedOnce() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        guestReplicator(service).onPlayerMarketTransaction(
                transaction("sindria", Submarkets.SUBMARKET_OPEN, commodityStack("fuel", 50)));

        List<CoopMessages.Message> txns = marketTxns(service);
        assertEquals(1, txns.size(), "the open market is the one shop the host mirrors: " + service.sent);
        assertEquals("sindria", CoopMessages.requiredPayloadString(txns.get(0), "marketId"));
        assertEquals(CoopMarketSync.ItemKind.COMMODITY.name(),
                CoopMessages.requiredPayloadString(txns.get(0), "kind"));
        assertEquals("fuel", CoopMessages.requiredPayloadString(txns.get(0), "itemId"));
        assertEquals(50L, CoopMessages.requiredPayloadLong(txns.get(0), "qty"),
                "bought means the item left the host's stock, so the delta is positive");
    }

    @Test
    void guestTransactionWithNoSubmarketIsNotReported() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        attachAppender();

        guestReplicator(service).onPlayerMarketTransaction(
                transaction("sindria", null, commodityStack("fuel", 50)));

        assertTrue(marketTxns(service).isEmpty(),
                "with no submarket there is no way to tell it was the open market: " + service.sent);
        assertTrue(appender.messages().stream().anyMatch(m -> m.contains("has no submarket")),
                "and the drop is warned about, naming the market: " + appender.messages());
    }

    @Test
    void hostStorageTransactionIsNotTalliedAsATrade() {
        AtomicInteger trades = new AtomicInteger();
        CoopCampaignReplicator replicator = hostReplicator();
        replicator.setStatsSink(countingTrades(trades));

        replicator.onPlayerMarketTransaction(
                transaction("sindria", Submarkets.SUBMARKET_STORAGE, commodityStack("fuel", 50)));

        assertEquals(0, trades.get(),
                "moving your own cargo in and out of a locker is not a trade with the market");
    }

    @Test
    void hostOpenMarketTransactionIsStillTallied() {
        AtomicInteger trades = new AtomicInteger();
        CoopCampaignReplicator replicator = hostReplicator();
        replicator.setStatsSink(countingTrades(trades));

        replicator.onPlayerMarketTransaction(
                transaction("sindria", Submarkets.SUBMARKET_OPEN, commodityStack("fuel", 50)));

        assertEquals(1, trades.get(), "the storage skip must not swallow real trades");
    }

    // ---- Transaction harness ---------------------------------------------------------------------

    private static CoopCampaignReplicator guestReplicator(RecordingNetService service) {
        return new CoopCampaignReplicator(service, activeGuestSession(), () -> 5678L);
    }

    private static List<CoopMessages.Message> marketTxns(RecordingNetService service) {
        return service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.MARKET_TXN)
                .toList();
    }

    /** A stats sink that counts nothing but {@code onTrade}; the other three hooks are inert here. */
    private static CoopCampaignReplicator.StatsSink countingTrades(AtomicInteger trades) {
        return new CoopCampaignReplicator.StatsSink() {
            @Override
            public void onTrade(String playerId, String marketId, long netCredits) {
                trades.incrementAndGet();
            }

            @Override
            public void onMissionClaimed(String playerId) {
            }

            @Override
            public void onSalvageConsumed() {
            }

            @Override
            public void onColonyFounded(String playerId) {
            }
        };
    }

    /**
     * A confirmed purchase of {@code bought} at {@code marketId}'s {@code submarketSpecId} shop.
     *
     * <p>{@code PlayerMarketTransaction} is a concrete API class whose {@code bought}/{@code sold}
     * fields initialize from {@code Global.getFactory().createCargo(true)}, so a factory has to be
     * installed before the constructor runs or it throws. The fake one hands back {@code null} and
     * the cargo is then overwritten with {@link #setBought}, which is the only side this test uses -
     * the null {@code sold} side is what "the player bought and sold nothing" looks like to
     * {@code reportCargoDeltas}, which returns on a null cargo.
     */
    private static PlayerMarketTransaction transaction(String marketId, String submarketSpecId,
                                                       CargoStackAPI bought) {
        Global.setFactory(fake(FactoryAPI.class, Map.of()));
        SubmarketAPI submarket = submarketSpecId == null ? null
                : fake(SubmarketAPI.class, Map.<String, Answer>of("getSpecId", args -> submarketSpecId));
        MarketAPI market = fake(MarketAPI.class, Map.<String, Answer>of("getId", args -> marketId));
        PlayerMarketTransaction transaction =
                new PlayerMarketTransaction(market, submarket, CoreUITradeMode.OPEN);
        transaction.setBought(fake(CargoAPI.class, Map.<String, Answer>of(
                "getStacksCopy", args -> new ArrayList<>(List.of(bought)))));
        transaction.setCreditValue(-1000f);
        return transaction;
    }

    private static CargoStackAPI commodityStack(String commodityId, int size) {
        return fake(CargoStackAPI.class, Map.<String, Answer>of(
                "isCommodityStack", args -> true,
                "getCommodityId", args -> commodityId,
                "getSize", args -> (float) size));
    }

    /** Answers the named methods; everything else falls through to its zero value. */
    private interface Answer {
        Object answer(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Answer> answers) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object[] safeArgs = args == null ? new Object[0] : args;
                    switch (method.getName()) {
                        case "toString":
                            return "Fake" + type.getSimpleName();
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
