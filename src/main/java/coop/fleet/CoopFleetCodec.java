package coop.fleet;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared compact text encoding for the campaign fleet DTOs ({@link CoopFleetSnapshot} for the player
 * mirror, {@link CoopNpcFleetSnapshot} for replicated NPC fleets). Both stream over UDP/TCP bodies
 * that are framed independently of the flat-JSON {@link coop.net.CoopMessages} envelope (which cannot
 * carry arrays), so they share this delimiter-based, backslash-escaped codec instead.
 *
 * <p>The encoding is pipe-delimited per record with {@code \\}, {@code \|}, {@code \n}, {@code \r}
 * escapes so member names containing those characters round-trip exactly.
 */
final class CoopFleetCodec {
    static final char FIELD_SEPARATOR = '|';
    static final int MEMBER_FIELD_COUNT = 7;

    private CoopFleetCodec() {
    }

    /** Escapes a single field so the field/record separators survive a round-trip. */
    static String escape(String value) {
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

    /** Splits one record line into its pipe-delimited fields, reversing {@link #escape(String)}. */
    static List<String> split(String line) {
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

    /**
     * Reverses {@link #escape(String)} for a whole field (no separator splitting). Used to pack a
     * multi-line per-fleet encoding onto a single line inside {@link CoopNpcFleetSetSnapshot}.
     */
    static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                switch (c) {
                    case '\\' -> out.append('\\');
                    case '|' -> out.append('|');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    default -> out.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Appends one ship member as a pipe-delimited record (no leading/trailing separator). */
    static void appendMember(StringBuilder out, CoopFleetSnapshot.Member member) {
        out.append(escape(member.fleetMemberId()))
                .append(FIELD_SEPARATOR).append(escape(member.hullId()))
                .append(FIELD_SEPARATOR).append(escape(member.variantId()))
                .append(FIELD_SEPARATOR).append(escape(member.shipName()))
                .append(FIELD_SEPARATOR).append(escape(member.captainName()))
                .append(FIELD_SEPARATOR).append(Float.toString(member.cr()))
                .append(FIELD_SEPARATOR).append(Float.toString(member.hullFraction()));
    }

    /** Parses the 7 fields of one member record produced by {@link #appendMember}. */
    static CoopFleetSnapshot.Member parseMember(List<String> fields) {
        if (fields.size() != MEMBER_FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + MEMBER_FIELD_COUNT
                    + " member fields, got " + fields.size());
        }
        return new CoopFleetSnapshot.Member(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                fields.get(4), Float.parseFloat(fields.get(5)), Float.parseFloat(fields.get(6)));
    }
}
