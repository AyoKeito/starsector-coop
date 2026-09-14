package coop.debug;

import coop.debug.CoopOwnFleetProbe.Drop;
import coop.debug.CoopOwnFleetProbe.FleetState;
import coop.debug.CoopOwnFleetProbe.Kind;
import coop.debug.CoopOwnFleetProbe.MemberState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe's detection rule, as a pure function over two snapshots.
 *
 * <p>Everything that decides whether a live smoke sees a line lives in {@code diff}; the engine half
 * only fills the records in. So this is the file that has to be right: a threshold that is off by a
 * factor of ten is a log full of noise or a log with the one line missing, and neither is discovered
 * until after the next multi-hour smoke run.
 */
class CoopOwnFleetProbeTest {

    private static final String ZIG = "ziggurat_Experimental";

    @Test
    void aCrDropPastTheEpsilonIsReported() {
        FleetState before = fleet(140f, member("m1", 0.34f, 1f));
        FleetState after = fleet(140f, member("m1", 0.21f, 1f));

        List<Drop> drops = CoopOwnFleetProbe.diff(before, after);

        assertEquals(1, drops.size());
        assertEquals(Kind.CR, drops.get(0).kind());
        assertEquals("m1", drops.get(0).memberId());
        assertEquals(0.34f, drops.get(0).oldValue(), 1e-6f);
        assertEquals(0.21f, drops.get(0).newValue(), 1e-6f);
    }

    /**
     * One wire step ({@code CoopFleetCodec.FRACTION_STEP} = 0.001) must not register by itself, or the
     * log fills with quantization noise and the real cliff is invisible in it.
     */
    @Test
    void aCrMoveAtTheEpsilonIsNotADrop() {
        FleetState before = fleet(140f, member("m1", 0.341f, 1f));
        FleetState after = fleet(140f, member("m1", 0.340f, 1f));

        assertTrue(CoopOwnFleetProbe.diff(before, after).isEmpty());
    }

    @Test
    void crRecoveryIsNeverReported() {
        FleetState before = fleet(140f, member("m1", 0.21f, 1f));
        FleetState after = fleet(140f, member("m1", 0.70f, 1f));

        assertTrue(CoopOwnFleetProbe.diff(before, after).isEmpty());
    }

    /**
     * The S5-B symptom proper: hull does not fall outside combat in vanilla, so any fall at all is
     * worth a line — there is no "normal" hull decay to filter out.
     */
    @Test
    void anyHullDropIsReported() {
        FleetState before = fleet(140f, member("m1", 0.34f, 1f));
        FleetState after = fleet(140f, member("m1", 0.34f, 0.87f));

        List<Drop> drops = CoopOwnFleetProbe.diff(before, after);

        assertEquals(1, drops.size());
        assertEquals(Kind.HULL, drops.get(0).kind());
        assertEquals(1f, drops.get(0).oldValue(), 1e-6f);
        assertEquals(0.87f, drops.get(0).newValue(), 1e-6f);
    }

    @Test
    void hullRepairIsNeverReported() {
        FleetState before = fleet(140f, member("m1", 0.34f, 0.87f));
        FleetState after = fleet(140f, member("m1", 0.34f, 1f));

        assertTrue(CoopOwnFleetProbe.diff(before, after).isEmpty());
    }

    @Test
    void crAndHullFallingTogetherAreTwoLines() {
        FleetState before = fleet(140f, member("m1", 0.34f, 1f));
        FleetState after = fleet(140f, member("m1", 0.21f, 0.87f));

        List<Drop> drops = CoopOwnFleetProbe.diff(before, after);

        assertEquals(List.of(Kind.CR, Kind.HULL), drops.stream().map(Drop::kind).toList());
    }

    /** The drop line has to carry the ship's state, not just the two numbers. */
    @Test
    void aDropCarriesTheNewMemberState() {
        MemberState after = member("m1", 0.21f, 0.87f);
        FleetState drops = fleet(140f, after);

        Drop drop = CoopOwnFleetProbe.diff(fleet(140f, member("m1", 0.34f, 1f)), drops).get(0);

        assertSame(after, drop.member());
        assertEquals(ZIG, drop.member().hullId());
    }

