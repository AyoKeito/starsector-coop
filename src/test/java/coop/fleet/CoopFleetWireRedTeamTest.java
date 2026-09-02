package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.MutableFleetStatsAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20 red-team regressions on the fleet wire codecs: non-finite floats (A10), a negative member
 * count (A15), and the observer-side sensor aggregates the motion range filter needs (C3).
 */
class CoopFleetWireRedTeamTest {

    private static CoopFleetSnapshot.Member member(String id) {
        return new CoopFleetSnapshot.Member(id, "wolf", "wolf_Assault", "Fang", "Vela", 0.8f, 0.9f);
    }

    private static CoopFleetSnapshot snapshot(CoopSensorSync.Profile sensors) {
        return CoopFleetSnapshot.create("p1", "Alice", "corvus", 10f, 20f, 1f, 2f, "player", true,
                sensors, List.of(member("m1")));
    }

    // ---- A10: NaN / Infinity ---------------------------------------------------------------------

    @Test
    void a10_aNonFinitePositionInATickIsRejectedRatherThanApplied() {
        String encoded = CoopFleetSnapshot.Tick.of(snapshot(
                new CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f))).encode();

        // A NaN x sets the mirror's coordinates to NaN, and the engine's own distance and camera
        // math propagates it for the rest of the session. Float.parseFloat accepts it happily.
        String poisoned = encoded.replaceFirst("\\|10\\.0\\|", "|NaN|");
        assertThrows(IllegalArgumentException.class, () -> CoopFleetSnapshot.Tick.decode(poisoned));

