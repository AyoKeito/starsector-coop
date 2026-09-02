package coop.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private List<String> drainDatagrams(CoopNetService service) {
        List<String> drained = new ArrayList<>();
        String payload;
        while ((payload = service.pollDatagram()) != null) {
            drained.add(payload);
        }
        return drained;
    }
}
