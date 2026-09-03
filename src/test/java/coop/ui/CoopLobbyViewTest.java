package coop.ui;

import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structural / live split the lobby dialog renders from. Pure values, so what does and does not
 * cost a panel rebuild is decided here rather than inside an engine call.
 */
class CoopLobbyViewTest {

    private static CoopLobbyView view(long countdownRemaining, long elapsedMillis, int rtt) {
        return new CoopLobbyView(CoopConnectionRole.HOST,
                List.of(new CoopLobbyView.Row("Alice", "Ready", true),
                        new CoopLobbyView.Row("Bob", "Not ready", false)),
                List.of("Connection: direct", "Endpoint: 91.77.0.1:7777", "Link: " + rtt + " ms over UDP"),
                countdownRemaining, elapsedMillis, false, false, "Waiting for Bob...", false, false, false);
    }

    @Test
    void theTickingNumbersAreAllOutsideTheStructuralKey() {
        CoopLobbyView.Key key = view(-1L, 12_000L, 42).structuralKey();

        assertEquals(key, view(-1L, 47_000L, 42).structuralKey(), "elapsed time is not structural");
        assertEquals(key, view(-1L, 12_000L, 310).structuralKey(), "nor is the RTT sample");
        assertEquals(view(5_000L, 12_000L, 42).structuralKey(), view(2_000L, 90_000L, 310).structuralKey(),
                "nor are the seconds left on a running countdown");
    }

    @Test
    void startingOrCancellingACountdownIsStructural() {
        assertNotEquals(view(-1L, 12_000L, 42).structuralKey(), view(5_000L, 12_000L, 42).structuralKey());
    }

    @Test
    void aRosterThatMovesIsStructural() {
        CoopLobbyView moved = new CoopLobbyView(CoopConnectionRole.HOST,
                List.of(new CoopLobbyView.Row("Alice", "Ready", true),
                        new CoopLobbyView.Row("Bob", "Ready", false)),
                List.of("Connection: direct", "Endpoint: 91.77.0.1:7777", "Link: 42 ms over UDP"),
                -1L, 12_000L, false, false, "Waiting for Bob...", false, false, false);

        assertNotEquals(view(-1L, 12_000L, 42).structuralKey(), moved.structuralKey());
    }

    @Test
    void theLiveVerdictLineIsSplitOutOfTheStableBlock() {
        CoopLobbyView view = view(-1L, 12_000L, 42);

        assertEquals(List.of("Connection: direct", "Endpoint: 91.77.0.1:7777"), view.stableVerdictLines());
        assertEquals("Link: 42 ms over UDP", view.liveVerdictLine());
    }

    @Test
    void aViewWithNoLinkSampleYetHasNoLiveVerdictLine() {
        CoopLobbyView view = new CoopLobbyView(CoopConnectionRole.GUEST, List.of(),
                List.of("Connection: direct"), -1L, 3_000L, false, false, "", false, false, false);

        assertEquals("", view.liveVerdictLine());
        assertEquals("Waiting 0:03.", view.liveLine());
    }

    @Test
    void theCountdownDisplacesTheElapsedCounterInTheLiveLine() {
        assertEquals("Waiting 0:12. - Link: 42 ms over UDP", view(-1L, 12_000L, 42).liveLine());
        assertEquals("Starting in 3... - Link: 42 ms over UDP", view(2_400L, 12_000L, 42).liveLine());
        assertEquals("Starting... - Link: 42 ms over UDP", view(0L, 12_000L, 42).liveLine());
    }

    @Test
    void theCountdownLineChangesOnlyOnWholeSeconds() {
        String at3s = view(3_000L, 12_000L, 42).liveLine();

        for (long remaining = 3_000L; remaining > 2_000L; remaining -= 50L) {
            assertEquals(at3s, view(remaining, 12_000L + remaining, 42).liveLine(),
                    "millisecond " + remaining + " must read the same as the whole second above it");
        }
        assertTrue(at3s.startsWith("Starting in 3..."));
        assertNotEquals(at3s, view(2_000L, 12_000L, 42).liveLine());
    }
}
