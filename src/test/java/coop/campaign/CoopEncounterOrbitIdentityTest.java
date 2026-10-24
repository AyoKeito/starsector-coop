package coop.campaign;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.fleet.CoopEntityIds;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoopEncounterOrbitIdentityTest {
    @Test
    void orbitBodyAndPseudoCenterFocusUseSharedKeysWithNativeIdsUntouched() {
        SectorEntityToken center = token("host-center-91", "coopenc_instance-a_system0", null);
        SectorEntityToken planet = token("host-planet-92", "coopenc_instance-a_system1", center);

        List<CoopOrbitSync.OrbitEntry> entries = CoopCampaignReplicator.orbitEntries(List.of(planet));

        assertEquals(List.of(new CoopOrbitSync.OrbitEntry("coopenc_instance-a_system1",
                "coopenc_instance-a_system0", 1500, 50, 42)), entries);
        assertEquals("host-center-91", center.getId());
        assertEquals("host-planet-92", planet.getId());
    }

    private static SectorEntityToken token(String id, String key, SectorEntityToken focus) {
        MemoryAPI memory = (MemoryAPI) Proxy.newProxyInstance(MemoryAPI.class.getClassLoader(),
                new Class<?>[]{MemoryAPI.class}, (p,m,a) -> m.getName().equals("get")
                        ? (CoopEntityIds.ENCOUNTER_ENTITY_ID_KEY.equals(a[0]) ? key : null)
                        : ProxyDefaults.defaultValue(m.getReturnType()));
        return (SectorEntityToken) Proxy.newProxyInstance(SectorEntityToken.class.getClassLoader(),
                new Class<?>[]{SectorEntityToken.class}, (p,m,a) -> switch (m.getName()) {
                    case "getId" -> id;
                    case "getMemoryWithoutUpdate" -> memory;
                    case "getOrbitFocus" -> focus;
                    case "getCircularOrbitRadius" -> 1500f;
                    case "getCircularOrbitPeriod" -> 50f;
                    case "getCircularOrbitAngle" -> 42f;
                    default -> ProxyDefaults.defaultValue(m.getReturnType());
                });
    }
}
