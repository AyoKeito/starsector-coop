package coop.net;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What this side of the link is actually experiencing (Phase 20.1 M2): round-trip time, datagram
 * loss, how long each transport has been silent, and — derived from those — whether the UDP path is
 * being eaten and the state stream has to fall back onto TCP.
 *
 * <p><b>Death is a verdict with exemptions, never a bare timeout</b> (M3). The campaign pump does not
 * run while its process is in combat or writing a coordinated autosave, so a peer can legitimately go
 * silent for minutes, and so can <em>this</em> process — which makes our own measured silence say
 * nothing about the peer. {@link #evaluateLinkDeath} is therefore the only place silence becomes a
 * verdict, and it refuses to give one while any of the three exemptions holds. Everything else here
 * measures and reports.
 *
 * <p><b>Why the loss estimate is shaped like this.</b> The state streams are ack-free, so there is no
 * sequence-number acknowledgement to count. What there is: every datagram carries the sender's
 * monotonic {@link CoopStreamClock#nextEpoch()} on its <em>last</em> section (the earlier section is
 * the previous send, riding along as {@link CoopDatagramRedundancy}), and that counter is shared by
 * every stream a process sends — the pump's {@code FLEET_SNAPSHOT} and the replicator's
 * {@code NPC_FLEET_MOTION} draw from one {@code CoopStreamClock} instance, so per <em>sender</em> the
 * epochs are dense over datagrams actually sent. Over a 10 s window, then, {@code distinct(epochs)}
 * out of {@code max - min + 1} is the fraction that arrived, and the rest is raw link loss. Keying by
 * sender rather than by (sender, type) is what makes the sequence dense; keying by type would read
 * every interleaved motion datagram as a snapshot gap.
 *
 * <p>Pure logic: every method takes the caller's wall clock, so the whole thing is testable on a fake
 * clock with no sockets. One instance per pump.
 */
public final class CoopLinkQuality {

    /** Smoothing factor for the RTT average; 0.2 is the classic RFC 6298 SRTT alpha. */
    public static final double RTT_EWMA_ALPHA = 0.2;
    /**
     * Outstanding PINGs remembered at once. A peer that never answers must not grow this map, and at
     * a 3 s ping cadence 16 is 48 s of unanswered pings — far past anything a live link produces.
     */
    static final int MAX_OUTSTANDING_PINGS = 16;
    /** RTT samples kept for the p95 and the p50. 32 samples at 3 s is ~1.5 minutes of link history. */
    static final int RTT_SAMPLE_RING = 32;
    /**
     * Smoothing factor for the inter-arrival jitter estimator (Phase 29 M2), Mirror's
     * dynamic-adjustment shape: {@code 2 / (N + 1)} for a horizon of N samples, with N = 20 — two
     * seconds of a 10 Hz state stream. At the 5 Hz floor tier the same alpha is a four-second
     * horizon, which reads slower and more conservatively; that is the right direction, because the
     * floor tier has already widened the interpolation delay on its own.
     */
    static final double JITTER_EMA_ALPHA = 2.0 / 21.0;
    /**
     * Inter-arrival gaps longer than this are not jitter. A peer in combat, a coordinated save, a
     * loading screen: the pump on the far side simply was not running, and feeding that gap to the
     * estimator would widen the interpolation delay to its clamp for a minute afterwards.
     */
    static final long JITTER_GAP_MILLIS = 2_000L;
    /** Sliding window the raw-loss estimate is computed over. */
    static final long LOSS_WINDOW_MILLIS = 10_000L;
    /** Hard cap on window entries so a flood cannot grow the deque without bound. */
    static final int MAX_LOSS_SAMPLES = 1_024;
    /**
     * An epoch this far from what we have seen is a restarted peer process (or a link so broken the
     * window is worthless either way); the window restarts rather than reporting a fake 99% loss.
     */
    static final long LOSS_EPOCH_RESET_GAP = 1_000L;

    /** Inbound UDP within this long counts as "the UDP path works" for {@code LINK_STATUS}. */
    static final long UDP_INBOUND_OK_MILLIS = 10_000L;
    /** UDP silence at or past this, with TCP still alive, means UDP is being eaten. */
    static final long FALLBACK_UDP_SILENCE_MILLIS = 10_000L;
    /** TCP silence below this is what makes the UDP silence attributable to the network, not to a
     * peer that is simply not running its pump (combat, saving). */
    static final long FALLBACK_TCP_ALIVE_MILLIS = 6_000L;
    /** Both fallback conditions must stay clear this long before the stream goes back to UDP. */
    static final long FALLBACK_RECOVERY_MILLIS = 5_000L;

    /** RTT EWMA at or above this counts as degraded. */
    static final int DEGRADED_RTT_MILLIS = 400;
    /** Loss at or above this counts as degraded. */
    static final int DEGRADED_LOSS_PERCENT = 10;
    /** Degraded and recovered both need this much continuous evidence before they are announced. */
    static final long DEGRADED_SUSTAIN_MILLIS = 10_000L;

    /**
     * TCP silence at or past this is the raw death threshold. Both sides ping every 3 s and answer
     * with a PONG, so a live link is never quiet for five ping intervals; a half-open socket after a
     * NAT drop is quiet forever, and the OS will not tell us for another minute or two.
     */
    public static final long DEAD_TCP_SILENCE_MILLIS = 15_000L;
    /**
     * Exemption: a coordinated save checkpoint this recently. Writing a save stops the peer's pump for
     * as long as the save takes, and a big late-game sector save is comfortably past the raw threshold.
     */
    public static final long DEATH_SAVE_EXEMPT_MILLIS = 60_000L;
    /**
     * Exemption trigger: a gap this large between two consecutive local frames means <em>we</em> were
     * not pumping — our own combat, our own save, an OS stall. Our silence measurement then describes
     * this process, not the link.
     */
    public static final long LOCAL_STALL_FRAME_GAP_MILLIS = 5_000L;

    private boolean frameSeen;
    private long lastFrameAtMillis;
    private long lastFrameGapMillis;
    private boolean localStallSeen;
    private long localStallEndedAtMillis;

    /** Transport token on the wire and in the HUD: the state stream is on UDP. */
    public static final String TRANSPORT_UDP = "UDP";
    /** Transport token on the wire and in the HUD: the state stream is wrapped in TCP. */
    public static final String TRANSPORT_TCP_FALLBACK = "TCP_FALLBACK";

    /** Outstanding PING seq -&gt; the wall clock it was sent at; eldest-first so the cap can evict. */
    private final Map<Long, Long> outstandingPings = new LinkedHashMap<>();
    private final Map<String, SenderLoss> lossBySender = new HashMap<>();
    private final int[] rttRing = new int[RTT_SAMPLE_RING];

    private double rttEwmaMillis;
    private boolean rttPrimed;
    private int rttRingCount;
    private int rttRingIndex;

    private long lastInboundTcpMillis;
    private long lastUdpInboundMillis;
    private long resetAtMillis;

    /** Jitter estimator state; see {@link #noteStateSampleArrival(long)}. */
    private boolean arrivalSeen;
    private long lastArrivalMillis;
    private boolean jitterPrimed;
    private double jitterMeanMillis;
    private double jitterVariance;

    private boolean fallbackActive;
    private long fallbackClearSinceMillis;
    private String fallbackReason = "";

    private boolean degraded;
    private long degradedSinceMillis;
    private long healthySinceMillis;

    /**
     * Session edge: forget everything, and start both silence timers from now. Measuring silence from
     * the session start rather than from an epoch-zero stamp is what stops a brand-new session from
     * reading as ten seconds of UDP silence on its very first frame.
     */
    public void reset(long nowMillis) {
        outstandingPings.clear();
        lossBySender.clear();
        Arrays.fill(rttRing, 0);
        rttEwmaMillis = 0.0;
        rttPrimed = false;
        rttRingCount = 0;
        rttRingIndex = 0;
        lastInboundTcpMillis = nowMillis;
        lastUdpInboundMillis = nowMillis;
        resetAtMillis = nowMillis;
        resetJitter();
        fallbackActive = false;
        fallbackClearSinceMillis = 0L;
        fallbackReason = "";
        degraded = false;
        degradedSinceMillis = 0L;
        healthySinceMillis = nowMillis;
        // Frame bookkeeping deliberately survives: it describes THIS process, not the connection.
        // A session edge does not un-stall a game that just spent forty seconds in a battle, and
        // clearing it here would throw away the very gap the next verdict has to account for.
    }

    /**
     * Resume edge (20.2): the link is back on the same session, so the silence timers and the loss
     * window — which describe the connection that just died — restart from now. The RTT history is
     * deliberately kept: it is the same two machines on the same path, and throwing away a minute and
     * a half of samples would leave the HUD blank for the first several pings after every blip.
     */
    public void resetSilence(long nowMillis) {
        lossBySender.clear();
        lastInboundTcpMillis = nowMillis;
        lastUdpInboundMillis = nowMillis;
        resetAtMillis = nowMillis;
        resetJitter();
        fallbackClearSinceMillis = 0L;
        outstandingPings.clear();
    }

    /** Drops the inter-arrival history; a link that just came back has none worth keeping. */
    private void resetJitter() {
        arrivalSeen = false;
        lastArrivalMillis = 0L;
        jitterPrimed = false;
        jitterMeanMillis = 0.0;
        jitterVariance = 0.0;
    }

    /** Wall clock of the most recent {@link #reset(long)}; the session-start stamp. */
    public long resetAtMillis() {
        return resetAtMillis;
    }

    /**
     * One campaign frame went by. The gap to the previous frame is the whole local-stall exemption:
     * a frame gap past {@link #LOCAL_STALL_FRAME_GAP_MILLIS} means this process was not running its
     * pump, so whatever silence we then measure is ours, not the peer's.
     */
    public void noteFrame(long nowMillis) {
        // A boolean rather than a zero sentinel: an injected test clock legitimately starts at 0, and
        // "no frame yet" and "the first frame was at 0" are different states.
        if (frameSeen && nowMillis > lastFrameAtMillis) {
            lastFrameGapMillis = nowMillis - lastFrameAtMillis;
            if (lastFrameGapMillis >= LOCAL_STALL_FRAME_GAP_MILLIS) {
                localStallSeen = true;
                localStallEndedAtMillis = nowMillis;
            }
        }
        frameSeen = true;
        lastFrameAtMillis = nowMillis;
    }

    /** The gap between the two most recent frames; 0 before a second frame has been seen. */
    public long lastFrameGapMillis() {
        return lastFrameGapMillis;
    }

    // ---- samples ---------------------------------------------------------------------------------

    /** Records an outbound PING so its PONG can be timed. Bounded: the eldest is evicted at the cap. */
    public void notePingSent(long seq, long nowMillis) {
        if (outstandingPings.size() >= MAX_OUTSTANDING_PINGS) {
            java.util.Iterator<Map.Entry<Long, Long>> eldest = outstandingPings.entrySet().iterator();
            if (eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
        outstandingPings.put(seq, nowMillis);
    }

    /**
     * Times a PONG against the PING it answers. An unknown {@code pingSeq} (evicted, or a peer
     * echoing something we never sent) is ignored rather than producing a garbage sample.
     *
     * @return the RTT sample in milliseconds, or -1 when the pong could not be matched
     */
    public int notePongReceived(long pingSeq, long nowMillis) {
        Long sentAt = outstandingPings.remove(pingSeq);
        if (sentAt == null) {
            return -1;
        }
        long rtt = nowMillis - sentAt;
        if (rtt < 0L) {
            return -1;
        }
        int sample = (int) Math.min(rtt, Integer.MAX_VALUE);
        rttEwmaMillis = rttPrimed ? (RTT_EWMA_ALPHA * sample) + ((1.0 - RTT_EWMA_ALPHA) * rttEwmaMillis) : sample;
        rttPrimed = true;
        rttRing[rttRingIndex] = sample;
        rttRingIndex = (rttRingIndex + 1) % RTT_SAMPLE_RING;
        if (rttRingCount < RTT_SAMPLE_RING) {
            rttRingCount++;
        }
        return sample;
    }

    /** Every inbound TCP message, whatever its type: the peer's process is alive and talking. */
    public void noteInboundTcp(long nowMillis) {
        if (nowMillis > lastInboundTcpMillis) {
            lastInboundTcpMillis = nowMillis;
        }
    }

    /**
     * One accepted datagram, from either transport. {@code lastSectionEpoch} is that datagram's own
     * epoch (see the class doc); a datagram whose epoch is already in the window is counted once.
     */
    public void noteInboundDatagram(String senderId, long lastSectionEpoch, long nowMillis) {
        String key = senderId == null ? "" : senderId;
        lossBySender.computeIfAbsent(key, ignored -> new SenderLoss()).note(lastSectionEpoch, nowMillis);
    }

    /**
     * The transport's last accepted <em>UDP</em> datagram stamp
     * ({@link CoopDatagramStats#lastInboundDatagramAtMillis()}). Deliberately separate from
     * {@link #noteInboundDatagram}: while the fallback is on, datagrams arrive wrapped in TCP, and
     * counting those as UDP liveness would make the fallback un-leaveable.
     */
    public void noteUdpInbound(long atMillis) {
        if (atMillis > lastUdpInboundMillis) {
            lastUdpInboundMillis = atMillis;
        }
    }

    /**
     * One <em>fresh</em> {@code FLEET_SNAPSHOT} datagram landed, at wall clock {@code nowMillis}
     * (Phase 29 M2). This is the jitter estimator's only input, and the three words in that sentence
     * are all load-bearing:
     * <ul>
     *   <li><b>FLEET_SNAPSHOT only.</b> It is the one stream that is sent on a fixed interval by both
     *       roles regardless of what the sector contains. {@code NPC_FLEET_MOTION} is host-only,
     *       chunked (several datagrams per tick), and range-filtered, so its arrival spacing measures
     *       the fleet population as much as the path.</li>
     *   <li><b>Fresh.</b> One sample per datagram whose current section cleared the watermark; the
     *       redundant older section riding along is the <em>previous</em> send and would double-count
     *       every interval.</li>
     *   <li><b>Wall clock.</b> Jitter is a transport property. The stream stamps are game time, which
     *       stops under a pause and runs fast under fast-forward.</li>
     * </ul>
     *
     * <p>The estimator is Mirror's exponential moving variance: the mean tracks the interval and the
     * variance tracks the squared deviation from it, both at {@link #JITTER_EMA_ALPHA}. Gaps past
     * {@link #JITTER_GAP_MILLIS} are dropped rather than fed (see that constant), and dropping one
     * re-seats the clock so the next gap is measured from now.
     */
    public void noteStateSampleArrival(long nowMillis) {
        if (!arrivalSeen) {
            arrivalSeen = true;
            lastArrivalMillis = nowMillis;
            return;
        }
        long delta = nowMillis - lastArrivalMillis;
        lastArrivalMillis = nowMillis;
        if (delta < 0L || delta > JITTER_GAP_MILLIS) {
            return;
        }
        if (!jitterPrimed) {
            jitterPrimed = true;
            jitterMeanMillis = delta;
            jitterVariance = 0.0;
            return;
        }
        double deviation = delta - jitterMeanMillis;
        jitterMeanMillis += JITTER_EMA_ALPHA * deviation;
        jitterVariance = (1.0 - JITTER_EMA_ALPHA)
                * (jitterVariance + JITTER_EMA_ALPHA * deviation * deviation);
    }

    // ---- readouts --------------------------------------------------------------------------------

    /** Smoothed RTT, or null when no PONG has been matched yet. */
    public Integer rttMillis() {
        return rttPrimed ? (int) Math.round(rttEwmaMillis) : null;
    }

    /** 95th percentile of the retained RTT samples, or null when there are none. */
    public Integer p95RttMillis() {
        return percentileRtt(0.95);
    }

    /**
     * Median of the retained RTT samples, or null when there are none. This is what
     * {@link CoopCadenceController} keys on rather than the p95 or the EWMA — see that class for the
     * frame-cap measurement artefact that makes the tail statistic unusable as a control input.
     */
    public Integer medianRttMillis() {
        return percentileRtt(0.5);
    }

    /** Nearest-rank percentile over the sample ring; {@code fraction} in (0, 1]. */
    private Integer percentileRtt(double fraction) {
        if (rttRingCount == 0) {
            return null;
        }
        // Until the ring wraps, entries [0, count) are exactly the samples written, so one copy
        // covers both the partial and the full case.
        int[] sorted = Arrays.copyOf(rttRing, rttRingCount);
        Arrays.sort(sorted);
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /**
     * Standard deviation of the state stream's inter-arrival spacing, in milliseconds; 0 until two
     * usable arrivals have been seen. Feeds the adaptive interpolation delay (Phase 29 M2).
     */
    public int jitterStdDevMillis() {
        if (!jitterPrimed || jitterVariance <= 0.0) {
            return 0;
        }
        return (int) Math.round(Math.sqrt(jitterVariance));
    }

    /**
     * Worst per-sender raw datagram loss over the last {@link #LOSS_WINDOW_MILLIS}, 0-100. With one
     * peer this is that peer's loss; with three it is the one that is suffering, which is the number
     * a player wants on the HUD.
     */
    public int lossPercent(long nowMillis) {
        int worst = 0;
        for (SenderLoss loss : lossBySender.values()) {
            worst = Math.max(worst, loss.lossPercent(nowMillis));
        }
        return worst;
    }

    /** How long since any TCP message arrived. */
    public long tcpSilenceMillis(long nowMillis) {
        return Math.max(0L, nowMillis - lastInboundTcpMillis);
    }

    /** How long since any UDP datagram arrived (TCP-wrapped ones deliberately do not count). */
    public long udpSilenceMillis(long nowMillis) {
        return Math.max(0L, nowMillis - lastUdpInboundMillis);
    }

    /** Whether UDP has delivered anything recently — the field the peer reads out of LINK_STATUS. */
    public boolean udpInboundOk(long nowMillis) {
        return udpSilenceMillis(nowMillis) < UDP_INBOUND_OK_MILLIS;
    }

    /** Everything a {@code LINK_STATUS} or a HUD line needs, sampled once so it is self-consistent. */
    public Snapshot snapshot(long nowMillis) {
        return new Snapshot(rttMillis(), p95RttMillis(), lossPercent(nowMillis),
                udpInboundOk(nowMillis), tcpSilenceMillis(nowMillis), udpSilenceMillis(nowMillis));
    }

    /** One self-consistent read of the link; see {@link #snapshot(long)}. */
    public record Snapshot(Integer rttMillis,
                           Integer p95RttMillis,
                           int lossPercent,
                           boolean udpInboundOk,
                           long tcpSilenceMillis,
                           long udpSilenceMillis) {
    }

    // ---- fallback decision -----------------------------------------------------------------------

    /**
     * The UDP-blocked decision, evaluated once per second by the pump. Symmetric and un-negotiated:
     * both peers run the same rule on the same evidence and arrive at the same answer, so there is no
     * handshake to get stuck half-completed.
     *
     * <p>Enter fallback when either
     * <ol>
     *   <li><b>local</b>: no UDP for {@link #FALLBACK_UDP_SILENCE_MILLIS} while TCP has been heard
     *       within {@link #FALLBACK_TCP_ALIVE_MILLIS}. The TCP clause is the whole point — a peer in
     *       combat or writing a save stops pumping and goes silent on <em>both</em> transports, and
     *       that must not read as a blocked path; or</li>
     *   <li><b>remote</b>: the peer's latest {@code LINK_STATUS} says it is receiving no UDP.</li>
     * </ol>
     * Leave it only after both have been clear for {@link #FALLBACK_RECOVERY_MILLIS} continuously.
     * Probing UDP during the fallback costs nothing extra: the M1 idle keepalive already emits a
     * {@code UDP_PROBE} every 5 s precisely because the datagram queue is idle, so the plan's "keep
     * probing UDP every 30 s" is subsumed by machinery that is already running.
     *
     * @param peerUdpInboundOk the peer's last fresh {@code LINK_STATUS} reading, or null when there
     *                         is none or it has aged out
     * @return whether the state stream should be on TCP after this evaluation
     */
    public boolean evaluateFallback(long nowMillis, Boolean peerUdpInboundOk) {
        boolean localBlocked = udpSilenceMillis(nowMillis) >= FALLBACK_UDP_SILENCE_MILLIS
                && tcpSilenceMillis(nowMillis) < FALLBACK_TCP_ALIVE_MILLIS;
        boolean peerBlocked = peerUdpInboundOk != null && !peerUdpInboundOk;

        if (localBlocked || peerBlocked) {
            fallbackClearSinceMillis = 0L;
            if (!fallbackActive) {
                fallbackActive = true;
                fallbackReason = (localBlocked ? "no inbound UDP for " + udpSilenceMillis(nowMillis)
                        + " ms while TCP was heard " + tcpSilenceMillis(nowMillis) + " ms ago"
                        : "peer reports it is receiving no UDP");
            }
            return true;
        }

        if (!fallbackActive) {
            fallbackClearSinceMillis = 0L;
            return false;
        }
        if (fallbackClearSinceMillis == 0L) {
            fallbackClearSinceMillis = nowMillis;
        } else if (nowMillis - fallbackClearSinceMillis >= FALLBACK_RECOVERY_MILLIS) {
            fallbackActive = false;
            fallbackClearSinceMillis = 0L;
            fallbackReason = "UDP has been clear for " + FALLBACK_RECOVERY_MILLIS + " ms";
        }
        return fallbackActive;
    }

    /** Whether the state stream is currently wrapped in TCP. */
    public boolean fallbackActive() {
        return fallbackActive;
    }

    /** Human-readable cause of the most recent fallback transition; for the INFO log line. */
    public String fallbackReason() {
        return fallbackReason;
    }

    /** {@link #TRANSPORT_UDP} or {@link #TRANSPORT_TCP_FALLBACK}, for LINK_STATUS and the HUD. */
    public String transport() {
        return fallbackActive ? TRANSPORT_TCP_FALLBACK : TRANSPORT_UDP;
    }

    /**
     * Playable-but-bad detection for the player-facing feed: RTT EWMA or loss past the thresholds,
     * held continuously for {@link #DEGRADED_SUSTAIN_MILLIS} in each direction so one bad sample
     * neither raises nor clears the banner.
     *
     * @return whether the link is currently considered degraded
     */
    public boolean evaluateDegraded(long nowMillis) {
        Integer rtt = rttMillis();
        // Inclusive (red-team B9): both constants are documented as "at or above this counts as
        // degraded", and a strict comparison made the documented threshold value itself healthy.
        boolean bad = (rtt != null && rtt >= DEGRADED_RTT_MILLIS)
                || lossPercent(nowMillis) >= DEGRADED_LOSS_PERCENT;
        if (bad) {
            healthySinceMillis = 0L;
            if (degradedSinceMillis == 0L) {
                degradedSinceMillis = nowMillis;
            } else if (!degraded && nowMillis - degradedSinceMillis >= DEGRADED_SUSTAIN_MILLIS) {
                degraded = true;
            }
            return degraded;
        }
        degradedSinceMillis = 0L;
        if (healthySinceMillis == 0L) {
            healthySinceMillis = nowMillis;
        } else if (degraded && nowMillis - healthySinceMillis >= DEGRADED_SUSTAIN_MILLIS) {
            degraded = false;
        }
        return degraded;
    }

    /** Whether the link is currently flagged degraded; see {@link #evaluateDegraded(long)}. */
    public boolean degraded() {
        return degraded;
    }

    // ---- link death ------------------------------------------------------------------------------

    /**
     * One evaluation of the death rule, with every exemption it considered spelled out so the INFO log
     * can say what it decided <em>on</em> rather than just what it decided.
     *
     * @param dead                 whether the link should be declared dead and the socket dropped
     * @param tcpSilenceMillis     how long since any inbound TCP message
     * @param udpSilenceMillis     how long since any inbound UDP datagram; context only
     * @param peerInCombat         exemption (a): the peer is fighting a battle, so its pump is stopped
     * @param recentSaveCheckpoint exemption (b): a coordinated save happened inside the exempt window
     * @param localStalled         exemption (c): this process itself was not pumping recently
     */
    public record DeathVerdict(boolean dead,
                               long tcpSilenceMillis,
                               long udpSilenceMillis,
                               boolean peerInCombat,
                               boolean recentSaveCheckpoint,
                               boolean localStalled) {

        /** Whether any exemption was what stopped the verdict; false when the silence was simply short. */
        public boolean exempted() {
            return peerInCombat || recentSaveCheckpoint || localStalled;
        }

        /** The log line's body: the numbers, then every exemption and whether it fired. */
        public String describe() {
            return "tcpSilence=" + tcpSilenceMillis + " ms udpSilence=" + udpSilenceMillis
                    + " ms threshold=" + DEAD_TCP_SILENCE_MILLIS
                    + " ms; exemptions peerInCombat=" + peerInCombat
                    + " recentSaveCheckpoint=" + recentSaveCheckpoint
                    + " localStalled=" + localStalled;
        }
    }

    /**
     * Should the TCP link be declared dead? Dead means the socket is closed deliberately so the
     * ordinary disconnect edge fires now instead of whenever the OS gives up retransmitting (one to
     * two minutes on a half-open socket after a NAT drop), which on the guest hands straight over to
     * the 500 ms reconnect retry and on the host opens the 20.2 grace window.
     *
     * <p>The raw condition is {@link #DEAD_TCP_SILENCE_MILLIS} of inbound TCP silence. It is then
     * vetoed by three exemptions, each of which describes a way the silence can be legitimate:
     * <ol>
     *   <li><b>The peer is in combat.</b> Its campaign pump is not running, so it is not sending
     *       anything, and this can last as long as a battle does.</li>
     *   <li><b>A coordinated save checkpoint inside {@link #DEATH_SAVE_EXEMPT_MILLIS}.</b> Both
     *       processes stop to write a save around a checkpoint, and a late-game sector save is well
     *       past the raw threshold.</li>
     *   <li><b>We were not pumping.</b> A local frame gap past
     *       {@link #LOCAL_STALL_FRAME_GAP_MILLIS} means this process just came back from its own
     *       combat or save; the accumulated silence is an artefact of our own stall, not evidence
     *       about the peer. The exemption is held for a further {@link #DEAD_TCP_SILENCE_MILLIS}
     *       after the stall ends, which is exactly how long it takes to re-earn the verdict on
     *       evidence gathered while we were actually running.</li>
     * </ol>
     *
     * @param peerInCombat              the remote player's battle state (see the pump's battle bridge)
     * @param lastSaveCheckpointMillis  wall clock of the last SAVE_CHECKPOINT sent or received, or 0
     */
    public DeathVerdict evaluateLinkDeath(long nowMillis, boolean peerInCombat,
                                          long lastSaveCheckpointMillis) {
        long tcpSilence = tcpSilenceMillis(nowMillis);
        long udpSilence = udpSilenceMillis(nowMillis);
        boolean recentSave = lastSaveCheckpointMillis > 0L
                && nowMillis - lastSaveCheckpointMillis <= DEATH_SAVE_EXEMPT_MILLIS;
        boolean localStalled = localStallSeen
                && nowMillis - localStallEndedAtMillis < DEAD_TCP_SILENCE_MILLIS;
        boolean dead = tcpSilence >= DEAD_TCP_SILENCE_MILLIS
                && !peerInCombat && !recentSave && !localStalled;
        return new DeathVerdict(dead, tcpSilence, udpSilence, peerInCombat, recentSave, localStalled);
    }

    /** Per-sender sliding window of received datagram epochs; see the class doc for the estimator. */
    private static final class SenderLoss {
        private final ArrayDeque<long[]> arrivals = new ArrayDeque<>();
        private final Set<Long> epochsInWindow = new HashSet<>();

        private void note(long epoch, long nowMillis) {
            long newest = epochsInWindow.isEmpty() ? epoch : maxEpoch();
            if (Math.abs(epoch - newest) > LOSS_EPOCH_RESET_GAP) {
                // A restarted peer process (or a link so broken the window is meaningless): start over
                // rather than report a fabricated 99%.
                arrivals.clear();
                epochsInWindow.clear();
            }
            prune(nowMillis);
            if (!epochsInWindow.add(epoch)) {
                return;
            }
            arrivals.addLast(new long[]{nowMillis, epoch});
            while (arrivals.size() > MAX_LOSS_SAMPLES) {
                epochsInWindow.remove(arrivals.removeFirst()[1]);
            }
        }

        private int lossPercent(long nowMillis) {
            prune(nowMillis);
            if (arrivals.size() < 2) {
                return 0;
            }
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (long[] arrival : arrivals) {
                min = Math.min(min, arrival[1]);
                max = Math.max(max, arrival[1]);
            }
            long expected = max - min + 1L;
            if (expected <= 0L || arrivals.size() >= expected) {
                return 0;
            }
            long lost = expected - arrivals.size();
            return (int) Math.max(0L, Math.min(100L, Math.round((100.0 * lost) / expected)));
        }

        private long maxEpoch() {
            long max = Long.MIN_VALUE;
            for (long[] arrival : arrivals) {
                max = Math.max(max, arrival[1]);
            }
            return max == Long.MIN_VALUE ? 0L : max;
        }

        private void prune(long nowMillis) {
            long cutoff = nowMillis - LOSS_WINDOW_MILLIS;
            while (!arrivals.isEmpty() && arrivals.peekFirst()[0] < cutoff) {
                epochsInWindow.remove(arrivals.removeFirst()[1]);
            }
        }
    }
}
