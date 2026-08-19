package coop.combat;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopBattleStatusTest {

    // ---- codec round trip -------------------------------------------------------------------------

    @Test
    void shipsAndKillFeedRoundTrip() {
        CoopBattleStatus original = new CoopBattleStatus("battle-1", 7L, 42000L,
                List.of(
                        ship("s1", "onslaught", "ISS Hammer", false, 0.75f, 0.25f,
                                CoopBattleStatus.ShipState.ALIVE),
                        ship("s2", "lasher", "Pirate Lasher", true, 0.1f, 0.9f,
                                CoopBattleStatus.ShipState.DISABLED)),
                List.of("Enemy Pirate Hound destroyed"));

        CoopBattleStatus decoded = CoopBattleStatus.decode("battle-1", 7L, 42000L, original.encodeBody());

        assertEquals(original.ships(), decoded.ships());
        assertEquals(original.killFeed(), decoded.killFeed());
        assertEquals("battle-1", decoded.battleId());
        assertEquals(7L, decoded.statusSeq());
        assertEquals(42000L, decoded.elapsedMillis());
    }

    @Test
    void delimiterHostileNamesSurviveTheEncoding() {
        // Ship names are player-editable, so a name containing the field or record separator must not
        // be able to break the framing of the whole snapshot.
        CoopBattleStatus original = new CoopBattleStatus("b", 1L, 0L,
                List.of(ship("s1", "hull|id", "a|b\nc\\d", false, 1f, 0f,
                        CoopBattleStatus.ShipState.ALIVE)),
                List.of("kill|feed\nentry"));

        CoopBattleStatus decoded = CoopBattleStatus.decode("b", 1L, 0L, original.encodeBody());

        assertEquals("a|b\nc\\d", decoded.ships().get(0).name());
        assertEquals("hull|id", decoded.ships().get(0).hullId());
        assertEquals(List.of("kill|feed\nentry"), decoded.killFeed());
    }

    @Test
    void emptyBattleEncodesAndDecodesAsEmpty() {
        CoopBattleStatus empty = new CoopBattleStatus("b", 0L, 0L, List.of(), List.of());

        assertEquals("", empty.encodeBody());
        CoopBattleStatus decoded = CoopBattleStatus.decode("b", 0L, 0L, "");
        assertTrue(decoded.ships().isEmpty());
        assertTrue(decoded.killFeed().isEmpty());
    }

    @Test
    void unknownRecordTypesAndExtraFieldsAreIgnored() {
        // Forward compatibility for Phase 22's tactical map: optional position fields appended to an
        // S line, and whole new record types, must not break an older decoder.
        String body = "S|s1|lasher|Lasher|1|0.5|0.25|ALIVE|123.0|456.0|90.0\nP|s1|123|456\nK|boom";

        CoopBattleStatus decoded = CoopBattleStatus.decode("b", 1L, 0L, body);

        assertEquals(1, decoded.ships().size());
        assertEquals("Lasher", decoded.ships().get(0).name());
        assertEquals(List.of("boom"), decoded.killFeed());
    }

    @Test
    void unknownShipStateDegradesToAlive() {
        CoopBattleStatus decoded = CoopBattleStatus.decode("b", 1L, 0L,
                "S|s1|lasher|Lasher|0|1.0|0.0|VAPORIZED");

        assertEquals(CoopBattleStatus.ShipState.ALIVE, decoded.ships().get(0).state());
    }

    @Test
    void hullAndFluxAreClampedToTheUnitRange() {
        CoopBattleStatus.ShipRecord record = ship("s", "h", "n", false, 4.2f, -1f,
                CoopBattleStatus.ShipState.ALIVE);

        assertEquals(1f, record.hullFraction());
        assertEquals(0f, record.fluxLevel());
    }

    // ---- side split -------------------------------------------------------------------------------

    @Test
    void sidesSplitByTheEnemyFlag() {
        CoopBattleStatus status = new CoopBattleStatus("b", 1L, 0L,
                List.of(
                        ship("a", "h", "Own", false, 1f, 0f, CoopBattleStatus.ShipState.ALIVE),
                        ship("b", "h", "Foe", true, 1f, 0f, CoopBattleStatus.ShipState.ALIVE),
                        ship("c", "h", "Own2", false, 1f, 0f, CoopBattleStatus.ShipState.ALIVE)),
                List.of());

        assertEquals(List.of("Own", "Own2"),
                status.ownShips().stream().map(CoopBattleStatus.ShipRecord::name).toList());
        assertEquals(List.of("Foe"),
                status.enemyShips().stream().map(CoopBattleStatus.ShipRecord::name).toList());
    }

    // ---- latest-wins sequencing -------------------------------------------------------------------

    @Test
    void firstSnapshotIsAlwaysNewer() {
        assertTrue(CoopBattleStatus.isNewer("b", 1L, null));
    }

    @Test
    void higherSeqWinsAndStaleSeqLoses() {
        CoopBattleStatus current = new CoopBattleStatus("b", 5L, 0L, List.of(), List.of());

        assertTrue(CoopBattleStatus.isNewer("b", 6L, current));
        assertFalse(CoopBattleStatus.isNewer("b", 5L, current));
        assertFalse(CoopBattleStatus.isNewer("b", 4L, current));
    }

    @Test
    void aDifferentBattleAlwaysSupersedes() {
        // Sequence numbers restart at 1 per battle, so a fresh battle's first snapshot would otherwise
        // look stale against the last snapshot of the previous one.
        CoopBattleStatus current = new CoopBattleStatus("battle-1", 900L, 0L, List.of(), List.of());

        assertTrue(CoopBattleStatus.isNewer("battle-2", 1L, current));
        assertTrue(new CoopBattleStatus("battle-2", 1L, 0L, List.of(), List.of()).isNewerThan(current));
    }

    // ---- message payload --------------------------------------------------------------------------

    @Test
    void battleStatusMessageCarriesTheBodyThroughTheFlatEnvelope() {
        CoopBattleStatus status = new CoopBattleStatus("battle-9", 3L, 1500L,
                List.of(ship("s1", "wolf", "Wolf \"Fang\"", true, 0.5f, 0.5f,
                        CoopBattleStatus.ShipState.ALIVE)),
                List.of("Own Kite destroyed"));

        CoopMessages.Message message = CoopMessages.battleStatus("session", 1L, 0L,
                status.battleId(), status.statusSeq(), status.elapsedMillis(), status.encodeBody());
        CoopMessages.Message wire = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.BATTLE_STATUS, wire.type());
        CoopBattleStatus decoded = CoopBattleStatus.decode(
                CoopMessages.requiredPayloadString(wire, "battleId"),
                CoopMessages.requiredPayloadLong(wire, "statusSeq"),
                CoopMessages.requiredPayloadLong(wire, "elapsedMillis"),
                CoopMessages.requiredPayloadString(wire, "ships"));
        assertEquals(status.ships(), decoded.ships());
        assertEquals(status.killFeed(), decoded.killFeed());
    }

    private static CoopBattleStatus.ShipRecord ship(String id, String hullId, String name, boolean enemy,
                                                    float hull, float flux,
                                                    CoopBattleStatus.ShipState state) {
        return new CoopBattleStatus.ShipRecord(id, hullId, name, enemy, hull, flux, state);
    }
}
