package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The one decision the host makes for a guest's world-affecting ability, extracted as a pure static
 * so it can be exercised without an engine: which ability gets run on the guest mirror. Reputation is
 * deliberately not one of this class's concerns any more -- the guest's own vanilla pulse charges it.
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

    // ---- No reputation hit ---------------------------------------------------------------------

    /**
     * The guest's own vanilla pulse already charges {@code INTERDICTED} locally (its fleet <em>is</em>
     * the player fleet there) and {@code onPlayerReputationChange} forwards that to the host as a
     * {@code GUEST_REP_DELTA}. The host-side manual hit that used to live here made the canonical
     * standing move twice per victim, so it is gone -- and must stay gone.
     */
    @Test
    void theHostAppliesNoReputationHitOfItsOwnForAGuestPulse() {
        for (java.lang.reflect.Method method
                : CoopAbilityEffectApplier.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("rep"),
                    "reputation is charged by the guest's own vanilla pulse, not here: " + name);
        }
    }
}
