package coop.storm;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Native-compatible two-bit storm tiles, with bounded and terminating decompression. */
public final class CoopStormGrid {
    public static final int MAX_CELLS = 4_194_304;
    public static final int MAX_DIMENSION = 16_384;
    public static final int MAX_ENCODED_CHARS = 2_097_152;
    private static final int MAX_COMPRESSED_BYTES = MAX_CELLS / 4 + 65_536;

    private CoopStormGrid() { }

    public static int cellCount(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_CELLS)
            throw new IllegalArgumentException("Storm grid dimensions exceed supported bounds");
        return Math.multiplyExact(width, height);
    }

    /** Native layout is cells[x][y], flattened x first, high-to-low two-bit pairs. */
    public static String encode(byte[][] cells) {
        Objects.requireNonNull(cells, "storm cells");
        int width = cells.length;
        if (width == 0 || cells[0] == null) throw new IllegalArgumentException("Empty storm grid");
        int height = cells[0].length, count = cellCount(width, height);
        byte[] packed = new byte[(count + 3) / 4];
        for (int x = 0; x < width; x++)
            if (cells[x] == null || cells[x].length != height)
                throw new IllegalArgumentException("Ragged storm grid");
        for (int index = 0; index < count; index++) {
            int value = cells[index % width][index / width];
            if (value < 0 || value > 2) throw new IllegalArgumentException("Invalid native storm tile value");
            packed[index / 4] |= (byte) (value << (6 - 2 * (index % 4)));
        }
        return Base64.getEncoder().encodeToString(deflate(packed));
    }

    public static String encode(int[][] cells) {
        Objects.requireNonNull(cells, "storm cells");
        if (cells.length == 0 || cells[0] == null) throw new IllegalArgumentException("Empty storm grid");
        int width = cells.length, height = cells[0].length;
        cellCount(width, height);
        byte[][] bytes = new byte[width][height];
        for (int i = 0; i < width; i++) {
            if (cells[i] == null || cells[i].length != height) throw new IllegalArgumentException("Ragged storm grid");
            for (int j = 0; j < height; j++) {
                if (cells[i][j] < 0 || cells[i][j] > 2) throw new IllegalArgumentException("Invalid native storm tile value");
                bytes[i][j] = (byte) cells[i][j];
            }
        }
        return encode(bytes);
    }

    public static int[][] decodeNative(String encoded, int width, int height) {
        byte[][] bytes = decode(encoded, width, height);
        int[][] cells = new int[width][height];
        for (int i = 0; i < width; i++) for (int j = 0; j < height; j++) cells[i][j] = bytes[i][j];
        return cells;
    }

    /** Accepts canonical Base64 and vanilla's separately padded 100-byte block concatenation. */
    public static byte[][] decode(String encoded, int width, int height) {
        int count = cellCount(width, height);
        byte[] packed = inflate(decodeNativeBase64(encoded), (count + 3) / 4);
        int unused = 4 - count % 4;
        if (unused != 4 && (packed[packed.length - 1] & ((1 << (2 * unused)) - 1)) != 0)
            throw new IllegalArgumentException("Nonzero storm grid tail padding");
        byte[][] cells = new byte[width][height];
        for (int index = 0; index < count; index++) {
            byte value = (byte) ((packed[index / 4] >>> (6 - 2 * (index % 4))) & 3);
            if (value == 3) throw new IllegalArgumentException("Invalid native storm tile value");
            cells[index % width][index / width] = value;
        }
        return cells;
    }

    private static byte[] decodeNativeBase64(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_CHARS
                || encoded.length() % 4 != 0 || !encoded.matches("[A-Za-z0-9+/=]+"))
            throw new IllegalArgumentException("Invalid storm grid Base64");
        int padding = encoded.indexOf('=');
        try {
            if (padding < 0 || padding >= encoded.length() - 2) {
                byte[] bytes = strictBase64(encoded);
                if (bytes.length > MAX_COMPRESSED_BYTES) throw new IllegalArgumentException("Storm grid compressed data too large");
                return bytes;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int cursor = 0;
            while (encoded.length() - cursor > 136) {
                String block = encoded.substring(cursor, cursor + 136);
                byte[] decoded = strictBase64(block);
                if (!block.endsWith("==") || decoded.length != 100)
                    throw new IllegalArgumentException("Invalid legacy storm Base64 block");
                bytes.writeBytes(decoded);
                cursor += 136;
                if (bytes.size() > MAX_COMPRESSED_BYTES) throw new IllegalArgumentException("Storm grid compressed data too large");
            }
            byte[] last = strictBase64(encoded.substring(cursor));
            if (last.length == 0 || last.length > 100) throw new IllegalArgumentException("Invalid final legacy storm block");
            bytes.writeBytes(last);
            if (bytes.size() > MAX_COMPRESSED_BYTES) throw new IllegalArgumentException("Storm grid compressed data too large");
            return bytes.toByteArray();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid storm grid Base64", failure);
        }
    }

    private static byte[] strictBase64(String encoded) {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (!Base64.getEncoder().encodeToString(decoded).equals(encoded))
            throw new IllegalArgumentException("Noncanonical Base64 block");
        return decoded;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw); deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int read = deflater.deflate(buffer);
                if (read == 0) throw new IllegalStateException("Storm grid compressor made no progress");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally { deflater.end(); }
    }

    private static byte[] inflate(byte[] compressed, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] raw = new byte[expected];
            int written = 0;
            while (!inflater.finished() && written < raw.length) {
                int read = inflater.inflate(raw, written, raw.length - written);
                if (read == 0) {
                    if (inflater.finished()) break;
                    throw new IllegalArgumentException("Truncated or stalled storm grid deflate");
                }
                written += read;
            }
            if (written != expected) throw new IllegalArgumentException("Storm grid packed length mismatch");
            byte[] excess = new byte[1];
            if (!inflater.finished() && inflater.inflate(excess) != 0)
                throw new IllegalArgumentException("Storm grid inflation exceeds dimensions");
            if (!inflater.finished() || inflater.getRemaining() != 0)
                throw new IllegalArgumentException("Truncated or trailing storm grid deflate");
            return raw;
        } catch (DataFormatException failure) {
            throw new IllegalArgumentException("Invalid storm grid deflate", failure);
        } finally { inflater.end(); }
    }
}
