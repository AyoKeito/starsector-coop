package coop.ui;

import coop.net.CoopConnectionRole;
import coop.net.CoopLinkQuality;
import coop.net.CoopMessages;
import coop.net.CoopPortMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSessionIntelFeedTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final CoopSessionIntelFeed feed = new CoopSessionIntelFeed(clock::get);

    @AfterEach
    void clearStaticHandle() {
        CoopSessionIntelFeed.uninstall();
    }

    private void publishRtt(int rtt, int loss) {
        feed.publishLink(rtt, rtt + 5, loss, true, CoopSessionIntelModel.TRANSPORT_UDP, 0L);
    }

    // ---- static handle ---------------------------------------------------------------------------

    @Test
    void noFeedInstalledMeansNoRoleAndAnEmptyModel() {
        assertNull(CoopSessionIntelFeed.active());
        assertFalse(CoopSessionIntelFeed.roleActive());
        assertEquals(CoopSessionIntelModel.empty(), CoopSessionIntelFeed.currentModel());
    }

    @Test
    void installedFeedWithoutARoleStillCountsAsSolo() {
        CoopSessionIntelFeed.install(feed);

        assertSame(feed, CoopSessionIntelFeed.active());
        assertFalse(CoopSessionIntelFeed.roleActive());
    }

    @Test
    void roleActiveOnceASessionIsPublishedAndFalseAgainAfterItEnds() {
        CoopSessionIntelFeed.install(feed);
        feed.publishSession(CoopConnectionRole.HOST, "session active", "Ayo");

        assertTrue(CoopSessionIntelFeed.roleActive());
        assertEquals("session active", CoopSessionIntelFeed.currentModel().sessionState());

        feed.endSession();

        assertFalse(CoopSessionIntelFeed.roleActive());
    }

    @Test
    void uninstallDropsTheHandle() {
        CoopSessionIntelFeed.install(feed);
        CoopSessionIntelFeed.uninstall();

        assertNull(CoopSessionIntelFeed.active());
        assertFalse(CoopSessionIntelFeed.roleActive());
    }

    // ---- null safety -----------------------------------------------------------------------------

    @Test
    void everyPublishTolerateNulls() {
        feed.publishSession(null, null, null);
        feed.publishLink((CoopLinkQuality.Snapshot) null, null);
        feed.notePeerLink((CoopMessages.LinkStatus) null);
        feed.noteReachability((CoopPortMapper.Result) null);
        feed.noteReachability((CoopSessionIntelModel.Reachability) null);
        feed.noteEvent(null);
        feed.noteEvent("   ");

        CoopSessionIntelModel model = feed.snapshot();

        assertEquals(CoopConnectionRole.NONE, model.localRole());
        assertEquals("", model.sessionState());
        assertEquals("", model.partnerName());
        assertNull(model.localLink());
        assertNull(model.peerLink());
        assertNull(model.peerLinkAgeMillis());
        assertNull(model.reachability());
        assertTrue(model.events().isEmpty());
        assertTrue(model.history().isEmpty());
    }

    // ---- history ring ----------------------------------------------------------------------------

    @Test
    void historyKeepsTheMostRecentSamplesOldestFirst() {
        for (int i = 0; i < CoopSessionIntelModel.MAX_HISTORY + 10; i++) {
            publishRtt(i, 0);
        }

        List<CoopSessionIntelModel.HistoryPoint> history = feed.snapshot().history();

        assertEquals(CoopSessionIntelModel.MAX_HISTORY, history.size());
        assertEquals(10, history.get(0).rttMillis());
        assertEquals(CoopSessionIntelModel.MAX_HISTORY + 9, history.get(history.size() - 1).rttMillis());
    }

    @Test
    void historyRecordsUnmeasuredRttAsAGap() {
        feed.publishLink(null, null, -1, false, CoopSessionIntelModel.TRANSPORT_UDP, 0L);
        feed.publishLink(-1, -1, 3, true, CoopSessionIntelModel.TRANSPORT_UDP, 0L);

        List<CoopSessionIntelModel.HistoryPoint> history = feed.snapshot().history();

        assertEquals(2, history.size());
        assertNull(history.get(0).rttMillis());
        assertEquals(-1, history.get(0).lossPercent());
        assertNull(history.get(1).rttMillis());
        assertEquals(3, history.get(1).lossPercent());
    }

    @Test
    void endSessionClearsTheRingButKeepsTheEventLog() {
        publishRtt(20, 0);
        feed.noteEvent("Link degraded");

        feed.endSession();
        CoopSessionIntelModel model = feed.snapshot();

        assertTrue(model.history().isEmpty());
        assertNull(model.localLink());
        assertEquals(1, model.events().size());

        feed.reset();

        assertTrue(feed.snapshot().events().isEmpty());
    }

    // ---- event list ------------------------------------------------------------------------------

    @Test
    void eventsAreBoundedAndNewestFirst() {
        for (int i = 0; i < CoopSessionIntelModel.MAX_EVENTS + 5; i++) {
            feed.noteEvent("event " + i);
        }

        List<CoopSessionIntelModel.Event> events = feed.snapshot().events();

        assertEquals(CoopSessionIntelModel.MAX_EVENTS, events.size());
        assertEquals("event " + (CoopSessionIntelModel.MAX_EVENTS + 4), events.get(0).line());
        assertEquals("event 5", events.get(events.size() - 1).line());
    }

    @Test
    void eventsAreTrimmedAndAged() {
        feed.noteEvent("  UDP blocked - state stream moved to TCP  ");
        clock.addAndGet(90_000L);
        feed.noteEvent("UDP recovered");

        List<CoopSessionIntelModel.Event> events = feed.snapshot().events();

        assertEquals(2, events.size());
        assertEquals("UDP recovered", events.get(0).line());
        assertEquals("just now", events.get(0).ageText());
        assertEquals("UDP blocked - state stream moved to TCP", events.get(1).line());
        assertEquals("1m ago", events.get(1).ageText());
    }

    // ---- peer status -----------------------------------------------------------------------------

    @Test
    void peerStatusSentinelsBecomeNullsAndTheAgeIsTracked() {
        feed.notePeerLink(null, null, -1, false, CoopSessionIntelModel.TRANSPORT_TCP_FALLBACK, 250L);
        clock.addAndGet(7_000L);

        CoopSessionIntelModel model = feed.snapshot();

        assertNotNull(model.peerLink());
        assertNull(model.peerLink().rttMillis());
        assertEquals(-1, model.peerLink().lossPercent());
        assertTrue(model.peerLink().onFallback());
        assertEquals(7_000L, model.peerLinkAgeMillis());
    }

    @Test
    void peerStatusFromAWireMessageDropsTheNegativeSentinels() {
        CoopMessages.LinkStatus status = new CoopMessages.LinkStatus(-1, -1, 4, true,
                CoopSessionIntelModel.TRANSPORT_UDP, 120L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        feed.notePeerLink(status);
        CoopSessionIntelModel.LinkSample peer = feed.snapshot().peerLink();

        assertNotNull(peer);
        assertNull(peer.rttMillis());
        assertNull(peer.p95RttMillis());
        assertEquals(4, peer.lossPercent());
        assertEquals(CoopSessionIntelModel.TRANSPORT_UDP, peer.transport());
        assertEquals(120L, peer.tcpSilenceMillis());
    }

    // ---- adapters --------------------------------------------------------------------------------

    @Test
    void linkQualitySnapshotAdapterCopiesEveryField() {
        CoopLinkQuality.Snapshot snapshot =
                new CoopLinkQuality.Snapshot(75, 140, 6, true, 900L, 1_100L);

        feed.publishLink(snapshot, CoopSessionIntelModel.TRANSPORT_TCP_FALLBACK);
        CoopSessionIntelModel.LinkSample link = feed.snapshot().localLink();

        assertNotNull(link);
        assertEquals(75, link.rttMillis());
        assertEquals(140, link.p95RttMillis());
        assertEquals(6, link.lossPercent());
        assertTrue(link.udpInboundOk());
        assertTrue(link.onFallback());
        assertEquals(900L, link.tcpSilenceMillis());
        assertEquals(1, feed.snapshot().history().size());
    }

    @Test
    void portMapperResultBecomesThreeDisplayLines() {
        CoopPortMapper.Result result = new CoopPortMapper.Result(CoopPortMapper.Tier.UPNP,
                "192.168.1.1", "Router", "100.72.1.9", 7777, true, "", true);

        feed.noteReachability(result);
        CoopSessionIntelModel.Reachability reach = feed.snapshot().reachability();

        assertNotNull(reach);
        assertEquals("mapped via UPNP, but CGNAT makes the mapped port unreachable", reach.tierText());
        assertEquals("100.72.1.9:7777", reach.externalEndpoint());
        assertTrue(reach.cgnatVerdict().startsWith("yes - 100.72.1.9 is private"));
    }

    // ---- the agent bridge's ring -------------------------------------------------------------------

    /**
     * The property the {@code feed} verb is built on: the transcript outlives the session, because
     * "what did the screen say when it ended" is asked after it ended.
     */
    @Test
    void theBridgeRingSurvivesEndSessionAndCarriesKindAndColour() {
        feed.publishSession(CoopConnectionRole.GUEST, "connected", "Ayo");
        feed.noteEvent("fallback", "Co-op: state stream fell back to TCP.",
                new java.awt.Color(255, 220, 120));
        clock.addAndGet(5_000L);
        feed.noteEvent("reconnect-wait", "Co-op: connection to the host lost.", null);

        assertFalse(feed.sessionEnded(), "the session is still up");
        feed.endSession();
        assertTrue(feed.sessionEnded());

        List<CoopSessionIntelFeed.BridgeEvent> events = feed.bridgeEvents(20);
        assertEquals(2, events.size(), "endSession() must not have taken the transcript with it");
        assertEquals("fallback", events.get(0).kind(), "oldest first");
        assertEquals("Co-op: state stream fell back to TCP.", events.get(0).text());
        assertEquals("#FFDC78", events.get(0).color());
        assertEquals(1_000_000L, events.get(0).atMillis());
        assertEquals("reconnect-wait", events.get(1).kind());
        assertEquals("", events.get(1).color(), "no colour is the feed's default, not black");
        assertEquals(1_005_000L, events.get(1).atMillis());
    }

    @Test
    void aNewSessionClearsTheEndedFlagButKeepsTheTranscript() {
        feed.noteEvent("partner-left", "Co-op: Ayo left the game.", null);
        feed.endSession();

        feed.publishSession(CoopConnectionRole.HOST, "waiting", "");

        assertFalse(feed.sessionEnded());
        assertEquals(1, feed.bridgeEvents(20).size());
    }

    @Test
    void onlyAFullResetClearsTheRing() {
        feed.noteEvent("degraded", "Co-op: connection degraded.", null);
        feed.reset();

        assertTrue(feed.bridgeEvents(20).isEmpty());
        assertFalse(feed.sessionEnded(), "a reset feed has not had a session end, it has had none");
    }

    @Test
    void theRingIsBoundedAndTheLimitTakesTheNewestLines() {
        for (int i = 0; i < CoopSessionIntelFeed.MAX_BRIDGE_EVENTS + 25; i++) {
            feed.noteEvent("kind", "line " + i, null);
        }

        List<CoopSessionIntelFeed.BridgeEvent> all = feed.bridgeEvents(1_000);
        assertEquals(CoopSessionIntelFeed.MAX_BRIDGE_EVENTS, all.size());
        assertEquals("line 25", all.get(0).text(), "the oldest 25 fell off the front");
        assertEquals("line 224", all.get(all.size() - 1).text());

        List<CoopSessionIntelFeed.BridgeEvent> last3 = feed.bridgeEvents(3);
        assertEquals(3, last3.size());
        assertEquals("line 222", last3.get(0).text());
        assertEquals("line 224", last3.get(2).text());
        assertTrue(feed.bridgeEvents(0).isEmpty());
    }

    /** The page's twenty-row list is untouched by the wider ring; they are different questions. */
    @Test
    void thePageStillShowsOnlyItsOwnTwentyRows() {
        for (int i = 0; i < 30; i++) {
            feed.noteEvent("kind", "line " + i, null);
        }

        assertEquals(CoopSessionIntelModel.MAX_EVENTS, feed.snapshot().events().size());
        assertEquals(30, feed.bridgeEvents(1_000).size());
    }

    /** The one-argument form is what non-pump callers use; it must still fill the ring. */
    @Test
    void theOneArgumentFormRecordsAnUnkindedUncolouredLine() {
        feed.noteEvent("just a line");

        List<CoopSessionIntelFeed.BridgeEvent> events = feed.bridgeEvents(20);
        assertEquals(1, events.size());
        assertEquals("", events.get(0).kind());
        assertEquals("", events.get(0).color());
        assertEquals("just a line", events.get(0).text());
    }

    // ---- snapshot isolation ----------------------------------------------------------------------

    @Test
    void snapshotIsDetachedFromLaterPublishes() {
        publishRtt(10, 0);
        CoopSessionIntelModel first = feed.snapshot();

        publishRtt(20, 0);

        assertEquals(1, first.history().size());
        assertEquals(2, feed.snapshot().history().size());
    }
}
