package coop.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoopNpcFleetSetSnapshotTest {
    private static CoopNpcFleetSnapshot fleet(String id, String faction, String location, String hullId) {
        return fleet(id, faction, location, hullId, true);
    }

    private static CoopNpcFleetSnapshot fleet(String id, String faction, String location, String hullId,
                                              boolean transponderOn) {
        return fleet(id, faction, "Name " + id, location, hullId, transponderOn, "");
    }

    private static CoopNpcFleetSnapshot fleet(String id, String faction, String name, String location,
                                              String hullId, boolean transponderOn, String actionText) {
        return CoopNpcFleetSnapshot.create(id, faction, name, location, 1f, 2f, 0f, 0f,
                transponderOn, sensors(150f, 90f), actionText,
                List.of(new CoopFleetSnapshot.Member("m-" + id, hullId, hullId + "_Standard",
                        "Ship", "Cpt", 0.8f, 1.0f)));
    }

    @Test
    void setHashIsIndependentOfFleetOrder() {
        List<CoopNpcFleetSnapshot> a = List.of(
                fleet("f3", "pirates", "corvus", "wolf"),
                fleet("f1", "hegemony", "corvus", "lasher"),
                fleet("f2", "independent", "hyperspace", "kite"));
        List<CoopNpcFleetSnapshot> b = List.of(
                fleet("f2", "independent", "hyperspace", "kite"),
                fleet("f3", "pirates", "corvus", "wolf"),
                fleet("f1", "hegemony", "corvus", "lasher"));

        assertEquals(CoopNpcFleetSetSnapshot.computeSetHash(a), CoopNpcFleetSetSnapshot.computeSetHash(b));
    }

    @Test
    void setHashChangesOnMembership() {
        List<CoopNpcFleetSnapshot> base = List.of(fleet("f1", "hegemony", "corvus", "lasher"));
        String baseHash = CoopNpcFleetSetSnapshot.computeSetHash(base);

        assertNotEquals(baseHash, CoopNpcFleetSetSnapshot.computeSetHash(List.of(
                fleet("f1", "hegemony", "corvus", "lasher"),
                fleet("f2", "pirates", "corvus", "wolf"))));
    }

    @Test
    void setHashChangesOnFleetLocationOrRoster() {
        String baseHash = CoopNpcFleetSetSnapshot.computeSetHash(
                List.of(fleet("f1", "hegemony", "corvus", "lasher")));

        // Same fleet, different system -> the guest must re-apply (re-add in the new location).
        assertNotEquals(baseHash, CoopNpcFleetSetSnapshot.computeSetHash(
                List.of(fleet("f1", "hegemony", "magec", "lasher"))));
        // Same fleet, different roster.
        assertNotEquals(baseHash, CoopNpcFleetSetSnapshot.computeSetHash(
                List.of(fleet("f1", "hegemony", "corvus", "onslaught"))));
    }

    @Test
    void setHashChangesOnTransponderToggle() {
        // The set is the only carrier of transponder state (the 10 Hz motion datagram omits it), and on
        // the guest that flag decides whether a mirror is faction-identified across its whole detection
        // range or only inside 10% of it. If a toggle does not move the hash, the guest never learns.
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(
                        fleet("f1", "hegemony", "corvus", "lasher", true))),
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(
                        fleet("f1", "hegemony", "corvus", "lasher", false))));
    }

    @Test
    void setHashChangesOnFaction() {
        // A faction change re-colours the mirror and re-decides who is hostile to whom; the guest has
        // to act on it in the tick it happens, so it stays structural.
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(fleet("f1", "hegemony", "corvus", "lasher"))),
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(fleet("f1", "pirates", "corvus", "lasher"))));
    }

    @Test
    void setHashIgnoresActionTextButTheSoftHashDoesNot() {
        // 2026-09-13: cosmetic tooltip prose left the structural hash. Live measurement that session
        // was 291 structural sends against 8 health sends, the whole 33-fleet set going out once a
        // second, because in a busy system somebody's line flips from "returning to X" to "delivering
        // Y to Z" on nearly every tick. It still has to reach the guest (the set is its only carrier),
        // so it moved to the soft hash behind the 10 s floor instead of being dropped.
        List<CoopNpcFleetSnapshot> travelling = List.of(fleet("f1", "hegemony", "Name f1",
                "corvus", "lasher", true, "traveling to Jangala"));
        List<CoopNpcFleetSnapshot> pursuing = List.of(fleet("f1", "hegemony", "Name f1",
                "corvus", "lasher", true, "pursuing your fleet"));

        assertEquals(CoopNpcFleetSetSnapshot.computeSetHash(travelling),
                CoopNpcFleetSetSnapshot.computeSetHash(pursuing));
        assertNotEquals(CoopNpcFleetSetSnapshot.computeSoftHash(travelling),
                CoopNpcFleetSetSnapshot.computeSoftHash(pursuing));
    }

    @Test
    void setHashChangesOnName() {
        // Same carrier argument as action text: refreshIdentity only sees a rename if a set arrives.
        // Name stayed structural on 2026-09-13 when action text moved out — a fleet is named at spawn
        // and keeps it, so renames are rare events rather than per-tick churn, and holding one behind
        // the 10 s floor would only buy a visibly stale label.
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(fleet("f1", "hegemony", "Patrol",
                        "corvus", "lasher", true, ""))),
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(fleet("f1", "hegemony", "Trade Convoy",
                        "corvus", "lasher", true, ""))));
    }

    // ---- health hash: the second send trigger -----------------------------------------------------

    /** Same fixture as above but with the member's CR and hull under the caller's control. */
    private static CoopNpcFleetSnapshot damaged(String id, float cr, float hullFraction) {
        return CoopNpcFleetSnapshot.create(id, "hegemony", "Name " + id, "corvus", 1f, 2f, 0f, 0f,
                true, sensors(150f, 90f), "",
                List.of(new CoopFleetSnapshot.Member("m-" + id, "lasher", "lasher_Standard",
                        "Ship", "Cpt", cr, hullFraction)));
    }

    @Test
    void healthHashChangesOnCombatReadiness() {
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeHealthHash(List.of(damaged("f1", 0.20f, 1.0f))),
                CoopNpcFleetSetSnapshot.computeHealthHash(List.of(damaged("f1", 0.70f, 1.0f))));
    }

    @Test
    void healthHashChangesOnHullFraction() {
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeHealthHash(List.of(damaged("f1", 0.80f, 0.30f))),
                CoopNpcFleetSetSnapshot.computeHealthHash(List.of(damaged("f1", 0.80f, 1.00f))));
    }

    @Test
    void healthHashIgnoresMovementInsideOneBucket() {
        // 5% buckets: a ship recovering CR a thousandth at a time must not put a full set on the wire
        // every tick. That is the whole reason CR left the structural hash in 2026-08-17.
        assertEquals(
                CoopNpcFleetSetSnapshot.computeHealthHash(List.of(damaged("f1", 0.500f, 1.0f))),
                CoopNpcFleetSetSnapshot.computeHealthHash(List.of(damaged("f1", 0.510f, 1.0f))));
    }

    @Test
    void healthHashIsIndependentOfFleetOrder() {
        List<CoopNpcFleetSnapshot> a = List.of(
                damaged("f3", 0.20f, 0.35f), damaged("f1", 0.90f, 1.0f), damaged("f2", 0.55f, 0.75f));
        List<CoopNpcFleetSnapshot> b = List.of(
                damaged("f2", 0.55f, 0.75f), damaged("f3", 0.20f, 0.35f), damaged("f1", 0.90f, 1.0f));

        assertEquals(CoopNpcFleetSetSnapshot.computeHealthHash(a),
                CoopNpcFleetSetSnapshot.computeHealthHash(b));
    }

    @Test
    void setHashStaysStructuralWhenOnlyHealthMoves() {
        // Regression pin. If health ever leaks into the structural hash, every repairing fleet flips
        // fleetHash again and the guest goes back to a full roster teardown per second (39 fps,
        // 2026-08-17) — and CoopFleetMirrorRegistry's freeze release starts firing on repair.
        assertEquals(
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(damaged("f1", 0.20f, 0.30f))),
                CoopNpcFleetSetSnapshot.computeSetHash(List.of(damaged("f1", 1.00f, 1.00f))));
    }

    @Test
    void softHashCarriesHealthAsWellAsText() {
        // One trigger, two contents: the soft hash has to move for either, or the rate-limited path
        // stops being the carrier for whichever half it dropped.
        assertNotEquals(
                CoopNpcFleetSetSnapshot.computeSoftHash(List.of(damaged("f1", 0.20f, 1.0f))),
                CoopNpcFleetSetSnapshot.computeSoftHash(List.of(damaged("f1", 0.70f, 1.0f))));
        assertEquals(
                CoopNpcFleetSetSnapshot.computeSoftHash(List.of(damaged("f1", 0.500f, 1.0f))),
                CoopNpcFleetSetSnapshot.computeSoftHash(List.of(damaged("f1", 0.510f, 1.0f))));
    }

    @Test
    void softHashIsIndependentOfFleetOrder() {
        List<CoopNpcFleetSnapshot> a = List.of(
                fleet("f3", "pirates", "Name f3", "corvus", "wolf", true, "orbiting Corvus I"),
                fleet("f1", "hegemony", "Name f1", "corvus", "lasher", true, "traveling to Jangala"));
        List<CoopNpcFleetSnapshot> b = List.of(
                fleet("f1", "hegemony", "Name f1", "corvus", "lasher", true, "traveling to Jangala"),
                fleet("f3", "pirates", "Name f3", "corvus", "wolf", true, "orbiting Corvus I"));

        assertEquals(CoopNpcFleetSetSnapshot.computeSoftHash(a),
                CoopNpcFleetSetSnapshot.computeSoftHash(b));
    }

    /**
     * The payload number the Phase 20 diet is argued from: what one representative
     * {@code NPC_FLEET_SET} body actually costs on the wire. Thirty fleets of five ships each, with
     * realistic names, captains and action lines — the shape of a busy trade system, which is where
     * the 2026-09-13 measurement of one full set per second was taken.
     *
     * <p>Measured 2026-09-13: <b>20,827 bytes</b> UTF-8 for the encoded set body, ~139 bytes per
     * replicated ship (the flat envelope and its JSON escaping sit on top of that). At the pre-fix
     * rate of one structural send per second — which is what a busy trade system actually produced,
     * because cosmetic action text was in the structural hash — that is ~20 KB/s of reliable TCP.
     * Behind the 10 s soft floor a text-only change costs at most ~2 KB/s.
     */
    @Test
    void aThirtyFleetSetEncodesToAboutTwentyKilobytes() {
        List<CoopNpcFleetSnapshot> fleets = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<CoopFleetSnapshot.Member> members = new java.util.ArrayList<>();
            for (int m = 0; m < 5; m++) {
                members.add(new CoopFleetSnapshot.Member("mem_" + i + "_" + m, "enforcer",
                        "enforcer_Assault", "TTS Vigilance " + i + "-" + m, "Captain Yaroslav",
                        0.7f, 0.95f));
            }
            fleets.add(CoopNpcFleetSnapshot.create("fleet_" + i, "hegemony",
                    "Hegemony Patrol Fleet " + i, "corvus", 1234.5f, -6789.25f, 12.5f, -3.25f,
                    true, sensors(150f, 90f), "delivering supplies to Jangala", members));
        }

        int bytes = CoopNpcFleetSetSnapshot.create(fleets).encode()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        // Loose bounds: this pins the order of magnitude the plan quotes, not the exact fixture.
        org.junit.jupiter.api.Assertions.assertTrue(bytes > 15_000 && bytes < 28_000,
                "representative 30-fleet set encoded to " + bytes + " bytes");
    }

    @Test
    void encodeDecodeRoundTripsWholeSet() {
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of(
                fleet("f1", "hegemony", "corvus", "lasher"),
                fleet("f2", "pi|rates", "hyper\nspace", "wolf")));

        CoopNpcFleetSetSnapshot decoded = CoopNpcFleetSetSnapshot.decode(set.encode());

        assertEquals(set, decoded);
        assertEquals(set.setHash(), decoded.setHash());
        assertEquals("pi|rates", decoded.fleets().get(1).factionId());
    }

    @Test
    void emptySetRoundTrips() {
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(List.of());
        assertEquals(set, CoopNpcFleetSetSnapshot.decode(set.encode()));
    }

    @Test
    void motionBatchRoundTrips() {
        List<CoopNpcFleetMotion> motions = List.of(
                new CoopNpcFleetMotion("f1", "corvus", 10.5f, -20.25f, 1.5f, -0.5f, sensors(220.5f, 90f)),
                new CoopNpcFleetMotion("f|2", "hyper\nspace", 0f, 0f, 0f, 0f, CoopSensorSync.Profile.UNKNOWN));

        List<CoopNpcFleetMotion> decoded = CoopNpcFleetMotion.decodeSection(
                CoopNpcFleetMotion.encodeFullSection(motions), null);

        assertEquals(motions, decoded);
    }

    @Test
    void emptyMotionBatchRoundTrips() {
        assertEquals(List.of(), CoopNpcFleetMotion.decodeSection(
                CoopNpcFleetMotion.encodeFullSection(List.of()), null));
    }

    /** Phase 14b sensor identity fixture: profile + the three detected-range aggregates + strength. */
    private static CoopSensorSync.Profile sensors(float profile, float strength) {
        return new CoopSensorSync.Profile(profile, 0f, 0f, 1f, strength);
    }
}
