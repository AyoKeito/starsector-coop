package coop.net;

import java.util.Locale;
import java.util.Objects;

/**
 * String-scanner for the UPnP device descriptor XML that an IGD serves at its SSDP {@code LOCATION}.
 *
 * <p>Why a string scan and not an XML parser: the mod cannot bundle an XML library and the JDK's
 * parsers all want {@code java.io} streams, which the Starsector script classloader blocks. The
 * shape we need is tiny and rigid — one {@code <service>} element whose {@code serviceType} names
 * {@code WANIPConnection} or {@code WANPPPConnection}, and that element's {@code controlURL} — so a
 * scan is both sufficient and immune to the namespace-prefix variation IGD firmware indulges in.
 *
 * <p>Document order decides which service wins when a descriptor advertises both. Real gateways
 * expose exactly one WAN connection service per WAN device (IP for Ethernet/DHCP uplinks, PPP for
 * PPPoE), and the first one listed is the one on the active WAN device.
 */
public final class CoopUpnpDescriptor {
    private CoopUpnpDescriptor() {
    }

    /** The WAN connection service we drive, with its control URL already made absolute. */
    public record Service(String serviceType, String controlUrl) {
        public Service {
            Objects.requireNonNull(serviceType, "serviceType");
            Objects.requireNonNull(controlUrl, "controlUrl");
        }
    }

    /**
     * Everything the connection doctor and the mapper need from the descriptor.
     *
     * @param service {@code null} when the descriptor advertises no WAN connection service — the
     *                gateway is a UPnP device of some other kind and cannot map ports
     */
    public record Descriptor(String friendlyName, String modelName, Service service) {
        public Descriptor {
            Objects.requireNonNull(friendlyName, "friendlyName");
            Objects.requireNonNull(modelName, "modelName");
        }

        /** Human label for logs: friendly name, model name, or {@code ""} when the router gave neither. */
        public String displayName() {
            if (!friendlyName.isEmpty() && !modelName.isEmpty() && !friendlyName.equals(modelName)) {
                return friendlyName + " (" + modelName + ")";
            }
            if (!friendlyName.isEmpty()) {
                return friendlyName;
            }
            return modelName;
        }
    }

    /**
     * @param xml           descriptor body as served
     * @param descriptorUrl the absolute URL the body came from; relative control URLs resolve
     *                      against {@code URLBase} when present, else against this
     */
    public static Descriptor parse(String xml, String descriptorUrl) {
        Objects.requireNonNull(xml, "xml");
        Objects.requireNonNull(descriptorUrl, "descriptorUrl");

        String urlBase = elementValue(xml, "URLBase", 0);
        String base = urlBase == null || urlBase.isEmpty() ? descriptorUrl : urlBase;

        return new Descriptor(
                orEmpty(elementValue(xml, "friendlyName", 0)),
                orEmpty(elementValue(xml, "modelName", 0)),
                findWanService(xml, base));
    }

    private static Service findWanService(String xml, String base) {
        int cursor = 0;
        while (cursor < xml.length()) {
            int hit = min(indexOfIgnoreCase(xml, "wanipconnection", cursor),
                    indexOfIgnoreCase(xml, "wanpppconnection", cursor));
            if (hit < 0) {
                return null;
            }
            // Bound the scan to the <service> element that mentions it, so a stray mention in a URL
            // or an SCPD path cannot pair one service's type with another's control URL.
            int serviceStart = lastServiceOpenTag(xml, hit);
            int serviceEnd = nextServiceCloseTag(xml, hit);
            if (serviceStart >= 0 && serviceEnd > serviceStart) {
                String block = xml.substring(serviceStart, serviceEnd);
                String serviceType = elementValue(block, "serviceType", 0);
                String controlUrl = elementValue(block, "controlURL", 0);
                if (serviceType != null && controlUrl != null && !controlUrl.isEmpty() && isWanConnection(serviceType)) {
                    return new Service(serviceType, resolve(base, controlUrl));
                }
            }
            cursor = hit + 1;
        }
        return null;
    }

    private static boolean isWanConnection(String serviceType) {
        String lower = serviceType.toLowerCase(Locale.ROOT);
        return lower.contains("wanipconnection") || lower.contains("wanpppconnection");
    }

