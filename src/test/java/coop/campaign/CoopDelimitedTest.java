package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The escape codec's contract, and the fast path added for perf audit #18: a value with nothing to
 * escape — which is nearly every id, hull name and faction key that goes down the wire — is returned
 * as-is instead of being copied through a StringBuilder.
 */
class CoopDelimitedTest {

    @Test
    void aValueWithNothingToEscapeIsReturnedUncopied() {
        String plain = "hegemony_patrol_1";
        assertSame(plain, CoopDelimited.field(plain), "clean values must not be rebuilt");
    }

    @Test
    void nullBecomesTheEmptyField() {
        assertEquals("", CoopDelimited.field(null));
    }

    @Test
    void everyEscapableCharacterStillRoundTrips() {
        String messy = "a|b\\c\nd\re";
        String encoded = CoopDelimited.field(messy);

        assertEquals("a\\|b\\\\c\\nd\\re", encoded);
        assertEquals(List.of(messy), CoopDelimited.split(encoded));
    }

    @Test
    void theFastPathDoesNotSwallowAnEscapeAfterACleanPrefix() {
        // The prefix before the first escapable char is copied in one append; a bug there would drop
        // or duplicate it, and the round-trip is what catches that.
        String value = "a long clean prefix|then a pipe";
        assertEquals(List.of(value), CoopDelimited.split(CoopDelimited.field(value)));
    }

    @Test
    void aRecordOfSeveralFieldsSplitsBackIntoTheSameValues() {
        String line = CoopDelimited.field("id-1") + "|" + CoopDelimited.field("na|me")
                + "|" + CoopDelimited.field("");

        assertEquals(List.of("id-1", "na|me", ""), CoopDelimited.split(line));
    }
}
