package coop.combat.join;

/**
 * Pure classifier: partner separation → join mode. No engine types — fully headless-testable.
 */
public final class CoopCombatJoinClassifier {

    private CoopCombatJoinClassifier() {
    }

    public static CoopCombatJoinMode classify(CoopPartnerSeparation separation) {
        return classify(separation, CoopCombatJoinRange.VANILLA_ALLY_JOIN_RADIUS_SU);
    }

    public static CoopCombatJoinMode classify(CoopPartnerSeparation separation, float allyRadiusSu) {
        if (separation == null || !separation.known()) {
            return CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE;
        }
        if (!separation.sameLocation()) {
            return CoopCombatJoinMode.REMOTE_SUPPORT;
        }
        float radius = allyRadiusSu < 0f ? 0f : allyRadiusSu;
        if (separation.distanceSu() <= radius) {
            return CoopCombatJoinMode.PROXIMITY;
        }
        return CoopCombatJoinMode.REMOTE_SUPPORT;
    }
}
