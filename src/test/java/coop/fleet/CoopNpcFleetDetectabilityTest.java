package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.StatBonus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value that decides whether an NPC fleet renders faction-red or grey on the guest.
 *
 * <p>The engine computes {@code detectionRange = (target.profile + observer.strength) *
 * target.detectedRangeMod * observer.sensorRangeMod} and then buckets the distance against it: faction
 * details inside 10% (or the whole range when the target's transponder is on), composition details
 * inside 50%, grey sensor contact beyond. The mirror on the guest has no {@code detectedRangeMod} of
 * its own, so the host has to fold the real fleet's into the profile it streams.
 */
class CoopNpcFleetDetectabilityTest {

    private static final float REFERENCE_STRENGTH = 400f;

    @Test
    void aFleetWithNoDetectedRangeBonusStreamsItsRawProfile() {
        assertEquals(650f, CoopNpcFleetReplicator.effectiveDetectability(
                fleet(650f, new StatBonus()), REFERENCE_STRENGTH), 0.01f);
    }

    @Test
    void aFlatBonusIsFoldedInExactlyAndIsReferenceIndependent() {
        // Transponder on (+1000 flat) and the Remnant/derelict generation flats are the common cases.
        StatBonus mod = new StatBonus();
        mod.modifyFlat("transponder", 1000f, "Transponder on");

        assertEquals(1650f, CoopNpcFleetReplicator.effectiveDetectability(
                fleet(650f, mod), REFERENCE_STRENGTH), 0.01f);
        // The flat term survives the +reference/-reference round trip untouched, so a wrong reference
        // strength cannot distort it.
        assertEquals(1650f, CoopNpcFleetReplicator.effectiveDetectability(fleet(650f, mod), 0f), 0.01f);
    }

    @Test
    void aPercentBonusScalesTheDetectionRangeNotJustTheProfile() {
        // Sustained burn is +100%: the engine doubles (profile + observerStrength), so the profile the
        // guest needs is 2*(650+400) - 400 = 1700.
        StatBonus mod = new StatBonus();
        mod.modifyPercent("sustainedBurn", 100f, "Sustained burn");

        assertEquals(1700f, CoopNpcFleetReplicator.effectiveDetectability(
                fleet(650f, mod), REFERENCE_STRENGTH), 0.01f);
    }

    @Test
    void aReducingBonusNeverStreamsLessThanTheRawProfile() {
        // Phase fields and going dark multiply detectability down. The mirror already carries the raw
        // profile, and dropping below it would make the fleet vanish on the guest while the host still
        // sees it — the Phase 9 symptom this fold exists to prevent.
        StatBonus mod = new StatBonus();
        mod.modifyMult("phaseField", 0.5f, "Phase field");

        assertEquals(650f, CoopNpcFleetReplicator.effectiveDetectability(
                fleet(650f, mod), REFERENCE_STRENGTH), 0.01f);
    }

    @Test
    void detectabilityNeverConsultsTheFleetAsAnObserver() {
        // The regression guard. effectiveDetectability used to call
        // fleet.getMaxSensorRangeToDetect(hostPlayer) — observer-first, so it returned the range at
        // which the NPC detects the HOST and folded the host player's own detectability (transponder
        // +/-1000 flat, sustained burn x2, hyperspace cloud x0.5) into every mirror's profile. Mirrors
        // then flipped between faction-red and grey on the guest purely from what the host was doing.
        // Nothing about an observer may enter this value.
        boolean[] tripped = {false};
        CampaignFleetAPI fleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "TrapFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> 650f;
                    case "getDetectedRangeMod" -> new StatBonus();
                    case "getMaxSensorRangeToDetect", "getBaseSensorRangeToDetect" -> {
                        tripped[0] = true;
                        yield 99999f;
                    }
                    default -> null;
                });

        assertEquals(650f, CoopNpcFleetReplicator.effectiveDetectability(fleet, REFERENCE_STRENGTH), 0.01f);
        assertTrue(!tripped[0], "effectiveDetectability must not ask the fleet what it can detect");
    }

    @Test
    void anExplodingFleetYieldsZeroRatherThanTakingTheTickDown() {
        CampaignFleetAPI fleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "BrokenFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> throw new IllegalStateException("boom");
                    default -> null;
                });

        assertEquals(0f, CoopNpcFleetReplicator.effectiveDetectability(fleet, REFERENCE_STRENGTH), 0.01f);
    }

    @Test
    void aNullDetectedRangeModFallsBackToTheRawProfile() {
        assertEquals(650f, CoopNpcFleetReplicator.effectiveDetectability(
                fleet(650f, null), REFERENCE_STRENGTH), 0.01f);
    }

    private static CampaignFleetAPI fleet(float sensorProfile, StatBonus detectedRangeMod) {
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "FakeFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getSensorProfile" -> sensorProfile;
                    case "getDetectedRangeMod" -> detectedRangeMod;
                    default -> null;
                });
    }
}
