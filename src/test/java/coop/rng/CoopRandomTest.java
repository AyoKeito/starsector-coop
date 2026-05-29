package coop.rng;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopRandomTest {
    private String previousSeed;

    @BeforeEach
    void capturePreviousSeed() {
        previousSeed = System.getProperty(CoopRandom.NEW_GAME_SEED_PROPERTY);
        System.clearProperty(CoopRandom.NEW_GAME_SEED_PROPERTY);
    }

    @AfterEach
    void restorePreviousSeed() {
        if (previousSeed == null) {
            System.clearProperty(CoopRandom.NEW_GAME_SEED_PROPERTY);
        } else {
            System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, previousSeed);
        }
    }

    @Test
    void reportsNoCoopSessionWhenSeedPropertyIsAbsentOrBlank() {
        assertFalse(CoopRandom.isCoopSession());
        assertEquals("", CoopRandom.sessionSeedString());
        assertEquals("<none>", CoopRandom.sessionSeedStringOrNone());

        System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, "   ");

        assertFalse(CoopRandom.isCoopSession());
        assertEquals("", CoopRandom.sessionSeedString());
        assertThrows(IllegalStateException.class, CoopRandom::sessionSeedLong);
        assertThrows(IllegalStateException.class, () -> CoopRandom.of("topic"));
    }

    @Test
    void trimsSessionSeedAndDerivesPositiveSeedLong() {
        System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, "  MN-1234567890123456789  ");

        assertTrue(CoopRandom.isCoopSession());
        assertEquals("MN-1234567890123456789", CoopRandom.sessionSeedString());
        assertEquals("MN-1234567890123456789", CoopRandom.sessionSeedStringOrNone());
        assertTrue(CoopRandom.sessionSeedLong() > 0L);
    }

    @Test
    void sameSeedTopicAndKeysProduceSameRandomSequence() {
        System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, "MN-1234567890123456789");

        Random first = CoopRandom.of("GateHaulerLocation", "alpha", 12);
        Random second = CoopRandom.of("GateHaulerLocation", "alpha", 12);

        for (int i = 0; i < 8; i++) {
            assertEquals(first.nextLong(), second.nextLong());
        }
    }

    @Test
    void differentTopicsOrKeysProduceDifferentRandomSequences() {
        System.setProperty(CoopRandom.NEW_GAME_SEED_PROPERTY, "MN-1234567890123456789");

        long base = CoopRandom.of("GateHaulerLocation", "alpha", 12).nextLong();

        assertNotEquals(base, CoopRandom.of("NamelessRock", "alpha", 12).nextLong());
        assertNotEquals(base, CoopRandom.of("GateHaulerLocation", "beta", 12).nextLong());
    }
}
