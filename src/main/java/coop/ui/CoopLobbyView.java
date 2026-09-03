package coop.ui;

import coop.net.CoopConnectionRole;

import java.util.ArrayList;
import java.util.List;

/**
 * One frame's worth of lobby, as a pure value. The dialog polls a {@code Supplier<CoopLobbyView>}
 * and re-renders only when the value changed, which is what keeps the text panel from being rebuilt
 * at frame rate.
 *
 * <p><b>Two kinds of change.</b> Whole-view {@code equals} is too coarse to drive rendering: three
 * fields here move on their own (the elapsed counter, the countdown, and the RTT inside the
 * verdict block), and a lobby dialog that clears and rebuilds its text panel on each of them
 * flashes several times a second, which a player reported as unreadable during the countdown. So
 * the view splits
 * itself: {@link #structuralKey()} is everything a rebuild is actually needed for, and
 * {@link #liveLine()} is the single trailing paragraph that holds every number that ticks. The
 * dialog rebuilds on the first and rewrites only the last paragraph for the second.
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

    /**
     * Prefix of the one verdict line that carries a number which moves on its own: the live RTT
     * sample. {@code CoopNetPump.lobbyVerdictLines()} builds it ("Link: 42 ms over UDP") and the tier
     * and endpoint lines above it change only when the connection itself does. Kept as a prefix match
     * rather than a fourth list because the pump hands the block over as plain strings; if that
     * wording ever changes, the line simply goes back to being structural and the lobby is a little
     * flashier, which is the safe direction for a mismatch to fail in.
     */
    public static final String LIVE_VERDICT_PREFIX = "Link: ";

    public CoopLobbyView {
        rows = rows == null ? List.of() : List.copyOf(rows);
        verdictLines = verdictLines == null ? List.of() : List.copyOf(verdictLines);
        startLabel = startLabel == null ? "" : startLabel;
    }

    /**
     * Everything the dialog has to clear and rebuild the panels for: the roster, the gate, the
     * confirmation-relevant flags, and the stable half of the verdict block. Deliberately excludes
     * every ticking number, which is what {@link #liveLine()} carries.
     *
     * @param countingDown whether a countdown is running at all; the seconds left are live, but
     *                     starting and cancelling one changes which options are on offer
     */
    public record Key(CoopConnectionRole localRole,
                      List<Row> rows,
                      List<String> verdictLines,
                      boolean countingDown,
                      boolean afkHint,
                      boolean allReady,
                      String startLabel,
                      boolean localReady,
                      boolean canReady,
                      boolean released) {
    }

    /** @see Key */
    public Key structuralKey() {
        return new Key(localRole, rows, stableVerdictLines(), countingDown(), afkHint, allReady,
                startLabel, localReady, canReady, released);
    }

    /** Whether a start countdown is running; the seconds left are {@link #countdownSeconds()}. */
    public boolean countingDown() {
        return countdownRemainingMillis >= 0L;
    }

    /** The verdict lines that do not move by themselves. */
    public List<String> stableVerdictLines() {
        List<String> stable = new ArrayList<>(verdictLines.size());
        for (String line : verdictLines) {
            if (line == null || !line.startsWith(LIVE_VERDICT_PREFIX)) {
                stable.add(line);
            }
        }
        return List.copyOf(stable);
    }

    /** The verdict line carrying the live RTT, or {@code ""} when there is no link sample yet. */
    public String liveVerdictLine() {
        for (String line : verdictLines) {
            if (line != null && line.startsWith(LIVE_VERDICT_PREFIX)) {
                return line;
            }
        }
        return "";
    }

    /**
     * The lobby's last paragraph: every number that ticks, on one line, so a tick is one
     * {@code replaceLastParagraph} instead of a panel rebuild.
     *
     * <p>The elapsed counter is dropped while a countdown runs. Two 1 Hz counters drifting out of
     * phase is exactly the "two periodic flashes overlap" the player hit, and during a countdown
     * nobody is reading how long the lobby has been open anyway.
     */
    public String liveLine() {
        String status;
        if (countingDown()) {
            int seconds = countdownSeconds();
            status = seconds > 0 ? "Starting in " + seconds + "..." : "Starting...";
        } else {
            status = "Waiting " + elapsedText() + ".";
        }
        String link = liveVerdictLine();
        return link.isEmpty() ? status : status + " - " + link;
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
