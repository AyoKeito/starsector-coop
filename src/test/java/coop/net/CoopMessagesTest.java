package coop.net;

import org.junit.jupiter.api.Test;

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
}
