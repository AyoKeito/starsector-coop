package coop.ui;

import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSessionIntelModelTest {

    private static CoopSessionIntelModel.LinkSample sample(Integer rtt, int loss, String transport) {
        return new CoopSessionIntelModel.LinkSample(rtt, rtt, loss, true, transport, 0L);
    }

    // ---- record invariants -----------------------------------------------------------------------

    @Test
    void emptyModelIsInactiveAndTotallyNullFree() {
        CoopSessionIntelModel model = CoopSessionIntelModel.empty();

        assertEquals(CoopConnectionRole.NONE, model.localRole());
        assertEquals("", model.sessionState());
        assertEquals("", model.partnerName());
        assertTrue(model.history().isEmpty());
        assertTrue(model.events().isEmpty());
        assertFalse(model.roleActive());
    }

    @Test
    void nullsInTheCanonicalConstructorBecomeSafeDefaults() {
        CoopSessionIntelModel model = new CoopSessionIntelModel(null, null, null, null, null, null,
                null, null, null);

        assertEquals(CoopConnectionRole.NONE, model.localRole());
        assertEquals("", model.sessionState());
        assertEquals("", model.partnerName());
        assertTrue(model.history().isEmpty());
        assertTrue(model.events().isEmpty());
    }

    @Test
    void listsAreDefensivelyCopied() {
        List<CoopSessionIntelModel.Event> events = new ArrayList<>();
        events.add(new CoopSessionIntelModel.Event("just now", "Link degraded"));
        CoopSessionIntelModel model = new CoopSessionIntelModel(CoopConnectionRole.HOST, "session active",
                "partner", null, null, null, List.of(), null, events);

        events.clear();

        assertEquals(1, model.events().size());
        assertThrows(UnsupportedOperationException.class,
                () -> model.events().add(new CoopSessionIntelModel.Event("x", "y")));
    }

    @Test
    void roleActiveTracksTheRole() {
        assertTrue(new CoopSessionIntelModel(CoopConnectionRole.GUEST, "", "", null, null, null,
                List.of(), null, List.of()).roleActive());
        assertFalse(CoopSessionIntelModel.empty().roleActive());
    }

    // ---- formatting ------------------------------------------------------------------------------

    @Test
    void roleTextCoversEveryRoleAndNull() {
        assertEquals("Host", CoopSessionIntelModel.roleText(CoopConnectionRole.HOST));
        assertEquals("Guest", CoopSessionIntelModel.roleText(CoopConnectionRole.GUEST));
        assertEquals("No session", CoopSessionIntelModel.roleText(CoopConnectionRole.NONE));
        assertEquals("No session", CoopSessionIntelModel.roleText(null));
    }

    @Test
    void rttFormattingTreatsNullAndTheWireSentinelAsUnmeasured() {
        assertEquals("42 ms", CoopSessionIntelModel.formatRtt(42));
        assertEquals("0 ms", CoopSessionIntelModel.formatRtt(0));
        assertEquals(CoopSessionIntelModel.UNKNOWN, CoopSessionIntelModel.formatRtt(null));
        assertEquals(CoopSessionIntelModel.UNKNOWN, CoopSessionIntelModel.formatRtt(-1));
    }

    @Test
    void lossFormattingTreatsNegativeAsUnmeasured() {
        assertEquals("0%", CoopSessionIntelModel.formatLoss(0));
        assertEquals("13%", CoopSessionIntelModel.formatLoss(13));
        assertEquals(CoopSessionIntelModel.UNKNOWN, CoopSessionIntelModel.formatLoss(-1));
    }

    @Test
    void transportWordingMapsTokensAndPassesUnknownsThrough() {
        assertEquals("UDP", CoopSessionIntelModel.describeTransport(CoopSessionIntelModel.TRANSPORT_UDP));
        assertEquals("TCP fallback",
                CoopSessionIntelModel.describeTransport(CoopSessionIntelModel.TRANSPORT_TCP_FALLBACK));
        assertEquals("QUIC", CoopSessionIntelModel.describeTransport("QUIC"));
        assertEquals(CoopSessionIntelModel.UNKNOWN, CoopSessionIntelModel.describeTransport(null));
        assertEquals(CoopSessionIntelModel.UNKNOWN, CoopSessionIntelModel.describeTransport(""));
    }

    @Test
    void udpPathWording() {
        assertEquals("inbound UDP OK", CoopSessionIntelModel.describeUdpPath(true));
        assertEquals("no inbound UDP", CoopSessionIntelModel.describeUdpPath(false));
    }

    @Test
    void durationsSwitchFromMillisecondsToSecondsAtOneSecond() {
        assertEquals("0 ms", CoopSessionIntelModel.formatDuration(0L));
        assertEquals("999 ms", CoopSessionIntelModel.formatDuration(999L));
        assertEquals("1.0 s", CoopSessionIntelModel.formatDuration(1000L));
        assertEquals("12.3 s", CoopSessionIntelModel.formatDuration(12_345L));
        assertEquals(CoopSessionIntelModel.UNKNOWN, CoopSessionIntelModel.formatDuration(-1L));
    }

    @Test
    void ageWordingIsCoarseAndClampsNegatives() {
        assertEquals("just now", CoopSessionIntelModel.formatAge(-5_000L));
        assertEquals("just now", CoopSessionIntelModel.formatAge(4_999L));
        assertEquals("5s ago", CoopSessionIntelModel.formatAge(5_000L));
        assertEquals("59s ago", CoopSessionIntelModel.formatAge(59_999L));
        assertEquals("1m ago", CoopSessionIntelModel.formatAge(60_000L));
        assertEquals("59m ago", CoopSessionIntelModel.formatAge(59L * 60_000L));
        assertEquals("1h 5m ago", CoopSessionIntelModel.formatAge(65L * 60_000L));
        assertEquals("1d ago", CoopSessionIntelModel.formatAge(25L * 3_600_000L));
    }

    @Test
    void degradedMirrorsTheDoctorThresholds() {
        assertFalse(CoopSessionIntelModel.degraded(null));
        assertFalse(CoopSessionIntelModel.degraded(sample(50, 0, "UDP")));
        assertTrue(CoopSessionIntelModel.degraded(
                sample(CoopSessionIntelModel.DEGRADED_RTT_MILLIS, 0, "UDP")));
        assertTrue(CoopSessionIntelModel.degraded(
                sample(20, CoopSessionIntelModel.DEGRADED_LOSS_PERCENT, "UDP")));
        assertFalse(CoopSessionIntelModel.degraded(sample(null, 0, "UDP")));
    }

    // ---- Phase 29 M2: the cadence tier on the state-stream line ---------------------------------

    @Test
    void theStateStreamLineNamesThePathAndTheRate() {
        assertEquals("UDP 10 Hz", CoopSessionIntelModel.describeStateStream(
                CoopSessionIntelModel.TRANSPORT_UDP, 10));
        assertEquals("TCP fallback 5 Hz", CoopSessionIntelModel.describeStateStream(
                CoopSessionIntelModel.TRANSPORT_TCP_FALLBACK, 5));
        assertEquals(CoopSessionIntelModel.UNKNOWN + " 10 Hz",
                CoopSessionIntelModel.describeStateStream("", 10));
        assertEquals("UDP", CoopSessionIntelModel.describeStateStream(
                CoopSessionIntelModel.TRANSPORT_UDP, 0), "an unset rate says nothing rather than 0 Hz");
    }

    @Test
    void aSampleBuiltWithoutACadenceCarriesTheDefaultTier() {
        assertEquals(CoopSessionIntelModel.DEFAULT_CADENCE_HZ,
                new CoopSessionIntelModel.LinkSample(1, 1, 0, true, "UDP", 0L).cadenceHz());
        assertEquals(5, new CoopSessionIntelModel.LinkSample(1, 1, 0, true, "UDP", 0L, 5).cadenceHz());
    }

    @Test
    void linkSampleNormalisesTransportAndKnowsTheFallback() {
        assertEquals("", new CoopSessionIntelModel.LinkSample(1, 1, 0, true, null, 0L).transport());
        assertTrue(sample(10, 0, CoopSessionIntelModel.TRANSPORT_TCP_FALLBACK).onFallback());
        assertFalse(sample(10, 0, CoopSessionIntelModel.TRANSPORT_UDP).onFallback());
    }

    // ---- sparkline -------------------------------------------------------------------------------

    @Test
    void sparklineScalesBetweenMinAndMax() {
        String line = CoopSessionIntelModel.sparkline(Arrays.asList(0, 50, 100));

        assertEquals(3, line.length());
        assertEquals(CoopSessionIntelModel.SPARK_LEVELS.charAt(0), line.charAt(0));
        assertEquals(CoopSessionIntelModel.SPARK_LEVELS.charAt(
                CoopSessionIntelModel.SPARK_LEVELS.length() / 2), line.charAt(1));
        assertEquals(CoopSessionIntelModel.SPARK_LEVELS.charAt(
                CoopSessionIntelModel.SPARK_LEVELS.length() - 1), line.charAt(2));
    }

    @Test
    void sparklineRendersGapsForMissingSamples() {
        String line = CoopSessionIntelModel.sparkline(Arrays.asList(10, null, 20));

        assertEquals(3, line.length());
        assertEquals(CoopSessionIntelModel.SPARK_GAP, line.charAt(1));
    }

    @Test
    void sparklineWithNoValuesAtAllIsAllGaps() {
        assertEquals("~~~", CoopSessionIntelModel.sparkline(Arrays.asList(null, null, null)));
        assertEquals("", CoopSessionIntelModel.sparkline(List.of()));
        assertEquals("", CoopSessionIntelModel.sparkline(null));
    }

    @Test
    void flatSeriesRendersAtTheLowestLevel() {
        assertEquals("___", CoopSessionIntelModel.sparkline(Arrays.asList(7, 7, 7)));
    }

    // ---- history stats ---------------------------------------------------------------------------

    @Test
    void statsOfAnEmptyRingReportsNothingMeasured() {
        CoopSessionIntelModel.HistoryStats stats = CoopSessionIntelModel.empty().stats();

        assertEquals(0, stats.samples());
        assertNull(stats.minRttMillis());
        assertNull(stats.medianRttMillis());
        assertNull(stats.maxRttMillis());
        assertEquals(-1, stats.medianLossPercent());
    }

    @Test
    void statsIgnoreUnmeasuredSamplesButStillCountThem() {
        List<CoopSessionIntelModel.HistoryPoint> points = List.of(
                new CoopSessionIntelModel.HistoryPoint(30, 0),
                new CoopSessionIntelModel.HistoryPoint(null, -1),
                new CoopSessionIntelModel.HistoryPoint(10, 5),
                new CoopSessionIntelModel.HistoryPoint(50, 20));

        CoopSessionIntelModel.HistoryStats stats = CoopSessionIntelModel.statsOf(points);

        assertEquals(4, stats.samples());
        assertEquals(10, stats.minRttMillis());
        assertEquals(30, stats.medianRttMillis());
        assertEquals(50, stats.maxRttMillis());
        assertEquals(0, stats.minLossPercent());
        assertEquals(5, stats.medianLossPercent());
        assertEquals(20, stats.maxLossPercent());
    }

    @Test
    void historyColumnsPreserveOrderAndTurnUnmeasuredLossIntoGaps() {
        CoopSessionIntelModel model = new CoopSessionIntelModel(CoopConnectionRole.HOST, "s", "p",
                null, null, null,
                List.of(new CoopSessionIntelModel.HistoryPoint(10, 1),
                        new CoopSessionIntelModel.HistoryPoint(null, -1)),
                null, List.of());

        assertEquals(Arrays.asList(10, null), model.rttHistory());
        assertEquals(Arrays.asList(1, null), model.lossHistory());
    }

    // ---- reachability wording --------------------------------------------------------------------

    @Test
    void reachabilityTierWording() {
        assertEquals("mapped via UPNP",
                CoopSessionIntelModel.reachabilityTierText("UPNP", true, true, false, ""));
        assertEquals("mapped via NAT_PMP, but CGNAT makes the mapped port unreachable",
                CoopSessionIntelModel.reachabilityTierText("NAT_PMP", true, true, true, ""));
        assertEquals("still negotiating with the router",
                CoopSessionIntelModel.reachabilityTierText("NONE", false, false, false, ""));
        assertEquals("no mapping (not attempted)",
                CoopSessionIntelModel.reachabilityTierText(null, false, true, false, "  "));
        assertEquals("no mapping (no UPnP gateway answered)",
                CoopSessionIntelModel.reachabilityTierText("NONE", false, true, false,
                        "no UPnP gateway answered"));
    }

    @Test
    void reachabilityEndpointWording() {
        assertEquals("1.2.3.4:7777", CoopSessionIntelModel.reachabilityEndpointText("1.2.3.4:7777"));
        assertEquals("not discovered", CoopSessionIntelModel.reachabilityEndpointText(""));
        assertEquals("not discovered", CoopSessionIntelModel.reachabilityEndpointText(null));
    }

    @Test
    void cgnatWording() {
        assertEquals("yes - 100.72.1.9 is private; no IPv4 port forward can reach you",
                CoopSessionIntelModel.cgnatText(true, "100.72.1.9"));
        assertEquals("yes - the router's external address is private;"
                        + " no IPv4 port forward can reach you",
                CoopSessionIntelModel.cgnatText(true, null));
        assertEquals("no - 84.10.2.3 is a public address",
                CoopSessionIntelModel.cgnatText(false, " 84.10.2.3 "));
        assertEquals("unknown (no external address was discovered)",
                CoopSessionIntelModel.cgnatText(false, ""));
    }

    @Test
    void reachabilityRecordNeverHoldsNulls() {
        CoopSessionIntelModel.Reachability reach =
                new CoopSessionIntelModel.Reachability(null, null, null);

        assertEquals("", reach.tierText());
        assertEquals("", reach.externalEndpoint());
        assertEquals("", reach.cgnatVerdict());
    }

    // ---- list row --------------------------------------------------------------------------------

    @Test
    void listLineFallsBackToTheStateWhenThereIsNoRtt() {
        assertEquals("no session", CoopSessionIntelModel.listLine(null));
        assertEquals("no session", CoopSessionIntelModel.listLine(CoopSessionIntelModel.empty()));
        assertEquals("waiting for guest", CoopSessionIntelModel.listLine(
                new CoopSessionIntelModel(CoopConnectionRole.HOST, "waiting for guest", "", null,
                        null, null, List.of(), null, List.of())));
    }

    @Test
    void listLineShowsRttAndFlagsTheFallback() {
        CoopSessionIntelModel udp = new CoopSessionIntelModel(CoopConnectionRole.HOST,
                "session active", "Ayo", sample(42, 0, CoopSessionIntelModel.TRANSPORT_UDP),
                null, null, List.of(), null, List.of());
        CoopSessionIntelModel tcp = new CoopSessionIntelModel(CoopConnectionRole.HOST,
                "session active", "Ayo",
                sample(180, 0, CoopSessionIntelModel.TRANSPORT_TCP_FALLBACK),
                null, null, List.of(), null, List.of());

        assertEquals("session active - 42 ms", CoopSessionIntelModel.listLine(udp));
        assertEquals("session active - 180 ms (TCP fallback)", CoopSessionIntelModel.listLine(tcp));
    }
}
