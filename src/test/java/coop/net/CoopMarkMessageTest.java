package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code MARK} codec: what the partner has to be able to read back out of one. */
class CoopMarkMessageTest {

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        CoopMessages.Message message = CoopMessages.mark("session-a", 12L, 99L, "host#3",
                206.412f, "Corvus (hyperspace)", 48120L, "the market reset itself");

        assertEquals(CoopMessages.Type.MARK, message.type());
        assertEquals("session-a", message.sessionId());
        assertEquals(12L, message.seq());
        assertEquals(99L, message.sentAtMillis());

        CoopMessages.Mark mark = CoopMessages.parseMark(message);
        assertEquals("host#3", mark.markerId());
        assertEquals(206.412f, mark.day(), 0.0001f);
        assertEquals("Corvus (hyperspace)", mark.location());
        assertEquals(48120L, mark.markSeq());
        assertEquals("the market reset itself", mark.note());
        assertTrue(mark.hasNote());
    }

    @Test
    void theImmediateHalfCarriesNoNote() {
        CoopMessages.Mark mark = CoopMessages.parseMark(
                CoopMessages.mark("session-a", 1L, 0L, "guest#1", 7f, "Askonia", 1L, ""));

        assertEquals("", mark.note());
        assertFalse(mark.hasNote());
    }

    @Test
    void aNoteIsSanitisedBeforeItReachesTheWire() {
        // The quote matters: the receiver writes the note inside quotes, and json escaping alone
        // would still leave a line a reader cannot parse by eye.
        CoopMessages.Mark mark = CoopMessages.parseMark(CoopMessages.mark("session-a", 1L, 0L,
                "host#1", 1f, "Corvus", 1L, "said \"hi\"\nthen left"));

        assertEquals("said 'hi' then left", mark.note());
    }

    @Test
    void aMarkerWithoutASessionOrAnIdIsACallerBugAndIsRefused() {
        assertThrows(RuntimeException.class, () -> CoopMessages.mark(null, 1L, 0L, "host#1",
                1f, "Corvus", 1L, ""));
        assertThrows(RuntimeException.class, () -> CoopMessages.mark("session-a", 1L, 0L, "",
                1f, "Corvus", 1L, ""));
    }

    @Test
    void aMalformedDayReadsAsZeroRatherThanLosingTheMarker() {
        CoopMessages.Message broken = new CoopMessages.Message(CoopMessages.Type.MARK, "session-a",
                5L, 0L, "{\"markerId\":\"host#1\",\"day\":\"later\",\"location\":\"Corvus\"}");

        CoopMessages.Mark mark = CoopMessages.parseMark(broken);

        assertEquals("host#1", mark.markerId());
        assertEquals(0f, mark.day(), 0f);
        assertEquals(5L, mark.markSeq(), "a missing markSeq falls back to the envelope's");
    }

    @Test
    void aMarkerIsReliableSoItCannotReachOnlyOneOfTheTwoLogs() {
        assertTrue(CoopMessages.isReliableOneShot(CoopMessages.Type.MARK));
    }
}
