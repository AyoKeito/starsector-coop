package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12c bar pool: the host-side change watcher's diff, the guest-side rebuild's ordering and
 * managed-subset rules, and the suppressor's match rule. The engine-touching halves (MethodHandles
 * seed access, {@code PortsideBarData} mutation, script removal) are covered by the two-instance
 * smoke test; everything decided in pure code is decided here.
 */
class CoopBarPoolTest {

    private static CoopMissionBoardSync.Entry offer(String id, long seed) {
        return CoopMissionBoardSync.Entry.barOffer(id, id, seed, "", 30L);
    }

    // ---- Host capture diff ---------------------------------------------------------------------

    @Test
    void unchangedPoolIsNotRebroadcast() {
        CoopBarPoolCapture capture = new CoopBarPoolCapture();
        List<CoopMissionBoardSync.Entry> pool = List.of(offer("a", 1L), offer("b", 2L));

        assertTrue(capture.markChanged(pool), "the first pool ever seen is always a change");
        assertFalse(capture.markChanged(pool));
        assertFalse(capture.markChanged(new ArrayList<>(pool)), "a fresh equal list is not a change");
    }

    @Test
    void reorderingThePoolIsAChange() {
        // Load-bearing: BarCMD shuffles the pool with a Random seeded off the synced manager seed,
        // and Collections.shuffle's permutation depends only on size + random. Same members in a
        // different order therefore show a different subset, so the guest must be told.
        CoopBarPoolCapture capture = new CoopBarPoolCapture();
        capture.markChanged(List.of(offer("a", 1L), offer("b", 2L)));

        assertTrue(capture.markChanged(List.of(offer("b", 2L), offer("a", 1L))));
    }

    @Test
    void aChangedContentSeedIsAChange() {
        CoopBarPoolCapture capture = new CoopBarPoolCapture();
        capture.markChanged(List.of(offer("a", 1L)));

        assertTrue(capture.markChanged(List.of(offer("a", 999L))));
    }

    @Test
    void remainingDaysAloneIsNotAChange() {
        // expiresAtDay ticks down continuously; if it counted, every single poll would rebroadcast
        // the whole pool.
        CoopBarPoolCapture capture = new CoopBarPoolCapture();
        capture.markChanged(List.of(CoopMissionBoardSync.Entry.barOffer("a", "a", 1L, "", 30L)));

        assertFalse(capture.markChanged(List.of(CoopMissionBoardSync.Entry.barOffer("a", "a", 1L, "", 12L))));
    }

    @Test
    void resetRearmsTheRebroadcastForARejoiningGuest() {
        CoopBarPoolCapture capture = new CoopBarPoolCapture();
        List<CoopMissionBoardSync.Entry> pool = List.of(offer("a", 1L));
        capture.markChanged(pool);

        capture.reset();

        assertTrue(capture.markChanged(pool));
    }

    @Test
    void intelBackedEventsAreRecognisedByClassName() {
        assertTrue(CoopBarPoolCapture.isIntelBacked("PirateBaseRumorBarEvent"));
        assertTrue(CoopBarPoolCapture.isIntelBacked("LuddicPathBaseBarEvent"));
        assertFalse(CoopBarPoolCapture.isIntelBacked("DeliveryBarEvent"));
        assertFalse(CoopBarPoolCapture.isIntelBacked(null));
    }

    // ---- Guest rebuild -------------------------------------------------------------------------

    /** Hand-rolled {@link CoopBarPoolInjector.PoolView}: an ordered list of class names. */
    private static final class FakePool implements CoopBarPoolInjector.PoolView {
        private final List<String> kinds;
        private final List<String> live;
        private final List<String> appended = new ArrayList<>();
        private boolean failNext;

        private FakePool(String... existing) {
            this.kinds = new ArrayList<>(List.of(existing));
            this.live = new ArrayList<>(List.of(existing));
        }

        @Override
        public List<String> eventKinds() {
            return new ArrayList<>(kinds);
        }

        @Override
        public void removeAt(int index) {
            // Remove by identity of the reported slot, mirroring the engine pool which is indexed
            // against the same pre-rebuild snapshot.
            live.remove(kinds.get(index));
        }

        @Override
        public boolean append(CoopMissionBoardSync.Entry entry) {
            if (failNext) {
                failNext = false;
                return false;
            }
            appended.add(entry.missionId());
            live.add(entry.missionId());
            return true;
        }
    }

    @Test
    void rebuildDropsGuestOffersKeepsIntelBackedOnesAndAppendsInSnapshotOrder() {
        FakePool pool = new FakePool("LuddicFarmerBarEvent", "PirateBaseRumorBarEvent", "DeliveryBarEvent");

        CoopBarPoolInjector.Rebuild result = CoopBarPoolInjector.rebuild(pool,
                List.of(offer("cheapCom", 5L), offer("extr", 6L), offer("smuggling", 7L)));

        assertEquals(2, result.removed(), "both locally generated offers go");
        assertEquals(1, result.kept(), "the intel-backed rumor stays");
        assertEquals(3, result.injected());
        assertEquals(0, result.failed());
        assertEquals(List.of("cheapCom", "extr", "smuggling"), pool.appended);
        assertEquals(List.of("PirateBaseRumorBarEvent", "cheapCom", "extr", "smuggling"), pool.live);
    }

