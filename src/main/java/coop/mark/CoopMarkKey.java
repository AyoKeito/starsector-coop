package coop.mark;

import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;
import coop.util.CoopLog;

import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Which key writes a marker. {@code coop.markKey} holds an LWJGL key <em>name</em> ({@code F11},
 * {@code F9}, {@code HOME}...) rather than a scan code, because a number in a settings file is a
 * number nobody can check and the names are what {@code org.lwjgl.input.Keyboard} already knows.
 *
 * <p><b>Fails loudly, then keeps working.</b> An unknown name is a typo in a settings file, which the
 * player cannot see from inside the game, so it is a WARN naming both the bad value and the fallback
 * - and then the default key is installed anyway. A marker hotkey that silently does nothing is the
 * one outcome worth avoiding: it is only ever pressed when something has already gone wrong.
 *
 * <p>The lookup is injected so the resolution rules are testable without LWJGL; {@link #resolve()}
 * is the production entry point that binds it to the real keyboard.
 */
public final class CoopMarkKey {

    /** The shipped key, and the fallback for anything unparsable. */
    public static final String DEFAULT_NAME = "F11";

    /** LWJGL's {@code Keyboard.KEY_NONE}: what {@code getKeyIndex} answers for a name it lacks. */
    public static final int KEY_NONE = 0;

    private final String name;
    private final int code;
    private final boolean fellBack;

    private CoopMarkKey(String name, int code, boolean fellBack) {
        this.name = name;
        this.code = code;
        this.fellBack = fellBack;
    }

    /** The resolved key's LWJGL name, always uppercase. */
    public String name() {
        return name;
    }

    /** The LWJGL key code to compare {@code InputEventAPI.getEventValue()} against. */
    public int code() {
        return code;
    }

    /** True when the configured name was unusable and {@link #DEFAULT_NAME} was installed instead. */
    public boolean fellBack() {
        return fellBack;
    }

    /**
     * Resolves the configured name against a key-name lookup.
     *
     * @param configured the raw setting; blank means "use the default", which is not a fallback
     * @param lookup     name to LWJGL key code, answering {@link #KEY_NONE} for an unknown name
     */
    public static CoopMarkKey resolve(String configured, ToIntFunction<String> lookup) {
        String wanted = configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            wanted = DEFAULT_NAME;
        }
        int code = safeLookup(lookup, wanted);
        if (code != KEY_NONE) {
            return new CoopMarkKey(wanted, code, false);
        }
        int fallback = safeLookup(lookup, DEFAULT_NAME);
        CoopLog.warn(CoopMarkKey.class, "Coop marker key " + CoopOptionsRegistry.MARK_KEY + "=\""
                + configured + "\" is not an LWJGL key name; falling back to " + DEFAULT_NAME
                + ". Valid names are the org.lwjgl.input.Keyboard KEY_* constants without the"
                + " KEY_ prefix (F11, F9, HOME, INSERT, ...).");
        return new CoopMarkKey(DEFAULT_NAME, fallback, true);
    }

    /**
     * The production resolution: the option stack ({@code -D}, then the player's own
     * {@code saves/common/coop_options.json.data}, then the shipped defaults) against the real
     * LWJGL keyboard.
     */
    public static CoopMarkKey resolve() {
        String configured;
        try {
            configured = CoopOptionsStore.system().string(CoopOptionsRegistry.MARK_KEY);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopMarkKey.class, "Could not read " + CoopOptionsRegistry.MARK_KEY
                    + "; using " + DEFAULT_NAME, ex);
            configured = DEFAULT_NAME;
        }
        return resolve(configured, CoopMarkKey::lwjglKeyIndex);
    }

    /**
     * {@code Keyboard.getKeyIndex} behind a total wrapper: a missing or unloadable LWJGL is
     * {@link #KEY_NONE}, which sends the caller down the fallback path rather than up the stack.
     */
    private static int lwjglKeyIndex(String keyName) {
        try {
            return org.lwjgl.input.Keyboard.getKeyIndex(keyName);
        } catch (RuntimeException | LinkageError ex) {
            return KEY_NONE;
        }
    }

    private static int safeLookup(ToIntFunction<String> lookup, String keyName) {
        if (lookup == null) {
            return KEY_NONE;
        }
        try {
            return lookup.applyAsInt(keyName);
        } catch (RuntimeException | LinkageError ex) {
            return KEY_NONE;
        }
    }
}
