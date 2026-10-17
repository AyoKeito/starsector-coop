package coop.combat.join;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: flagship never claimable; only marked non-flagship hulls are remote seats.
 */
class CoopRemoteCommRulesTest {

    @Test
    void flagshipIndexCannotBeMarked() {
        assertFalse(CoopRemoteCommRules.canMarkAtFleetIndex(0));
        assertTrue(CoopRemoteCommRules.canMarkAtFleetIndex(1));
        assertTrue(CoopRemoteCommRules.canMarkAtFleetIndex(3));
    }

    @Test
    void claimRequiresAliveMarkedNonPlayerShip() {
        assertTrue(CoopRemoteCommRules.isClaimableFlags(
                true, false, false, true, false));
    }

    @Test
    void deadFighterShuttleUnmarkedOrEngagedPlayerAreNotClaimable() {
        assertFalse(CoopRemoteCommRules.isClaimableFlags(
                false, false, false, true, false));
        assertFalse(CoopRemoteCommRules.isClaimableFlags(
                true, true, false, true, false));
        assertFalse(CoopRemoteCommRules.isClaimableFlags(
                true, false, true, true, false));
        assertFalse(CoopRemoteCommRules.isClaimableFlags(
                true, false, false, false, false));
        assertFalse(CoopRemoteCommRules.isClaimableFlags(
                true, false, false, true, true));
    }

    @Test
    void hullmodIdMatchesCsv() {
        assertEquals("coop_remote_comm", CoopRemoteCommRules.HULLMOD_ID);
    }
}
