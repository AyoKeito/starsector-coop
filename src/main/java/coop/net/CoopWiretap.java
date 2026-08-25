package coop.net;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import coop.util.CoopLog;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Dev wiretap for the two UDP state streams ({@code FLEET_SNAPSHOT}, {@code NPC_FLEET_MOTION}).
 * Dormant by default. Two jobs, one flag:
 *
 * <ol>
 *   <li><b>Plaintext trace.</b> Every Nth datagram per {@code (direction, type)} is logged as one
 *       greppable line carrying the decoded payload, so a host log and a guest log can be diffed
 *       line-for-line on localhost.</li>
 *   <li><b>Composed-size statistics.</b> Every datagram — not just the sampled ones — folds into a
 *       per-type count/min/mean/max plus a histogram bucketed around the two numbers that matter for
 *       WAN: the 1,200 B payload budget and the 1,472 B Ethernet fragmentation line. This is the
 *       plan's Phase 20.1 "datagram size histogram" spike instrument; the summary states the
 *       over-budget fraction outright so no arithmetic is needed to read it.</li>
 * </ol>
 *
 * <p><b>Sizes are of the composed datagram.</b> {@link CoopDatagramRedundancy} packs the previous
 * send of the same type into every datagram, so a steady stream measures roughly 2x body plus the
 * envelope — which is exactly the number the MTU decision needs, and the reason the spike is
 * specified on composed datagrams rather than bodies.
 *
 * <p><b>Enable</b> (same shape as {@link coop.util.CoopDebug} / {@link coop.util.CoopFrameProfiler}):
 * <ul>
 *   <li>at launch: {@code -Dcoop.debug.wiretap=true};</li>
 *   <li>in-game, no relaunch: sector memory flag {@code $coopWiretap} (e.g. console
 *       {@code SetMemoryKey $coopWiretap true}), re-read every {@value #TOGGLE_POLL_FRAMES} pump
 *       frames;</li>
 *   <li>sample rate: {@code -Dcoop.debug.wiretapSample=N} logs every Nth datagram per
 *       {@code (direction, type)}; default {@value #DEFAULT_SAMPLE_INTERVAL}, {@code 1} logs every
 *       one. Re-read on the same poll as the toggle.</li>
 * </ul>
 *
 * <p><b>Cost when off.</b> Every entry point is one static boolean read and a return: no clock read,
 * no parse, no allocation. The only always-on work is the {@code int} increment in
 * {@link #pollFrame()} that drives the toggle poll — the same deal {@code CoopFrameProfiler} makes.
 *
 * <p><b>Threading.</b> Campaign thread only. The send hook runs inside
 * {@code CoopNetService.flushDatagramsLocked} and the receive hook inside
 * {@code CoopNetPump.drainFleetDatagrams}; both are reached only from the pump's
 * {@code EveryFrameScript.advance()} (the transport has no reader thread — it polls its channels
 * from that same call). So every accumulator here is a plain field with no synchronization, and none
 * is justified. {@link #enabled} and {@link #sampleInterval} are {@code volatile} only because the
 * JVM properties behind them could in principle be flipped from elsewhere.
 *
 * <p><b>Sandbox.</b> {@code CoopLog} and in-memory counters only — no {@code java.io}, no
 * {@code java.lang.reflect}; the mod classloader blocks both.
 */
public final class CoopWiretap {

    public static final String PROPERTY = "coop.debug.wiretap";
    public static final String MEMORY_FLAG = "$coopWiretap";
    public static final String SAMPLE_PROPERTY = "coop.debug.wiretapSample";

    /** Log one datagram in ten per {@code (direction, type)} unless {@link #SAMPLE_PROPERTY} says otherwise. */
    static final int DEFAULT_SAMPLE_INTERVAL = 10;
    /** How often {@link #pollFrame()} re-reads the toggle, in pump frames (~5 s at 60 fps). */
    static final int TOGGLE_POLL_FRAMES = 300;
    /** Summary cadence, on the pump's clock. */
    static final long SUMMARY_INTERVAL_MILLIS = 60_000L;
    /** The WAN payload budget from plan Phase 20.1. The summary reports the fraction above it. */
    static final int WAN_BUDGET_BYTES = 1200;
    /**
     * Histogram bucket floors, inclusive; a datagram lands in the last bucket whose floor it reaches.
     * Chosen around the two thresholds that decide the phase: 1,200 B (WAN payload budget) and
     * 1,472 B (Ethernet MTU minus IP+UDP headers — past this a datagram is IP-fragmented, and losing
     * any fragment loses the whole datagram).
     */
    static final int[] BUCKET_FLOOR_BYTES = {0, 300, 600, 1200, 1472, 3000};
    static final String[] BUCKET_LABELS =
            {"0-300", "300-600", "600-1200", "1200-1472", "1472-3000", "3000+"};

    /** The one flag every call site branches on. Seeded from the property so a launch arg is live at frame 0. */
    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    /** Log every Nth datagram per stream; always >= 1. */
    private static volatile int sampleInterval = readSampleInterval();
    /** The session's wiretap, so the transport's send hook can find it. Campaign thread. */
    private static CoopWiretap active;
    private static int pollFrames;

    private final LongSupplier clockMillis;
    private final Consumer<String> logSink;
    private final Map<CoopMessages.Type, Stream> sent = new EnumMap<>(CoopMessages.Type.class);
    private final Map<CoopMessages.Type, Stream> received = new EnumMap<>(CoopMessages.Type.class);

    private long sessionStartMillis;
    private long nextSummaryAtMillis;

    public CoopWiretap(LongSupplier clockMillis) {
        this(clockMillis, message -> CoopLog.info(CoopWiretap.class, message));
    }

    /** Injected-sink form; tests drive this one so they never touch a logger or a socket. */
    public CoopWiretap(LongSupplier clockMillis, Consumer<String> logSink) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
        long now = clockMillis.getAsLong();
        this.sessionStartMillis = now;
        this.nextSummaryAtMillis = now + SUMMARY_INTERVAL_MILLIS;
    }

    /**
     * Creates the wiretap for this pump and makes it the one the transport's send hook reports into.
     * A game reload replaces the previous session's accumulators wholesale.
     */
    public static CoopWiretap installFresh(LongSupplier clockMillis) {
        CoopWiretap wiretap = new CoopWiretap(clockMillis);
        active = wiretap;
        return wiretap;
    }

    /** The flag every hot-path call site branches on. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Send hook, called from the transport with the composed wire string and its exact UTF-8 length
     * — the transport already has both, so the wiretap never re-encodes. Static because
     * {@code CoopNetService} has no handle on the pump; the same minimal seam
     * {@code CoopFrameProfiler.noteRosterRebuild()} uses.
     */
    public static void noteSend(String wire, int wireBytes) {
        if (!enabled) {
            return;
        }
        CoopWiretap wiretap = active;
        if (wiretap != null) {
            wiretap.recordSend(wire, wireBytes);
        }
    }

    /**
     * Frame entry from the pump: re-reads the toggle every {@value #TOGGLE_POLL_FRAMES} frames and
     * emits the periodic summary. Cheap on every other frame (one {@code int} increment).
     */
    public static void pollFrame() {
        if (++pollFrames >= TOGGLE_POLL_FRAMES) {
            pollFrames = 0;
            refresh();
        }
        if (!enabled) {
            return;
        }
        CoopWiretap wiretap = active;
        if (wiretap != null) {
            wiretap.tick();
        }
    }

    /**
     * Receive hook. Takes the raw wire string (for the size) and the datagram the drain already
     * parsed (for the type and sections), so the receive path never parses twice.
     */
    public void recordReceive(String wire, CoopMessages.Datagram datagram) {
        if (!enabled) {
            return;
        }
        record(received, "RX", datagram, utf8Bytes(wire));
    }

    /** Instance form of {@link #noteSend(String, int)}; the parse happens here, only when enabled. */
    public void recordSend(String wire, int wireBytes) {
        if (!enabled) {
            return;
        }
        // Deliberately not defended: compose() built this string, so a parse failure is a real
        // encoder bug and must surface as the exception it is (the two send call sites log it).
        record(sent, "TX", CoopMessages.parseDatagram(wire), wireBytes);
    }

    /**
     * Session-edge entry: drops the previous session's accumulators so a second session in the same
     * game process reports its own numbers rather than folding both together.
     */
    public void sessionStarted() {
        sent.clear();
        received.clear();
        long now = clockMillis.getAsLong();
        sessionStartMillis = now;
        nextSummaryAtMillis = now + SUMMARY_INTERVAL_MILLIS;
    }

    /** Session-edge entry: final summary (the one that answers the Phase 20.1 spike), then reset. */
    public void sessionEnded() {
        if (enabled) {
            summarize("session end");
        }
        sessionStarted();
    }

    /** Emits the periodic summary when the window is up. Called once per pump frame while enabled. */
    void tick() {
        long now = clockMillis.getAsLong();
        if (now < nextSummaryAtMillis) {
            return;
        }
        nextSummaryAtMillis = now + SUMMARY_INTERVAL_MILLIS;
        summarize("periodic");
    }

    private void record(Map<CoopMessages.Type, Stream> streams, String direction,
                        CoopMessages.Datagram datagram, int wireBytes) {
        Stream stream = streams.get(datagram.type());
        if (stream == null) {
            stream = new Stream();
            streams.put(datagram.type(), stream);
        }
        long index = stream.count;
        stream.accept(wireBytes);
        if (!isSampled(index, sampleInterval)) {
            return;
        }
        List<CoopMessages.DatagramSection> sections = datagram.sections();
        // The newest section is this tick's payload; the older one is the previous tick's, already
        // printed by the previous sampled line, so printing it again would only double the log.
        CoopMessages.DatagramSection newest = sections.get(sections.size() - 1);
        logSink.accept("Coop wiretap " + direction + " " + datagram.type()
                + " wire=" + wireBytes + "B"
                + " sections=" + sections.size()
                + " n=" + (index + 1)
                + " epoch=" + newest.epoch()
                + " gameMs=" + newest.sentGameTimeMillis()
                + " body=" + oneLine(newest.body()));
    }

    /** Emits the summary block. Silent when nothing has been seen, so an idle session stays quiet. */
    void summarize(String reason) {
        if (sent.isEmpty() && received.isEmpty()) {
            return;
        }
        double elapsedSeconds = (clockMillis.getAsLong() - sessionStartMillis) / 1000.0d;
        logSink.accept("Coop wiretap sizes (" + reason + ") elapsed=" + fmt1(elapsedSeconds) + "s"
                + " sample=every " + sampleInterval
                + " | composed wire bytes, incl. the redundancy layer's previous section;"
                + " budget=" + WAN_BUDGET_BYTES + "B, fragmentation=1472B");
        summarizeDirection("TX", sent);
        summarizeDirection("RX", received);
    }

    private void summarizeDirection(String direction, Map<CoopMessages.Type, Stream> streams) {
        for (Map.Entry<CoopMessages.Type, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            StringBuilder line = new StringBuilder(192);
            line.append("Coop wiretap sizes ").append(direction).append(' ').append(entry.getKey())
                    .append(" n=").append(stream.count)
                    .append(" min/mean/max=").append(stream.minBytes).append('/')
                    .append(fmt1(stream.totalBytes / (double) stream.count)).append('/')
                    .append(stream.maxBytes).append('B')
                    .append(" over").append(WAN_BUDGET_BYTES).append("B=").append(stream.overBudget)
                    .append(" (").append(fmt1(100.0d * stream.overBudget / stream.count)).append("%)")
                    .append(" buckets");
            for (int i = 0; i < BUCKET_LABELS.length; i++) {
                line.append(' ').append(BUCKET_LABELS[i]).append('=').append(stream.buckets[i]);
            }
            logSink.accept(line.toString());
        }
    }

    /**
     * True when the datagram at this 0-based per-stream index should be logged. The first datagram of
     * every stream always logs, so a wiretap gives feedback immediately rather than after N ticks.
     */
    static boolean isSampled(long index, int interval) {
        return interval <= 1 || index % interval == 0;
    }

    /** The last bucket whose floor {@code bytes} reaches; floors are inclusive (300 B is "300-600"). */
    static int bucketIndex(int bytes) {
        for (int i = BUCKET_FLOOR_BYTES.length - 1; i > 0; i--) {
            if (bytes >= BUCKET_FLOOR_BYTES[i]) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Collapses a payload to one log line: raw newlines become the literal two-character sequences
     * {@code \n} / {@code \r}. ASCII rather than a symbol so the line stays diffable between a host
     * log and a guest log whatever encoding the log viewer assumes. Nothing is truncated — reading
     * the whole payload is the point of the instrument.
     */
    static String oneLine(String body) {
        if (body.indexOf('\n') < 0 && body.indexOf('\r') < 0) {
            return body;
        }
        return body.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Parses {@link #SAMPLE_PROPERTY}. Missing or unparseable falls back to
     * {@value #DEFAULT_SAMPLE_INTERVAL} with a warning (a typo must not silently disable the trace);
     * anything below 1 means "log every datagram", which is what 1 already means.
     */
    static int readSampleInterval() {
        String raw = System.getProperty(SAMPLE_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_SAMPLE_INTERVAL;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            CoopLog.warn(CoopWiretap.class, "Ignoring non-numeric " + SAMPLE_PROPERTY + "=" + raw
                    + "; sampling every " + DEFAULT_SAMPLE_INTERVAL + "th datagram");
            return DEFAULT_SAMPLE_INTERVAL;
        }
        return Math.max(1, parsed);
    }

    /** The real lookup. Package-private so the toggle test can drive it without a pump. */
    static void refresh() {
        sampleInterval = readSampleInterval();
        boolean was = enabled;
        enabled = Boolean.getBoolean(PROPERTY) || memoryFlagSet();
        if (was && !enabled) {
            // Flushed on the way down: turning the flag off mid-session must not eat the numbers
            // collected so far.
            CoopWiretap wiretap = active;
            if (wiretap != null) {
                wiretap.summarize("wiretap disabled");
                wiretap.sessionStarted();
            }
        }
    }

    private static boolean memoryFlagSet() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return false;
            }
            MemoryAPI memory = sector.getMemoryWithoutUpdate();
            return memory != null && memory.getBoolean(MEMORY_FLAG);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String fmt1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Test seam: the flag is otherwise only driven by the JVM property and the memory flag. */
    static void setEnabledForTesting(boolean value) {
        enabled = value;
    }

    /** Test seam: mirrors {@link #installFresh(LongSupplier)} without touching the real logger. */
    static void setActiveForTesting(CoopWiretap wiretap) {
        active = wiretap;
    }

    /** Test seam for the sample rate; production only sets it from the JVM property. */
    static void setSampleIntervalForTesting(int value) {
        sampleInterval = Math.max(1, value);
    }

    /** One {@code (direction, type)} stream's size distribution. Plain fields: campaign thread only. */
    private static final class Stream {
        private long count;
        private long totalBytes;
        private long overBudget;
        private int minBytes = Integer.MAX_VALUE;
        private int maxBytes;
        private final long[] buckets = new long[BUCKET_LABELS.length];

        void accept(int wireBytes) {
            count++;
            totalBytes += wireBytes;
            if (wireBytes < minBytes) {
                minBytes = wireBytes;
            }
            if (wireBytes > maxBytes) {
                maxBytes = wireBytes;
            }
            if (wireBytes > WAN_BUDGET_BYTES) {
                overBudget++;
            }
            buckets[bucketIndex(wireBytes)]++;
        }
    }
}
