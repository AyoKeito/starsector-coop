package coop.campaign;

import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned flat-string wire and save encoding for complete encounter manifests. */
public final class CoopEncounterCodec {
    // The flat record is escaped into payloadJson, which is escaped again into the envelope.
    public static final int MAX_ENCODED_CHARACTERS = 384 * 1024;
    public static final int MAX_STATE_ENCODED_CHARACTERS = 64 * 1024;
    private static final int MAX_WIRE_CONTENT_BYTES = 1024 * 1024 - 2048;
    private static final String SET_MAGIC = "ENC_SET";
    private static final String STATE_MAGIC = "ENC_STATE";
    private CoopEncounterCodec() { }

    public static String encode(CoopEncounterRecord record) {
        Objects.requireNonNull(record, "encounter");
        Writer w = new Writer();
        w.add(record.version()); w.add(record.instanceId()); w.add(record.effectiveSeed());
        w.add(record.encounterPointId()); w.add(record.x()); w.add(record.y()); w.add(record.depth());
        w.add(record.threatRollRan()); w.add(record.creator().name()); w.add(record.threatTag());
        w.add(record.entities().size());
        for (CoopEncounterRecord.Entity entity : record.entities()) {
            w.add(entity.role().name()); w.add(entity.ordinal()); w.add(entity.specId());
        }
        w.add(record.specials().size());
        for (CoopEncounterRecord.Special special : record.specials()) {
            w.add(special.type().name()); w.add(special.entityId());
            w.add(special.orbit() != null);
            if (special.orbit() != null) {
                w.add(special.orbit().focusId()); w.add(special.orbit().angle());
                w.add(special.orbit().radius()); w.add(special.orbit().period());
            }
            w.nullable(special.wreck() == null ? null : special.wreck().encode());
            w.memory(special.memory());
        }
        w.add(record.overlay().consumedIds().size());
        for (String id : record.overlay().consumedIds()) w.add(id);
        w.add(record.overlay().entities().size());
        for (CoopEncounterRecord.EntityState entity : record.overlay().entities()) w.state(entity);
        return w.finish();
    }

    public static CoopEncounterRecord decode(String encoded) {
        Reader r = new Reader(encoded);
        int version = r.integer();
        if (version != CoopEncounterRecord.VERSION) throw new IllegalArgumentException("Unsupported encounter version: " + version);
        String id = r.next();
        long seed = r.longInteger(); String point = r.next();
        float x = r.number(), y = r.number(), depth = r.number();
        boolean threatRan = r.bool();
        CoopEncounterRecord.Creator creator = CoopEncounterRecord.Creator.valueOf(r.next());
        boolean threat = r.bool();
        List<CoopEncounterRecord.Entity> entities = new ArrayList<>();
        for (int n = r.count(CoopEncounterRecord.MAX_ENTITIES); n > 0; n--)
            entities.add(new CoopEncounterRecord.Entity(CoopEncounterRecord.Role.valueOf(r.next()), r.integer(), r.next()));
        List<CoopEncounterRecord.Special> specials = new ArrayList<>();
        for (int n = r.count(CoopEncounterRecord.MAX_SPECIALS); n > 0; n--) {
            CoopEncounterRecord.SpecialType type = CoopEncounterRecord.SpecialType.valueOf(r.next());
            String entityId = r.next();
            CoopEncounterRecord.Orbit orbit = r.bool()
                    ? new CoopEncounterRecord.Orbit(r.next(), r.number(), r.number(), r.number()) : null;
            String wreck = r.nullable();
            specials.add(new CoopEncounterRecord.Special(type, entityId, orbit,
                    wreck == null ? null : CoopWreckRecord.decode(wreck), r.memory()));
        }
        List<String> consumed = new ArrayList<>();
        for (int n = r.count(CoopEncounterRecord.MAX_ENTITIES); n > 0; n--) consumed.add(r.next());
        List<CoopEncounterRecord.EntityState> states = new ArrayList<>();
        for (int n = r.count(CoopEncounterRecord.MAX_ENTITIES); n > 0; n--)
            states.add(r.state());
        r.end();
        return new CoopEncounterRecord(version, id, seed, point, x, y, depth, threatRan, creator,
                threat, entities, specials, new CoopEncounterRecord.Overlay(consumed, states));
    }

    public static String encodeSet(Collection<CoopEncounterRecord> records) {
        List<CoopEncounterRecord> sorted = sorted(records);
        Writer w = new Writer(); w.add(SET_MAGIC); w.add(CoopEncounterRecord.VERSION); w.add(sorted.size());
        for (CoopEncounterRecord record : sorted) w.add(encode(record));
        return w.finish();
    }

    public static List<CoopEncounterRecord> decodeSet(String encoded) {
        Reader r = new Reader(encoded);
        if (!SET_MAGIC.equals(r.next())) throw new IllegalArgumentException("Not an encounter manifest");
        int version = r.integer();
        if (version != CoopEncounterRecord.VERSION) throw new IllegalArgumentException("Unsupported encounter set version: " + version);
        List<CoopEncounterRecord> records = new ArrayList<>();
        for (int n = r.count(CoopEncounterRecord.MAX_ENCOUNTERS); n > 0; n--) records.add(decode(r.next()));
        r.end();
        return sorted(records);
    }

    public static String setHash(Collection<CoopEncounterRecord> records) {
        return CoopChecksum.sha256Text(encodeSet(records));
    }

