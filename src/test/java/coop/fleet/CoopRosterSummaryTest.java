package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The roster diagnostic is only worth anything if the host's line and the guest's line are directly
 * comparable, so the formatting is pinned here rather than left to whichever call site prints it.
 */
class CoopRosterSummaryTest {

    private static CoopFleetSnapshot.Member member(String hullId) {
        return new CoopFleetSnapshot.Member(hullId + "-id", hullId, hullId + "_Standard",
                "Ship", "", 1f, 1f);
    }

    @Test
    void countsRepeatsAndKeepsFirstAppearanceOrder() {
        assertEquals("hound x2, cerberus x1, nebula x3", CoopRosterSummary.ofMembers(List.of(
                member("hound"), member("cerberus"), member("hound"),
                member("nebula"), member("nebula"), member("nebula"))));
    }

    @Test
    void aUniformCivilianStackReadsAsOneEntry() {
        // The exact shape of the 2026-08-19 report: a patrol mirror wearing six identical freighters.
        assertEquals("nebula x6", CoopRosterSummary.ofHullIds(
                List.of("nebula", "nebula", "nebula", "nebula", "nebula", "nebula")));
    }

    @Test
    void anEmptyRosterIsNamedRatherThanBlank() {
        // A blank field in a log line reads as "the diagnostic is broken"; this reads as "zero ships",
        // which is a real state the host has been observed to replicate.
        assertEquals(CoopRosterSummary.EMPTY, CoopRosterSummary.ofMembers(List.of()));
        assertEquals(CoopRosterSummary.EMPTY, CoopRosterSummary.ofHullIds(null));
    }

    @Test
    void replicatedPermanentHullModsShowInTheSummary() {
        // Phase 16: the whole point of the roster-diff pair is that a divergence is visible without
        // opening two saves, so the replicated d-mod/S-mod counts have to be on the line.
        assertEquals("falcon_default_D+d2 x1, wolf+s1 x1, onslaught+d1+s2 x1",
                CoopRosterSummary.ofMembers(List.of(
                        new CoopFleetSnapshot.Member("m1", "falcon_default_D", "falcon_Assault",
                                "Ship", "", 1f, 1f, "compromised_storage,damagedengines", "", ""),
                        new CoopFleetSnapshot.Member("m2", "wolf", "wolf_Assault",
                                "Ship", "", 1f, 1f, "", "heavyarmor", ""),
                        new CoopFleetSnapshot.Member("m3", "onslaught", "onslaught_Standard",
                                "Ship", "", 1f, 1f, "structuraldamage", "heavyarmor",
                                "armoredweapons"))));
    }

    @Test
    void aCleanRosterPrintsExactlyAsItDidBeforePhase16() {
        assertEquals("hound x2", CoopRosterSummary.ofMembers(List.of(member("hound"), member("hound"))));
    }

    @Test
    void aMissingHullIdIsMarkedRatherThanDropped() {
        assertEquals("? x1, hound x1", CoopRosterSummary.ofHullIds(java.util.Arrays.asList("", "hound")));
    }
}
