package coop.testing;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A log4j 1.2 sink that keeps what a class logged, so "it warns once" can be asserted rather than
 * assumed.
 *
 * <p>Nine test classes carried a private {@code CapturingAppender} in two flavours: one recorded
 * every event into {@code messages}, the other recorded only WARN-and-above into {@code warnings}
 * and shipped its own {@code attach}/{@code detach} pair. Both are here, because both are load
 * bearing: a test that asserts an INFO line was emitted needs the unfiltered list, and a test that
 * asserts "exactly one warning" needs the filtered one and would count INFO noise otherwise.
 *
 * <p>{@link #messages} is the live list and {@link #messages()} a snapshot, matching what the
 * originals did; {@link #warnings()} stays a live view because callers held on to it across further
 * logging.
 *
 * <p>Prefer {@link #attach(Class)} plus a {@code finally { log.detach(); }} over hand-wiring
 * {@code Logger.getLogger(x).addAppender(...)}: an appender left attached outlives the test and
 * starts recording the next one's output.
 */
public final class LogCapture extends AppenderSkeleton {

    /** Every event's message, any level, in order. Live - assertions read it directly. */
    public final List<String> messages = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    private Logger attachedTo;

    /** Attaches a fresh capture to {@code source}'s logger. Pair with {@link #detach()}. */
    public static LogCapture attach(Class<?> source) {
        return new LogCapture().attachTo(Logger.getLogger(source));
    }

    /**
     * Attaches to a logger the caller resolved itself. {@code CoopLog.getLogger} is not always
     * {@code Logger.getLogger} - it goes through the engine's logger when Global has one wired - and
     * a test exercising that fallback has to hand over the logger it means.
     */
    public LogCapture attachTo(Logger logger) {
        detach();
        attachedTo = logger;
        logger.addAppender(this);
        return this;
    }

    /** Removes this capture from whatever {@link #attach(Class)} put it on. Idempotent. */
    public void detach() {
        if (attachedTo != null) {
            attachedTo.removeAppender(this);
            attachedTo = null;
        }
    }

    /**
     * Deliberately a no-op, as every copy of this had it. log4j calls {@code close()} from
     * {@code removeAllAppenders()} while it is walking its own appender list, so detaching here
     * would mutate that list mid-walk. {@link #detach()} is the one that unhooks.
     */
    @Override
    public void close() {
    }

    /** Snapshot of every message, any level. */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** Live view of the WARN-and-above messages. */
    public List<String> warnings() {
        return warnings;
    }

    /** Whether anything at WARN or above was logged. */
    public boolean hasWarning() {
        return !warnings.isEmpty();
    }

    /** The warnings containing {@code needle}, so a count can name what it counted. */
    public List<String> matching(String needle) {
        List<String> hits = new ArrayList<>();
        for (String warning : warnings) {
            if (warning.contains(needle)) {
                hits.add(warning);
            }
        }
        return hits;
    }

    @Override
    protected void append(LoggingEvent event) {
        String message = String.valueOf(event.getMessage());
        messages.add(message);
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            warnings.add(message);
        }
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
