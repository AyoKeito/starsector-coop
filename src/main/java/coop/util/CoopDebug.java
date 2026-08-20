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

    /** How often {@link #pollFrame()} re-reads the toggle, in pump frames (~5 s at 60 fps). */
    static final int TOGGLE_POLL_FRAMES = 300;

    /**
     * The flag every call site branches on. Seeded from the JVM property at class init so a launch-time
     * {@code -Dcoop.debug.diagnostics=true} is live before the first frame ever runs, then refreshed by
     * {@link #pollFrame()}. Volatile only because the JVM property could in principle be flipped from
     * another thread; every read and the poll itself are campaign-thread.
     */
    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    private static int pollFrames;

    private CoopDebug() {
    }

    /** True when coop diagnostics should log, via the JVM property or the in-game memory flag. */
    public static boolean diagnosticsEnabled() {
        return enabled;
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

    /** Test seam: the flag is otherwise only driven by the JVM property and the memory flag. */
    static void setEnabledForTesting(boolean value) {
        enabled = value;
    }

    /** Test seam: resets the frame counter so a test starts a poll window from a known point. */
    static void resetPollCounterForTesting() {
        pollFrames = 0;
    }
}
