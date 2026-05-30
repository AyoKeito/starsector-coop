package coop.time;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSharedPauseCoordinatorTest {

    @Test
    void effectivePausedIsOrOfHostGuestKeyGuestScreenAndCombatIntents() {
        for (int mask = 0; mask < 16; mask++) {
            boolean host = (mask & 1) != 0;
            boolean key = (mask & 2) != 0;
            boolean screen = (mask & 4) != 0;
            boolean combat = (mask & 8) != 0;

            CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
            coordinator.setHostPauseIntent(host);
            // Distinct, increasing seqs so both guest intents are applied (last-writer-wins debounce).
            coordinator.applyGuestKeyPauseIntent(key, 1L);
            coordinator.applyGuestScreenPauseIntent(screen, 2L);
            coordinator.setEitherInCombat(combat);

            boolean expected = host || key || screen || combat;
            assertEquals(expected, coordinator.effectivePaused(),
                    "host=" + host + " key=" + key + " screen=" + screen + " combat=" + combat);
        }
    }

    @Test
    void guestPauseKeyPressIsForwardedAsOppositeOfObservedState() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        // Running -> a press wants to pause.
        coordinator.setObservedPaused(false);
        coordinator.recordGuestPauseKeyPress();
        assertTrue(coordinator.consumeGuestKeyPress());
        assertTrue(coordinator.desiredKeyPause());
        // Press is consumed once.
        assertFalse(coordinator.consumeGuestKeyPress());

        // Paused -> a press wants to run.
        coordinator.setObservedPaused(true);
        coordinator.recordGuestPauseKeyPress();
        assertTrue(coordinator.consumeGuestKeyPress());
        assertFalse(coordinator.desiredKeyPause());
    }

    @Test
    void guestScreenLevelChangeDetection() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        assertTrue(coordinator.updateGuestScreenLevel(true));   // opened -> changed
        assertFalse(coordinator.updateGuestScreenLevel(true));  // still open -> no change
        assertTrue(coordinator.updateGuestScreenLevel(false));  // closed -> changed
        assertFalse(coordinator.updateGuestScreenLevel(false)); // still closed -> no change
    }

    @Test
    void hostMayOverrideGuestKeyPauseButNotGuestScreenPause() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.applyGuestKeyPauseIntent(true, 1L);
        coordinator.applyGuestScreenPauseIntent(true, 2L);
        coordinator.setObservedPaused(true);

        // Host taps pause while paused: clears its own intent + the guest's manual (key) pause...
        coordinator.onHostPauseKey();

        assertFalse(coordinator.hostPauseIntent());
        assertFalse(coordinator.guestKeyPauseIntent());
        // ...but the guest's screen pause survives, so the world stays paused.
        assertTrue(coordinator.guestScreenPauseIntent());
        assertTrue(coordinator.effectivePaused());
    }

    @Test
    void hostPauseKeyAssertsPauseWhenRunning() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setObservedPaused(false);

        coordinator.onHostPauseKey();

        assertTrue(coordinator.hostPauseIntent());
        assertTrue(coordinator.effectivePaused());
    }

    @Test
    void guestPauseIntentAppliesLastWriterWinsBySeq() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();

        assertTrue(coordinator.applyGuestKeyPauseIntent(true, 5L));
        assertTrue(coordinator.guestKeyPauseIntent());

        // Stale (lower) seq is ignored.
        assertFalse(coordinator.applyGuestKeyPauseIntent(false, 3L));
        assertTrue(coordinator.guestKeyPauseIntent());

        // Equal seq is ignored.
        assertFalse(coordinator.applyGuestKeyPauseIntent(false, 5L));
        assertTrue(coordinator.guestKeyPauseIntent());

        // Newer seq wins (and the seq counter is shared with screen intents).
        assertTrue(coordinator.applyGuestScreenPauseIntent(true, 6L));
        assertFalse(coordinator.applyGuestKeyPauseIntent(false, 6L));
        assertTrue(coordinator.applyGuestKeyPauseIntent(false, 7L));
        assertFalse(coordinator.guestKeyPauseIntent());
    }

    @Test
    void forceClearGuestKeyPauseLeavesScreenPause() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.applyGuestKeyPauseIntent(true, 1L);
        coordinator.applyGuestScreenPauseIntent(true, 2L);

        coordinator.forceClearGuestKeyPauseIntent();

        assertFalse(coordinator.guestKeyPauseIntent());
        assertTrue(coordinator.guestScreenPauseIntent());
    }

    @Test
    void nextLocalSeqIsMonotonic() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        assertEquals(1L, coordinator.nextLocalSeq());
        assertEquals(2L, coordinator.nextLocalSeq());
        assertEquals(3L, coordinator.nextLocalSeq());
    }

    @Test
    void resetClearsAllIntentState() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setHostPauseIntent(true);
        coordinator.applyGuestKeyPauseIntent(true, 9L);
        coordinator.applyGuestScreenPauseIntent(true, 10L);
        coordinator.setEitherInCombat(true);
        coordinator.updateGuestScreenLevel(true);
        coordinator.recordGuestPauseKeyPress();
        coordinator.setObservedPaused(true);
        coordinator.nextLocalSeq();

        coordinator.reset();

        assertFalse(coordinator.hostPauseIntent());
        assertFalse(coordinator.guestKeyPauseIntent());
        assertFalse(coordinator.guestScreenPauseIntent());
        assertFalse(coordinator.eitherInCombat());
        assertFalse(coordinator.guestScreenLevel());
        assertFalse(coordinator.consumeGuestKeyPress());
        assertFalse(coordinator.observedPaused());
        assertFalse(coordinator.effectivePaused());
        // The applied-seq tracker is reset, so the next intent (any seq) is accepted again.
        assertTrue(coordinator.applyGuestKeyPauseIntent(true, 1L));
        assertEquals(1L, coordinator.nextLocalSeq());
    }

    @Test
    void guestKeyPressDoesNotQueueAPhantomPauseWhileHostHoldsTheWorldPaused() {
        // Regression: host holds the world paused; the guest taps pause meaning "let me run". The
        // forwarded value is the opposite of the observed (paused) state -> false, so no phantom pause
        // is asserted on the host.
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setObservedPaused(true); // guest clock mirrors the host's paused clock

        coordinator.recordGuestPauseKeyPress();

        assertTrue(coordinator.consumeGuestKeyPress());
        assertFalse(coordinator.desiredKeyPause());
    }

    @Test
    void pauseIntentMessageRoundTrips() {
        CoopMessages.Message message = CoopMessages.pauseIntent(
                "session-z", 4L, 7000L, CoopMessages.PauseSource.KEY, true, 11L);
        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.PAUSE_INTENT, decoded.type());
        assertEquals("session-z", decoded.sessionId());
        assertEquals(4L, decoded.seq());
        assertEquals(7000L, decoded.sentAtMillis());
        assertEquals("KEY", CoopMessages.requiredPayloadString(decoded, "source"));
        assertEquals("true", CoopMessages.requiredPayloadString(decoded, "paused"));
        assertEquals(11L, CoopMessages.requiredPayloadLong(decoded, "intentSeq"));
    }
}
