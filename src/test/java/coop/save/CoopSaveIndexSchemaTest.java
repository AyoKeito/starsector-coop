package coop.save;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared row codec, tested on its own so both the mod and the launcher inherit the same answers.
 *
 * <p>The golden test at the bottom is the point of the whole class: the on-disk key set was captured
 * from the code as it stood before the schema was split out, and it is asserted here so that moving
 * the codec cannot have changed the file by accident. If a change to {@code writeRow} makes that test
 * fail, the file format changed and every launcher already installed is the thing to think about.
 */
class CoopSaveIndexSchemaTest {

    private static final CoopSaveIndexSchema.Row FULL = new CoopSaveIndexSchema.Row(
            "camp-golden", "save_Kaz_9f2", "Kaz Alba", 12, 5_000L, "Cycle 206, Kerenth 12",
            1_700_000_000_000L, Boolean.TRUE, "HOST", "MN-42", "small", "young");

    private static List<String> sortedKeys(JSONObject entry) {
        List<String> keys = new ArrayList<>();
        // keySet() does not exist in the game's 2010 org.json; keys() does.
        for (Iterator<?> it = entry.keys(); it.hasNext(); ) {
            keys.add(String.valueOf(it.next()));
        }
        keys.sort(String::compareTo);
        return keys;
    }

    // ---- round trips ---------------------------------------------------------------------------

    @Test
    void aFullRowSurvivesTheRoundTripFieldForField() {
        CoopSaveIndexSchema.Row back = CoopSaveIndexSchema.readRow(CoopSaveIndexSchema.writeRow(FULL));

        assertEquals(FULL, back);
    }

    /** Every row written before 2026-09-04 looks like this: no folder name, no date, no world. */
    @Test
    void aRowFromBeforeTheOptionalKeysExistedReadsBackWithThemEmpty() {
        CoopSaveIndexSchema.Row spare = new CoopSaveIndexSchema.Row("camp-a", "", "Kaz", 3, 1L, "",
                5L, null, "HOST", "", "", "");

        JSONObject entry = CoopSaveIndexSchema.writeRow(spare);

        assertEquals(List.of("campaignId", "characterName", "gameDateTimestamp", "level", "role",
                "savedAtMillis"), sortedKeys(entry));
        assertEquals(spare, CoopSaveIndexSchema.readRow(entry));
        assertFalse(spare.hasSaveDirName());
    }

    /**
     * The mod only ever writes a JSON boolean, but the launcher has always read the string forms as
     * well, for a file somebody has opened in an editor. Splitting the codec keeps that.
     */
    @Test
    void anAutosaveWrittenAsAStringIsStillReadAsTheFlag() {
        assertEquals(Boolean.TRUE,
                CoopSaveIndexSchema.readRow(entryWithAutosave("\"true\"")).autosave());
        assertEquals(Boolean.FALSE,
                CoopSaveIndexSchema.readRow(entryWithAutosave("\"FALSE\"")).autosave());
        assertEquals(Boolean.TRUE, CoopSaveIndexSchema.readRow(entryWithAutosave("true")).autosave());
        assertEquals(Boolean.FALSE, CoopSaveIndexSchema.readRow(entryWithAutosave("false")).autosave());
    }

    /** "Absent means unknown, not false", and so does anything that is neither a yes nor a no. */
    @Test
    void anAutosaveThatIsNeitherIsUnknownRatherThanFalse() {
        assertNull(CoopSaveIndexSchema.readRow(entryWithAutosave("\"maybe\"")).autosave());
        assertNull(CoopSaveIndexSchema.readRow(entryWithAutosave("null")).autosave());
        assertNull(CoopSaveIndexSchema.readRow(entryWithAutosave("7")).autosave());
        assertNull(CoopSaveIndexSchema.readRow(new JSONObject()).autosave());
    }

    @Test
    void aRowWithNoCampaignIdComesBackUnusableRatherThanAsNull() {
        CoopSaveIndexSchema.Row row = CoopSaveIndexSchema.readRow(new JSONObject());

        assertFalse(row.usable());
        assertEquals("NONE", row.role());
        assertFalse(CoopSaveIndexSchema.readRow(null).usable());
    }

    @Test
    void textIsTrimmedTheRoleDefaultsAndTheWorldSettingsAreLowerCased() throws Exception {
        CoopSaveIndexSchema.Row row = CoopSaveIndexSchema.readRow(new JSONObject(
                "{\"campaignId\": \" camp-a \", \"characterName\": \"  Kaz  \","
                        + " \"gameDate\": \" Cycle 206 \", \"seedString\": \" MN-42 \","
                        + " \"sectorSize\": \" Small \", \"sectorAge\": \" YOUNG \"}"));

        assertEquals("camp-a", row.campaignId());
        assertEquals("Kaz", row.characterName());
        assertEquals("Cycle 206", row.gameDate());
        assertEquals("MN-42", row.seedString());
        assertEquals("small", row.sectorSize());
        assertEquals("young", row.sectorAge());
        assertEquals("NONE", row.role());
        assertTrue(row.usable());
    }

    // ---- the golden shape ----------------------------------------------------------------------

    /**
     * Captured from the code as it stood at 82d6fb3, before the codec moved. Twelve keys, the types
     * {@code org.json} writes for them, and the two longs that must not have been narrowed.
     */
    @Test
    void writeRowProducesExactlyTheShapeTheLauncherHasAlwaysRead() {
        JSONObject entry = CoopSaveIndexSchema.writeRow(FULL);

        assertEquals(List.of("autosave", "campaignId", "characterName", "gameDate",
                "gameDateTimestamp", "level", "role", "saveDirName", "savedAtMillis", "sectorAge",
                "sectorSize", "seedString"), sortedKeys(entry));
        assertEquals("camp-golden", entry.opt("campaignId"));
        assertEquals("save_Kaz_9f2", entry.opt("saveDirName"));
        assertEquals("Kaz Alba", entry.opt("characterName"));
        assertEquals(Integer.valueOf(12), entry.opt("level"));
        assertEquals(Long.valueOf(5_000L), entry.opt("gameDateTimestamp"));
        assertEquals("Cycle 206, Kerenth 12", entry.opt("gameDate"));
        assertEquals(Long.valueOf(1_700_000_000_000L), entry.opt("savedAtMillis"));
        assertEquals(Boolean.TRUE, entry.opt("autosave"));
        assertEquals("HOST", entry.opt("role"));
        assertEquals("MN-42", entry.opt("seedString"));
        assertEquals("small", entry.opt("sectorSize"));
        assertEquals("young", entry.opt("sectorAge"));
    }

    /** The mod's own writer goes through the same codec, so the file it produces is the same file. */
    @Test
    void theIndexTheModWritesCarriesTheGoldenRowUnchanged() throws Exception {
        JSONObject entry = new JSONObject(CoopSaveIndex.withRowText(null, FULL))
                .optJSONArray(CoopSaveIndexSchema.KEY_SAVES).optJSONObject(0);

        assertEquals(sortedKeys(CoopSaveIndexSchema.writeRow(FULL)), sortedKeys(entry));
        assertEquals(FULL, CoopSaveIndexSchema.readRow(entry));
    }

    private static JSONObject entryWithAutosave(String jsonValue) {
        try {
            return new JSONObject("{\"campaignId\": \"camp-a\", \"autosave\": " + jsonValue + "}");
        } catch (Exception ex) {
            throw new IllegalStateException(jsonValue, ex);
        }
    }
}
