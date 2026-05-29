package coop.seed;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import coop.util.CoopLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

public final class CoopSeedSync {
    public static final String PERSISTENT_SEED_LONG = "coop.seedLong";
    public static final String PERSISTENT_SEED_STRING = "coop.seedString";
    public static final String PERSISTENT_SECTOR_FINGERPRINT = "coop.sectorFingerprint";

    private CoopSeedSync() {
    }

    public record SeedData(long seedLong, String seedString, String sectorFingerprint) {
        public SeedData {
            seedString = requireText(seedString, "seedString");
            sectorFingerprint = sectorFingerprint == null ? "" : sectorFingerprint.trim();
        }

        public SeedData withFingerprint(String fingerprint) {
            return new SeedData(seedLong, seedString, fingerprint);
        }
    }

    public static SeedData seedData(long seedLong) {
        return new SeedData(seedLong, formatSeedString(seedLong), "");
    }

    public static SeedData seedDataFromSeedString(String seedString) {
        String normalized = requireText(seedString, "seedString");
        return new SeedData(stableSeedLong(normalized), normalized, "");
    }

    public static SeedData seedForLoadedSector() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                throw new IllegalStateException("No loaded sector");
            }
            return seedDataFromSeedString(sector.getSeedString());
        } catch (RuntimeException | LinkageError ex) {
            throw new IllegalStateException("Loaded sector seed string is unavailable", ex);
        }
    }

    public static String formatSeedString(long seedLong) {
        return "coop-" + String.format("%016x", seedLong);
    }

    public static void applyToCharacterCreationData(CharacterCreationData data, SeedData seed) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(seed, "seed");

        data.setSeed(seed.seedLong());
        data.setSeedString(seed.seedString());
    }

    public static void storePersistentData(Map<String, Object> persistentData, SeedData seed) {
        Objects.requireNonNull(persistentData, "persistentData");
        Objects.requireNonNull(seed, "seed");

        persistentData.put(PERSISTENT_SEED_LONG, seed.seedLong());
        persistentData.put(PERSISTENT_SEED_STRING, seed.seedString());
        persistentData.put(PERSISTENT_SECTOR_FINGERPRINT, seed.sectorFingerprint());
    }

    public static void storeCurrentSectorPersistentData(SeedData seed) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            storePersistentData(sector.getPersistentData(), seed);
            sector.setSeedString(seed.seedString());
            CoopLog.info(CoopSeedSync.class,
                    "Coop seed stored seedLong=" + seed.seedLong()
                            + " seedString=" + seed.seedString()
                            + " sectorFingerprint=" + seed.sectorFingerprint());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSeedSync.class, "Unable to store coop seed data in sector persistent data", ex);
        }
    }

    public static String currentSectorFingerprint() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return "";
            }
            return CoopSectorFingerprint.fingerprint(sector);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSeedSync.class, "Unable to compute coop sector fingerprint", ex);
            return "";
        }
    }

    public static String currentSectorSeedString() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return "";
            }
            String seedString = sector.getSeedString();
            return seedString == null ? "" : seedString.trim();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSeedSync.class, "Unable to read coop sector seed string", ex);
            return "";
        }
    }

    public static void storeCurrentSectorFingerprint() {
        String fingerprint = currentSectorFingerprint();
        if (fingerprint.isEmpty()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            sector.getPersistentData().put(PERSISTENT_SECTOR_FINGERPRINT, fingerprint);
            CoopLog.info(CoopSeedSync.class,
                    "Coop sector fingerprint=" + fingerprint + " seedString=" + currentSectorSeedString());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSeedSync.class, "Unable to store coop sector fingerprint", ex);
        }
    }

    public static String seedStringMismatch(String hostSeedString, String guestSeedString) {
        String host = hostSeedString == null ? "" : hostSeedString.trim();
        String guest = guestSeedString == null ? "" : guestSeedString.trim();
        if (host.equals(guest)) {
            return "";
        }
        return "seedString: host=" + host + " guest=" + guest;
    }

    public static String fingerprintMismatch(String hostFingerprint, String guestFingerprint) {
        String host = hostFingerprint == null ? "" : hostFingerprint.trim();
        String guest = guestFingerprint == null ? "" : guestFingerprint.trim();
        if (host.equals(guest)) {
            return "";
        }
        return "sectorFingerprint: host=" + host + " guest=" + guest;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    private static long stableSeedLong(String seedString) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(seedString.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (hash[i] & 0xffL);
            }
            // Vanilla SectorProcGen.prepare skips setSeed when seed <= 0; force positive.
            return value & Long.MAX_VALUE;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive stable seed from seed string", ex);
        }
    }
}
