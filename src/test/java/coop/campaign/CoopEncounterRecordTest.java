package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static coop.campaign.CoopEncounterRecord.*;
import static org.junit.jupiter.api.Assertions.*;

class CoopEncounterRecordTest {
    @Test void completeCreationAndSurvivingOverlayRoundTripWithoutNativeObjects() {
        CoopEncounterRecord recipe = CoopEncounterFixtures.sample("host-uuid");
        assertEquals(recipe, CoopEncounterRecord.decode(recipe.encode()));
        assertEquals(Long.MIN_VALUE, recipe.effectiveSeed());
        assertEquals(3.25f, recipe.depth());
        assertTrue(recipe.threatRollRan());
        assertEquals(Creator.DIRE_HINTS, recipe.creator());
        assertEquals(8, recipe.entities().size());
        assertEquals(4, recipe.specials().size());
        assertEquals(1, recipe.overlay().consumedIds().size());
        assertEquals("FULL", recipe.overlay().entities().get(0).surveyLevel());
    }

    @Test void creatorClassAllowListDoesNotAcceptOtherEpecSubclasses() {
        for (Creator creator : Creator.values()) assertEquals(creator, Creator.fromClassName(creator.className()));
        assertThrows(IllegalArgumentException.class, () -> Creator.fromClassName("modded.EPEC"));
        assertThrows(IllegalArgumentException.class, () -> Creator.fromClassName(null));
    }

    @Test void identityNamesRolesWithoutReindexingAfterConsumption() {
        CoopEncounterRecord recipe = CoopEncounterFixtures.sample("uuid");
        Entity original = new Entity(Role.SYSTEM, 4, "salvage");
        assertEquals("coopenc_uuid_system4", recipe.entityId(original));
        assertEquals("coopenc_uuid_jump_point0", entityId("uuid", Role.JUMP_POINT, 0));
        assertEquals("wreck:coopenc_uuid_system4", wreckId(recipe.entityId(original)));
        CoopEncounterRecord allGone = recipe.withOverlay(new Overlay(
                recipe.entities().stream().map(recipe::entityId).toList(), List.of()));
        assertEquals(recipe.entities(), allGone.entities());
        assertEquals(recipe.specials(), allGone.specials());
    }

    @Test void entityAndOverlayCollectionsAreCanonicalAndDefensive() {
        CoopEncounterRecord recipe = CoopEncounterFixtures.sample("uuid");
        List<Entity> entities = new ArrayList<>(recipe.entities()); Collections.reverse(entities);
        List<Special> specials = new ArrayList<>(recipe.specials()); Collections.reverse(specials);
        CoopEncounterRecord reversed = copy(recipe, entities, specials, recipe.overlay());
        entities.clear(); specials.clear();
        assertEquals(recipe, reversed);
        assertThrows(UnsupportedOperationException.class, () -> reversed.entities().clear());
        assertThrows(UnsupportedOperationException.class, () -> reversed.specials().clear());
        assertThrows(UnsupportedOperationException.class, () -> reversed.overlay().consumedIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> reversed.overlay().entities().clear());
    }

    @Test void overlayNullableNativeFieldsRemainDistinctFromFalseAndNone() {
        EntityState empty = new EntityState(entityId("uuid", Role.SYSTEM, 0), null, null, List.of());
        EntityState explicit = new EntityState(empty.entityId(), "NONE", false, List.of());
        assertNotEquals(empty, explicit);
        assertEquals(empty, CoopEncounterCodec.decodeEntityState(CoopEncounterCodec.encodeEntityState(empty)));
        assertEquals(explicit, CoopEncounterCodec.decodeEntityState(CoopEncounterCodec.encodeEntityState(explicit)));
    }

    @Test void overlayRejectsUnknownConsumedAndSurvivingEntities() {
        CoopEncounterRecord recipe = CoopEncounterFixtures.sample("uuid");
        String other = entityId("different", Role.SYSTEM, 0);
        assertThrows(IllegalArgumentException.class, () -> recipe.withOverlay(new Overlay(List.of(other), List.of())));
        assertThrows(IllegalArgumentException.class, () -> recipe.withOverlay(new Overlay(List.of(),
                List.of(new EntityState(other, null, null, List.of())))));
        String consumed = recipe.overlay().consumedIds().get(0);
        assertThrows(IllegalArgumentException.class, () -> recipe.withOverlay(new Overlay(List.of(consumed),
                List.of(new EntityState(consumed, null, null, List.of())))));
    }

