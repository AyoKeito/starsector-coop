package coop.campaign;

import coop.storm.CoopStormControl;
import coop.storm.CoopStormGrid;
import coop.storm.CoopStormSnapshot;

import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** One flat-string envelope, bounded deflate, and an exact versioned binary checkpoint. */
public final class CoopStormCodec {
    public static final int MAX_RAW_SNAPSHOT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ENCODED_SNAPSHOT_CHARS = 24 * 1024 * 1024;
    public static final int FRAGMENT_CHARS = 512 * 1024;
    public static final int MAX_FRAGMENTS = 48;
    public static final long ASSEMBLY_TIMEOUT_MILLIS = 60_000;
    public static final int MAX_CONTROL_CHARS = 4096;
    private static final int MAGIC = 0x43535431;
    private static final int TRACKER_BYTES = 41;
    private static final String PREFIX = "STORM1:";
    private static final String CONTROL_PREFIX = "STORM_CONTROL1:";
    private static final String FRAGMENT_PREFIX = "STORM_PART1:";

    private CoopStormCodec() { }

    /** Each element remains one ordinary STORM_SET or STORM_GRID flat-string payload. */
    public static List<String> encodeSnapshotParts(CoopStormSnapshot snapshot) {
        String encoded = encodeSnapshot(snapshot);
        if (encoded.length() <= FRAGMENT_CHARS) return List.of(encoded);
        int count = (encoded.length() + FRAGMENT_CHARS - 1) / FRAGMENT_CHARS;
        if (count > MAX_FRAGMENTS) throw new IllegalArgumentException("Too many storm snapshot fragments");
        String digest = digest(encoded);
        List<String> parts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) parts.add(FRAGMENT_PREFIX + snapshot.revision() + ':' + index + ':'
                + count + ':' + encoded.length() + ':' + digest + ':'
                + encoded.substring(index * FRAGMENT_CHARS, Math.min(encoded.length(), (index + 1) * FRAGMENT_CHARS)));
        return List.copyOf(parts);
    }

    /** One bounded transient assembly. Neither incomplete nor malformed data reaches native World. */
    public static final class Assembler {
        private Pending pending;
        private Completed completed;

        public CoopStormSnapshot accept(String encoded, boolean baseline, long now) {
            return accept(encoded, baseline, now, -1);
        }

        public CoopStormSnapshot accept(String encoded, boolean baseline, long now, long minimumRevision) {
            if (encoded != null && encoded.startsWith(PREFIX)) {
                CoopStormSnapshot snapshot = decodeSnapshot(encoded);
                if (snapshot.revision() < minimumRevision) throw new IllegalArgumentException("Stale storm snapshot revision");
                String hash = digest(encoded);
                checkCompleted(snapshot.revision(), hash);
                if (pending != null && pending.revision == snapshot.revision()
                        && (pending.baseline != baseline || !pending.digest.equals(hash)))
                    throw new IllegalArgumentException("Conflicting storm snapshot assembly identity");
                if (!baseline && pending != null && snapshot.revision() < pending.revision)
                    throw new IllegalArgumentException("Stale storm snapshot revision");
                pending = null;
                completed = new Completed(snapshot.revision(), hash, encoded.length(), pieceHashes(encoded));
                return snapshot;
            }
            Part part = parsePart(encoded);
            if (part.revision < minimumRevision) throw new IllegalArgumentException("Stale storm fragment revision");
            if (completed != null && part.revision == completed.revision) {
                checkCompleted(part.revision, part.digest);
                if (completed.hashes.length != part.count || completed.total != part.total
                        || !completed.hashes[part.index].equals(digest(part.piece)))
                    throw new IllegalArgumentException("Conflicting completed storm fragment");
                return null;
            }
            if (pending != null && expired(pending.started, now)) pending = null;
            if (pending != null && pending.revision == part.revision) {
                if (pending.baseline != baseline || !pending.digest.equals(part.digest)
                        || pending.total != part.total || pending.pieces.length != part.count)
                    throw new IllegalArgumentException("Conflicting storm fragment metadata or message type");
            } else {
                // An authenticated fresh SET supersedes any incomplete incremental picture.
                if (!baseline && pending != null && part.revision < pending.revision)
                    throw new IllegalArgumentException("Stale storm fragment revision");
                pending = new Pending(baseline, part, now);
            }
            String previous = pending.pieces[part.index];
            if (previous != null) {
                if (!previous.equals(part.piece)) throw new IllegalArgumentException("Conflicting duplicate storm fragment");
                return null;
            }
            pending.pieces[part.index] = part.piece; pending.received++;
            if (pending.received < part.count) return null;
            Pending ready = pending; pending = null;
            StringBuilder whole = new StringBuilder(ready.total);
            for (String piece : ready.pieces) whole.append(piece);
            String assembled = whole.toString();
            if (assembled.length() != ready.total || !digest(assembled).equals(ready.digest))
                throw new IllegalArgumentException("Storm snapshot assembly length/hash mismatch");
            CoopStormSnapshot snapshot = decodeSnapshot(assembled);
            if (snapshot.revision() != ready.revision)
                throw new IllegalArgumentException("Storm fragment revision differs from checkpoint");
            String[] hashes = new String[ready.pieces.length];
            for (int index = 0; index < hashes.length; index++) hashes[index] = digest(ready.pieces[index]);
            completed = new Completed(ready.revision, ready.digest, ready.total, hashes);
            return snapshot;
        }

        private void checkCompleted(long revision, String digest) {
            if (completed != null && completed.revision == revision
                    && !completed.digest.equals(digest))
                throw new IllegalArgumentException("Conflicting completed storm snapshot identity");
        }

        private static String[] pieceHashes(String encoded) {
            int count = (encoded.length() + FRAGMENT_CHARS - 1) / FRAGMENT_CHARS;
            String[] hashes = new String[count];
            for (int index = 0; index < count; index++) hashes[index] = digest(encoded.substring(
                    index * FRAGMENT_CHARS, Math.min(encoded.length(), (index + 1) * FRAGMENT_CHARS)));
            return hashes;
        }

        public boolean hasPending() { return pending != null; }
        public int pendingParts() { return pending == null ? 0 : pending.received; }
        public void reset() { pending = null; completed = null; }

        private static boolean expired(long started, long now) {
            try { return now < started || Math.subtractExact(now, started) > ASSEMBLY_TIMEOUT_MILLIS; }
            catch (ArithmeticException failure) { return true; }
        }
    }

    private record Part(long revision, int index, int count, int total, String digest, String piece) { }
    private record Completed(long revision, String digest, int total, String[] hashes) { }
    private static final class Pending {
        final boolean baseline;
        final long revision, started;
        final String digest;
        final int total;
        final String[] pieces;
        int received;
        Pending(boolean baseline, Part part, long started) {
            this.baseline = baseline; this.revision = part.revision; this.started = started;
            this.digest = part.digest; this.total = part.total; this.pieces = new String[part.count];
        }
    }

    private static Part parsePart(String encoded) {
        if (encoded == null || !encoded.startsWith(FRAGMENT_PREFIX) || encoded.length() > FRAGMENT_CHARS + 200)
            throw new IllegalArgumentException("Invalid storm fragment envelope");
        String[] fields = encoded.split(":", 7);
        if (fields.length != 7) throw new IllegalArgumentException("Truncated storm fragment metadata");
        long revision = longInteger(fields[1]);
        int index = integer(fields[2]), count = integer(fields[3]), total = integer(fields[4]);
        if (revision < 0 || count < 2 || count > MAX_FRAGMENTS || index >= count
                || total <= FRAGMENT_CHARS || total > MAX_ENCODED_SNAPSHOT_CHARS
                || count != (total + FRAGMENT_CHARS - 1) / FRAGMENT_CHARS
                || !fields[5].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid storm fragment bounds/identity");
        int length = index == count - 1 ? total - index * FRAGMENT_CHARS : FRAGMENT_CHARS;
        if (fields[6].length() != length) throw new IllegalArgumentException("Storm fragment length mismatch");
        return new Part(revision, index, count, total, fields[5], fields[6]);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : bytes) hex.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    public static String encodeSnapshot(CoopStormSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "storm snapshot");
        CoopStormGrid.decode(snapshot.encodedCells(), snapshot.width(), snapshot.height());
        byte[] grid = snapshot.encodedCells().getBytes(StandardCharsets.US_ASCII);
        long length = 56L + grid.length + (long) snapshot.trackers().size() * TRACKER_BYTES;
        if (length > MAX_RAW_SNAPSHOT_BYTES) throw new IllegalArgumentException("Storm snapshot raw cap exceeded");
        ByteBuffer out = ByteBuffer.allocate((int) length);
        out.putInt(MAGIC);
        out.putLong(snapshot.revision()); out.putLong(snapshot.generation());
        out.putLong(snapshot.commitTimestamp()); out.putLong(snapshot.snapshotTimestamp());
        out.putFloat(snapshot.intervalDurationDays());
        out.putInt(snapshot.width()); out.putInt(snapshot.height());
        out.putInt(grid.length); out.put(grid);
        out.putInt(snapshot.trackers().size());
        for (CoopStormSnapshot.Tracker tracker : snapshot.trackers()) {
            out.putInt(tracker.i()); out.putInt(tracker.j()); out.putLong(tracker.epoch());
            out.put((byte) tracker.state());
            out.putFloat(tracker.waitDays()); out.putFloat(tracker.signal()); out.putFloat(tracker.wane());
            out.putFloat(tracker.maxSignal()); out.putFloat(tracker.maxWane()); out.putFloat(tracker.timeScale());
        }
        byte[] raw = out.array();
        String encoded = PREFIX + raw.length + ':' + Base64.getEncoder().encodeToString(deflate(raw));
        if (encoded.length() > MAX_ENCODED_SNAPSHOT_CHARS)
            throw new IllegalArgumentException("Storm snapshot compressed cap exceeded");
        return encoded;
    }

    public static CoopStormSnapshot decodeSnapshot(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX) || encoded.length() > MAX_ENCODED_SNAPSHOT_CHARS)
            throw new IllegalArgumentException("Invalid storm snapshot envelope");
        int colon = encoded.indexOf(':', PREFIX.length());
        if (colon < 0) throw new IllegalArgumentException("Truncated storm snapshot envelope");
        int rawLength = integer(encoded.substring(PREFIX.length(), colon));
        if (rawLength < 56 || rawLength > MAX_RAW_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("Storm snapshot raw cap exceeded");
        String data = encoded.substring(colon + 1);
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(data);
            if (!Base64.getEncoder().encodeToString(compressed).equals(data))
                throw new IllegalArgumentException("Noncanonical storm snapshot Base64");
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid storm snapshot Base64", failure); }
        byte[] raw = inflate(compressed, rawLength);
        try {
            ByteBuffer in = ByteBuffer.wrap(raw);
            if (in.getInt() != MAGIC) throw new IllegalArgumentException("Unsupported storm snapshot version");
            long revision = in.getLong(), generation = in.getLong(), commit = in.getLong(), timestamp = in.getLong();
            float duration = in.getFloat();
            int width = in.getInt(), height = in.getInt(), cells = CoopStormGrid.cellCount(width, height);
            int gridLength = in.getInt();
            if (gridLength < 1 || gridLength > CoopStormGrid.MAX_ENCODED_CHARS || gridLength > in.remaining() - 4)
                throw new IllegalArgumentException("Storm grid string length exceeds body");
            byte[] gridBytes = new byte[gridLength]; in.get(gridBytes);
            String grid = new String(gridBytes, StandardCharsets.US_ASCII);
            CoopStormGrid.decode(grid, width, height);
            int count = in.getInt();
            if (count < 0 || count > CoopStormSnapshot.MAX_TRACKERS || count > cells
                    || (long) count * TRACKER_BYTES != in.remaining())
                throw new IllegalArgumentException("Storm tracker count/body length mismatch");
            List<CoopStormSnapshot.Tracker> trackers = new ArrayList<>(count);
            for (int index = 0; index < count; index++) trackers.add(new CoopStormSnapshot.Tracker(
                    in.getInt(), in.getInt(), in.getLong(), Byte.toUnsignedInt(in.get()),
                    in.getFloat(), in.getFloat(), in.getFloat(), in.getFloat(), in.getFloat(), in.getFloat()));
            return new CoopStormSnapshot(revision, generation, commit, timestamp, duration, width, height, grid, trackers);
        } catch (BufferUnderflowException failure) { throw new IllegalArgumentException("Truncated storm snapshot body", failure); }
    }

    public static String encodeControl(CoopStormControl control) {
        Objects.requireNonNull(control, "storm control");
        StringBuilder out = new StringBuilder(CONTROL_PREFIX);
        for (Object field : List.<Object>of(control.requestId(), control.baseRevision(), control.action().name(),
                control.x(), control.y(), control.radius(), control.waitDays(), control.minSignalDays(),
                control.maxSignalDays(), control.extraRadius(), control.timeScale())) {
            String value = field.toString(); out.append(value.length()).append(':').append(value);
        }
        if (out.length() > MAX_CONTROL_CHARS) throw new IllegalArgumentException("Storm control cap exceeded");
        return out.toString();
    }

    public static CoopStormControl decodeControl(String encoded) {
        if (encoded == null || !encoded.startsWith(CONTROL_PREFIX) || encoded.length() > MAX_CONTROL_CHARS)
            throw new IllegalArgumentException("Invalid storm control envelope");
        Fields fields = new Fields(encoded, CONTROL_PREFIX.length());
        String id = fields.next();
        long revision = longInteger(fields.next());
        CoopStormControl.Action action;
        try { action = CoopStormControl.Action.valueOf(fields.next()); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid storm control action", failure); }
        CoopStormControl result = new CoopStormControl(id, revision, action, number(fields.next()), number(fields.next()),
                number(fields.next()), number(fields.next()), number(fields.next()), number(fields.next()),
                number(fields.next()), number(fields.next()));
        if (fields.cursor != encoded.length()) throw new IllegalArgumentException("Trailing storm control fields");
        return result;
    }

    private static final class Fields {
        private final String encoded;
        private int cursor;
        private Fields(String encoded, int cursor) { this.encoded = encoded; this.cursor = cursor; }
        private String next() {
            int colon = encoded.indexOf(':', cursor);
            if (colon < 0) throw new IllegalArgumentException("Truncated storm control field");
            int length = integer(encoded.substring(cursor, colon));
            if (length < 1 || length > MAX_CONTROL_CHARS || (long) colon + 1 + length > encoded.length())
                throw new IllegalArgumentException("Storm control field exceeds body");
            cursor = colon + 1;
            String value = encoded.substring(cursor, cursor + length); cursor += length;
            return value;
        }
    }

    private static int integer(String text) {
        try {
            if (!text.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
            return Integer.parseInt(text);
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid storm length/count", failure); }
    }

    private static long longInteger(String text) {
        try {
            long value = Long.parseLong(text);
            if (!Long.toString(value).equals(text)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid storm integer", failure); }
    }

    private static float number(String text) {
        try {
            float value = Float.parseFloat(text);
            if (!Float.isFinite(value) || !Float.toString(value).equals(text)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid storm number", failure); }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw); deflater.finish();
            byte[] output = new byte[raw.length + raw.length / 16 + 128];
            int written = 0;
            while (!deflater.finished()) {
                int count = deflater.deflate(output, written, output.length - written);
                if (count == 0) throw new IllegalStateException("Storm compressor made no progress");
                written += count;
            }
            return Arrays.copyOf(output, written);
        } finally { deflater.end(); }
    }

    private static byte[] inflate(byte[] compressed, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] raw = new byte[expected];
            int read = 0;
            while (!inflater.finished() && read < raw.length) {
                int count = inflater.inflate(raw, read, raw.length - read);
                if (count == 0) {
                    if (inflater.finished()) break;
                    throw new IllegalArgumentException("Truncated or stalled storm snapshot deflate");
                }
                read += count;
            }
            if (read != expected) throw new IllegalArgumentException("Storm snapshot raw length mismatch");
            if (!inflater.finished() && inflater.inflate(new byte[1]) != 0)
                throw new IllegalArgumentException("Storm snapshot inflation exceeds declared length");
            if (!inflater.finished() || inflater.getRemaining() != 0)
                throw new IllegalArgumentException("Truncated or trailing storm snapshot deflate");
            return raw;
        } catch (DataFormatException failure) { throw new IllegalArgumentException("Invalid storm snapshot deflate", failure); }
        finally { inflater.end(); }
    }
}
