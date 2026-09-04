package coop.launcher;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Follows {@code starsector-core/starsector.log} while the game runs and forwards the co-op lines
 * worth watching.
 *
 * <p>Opened through {@link Files#newByteChannel}, once per poll, and closed again immediately.
 * Both halves matter on Windows: NIO opens with {@code FILE_SHARE_DELETE}, and the handle is not
 * held between polls, so log4j's {@code RollingFileAppender} can rename the file to
 * {@code starsector.log.1}. A held {@link java.io.RandomAccessFile} blocks that rename, and log4j
 * 1.2.9 ignores the failure and reopens with {@code append=false} - which truncates the live log
 * instead of rolling it.
 *
 * <p>The file position is tracked in raw bytes, never in decoded characters: the game writes its log
 * in the platform charset, so a byte that is not valid UTF-8 decodes to one replacement character
 * and would re-encode to three. Advancing by the re-encoded length walked the cursor past the end of
 * the file and turned the "the game truncated the file" branch into a replay loop.
 *
 * <p>A length that went backwards means "reopen from zero": the game either truncated the file at
 * startup or rolled it, and in both cases everything in the file now belongs to the run being
 * watched.
 *
 * <p>Lines are forwarded exactly as they appear. Reformatting them would make them stop matching
 * what a bug report needs to contain.
 */
public final class CoopLogTail implements Closeable {

    private static final long POLL_MILLIS = 500L;

    /** Header of the connection doctor block; the lines under it are indented and unprefixed. */
    static final String DOCTOR_HEADER = "Coop connection doctor:";
    /** Marker the mod puts on its machine-readable diagnostic lines. */
    static final String DOCTOR_MARKER = "[COOP-DOCTOR]";
    /** Longest doctor block the tail will follow before giving up on finding its end. */
    static final int MAX_DOCTOR_BLOCK_LINES = 40;

    /** Most bytes read in one poll; a bigger backlog is caught up over the polls after it. */
    private static final int MAX_CHUNK_BYTES = 1 << 20;

    /**
     * Start of a log4j line in Starsector's format: {@code 33291 [Thread-2] INFO  coop.net.X  - ...}.
     * A doctor block ends at the next one of these, which is what actually terminates it in the real
     * file - the blank line the block is nominally delimited by is not always there.
     */
    private static final Pattern LOG_LINE =
            Pattern.compile("^\\d+ \\[[^\\]]*\\] (TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+(\\S+)\\s+- (.*)$");

    private final File file;
    private final Consumer<String> sink;
    private final Filter filter = new Filter();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread thread;
    /**
     * Where the follow starts: the file's length at the moment {@link #start} was called, measured on
     * the caller's thread. Doing it inside the thread instead left a race - anything written between
     * {@code start()} returning and the thread's first open was skipped, which is exactly the window
     * a caller uses to make something happen.
     */
    private final long startPosition;

    /**
     * True when {@code line} starts a fresh Starsector log line rather than continuing the one
     * above it. Factored out of {@link Filter} because {@link CoopBugReport} needs the same answer
     * to find where a connection doctor block ends when it scans a whole log file.
     */
    static boolean isLogLineStart(String line) {
        return line != null && LOG_LINE.matcher(line).matches();
    }

    private CoopLogTail(File file, Consumer<String> sink) {
        this.file = Objects.requireNonNull(file, "file");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.startPosition = file.isFile() ? file.length() : 0L;
        this.thread = new Thread(this::run, "coop-launcher-log-tail");
        this.thread.setDaemon(true);
    }

    /**
     * Starts following {@code file} from its current end. Returns immediately; {@code sink} is called
     * from the tail's own thread, so a Swing caller has to hop back to the event dispatch thread.
     */
    public static CoopLogTail start(File file, Consumer<String> sink) {
        CoopLogTail tail = new CoopLogTail(file, sink);
        tail.thread.start();
        return tail;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long position = startPosition;
        boolean firstOpen = true;
        while (!closed.get()) {
            if (!file.isFile()) {
                sleep();
                continue;
            }
            try (SeekableByteChannel handle =
                         Files.newByteChannel(file.toPath(), StandardOpenOption.READ)) {
                long length = handle.size();
                if (firstOpen) {
                    // Start where the file already ended: the launcher shows what happens from now
                    // on, not the megabytes of a previous session. Clamped in case the file shrank
                    // before the thread got here.
                    firstOpen = false;
                    position = Math.min(startPosition, length);
                }
                if (length < position) {
                    // The game truncated the file at startup, or log4j rolled it. Either way what is
                    // in the file now belongs to the run the launcher is watching, so re-read it
                    // from the beginning.
                    position = 0L;
                    filter.reset();
                    continue;
                }
                if (length == position) {
                    sleep();
                    continue;
                }
                handle.position(position);
                byte[] bytes = readChunk(handle, (int) Math.min(length - position, MAX_CHUNK_BYTES));
                int lastNewline = lastIndexOf(bytes, (byte) '\n');
                if (lastNewline < 0) {
                    // A partial line; wait for the rest rather than splitting a line in half.
                    sleep();
                    continue;
                }
                // Raw bytes, not the length of the decoded text: see the class comment.
                position += lastNewline + 1;
                String complete = new String(bytes, 0, lastNewline, StandardCharsets.UTF_8);
                for (String line : complete.split("\r?\n", -1)) {
                    if (filter.accept(line)) {
                        sink.accept(line);
                    }
                }
            } catch (IOException ex) {
                sleep();
            }
        }
    }

    private static byte[] readChunk(SeekableByteChannel handle, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        while (buffer.hasRemaining() && handle.read(buffer) >= 0) {
            // Keep reading; a short read is normal on a file someone else is writing.
        }
        return buffer.hasRemaining()
                ? Arrays.copyOf(buffer.array(), buffer.position())
                : buffer.array();
    }

    private static int lastIndexOf(byte[] bytes, byte value) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closed.set(true);
        }
    }

    /**
     * Which lines the launcher shows. Stateful only because the connection doctor writes a block of
     * indented continuation lines that carry no prefix of their own.
     */
    static final class Filter {

        private int doctorBlockLinesLeft;

        void reset() {
            doctorBlockLinesLeft = 0;
        }

        /** True when {@code line} should reach the status pane. */
        boolean accept(String line) {
            if (line == null) {
                return false;
            }
            if (doctorBlockLinesLeft > 0) {
                if (line.isBlank() || isLogLineStart(line)) {
                    doctorBlockLinesLeft = 0;
                    // Fall through: a new log line still gets judged on its own merits.
                } else {
                    doctorBlockLinesLeft--;
                    return true;
                }
            }
            if (line.contains(DOCTOR_HEADER)) {
                doctorBlockLinesLeft = MAX_DOCTOR_BLOCK_LINES;
                return true;
            }
            if (line.contains(DOCTOR_MARKER)) {
                return true;
            }
            var matcher = LOG_LINE.matcher(line);
            if (!matcher.matches()) {
                return false;
            }
            String level = matcher.group(1);
            String logger = matcher.group(2);
            String message = matcher.group(3);
            if (!logger.startsWith("coop.")) {
                return false;
            }
            if ("WARN".equals(level) || "ERROR".equals(level) || "FATAL".equals(level)) {
                return true;
            }
            if (!"INFO".equals(level)) {
                return false;
            }
            return message.startsWith("Coop lobby") || message.startsWith("Coop session");
        }
    }
}
