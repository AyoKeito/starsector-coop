package coop.mark;

import java.util.Locale;

/**
 * The one place the {@code COOP-MARK} log lines are spelled.
 *
 * <p>A marker exists to be grepped out of two logs and lined up by hand, which makes the exact
 * characters the feature: {@code rg COOP-MARK} in either player's {@code starsector.log} has to find
 * every marker both players pressed, in the same shape, whoever pressed it. So the prefix is a
 * constant, the field order is fixed, and both the presser and the receiver render an identical line
 * from the same fields - the receiver renders the <em>sender's</em> numbers, not its own.
 *
 * <p>Pure: no engine, no clock, no network. The caller gathers the four facts and this turns them
 * into characters, which is what makes the shape testable without a game.
 */
public final class CoopMarkFormat {

    /** The grep handle. Never change it without changing every runbook that greps for it. */
    public static final String PREFIX = "COOP-MARK";

    /** Role half of a marker id; the other half is a per-role counter starting at 1. */
    public static final String ROLE_HOST = "host";
    public static final String ROLE_GUEST = "guest";

    /**
     * Longest note carried on the wire and into the log. A marker note is "what looked wrong",
     * typed while the world is paused behind a dialog; past a line's worth it is a bug report, and
     * the bug report belongs in the launcher's zip. Truncated rather than refused - losing the tail
     * of a sentence beats losing the marker.
     */
    public static final int MAX_NOTE_CHARS = 120;

    private CoopMarkFormat() {
    }

    /** {@code host#3} / {@code guest#1}: role first so the two players' counters cannot collide. */
    public static String markerId(String role, int ordinal) {
        String safe = ROLE_GUEST.equals(role) ? ROLE_GUEST : ROLE_HOST;
        return safe + "#" + Math.max(1, ordinal);
    }

    /**
     * The marker line, written by the presser the instant the key goes down and by the partner the
     * instant the message lands.
     *
     * <p>{@code COOP-MARK host#3 | day 74418.38 | Corvus (hyperspace) | seq 48120 | ""}
     *
     * @param markerId marker id from {@link #markerId}
     * @param day      campaign day count (cycle times 360 plus the day into the cycle, hours as a
     *                 fraction), two decimals; the same number on both clocks modulo whatever drift
     *                 the reconciler has not taken out yet, which is the point
     * @param location containing location of the presser's fleet, already suffixed by
     *                 {@link #location}
     * @param seq      the <em>sender's</em> outbound envelope sequence, so a marker can be found in
     *                 the {@code Coop net ... seq=} stream on either side
     * @param note     the typed note, or {@code ""} for the immediate line
     */
    public static String line(String markerId, float day, String location, long seq, String note) {
        return PREFIX + " " + markerId
                + " | day " + day(day)
                + " | " + (location == null || location.isBlank() ? "unknown" : location.trim())
                + " | seq " + seq
                + " | \"" + note(note) + "\"";
    }

    /**
     * The follow-up line, written on both sides when the presser types something and presses OK.
     *
     * <p>{@code COOP-MARK host#3 note | "the text"}
     *
     * <p>A second line rather than a rewrite of the first: the first one is already in the file by
     * the time the dialog opens, and the pair is what tells a reader the note was typed after the
     * press rather than being part of it.
     */
    public static String noteLine(String markerId, String note) {
        return PREFIX + " " + markerId + " note | \"" + note(note) + "\"";
    }

    /** Two decimals, always, in {@link Locale#ROOT} - a comma here would break the grep. */
    public static String day(float day) {
        return String.format(Locale.ROOT, "%.2f", day);
    }

    /**
     * The location field: the containing location's name, with {@code (hyperspace)} appended when
     * the fleet is in hyperspace. A blank name reads as {@code unknown} rather than as an empty
     * field, because an empty field in the middle of a pipe-separated line looks like a bug in the
     * formatter rather than a fact about the world.
     */
    public static String location(String name, boolean hyperspace) {
        String base = name == null || name.isBlank() ? "unknown" : name.trim();
        return hyperspace ? base + " (hyperspace)" : base;
    }

    /**
     * One line, no quotes, bounded, printable ASCII. Quotes become apostrophes because the note is
     * written inside quotes and a nested one would make the line ambiguous to read; newlines and
     * tabs become spaces because a marker is one line by definition. Anything outside printable
     * ASCII is written as {@code \\uXXXX}: the log is written in the machine's default code page,
     * so Cyrillic or CJK typed into the box would otherwise reach the file mangled on a Windows
     * whose code page is not UTF-8, and the two players' files must carry identical characters.
     */
    public static String note(String note) {
        if (note == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(note.length(), MAX_NOTE_CHARS));
        for (int i = 0; i < note.length() && out.length() < MAX_NOTE_CHARS; i++) {
            char c = note.charAt(i);
            if (c == '"') {
                out.append('\'');
            } else if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c < 0x20) {
                // Control characters would be invisible in the log and could confuse a terminal.
                out.append(' ');
            } else if (c > 0x7e) {
                if (out.length() + 6 > MAX_NOTE_CHARS) {
                    break;
                }
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    /** The HUD confirmation the presser sees: {@code marked host#3}. */
    public static String ownNotice(String markerId, String note) {
        String text = note(note);
        return text.isEmpty() ? "marked " + markerId : "marked " + markerId + ": " + text;
    }

    /** The HUD line the receiver sees: {@code partner marked host#3}. */
    public static String partnerNotice(String markerId, String note) {
        String text = note(note);
        return text.isEmpty() ? "partner marked " + markerId : "partner marked " + markerId + ": " + text;
    }
}
