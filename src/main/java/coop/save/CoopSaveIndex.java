package coop.save;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.net.CoopNetStartupConfig;
import coop.seed.CoopSeedSync;
import coop.util.CoopLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer to "which save is the co-op campaign in?", written where something outside the game can
 * read it.
 *
 * <p>Starsector names a save folder {@code save_<character>_<random>} and shows the player a slot
 * list built from each folder's {@code descriptor.xml}. Neither carries a campaign id, so a player
 * with three campaigns and two autosave slots per campaign has no way to tell which folder the co-op
 * invite is for, and the launcher has nothing to point at. This index is that missing column: one row
 * per save the mod has watched being written, keyed by the sector-persistent
 * {@code coop.campaignId} the Phase 6b seed lock already maintains.
 *
 * <p><b>Where it lives.</b> {@code saves/common/coop_saves.json.data} - the engine appends
 * {@code .data} to every {@code ...Common} name, exactly as {@code CoopOptionsStore} documents for
 * its own file. It is a <em>common</em> file, not sector data: nothing here goes into a save, so no
 * XStream alias and no save migration is involved. The engine's own 1 MB write cap applies, which is
 * what the retention rules below are sized against.
 *
 * <p><b>Written from {@code afterGameSave}, never {@code beforeGameSave}.</b> Two reasons. The folder
 * exists by then, so a row never points at a directory the engine gave up on halfway; and a save that
 * threw takes {@code onGameSaveFailed} instead, so a failed write leaves no row claiming success.
 *
 * <p><b>{@code saveDirName} is read inside the hook, every time, and never cached.</b> The engine
 * swaps {@code CampaignEngine.saveDirName} to a freshly generated {@code save_<name>_<random>} for
 * the duration of an autosave or a save-into-a-new-slot and restores it afterwards
 * ({@code CampaignState.autosave}, and the "save as" branch of its dialog handler). Both mod hooks
 * fire <em>inside</em> that swap - {@code beforeGameSave} at the top of
 * {@code CampaignGameManager}'s save routine, {@code afterGameSave} at the bottom of it, with the
 * restore only happening after the routine returns - so the in-hook value is the folder actually
 * being written, and a value cached from an earlier save would name the wrong one.
 *
 * <p><b>Why {@link MethodHandle}s for one getter.</b> {@code Global.getSector()} is
 * {@code com.fs.starfarer.campaign.CampaignEngine}, which is {@code DoNotObfuscate} and declares
 * {@code public String getSaveDirName()} - but {@code SectorAPI} does not, and the mod's script
 * classloader hard-blocks {@code java.lang.reflect}. {@code MethodHandles.privateLookupIn} on the
 * runtime class is the variant with live in-game evidence behind it; see
 * {@code coop.time.CoopFastForwardLock} and {@code docs/starsector-runtime-limitations.md}. When it
 * cannot be resolved the row is still written, without a folder name, and the launcher falls back to
 * matching {@code characterName} plus {@code gameDateTimestamp} against each save's
 * {@code descriptor.xml}.
 *
 * <p><b>A save must never fail because of this.</b> Every engine call and every JSON call in here is
 * wrapped; an unreadable or malformed existing file is logged once and replaced by a fresh index
 * rather than throwing back into the engine's save routine.
 *
 * <p><b>Rows go stale on purpose.</b> The engine prunes autosave folders to {@code maxAutosaveSlots}
 * (3 by default) without telling any mod, so a row can name a folder that is no longer there. The
 * index does not try to keep up: the reader (the launcher) stats the folder and skips what is gone.
 */
public final class CoopSaveIndex {

    /** The name handed to {@code SettingsAPI}'s {@code ...Common} calls. */
    public static final String COMMON_FILE = "coop_saves.json";

    /** Where the file actually sits, for anything a player or a launcher reads. */
    public static final String COMMON_PATH = "saves/common/coop_saves.json.data";

    /** Bumped only if the row shape changes incompatibly; a reader that sees a higher one should stop. */
    public static final int FORMAT_VERSION = 1;

    /** Top-level keys. */
    public static final String KEY_VERSION = "version";
    public static final String KEY_SAVES = "saves";

