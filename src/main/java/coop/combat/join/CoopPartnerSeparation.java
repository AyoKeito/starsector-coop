package coop.combat.join;

/**
 * Measured separation between the local player fleet and the partner's player-fleet mirror.
 *
 * @param known          false when either fleet or a shared location could not be resolved
 * @param sameLocation   true when both fleets share a containing location id
 * @param distanceSu     planar distance in the shared location; meaningless when {@code !sameLocation}
 */
public record CoopPartnerSeparation(boolean known, boolean sameLocation, float distanceSu) {

    public static CoopPartnerSeparation unknown() {
        return new CoopPartnerSeparation(false, false, Float.POSITIVE_INFINITY);
    }

    public static CoopPartnerSeparation differentLocation() {
        return new CoopPartnerSeparation(true, false, Float.POSITIVE_INFINITY);
    }

    public static CoopPartnerSeparation sameLocation(float distanceSu) {
        float d = distanceSu < 0f ? 0f : distanceSu;
        return new CoopPartnerSeparation(true, true, d);
    }
}
