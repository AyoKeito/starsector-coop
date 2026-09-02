package coop.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Formats the "why can't we connect" log block for both ends of a coop session.
 *
 * <p>The whole point is that one glance at either side's log answers the question. The host block
 * names the addresses the machine actually has, what the port mapper managed, whether the ISP is
 * doing carrier-grade NAT, the reachability tier that leaves them on, and the single next thing to
 * do. The guest block says whether TCP came up, whether UDP got through, and what the round trip is.
 *
 * <p>No engine dependency and no state: this class only reads {@link NetworkInterface} and formats
 * strings, so it is callable from anywhere and testable without a game or a socket.
 *
 * <p><strong>What it cannot tell you.</strong> A VPN pseudo-LAN (Tailscale, ZeroTier, Radmin) looks
 * exactly like an ordinary private LAN address from inside the process — there is no reliable way to
 * distinguish a Tailscale 100.x address from a CGNAT one, and no way to know the peer is on the same
 * overlay. So when mapping is off or failed and all we have is a private address, the tier is
 * reported as "0/unknown" rather than guessed: a working VPN session and a broken direct one produce
 * the same evidence.
 */
public final class CoopConnectionDoctor {
    private static final String HEADER = "Coop connection doctor:";
    private static final String INDENT = "  ";

    private CoopConnectionDoctor() {
    }

    /** What the machine's interfaces say about it. Split out so the formatting is unit-testable. */
    public record LocalAddresses(List<String> ipv4, List<String> globalIpv6) {
        public LocalAddresses {
            ipv4 = List.copyOf(Objects.requireNonNull(ipv4, "ipv4"));
            globalIpv6 = List.copyOf(Objects.requireNonNull(globalIpv6, "globalIpv6"));
        }

        public boolean hasGlobalIpv6() {
            return !globalIpv6.isEmpty();
        }

        /** True when one of the bound IPv4 addresses is itself Internet-routable (no NAT in front). */
        public boolean hasPublicIpv4() {
            return ipv4.stream().anyMatch(address -> !CoopPortMapper.isUnroutableExternalAddress(address));
        }
    }

    /** Host-side block, enumerating this machine's addresses. */
    public static String hostReport(int port, CoopPortMapper.Result result) {
        return hostReport(port, result, enumerateLocalAddresses());
    }

    static String hostReport(int port, CoopPortMapper.Result result, LocalAddresses addresses) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(addresses, "addresses");

        boolean mapped = result.mapped();
        int tier = reachedTier(result, addresses);

