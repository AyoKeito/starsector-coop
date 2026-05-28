package coop.session;

import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSessionStateTest {
    @Test
    void hostTransitionsFromNoneToWaitingThenConnectedWithoutCanonicalSession() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));

        state.startHost("Host");

        assertEquals(CoopConnectionRole.HOST, state.role());
        assertEquals(CoopLobbyState.HOST_WAITING, state.connectionState());
        assertEquals("lobby-a", state.provisionalLobbyId());
        assertEquals("host-player", state.localPlayerId());
        assertEquals("Host", state.localName());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());

        assertTrue(state.canAcceptGuest());
        state.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));

        assertEquals(CoopLobbyState.HOST_CONNECTED, state.connectionState());
        assertEquals("guest-player", state.remotePlayerId());
        assertEquals("Guest", state.remoteName());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
    }

    @Test
    void guestTransitionsFromNoneToConnectingThenConnectedWithoutCanonicalSession() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("guest-player"));

        state.startGuest("Guest");

        assertEquals(CoopConnectionRole.GUEST, state.role());
        assertEquals(CoopLobbyState.GUEST_CONNECTING, state.connectionState());
        assertEquals("guest-player", state.localPlayerId());
        assertEquals("Guest", state.localName());
        assertNull(state.provisionalLobbyId());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());

        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));

        assertEquals(CoopLobbyState.GUEST_CONNECTED, state.connectionState());
        assertEquals("lobby-a", state.provisionalLobbyId());
        assertEquals("host-player", state.remotePlayerId());
        assertEquals("Host", state.remoteName());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
    }

    @Test
    void hostRefusesSecondGuestAfterLobbyIsOccupied() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));

        assertFalse(state.canAcceptGuest());

        String reason = state.rejectReasonForGuest(new CoopPlayerInfo("guest-b", "Guest B"));

        assertEquals("Lobby already has a guest", reason);
        assertEquals(CoopLobbyState.HOST_CONNECTED, state.connectionState());
        assertEquals("guest-a", state.remotePlayerId());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
    }

    @Test
    void hostAllocatesCanonicalSessionOnlyAfterHandshakeAcceptance() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));

        String sessionId = state.hostAcceptHandshake();

        assertEquals("session-a", sessionId);
        assertEquals("session-a", state.sessionId());
        assertTrue(state.handshakeValidated());
    }

    @Test
    void guestRecordsCanonicalSessionOnlyAfterHandshakeAcceptance() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("guest-player"));
        state.startGuest("Guest");
        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));

        state.guestAcceptHandshake("session-a");

        assertEquals("session-a", state.sessionId());
        assertTrue(state.handshakeValidated());
    }

    @Test
    void handshakeRejectionClearsCanonicalSession() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        state.hostAcceptHandshake();

        state.rejectHandshake("gameVersion: host=0.98a-RC8 guest=0.97a");

        assertEquals(CoopLobbyState.REJECTED, state.connectionState());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
    }

    private static final class SequencedIds implements java.util.function.Supplier<String> {
        private final Queue<String> ids;

        private SequencedIds(String... ids) {
            this.ids = new ArrayDeque<>(List.of(ids));
        }

        @Override
        public String get() {
            return ids.remove();
        }
    }
}
