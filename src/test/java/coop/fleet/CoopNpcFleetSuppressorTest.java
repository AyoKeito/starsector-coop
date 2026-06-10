package coop.fleet;

import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNpcFleetSuppressorTest {

    @Test
    void plainNpcFleetIsSwept() {
        assertTrue(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, false, false));
    }

    @Test
    void localPlayerFleetIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(true, false, false, false));
    }

    @Test
    void stationIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, true, false, false));
    }

    @Test
    void remotePlayerMirrorIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, true, false));
    }

    @Test
    void npcMirrorIsPreserved() {
        assertFalse(CoopNpcFleetSuppressor.shouldRemoveFleet(false, false, false, true));
    }

    @Test
    void suppressorRunsOnGuestOnly() {
        assertTrue(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.GUEST));
        assertFalse(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.HOST));
        assertFalse(CoopNpcFleetSuppressor.activeForRole(CoopConnectionRole.NONE));
    }

    @Test
    void recognizesKnownAndSuffixedSpawnerScripts() {
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("RouteManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("MercFleetManagerV2"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("RemnantSeededFleetManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("PersonBountyManager"));
        assertTrue(CoopNpcFleetSuppressor.isSpawnerScriptName("HegemonyPatrolFleetManager"));
    }

    @Test
    void doesNotMatchUnrelatedScripts() {
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName("CoopNetPump"));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName("CoopCampaignReplicator"));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(""));
        assertFalse(CoopNpcFleetSuppressor.isSpawnerScriptName(null));
    }
}
