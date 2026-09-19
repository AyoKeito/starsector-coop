package coop.fleet;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import coop.combat.CoopAllyBattleJoin;
import coop.combat.CoopAllyBattleOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 33's core, and the part the 2026-09-20 spike proved cannot be done any other way: while the
 * partner's mirror is in a battle the owner's snapshot must not write hull, CR or the ship set, and
 * the engine's numbers have to be read on the one frame between the battle ending and the next
 * snapshot arriving.
 */
class CoopAllyBattleTrackerTest {

    private static final String OWNER = "player-2";

    @Test
    void theJoinIsRaisedOnceForOneBattle() {
        FakeMirror mirror = new FakeMirror("a", "b", "c");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();
        mirror.battle = battleNamed("Hegemony Patrol");

        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        CoopAllyBattleJoin join = tracker.takeJoin();
        assertNotNull(join);
        assertEquals(OWNER, join.ownerPlayerId());
        assertEquals("Hegemony Patrol", join.enemySummary());
        assertNull(tracker.takeJoin(), "one join per battle, however many frames it lasts");
    }

    @Test
    void theMirrorIsFrozenFromTheFrameTheBattleIsSeen() {
        FakeMirror mirror = new FakeMirror("a", "b");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();

        assertFalse(tracker.frozen());
        mirror.battle = battleNamed("Pirate Raiders");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        assertTrue(tracker.frozen(), "the owner's hull and CR writes erase the fight within a frame");
    }

    @Test
    void theOutcomeNamesTheLostShipAndTheEnginesNumbersForTheRest() {
        FakeMirror mirror = new FakeMirror("a", "b", "c");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();
        mirror.battle = battleNamed("Pirate Raiders");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        // The fight: one ship gone, the survivors beaten up. These are the values the engine left,
        // which is exactly what the owner's next snapshot used to overwrite before the freeze.
        mirror.destroy("b");
        mirror.member("a").hullFraction = 0.42f;
        mirror.member("a").cr = 0.31f;
        mirror.member("c").hullFraction = 0.9f;
        mirror.member("c").cr = 0.55f;
        mirror.battle = null;
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        CoopAllyBattleOutcome outcome = tracker.takeOutcome();
        assertNotNull(outcome);
        assertEquals(OWNER, outcome.ownerPlayerId());
        assertEquals(List.of("owner-b"), outcome.destroyedMemberIds());
        assertEquals(List.of(new CoopAllyBattleOutcome.Survivor("owner-a", 0.42f, 0.31f),
                        new CoopAllyBattleOutcome.Survivor("owner-c", 0.9f, 0.55f)),
                outcome.survivors());
    }

    @Test
    void theFreezeHoldsUntilTheOutcomeIsTakenAndThenLifts() {
        FakeMirror mirror = new FakeMirror("a");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();
        mirror.battle = battleNamed("Luddic Path Cell");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        mirror.battle = null;
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        assertTrue(tracker.frozen(), "the snapshot that would erase the result lands the same frame");
        assertNotNull(tracker.takeOutcome());
        assertFalse(tracker.frozen());
        assertNull(tracker.takeOutcome(), "the outcome is a one-shot; a second send would re-apply it");
    }

    @Test
    void aSecondBattleIsReportedInItsOwnRight() {
        FakeMirror mirror = new FakeMirror("a");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();
        mirror.battle = battleNamed("First");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        mirror.battle = null;
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        tracker.takeJoin();
        tracker.takeOutcome();

        mirror.battle = battleNamed("Second");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        CoopAllyBattleJoin join = tracker.takeJoin();
        assertNotNull(join);
        assertEquals("Second", join.enemySummary());
    }

    @Test
    void aResetWhileFrozenDropsThePendingResult() {
        // The mirror was disposed or the session ended: the engine that would have received this has
        // no mirror any more, so there is nothing to apply the losses to.
        FakeMirror mirror = new FakeMirror("a");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();
        mirror.battle = battleNamed("Interrupted");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        mirror.battle = null;
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        tracker.reset();

        assertFalse(tracker.frozen());
        assertNull(tracker.takeOutcome());
        assertNull(tracker.takeJoin());
    }

