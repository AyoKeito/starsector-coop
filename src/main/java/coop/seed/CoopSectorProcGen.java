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
        applyCoopSeedIfPresent(data, true);
    }

    /**
     * As {@link #applyCoopSeedIfPresent(CharacterCreationData)}, with the log line suppressed.
     *
     * <p>{@code announce=false} is for the one caller that runs every frame: the New Game dialog
     * re-pins the panel on each {@code advance()} because the panel can write its own values back at
     * any time, and the line below is identical every time. Left unconditional it wrote roughly one
     * INFO per frame for as long as a player sat in character creation, burying the seed-lock and
     * pin lines a smoke run greps for.
     */
    public static void applyCoopSeedIfPresent(CharacterCreationData data, boolean announce) {
        if (data == null) {
            return;
        }
        String seedString = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        if (seedString.isEmpty()) {
            return;
        }
        CoopSeedSync.SeedData seed = CoopSeedSync.seedDataFromSeedString(seedString);
        CoopSeedSync.applyToCharacterCreationData(data, seed);
        if (!announce) {
            return;
        }
        CoopLog.info(CoopSectorProcGen.class,
                "Coop overriding new-game seed from JVM property "
                        + CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY
                        + " seedString=" + seed.seedString()
                        + " seedLong=" + seed.seedLong());
    }
}
