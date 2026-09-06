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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2026-09-06 smoke observation O3: a mirror engaged the guest showing 0% combat readiness and
 * only snapped back to the host's real value once the host made contact with the fleet.
 *
 * <p>The path that produced it was a fail-quiet default. {@code CoopFleetSnapshotFactory} captured CR
 * as {@code readFloat(() -> member.getRepairTracker().getCR(), 0f)}, so any engine read that threw —
 * or any member whose <em>effective</em> CR read zero while its base CR was fine — put a literal
 * {@code 0} on the wire. The receiver could not tell that from a genuinely wrecked ship, wrote it
 * with {@code setCR(0)}, and then sat on it: {@code CoopFleetSnapshot#computeFleetHash} is
 * structural, so the same wrong value re-sends identically forever and only a change to the host
 * fleet's <em>roster</em> reopens the question. Host contact runs {@code inflateIfNeeded}, whose
 * autofit variant swap is exactly such a change — which is why contact "fixed" it.
 *
 * <p>These cover the three seams of the fix: what the sender puts on the wire when it cannot read,
 * what the receiver does with that sentinel on the creating path and the updating path, and the
 * invariant that the Phase 20 M4 range filter never gates per-member data.
 */
class CoopMirrorCombatReadinessTest {

    // ---- sender: what goes on the wire ----------------------------------------------------------

    @Test
    void aReadableCombatReadinessIsStreamedAsIs() {
        assertEquals(0.7f, CoopFleetSnapshotFactory.resolveCr(0.7f, 0.7f));
        assertEquals(0.31f, CoopFleetSnapshotFactory.resolveCr(0.31f, 0.62f));
    }

    @Test
    void aZeroEffectiveReadingDefersToANonZeroBaseReading() {
        // getCR() is baseCR * getCrewFraction(); setCR() on the receiver writes the base field. On an
        // AI-mode fleet -- which every NPC fleet is -- the two agree by definition, so a zero
        // effective reading against a healthy base one is a read artifact (an unseated fleetData
        // back-link, an unsynced CrewComposition), not a combat-ineffective ship.
        assertEquals(0.7f, CoopFleetSnapshotFactory.resolveCr(0f, 0.7f));
    }

    @Test
    void aGenuinelyZeroShipStillStreamsZero() {
        // The fix must not paper over a real 0-CR ship: the guest fights the mirror's members, so an
        // optimistic CR would hand it a fight the host's world does not agree with.
        assertEquals(0f, CoopFleetSnapshotFactory.resolveCr(0f, 0f));
        assertTrue(CoopFleetSnapshot.isKnownCr(CoopFleetSnapshotFactory.resolveCr(0f, 0f)));
    }

    @Test
    void anUnreadableCombatReadinessStreamsTheSentinelNotZero() {
        assertEquals(CoopFleetSnapshot.CR_UNKNOWN, CoopFleetSnapshotFactory.resolveCr(null, null));
        assertEquals(CoopFleetSnapshot.CR_UNKNOWN,
                CoopFleetSnapshotFactory.resolveCr(Float.NaN, Float.NaN));
        assertEquals(CoopFleetSnapshot.CR_UNKNOWN, CoopFleetSnapshotFactory.resolveCr(-2f, null));
        assertFalse(CoopFleetSnapshot.isKnownCr(CoopFleetSnapshot.CR_UNKNOWN));
    }

    @Test
    void aMemberWhoseRepairTrackerIsNullCapturesAsUnknown() {
        // FleetData.isInInvalidStateDueToGameLoadOrder exists precisely because a live member can
        // have a null repair tracker; the capture used to answer 0 for one of those.
        assertEquals(CoopFleetSnapshot.CR_UNKNOWN,
                CoopFleetSnapshotFactory.captureCr(memberWithTracker(null)));
    }

    @Test
    void aMemberWhoseEffectiveReadIsZeroCapturesItsBaseReading() {
        assertEquals(0.85f, CoopFleetSnapshotFactory.captureCr(
                memberWithTracker(tracker(0f, 0.85f, new ArrayList<>()))));
    }

    // ---- receiver: creating and updating a mirror ship -------------------------------------------

    @Test
    void aMirrorShipIsCreatedWithTheSendersCombatReadiness() {
        List<Float> written = new ArrayList<>();
        RepairTrackerAPI tracker = tracker(0.5f, 0.5f, written);

        assertTrue(CoopFleetMirror.writeCr(tracker, 0.72f));
        assertEquals(List.of(0.72f), written);
    }

    @Test
    void aMirrorShipCreatedWithoutCombatReadinessKeepsTheEnginesOwnValue() {
        // The whole defect: this used to be setCR(0) on a brand new ship. Leaving the engine default
        // in place lets the AI-mode mirror recover toward maxCR instead of sitting at 0% until the
        // host's structural hash happens to move.
        List<Float> written = new ArrayList<>();
        RepairTrackerAPI tracker = tracker(0.5f, 0.5f, written);

        assertFalse(CoopFleetMirror.writeCr(tracker, CoopFleetSnapshot.CR_UNKNOWN));
        assertTrue(written.isEmpty());
    }

    @Test
    void anUnknownReadingIsRefusedOnTheUpdatePathToo() {
        List<Float> written = new ArrayList<>();
        RepairTrackerAPI tracker = tracker(0.2f, 0.2f, written);

        assertFalse(CoopFleetMirror.writeCr(tracker, Float.NaN));
        assertFalse(CoopFleetMirror.writeCr(tracker, -0.5f));
        assertTrue(written.isEmpty());
    }

    // ---- receiver: the strength-invalidation gate ------------------------------------------------

    @Test
    void theFirstKnownReadingAlwaysFiresTheStrengthInvalidation() {
        // An unseated slot (NaN) is the "we do not know what this ship was" state, which is now
        // reachable with a live ship in it: one created while the sender could not read CR carries the
        // engine's default and drifts under AI-mode recovery. A rise from that must invalidate
        // cachedStrength even when the step is under the 0.005 epsilon.
        assertTrue(CoopFleetMirror.shouldInvalidateStrength(Float.NaN, 0.7f));
        assertTrue(CoopFleetMirror.shouldInvalidateStrength(Float.NaN, 0.0004f));
        assertEquals(0.0004f, CoopFleetMirror.nextCrReference(Float.NaN, 0.0004f));
    }

    @Test
    void aSeatedSlotStillFiltersSubEpsilonNoise() {
        assertFalse(CoopFleetMirror.shouldInvalidateStrength(0.7f, 0.7004f));
        assertTrue(CoopFleetMirror.shouldInvalidateStrength(0.7f, 0.71f));
        assertEquals(0.7f, CoopFleetMirror.nextCrReference(0.7f, 0.7004f));
    }

    // ---- wire + resend trigger -------------------------------------------------------------------

    @Test
    void theSentinelSurvivesTheNpcSetEncoding() {
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of(
                fleet("12486", 0f, 0f, member("m1", CoopFleetSnapshot.CR_UNKNOWN))));

        CoopNpcFleetSetSnapshot decoded = CoopNpcFleetSetSnapshot.decode(set.encode());

        assertEquals(CoopFleetSnapshot.CR_UNKNOWN, decoded.fleets().get(0).members().get(0).cr());
        assertFalse(CoopFleetSnapshot.isKnownCr(decoded.fleets().get(0).members().get(0).cr()));
    }

    @Test
    void learningARealReadingFlipsTheHealthHashSoTheSetIsResent() {
        // CR reaches the guest only through the rate-limited health trigger, so an unknown that later
        // becomes readable has to move computeHealthHash or the correction never leaves the host.
        List<CoopNpcFleetSnapshot> unknown = List.of(
                fleet("12486", 0f, 0f, member("m1", CoopFleetSnapshot.CR_UNKNOWN)));
        List<CoopNpcFleetSnapshot> known = List.of(
                fleet("12486", 0f, 0f, member("m1", 0.7f)));

        assertNotEquals(CoopNpcFleetSetSnapshot.computeHealthHash(unknown),
                CoopNpcFleetSetSnapshot.computeHealthHash(known));
        // ...while the structural hash does not move, so this is a health-trigger resend and not a
        // roster teardown on the guest.
        assertEquals(CoopNpcFleetSetSnapshot.computeSetHash(unknown),
                CoopNpcFleetSetSnapshot.computeSetHash(known));
    }

    // ---- range transition ------------------------------------------------------------------------

    @Test
    void aFleetOutsideTheMotionRangeStillShipsFullMemberData() {
        // The Phase 20 M4 filter gates NPC_FLEET_MOTION only; NPC_FLEET_SET carries every fleet's full
        // member list at any distance. A far -> near transition therefore needs no "full member send"
        // of its own, and cannot be what leaves a mirror without a CR.
        float radius = CoopNpcFleetReplicator.streamRadius(400f);
        assertFalse(CoopNpcFleetReplicator.withinRange(0f, 0f, 40_000f, 0f, radius));
        assertTrue(CoopNpcFleetReplicator.withinRange(0f, 0f, 100f, 0f, radius));

        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of(
                fleet("far", 40_000f, 0f, member("m1", 0.61f), member("m2", 0.83f)),
                fleet("near", 100f, 0f, member("m3", 0.7f))));

        CoopNpcFleetSetSnapshot decoded = CoopNpcFleetSetSnapshot.decode(set.encode());

        assertEquals(2, decoded.fleets().get(0).members().size());
        assertEquals(0.61f, decoded.fleets().get(0).members().get(0).cr());
        assertEquals(0.83f, decoded.fleets().get(0).members().get(1).cr());
        assertEquals(0.7f, decoded.fleets().get(1).members().get(0).cr());
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static CoopFleetSnapshot.Member member(String id, float cr) {
        return new CoopFleetSnapshot.Member(id, "hound", "hound_Standard", "ISS " + id, "", cr, 1f);
    }

    private static CoopNpcFleetSnapshot fleet(String coopFleetId, float x, float y,
                                              CoopFleetSnapshot.Member... members) {
        return CoopNpcFleetSnapshot.create(coopFleetId, "pirates", "Armada", "yma", x, y, 0f, 0f,
                false, CoopSensorSync.Profile.UNKNOWN, "travelling", List.of(members));
    }

    /** A {@link RepairTrackerAPI} that answers the two reads the capture makes and records writes. */
    private static RepairTrackerAPI tracker(float effective, float base, List<Float> written) {
        return (RepairTrackerAPI) Proxy.newProxyInstance(
                RepairTrackerAPI.class.getClassLoader(),
                new Class<?>[]{RepairTrackerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCR" -> effective;
                    case "getBaseCR" -> base;
                    case "setCR" -> {
                        written.add((Float) args[0]);
                        yield null;
                    }
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
