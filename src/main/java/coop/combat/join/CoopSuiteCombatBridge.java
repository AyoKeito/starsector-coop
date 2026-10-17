package coop.combat.join;

import coop.util.CoopLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective adapter to BattleOnline Suite's {@code suite.coop.SuiteCoopBridge}.
 *
 * <p>Coop stays playable without Suite: {@link #tryInstall(CoopCombatJoinCoordinator)} no-ops when
 * the Suite class is absent. When Suite is present, proximity / accept / decline decisions sync the
 * Suite combat-join mode so REMOTE_SUPPORT claim can run inside BattleOnline combat. Campaign-map
 * Accept still cannot invent same-battle multipilot by itself — that needs the combat package.
 */
public final class CoopSuiteCombatBridge implements CoopSharedBattleHooks {

    public static final String SUITE_BRIDGE_CLASS = "suite.coop.SuiteCoopBridge";

    private final boolean suitePresent;
    private final Class<?> bridgeClass;

    /** Visible for same-package tests (pass {@code null} to simulate Suite absent). */
    CoopSuiteCombatBridge(Class<?> bridgeClass) {
        this.bridgeClass = bridgeClass;
        this.suitePresent = bridgeClass != null;
    }

    /** True when Suite's bridge class is on the classpath. */
    public static boolean isSuitePresent() {
        return findBridgeClass() != null;
    }

    /**
     * Installs this adapter on {@code join} when Suite is available; otherwise leaves hooks alone.
     *
     * @return true when Suite hooks were installed
     */
    public static boolean tryInstall(CoopCombatJoinCoordinator join) {
        if (join == null) {
            return false;
        }
        Class<?> cls = findBridgeClass();
        if (cls == null) {
            CoopLog.info(CoopSuiteCombatBridge.class,
                    "Suite combat package not on classpath — join hooks stay NOOP");
            return false;
        }
        join.setSharedBattleHooks(new CoopSuiteCombatBridge(cls));
        CoopLog.info(CoopSuiteCombatBridge.class,
                "Suite combat package linked — proximity/remote decisions will sync join mode");
        return true;
    }

    static Class<?> findBridgeClass() {
        try {
            return Class.forName(SUITE_BRIDGE_CLASS);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    public boolean suitePresent() {
        return suitePresent;
    }

    @Override
    public void onProximityJoinAvailable(String battleId, String partnerName) {
        invoke("onProximityJoinAvailable", battleId, partnerName);
    }

    @Override
    public void onRemoteSupportAccepted(String battleId, String partnerName) {
        invoke("onRemoteSupportAccepted", battleId, partnerName);
    }

    @Override
    public void onRemoteSupportUnavailable(String battleId, CoopRemoteSupportPrompt.State reason) {
        invoke("onRemoteSupportUnavailable", battleId, reason == null ? "" : reason.name());
    }

    @Override
    public void onRemoteBattleEnded(String battleId) {
        invokeOne("onRemoteBattleEnded", battleId);
    }

    @Override
    public List<String> extraBannersAfterAccept(String battleId, String partnerName) {
        List<String> out = new ArrayList<>();
        if (suitePresent) {
            out.add("Coop: BattleOnline Suite linked — REMOTE_SUPPORT claim armed for the next"
                    + " shared combat. Losses stay with the engaged fleet.");
        } else {
            out.add("Coop: accepted for this pause window. Install BattleOnline Suite for"
                    + " hyperspace-comm claim piloting inside shared combat.");
        }
        return out;
    }

    @Override
    public List<String> extraBannersAfterProximity(String battleId, String partnerName) {
        List<String> out = new ArrayList<>();
        if (suitePresent) {
            out.add("Coop: Suite join mode set to proximity (side-by-side). Full dual-pilot sync"
                    + " still depends on the combat package.");
        }
        return out;
    }

    private void invoke(String method, String a, String b) {
        if (bridgeClass == null) {
            return;
        }
        try {
            Method m = bridgeClass.getMethod(method, String.class, String.class);
            m.invoke(null, a == null ? "" : a, b == null ? "" : b);
        } catch (ReflectiveOperationException ex) {
            CoopLog.warn(CoopSuiteCombatBridge.class, "Suite bridge call failed: " + method, ex);
        }
    }

    private void invokeOne(String method, String a) {
        if (bridgeClass == null) {
            return;
        }
        try {
            Method m = bridgeClass.getMethod(method, String.class);
            m.invoke(null, a == null ? "" : a);
        } catch (ReflectiveOperationException ex) {
            CoopLog.warn(CoopSuiteCombatBridge.class, "Suite bridge call failed: " + method, ex);
        }
    }
}
