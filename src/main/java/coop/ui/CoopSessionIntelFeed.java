package coop.ui;

import coop.net.CoopConnectionRole;
import coop.net.CoopLinkQuality;
import coop.net.CoopMessages;
import coop.net.CoopPortMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The mutable half of the Phase 20.6 intel page: the pump writes into it, {@link CoopSessionIntel}
 * reads {@link #snapshot()} out of it, and the two never touch each other directly.
 *
 * <p><b>Why a static handle.</b> Intel plugins are constructed by XStream on load and by the intel
 * manager on registration; neither hands them a reference to the pump. The plugin therefore reaches
 * the live data through {@link #active()}, and the same handle doubles as the presence flag that
 * decides whether the entry is visible at all - no feed installed means no coop pump running, which
 * means solo play, which means the entry hides itself. The field is {@code volatile} because the
 * intel screen and the campaign pump are not guaranteed to be the same thread.
 *
 * <p><b>What is published and when.</b> Everything except {@link #noteEvent} arrives on the pump's
 * own cadence; {@link #publishLink} is deliberately the only method that appends to the history ring,
 * so the ring's meaning ("one sample per {@code LINK_STATUS} interval") is a property of the call
 * site rather than of a timer inside this class. Calling it every frame would fill the ring with
 * five seconds of history instead of five minutes, which is why the wiring note below is explicit.
 *
 * <h2>Wiring the coordinator must add to {@code CoopNetPump}</h2>
 * Three calls, none of which this worker was allowed to make (see {@link CoopSessionIntel} for the
 * exact snippet):
 * <ol>
 *   <li>{@link #publishSession} + {@link #publishLink} on every {@code LINK_STATUS} interval;</li>
 *   <li>{@link #noteEvent} on each link transition the doctor already logs;</li>
 *   <li>{@link CoopSessionIntel#ensureRegistered} in {@code onGameLoad}, plus
 *       {@link #install(CoopSessionIntelFeed)} when the pump is created and {@link #uninstall} when
 *       it is disposed.</li>
 * </ol>
 *
 * <p><b>Total by construction.</b> Every method tolerates nulls and none of them throws: this is
 * telemetry for a UI page, and a malformed peer status must not be able to take down the frame that
 * received it.
 */
public final class CoopSessionIntelFeed {

    /**
     * The feed the intel entry reads. Null means "no coop pump is running in this game", which is
     * the solo-play case and the reason {@link CoopSessionIntel#isHidden()} returns true.
     */
    private static volatile CoopSessionIntelFeed active;

    private final LongSupplier clock;

    private final Deque<CoopSessionIntelModel.HistoryPoint> history = new ArrayDeque<>();
    private final Deque<EventEntry> events = new ArrayDeque<>();

    private CoopConnectionRole localRole = CoopConnectionRole.NONE;
    private String sessionState = "";
    private String partnerName = "";
    private CoopSessionIntelModel.LinkSample localLink;
    private CoopSessionIntelModel.LinkSample peerLink;
    private long peerLinkAtMillis;
    private CoopSessionIntelModel.Reachability reachability;

    /** Wall-clock timestamp plus the line; the display age is derived at snapshot time. */
    private record EventEntry(long atMillis, String line) {
    }

    public CoopSessionIntelFeed() {
        this(System::currentTimeMillis);
    }

    /** Test seam: every timestamp this class takes comes from here, so ages are deterministic. */
    public CoopSessionIntelFeed(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    // ---- static handle ---------------------------------------------------------------------------

    /** Publishes this feed as the one the intel entry reads. A null argument uninstalls. */
    public static void install(CoopSessionIntelFeed feed) {
        active = feed;
    }

    /** Session teardown: the intel entry goes back to hiding itself. */
    public static void uninstall() {
        active = null;
    }

    /** The installed feed, or null when there is none. */
    public static CoopSessionIntelFeed active() {
        return active;
    }

    /**
     * The presence flag {@link CoopSessionIntel#isHidden()} reads: a feed is installed and it has a
     * role. Both halves matter - the feed is installed when the pump is created, which happens
     * before the player has actually started hosting or joining anything.
     */
    public static boolean roleActive() {
        CoopSessionIntelFeed feed = active;
        return feed != null && feed.currentRole() != CoopConnectionRole.NONE;
    }

    /** What the intel page renders. Never null: with no feed installed this is the empty model. */
    public static CoopSessionIntelModel currentModel() {
        CoopSessionIntelFeed feed = active;
        return feed == null ? CoopSessionIntelModel.empty() : feed.snapshot();
    }

    // ---- publishing ------------------------------------------------------------------------------

    /** Role, session wording and partner name. Safe to call every frame; nothing accumulates. */
    public synchronized void publishSession(CoopConnectionRole role, String state, String partner) {
        this.localRole = role == null ? CoopConnectionRole.NONE : role;
        this.sessionState = state == null ? "" : state;
        this.partnerName = partner == null ? "" : partner;
    }

    /**
     * One link sample. <b>Call this once per {@code LINK_STATUS} interval, not once per frame</b> -
     * it is what fills the history ring, and the ring's five-minute span is a consequence of that
     * cadence.
     */
    public void publishLink(Integer rttMillis, Integer p95RttMillis, int lossPercent,
                            boolean udpInboundOk, String transport, long tcpSilenceMillis) {
        publishLink(rttMillis, p95RttMillis, lossPercent, udpInboundOk, transport, tcpSilenceMillis,
                CoopSessionIntelModel.DEFAULT_CADENCE_HZ);
    }

    /** As above, carrying the Phase 29 M2 cadence tier this side's state streams are sending at. */
    public synchronized void publishLink(Integer rttMillis, Integer p95RttMillis, int lossPercent,
                                         boolean udpInboundOk, String transport,
                                         long tcpSilenceMillis, int cadenceHz) {
        this.localLink = new CoopSessionIntelModel.LinkSample(rttMillis, p95RttMillis, lossPercent,
                udpInboundOk, transport, tcpSilenceMillis, cadenceHz);
        history.addLast(new CoopSessionIntelModel.HistoryPoint(
                rttMillis == null || rttMillis < 0 ? null : rttMillis,
                lossPercent));
        while (history.size() > CoopSessionIntelModel.MAX_HISTORY) {
            history.removeFirst();
        }
    }

    /**
     * Adapter for the value the pump already has in hand when it composes {@code LINK_STATUS}. Reads
     * accessors only, so a field added to {@code CoopLinkQuality.Snapshot} does not break this.
     */
    public void publishLink(CoopLinkQuality.Snapshot snapshot, String transport) {
        publishLink(snapshot, transport, CoopSessionIntelModel.DEFAULT_CADENCE_HZ);
    }

    /** The same adapter, carrying the cadence tier the pump has applied to its state streams. */
    public void publishLink(CoopLinkQuality.Snapshot snapshot, String transport, int cadenceHz) {
        if (snapshot == null) {
            return;
        }
        publishLink(snapshot.rttMillis(), snapshot.p95RttMillis(), snapshot.lossPercent(),
                snapshot.udpInboundOk(), transport, snapshot.tcpSilenceMillis(), cadenceHz);
    }

    /**
     * The peer's mirrored reading. The {@code -1} sentinels the wire uses for "no sample yet" become
     * nulls here, because {@link CoopSessionIntelModel#formatRtt} has to be able to say so.
     */
    public void notePeerLink(CoopMessages.LinkStatus status) {
        if (status == null) {
            return;
        }
        notePeerLink(status.rttMillis() < 0 ? null : status.rttMillis(),
                status.p95RttMillis() < 0 ? null : status.p95RttMillis(),
                status.lossPercent(), status.udpInboundOk(), status.transport(),
                status.tcpSilenceMillis(), status.cadenceTier().hz());
    }

    /** The primitive form, for callers that have the numbers but not the message. */
    public void notePeerLink(Integer rttMillis, Integer p95RttMillis, int lossPercent,
                             boolean udpInboundOk, String transport, long tcpSilenceMillis) {
        notePeerLink(rttMillis, p95RttMillis, lossPercent, udpInboundOk, transport, tcpSilenceMillis,
                CoopSessionIntelModel.DEFAULT_CADENCE_HZ);
    }

    /** The primitive form carrying the cadence tier the peer announced. */
    public synchronized void notePeerLink(Integer rttMillis, Integer p95RttMillis, int lossPercent,
                                          boolean udpInboundOk, String transport,
                                          long tcpSilenceMillis, int cadenceHz) {
        this.peerLink = new CoopSessionIntelModel.LinkSample(rttMillis, p95RttMillis, lossPercent,
                udpInboundOk, transport, tcpSilenceMillis, cadenceHz);
        this.peerLinkAtMillis = now();
    }

    /**
     * The host's port-mapping verdict. Reads accessors only, for the same reason
     * {@link #publishLink(CoopLinkQuality.Snapshot, String)} does.
     */
    public void noteReachability(CoopPortMapper.Result result) {
        if (result == null) {
            return;
        }
        noteReachability(new CoopSessionIntelModel.Reachability(
                CoopSessionIntelModel.reachabilityTierText(result.tier() == null ? "" : result.tier().name(),
                        result.mapped(), result.finished(), result.cgnat(), result.failureText()),
                CoopSessionIntelModel.reachabilityEndpointText(result.externalEndpoint()),
                CoopSessionIntelModel.cgnatText(result.cgnat(), result.externalAddress())));
    }

    /** Already-formatted form, for tests and for a caller that has no mapper result. */
    public synchronized void noteReachability(CoopSessionIntelModel.Reachability value) {
        this.reachability = value;
    }

    /**
     * Appends one connection event: fallback entered/recovered, degraded/recovered, reconnect
     * wait/resume/expiry. Blank lines are dropped rather than recorded as empty rows.
     */
    public synchronized void noteEvent(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        events.addLast(new EventEntry(now(), trimmed));
        while (events.size() > CoopSessionIntelModel.MAX_EVENTS) {
            events.removeFirst();
        }
    }

    /**
     * Session teardown. Events survive on purpose - the last thing that happened before a session
     * ended is exactly what a player opens this page to read - while every live reading is dropped so
     * a dead session cannot keep showing a stale RTT.
     */
    public synchronized void endSession() {
        this.localRole = CoopConnectionRole.NONE;
        this.sessionState = "";
        this.partnerName = "";
        this.localLink = null;
        this.peerLink = null;
        this.peerLinkAtMillis = 0L;
        this.reachability = null;
        history.clear();
    }

    /** Full wipe, including the event log. */
    public synchronized void reset() {
        endSession();
        events.clear();
    }

    // ---- reading ---------------------------------------------------------------------------------

    /** The role as last published; used by {@link #roleActive()} without building a whole model. */
    public synchronized CoopConnectionRole currentRole() {
        return localRole;
    }

    /** An immutable view of everything published so far. */
    public synchronized CoopSessionIntelModel snapshot() {
        long now = now();
        List<CoopSessionIntelModel.Event> eventList = new ArrayList<>(events.size());
        // Newest first: the page is read top-down and the newest event is the one that matters.
        for (EventEntry entry : events) {
            eventList.add(0, new CoopSessionIntelModel.Event(
                    CoopSessionIntelModel.formatAge(now - entry.atMillis()), entry.line()));
        }
        Long peerAge = peerLinkAtMillis == 0L ? null : Math.max(0L, now - peerLinkAtMillis);
        return new CoopSessionIntelModel(localRole, sessionState, partnerName, localLink, peerLink,
                peerAge, new ArrayList<>(history), reachability, eventList);
    }

    private long now() {
        try {
            return clock.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}
