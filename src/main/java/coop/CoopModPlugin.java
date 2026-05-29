package coop;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import coop.net.CoopNetPumpInstaller;
import coop.net.CoopNetService;
import coop.net.CoopNetStartupConfig;
import coop.seed.CoopSeedSync;
import coop.util.CoopLog;

public class CoopModPlugin extends BaseModPlugin {
    private CoopNetService netService;

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        CoopLog.info(CoopModPlugin.class, "CoopModPlugin loaded");
        String newGameSeed = CoopNetStartupConfig.newGameSeedFromSystemProperties();
        if (!newGameSeed.isEmpty()) {
            CoopLog.info(CoopModPlugin.class,
                    "Coop new-game seed override configured via "
                            + CoopNetStartupConfig.NEW_GAME_SEED_PROPERTY
                            + "=" + newGameSeed);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (netService != null) {
            netService.shutdown();
        }
        netService = new CoopNetService();
        CoopNetPumpInstaller.install(Global.getSector(), netService);
        CoopSeedSync.storeCurrentSectorFingerprint();
        CoopLog.info(CoopModPlugin.class, "CoopNetPump registered");
    }

    @Override
    public void onNewGameAfterProcGen() {
        CoopSeedSync.storeCurrentSectorFingerprint();
    }
}
