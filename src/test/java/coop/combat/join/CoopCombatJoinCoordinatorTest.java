package coop.combat.join;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: battle-bridge join orchestration — classify, prompt, hooks, accept/decline.
 */
class CoopCombatJoinCoordinatorTest {

    @AfterEach
    void detachActive() {
        CoopCombatJoinCoordinator leftover = CoopCombatJoinCoordinator.active();
        if (leftover != null) {
            leftover.detachIfActive();
        }
    }

    @Test
    void proximityBattleQueuesNoticeAndFiresProximityHook() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        AtomicReference<String> hookedBattle = new AtomicReference<>();
        join.setSeparationProbe(s -> CoopPartnerSeparation.sameLocation(100f));
        join.setSharedBattleHooks(new CoopSharedBattleHooks() {
            @Override
            public void onProximityJoinAvailable(String battleId, String partnerName) {
                hookedBattle.set(battleId + ":" + partnerName);
            }
        });

        List<String> banners = join.onPartnerBattleBegin(null, "battle-p", "Pat", "pirates");

        assertEquals(CoopCombatJoinMode.PROXIMITY, join.lastMode());
        assertEquals("battle-p", join.lastBattleId());
        assertEquals(CoopRemoteSupportPrompt.State.IDLE, join.prompt().state());
        assertTrue(banners.get(0).contains("proximity join"));
        assertEquals("battle-p:Pat", hookedBattle.get());
    }

    @Test
    void remoteBattleWithMarkedShipOpensPendingCall() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        join.setSeparationProbe(s -> CoopPartnerSeparation.sameLocation(2_000f));
        join.setMarkedShipProbe(s -> true);

        List<String> banners = join.onPartnerBattleBegin(null, "battle-r", "Ray", "remnants");

        assertEquals(CoopCombatJoinMode.REMOTE_SUPPORT, join.lastMode());
        assertEquals(CoopRemoteSupportPrompt.State.PENDING, join.prompt().state());
        assertTrue(banners.get(0).contains("hyperspace support"));
    }

    @Test
    void remoteWithoutMarkedShipReportsUnavailable() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        AtomicReference<CoopRemoteSupportPrompt.State> reason = new AtomicReference<>();
        join.setSeparationProbe(s -> CoopPartnerSeparation.differentLocation());
        join.setMarkedShipProbe(s -> false);
        join.setSharedBattleHooks(new CoopSharedBattleHooks() {
            @Override
            public void onRemoteSupportUnavailable(String battleId, CoopRemoteSupportPrompt.State r) {
                reason.set(r);
            }
        });

        List<String> banners = join.onPartnerBattleBegin(null, "battle-n", "Ned", "pirates");

        assertTrue(join.lastMode().isRemote());
        assertEquals(CoopRemoteSupportPrompt.State.NO_MARKED_SHIP, join.prompt().state());
        assertEquals(CoopRemoteSupportPrompt.State.NO_MARKED_SHIP, reason.get());
        assertTrue(banners.get(0).contains("no ship is marked"));
    }

    @Test
    void unknownSeparationNeverChoosesProximity() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        join.setSeparationProbe(s -> CoopPartnerSeparation.unknown());
        join.setMarkedShipProbe(s -> true);

        join.onPartnerBattleBegin(null, "battle-u", "Una", "pirates");

        assertEquals(CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE, join.lastMode());
        assertTrue(join.lastMode().isRemote());
        assertEquals(CoopRemoteSupportPrompt.State.PENDING, join.prompt().state());
    }

    @Test
    void acceptAndDeclineDriveHooksThenBattleEndClears() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        List<String> events = new ArrayList<>();
        join.setSeparationProbe(s -> CoopPartnerSeparation.sameLocation(999f));
        join.setMarkedShipProbe(s -> true);
        join.setSharedBattleHooks(new CoopSharedBattleHooks() {
            @Override
            public void onRemoteSupportAccepted(String battleId, String partnerName) {
                events.add("accept:" + battleId);
            }

            @Override
            public void onRemoteSupportUnavailable(String battleId, CoopRemoteSupportPrompt.State reason) {
                events.add("unavail:" + reason);
            }

            @Override
            public void onRemoteBattleEnded(String battleId) {
                events.add("end:" + battleId);
            }
        });

        join.onPartnerBattleBegin(null, "battle-a", "Ada", "pirates");
        assertTrue(join.acceptRemoteSupport().get(0).contains("accepted"));
        assertEquals(CoopRemoteSupportPrompt.State.ACCEPTED, join.prompt().state());

        join.onPartnerBattleBegin(null, "battle-d", "Dee", "pirates");
        assertTrue(join.declineRemoteSupport().get(0).contains("declined"));

        join.onPartnerBattleEnd("battle-d");
        assertEquals(CoopRemoteSupportPrompt.State.IDLE, join.prompt().state());
        assertEquals("", join.lastBattleId());
        assertEquals(List.of("accept:battle-a", "unavail:DECLINED", "end:battle-d"), events);
    }

    @Test
    void attachAsActiveExposesProcessWideCoordinator() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        join.attachAsActive();
        assertSame(join, CoopCombatJoinCoordinator.active());
        join.detachIfActive();
        assertNull(CoopCombatJoinCoordinator.active());
    }

    @Test
    void acceptWhenIdleReturnsNoBanners() {
        CoopCombatJoinCoordinator join = new CoopCombatJoinCoordinator();
        AtomicInteger accepts = new AtomicInteger();
        join.setSharedBattleHooks(new CoopSharedBattleHooks() {
            @Override
            public void onRemoteSupportAccepted(String battleId, String partnerName) {
                accepts.incrementAndGet();
            }
        });

        assertTrue(join.acceptRemoteSupport().isEmpty());
        assertTrue(join.declineRemoteSupport().isEmpty());
        assertEquals(0, accepts.get());
    }
}
