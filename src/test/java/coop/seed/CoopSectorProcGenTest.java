package coop.seed;

import com.fs.starfarer.api.characters.CharacterCreationData;
import coop.net.CoopNetStartupConfig;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSectorProcGenTest {
    private String previousProperty;

    @BeforeEach
    void capturePreviousProperty() {
        previousProperty = System.getProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
        System.clearProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
    }

    @AfterEach
    void restorePreviousProperty() {
        if (previousProperty == null) {
            System.clearProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY);
        } else {
            System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, previousProperty);
        }
    }

    @Test
    void appliesSeedToCharacterCreationDataWhenPropertyIsSet() {
        System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, "MN-1234567890123456789");
        Map<String, Object> calls = new HashMap<>();
        CharacterCreationData data = recordingCharacterCreationData(calls);

        CoopSectorProcGen.applyCoopSeedIfPresent(data);

        assertEquals("MN-1234567890123456789", calls.get("seedString"));
        long expectedSeedLong = CoopSeedSync.seedDataFromSeedString("MN-1234567890123456789").seedLong();
        assertEquals(expectedSeedLong, calls.get("seed"));
    }

    @Test
    void doesNothingWhenPropertyIsUnset() {
        Map<String, Object> calls = new HashMap<>();
        CharacterCreationData data = recordingCharacterCreationData(calls);

        CoopSectorProcGen.applyCoopSeedIfPresent(data);

        assertFalse(calls.containsKey("seed"));
        assertFalse(calls.containsKey("seedString"));
    }

    @Test
    void doesNothingWhenPropertyIsBlank() {
        System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, "   ");
        Map<String, Object> calls = new HashMap<>();
        CharacterCreationData data = recordingCharacterCreationData(calls);

        CoopSectorProcGen.applyCoopSeedIfPresent(data);

        assertFalse(calls.containsKey("seed"));
        assertFalse(calls.containsKey("seedString"));
    }

    /**
     * config-plugin-3: the New Game dialog re-pins the panel on every frame, and this line went out
     * with it - thousands of identical INFO lines while a player picked a portrait.
     */
    @Test
    void theQuietOverloadStillAppliesTheSeedButDoesNotLogIt() {
        System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, "MN-1234567890123456789");
        Map<String, Object> calls = new HashMap<>();
        CharacterCreationData data = recordingCharacterCreationData(calls);
        CapturingAppender appender = new CapturingAppender();
        Logger logger = Logger.getLogger(CoopSectorProcGen.class);
        logger.addAppender(appender);
        try {
            CoopSectorProcGen.applyCoopSeedIfPresent(data, false);

            assertEquals("MN-1234567890123456789", calls.get("seedString"),
                    "quiet means quiet, not inert: the panel can write its own seed back any frame");
            assertTrue(appender.messages.isEmpty(), appender.messages.toString());

            CoopSectorProcGen.applyCoopSeedIfPresent(data, true);

            assertEquals(1, appender.messages.size(), "the once-per-new-game callers still say so");
        } finally {
            logger.removeAppender(appender);
        }
    }

    /** Minimal log4j sink; CoopLog falls back to plain log4j when there is no game. */
    private static final class CapturingAppender extends AppenderSkeleton {
        private final List<String> messages = new ArrayList<>();

        @Override
        protected void append(LoggingEvent event) {
            messages.add(String.valueOf(event.getMessage()));
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }

    @Test
    void tolerantOfNullCharacterCreationData() {
        System.setProperty(CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY, "MN-1234567890123456789");

        CoopSectorProcGen.applyCoopSeedIfPresent(null);
    }

    @Test
    void settingsJsonRegistersCoopSectorProcGen() throws Exception {
        String settingsJson = Files.readString(Path.of("data", "config", "settings.json"));

        assertTrue(settingsJson.contains("\"newGameSectorProcGen\":\"coop.seed.CoopSectorProcGen\""));
    }

    @Test
    void settingsJsonNoLongerOverridesCampaignSpeedupMult() throws Exception {
        // Phase 7b: the static 1x override is gone — the multiplier is now forced at runtime
        // (2x for a coop session, 1x only when CoopFastForwardLock's handles are unavailable), so a
        // solo game with the mod enabled gets vanilla fast-forward back.
        String settingsJson = Files.readString(Path.of("data", "config", "settings.json"));

        assertFalse(settingsJson.contains("campaignSpeedupMult"));
    }

    private static CharacterCreationData recordingCharacterCreationData(Map<String, Object> calls) {
        return (CharacterCreationData) Proxy.newProxyInstance(
                CharacterCreationData.class.getClassLoader(),
                new Class<?>[]{CharacterCreationData.class},
                (proxy, method, args) -> {
                    if ("setSeed".equals(method.getName())) {
                        calls.put("seed", args[0]);
                        return null;
                    }
                    if ("setSeedString".equals(method.getName())) {
                        calls.put("seedString", args[0]);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }
}
