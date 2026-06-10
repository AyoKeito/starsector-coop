package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopAbilityArbiterTest {

    @Test
    void purelyLocalAbilitiesAreNotArbitrated() {
        assertTrue(CoopAbilityArbiter.isLocal("emergency_burn"));
        assertTrue(CoopAbilityArbiter.isLocal("sustained_burn"));
        assertTrue(CoopAbilityArbiter.isLocal("transponder"));
        assertTrue(CoopAbilityArbiter.isLocal("go_dark"));
        assertFalse(CoopAbilityArbiter.isWorldAffecting("emergency_burn"));
    }

    @Test
    void worldAffectingAbilitiesAreArbitrated() {
        assertTrue(CoopAbilityArbiter.isWorldAffecting("interdiction_pulse"));
        assertTrue(CoopAbilityArbiter.isWorldAffecting("distress_call"));
        assertTrue(CoopAbilityArbiter.isWorldAffecting("sensor_burst"));
    }

    @Test
    void unknownAbilityDefaultsToWorldAffecting() {
        // Safe default: arbitrate an unknown ability rather than risk a silent desync.
        assertTrue(CoopAbilityArbiter.isWorldAffecting("some_mod_ability"));
    }

    @Test
    void abilityActivateMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.abilityActivate(
                "s1", 2L, 50L, "interdiction_pulse", "guest", "{\"x\":10,\"y\":20}");
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.ABILITY_ACTIVATE, decoded.type());
        assertEquals("interdiction_pulse", CoopMessages.requiredPayloadString(decoded, "abilityId"));
        assertEquals("guest", CoopMessages.requiredPayloadString(decoded, "playerId"));
        assertEquals("{\"x\":10,\"y\":20}", CoopMessages.requiredPayloadString(decoded, "targetJson"));
    }
}
