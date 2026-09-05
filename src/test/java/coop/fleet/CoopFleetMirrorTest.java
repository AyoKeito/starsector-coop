package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.util.CoopDebug;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void aSlowCrRecoveryTripsTheGateOnceInsteadOfNever() {
        // The 2026-08-25 ratchet. Wire CR arrives quantized onto CoopFleetCodec.FRACTION_STEP (0.001),
        // so a repairing ship moves one step per sample and no single step ever clears the 0.005
        // epsilon. Gating against the value the previous apply WROTE meant the gate never fired at all
        // however far CR really drifted, and cachedStrength stayed frozen for the life of the roster.
        float ratchetReference = 0.40f;
        float lastInvalidated = 0.40f;
        int ratchetFirings = 0;
        int firings = 0;
        float cr = 0.40f;
        for (int step = 0; step < 6; step++) {
            cr += 0.001f;
            if (CoopFleetMirror.crDiffers(ratchetReference, cr)) {
                ratchetFirings++;
            }
            ratchetReference = cr; // the old behaviour: the reference chased every write
            if (CoopFleetMirror.crDiffers(lastInvalidated, cr)) {
                firings++;
            }
            lastInvalidated = CoopFleetMirror.nextCrReference(lastInvalidated, cr);
        }
        assertEquals(0, ratchetFirings);
        assertEquals(1, firings);
        // The one firing reseated the reference; it is no longer the CR the recovery started from.
        assertTrue(lastInvalidated > 0.404f);
    }

    @Test
    void perTickCrNoiseAroundAStableValueStillInvalidatesNothing() {
        // The case the gate exists for: a fleet whose CR is not really moving must not drag a full
        // per-member updateStats() (and the fleet sync it cascades into) through every 10 Hz apply.
        // Holding the reference still is also what keeps the noise from random-walking the reference
        // out from under itself.
        float reference = 0.70f;
        int firings = 0;
        for (float cr : new float[] {0.700f, 0.703f, 0.698f, 0.702f, 0.699f, 0.701f, 0.700f}) {
            if (CoopFleetMirror.crDiffers(reference, cr)) {
                firings++;
            }
            reference = CoopFleetMirror.nextCrReference(reference, cr);
        }
        assertEquals(0, firings);
        assertEquals(0.70f, reference, 1e-6f);
    }

    @Test
    void aFiredGateReseatsItsReferenceOnTheValueThatFiredIt() {
        // Otherwise one real jump would leave the reference behind and every later sample would fire.
        assertEquals(0.4f, CoopFleetMirror.nextCrReference(1.0f, 0.4f), 1e-6f);
        assertEquals(0.7f, CoopFleetMirror.nextCrReference(0.7f, 0.702f), 1e-6f);
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

    @Test
    void anEmptyPlayerRosterIsNeverCommitted() {
        // The wiped client streams 0-member FLEET_SNAPSHOTs for the seconds between "no ships left"
        // and vanilla's showShuttleDialog() replacing its fleet. Committing that gives the partner a
        // 0-member CampaignFleet, which CampaignFleet.advance() despawns as NO_MEMBERS - a branch
        // setNoAutoDespawn(true) does not cover. Skipping keeps the last non-empty roster until the
        // respawned fleet's snapshot lands.
        assertTrue(CoopFleetMirror.shouldSkipRosterApply(true, 0));
    }

    @Test
    void aNonEmptyPlayerRosterAppliesNormally() {
        assertFalse(CoopFleetMirror.shouldSkipRosterApply(true, 1));
        assertFalse(CoopFleetMirror.shouldSkipRosterApply(true, 30));
    }

    @Test
    void anEmptySnapshotNeverCreatesAPlayerMirrorInTheFirstPlace() {
        // The other half of the wipe guard: with no mirror yet, building one from a 0-member snapshot
        // hands the engine a member-less CampaignFleet, which advance() despawns as NO_MEMBERS - and
        // the next snapshot then builds another. Creation waits for a roster worth showing.
        assertTrue(CoopFleetMirror.shouldDeferPlayerFleetCreation(false, 0));
        assertFalse(CoopFleetMirror.shouldDeferPlayerFleetCreation(true, 0),
                "a live mirror keeps its last roster instead - that is shouldSkipRosterApply's job");
        assertFalse(CoopFleetMirror.shouldDeferPlayerFleetCreation(false, 1));
    }

    // ---- The same-hash fast path ------------------------------------------------------------------

    @Test
    void aMirrorWhoseRosterShrankUnderneathItIsRebuiltEvenAtTheSameHash() {
        // The freeze the registry holds after a local battle promises that the host's authoritative
        // set resurrects an unreported kill. It could not: the thawed apply carries the host's
        // unchanged hash, so the gate took the fast path and updateMemberState bailed on the count
        // mismatch, leaving a six-ship fleet mirrored as the three ships the battle left.
        assertFalse(CoopFleetMirror.rosterStillIntact(3, 6));
        assertTrue(CoopFleetMirror.rosterStillIntact(6, 6));
    }

    @Test
    void anUnbuildableRosterStillDoesNotRebuildForever() {
        // The count compared against is what the last rebuild actually BUILT, not what the snapshot
        // asked for, or a roster with one unresolvable variant would rebuild on every set for the
        // life of the fleet - the storm the retry latch exists to bound.
        assertTrue(CoopFleetMirror.rosterStillIntact(4, 4), "built 4 of 6, and 4 are still there");
        assertTrue(CoopFleetMirror.rosterStillIntact(-1, 6), "an unreadable fleet is not a shrink");
        assertTrue(CoopFleetMirror.rosterStillIntact(0, -1), "nothing has been built yet");
    }

    // ---- Per-ship state pairing -------------------------------------------------------------------

    @Test
    void perShipStateFollowsTheShipWhenTheSenderReordersAtAnUnchangedHash() {
        // The structural hash sorts, so a reorder alone never rebuilds the mirror. Paired by raw
        // position, every ship would then wear a sibling's CR until the ship set itself changed.
        List<String> built = List.of("m-wolf", "m-lasher");
        List<CoopFleetSnapshot.Member> reordered = List.of(member("m-lasher"), member("m-wolf"));

        int[] pairing = CoopFleetMirror.memberPairing(built, reordered);

        assertNotNull(pairing);
        assertEquals(1, pairing[0], "mirror slot 0 is the wolf, which is now member 1");
        assertEquals(0, pairing[1]);
    }

    @Test
    void anUnchangedOrderPairsByPositionWithNoPermutationAtAll() {
        assertNull(CoopFleetMirror.memberPairing(List.of("m-wolf", "m-lasher"),
                List.of(member("m-wolf"), member("m-lasher"))));
    }

    @Test
    void anythingLessThanACleanOneToOneFallsBackToPosition() {
        // Half a permutation is worse than none: the unmapped slots would take whatever was left.
        assertNull(CoopFleetMirror.memberPairing(List.of("m-wolf"),
                List.of(member("m-wolf"), member("m-lasher"))), "sizes disagree");
        assertNull(CoopFleetMirror.memberPairing(List.of("m-wolf", "m-lasher"),
                List.of(member("m-wolf"), member("m-hound"))), "a slot the snapshot does not name");
        assertNull(CoopFleetMirror.memberPairing(List.of("m-wolf", "m-lasher"),
                List.of(member("m-wolf"), member(""))), "an id-less member");
        assertNull(CoopFleetMirror.memberPairing(List.of("m-wolf", "m-wolf"),
                List.of(member("m-wolf"), member("m-wolf"))), "duplicate ids");
        assertNull(CoopFleetMirror.memberPairing(null, List.of(member("m-wolf"))));
    }

    private static CoopFleetSnapshot.Member member(String id) {
        return new CoopFleetSnapshot.Member(id, "wolf", "wolf_Assault", "Ship", "Cpt", 0.5f, 1f);
    }

    // ---- Ordering against the motion stream -------------------------------------------------------

    @Test
    void aSetOlderThanTheBufferedMotionIsNotAllowedToMoveTheMirrorBack() {
        // The 1 Hz NPC set rides TCP and the motion stream rides UDP, so a set built before a jump can
        // land after the datagrams that already carried the fleet through it. Re-placing the mirror
        // from it would remove/add the entity, clear the interpolation buffer and hard-set the old
        // position, for one interval, on every jump the set lags.
        assertTrue(CoopFleetMirror.isStaleSample(12.5, 12.4));
        assertTrue(CoopFleetMirror.isStaleSample(12.5, 12.5), "a repeat of the newest sample too");
        assertFalse(CoopFleetMirror.isStaleSample(12.5, 12.6));
        assertFalse(CoopFleetMirror.isStaleSample(Double.NaN, 12.6), "an empty buffer is never stale");
    }

    // ---- The battle pull-in shield ----------------------------------------------------------------

    @Test
    void aLiveMirrorGetsItsPullInShieldReAssertedNotJustSetAtCreation() {
        // FLEET_IGNORES_OTHER_FLEETS is the only flag pullInNearbyFleets consults (it never calls
        // canBeEngaged), and it lives in fleet memory that dialog staging and restores can drop.
        FakeMemory memory = new FakeMemory();

        CoopFleetMirror.assertIgnoresOtherFleets(fleetWith(memory));

        assertEquals(Boolean.TRUE, memory.values.get(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
        assertEquals(1, memory.writes);
    }

    @Test
    void aShieldThatIsAlreadyUpIsNotRewrittenOnEveryApply() {
        FakeMemory memory = new FakeMemory();
        memory.values.put(MemFlags.FLEET_IGNORES_OTHER_FLEETS, Boolean.TRUE);

        CoopFleetMirror.assertIgnoresOtherFleets(fleetWith(memory));

        assertEquals(0, memory.writes, "one memory read per apply, a write only when it is gone");
    }

    @Test
    void aFleetThatCannotAnswerForItsMemoryNeverBreaksAnApply() {
        CampaignFleetAPI throwing = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("no memory");
                });

        CoopFleetMirror.assertIgnoresOtherFleets(throwing);
        CoopFleetMirror.assertIgnoresOtherFleets(null);
    }

    // ---- The ally pull-in spike (-Dcoop.debug.allyPullIn) -----------------------------------------

    @AfterEach
    void disarmTheSpike() {
        CoopDebug.setAllyPullInForTesting(false, false);
    }

    @Test
    void theShippedPlayerMirrorIsUnjoinableAtCreationAndStaysThatWay() {
        FakeMemory created = new FakeMemory();
        CoopFleetMirror.stampPlayerMirrorMemory(created.proxy());

        assertEquals(Boolean.TRUE, created.values.get(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
        assertEquals(Boolean.TRUE, created.values.get(CoopMirrorTags.PLAYER_MIRROR_TAG));

        // And the per-apply re-assert puts it back if anything drops it mid-session.
        FakeMemory dropped = new FakeMemory();
        CoopFleetMirror.assertIgnoresOtherFleets(fleetWith(dropped), true);
        assertEquals(Boolean.TRUE, dropped.values.get(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
    }

    @Test
    void theArmedSpikeLeavesThePlayerMirrorJoinableAtCreationAndOnEveryApply() {
        // Being joinable is the point: FLEET_IGNORES_OTHER_FLEETS is the only thing pullInNearbyFleets
        // consults, so the re-assert would put the mirror back out of reach on the next snapshot.
        CoopDebug.setAllyPullInForTesting(true, false);

        FakeMemory created = new FakeMemory();
        CoopFleetMirror.stampPlayerMirrorMemory(created.proxy());
        assertFalse(created.values.containsKey(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
        assertEquals(Boolean.TRUE, created.values.get(CoopMirrorTags.PLAYER_MIRROR_TAG),
                "it is still the partner's mirror, and everything else keys off that tag");

        FakeMemory live = new FakeMemory();
        CoopFleetMirror.assertIgnoresOtherFleets(fleetWith(live), true);
        assertEquals(0, live.writes, "the per-apply re-assert must not put the flag back");
    }

    @Test
    void theArmedSpikeDoesNotTouchNpcMirrors() {
        // Only the partner's own fleet is being exposed. An NPC mirror joining a host battle would be
        // a second, unrelated failure mode in the same log.
        CoopDebug.setAllyPullInForTesting(true, true);

        FakeMemory created = new FakeMemory();
        CoopFleetMirror.stampNpcMirrorMemory(created.proxy(), "fleet-7");
        assertEquals(Boolean.TRUE, created.values.get(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
        assertEquals("fleet-7", created.values.get(CoopMirrorTags.NPC_MIRROR_TAG));

        FakeMemory live = new FakeMemory();
        CoopFleetMirror.assertIgnoresOtherFleets(fleetWith(live));
        assertEquals(Boolean.TRUE, live.values.get(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
        assertEquals(1, live.writes);
    }

    /** Just the two MemoryAPI calls the shield assert makes, with a write counter. */
    private static final class FakeMemory {
        final Map<String, Object> values = new HashMap<>();
        int writes;

        MemoryAPI proxy() {
            return (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(),
                    new Class<?>[] {MemoryAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBoolean" -> Boolean.TRUE.equals(values.get((String) args[0]));
                        case "set" -> {
                            writes++;
                            values.put((String) args[0], args[1]);
                            yield null;
                        }
                        case "toString" -> "FakeMemory";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static CampaignFleetAPI fleetWith(FakeMemory memory) {
        MemoryAPI api = memory.proxy();
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMemoryWithoutUpdate" -> api;
                    case "toString" -> "FakeFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void anNpcMirrorStillAcceptsAnEmptyRoster() {
        // Phase 15's battle-result teardown legitimately empties a destroyed NPC mirror moments before
        // removing it. A blanket guard would leave the wreck wearing its pre-battle roster.
        assertFalse(CoopFleetMirror.shouldSkipRosterApply(false, 0));
        assertFalse(CoopFleetMirror.shouldSkipRosterApply(false, 4));
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

    // ---- The engagement-shield re-assert timer ---------------------------------------------------

    @Test
    void theShieldIsAssertedOnTheFirstPass() {
        // Sentinel-checked explicitly rather than by arithmetic: now - Long.MIN_VALUE overflows.
        assertTrue(CoopFleetMirror.shouldReassertShield(CoopFleetMirror.NEVER_ASSERTED, 0L));
        assertTrue(CoopFleetMirror.shouldReassertShield(CoopFleetMirror.NEVER_ASSERTED, 5_000L));
    }

    @Test
    void theShieldIsNotRebuiltEveryFrame() {
        // setNoEngaging allocates a fresh Fader per call and the fader it builds does not expire for
        // ~1 s, so re-asserting every frame for every mirror was 2580 allocations/s at 43 mirrors.
        long last = 10_000L;
        assertFalse(CoopFleetMirror.shouldReassertShield(last, last));
        assertFalse(CoopFleetMirror.shouldReassertShield(last, last + 16L));
        assertFalse(CoopFleetMirror.shouldReassertShield(last,
                last + CoopFleetMirror.SHIELD_REASSERT_INTERVAL_MILLIS - 1L));
    }

    @Test
    void theShieldIsRefreshedWellInsideTheFadersLifetime() {
        // The fader lasts ~1 s; a 250 ms cadence holds it with a 4x margin even if a frame is late.
        long last = 10_000L;
        assertTrue(CoopFleetMirror.shouldReassertShield(last,
                last + CoopFleetMirror.SHIELD_REASSERT_INTERVAL_MILLIS));
        assertTrue(CoopFleetMirror.shouldReassertShield(last, last + 900L));
        assertTrue(CoopFleetMirror.SHIELD_REASSERT_INTERVAL_MILLIS * 4 <= 1000L,
                "the interval must stay comfortably inside the fader's ~1 s lifetime");
    }

    @Test
    void aBackwardsClockReassertsRatherThanLeavingTheShieldDown() {
        assertTrue(CoopFleetMirror.shouldReassertShield(10_000L, 9_000L));
    }

    @Test
    void theCodecUnescapeIsTheInverseOfEscape() {
        // CoopNpcFleetSetSnapshot escapes an already-escaped per-fleet block, so escape/unescape have
        // to agree on the whole alphabet — including the U+001F case, which unescape used to drop.
        String raw = "a|b\\c\nd\re" + CoopFleetCodec.UNIT_SEPARATOR + "f";
        assertEquals(raw, CoopFleetCodec.unescape(CoopFleetCodec.escape(raw)));
    }
}
