package coop.ui;

import coop.config.CoopOptionsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine-free half of the options page, on the same line the other two intel tests draw: the
 * reader that decides where a value came from, the confirm-dialog geometry, and the once-per-process
 * warning guard. Everything that needs a live {@code TooltipMakerAPI} is checked by eye in the smoke
 * pass, and what it would render is covered by {@link CoopOptionsViewTest}.
 */
class CoopOptionsPageTest {

    private final CoopOptionsPage page = new CoopOptionsPage();

    @AfterEach
    void clearProperties() {
        System.clearProperty(CoopOptionsRegistry.HUD_CORNER);
        System.clearProperty(CoopOptionsRegistry.PLAYER_NAME);
        CoopOptionsPage.ensureRegistered(null);
    }

    // ---- review item 3: an explicitly empty -D is still the command line deciding -----------------

    @Test
    void anExplicitlyEmptyPropertyIsReadAsTheCommandLineNotAsAbsent() {
        CoopOptionsPage.LiveReader reader = new CoopOptionsPage.LiveReader();
        assertFalse(reader.commandLine(CoopOptionsRegistry.HUD_CORNER),
                "no -D at all, so the file layers below decide");

        System.setProperty(CoopOptionsRegistry.HUD_CORNER, "");

        assertTrue(new CoopOptionsPage.LiveReader().commandLine(CoopOptionsRegistry.HUD_CORNER),
                "-Dcoop.hudCorner= wins over every file layer, so the row is read-only; tagging it"
                        + " (default) drew a button whose press the next read discarded");
    }

    @Test
    void anOrdinaryPropertyIsStillTheCommandLine() {
        System.setProperty(CoopOptionsRegistry.PLAYER_NAME, "Ayo");

        assertTrue(new CoopOptionsPage.LiveReader().commandLine(CoopOptionsRegistry.PLAYER_NAME));
    }

    // ---- review item 7 ----------------------------------------------------------------------------

    @Test
    void theConfirmPromptIsWideEnoughForTheLongestOne() {
        // BaseIntelPlugin ships 550, which wraps the three-paragraph pauseOnGuestScreens prompt into
        // a column tall enough to crowd the dialog.
        assertEquals(650f, page.getConfirmationPromptWidth(CoopOptionsPage.BUTTON_RESET));
    }

    @Test
    void registeringThePageAgainReArmsTheOnceOnlyRenderWarning() {
        CoopOptionsPage.logRenderFailureOnce(new IllegalStateException("boom"));
        assertTrue(CoopOptionsPage.renderFailureLogged());

        // Runs on every campaign load. The guard exists to stop one broken render spamming the log
        // within a session, not to hide a different failure in the campaign after it.
        CoopOptionsPage.ensureRegistered(null);

        assertFalse(CoopOptionsPage.renderFailureLogged());
    }

    // ---- Phase 32 addition B: the "Send credits" block -------------------------------------------

    @Test
    void theSendCreditsRowShowsThePendingAmountAndIsDeadWithoutASession() {
        coop.campaign.CoopCreditTransfer.uninstall();
        coop.campaign.CoopCreditTransfer.stepPendingAmount(25_000);

        CoopOptionsPage.CreditRow row = CoopOptionsPage.liveCreditRow();

        assertEquals("25,000", row.amountText(), "the amount Send would move must be on the page");
        assertFalse(row.sendEnabled(), "no session, nobody to send to");
        assertFalse(row.canStep());
        assertTrue(row.note().contains("No co-op session"));

        coop.campaign.CoopCreditTransfer.uninstall();
    }

    @Test
    void theSendButtonIsLiveOnlyForAnAmountTheWalletCoversInALiveSession() {
        assertFalse(CoopOptionsPage.creditRow(true, 0, 100_000L).sendEnabled(),
                "nothing pending is nothing to send");
        assertTrue(CoopOptionsPage.creditRow(true, 0, 100_000L).canStep());

        assertFalse(CoopOptionsPage.creditRow(true, 100_001, 100_000L).sendEnabled(),
                "the button must not promise what send() would refuse");
        assertEquals("You do not have that many credits.",
                CoopOptionsPage.creditRow(true, 100_001, 100_000L).note());

        CoopOptionsPage.CreditRow ready = CoopOptionsPage.creditRow(true, 100_000, 100_000L);
        assertTrue(ready.sendEnabled());
        assertEquals("", ready.note());
        assertEquals("100,000", ready.walletText());
    }

    @Test
    void anUnreadableWalletSaysSoAndDisablesSend() {
        // Credit red-team P2-4: this used to leave Send enabled on the reasoning that the real cover
        // check is in send(). It is - and it answers "not enough credits, 0 available" on a page that
        // shows no balance at all, so the button always failed with a line contradicting the page.
        CoopOptionsPage.CreditRow row = CoopOptionsPage.creditRow(true, 5_000, -1L);

        assertFalse(row.sendEnabled());
        assertEquals("", row.walletText(), "no wallet line rather than a fake zero");
        assertEquals("Your wallet could not be read; credits cannot be sent right now.", row.note());
        assertTrue(row.canStep(), "the amount buttons still work; it is Send that cannot promise");
    }