    // ---- supplies -------------------------------------------------------------------------------

    /**
     * A discrete supply loss is an event; the per-frame maintenance drip is not. Half a unit in one
     * frame is orders of magnitude past anything upkeep can take at 60 Hz.
     */
    @Test
    void aSupplyDropPastTheEpsilonIsReported() {
        List<Drop> drops = CoopOwnFleetProbe.diff(
                fleet(140f, member("m1", 0.34f, 1f)),
                fleet(100f, member("m1", 0.34f, 1f)));

        assertEquals(1, drops.size());
        assertEquals(Kind.SUPPLIES, drops.get(0).kind());
        assertEquals(140f, drops.get(0).oldValue(), 1e-6f);
        assertEquals(100f, drops.get(0).newValue(), 1e-6f);
        assertEquals("", drops.get(0).memberId());
        assertEquals(null, drops.get(0).member());
    }

    @Test
    void theMaintenanceDripIsNotASupplyDrop() {
        List<Drop> drops = CoopOwnFleetProbe.diff(
                fleet(140f, member("m1", 0.34f, 1f)),
                fleet(139.7f, member("m1", 0.34f, 1f)));

        assertTrue(drops.isEmpty(), "0.3 supplies in a frame is upkeep, and it rides the day summary");
        assertEquals(-0.3f, CoopOwnFleetProbe.supplyDelta(
                fleet(140f, member("m1", 0.34f, 1f)),
                fleet(139.7f, member("m1", 0.34f, 1f))), 1e-5f);
    }

    @Test
    void buyingSuppliesIsNotADrop() {
        assertTrue(CoopOwnFleetProbe.diff(
                fleet(0f, member("m1", 0.34f, 1f)),
                fleet(140f, member("m1", 0.34f, 1f))).isEmpty());
    }

    // ---- roster ---------------------------------------------------------------------------------

    @Test
    void aMemberThatDisappearsIsReportedWithItsLastKnownState() {
        MemberState gone = member("m1", 0.34f, 1f);

        List<Drop> drops = CoopOwnFleetProbe.diff(fleet(140f, gone), fleet(140f));

        assertEquals(1, drops.size());
        assertEquals(Kind.MEMBER_GONE, drops.get(0).kind());
        assertSame(gone, drops.get(0).member());
    }

    @Test
    void aMemberThatAppearsIsReported() {
        List<Drop> drops = CoopOwnFleetProbe.diff(fleet(140f), fleet(140f, member("m1", 0.7f, 1f)));

        assertEquals(1, drops.size());
        assertEquals(Kind.MEMBER_NEW, drops.get(0).kind());
        assertEquals("m1", drops.get(0).memberId());
    }

    /** Members are paired by id, so a reordered roster is not four ships changing at once. */
    @Test
    void reorderingTheRosterIsNotAChange() {
        MemberState a = member("m1", 0.34f, 1f);
        MemberState b = member("m2", 0.90f, 0.5f);

        assertTrue(CoopOwnFleetProbe.diff(fleet(140f, a, b), fleet(140f, b, a)).isEmpty());
    }

    @Test
    void aReplacedMemberIsGoneAndNewRatherThanADrop() {
        List<Drop> drops = CoopOwnFleetProbe.diff(
                fleet(140f, member("m1", 0.90f, 1f)),
                fleet(140f, member("m2", 0.10f, 0.3f)));

        assertEquals(List.of(Kind.MEMBER_GONE, Kind.MEMBER_NEW),
                drops.stream().map(Drop::kind).toList());
    }

    // ---- degenerate inputs ----------------------------------------------------------------------

    @Test
    void theFirstFrameHasNothingToDiffAgainst() {
        assertTrue(CoopOwnFleetProbe.diff(null, fleet(140f, member("m1", 0.34f, 1f))).isEmpty());
        assertTrue(CoopOwnFleetProbe.diff(fleet(140f, member("m1", 0.34f, 1f)), null).isEmpty());
        assertEquals(0f, CoopOwnFleetProbe.supplyDelta(null, fleet(140f)), 1e-6f);
    }

