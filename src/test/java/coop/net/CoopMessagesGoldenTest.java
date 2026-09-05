package coop.net;

import coop.session.CoopPlayerInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-for-byte lock on the wire. These constants were captured from the hand-rolled encoder as it
 * stood before the {@code CoopJson} extraction, and they are the reason that extraction is safe to
 * do: any change to the escaper, to field order, or to how a number or a nullable is rendered shows
 * up here as a string mismatch rather than as a peer that silently stops understanding us.
 *
 * <p>The malformed-input half does the same job for the parser. It does not assert that the grammar
 * is <em>good</em> — it asserts that it is the grammar we already shipped.
 */
class CoopMessagesGoldenTest {

    private static CoopDatagramStats stats() {
        return new CoopDatagramStats(0L, 5L, 6L, 0L, 0L, 0L, 7L, 0L, 0L, 8L, 0L, 9L, 0L, 3L, 4L,
                0L, 0L, "");
    }

    // ---- golden payloads --------------------------------------------------------------------------

    @Test
    void linkStatusPayloadIsByteForByteStable() {
        CoopMessages.Message message = CoopMessages.linkStatus("session-a", 4L, 1234L,
                new CoopLinkQuality.Snapshot(87, 210, 4, false, 1500L, 12_000L),
                CoopLinkQuality.TRANSPORT_TCP_FALLBACK, 10, stats());

        assertEquals("{\"rttMillis\":87,\"p95RttMillis\":210,\"lossPercent\":4,"
                        + "\"udpInboundOk\":\"false\",\"transport\":\"TCP_FALLBACK\","
                        + "\"cadenceHz\":10,\"tcpSilenceMillis\":1500,"
                        + "\"droppedTokenMismatch\":5,\"droppedForeignSource\":6,"
                        + "\"pathValidations\":7,\"icmpTransients\":8,\"escalatedToTcp\":9,"
                        + "\"connectionsThrottled\":3,\"invalidFrames\":4}",
                message.payloadJson());

        CoopMessages.LinkStatus parsed =
                CoopMessages.parseLinkStatus(CoopMessages.decode(CoopMessages.encode(message)));
        assertEquals(87, parsed.rttMillis());
        assertEquals(210, parsed.p95RttMillis());
        assertEquals(4, parsed.lossPercent());
        assertEquals("TCP_FALLBACK", parsed.transport());
        assertEquals(1500L, parsed.tcpSilenceMillis());
        assertEquals(5L, parsed.droppedTokenMismatch());
        assertEquals(6L, parsed.droppedForeignSource());
        assertEquals(7L, parsed.pathValidations());
        assertEquals(8L, parsed.icmpTransients());
        assertEquals(9L, parsed.escalatedToTcp());
        assertEquals(3L, parsed.connectionsThrottled());
        assertEquals(4L, parsed.invalidFrames());
        assertEquals(10, parsed.cadenceHz());
    }

    @Test
    void linkStatusRendersAbsentSamplesAsMinusOne() {
        CoopMessages.Message message = CoopMessages.linkStatus("session-a", 4L, 1234L,
                new CoopLinkQuality.Snapshot(null, null, 0, true, 0L, 0L),
                CoopLinkQuality.TRANSPORT_UDP, 5,
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        0L, 0L, ""));

