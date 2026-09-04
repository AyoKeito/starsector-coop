package coop.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.config.CoopOptionsRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Per-frame section timer for the coop pump. Dormant by default; it measures, it never fixes.
 *
 * <p>Enable either way (same shape as {@link CoopDebug}):
 * <ul>
 *   <li>at launch: JVM arg {@code -Dcoop.debug.frameProfile=true};</li>
 *   <li>in-game, no relaunch: sector memory flag {@code $coopFrameProfile}, e.g. console
 *       {@code SetMemoryKey $coopFrameProfile true}. The flag is re-read every
 *       {@value #TOGGLE_POLL_FRAMES} frames, so it takes a few seconds to take effect.</li>
 * </ul>
 *
 * <p><b>Cost when disabled.</b> Every entry point starts with a read of one static boolean and
 * returns; {@link #start()} hands back 0 and {@link #split(String, long)} does nothing with it. No
 * clock reads, no allocation, no string work. The only per-frame work is an {@code int} increment
 * in {@link #beginFrame()} that drives the memory-flag poll.
 *
 * <p><b>Threading.</b> Campaign thread only — this is driven from {@code EveryFrameScript.advance()}
 * and from handlers that run inside it. Every accumulator is a plain field with no synchronization
 * and none is needed; do not call any of this from the network reader thread. The one exception is
 * {@link #enabled}, which is {@code volatile} only because the JVM property could in principle be
 * flipped from elsewhere.
 *
 * <p><b>Heap numbers are approximate.</b> {@code Runtime.totalMemory()-freeMemory()} sampled at the
 * report boundaries tells you which way allocation is trending between reports; it is not a GC
 * measurement, and a collection landing inside the window can make the delta negative. The script
 * classloader blocks {@code java.lang.reflect}/{@code java.io}, and management beans are unverified,
 * so {@link System#nanoTime()} and {@code Runtime} are the whole toolbox here.
 */
public final class CoopFrameProfiler {

    public static final String PROPERTY = CoopOptionsRegistry.DEBUG_FRAME_PROFILE;
    public static final String MEMORY_FLAG = "$coopFrameProfile";

    /** One summary every ~5 s of real time, measured on the profiler's own nano clock. */
    static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;
    /** 60 fps budget. Frames over this are the ones the player feels as a dropped frame. */
    static final long FRAME_BUDGET_NANOS = 16_700_000L;
    /** 30 fps. Frames over this are visible stutters. */
    static final long FRAME_STALL_NANOS = 33_000_000L;
    /** How often {@link #beginFrame()} re-reads the memory flag, in frames (~5 s at 60 fps). */
    static final int TOGGLE_POLL_FRAMES = 300;
    private static final int TOP_SECTIONS = 5;
    private static final double NANOS_PER_MILLI = 1_000_000.0d;
    private static final double BYTES_PER_MEGABYTE = 1024.0d * 1024.0d;

    /**
     * The one flag every call site branches on. Read from the JVM property once at class init and
     * refreshed from the sector memory flag by {@link #beginFrame()}.
     */
    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    /** The pump's current profiler, so static seams (roster rebuilds) can find it. Campaign thread. */
    private static CoopFrameProfiler active;

    private final LongSupplier nanoClock;
    private final Consumer<String> logSink;
    private final Map<String, Section> sections = new HashMap<>();

    private long windowStartNanos;
    private long frameStartNanos;
    private boolean frameOpen;
    private long windowHeapUsedAtStart;
    private int frames;
    private int framesOverBudget;
    private int framesOverStall;
    private long frameTotalNanos;
    private long frameMaxNanos;
    private int togglePollFrames;

    // NPC_FLEET_SET apply stats (the Phase 16 suspect: the set got ~40% fatter and the guest
    // decodes the whole thing on every rebroadcast).
    private int npcSetApplies;
    private int npcSetLastChars;
    private int npcSetMaxChars;
    private long npcSetDecodeTotalNanos;
    private long npcSetDecodeMaxNanos;
    private long npcSetApplyTotalNanos;
    private long npcSetApplyMaxNanos;
    private int rosterRebuilds;

    public CoopFrameProfiler() {
        this(System::nanoTime, message -> CoopLog.info(CoopFrameProfiler.class, message));
    }

    /** Injected-clock form; tests drive this one so they never sleep. */
    public CoopFrameProfiler(LongSupplier nanoClock, Consumer<String> logSink) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
        this.windowStartNanos = nanoClock.getAsLong();
        this.windowHeapUsedAtStart = heapUsedBytes();
    }

    /**
     * Creates a fresh profiler and makes it the one the static seams report into. Called from the
     * pump's constructor, so a game reload replaces the previous session's accumulators wholesale
     * rather than folding a dead session's numbers into the new one.
     */
    public static CoopFrameProfiler installFresh() {
        CoopFrameProfiler profiler = new CoopFrameProfiler();
        active = profiler;
        return profiler;
    }

    /** The flag every hot-path call site branches on. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Records one guest-side mirror roster rebuild. Static because the rebuild happens deep inside
     * {@code CoopFleetMirror}, which has no handle on the pump — this is the minimal seam.
     */
    public static void noteRosterRebuild() {
        if (!enabled) {
            return;
        }
        CoopFrameProfiler profiler = active;
        if (profiler != null) {
            profiler.rosterRebuilds++;
        }
    }

    /**
     * Static form of {@link #start()}, for sections that live outside the pump and have no handle on
     * it — the same minimal seam {@link #noteRosterRebuild()} uses. Returns 0 (and reads no clock)
     * when profiling is off or no pump has installed a profiler yet.
     */
    public static long sectionStart() {
        if (!enabled) {
            return 0L;
        }
        CoopFrameProfiler profiler = active;
        return profiler == null ? 0L : profiler.nanoClock.getAsLong();
    }

    /** Static form of {@link #split(String, long)}. */
    public static long sectionSplit(String section, long startNanos) {
        if (!enabled) {
            return 0L;
        }
        CoopFrameProfiler profiler = active;
        return profiler == null ? 0L : profiler.split(section, startNanos);
    }

    /** Static form of {@link #record(String, long)}. */
    public static void sectionRecord(String section, long startNanos) {
        if (!enabled) {
            return;
        }
        CoopFrameProfiler profiler = active;
        if (profiler != null) {
            profiler.record(section, startNanos);
        }
    }

    /** Frame entry: stamps the whole-frame timer and polls the in-game toggle. */
    public void beginFrame() {
        if (++togglePollFrames >= TOGGLE_POLL_FRAMES) {
            togglePollFrames = 0;
            refreshEnabledFromMemory();
        }
        if (!enabled) {
            return;
        }
        frameStartNanos = nanoClock.getAsLong();
        frameOpen = true;
    }

    /** Frame exit: folds the whole-frame duration in and emits the summary when the window is up. */
    public void endFrame() {
        if (!enabled || !frameOpen) {
            return;
        }
        long now = nanoClock.getAsLong();
        long elapsed = now - frameStartNanos;
        frameOpen = false;
        frames++;
        frameTotalNanos += elapsed;
        if (elapsed > frameMaxNanos) {
            frameMaxNanos = elapsed;
        }
        if (elapsed > FRAME_BUDGET_NANOS) {
            framesOverBudget++;
        }
        if (elapsed > FRAME_STALL_NANOS) {
            framesOverStall++;
        }
        if (now - windowStartNanos >= REPORT_INTERVAL_NANOS) {
            report(now);
        }
    }

    /** @return a start stamp for {@link #record} / {@link #split}, or 0 when profiling is off. */
    public long start() {
        return enabled ? nanoClock.getAsLong() : 0L;
    }

    /** Folds {@code now - startNanos} into {@code section}. */
    public void record(String section, long startNanos) {
        if (!enabled) {
            return;
        }
        accumulate(section, nanoClock.getAsLong() - startNanos);
    }

    /**
     * Records a section and returns the stamp for the next one, so a long call sequence costs one
     * line and one clock read per step rather than two.
     *
     * @return the new start stamp, or 0 when profiling is off.
     */
    public long split(String section, long startNanos) {
        if (!enabled) {
            return 0L;
        }
        long now = nanoClock.getAsLong();
        accumulate(section, now - startNanos);
        return now;
    }

    /**
     * Folds one {@code NPC_FLEET_SET} apply in. {@code decodeStartNanos} is the stamp taken before
     * {@code decode}, {@code applyStartNanos} the one taken between {@code decode} and
     * {@code applySet}; "now" closes the apply.
     */
    public void noteNpcSetApply(int encodedChars, long decodeStartNanos, long applyStartNanos) {
        if (!enabled) {
            return;
        }
        long now = nanoClock.getAsLong();
        long decodeNanos = applyStartNanos - decodeStartNanos;
        long applyNanos = now - applyStartNanos;
        npcSetApplies++;
        npcSetLastChars = encodedChars;
        if (encodedChars > npcSetMaxChars) {
            npcSetMaxChars = encodedChars;
        }
        npcSetDecodeTotalNanos += decodeNanos;
        if (decodeNanos > npcSetDecodeMaxNanos) {
            npcSetDecodeMaxNanos = decodeNanos;
        }
        npcSetApplyTotalNanos += applyNanos;
        if (applyNanos > npcSetApplyMaxNanos) {
            npcSetApplyMaxNanos = applyNanos;
        }
    }

    private void accumulate(String section, long durationNanos) {
        Section entry = sections.get(section);
        if (entry == null) {
            entry = new Section();
            sections.put(section, entry);
        }
        entry.totalNanos += durationNanos;
        entry.calls++;
        if (durationNanos > entry.maxNanos) {
            entry.maxNanos = durationNanos;
        }
    }

    /** Emits the summary and clears every accumulator. Visible for tests. */
    void report(long nowNanos) {
        double windowMillis = (nowNanos - windowStartNanos) / NANOS_PER_MILLI;
        long heapUsed = heapUsedBytes();
        double heapDeltaMb = (heapUsed - windowHeapUsedAtStart) / BYTES_PER_MEGABYTE;

        List<Map.Entry<String, Section>> ranked = new ArrayList<>(sections.entrySet());
        ranked.sort((left, right) -> Long.compare(right.getValue().totalNanos, left.getValue().totalNanos));

        StringBuilder line = new StringBuilder(256);
        line.append("Coop frame profile window=").append(fmt1(windowMillis)).append("ms")
                .append(" frames=").append(frames)
                .append(" over16.7ms=").append(framesOverBudget)
                .append(" over33ms=").append(framesOverStall)
                .append(" frameMax=").append(fmt1(frameMaxNanos / NANOS_PER_MILLI)).append("ms")
                .append(" frameAvg=").append(fmt1(frames == 0 ? 0.0d : frameTotalNanos / (double) frames / NANOS_PER_MILLI)).append("ms")
                .append(" heapUsedDelta=").append(fmt1(heapDeltaMb)).append("MB(approx)")
                .append(" | top").append(TOP_SECTIONS).append(" total/max/calls:");
        int shown = Math.min(TOP_SECTIONS, ranked.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, Section> entry = ranked.get(i);
            Section section = entry.getValue();
            line.append(' ').append(entry.getKey()).append(' ')
                    .append(fmt1(section.totalNanos / NANOS_PER_MILLI)).append('/')
                    .append(fmt1(section.maxNanos / NANOS_PER_MILLI)).append('/')
                    .append(section.calls);
            if (i < shown - 1) {
                line.append(';');
            }
        }
        if (shown == 0) {
            line.append(" none");
        }
        logSink.accept(line.toString());

        if (npcSetApplies > 0 || rosterRebuilds > 0) {
            logSink.accept("Coop frame profile npcSet applies=" + npcSetApplies
                    + " chars last/max=" + npcSetLastChars + "/" + npcSetMaxChars
                    + " decodeMs total/max=" + fmt1(npcSetDecodeTotalNanos / NANOS_PER_MILLI)
                    + "/" + fmt1(npcSetDecodeMaxNanos / NANOS_PER_MILLI)
                    + " applySetMs total/max=" + fmt1(npcSetApplyTotalNanos / NANOS_PER_MILLI)
                    + "/" + fmt1(npcSetApplyMaxNanos / NANOS_PER_MILLI)
                    + " rosterRebuilds=" + rosterRebuilds);
        }

        resetWindow(nowNanos, heapUsed);
    }

    private void resetWindow(long nowNanos, long heapUsed) {
        sections.clear();
        windowStartNanos = nowNanos;
        windowHeapUsedAtStart = heapUsed;
        frames = 0;
        framesOverBudget = 0;
        framesOverStall = 0;
        frameTotalNanos = 0L;
        frameMaxNanos = 0L;
        npcSetApplies = 0;
        npcSetLastChars = 0;
        npcSetMaxChars = 0;
        npcSetDecodeTotalNanos = 0L;
        npcSetDecodeMaxNanos = 0L;
        npcSetApplyTotalNanos = 0L;
        npcSetApplyMaxNanos = 0L;
        rosterRebuilds = 0;
    }

    private static String fmt1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static long heapUsedBytes() {
        try {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static void refreshEnabledFromMemory() {
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

    /** Test seam: the enabled flag is otherwise only driven by the JVM property and the memory flag. */
    static void setEnabledForTesting(boolean value) {
        enabled = value;
    }

    /** Test seam: mirrors {@link #installFresh()} without constructing an engine-clocked profiler. */
    static void setActiveForTesting(CoopFrameProfiler profiler) {
        active = profiler;
    }

    int rosterRebuildsForTesting() {
        return rosterRebuilds;
    }

    private static final class Section {
        private long totalNanos;
        private long maxNanos;
        private int calls;
    }
}
