package coop.save;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The one description of what {@code saves/common/coop_saves.json.data} looks like: the format
 * version, the key names, the row record and the codec that turns a row into JSON and back.
 *
 * <p><b>Why it is its own class.</b> Two programs read and write this file. {@link CoopSaveIndex}
 * writes it from inside the game, and {@code coop.launcher.CoopSaveIndexReader} reads it from the
 * desktop launcher's own JVM. Before this class both of them spelled out the format version, the two
 * top-level keys, the twelve row keys, a twelve-component row record and a decoder - independently.
 * Adding {@code sectorSize} and {@code sectorAge} meant editing both sides in lockstep, and nothing
 * would have caught it if only one side had been edited. Now the shape is stated once and both sides
 * link against it.
 *
 * <p><b>What it may import.</b> Nothing from {@code com.fs.starfarer}, and not {@code coop.util.
 * CoopLog}. The launcher compiles against {@code sourceSets.main.output} plus {@code json.jar} and
 * log4j, with {@code starfarer.api.jar} deliberately absent (see {@code build.gradle}), so anything
 * this class touches has to exist without the game. {@code org.json} and {@code java.util} only. That
 * is also why the codec is here rather than on {@code CoopSaveIndex}, which imports {@code Global}
 * and could never be linked from the launcher.
 *
 * <p><b>The decoder is the lenient one.</b> The two decoders it replaces differed, and where they did
 * the launcher's was the more forgiving: text is trimmed, and an {@code autosave} written as the
 * string {@code "true"} by somebody hand-editing the file is read as the boolean. That behaviour is
 * kept, because a file a human has touched is exactly the case where the reader should try. The two
 * rules that are genuinely the caller's business stay with the callers: {@link Row#usable()} says a
 * row has no campaign id, and each side decides what to do about it.
 *
 * <p><b>The encoder omits rather than defaults.</b> An optional field that is empty leaves its key out
 * of the object entirely - a row that never recorded a sector size says nothing about sector size,
 * instead of claiming one the sector was never generated at.
 */
public final class CoopSaveIndexSchema {

    /** The name handed to {@code SettingsAPI}'s {@code ...Common} calls. */
    public static final String COMMON_FILE = "coop_saves.json";

    /** What the file is actually called on disk: the engine appends {@code .data} to a common name. */
    public static final String COMMON_FILE_ON_DISK = COMMON_FILE + ".data";

    /** Where the file sits, for anything a player or a launcher shows. */
    public static final String COMMON_PATH = "saves/common/" + COMMON_FILE_ON_DISK;

    /** Bumped only if the row shape changes incompatibly; a reader that sees a higher one should stop. */
    public static final int FORMAT_VERSION = 1;

    /** Top-level keys. */
    public static final String KEY_VERSION = "version";
    public static final String KEY_SAVES = "saves";

    /** Row keys. */
    public static final String KEY_CAMPAIGN_ID = "campaignId";
    public static final String KEY_SAVE_DIR_NAME = "saveDirName";
    public static final String KEY_CHARACTER_NAME = "characterName";
    public static final String KEY_LEVEL = "level";
    public static final String KEY_GAME_DATE_TIMESTAMP = "gameDateTimestamp";
    public static final String KEY_GAME_DATE = "gameDate";
    public static final String KEY_SAVED_AT_MILLIS = "savedAtMillis";
    public static final String KEY_AUTOSAVE = "autosave";
    public static final String KEY_ROLE = "role";
    public static final String KEY_SEED_STRING = "seedString";
    /**
     * Sector size and star age, added 2026-09-04. Both are optional: only a campaign generated
     * through the coop new-game dialog since that date has them, so an older row simply leaves them
     * out and the launcher leaves its own controls alone. Additive on purpose -- the format version
     * is unchanged, because a launcher that has never heard of these keys goes on reading every row
     * exactly as before, and one that has, reads an old file just as happily.
     */
    public static final String KEY_SECTOR_SIZE = "sectorSize";
    public static final String KEY_SECTOR_AGE = "sectorAge";

    private CoopSaveIndexSchema() {
    }

