package coop.combat.join;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: partner separation → proximity vs remote. Never invent a merge when distance is unknown.
 */
class CoopCombatJoinClassifierTest {

    @Test
    void sameLocationInsideAllyRadiusIsProximity() {
        assertEquals(CoopCombatJoinMode.PROXIMITY,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(0f)));
        assertEquals(CoopCombatJoinMode.PROXIMITY,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(700f)));
    }

    @Test
    void sameLocationJustOutsideAllyRadiusIsRemote() {
        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(700.1f)));
        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(5_000f)));
    }

    @Test
    void differentLocationIsAlwaysRemote() {
        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.differentLocation()));
    }

    @Test
    void unknownSeparationFallsBackToRemote() {
        assertEquals(CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.unknown()));
        assertEquals(CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE,
                CoopCombatJoinClassifier.classify(null));
        assertTrue(CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE.isRemote());
        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT,
                CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE.canonical());
    }

    @Test
    void customRadiusIsRespectedAndNegativeClampsToZero() {
        assertEquals(CoopCombatJoinMode.PROXIMITY,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(50f), 50f));
        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(50.1f), 50f));
        assertEquals(CoopCombatJoinMode.PROXIMITY,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(0f), -10f));
        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT,
                CoopCombatJoinClassifier.classify(CoopPartnerSeparation.sameLocation(0.1f), -10f));
    }

    @Test
    void allyPullInHelperMatchesClassifierBoundary() {
        assertTrue(CoopCombatJoinRange.withinAllyPullIn(700f));
        assertFalse(CoopCombatJoinRange.withinAllyPullIn(700.1f));
        assertFalse(CoopCombatJoinRange.withinAllyPullIn(-1f));
    }
}
