package coop.launcher;

import coop.net.CoopNetStartupConfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSeedsTest {

    @Test
    void everyGeneratedSeedSatisfiesTheGamesOwnRule() {
        for (int i = 0; i < 2000; i++) {
            String seed = CoopSeeds.generate();
            assertTrue(seed.startsWith(CoopSeeds.PREFIX), seed);
            assertNull(CoopNetStartupConfig.validateNewGameSeed(seed), seed);
            long value = Long.parseLong(seed.substring(CoopSeeds.PREFIX.length()));
            assertTrue(value >= 0, seed);
        }
    }

    @Test
    void twoGeneratedSeedsDiffer() {
        assertTrue(!CoopSeeds.generate().equals(CoopSeeds.generate()));
    }

    /**
     * The launcher must never accept a seed the game would reject, or reject one it would accept.
     * Pinned by delegation rather than by a copied regex.
     */
    @Test
    void theLauncherValidatorIsTheGamesValidator() {
        String[] samples = {
                "",
                "   ",
                "MN-0",
                "MN-1234567890123456789",
                "MN-9223372036854775807",
                "MN-9223372036854775808",
                "MN-9999999999999999999",
                "MN-",
                "MN--1",
                "mn-42",
                "42",
                "MN-4 2",
                "MN-12a"};
        for (String sample : samples) {
            String expected = CoopNetStartupConfig.validateNewGameSeed(sample.trim());
            assertEquals(expected, CoopSeeds.validate(sample), "seed: \"" + sample + "\"");
        }
    }

    @Test
    void anOverflowingSeedIsRejectedWithTheReasonSpelledOut() {
        String reason = CoopSeeds.validate("MN-9999999999999999999");

        assertNotNull(reason);
        assertTrue(reason.contains("64-bit"), reason);
    }
}
