package coop.net;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal HTTP/1.1 response reader for the UPnP control conversations in {@link CoopPortMapper}.
 *
 * <p>Why hand-rolled: the Starsector script classloader refuses {@code java.io.*}, which rules out
 * {@code HttpURLConnection}, {@code URL.openStream()} and every stream-based HTTP client in the JDK.
 * The mapper therefore speaks HTTP over a raw non-blocking {@link java.nio.channels.SocketChannel}
 * and hands the accumulated bytes here.
 *
 * <p>Why both framing modes: IGD firmware is inconsistent. Some routers answer the device-descriptor
 * GET with {@code Content-Length}, others chunk it, and a few send neither and just close the socket.
 * All three shapes are handled — {@link #isComplete} reports whether the framing already says the
 * body ended, and the caller falls back to end-of-stream when it does not.
 */
public final class CoopHttpMessages {
    private CoopHttpMessages() {
    }

    /** A parsed response. {@code headers} keys are lower-cased; {@code body} is decoded UTF-8. */
    public record Response(int statusCode, String reasonPhrase, Map<String, String> headers, String body) {
        public Response {
            Objects.requireNonNull(reasonPhrase, "reasonPhrase");
            Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(body, "body");
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** An absolute {@code http://} URL split into the pieces a {@code SocketChannel} needs. */
    public record Url(String host, int port, String path) {
        public Url {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(path, "path");
        }

        /** {@code host:port} as an HTTP {@code Host} header value, bracketing IPv6 literals. */
        public String authority() {
            String bracketed = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return bracketed + ":" + port;
        }
    }

    /**
     * Splits an absolute {@code http://} URL.
     *
     * <p>Hand-rolled rather than {@code java.net.URL} because that class drags in the stream
     * machinery the script sandbox blocks.
     *
     * <p><b>The host must be an IP literal</b> (red-team C4). Every URL that reaches this comes from
     * an unauthenticated LAN packet: the {@code LOCATION} header of an SSDP announcement, or a
     * {@code controlURL} inside the descriptor that announcement pointed at. A name there would be
     * resolved by {@code InetAddress.getByName} on the campaign thread, where a DNS lookup that takes
     * the resolver's timeout is a multi-second freeze of the game that anything on the LAN can
     * trigger. Real IGDs announce literals; a name is either a broken device or someone testing what
     * this code does with one, and the answer is to refuse the device and keep listening.
     *
     * @throws IllegalArgumentException when the URL is not an absolute http URL with a literal host
     */
    public static Url parseUrl(String url) {
        Objects.requireNonNull(url, "url");
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://")) {
            throw new IllegalArgumentException("Expected an absolute http:// URL, got: " + url);
        }
        String rest = trimmed.substring("http://".length());
        int pathStart = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        String path = pathStart < 0 ? "/" : rest.substring(pathStart);
        if (path.isEmpty()) {
            path = "/";
        }
        String host;
        int port = 80;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Unterminated IPv6 literal in URL: " + url);
            }
            host = authority.substring(1, close);
            String tail = authority.substring(close + 1);
            if (tail.startsWith(":")) {
                port = parsePort(tail.substring(1), url);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = parsePort(authority.substring(colon + 1), url);
            } else {
                host = authority;
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("URL has no host: " + url);
        }
        if (!isIpLiteral(host)) {
            throw new IllegalArgumentException("URL host must be an IP literal (no DNS on the campaign"
                    + " thread): " + url);
        }
        return new Url(host, port, path);
    }

    /**
     * Whether {@code host} is an IPv4 or IPv6 literal, decided by syntax alone. Deliberately not
     * {@code InetAddress.getByName}: for a name, that method <em>is</em> the DNS lookup this check
     * exists to prevent.
     */
    static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        int zone = host.indexOf('%');
        String bare = zone < 0 ? host : host.substring(0, zone);
        return bare.indexOf(':') >= 0 ? isIpv6Literal(bare) : isIpv4Literal(bare);
    }

    private static boolean isIpv4Literal(String host) {
        int octets = 0;
        int start = 0;
        while (start <= host.length()) {
            int dot = host.indexOf('.', start);
            String part = dot < 0 ? host.substring(start) : host.substring(start, dot);
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return false;
            }
            octets++;
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        return octets == 4;
    }

    private static boolean isIpv6Literal(String host) {
        // Structural check, not a full RFC 4291 parser: hex groups separated by colons, at most one
        // "::", an optional trailing IPv4 tail. Anything a resolver would have to think about is
        // rejected, which is the whole point.
        int doubleColon = host.indexOf("::");
        if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) {
            return false;
        }
        int groups = 0;
        int start = 0;
        boolean sawIpv4Tail = false;
        while (start <= host.length()) {
            int colon = host.indexOf(':', start);
            String part = colon < 0 ? host.substring(start) : host.substring(start, colon);
            if (!part.isEmpty()) {
                if (part.indexOf('.') >= 0) {
                    if (colon >= 0 || !isIpv4Literal(part)) {
                        return false;
                    }
                    sawIpv4Tail = true;
                    groups += 2;
                } else {
                    if (part.length() > 4) {
                        return false;
                    }
                    for (int i = 0; i < part.length(); i++) {
                        if (Character.digit(part.charAt(i), 16) < 0) {
                            return false;
                        }
                    }
                    groups++;
                }
            }
            if (colon < 0) {
                break;
            }
            start = colon + 1;
        }
        if (groups == 0 && doubleColon < 0) {
            return false;
        }
        if (sawIpv4Tail && groups > 8) {
            return false;
        }
        return doubleColon >= 0 ? groups <= 8 : groups == 8;
    }

    private static int parsePort(String value, String url) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("URL port out of range: " + url);
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed port in URL: " + url, ex);
        }
    }

    /**
     * Index of the first body byte, or {@code -1} when the header block has not fully arrived yet.
     * Tolerates bare-LF header terminators, which a few embedded HTTP stacks emit.
     */
    public static int bodyStart(byte[] data, int length) {
        Objects.requireNonNull(data, "data");
        for (int i = 0; i + 1 < length; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                return i + 2;
            }
            if (i + 3 < length
                    && data[i] == '\r' && data[i + 1] == '\n'
                    && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /**
     * {@code true} when the framing headers prove the whole body has arrived. {@code false} means
     * "keep reading" — either the headers are incomplete, or the response is delimited by connection
     * close and only end-of-stream ends it.
     */
    public static boolean isComplete(byte[] data, int length) {
        int start = bodyStart(data, length);
        if (start < 0) {
            return false;
        }
        Map<String, String> headers = parseHeaderBlock(headerText(data, start));
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return findChunkedEnd(data, start, length) >= 0;
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                return length - start >= Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return false;
    }

    /**
     * Parses a complete (or connection-closed) response.
     *
     * @throws IllegalArgumentException when the status line or header block is malformed
     */
    public static Response parse(byte[] data, int length) {
        int start = bodyStart(data, length);
        if (start < 0) {
            throw new IllegalArgumentException("HTTP response has no header terminator");
        }
        String headerText = headerText(data, start);
        int firstLineEnd = headerText.indexOf('\n');
        String statusLine = (firstLineEnd < 0 ? headerText : headerText.substring(0, firstLineEnd)).trim();
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
            throw new IllegalArgumentException("Malformed HTTP status line: " + statusLine);
        }
        int statusCode;
        try {
            statusCode = Integer.parseInt(statusParts[1].trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed HTTP status code: " + statusLine, ex);
        }
        String reason = statusParts.length > 2 ? statusParts[2].trim() : "";

        Map<String, String> headers = parseHeaderBlock(headerText);
        byte[] body;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = decodeChunked(data, start, length);
        } else {
            int bodyLength = length - start;
            String contentLength = headers.get("content-length");
            if (contentLength != null) {
                try {
                    bodyLength = Math.min(bodyLength, Integer.parseInt(contentLength.trim()));
                } catch (NumberFormatException ignored) {
                    // Bad Content-Length: fall back to "everything we read".
                }
            }
            body = new byte[Math.max(0, bodyLength)];
            System.arraycopy(data, start, body, 0, body.length);
        }
        return new Response(statusCode, reason, headers, new String(body, StandardCharsets.UTF_8));
    }

    /** Splits a header block (status line first) into lower-cased keys. Accepts CRLF and bare LF. */
    static Map<String, String> parseHeaderBlock(String headerText) {
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = headerText.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = stripTrailingCr(lines[i]);
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            // First occurrence wins: duplicated headers on IGD firmware are usually a repeat, not a list.
            headers.putIfAbsent(name, value);
        }
        return headers;
    }

    private static String headerText(byte[] data, int bodyStart) {
        return new String(data, 0, bodyStart, StandardCharsets.ISO_8859_1);
    }

    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** Index just past the terminating zero-length chunk, or {@code -1} if it has not arrived. */
    private static int findChunkedEnd(byte[] data, int start, int length) {
        int cursor = start;
        while (true) {
            int lineEnd = indexOfLf(data, cursor, length);
            if (lineEnd < 0) {
                return -1;
            }
            int size = parseChunkSize(data, cursor, lineEnd);
            if (size < 0) {
                return -1;
            }
            cursor = lineEnd + 1;
            if (size == 0) {
                // Trailer section ends at the next blank line; treat the chunk line as sufficient.
                return Math.min(cursor, length);
            }
            cursor += size;
            // Skip the CRLF that follows chunk data.
            int afterData = indexOfLf(data, Math.min(cursor, length), length);
            if (afterData < 0 || cursor > length) {
                return -1;
            }
            cursor = afterData + 1;
        }
    }

    private static byte[] decodeChunked(byte[] data, int start, int length) {
        byte[] out = new byte[Math.max(16, length - start)];
        int outLength = 0;
        int cursor = start;
        while (true) {
            int lineEnd = indexOfLf(data, cursor, length);
            if (lineEnd < 0) {
                break;
            }
            int size = parseChunkSize(data, cursor, lineEnd);
            if (size <= 0) {
                break;
            }
            cursor = lineEnd + 1;
            int available = Math.min(size, length - cursor);
            if (available <= 0) {
                break;
            }
            if (outLength + available > out.length) {
                byte[] grown = new byte[Math.max(out.length * 2, outLength + available)];
                System.arraycopy(out, 0, grown, 0, outLength);
                out = grown;
            }
            System.arraycopy(data, cursor, out, outLength, available);
            outLength += available;
            cursor += available;
            int afterData = indexOfLf(data, cursor, length);
            if (afterData < 0) {
                break;
            }
            cursor = afterData + 1;
        }
        byte[] trimmed = new byte[outLength];
        System.arraycopy(out, 0, trimmed, 0, outLength);
        return trimmed;
    }

    private static int indexOfLf(byte[] data, int from, int length) {
        for (int i = Math.max(0, from); i < length; i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Chunk-size line is hex, optionally followed by {@code ;extensions}. */
    private static int parseChunkSize(byte[] data, int from, int lineEnd) {
        String line = new String(data, from, Math.max(0, lineEnd - from), StandardCharsets.ISO_8859_1).trim();
        int semicolon = line.indexOf(';');
        if (semicolon >= 0) {
            line = line.substring(0, semicolon).trim();
        }
        if (line.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(line, 16);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