    @Test void membershipAndSpecialIdentityMustBeUniqueAndResolved() {
        CoopEncounterRecord recipe = CoopEncounterFixtures.sample("uuid");
        List<Entity> duplicate = new ArrayList<>(recipe.entities()); duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> copy(recipe, duplicate, recipe.specials(), recipe.overlay()));
        List<Special> duplicateSpecial = new ArrayList<>(recipe.specials()); duplicateSpecial.add(duplicateSpecial.get(0));
        assertThrows(IllegalArgumentException.class, () -> copy(recipe, recipe.entities(), duplicateSpecial, recipe.overlay()));
        Special unknownTarget = new Special(SpecialType.VAMBRACE, entityId("other", Role.SYSTEM, 0), null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> copy(recipe, recipe.entities(), List.of(unknownTarget), recipe.overlay()));
        Special unknownFocus = new Special(SpecialType.VAMBRACE, entityId("uuid", Role.SYSTEM, 3),
                new Orbit(entityId("other", Role.SYSTEM, 0), 0, 100, 10), null, List.of());
        assertThrows(IllegalArgumentException.class, () -> copy(recipe, recipe.entities(), List.of(unknownFocus), recipe.overlay()));
    }

    @Test void specialsRejectMissingOrMismatchedWreckRecipe() {
        String id = entityId("uuid", Role.SYSTEM, 0);
        assertThrows(IllegalArgumentException.class, () -> new Special(SpecialType.GHOST_WRECK, id, null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Special(SpecialType.GHOST_WRECK, id, null,
                CoopWreckFixtures.sample("wreck:wrong"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Special(SpecialType.VAMBRACE, id, null,
                CoopWreckFixtures.sample(wreckId(id)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Special(SpecialType.PLANET_FLAGS, id,
                new Orbit(id, 0, 100, 10), null, List.of()));
    }

    @Test void overlayAllowsOnlyKnownTypedHintsWithoutSectorState() {
        String id = entityId("uuid", Role.SYSTEM, 0);
        for (String key : MEMORY_KEYS) {
            CoopWreckRecord.MemoryValue value = new CoopWreckRecord.MemoryValue(key,
                    key.equals("$gsType") ? "STRING" : "BOOLEAN", key.equals("$gsType") ? "GS_OPIS" : "true", -1);
            assertEquals(value, new EntityState(id, null, null, List.of(value)).memory().get(0));
        }
        assertThrows(IllegalArgumentException.class, () -> new EntityState(id, null, null,
                List.of(new CoopWreckRecord.MemoryValue("$threatSpawnsFailedToRoll", "LONG", "2", -1))));
        assertThrows(IllegalArgumentException.class, () -> new EntityState(id, null, null,
                List.of(new CoopWreckRecord.MemoryValue("$visited", "STRING", "true", -1))));
        assertThrows(IllegalArgumentException.class, () -> new EntityState(id, null, null,
                List.of(new CoopWreckRecord.MemoryValue("$gsType", "BOOLEAN", "true", -1))));
        assertThrows(IllegalArgumentException.class, () -> new EntityState(id, "UNKNOWN", null, List.of()));
    }

    @Test void duplicateMemoryAndOverlayIdsAreRejected() {
        String id = entityId("uuid", Role.SYSTEM, 0);
        EntityState state = new EntityState(id, null, null, List.of());
        CoopWreckRecord.MemoryValue value = new CoopWreckRecord.MemoryValue("$visited", "BOOLEAN", "true", -1);
        assertThrows(IllegalArgumentException.class, () -> new EntityState(id, null, null, List.of(value, value)));
        assertThrows(IllegalArgumentException.class, () -> new Overlay(List.of(id, id), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Overlay(List.of(), List.of(state, state)));
    }

    @Test void identityNumericAndCollectionBoundsFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> entityId("invalid/id", Role.SYSTEM, 0));
        assertThrows(IllegalArgumentException.class, () -> entityId("a".repeat(129), Role.SYSTEM, 0));
        assertThrows(IllegalArgumentException.class, () -> new Entity(Role.SYSTEM, -1, ""));
        assertThrows(IllegalArgumentException.class, () -> new Entity(Role.SYSTEM, MAX_ENTITIES, ""));
        assertThrows(IllegalArgumentException.class, () -> new EntityState("coopenc_uuid_system000", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EntityState("coopenc_uuid_system4096", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Orbit(entityId("uuid", Role.SYSTEM, 0), Float.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Orbit(entityId("uuid", Role.SYSTEM, 0), 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Orbit(entityId("uuid", Role.SYSTEM, 0), 0, 1, 0));
        CoopEncounterRecord recipe = CoopEncounterFixtures.sample("uuid");
        assertThrows(IllegalArgumentException.class, () -> copy(recipe, List.of(), recipe.specials(), recipe.overlay()));
        assertThrows(IllegalArgumentException.class, () -> copy(recipe,
                Collections.nCopies(MAX_ENTITIES + 1, recipe.entities().get(0)), List.of(), Overlay.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CoopEncounterRecord(2, "uuid", 0, "ep", 0, 0, 0,
                false, Creator.PARENT, false, recipe.entities(), List.of(), Overlay.empty()));
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 1_000_001})
            assertThrows(IllegalArgumentException.class, () -> new CoopEncounterRecord("uuid", 0, "ep", value, 0, 0,
                    false, Creator.PARENT, false, recipe.entities(), List.of(), Overlay.empty()));
    }

    static CoopEncounterRecord copy(CoopEncounterRecord source, List<Entity> entities,
                                    List<Special> specials, Overlay overlay) {
        return new CoopEncounterRecord(source.version(), source.instanceId(), source.effectiveSeed(),
                source.encounterPointId(), source.x(), source.y(), source.depth(), source.threatRollRan(),
                source.creator(), source.threatTag(), entities, specials, overlay);
    }
}
