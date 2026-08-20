package coop.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopFrameProfilerTest {

    private static final long MILLI = 1_000_000L;

    /** Manually advanced nano clock, so nothing here sleeps. */
    private static final class FakeClock {
        private long nanos = 1_000_000_000L;

        long get() {
            return nanos;
        }

        void advanceMillis(double millis) {
            nanos += (long) (millis * MILLI);
        }
    }

    private FakeClock clock;
    private List<String> lines;
    private CoopFrameProfiler profiler;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        lines = new ArrayList<>();
        profiler = new CoopFrameProfiler(clock::get, lines::add);
        CoopFrameProfiler.setActiveForTesting(profiler);
        CoopFrameProfiler.setEnabledForTesting(true);
    }

    @AfterEach
    void tearDown() {
        // The enabled flag and the active instance are static; leaving them set would silently
        // profile every other test in this JVM.
        CoopFrameProfiler.setEnabledForTesting(false);
        CoopFrameProfiler.setActiveForTesting(null);
    }

    private void frame(double millis) {
        profiler.beginFrame();
        clock.advanceMillis(millis);
        profiler.endFrame();
    }

    @Test
    void recordAccumulatesTotalMaxAndCalls() {
        long start = profiler.start();
        clock.advanceMillis(4.0d);
        profiler.record("alpha", start);

        start = profiler.start();
        clock.advanceMillis(10.0d);
        profiler.record("alpha", start);

        clock.advanceMillis(CoopFrameProfiler.REPORT_INTERVAL_NANOS / (double) MILLI);
        profiler.report(clock.get());

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("alpha 14.0/10.0/2"),
                "expected total/max/calls for alpha, got: " + lines.get(0));
    }

    @Test
    void splitChainsSectionsWithOneClockReadEach() {
        long t = profiler.start();
        clock.advanceMillis(2.0d);
        t = profiler.split("first", t);
        clock.advanceMillis(3.0d);
        profiler.record("second", t);

        profiler.report(clock.get());

        String line = lines.get(0);
        assertTrue(line.contains("second 3.0/3.0/1"), line);
        assertTrue(line.contains("first 2.0/2.0/1"), line);
    }

    @Test
    void frameCountersClassifyBudgetAndStallFrames() {
        frame(5.0d);
        frame(20.0d);   // over 16.7 ms
        frame(40.0d);   // over 16.7 ms and over 33 ms

        profiler.report(clock.get());

        String line = lines.get(0);
        assertTrue(line.contains("frames=3"), line);
        assertTrue(line.contains("over16.7ms=2"), line);
        assertTrue(line.contains("over33ms=1"), line);
        assertTrue(line.contains("frameMax=40.0ms"), line);
    }

    @Test
    void reportFiresOncePerWindowFromEndFrame() {
        frame(1.0d);
        assertTrue(lines.isEmpty(), "no report before the window elapses");

        clock.advanceMillis(CoopFrameProfiler.REPORT_INTERVAL_NANOS / (double) MILLI);
        frame(1.0d);
        assertEquals(1, lines.size());

        frame(1.0d);
        assertEquals(1, lines.size(), "second window has not elapsed yet");
    }

    @Test
    void reportResetsAccumulators() {
        long start = profiler.start();
        clock.advanceMillis(7.0d);
        profiler.record("alpha", start);
        frame(40.0d);
        profiler.report(clock.get());

        lines.clear();
        frame(1.0d);
        profiler.report(clock.get());

        String line = lines.get(0);
        assertTrue(line.contains("frames=1"), line);
        assertTrue(line.contains("over33ms=0"), line);
        assertFalse(line.contains("alpha"), "sections must not survive a report: " + line);
    }

    @Test
    void reportRanksTopFiveSectionsByTotalTime() {
        String[] names = {"s1", "s2", "s3", "s4", "s5", "s6", "s7"};
        for (int i = 0; i < names.length; i++) {
            long start = profiler.start();
            clock.advanceMillis(i + 1);
            profiler.record(names[i], start);
        }

        profiler.report(clock.get());

        String line = lines.get(0);
        int top = line.indexOf("top5");
        assertTrue(top >= 0, line);
        String tail = line.substring(top);
        assertTrue(tail.startsWith("top5 total/max/calls: s7 7.0/7.0/1; s6 6.0/6.0/1; s5 5.0/5.0/1;"
                + " s4 4.0/4.0/1; s3 3.0/3.0/1"), tail);
        assertFalse(tail.contains("s2"), "only the top 5 belong in the summary: " + tail);
    }

    @Test
    void npcSetStatsFoldIntoASecondLine() {
        long decodeStart = profiler.start();
        clock.advanceMillis(3.0d);
        long applyStart = profiler.start();
        clock.advanceMillis(12.0d);
        profiler.noteNpcSetApply(90_000, decodeStart, applyStart);

        decodeStart = profiler.start();
        clock.advanceMillis(1.0d);
        applyStart = profiler.start();
        clock.advanceMillis(4.0d);
        profiler.noteNpcSetApply(70_000, decodeStart, applyStart);

        CoopFrameProfiler.noteRosterRebuild();
        CoopFrameProfiler.noteRosterRebuild();
        CoopFrameProfiler.noteRosterRebuild();

        profiler.report(clock.get());

        assertEquals(2, lines.size(), "one frame line plus one npcSet line");
        String npc = lines.get(1);
        assertTrue(npc.contains("applies=2"), npc);
        assertTrue(npc.contains("chars last/max=70000/90000"), npc);
        assertTrue(npc.contains("decodeMs total/max=4.0/3.0"), npc);
        assertTrue(npc.contains("applySetMs total/max=16.0/12.0"), npc);
        assertTrue(npc.contains("rosterRebuilds=3"), npc);
    }

    @Test
    void noNpcLineWhenNothingApplied() {
        frame(1.0d);
        profiler.report(clock.get());
        assertEquals(1, lines.size());
    }

    @Test
    void disabledProfilerIsAFullNoOp() {
        CoopFrameProfiler.setEnabledForTesting(false);

        assertFalse(CoopFrameProfiler.isEnabled());
        assertEquals(0L, profiler.start());
        assertEquals(0L, profiler.split("alpha", 0L));
        profiler.record("beta", 0L);
        profiler.beginFrame();
        clock.advanceMillis(50.0d);
        profiler.endFrame();
        profiler.noteNpcSetApply(1234, 0L, 0L);
        CoopFrameProfiler.noteRosterRebuild();

        assertEquals(0, profiler.rosterRebuildsForTesting());
        assertTrue(lines.isEmpty(), "a disabled profiler must never log");

        // Re-enabling proves nothing was accumulated while off.
        CoopFrameProfiler.setEnabledForTesting(true);
        profiler.report(clock.get());
        String line = lines.get(0);
        assertEquals(1, lines.size(), "no npcSet line, so nothing was recorded while disabled");
        assertTrue(line.contains("frames=0"), line);
        assertTrue(line.endsWith("none"), line);
    }

    @Test
    void endFrameWithoutBeginFrameIsIgnored() {
        profiler.endFrame();
        profiler.report(clock.get());
        assertTrue(lines.get(0).contains("frames=0"), lines.get(0));
    }
}
