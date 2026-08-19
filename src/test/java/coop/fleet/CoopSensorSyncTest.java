package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.StatBonus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the values that decide whether a mirror is detected, identified, or invisible.
 *
 * <p>The engine computes {@code range = (targetProfile + observerStrength) * targetDetectedRangeMod *
 * observerSensorRangeMod} and buckets the edge-to-edge distance against it: faction details inside
 * {@code max(0.1 x range, 50)} (or the whole range when the target's transponder is on), grey
 * composition-only inside {@code 0.5 x range}, a question-mark sensor contact inside {@code range}.
 * Phase 14b replicates the profile and the three {@code detectedRangeMod} aggregates verbatim rather
 * than folding them together, because folding double-counted the receiving client's own terrain.
 */
class CoopSensorSyncTest {

    @Test
    void captureTakesTheProfileAndTheThreeDetectedRangeAggregatesVerbatim() {
        StatBonus mod = new StatBonus();
        mod.modifyFlat("transponder_ability_mod", 1000f, "Transponder on");
        mod.modifyPercent("sustained_burn_ability_mod", 100f, "Sustained burn");
        mod.modifyMult("go_dark_ability_mod", 0.5f, "Going dark");

        CoopSensorSync.Profile profile = CoopSensorSync.capture(fleet(650f, 420f, mod));

        assertEquals(650f, profile.sensorProfile(), 0.001f);
        assertEquals(1000f, profile.detectedRangeFlat(), 0.001f);
        assertEquals(100f, profile.detectedRangePercent(), 0.001f);
        assertEquals(0.5f, profile.detectedRangeMult(), 0.001f);
        assertEquals(420f, profile.sensorStrength(), 0.001f);
        assertTrue(profile.isKnown());
    }

    @Test
    void aFleetWithNoDetectedRangeBonusesCapturesTheIdentityAggregates() {
        CoopSensorSync.Profile profile = CoopSensorSync.capture(fleet(650f, 420f, new StatBonus()));

        assertEquals(0f, profile.detectedRangeFlat(), 0.001f);
        assertEquals(0f, profile.detectedRangePercent(), 0.001f);
        assertEquals(1f, profile.detectedRangeMult(), 0.001f);
    }

    @Test
    void captureNeverConsultsTheFleetAsAnObserver() {
        // The regression guard. This value used to be derived through
        // fleet.getMaxSensorRangeToDetect(hostPlayer) — observer-first, so it returned the range at
        // which the NPC detects the HOST and folded the host player's own detectability (transponder
        // +/-1000 flat, sustained burn x2, hyperspace cloud x0.5) into every mirror's profile. Mirrors
        // then flipped between faction-red and grey purely from what the host was doing. Nothing about
        // an observer may enter these values.
        boolean[] tripped = {false};
        CampaignFleetAPI trap = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "TrapFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> 650f;
                    case "getSensorStrength" -> 420f;
                    case "getDetectedRangeMod" -> new StatBonus();
                    case "getMaxSensorRangeToDetect", "getBaseSensorRangeToDetect",
                         "getVisibilityLevelTo", "getVisibilityLevelToPlayerFleet" -> {
                        tripped[0] = true;
                        yield 99999f;
                    }
                    default -> null;
                });

        assertEquals(650f, CoopSensorSync.capture(trap).sensorProfile(), 0.001f);
        assertFalse(tripped[0], "capture must not ask the fleet what it can detect");
    }

    @Test
    void anExplodingFleetYieldsTheUnknownProfileRatherThanTakingTheTickDown() {
        CampaignFleetAPI broken = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "BrokenFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> throw new IllegalStateException("boom");
                    default -> null;
                });

        assertEquals(CoopSensorSync.Profile.UNKNOWN, CoopSensorSync.capture(broken));
        assertFalse(CoopSensorSync.capture(broken).isKnown());
    }

    @Test
    void aNullFleetOrNullDetectedRangeModIsHandled() {
        assertEquals(CoopSensorSync.Profile.UNKNOWN, CoopSensorSync.capture(null));
        assertEquals(1f, CoopSensorSync.capture(fleet(650f, 420f, null)).detectedRangeMult(), 0.001f);
    }

    @Test
    void theUnknownProfileIsMultiplicativeIdentityAndNeverApplied() {
        assertEquals(1f, CoopSensorSync.Profile.UNKNOWN.detectedRangeMult(), 0.001f);
        assertFalse(CoopSensorSync.Profile.UNKNOWN.isKnown());
        assertFalse(CoopSensorSync.apply(null, CoopSensorSync.Profile.UNKNOWN));
    }

    // ---- the correction that absorbs the receiving client's own mods -----------------------------

    @Test
    void theMultCorrectionCancelsWhateverTheLocalEngineApplied() {
        // The guest's real fleet is at x0.5 (going dark). The host's own nebula has already put x0.5
        // on the mirror. The correction must produce x1.0 so the TOTAL is x0.5, not x0.25 — the exact
        // double-count that made replicated fleets render grey.
        assertEquals(1f, CoopSensorSync.multCorrection(0.5f, 0.5f), 0.0001f);
        // Nothing local: the correction is the value itself.
        assertEquals(0.5f, CoopSensorSync.multCorrection(0.5f, 1f), 0.0001f);
        // Local applied more than the source had: correct upward.
        assertEquals(2f, CoopSensorSync.multCorrection(1f, 0.5f), 0.0001f);
    }

    @Test
    void aZeroOrBrokenLocalMultFallsBackToIdentityInsteadOfDividingByZero() {
        assertEquals(1f, CoopSensorSync.multCorrection(0.5f, 0f), 0.0001f);
        assertEquals(1f, CoopSensorSync.multCorrection(0.5f, Float.NaN), 0.0001f);
        assertEquals(1f, CoopSensorSync.multCorrection(0.5f, Float.POSITIVE_INFINITY), 0.0001f);
    }

    // ---- wire codec ------------------------------------------------------------------------------

    @Test
    void theSensorFieldsRoundTripThroughTheWireCodec() {
        CoopSensorSync.Profile profile = new CoopSensorSync.Profile(650.5f, 1000f, -25f, 0.5f, 420.25f);
        StringBuilder out = new StringBuilder("prefix");
        CoopSensorSync.append(out, profile);

        List<String> fields = CoopFleetCodec.split(out.toString());

        assertEquals(1 + CoopSensorSync.FIELD_COUNT, fields.size());
        assertEquals(profile, CoopSensorSync.parse(fields, 1));
    }

    @Test
    void aNullProfileEncodesAsTheUnknownProfile() {
        StringBuilder out = new StringBuilder("prefix");
        CoopSensorSync.append(out, null);

        assertEquals(CoopSensorSync.Profile.UNKNOWN,
                CoopSensorSync.parse(CoopFleetCodec.split(out.toString()), 1));
    }

    private static CampaignFleetAPI fleet(float sensorProfile, float sensorStrength,
                                          StatBonus detectedRangeMod) {
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> sensorProfile;
                    case "getSensorStrength" -> sensorStrength;
                    case "getDetectedRangeMod" -> detectedRangeMod;
                    default -> null;
                });
    }
}
