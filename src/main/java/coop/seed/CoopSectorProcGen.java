package coop.seed;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorGenProgress;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.impl.campaign.procgen.SectorProcGen;
import coop.net.CoopNetStartupConfig;
import coop.util.CoopLog;

public class CoopSectorProcGen extends SectorProcGen {

    @Override
    public void prepare(CharacterCreationData data) {
        logInvocation("prepare", data);
        applyCoopSeedIfPresent(data);
        super.prepare(data);
    }

    @Override
    public void generate(CharacterCreationData data, SectorGenProgress progress) {
        logInvocation("generate", data);
        applyCoopSeedIfPresent(data);
        super.generate(data, progress);
        forceCoopSeedOntoSector();
    }

    private static void forceCoopSeedOntoSector() {
        String seedString = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        if (seedString.isEmpty()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            String previous = sector.getSeedString();
            sector.setSeedString(seedString);
            CoopLog.info(CoopSectorProcGen.class,
                    "Coop forced sector.seedString=" + seedString
                            + " (was " + previous + ")");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSectorProcGen.class,
                    "Failed to force coop seedString onto sector", ex);
        }
    }

    private static void logInvocation(String stage, CharacterCreationData data) {
        String incomingSeedString = data == null ? "<null>" : data.getSeedString();
        long incomingSeed = data == null ? 0L : data.getSeed();
        String configured = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        CoopLog.info(CoopSectorProcGen.class,
                "CoopSectorProcGen." + stage + " invoked"
                        + " incomingSeedString=" + incomingSeedString
                        + " incomingSeedLong=" + incomingSeed
                        + " configuredCoopSeed=" + (configured.isEmpty() ? "<none>" : configured));
    }

    /**
     * Forces {@code -Dcoop.newGameSeed} onto the character-creation data, or does nothing when the
     * property is absent. Public because {@code coop.newgame.CoopNewGameDialogPlugin} pins the same
     * seed from the New Game dialog -- one derivation, two call sites, so the dialog and procgen
     * cannot disagree about what the property means.
     */
    public static void applyCoopSeedIfPresent(CharacterCreationData data) {
        if (data == null) {
            return;
        }
        String seedString = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        if (seedString.isEmpty()) {
            return;
        }
        CoopSeedSync.SeedData seed = CoopSeedSync.seedDataFromSeedString(seedString);
        CoopSeedSync.applyToCharacterCreationData(data, seed);
        CoopLog.info(CoopSectorProcGen.class,
                "Coop overriding new-game seed from JVM property "
                        + CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY
                        + " seedString=" + seed.seedString()
                        + " seedLong=" + seed.seedLong());
    }
}
