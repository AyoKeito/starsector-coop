package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33's toggle as the rest of the mod sees it. The plugin class itself is not loaded here: it
 * extends {@code BaseToggleAbility}, whose static initializer reaches for {@code Global}, and
 * {@link CoopAllyToggleAbility#ABILITY_ID} is a compile-time constant precisely so the readers in
 * {@code coop.fleet} and {@code CoopAbilityArbiter} can name it without that happening.
 */
class CoopAllyToggleAbilityTest {

    @Test
    void theIdMatchesTheAbilitiesCsvRow() {
        assertEquals("coop_ally", CoopAllyToggleAbility.ABILITY_ID);
    }

    @Test
    void theToggleIsNeverMirroredAsAnAbilityActivation() {
        // Each player's toggle governs their own fleet. Arbitrating it would send an
        // ABILITY_ACTIVATE that makes the partner's engine turn ITS copy on, i.e. one player's yes
        // would enrol the other player's fleet. The bit rides the fleet snapshot instead.
        assertTrue(CoopAbilityArbiter.isLocal(CoopAllyToggleAbility.ABILITY_ID));
        assertFalse(CoopAbilityArbiter.isWorldAffecting(CoopAllyToggleAbility.ABILITY_ID));
    }

    @Test
    void itIsNotInTheArbitratedSet() {
        assertFalse(CoopAbilityArbiter.WORLD_AFFECTING_ABILITIES
                .contains(CoopAllyToggleAbility.ABILITY_ID));
    }
}
