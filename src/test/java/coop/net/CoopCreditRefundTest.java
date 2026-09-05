package coop.net;

import coop.campaign.CoopCreditTransfer;
import coop.testing.FakeCreditEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 32 addition B, credit red-team P1-1/P1-2/P1-4: every place the transport throws a queued
 * message away without writing it, wired to the real {@link CoopCreditTransfer} through the real
 * {@link CoopOutboundDiscardListener} seam.
 *
 * <p>The unit tests in {@code CoopCreditTransferTest} prove the refund <em>rules</em> against a fake
 * wallet. These prove the <em>plumbing</em>: that each drop site actually reaches those rules, with
 * the real queue and the real message, and that a message which did reach a socket does not.
 *
 * <p>Nothing here needs the campaign — {@code CoopCreditTransfer.Engine} is a fake — and only the
 * last test needs a socket.
 */
class CoopCreditRefundTest {

    private static final String SESSION = "session-a";

    private final FakeCreditEngine engine = new FakeCreditEngine(100_000L);

    /**
     * A transfer whose {@code Link} pushes real {@code CREDITS_GRANT} messages into {@code service}'s
     * outbound queue, subscribed to that service's discard reports. This is the production wiring
     * with the campaign half swapped out; the replicator does the same thing with a lambda.
     */
    private CoopCreditTransfer transferOn(CoopNetService service) {
        CoopCreditTransfer transfer = new CoopCreditTransfer(engine, new CoopCreditTransfer.Link() {
            private int minted;

            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public String mintLedgerId() {
                return SESSION + "-player-a-" + (++minted);
            }

            @Override
            public void sendGrant(String ledgerId, int amount, String reason) {
                service.send(CoopMessages.creditsGrant(SESSION, service.nextSeq(), 1_000L,
                        ledgerId, amount, reason));
            }
        });
        service.setOutboundDiscardListener(transfer);
        return transfer;
    }

