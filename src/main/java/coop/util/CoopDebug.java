package coop.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.config.CoopOptionsRegistry;

/**
 * Opt-in switch for the dormant coop diagnostics (orbit-dump, dialog-state). These are off by default
 * so they don't spam the log, but stay in the code so a desync can be re-investigated without a new
 * build. Enable either way:
 *
 * <ul>
 *   <li>at launch: JVM arg {@code -Dcoop.debug.diagnostics=true} (set on the instance you want to trace);</li>
 *   <li>in-game, no relaunch: set the sector memory flag, e.g. console {@code SetMemoryKey $coopDebug true},
 *       or {@code Global.getSector().getMemoryWithoutUpdate().set("$coopDebug", true)}. The flag is
 *       re-read every {@value #TOGGLE_POLL_FRAMES} pump frames, so it takes a few seconds to engage.</li>
 * </ul>
 *
 * <p><b>Cost.</b> {@link #diagnosticsEnabled()} is called 3-4x per pump frame from the hot paths, so it
 * is a single volatile boolean read and nothing else. The actual lookup — {@code Boolean.getBoolean},
 * which is a synchronized {@code Properties} hit, plus a live sector-memory read — happens in
 * {@link #pollFrame()}, driven from {@code CoopNetPump.advance()} on the same 300-frame cadence
 * {@link CoopFrameProfiler} uses for its own toggle.
 */
public final class CoopDebug {
    public static final String PROPERTY = CoopOptionsRegistry.DEBUG_DIAGNOSTICS;
    public static final String MEMORY_FLAG = "$coopDebug";

    /**
     * Phase 18 latency lever: {@code -Dcoop.debug.interactionDelayMs=<n>} makes the HOST hold every
     * inbound {@code INTERACTION_CLAIM} for {@code n} ms before arbitrating it, which widens the
     * claim race to something a human can hit on localhost (on a real link the window is only host
     * frame + TCP one-way + guest frame). Dormant at 0, which is the default and what every shipped
     * session runs; it is a test instrument, not a tuning knob.
     */
    public static final String INTERACTION_DELAY_PROPERTY = CoopOptionsRegistry.DEBUG_INTERACTION_DELAY_MS;

    /**
     * Sanity cap on the lever: past this the session is unplayable and the value is a typo.
     *
     * <p>Public because it is the <em>one</em> source for this bound. {@code CoopOptionsRegistry}
     * declares it as the key's {@code max} (so the registry clamps a file value to it and the
     * launcher's spinner will not offer more), and {@link #readInteractionClaimDelayMillis()}
     * applies it again to the raw {@code -D} path, which never passes through the registry.
     */
    public static final int MAX_INTERACTION_DELAY_MILLIS = 60_000;

    /**
     * Live spike: {@code -Dcoop.debug.allyPullIn=true} stops the <em>player</em> mirror from carrying
     * {@code FLEET_IGNORES_OTHER_FLEETS} and stops the host's per-frame battle eject, so the engine is
     * allowed to drag the partner's mirror into a battle as an ally and the log can record what it
     * then does. Off in every shipped session; on, the mirror is unprotected and can be shot at.
     */
    public static final String ALLY_PULL_IN_PROPERTY = CoopOptionsRegistry.DEBUG_ALLY_PULL_IN;

    /**
     * Second run of the same spike: {@code -Dcoop.debug.allyPullInDropShield=true} additionally drops
     * the player mirror's {@code setNoEngaging} shield. Only meaningful together with
     * {@link #ALLY_PULL_IN_PROPERTY}, which is why {@link #allyPullInDropShieldEnabled()} ands the two
     * — the point of keeping them separate is being able to tell the two effects apart in one log.
     */
    public static final String ALLY_PULL_IN_DROP_SHIELD_PROPERTY =
            CoopOptionsRegistry.DEBUG_ALLY_PULL_IN_DROP_SHIELD;

    /** How often {@link #pollFrame()} re-reads the toggle, in pump frames (~5 s at 60 fps). */
    static final int TOGGLE_POLL_FRAMES = 300;

    /**
     * The flag every call site branches on. Seeded from the JVM property at class init so a launch-time
     * {@code -Dcoop.debug.diagnostics=true} is live before the first frame ever runs, then refreshed by
     * {@link #pollFrame()}. Volatile only because the JVM property could in principle be flipped from
     * another thread; every read and the poll itself are campaign-thread.
     */
    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    /**
     * Milliseconds the host holds an inbound interaction claim before arbitrating it; 0 = dormant.
     * Seeded at class init so a launch-time property is live before the first frame, then refreshed
     * on the same poll as {@link #enabled} (so it can also be flipped from the console mid-session).
     */
    private static volatile int interactionClaimDelayMillis = readInteractionClaimDelayMillis();
    /**
     * The ally pull-in spike switches. Seeded once at class init and never polled: unlike the
     * diagnostics toggle there is no in-game flag for them, and both are read from paths that must
     * cost a field access — {@code assertIgnoresOtherFleets} runs on every snapshot apply and the
     * host's battle eject runs on every frame.
     */
    private static volatile boolean allyPullIn = Boolean.getBoolean(ALLY_PULL_IN_PROPERTY);
    private static volatile boolean allyPullInDropShield =
            Boolean.getBoolean(ALLY_PULL_IN_DROP_SHIELD_PROPERTY);
    private static int pollFrames;

