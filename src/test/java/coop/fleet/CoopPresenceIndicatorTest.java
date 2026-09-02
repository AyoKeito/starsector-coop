package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.fleet.MutableFleetStatsAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.DynamicStatsAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopPresenceIndicatorTest {
    @Test
    void presenceLabelIsANounPhraseThatReadsAfterTheFactionArticle() {
        // The mirror is a player-faction fleet, so vanilla draws it as
        // "<player.faction displayNameWithArticle> <name>" — "Your Alice" with the bare username,
        // and "The Foo Republic Alice" once colonies have renamed the faction.
        assertEquals("partner Alice", CoopPresenceIndicator.presenceLabel("  Alice  "));
    }

    @Test
    void presenceLabelFallsBackWhenUsernameMissing() {
        assertEquals("coop partner", CoopPresenceIndicator.presenceLabel(null));
        assertEquals("coop partner", CoopPresenceIndicator.presenceLabel("   "));
    }

    @Test
    void presenceFactionIdUsesLocalPlayerFactionForOwnColor() {
        assertEquals("tritachyon", CoopPresenceIndicator.presenceFactionId("  tritachyon "));
    }

    @Test
    void presenceFactionIdFallsBackToPlayerFaction() {
        assertEquals("player", CoopPresenceIndicator.presenceFactionId(null));
        assertEquals("player", CoopPresenceIndicator.presenceFactionId(""));
    }

    // ---- Phase 14b: presence must not be visible to NPC AI ---------------------------------------

    @Test
    void presenceStylingNeverTouchesTheTwoTermsNpcDetectionReads() {
        // The regression guard for the biggest stealth blocker in the mod. Until 14b this forced
        // setSensorProfile(100000) and a +100000 flat on getDetectedRangeMod() — the exact two
        // target-side terms of BaseCampaignEntity.getMaxSensorRangeToDetect, which every hostile NPC
        // reads through getVisibilityLevelTo before it will consider hunting. A guest running dark was
        // detectable from across the system. Presence now rides a player-observer-only dynamic stat.
        boolean[] touchedNpcVisibleTerms = {false};
        MutableStat detectedByPlayer = new MutableStat(1f);
        CampaignFleetAPI mirror = mirrorFleet(detectedByPlayer, touchedNpcVisibleTerms);

        new CoopPresenceIndicator().apply(mirror, "Alice");

        assertFalse(touchedNpcVisibleTerms[0],
                "presence styling must not write sensorProfile or detectedRangeMod");
        assertEquals(CoopPresenceIndicator.PLAYER_DETECTION_MULT,
                detectedByPlayer.getModifiedValue(), 0.001f);
    }

    @Test
    void presenceStylingIsIdempotent() {
        MutableStat detectedByPlayer = new MutableStat(1f);
        CampaignFleetAPI mirror = mirrorFleet(detectedByPlayer, new boolean[]{false});
        CoopPresenceIndicator indicator = new CoopPresenceIndicator();

        indicator.apply(mirror, "Alice");
        indicator.apply(mirror, "Alice");
        indicator.apply(mirror, "Alice");

        assertEquals(CoopPresenceIndicator.PLAYER_DETECTION_MULT,
                detectedByPlayer.getModifiedValue(), 0.001f);
    }

    @Test
    void theBoostIsBigEnoughToOutrunTheEnginesSensorRangeCap() {
        // sensorRangeMax is 5,000 su in-system and 2,000 in hyperspace; a typical fleet detection range
        // is on the order of 1,000. Anything short of a big multiplier would make the partner blink out.
        assertTrue(CoopPresenceIndicator.PLAYER_DETECTION_MULT >= 10f);
    }

    private static CampaignFleetAPI mirrorFleet(MutableStat detectedByPlayer, boolean[] tripped) {
        DynamicStatsAPI dynamic = (DynamicStatsAPI) Proxy.newProxyInstance(
                DynamicStatsAPI.class.getClassLoader(),
                new Class<?>[]{DynamicStatsAPI.class},
                (proxy, method, args) -> "getStat".equals(method.getName())
                        && Stats.DETECTED_BY_PLAYER_RANGE_MULT.equals(args[0])
                        ? detectedByPlayer : null);
        MutableFleetStatsAPI stats = (MutableFleetStatsAPI) Proxy.newProxyInstance(
                MutableFleetStatsAPI.class.getClassLoader(),
                new Class<?>[]{MutableFleetStatsAPI.class},
                (proxy, method, args) -> {
                    if ("getDynamic".equals(method.getName())) {
                        return dynamic;
                    }
                    if ("getDetectedRangeMod".equals(method.getName())) {
                        tripped[0] = true;
                    }
                    return null;
                });
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "MirrorFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getStats" -> stats;
                    case "setSensorProfile", "getDetectedRangeMod", "setSensorStrength" -> {
                        tripped[0] = true;
                        yield null;
                    }
                    default -> null;
                });
    }
}