        String infinite = encoded.replaceFirst("\\|10\\.0\\|", "|Infinity|");
        assertThrows(IllegalArgumentException.class, () -> CoopFleetSnapshot.Tick.decode(infinite));
    }

    @Test
    void a10_aNonFiniteCrInAMemberRecordIsRejected() {
        String encoded = CoopFleetRoster.of(snapshot(CoopSensorSync.Profile.UNKNOWN)).encode();
        String poisoned = encoded.replace("|0.8|", "|NaN|");

        assertThrows(IllegalArgumentException.class, () -> CoopFleetRoster.decode(poisoned));
    }

    @Test
    void a10_aNonFiniteSensorMultInAMotionRecordIsRejected() {
        CoopNpcFleetMotion motion = new CoopNpcFleetMotion("f1", "loc", 1f, 2f, 3f, 4f,
                new CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f));
        String section = CoopNpcFleetMotion.encodeFullSection(List.of(motion));
        // The mult is pinned onto the mirror's StatBonus; a NaN there poisons the detection formula
        // for every observer, not just this fleet.
        String poisoned = section.replace("|1.0|", "|NaN|");

        assertThrows(IllegalArgumentException.class,
                () -> CoopNpcFleetMotion.decodeSection(poisoned, null));
    }

    @Test
    void a10_aNonFiniteFloatInAnNpcSetIsRejected() {
        CoopNpcFleetSnapshot fleet = new CoopNpcFleetSnapshot("f1", "pirates", "Raiders", "loc",
                1f, 2f, 3f, 4f, true, new CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f), "",
                "hash", List.of(member("m1")));
        String encoded = CoopNpcFleetSetSnapshot.create(List.of(fleet)).encode();
        String poisoned = encoded.replace("2.0", "Infinity");

        assertThrows(IllegalArgumentException.class, () -> CoopNpcFleetSetSnapshot.decode(poisoned));
    }

    // ---- A15: negative member counts -------------------------------------------------------------

    @Test
    void a15_aNegativeMemberCountIsRejectedByEveryRosterDecoder() {
        String roster = CoopFleetRoster.of(snapshot(CoopSensorSync.Profile.UNKNOWN)).encode();
        // The declared count sails past the "are there enough lines" check when it is negative, and
        // the exception a reader eventually gets names an ArrayList capacity instead of the field.
        String negativeRoster = roster.replaceFirst("\\|1\\n", "|-1\n");
        IllegalArgumentException rosterEx = assertThrows(IllegalArgumentException.class,
                () -> CoopFleetRoster.decode(negativeRoster));
        assertEquals("Negative roster member count: -1", rosterEx.getMessage());

        String full = snapshot(CoopSensorSync.Profile.UNKNOWN).encodeFull();
        String negativeFull = full.replaceFirst("\\|1\\n", "|-1\n");
        IllegalArgumentException fullEx = assertThrows(IllegalArgumentException.class,
                () -> CoopFleetSnapshot.decodeFull(negativeFull));
        assertEquals("Negative snapshot member count: -1", fullEx.getMessage());
    }

    // ---- C3: observer-side sensorRangeMod --------------------------------------------------------

    private static final CoopSensorSync.Profile BURSTING = new CoopSensorSync.Profile(
            650.5f, 120.5f, 25.5f, 0.875f, 419.5f, 5000f, 50f, 1.5f);

    @Test
    void c3_theObserverSideSensorRangeAggregatesRoundTripThroughEveryEncoder() {
        StringBuilder out = new StringBuilder();
        CoopSensorSync.append(out, BURSTING);
        List<String> fields = CoopFleetCodec.split(out.substring(1));

        assertEquals(CoopSensorSync.FIELD_COUNT, fields.size());
        assertEquals(BURSTING, CoopSensorSync.parse(fields, 0));

        // The full snapshot and the tick carry the same block, so a field-count mistake shows up as a
        // shifted fleetHash rather than as a wrong number.
        CoopFleetSnapshot snapshot = snapshot(BURSTING);
        assertEquals(BURSTING, CoopFleetSnapshot.decodeFull(snapshot.encodeFull()).sensors());
        assertEquals(BURSTING, CoopFleetSnapshot.Tick
                .decode(CoopFleetSnapshot.Tick.of(snapshot).encode()).sensors());
    }

    @Test
    void c3_theChangeMaskCoversTheObserverSideFieldsToo() {
        CoopSensorSync.Profile quiet = new CoopSensorSync.Profile(650.5f, 120.5f, 25.5f, 0.875f,
                419.5f, 0f, 0f, 1f);

        // A sensor burst is a sensorRangeMod change and nothing else. Before C3 the mask could not
        // express it at all, so the receiving mirror detected at the unmodified range - and the
        // host's motion range filter, which asks the observer for exactly that number, under-streamed
        // to a guest who could plainly see further.
        int mask = CoopSensorSync.changeMask(BURSTING, quiet);
        assertEquals(0b11100000, mask);

        StringBuilder out = new StringBuilder();
        CoopSensorSync.appendMasked(out, BURSTING, mask);
        List<String> fields = CoopFleetCodec.split(out.substring(1));
        assertEquals(BURSTING, CoopSensorSync.parseMasked(fields, 0, mask, quiet));
    }

    @Test
    void c3_aMotionRecordCarriesTheObserverSideFieldsWhenTheyMove() {
        CoopNpcFleetMotion quiet = new CoopNpcFleetMotion("f1", "loc", 1f, 2f, 3f, 4f,
                new CoopSensorSync.Profile(650.5f, 120.5f, 25.5f, 0.875f, 419.5f, 0f, 0f, 1f));
        CoopNpcFleetMotion bursting = new CoopNpcFleetMotion("f1", "loc", 5f, 6f, 7f, 8f, BURSTING);

        List<List<CoopNpcFleetMotion>> decoded = CoopNpcFleetMotion.decodeDatagram(List.of(
                CoopNpcFleetMotion.encodeFullSection(List.of(quiet)),
                CoopNpcFleetMotion.encodeDeltaSection(List.of(bursting), List.of(quiet))));

        assertEquals(BURSTING, decoded.get(1).get(0).sensors());
    }

    @Test
    void c3_captureReadsTheObserverSideModsAndApplyPinsThemOntoTheMirror() {
        com.fs.starfarer.api.combat.StatBonus sourceSensorRange =
                new com.fs.starfarer.api.combat.StatBonus();
        sourceSensorRange.modifyFlat("sensor_burst", 5000f, "Active sensor burst");
        sourceSensorRange.modifyMult("neutrino_detector", 1.5f, "Neutrino detector");

        CoopSensorSync.Profile captured = CoopSensorSync.capture(
                fleetWithStats(650f, 420f, new com.fs.starfarer.api.combat.StatBonus(),
                        sourceSensorRange, new com.fs.starfarer.api.combat.StatBonus()));

        assertEquals(5000f, captured.sensorRangeFlat(), 0.001f);
        assertEquals(0f, captured.sensorRangePercent(), 0.001f);
        assertEquals(1.5f, captured.sensorRangeMult(), 0.001f);

        // The mirror already carries a local mod of its own, exactly as it does for detectedRangeMod:
        // the correction is re-derived against what is natively there, never added on top of it.
        com.fs.starfarer.api.combat.StatBonus mirrorSensorRange =
                new com.fs.starfarer.api.combat.StatBonus();
        mirrorSensorRange.modifyMult("some_local_hullmod", 2f, "Local");
        CampaignFleetAPI mirror = fleetWithStats(650f, 420f,
                new com.fs.starfarer.api.combat.StatBonus(), mirrorSensorRange,
                new com.fs.starfarer.api.combat.StatBonus());

        assertTrue(CoopSensorSync.apply(mirror, captured));
        assertEquals(5000f, mirrorSensorRange.getFlatBonus(), 0.001f);
        assertEquals(1.5f, mirrorSensorRange.getMult(), 0.001f);

        // Idempotent on the steady path, which it has to be: this runs every frame.
        assertFalse(CoopSensorSync.applySensorRangeForTest(mirror, captured));
    }

    private static CampaignFleetAPI fleetWithStats(float sensorProfile, float sensorStrength,
                                                   com.fs.starfarer.api.combat.StatBonus detectedRange,
                                                   com.fs.starfarer.api.combat.StatBonus sensorRange,
                                                   com.fs.starfarer.api.combat.StatBonus strengthMod) {
        MutableFleetStatsAPI stats = (MutableFleetStatsAPI) java.lang.reflect.Proxy.newProxyInstance(
                MutableFleetStatsAPI.class.getClassLoader(),
                new Class<?>[]{MutableFleetStatsAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeStats";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorRangeMod" -> sensorRange;
                    case "getSensorStrengthMod" -> strengthMod;
                    default -> null;
                });
        return (CampaignFleetAPI) java.lang.reflect.Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> sensorProfile;
                    case "getSensorStrength" -> sensorStrength;
                    case "getDetectedRangeMod" -> detectedRange;
                    case "getStats" -> stats;
                    default -> null;
                });
    }
}
