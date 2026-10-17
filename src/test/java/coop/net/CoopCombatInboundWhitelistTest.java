package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopCombatInboundWhitelistTest {

    @Test
    void sharedCombatAndKeepalivesAreAllowedMidBattle() {
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.SHARED_COMBAT));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.PING));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.PONG));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.LINK_STATUS));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.RELIABLE_ACK));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.BATTLE_END));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.BATTLE_STATUS));
        assertTrue(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.SESSION_LEAVE));
    }

    @Test
    void campaignMutatorsStayDeferredUntilCampaignPumpResumes() {
        assertFalse(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.WORLD_DELTA));
        assertFalse(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.MARKET_TXN));
        assertFalse(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.NPC_FLEET_SET));
        assertFalse(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.FLEET_SNAPSHOT));
        assertFalse(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.INTERACTION_CLAIM));
        assertFalse(CoopNetPump.allowedDuringCombatInbound(CoopMessages.Type.BATTLE_BEGIN));
    }
}
