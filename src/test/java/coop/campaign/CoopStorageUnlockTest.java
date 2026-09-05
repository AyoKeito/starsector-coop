package coop.campaign;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-market storage flag and the {@link java.lang.invoke.MethodHandles} read of vanilla's
 * private {@code StoragePlugin.playerPaidToUnlock}.
 *
 * <p>The plugin here is a <em>real</em> {@link StoragePlugin}, not a proxy, because the handle read
 * is the whole point of the class: a proxy would prove only that the test's own stub answers, while
 * a real instance proves the field name and type still resolve against the shipped
 * {@code starfarer.api.jar}. It constructs cleanly under test — neither it nor
 * {@code BaseSubmarketPlugin} touches {@code Global} in a field initializer or constructor
 * ({@code StoragePlugin.java:17-19}, {@code BaseSubmarketPlugin.java:79-83}).
 */
class CoopStorageUnlockTest {

    @Test
    void flagIsWrittenOncePerMarketAndReadsBack() {
        Map<String, Object> persistent = new HashMap<>();
        SectorAPI sector = sectorWithPersistentData(persistent);

        assertFalse(CoopStorageUnlock.flagSet(sector, "market_jangala"));
        assertTrue(CoopStorageUnlock.setFlag(sector, "market_jangala"));
        assertFalse(CoopStorageUnlock.setFlag(sector, "market_jangala"), "already flagged");
        assertTrue(CoopStorageUnlock.flagSet(sector, "market_jangala"));
        assertEquals(Boolean.TRUE, persistent.get("coop.storageUnlocked:market_jangala"));
    }

    @Test
    void flagKeyIsThePrefixedMarketId() {
        assertEquals("coop.storageUnlocked:market_x", CoopStorageUnlock.flagKey("market_x"));
    }

    @Test
    void missingSectorOrMarketIdIsNeverFlagged() {
        assertFalse(CoopStorageUnlock.flagSet(null, "market_x"));
        assertFalse(CoopStorageUnlock.setFlag(null, "market_x"));
        assertFalse(CoopStorageUnlock.setFlag(sectorWithPersistentData(new HashMap<>()), ""));
    }

    @Test
    void pluginPaidReadsTheRealPrivateField() {
        StoragePlugin plugin = new StoragePlugin();
        MarketAPI market = marketWithStorage("market_jangala", plugin);

        assertFalse(CoopStorageUnlock.pluginPaid(market), "a fresh plugin is locked");
        plugin.setPlayerPaidToUnlock(true);
        assertTrue(CoopStorageUnlock.pluginPaid(market));
    }

    @Test
    void pluginPaidIsFalseWhenThereIsNoStorageAtAll() {
        assertFalse(CoopStorageUnlock.pluginPaid(null));
        assertFalse(CoopStorageUnlock.pluginPaid(marketWithoutStorage("market_bare")));
    }

    @Test
    void unlockPluginWritesTheField() {
        StoragePlugin plugin = new StoragePlugin();
        MarketAPI market = marketWithStorage("market_jangala", plugin);

        CoopStorageUnlock.unlockPlugin(market);

        assertTrue(CoopStorageUnlock.pluginPaid(market));
    }

    @Test
    void unlockReportsTheNewFlagExactlyOnceAndAlwaysOpensThePlugin() {
        Map<String, Object> persistent = new HashMap<>();
        SectorAPI sector = sectorWithPersistentData(persistent);
        StoragePlugin plugin = new StoragePlugin();
        MarketAPI market = marketWithStorage("market_jangala", plugin);

        assertTrue(CoopStorageUnlock.unlock(sector, market), "first unlock is the one to report");
        assertTrue(CoopStorageUnlock.pluginPaid(market));

        // A rebuilt mirrored colony brings a fresh, locked plugin; the flag is already set, so the
        // second unlock reports nothing but still has to open the new plugin.
        StoragePlugin rebuilt = new StoragePlugin();
        MarketAPI rebuiltMarket = marketWithStorage("market_jangala", rebuilt);
        assertFalse(CoopStorageUnlock.unlock(sector, rebuiltMarket), "no second report");
        assertTrue(CoopStorageUnlock.pluginPaid(rebuiltMarket));
    }

