package coop.handshake;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import coop.build.CoopBuildInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CoopHandshakeManifest(
        String gameVersion,
        String coopBuildVersion,
        String coopGitCommit,
        List<ModEntry> enabledMods
) {
    public CoopHandshakeManifest {
        gameVersion = requireText(gameVersion, "gameVersion");
        coopBuildVersion = normalize(coopBuildVersion, "dev");
        coopGitCommit = normalize(coopGitCommit, "dev-uncommitted");
        enabledMods = enabledMods == null ? List.of() : enabledMods.stream()
                .sorted(Comparator.comparing(ModEntry::id))
                .toList();
    }

    public static CoopHandshakeManifest capture() {
        String gameVersion = Global.getSettings().getGameVersion();
        List<ModEntry> mods = Global.getSettings().getModManager().getEnabledModsCopy().stream()
                .map(CoopHandshakeManifest::fromModSpec)
                .toList();
        return new CoopHandshakeManifest(gameVersion, captureBuildVersion(), captureGitCommit(), mods);
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendField(json, "gameVersion", gameVersion);
        json.append(',');
        appendField(json, "coopBuildVersion", coopBuildVersion);
        json.append(',');
        appendField(json, "coopGitCommit", coopGitCommit);
        json.append(",\"enabledMods\":[");
        for (int i = 0; i < enabledMods.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            enabledMods.get(i).appendJson(json);
        }
        json.append("]}");
        return json.toString();
    }

    @SuppressWarnings("unchecked")
    public static CoopHandshakeManifest fromJson(String json) {
        Object parsed = new JsonParser(json).parse();
        Map<String, Object> object = requireObject(parsed, "manifest");
        List<ModEntry> mods = new ArrayList<>();
        for (Object modObject : requireList(object.get("enabledMods"), "enabledMods")) {
            Map<String, Object> mod = requireObject(modObject, "enabledMods[]");
            List<String> jars = new ArrayList<>();
            for (Object jar : requireList(mod.get("jars"), "jars")) {
                jars.add(requireString(jar, "jars[]"));
            }

            LinkedHashMap<String, String> checksums = new LinkedHashMap<>();
            Map<String, Object> checksumObject = requireObject(mod.get("checksums"), "checksums");
            checksumObject.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checksums.put(entry.getKey(), requireString(entry.getValue(), entry.getKey())));

            mods.add(new ModEntry(
                    requireString(mod.get("id"), "id"),
                    requireString(mod.get("name"), "name"),
                    requireString(mod.get("version"), "version"),
                    requireString(mod.get("gameVersion"), "gameVersion"),
                    requireString(mod.get("path"), "path"),
                    jars,
                    checksums));
        }

        return new CoopHandshakeManifest(
                requireString(object.get("gameVersion"), "gameVersion"),
                requireString(object.get("coopBuildVersion"), "coopBuildVersion"),
                requireString(object.get("coopGitCommit"), "coopGitCommit"),
                mods);
    }

    private static ModEntry fromModSpec(ModSpecAPI spec) {
        LinkedHashMap<String, String> checksums = new LinkedHashMap<>();
        checksums.put("mod_info.json", CoopChecksum.unavailable("script-sandbox"));

        List<String> jars = spec.getJars() == null ? List.of() : spec.getJars().stream()
                .map(CoopHandshakeManifest::normalizePath)
                .sorted()
                .toList();
        for (String jar : jars) {
            checksums.put(jar, CoopChecksum.unavailable("script-sandbox"));
        }

        return new ModEntry(
                spec.getId(),
                spec.getName(),
                spec.getVersion(),
                spec.getGameVersion(),
                spec.getPath(),
                jars,
                checksums);
    }

    private static String captureBuildVersion() {
        return normalize(CoopBuildInfo.VERSION, "dev");
    }

    private static String captureGitCommit() {
        return normalize(CoopBuildInfo.GIT_COMMIT, "dev-uncommitted");
    }

    private static void appendField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static void appendStringArray(StringBuilder json, List<String> values) {
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escapeJson(values.get(i))).append('"');
        }
        json.append(']');
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

    private static Map<String, Object> requireObject(Object value, String fieldName) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw new IllegalArgumentException("Expected object field: " + fieldName);
    }

    private static List<Object> requireList(Object value, String fieldName) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new IllegalArgumentException("Expected array field: " + fieldName);
    }

    private static String requireString(Object value, String fieldName) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalArgumentException("Expected string field: " + fieldName);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalizePath(String value) {
        String normalized = normalize(value, "").replace('\\', '/');
        int modsIndex = normalized.lastIndexOf("/mods/");
        if (modsIndex >= 0) {
            return "mods/" + normalized.substring(modsIndex + "/mods/".length());
        }
        if (normalized.startsWith("mods/")) {
            return normalized;
        }
        return normalized;
    }

    public record ModEntry(
            String id,
            String name,
            String version,
            String gameVersion,
            String path,
            List<String> jars,
            Map<String, String> checksums
    ) {
        public ModEntry {
            id = requireText(id, "id");
            name = normalize(name, "");
            version = normalize(version, "");
            gameVersion = normalize(gameVersion, "");
            path = normalizePath(path);
            jars = jars == null ? List.of() : jars.stream()
                    .map(CoopHandshakeManifest::normalizePath)
                    .sorted()
                    .toList();
            LinkedHashMap<String, String> sortedChecksums = new LinkedHashMap<>();
            if (checksums != null) {
                checksums.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> sortedChecksums.put(
                                normalizePath(entry.getKey()),
                                normalize(entry.getValue(), CoopChecksum.unavailable("unknown"))));
            }
            checksums = Collections.unmodifiableMap(sortedChecksums);
        }

        private void appendJson(StringBuilder json) {
            json.append('{');
            appendField(json, "id", id);
            json.append(',');
            appendField(json, "name", name);
            json.append(',');
            appendField(json, "version", version);
            json.append(',');
            appendField(json, "gameVersion", gameVersion);
            json.append(',');
            appendField(json, "path", path);
            json.append(",\"jars\":");
            appendStringArray(json, jars);
            json.append(",\"checksums\":{");
            int index = 0;
            for (Map.Entry<String, String> entry : checksums.entrySet()) {
                if (index > 0) {
                    json.append(',');
                }
                json.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                        .append(escapeJson(entry.getValue())).append('"');
                index++;
            }
            json.append("}}");
        }
    }

    private static final class JsonParser {
        private final String json;
        private int index;

        private JsonParser(String json) {
            this.json = Objects.requireNonNull(json, "json");
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != json.length()) {
                throw error("Trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (peek('{')) {
                return parseObject();
            }
            if (peek('[')) {
                return parseArray();
            }
            if (peek('"')) {
                return parseString();
            }
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            throw error("Expected JSON value");
        }

        private Map<String, Object> parseObject() {
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
                fields.put(key, parseValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                expect('}');
                return fields;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return values;
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                expect(']');
                return values;
            }
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
