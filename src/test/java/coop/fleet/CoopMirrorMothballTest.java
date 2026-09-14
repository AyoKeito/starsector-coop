package coop.fleet;

import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding S4-B (2026-09-13 smoke): a trade fleet's mirrored ships showed in the guest's fleet tooltip
 * as ordinary warships at 0% CR, where vanilla shows them mothballed.
 *
 * <p>They are not a CR defect. {@code EconomyFleetAssignmentAI.syncMothballedShips} carries a trade
 * fleet's "ship hulls" cargo as real fleet members and mothballs them, and
 * {@code RepairTracker.setMothballed(true)} zeroes the member's CR as part of doing so — so the host
 * captured a perfectly truthful 0, the wire carried it faithfully, and the mirror rebuilt a combat
 * ship out of it because the one field that explains the zero was not on the wire at all.
 *
 * <p>Covered here: the sender's read, the wire in both directions (including the pre-field shape), the
 * receiver's write and its ordering against the CR write, and the structural-hash decision.
 */
class CoopMirrorMothballTest {

    // ---- sender ---------------------------------------------------------------------------------

    @Test
    void theMothballedFlagIsReadOffTheRepairTracker() {
        assertTrue(CoopFleetSnapshotFactory.captureMothballed(memberWithTracker(tracker(true))));
        assertFalse(CoopFleetSnapshotFactory.captureMothballed(memberWithTracker(tracker(false))));
    }

    @Test
    void aMemberWithNoRepairTrackerCapturesAsNotMothballed() {
        // The load-order window FleetData.isInInvalidStateDueToGameLoadOrder exists for. False is not
        // a guess dressed up as data: it is the state the receiver's freshly built ship is in anyway,
        // and the same unreadable tracker already streams CR_UNKNOWN, which the receiver logs.
        assertFalse(CoopFleetSnapshotFactory.captureMothballed(memberWithTracker(null)));
        assertEquals(CoopFleetSnapshot.CR_UNKNOWN,
                CoopFleetSnapshotFactory.captureCr(memberWithTracker(null)));
    }

    // ---- wire -----------------------------------------------------------------------------------

    @Test
    void theFlagSurvivesTheFullSnapshotEncoding() {
        CoopFleetSnapshot snapshot = snapshot(
                member("m1", false), member("m2", true));

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());

