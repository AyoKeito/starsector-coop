package coop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopHudStateTest {

    private static final String DOT = CoopHudState.SEPARATOR_DOT;
    private static final String PIPE = CoopHudState.SEPARATOR_PIPE;

    @Test
    void hostSessionActive() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, null);

        assertEquals("HOST · session active", CoopHudState.formatLine(state, DOT));
        assertEquals("HOST | session active", CoopHudState.formatLine(state, PIPE));
    }

    @Test
    void hostSessionActivePausedByGuest() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, true, "guest", null);

        assertEquals("HOST · session active · paused by guest", CoopHudState.formatLine(state, DOT));
        assertEquals("HOST | session active | paused by guest", CoopHudState.formatLine(state, PIPE));
    }

    @Test
    void hostPauseHolderVariants() {
        for (String holder : new String[] {"host", "guest", "guest screen", "combat"}) {
            CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                    CoopHudState.STATUS_SESSION_ACTIVE, true, holder, null);
            assertEquals("HOST · session active · paused by " + holder,
                    CoopHudState.formatLine(state, DOT));
        }
    }

    @Test
    void guestBehindHost() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, 3);

        assertEquals("GUEST · session active · guest 3h behind", CoopHudState.formatLine(state, DOT));
        assertEquals("GUEST | session active | guest 3h behind", CoopHudState.formatLine(state, PIPE));
    }

    @Test
    void guestAheadOfHost() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, -2);

        assertEquals("GUEST · session active · guest 2h ahead", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void guestPausedByHostWithDrift() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, true, "host", 1);

        assertEquals("GUEST · session active · paused by host · guest 1h behind",
                CoopHudState.formatLine(state, DOT));
    }

    @Test
    void zeroDriftIsNotDrawn() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, 0);

        assertEquals("GUEST · session active", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void nullHolderAddsNoPauseSegmentEvenWhenPaused() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, true, null, null);

        assertEquals("HOST · session active", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void emptyHolderAddsNoPauseSegment() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, true, "", null);

        assertEquals("HOST · session active", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void hostWaitingForGuest() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_WAITING_FOR_GUEST, true, null, null);

        assertEquals("HOST · waiting for guest", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void guestConnecting() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_CONNECTING, false, null, null);

        assertEquals("GUEST · connecting", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void hostHoldingAfterGuestDropped() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING, true, null, null);

        assertEquals("HOST · guest disconnected, holding", CoopHudState.formatLine(state, DOT));
        assertEquals("HOST | guest disconnected, holding", CoopHudState.formatLine(state, PIPE));
    }

    @Test
    void noSession() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_COOP,
                CoopHudState.STATUS_NO_SESSION, false, null, null);

        assertEquals("COOP · no session", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void nullStateAndNullFieldsDegradeInsteadOfThrowing() {
        assertEquals("", CoopHudState.formatLine(null, DOT));
        assertEquals("COOP | no session",
                CoopHudState.formatLine(new CoopHudState(null, null, false, null, null), null));
    }

    @Test
    void lineAlwaysStartsWithTheBadgeSoTheHudCanSplitOnIt() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, true, "host", -4);
        String line = CoopHudState.formatLine(state, DOT);

        assertEquals(CoopHudState.BADGE_GUEST, line.substring(0, state.roleBadge().length()));
        assertEquals(" · session active · paused by host · guest 4h ahead",
                line.substring(state.roleBadge().length()));
    }
}
