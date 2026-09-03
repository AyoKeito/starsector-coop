package coop.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

/**
 * Packs one zip with everything a co-op bug report needs, so the player does not have to know which
 * of nine files under two folders the answer is in.
 *
 * <p>Two things get edited on the way in and nothing else does: {@code coop.password} is blanked in
 * the packed copy of the settings file, and any {@code -Dcoop.password=} token is dropped from the
 * packed copy of {@code vmparams}. Both edits are noted in {@code report.txt}, because a player who
 * is about to post an archive in public should be able to see what was taken out of it. Everything
 * else - the logs above all - is packed byte for byte; a report that has been tidied up is a report
 * that no longer shows the bug.
 *
 * <p>The archive still carries the host's public address, which is in the connection doctor block by
 * design. That is said out loud in the status pane rather than scrubbed: the address is the single
 * most useful line in a connectivity report.
 *
 * <p>Logs are streamed in 64 KB chunks and opened with shared access, so a 50 MB
 * {@code starsector.log} that the running game is still writing to packs without either loading it
 * whole or locking the game out of it.
 */
public final class CoopBugReport {

    /** How long a save folder has to have been quiet before it is safe to copy. */
    static final long SAVE_QUIET_MILLIS = 5000L;

    /** Save folder names all start with this; the rest is the campaign id. */
    static final String SAVE_PREFIX = "save_";

    /** The two files the engine writes last when it saves, and so the two worth timing. */
    static final List<String> SAVE_MARKER_FILES = List.of("descriptor.xml", "campaign.xml");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * A {@code -Dcoop.password=...} token plus the spaces in front of it. Whitespace-delimited on
     * purpose: vmparams is one long line of tokens and the value never contains a space, so this is
     * the whole token and nothing next to it. The leading {@code [ \t]*} keeps the line from ending
     * up with a double space; it deliberately does not match a newline, so a token at the start of a
     * line cannot swallow the break above it.
     */
    private static final Pattern VMPARAMS_PASSWORD =
            Pattern.compile("[ \\t]*-Dcoop\\.password=\\S*");

    /** What a finished report contains, for the status pane and for the tests. */
    public record Result(File zip,
                         List<String> entries,
                         List<String> missing,
                         List<String> notes,
                         boolean saveInFlight) {
        public Result {
            Objects.requireNonNull(zip, "zip");
            entries = List.copyOf(entries);
            missing = List.copyOf(missing);
            notes = List.copyOf(notes);
        }
    }

    /** One file on its way into the archive. {@code bytes} is null when it is copied from disk. */
    private record Planned(String entry, File source, byte[] bytes) {
    }

    private CoopBugReport() {
    }

    /** Writes the report to the desktop (or the install root) using the wall clock. */
    public static Result write(CoopInstallLayout layout, String role, boolean includeSave)
            throws IOException {
        return write(layout, role, includeSave, defaultOutputFolder(layout),
                System::currentTimeMillis);
    }

