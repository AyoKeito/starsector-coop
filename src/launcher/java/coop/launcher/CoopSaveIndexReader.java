package coop.launcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The launcher's half of the co-op save index the mod writes at every save
 * ({@code coop.save.CoopSaveIndex}). It answers one question: for this campaign id, which save
 * folder on this machine should the player load?
 *
 * <p><b>Why a join and not just the index.</b> The index row carries the campaign id, which no save
 * folder does; {@code descriptor.xml} carries the character, the level and the save date, which the
 * index only has a copy of. The copy can be stale - the engine prunes autosaves to three, so a row
 * can name a folder that is gone, and the launcher must not offer it. So every row is stat'ed
 * against {@code <install>/saves/<saveDirName>}, rows whose folder has been pruned are dropped, and
 * for the ones that survive the descriptor wins on every display field.
 *
 * <p><b>Rows without a folder name.</b> {@code saveDirName} is omitted when the engine getter was
 * unreachable at save time. Those rows are matched to a folder by character name plus the in-game
 * timestamp, which is exact enough: two saves of the same character at the same in-game second are
 * the same save.
 *
 * <p><b>Version.</b> A file that says {@code version} 2 is not guessed at. The launcher says it
 * cannot read it and offers no save, which is a great deal better than naming the wrong one.
 *
 * <p>Nothing here throws for a bad file: every failure comes back as a {@link Status} and a sentence
 * for the window, because a save list that will not parse must never stop somebody launching.
 */
public final class CoopSaveIndexReader {

    /** The index file, under {@code <install>/saves/common}. The engine appends {@code .data}. */
    public static final String INDEX_FILE_NAME = "coop_saves.json.data";

    /** The one format this launcher knows how to read. */
    public static final int SUPPORTED_VERSION = 1;

    /** Path shown to the player when something is wrong with the file. */
    public static final String INDEX_DISPLAY_PATH = "saves/common/" + INDEX_FILE_NAME;

    private static final String KEY_VERSION = "version";
    private static final String KEY_SAVES = "saves";
    private static final String KEY_CAMPAIGN_ID = "campaignId";
    private static final String KEY_SAVE_DIR_NAME = "saveDirName";
    private static final String KEY_CHARACTER_NAME = "characterName";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_GAME_DATE_TIMESTAMP = "gameDateTimestamp";
    private static final String KEY_GAME_DATE = "gameDate";
    private static final String KEY_SAVED_AT_MILLIS = "savedAtMillis";
    private static final String KEY_AUTOSAVE = "autosave";
    private static final String KEY_ROLE = "role";
    private static final String KEY_SEED_STRING = "seedString";
    // Added by the mod on 2026-09-04, and optional for good: rows written before then have no
    // sector size or star age, and the version stayed at 1 precisely so both kinds keep parsing.
    private static final String KEY_SECTOR_SIZE = "sectorSize";
    private static final String KEY_SECTOR_AGE = "sectorAge";

    /** {@code 2026-05-28 18:16:01.129 UTC} - the shape {@code descriptor.xml} writes. */
    private static final DateTimeFormatter DESCRIPTOR_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** What the player is shown for a save date. Minutes: seconds tell nobody anything. */
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private CoopSaveIndexReader() {
    }

    /** How the read went. Only {@link #OK} carries saves. */
    public enum Status {
        /** The file was read and joined to the folders on disk. */
        OK,
        /** No index file: nobody has saved a co-op campaign on this install yet. */
        ABSENT,
        /** The file is there and will not parse, or says nothing this reader recognises. */
        UNREADABLE,
        /** The file is a format from a newer mod than this launcher. */
        TOO_NEW
    }