    @Test
    void clearAmountIsDrawnWheneverSomethingIsPendingEvenWithNoSession() {
        // Credit red-team P2-5: "Clear amount" hung off canStep, which is false without a session, so
        // an amount stepped up before the link died could not be put away at all.
        assertTrue(CoopOptionsPage.creditRow(false, 500_000, 900_000L).canClear());
        assertFalse(CoopOptionsPage.creditRow(false, 500_000, 900_000L).canStep());

        assertFalse(CoopOptionsPage.creditRow(false, 0, 900_000L).canClear(),
                "nothing pending, nothing to clear");
        assertTrue(CoopOptionsPage.creditRow(true, 5_000, 900_000L).canClear());
    }

    @Test
    void theSendConfirmationNamesTheAmountAndSaysWhatCanAndCannotBeUndone() {
        coop.campaign.CoopCreditTransfer.uninstall();
        coop.campaign.CoopCreditTransfer.stepPendingAmount(7_500);

        String prompt = CoopOptionsPage.sendCreditsPrompt();

        assertTrue(prompt.contains("7,500"), prompt);
        assertTrue(prompt.contains("they come back to you"),
                "the refund path is the honest half of the promise (credit red-team P1-4): " + prompt);
        assertTrue(prompt.contains("Once they arrive there is no way to take them back"), prompt);
        assertTrue(page.doesButtonHaveConfirmDialog(CoopOptionsPage.BUTTON_SEND_CREDITS));

        coop.campaign.CoopCreditTransfer.uninstall();
    }

    // ---- P3-1: button routing ---------------------------------------------------------------------

    @Test
    void theClearButtonAndTheStepButtonsRouteToThePendingAmount() {
        coop.campaign.CoopCreditTransfer.uninstall();

        page.buttonPressConfirmed(new CoopOptionsPage.CreditStep(10_000), null);
        page.buttonPressConfirmed(new CoopOptionsPage.CreditStep(1_000), null);
        assertEquals(11_000, coop.campaign.CoopCreditTransfer.pendingAmount());

        page.buttonPressConfirmed(new CoopOptionsPage.CreditStep(-1_000), null);
        assertEquals(10_000, coop.campaign.CoopCreditTransfer.pendingAmount());

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_CLEAR_CREDITS, null);
        assertEquals(0, coop.campaign.CoopCreditTransfer.pendingAmount());
    }

    @Test
    void aSuccessfulSendClearsThePendingAmountSoASecondPressCannotRepeatTheGift() {
        coop.testing.FakeCreditEngine engine = new coop.testing.FakeCreditEngine(100_000L);
        RecordingLink link = new RecordingLink();
        coop.campaign.CoopCreditTransfer.install(new coop.campaign.CoopCreditTransfer(engine, link));
        coop.campaign.CoopCreditTransfer.stepPendingAmount(25_000);

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);

        assertEquals(1, link.sent, "one press, one grant");
        assertEquals(75_000L, engine.credits);
        assertEquals(0, coop.campaign.CoopCreditTransfer.pendingAmount());

        // The second press has nothing pending, so it is a BAD_AMOUNT refusal rather than a re-gift.
        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);
        assertEquals(1, link.sent);
        assertEquals(75_000L, engine.credits);

        coop.campaign.CoopCreditTransfer.uninstall();
    }

    @Test
    void aRefusedSendLeavesThePendingAmountWhereItWasSoThePlayerCanRetry() {
        coop.testing.FakeCreditEngine engine = new coop.testing.FakeCreditEngine(1_000L);
        RecordingLink link = new RecordingLink();
        coop.campaign.CoopCreditTransfer.install(new coop.campaign.CoopCreditTransfer(engine, link));
        coop.campaign.CoopCreditTransfer.stepPendingAmount(25_000);

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);

        assertEquals(0, link.sent);
        assertEquals(25_000, coop.campaign.CoopCreditTransfer.pendingAmount());

        coop.campaign.CoopCreditTransfer.uninstall();
    }

    @Test
    void sendingWithNoTransferInstalledDoesNothingAtAllRatherThanThrowing() {
        coop.campaign.CoopCreditTransfer.uninstall();
        coop.campaign.CoopCreditTransfer.stepPendingAmount(5_000);

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);

        assertEquals(5_000, coop.campaign.CoopCreditTransfer.pendingAmount(),
                "no session to send into, so the amount stays where the player put it");
        coop.campaign.CoopCreditTransfer.uninstall();
    }

    /** A link that records rather than sends; the page tests only care that send() reached it. */
    private static final class RecordingLink implements coop.campaign.CoopCreditTransfer.Link {
        private int sent;

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public String mintLedgerId() {
            return "session-a-player-a-" + (sent + 1);
        }

        @Override
        public void sendGrant(String ledgerId, int amount, String reason) {
            sent++;
        }
    }

    @Test
    void isQuietAndPermanentLikeItsSiblings() {
        assertFalse(page.isEnded());
        assertFalse(page.isEnding());
        assertFalse(page.shouldRemoveIntel());
        assertFalse(page.autoAddCampaignMessage());
        assertFalse(page.hasImportantButton());
        assertTrue(page.hasLargeDescription());
        assertFalse(page.hasSmallDescription());
        assertEquals(CoopOptionsPage.NAME, page.getName());
    }
}
