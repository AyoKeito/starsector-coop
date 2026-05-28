package coop;

import com.fs.starfarer.api.BaseModPlugin;
import coop.util.CoopLog;

public class CoopModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        CoopLog.info(CoopModPlugin.class, "CoopModPlugin loaded");
    }
}

