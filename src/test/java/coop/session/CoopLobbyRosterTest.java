package coop.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 21: the lobby's whole rule set lives in this class, so this is where the rules are pinned —
 * admission serialisation, the ready lifecycle, the countdown, and the exact words on each row.
 */
class CoopLobbyRosterTest {

    private static final long T0 = 10_000L;

    private CoopLobbyRoster openRoster() {
        CoopLobbyRoster roster = new CoopLobbyRoster();
        roster.open(T0);
        roster.setHost("host-1", "Alice", T0);
        return roster;
    }

    @Test
    void theHostRowComesFirstAndIsAutoReady() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);

        List<CoopLobbyRoster.Row> rows = roster.rows();
        assertEquals(List.of("Alice", "Bob"), rows.stream().map(CoopLobbyRoster.Row::name).toList());
        assertTrue(rows.get(0).host());
        assertTrue(rows.get(0).ready(), "the host's ready is the Start press");
        assertEquals(CoopLobbyRoster.STATE_READY, roster.stateWord(rows.get(0), T0));
    }

    @Test
    void oneAdmissionAtATime() {
        CoopLobbyRoster roster = openRoster();
        assertTrue(roster.admit("guest-1", "Bob", T0));

        // Bob is still mid-handshake, so nobody else may start one.
        assertFalse(roster.admit("guest-2", "Carol", T0), "a second join must wait its turn");
        assertNull(roster.row("guest-2"));

        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);
        assertTrue(roster.admit("guest-2", "Carol", T0), "and is admitted once the first one is in");
    }

    @Test
    void readmittingTheSameIdIsIdempotentAndRefreshesTheName() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);

        assertTrue(roster.admit("guest-1", "Bobby", T0));
        assertEquals(1, roster.rows().size() - 1);
        assertEquals("Bobby", roster.row("guest-1").name());
    }

    @Test
    void aGuestThatDropsMidHandshakeLeavesNoRow() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        roster.setPhase("guest-1", CoopJoinPhase.SEED_LOCKED, T0);

        assertTrue(roster.dropPartial("guest-1"));
        assertNull(roster.row("guest-1"));
    }

    @Test
    void aGuestThatDropsAfterTheSnapshotKeepsItsRowAndItsReady() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);
        roster.setReady("guest-1", true, T0);

        assertFalse(roster.dropPartial("guest-1"), "past the snapshot the row is not partial");
        assertTrue(roster.markReconnecting("guest-1", T0));
        assertNotNull(roster.row("guest-1"));
        assertTrue(roster.row("guest-1").ready(), "ready survives the grace window");
        assertEquals("Reconnecting 0:42", roster.stateWord(roster.row("guest-1"), T0 + 42_000L));

        assertFalse(roster.allReady(), "a reconnecting player blocks the gate even while ready");
        assertEquals("Bob", roster.blockingName());

        roster.markReconnected("guest-1", T0 + 43_000L);
        assertTrue(roster.allReady());
    }

    @Test
    void readyIsOnlyAcceptedFromTheSnapshotPhaseOnAndIsRevocable() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);

        assertFalse(roster.setReady("guest-1", true, T0),
                "a player who has not got the world yet has nothing to be ready for");
        assertFalse(roster.row("guest-1").ready());

        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);
        assertTrue(roster.setReady("guest-1", true, T0));
        assertEquals(CoopJoinPhase.READY, roster.row("guest-1").phase());
        assertTrue(roster.allReady());

        assertTrue(roster.setReady("guest-1", false, T0 + 1L), "ready is revocable at any time");
        assertEquals(CoopJoinPhase.SNAPSHOT_APPLIED, roster.row("guest-1").phase());
        assertFalse(roster.allReady());
    }

    @Test
    void anEmptyLobbyIsNeverAllReady() {
        CoopLobbyRoster roster = openRoster();

        assertFalse(roster.allReady(), "starting alone is the hole this gate closes");
        assertEquals("Waiting for a player to connect...", roster.startLabel());
    }

    @Test
    void theStartLabelNamesTheBlockingPlayer() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);

        assertEquals("Waiting for Bob...", roster.startLabel());
        roster.setReady("guest-1", true, T0);
        assertEquals(CoopLobbyRoster.START_LABEL, roster.startLabel());
        assertNull(roster.blockingName());
    }

    @Test
    void stateWordsCoverEveryPhase() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        CoopLobbyRoster.Row row = roster.row("guest-1");

        assertEquals("Connecting...", roster.stateWord(row, T0));
        roster.setPhase("guest-1", CoopJoinPhase.VERSIONS_MATCHED, T0);
        assertEquals("Syncing 2/5", roster.stateWord(row, T0));
        roster.setPhase("guest-1", CoopJoinPhase.SEED_LOCKED, T0);
        assertEquals("Syncing 3/5", roster.stateWord(row, T0));
        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);
        assertEquals("Not ready", roster.stateWord(row, T0));
        roster.setReady("guest-1", true, T0);
        assertEquals("Ready", roster.stateWord(row, T0));

        roster.setReason("guest-1", "Mod mismatch", T0);
        assertEquals("Mod mismatch", roster.stateWord(roster.row("guest-1"), T0),
                "a blocking reason outranks the state word");
        assertFalse(roster.allReady());
    }

    @Test
    void resetReadyRevokesEveryGuestAndNamesThem() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);
        roster.setReady("guest-1", true, T0);
        roster.startCountdown(T0);

        List<String> affected = roster.resetReady("the host changed a setting", T0 + 5L);

        assertEquals(List.of("Bob"), affected);
        assertFalse(roster.row("guest-1").ready());
        assertEquals(CoopJoinPhase.SNAPSHOT_APPLIED, roster.row("guest-1").phase());
        assertEquals("the host changed a setting", roster.lastResetReason());
        assertFalse(roster.countdownActive(), "a reset cannot leave a countdown running");
        assertTrue(roster.rows().get(0).ready(), "the host stays ready; the reset is about guests");

        roster.clearResetReason();
        assertEquals("", roster.lastResetReason());
    }

    @Test
    void theCountdownRunsForThreeSecondsAndIsCancellable() {
        CoopLobbyRoster roster = openRoster();

        assertTrue(roster.startCountdown(T0));
        assertFalse(roster.startCountdown(T0), "arming twice is a no-op");
        assertEquals(CoopLobbyRoster.COUNTDOWN_MILLIS, roster.countdownRemainingMillis(T0));
        assertFalse(roster.countdownElapsed(T0 + 2_999L));
        assertTrue(roster.countdownElapsed(T0 + 3_000L));

        assertTrue(roster.cancelCountdown());
        assertFalse(roster.countdownActive());
        assertEquals(CoopLobbyRoster.NO_COUNTDOWN, roster.countdownRemainingMillis(T0));
        assertFalse(roster.countdownElapsed(T0 + 10_000L));
    }

    @Test
    void aMirroredCountdownIsRebasedOntoTheLocalClock() {
        CoopLobbyRoster roster = openRoster();

        // The host's clock is nowhere near ours; only the remaining value crosses the wire.
        roster.applyCountdownRemaining(1_500L, 900_000L);
        assertEquals(1_500L, roster.countdownRemainingMillis(900_000L));
        assertTrue(roster.countdownElapsed(901_500L));

        roster.applyCountdownRemaining(-1L, 901_600L);
        assertFalse(roster.countdownActive());
    }

    @Test
    void theAfkHintFiresAfterTwoMinutesAndNeverStartsAnything() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);

        assertFalse(roster.afkHint(T0 + 119_000L));
        assertTrue(roster.afkHint(T0 + 120_000L));
        assertFalse(roster.countdownActive(), "the hint surfaces the override, it does not fire it");

        roster.setReady("guest-1", true, T0);
        assertFalse(roster.afkHint(T0 + 600_000L));
    }

    @Test
    void theElapsedCounterRunsFromTheOpen() {
        CoopLobbyRoster roster = new CoopLobbyRoster();
        assertEquals(0L, roster.elapsedMillis(T0));

        roster.open(T0);
        roster.open(T0 + 50_000L);
        assertEquals(5_000L, roster.elapsedMillis(T0 + 5_000L), "a second open does not restart it");
    }

    @Test
    void replaceAllTakesTheHostsViewWholesale() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);

        roster.replaceAll(List.of(
                CoopLobbyRoster.mirroredRow("host-1", "Alice", true, CoopJoinPhase.READY, true, null, "", T0),
                CoopLobbyRoster.mirroredRow("guest-9", "Dave", false, CoopJoinPhase.SNAPSHOT_APPLIED,
                        false, null, "", T0)), T0);

        assertEquals(List.of("Alice", "Dave"),
                roster.rows().stream().map(CoopLobbyRoster.Row::name).toList());
        assertNull(roster.row("guest-1"), "the host owns this list; a merge would keep stale rows");
    }

    @Test
    void formatClockIsMinutesAndPaddedSeconds() {
        assertEquals("0:00", CoopLobbyRoster.formatClock(0L));
        assertEquals("0:09", CoopLobbyRoster.formatClock(9_400L));
        assertEquals("1:05", CoopLobbyRoster.formatClock(65_000L));
        assertEquals("0:00", CoopLobbyRoster.formatClock(-5L));
    }

    @Test
    void setPhaseNeverMarksSomebodyReady() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);

        assertFalse(roster.setPhase("guest-1", CoopJoinPhase.READY, T0),
                "readying is a player action, never a protocol one");
        assertFalse(roster.row("guest-1").ready());
    }

    @Test
    void fallingBackBelowTheSnapshotPhaseClearsAStaleReady() {
        CoopLobbyRoster roster = openRoster();
        roster.admit("guest-1", "Bob", T0);
        roster.setPhase("guest-1", CoopJoinPhase.SNAPSHOT_APPLIED, T0);
        roster.setReady("guest-1", true, T0);

        roster.setPhase("guest-1", CoopJoinPhase.SEED_LOCKED, T0 + 1L);
        assertFalse(roster.row("guest-1").ready());
    }
}