    public static String encodeEntityState(CoopEncounterRecord.EntityState state) {
        Objects.requireNonNull(state, "encounter entity state");
        Writer w = new Writer(); w.add(STATE_MAGIC); w.add(CoopEncounterRecord.VERSION); w.state(state);
        String encoded = w.finish();
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_ENCODED_CHARACTERS)
            throw new IllegalArgumentException("Encounter state payload too large");
        return encoded;
    }

    public static CoopEncounterRecord.EntityState decodeEntityState(String encoded) {
        if (encoded == null || encoded.length() > MAX_STATE_ENCODED_CHARACTERS
                || encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_ENCODED_CHARACTERS)
            throw new IllegalArgumentException("Encounter state payload too large/null");
        Reader r = new Reader(encoded);
        if (!STATE_MAGIC.equals(r.next())) throw new IllegalArgumentException("Not an encounter entity state");
        int version = r.integer();
        if (version != CoopEncounterRecord.VERSION) throw new IllegalArgumentException("Unsupported encounter state version: " + version);
        CoopEncounterRecord.EntityState state = r.state(); r.end(); return state;
    }

    private static List<CoopEncounterRecord> sorted(Collection<CoopEncounterRecord> records) {
        Objects.requireNonNull(records, "encounters");
        if (records.size() > CoopEncounterRecord.MAX_ENCOUNTERS) throw new IllegalArgumentException("Too many encounters");
        List<CoopEncounterRecord> sorted = new ArrayList<>(List.copyOf(records));
        sorted.sort(Comparator.comparing(CoopEncounterRecord::instanceId));
        Set<String> ids = new HashSet<>();
        for (CoopEncounterRecord record : sorted) if (!ids.add(record.instanceId()))
            throw new IllegalArgumentException("Duplicate encounter instance id");
        return List.copyOf(sorted);
    }

    private static void checkSize(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED_CHARACTERS)
            throw new IllegalArgumentException("Encounter payload too large/null");
        long jsonBytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (jsonBytes > MAX_ENCODED_CHARACTERS) throw new IllegalArgumentException("Encounter payload too large");
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '\\' || c == '"') jsonBytes += 3;
            else if (c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') jsonBytes += 2;
            else if (c < 0x20) jsonBytes += 6;
        }
        if (jsonBytes > MAX_WIRE_CONTENT_BYTES) throw new IllegalArgumentException("Encounter JSON payload too large");
    }

    private static final class Writer {
        private final StringBuilder encoded = new StringBuilder();
        private int fields;
        void add(Object value) {
            if (fields++ > 0) encoded.append('|');
            encoded.append(CoopDelimited.field(String.valueOf(value)));
            if (encoded.length() > MAX_ENCODED_CHARACTERS) throw new IllegalArgumentException("Encounter payload too large");
        }
        void nullable(String value) { add(value != null); if (value != null) add(value); }
        void memory(List<CoopWreckRecord.MemoryValue> values) {
            add(values.size());
            for (CoopWreckRecord.MemoryValue value : values) {
                add(value.key()); add(value.type()); add(value.value()); add(value.expiresDays());
            }
        }
        void state(CoopEncounterRecord.EntityState state) {
            add(state.entityId()); nullable(state.surveyLevel());
            add(state.ruinsExplored() == null ? "" : state.ruinsExplored()); memory(state.memory());
        }
        String finish() { String result = encoded.toString(); checkSize(result); return result; }
    }

    private static final class Reader {
        private final List<String> fields;
        private int index;
        Reader(String encoded) {
            checkSize(encoded);
            // CoopDelimited tolerates unknown/trailing escapes for older message families. This
            // versioned recipe rejects them so corruption cannot silently alter an identity.
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (c == '\n' || c == '\r') throw new IllegalArgumentException("Raw newline in encounter payload");
                if (c == '\\' && (++i >= encoded.length() || "\\|nr".indexOf(encoded.charAt(i)) < 0))
                    throw new IllegalArgumentException("Bad encounter escape");
            }
            fields = CoopDelimited.split(encoded);
        }
        String next() {
            if (index >= fields.size()) throw new IllegalArgumentException("Truncated encounter payload");
            return fields.get(index++);
        }
        int integer() { return Integer.parseInt(next()); }
        long longInteger() { return Long.parseLong(next()); }
        float number() { float value = Float.parseFloat(next()); CoopEncounterRecord.finite(value); return value; }
        boolean bool() {
            String value = next();
            if (value.equals("true")) return true;
            if (value.equals("false")) return false;
            throw new IllegalArgumentException("Bad encounter boolean");
        }
        Boolean optionalBoolean() {
            String value = next();
            if (value.isEmpty()) return null;
            if (value.equals("true")) return true;
            if (value.equals("false")) return false;
            throw new IllegalArgumentException("Bad optional encounter boolean");
        }
        String nullable() { return bool() ? next() : null; }
        int count(int maximum) {
            int value = integer();
            if (value < 0 || value > maximum) throw new IllegalArgumentException("Encounter count out of bounds");
            return value;
        }
        List<CoopWreckRecord.MemoryValue> memory() {
            List<CoopWreckRecord.MemoryValue> result = new ArrayList<>();
            for (int n = count(CoopEncounterRecord.MAX_MEMORY_VALUES); n > 0; n--)
                result.add(new CoopWreckRecord.MemoryValue(next(), next(), next(), number()));
            return result;
        }
        CoopEncounterRecord.EntityState state() {
            return new CoopEncounterRecord.EntityState(next(), nullable(), optionalBoolean(), memory());
        }
        void end() { if (index != fields.size()) throw new IllegalArgumentException("Trailing encounter fields"); }
    }
}
