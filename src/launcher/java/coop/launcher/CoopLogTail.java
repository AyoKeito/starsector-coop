package coop.launcher;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Follows {@code starsector-core/starsector.log} while the game runs and forwards the co-op lines
 * worth watching.
 *
 * <p>Opened with {@link RandomAccessFile} in {@code "r"} mode, which on Windows takes a shared read
 * lock: the game keeps writing normally. The file is truncated at every game start, so a length that
 * went backwards means "reopen from zero" rather than "seek forward".
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
        RandomAccessFile handle = null;
        try {
            while (!closed.get()) {
                try {
                    if (handle == null) {
                        if (!file.isFile()) {
                            sleep();
                            continue;
                        }
                        handle = new RandomAccessFile(file, "r");
                        if (firstOpen) {
                            // Start where the file already ended: the launcher shows what happens
                            // from now on, not the megabytes of a previous session. Clamped in case
                            // the file shrank before the thread got here.
                            firstOpen = false;
                            position = Math.min(startPosition, handle.length());
                        }
                        handle.seek(position);
                    }
                    long length = handle.length();
                    if (length < position) {
                        // The game truncated or replaced the file at startup. Re-read from the
                        // beginning of the new file: everything in it belongs to the run that just
                        // started, which is the run the launcher is watching.
                        closeQuietly(handle);
                        handle = null;
                        position = 0L;
                        filter.reset();
                        continue;
                    }
                    if (length == position) {
                        sleep();
                        continue;
                    }
                    handle.seek(position);
                    String chunk = readChunk(handle, (int) Math.min(length - position, 1 << 20));
                    int lastNewline = chunk.lastIndexOf('\n');
                    if (lastNewline < 0) {
                        // A partial line; wait for the rest rather than splitting a line in half.
                        sleep();
                        continue;
                    }
                    String complete = chunk.substring(0, lastNewline);
                    position += complete.getBytes(StandardCharsets.UTF_8).length + 1;
                    for (String line : complete.split("\r?\n", -1)) {
                        if (filter.accept(line)) {
                            sink.accept(line);
                        }
                    }
                } catch (IOException ex) {
                    closeQuietly(handle);
                    handle = null;
                    sleep();
                }
            }
        } finally {
            closeQuietly(handle);
        }
    }

    private String readChunk(RandomAccessFile handle, int count) throws IOException {
        byte[] bytes = new byte[count];
        handle.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closed.set(true);
        }
    }

    private static void closeQuietly(RandomAccessFile handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (IOException ignored) {
            // Nothing left to do about it; the next loop reopens.
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
