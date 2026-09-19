package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 33 wire additions: one ally bit on the 10 Hz tick and the full snapshot, and the officer
 * fields on every roster member plus the owner's commander.
 *
 * <p>Both are <b>additive</b>, and that is what most of this file is about. The ally bit sits after
 * the tick's variable-length member section and the officer fields after each member's existing
 * ones, so a body written without them decodes as "no consent, no officers" instead of shifting a
 * field and dropping the whole record.
 */
class CoopAllyWireFormatTest {

    private static final CoopSensorSync.Profile SENSORS =
            new CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f);

    @Test
    void theAllyBitRidesTheTickBothWays() {
        CoopFleetSnapshot allowed = snapshot(true, List.of(member("m1", 0, "", "")));
        CoopFleetSnapshot refused = snapshot(false, List.of(member("m1", 0, "", "")));

        assertTrue(CoopFleetSnapshot.Tick.decode(CoopFleetSnapshot.Tick.of(allowed).encode())
                .allyAllowed());
        assertFalse(CoopFleetSnapshot.Tick.decode(CoopFleetSnapshot.Tick.of(refused).encode())
                .allyAllowed());
    }

    @Test
    void aTickWithoutTheAllyFieldStillDecodes() {
        // A peer that does not send it has not consented, which is the safe answer and the one every
        // build before 0.1.4 meant. Shifting the member pairs instead would be a rejected datagram.
        CoopFleetSnapshot snapshot = snapshot(true, List.of(member("m1", 0, "", ""),
                member("m2", 0, "", "")));
        String encoded = CoopFleetSnapshot.Tick.of(snapshot).encode();
        String withoutBit = encoded.substring(0, encoded.lastIndexOf('|'));

        CoopFleetSnapshot.Tick decoded = CoopFleetSnapshot.Tick.decode(withoutBit);

        assertFalse(decoded.allyAllowed());
        assertEquals(2, decoded.members().size());
        assertEquals(CoopFleetSnapshot.Tick.of(snapshot).members(), decoded.members());
    }

    @Test
    void theAllyBitAndTheCommanderRideTheFullSnapshot() {
        CoopFleetSnapshot snapshot = snapshot(true, List.of(member("m1", 0, "", "")),
                "coordinated_maneuvers:1,fighter_uplink:2", 9);

        CoopFleetSnapshot decoded = CoopFleetSnapshot.decodeFull(snapshot.encodeFull());

        assertTrue(decoded.allyAllowed());
        assertEquals("coordinated_maneuvers:1,fighter_uplink:2", decoded.commanderSkills());
        assertEquals(9, decoded.commanderLevel());
    }

    @Test
    void anOfficerRoundTripsThroughTheRoster() {
        CoopFleetSnapshot.Member officered =
                member("m1", 5, "aggressive", "combat_endurance:1,target_analysis:2");
        CoopFleetRoster roster = CoopFleetRoster.of(snapshot(true, List.of(officered),
                "coordinated_maneuvers:1", 7));

        CoopFleetRoster decoded = CoopFleetRoster.decode(roster.encode());

        assertEquals(officered, decoded.members().get(0));
        assertEquals("coordinated_maneuvers:1", decoded.commanderSkills());
        assertEquals(7, decoded.commanderLevel());
    }

    @Test
    void aShipWithNoOfficerRoundTripsAsOneWithNoOfficer() {
        CoopFleetSnapshot.Member plain = member("m1", 0, "", "");
        CoopFleetRoster decoded = CoopFleetRoster.decode(
                CoopFleetRoster.of(snapshot(false, List.of(plain))).encode());

        assertEquals(plain, decoded.members().get(0));
        assertFalse(decoded.members().get(0).hasCaptain());
    }

    @Test
    void aMemberRecordWithoutTheOfficerFieldsStillDecodes() {
        // The pre-0.1.4 eleven-field shape, which the wiretap fixtures and recorded bodies in
        // tmp_ff_analysis are full of. Dropping the roster over three missing trailing fields would
        // cost the mirror every ship in it.
        List<String> preOfficers = CoopFleetCodec.split(
                appended(member("m1", 4, "steady", "helmsmanship:1")));
        List<String> trimmed = preOfficers.subList(0, CoopFleetCodec.MEMBER_FIELD_COUNT_PRE_OFFICERS);

        CoopFleetSnapshot.Member decoded = CoopFleetCodec.parseMember(trimmed);

        assertEquals("m1", decoded.fleetMemberId());
        assertEquals(0, decoded.captainLevel());
        assertEquals("", decoded.captainPersonality());
        assertEquals("", decoded.captainSkills());
    }

    @Test
    void aRosterWithoutTheCommanderFieldsStillDecodes() {
        String encoded = CoopFleetRoster.of(snapshot(false, List.of(member("m1", 0, "", "")))).encode();
        int newline = encoded.indexOf('\n');
        String header = encoded.substring(0, newline);
        // Drop the two trailing commander fields, i.e. the shape a pre-0.1.4 sender wrote.
        String trimmedHeader = header.substring(0, header.lastIndexOf('|'));
        trimmedHeader = trimmedHeader.substring(0, trimmedHeader.lastIndexOf('|'));

        CoopFleetRoster decoded =
                CoopFleetRoster.decode(trimmedHeader + encoded.substring(newline));

        assertEquals(1, decoded.members().size());
        assertEquals("", decoded.commanderSkills());
        assertEquals(0, decoded.commanderLevel());
    }

    @Test
    void anOfficerChangeMovesTheStructuralHash() {
        // The mirror builds a PersonAPI at roster-build time and never touches it again, so a skill
        // point spent or an officer moved has to force a rebuild or it reaches the mirror never.
        String before = CoopFleetSnapshot.computeFleetHash(
                List.of(member("m1", 4, "steady", "helmsmanship:1")));
        String afterSkill = CoopFleetSnapshot.computeFleetHash(
                List.of(member("m1", 4, "steady", "helmsmanship:2")));
        String afterLevel = CoopFleetSnapshot.computeFleetHash(
                List.of(member("m1", 5, "steady", "helmsmanship:1")));
        String afterPersonality = CoopFleetSnapshot.computeFleetHash(
                List.of(member("m1", 4, "aggressive", "helmsmanship:1")));

        assertNotEquals(before, afterSkill);
        assertNotEquals(before, afterLevel);
        assertNotEquals(before, afterPersonality);
    }

    @Test
    void theAllyBitIsNotStructural() {
        // It is consent, not a ship set: flipping the toggle must not tear the mirror down and
        // rebuild it, and it reaches the other engine on the next tick regardless.
        List<CoopFleetSnapshot.Member> members = List.of(member("m1", 0, "", ""));

        assertEquals(snapshot(true, members).fleetHash(), snapshot(false, members).fleetHash());
    }

    @Test
    void anOfficerNameWithADelimiterInItSurvivesTheRoundTrip() {
        CoopFleetSnapshot.Member awkward = new CoopFleetSnapshot.Member("m1", "falcon",
                "falcon_Assault", "Pipe|Dream", "A|B", 0.8f, 0.9f, "", "", "", false,
                3, "reckless", "helmsmanship:1");

        CoopFleetRoster decoded = CoopFleetRoster.decode(
                CoopFleetRoster.of(snapshot(false, List.of(awkward))).encode());

        assertEquals(awkward, decoded.members().get(0));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String appended(CoopFleetSnapshot.Member member) {
        StringBuilder out = new StringBuilder();
        CoopFleetCodec.appendMember(out, member);
        return out.toString();
    }

    private static CoopFleetSnapshot.Member member(String id, int captainLevel, String personality,
                                                   String skills) {
        return new CoopFleetSnapshot.Member(id, "falcon", "falcon_Assault", "Ship " + id,
                captainLevel > 0 ? "Hanan Yusuf" : "", 0.8f, 0.9f, "", "", "", false,
                captainLevel, personality, skills);
    }

    private static CoopFleetSnapshot snapshot(boolean allyAllowed,
                                              List<CoopFleetSnapshot.Member> members) {
        return snapshot(allyAllowed, members, "", 0);
    }

    private static CoopFleetSnapshot snapshot(boolean allyAllowed,
                                              List<CoopFleetSnapshot.Member> members,
                                              String commanderSkills, int commanderLevel) {
        return CoopFleetSnapshot.create("p1", "Ayo", "loc", 1f, 2f, 3f, 4f, "player", true,
                SENSORS, members, allyAllowed, commanderSkills, commanderLevel);
    }
}
