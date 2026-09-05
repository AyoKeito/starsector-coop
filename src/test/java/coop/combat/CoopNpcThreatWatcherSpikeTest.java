package coop.combat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import coop.fleet.CoopAllyPullInSpike;
import coop.net.CoopConnectionRole;
import coop.testing.LogCapture;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import coop.util.CoopDebug;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static coop.testing.ProxyDefaults.defaultValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug-only ally pull-in spike, at the one place it changes a load-bearing behaviour: the
 * host's per-frame battle eject. Armed, the eject must not run — the whole question the spike exists
 * to answer is what the engine does with a mirror that is left in the battle — and the observer must
 * say so exactly once per battle instead of once per frame.
 *
 * <p>Kept apart from {@link CoopNpcThreatWatcherTest}, which is the pure decision core and touches no
 * engine interface at all.
 */
class CoopNpcThreatWatcherSpikeTest {

    private LogCapture log;

    @BeforeEach
    void setUp() {
        CoopAllyPullInSpike.reset();
        log = LogCapture.attach(CoopAllyPullInSpike.class);
    }

    @AfterEach
    void tearDown() {
        log.detach();
        CoopDebug.setAllyPullInForTesting(false, false);
        CoopAllyPullInSpike.reset();
    }

    private static CoopNpcThreatWatcher watcher() {
        return new CoopNpcThreatWatcher(new RecordingNetService(CoopConnectionRole.HOST),
                TestSessions.activeHostSession());
    }

    @Test
    void theEjectStillRunsEveryFrameWithTheSpikeOff() {
        FakeBattle battle = new FakeBattle();
        FakeMirror mirror = new FakeMirror(battle);
        CoopNpcThreatWatcher watcher = watcher();

        watcher.ejectFromBattleIfNeeded(mirror.proxy());

        assertEquals(1, battle.leaves, "the pull-in recovery is not optional in a shipped session");
        assertEquals(1, watcher.ejectCount());
        assertTrue(linesStartingWith(CoopAllyPullInSpike.JOIN_PREFIX).isEmpty(),
                "the spike must be silent when it is off");
    }

    @Test
    void theArmedSpikeObservesTheBattleInsteadOfLeavingIt() {
        CoopDebug.setAllyPullInForTesting(true, false);
        FakeBattle battle = new FakeBattle();
        FakeMirror mirror = new FakeMirror(battle);
        CoopNpcThreatWatcher watcher = watcher();

        for (int frame = 0; frame < 5; frame++) {
            watcher.ejectFromBattleIfNeeded(mirror.proxy());
        }

        assertEquals(0, battle.leaves, "the spike is pointless if the mirror is pulled straight out");
        assertEquals(0, watcher.ejectCount());
        List<String> joins = linesStartingWith(CoopAllyPullInSpike.JOIN_PREFIX);
        assertEquals(1, joins.size(), "once per battle, not once per frame: " + joins);
    }

    @Test
    void theJoinLineCarriesTheSidesAndThePreBattleRoster() {
        CoopDebug.setAllyPullInForTesting(true, false);
        FakeBattle battle = new FakeBattle();
        FakeMirror mirror = new FakeMirror(battle);
        battle.sideOne.add(mirror.proxy());
        battle.sideOne.add(fleetNamed("Player Fleet"));
        battle.sideTwo.add(fleetNamed("Pirate Armada"));

        watcher().ejectFromBattleIfNeeded(mirror.proxy());

        String line = linesStartingWith(CoopAllyPullInSpike.JOIN_PREFIX).get(0);
        assertTrue(line.contains("mirrorSide=ONE"), line);
        assertTrue(line.contains("mirrorSideIsPlayerSide=true"), line);
        assertTrue(line.contains("playerInvolved=true"), line);
        assertTrue(line.contains("sideOneFleets=2"), line);
        assertTrue(line.contains("sideTwoFleets=1"), line);
        assertTrue(line.contains("primaryTwo='Pirate Armada'"), line);
        assertTrue(line.contains("pre-battle roster: 1 members [wolf hull=1.000 cr=0.700]"), line);
    }

