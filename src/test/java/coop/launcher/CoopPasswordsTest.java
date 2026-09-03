package coop.launcher;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopPasswordsTest {

    /** The glyph pairs a player would misread off a screen or down a voice call. */
    private static final String AMBIGUOUS = "0Oo1lI";

    @Test
    void theAlphabetLeavesOutEveryAmbiguousGlyph() {
        for (char ambiguous : AMBIGUOUS.toCharArray()) {
            assertFalse(CoopPasswords.ALPHABET.indexOf(ambiguous) >= 0,
                    "the alphabet still holds " + ambiguous);
        }
        assertEquals(56, CoopPasswords.ALPHABET.length());
    }

    @Test
    void theAlphabetHasNoRepeats() {
        Set<Character> seen = new HashSet<>();
        for (char c : CoopPasswords.ALPHABET.toCharArray()) {
            assertTrue(seen.add(c), "the alphabet repeats " + c + ", which skews the draw");
        }
    }

    @Test
    void everyGeneratedPasswordIsTenCharactersFromThatAlphabet() {
        for (int run = 0; run < 200; run++) {
            String password = CoopPasswords.generate();
            assertEquals(CoopPasswords.LENGTH, password.length(), password);
            for (char c : password.toCharArray()) {
                assertTrue(CoopPasswords.ALPHABET.indexOf(c) >= 0,
                        "generated " + c + ", which is not in the alphabet");
            }
        }
    }

    @Test
    void twoPasswordsInARowAreNotTheSame() {
        Set<String> seen = new HashSet<>();
        for (int run = 0; run < 100; run++) {
            assertTrue(seen.add(CoopPasswords.generate()), "the generator repeated itself");
        }
    }
}
