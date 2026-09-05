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

    // ---- Phase 32 addition A: the parked-flag migration ------------------------------------

    @Test
    void learningABaseMarketIdMovesTheFlagParkedUnderTheHostsIdAndOpensTheLocker() {
        // A STORAGE_UNLOCK for a hidden base can arrive before CoopBaseAuthority has paired that
        // base, and applyRemote then flags it under the host's id -- an id that names no market
        // here and that no rebuild would ever resolve, because the local base answers to its own
        // genUID id. Learning the mapping is the one moment the flag can be moved.
        FakeEngine engine = new FakeEngine();
        engine.markets.put("market_LOCAL", market("market_LOCAL"));
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);
        sync.applyRemote("market_HOST");
        assertTrue(engine.flagged.contains("market_HOST"));
        assertEquals(0, engine.unlockCalls, "there was no local market to open under that id");

        sync.onMarketIdMapped("market_HOST", "market_LOCAL");

        assertEquals(List.of("market_LOCAL"), List.copyOf(engine.flagged),
                "the host-id key must go: the host resends flaggedMarketIds as its baseline");
        assertEquals(List.of("market_LOCAL"), engine.unlocked);
    }

    @Test
    void learningAMappingWithNoParkedFlagChangesNothing() {
        // The normal case, and it runs for every base on every reconcile, so it has to be cheap and
        // silent rather than inventing an unlock nobody paid for.
        FakeEngine engine = new FakeEngine();
        engine.markets.put("market_LOCAL", market("market_LOCAL"));
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.onMarketIdMapped("market_HOST", "market_LOCAL");

        assertTrue(engine.flagged.isEmpty());
        assertEquals(0, engine.unlockCalls);
    }

    @Test
    void aParkedFlagIsStillMovedWhenTheLocalMarketCannotBeResolvedYet() {
        FakeEngine engine = new FakeEngine();
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);
        sync.applyRemote("market_HOST");

        sync.onMarketIdMapped("market_HOST", "market_LOCAL");

        assertEquals(List.of("market_LOCAL"), List.copyOf(engine.flagged));
        assertEquals(0, engine.unlockCalls);
    }

    /**
     * Red-team P1-4. A base destroyed and rebuilt in the same system keeps its
     * {@code (kind, systemId)} identity, so {@code CoopMarketIds.learn} hands the same host id a
     * fresh local id — and by then the flag is parked under the <em>old local</em> id, because the
     * first mapping already moved it off the host's. Migrating only from the host id did nothing:
     * the rebuilt base's locker stayed shut, the dead key stayed in the save, and the 1 Hz repair
     * could not help because it reads the flag under the new id.
     */
    @Test
    void aFlagParkedUnderTheDisplacedLocalIdIsMigratedToTheRebuiltMarket() {
        FakeEngine engine = new FakeEngine();
        engine.markets.put("market_REBUILT", market("market_REBUILT"));
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);
        sync.applyRemote("market_HOST");
        sync.onMarketIdMapped("market_HOST", "market_LOCAL");
        assertEquals(List.of("market_LOCAL"), List.copyOf(engine.flagged));

        sync.onMarketIdMapped("market_HOST", "market_REBUILT", "market_LOCAL");

        assertEquals(List.of("market_REBUILT"), List.copyOf(engine.flagged),
                "the dead local key must go with it, or it rides the save forever");
        assertEquals(List.of("market_REBUILT"), engine.unlocked);
    }

    @Test
    void aFlagUnderBothTheHostIdAndTheDisplacedLocalIdIsFullyCollapsed() {
        FakeEngine engine = new FakeEngine();
        engine.flagged.add("market_HOST");
        engine.flagged.add("market_LOCAL");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.onMarketIdMapped("market_HOST", "market_REBUILT", "market_LOCAL");

        assertEquals(List.of("market_REBUILT"), List.copyOf(engine.flagged));
    }

    /**
     * Red-team P2-7. The repair does not stick when {@code playerPaidToUnlock} is unreadable or the
     * storage plugin is not a {@code StoragePlugin} — {@code unlockPlugin} is a silent no-op there —
     * and the old code then re-ran it, and logged, on every 1 Hz poll for as long as the dialog was
     * open. Once per market per dialog; the next dock gets its one attempt.
     */
    @Test
    void aRepairThatDoesNotStickIsAttemptedOncePerMarketPerDialog() {
        FakeEngine engine = new FakeEngine();
        engine.unlockIsANoOp = true;
        engine.dialogMarket = market("market_base");
        engine.flagged.add("market_base");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);
        long now = T0;

        assertNull(sync.pollDockedUnlock(now));
        assertEquals(1, engine.unlockCalls);

        for (int poll = 0; poll < 5; poll++) {
            assertNull(sync.pollDockedUnlock(now += CoopStorageUnlockSync.POLL_INTERVAL_MILLIS));
        }
        assertEquals(1, engine.unlockCalls, "one attempt, not one per second");

        // Undock, then dock again: a fresh dialog is a fresh chance.
        engine.dialogMarket = null;
        assertNull(sync.pollDockedUnlock(now += CoopStorageUnlockSync.POLL_INTERVAL_MILLIS));
        engine.dialogMarket = market("market_base");
        assertNull(sync.pollDockedUnlock(now + CoopStorageUnlockSync.POLL_INTERVAL_MILLIS));

        assertEquals(2, engine.unlockCalls);
    }

    @Test
    void dockingAtASecondMarketGetsItsOwnRepairAttempt() {
        FakeEngine engine = new FakeEngine();
        engine.unlockIsANoOp = true;
        engine.flagged.add("market_a");
        engine.flagged.add("market_b");
        engine.dialogMarket = market("market_a");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        assertNull(sync.pollDockedUnlock(T0));
        engine.dialogMarket = market("market_b");
        assertNull(sync.pollDockedUnlock(T0 + CoopStorageUnlockSync.POLL_INTERVAL_MILLIS));

        assertEquals(List.of("market_a", "market_b"), engine.unlocked);
    }

    @Test
    void degenerateMappingsAreIgnored() {
        FakeEngine engine = new FakeEngine();
        engine.flagged.add("market_HOST");
        CoopStorageUnlockSync sync = new CoopStorageUnlockSync(engine);

        sync.onMarketIdMapped("market_HOST", "market_HOST");
        sync.onMarketIdMapped(null, "market_LOCAL");
        sync.onMarketIdMapped("market_HOST", "");

        assertEquals(List.of("market_HOST"), List.copyOf(engine.flagged));
    }

    private static final class FakeEngine implements CoopStorageUnlockSync.Engine {
        private final Set<String> paid = new LinkedHashSet<>();
        private final Set<String> flagged = new LinkedHashSet<>();
        private final Map<String, MarketAPI> markets = new LinkedHashMap<>();
        private final List<String> unlocked = new ArrayList<>();
        private MarketAPI dialogMarket;
        private int dialogReads;
        private int unlockCalls;
        /**
         * Models the two ways {@code unlockPlugin} cannot take: an unreadable
         * {@code playerPaidToUnlock}, and a storage submarket whose plugin is not a
         * {@code StoragePlugin}. Both leave {@code pluginPaid} false forever.
         */
        private boolean unlockIsANoOp;

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
            if (!unlockIsANoOp && market != null && market.getId() != null) {
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
        public boolean clearFlag(String marketId) {
            return flagged.remove(marketId);
        }

        @Override
        public List<String> flaggedMarketIds() {
            return List.copyOf(flagged);
        }
    }
}
