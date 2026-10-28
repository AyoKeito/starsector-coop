package coop.campaign;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolved host slipstream geometry and live fade state. Contains no engine objects or RNG state.
 * Discovery belongs to the local player and is deliberately absent.
 *
 * <p>Each record is a single escaped line. Lists carry explicit counts inside that line, rather
 * than JSON arrays, so the existing flat TCP envelope can carry a record as one string.
 */
public record CoopSlipstreamRecord(int version, String hostId, Kind kind, Params params,
                                  List<String> terrainTags, List<Segment> segments, Fade fade,
                                  Timeline timeline, SurgeWaveState surgeWave) {
    public static final int VERSION = 2;
    public static final long QUANTUM_CAMPAIGN_MILLIS = 72_000L;
    public static final long MAX_COMMITTED_TICK = Long.MAX_VALUE / QUANTUM_CAMPAIGN_MILLIS;
    public static final int MIN_SEGMENTS = 3;
    public static final int MAX_SEGMENTS = 4096;
    public static final int MAX_NOISE_POINTS = 512;
    public static final int MAX_TAGS = 128;
    public static final int MAX_STREAMS = 256;
    public static final int MAX_ENCODED_CHARACTERS = 768 * 1024;
    public static final int MAX_ID_LENGTH = 256;
    public static final int MAX_STRING_LENGTH = 4096;
    private static final float MAX_COORDINATE = 1_000_000f;
    private static final float MAX_WIDTH = 100_000f;
    private static final double MAX_TOTAL_LENGTH = 2_000_000d;

    public enum Kind { SEASONAL, OTHER, TRAIL }

    /** Names correspond exactly to the engine's public FaderUtil.State. */
    public enum FaderState { IN, OUT, IDLE }

    public CoopSlipstreamRecord {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported slipstream version: " + version);
        }
        hostId = checkedString(hostId, "hostId", MAX_ID_LENGTH, false);
        kind = Objects.requireNonNull(kind, "kind");
        params = Objects.requireNonNull(params, "params");
        fade = Objects.requireNonNull(fade, "fade");
        timeline = Objects.requireNonNull(timeline, "timeline");
        surgeWave = Objects.requireNonNull(surgeWave, "surgeWave");
        terrainTags = checkedStrings(terrainTags, "terrain tags", MAX_TAGS);
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        // Native Leviathan trails begin with one point and grow; seasonal/surge routes need three.
        checkCount(segments.size(), kind == Kind.TRAIL ? 1 : MIN_SEGMENTS, MAX_SEGMENTS, "segments");
        if (surgeWave.index() > segments.size()) {
            throw new IllegalArgumentException("Surge wave cursor exceeds segment count");
        }
        if (kind == Kind.TRAIL && surgeWave.present()) {
            throw new IllegalArgumentException("Leviathan trail cannot carry a slipsurge wave");
        }
        double totalLength = 0;
        for (int i = 1; i < segments.size(); i++) {
            Segment a = segments.get(i - 1);
            Segment b = segments.get(i);
            totalLength += Math.hypot((double) b.x() - a.x(), (double) b.y() - a.y());
        }
        // The engine allocates its distance lookup from the complete polyline length.
        if (totalLength > MAX_TOTAL_LENGTH) {
            throw new IllegalArgumentException("Slipstream polyline too long: " + totalLength);
        }
    }

    public CoopSlipstreamRecord(String hostId, Kind kind, Params params, List<String> terrainTags,
                               List<Segment> segments, Fade fade, boolean unfinishedSurgeFadeIn) {
        this(VERSION, hostId, kind, params, terrainTags, segments, fade,
                Timeline.atOrigin(0L, false, true), initialWave(segments, unfinishedSurgeFadeIn));
    }

    public CoopSlipstreamRecord(int version, String hostId, Kind kind, Params params,
                               List<String> terrainTags, List<Segment> segments, Fade fade,
                               boolean unfinishedSurgeFadeIn) {
        this(version, hostId, kind, params, terrainTags, segments, fade,
                Timeline.atOrigin(0L, false, true), initialWave(segments, unfinishedSurgeFadeIn));
    }

    public CoopSlipstreamRecord(String hostId, Kind kind, Params params, List<String> terrainTags,
                               List<Segment> segments, Fade fade, Timeline timeline,
                               SurgeWaveState surgeWave) {
        this(VERSION, hostId, kind, params, terrainTags, segments, fade, timeline, surgeWave);
    }

    public boolean unfinishedSurgeFadeIn() {
        return surgeWave.present() && !surgeWave.done();
    }

    private static SurgeWaveState initialWave(List<Segment> segments, boolean unfinished) {
        Objects.requireNonNull(segments, "segments");
        return unfinished ? new SurgeWaveState(true, false, 0, 0f,
                0.2f * segments.size() * 0.34f + 3f) : SurgeWaveState.absent();
    }

    /** Native fields correspond to the committed fixed-grid tick, not the later capture time. */
    public record Timeline(long originCampaignMillis, long committedTick,
                           long captureCampaignMillis, boolean hostPaused,
                           boolean hostInHyperspace) {
        public Timeline {
            if (committedTick < 0 || committedTick > MAX_COMMITTED_TICK) {
                throw new IllegalArgumentException("Slipstream committed tick out of bounds");
            }
            try {
                long committed = Math.addExact(originCampaignMillis,
                        Math.multiplyExact(committedTick, QUANTUM_CAMPAIGN_MILLIS));
                long span = Math.subtractExact(captureCampaignMillis, originCampaignMillis);
                if (span < 0 || committed > captureCampaignMillis) {
                    throw new IllegalArgumentException("Slipstream capture precedes committed grid state");
                }
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("Slipstream timeline overflows campaign time", failure);
            }
        }

        public static Timeline atOrigin(long campaignMillis, boolean paused, boolean inHyperspace) {
            return new Timeline(campaignMillis, 0L, campaignMillis, paused, inHyperspace);
        }

        public long committedCampaignMillis() {
            return Math.addExact(originCampaignMillis,
                    Math.multiplyExact(committedTick, QUANTUM_CAMPAIGN_MILLIS));
        }
    }

    /** The actual native sweep cursor; absence differs from a retained completed script. */
    public record SurgeWaveState(boolean present, boolean done, int index,
                                 float elapsed, float maxElapsed) {
        public SurgeWaveState {
            checkCount(index, 0, MAX_SEGMENTS, "surge wave index");
            nonnegative(elapsed, "surge wave elapsed");
            nonnegative(maxElapsed, "surge wave maxElapsed");
            if (!present && (!done || index != 0 || elapsed != 0f || maxElapsed != 0f)) {
                throw new IllegalArgumentException("Absent surge wave carries native state");
            }
            if (present && maxElapsed <= 0f) {
                throw new IllegalArgumentException("Present surge wave has no lifetime");
            }
        }

        public static SurgeWaveState absent() {
            return new SurgeWaveState(false, true, 0, 0f, 0f);
        }
    }

    /** All non-Color fields of SlipstreamParams2, in their source declaration order. */
    public record Params(String enteringSlipstreamTextOverride,
                         Float enteringSlipstreamTextDurationOverride,
                         boolean forceNoWindVisualEffectOnFleets,
                         String spriteKey1, String spriteKey2, String spriteKey3, String edgeKey,
                         float baseWidth, float widthForMaxSpeed, float edgeWidth,
                         float areaPerParticle, int maxParticles, float minSpeed, float maxSpeed,
                         float minDur, float maxDur, float particleFadeInTime,
                         float lineLengthFractionOfSpeed, int burnLevel,
                         int maxBurnLevelForTextureScroll, boolean slowDownInWiderSections,
                         float widthForMaxSpeedMinMult, float widthForMaxSpeedMaxMult,
                         float accelerationMult, String name,
                         float texScrollMult0, float texScrollMult1, float texScrollMult2) {
        public Params {
            enteringSlipstreamTextOverride = optionalString(enteringSlipstreamTextOverride,
                    "enteringSlipstreamTextOverride");
            name = optionalString(name, "name");
            spriteKey1 = checkedString(spriteKey1, "spriteKey1", MAX_STRING_LENGTH, false);
            spriteKey2 = checkedString(spriteKey2, "spriteKey2", MAX_STRING_LENGTH, false);
            spriteKey3 = checkedString(spriteKey3, "spriteKey3", MAX_STRING_LENGTH, false);
            edgeKey = checkedString(edgeKey, "edgeKey", MAX_STRING_LENGTH, false);
            if (enteringSlipstreamTextDurationOverride != null) {
                nonnegative(enteringSlipstreamTextDurationOverride,
                        "enteringSlipstreamTextDurationOverride");
            }
            positive(baseWidth, "baseWidth");
            positive(widthForMaxSpeed, "widthForMaxSpeed");
            nonnegative(edgeWidth, "edgeWidth");
            positive(areaPerParticle, "areaPerParticle");
            checkCount(maxParticles, 0, 100_000, "maxParticles");
            nonnegative(minSpeed, "minSpeed");
            nonnegative(maxSpeed, "maxSpeed");
            if (minSpeed > maxSpeed) throw new IllegalArgumentException("minSpeed exceeds maxSpeed");
            positive(minDur, "minDur");
            positive(maxDur, "maxDur");
            if (minDur > maxDur) throw new IllegalArgumentException("minDur exceeds maxDur");
            nonnegative(particleFadeInTime, "particleFadeInTime");
            nonnegative(lineLengthFractionOfSpeed, "lineLengthFractionOfSpeed");
            checkCount(burnLevel, 0, 100_000, "burnLevel");
            checkCount(maxBurnLevelForTextureScroll, 0, 100_000, "maxBurnLevelForTextureScroll");
            positive(widthForMaxSpeedMinMult, "widthForMaxSpeedMinMult");
            positive(widthForMaxSpeedMaxMult, "widthForMaxSpeedMaxMult");
            if (widthForMaxSpeedMinMult > widthForMaxSpeedMaxMult) {
                throw new IllegalArgumentException("Width multiplier minimum exceeds maximum");
            }
            positive(accelerationMult, "accelerationMult");
            finite(texScrollMult0, "texScrollMult0");
            finite(texScrollMult1, "texScrollMult1");
            finite(texScrollMult2, "texScrollMult2");
        }

        /** Stock defaults; min/max speed remain zero until a creator assigns them. */
        public static Params defaults() {
            return new Params(null, null, false, "slipstream0", "slipstream1", "slipstream2",
                    "slipstream_edge", 768f, 768f, 256f, 10000f, 2000, 0f, 0f,
                    2f, 6f, 1f, 0.25f, 30, 30, true, 0.67f, 1.5f, 1f, null,
                    0f, -1.13f, -0.28f);
        }
    }

    public record Fader(float brightness, float durationIn, float durationOut, FaderState state,
                        boolean bounceUp, boolean bounceDown) {
        public Fader {
            unitFraction(brightness, "fader brightness");
            nonnegative(durationIn, "durationIn");
            nonnegative(durationOut, "durationOut");
            state = Objects.requireNonNull(state, "fader state");
        }
    }

    public record Segment(float x, float y, float width, float bMult, Fader fader) {
        public Segment {
            finite(x, "segment x");
            finite(y, "segment y");
            if (Math.abs(x) > MAX_COORDINATE || Math.abs(y) > MAX_COORDINATE) {
                throw new IllegalArgumentException("Slipstream segment coordinate out of bounds");
            }
            nonnegative(width, "segment width");
            if (width > MAX_WIDTH) throw new IllegalArgumentException("Segment width out of bounds");
            nonnegative(bMult, "segment bMult");
            fader = Objects.requireNonNull(fader, "segment fader");
        }
    }

    /** Null noise denotes an inactive fade; it is distinct from any live noise array. */
    public record Fade(float[] spawnNoise, float spawnDays, float spawnElapsed,
                       float[] despawnNoise, float despawnDelay, float despawnDays,
                       float despawnElapsed) {
        public Fade {
            spawnNoise = checkedNoise(spawnNoise, "spawnNoise");
            despawnNoise = checkedNoise(despawnNoise, "despawnNoise");
            nonnegative(spawnDays, "spawnDays");
            nonnegative(spawnElapsed, "spawnElapsed");
            // Vanilla decrements this through zero and keeps the negative value during fading.
            finite(despawnDelay, "despawnDelay");
            nonnegative(despawnDays, "despawnDays");
            nonnegative(despawnElapsed, "despawnElapsed");
        }

        @Override public float[] spawnNoise() {
            return spawnNoise == null ? null : spawnNoise.clone();
        }

        @Override public float[] despawnNoise() {
            return despawnNoise == null ? null : despawnNoise.clone();
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Fade f)) return false;
            return Arrays.equals(spawnNoise, f.spawnNoise)
                    && Arrays.equals(despawnNoise, f.despawnNoise)
                    && Float.compare(spawnDays, f.spawnDays) == 0
                    && Float.compare(spawnElapsed, f.spawnElapsed) == 0
                    && Float.compare(despawnDelay, f.despawnDelay) == 0
                    && Float.compare(despawnDays, f.despawnDays) == 0
                    && Float.compare(despawnElapsed, f.despawnElapsed) == 0;
        }

        @Override public int hashCode() {
            int result = Objects.hash(spawnDays, spawnElapsed, despawnDelay, despawnDays,
                    despawnElapsed);
            result = 31 * result + Arrays.hashCode(spawnNoise);
            return 31 * result + Arrays.hashCode(despawnNoise);
        }

        public static Fade idle() {
            return new Fade(null, 0f, 0f, null, 0f, 0f, 0f);
        }
    }

    /** Only the fields changed by vanilla despawn(), without resending immutable geometry. */
    public record Despawn(String hostId, float[] noise, float delay, float days, float elapsed) {
        public Despawn {
            hostId = checkedString(hostId, "hostId", MAX_ID_LENGTH, false);
            noise = checkedNoise(noise, "despawnNoise");
            finite(delay, "despawnDelay");
            nonnegative(days, "despawnDays");
            nonnegative(elapsed, "despawnElapsed");
        }

        @Override public float[] noise() { return noise == null ? null : noise.clone(); }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Despawn d)) return false;
            return hostId.equals(d.hostId) && Arrays.equals(noise, d.noise)
                    && Float.compare(delay, d.delay) == 0 && Float.compare(days, d.days) == 0
                    && Float.compare(elapsed, d.elapsed) == 0;
        }

        @Override public int hashCode() {
            return 31 * Objects.hash(hostId, delay, days, elapsed) + Arrays.hashCode(noise);
        }

        public String encode() {
            Writer w = new Writer();
            w.add(VERSION); w.add(hostId); w.noise(noise);
            w.add(delay); w.add(days); w.add(elapsed);
            return w.finish();
        }

        public static Despawn decode(String encoded) {
            Reader r = new Reader(encoded);
            int version = r.integer();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported slipstream version: " + version);
            }
            Despawn result = new Despawn(r.next(), r.noise(), r.number(), r.number(), r.number());
            r.end();
            return result;
        }
    }

    public String encode() {
        Writer w = new Writer();
        w.add(version); w.add(hostId); w.add(kind.name());
        w.nullable(params.enteringSlipstreamTextOverride());
        w.add(params.enteringSlipstreamTextDurationOverride() == null ? ""
                : params.enteringSlipstreamTextDurationOverride().toString());
        w.add(params.forceNoWindVisualEffectOnFleets());
        w.add(params.spriteKey1()); w.add(params.spriteKey2()); w.add(params.spriteKey3());
        w.add(params.edgeKey());
        w.add(params.baseWidth()); w.add(params.widthForMaxSpeed()); w.add(params.edgeWidth());
        w.add(params.areaPerParticle()); w.add(params.maxParticles());
        w.add(params.minSpeed()); w.add(params.maxSpeed()); w.add(params.minDur());
        w.add(params.maxDur()); w.add(params.particleFadeInTime());
        w.add(params.lineLengthFractionOfSpeed()); w.add(params.burnLevel());
        w.add(params.maxBurnLevelForTextureScroll()); w.add(params.slowDownInWiderSections());
        w.add(params.widthForMaxSpeedMinMult()); w.add(params.widthForMaxSpeedMaxMult());
        w.add(params.accelerationMult()); w.nullable(params.name());
        w.add(params.texScrollMult0()); w.add(params.texScrollMult1()); w.add(params.texScrollMult2());
        w.add(terrainTags.size());
        for (String tag : terrainTags) w.add(tag);
        w.add(segments.size());
        for (Segment s : segments) {
            w.add(s.x()); w.add(s.y()); w.add(s.width()); w.add(s.bMult());
            w.add(s.fader().brightness()); w.add(s.fader().durationIn());
            w.add(s.fader().durationOut()); w.add(s.fader().state().name());
            w.add(s.fader().bounceUp()); w.add(s.fader().bounceDown());
        }
        w.noise(fade.spawnNoise); w.add(fade.spawnDays()); w.add(fade.spawnElapsed());
        w.noise(fade.despawnNoise); w.add(fade.despawnDelay()); w.add(fade.despawnDays());
        w.add(fade.despawnElapsed());
        w.add(timeline.originCampaignMillis()); w.add(timeline.committedTick());
        w.add(timeline.captureCampaignMillis()); w.add(timeline.hostPaused());
        w.add(timeline.hostInHyperspace());
        w.add(surgeWave.present()); w.add(surgeWave.done()); w.add(surgeWave.index());
        w.add(surgeWave.elapsed()); w.add(surgeWave.maxElapsed());
        return w.finish();
    }

    /** Decodes and validates the complete recipe before any caller can apply engine state. */
    public static CoopSlipstreamRecord decode(String encoded) {
        Reader r = new Reader(encoded);
        int version = r.integer();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported slipstream version: " + version);
        }
        String id = r.next();
        Kind kind = Kind.valueOf(r.next());
        String enteringText = r.nullable();
        String enteringDuration = r.next();
        Float duration = enteringDuration.isEmpty() ? null : parseFloat(enteringDuration);
        Params params = new Params(enteringText, duration, r.bool(),
                r.next(), r.next(), r.next(), r.next(),
                r.number(), r.number(), r.number(), r.number(), r.integer(),
                r.number(), r.number(), r.number(), r.number(), r.number(), r.number(),
                r.integer(), r.integer(), r.bool(), r.number(), r.number(), r.number(),
                r.nullable(), r.number(), r.number(), r.number());
        int tagCount = r.count(0, MAX_TAGS, "terrain tags");
        List<String> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) tags.add(r.next());
        int segmentCount = r.count(kind == Kind.TRAIL ? 1 : MIN_SEGMENTS, MAX_SEGMENTS, "segments");
        List<Segment> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            float x = r.number(), y = r.number(), width = r.number(), mult = r.number();
            Fader fader = new Fader(r.number(), r.number(), r.number(),
                    FaderState.valueOf(r.next()), r.bool(), r.bool());
            segments.add(new Segment(x, y, width, mult, fader));
        }
        Fade fade = new Fade(r.noise(), r.number(), r.number(), r.noise(),
                r.number(), r.number(), r.number());
        Timeline timeline = new Timeline(r.longInteger(), r.longInteger(), r.longInteger(),
                r.bool(), r.bool());
        SurgeWaveState surgeWave = new SurgeWaveState(r.bool(), r.bool(), r.integer(),
                r.number(), r.number());
        r.end();
        return new CoopSlipstreamRecord(version, id, kind, params, tags, segments, fade,
                timeline, surgeWave);
    }

    /** Deterministic helpers for saved recipes and tests; M1's live SET is an id manifest. */
    public static String encodeSet(Collection<CoopSlipstreamRecord> records) {
        Objects.requireNonNull(records, "records");
        checkCount(records.size(), 0, MAX_STREAMS, "streams");
        List<CoopSlipstreamRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparing(CoopSlipstreamRecord::hostId));
        Set<String> ids = new HashSet<>();
        List<String> lines = new ArrayList<>(sorted.size());
        for (CoopSlipstreamRecord record : sorted) {
            if (!ids.add(record.hostId())) throw new IllegalArgumentException("Duplicate stream id");
            lines.add(record.encode());
        }
        return String.join("\n", lines);
    }

    public static List<CoopSlipstreamRecord> decodeSet(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty()) return List.of();
        String[] lines = encoded.split("\n", -1);
        checkCount(lines.length, 0, MAX_STREAMS, "streams");
        List<CoopSlipstreamRecord> records = new ArrayList<>(lines.length);
        Set<String> ids = new HashSet<>();
        for (String line : lines) {
            CoopSlipstreamRecord record = decode(line);
            if (!ids.add(record.hostId())) throw new IllegalArgumentException("Duplicate stream id");
            records.add(record);
        }
        return List.copyOf(records);
    }

    public static String encodeIds(Collection<String> ids) {
        List<String> checked = checkedStrings(ids, "stream ids", MAX_STREAMS);
        List<String> sorted = new ArrayList<>(checked);
        sorted.sort(null);
        List<String> lines = new ArrayList<>(sorted.size());
        for (String id : sorted) {
            checkedString(id, "stream id", MAX_ID_LENGTH, false);
            lines.add(CoopDelimited.field(id));
        }
        return String.join("\n", lines);
    }

    public static List<String> decodeIds(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty()) return List.of();
        // Each id can at most double in size through delimiter escaping.
        if (encoded.length() > MAX_STREAMS * (2 * MAX_ID_LENGTH + 1)) {
            throw new IllegalArgumentException("Slipstream manifest too large");
        }
        String[] lines = encoded.split("\n", -1);
        checkCount(lines.length, 0, MAX_STREAMS, "stream ids");
        List<String> ids = new ArrayList<>(lines.length);
        for (String line : lines) {
            Reader r = new Reader(line);
            ids.add(checkedString(r.next(), "stream id", MAX_ID_LENGTH, false));
            r.end();
        }
        return checkedStrings(ids, "stream ids", MAX_STREAMS);
    }

    private static List<String> checkedStrings(Collection<String> values, String name, int cap) {
        Objects.requireNonNull(values, name);
        checkCount(values.size(), 0, cap, name);
        List<String> copy = new ArrayList<>(values.size());
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            checkedString(value, name, MAX_ID_LENGTH, false);
            if (!seen.add(value)) throw new IllegalArgumentException("Duplicate " + name + ": " + value);
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static String checkedString(String value, String name, int cap, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isBlank()) || value.length() > cap) {
            throw new IllegalArgumentException("Invalid slipstream " + name);
        }
        return value;
    }

    private static String optionalString(String value, String name) {
        return value == null ? null : checkedString(value, name, MAX_STRING_LENGTH, true);
    }

    private static float[] checkedNoise(float[] noise, String name) {
        if (noise == null) return null;
        checkCount(noise.length, 2, MAX_NOISE_POINTS, name);
        float[] copy = noise.clone();
        for (float value : copy) unitFraction(value, name);
        return copy;
    }

    private static void checkCount(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Slipstream " + name + " outside " + min + ".." + max
                    + ": " + value);
        }
    }

    private static void finite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite slipstream " + name);
    }

    private static void nonnegative(float value, String name) {
        finite(value, name);
        if (value < 0f) throw new IllegalArgumentException("Negative slipstream " + name);
    }

    private static void positive(float value, String name) {
        finite(value, name);
        if (value <= 0f) throw new IllegalArgumentException("Nonpositive slipstream " + name);
    }

    private static void unitFraction(float value, String name) {
        nonnegative(value, name);
        if (value > 1f) throw new IllegalArgumentException("Slipstream " + name + " exceeds 1");
    }

    private static float parseFloat(String value) {
        float result = Float.parseFloat(value);
        finite(result, "number");
        return result;
    }

    private static final class Writer {
        private final StringBuilder out = new StringBuilder();
        private boolean first = true;

        void add(Object value) {
            if (!first) out.append('|');
            first = false;
            out.append(CoopDelimited.field(value.toString()));
        }

        void nullable(String value) { add(value == null ? "0" : "1" + value); }

        void noise(float[] noise) {
            add(noise == null ? -1 : noise.length);
            if (noise != null) for (float value : noise) add(value);
        }

        String finish() {
            if (out.length() > MAX_ENCODED_CHARACTERS) {
                throw new IllegalArgumentException("Encoded slipstream record too large");
            }
            return out.toString();
        }
    }

    private static final class Reader {
        private final List<String> fields;
        private int index;

        Reader(String encoded) {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.length() > MAX_ENCODED_CHARACTERS) {
                throw new IllegalArgumentException("Encoded slipstream record too large");
            }
            // CoopDelimited tolerates unknown and dangling escapes; recipes must fail closed.
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (c == '\n' || c == '\r') throw new IllegalArgumentException("Unescaped record newline");
                if (c == '\\') {
                    if (++i == encoded.length()) throw new IllegalArgumentException("Truncated escape");
                    char escaped = encoded.charAt(i);
                    if (escaped != '\\' && escaped != '|' && escaped != 'n' && escaped != 'r') {
                        throw new IllegalArgumentException("Unknown record escape");
                    }
                }
            }
            fields = CoopDelimited.split(encoded);
        }

        String next() {
            if (index == fields.size()) throw new IllegalArgumentException("Truncated slipstream record");
            return fields.get(index++);
        }

        int integer() { return Integer.parseInt(next()); }
        long longInteger() { return Long.parseLong(next()); }
        float number() { return parseFloat(next()); }
        boolean bool() {
            String value = next();
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw new IllegalArgumentException("Invalid slipstream boolean: " + value);
        }

        String nullable() {
            String value = next();
            if ("0".equals(value)) return null;
            if (value.startsWith("1")) return value.substring(1);
            throw new IllegalArgumentException("Invalid nullable slipstream string");
        }

        int count(int min, int max, String name) {
            int count = integer();
            checkCount(count, min, max, name);
            return count;
        }

        float[] noise() {
            int count = integer();
            if (count == -1) return null;
            checkCount(count, 2, MAX_NOISE_POINTS, "noise points");
            float[] values = new float[count];
            for (int i = 0; i < count; i++) values[i] = number();
            return values;
        }

        void end() {
            if (index != fields.size()) throw new IllegalArgumentException("Trailing slipstream fields");
        }
    }
}
