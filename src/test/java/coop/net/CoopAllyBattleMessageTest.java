package coop.net;

import coop.combat.CoopAllyBattleOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33 codec: the two ally-battle messages, their delimited lists, and what the parser does with
 * a payload a peer built wrong.
 *
 * <p>The golden payloads are here for the same reason the credit grant's is. This is the second
 * message that changes another player's property, the lists are positional, and a field reorder both
 * halves agree on would pass a round trip while deleting the wrong ship.
 */
class CoopAllyBattleMessageTest {

    private static CoopAllyBattleOutcome outcome() {
        return new CoopAllyBattleOutcome("owner-player",
                List.of("member-1", "member-2"),
                List.of(new CoopAllyBattleOutcome.Survivor("member-3", 0.42f, 0.7f)));
    }

    // ---- golden payloads --------------------------------------------------------------------------

    @Test
    void allyBattleJoinPayloadIsByteForByteStable() {
        CoopMessages.Message join = CoopMessages.allyBattleJoin("session-a", 3L, 4L,
                "owner-player", "pilot-player", "Hegemony Patrol");

        assertEquals("{\"ownerPlayerId\":\"owner-player\",\"pilotPlayerId\":\"pilot-player\","
                        + "\"enemySummary\":\"Hegemony Patrol\"}",
                join.payloadJson());
    }

    @Test
    void allyBattleResultPayloadIsByteForByteStable() {
        CoopMessages.Message result = CoopMessages.allyBattleResult("session-a", 3L, 4L,
                "session-a-pilot-player-9", "pilot-player", outcome());

        assertEquals("{\"ledgerId\":\"session-a-pilot-player-9\",\"ownerPlayerId\":\"owner-player\","
                        + "\"pilotPlayerId\":\"pilot-player\","
                        + "\"destroyed\":\"member-1\\nmember-2\","
                        + "\"survivors\":\"member-3|0.42|0.7\"}",
                result.payloadJson());
    }

    // ---- round trips ------------------------------------------------------------------------------

    @Test
    void allyBattleJoinRoundTripsThroughTheEnvelope() {
        CoopMessages.Message join = CoopMessages.allyBattleJoin("session-a", 3L, 4L,
                "owner-player", "pilot-player", "Tri-Tachyon Task Force");

        CoopMessages.AllyBattleJoin parsed = CoopMessages.parseAllyBattleJoin(
                CoopMessages.decode(CoopMessages.encode(join)));

        assertEquals("owner-player", parsed.ownerPlayerId());
        assertEquals("pilot-player", parsed.pilotPlayerId());
        assertEquals("Tri-Tachyon Task Force", parsed.enemySummary());
    }

    @Test
    void allyBattleResultRoundTripsThroughTheEnvelope() {
        CoopMessages.Message message = CoopMessages.allyBattleResult("session-a", 3L, 4L,
                "session-a-pilot-player-9", "pilot-player", outcome());

        CoopMessages.AllyBattleResult parsed = CoopMessages.parseAllyBattleResult(
                CoopMessages.decode(CoopMessages.encode(message)));

        assertEquals("session-a-pilot-player-9", parsed.ledgerId());
        assertEquals("pilot-player", parsed.pilotPlayerId());
        assertEquals("owner-player", parsed.outcome().ownerPlayerId());
        assertEquals(List.of("member-1", "member-2"), parsed.outcome().destroyedMemberIds());
        assertEquals(1, parsed.outcome().survivors().size());
        assertEquals("member-3", parsed.outcome().survivors().get(0).memberId());
        assertEquals(0.42f, parsed.outcome().survivors().get(0).hullFraction(), 0.0001f);
        assertEquals(0.7f, parsed.outcome().survivors().get(0).cr(), 0.0001f);
    }

    /** An empty result is a real message: it is how the owner learns the fight ended. */
    @Test
    void anEmptyOutcomeRoundTripsAsEmpty() {
        CoopMessages.Message message = CoopMessages.allyBattleResult("session-a", 3L, 4L,
                "ledger-1", "pilot-player",
                new CoopAllyBattleOutcome("owner-player", List.of(), List.of()));

        CoopMessages.AllyBattleResult parsed = CoopMessages.parseAllyBattleResult(
                CoopMessages.decode(CoopMessages.encode(message)));

        assertTrue(parsed.outcome().isEmpty());
        assertEquals("owner-player", parsed.outcome().ownerPlayerId());
    }