    @Test
    void rebuildOfAnEmptySnapshotStillClearsTheGuestsOwnOffers() {
        FakePool pool = new FakePool("LuddicFarmerBarEvent", "DeliveryBarEvent");

        CoopBarPoolInjector.Rebuild result = CoopBarPoolInjector.rebuild(pool, List.of());

        assertEquals(2, result.removed());
        assertEquals(List.of(), pool.live);
    }

    @Test
    void aConstructionFailureIsCountedNotFatal() {
        FakePool pool = new FakePool();
        pool.failNext = true;

        CoopBarPoolInjector.Rebuild result = CoopBarPoolInjector.rebuild(pool,
                List.of(offer("unknownToThisClient", 1L), offer("cheapCom", 2L)));

        assertEquals(1, result.failed());
        assertEquals(1, result.injected());
        assertEquals(List.of("cheapCom"), pool.appended);
    }

    @Test
    void managedSubsetIsEverythingButTheIntelBackedClasses() {
        assertTrue(CoopBarPoolInjector.isManaged("DeliveryBarEvent"));
        assertTrue(CoopBarPoolInjector.isManaged(""));
        assertFalse(CoopBarPoolInjector.isManaged("LuddicPathBaseBarEvent"));
    }

    // ---- Guest injectable filter ---------------------------------------------------------------

    @Test
    void injectableKeepsBarEntriesInOrderAndDropsTheOtherPlayersClaims() {
        List<CoopMissionBoardSync.Entry> entries = List.of(
                offer("a", 1L),
                offer("b", 2L).withAcceptedBy("host"),
                offer("c", 3L).withAcceptedBy("guest"),
                new CoopMissionBoardSync.Entry("m", CoopMissionBoardSync.SourceType.CONTACT,
                        "contact-1", "", "", "", "", 0L, 0L, ""));

        List<CoopMissionBoardSync.Entry> injectable = CoopBarPoolInjector.injectable(entries, "guest");

        assertEquals(List.of("a", "c"), injectable.stream().map(CoopMissionBoardSync.Entry::missionId).toList());
    }

    @Test
    void injectableDropsIntelBackedAndDuplicateIds() {
        List<CoopMissionBoardSync.Entry> entries = List.of(
                CoopMissionBoardSync.Entry.barOffer("rumor", "PirateBaseRumorBarEvent", 1L, "", 0L),
                offer("a", 2L),
                offer("a", 3L));

        List<CoopMissionBoardSync.Entry> injectable = CoopBarPoolInjector.injectable(entries, "guest");

        assertEquals(1, injectable.size());
        assertEquals(2L, injectable.get(0).contentSeed(), "the first occurrence of an id wins");
    }

    @Test
    void injectableToleratesNoLocalPlayerId() {
        List<CoopMissionBoardSync.Entry> entries = List.of(offer("a", 1L), offer("b", 2L).withAcceptedBy("host"));

        assertEquals(1, CoopBarPoolInjector.injectable(entries, null).size());
        assertEquals(0, CoopBarPoolInjector.injectable(null, "guest").size());
    }

    // ---- Suppressor ----------------------------------------------------------------------------

    @Test
    void suppressorOnlyRunsOnTheGuest() {
        assertTrue(CoopBarGenerationSuppressor.activeForRole(coop.net.CoopConnectionRole.GUEST));
        assertFalse(CoopBarGenerationSuppressor.activeForRole(coop.net.CoopConnectionRole.HOST));
        assertFalse(CoopBarGenerationSuppressor.activeForRole(coop.net.CoopConnectionRole.NONE));
    }

    @Test
    void suppressorMatchesOnlyTheGeneratorScript() {
        assertTrue(CoopBarGenerationSuppressor.isManagerScript("BarEventManager"));
        // PortsideBarData must keep ticking: it is the pool itself, and its advance prunes the
        // guest's own intel-backed rumors.
        assertFalse(CoopBarGenerationSuppressor.isManagerScript("PortsideBarData"));
        assertFalse(CoopBarGenerationSuppressor.isManagerScript(null));
    }

    @Test
    void suppressorRearmsOnSessionRestart() {
        CoopBarGenerationSuppressor suppressor = new CoopBarGenerationSuppressor();
        assertFalse(suppressor.isSuppressed());

        // A null sector is the pre-load frame: it must not consume the one-shot.
        suppressor.tick(null);
        assertFalse(suppressor.isSuppressed());

        suppressor.reset();
        assertFalse(suppressor.isSuppressed());
    }
}
