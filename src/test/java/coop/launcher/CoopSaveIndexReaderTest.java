package coop.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher's read of the co-op save index, exercised against a fake {@code saves} tree under a
 * temporary folder - never a real install.
 *
 * <p>The cases that matter are the ones where the file and the disk disagree: a row naming a folder
 * the engine has pruned, a row with no folder name at all, and a descriptor that says something
 * different from the row that points at it.
 */
class CoopSaveIndexReaderTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @TempDir
    Path saves;

    @BeforeEach
    void createCommon() throws IOException {
        Files.createDirectories(saves.resolve("common"));
    }

    // ---- the file itself ------------------------------------------------------------------------

    @Test
    void noIndexFileIsAbsentAndNotAProblem() {
        CoopSaveIndexReader.Index index = CoopSaveIndexReader.read(saves);

        assertEquals(CoopSaveIndexReader.Status.ABSENT, index.status());
        assertTrue(index.saves().isEmpty());
    }

    @Test
    void aFileThatWillNotParseIsUnreadableAndSaysWhy() throws IOException {
        writeIndex("{this is not json");

        CoopSaveIndexReader.Index index = CoopSaveIndexReader.read(saves);

        assertEquals(CoopSaveIndexReader.Status.UNREADABLE, index.status());
        assertTrue(index.saves().isEmpty());
        assertTrue(index.problem().length() > 0, "an unreadable file has to say something");
    }

    /** The one rule the mod's commit message spells out: a newer format is not guessed at. */
    @Test
    void aNewerFormatVersionStopsTheReaderRatherThanBeingGuessedAt() throws IOException {
        writeIndex("{\"version\": 2, \"saves\": [" + row("c1", "save_a", "Kaz", 4) + "]}");

        CoopSaveIndexReader.Index index = CoopSaveIndexReader.read(saves);

        assertEquals(CoopSaveIndexReader.Status.TOO_NEW, index.status());
        assertTrue(index.saves().isEmpty());
        assertTrue(index.problem().contains("version 2"), index.problem());
    }

    @Test
    void aFileWithNoVersionFieldIsNotTheDocumentedFile() throws IOException {
        writeIndex("{\"saves\": []}");

        assertEquals(CoopSaveIndexReader.Status.UNREADABLE, CoopSaveIndexReader.read(saves).status());
    }

    @Test
    void aFileWithNoSavesListIsUnreadable() throws IOException {
        writeIndex("{\"version\": 1}");

        assertEquals(CoopSaveIndexReader.Status.UNREADABLE, CoopSaveIndexReader.read(saves).status());
    }

    // ---- the join to the folders ----------------------------------------------------------------

    /**
     * Autosaves are pruned to three by the engine, so a row can outlive its folder. Offering it
     * would send the player looking for a slot the game's own load screen does not show.
     */
    @Test
    void aRowWhoseFolderHasBeenPrunedIsDropped() throws IOException {
        folder("save_here", "Kaz", 12, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + row("c1", "save_gone", "Ghost", 3) + ","
                + row("c1", "save_here", "Kaz", 12) + "]}");

        CoopSaveIndexReader.Index index = CoopSaveIndexReader.read(saves);

        assertEquals(CoopSaveIndexReader.Status.OK, index.status());
        assertEquals(1, index.saves().size());
        assertEquals("save_here", index.saves().get(0).saveDirName());
    }

    @Test
    void theDescriptorWinsOverTheIndexRowForEverythingItKnows() throws IOException {
        folder("save_a", "Kaz Alba", 12, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + "{\"campaignId\": \"c1\", \"saveDirName\": \"save_a\","
                + " \"characterName\": \"stale name\", \"level\": 1,"
                + " \"gameDateTimestamp\": 500, \"gameDate\": \"Cycle 206, Kerenth 12\","
                + " \"savedAtMillis\": 1, \"role\": \"HOST\"}]}");

        CoopSaveIndexReader.Save save = CoopSaveIndexReader.read(saves).saves().get(0);

        assertEquals("Kaz Alba", save.characterName());
        assertEquals(12, save.level());
        // The in-game date has no descriptor equivalent, so the row still supplies it.
        assertEquals("Cycle 206, Kerenth 12", save.gameDate());
        assertEquals("2026-05-28 18:16", save.savedLocal(UTC));
    }

    @Test
    void theDropDownLineNamesTheCharacterTheLevelTheDateAndTheSaveTime() throws IOException {
        folder("save_a", "Kaz Alba", 12, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + "{\"campaignId\": \"c1\", \"saveDirName\": \"save_a\","
                + " \"characterName\": \"Kaz Alba\", \"level\": 12,"
                + " \"gameDateTimestamp\": 500, \"gameDate\": \"Cycle 206, Kerenth 12\","
                + " \"savedAtMillis\": 1, \"role\": \"HOST\"}]}");

        assertEquals("Kaz Alba, level 12, Cycle 206, Kerenth 12, saved 2026-05-28 18:16",
                CoopSaveIndexReader.read(saves).saves().get(0).label(UTC));
    }

    /** {@code saveDirName} is omitted when the engine getter was unreachable at save time. */
    @Test
    void aRowWithNoFolderNameIsMatchedByCharacterNameAndGameDate() throws IOException {
        folder("save_other", "Kaz Alba", 12, 999L, "2026-05-28 18:16:01.129 UTC");
        folder("save_right", "Kaz Alba", 12, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + "{\"campaignId\": \"c1\", \"characterName\": \"Kaz Alba\", \"level\": 12,"
                + " \"gameDateTimestamp\": 500, \"savedAtMillis\": 7, \"role\": \"HOST\"}]}");

        CoopSaveIndexReader.Index index = CoopSaveIndexReader.read(saves);

        assertEquals(1, index.saves().size());
        assertEquals("save_right", index.saves().get(0).saveDirName());
    }

    @Test
    void aRowWithNoFolderNameAndNothingToMatchIsDropped() throws IOException {
        folder("save_other", "Someone Else", 3, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + "{\"campaignId\": \"c1\", \"characterName\": \"Kaz Alba\", \"level\": 12,"
                + " \"gameDateTimestamp\": 500, \"savedAtMillis\": 7, \"role\": \"HOST\"}]}");

        assertTrue(CoopSaveIndexReader.read(saves).saves().isEmpty());
    }

    @Test
    void aRowWithNoCampaignIdIsSkippedBecauseNoInviteCouldEverNameIt() throws IOException {
        folder("save_a", "Kaz", 1, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + "{\"saveDirName\": \"save_a\", \"characterName\": \"Kaz\", \"level\": 1,"
                + " \"savedAtMillis\": 5, \"role\": \"NONE\"}]}");

        assertTrue(CoopSaveIndexReader.read(saves).saves().isEmpty());
    }

    // ---- ordering and per-campaign picking ------------------------------------------------------

    @Test
    void savesComeBackNewestFirstWhateverOrderTheFileHadThemIn() throws IOException {
        folder("save_old", "Kaz", 1, 1L, "2026-01-01 00:00:00.000 UTC");
        folder("save_new", "Kaz", 9, 2L, "2026-06-01 00:00:00.000 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + row("c1", "save_old", "Kaz", 1) + ","
                + row("c1", "save_new", "Kaz", 9) + "]}");

        List<CoopSaveIndexReader.Save> found = CoopSaveIndexReader.read(saves).saves();

        assertEquals("save_new", found.get(0).saveDirName());
        assertEquals("save_old", found.get(1).saveDirName());
        assertEquals("save_new", CoopSaveIndexReader.read(saves).newestFor("c1").saveDirName());
    }

    @Test
    void oneEntryPerCampaignAndAGuestOnlyCampaignIsNotOfferedForHosting() throws IOException {
        folder("save_a1", "Kaz", 1, 1L, "2026-01-01 00:00:00.000 UTC");
        folder("save_a2", "Kaz", 9, 2L, "2026-06-01 00:00:00.000 UTC");
        folder("save_b", "Vela", 4, 3L, "2026-03-01 00:00:00.000 UTC");
        folder("save_g", "Rho", 2, 4L, "2026-07-01 00:00:00.000 UTC");
        writeIndex("{\"version\": 1, \"saves\": ["
                + row("cA", "save_a2", "Kaz", 9) + ","
                + row("cG", "save_g", "Rho", 2, "GUEST") + ","
                + row("cB", "save_b", "Vela", 4) + ","
                + row("cA", "save_a1", "Kaz", 1) + "]}");

        List<CoopSaveIndexReader.Save> hostable =
                CoopSaveIndexReader.read(saves).newestPerHostCampaign();

        assertEquals(2, hostable.size());
        assertEquals("cA", hostable.get(0).campaignId());
        assertEquals("save_a2", hostable.get(0).saveDirName());
        assertEquals("cB", hostable.get(1).campaignId());
    }

    @Test
    void newestForAnUnknownCampaignIsNothing() throws IOException {
        folder("save_a", "Kaz", 1, 1L, "2026-01-01 00:00:00.000 UTC");
        writeIndex("{\"version\": 1, \"saves\": [" + row("c1", "save_a", "Kaz", 1) + "]}");

        assertNotNull(CoopSaveIndexReader.read(saves).newestFor("c1"));
        assertNull(CoopSaveIndexReader.read(saves).newestFor("c2"));
        assertNull(CoopSaveIndexReader.read(saves).newestFor(""));
    }

    // ---- descriptor.xml on its own --------------------------------------------------------------

    /**
     * The real file nests a whole mod list inside itself, complete with {@code z} attributes and
     * {@code ref} back-references. Only the root's own children may answer.
     */
    @Test
    void theDescriptorIsReadFromTheRootsOwnChildrenOnly() {
        CoopSaveIndexReader.Descriptor descriptor = CoopSaveIndexReader.parseDescriptor(
                "<?xml version=\"1.0\" ?>\n"
                        + "<SaveGameData z=\"1\">\n"
                        + "<characterName>Kaz Alba</characterName>\n"
                        + "<characterLevel>12</characterLevel>\n"
                        + "<gameDate z=\"2\"><secondsPerDay>10.0</secondsPerDay>"
                        + "<timestamp>-55661292720000</timestamp></gameDate>\n"
                        + "<saveDate z=\"3\">2026-05-28 18:16:01.129 UTC</saveDate>\n"
                        + "<enabledMods z=\"4\"><spec><characterName>a mod</characterName>"
                        + "<timestamp>1</timestamp></spec></enabledMods>\n"
                        + "<locDesc>Corvus Star System</locDesc>\n"
                        + "<autosave>true</autosave>\n"
                        + "</SaveGameData>");

        assertNotNull(descriptor);
        assertEquals("Kaz Alba", descriptor.characterName());
        assertEquals(12, descriptor.level());
        assertEquals(-55661292720000L, descriptor.gameDateTimestamp());
        assertEquals("Corvus Star System", descriptor.locDesc());
        assertEquals(Boolean.TRUE, descriptor.autosave());
    }

    @Test
    void wellFormedXmlThatIsNotASaveDescriptorIsRefused() {
        assertNull(CoopSaveIndexReader.parseDescriptor("<other><hello>yes</hello></other>"));
        assertNull(CoopSaveIndexReader.parseDescriptor("not xml at all"));
        assertNull(CoopSaveIndexReader.parseDescriptor(""));
    }

    @Test
    void theSaveDateIsReadWithItsZoneAndAnUnparseableOneIsJustZero() {
        assertEquals(1779992161129L,
                CoopSaveIndexReader.parseSaveDate("2026-05-28 18:16:01.129 UTC"));
        assertEquals(0L, CoopSaveIndexReader.parseSaveDate("last Tuesday"));
        assertEquals(0L, CoopSaveIndexReader.parseSaveDate(""));
    }

    /** "Absent means unknown, not false" - the mod cannot always tell, and neither can we. */
    @Test
    void anAbsentAutosaveKeyIsUnknownRatherThanFalse() throws IOException {
        Files.createDirectories(saves.resolve("save_a"));
        writeIndex("{\"version\": 1, \"saves\": ["
                + "{\"campaignId\": \"c1\", \"saveDirName\": \"save_a\","
                + " \"characterName\": \"Kaz\", \"level\": 1, \"savedAtMillis\": 5,"
                + " \"role\": \"NONE\"}]}");

        assertNull(CoopSaveIndexReader.read(saves).saves().get(0).autosave());
    }

    @Test
    void aFolderWithNoDescriptorStillCountsAndTheRowSuppliesEverything() throws IOException {
        Files.createDirectories(saves.resolve("save_a"));
        writeIndex("{\"version\": 1, \"saves\": [" + row("c1", "save_a", "Kaz", 7) + "]}");

        CoopSaveIndexReader.Save save = CoopSaveIndexReader.read(saves).saves().get(0);

        assertEquals("Kaz", save.characterName());
        assertEquals(7, save.level());
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private void writeIndex(String json) throws IOException {
        Files.writeString(saves.resolve("common").resolve(CoopSaveIndexReader.INDEX_FILE_NAME),
                json, StandardCharsets.UTF_8);
    }

    private void folder(String name, String character, int level, long gameDateTimestamp,
                        String saveDate) throws IOException {
        Path directory = saves.resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("descriptor.xml"),
                "<?xml version=\"1.0\" ?>\n<SaveGameData z=\"1\">\n"
                        + "<characterName>" + character + "</characterName>\n"
                        + "<characterLevel>" + level + "</characterLevel>\n"
                        + "<gameDate z=\"2\"><timestamp>" + gameDateTimestamp
                        + "</timestamp></gameDate>\n"
                        + "<saveDate z=\"3\">" + saveDate + "</saveDate>\n"
                        + "<locDesc>Corvus Star System</locDesc>\n"
                        + "<autosave>false</autosave>\n"
                        + "</SaveGameData>\n", StandardCharsets.UTF_8);
    }

    private static String row(String campaignId, String folder, String character, int level) {
        return row(campaignId, folder, character, level, "HOST");
    }

    private static String row(String campaignId, String folder, String character, int level,
                              String role) {
        return "{\"campaignId\": \"" + campaignId + "\", \"saveDirName\": \"" + folder + "\","
                + " \"characterName\": \"" + character + "\", \"level\": " + level + ","
                + " \"gameDateTimestamp\": 500, \"gameDate\": \"Cycle 206\","
                + " \"savedAtMillis\": 1, \"autosave\": false, \"role\": \"" + role + "\"}";
    }

    // ---- the world settings ----------------------------------------------------------------------

    @Test
    void aRowThatRecordsItsWorldSettingsCarriesThemThroughTheJoin() throws IOException {
        folder("save_a", "Kaz", 4, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": [" + worldRow("c1", "save_a", "small", "young")
                + "]}");

        CoopSaveIndexReader.Save save = CoopSaveIndexReader.read(saves).saves().get(0);

        assertEquals("small", save.sectorSize());
        assertEquals("young", save.sectorAge());
        assertTrue(save.hasWorldSettings());
    }

    /** Every row written before 2026-09-04 looks like this, and the version is still 1. */
    @Test
    void aRowFromBeforeTheWorldSettingsExistedParsesWithoutThem() throws IOException {
        folder("save_a", "Kaz", 4, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": [" + row("c1", "save_a", "Kaz", 4) + "]}");

        CoopSaveIndexReader.Index index = CoopSaveIndexReader.read(saves);

        assertEquals(CoopSaveIndexReader.Status.OK, index.status());
        CoopSaveIndexReader.Save save = index.saves().get(0);
        assertEquals("", save.sectorSize());
        assertEquals("", save.sectorAge());
        assertFalse(save.hasWorldSettings());
    }

    @Test
    void halfTheWorldSettingsIsNotEnoughToPutInTheDropDowns() throws IOException {
        folder("save_a", "Kaz", 4, 500L, "2026-05-28 18:16:01.129 UTC");
        writeIndex("{\"version\": 1, \"saves\": [" + worldRow("c1", "save_a", "small", "")
                + "]}");

        assertFalse(CoopSaveIndexReader.read(saves).saves().get(0).hasWorldSettings());
    }

    private static String worldRow(String campaignId, String folder, String sectorSize,
                                   String sectorAge) {
        String base = row(campaignId, folder, "Kaz", 4);
        return base.substring(0, base.length() - 1)
                + ", \"sectorSize\": \"" + sectorSize + "\", \"sectorAge\": \"" + sectorAge
                + "\"}";
    }
}
