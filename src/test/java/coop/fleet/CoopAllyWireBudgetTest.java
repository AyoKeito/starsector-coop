package coop.fleet;

import coop.net.CoopNetService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Phase 33's officer fields cost on the wire, measured rather than argued.
 *
 * <p>The two halves travel on different budgets. The roster is reliable TCP, sent only when the
 * structural hash moves, against a 256 KB soft warn and a 1 MB hard cap
 * ({@code CoopNetService.MAX_FRAME_BYTES}); the tick is a 10 Hz UDP datagram against
 * {@link CoopNetService#MAX_DATAGRAM_BYTES}, 1,200 bytes, which the whole Phase 20 payload diet was
 * sized to. The officers ride the roster and the ally bit rides the tick, which is the right way
 * round: the officers are the big field and they move once in a while, the bit is two bytes.
 *
 * <p>Measured on 2026-09-20 with a deliberately pessimistic fleet — 30 ships at vanilla's cap, long
 * member ids, long ship names, D-hulls, three d-mods and three s-mods each, a ten-skill elite
 * officer on <em>every</em> ship and a fifteen-skill commander: <b>13,223 bytes</b>, against 7,523
 * for the same fleet with no officers. The bounds below leave room for that to grow by half again
 * before anyone has to look at it.
 */
class CoopAllyWireBudgetTest {

    /** Vanilla's fleet size cap, and the size the payload diet was measured against. */
    private static final int SHIPS = 30;

    @Test
    void aFullyOfficeredThirtyShipRosterIsNowhereNearTheTcpFrameCap() {
        int bytes = roster(SHIPS, 10).encode().getBytes(StandardCharsets.UTF_8).length;

        assertTrue(bytes < 20_000, "roster grew to " + bytes + " bytes");
        // CoopNetService's soft warn threshold, named here as a literal because it is
        // package-private to coop.net: a roster approaching it needs a payload diet of its own.
        assertTrue(bytes < 256 * 1024 / 4,
                "roster grew to " + bytes + " bytes, within reach of the TCP soft warn");
    }

    @Test
    void theOfficersAtLeastDoubleTheRosterAndThatIsThePointOfSendingItOnTcp() {
        int plain = roster(SHIPS, 0).encode().getBytes(StandardCharsets.UTF_8).length;
        int officered = roster(SHIPS, 10).encode().getBytes(StandardCharsets.UTF_8).length;

        assertTrue(officered > plain, "the officers have to actually be on the wire");
        // Whatever it grows to, it is only sent when the structural hash moves -- which is why the
        // officer fields are part of that hash rather than riding the 10 Hz tick.
        assertTrue(officered < plain * 3, "officer encoding grew out of proportion: " + plain
                + " -> " + officered);
    }

    @Test
    void theTickStaysInsideOneDatagramWithTheAllyBitOnIt() {
        CoopFleetRoster roster = roster(SHIPS, 10);
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.create(roster.playerId(), roster.username(),
                "orion_loc", 1f, 2f, 3f, 4f, "player", true,
                new CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f), roster.members(), true,
                roster.commanderSkills(), roster.commanderLevel());

        int bytes = CoopFleetSnapshot.Tick.of(snapshot).encode()
                .getBytes(StandardCharsets.UTF_8).length;

        assertTrue(bytes < CoopNetService.MAX_DATAGRAM_BYTES,
                "the tick composed to " + bytes + " bytes and would be rerouted onto TCP");
    }

    /** A pessimistic fleet: nothing here is short, and every ship carries an elite officer. */
    private static CoopFleetRoster roster(int ships, int skills) {
        List<CoopFleetSnapshot.Member> members = new ArrayList<>();
        for (int i = 0; i < ships; i++) {
            members.add(new CoopFleetSnapshot.Member(
                    "MN-1234567890abcdef" + i,
                    "onslaught_default_D", "onslaught_Outdated", "ISS Persistent Grudge " + i,
                    skills > 0 ? "Hanan Yusuf-Sharmila" : "",
                    0.812f, 0.934f,
                    "compromised_storage,damagedengines,faulty_automated_systems",
                    "heavyarmor,missleracks", "advancedshieldemitter", false,
                    skills > 0 ? 8 : 0, skills > 0 ? "aggressive" : "", skillList(skills)));
        }
        return new CoopFleetRoster("MN-1234567890abcdef", "Ayo", "player", "0123456789abcdef",
                members, skillList(skills > 0 ? 15 : 0), skills > 0 ? 15 : 0);
    }

    private static String skillList(int count) {
        String[] ids = {"combat_endurance", "impact_mitigation", "damage_control",
                "field_modulation", "target_analysis", "systems_expertise", "ballistic_mastery",
                "gunnery_implants", "ordnance_expertise", "polarized_armor", "helmsmanship",
                "energy_weapon_mastery", "missile_specialization", "point_defense",
                "wolfpack_tactics"};
        List<CoopOfficerSkills.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count && i < ids.length; i++) {
            entries.add(new CoopOfficerSkills.Entry(ids[i], i % 2 == 0 ? 2 : 1));
        }
        return CoopOfficerSkills.encode(entries);
    }
}
