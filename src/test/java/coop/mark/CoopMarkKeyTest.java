package coop.mark;

import coop.testing.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key-name resolution, against a stand-in for {@code Keyboard.getKeyIndex}. The real LWJGL keyboard
 * is deliberately not exercised: what is worth pinning is that a typo in a settings file produces a
 * warning and a working key rather than a hotkey that silently does nothing, and that is a property
 * of the rules here, not of LWJGL's table.
 */
class CoopMarkKeyTest {

    /** A few real LWJGL codes, plus KEY_NONE for everything else, which is what LWJGL answers. */
    private static final Map<String, Integer> KEYS = Map.of(
            "F11", 0x57, "F9", 0x43, "HOME", 0xC7, "INSERT", 0xD2);

    private static final ToIntFunction<String> LOOKUP =
            name -> KEYS.getOrDefault(name, CoopMarkKey.KEY_NONE);

    @Test
    void aKnownNameResolvesToItsCode() {
        CoopMarkKey key = CoopMarkKey.resolve("F9", LOOKUP);

        assertEquals("F9", key.name());
        assertEquals(0x43, key.code());
        assertFalse(key.fellBack());
    }

    @Test
    void namesAreCaseInsensitiveAndTrimmed() {
        assertEquals(0xC7, CoopMarkKey.resolve("  home  ", LOOKUP).code());
        assertEquals("HOME", CoopMarkKey.resolve("Home", LOOKUP).name());
    }

    @Test
    void blankMeansTheDefaultAndIsNotAFallback() {
        for (String blank : List.of("", "   ")) {
            CoopMarkKey key = CoopMarkKey.resolve(blank, LOOKUP);
            assertEquals(CoopMarkKey.DEFAULT_NAME, key.name());
            assertEquals(0x57, key.code());
            assertFalse(key.fellBack(), "an unset key is not a misconfigured one");
        }
    }

    @Test
    void anUnknownNameFallsBackToF11AndSaysSoInTheLog() {
        LogCapture log = LogCapture.attach(CoopMarkKey.class);
        try {
            CoopMarkKey key = CoopMarkKey.resolve("F13ish", LOOKUP);

            assertEquals(CoopMarkKey.DEFAULT_NAME, key.name());
            assertEquals(0x57, key.code());
            assertTrue(key.fellBack());
            assertEquals(1, log.matching("is not an LWJGL key name").size(),
                    log.warnings().toString());
        } finally {
            log.detach();
        }
    }

    @Test
    void aLookupThatThrowsIsTreatedAsAnUnknownName() {
        LogCapture log = LogCapture.attach(CoopMarkKey.class);
        try {
            CoopMarkKey key = CoopMarkKey.resolve("F9", name -> {
                throw new IllegalStateException("no keyboard");
            });

            assertEquals(CoopMarkKey.DEFAULT_NAME, key.name());
            assertEquals(CoopMarkKey.KEY_NONE, key.code(),
                    "with no keyboard there is no code either, and the listener then watches nothing");
            assertTrue(key.fellBack());
            assertEquals(1, log.matching("is not an LWJGL key name").size(),
                    log.warnings().toString());
        } finally {
            log.detach();
        }
    }
}
