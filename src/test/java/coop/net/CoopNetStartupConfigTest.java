package coop.net;

import org.junit.jupiter.api.Test;

import java.util.Properties;

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
}
