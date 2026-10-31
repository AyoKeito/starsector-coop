package coop.campaign;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Length-prefixed primitive text for native-light manifests and committed interactions. */
public final class CoopLightCodec {
    public static final int MAX_ENCODED_BYTES = 384 * 1024;
    private static final int MAX_FIELD_CHARACTERS = 16 * 1024;
    private CoopLightCodec() { }

    public enum Action { PULSE, SENSOR_BURST, DWELLER }

    /** Identity is supplied by the authenticated session, never by this request. */
    public record Request(String lightId, String requestId, Action action) {
        public Request {
            lightId = CoopLightRecord.canonicalId(lightId);
            requestId = CoopLightRecord.uuid(requestId, "light request id");
            Objects.requireNonNull(action, "action");
        }
        public String encode() { return encodeRequest(this); }
        public static Request decode(String encoded) { return decodeRequest(encoded); }
    }

    /** Resolved reward comes from Host acceptance, even if the actor moves before acknowledgement. */
    public record Outcome(String requestId, String lightId, String actorId, Action action,
                          boolean accepted, CoopLightRecord record, float bonus, int points, String reason) {
        public Outcome {
            requestId = CoopLightRecord.uuid(requestId, "light request id");
            lightId = CoopLightRecord.canonicalId(lightId);
            actorId = CoopLightRecord.identity(actorId, "light actor id");
            Objects.requireNonNull(action, "action");
            if (record != null && !record.id().equals(lightId))
                throw new IllegalArgumentException("Invalid light outcome recipe");
            if (!Float.isFinite(bonus) || bonus < 0f || bonus > 1_000f || points < 0 || points > 1_000_000
                    || (!accepted || action == Action.DWELLER) && (bonus != 0f || points != 0))
                throw new IllegalArgumentException("Invalid light outcome reward");
            if (reason == null || reason.length() > 1_024)
                throw new IllegalArgumentException("Invalid light outcome reason");
        }
        public String encode() { return encodeOutcome(this); }
        public static Outcome decode(String encoded) { return decodeOutcome(encoded); }
    }

    public static String encode(CoopLightRecord record) {
        Objects.requireNonNull(record, "light");
        Writer w = new Writer("LIGHT", record.version());
        w.add(record.id()); w.add(record.hostEntityId()); w.add(record.x()); w.add(record.y());
        w.add(record.colorArgb()); w.add(record.frequencyMultMin()); w.add(record.frequencyMultMax());
        w.add(record.frequencyChangeMult()); w.add(record.size()); w.add(record.detectedRange());
        w.add(record.bonusMult()); w.add(record.remainingDays()); w.add(record.pointsOverride());
        w.add(record.dweller()); w.add(record.salvageSeed());
        return w.finish();
    }

    public static CoopLightRecord decode(String encoded) {
        Reader r = new Reader(encoded, "LIGHT");
        CoopLightRecord result = new CoopLightRecord(CoopLightRecord.VERSION, r.next(), r.next(),
                r.number(), r.number(), r.integer(), r.number(), r.number(), r.number(),
                r.number(), r.number(), r.number(), r.number(), r.integer(), r.bool(), r.longInteger());
        r.end();
        return result;
    }

    public static String encodeSet(Collection<CoopLightRecord> records) {
        List<CoopLightRecord> sorted = sorted(records);
        Writer w = new Writer("LIGHT_SET", CoopLightRecord.VERSION);
        w.add(sorted.size());
        for (CoopLightRecord record : sorted) w.add(encode(record));
        return w.finish();
    }

