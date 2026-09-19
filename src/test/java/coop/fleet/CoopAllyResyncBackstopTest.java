package coop.fleet;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The plan's roster resync backstop (Phase 33). The mirror's roster gate only rebuilds when the
 * owner's structural hash changes, and a battle moves neither the hash nor, necessarily, the ship
 * count — a fight with no losses still leaves a mirror the engine rearranged. Taking the ally result
 * therefore drops the latch, so whatever the owner sends next is applied in full.
 */
class CoopAllyResyncBackstopTest {

    @Test
    void takingTheOutcomeDropsTheRosterLatch() {
        CoopFleetMirror mirror = new CoopFleetMirror();
        mirror.seatRosterLatchForTesting("structural-hash");
        AtomicReference<BattleAPI> battle = new AtomicReference<>(battle());
        CampaignFleetAPI fleet = fleetIn(battle);

        mirror.battleTrackerForTesting().poll(fleet, "player-2", Map.of("m1", "owner-m1"));
        battle.set(null);
        mirror.battleTrackerForTesting().poll(fleet, "player-2", Map.of("m1", "owner-m1"));

        assertNotNull(mirror.takeAllyBattleOutcome());
        assertNull(mirror.rosterLatchForTesting(),
                "the owner has applied the losses by now; the next roster is the truth");
    }

    @Test
    void anUntakenFrameLeavesTheLatchAlone() {
        // takeAllyBattleOutcome is polled every frame by the pump and answers null on almost all of
        // them. If that dropped the latch, every mirror in the game would rebuild its roster at
        // frame rate.
        CoopFleetMirror mirror = new CoopFleetMirror();
        mirror.seatRosterLatchForTesting("structural-hash");

        assertNull(mirror.takeAllyBattleOutcome());
        assertNotNull(mirror.rosterLatchForTesting());
    }

    @Test
    void anNpcMirrorNeverReportsAnAllyBattle() {
        // The two roles share CoopFleetMirror. An NPC mirror is never a player mirror, so it has no
        // owner to bill and its battles are the host's business, not an ally result.
        CoopFleetMirror mirror = new CoopFleetMirror();

        assertFalse(mirror.allyAllowed());
        assertNull(mirror.takeAllyBattleJoin());
        assertNull(mirror.takeAllyBattleOutcome());
    }

    private static BattleAPI battle() {
        return (BattleAPI) Proxy.newProxyInstance(
                BattleAPI.class.getClassLoader(),
                new Class<?>[] {BattleAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOtherSideFor" -> List.of();
                    case "toString" -> "Battle";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static CampaignFleetAPI fleetIn(AtomicReference<BattleAPI> battle) {
        FleetMemberStatusAPI status = (FleetMemberStatusAPI) Proxy.newProxyInstance(
                FleetMemberStatusAPI.class.getClassLoader(),
                new Class<?>[] {FleetMemberStatusAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullFraction" -> 0.5f;
                    case "toString" -> "Status";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        RepairTrackerAPI repair = (RepairTrackerAPI) Proxy.newProxyInstance(
                RepairTrackerAPI.class.getClassLoader(),
                new Class<?>[] {RepairTrackerAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCR" -> 0.6f;
                    case "toString" -> "Repair";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        FleetMemberAPI member = (FleetMemberAPI) Proxy.newProxyInstance(
                FleetMemberAPI.class.getClassLoader(),
                new Class<?>[] {FleetMemberAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "m1";
                    case "getStatus" -> status;
                    case "getRepairTracker" -> repair;
                    case "toString" -> "Member";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        FleetDataAPI data = (FleetDataAPI) Proxy.newProxyInstance(
                FleetDataAPI.class.getClassLoader(),
                new Class<?>[] {FleetDataAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMembersListCopy" -> new java.util.ArrayList<>(List.of(member));
                    case "toString" -> "FleetData";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBattle" -> battle.get();
                    case "getFleetData" -> data;
                    case "toString" -> "Mirror";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
