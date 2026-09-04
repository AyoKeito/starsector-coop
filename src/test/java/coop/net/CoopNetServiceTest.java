package coop.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNetServiceTest {
    /** Both peers derive the same datagram token from the session id; the tests do it by hand. */
    private static final String SESSION_ID = "session-a";
    private static final String TOKEN = CoopMessages.wireToken(SESSION_ID);
    private static final String FOREIGN_TOKEN = CoopMessages.wireToken("someone-elses-session");
    private static final String HOST_SENDER = CoopMessages.wireToken("host-player");
    private static final String GUEST_SENDER = CoopMessages.wireToken("guest-player");

    private static String snapshot(String senderId, long epoch, String body) {
        return CoopMessages.datagram(TOKEN, senderId, CoopMessages.Type.FLEET_SNAPSHOT, epoch, 0L, body);
    }

    @Test
    void hostAndGuestExchangePingPongOverLocalTcp() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L));
            guest.flushOutbound();
            CoopMessages.Message inboundPing = waitForMessage(host, "host inbound ping");
            assertEquals(CoopMessages.Type.PING, inboundPing.type());

            host.send(CoopMessages.pong(null, host.nextSeq(), 1100L, inboundPing.seq()));
            host.flushOutbound();
            CoopMessages.Message inboundPong = waitForMessage(guest, "guest inbound pong");
            assertEquals(CoopMessages.Type.PONG, inboundPong.type());
            assertEquals("{\"pingSeq\":1}", inboundPong.payloadJson());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void hostAndGuestExchangeFleetDatagramsOverLocalUdp() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);

            String guestSnapshot = snapshot(GUEST_SENDER, 1L, "guest-snapshot");
            guest.sendDatagram(guestSnapshot);
            guest.flushOutbound();

            assertEquals(guestSnapshot, waitForDatagram(host, "host inbound UDP fleet snapshot", guest));

            // The host can only stream back once the guest's address has passed its path challenge.
            validateUdpPath(host, guest);
            String hostSnapshot = snapshot(HOST_SENDER, 1L, "host-snapshot");
            host.sendDatagram(hostSnapshot);
            host.flushOutbound();

            assertEquals(hostSnapshot, waitForDatagram(guest, "guest inbound UDP fleet snapshot", host));
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void guestRetriesUntilLateHostStartsListening() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            guest.connect("127.0.0.1", port);
            Thread.sleep(300L);

            host.startHost(port);

            waitUntil(() -> bothConnected(host, guest), "guest retry connected to late host");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void hostSendsLobbyRejectToExtraTcpGuest() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        CoopNetService extraGuest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "first guest connected");

            extraGuest.connect("127.0.0.1", port);
            CoopMessages.Message reject = waitForMessageWhilePollingHost(host, extraGuest, "extra guest lobby reject");

            assertEquals(CoopMessages.Type.LOBBY_REJECT, reject.type());
            assertEquals("{\"reason\":\"Host already has an active connection\"}", reject.payloadJson());
            assertTrue(bothConnected(host, guest), "first guest remains connected");
        } finally {
            extraGuest.shutdown();
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- Phase 20.2: half-open replacement and deliberate drops ----------------------------------

    @Test
    void anExtraConnectionIsStillRejectedWhileTheHeldOneIsFresh() throws Exception {
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        CoopNetService guest = new CoopNetService();
        CoopNetService extraGuest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "first guest connected");

            // One millisecond short of the half-open threshold: still an ordinary extra connection.
            clock.addAndGet(CoopNetService.HALF_OPEN_REPLACE_MILLIS - 1L);
            extraGuest.connect("127.0.0.1", port);
            CoopMessages.Message reject =
                    waitForMessageWhilePollingHost(host, extraGuest, "extra guest lobby reject");

            assertEquals(CoopMessages.Type.LOBBY_REJECT, reject.type());
            assertTrue(bothConnected(host, guest), "the held connection is untouched");
        } finally {
            extraGuest.shutdown();
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * The stranded-socket case: after a NAT drop the host's channel is not closed, it is half-open,
     * and the OS will not say so for another minute or two. Without the replacement rule the guest's
     * reconnect knocks every 500 ms for that whole window and is turned away every time.
     */
    @Test
    void aHalfOpenConnectionIsReplacedByTheReconnectingGuest() throws Exception {
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        CoopNetService stranded = new CoopNetService();
        CoopNetService returning = new CoopNetService();
        try {
            host.startHost(port);
            stranded.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, stranded), "first guest connected");

            // No inbound bytes for the whole threshold: the held channel is presumed dead.
            clock.addAndGet(CoopNetService.HALF_OPEN_REPLACE_MILLIS);
            returning.connect("127.0.0.1", port);
            returning.send(CoopMessages.ping(null, returning.nextSeq(), 1000L));

            AtomicReference<CoopMessages.Message> adopted = new AtomicReference<>();
            waitUntil(() -> {
                host.flushOutbound();
                returning.flushOutbound();
                adopted.set(host.pollInbound());
                return adopted.get() != null;
            }, "host adopted the reconnecting guest");

            assertEquals(CoopMessages.Type.PING, adopted.get().type());
            assertNull(returning.pollInbound(), "the reconnecting guest must not be lobby-rejected");
            waitUntil(() -> {
                stranded.flushOutbound();
                return !stranded.isConnected();
            }, "the replaced connection was closed");
        } finally {
            returning.shutdown();
            stranded.shutdown();
            host.shutdown();
        }
    }

    @Test
    void droppingTheActiveConnectionTakesTheOrdinaryDisconnectPathAndTheGuestRetries() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            assertTrue(host.dropActiveConnection("link death: tcpSilence=15000 ms"));
            assertFalse(host.isConnected(), "the drop is synchronous on the side that ran it");
            assertFalse(host.dropActiveConnection("again"), "there is nothing left to drop");

            // The host is still listening and the guest still retries every 500 ms, which is the whole
            // point of closing rather than shutting down.
            waitUntil(() -> {
                host.flushOutbound();
                guest.flushOutbound();
                return bothConnected(host, guest);
            }, "the guest reconnected on its own retry");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void droppingWithNoActiveConnectionIsANoOp() {
        CoopNetService service = new CoopNetService();

        assertFalse(service.dropActiveConnection("nothing to drop"));
    }

    // ---- poll consolidation (perf audit #10) -----------------------------------------------------

    /**
     * The drain no longer polls the network per message — it empties the queue first and only polls
     * when it runs dry. Every queued message must still come out, in order.
     */
    @Test
    void pollInboundDrainsTheWholeBacklogAfterOnePoll() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            for (int i = 0; i < 5; i++) {
                guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L + i));
            }
            guest.flushOutbound();

            List<CoopMessages.Message> drained = new ArrayList<>();
            waitUntil(() -> {
                CoopMessages.Message message;
                while ((message = host.pollInbound()) != null) {
                    drained.add(message);
                }
                return drained.size() == 5;
            }, "host drained all five pings");

            for (int i = 0; i < 5; i++) {
                assertEquals(CoopMessages.Type.PING, drained.get(i).type());
                assertEquals(i + 1L, drained.get(i).seq(), "backlog came out of order");
            }
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /** Same contract for the UDP queue. */
    @Test
    void pollDatagramDrainsTheWholeBacklogAfterOnePoll() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);

            for (int i = 0; i < 4; i++) {
                guest.sendDatagram(snapshot(GUEST_SENDER, 1L + i, "snapshot-" + i));
            }
            guest.flushOutbound();

            List<String> drained = new ArrayList<>();
            waitUntil(() -> {
                String payload;
                while ((payload = host.pollDatagram()) != null) {
                    drained.add(payload);
                }
                return drained.size() == 4;
            }, "host drained all four datagrams");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * The cached flag must follow the channel, not lag it: the poll that discovers the peer is gone is
     * the one that has to clear it, because the pump's {@code detectPeerDisconnect} reads the flag
     * immediately after that poll and nothing else re-derives it.
     */
    @Test
    void cachedConnectedFlagClearsOnThePollThatSeesThePeerLeave() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");
            assertTrue(host.isConnected(), "host should be connected before the guest leaves");

            guest.shutdown();

            waitUntil(() -> {
                host.flushOutbound(); // the pump's frame-head poll
                return !host.isConnected();
            }, "host observed the disconnect through its frame poll");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /** Local shutdown clears the flag with no poll at all. */
    @Test
    void shutdownClearsTheCachedConnectedFlagImmediately() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            guest.shutdown();
            assertFalse(guest.isConnected(), "shutdown must clear the connected flag on the spot");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    private int reserveLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private CoopMessages.Message waitForMessage(CoopNetService service, String description) throws InterruptedException {
        AtomicReference<CoopMessages.Message> message = new AtomicReference<>();
        waitUntil(() -> {
            message.set(service.pollInbound());
            return message.get() != null;
        }, description);
        return message.get();
    }

    /**
     * Polls {@code service} for a datagram while pumping every other service given — the frame loop
     * the game actually runs. Pumping matters now: a path challenge is only answered by a peer whose
     * {@code flushOutbound} runs.
     */
    private String waitForDatagram(CoopNetService service, String description, CoopNetService... pump)
            throws InterruptedException {
        AtomicReference<String> datagram = new AtomicReference<>();
        waitUntil(() -> {
            for (CoopNetService other : pump) {
                other.flushOutbound();
            }
            service.flushOutbound();
            datagram.set(service.pollDatagram());
            return datagram.get() != null;
        }, description);
        return datagram.get();
    }

    /** Connects the pair and hands both ends the session token the handshake would have given them. */
    private void startSession(CoopNetService host, CoopNetService guest, int port)
            throws InterruptedException {
        host.startHost(port);
        guest.connect("127.0.0.1", port);
        waitUntil(() -> bothConnected(host, guest), "host and guest connected");
        host.setExpectedSessionToken(TOKEN);
        guest.setExpectedSessionToken(TOKEN);
    }

    /** Pumps both ends until the host has validated a UDP return address. */
    private void validateUdpPath(CoopNetService host, CoopNetService guest) throws InterruptedException {
        waitUntil(() -> {
            guest.sendDatagram(snapshot(GUEST_SENDER, 1L, "keepalive-ish"));
            guest.flushOutbound();
            host.flushOutbound();
            guest.flushOutbound();
            host.flushOutbound();
            while (host.pollDatagram() != null) {
                // drain the priming traffic so it cannot be mistaken for a test payload
            }
            return !host.datagramStats().validatedRemote().isEmpty();
        }, "host validated the guest UDP return address");
    }

    private void send(DatagramSocket socket, int port, String payload) throws IOException {
        byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }

    private CoopMessages.Message waitForMessageWhilePollingHost(CoopNetService host, CoopNetService guest,
                                                                String description) throws InterruptedException {
        AtomicReference<CoopMessages.Message> message = new AtomicReference<>();
        waitUntil(() -> {
            // The host's accept only happens inside a poll, and since perf audit #10 isConnected()
            // stops polling once it is connected. flushOutbound() is the poll the pump runs at the
            // head of every frame, so driving the host with it is what the game actually does.
            host.flushOutbound();
            message.set(guest.pollInbound());
            return message.get() != null;
        }, description);
        return message.get();
    }

    private void waitUntil(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description);
    }

    private boolean bothConnected(CoopNetService host, CoopNetService guest) {
        boolean hostConnected = host.isConnected();
        boolean guestConnected = guest.isConnected();
        return hostConnected && guestConnected;
    }

    // ---- F4: what a lobby reject does to the guest connect loop ----------------------------------

    /** Drives a poll (the connected flag is cached, so asking alone never discovers a close). */
    private boolean sawDrop(CoopNetService guest) {
        guest.pollInbound();
        return !guest.isConnected();
    }

    @Test
    void aPlainDropRetriesFastButALobbyRejectBacksOffToFiveSeconds() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);

            long droppedAt = System.currentTimeMillis();
            host.dropActiveConnection("plain drop, no reject");
            waitUntil(() -> sawDrop(guest), "the guest saw the plain drop");
            long fastRetryIn = guest.nextConnectAttemptAtMillisForTest() - droppedAt;
            assertTrue(fastRetryIn <= 1_500L,
                    "a plain drop keeps the 500 ms retry the reconnect grace rides on, was " + fastRetryIn);

            waitUntil(() -> bothConnected(host, guest), "the guest reconnected");

            guest.noteLobbyRejected();
            long rejectedAt = System.currentTimeMillis();
            host.dropActiveConnection("closing behind a LOBBY_REJECT");
            waitUntil(() -> sawDrop(guest), "the guest saw the post-reject drop");
            long slowRetryIn = guest.nextConnectAttemptAtMillisForTest() - rejectedAt;
            assertTrue(slowRetryIn >= 4_000L,
                    "a reject must back the loop off to 5 s, was " + slowRetryIn);
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void stopReconnectingEndsTheGuestConnectLoopForGood() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);

            guest.stopReconnecting("password rejected");
            assertTrue(guest.reconnectStopped());
            assertTrue(sawDrop(guest), "stopping closes the socket it was holding");
            // The role survives so the HUD can still say GUEST and explain itself.
            assertEquals(CoopConnectionRole.GUEST, guest.role());

            // Well past the 500 ms the loop used to retry on, with the host still listening.
            Thread.sleep(900L);
            assertTrue(sawDrop(guest), "a terminal reject must not reconnect");
            assertTrue(sawDrop(host), "and must not take the host's slot again either");

            // Only an explicit connect() re-arms it.
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "an explicit connect re-arms the loop");
            assertFalse(guest.reconnectStopped());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- Phase 12b -------------------------------------------------------------------------------

    @Test
    void restartInTheSameProcessDoesNotReplayStaleTcpMessages() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            // Queue traffic in both directions, then tear the guest down without draining it.
            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L));
            guest.flushOutbound();
            waitForMessage(host, "host inbound ping");
            host.send(CoopMessages.pong(null, host.nextSeq(), 1100L, 1L));
            host.flushOutbound();
            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1200L));

            guest.shutdown();

            // A fresh session in the same process must not inherit the previous one's queues.
            assertNull(guest.pollInbound(), "stale TCP inbound survived shutdown");

            guest.connect("127.0.0.1", port);
            waitUntil(guest::isConnected, "guest reconnected");
            assertNull(guest.pollInbound(), "stale TCP inbound replayed into the fresh connection");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void hostIgnoresDatagramsFromANonPeerAddressAndKeepsStreamingToThePeer() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        DatagramSocket intruder = new DatagramSocket();
        try {
            startSession(host, guest, port);

            // Establish and validate the legitimate return address first.
            String fromGuest = snapshot(GUEST_SENDER, 1L, "guest-snapshot");
            guest.sendDatagram(fromGuest);
            guest.flushOutbound();
            assertEquals(fromGuest, waitForDatagram(host, "host inbound UDP from guest", guest));
            validateUdpPath(host, guest);
            String validated = host.datagramStats().validatedRemote();

            // A stray packet from another local socket: same address (loopback gives everyone
            // 127.0.0.1), different port, and a session token from somewhere else entirely. It must
            // not surface as traffic and must not become a candidate for the return address.
            send(intruder, port, CoopMessages.datagram(FOREIGN_TOKEN, GUEST_SENDER,
                    CoopMessages.Type.FLEET_SNAPSHOT, 9L, 0L, "intruder"));

            waitUntil(() -> {
                host.flushOutbound();
                return host.datagramStats().droppedTokenMismatch() > 0;
            }, "host counted the foreign-token datagram");
            assertNull(host.pollDatagram(), "intruder datagram must not be delivered to the drain");
            assertEquals(validated, host.datagramStats().validatedRemote(),
                    "a foreign token must never re-point the return address");

            // And the host still streams to the real guest, not to whoever spoke last.
            String toGuest = snapshot(HOST_SENDER, 2L, "host-snapshot");
            host.sendDatagram(toGuest);
            host.flushOutbound();
            assertEquals(toGuest, waitForDatagram(guest, "guest inbound UDP after intruder packet", host));
        } finally {
            intruder.close();
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void oversizedDatagramIsDiscardedRatherThanDecodedTruncated() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);

            // Teach the host the guest's return address with a normal datagram.
            String small = snapshot(GUEST_SENDER, 1L, "small");
            guest.sendDatagram(small);
            guest.flushOutbound();
            assertEquals(small, waitForDatagram(host, "host inbound UDP priming", guest));

            // Fill the receive buffer exactly: the service cannot tell a full buffer from a
            // truncated payload, so it must discard rather than decode a partial record.
            byte[] huge = new byte[64 * 1024];
            java.util.Arrays.fill(huge, (byte) 'x');
            try (DatagramSocket sender = new DatagramSocket()) {
                sender.send(new DatagramPacket(huge, huge.length,
                        InetAddress.getByName("127.0.0.1"), port));
            } catch (IOException ignored) {
                // Some stacks refuse a 64 KB datagram outright; the guard is still what matters.
            }

            // A subsequent valid datagram still gets through, proving the discard did not wedge
            // the receive loop.
            String after = snapshot(GUEST_SENDER, 2L, "after-truncation");
            guest.sendDatagram(after);
            guest.flushOutbound();
            assertEquals(after, waitForDatagram(host, "host inbound UDP after oversized datagram", guest));
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- Phase 20.1: session token, path validation, keepalive, ICMP ----------------------------

    @Test
    void datagramsAreDroppedAndCountedWhileNoSessionTokenExists() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");
            // Deliberately no setExpectedSessionToken: this is the pre-handshake window, where
            // nothing legitimate is streaming yet and accepting anything is the hole the token closes.

            guest.sendDatagram(snapshot(GUEST_SENDER, 1L, "too-early"));
            guest.flushOutbound();

            waitUntil(() -> {
                host.flushOutbound();
                return host.datagramStats().droppedNoToken() > 0;
            }, "host counted the pre-session datagram");
            assertNull(host.pollDatagram(), "a datagram before the session token must not be delivered");
            assertEquals("", host.datagramStats().validatedRemote(),
                    "nothing may be learned from a datagram that failed the token check");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * The QUIC PATH_CHALLENGE model (RFC 9000 §8.2). A valid session token proves the sender knows the
     * session — it is sniffable on-path and replayable from anywhere, so it cannot also be allowed to
     * prove where the stream should go. The new source's payload is still accepted (the watermark
     * defeats replay); only the send target waits for the echo.
     */
    @Test
    void validTokenFromANewPortRePointsOnlyAfterThePathChallengeEcho() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        DatagramSocket rebound = new DatagramSocket();
        try {
            rebound.setSoTimeout(200);
            startSession(host, guest, port);
            validateUdpPath(host, guest);
            String originalTarget = host.datagramStats().validatedRemote();
            long validationsBefore = host.datagramStats().pathValidations();

            // Same address, new port, valid token — a NAT rebind looks exactly like this, and so does
            // an attacker who sniffed the token.
            send(rebound, port, snapshot(GUEST_SENDER, 5L, "from-new-port"));

            String challenge = awaitDatagram(rebound, host, "host challenged the new source");
            CoopMessages.Datagram probe = CoopMessages.parseDatagram(challenge);
            assertEquals(CoopMessages.Type.PATH_PROBE, probe.type());
            String body = probe.sections().get(0).body();
            assertTrue(body.startsWith("C:"), "challenge body should carry a nonce: " + body);
            assertEquals(TOKEN, probe.token(), "the challenge itself must carry the session token");

            assertEquals(originalTarget, host.datagramStats().validatedRemote(),
                    "an unvalidated source must not re-point the stream");
            String stillToGuest = snapshot(HOST_SENDER, 6L, "still-to-the-old-address");
            host.sendDatagram(stillToGuest);
            host.flushOutbound();
            assertEquals(stillToGuest,
                    waitForDatagram(guest, "guest still receives while the new path is unproven", host));

            // The payload from the unproven source is still delivered; only the target waits.
            assertTrue(drainDatagrams(host).contains(snapshot(GUEST_SENDER, 5L, "from-new-port")),
                    "an unproven source's payload is accepted inbound, it just cannot re-point");

            send(rebound, port, CoopMessages.datagram(TOKEN, GUEST_SENDER,
                    CoopMessages.Type.PATH_PROBE, 0L, 0L, "R:" + body.substring(2)));

            waitUntil(() -> {
                host.flushOutbound();
                return !originalTarget.equals(host.datagramStats().validatedRemote());
            }, "host re-pointed after the echo");
            assertEquals(validationsBefore + 1L, host.datagramStats().pathValidations());
        } finally {
            rebound.close();
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void pathChallengeEchoWithTheWrongNonceIsRejected() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        DatagramSocket rebound = new DatagramSocket();
        try {
            rebound.setSoTimeout(200);
            startSession(host, guest, port);
            validateUdpPath(host, guest);
            String originalTarget = host.datagramStats().validatedRemote();
            long validationsBefore = host.datagramStats().pathValidations();

            send(rebound, port, snapshot(GUEST_SENDER, 5L, "from-new-port"));
            awaitDatagram(rebound, host, "host challenged the new source");

            send(rebound, port, CoopMessages.datagram(TOKEN, GUEST_SENDER,
                    CoopMessages.Type.PATH_PROBE, 0L, 0L, "R:0000000000000000"));

            waitUntil(() -> {
                host.flushOutbound();
                return host.datagramStats().probeEchoesReceived() > 0;
            }, "host processed the bogus echo");
            assertEquals(originalTarget, host.datagramStats().validatedRemote(),
                    "a guessed nonce must not re-point the stream");
            assertEquals(validationsBefore, host.datagramStats().pathValidations());
        } finally {
            rebound.close();
            guest.shutdown();
            host.shutdown();
        }
    }

    /** The guest is what makes the host's validation possible: it answers challenges unprompted. */
    @Test
    void guestAnswersAPathChallengeWithTheSameNonce() throws Exception {
        int port = reserveLocalPort();
        CoopNetService guest = new CoopNetService();
        DatagramSocket pretendHost = new DatagramSocket(port);
        try {
            pretendHost.setSoTimeout(200);
            guest.connect("127.0.0.1", port);
            guest.setExpectedSessionToken(TOKEN);

            // The guest speaks first, which is how its address becomes known at all.
            guest.sendDatagram(snapshot(GUEST_SENDER, 1L, "hello"));
            guest.flushOutbound();
            DatagramPacket first = receivePacket(pretendHost, guest, "host-side saw the guest datagram");

            byte[] challenge = CoopMessages.datagram(TOKEN, HOST_SENDER,
                            CoopMessages.Type.PATH_PROBE, 0L, 0L, "C:abcdef0123456789")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            pretendHost.send(new DatagramPacket(challenge, challenge.length,
                    first.getAddress(), first.getPort()));

            String echo = awaitDatagram(pretendHost, guest, "guest echoed the challenge");
            CoopMessages.Datagram decoded = CoopMessages.parseDatagram(echo);
            assertEquals(CoopMessages.Type.PATH_PROBE, decoded.type());
            assertEquals("R:abcdef0123456789", decoded.sections().get(0).body());
            assertNull(guest.pollDatagram(), "a PATH_PROBE is transport traffic, never gameplay traffic");
        } finally {
            pretendHost.close();
            guest.shutdown();
        }
    }

    @Test
    void keepaliveFiresAfterFiveIdleSecondsAndNotBefore() throws Exception {
        int port = reserveLocalPort();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(10_000L);
        CoopNetService guest = new CoopNetService(now::get);
        DatagramSocket pretendHost = new DatagramSocket(port);
        try {
            guest.connect("127.0.0.1", port);
            guest.setExpectedSessionToken(TOKEN);

            guest.flushOutbound();
            assertEquals(0L, guest.datagramStats().keepalivesSent(), "no keepalive on a fresh link");

            now.addAndGet(CoopNetService.KEEPALIVE_IDLE_MILLIS - 1);
            guest.flushOutbound();
            assertEquals(0L, guest.datagramStats().keepalivesSent(), "keepalive fired a millisecond early");

            now.addAndGet(1);
            guest.flushOutbound();
            assertEquals(1L, guest.datagramStats().keepalivesSent());

            // Sending it restarts the idle timer; the cadence is idle-gap, not a metronome.
            guest.flushOutbound();
            assertEquals(1L, guest.datagramStats().keepalivesSent());
            now.addAndGet(CoopNetService.KEEPALIVE_IDLE_MILLIS);
            guest.flushOutbound();
            assertEquals(2L, guest.datagramStats().keepalivesSent());

            pretendHost.setSoTimeout(500);
            DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
            pretendHost.receive(packet);
            CoopMessages.DatagramHeader header = CoopMessages.parseDatagramHeader(
                    new String(packet.getData(), 0, packet.getLength(),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(CoopMessages.Type.UDP_PROBE, header.type());
            assertEquals(TOKEN, header.token());
        } finally {
            pretendHost.close();
            guest.shutdown();
        }
    }

    @Test
    void receivedKeepalivesAreCountedAndNeverReachTheDrain() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);
            guest.sendDatagram(CoopMessages.datagram(TOKEN, GUEST_SENDER,
                    CoopMessages.Type.UDP_PROBE, 0L, 0L, ""));
            guest.flushOutbound();

            waitUntil(() -> {
                host.flushOutbound();
                return host.datagramStats().keepalivesReceived() > 0;
            }, "host counted the keepalive");
            assertNull(host.pollDatagram(), "a UDP_PROBE is transport traffic, never gameplay traffic");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * Loopback will not reliably produce an ICMP rejection on demand, so the classification itself is
     * what gets pinned: an ICMP port-unreachable arrives as {@code PortUnreachableException} where the
     * JDK documents it and as a plain {@code SocketException} on Windows (JDK-4676710), and neither
     * may ever be treated as a reason to close the channel.
     */
    @Test
    void icmpClassIsTransientAndOtherFailuresAreNot() {
        assertTrue(CoopNetService.isTransientLinkException(new java.net.PortUnreachableException()));
        assertTrue(CoopNetService.isTransientLinkException(new java.net.SocketException("ICMP")));
        assertFalse(CoopNetService.isTransientLinkException(new IllegalStateException("bug")));
        assertFalse(CoopNetService.isTransientLinkException(new OutOfMemoryError("not link weather")));
    }

    // ---- Phase 20.5: TCP senderId stamping ------------------------------------------------------

    @Test
    void sendStampsTheLocalSenderIdOnUnstampedMessages() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");
            guest.setLocalSenderId("guest-player-uuid");

            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L));
            guest.flushOutbound();

            assertEquals("guest-player-uuid",
                    waitForMessage(host, "host inbound ping").senderId());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    @Test
    void sendLeavesAnUnsetSenderIdNullAndKeepsAnExplicitOne() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            // No local sender id yet (pre-lobby): the field stays null rather than becoming "null".
            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L));
            guest.flushOutbound();
            assertNull(waitForMessage(host, "host inbound unstamped ping").senderId());

            // An already-stamped message is left alone — the relay case Phase 27 will need.
            guest.setLocalSenderId("guest-player-uuid");
            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1100L).withSenderId("someone-else"));
            guest.flushOutbound();
            assertEquals("someone-else", waitForMessage(host, "host inbound relayed ping").senderId());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- helpers for the Phase 20.1 tests --------------------------------------------------------

    /** Pumps {@code service} until {@code socket} has a datagram, or fails the test. */
    private String awaitDatagram(DatagramSocket socket, CoopNetService service, String description)
            throws Exception {
        DatagramPacket packet = receivePacket(socket, service, description);
        return new String(packet.getData(), packet.getOffset(), packet.getLength(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private DatagramPacket receivePacket(DatagramSocket socket, CoopNetService service,
                                         String description) throws Exception {
        socket.setSoTimeout(100);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            service.flushOutbound();
            DatagramPacket packet = new DatagramPacket(new byte[64 * 1024], 64 * 1024);
            try {
                socket.receive(packet);
                return packet;
            } catch (java.net.SocketTimeoutException ignored) {
                // keep pumping
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    // ---- Phase 20.1 M2: outbound TCP backpressure ------------------------------------------------
    //
    // No channel is attached in these tests, so flushOutbound cannot drain: the queue grows exactly
    // as it would against a stalled peer socket, which is the only state coalescing is allowed in.

    @Test
    void anIdleQueueNeverCoalescesSoLocalhostTrafficIsUnchanged() {
        CoopNetService service = new CoopNetService();
        try {
            for (int i = 0; i < CoopNetService.COALESCE_BACKLOG_MESSAGES - 1; i++) {
                service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), false, false,
                        1000L + i, 1L, 1000L + i, ""));
            }

            assertEquals(CoopNetService.COALESCE_BACKLOG_MESSAGES - 1, service.outboundQueueDepth(),
                    "below the backlog threshold every message is queued as sent");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aBackloggedQueueKeepsOnlyTheNewestOfEachSupersededSnapshot() {
        CoopNetService service = new CoopNetService();
        try {
            for (int i = 0; i < CoopNetService.COALESCE_BACKLOG_MESSAGES; i++) {
                service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), false, false,
                        1000L + i, 1L, 1000L + i, ""));
            }
            int depthWhenBacklogged = service.outboundQueueDepth();

            for (int i = 0; i < 50; i++) {
                service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), true, false,
                        9000L + i, 9L, 9000L + i, ""));
            }

            assertEquals(depthWhenBacklogged, service.outboundQueueDepth(),
                    "50 superseded snapshots must replace one queued snapshot, not grow the queue");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aBackloggedQueueNeverCoalescesSemanticEvents() {
        CoopNetService service = new CoopNetService();
        try {
            for (int i = 0; i < CoopNetService.COALESCE_BACKLOG_MESSAGES; i++) {
                service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), false, false,
                        1000L + i, 1L, 1000L + i, ""));
            }
            int depthWhenBacklogged = service.outboundQueueDepth();

            for (int i = 0; i < 10; i++) {
                service.send(CoopMessages.interactionClaim(SESSION_ID, service.nextSeq(), 2000L,
                        "entity-" + i, "Entity", "player-a"));
            }

            assertEquals(depthWhenBacklogged + 10, service.outboundQueueDepth(),
                    "claims are events: every one of them must still be delivered");
        } finally {
            service.shutdown();
        }
    }

    /**
     * One TCP-fallback stream carries several independent state streams. Keying only on the message
     * type would let a motion datagram supersede a fleet snapshot and silently censor it.
     */
    @Test
    void backloggedStateDatagramsCoalescePerWrappedStreamNotAcrossThem() {
        CoopNetService service = new CoopNetService();
        try {
            for (int i = 0; i < CoopNetService.COALESCE_BACKLOG_MESSAGES; i++) {
                service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), false, false,
                        1000L + i, 1L, 1000L + i, ""));
            }
            int depthWhenBacklogged = service.outboundQueueDepth();

            service.send(CoopMessages.stateDatagram(SESSION_ID, service.nextSeq(), 2000L,
                    snapshot(HOST_SENDER, 1L, "body-1")));
            service.send(CoopMessages.stateDatagram(SESSION_ID, service.nextSeq(), 2100L,
                    CoopMessages.datagram(TOKEN, HOST_SENDER, CoopMessages.Type.NPC_FLEET_MOTION,
                            2L, 0L, "motion-1")));
            service.send(CoopMessages.stateDatagram(SESSION_ID, service.nextSeq(), 2200L,
                    snapshot(GUEST_SENDER, 3L, "body-guest")));
            assertEquals(depthWhenBacklogged + 3, service.outboundQueueDepth(),
                    "three distinct (type, sender) streams are three queue entries");

            service.send(CoopMessages.stateDatagram(SESSION_ID, service.nextSeq(), 2300L,
                    snapshot(HOST_SENDER, 4L, "body-2")));
            assertEquals(depthWhenBacklogged + 3, service.outboundQueueDepth(),
                    "a newer sample of the same stream replaces the queued one");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void coalescingKeepsTheNewestPayloadInTheOldestSlot() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            // Queue past the threshold before the first flush, so the coalescing path is exercised
            // and the survivor is then actually written to the socket.
            for (int i = 0; i < CoopNetService.COALESCE_BACKLOG_MESSAGES; i++) {
                host.send(CoopMessages.interactionClaim(SESSION_ID, host.nextSeq(), 1000L,
                        "entity-" + i, "Entity", "host-player"));
            }
            host.send(CoopMessages.timeSnapshot(SESSION_ID, host.nextSeq(), false, false,
                    1L, 1L, 1L, ""));
            host.send(CoopMessages.timeSnapshot(SESSION_ID, host.nextSeq(), true, false,
                    777L, 7L, 777L, "host"));
            host.flushOutbound();

            List<CoopMessages.Message> received = new ArrayList<>();
            waitUntil(() -> {
                CoopMessages.Message message;
                while ((message = guest.pollInbound()) != null) {
                    received.add(message);
                }
                return received.stream().anyMatch(m -> m.type() == CoopMessages.Type.TIME_SNAPSHOT);
            }, "guest received the coalesced snapshot");

            List<CoopMessages.Message> snapshots = received.stream()
                    .filter(m -> m.type() == CoopMessages.Type.TIME_SNAPSHOT)
                    .toList();
            assertEquals(1, snapshots.size(), "only the newest snapshot survives");
            assertEquals(777L, CoopMessages.requiredPayloadLong(snapshots.get(0), "timestampMillis"));
            assertEquals(CoopNetService.COALESCE_BACKLOG_MESSAGES,
                    received.stream().filter(m -> m.type() == CoopMessages.Type.INTERACTION_CLAIM).count(),
                    "no event was dropped on the way");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    private List<String> drainDatagrams(CoopNetService service) {
        List<String> drained = new ArrayList<>();
        String payload;
        while ((payload = service.pollDatagram()) != null) {
            drained.add(payload);
        }
        return drained;
    }

    // ---- Phase 20.4: connection throttle, garbage strikes, per-poll ceilings ---------------------

    /**
     * The reject path writes a frame on a <em>blocking</em> socket, which is the work a connection
     * flood is really buying. Past the limit the host stops paying for it: no frame, just a close.
     */
    @Test
    void aFloodOfConnectionsFromOneAddressIsClosedSilentlyThenAllowedAgainAfterTheCooldown()
            throws Exception {
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        try {
            // Updated for red-team A3 and its known-peer exemption. This used to park a real guest on
            // the slot so every attempt met the reject path, and that shape cannot survive the
            // exemption: on loopback the guest's address IS the flooder's address, so a session peer
            // here would make the whole flood exempt. It also no longer needs one — the throttle now
            // gates the accept itself, so a free slot is the honest place to measure it, and the
            // absence of a session is what keeps a flooder from ever becoming a remembered peer.
            host.startHost(port);
            for (int attempt = 1; attempt <= CoopNetService.MAX_CONNECTION_ATTEMPTS_PER_WINDOW; attempt++) {
                assertTrue(connectAndDisconnect(host, port),
                        "attempt " + attempt + " is inside the limit and must be admitted");
            }
            assertEquals(0L, host.datagramStats().connectionsThrottled());

            assertEquals("", knock(host, port),
                    "the attempt past the limit must be closed with no reply at all");
            assertEquals(1L, host.datagramStats().connectionsThrottled());
            assertEquals(CoopNetService.MAX_CONNECTION_ATTEMPTS_PER_WINDOW + 1L,
                    host.datagramStats().connectionAttempts());

            // An address that keeps knocking through its cooldown must not be able to extend it.
            for (int i = 0; i < 5; i++) {
                clock.addAndGet(CoopNetService.CONNECTION_THROTTLE_COOLDOWN_MILLIS / 10L);
                assertEquals("", knock(host, port), "still inside the cooldown");
            }

            // Still throttled a millisecond short of the cooldown.
            clock.addAndGet(CoopNetService.CONNECTION_THROTTLE_COOLDOWN_MILLIS / 2L - 1L);
            assertEquals("", knock(host, port), "the cooldown ended a millisecond early");

            clock.addAndGet(2L);
            assertTrue(connectAndDisconnect(host, port),
                    "past the cooldown the address is served normally again");
        } finally {
            host.shutdown();
        }
    }

    /**
     * The frame decoder is deliberately tolerant so a corrupted stream can resynchronise. That
     * tolerance is only safe once the peer has proved it belongs to the session; before then, a
     * stranger feeding garbage is just a stranger.
     */
    @Test
    void aPreSessionConnectionFeedingGarbageIsDropped() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        try {
            host.startHost(port);
            // Deliberately no setExpectedSessionToken: this connection never completes a handshake.
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
                waitUntil(() -> {
                    host.flushOutbound();
                    return host.isConnected();
                }, "host adopted the raw connection");

                for (int i = 0; i < CoopNetService.PRE_SESSION_INVALID_FRAME_LIMIT; i++) {
                    socket.getOutputStream().write(("not-a-frame-" + i + "\n").getBytes());
                }
                socket.getOutputStream().flush();

                waitUntil(() -> {
                    host.flushOutbound();
                    return host.datagramStats().connectionsDroppedForGarbage() > 0;
                }, "host dropped the garbage connection");
            }

            assertFalse(host.isConnected(), "the garbage connection must not still be held");
            assertTrue(host.datagramStats().invalidFrames()
                            >= CoopNetService.PRE_SESSION_INVALID_FRAME_LIMIT,
                    "every undecodable frame is counted, not just the last one");
            assertNull(host.pollInbound(), "nothing decodable came out of it");
        } finally {
            host.shutdown();
        }
    }

    /**
     * A burst larger than the ceiling must arrive complete and cost more than one poll. The exact
     * split is not pinned — the read loop finishes the 8 KB buffer it is holding — only that one poll
     * cannot be made unbounded by a sender.
     */
    @Test
    void onePollIngestsAtMostTheFrameCeilingAndTheRestFollowsOnLaterPolls() throws Exception {
        int burst = CoopNetService.MAX_FRAMES_PER_POLL * 4;
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            for (int i = 0; i < burst; i++) {
                guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L + i));
            }
            guest.flushOutbound();

            List<CoopMessages.Message> drained = new ArrayList<>();
            AtomicLong firstPollFrames = new AtomicLong(-1L);
            waitUntil(() -> {
                guest.flushOutbound();
                host.flushOutbound();
                if (firstPollFrames.get() < 0 && host.framesInLastPoll() > 0) {
                    firstPollFrames.set(host.framesInLastPoll());
                }
                CoopMessages.Message message;
                while ((message = host.pollInbound()) != null) {
                    drained.add(message);
                }
                return drained.size() == burst;
            }, "host drained the whole burst");

            assertTrue(firstPollFrames.get() > 0, "the burst never reached the host");
            assertTrue(firstPollFrames.get() < burst,
                    "one poll ingested the whole " + burst + "-frame burst: the ceiling is not applied");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- Phase 20.5: peer table and routing ------------------------------------------------------

    @Test
    void thePeerTableHoldsExactlyOneGuestInV1() {
        CoopNetService service = new CoopNetService();
        try {
            assertEquals(1, service.peerCapacity());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void unicastReachesTheNamedPeerAndAnUnknownNameFallsBackToBroadcast() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            // Before the peer has stamped anything the host cannot name it, so an addressed answer
            // has to broadcast or it would be lost. That is the whole lobby exchange.
            host.send(CoopMessages.ping(null, host.nextSeq(), 900L));
            host.sendTo("guest-player-uuid", CoopMessages.pong(null, host.nextSeq(), 950L, 1L));
            host.flushOutbound();
            assertEquals(CoopMessages.Type.PING, waitForMessage(guest, "broadcast ping").type());
            assertEquals(CoopMessages.Type.PONG,
                    waitForMessage(guest, "unicast to an unnamed peer still lands").type());

            // Once the guest stamps a message the host learns which link is which.
            guest.setLocalSenderId("guest-player-uuid");
            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L));
            guest.flushOutbound();
            assertEquals("guest-player-uuid", waitForMessage(host, "host learned the sender").senderId());

            host.sendTo("guest-player-uuid", CoopMessages.pong(null, host.nextSeq(), 1100L, 2L));
            host.flushOutbound();
            assertEquals(CoopMessages.Type.PONG, waitForMessage(guest, "unicast to the named peer").type());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    // ---- red-team hardening ---------------------------------------------------------------------

    /**
     * The slot-denial hole A1 names: the garbage-strike rule only counted undecodable frames, the
     * half-open replacement rule only fires on silence, and both were satisfied by a stranger that
     * sends a single newline every few seconds. It held the only guest slot for as long as it liked.
     */
    @Test
    void a1_aByteTricklingStrangerIsDroppedAtTheHandshakeDeadline() throws Exception {
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        try {
            host.startHost(port);
            // Deliberately no session token: this connection never proves anything.
            try (java.net.Socket stranger = new java.net.Socket()) {
                stranger.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
                waitUntil(() -> {
                    host.flushOutbound();
                    return host.isConnected();
                }, "host adopted the stranger");

                // Trickle. Every one of these refreshes the silence clock the half-open rule reads.
                for (long at : new long[] {6_000L, 11_000L, 14_999L}) {
                    clock.set(at);
                    stranger.getOutputStream().write('\n');
                    stranger.getOutputStream().flush();
                    Thread.sleep(20L);
                    host.flushOutbound();
                }
                assertTrue(host.isConnected(), "inside the deadline the stranger still holds the slot");
                assertEquals(0L, host.datagramStats().handshakeDeadlineDrops());

                clock.set(1_000L + CoopNetService.HANDSHAKE_DEADLINE_MILLIS);
                host.flushOutbound();

                assertFalse(host.isConnected(),
                        "past the deadline an unproven connection must not still hold the slot");
                assertEquals(1L, host.datagramStats().handshakeDeadlineDrops());
            }
        } finally {
            host.shutdown();
        }
    }

    /** The other half of A1: an empty frame is a frame the sender chose to send, so it takes a strike. */
    @Test
    void a1_emptyFramesCountTowardThePreSessionStrikeLimit() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        try {
            host.startHost(port);
            try (java.net.Socket stranger = new java.net.Socket()) {
                stranger.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
                waitUntil(() -> {
                    host.flushOutbound();
                    return host.isConnected();
                }, "host adopted the stranger");

                byte[] newlines = new byte[CoopNetService.PRE_SESSION_INVALID_FRAME_LIMIT];
                java.util.Arrays.fill(newlines, (byte) '\n');
                stranger.getOutputStream().write(newlines);
                stranger.getOutputStream().flush();

                waitUntil(() -> {
                    host.flushOutbound();
                    return host.datagramStats().connectionsDroppedForGarbage() > 0;
                }, "host dropped the newline-only connection");
            }
            assertFalse(host.isConnected());
        } finally {
            host.shutdown();
        }
    }

    /**
     * A3: the throttle verdict used to be consulted only when no slot was free, so an attacker that
     * kept the slot free — by disconnecting after every attempt, which is exactly what a password
     * guesser does — never met it.
     */
    @Test
    void a3_aThrottledAddressIsRefusedEvenWhenASlotIsFree() throws Exception {
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        try {
            host.startHost(port);
            for (int attempt = 1; attempt <= CoopNetService.MAX_CONNECTION_ATTEMPTS_PER_WINDOW; attempt++) {
                assertTrue(connectAndDisconnect(host, port),
                        "attempt " + attempt + " is inside the limit and the slot is free");
            }
            assertEquals(0L, host.datagramStats().connectionsThrottled());

            assertFalse(connectAndDisconnect(host, port),
                    "past the limit the connection must be closed even though the slot is free");
            assertEquals(1L, host.datagramStats().connectionsThrottled());

            clock.addAndGet(CoopNetService.CONNECTION_THROTTLE_COOLDOWN_MILLIS + 1L);
            assertTrue(connectAndDisconnect(host, port), "past the cooldown the address is served again");
        } finally {
            host.shutdown();
        }
    }

    /**
     * A3's second half: a wrong password drops the connection, which frees the slot, so the
     * connection-attempt throttle saw every guess as a fresh first attempt. The pump reports the
     * failed proof here and the next connection from that source is closed with no reply.
     */
    @Test
    void a3_repeatedFailedProofsPutAnAddressIntoAnExponentialCooldown() throws Exception {
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try {
            host.startHost(port);
            for (int i = 0; i < CoopNetService.MAX_FAILED_PROOFS - 1; i++) {
                host.noteFailedProof(loopback);
                assertFalse(host.isProofThrottled(loopback), "a typo is not an attack");
            }
            host.noteFailedProof(loopback);
            assertTrue(host.isProofThrottled(loopback));

            assertFalse(connectAndDisconnect(host, port), "a guesser gets no frame and no slot");
            assertEquals(1L, host.datagramStats().proofThrottled());

            clock.addAndGet(CoopNetService.FAILED_PROOF_COOLDOWN_MILLIS + 1L);
            assertFalse(host.isProofThrottled(loopback), "the first cooldown is finite");

            // Each further failure doubles the wait, up to the cap.
            host.noteFailedProof(loopback);
            clock.addAndGet(CoopNetService.FAILED_PROOF_COOLDOWN_MILLIS + 1L);
            assertTrue(host.isProofThrottled(loopback), "the fourth failure is worth more than 30 s");

            for (int i = 0; i < 20; i++) {
                host.noteFailedProof(loopback);
            }
            clock.addAndGet(CoopNetService.FAILED_PROOF_MAX_COOLDOWN_MILLIS + 1L);
            assertFalse(host.isProofThrottled(loopback), "the doubling is capped, not unbounded");
        } finally {
            host.shutdown();
        }
    }

    /**
     * The cost A3 would otherwise put on the peer it least wants to refuse. A guest whose link died
     * knocks every 500 ms while the host's OS finishes tearing the old socket down, so it crosses a
     * 5-attempts-per-10-s limit in 2.5 s and would then sit out a 30 s cooldown inside its own
     * reconnect grace — the transport locking out the exact peer the grace window exists for.
     *
     * <p>A known peer is therefore exempt from both gates and its attempts are not counted. It stays
     * known for {@link CoopNetService#KNOWN_PEER_MEMORY_MILLIS} after its link goes away, and only
     * because that link had proved a session — which is what keeps a password guesser out of the
     * exemption (see {@code a3_repeatedFailedProofsPutAnAddressIntoAnExponentialCooldown}).
     */
    @Test
    void a3_aReconnectingKnownPeerIsNeverThrottled() throws Exception {
        int knocks = 20;
        int port = reserveLocalPort();
        AtomicLong clock = new AtomicLong(1_000L);
        CoopNetService host = new CoopNetService(clock::get);
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "the guest took the slot");
            // A proved session is what makes this address a known peer at all.
            host.setExpectedSessionToken(TOKEN);

            // 20 knocks across 5 s, four times the throttle limit for the window. Every one of them
            // must be answered with a frame rather than closed silently.
            for (int attempt = 1; attempt <= knocks; attempt++) {
                clock.addAndGet(250L);
                assertTrue(knock(host, port).contains("LOBBY_REJECT"),
                        "knock " + attempt + " from the known peer was closed with no reply");
            }
            assertEquals(0L, host.datagramStats().connectionsThrottled(),
                    "a returning guest must never be throttled by its own reconnect loop");

            // ...and the half-open replacement still admits it. The held link has now been silent
            // past HALF_OPEN_REPLACE_MILLIS, so the next connection takes the slot off it.
            clock.addAndGet(CoopNetService.HALF_OPEN_REPLACE_MILLIS + 1L);
            try (java.net.Socket returning = new java.net.Socket()) {
                returning.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
                CoopMessages.Message hello = CoopMessages.ping(SESSION_ID, 1L, 4_000L);
                returning.getOutputStream().write((CoopMessages.encode(hello) + "\n").getBytes());
                returning.getOutputStream().flush();

                CoopMessages.Message delivered = waitForMessage(host,
                        "the replacing connection's first frame");
                assertEquals(CoopMessages.Type.PING, delivered.type(),
                        "the returning peer must own the slot, not be refused by the throttle");
            }

            // With the slot free again the exemption comes from the remembered address rather than
            // from an attached link, and the address is still nowhere near the attempt limit's mercy.
            waitUntil(() -> {
                host.flushOutbound();
                return !host.isConnected();
            }, "the host reaped the closed link");
            assertTrue(connectAndDisconnect(host, port),
                    "a known peer is admitted even 20-odd attempts into the window");
            assertEquals(0L, host.datagramStats().connectionsThrottled());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * A4: coalescing bounds nothing on its own — it only ever replaces a snapshot with a newer
     * snapshot, and a queue of semantic events grows one entry per event forever against a peer whose
     * socket has stopped draining. No channel is attached here, which is that state exactly.
     */
    @Test
    void a4_theOutboundQueueIsHardCappedAndTheLinkGoesWhenNothingCanBeTrimmed() {
        CoopNetService service = new CoopNetService();
        try {
            for (int i = 0; i < CoopNetService.QUEUE_HARD_CAP_MESSAGES; i++) {
                service.send(CoopMessages.interactionClaim(SESSION_ID, service.nextSeq(), 1000L,
                        "entity-" + i, "Entity", "player-a"));
            }
            assertEquals(CoopNetService.QUEUE_HARD_CAP_MESSAGES, service.outboundQueueDepth());
            assertEquals(0L, service.datagramStats().queueOverflowDrops());

            // Past the cap a superseded snapshot is what gets discarded; the events keep their places.
            service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), false, false,
                    1L, 1L, 1L, ""));
            assertEquals(CoopNetService.QUEUE_HARD_CAP_MESSAGES, service.outboundQueueDepth(),
                    "the queue must stop growing at its hard cap");
            assertEquals(1L, service.datagramStats().queueOverflowDrops());

            // Nothing left to trim: one message past 2x the cap, the peer is treated as gone.
            int toTheDropThreshold = CoopNetService.QUEUE_DROP_LINK_MESSAGES
                    - CoopNetService.QUEUE_HARD_CAP_MESSAGES + 1;
            for (int i = 0; i < toTheDropThreshold; i++) {
                service.send(CoopMessages.interactionClaim(SESSION_ID, service.nextSeq(), 2000L,
                        "late-" + i, "Entity", "player-a"));
            }

            assertEquals(0, service.outboundQueueDepth(), "the dropped link's queue goes with it");
            assertEquals(CoopNetService.QUEUE_DROP_LINK_MESSAGES + 2L,
                    service.datagramStats().queueOverflowDrops(),
                    "the trimmed snapshot plus every message the dropped link was still holding");
        } finally {
            service.shutdown();
        }
    }

    /**
     * A5/A15: the old guard asked whether the 64 KB receive buffer had filled up, which no UDP
     * datagram can do (the largest payload that exists is 65,507 bytes), so nothing bounded the size
     * of an inbound packet at all.
     */
    @Test
    void a5_anOversizedInboundDatagramIsDroppedAndCounted() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        try {
            host.startHost(port);
            host.setExpectedSessionToken(TOKEN);
            try (DatagramSocket sender = new DatagramSocket()) {
                send(sender, port, snapshot(GUEST_SENDER, 1L,
                        "x".repeat(CoopNetService.MAX_INBOUND_DATAGRAM_BYTES)));
                waitUntil(() -> {
                    host.flushOutbound();
                    return host.datagramStats().droppedOversizedInbound() > 0;
                }, "host dropped the oversized datagram");

                assertNull(host.pollDatagram(), "nothing oversized may reach the drain");
                assertEquals(1L, host.datagramStats().droppedOversizedInbound());

                // A normal-sized one from the same source still lands, so this is a cap, not a wall.
                String ok = snapshot(GUEST_SENDER, 2L, "small");
                send(sender, port, ok);
                assertEquals(ok, waitForDatagram(host, "the in-budget datagram"));
            }
        } finally {
            host.shutdown();
        }
    }

    /**
     * A12: the ceiling was checked once per 8 KB read rather than per frame, so one buffer of
     * one-byte frames bought thousands of framer passes. The bytes past the ceiling are this peer's
     * TCP stream and must be carried to the next poll, never dropped.
     */
    @Test
    void a12_aFloodOfEmptyFramesCannotExceedThePerPollFrameCeiling() throws Exception {
        int frames = CoopNetService.MAX_FRAMES_PER_POLL * 4;
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        try {
            host.startHost(port);
            // A session exists, so the strike rule does not end the connection mid-test.
            host.setExpectedSessionToken(TOKEN);
            try (java.net.Socket peer = new java.net.Socket()) {
                peer.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
                waitUntil(() -> {
                    host.flushOutbound();
                    return host.isConnected();
                }, "host adopted the peer");

                byte[] newlines = new byte[frames];
                java.util.Arrays.fill(newlines, (byte) '\n');
                peer.getOutputStream().write(newlines);
                peer.getOutputStream().flush();

                AtomicLong firstPollFrames = new AtomicLong(-1L);
                waitUntil(() -> {
                    host.flushOutbound();
                    if (firstPollFrames.get() < 0 && host.framesInLastPoll() > 0) {
                        firstPollFrames.set(host.framesInLastPoll());
                    }
                    return host.datagramStats().invalidFrames() >= frames;
                }, "host framed the whole burst across several polls");

                assertEquals(CoopNetService.MAX_FRAMES_PER_POLL, firstPollFrames.get(),
                        "one poll must stop at the ceiling, not at the end of the read buffer");
                assertEquals(frames, host.datagramStats().invalidFrames(),
                        "the bytes past the ceiling are parked, not discarded");
                assertTrue(host.isConnected(), "the session peer is not dropped for this");
            }
        } finally {
            host.shutdown();
        }
    }

    /**
     * A13: an IPv6 host is routinely delegated a whole /64, so a per-address record is a table of
     * one-shot entries and a rate limit that never fires.
     */
    @Test
    void a13_theConnectionThrottleIsKeyedByTheIpv6Prefix() throws Exception {
        String first = CoopNetService.throttleKey(InetAddress.getByName("2001:db8:1:2::1"));
        String second = CoopNetService.throttleKey(InetAddress.getByName("2001:db8:1:2:dead:beef:0:9"));
        String otherPrefix = CoopNetService.throttleKey(InetAddress.getByName("2001:db8:1:3::1"));

        assertEquals(first, second, "two addresses in one /64 are one throttle identity");
        assertNotEquals(first, otherPrefix, "a different /64 is a different identity");
        assertEquals("20010db800010002::/64", first);

        // IPv4 keeps full-address granularity; there is no prefix to hide behind.
        assertNotEquals(CoopNetService.throttleKey(InetAddress.getByName("198.51.100.1")),
                CoopNetService.throttleKey(InetAddress.getByName("198.51.100.2")));
        assertNull(CoopNetService.throttleKey(null));
    }

    /**
     * C2: one motion tick is several chunk datagrams sharing a type and a sender. Without the chunk
     * in the key, a backlogged TCP fallback superseded every chunk of the tick with the last one and
     * delivered a batch missing most of its fleets — which reads as fleets that stopped moving.
     */
    @Test
    void c2_backloggedStateDatagramChunksDoNotSupersedeEachOther() {
        CoopNetService service = new CoopNetService();
        try {
            for (int i = 0; i < CoopNetService.COALESCE_BACKLOG_MESSAGES; i++) {
                service.send(CoopMessages.timeSnapshot(SESSION_ID, service.nextSeq(), false, false,
                        1000L + i, 1L, 1000L + i, ""));
            }
            int backlogged = service.outboundQueueDepth();

            for (int chunk = 0; chunk < 3; chunk++) {
                service.send(CoopMessages.stateDatagram(SESSION_ID, service.nextSeq(), 2000L,
                        CoopMessages.datagram(TOKEN, HOST_SENDER, CoopMessages.Type.NPC_FLEET_MOTION,
                                5L, 0L, chunk, "chunk-" + chunk)));
            }
            assertEquals(backlogged + 3, service.outboundQueueDepth(),
                    "three chunks of one tick are three queue entries");

            service.send(CoopMessages.stateDatagram(SESSION_ID, service.nextSeq(), 2100L,
                    CoopMessages.datagram(TOKEN, HOST_SENDER, CoopMessages.Type.NPC_FLEET_MOTION,
                            6L, 0L, 1, "chunk-1-newer")));
            assertEquals(backlogged + 3, service.outboundQueueDepth(),
                    "a newer sample of chunk 1 replaces chunk 1, and only chunk 1");
        } finally {
            service.shutdown();
        }
    }

    /**
     * C5: the reject frame used to be written on a socket switched to <em>blocking</em> mode, inside
     * the campaign frame. A peer that connects and never reads is an ordinary WAN event, and against
     * one the write loop had nothing bounding it.
     */
    @Test
    void c5_anExtraConnectionThatNeverReadsIsRejectedWithoutStallingTheFrame() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "the only slot is taken");

            try (java.net.Socket silent = new java.net.Socket()) {
                silent.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
                silent.setSoTimeout(20);
                byte[] sink = new byte[512];
                waitUntil(() -> {
                    host.flushOutbound();
                    try {
                        // Drained a buffer at a time, not a byte at a time: reading one byte per
                        // 25 ms poll made this wait proportional to the reject frame's length
                        // (~200 bytes = ~5 s), which is the whole margin waitUntil has. The property
                        // under test is that the connection is closed, and the property that the
                        // write is non-blocking is asserted structurally below.
                        return silent.getInputStream().read(sink) < 0;
                    } catch (java.net.SocketTimeoutException timeout) {
                        return false;
                    } catch (IOException closed) {
                        return true;
                    }
                }, "the host closed the extra connection");
            }
            assertTrue(bothConnected(host, guest), "the real session is untouched");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * The structural half of C5, so the regression is caught by a property rather than by a stopwatch:
     * on a loaded machine "the poll took under a second" measures the scheduler, not this code.
     */
    @Test
    void c5_theRejectFrameIsOneWriteOnANonBlockingChannel() throws Exception {
        CoopNetService service = new CoopNetService();
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                 SocketChannel accepted = listener.accept()) {
                assertTrue(accepted.isBlocking(), "a freshly accepted channel starts out blocking");

                assertTrue(service.writeLobbyRejectFrame(accepted, "Host already has an active connection"),
                        "the whole frame fits in an empty socket buffer");

                assertFalse(accepted.isBlocking(),
                        "the reject must never be written on a channel that can park the frame");
                ByteBuffer received = ByteBuffer.allocate(1024);
                client.read(received);
                assertTrue(new String(received.array(), 0, received.position(),
                                java.nio.charset.StandardCharsets.UTF_8).contains("LOBBY_REJECT"),
                        "the courtesy reject still reaches the peer");
            }
        } finally {
            service.shutdown();
        }
    }

    /**
     * net-9: both guest start paths set the local id and then call {@code connect()}, whose teardown
     * used to null it — so every frame a guest sent went out unstamped, no peer was ever learned by
     * name, and every host unicast fell back to broadcast.
     */
    @Test
    void aSenderIdSetBeforeConnectStillStampsTheGuestsFrames() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.setLocalSenderId("guest-player");
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            guest.send(CoopMessages.ping(null, guest.nextSeq(), 1000L));
            guest.flushOutbound();

            CoopMessages.Message inbound = waitForMessage(host, "host inbound ping");
            assertEquals("guest-player", inbound.senderId(),
                    "the id the pump set before connect() must survive it");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * net-7: {@code SocketChannel.connect} with an unresolved address throws before the JDK's own
     * close-on-failure path, so the 500 ms retry loop leaked a handle per attempt — and paid for a
     * blocking resolver call on the campaign thread every time it did.
     */
    @Test
    void anUnresolvableHostNeverOpensASocketAndBacksOffUntilItResolves() throws Exception {
        int port = reserveLocalPort();
        AtomicLong lookups = new AtomicLong();
        AtomicReference<java.net.InetSocketAddress> answer = new AtomicReference<>();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService() {
            @Override
            java.net.InetSocketAddress resolveConnectAddress(String name, int servicePort) {
                lookups.incrementAndGet();
                java.net.InetSocketAddress resolved = answer.get();
                return resolved != null
                        ? resolved
                        : java.net.InetSocketAddress.createUnresolved(name, servicePort);
            }
        };
        try {
            host.startHost(port);
            guest.connect("host.invalid", port);
            for (int i = 0; i < 20; i++) {
                guest.flushOutbound();
            }

            assertFalse(guest.isConnected(), "there is nothing to connect to yet");
            assertTrue(lookups.get() <= 2L,
                    "a name that will not resolve is looked up once per backoff window, not once per"
                            + " poll; was " + lookups.get() + " for 21 polls");
            assertTrue(guest.nextConnectAttemptAtMillisForTest() > System.currentTimeMillis(),
                    "the connect loop must back off rather than spin");

            answer.set(new java.net.InetSocketAddress("127.0.0.1", port));
            waitUntil(() -> {
                guest.flushOutbound();
                host.flushOutbound();
                return bothConnected(host, guest);
            }, "the guest connects once the name resolves");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * net-32: a channel that fails its socket options is referenced by nothing — the accept path's
     * only handler closes {@code pendingConnectChannel}, which is null on the host — so leaving it
     * open leaked a handle per occurrence.
     */
    @Test
    void aChannelThatFailsItsSocketOptionsIsClosedRatherThanOrphaned() {
        FailingSocketChannel channel = new FailingSocketChannel();

        assertThrows(IOException.class, () -> CoopNetService.configurePeerChannel(channel));

        assertTrue(channel.closed, "the channel nobody else can reach must be closed here");
    }

    /** A channel whose {@code configureBlocking} fails, which no real socket can be made to do on cue. */
    private static final class FailingSocketChannel extends SocketChannel {
        private boolean closed;

        private FailingSocketChannel() {
            super(java.nio.channels.spi.SelectorProvider.provider());
        }

        @Override
        protected void implConfigureBlocking(boolean block) throws IOException {
            throw new IOException("no");
        }

        @Override
        protected void implCloseSelectableChannel() {
            closed = true;
        }

        @Override
        public java.net.Socket socket() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SocketChannel bind(java.net.SocketAddress local) {
            return this;
        }

        @Override
        public <T> SocketChannel setOption(java.net.SocketOption<T> name, T value) {
            return this;
        }

        @Override
        public <T> T getOption(java.net.SocketOption<T> name) {
            return null;
        }

        @Override
        public java.util.Set<java.net.SocketOption<?>> supportedOptions() {
            return java.util.Set.of();
        }

        @Override
        public SocketChannel shutdownInput() {
            return this;
        }

        @Override
        public SocketChannel shutdownOutput() {
            return this;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isConnectionPending() {
            return false;
        }

        @Override
        public boolean connect(java.net.SocketAddress remote) {
            return true;
        }

        @Override
        public boolean finishConnect() {
            return true;
        }

        @Override
        public java.net.SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public java.net.SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public int read(ByteBuffer destination) {
            return -1;
        }

        @Override
        public long read(ByteBuffer[] destinations, int offset, int length) {
            return -1L;
        }

        @Override
        public int write(ByteBuffer source) {
            return 0;
        }

        @Override
        public long write(ByteBuffer[] sources, int offset, int length) {
            return 0L;
        }
    }

    /**
     * C8: {@code setExpectedSessionToken} claimed in a comment to clear the validated return address
     * and did not. On the host that address is learned from the wire and must be re-earned by the
     * path challenge when the session changes; on the guest it is the configured host address, and
     * clearing it would silence the guest's own stream.
     */
    @Test
    void c8_aNewSessionTokenClearsTheHostsValidatedUdpAddressAndNotTheGuests() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            startSession(host, guest, port);
            validateUdpPath(host, guest);
            assertFalse(host.datagramStats().validatedRemote().isEmpty());
            String guestTarget = guest.datagramStats().validatedRemote();
            assertFalse(guestTarget.isEmpty());

            String nextSession = CoopMessages.wireToken("session-b");
            host.setExpectedSessionToken(nextSession);
            guest.setExpectedSessionToken(nextSession);

            assertEquals("", host.datagramStats().validatedRemote(),
                    "the host must re-earn the return address for the new session");
            assertEquals(guestTarget, guest.datagramStats().validatedRemote(),
                    "the guest's target is configured, not learned");

            // Re-setting the same token is not a session change and must not disturb anything.
            guest.setExpectedSessionToken(nextSession);
            assertEquals(guestTarget, guest.datagramStats().validatedRemote());
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * The seam B2/C1 need: a close and an attach inside one poll produce no {@code isConnected()}
     * edge, so a monotonic count of attaches is the only thing that tells the pump the slot is held
     * by a different socket than it was.
     */
    @Test
    void connectionGenerationRisesOnEveryAttach() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService();
        CoopNetService guest = new CoopNetService();
        try {
            assertEquals(0L, host.connectionGeneration());
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");
            assertEquals(1L, host.connectionGeneration());

            host.dropActiveConnection("test");
            waitUntil(() -> {
                host.flushOutbound();
                guest.flushOutbound();
                return host.connectionGeneration() == 2L;
            }, "the guest's retry produced a second attach");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    /**
     * Opens a connection, lets the host decide what to do with it, then closes it.
     *
     * @return true when the host adopted it, false when the host closed it with no reply
     */
    private boolean connectAndDisconnect(CoopNetService host, int port) throws Exception {
        boolean adopted;
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(20);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            Boolean verdict = null;
            while (verdict == null && System.nanoTime() < deadline) {
                host.flushOutbound();
                if (host.isConnected()) {
                    verdict = Boolean.TRUE;
                    break;
                }
                try {
                    if (socket.getInputStream().read() < 0) {
                        verdict = Boolean.FALSE;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // keep pumping; the host accepts only inside a poll
                }
            }
            assertNotNull(verdict, "the host neither adopted nor closed the connection");
            adopted = verdict;
        }
        if (adopted) {
            waitUntil(() -> {
                host.flushOutbound();
                return !host.isConnected();
            }, "the host noticed the connection close");
        }
        return adopted;
    }

    /**
     * One TCP connection attempt from a throwaway socket. Returns the frame the host answered with,
     * or "" when it closed without saying anything.
     */
    private String knock(CoopNetService host, int port) throws Exception {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(20);
            StringBuilder received = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                host.flushOutbound();
                try {
                    int value = socket.getInputStream().read();
                    if (value < 0 || value == '\n') {
                        return received.toString();
                    }
                    received.append((char) value);
                } catch (java.net.SocketTimeoutException ignored) {
                    // keep pumping the host; its accept only happens inside a poll
                }
            }
            throw new AssertionError("Host never answered or closed the knocking connection");
        }
    }
}
