package coop.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopPortMapperTest {
    @Test
    void disabledMapperFinishesImmediatelyWithoutTouchingTheNetwork() {
        CoopPortMapper mapper = CoopPortMapper.start(27015, false, () -> 0L);

        CoopPortMapper.Result result = mapper.result();

        assertTrue(result.finished());
        assertFalse(result.mapped());
        assertEquals(CoopPortMapper.Tier.NONE, result.tier());
        assertTrue(result.failureText().contains("coop.portMapping=off"), result.failureText());
        assertEquals("", result.externalEndpoint());

        mapper.tick(1L);
        mapper.shutdown();
        assertTrue(mapper.result().finished());
    }

    @Test
    void rejectsAnOutOfRangePort() {
        assertThrows(IllegalArgumentException.class, () -> CoopPortMapper.start(0, true, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> CoopPortMapper.start(70000, true, () -> 0L));
    }

    @Test
    void silentDiscoveryFallsThroughNatPmpAndGivesUpWithAFailureText() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startOffline(27015, clock::get);

        for (int i = 0; i < 500 && !mapper.result().finished(); i++) {
            mapper.tick(clock.addAndGet(100L));
        }

        CoopPortMapper.Result result = mapper.result();
        assertTrue(result.finished(), "state machine must reach a terminal state on its own");
        assertFalse(result.mapped());
        assertEquals(CoopPortMapper.Tier.NONE, result.tier());
        assertTrue(result.failureText().toLowerCase().contains("nat-pmp"), result.failureText());
        // Whole run: 3 s SSDP window plus three NAT-PMP attempts 750 ms apart.
        assertTrue(clock.get() - 1_000_000L < 8_000L, "gave up in " + (clock.get() - 1_000_000L) + " ms");
    }

    @Test
    void tickIsIdempotentAfterAFailureAndShutdownIsSafeToCallTwice() {
        AtomicLong clock = new AtomicLong(0L);
        CoopPortMapper mapper = CoopPortMapper.startOffline(27015, clock::get);
        for (int i = 0; i < 500 && !mapper.result().finished(); i++) {
            mapper.tick(clock.addAndGet(100L));
        }

        String failure = mapper.result().failureText();
        mapper.tick(clock.addAndGet(60_000L));
        assertEquals(failure, mapper.result().failureText());

        mapper.shutdown();
        mapper.shutdown();
        assertTrue(mapper.result().finished());
    }

    /**
     * B5's publish half: the mapper's verdict is not write-once. A renewal 30 minutes in can lose the
     * lease, change the external port or discover CGNAT, and every caller that published the first
     * verdict - the reachability line, the connection doctor - had no way to notice.
     */
    @Test
    void b5_resultVersionChangesExactlyWhenTheResultDoes() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startOffline(27015, clock::get);

        long initial = mapper.resultVersion();
        assertEquals(initial, mapper.resultVersion(), "polling an unchanged result must not churn it");

        for (int i = 0; i < 500 && !mapper.result().finished(); i++) {
            mapper.tick(clock.addAndGet(100L));
        }

        long afterVerdict = mapper.resultVersion();
        assertTrue(afterVerdict > initial, "the verdict changed and a publisher has to be told");

        mapper.tick(clock.addAndGet(60_000L));
        assertEquals(afterVerdict, mapper.resultVersion(), "a settled mapper stops changing");
    }

    @Test
    void classifiesCarrierGradeAndPrivateAddressesAsUnroutable() {
        for (String address : new String[]{
                "10.0.0.1", "10.255.255.255",
                "172.16.0.1", "172.31.255.254",
                "192.168.0.1", "192.168.1.1",
                "100.64.0.1", "100.127.255.254",
                "169.254.1.1",
                "127.0.0.1"}) {
            assertTrue(CoopPortMapper.isUnroutableExternalAddress(address), address + " must be unroutable");
        }
    }

    @Test
    void classifiesPublicAddressesAsRoutableIncludingTheNeighboursOfEveryPrivateRange() {
        for (String address : new String[]{
                "203.0.113.7", "8.8.8.8",
                "9.255.255.255", "11.0.0.0",
                "172.15.255.255", "172.32.0.0",
                "192.167.255.255", "192.169.0.0",
                "100.63.255.255", "100.128.0.0",
                "169.253.255.255", "169.255.0.0",
                "126.255.255.255", "128.0.0.1"}) {
            assertFalse(CoopPortMapper.isUnroutableExternalAddress(address), address + " must be routable");
        }
    }

    @Test
    void unparsableAddressesAreNotClaimedToBeCarrierGradeNat() {
        assertFalse(CoopPortMapper.isUnroutableExternalAddress(null));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress(""));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("2001:db8::1"));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("10.0.0"));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("10.0.0.999"));
        assertFalse(CoopPortMapper.isUnroutableExternalAddress("ten.zero.zero.one"));
    }

    @Test
    void guessesTheGatewayAsTheFirstHostOfTheLocalSubnet() {
        assertEquals("192.168.1.1", CoopPortMapper.guessDefaultGateway("192.168.1.5"));
        assertEquals("10.0.7.1", CoopPortMapper.guessDefaultGateway("10.0.7.240"));
        assertEquals("", CoopPortMapper.guessDefaultGateway(""));
        assertEquals("", CoopPortMapper.guessDefaultGateway(null));
        assertEquals("", CoopPortMapper.guessDefaultGateway("2001:db8::1"));
    }

    @Test
    void resultReportsAShareableEndpointOnlyWhenSomethingWasMapped() {
        CoopPortMapper.Result mapped = new CoopPortMapper.Result(CoopPortMapper.Tier.UPNP,
                "192.168.1.1", "Test Router", "203.0.113.7", 27015, false, "", true);
        assertTrue(mapped.mapped());
        assertEquals("203.0.113.7:27015", mapped.externalEndpoint());

        CoopPortMapper.Result failed = new CoopPortMapper.Result(CoopPortMapper.Tier.NONE,
                "", "", "", 0, false, "no gateway", true);
        assertFalse(failed.mapped());
        assertEquals("", failed.externalEndpoint());
    }

    @Test
    void namesEachMappingTierForTheDoctorBlock() {
        assertEquals("UPnP IGD", CoopPortMapper.describeTier(CoopPortMapper.Tier.UPNP));
        assertEquals("NAT-PMP", CoopPortMapper.describeTier(CoopPortMapper.Tier.NAT_PMP));
        assertEquals("PCP", CoopPortMapper.describeTier(CoopPortMapper.Tier.PCP));
        assertEquals("none", CoopPortMapper.describeTier(CoopPortMapper.Tier.NONE));
    }

    @Test
    void b5_theResultVersionChangesExactlyWhenTheResultDoes() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CoopPortMapper mapper = CoopPortMapper.startOffline(27015, clock::get);

        long start = mapper.resultVersion();
        assertEquals(start, mapper.resultVersion(), "an unchanged result must not move the version");

        int changes = 0;
        CoopPortMapper.Result seen = mapper.result();
        for (int i = 0; i < 500 && !mapper.result().finished(); i++) {
            mapper.tick(clock.addAndGet(100L));
            long version = mapper.resultVersion();
            if (!mapper.result().equals(seen)) {
                changes++;
                seen = mapper.result();
                assertTrue(version > start, "a changed result must bump the version");
                start = version;
            } else {
                assertEquals(start, version, "an unchanged result must not bump the version");
            }
        }

        // The point of the counter (red-team B5): the host's doctor block and the intel page's
        // reachability line were published once per session, so a renewal that later broke or
        // repaired the mapping was never reported and both surfaces kept showing minute one.
        assertTrue(changes > 0, "the mapper's verdict changed at least once on the way to giving up");
        assertEquals(start, mapper.resultVersion());
    }

    /**
     * net-11: a router whose WAN link is down answers GetExternalIPAddress with 0.0.0.0. That is not
     * an address anyone can connect to, and the doctor used to print "no - 0.0.0.0 is a public
     * address" under it.
     */
    @Test
    void net11_theUnspecifiedAddressIsNeitherPublicNorAnAnswer() {
        assertTrue(CoopPortMapper.isUnroutableExternalAddress("0.0.0.0"));
        assertTrue(CoopPortMapper.isUnroutableExternalAddress("0.1.2.3"));

        assertTrue(CoopPortMapper.isUnknownExternalAddress(null));
        assertTrue(CoopPortMapper.isUnknownExternalAddress(""));
        assertTrue(CoopPortMapper.isUnknownExternalAddress("  0.0.0.0 "));
        assertTrue(CoopPortMapper.isUnknownExternalAddress("0.255.255.255"));
        assertFalse(CoopPortMapper.isUnknownExternalAddress("203.0.113.7"));
        assertFalse(CoopPortMapper.isUnknownExternalAddress("10.0.0.1"));
    }

    /**
     * net-14: the LAN address drove both the SSDP multicast pin and the NAT-PMP gateway guess, and
     * was taken from whatever interface enumerated first — routinely a VirtualBox or Hyper-V switch
     * with no router behind it. It is now the source address the routing table would actually use.
     */
    @Test
    void net14_theLanAddressComesFromTheRoutingTableAndIsAlwaysOneOfOurs() throws Exception {
        assertEquals("127.0.0.1", CoopPortMapper.routedLocalIpv4("127.0.0.1"),
                "a UDP connect to loopback must report the loopback source address");

        String lan = CoopPortMapper.localLanIpv4();
        assertFalse(lan.startsWith("127."), "the LAN address must never be loopback, got " + lan);
        if (!lan.isEmpty()) {
            assertTrue(isLocalInterfaceAddress(lan), lan + " must be an address this machine holds");
        }
    }

    /**
     * net-30: the exchange assigned the channel to a final field and only then connected, so a
     * connect() that throws left an open socket nothing could ever close — one per renewal attempt.
     */
    @Test
    void net30_aConnectThatThrowsClosesTheChannelInsteadOfLeakingIt() throws Exception {
        SocketChannel channel = SocketChannel.open();
        try {
            assertThrows(UnresolvedAddressException.class, () -> CoopPortMapper.connectNonBlocking(
                    channel, InetSocketAddress.createUnresolved("router.invalid", 80)));
            assertFalse(channel.isOpen(), "the channel must be closed when connect() throws");
        } finally {
            channel.close();
        }
    }

    private static boolean isLocalInterfaceAddress(String address) throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface nic : Collections.list(interfaces)) {
            for (InetAddress candidate : Collections.list(nic.getInetAddresses())) {
                if (candidate.getHostAddress().equals(address)) {
                    return true;
                }
            }
        }
        return false;
    }
}