        assertEquals("{\"rttMillis\":-1,\"p95RttMillis\":-1,\"lossPercent\":0,"
                        + "\"udpInboundOk\":\"true\",\"transport\":\"UDP\",\"cadenceHz\":5,"
                        + "\"tcpSilenceMillis\":0,\"droppedTokenMismatch\":0,"
                        + "\"droppedForeignSource\":0,\"pathValidations\":0,\"icmpTransients\":0,"
                        + "\"escalatedToTcp\":0,\"connectionsThrottled\":0,\"invalidFrames\":0}",
                message.payloadJson());
        assertEquals(-1, CoopMessages.parseLinkStatus(message).rttMillis());
    }

    @Test
    void stateDatagramPayloadIsByteForByteStable() {
        CoopMessages.Message message =
                CoopMessages.stateDatagram("session-a", 9L, 42L, "tok\u001fsender\u001fPING\u001f1");

        assertEquals("{\"datagram\":\"tok\\u001fsender\\u001fPING\\u001f1\"}",
                message.payloadJson());
        assertEquals("tok\u001fsender\u001fPING\u001f1", CoopMessages.parseStateDatagram(message));
    }

    @Test
    void lobbyHelloPayloadIsByteForByteStable() {
        CoopMessages.Message hello =
                CoopMessages.lobbyHello(1L, 2L, new CoopPlayerInfo("guest-player", "Guest"));

        assertEquals("{\"playerId\":\"guest-player\",\"playerName\":\"Guest\"}",
                hello.payloadJson());

        CoopMessages.Message withProof =
                CoopMessages.lobbyHello(1L, 2L, new CoopPlayerInfo("guest-player", "Guest"), "abc");
        assertEquals("{\"playerId\":\"guest-player\",\"playerName\":\"Guest\",\"proof\":\"abc\"}",
                withProof.payloadJson());
    }

    @Test
    void envelopeRendersNullSessionAndSenderAsBareNull() {
        CoopMessages.Message hello =
                CoopMessages.lobbyHello(1L, 2L, new CoopPlayerInfo("guest-player", "Guest"));

        assertEquals("{\"type\":\"LOBBY_HELLO\",\"sessionId\":null,\"seq\":1,\"sentAtMillis\":2,"
                        + "\"payloadJson\":\"{\\\"playerId\\\":\\\"guest-player\\\","
                        + "\\\"playerName\\\":\\\"Guest\\\"}\",\"senderId\":null}",
                CoopMessages.encode(hello));

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(hello));
        assertNull(decoded.sessionId());
        assertNull(decoded.senderId());
        assertEquals(hello.payloadJson(), decoded.payloadJson());
    }

    @Test
    void marketTxnEncodesItsFloatAsAQuotedString() {
        CoopMessages.Message txn = CoopMessages.marketTxn("session-a", 3L, 4L,
                "jangala_market", "open_market", "buy", "supplies", 7, 12.5f, "guest-player", "");

        assertEquals("{\"marketId\":\"jangala_market\",\"submarketId\":\"open_market\","
                        + "\"kind\":\"buy\",\"itemId\":\"supplies\","
                        + "\"qty\":7,\"unitPrice\":\"12.5\",\"actingPlayerId\":\"guest-player\","
                        + "\"detail\":\"\"}",
                txn.payloadJson());
        assertEquals(12.5f, CoopMessages.requiredPayloadFloat(txn, "unitPrice"), 0.0001f);
        assertEquals(7L, CoopMessages.requiredPayloadLong(txn, "qty"));
    }

    /**
     * The only message on the wire that moves money. A round trip cannot catch a field reorder that
     * both halves agree on; this can, and a grant that decodes into the wrong field pays the wrong
     * amount to the wrong ledger.
     */
    @Test
    void creditsGrantPayloadIsByteForByteStable() {
        CoopMessages.Message grant = CoopMessages.creditsGrant("session-a", 3L, 4L,
                "guest-player-17", 25_000, "gift");

        assertEquals("{\"ledgerId\":\"guest-player-17\",\"amount\":25000,\"reason\":\"gift\"}",
                grant.payloadJson());

        CoopMessages.CreditsGrant parsed =
                CoopMessages.parseCreditsGrant(CoopMessages.decode(CoopMessages.encode(grant)));
        assertEquals("guest-player-17", parsed.ledgerId());
        assertEquals(25_000, parsed.amount());
        assertEquals("gift", parsed.reason());
    }

    /** The amount is a bare number, not a quoted one -- unlike {@code marketTxn}'s float. */
    @Test
    void creditsGrantRendersItsAmountAsABareNumberAndEscapesTheLedgerId() {
        CoopMessages.Message grant = CoopMessages.creditsGrant("session-a", 1L, 1L,
                "host\"player\\1", 1, "bounty:sys\nbounty");

        assertEquals("{\"ledgerId\":\"host\\\"player\\\\1\",\"amount\":1,"
                        + "\"reason\":\"bounty:sys\\nbounty\"}",
                grant.payloadJson());
        assertEquals("host\"player\\1", CoopMessages.parseCreditsGrant(grant).ledgerId());
    }

    /** Phase 32: {@code submarketId} is required text and sits between the market and the player. */
    @Test
    void marketOpenPayloadIsByteForByteStable() {
        CoopMessages.Message open = CoopMessages.marketOpen("session-a", 3L, 4L,
                "jangala_market", CoopMessages.SUBMARKET_ALL, "guest-player");

        assertEquals("{\"marketId\":\"jangala_market\",\"submarketId\":\"*\","
                        + "\"playerId\":\"guest-player\"}",
                open.payloadJson());
        assertEquals("*", CoopMessages.SUBMARKET_ALL);
    }

    /**
     * Phase 32: {@code submarketCount} is a bare number and {@code stock} is the escaped blob. The
     * count is what the guest's sync gate counts down to before it opens the trade screens, so a
     * reorder that swapped it with anything else would open them onto the guest's own stock.
     */
    @Test
    void marketSnapshotPayloadIsByteForByteStable() {
        CoopMessages.Message snapshot = CoopMessages.marketSnapshot("session-a", 3L, 4L,
                "jangala_market", "storage", 3, "supplies|10\nfuel|5");

        assertEquals("{\"marketId\":\"jangala_market\",\"submarketId\":\"storage\","
                        + "\"submarketCount\":3,\"stock\":\"supplies|10\\nfuel|5\"}",
                snapshot.payloadJson());
        assertEquals(3L, CoopMessages.requiredPayloadLong(snapshot, "submarketCount"));
        assertEquals("supplies|10\nfuel|5",
                CoopMessages.payload(snapshot).requiredString("stock"));
    }

    /** Every branch of the escaper, in one payload, through a real builder. */
    @Test
    void escaperCoversEveryBranchOnTheWire() {
        String raw = "q\"b\\s\bf\fn\nr\rt\tc\u0001u\u00e9w\u661f ~";
        CoopMessages.Message message = CoopMessages.stateDatagram("session-a", 1L, 1L, raw);

        assertEquals("{\"datagram\":\"q\\\"b\\\\s\\bf\\fn\\nr\\rt\\tc\\u0001u\u00e9w\u661f ~\"}",
                message.payloadJson());
        assertEquals(raw, CoopMessages.parseStateDatagram(message));
    }

    // ---- malformed input: the grammar we already ship ---------------------------------------------

    private static CoopMessages.Message payload(String json) {
        return new CoopMessages.Message(CoopMessages.Type.PING, null, 0L, 0L, json);
    }

    private static void rejects(String json) {
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.decodePayload(payload(json)),
                "should reject: " + json);
    }

    @Test
    void truncatedObjectIsRejected() {
        rejects("{");
        rejects("{\"a\"");
        rejects("{\"a\":");
        rejects("{\"a\":1");
        rejects("{\"a\":1,");
    }

    @Test
    void trailingGarbageAfterTheObjectIsRejected() {
        rejects("{\"a\":1}x");
        rejects("{\"a\":1}{\"b\":2}");
        rejects("{}}");
    }

    @Test
    void badEscapeIsRejected() {
        rejects("{\"a\":\"\\q\"}");
        rejects("{\"a\":\"\\u12\"}");
        rejects("{\"a\":\"\\uzzzz\"}");
        rejects("{\"a\":\"x\\");
    }

    @Test
    void unterminatedStringIsRejected() {
        rejects("{\"a\":\"x}");
        rejects("{\"a");
    }

    @Test
    void duplicateKeysKeepTheLastValue() {
        Map<String, Object> fields = CoopMessages.decodePayload(payload("{\"a\":1,\"a\":2}"));
        assertEquals(1, fields.size());
        assertEquals(2L, fields.get("a"));
    }

    @Test
    void whitespaceIsToleratedEverywhereAValueCanStart() {
        Map<String, Object> fields =
                CoopMessages.decodePayload(payload("  {  \"a\" :  1 ,  \"b\" : \"x\"  }  "));
        assertEquals(1L, fields.get("a"));
        assertEquals("x", fields.get("b"));
    }

    @Test
    void emptyObjectDecodesToNoFields() {
        assertTrue(CoopMessages.decodePayload(payload("{}")).isEmpty());
        assertTrue(CoopMessages.decodePayload(payload("  {  }  ")).isEmpty());
    }

    @Test
    void nullAndNegativeAndZeroValuesSurvive() {
        Map<String, Object> fields =
                CoopMessages.decodePayload(payload("{\"a\":null,\"b\":-12,\"c\":0}"));
        assertTrue(fields.containsKey("a"));
        assertNull(fields.get("a"));
        assertEquals(-12L, fields.get("b"));
        assertEquals(0L, fields.get("c"));
    }

    @Test
    void nonObjectTopLevelPayloadIsRejected() {
        rejects("[]");
        rejects("\"x\"");
        rejects("1");
        rejects("null");
        rejects("");
    }

    /** Neither shipped parser accepts these, and the shared one keeps refusing them. */
    @Test
    void booleansAndFractionalNumbersAreNotPartOfTheGrammar() {
        rejects("{\"a\":true}");
        rejects("{\"a\":false}");
        rejects("{\"a\":1.5}");
        rejects("{\"a\":1e3}");
        rejects("{\"a\":-}");
    }
}
