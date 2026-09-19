package coop.combat;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import coop.fleet.CoopMirrorTags;
import coop.testing.ApiProxies;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33 on the combat side: the two paths that used to assert "this mirror ignores everyone"
 * unconditionally now read the owner's consent off the mirror's own memory first.
 */
class CoopAllyPostureCombatTest {

    @Test
    void aMirrorWhoseOwnerSaidYesIsLeftInItsBattle() {
        // The eject is load-bearing for every other case and runs every frame; if it also ran for an
        // invited ally it would undo the feature before the first shot.
        assertTrue(CoopNpcThreatWatcher.allyAllowed(
                fleetWith(new HashMap<>(Map.of(CoopMirrorTags.ALLY_ALLOWED_FLAG, Boolean.TRUE)))));
    }

    @Test
    void anNpcMirrorIsNeverTreatedAsAnInvitedAlly() {
        // An NPC mirror carries no ally flag at all, so the absent-is-false reading keeps the eject
        // exactly as it was before 0.1.4.
        assertFalse(CoopNpcThreatWatcher.allyAllowed(
                fleetWith(new HashMap<>(Map.of(CoopMirrorTags.NPC_MIRROR_TAG, "fleet-7")))));
    }

    @Test
    void aPartnerMirrorWithTheToggleOffIsStillEjected() {
        assertFalse(CoopNpcThreatWatcher.allyAllowed(
                fleetWith(new HashMap<>(Map.of(CoopMirrorTags.ALLY_ALLOWED_FLAG, Boolean.FALSE)))));
    }

    @Test
    void aFleetThatCannotAnswerForItsMemoryHasConsentedToNothing() {
        CampaignFleetAPI throwing = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("no memory");
                });

        assertFalse(CoopNpcThreatWatcher.allyAllowed(throwing));
        assertFalse(CoopNpcThreatWatcher.allyAllowed(null));
    }

    @Test
    void theCustomsRestorePutsTheShieldBackWhenTheOwnerSaidNo() {
        Map<String, Object> values = new HashMap<>();
        CampaignFleetAPI mirror = fleetWith(values);

        CoopCustomsDialogStaging.restoreEngagementShield(mirror);

        assertEquals(Boolean.TRUE, values.get(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
    }

    @Test
    void theCustomsRestoreLeavesAnInvitedAllyJoinable() {
        // A hard `true` here would switch the feature off for the rest of the session: the flag
        // lives in fleet memory and only the owner's next snapshot would correct it.
        Map<String, Object> values = new HashMap<>();
        values.put(CoopMirrorTags.ALLY_ALLOWED_FLAG, Boolean.TRUE);
        values.put(MemFlags.FLEET_IGNORES_OTHER_FLEETS, Boolean.TRUE);
        CampaignFleetAPI mirror = fleetWith(values);

        CoopCustomsDialogStaging.restoreEngagementShield(mirror);

        assertFalse(values.containsKey(MemFlags.FLEET_IGNORES_OTHER_FLEETS));
        assertEquals(Boolean.TRUE, values.get(CoopMirrorTags.ALLY_ALLOWED_FLAG));
    }

    private static CampaignFleetAPI fleetWith(Map<String, Object> values) {
        MemoryAPI memory = ApiProxies.memory(values);
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMemoryWithoutUpdate", "getMemory" -> memory;
                    case "toString" -> "Mirror";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
