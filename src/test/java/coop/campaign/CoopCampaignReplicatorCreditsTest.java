package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.FakeCreditEngine;
import coop.testing.RecordingNetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static coop.testing.TestSessions.activeGuestSession;
import static coop.testing.TestSessions.activeHostSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32 addition B, end to end through the replicator: the real session id, the real sequence
 * counter and the real ledger-id minting on the way out, and the real message switch on the way in.
 * Only the wallet and the feed are faked.
 */
class CoopCampaignReplicatorCreditsTest {

    @AfterEach
    void clearHandle() {
        CoopCreditTransfer.uninstall();
    }

    @Test
    void aGuestSendsAGrantWithASessionUniqueLedgerId() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeGuestSession(), () -> 4242L);
        FakeCreditEngine engine = new FakeCreditEngine(60_000L);
        CoopCreditTransfer transfer = replicator.replaceCreditTransferEngineForTest(engine);

        assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(50_000));

        assertEquals(10_000L, engine.credits);
        CoopMessages.Message message = service.lastOfType(CoopMessages.Type.CREDITS_GRANT);
        assertEquals("session-a", message.sessionId());
        assertEquals(4242L, message.sentAtMillis());
        CoopMessages.CreditsGrant grant = CoopMessages.parseCreditsGrant(message);
        assertEquals(50_000, grant.amount());
        assertEquals("gift", grant.reason());
        // Credit red-team P1-3: the session id has to be in the prefix. nextSeq restarts at 1 with a
        // new CoopNetService (loading a save builds one), so a bare <playerId>-<seq> can reuse an id
        // the peer's still-live applied-ledger has already paid, and the peer would discard the new
        // grant as a duplicate while this side showed a "Sent" line.
        assertTrue(grant.ledgerId().startsWith("session-a-guest-player-"), grant.ledgerId());
    }

    @Test
    void aHostAppliesAnInboundGrantOnceHoweverOftenItArrives() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeHostSession(), () -> 1L);
        FakeCreditEngine engine = new FakeCreditEngine(1_000L);
        replicator.replaceCreditTransferEngineForTest(engine);

        CoopMessages.Message grant = CoopMessages.creditsGrant("session-a", 9L, 100L,
                "guest-player-9", 25_000, "gift");

        assertTrue(replicator.handle(grant), "CREDITS_GRANT is a campaign-replication message type");
        replicator.handle(grant);

        assertEquals(26_000L, engine.credits, "the ledger makes the redelivery free");
        assertEquals(1, engine.feed.size());
    }

    @Test
    void aMalformedGrantIsDroppedRatherThanCrediting() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeHostSession(), () -> 1L);
        FakeCreditEngine engine = new FakeCreditEngine(1_000L);
        replicator.replaceCreditTransferEngineForTest(engine);

        assertTrue(replicator.handle(new CoopMessages.Message(CoopMessages.Type.CREDITS_GRANT,
                "session-a", 9L, 100L, "{\"ledgerId\":\"x\",\"reason\":\"gift\"}")));

        assertEquals(1_000L, engine.credits);
    }

    @Test
    void teardownDropsTheGrantLedgerWithTheSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeHostSession(), () -> 1L);
        FakeCreditEngine engine = new FakeCreditEngine(0L);
        CoopCreditTransfer transfer = replicator.replaceCreditTransferEngineForTest(engine);

        replicator.handle(CoopMessages.creditsGrant("session-a", 9L, 100L, "g-1", 500, "gift"));
        assertTrue(transfer.hasApplied("g-1"));

        replicator.dispose(null);

        org.junit.jupiter.api.Assertions.assertFalse(transfer.hasApplied("g-1"));
    }

    /**
     * Credit red-team P1-1/P1-2: the replicator is where the transport's discard reports are wired to
     * the refund, and {@code replaceCreditTransferEngineForTest} swaps the transfer instance, so the
     * registration has to follow the swap rather than pin the instance that existed at construction.
     */
    @Test
    void aDiscardedGrantIsRefundedThroughTheReplicatorsRegistration() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeGuestSession(), () -> 1L);
        FakeCreditEngine engine = new FakeCreditEngine(60_000L);
        CoopCreditTransfer transfer = replicator.replaceCreditTransferEngineForTest(engine);

        assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(50_000));
        assertEquals(10_000L, engine.credits);
        CoopMessages.Message grant = service.lastOfType(CoopMessages.Type.CREDITS_GRANT);

        service.reportOutboundDiscardForTest(grant, "session-end");

        assertEquals(60_000L, engine.credits, "an undelivered grant comes back to the sender");
        assertTrue(engine.feed.stream().anyMatch(line -> line.contains("were returned")),
                engine.feed.toString());
    }

    /** Credit red-team P2-3: an arrival is on the intel page too, not only the campaign feed. */
    @Test
    void anAppliedGrantIsRecordedOnTheSessionIntelFeed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeHostSession(), () -> 1L);
        FakeCreditEngine engine = new FakeCreditEngine(0L);
        replicator.replaceCreditTransferEngineForTest(engine);

        replicator.handle(CoopMessages.creditsGrant("session-a", 9L, 100L, "g-1", 500, "gift"));

        assertEquals(1, engine.intel.size(), engine.intel.toString());
        assertTrue(engine.intel.get(0).contains("500 credits"), engine.intel.toString());
    }
}
