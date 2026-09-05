package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONObject;

import coop.config.CoopOptionsRegistry;

/**
 * Read and write of {@code saves/common/coop_options.json.data} - the one file the launcher owns.
 *
 * <p><b>Preservation.</b> Every key the launcher does not write is carried through untouched, in the
 * order it was found. A player who hand-set {@code coop.partnerColor} keeps it after pressing Launch.
 *
 * <p><b>Unreadable is not empty.</b> A file that will not parse is refused, never rewritten:
 * rewriting it would silently delete every setting in it. The launcher shows a FAIL row instead and
 * blocks Launch until the player fixes or deletes the file. Same rule
 * {@code CoopOptionsStore.writeOverrides} follows in the game.
 *
 * <p><b>Blank means remove.</b> An empty field drops the key so the shipped default applies again -
 * except for the three role keys, where an explicit {@code ""} is the value that means "not
 * configured" and has to be written to cancel the other role.
 */
public final class CoopLauncherConfig {

    /** Role keys, whose blanking is an explicit {@code ""} rather than a removal. */
    public static final String HOST_PORT = CoopOptionsRegistry.HOST_PORT;
    public static final String CONNECT_HOST = CoopOptionsRegistry.CONNECT_HOST;
    public static final String CONNECT_PORT = CoopOptionsRegistry.CONNECT_PORT;

    public static final String PASSWORD = CoopOptionsRegistry.PASSWORD;
    public static final String NEW_GAME_SEED = CoopOptionsRegistry.NEW_GAME_SEED;
    public static final String PORT_MAPPING = CoopOptionsRegistry.PORT_MAPPING;
    public static final String RECONNECT_GRACE_SECONDS = CoopOptionsRegistry.RECONNECT_GRACE_SECONDS;
    public static final String HUD_CORNER = CoopOptionsRegistry.HUD_CORNER;
    public static final String SECTOR_SIZE = CoopOptionsRegistry.SECTOR_SIZE;
    public static final String SECTOR_AGE = CoopOptionsRegistry.SECTOR_AGE;

    /** The -D-only flags the Advanced card exposes; the mod republishes them at application load. */
    public static final String FULL_FIDELITY_GUEST_SYSTEM = CoopOptionsRegistry.FULL_FIDELITY_GUEST_SYSTEM;
    public static final String FF_DISABLE = CoopOptionsRegistry.FF_DISABLE;
    public static final String CLOCK_DISABLE = CoopOptionsRegistry.CLOCK_DISABLE;
    public static final String DEBUG_DIAGNOSTICS = CoopOptionsRegistry.DEBUG_DIAGNOSTICS;
    public static final String DEBUG_BRIDGE = CoopOptionsRegistry.DEBUG_BRIDGE;
    public static final String DEBUG_WIRETAP = CoopOptionsRegistry.DEBUG_WIRETAP;
    public static final String DEBUG_WIRETAP_SAMPLE = CoopOptionsRegistry.DEBUG_WIRETAP_SAMPLE;
    public static final String DEBUG_FRAME_PROFILE = CoopOptionsRegistry.DEBUG_FRAME_PROFILE;
    public static final String DEBUG_INTERACTION_DELAY_MS = CoopOptionsRegistry.DEBUG_INTERACTION_DELAY_MS;
    public static final String ADOPT_CAMPAIGN_ID = CoopOptionsRegistry.ADOPT_CAMPAIGN_ID;
    public static final String EXPECTED_CAMPAIGN_ID = CoopOptionsRegistry.EXPECTED_CAMPAIGN_ID;
    public static final String ALLOW_GAME_VERSION_MISMATCH =
            CoopOptionsRegistry.ALLOW_GAME_VERSION_MISMATCH;

    /**
     * The order launcher-owned keys are written in, so two runs over the same settings produce the
     * same file and a diff is readable.
     */
    private static final List<String> OWNED_ORDER = List.of(
            HOST_PORT,
            CONNECT_HOST,
            CONNECT_PORT,
            PASSWORD,
            NEW_GAME_SEED,
            PORT_MAPPING,
            RECONNECT_GRACE_SECONDS,
            HUD_CORNER,
            SECTOR_SIZE,
            SECTOR_AGE,
            FULL_FIDELITY_GUEST_SYSTEM,
            FF_DISABLE,
            CLOCK_DISABLE,
            DEBUG_DIAGNOSTICS,
            DEBUG_BRIDGE,
            DEBUG_WIRETAP,
            DEBUG_WIRETAP_SAMPLE,
            DEBUG_FRAME_PROFILE,
            DEBUG_INTERACTION_DELAY_MS,
            ALLOW_GAME_VERSION_MISMATCH,
            ADOPT_CAMPAIGN_ID,
            EXPECTED_CAMPAIGN_ID);

    private final Map<String, Object> existing;
    private final boolean fileExisted;
    private final String readError;

    private CoopLauncherConfig(Map<String, Object> existing, boolean fileExisted, String readError) {
        this.existing = existing;
        this.fileExisted = fileExisted;
        this.readError = readError;
    }

