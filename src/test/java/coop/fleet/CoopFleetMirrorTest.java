package coop.fleet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aCompleteRebuildIsCommittedImmediately() {
        assertTrue(CoopFleetMirror.shouldCommitRoster(true, "hash-a", null));
    }

    @Test
    void anIncompleteRebuildIsRetriedExactlyOnce() {
        // First failure: do NOT commit, or the structural-hash gate latches the short roster and the
        // mirror wears it until the host fleet's own roster changes — which for a stable patrol is
        // never. Second failure on the same hash: commit, so an unbuildable roster cannot rebuild on
        // every set for the life of the fleet.
        assertFalse(CoopFleetMirror.shouldCommitRoster(false, "hash-a", null));
        assertTrue(CoopFleetMirror.shouldCommitRoster(false, "hash-a", "hash-a"));
        assertFalse(CoopFleetMirror.shouldCommitRoster(false, "hash-b", "hash-a"));
    }

    /** Stands in for this install's spec store. */
    private static java.util.function.Predicate<String> installHas(String... ids) {
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(ids));
        return known::contains;
    }

    @Test
    void aResolvableVariantIsUsedDirectly() {
        assertEquals("falcon_Assault", CoopFleetMirror.resolveCreationId("falcon_Assault",
                "falcon_default_D", installHas("falcon_Assault", "falcon_Hull")));
    }

    @Test
    void anUnknownVariantFallsToTheHullInsteadOfBeingAskedFor() {
        // Asking for it is the bug: createFleetMember does not reject an unknown variant id, it
        // substitutes settings.json's errorShipVariant, so the exception-driven chain never fired.
        assertEquals("falcon_default_D_Hull", CoopFleetMirror.resolveCreationId("905d_3",
                "falcon_default_D", installHas("falcon_Hull", "falcon_default_D_Hull")));
    }

    @Test
    void aDHullWithNoHullVariantFallsToItsNonDParent() {
        // ShipHullSpecLoader registers the generated <id>_default_D HULL but builds the auto
        // "<id>_Hull" VARIANT from the parent spec only, so falcon_default_D_Hull does not exist
        // even though the falcon_default_D hull does. Gating this branch on the hull spec instead of
        // the variant id would ask for it and get another silent placeholder.
        assertEquals("falcon_Hull", CoopFleetMirror.resolveCreationId("905d_3", "falcon_default_D",
                installHas("falcon_Hull")));
    }

    @Test
    void nothingResolvableMeansNoShipRatherThanAPlaceholder() {
        assertEquals("", CoopFleetMirror.resolveCreationId("905d_3", "falcon_default_D",
                installHas()));
        assertEquals("", CoopFleetMirror.resolveCreationId("", "", installHas()));
    }

    @Test
    void losingTheDHullSuffixIsAcceptableButGettingANebulaIsNot() {
        // Building the stock falcon_Assault for a host ship whose live hull was falcon_default_D is
        // the accepted d-mod-fidelity trade.
        assertTrue(CoopFleetMirror.isPlausibleHull("falcon", "falcon_default_D"));
        assertTrue(CoopFleetMirror.isPlausibleHull("falcon_default_D", "falcon"));
        assertTrue(CoopFleetMirror.isPlausibleHull("falcon", "falcon"));
        // The 2026-08-19 failure: every inflated ship came back as the engine's placeholder.
        assertFalse(CoopFleetMirror.isPlausibleHull("nebula", "falcon_default_D"));
        assertFalse(CoopFleetMirror.isPlausibleHull("nebula", "manticore"));
    }

    @Test
    void aMemberWithNoStreamedHullIsNotDiscardedOverTheCrossCheck() {
        assertTrue(CoopFleetMirror.isPlausibleHull("nebula", ""));
        assertTrue(CoopFleetMirror.isPlausibleHull("nebula", null));
    }

    @Test
    void theCodecUnescapeIsTheInverseOfEscape() {
        // CoopNpcFleetSetSnapshot escapes an already-escaped per-fleet block, so escape/unescape have
        // to agree on the whole alphabet — including the U+001F case, which unescape used to drop.
        String raw = "a|b\\c\nd\re" + CoopFleetCodec.UNIT_SEPARATOR + "f";
        assertEquals(raw, CoopFleetCodec.unescape(CoopFleetCodec.escape(raw)));
    }
}
