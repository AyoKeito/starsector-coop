package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.util.CoopLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import coop.testing.LogCapture;
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
                new RecordingNetService(CoopConnectionRole.HOST), activeHostSession(), () -> 5678L);
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
}
