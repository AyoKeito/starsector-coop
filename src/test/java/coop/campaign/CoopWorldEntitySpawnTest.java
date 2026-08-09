package coop.campaign;

import coop.net.CoopMessages;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWorldEntitySpawnTest {

    private static CoopWorldEntitySpawn podsWith(Map<String, Integer> contents) {
        return new CoopWorldEntitySpawn("guest-player:pods_1", "cargo_pods", "corvus",
                1234.5f, -678.25f, 3.5f, -1.25f, contents);
    }

    @Test
    void encodeDecodeRoundTripsWithContents() {
        Map<String, Integer> cargo = new LinkedHashMap<>();
        cargo.put(CoopWorldEntitySpawn.key(CoopWorldEntitySpawn.ItemKind.COMMODITY, "supplies"), 120);
        cargo.put(CoopWorldEntitySpawn.key(CoopWorldEntitySpawn.ItemKind.COMMODITY, "fuel"), 50);
        CoopWorldEntitySpawn spawn = podsWith(cargo);

        CoopWorldEntitySpawn decoded = CoopWorldEntitySpawn.decode(spawn.encode());

        assertEquals(spawn, decoded);
        assertEquals(120, decoded.contents().get("COMMODITY:supplies"));
        assertEquals(50, decoded.contents().get("COMMODITY:fuel"));
    }

    @Test
    void emptyPodRoundTrips() {
        CoopWorldEntitySpawn spawn = podsWith(Map.of());
        assertEquals(spawn, CoopWorldEntitySpawn.decode(spawn.encode()));
    }

    @Test
    void encodingIsStableRegardlessOfMapOrder() {
        Map<String, Integer> ascending = new LinkedHashMap<>();
        ascending.put("COMMODITY:fuel", 50);
        ascending.put("COMMODITY:supplies", 120);
        Map<String, Integer> descending = new LinkedHashMap<>();
        descending.put("COMMODITY:supplies", 120);
        descending.put("COMMODITY:fuel", 50);

        // Iteration order must not leak into the wire format (the Phase 8 rule).
        assertEquals(podsWith(ascending).encode(), podsWith(descending).encode());
    }

    @Test
    void encodingCarriesNoJsonArrayAndNoRawSeparator() {
        CoopWorldEntitySpawn spawn = podsWith(Map.of("COMMODITY:supplies", 10));
        String encoded = spawn.encode();

        // The TCP envelope parser is flat: arrays would not survive.
        assertFalse(encoded.contains("["), "payload must not contain a JSON array");
        assertFalse(encoded.contains(String.valueOf((char) 0x1F)),
                "payload must not contain a raw datagram record separator");
    }

    @Test
    void positionAndVelocitySurviveBecauseTheyCannotBeRecomputed() {
        // Misc.addCargoPods draws its velocity from Math.random(), so the partner must be told it
        // rather than rolling its own.
        CoopWorldEntitySpawn decoded = CoopWorldEntitySpawn.decode(podsWith(Map.of()).encode());

        assertEquals(1234.5f, decoded.x(), 0.001f);
        assertEquals(-678.25f, decoded.y(), 0.001f);
        assertEquals(3.5f, decoded.velocityX(), 0.001f);
        assertEquals(-1.25f, decoded.velocityY(), 0.001f);
    }

    @Test
    void delimiterHostileValuesRoundTrip() {
        Map<String, Integer> cargo = new LinkedHashMap<>();
        cargo.put("COMMODITY:weird|commodity", 7);
        CoopWorldEntitySpawn spawn = new CoopWorldEntitySpawn(
                "guest|player:pods\n1", "cargo_pods", "loc|id", 0f, 0f, 0f, 0f, cargo);

        assertEquals(spawn, CoopWorldEntitySpawn.decode(spawn.encode()));
    }

    @Test
    void zeroAndNegativeQuantitiesAreDropped() {
        Map<String, Integer> cargo = new LinkedHashMap<>();
        cargo.put("COMMODITY:supplies", 0);
        cargo.put("COMMODITY:fuel", -5);
        cargo.put("COMMODITY:metals", 3);

        assertEquals(Map.of("COMMODITY:metals", 3), podsWith(cargo).contents());
    }

    @Test
    void truncatedPayloadIsRejectedRatherThanSilentlyAccepted() {
        String encoded = podsWith(Map.of("COMMODITY:supplies", 10, "COMMODITY:fuel", 5)).encode();
        String truncated = encoded.substring(0, encoded.lastIndexOf('\n'));

        assertThrows(IllegalArgumentException.class, () -> CoopWorldEntitySpawn.decode(truncated));
    }

    @Test
    void spawnDeltaRidesTheExistingWorldDeltaMessage() {
        CoopWorldEntitySpawn spawn = podsWith(Map.of("COMMODITY:supplies", 10));
        CoopMessages.Message message = CoopMessages.worldDelta("s1", 5L, 100L,
                spawn.coopEntityId(), CoopWorldDelta.Kind.SPAWN.name(), false, spawn.encode(), "guest");

        CoopMessages.Message decoded = CoopMessages.decode(CoopMessages.encode(message));

        assertEquals(CoopMessages.Type.WORLD_DELTA, decoded.type());
        assertEquals("SPAWN", CoopMessages.requiredPayloadString(decoded, "kind"));
        assertEquals(spawn, CoopWorldEntitySpawn.decode(
                CoopMessages.requiredPayloadString(decoded, "newStateJson")));
    }

    @Test
    void spawnIsIdempotentOnTheLedger() {
        CoopWorldEntitySpawn spawn = podsWith(Map.of("COMMODITY:supplies", 10));
        CoopWorldDelta delta = new CoopWorldDelta(spawn.coopEntityId(), CoopWorldDelta.Kind.SPAWN,
                false, spawn.encode(), "guest");
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(delta));
        assertFalse(ledger.apply(delta), "the host's echo rebroadcast must not re-materialize the pod");
    }

    @Test
    void aSpawnedEntityCanStillBeConsumedLater() {
        CoopWorldEntitySpawn spawn = podsWith(Map.of("COMMODITY:supplies", 10));
        CoopWorldDelta.Ledger ledger = new CoopWorldDelta.Ledger();

        assertTrue(ledger.apply(new CoopWorldDelta(spawn.coopEntityId(), CoopWorldDelta.Kind.SPAWN,
                false, spawn.encode(), "guest")));
        // Partner loots the pod: keyed by the same coop id, and must not be blocked by the spawn.
        assertTrue(ledger.apply(new CoopWorldDelta(spawn.coopEntityId(), CoopWorldDelta.Kind.CONSUME,
                true, "", "host")));
        assertTrue(ledger.isConsumed(spawn.coopEntityId()));
        assertFalse(ledger.apply(new CoopWorldDelta(spawn.coopEntityId(), CoopWorldDelta.Kind.CONSUME,
                true, "", "guest")), "no double-loot");
    }

    // ---- Consume-watcher scope (Phase 12d widening) ----------------------------------------------

    @Test
    void salvageTaggedEntitiesAreStillTracked() {
        assertTrue(CoopCampaignReplicator.shouldTrackForConsume(false, true, false, false));
    }

    @Test
    void customEntitiesAreTrackedSoPodsAndMakeshiftStructuresCount() {
        // cargo_pods is tagged [has_interaction_dialog, neutrino, salvage_music] and
        // nav_buoy_makeshift is [nav_buoy, neutrino_high, objective, makeshift]. Neither carries
        // salvageable, which is why the old allowlist reported nothing for either.
        assertTrue(CoopCampaignReplicator.shouldTrackForConsume(false, false, false, true));
    }

    @Test
    void coopReplicatedEntitiesAreTrackedEvenIfNothingElseMatches() {
        assertTrue(CoopCampaignReplicator.shouldTrackForConsume(false, false, true, false));
    }

    @Test
    void fleetsAreNeverTrackedBecausePhase9OwnsThem() {
        assertFalse(CoopCampaignReplicator.shouldTrackForConsume(true, true, true, true));
    }

    @Test
    void plainWorldgenEntitiesLikePlanetsAreNotTracked() {
        assertFalse(CoopCampaignReplicator.shouldTrackForConsume(false, false, false, false));
    }
}
