package coop.save;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordinated-save rules. Two of them are engine facts rather than preferences and are the whole
 * reason this class exists: {@code CampaignUIAPI.autosave()} silently does nothing while a dialog is
 * open (so a checkpoint has to be parked and retried), and a host save can fire twice in quick
 * succession (so the send has to debounce or the guest saves twice for one host save).
 */
class CoopSaveCheckpointTest {

    // ---- Guest: deferral around dialogs ---------------------------------------------------------

    @Test
    void anAutosaveIsRetriedWhileAScreenIsOpenAndPerformedExactlyOnceWhenItClears() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = false;

        checkpoint.onCheckpointReceived(1L, "host save", 1_000L);
        assertTrue(checkpoint.isAutosavePending());

        // Three frames behind an open dialog: nothing happens, and the request survives.
        assertFalse(checkpoint.tick(target, 1_016L));
        assertFalse(checkpoint.tick(target, 1_032L));
        assertFalse(checkpoint.tick(target, 1_048L));
        assertEquals(0, target.autosaves);
        assertTrue(checkpoint.isAutosavePending());

        target.canAutosave = true;
        assertTrue(checkpoint.tick(target, 1_064L));
        assertEquals(1, target.autosaves);
        assertFalse(checkpoint.isAutosavePending());

        // And it does not fire again on every subsequent frame.
        assertFalse(checkpoint.tick(target, 1_080L));
        assertEquals(1, target.autosaves);
    }

    @Test
    void aClearScreenAutosavesOnTheVeryNextTick() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = true;

        checkpoint.onCheckpointReceived(1L, "host save", 1_000L);
        assertTrue(checkpoint.tick(target, 1_016L));

        assertEquals(1, target.autosaves);
    }

    @Test
    void tickDoesNothingWithoutACheckpoint() {
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = true;

        assertFalse(new CoopSaveCheckpoint().tick(target, 1_000L));

        assertEquals(0, target.autosaves);
    }

    // ---- Guest: duplicate suppression -----------------------------------------------------------

    @Test
    void arepeatedCheckpointIdIsIgnored() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = true;

        checkpoint.onCheckpointReceived(7L, "host save", 1_000L);
        assertTrue(checkpoint.tick(target, 1_016L));
        // A resend of the same checkpoint (flaky link, host retry) must not save a second time.
        checkpoint.onCheckpointReceived(7L, "host save", 1_100L);

        assertFalse(checkpoint.isAutosavePending());
        assertFalse(checkpoint.tick(target, 1_116L));
        assertEquals(1, target.autosaves);
    }

    @Test
    void checkpointsArrivingWhileOneIsParkedCollapseIntoASingleAutosave() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = false;

        checkpoint.onCheckpointReceived(1L, "host save", 1_000L);
        checkpoint.tick(target, 1_016L);
        checkpoint.onCheckpointReceived(2L, "host save", 5_000L);
        checkpoint.onCheckpointReceived(3L, "host save", 9_000L);

        target.canAutosave = true;
        assertTrue(checkpoint.tick(target, 9_016L));
        assertFalse(checkpoint.tick(target, 9_032L));
        assertEquals(1, target.autosaves);
    }

    @Test
    void aFoldedInCheckpointDoesNotExtendTheGiveUpDeadline() {
        // One autosave, one 30-second budget: a stream of checkpoints while the player sits in a
        // dialog must not keep the retry loop alive indefinitely.
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = false;

        checkpoint.onCheckpointReceived(1L, "host save", 1_000L);
        checkpoint.onCheckpointReceived(2L, "host save", 20_000L);
        checkpoint.tick(target, 1_000L + CoopSaveCheckpoint.GIVE_UP_MILLIS);

        assertFalse(checkpoint.isAutosavePending());
    }

    // ---- Guest: give up -------------------------------------------------------------------------

    @Test
    void aCheckpointIsAbandonedWithAWarningAfterTheGiveUpWindow() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = false;
        CapturingAppender log = CapturingAppender.attach(CoopSaveCheckpoint.class);
        try {
            checkpoint.onCheckpointReceived(1L, "host save", 1_000L);

            // One millisecond short of the window: still trying.
            assertFalse(checkpoint.tick(target, 1_000L + CoopSaveCheckpoint.GIVE_UP_MILLIS - 1L));
            assertTrue(checkpoint.isAutosavePending());
            assertFalse(log.hasWarning());

            assertFalse(checkpoint.tick(target, 1_000L + CoopSaveCheckpoint.GIVE_UP_MILLIS));

            assertFalse(checkpoint.isAutosavePending());
            assertEquals(0, target.autosaves);
            assertTrue(log.hasWarning(), "the give-up path must leave a trace in the log");
            assertTrue(log.warnings().get(0).contains("gave up"), log.warnings().get(0));
        } finally {
            log.detach();
        }
    }

    @Test
    void aThrowingAutosaveDropsTheRequestRatherThanRetryingForever() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        ScriptedTarget target = new ScriptedTarget();
        target.canAutosave = true;
        target.throwOnAutosave = true;

        checkpoint.onCheckpointReceived(1L, "host save", 1_000L);

        assertFalse(checkpoint.tick(target, 1_016L));
        assertFalse(checkpoint.isAutosavePending());
    }

    // ---- Host: send debounce --------------------------------------------------------------------

    @Test
    void twoHostSavesInsideTheDebounceWindowSendOneCheckpoint() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        RecordingSender sender = new RecordingSender();
        checkpoint.setSender(sender);

        assertTrue(checkpoint.onLocalGameSaved("host save", 10_000L));
        // An autosave landing right behind a manual save is one event, not two.
        assertFalse(checkpoint.onLocalGameSaved("host save", 10_500L));
        assertFalse(checkpoint.onLocalGameSaved("host save",
                10_000L + CoopSaveCheckpoint.SEND_DEBOUNCE_MILLIS - 1L));

        assertEquals(1, sender.reasons.size());
        assertEquals(List.of(1L), sender.ids);
    }

    @Test
    void aLaterHostSaveSendsTheNextCheckpointId() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        RecordingSender sender = new RecordingSender();
        checkpoint.setSender(sender);

        checkpoint.onLocalGameSaved("host save", 10_000L);
        assertTrue(checkpoint.onLocalGameSaved(CoopSaveCheckpoint.REASON_SESSION_END,
                10_000L + CoopSaveCheckpoint.SEND_DEBOUNCE_MILLIS));

        assertEquals(List.of(1L, 2L), sender.ids);
        assertEquals(CoopSaveCheckpoint.REASON_SESSION_END, sender.reasons.get(1));
    }

    @Test
    void aRefusedSendDoesNotBurnACheckpointIdOrTheDebounce() {
        // The pump's sender refuses when this client is not a connected host — the case that fires on
        // every guest autosave. It must leave no trace, or the host's first real checkpoint would be
        // numbered wrong and the next genuine save would be debounced away.
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        RecordingSender sender = new RecordingSender();
        sender.accept = false;
        checkpoint.setSender(sender);

        assertFalse(checkpoint.onLocalGameSaved("host save", 10_000L));

        sender.accept = true;
        assertTrue(checkpoint.onLocalGameSaved("host save", 10_100L));
        assertEquals(List.of(1L, 1L), sender.ids);
    }

    @Test
    void aCheckpointWithoutASenderIsSimplyDropped() {
        assertFalse(new CoopSaveCheckpoint().onLocalGameSaved("host save", 10_000L));
    }

    @Test
    void resetForgetsTheDebounceAndTheDuplicateHistory() {
        CoopSaveCheckpoint checkpoint = new CoopSaveCheckpoint();
        RecordingSender sender = new RecordingSender();
        checkpoint.setSender(sender);
        checkpoint.onLocalGameSaved("host save", 10_000L);
        checkpoint.onCheckpointReceived(1L, "host save", 10_000L);

        checkpoint.reset();

        assertFalse(checkpoint.isAutosavePending());
        assertTrue(checkpoint.onLocalGameSaved("host save", 10_100L));
        assertEquals(List.of(1L, 1L), sender.ids);
        checkpoint.onCheckpointReceived(1L, "host save", 10_200L);
        assertTrue(checkpoint.isAutosavePending());
    }

    private static final class ScriptedTarget implements CoopSaveCheckpoint.AutosaveTarget {
        private boolean canAutosave;
        private boolean throwOnAutosave;
        private int autosaves;

        @Override
        public boolean canAutosaveNow() {
            return canAutosave;
        }

        @Override
        public void autosave() {
            if (throwOnAutosave) {
                throw new IllegalStateException("save failed");
            }
            autosaves++;
        }
    }

    private static final class RecordingSender implements CoopSaveCheckpoint.Sender {
        private final List<Long> ids = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();
        private boolean accept = true;

        @Override
        public boolean sendCheckpoint(long checkpointId, String reason) {
            ids.add(checkpointId);
            if (!accept) {
                return false;
            }
            reasons.add(reason);
            return true;
        }
    }

    /** Minimal log4j sink so the give-up path can be asserted on rather than assumed. */
    private static final class CapturingAppender extends AppenderSkeleton {
        private final List<String> warnings = new ArrayList<>();
        private Logger attachedTo;

        private static CapturingAppender attach(Class<?> source) {
            CapturingAppender appender = new CapturingAppender();
            appender.attachedTo = Logger.getLogger(source);
            appender.attachedTo.addAppender(appender);
            return appender;
        }

        private void detach() {
            if (attachedTo != null) {
                attachedTo.removeAppender(this);
            }
        }

        private boolean hasWarning() {
            return !warnings.isEmpty();
        }

        private List<String> warnings() {
            return warnings;
        }

        @Override
        protected void append(LoggingEvent event) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                warnings.add(String.valueOf(event.getMessage()));
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
