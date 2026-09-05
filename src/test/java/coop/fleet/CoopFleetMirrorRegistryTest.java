package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopFleetMirrorRegistryTest {

    /** Records what the registry asks of each mirror, without touching the engine. */
    private static final class FakeMirror implements CoopNpcMirror {
        /** Stands in for the engine fleet: the shield decision is identity-based on it. */
        final Object fleet = new Object();
        int snapshotApplies;
        int motionApplies;
        int motionAdvances;
        double lastSampleTimeSeconds = Double.NaN;
        double lastCursorSeconds = Double.NaN;
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
        public void applySnapshot(CoopNpcFleetSnapshot snapshot, double sampleTimeSeconds) {
            snapshotApplies++;
            lastSnapshot = snapshot;
            lastSampleTimeSeconds = sampleTimeSeconds;
        }

        @Override
        public void applyMotion(CoopNpcFleetMotion motion, double sampleTimeSeconds) {
            motionApplies++;
            lastSampleTimeSeconds = sampleTimeSeconds;
        }

        @Override
        public void advanceMotion(double cursorSeconds) {
            motionAdvances++;
            lastCursorSeconds = cursorSeconds;
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

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);

        assertEquals(2, registry.size());
        assertEquals(List.of("a", "b"), new ArrayList<>(registry.fleetIds()));
        assertEquals(2, creationOrder.size());
    }

    @Test
    void reapplyingSameSetIsIdempotentAndReusesMirrors() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);
        FakeMirror first = creationOrder.get(0);

        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);

        assertEquals(1, registry.size());
        assertEquals(1, creationOrder.size(), "no new mirror created for an existing fleet id");
        assertSame(first, creationOrder.get(0));
        assertEquals(2, first.snapshotApplies, "existing mirror re-applied, not recreated");
        assertFalse(first.disposed);
    }

    @Test
    void fleetAbsentFromNewSetIsDisposedAndRemoved() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);
        FakeMirror mirrorA = creationOrder.get(0);
        FakeMirror mirrorB = creationOrder.get(1);

        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);

        assertEquals(1, registry.size());
        assertEquals(List.of("a"), new ArrayList<>(registry.fleetIds()));
        assertFalse(mirrorA.disposed);
        assertTrue(mirrorB.disposed, "fleet dropped from the host set is disposed");
    }

    @Test
    void newFleetInLaterSetIsAddedWithoutTouchingOthers() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("c", "hyperspace", "kite")), 0.0);

        assertEquals(2, registry.size());
        assertTrue(registry.fleetIds().contains("c"));
        assertFalse(creationOrder.get(0).disposed);
    }

    @Test
    void applyMotionRoutesToMatchingMirrorAndIgnoresUnknown() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);
        FakeMirror mirrorA = creationOrder.get(0);

        registry.applyMotion(List.of(
                new CoopNpcFleetMotion("a", "corvus", 1f, 2f, 0f, 0f, sensors(150f, 90f)),
                new CoopNpcFleetMotion("ghost", "corvus", 9f, 9f, 0f, 0f, sensors(150f, 90f))), 12.5);

        assertEquals(1, mirrorA.motionApplies);
        assertEquals(12.5, mirrorA.lastSampleTimeSeconds, "the section stamp reaches the mirror");
        assertEquals(1, registry.size(), "motion for an unknown fleet does not create a mirror");
    }

    @Test
    void advanceMotionDrivesEveryMirrorAndSkipsAnEmptyCursor() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);

        registry.advanceMotion(Double.NaN);
        assertEquals(0, creationOrder.get(0).motionAdvances, "no cursor yet means no drive");

        registry.advanceMotion(42.0);
        assertEquals(1, creationOrder.get(0).motionAdvances);
        assertEquals(1, creationOrder.get(1).motionAdvances);
        assertEquals(42.0, creationOrder.get(1).lastCursorSeconds);
    }

    @Test
    void assertEngagementShieldsHitsEveryMirrorOnTheReassertCadence() {
        // The pass runs every frame and never depends on traffic arriving; the engine call it makes is
        // what is rate-limited, because setNoEngaging allocates a fader that lasts ~1 s.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);

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
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);
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
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);
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
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);

        registry.assertEngagementShields(new Object());

        assertEquals(1, creationOrder.get(0).shieldAsserts);
        assertEquals(0, creationOrder.get(0).shieldReleases);
    }

    @Test
    void theShieldGoesBackUpWhenThePlayerPicksAnotherTarget() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);
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
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0);

        registry.assertEngagementShields(null);

        assertEquals(1, creationOrder.get(0).shieldAsserts);
        assertEquals(0, creationOrder.get(1).shieldAsserts);
    }

    @Test
    void disposeAllDisposesEveryMirrorAndClears() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0);

        registry.disposeAll();

        assertEquals(0, registry.size());
        assertTrue(creationOrder.get(0).disposed);
        assertTrue(creationOrder.get(1).disposed);
    }

    // ---- Phase 15: post-battle freeze --------------------------------------------------------------

    @Test
    void aFrozenMirrorIsNotResurrectedByTheHostsStaleSet() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        // The host has not heard about the battle yet, so it keeps reporting the pre-battle roster.
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 3000L);

        assertEquals(1, mirror.snapshotApplies, "the beaten mirror must not be re-asserted");
        assertFalse(mirror.disposed);
        assertEquals(List.of("a"), new ArrayList<>(registry.pendingReconcileIds()));
    }

    @Test
    void theFreezeReleasesWhenTheHostReportsTheNewRoster() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        // Reconciled: the fleet lost a ship, so its roster hash is different now.
        registry.applySet(set(fleet("a", "corvus", "lasher")), 0.0, 3000L);

        assertEquals(2, mirror.snapshotApplies);
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void theFreezeReleasesWhenTheHostDropsTheFleetEntirely() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "magec", "lasher")), 0.0, 1000L);
        FakeMirror killed = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applySet(set(fleet("b", "magec", "lasher")), 0.0, 3000L);

        assertTrue(killed.disposed, "the host confirmed the kill");
        assertEquals(1, registry.size());
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void theFreezeCannotBecomePermanentDivergence() {
        // A BATTLE_RESULT that never arrives (disconnect) must not leave a mirror frozen forever;
        // the host's authoritative set legitimately resurrects the unreported kill.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0,
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS);

        assertEquals(2, mirror.snapshotApplies);
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void motionKeepsDrivingAFrozenMirror() {
        // The freeze is a roster freeze, not a position freeze: a beaten survivor still flees.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applyMotion(List.of(new CoopNpcFleetMotion("a", "corvus", 5f, 5f, 1f, 1f, sensors(150f, 90f))), 0.0);

        assertEquals(1, mirror.motionApplies);
    }

    @Test
    void reMarkingRefreshesTheClockWithoutForgettingThePreBattleRoster() {
        // The freeze is refreshed on the battle's status cadence so a fight longer than the timeout
        // does not come back to an already-thawed mirror.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);
        registry.markPendingReconcile("a",
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS - 1);

        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0,
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
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        for (long now = 2000L; now <= 2000L + 180_000L; now += 2000L) {
            registry.markPendingReconcile("a", now);
            registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, now + 1000L);
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

    // ---- the freeze expires on a per-frame clock, not only on set arrival ------------------------

    @Test
    void anExpiredFreezeAppliesTheSnapshotItDeferred() {
        // The host only sends NPC_FLEET_SET when its set hash changes, so the timeout cannot depend
        // on another set arriving: the per-frame expiry applies the host's last word instead.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);
        CoopNpcFleetSetSnapshot deferred = set(fleet("a", "corvus", "wolf"));
        registry.applySet(deferred, 7.5, 3000L);
        assertEquals(1, mirror.snapshotApplies, "still frozen while the mark stands");

        registry.expirePendingReconcile(
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS);

        assertEquals(2, mirror.snapshotApplies, "exactly one apply on expiry");
        assertSame(deferred.fleets().get(0), mirror.lastSnapshot);
        assertEquals(7.5, mirror.lastSampleTimeSeconds, 1e-9,
                "the deferred sample time rides along; the mirror's own staleness check drops the"
                        + " position stamp if it is behind");
        assertTrue(registry.pendingReconcileIds().isEmpty());

        // And the freeze is genuinely gone: the very next identical set applies normally.
        registry.applySet(set(fleet("a", "corvus", "wolf")), 8.0, 70_000L);
        assertEquals(3, mirror.snapshotApplies);
    }

    @Test
    void expiringOneSecondEarlyChangesNothing() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);
        registry.applySet(set(fleet("a", "corvus", "wolf")), 7.5, 3000L);

        registry.expirePendingReconcile(
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS - 1000L);

        assertEquals(1, mirror.snapshotApplies);
        assertEquals(List.of("a"), new ArrayList<>(registry.pendingReconcileIds()));
    }

    @Test
    void expiryCountsFromTheLastReMarkNotTheFirst() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);
        CoopNpcFleetSetSnapshot deferred = set(fleet("a", "corvus", "wolf"));
        registry.applySet(deferred, 7.5, 3000L);
        // The battle bridge renews the mark 30 s in; the deferred snapshot must survive the re-mark.
        registry.markPendingReconcile("a", 32_000L);

        registry.expirePendingReconcile(82_000L);
        assertEquals(1, mirror.snapshotApplies, "only 50 s since the re-mark");
        assertEquals(List.of("a"), new ArrayList<>(registry.pendingReconcileIds()));

        registry.expirePendingReconcile(92_000L);
        assertEquals(2, mirror.snapshotApplies);
        assertSame(deferred.fleets().get(0), mirror.lastSnapshot);
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void expiringAFreezeThatNeverSawASetJustClearsTheMark() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.expirePendingReconcile(
                2000L + CoopFleetMirrorRegistry.PENDING_RECONCILE_TIMEOUT_MILLIS);

        assertEquals(1, mirror.snapshotApplies, "nothing was deferred, so nothing is applied");
        assertFalse(mirror.disposed);
        assertTrue(registry.pendingReconcileIds().isEmpty());
    }

    @Test
    void aReconciledSetStillReleasesTheFreezeImmediately() {
        // Regression: the per-frame expiry must not have displaced the fast path.
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        FakeMirror mirror = creationOrder.get(0);
        registry.markPendingReconcile("a", 2000L);

        registry.applySet(set(fleet("a", "corvus", "lasher")), 0.0, 3000L);

        assertEquals(2, mirror.snapshotApplies);
        assertTrue(registry.pendingReconcileIds().isEmpty());

        // Nothing left to expire, and the expiry pass must not re-apply an old snapshot.
        registry.expirePendingReconcile(120_000L);
        assertEquals(2, mirror.snapshotApplies);
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

    // ---- diagnostics pre-check (perf audit #16) --------------------------------------------------

    @Test
    void theIdHashTracksTheMirroredSetWithoutBuildingIt() {
        CoopFleetMirrorRegistry registry = newRegistry();
        assertEquals(1, registry.fleetIdsHash(), "the empty registry hashes to the seed");

        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "corvus", "lasher")), 0.0, 1000L);
        int twoFleets = registry.fleetIdsHash();

        // Same population, resent: the pre-check must say "nothing to print".
        registry.applySet(set(fleet("a", "corvus", "wolf"), fleet("b", "corvus", "lasher")), 0.0, 2000L);
        assertEquals(twoFleets, registry.fleetIdsHash());

        // A roster change with no id change must not move it either — that is what the host's own
        // set hash is for; this only answers "are these the same mirrors".
        registry.applySet(set(fleet("a", "corvus", "hammerhead"), fleet("b", "corvus", "lasher")), 0.0, 3000L);
        assertEquals(twoFleets, registry.fleetIdsHash());

        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 4000L);
        assertNotEquals(twoFleets, registry.fleetIdsHash(), "a dropped mirror must change the hash");

        registry.disposeAll();
        assertEquals(1, registry.fleetIdsHash(), "teardown returns to the empty hash");
    }

    @Test
    void theIdHashDistinguishesASwapOfOneIdForAnother() {
        CoopFleetMirrorRegistry registry = newRegistry();
        registry.applySet(set(fleet("a", "corvus", "wolf")), 0.0, 1000L);
        int withA = registry.fleetIdsHash();

        registry.applySet(set(fleet("b", "corvus", "wolf")), 0.0, 2000L);

        assertEquals(1, registry.size(), "same size, different fleet");
        assertNotEquals(withA, registry.fleetIdsHash());
    }

    /** Phase 14b sensor identity fixture: profile + the three detected-range aggregates + strength. */
    private static CoopSensorSync.Profile sensors(float profile, float strength) {
        return new CoopSensorSync.Profile(profile, 0f, 0f, 1f, strength);
    }
}
