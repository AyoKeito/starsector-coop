package coop.combat.shared;

import coop.combat.join.CoopCombatJoinMode;
import coop.combat.join.CoopRemoteSupportPrompt;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSharedCombatSessionTest {

    @Test
    void engagerOfferSendsSharedCombatOffer() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = liveSession();
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, session, () -> 1000L);

        shared.onLocalEngagerBattleBegin("battle-x", CoopCombatJoinMode.PROXIMITY,
                List.of("onslaught", "wolf"));

        assertEquals(CoopSharedCombatSession.Phase.OFFERED, shared.phase());
        assertEquals(1, service.sent.size());
        assertEquals(CoopMessages.Type.SHARED_COMBAT, service.sent.get(0).type());
        assertEquals("OFFER", CoopMessages.payload(service.sent.get(0)).requiredString("action"));
        assertTrue(CoopMessages.payload(service.sent.get(0)).optionalString("body", "")
                .startsWith("PROXIMITY"));
    }

    @Test
    void remoteDeclineKeepsSessionIdleWithoutEntry() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onPartnerBattleOffered("battle-r", CoopCombatJoinMode.REMOTE_SUPPORT, List.of());
        assertEquals(CoopSharedCombatSession.Phase.OFFERED, shared.phase());

        shared.onRemoteSupportUnavailable("battle-r", CoopRemoteSupportPrompt.State.DECLINED);

        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertFalse(shared.isActive());
    }

    @Test
    void disconnectEndsWithoutLeaveWireWhenAlreadyDisconnected() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-d", CoopCombatJoinMode.PROXIMITY, List.of());
        service.sent.clear();

        shared.onBattleEnded("DISCONNECT");

        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertEquals("DISCONNECT", shared.endOutcome());
        assertTrue(service.sent.isEmpty(), "disconnect must not try to LEAVE on a dead link");
    }

    @Test
    void leaveFromPeerClearsActiveSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-l", CoopCombatJoinMode.PROXIMITY, List.of());
        service.sent.clear();

        shared.handleMessage(CoopMessages.sharedCombat(
                "session-a", 9L, 1L, CoopMessages.SharedCombatAction.LEAVE, "PEER_END"));

        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertEquals("PEER_END", shared.endOutcome());
    }

    private static CoopSessionState liveSession() {
        CoopSessionState session = new CoopSessionState(
                TestSessions.sequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(1234L, "seed", "fingerprint");
        return session;
    }
}
