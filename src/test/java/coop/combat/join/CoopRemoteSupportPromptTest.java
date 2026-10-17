package coop.combat.join;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: hyperspace support call state machine (pending → accept/decline, or no marked ship).
 */
class CoopRemoteSupportPromptTest {

    @Test
    void remoteCallWithMarkedShipOpensPending() {
        CoopRemoteSupportPrompt prompt = new CoopRemoteSupportPrompt();
        String[] banners = prompt.beginRemoteCall("b1", "Ada", "pirates", true);

        assertEquals(CoopRemoteSupportPrompt.State.PENDING, prompt.state());
        assertTrue(prompt.isPending());
        assertEquals("b1", prompt.battleId());
        assertEquals(1, banners.length);
        assertTrue(banners[0].contains("Ada"));
        assertTrue(banners[0].contains("hyperspace support"));
    }

    @Test
    void remoteCallWithoutMarkedShipBlocksAccept() {
        CoopRemoteSupportPrompt prompt = new CoopRemoteSupportPrompt();
        String[] banners = prompt.beginRemoteCall("b2", "Bea", "", false);

        assertEquals(CoopRemoteSupportPrompt.State.NO_MARKED_SHIP, prompt.state());
        assertFalse(prompt.isPending());
        assertFalse(prompt.accept());
        assertFalse(prompt.decline());
        assertTrue(banners[0].contains("no ship is marked"));
    }

    @Test
    void acceptAndDeclineOnlyWorkFromPending() {
        CoopRemoteSupportPrompt prompt = new CoopRemoteSupportPrompt();
        assertFalse(prompt.accept());
        assertFalse(prompt.decline());

        prompt.beginRemoteCall("b3", "Cy", "remnants", true);
        assertTrue(prompt.accept());
        assertEquals(CoopRemoteSupportPrompt.State.ACCEPTED, prompt.state());
        assertFalse(prompt.decline());
        assertTrue(prompt.acceptBanner().contains("remote-pilot"));

        prompt.beginRemoteCall("b4", "Dee", "pirates", true);
        assertTrue(prompt.decline());
        assertEquals(CoopRemoteSupportPrompt.State.DECLINED, prompt.state());
        assertFalse(prompt.accept());
        assertTrue(prompt.declineBanner().contains("declined"));
    }

    @Test
    void proximityNoticeClearsRemoteState() {
        CoopRemoteSupportPrompt prompt = new CoopRemoteSupportPrompt();
        prompt.beginRemoteCall("b5", "Eve", "pirates", true);
        String[] notice = prompt.beginProximityNotice("Eve", "pirates");

        assertEquals(CoopRemoteSupportPrompt.State.IDLE, prompt.state());
        assertTrue(notice[0].contains("proximity join"));
    }

    @Test
    void blankNamesGetDefaults() {
        CoopRemoteSupportPrompt prompt = new CoopRemoteSupportPrompt();
        String[] remote = prompt.beginRemoteCall("b6", "  ", null, true);
        assertTrue(remote[0].contains("your partner"));

        String[] near = prompt.beginProximityNotice(null, " ");
        assertTrue(near[0].contains("Your partner"));
        assertTrue(near[0].contains("an enemy"));
    }
}
