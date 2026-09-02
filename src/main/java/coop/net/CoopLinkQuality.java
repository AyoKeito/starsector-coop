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
 * <p><b>Measures and reports; never declares death.</b> The campaign pump does not run while its
 * process is in combat or writing a coordinated autosave, so a peer can legitimately go silent for
 * minutes. Nothing here times a peer out — the only disconnect trigger remains the TCP socket
 * closing. The reconnect coordinator (M3) is where silence becomes a verdict, because that is where
 * the battle/save exemptions live.
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
    /** RTT samples kept for the p95. 32 samples at 3 s is ~1.5 minutes of link history. */
    static final int RTT_SAMPLE_RING = 32;
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
        fallbackActive = false;
        fallbackClearSinceMillis = 0L;
        fallbackReason = "";
        degraded = false;
        degradedSinceMillis = 0L;
        healthySinceMillis = nowMillis;
    }

    /** Wall clock of the most recent {@link #reset(long)}; the session-start stamp. */
    public long resetAtMillis() {
        return resetAtMillis;
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

    // ---- readouts --------------------------------------------------------------------------------

    /** Smoothed RTT, or null when no PONG has been matched yet. */
    public Integer rttMillis() {
        return rttPrimed ? (int) Math.round(rttEwmaMillis) : null;
    }

    /** 95th percentile of the retained RTT samples, or null when there are none. */
    public Integer p95RttMillis() {
        if (rttRingCount == 0) {
            return null;
        }
        // Until the ring wraps, entries [0, count) are exactly the samples written, so one copy
        // covers both the partial and the full case.
        int[] sorted = Arrays.copyOf(rttRing, rttRingCount);
        Arrays.sort(sorted);
        int index = (int) Math.ceil(0.95 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
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
        boolean bad = (rtt != null && rtt > DEGRADED_RTT_MILLIS)
                || lossPercent(nowMillis) > DEGRADED_LOSS_PERCENT;
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

    // ---- diagnostics -----------------------------------------------------------------------------

    /**
     * The one-shot INFO block the guest logs once it knows whether its UDP path works. A guest whose
     * router eats UDP used to see nothing but a frozen partner mirror; this turns that into a line a
     * player can paste into a bug report.
     *
     * <p>Formatting lives here rather than at the call site so it is testable, and so the connection
     * doctor being built on another branch has one formatter to merge with rather than two call sites
     * to chase.
     */
    public static String guestDoctorBlock(Snapshot link, CoopDatagramStats stats, boolean udpObserved) {
        StringBuilder out = new StringBuilder(320);
        out.append("Coop connection doctor (guest):");
        out.append("\n  TCP control channel: ok (silent for ")
                .append(link == null ? 0L : link.tcpSilenceMillis()).append(" ms)");
        out.append("\n  UDP state path: ").append(udpObserved ? "ok" : "BLOCKED (no inbound datagram)");
        Integer rtt = link == null ? null : link.rttMillis();
        Integer p95 = link == null ? null : link.p95RttMillis();
        out.append("\n  RTT: ").append(rtt == null ? "unknown" : rtt + " ms")
                .append(", p95 ").append(p95 == null ? "unknown" : p95 + " ms")
                .append(", loss ").append(link == null ? 0 : link.lossPercent()).append('%');
        if (stats != null) {
            out.append("\n  Validated send target: ")
                    .append(stats.validatedRemote().isEmpty() ? "none" : stats.validatedRemote())
                    .append(" (path validations ").append(stats.pathValidations())
                    .append(", probes sent ").append(stats.probesSent())
                    .append(", echoes ").append(stats.probeEchoesReceived()).append(')');
            out.append("\n  Dropped inbound: token mismatch ").append(stats.droppedTokenMismatch())
                    .append(", foreign source ").append(stats.droppedForeignSource())
                    .append(", malformed ").append(stats.droppedMalformed())
                    .append(", no token ").append(stats.droppedNoToken());
            out.append("\n  Keepalives: sent ").append(stats.keepalivesSent())
                    .append(", received ").append(stats.keepalivesReceived())
                    .append("; ICMP transients ").append(stats.icmpTransients());
        }
        if (!udpObserved) {
            out.append("\n  Likely cause: a NAT or firewall on the path is dropping UDP."
                    + " The state stream falls back to TCP automatically; expect coarser mirror motion.");
        }
        return out.toString();
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
