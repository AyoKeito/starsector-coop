package coop.ui;

import coop.net.CoopConnectionRole;

import java.util.List;

/**
 * One frame's worth of lobby, as a pure value. The dialog polls a {@code Supplier<CoopLobbyView>}
 * and re-renders only when the value changed, which is what keeps the text panel from being rebuilt
 * at frame rate; because the whole thing is a record, "changed" is {@code equals}.
 *
 * <p>Nothing here touches the engine or the transport: the pump builds it from state it already owns,
 * so the entire lobby wording is unit-testable with no game running.
 *
 * @param localRole      the role of the client reading this lobby
 * @param rows           roster rows in fixed order: host first, then join order
 * @param verdictLines   the Phase 20 connection-doctor block as plain lines (tier, endpoint, RTT,
 *                       transport), already worded by {@code CoopSessionIntelModel}
 * @param countdownRemainingMillis milliseconds left on the start countdown, or -1 when none is running
 * @param elapsedMillis  how long the lobby has been open; ticks so "slow" reads differently from "dead"
 * @param afkHint        whether to show the line pointing the host at "Start anyway"
 * @param allReady       whether the Start option may arm
 * @param startLabel     the Start option's label, blocking condition included
 * @param localReady     the local player's own ready value (guest only; the host is auto-ready)
 * @param canReady       whether the local guest has reached the phase where readying is accepted
 * @param released       true on the frame the session actually starts
 */
public record CoopLobbyView(CoopConnectionRole localRole,
                            List<Row> rows,
                            List<String> verdictLines,
                            long countdownRemainingMillis,
                            long elapsedMillis,
                            boolean afkHint,
                            boolean allReady,
                            String startLabel,
                            boolean localReady,
                            boolean canReady,
                            boolean released) {

    public CoopLobbyView {
        rows = rows == null ? List.of() : List.copyOf(rows);
        verdictLines = verdictLines == null ? List.of() : List.copyOf(verdictLines);
        startLabel = startLabel == null ? "" : startLabel;
    }

    /**
     * One rendered roster row: name plus the state word that carries the load. Deliberately not a
     * coloured dot — dots alone get mis-read, and the phase research is unambiguous that the word is
     * what players actually parse.
     */
    public record Row(String name, String stateWord, boolean local) {
        public Row {
            name = name == null ? "" : name;
            stateWord = stateWord == null ? "" : stateWord;
        }

        /** The line the text panel prints for this row. */
        public String line() {
            return (local ? "> " : "  ") + name + " - " + stateWord;
        }
    }

    /** Whole-second countdown for display; 0 when nothing is counting. */
    public int countdownSeconds() {
        if (countdownRemainingMillis < 0L) {
            return 0;
        }
        return (int) ((countdownRemainingMillis + 999L) / 1000L);
    }

    /** m:ss since the lobby opened. */
    public String elapsedText() {
        return coop.session.CoopLobbyRoster.formatClock(elapsedMillis);
    }
}
