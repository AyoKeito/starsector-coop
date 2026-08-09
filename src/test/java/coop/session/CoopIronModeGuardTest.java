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

    // ---- Phase 12b: depth cap ------------------------------------------------------------------

    @Test
    void deeplyNestedThirdPartyIronModeKeyNoLongerRejects() {
        // A third-party mod storing its own isIronMode key three levels down used to false-positive
        // and block coop entirely. Real save state lives at depth 1 or 2.
        Map<String, ?> persistent = Map.of(
                "someOtherMod", Map.of(
                        "settings", Map.of(
                                "isIronMode", true)));

        assertFalse(CoopIronModeGuard.isIronModeActive(false, persistent));
    }

    @Test
    void depthCapDoesNotBreakTheRealDetectionLevels() {
        // Top level and one nested level are the levels the engine actually uses; both still reject.
        assertTrue(CoopIronModeGuard.isIronModeActive(false, Map.of("isIronMode", true)));
        assertTrue(CoopIronModeGuard.isIronModeActive(false,
                Map.of("saveDescriptor", Map.of("isIronMode", true))));
    }
}
