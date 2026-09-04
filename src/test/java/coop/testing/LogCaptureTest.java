package coop.testing;

import org.apache.log4j.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two flavours of capturing appender this class replaced disagreed about what "captured" meant:
 * one kept every event, the other kept WARN and above. Both callers still exist, so both views have
 * to stay honest at the same time.
 */
class LogCaptureTest {

    /** Any class works as a logger name; this one is only ever used from here. */
    private static final class LogSource {
    }

    @Test
    void everyLevelReachesMessagesButOnlyWarnAndAboveReachesWarnings() {
        LogCapture log = LogCapture.attach(LogSource.class);
        try {
            Logger logger = Logger.getLogger(LogSource.class);
            logger.info("an info line");
            logger.warn("a warn line");
            logger.error("an error line");

            assertEquals(List.of("an info line", "a warn line", "an error line"), log.messages());
            assertEquals(List.of("a warn line", "an error line"), log.warnings());
            assertTrue(log.hasWarning());
        } finally {
            log.detach();
        }
    }

    @Test
    void anInfoOnlyRunHasNoWarnings() {
        LogCapture log = LogCapture.attach(LogSource.class);
        try {
            Logger.getLogger(LogSource.class).info("nothing wrong here");

            assertEquals(1, log.messages().size());
            assertTrue(log.warnings().isEmpty());
            assertFalse(log.hasWarning());
        } finally {
            log.detach();
        }
    }

    @Test
    void matchingNarrowsTheWarningsToTheOnesWorthCounting() {
        LogCapture log = LogCapture.attach(LogSource.class);
        try {
            Logger logger = Logger.getLogger(LogSource.class);
            logger.warn("Coop clock guest ahead by 3 days");
            logger.warn("something else entirely");
            logger.info("Coop clock guest ahead by 4 days");

            assertEquals(List.of("Coop clock guest ahead by 3 days"),
                    log.matching("Coop clock guest ahead"),
                    "matching filters the warnings, not every message");
        } finally {
            log.detach();
        }
    }

    /**
     * An appender left attached records the next test's output too, which is the failure mode
     * {@code detach()} exists to prevent - so it has to actually unhook, and survive being called
     * twice from a finally block that already ran.
     */
    @Test
    void detachStopsRecordingAndToleratesBeingCalledTwice() {
        LogCapture log = LogCapture.attach(LogSource.class);
        Logger.getLogger(LogSource.class).warn("before");
        log.detach();
        log.detach();
        Logger.getLogger(LogSource.class).warn("after");

        assertEquals(List.of("before"), log.warnings());
    }
}