    @Test
    void theEndOfTheBattleIsLoggedOnceWithTheRosterToDiffAgainst() {
        CoopDebug.setAllyPullInForTesting(true, false);
        FakeBattle battle = new FakeBattle();
        FakeMirror mirror = new FakeMirror(battle);
        CoopNpcThreatWatcher watcher = watcher();
        watcher.ejectFromBattleIfNeeded(mirror.proxy());

        // The battle resolves and the engine takes the mirror out of it, having chewed on it.
        mirror.battle = null;
        mirror.hullFraction = 0.42f;
        mirror.cr = 0.31f;
        for (int frame = 0; frame < 3; frame++) {
            watcher.ejectFromBattleIfNeeded(mirror.proxy());
        }

        List<String> leaves = linesStartingWith(CoopAllyPullInSpike.LEAVE_PREFIX);
        assertEquals(1, leaves.size(), "the leave edge fires once, not every frame after: " + leaves);
        assertTrue(leaves.get(0).contains("post-battle roster: 1 members [wolf hull=0.420 cr=0.310]"),
                leaves.get(0));
        assertTrue(leaves.get(0).contains("pre-battle roster: 1 members [wolf hull=1.000 cr=0.700]"),
                leaves.get(0));
        assertFalse(CoopAllyPullInSpike.isObservingBattle());
    }

    @Test
    void aMirrorThatCannotAnswerForItsBattleNeverBreaksTheFrame() {
        CoopDebug.setAllyPullInForTesting(true, false);
        CampaignFleetAPI throwing = (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("no battle");
                });

        watcher().ejectFromBattleIfNeeded(throwing);
        CoopAllyPullInSpike.observe(null);
    }

    private List<String> linesStartingWith(String prefix) {
        List<String> hits = new ArrayList<>();
        for (String message : log.messages()) {
            if (message.startsWith(prefix)) {
                hits.add(message);
            }
        }
        return hits;
    }

    // ---- engine fakes ----------------------------------------------------------------------------

    /** Records {@code leave} and answers the handful of side queries the observer asks for. */
    private static final class FakeBattle {
        final List<CampaignFleetAPI> sideOne = new ArrayList<>();
        final List<CampaignFleetAPI> sideTwo = new ArrayList<>();
        int leaves;
        private final BattleAPI proxy;

        FakeBattle() {
            proxy = (BattleAPI) Proxy.newProxyInstance(
                    BattleAPI.class.getClassLoader(),
                    new Class<?>[]{BattleAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "leave" -> {
                            leaves++;
                            yield null;
                        }
                        case "getSideOne" -> sideOne;
                        case "getSideTwo" -> sideTwo;
                        case "getPlayerSide" -> sideOne;
                        case "getSideFor" -> sideTwo.contains(args[0]) ? sideTwo : sideOne;
                        case "isPlayerSide" -> args[0] == sideOne;
                        case "isPlayerInvolved" -> true;
                        case "getPrimary" -> ((List<?>) args[0]).get(0);
                        case "toString" -> "FakeBattle";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        BattleAPI proxy() {
            return proxy;
        }
    }

    /** A one-ship partner mirror whose battle and damage the test moves between frames. */
    private static final class FakeMirror {
        BattleAPI battle;
        float hullFraction = 1f;
        float cr = 0.7f;
        private final CampaignFleetAPI proxy;

        FakeMirror(FakeBattle battle) {
            this.battle = battle.proxy();
            FleetMemberAPI member = member();
            FleetDataAPI data = (FleetDataAPI) Proxy.newProxyInstance(
                    FleetDataAPI.class.getClassLoader(),
                    new Class<?>[]{FleetDataAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMembersListCopy" -> List.of(member);
                        case "toString" -> "FakeFleetData";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
            proxy = (CampaignFleetAPI) Proxy.newProxyInstance(
                    CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignFleetAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBattle" -> this.battle;
                        case "getFleetData" -> data;
                        case "getName" -> "partner Guest";
                        case "toString" -> "FakeMirror";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }

        CampaignFleetAPI proxy() {
            return proxy;
        }

        private FleetMemberAPI member() {
            FleetMemberStatusAPI status = (FleetMemberStatusAPI) Proxy.newProxyInstance(
                    FleetMemberStatusAPI.class.getClassLoader(),
                    new Class<?>[]{FleetMemberStatusAPI.class},
                    (proxy, method, args) -> "getHullFraction".equals(method.getName())
                            ? hullFraction : defaultValue(method.getReturnType()));
            RepairTrackerAPI repair = (RepairTrackerAPI) Proxy.newProxyInstance(
                    RepairTrackerAPI.class.getClassLoader(),
                    new Class<?>[]{RepairTrackerAPI.class},
                    (proxy, method, args) -> "getCR".equals(method.getName())
                            ? cr : defaultValue(method.getReturnType()));
            return (FleetMemberAPI) Proxy.newProxyInstance(
                    FleetMemberAPI.class.getClassLoader(),
                    new Class<?>[]{FleetMemberAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHullId" -> "wolf";
                        case "getStatus" -> status;
                        case "getRepairTracker" -> repair;
                        case "toString" -> "FakeMember";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static CampaignFleetAPI fleetNamed(String name) {
        return (CampaignFleetAPI) Proxy.newProxyInstance(
                CampaignFleetAPI.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }
}