    private CoopDebug() {
    }

    /** True when coop diagnostics should log, via the JVM property or the in-game memory flag. */
    public static boolean diagnosticsEnabled() {
        return enabled;
    }

    /**
     * How long the host should delay processing an inbound {@code INTERACTION_CLAIM}, in ms. Zero
     * (the default) means process it immediately; see {@link #INTERACTION_DELAY_PROPERTY}.
     */
    public static int interactionClaimDelayMillis() {
        return interactionClaimDelayMillis;
    }

    /**
     * True when the ally pull-in spike is armed on this instance: the player mirror is created
     * joinable and the host's battle eject only observes. See {@link #ALLY_PULL_IN_PROPERTY}.
     */
    public static boolean allyPullInEnabled() {
        return allyPullIn;
    }

    /**
     * True when the spike's second run is armed: the player mirror's {@code setNoEngaging} shield is
     * left down as well. False unless {@link #allyPullInEnabled()} is also true — dropping the shield
     * on its own is not a scenario anyone asked for and would silently open PvP.
     */
    public static boolean allyPullInDropShieldEnabled() {
        return allyPullIn && allyPullInDropShield;
    }

    /**
     * Frame entry from the pump: re-reads the toggle every {@value #TOGGLE_POLL_FRAMES} frames. Cheap
     * on every other frame (one {@code int} increment and a compare).
     */
    public static void pollFrame() {
        if (++pollFrames < TOGGLE_POLL_FRAMES) {
            return;
        }
        pollFrames = 0;
        refresh();
    }

    /** The real lookup. Package-private so the toggle test can drive it without a pump. */
    static void refresh() {
        interactionClaimDelayMillis = readInteractionClaimDelayMillis();
        if (Boolean.getBoolean(PROPERTY)) {
            enabled = true;
            return;
        }
        boolean flagged = false;
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                MemoryAPI memory = sector.getMemoryWithoutUpdate();
                flagged = memory != null && memory.getBoolean(MEMORY_FLAG);
            }
        } catch (RuntimeException | LinkageError ex) {
            flagged = false;
        }
        enabled = flagged;
    }

    /**
     * Parses {@link #INTERACTION_DELAY_PROPERTY}. A missing property, a non-number, or a negative
     * value all mean "dormant" — the lever must never be able to take a session down, and a typo
     * that silently disabled a debug instrument is cheaper than one that throws in the pump.
     */
    static int readInteractionClaimDelayMillis() {
        String raw = System.getProperty(INTERACTION_DELAY_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            CoopLog.warn(CoopDebug.class, "Ignoring non-numeric " + INTERACTION_DELAY_PROPERTY
                    + "=" + raw);
            return 0;
        }
        if (parsed <= 0) {
            return 0;
        }
        if (parsed > MAX_INTERACTION_DELAY_MILLIS) {
            CoopLog.warn(CoopDebug.class, "Clamping " + INTERACTION_DELAY_PROPERTY + "=" + parsed
                    + " to " + MAX_INTERACTION_DELAY_MILLIS + "ms");
            return MAX_INTERACTION_DELAY_MILLIS;
        }
        return parsed;
    }

    /** Test seam: the flag is otherwise only driven by the JVM property and the memory flag. */
    static void setEnabledForTesting(boolean value) {
        enabled = value;
    }

    /** Test seam for the latency lever; production only sets it from the JVM property. */
    static void setInteractionClaimDelayMillisForTesting(int value) {
        interactionClaimDelayMillis = Math.max(0, value);
    }

    /**
     * Test seam for the ally pull-in spike, set as a pair because the second switch is only defined
     * relative to the first. {@code public}, unlike the seams above, because the switch is read from
     * {@code coop.fleet} and {@code coop.combat} and their tests cannot reach a package-private one;
     * production only ever seeds these from the JVM properties at class init.
     */
    public static void setAllyPullInForTesting(boolean pullIn, boolean dropShield) {
        allyPullIn = pullIn;
        allyPullInDropShield = dropShield;
    }

    /** Test seam: resets the frame counter so a test starts a poll window from a known point. */
    static void resetPollCounterForTesting() {
        pollFrames = 0;
    }
}
