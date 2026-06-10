package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopMissionBoardSyncTest {

    private static CoopMissionBoardSync.Entry entry(String missionId, String acceptedBy) {
        return new CoopMissionBoardSync.Entry("market-1", CoopMissionBoardSync.SourceType.BAR,
                missionId, "Title " + missionId, "giver-1", "50000 credits", acceptedBy, 120L);
    }

    @Test
    void guestRendersHostPool() {
        CoopMissionBoardSync sync = new CoopMissionBoardSync();
        sync.applySnapshot(List.of(entry("m1", ""), entry("m2", "")));

        assertEquals(2, sync.pool().size());
        assertEquals("Title m1", sync.entry("m1").title());
    }

    @Test
    void firstClaimWinsByHostReceiveSequence() {
        CoopMissionBoardSync sync = new CoopMissionBoardSync();
        sync.applySnapshot(List.of(entry("m1", "")));

        CoopMissionBoardSync.ClaimResult first = sync.arbitrate("m1", "host");
        CoopMissionBoardSync.ClaimResult second = sync.arbitrate("m1", "guest");

        assertTrue(first.accepted());
        assertEquals("host", first.claim().acceptedByPlayerId());
        assertEquals(1L, first.hostSeq());

        assertFalse(second.accepted());
        assertEquals("already_claimed_by:host", second.rejectReason());
        assertTrue(first.hostSeq() < second.hostSeq());
        assertEquals("host", sync.claimHolder("m1"));
        // The pool entry reflects the accepted owner.
        assertEquals("host", sync.entry("m1").acceptedByPlayerId());
    }

    @Test
    void samePlayerReclaimKeepsOriginalClaim() {
        CoopMissionBoardSync sync = new CoopMissionBoardSync();
        CoopMissionBoardSync.ClaimResult first = sync.arbitrate("m1", "guest");
        CoopMissionBoardSync.ClaimResult reclaim = sync.arbitrate("m1", "guest");

        assertTrue(reclaim.accepted());
        assertEquals(first.hostSeq(), reclaim.hostSeq());
    }

    @Test
    void visibleEntriesHidesOtherPlayersClaims() {
        CoopMissionBoardSync sync = new CoopMissionBoardSync();
        sync.applySnapshot(List.of(entry("m1", ""), entry("m2", "")));
        sync.arbitrate("m1", "host");

        // The guest can still see the unclaimed m2 but not the host-claimed m1.
        List<CoopMissionBoardSync.Entry> visibleToGuest = sync.visibleEntriesFor("guest");
        assertEquals(1, visibleToGuest.size());
        assertEquals("m2", visibleToGuest.get(0).missionId());

        // The holder still sees its own claimed entry.
        assertEquals(2, sync.visibleEntriesFor("host").size());
    }

    @Test
    void applyAcceptedRecordsHostDecisionOnGuest() {
        CoopMissionBoardSync guest = new CoopMissionBoardSync();
        guest.applySnapshot(List.of(entry("m1", "")));
        guest.applyAccepted(new CoopMissionClaim("m1", "host", 4L));

        assertTrue(guest.isClaimed("m1"));
        assertEquals("host", guest.claimHolder("m1"));
        assertNull(guest.claimHolder("m-absent") == null ? null : guest.claimHolder("m-absent"));
    }

    @Test
    void poolEncodingRoundTripsWithDelimiterEdgeCases() {
        CoopMissionBoardSync.Entry tricky = new CoopMissionBoardSync.Entry(
                "market|2", CoopMissionBoardSync.SourceType.BOUNTY, "m|3",
                "Hunt the\npirate", "giver\\1", "100|000 cr", "guest", 88L);
        String encoded = CoopMissionBoardSync.encodePool(List.of(entry("m1", ""), tricky));
        List<CoopMissionBoardSync.Entry> decoded = CoopMissionBoardSync.decodePool(encoded);

        assertEquals(2, decoded.size());
        CoopMissionBoardSync.Entry back = decoded.get(1);
        assertEquals("market|2", back.marketId());
        assertEquals("m|3", back.missionId());
        assertEquals("Hunt the\npirate", back.title());
        assertEquals("giver\\1", back.giverId());
        assertEquals("100|000 cr", back.rewardSummary());
        assertEquals(CoopMissionBoardSync.SourceType.BOUNTY, back.sourceType());
        assertEquals(88L, back.expiresAtDay());
    }

    @Test
    void claimMessagesRoundTrip() {
        CoopMessages.Message request = CoopMessages.missionClaimRequest("s1", 1L, 10L, "m1", "guest");
        CoopMessages.Message decodedReq = CoopMessages.decode(CoopMessages.encode(request));
        assertEquals(CoopMessages.Type.MISSION_CLAIM_REQUEST, decodedReq.type());
        assertEquals("m1", CoopMessages.requiredPayloadString(decodedReq, "missionId"));
        assertEquals("guest", CoopMessages.requiredPayloadString(decodedReq, "playerId"));

        CoopMessages.Message accept = CoopMessages.missionClaimAccept("s1", 2L, 20L, "m1", "host", 9L);
        CoopMessages.Message decodedAcc = CoopMessages.decode(CoopMessages.encode(accept));
        assertEquals(CoopMessages.Type.MISSION_CLAIM_ACCEPT, decodedAcc.type());
        assertEquals(9L, CoopMessages.requiredPayloadLong(decodedAcc, "hostSeq"));

        CoopMessages.Message reject = CoopMessages.missionClaimReject("s1", 3L, 30L, "m1", "already_claimed_by:host");
        CoopMessages.Message decodedRej = CoopMessages.decode(CoopMessages.encode(reject));
        assertEquals(CoopMessages.Type.MISSION_CLAIM_REJECT, decodedRej.type());
        assertEquals("already_claimed_by:host", CoopMessages.requiredPayloadString(decodedRej, "reason"));

        CoopMessages.Message pool = CoopMessages.missionPoolSnapshot("s1", 4L, 40L, "market-1",
                CoopMissionBoardSync.encodePool(List.of(entry("m1", ""))));
        CoopMessages.Message decodedPool = CoopMessages.decode(CoopMessages.encode(pool));
        assertEquals(CoopMessages.Type.MISSION_POOL_SNAPSHOT, decodedPool.type());
        List<CoopMissionBoardSync.Entry> back = CoopMissionBoardSync.decodePool(
                CoopMessages.requiredPayloadString(decodedPool, "pool"));
        assertEquals("m1", back.get(0).missionId());
    }
}
