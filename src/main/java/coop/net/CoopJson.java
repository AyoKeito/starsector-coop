package coop.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The mod's single JSON escaper and single strict parser.
 *
 * <p>There used to be three byte-identical copies of the escaper ({@code CoopMessages},
 * {@code CoopHandshakeManifest}, {@code CoopSessionStatsCodec}) and two near-identical
 * recursive-descent parsers. Three escapers that must agree byte-for-byte or the wire desyncs is
 * two escapers too many, so they live here.
 *
 * <p><b>This is not a general JSON library and must not become one.</b> {@code org.json} is on the
 * classpath and is the right tool for files on disk; it is deliberately not the tool for the wire,
 * because the wire's byte layout is a compatibility surface and a library upgrade must not be able
 * to move it. What is implemented here is exactly the grammar the two shipped parsers accepted
 * between them:
 *
 * <ul>
 *   <li><b>values</b> — object, array, string, integral number, {@code null}</li>
 *   <li><b>numbers</b> — optional {@code -} then digits, decoded as {@code Long}. No fraction, no
 *       exponent, no leading {@code +}. Neither shipped parser accepted those and nothing on the
 *       wire emits them: floats travel as quoted strings (see {@code marketTxn}'s
 *       {@code unitPrice}) precisely so that no float formatting difference can reach the wire.</li>
 *   <li><b>booleans</b> — <em>not</em> in the grammar. Neither shipped parser accepted {@code true}
 *       or {@code false}; booleans travel as the quoted strings {@code "true"} / {@code "false"}
 *       (see {@code linkStatus}'s {@code udpInboundOk}).</li>
 *   <li><b>escapes</b> — the seven two-character escapes plus {@code \}{@code uXXXX}. Anything else
 *       after a backslash is an error rather than a passthrough.</li>
 *   <li><b>whitespace</b> — skipped before and after every token.</li>
 *   <li><b>duplicate keys</b> — last one wins, because the map is a {@link LinkedHashMap} and the
 *       parser simply puts.</li>
 *   <li><b>trailing content</b> — rejected. The flat parser used to let {@code "{}}"} through by
 *       returning early on the empty object before it ran that check; the manifest parser rejected
 *       it. Reconciled in favour of the stricter one.</li>
 * </ul>
 *
 * <p>Values are handed back in the model both callers already expect: {@link String}, {@link Long},
 * {@code null}, {@link List} and {@link Map} (a {@link LinkedHashMap}, so field order survives).
 */
public final class CoopJson {

    /**
     * Nesting cap. The flat payload parser could not nest at all before this class existed, so
     * widening it to the manifest's grammar hands a hostile peer a stack-overflow lever it did not
     * have: recursion depth is attacker-chosen and {@code StackOverflowError} is an {@link Error},
     * which the pump's {@code IllegalArgumentException} handling does not catch. Real traffic is
     * four levels deep at its worst (manifest &rarr; mods &rarr; entry &rarr; checksums), so this is
     * an order of magnitude of headroom and still a hard ceiling.
     */
    private static final int MAX_DEPTH = 64;

    private CoopJson() {
    }

    /**
     * Escapes {@code value} for use inside a JSON string literal — quotes, backslash, the five named
     * control escapes, and {@code \}{@code u00xx} for every other C0 control. Everything at or above
     * {@code 0x20} passes through unchanged, which keeps non-ASCII on the wire as UTF-8 rather than
     * as escape sequences.
     */
    public static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Parses a document whose top level must be an object. Used by the envelope and by every payload:
     * a payload that is an array or a bare scalar is malformed by definition.
     *
     * @throws IllegalArgumentException on anything the grammar above does not accept
     */
    public static Map<String, Object> parseObject(String json) {
        Parser parser = new Parser(json);
        Map<String, Object> fields = parser.readObject(0);
        parser.finish();
        return fields;
    }

    /**
     * Parses a document whose top level may be any value. Used by the handshake manifest, which is a
     * nested object graph rather than the flat map the wire envelope uses.
     *
     * @throws IllegalArgumentException on anything the grammar above does not accept
     */
    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.readValue(0);
        parser.finish();
        return value;
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = Objects.requireNonNull(json, "json");
        }

        /** Whitespace then end-of-input; anything else is trailing content. */
        private void finish() {
            skipWhitespace();
            if (index != json.length()) {
                throw error("Trailing content");
            }
        }

        private Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting too deep");
            }
            skipWhitespace();
            if (peek('{')) {
                return readObject(depth);
            }
            if (peek('[')) {
                return readArray(depth);
            }
            if (peek('"')) {
                return readString();
            }
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            return readLong();
        }

        private Map<String, Object> readObject(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting too deep");
            }
            skipWhitespace();
            expect('{');
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return fields;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                fields.put(key, readValue(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                expect('}');
                return fields;
            }
        }

        private List<Object> readArray(int depth) {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return values;
            }
            while (true) {
                values.add(readValue(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                expect(']');
                return values;
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (index >= json.length()) {
                    throw error("Unterminated escape sequence");
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(readUnicodeEscape());
                    default -> throw error("Unsupported escape sequence: \\" + escaped);
                }
            }
            throw error("Unterminated string");
        }

        private char readUnicodeEscape() {
            if (index + 4 > json.length()) {
                throw error("Incomplete unicode escape");
            }
            String digits = json.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException ex) {
                throw error("Invalid unicode escape: " + digits);
            }
        }

        private Long readLong() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
            if (start == index || (json.charAt(start) == '-' && start + 1 == index)) {
                throw error("Expected number");
            }
            try {
                return Long.parseLong(json.substring(start, index));
            } catch (NumberFormatException ex) {
                throw error("Invalid long value");
            }
        }

        private void expect(char expected) {
            if (index >= json.length() || json.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private boolean startsWith(String value) {
            return json.startsWith(value, index);
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }
    }
}
