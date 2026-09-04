package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The install rows the launcher shows. Nothing here writes anything: a row that can be put right
 * automatically carries a {@link CoopInstallFixer.Target} and the window hangs a Fix button off it,
 * which is where the writing happens. Keeping the verdict and the edit in separate classes is what
 * lets the whole verdict half stay a pure function.
 *
 * <p>The decision half ({@link #rows(Inputs)}) is a pure function over already-read text so it can be
 * unit-tested against a stock vmparams, a patched one and a stale-{@code -D} one without touching a
 * disk. {@link #inspect} is the only part that does I/O.
 */
public final class CoopInstallCheck {

    /**
     * Row verdicts. A single {@link #FAIL} anywhere blocks Launch; {@link #WARN} never does, and
     * {@link #INFO} is not even a complaint - it is for a row that could not reach a verdict at all,
     * like an update check against a GitHub that did not answer.
     */
    public enum Status {
        OK,
        INFO,
        WARN,
        FAIL
    }

    /**
     * One line in the install panel.
     *
     * @param label   what was checked
     * @param status  the verdict
     * @param detail  what was found
     * @param fix     what to do about it, empty when there is nothing to do
     * @param fixable which file the launcher can put right itself, or {@code null} for the rows a
     *                player has to deal with. A row is marked fixable when the shape of the problem
     *                is one {@link CoopInstallFixer} handles; the fixer still gets the last word
     *                and refuses with the manual text when the file on disk turns out not to be.
     */
    public record Row(String label, Status status, String detail, String fix,
                      CoopInstallFixer.Target fixable) {
        public Row {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            fix = fix == null ? "" : fix;
        }

        /** A row nothing can be pressed on. */
        public Row(String label, Status status, String detail, String fix) {
            this(label, status, detail, fix, null);
        }

        @Override
        public String toString() {
            return "[" + status + "] " + label + ": " + detail + (fix.isEmpty() ? "" : "  -> " + fix);
        }
    }

    /**
     * Everything the pure checker needs, already read off disk.
     *
     * @param installRoot     shown in the first row so the player can see which install was found
     * @param starsectorExe   whether {@code starsector.exe} is there
     * @param javaw           whether {@code jre\bin\javaw.exe} is there
     * @param starsectorCore  whether the {@code starsector-core} folder is there
     * @param coopJarPresent  whether {@code mods\coop\jars\coop.jar} is there
     * @param forksJarPresent whether {@code mods\coop\jars\coop-forks.jar} is there
     * @param vmparamsText    the whole vmparams line, or {@code null} when the file is missing
     * @param enabledModsText the raw {@code enabled_mods.json}, or {@code null} when missing
     * @param modInfoVersion  {@code version} out of {@code mod_info.json}, or {@code null}
     * @param jarVersion      {@code Implementation-Version} out of coop.jar, or {@code null}
     * @param settingsError   why the settings file could not be read, or {@code null} when it is fine
     * @param modGameVersion  {@code gameVersion} out of {@code mod_info.json}, or {@code null}
     * @param gameVersion     the game version read out of {@code starsector.log}, or {@code null}
     *                        when the game has not run yet or the line was not found. Already
     *                        extracted rather than passed as raw log text: a rolled log is 50 MB,
     *                        and holding that in a record so a pure function can regex it would be
     *                        the launcher's largest allocation by two orders of magnitude. The
     *                        extraction itself is the pure part -
     *                        {@link #gameVersionInLogText(String)}.
     * @param allowGameVersionMismatch whether the Advanced developer flag is ticked, which turns the
     *                        game-version row from a FAIL into a WARN so it stops blocking Launch
     */
    public record Inputs(String installRoot,
                         boolean starsectorExe,
                         boolean javaw,
                         boolean starsectorCore,
                         boolean coopJarPresent,
                         boolean forksJarPresent,
                         String vmparamsText,
                         String enabledModsText,
                         String modInfoVersion,
                         String jarVersion,
                         String settingsError,
                         String modGameVersion,
                         String gameVersion,
                         boolean allowGameVersionMismatch) {
        public Inputs {
            installRoot = installRoot == null ? "" : installRoot;
        }
    }

    private CoopInstallCheck() {
    }

    /** Reads the install and runs the checks. The only I/O in this class. */
    public static List<Row> inspect(CoopInstallLayout layout, String settingsError,
                                    boolean allowGameVersionMismatch) {
        Objects.requireNonNull(layout, "layout");
        return rows(new Inputs(
                layout.installRoot().getPath(),
                layout.starsectorExe().isFile(),
                layout.javaw().isFile(),
                layout.starsectorCore().isDirectory(),
                layout.coopJar().isFile(),
                layout.forksJar().isFile(),
                readTextOrNull(layout.vmparams()),
                readTextOrNull(layout.enabledMods()),
                readModInfoVersion(layout.modInfo()),
                readJarVersion(layout.coopJar()),
                settingsError,
                readModInfoGameVersion(layout.modInfo()),
                readGameVersion(layout),
                allowGameVersionMismatch));
    }

    /** The pure half: inputs in, rows out, in the order they are shown. */
    public static List<Row> rows(Inputs in) {
        Objects.requireNonNull(in, "in");
        List<Row> rows = new ArrayList<>();

        rows.add(new Row("Starsector install", Status.OK, in.installRoot(), ""));

        rows.add(presence("starsector.exe", in.starsectorExe(),
                "not found next to the mods folder",
                "The launcher is looking at the wrong folder. Use \"Choose install folder\"."));
        rows.add(presence("jre\\bin\\javaw.exe", in.javaw(),
                "not found",
                "This install has no bundled JRE. A modded-JRE install starts the game from a .bat"
                        + " file instead; launch it that way and use this window for settings only."));
        rows.add(presence("starsector-core", in.starsectorCore(),
                "not found",
                "The launcher is looking at the wrong folder. Use \"Choose install folder\"."));
        rows.add(presence("mods\\coop\\jars\\coop.jar", in.coopJarPresent(),
                "not found",
                "Unzip the mod so that mod_info.json sits directly in <install>\\mods\\coop."
                        + " See docs/player/INSTALL.md section 2."));
        rows.add(presence("mods\\coop\\jars\\coop-forks.jar", in.forksJarPresent(),
                "not found",
                "Unzip the mod so that mod_info.json sits directly in <install>\\mods\\coop."
                        + " See docs/player/INSTALL.md section 2."));

        rows.add(classpathRow(in.vmparamsText()));
        rows.add(stalePropertyRow(in.vmparamsText()));
        rows.add(enabledModsRow(in.enabledModsText()));
        rows.add(versionRow(in.modInfoVersion(), in.jarVersion()));
        rows.add(gameVersionRow(in.modGameVersion(), in.gameVersion(),
                in.allowGameVersionMismatch()));
        rows.add(settingsRow(in.settingsError()));

        return List.copyOf(rows);
    }

    /** True when any row would stop a Launch. */
    public static boolean blocked(List<Row> rows) {
        for (Row row : rows) {
            if (row.status() == Status.FAIL) {
                return true;
            }
        }
        return false;
    }

    private static Row presence(String label, boolean present, String missingDetail, String fix) {
        return present
                ? new Row(label, Status.OK, "found", "")
                : new Row(label, Status.FAIL, missingDetail, fix);
    }

    private static Row classpathRow(String vmparamsText) {
        String label = "coop-forks.jar first on the JVM classpath";
        if (vmparamsText == null) {
            return new Row(label, Status.FAIL, "vmparams is missing or unreadable",
                    "Restore <install>\\vmparams from your backup, then redo INSTALL.md section 3.");
        }
        if (!CoopVmparamsText.hasClasspath(vmparamsText)) {
            return new Row(label, Status.FAIL, "vmparams has no \" -classpath \" on it",
                    "That file is not a Starsector vmparams. Restore it from your backup.");
        }
        if (CoopVmparamsText.hasForksFirstOnClasspath(vmparamsText)) {
            return new Row(label, Status.OK, "yes", "");
        }
        if (CoopVmparamsText.hasForksLaterOnClasspath(vmparamsText)) {
            return new Row(label, Status.FAIL,
                    "the entry is on the classpath but not at the front, so it does nothing",
                    byButtonOr(CoopVmparamsText.forksFixText()), CoopInstallFixer.Target.VMPARAMS);
        }
        return new Row(label, Status.FAIL, "the entry is not on the classpath",
                byButtonOr(CoopVmparamsText.forksFixText()), CoopInstallFixer.Target.VMPARAMS);
    }

    /**
     * The fix text for a row that carries a Fix button: the button first, the manual edit after it.
     * Both stay on the row on purpose - the button cannot help a player whose install refuses the
     * write, and the manual instructions are the thing they fall back to.
     */
    private static String byButtonOr(String manual) {
        return "Press Fix and the launcher does it for you. By hand: " + manual;
    }

    private static Row stalePropertyRow(String vmparamsText) {
        String label = "no leftover -Dcoop.* in vmparams";
        if (vmparamsText == null) {
            return new Row(label, Status.WARN, "vmparams could not be read, so this was not checked",
                    "");
        }
        List<String> stale = CoopVmparamsText.staleCoopProperties(vmparamsText);
        if (stale.isEmpty()) {
            return new Row(label, Status.OK, "none", "");
        }
        // A warning, never a failure: the game still runs. What it costs is that these values win
        // over the settings file this launcher writes, so the fields on screen quietly stop being
        // what the game uses. Left behind by the dev launch scripts, which patch vmparams directly.
        return new Row(label, Status.WARN,
                stale.size() + " left on the line: " + String.join(" ", stale)
                        + " (these override anything set here)",
                CoopVmparamsText.stalePropertyFixText(stale));
    }

    private static Row enabledModsRow(String enabledModsText) {
        String label = "co-op enabled in mods\\enabled_mods.json";
        if (enabledModsText == null) {
            return new Row(label, Status.FAIL, "enabled_mods.json is missing or unreadable",
                    byButtonOr(CoopInstallFixer.enabledModsFixText()),
                    CoopInstallFixer.Target.ENABLED_MODS);
        }
        Boolean enabled = enabledModsContains(enabledModsText, CoopInstallLayout.MOD_ID);
        if (enabled == null) {
            return new Row(label, Status.FAIL, "enabled_mods.json is not valid JSON",
                    "Delete it and re-tick the mod in the Starsector launcher.");
        }
        if (enabled) {
            return new Row(label, Status.OK, "yes", "");
        }
        return new Row(label, Status.FAIL, "\"coop\" is not in the enabled list",
                byButtonOr(CoopInstallFixer.enabledModsFixText()),
                CoopInstallFixer.Target.ENABLED_MODS);
    }

    private static Row versionRow(String modInfoVersion, String jarVersion) {
        String label = "mod_info.json version matches coop.jar";
        if (modInfoVersion == null) {
            return new Row(label, Status.FAIL, "mod_info.json is missing or has no version",
                    "Unzip the mod again; the folder is incomplete.");
        }
        if (jarVersion == null) {
            return new Row(label, Status.FAIL, "coop.jar has no Implementation-Version",
                    "Unzip the mod again; the jar is not the shipped one.");
        }
        if (modInfoVersion.equals(jarVersion)) {
            return new Row(label, Status.OK, modInfoVersion, "");
        }
        return new Row(label, Status.FAIL,
                "mod_info.json says " + modInfoVersion + ", coop.jar says " + jarVersion,
                "Two builds got mixed in one folder. Delete <install>\\mods\\coop and unzip the"
                        + " download again.");
    }

    /**
     * Does the installed game match the version the mod was built for?
     *
     * <p>The mod refuses to start a session on a mismatch (support code {@code COOP-GAME}), so the
     * launcher says so before the player presses Launch and waits through a load for the same
     * answer.
     *
     * <p><b>The flag downgrades the row instead of being special-cased in {@link #blocked}.</b> Two
     * ways to keep the developer flag from being blocked by its own row were available; this is the
     * simpler one. {@code blocked} stays a plain "any FAIL blocks" rule with no exception list, and
     * the row a player sees matches what will actually happen: a WARN, because the game will start
     * and co-op will run. The alternative - a FAIL that {@code blocked} secretly ignores - would put
     * a red row above a Launch button that works, which is the kind of thing that teaches people to
     * ignore red rows.
     */
    private static Row gameVersionRow(String modGameVersion, String gameVersion, boolean allowed) {
        String label = "Game version";
        String mod = trimOrNull(modGameVersion);
        String game = trimOrNull(gameVersion);
        if (mod == null) {
            return new Row(label, Status.INFO, "mod_info.json does not say which version it is for",
                    "Unzip the mod again; the folder is incomplete.");
        }
        if (game == null) {
            return new Row(label, Status.INFO, "unknown until the game has run once",
                    "Press Launch once. The version is read out of starsector-core\\starsector.log,"
                            + " which the game writes at startup.");
        }
        if (mod.equals(game)) {
            return new Row(label, Status.OK, "matches the mod: " + mod, "");
        }
        return new Row(label, allowed ? Status.WARN : Status.FAIL,
                "game is " + game + ", the mod was built for " + mod,
                "Install Starsector " + mod + " on both PCs, or tick Allow game version mismatch"
                        + " under Advanced if you are testing.");
    }

    private static Row settingsRow(String settingsError) {
        String label = "settings file saves\\common\\coop_options.json.data";
        if (settingsError == null) {
            return new Row(label, Status.OK, "readable", "");
        }
        return new Row(label, Status.FAIL, "settings file unreadable: " + settingsError,
                "Fix it by hand (plain JSON, no # comments) or delete it. The launcher will not"
                        + " overwrite it, because that would throw away every setting in it.");
    }

    /**
     * Whether {@code modId} is in the {@code enabledMods} array, or {@code null} when the text does
     * not parse.
     */
    static Boolean enabledModsContains(String text, String modId) {
        try {
            JSONObject json = new JSONObject(text);
            JSONArray mods = json.optJSONArray("enabledMods");
            if (mods == null) {
                return Boolean.FALSE;
            }
            for (int i = 0; i < mods.length(); i++) {
                if (modId.equals(String.valueOf(mods.opt(i)))) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String readTextOrNull(File file) {
        try {
            if (file == null || !file.isFile()) {
                return null;
            }
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    static String readModInfoVersion(File modInfo) {
        return readModInfoField(modInfo, "version");
    }

    /** {@code gameVersion} out of {@code mod_info.json}: the Starsector this build is for. */
    static String readModInfoGameVersion(File modInfo) {
        return readModInfoField(modInfo, "gameVersion");
    }

    private static String readModInfoField(File modInfo, String field) {
        String text = readTextOrNull(modInfo);
        if (text == null) {
            return null;
        }
        try {
            String value = new JSONObject(text).optString(field, "");
            return value.isEmpty() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * What the launcher line at the top of every run says the game is. The engine writes it before
     * anything else, so the log is the only place a launcher running outside the game can read the
     * installed version from - {@code starsector.exe} carries no version resource, and
     * {@code starsector-core} has no version file.
     *
     * <p>Shape, verbatim from a real log:
     * {@code 0    [main] INFO  com.fs.starfarer.StarfarerLauncher  - Starting Starsector 0.98a-RC8 launcher}
     *
     * <p>Matched anywhere on the line rather than at a fixed column, because the prefix is log4j's
     * and carries a variable-width uptime figure.
     */
    static final String LAUNCH_LINE_PREFIX = "Starting Starsector ";

    private static final String LAUNCH_LINE_SUFFIX = " launcher";

    /**
     * The version on the <em>last</em> launcher line in a chunk of log text, or {@code null}.
     *
     * <p>Last, not first: {@code starsector.log} is appended to across runs and only rolls at 50 MB,
     * so its head is usually the tail of a run from days ago. Taking the first match would report
     * the version the player had before they updated.
     */
    static String gameVersionInLogText(String logText) {
        if (logText == null) {
            return null;
        }
        String found = null;
        for (String line : logText.split("\r?\n")) {
            String version = gameVersionInLogLine(line);
            if (version != null) {
                found = version;
            }
        }
        return found;
    }

    /** One line's worth of {@link #gameVersionInLogText}. */
    static String gameVersionInLogLine(String line) {
        if (line == null) {
            return null;
        }
        int start = line.indexOf(LAUNCH_LINE_PREFIX);
        if (start < 0) {
            return null;
        }
        start += LAUNCH_LINE_PREFIX.length();
        int end = line.indexOf(LAUNCH_LINE_SUFFIX, start);
        if (end <= start) {
            return null;
        }
        String version = line.substring(start, end).trim();
        return version.isEmpty() ? null : version;
    }

    /**
     * {@code starsector.log}, then {@code starsector.log.1} when the current one has no launcher
     * line in it (which happens right after a roll).
     */
    static String readGameVersion(CoopInstallLayout layout) {
        String current = readGameVersionFromLog(layout.starsectorLog());
        if (current != null) {
            return current;
        }
        return readGameVersionFromLog(new File(layout.starsectorCore(), "starsector.log.1"));
    }

    /**
     * Streams one log file and keeps the last launcher line's version.
     *
     * <p>Streamed rather than read into a string on purpose: these files run to 50 MB, and the
     * launcher has no business holding one in memory to run a substring over it.
     */
    static String readGameVersionFromLog(File log) {
        if (log == null || !log.isFile()) {
            return null;
        }
        String found = null;
        // InputStreamReader, not Files.newBufferedReader: the latter throws on a malformed byte,
        // and a 50 MB game log picks up the occasional one. Replacing it costs nothing here.
        try (InputStream stream = new java.io.FileInputStream(log);
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String version = gameVersionInLogLine(line);
                if (version != null) {
                    found = version;
                }
            }
        } catch (IOException | RuntimeException ex) {
            // A log that will not read is "unknown", which is an INFO row, not a failure.
            return found;
        }
        return found;
    }

    /** {@code Implementation-Version} out of a jar manifest, or {@code null}. */
    public static String readJarVersion(File jar) {
        return readJarAttribute(jar, "Implementation-Version");
    }

    /**
     * One main-manifest attribute out of a jar, or {@code null} when the jar, the manifest or the
     * attribute is missing. Used for the version row here and for {@code Coop-Git-Commit} in a bug
     * report, which is the only way to tell two builds of the same version apart.
     */
    public static String readJarAttribute(File jar, String name) {
        if (jar == null || !jar.isFile()) {
            return null;
        }
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes attributes = manifest.getMainAttributes();
            String value = attributes.getValue(name);
            return value == null || value.isEmpty() ? null : value;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * The launcher's own version, off its own manifest. Falls back to {@code "dev"} when the classes
     * are loaded from a directory rather than a jar, which is what a test run looks like.
     */
    public static String launcherVersion() {
        try (InputStream stream = CoopInstallCheck.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (stream == null) {
                return "dev";
            }
            Manifest manifest = new Manifest(stream);
            String title = manifest.getMainAttributes().getValue("Implementation-Title");
            String version = manifest.getMainAttributes().getValue("Implementation-Version");
            if (version == null || title == null
                    || !title.toLowerCase(Locale.ROOT).contains("launcher")) {
                return "dev";
            }
            return version;
        } catch (IOException | RuntimeException ex) {
            return "dev";
        }
    }
}
