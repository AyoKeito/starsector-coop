package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 33 officer encoding. It is one {@link CoopFleetCodec} field, so the pair and level
 * separators have to be characters a skill id cannot contain; everything else here is about being
 * total, because this runs inside a roster build that must not throw.
 */
class CoopOfficerSkillsTest {

    @Test
    void skillsRoundTripThroughTheWireForm() {
        List<CoopOfficerSkills.Entry> entries = List.of(
                new CoopOfficerSkills.Entry("combat_endurance", 1),
                new CoopOfficerSkills.Entry("target_analysis", 2));

        String encoded = CoopOfficerSkills.encode(entries);

        assertEquals("combat_endurance:1,target_analysis:2", encoded);
        assertEquals(entries, CoopOfficerSkills.decode(encoded));
    }

    @Test
    void anUntakenSkillIsNotEncodedAtAll() {
        // getSkillsCopy() hands back every skill in the game for a refreshed character, most of them
        // at level 0. Streaming those would be sixty pairs to say "no" fifty-seven times.
        assertEquals("helmsmanship:1", CoopOfficerSkills.encode(List.of(
                new CoopOfficerSkills.Entry("helmsmanship", 1),
                new CoopOfficerSkills.Entry("gunnery_implants", 0),
                new CoopOfficerSkills.Entry("", 2))));
    }

    @Test
    void aCharacterWithNoSkillsEncodesToTheEmptyField() {
        assertEquals("", CoopOfficerSkills.encode(null));
        assertEquals("", CoopOfficerSkills.encode(List.of()));
        assertTrue(CoopOfficerSkills.decode("").isEmpty());
        assertTrue(CoopOfficerSkills.decode(null).isEmpty());
    }

    @Test
    void aMalformedPairIsDroppedAndTheRestStillLands() {
        // The alternative is a throw inside rebuildRoster, which costs the ship the officer is on.
        assertEquals(List.of(new CoopOfficerSkills.Entry("impact_mitigation", 2)),
                CoopOfficerSkills.decode("garbage,:1,missing_level:,x:notanumber,impact_mitigation:2"));
    }

    @Test
    void anEngineLevelBecomesTheWiresInteger() {
        // The engine models a skill level as a float and seats 1 or 2 on an officer; 2 is elite.
        assertEquals(0, CoopOfficerSkills.levelOf(0f));
        assertEquals(1, CoopOfficerSkills.levelOf(1f));
        assertEquals(2, CoopOfficerSkills.levelOf(2f));
        assertEquals(0, CoopOfficerSkills.levelOf(Float.NaN));
        assertEquals(0, CoopOfficerSkills.levelOf(-1f));
    }

    @Test
    void theSeparatorsCannotOccurInsideASkillId() {
        // Skill ids come out of skills.csv and are resolved with getSkillSpec(id); every vanilla one
        // is lowercase letters, digits and underscores. If that ever stopped being true this field
        // would split a CoopFleetCodec record rather than a pair, so the assumption is pinned here.
        assertEquals(-1, "combat_endurance".indexOf(CoopOfficerSkills.PAIR_SEPARATOR));
        assertEquals(-1, "combat_endurance".indexOf(CoopOfficerSkills.LEVEL_SEPARATOR));
    }
}
