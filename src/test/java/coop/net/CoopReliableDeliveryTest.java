package coop.net;

import coop.campaign.CoopCreditTransfer;
import coop.testing.FakeCreditEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.1.1 reliable delivery, at the transport seam: the acknowledge / replay / write-off API on
 * {@link CoopNetService}, and one end-to-end run over real loopback sockets.
 *
 * <p><b>The defect these pin.</b> A message written whole into a TCP socket that then died was
 * treated as delivered by everything in this transport, because "TCP guarantees it". TCP guarantees
 * it on <em>that socket</em>, and this transport replaces the socket on every reconnect — so a
 * MARKET_TXN, two CREDITS_GRANTs and a storage deposit were lost across one live link drop, with the
 * sender debited and the receiver never told. The queue survived the reconnect; the bytes already
 * handed to the kernel did not, and the resume handshake carried no acknowledgement information for
 * anything to notice.
 *
 * <p>The per-link bookkeeping is unit-tested in {@code CoopPeerLinkTest} and the receiver's dedup and
 * ack cadence in {@code CoopNetPumpTest}. What is here is the routing between them and the proof
 * that the replay survives a real socket replacement.
 */
class CoopReliableDeliveryTest {

    private static final String SESSION = "session-a";

    // ---- routing ---------------------------------------------------------------------------------

    @Test
    void anAcknowledgementIsRoutedToThePeerThatSentIt() {
        CoopNetService service = new CoopNetService(System::currentTimeMillis, 2);
        try {
            service.peerForTest(0).learnSenderId("guest-a");
            service.peerForTest(1).learnSenderId("guest-b");
            write(service.peerForTest(0), marketTxn(1L));
            write(service.peerForTest(1), marketTxn(2L));

            service.acknowledgeReliable("guest-a", List.of(1L, 2L));

            assertEquals(0, service.peerForTest(0).unackedCount());
            assertEquals(1, service.peerForTest(1).unackedCount(),
                    "seq 2 belongs to the other peer and its ack did not come from this one");
        } finally {
            service.shutdown();
        }
    }

