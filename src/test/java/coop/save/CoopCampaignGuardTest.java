package coop.save;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wrong-campaign decision, branch by branch.
 *
 * <p>Two properties are asserted everywhere rather than in one place: the guard never produces a
 * notice it has no evidence for (that is what makes it safe to leave on), and every notice it does
 * produce names a concrete next action. A dialog that says only "wrong campaign" is the failure mode
 * this exists to avoid.
 */
class CoopCampaignGuardTest {

    private static CoopSaveIndex.Row row(String campaignId) {
        return new CoopSaveIndex.Row(campaignId, "save_Kaz_9f2", "Kaz Alba", 12, 5_000L,
                "Cycle 206, Kerenth 12", 1_700_000_000_000L, Boolean.TRUE, "GUEST", "MN-42",
                "normal", "mixed");
    }

    private static final List<CoopSaveIndex.Row> INDEX = List.of(row("camp-invited"));

    // ---- load ----------------------------------------------------------------------------------

    @Test
    void nothingIsSaidWhenTheLauncherNamedNoCampaign() {
        assertTrue(CoopCampaignGuard.onLoad("", "camp-other", INDEX).silent());
        assertTrue(CoopCampaignGuard.onLoad(null, "camp-other", INDEX).silent());
    }

    @Test
    void nothingIsSaidWhenTheLoadedSaveIsTheInvitedOne() {
        assertTrue(CoopCampaignGuard.onLoad("camp-invited", "camp-invited", INDEX).silent());
        assertTrue(CoopCampaignGuard.onLoad(" camp-invited ", "camp-invited", INDEX).silent());
    }

    @Test
    void nothingIsSaidWhenTheLoadedSaveHasNeverBeenSeedLocked() {
        // No campaign id at all is an ordinary solo save, or a co-op save from before the first
        // connect. The seed lock handles it on connect; guessing here would warn on every solo load.
        assertTrue(CoopCampaignGuard.onLoad("camp-invited", "", INDEX).silent());
        assertTrue(CoopCampaignGuard.onLoad("camp-invited", null, INDEX).silent());
    }

    @Test
    void aDifferentCampaignNamesTheSaveToLoadWhenTheIndexKnowsOne() {
        CoopCampaignGuard.Notice notice =
                CoopCampaignGuard.onLoad("camp-invited", "camp-other", INDEX);

        assertEquals(CoopCampaignGuard.Kind.WRONG_CAMPAIGN, notice.kind());
        String message = notice.message();
        assertTrue(message.contains("not the campaign the co-op invite is for"), message);
        assertTrue(message.contains("Kaz Alba"), message);
        assertTrue(message.contains("level 12"), message);
        assertTrue(message.contains("Cycle 206, Kerenth 12"), message);
        assertTrue(message.contains("save_Kaz_9f2"), message);
        // Warn, then proceed: the text has to say the player may carry on.
        assertTrue(message.contains("keep playing"), message);
        assertAscii(message);
    }

    @Test
    void aDifferentCampaignWithNoKnownSaveSaysToStartANewGame() {
        CoopCampaignGuard.Notice notice =
                CoopCampaignGuard.onLoad("camp-invited", "camp-other", List.of());

        assertEquals(CoopCampaignGuard.Kind.WRONG_CAMPAIGN_NO_SAVE, notice.kind());
        assertTrue(notice.message().contains("no co-op save for that campaign on this machine"),
                notice.message());
        assertTrue(notice.message().contains("New Game"), notice.message());
        assertAscii(notice.message());
    }

    @Test
    void bothCampaignIdsAreShortenedButStillDistinguishable() {
        CoopCampaignGuard.Notice notice = CoopCampaignGuard.onLoad(
                "aaaaaaaa-1111-2222-3333-444444444444",
                "bbbbbbbb-1111-2222-3333-444444444444", INDEX);

        assertTrue(notice.message().contains("aaaaaaaa..."), notice.message());
        assertTrue(notice.message().contains("bbbbbbbb..."), notice.message());
        assertFalse(notice.message().contains("444444444444"), notice.message());
    }

    // ---- new game ------------------------------------------------------------------------------

    @Test
    void aNewGameIsWarnedAboutWhenTheInvitedCampaignAlreadyHasASaveHere() {
        CoopCampaignGuard.Notice notice =
                CoopCampaignGuard.onNewGame("camp-invited", INDEX, false);

        assertEquals(CoopCampaignGuard.Kind.NEW_GAME_ALREADY_IN_FLIGHT, notice.kind());
        assertTrue(notice.message().contains("already in flight"), notice.message());
        assertTrue(notice.message().contains("Kaz Alba"), notice.message());
        assertTrue(notice.message().contains("Start over inside the host's campaign"),
                notice.message());
        assertAscii(notice.message());
    }

    @Test
    void theAdoptConsentSilencesTheNewGameWarning() {
        // Adopting is the one gesture that overrides the seed lock, so the warning would be telling
        // the player their deliberate choice will not work, which is both wrong and infuriating.
        assertTrue(CoopCampaignGuard.onNewGame("camp-invited", INDEX, true).silent());
    }

    @Test
    void aNewGameForACampaignWithNoSaveHereIsExactlyWhatTheInviteAskedFor() {
        assertTrue(CoopCampaignGuard.onNewGame("camp-invited", List.of(), false).silent());
        assertTrue(CoopCampaignGuard.onNewGame("", INDEX, false).silent());
        assertTrue(CoopCampaignGuard.onNewGame(null, INDEX, false).silent());
    }

    // ---- the row description -------------------------------------------------------------------

    @Test
    void aRowWithNothingButACampaignIdStillDescribesItself() {
        String described = CoopCampaignGuard.describe(
                new CoopSaveIndex.Row("camp-a", "", "", 0, 0L, "", 0L, null, "", "", "", ""));

        assertEquals("\"unnamed character\"", described);
    }

    @Test
    void theWallClockStampIsSortableAndLocaleFree() {
        String stamp = CoopCampaignGuard.wallClock(0L);

        assertTrue(stamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), stamp);
    }

    /** The mod's bitmap font draws anything outside ASCII as a box; an em dash has shipped as "?". */
    private static void assertAscii(String message) {
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            assertTrue(c == '\n' || (c >= 0x20 && c < 0x7F),
                    "non-ASCII character at " + i + " in: " + message);
        }
    }
}
