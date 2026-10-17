package coop.campaign;

import java.util.List;

/** Shared pure recipe for authority and codec regression tests. */
public final class CoopWreckFixtures {
    private CoopWreckFixtures() { }

    public static CoopWreckRecord sample(String id) {
        CoopWreckRecord.Ship ship = new CoopWreckRecord.Ship("BATTERED", "hound_luddic_path", null,
                null, "ISS Witness", null, true, true, null, 0.05f);
        return new CoopWreckRecord(1, id, "native-" + id, "Derelict Ship", "neutral",
                -1234f, 5678f, 7f, -3f, 150f, -8f, 4.2f, 25f, null, true,
                ship, 123456789L, new CoopWreckRecord.Recovery(null, null, null, null, List.of(ship)), null,
                List.of(new CoopWreckRecord.Drop("basic", -1, -1, 1000, 1f, List.of())), List.of(),
                CoopWreckRecord.ExtraCargo.absent(), CoopWreckRecord.ExtraCargo.absent(),
                50f, 150f, 1f, false, 25f,
                List.of(new CoopWreckRecord.Modifier("FLAT", "gen", null, 800f)),
                List.of("expires"), List.of(new CoopWreckRecord.MemoryValue("$fromGhost", "BOOLEAN", "true", -1f)));
    }
}
