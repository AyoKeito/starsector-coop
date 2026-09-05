package coop.testing;

import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;

/**
 * Dynamic-proxy stubs for the game API interfaces that most colony, raid and campaign tests have to
 * hand to {@code Global} before the code under test will run.
 *
 * <p>The settings proxy was copied character for character into seven test classes
 * ({@code CoopColonyMgmtReplicatorTest}, {@code CoopColonyReplicatorTest},
 * {@code CoopRaidReplicatorTest}, {@code CoopColonyIncomeTest}, {@code CoopColonyManagementTest},
 * {@code CoopColonySyncTest} and {@code CoopRaidOutcomeSyncTest}); the listener-manager proxy into
 * four of them ({@code CoopColonyMgmtReplicatorTest}, {@code CoopColonyReplicatorTest},
 * {@code CoopRaidReplicatorTest} and {@code CoopSkeletonMutationReplicatorTest}).
 *
 * <p>The memory and memory-backed sector pair arrived with the story-chain gate, whose whole subject
 * is one sector-memory key; the same shape is copied into five other test classes as a private
 * {@code FakeMemory}, which is where new callers should come from rather than a sixth copy.
 *
 * <p>Tests that need a different answer out of either interface still write their own proxy:
 * {@code CoopFleetSnapshotFactoryTest} needs {@code getHullModSpec}, and {@code CoopTimeSnapshotTest}
 * needs a listener manager that reports rather than stores.
 */
public final class ApiProxies {

    private ApiProxies() {
    }

    /**
     * A {@link SettingsAPI} whose only real answer is {@code getColor}, which is white. Everything
     * else falls through to {@link ProxyDefaults#defaultValue(Class)}.
     */
    public static SettingsAPI whiteSettings() {
        return (SettingsAPI) Proxy.newProxyInstance(
                SettingsAPI.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColor" -> Color.WHITE;
                    case "toString" -> "Settings";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * A {@link ListenerManagerAPI} that keeps its registrations in {@code listeners}: an
     * {@code addListener} appends the listener and a {@code removeListener} drops it, so a test can
     * read the collection back to see what the code under test registered.
     */
    public static ListenerManagerAPI listenerManager(Collection<Object> listeners) {
        return (ListenerManagerAPI) Proxy.newProxyInstance(
                ListenerManagerAPI.class.getClassLoader(),
                new Class<?>[]{ListenerManagerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "addListener" -> {
                        listeners.add(args[0]);
                        yield null;
                    }
                    case "removeListener" -> {
                        listeners.remove(args[0]);
                        yield null;
                    }
                    case "toString" -> "ListenerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * A {@link MemoryAPI} backed by {@code values}, so a test can seed keys before the code under
     * test runs and read back what it wrote. {@code getBoolean} answers the way the engine's does:
     * a key that is absent, or holds anything other than {@code Boolean.TRUE}, is false.
     */
    public static MemoryAPI memory(Map<String, Object> values) {
        return (MemoryAPI) Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        values.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "unset" -> {
                        values.remove((String) args[0]);
                        yield null;
                    }
                    case "get" -> values.get((String) args[0]);
                    case "contains" -> values.containsKey((String) args[0]);
                    case "getBoolean" -> Boolean.TRUE.equals(values.get((String) args[0]));
                    case "toString" -> "Memory" + values;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * A {@link SectorAPI} whose only real answer is its memory - both {@code getMemory} and
     * {@code getMemoryWithoutUpdate}, since callers use whichever suits them. Everything else falls
     * through to {@link ProxyDefaults#defaultValue(Class)}, {@code getAllLocations} included, so this
     * is only enough sector for code that reads or writes sector memory.
     *
     * @param memory the memory to hand back; null models a sector that has none yet
     */
    public static SectorAPI sectorWithMemory(MemoryAPI memory) {
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMemory", "getMemoryWithoutUpdate" -> memory;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
