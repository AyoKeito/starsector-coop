package coop.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopReconnectCoordinatorTest {

    private static final long GRACE = 60_000L;
    private static final String SESSION = "session-a";
    private static final String GUEST = "guest-player";

    private final RecordingListener listener = new RecordingListener();

    private CoopReconnectCoordinator coordinator() {
        return new CoopReconnectCoordinator(GRACE, listener);
    }

    // ---- host ------------------------------------------------------------------------------------

    @Test
    void aHostWaitOpensAGraceWindowAndAnnouncesIt() {
        CoopReconnectCoordinator reconnect = coordinator();

        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertTrue(reconnect.active());
        assertTrue(reconnect.hostWaiting());
        assertEquals(SESSION, reconnect.sessionId());
        assertEquals(GRACE, reconnect.remainingMillis(1_000L));
        assertEquals(60, reconnect.remainingSeconds(1_000L));
        assertEquals(List.of("started:HOST_WAIT:60000"), listener.events);
    }

    @Test
    void aMatchingResumeRequestIsAcceptedAndFiresTheRebroadcastCallback() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertEquals(CoopReconnectCoordinator.ResumeDecision.ACCEPT,
                reconnect.evaluateResumeRequest(SESSION, GUEST));
        assertTrue(reconnect.resume());

        assertFalse(reconnect.active());
        assertNull(reconnect.sessionId());
        assertEquals(List.of("started:HOST_WAIT:60000", "resumed:HOST_WAIT"), listener.events);
    }

    @Test
    void aWrongSessionIdIsRejectedAndTheWaitKeepsRunning() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertEquals(CoopReconnectCoordinator.ResumeDecision.REJECT_SESSION_MISMATCH,
                reconnect.evaluateResumeRequest("session-b", GUEST));

        // A stranger must not be able to end the wait early, so the window is untouched.
        assertTrue(reconnect.hostWaiting());
        assertEquals(GRACE, reconnect.remainingMillis(1_000L));
        assertEquals(List.of("started:HOST_WAIT:60000"), listener.events);
    }

    @Test
    void aWrongPlayerIdIsRejectedSeparatelyFromAWrongSession() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertEquals(CoopReconnectCoordinator.ResumeDecision.REJECT_PLAYER_MISMATCH,
                reconnect.evaluateResumeRequest(SESSION, "someone-else"));
        assertEquals(CoopReconnectCoordinator.ResumeDecision.REJECT_SESSION_MISMATCH,
                reconnect.evaluateResumeRequest(null, GUEST));
        assertEquals(CoopReconnectCoordinator.ResumeDecision.REJECT_PLAYER_MISMATCH,
                reconnect.evaluateResumeRequest(SESSION, null));
        assertTrue(reconnect.hostWaiting());
    }

    @Test
    void thereIsNothingToResumeWhenNoWindowIsOpen() {
        CoopReconnectCoordinator reconnect = coordinator();

        assertEquals(CoopReconnectCoordinator.ResumeDecision.REJECT_NOT_WAITING,
                reconnect.evaluateResumeRequest(SESSION, GUEST));
        assertFalse(reconnect.resume());
        assertFalse(reconnect.end("nothing to end"));
        assertEquals(List.of(), listener.events);
    }

    @Test
    void everyRejectDecisionCarriesADistinctReasonForTheWire() {
        for (CoopReconnectCoordinator.ResumeDecision decision
                : CoopReconnectCoordinator.ResumeDecision.values()) {
            String reason = CoopReconnectCoordinator.rejectReason(decision);
            assertEquals(decision.accepted(), reason.isEmpty(),
                    "only ACCEPT has no reason: " + decision);
        }
    }

    // ---- expiry ----------------------------------------------------------------------------------

    @Test
    void expiryFiresTheResetCallbackExactlyOnce() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertFalse(reconnect.tick(60_999L));
        assertTrue(reconnect.hostWaiting());
        assertEquals(1, reconnect.remainingSeconds(60_999L), "a live window never shows 0 s");

        assertTrue(reconnect.tick(61_000L));
        assertFalse(reconnect.active());
        assertFalse(reconnect.tick(200_000L), "the window closes once");
        assertEquals(List.of("started:HOST_WAIT:60000",
                "ended:HOST_WAIT:" + CoopReconnectCoordinator.REASON_GRACE_EXPIRED), listener.events);
    }

    @Test
    void aZeroLengthGraceEndsTheSessionOnTheVeryNextTick() {
        CoopReconnectCoordinator reconnect = new CoopReconnectCoordinator(0L, listener);
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertTrue(reconnect.tick(1_000L));
        assertFalse(reconnect.active());
    }

    @Test
    void theEndSessionButtonClosesTheWindowImmediately() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertTrue(reconnect.end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER));

        assertFalse(reconnect.active());
        assertEquals(List.of("started:HOST_WAIT:60000",
                "ended:HOST_WAIT:" + CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER), listener.events);
    }

    @Test
    void abandoningDropsTheWindowWithoutRunningEitherCallback() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);
        listener.events.clear();

        reconnect.abandon();

        assertFalse(reconnect.active());
        assertEquals(List.of(), listener.events);
    }

    // ---- guest -----------------------------------------------------------------------------------

    @Test
    void aReconnectingGuestOwesOneResumeRequestPerSocket() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginGuestReconnect(SESSION, GUEST, 1_000L);

        assertTrue(reconnect.guestReconnecting());
        assertTrue(reconnect.resumeRequestDue());
        reconnect.markResumeRequestSent();
        assertFalse(reconnect.resumeRequestDue(), "one request per socket, not one per frame");

        // The socket died again before an answer arrived: the next one owes a fresh request.
        reconnect.noteChannelDown();
        assertTrue(reconnect.resumeRequestDue());
    }

    @Test
    void anIdleCoordinatorNeverOwesAResumeRequest() {
        CoopReconnectCoordinator reconnect = coordinator();

        reconnect.noteChannelDown();

        assertFalse(reconnect.resumeRequestDue());
    }

    @Test
    void aGuestRejectRunsTheOrdinaryTeardown() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginGuestReconnect(SESSION, GUEST, 1_000L);

        assertTrue(reconnect.end(CoopReconnectCoordinator.REASON_HOST_REJECTED));

        assertFalse(reconnect.active());
        assertEquals(List.of("started:GUEST_RECONNECTING:60000",
                "ended:GUEST_RECONNECTING:" + CoopReconnectCoordinator.REASON_HOST_REJECTED),
                listener.events);
    }

    @Test
    void aGuestResumeFiresTheResumedCallbackWithItsOwnPreviousState() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginGuestReconnect(SESSION, GUEST, 1_000L);

        assertTrue(reconnect.resume());

        assertEquals(List.of("started:GUEST_RECONNECTING:60000", "resumed:GUEST_RECONNECTING"),
                listener.events);
    }

    // ---- guards ----------------------------------------------------------------------------------

    @Test
    void aWindowWithNothingToMatchAgainstNeverOpens() {
        CoopReconnectCoordinator reconnect = coordinator();

        reconnect.beginHostWait(null, GUEST, 1_000L);
        reconnect.beginHostWait(SESSION, null, 1_000L);
        reconnect.beginHostWait("", GUEST, 1_000L);
        reconnect.beginGuestReconnect(SESSION, "", 1_000L);

        assertFalse(reconnect.active());
        assertEquals(List.of(), listener.events);
    }

    @Test
    void aSecondBeginDoesNotRestartAnOpenWindow() {
        CoopReconnectCoordinator reconnect = coordinator();
        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        reconnect.beginHostWait(SESSION, GUEST, 30_000L);

        assertEquals(30_000L, reconnect.remainingMillis(31_000L),
                "the deadline is still the original one");
        assertEquals(1, listener.events.size());
    }

    @Test
    void remainingIsZeroWhenIdleAndFlooredWhenOverdue() {
        CoopReconnectCoordinator reconnect = coordinator();
        assertEquals(0L, reconnect.remainingMillis(1_000L));
        assertEquals(0, reconnect.remainingSeconds(1_000L));

        reconnect.beginHostWait(SESSION, GUEST, 1_000L);

        assertEquals(0L, reconnect.remainingMillis(500_000L));
        assertEquals(0, reconnect.remainingSeconds(500_000L));
    }

    private static final class RecordingListener implements CoopReconnectCoordinator.Listener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onGraceStarted(CoopReconnectCoordinator.State state, long graceMillis) {
            events.add("started:" + state + ":" + graceMillis);
        }

        @Override
        public void onResumed(CoopReconnectCoordinator.State previous) {
            events.add("resumed:" + previous);
        }

        @Override
        public void onEnded(CoopReconnectCoordinator.State previous, String reason) {
            events.add("ended:" + previous + ":" + reason);
        }
    }
}
