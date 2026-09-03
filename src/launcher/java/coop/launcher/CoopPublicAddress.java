package coop.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one outbound request the launcher makes, and only when the host asks for it: an IP-echo lookup
 * that fills the address field of an invite.
 *
 * <p>Two services, tried in order, five seconds each. Nothing is sent - the request carries no query
 * string and no body, and the answer is a bare address. A host on a VPN pseudo-LAN or a LAN-only
 * session overwrites the field by hand, which is why this is a button rather than something that
 * runs on startup.
 */
public final class CoopPublicAddress {

    /** Tried in order. Both answer with the caller's address as plain text and nothing else. */
    public static final List<String> ENDPOINTS =
            List.of("https://api.ipify.org", "https://icanhazip.com");

    private static final int TIMEOUT_MILLIS = 5000;
    private static final int MAX_RESPONSE_BYTES = 128;

    private static final Pattern IPV4 = Pattern.compile(
            "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    /** Exactly one of {@link #address()} and {@link #error()} is non-empty. */
    public record Lookup(String address, String error) {
        public Lookup {
            address = address == null ? "" : address;
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return !address.isEmpty();
        }
    }

    /** Fetches one URL's body as text. Separated out so tests never touch the network. */
    interface Fetcher {
        String get(String url) throws IOException;
    }

    private CoopPublicAddress() {
    }

    /** Runs the real lookup. Blocking; call it off the event dispatch thread. */
    public static Lookup lookup() {
        return lookup(ENDPOINTS, CoopPublicAddress::fetch);
    }

    /** Pure-ish core: endpoints in order, first valid literal wins. */
    static Lookup lookup(List<String> endpoints, Fetcher fetcher) {
        StringBuilder failures = new StringBuilder();
        for (String endpoint : endpoints) {
            String body;
            try {
                body = fetcher.get(endpoint);
            } catch (IOException | RuntimeException ex) {
                append(failures, endpoint + " (" + describe(ex) + ")");
                continue;
            }
            String trimmed = body == null ? "" : body.trim();
            if (isIpLiteral(trimmed)) {
                return new Lookup(trimmed, "");
            }
            append(failures, endpoint + " (answered something that is not an address)");
        }
        return new Lookup("",
                "Could not look up your public address, type it in. Tried " + failures + ".");
    }

    /** True for an IPv4 or IPv6 literal. Never resolves a name, so this cannot hit DNS. */
    static boolean isIpLiteral(String text) {
        if (text == null || text.isEmpty() || text.length() > 45) {
            return false;
        }
        if (IPV4.matcher(text).matches()) {
            for (String part : text.split("\\.")) {
                int value = Integer.parseInt(part);
                if (value > 255 || (part.length() > 1 && part.charAt(0) == '0')) {
                    return false;
                }
            }
            return true;
        }
        if (text.indexOf(':') < 0 || !IPV6_CHARS.matcher(text).matches()) {
            return false;
        }
        try {
            // Only reached for a string made of hex digits, colons and dots, which the resolver
            // parses as a literal rather than looking it up.
            InetAddress parsed = InetAddress.getByName(text);
            return parsed instanceof java.net.Inet6Address;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String fetch(String endpoint) throws IOException {
        URL url;
        try {
            url = URI.create(endpoint).toURL();
        } catch (RuntimeException ex) {
            throw new IOException("bad endpoint " + endpoint, ex);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("refusing a non-HTTPS lookup endpoint: " + endpoint);
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "starsector-coop-launcher");
        connection.setRequestProperty("Accept", "text/plain");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void append(StringBuilder failures, String text) {
        if (failures.length() > 0) {
            failures.append("; ");
        }
        failures.append(text);
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                : message;
    }
}
