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
        coop.campaign.CoopCreditTransfer.uninstall();
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
    void theSendCreditsBlockIsDeadWithoutASession() {
        coop.campaign.CoopCreditTransfer.uninstall();

        CoopOptionsPage.CreditRow row = CoopOptionsPage.liveCreditRow();

        assertFalse(row.sendEnabled(), "no session, nobody to send to");
        assertTrue(row.note().contains("No co-op session"));
    }

    @Test
    void theSendButtonIsLiveWheneverThereIsASessionAndAWalletToReadFrom() {
        // The amount is no longer part of this decision: it lives in the field, and only a press can
        // read it. What the row still decides is whether sending is possible at all.
        CoopOptionsPage.CreditRow ready = CoopOptionsPage.creditRow(true, 100_000L);

        assertTrue(ready.sendEnabled());
        assertEquals("", ready.note());
        assertEquals("100,000", ready.walletText());
    }

    @Test
    void anUnreadableWalletSaysSoAndDisablesSend() {
        // Credit red-team P2-4: this used to leave Send enabled on the reasoning that the real cover
        // check is in send(). It is - and it answers "not enough credits, 0 available" on a page that
        // shows no balance at all, so the button always failed with a line contradicting the page.
        CoopOptionsPage.CreditRow row = CoopOptionsPage.creditRow(true, -1L);

        assertFalse(row.sendEnabled());
        assertEquals("", row.walletText(), "no wallet line rather than a fake zero");
        assertEquals("Your wallet could not be read; credits cannot be sent right now.", row.note());
    }

    // ---- the typed amount -------------------------------------------------------------------------

    @Test
    void aTypedAmountTakesPlainDigitsAndTheGroupingThePageItselfPrints() {
        // The wallet line above the field says "You have 250,000 credits"; typing that back has to
        // work, or the page teaches a format it then refuses.
        assertEquals(25_000, CoopOptionsPage.parseAmount("25000", 250_000L).amount());
        assertEquals(25_000, CoopOptionsPage.parseAmount("25,000", 250_000L).amount());
        assertEquals(25_000, CoopOptionsPage.parseAmount("25 000", 250_000L).amount());
        assertEquals(25_000, CoopOptionsPage.parseAmount("  25,000  ", 250_000L).amount());
        assertTrue(CoopOptionsPage.parseAmount("25,000", 250_000L).ok());
    }

    @Test
    void anEmptyOrZeroOrNegativeOrNonNumericAmountIsRefusedInWriting() {
        assertFalse(CoopOptionsPage.parseAmount("", 250_000L).ok());
        assertFalse(CoopOptionsPage.parseAmount("   ", 250_000L).ok());
        assertTrue(CoopOptionsPage.parseAmount("", 250_000L).refusal().contains("Type an amount"));

        assertFalse(CoopOptionsPage.parseAmount("0", 250_000L).ok());
        assertTrue(CoopOptionsPage.parseAmount("0", 250_000L).refusal().contains("above zero"));

        assertFalse(CoopOptionsPage.parseAmount("-500", 250_000L).ok());
        assertTrue(CoopOptionsPage.parseAmount("-500", 250_000L).refusal().contains("positive"));

        assertFalse(CoopOptionsPage.parseAmount("lots", 250_000L).ok());
        assertTrue(CoopOptionsPage.parseAmount("lots", 250_000L).refusal().contains("not an amount"));

        // Stripping the point would turn 1.5 into 15 and send fifteen credits without a word.
        assertFalse(CoopOptionsPage.parseAmount("1.5", 250_000L).ok());
        assertTrue(CoopOptionsPage.parseAmount("1.5", 250_000L).refusal().contains("whole numbers"));
    }

    @Test
    void anAmountOverTheCapOrOverTheBalanceIsRefusedWithTheNumberThatWouldNotBe() {
        String overCap = CoopOptionsPage.parseAmount("2,000,000,000", Long.MAX_VALUE).refusal();
        assertTrue(overCap.contains(coop.campaign.CoopCreditTransfer.format(
                coop.campaign.CoopCreditTransfer.MAX_AMOUNT)), overCap);

        // 19+ digits overflow a long; the useful answer is still the ceiling, not a stack trace.
        assertFalse(CoopOptionsPage.parseAmount("99999999999999999999", Long.MAX_VALUE).ok());

        String overBalance = CoopOptionsPage.parseAmount("100,001", 100_000L).refusal();
        assertTrue(overBalance.contains("only 100,000 credits"), overBalance);

        assertTrue(CoopOptionsPage.parseAmount("100,000", 100_000L).ok(), "the whole wallet is fine");
        assertTrue(CoopOptionsPage.parseAmount("100,001", -1L).ok(),
                "an unreadable wallet cannot cover-check; send() still will");
    }

    @Test
    void theSendConfirmationNamesTheTypedAmountAndSaysWhatCanAndCannotBeUndone() {
        coop.campaign.CoopCreditTransfer.uninstall();

        String prompt = CoopOptionsPage.sendCreditsPrompt(7_500);

        assertTrue(prompt.contains("7,500"), prompt);
        assertTrue(prompt.contains("they come back to you"),
                "the refund path is the honest half of the promise (credit red-team P1-4): " + prompt);
        assertTrue(prompt.contains("Once they arrive there is no way to take them back"), prompt);
    }

    @Test
    void aGoodAmountGetsTheConfirmStepAndABadOneGoesStraightToTheReasonUnderTheField() {
        installTransfer(100_000L);
        page.amountText = () -> "25,000";
        assertTrue(page.doesButtonHaveConfirmDialog(CoopOptionsPage.BUTTON_SEND_CREDITS),
                "money, and irreversible");

        page.amountText = () -> "banana";
        assertFalse(page.doesButtonHaveConfirmDialog(CoopOptionsPage.BUTTON_SEND_CREDITS),
                "there is nothing to confirm about a typo");

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);
        assertTrue(page.lastCreditRefusal().contains("not an amount"),
                "a refused press has to say why: " + page.lastCreditRefusal());
    }

    @Test
    void aSendTheWalletCannotCoverSaysSoOnThePageRatherThanDoingNothing() {
        installTransfer(1_000L);
        page.amountText = () -> "25,000";

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);

        assertEquals("You have only 1,000 credits.", page.lastCreditRefusal());
    }

    // ---- P3-1: button routing ---------------------------------------------------------------------

    @Test
    void aSuccessfulSendEmptiesTheFieldSoASecondPressCannotRepeatTheGift() {
        coop.testing.FakeCreditEngine engine = new coop.testing.FakeCreditEngine(100_000L);
        RecordingLink link = new RecordingLink();
        coop.campaign.CoopCreditTransfer.install(new coop.campaign.CoopCreditTransfer(engine, link));
        page.amountText = () -> "25,000";

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);

        assertEquals(1, link.sent, "one press, one grant");
        assertEquals(75_000L, engine.credits);
        assertEquals("", page.amountText.get(), "the field is emptied, not left showing the gift");
        assertEquals("", page.lastCreditRefusal());

        // The second press has an empty field, so it is refused rather than re-gifting.
        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);
        assertEquals(1, link.sent);
        assertEquals(75_000L, engine.credits);
        assertTrue(page.lastCreditRefusal().contains("Type an amount"));

        coop.campaign.CoopCreditTransfer.uninstall();
    }

    @Test
    void sendingWithNoTransferInstalledSaysSoRatherThanThrowing() {
        coop.campaign.CoopCreditTransfer.uninstall();
        page.amountText = () -> "5,000";

        page.buttonPressConfirmed(CoopOptionsPage.BUTTON_SEND_CREDITS, null);

        assertTrue(page.lastCreditRefusal().contains("No co-op session"), page.lastCreditRefusal());
        assertEquals("5,000", page.amountText.get(),
                "no session to send into, so what the player typed stays put");
    }

    /** A live transfer with a wallet of {@code credits}, for the press-routing tests. */
    private static void installTransfer(long credits) {
        coop.campaign.CoopCreditTransfer.install(new coop.campaign.CoopCreditTransfer(
                new coop.testing.FakeCreditEngine(credits), new RecordingLink()));
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
