package coop.ui;

import coop.net.CoopConnectionRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the Phase 20.6 "Coop Session" intel page shows, as one immutable value.
 *
 * <p><b>Why a record and not a set of getters on the pump.</b> The intel page is rendered on the
 * UI's whim - {@code createLargeDescription} runs whenever the player opens or re-selects the entry,
 * which is never a frame the pump controls. Reading live pump state from there would mean either
 * locking or tearing. Instead the pump publishes a snapshot into {@link CoopSessionIntelFeed} and
 * the page renders whatever the last published value was: stale by at most one {@code LINK_STATUS}
 * interval, and never inconsistent with itself.
 *
 * <p><b>Engine-free on purpose.</b> Nothing here imports a Starsector type, so every number, every
 * wording decision and every sparkline is unit-testable without a sector. {@link CoopSessionIntel}
 * is the only class in this pair that touches the engine, and all it does is read these fields and
 * hand strings to a {@code TooltipMakerAPI}.
 *
 * <p><b>Null is a real value here.</b> "No RTT sample yet" and "the peer has never sent a
 * LINK_STATUS" are states the page must be able to say out loud, so the nullable fields stay
 * nullable and the formatting helpers turn them into {@link #UNKNOWN} rather than into a fake zero.
 *
 * @param localRole         this client's role; {@link CoopConnectionRole#NONE} means no coop
 *                          session, which is what hides the intel entry entirely
 * @param sessionState      display text for what the session is doing ("session active",
 *                          "waiting for guest", ...); supplied by the caller so this class does not
 *                          duplicate {@link CoopHudState}'s vocabulary
 * @param partnerName       the peer's display name, or {@code ""} when there is no peer
 * @param localLink         what this side measures, or null outside a session
 * @param peerLink          the peer's latest {@code LINK_STATUS}, or null when none has arrived
 * @param peerLinkAgeMillis how long ago that {@code LINK_STATUS} arrived, or null when there is none
 * @param history           up to {@link #MAX_HISTORY} link samples, oldest first, one per
 *                          {@code LINK_STATUS} interval (~5 s), so a full ring is ~5 minutes
 * @param reachability      host-only port-mapping verdict, or null on the guest and before the
 *                          mapper has anything to say
 * @param events            up to {@link #MAX_EVENTS} recent connection events, newest first
 */
public record CoopSessionIntelModel(CoopConnectionRole localRole,
                                    String sessionState,
                                    String partnerName,
                                    LinkSample localLink,
                                    LinkSample peerLink,
                                    Long peerLinkAgeMillis,
                                    List<HistoryPoint> history,
                                    Reachability reachability,
                                    List<Event> events) {

    /** Ring capacity: 60 samples at the ~5 s {@code LINK_STATUS} cadence is about five minutes. */
    public static final int MAX_HISTORY = 60;
    /** Event-list capacity. Twenty lines is more than fits on the page without scrolling anyway. */
    public static final int MAX_EVENTS = 20;

    /** Transport token as it travels on the wire (mirrors {@code CoopLinkQuality.TRANSPORT_UDP}). */
    public static final String TRANSPORT_UDP = "UDP";
    /** Transport token as it travels on the wire (mirrors {@code TRANSPORT_TCP_FALLBACK}). */
    public static final String TRANSPORT_TCP_FALLBACK = "TCP_FALLBACK";

    /** What every formatter prints when the underlying reading does not exist yet. */
    public static final String UNKNOWN = "n/a";

    /**
     * Mirrors {@code CoopCadenceTier.DEFAULT.hz()}, which lives in {@code coop.net}. Duplicated for
     * the same reason the degraded thresholds below are: this file is the engine-free UI model and
     * the value only ever decides wording.
     */
    public static final int DEFAULT_CADENCE_HZ = 10;

    /**
     * Mirrors {@code CoopLinkQuality.DEGRADED_RTT_MILLIS}, which is package-private in
     * {@code coop.net} and therefore not readable from here. Duplicated rather than widened because
     * this copy decides a text colour and nothing else - if the two ever drift, the page paints a
     * line yellow slightly early or late and no behaviour changes.
     */
    public static final int DEGRADED_RTT_MILLIS = 400;
    /** Mirrors {@code CoopLinkQuality.DEGRADED_LOSS_PERCENT}, for the same reason. */
    public static final int DEGRADED_LOSS_PERCENT = 10;

    /** Sparkline ramp, lowest to highest. ASCII only: the intel font is the vanilla one. */
    public static final String SPARK_LEVELS = "_.-=+*#";
    /** Sparkline character for a sample that has no value (no RTT measured in that interval). */
    public static final char SPARK_GAP = '~';

    public CoopSessionIntelModel {
        localRole = localRole == null ? CoopConnectionRole.NONE : localRole;
        sessionState = text(sessionState);
        partnerName = text(partnerName);
        history = history == null ? List.of() : List.copyOf(history);
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** The "there is nothing to show" value; also what the page renders in solo play. */
    public static CoopSessionIntelModel empty() {
        return new CoopSessionIntelModel(CoopConnectionRole.NONE, "", "", null, null, null,
                List.of(), null, List.of());
    }

    /** True once a role has been established, which is also the condition for showing the entry. */
    public boolean roleActive() {
        return localRole != CoopConnectionRole.NONE;
    }

    // ---- nested values ---------------------------------------------------------------------------

    /**
     * One side's link reading. Shared by the local measurement and the peer's mirrored
     * {@code LINK_STATUS} so the page can render both with the same code.
     *
     * @param rttMillis        smoothed round trip, or null when nothing has been measured
     * @param p95RttMillis     95th percentile over the sample ring, or null
     * @param lossPercent      raw datagram loss over the last window, negative when unmeasured
     * @param udpInboundOk     inbound UDP was seen recently
     * @param transport        {@link #TRANSPORT_UDP} or {@link #TRANSPORT_TCP_FALLBACK}
     * @param tcpSilenceMillis how long the TCP channel has been quiet
     * @param cadenceHz        the state streams' current cadence tier, in hertz
     */
    public record LinkSample(Integer rttMillis,
                             Integer p95RttMillis,
                             int lossPercent,
                             boolean udpInboundOk,
                             String transport,
                             long tcpSilenceMillis,
                             int cadenceHz) {
        public LinkSample {
            transport = transport == null ? "" : transport;
        }

        /** Pre-Phase-29-M2 shape, defaulting to the tier every build before M2 ran at. */
        public LinkSample(Integer rttMillis, Integer p95RttMillis, int lossPercent,
                          boolean udpInboundOk, String transport, long tcpSilenceMillis) {
            this(rttMillis, p95RttMillis, lossPercent, udpInboundOk, transport, tcpSilenceMillis,
                    DEFAULT_CADENCE_HZ);
        }

        /** The state stream is wrapped in TCP because UDP is being eaten. */
        public boolean onFallback() {
            return TRANSPORT_TCP_FALLBACK.equals(transport);
        }
    }

    /** One ring entry. Deliberately just the two numbers a history is worth drawing. */
    public record HistoryPoint(Integer rttMillis, int lossPercent) {
    }

    /**
     * The host's port-mapping verdict, already turned into display text by
     * {@link #reachabilityTierText}/{@link #reachabilityEndpointText}/{@link #cgnatText} so this
     * record carries no logic and the wording is testable on its own.
     */
    public record Reachability(String tierText, String externalEndpoint, String cgnatVerdict) {
        public Reachability {
            tierText = text(tierText);
            externalEndpoint = text(externalEndpoint);
            cgnatVerdict = text(cgnatVerdict);
        }
    }

    /**
     * One connection event. {@code ageText} is resolved at snapshot time rather than at render time
     * so the rendering path stays a pure read of this record.
     */
    public record Event(String ageText, String line) {
        public Event {
            ageText = text(ageText);
            line = text(line);
        }
    }

    /** Summary of a history ring, so the page can say more than "here is a squiggle". */
    public record HistoryStats(int samples,
                               Integer minRttMillis,
                               Integer medianRttMillis,
                               Integer maxRttMillis,
                               int minLossPercent,
                               int medianLossPercent,
                               int maxLossPercent) {

        /** Empty ring: no RTT figures at all, and loss reported as unmeasured, not as zero. */
        public static HistoryStats empty() {
            return new HistoryStats(0, null, null, null, -1, -1, -1);
        }
    }

    // ---- derived readings ------------------------------------------------------------------------

    /** The RTT column of {@link #history()}, in ring order; nulls preserved as gaps. */
    public List<Integer> rttHistory() {
        List<Integer> out = new ArrayList<>(history.size());
        for (HistoryPoint point : history) {
            out.add(point == null ? null : point.rttMillis());
        }
        return out;
    }

    /** The loss column of {@link #history()}, in ring order; unmeasured samples become gaps. */
    public List<Integer> lossHistory() {
        List<Integer> out = new ArrayList<>(history.size());
        for (HistoryPoint point : history) {
            out.add(point == null || point.lossPercent() < 0 ? null : point.lossPercent());
        }
        return out;
    }

    /** Min/median/max over the ring. Median is the lower of the two middles on an even count. */
    public HistoryStats stats() {
        return statsOf(history);
    }

    static HistoryStats statsOf(List<HistoryPoint> points) {
        if (points == null || points.isEmpty()) {
            return HistoryStats.empty();
        }
        List<Integer> rtt = new ArrayList<>();
        List<Integer> loss = new ArrayList<>();
        for (HistoryPoint point : points) {
            if (point == null) {
                continue;
            }
            if (point.rttMillis() != null && point.rttMillis() >= 0) {
                rtt.add(point.rttMillis());
            }
            if (point.lossPercent() >= 0) {
                loss.add(point.lossPercent());
            }
        }
        rtt.sort(null);
        loss.sort(null);
        return new HistoryStats(points.size(),
                rtt.isEmpty() ? null : rtt.get(0),
                rtt.isEmpty() ? null : rtt.get((rtt.size() - 1) / 2),
                rtt.isEmpty() ? null : rtt.get(rtt.size() - 1),
                loss.isEmpty() ? -1 : loss.get(0),
                loss.isEmpty() ? -1 : loss.get((loss.size() - 1) / 2),
                loss.isEmpty() ? -1 : loss.get(loss.size() - 1));
    }

    // ---- formatting helpers ----------------------------------------------------------------------

    /** "Host" / "Guest" / "No session"; the page's header word. */
    public static String roleText(CoopConnectionRole role) {
        if (role == null) {
            return "No session";
        }
        return switch (role) {
            case HOST -> "Host";
            case GUEST -> "Guest";
            case NONE -> "No session";
        };
    }

    /** {@code "42 ms"}, or {@link #UNKNOWN} for null and for the negative wire sentinel. */
    public static String formatRtt(Integer millis) {
        return millis == null || millis < 0 ? UNKNOWN : millis + " ms";
    }

    /** {@code "3%"}, or {@link #UNKNOWN} for the negative "not measured" sentinel. */
    public static String formatLoss(int percent) {
        return percent < 0 ? UNKNOWN : percent + "%";
    }

    /** Wire token to player wording. An unknown token is passed through rather than hidden. */
    public static String describeTransport(String transport) {
        if (transport == null || transport.isEmpty()) {
            return UNKNOWN;
        }
        return switch (transport) {
            case TRANSPORT_UDP -> "UDP";
            case TRANSPORT_TCP_FALLBACK -> "TCP fallback";
            default -> transport;
        };
    }

    /**
     * Whether a sample sits in the range the connection doctor calls degraded. Presentation only:
     * this decides whether the page paints a line yellow, never what the transport does.
     */
    public static boolean degraded(LinkSample sample) {
        if (sample == null) {
            return false;
        }
        boolean slow = sample.rttMillis() != null && sample.rttMillis() >= DEGRADED_RTT_MILLIS;
        return slow || sample.lossPercent() >= DEGRADED_LOSS_PERCENT;
    }

    /**
     * The state-stream line's subject: what carries it and how fast, e.g. {@code UDP 10 Hz} or
     * {@code TCP fallback 5 Hz}. Both halves in one phrase because they are one fact — the floor tier
     * and the TCP path are the same degraded mode wearing two labels.
     */
    public static String describeStateStream(String transport, int cadenceHz) {
        String path = describeTransport(transport);
        return cadenceHz <= 0 ? path : path + " " + cadenceHz + " Hz";
    }

    /** The one-phrase verdict on the inbound UDP path. */
    public static String describeUdpPath(boolean udpInboundOk) {
        return udpInboundOk ? "inbound UDP OK" : "no inbound UDP";
    }

    /**
     * Durations the player reads as "how long has this been quiet": milliseconds under a second,
     * seconds above it.
     */
    public static String formatDuration(long millis) {
        if (millis < 0L) {
            return UNKNOWN;
        }
        if (millis < 1000L) {
            return millis + " ms";
        }
        // ROOT locale so the decimal separator is a dot on every machine that runs the mod.
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    /**
     * "How long ago" wording for the event list and for the peer's last {@code LINK_STATUS}. Coarse
     * on purpose: an event that happened four and a half minutes ago is "4m ago", and nobody reading
     * this page needs better than that.
     */
    public static String formatAge(long millis) {
        long clamped = Math.max(0L, millis);
        long seconds = clamped / 1000L;
        if (seconds < 5L) {
            return "just now";
        }
        if (seconds < 60L) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "h " + (minutes % 60L) + "m ago";
        }
        return (hours / 24L) + "d ago";
    }

    /**
     * An ASCII sparkline over the ring, scaled between the smallest and largest present value.
     *
     * <p>Relative scaling is the point: an absolute ms axis needs a fixed ceiling, and any ceiling is
     * wrong for both a 12 ms LAN link and a 300 ms transatlantic one. What the player is looking for
     * here is shape - did it spike, is it climbing - not a calibrated axis, and the min/median/max
     * line underneath carries the absolute numbers. A perfectly flat series therefore renders as the
     * lowest level, which is exactly why those numbers are printed next to it.
     */
    public static String sparkline(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Integer value : values) {
            if (value == null) {
                continue;
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        StringBuilder out = new StringBuilder(values.size());
        if (min > max) {
            for (int i = 0; i < values.size(); i++) {
                out.append(SPARK_GAP);
            }
            return out.toString();
        }
        int span = max - min;
        int top = SPARK_LEVELS.length() - 1;
        for (Integer value : values) {
            if (value == null) {
                out.append(SPARK_GAP);
                continue;
            }
            int level = span == 0 ? 0 : (int) Math.round((double) (value - min) * top / span);
            out.append(SPARK_LEVELS.charAt(Math.max(0, Math.min(top, level))));
        }
        return out.toString();
    }

    // ---- reachability wording --------------------------------------------------------------------

    /**
     * The tier line. Split from {@code CoopConnectionDoctor}'s wording deliberately: the doctor
     * writes a log paragraph ending in a next-step sentence, and this is a single line in a UI
     * column.
     *
     * @param tierName    {@code CoopPortMapper.Tier} name, e.g. {@code UPNP}
     * @param mapped      {@code CoopPortMapper.Result.mapped()}
     * @param finished    the mapper has stopped trying
     * @param cgnat       the router's own external address is private
     * @param failureText the mapper's one-sentence failure, empty on success
     */
    public static String reachabilityTierText(String tierName, boolean mapped, boolean finished,
                                              boolean cgnat, String failureText) {
        String tier = tierName == null || tierName.isEmpty() ? "NONE" : tierName;
        if (mapped) {
            return cgnat
                    ? "mapped via " + tier + ", but CGNAT makes the mapped port unreachable"
                    : "mapped via " + tier;
        }
        if (!finished) {
            return "still negotiating with the router";
        }
        String failure = failureText == null ? "" : failureText.trim();
        return failure.isEmpty() ? "no mapping (not attempted)" : "no mapping (" + failure + ")";
    }

    /** The address the guest should be given, or a plain statement that there is not one yet. */
    public static String reachabilityEndpointText(String externalEndpoint) {
        return externalEndpoint == null || externalEndpoint.isEmpty()
                ? "not discovered" : externalEndpoint;
    }

    /**
     * The CGNAT verdict, the single most valuable line on this page for a host nobody can reach: it
     * turns "we cannot connect" into "no port forward will ever work, use IPv6 or a VPN".
     */
    public static String cgnatText(boolean cgnat, String externalAddress) {
        String address = externalAddress == null ? "" : externalAddress.trim();
        if (cgnat) {
            String subject = address.isEmpty() ? "the router's external address" : address;
            return "yes - " + subject + " is private; no IPv4 port forward can reach you";
        }
        if (address.isEmpty()) {
            return "unknown (no external address was discovered)";
        }
        return "no - " + address + " is a public address";
    }

    // ---- one-line summaries ----------------------------------------------------------------------

    /**
     * The intel list row: state plus the one number worth seeing without opening the page. Never
     * empty, because an empty row would render as a nameless entry.
     */
    public static String listLine(CoopSessionIntelModel model) {
        CoopSessionIntelModel value = model == null ? empty() : model;
        String state = value.sessionState().isEmpty() ? "no session" : value.sessionState();
        LinkSample link = value.localLink();
        if (link == null || link.rttMillis() == null || link.rttMillis() < 0) {
            return state;
        }
        String line = state + " - " + formatRtt(link.rttMillis());
        return link.onFallback() ? line + " (TCP fallback)" : line;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
