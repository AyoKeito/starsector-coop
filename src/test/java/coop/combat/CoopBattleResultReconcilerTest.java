package coop.combat;

import coop.fleet.CoopFleetSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBattleResultReconcilerTest {

    // ---- authoritative-set integration ------------------------------------------------------------

    @Test
    void destroyedFleetIdsAreDespawnedFromTheAuthoritativeSet() {
        FakeFleets fleets = new FakeFleets("fleet_alpha", "fleet_beta");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        assertTrue(reconciler.apply(result("battle-1", List.of("fleet_alpha"), List.of())));

        assertEquals(List.of("fleet_alpha"), fleets.despawned);
        assertTrue(fleets.rosters.isEmpty());
        assertEquals(1, fleets.rebroadcasts);
    }

    @Test
    void aDestroyedFleetTheHostNoLongerHasIsSkippedNotResurrected() {
        FakeFleets fleets = new FakeFleets("fleet_beta");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        reconciler.apply(result("battle-1", List.of("fleet_alpha"), List.of()));

        assertTrue(fleets.despawned.isEmpty());
    }

    @Test
    void survivorsGetTheirPostBattleRoster() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);
        List<CoopFleetSnapshot.Member> survivors = List.of(
                member("wolf", "wolf_Assault", 0.4f, 0.3f),
                member("lasher", "lasher_CS", 1f, 1f));

        reconciler.apply(result("battle-1", List.of(),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_alpha", survivors))));

        assertEquals(survivors, fleets.rosters.get("fleet_alpha"));
        assertTrue(fleets.despawned.isEmpty());
    }

    @Test
    void aSurvivorReportedWithNoShipsLeftIsTreatedAsDestroyed() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        reconciler.apply(result("battle-1", List.of(),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_alpha", List.of()))));

        assertEquals(List.of("fleet_alpha"), fleets.despawned);
        assertTrue(fleets.rosters.isEmpty());
    }

    @Test
    void anEmptyResultStillRebroadcastsAndRestartsPacing() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        reconciler.apply(result("battle-1", List.of(), List.of()));

        assertEquals(1, fleets.rebroadcasts);
        assertTrue(fleets.despawned.isEmpty());
    }

    @Test
    void everyInvolvedFleetGetsItsEngageCooldownRestarted() {
        FakeFleets fleets = new FakeFleets("fleet_alpha", "fleet_beta");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        reconciler.apply(result("battle-1", List.of("fleet_alpha"),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_beta",
                        List.of(member("wolf", "wolf_Assault", 1f, 1f))))));

        assertEquals(List.of("fleet_alpha", "fleet_beta"), fleets.cooldownRestarts);
    }

    // ---- idempotency ------------------------------------------------------------------------------

    @Test
    void aRedeliveredResultAppliesOnlyOnce() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);
        CoopBattleResult result = result("battle-1", List.of("fleet_alpha"), List.of());

        assertTrue(reconciler.apply(result));
        assertFalse(reconciler.apply(result));

        assertEquals(List.of("fleet_alpha"), fleets.despawned);
        assertEquals(1, fleets.rebroadcasts);
    }

    /**
     * The defect this pins: the ledger used to record the battle before touching a single fleet, and
     * a despawn the engine refused was swallowed with a warning, so {@code apply} returned true, the
     * pump counted the battle, and the resend was answered with "already applied" — leaving the
     * destroyed fleet alive on the host until an unrelated set change removed it.
     */
    @Test
    void aRefusedDespawnFailsTheApplyAndTheResendRetriesIt() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        fleets.failDespawnOf.add("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);
        CoopBattleResult result = result("battle-1", List.of("fleet_alpha"), List.of());

        assertFalse(reconciler.apply(result), "a despawn that did not happen is not a success");
        assertTrue(fleets.despawned.isEmpty());
        assertEquals(0, reconciler.seenBattleCount(), "the battle must leave the applied ledger");
        // The mirrors the guest froze are released either way.
        assertEquals(1, fleets.rebroadcasts);

        assertTrue(reconciler.apply(result), "the resend must be re-attempted, not deduplicated");
        assertEquals(List.of("fleet_alpha"), fleets.despawned);
        assertEquals(1, reconciler.seenBattleCount());
    }

    @Test
    void aRefusedRosterUpdateFailsTheApplyAndTheResendRetriesIt() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        fleets.failRosterOf.add("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);
        List<CoopFleetSnapshot.Member> survivors = List.of(member("wolf", "wolf_Assault", 0.4f, 0.3f));
        CoopBattleResult result = result("battle-1", List.of(),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_alpha", survivors)));

        assertFalse(reconciler.apply(result));
        assertTrue(fleets.rosters.isEmpty());
        assertEquals(0, reconciler.seenBattleCount());

        assertTrue(reconciler.apply(result));
        assertEquals(survivors, fleets.rosters.get("fleet_alpha"));
    }

    /** A fleet the first pass already despawned is skipped on the retry rather than despawned twice. */
    @Test
    void aRetryAfterAPartialApplyDoesNotRedoTheWorkThatLanded() {
        FakeFleets fleets = new FakeFleets("fleet_alpha", "fleet_beta");
        fleets.failDespawnOf.add("fleet_beta");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);
        CoopBattleResult result =
                result("battle-1", List.of("fleet_alpha", "fleet_beta"), List.of());

        assertFalse(reconciler.apply(result));
        assertEquals(List.of("fleet_alpha"), fleets.despawned);

        assertTrue(reconciler.apply(result));
        assertEquals(List.of("fleet_alpha", "fleet_beta"), fleets.despawned,
                "alpha was already gone, so the retry only had beta left to do");
    }

    /** A throwing implementation is a failure too, not a silently successful reconcile. */
    @Test
    void aThrowingMutationFailsTheApplyWithoutThrowingOutOfIt() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        fleets.throwOnDespawn = true;
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        assertFalse(reconciler.apply(result("battle-1", List.of("fleet_alpha"), List.of())));

        assertEquals(0, reconciler.seenBattleCount());
        assertEquals(1, fleets.rebroadcasts, "the rebroadcast still has to go out");
    }

    @Test
    void differentBattleIdsBothApply() {
        FakeFleets fleets = new FakeFleets("fleet_alpha", "fleet_beta");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        assertTrue(reconciler.apply(result("battle-1", List.of("fleet_alpha"), List.of())));
        assertTrue(reconciler.apply(result("battle-2", List.of("fleet_beta"), List.of())));

        assertEquals(List.of("fleet_alpha", "fleet_beta"), fleets.despawned);
    }

    @Test
    void theSeenBattleSetIsBounded() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < CoopBattleResultReconciler.SEEN_BATTLE_CAPACITY * 3; i++) {
            assertTrue(CoopBattleResultReconciler.remember(seen, "battle-" + i));
        }

        assertEquals(CoopBattleResultReconciler.SEEN_BATTLE_CAPACITY, seen.size());
        assertTrue(seen.contains("battle-" + (CoopBattleResultReconciler.SEEN_BATTLE_CAPACITY * 3 - 1)));
        assertFalse(seen.contains("battle-0"));
    }

    @Test
    void resetForgetsTheAppliedHistory() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);
        CoopBattleResult result = result("battle-1", List.of("fleet_alpha"), List.of());

        reconciler.apply(result);
        reconciler.reset();

        assertEquals(0, reconciler.seenBattleCount());
        assertTrue(reconciler.apply(result));
    }

    // ---- v1 reward rule ---------------------------------------------------------------------------

    /**
     * The solo fighter keeps 100% of its own spoils: the reconciler's whole surface is fleet
     * existence, rosters, the set rebroadcast and the engage cooldown. Nothing here can move credits,
     * XP or reputation between the two players.
     */
    @Test
    void theReconcilerNeverMovesSpoilsOrReputation() {
        FakeFleets fleets = new FakeFleets("fleet_alpha");
        CoopBattleResultReconciler reconciler = new CoopBattleResultReconciler(fleets);

        reconciler.apply(result("battle-1", List.of("fleet_alpha"), List.of()));

        assertEquals(List.of("exists", "despawn", "rebroadcastSet"), fleets.operationKinds());
        // The whole contract, so a future field cannot quietly add a money/rep channel.
        assertEquals(new java.util.TreeSet<>(List.of("exists", "despawn", "applySurvivingRoster",
                        "rebroadcastSet", "restartEngageCooldown")),
                declaredOperations());
    }

    // ---- roster multiset matching -----------------------------------------------------------------

    @Test
    void rosterDiffRemovesExactlyTheMissingVariants() {
        List<String> host = List.of(key("wolf_Assault"), key("wolf_Assault"), key("lasher_CS"));
        List<String> survivors = List.of(key("wolf_Assault"), key("lasher_CS"));

        assertEquals(List.of(1), CoopBattleResultReconciler.membersToRemove(host, survivors));
    }

    @Test
    void rosterDiffRemovesNothingWhenNobodyDied() {
        List<String> host = List.of(key("wolf_Assault"), key("lasher_CS"));

        assertEquals(List.of(), CoopBattleResultReconciler.membersToRemove(host, host));
    }

    @Test
    void rosterDiffNeverRemovesMoreShipsThanWereLost() {
        // The reported survivors carry variant ids that do not exist on the host at all (a custom
        // variant that failed to round-trip through the mirror). Two ships were lost, so at most two
        // may die here - the fleet must not be wiped.
        List<String> host = List.of(key("wolf_Assault"), key("wolf_Assault"), key("lasher_CS"),
                key("hammerhead_Balanced"));
        List<String> survivors = List.of(key("mystery_A"), key("mystery_B"));

        assertEquals(2, CoopBattleResultReconciler.membersToRemove(host, survivors).size());
    }

    @Test
    void rosterDiffFallsBackToHullIdWhenTheVariantIsUnknown() {
        assertEquals(CoopBattleResultReconciler.memberKey("wolf", ""),
                CoopBattleResultReconciler.memberKey("wolf", null));
        assertFalse(CoopBattleResultReconciler.memberKey("wolf", "wolf_Assault")
                .equals(CoopBattleResultReconciler.memberKey("wolf", "")));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static String key(String variantId) {
        return CoopBattleResultReconciler.memberKey("", variantId);
    }

    private static java.util.SortedSet<String> declaredOperations() {
        java.util.SortedSet<String> names = new java.util.TreeSet<>();
        for (java.lang.reflect.Method method
                : CoopBattleResultReconciler.AuthoritativeFleets.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private static CoopBattleResult result(String battleId, List<String> destroyed,
                                           List<CoopBattleResult.SurvivingFleet> survivors) {
        return new CoopBattleResult(battleId, "player-guest", "WIN", 4, destroyed, survivors);
    }

    private static CoopFleetSnapshot.Member member(String hullId, String variantId, float cr,
                                                   float hull) {
        return new CoopFleetSnapshot.Member(hullId + "-id", hullId, variantId, "", "", cr, hull);
    }

    /** Records every reconciler interaction; deliberately has no credits/XP/rep surface at all. */
    private static final class FakeFleets implements CoopBattleResultReconciler.AuthoritativeFleets {
        private final LinkedHashSet<String> present = new LinkedHashSet<>();
        private final List<String> despawned = new ArrayList<>();
        private final Map<String, List<CoopFleetSnapshot.Member>> rosters = new LinkedHashMap<>();
        private final List<String> cooldownRestarts = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();
        /** Ids whose next despawn/roster edit refuses; consumed on use, so the retry succeeds. */
        private final java.util.Set<String> failDespawnOf = new LinkedHashSet<>();
        private final java.util.Set<String> failRosterOf = new LinkedHashSet<>();
        private boolean throwOnDespawn;
        private int rebroadcasts;

        private FakeFleets(String... existing) {
            present.addAll(List.of(existing));
        }

        @Override
        public boolean exists(String coopFleetId) {
            operations.add("exists");
            return present.contains(coopFleetId);
        }

        @Override
        public boolean despawn(String coopFleetId) {
            operations.add("despawn");
            if (throwOnDespawn) {
                throw new IllegalStateException("engine refused the despawn");
            }
            if (failDespawnOf.remove(coopFleetId)) {
                // The engine refused: the fleet is still in the world. Same shape as EngineFleets
                // catching a throw out of CampaignFleetAPI.despawn.
                return false;
            }
            present.remove(coopFleetId);
            despawned.add(coopFleetId);
            return true;
        }

        @Override
        public boolean applySurvivingRoster(String coopFleetId,
                                            List<CoopFleetSnapshot.Member> survivors) {
            operations.add("applySurvivingRoster");
            if (failRosterOf.remove(coopFleetId)) {
                return false;
            }
            rosters.put(coopFleetId, survivors);
            return true;
        }

        @Override
        public void rebroadcastSet() {
            operations.add("rebroadcastSet");
            rebroadcasts++;
        }

        @Override
        public void restartEngageCooldown(String coopFleetId) {
            // Not in operationKinds(): the cooldown restart is pacing, not campaign state, and every
            // test that cares asserts on cooldownRestarts directly.
            cooldownRestarts.add(coopFleetId);
        }

        private List<String> operationKinds() {
            return List.copyOf(operations);
        }
    }
}
