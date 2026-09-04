package coop.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

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
    public static final String PROPERTY = "coop.debug.diagnostics";
    public static final String MEMORY_FLAG = "$coopDebug";

    /**
     * Phase 18 latency lever: {@code -Dcoop.debug.interactionDelayMs=<n>} makes the HOST hold every
     * inbound {@code INTERACTION_CLAIM} for {@code n} ms before arbitrating it, which widens the
     * claim race to something a human can hit on localhost (on a real link the window is only host
     * frame + TCP one-way + guest frame). Dormant at 0, which is the default and what every shipped
     * session runs; it is a test instrument, not a tuning knob.
     */
    public static final String INTERACTION_DELAY_PROPERTY = "coop.debug.interactionDelayMs";

    /**
     * Sanity cap on the lever: past this the session is unplayable and the value is a typo.
     *
     * <p>Public because it is the <em>one</em> source for this bound. {@code CoopOptionsRegistry}
     * declares it as the key's {@code max} (so the registry clamps a file value to it and the
     * launcher's spinner will not offer more), and {@link #readInteractionClaimDelayMillis()}
     * applies it again to the raw {@code -D} path, which never passes through the registry.
     */
    public static final int MAX_INTERACTION_DELAY_MILLIS = 60_000;

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

    /** Test seam: resets the frame counter so a test starts a poll window from a known point. */
    static void resetPollCounterForTesting() {
        pollFrames = 0;
    }
}
