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

    @Test
    void noncesAreHexAndCarryNoSpaces() {
        for (int i = 0; i < 200; i++) {
            String nonce = CoopLauncherProbe.newNonce();
            assertTrue(nonce.matches("[0-9a-f]+"), nonce);
        }
    }
}
