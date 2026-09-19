package coop.fleet;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared compact text encoding for the campaign fleet DTOs ({@link CoopFleetSnapshot} for the player
 * mirror, {@link CoopNpcFleetSnapshot} for replicated NPC fleets). Both stream over UDP/TCP bodies
 * that are framed independently of the flat-JSON {@link coop.net.CoopMessages} envelope (which cannot
 * carry arrays), so they share this delimiter-based, backslash-escaped codec instead.
 *
 * <p>The encoding is pipe-delimited per record with {@code \\}, {@code \|}, {@code \n}, {@code \r},
 * and {@code \s} (for the U+001F unit separator, which the datagram envelope uses as its record
 * separator) escapes, so member names containing any of those characters round-trip exactly.
 *
 * <p>The escape letter for U+001F is {@code s}, not {@code u}: the sequence backslash-u is a unicode
 * escape to the Java lexer even inside comments and escaped string literals, so it cannot appear in
 * source at all.
 */
final class CoopFleetCodec {
    static final char FIELD_SEPARATOR = '|';
    /**
     * Bumped 7 -&gt; 10 by Phase 16 for the replicated permanent hullmods ({@code dmodIds},
     * {@code sModIds}, {@code sModdedBuiltInIds}), 10 -&gt; 11 on 2026-09-14 (S4-B) for
     * {@code mothballed}, and 11 -&gt; 14 by Phase 33 for the officer ({@code captainLevel},
     * {@code captainPersonality}, {@code captainSkills}); see {@link CoopFleetSnapshot.Member}.
     */
    static final int MEMBER_FIELD_COUNT = 14;

    /**
     * The member record shape before {@code mothballed} (2026-09-14, S4-B). Both peers always run the
     * same build — the handshake's {@code COOP-GAME} version check refuses anything else — so nothing
     * in a live session can emit one of these. The decoder accepts it anyway because the parser is
     * also what reads the wiretap fixtures and the recorded bodies in {@code tmp_ff_analysis}, and
     * because "one trailing field is missing" has exactly one sane reading (the flag was not sent, so
     * it is false) while a hard throw drops the whole roster the member is in.
     */
    static final int MEMBER_FIELD_COUNT_PRE_MOTHBALL = 10;

    /**
     * The member record shape before Phase 33's officer fields, tolerated for the same reason as
     * {@link #MEMBER_FIELD_COUNT_PRE_MOTHBALL}: the missing trailing fields read as "this ship has no
     * replicated officer", which is what every roster recorded before 0.1.4 meant.
     */
    static final int MEMBER_FIELD_COUNT_PRE_OFFICERS = 11;
    /** U+001F UNIT SEPARATOR: the datagram envelope's record separator, and player-typeable. */
    static final char UNIT_SEPARATOR = (char) 0x1F;

    /**
     * Position and velocity quantum, in su. Also an exact binary fraction, so the snapped value always
     * prints as a short terminating decimal. A quarter of a su is far below the smallest offset
     * visible on the campaign map (fleet icons are tens of su across) and the receiving mirror
     * interpolates through these samples anyway; what it buys is {@code -14625.25} in place of
     * {@code -14625.389}, per coordinate, ten times a second, per fleet.
     */
    static final double POSITION_STEP = 0.25;
    /** CR and hull fraction: a tenth of a percent, finer than any bar the UI draws. */
    static final double FRACTION_STEP = 0.001;
    /** Sensor profile, flat/percent detected-range terms and sensor strength; engine units, hundreds. */
    static final double SENSOR_STEP = 0.1;
    /** The detected-range mult is a multiplier around 1, so it needs the finer grid. */
    static final double SENSOR_MULT_STEP = 0.001;

    private CoopFleetCodec() {
    }

    /**
     * Snaps {@code value} to the nearest multiple of {@code step}. Wire-format only: it runs at the
     * serialization boundary so the emitted decimal strings are short, never on a value that feeds a
     * hash, a checksum or any gameplay math.
     *
     * <p>{@code step} is a {@code double} on purpose. The grid that matters is the <em>decimal</em>
     * one — the result has to be the float nearest {@code n * 0.001}, because that is the float whose
     * shortest round-trip form is {@code "0.85"}. Rounding onto multiples of the float {@code 0.001f}
     * instead lands a whole ulp away for many n (a CR of {@code 0.7f} came back {@code 0.70000005f}),
     * which is both a changed value and the long string this exists to avoid.
     *
     * <p>Values whose own ulp already exceeds the step are returned untouched — there is no finer grid
     * to snap to up there — and that guard is also what keeps {@code value / step} inside {@code long}
     * for every value that reaches the divide. NaN and the infinities pass through ahead of it.
     */
    static float quantize(float value, double step) {
        if (!Float.isFinite(value) || !(step > 0.0) || Math.ulp(value) >= step) {
            return value;
        }
        return (float) (Math.round((double) value / step) * step);
    }

    /** {@link #quantize} plus the {@link Float#toString} the encoders would have called directly. */
    static String encodeFloat(float value, double step) {
        return Float.toString(quantize(value, step));
    }

