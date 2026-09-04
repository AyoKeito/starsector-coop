package coop.handshake;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import coop.build.CoopBuildInfo;
import coop.build.CoopForksBuildInfo;
import coop.net.CoopJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static coop.util.CoopText.requireText;

public record CoopHandshakeManifest(
        String gameVersion,
        String coopBuildVersion,
        String coopGitCommit,
        String coopForksBuild,
        List<ModEntry> enabledMods
) {
    /** What {@link #coopForksBuild()} says when coop-forks.jar carries no build stamp at all. */
    public static final String FORKS_BUILD_ABSENT = "absent";

    /** What it says when the peer's build predates the field, so its manifest never had one. */
    public static final String FORKS_BUILD_NOT_REPORTED = "not-reported";

    public CoopHandshakeManifest {
        gameVersion = requireText(gameVersion, "gameVersion");
        coopBuildVersion = normalize(coopBuildVersion, "dev");
        coopGitCommit = normalize(coopGitCommit, "dev-uncommitted");
        coopForksBuild = normalize(coopForksBuild, FORKS_BUILD_ABSENT);
        enabledMods = enabledMods == null ? List.of() : enabledMods.stream()
                .sorted(Comparator.comparing(ModEntry::id))
                .toList();
    }

    /**
     * The shape before {@code coopForksBuild} existed, for callers that have no answer for it. The
     * two that matter - {@link #capture()} and {@link #fromJson(String)} - always do, and use the
     * canonical constructor; this one exists so a caller only interested in the mod list does not
     * have to invent a build stamp, and it says {@link #FORKS_BUILD_ABSENT} rather than pretending
     * to match anything.
     */
    public CoopHandshakeManifest(String gameVersion, String coopBuildVersion, String coopGitCommit,
                                 List<ModEntry> enabledMods) {
        this(gameVersion, coopBuildVersion, coopGitCommit, FORKS_BUILD_ABSENT, enabledMods);
    }

    public static CoopHandshakeManifest capture() {
        String gameVersion = Global.getSettings().getGameVersion();
        List<ModEntry> mods = Global.getSettings().getModManager().getEnabledModsCopy().stream()
                .map(CoopHandshakeManifest::fromModSpec)
                .toList();
        return new CoopHandshakeManifest(gameVersion, captureBuildVersion(), captureGitCommit(),
                captureForksBuild(), mods);
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendField(json, "gameVersion", gameVersion);
        json.append(',');
        appendField(json, "coopBuildVersion", coopBuildVersion);
        json.append(',');
        appendField(json, "coopGitCommit", coopGitCommit);
        json.append(',');
        appendField(json, "coopForksBuild", coopForksBuild);
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
        Object parsed = CoopJson.parse(json);
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

        // Tolerant on purpose: a peer built before this field existed sends a manifest without the
        // key, and refusing to parse it would turn "your partner is on an older build" into "the
        // handshake is broken". The placeholder makes it a diff line instead, which says so.
        Object forksBuild = object.get("coopForksBuild");
        return new CoopHandshakeManifest(
                requireString(object.get("gameVersion"), "gameVersion"),
                requireString(object.get("coopBuildVersion"), "coopBuildVersion"),
                requireString(object.get("coopGitCommit"), "coopGitCommit"),
                forksBuild == null ? FORKS_BUILD_NOT_REPORTED
                        : requireString(forksBuild, "coopForksBuild"),
                mods);
    }

    private static ModEntry fromModSpec(ModSpecAPI spec) {
        LinkedHashMap<String, String> checksums = new LinkedHashMap<>();
        checksums.put("mod_info.json", modInfoChecksum(spec.getId()));

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

    /**
     * Real per-mod {@code mod_info.json} checksum via the engine's own text loader — proven safe
     * in-game by the Phase 12b probe (drill session 2026-08-17: SUCCESS on both clients, identical
     * hashes). One unreadable mod degrades to its own placeholder entry rather than failing the
     * whole capture. Jar checksums stay unavailable: no engine surface hands back jar bytes and the
     * sandbox forbids opening them directly.
     */
    private static String modInfoChecksum(String modId) {
        try {
            String text = Global.getSettings().loadText("mod_info.json", modId);
            if (text == null || text.isBlank()) {
                return CoopChecksum.unavailable("empty-mod-info");
            }
            // Normalize line endings so a CRLF/LF checkout difference does not read as a mismatch.
            return CoopChecksum.sha256Text(text.replace("\r\n", "\n").replace('\r', '\n'));
        } catch (Throwable ex) {
            // Throwable, and never name the loader's checked exception type: catching it by name
            // makes the verifier resolve a blocked i/o class in this class (the documented sandbox
            // pattern). A throw here means this mod's file is unreadable, not that capture failed.
            return CoopChecksum.unavailable("script-sandbox");
        }
    }

    private static String captureBuildVersion() {
        return normalize(CoopBuildInfo.VERSION, "dev");
    }

    private static String captureGitCommit() {
        return normalize(CoopBuildInfo.GIT_COMMIT, "dev-uncommitted");
    }

    /**
     * The identity of the OTHER jar: {@code coop-forks.jar}, which holds the forked engine classes
     * and is loaded by the system classloader rather than the mod one.
     *
     * <p>Everything above this method describes {@code coop.jar}. Two players can hold identical
     * {@code coopBuildVersion} and {@code coopGitCommit} while running different forked engines,
     * because nothing on the wire had ever looked at the second jar - and a forked
     * {@code RouteManager} that differs between the two machines desyncs the world with no line in
     * either log to say why.
     *
     * <p>{@link CoopForksBuildInfo} is generated into {@code coop-forks.jar} only, so a forks jar
     * that is missing or built before this existed fails to resolve here. That is a
     * {@link LinkageError}, not an exception, which is why the guard catches one: the answer is
     * {@link #FORKS_BUILD_ABSENT}, and the peer's diff line says so.
     */
    private static String captureForksBuild() {
        try {
            return normalize(CoopForksBuildInfo.version() + "/" + CoopForksBuildInfo.gitCommit(),
                    FORKS_BUILD_ABSENT);
        } catch (LinkageError | RuntimeException ex) {
            return FORKS_BUILD_ABSENT;
        }
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

    /** @see CoopJson#escape(String) — the one escaper; this alias keeps the append sites short. */
    private static String escapeJson(String value) {
        return CoopJson.escape(value);
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
}
