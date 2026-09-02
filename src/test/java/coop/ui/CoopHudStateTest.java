package coop.ui;

import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    // ---- displayHolder: raw wire token -> wording for whoever is reading ------------------------

    @Test
    void displayHolderOnTheHostCallsTheHostYou() {
        assertEquals("you", CoopHudState.displayHolder(CoopHudState.HOLDER_HOST, CoopConnectionRole.HOST));
        assertEquals("guest", CoopHudState.displayHolder(CoopHudState.HOLDER_GUEST, CoopConnectionRole.HOST));
        assertEquals("guest's screen",
                CoopHudState.displayHolder(CoopHudState.HOLDER_GUEST_SCREEN, CoopConnectionRole.HOST));
        assertEquals("combat", CoopHudState.displayHolder(CoopHudState.HOLDER_COMBAT, CoopConnectionRole.HOST));
    }

    @Test
    void displayHolderOnTheGuestCallsTheGuestYou() {
        assertEquals("host", CoopHudState.displayHolder(CoopHudState.HOLDER_HOST, CoopConnectionRole.GUEST));
        assertEquals("you", CoopHudState.displayHolder(CoopHudState.HOLDER_GUEST, CoopConnectionRole.GUEST));
        assertEquals("your screen",
                CoopHudState.displayHolder(CoopHudState.HOLDER_GUEST_SCREEN, CoopConnectionRole.GUEST));
        assertEquals("combat", CoopHudState.displayHolder(CoopHudState.HOLDER_COMBAT, CoopConnectionRole.GUEST));
    }

    @Test
    void displayHolderMapsNobodyToTheEmptyString() {
        assertEquals("", CoopHudState.displayHolder(null, CoopConnectionRole.HOST));
        assertEquals("", CoopHudState.displayHolder("", CoopConnectionRole.GUEST));
        assertEquals("", CoopHudState.displayHolder("   ", CoopConnectionRole.GUEST));
        assertEquals("", CoopHudState.displayHolder(null, null));
    }

    @Test
    void displayHolderPassesThroughWhenThereIsNoLocalRole() {
        assertEquals("host", CoopHudState.displayHolder(CoopHudState.HOLDER_HOST, CoopConnectionRole.NONE));
        assertEquals("guest", CoopHudState.displayHolder(CoopHudState.HOLDER_GUEST, null));
    }

    @Test
    void displayHolderPassesThroughAnUnknownTokenInsteadOfDroppingIt() {
        assertEquals("some future holder",
                CoopHudState.displayHolder("some future holder", CoopConnectionRole.GUEST));
        assertEquals("some future holder",
                CoopHudState.displayHolder("some future holder", CoopConnectionRole.HOST));
    }

    @Test
    void displayHolderFeedsTheRenderedLine() {
        for (String raw : new String[] {CoopHudState.HOLDER_HOST, CoopHudState.HOLDER_GUEST,
                CoopHudState.HOLDER_GUEST_SCREEN, CoopHudState.HOLDER_COMBAT}) {
            CoopHudState guest = new CoopHudState(CoopHudState.BADGE_GUEST,
                    CoopHudState.STATUS_SESSION_ACTIVE, true,
                    CoopHudState.displayHolder(raw, CoopConnectionRole.GUEST), null);
            assertEquals("GUEST · session active · paused by "
                            + CoopHudState.displayHolder(raw, CoopConnectionRole.GUEST),
                    CoopHudState.formatLine(guest, DOT));
        }
        CoopHudState hostSelfPause = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, true,
                CoopHudState.displayHolder(CoopHudState.HOLDER_HOST, CoopConnectionRole.HOST), null);
        assertEquals("HOST · session active · paused by you", CoopHudState.formatLine(hostSelfPause, DOT));
    }

    // ---- Phase 20.6 M2: link readout ------------------------------------------------------------

    @Test
    void theFiveFieldConstructorLeavesTheLinkReadoutAbsent() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, null);

        assertNull(state.rttMillis());
        assertNull(state.lossPercent());
        assertNull(state.transport());
        assertEquals("HOST · session active", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void aLiveSessionAppendsRttLossAndTransport() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, null,
                87, 2, CoopHudState.TRANSPORT_UDP);

        assertEquals("GUEST · session active · 87 ms · loss 2% · udp",
                CoopHudState.formatLine(state, DOT));
    }

    @Test
    void theFallbackTransportIsNamedInTheLine() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, null,
                310, 0, CoopHudState.TRANSPORT_TCP_FALLBACK);

        assertEquals("HOST · session active · 310 ms · loss 0% · tcp fallback",
                CoopHudState.formatLine(state, DOT));
    }

    /** Before the first PONG lands there is no RTT to show, but the transport is already known. */
    @Test
    void anUnmeasuredRttIsOmittedRatherThanShownAsZero() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_SESSION_ACTIVE, false, null, null,
                null, 0, CoopHudState.TRANSPORT_UDP);

        assertEquals("HOST · session active · loss 0% · udp", CoopHudState.formatLine(state, DOT));
    }

    @Test
    void theLinkReadoutSitsAfterThePauseHolderAndTheClockDrift() {
        CoopHudState state = new CoopHudState(CoopHudState.BADGE_GUEST,
                CoopHudState.STATUS_SESSION_ACTIVE, true, "host", 3,
                140, 11, CoopHudState.TRANSPORT_UDP);

        assertEquals("GUEST · session active · paused by host · guest 3h behind · 140 ms · loss 11% · udp",
                CoopHudState.formatLine(state, DOT));
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

    @Test
    void theReconnectHolderReadsTheSameOnBothClients() {
        // Nobody "owns" this pause, so unlike host/guest it is not rewritten to "you" on either side.
        assertEquals("reconnect",
                CoopHudState.displayHolder(CoopHudState.HOLDER_RECONNECT, CoopConnectionRole.HOST));
        assertEquals("reconnect",
                CoopHudState.displayHolder(CoopHudState.HOLDER_RECONNECT, CoopConnectionRole.GUEST));

        CoopHudState state = new CoopHudState(CoopHudState.BADGE_HOST,
                CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING, true, "reconnect", null);
        assertEquals("HOST · guest disconnected, holding · paused by reconnect",
                CoopHudState.formatLine(state, DOT));
    }
}
