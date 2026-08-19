package coop.combat;

import coop.combat.CoopNpcThreatWatcher.Action;
import coop.combat.CoopNpcThreatWatcher.Cooldowns;
import coop.combat.CoopNpcThreatWatcher.FleetView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNpcThreatWatcherTest {

    private static final boolean VANILLA_MODEL = false;
    private static final boolean SYNTHESIZED_MODEL = true;
    private static final boolean TRANSPONDER_ON = true;
    private static final boolean TRANSPONDER_OFF = false;
    private static final boolean NO_BATTLE = false;
    private static final boolean IN_BATTLE = true;
    private static final boolean READY = true;
    private static final boolean COOLING = false;

    /** Contact for the fixtures below: two 150 su fleets plus the 100 su scan margin. */
    private static final float CONTACT = 400f;
    /** Vanilla's patrol pursuit patience with no burn bonus (StrategicModule:554-588). */
    private static final float PATROL_PATIENCE_DAYS = 3f;
    private static final boolean PURSUING = true;
    private static final boolean NOT_PURSUING = false;

    // ---- primary model: vanilla decides the hunt --------------------------------------------------

    @Test
    void aVanillaHuntThatHasClosedToContactIsHandedToTheGuest() {
        Action action = CoopNpcThreatWatcher.decide(
                hunting(CONTACT - 1f), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY);

        assertEquals(Action.ENGAGE_GUEST, action);
    }

    @Test
    void aVanillaHuntStillOutOfContactIsLeftToVanillaToRun() {
        // The chase itself is vanilla's setMoveDestination. Nothing is injected, and the guest is not
        // dropped into a fight it could still outrun — the locked 2026-08-19 decision.
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hunting(CONTACT + 1f), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hunting(3000f), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aHostileVanillaIsNotHuntingIsNeverHandedOffInTheVanillaModel() {
        // Sitting on top of the mirror is not consent: if TacticalModule has not targeted it, every
        // vanilla gate (visibility, isAllowedToEngage, patience, personality) said no.
        FleetView adjacent = hostile(10f, true, false, true, true, 0f);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                adjacent, VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void theEnginesOwnEncounterVerdictStillVetoesTheHandoff() {
        // TacticalModule also sets its target in the maintain-contact and evade branches. Only a fleet
        // that would actually take the fight gets one.
        FleetView shadowing = hostile(50f, true, true, false, true, 0f);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                shadowing, VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void theVanillaModelNeverSteersPursuitItself() {
        for (float distance : new float[]{CONTACT + 1f, 800f, 1900f}) {
            assertNotEquals(Action.STEER_PURSUIT, CoopNpcThreatWatcher.decide(
                    hunting(distance), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY),
                    "distance " + distance);
        }
    }

    @Test
    void handoffNeverFiresWhileACoopBattleIsAlreadyRunning() {
        // The shared combat pause serializes engagements: a second one would strand the spectator.
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hunting(100f), VANILLA_MODEL, TRANSPONDER_ON, IN_BATTLE, READY, READY, READY));
    }

    @Test
    void handoffOnCooldownDoesNothingInTheVanillaModel() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hunting(100f), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, COOLING, READY, READY));
    }

    // ---- fallback model: the watcher re-implements vanilla's gates --------------------------------

    @Test
    void theFallbackSteersPursuitWhenEveryVanillaGatePasses() {
        Action action = CoopNpcThreatWatcher.decide(
                fallbackChaser(1500f, 0f), SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE,
                READY, READY, READY);

        assertEquals(Action.STEER_PURSUIT, action);
    }

    @Test
    void theFallbackHandsOffOnceItReachesContact() {
        assertEquals(Action.ENGAGE_GUEST, CoopNpcThreatWatcher.decide(
                fallbackChaser(CONTACT - 1f, 0f), SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE,
                READY, READY, READY));
    }

    @Test
    void theFallbackRespectsTheVisibilityGate() {
        // A guest running dark is invisible; a chaser that cannot see it must not acquire it.
        FleetView blind = new FleetView("fleet-a", "Raiders", "pirates", true, true, false, true,
                false, false, true, 0f, 1.5f, 800f, CONTACT, NOT_PURSUING, 0f, 0);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                blind, SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void theFallbackRespectsIsAllowedToEngage() {
        // This is the do-not-attack tracker, the ignore flags and the assignment vetoes in one read.
        FleetView vetoed = new FleetView("fleet-a", "Raiders", "pirates", true, true, false, true,
                true, false, false, 0f, 1.5f, 800f, CONTACT, NOT_PURSUING, 0f, 0);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                vetoed, SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void theFallbackGivesUpWhenVanillasPursuitPatienceIsSpent() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                fallbackChaser(800f, 1.51f), SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE,
                READY, READY, READY));
        // ...and keeps going right up to the budget.
        assertEquals(Action.STEER_PURSUIT, CoopNpcThreatWatcher.decide(
                fallbackChaser(800f, 1.5f), SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE,
                READY, READY, READY));
    }

    @Test
    void theFallbackDoesNotAcquireFromAcrossTheSystem() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                fallbackChaser(CoopNpcThreatWatcher.PURSUIT_ACQUIRE_SU + 1f, 0f), SYNTHESIZED_MODEL,
                TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aHandoffOnCooldownStillLetsTheFallbackKeepChasing() {
        assertEquals(Action.STEER_PURSUIT, CoopNpcThreatWatcher.decide(
                fallbackChaser(100f, 0f), SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE,
                COOLING, READY, READY));
    }

    @Test
    void bothFallbackThrottlesClosedMeansNothingHappens() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                fallbackChaser(100f, 0f), SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE,
                COOLING, COOLING, READY));
    }

    // ---- customs: starting the inspection chase ---------------------------------------------------

    @Test
    void aPatrolThatDetectsTheDarkGuestStartsChasingItFromThere() {
        // The fix for "patrols only hail you if you nearly collide with them" (in-game, 2026-08-19):
        // detection is the whole start gate, so the chase begins wherever the patrol spotted the guest.
        Action action = CoopNpcThreatWatcher.decide(
                patrol(4000f, true), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY);

        assertEquals(Action.CUSTOMS_PURSUE, action);
    }

    @Test
    void aPatrolAlreadyOnTopOfTheDarkGuestSkipsStraightToTheStop() {
        assertEquals(Action.CUSTOMS_DIALOG, CoopNpcThreatWatcher.decide(
                patrol(CONTACT - 1f, true), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE,
                READY, READY, READY));
    }

    @Test
    void aPatrolThatCannotSeeTheDarkGuestDoesNotChaseOrHailIt() {
        // The Phase 14b stealth requirement in one assertion: no detection, no interest.
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(300f, false), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aTransponderOnGuestIsNeitherChasedNorStopped() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(300f, true), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(4000f, true), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aNonPatrolCivilianFleetNeverStopsTheGuest() {
        FleetView trader = new FleetView("f", "Trader", "independent", false, false, false, true,
                true, false, true, 0f, 1.5f, 100f, CONTACT, NOT_PURSUING, 0f, 0);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                trader, VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aFreshChaseIsSuppressedDuringACoopBattleAndByTheCustomsCooldown() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(2000f, true), VANILLA_MODEL, TRANSPONDER_OFF, IN_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(2000f, true), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, COOLING));
    }

    // ---- customs: sustaining and ending the chase -------------------------------------------------

    @Test
    void aChaseInFlightKeepsClosingWhileEveryGateHolds() {
        assertEquals(Action.CUSTOMS_PURSUE, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, true, 1.0f, 0), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE,
                READY, READY, COOLING));
    }

    @Test
    void aChaseInFlightIgnoresTheCustomsCooldownItsOwnEndWillStamp() {
        // The cooldown gates acquisition, not continuation: stamping it at the stop is what stops a
        // patrol re-hailing, so reading it mid-chase would cancel the chase the instant it started.
        assertEquals(Action.CUSTOMS_DIALOG, CoopNpcThreatWatcher.decide(
                chasingPatrol(CONTACT, true, 1.0f, 0), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE,
                READY, READY, COOLING));
    }

    @Test
    void aChaseEndsWhenVanillasPatrolPatienceIsSpent() {
        assertEquals(Action.CUSTOMS_PURSUE, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, true, PATROL_PATIENCE_DAYS, 0), VANILLA_MODEL, TRANSPONDER_OFF,
                NO_BATTLE, READY, READY, READY));
        assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, true, PATROL_PATIENCE_DAYS + 0.01f, 0), VANILLA_MODEL,
                TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aChaseSurvivesABriefContactLossAndEndsOnASustainedOne() {
        assertEquals(Action.CUSTOMS_PURSUE, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, false, 0.2f, CoopNpcThreatWatcher.CUSTOMS_UNSEEN_SCAN_LIMIT),
                VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, false, 0.2f, CoopNpcThreatWatcher.CUSTOMS_UNSEEN_SCAN_LIMIT + 1),
                VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void turningTheTransponderBackOnStandsThePatrolDown() {
        assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, true, 0.2f, 0), VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE,
                READY, READY, READY));
    }

    @Test
    void aCoopBattleEndsTheChaseRatherThanPausingIt() {
        assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                chasingPatrol(1200f, true, 0.2f, 0), VANILLA_MODEL, TRANSPONDER_OFF, IN_BATTLE,
                READY, READY, READY));
    }

    @Test
    void aChasingPatrolThatTurnsHostileReleasesItsAssignmentBeforeAnythingElseClaimsIt() {
        // The chase owns an INTERCEPT on the patrol. If the hostile branch could claim the fleet while
        // that assignment was still live, nothing would ever remove it — the permanent-siege shape.
        FleetView turned = new FleetView("fleet-p", "Fast Picket", "hegemony", true, true, true, true,
                true, true, true, 0f, PATROL_PATIENCE_DAYS, 50f, CONTACT, PURSUING, 0.2f, 0);

        assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                turned, VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aChaseWhoseQuarryBecameUnreadableIsStillCleanedUp() {
        assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                chasingPatrol(-1f, true, 0.2f, 0), VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE,
                READY, READY, READY));
    }

    @Test
    void everyGiveUpReasonIsNamedForTheLogLineTheSmokeGreps() {
        assertEquals("transponderOn", CoopNpcThreatWatcher.customsGiveUpReason(
                chasingPatrol(1200f, true, 0.2f, 0), TRANSPONDER_ON, NO_BATTLE));
        assertEquals("coopBattle", CoopNpcThreatWatcher.customsGiveUpReason(
                chasingPatrol(1200f, true, 0.2f, 0), TRANSPONDER_OFF, IN_BATTLE));
        assertEquals("lostContact", CoopNpcThreatWatcher.customsGiveUpReason(
                chasingPatrol(1200f, false, 0.2f, CoopNpcThreatWatcher.CUSTOMS_UNSEEN_SCAN_LIMIT + 1),
                TRANSPONDER_OFF, NO_BATTLE));
        assertEquals("outOfPatience", CoopNpcThreatWatcher.customsGiveUpReason(
                chasingPatrol(1200f, true, PATROL_PATIENCE_DAYS + 0.01f, 0),
                TRANSPONDER_OFF, NO_BATTLE));
        assertEquals("gone", CoopNpcThreatWatcher.customsGiveUpReason(
                chasingPatrol(-1f, true, 0.2f, 0), TRANSPONDER_OFF, NO_BATTLE));
        assertEquals("gone", CoopNpcThreatWatcher.customsGiveUpReason(null, TRANSPONDER_OFF, NO_BATTLE));
    }

    @Test
    void theReasonAlwaysMatchesAnActualGiveUpDecision() {
        // Guard against a give-up path acquiring a condition the reason function does not know about,
        // which would log "other" and leave the smoke test with nothing to grep.
        FleetView[] endings = {
                chasingPatrol(1200f, true, PATROL_PATIENCE_DAYS + 1f, 0),
                chasingPatrol(1200f, false, 0.2f, CoopNpcThreatWatcher.CUSTOMS_UNSEEN_SCAN_LIMIT + 5),
                chasingPatrol(-1f, true, 0.2f, 0),
        };
        for (FleetView ending : endings) {
            assertEquals(Action.CUSTOMS_GIVE_UP, CoopNpcThreatWatcher.decide(
                    ending, VANILLA_MODEL, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
            assertNotEquals("other",
                    CoopNpcThreatWatcher.customsGiveUpReason(ending, TRANSPONDER_OFF, NO_BATTLE));
        }
    }

    @Test
    void theChaseAssignmentIsBoundedWellInsideThePatienceItServes() {
        // If the watcher ever loses sight of a patrol the assignment is the only thing left to expire.
        assertTrue(CoopNpcThreatWatcher.CUSTOMS_PURSUIT_ASSIGNMENT_DAYS
                < CoopNpcThreatWatcher.PURSUIT_BUDGET_DAYS_PATROL);
        assertTrue(CoopNpcThreatWatcher.CUSTOMS_PURSUIT_ASSIGNMENT_DAYS > 0f);
    }

    // ---- general gates ----------------------------------------------------------------------------

    @Test
    void nonCombatFleetsAndUnresolvedDistancesAreSkipped() {
        FleetView station = new FleetView("f", "Station", "hegemony", true, true, false, false,
                true, true, true, 0f, 1.5f, 10f, CONTACT, NOT_PURSUING, 0f, 0);
        FleetView elsewhere = new FleetView("f", "Ghost", "pirates", true, true, false, true,
                true, true, true, 0f, 1.5f, -1f, CONTACT, NOT_PURSUING, 0f, 0);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                station, VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                elsewhere, VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                null, VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    // ---- contact threshold ------------------------------------------------------------------------

    @Test
    void contactIsTheSumOfBothRadiiPlusOneScanOfClosingSpeed() {
        // Vanilla measures fleet distance edge to edge (BaseCampaignEntity:1118-1120) and its own
        // "still engaged" test is dist <= combinedRadius (StrategicModule:519-538).
        assertEquals(400f, CoopNpcThreatWatcher.contactDistance(150f, 150f), 0.001f);
        assertEquals(CoopNpcThreatWatcher.CONTACT_MARGIN_SU,
                CoopNpcThreatWatcher.contactDistance(0f, 0f), 0.001f);
        // 340 su/s (the fastest chaser the spike clocked) x 250 ms = 85 su, rounded up to 100.
        assertTrue(CoopNpcThreatWatcher.CONTACT_MARGIN_SU
                >= 340f * (CoopNpcThreatWatcher.SCAN_INTERVAL_MILLIS / 1000f));
    }

    @Test
    void negativeRadiiCannotProduceAShorterThanMarginContact() {
        assertEquals(CoopNpcThreatWatcher.CONTACT_MARGIN_SU,
                CoopNpcThreatWatcher.contactDistance(-500f, -500f), 0.001f);
    }

    @Test
    void pursuitPatienceMatchesVanillasPatrolAndBurnTerms() {
        assertEquals(1.5f, CoopNpcThreatWatcher.pursuitBudgetDays(false, 0f), 0.001f);
        assertEquals(3.0f, CoopNpcThreatWatcher.pursuitBudgetDays(true, 0f), 0.001f);
        assertEquals(2.5f, CoopNpcThreatWatcher.pursuitBudgetDays(false, 10f), 0.001f);
        assertEquals(4.0f, CoopNpcThreatWatcher.pursuitBudgetDays(true, 10f), 0.001f);
    }

    // ---- post-defeat grace ------------------------------------------------------------------------

    @Test
    void thePostDefeatWindowStraddlesVanillasHalfToOneAndAHalfDays() {
        for (String id : new String[]{"fleet-a", "fleet-b", "", "0000", "zzzz"}) {
            float days = CoopNpcThreatWatcher.postDefeatGraceDays(id);
            assertTrue(days >= 0.5f && days <= 1.5f, id + " -> " + days);
        }
        assertEquals(0.5f, CoopNpcThreatWatcher.postDefeatGraceDays(null), 0.001f);
    }

    @Test
    void thePostDefeatWindowIsDeterministicPerFleet() {
        assertEquals(CoopNpcThreatWatcher.postDefeatGraceDays("fleet-a"),
                CoopNpcThreatWatcher.postDefeatGraceDays("fleet-a"), 0.0f);
    }

    @Test
    void aBattleEndQueuesTheDoNotAttackWindowForThatFleet() {
        CoopNpcThreatWatcher watcher = watcher();

        watcher.noteBattleConcluded("fleet-a", 1000L);

        assertTrue(watcher.isPostDefeatGracePending("fleet-a"));
        assertFalse(watcher.isPostDefeatGracePending("fleet-b"));
        assertEquals(0, watcher.graceAppliedCount());
    }

    @Test
    void resetForgetsQueuedDoNotAttackWindows() {
        CoopNpcThreatWatcher watcher = watcher();
        watcher.noteBattleConcluded("fleet-a", 1000L);

        watcher.reset();

        assertFalse(watcher.isPostDefeatGracePending("fleet-a"));
    }

    // ---- cooldown bookkeeping ---------------------------------------------------------------------

    @Test
    void cooldownBlocksUntilTheWindowElapses() {
        Cooldowns cooldowns = new Cooldowns();
        String key = CoopNpcThreatWatcher.cooldownKey("fleet-a", Action.ENGAGE_GUEST);

        assertTrue(cooldowns.isReady(key, 1000L, 5000L));
        cooldowns.mark(key, 1000L);
        assertFalse(cooldowns.isReady(key, 2000L, 5000L));
        assertFalse(cooldowns.isReady(key, 5999L, 5000L));
        assertTrue(cooldowns.isReady(key, 6000L, 5000L));
    }

    @Test
    void cooldownsAreIndependentPerFleetAndPerAction() {
        Cooldowns cooldowns = new Cooldowns();
        cooldowns.mark(CoopNpcThreatWatcher.cooldownKey("fleet-a", Action.ENGAGE_GUEST), 1000L);

        assertFalse(cooldowns.isReady(
                CoopNpcThreatWatcher.cooldownKey("fleet-a", Action.ENGAGE_GUEST), 1500L, 5000L));
        assertTrue(cooldowns.isReady(
                CoopNpcThreatWatcher.cooldownKey("fleet-a", Action.STEER_PURSUIT), 1500L, 5000L));
        assertTrue(cooldowns.isReady(
                CoopNpcThreatWatcher.cooldownKey("fleet-b", Action.ENGAGE_GUEST), 1500L, 5000L));
    }

    @Test
    void resetForgetsEveryThrottle() {
        Cooldowns cooldowns = new Cooldowns();
        cooldowns.mark("x", 1L);
        assertEquals(1, cooldowns.size());

        cooldowns.clear();

        assertEquals(0, cooldowns.size());
        assertTrue(cooldowns.isReady("x", 2L, 5000L));
    }

    // ---- Phase 15: cooldown restart at battle end -------------------------------------------------

    @Test
    void aBattleEndRestartsThatFleetsEngageCooldown() {
        CoopNpcThreatWatcher watcher = watcher();
        long battleEnd = 100_000L;

        watcher.noteBattleConcluded("fleet-a", battleEnd);

        assertFalse(watcher.isEngageReady("fleet-a", battleEnd));
        assertFalse(watcher.isEngageReady("fleet-a",
                battleEnd + CoopNpcThreatWatcher.ENGAGE_COOLDOWN_MILLIS - 1));
        assertTrue(watcher.isEngageReady("fleet-a",
                battleEnd + CoopNpcThreatWatcher.ENGAGE_COOLDOWN_MILLIS));
    }

    @Test
    void theRestartedClockRunsFromTheBattleEndNotTheHandoff() {
        // A fight lasting longer than the cooldown used to leave the beaten fleet already re-armed
        // the moment the guest came back, while its reconciliation was still in flight.
        CoopNpcThreatWatcher watcher = watcher();
        long handoff = 0L;
        long battleEnd = handoff + 90_000L;

        watcher.noteBattleConcluded("fleet-a", handoff);
        assertTrue(watcher.isEngageReady("fleet-a", battleEnd));

        watcher.noteBattleConcluded("fleet-a", battleEnd);

        assertFalse(watcher.isEngageReady("fleet-a", battleEnd + 1000L));
    }

    @Test
    void theCooldownBaseIsUnchangedVanillaPacing() {
        // Phase 15 restarts the clock; it does not lengthen it (memory: 15 s, tuned 2026-08-19).
        assertEquals(15000L, CoopNpcThreatWatcher.ENGAGE_COOLDOWN_MILLIS);
    }

    @Test
    void concludingAnUnknownOrBlankFleetIsANoOp() {
        CoopNpcThreatWatcher watcher = watcher();

        watcher.noteBattleConcluded("", 1000L);
        watcher.noteBattleConcluded(null, 1000L);

        assertTrue(watcher.isEngageReady("fleet-a", 1000L));
        assertFalse(watcher.isPostDefeatGracePending(""));
    }

    @Test
    void resetForgetsTheBattleEndRestart() {
        CoopNpcThreatWatcher watcher = watcher();
        watcher.noteBattleConcluded("fleet-a", 1000L);

        watcher.reset();

        assertTrue(watcher.isEngageReady("fleet-a", 1000L));
    }

    // ---- model routing ----------------------------------------------------------------------------

    @Test
    void theSameFleetRoutesDifferentlyUnderTheTwoModels() {
        // Vanilla is not hunting, but every synthesized gate passes: the fallback chases, the primary
        // model stays out of it. This is the flag's whole behavioural difference.
        FleetView notHunted = new FleetView("fleet-a", "Raiders", "pirates", true, true, false, true,
                true, false, true, 0f, 1.5f, 800f, CONTACT, NOT_PURSUING, 0f, 0);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                notHunted, VANILLA_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.STEER_PURSUIT, CoopNpcThreatWatcher.decide(
                notHunted, SYNTHESIZED_MODEL, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void theFallbackIsOffUnlessItsPropertyIsSet() {
        // Default OFF: the primary model ships live and the flag is the smoke test's escape hatch.
        assertFalse(Boolean.getBoolean(CoopNpcThreatWatcher.SYNTHESIZED_PURSUIT_PROPERTY));
    }

    private static CoopNpcThreatWatcher watcher() {
        // Neither the service nor the session is touched by the cooldown bookkeeping under test.
        return new CoopNpcThreatWatcher(new coop.net.CoopNetService(),
                new coop.session.CoopSessionState(), () -> 0L);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A hostile vanilla has actually targeted (primary-model trigger). */
    private static FleetView hunting(float distance) {
        return hostile(distance, true, true, true, true, 0f);
    }

    /** A hostile the fallback would chase: visible, allowed, patient, but not vanilla-targeted. */
    private static FleetView fallbackChaser(float distance, float pursuitDays) {
        return hostile(distance, true, false, true, true, pursuitDays);
    }

    private static FleetView hostile(float distance, boolean visible, boolean huntingMirror,
                                     boolean engagePick, boolean allowedToEngage, float pursuitDays) {
        return new FleetView("fleet-a", "Raiders", "pirates", true, engagePick, false, true,
                visible, huntingMirror, allowedToEngage, pursuitDays, 1.5f, distance, CONTACT,
                NOT_PURSUING, 0f, 0);
    }

    /** A patrol that has not started an inspection chase yet. */
    private static FleetView patrol(float distance, boolean visible) {
        return patrol(distance, visible, NOT_PURSUING, 0f, 0);
    }

    /** A patrol mid-chase: the give-up gates read the last three fields. */
    private static FleetView chasingPatrol(float distance, boolean visible, float pursuitDays,
                                           int unseenScans) {
        return patrol(distance, visible, PURSUING, pursuitDays, unseenScans);
    }

    private static FleetView patrol(float distance, boolean visible, boolean pursuing,
                                    float pursuitDays, int unseenScans) {
        return new FleetView("fleet-p", "Fast Picket", "hegemony", false, false, true, true,
                visible, false, true, 0f, PATROL_PATIENCE_DAYS, distance, CONTACT,
                pursuing, pursuitDays, unseenScans);
    }
}