    @Test
    void isUnlockedAnswersFromEitherTheFlagOrThePlugin() {
        Map<String, Object> persistent = new HashMap<>();
        SectorAPI sector = sectorWithPersistentData(persistent);
        StoragePlugin plugin = new StoragePlugin();
        MarketAPI market = marketWithStorage("market_jangala", plugin);

        assertFalse(CoopStorageUnlock.isUnlocked(sector, market));

        plugin.setPlayerPaidToUnlock(true);
        assertTrue(CoopStorageUnlock.isUnlocked(sector, market), "local plugin alone is enough");

        StoragePlugin locked = new StoragePlugin();
        MarketAPI lockedMarket = marketWithStorage("market_other", locked);
        CoopStorageUnlock.setFlag(sector, "market_other");
        assertTrue(CoopStorageUnlock.isUnlocked(sector, lockedMarket), "coop flag alone is enough");

        assertFalse(CoopStorageUnlock.isUnlocked(sector, null));
    }

    @Test
    void flaggedMarketIdsListsEveryFlaggedMarketSortedAndNothingElse() {
        Map<String, Object> persistent = new HashMap<>();
        SectorAPI sector = sectorWithPersistentData(persistent);
        CoopStorageUnlock.setFlag(sector, "market_zeta");
        CoopStorageUnlock.setFlag(sector, "market_alpha");
        persistent.put("coop.somethingElse", Boolean.TRUE);
        persistent.put("unrelated", "value");
        // A false value is not an unlock, whatever wrote it.
        persistent.put(CoopStorageUnlock.flagKey("market_no"), Boolean.FALSE);

        assertEquals(List.of("market_alpha", "market_zeta"),
                CoopStorageUnlock.flaggedMarketIds(sector));
    }

    @Test
    void flaggedMarketIdsIsEmptyWithoutASector() {
        assertEquals(List.of(), CoopStorageUnlock.flaggedMarketIds(null));
    }

    @Test
    void clearFlagRemovesOnlyTheNamedMarketAndReportsWhetherItWasSet() {
        Map<String, Object> persistent = new HashMap<>();
        SectorAPI sector = sectorWithPersistentData(persistent);
        CoopStorageUnlock.setFlag(sector, "market_gone");
        CoopStorageUnlock.setFlag(sector, "market_kept");

        assertTrue(CoopStorageUnlock.clearFlag(sector, "market_gone"));
        assertFalse(CoopStorageUnlock.clearFlag(sector, "market_gone"), "already gone");
        assertFalse(CoopStorageUnlock.clearFlag(sector, ""));
        assertFalse(CoopStorageUnlock.clearFlag(null, "market_kept"));
        assertEquals(List.of("market_kept"), CoopStorageUnlock.flaggedMarketIds(sector));
    }

    /**
     * The handle statics are JVM-lifetime, so one failed resolve would otherwise pin every later
     * {@code pluginPaid} read in the process to "not paid" — silently, since {@code warnOnce} says so
     * exactly once. A reset from the mod plugin's teardown window has to make the getter resolve
     * again, which this proves by reading a real field through a freshly resolved handle.
     */
    @Test
    void resettingTheHandlesMakesTheGetterResolveAgain() {
        StoragePlugin plugin = new StoragePlugin();
        MarketAPI market = marketWithStorage("market_jangala", plugin);
        plugin.setPlayerPaidToUnlock(true);
        assertTrue(CoopStorageUnlock.pluginPaid(market), "the handle resolves before the reset");

        CoopStorageUnlock.resetHandlesForReload();

        assertTrue(CoopStorageUnlock.pluginPaid(market), "and again after it");
        // Idempotent: a second teardown with nothing resolved in between is not a failure.
        CoopStorageUnlock.resetHandlesForReload();
        CoopStorageUnlock.resetHandlesForReload();
        assertTrue(CoopStorageUnlock.pluginPaid(market));
    }

    // ---- Engine fakes --------------------------------------------------------------------------

    private static SectorAPI sectorWithPersistentData(Map<String, Object> persistent) {
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentData" -> persistent;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    static MarketAPI marketWithStorage(String id, SubmarketPlugin plugin) {
        SubmarketAPI storage = (SubmarketAPI) Proxy.newProxyInstance(
                SubmarketAPI.class.getClassLoader(),
                new Class<?>[]{SubmarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlugin" -> plugin;
                    case "getSpecId" -> Submarkets.SUBMARKET_STORAGE;
                    case "toString" -> "Submarket[storage]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "hasSubmarket" -> Submarkets.SUBMARKET_STORAGE.equals(args[0]);
                    case "getSubmarket" ->
                            Submarkets.SUBMARKET_STORAGE.equals(args[0]) ? storage : null;
                    case "toString" -> "Market[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static MarketAPI marketWithoutStorage(String id) {
        return (MarketAPI) Proxy.newProxyInstance(
                MarketAPI.class.getClassLoader(),
                new Class<?>[]{MarketAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "hasSubmarket" -> false;
                    case "toString" -> "Market[" + id + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