    /**
     * The fallback {@link CoopNetService#sendTo} already relies on: before a peer has stamped a
     * message we have seen it cannot be named, and an unaddressed ack must still be honoured. It is
     * safe at any capacity because a seq a peer never sent is simply not in that peer's history.
     */
    @Test
    void anUnaddressedAcknowledgementReachesEveryPeer() {
        CoopNetService service = new CoopNetService(System::currentTimeMillis, 2);
        try {
            write(service.peerForTest(0), marketTxn(1L));
            write(service.peerForTest(1), marketTxn(1L));

            service.acknowledgeReliable(null, List.of(1L));

            assertEquals(0, service.peerForTest(0).unackedCount());
            assertEquals(0, service.peerForTest(1).unackedCount());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aReplayIsRoutedToThePeerThatIsResuming() {
        CoopNetService service = new CoopNetService(System::currentTimeMillis, 2);
        try {
            service.peerForTest(0).learnSenderId("guest-a");
            service.peerForTest(1).learnSenderId("guest-b");
            write(service.peerForTest(0), marketTxn(1L));
            write(service.peerForTest(0), marketTxn(2L));
            write(service.peerForTest(1), marketTxn(3L));

            Map<CoopMessages.Type, Integer> replayed = service.requeueUnackedReliable("guest-a");

            assertEquals(Map.of(CoopMessages.Type.MARKET_TXN, 2), replayed);
            assertEquals(2, service.peerForTest(0).outboundDepth());
            assertEquals(0, service.peerForTest(1).outboundDepth(),
                    "the other peer never dropped its link and owes nothing yet");
            assertEquals(1, service.peerForTest(1).unackedCount());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void anUnaddressedReplayCoversEveryPeerAndReportsTheTotal() {
        CoopNetService service = new CoopNetService(System::currentTimeMillis, 2);
        try {
            write(service.peerForTest(0), marketTxn(1L));
            write(service.peerForTest(1), grant(2L, "ledger-2"));

            Map<CoopMessages.Type, Integer> replayed = service.requeueUnackedReliable("nobody");

            assertEquals(2, CoopNetService.totalOf(replayed));
            assertEquals(0, service.unackedReliableCount());
            assertTrue(CoopNetService.describeTypeCounts(replayed).contains("MARKET_TXN×1"),
                    CoopNetService.describeTypeCounts(replayed));
        } finally {
            service.shutdown();
        }
    }

    /**
     * S4-I, 2026-09-14, at the seam the live failure actually crossed: the accept is sent through
     * {@code sendTo} with held session traffic already in the queue, and it still has to leave first.
     * Before the fix it was appended at the tail, the replay was inserted at index 0 because the
     * leading connection-scoped run was empty, and the guest's grace gate destroyed the replayed
     * grant one millisecond before the accept that would have opened the gate.
     */
    @Test
    void theResumeAcceptLeavesAheadOfTheReplayEvenThoughItIsSentLast() {
        CoopNetService service = new CoopNetService(System::currentTimeMillis, 1);
        try {
            CoopPeerLink peer = service.peerForTest(0);
            peer.learnSenderId("guest-a");
            write(peer, grant(1L, "ledger-1"));
            // Session traffic the outbound write gate refused for the length of the grace window.
            service.send(marketTxn(40L));
            service.sendTo("guest-a", CoopMessages.sessionResumeAccept(SESSION, 50L, 1_000L));

            service.requeueUnackedReliable("guest-a");

            assertEquals(List.of(50L, 1L, 40L),
                    peer.outbound().stream().map(CoopMessages.Message::seq).toList(),
                    "accept, then the replay, then the held traffic");
        } finally {
            service.shutdown();
        }
    }

    /**
     * The other half of S4-I: once the replay is written again it is owed again, and the peer's
     * acknowledgement is what finally clears it. Pre-fix the guest never applied the replay, so it
     * never acknowledged it and this entry stayed owed for the rest of the session.
     */
    @Test
    void aReplayedMessageStopsBeingOwedOnceThePeerAcknowledgesIt() {
        CoopNetService service = new CoopNetService(System::currentTimeMillis, 1);
        try {
            CoopPeerLink peer = service.peerForTest(0);
            peer.learnSenderId("guest-a");
            write(peer, grant(7L, "ledger-7"));

            service.requeueUnackedReliable("guest-a");
            assertEquals(0, peer.unackedCount(), "the replay is back on the queue, not in the history");

            // The flush writes it again, which is what puts it back in the history.
            write(peer, peer.outbound().removeFirst());
            assertEquals(1, peer.unackedCount());

            service.acknowledgeReliable("guest-a", List.of(7L));

            assertEquals(0, peer.unackedCount(), "the peer applied the replay and said so");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void anEmptyTallyRendersAsNothing() {
        assertEquals("", CoopNetService.describeTypeCounts(Map.of()));
        assertEquals(0, CoopNetService.totalOf(Map.of()));
    }

    // ---- session end -----------------------------------------------------------------------------

    /**
     * The session-end edge has to clear both halves. The queued grants were already covered
     * (credit red-team P1-1); the written-but-unacknowledged ones are the money the live smoke lost,
     * and they are refunded through the same listener.
     */
    @Test
    void endingTheSessionRefundsQueuedAndUnacknowledgedGrantsAlike() {
        FakeCreditEngine engine = new FakeCreditEngine(100_000L);
        CoopNetService service = new CoopNetService();
        try {
            CoopCreditTransfer transfer = transferOn(service, engine);
            // One that never reached a socket, and one that did.
            assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(25_000));
            assertEquals(CoopCreditTransfer.Result.SENT, transfer.send(10_000));
            CoopMessages.Message written = service.peerForTest(0).outbound().removeLast();
            write(service.peerForTest(0), written);
            assertEquals(65_000L, engine.credits);

            assertEquals(1, service.discardOutboundCreditsGrants());
            Map<CoopMessages.Type, Integer> lost = service.drainUnackedReliable("session-end");

            assertEquals(100_000L, engine.credits, "both grants came back");
            assertEquals(2, refundLines(engine), engine.feed.toString());
            assertEquals(Map.of(), lost, "a grant is refunded, not reported as an unexplained loss");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void theNonGrantRemainderIsReportedRatherThanRefunded() {
        CoopNetService service = new CoopNetService();
        try {
            write(service.peerForTest(0), marketTxn(1L));
            write(service.peerForTest(0), marketTxn(2L));
            write(service.peerForTest(0), worldDelta(3L));

            Map<CoopMessages.Type, Integer> lost = service.drainUnackedReliable("session-end");

            assertEquals(3, CoopNetService.totalOf(lost));
            assertEquals("MARKET_TXN×2, WORLD_DELTA×1", CoopNetService.describeTypeCounts(lost));
            assertEquals(0, service.unackedReliableCount());
        } finally {
            service.shutdown();
        }
    }

    // ---- end to end ------------------------------------------------------------------------------

    /**
     * The whole defect and the whole fix over real sockets: the guest writes a {@code MARKET_TXN},
     * the host's socket dies before it ever reads the frame, the guest reconnects to the same host,
     * and the replay delivers exactly one copy.
     *
     * <p>The host process keeps running throughout, which is the case that bit: it is the
     * <em>socket</em> that is replaced on a reconnect, and everything in this transport used to treat
     * "the kernel took the frame" as "the partner has it".
     *
     * <p>The receiver-side dedup that makes "exactly one" hold when the <em>ack</em> is what went
     * missing lives in the pump and is tested there; this covers the transport half, which is where
     * the bytes were being lost.
     */
    @Test
    void aTransactionWrittenIntoADyingSocketIsRedeliveredAfterTheReconnect() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> connectedBoth(host, guest), "host and guest connected");

            guest.send(CoopMessages.marketTxn(SESSION, guest.nextSeq(), 1_000L,
                    "market-a", "open_market", "buy", "fuel", 50, 12f, "player-a"));
            waitUntil(() -> {
                guest.flushOutbound();
                return guest.outboundQueueDepth() == 0 && guest.outboundIdle();
            }, "the guest handed the transaction to its socket");
            assertEquals(1, guest.unackedReliableCount(),
                    "written is not delivered: the transport still owes this one");

            // The connection dies with the frame still unread - a WAN outage from the guest's point
            // of view, and the shape of the four live losses.
            host.shutdown();
            waitUntil(() -> {
                guest.flushOutbound();
                return !guest.isConnected();
            }, "the guest noticed the link died");
            assertEquals(1, guest.unackedReliableCount(), "and the guest still owes it after the drop");

            CoopNetService returning = new CoopNetService();
            try {
                // The listener comes back on the same port and the guest's retry loop finds it: one
                // session, two sockets, which is the whole reason the old "TCP owns it" claim failed.
                returning.startHost(port);
                waitUntil(() -> {
                    returning.flushOutbound();
                    guest.flushOutbound();
                    return connectedBoth(returning, guest);
                }, "the guest reconnected");
                assertNull(returning.pollInbound(),
                        "the frame died with the first socket; nothing in TCP resends it");

                // What handleSessionResumeAccept does once the resume is real.
                assertEquals(Map.of(CoopMessages.Type.MARKET_TXN, 1),
                        guest.requeueUnackedReliable(null));

                CoopMessages.Message delivered = waitForMessage(returning, guest);
                assertEquals(CoopMessages.Type.MARKET_TXN, delivered.type());
                assertEquals(50, (int) CoopMessages.requiredPayloadLong(delivered, "qty"));

                // Exactly one: nothing re-queues it a second time, and the guest goes on owing it
                // until the peer's RELIABLE_ACK arrives.
                guest.flushOutbound();
                returning.flushOutbound();
                assertNull(returning.pollInbound());
                assertEquals(1, guest.unackedReliableCount());

                guest.acknowledgeReliable(delivered.senderId(), List.of(delivered.seq()));
                assertEquals(0, guest.unackedReliableCount());
            } finally {
                returning.shutdown();
            }
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * The pre-proof hold, which the replay has to clear or it never leaves the queue. A socket that
     * has just attached has proved nothing, and while a session token is set the flush holds every
     * message {@link CoopNetService#allowedBeforeProof} refuses — which includes every reliable type.
     * The resume path establishes the proof before the replay is queued
     * ({@code reconnect.resume()} sets the token, which marks the connection proven, and
     * {@code resendUnackedReliable} runs after it), and this pins both halves of that claim.
     */
    @Test
    void theResumedConnectionIsProvenBeforeTheReplayIsFlushed() throws Exception {
        assertFalse(CoopNetService.allowedBeforeProof(CoopMessages.Type.MARKET_TXN),
                "an unproven socket holds session traffic, replay included");
        assertFalse(CoopNetService.allowedBeforeProof(CoopMessages.Type.CREDITS_GRANT));

        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> connectedBoth(host, guest), "host and guest connected");
            assertFalse(host.peerForTest(0).proven(), "a fresh socket has proved nothing");

            // What ReconnectListener.onResumed does, and the reason the order in
            // handleSessionResumeRequest is accept, then resume, then replay.
            host.setExpectedSessionToken(CoopMessages.wireToken(SESSION));
            assertTrue(host.peerForTest(0).proven());

            host.send(marketTxn(1L));
            CoopMessages.Message delivered = waitForMessage(guest, host);
            assertEquals(CoopMessages.Type.MARKET_TXN, delivered.type());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Models the flush: park the encoded frame, then report that the kernel took all of it. */
    private static void write(CoopPeerLink peer, CoopMessages.Message message) {
        peer.setPendingWrite(java.nio.ByteBuffer.wrap(new byte[]{1}), message);
        peer.clearPendingWrite();
    }

    private static CoopMessages.Message marketTxn(long seq) {
        return CoopMessages.marketTxn(SESSION, seq, 1_000L, "market-a", "open_market", "buy", "fuel", 10, 5f,
                "player-a");
    }

    private static CoopMessages.Message worldDelta(long seq) {
        return new CoopMessages.Message(CoopMessages.Type.WORLD_DELTA, SESSION, seq, 1_000L, "{}");
    }

    private static CoopMessages.Message grant(long seq, String ledgerId) {
        return CoopMessages.creditsGrant(SESSION, seq, 1_000L, ledgerId, 1_000, "gift");
    }

    /** The production wiring with the campaign half swapped out; see {@code CoopCreditRefundTest}. */
    private CoopCreditTransfer transferOn(CoopNetService service, FakeCreditEngine engine) {
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

    private static long refundLines(FakeCreditEngine engine) {
        return engine.feed.stream().filter(line -> line.contains("were returned")).count();
    }

    /**
     * Deliberately not {@code &&}: {@code isConnected()} is also the poll, and short-circuiting it
     * means the guest never dials while the host is waiting for it to.
     */
    private static boolean connectedBoth(CoopNetService host, CoopNetService guest) {
        boolean hostUp = host.isConnected();
        boolean guestUp = guest.isConnected();
        return hostUp && guestUp;
    }

    private static int reserveLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static CoopMessages.Message waitForMessage(CoopNetService receiver, CoopNetService sender)
            throws InterruptedException {
        List<CoopMessages.Message> holder = new ArrayList<>(1);
        waitUntil(() -> {
            sender.flushOutbound();
            receiver.flushOutbound();
            CoopMessages.Message next = receiver.pollInbound();
            if (next == null) {
                return false;
            }
            holder.add(next);
            return true;
        }, "an inbound message");
        return holder.get(0);
    }

    private static void waitUntil(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description);
    }

    /** Guards the assumption the loopback test rests on: a fresh service owes nothing. */
    @Test
    void aFreshServiceOwesNothing() {
        CoopNetService service = new CoopNetService();
        try {
            assertEquals(0, service.unackedReliableCount());
            assertFalse(service.isConnected());
        } finally {
            service.shutdown();
        }
    }
}
