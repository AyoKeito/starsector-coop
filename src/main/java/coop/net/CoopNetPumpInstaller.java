package coop.net;

import com.fs.starfarer.api.campaign.SectorAPI;

import java.util.Objects;

public final class CoopNetPumpInstaller {
    private CoopNetPumpInstaller() {
    }

    /** @return the pump just installed, for callers that need a handle on it (the Phase 20.6 HUD). */
    public static CoopNetPump install(SectorAPI sector, CoopNetService service) {
        Objects.requireNonNull(sector, "sector");
        Objects.requireNonNull(service, "service");

        sector.removeScriptsOfClass(CoopNetPump.class);
        sector.removeTransientScriptsOfClass(CoopNetPump.class);
        CoopNetPump pump = new CoopNetPump(service);
        sector.addTransientScript(pump);
        return pump;
    }
}