    @Test
    void aShipTheMirrorWasNeverBuiltWithIsInNeitherList() {
        // The owner addresses its fleet by its own member ids. An engine-side ship this mapping does
        // not know is one the owner cannot act on, so naming it would be worse than silence.
        FakeMirror mirror = new FakeMirror("a");
        mirror.members.add(new FakeMember("stowaway", 1f, 1f));
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();
        mirror.battle = battleNamed("Anything");
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        mirror.battle = null;
        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);

        CoopAllyBattleOutcome outcome = tracker.takeOutcome();
        assertNotNull(outcome);
        assertEquals(List.of(), outcome.destroyedMemberIds());
        assertEquals(1, outcome.survivors().size());
        assertEquals("owner-a", outcome.survivors().get(0).memberId());
    }

    @Test
    void aMirrorWithNoBattleProducesNothing() {
        FakeMirror mirror = new FakeMirror("a");
        CoopAllyBattleTracker tracker = new CoopAllyBattleTracker();

        tracker.poll(mirror.fleet(), OWNER, mirror.senderIds);
        tracker.poll(null, OWNER, mirror.senderIds);

        assertNull(tracker.takeJoin());
        assertNull(tracker.takeOutcome());
        assertFalse(tracker.frozen());
    }

    // ---- fakes -----------------------------------------------------------------------------------

    /** One engine ship, with the two numbers a battle changes. */
    private static final class FakeMember {
        final String engineId;
        float hullFraction;
        float cr;

        FakeMember(String engineId, float hullFraction, float cr) {
            this.engineId = engineId;
            this.hullFraction = hullFraction;
            this.cr = cr;
        }

        FleetMemberAPI proxy() {
            FleetMemberStatusAPI status = (FleetMemberStatusAPI) Proxy.newProxyInstance(
                    FleetMemberStatusAPI.class.getClassLoader(),
                    new Class<?>[] {FleetMemberStatusAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHullFraction" -> hullFraction;
                        case "toString" -> "Status";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            RepairTrackerAPI tracker = (RepairTrackerAPI) Proxy.newProxyInstance(
                    RepairTrackerAPI.class.getClassLoader(),
                    new Class<?>[] {RepairTrackerAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCR" -> cr;
                        case "toString" -> "Repair";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return (FleetMemberAPI) Proxy.newProxyInstance(
                    FleetMemberAPI.class.getClassLoader(),
                    new Class<?>[] {FleetMemberAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> engineId;
                        case "getStatus" -> status;
                        case "getRepairTracker" -> tracker;
                        case "toString" -> "Member " + engineId;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /** A mirror fleet whose roster and battle a test can move. */
    private static final class FakeMirror {
        final List<FakeMember> members = new ArrayList<>();
        final Map<String, String> senderIds = new LinkedHashMap<>();
        BattleAPI battle;

        FakeMirror(String... engineIds) {
            for (String engineId : engineIds) {
                members.add(new FakeMember(engineId, 1f, 1f));
                senderIds.put(engineId, "owner-" + engineId);
            }
        }

        FakeMember member(String engineId) {
            for (FakeMember member : members) {
                if (member.engineId.equals(engineId)) {
                    return member;
                }
            }
            throw new IllegalArgumentException(engineId);
        }

        void destroy(String engineId) {
            members.removeIf(member -> member.engineId.equals(engineId));
        }

        CampaignFleetAPI fleet() {
            FleetDataAPI data = (FleetDataAPI) Proxy.newProxyInstance(
                    FleetDataAPI.class.getClassLoader(),
                    new Class<?>[] {FleetDataAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMembersListCopy" -> {
                            List<FleetMemberAPI> copy = new ArrayList<>();
                            for (FakeMember member : members) {
                                copy.add(member.proxy());
                            }
                            yield copy;
                        }
                        case "toString" -> "FleetData";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            return (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[] {CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBattle" -> battle;
                        case "getFleetData" -> data;
                        case "getName" -> "partner Ayo";
                        case "toString" -> "Mirror";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    /** A battle whose other side has one primary fleet with the given name. */
    private static BattleAPI battleNamed(String enemyName) {
        CampaignFleetAPI enemy = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[] {CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> enemyName;
                    case "toString" -> "Enemy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        List<CampaignFleetAPI> otherSide = List.of(enemy);
        return (BattleAPI) Proxy.newProxyInstance(
                BattleAPI.class.getClassLoader(),
                new Class<?>[] {BattleAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOtherSideFor" -> otherSide;
                    case "getPrimary" -> enemy;
                    case "toString" -> "Battle " + enemyName;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
