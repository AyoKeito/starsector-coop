package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.util.CoopLog;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12b: a {@code MARKET_TXN} that cannot reach the engine must say so.
 *
 * <p>The host's open-market cargo is only materialized once that client has docked there, and
 * {@code getCargoNullOk()} returns {@code null} until then. The old code returned silently on that
 * path while the caller logged "applied" unconditionally, so the log asserted success over a no-op
 * and a guest purchase that never propagated looked healthy.
 */
class CoopCampaignReplicatorMarketTxnTest {

    private final CapturingAppender appender = new CapturingAppender();

    @AfterEach
    void detachAppenderAndSector() {
        Logger.getLogger(CoopCampaignReplicator.class).removeAppender(appender);
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
        CoopLog.getLogger(CoopCampaignReplicator.class).addAppender(appender);
    }

    private static CoopCampaignReplicator hostReplicator() {
        return new CoopCampaignReplicator(
                new SilentNetService(CoopConnectionRole.HOST), activeHostSession(), () -> 5678L);
    }

    private static CoopSessionState activeHostSession() {
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123L, "seed-a", "fingerprint-a");
        return session;
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

    private static final class CapturingAppender extends AppenderSkeleton {
        private final List<String> messages = new ArrayList<>();

        private List<String> messages() {
            return List.copyOf(messages);
        }

        @Override
        protected void append(LoggingEvent event) {
            messages.add(String.valueOf(event.getMessage()));
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
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

    private static final class SequencedIds implements Supplier<String> {
        private final List<String> ids;
        private int index;

        private SequencedIds(String... ids) {
            this.ids = List.of(ids);
        }

        @Override
        public String get() {
            return ids.get(index++);
        }
    }
}
