package coop.time;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Objects;

/**
 * Phase 7b: the single home for the engine access that makes campaign fast-forward a <em>shared</em>
 * time speed instead of a per-client one.
 *
 * <p>Three things happen while a coop session is active, on BOTH roles:
 *
 * <ul>
 *   <li>vanilla's <em>toggle</em> fast-forward input mode is forced on. In the default hold-Shift
 *       mode {@code CampaignState.processInput} re-polls the raw key state every frame and clobbers
 *       any value a mod writes; in toggle mode that poll block is skipped entirely and the field is
 *       only flipped by a discrete, {@code isConsumed()}-checked {@code FAST_FORWARD} key event — so
 *       the guest's existing {@code CoopCampaignInputBlocker} consumption really blocks it and our
 *       field write sticks between frames;</li>
 *   <li>{@code campaignSpeedupMult} is forced to {@value #SESSION_MULT} through the public
 *       {@code SettingsAPI} so both clients run the identical speed regardless of local json;</li>
 *   <li>the host's {@code fastForward} field is mirrored onto the guest by {@link CoopTimeLock#apply}
 *       via {@link #writeFastForward(boolean)} (the {@code TIME_SNAPSHOT} already carries the bit).</li>
 * </ul>
 *
 * <p><b>Failure degrades to Phase 7 behaviour, never to desync.</b> If any handle fails to resolve,
 * or {@code -Dcoop.ff.disable=true} is set, the lock goes sticky-unavailable, logs one warning, and
 * {@link #enforceSessionState()} instead forces the multiplier to {@value #FALLBACK_MULT} using
 * public API only — exactly the old {@code settings.json} 1x lock, now applied at runtime.
 *
 * <p><b>Engine access notes (0.98a-RC8, pinned safe by the Phase 5 exact-version handshake).</b> All
 * of it is {@code java.lang.invoke} — {@code java.lang.reflect.*} is hard-blocked by the game's
 * script classloader and crashes in-game even though it compiles and unit-tests green (see
 * {@code CoopBarSync.resolveHandles()}, whose lazy-resolve + {@code Throwable}-catch shape this
 * copies). The toggle flag is the private static {@code boolean} field literally named
 * {@code class} on {@code com.fs.starfarer.settings.StarfarerSettings} (yes, the Java keyword; it is
 * distinct from the separate field {@code class.class}). Its accessors are {@code Oo0000()} (getter)
 * and {@code ö00000(boolean)} (setter, U+00F6 followed by five zeros) — recorded here for the record
 * only: we deliberately resolve the FIELD with {@code findStaticGetter}/{@code findStaticSetter}
 * rather than the methods, because five different static {@code (boolean)} setters on that class
 * print identically under {@code javap} and picking one by name is a coin flip.
 */
public final class CoopFastForwardLock {

    /** The engine default, and the fixed shared session speed (v1 policy: no dynamic multipliers). */
    public static final float SESSION_MULT = 2f;
    /** Fallback lock: 1x for everyone, matching pre-7b behaviour, via public API only. */
    public static final float FALLBACK_MULT = 1f;
    /** Debug lever (read once) that forces the fallback path without a code edit. */
    public static final String DISABLE_PROPERTY = "coop.ff.disable";

    private static final String MULT_KEY = "campaignSpeedupMult";

    /**
     * The engine seam. Every method may throw: the production implementation is a bundle of
     * {@link MethodHandle}s whose {@code invoke} is declared {@code throws Throwable}. Callers in
     * this class catch {@code Throwable} and go sticky-unavailable.
     */
    public interface Handles {
        boolean readToggle() throws Throwable;

        void writeToggle(boolean value) throws Throwable;

        boolean readFastForward() throws Throwable;

        void writeFastForward(boolean value) throws Throwable;
    }

    /** Resolver seam so tests can inject fakes (and a throwing resolver) with no engine on the path. */
    public interface HandlesResolver {
        /** @return the resolved handles, or {@code null} when unavailable. */
        Handles resolve() throws Throwable;
    }

    /** The {@code campaignSpeedupMult} seam — public {@code SettingsAPI} in production. */
    public interface MultSetting {
        float read() throws Throwable;

        void write(float value) throws Throwable;
    }

    private final HandlesResolver resolver;
    private final MultSetting mult;
    private final boolean disabledByProperty;

