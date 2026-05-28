package coop;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import coop.net.CoopNetPumpInstaller;
import coop.net.CoopNetService;
import coop.util.CoopLog;

public class CoopModPlugin extends BaseModPlugin {
    private CoopNetService netService;

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        CoopLog.info(CoopModPlugin.class, "CoopModPlugin loaded");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (netService != null) {
            netService.shutdown();
        }
        netService = new CoopNetService();
        CoopNetPumpInstaller.install(Global.getSector(), netService);
        CoopLog.info(CoopModPlugin.class, "CoopNetPump registered");
    }
}