    /**
     * {@link #encodeFloat} for a field whose zero is a <em>sentinel</em> rather than a small number: a
     * strictly positive input is never allowed to encode as {@code 0}, it floors at one step instead.
     *
     * <p>Rounding to nearest is the right rule for a magnitude and the wrong rule for a flag. A sensor
     * value in {@code (0, 0.05]} would snap to exactly zero on the 0.1 grid, and zero is what
     * {@link CoopSensorSync.Profile#isKnown()} reads as "this peer never captured a sensor identity" —
     * whereupon the receiver leaves the mirror's profile unset and the engine's {@code !hasSensorProfile()}
     * branch treats it as <b>always identified</b>. That inverts the whole subsystem, so the encoder
     * refuses to synthesize the sentinel out of a real reading. One step of overstatement on a value
     * that small is beneath measurement; the sentinel is not.
     */
    static String encodePositiveFloat(float value, double step) {
        float snapped = quantize(value, step);
        if (value > 0f && !(snapped > 0f)) {
            return Float.toString((float) step);
        }
        return Float.toString(snapped);
    }

    /**
     * The one float parser every wire decoder in this package uses (red-team A10).
     *
     * <p>{@link Float#parseFloat} accepts {@code NaN}, {@code Infinity} and {@code -Infinity}, and
     * nothing downstream of the decoders re-checks: a NaN position sets a mirror's coordinates to
     * NaN, which propagates through the engine's own distance and camera math for the rest of the
     * session, and a NaN CR or sensor mult poisons the corresponding {@code StatBonus}. None of
     * these values can be produced by the encoders, so rejecting them costs nothing and drops the
     * whole malformed section instead of the frame that reads it.
     */
    static float parseFiniteFloat(String text) {
        float value = Float.parseFloat(text);
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite float on the wire: " + text);
        }
        return value;
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
                // U+001F is the datagram envelope's record separator and ship/fleet names are
                // player-editable, so an unescaped one would split a record mid-field on the wire.
                case UNIT_SEPARATOR -> escaped.append("\\s");
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
                    case 's' -> token.append(UNIT_SEPARATOR);
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
                    // Must mirror escape()'s full alphabet, including the U+001F case, or this is not
                    // the inverse of escape() and a field could survive one round trip but not two.
                    case 's' -> out.append(UNIT_SEPARATOR);
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

    /**
     * Appends one ship member as a pipe-delimited record (no leading/trailing separator). CR and hull
     * fraction are quantized on the way out; the {@code fleetHash} carried alongside them is computed
     * from the unquantized member and is deliberately not a function of either field anyway.
     */
    static void appendMember(StringBuilder out, CoopFleetSnapshot.Member member) {
        out.append(escape(member.fleetMemberId()))
                .append(FIELD_SEPARATOR).append(escape(member.hullId()))
                .append(FIELD_SEPARATOR).append(escape(member.variantId()))
                .append(FIELD_SEPARATOR).append(escape(member.shipName()))
                .append(FIELD_SEPARATOR).append(escape(member.captainName()))
                .append(FIELD_SEPARATOR).append(encodeFloat(member.cr(), FRACTION_STEP))
                .append(FIELD_SEPARATOR).append(encodeFloat(member.hullFraction(), FRACTION_STEP))
                .append(FIELD_SEPARATOR).append(escape(member.dmodIds()))
                .append(FIELD_SEPARATOR).append(escape(member.sModIds()))
                .append(FIELD_SEPARATOR).append(escape(member.sModdedBuiltInIds()))
                .append(FIELD_SEPARATOR).append(member.mothballed() ? '1' : '0')
                .append(FIELD_SEPARATOR).append(Integer.toString(member.captainLevel()))
                .append(FIELD_SEPARATOR).append(escape(member.captainPersonality()))
                .append(FIELD_SEPARATOR).append(escape(member.captainSkills()));
    }

    /**
     * Parses the {@value #MEMBER_FIELD_COUNT} fields of one member record produced by
     * {@link #appendMember}, tolerating the {@value #MEMBER_FIELD_COUNT_PRE_MOTHBALL}-field shape that
     * predates {@code mothballed} (see {@link #MEMBER_FIELD_COUNT_PRE_MOTHBALL}). Any other count is
     * still a hard error: a record that is neither shape has been corrupted or truncated somewhere
     * this codec cannot reason about, and guessing which fields survived would silently build the
     * wrong ship.
     */
    static CoopFleetSnapshot.Member parseMember(List<String> fields) {
        if (fields.size() != MEMBER_FIELD_COUNT && fields.size() != MEMBER_FIELD_COUNT_PRE_MOTHBALL
                && fields.size() != MEMBER_FIELD_COUNT_PRE_OFFICERS) {
            throw new IllegalArgumentException("Expected " + MEMBER_FIELD_COUNT
                    + " member fields, got " + fields.size());
        }
        boolean mothballed = fields.size() > MEMBER_FIELD_COUNT_PRE_MOTHBALL
                && "1".equals(fields.get(MEMBER_FIELD_COUNT_PRE_MOTHBALL));
        boolean officers = fields.size() == MEMBER_FIELD_COUNT;
        return new CoopFleetSnapshot.Member(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                fields.get(4), parseFiniteFloat(fields.get(5)), parseFiniteFloat(fields.get(6)),
                fields.get(7), fields.get(8), fields.get(9), mothballed,
                officers ? parseOfficerLevel(fields.get(11)) : 0,
                officers ? fields.get(12) : "",
                officers ? fields.get(13) : "");
    }

    /**
     * An officer level off the wire. Unparsable reads as "no officer level", not as a throw: the rest
     * of the ship is intact and a roster is not worth dropping over one number the mirror only uses
     * to seat a {@code PersonAPI}'s stats.
     */
    private static int parseOfficerLevel(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
