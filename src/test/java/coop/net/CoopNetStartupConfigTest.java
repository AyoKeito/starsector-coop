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
}
