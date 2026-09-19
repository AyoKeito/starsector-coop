package coop;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import coop.campaign.CoopAllyToggleAbility;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33: {@code onGameLoad} puts the ally toggle on the local player's fleet so both players have
 * it from the first co-op load, including in saves that predate the ability. The add has to be
 * idempotent, because {@code addAbility} on a fleet that already has one replaces the plugin and
 * with it the player's answer.
 */
class CoopAllyAbilityInstallTest {

    @Test
    void theToggleIsAddedToAFleetThatDoesNotHaveIt() {
        List<String> added = new ArrayList<>();

        assertTrue(CoopModPlugin.ensureAllyAbility(sectorWithFleet(fleet(added, false))));
        assertEquals(List.of(CoopAllyToggleAbility.ABILITY_ID), added);
    }

    @Test
    void aFleetThatAlreadyHasItIsLeftAlone() {
        // The player's on/off answer lives in the plugin instance the fleet is holding; re-adding
        // would silently reset it on every load.
        List<String> added = new ArrayList<>();

        assertFalse(CoopModPlugin.ensureAllyAbility(sectorWithFleet(fleet(added, true))));
        assertTrue(added.isEmpty());
    }

    @Test
    void aSectorWithNoFleetOrNoSectorAtAllIsNotAnError() {
        assertFalse(CoopModPlugin.ensureAllyAbility(null));
        assertFalse(CoopModPlugin.ensureAllyAbility(sectorWithFleet(null)));
    }

    @Test
    void aFleetThatThrowsCostsALogLineAndNothingElse() {
        CampaignFleetAPI throwing = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("no abilities");
                });

        assertFalse(CoopModPlugin.ensureAllyAbility(sectorWithFleet(throwing)));
    }

    private static CampaignFleetAPI fleet(List<String> added, boolean alreadyHasIt) {
        AbilityPlugin existing = alreadyHasIt ? (AbilityPlugin) Proxy.newProxyInstance(
                AbilityPlugin.class.getClassLoader(),
                new Class<?>[] {AbilityPlugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> CoopAllyToggleAbility.ABILITY_ID;
                    case "isActive" -> Boolean.FALSE;
                    case "toString" -> "Ability";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                }) : null;
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAbility" -> CoopAllyToggleAbility.ABILITY_ID.equals(args[0])
                            ? existing : null;
                    case "addAbility" -> {
                        added.add((String) args[0]);
                        yield null;
                    }
                    case "toString" -> "PlayerFleet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static SectorAPI sectorWithFleet(CampaignFleetAPI fleet) {
        return (SectorAPI) Proxy.newProxyInstance(
                SectorAPI.class.getClassLoader(),
                new Class<?>[] {SectorAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayerFleet" -> fleet;
                    case "toString" -> "Sector";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
