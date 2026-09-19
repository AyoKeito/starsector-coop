package coop.mark;

import coop.net.CoopMessages;
import coop.testing.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The marker protocol with no game attached: what gets logged, what goes on the wire, when the note
 * box is asked for, and what the receiver writes.
 */
class CoopMarkServiceTest {

    private final List<CoopMessages.Message> sent = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private final List<String> noteBoxes = new ArrayList<>();

    private String role = CoopMarkFormat.ROLE_HOST;
    private String sessionId = "session-a";
    private long seq;
    private CoopMarkService.World world = new CoopMarkService.World(206.412f, "Corvus", true);

    private CoopMarkService service() {
        return new CoopMarkService(
                () -> role,
                () -> sessionId,
                () -> ++seq,
                () -> 1_000L,
                sent::add,
                () -> world,
                notices::add,
                noteBoxes::add);
    }

    // ---- numbering -------------------------------------------------------------------------------

    @Test
    void theCounterStartsAtOneAndIsNamespacedByRole() {
        CoopMarkService host = service();
        assertEquals("host#1", host.onKeyPressed());
        assertEquals("host#2", host.onKeyPressed());
        assertEquals(2, host.pressedCount());

        role = CoopMarkFormat.ROLE_GUEST;
        CoopMarkService guest = service();
        assertEquals("guest#1", guest.onKeyPressed(),
                "the two players number independently, so their ids cannot collide");
    }

    @Test
    void aNewSessionStartsCountingAgain() {
        CoopMarkService service = service();
        service.onKeyPressed();
        service.onKeyPressed();

        service.reset();

        assertEquals(0, service.pressedCount());
        assertEquals("host#1", service.onKeyPressed());
    }

    // ---- the immediate line ----------------------------------------------------------------------

    @Test
    void thePressWritesItsLineBeforeAnythingElseCanFail() {
        LogCapture log = LogCapture.attach(CoopMarkService.class);
        try {
            service().onKeyPressed();

            assertEquals(List.of("COOP-MARK host#1 | day 206.41 | Corvus | seq 1 | \"\""),
                    log.messages());
        } finally {
            log.detach();
        }
    }

    @Test
    void thePressSendsTheSameFactsToThePartner() {
        service().onKeyPressed();

        assertEquals(1, sent.size());
        CoopMessages.Message message = sent.get(0);
        assertEquals(CoopMessages.Type.MARK, message.type());
        assertEquals("session-a", message.sessionId());

        CoopMessages.Mark mark = CoopMessages.parseMark(message);
        assertEquals("host#1", mark.markerId());
        assertEquals(206.412f, mark.day(), 0.0001f);
        assertEquals("Corvus", mark.location());
        assertEquals(1L, mark.markSeq());
        assertEquals("", mark.note());
        assertFalse(mark.hasNote());
    }

    @Test
    void thePresserSeesTheirOwnConfirmation() {
        service().onKeyPressed();

        assertEquals(List.of("marked host#1"), notices);
    }

    @Test
    void aMarkerWithNoSessionIsStillWrittenLocally() {
        sessionId = "";
        LogCapture log = LogCapture.attach(CoopMarkService.class);
        try {
            assertEquals("host#1", service().onKeyPressed());
            assertEquals(1, log.messages().size());
            assertTrue(sent.isEmpty(), "there is nobody to send it to");
        } finally {
            log.detach();
        }
    }

    // ---- the note box ----------------------------------------------------------------------------

    @Test
    void theNoteBoxIsAskedForOnlyWhenTheMapIsFree() {
        CoopMarkService free = service();
        free.onKeyPressed();
        assertEquals(List.of("host#1"), noteBoxes);
        assertEquals("host#1", free.awaitingNoteFor());

        noteBoxes.clear();
        world = new CoopMarkService.World(206.412f, "Corvus", false);
        CoopMarkService busy = service();
        busy.onKeyPressed();
        assertTrue(noteBoxes.isEmpty(), "nothing is queued behind a market screen");
        assertEquals("", busy.awaitingNoteFor());
    }