    /**
     * One save, after the join: index row plus whatever {@code descriptor.xml} had to say about it.
     * Every instance names a folder that existed when the index was read.
     *
     * @param autosave   {@code null} when neither the row nor the descriptor could tell
     * @param sectorSize the size the sector was generated at, {@code ""} on a row that does not
     *                   record it -- which every row written before 2026-09-04 is
     * @param sectorAge  the star age the sector was generated at, same rule
     */
    public record Save(String campaignId, String saveDirName, String characterName, int level,
                       String gameDate, long gameDateTimestamp, long savedAtMillis,
                       Boolean autosave, String role, String seedString, String sectorSize,
                       String sectorAge) {

        public Save {
            campaignId = text(campaignId);
            saveDirName = text(saveDirName);
            characterName = text(characterName);
            gameDate = text(gameDate);
            role = text(role).toUpperCase(Locale.ROOT);
            seedString = text(seedString);
            sectorSize = text(sectorSize).toLowerCase(Locale.ROOT);
            sectorAge = text(sectorAge).toLowerCase(Locale.ROOT);
        }

        /** True when this row knows both world settings, so the launcher can put them back. */
        public boolean hasWorldSettings() {
            return !sectorSize.isEmpty() && !sectorAge.isEmpty();
        }

        /** True for a save this player made as the host, or outside a session. */
        public boolean hostSide() {
            return !"GUEST".equals(role);
        }

        public boolean guestSide() {
            return "GUEST".equals(role);
        }

        /** The save date in the player's own time zone. */
        public String savedLocal(ZoneId zone) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(savedAtMillis), zone)
                    .format(DISPLAY_DATE);
        }

        /** The drop-down line: {@code Kaz Alba, level 12, Cycle 206, Kerenth 12, saved ...}. */
        public String label(ZoneId zone) {
            StringBuilder text = new StringBuilder();
            text.append(characterName.isEmpty() ? "unnamed character" : characterName);
            text.append(", level ").append(level);
            if (!gameDate.isEmpty()) {
                text.append(", ").append(gameDate);
            }
            text.append(", saved ").append(savedLocal(zone));
            return text.toString();
        }
    }

    /**
     * The result of a read: a status, a sentence for the window when something is off, and the saves
     * newest first.
     */
    public record Index(Status status, String problem, List<Save> saves) {

        public Index {
            problem = text(problem);
            saves = saves == null ? List.of() : List.copyOf(saves);
        }

        public static Index absent() {
            return new Index(Status.ABSENT, "", List.of());
        }

        /** True when the file was read; it may still hold no saves. */
        public boolean ok() {
            return status == Status.OK;
        }

        /** The newest surviving save for one campaign, or {@code null} when there is none. */
        public Save newestFor(String campaignId) {
            String wanted = text(campaignId);
            if (wanted.isEmpty()) {
                return null;
            }
            for (Save save : saves) {
                if (wanted.equals(save.campaignId())) {
                    return save;
                }
            }
            return null;
        }

        /**
         * The newest surviving save of every campaign this player could load as a host - which is
         * every campaign except the ones they only ever played as a guest. Newest campaign first.
         */
        public List<Save> newestPerHostCampaign() {
            Map<String, Save> byCampaign = new LinkedHashMap<>();
            for (Save save : saves) {
                if (!save.hostSide()) {
                    continue;
                }
                byCampaign.putIfAbsent(save.campaignId(), save);
            }
            return List.copyOf(byCampaign.values());
        }
    }

    /**
     * Reads {@code <savesRoot>/common/coop_saves.json.data} and joins it to the folders under
     * {@code savesRoot}. Never throws.
     *
     * @param savesRoot the install's {@code saves} folder
     */
    public static Index read(Path savesRoot) {
        Objects.requireNonNull(savesRoot, "savesRoot");
        Path file = savesRoot.resolve("common").resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Index.absent();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return new Index(Status.UNREADABLE, describe(ex), List.of());
        }
        return parse(text, savesRoot);
    }

    /**
     * The half of {@link #read} that has the file text in hand already. Still touches
     * {@code savesRoot} for the folder join, which is the point: a test points it at a temporary
     * directory holding fake save folders.
     */
    static Index parse(String text, Path savesRoot) {
        JSONObject json;
        try {
            json = new JSONObject(text == null ? "" : text);
        } catch (Exception ex) {
            return new Index(Status.UNREADABLE, describe(ex), List.of());
        }
        if (!json.has(KEY_VERSION)) {
            return new Index(Status.UNREADABLE, "it has no \"version\" field", List.of());
        }
        Integer version = asInt(json.opt(KEY_VERSION));
        if (version == null) {
            return new Index(Status.UNREADABLE,
                    "its \"version\" is not a number", List.of());
        }
        if (version > SUPPORTED_VERSION) {
            return new Index(Status.TOO_NEW, "it is version " + version + "; this launcher reads"
                    + " version " + SUPPORTED_VERSION, List.of());
        }
        JSONArray rows = json.optJSONArray(KEY_SAVES);
        if (rows == null) {
            return new Index(Status.UNREADABLE, "it has no \"saves\" list", List.of());
        }

        List<Row> parsed = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            Row one = row(row);
            if (one != null) {
                parsed.add(one);
            }
        }

        boolean needsFallback = false;
        for (Row row : parsed) {
            if (row.saveDirName.isEmpty()) {
                needsFallback = true;
                break;
            }
        }
        Map<String, Descriptor> descriptors = needsFallback
                ? readAllDescriptors(savesRoot)
                : new LinkedHashMap<>();

        List<Save> joined = new ArrayList<>();
        for (Row row : parsed) {
            String folder = row.saveDirName;
            Descriptor descriptor;
            if (folder.isEmpty()) {
                folder = matchByCharacterAndDate(descriptors, row.characterName,
                        row.gameDateTimestamp);
                if (folder == null) {
                    continue;
                }
                descriptor = descriptors.get(folder);
            } else {
                Path directory = savesRoot.resolve(folder);
                if (!Files.isDirectory(directory)) {
                    // The engine prunes autosaves to three; a row can outlive its folder. Offering
                    // it would send the player looking for a slot the game does not show.
                    continue;
                }
                descriptor = readDescriptor(directory);
            }
            joined.add(merge(row, folder, descriptor));
        }

        // The mod writes newest first, but a hand-edited or half-written file need not, and every
        // "newest" answer below depends on this order.
        joined.sort((left, right) -> {
            int byTime = Long.compare(right.savedAtMillis(), left.savedAtMillis());
            return byTime != 0 ? byTime : left.saveDirName().compareTo(right.saveDirName());
        });
        return new Index(Status.OK, "", joined);
    }

    /** An index row before the folder join. */
    private record Row(String campaignId, String saveDirName, String characterName, int level,
                       String gameDate, long gameDateTimestamp, long savedAtMillis,
                       Boolean autosave, String role, String seedString, String sectorSize,
                       String sectorAge) {
    }

    private static Row row(JSONObject json) {
        String campaignId = text(json.optString(KEY_CAMPAIGN_ID, ""));
        if (campaignId.isEmpty()) {
            // A row with no campaign id is what a solo save would look like; there is nothing an
            // invite could match it against.
            return null;
        }
        Boolean autosave = null;
        if (json.has(KEY_AUTOSAVE)) {
            Object value = json.opt(KEY_AUTOSAVE);
            if (value instanceof Boolean flag) {
                autosave = flag;
            } else if (value != null) {
                String written = String.valueOf(value).trim();
                if (written.equalsIgnoreCase("true") || written.equalsIgnoreCase("false")) {
                    autosave = Boolean.valueOf(written.equalsIgnoreCase("true"));
                }
            }
        }
        return new Row(campaignId,
                text(json.optString(KEY_SAVE_DIR_NAME, "")),
                text(json.optString(KEY_CHARACTER_NAME, "")),
                json.optInt(KEY_LEVEL, 0),
                text(json.optString(KEY_GAME_DATE, "")),
                json.optLong(KEY_GAME_DATE_TIMESTAMP, 0L),
                json.optLong(KEY_SAVED_AT_MILLIS, 0L),
                autosave,
                text(json.optString(KEY_ROLE, "")),
                text(json.optString(KEY_SEED_STRING, "")),
                text(json.optString(KEY_SECTOR_SIZE, "")),
                text(json.optString(KEY_SECTOR_AGE, "")));
    }

    /** Index row plus descriptor, the descriptor winning every field it has an answer for. */
    private static Save merge(Row row, String folder, Descriptor descriptor) {
        String character = row.characterName;
        int level = row.level;
        long savedAt = row.savedAtMillis;
        Boolean autosave = row.autosave;
        if (descriptor != null) {
            if (!descriptor.characterName().isEmpty()) {
                character = descriptor.characterName();
            }
            if (descriptor.level() > 0) {
                level = descriptor.level();
            }
            if (descriptor.savedAtMillis() > 0L) {
                savedAt = descriptor.savedAtMillis();
            }
            if (descriptor.autosave() != null) {
                autosave = descriptor.autosave();
            }
        }
        return new Save(row.campaignId, folder, character, level, row.gameDate,
                row.gameDateTimestamp, savedAt, autosave, row.role, row.seedString, row.sectorSize,
                row.sectorAge);
    }

    /**
     * The folder holding a save of {@code characterName} at exactly {@code gameDateTimestamp}, or
     * {@code null}. Used only for rows the mod could not name a folder for.
     */
    private static String matchByCharacterAndDate(Map<String, Descriptor> descriptors,
                                                  String characterName, long gameDateTimestamp) {
        if (characterName.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Descriptor> entry : descriptors.entrySet()) {
            Descriptor descriptor = entry.getValue();
            if (descriptor.gameDateTimestamp() == gameDateTimestamp
                    && characterName.equals(descriptor.characterName())) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---- descriptor.xml -----------------------------------------------------------------------

    /**
     * The handful of fields the launcher wants out of a save folder's {@code descriptor.xml}.
     *
     * @param savedAtMillis the {@code saveDate} as epoch millis, or 0 when it would not parse
     * @param autosave      {@code null} when the file did not say
     */
    public record Descriptor(String characterName, int level, long gameDateTimestamp,
                             long savedAtMillis, String locDesc, Boolean autosave) {

        public Descriptor {
            characterName = text(characterName);
            locDesc = text(locDesc);
        }
    }

    /** Reads {@code <folder>/descriptor.xml}, or {@code null} when it is not there or not readable. */
    public static Descriptor readDescriptor(Path saveFolder) {
        Path file = saveFolder.resolve("descriptor.xml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return parseDescriptor(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Parses one {@code descriptor.xml}, or returns {@code null} when it is not one.
     *
     * <p>Only the direct children of the root are looked at. The file nests a whole mod list inside
     * itself, and a mod called {@code characterName} would otherwise be able to name the save.
     */
    public static Descriptor parseDescriptor(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A save folder is player-supplied data. No DTDs, no entity expansion, no network.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return null;
        }
        Element root = document.getDocumentElement();
        if (root == null) {
            return null;
        }
        String characterName = childText(root, "characterName");
        String levelText = childText(root, "characterLevel");
        String autosaveText = childText(root, "autosave");
        Element gameDate = child(root, "gameDate");
        String timestampText = gameDate == null ? "" : childText(gameDate, "timestamp");
        Descriptor descriptor = new Descriptor(characterName,
                (int) parseLong(levelText, 0L),
                parseLong(timestampText, 0L),
                parseSaveDate(childText(root, "saveDate")),
                childText(root, "locDesc"),
                autosaveText.isEmpty() ? null : Boolean.valueOf(autosaveText.equalsIgnoreCase("true")));
        if (descriptor.characterName().isEmpty() && descriptor.gameDateTimestamp() == 0L
                && descriptor.savedAtMillis() == 0L) {
            // Well-formed XML that is not a save descriptor. Pretending it is would let a stray file
            // win over the index row it is meant to confirm.
            return null;
        }
        return descriptor;
    }

    /** {@code 2026-05-28 18:16:01.129 UTC} as epoch millis, or 0 when it will not parse. */
    static long parseSaveDate(String value) {
        String trimmed = text(value);
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace <= 0) {
            return 0L;
        }
        String stamp = trimmed.substring(0, lastSpace);
        String zoneName = trimmed.substring(lastSpace + 1);
        try {
            ZoneId zone = ZoneId.of(zoneName, ZoneId.SHORT_IDS);
            return LocalDateTime.parse(stamp, DESCRIPTOR_DATE).atZone(zone).toInstant()
                    .toEpochMilli();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /** Every {@code save_*} folder under {@code savesRoot} that has a readable descriptor. */
    private static Map<String, Descriptor> readAllDescriptors(Path savesRoot) {
        Map<String, Descriptor> found = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                Descriptor descriptor = readDescriptor(entry);
                if (descriptor != null) {
                    found.put(entry.getFileName().toString(), descriptor);
                }
            }
        } catch (IOException | RuntimeException ex) {
            return found;
        }
        return found;
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : text(element.getTextContent());
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(text(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
