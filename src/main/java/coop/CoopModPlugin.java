package coop;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import coop.fleet.CoopFullFidelitySystemDriver;
import coop.fleet.CoopMirrorOrphanSweeper;
import coop.fleet.CoopSystemDriveFrameHook;
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
        // Before the pump installs: no session can be active yet, so this only ever sees mirrors
        // orphaned by a previous session's save (see CoopMirrorOrphanSweeper).
        CoopMirrorOrphanSweeper.sweep(Global.getSector());
        // Before the pump, so it sits earlier in the transient script list and runs first each frame:
        // it captures the engine's per-frame delta for the full-fidelity guest-system drive and is the
        // always-on observer that releases the drive when the pump stops ticking it.
        CoopSystemDriveFrameHook.install(Global.getSector());
        CoopNetPumpInstaller.install(Global.getSector(), netService);
        CoopSeedSync.storeCurrentSectorFingerprint();
        CoopLog.info(CoopModPlugin.class, "CoopNetPump registered");
    }

    @Override
    public void onNewGameAfterProcGen() {
        CoopSeedSync.storeCurrentSectorFingerprint();
    }

    /**
     * Belt and braces for the full-fidelity guest-system drive. The drive never removes anything from
     * the engine, so a save taken mid-drive is already correct; this just guarantees nothing of ours
     * reorders a list or advances a location while XStream is walking the sector.
     */
    @Override
    public void beforeGameSave() {
        CoopFullFidelitySystemDriver.beginSave();
    }

    @Override
    public void afterGameSave() {
        CoopFullFidelitySystemDriver.endSave();
    }
}