    @Test
    void okWithTextWritesASecondLineAndSendsASecondMessage() {
        CoopMarkService service = service();
        service.onKeyPressed();
        sent.clear();
        notices.clear();

        LogCapture log = LogCapture.attach(CoopMarkService.class);
        try {
            service.onNoteEntered("host#1", "the market reset itself");

            assertEquals(List.of("COOP-MARK host#1 note | \"the market reset itself\""),
                    log.messages());
        } finally {
            log.detach();
        }

        assertEquals(1, sent.size());
        CoopMessages.Mark mark = CoopMessages.parseMark(sent.get(0));
        assertEquals("host#1", mark.markerId(), "the same marker, not a new one");
        assertEquals("the market reset itself", mark.note());
        assertTrue(mark.hasNote());
        assertEquals(List.of("marked host#1: the market reset itself"), notices);
        assertEquals("", service.awaitingNoteFor());
    }

    @Test
    void cancelAndAnEmptyNoteBothWriteNothingFurther() {
        for (String typed : new String[]{"", "   ", null}) {
            sent.clear();
            notices.clear();
            CoopMarkService service = service();
            service.onKeyPressed();
            sent.clear();
            notices.clear();

            service.onNoteEntered("host#1", typed);

            assertTrue(sent.isEmpty(), "no second message for " + typed);
            assertTrue(notices.isEmpty(), "no second notice for " + typed);
            assertEquals("", service.awaitingNoteFor());
        }
    }

    @Test
    void aNoteForAMarkerNobodyIsWaitingOnIsIgnored() {
        CoopMarkService service = service();
        service.onKeyPressed();
        service.onNoteBoxClosed();
        sent.clear();

        service.onNoteEntered("host#1", "late");

        assertTrue(sent.isEmpty(), "a stale dialog callback must not append to a finished marker");
    }

    @Test
    void theNoteHalfReportsWhereThePressHappened() {
        CoopMarkService service = service();
        service.onKeyPressed();
        sent.clear();
        // The fleet moved while the player typed. The marker is about the press, not about now.
        world = new CoopMarkService.World(999f, "Askonia", true);

        service.onNoteEntered("host#1", "here");

        CoopMessages.Mark mark = CoopMessages.parseMark(sent.get(0));
        assertEquals(206.412f, mark.day(), 0.0001f);
        assertEquals("Corvus", mark.location());
    }

    // ---- the receiving side ----------------------------------------------------------------------

    @Test
    void theReceiverWritesTheSendersLineVerbatim() {
        CoopMessages.Message inbound = CoopMessages.mark("session-a", 9L, 0L, "guest#4",
                206.412f, "Corvus (hyperspace)", 48120L, "");

        LogCapture log = LogCapture.attach(CoopMarkService.class);
        try {
            service().applyInbound(inbound);

            assertEquals(
                    List.of("COOP-MARK guest#4 | day 206.41 | Corvus (hyperspace) | seq 48120 | \"\""),
                    log.messages(),
                    "the receiver renders the sender's numbers, or the two logs would disagree");
        } finally {
            log.detach();
        }
        assertEquals(List.of("partner marked guest#4"), notices);
        assertTrue(sent.isEmpty(), "receiving a marker must not echo one back");
    }

    @Test
    void theReceiverWritesTheNoteHalfAsItsOwnLine() {
        CoopMessages.Message inbound = CoopMessages.mark("session-a", 9L, 0L, "guest#4",
                206.412f, "Corvus", 48121L, "fuel went backwards");

        LogCapture log = LogCapture.attach(CoopMarkService.class);
        try {
            service().applyInbound(inbound);

            assertEquals(List.of("COOP-MARK guest#4 note | \"fuel went backwards\""), log.messages());
        } finally {
            log.detach();
        }
        assertEquals(List.of("partner marked guest#4: fuel went backwards"), notices);
    }

    @Test
    void aMarkerDoesNotAdvanceTheLocalCounter() {
        CoopMarkService service = service();
        service.applyInbound(CoopMessages.mark("session-a", 9L, 0L, "guest#1",
                1f, "Corvus", 9L, ""));

        assertEquals(0, service.pressedCount());
        assertEquals("host#1", service.onKeyPressed(), "the partner's markers are not ours");
    }
}
