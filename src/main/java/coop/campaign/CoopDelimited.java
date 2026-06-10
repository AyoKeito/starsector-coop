package coop.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared compact delimited text encoding for the Phase 12 list-bearing payloads
 * ({@link CoopMissionBoardSync} pools, {@link CoopMarketSync} snapshots).
 *
 * <p>The reliable TCP {@link coop.net.CoopMessages} envelope parser is flat (no JSON arrays), so any
 * payload carrying a list of records ships its own self-contained text encoding inside a single
 * string field, mirroring {@link coop.fleet.CoopFleetSnapshot}. Records are newline separated and
 * fields are {@code |} separated, with backslash escaping so values containing {@code |}, newlines,
 * or backslashes round-trip exactly.
 */
public final class CoopDelimited {
    private CoopDelimited() {
    }

    /** Escapes a single field value so it round-trips through {@link #split(String)}. */
    public static String field(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\|");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** Splits one {@code |}-separated, backslash-escaped record line back into its fields. */
    public static List<String> split(String line) {
        Objects.requireNonNull(line, "line");
        List<String> fields = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                switch (c) {
                    case '\\' -> token.append('\\');
                    case '|' -> token.append('|');
                    case 'n' -> token.append('\n');
                    case 'r' -> token.append('\r');
                    default -> token.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                fields.add(token.toString());
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        fields.add(token.toString());
        return fields;
    }

    public static String normalize(String value) {
        return value == null ? "" : value;
    }
}
