package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopFactionRelationsTest {

    @Test
    void unknownPairReadsAsNeutralBaseline() {
        CoopFactionRelations rel = new CoopFactionRelations();
        assertFalse(rel.isKnown("hegemony", "tritachyon"));
        assertEquals(0f, rel.relationship("hegemony", "tritachyon"));
    }

    @Test
    void pairKeyIsOrderIndependent() {
        assertEquals(CoopFactionRelations.pairKey("hegemony", "tritachyon"),
                CoopFactionRelations.pairKey("tritachyon", "hegemony"));
    }

    @Test
    void applyResultIsSymmetricAndConverges() {
        CoopFactionRelations rel = new CoopFactionRelations();
        rel.applyResult("hegemony", "tritachyon", -0.5f);
        // Reading in the opposite order returns the same value (symmetric).
        assertEquals(-0.5f, rel.relationship("tritachyon", "hegemony"));

        // A later host delta wins (final-value convergence), regardless of argument order.
        rel.applyResult("tritachyon", "hegemony", -1.0f);
        assertEquals(-1.0f, rel.relationship("hegemony", "tritachyon"));
        assertEquals(1, rel.size());
        assertTrue(rel.isKnown("hegemony", "tritachyon"));
    }

    @Test
    void factionRelDeltaMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.factionRelDelta(
                "s1", 3L, 50L, "hegemony", "tritachyon", -0.75f);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.FACTION_REL_DELTA, decoded.type());
        assertEquals("hegemony", CoopMessages.requiredPayloadString(decoded, "factionA"));
        assertEquals("tritachyon", CoopMessages.requiredPayloadString(decoded, "factionB"));
        assertEquals(-0.75f, CoopMessages.requiredPayloadFloat(decoded, "resultingValue"));
    }
}
