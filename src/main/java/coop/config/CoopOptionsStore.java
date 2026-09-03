package coop.config;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import coop.util.CoopLog;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 28 milestone 1: the precedence stack behind {@link CoopOptionsRegistry}.
 *
 * <pre>
 *   -Dcoop.*  (highest - dev/debug override, unchanged from before this phase)
 *   saves/common/coop_options.json   (the user's own overrides; survives a mod update)
 *   data/config/coop_options.json    (shipped defaults; overwritten on every mod update)
 *   the registry default             (lowest)
 * </pre>
 *
 * <p><b>Why the middle layer exists.</b> Anything inside the mod's {@code data/config} is replaced
 * when the player updates the mod, so user overrides cannot live there. {@code saves/common}
 * survives, and {@code SettingsAPI} is the only sanctioned way to reach it.
 *
 * <p><b>Sandbox.</b> The script classloader blocks {@code java.io.*}, {@code java.nio.file.*} and
 * {@code java.lang.reflect}. Every file read here goes through {@code SettingsAPI}
 * ({@code loadJSON(path, modId)}, {@code fileExistsInCommon}, {@code readJSONFromCommon}), and the
 * calls are caught as {@code Exception | LinkageError} rather than by their declared
 * {@code IOException} - naming the checked type would make the verifier resolve a {@code java.io}
 * class in this class, which is the exact pattern the sandbox refuses.
 *
 * <p><b>Failure model.</b> A missing, unreadable or malformed file degrades to "no override" with a
 * single WARN. Config is never load-bearing enough to stop the game from starting.
 *
 * <p><b>Caching.</b> The two file layers are read once and cached (they cannot change without a
 * relaunch or an explicit {@link #reload()}). "Once" means once <em>successfully</em>: a read
 * attempted before {@code Global.getSettings()} exists caches nothing, or the first class to ask a
 * question early would pin "there is no settings file" for the rest of the process. The property layer is read live, because
 * {@code System.setProperty} is how tests and the launch scripts arrange a run.
 *
 * <p><b>Policy tier.</b> Milestone 1 reads {@link CoopOptionsRegistry.Tier#POLICY} keys through this
 * same stack. Milestone 2 adds the per-campaign store and the {@code OPTIONS_SNAPSHOT} broadcast, at
 * which point this becomes the host's seed value rather than the effective one.
 */
public final class CoopOptionsStore {

    /** Mod-relative path of the shipped defaults. */
    public static final String SHIPPED_PATH = "data/config/coop_options.json";
    /** File name under {@code saves/common} holding the user's overrides. */
    public static final String COMMON_FILE = "coop_options.json";
    /** The mod id {@code loadJSON} resolves {@link #SHIPPED_PATH} against. */
    public static final String MOD_ID = "coop";

    /**
     * What a player loses when the user file cannot be read or parsed, said in full because the
     * failure is otherwise silent: every setting in that file stops applying, not just the bad line.
     * The note about comments is there because the file comes back through
     * {@code readJSONFromCommon}, a save-data reader that - unlike the engine loader that reads the
     * shipped copy - does not strip {@code #} comments, so a commented user file fails to parse as a
     * whole.
     */
    static final String COMMON_CONSEQUENCE =
            "all overrides ignored, using shipped defaults (this file must be plain JSON:"
                    + " # comments work in the shipped data/config copy but NOT here)";

    /** The same sentence for the shipped layer, where the registry defaults are the fallback. */
    static final String SHIPPED_CONSEQUENCE = "falling back to built-in defaults";

    /** Where a resolved value came from. Exposed for the doctor output and the milestone 3 page. */
    public enum Source {
        PROPERTY,
        COMMON,
        SHIPPED,
        DEFAULT
    }

    /**
     * The two JSON layers, injectable because {@code Global.getSettings()} is null outside a running
     * game. Either method may return {@code null}, which simply means "this layer contributes
     * nothing"; neither is allowed to throw.
     */
    public interface JsonSource {
        /** The shipped {@code data/config/coop_options.json}, or {@code null}. */
        JSONObject shipped();

        /** The user's {@code saves/common/coop_options.json}, or {@code null} when absent. */
        JSONObject common();

        /** Drops any memoised read so the next call hits the engine again. */
        default void invalidate() {
        }
    }

    private static volatile CoopOptionsStore system;

    /**
     * How many distinct {@link Properties} sets keep a memoised store. Production uses one; the
     * bound exists so a caller that manufactures property sets in a loop cannot grow this forever.
     */
    private static final int PROPERTY_STORE_CACHE_SIZE = 8;

    /**
     * One store per {@link Properties} set, so {@link #warnOnce} really is once. Before this,
     * {@code CoopNetStartupConfig.from(Properties)} built a fresh store on every call, and every
     * call re-logged the same settings warning.
     *
     * <p>Keyed the way {@link Properties} itself compares, by content. Mutating a property set after
     * it has been resolved here simply misses the cache and builds a new store, which is wasteful
     * and never wrong. A bounded LRU rather than weak keys because the store holds the property set
     * (its {@code -D} layer reads through it), so a weak key could never be collected anyway.
     */
    private static final Map<Properties, CoopOptionsStore> BY_PROPERTIES =
            new LinkedHashMap<Properties, CoopOptionsStore>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Properties, CoopOptionsStore> eldest) {
                    return size() > PROPERTY_STORE_CACHE_SIZE;
                }
            };

    private final JsonSource source;
    private final Function<String, String> propertyReader;
    private final Set<String> warned = new HashSet<>();

    private Map<String, String> commonLayer;
    private Map<String, String> shippedLayer;

    /**
     * @param source         the two file layers
     * @param propertyReader the {@code -D} layer; {@code System::getProperty} in production
     */
    public CoopOptionsStore(JsonSource source, Function<String, String> propertyReader) {
        this.source = Objects.requireNonNull(source, "source");
        this.propertyReader = Objects.requireNonNull(propertyReader, "propertyReader");
    }

    /** The process-wide store: system properties over the engine's file surfaces. */
    public static CoopOptionsStore system() {
        CoopOptionsStore local = system;
        if (local == null) {
            synchronized (CoopOptionsStore.class) {
                local = system;
                if (local == null) {
                    local = new CoopOptionsStore(SettingsJsonSource.INSTANCE, System::getProperty);
                    system = local;
                }
            }
        }
        return local;
    }

    /**
     * A store whose {@code -D} layer is an explicit {@link Properties} instead of the real system
     * properties, over the same (memoised) engine file layers. This is what
     * {@code CoopNetStartupConfig.from(Properties)} uses, so a caller that hands in a property set
     * still gets the file stack underneath it.
     *
     * <p>Memoised per property set: the store carries the once-per-key warning memory, and building
     * a new one on every call turns "one WARN per bad setting" into one WARN per read.
     */
    public static CoopOptionsStore forProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        synchronized (BY_PROPERTIES) {
            CoopOptionsStore existing = BY_PROPERTIES.get(properties);
            if (existing != null) {
                return existing;
            }
            CoopOptionsStore created =
                    new CoopOptionsStore(SettingsJsonSource.INSTANCE, properties::getProperty);
            BY_PROPERTIES.put(properties, created);
            return created;
        }
    }

    /** Drops the {@link #forProperties} memo. Tests only; production never needs it. */
    static void clearPropertyStoreCache() {
        synchronized (BY_PROPERTIES) {
            BY_PROPERTIES.clear();
        }
    }

    /**
     * The winning value with <em>no</em> validation applied, or the registry default when no layer
     * supplies one. Never {@code null}.
     *
     * <p>This is what callers that already own a stricter parser use - {@code CoopNetStartupConfig}
     * refuses a malformed port or grace window rather than quietly substituting one, and that
     * refusal has to behave identically whether the bad value came from {@code -D} or from a file.
     * Everyone else should prefer {@link #string}, {@link #bool} or {@link #integer}, which clamp.
     *
     * <p>A key given as an explicitly empty {@code -D} resolves to {@code ""} instead of falling
     * through to a file layer; see {@link #rawOrNull}.
     */
    public String raw(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        String value = rawOrNull(option);
        return value == null ? option.defaultValue() : value;
    }

    /** The validated value: enum values canonicalised, bad values replaced, one WARN per key. */
    public String string(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        CoopOptionsRegistry.Coercion coercion = option.coerce(rawOrNull(option));
        if (!coercion.clean()) {
            warnOnce(key, coercion.warning());
        }
        return coercion.value();
    }

    /** The validated boolean. A non-boolean value logs once and falls back to the default. */
    public boolean bool(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        if (option.type() != CoopOptionsRegistry.Type.BOOL) {
            throw new IllegalArgumentException(key + " is not a BOOL option");
        }
        return Boolean.parseBoolean(string(key));
    }

    /** The validated integer, clamped into the registry's bounds. */
    public int integer(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        if (option.type() != CoopOptionsRegistry.Type.INT) {
            throw new IllegalArgumentException(key + " is not an INT option");
        }
        String value = string(key);
        if (value.isEmpty()) {
            throw new IllegalStateException(key + " has no value; it is an optional integer - read it"
                    + " with raw(...) and decide what \"unset\" means at the call site");
        }
        return Integer.parseInt(value);
    }

    /** Whether the {@code -D} layer names this key with a non-blank value. */
    public boolean hasProperty(String key) {
        return property(key) != null;
    }

    /** The {@code -D} value, trimmed, or {@code null} when absent or blank. */
    public String property(String key) {
        CoopOptionsRegistry.require(key);
        return trimToNull(propertyReader.apply(key));
    }

    /** Which layer supplied the winning value. */
    public Source sourceOf(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        if (propertyReader.apply(key) != null) {
            // Deliberately not property(), which trims a blank away: an explicitly empty -D is still
            // the property layer deciding. See rawOrNull.
            return Source.PROPERTY;
        }
        if (option.dOnly()) {
            return Source.DEFAULT;
        }
        if (common().containsKey(key)) {
            return Source.COMMON;
        }
        if (shipped().containsKey(key)) {
            return Source.SHIPPED;
        }
        return Source.DEFAULT;
    }

    /** Re-reads both file layers and clears the once-per-key warning memory. */
    public void reload() {
        commonLayer = null;
        shippedLayer = null;
        warned.clear();
        source.invalidate();
    }

    // ---- resolution ---------------------------------------------------------------------------

    private String rawOrNull(CoopOptionsRegistry.Option option) {
        String fromProperty = propertyReader.apply(option.key());
        if (fromProperty != null) {
            // Present-but-empty is a decision, not an absence: -Dcoop.password= is how a player
            // turns off a password their settings file sets, and trimming it to null first handed
            // the file value straight back - the opposite of what the command line asked for.
            // property() and hasProperty() keep the trim-to-null reading, so the "any role key
            // given as -D decides the role alone" rule in CoopNetStartupConfig is unchanged.
            return fromProperty.trim();
        }
        if (option.dOnly()) {
            // Deliberately does not consult either file. See CoopOptionsRegistry.Option#dOnly.
            return null;
        }
        String fromCommon = common().get(option.key());
        if (fromCommon != null) {
            return fromCommon;
        }
        return shipped().get(option.key());
    }

    private Map<String, String> common() {
        if (commonLayer == null) {
            commonLayer = flatten(safeCommon(), "saves/common/" + COMMON_FILE, COMMON_CONSEQUENCE);
        }
        return commonLayer;
    }

    private Map<String, String> shipped() {
        if (shippedLayer == null) {
            shippedLayer = flatten(safeShipped(), SHIPPED_PATH, SHIPPED_CONSEQUENCE);
        }
        return shippedLayer;
    }

    private JSONObject safeCommon() {
        try {
            return source.common();
        } catch (Exception | LinkageError ex) {
            warnOnce("layer:common", "could not read saves/common/" + COMMON_FILE + "; "
                    + COMMON_CONSEQUENCE + " (" + ex + ")");
            return null;
        }
    }

    private JSONObject safeShipped() {
        try {
            return source.shipped();
        } catch (Exception | LinkageError ex) {
            warnOnce("layer:shipped", "could not read " + SHIPPED_PATH + "; "
                    + SHIPPED_CONSEQUENCE + " (" + ex + ")");
            return null;
        }
    }

    /**
     * Turns one JSON layer into key/value strings, keeping only registered, non-{@code dOnly} keys.
     * Values may be JSON booleans or numbers as well as strings - a hand-edited file is allowed to
     * say {@code "coop.hostPort":7777} - so everything is stringified and validated later.
     */
    private Map<String, String> flatten(JSONObject json, String label, String consequence) {
        if (json == null) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<>();
        StringBuilder unknown = null;
        try {
            Iterator<?> keys = json.keys();
            while (keys.hasNext()) {
                Object rawKey = keys.next();
                if (rawKey == null) {
                    continue;
                }
                String key = rawKey.toString();
                if (key.startsWith("#") || key.startsWith("comment")) {
                    continue;
                }
                CoopOptionsRegistry.Option option = CoopOptionsRegistry.option(key);
                if (option == null) {
                    unknown = append(unknown, key);
                    continue;
                }
                if (option.dOnly()) {
                    unknown = append(unknown, key + " (-D only)");
                    continue;
                }
                Object value = json.opt(key);
                if (value == null || JSONObject.NULL.equals(value)) {
                    continue;
                }
                values.put(key, String.valueOf(value));
            }
        } catch (Exception | LinkageError ex) {
            warnOnce("parse:" + label, "could not parse " + label + "; " + consequence
                    + " (" + ex + ")");
            return Collections.emptyMap();
        }
        if (unknown != null) {
            warnOnce("unknown:" + label,
                    label + " has entries this build does not use and ignored: " + unknown);
        }
        return Collections.unmodifiableMap(values);
    }

    private static StringBuilder append(StringBuilder builder, String entry) {
        StringBuilder target = builder == null ? new StringBuilder() : builder;
        if (target.length() > 0) {
            target.append(", ");
        }
        return target.append(entry);
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            CoopLog.warn(CoopOptionsStore.class, "Coop options: " + message);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- production file source ----------------------------------------------------------------

    /**
     * The real {@code SettingsAPI} reader. Memoised process-wide so that constructing a store per
     * call (see {@link #forProperties}) does not re-read the files, and so that the one WARN a
     * broken install produces is logged once rather than once per read.
     */
    static final class SettingsJsonSource implements JsonSource {
        static final SettingsJsonSource INSTANCE = new SettingsJsonSource();

        private boolean shippedRead;
        private JSONObject shippedJson;
        private boolean commonRead;
        private JSONObject commonJson;

        private SettingsJsonSource() {
        }

        @Override
        public synchronized JSONObject shipped() {
            if (shippedRead) {
                return shippedJson;
            }
            SettingsAPI settings = settings();
            if (settings == null) {
                // No engine yet: a read from a unit test, or from a class that initialises before
                // the game does. That is not an answer, so nothing is latched - latching here would
                // cache "there are no shipped defaults" for the rest of the process.
                return null;
            }
            shippedRead = true;
            try {
                // loadJSON(path, modId) rather than getMergedJSONForMod: these settings belong to
                // this mod, and a merge would let an unrelated mod silently change how a coop
                // session connects.
                shippedJson = settings.loadJSON(SHIPPED_PATH, MOD_ID);
            } catch (Exception | LinkageError ex) {
                shippedJson = null;
                CoopLog.warn(CoopOptionsStore.class, "Coop options: " + SHIPPED_PATH
                        + " could not be loaded; " + SHIPPED_CONSEQUENCE, ex);
            }
            return shippedJson;
        }

        @Override
        public synchronized JSONObject common() {
            if (commonRead) {
                return commonJson;
            }
            SettingsAPI settings = settings();
            if (settings == null) {
                return null;
            }
            commonRead = true;
            try {
                if (!settings.fileExistsInCommon(COMMON_FILE)) {
                    commonJson = null;
                } else {
                    // putInWriteCache=false: this is a read-only consumer. Milestone 3 is what
                    // writes the file back, and it will do so explicitly.
                    commonJson = settings.readJSONFromCommon(COMMON_FILE, false);
                }
            } catch (Exception | LinkageError ex) {
                commonJson = null;
                CoopLog.warn(CoopOptionsStore.class, "Coop options: saves/common/" + COMMON_FILE
                        + " could not be read; " + COMMON_CONSEQUENCE, ex);
            }
            return commonJson;
        }

        /** {@code null} until the engine exists; never throws. */
        private static SettingsAPI settings() {
            try {
                return Global.getSettings();
            } catch (Exception | LinkageError ex) {
                return null;
            }
        }

        /** Whether a real shipped read has happened and been cached. Tests only. */
        synchronized boolean shippedCached() {
            return shippedRead;
        }

        /** Whether a real common read has happened and been cached. Tests only. */
        synchronized boolean commonCached() {
            return commonRead;
        }

        @Override
        public synchronized void invalidate() {
            shippedRead = false;
            shippedJson = null;
            commonRead = false;
            commonJson = null;
        }
    }
}
