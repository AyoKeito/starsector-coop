package coop.ui;

import coop.net.CoopConnectionRole;
import coop.util.CoopLog;

import java.util.List;

/**
 * The one line a support helper needs, written into {@code starsector.log} whenever a desync dialog
 * opens (Phase 21, "Support pattern").
 *
 * <p><b>Why one line and not a report.</b> No clipboard write exists in this engine (LWJGL 2 ships
 * {@code Sys.getClipboard} only), so a player cannot copy a block of text out of the game. Every
 * support affordance therefore degrades to "here is the exact file and the exact string to search
 * for" - which only works if what they find is a single line they can select in one drag. A
 * multi-line dump would be truncated by hand at the worst possible place.
 *
 * <p><b>Why {@code sessionId} leads.</b> It is identical on both machines, so a thread with two
 * pastes can be matched up without asking anybody which log is which (the Paradox out-of-sync id
 * insight). Everything after it is the local half of the story.
 *
 * <p>Pure formatting, no engine calls; {@link #log} is the only method that touches anything, and it
 * goes through {@link CoopLog}, which already falls back to a plain log4j logger outside the game.
 */
public final class CoopDoctorMarker {

    /** The literal every marker line starts with, and the thing a player is told to search for. */
    public static final String PREFIX = "[COOP-DOCTOR]";

    /**
     * How much of {@code rawReason} rides on the marker line before it is cut off. A 30-mod handshake
     * diff can be several kilobytes; the full text is already in the adjacent WARN this line sits next
     * to, so the marker only needs enough of it to be recognisable in a search.
     */
    static final int MAX_REASON_CHARS = 300;

    private CoopDoctorMarker() {
    }

    /**
     * The exact string the dialogs tell the player to search the log for.
     *
     * <p>It includes the {@code code=} key rather than a bare code, because that is what the line
     * actually contains - a search string that does not match the line it points at is worse than no
     * search string.
     */
    public static String searchString(CoopDesyncReason reason) {
        return PREFIX + " code=" + (reason == null ? "COOP-SESSION" : reason.code());
    }

    /**
     * Builds the marker.
     *
     * @param reason     the classified reason; null is tolerated and produces an unmapped line
     * @param sessionId  the co-op session id, identical on both sides; blank becomes {@code <none>}
     * @param role       this machine's role
     * @param localName  this player's display name
     * @param remoteName the other player's display name
     * @return one line, no newlines anywhere in it
     */
    public static String format(CoopDesyncReason reason, String sessionId, CoopConnectionRole role,
                                String localName, String remoteName) {
        CoopDesyncReason value = reason == null
                ? CoopDesyncReason.classify(null, CoopDesyncReason.Source.OTHER)
                : reason;
        StringBuilder line = new StringBuilder(256);
        line.append(PREFIX);
        append(line, "code", value.code());
        append(line, "sessionId", orNone(sessionId));
        append(line, "role", role == null ? CoopConnectionRole.NONE.name() : role.name());
        append(line, "source", value.source().name());
        appendQuoted(line, "local", orNone(localName));
        appendQuoted(line, "remote", orNone(remoteName));
        appendQuoted(line, "reason", truncateReason(value.rawReason()));
        switch (value.kind()) {
            case SEED -> appendSeed(line, value);
            case MODS -> appendMods(line, value);
            case SESSION -> appendSession(line, value);
            case UNMAPPED -> append(line, "kind", "unmapped");
        }
        return line.toString();
    }

    /**
     * Formats and writes the marker at WARN. WARN, not INFO: this line is only ever emitted next to a
     * dialog telling a player their session just ended, and it has to be findable in a log that is
     * mostly INFO chatter.
     */
    public static void log(CoopDesyncReason reason, String sessionId, CoopConnectionRole role,
                           String localName, String remoteName) {
        try {
            CoopLog.warn(CoopDoctorMarker.class, format(reason, sessionId, role, localName, remoteName));
        } catch (Throwable ex) {
            // A diagnostic that can take down the frame that is trying to end the session cleanly is
            // worse than no diagnostic. The dialog is the player-facing half and does not need this.
            CoopLog.warn(CoopDoctorMarker.class, "Coop could not write the desync doctor marker", ex);
        }
    }

    private static void appendSeed(StringBuilder line, CoopDesyncReason reason) {
        append(line, "campaignIdMismatch", String.valueOf(reason.campaignIdMismatch()));
        append(line, "hostSeed", orNone(reason.hostSeed()));
        append(line, "guestSeed", orNone(reason.guestSeed()));
        append(line, "hostFingerprint", orNone(reason.hostFingerprint()));
        append(line, "guestFingerprint", orNone(reason.guestFingerprint()));
        append(line, "hostCampaignId", orNone(reason.hostCampaignId()));
        append(line, "guestCampaignId", orNone(reason.guestCampaignId()));
    }

    private static void appendMods(StringBuilder line, CoopDesyncReason reason) {
        List<CoopDesyncReason.ModRow> rows = reason.modRows();
        append(line, "modsShown", String.valueOf(rows.size()));
        append(line, "modsHidden", String.valueOf(reason.hiddenModRows()));
        append(line, "gameVersion", reason.gameVersionMismatch()
                ? reason.hostGameVersion() + ">" + reason.guestGameVersion() : "match");
        append(line, "coopBuild", reason.coopBuildMismatch()
                ? reason.hostCoopBuild() + ">" + reason.guestCoopBuild() : "match");
        append(line, "ironMode", orNone(reason.ironModeSide()));
        append(line, "manifestUnreadable", String.valueOf(reason.manifestUnreadable()));
        StringBuilder mods = new StringBuilder();
        for (CoopDesyncReason.ModRow row : rows) {
            if (mods.length() > 0) {
                mods.append(';');
            }
            mods.append(row.modId()).append('=').append(row.verdict().name())
                    .append(':').append(orNone(row.hostVersion()))
                    .append('>').append(orNone(row.guestVersion()));
        }
        appendQuoted(line, "mods", mods.length() == 0 ? "<none>" : mods.toString());
    }

    private static void appendSession(StringBuilder line, CoopDesyncReason reason) {
        append(line, "cause", reason.sessionCause() == null ? "OTHER" : reason.sessionCause().name());
        append(line, "graceSeconds", String.valueOf(reason.graceSeconds()));
        append(line, "retryable", String.valueOf(reason.retryable()));
    }

    private static void append(StringBuilder line, String key, String value) {
        line.append(' ').append(key).append('=').append(escape(value));
    }

    private static void appendQuoted(StringBuilder line, String key, String value) {
        line.append(' ').append(key).append("=\"").append(escape(value)).append('"');
    }

    private static String orNone(String value) {
        return value == null || value.trim().isEmpty() ? "<none>" : value.trim();
    }

    /**
     * Cuts {@code rawReason} down to {@link #MAX_REASON_CHARS}, with a count of what was dropped. The
     * full text already went out at WARN right next to this line, so nothing here is lost, only
     * shortened to what a support helper needs to recognise and search for.
     */
    private static String truncateReason(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= MAX_REASON_CHARS) {
            return text;
        }
        int more = text.length() - MAX_REASON_CHARS;
        return text.substring(0, MAX_REASON_CHARS) + " +" + more + " more chars";
    }

    /**
     * Makes any value safe to sit on one line inside quotes. Backslash first, or the escapes we add
     * afterwards would be escaped again on a re-read. Every other C0 control character (form feed,
     * backspace, a stray NUL from a mangled stack trace, and so on) is escaped too, as {@code \\uXXXX} -
     * one of these left raw is exactly as capable of breaking a single-line paste as an unescaped
     * newline, it is just rarer.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
