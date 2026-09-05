package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The two install problems the launcher can put right by itself: the {@code coop-forks.jar} entry at
 * the front of the {@code -classpath} in {@code vmparams}, and the {@code "coop"} tick in
 * {@code mods\enabled_mods.json}.
 *
 * <p>Phase 31 shipped these as report-only rows, on the reasoning that an automatic edit that goes
 * wrong leaves an install that will not start. The rows stayed red for players who read the fix text
 * and still could not carry it out - a file with no extension, one line thousands of characters
 * long, and an editor that helpfully wraps it. Decision reversed 2026-09-04: the launcher applies
 * both edits on a button press, and {@code INSTALL.md} section 3 stays as the manual fallback.
 *
 * <p>What keeps the reversal safe:
 *
 * <ul>
 *   <li><b>A backup, once.</b> {@code vmparams} is copied to {@code vmparams.backup} before the
 *       first edit and never again, so a good backup is not overwritten by a bad file later.
 *   <li><b>Byte fidelity.</b> The line is read and written as raw bytes ({@code ISO-8859-1} maps
 *       every byte to one character and back, which no other charset promises), so nothing outside
 *       the classpath value can change. No trailing newline is added; the file stays one line.
 *   <li><b>Idempotence.</b> Every existing forks entry, in any spelling {@link CoopVmparamsText}
 *       recognises, is removed before the canonical one is inserted at the front. Pressing Fix twice
 *       produces the same file, and an entry that was merely in the wrong place moves rather than
 *       gaining a second copy.
 *   <li><b>Refusal over guessing.</b> A file with no {@code " -classpath "}, a multi-line file, and a
 *       modded-JRE install that starts the game from a {@code .bat} are all refused with the manual
 *       text instead of edited.
 *   <li><b>{@code -Dcoop.*} is never touched.</b> That row is a warning about settings the player or
 *       a dev script put there on purpose; deleting someone's flags is not a fix.
 * </ul>
 *
 * <p>The transforms are pure functions over text. {@link #apply} is the only part that touches a
 * disk.
 */
public final class CoopInstallFixer {

    private static final Logger LOG = Logger.getLogger(CoopInstallFixer.class);

    /**
     * Byte-preserving charset. Every byte 0x00-0xFF maps to exactly one character and back, so a
     * read/patch/write round trip cannot corrupt a byte the launcher did not mean to change. A
     * stock {@code vmparams} is pure ASCII, and ASCII is a subset of this, so the file written is
     * the ASCII file {@code INSTALL.md} describes.
     */
    private static final Charset BYTES = StandardCharsets.ISO_8859_1;

    /** The key the enabled-mods document lists mod ids under. */
    static final String ENABLED_MODS_KEY = "enabledMods";

    /** Which file a Fix button acts on. */
    public enum Target {
        VMPARAMS,
        ENABLED_MODS
    }

    /**
     * What one Fix press did.
     *
     * @param changed      true when a file was written
     * @param accessDenied true when the write was refused by the filesystem, which is the signal to
     *                     offer an elevated relaunch rather than print an error
     * @param message      one line for the log drawer, always populated
     */
    public record Result(boolean changed, boolean accessDenied, String message) {
        public Result {
            Objects.requireNonNull(message, "message");
        }
    }

    private CoopInstallFixer() {
    }

    // ---- vmparams -----------------------------------------------------------------------------

    /**
     * Why {@code text} cannot be patched, phrased for the player, or {@code null} when it can.
     *
     * <p>Deliberately not a boolean: every refusal ends with the manual instructions, and the player
     * needs to know which of them applies.
     */
    public static String vmparamsRefusal(String text) {
        if (text == null) {
            return "vmparams could not be read.";
        }
        String body = stripTrailingNewlines(text);
        if (body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0) {
            return "vmparams has been split into several lines, so the launcher cannot tell which"
                    + " one the JVM reads.";
        }
        if (!CoopVmparamsText.hasClasspath(body)) {
            return "vmparams has no \" -classpath \" on it, so it is not a Starsector vmparams.";
        }
        return null;
    }

    /**
     * {@code text} with the forks entry first on the classpath, everything else byte for byte and no
     * trailing newline.
     *
     * <p>The algorithm is the one {@code scripts/launch-host.ps1} has used on the dev boxes since
     * Phase 1: drop every existing forks entry, then insert the canonical spelling immediately after
     * {@code " -classpath "}. The last classpath entry is skipped by the removal pass because it
     * carries the main class after a space and is never the forks jar.
     *
     * @throws IllegalArgumentException when {@link #vmparamsRefusal} would refuse the text
     */
    public static String patchVmparams(String text) {
        String refusal = vmparamsRefusal(text);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        String body = stripTrailingNewlines(text);
        int marker = body.indexOf(CoopVmparamsText.CLASSPATH_MARKER);
        int valueStart = marker + CoopVmparamsText.CLASSPATH_MARKER.length();
        String head = body.substring(0, valueStart);
        String value = body.substring(valueStart);

        List<String> entries = new ArrayList<>(Arrays.asList(value.split(";", -1)));
        for (int i = entries.size() - 2; i >= 0; i--) {
            if (CoopVmparamsText.isForksEntry(entries.get(i))) {
                entries.remove(i);
            }
        }
        return head + CoopVmparamsText.FORKS_ENTRY + String.join(";", entries);
    }

    /** Trailing newlines an editor may have added. Nothing else about the text is normalised. */
    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }

    /** Where {@link #apply} puts the copy it takes before its first edit. */
    public static File vmparamsBackup(File vmparams) {
        return new File(vmparams.getParentFile(), vmparams.getName() + ".backup");
    }

    // ---- enabled_mods.json --------------------------------------------------------------------

    /**
     * {@code text} with {@code modId} added to {@code enabledMods}, or {@code null} when it is
     * already there and nothing needs writing.
     *
     * <p>Order is preserved and no other mod is touched: the existing ids are read out of the array
     * in order, {@code modId} is appended, and the document is re-emitted in the two-space shape the
     * vanilla launcher writes. Any other top-level key is carried across. A {@code null} or blank
     * text means "no file yet" and produces a document holding only {@code modId}.
     *
     * @throws IllegalArgumentException when the text is not JSON, or {@code enabledMods} is not an
     *                                  array of strings
     */
    public static String addEnabledMod(String text, String modId) {
        Objects.requireNonNull(modId, "modId");
        if (text == null || text.isBlank()) {
            return renderEnabledMods(new JSONObject(), List.of(modId));
        }
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (Exception ex) {
            // org.json in the json.jar Starsector ships throws a checked JSONException.
            throw new IllegalArgumentException("enabled_mods.json is not valid JSON", ex);
        }
        Object existing = json.opt(ENABLED_MODS_KEY);
        List<String> mods = new ArrayList<>();
        if (existing instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (!(value instanceof String id)) {
                    throw new IllegalArgumentException(
                            "enabled_mods.json lists something that is not a mod id");
                }
                if (id.equals(modId)) {
                    return null;
                }
                mods.add(id);
            }
        } else if (existing != null && existing != JSONObject.NULL) {
            throw new IllegalArgumentException("enabled_mods.json has an \"" + ENABLED_MODS_KEY
                    + "\" that is not a list");
        }
        mods.add(modId);
        return renderEnabledMods(json, mods);
    }

    /**
     * The document the vanilla launcher would have written: two-space indent, one id per line,
     * {@code enabledMods} first and any other top-level key after it in name order.
     *
     * <p>Hand-rendered rather than {@code JSONObject.toString(2)}, which puts a single-key object's
     * array on the same line as the brace and would rewrite a file that a player may well open.
     */
    private static String renderEnabledMods(JSONObject json, List<String> mods) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  ").append(JSONObject.quote(ENABLED_MODS_KEY)).append(": [\n");
        for (int i = 0; i < mods.size(); i++) {
            out.append("    ").append(JSONObject.quote(mods.get(i)));
            out.append(i == mods.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ]");
        List<String> others = new ArrayList<>();
        for (Iterator<?> keys = json.keys(); keys.hasNext(); ) {
            String key = String.valueOf(keys.next());
            if (!ENABLED_MODS_KEY.equals(key)) {
                others.add(key);
            }
        }
        Collections.sort(others);
        for (String key : others) {
            out.append(",\n  ").append(JSONObject.quote(key)).append(": ")
                    .append(jsonValue(json.opt(key)));
        }
        out.append("\n}\n");
        return out.toString();
    }

    /**
     * One JSON value, rendered. The {@code json.jar} Starsector ships is an old org.json where
     * {@code valueToString} is package-private, so the value goes through a throwaway array whose
     * brackets are then stripped - which handles every type the library knows without this class
     * having an opinion about any of them.
     */
    private static String jsonValue(Object value) {
        String rendered = new JSONArray().put(value).toString();
        return rendered.substring(1, rendered.length() - 1);
    }

    /** The manual fallback for the enabled-mods row, mirroring {@code INSTALL.md} section 4. */
    public static String enabledModsFixText() {
        return "Start the Starsector launcher once, click MODS and tick Starsector Coop V1."
                + " See docs/player/INSTALL.md section 4.";
    }

    // ---- the disk half ------------------------------------------------------------------------

    /**
     * Applies one fix and reports what happened. The only I/O in this class, and the only place the
     * launcher writes outside {@code saves/common}.
     *
     * <p>Runs on a worker thread; nothing here touches Swing.
     */
    public static Result apply(CoopInstallLayout layout, Target target) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(target, "target");
        return target == Target.VMPARAMS ? applyVmparams(layout) : applyEnabledMods(layout);
    }

    private static Result applyVmparams(CoopInstallLayout layout) {
        File file = layout.vmparams();
        if (!layout.javaw().isFile()) {
            // A modded-JRE install starts the game from a .bat with its own -classpath; vmparams is
            // then a file the JVM never reads, and patching it would look like it worked.
            return refused("This install has no jre\\bin\\javaw.exe, so it starts the game from a"
                    + " .bat file with its own classpath. Put the entry at the front of that file's"
                    + " -classpath by hand. " + CoopVmparamsText.forksFixText());
        }
        if (!file.isFile()) {
            return refused("There is no " + file + " to edit. "
                    + CoopVmparamsText.forksFixText());
        }
        String text;
        try {
            text = Files.readString(file.toPath(), BYTES);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Could not read " + file, ex);
            return refused("Could not read " + file + ": " + ex + ". "
                    + CoopVmparamsText.forksFixText());
        }
        String refusal = vmparamsRefusal(text);
        if (refusal != null) {
            LOG.warn("Refusing to patch " + file + ": " + refusal);
            return refused(refusal + " " + CoopVmparamsText.forksFixText());
        }
        String patched = patchVmparams(text);
        if (patched.equals(stripTrailingNewlines(text))) {
            return new Result(false, false, "vmparams already has " + CoopVmparamsText.FORKS_ENTRY
                    + " first on the classpath; nothing to change.");
        }
        File backup = vmparamsBackup(file);
        try {
            if (!backup.exists()) {
                Files.copy(file.toPath(), backup.toPath());
                LOG.info("Backed " + file + " up to " + backup);
            } else {
                LOG.info("Leaving the existing " + backup + " alone");
            }
            CoopAtomicFiles.writeAtomically(file.toPath(), patched.getBytes(BYTES));
        } catch (AccessDeniedException ex) {
            LOG.warn("Access denied writing " + file, ex);
            return denied(file);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Could not write " + file, ex);
            return refused("Could not write " + file + ": " + ex + ". "
                    + CoopVmparamsText.forksFixText());
        }
        LOG.info("Patched " + file + "; backup at " + backup);
        return new Result(true, false, "Put " + CoopVmparamsText.FORKS_ENTRY + " first on the"
                + " classpath in " + file + ". The file it replaced is at " + backup + ".");
    }

    private static Result applyEnabledMods(CoopInstallLayout layout) {
        File file = layout.enabledMods();
        String text = null;
        if (file.isFile()) {
            try {
                text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ex) {
                LOG.warn("Could not read " + file, ex);
                return refused("Could not read " + file + ": " + ex + ". "
                        + enabledModsFixText());
            }
        }
        String written;
        try {
            written = addEnabledMod(text, CoopInstallLayout.MOD_ID);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Refusing to edit " + file + ": " + ex.getMessage());
            return refused(ex.getMessage() + ". Delete it and re-tick the mod in the Starsector"
                    + " launcher. " + enabledModsFixText());
        }
        if (written == null) {
            return new Result(false, false,
                    "\"" + CoopInstallLayout.MOD_ID + "\" is already in " + file + ".");
        }
        try {
            CoopAtomicFiles.writeAtomically(file.toPath(), written.getBytes(StandardCharsets.UTF_8));
        } catch (AccessDeniedException ex) {
            LOG.warn("Access denied writing " + file, ex);
            return denied(file);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Could not write " + file, ex);
            return refused("Could not write " + file + ": " + ex + ". " + enabledModsFixText());
        }
        LOG.info("Added \"" + CoopInstallLayout.MOD_ID + "\" to " + file);
        return new Result(true, false, "Ticked \"" + CoopInstallLayout.MOD_ID + "\" in " + file
                + ". Your other mods are untouched and in the same order.");
    }

    private static Result refused(String message) {
        return new Result(false, false, message);
    }

    private static Result denied(File file) {
        return new Result(false, true, "Windows would not let the launcher write " + file + ".");
    }
}
