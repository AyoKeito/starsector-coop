package coop.campaign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The origin-namespaced fleet-member id (Phase 32, P1-2).
 *
 * <p>The property that matters is negative and easy to lose: a coop wire id must be an id neither
 * engine's {@code Misc.genUID()} can ever mint, because the whole defect is two engines drawing
 * member ids from the same counter and one of them stamping its draws into the other's locker.
 */
class CoopMemberIdsTest {

    @Test
    void aRawMemberIdIsNamespacedWithItsOriginPlayer() {
        assertEquals("c_guest-player_8f9a", CoopMemberIds.wireId("guest-player", "8f9a"));
        assertEquals("c_host-player_8f9a", CoopMemberIds.wireId("host-player", "8f9a"));
        assertNotEquals(CoopMemberIds.wireId("guest-player", "8f9a"),
                CoopMemberIds.wireId("host-player", "8f9a"),
                "the same genUID drawn on both engines must not name the same hull");
    }

    @Test
    void namespacingIsIdempotentSoARoundTripKeepsOneName() {
        // guest deposits -> host rebuilds with the wire id -> host re-captures for its snapshot.
        // A second prefix there would rename the hull mid-flight and orphan the guest's own copy.
        String once = CoopMemberIds.wireId("guest-player", "8f9a");
        assertEquals(once, CoopMemberIds.wireId("host-player", once));
        assertEquals(once, CoopMemberIds.wireId("guest-player", once));
    }

    @Test
    void aWireIdCanNeverCollideWithAGenUidShapedId() {
        // Misc.genUID() delegates to a per-sector counter rendered as short lowercase hex: no
        // underscore, no letter past f. The c_ prefix is outside that alphabet by construction.
        for (String genUid : new String[]{"8f9a", "364d", "8fa4", "p_341c", "341d"}) {
            assertFalse(CoopMemberIds.isCoopId(genUid), genUid + " must not read as a coop id");
            assertNotEquals(genUid, CoopMemberIds.wireId("guest-player", genUid));
            assertTrue(CoopMemberIds.isCoopId(CoopMemberIds.wireId("guest-player", genUid)));
        }
    }

    @Test
    void theOriginEngineRecognisesItsOwnShipUnderTheWireId() {
        String wire = CoopMemberIds.wireId("guest-player", "8f9a");
        assertTrue(CoopMemberIds.matchesLocal(wire, "8f9a", "guest-player"),
                "the depositor's real ship keeps its local id; nothing rewrites a live object");
        assertFalse(CoopMemberIds.matchesLocal(wire, "8f9a", "host-player"),
                "and the other engine's like-numbered hull is not it");
    }

    @Test
    void theReceivingEngineRecognisesItsRebuildByPlainEquality() {
        String wire = CoopMemberIds.wireId("guest-player", "8f9a");
        assertTrue(CoopMemberIds.matchesLocal(wire, wire, "host-player"),
                "a rebuilt member is setId()'d to the wire id, so equality is the match");
    }

    @Test
    void aBlankIdIsNeverNamespacedAndNeverMatches() {
        assertEquals("", CoopMemberIds.wireId("guest-player", null));
        assertEquals("", CoopMemberIds.wireId("guest-player", "   "));
        assertFalse(CoopMemberIds.matchesLocal("c_guest-player_", "", "guest-player"));
        assertFalse(CoopMemberIds.matchesLocal(null, "8f9a", "guest-player"));
    }

    @Test
    void aPlayerIdWithSeparatorsInItStillProducesAPlainToken() {
        // Real ids are UUIDs, for which this is the identity; the sanitiser is here so a future id
        // scheme cannot smuggle a '|' into a CoopDelimited field or a space into a save-file id.
        assertEquals("c_a-b-c_1", CoopMemberIds.wireId("a|b c", "1"));
        assertEquals("c_anon_1", CoopMemberIds.wireId(null, "1"));
        assertEquals("c_1e4d0c2a-4b6f-4a11-9d3e-000000000001_8f9a",
                CoopMemberIds.wireId("1e4d0c2a-4b6f-4a11-9d3e-000000000001", "8f9a"));
    }
}
