package coop.seed;

import com.fs.starfarer.api.characters.CharacterCreationData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSeedSyncTest {
    @Test
    void formatSeedStringIsStableHexOfTheLong() {
        // Direct formatSeedString coverage; the old seedData(long) factory was deleted in 6b
        // because it built a self-inconsistent pair (everywhere else the long is SHA-derived FROM
        // the string, so round-tripping its output produced a different long).
        assertEquals("coop-00000000075bcd15", CoopSeedSync.formatSeedString(123456789L));
    }

    @Test
    void appliesSeedToCharacterCreationDataBeforeProcgen() {
        Map<String, Object> calls = new HashMap<>();
        CharacterCreationData data = recordingCharacterCreationData(calls);
        CoopSeedSync.SeedData seed = new CoopSeedSync.SeedData(987654321L, "coop-seed", "fingerprint-a");

        CoopSeedSync.applyToCharacterCreationData(data, seed);

        assertEquals(987654321L, calls.get("seed"));
        assertEquals("coop-seed", calls.get("seedString"));
    }

    @Test
    void storesSeedDataInPersistentData() {
        Map<String, Object> persistentData = new HashMap<>();
        CoopSeedSync.SeedData seed = new CoopSeedSync.SeedData(42L, "coop-42", "fingerprint-a");

        CoopSeedSync.storePersistentData(persistentData, seed);

        assertEquals(42L, persistentData.get(CoopSeedSync.PERSISTENT_SEED_LONG));
        assertEquals("coop-42", persistentData.get(CoopSeedSync.PERSISTENT_SEED_STRING));
        assertEquals("fingerprint-a", persistentData.get(CoopSeedSync.PERSISTENT_SECTOR_FINGERPRINT));
    }

    @Test
    void seedDataFromVisibleSeedStringIsStableAndPreservesSeedString() {
        CoopSeedSync.SeedData first = CoopSeedSync.seedDataFromSeedString("MN-2587421401119275744");
        CoopSeedSync.SeedData second = CoopSeedSync.seedDataFromSeedString("MN-2587421401119275744");
        CoopSeedSync.SeedData different = CoopSeedSync.seedDataFromSeedString("MN-2587421401119275745");

        assertEquals("MN-2587421401119275744", first.seedString());
        assertEquals(first.seedLong(), second.seedLong());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.seedLong(), different.seedLong());
    }

    @Test
    void seedDataFromSeedStringProducesPositiveSeedLong() {
        // Vanilla SectorProcGen.prepare skips setSeed when seed <= 0,
        // so a stable seed derived from a string must be positive.
        for (String input : new String[]{
                "MN-1234567890123456789",
                "MN-4556855818685483012",
                "coop-test-shared",
                "a",
                "negative-source-zzzzzzz"
        }) {
            assertTrue(CoopSeedSync.seedDataFromSeedString(input).seedLong() > 0L,
                    "expected positive seedLong for " + input);
        }
    }

    @Test
    void seedStringMismatchReportsOnlyDifferentVisibleSeedStrings() {
        assertEquals("", CoopSeedSync.seedStringMismatch(" MN-2587421401119275744 ",
                "MN-2587421401119275744"));
        assertEquals("seedString: host=MN-2587421401119275744 guest=MN-2587421401119275745",
                CoopSeedSync.seedStringMismatch("MN-2587421401119275744", "MN-2587421401119275745"));
    }

    private static CharacterCreationData recordingCharacterCreationData(Map<String, Object> calls) {
        return (CharacterCreationData) Proxy.newProxyInstance(
                CharacterCreationData.class.getClassLoader(),
                new Class<?>[]{CharacterCreationData.class},
                (proxy, method, args) -> {
                    if ("setSeed".equals(method.getName())) {
                        calls.put("seed", args[0]);
                        return null;
                    }
                    if ("setSeedString".equals(method.getName())) {
                        calls.put("seedString", args[0]);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }
}
