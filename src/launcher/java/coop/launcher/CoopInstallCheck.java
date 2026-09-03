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
 * The install rows the launcher shows. Report only: nothing here writes {@code vmparams} or
 * {@code enabled_mods.json}. Both are files the player and the Starsector installer own, and an
 * automatic edit that goes wrong leaves an install that will not start.
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
     * @param label  what was checked
     * @param status the verdict
     * @param detail what was found
     * @param fix    what to do about it, empty when there is nothing to do
     */
    public record Row(String label, Status status, String detail, String fix) {
        public Row {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            fix = fix == null ? "" : fix;
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
                         String settingsError) {
        public Inputs {
            installRoot = installRoot == null ? "" : installRoot;
        }
    }

    private CoopInstallCheck() {
    }

    /** Reads the install and runs the checks. The only I/O in this class. */
    public static List<Row> inspect(CoopInstallLayout layout, String settingsError) {
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
                settingsError));
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
                    CoopVmparamsText.forksFixText());
        }
        return new Row(label, Status.FAIL, "the entry is not on the classpath",
                CoopVmparamsText.forksFixText());
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
                    "Start the Starsector launcher once, click MODS and tick Starsector Coop V1.");
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
                "Start the Starsector launcher once, click MODS and tick Starsector Coop V1.");
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
        String text = readTextOrNull(modInfo);
        if (text == null) {
            return null;
        }
        try {
            String version = new JSONObject(text).optString("version", "");
            return version.isEmpty() ? null : version;
        } catch (Exception ex) {
            return null;
        }
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
