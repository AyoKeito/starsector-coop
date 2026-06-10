package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWorldDeltaTest {

    @Test
    void firstConsumeAppliesAndIsIdempotentAfterwards() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        CoopWorldDelta salvage = new CoopWorldDelta("derelict-1", CoopWorldDelta.Kind.SALVAGE, true, "", "guest");

        // First apply: the caller should mutate world state.
        assertTrue(ledger.apply(salvage));
        assertTrue(ledger.isConsumed("derelict-1"));

        // Host rebroadcast / duplicate packet / both-clients apply: no double-loot.
        assertFalse(ledger.apply(salvage));
        assertFalse(ledger.apply(new CoopWorldDelta("derelict-1", CoopWorldDelta.Kind.SALVAGE, true, "", "host")));
        assertEquals(1, ledger.size());
    }

    @Test
    void distinctEntitiesEachConsumeOnce() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        assertTrue(ledger.apply(new CoopWorldDelta("ruin-1", CoopWorldDelta.Kind.EXPLORE, true, "", "host")));
        assertTrue(ledger.apply(new CoopWorldDelta("ruin-2", CoopWorldDelta.Kind.EXPLORE, true, "", "guest")));
        assertEquals(2, ledger.size());
    }

    @Test
    void nonConsumingDeltaIsNotTrackedAsConsumed() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        CoopWorldDelta construct = new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.CONSTRUCT, false, "{}", "host");

        assertTrue(ledger.apply(construct));
        assertFalse(ledger.isConsumed("relay-1"));
        assertEquals(0, ledger.size());
    }

    @Test
    void worldDeltaMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.worldDelta("s1", 5L, 100L,
                "derelict-1", "SALVAGE", true, "{\"looted\":true}", "guest");
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.WORLD_DELTA, decoded.type());
        assertEquals("derelict-1", CoopMessages.requiredPayloadString(decoded, "entityId"));
        assertEquals("SALVAGE", CoopMessages.requiredPayloadString(decoded, "kind"));
        assertTrue(Boolean.parseBoolean(CoopMessages.requiredPayloadString(decoded, "consumed")));
        assertEquals("{\"looted\":true}", CoopMessages.requiredPayloadString(decoded, "newStateJson"));
        assertEquals("guest", CoopMessages.requiredPayloadString(decoded, "actingPlayerId"));
    }
}
