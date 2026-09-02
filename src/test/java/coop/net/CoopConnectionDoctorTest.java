package coop.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopConnectionDoctorTest {
    private static final CoopConnectionDoctor.LocalAddresses PRIVATE_ONLY =
            new CoopConnectionDoctor.LocalAddresses(List.of("192.168.1.5"), List.of());
    private static final CoopConnectionDoctor.LocalAddresses WITH_GLOBAL_IPV6 =
            new CoopConnectionDoctor.LocalAddresses(List.of("192.168.1.5"), List.of("2001:db8::5"));
    private static final CoopConnectionDoctor.LocalAddresses PUBLIC_IPV4 =
            new CoopConnectionDoctor.LocalAddresses(List.of("203.0.113.9"), List.of());

    private static CoopPortMapper.Result mapped(String externalAddress) {
        return new CoopPortMapper.Result(CoopPortMapper.Tier.UPNP, "192.168.1.1", "Test Router (TR-9000)",
                externalAddress, 27015, CoopPortMapper.isUnroutableExternalAddress(externalAddress), "", true);
    }

    private static CoopPortMapper.Result failed(String failureText) {
        return new CoopPortMapper.Result(CoopPortMapper.Tier.NONE, "", "", "", 0, false, failureText, true);
    }

    @Test
    void everyReportStartsWithTheSameSearchableHeaderLine() {
        assertTrue(CoopConnectionDoctor.hostReport(27015, mapped("203.0.113.7"), PRIVATE_ONLY)
                .startsWith("Coop connection doctor:\n"));
        assertTrue(CoopConnectionDoctor.guestReport("203.0.113.7", 27015, true, true, 42)
                .startsWith("Coop connection doctor:\n"));
    }

    @Test
    void successfulMappingReportsTierThreeAndAShareableEndpoint() {
        String report = CoopConnectionDoctor.hostReport(27015, mapped("203.0.113.7"), PRIVATE_ONLY);

        assertEquals(3, CoopConnectionDoctor.reachedTier(mapped("203.0.113.7"), PRIVATE_ONLY));
        assertTrue(report.contains("tier reached      3 - automatic port mapping (UPnP IGD)"), report);
        assertTrue(report.contains("share with guest  203.0.113.7:27015"), report);
        assertTrue(report.contains("Test Router (TR-9000)"), report);
        assertTrue(report.contains("next step         none - give the guest 203.0.113.7:27015"), report);
        assertTrue(report.contains("CGNAT             no - 203.0.113.7 is a public address"), report);
    }

    @Test
    void carrierGradeNatIsNamedAndSendsTheHostToIpv6WhenThereIsIpv6() {
        String report = CoopConnectionDoctor.hostReport(27015, mapped("100.71.4.9"), WITH_GLOBAL_IPV6);

        assertTrue(report.contains("CGNAT             YES"), report);
        assertTrue(report.contains("second layer of NAT"), report);
        assertTrue(report.contains("CGNAT makes the mapped port unreachable"), report);
        assertTrue(report.contains("Use IPv6"), report);
        assertTrue(report.contains("2001:db8::5"), report);
        assertFalse(report.contains("share with guest  100.71.4.9"), report);
    }

    @Test
    void carrierGradeNatWithoutIpv6SendsTheHostToAVpn() {
        String report = CoopConnectionDoctor.hostReport(27015, mapped("100.71.4.9"), PRIVATE_ONLY);

        assertTrue(report.contains("Tailscale"), report);
        assertTrue(report.contains("docs/CONNECTIVITY.md"), report);
    }

    @Test
    void aPublicIpv4WithoutMappingIsTierTwo() {
        CoopPortMapper.Result result = failed("no UPnP gateway answered");

        assertEquals(2, CoopConnectionDoctor.reachedTier(result, PUBLIC_IPV4));
        String report = CoopConnectionDoctor.hostReport(27015, result, PUBLIC_IPV4);
        assertTrue(report.contains("tier reached      2 -"), report);
        assertTrue(report.contains("Forward TCP+UDP 27015 to 203.0.113.9"), report);
        assertTrue(report.contains("share with guest  203.0.113.9:27015"), report);
    }

    @Test
    void aGlobalIpv6WithoutMappingOrPublicIpv4IsTierOne() {
        CoopPortMapper.Result result = failed("no UPnP gateway answered");

        assertEquals(1, CoopConnectionDoctor.reachedTier(result, WITH_GLOBAL_IPV6));
        String report = CoopConnectionDoctor.hostReport(27015, result, WITH_GLOBAL_IPV6);
        assertTrue(report.contains("tier reached      1 - IPv6 direct is the working path"), report);
        assertTrue(report.contains("bare literal, no brackets"), report);
        assertTrue(report.contains("Allow TCP+UDP 27015 through the firewall"), report);
    }

    @Test
    void privateAddressesWithNoMappingReportTierZeroUnknownAndMentionTheVpnBlindSpot() {
        CoopPortMapper.Result result = failed("automatic port mapping disabled (coop.portMapping=off)");

        assertEquals(0, CoopConnectionDoctor.reachedTier(result, PRIVATE_ONLY));
        String report = CoopConnectionDoctor.hostReport(27015, result, PRIVATE_ONLY);
        assertTrue(report.contains("tier reached      0/unknown"), report);
        assertTrue(report.contains("Tailscale/ZeroTier this is expected"), report);
        assertTrue(report.contains("port mapping      none (automatic port mapping disabled"), report);
        assertTrue(report.contains("share with guest  nothing shareable yet"), report);
    }

    @Test
    void anUnfinishedMapperReadsAsStillNegotiating() {
        CoopPortMapper.Result pending =
                new CoopPortMapper.Result(CoopPortMapper.Tier.NONE, "", "", "", 0, false, "", false);

        assertTrue(CoopConnectionDoctor.hostReport(27015, pending, PRIVATE_ONLY)
                .contains("still negotiating with the router"));
    }

    @Test
    void guestReportNamesTheHealthyCase() {
        String report = CoopConnectionDoctor.guestReport("203.0.113.7", 27015, true, true, 48);

        assertTrue(report.contains("role              guest, connecting to 203.0.113.7:27015"), report);
        assertTrue(report.contains("TCP               up"), report);
        assertTrue(report.contains("UDP path          up - fleet state streams over UDP"), report);
        assertTrue(report.contains("RTT               48 ms"), report);
        assertTrue(report.contains("next step         none - the link is healthy."), report);
    }

    @Test
    void guestReportNamesABlockedUdpPathAsTheTcpFallbackCase() {
        String report = CoopConnectionDoctor.guestReport("203.0.113.7", 27015, true, false, 120);

        assertTrue(report.contains("UDP path          blocked - fleet state falls back to TCP"), report);
        assertTrue(report.contains("drops UDP"), report);
    }

    @Test
    void guestReportWithoutTcpDoesNotClaimToKnowAnythingAboutUdp() {
        String report = CoopConnectionDoctor.guestReport("203.0.113.7", 27015, false, false, -1);

        assertTrue(report.contains("TCP               DOWN"), report);
        assertTrue(report.contains("UDP path          unknown (TCP is not up yet)"), report);
        assertTrue(report.contains("RTT               not measured yet"), report);
        assertTrue(report.contains("The host port is not reachable"), report);
    }

    @Test
    void enumeratingThisMachineNeverThrowsAndNeverReportsLoopback() {
        CoopConnectionDoctor.LocalAddresses addresses = CoopConnectionDoctor.enumerateLocalAddresses();

        assertFalse(addresses.ipv4().contains("127.0.0.1"));
        assertFalse(addresses.globalIpv6().contains("::1"));
        for (String address : addresses.globalIpv6()) {
            assertFalse(address.contains("%"), "scope id must be stripped: " + address);
        }
    }

    @Test
    void publicIpv4DetectionUsesTheSameTableAsTheCgnatVerdict() {
        assertTrue(PUBLIC_IPV4.hasPublicIpv4());
        assertFalse(PRIVATE_ONLY.hasPublicIpv4());
        assertTrue(WITH_GLOBAL_IPV6.hasGlobalIpv6());
        assertFalse(PRIVATE_ONLY.hasGlobalIpv6());
    }
}