    /**
     * Test seam: the output folder and the clock are given. The clock drives both the file name and
     * the save-in-flight guard, so a test can put a save's modification time wherever it wants.
     */
    static Result write(CoopInstallLayout layout, String role, boolean includeSave,
                        File outputFolder, LongSupplier clock) throws IOException {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(outputFolder, "outputFolder");
        long now = clock.getAsLong();
        String roleTag = roleTag(role);
        String stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(STAMP);
        File zipFile = new File(outputFolder, "coop-report-" + roleTag + "-" + stamp + ".zip");

        List<Planned> planned = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        String modFolder = "mods/" + layout.modRoot().getName() + "/";

        plainFile(planned, missing, "starsector-core/starsector.log", layout.starsectorLog());
        plainFile(planned, missing, "starsector-core/starsector.log.1", rolledLog(layout));
        plainFile(planned, missing, modFolder + "coop-launcher.log", layout.launcherLog());
        plainFile(planned, missing, modFolder + "mod_info.json", layout.modInfo());
        plainFile(planned, missing, "mods/enabled_mods.json", layout.enabledMods());

        packOptions(planned, missing, notes, layout.coopOptions());
        packVmparams(planned, missing, notes, layout.vmparams());

        boolean saveInFlight = false;
        File save = includeSave ? newestSave(layout.saves()) : null;
        if (includeSave && save == null) {
            missing.add("saves/save_* (no save folder found)");
        } else if (save != null) {
            saveInFlight = saveInFlight(save, now);
            if (saveInFlight) {
                notes.add("The newest save (" + save.getName() + ") was still being written, so it"
                        + " was left out. Run the report again in a moment to include it.");
            } else {
                for (String name : SAVE_MARKER_FILES) {
                    if (!new File(save, name).isFile()) {
                        notes.add("The newest save has no " + name + ", which is unusual. It was"
                                + " packed anyway.");
                    }
                }
                packFolder(planned, "saves/" + save.getName(), save);
            }
        }

        Doctor doctor = readDoctor(layout);
        byte[] report = reportText(layout, roleTag, now, planned, missing, notes, doctor, save,
                includeSave, saveInFlight).getBytes(StandardCharsets.UTF_8);

        List<String> entries = new ArrayList<>();
        entries.add("report.txt");
        File parent = zipFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()))) {
            writeEntry(zip, "report.txt", report, now);
            for (Planned item : planned) {
                if (item.bytes() != null) {
                    writeEntry(zip, item.entry(), item.bytes(), now);
                } else {
                    copyEntry(zip, item.entry(), item.source());
                }
                entries.add(item.entry());
            }
        }
        return new Result(zipFile, entries, missing, notes, saveInFlight);
    }

    // ---- the folder the zip lands in --------------------------------------------------------

    /** The player's desktop when there is one, otherwise the install root. */
    static File defaultOutputFolder(CoopInstallLayout layout) {
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            File desktop = new File(home, "Desktop");
            if (desktop.isDirectory()) {
                return desktop;
            }
        }
        return layout.installRoot();
    }

    static String roleTag(String role) {
        String trimmed = role == null ? "" : role.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    // ---- scrubbing ---------------------------------------------------------------------------

    /**
     * The settings file with {@code coop.password} rewritten to an empty string, or {@code null}
     * when there was nothing to blank - either the key is absent or the text does not parse. Both
     * of those mean "pack the file exactly as it is": a settings file that will not parse is
     * precisely the settings file a bug report is about, and rewriting it would hide the bug.
     */
    static String blankPassword(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject parsed = new JSONObject(json);
            if (!parsed.has(CoopLauncherConfig.PASSWORD)) {
                return null;
            }
            parsed.put(CoopLauncherConfig.PASSWORD, "");
            return parsed.toString(2);
        } catch (Exception ex) {
            // org.json in starsector-core throws a checked JSONException, so this has to be broad.
            return null;
        }
    }

    /** True when the text parses as JSON at all, whatever keys it holds. */
    static boolean parsesAsJson(String json) {
        if (json == null) {
            return false;
        }
        try {
            new JSONObject(json);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** A vmparams line with every {@code -Dcoop.password=} token taken out. */
    record VmparamsScrub(String text, int removed) {
    }

    static VmparamsScrub scrubVmparams(String text) {
        if (text == null) {
            return new VmparamsScrub("", 0);
        }
        Matcher matcher = VMPARAMS_PASSWORD.matcher(text);
        StringBuilder out = new StringBuilder();
        int removed = 0;
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            last = matcher.end();
            removed++;
        }
        out.append(text, last, text.length());
        return new VmparamsScrub(out.toString(), removed);
    }

    // ---- saves ---------------------------------------------------------------------------------

    /** The most recently modified {@code save_*} folder, or {@code null} when there is none. */
    static File newestSave(File savesDir) {
        if (savesDir == null || !savesDir.isDirectory()) {
            return null;
        }
        File[] children = savesDir.listFiles();
        if (children == null) {
            return null;
        }
        return Arrays.stream(children)
                .filter(File::isDirectory)
                .filter(child -> child.getName().startsWith(SAVE_PREFIX))
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    /**
     * True when the engine looks like it is still writing this save. Only the two files that exist
     * are timed: a folder missing one of them is odd rather than in flight, and is reported as such.
     */
    static boolean saveInFlight(File saveDir, long now) {
        for (String name : SAVE_MARKER_FILES) {
            File file = new File(saveDir, name);
            if (file.isFile() && now - file.lastModified() <= SAVE_QUIET_MILLIS) {
                return true;
            }
        }
        return false;
    }

    // ---- the doctor lines ----------------------------------------------------------------------

    /** The two things worth lifting out of the game log, and where they were found. */
    record Doctor(String markerLine, String markerSource, List<String> block, String blockSource) {
        Doctor {
            markerLine = markerLine == null ? "" : markerLine;
            markerSource = markerSource == null ? "" : markerSource;
            block = block == null ? List.of() : List.copyOf(block);
            blockSource = blockSource == null ? "" : blockSource;
        }
    }

    /** Scans {@code starsector.log}, then {@code starsector.log.1} for whatever is still missing. */
    static Doctor readDoctor(CoopInstallLayout layout) {
        File current = layout.starsectorLog();
        File rolled = rolledLog(layout);
        Scan first = scan(current);
        String marker = first.lastMarker();
        List<String> block = first.lastBlock();
        String markerSource = marker.isEmpty() ? "" : current.getName();
        String blockSource = block.isEmpty() ? "" : current.getName();
        if ((marker.isEmpty() || block.isEmpty()) && rolled.isFile()) {
            Scan older = scan(rolled);
            if (marker.isEmpty() && !older.lastMarker().isEmpty()) {
                marker = older.lastMarker();
                markerSource = rolled.getName();
            }
            if (block.isEmpty() && !older.lastBlock().isEmpty()) {
                block = older.lastBlock();
                blockSource = rolled.getName();
            }
        }
        return new Doctor(marker, markerSource, block, blockSource);
    }

    static File rolledLog(CoopInstallLayout layout) {
        return new File(layout.starsectorCore(), "starsector.log.1");
    }

    /** Streams a log through a {@link Scan}. A missing or unreadable file yields an empty scan. */
    static Scan scan(File log) {
        Scan scan = new Scan();
        if (log == null || !log.isFile()) {
            return scan;
        }
        try (InputStream stream = new FileInputStream(log);
             BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                scan.accept(line);
            }
        } catch (IOException | RuntimeException ex) {
            // A log that cannot be read is reported as "not found" rather than failing the report.
            // The report is what a player falls back to when everything else has already gone wrong.
        }
        scan.finish();
        return scan;
    }

    /**
     * Keeps the last {@code [COOP-DOCTOR]} line and the last connection doctor block seen in a
     * stream of log lines, and nothing else - a rolled log is 50 MB and this runs over all of it.
     *
     * <p>Block boundaries are {@link CoopLogTail}'s: a block starts at the header line and ends at
     * the next real log line, at a blank line, or after {@link CoopLogTail#MAX_DOCTOR_BLOCK_LINES}
     * body lines. The blank line the block is nominally delimited by is not always there.
     */
    static final class Scan {

        private String lastMarker = "";
        private List<String> lastBlock = List.of();
        private List<String> current;

        void accept(String line) {
            if (line == null) {
                return;
            }
            if (current != null) {
                boolean ends = line.isBlank() || CoopLogTail.isLogLineStart(line)
                        || current.size() > CoopLogTail.MAX_DOCTOR_BLOCK_LINES;
                if (!ends) {
                    current.add(line);
                    return;
                }
                closeBlock();
            }
            if (line.contains(CoopLogTail.DOCTOR_HEADER)) {
                current = new ArrayList<>();
                current.add(line);
            }
            if (line.contains(CoopLogTail.DOCTOR_MARKER)) {
                lastMarker = line;
            }
        }

        void finish() {
            closeBlock();
        }

        String lastMarker() {
            return lastMarker;
        }

        List<String> lastBlock() {
            return lastBlock;
        }

        private void closeBlock() {
            if (current == null) {
                return;
            }
            lastBlock = List.copyOf(current);
            current = null;
        }
    }

    // ---- report.txt ----------------------------------------------------------------------------

    private static String reportText(CoopInstallLayout layout, String roleTag, long now,
                                     List<Planned> planned, List<String> missing,
                                     List<String> notes, Doctor doctor, File save,
                                     boolean includeSave, boolean saveInFlight) {
        StringBuilder text = new StringBuilder();
        text.append("Starsector co-op bug report\r\n");
        text.append("===========================\r\n\r\n");
        line(text, "Written", LocalDateTime.ofInstant(Instant.ofEpochMilli(now),
                ZoneId.systemDefault()).format(HUMAN_TIME));
        line(text, "Role", roleTag);
        String modVersion = CoopInstallCheck.readJarAttribute(layout.coopJar(),
                "Implementation-Version");
        String modCommit = CoopInstallCheck.readJarAttribute(layout.coopJar(), "Coop-Git-Commit");
        line(text, "Mod version", modVersion == null ? "unknown (coop.jar has no manifest version)"
                : modVersion + (modCommit == null ? "" : " (commit " + modCommit + ")"));
        line(text, "Launcher version", CoopInstallCheck.launcherVersion());
        String builtFor = CoopInstallCheck.readModInfoGameVersion(layout.modInfo());
        String gameVersion = CoopInstallCheck.readGameVersion(layout);
        line(text, "Game version", (gameVersion == null
                ? "unknown (no launcher line in the logs)" : gameVersion)
                + (builtFor == null ? "" : ", mod built for " + builtFor));
        line(text, "Windows", System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "unknown"));
        line(text, "Java", System.getProperty("java.version", "unknown") + " "
                + System.getProperty("java.vendor", ""));
        line(text, "Install root", layout.installRoot().getPath());
        line(text, "Newest save", save == null
                ? (includeSave ? "none found" : "not included, the player unticked it")
                : save.getName() + (saveInFlight ? " (skipped, still being written)" : ""));

        text.append("\r\nLast [COOP-DOCTOR] line\r\n");
        if (doctor.markerLine().isEmpty()) {
            text.append("  not found in starsector.log or starsector.log.1\r\n");
        } else {
            text.append("  (from ").append(doctor.markerSource()).append(")\r\n");
            text.append("  ").append(doctor.markerLine()).append("\r\n");
        }

        text.append("\r\nLast \"").append(CoopLogTail.DOCTOR_HEADER).append("\" block\r\n");
        if (doctor.block().isEmpty()) {
            text.append("  not found in starsector.log or starsector.log.1\r\n");
        } else {
            text.append("  (from ").append(doctor.blockSource()).append(")\r\n");
            for (String blockLine : doctor.block()) {
                text.append("  ").append(blockLine).append("\r\n");
            }
        }

        text.append("\r\nFiles packed\r\n");
        for (Planned item : planned) {
            long size = item.bytes() != null ? item.bytes().length : item.source().length();
            text.append("  ").append(item.entry()).append("  ").append(size).append(" bytes\r\n");
        }

        text.append("\r\nNot packed, not on this install\r\n");
        if (missing.isEmpty()) {
            text.append("  nothing was missing\r\n");
        } else {
            for (String name : missing) {
                text.append("  ").append(name).append("\r\n");
            }
        }

        text.append("\r\nWhat was taken out\r\n");
        if (notes.isEmpty()) {
            text.append("  nothing; there was no password in any packed file\r\n");
        } else {
            for (String note : notes) {
                text.append("  ").append(note).append("\r\n");
            }
        }

        text.append("\r\nThis archive still holds your public address, which is in the doctor block"
                + " on purpose. Both players should attach their own zip.\r\n");
        return text.toString();
    }

    /** One aligned {@code label value} line. CRLF throughout: this file gets opened in Notepad. */
    private static void line(StringBuilder text, String label, String value) {
        text.append(label);
        for (int i = label.length(); i < 18; i++) {
            text.append(' ');
        }
        text.append(' ').append(value).append("\r\n");
    }

    // ---- packing -------------------------------------------------------------------------------

    private static void plainFile(List<Planned> planned, List<String> missing, String entry,
                                  File source) {
        if (source != null && source.isFile()) {
            planned.add(new Planned(entry, source, null));
        } else {
            missing.add(entry);
        }
    }

    private static void packOptions(List<Planned> planned, List<String> missing, List<String> notes,
                                    File options) {
        String entry = "saves/common/coop_options.json.data";
        if (options == null || !options.isFile()) {
            missing.add(entry);
            return;
        }
        String text = CoopInstallCheck.readTextOrNull(options);
        if (!parsesAsJson(text)) {
            notes.add(entry + " does not parse as JSON, so it was packed exactly as it is. Check it"
                    + " by hand for a password before you post the archive.");
            planned.add(new Planned(entry, options, null));
            return;
        }
        String blanked = blankPassword(text);
        if (blanked == null) {
            planned.add(new Planned(entry, options, null));
            return;
        }
        notes.add("coop.password was blanked in the packed copy of " + entry + ".");
        planned.add(new Planned(entry, null, blanked.getBytes(StandardCharsets.UTF_8)));
    }

    private static void packVmparams(List<Planned> planned, List<String> missing, List<String> notes,
                                     File vmparams) {
        String entry = "vmparams";
        if (vmparams == null || !vmparams.isFile()) {
            missing.add(entry);
            return;
        }
        String text = CoopInstallCheck.readTextOrNull(vmparams);
        if (text == null) {
            missing.add(entry + " (unreadable)");
            return;
        }
        VmparamsScrub scrub = scrubVmparams(text);
        if (scrub.removed() == 0) {
            planned.add(new Planned(entry, vmparams, null));
            return;
        }
        notes.add(scrub.removed() + " -Dcoop.password token(s) were removed from the packed copy of"
                + " vmparams.");
        planned.add(new Planned(entry, null, scrub.text().getBytes(StandardCharsets.UTF_8)));
    }

    private static void packFolder(List<Planned> planned, String entryPrefix, File folder) {
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            String entry = entryPrefix + "/" + child.getName();
            if (child.isDirectory()) {
                packFolder(planned, entry, child);
            } else if (child.isFile() && !child.getName().endsWith(".bak")) {
                // The game keeps the previous save as campaign.xml.bak / descriptor.xml.bak next to
                // the live files. Packing them doubles the archive for a save that is one step old.
                planned.add(new Planned(entry, child, null));
            }
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes, long now)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(now);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void copyEntry(ZipOutputStream zip, String name, File source) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(source.lastModified());
        zip.putNextEntry(entry);
        // FileInputStream opens with FILE_SHARE_READ|FILE_SHARE_WRITE on Windows, so the running
        // game keeps writing its log while this reads it. Chunked because starsector.log rolls at
        // 50 MB and must never be held whole in memory.
        try (InputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        } catch (IOException ex) {
            // The file went away or the game holds it in a way this JVM cannot read. An empty entry
            // with the name still in it beats losing the whole report.
        }
        zip.closeEntry();
    }
}
