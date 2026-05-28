package coop.net;

import coop.session.CoopPlayerInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CoopMessages {
    private CoopMessages() {
    }

    public enum Type {
        HELLO,
        LOBBY_HELLO,
        LOBBY_ACCEPT,
        LOBBY_REJECT,
        PING,
        PONG,
        DISCONNECT
    }

    public record Message(Type type, String sessionId, long seq, long sentAtMillis, String payloadJson) {
        public Message {
            Objects.requireNonNull(type, "type");
            payloadJson = payloadJson == null ? "{}" : payloadJson;
        }
    }

    public static Message hello(String sessionId, long seq, long sentAtMillis, CoopConnectionRole role) {
        Objects.requireNonNull(role, "role");
        return new Message(Type.HELLO, sessionId, seq, sentAtMillis, "{\"role\":\"" + role.name() + "\"}");
    }

    public static Message ping(String sessionId, long seq, long sentAtMillis) {
        return new Message(Type.PING, sessionId, seq, sentAtMillis, "{}");
    }

    public static Message pong(String sessionId, long seq, long sentAtMillis, long pingSeq) {
        return new Message(Type.PONG, sessionId, seq, sentAtMillis, "{\"pingSeq\":" + pingSeq + "}");
    }

    public static Message lobbyHello(long seq, long sentAtMillis, CoopPlayerInfo playerInfo) {
        Objects.requireNonNull(playerInfo, "playerInfo");
        return new Message(Type.LOBBY_HELLO, null, seq, sentAtMillis,
                "{\"playerId\":\"" + escapeJson(playerInfo.playerId()) + "\","
                        + "\"playerName\":\"" + escapeJson(playerInfo.name()) + "\"}");
    }

    public static Message lobbyAccept(long seq, long sentAtMillis, String provisionalLobbyId,
                                      CoopPlayerInfo hostInfo) {
        Objects.requireNonNull(hostInfo, "hostInfo");
        return new Message(Type.LOBBY_ACCEPT, null, seq, sentAtMillis,
                "{\"provisionalLobbyId\":\"" + escapeJson(requireText(provisionalLobbyId, "provisionalLobbyId")) + "\","
                        + "\"hostPlayerId\":\"" + escapeJson(hostInfo.playerId()) + "\","
                        + "\"hostName\":\"" + escapeJson(hostInfo.name()) + "\"}");
    }

    public static Message lobbyReject(long seq, long sentAtMillis, String reason) {
        return new Message(Type.LOBBY_REJECT, null, seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static Message disconnect(String sessionId, long seq, long sentAtMillis, String reason) {
        return new Message(Type.DISCONNECT, sessionId, seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static String encode(Message message) {
        Objects.requireNonNull(message, "message");
        StringBuilder json = new StringBuilder(128);
        json.append('{');
        json.append("\"type\":\"").append(message.type().name()).append("\",");
        json.append("\"sessionId\":");
        appendNullableString(json, message.sessionId());
        json.append(',');
        json.append("\"seq\":").append(message.seq()).append(',');
        json.append("\"sentAtMillis\":").append(message.sentAtMillis()).append(',');
        json.append("\"payloadJson\":\"").append(escapeJson(message.payloadJson())).append("\"");
        json.append('}');
        return json.toString();
    }

    public static Message decode(String json) {
        Map<String, Object> fields = new Parser(json).parseObject();
        Type type = Type.valueOf(requiredString(fields, "type"));
        String sessionId = nullableString(fields, "sessionId");
        long seq = requiredLong(fields, "seq");
        long sentAtMillis = requiredLong(fields, "sentAtMillis");
        String payloadJson = requiredString(fields, "payloadJson");
        return new Message(type, sessionId, seq, sentAtMillis, payloadJson);
    }

    public static Map<String, Object> decodePayload(Message message) {
        Objects.requireNonNull(message, "message");
        return new Parser(message.payloadJson()).parseObject();
    }

    private static void appendNullableString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"').append(escapeJson(value)).append('"');
    }

    private static String requiredString(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalArgumentException("Missing string field: " + name);
    }

    public static String requiredPayloadString(Message message, String name) {
        return requiredString(decodePayload(message), name);
    }

    private static String nullableString(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalArgumentException("Expected nullable string field: " + name);
    }

    private static long requiredLong(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Long longValue) {
            return longValue;
        }
        throw new IllegalArgumentException("Missing long field: " + name);
    }

    private static String escapeJson(String value) {
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

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = Objects.requireNonNull(json, "json");
        }

        private Map<String, Object> parseObject() {
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
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                fields.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                expect('}');
                skipWhitespace();
                if (index != json.length()) {
                    throw error("Trailing content");
                }
                return fields;
            }
        }

        private Object parseValue() {
            if (peek('"')) {
                return parseString();
            }
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            return parseLong();
        }

        private String parseString() {
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
                    case 'u' -> value.append(parseUnicodeEscape());
                    default -> throw error("Unsupported escape sequence: \\" + escaped);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
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

        private Long parseLong() {
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
