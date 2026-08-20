package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The inflate/deflate churn guard. Every test here is written against the structural fleet hash as
 * well as the fields, because the hash is what actually gates the guest's roster teardown — a fix
 * that got the fields right and the hash wrong would fix nothing anyone can see.
 */
class CoopInflationLatchTest {

    /** A ship as the spawner asked for it: stock hull, stock variant, no permanent hullmods. */
    private static CoopFleetSnapshot.Member stock(String id, String hull) {
        return new CoopFleetSnapshot.Member(id, hull, hull + "_Standard", "ISS " + id, "", 1f, 1f);
    }

    /** The same ship after {@code DefaultFleetInflater} autofit it: D hull, D variant, d-mods. */
    private static CoopFleetSnapshot.Member inflated(String id, String hull) {
        return new CoopFleetSnapshot.Member(id, hull + "_default_D", hull + "_default_D", "ISS " + id,
                "", 1f, 1f, "compromised_storage,damagedengines", "", "");
    }

    private static String hash(List<CoopFleetSnapshot.Member> members) {
        return CoopFleetSnapshot.computeFleetHash(members);
    }

    @Test
    void deflationDoesNotChangeTheFieldsOrTheHashOnceTheFleetHasBeenSeenInflated() {
        CoopInflationLatch latch = new CoopInflationLatch();
        Object fleet = new Object();
        List<CoopFleetSnapshot.Member> whenInflated =
                List.of(inflated("m1", "atlas"), inflated("m2", "enforcer"));
        List<CoopFleetSnapshot.Member> whenDeflated =
                List.of(stock("m1", "atlas"), stock("m2", "enforcer"));
        // The premise: without the latch these two captures are two different fleets to the hash.
        assertNotEquals(hash(whenInflated), hash(whenDeflated));

        List<CoopFleetSnapshot.Member> first = latch.reconcile(fleet, true, whenInflated);
        List<CoopFleetSnapshot.Member> second = latch.reconcile(fleet, false, whenDeflated);

        assertEquals(whenInflated, first);
        assertEquals(whenInflated, second);
        assertEquals(hash(first), hash(second));
    }

    @Test
    void theLatchKeepsHoldingAcrossManyDeflatedCaptures() {
        // The live defect was five flips in 45 seconds; replaying once is not enough of a test.
        CoopInflationLatch latch = new CoopInflationLatch();
        Object fleet = new Object();
        List<CoopFleetSnapshot.Member> whenInflated = List.of(inflated("m1", "dominator"));
        String expected = hash(latch.reconcile(fleet, true, whenInflated));

        for (int i = 0; i < 5; i++) {
            assertEquals(expected,
                    hash(latch.reconcile(fleet, false, List.of(stock("m1", "dominator")))));
            assertEquals(expected, hash(latch.reconcile(fleet, true, whenInflated)));
        }
    }

    @Test
    void aMemberLostWhileDeflatedLeavesTheRestLatchedAndItsOwnEntryUnused() {
        CoopInflationLatch latch = new CoopInflationLatch();
        Object fleet = new Object();
        latch.reconcile(fleet, true,
                List.of(inflated("m1", "atlas"), inflated("m2", "enforcer"), inflated("m3", "hound")));

        List<CoopFleetSnapshot.Member> survivors =
                latch.reconcile(fleet, false, List.of(stock("m1", "atlas"), stock("m3", "hound")));

        assertEquals(List.of(inflated("m1", "atlas"), inflated("m3", "hound")), survivors);
        assertEquals(hash(List.of(inflated("m1", "atlas"), inflated("m3", "hound"))), hash(survivors));
    }

    @Test
    void aMemberThatAppearsWhileDeflatedIsCapturedAsIs() {
        CoopInflationLatch latch = new CoopInflationLatch();
        Object fleet = new Object();
        latch.reconcile(fleet, true, List.of(inflated("m1", "atlas")));

        List<CoopFleetSnapshot.Member> mixed =
                latch.reconcile(fleet, false, List.of(stock("m1", "atlas"), stock("m9", "buffalo")));

        // The known ship keeps its inflated fit; the ship this fleet has never been seen carrying
        // streams exactly as read, which is pre-Phase-16 behaviour and is acceptable.
        assertEquals(List.of(inflated("m1", "atlas"), stock("m9", "buffalo")), mixed);
    }

