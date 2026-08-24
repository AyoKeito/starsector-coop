package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two decisions the host makes for a guest's world-affecting ability, both extracted as pure
 * statics so they can be exercised without an engine: which ability gets run on the guest mirror,
 * and which fleets the guest's interdiction pulse costs standing with.
 */
class CoopAbilityEffectApplierTest {

    // ---- Routing -------------------------------------------------------------------------------

    @Test
    void interdictionAndDistressCallRunOnTheMirror() {
        assertEquals(CoopAbilityEffectApplier.Decision.ACTIVATE_ON_MIRROR,
                CoopAbilityEffectApplier.decide("interdiction_pulse", true));
        assertEquals(CoopAbilityEffectApplier.Decision.ACTIVATE_ON_MIRROR,
                CoopAbilityEffectApplier.decide("distress_call", true));
    }

    @Test
    void noMirrorMeansNothingToActivateOn() {
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_NO_MIRROR,
                CoopAbilityEffectApplier.decide("interdiction_pulse", false));
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_NO_MIRROR,
                CoopAbilityEffectApplier.decide("distress_call", false));
    }

    /** Activating it would fight CoopSensorSync's per-frame pin, so it is a no-op either way. */
    @Test
    void sensorBurstIsAlreadyReplicatedWithOrWithoutAMirror() {
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_ALREADY_REPLICATED,
                CoopAbilityEffectApplier.decide("sensor_burst", true));
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_ALREADY_REPLICATED,
                CoopAbilityEffectApplier.decide("sensor_burst", false));
    }

    @Test
    void abilitiesWithNoHostSideEffectAreReportedAsUnhandled() {
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_UNHANDLED,
                CoopAbilityEffectApplier.decide("remote_survey", true));
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_UNHANDLED,
                CoopAbilityEffectApplier.decide("generate_slipsurge", true));
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_UNHANDLED,
                CoopAbilityEffectApplier.decide("some_mod_ability", true));
    }

    @Test
    void blankAndNullIdsAreUnhandledRatherThanThrowing() {
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_UNHANDLED,
                CoopAbilityEffectApplier.decide(null, true));
        assertEquals(CoopAbilityEffectApplier.Decision.SKIP_UNHANDLED,
                CoopAbilityEffectApplier.decide("   ", true));
    }

    @Test
    void idsAreTrimmed() {
        assertEquals(CoopAbilityEffectApplier.Decision.ACTIVATE_ON_MIRROR,
                CoopAbilityEffectApplier.decide("  interdiction_pulse  ", true));
    }

    // ---- Interdiction reputation hit -----------------------------------------------------------

    private static boolean hit(boolean sameFaction, boolean transition, boolean inRange,
                               boolean visible, float seconds, boolean knowsPlayer) {
        return CoopAbilityEffectApplier.shouldTakeInterdictionRepHit(
                sameFaction, transition, inRange, visible, seconds, knowsPlayer);
    }

    @Test
    void aVisibleHostileInRangeThatKnowsThePlayerCostsStanding() {
        assertTrue(hit(false, false, true, true, 3f, true));
    }

    @Test
    void vanillaSkipConditionsAreHonoured() {
        assertFalse(hit(true, false, true, true, 3f, true), "same faction");
        assertFalse(hit(false, true, true, true, 3f, true), "in hyperspace transition");
        assertFalse(hit(false, false, false, true, 3f, true), "out of range");
        assertFalse(hit(false, false, true, true, 3f, false), "does not know who the player is");
    }

    /** Vanilla's "Interdict avoided!" branch: a victim that sees the pulse and shrugs it off. */
    @Test
    void anAvoidedInterdictOnAVisibleVictimCostsNothing() {
        assertFalse(hit(false, false, true, true, 0f, true));
        assertFalse(hit(false, false, true, true, -1f, true));
    }

    /**
     * The avoidance skip is inside vanilla's visibility branch, so an unseen victim still costs
     * standing even at zero interdict seconds. Replicated verbatim rather than "fixed".
     */
    @Test
    void anAvoidedInterdictOnAnUnseenVictimStillCostsStanding() {
        assertTrue(hit(false, false, true, false, 0f, true));
    }
}
