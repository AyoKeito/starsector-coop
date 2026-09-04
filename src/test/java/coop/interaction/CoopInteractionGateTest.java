package coop.interaction;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopInteractionGateTest {

    @Test
    void firstClaimWinsByHostReceiveSequence() {
        CoopInteractionGate gate = new CoopInteractionGate();

        CoopInteractionGate.ClaimResult first = gate.arbitrate("market-1", "host", "Jangala");
        CoopInteractionGate.ClaimResult second = gate.arbitrate("market-1", "guest", "Jangala");

        assertTrue(first.accepted());
        assertEquals("host", first.claim().playerId());
        assertEquals(1L, first.hostSeq());

        assertFalse(second.accepted());
        assertEquals("host", second.rejectedByPlayerId());
        assertEquals("already_claimed_by:host", second.rejectReason());
        // The accepted holder keeps the earlier sequence; the loser arbitrated later.
        assertTrue(first.hostSeq() < second.hostSeq());
        assertEquals("host", gate.holderOf("market-1"));
    }

    @Test
    void samePlayerReclaimKeepsOriginalClaim() {
        CoopInteractionGate gate = new CoopInteractionGate();

        CoopInteractionGate.ClaimResult first = gate.arbitrate("fleet-7", "guest", "Pirate Armada");
        CoopInteractionGate.ClaimResult reclaim = gate.arbitrate("fleet-7", "guest", "Pirate Armada");

        assertTrue(reclaim.accepted());
        assertEquals(first.hostSeq(), reclaim.hostSeq());
        assertEquals("guest", gate.holderOf("fleet-7"));
    }

    @Test
    void releaseByHolderAllowsNewClaim() {
        CoopInteractionGate gate = new CoopInteractionGate();
        gate.arbitrate("market-1", "host", "Jangala");

        assertTrue(gate.release("market-1", "host"));
        assertNull(gate.holderOf("market-1"));

        CoopInteractionGate.ClaimResult afterRelease = gate.arbitrate("market-1", "guest", "Jangala");
        assertTrue(afterRelease.accepted());
        assertEquals("guest", gate.holderOf("market-1"));
    }

    @Test
    void releaseByNonHolderIsRejectedAndKeepsClaim() {
        CoopInteractionGate gate = new CoopInteractionGate();
        gate.arbitrate("market-1", "host", "Jangala");

        assertFalse(gate.release("market-1", "guest"));
        assertEquals("host", gate.holderOf("market-1"));
    }

    @Test
    void blockingClaimReflectsRemoteHolderOnly() {
        CoopInteractionGate gate = new CoopInteractionGate();
        gate.arbitrate("market-1", "host", "Jangala");

        // The other player is locked out; the holder is free.
        assertTrue(gate.isBlockedFor("guest"));
        assertEquals("Jangala", gate.blockingClaimFor("guest").entityName());
        assertFalse(gate.isBlockedFor("host"));
        assertNull(gate.blockingClaimFor("host"));
    }

    @Test
    void blockingClaimPicksEarliestHostSequence() {
        CoopInteractionGate gate = new CoopInteractionGate();
        gate.arbitrate("market-1", "host", "Jangala");
        gate.arbitrate("market-2", "host", "Culann");

        CoopInteractionClaim blocking = gate.blockingClaimFor("guest");
        assertEquals("market-1", blocking.entityId());
        assertEquals(1L, blocking.hostSeq());
    }

    @Test
    void applyAcceptedRecordsHostDecisionOnClient() {
        CoopInteractionGate guestGate = new CoopInteractionGate();
        // Guest never arbitrates; it mirrors the host's accept message.
        guestGate.applyAccepted(new CoopInteractionClaim("market-1", "host", "Jangala", 4L));

        assertTrue(guestGate.isBlockedFor("guest"));
        assertEquals("host", guestGate.holderOf("market-1"));

        guestGate.release("market-1", "host");
        assertFalse(guestGate.isBlockedFor("guest"));
    }

    @Test
    void releaseAllDropsEveryClaimForPlayer() {
        CoopInteractionGate gate = new CoopInteractionGate();
        gate.arbitrate("market-1", "guest", "Jangala");
        gate.arbitrate("fleet-7", "guest", "Pirate Armada");
        gate.arbitrate("market-2", "host", "Culann");

        gate.releaseAll("guest");

        assertNull(gate.holderOf("market-1"));
        assertNull(gate.holderOf("fleet-7"));
        assertEquals("host", gate.holderOf("market-2"));
    }

    /**
     * The session-edge reset asks the gate itself whether there is anything to clear. Its own
     * bookkeeping cannot answer: a claim the drain accepted this frame is here and in none of the
     * pump's side flags, which is how one used to survive a session edge and lock a player out.
     */
    @Test
    void isEmptySeesAClaimNothingElseHasObservedYet() {
        CoopInteractionGate gate = new CoopInteractionGate();
        assertTrue(gate.isEmpty());

        gate.applyAccepted(new CoopInteractionClaim("market-1", "guest", "Jangala", 1L));
        assertFalse(gate.isEmpty());

        gate.clear();
        assertTrue(gate.isEmpty());
    }

    @Test
    void interactionMessagesRoundTrip() {
        CoopMessages.Message claim = CoopMessages.interactionClaim(
                "s1", 1L, 100L, "market-1", "Jangala", "guest");
        CoopMessages.Message decodedClaim = CoopMessages.decode(CoopMessages.encode(claim));
        assertEquals(CoopMessages.Type.INTERACTION_CLAIM, decodedClaim.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(decodedClaim, "entityId"));
        assertEquals("Jangala", CoopMessages.requiredPayloadString(decodedClaim, "entityName"));
        assertEquals("guest", CoopMessages.requiredPayloadString(decodedClaim, "playerId"));

        CoopMessages.Message accept = CoopMessages.interactionAccept(
                "s1", 2L, 200L, "market-1", "host", "Jangala", 5L);
        CoopMessages.Message decodedAccept = CoopMessages.decode(CoopMessages.encode(accept));
        assertEquals(CoopMessages.Type.INTERACTION_ACCEPT, decodedAccept.type());
        assertEquals("host", CoopMessages.requiredPayloadString(decodedAccept, "playerId"));
        assertEquals(5L, CoopMessages.requiredPayloadLong(decodedAccept, "hostSeq"));

        CoopMessages.Message reject = CoopMessages.interactionReject(
                "s1", 3L, 300L, "market-1", "already_claimed_by:host");
        CoopMessages.Message decodedReject = CoopMessages.decode(CoopMessages.encode(reject));
        assertEquals(CoopMessages.Type.INTERACTION_REJECT, decodedReject.type());
        assertEquals("already_claimed_by:host",
                CoopMessages.requiredPayloadString(decodedReject, "reason"));

        CoopMessages.Message release = CoopMessages.interactionRelease(
                "s1", 4L, 400L, "market-1", "host");
        CoopMessages.Message decodedRelease = CoopMessages.decode(CoopMessages.encode(release));
        assertEquals(CoopMessages.Type.INTERACTION_RELEASE, decodedRelease.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(decodedRelease, "entityId"));
        assertEquals("host", CoopMessages.requiredPayloadString(decodedRelease, "playerId"));
    }
}
