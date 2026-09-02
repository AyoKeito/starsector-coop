package coop.net;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20.3 spike, Tier 1 (IPv6 direct): does a wildcard NIO bind actually accept IPv6 traffic on
 * this platform, and which literal form does {@code coop.connectHost} take?
 *
 * <p>Tier 1 is the cheapest reachability tier there is — many households have public IPv6 with no
 * NAT at all, only a firewall rule. But it is only free if the existing wildcard binds in
 * {@code CoopNetService} are dual-stack; if they are v4-only, Tier 1 needs code, not documentation.
 * This test answers that on whatever machine it runs, and the answers are written up in
 * {@code docs/CONNECTIVITY.md}.
 *
 * <p>Skipped rather than failed where IPv6 is absent: a machine with no IPv6 loopback cannot answer
 * the question, and pretending otherwise would be a false negative.
 */
class CoopDualStackBindSpikeTest {
    private static InetAddress ipv6Loopback() {
        try {
            InetAddress address = InetAddress.getByName("::1");
            // Probe that the stack will actually bind it before the real test tries to use it.
            try (DatagramChannel probe = DatagramChannel.open(StandardProtocolFamily.INET6)) {
                probe.bind(new InetSocketAddress(address, 0));
            }
            return address;
        } catch (Exception ex) {
            return null;
        }
    }

    @Test
    void wildcardBoundServerSocketAcceptsAnIpv6LoopbackConnection() throws Exception {
        InetAddress loopback6 = ipv6Loopback();
        Assumptions.assumeTrue(loopback6 != null, "no usable ::1 on this machine");

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

            try (SocketChannel client = SocketChannel.open(new InetSocketAddress(loopback6, port));
                 SocketChannel accepted = server.accept()) {
                assertNotNull(accepted, "wildcard TCP bind did not accept the IPv6 connection");
                SocketAddress remote = accepted.getRemoteAddress();
                InetAddress remoteAddress = ((InetSocketAddress) remote).getAddress();

                System.out.println("=== Phase 20.3 dual-stack spike (TCP) ===");
                System.out.println("server local      " + server.getLocalAddress());
                System.out.println("accepted remote   " + remote);
                System.out.println("remote class      " + remoteAddress.getClass().getSimpleName());
                System.out.println("client local      " + client.getLocalAddress());

                assertTrue(remoteAddress.isLoopbackAddress());
                // Recorded 2026-09-02 on Windows 11: the wildcard bind comes up as [::] and the peer
                // arrives as a real Inet6Address, not a v4-mapped Inet4Address. Tier 1 therefore needs
                // no code -- CoopNetService's existing wildcard binds already serve IPv6.
                assertTrue(remoteAddress instanceof Inet6Address,
                        "expected a native IPv6 peer address, got " + remoteAddress.getClass().getSimpleName());
            }
        }
    }

    @Test
    void wildcardBoundDatagramChannelReceivesFromAnIpv6Socket() throws Exception {
        InetAddress loopback6 = ipv6Loopback();
        Assumptions.assumeTrue(loopback6 != null, "no usable ::1 on this machine");

        try (DatagramChannel receiver = DatagramChannel.open();
             DatagramChannel sender = DatagramChannel.open(StandardProtocolFamily.INET6)) {
            receiver.bind(new InetSocketAddress(0));
            sender.bind(new InetSocketAddress(0));
            int port = ((InetSocketAddress) receiver.getLocalAddress()).getPort();

            sender.send(ByteBuffer.wrap(new byte[]{1, 2, 3}), new InetSocketAddress(loopback6, port));

            ByteBuffer buffer = ByteBuffer.allocate(16);
            receiver.configureBlocking(true);
            receiver.socket().setSoTimeout(2000);
            SocketAddress from = receiver.receive(buffer);
            assertNotNull(from, "wildcard UDP bind did not receive the IPv6 datagram");
            InetAddress fromAddress = ((InetSocketAddress) from).getAddress();

            System.out.println("=== Phase 20.3 dual-stack spike (UDP) ===");
            System.out.println("receiver local    " + receiver.getLocalAddress());
            System.out.println("datagram from     " + from);
            System.out.println("from class        " + fromAddress.getClass().getSimpleName());

            assertEquals(3, buffer.position());
            assertTrue(fromAddress.isLoopbackAddress());
            assertTrue(fromAddress instanceof Inet6Address,
                    "expected a native IPv6 sender address, got " + fromAddress.getClass().getSimpleName());
        }
    }

    @Test
    void reportsWhichIpv6LiteralFormsCoopConnectHostAccepts() {
        InetSocketAddress bare = new InetSocketAddress("::1", 27015);
        InetSocketAddress bracketed = new InetSocketAddress("[::1]", 27015);
        InetSocketAddress bogus = new InetSocketAddress("::1:27015", 27015);

        System.out.println("=== Phase 20.3 dual-stack spike (literal forms) ===");
        System.out.println("\"::1\"           resolved=" + !bare.isUnresolved() + " -> " + bare.getAddress());
        System.out.println("\"[::1]\"         resolved=" + !bracketed.isUnresolved() + " -> " + bracketed.getAddress());
        System.out.println("\"::1:27015\"     resolved=" + !bogus.isUnresolved() + " -> " + bogus.getAddress());

        // Recorded 2026-09-02: InetAddress.getByName accepts both the bare RFC 2373 literal and the
        // RFC 2732 bracketed one, so coop.connectHost takes either. What it does NOT take is the
        // address and port jammed together -- the port always goes in coop.connectPort.
        assertTrue(!bare.isUnresolved() && bare.getAddress() instanceof Inet6Address,
                "the bare literal must work: it is what docs/CONNECTIVITY.md tells hosts to share");
        assertTrue(!bracketed.isUnresolved() && bracketed.getAddress() instanceof Inet6Address,
                "the bracketed literal is accepted too, so the doc need not forbid it");
        assertTrue(bogus.isUnresolved(), "address:port in one string must not silently resolve");
    }
}
