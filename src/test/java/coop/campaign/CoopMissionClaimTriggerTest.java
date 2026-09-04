package coop.campaign;

import com.fs.starfarer.api.Global;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import coop.testing.RecordingNetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;

/**
 * Phase 12 first-come mission claims, wire level: what the two trigger entry points actually send,
 * and that a claim leaves the board marked so the next pool snapshot cannot un-take the offer.
 *
 * <p>These two methods existed with zero callers until 2026-09-04 — nothing in the game ever sent a
 * {@code MISSION_CLAIM_REQUEST}, so the arbitration below it never ran and both players could accept
 * the same bar offer. {@link CoopBarAcceptanceWatcher} is the caller now; this covers the half of the
 * path that does not need an engine.
 */
class CoopMissionClaimTriggerTest {

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
    }

    @Test
    void hostLocalClaimMarksTheBoardAndBroadcastsTheAccept() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1000L);
        replicator.missionBoard().applySnapshot(List.of(barEntry("smuggling")));

        assertTrue(replicator.hostClaimMissionLocally("smuggling"));

        assertEquals("host-player", replicator.missionBoard().claimHolder("smuggling"));
        assertEquals("host-player", replicator.missionBoard().entry("smuggling").acceptedByPlayerId());
        assertEquals(1, service.sent.size());
        CoopMessages.Message message = service.sent.get(0);
        assertEquals(CoopMessages.Type.MISSION_CLAIM_ACCEPT, message.type());
        assertEquals("smuggling", CoopMessages.requiredPayloadString(message, "missionId"));
        assertEquals("host-player", CoopMessages.requiredPayloadString(message, "playerId"));
    }

    @Test
    void hostLocalClaimLosesToAGuestClaimThatArrivedFirst() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1000L);
        replicator.missionBoard().applySnapshot(List.of(barEntry("smuggling")));

        replicator.handle(CoopMessages.missionClaimRequest(
                "session-a", 3L, 900L, "smuggling", "guest-player"));
        service.sent.clear();

        assertFalse(replicator.hostClaimMissionLocally("smuggling"),
                "the host detected its own acceptance a frame too late");
        assertEquals("guest-player", replicator.missionBoard().claimHolder("smuggling"));
        assertEquals(0, service.sent.size(), "a lost race broadcasts nothing");
    }

    @Test
    void hostAcceptsTheFirstRemoteClaimAndRejectsTheSecond() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeHostSession(), () -> 1000L);
        replicator.missionBoard().applySnapshot(List.of(barEntry("smuggling")));

        replicator.handle(CoopMessages.missionClaimRequest(
                "session-a", 3L, 900L, "smuggling", "guest-player"));
        replicator.handle(CoopMessages.missionClaimRequest(
                "session-a", 4L, 950L, "smuggling", "host-player"));

        assertEquals(2, service.sent.size());
        assertEquals(CoopMessages.Type.MISSION_CLAIM_ACCEPT, service.sent.get(0).type());
        assertEquals(CoopMessages.Type.MISSION_CLAIM_REJECT, service.sent.get(1).type());
        assertEquals("already_claimed_by:guest-player",
                CoopMessages.requiredPayloadString(service.sent.get(1), "reason"));
    }

    @Test
    void guestClaimTriggerSendsARequestNamingTheLocalPlayer() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1000L);

        replicator.guestRequestMissionClaim("smuggling");

        assertEquals(1, service.sent.size());
        CoopMessages.Message message = service.sent.get(0);
        assertEquals(CoopMessages.Type.MISSION_CLAIM_REQUEST, message.type());
        assertEquals("smuggling", CoopMessages.requiredPayloadString(message, "missionId"));
        assertEquals("guest-player", CoopMessages.requiredPayloadString(message, "playerId"));
    }

    @Test
    void aClaimTriggerIsIgnoredOutsideItsOwnRole() {
        RecordingNetService host = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator hostReplicator = new CoopCampaignReplicator(
                host, activeHostSession(), () -> 1000L);
        RecordingNetService guest = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator guestReplicator = new CoopCampaignReplicator(
                guest, activeGuestSession(), () -> 1000L);

        guestReplicator.hostClaimMissionLocally("smuggling");
        hostReplicator.guestRequestMissionClaim("smuggling");

        assertEquals(0, host.sent.size());
        assertEquals(0, guest.sent.size());
        assertNull(guestReplicator.missionBoard().claimHolder("smuggling"));
    }

    @Test
    void theGuestRecordsTheHostsAcceptOnTheBoard() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1000L);
        replicator.missionBoard().applySnapshot(List.of(barEntry("smuggling")));

        replicator.handle(CoopMessages.missionClaimAccept(
                "session-a", 5L, 1000L, "smuggling", "guest-player", 1L));

        assertEquals("guest-player", replicator.missionBoard().claimHolder("smuggling"));
        assertEquals("guest-player", replicator.missionBoard().entry("smuggling").acceptedByPlayerId());
    }

    @Test
    void aRejectedGuestClaimIsSurvivableWithNothingToRollBack() {
        // No sector, no retained handle: the reject path still has to log, post the notice and
        // return. Swallowing the message would leave the loser holding a duplicate mission with no
        // explanation of why the two clients diverged.
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator = new CoopCampaignReplicator(
                service, activeGuestSession(), () -> 1000L);

        replicator.handle(CoopMessages.missionClaimReject(
                "session-a", 6L, 1000L, "smuggling", "already_claimed_by:host-player"));

        assertEquals(0, service.sent.size(), "a rejected claim answers nothing back");
    }

    private static CoopMissionBoardSync.Entry barEntry(String missionId) {
        return CoopMissionBoardSync.Entry.barOffer(missionId, "HubMissionBarEventWrapper", 7L, "", 30L);
    }

}
