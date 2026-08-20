package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopFleetMirrorRegistryTest {

    /** Records what the registry asks of each mirror, without touching the engine. */
    private static final class FakeMirror implements CoopNpcMirror {
        /** Stands in for the engine fleet: the shield decision is identity-based on it. */
        final Object fleet = new Object();
        int snapshotApplies;
        int motionApplies;
        int shieldAsserts;
        int shieldReleases;
        long shieldAssertedAtMillis = CoopFleetMirror.NEVER_ASSERTED;
        boolean disposed;
        CoopNpcFleetSnapshot lastSnapshot;

        @Override
        public void assertEngagementShield(Object playerInteractionTarget, long nowMillis) {
            // Same calls the real mirror makes, so the fake cannot drift from the production
            // decisions: release for the targeted fleet, otherwise assert on the re-assert timer.
            if (CoopFleetMirror.shouldReleaseShield(fleet, playerInteractionTarget)) {
                shieldReleases++;
                shieldAssertedAtMillis = CoopFleetMirror.NEVER_ASSERTED;
                return;
            }
            if (!CoopFleetMirror.shouldReassertShield(shieldAssertedAtMillis, nowMillis)) {
                return;
            }
            shieldAssertedAtMillis = nowMillis;
            shieldAsserts++;
        }

        @Override
        public void applySnapshot(CoopNpcFleetSnapshot snapshot) {
            snapshotApplies++;
            lastSnapshot = snapshot;
        }

        @Override
        public void applyMotion(CoopNpcFleetMotion motion) {
            motionApplies++;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private final List<FakeMirror> creationOrder = new ArrayList<>();

    private CoopFleetMirrorRegistry newRegistry() {
        return new CoopFleetMirrorRegistry(() -> {
            FakeMirror mirror = new FakeMirror();
            creationOrder.add(mirror);
            return mirror;
        });
    }

    private static CoopNpcFleetSnapshot fleet(String id, String location, String hull) {
        return CoopNpcFleetSnapshot.create(id, "pirates", "Name " + id, location, 0f, 0f, 0f, 0f, true, sensors(150f, 90f), "",
                List.of(new CoopFleetSnapshot.Member("m-" + id, hull, hull + "_Standard",
                        "Ship", "Cpt", 0.8f, 1.0f)));
    }

    private static CoopNpcFleetSetSnapshot set(CoopNpcFleetSnapshot... fleets) {
        return CoopNpcFleetSetSnapshot.create(List.of(fleets));
    }

    @Test
    void applySetCreatesOneMirrorPerFleet() {
        CoopFleetMirrorRegistry registry = newRegistry();

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));

        assertEquals(2, registry.size());
        assertEquals(List.of("a", "b"), new ArrayList<>(registry.fleetIds()));
        assertEquals(2, creationOrder.size());
    }

    @Test
    void reapplyingSameSetIsIdempotentAndReusesMirrors() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));
        FakeMirror first = creationOrder.get(0);

        registry.applySet(set(fleet("a", "corvus", "wolf")));

        assertEquals(1, registry.size());
        assertEquals(1, creationOrder.size(), "no new mirror created for an existing fleet id");
        assertSame(first, creationOrder.get(0));
        assertEquals(2, first.snapshotApplies, "existing mirror re-applied, not recreated");
        assertFalse(first.disposed);
    }

    @Test
    void fleetAbsentFromNewSetIsDisposedAndRemoved() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));
        FakeMirror mirrorA = creationOrder.get(0);
        FakeMirror mirrorB = creationOrder.get(1);

        registry.applySet(set(fleet("a", "corvus", "wolf")));

        assertEquals(1, registry.size());
        assertEquals(List.of("a"), new ArrayList<>(registry.fleetIds()));
        assertFalse(mirrorA.disposed);
        assertTrue(mirrorB.disposed, "fleet dropped from the host set is disposed");
    }

    @Test
    void newFleetInLaterSetIsAddedWithoutTouchingOthers() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("c", "hyperspace", "kite")));

        assertEquals(2, registry.size());
        assertTrue(registry.fleetIds().contains("c"));
        assertFalse(creationOrder.get(0).disposed);
    }

    @Test
    void applyMotionRoutesToMatchingMirrorAndIgnoresUnknown() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));
        FakeMirror mirrorA = creationOrder.get(0);

        registry.applyMotion(List.of(
                new CoopNpcFleetMotion("a", "corvus", 1f, 2f, 0f, 0f, sensors(150f, 90f)),
                new CoopNpcFleetMotion("ghost", "corvus", 9f, 9f, 0f, 0f, sensors(150f, 90f))));

        assertEquals(1, mirrorA.motionApplies);
        assertEquals(1, registry.size(), "motion for an unknown fleet does not create a mirror");
    }

    @Test
    void assertEngagementShieldsHitsEveryMirrorOnTheReassertCadence() {
        // The pass runs every frame and never depends on traffic arriving; the engine call it makes is
        // what is rate-limited, because setNoEngaging allocates a fader that lasts ~1 s.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));

        registry.assertEngagementShields(null, 1_000L);
        registry.assertEngagementShields(null, 1_016L);
        registry.assertEngagementShields(null, 1_000L + CoopFleetMirror.SHIELD_REASSERT_INTERVAL_MILLIS);

        assertEquals(2, creationOrder.get(0).shieldAsserts);
        assertEquals(2, creationOrder.get(1).shieldAsserts, "every mirror is on the same cadence");
        assertEquals(0, creationOrder.get(0).shieldReleases);
    }

    @Test
    void aReleasedShieldGoesBackUpOnTheNextFrameNotTheNextInterval() {
        // Releasing clears the stamp, so the frame after the player stops targeting the mirror puts
        // the shield straight back up instead of leaving it engageable for up to an interval.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));
        FakeMirror mirror = creationOrder.get(0);

        registry.assertEngagementShields(null, 1_000L);
        registry.assertEngagementShields(mirror.fleet, 1_016L);
        registry.assertEngagementShields(null, 1_032L);

        assertEquals(1, mirror.shieldReleases);
        assertEquals(2, mirror.shieldAsserts);
    }

    @Test
    void onlyThePlayersOwnInteractionTargetHasItsShieldReleased() {
        // Without this release the engine's player-combat-initiation block skips the mirror entirely
        // (it needs target.canBeEngaged()), so right-clicking a mirror does nothing at all.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));
        FakeMirror targeted = creationOrder.get(0);
        FakeMirror other = creationOrder.get(1);

        registry.assertEngagementShields(targeted.fleet);

        assertEquals(1, targeted.shieldReleases, "the targeted mirror must become engageable");
        assertEquals(0, targeted.shieldAsserts);
        assertEquals(1, other.shieldAsserts, "every other mirror keeps the shield");
        assertEquals(0, other.shieldReleases);
    }

    @Test
    void aTargetThatIsNotAMirrorLeavesEveryShieldUp() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));

        registry.assertEngagementShields(new Object());

        assertEquals(1, creationOrder.get(0).shieldAsserts);
        assertEquals(0, creationOrder.get(0).shieldReleases);
    }

    @Test
    void theShieldGoesBackUpWhenThePlayerPicksAnotherTarget() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")));
        FakeMirror mirror = creationOrder.get(0);

        registry.assertEngagementShields(mirror.fleet);
        registry.assertEngagementShields(null);

        assertEquals(1, mirror.shieldReleases);
        assertEquals(1, mirror.shieldAsserts);
    }

    @Test
    void theReleaseDecisionIsFleetIdentityAndNothingElse() {
        Object fleet = new Object();

        assertTrue(CoopFleetMirror.shouldReleaseShield(fleet, fleet));
        assertFalse(CoopFleetMirror.shouldReleaseShield(fleet, new Object()));
        assertFalse(CoopFleetMirror.shouldReleaseShield(fleet, null),
                "no interaction target means no release");
        assertFalse(CoopFleetMirror.shouldReleaseShield(null, null),
                "a mirror with no engine fleet is never released");
    }

    @Test
    void disposedMirrorNoLongerGetsTheShield() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));
        registry.applySet(set(fleet("a", "corvus", "wolf")));

        registry.assertEngagementShields(null);

        assertEquals(1, creationOrder.get(0).shieldAsserts);
        assertEquals(0, creationOrder.get(1).shieldAsserts);
    }

    @Test
    void disposeAllDisposesEveryMirrorAndClears() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")));

        registry.disposeAll();

        assertEquals(0, registry.size());
        assertTrue(creationOrder.get(0).disposed);
        assertTrue(creationOrder.get(1).disposed);
    }

    // ---- Phase 15: post-battle freeze --------------------------------------------------------------

    @Test
    void aFrozenMirrorIsNotResurrectedByTheHostsStaleSet() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        // The host has not heard about the battle yet, so it keeps reporting the pre-battle roster.
        registry.applySet(set(fleet("a", "corvus", "wolf")), 3000L);

        assertEquals(1, mirror.snapshotApplies, "the beaten mirror must not be re-asserted");
        assertFalse(mirror.disposed);
        assertEquals(List.of("a"), new ArrayList<>(registry.pendingReconcileIds()));
    }

    @Test
    void theFreezeReleasesWhenTheHostReportsTheNewRoster() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        // Reconciled: the fleet lost a ship, so its roster hash is different now.
        registry.applySet(set(fleet("a", "corvus", "lasher")), 3000L);

        assertEquals(2, mirror.snapshotApplies);
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void theFreezeReleasesWhenTheHostDropsTheFleetEntirely() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 1000L);
        FakeMirror killed = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applySet(set(fleet("b", "magec", "lasher")), 3000L);

        assertTrue(killed.disposed, "the host confirmed the kill");
        assertEquals(1, registry.size());
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void theFreezeCannotBecomePermanentDivergence() {
        // A BATTLE_RESULT that never arrives (disconnect) must not leave a mirror frozen forever;
        // the host's authoritative set legitimately resurrects the unreported kill.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applySet(set(fleet("a", "corvus", "wolf")),
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS);

        assertEquals(2, mirror.snapshotApplies);
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void motionKeepsDrivingAFrozenMirror() {
        // The freeze is a roster freeze, not a position freeze: a beaten survivor still flees.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applyMotion(List.of(new CoopNpcFleetMotion("a", "corvus", 5f, 5f, 1f, 1f, sensors(150f, 90f))));

        assertEquals(1, mirror.motionApplies);
    }

    @Test
    void reMarkingRefreshesTheClockWithoutForgettingThePreBattleRoster() {
        // The freeze is refreshed on the battle's status cadence so a fight longer than the timeout
        // does not come back to an already-thawed mirror.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);
        registry.markPendingReconcile("a",
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS - 1);

        registry.applySet(set(fleet("a", "corvus", "wolf")),
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS + 1000L);

        assertEquals(1, mirror.snapshotApplies, "the refreshed freeze still holds");
        assertEquals(List.of("a"), new ArrayList<>(registry.pendingReconcileIds()));
    }

    @Test
    void aTwoSecondReMarkCadenceKeepsTheFreezeAliveThroughAThreeMinuteDialog() {
        // The battle bridge renews the mark on its FREEZE_REFRESH_INTERVAL_MILLIS cadence for as long
        // as the post-battle dialog is open; interleaved with the host's ~1 s set stream, the stale
        // pre-battle roster must never re-assert, however long the player browses salvage.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        for (long now = 2000L; now <= 2000L + 180_000L; now += 2000L) {
            registry.markPendingReconcile("a", now);
            registry.applySet(set(fleet("a", "corvus", "wolf")), now + 1000L);
        }

        assertEquals(1, mirror.snapshotApplies, "the stale pre-battle roster never re-asserts");
        assertEquals(List.of("a"), new ArrayList<>(registry.pendingReconcileIds()));
    }

    @Test
    void markingAnUnknownFleetIsANoOp() {
        CoopFleetMirrorRegistry registry = newRegistry();

        registry.markPendingReconcile("ghost", 1000L);
        registry.markPendingReconcile("", 1000L);

        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void theFreezePredicateIsPureAndTimeBounded() {
        assertTrue(CoopFleetMirrorRegistry.shouldDeferReassert("hash-1", 0L, "hash-1", 5000L));
        assertFalse(CoopFleetMirrorRegistry.shouldDeferReassert("hash-1", 0L, "hash-2", 5000L),
                "a changed roster means the host reconciled");
        assertFalse(CoopFleetMirrorRegistry.shouldDeferReassert("hash-1", 0L, "hash-1",
                        CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS),
                "the freeze expires so it can never diverge permanently");
    }

    /** Phase 14b sensor identity fixture: profile + the three detected-range aggregates + strength. */
    private static CoopSensorSync.Profile sensors(float profile, float strength) {
        return new CoopSensorSync.Profile(profile, 0f, 0f, 1f, strength);
    }
}
