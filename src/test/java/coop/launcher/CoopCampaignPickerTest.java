package coop.launcher;

import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drop-down the host picks a campaign from and the one line under each card that says which
 * save to load. Pure, so every sentence a player can be shown is pinned here rather than only in a
 * window somebody has to open.
 */
class CoopCampaignPickerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** 2026-05-28 18:16:01.129 UTC, the timestamp out of a real {@code descriptor.xml}. */
    private static final long SAVED = 1779992161129L;

    private static CoopSaveIndexReader.Save save(String campaignId, String folder, String character,
                                                 int level, long savedAt, String role) {
        return save(campaignId, folder, character, level, savedAt, role, "MN-42");
    }

    private static CoopSaveIndexReader.Save save(String campaignId, String folder, String character,
                                                 int level, long savedAt, String role, String seed) {
        return save(campaignId, folder, character, level, savedAt, role, seed, "small", "young");
    }

    private static CoopSaveIndexReader.Save save(String campaignId, String folder, String character,
                                                 int level, long savedAt, String role, String seed,
                                                 String sectorSize, String sectorAge) {
        return new CoopSaveIndexReader.Save(campaignId, folder, character, level, "Cycle 206",
                500L, savedAt, Boolean.FALSE, role, seed, sectorSize, sectorAge);
    }

    private static CoopCampaignPicker.Entry entry(String campaignId, String seed) {
        return entry(campaignId, seed, "small", "young");
    }

    private static CoopCampaignPicker.Entry entry(String campaignId, String seed, String sectorSize,
                                                  String sectorAge) {
        return new CoopCampaignPicker.Entry(campaignId, "Kaz", "save_a", seed, sectorSize,
                sectorAge);
    }

    private static CoopSaveIndexReader.Index ok(CoopSaveIndexReader.Save... saves) {
        return new CoopSaveIndexReader.Index(CoopSaveIndexReader.Status.OK, "", List.of(saves));
    }

    // ---- the drop-down --------------------------------------------------------------------------

    @Test
    void newCampaignIsAlwaysFirstAndQuotesTheSeed() {
        List<CoopCampaignPicker.Entry> entries =
                CoopCampaignPicker.entries("MN-42", CoopSaveIndexReader.Index.absent(), UTC);

        assertEquals(1, entries.size());
        assertEquals("New campaign (seed MN-42)", entries.get(0).label());
        assertTrue(entries.get(0).newCampaign());
        assertEquals("", entries.get(0).campaignId());
    }

    @Test
    void aSeedlessHostStillGetsANewCampaignEntry() {
        assertEquals("New campaign", CoopCampaignPicker.newCampaignEntry("  ").label());
    }

    @Test
    void oneEntryPerCampaignNewestFirstAfterTheNewOne() {
        List<CoopCampaignPicker.Entry> entries = CoopCampaignPicker.entries("MN-42",
                ok(save("cA", "save_a2", "Kaz Alba", 12, SAVED, "HOST"),
                        save("cB", "save_b", "Vela", 4, SAVED - 86_400_000L, "NONE"),
                        save("cA", "save_a1", "Kaz Alba", 3, 1L, "HOST")), UTC);

        assertEquals(3, entries.size());
        assertTrue(entries.get(0).newCampaign());
        assertEquals("cA", entries.get(1).campaignId());
        assertEquals("save_a2", entries.get(1).folderName());
        assertEquals("Kaz Alba, level 12, Cycle 206, saved 2026-05-28 18:16", entries.get(1).label());
        assertEquals("cB", entries.get(2).campaignId());
    }

    @Test
    void aCampaignThisPlayerOnlyEverGuestedInIsNotOfferedForHosting() {
        List<CoopCampaignPicker.Entry> entries = CoopCampaignPicker.entries("MN-42",
                ok(save("cG", "save_g", "Rho", 2, SAVED, "GUEST")), UTC);

        assertEquals(1, entries.size());
        assertTrue(entries.get(0).newCampaign());
    }

    @Test
    void anUnreadableIndexLeavesTheDropDownWithNothingButNewCampaign() {
        CoopSaveIndexReader.Index broken = new CoopSaveIndexReader.Index(
                CoopSaveIndexReader.Status.UNREADABLE, "it is half a file", List.of());

        assertEquals(1, CoopCampaignPicker.entries("MN-42", broken, UTC).size());
        assertEquals(1, CoopCampaignPicker.entries("MN-42", null, UTC).size());
    }

    // ---- the seed a pick carries ----------------------------------------------------------------

    @Test
    void everyEntryCarriesTheSeedTheSectorCameFrom() {
        List<CoopCampaignPicker.Entry> entries = CoopCampaignPicker.entries("MN-draft",
                ok(save("cA", "save_a", "Kaz", 12, SAVED, "HOST", "MN-777")), UTC);

        assertEquals("MN-draft", entries.get(0).seedString());
        assertEquals("MN-777", entries.get(1).seedString());
    }

    /** The bug: the invite kept quoting the draft seed while a saved campaign was selected. */
    @Test
    void pickingASavedCampaignPutsThatCampaignsSeedInTheBox() {
        CoopCampaignPicker.Entry saved = entry("cA", "MN-777");

        assertEquals("MN-777", CoopCampaignPicker.seedAfterPick(saved, "MN-draft", "MN-draft"));
    }

    @Test
    void goingBackToNewRestoresTheDraftSeed() {
        CoopCampaignPicker.Entry brandNew = CoopCampaignPicker.newCampaignEntry("MN-777");

        assertEquals("MN-draft", CoopCampaignPicker.seedAfterPick(brandNew, "MN-draft", "MN-777"));
        assertEquals("MN-draft", CoopCampaignPicker.seedAfterPick(null, "MN-draft", "MN-777"));
    }

    /** A row written before the mod recorded seeds. Blanking the box would be worse than leaving it. */
    @Test
    void aSavedCampaignWithNoRecordedSeedLeavesTheBoxAlone() {
        CoopCampaignPicker.Entry saved = entry("cA", "");

        assertEquals("MN-draft", CoopCampaignPicker.seedAfterPick(saved, "MN-draft", "MN-draft"));
    }

    @Test
    void aHostWithNoDraftYetKeepsWhateverIsInTheBox() {
        assertEquals("MN-777",
                CoopCampaignPicker.seedAfterPick(CoopCampaignPicker.newCampaignEntry(""), "  ",
                        "MN-777"));
    }

    // ---- what the pick turns on and off ---------------------------------------------------------

    @Test
    void theSeedAndWorldSettingsAreLiveOnlyForANewCampaign() {
        CoopCampaignPicker.Entry brandNew = CoopCampaignPicker.newCampaignEntry("MN-42");
        CoopCampaignPicker.Entry existing = entry("cA", "MN-42");

        assertTrue(CoopCampaignPicker.worldControlsEnabled(brandNew));
        assertTrue(CoopCampaignPicker.worldControlsEnabled(null));
        assertFalse(CoopCampaignPicker.worldControlsEnabled(existing));
    }

    @Test
    void theFolderLineNamesTheSaveAndIsBlankForANewCampaign() {
        assertEquals("folder save_a",
                CoopCampaignPicker.folderLine(entry("cA", "MN-42")));
        assertEquals("", CoopCampaignPicker.folderLine(CoopCampaignPicker.newCampaignEntry("MN-1")));
        assertEquals("", CoopCampaignPicker.folderLine(null));
    }

    @Test
    void aPickIsKeptAcrossARefreshAndFallsBackToNewWhenItsLastSaveIsGone() {
        List<CoopCampaignPicker.Entry> entries = CoopCampaignPicker.entries("MN-42",
                ok(save("cA", "save_a", "Kaz", 12, SAVED, "HOST")), UTC);

        assertEquals("cA", CoopCampaignPicker.select(entries, "cA").campaignId());
        assertTrue(CoopCampaignPicker.select(entries, "cGone").newCampaign());
        assertTrue(CoopCampaignPicker.select(List.of(), "cA").newCampaign());
    }

    // ---- the hint line --------------------------------------------------------------------------

    @Test
    void aNewCampaignSaysToStartANewGame() {
        assertEquals("Start a New Game with the seed above.",
                CoopCampaignPicker.hint("", ok(), UTC));
    }

    @Test
    void aCampaignWithASaveHereIsNamedDownToTheFolder() {
        String hint = CoopCampaignPicker.hint("cA",
                ok(save("cA", "save_ds_140", "Kaz Alba", 12, SAVED, "HOST")), UTC);

        assertEquals("Load the save \"Kaz Alba\", level 12, saved 2026-05-28 18:16"
                + " (folder save_ds_140).", hint);
    }

    /**
     * The seed box cannot be put right for a save row from before the mod recorded seeds, so the
     * hint line has to say the seed on screen is not this campaign's.
     */
    @Test
    void aSaveWithNoRecordedSeedSaysTheSeedBoxIsNotItsOwn() {
        String hint = CoopCampaignPicker.hint("cA",
                ok(save("cA", "save_ds_140", "Kaz Alba", 12, SAVED, "HOST", "")), UTC);

        assertTrue(hint.startsWith("Load the save \"Kaz Alba\""), hint);
        assertTrue(hint.contains("does not record its seed"), hint);
    }

    @Test
    void theNewestSaveOfTheCampaignIsTheOneNamed() {
        String hint = CoopCampaignPicker.hint("cA",
                ok(save("cA", "save_new", "Kaz Alba", 12, SAVED, "HOST"),
                        save("cA", "save_old", "Kaz Alba", 3, 1L, "HOST")), UTC);

        assertTrue(hint.contains("save_new"), hint);
        assertFalse(hint.contains("save_old"), hint);
    }

    @Test
    void aCampaignWithNoSaveOnThisMachineSaysSoAndPointsAtTheSeed() {
        assertEquals("No co-op save for this campaign on this machine: start a New Game with the"
                        + " seed above.",
                CoopCampaignPicker.hint("cA", ok(save("cB", "save_b", "Vela", 4, SAVED, "HOST")),
                        UTC));
    }

    @Test
    void aGuestSaveOfTheRightCampaignStillCounts() {
        // Hosting it is another matter, but a guest rejoining has to be told to load exactly this.
        String hint = CoopCampaignPicker.hint("cG",
                ok(save("cG", "save_g", "Rho", 2, SAVED, "GUEST")), UTC);

        assertTrue(hint.startsWith("Load the save \"Rho\""), hint);
    }

    @Test
    void anInstallWithNoSaveListYetIsToldSoCalmly() {
        String hint = CoopCampaignPicker.hint("cA", CoopSaveIndexReader.Index.absent(), UTC);

        assertEquals("No co-op saves have been recorded on this machine yet: start a New Game with"
                + " the seed above.", hint);
    }

    @Test
    void anUnreadableOrNewerSaveListSaysSoAndSaysLaunchingStillWorks() {
        String unreadable = CoopCampaignPicker.hint("cA", new CoopSaveIndexReader.Index(
                CoopSaveIndexReader.Status.UNREADABLE, "it is half a file", List.of()), UTC);
        String tooNew = CoopCampaignPicker.hint("cA", new CoopSaveIndexReader.Index(
                CoopSaveIndexReader.Status.TOO_NEW, "it is version 2", List.of()), UTC);

        assertTrue(unreadable.contains(CoopSaveIndexReader.INDEX_DISPLAY_PATH), unreadable);
        assertTrue(unreadable.contains("it is half a file"), unreadable);
        assertTrue(unreadable.endsWith("Launching still works."), unreadable);
        assertTrue(tooNew.contains("newer version of the mod"), tooNew);
        assertTrue(tooNew.endsWith("Launching still works."), tooNew);
    }

    @Test
    void noIndexAtAllStillProducesASentence() {
        assertNotNull(CoopCampaignPicker.hint("cA", null, UTC));
    }

    // ---- what may travel in an invite -----------------------------------------------------------

    @Test
    void aUuidCampaignIdIsFineAndABlankOneIsNotAProblem() {
        assertNull(CoopCampaignPicker.campaignIdProblem(
                "6f1a3c2e-9b44-4f2a-8d21-0c7e5a9b1f30"));
        assertNull(CoopCampaignPicker.campaignIdProblem(""));
        assertNull(CoopCampaignPicker.campaignIdProblem(null));
    }

    @Test
    void aCampaignIdWithSomethingThatIsNotOneInItIsRefused() {
        assertNotNull(CoopCampaignPicker.campaignIdProblem("has a space"));
        assertNotNull(CoopCampaignPicker.campaignIdProblem("a&b=c"));
        assertNotNull(CoopCampaignPicker.campaignIdProblem("../../etc"));
        assertNotNull(CoopCampaignPicker.campaignIdProblem("x".repeat(129)));
    }

    // ---- the world settings follow the picker too --------------------------------------------------

    @Test
    void everyEntryCarriesTheSizeAndAgeItsSectorWasGeneratedAt() {
        List<CoopCampaignPicker.Entry> entries = CoopCampaignPicker.entries("MN-draft",
                ok(save("cA", "save_a", "Kaz", 12, SAVED, "HOST", "MN-777", "small", "old")), UTC);

        assertEquals("", entries.get(0).sectorSize(), "a new campaign has no recorded settings");
        assertEquals("", entries.get(0).sectorAge());
        assertEquals("small", entries.get(1).sectorSize());
        assertEquals("old", entries.get(1).sectorAge());
    }

    @Test
    void pickingASavedCampaignPutsItsOwnSizeAndAgeInTheDropDowns() {
        CoopCampaignPicker.Entry saved = entry("cA", "MN-777", "small", "old");

        assertEquals("small", CoopCampaignPicker.sectorSizeAfterPick(saved, "normal", "normal"));
        assertEquals("old", CoopCampaignPicker.sectorAgeAfterPick(saved, "mixed", "mixed"));
    }

    @Test
    void goingBackToNewRestoresTheDraftedSizeAndAge() {
        CoopCampaignPicker.Entry brandNew = CoopCampaignPicker.newCampaignEntry("MN-777");

        assertEquals("normal", CoopCampaignPicker.sectorSizeAfterPick(brandNew, "normal", "small"));
        assertEquals("mixed", CoopCampaignPicker.sectorAgeAfterPick(brandNew, "mixed", "old"));
        assertEquals("normal", CoopCampaignPicker.sectorSizeAfterPick(null, "normal", "small"));
        assertEquals("mixed", CoopCampaignPicker.sectorAgeAfterPick(null, "mixed", "old"));
    }

    /** A row written before the mod recorded these. The drop-downs stay where the host left them. */
    @Test
    void aSavedCampaignWithNoRecordedSizeOrAgeLeavesTheDropDownsAlone() {
        CoopCampaignPicker.Entry saved = entry("cA", "MN-777", "", "");

        assertEquals("normal", CoopCampaignPicker.sectorSizeAfterPick(saved, "small", "normal"));
        assertEquals("mixed", CoopCampaignPicker.sectorAgeAfterPick(saved, "young", "mixed"));
    }

    @Test
    void aHostWithNoDraftedSizeYetKeepsWhateverTheDropDownShows() {
        CoopCampaignPicker.Entry brandNew = CoopCampaignPicker.newCampaignEntry("");

        assertEquals("small", CoopCampaignPicker.sectorSizeAfterPick(brandNew, "  ", "small"));
        assertEquals("old", CoopCampaignPicker.sectorAgeAfterPick(brandNew, "", "old"));
    }

    // ---- what the hint line says about a row that records less -------------------------------------

    @Test
    void aSaveWithNoRecordedSizeOrAgeSaysThoseTwoBoxesAreStillTheHostsDraft() {
        String hint = CoopCampaignPicker.hint("cA",
                ok(save("cA", "save_a", "Kaz", 12, SAVED, "HOST", "MN-42", "", "")), UTC);

        assertTrue(hint.contains("does not record the sector size and star age"), hint);
        assertTrue(hint.contains("the seed is this campaign's"), hint);
    }

    @Test
    void aSaveRecordingNeitherSeedNorWorldSettingsSaysSoInOneSentence() {
        String hint = CoopCampaignPicker.hint("cA",
                ok(save("cA", "save_a", "Kaz", 12, SAVED, "HOST", "", "", "")), UTC);

        assertTrue(hint.contains("records neither its seed nor the sector size and star age"), hint);
        assertFalse(hint.contains("does not record its seed"), "one sentence, not two: " + hint);
    }

    @Test
    void aSaveRecordingEverythingSaysNothingExtra() {
        String hint = CoopCampaignPicker.hint("cA",
                ok(save("cA", "save_a", "Kaz", 12, SAVED, "HOST", "MN-42", "small", "old")), UTC);

        assertTrue(hint.startsWith("Load the save"), hint);
        assertFalse(hint.contains("does not record"), hint);
        assertFalse(hint.contains("records neither"), hint);
    }
}