    @Test
    void aFleetWithNoInflaterNeverEngagesTheLatch() {
        // The player fleet's path: every capture is authoritative, so the list handed back is the
        // very list that was captured, byte-for-byte what went on the wire before this fix existed.
        CoopInflationLatch latch = new CoopInflationLatch();
        Object playerFleet = new Object();
        List<CoopFleetSnapshot.Member> refit = List.of(
                new CoopFleetSnapshot.Member("m1", "wolf", "wolf_Assault", "Fang", "Vela", 0.7f, 0.9f,
                        "", "heavyarmor", "solar_shielding"));
        List<CoopFleetSnapshot.Member> stripped = List.of(stock("m1", "wolf"));

        assertSame(refit, latch.reconcile(playerFleet, true, refit));
        // A genuine roster change on a fleet that only ever takes the authoritative branch is
        // reported verbatim — the latch must never resurrect a ship's old fit here.
        assertSame(stripped, latch.reconcile(playerFleet, true, stripped));
        assertEquals(hash(stripped), hash(latch.reconcile(playerFleet, true, stripped)));
    }

    @Test
    void anUnchangedDeflatedCaptureIsHandedBackWithoutCopyingTheList() {
        // Nothing to replay means nothing to allocate: this runs once per NPC fleet per second.
        CoopInflationLatch latch = new CoopInflationLatch();
        Object fleet = new Object();
        List<CoopFleetSnapshot.Member> members = List.of(stock("m1", "hound"));
        latch.reconcile(fleet, true, members);

        List<CoopFleetSnapshot.Member> unchanged = List.of(stock("m1", "hound"));
        assertSame(unchanged, latch.reconcile(fleet, false, unchanged));
        assertSame(members, latch.reconcile(new Object(), false, members));
    }

    @Test
    void reInflationReplacesAStaleLatchRatherThanMergingWithIt() {
        // A ship that picks up a d-mod, or that the inflater refits differently, must not keep
        // streaming last hour's fit forever.
        CoopInflationLatch latch = new CoopInflationLatch();
        Object fleet = new Object();
        latch.reconcile(fleet, true, List.of(inflated("m1", "atlas")));
        CoopFleetSnapshot.Member repaired = new CoopFleetSnapshot.Member("m1", "atlas",
                "atlas_Standard", "ISS m1", "", 1f, 1f, "damagedengines", "", "");
        latch.reconcile(fleet, true, List.of(repaired));

        assertEquals(List.of(repaired), latch.reconcile(fleet, false, List.of(stock("m1", "atlas"))));
    }

    @Test
    void twoFleetsDoNotShareALatchEvenWithIdenticalMemberIds() {
        // Fleet member ids are only unique within a fleet, so the per-fleet key is load-bearing.
        CoopInflationLatch latch = new CoopInflationLatch();
        Object convoy = new Object();
        Object patrol = new Object();
        latch.reconcile(convoy, true, List.of(inflated("m1", "atlas")));
        latch.reconcile(patrol, true, List.of(inflated("m1", "enforcer")));

        assertEquals(List.of(inflated("m1", "atlas")),
                latch.reconcile(convoy, false, List.of(stock("m1", "atlas"))));
        assertEquals(List.of(inflated("m1", "enforcer")),
                latch.reconcile(patrol, false, List.of(stock("m1", "enforcer"))));
        assertEquals(2, latch.trackedFleetCount());
    }

    @Test
    void aFleetNeverSeenInflatedStreamsExactlyWhatWasRead() {
        CoopInflationLatch latch = new CoopInflationLatch();
        List<CoopFleetSnapshot.Member> members = new ArrayList<>(List.of(stock("m1", "cerberus")));

        assertSame(members, latch.reconcile(new Object(), false, members));
        assertEquals(0, latch.trackedFleetCount());
    }

    @Test
    void nullInputsAreHandedBackUntouched() {
        CoopInflationLatch latch = new CoopInflationLatch();
        List<CoopFleetSnapshot.Member> members = List.of(stock("m1", "wolf"));

        assertSame(members, latch.reconcile(null, true, members));
        assertNull(latch.reconcile(new Object(), true, null));
    }
}
