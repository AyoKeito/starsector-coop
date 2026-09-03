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