        assertFalse(decoded.members().get(0).mothballed());
        assertTrue(decoded.members().get(1).mothballed());
        assertEquals(snapshot.fleetHash(), decoded.fleetHash());
    }

    @Test
    void theFlagSurvivesTheRosterHalfAndTheNpcSetEncoding() {
        // The roster half is the one that matters in practice: mothballed is structural, so it rides
        // the TCP roster (player mirror) and the NPC set, never the 10 Hz tick.
        CoopFleetRoster roster = CoopFleetRoster.of(snapshot(member("m1", true), member("m2", false)));
        CoopFleetRoster decodedRoster = CoopFleetRoster.decode(roster.encode());
        assertTrue(decodedRoster.members().get(0).mothballed());
        assertFalse(decodedRoster.members().get(1).mothballed());

        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of(
                npcFleet("12486", member("m1", true), member("m2", false))));
        CoopNpcFleetSetSnapshot decodedSet = CoopNpcFleetSetSnapshot.decode(set.encode());
        assertTrue(decodedSet.fleets().get(0).members().get(0).mothballed());
        assertFalse(decodedSet.fleets().get(0).members().get(1).mothballed());
    }

    @Test
    void aMemberRecordWithoutTheFlagDecodesAsNotMothballed() {
        // Both peers always run the same build (the COOP-GAME handshake check), so this shape cannot
        // appear in a live session -- but the same parser reads recorded bodies and wiretap fixtures,
        // and a missing trailing field has exactly one reading.
        List<String> preMothball = CoopFleetCodec.split(
                "m1|wolf|wolf_Assault|ISS Fang||1.0|1.0|||");

        assertEquals(CoopFleetCodec.MEMBER_FIELD_COUNT_PRE_MOTHBALL, preMothball.size());
        assertFalse(CoopFleetCodec.parseMember(preMothball).mothballed());
    }

    @Test
    void aMemberRecordThatIsNeitherShapeIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopFleetCodec.parseMember(CoopFleetCodec.split("m1|wolf|wolf_Assault")));
        assertThrows(IllegalArgumentException.class, () -> CoopFleetCodec.parseMember(
                CoopFleetCodec.split("m1|wolf|wolf_Assault|ISS Fang||1.0|1.0||||1|extra")));
        // ...while the 11-field shape one field short of it is what this build emits.
        assertTrue(CoopFleetCodec.parseMember(
                CoopFleetCodec.split("m1|wolf|wolf_Assault|ISS Fang||1.0|1.0||||1")).mothballed());
    }

    @Test
    void theTickCarriesNoMothballState() {
        // Structural fields do not belong in the 10 Hz datagram; the tick is CR and hull only.
        CoopFleetSnapshot snapshot = snapshot(member("m1", true), member("m2", false));

        CoopFleetSnapshot.Tick tick = CoopFleetSnapshot.Tick.decode(
                CoopFleetSnapshot.Tick.of(snapshot).encode());

        assertEquals(2, tick.members().size());
        assertEquals(snapshot.fleetHash16(), tick.fleetHash16());
    }

    // ---- hash -----------------------------------------------------------------------------------

    @Test
    void mothballingIsStructuralSoTheRosterIsRebuilt() {
        // The rebuild is the only path that applies member state other than CR and hull, so a flag
        // outside this hash would reach an already-built mirror never.
        String afloat = CoopFleetSnapshot.computeFleetHash(List.of(member("m1", false)));
        String mothballed = CoopFleetSnapshot.computeFleetHash(List.of(member("m1", true)));

        assertNotEquals(afloat, mothballed);
        assertNotEquals(CoopNpcFleetSetSnapshot.computeSetHash(
                        List.of(npcFleet("12486", member("m1", false)))),
                CoopNpcFleetSetSnapshot.computeSetHash(
                        List.of(npcFleet("12486", member("m1", true)))));
    }

    @Test
    void theCanonicalOrderStillSeparatesOtherwiseIdenticalMembers() {
        // canonicalOrderIndexes pairs the tick's per-ship state onto the roster, so its comparator has
        // to look at every field the hash does or two sides could order the same roster differently.
        List<CoopFleetSnapshot.Member> members = List.of(member("m1", true), member("m1", false));

        int[] order = CoopFleetSnapshot.canonicalOrderIndexes(members);

        assertEquals(2, order.length);
        assertFalse(members.get(order[0]).mothballed());
        assertTrue(members.get(order[1]).mothballed());
    }

    // ---- receiver -------------------------------------------------------------------------------

    @Test
    void aMothballedMemberIsMothballedOnTheMirror() {
        StatefulTracker tracker = new StatefulTracker();

        assertTrue(CoopFleetMirror.writeMothballed(tracker.api(), true));
        assertTrue(tracker.mothballed);
    }

    @Test
    void anAfloatMemberIsLeftAloneRatherThanUnMothballed() {
        // setMothballed(false) restores crPriorToMothballing, so calling it on a brand-new ship would
        // be a CR write disguised as a no-op. The ship is created un-mothballed; there is nothing to
        // clear, and a member that stops being mothballed arrives as a fresh build anyway.
        StatefulTracker tracker = new StatefulTracker();

        assertFalse(CoopFleetMirror.writeMothballed(tracker.api(), false));
        assertTrue(tracker.calls.isEmpty());
    }

    @Test
    void aMemberWithNoTrackerIsReportedRatherThanCrashingTheRebuild() {
        assertFalse(CoopFleetMirror.writeMothballed(null, true));
    }

    @Test
    void theCombatReadinessIsWrittenBeforeTheMothballSoTheShipLandsAtZero() {
        // Vanilla's setMothballed(true) moves cr into crPriorToMothballing and zeroes cr. Run in the
        // other order the mirror would end up mothballed *and* carrying a live CR, and un-mothballing
        // it would restore the wrong one. The sender captures 0 for a mothballed ship, so in this
        // order the two applies agree instead of fighting.
        StatefulTracker tracker = new StatefulTracker();

        CoopFleetMirror.writeCr(tracker.api(), 0f);
        CoopFleetMirror.writeMothballed(tracker.api(), true);

        assertEquals(List.of("setCR", "setMothballed"), tracker.calls);
        assertEquals(0f, tracker.cr);
        assertEquals(0f, tracker.crPriorToMothballing);
        assertTrue(tracker.mothballed);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static CoopFleetSnapshot.Member member(String id, boolean mothballed) {
        return new CoopFleetSnapshot.Member(id, "wolf", "wolf_Assault", "ISS " + id, "",
                mothballed ? 0f : 0.7f, 1f, "", "", "", mothballed);
    }

    private static CoopFleetSnapshot snapshot(CoopFleetSnapshot.Member... members) {
        return CoopFleetSnapshot.create("p1", "Alice", "yma", 10f, 20f, 0f, 0f, "player", true,
                CoopSensorSync.Profile.UNKNOWN, List.of(members));
    }

    private static CoopNpcFleetSnapshot npcFleet(String coopFleetId,
                                                 CoopFleetSnapshot.Member... members) {
        return CoopNpcFleetSnapshot.create(coopFleetId, "independent", "Trade Fleet", "yma",
                0f, 0f, 0f, 0f, true, CoopSensorSync.Profile.UNKNOWN, "travelling", List.of(members));
    }

    /** A {@link RepairTrackerAPI} that models vanilla's mothball/CR coupling and records its calls. */
    private static final class StatefulTracker {
        final List<String> calls = new ArrayList<>();
        float cr = 0.7f;
        float crPriorToMothballing;
        boolean mothballed;

        RepairTrackerAPI api() {
            return (RepairTrackerAPI) Proxy.newProxyInstance(
                    RepairTrackerAPI.class.getClassLoader(),
                    new Class<?>[]{RepairTrackerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCR", "getBaseCR" -> cr;
                        case "setCR" -> {
                            calls.add("setCR");
                            cr = (Float) args[0];
                            yield null;
                        }
                        case "isMothballed" -> mothballed;
                        case "setMothballed" -> {
                            calls.add("setMothballed");
                            boolean next = (Boolean) args[0];
                            if (next != mothballed) {
                                // RepairTracker.setMothballed, verbatim.
                                if (next) {
                                    crPriorToMothballing = cr;
                                    cr = 0f;
                                } else {
                                    cr = crPriorToMothballing;
                                }
                            }
                            mothballed = next;
                            yield null;
                        }
                        case "toString" -> "RepairTracker";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /** A read-only tracker that answers {@code isMothballed()} and nothing else of interest. */
    private static RepairTrackerAPI tracker(boolean mothballed) {
        return (RepairTrackerAPI) Proxy.newProxyInstance(
                RepairTrackerAPI.class.getClassLoader(),
                new Class<?>[]{RepairTrackerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isMothballed" -> mothballed;
                    case "toString" -> "RepairTracker";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A member whose {@code getRepairTracker()} answers {@code tracker} — null models the load window. */
    private static FleetMemberAPI memberWithTracker(RepairTrackerAPI tracker) {
        return (FleetMemberAPI) Proxy.newProxyInstance(
                FleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{FleetMemberAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRepairTracker" -> tracker;
                    case "toString" -> "FleetMember";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
