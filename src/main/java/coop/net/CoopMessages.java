package coop.net;

import coop.handshake.CoopHandshakeManifest;
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
        HANDSHAKE_MANIFEST,
        HANDSHAKE_RESULT,
        SEED_LOCK_REQUEST,
        SEED_LOCK_ACK,
        SEED_LOCK_REJECT,
        TIME_SNAPSHOT,
        PAUSE_INTENT,
        FLEET_SNAPSHOT,
        INTERACTION_CLAIM,
        INTERACTION_ACCEPT,
        INTERACTION_REJECT,
        INTERACTION_RELEASE,
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

    public static Message handshakeManifest(long seq, long sentAtMillis, CoopHandshakeManifest manifest,
                                             boolean ironMode) {
        Objects.requireNonNull(manifest, "manifest");
        return new Message(Type.HANDSHAKE_MANIFEST, null, seq, sentAtMillis,
                "{\"manifestJson\":\"" + escapeJson(manifest.toJson()) + "\","
                        + "\"ironMode\":\"" + ironMode + "\"}");
    }

    public static Message handshakeResultAccept(long seq, long sentAtMillis, String sessionId) {
        String acceptedSessionId = requireText(sessionId, "sessionId");
        return new Message(Type.HANDSHAKE_RESULT, acceptedSessionId, seq, sentAtMillis,
                "{\"accepted\":\"true\","
                        + "\"sessionId\":\"" + escapeJson(acceptedSessionId) + "\","
                        + "\"diff\":\"\"}");
    }

    public static Message handshakeResultReject(long seq, long sentAtMillis, String diff) {
        return new Message(Type.HANDSHAKE_RESULT, null, seq, sentAtMillis,
                "{\"accepted\":\"false\","
                        + "\"sessionId\":\"\","
                        + "\"diff\":\"" + escapeJson(diff == null ? "" : diff) + "\"}");
    }

    public static Message seedLockRequest(String sessionId, long seq, long sentAtMillis, long seedLong,
                                          String seedString, String sectorFingerprint) {
        return new Message(Type.SEED_LOCK_REQUEST, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"seedLong\":" + seedLong + ","
                        + "\"seedString\":\"" + escapeJson(requireText(seedString, "seedString")) + "\","
                        + "\"sectorFingerprint\":\""
                        + escapeJson(requireText(sectorFingerprint, "sectorFingerprint")) + "\"}");
    }

    public static Message seedLockAck(String sessionId, long seq, long sentAtMillis, String sectorFingerprint) {
        return new Message(Type.SEED_LOCK_ACK, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"sectorFingerprint\":\""
                        + escapeJson(requireText(sectorFingerprint, "sectorFingerprint")) + "\"}");
    }

    public static Message seedLockReject(String sessionId, long seq, long sentAtMillis, String reason) {
        return new Message(Type.SEED_LOCK_REJECT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static Message timeSnapshot(String sessionId, long seq, boolean paused, boolean fastForward,
                                       long timestampMillis, long campaignDay, long sentAtMillis) {
        return new Message(Type.TIME_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"paused\":\"" + paused + "\","
                        + "\"fastForward\":\"" + fastForward + "\","
                        + "\"timestampMillis\":" + timestampMillis + ","
                        + "\"campaignDay\":" + campaignDay + ","
                        + "\"sentAtMillis\":" + sentAtMillis + "}");
    }

    /** Source of a guest pause intent: {@code KEY} = manual pause-key, {@code SCREEN} = open screen. */
    public enum PauseSource {
        KEY,
        SCREEN
    }

    /**
     * Phase 11 guest&rarr;host shared-pause intent (reliable TCP). {@code source} distinguishes the
     * overridable manual key pause from the non-overridable screen pause. {@code intentSeq} is a
     * monotonic per-guest sequence used by the host for last-writer-wins debounce, independent of the
     * network envelope {@code seq}.
     */
    public static Message pauseIntent(String sessionId, long seq, long sentAtMillis,
                                      PauseSource source, boolean paused, long intentSeq) {
        Objects.requireNonNull(source, "source");
        return new Message(Type.PAUSE_INTENT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"source\":\"" + source.name() + "\","
                        + "\"paused\":\"" + paused + "\","
                        + "\"intentSeq\":" + intentSeq + "}");
    }

    public static Message interactionClaim(String sessionId, long seq, long sentAtMillis,
                                           String entityId, String entityName, String playerId) {
        return new Message(Type.INTERACTION_CLAIM, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"entityName\":\"" + escapeJson(entityName == null ? "" : entityName) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    public static Message interactionAccept(String sessionId, long seq, long sentAtMillis,
                                            String entityId, String playerId, String entityName, long hostSeq) {
        return new Message(Type.INTERACTION_ACCEPT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\","
                        + "\"entityName\":\"" + escapeJson(entityName == null ? "" : entityName) + "\","
                        + "\"hostSeq\":" + hostSeq + "}");
    }

    public static Message interactionReject(String sessionId, long seq, long sentAtMillis,
                                            String entityId, String reason) {
        return new Message(Type.INTERACTION_REJECT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static Message interactionRelease(String sessionId, long seq, long sentAtMillis,
                                             String entityId, String playerId) {
        return new Message(Type.INTERACTION_RELEASE, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    public static Message disconnect(String sessionId, long seq, long sentAtMillis, String reason) {
        return new Message(Type.DISCONNECT, sessionId, seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    /**
     * UDP datagram envelope for high-frequency state streams (Phase 8 fleet snapshots, later combat
     * snapshots). Unlike the TCP {@link Message} line protocol this is not JSON: the body carries its
     * own compact encoding (e.g. {@link coop.fleet.CoopFleetSnapshot#encode()}), and the envelope is
     * just {@code sessionId} + {@code type} + {@code body} joined by a unit-separator that never
     * appears in those encodings. Datagrams are framed one-per-packet by UDP itself.
     */
    public record Datagram(String sessionId, Type type, String body) {
        public Datagram {
            type = Objects.requireNonNull(type, "type");
            sessionId = sessionId == null ? "" : sessionId;
            body = body == null ? "" : body;
        }
    }

    private static final char DATAGRAM_SEPARATOR = '\u001f';

    public static String datagram(String sessionId, Type type, String body) {
        Objects.requireNonNull(type, "type");
        return (sessionId == null ? "" : sessionId)
                + DATAGRAM_SEPARATOR + type.name()
                + DATAGRAM_SEPARATOR + (body == null ? "" : body);
    }

    public static Datagram parseDatagram(String raw) {
        Objects.requireNonNull(raw, "raw");
        int first = raw.indexOf(DATAGRAM_SEPARATOR);
        int second = first < 0 ? -1 : raw.indexOf(DATAGRAM_SEPARATOR, first + 1);
        if (first < 0 || second < 0) {
            throw new IllegalArgumentException("Malformed coop datagram envelope");
        }
        String sessionId = raw.substring(0, first);
        Type type = Type.valueOf(raw.substring(first + 1, second));
        String body = raw.substring(second + 1);
        return new Datagram(sessionId, type, body);
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

    public static long requiredPayloadLong(Message message, String name) {
        return requiredLong(decodePayload(message), name);
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
