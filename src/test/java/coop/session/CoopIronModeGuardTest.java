package coop.session;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopIronModeGuardTest {
    @Test
    void activeSectorIronModeRejectsCoop() {
        assertTrue(CoopIronModeGuard.isIronModeActive(true, Map.of()));
    }

    @Test
    void persistentIsIronModeBooleanRejectsLoadedSave() {
        assertTrue(CoopIronModeGuard.isIronModeActive(false, Map.of("isIronMode", true)));
    }

    @Test
    void nestedPersistentIsIronModeStringRejectsLoadedSaveDescriptor() {
        assertTrue(CoopIronModeGuard.isIronModeActive(false,
                Map.of("saveDescriptor", Map.of("isIronMode", "true"))));
    }

    @Test
    void nonIronModeStateDoesNotReject() {
        assertFalse(CoopIronModeGuard.isIronModeActive(false,
                Map.of("saveDescriptor", Map.of("isIronMode", false))));
    }
}
