package coop.campaign;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Transient current orbit phases accompanying a complete encounter set; never saved in a recipe. */
public final class CoopEncounterOrbitSetCodec {
    public static final int MAX_ENCODED_BYTES = 384 * 1024;
    private static final long MAX_JSON_CONTENT_BYTES = 1024 * 1024 - 2048;
    private static final String MAGIC = "ENC_ORBITS";
    private static final String VERSION = "1";

    private CoopEncounterOrbitSetCodec() { }

    public static String encode(Map<String, String> orbits, List<CoopEncounterRecord> records) {
        Map<String, CoopEncounterRecord> expected = recordsById(records);
        requireKeys(orbits, expected);
        long inputBytes = 0;
        for (Map.Entry<String, String> entry : orbits.entrySet()) {
            inputBytes += utf8Bytes(entry.getKey());
            inputBytes += utf8Bytes(entry.getValue());
            if (inputBytes > MAX_ENCODED_BYTES) throw invalid("Orbit map exceeds its byte limit");
        }
        StringBuilder encoded = new StringBuilder(MAGIC).append('|').append(VERSION).append('|').append(expected.size());
        for (Map.Entry<String, CoopEncounterRecord> entry : expected.entrySet()) {
            String value = orbits.get(entry.getKey());
            validateOrbits(value, entry.getValue());
            encoded.append('|').append(CoopDelimited.field(entry.getKey())).append('|').append(CoopDelimited.field(value));
            if (encoded.length() > MAX_ENCODED_BYTES) throw invalid("Orbit set exceeds its byte limit");
        }
        String result = encoded.toString(); checkSize(result); return result;
    }

    public static Map<String, String> decode(String encoded, List<CoopEncounterRecord> records) {
        checkSize(encoded);
        strictEscapes(encoded);
        List<String> fields = CoopDelimited.split(encoded);
        if (fields.size() < 3 || !MAGIC.equals(fields.get(0)) || !VERSION.equals(fields.get(1)))
            throw invalid("Unsupported encounter orbit set");
        int count;
        try { count = Integer.parseInt(fields.get(2)); }
        catch (NumberFormatException error) { throw invalid("Invalid orbit instance count"); }
        if (count < 0 || count > CoopEncounterRecord.MAX_ENCOUNTERS || !Integer.toString(count).equals(fields.get(2))
                || fields.size() != 3 + count * 2) throw invalid("Invalid orbit instance count or trailing fields");
        Map<String, CoopEncounterRecord> expected = recordsById(records);
        Map<String, String> result = new TreeMap<>();
        for (int index = 3; index < fields.size(); index += 2) {
            String id = fields.get(index), value = fields.get(index + 1);
            if (result.putIfAbsent(id, value) != null) throw invalid("Duplicate orbit instance id");
        }
        requireKeys(result, expected);
        for (Map.Entry<String, String> entry : result.entrySet()) validateOrbits(entry.getValue(), expected.get(entry.getKey()));
        return immutable(result);
    }

    /** Every desired instance has an explicit empty phase table, including an empty desired set. */
    public static Map<String, String> empty(List<CoopEncounterRecord> records) {
        Map<String, String> result = new TreeMap<>();
        for (String id : recordsById(records).keySet()) result.put(id, "");
        return immutable(result);
    }

    private static Map<String, CoopEncounterRecord> recordsById(List<CoopEncounterRecord> records) {
        if (records == null || records.size() > CoopEncounterRecord.MAX_ENCOUNTERS) throw invalid("Invalid encounter recipe set");
        Map<String, CoopEncounterRecord> result = new TreeMap<>();
        for (CoopEncounterRecord record : records) {
            if (record == null || result.putIfAbsent(record.instanceId(), record) != null) throw invalid("Duplicate or null encounter recipe");
        }
        return result;
    }

    private static void requireKeys(Map<String, String> orbits, Map<String, CoopEncounterRecord> records) {
        if (orbits == null || orbits.size() > CoopEncounterRecord.MAX_ENCOUNTERS || !orbits.keySet().equals(records.keySet()))
            throw invalid("Orbit instance keys differ from the encounter set");
    }

