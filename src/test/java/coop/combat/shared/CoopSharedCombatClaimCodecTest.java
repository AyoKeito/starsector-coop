package coop.combat.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSharedCombatClaimCodecTest {

    @Test
    void claimRequestRoundTrip() {
        CoopSharedCombatClaimCodec.ClaimRequest req = new CoopSharedCombatClaimCodec.ClaimRequest(
                "battle-1", "ship-9", "legion", "Mark", "legion_Standard");
        CoopSharedCombatClaimCodec.ClaimRequest decoded =
                CoopSharedCombatClaimCodec.ClaimRequest.decode(req.encode());
        assertEquals("battle-1", decoded.battleId());
        assertEquals("ship-9", decoded.targetShipId());
        assertEquals("legion", decoded.hullId());
        assertEquals("Mark", decoded.shipName());
        assertEquals("legion_Standard", decoded.variantHint());
    }

    @Test
    void claimAckRoundTripIncludingTree() {
        CoopSharedCombatClaimCodec.ClaimAck ack = new CoopSharedCombatClaimCodec.ClaimAck(
                true, "battle-1", "legion", "Mark", 12.5f, -3f, 45f, "TREE|root|child");
        CoopSharedCombatClaimCodec.ClaimAck decoded =
                CoopSharedCombatClaimCodec.ClaimAck.decode(ack.encode());
        assertTrue(decoded.ok());
        assertEquals("battle-1", decoded.battleId());
        assertEquals("legion", decoded.hullId());
        assertEquals(12.5f, decoded.x(), 0.001f);
        assertEquals(-3f, decoded.y(), 0.001f);
        assertEquals(45f, decoded.facing(), 0.001f);
        assertEquals("TREE|root|child", decoded.treeOrReason());
    }

    @Test
    void deniedAckPreservesReason() {
        CoopSharedCombatClaimCodec.ClaimAck ack = new CoopSharedCombatClaimCodec.ClaimAck(
                false, "b", "", "", 0f, 0f, 0f, "NO_MARKED_SHIP");
        CoopSharedCombatClaimCodec.ClaimAck decoded =
                CoopSharedCombatClaimCodec.ClaimAck.decode(ack.encode());
        assertFalse(decoded.ok());
        assertEquals("NO_MARKED_SHIP", decoded.treeOrReason());
    }
}
