package coop.net;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Hand-rolled SOAP for the three UPnP IGD actions {@link CoopPortMapper} needs:
 * {@code GetExternalIPAddress}, {@code AddPortMapping}, {@code DeletePortMapping}.
 *
 * <p>Why strings and not a SOAP stack: no XML library is available inside the script sandbox (they
 * all need {@code java.io}), and these three envelopes are fixed-shape documents with at most six
 * scalar arguments. Building them as text is smaller than any parser we could ship, and the wire
 * bytes end up log-diffable.
 *
 * <p>Error handling note: an IGD reports a refusal as HTTP 500 with a {@code UPnPError} body, not as
 * a transport failure. {@link #errorCode} pulls the number out so the mapper can act on the two that
 * matter — 718 ({@code ConflictInMappingEntry}: someone already owns that external port) and 725
 * ({@code OnlyPermanentLeasesSupported}: the router rejects any non-zero lease).
 */
public final class CoopUpnpSoap {
    /** External port already mapped to a different internal client. */
    public static final int ERROR_CONFLICT_IN_MAPPING_ENTRY = 718;
    /** Router supports permanent mappings only; retry with lease 0. */
    public static final int ERROR_ONLY_PERMANENT_LEASES = 725;

    private CoopUpnpSoap() {
    }

    public static String getExternalIpAddressBody(String serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        return envelope("GetExternalIPAddress", serviceType, "");
    }

    public static String addPortMappingBody(String serviceType,
                                            String protocol,
                                            int externalPort,
                                            int internalPort,
                                            String internalClient,
                                            String description,
                                            int leaseSeconds) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(internalClient, "internalClient");
        Objects.requireNonNull(description, "description");
        String arguments = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + requireProtocol(protocol) + "</NewProtocol>"
                + "<NewInternalPort>" + internalPort + "</NewInternalPort>"
                + "<NewInternalClient>" + escape(internalClient) + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + escape(description) + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>" + leaseSeconds + "</NewLeaseDuration>";
        return envelope("AddPortMapping", serviceType, arguments);
    }

    public static String deletePortMappingBody(String serviceType, String protocol, int externalPort) {
        Objects.requireNonNull(serviceType, "serviceType");
        String arguments = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + requireProtocol(protocol) + "</NewProtocol>";
        return envelope("DeletePortMapping", serviceType, arguments);
    }

    /**
     * Asks who owns an external port. The mapper runs this before acting on a 718, so a conflict is
     * never resolved by deleting a mapping some other machine on the LAN is relying on.
     */
    public static String getSpecificPortMappingEntryBody(String serviceType, String protocol, int externalPort) {
        Objects.requireNonNull(serviceType, "serviceType");
        String arguments = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + requireProtocol(protocol) + "</NewProtocol>";
        return envelope("GetSpecificPortMappingEntry", serviceType, arguments);
    }

    /** Full HTTP request bytes for a SOAP action against an absolute control URL. */
    public static byte[] httpRequest(String controlUrl, String serviceType, String action, String body) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(body, "body");
        CoopHttpMessages.Url url = CoopHttpMessages.parseUrl(controlUrl);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + url.path() + " HTTP/1.1\r\n"
                + "HOST: " + url.authority() + "\r\n"
                + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                + "SOAPACTION: \"" + serviceType + "#" + action + "\"\r\n"
                + "CONTENT-LENGTH: " + bodyBytes.length + "\r\n"
                + "CONNECTION: close\r\n"
                + "USER-AGENT: Starsector-coop/1.0 UPnP/1.1\r\n"
                + "\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] request = new byte[headBytes.length + bodyBytes.length];
        System.arraycopy(headBytes, 0, request, 0, headBytes.length);
        System.arraycopy(bodyBytes, 0, request, headBytes.length, bodyBytes.length);
        return request;
    }

    /** The plain HTTP GET the descriptor fetch uses. Kept here so both requests share a shape. */
    public static byte[] httpGet(String url) {
        CoopHttpMessages.Url parsed = CoopHttpMessages.parseUrl(url);
        String request = "GET " + parsed.path() + " HTTP/1.1\r\n"
                + "HOST: " + parsed.authority() + "\r\n"
                + "CONNECTION: close\r\n"
                + "USER-AGENT: Starsector-coop/1.0 UPnP/1.1\r\n"
                + "ACCEPT: text/xml\r\n"
                + "\r\n";
        return request.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code NewExternalIPAddress} from a {@code GetExternalIPAddress} response, or {@code null}. */
    public static String externalIpAddress(String responseBody) {
        return element(responseBody, "NewExternalIPAddress");
    }

    /** {@code NewInternalClient} of an existing mapping, or {@code null} when the body has none. */
    public static String internalClient(String responseBody) {
        return element(responseBody, "NewInternalClient");
    }

    /** {@code NewPortMappingDescription} of an existing mapping, or {@code null}. */
    public static String portMappingDescription(String responseBody) {
        return element(responseBody, "NewPortMappingDescription");
    }

    /** UPnP {@code errorCode} from a fault body, or {@code -1} when the body carries no fault. */
    public static int errorCode(String responseBody) {
        String value = element(responseBody, "errorCode");
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** UPnP {@code errorDescription} from a fault body, or {@code ""}. */
    public static String errorDescription(String responseBody) {
        String value = element(responseBody, "errorDescription");
        return value == null ? "" : value;
    }

    private static String envelope(String action, String serviceType, String arguments) {
        return "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + arguments
                + "</u:" + action + ">"
                + "</s:Body>"
                + "</s:Envelope>";
    }

    private static String requireProtocol(String protocol) {
        Objects.requireNonNull(protocol, "protocol");
        String upper = protocol.toUpperCase(Locale.ROOT);
        if (!upper.equals("TCP") && !upper.equals("UDP")) {
            throw new IllegalArgumentException("Protocol must be TCP or UDP, got: " + protocol);
        }
        return upper;
    }

    /** Namespace-prefix-tolerant element text lookup (bodies come back as {@code <u:...>} or bare). */
    private static String element(String xml, String name) {
        if (xml == null) {
            return null;
        }
        String lower = xml.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int open = lower.indexOf('<', from);
            if (open < 0) {
                return null;
            }
            int tagEnd = xml.indexOf('>', open);
            if (tagEnd < 0) {
                return null;
            }
            String tag = lower.substring(open + 1, tagEnd).trim();
            if (!tag.startsWith("/") && bareName(tag).equals(needle)) {
                int close = closeTagIndex(lower, tagEnd, needle);
                return close < 0 ? null : xml.substring(tagEnd + 1, close).trim();
            }
            from = tagEnd + 1;
        }
    }

    private static int closeTagIndex(String lowerXml, int from, String needle) {
        int cursor = from;
        while (true) {
            cursor = lowerXml.indexOf("</", cursor);
            if (cursor < 0) {
                return -1;
            }
            int tagEnd = lowerXml.indexOf('>', cursor);
            if (tagEnd < 0) {
                return -1;
            }
            if (bareName(lowerXml.substring(cursor + 2, tagEnd).trim()).equals(needle)) {
                return cursor;
            }
            cursor = tagEnd + 1;
        }
    }

    /** Strips a namespace prefix and any attributes from a lower-cased tag body. */
    private static String bareName(String tag) {
        String name = tag.endsWith("/") ? tag.substring(0, tag.length() - 1).trim() : tag;
        int space = name.indexOf(' ');
        if (space >= 0) {
            name = name.substring(0, space);
        }
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
