package coop.combat;

import coop.fleet.CoopFleetSnapshot;
import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBattleResultTest {

    @Test
    void encodesAndDecodesDestroyedAndSurvivingFleets() {
        CoopBattleResult original = result(
                List.of("fleet_alpha", "fleet_beta"),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_gamma", List.of(
                        member("m1", "wolf", "wolf_Assault", "ISS Wolf", "Kanta", 0.6f, 0.35f),
                        member("m2", "lasher", "lasher_CS", "ISS Lasher", "", 0.9f, 1f)))));

        CoopBattleResult roundTripped = CoopBattleResult.decode(
                original.battleId(), original.engagingPlayerId(), original.outcome(),
                original.engagingFleetSize(), original.encodeBody());

        assertEquals(original, roundTripped);
    }

    @Test
    void roundTripsThroughTheRealEnvelope() {
        CoopBattleResult original = result(
                List.of("fleet|with\\odd\nid"),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_gamma", List.of(
                        member("m1", "wolf", "wolf_Assault", "ISS \"Quote|Pipe\"", "O'Neil", 0.5f, 0.5f)))));

        CoopMessages.Message sent = CoopMessages.battleResult("session-1", 7L, 1234L,
                original.battleId(), original.engagingPlayerId(), original.outcome(),
                original.engagingFleetSize(), original.encodeBody());
        CoopMessages.Message received = CoopMessages.decode(CoopMessages.encode(sent));

        assertEquals(CoopMessages.Type.BATTLE_RESULT, received.type());
        CoopBattleResult decoded = CoopBattleResult.decode(
                CoopMessages.requiredPayloadString(received, "battleId"),
                CoopMessages.requiredPayloadString(received, "engagingPlayerId"),
                CoopMessages.requiredPayloadString(received, "outcome"),
                (int) CoopMessages.requiredPayloadLong(received, "engagingFleetSize"),
                CoopMessages.requiredPayloadString(received, "body"));

        assertEquals(original, decoded);
    }

    @Test
    void anEscapeWithNoLossesStillProducesASendableResult() {
        CoopBattleResult escaped = result(List.of(), List.of());

        assertTrue(escaped.isEmpty());
        assertEquals("", escaped.encodeBody());
        assertEquals(escaped, CoopBattleResult.decode(escaped.battleId(), escaped.engagingPlayerId(),
                escaped.outcome(), escaped.engagingFleetSize(), escaped.encodeBody()));
    }

    @Test
    void involvedFleetIdsCoverBothSidesOfTheOutcome() {
        CoopBattleResult mixed = result(
                List.of("fleet_alpha"),
                List.of(new CoopBattleResult.SurvivingFleet("fleet_beta",
                        List.of(member("m1", "wolf", "wolf_Assault", "ISS Wolf", "", 1f, 1f)))));

        assertEquals(List.of("fleet_alpha", "fleet_beta"), mixed.involvedFleetIds());
    }

    @Test
    void unknownRecordTypesAndExtraFieldsAreIgnored() {
        String body = "D|fleet_alpha|extra-field-from-a-later-build\n"
                + "Z|some-future-record\n"
                + "F|fleet_beta\n"
                + "M|m1|wolf|wolf_Assault|ISS Wolf||1.0|1.0|future-field";

        CoopBattleResult decoded = CoopBattleResult.decode("b", "p", "WIN", 3, body);

        assertEquals(List.of("fleet_alpha"), decoded.destroyedFleetIds());
        assertEquals(1, decoded.survivingFleets().size());
        assertEquals("fleet_beta", decoded.survivingFleets().get(0).coopFleetId());
        assertEquals(1, decoded.survivingFleets().get(0).members().size());
    }

    /**
     * The rep decision made executable. Battle reputation reaches the host on the Phase 12
     * {@code GUEST_REP_DELTA} path already ({@code CoopCampaignReplicator.onPlayerReputationChange});
     * a rep field here would be applied a second time. Same for spoils, which the fighter keeps.
     */
    @Test
    void carriesNoReputationAndNoSpoils() {
        for (RecordComponent component : CoopBattleResult.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("rep"), "unexpected reputation field: " + component.getName());
            assertFalse(name.contains("credit"), "unexpected credits field: " + component.getName());
            assertFalse(name.contains("xp"), "unexpected XP field: " + component.getName());
            assertFalse(name.contains("loot") || name.contains("salvage"),
                    "unexpected spoils field: " + component.getName());
        }
    }

    private static CoopBattleResult result(List<String> destroyed,
                                           List<CoopBattleResult.SurvivingFleet> survivors) {
        return new CoopBattleResult("session-1-42", "player-guest", "WIN", 5, destroyed, survivors);
    }

    private static CoopFleetSnapshot.Member member(String id, String hullId, String variantId,
                                                   String shipName, String captain, float cr,
                                                   float hull) {
        return new CoopFleetSnapshot.Member(id, hullId, variantId, shipName, captain, cr, hull);
    }
}