    /** Row keys, spelled once so the launcher-side parser can be checked against them. */
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
     * How many rows one campaign keeps. Eight covers the three autosave slots the engine keeps plus a
     * handful of manual slots, which is more history than a picker can use and still leaves the file
     * two orders of magnitude under the write cap.
     */
    public static final int MAX_ROWS_PER_CAMPAIGN = 8;

    /**
     * How many campaigns the file remembers at all, least-recently-saved first out. Without it a
     * player who starts twenty test campaigns grows the file forever, which is the only way a 1 MB cap
     * is reachable from rows this small.
     */
    public static final int MAX_CAMPAIGNS = 16;

    /**
     * Refuse to write anything larger. The engine's cap is 1 MB; this leaves room for the encoding
     * overhead its writer adds, and a file that somehow got past the two retention rules is trimmed
     * down to fit rather than dropped.
     */
    public static final int MAX_BYTES = 900_000;

    /** Depth of coop-requested autosaves in flight; see {@link #beginCoopAutosave()}. */
    private static int coopAutosaveDepth;

    /** Resolved once per runtime class; the handle is cached, the value it returns never is. */
    private static volatile Class<?> saveDirOwner;
    private static volatile MethodHandle saveDirHandle;
    private static volatile boolean saveDirWarned;

    private CoopSaveIndex() {
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
     */
    public record Row(String campaignId, String saveDirName, String characterName, int level,
                      long gameDateTimestamp, String gameDate, long savedAtMillis, Boolean autosave,
                      String role, String seedString) {

        public Row {
            campaignId = text(campaignId);
            saveDirName = text(saveDirName);
            characterName = text(characterName);
            gameDate = text(gameDate);
            role = text(role).isEmpty() ? "NONE" : text(role);
            seedString = text(seedString);
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

    // ---- pure index maths ----------------------------------------------------------------------

    /** A valid, empty index. */
    public static JSONObject emptyIndex() {
        JSONObject index = new JSONObject();
        try {
            index.put(KEY_VERSION, FORMAT_VERSION);
            index.put(KEY_SAVES, new JSONArray());
        } catch (Exception ignored) {
            // org.json only throws here for a null key, which cannot happen with constants.
        }
        return index;
    }

    /**
     * The rows in an index, newest first, skipping anything unusable. Never throws: an entry that is
     * not an object, or has no campaign id, is simply not a row.
     */
    public static List<Row> rows(JSONObject index) {
        List<Row> result = new ArrayList<>();
        if (index == null) {
            return result;
        }
        JSONArray saves = index.optJSONArray(KEY_SAVES);
        if (saves == null) {
            return result;
        }
        for (int i = 0; i < saves.length(); i++) {
            JSONObject entry = saves.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            Row row = readRow(entry);
            if (row.usable()) {
                result.add(row);
            }
        }
        result.sort(byNewestFirst());
        return result;
    }

    /**
     * The rows in an index given as text. A file that will not parse is not an error the caller has to
     * handle: it yields no rows, and {@link #withRowText} replaces it.
     */
    public static List<Row> rowsFromText(String indexText) {
        return rows(parseOrNull(indexText));
    }

    /**
     * The index with {@code row} added, the retention rules applied and the whole thing ordered newest
     * first.
     *
     * <p>A row replaces an existing one when both name the same campaign <em>and</em> the same
     * non-empty folder: that is a manual save written over its own slot, not a new save. With no
     * folder name there is nothing to match on, so the row is appended and retention decides.
     *
     * <p>{@code index} may be null or malformed; either way the result is a fresh, valid index that
     * carries {@code row}.
     */
    public static JSONObject withRow(JSONObject index, Row row) {
        JSONObject result = emptyIndex();
        if (row == null || !row.usable()) {
            return index == null ? result : write(result, rows(index));
        }
        List<Row> kept = new ArrayList<>();
        for (Row existing : rows(index)) {
            if (replaces(row, existing)) {
                continue;
            }
            kept.add(existing);
        }
        kept.add(row);
        return write(result, trim(kept));
    }

    /** {@link #withRow} over text, for callers (and tests) that hold the file as a string. */
    public static String withRowText(String indexText, Row row) {
        return withRow(parseOrNull(indexText), row).toString();
    }

    /** Every row for one campaign, newest first. */
    public static List<Row> forCampaign(List<Row> rows, String campaignId) {
        List<Row> result = new ArrayList<>();
        String wanted = text(campaignId);
        if (rows == null || wanted.isEmpty()) {
            return result;
        }
        for (Row row : rows) {
            if (wanted.equals(row.campaignId())) {
                result.add(row);
            }
        }
        result.sort(byNewestFirst());
        return result;
    }

    /** The most recent row for one campaign, or null. */
    public static Row newestForCampaign(List<Row> rows, String campaignId) {
        List<Row> matches = forCampaign(rows, campaignId);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** True when {@code row} is a rewrite of {@code existing} rather than a new save. */
    private static boolean replaces(Row row, Row existing) {
        return row.campaignId().equals(existing.campaignId())
                && row.hasSaveDirName()
                && row.saveDirName().equals(existing.saveDirName());
    }

    /**
     * Both retention rules, in the order that makes them composable: per-campaign first (so a busy
     * campaign cannot crowd out a quiet one), then the campaign cap by how recently each was saved.
     */
    private static List<Row> trim(List<Row> rows) {
        Map<String, List<Row>> byCampaign = new LinkedHashMap<>();
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(byNewestFirst());
        for (Row row : sorted) {
            List<Row> group = byCampaign.computeIfAbsent(row.campaignId(), key -> new ArrayList<>());
            if (group.size() < MAX_ROWS_PER_CAMPAIGN) {
                group.add(row);
            }
        }
        List<String> campaigns = new ArrayList<>(byCampaign.keySet());
        // byCampaign was filled from a newest-first list, so each group's head is its newest row and
        // the insertion order is already "most recently saved campaign first".
        List<Row> result = new ArrayList<>();
        for (int i = 0; i < campaigns.size() && i < MAX_CAMPAIGNS; i++) {
            result.addAll(byCampaign.get(campaigns.get(i)));
        }
        result.sort(byNewestFirst());
        return result;
    }

    private static Comparator<Row> byNewestFirst() {
        return Comparator.comparingLong(Row::savedAtMillis).reversed()
                .thenComparing(Row::campaignId)
                .thenComparing(Row::saveDirName);
    }

    private static JSONObject write(JSONObject index, List<Row> rows) {
        List<Row> pending = new ArrayList<>(rows);
        while (true) {
            JSONArray saves = new JSONArray();
            for (Row row : pending) {
                saves.put(writeRow(row));
            }
            try {
                index.put(KEY_SAVES, saves);
            } catch (Exception ignored) {
                // Constant key; unreachable.
            }
            if (index.toString().length() <= MAX_BYTES || pending.size() <= 1) {
                return index;
            }
            // Cannot happen with the two retention caps in place, and is still handled: the oldest row
            // goes rather than the write being refused.
            pending.remove(pending.size() - 1);
        }
    }

    private static JSONObject writeRow(Row row) {
        JSONObject entry = new JSONObject();
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
        } catch (Exception ignored) {
            // Constant keys and primitives; org.json only refuses null keys and NaN.
        }
        return entry;
    }

    private static Row readRow(JSONObject entry) {
        Boolean autosave = entry.has(KEY_AUTOSAVE) && !entry.isNull(KEY_AUTOSAVE)
                ? Boolean.valueOf(entry.optBoolean(KEY_AUTOSAVE, false))
                : null;
        return new Row(entry.optString(KEY_CAMPAIGN_ID, ""),
                entry.optString(KEY_SAVE_DIR_NAME, ""),
                entry.optString(KEY_CHARACTER_NAME, ""),
                entry.optInt(KEY_LEVEL, 0),
                entry.optLong(KEY_GAME_DATE_TIMESTAMP, 0L),
                entry.optString(KEY_GAME_DATE, ""),
                entry.optLong(KEY_SAVED_AT_MILLIS, 0L),
                autosave,
                entry.optString(KEY_ROLE, "NONE"),
                entry.optString(KEY_SEED_STRING, ""));
    }

    private static JSONObject parseOrNull(String indexText) {
        if (indexText == null || indexText.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(indexText);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- the coop-autosave marker --------------------------------------------------------------

    /**
     * Marks the enclosing {@code CampaignUIAPI.autosave()} call as one the mod asked for, so the row
     * it produces can say {@code autosave: true}.
     *
     * <p>The engine passes its own autosave flag to {@code SaveGameData.setAutosave} and to nobody
     * else - {@code beforeGameSave}/{@code afterGameSave} take no arguments - and an autosave folder
     * is named by the same generator a "save into a new slot" uses, so the name cannot be read for it
     * either. What the mod <em>can</em> know is its own two autosave call sites (the coordinated
     * checkpoint and the pre-battle insurance save), and those are the autosaves a rejoining guest
     * cares about. Anything else leaves the field out rather than guessing.
     *
     * <p>{@code autosave()} runs the whole save inline, so this is a plain depth counter and its
     * partner belongs in a {@code finally}.
     */
    public static synchronized void beginCoopAutosave() {
        coopAutosaveDepth++;
    }

    /** Closes a {@link #beginCoopAutosave()} scope. Safe to call when none is open. */
    public static synchronized void endCoopAutosave() {
        if (coopAutosaveDepth > 0) {
            coopAutosaveDepth--;
        }
    }

    /** {@code TRUE} inside a coop-requested autosave, {@code null} when it cannot be told. */
    static synchronized Boolean autosaveFlag() {
        return coopAutosaveDepth > 0 ? Boolean.TRUE : null;
    }

    // ---- the engine side -----------------------------------------------------------------------

    /**
     * Writes or updates this campaign's row. Call from {@code afterGameSave} only. Never throws.
     *
     * <p>Skipped entirely when the sector has no {@code coop.campaignId}: a solo campaign that has
     * never seed-locked is not a co-op campaign, and an id-less row would be a save the launcher can
     * offer for every invite.
     */
    public static void recordCurrentSave() {
        try {
            Row row = currentRow();
            if (row == null) {
                return;
            }
            JSONObject updated = withRow(readIndex(), row);
            if (!writeIndex(updated)) {
                return;
            }
            CoopLog.info(CoopSaveIndex.class, "Coop save index updated in " + COMMON_PATH
                    + ": campaignId=" + row.campaignId()
                    + " saveDirName=" + (row.hasSaveDirName() ? row.saveDirName() : "<unknown>")
                    + " character=" + row.characterName() + " level=" + row.level()
                    + " role=" + row.role());
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopSaveIndex.class, "Coop could not update the save index " + COMMON_PATH
                    + "; the launcher will not be able to name this save for a co-op invite", ex);
        }
    }

    /** Every row on this machine, newest first. Never throws; an unreadable file yields no rows. */
    public static List<Row> readRows() {
        try {
            return rows(readIndex());
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopSaveIndex.class, "Coop could not read the save index " + COMMON_PATH, ex);
            return List.of();
        }
    }

    /** The row for the save currently being written, or null when there is nothing worth writing. */
    private static Row currentRow() {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return null;
        }
        String campaignId = CoopSeedSync.currentCampaignId();
        if (campaignId == null || campaignId.trim().isEmpty()) {
            return null;
        }
        return new Row(campaignId,
                currentSaveDirName(sector),
                currentCharacterName(sector),
                currentLevel(sector),
                currentGameDateTimestamp(sector),
                currentGameDate(sector),
                System.currentTimeMillis(),
                autosaveFlag(),
                currentRole(),
                CoopSeedSync.currentSectorSeedString());
    }

    /**
     * The save folder the engine is writing right now, or {@code ""}.
     *
     * <p>Read fresh on every call, deliberately: see the class doc for the swap that makes a cached
     * value name the wrong folder.
     */
    static String currentSaveDirName(SectorAPI sector) {
        if (sector == null) {
            return "";
        }
        try {
            MethodHandle handle = saveDirNameHandle(sector.getClass());
            if (handle == null) {
                return "";
            }
            Object value = handle.invoke(sector);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable ex) {
            warnSaveDirOnce(ex);
            return "";
        }
    }

    private static MethodHandle saveDirNameHandle(Class<?> sectorClass) {
        MethodHandle cached = saveDirHandle;
        if (cached != null && saveDirOwner == sectorClass) {
            return cached;
        }
        Throwable lastMiss = null;
        for (Class<?> c = sectorClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                MethodHandles.Lookup priv = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                MethodHandle handle = priv.findVirtual(c, "getSaveDirName",
                        MethodType.methodType(String.class));
                saveDirOwner = sectorClass;
                saveDirHandle = handle;
                return handle;
            } catch (Throwable miss) {
                lastMiss = miss;
            }
        }
        warnSaveDirOnce(lastMiss);
        return null;
    }

    private static synchronized void warnSaveDirOnce(Throwable ex) {
        if (saveDirWarned) {
            return;
        }
        saveDirWarned = true;
        CoopLog.warn(CoopSaveIndex.class,
                "Coop could not read CampaignEngine.getSaveDirName(), so save index rows carry no"
                        + " folder name; the launcher will fall back to matching the character name"
                        + " and in-game date against each save's descriptor.xml", ex);
    }

    /**
     * The creation-time character name.
     *
     * <p>The engine writes {@code descriptor.xml}'s {@code characterName} from
     * {@code CampaignEngine.getCharacterData().getName()}, which is the trimmed string the creation
     * screen was given and which the same setter splits into the player person's
     * {@code FullName(first, last)}. {@code getCharacterData()} is not on {@code SectorAPI} and its
     * return type is not an API type, so it cannot be reached through a method handle without naming
     * an engine class; the player person's full name is the same string by construction and is plain
     * public API.
     */
    static String currentCharacterName(SectorAPI sector) {
        try {
            return text(sector.getPlayerPerson().getName().getFullName());
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    static int currentLevel(SectorAPI sector) {
        try {
            return sector.getPlayerStats().getLevel();
        } catch (Exception | LinkageError ex) {
            return 0;
        }
    }

    static long currentGameDateTimestamp(SectorAPI sector) {
        try {
            CampaignClockAPI clock = sector.getClock();
            return clock == null ? 0L : clock.getTimestamp();
        } catch (Exception | LinkageError ex) {
            return 0L;
        }
    }

    static String currentGameDate(SectorAPI sector) {
        try {
            CampaignClockAPI clock = sector.getClock();
            return clock == null ? "" : text(clock.getDateString());
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    /**
     * The role this install was launched in. The launch settings rather than the live session, on
     * purpose: a host that saves before the guest connects is still the host's save, and the field is
     * there to tell the two machines' rows apart in a shared bug report.
     */
    static String currentRole() {
        try {
            return CoopNetStartupConfig.fromSystemProperties().role().name();
        } catch (Exception | LinkageError ex) {
            return "NONE";
        }
    }

    private static JSONObject readIndex() {
        SettingsAPI settings = settingsOrNull();
        if (settings == null) {
            return null;
        }
        try {
            if (!settings.fileExistsInCommon(COMMON_FILE)) {
                return null;
            }
            // putInWriteCache=false: the write below hands the engine a whole new object anyway.
            return settings.readJSONFromCommon(COMMON_FILE, false);
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopSaveIndex.class, "Coop save index " + COMMON_PATH
                    + " could not be read and is being rewritten from scratch; older rows in it are"
                    + " lost, which costs nothing but the launcher's memory of earlier saves", ex);
            return null;
        }
    }

    private static boolean writeIndex(JSONObject index) {
        SettingsAPI settings = settingsOrNull();
        if (settings == null || index == null) {
            return false;
        }
        try {
            settings.writeJSONToCommon(COMMON_FILE, index, true);
            return true;
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopSaveIndex.class, "Coop save index " + COMMON_PATH
                    + " could not be written", ex);
            return false;
        }
    }

    private static SectorAPI sectorOrNull() {
        try {
            return Global.getSector();
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private static SettingsAPI settingsOrNull() {
        try {
            return Global.getSettings();
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** Test seam: forget the resolved handle and the one-shot warning. */
    static synchronized void resetEngineHandlesForTest() {
        saveDirOwner = null;
        saveDirHandle = null;
        saveDirWarned = false;
        coopAutosaveDepth = 0;
    }

    /** Test seam: the keys an index carries, for a shape assertion without a JSON literal. */
    static List<String> keysOf(JSONObject entry) {
        List<String> keys = new ArrayList<>();
        if (entry == null) {
            return keys;
        }
        // keySet() does not exist in the game's 2010 org.json; keys() does.
        for (Iterator<?> it = entry.keys(); it.hasNext(); ) {
            keys.add(String.valueOf(it.next()));
        }
        return keys;
    }
}
