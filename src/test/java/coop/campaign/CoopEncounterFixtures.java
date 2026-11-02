package coop.campaign;

import java.util.List;

import static coop.campaign.CoopEncounterRecord.*;

final class CoopEncounterFixtures {
    private CoopEncounterFixtures() { }

    static String id(String instance, Role role, int ordinal) { return entityId(instance, role, ordinal); }

    static CoopEncounterRecord sample(String instance) {
        String star = id(instance, Role.SYSTEM, 0), planet = id(instance, Role.SYSTEM, 1);
        String wreck = id(instance, Role.SYSTEM, 2), vambrace = id(instance, Role.SYSTEM, 3);
        String consumed = id(instance, Role.SYSTEM, 4);
        return new CoopEncounterRecord(instance, Long.MIN_VALUE, "EP|\\\n\r", -12345.125f, 9876.5f,
                3.25f, true, Creator.DIRE_HINTS, true,
                List.of(new Entity(Role.SYSTEM, 0, "black_hole"), new Entity(Role.SYSTEM, 1, "gas_giant"),
                        new Entity(Role.SYSTEM, 2, "wreck"), new Entity(Role.SYSTEM, 3, "derelict_vambrace"),
                        new Entity(Role.SYSTEM, 4, "salvage"), new Entity(Role.ANCHOR, 0, ""),
                        new Entity(Role.JUMP_POINT, 0, "jump_point"), new Entity(Role.WELL, 0, "nascent_gravity_well")),
                List.of(new Special(SpecialType.GHOST_WRECK, wreck, new Orbit(planet, 71.5f, 400f, 38.25f),
                                CoopWreckFixtures.sample(wreckId(wreck)),
                                List.of(new CoopWreckRecord.MemoryValue("$gsType", "STRING", "GS_OPIS|\\\n", -1))),
                        new Special(SpecialType.VAMBRACE, vambrace, new Orbit(planet, 300, 500, 100), null, List.of()),
                        new Special(SpecialType.PLANET_FLAGS, star, null, null,
                                List.of(new CoopWreckRecord.MemoryValue("$abyssalBlackHoleReadings", "BOOLEAN", "true", -1))),
                        new Special(SpecialType.PLANET_FLAGS, planet, null, null,
                                List.of(new CoopWreckRecord.MemoryValue("$abyssalGasGiantTurbulence", "BOOLEAN", "true", -1),
                                        new CoopWreckRecord.MemoryValue("$abyssalPlanetoidMiningOp", "BOOLEAN", "true", -1)))),
                new Overlay(List.of(consumed), List.of(new EntityState(planet, "FULL", true,
                        List.of(new CoopWreckRecord.MemoryValue("$visited", "BOOLEAN", "true", -1))))));
    }
}