    /**
     * One save, as the launcher's picker needs it.
     *
     * @param campaignId        the sector-persistent {@code coop.campaignId}; a row without one is
     *                          meaningless and is never written
     * @param saveDirName       the {@code saves/} folder name, or {@code ""} when the engine getter
     *                          could not be reached
     * @param characterName     the creation-time character name, the same string the engine writes to
     *                          {@code descriptor.xml} as {@code characterName}
     * @param level             the player's level, as {@code descriptor.xml} records it
     * @param gameDateTimestamp {@code CampaignClockAPI.getTimestamp()}, the in-game clock as a long
     * @param gameDate          the same instant as the game prints it ("Cycle 206, Kerenth 12"), or
     *                          {@code ""}
     * @param savedAtMillis     wall clock at the moment the save finished; the ordering key
     * @param autosave          {@code TRUE} when the mod itself asked for this autosave, {@code null}
     *                          when it cannot be told apart from a manual save (the engine does not
     *                          pass the flag to the hook)
     * @param role              {@code HOST}, {@code GUEST} or {@code NONE}, from the launch settings
     * @param seedString        the campaign's seed, or {@code ""} before one is stored
     * @param sectorSize        the size the sector was generated at, or {@code ""} on a row that does
     *                          not record it -- which every row written before 2026-09-04 is
     * @param sectorAge         the star age the sector was generated at, same rule
     */
    public record Row(String campaignId, String saveDirName, String characterName, int level,
                      long gameDateTimestamp, String gameDate, long savedAtMillis, Boolean autosave,
                      String role, String seedString, String sectorSize, String sectorAge) {

        public Row {
            campaignId = text(campaignId);
            saveDirName = text(saveDirName);
            characterName = text(characterName);
            gameDate = text(gameDate);
            role = text(role).isEmpty() ? "NONE" : text(role);
            seedString = text(seedString);
            sectorSize = text(sectorSize).toLowerCase(Locale.ROOT);
            sectorAge = text(sectorAge).toLowerCase(Locale.ROOT);
        }

        /** Whether this row carries enough to be worth keeping. */
        public boolean usable() {
            return !campaignId.isEmpty();
        }

        /** Whether the launcher can open the folder directly instead of matching descriptors. */
        public boolean hasSaveDirName() {
            return !saveDirName.isEmpty();
        }
    }

    /**
     * One row as it goes on disk. Optional fields that are empty are left out rather than written as
     * an empty string, which is what makes an old row and a new one the same file format.
     *
     * <p>Never throws: {@code org.json} only refuses a null key or a NaN, and there are neither here.
     */
    public static JSONObject writeRow(Row row) {
        JSONObject entry = new JSONObject();
        if (row == null) {
            return entry;
        }
        try {
            entry.put(KEY_CAMPAIGN_ID, row.campaignId());
            if (row.hasSaveDirName()) {
                entry.put(KEY_SAVE_DIR_NAME, row.saveDirName());
            }
            entry.put(KEY_CHARACTER_NAME, row.characterName());
            entry.put(KEY_LEVEL, row.level());
            entry.put(KEY_GAME_DATE_TIMESTAMP, row.gameDateTimestamp());
            if (!row.gameDate().isEmpty()) {
                entry.put(KEY_GAME_DATE, row.gameDate());
            }
            entry.put(KEY_SAVED_AT_MILLIS, row.savedAtMillis());
            if (row.autosave() != null) {
                entry.put(KEY_AUTOSAVE, row.autosave().booleanValue());
            }
            entry.put(KEY_ROLE, row.role());
            if (!row.seedString().isEmpty()) {
                entry.put(KEY_SEED_STRING, row.seedString());
            }
            if (!row.sectorSize().isEmpty()) {
                entry.put(KEY_SECTOR_SIZE, row.sectorSize());
            }
            if (!row.sectorAge().isEmpty()) {
                entry.put(KEY_SECTOR_AGE, row.sectorAge());
            }
        } catch (Exception ignored) {
            // Constant keys and primitives; org.json only refuses null keys and NaN.
        }
        return entry;
    }

    /**
     * One row as it comes off disk. Never throws and never returns null: a row that says nothing
     * useful comes back with an empty campaign id, and {@link Row#usable()} is how a caller asks.
     *
     * <p>{@code role} is left to the {@link Row} constructor's {@code NONE} default rather than
     * defaulted here, so a row that omits the key and a row that writes {@code ""} read the same.
     */
    public static Row readRow(JSONObject entry) {
        if (entry == null) {
            return new Row("", "", "", 0, 0L, "", 0L, null, "", "", "", "");
        }
        return new Row(entry.optString(KEY_CAMPAIGN_ID, ""),
                entry.optString(KEY_SAVE_DIR_NAME, ""),
                entry.optString(KEY_CHARACTER_NAME, ""),
                entry.optInt(KEY_LEVEL, 0),
                entry.optLong(KEY_GAME_DATE_TIMESTAMP, 0L),
                entry.optString(KEY_GAME_DATE, ""),
                entry.optLong(KEY_SAVED_AT_MILLIS, 0L),
                readAutosave(entry),
                entry.optString(KEY_ROLE, ""),
                entry.optString(KEY_SEED_STRING, ""),
                entry.optString(KEY_SECTOR_SIZE, ""),
                entry.optString(KEY_SECTOR_AGE, ""));
    }

    /**
     * {@code TRUE}/{@code FALSE} when the row says so, {@code null} when it does not say or says
     * something that is not a yes or a no.
     *
     * <p>The string forms are read too. The mod only ever writes a JSON boolean, but a player who has
     * opened the file in an editor to fix something else should not have the flag silently dropped -
     * and "absent means unknown" is a real answer here, so guessing {@code false} for a value like
     * {@code "maybe"} would be worse than admitting we cannot tell.
     */
    private static Boolean readAutosave(JSONObject entry) {
        if (!entry.has(KEY_AUTOSAVE)) {
            return null;
        }
        Object value = entry.opt(KEY_AUTOSAVE);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null) {
            return null;
        }
        String written = text(String.valueOf(value));
        if (written.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (written.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
