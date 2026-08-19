package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure decisions inside the mirror driving path. The engine-touching parts are exercised
 * through {@link CoopFleetMirrorRegistryTest}'s fakes; these are the bits that have to be right for
 * reasons the registry cannot see.
 */
class CoopFleetMirrorTest {

    @Test
    void aChangedCrForcesTheCachedStrengthInvalidation() {
        // RepairTracker.setCR is a bare field write: it clears nothing. FleetMember caches its
        // CR-derived strength in cachedStrength, which only setStatUpdateNeeded(true)/updateStats()
        // reset (probe/FleetMember.java:278-284, 640-661). Without the invalidation a mirror's
        // engine-visible strength stayed frozen at roster-build time and every hostile's
        // pickEncounterOption judged a wrecked guest fleet as if it were fresh.
        assertTrue(CoopFleetMirror.crDiffers(1.0f, 0.4f));
        assertTrue(CoopFleetMirror.crDiffers(0.4f, 1.0f));
        assertTrue(CoopFleetMirror.crDiffers(0.70f, 0.71f));
    }

    @Test
    void anUnchangedCrDoesNotForceAPerMemberStatRebuild() {
        // The deferred updateStats() rebuilds the member's whole stat object and cascades into a fleet
        // sync, so it must not run on every 10 Hz apply for a fleet whose CR is not moving.
        assertFalse(CoopFleetMirror.crDiffers(0.7f, 0.7f));
        assertFalse(CoopFleetMirror.crDiffers(0.7f, 0.7004f));
        assertFalse(CoopFleetMirror.crDiffers(0f, 0f));
    }
}
