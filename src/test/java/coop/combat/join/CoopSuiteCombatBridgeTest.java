package coop.combat.join;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite adapter must stay optional: absent Suite → no crash; class name stays stable for reflection.
 */
class CoopSuiteCombatBridgeTest {

    @AfterEach
    void detach() {
        CoopCombatJoinCoordinator active = CoopCombatJoinCoordinator.active();
        if (active != null) {
            active.detachIfActive();
        }
    }

    @Test
    void suiteBridgeClassNameIsStable() {
        assertTrue(CoopSuiteCombatBridge.SUITE_BRIDGE_CLASS.startsWith("suite.coop."));
    }

    @Test
    void tryInstallWithoutSuiteLeavesPriorHooks() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        CoopSharedBattleHooks prior = new CoopSharedBattleHooks() {
        };
        join.setSharedBattleHooks(prior);

        boolean installed = CoopSuiteCombatBridge.tryInstall(join);
        if (!CoopSuiteCombatBridge.isSuitePresent()) {
            assertFalse(installed);
            assertSame(prior, join.hooks());
        }
    }

    @Test
    void missingSuiteAcceptBannerExplainsNextStep() {
        CoopSuiteCombatBridge bridge = new CoopSuiteCombatBridge(null);
        assertFalse(bridge.suitePresent());
        List<String> banners = bridge.extraBannersAfterAccept("b1", "Pat");
        assertFalse(banners.isEmpty());
        assertTrue(banners.get(0).contains("BattleOnline Suite"));
    }

    @Test
    void attachAsActiveAttemptsSuiteInstallIdempotently() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        join.attachAsActive();
        assertSame(join, CoopCombatJoinCoordinator.active());
        // Second attach on another instance should replace ACTIVE without throwing.
        CoopCombatJoinCoordinator other = new CoopCombatJoinCoordinator();
        other.attachAsActive();
        assertSame(other, CoopCombatJoinCoordinator.active());
        other.detachIfActive();
    }
}