        StringBuilder report = new StringBuilder(HEADER);
        line(report, "role", "host, listening on port " + port + " (TCP+UDP)");
        line(report, "local IPv4", addresses.ipv4().isEmpty() ? "none" : String.join(", ", addresses.ipv4()));
        line(report, "global IPv6", addresses.hasGlobalIpv6()
                ? String.join(", ", addresses.globalIpv6())
                : "none (no IPv6, or only link-local/private addresses)");
        line(report, "port mapping", describeMapping(result));
        line(report, "CGNAT", describeCgnat(result));
        line(report, "tier reached", describeTier(tier, result));
        line(report, "share with guest", mapped && !result.cgnat()
                ? result.externalEndpoint()
                : shareFallback(addresses, port));
        line(report, "next step", nextStep(tier, result, addresses, port));
        return report.toString();
    }

    /** Guest-side block, written once the connection attempt has settled. */
    public static String guestReport(String host, int port, boolean tcpUp, boolean udpPathUp, long rttMillis) {
        Objects.requireNonNull(host, "host");

        StringBuilder report = new StringBuilder(HEADER);
        line(report, "role", "guest, connecting to " + host + ":" + port);
        line(report, "TCP", tcpUp ? "up" : "DOWN - no connection to the host");
        line(report, "UDP path", !tcpUp
                ? "unknown (TCP is not up yet)"
                : udpPathUp
                        ? "up - fleet state streams over UDP"
                        : "blocked - fleet state falls back to TCP; movement will be less smooth");
        line(report, "RTT", rttMillis < 0 ? "not measured yet" : rttMillis + " ms");
        line(report, "next step", guestNextStep(host, port, tcpUp, udpPathUp));
        return report.toString();
    }

    /**
     * Tier the host ended up on. See {@code docs/CONNECTIVITY.md} for what each one means; 0 is the
     * honest "we cannot tell from here" answer, which includes a working VPN.
     */
    static int reachedTier(CoopPortMapper.Result result, LocalAddresses addresses) {
        if (result.mapped()) {
            return 3;
        }
        if (addresses.hasPublicIpv4()) {
            return 2;
        }
        if (addresses.hasGlobalIpv6()) {
            return 1;
        }
        return 0;
    }

    private static String describeMapping(CoopPortMapper.Result result) {
        if (result.mapped()) {
            String gateway = result.gatewayAddress().isEmpty() ? "" : " via " + result.gatewayAddress();
            String name = result.gatewayName().isEmpty() ? "" : " \"" + result.gatewayName() + "\"";
            return CoopPortMapper.describeTier(result.tier()) + gateway + name
                    + " - external " + result.externalEndpoint();
        }
        if (!result.finished()) {
            return "still negotiating with the router";
        }
        return "none (" + (result.failureText().isEmpty() ? "not attempted" : result.failureText()) + ")";
    }

    private static String describeCgnat(CoopPortMapper.Result result) {
        if (result.cgnat()) {
            return "YES - the router's own \"external\" address " + result.externalAddress()
                    + " is private. Your ISP is doing a second layer of NAT; no port forward"
                    + " of any kind can reach you over IPv4.";
        }
        if (result.externalAddress().isEmpty()) {
            return "unknown (no external address was discovered)";
        }
        return "no - " + result.externalAddress() + " is a public address";
    }

    private static String describeTier(int tier, CoopPortMapper.Result result) {
        return switch (tier) {
            case 3 -> "3 - automatic port mapping (" + CoopPortMapper.describeTier(result.tier()) + ")"
                    + (result.cgnat() ? ", but CGNAT makes the mapped port unreachable" : "");
            case 2 -> "2 - this machine holds a public IPv4 address; forward the port or open the firewall";
            case 1 -> "1 - IPv6 direct is the working path";
            default -> "0/unknown - only private addresses and no mapping."
                    + " A VPN pseudo-LAN (tier 0) looks identical from here, so if you are on"
                    + " Tailscale/ZeroTier this is expected and fine.";
        };
    }

    private static String shareFallback(LocalAddresses addresses, int port) {
        if (addresses.hasGlobalIpv6()) {
            return addresses.globalIpv6().get(0) + " port " + port + " (IPv6; guest sets coop.connectHost"
                    + " to the bare literal, no brackets)";
        }
        if (addresses.hasPublicIpv4()) {
            return publicIpv4(addresses) + ":" + port;
        }
        return "nothing shareable yet - see docs/CONNECTIVITY.md";
    }

    private static String nextStep(int tier, CoopPortMapper.Result result, LocalAddresses addresses, int port) {
        if (tier == 3 && !result.cgnat()) {
            return "none - give the guest " + result.externalEndpoint() + " and start the session.";
        }
        if (result.cgnat()) {
            return addresses.hasGlobalIpv6()
                    ? "IPv4 is a dead end behind CGNAT. Use IPv6: allow TCP+UDP " + port
                    + " through the firewall and give the guest " + addresses.globalIpv6().get(0) + "."
                    : "IPv4 is a dead end behind CGNAT and there is no IPv6 here."
                    + " Use a VPN pseudo-LAN (Tailscale) - docs/CONNECTIVITY.md tier 0.";
        }
        if (tier == 2) {
            return "Forward TCP+UDP " + port + " to " + publicIpv4(addresses)
                    + " on the router, or allow it through this machine's firewall if there is no NAT.";
        }
        if (tier == 1) {
            return "Allow TCP+UDP " + port + " through the firewall for IPv6 and give the guest "
                    + addresses.globalIpv6().get(0) + ".";
        }
        return "Automatic mapping did not work. Forward TCP+UDP " + port + " manually on the router"
                + " (docs/CONNECTIVITY.md tier 2), or use a VPN pseudo-LAN (tier 0).";
    }

    private static String guestNextStep(String host, int port, boolean tcpUp, boolean udpPathUp) {
        if (!tcpUp) {
            return "The host port is not reachable. Check that the host shared the address this log names ("
                    + host + ":" + port + "), and that their tier is not 0 - docs/CONNECTIVITY.md.";
        }
        if (!udpPathUp) {
            return "Something between the two machines drops UDP. The session works over TCP;"
                    + " if mirrors look choppy, both sides should try a VPN pseudo-LAN (tier 0).";
        }
        return "none - the link is healthy.";
    }

    private static String publicIpv4(LocalAddresses addresses) {
        for (String address : addresses.ipv4()) {
            if (!CoopPortMapper.isUnroutableExternalAddress(address)) {
                return address;
            }
        }
        return "";
    }

    private static void line(StringBuilder report, String label, String value) {
        report.append('\n').append(INDENT).append(pad(label)).append(value);
    }

    private static String pad(String label) {
        StringBuilder padded = new StringBuilder(label);
        while (padded.length() < 18) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Reads the machine's own addresses. Excludes loopback, link-local and IPv6 unique-local
     * ({@code fc00::/7}) — none of those are anything a guest on another network could dial.
     */
    public static LocalAddresses enumerateLocalAddresses() {
        List<String> ipv4 = new ArrayList<>();
        List<String> ipv6 = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address) {
                        ipv4.add(address.getHostAddress());
                    } else if (address instanceof Inet6Address ipv6Address && isGlobalIpv6(ipv6Address)) {
                        ipv6.add(stripScope(ipv6Address.getHostAddress()));
                    }
                }
            }
        } catch (Exception ignored) {
            // A machine we cannot enumerate reports as "none", which the tier logic already handles.
        }
        return new LocalAddresses(ipv4, ipv6);
    }

    static boolean isGlobalIpv6(Inet6Address address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        // fc00::/7 unique-local: routable inside one site, never across the Internet.
        int firstByte = address.getAddress()[0] & 0xFF;
        return (firstByte & 0xFE) != 0xFC;
    }

    static String stripScope(String hostAddress) {
        int percent = hostAddress.indexOf('%');
        return percent < 0 ? hostAddress : hostAddress.substring(0, percent);
    }
}
