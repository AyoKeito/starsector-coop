package coop.net;

import coop.session.CoopPlayerInfo;
import coop.handshake.CoopHandshakeManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoopMessagesTest {
    @Test
    void pingMessageEncodesDeterministicEnvelopeJson() {
        CoopMessages.Message ping = CoopMessages.ping("session-a", 7L, 123456789L);

        assertEquals(
                "{\"type\":\"PING\",\"sessionId\":\"session-a\",\"seq\":7,\"sentAtMillis\":123456789,\"payloadJson\":\"{}\"}",
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
                        + "\"payloadJson\":\"{\\\"playerId\\\":\\\"guest-player\\\",\\\"playerName\\\":\\\"Guest\\\"}\"}",
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
                "session-a", CoopMessages.Type.FLEET_SNAPSHOT, 7L, 12345L, body);

        CoopMessages.Datagram decoded = CoopMessages.parseDatagram(encoded);

        assertEquals("session-a", decoded.sessionId());
        assertEquals(CoopMessages.Type.FLEET_SNAPSHOT, decoded.type());
        assertEquals(1, decoded.sections().size());
        assertEquals(7L, decoded.sections().get(0).epoch());
        assertEquals(12345L, decoded.sections().get(0).sentGameTimeMillis());
        assertEquals(body, decoded.sections().get(0).body());
    }

    @Test
    void datagramEnvelopeRoundTripsRedundantSectionsOldestFirst() {
        // Bodies deliberately exercise the characters the section split must not trip on: the batch
        // codecs use newlines and pipes internally, never the envelope's unit separator.
        String older = "3\nfleet-a|corvus|1.0|2.0|0.5|0.5";
        String newer = "3\nfleet-a|corvus|1.5|2.5|0.5|0.5";
        String encoded = CoopMessages.datagram("session-a", CoopMessages.Type.NPC_FLEET_MOTION,
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
                () -> CoopMessages.parseDatagram("session-a|FLEET_SNAPSHOT|payload"));
        // A stamp that is not a number is malformed, not a zero.
        String badStamp = CoopMessages.datagram(
                        "session-a", CoopMessages.Type.FLEET_SNAPSHOT, 1L, 0L, "body")
                .replace("10", "x0");
        assertThrows(IllegalArgumentException.class, () -> CoopMessages.parseDatagram(badStamp));
    }
}
