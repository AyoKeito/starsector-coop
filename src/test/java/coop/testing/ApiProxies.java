package coop.testing;

import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.Collection;

import static coop.testing.ProxyDefaults.defaultValue;

/**
 * Dynamic-proxy stubs for the two game API interfaces that most colony and raid tests have to hand
 * to {@code Global} before the code under test will run.
 *
 * <p>The settings proxy was copied character for character into seven test classes
 * ({@code CoopColonyMgmtReplicatorTest}, {@code CoopColonyReplicatorTest},
 * {@code CoopRaidReplicatorTest}, {@code CoopColonyIncomeTest}, {@code CoopColonyManagementTest},
 * {@code CoopColonySyncTest} and {@code CoopRaidOutcomeSyncTest}); the listener-manager proxy into
 * four of them ({@code CoopColonyMgmtReplicatorTest}, {@code CoopColonyReplicatorTest},
 * {@code CoopRaidReplicatorTest} and {@code CoopSkeletonMutationReplicatorTest}).
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
}
