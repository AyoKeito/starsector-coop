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

    private static final boolean TRANSPONDER_ON = true;
    private static final boolean TRANSPONDER_OFF = false;
    private static final boolean NO_BATTLE = false;
    private static final boolean IN_BATTLE = true;
    private static final boolean READY = true;
    private static final boolean COOLING = false;

    // ---- hostile handoff --------------------------------------------------------------------------

    @Test
    void hostileEngagePickerInsideTheTriggerDistanceIsHandedToTheGuest() {
        Action action = CoopNpcThreatWatcher.decide(
                hostile(true, 400f), TRANSPONDER_ON, NO_BATTLE, READY, READY, READY);

        assertEquals(Action.ENGAGE_GUEST, action);
    }

    @Test
    void hostileThatWouldNotEngageIsLeftAlone() {
        // pickEncounterOption is the engine's own decision function. If it says HOLD/DISENGAGE, the
        // watcher must not synthesize a fight vanilla itself would have refused.
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hostile(false, 100f), TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void hostileBeyondTheTriggerDistanceGetsAChaseInsteadOfAHandoff() {
        Action action = CoopNpcThreatWatcher.decide(
                hostile(true, 1500f), TRANSPONDER_ON, NO_BATTLE, READY, READY, READY);

        assertEquals(Action.INJECT_CHASE, action);
    }

    @Test
    void hostileBeyondTheChaseRangeIsIgnored() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hostile(true, 5000f), TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void handoffNeverFiresWhileACoopBattleIsAlreadyRunning() {
        // The shared combat pause serializes engagements: a second one would strand the spectator.
        Action action = CoopNpcThreatWatcher.decide(
                hostile(true, 100f), TRANSPONDER_ON, IN_BATTLE, READY, READY, READY);

        assertNotEquals(Action.ENGAGE_GUEST, action);
        assertEquals(Action.INJECT_CHASE, action);
    }

    @Test
    void handoffOnCooldownDegradesToAChase() {
        Action action = CoopNpcThreatWatcher.decide(
                hostile(true, 100f), TRANSPONDER_ON, NO_BATTLE, COOLING, READY, READY);

        assertEquals(Action.INJECT_CHASE, action);
    }

    @Test
    void bothThrottlesClosedMeansNothingHappens() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                hostile(true, 100f), TRANSPONDER_ON, NO_BATTLE, COOLING, COOLING, READY));
    }

    // ---- customs synthesis ------------------------------------------------------------------------

    @Test
    void nonHostilePatrolStopsTheTransponderOffGuest() {
        Action action = CoopNpcThreatWatcher.decide(
                patrol(300f), TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY);

        assertEquals(Action.CUSTOMS_DIALOG, action);
    }

    @Test
    void aTransponderOnGuestIsNotStopped() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(300f), TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aNonPatrolCivilianFleetNeverStopsTheGuest() {
        FleetView trader = new FleetView("f", "Trader", "independent",
                false, false, false, true, 100f);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                trader, TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void aDistantPatrolDoesNotStopTheGuest() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(1200f), TRANSPONDER_OFF, NO_BATTLE, READY, READY, READY));
    }

    @Test
    void customsIsSuppressedDuringACoopBattleAndByItsCooldown() {
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(100f), TRANSPONDER_OFF, IN_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                patrol(100f), TRANSPONDER_OFF, NO_BATTLE, READY, READY, COOLING));
    }

    // ---- general gates ----------------------------------------------------------------------------

    @Test
    void nonCombatFleetsAndUnresolvedDistancesAreSkipped() {
        FleetView station = new FleetView("f", "Station", "hegemony",
                true, true, false, false, 10f);
        FleetView elsewhere = new FleetView("f", "Ghost", "pirates",
                true, true, false, true, -1f);

        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                station, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                elsewhere, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
        assertEquals(Action.NONE, CoopNpcThreatWatcher.decide(
                null, TRANSPONDER_ON, NO_BATTLE, READY, READY, READY));
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
                CoopNpcThreatWatcher.cooldownKey("fleet-a", Action.INJECT_CHASE), 1500L, 5000L));
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

    // ---- threshold provenance ---------------------------------------------------------------------

    @Test
    void triggerDistancesKeepTheirSpikeDerivedOrdering() {
        // The handoff must sit well inside the chase-injection range, or the guest is dropped into a
        // fight before the chaser is even visibly pursuing. Observed closing speeds were 57-340 su/s.
        assertTrue(CoopNpcThreatWatcher.ENGAGE_TRIGGER_SU < CoopNpcThreatWatcher.CHASE_INJECT_SU);
        assertTrue(CoopNpcThreatWatcher.ENGAGE_TRIGGER_SU >= 400f);
        assertTrue(CoopNpcThreatWatcher.ENGAGE_TRIGGER_SU <= 700f);
    }

    private static FleetView hostile(boolean engagePick, float distance) {
        return new FleetView("fleet-a", "Raiders", "pirates", true, engagePick, false, true, distance);
    }

    private static FleetView patrol(float distance) {
        return new FleetView("fleet-p", "Fast Picket", "hegemony", false, false, true, true, distance);
    }
}