    @Test
    void anIdenticalFrameProducesNothing() {
        FleetState state = fleet(140f, member("m1", 0.34f, 1f), member("m2", 0.9f, 0.5f));

        assertTrue(CoopOwnFleetProbe.diff(state, state).isEmpty());
    }

    // ---- the line itself ------------------------------------------------------------------------

    /**
     * The drop line is the artefact a smoke greps for, so the fields the handoff promised have to be
     * on it. Asserted by key rather than by whole-string equality: the order is presentation, the
     * presence is the contract.
     */
    @Test
    void theDropLineCarriesEveryFieldTheSmokeNeeds() {
        FleetState before = fleet(140f, member("m1", 0.34f, 1f));
        FleetState after = new FleetState(1_500L, 9_000L, "Jul 4, c206", 14, 2060704, 0f, 12f, 40,
                true, false, false, "hyperspace",
                List.of(new MemberState("m1", ZIG, "ziggurat_Experimental", "Bad Faith",
                        0.21f, 0.70f, 0.21f, false, true, false, 0.87f,
                        3f, 5f, 0.5f, 0.02f, 4f, "noSupply[-0.0400_Out_of_supplies]")));

        Drop crDrop = CoopOwnFleetProbe.diff(before, after).stream()
                .filter(drop -> drop.kind() == Kind.CR).findFirst().orElseThrow();
        String line = CoopOwnFleetProbe.formatDrop(before, after, crDrop);

        assertTrue(line.startsWith(CoopOwnFleetProbe.DROP_PREFIX + " CR "), line);
        for (String key : List.of("date=Jul 4, c206", "hour=14", "dtMs=500", "member=m1",
                "hull=" + ZIG, "variant=ziggurat_Experimental", "cr=0.3400->0.2100",
                "maxCR=0.7000", "mothballed=false", "crashMothballed=true", "hullFrac=0.8700",
                "minCrew=3.0000", "supplies=0.0000", "fuel=12.0000", "crew=40",
                "fleetMinCrew=3.0000", "hyper=true", "paused=false", "battle=false",
                "loc=hyperspace", "crEvents=[noSupply[-0.0400_Out_of_supplies]]")) {
            assertTrue(line.contains(key), "missing " + key + " in: " + line);
        }
    }

    @Test
    void aSupplyLineNamesNoMember() {
        FleetState before = fleet(140f, member("m1", 0.34f, 1f));
        FleetState after = fleet(0f, member("m1", 0.34f, 1f));

        String line = CoopOwnFleetProbe.formatDrop(before, after,
                CoopOwnFleetProbe.diff(before, after).get(0));

        assertTrue(line.startsWith(CoopOwnFleetProbe.DROP_PREFIX + " SUPPLIES "), line);
        assertTrue(line.contains("supplies=140.0000->0.0000"), line);
        assertFalse(line.contains("member="), line);
    }

    @Test
    void fleetMinCrewIsTheSumOverTheRoster() {
        assertEquals(8f, CoopOwnFleetProbe.fleetMinCrew(
                fleet(140f, member("m1", 0.3f, 1f), member("m2", 0.3f, 1f))), 1e-6f);
    }

    /** A null tracker must not be the thing that takes a diagnostic line down. */
    @Test
    void crEventTextSurvivesAMissingTracker() {
        assertEquals("", CoopOwnFleetProbe.crEventText(null));
    }

    /** The ring buffer is empty until the probe is ticked, which needs diagnostics on. */
    @Test
    void theDropRingStartsEmpty() {
        CoopOwnFleetProbe.INSTANCE.reset();
        assertTrue(CoopOwnFleetProbe.INSTANCE.recentDrops().isEmpty());
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private static FleetState fleet(float supplies, MemberState... members) {
        return new FleetState(1_000L, 8_000L, "Jun 28, c206", 3, 2060628, supplies, 12f, 40,
                false, false, false, "corvus", List.of(members));
    }

    private static MemberState member(String id, float cr, float hullFraction) {
        return new MemberState(id, ZIG, "ziggurat_Experimental", "Bad Faith",
                cr, 0.70f, cr, false, false, false, hullFraction,
                4f, 6f, 1f, 0.02f, 0f, "");
    }
}
