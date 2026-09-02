package coop.net;

import coop.session.CoopPlayerInfo;
import coop.handshake.CoopHandshakeManifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMessagesTest {
    @Test
    void pingMessageEncodesDeterministicEnvelopeJson() {
        CoopMessages.Message ping = CoopMessages.ping("session-a", 7L, 123456789L);

        assertEquals(
                "{\"type\":\"PING\",\"sessionId\":\"session-a\",\"seq\":7,\"sentAtMillis\":123456789,\"payloadJson\":\"{}\",\"senderId\":null}",
                CoopMessages.encode(ping));
    }

    @Test
    void pongMessageRoundTripsPayloadJson() {
        CoopMessages.Message pong = CoopMessages.pong("session-a", 8L, 123456799L, 7L);
        String json = CoopMessages.encode(pong);

        CoopMessages.Message decoded = CoopMessages.decode(json);

        assertEquals(CoopMessages.Type.PONG, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals(8L, decoded.seq());
        assertEquals(123456799L, decoded.sentAtMillis());
        assertEquals("{\"pingSeq\":7}", decoded.payloadJson());
    }

    @Test
    void nullableSessionIdRoundTripsForEarlyHello() {
        CoopMessages.Message hello = CoopMessages.hello(null, 1L, 2000L, CoopConnectionRole.GUEST);

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(hello));

        assertEquals(CoopMessages.Type.HELLO, decoded.type());
        assertNull(decoded.sessionId());
        assertEquals("{\"role\":\"GUEST\"}", decoded.payloadJson());
    }

    @Test
    void lobbyHelloEncodesDeterministicPlayerPayload() {
        CoopMessages.Message hello = CoopMessages.lobbyHello(3L, 4000L,
                new CoopPlayerInfo("guest-player", "Guest"));

        assertEquals(
                "{\"type\":\"LOBBY_HELLO\",\"sessionId\":null,\"seq\":3,\"sentAtMillis\":4000,"
                        + "\"payloadJson\":\"{\\\"playerId\\\":\\\"guest-player\\\",\\\"playerName\\\":\\\"Guest\\\"}\",\"senderId\":null}",
                CoopMessages.encode(hello));

        Map<String, Object> payload = CoopMessages.decodePayload(hello);
        assertEquals("guest-player", payload.get("playerId"));
        assertEquals("Guest", payload.get("playerName"));
    }

    @Test
    void lobbyAcceptRoundTripsProvisionalLobbyAndHostPlayerInfo() {
        CoopMessages.Message accept = CoopMessages.lobbyAccept(4L, 5000L, "lobby-a",
                new CoopPlayerInfo("host-player", "Host"));

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(accept));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.LOBBY_ACCEPT, decoded.type());
        assertNull(decoded.sessionId());
        assertEquals("lobby-a", payload.get("provisionalLobbyId"));
        assertEquals("host-player", payload.get("hostPlayerId"));
        assertEquals("Host", payload.get("hostName"));
    }

    @Test
    void lobbyRejectRoundTripsReason() {
        CoopMessages.Message reject = CoopMessages.lobbyReject(5L, 6000L, "Lobby already has a guest");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(reject));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.LOBBY_REJECT, decoded.type());
        assertNull(decoded.sessionId());
        assertEquals("Lobby already has a guest", payload.get("reason"));
    }

    @Test
    void handshakeManifestRoundTripsManifestAndIronMode() {
        CoopHandshakeManifest manifest = new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of());

        CoopMessages.Message message = CoopMessages.handshakeManifest(6L, 7000L, manifest, false);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.HANDSHAKE_MANIFEST, decoded.type());
        assertNull(decoded.sessionId());
        assertEquals(manifest, CoopHandshakeManifest.fromJson((String) payload.get("manifestJson")));
        assertEquals("false", payload.get("ironMode"));
    }

    @Test
    void handshakeAcceptResultCarriesCanonicalSessionId() {
        CoopMessages.Message result = CoopMessages.handshakeResultAccept(7L, 8000L, "session-a");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(result));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.HANDSHAKE_RESULT, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals("true", payload.get("accepted"));
        assertEquals("session-a", payload.get("sessionId"));
        assertEquals("", payload.get("diff"));
    }

    @Test
    void handshakeRejectResultCarriesReadableDiffWithoutCanonicalSessionId() {
        CoopMessages.Message result = CoopMessages.handshakeResultReject(8L, 9000L,
                "mod utility.version: host=1.0.0 guest=1.1.0");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(result));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.HANDSHAKE_RESULT, decoded.type());
        assertNull(decoded.sessionId());
        assertEquals("false", payload.get("accepted"));
        assertEquals("", payload.get("sessionId"));
        assertEquals("mod utility.version: host=1.0.0 guest=1.1.0", payload.get("diff"));
    }

    @Test
    void seedLockRequestCarriesSeedHostFingerprintAndCampaignId() {
        CoopMessages.Message request = CoopMessages.seedLockRequest(
                "session-a", 9L, 10000L, 123456789L, "coop-00000000075bcd15", "fingerprint-host",
                "campaign-uuid-1", true);

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(request));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.SEED_LOCK_REQUEST, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals(123456789L, payload.get("seedLong"));
        assertEquals("coop-00000000075bcd15", payload.get("seedString"));
        assertEquals("fingerprint-host", payload.get("sectorFingerprint"));
        assertEquals("campaign-uuid-1", payload.get("campaignId"));
        assertEquals("campaign-uuid-1", CoopMessages.requiredPayloadString(decoded, "campaignId"));
        assertEquals("true", CoopMessages.requiredPayloadString(decoded, "campaignIdMinted"));
        assertEquals(123456789L, CoopMessages.requiredPayloadLong(decoded, "seedLong"));
    }

    @Test
    void seedLockAckCarriesGuestFingerprint() {
        CoopMessages.Message ack = CoopMessages.seedLockAck("session-a", 10L, 11000L, "fingerprint-guest");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(ack));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.SEED_LOCK_ACK, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals("fingerprint-guest", payload.get("sectorFingerprint"));
    }

    @Test
    void seedLockRejectCarriesReadableReason() {
        CoopMessages.Message reject = CoopMessages.seedLockReject(
                "session-a", 11L, 12000L, "sectorFingerprint: host=a guest=b");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(reject));
        Map<String, Object> payload = CoopMessages.decodePayload(decoded);

        assertEquals(CoopMessages.Type.SEED_LOCK_REJECT, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals("sectorFingerprint: host=a guest=b", payload.get("reason"));
    }

    @Test
    void datagramEnvelopeRoundTripsHighFrequencyPayload() {
        String body = "player-a|Guest|corvus\nmember-1|wolf|wolf_Assault";
        String encoded = CoopMessages.datagram(
                "0123456789abcdef", "fedcba9876543210", CoopMessages.Type.FLEET_SNAPSHOT, 7L, 12345L, body);

        CoopMessages.Datagram decoded = CoopMessages.parseDatagram(encoded);

        assertEquals("0123456789abcdef", decoded.token());
        assertEquals("fedcba9876543210", decoded.senderId());
        assertEquals(CoopMessages.Type.FLEET_SNAPSHOT, decoded.type());
        assertEquals(1, decoded.sections().size());
        assertEquals(7L, decoded.sections().get(0).epoch());
        assertEquals(12345L, decoded.sections().get(0).sentGameTimeMillis());
        assertEquals(0, decoded.sections().get(0).chunk());
        assertEquals(body, decoded.sections().get(0).body());
    }

    @Test
    void datagramEnvelopeRoundTripsAnExplicitChunkIndex() {
        String encoded = CoopMessages.datagram("token", "sender",
                CoopMessages.Type.NPC_FLEET_MOTION, 9L, 900L, 3, "slice");

        CoopMessages.Datagram decoded = CoopMessages.parseDatagram(encoded);

        assertEquals(3, decoded.sections().get(0).chunk());
        assertEquals("slice", decoded.sections().get(0).body());
    }

    @Test
    void datagramEnvelopeRoundTripsRedundantSectionsOldestFirst() {
        // Bodies deliberately exercise the characters the section split must not trip on: the batch
        // codecs use newlines and pipes internally, never the envelope's unit separator.
        String older = "3\nfleet-a|corvus|1.0|2.0|0.5|0.5";
        String newer = "3\nfleet-a|corvus|1.5|2.5|0.5|0.5";
        String encoded = CoopMessages.datagram("token", "sender", CoopMessages.Type.NPC_FLEET_MOTION,
                java.util.List.of(new CoopMessages.DatagramSection(4L, 400L, older),
                        new CoopMessages.DatagramSection(5L, 500L, newer)));

        CoopMessages.Datagram decoded = CoopMessages.parseDatagram(encoded);

        assertEquals(2, decoded.sections().size());
        assertEquals(4L, decoded.sections().get(0).epoch());
        assertEquals(older, decoded.sections().get(0).body());
        assertEquals(5L, decoded.sections().get(1).epoch());
        assertEquals(500L, decoded.sections().get(1).sentGameTimeMillis());
        assertEquals(newer, decoded.sections().get(1).body());
    }

    @Test
    void malformedDatagramEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopMessages.parseDatagram("token|FLEET_SNAPSHOT|payload"));
        // A stamp that is not a number is malformed, not a zero.
        String badStamp = CoopMessages.datagram(
                        "token", "sender", CoopMessages.Type.FLEET_SNAPSHOT, 10L, 0L, "body")
                .replace("10", "x0");
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagram(badStamp));
    }

    @Test
    void datagramSectionWithANonIntegerChunkIsMalformed() {
        String encoded = CoopMessages.datagram("token", "sender",
                CoopMessages.Type.FLEET_SNAPSHOT, 1L, 2L, 7, "body");

        assertThrows(IllegalArgumentException.class,
                () -> CoopMessages.parseDatagram(encoded.replace("\u001f7\u001f", "\u001fx\u001f")));
    }

    // ---- Phase 20.1 header-only parse -----------------------------------------------------------

    @Test
    void datagramHeaderParseReadsThePrefixWithoutTheSections() {
        String encoded = CoopMessages.datagram("0123456789abcdef", "fedcba9876543210",
                CoopMessages.Type.NPC_FLEET_MOTION, 4L, 400L, "a-body-the-header-parse-never-touches");

        CoopMessages.DatagramHeader header = CoopMessages.parseDatagramHeader(encoded);

        assertEquals("0123456789abcdef", header.token());
        assertEquals("fedcba9876543210", header.senderId());
        assertEquals(CoopMessages.Type.NPC_FLEET_MOTION, header.type());
    }

    /**
     * The transport runs this on every inbound packet including hostile ones, so it must have exactly
     * one failure mode: {@link IllegalArgumentException}, never a runtime surprise the receive loop
     * does not catch.
     */
    @Test
    void datagramHeaderParseRejectsEveryMalformedShapeWithIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagramHeader(null));
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagramHeader(""));
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagramHeader("token"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopMessages.parseDatagramHeader("token\u001fsender"));
        // Header with no section at all is not something this transport emits.
        assertThrows(IllegalArgumentException.class,
                () -> CoopMessages.parseDatagramHeader("token\u001fsender\u001fPING"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopMessages.parseDatagramHeader("token\u001fsender\u001fNOT_A_TYPE\u001f1\u001f2\u001f0\u001fbody"));
    }

    // ---- red-team A5/C2: bounded stamps, and the chunk in the header -----------------------------

    @Test
    void a5_aDatagramWithAnOutOfRangeChunkIsRejectedAtParse() {
        String encoded = CoopMessages.datagram("token", "sender",
                CoopMessages.Type.FLEET_SNAPSHOT, 1L, 2L, 7, "body");

        // The chunk index keys receiver-side per-chunk state; an unbounded index is an unbounded map.
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagram(
                encoded.replace("\u001f7\u001f", "\u001f-1\u001f")));
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagram(
                encoded.replace("\u001f7\u001f", "\u001f" + CoopMessages.MAX_DATAGRAM_CHUNKS + "\u001f")));
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagram(
                encoded.replace("\u001f7\u001f", "\u001f2000000000\u001f")));

        // The last legal index still parses, so the bound is a bound and not an off-by-one.
        assertEquals(CoopMessages.MAX_DATAGRAM_CHUNKS - 1, CoopMessages.parseDatagram(
                        encoded.replace("\u001f7\u001f",
                                "\u001f" + (CoopMessages.MAX_DATAGRAM_CHUNKS - 1) + "\u001f"))
                .sections().get(0).chunk());
    }

    @Test
    void a5_aDatagramWithMoreSectionsThanTheCapIsRejectedAtParse() {
        List<CoopMessages.DatagramSection> tooMany = new ArrayList<>();
        for (int i = 0; i <= CoopMessages.MAX_DATAGRAM_SECTIONS; i++) {
            tooMany.add(new CoopMessages.DatagramSection(i + 1, i * 10L, 0, "body-" + i));
        }
        String encoded = CoopMessages.datagram("token", "sender",
                CoopMessages.Type.NPC_FLEET_MOTION, tooMany);

        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagram(encoded));

        // Redundancy composes two; the cap is only ever reached by a sender that chose to.
        assertEquals(CoopMessages.MAX_DATAGRAM_SECTIONS,
                CoopMessages.parseDatagram(CoopMessages.datagram("token", "sender",
                                CoopMessages.Type.NPC_FLEET_MOTION,
                                tooMany.subList(0, CoopMessages.MAX_DATAGRAM_SECTIONS)))
                        .sections().size());
    }

    @Test
    void c2_theDatagramHeaderCarriesTheFirstSectionsEpochAndChunk() {
        String encoded = CoopMessages.datagram("0123456789abcdef", "fedcba9876543210",
                CoopMessages.Type.NPC_FLEET_MOTION, 41L, 400L, 3, "body");

        CoopMessages.DatagramHeader header = CoopMessages.parseDatagramHeader(encoded);

        assertEquals(41L, header.epoch());
        assertEquals(3, header.chunk(), "the TCP fallback keys its coalescing on this");

        // Redundancy's two-section form: the header reports the OLDEST section, which is the one the
        // envelope leads with, so the key is stable across a datagram and its redundant predecessor.
        String withPrevious = CoopMessages.datagram("0123456789abcdef", "fedcba9876543210",
                CoopMessages.Type.NPC_FLEET_MOTION,
                List.of(new CoopMessages.DatagramSection(40L, 300L, 3, "prev"),
                        new CoopMessages.DatagramSection(41L, 400L, 3, "cur")));
        assertEquals(3, CoopMessages.parseDatagramHeader(withPrevious).chunk());
        assertEquals(40L, CoopMessages.parseDatagramHeader(withPrevious).epoch());
    }

    @Test
    void c2_aHeaderWithAnUnparseableSectionStampIsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagramHeader(
                "token\u001fsender\u001fFLEET_SNAPSHOT\u001fnot-a-number\u001f2\u001f0\u001fbody"));
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagramHeader(
                "token\u001fsender\u001fFLEET_SNAPSHOT\u001f1\u001f2\u001fnot-a-chunk\u001fbody"));
        // A section stamp that is cut short is not a datagram this transport emits either.
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagramHeader(
                "token\u001fsender\u001fFLEET_SNAPSHOT\u001f1\u001f2"));
    }

    // ---- Phase 20.1 wire token ------------------------------------------------------------------

    @Test
    void wireTokenIsSixteenLowercaseHexCharactersAndDeterministic() {
        String token = CoopMessages.wireToken("2f2c9a10-0d3c-4f2a-9b6f-6d0a1e5c8b77");

        assertEquals(CoopMessages.WIRE_TOKEN_CHARS, token.length());
        assertTrue(token.matches("[0-9a-f]{16}"), "token must be lowercase hex: " + token);
        assertEquals(token, CoopMessages.wireToken("2f2c9a10-0d3c-4f2a-9b6f-6d0a1e5c8b77"),
                "the same id must always derive the same token — both peers derive it independently");
    }

    @Test
    void wireTokenSeparatesDifferentIdsAndTolerantOfNull() {
        assertNotEquals(CoopMessages.wireToken("session-a"), CoopMessages.wireToken("session-b"));
        assertEquals(CoopMessages.WIRE_TOKEN_CHARS, CoopMessages.wireToken(null).length());
    }

    /** The token is the first 16 hex of SHA-256(id) — pinned so a future refactor cannot drift it. */
    @Test
    void wireTokenIsThePrefixOfTheSha256OfTheId() {
        assertEquals(coop.handshake.CoopChecksum.sha256Text("session-a").substring(0, 16),
                CoopMessages.wireToken("session-a"));
    }

    // ---- Phase 20.5 TCP senderId ----------------------------------------------------------------

    @Test
    void tcpEnvelopeCarriesSenderIdAndDecodesMissingOnesAsNull() {
        CoopMessages.Message stamped =
                CoopMessages.ping("session-a", 1L, 1000L).withSenderId("player-1");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(stamped));
        assertEquals("player-1", decoded.senderId());

        // Every factory builds an unstamped message; that has to survive the round trip as null
        // rather than as the string "null" or a decode failure.
        CoopMessages.Message unstamped = CoopMessages.decode(
                CoopMessages.encode(CoopMessages.ping("session-a", 2L, 1000L)));
        assertNull(unstamped.senderId());
    }

    @Test
    void withSenderIdLeavesAnAlreadyStampedMessageAlone() {
        CoopMessages.Message stamped =
                CoopMessages.ping("session-a", 1L, 1000L).withSenderId("player-1");

        assertEquals("player-1", stamped.withSenderId("player-2").senderId());
        assertNull(CoopMessages.ping("session-a", 1L, 1000L).withSenderId(null).senderId());
    }

    // ---- Phase 20.1 M2 ---------------------------------------------------------------------------

    @Test
    void linkStatusRoundTripsEveryFieldTheFallbackRuleReadsOff() {
        CoopMessages.Message message = CoopMessages.linkStatus("session-a", 4L, 1234L,
                new CoopLinkQuality.Snapshot(87, 210, 4, false, 1500L, 12_000L),
                CoopLinkQuality.TRANSPORT_TCP_FALLBACK,
                new CoopDatagramStats(0L, 5L, 6L, 0L, 0L, 0L, 7L, 0L, 0L, 8L, 0L, 9L, 0L, 3L, 4L, 0L, 0L, ""));

        CoopMessages.LinkStatus parsed =
                CoopMessages.parseLinkStatus(CoopMessages.decode(CoopMessages.encode(message)));

        assertEquals(87, parsed.rttMillis());
        assertEquals(210, parsed.p95RttMillis());
        assertEquals(4, parsed.lossPercent());
        assertFalse(parsed.udpInboundOk());
        assertEquals(CoopLinkQuality.TRANSPORT_TCP_FALLBACK, parsed.transport());
        assertEquals(1500L, parsed.tcpSilenceMillis());
        assertEquals(5L, parsed.droppedTokenMismatch());
        assertEquals(6L, parsed.droppedForeignSource());
        assertEquals(7L, parsed.pathValidations());
        assertEquals(8L, parsed.icmpTransients());
    }

    @Test
    void anUnmeasuredRttTravelsAsMinusOneRatherThanZero() {
        CoopMessages.Message message = CoopMessages.linkStatus("session-a", 4L, 1234L,
                new CoopLinkQuality.Snapshot(null, null, 0, true, 0L, 0L),
                CoopLinkQuality.TRANSPORT_UDP,
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, ""));

        CoopMessages.LinkStatus parsed = CoopMessages.parseLinkStatus(message);

        assertEquals(-1, parsed.rttMillis());
        assertEquals(-1, parsed.p95RttMillis());
        assertTrue(parsed.udpInboundOk());
    }

    // ---- Phase 29 M2: the announced cadence tier -------------------------------------------------

    @Test
    void theAnnouncedCadenceTierRoundTrips() {
        CoopMessages.Message message = CoopMessages.linkStatus("session-a", 4L, 1234L,
                new CoopLinkQuality.Snapshot(20, 30, 0, true, 0L, 0L),
                CoopLinkQuality.TRANSPORT_UDP, CoopCadenceTier.FLOOR.hz(),
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, ""));

        CoopMessages.LinkStatus parsed =
                CoopMessages.parseLinkStatus(CoopMessages.decode(CoopMessages.encode(message)));

        assertEquals(5, parsed.cadenceHz());
        assertEquals(CoopCadenceTier.FLOOR, parsed.cadenceTier());
    }

    @Test
    void aLinkStatusWithoutTheCadenceFieldParsesAsTheDefaultTier() {
        // Byte-for-byte what a pre-Phase-29-M2 peer puts on the wire: every field but cadenceHz.
        CoopMessages.Message legacy = new CoopMessages.Message(CoopMessages.Type.LINK_STATUS,
                "session-a", 4L, 1234L,
                "{\"rttMillis\":40,\"p95RttMillis\":60,\"lossPercent\":1,\"udpInboundOk\":\"true\","
                        + "\"transport\":\"UDP\",\"tcpSilenceMillis\":10,\"droppedTokenMismatch\":0,"
                        + "\"droppedForeignSource\":0,\"pathValidations\":0,\"icmpTransients\":0,"
                        + "\"escalatedToTcp\":0}");

        CoopMessages.LinkStatus parsed = CoopMessages.parseLinkStatus(legacy);

        assertEquals(10, parsed.cadenceHz(),
                "field absent and 10 Hz are the same statement about that peer");
        assertEquals(CoopCadenceTier.DEFAULT, parsed.cadenceTier());
    }

    /**
     * The wrapped payload contains unit separators and can contain newlines; both would break the
     * JSON line framing if the escape were not exact.
     */
    @Test
    void aStateDatagramSurvivesTheJsonLineFramingByteForByte() {
        String datagram = CoopMessages.datagram("token", "sender",
                CoopMessages.Type.FLEET_SNAPSHOT, 9L, 1L, "body\nwithseparators\"and quotes\"");

        CoopMessages.Message message = CoopMessages.stateDatagram("session-a", 3L, 100L, datagram);
        String decoded = CoopMessages.parseStateDatagram(
                CoopMessages.decode(CoopMessages.encode(message)));

        assertEquals(datagram, decoded);
        assertFalse(CoopMessages.encode(message).contains("\n"), "the frame must stay one line");
        assertEquals(CoopMessages.Type.FLEET_SNAPSHOT,
                CoopMessages.parseDatagramHeader(decoded).type());
    }

    // ---- Phase 20.2 session resume ----------------------------------------------------------------

    @Test
    void aResumeRequestCarriesTheSessionInBothTheEnvelopeAndThePayload() {
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(
                CoopMessages.sessionResumeRequest("session-a", 5L, 9000L, "guest-player")));

        assertEquals(CoopMessages.Type.SESSION_RESUME_REQUEST, decoded.type());
        assertEquals("session-a", decoded.sessionId());
        assertEquals(5L, decoded.seq());
        assertEquals(9000L, decoded.sentAtMillis());
        // The payload copy is what the host's grace check compares, deliberately independent of the
        // envelope so the check cannot start passing by accident if envelope handling changes.
        assertEquals("session-a", CoopMessages.parseResumeSessionId(decoded));
        assertEquals("guest-player", CoopMessages.parseResumePlayerId(decoded));
    }

    @Test
    void aResumeRequestRefusesToBeBuiltWithoutBothIdentities() {
        assertThrows(RuntimeException.class,
                () -> CoopMessages.sessionResumeRequest(null, 1L, 0L, "guest-player"));
        assertThrows(RuntimeException.class,
                () -> CoopMessages.sessionResumeRequest("session-a", 1L, 0L, " "));
    }

    @Test
    void aResumeAcceptNamesTheSessionItIsHandingBack() {
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(
                CoopMessages.sessionResumeAccept("session-a", 6L, 9100L)));

        assertEquals(CoopMessages.Type.SESSION_RESUME_ACCEPT, decoded.type());
        assertEquals("session-a", CoopMessages.parseResumeSessionId(decoded));
    }

    @Test
    void aResumeRejectCarriesItsReasonAndToleratesHavingNoSession() {
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(
                CoopMessages.sessionResumeReject(null, 7L, 9200L, "session id does not match")));

        assertEquals(CoopMessages.Type.SESSION_RESUME_REJECT, decoded.type());
        assertNull(decoded.sessionId(), "a host may be rejecting a session it never heard of");
        assertEquals("session id does not match", CoopMessages.parseResumeRejectReason(decoded));
        assertEquals("", CoopMessages.parseResumeRejectReason(CoopMessages.decode(CoopMessages.encode(
                CoopMessages.sessionResumeReject("session-a", 8L, 9300L, null)))));
    }
}