    /**
     * Reads the file. A missing file is an empty config, which is not an error. A file that is there
     * but will not parse produces a config whose {@link #readError()} is set and which refuses to
     * {@link #write}.
     */
    public static CoopLauncherConfig read(File file) {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            return new CoopLauncherConfig(new LinkedHashMap<>(), false, null);
        }
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return new CoopLauncherConfig(new LinkedHashMap<>(), true, describe(ex));
        }
        return parse(text);
    }

    /** Pure half of {@link #read}, for tests. */
    static CoopLauncherConfig parse(String text) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            JSONObject json = new JSONObject(text);
            // json.jar's key iteration order is not insertion order, so sort what we carry through:
            // an arbitrary-but-stable order still gives a stable file, which is the point.
            Set<String> keys = new TreeSet<>();
            Iterator<?> iterator = json.keys();
            while (iterator.hasNext()) {
                keys.add(String.valueOf(iterator.next()));
            }
            for (String key : keys) {
                Object value = json.opt(key);
                if (value == null || JSONObject.NULL.equals(value)) {
                    continue;
                }
                values.put(key, value);
            }
        } catch (Exception ex) {
            return new CoopLauncherConfig(new LinkedHashMap<>(), true, describe(ex));
        }
        return new CoopLauncherConfig(values, true, null);
    }

    /** Why the file could not be read, or {@code null} when it is fine (including when absent). */
    public String readError() {
        return readError;
    }

    public boolean fileExisted() {
        return fileExisted;
    }

    /** The stored value for {@code key} as text, or {@code ""} when absent. */
    public String value(String key) {
        Object value = existing.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** Every key currently in the file, for the log. */
    public Set<String> keys() {
        return new LinkedHashSet<>(existing.keySet());
    }

    /**
     * The file text this config would produce for the given role and values.
     *
     * @param host    true for a host launch, false for a guest one
     * @param owned   launcher-owned key to value; a blank value removes the key, except for the role
     *                keys handled by {@code host}
     * @return the exact bytes to write, newline-terminated
     */
    public String compose(boolean host, Map<String, String> owned) {
        Objects.requireNonNull(owned, "owned");
        Map<String, Object> result = new LinkedHashMap<>(existing);

        // Role ownership. The registry default for all three is "", which CoopNetStartupConfig reads
        // as "not configured", so blanking the other role is a written "" and not a removal - a
        // removed key would fall through to whatever the shipped defaults say.
        if (host) {
            result.put(HOST_PORT, required(owned, HOST_PORT));
            result.put(CONNECT_HOST, "");
            result.put(CONNECT_PORT, "");
        } else {
            result.put(HOST_PORT, "");
            result.put(CONNECT_HOST, required(owned, CONNECT_HOST));
            result.put(CONNECT_PORT, required(owned, CONNECT_PORT));
        }

        for (String key : OWNED_ORDER) {
            if (key.equals(HOST_PORT) || key.equals(CONNECT_HOST) || key.equals(CONNECT_PORT)) {
                continue;
            }
            String value = owned.get(key);
            if (value == null || value.isBlank()) {
                result.remove(key);
            } else {
                result.put(key, value.trim());
            }
        }

        return renderFile(result);
    }

    /**
     * Drops the one-shot adopt-campaign consent from the file, if it is in there, leaving every
     * other key untouched.
     *
     * <p>The launcher starts {@code starsector.exe}, not a JVM, so this file is its only channel to
     * the game - and the mod republishes every key in it as a system property at every application
     * load. A tick that meant "adopt the host's campaign this once" would otherwise keep meaning it,
     * including on a start made by double-clicking the game rather than by pressing Launch.
     *
     * @return true when the file was rewritten
     * @throws IOException when the file could not be written
     */
    public static boolean clearAdoptCampaignConsent(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            return false;
        }
        CoopLauncherConfig current = read(file);
        // Same rule as write(): a file that will not parse is never rewritten, because rewriting it
        // would throw away every setting this launcher cannot see.
        if (current.readError != null || !current.existing.containsKey(ADOPT_CAMPAIGN_ID)) {
            return false;
        }
        Map<String, Object> remaining = new LinkedHashMap<>(current.existing);
        remaining.remove(ADOPT_CAMPAIGN_ID);
        CoopAtomicFiles.writeAtomically(file.toPath(),
                renderFile(remaining).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /** The file text for a set of values: owned keys in their fixed order, everything else sorted. */
    private static String renderFile(Map<String, Object> result) {
        List<String> ordered = new ArrayList<>();
        for (String key : OWNED_ORDER) {
            if (result.containsKey(key)) {
                ordered.add(key);
            }
        }
        for (String key : new TreeSet<>(result.keySet())) {
            if (!ordered.contains(key)) {
                ordered.add(key);
            }
        }

        StringBuilder text = new StringBuilder("{\n");
        for (int i = 0; i < ordered.size(); i++) {
            String key = ordered.get(i);
            text.append("\t").append(JSONObject.quote(key)).append(": ")
                    .append(render(result.get(key)));
            if (i < ordered.size() - 1) {
                text.append(',');
            }
            text.append('\n');
        }
        text.append("}\n");
        return text.toString();
    }

    /**
     * Writes the file, creating {@code saves/common} when it is not there yet.
     *
     * @throws IOException when the file could not be written
     * @throws IllegalStateException when the existing file could not be read, because rewriting it
     *         would throw away settings this launcher cannot see
     */
    public void write(File file, boolean host, Map<String, String> owned) throws IOException {
        if (readError != null) {
            throw new IllegalStateException("refusing to rewrite " + file
                    + ": it exists but could not be read (" + readError + ")");
        }
        String text = compose(host, owned);
        CoopAtomicFiles.writeAtomically(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Serialises one value. Everything the launcher owns is a string, because that is what the store
     * reads; a carried-through key keeps whatever shape it had, so a hand-written
     * {@code "coop.hostPort": 7777} is not silently turned into text. {@code JSONObject.valueToString}
     * is package-private in the bundled json.jar, hence the hand-rolled version.
     */
    private static String render(Object value) {
        if (value instanceof String text) {
            return JSONObject.quote(text);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof org.json.JSONObject || value instanceof org.json.JSONArray) {
            return value.toString();
        }
        return JSONObject.quote(String.valueOf(value));
    }

    private static String required(Map<String, String> owned, String key) {
        String value = owned.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for this role");
        }
        return value.trim();
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : message;
    }
}