    private static void validateOrbits(String encoded, CoopEncounterRecord recipe) {
        if (encoded == null) throw invalid("Null orbit table");
        if (encoded.isEmpty()) return;
        if (utf8Bytes(encoded) > MAX_ENCODED_BYTES) throw invalid("Orbit table exceeds its byte limit");
        String[] lines = encoded.split("\n", -1);
        if (lines.length > CoopEncounterRecord.MAX_ENTITIES) throw invalid("Too many encounter orbit entries");
        Set<String> members = new HashSet<>();
        for (CoopEncounterRecord.Entity member : recipe.entities())
            if (member.role() == CoopEncounterRecord.Role.SYSTEM) members.add(recipe.entityId(member));
        Set<String> consumed = new HashSet<>(recipe.overlay().consumedIds());
        Set<String> ghosts = new HashSet<>();
        for (CoopEncounterRecord.Special special : recipe.specials())
            if (special.type() == CoopEncounterRecord.SpecialType.GHOST_WRECK) ghosts.add(special.entityId());
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            if (line.isEmpty()) throw invalid("Empty encounter orbit entry");
            strictEscapes(line);
            List<String> fields = CoopDelimited.split(line);
            if (fields.size() != 5 && fields.size() != 10) throw invalid("Partial or unsupported encounter orbit entry");
            String body = fields.get(0), focus = fields.get(1);
            if (!members.contains(body) || consumed.contains(body) || !seen.add(body))
                throw invalid("Unknown, consumed or duplicate encounter orbit body");
            if (!focus.isEmpty() && (!members.contains(focus) || consumed.contains(focus)))
                throw invalid("Unknown or consumed encounter orbit focus");
            float radius = number(fields.get(2)), period = number(fields.get(3)), angle = number(fields.get(4));
            if (radius < 0 || radius > 1_000_000f || period <= 0 || period > 1_000_000f || Math.abs(angle) > 1_000_000f)
                throw invalid("Encounter orbit geometry out of bounds");
            if (fields.size() == 10) {
                if (!ghosts.contains(body)) throw invalid("Exact pose requires the resolved encounter ghost");
                new CoopOrbitSync.Pose(number(fields.get(5)), number(fields.get(6)), number(fields.get(7)), number(fields.get(8)), number(fields.get(9)));
            }
        }
    }

    private static float number(String text) {
        try {
            float value = Float.parseFloat(text);
            if (!Float.isFinite(value)) throw invalid("Nonfinite encounter orbit value");
            return value;
        } catch (NumberFormatException error) { throw invalid("Invalid encounter orbit number"); }
    }

    private static void strictEscapes(String encoded) {
        for (int index = 0; index < encoded.length(); index++) {
            char c = encoded.charAt(index);
            if (c == '\n' || c == '\r') throw invalid("Raw newline in encounter orbit field");
            if (c == '\\' && (++index >= encoded.length() || "\\|nr".indexOf(encoded.charAt(index)) < 0))
                throw invalid("Invalid encounter orbit escape");
        }
    }

    private static void checkSize(String encoded) {
        long jsonBytes = utf8Bytes(encoded);
        if (jsonBytes > MAX_ENCODED_BYTES) throw invalid("Orbit set exceeds its byte limit");
        // payloadJson is itself a string in the outer message envelope. Final recipe + orbit size
        // must still be checked together by the authority before it queues the actual message.
        for (int index = 0; index < encoded.length(); index++) {
            char c = encoded.charAt(index);
            if (c == '\\' || c == '"') jsonBytes += 3;
            else if (c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') jsonBytes += 2;
            else if (c < 0x20) jsonBytes += 6;
        }
        if (jsonBytes > MAX_JSON_CONTENT_BYTES) throw invalid("Orbit set exceeds its escaped JSON byte limit");
    }

    private static int utf8Bytes(String value) {
        if (value == null || value.length() > MAX_ENCODED_BYTES) throw invalid("Null or oversized encounter orbit text");
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, String> immutable(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