    /** Ship ids and fleet names are engine strings; the delimiters must not be able to split one. */
    @Test
    void delimitersInsideAnIdOrASummaryRoundTripIntact() {
        CoopMessages.Message join = CoopMessages.allyBattleJoin("session-a", 1L, 2L,
                "owner-player", "pilot-player", "a|b\nc");
        assertEquals("a|b\nc",
                CoopMessages.parseAllyBattleJoin(CoopMessages.decode(CoopMessages.encode(join)))
                        .enemySummary());

        CoopMessages.Message result = CoopMessages.allyBattleResult("session-a", 1L, 2L,
                "ledger-1", "pilot-player",
                new CoopAllyBattleOutcome("owner-player", List.of("odd|id\nhere"),
                        List.of(new CoopAllyBattleOutcome.Survivor("back\\slash", 1f, 1f))));

        CoopMessages.AllyBattleResult parsed = CoopMessages.parseAllyBattleResult(
                CoopMessages.decode(CoopMessages.encode(result)));
        assertEquals(List.of("odd|id\nhere"), parsed.outcome().destroyedMemberIds());
        assertEquals("back\\slash", parsed.outcome().survivors().get(0).memberId());
    }

    // ---- malformed input --------------------------------------------------------------------------

    /**
     * A payload with no owner id, no pilot id or no ledger is thrown out rather than guessed at: the
     * caller logs it and applies nothing. Writing an unattributed loss onto somebody's fleet is the
     * worse failure.
     */
    @Test
    void aResultMissingAnIdentifyingFieldThrows() {
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseAllyBattleResult(
                new CoopMessages.Message(CoopMessages.Type.ALLY_BATTLE_RESULT, "session-a", 1L, 2L,
                        "{\"ownerPlayerId\":\"owner-player\",\"pilotPlayerId\":\"pilot-player\"}")),
                "no ledgerId: the receiver cannot dedup it");
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseAllyBattleResult(
                new CoopMessages.Message(CoopMessages.Type.ALLY_BATTLE_RESULT, "session-a", 1L, 2L,
                        "{\"ledgerId\":\"l-1\",\"pilotPlayerId\":\"pilot-player\"}")),
                "no ownerPlayerId: nobody to apply it to");
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseAllyBattleJoin(
                new CoopMessages.Message(CoopMessages.Type.ALLY_BATTLE_JOIN, "session-a", 1L, 2L,
                        "{\"ownerPlayerId\":\"owner-player\"}")),
                "no pilotPlayerId: no name for the line");
    }

    /** Both lists are optional on the wire, so an older or terser peer still parses. */
    @Test
    void absentListsParseAsEmptyRatherThanThrowing() {
        CoopMessages.AllyBattleResult parsed = CoopMessages.parseAllyBattleResult(
                new CoopMessages.Message(CoopMessages.Type.ALLY_BATTLE_RESULT, "session-a", 1L, 2L,
                        "{\"ledgerId\":\"l-1\",\"ownerPlayerId\":\"owner-player\","
                                + "\"pilotPlayerId\":\"pilot-player\"}"));

        assertTrue(parsed.outcome().isEmpty());
    }

    /** One unreadable survivor row costs that row, not the ships that did decode. */
    @Test
    void anUnreadableSurvivorRowIsSkippedAndTheRestApplies() {
        List<CoopAllyBattleOutcome.Survivor> survivors = CoopMessages.decodeAllySurvivors(
                "member-1|0.5|0.6\nmember-2|not-a-number|0.6\n|0.1|0.1\nshort-row\nmember-4|0.9|0.9");

        assertEquals(2, survivors.size());
        assertEquals("member-1", survivors.get(0).memberId());
        assertEquals("member-4", survivors.get(1).memberId());
    }

    @Test
    void aBlankDestroyedRowIsSkipped() {
        assertEquals(List.of("member-1", "member-2"),
                CoopMessages.decodeAllyDestroyed("member-1\n\nmember-2"));
    }

    /** The cap is what stops a peer handing the applier an arbitrarily long walk. */
    @Test
    void bothListsStopAtTheMemberCap() {
        StringBuilder destroyed = new StringBuilder();
        StringBuilder survivors = new StringBuilder();
        for (int i = 0; i < CoopMessages.MAX_ALLY_BATTLE_MEMBERS + 10; i++) {
            if (i > 0) {
                destroyed.append('\n');
                survivors.append('\n');
            }
            destroyed.append("member-").append(i);
            survivors.append("member-").append(i).append("|1.0|1.0");
        }

        assertEquals(CoopMessages.MAX_ALLY_BATTLE_MEMBERS,
                CoopMessages.decodeAllyDestroyed(destroyed.toString()).size());
        assertEquals(CoopMessages.MAX_ALLY_BATTLE_MEMBERS,
                CoopMessages.decodeAllySurvivors(survivors.toString()).size());
    }

    // ---- policy ------------------------------------------------------------------------------------

    /**
     * The result is the half that carries facts, so it is reliable; the join is an announcement of a
     * battle whose result says the same thing a minute later, so it is not. Pinned here as well as in
     * {@link CoopMessageTypePolicyTest} because this is where the pair's reasoning lives.
     */
    @Test
    void onlyTheResultHalfIsReliable() {
        assertTrue(CoopMessages.isReliableOneShot(CoopMessages.Type.ALLY_BATTLE_RESULT));
        assertFalse(CoopMessages.isReliableOneShot(CoopMessages.Type.ALLY_BATTLE_JOIN));
    }
}
