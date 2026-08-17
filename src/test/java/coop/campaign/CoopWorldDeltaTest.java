package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- Phase 12b: idempotency for non-consuming kinds ---------------------------------------

    @Test
    void repeatedConstructIsAppliedOnlyOnce() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        CoopWorldDelta construct = new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.CONSTRUCT, false, "{}", "host");

        assertTrue(ledger.apply(construct));
        // The host's echo rebroadcast comes back to the originator; pre-12b this looked like a
        // first apply because non-consuming deltas were never recorded.
        assertFalse(ledger.apply(construct));
        assertFalse(ledger.apply(new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.CONSTRUCT, false, "{}", "guest")));
    }

    @Test
    void constructAndConsumeOnTheSameEntityDoNotBlockEachOther() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        // Kind-prefixed keys: a CONSTRUCT on X must not consume X, nor stop X being consumed later.
        assertTrue(ledger.apply(new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.CONSTRUCT, false, "{}", "host")));
        assertFalse(ledger.isConsumed("relay-1"));
        assertTrue(ledger.apply(new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.SALVAGE, true, "", "guest")));
        assertTrue(ledger.isConsumed("relay-1"));

        // Once consumed, further non-consuming deltas on the same entity are dead.
        assertFalse(ledger.apply(new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.PARLEY, false, "{}", "host")));
    }

    @Test
    void distinctNonConsumingKindsOnOneEntityEachApplyOnce() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(new CoopWorldDelta("site-1", CoopWorldDelta.Kind.CONSTRUCT, false, "{}", "host")));
        assertTrue(ledger.apply(new CoopWorldDelta("site-1", CoopWorldDelta.Kind.PARLEY, false, "{}", "host")));
        assertFalse(ledger.apply(new CoopWorldDelta("site-1", CoopWorldDelta.Kind.PARLEY, false, "{}", "host")));
        assertEquals(0, ledger.size());
    }

    @Test
    void clearResetsNonConsumingTrackingToo() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        CoopWorldDelta construct = new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.CONSTRUCT, false, "{}", "host");

        assertTrue(ledger.apply(construct));
        ledger.clear();
        assertTrue(ledger.apply(construct), "clear() must reset both tracking sets");
    }

    // ---- Phase 13: skeleton mutations -----------------------------------------------------------

    @Test
    void decivIsALatchAppliedExactlyOnce() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        CoopWorldDelta deciv = new CoopWorldDelta("market_yama", CoopWorldDelta.Kind.DECIV, false,
                "false", "host");

        assertTrue(ledger.apply(deciv));
        assertFalse(ledger.apply(deciv), "the host's echo rebroadcast must not re-decivilize");
        assertFalse(ledger.apply(new CoopWorldDelta("market_yama", CoopWorldDelta.Kind.DECIV, false,
                "false", "guest")));
        assertEquals(0, ledger.size());
    }

    @Test
    void objectiveOwnershipFlippingBackAndForthAppliesEveryLeg() {
        // The bug this guards: a set-based (kind, entity) key records OBJECTIVE_OWNERSHIP:relay-1 on
        // the first flip and swallows every flip after it, freezing the guest on a stale owner for
        // the rest of the campaign. Latest-wins compares the payload instead.
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(ownership("relay-1", "pirates")));
        assertTrue(ledger.apply(ownership("relay-1", "hegemony")));
        assertTrue(ledger.apply(ownership("relay-1", "pirates")));
        assertEquals("pirates",
                ledger.latestState(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, "relay-1"));
    }

    @Test
    void objectiveOwnershipEchoOfTheCurrentOwnerIsInert() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(ownership("relay-1", "pirates")));
        // The host rebroadcasts the delta verbatim; it must come back as a no-op, which is what
        // keeps a latest-wins kind from oscillating between the two clients.
        assertFalse(ledger.apply(ownership("relay-1", "pirates")));
        assertFalse(ledger.apply(new CoopWorldDelta("relay-1",
                CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, false, "pirates", "guest")));
    }

    @Test
    void objectiveOwnershipIsTrackedPerEntity() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(ownership("relay-1", "pirates")));
        assertTrue(ledger.apply(ownership("buoy-1", "pirates")));
        assertFalse(ledger.apply(ownership("relay-1", "pirates")));
        assertEquals("pirates", ledger.latestState(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, "buoy-1"));
        assertNull(ledger.latestState(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, "buoy-2"));
    }

    @Test
    void gateActivationAppliesEachDistinctStateOnce() {
        // Activation arrives in two steps: the gate is scanned, then gates become usable.
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        CoopWorldDelta scanned = gate("gate-1", true, false, false);
        CoopWorldDelta usable = gate("gate-1", true, true, true);

        assertTrue(ledger.apply(scanned));
        assertFalse(ledger.apply(scanned));
        assertTrue(ledger.apply(usable));
        assertFalse(ledger.apply(usable));
    }

    @Test
    void latestWinsKindsAreBlockedOnceTheEntityIsConsumed() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(new CoopWorldDelta("relay-1", CoopWorldDelta.Kind.SALVAGE, true, "", "guest")));
        assertFalse(ledger.apply(ownership("relay-1", "pirates")),
                "a destroyed objective must not be resurrected by a stale ownership flip");
    }

    @Test
    void clearResetsLatestWinsTrackingToo() {
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();
        assertTrue(ledger.apply(ownership("relay-1", "pirates")));
        ledger.clear();

        assertNull(ledger.latestState(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, "relay-1"));
        assertTrue(ledger.apply(ownership("relay-1", "pirates")));
    }

    @Test
    void onlyTheValueBearingKindsAreLatestWins() {
        assertTrue(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP.latestWins());
        assertTrue(CoopWorldDelta.Kind.GATE_ACTIVATED.latestWins());
        assertFalse(CoopWorldDelta.Kind.DECIV.latestWins());
        assertFalse(CoopWorldDelta.Kind.CONSTRUCT.latestWins());
        assertFalse(CoopWorldDelta.Kind.PARLEY.latestWins());
        assertFalse(CoopWorldDelta.Kind.SPAWN.latestWins());
    }

    private static CoopWorldDelta ownership(String entityId, String factionId) {
        return new CoopWorldDelta(entityId, CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, false,
                factionId, "host");
    }

    private static CoopWorldDelta gate(String entityId, boolean scanned, boolean gatesActive,
                                       boolean canUseGates) {
        return new CoopWorldDelta(entityId, CoopWorldDelta.Kind.GATE_ACTIVATED, false,
                CoopSkeletonMutationWatcher.encodeGateState(scanned, gatesActive, canUseGates), "host");
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
