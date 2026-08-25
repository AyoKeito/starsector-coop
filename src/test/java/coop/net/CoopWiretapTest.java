package coop.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWiretapTest {

    /** Manually advanced millisecond clock, so nothing here sleeps. */
    private static final class FakeClock {
        private long millis = 1_000_000L;

        long get() {
            return millis;
        }

        void advanceMillis(long amount) {
            millis += amount;
        }
    }

    private FakeClock clock;
    private List<String> lines;
    private CoopWiretap wiretap;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        lines = new ArrayList<>();
        wiretap = new CoopWiretap(clock::get, lines::add);
        CoopWiretap.setActiveForTesting(wiretap);
        CoopWiretap.setEnabledForTesting(true);
        CoopWiretap.setSampleIntervalForTesting(CoopWiretap.DEFAULT_SAMPLE_INTERVAL);
    }

    @AfterEach
    void tearDown() {
        // The flag, the sample rate and the active instance are static; leaving them set would
        // silently wiretap every other test in this JVM.
        CoopWiretap.setEnabledForTesting(false);
        CoopWiretap.setActiveForTesting(null);
        CoopWiretap.setSampleIntervalForTesting(CoopWiretap.DEFAULT_SAMPLE_INTERVAL);
        System.clearProperty(CoopWiretap.SAMPLE_PROPERTY);
    }

    /** A composed datagram of a known wire size: body padded so the total lands on {@code wireBytes}. */
    private static String datagramOfSize(CoopMessages.Type type, long epoch, int wireBytes) {
        String probe = CoopMessages.datagram("s", type, epoch, 7L, "");
        int padding = wireBytes - probe.length();
        if (padding < 0) {
            throw new IllegalArgumentException("envelope alone is " + probe.length() + " bytes");
        }
        return CoopMessages.datagram("s", type, epoch, 7L, "x".repeat(padding));
    }

    // ---- sampling ---------------------------------------------------------------------------

    @Test
    void samplingLogsTheFirstDatagramThenEveryNth() {
        assertTrue(CoopWiretap.isSampled(0L, 10));
        for (long index = 1; index < 10; index++) {
            assertFalse(CoopWiretap.isSampled(index, 10), "index " + index + " must not sample");
        }
        assertTrue(CoopWiretap.isSampled(10L, 10));
        assertTrue(CoopWiretap.isSampled(20L, 10));
        assertFalse(CoopWiretap.isSampled(21L, 10));
    }

    @Test
    void sampleIntervalOfOneOrLessLogsEveryDatagram() {
        for (long index = 0; index < 5; index++) {
            assertTrue(CoopWiretap.isSampled(index, 1));
            assertTrue(CoopWiretap.isSampled(index, 0), "a clamped-away interval must not divide by zero");
            assertTrue(CoopWiretap.isSampled(index, -3));
        }
    }

    @Test
    void samplingIsPerDirectionAndPerType() {
        CoopWiretap.setSampleIntervalForTesting(3);
        for (int i = 0; i < 3; i++) {
            wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, i, 200), 200);
            wiretap.recordSend(datagramOfSize(CoopMessages.Type.NPC_FLEET_MOTION, i, 200), 200);
            wiretap.recordReceive(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, i, 200), parse(
                    datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, i, 200)));
        }
        // Three independent streams, each on its own counter: each logs only its first of three.
        assertEquals(3, lines.size(), lines.toString());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("Coop wiretap TX FLEET_SNAPSHOT")).count());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("Coop wiretap TX NPC_FLEET_MOTION")).count());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("Coop wiretap RX FLEET_SNAPSHOT")).count());
    }

    @Test
    void readSampleIntervalDefaultsParsesAndClamps() {
        System.clearProperty(CoopWiretap.SAMPLE_PROPERTY);
        assertEquals(CoopWiretap.DEFAULT_SAMPLE_INTERVAL, CoopWiretap.readSampleInterval());

        System.setProperty(CoopWiretap.SAMPLE_PROPERTY, " 4 ");
        assertEquals(4, CoopWiretap.readSampleInterval());

        System.setProperty(CoopWiretap.SAMPLE_PROPERTY, "0");
        assertEquals(1, CoopWiretap.readSampleInterval(), "0 means log everything, not divide by zero");

        System.setProperty(CoopWiretap.SAMPLE_PROPERTY, "-7");
        assertEquals(1, CoopWiretap.readSampleInterval());

        System.setProperty(CoopWiretap.SAMPLE_PROPERTY, "many");
        assertEquals(CoopWiretap.DEFAULT_SAMPLE_INTERVAL, CoopWiretap.readSampleInterval(),
                "a typo falls back to the default rather than disabling the trace");
    }

    // ---- histogram bucketing ----------------------------------------------------------------

    @Test
    void bucketFloorsAreInclusiveAndCoverEveryEdge() {
        assertEquals(0, CoopWiretap.bucketIndex(0));
        assertEquals(0, CoopWiretap.bucketIndex(299));
        assertEquals(1, CoopWiretap.bucketIndex(300));
        assertEquals(1, CoopWiretap.bucketIndex(599));
        assertEquals(2, CoopWiretap.bucketIndex(600));
        assertEquals(2, CoopWiretap.bucketIndex(1199));
        assertEquals(3, CoopWiretap.bucketIndex(1200));
        assertEquals(3, CoopWiretap.bucketIndex(1471));
        assertEquals(4, CoopWiretap.bucketIndex(1472));
        assertEquals(4, CoopWiretap.bucketIndex(2999));
        assertEquals(5, CoopWiretap.bucketIndex(3000));
        assertEquals(5, CoopWiretap.bucketIndex(60 * 1024));
        assertEquals(CoopWiretap.BUCKET_LABELS.length, CoopWiretap.BUCKET_FLOOR_BYTES.length,
                "every bucket needs a label");
    }

    @Test
    void summaryReportsCountRangeMeanAndTheOverBudgetFraction() {
        CoopWiretap.setSampleIntervalForTesting(1000);   // trace off; only the stats matter here
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1, 200), 200);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 2, 1600), 1600);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 3, 1600), 1600);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 4, 4000), 4000);
        lines.clear();   // the first datagram of a stream always traces; only the summary matters here

        wiretap.summarize("test");

        String header = lines.get(0);
        assertTrue(header.startsWith("Coop wiretap sizes (test)"), header);
        assertTrue(header.contains("budget=1200B"), header);

        String tx = lines.get(1);
        assertTrue(tx.startsWith("Coop wiretap sizes TX FLEET_SNAPSHOT"), tx);
        assertTrue(tx.contains("n=4"), tx);
        assertTrue(tx.contains("min/mean/max=200/1850.0/4000B"), tx);
        assertTrue(tx.contains("over1200B=3 (75.0%)"), tx);
        assertTrue(tx.contains("buckets 0-300=1 300-600=0 600-1200=0 1200-1472=0 1472-3000=2 3000+=1"), tx);
        assertEquals(2, lines.size(), "nothing was received, so there is no RX line: " + lines);
    }

    @Test
    void aDatagramExactlyOnTheBudgetIsNotCountedAsOverIt() {
        CoopWiretap.setSampleIntervalForTesting(1000);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1,
                CoopWiretap.WAN_BUDGET_BYTES), CoopWiretap.WAN_BUDGET_BYTES);
        lines.clear();

        wiretap.summarize("test");

        String tx = lines.get(1);
        assertTrue(tx.contains("over1200B=0 (0.0%)"), tx);
        assertTrue(tx.contains("1200-1472=1"), tx);
    }

    @Test
    void sizesAccumulateAcrossSummariesUntilTheSessionEnds() {
        CoopWiretap.setSampleIntervalForTesting(1000);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1, 400), 400);
        wiretap.summarize("first");
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 2, 400), 400);
        lines.clear();

        wiretap.summarize("second");
        assertTrue(lines.get(1).contains("n=2"),
                "the spike wants the whole run's distribution, not one window's: " + lines.get(1));

        lines.clear();
        wiretap.sessionEnded();
        assertTrue(lines.get(0).contains("(session end)"), lines.get(0));
        assertTrue(lines.get(1).contains("n=2"), lines.get(1));

        lines.clear();
        wiretap.summarize("after end");
        assertTrue(lines.isEmpty(), "a session edge clears the accumulators: " + lines);
    }

    // ---- log line shape ---------------------------------------------------------------------

    @Test
    void oneLineReplacesEveryNewlineFormWithTwoCharacterEscapes() {
        assertEquals("abc", CoopWiretap.oneLine("abc"));
        assertEquals("a\\nb", CoopWiretap.oneLine("a\nb"));
        assertEquals("a\\nb", CoopWiretap.oneLine("a\r\nb"));
        assertEquals("a\\rb", CoopWiretap.oneLine("a\rb"));
        assertEquals("a\\n\\nb", CoopWiretap.oneLine("a\n\nb"));
    }

    @Test
    void traceLineCarriesTheNewestSectionAndStaysOnOneLine() {
        CoopWiretap.setSampleIntervalForTesting(1);
        CoopDatagramRedundancy redundancy = new CoopDatagramRedundancy();
        redundancy.compose("s", CoopMessages.Type.NPC_FLEET_MOTION, 41L, 100L, "old-body");
        String wire = redundancy.compose(
                "s", CoopMessages.Type.NPC_FLEET_MOTION, 42L, 200L, "new\nbody");

        wiretap.recordReceive(wire, parse(wire));

        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("Coop wiretap RX NPC_FLEET_MOTION"), line);
        assertTrue(line.contains("sections=2"), line);
        assertTrue(line.contains("epoch=42"), line);
        assertTrue(line.contains("gameMs=200"), line);
        assertTrue(line.endsWith("body=new\\nbody"), line);
        assertFalse(line.contains("old-body"),
                "the redundant previous section is the previous line's body; printing it doubles the log");
        assertFalse(line.contains("\n"), "a wiretap entry must stay one log line: " + line);
    }

    @Test
    void wireSizeIsReportedAsGiven() {
        CoopWiretap.setSampleIntervalForTesting(1);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1, 1761), 1761);
        assertTrue(lines.get(0).contains("wire=1761B"), lines.get(0));
        assertTrue(lines.get(0).contains("n=1"), lines.get(0));
    }

    @Test
    void multiByteCharactersCountAsWireBytesNotCharacters() {
        CoopWiretap.setSampleIntervalForTesting(1000);
        // "é" is two UTF-8 bytes; the receive hook measures the encoded length, not String.length().
        String wire = CoopMessages.datagram("s", CoopMessages.Type.FLEET_SNAPSHOT, 1L, 2L, "éé");
        wiretap.recordReceive(wire, parse(wire));
        lines.clear();

        wiretap.summarize("test");
        String rx = lines.get(1);
        int expected = wire.length() + 2;
        assertTrue(rx.contains("min/mean/max=" + expected + "/"), rx);
    }

    // ---- dormancy ---------------------------------------------------------------------------

    @Test
    void disabledWiretapIsAFullNoOp() {
        CoopWiretap.setEnabledForTesting(false);
        CoopWiretap.setSampleIntervalForTesting(1);

        assertFalse(CoopWiretap.isEnabled());
        String wire = datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1, 2000);
        wiretap.recordSend(wire, 2000);
        wiretap.recordReceive(wire, parse(wire));
        CoopWiretap.noteSend(wire, 2000);
        CoopWiretap.pollFrame();

        assertTrue(lines.isEmpty(), "a disabled wiretap must never log");

        // Re-enabling proves nothing was accumulated while off.
        CoopWiretap.setEnabledForTesting(true);
        wiretap.summarize("test");
        assertTrue(lines.isEmpty(), "nothing may be recorded while disabled: " + lines);
    }

    @Test
    void staticSendSeamReachesTheActiveWiretap() {
        CoopWiretap.setSampleIntervalForTesting(1);
        CoopWiretap.noteSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1, 500), 500);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("Coop wiretap TX FLEET_SNAPSHOT"), lines.get(0));

        CoopWiretap.setActiveForTesting(null);
        CoopWiretap.noteSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 2, 500), 500);
        assertEquals(1, lines.size(), "no active wiretap means no report, not an exception");
    }

    @Test
    void periodicSummaryFiresOnceAWindow() {
        CoopWiretap.setSampleIntervalForTesting(1000);
        wiretap.recordSend(datagramOfSize(CoopMessages.Type.FLEET_SNAPSHOT, 1, 800), 800);
        lines.clear();

        wiretap.tick();
        assertTrue(lines.isEmpty(), "no summary before the window elapses");

        clock.advanceMillis(CoopWiretap.SUMMARY_INTERVAL_MILLIS);
        wiretap.tick();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("(periodic)"), lines.get(0));

        lines.clear();
        wiretap.tick();
        assertTrue(lines.isEmpty(), "the second window has not elapsed yet");
    }

    @Test
    void idleSessionStaysQuiet() {
        clock.advanceMillis(CoopWiretap.SUMMARY_INTERVAL_MILLIS * 5);
        wiretap.tick();
        wiretap.sessionEnded();
        assertTrue(lines.isEmpty(), "no traffic means no summary block: " + lines);
    }

    private static CoopMessages.Datagram parse(String wire) {
        return CoopMessages.parseDatagram(wire);
    }
}
