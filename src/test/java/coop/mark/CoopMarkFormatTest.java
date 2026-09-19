package coop.mark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The marker lines are the feature, so their exact characters are pinned here. A tester greps
 * {@code COOP-MARK} out of two logs and reads the fields off by eye; a field that moves or a
 * separator that changes breaks every runbook that does it.
 */
class CoopMarkFormatTest {

    @Test
    void theImmediateLineHasTheExactShapeTheRunbooksGrepFor() {
        assertEquals("COOP-MARK host#3 | day 206.41 | Corvus (hyperspace) | seq 48120 | \"\"",
                CoopMarkFormat.line("host#3", 206.412f, "Corvus (hyperspace)", 48120L, ""));
    }

    @Test
    void aNotedLineCarriesTheTextInsideTheQuotes() {
        assertEquals("COOP-MARK guest#1 | day 7.00 | Askonia | seq 12 | \"fuel went backwards\"",
                CoopMarkFormat.line("guest#1", 7f, "Askonia", 12L, "fuel went backwards"));
    }

    @Test
    void theNoteLineIsItsOwnShorterLine() {
        assertEquals("COOP-MARK host#2 note | \"the market reset itself\"",
                CoopMarkFormat.noteLine("host#2", "the market reset itself"));
    }

    @Test
    void theDayAlwaysCarriesTwoDecimalsAndAPoint() {
        // Locale.ROOT, not the platform default: a comma here would break a grep that splits on it.
        assertEquals("0.00", CoopMarkFormat.day(0f));
        assertEquals("7.00", CoopMarkFormat.day(7f));
        assertEquals("206.41", CoopMarkFormat.day(206.4149f));
        assertEquals("206.42", CoopMarkFormat.day(206.4162f));
    }

    @Test
    void hyperspaceIsASuffixOnTheContainingLocation() {
        assertEquals("Corvus", CoopMarkFormat.location("Corvus", false));
        assertEquals("Corvus (hyperspace)", CoopMarkFormat.location("Corvus", true));
        assertEquals("Hyperspace (hyperspace)", CoopMarkFormat.location("Hyperspace", true));
    }

    @Test
    void anAbsentLocationReadsAsUnknownRatherThanAsAnEmptyField() {
        assertEquals("unknown", CoopMarkFormat.location(null, false));
        assertEquals("unknown", CoopMarkFormat.location("   ", false));
        assertEquals("unknown (hyperspace)", CoopMarkFormat.location("", true));
        assertTrue(CoopMarkFormat.line("host#1", 1f, "", 1L, "").contains("| unknown |"));
    }

    @Test
    void markerIdsCannotCollideBetweenThePlayers() {
        assertEquals("host#1", CoopMarkFormat.markerId("host", 1));
        assertEquals("guest#1", CoopMarkFormat.markerId("guest", 1));
        assertEquals("host#12", CoopMarkFormat.markerId("host", 12));
        // Anything that is not the guest is the host: a role string this does not recognise must
        // still produce a well-formed id rather than a third namespace.
        assertEquals("host#4", CoopMarkFormat.markerId("spectator", 4));
        assertEquals("host#1", CoopMarkFormat.markerId("host", 0), "the counter starts at 1");
    }

    @Test
    void aNoteIsOneLineWithoutQuotesAndBounded() {
        assertEquals("said 'hello'", CoopMarkFormat.note("said \"hello\""));
        assertEquals("two lines", CoopMarkFormat.note("two\nlines"));
        assertEquals("a b", CoopMarkFormat.note("a\tb"));
        assertEquals("", CoopMarkFormat.note(null));
        assertEquals("", CoopMarkFormat.note("   "));

        String long_ = "x".repeat(CoopMarkFormat.MAX_NOTE_CHARS + 40);
        assertEquals(CoopMarkFormat.MAX_NOTE_CHARS, CoopMarkFormat.note(long_).length());
    }

    @Test
    void aNoteCanNeverBreakTheLineItIsWrittenInto() {
        String hostile = "a\"b\nc";
        String line = CoopMarkFormat.noteLine("host#1", hostile);
        assertEquals("COOP-MARK host#1 note | \"a'b c\"", line);
        assertEquals(2, line.chars().filter(c -> c == '"').count(),
                "exactly the opening and closing quote");
    }

    @Test
    void theHudNoticesNameTheMarkerAndCarryTheNoteWhenThereIsOne() {
        assertEquals("marked host#3", CoopMarkFormat.ownNotice("host#3", ""));
        assertEquals("marked host#3: broke", CoopMarkFormat.ownNotice("host#3", "broke"));
        assertEquals("partner marked host#3", CoopMarkFormat.partnerNotice("host#3", null));
        assertEquals("partner marked host#3: broke", CoopMarkFormat.partnerNotice("host#3", "broke"));
    }
}
