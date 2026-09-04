package coop.save;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index maths, with no engine anywhere near it. Everything the launcher depends on is decided by
 * {@link CoopSaveIndex#withRowText} and {@link CoopSaveIndex#rowsFromText}, so both are pure and both
 * are exercised here as text in / text out: that is the shape the file actually has.
 *
 * <p>The one rule worth stating out loud is what happens to a file that will not parse. This code runs
 * inside {@code afterGameSave}, so throwing is not an option and neither is refusing to write: a
 * corrupt index that could never be replaced would stay corrupt forever. It is replaced, and the cost
 * is the launcher's memory of older saves.
 */
class CoopSaveIndexTest {

    private static CoopSaveIndexSchema.Row row(String campaignId, String dir, long savedAt) {
        return new CoopSaveIndexSchema.Row(campaignId, dir, "Kaz Alba", 7, 1_000L,
                "Cycle 206, Kerenth 12", savedAt, null, "HOST", "MN-42", "small", "young");
    }

    // ---- add and update ------------------------------------------------------------------------

    @Test
    void aRowIsAddedToAnEmptyIndex() throws Exception {
        String text = CoopSaveIndex.withRowText(null, row("camp-a", "save_Kaz_1", 100L));

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(1, rows.size());
        CoopSaveIndexSchema.Row stored = rows.get(0);
        assertEquals("camp-a", stored.campaignId());
        assertEquals("save_Kaz_1", stored.saveDirName());
        assertEquals("Kaz Alba", stored.characterName());
        assertEquals(7, stored.level());
        assertEquals(1_000L, stored.gameDateTimestamp());
        assertEquals("Cycle 206, Kerenth 12", stored.gameDate());
        assertEquals(100L, stored.savedAtMillis());
        assertEquals("HOST", stored.role());
        assertEquals("MN-42", stored.seedString());
        assertNull(stored.autosave());
        assertEquals(CoopSaveIndex.FORMAT_VERSION,
                new JSONObject(text).optInt(CoopSaveIndex.KEY_VERSION, -1));
    }

    @Test
    void savingOverTheSameFolderReplacesTheRowRatherThanAddingOne() {
        String text = CoopSaveIndex.withRowText(null, row("camp-a", "save_Kaz_1", 100L));
        text = CoopSaveIndex.withRowText(text, row("camp-a", "save_Kaz_1", 200L));

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(1, rows.size());
        assertEquals(200L, rows.get(0).savedAtMillis());
    }

    @Test
    void theSameCampaignInADifferentFolderIsASecondRow() {
        String text = CoopSaveIndex.withRowText(null, row("camp-a", "save_Kaz_1", 100L));
        text = CoopSaveIndex.withRowText(text, row("camp-a", "save_Kaz_2", 200L));

        assertEquals(2, CoopSaveIndex.rowsFromText(text).size());
    }

    @Test
    void aRowWithNoFolderNameOmitsTheKeyAndIsNeverTreatedAsARewrite() throws Exception {
        String text = CoopSaveIndex.withRowText(null, row("camp-a", "", 100L));
        text = CoopSaveIndex.withRowText(text, row("camp-a", "", 200L));

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        // With nothing to match on there is no way to know the two are the same slot, so both are
        // kept and retention decides. The launcher matches these against descriptor.xml instead.
        assertEquals(2, rows.size());
        assertFalse(rows.get(0).hasSaveDirName());
        JSONObject entry = new JSONObject(text)
                .optJSONArray(CoopSaveIndex.KEY_SAVES).optJSONObject(0);
        assertFalse(CoopSaveIndex.keysOf(entry).contains(CoopSaveIndex.KEY_SAVE_DIR_NAME));
    }

    @Test
    void anIndexWithNoCampaignIdIsRefusedRatherThanStored() throws Exception {
        String text = CoopSaveIndex.withRowText(null, row("", "save_Kaz_1", 100L));

        assertTrue(CoopSaveIndex.rowsFromText(text).isEmpty());
        assertNotNull(new JSONObject(text).optJSONArray(CoopSaveIndex.KEY_SAVES));
    }

    // ---- retention -----------------------------------------------------------------------------

    @Test
    void onlyTheLastEightRowsOfACampaignAreKept() {
        String text = null;
        for (int i = 1; i <= CoopSaveIndex.MAX_ROWS_PER_CAMPAIGN + 4; i++) {
            text = CoopSaveIndex.withRowText(text, row("camp-a", "save_Kaz_" + i, i * 100L));
        }

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(CoopSaveIndex.MAX_ROWS_PER_CAMPAIGN, rows.size());
        assertEquals(1_200L, rows.get(0).savedAtMillis());
        assertEquals(500L, rows.get(rows.size() - 1).savedAtMillis());
    }

    @Test
    void oneBusyCampaignDoesNotCrowdOutAQuietOne() {
        String text = CoopSaveIndex.withRowText(null, row("camp-quiet", "save_Q_1", 1L));
        for (int i = 1; i <= CoopSaveIndex.MAX_ROWS_PER_CAMPAIGN + 4; i++) {
            text = CoopSaveIndex.withRowText(text, row("camp-busy", "save_B_" + i, i * 100L));
        }

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(1, CoopSaveIndex.forCampaign(rows, "camp-quiet").size());
        assertEquals(CoopSaveIndex.MAX_ROWS_PER_CAMPAIGN,
                CoopSaveIndex.forCampaign(rows, "camp-busy").size());
    }

    @Test
    void theLeastRecentlySavedCampaignFallsOffPastTheCampaignCap() {
        String text = null;
        for (int i = 1; i <= CoopSaveIndex.MAX_CAMPAIGNS + 2; i++) {
            text = CoopSaveIndex.withRowText(text, row("camp-" + i, "save_" + i, i * 100L));
        }

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(CoopSaveIndex.MAX_CAMPAIGNS, rows.size());
        assertTrue(CoopSaveIndex.forCampaign(rows, "camp-1").isEmpty());
        assertTrue(CoopSaveIndex.forCampaign(rows, "camp-2").isEmpty());
        assertFalse(CoopSaveIndex.forCampaign(rows, "camp-3").isEmpty());
    }

    // ---- ordering and lookup -------------------------------------------------------------------

    @Test
    void rowsComeBackNewestFirstWhateverOrderTheyWentIn() {
        String text = CoopSaveIndex.withRowText(null, row("camp-a", "save_1", 300L));
        text = CoopSaveIndex.withRowText(text, row("camp-a", "save_2", 100L));
        text = CoopSaveIndex.withRowText(text, row("camp-a", "save_3", 200L));

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(List.of(300L, 200L, 100L),
                rows.stream().map(CoopSaveIndexSchema.Row::savedAtMillis).toList());
        assertEquals("save_1", CoopSaveIndex.newestForCampaign(rows, "camp-a").saveDirName());
        assertNull(CoopSaveIndex.newestForCampaign(rows, "camp-b"));
        assertNull(CoopSaveIndex.newestForCampaign(rows, ""));
    }

    // ---- tolerance -----------------------------------------------------------------------------

    @Test
    void aMalformedFileIsReplacedByAFreshIndexRatherThanThrowing() {
        String text = CoopSaveIndex.withRowText("{ this is not json", row("camp-a", "save_1", 100L));

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(1, rows.size());
        assertEquals("camp-a", rows.get(0).campaignId());
    }

    @Test
    void junkInsideAWellFormedFileIsSkippedRowByRow() {
        String text = "{\"version\":1,\"saves\":[7,{\"characterName\":\"no id\"},"
                + "{\"campaignId\":\"camp-a\",\"savedAtMillis\":50}]}";

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);
        assertEquals(1, rows.size());
        assertEquals("camp-a", rows.get(0).campaignId());
        // And the next write keeps the one salvageable row alongside the new one.
        assertEquals(2, CoopSaveIndex.rowsFromText(
                CoopSaveIndex.withRowText(text, row("camp-b", "save_1", 100L))).size());
    }

    @Test
    void nullAndBlankTextAreBothJustAnEmptyIndex() {
        assertTrue(CoopSaveIndex.rowsFromText(null).isEmpty());
        assertTrue(CoopSaveIndex.rowsFromText("   ").isEmpty());
        assertTrue(CoopSaveIndex.rows(null).isEmpty());
        assertTrue(CoopSaveIndex.rows(CoopSaveIndex.emptyIndex()).isEmpty());
    }

    @Test
    void aRowNormalisesItsOwnTextAndDefaultsTheRole() {
        CoopSaveIndexSchema.Row normalised = new CoopSaveIndexSchema.Row(" camp-a ", null, "  Kaz  ", 3, 1L,
                null, 9L, Boolean.TRUE, "", null, " NORMAL ", " Mixed ");

        assertEquals("camp-a", normalised.campaignId());
        assertEquals("", normalised.saveDirName());
        assertEquals("Kaz", normalised.characterName());
        assertEquals("NONE", normalised.role());
        assertEquals("", normalised.seedString());
        assertEquals("normal", normalised.sectorSize());
        assertEquals("mixed", normalised.sectorAge());
        assertTrue(normalised.usable());
    }

    // ---- the autosave flag ---------------------------------------------------------------------

    @Test
    void theAutosaveFlagSurvivesTheRoundTripAndIsOmittedWhenUnknown() throws Exception {
        CoopSaveIndexSchema.Row marked = new CoopSaveIndexSchema.Row("camp-a", "save_1", "Kaz", 1, 1L, "",
                100L, Boolean.TRUE, "GUEST", "", "", "");
        String text = CoopSaveIndex.withRowText(null, marked);
        assertEquals(Boolean.TRUE, CoopSaveIndex.rowsFromText(text).get(0).autosave());

        String unknown = CoopSaveIndex.withRowText(null, row("camp-a", "save_1", 100L));
        assertNull(CoopSaveIndex.rowsFromText(unknown).get(0).autosave());
        JSONObject entry = new JSONObject(unknown)
                .optJSONArray(CoopSaveIndex.KEY_SAVES).optJSONObject(0);
        assertFalse(CoopSaveIndex.keysOf(entry).contains(CoopSaveIndex.KEY_AUTOSAVE));
    }

    @Test
    void theCoopAutosaveMarkerIsADepthCounterAndClosesBackToUnknown() {
        CoopSaveIndex.resetEngineHandlesForTest();
        assertNull(CoopSaveIndex.autosaveFlag());

        CoopSaveIndex.beginCoopAutosave();
        CoopSaveIndex.beginCoopAutosave();
        assertEquals(Boolean.TRUE, CoopSaveIndex.autosaveFlag());
        CoopSaveIndex.endCoopAutosave();
        assertEquals(Boolean.TRUE, CoopSaveIndex.autosaveFlag());
        CoopSaveIndex.endCoopAutosave();
        assertNull(CoopSaveIndex.autosaveFlag());

        // An unmatched close must not push the counter negative: the next real scope would then be
        // invisible, and every autosave after it recorded as if it were a manual save.
        CoopSaveIndex.endCoopAutosave();
        CoopSaveIndex.beginCoopAutosave();
        assertEquals(Boolean.TRUE, CoopSaveIndex.autosaveFlag());
        CoopSaveIndex.endCoopAutosave();
        assertNull(CoopSaveIndex.autosaveFlag());
    }

    // ---- the write cap -------------------------------------------------------------------------

    @Test
    void theFileStaysFarUnderTheEnginesOneMegabyteWriteCap() {
        String text = null;
        for (int campaign = 1; campaign <= CoopSaveIndex.MAX_CAMPAIGNS + 4; campaign++) {
            for (int save = 1; save <= CoopSaveIndex.MAX_ROWS_PER_CAMPAIGN + 2; save++) {
                text = CoopSaveIndex.withRowText(text,
                        row("campaign-" + campaign, "save_Kaz_" + campaign + "_" + save,
                                campaign * 1_000L + save));
            }
        }

        assertTrue(text.length() < CoopSaveIndex.MAX_BYTES, "index grew to " + text.length());
        assertEquals(CoopSaveIndex.MAX_CAMPAIGNS * CoopSaveIndex.MAX_ROWS_PER_CAMPAIGN,
                CoopSaveIndex.rowsFromText(text).size());
    }

    // ---- the world settings ----------------------------------------------------------------------

    @Test
    void theSectorSizeAndStarAgeSurviveTheRoundTrip() {
        String text = CoopSaveIndex.withRowText(null, row("camp-a", "save_Kaz_1", 100L));

        CoopSaveIndexSchema.Row stored = CoopSaveIndex.rowsFromText(text).get(0);
        assertEquals("small", stored.sectorSize());
        assertEquals("young", stored.sectorAge());
    }

    /**
     * A campaign generated before the mod recorded these, or through the vanilla dialog. The keys go
     * out of the row entirely rather than carrying a default the sector was never generated at.
     */
    @Test
    void aRowWithNoWorldSettingsOmitsBothKeys() throws Exception {
        String text = CoopSaveIndex.withRowText(null,
                new CoopSaveIndexSchema.Row("camp-a", "save_1", "Kaz", 1, 1L, "", 100L, null, "HOST",
                        "MN-42", "", ""));

        JSONObject entry = new JSONObject(text)
                .optJSONArray(CoopSaveIndex.KEY_SAVES).optJSONObject(0);
        assertFalse(entry.has(CoopSaveIndex.KEY_SECTOR_SIZE));
        assertFalse(entry.has(CoopSaveIndex.KEY_SECTOR_AGE));
        CoopSaveIndexSchema.Row stored = CoopSaveIndex.rowsFromText(text).get(0);
        assertEquals("", stored.sectorSize());
        assertEquals("", stored.sectorAge());
    }

    /**
     * The two keys were added on 2026-09-04 without touching the version, so a launcher that has
     * never heard of them keeps reading the file. A row written before them has to keep reading too.
     */
    @Test
    void aRowFromBeforeTheWorldSettingsExistedStillReadsAtVersionOne() throws Exception {
        String text = "{\"version\": 1, \"saves\": [{\"campaignId\": \"camp-a\","
                + " \"saveDirName\": \"save_1\", \"characterName\": \"Kaz\", \"level\": 3,"
                + " \"gameDateTimestamp\": 1, \"savedAtMillis\": 100, \"role\": \"HOST\"}]}";

        List<CoopSaveIndexSchema.Row> rows = CoopSaveIndex.rowsFromText(text);

        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).sectorSize());
        assertEquals("", rows.get(0).sectorAge());
        assertEquals(1, CoopSaveIndex.FORMAT_VERSION,
                "the world settings are additive; bumping the version would lock older launchers"
                        + " out of a file they can read perfectly well");
    }
}
