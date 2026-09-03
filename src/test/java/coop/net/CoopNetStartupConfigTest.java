package coop.net;

import coop.config.CoopOptionsStore;
import coop.ui.CoopHudCorner;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNetStartupConfigTest {
    @Test
    void parsesHostPortProperty() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "7777");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertTrue(config.isPresent());
        assertEquals(CoopConnectionRole.HOST, config.role());
        assertEquals(7777, config.port());
        assertEquals("", config.host());
    }

    @Test
    void parsesGuestConnectProperties() {
        Properties properties = new Properties();
        properties.setProperty("coop.connectHost", "127.0.0.1");
        properties.setProperty("coop.connectPort", "7777");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertTrue(config.isPresent());
        assertEquals(CoopConnectionRole.GUEST, config.role());
        assertEquals("127.0.0.1", config.host());
        assertEquals(7777, config.port());
    }

    @Test
    void emptyPropertiesReturnNoStartupConfig() {
        CoopNetStartupConfig config = CoopNetStartupConfig.from(new Properties());

        assertFalse(config.isPresent());
        assertEquals(CoopConnectionRole.NONE, config.role());
    }

    @Test
    void rejectsMixedHostAndGuestProperties() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "7777");
        properties.setProperty("coop.connectHost", "127.0.0.1");
        properties.setProperty("coop.connectPort", "7777");

        assertThrows(IllegalArgumentException.class, () -> CoopNetStartupConfig.from(properties));
    }

    @Test
    void rejectsOutOfRangePort() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "70000");

        assertThrows(IllegalArgumentException.class, () -> CoopNetStartupConfig.from(properties));
    }

    @Test
    void parsesNewGameSeedAlongsideHostProperty() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "7777");
        properties.setProperty("coop.newGameSeed", "  MN-shared-test-seed  ");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertTrue(config.isPresent());
        assertEquals(CoopConnectionRole.HOST, config.role());
        assertEquals("MN-shared-test-seed", config.newGameSeed());
    }

    @Test
    void parsesNewGameSeedAlongsideGuestProperties() {
        Properties properties = new Properties();
        properties.setProperty("coop.connectHost", "127.0.0.1");
        properties.setProperty("coop.connectPort", "7777");
        properties.setProperty("coop.newGameSeed", "MN-shared-test-seed");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertTrue(config.isPresent());
        assertEquals(CoopConnectionRole.GUEST, config.role());
        assertEquals("MN-shared-test-seed", config.newGameSeed());
    }

    @Test
    void newGameSeedAloneIsExposedButNotARoleConfig() {
        Properties properties = new Properties();
        properties.setProperty("coop.newGameSeed", "MN-shared-test-seed");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertFalse(config.isPresent());
        assertEquals(CoopConnectionRole.NONE, config.role());
        assertEquals("MN-shared-test-seed", config.newGameSeed());
    }

    @Test
    void newGameSeedDefaultsToEmpty() {
        CoopNetStartupConfig config = CoopNetStartupConfig.from(new Properties());

        assertEquals("", config.newGameSeed());
    }

    @Test
    void portMappingDefaultsToAuto() {
        assertTrue(CoopNetStartupConfig.from(new Properties()).portMappingEnabled());
    }

    @Test
    void portMappingAutoEnablesTheMapperForAHost() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "27015");
        properties.setProperty("coop.portMapping", "auto");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertEquals(CoopConnectionRole.HOST, config.role());
        assertTrue(config.portMappingEnabled());
    }

    @Test
    void portMappingOffDisablesTheMapper() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "27015");
        properties.setProperty("coop.portMapping", "off");

        assertFalse(CoopNetStartupConfig.from(properties).portMappingEnabled());
    }

    @Test
    void portMappingValueIsCaseAndWhitespaceInsensitive() {
        Properties properties = new Properties();
        properties.setProperty("coop.portMapping", "  OFF  ");

        assertFalse(CoopNetStartupConfig.from(properties).portMappingEnabled());
    }

    @Test
    void portMappingIsReadableWithoutAnyRoleConfiguration() {
        Properties properties = new Properties();
        properties.setProperty("coop.portMapping", "off");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertFalse(config.isPresent());
        assertFalse(config.portMappingEnabled());
    }

    @Test
    void rejectsAnUnknownPortMappingValue() {
        Properties properties = new Properties();
        properties.setProperty("coop.portMapping", "upnp");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> CoopNetStartupConfig.from(properties));

        assertTrue(failure.getMessage().contains("coop.portMapping"), failure.getMessage());
    }

    // ---- Phase 20.2 reconnect grace ---------------------------------------------------------------

    @Test
    void theReconnectGraceDefaultsToSixtySeconds() {
        CoopNetStartupConfig config = CoopNetStartupConfig.from(new Properties());

        assertEquals(60, config.reconnectGraceSeconds());
        assertEquals(60_000L, config.reconnectGraceMillis());
        assertFalse(config.isPresent(), "an empty property set still configures no role");
    }

    @Test
    void parsesAnExplicitReconnectGrace() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "7777");
        properties.setProperty("coop.reconnectGraceSeconds", " 120 ");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertEquals(120, config.reconnectGraceSeconds());
        assertEquals(120_000L, config.reconnectGraceMillis());
    }

    @Test
    void zeroDisablesTheGraceAndIsReadableWithoutARole() {
        Properties properties = new Properties();
        properties.setProperty("coop.reconnectGraceSeconds", "0");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertFalse(config.isPresent());
        assertEquals(0, config.reconnectGraceSeconds());
        assertEquals(0L, config.reconnectGraceMillis());
    }

    @Test
    void rejectsANonNumericOrOutOfRangeReconnectGrace() {
        for (String value : new String[]{"soon", "-1", "3601"}) {
            Properties properties = new Properties();
            properties.setProperty("coop.reconnectGraceSeconds", value);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> CoopNetStartupConfig.from(properties), "expected a rejection for " + value);

            assertTrue(failure.getMessage().contains("coop.reconnectGraceSeconds"), failure.getMessage());
        }
    }

    // ---- Phase 20.4/20.5: lobby password and peer capacity ---------------------------------------

    @Test
    void parsesAndTrimsTheLobbyPassword() {
        Properties properties = new Properties();
        properties.setProperty("coop.password", "  hunter2  ");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertEquals("hunter2", config.password());
        assertTrue(config.passwordRequired());
    }

    @Test
    void anAbsentOrBlankPasswordMeansNoGate() {
        assertEquals("", CoopNetStartupConfig.from(new Properties()).password());
        assertFalse(CoopNetStartupConfig.from(new Properties()).passwordRequired());

        Properties blank = new Properties();
        blank.setProperty("coop.password", "   ");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(blank);

        assertEquals("", config.password());
        assertFalse(config.passwordRequired());
    }

    @Test
    void aPasswordAloneIsReadableWithoutARole() {
        Properties properties = new Properties();
        properties.setProperty("coop.password", "hunter2");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertFalse(config.isPresent());
        assertEquals("hunter2", config.password());
    }

    @Test
    void maxGuestsDefaultsToOne() {
        assertEquals(1, CoopNetStartupConfig.from(new Properties()).maxGuests());
    }

    @Test
    void maxGuestsIsClampedToOneRatherThanRejected() {
        for (String value : new String[]{"3", "0", "-2", "many"}) {
            Properties properties = new Properties();
            properties.setProperty("coop.maxGuests", value);

            CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

            assertEquals(CoopNetStartupConfig.MAX_GUESTS_V1, config.maxGuests(),
                    "coop.maxGuests=" + value + " must clamp, not fail the launch");
        }
    }

    @Test
    void anExplicitMaxGuestsOfOneStaysTheEmptyConfig() {
        Properties properties = new Properties();
        properties.setProperty("coop.maxGuests", "1");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(properties);

        assertFalse(config.isPresent());
        assertEquals(1, config.maxGuests());
    }

    // ---- Phase 20.6 HUD corner ---------------------------------------------------------------------

    @Test
    void hudCornerPropertyNameIsCoopHudCorner() {
        assertEquals("coop.hudCorner", CoopNetStartupConfig.HUD_CORNER_PROPERTY);
    }

    @Test
    void hudCornerParsesAllFourValues() {
        assertEquals(CoopHudCorner.TOP_RIGHT, CoopHudCorner.parse("TR"));
        assertEquals(CoopHudCorner.TOP_LEFT, CoopHudCorner.parse("TL"));
        assertEquals(CoopHudCorner.BOTTOM_RIGHT, CoopHudCorner.parse("BR"));
        assertEquals(CoopHudCorner.BOTTOM_LEFT, CoopHudCorner.parse("BL"));
    }

    @Test
    void hudCornerParsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(CoopHudCorner.BOTTOM_LEFT, CoopHudCorner.parse("  bl  "));
        assertEquals(CoopHudCorner.TOP_LEFT, CoopHudCorner.parse("Tl"));
    }

    @Test
    void hudCornerDefaultsToTopRight() {
        assertEquals(CoopHudCorner.TOP_RIGHT, CoopHudCorner.DEFAULT);
        assertEquals(CoopHudCorner.TOP_RIGHT, CoopHudCorner.parse(null));
        assertEquals(CoopHudCorner.TOP_RIGHT, CoopHudCorner.parse(""));
        assertEquals(CoopHudCorner.TOP_RIGHT, CoopHudCorner.parse("   "));
    }

    @Test
    void anUnknownHudCornerFallsBackToTopRightWithoutThrowing() {
        assertEquals(CoopHudCorner.TOP_RIGHT, assertDoesNotThrow(() -> CoopHudCorner.parse("NE")));
        assertEquals(CoopHudCorner.TOP_RIGHT, assertDoesNotThrow(() -> CoopHudCorner.parse("top-right")));
    }

    @Test
    void hudCornerFromSystemPropertiesReadsTheSystemProperty() {
        String previous = System.getProperty(CoopNetStartupConfig.HUD_CORNER_PROPERTY);
        try {
            System.setProperty(CoopNetStartupConfig.HUD_CORNER_PROPERTY, "bl");
            assertEquals(CoopHudCorner.BOTTOM_LEFT, CoopNetStartupConfig.hudCornerFromSystemProperties());

            System.clearProperty(CoopNetStartupConfig.HUD_CORNER_PROPERTY);
            assertEquals(CoopHudCorner.TOP_RIGHT, CoopNetStartupConfig.hudCornerFromSystemProperties());
        } finally {
            if (previous == null) {
                System.clearProperty(CoopNetStartupConfig.HUD_CORNER_PROPERTY);
            } else {
                System.setProperty(CoopNetStartupConfig.HUD_CORNER_PROPERTY, previous);
            }
        }
    }

    // ---- Phase 28 milestone 1: the file stack underneath the properties ----------------------

    /** A {@code saves/common/coop_options.json} standing in for the real one. */
    private static CoopOptionsStore storeWithCommonOverride(Properties properties, String... keyValues) {
        JSONObject common = new JSONObject();
        try {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                common.put(keyValues[i], keyValues[i + 1]);
            }
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        CoopOptionsStore.JsonSource source = new CoopOptionsStore.JsonSource() {
            @Override
            public JSONObject shipped() {
                return null;
            }

            @Override
            public JSONObject common() {
                return common;
            }
        };
        return new CoopOptionsStore(source, properties::getProperty);
    }

    @Test
    void readsValuesConfiguredOnlyInTheCommonOverrideFile() {
        CoopOptionsStore store = storeWithCommonOverride(new Properties(),
                "coop.hostPort", "7788",
                "coop.portMapping", "off",
                "coop.reconnectGraceSeconds", "120",
                "coop.password", "from-the-file");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(store);

        assertTrue(config.isPresent());
        assertEquals(CoopConnectionRole.HOST, config.role());
        assertEquals(7788, config.port());
        assertFalse(config.portMappingEnabled());
        assertEquals(120, config.reconnectGraceSeconds());
        assertEquals("from-the-file", config.password());
        assertTrue(config.passwordRequired());
    }

    @Test
    void propertiesOverrideTheCommonOverrideFile() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "7777");
        properties.setProperty("coop.reconnectGraceSeconds", "5");
        properties.setProperty("coop.password", "from-the-command-line");

        CoopOptionsStore store = storeWithCommonOverride(properties,
                "coop.hostPort", "7788",
                "coop.reconnectGraceSeconds", "120",
                "coop.password", "from-the-file");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(store);

        assertEquals(7777, config.port());
        assertEquals(5, config.reconnectGraceSeconds());
        assertEquals("from-the-command-line", config.password());
    }

    /**
     * A player who keeps {@code coop.hostPort} in their settings file must still be able to join
     * someone else's game from the command line, instead of hitting the "host and guest configured
     * together" refusal caused by combining the two layers.
     */
    @Test
    void aRoleGivenOnTheCommandLineIgnoresRoleKeysInTheFile() {
        Properties properties = new Properties();
        properties.setProperty("coop.connectHost", "203.0.113.9");
        properties.setProperty("coop.connectPort", "7777");

        CoopOptionsStore store = storeWithCommonOverride(properties, "coop.hostPort", "7788");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(store);

        assertEquals(CoopConnectionRole.GUEST, config.role());
        assertEquals("203.0.113.9", config.host());
        assertEquals(7777, config.port());
    }

    /**
     * Red-team item 1. The strict parsers are reachable from a file a player edits by hand, and a
     * throw here surfaces as a host that logs one WARN and then never starts, with nothing on
     * screen to explain it. A file value is coerced with a warning instead; only the command line
     * is still refused outright (see the test below).
     */
    @Test
    void aMalformedFileValueIsCoercedRatherThanStoppingTheLaunch() {
        CoopOptionsStore store = storeWithCommonOverride(new Properties(),
                "coop.reconnectGraceSeconds", "eventually",
                "coop.portMapping", "sometimes",
                "coop.hostPort", "not-a-port");

        CoopNetStartupConfig config =
                assertDoesNotThrow(() -> CoopNetStartupConfig.from(store));

        assertEquals(CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS,
                config.reconnectGraceSeconds());
        assertTrue(config.portMappingEnabled(), "an unreadable portMapping falls back to auto");
        assertFalse(config.isPresent(), "an unreadable host port reads as \"not hosting\"");
        assertEquals(CoopConnectionRole.NONE, config.role());
    }

    @Test
    void outOfRangeFileValuesAreClampedRatherThanRefused() {
        CoopOptionsStore store = storeWithCommonOverride(new Properties(),
                "coop.reconnectGraceSeconds", "99999",
                "coop.hostPort", "70000");

        CoopNetStartupConfig config =
                assertDoesNotThrow(() -> CoopNetStartupConfig.from(store));

        assertEquals(CoopNetStartupConfig.MAX_RECONNECT_GRACE_SECONDS,
                config.reconnectGraceSeconds());
        assertEquals(CoopConnectionRole.HOST, config.role());
        assertEquals(65535, config.port());
    }

    /**
     * The other half of item 1: whoever typed a bad value on the command line is standing at a
     * console, so it is still refused there. Same values as the file test above.
     */
    @Test
    void aMalformedCommandLineValueIsStillRefusedOutright() {
        for (String[] keyValue : new String[][]{
                {"coop.reconnectGraceSeconds", "eventually"},
                {"coop.portMapping", "sometimes"},
                {"coop.hostPort", "not-a-port"}}) {
            Properties properties = new Properties();
            properties.setProperty(keyValue[0], keyValue[1]);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> CoopNetStartupConfig.from(properties),
                    "expected a rejection for " + keyValue[0] + "=" + keyValue[1]);

            assertTrue(failure.getMessage().contains(keyValue[0]), failure.getMessage());
        }
    }

    /**
     * Red-team item 3: {@code -Dcoop.password=} is how you run one session without the password your
     * settings file sets. It used to be trimmed to null before the layer was chosen, so the file
     * value came back and the gate stayed on.
     */
    @Test
    void anExplicitlyEmptyPasswordPropertyClearsAFilePassword() {
        Properties properties = new Properties();
        properties.setProperty("coop.password", "");

        CoopOptionsStore store =
                storeWithCommonOverride(properties, "coop.password", "from-the-file");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(store);

        assertEquals("", config.password());
        assertFalse(config.passwordRequired());
    }

    /** ...while an absent one still inherits the file password. */
    @Test
    void anAbsentPasswordPropertyStillInheritsTheFilePassword() {
        CoopOptionsStore store =
                storeWithCommonOverride(new Properties(), "coop.password", "from-the-file");

        assertEquals("from-the-file", CoopNetStartupConfig.from(store).password());
    }

    /**
     * The role rule is unchanged by item 3: an empty {@code -Dcoop.hostPort=} does not count as the
     * command line naming a role key, so the file still decides the role.
     */
    @Test
    void anEmptyRolePropertyDoesNotMakeTheCommandLineTheRoleAuthority() {
        Properties properties = new Properties();
        properties.setProperty("coop.hostPort", "");

        CoopOptionsStore store = storeWithCommonOverride(properties,
                "coop.connectHost", "203.0.113.9",
                "coop.connectPort", "7777");

        CoopNetStartupConfig config = CoopNetStartupConfig.from(store);

        assertEquals(CoopConnectionRole.GUEST, config.role());
        assertEquals("203.0.113.9", config.host());
        assertEquals(7777, config.port());
    }

    /** The seed is a one-shot {@code -D} gesture: a file must not be able to pin it. */
    @Test
    void theNewGameSeedIsNotReadableFromAFile() {
        CoopOptionsStore store = storeWithCommonOverride(new Properties(),
                "coop.hostPort", "7777",
                "coop.newGameSeed", "12345");

        assertEquals("", CoopNetStartupConfig.from(store).newGameSeed());
    }
}
