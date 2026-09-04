package coop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things about the load-time notice that can be decided without a game: when the engine will
 * accept a message dialog, and that a blank message installs nothing.
 */
class CoopCampaignNoticeTest {

    @Test
    void aMessageDialogNeedsAUiNoOpenDialogAndTheCampaignState() {
        assertTrue(CoopCampaignNotice.canShowNow(true, false, true));
        assertFalse(CoopCampaignNotice.canShowNow(false, false, true));
        // Showing over an open dialog is what steals a trade the player was in the middle of.
        assertFalse(CoopCampaignNotice.canShowNow(true, true, true));
        // Title screen, combat, a fresh load that has not reached the campaign yet.
        assertFalse(CoopCampaignNotice.canShowNow(true, false, false));
    }

    @Test
    void installingNothingIsSafeSoTheCallerNeedNotCheckFirst() {
        // Null sector stands in for "no game"; a blank message stands in for "the guard said nothing".
        CoopCampaignNotice.install(null, "something");
        CoopCampaignNotice.install(null, "");
        CoopCampaignNotice.install(null, null);
    }

    @Test
    void aNoticeIsDoneOnlyAfterItHasHadItsSay() {
        CoopCampaignNotice notice = new CoopCampaignNotice("wrong campaign", 0L);

        assertFalse(notice.isDone());
        assertTrue(notice.runWhilePaused());
        // The deferral frames burn without touching the engine at all.
        for (int i = 0; i <= CoopCampaignNotice.FRAMES_BEFORE_FIRST_SHOW; i++) {
            notice.advance(0.1f);
        }
        assertFalse(notice.isDone());
        assertTrue(notice.message().contains("wrong campaign"));
    }

    @Test
    void aNoticeThatWasNeverShownRetiresRatherThanAmbushingThePlayerLater() {
        CoopCampaignNotice notice = new CoopCampaignNotice("wrong campaign",
                System.currentTimeMillis() - CoopCampaignNotice.GIVE_UP_MILLIS - 1L);

        for (int i = 0; i <= CoopCampaignNotice.FRAMES_BEFORE_FIRST_SHOW + 1; i++) {
            notice.advance(0.1f);
        }
        assertTrue(notice.isDone());
    }
}
