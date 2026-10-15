package coop.campaign;

import coop.campaign.CoopSlipstreamRecord.Despawn;
import coop.campaign.CoopSlipstreamRecord.Fade;
import coop.campaign.CoopSlipstreamRecord.Fader;
import coop.campaign.CoopSlipstreamRecord.FaderState;
import coop.campaign.CoopSlipstreamRecord.Kind;
import coop.campaign.CoopSlipstreamRecord.Params;
import coop.campaign.CoopSlipstreamRecord.Segment;
import coop.campaign.CoopSlipstreamRecord.SurgeWaveState;
import coop.campaign.CoopSlipstreamRecord.Timeline;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSlipstreamRecordTest {
    // Three header fields followed by all 28 non-Color params.
    private static final int TAG_COUNT = 31;

    @Test
    void surgeRoundTripsEveryNondefaultParamAndUnfinishedFade() {
        CoopSlipstreamRecord record = surge("host-surge", 3, 32);

        CoopSlipstreamRecord decoded = CoopSlipstreamRecord.decode(record.encode());

        assertEquals(record, decoded);
        assertEquals(record.hashCode(), decoded.hashCode());
        assertEquals(Kind.OTHER, decoded.kind());
        assertTrue(decoded.unfinishedSurgeFadeIn());
        assertEquals(-0.35f, decoded.fade().despawnDelay());
        assertFalse(decoded.params().slowDownInWiderSections());
        assertTrue(decoded.params().forceNoWindVisualEffectOnFleets());
        assertEquals(FaderState.IN, decoded.segments().get(0).fader().state());
        assertEquals(FaderState.OUT, decoded.segments().get(1).fader().state());
        assertEquals(FaderState.IDLE, decoded.segments().get(2).fader().state());
        assertEquals(record.timeline(), decoded.timeline());
        assertEquals(record.surgeWave(), decoded.surgeWave());
    }

    @Test
    void fourHundredSegmentsAndBoth512PointNoiseArraysFitCurrentTcpFrameCap() {
        CoopSlipstreamRecord record = surge("host-realistic-stream", 400, 512);
        String encoded = record.encode();
        // Include the real envelope's second layer of JSON escaping, rather than just raw bytes.
        CoopMessages.Message message = CoopMessages.streamAdd("session", 1L, 123L, encoded)
                .withSenderId("host");
        String frame = CoopMessages.encode(message);

        assertTrue(frame.getBytes(StandardCharsets.UTF_8).length < CoopNetService.MAX_FRAME_BYTES,
                "the transport hard cap includes envelope escaping");
        assertEquals(record, CoopSlipstreamRecord.decode(encoded));
        assertEquals(encoded, CoopMessages.requiredPayloadString(CoopMessages.decode(frame), "stream"));
        int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
        System.out.println("P26 v2 400-segment checkpoint: payload="
                + encoded.getBytes(StandardCharsets.UTF_8).length + " bytes, TCP envelope=" + bytes
                + " bytes, seven active streams at 4Hz=" + (7L * 4L * bytes) + " bytes/s");
    }

    @Test
    void timelineKeepsItsOriginAcrossCheckpointsAndAllowsNegativeCampaignEpochs() {
        CoopSlipstreamRecord base = surge("timeline", 3, 2);
        long origin = -2_000_000L;
        Timeline initial = new Timeline(origin, 7, origin + 7 * CoopSlipstreamRecord.QUANTUM_CAMPAIGN_MILLIS
                + 123L, true, false);
        Timeline later = new Timeline(origin, 18, origin + 18 * CoopSlipstreamRecord.QUANTUM_CAMPAIGN_MILLIS
                + 456L, false, true);
        CoopSlipstreamRecord first = checkpoint(base, initial, base.surgeWave());
        CoopSlipstreamRecord next = checkpoint(base, later,
                new SurgeWaveState(true, true, 3, 4.1f, 4f));

        CoopSlipstreamRecord decodedFirst = CoopSlipstreamRecord.decode(first.encode());
        CoopSlipstreamRecord decodedNext = CoopSlipstreamRecord.decode(next.encode());

        assertEquals(origin, decodedFirst.timeline().originCampaignMillis());
        assertEquals(origin, decodedNext.timeline().originCampaignMillis());
        assertEquals(18, decodedNext.timeline().committedTick());
        assertEquals(origin + 18 * CoopSlipstreamRecord.QUANTUM_CAMPAIGN_MILLIS,
                decodedNext.timeline().committedCampaignMillis());
        assertTrue(decodedFirst.timeline().hostPaused());
        assertFalse(decodedFirst.timeline().hostInHyperspace());
        assertFalse(decodedNext.unfinishedSurgeFadeIn());
        assertEquals(new SurgeWaveState(true, true, 3, 4.1f, 4f), decodedNext.surgeWave(),
                "native completion may overshoot maxElapsed and must retain the actual cursor");
        assertEquals(Timeline.atOrigin(Long.MIN_VALUE, true, true),
                CoopSlipstreamRecord.decode(checkpoint(base,
                        Timeline.atOrigin(Long.MIN_VALUE, true, true), SurgeWaveState.absent()).encode()).timeline());
    }

    @Test
    void timelineRejectsNegativeTicksFutureCommittedStatesAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new Timeline(0, -1, 0, false, true));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(0,
                CoopSlipstreamRecord.MAX_COMMITTED_TICK + 1, Long.MAX_VALUE, false, true));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(0, 1,
                CoopSlipstreamRecord.QUANTUM_CAMPAIGN_MILLIS - 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(100, 0, 99, false, true));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(Long.MAX_VALUE,
                1, Long.MAX_VALUE, false, true));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(Long.MIN_VALUE,
                0, Long.MAX_VALUE, false, true));
    }

    @Test
    void malformedTimelineLongsAndNativeSurgeCursorsAreRejectedBeforeApply() {
        CoopSlipstreamRecord source = surge("id", 3, 2);
        List<String> fields = CoopDelimited.split(source.encode());
        int start = fields.size() - 10;
        for (int position : List.of(start, start + 1, start + 2)) {
            for (String bad : List.of("", "1.0", "NaN", "9223372036854775808")) {
                assertRejected(fields, position, bad);
            }
        }
        assertRejected(fields, start + 1, "-1");
        assertRejected(fields, start + 7, "4");
        assertRejected(fields, start + 8, "-0.1");
        assertRejected(fields, start + 9, "0");
        assertThrows(IllegalArgumentException.class, () -> new SurgeWaveState(false, false, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurgeWaveState(false, true, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurgeWaveState(true, false, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SurgeWaveState(true, false, 0, Float.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> checkpoint(source, source.timeline(),
                new SurgeWaveState(true, false, 4, 0, 1)));
    }

    @Test
    void protocolBuildersCarryOnlyFlatEscapedStringFields() {
        String id = " id|\\\n\r\"\n";
        String manifest = CoopSlipstreamRecord.encodeIds(List.of(id, "another"));
        String stream = surge(id, 3, 32).encode();
        String despawn = new Despawn(id, noise(32), 1.5f, 0.2f, 0f).encode();
        List<CoopMessages.Message> messages = List.of(
                CoopMessages.streamSet("session", 1L, 23L, manifest),
                CoopMessages.streamAdd("session", 2L, 23L, stream),
                CoopMessages.streamDespawn("session", 3L, 23L, despawn),
                CoopMessages.streamRemove("session", 4L, 23L, id));
        List<CoopMessages.Type> types = List.of(CoopMessages.Type.STREAM_SET,
                CoopMessages.Type.STREAM_ADD, CoopMessages.Type.STREAM_DESPAWN,
                CoopMessages.Type.STREAM_REMOVE);
        List<String> keys = List.of("streams", "stream", "stream", "streamId");
        List<String> values = List.of(manifest, stream, despawn, id);
        for (int i = 0; i < messages.size(); i++) {
            CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(messages.get(i)));
            assertEquals(types.get(i), decoded.type());
            assertEquals("session", decoded.sessionId());
            assertEquals(i + 1L, decoded.seq());
            assertEquals(23L, decoded.sentAtMillis());
            assertEquals(values.get(i), CoopMessages.requiredPayloadString(decoded, keys.get(i)));
            assertEquals(1, CoopMessages.decodePayload(decoded).size());
        }
        assertEquals("", CoopMessages.requiredPayloadString(
                CoopMessages.streamSet("session", 5L, 23L, ""), "streams"));
    }

    @Test
    void nullableStringsDistinguishNullEmptyAndMarkerLikeValues() {
        CoopSlipstreamRecord stock = new CoopSlipstreamRecord("stock", Kind.SEASONAL,
                Params.defaults(), List.of(), segments(3), Fade.idle(), false);
        CoopSlipstreamRecord decoded = CoopSlipstreamRecord.decode(stock.encode());
        assertNull(decoded.params().enteringSlipstreamTextOverride());
        assertNull(decoded.params().enteringSlipstreamTextDurationOverride());
        assertNull(decoded.params().name());
        assertNull(decoded.fade().spawnNoise());
        assertNull(decoded.fade().despawnNoise());

        for (String text : List.of("", "0", "1", "1name", "0\\|\n\r")) {
            List<String> fields = CoopDelimited.split(stock.encode());
            fields.set(3, "1" + text);
            fields.set(27, "1" + text);
            CoopSlipstreamRecord withText = CoopSlipstreamRecord.decode(join(fields));
            assertEquals(text, withText.params().enteringSlipstreamTextOverride());
            assertEquals(text, withText.params().name());
            assertEquals(withText, CoopSlipstreamRecord.decode(withText.encode()));
        }
    }

    @Test
    void escapedIdsTagsAndParamTextRemainOnOneLineAndSurviveSets() {
        CoopSlipstreamRecord a = surge("id|\\\n\r", 3, 32);
        CoopSlipstreamRecord b = surge("other", 3, 32);

        assertFalse(a.encode().contains("\n"));
        assertFalse(a.encode().contains("\r"));
        assertEquals(a, CoopSlipstreamRecord.decode(a.encode()));
        assertEquals(List.of(a, b), CoopSlipstreamRecord.decodeSet(
                CoopSlipstreamRecord.encodeSet(List.of(b, a))));
        assertEquals(CoopSlipstreamRecord.encodeSet(List.of(a, b)),
                CoopSlipstreamRecord.encodeSet(List.of(b, a)));
        assertEquals(List.of(), CoopSlipstreamRecord.decodeSet(""));
        assertEquals("", CoopSlipstreamRecord.encodeSet(List.of()));
    }

    @Test
    void primitiveArraysAndListsAreImmutableCopies() {
        float[] spawn = noise(32);
        float[] despawn = noise(32);
        Fade fade = new Fade(spawn, 1f, 0.4f, despawn, -1f, 2f, 0.2f);
        List<String> tags = new ArrayList<>(List.of("tag"));
        List<Segment> segments = new ArrayList<>(segments(3));
        CoopSlipstreamRecord record = new CoopSlipstreamRecord("id", Kind.OTHER,
                Params.defaults(), tags, segments, fade, true);
        String before = record.encode();

        spawn[0] = 0.95f;
        despawn[0] = 0.95f;
        fade.spawnNoise()[0] = 0.85f;
        fade.despawnNoise()[0] = 0.85f;
        tags.clear();
        segments.clear();

        assertEquals(before, record.encode());
        assertThrows(UnsupportedOperationException.class, () -> record.terrainTags().clear());
        assertThrows(UnsupportedOperationException.class, () -> record.segments().clear());
        assertArrayEquals(noise(32), fade.spawnNoise());
        assertArrayEquals(noise(32), fade.despawnNoise());
        assertEquals(fade, CoopSlipstreamRecord.decode(before).fade());
        assertNotEquals(fade, Fade.idle());
    }

    @Test
    void rejectsEveryTruncatedFieldAndTrailingData() {
        List<String> fields = CoopDelimited.split(surge("id", 3, 2).encode());
        for (int length = 0; length < fields.size(); length++) {
            String truncated = join(fields.subList(0, length));
            assertThrows(IllegalArgumentException.class, () -> CoopSlipstreamRecord.decode(truncated),
                    "accepted prefix with " + length + " fields");
        }
        assertRejected(fields, fields.size() - 1, "");
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decode(join(fields) + "|extra"));
    }

    @Test
    void rejectsMalformedVersionKindNullableFieldsAndBooleans() {
        List<String> fields = CoopDelimited.split(surge("id", 3, 2).encode());
        assertRejected(fields, 0, "1");
        assertRejected(fields, 0, "3");
        assertRejected(fields, 0, "1.0");
        assertRejected(fields, 1, "");
        assertRejected(fields, 2, "DYNAMIC");
        assertRejected(fields, 3, "null");
        assertRejected(fields, 27, "00");
        int segmentStart = segmentStart(fields);
        int timelineStart = fields.size() - 10;
        for (int index : List.of(5, 23, segmentStart + 8, segmentStart + 9,
                timelineStart + 3, timelineStart + 4, timelineStart + 5, timelineStart + 6)) {
            for (String value : List.of("TRUE", "1", "yes", "")) assertRejected(fields, index, value);
        }
        assertRejected(fields, segmentStart + 7, "FADE_IN");
    }

    @Test
    void rejectsNonfiniteValuesInEveryFloatField() {
        List<String> fields = CoopDelimited.split(surge("id", 3, 2).encode());
        List<Integer> floats = new ArrayList<>(List.of(4, 10, 11, 12, 13, 15, 16, 17, 18,
                19, 20, 24, 25, 26, 28, 29, 30));
        int segmentStart = segmentStart(fields);
        for (int i = 0; i < 3; i++) {
            for (int field = 0; field < 7; field++) floats.add(segmentStart + i * 10 + field);
        }
        int noiseStart = segmentStart + 30;
        // Each 2-entry noise block has its count first; all remaining fade fields are floats.
        floats.addAll(List.of(noiseStart + 1, noiseStart + 2, noiseStart + 3, noiseStart + 4,
                noiseStart + 6, noiseStart + 7, noiseStart + 8, noiseStart + 9, noiseStart + 10));
        floats.addAll(List.of(fields.size() - 2, fields.size() - 1));
        for (int index : floats) {
            for (String value : List.of("NaN", "Infinity", "-Infinity", "1e1000")) {
                assertRejected(fields, index, value);
            }
        }
    }

    @Test
    void rejectsOutOfBoundsCountsBeforeAllocatingRecipes() {
        List<String> fields = CoopDelimited.split(surge("id", 3, 2).encode());
        for (String count : List.of("-1", "129", "2147483647")) {
            assertRejected(fields, TAG_COUNT, count);
        }
        int segmentCount = segmentStart(fields) - 1;
        for (String count : List.of("-1", "0", "2", "4097", "2147483647")) {
            assertRejected(fields, segmentCount, count);
        }
        int noiseStart = segmentStart(fields) + 30;
        for (String count : List.of("-2", "0", "1", "513", "2147483647")) {
            assertRejected(fields, noiseStart, count);
            assertRejected(fields, noiseStart + 5, count);
        }
        assertRejected(fields, noiseStart + 1, "-0.1");
        assertRejected(fields, noiseStart + 1, "1.1");
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decode("x".repeat(
                        CoopSlipstreamRecord.MAX_ENCODED_CHARACTERS + 1)));
    }

    @Test
    void rejectsHostGeometryThatWouldOverflowOrAllocateAnEnormousDistanceLookup() {
        Fader idle = new Fader(1f, 0f, 0f, FaderState.IDLE, false, false);
        assertThrows(IllegalArgumentException.class,
                () -> new Segment(Float.MAX_VALUE, 0f, 10f, 1f, idle));
        assertThrows(IllegalArgumentException.class,
                () -> new Segment(0f, 0f, 100_001f, 1f, idle));
        assertThrows(IllegalArgumentException.class,
                () -> new CoopSlipstreamRecord("id", Kind.SEASONAL, Params.defaults(), List.of(),
                        List.of(new Segment(-1_000_000f, 0f, 10f, 1f, idle),
                                new Segment(1_000_000f, 0f, 10f, 1f, idle),
                                new Segment(-1_000_000f, 0f, 10f, 1f, idle)), Fade.idle(), false));
        assertThrows(IllegalArgumentException.class, () -> new Fader(1.01f, 1f, 1f,
                FaderState.IN, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Fader(0f, -1f, 1f,
                FaderState.OUT, false, false));
    }

    @Test
    void strictEscapesRejectDamageThatCoopDelimitedWouldOtherwiseTolerate() {
        String valid = surge("id", 3, 2).encode();
        assertThrows(IllegalArgumentException.class, () -> CoopSlipstreamRecord.decode(valid + "\\"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decode(valid.replace("|id|", "|id\\q|")));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decode(valid.replace("|id|", "|id\n|")));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decode(valid.replace("|id|", "|id\r|")));
    }

    @Test
    void manifestRoundTripsEscapedIdsAndUsesDeterministicOrder() {
        List<String> ids = List.of("seasonal", "surge|\\\n\r", "another");
        String encoded = CoopSlipstreamRecord.encodeIds(ids);
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        assertEquals(sorted, CoopSlipstreamRecord.decodeIds(encoded));
        assertEquals("", CoopSlipstreamRecord.encodeIds(List.of()));
        assertEquals(List.of(), CoopSlipstreamRecord.decodeIds(""));
        assertThrows(UnsupportedOperationException.class,
                () -> CoopSlipstreamRecord.decodeIds(encoded).clear());
    }

    @Test
    void manifestRejectsDuplicateBlankMalformedAndOversizedIds() {
        for (String encoded : List.of("id\nid", "id\n", "\nid", "id|extra", "id\\q", "id\\")) {
            assertThrows(IllegalArgumentException.class, () -> CoopSlipstreamRecord.decodeIds(encoded));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decodeIds("x".repeat(CoopSlipstreamRecord.MAX_ID_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.encodeIds(List.of("id", "id")));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decodeIds(String.join("\n",
                        Collections.nCopies(CoopSlipstreamRecord.MAX_STREAMS + 1, "id"))));
    }

    @Test
    void setsRejectDuplicateIdsAndMalformedLaterRowsAsAWhole() {
        CoopSlipstreamRecord a = surge("id", 3, 2);
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.encodeSet(List.of(a, a)));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decodeSet(a.encode() + "\n" + a.encode()));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decodeSet(a.encode() + "\nmalformed"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopSlipstreamRecord.decodeSet(a.encode() + "\n"));
    }

    @Test
    void despawnRoundTripsOnlyTheChangedFieldsAndDefensivelyCopiesNoise() {
        float[] source = noise(512);
        Despawn d = new Despawn("id|\\\n", source, -2.4f, 27f, 1.7f);
        source[0] = 0.9f;
        d.noise()[0] = 0.8f;
        Despawn decoded = Despawn.decode(d.encode());
        assertEquals(d, decoded);
        assertEquals(d.hashCode(), decoded.hashCode());
        assertArrayEquals(noise(512), decoded.noise());
        assertEquals(new Despawn("expired", null, -1f, 0.2f, 0.3f),
                Despawn.decode(new Despawn("expired", null, -1f, 0.2f, 0.3f).encode()));
        assertTrue(d.encode().length() < surge("id", 400, 512).encode().length());
    }

    @Test
    void despawnRejectsMalformedTruncatedOversizedAndNonfiniteRecords() {
        List<String> fields = CoopDelimited.split(new Despawn("id", noise(2), 1.5f, 0.2f, 0f).encode());
        for (int length = 0; length < fields.size(); length++) {
            String prefix = join(fields.subList(0, length));
            assertThrows(IllegalArgumentException.class, () -> Despawn.decode(prefix));
        }
        for (int index : List.of(3, 4, 5, 6, 7)) {
            List<String> changed = new ArrayList<>(fields);
            changed.set(index, "NaN");
            assertThrows(IllegalArgumentException.class, () -> Despawn.decode(join(changed)));
        }
        fields.set(0, "1");
        assertThrows(IllegalArgumentException.class, () -> Despawn.decode(join(fields)));
        fields.set(0, Integer.toString(CoopSlipstreamRecord.VERSION));
        fields.set(2, "513");
        assertThrows(IllegalArgumentException.class, () -> Despawn.decode(join(fields)));
    }

    private static CoopSlipstreamRecord surge(String id, int segmentCount, int noiseCount) {
        Params p = new Params("Entering|surge\\\n\r", 7.3f, true,
                "sprite-a", "sprite-b", "sprite-c", "edge", 333f, 222f, 111f, 4567f,
                321, 21f, 41f, 1.2f, 7.4f, 0.2f, 0.42f, 450, 99, false,
                0.25f, 2.25f, 200f, "surge|name\\\n", 0.3f, -2.3f, 0.5f);
        Fade f = new Fade(noise(noiseCount), 2.3f, 0.35f,
                noise(noiseCount), -0.35f, 0.2f, 0.11f);
        return new CoopSlipstreamRecord(id, Kind.OTHER, p,
                List.of("visible|\\\n\r", "second"), segments(segmentCount), f,
                new Timeline(-2_000_000L, 12L, -1_000_000L, false, true),
                new SurgeWaveState(true, false, 2, 0.13f, 4.2f));
    }

    private static CoopSlipstreamRecord checkpoint(CoopSlipstreamRecord source,
                                                   Timeline timeline, SurgeWaveState wave) {
        return new CoopSlipstreamRecord(source.hostId(), source.kind(), source.params(),
                source.terrainTags(), source.segments(), source.fade(), timeline, wave);
    }

    private static List<Segment> segments(int count) {
        List<Segment> segments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Fader fader = new Fader((i % 11) / 10f, i + 0.2f, i + 0.3f,
                    FaderState.values()[i % 3], i % 2 == 0, i % 3 == 0);
            segments.add(new Segment(-50_000.125f + i * 300.3f, i * 0.7f,
                    600.25f + i, 0.9f, fader));
        }
        return segments;
    }

    private static float[] noise(int count) {
        float[] noise = new float[count];
        for (int i = 0; i < count; i++) noise[i] = (i % 10) / 10f;
        return noise;
    }

    private static int segmentStart(List<String> fields) {
        return TAG_COUNT + Integer.parseInt(fields.get(TAG_COUNT)) + 2;
    }

    private static void assertRejected(List<String> original, int index, String value) {
        List<String> fields = new ArrayList<>(original);
        fields.set(index, value);
        assertThrows(IllegalArgumentException.class, () -> CoopSlipstreamRecord.decode(join(fields)),
                "accepted invalid field " + index + ": " + value);
    }

    private static String join(List<String> fields) {
        return String.join("|", fields.stream().map(CoopDelimited::field).toList());
    }
}