    public static List<CoopLightRecord> decodeSet(String encoded) {
        Reader r = new Reader(encoded, "LIGHT_SET");
        int count = r.integer();
        if (count < 0 || count > CoopLightRecord.MAX_LIGHTS) throw new IllegalArgumentException("Too many lights");
        List<CoopLightRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) records.add(decode(r.next()));
        r.end();
        return sorted(records);
    }

    public static String staticFingerprint(Collection<CoopLightRecord> records) {
        Writer w = new Writer("LIGHT_STATIC", CoopLightRecord.VERSION);
        for (CoopLightRecord record : sorted(records)) w.add(record.staticFingerprint());
        return w.finish();
    }

    public static String encodeRequest(Request request) {
        Objects.requireNonNull(request, "request");
        Writer w = new Writer("LIGHT_REQUEST", CoopLightRecord.VERSION);
        w.add(request.lightId()); w.add(request.requestId()); w.add(request.action().name());
        return w.finish();
    }

    public static Request decodeRequest(String encoded) {
        Reader r = new Reader(encoded, "LIGHT_REQUEST");
        Request result = new Request(r.next(), r.next(), Action.valueOf(r.next()));
        r.end();
        return result;
    }

    public static String encodeOutcome(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        Writer w = new Writer("LIGHT_RESULT", CoopLightRecord.VERSION);
        w.add(outcome.requestId()); w.add(outcome.lightId()); w.add(outcome.actorId());
        w.add(outcome.action().name()); w.add(outcome.accepted());
        w.add(outcome.record() == null ? "" : encode(outcome.record()));
        w.add(outcome.bonus()); w.add(outcome.points()); w.add(outcome.reason());
        return w.finish();
    }

    public static Outcome decodeOutcome(String encoded) {
        Reader r = new Reader(encoded, "LIGHT_RESULT");
        String request = r.next(), light = r.next(), actor = r.next();
        Action action = Action.valueOf(r.next());
        boolean accepted = r.bool();
        String record = r.next();
        Outcome result = new Outcome(request, light, actor, action, accepted,
                record.isEmpty() ? null : decode(record), r.number(), r.integer(), r.next());
        r.end();
        return result;
    }

    private static List<CoopLightRecord> sorted(Collection<CoopLightRecord> records) {
        Objects.requireNonNull(records, "lights");
        if (records.size() > CoopLightRecord.MAX_LIGHTS) throw new IllegalArgumentException("Too many lights");
        List<CoopLightRecord> result = new ArrayList<>(List.copyOf(records));
        result.sort(Comparator.comparing(CoopLightRecord::id));
        Set<String> ids = new HashSet<>(), nativeIds = new HashSet<>();
        for (CoopLightRecord record : result) {
            if (!ids.add(record.id())) throw new IllegalArgumentException("Duplicate light id");
            if (!nativeIds.add(record.hostEntityId())) throw new IllegalArgumentException("Duplicate native light id");
        }
        return List.copyOf(result);
    }

    private static void checkSize(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Light payload exceeds UTF-8 cap or is null");
    }

    private static final class Writer {
        private final StringBuilder text = new StringBuilder();
        Writer(String magic, int version) { add(magic); add(version); }
        void add(Object field) {
            String value = Objects.requireNonNull(field, "light field").toString();
            if (value.length() > MAX_FIELD_CHARACTERS) throw new IllegalArgumentException("Light field too large");
            text.append(value.length()).append(':').append(value);
            if (text.length() > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Light payload too large");
        }
        String finish() { String result = text.toString(); checkSize(result); return result; }
    }

    private static final class Reader {
        private final String text;
        private int cursor;
        Reader(String encoded, String magic) {
            checkSize(encoded); text = encoded;
            if (!magic.equals(next())) throw new IllegalArgumentException("Not a " + magic + " payload");
            if (integer() != CoopLightRecord.VERSION) throw new IllegalArgumentException("Unsupported light payload version");
        }
        String next() {
            int colon = text.indexOf(':', cursor);
            if (colon < cursor || colon - cursor > 6) throw new IllegalArgumentException("Invalid light field length");
            int length;
            try { length = Integer.parseInt(text.substring(cursor, colon)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid light field length", ex); }
            if (length < 0 || length > MAX_FIELD_CHARACTERS || length > text.length() - colon - 1)
                throw new IllegalArgumentException("Truncated or oversized light field");
            cursor = colon + 1;
            String result = text.substring(cursor, cursor + length);
            cursor += length;
            return result;
        }
        int integer() {
            try { return Integer.parseInt(next()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid light integer", ex); }
        }
        long longInteger() {
            try { return Long.parseLong(next()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid light long", ex); }
        }
        float number() {
            try {
                float result = Float.parseFloat(next());
                if (!Float.isFinite(result)) throw new IllegalArgumentException("Non-finite light number");
                return result;
            } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid light number", ex); }
        }
        boolean bool() {
            return switch (next()) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("Invalid light boolean");
            };
        }
        void end() { if (cursor != text.length()) throw new IllegalArgumentException("Trailing light payload"); }
    }
}
