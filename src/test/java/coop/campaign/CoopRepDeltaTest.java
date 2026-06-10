package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopRepDeltaTest {

    @Test
    void guestBaselineIsZeroBeforeAnyHostDelta() {
        Map<String, Float> table = new HashMap<>();
        assertEquals(0f, CoopRepDelta.relationship(table, CoopRepDelta.TargetType.FACTION, "hegemony"));
        assertEquals(CoopRepDelta.BASELINE,
                CoopRepDelta.relationship(table, CoopRepDelta.TargetType.PERSON, "person-1"));
    }

    @Test
    void applySetsTableToHostResultingValueNotIncrement() {
        Map<String, Float> table = new HashMap<>();
        // Even with a pre-existing local value, the host's resulting value wins (final-value convergence).
        table.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, "hegemony"), 0.99f);

        CoopRepDelta delta = new CoopRepDelta(CoopRepDelta.TargetType.FACTION, "hegemony", -0.1f, -0.42f);
        float applied = delta.applyTo(table);

        assertEquals(-0.42f, applied);
        assertEquals(-0.42f, CoopRepDelta.relationship(table, CoopRepDelta.TargetType.FACTION, "hegemony"));
    }

    @Test
    void repeatedDeltasConvergeToHostValue() {
        Map<String, Float> guest = new HashMap<>();
        new CoopRepDelta(CoopRepDelta.TargetType.FACTION, "tritachyon", 0.2f, 0.2f).applyTo(guest);
        new CoopRepDelta(CoopRepDelta.TargetType.FACTION, "tritachyon", -0.5f, -0.3f).applyTo(guest);
        new CoopRepDelta(CoopRepDelta.TargetType.FACTION, "tritachyon", 0.1f, -0.2f).applyTo(guest);

        assertEquals(-0.2f, CoopRepDelta.relationship(guest, CoopRepDelta.TargetType.FACTION, "tritachyon"));
    }

    @Test
    void replayGuardSuppressesNestedRebroadcast() {
        CoopCampaignReplicator.ReplayGuard guard = new CoopCampaignReplicator.ReplayGuard();
        assertFalse(guard.isReplaying());

        guard.begin();
        assertTrue(guard.isReplaying());
        // Nested begin/end balances correctly (re-entrant apply).
        guard.begin();
        guard.end();
        assertTrue(guard.isReplaying());
        guard.end();
        assertFalse(guard.isReplaying());

        // Extra end() never underflows below "not replaying".
        guard.end();
        assertFalse(guard.isReplaying());
    }

    @Test
    void repDeltaMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.repDelta(
                "s1", 7L, 100L, "FACTION", "hegemony", -0.1f, -0.42f);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.REP_DELTA, decoded.type());
        assertEquals("FACTION", CoopMessages.requiredPayloadString(decoded, "targetType"));
        assertEquals("hegemony", CoopMessages.requiredPayloadString(decoded, "targetId"));
        assertEquals(-0.42f, CoopMessages.requiredPayloadFloat(decoded, "resultingValue"));
        assertEquals(-0.1f, CoopMessages.requiredPayloadFloat(decoded, "delta"));
    }

    @Test
    void guestRepDeltaMessageRoundTrips() {
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(
                CoopMessages.guestRepDelta("s1", 3L, 50L, "FACTION", "hegemony", -0.08f)));

        assertEquals(CoopMessages.Type.GUEST_REP_DELTA, decoded.type());
        assertEquals("FACTION", CoopMessages.requiredPayloadString(decoded, "targetType"));
        assertEquals("hegemony", CoopMessages.requiredPayloadString(decoded, "targetId"));
        assertEquals(-0.08f, CoopMessages.requiredPayloadFloat(decoded, "delta"));
    }

    @Test
    void factionStandingsRoundTripThroughSnapshotMessage() {
        Map<String, Float> standings = new LinkedHashMap<>();
        standings.put("hegemony", -0.42f);
        standings.put("tritachyon", 0.3f);
        standings.put("pirates", -1.0f);

        String encoded = CoopRepDelta.encodeFactionStandings(standings);
        CoopMessages.Message decoded = CoopMessages.decode(
                CoopMessages.encode(CoopMessages.playerRepSnapshot("s1", 9L, 200L, encoded)));

        assertEquals(CoopMessages.Type.PLAYER_REP_SNAPSHOT, decoded.type());
        Map<String, Float> back = CoopRepDelta.decodeFactionStandings(
                CoopMessages.requiredPayloadString(decoded, "reps"));
        assertEquals(standings, back);
    }

    @Test
    void emptyStandingsRoundTrip() {
        assertTrue(CoopRepDelta.decodeFactionStandings(
                CoopRepDelta.encodeFactionStandings(new LinkedHashMap<>())).isEmpty());
    }
}
