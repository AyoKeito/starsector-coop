package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
        FakeEngine engine = new FakeEngine(60_000L);
        CoopCreditTransfer transfer = replicator.replaceCreditTransferEngineForTest(engine);

        assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(50_000));

        assertEquals(10_000L, engine.credits);
        CoopMessages.Message message = service.lastOfType(CoopMessages.Type.CREDITS_GRANT);
        assertEquals("session-a", message.sessionId());
        assertEquals(4242L, message.sentAtMillis());
        CoopMessages.CreditsGrant grant = CoopMessages.parseCreditsGrant(message);
        assertEquals(50_000, grant.amount());
        assertEquals("gift", grant.reason());
        assertTrue(grant.ledgerId().startsWith("guest-player-"), grant.ledgerId());
    }

    @Test
    void aHostAppliesAnInboundGrantOnceHoweverOftenItArrives() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopCampaignReplicator replicator =
                new CoopCampaignReplicator(service, activeHostSession(), () -> 1L);
        FakeEngine engine = new FakeEngine(1_000L);
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
        FakeEngine engine = new FakeEngine(1_000L);
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
        FakeEngine engine = new FakeEngine(0L);
        CoopCreditTransfer transfer = replicator.replaceCreditTransferEngineForTest(engine);

        replicator.handle(CoopMessages.creditsGrant("session-a", 9L, 100L, "g-1", 500, "gift"));
        assertTrue(transfer.hasApplied("g-1"));

        replicator.dispose(null);

        org.junit.jupiter.api.Assertions.assertFalse(transfer.hasApplied("g-1"));
    }

    private static final class FakeEngine implements CoopCreditTransfer.Engine {
        private long credits;
        private final List<String> feed = new ArrayList<>();

        private FakeEngine(long credits) {
            this.credits = credits;
        }

        @Override
        public long credits() {
            return credits;
        }

        @Override
        public void addCredits(long delta) {
            credits += delta;
        }

        @Override
        public void feed(String line) {
            feed.add(line);
        }

        @Override
        public String partnerName() {
            return "Partner";
        }
    }
}