    /**
     * Makes a possibly-relative control URL absolute. Handles the three shapes firmware emits:
     * already absolute, root-relative ({@code /ctl/IPConn}) and base-relative ({@code ctl/IPConn}).
     */
    public static String resolve(String baseUrl, String reference) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(reference, "reference");
        String ref = reference.trim();
        if (ref.toLowerCase(Locale.ROOT).startsWith("http://")
                || ref.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return ref;
        }
        CoopHttpMessages.Url base = CoopHttpMessages.parseUrl(baseUrl);
        String origin = "http://" + base.authority();
        if (ref.startsWith("/")) {
            return origin + ref;
        }
        String basePath = base.path();
        int lastSlash = basePath.lastIndexOf('/');
        String directory = lastSlash < 0 ? "/" : basePath.substring(0, lastSlash + 1);
        return origin + directory + ref;
    }

    /**
     * Text of the first {@code <name>} element at or after {@code from}; namespace prefixes tolerated,
     * so {@code <dev:friendlyName>} matches {@code friendlyName}.
     *
     * <p>Every index here is an index into {@code xml} itself. The scan used to search a lower-cased
     * copy and apply those offsets to the original (red-team net-31), which silently misaligns as soon
     * as lower-casing changes the string's length — {@code "İ"} folds to two chars — and then extracts
     * a value with a fragment of the closing tag glued on, or misses the element entirely.
     */
    private static String elementValue(String xml, String name, int from) {
        int cursor = Math.max(0, from);
        while (true) {
            int open = xml.indexOf('<', cursor);
            if (open < 0) {
                return null;
            }
            int tagEnd = xml.indexOf('>', open);
            if (tagEnd < 0) {
                return null;
            }
            String tag = xml.substring(open + 1, tagEnd).trim();
            if (isOpeningTagFor(tag, name)) {
                if (tag.endsWith("/")) {
                    return ""; // <friendlyName/>: present but empty.
                }
                int close = closeTagIndex(xml, tagEnd + 1, name);
                return close < 0 ? null : xml.substring(tagEnd + 1, close).trim();
            }
            cursor = tagEnd + 1;
        }
    }

    /** Index of the matching {@code </name>} at or after {@code from}, or {@code -1}. */
    private static int closeTagIndex(String xml, int from, String name) {
        int cursor = from;
        while (true) {
            cursor = xml.indexOf("</", cursor);
            if (cursor < 0) {
                return -1;
            }
            int tagEnd = xml.indexOf('>', cursor);
            if (tagEnd < 0) {
                return -1;
            }
            if (bareName(xml.substring(cursor + 2, tagEnd).trim()).equalsIgnoreCase(name)) {
                return cursor;
            }
            cursor = tagEnd + 1;
        }
    }

    /** Start of the last {@code <service>} opening tag before {@code limit}, or {@code -1}. */
    private static int lastServiceOpenTag(String xml, int limit) {
        int best = -1;
        int cursor = 0;
        while (true) {
            int open = xml.indexOf('<', cursor);
            if (open < 0 || open >= limit) {
                return best;
            }
            int tagEnd = xml.indexOf('>', open);
            if (tagEnd < 0) {
                return best;
            }
            if (isOpeningTagFor(xml.substring(open + 1, tagEnd).trim(), "service")) {
                best = open;
            }
            cursor = tagEnd + 1;
        }
    }

    /** Index of the first {@code </service>} at or after {@code from}, or {@code -1}. */
    private static int nextServiceCloseTag(String xml, int from) {
        return closeTagIndex(xml, from, "service");
    }

    /** True for an opening (not closing, not declaration) tag whose local name is {@code name}. */
    private static boolean isOpeningTagFor(String tag, String name) {
        if (tag.isEmpty() || tag.charAt(0) == '/' || tag.charAt(0) == '?' || tag.charAt(0) == '!') {
            return false;
        }
        return bareName(tag).equalsIgnoreCase(name);
    }

    /** Strips a namespace prefix, attributes and any self-closing slash from a tag body. */
    private static String bareName(String tag) {
        String name = tag.endsWith("/") ? tag.substring(0, tag.length() - 1).trim() : tag;
        int cut = name.length();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                cut = i;
                break;
            }
        }
        name = name.substring(0, cut);
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** Length-preserving case-insensitive search, so the index means the same in both cases. */
    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int last = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int min(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
