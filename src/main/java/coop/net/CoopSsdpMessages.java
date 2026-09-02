package coop.net;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SSDP (Simple Service Discovery Protocol) request bytes and response header parsing for the UPnP
 * IGD discovery step of {@link CoopPortMapper}.
 *
 * <p>Why a unicast M-SEARCH rather than joining the multicast group: the search is sent <em>to</em>
 * 239.255.255.250:1900 from an ordinary ephemeral UDP socket, and every IGD answers by unicast back
 * to that socket's port. No {@code MembershipKey}, no interface selection ceremony, and nothing that
 * needs a privileged bind — which matters because this runs inside the game's script sandbox.
 */
public final class CoopSsdpMessages {
    public static final String MULTICAST_ADDRESS = "239.255.255.250";
    public static final int MULTICAST_PORT = 1900;

    /** IGD v1 and v2 search targets; routers answer one or the other, rarely both. */
    public static final String ST_IGD_V1 = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
    public static final String ST_IGD_V2 = "urn:schemas-upnp-org:device:InternetGatewayDevice:2";

    private CoopSsdpMessages() {
    }

    /**
     * The M-SEARCH datagram for one search target. {@code MX: 2} bounds how long a device may wait
     * before answering, so a 3 s discovery window covers a compliant device's worst case.
     */
    public static byte[] searchRequest(String searchTarget) {
        Objects.requireNonNull(searchTarget, "searchTarget");
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + MULTICAST_ADDRESS + ":" + MULTICAST_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + searchTarget + "\r\n"
                + "\r\n";
        return request.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses an SSDP response into lower-cased headers. SSDP borrows HTTP's header syntax, so this
     * delegates; the status line ({@code HTTP/1.1 200 OK}) is skipped like any HTTP response's.
     */
    public static Map<String, String> parseHeaders(String response) {
        Objects.requireNonNull(response, "response");
        return CoopHttpMessages.parseHeaderBlock(response);
    }

    /** {@code LOCATION} header (device descriptor URL), or {@code null} when absent. */
    public static String location(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        String location = headers.get("location");
        if (location == null) {
            location = headers.get("al"); // Legacy alternate-location header, still seen on old firmware.
        }
        if (location == null) {
            return null;
        }
        String trimmed = location.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Accepts any reply whose ST/NT/USN mentions an internet gateway device or one of its WAN
     * connection services. Deliberately loose: firmware answers a v1 search with a v2 ST, answers
     * with the service type instead of the device type, or capitalises differently — and every one
     * of those still has the {@code AddPortMapping} action we want.
     */
    public static boolean isInternetGatewayDevice(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        String haystack = (value(headers, "st") + " " + value(headers, "nt") + " " + value(headers, "usn"))
                .toLowerCase(Locale.ROOT);
        return haystack.contains("internetgatewaydevice")
                || haystack.contains("wanipconnection")
                || haystack.contains("wanpppconnection");
    }

    private static String value(Map<String, String> headers, String key) {
        String value = headers.get(key);
        return value == null ? "" : value;
    }
}
