package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopLinkQualityTest {

    private static final String SENDER = "abcdef0123456789";

    private static CoopLinkQuality armed(long now) {
        CoopLinkQuality link = new CoopLinkQuality();
        link.reset(now);
        return link;
    }

    // ---- RTT ------------------------------------------------------------------------------------

    @Test
    void rttIsUnknownUntilAPongIsMatched() {
        CoopLinkQuality link = armed(1_000L);

        assertNull(link.rttMillis());
        assertNull(link.p95RttMillis());
    }

    @Test
    void firstSampleSeedsTheEwmaAndLaterSamplesSmoothTowardIt() {
        CoopLinkQuality link = armed(1_000L);

        link.notePingSent(1L, 1_000L);
        assertEquals(100, link.notePongReceived(1L, 1_100L));
        assertEquals(100, link.rttMillis(), "the first sample seeds the average rather than decaying from zero");

        link.notePingSent(2L, 2_000L);
        link.notePongReceived(2L, 2_300L);
        // 0.2 * 300 + 0.8 * 100 = 140
        assertEquals(140, link.rttMillis());
    }

    @Test
    void anUnmatchedPongContributesNoSample() {
        CoopLinkQuality link = armed(1_000L);

        assertEquals(-1, link.notePongReceived(99L, 1_100L));
        assertNull(link.rttMillis());
    }

    @Test
    void outstandingPingsAreBoundedSoASilentPeerCannotGrowTheMap() {
        CoopLinkQuality link = armed(0L);

        for (long seq = 1; seq <= CoopLinkQuality.MAX_OUTSTANDING_PINGS + 4; seq++) {
            link.notePingSent(seq, seq * 3_000L);
        }

        assertEquals(-1, link.notePongReceived(1L, 100_000L), "the eldest unanswered pings are evicted");
        assertEquals(1_000,
                link.notePongReceived(CoopLinkQuality.MAX_OUTSTANDING_PINGS + 4L,
                        (CoopLinkQuality.MAX_OUTSTANDING_PINGS + 4L) * 3_000L + 1_000L),
                "the newest ping is still matchable");
    }

    @Test
    void p95IgnoresAllButTheWorstOfAWindowOfSamples() {
        CoopLinkQuality link = armed(0L);

        // 31 fast samples and one 900 ms spike: p95 of 32 samples is the second-worst.
        for (long seq = 1; seq <= 31; seq++) {
            link.notePingSent(seq, seq * 1_000L);
            link.notePongReceived(seq, seq * 1_000L + 50L);
        }
        link.notePingSent(32L, 32_000L);
        link.notePongReceived(32L, 32_900L);

        assertEquals(50, link.p95RttMillis(), "one spike in 32 samples must not move the p95");

        link.notePingSent(33L, 33_000L);
        link.notePongReceived(33L, 33_800L);
        assertEquals(800, link.p95RttMillis(), "two spikes in 32 samples do move it");
    }

    // ---- loss window ----------------------------------------------------------------------------

    @Test
    void aDenseEpochSequenceReadsAsZeroLoss() {
        CoopLinkQuality link = armed(0L);

        for (int i = 1; i <= 20; i++) {
            link.noteInboundDatagram(SENDER, i, i * 100L);
        }

        assertEquals(0, link.lossPercent(2_000L));
    }

    @Test
    void everyOtherEpochMissingReadsAsHalfLoss() {
        CoopLinkQuality link = armed(0L);

        // Epochs 1,3,5..19 arrive; 2,4..20 were lost. 10 of 19 arrived.
        for (int epoch = 1; epoch <= 19; epoch += 2) {
            link.noteInboundDatagram(SENDER, epoch, epoch * 100L);
        }

        assertEquals(47, link.lossPercent(2_000L), "9 of 19 epochs in the span never arrived");
    }

    @Test
    void aSingleDatagramIsNotEnoughEvidenceForALossFigure() {
        CoopLinkQuality link = armed(0L);

        link.noteInboundDatagram(SENDER, 7L, 100L);

        assertEquals(0, link.lossPercent(200L));
    }

    /**
     * Redundancy means the same epoch can be seen twice (the previous section rides along in the next
     * datagram, and the pump feeds only the last section's epoch, but a duplicated datagram would
     * repeat it). A repeat must not manufacture an extra "arrival" and hide real loss.
     */
    @Test
    void repeatedEpochsAreCountedOnce() {
        CoopLinkQuality link = armed(0L);

        link.noteInboundDatagram(SENDER, 1L, 100L);
        link.noteInboundDatagram(SENDER, 1L, 150L);
        link.noteInboundDatagram(SENDER, 1L, 180L);
        link.noteInboundDatagram(SENDER, 3L, 300L);

        assertEquals(33, link.lossPercent(400L), "epoch 2 is still missing however often 1 repeats");
    }

    @Test
    void theLossWindowSlidesSoAnOldOutageStopsCounting() {
        CoopLinkQuality link = armed(0L);

        link.noteInboundDatagram(SENDER, 1L, 1_000L);
        link.noteInboundDatagram(SENDER, 10L, 2_000L);
        assertTrue(link.lossPercent(2_000L) > 50);

        // 20 s later only the fresh, dense samples are inside the window.
        for (int i = 0; i < 10; i++) {
            link.noteInboundDatagram(SENDER, 100 + i, 22_000L + i * 100L);
        }
        assertEquals(0, link.lossPercent(23_000L));
    }

    @Test
    void aRestartedPeersEpochCounterRestartsTheWindowInsteadOfFakingTotalLoss() {
        CoopLinkQuality link = armed(0L);

        for (int i = 5_000; i < 5_010; i++) {
            link.noteInboundDatagram(SENDER, i, i);
        }
        link.noteInboundDatagram(SENDER, 1L, 6_000L);
        link.noteInboundDatagram(SENDER, 2L, 6_100L);

        assertEquals(0, link.lossPercent(6_200L));
    }

    @Test
    void lossIsReportedPerSenderAndTheWorstSenderWins() {
        CoopLinkQuality link = armed(0L);

        for (int i = 1; i <= 10; i++) {
            link.noteInboundDatagram("clean", i, i * 100L);
        }
        for (int epoch = 1; epoch <= 19; epoch += 2) {
            link.noteInboundDatagram("lossy", epoch, epoch * 100L);
        }

        assertEquals(47, link.lossPercent(2_000L));
    }

    // ---- silence timers -------------------------------------------------------------------------

    @Test
    void silenceIsMeasuredFromTheSessionStartNotFromEpochZero() {
        CoopLinkQuality link = armed(1_000_000L);

        assertEquals(0L, link.tcpSilenceMillis(1_000_000L));
        assertEquals(0L, link.udpSilenceMillis(1_000_000L));
        assertEquals(500L, link.tcpSilenceMillis(1_000_500L));
        assertTrue(link.udpInboundOk(1_000_500L));
    }

    @Test
    void tcpWrappedDatagramsDoNotCountAsUdpLiveness() {
        CoopLinkQuality link = armed(0L);

        link.noteInboundDatagram(SENDER, 1L, 20_000L);

        assertEquals(20_000L, link.udpSilenceMillis(20_000L),
                "only the transport's own UDP stamp may clear the UDP silence timer");
        link.noteUdpInbound(20_000L);
        assertEquals(0L, link.udpSilenceMillis(20_000L));
    }

    @Test
    void udpInboundOkFlipsAtTenSeconds() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);

        assertTrue(link.udpInboundOk(10_999L));
        assertFalse(link.udpInboundOk(11_000L));
    }

    // ---- fallback decision ----------------------------------------------------------------------

    @Test
    void udpSilenceWithLiveTcpEntersFallback() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(10_000L);

        assertFalse(link.evaluateFallback(10_500L, null), "9.5 s of UDP silence is not yet enough");
        link.noteInboundTcp(11_500L);
        assertTrue(link.evaluateFallback(11_500L, null));
        assertEquals(CoopLinkQuality.TRANSPORT_TCP_FALLBACK, link.transport());
    }

    /**
     * The reason the TCP clause exists: a peer in combat or writing a coordinated autosave stops
     * running its pump, so BOTH transports go quiet. That is not a blocked path and must not switch
     * the stream onto a wire the peer is not reading either.
     */
    @Test
    void silenceOnBothTransportsIsNotTreatedAsBlockedUdp() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(1_000L);

        assertFalse(link.evaluateFallback(60_000L, null));
        assertEquals(CoopLinkQuality.TRANSPORT_UDP, link.transport());
    }

    @Test
    void aPeerReportingNoInboundUdpEntersFallbackEvenWhenTheLocalSideIsFine() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(1_500L);

        assertTrue(link.evaluateFallback(2_000L, Boolean.FALSE));
        assertTrue(link.fallbackReason().contains("peer"));
    }

    @Test
    void aStalePeerReportIsNoEvidence() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(1_500L);

        // The pump passes null once the peer's LINK_STATUS has aged out; the local side is healthy.
        assertFalse(link.evaluateFallback(2_000L, null));
    }

    @Test
    void leavingFallbackNeedsFiveContinuousSecondsOfClearEvidence() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(11_500L);
        assertTrue(link.evaluateFallback(11_500L, null));

        link.noteUdpInbound(12_000L);
        link.noteInboundTcp(12_000L);
        assertTrue(link.evaluateFallback(12_000L, null), "clear, but not yet for long enough");
        assertTrue(link.evaluateFallback(16_000L, null));
        assertFalse(link.evaluateFallback(17_000L, null), "clear for 5 s: back to UDP");
    }

    @Test
    void theHysteresisTimerRestartsWhenTheEvidenceComesBack() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(11_500L);
        assertTrue(link.evaluateFallback(11_500L, null));

        link.noteUdpInbound(12_000L);
        link.noteInboundTcp(12_000L);
        assertTrue(link.evaluateFallback(12_000L, null));
        // Peer says it still sees no UDP at 15 s: the clear run restarts from there.
        assertTrue(link.evaluateFallback(15_000L, Boolean.FALSE));
        // The clear run restarts at the first clear evaluation after that, not at 12 s.
        assertTrue(link.evaluateFallback(19_000L, Boolean.TRUE));
        assertTrue(link.evaluateFallback(23_000L, Boolean.TRUE), "only 4 s clear so far");
        assertFalse(link.evaluateFallback(24_100L, Boolean.TRUE));
    }

    @Test
    void resetClearsTheFallbackAndTheMeasurements() {
        CoopLinkQuality link = armed(0L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(11_500L);
        link.notePingSent(1L, 0L);
        link.notePongReceived(1L, 500L);
        assertTrue(link.evaluateFallback(11_500L, null));

        link.reset(20_000L);

        assertFalse(link.fallbackActive());
        assertNull(link.rttMillis());
        assertEquals(0, link.lossPercent(20_000L));
        assertEquals(0L, link.udpSilenceMillis(20_000L));
    }

    // ---- degraded -------------------------------------------------------------------------------

    @Test
    void degradedNeedsTenContinuousSecondsAndClearsTheSameWay() {
        CoopLinkQuality link = armed(0L);
        link.notePingSent(1L, 0L);
        link.notePongReceived(1L, 900L);

        assertFalse(link.evaluateDegraded(1_000L), "one bad reading is not a degraded link");
        assertFalse(link.evaluateDegraded(9_000L));
        assertTrue(link.evaluateDegraded(11_000L));

        // Ten good samples pull the EWMA back under the threshold.
        for (long seq = 2; seq <= 30; seq++) {
            link.notePingSent(seq, 11_000L + seq * 100L);
            link.notePongReceived(seq, 11_000L + seq * 100L + 20L);
        }
        assertTrue(link.evaluateDegraded(15_000L), "recovery is sustained too");
        assertFalse(link.evaluateDegraded(30_000L));
    }

    @Test
    void heavyLossAloneIsEnoughToReadAsDegraded() {
        CoopLinkQuality link = armed(0L);
        for (int epoch = 1; epoch <= 19; epoch += 2) {
            link.noteInboundDatagram(SENDER, epoch, epoch * 100L);
        }

        assertFalse(link.evaluateDegraded(1_000L));
        assertTrue(link.evaluateDegraded(11_000L));
    }

    // ---- link death (Phase 20.2) ------------------------------------------------------------------

    @Test
    void silenceUnderTheThresholdIsNeverDeath() {
        CoopLinkQuality link = armed(0L);
        link.noteInboundTcp(0L);

        CoopLinkQuality.DeathVerdict verdict = link.evaluateLinkDeath(14_999L, false, 0L);

        assertFalse(verdict.dead());
        assertFalse(verdict.exempted());
        assertEquals(14_999L, verdict.tcpSilenceMillis());
    }

    @Test
    void fifteenSecondsOfTcpSilenceWithNoExemptionIsDeath() {
        CoopLinkQuality link = armed(0L);
        link.noteInboundTcp(0L);

        CoopLinkQuality.DeathVerdict verdict = link.evaluateLinkDeath(15_000L, false, 0L);

        assertTrue(verdict.dead());
        assertFalse(verdict.exempted());
        assertTrue(verdict.describe().contains("tcpSilence=15000"), verdict.describe());
    }

    @Test
    void aPeerInCombatIsExemptFromDeathNoMatterHowLongItIsQuiet() {
        CoopLinkQuality link = armed(0L);
        link.noteInboundTcp(0L);

        CoopLinkQuality.DeathVerdict verdict = link.evaluateLinkDeath(600_000L, true, 0L);

        assertFalse(verdict.dead());
        assertTrue(verdict.peerInCombat());
        assertTrue(verdict.exempted());
    }

    @Test
    void aRecentSaveCheckpointIsExemptAndTheExemptionAgesOut() {
        CoopLinkQuality link = armed(0L);
        link.noteInboundTcp(0L);

        // Checkpoint at 1 s, evaluated at 40 s: inside the 60 s exempt window.
        assertFalse(link.evaluateLinkDeath(40_000L, false, 1_000L).dead());
        assertTrue(link.evaluateLinkDeath(40_000L, false, 1_000L).recentSaveCheckpoint());
        // Same checkpoint, evaluated at 62 s: the save cannot explain this much silence any more.
        assertTrue(link.evaluateLinkDeath(62_000L, false, 1_000L).dead());
        // A zero stamp means "no checkpoint has ever happened", not "one happened at the epoch".
        assertTrue(link.evaluateLinkDeath(40_000L, false, 0L).dead());
    }

    @Test
    void aLocalFrameGapExemptsUntilAFullSilenceWindowHasBeenReEarned() {
        CoopLinkQuality link = armed(0L);
        link.noteInboundTcp(0L);
        link.noteFrame(0L);
        // The local process stalls for 30 s (its own combat or save) and comes back at 30 s.
        link.noteFrame(30_000L);

        assertEquals(30_000L, link.lastFrameGapMillis());
        // The accumulated silence is ours, not the peer's: no verdict yet.
        assertFalse(link.evaluateLinkDeath(30_000L, false, 0L).dead());
        assertTrue(link.evaluateLinkDeath(30_000L, false, 0L).localStalled());
        assertFalse(link.evaluateLinkDeath(44_000L, false, 0L).dead());
        // A full silence window after the stall ended, the evidence is ours again.
        assertTrue(link.evaluateLinkDeath(45_000L, false, 0L).dead());
    }

    @Test
    void anOrdinaryFrameGapIsNotALocalStall() {
        CoopLinkQuality link = armed(0L);
        link.noteInboundTcp(0L);
        for (long frame = 0L; frame <= 20_000L; frame += 16L) {
            link.noteFrame(frame);
        }

        CoopLinkQuality.DeathVerdict verdict = link.evaluateLinkDeath(20_000L, false, 0L);

        assertFalse(verdict.localStalled());
        assertTrue(verdict.dead());
    }

    @Test
    void resumingResetsTheSilenceTimersButKeepsTheRttHistory() {
        CoopLinkQuality link = armed(0L);
        link.notePingSent(1L, 0L);
        link.notePongReceived(1L, 90L);
        link.noteInboundTcp(0L);
        assertTrue(link.evaluateLinkDeath(20_000L, false, 0L).dead());

        link.resetSilence(20_000L);

        assertEquals(90, link.rttMillis(), "the same two machines on the same path");
        assertEquals(0L, link.lastFrameGapMillis(), "no frames were noted in this test");
        assertEquals(0L, link.tcpSilenceMillis(20_000L));
        assertFalse(link.evaluateLinkDeath(20_000L, false, 0L).dead());
    }

    @Test
    void theSnapshotIsOneSelfConsistentReadOfEverythingLinkStatusCarries() {
        CoopLinkQuality link = armed(0L);
        link.notePingSent(1L, 0L);
        link.notePongReceived(1L, 120L);
        link.noteUdpInbound(1_000L);
        link.noteInboundTcp(1_500L);

        CoopLinkQuality.Snapshot snapshot = link.snapshot(3_000L);

        assertEquals(120, snapshot.rttMillis());
        assertEquals(120, snapshot.p95RttMillis());
        assertEquals(0, snapshot.lossPercent());
        assertTrue(snapshot.udpInboundOk());
        assertEquals(1_500L, snapshot.tcpSilenceMillis());
        assertEquals(2_000L, snapshot.udpSilenceMillis());
    }
}
