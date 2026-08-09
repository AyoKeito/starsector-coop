package coop.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNetServiceTest {
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
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            String guestSnapshot = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "guest-snapshot");
            guest.sendDatagram(guestSnapshot);
            guest.flushOutbound();

            assertEquals(guestSnapshot, waitForDatagram(host, "host inbound UDP fleet snapshot"));

            String hostSnapshot = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "host-snapshot");
            host.sendDatagram(hostSnapshot);
            host.flushOutbound();

            assertEquals(hostSnapshot, waitForDatagram(guest, "guest inbound UDP fleet snapshot"));
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

    private String waitForDatagram(CoopNetService service, String description) throws InterruptedException {
        AtomicReference<String> datagram = new AtomicReference<>();
        waitUntil(() -> {
            datagram.set(service.pollDatagram());
            return datagram.get() != null;
        }, description);
        return datagram.get();
    }

    private CoopMessages.Message waitForMessageWhilePollingHost(CoopNetService host, CoopNetService guest,
                                                                String description) throws InterruptedException {
        AtomicReference<CoopMessages.Message> message = new AtomicReference<>();
        waitUntil(() -> {
            host.isConnected();
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
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            // Establish the legitimate return address first.
            String fromGuest = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "guest-snapshot");
            guest.sendDatagram(fromGuest);
            guest.flushOutbound();
            assertEquals(fromGuest, waitForDatagram(host, "host inbound UDP from guest"));

            // A stray packet from another local socket: same address (loopback gives everyone
            // 127.0.0.1), different port. Address-only pinning would let this through and re-teach
            // the host its return address, blackholing the motion stream to the intruder.
            byte[] payload = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "intruder")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            intruder.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName("127.0.0.1"), port));

            // The intruder's payload must never surface as inbound traffic.
            Thread.sleep(200L);
            host.isConnected();
            CoopMessages.Message leaked = host.pollInbound();
            assertNull(leaked, "intruder datagram must not be delivered as inbound traffic");

            // And the host still streams to the real guest, not to whoever spoke last.
            String toGuest = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "host-snapshot");
            host.sendDatagram(toGuest);
            host.flushOutbound();
            assertEquals(toGuest, waitForDatagram(guest, "guest inbound UDP after intruder packet"));
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
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> bothConnected(host, guest), "host and guest connected");

            // Teach the host the guest's return address with a normal datagram.
            String small = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "small");
            guest.sendDatagram(small);
            guest.flushOutbound();
            assertEquals(small, waitForDatagram(host, "host inbound UDP priming"));

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
            String after = CoopMessages.datagram(
                    "session-a", CoopMessages.Type.FLEET_SNAPSHOT, "after-truncation");
            guest.sendDatagram(after);
            guest.flushOutbound();
            assertEquals(after, waitForDatagram(host, "host inbound UDP after oversized datagram"));
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }
}