    /** P1-2: the queue-cap drop closes the link so the reconnect path can run - and wiped the queue. */
    @Test
    void theQueueCapDropRefundsTheGrantItWouldOtherwiseHaveDeleted() {
        CoopNetService service = new CoopNetService();
        try {
            CoopCreditTransfer transfer = transferOn(service);
            assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(25_000));
            assertEquals(75_000L, engine.credits);

            floodPastTheDropThreshold(service);

            assertEquals(0, service.outboundQueueDepth(), "the dropped link's queue went with it");
            assertEquals(100_000L, engine.credits,
                    "the grant was queued, never written, and nobody re-sends one");
            assertEquals(1, refundLines(), engine.feed.toString());
        } finally {
            service.shutdown();
        }
    }

    /** P1-1: a new socket used to be written a grant belonging to the session that just died. */
    @Test
    void attachingANewSocketRefundsTheGrantLeftOverFromTheOldSession() throws Exception {
        CoopNetService service = new CoopNetService();
        try {
            CoopCreditTransfer transfer = transferOn(service);
            transfer.send(25_000);
            assertEquals(1, service.outboundQueueDepth());
            assertFalse(service.grantsHeldForResumeLocked(),
                    "no grace open, so this attach is a different session rather than a resume");

            service.peerForTest(0).attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false, 1L);

            assertEquals(0, service.outboundQueueDepth(),
                    "written onto the new socket it is discarded as pre-session traffic by the far"
                            + " side, or paid to whoever took the slot");
            assertEquals(100_000L, engine.credits);
            assertEquals(1, refundLines(), engine.feed.toString());
        } finally {
            service.shutdown();
        }
    }

    /**
     * The other half of the attach rule, and the one the design's central promise rests on: a socket
     * attaching <em>into</em> a reconnect grace is the partner coming back, and the grant queued
     * behind it is about to be delivered to exactly the player it was meant for. Refunding it there
     * would cancel a delivery that was about to succeed.
     */
    @Test
    void attachingDuringAReconnectGraceLeavesTheGrantQueuedForTheResume() throws Exception {
        CoopNetService service = new CoopNetService();
        try {
            CoopCreditTransfer transfer = transferOn(service);
            // The pump's gate for an open grace: nothing but the resume vocabulary goes out, and
            // CREDITS_GRANT is not in it.
            service.setOutboundWriteGate(type -> type == CoopMessages.Type.SESSION_RESUME_REQUEST);
            assertTrue(service.grantsHeldForResumeLocked(),
                    "this gate is what attachChannelLocked reads to tell a resume from a new session");
            transfer.send(25_000);

            service.peerForTest(0).attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false, 1L,
                    true);

            assertEquals(1, service.outboundQueueDepth(), "the grant waits for the resume");
            assertEquals(75_000L, engine.credits, "and it is still the partner's money, not a refund");
            assertEquals(0, refundLines(), engine.feed.toString());
        } finally {
            service.setOutboundWriteGate(null);
            service.shutdown();
        }
    }

    /** P1-1: grace expiry, where both the write gate and the session token stop holding the grant. */
    @Test
    void endingTheSessionRefundsEveryGrantStillQueued() {
        CoopNetService service = new CoopNetService();
        try {
            CoopCreditTransfer transfer = transferOn(service);
            transfer.send(25_000);
            transfer.send(10_000);
            assertEquals(65_000L, engine.credits);

            assertEquals(2, service.discardOutboundCreditsGrants());

            assertEquals(100_000L, engine.credits);
            assertEquals(2, refundLines(), engine.feed.toString());

            // Idempotent: there is nothing left to drop, so a second pass pays nothing.
            assertEquals(0, service.discardOutboundCreditsGrants());
            assertEquals(100_000L, engine.credits);
        } finally {
            service.shutdown();
        }
    }

    /** P1-4: "the session ending forever" - the case the design accepted, now refunded too. */
    @Test
    void shuttingTheTransportDownRefundsTheGrantItWasStillHolding() {
        CoopNetService service = new CoopNetService();
        CoopCreditTransfer transfer = transferOn(service);
        transfer.send(25_000);
        assertEquals(75_000L, engine.credits);

        service.shutdown();

        assertEquals(100_000L, engine.credits);
        assertEquals(1, refundLines(), engine.feed.toString());
    }

    /** Two notifications for one grant - the queue cannot produce this, but the rule is load-bearing. */
    @Test
    void aSecondDiscardNotificationForTheSameGrantPaysNothing() {
        CoopNetService service = new CoopNetService();
        try {
            CoopCreditTransfer transfer = transferOn(service);
            transfer.send(25_000);
            CoopMessages.Message grant = CoopMessages.creditsGrant(SESSION, 1L, 1_000L,
                    SESSION + "-player-a-1", 25_000, CoopCreditTransfer.REASON_GIFT);

            transfer.onOutboundDiscarded(grant, "queue-cap");
            transfer.onOutboundDiscarded(grant, "shutdown");

            assertEquals(100_000L, engine.credits, "refunded once, not twice");
            assertEquals(1, refundLines(), engine.feed.toString());
        } finally {
            service.shutdown();
        }
    }

    /**
     * The boundary the whole design rests on: once the frame is on the socket the peer owns it, and a
     * refund here would mint a duplicate of a gift the partner already banked.
     */
    @Test
    void aGrantThatReachedTheSocketIsNotRefundedWhenTheTransportShutsDown() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> host.isConnected() && guest.isConnected(), "host and guest connected");

            CoopCreditTransfer transfer = transferOn(guest);
            transfer.send(25_000);
            waitUntil(() -> {
                guest.flushOutbound();
                return guest.outboundQueueDepth() == 0;
            }, "the guest wrote the grant to its socket");

            CoopMessages.Message delivered = waitForMessage(host);
            assertEquals(CoopMessages.Type.CREDITS_GRANT, delivered.type());

            guest.shutdown();

            assertEquals(75_000L, engine.credits,
                    "the partner has these credits; refunding them would create a second copy");
            assertEquals(0, refundLines(), engine.feed.toString());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private long refundLines() {
        return engine.feed.stream().filter(line -> line.contains("were returned")).count();
    }

    /**
     * Fills the queue with undroppable semantic events until the service gives up on the link. Sized
     * to trip the threshold on the <em>last</em> send, with the caller's one queued grant making up
     * the difference: the drop empties the queue, and anything sent after it starts refilling.
     *
     * <p>The grant is not coalescable, so nothing before this threshold can discard it, and
     * {@code dropOldestCoalescable} finds nothing to trim in a queue of pure semantic events.
     */
    private void floodPastTheDropThreshold(CoopNetService service) {
        for (int i = 0; i < CoopNetService.QUEUE_DROP_LINK_MESSAGES; i++) {
            service.send(CoopMessages.interactionClaim(SESSION, service.nextSeq(), 1_000L,
                    "entity-" + i, "Entity", "player-a"));
        }
    }

    private int reserveLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private CoopMessages.Message waitForMessage(CoopNetService service) throws InterruptedException {
        CoopMessages.Message[] holder = new CoopMessages.Message[1];
        waitUntil(() -> {
            holder[0] = service.pollInbound();
            return holder[0] != null;
        }, "an inbound message");
        return holder[0];
    }

    private static void waitUntil(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description);
    }

}
