package coop.net;

import com.fs.starfarer.api.campaign.SectorAPI;

import java.util.Objects;

public final class CoopNetPumpInstaller {
    private CoopNetPumpInstaller() {
    }

    public static void install(SectorAPI sector, CoopNetService service) {
        Objects.requireNonNull(sector, "sector");
        Objects.requireNonNull(service, "service");

        sector.removeScriptsOfClass(CoopNetPump.class);
        sector.removeTransientScriptsOfClass(CoopNetPump.class);
        sector.addTransientScript(new CoopNetPump(service));
    }
}
