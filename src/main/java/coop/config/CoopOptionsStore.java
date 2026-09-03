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
 *   -Dcoop.*                             (highest - dev/debug override, unchanged from before this phase)
 *   saves/common/coop_options.json.data  (the user's own overrides; survives a mod update)
 *   data/config/coop_options.json        (shipped defaults; overwritten on every mod update)
 *   the registry default                 (lowest)
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
 * <p><b>Policy tier.</b> {@link CoopOptionsRegistry.Tier#POLICY} keys read through this stack are the
 * value a <em>new</em> campaign is seeded with, not the value in force: once a campaign exists,
 * {@link CoopOptionsPolicy} owns those keys out of the campaign's own save and syncs them to the
 * guest. {@link #writeOverride} refuses them for the same reason.
 */
public final class CoopOptionsStore {

    /** Mod-relative path of the shipped defaults. */
    public static final String SHIPPED_PATH = "data/config/coop_options.json";
    /**
     * The name handed to {@code SettingsAPI}'s {@code ...Common} calls. Not what the file is called
     * on disk: see {@link #COMMON_PATH}.
     */
    public static final String COMMON_FILE = "coop_options.json";
    /**
     * Where the user's overrides actually sit, for anything a player reads. The engine appends
     * {@code .data} to the name in every one of its {@code saves/common} calls - the write, the
     * read, the existence check and the delete alike - so the file this store writes as
     * {@link #COMMON_FILE} is {@code coop_options.json.data} when the player goes looking for it.
     * Confirmed against the 0.98a implementation of {@code SettingsAPI}, which builds
     * {@code <saves>/common/<name>.data} in all four methods.
     */
    public static final String COMMON_PATH = "saves/common/coop_options.json.data";
    /** The mod id {@code loadJSON} resolves {@link #SHIPPED_PATH} against. */
    public static final String MOD_ID = "coop";

    /**
     * The three {@code dOnly} keys the Phase 31 launcher is allowed to hand over through
     * {@link #COMMON_PATH}. See {@link #rawOneShot} for the reasoning; nothing else reads them from
     * a file, and they still never appear in the shipped defaults or on the options page.
     */
    public static final Set<String> ONE_SHOT_KEYS = Set.of(
            CoopOptionsRegistry.NEW_GAME_SEED,
            CoopOptionsRegistry.SECTOR_SIZE,
            CoopOptionsRegistry.SECTOR_AGE);

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

        /** The user's {@code saves/common/coop_options.json.data}, or {@code null} when absent. */
        JSONObject common();

        /**
         * Whether the most recent {@link #common()} <em>failed</em>, as opposed to finding no file.
         *
         * <p>Both cases return {@code null} from {@link #common()}, and for reading that is the
         * right answer either way - a file that will not parse contributes nothing, same as a file
         * that is not there. For <em>writing</em> the two are opposites: rewriting an absent file
         * creates it, while rewriting an unreadable one throws away every setting in it, including
         * the ones this build cannot see. {@link CoopOptionsStore#writeOverrides} refuses in the
         * second case, and this is how it tells them apart.
         */
        default boolean commonReadFailed() {
            return false;
        }

        /**
         * Whether {@code saves/common/coop_options.json.data} exists at all. Only consulted alongside
         * {@link #commonReadFailed()}; a source that cannot answer says "no file" and a failed read
         * over a file that is not there is then treated as harmless.
         */
        default boolean commonFileExists() {
            return false;
        }

        /**
         * Replaces {@code saves/common/coop_options.json.data} with {@code json} and makes the new
         * content what {@link #common()} returns from here on.
         *
         * <p>Default: refuse. A source that cannot write says so rather than pretending, so the
         * options page can tell the player the setting did not stick.
         *
         * @return true when the file was written
         */
        default boolean writeCommon(JSONObject json) {
            return false;
        }

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
    /** See {@link #oneShotCommon()}. */
    private Map<String, String> oneShotLayer;
    private Map<String, String> shippedLayer;

    /**
     * Whether the last attempt to read the common layer failed, as opposed to finding no file. Only
     * {@link #writeOverrides} cares: reads treat the two the same, writes must not.
     */
    private boolean commonFailed;

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

    /**
     * Phase 31: the value of a <em>one-shot new-game key</em> - {@code coop.newGameSeed},
     * {@code coop.sectorSize}, {@code coop.sectorAge} - resolved as {@code -D} first and then the
     * user's own {@code saves/common/coop_options.json.data}.
     *
     * <p><b>Why this exists.</b> Phase 28 classified those three as {@code dOnly}: they are one-shot
     * gestures, so they are not in the shipped defaults file, they are not on the in-game options
     * page, and {@link #writeOverrides} refuses them. Phase 31 then added a launcher that cannot set
     * a {@code -D} at all - it is forbidden from editing {@code vmparams}, and {@code starsector.exe}
     * reads its JVM flags from nowhere else. The launcher's only channel is the settings file it
     * writes immediately before starting the game.
     *
     * <p>So this method is the one seam between the two decisions, and it is deliberately narrow:
     * the launcher-written user file is read, the shipped defaults file is <em>not</em> (a value
     * there would be a standing setting, which is exactly what Phase 28 refused), and no other key
     * may be read this way.
     *
     * @throws IllegalArgumentException when {@code key} is not one of the three
     */
    public String rawOneShot(String key) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        if (!ONE_SHOT_KEYS.contains(key)) {
            throw new IllegalArgumentException(key + " is not a one-shot new-game key; use raw(...)");
        }
        String fromProperty = propertyReader.apply(key);
        if (fromProperty != null) {
            return fromProperty.trim();
        }
        String fromCommon = oneShotCommon().get(key);
        return fromCommon == null ? option.defaultValue() : fromCommon;
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

    /**
     * Phase 28 milestone 3: writes one user override into {@code saves/common/coop_options.json.data}.
     *
     * <p>The file is rewritten whole from the override map that is already loaded, with this key
     * replaced (or removed, for a null {@code value}, which puts the key back to the shipped
     * default). Keys this build does not know about are carried through untouched: a settings file
     * written by a newer version of the mod must not be silently trimmed by an older one.
     *
     * <p><b>Not for policy or {@code -D}-only keys.</b> A policy value belongs to a campaign and is
     * written by {@link CoopOptionsPolicy}; a {@code -D}-only key is deliberately not file-backed.
     * Both are refused rather than written somewhere they would be ignored.
     *
     * <p>Failure is one WARN and {@code false} - never an exception, and the checked type the engine
     * declares is caught as {@code Exception} so this class never names it (see the class javadoc).
     *
     * @param value the new value, or {@code null} to drop the override entirely
     * @return true when the file was written
     */
    public boolean writeOverride(String key, String value) {
        Map<String, String> single = new LinkedHashMap<>();
        single.put(key, value);
        return writeOverrides(single);
    }

    /**
     * Phase 28 milestone 3: writes several user overrides in <em>one</em> file rewrite.
     *
     * <p>What "Reset to defaults" uses. Doing it one {@link #writeOverride} per key would rewrite
     * the file once per key and, when the file is not writable, produce one WARN per key - a wall of
     * identical lines for a single player action.
     *
     * <p>Same rules as the single-key form: a {@code null} value drops the override, unknown keys in
     * the file are carried through untouched, and policy / {@code -D}-only keys are refused with
     * their own WARN and simply left out of the write.
     *
     * @param values key to new value; a {@code null} value drops that key's override
     * @return true when the file was written (or when there was nothing left to write)
     */
    public boolean writeOverrides(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return true;
        }
        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
            if (option.dOnly()) {
                CoopLog.warn(CoopOptionsStore.class, "Coop options: " + key
                        + " is a command-line-only setting and is not written to a file");
                continue;
            }
            if (option.tier() == CoopOptionsRegistry.Tier.POLICY) {
                CoopLog.warn(CoopOptionsStore.class, "Coop options: " + key
                        + " is host policy and belongs to the campaign, not to " + COMMON_PATH);
                continue;
            }
            accepted.put(key, entry.getValue());
        }
        if (accepted.isEmpty()) {
            return false;
        }
        String label = describe(accepted.keySet());
        // Refuse before composing anything. safeCommon() hands back null for "no file" and for
        // "the file is there but will not parse" alike, and rewriting the file from that null would
        // replace a settings file the player hand-edited (and broke) with whatever this one action
        // set - silently deleting every other setting in it. A file that cannot be read is a file
        // the player has to fix by hand.
        if (commonUnreadable()) {
            CoopLog.warn(CoopOptionsStore.class, "Coop options: " + COMMON_PATH
                    + " exists but could not be read, so it will not be rewritten - that would throw"
                    + " away everything in it. Fix it by hand (it must be plain JSON, with no #"
                    + " comments) or delete it. " + label + " not saved.");
            return false;
        }
        JSONObject json = new JSONObject();
        try {
            JSONObject existing = safeCommon();
            if (existing != null) {
                Iterator<?> keys = existing.keys();
                while (keys.hasNext()) {
                    Object rawKey = keys.next();
                    if (rawKey == null) {
                        continue;
                    }
                    String name = rawKey.toString();
                    if (accepted.containsKey(name)) {
                        continue;
                    }
                    Object existingValue = existing.opt(name);
                    if (existingValue != null && !JSONObject.NULL.equals(existingValue)) {
                        json.put(name, existingValue);
                    }
                }
            }
            for (Map.Entry<String, String> entry : accepted.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                json.put(entry.getKey(),
                        CoopOptionsRegistry.require(entry.getKey()).coerce(entry.getValue()).value());
            }
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopOptionsStore.class, "Coop options: could not compose "
                    + COMMON_PATH + "; " + label + " not saved", ex);
            return false;
        }
        boolean written;
        try {
            written = source.writeCommon(json);
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopOptionsStore.class, "Coop options: could not write "
                    + COMMON_PATH + "; " + label + " not saved", ex);
            return false;
        }
        if (!written) {
            CoopLog.warn(CoopOptionsStore.class, "Coop options: " + COMMON_PATH
                    + " is not writable here; " + label + " not saved");
            return false;
        }
        // The layer is re-derived from what was just written, so the next read reflects the change
        // without waiting for a relaunch. The warning memory survives: a bad value elsewhere in the
        // file has already been reported and does not need reporting again per edit.
        //
        // Every memoised store is dropped too, not just this one: forProperties() hands out stores
        // that share this process's SettingsJsonSource, so a page that writes through system() would
        // otherwise leave CoopNetStartupConfig's store answering out of a layer map built before the
        // write.
        dropCommonLayer();
        invalidateMemoisedCommonLayers();
        return true;
    }

    /** "coop.hudCorner" for one key, "3 settings" for several; used in the failure WARNs. */
    private static String describe(Set<String> keys) {
        if (keys.size() == 1) {
            return keys.iterator().next();
        }
        return keys.size() + " settings";
    }

    /**
     * True when the user's file is present but could not be read or parsed - the one state in which
     * rewriting it destroys data. "Absent" is deliberately <em>not</em> this: creating the file is
     * exactly what the first override does.
     */
    public boolean commonUnreadable() {
        // Resolve the layer first; commonFailed is only meaningful after a read has been attempted.
        common();
        return commonFailed && sourceSays(JsonSource::commonFileExists);
    }

    private boolean sourceSays(Function<JsonSource, Boolean> question) {
        try {
            return Boolean.TRUE.equals(question.apply(source));
        } catch (Exception | LinkageError ex) {
            return false;
        }
    }

    private synchronized void dropCommonLayer() {
        commonLayer = null;
        commonFailed = false;
    }

    /** Drops the common layer of {@link #system()} and of every {@link #forProperties} store. */
    private static void invalidateMemoisedCommonLayers() {
        CoopOptionsStore local = system;
        if (local != null) {
            local.dropCommonLayer();
        }
        synchronized (BY_PROPERTIES) {
            for (CoopOptionsStore store : BY_PROPERTIES.values()) {
                store.dropCommonLayer();
            }
        }
    }

    /** Re-reads both file layers and clears the once-per-key warning memory. */
    public void reload() {
        commonLayer = null;
        oneShotLayer = null;
        commonFailed = false;
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
            commonLayer = flatten(safeCommon(), COMMON_PATH, COMMON_CONSEQUENCE);
        }
        return commonLayer;
    }

    /**
     * The {@link #ONE_SHOT_KEYS} present in the user file, which {@link #flatten} deliberately drops
     * from the ordinary layer. Cached like the others, and cleared by {@link #reload()}.
     */
    private Map<String, String> oneShotCommon() {
        if (oneShotLayer == null) {
            Map<String, String> values = new LinkedHashMap<>();
            JSONObject json = safeCommon();
            if (json != null) {
                try {
                    for (String key : ONE_SHOT_KEYS) {
                        Object value = json.opt(key);
                        if (value == null || JSONObject.NULL.equals(value)) {
                            continue;
                        }
                        String text = String.valueOf(value).trim();
                        if (!text.isEmpty()) {
                            values.put(key, text);
                        }
                    }
                } catch (Exception | LinkageError ex) {
                    warnOnce("oneShot:" + COMMON_PATH, "could not read the one-shot new-game keys"
                            + " from " + COMMON_PATH + " (" + ex + ")");
                }
            }
            oneShotLayer = Collections.unmodifiableMap(values);
        }
        return oneShotLayer;
    }

    private Map<String, String> shipped() {
        if (shippedLayer == null) {
            shippedLayer = flatten(safeShipped(), SHIPPED_PATH, SHIPPED_CONSEQUENCE);
        }
        return shippedLayer;
    }

    private JSONObject safeCommon() {
        try {
            JSONObject json = source.common();
            // The source swallows its own read failure (it has to: a broken file must not stop the
            // game starting) and reports it through this flag instead.
            commonFailed = sourceSays(JsonSource::commonReadFailed);
            return json;
        } catch (Exception | LinkageError ex) {
            commonFailed = true;
            warnOnce("layer:common", "could not read " + COMMON_PATH + "; "
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
                    // The three one-shot new-game keys are the Phase 31 launcher's only channel and
                    // are read by rawOneShot instead, so finding one here is expected rather than a
                    // mistake worth warning about. Every other -D-only key still is one.
                    if (!ONE_SHOT_KEYS.contains(key)) {
                        unknown = append(unknown, key + " (-D only)");
                    }
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
        private boolean commonFailed;
        private boolean commonExists;

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
                commonExists = settings.fileExistsInCommon(COMMON_FILE);
                if (!commonExists) {
                    commonJson = null;
                } else {
                    // putInWriteCache=false: this is a read-only consumer. Milestone 3 is what
                    // writes the file back, and it will do so explicitly.
                    commonJson = settings.readJSONFromCommon(COMMON_FILE, false);
                }
            } catch (Exception | LinkageError ex) {
                commonJson = null;
                commonFailed = true;
                // If fileExistsInCommon itself threw we do not know whether the file is there, and
                // the safe assumption is that it is: it makes writeOverrides refuse, which is
                // recoverable, where guessing "absent" would overwrite it.
                commonExists = true;
                CoopLog.warn(CoopOptionsStore.class, "Coop options: " + COMMON_PATH
                        + " could not be read; " + COMMON_CONSEQUENCE, ex);
            }
            return commonJson;
        }

        @Override
        public synchronized boolean commonReadFailed() {
            return commonFailed;
        }

        @Override
        public synchronized boolean commonFileExists() {
            return commonExists;
        }

        /**
         * Writes the file and adopts the written content as the memoised read, so the next
         * {@link #common()} returns what is on disk without a re-read.
         *
         * <p>{@code onlyIfChanged=true}: the engine skips the write when the content is identical,
         * which turns a page that re-saves the same value into no disk traffic at all.
         */
        @Override
        public synchronized boolean writeCommon(JSONObject json) {
            if (json == null) {
                return false;
            }
            SettingsAPI settings = settings();
            if (settings == null) {
                return false;
            }
            try {
                settings.writeJSONToCommon(COMMON_FILE, json, true);
            } catch (Exception | LinkageError ex) {
                CoopLog.warn(CoopOptionsStore.class, "Coop options: " + COMMON_PATH
                        + " could not be written; the setting holds for this session only", ex);
                return false;
            }
            commonJson = json;
            commonRead = true;
            commonFailed = false;
            commonExists = true;
            return true;
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
            commonFailed = false;
            commonExists = false;
        }
    }
}
