package coop.net;

import coop.session.CoopPlayerInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
