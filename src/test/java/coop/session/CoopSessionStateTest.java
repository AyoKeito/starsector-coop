package coop.session;

import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void hostFreesGuestSlotWhenChannelDisconnects() {
        CoopSessionState state = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        state.hostAcceptHandshake();
        state.recordSeedLock(42L, "coop-seed", "fingerprint");

        assertTrue(state.onChannelDisconnected());

        // Back to accepting, with the host's own identity intact across the reconnect.
        assertEquals(CoopLobbyState.HOST_WAITING, state.connectionState());
        assertTrue(state.canAcceptGuest());
        assertEquals("host-player", state.localPlayerId());
        assertEquals("lobby-a", state.provisionalLobbyId());
        assertNull(state.remotePlayerId());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
        assertNull(state.seedLong());

        // A different guest can now join.
        state.hostAcceptGuest(new CoopPlayerInfo("guest-b", "Guest B"));
        assertEquals("guest-b", state.remotePlayerId());
    }

    @Test
    void guestRewindsToConnectingWhenChannelDisconnects() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("guest-player"));
        state.startGuest("Guest");
        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        state.guestAcceptHandshake("session-a");
        state.recordSeedLock(42L, "coop-seed", "fingerprint");

        assertTrue(state.onChannelDisconnected());

        assertEquals(CoopLobbyState.GUEST_CONNECTING, state.connectionState());
        assertEquals("guest-player", state.localPlayerId());
        assertNull(state.provisionalLobbyId());
        assertNull(state.remotePlayerId());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
        assertNull(state.seedLong());

        // The rewound guest can run the full sequence again.
        state.guestAcceptLobby("lobby-b", new CoopPlayerInfo("host-player", "Host"));
        assertEquals(CoopLobbyState.GUEST_CONNECTED, state.connectionState());
    }

    @Test
    void disconnectRecoversFromRejectedSoACorrectedPeerCanRetry() {
        CoopSessionState state = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        state.rejectHandshake("gameVersion: host=0.98a guest=0.97a");
        assertEquals(CoopLobbyState.REJECTED, state.connectionState());

        assertTrue(state.onChannelDisconnected());

        assertEquals(CoopLobbyState.HOST_WAITING, state.connectionState());
        assertTrue(state.canAcceptGuest());
    }

    @Test
    void disconnectIsANoOpBeforeAnyRoleIsChosen() {
        CoopSessionState state = new CoopSessionState(new SequencedIds());

        assertFalse(state.onChannelDisconnected());
        assertEquals(CoopLobbyState.NONE, state.connectionState());
    }

    @Test
    void disconnectWhileAlreadyWaitingReportsNothingDropped() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        state.startHost("Host");

        assertFalse(state.onChannelDisconnected());
        assertEquals(CoopLobbyState.HOST_WAITING, state.connectionState());
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
        state.recordSeedLock(123456789L, "coop-seed", "fingerprint-a");

        state.rejectHandshake("gameVersion: host=0.98a-RC8 guest=0.97a");

        assertEquals(CoopLobbyState.REJECTED, state.connectionState());
        assertNull(state.sessionId());
        assertFalse(state.handshakeValidated());
        assertNull(state.seedLong());
        assertNull(state.seedString());
        assertNull(state.sectorFingerprint());
    }

    @Test
    void seedLockFieldsAreStoredAfterHandshake() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        state.hostAcceptHandshake();

        state.recordSeedLock(123456789L, "coop-seed", "fingerprint-a");

        assertEquals(123456789L, state.seedLong());
        assertEquals("coop-seed", state.seedString());
        assertEquals("fingerprint-a", state.sectorFingerprint());
    }

    // ---- Phase 21: the lobby gate + the kept handshake reason -------------------------------------

    @Test
    void theLobbyGateStartsClosedAndOpensOnlyOnce() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        state.startHost("Host");

        assertFalse(state.lobbyReleased(), "a fresh session has not been started by anybody yet");
        assertTrue(state.releaseLobby());
        assertTrue(state.lobbyReleased());
        assertFalse(state.releaseLobby(), "releasing twice is a no-op");
    }

    @Test
    void losingTheCanonicalSessionClosesTheLobbyGateAgain() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        state.hostAcceptHandshake();
        state.recordSeedLock(1L, "coop-seed", "fingerprint-a");
        state.releaseLobby();

        // The peer drops outside a grace window: the canonical session goes, and so does the gate.
        assertTrue(state.onChannelDisconnected());

        assertFalse(state.lobbyReleased(),
                "a fresh handshake must run a fresh lobby, not drop a new partner into a live world");
    }

    @Test
    void resetClosesTheLobbyGate() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        state.startHost("Host");
        state.releaseLobby();

        state.reset();

        assertFalse(state.lobbyReleased());
    }

    @Test
    void aRejectedHandshakeKeepsItsReason() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("guest-player"));
        state.startGuest("Guest");
        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));

        state.rejectHandshake("mod list differs: nexerelin 0.11 vs 0.12");

        assertEquals("mod list differs: nexerelin 0.11 vs 0.12", state.handshakeRejectReason());
        assertEquals(CoopLobbyState.REJECTED, state.connectionState());
    }

    @Test
    void aBlankHandshakeReasonIsStoredAsNull() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("guest-player"));
        state.startGuest("Guest");
        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));

        state.rejectHandshake("   ");

        assertNull(state.handshakeRejectReason());
    }

    @Test
    void theHandshakeReasonIsClearedWhenTheGuestRearmsForAFreshRound() {
        CoopSessionState state = new CoopSessionState(new SequencedIds("guest-player"));
        state.startGuest("Guest");
        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        state.rejectHandshake("versions differ");
        state.guestRejectLobby("try again");

        assertTrue(state.guestRearmLobby());

        assertNull(state.handshakeRejectReason(), "a new round must not show the old round's reason");
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

    /**
     * ui-session-1: a retryable reject drained before the drop leaves REJECTED behind, and the rewind
     * out of it means guestRearmLobby - the only other clear - never runs again. The reason then
     * survived into the next accepted round, where CoopNetPump reads it as a live HOST_REFUSED.
     */
    @Test
    void aDropClearsARetryableRejectSoTheNextRoundStartsClean() {
        CoopSessionState state = new CoopSessionState(() -> "guest-player");
        state.startGuest("Guest");
        state.guestRejectLobby("Lobby already has a guest");
        assertEquals(CoopLobbyState.REJECTED, state.connectionState());

        state.onChannelDisconnected();

        assertEquals(CoopLobbyState.GUEST_CONNECTING, state.connectionState());
        assertNull(state.rejectReason());
        assertFalse(state.guestRearmLobby(), "the rewind already happened; nothing left to rearm");

        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        assertEquals(CoopLobbyState.GUEST_CONNECTED, state.connectionState());
        assertNull(state.rejectReason());
    }

    /** A terminal reject is still the only on-screen explanation, so the drop leaves it standing. */
    @Test
    void aTerminalRejectKeepsItsReasonAcrossTheDrop() {
        CoopSessionState state = new CoopSessionState(() -> "guest-player");
        state.startGuest("Guest");
        state.guestRejectLobby("Wrong lobby password", true);

        state.onChannelDisconnected();

        assertEquals(CoopLobbyState.REJECTED, state.connectionState());
        assertEquals("Wrong lobby password", state.rejectReason());
    }

    /** An accepted round can never carry a previous round's refusal. */
    @Test
    void anAcceptedLobbyRoundClearsAnyLeftoverReason() {
        CoopSessionState state = new CoopSessionState(() -> "guest-player");
        state.startGuest("Guest");
        state.guestRejectLobby("Lobby already has a guest");
        state.guestRearmLobby();
        state.rejectHandshake("mod list mismatch");
        state.onChannelDisconnected();

        state.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));

        assertNull(state.rejectReason());
        assertNull(state.handshakeRejectReason());
    }

    /**
     * net-3: the pump hands in the id this save has used since its first coop launch, so the same
     * human is one player across reloads instead of a new stats column per launch.
     */
    @Test
    void aStableLocalPlayerIdReplacesThePerProcessOne() {
        CoopSessionState state = new CoopSessionState(() -> "minted-per-process");
        state.startHost("Host");
        assertEquals("minted-per-process", state.localPlayerId());

        state.adoptLocalPlayerId("  from-the-save  ");
        assertEquals("from-the-save", state.localPlayerId());

        // Blank and no-op adoptions leave it alone rather than clearing identity.
        state.adoptLocalPlayerId("");
        state.adoptLocalPlayerId(null);
        assertEquals("from-the-save", state.localPlayerId());
    }

    @Test
    void aLocalPlayerIdCannotChangeUnderALiveSession() {
        CoopSessionState state = new CoopSessionState(() -> "minted-per-process");
        state.startHost("Host");
        state.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));

        assertThrows(IllegalStateException.class, () -> state.adoptLocalPlayerId("too-late"));
        assertEquals("minted-per-process", state.localPlayerId());
    }
}
