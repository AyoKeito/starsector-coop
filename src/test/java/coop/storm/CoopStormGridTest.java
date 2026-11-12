package coop.storm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

class CoopStormGridTest {
    @Test void nativeHighBitFirstLayoutAndColumnMajorArraysRoundTrip() throws Exception {
        byte[][] cells = {{0, 2}, {1, 0}};
        String encoded = CoopStormGrid.encode(cells);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(Base64.getDecoder().decode(encoded));
            byte[] packed = new byte[1];
            assertEquals(1, inflater.inflate(packed));
            assertEquals(0x18, packed[0]);
        } finally { inflater.end(); }
        assertTrue(Arrays.deepEquals(cells, CoopStormGrid.decode(encoded, 2, 2)));
        assertTrue(Arrays.deepEquals(new int[][]{{0, 2}, {1, 0}}, CoopStormGrid.decodeNative(encoded, 2, 2)));
        assertEquals(encoded, CoopStormGrid.encode(new int[][]{{0, 2}, {1, 0}}));
    }

    @Test void everyPartialTailLengthPreservesAllCells() {
        for (int width = 1; width <= 11; width++) for (int height = 1; height <= 7; height++) {
            byte[][] cells = new byte[width][height];
            for (int i = 0; i < width; i++) for (int j = 0; j < height; j++) cells[i][j] = (byte) ((i + 2 * j) % 3);
            assertTrue(Arrays.deepEquals(cells, CoopStormGrid.decode(CoopStormGrid.encode(cells), width, height)),
                    "tail lost at " + width + 'x' + height);
        }
    }

    @Test void nativeSeparatelyPaddedHundredByteBlocksDecodeStrictly() {
        byte[] packed = new byte[4096];
        Random random = new Random(127);
        for (int i = 0; i < packed.length; i++) {
            int value = 0;
            for (int shift : new int[]{6, 4, 2, 0}) value |= random.nextInt(3) << shift;
            packed[i] = (byte) value;
        }
        byte[] compressed = compressed(packed);
        assertTrue(compressed.length > 100);
        StringBuilder legacy = new StringBuilder();
        for (int start = 0; start < compressed.length; start += 100)
            legacy.append(Base64.getEncoder().encodeToString(Arrays.copyOfRange(compressed, start, Math.min(start + 100, compressed.length))));
        byte[][] nativeCells = CoopStormGrid.decode(legacy.toString(), 128, 128);
        assertTrue(Arrays.deepEquals(nativeCells, CoopStormGrid.decode(Base64.getEncoder().encodeToString(compressed), 128, 128)));
    }

    @Test void malformedTruncatedTrailingAndOversizedInflationFailWithoutHanging() {
        String valid = CoopStormGrid.encode(new byte[][]{{1, 2}, {0, 1}});
        byte[] bytes = Base64.getDecoder().decode(valid);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode("not base64", 2, 2));
            assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, bytes.length - 1)), 2, 2));
            assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, bytes.length + 1)), 2, 2));
            assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode(Base64.getEncoder().encodeToString(compressed(new byte[4096])), 2, 2));
            assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode(valid, 3, 3));
            assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode("AQ==AQ==", 1, 1));
        });
    }

    @Test void invalidTileCodesAndNonzeroTailPaddingCannotEnterNativeWorld() {
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode(Base64.getEncoder().encodeToString(compressed(new byte[]{(byte) 0xc0})), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode(Base64.getEncoder().encodeToString(compressed(new byte[]{0x41})), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.encode(new byte[][]{{3}}));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.encode(new int[][]{{256}}));
    }

    @Test void impossibleDimensionsAndRaggedArraysAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.cellCount(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.cellCount(4096, 4096));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.decode("", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.encode(new byte[][]{{1}, {1, 2}}));
        assertThrows(IllegalArgumentException.class, () -> CoopStormGrid.encode(new byte[0][]));
    }

    private static byte[] compressed(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw); deflater.finish();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[100];
            while (!deflater.finished()) { int count = deflater.deflate(buffer); bytes.write(buffer, 0, count); }
            return bytes.toByteArray();
        } finally { deflater.end(); }
    }
}
