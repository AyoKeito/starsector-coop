package coop.launcher;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * The one line a host hands a guest.
 *
 * <pre>
 *   coop://&lt;host&gt;:&lt;port&gt;/?seed=&lt;seed&gt;&amp;pw=&lt;url-encoded password&gt;
 * </pre>
 *
 * <p>IPv6 hosts are bracketed, the password is URL-encoded UTF-8 and left out entirely when there is
 * none, and the seed is left out when the host has not pinned one. Hand-parsed rather than run
 * through {@link java.net.URI}: the failure messages have to name the part that broke, and a URI
 * parse failure names a character offset.
 */
public record CoopInvite(String host, int port, String seed, String password) {

    /** Scheme, lower case, including the separator. */
    public static final String SCHEME = "coop://";

    public CoopInvite {
        host = trim(host);
        seed = trim(seed);
        password = password == null ? "" : password;
    }

    /** A parse attempt: exactly one of {@link #invite()} and {@link #error()} is set. */
    public record Parsed(CoopInvite invite, String error) {
        public boolean ok() {
            return invite != null;
        }
    }

    /**
     * Renders an invite. Throws on a host or port that could not round-trip, because producing a
     * broken invite is a bug here, not something the guest should have to diagnose.
     */
    public static String format(String host, int port, String seed, String password) {
        String cleanHost = trim(host);
        if (cleanHost.isEmpty()) {
            throw new IllegalArgumentException("an invite needs a host address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 1..65535");
        }
        StringBuilder text = new StringBuilder(SCHEME);
        text.append(bracketIfIpv6(cleanHost)).append(':').append(port).append("/?");
        boolean first = true;
        String cleanSeed = trim(seed);
        if (!cleanSeed.isEmpty()) {
            text.append("seed=").append(encode(cleanSeed));
            first = false;
        }
        String cleanPassword = password == null ? "" : password;
        if (!cleanPassword.isEmpty()) {
            if (!first) {
                text.append('&');
            }
            text.append("pw=").append(encode(cleanPassword));
        }
        // Trailing "?" with nothing after it is legal and round-trips, but it reads like a mistake
        // on a line someone pastes into a chat window.
        if (text.charAt(text.length() - 1) == '?') {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    /** Convenience for a value already in hand. */
    public String format() {
        return format(host, port, seed, password);
    }

    /**
     * Parses an invite, tolerating whitespace around it. On failure the message names the part that
     * broke, so a guest who pasted half a line is told which half.
     */
    public static Parsed parse(String text) {
        String raw = trim(text);
        if (raw.isEmpty()) {
            return fail("there is nothing to paste; the clipboard is empty");
        }
        if (raw.length() < SCHEME.length()
                || !raw.substring(0, SCHEME.length()).toLowerCase(Locale.ROOT).equals(SCHEME)) {
            return fail("this is not a co-op invite; it has to start with " + SCHEME);
        }
        String rest = raw.substring(SCHEME.length());
        String query = "";
        int questionMark = rest.indexOf('?');
        if (questionMark >= 0) {
            query = rest.substring(questionMark + 1);
            rest = rest.substring(0, questionMark);
        }
        // The "/" between the authority and the query is optional on the way in.
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            String tail = rest.substring(slash + 1);
            if (!tail.isEmpty()) {
                return fail("the address part has an unexpected \"/" + tail + "\" in it");
            }
            rest = rest.substring(0, slash);
        }
        if (rest.isEmpty()) {
            return fail("the address part is missing");
        }

        String host;
        String portText;
        if (rest.charAt(0) == '[') {
            int close = rest.indexOf(']');
            if (close < 0) {
                return fail("the IPv6 address is missing its closing \"]\"");
            }
            host = rest.substring(1, close);
            String after = rest.substring(close + 1);
            if (after.isEmpty()) {
                return fail("the port is missing; an invite looks like " + SCHEME
                        + "[2001:db8::1]:7777/");
            }
            if (after.charAt(0) != ':') {
                return fail("expected \":\" and a port after the IPv6 address, found \"" + after + "\"");
            }
            portText = after.substring(1);
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon < 0) {
                return fail("the port is missing; an invite looks like " + SCHEME + "203.0.113.9:7777/");
            }
            if (rest.indexOf(':') != colon) {
                return fail("this looks like a bare IPv6 address; it has to be in brackets, as in "
                        + SCHEME + "[" + rest + "]:7777/");
            }
            host = rest.substring(0, colon);
            portText = rest.substring(colon + 1);
        }
        if (host.isEmpty()) {
            return fail("the host address is empty");
        }
        if (portText.isEmpty()) {
            return fail("the port is missing");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            return fail("the port \"" + portText + "\" is not a number");
        }
        if (port < 1 || port > 65535) {
            return fail("the port " + port + " is outside the range 1 to 65535");
        }

        String seed = "";
        String password = "";
        if (!query.isEmpty()) {
            for (String pair : query.split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                String decoded;
                try {
                    decoded = decode(value);
                } catch (RuntimeException ex) {
                    return fail("the \"" + name + "\" part of the invite is not readable ("
                            + ex.getMessage() + ")");
                }
                switch (name.toLowerCase(Locale.ROOT)) {
                    case "seed" -> seed = decoded;
                    case "pw" -> password = decoded;
                    default -> {
                        return fail("the invite carries an unknown setting \"" + name + "\"");
                    }
                }
            }
        }
        String seedProblem = CoopSeeds.validate(seed);
        if (seedProblem != null) {
            return fail("the seed \"" + seed + "\" is not usable: " + seedProblem);
        }
        return new Parsed(new CoopInvite(host, port, seed, password), null);
    }

    private static Parsed fail(String reason) {
        return new Parsed(null, reason);
    }

    /** Wraps a bare IPv6 literal in brackets; leaves everything else alone. */
    static String bracketIfIpv6(String host) {
        if (host.startsWith("[")) {
            return host;
        }
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            // UTF-8 is required of every JVM; this cannot happen.
            throw new IllegalStateException(ex);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        // Never the password: this string ends up in the launcher log.
        return "CoopInvite[" + Objects.toString(host) + ":" + port + " seed=" + seed
                + " password=" + (password.isEmpty() ? "none" : "set") + "]";
    }
}
