package coop.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            waitUntil(() -> host.isConnected() && guest.isConnected(), "host and guest connected");

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
}