    private boolean resolveAttempted;
    private Handles handles;
    /** Sticky: once anything throws we never touch the engine handles again this session. */
    private boolean failed;
    private boolean warned;

    /** True between the first {@link #enforceSessionState()} and the matching restore. */
    private boolean enforcing;
    private boolean originalToggleKnown;
    private boolean originalToggle;

    public CoopFastForwardLock() {
        this(CoopFastForwardLock::resolveEngineHandles, new SettingsMult(),
                Boolean.getBoolean(DISABLE_PROPERTY));
    }

    public CoopFastForwardLock(HandlesResolver resolver, MultSetting mult, boolean disabled) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.mult = Objects.requireNonNull(mult, "mult");
        this.disabledByProperty = disabled;
    }

    /** Resolves on first call. False means the session runs on the public-API 1x fallback lock. */
    public boolean isAvailable() {
        return ensureResolved();
    }

    /**
     * Called every frame while a coop session is active, on both roles. Re-asserting the toggle flag
     * per frame is deliberate and cheap (one static field read, a setter only on a real difference):
     * the player can untick the vanilla settings-menu checkbox mid-session, and re-forcing makes that
     * harmless.
     */
    public void enforceSessionState() {
        enforcing = true;
        if (!ensureResolved()) {
            setMult(FALLBACK_MULT);
            return;
        }
        try {
            boolean toggle = handles.readToggle();
            if (!originalToggleKnown) {
                originalToggle = toggle;
                originalToggleKnown = true;
            }
            if (!toggle) {
                handles.writeToggle(true);
            }
        } catch (Throwable ex) {
            fail("Failed to force vanilla toggle fast-forward mode", ex);
            setMult(FALLBACK_MULT);
            return;
        }
        setMult(SESSION_MULT);
    }

    /** Pump seam: the "session just ended" branch, a no-op unless we were enforcing. */
    public void restoreDefaultsIfEnforcing() {
        if (!enforcing) {
            return;
        }
        restoreDefaults();
    }

    /**
     * Puts the local client back to vanilla: the player's original toggle-mode preference and the
     * engine-default multiplier. Safe to call when nothing was ever enforced.
     */
    public void restoreDefaults() {
        enforcing = false;
        setMult(SESSION_MULT);
        if (handles == null || !originalToggleKnown) {
            originalToggleKnown = false;
            return;
        }
        originalToggleKnown = false;
        try {
            if (handles.readToggle() != originalToggle) {
                handles.writeToggle(originalToggle);
            }
        } catch (Throwable ex) {
            fail("Failed to restore the vanilla fast-forward toggle setting", ex);
        }
    }

    /**
     * Guest side: mirror the host's {@code fastForward} field. Idempotent-on-change, the same
     * discipline {@link CoopTimeLock#apply} uses for pause. No-op when unavailable — the fallback
     * multiplier already has both clients pinned at 1x.
     */
    public void writeFastForward(boolean desired) {
        if (!ensureResolved()) {
            return;
        }
        try {
            if (handles.readFastForward() != desired) {
                handles.writeFastForward(desired);
            }
        } catch (Throwable ex) {
            fail("Failed to mirror the host fast-forward state", ex);
        }
    }

    /** Test/diagnostic seam: whether the session-active branch ran without a matching restore. */
    boolean isEnforcing() {
        return enforcing;
    }

    private boolean ensureResolved() {
        if (disabledByProperty) {
            if (!warned) {
                warned = true;
                CoopLog.warn(CoopFastForwardLock.class, "-D" + DISABLE_PROPERTY
                        + "=true: shared fast-forward disabled by debug property; coop session falls"
                        + " back to a locked 1x campaign speed (dates DRIFT if the other client"
                        + " is not also running this flag)");
            }
            return false;
        }
        if (failed) {
            return false;
        }
        if (!resolveAttempted) {
            resolveAttempted = true;
            try {
                handles = resolver.resolve();
            } catch (Throwable ex) {
                fail("Failed to resolve campaign fast-forward handles", ex);
                return false;
            }
            if (handles == null) {
                fail("Campaign fast-forward handles unavailable", null);
                return false;
            }
        }
        return handles != null;
    }

    /** Reads the current value back first so we only write on a real difference (see plan 7b). */
    private void setMult(float value) {
        try {
            if (mult.read() != value) {
                mult.write(value);
            }
        } catch (Throwable ex) {
            fail("Failed to set " + MULT_KEY, ex);
        }
    }

    private void fail(String message, Throwable ex) {
        failed = true;
        handles = null;
        if (warned) {
            return;
        }
        warned = true;
        if (ex == null) {
            CoopLog.warn(CoopFastForwardLock.class,
                    message + "; coop session falls back to a locked 1x campaign speed");
        } else {
            CoopLog.warn(CoopFastForwardLock.class,
                    message + "; coop session falls back to a locked 1x campaign speed", ex);
        }
    }

    // ---- production engine access ---------------------------------------------------------------

    private static Handles resolveEngineHandles() throws Throwable {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return null;
        }
        Object campaignUi = sector.getCampaignUI();
        if (campaignUi == null) {
            return null;
        }
        // Fact 1: the object behind CampaignUIAPI *is* com.fs.starfarer.campaign.CampaignState, which
        // declares the unobfuscated private field `fastForward`. No class literal, no name string.
        Class<?> campaignStateClass = campaignUi.getClass();
        MethodHandle ffGetter = null;
        MethodHandle ffSetter = null;
        NoSuchFieldException lastMiss = null;
        for (Class<?> c = campaignStateClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                MethodHandles.Lookup priv = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                ffGetter = priv.findGetter(c, "fastForward", boolean.class);
                ffSetter = priv.findSetter(c, "fastForward", boolean.class);
                break;
            } catch (NoSuchFieldException miss) {
                lastMiss = miss;
            }
        }
        if (ffGetter == null || ffSetter == null) {
            throw lastMiss != null ? lastMiss : new NoSuchFieldException("fastForward");
        }
        // Class.forName is java.lang, not java.lang.reflect, so it is expected to pass the script
        // sandbox the way privateLookupIn does.
        Class<?> settingsClass = Class.forName("com.fs.starfarer.settings.StarfarerSettings",
                false, campaignStateClass.getClassLoader());
        MethodHandles.Lookup settingsLookup =
                MethodHandles.privateLookupIn(settingsClass, MethodHandles.lookup());
        // The toggle-mode flag. Field, not method: see the class doc — Oo0000()/ö00000(boolean) are
        // the accessors, but five distinct static (boolean) setters are indistinguishable by name.
        MethodHandle toggleGetter = settingsLookup.findStaticGetter(settingsClass, "class", boolean.class);
        MethodHandle toggleSetter = settingsLookup.findStaticSetter(settingsClass, "class", boolean.class);
        return new EngineHandles(campaignStateClass, ffGetter, ffSetter, toggleGetter, toggleSetter);
    }

    /**
     * Holds the CampaignState <em>class</em>, never an instance: the current campaign UI object is
     * fetched fresh per call so a handle bundle never outlives the campaign it was resolved against.
     * A different runtime class than the one the handles were bound to fails the invoke loudly and
     * trips the lock's sticky fallback, which is the intended outcome.
     */
    private record EngineHandles(Class<?> campaignStateClass, MethodHandle ffGetter, MethodHandle ffSetter,
                                 MethodHandle toggleGetter, MethodHandle toggleSetter)
            implements Handles {

        private Object campaignState() {
            Object ui = Global.getSector().getCampaignUI();
            if (ui == null) {
                throw new IllegalStateException("No campaign UI");
            }
            return campaignStateClass.cast(ui);
        }

        @Override
        public boolean readToggle() throws Throwable {
            return (boolean) toggleGetter.invoke();
        }

        @Override
        public void writeToggle(boolean value) throws Throwable {
            toggleSetter.invoke(value);
        }

        @Override
        public boolean readFastForward() throws Throwable {
            return (boolean) ffGetter.invoke(campaignState());
        }

        @Override
        public void writeFastForward(boolean value) throws Throwable {
            ffSetter.invoke(campaignState(), value);
        }
    }

    /** Public {@code SettingsAPI} — always available, no handles, so the fallback lock always works. */
    private static final class SettingsMult implements MultSetting {
        @Override
        public float read() {
            return Global.getSettings().getFloat(MULT_KEY);
        }

        @Override
        public void write(float value) {
            Global.getSettings().setFloat(MULT_KEY, Float.valueOf(value));
        }
    }
}
