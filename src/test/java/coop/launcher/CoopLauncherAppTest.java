package coop.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher window itself needs a display, so what is covered here is the handful of decisions
 * that were pulled out of it: which results from a background task are still allowed to touch the
 * co-op port and the address field once they land back on the event dispatch thread.
 */
class CoopLauncherAppTest {

    // ---- the connection check and the co-op port ------------------------------------------------

    @Test
    void aCheckThatIsStillTheCurrentOneMayOpenTheListener() {
        assertTrue(CoopLauncherApp.checkResultStillApplies(3, 3, false, true));
    }

    /**
     * The one that mattered: nothing disables LAUNCH while a check runs, and the check can take
     * twenty seconds. A listener bound after the game started either loses the game its bind or
     * answers the guest with a launcher banner on the co-op port.
     */
    @Test
    void aCheckThatFinishesAfterLaunchDoesNotOpenTheListener() {
        assertFalse(CoopLauncherApp.checkResultStillApplies(3, 3, true, true));
    }

    @Test
    void aCheckSupersededByANewerOneDoesNotOpenTheListener() {
        assertFalse(CoopLauncherApp.checkResultStillApplies(3, 4, false, true));
    }

    @Test
    void aCheckThatFinishesAfterASwitchToGuestDoesNotOpenAHostListener() {
        assertFalse(CoopLauncherApp.checkResultStillApplies(3, 3, false, false));
    }

    /**
     * The check maps the port and then releases the mapping. Mid-session that is the running game's
     * mapping, and deleting it stops the guest's traffic reaching the host.
     */
    @Test
    void aCheckIsRefusedWhileTheGameIsRunning() {
        String reason = CoopLauncherApp.connectionCheckBlockedReason(true);

        assertNotNull(reason);
        assertTrue(reason.contains("router mapping"), reason);
        assertNull(CoopLauncherApp.connectionCheckBlockedReason(false));
    }

    // ---- the public address lookup ---------------------------------------------------------------

    @Test
    void anAutomaticLookupFillsAFieldThePlayerLeftAlone() {
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(true, "", ""));
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(true, "  ", "   "));
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(true, "10.8.0.2", "10.8.0.2"));
    }

    /**
     * The lookup takes up to ten seconds on a slow network, which is long enough for the host to
     * type a LAN or VPN address and copy an invite from it. Overwriting it changed the invite after
     * it was sent.
     */
    @Test
    void anAutomaticLookupKeepsAnAddressTypedWhileItRan() {
        assertFalse(CoopLauncherApp.shouldApplyLookedUpAddress(true, "", "10.8.0.2"));
        assertFalse(CoopLauncherApp.shouldApplyLookedUpAddress(true, "10.8.0.2", "192.168.1.5"));
    }

    /** Pressing "Look up" is an explicit request to replace whatever is in the field. */
    @Test
    void anExplicitLookupOverwritesWhateverIsThere() {
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(false, "", "10.8.0.2"));
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(false, "10.8.0.2", "192.168.1.5"));
    }
}
