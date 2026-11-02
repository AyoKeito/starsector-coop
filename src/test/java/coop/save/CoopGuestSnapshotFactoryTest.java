package coop.save;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopGuestSnapshotFactoryTest {
    @Test
    void aGuestRecoverySnapshotKeepsTheNamelessRockUniqueLocationKey() {
        StarSystemAPI location = (StarSystemAPI) Proxy.newProxyInstance(
                StarSystemAPI.class.getClassLoader(), new Class<?>[]{StarSystemAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "deep space";
                    case "getOptionalUniqueId" -> "nameless_rock_location";
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        CampaignFleetAPI fleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(), new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContainingLocation" -> location;
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(), new Class<?>[]{SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayerFleet" -> fleet;
                    default -> ProxyDefaults.defaultValue(method.getReturnType());
                });

        CoopGuestSnapshot snapshot = CoopGuestSnapshotFactory.capture(sector, "session", "guest",
                "Guest", "campaign", "seed", 123L);

        assertEquals("nameless_rock_location", snapshot.getLocationId());
    }
}
