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
 * <p><b>Those two files fail closed.</b> Either the edit is proven to have worked - the blanked
 * settings file is re-parsed and asserted to hold no password, the scrubbed {@code vmparams} is
 * asserted to contain neither the string {@code coop.password} nor the value the settings file held
 * - or the file is left out of the archive entirely and a note says why. The earlier behaviour was
 * to pack such a file verbatim under a note telling the player to check it by hand, which is a
 * plain-text password sitting in an archive that is about to be posted on a public forum, guarded
 * by nothing but a line of {@code report.txt} the player has to read first. A missing settings file
 * costs a support answer; a leaked password costs the session.
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
     * A {@code -Dcoop.password=...} token plus the spaces in front of it, in all three spellings a
     * player can end up with: {@code ="two words"}, {@code ='two words'} and a bare
     * whitespace-delimited value.
     *
     * <p>The two quoted forms are the reason this is not just {@code \S*}. The launcher never writes
     * this property itself - it puts the password in {@code saves/common/coop_options.json.data} -
     * so every one in a {@code vmparams} was typed or pasted by a person, and a person with a
     * password that has a space in it quotes it. Against the old whitespace-delimited pattern
     * {@code -Dcoop.password="two words"} left {@code words"} on the line: the second half of the
     * password, packed into an archive under a note claiming the password had been removed.
     *
     * <p>Each quoted alternative stops at a line break as well as at its closing quote, so an
     * unterminated quote deletes one token rather than the whole rest of the file. The alternation
     * is ordered: the quoted forms are tried before the bare one, which would otherwise match only
     * the first word of a quoted value. The leading {@code [ \t]*} keeps the line from ending up
     * with a double space; it deliberately does not match a newline, so a token at the start of a
     * line cannot swallow the break above it.
     */
    private static final Pattern VMPARAMS_PASSWORD = Pattern.compile(
            "[ \\t]*-Dcoop\\.password=(?:\"[^\"\\r\\n]*\"?|'[^'\\r\\n]*'?|\\S*)");

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

        // Ordered: the settings file is the only place the launcher stores the password, so reading
        // it first is what lets the vmparams scrub prove that same string is not still on the line.
        String optionsPassword = packOptions(planned, missing, notes, layout.coopOptions());
        packVmparams(planned, missing, notes, layout.vmparams(), optionsPassword);

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
    /**
     * The {@code coop.password} value in a settings file, or {@code ""} when there is none and when
     * the text does not parse. The value itself is needed twice: to decide whether a file that
     * could not be blanked is dangerous, and to prove afterwards that the same string is not still
     * sitting in the packed {@code vmparams} in some spelling this class did not think of.
     *
     * <p>Never logged and never put in a note. It only ever gets compared against.
     */
    static String passwordIn(String json) {
        if (json == null) {
            return "";
        }
        try {
            JSONObject parsed = new JSONObject(json);
            return parsed.optString(CoopLauncherConfig.PASSWORD, "");
        } catch (Exception ex) {
            // org.json in starsector-core throws a checked JSONException, so this has to be broad.
            return "";
        }
    }

    /**
     * True when a rewritten settings file is provably safe to pack: it parses, and the password key
     * is either gone or empty. Proof rather than trust - {@link #blankPassword} builds its answer
     * through org.json, and a settings file is the one file in the archive whose contents are a
     * secret, so "the rewrite did not throw" is not enough to post it in public on.
     */
    static boolean passwordIsBlanked(String blankedJson) {
        if (blankedJson == null) {
            return false;
        }
        try {
            JSONObject parsed = new JSONObject(blankedJson);
            return parsed.optString(CoopLauncherConfig.PASSWORD, "").isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Why a settings file does not parse, for the note that says it was left out, or {@code null}
     * when it parses fine. The parser's own message names the character it gave up at, which is the
     * one thing that lets a player find the typo in a file the archive no longer carries.
     */
    static String jsonParseError(String json) {
        if (json == null) {
            return "no text";
        }
        try {
            new JSONObject(json);
            return null;
        } catch (Exception ex) {
            String message = ex.getMessage();
            return message == null || message.isEmpty() ? ex.getClass().getSimpleName() : message;
        }
    }

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

    /**
     * Why a scrubbed {@code vmparams} must not be packed, or {@code null} when it is clean.
     *
     * <p>Two checks, both deliberately blunt. The string {@code coop.password} must not survive at
     * all: whatever spelling put it there is one {@link #VMPARAMS_PASSWORD} did not remove, and a
     * pattern that did not match is exactly the case where the value is still on the line. And the
     * password the settings file holds must not appear anywhere in the text, whatever it is attached
     * to - the same secret pasted into a second property, a launch script path, a comment.
     *
     * <p>The second check can fire on a password that happens to be a substring of something
     * innocent ({@code 2048}, say, against {@code -Xmx2048m}). That costs a {@code vmparams} in one
     * bug report; the other way round costs a password in a public thread, so the false positive is
     * the side to be wrong on.
     *
     * @param scrubbed the text as it would be packed
     * @param password the settings file's password, or {@code ""} when none is known
     */
    static String vmparamsLeak(String scrubbed, String password) {
        if (scrubbed == null) {
            return "there was nothing to pack";
        }
        if (scrubbed.contains("coop.password")) {
            return "coop.password is still on the line after the scrub, in a spelling the launcher"
                    + " does not know how to remove";
        }
        if (password != null && !password.isEmpty() && scrubbed.contains(password)) {
            return "the password from your settings file still appears in it";
        }
        return null;
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
        // The forked engine jar is a second build with its own manifest, and a report that only
        // names coop.jar cannot show the one mix-up that produces unexplainable behaviour.
        String forksVersion = CoopInstallCheck.readJarAttribute(layout.forksJar(),
                "Implementation-Version");
        String forksCommit = CoopInstallCheck.readJarAttribute(layout.forksJar(),
                CoopInstallCheck.GIT_COMMIT_ATTRIBUTE);
        line(text, "Forks version", forksVersion == null
                ? "unknown (coop-forks.jar has no manifest version)"
                : forksVersion + (forksCommit == null ? "" : " (commit " + forksCommit + ")"));
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

        text.append("\r\nWhat was taken out, and what was left out\r\n");
        if (notes.isEmpty()) {
            text.append("  nothing; there was no password in any packed file\r\n");
        } else {
            for (String note : notes) {
                text.append("  ").append(note).append("\r\n");
            }
        }

        text.append("\r\nNo file in this archive carries your lobby password. The settings file and"
                + " vmparams are packed only when the password was provably removed from them, and"
                + " left out with a line above when it could not be.\r\n");
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

    /**
     * Packs the settings file, or leaves it out when the password in it cannot be proven gone.
     *
     * @return the password the file holds, for {@link #packVmparams} to check its own text against,
     *         or {@code ""} when the file has none or could not be read at all
     */
    private static String packOptions(List<Planned> planned, List<String> missing,
                                      List<String> notes, File options) {
        String entry = "saves/common/coop_options.json.data";
        if (options == null || !options.isFile()) {
            missing.add(entry);
            return "";
        }
        String text = CoopInstallCheck.readTextOrNull(options);
        if (text == null) {
            // There but unreadable - locked by another process, or no permission. Packing it would
            // put an empty entry in the archive under a note saying it was packed verbatim.
            missing.add(entry + " (unreadable)");
            return "";
        }
        String parseError = jsonParseError(text);
        if (parseError != null) {
            // The file the report is about, and the file that cannot be scrubbed, are the same one.
            // It is left out: this launcher cannot read what is in it, so it cannot promise the
            // password is not, and the whole point of a settings file is that it stores one.
            notes.add(entry + " was LEFT OUT of the archive: it does not parse as JSON ("
                    + parseError + "), so coop.password could not be removed from it. If the"
                    + " settings matter to your report, take the password out by hand and attach"
                    + " the file yourself.");
            return "";
        }
        String password = passwordIn(text);
        String blanked = blankPassword(text);
        if (blanked == null) {
            // Two ways to get here, and only one of them is harmless: the file has no password key
            // at all, or the rewrite failed on a file that does. The second is not packed, because
            // the password is stored there in plain text.
            if (password.isEmpty()) {
                planned.add(new Planned(entry, options, null));
                return "";
            }
            notes.add(entry + " was LEFT OUT of the archive: coop.password could not be blanked in"
                    + " it. Take the password out by hand and attach the file yourself if you need"
                    + " it in the report.");
            return password;
        }
        if (!passwordIsBlanked(blanked)) {
            notes.add(entry + " was LEFT OUT of the archive: the blanked copy still had a"
                    + " coop.password in it, which should be impossible - do not post the original"
                    + " without taking the password out first.");
            return password;
        }
        notes.add("coop.password was blanked in the packed copy of " + entry + ".");
        planned.add(new Planned(entry, null, blanked.getBytes(StandardCharsets.UTF_8)));
        return password;
    }

    /**
     * Packs {@code vmparams} with every {@code -Dcoop.password} token removed, or leaves it out when
     * the scrubbed text cannot be proven clean.
     *
     * @param optionsPassword the settings file's password, so a copy of it under some other name on
     *                        this line is caught too; {@code ""} when none is known
     */
    private static void packVmparams(List<Planned> planned, List<String> missing, List<String> notes,
                                     File vmparams, String optionsPassword) {
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
        String leak = vmparamsLeak(scrub.text(), optionsPassword);
        if (leak != null) {
            notes.add("vmparams was LEFT OUT of the archive: " + leak + ". Post the classpath line"
                    + " by hand with the secret taken out if the report needs it.");
            return;
        }
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
