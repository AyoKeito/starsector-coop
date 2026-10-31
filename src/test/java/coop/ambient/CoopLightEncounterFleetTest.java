package coop.ambient;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import coop.testing.ProxyDefaults;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopLightEncounterFleetTest {
    private static final String LIGHT_ID = "light:01234567-89ab-4def-8123-0123456789ab";

    @Test
    void leavingAManagedDwellerEncounterRemovesItsSurvivingTemporaryFleet() {
        List<Object> removed = new ArrayList<>();
        CampaignFleetAPI fleet = fleet(Factions.DWELLER, LIGHT_ID, removed);

        assertTrue(CoopLightBridge.isEncounterFleet(fleet));
        CoopLightBridge.discardEncounterFleet(fleet);

        assertEquals(List.of(fleet), removed);
        assertFalse(fleet.getMemoryWithoutUpdate().contains("$coopNpcFleetId"));
    }

    @Test
    void leavingAnUnmanagedDwellerOrAnotherFactionDoesNotRemoveIt() {
        List<Object> removed = new ArrayList<>();
        for (CampaignFleetAPI fleet : List.of(
                fleet(Factions.DWELLER, null, removed),
                fleet(Factions.PIRATES, LIGHT_ID, removed),
                fleet(Factions.DWELLER, "light:invalid", removed),
                fleet(Factions.DWELLER, true, removed))) {
            assertFalse(CoopLightBridge.isEncounterFleet(fleet));
            CoopLightBridge.discardEncounterFleet(fleet);
        }

        assertTrue(removed.isEmpty());
    }

    @Test
    void anUnplacedOrAlreadyRemovedManifestationCanBeReleasedSafely() {
        CoopLightBridge.discardEncounterFleet(fleet(Factions.DWELLER, LIGHT_ID, null));
        CoopLightBridge.discardEncounterFleet(null);
    }

    private static CampaignFleetAPI fleet(String factionId, Object marker, List<Object> removed) {
        MemoryAPI memory = stub(MemoryAPI.class, (name, args) -> switch (name) {
            case "get" -> CoopLightBridge.ENCOUNTER_FLEET_KEY.equals(args[0]) ? marker : null;
            case "contains" -> CoopLightBridge.ENCOUNTER_FLEET_KEY.equals(args[0]) && marker != null;
            default -> null;
        });
        FactionAPI faction = stub(FactionAPI.class, (name, args) -> "getId".equals(name) ? factionId : null);
        LocationAPI location = removed == null ? null : stub(LocationAPI.class, (name, args) -> {
            if ("removeEntity".equals(name)) removed.add(args[0]);
            return null;
        });
        return stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
            case "getMemoryWithoutUpdate" -> memory;
            case "getFaction" -> faction;
            case "getContainingLocation" -> location;
            default -> null;
        });
    }

    private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + "Proxy";
                    default -> {
                        Object answer = answers.apply(method.getName(), args);
                        yield answer == null ? ProxyDefaults.defaultValue(method.getReturnType()) : answer;
                    }
                }));
    }
}
