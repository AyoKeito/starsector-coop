package coop.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The HUD's transient slot: one line, last writer wins, gone after its time is up. */
class CoopHudNoticeTest {

    @BeforeEach
    @AfterEach
    void emptyTheSlot() {
        // Static state, so a leftover line would leak into the next test in the tree.
        CoopHudNotice.clear();
    }

    @Test
    void anEmptySlotDrawsNothing() {
        assertEquals("", CoopHudNotice.current(0L));
    }

    @Test
    void aNoticeIsShownForItsTtlAndThenStops() {
        CoopHudNotice.show("partner marked host#3", 1_000L);

        assertEquals("partner marked host#3", CoopHudNotice.current(1_000L));
        assertEquals("partner marked host#3",
                CoopHudNotice.current(1_000L + CoopHudNotice.TTL_MILLIS - 1L));
        assertEquals("", CoopHudNotice.current(1_000L + CoopHudNotice.TTL_MILLIS));
        assertEquals("", CoopHudNotice.current(9_999_999L));
    }

    @Test
    void theSecondNoticeReplacesTheFirstAndRestartsItsClock() {
        CoopHudNotice.show("marked host#1", 0L);
        CoopHudNotice.show("marked host#2", 4_000L);

        assertEquals("marked host#2", CoopHudNotice.current(4_000L),
                "one slot: the log is where the history lives");
        assertEquals("marked host#2", CoopHudNotice.current(8_000L),
                "and the replacement gets a full ttl of its own");
    }

    @Test
    void aBlankNoticeClearsTheSlotRatherThanDrawingAnEmptyRow() {
        CoopHudNotice.show("marked host#1", 0L);

        CoopHudNotice.show("   ", 100L);

        assertEquals("", CoopHudNotice.current(100L));
    }
}
