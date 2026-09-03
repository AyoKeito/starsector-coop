package coop.launcher;

import java.security.SecureRandom;

/**
 * The password the launcher offers a host who has not typed one.
 *
 * <p>A generated default exists because the alternative players actually pick is no password at all,
 * and the session password is what keeps a stranger who guesses the port out of the campaign. It is
 * only a default: the field stays editable, and clearing it is respected for the rest of the run.
 *
 * <p>The alphabet leaves out {@code 0 O o 1 l I}. This string gets read off one screen and typed
 * into another, or read down a voice call, so a glyph pair that is ambiguous in a proportional font
 * costs more than the handful of bits it is worth.
 */
public final class CoopPasswords {

    /** Digits and letters with the six ambiguous glyphs removed. 56 characters. */
    public static final String ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    /** Long enough that 56^10 is not worth guessing, short enough to read out loud. */
    public static final int LENGTH = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CoopPasswords() {
    }

    /** A fresh password. Never logged, only put in the field and carried by the invite. */
    public static String generate() {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
