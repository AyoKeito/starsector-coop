package coop.fleet;

import com.fs.starfarer.api.campaign.FleetAssignment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link CoopNpcActionTextCapture#resolve} — the pure half of the Phase 9b capture — through the
 * branches the vanilla tooltip resolution has. No engine: the {@link CoopNpcActionTextCapture.View}
 * seam is exactly what lets these run headless.
 */
class CoopNpcActionTextCaptureTest {

    private static final String HOST_LABEL = "Bob";

    private static CoopNpcActionTextCapture.View ai() {
        CoopNpcActionTextCapture.View view = new CoopNpcActionTextCapture.View();
        view.hasAi = true;
        view.hostPlayerLabel = HOST_LABEL;
        return view;
    }

    private static CoopNpcActionTextCapture.View assignment(FleetAssignment assignment) {
        CoopNpcActionTextCapture.View view = ai();
        view.hasAssignment = true;
        view.assignment = assignment;
        return view;
    }

    // ---- Assignment-derived text ----------------------------------------------------------------

    @Test
    void assignmentActionTextIsUsedVerbatim() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.GO_TO_LOCATION);
        view.assignmentActionText = "travelling to Jangala";

        assertEquals("travelling to Jangala", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void assignmentWithoutActionTextFallsBackToTheDescriptionPlusTargetName() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.RESUPPLY);
        view.assignmentTarget = CoopNpcActionTextCapture.Target.entity("Jangala");

        assertEquals("resupplying at Jangala", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void assignmentThatDoesNotAddATargetNameStopsAtTheDescription() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.assignmentTarget = CoopNpcActionTextCapture.Target.entity("Corvus");

        assertEquals("patrolling system", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void assignmentTargetThatIsTheGuestMirrorReadsAsYourFleet() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.DELIVER_SUPPLIES);
        view.assignmentTarget = CoopNpcActionTextCapture.Target.guestMirror("Alice");

        assertEquals("delivering supplies to your fleet", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void assignmentTargetThatIsTheHostPlayerReadsAsTheHostsLabel() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.DELIVER_SUPPLIES);
        view.assignmentTarget = CoopNpcActionTextCapture.Target.hostPlayer();

        assertEquals("delivering supplies to " + HOST_LABEL, CoopNpcActionTextCapture.resolve(view));
    }

    // ---- Pursuit --------------------------------------------------------------------------------

    @Test
    void busyFleetWithAVisibleTargetIsPursuingIt() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.tacticalTarget = CoopNpcActionTextCapture.Target.fleet("Hegemony Patrol");

        assertEquals("pursuing Hegemony Patrol", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void pursuingTheGuestMirrorReadsAsYourFleet() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.tacticalTarget = CoopNpcActionTextCapture.Target.guestMirror("Alice");

        assertEquals("pursuing your fleet", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void pursuingTheHostPlayerNamesTheHostInsteadOfSayingYourFleet() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.tacticalTarget = CoopNpcActionTextCapture.Target.hostPlayer();

        assertEquals("pursuing " + HOST_LABEL, CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void maintainingContactReplacesThePursuitVerb() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.maintainingContact = true;
        view.tacticalTarget = CoopNpcActionTextCapture.Target.guestMirror("Alice");

        assertEquals("maintaining contact with your fleet", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void aStationTargetIsEngagedNotPursued() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.tacticalTarget = CoopNpcActionTextCapture.Target.fleet("Orbital Station").asStation();

        assertEquals("engaging Orbital Station", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void anInterceptTargetThatIsNoLongerVisibleBecomesLookingFor() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.INTERCEPT);
        view.tacticalTarget = CoopNpcActionTextCapture.Target.fleet("Pirate Raiders").notVisible();

        assertEquals("looking for Pirate Raiders", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void anInterceptTargetThatIsStillVisibleUsesTheAssignmentWording() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.INTERCEPT);
        view.tacticalTarget = CoopNpcActionTextCapture.Target.guestMirror("Alice");
        view.assignmentTarget = CoopNpcActionTextCapture.Target.guestMirror("Alice");

        assertEquals("intercepting your fleet", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void anUnnamedInvisibleTargetDegradesToUnknownLocation() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.INTERCEPT);
        view.tacticalTarget = new CoopNpcActionTextCapture.Target(
                "", "", true, false, false, false, false);

        assertEquals("looking for unknown location", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void aPriorityTargetStandsInWhenTheTacticalModuleHasNone() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.priorityTarget = CoopNpcActionTextCapture.Target.fleet("Luddic Pilgrims");

        assertEquals("pursuing Luddic Pilgrims", CoopNpcActionTextCapture.resolve(view));
    }

    // ---- Fleeing --------------------------------------------------------------------------------

    @Test
    void fleeingNamesTheLargestEnemy() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.fleeing = true;
        view.largestEnemy = CoopNpcActionTextCapture.Target.fleet("Pirate Armada");

        assertEquals("running from Pirate Armada", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void fleeingFromTheGuestMirrorReadsAsYourFleet() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.fleeing = true;
        view.largestEnemy = CoopNpcActionTextCapture.Target.guestMirror("Alice");

        assertEquals("running from your fleet", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void halfheartedAvoidanceSoftensTheFleeingWording() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.fleeing = true;
        view.avoidingPlayerHalfheartedly = true;
        view.largestEnemy = CoopNpcActionTextCapture.Target.guestMirror("Alice");

        assertEquals("avoiding contact", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void fleeingFromTheHostPlayerNamesTheHost() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.fleeing = true;
        view.largestEnemy = CoopNpcActionTextCapture.Target.hostPlayer();

        assertEquals("running from " + HOST_LABEL, CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void fleeingAStationIsAvoidingWhenCloseAndDisengagingWhenFar() {
        CoopNpcActionTextCapture.View near = assignment(FleetAssignment.PATROL_SYSTEM);
        near.busy = true;
        near.fleeing = true;
        near.largestEnemy = CoopNpcActionTextCapture.Target.fleet("Sindria Station").asStation();
        near.distanceToLargestEnemy = 500f;

        CoopNpcActionTextCapture.View far = assignment(FleetAssignment.PATROL_SYSTEM);
        far.busy = true;
        far.fleeing = true;
        far.largestEnemy = CoopNpcActionTextCapture.Target.fleet("Sindria Station").asStation();
        far.distanceToLargestEnemy = 4000f;

        assertEquals("avoiding Sindria Station", CoopNpcActionTextCapture.resolve(near));
        assertEquals("disengaging from Sindria Station", CoopNpcActionTextCapture.resolve(far));
    }

    // ---- Overrides, battle, and the empty case --------------------------------------------------

    @Test
    void anActionTextOverrideWinsOverTheAssignment() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.RESUPPLY);
        view.assignmentTarget = CoopNpcActionTextCapture.Target.entity("Jangala");
        view.actionTextOverride = "waiting for orders";

        assertEquals("waiting for orders", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void anActionTextProviderIsUsedWhenThereIsNoOverride() {
        CoopNpcActionTextCapture.View view = ai();
        view.providerActionText = "scavenging the debris field";

        assertEquals("scavenging the debris field", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void abyssalHyperspaceAvoidanceOverridesTheRest() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.GO_TO_LOCATION);
        view.assignmentActionText = "travelling to Jangala";
        view.avoidingAbyssalHyperspace = true;

        assertEquals("avoiding abyssal hyperspace", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void aFleetInABattleIsEngagedInBattleWhateverElseItWasDoing() {
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.PATROL_SYSTEM);
        view.busy = true;
        view.tacticalTarget = CoopNpcActionTextCapture.Target.guestMirror("Alice");
        view.inBattle = true;

        assertEquals("engaged in battle", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void anIdleFleetHasNoActionText() {
        assertEquals("", CoopNpcActionTextCapture.resolve(ai()));
    }

    @Test
    void aFleetWithoutAModularAiUsesItsNullAiActionText() {
        CoopNpcActionTextCapture.View view = new CoopNpcActionTextCapture.View();
        view.nullAiActionText = "derelict";

        assertEquals("derelict", CoopNpcActionTextCapture.resolve(view));
    }

    @Test
    void aFleetWithoutAModularAiAndNoTextIsEmpty() {
        assertEquals("", CoopNpcActionTextCapture.resolve(new CoopNpcActionTextCapture.View()));
    }

    // ---- Sanitizing -----------------------------------------------------------------------------

    @Test
    void overlongTextIsCappedAndStillAPrefixOfTheOriginal() {
        String verbose = "travelling to a place with a very long name that nobody would ever type"
                + " into a fleet assignment but which must not be allowed to inflate the set message";
        CoopNpcActionTextCapture.View view = assignment(FleetAssignment.GO_TO_LOCATION);
        view.assignmentActionText = verbose;

        String resolved = CoopNpcActionTextCapture.resolve(view);
        assertTrue(resolved.length() <= CoopNpcActionTextCapture.MAX_LENGTH,
                "capped at " + CoopNpcActionTextCapture.MAX_LENGTH + ", was " + resolved.length());
        assertTrue(resolved.length() > CoopNpcActionTextCapture.MAX_LENGTH - 20, "not over-trimmed");
        assertTrue(verbose.startsWith(resolved));
    }

    @Test
    void newlinesAreFlattenedAndNullBecomesEmpty() {
        assertEquals("line one line two", CoopNpcActionTextCapture.sanitize("line one\nline two"));
        assertEquals("line one line two", CoopNpcActionTextCapture.sanitize("line one\rline two"));
        assertEquals("trailing", CoopNpcActionTextCapture.sanitize("trailing\n"));
        assertEquals("", CoopNpcActionTextCapture.sanitize(null));
    }
}
