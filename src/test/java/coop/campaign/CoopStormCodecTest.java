package coop.campaign;

import coop.net.CoopMessages;
import coop.storm.CoopStormControl;
import coop.storm.CoopStormGrid;
import coop.storm.CoopStormSnapshot;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

class CoopStormCodecTest {
    private static final String ID = "77032526-2d55-48e2-a9a0-7b866330ab20";

    @Test void exactNativeFieldsSignedTimestampsAndOvershootRoundTrip() {
        CoopStormSnapshot snapshot = small(1, 1, -9000, List.of(
                new CoopStormSnapshot.Tracker(1, 1, Long.MAX_VALUE, 4, -0.1f, -1, -0.01f, 0.2f, 1.2f, 5),
                new CoopStormSnapshot.Tracker(0, 0, 2, 1, 1.2f, 0.25f, 0, 0.25f, 0, 1)));
        assertEquals(snapshot, CoopStormCodec.decodeSnapshot(CoopStormCodec.encodeSnapshot(snapshot)));
        assertFalse(CoopStormCodec.encodeSnapshot(snapshot).contains("\n"));
    }

    @Test void malformedEnvelopeInflationAndVersionFailBeforeNativeApply() {
        String encoded = CoopStormCodec.encodeSnapshot(small(1, 1, -9000, List.of()));
        byte[] raw = raw(encoded);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot("STORM2:1:AAAA"));
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot("STORM1:" + (CoopStormCodec.MAX_RAW_SNAPSHOT_BYTES + 1) + ":AAAA"));
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(encoded.substring(0, encoded.length() - 4)));
            byte[] badMagic = raw.clone(); ByteBuffer.wrap(badMagic).putInt(123);
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(envelope(badMagic)));
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(envelope(Arrays.copyOf(raw, raw.length + 1))));
            String[] pieces = encoded.split(":", 3);
            byte[] trailing = Base64.getDecoder().decode(pieces[2]);
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(pieces[0] + ':' + pieces[1] + ':'
                    + Base64.getEncoder().encodeToString(Arrays.copyOf(trailing, trailing.length + 1))));
        });
    }

    @Test void decodedCountsDimensionsStateAndFiniteTimersAreValidated() {
        byte[] good = raw(CoopStormCodec.encodeSnapshot(small(1, 1, -9000,
                List.of(new CoopStormSnapshot.Tracker(0, 0, 1, 1, 1, 0.1f, 0, 0.1f, 0, 1)))));
        byte[] bad = good.clone(); ByteBuffer.wrap(bad).putInt(40, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(envelope(bad)));
        int gridLength = ByteBuffer.wrap(good).getInt(48), countOffset = 52 + gridLength, trackerOffset = countOffset + 4;
        byte[] badCount = good.clone(); ByteBuffer.wrap(badCount).putInt(countOffset, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(envelope(badCount)));
        byte[] badState = good.clone(); badState[trackerOffset + 16] = 5;
        assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(envelope(badState)));
        byte[] badFloat = good.clone(); ByteBuffer.wrap(badFloat).putFloat(trackerOffset + 17, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeSnapshot(envelope(badFloat)));
    }

    @Test void twentyThousandNativeShapedTrackersAndActualSizedGridFitTcpFrame() {
        int width = 810, height = 500;
        byte[][] cells = new byte[width][height]; Random random = new Random(426);
        for (int i = 0; i < width; i++) for (int j = 0; j < height; j++) cells[i][j] = (byte) random.nextInt(3);
        List<CoopStormSnapshot.Tracker> trackers = new ArrayList<>();
        for (int n = 0; n < 20_000; n++) trackers.add(new CoopStormSnapshot.Tracker(n % width, n / width,
                n + 1L, n % 4 + 1, random.nextFloat() * 3f, random.nextFloat() * 0.2f,
                0, 0.1f + random.nextFloat() * 0.2f, 0, n % 7 == 0 ? 5 : 1));
        CoopStormSnapshot snapshot = new CoopStormSnapshot(100, 10, -20_000, -10_000, 2,
                width, height, CoopStormGrid.encode(cells), trackers);
        String encoded = CoopStormCodec.encodeSnapshot(snapshot);
        assertTrue(CoopSlipstreamAuthority.fitsFrame(CoopMessages.stormSet("session-a", 1, 0, encoded)));
        assertEquals(snapshot, CoopStormCodec.decodeSnapshot(encoded));
    }

    @Test void typedNativeControlsRoundTripAndRejectTruncationTrailingAndArbitrarySignals() {
        for (CoopStormControl control : List.of(CoopStormControl.tileOff(ID, 1, -100, 200, 500, 0.25f),
                CoopStormControl.surgeWindow(ID, 1, -100, 200, 16000, 5),
                CoopStormControl.surgeWindow(ID, 1, -100, 200, 0, 1))) {
            String encoded = CoopStormCodec.encodeControl(control);
            assertEquals(control, CoopStormCodec.decodeControl(encoded));
            for (int end = 0; end < encoded.length(); end++) {
                String partial = encoded.substring(0, end);
                assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeControl(partial));
            }
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeControl(encoded + "1:x"));
            assertThrows(IllegalArgumentException.class, () -> CoopStormCodec.decodeControl(encoded.replace(control.action().name(), "BAD_KIND")));
        }
    }

    @Test void denseSurgeCheckpointUsesBoundedFramesAndReassemblesOutOfOrderAtomically() {
        CoopStormSnapshot snapshot = large(8, 136_000);
        List<String> parts = CoopStormCodec.encodeSnapshotParts(snapshot);
        assertTrue(parts.size() > 2, "dense surges must fragment rather than stop replication");
        assertTrue(parts.size() <= CoopStormCodec.MAX_FRAGMENTS);
        CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
        for (int index = parts.size() - 1; index > 0; index--) {
            assertTrue(CoopSlipstreamAuthority.fitsFrame(CoopMessages.stormSet("session-a", index, 0, parts.get(index))));
            assertNull(assembler.accept(parts.get(index), true, 100));
            assertNull(assembler.accept(parts.get(index), true, 101), "exact duplicate cannot count twice");
        }
        assertEquals(snapshot, assembler.accept(parts.get(0), true, 102));
        assertFalse(assembler.hasPending());
        for (String part : parts) assertNull(assembler.accept(part, true, 103), "completed duplicates cannot reapply");
    }

    @Test void fragmentMetadataTypeAndDuplicateConflictsFailWithoutPublishingAnything() {
        List<String> parts = CoopStormCodec.encodeSnapshotParts(large(8, 20_000));
        assertTrue(parts.size() > 1);
        CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
        assertNull(assembler.accept(parts.get(0), true, 0));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(parts.get(1), false, 1));
        String first = parts.get(0), altered = first.substring(0, first.length() - 1) + (first.endsWith("A") ? 'B' : 'A');
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(altered, true, 1));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(first.replaceFirst(":0:", ":99:"), true, 1));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(first.substring(0, first.length() - 1), true, 1));
        assertEquals(1, assembler.pendingParts());
    }

    @Test void completedSmallCheckpointsAllowExactCrossTypeDuplicatesButRejectConflictingBytes() {
        CoopStormSnapshot snapshot = small(1, 1, -9000, List.of());
        String whole = CoopStormCodec.encodeSnapshot(snapshot);
        CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
        assertEquals(snapshot, assembler.accept(whole, true, 0));
        assertEquals(snapshot, assembler.accept(whole, true, 1));
        assertEquals(snapshot, assembler.accept(whole, false, 2));
        String conflict = CoopStormCodec.encodeSnapshot(small(1, 1, -8999, List.of()));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(conflict, false, 2));
        assembler.reset();
        assertEquals(snapshot, assembler.accept(whole, false, 3));

        var large = large(8, 20_000); var parts = CoopStormCodec.encodeSnapshotParts(large);
        assertNull(assembler.accept(parts.get(0), false, 4));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.accept(whole.substring(0, whole.length() - 1), true, 5));
        assertEquals(1, assembler.pendingParts(), "malformed whole checkpoint must leave valid partial assembly intact");
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(whole, false, 6));
        assertEquals(1, assembler.pendingParts(), "stale GRID cannot discard a newer partial assembly");
        CoopStormSnapshot result = null;
        for (int index = 1; index < parts.size(); index++) result = assembler.accept(parts.get(index), false, 7);
        assertEquals(large, result);
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(parts.get(0), false, 8, 9));
    }

    @Test void completedWholeAndSplitFormsShareIdentityAcrossMessageTypes() {
        var snapshot = large(8, 20_000);
        var whole = CoopStormCodec.encodeSnapshot(snapshot);
        var parts = CoopStormCodec.encodeSnapshotParts(snapshot);
        CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
        assertEquals(snapshot, assembler.accept(whole, true, 0));
        for (String part : parts) assertNull(assembler.accept(part, false, 1));
        assembler.reset();
        CoopStormSnapshot result = null;
        for (String part : parts) result = assembler.accept(part, false, 2);
        assertEquals(snapshot, result);
        assertEquals(snapshot, assembler.accept(whole, true, 3));
    }

    @Test void freshSetSupersedesInterruptedGridAndExpiredAssembliesCannotMixFragments() {
        var snapshot = large(8, 20_000); var next = large(9, 20_000);
        var oldParts = CoopStormCodec.encodeSnapshotParts(snapshot); var newParts = CoopStormCodec.encodeSnapshotParts(next);
        CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
        assertNull(assembler.accept(oldParts.get(0), false, 0));
        CoopStormSnapshot result = null;
        for (String part : newParts) result = assembler.accept(part, true, 10);
        assertEquals(next, result);
        assembler.reset(); assertNull(assembler.accept(oldParts.get(0), true, 0));
        for (int i = 1; i < oldParts.size(); i++)
            assertNull(assembler.accept(oldParts.get(i), true, CoopStormCodec.ASSEMBLY_TIMEOUT_MILLIS + 1));
        assertTrue(assembler.hasPending(), "expired first fragment cannot complete a later batch");
        assembler.reset(); assertFalse(assembler.hasPending());
        for (String part : oldParts) result = assembler.accept(part, true, 100_000);
        assertEquals(snapshot, result);
    }

    @Test void wholeCheckpointHashMustMatchBeforeDecodedSnapshotCanEscapeAssembly() {
        List<String> parts = CoopStormCodec.encodeSnapshotParts(large(8, 20_000));
        CoopStormCodec.Assembler assembler = new CoopStormCodec.Assembler();
        for (int index = 0; index < parts.size(); index++) {
            String[] fields = parts.get(index).split(":", 7); fields[5] = "0".repeat(64);
            String altered = String.join(":", fields);
            if (index == parts.size() - 1) assertThrows(IllegalArgumentException.class, () -> assembler.accept(altered, true, 0));
            else assertNull(assembler.accept(altered, true, 0));
        }
        assertFalse(assembler.hasPending());
    }

    @Test void childLoaderCodecAndAuthorityHaveNoSandboxBlockedIoDependencies() throws Exception {
        for (String source : List.of("CoopStormCodec.java", "CoopStormAuthority.java")) {
            String code = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/coop/campaign", source));
            assertFalse(code.contains("java.io."), source + " must use sandbox-proven buffers");
            assertFalse(code.contains("java.lang.reflect"), source + " must avoid reflection");
        }
        for (String type : List.of("CoopStormCodec", "CoopStormCodec$Assembler", "CoopStormCodec$Part",
                "CoopStormCodec$Pending", "CoopStormCodec$Completed", "CoopStormCodec$Fields", "CoopStormAuthority")) {
            try (var bytes = getClass().getResourceAsStream("/coop/campaign/" + type + ".class")) {
                assertNotNull(bytes, type);
                String pool = new String(bytes.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(pool.contains("java/io/"), type + " constant pool references blocked I/O types");
                assertFalse(pool.contains("java/lang/reflect/"), type + " constant pool references reflection");
            }
        }
    }

    static CoopStormSnapshot small(long revision, long generation, long timestamp, List<CoopStormSnapshot.Tracker> trackers) {
        return new CoopStormSnapshot(revision, generation, -10000, timestamp, 2, 2, 2,
                CoopStormGrid.encode(new byte[][]{{0, 1}, {2, 0}}), trackers);
    }

    static CoopStormSnapshot large(long revision, int count) {
        int width = 810, height = 500; Random random = new Random(426);
        byte[][] cells = new byte[width][height];
        for (int i = 0; i < width; i++) for (int j = 0; j < height; j++) cells[i][j] = (byte) random.nextInt(3);
        List<CoopStormSnapshot.Tracker> trackers = new ArrayList<>();
        for (int n = 0; n < count; n++) trackers.add(new CoopStormSnapshot.Tracker(n % width, n / width,
                n + 1L, n % 4 + 1, random.nextFloat() * 3, random.nextFloat() * 0.2f,
                0, 0.1f + random.nextFloat() * 0.2f, 0, n % 7 == 0 ? 5 : 1));
        return new CoopStormSnapshot(revision, 1, -10000, -9000 + revision, 2,
                width, height, CoopStormGrid.encode(cells), trackers);
    }

    private static byte[] raw(String encoded) {
        String[] parts = encoded.split(":", 3); byte[] raw = new byte[Integer.parseInt(parts[1])];
        Inflater inflater = new Inflater();
        try { inflater.setInput(Base64.getDecoder().decode(parts[2])); assertEquals(raw.length, inflater.inflate(raw)); return raw; }
        catch (Exception failure) { throw new AssertionError(failure); }
        finally { inflater.end(); }
    }

    private static String envelope(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw); deflater.finish(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) { int count = deflater.deflate(buffer); bytes.write(buffer, 0, count); }
            return "STORM1:" + raw.length + ':' + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } finally { deflater.end(); }
    }
}
