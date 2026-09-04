package coop.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSessionStatsCodecTest {

    private static final String HOST = "host-id";
    private static final String GUEST = "guest-id";

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        CoopSessionStats original = fullyPopulated();

        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(
                CoopSessionStatsCodec.encodePayload(original));

        assertEquals(List.of(HOST, GUEST), decoded.playerIds());
        assertEquals("Ayo", decoded.playerName(HOST));
        assertEquals("Partner", decoded.playerName(GUEST));

        CoopSessionStats.PlayerStats host = decoded.player(HOST);
        assertEquals(7L, host.battlesFought());
        assertEquals(5L, host.battlesWon());
        assertEquals(2L, host.shipsLost());
        assertEquals(12_345.5f, host.distanceTraveledSu(), 0.01f);
        assertEquals(987_654L, host.netWorthCredits());
        assertEquals(250_000L, host.bestSingleTradeCredits());
        assertEquals(4L, host.missionsClaimed());
        assertEquals(2L, host.coloniesFounded());
        assertEquals(List.of("jangala_market", "kazeron_market"), host.marketsTradedWith());
        assertEquals(List.of("corvus", "askonia"), host.systemsVisited());

        CoopSessionStats.PlayerStats guest = decoded.player(GUEST);
        assertEquals(3L, guest.battlesFought());
        assertEquals(1L, guest.battlesWon());
        assertEquals(1L, guest.shipsLost());
        assertEquals(List.of("hybrasil"), guest.systemsVisited());

        assertEquals(9L, decoded.fleetsDestroyedTeam());
        assertEquals(11L, decoded.salvageEventsTeam());
        assertEquals(3L, decoded.coloniesHeldTeam());
        assertEquals(4321.5f, decoded.timeFlownTogetherSeconds(), 0.01f);
        assertEquals(64.25f, decoded.daysElapsed(), 0.01f);
        assertEquals(60f, decoded.lastHullLossDay(), 0.01f);
        assertEquals(4.25f, decoded.daysSinceLastHullLoss(), 0.01f);

        assertEquals(3, decoded.shipLossLedger().size());
        CoopSessionStats.ShipLoss newest = decoded.shipLossLedger().get(2);
        assertEquals(GUEST, newest.playerId());
        assertEquals("Ill-Advised | Notion", newest.hullName());
        assertEquals("cruiser", newest.hullClass());
        assertEquals("Hybrasil", newest.systemName());
        assertEquals(60f, newest.day(), 0.01f);
        assertEquals("destroyed by a\nRemnant ordnance pod", newest.cause());
    }

    @Test
    void emptyStatsRoundTripToEmptyStats() {
        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(
                CoopSessionStatsCodec.encodePayload(new CoopSessionStats()));

        assertTrue(decoded.isEmpty());
        assertTrue(decoded.playerIds().isEmpty());
        assertNull(decoded.daysSinceLastHullLoss());
    }

    @Test
    void aNullStatsObjectEncodesAsAnEmptyOne() {
        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(
                CoopSessionStatsCodec.encodePayload(null));

        assertTrue(decoded.isEmpty());
    }

    @Test
    void unknownJsonFieldsAreIgnored() {
        String payload = "{\"fleetsDestroyed\":2,\"futureField\":\"whatever\","
                + "\"anotherFutureCount\":77,\"body\":\"P|a|Ayo\"}";

        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(payload);

        assertEquals(2L, decoded.fleetsDestroyedTeam());
        assertEquals(List.of("a"), decoded.playerIds());
        assertEquals("Ayo", decoded.playerName("a"));
    }

    @Test
    void unknownBodyTokensAndShortRecordsAreSkipped() {
        String payload = "{\"body\":\"P|a|Ayo\\nX|a|from-the-future\\nP|b\\nV|a|corvus\"}";

        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(payload);

        assertEquals(List.of("a"), decoded.playerIds());
        assertEquals(List.of("corvus"), decoded.player("a").systemsVisited());
    }

    @Test
    void extraTrailingFieldsOnAKnownRecordAreTolerated() {
        String payload = "{\"body\":\"S|a|1|1|0|0.0|0|0|0|0|extra|fields\"}";

        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(payload);

        assertEquals(1L, decoded.player("a").battlesFought());
    }

    @Test
    void missingFieldsDecodeToZeroRatherThanThrowing() {
        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload("{}");

        assertTrue(decoded.isEmpty());
        assertEquals(0L, decoded.fleetsDestroyedTeam());
        assertEquals(0f, decoded.daysElapsed(), 0.001f);
    }

    @Test
    void unparseableNumbersFallBackToZero() {
        String payload = "{\"togetherSeconds\":\"not-a-number\",\"daysElapsed\":\"\","
                + "\"body\":\"S|a|nope|1|0|also-nope|0|0|0|0\"}";

        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(payload);

        assertEquals(0f, decoded.timeFlownTogetherSeconds(), 0.001f);
        assertEquals(0f, decoded.daysElapsed(), 0.001f);
        assertEquals(0L, decoded.player("a").battlesFought());
        assertEquals(1L, decoded.player("a").battlesWon());
        assertEquals(0f, decoded.player("a").distanceTraveledSu(), 0.001f);
    }

    @Test
    void aLedgerLongerThanTheCapIsTruncatedOnDecodeToo() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < CoopSessionStats.LEDGER_LIMIT + 4; i++) {
            if (i > 0) {
                body.append('\n');
            }
            body.append("L|a|hull-").append(i).append("|frigate|corvus|").append(i).append("|lost");
        }
        String payload = "{\"body\":\"" + CoopSessionStatsCodec.escapeJson(body.toString()) + "\"}";

        CoopSessionStats decoded = CoopSessionStatsCodec.decodePayload(payload);

        assertEquals(CoopSessionStats.LEDGER_LIMIT, decoded.shipLossLedger().size());
        assertEquals("hull-4", decoded.shipLossLedger().get(0).hullName());
    }

    @Test
    void malformedJsonIsRejectedLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopSessionStatsCodec.decodePayload("not json at all"));
        assertThrows(NullPointerException.class, () -> CoopSessionStatsCodec.decodePayload(null));
    }

    @Test
    void thePayloadIsAFlatJsonObjectTheEnvelopeParserAccepts() {
        String payload = CoopSessionStatsCodec.encodePayload(fullyPopulated());

        // If this ever fails, the payload has grown a bare float or some other shape the envelope's
        // flat value model does not carry, which would only be discovered on the wire. (CoopJson
        // does understand nested objects and arrays -- the manifest needs them -- but the flat
        // token-line body below is what keeps this payload one level deep.)
        assertTrue(CoopSessionStatsCodec.parseFlatObject(payload).containsKey("body"));
    }

    private static CoopSessionStats fullyPopulated() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.notePlayer(GUEST, "Partner");

        for (int i = 0; i < 7; i++) {
            stats.noteBattle(HOST, i < 5);
        }
        for (int i = 0; i < 3; i++) {
            stats.noteBattle(GUEST, i < 1);
        }
        stats.noteDistance(HOST, 12_345.5f);
        stats.noteDistance(GUEST, 2_000f);
        stats.noteNetWorth(HOST, 987_654L);
        stats.noteNetWorth(GUEST, 123_456L);
        stats.noteTrade(HOST, "jangala_market", 250_000L);
        stats.noteTrade(HOST, "kazeron_market", 1_000L);
        stats.noteTrade(GUEST, "jangala_market", 9_000L);
        for (int i = 0; i < 4; i++) {
            stats.noteMissionClaimed(HOST);
        }
        stats.noteMissionClaimed(GUEST);
        stats.noteSystemVisited(HOST, "corvus");
        stats.noteSystemVisited(HOST, "askonia");
        stats.noteSystemVisited(GUEST, "hybrasil");
        stats.noteColonyFounded(HOST);
        stats.noteColonyFounded(HOST);
        stats.noteColonyFounded(GUEST);
        stats.noteColoniesHeld(3);
        stats.noteFleetsDestroyed(9);
        for (int i = 0; i < 11; i++) {
            stats.noteSalvage();
        }
        stats.noteTogether(4_321.5f);
        stats.noteDaysElapsed(64.25f);
        // Deliberately awkward text: a pipe, a newline and a backslash all have to survive both the
        // delimited body encoding and the JSON string escape.
        stats.noteShipLost(HOST, "Wolf", "frigate", "Corvus", 12f, "destroyed");
        stats.noteShipLost(HOST, "Lasher\\Mk II", "frigate", "Askonia", 30f, "scuttled");
        stats.noteShipLost(GUEST, "Ill-Advised | Notion", "cruiser", "Hybrasil", 60f,
                "destroyed by a\nRemnant ordnance pod");
        return stats;
    }
}
