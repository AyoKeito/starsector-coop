package coop.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopLauncherProbeTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void aRealListenerAnswersTheTcpBannerTheProbeAndTheUdpEcho() throws IOException {
        try (CoopLauncherProbe.HostListener listener =
                     CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK)) {
            CoopLauncherProbe.Result result =
                    CoopLauncherProbe.GuestProber.probe(LOOPBACK.getHostAddress(), listener.port());

            assertTrue(result.tcpReachable(), result.message());
            assertTrue(result.launcherAnswered(), result.message());
            assertEquals("0.1.0-test", result.launcherVersion());
            assertTrue(result.udpEchoed(), result.message());
            assertTrue(result.rttMillis() >= 0, result.message());
            assertTrue(result.message().contains("round trip"), result.message());
        }
    }

    @Test
    void theListenerServesMoreThanOneProbe() throws IOException {
        try (CoopLauncherProbe.HostListener listener =
                     CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK)) {
            for (int i = 0; i < 3; i++) {
                assertTrue(CoopLauncherProbe.GuestProber
                        .probe(LOOPBACK.getHostAddress(), listener.port()).launcherAnswered());
            }
        }
    }

    /**
     * The load-bearing one. Against something that listens but never speaks - which is what the
     * host's running <em>game</em> looks like - the prober must report "not the launcher" and must
     * put nothing at all on the wire, because a byte here could be read as a password proof and
     * count toward the game's per-address cooldown.
     */
    @Test
    void againstASilentListenerTheProberSendsNothingAtAll() throws Exception {
        AtomicInteger bytesReceived = new AtomicInteger(-1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch served = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(LOOPBACK, 0));
            Thread accepter = new Thread(() -> {
                try (Socket client = server.accept()) {
                    // Deliberately write nothing. Read with a short timeout: anything that arrives
                    // is a byte the prober was not allowed to send.
                    client.setSoTimeout(1500);
                    InputStream in = client.getInputStream();
                    int read;
                    try {
                        read = in.read();
                    } catch (SocketTimeoutException ex) {
                        read = -1;
                    }
                    bytesReceived.set(read);
                } catch (Exception ex) {
                    failure.set(ex);
                } finally {
                    served.countDown();
                }
            }, "silent-listener");
            accepter.setDaemon(true);
            accepter.start();

            CoopLauncherProbe.Result result =
                    CoopLauncherProbe.GuestProber.probe(LOOPBACK.getHostAddress(),
                            server.getLocalPort());

            assertTrue(result.tcpReachable(), result.message());
            assertFalse(result.launcherAnswered(), result.message());
            assertFalse(result.udpEchoed(), result.message());
            assertEquals(-1L, result.rttMillis());
            assertTrue(result.message().contains("not the co-op launcher"), result.message());

            assertTrue(served.await(10, TimeUnit.SECONDS), "the silent listener never finished");
            assertEquals(null, failure.get());
            // -1 is end-of-stream or the read timeout: either way, zero bytes arrived.
            assertEquals(-1, bytesReceived.get(),
                    "the prober put a byte on the wire against a non-launcher listener");
        }
    }

    /**
     * close() runs on the event dispatch thread, from LAUNCH among others. A half-open probe that
     * says nothing must not park the window: closing the ServerSocket leaves the accepted socket
     * alone, so the serving thread would sit in its five-second read while close() waited out its
     * two-second join.
     */
    @Test
    void closingReturnsAtOnceWhileAProbeIsBeingServed() throws Exception {
        CoopLauncherProbe.HostListener listener =
                CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK);
        try (Socket silent = new Socket()) {
            silent.connect(new InetSocketAddress(LOOPBACK, listener.port()), 5000);
            // Reading the banner proves the listener accepted and is now waiting for a line that
            // this test never sends.
            silent.setSoTimeout(5000);
            assertTrue(silent.getInputStream().read() >= 0, "no banner arrived");

            long started = System.nanoTime();
            listener.close();
            long millis = (System.nanoTime() - started) / 1_000_000L;

            assertTrue(millis < 1000L, "close() blocked the caller for " + millis + " ms");
        } finally {
            listener.close();
        }
    }

    @Test
    void aClosedPortIsRefusedWithItsOwnMessage() throws IOException {
        int port;
        try (ServerSocket scratch = new ServerSocket()) {
            scratch.bind(new InetSocketAddress(LOOPBACK, 0));
            port = scratch.getLocalPort();
        }

        CoopLauncherProbe.Result result =
                CoopLauncherProbe.GuestProber.probe(LOOPBACK.getHostAddress(), port);

        assertFalse(result.tcpReachable(), result.message());
        assertTrue(result.message().contains("Could not connect"), result.message());
        assertTrue(result.message().contains("firewall"), result.message());
    }

    @Test
    void anAddressThatDoesNotResolveSaysSoRatherThanTimingOut() {
        CoopLauncherProbe.Result result = CoopLauncherProbe.GuestProber
                .probe("no-such-host.invalid", 7777);

        assertFalse(result.tcpReachable());
        assertTrue(result.message().contains("could not be looked up"), result.message());
    }

    @Test
    void anEmptyAddressOrABadPortIsRefusedBeforeAnySocketIsOpened() {
        assertTrue(CoopLauncherProbe.GuestProber.probe("  ", 7777).message()
                .contains("address first"));
        assertTrue(CoopLauncherProbe.GuestProber.probe("127.0.0.1", 0).message()
                .contains("1 and 65535"));
        assertTrue(CoopLauncherProbe.GuestProber.probe("127.0.0.1", 70000).message()
                .contains("1 and 65535"));
    }

    @Test
    void aBracketedIpv6LoopbackAddressIsAccepted() throws IOException {
        InetAddress ipv6Loopback;
        try {
            ipv6Loopback = InetAddress.getByName("::1");
        } catch (IOException ex) {
            return;
        }
        try (CoopLauncherProbe.HostListener listener =
                     CoopLauncherProbe.HostListener.open(0, "0.1.0-test", ipv6Loopback)) {
            CoopLauncherProbe.Result result =
                    CoopLauncherProbe.GuestProber.probe("[::1]", listener.port());
            assertTrue(result.launcherAnswered(), result.message());
        } catch (IOException ex) {
            // No IPv6 on this machine; nothing to assert.
        }
    }

    @Test
    void closingTheListenerReleasesThePortForTheGame() throws IOException {
        CoopLauncherProbe.HostListener listener =
                CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK);
        int port = listener.port();
        listener.close();

        try (ServerSocket rebind = new ServerSocket()) {
            rebind.bind(new InetSocketAddress(LOOPBACK, port));
            assertEquals(port, rebind.getLocalPort());
        }
    }

    @Test
    void aPortAlreadyHeldRefusesToBindRatherThanPretending() throws IOException {
        try (CoopLauncherProbe.HostListener first =
                     CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK)) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> CoopLauncherProbe.HostListener.open(first.port(), "0.1.0-test", LOOPBACK)
                            .close());
        }
    }

    /**
     * net-fix-3: the listener answered with {@code readLine()}, which buffers until a newline
     * arrives. A client that opened the socket and sent megabytes without one grew the launcher's
     * heap for as long as it kept going, and the accept loop is serial, so it also denied the check
     * to the real guest.
     */
    @Test
    void anUnboundedRequestLineIsRefusedAndTheSocketClosed() throws Exception {
        try (CoopLauncherProbe.HostListener listener =
                     CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress(LOOPBACK, listener.port()), 3000);
            client.setSoTimeout(10_000);
            assertTrue(readLine(client).startsWith(CoopLauncherProbe.BANNER_PREFIX));

            byte[] flood = new byte[CoopLauncherProbe.MAX_REQUEST_BYTES * 8];
            java.util.Arrays.fill(flood, (byte) 'A');
            try {
                client.getOutputStream().write(flood);
                client.getOutputStream().flush();
            } catch (IOException ignored) {
                // The listener may already have closed under us, which is the behaviour under test.
            }

            assertEquals(-1, client.getInputStream().read(),
                    "the listener must close on an over-long request line, not keep buffering it");

            // And the listener is still serving: the refusal ends one connection, not the check.
            assertTrue(CoopLauncherProbe.GuestProber
                    .probe(LOOPBACK.getHostAddress(), listener.port()).launcherAnswered());
        }
    }

    /**
     * net-fix-3, the other half: {@code setSoTimeout} bounds idle time only, so a client that sent
     * one byte every few seconds could hold the serial accept loop open indefinitely. The deadline is
     * absolute, measured from accept.
     */
    @Test
    void aTricklingClientIsCutOffAtTheAbsoluteDeadline() throws Exception {
        try (CoopLauncherProbe.HostListener listener =
                     CoopLauncherProbe.HostListener.open(0, "0.1.0-test", LOOPBACK);
             Socket client = new Socket()) {
            client.connect(new InetSocketAddress(LOOPBACK, listener.port()), 3000);
            client.setSoTimeout(15_000);
            assertTrue(readLine(client).startsWith(CoopLauncherProbe.BANNER_PREFIX));

            long start = System.currentTimeMillis();
            // 512 bytes at one every 250 ms is over two minutes, so the request-length cap cannot be
            // what ends this; only the wall clock can.
            long limit = start + CoopLauncherProbe.CONNECTION_DEADLINE_MILLIS + 2000L;
            boolean closed = false;
            while (System.currentTimeMillis() < limit) {
                try {
                    // One byte, well inside the idle timeout, forever. Never a newline.
                    client.getOutputStream().write('A');
                    client.getOutputStream().flush();
                } catch (IOException ex) {
                    closed = true;
                    break;
                }
                Thread.sleep(250L);
            }
            if (!closed) {
                client.setSoTimeout(2000);
                try {
                    closed = client.getInputStream().read() < 0;
                } catch (SocketTimeoutException ex) {
                    closed = false;
                }
            }
            assertTrue(closed, "the listener served a trickling client past its absolute deadline");
        }
    }

    private static String readLine(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) >= 0 && value != '\n') {
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return line.toString();
    }

    @Test
    void noncesAreHexAndCarryNoSpaces() {
        for (int i = 0; i < 200; i++) {
            String nonce = CoopLauncherProbe.newNonce();
            assertTrue(nonce.matches("[0-9a-f]+"), nonce);
        }
    }
}
