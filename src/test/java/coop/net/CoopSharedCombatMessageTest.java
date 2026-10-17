package coop.net;

import coop.combat.shared.CoopSharedCombatFrameCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level checks for {@link CoopMessages#sharedCombat} — multiline FRAME/OFFER bodies must
 * survive JSON escape/parse or dual-engine sync silently desyncs.
 */
class CoopSharedCombatMessageTest {

    @Test
    void offerBodyRoundTripsBattleIdModeAndHulls() {
        String body = "battle-1\nPROXIMITY\nonslaught,wolf";
        CoopMessages.Message msg = CoopMessages.sharedCombat(
                "session-a", 1L, 0L, CoopMessages.SharedCombatAction.OFFER, body);
        assertEquals(CoopMessages.Type.SHARED_COMBAT, msg.type());
        assertEquals("OFFER", CoopMessages.payload(msg).requiredString("action"));
        assertEquals(body, CoopMessages.payload(msg).optionalString("body", ""));
    }

    @Test
    void frameBodyWithShipRecordsRoundTrips() {
        String frames = CoopSharedCombatFrameCodec.encode(List.of(
                new CoopSharedCombatFrameCodec.ShipFrame(
                        "s1", "wolf", "Wolf One", 0, 100f, -50f, 180f, 0.75f, 0.2f, true),
                new CoopSharedCombatFrameCodec.ShipFrame(
                        "s2", "lasher", "Lasher", 1, 0f, 0f, 0f, 0f, 1f, false)));
        String body = "battle-9\n42\n" + frames;
        CoopMessages.Message msg = CoopMessages.sharedCombat(
                "session-a", 2L, 0L, CoopMessages.SharedCombatAction.FRAME, body);
        String decoded = CoopMessages.payload(msg).optionalString("body", "");
        assertEquals(body, decoded);
        String[] parts = decoded.split("\n", 3);
        assertEquals("battle-9", parts[0]);
        assertEquals("42", parts[1]);
        assertEquals(2, CoopSharedCombatFrameCodec.decode(parts[2]).size());
        assertEquals("s1", CoopSharedCombatFrameCodec.decode(parts[2]).get(0).shipId());
    }

    @Test
    void claimAckWithPipesInTreeRoundTripsThroughJson() {
        coop.combat.shared.CoopSharedCombatClaimCodec.ClaimAck ack =
                new coop.combat.shared.CoopSharedCombatClaimCodec.ClaimAck(
                        true, "battle-1", "legion", "Mark", 12.5f, -3f, 45f, "TREE|root|child");
        CoopMessages.Message msg = CoopMessages.sharedCombat(
                "session-a", 3L, 0L, CoopMessages.SharedCombatAction.CLAIM_ACK, ack.encode());
        String wire = CoopMessages.payload(msg).optionalString("body", "");
        assertTrue(wire.contains("TREE\\|root\\|child"), wire);
        coop.combat.shared.CoopSharedCombatClaimCodec.ClaimAck parsed =
                coop.combat.shared.CoopSharedCombatClaimCodec.ClaimAck.decode(wire);
        assertTrue(parsed.ok());
        assertEquals("TREE|root|child", parsed.treeOrReason());
        assertEquals(12.5f, parsed.x(), 0.001f);
    }
}
