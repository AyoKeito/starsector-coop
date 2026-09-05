package coop.campaign;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialog poll's decision table and the host's session-start baseline. The engine is a plain
 * fake rather than a proxy: {@link CoopStorageUnlockSync.Engine} exists precisely so these decisions
 * can be exercised without a sector, and {@code CoopStorageUnlockTest} covers the real engine calls
 * the live implementation delegates to.
 */
class CoopStorageUnlockSyncTest {

    private static final long T0 = 1_000_000L;

    @Test
    void aPaidUnflaggedMarketIsCapturedOnceAndFlaggedLocally() {
        FakeEngine engine = new FakeEngine();
        engine.dialogMarket = market("market_jangala");
        engine.paid.add("market_jangala");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        assertEquals("market_jangala", sync.pollDockedUnlock(T0));
        assertTrue(engine.flagged.contains("market_jangala"), "the capturing engine flags it too");

        // Still docked a second later: the flag it just set makes the next poll silent, which is
        // what stops a one-second heartbeat of duplicate deltas for the rest of the dock.
        assertNull(sync.pollDockedUnlock(T0 + CoopStorageUnlockSync.POLL_INTERVAL_MILLIS));
    }

    @Test
    void aPaidAndAlreadyFlaggedMarketReportsNothing() {
        FakeEngine engine = new FakeEngine();
        engine.dialogMarket = market("market_jangala");
        engine.paid.add("market_jangala");
        engine.flagged.add("market_jangala");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        assertNull(sync.pollDockedUnlock(T0));
        assertEquals(0, engine.unlockCalls);
    }

    @Test
    void aFlaggedButUnpaidPluginIsRepairedLocallyAndNotReported() {
        // The rebuilt-mirror case: the coop crew paid, but this engine's market was reconstructed
        // and came back with a fresh, locked StoragePlugin.
        FakeEngine engine = new FakeEngine();
        engine.dialogMarket = market("market_base");
        engine.flagged.add("market_base");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        assertNull(sync.pollDockedUnlock(T0), "a repair is not a capture");
        assertEquals(1, engine.unlockCalls);
        assertEquals(List.of("market_base"), engine.unlocked);
    }

    @Test
    void noDialogAndNoMarketBehindOneAreBothSilent() {
        FakeEngine engine = new FakeEngine();
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        assertNull(sync.pollDockedUnlock(T0), "no dialog open");

        engine.dialogMarket = market(null);
        assertNull(sync.pollDockedUnlock(T0 + CoopStorageUnlockSync.POLL_INTERVAL_MILLIS),
                "a dialog target with a market that has no id");
        assertTrue(engine.flagged.isEmpty());
    }

    @Test
    void pollIsThrottledToOncePerSecond() {
        FakeEngine engine = new FakeEngine();
        engine.dialogMarket = market("market_jangala");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.pollDockedUnlock(T0);
        assertEquals(1, engine.dialogReads);

        // The pump calls this every frame; only one read per interval may reach the engine.
        engine.paid.add("market_jangala");
        assertNull(sync.pollDockedUnlock(T0 + CoopStorageUnlockSync.POLL_INTERVAL_MILLIS - 1));
        assertEquals(1, engine.dialogReads);

        assertEquals("market_jangala",
                sync.pollDockedUnlock(T0 + CoopStorageUnlockSync.POLL_INTERVAL_MILLIS));
        assertEquals(2, engine.dialogReads);
    }

    @Test
    void baselineListsEveryFlaggedMarketExactlyOncePerSession() {
        FakeEngine engine = new FakeEngine();
        engine.flagged.add("market_alpha");
        engine.flagged.add("market_zeta");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        assertEquals(List.of("market_alpha", "market_zeta"), sync.takeBaseline());
        assertEquals(List.of(), sync.takeBaseline(), "once per session, not once per frame");

        // A reconnect tears the session down and builds a new one; the guest on the far side may be
        // a fresh engine that has never heard any of this.
        sync.reset();
        assertEquals(List.of("market_alpha", "market_zeta"), sync.takeBaseline());
    }

    @Test
    void applyingARemoteUnlockOpensAKnownMarket() {
        FakeEngine engine = new FakeEngine();
        MarketAPI known = market("market_jangala");
        engine.markets.put("market_jangala", known);
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.applyRemote("market_jangala");

        assertTrue(engine.flagged.contains("market_jangala"));
        assertEquals(List.of("market_jangala"), engine.unlocked);
    }

    @Test
    void applyingARemoteUnlockForAnUnknownMarketStillSetsTheFlag() {
        // A hidden base the guest has not reconstructed yet, or a colony this engine has not been
        // told about. The flag is what the market's first dialog poll reads once it does exist.
        FakeEngine engine = new FakeEngine();
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.applyRemote("market_ghost");

        assertTrue(engine.flagged.contains("market_ghost"));
        assertEquals(0, engine.unlockCalls);
    }

    @Test
    void applyingAnEmptyMarketIdIsRefused() {
        FakeEngine engine = new FakeEngine();
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.applyRemote("");
        sync.applyRemote(null);

        assertTrue(engine.flagged.isEmpty());
        assertEquals(0, engine.unlockCalls);
    }

    // ---- Fakes ---------------------------------------------------------------------------------

    private static MarketAPI market(String id) {
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "toString" -> "Market[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class FakeEngine implements CoopStorageUnlockSync.Engine {
        private final Set<String> paid = new LinkedHashSet<>();
        private final Set<String> flagged = new LinkedHashSet<>();
        private final Map<String, MarketAPI> markets = new LinkedHashMap<>();
        private final List<String> unlocked = new ArrayList<>();
        private MarketAPI dialogMarket;
        private int dialogReads;
        private int unlockCalls;

        @Override
        public MarketAPI dialogMarket() {
            dialogReads++;
            return dialogMarket;
        }

        @Override
        public MarketAPI findMarket(String marketId) {
            return markets.get(marketId);
        }

        @Override
        public boolean pluginPaid(MarketAPI market) {
            return market != null && paid.contains(market.getId());
        }

        @Override
        public void unlockPlugin(MarketAPI market) {
            unlockCalls++;
            unlocked.add(market == null ? null : market.getId());
            if (market != null && market.getId() != null) {
                paid.add(market.getId());
            }
        }

        @Override
        public boolean flagSet(String marketId) {
            return flagged.contains(marketId);
        }

        @Override
        public boolean setFlag(String marketId) {
            return flagged.add(marketId);
        }

        @Override
        public List<String> flaggedMarketIds() {
            return List.copyOf(flagged);
        }
    }
}
