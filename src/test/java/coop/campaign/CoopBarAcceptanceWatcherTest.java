package coop.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 12 first-come trigger: the pool-id diff that decides when the local player accepted a bar
 * offer. The engine half — reading {@code PortsideBarData} and classifying a vanished offer through
 * {@code BarEventManager.getCreatorFor} — is covered by the two-instance smoke; the bookkeeping that
 * decides whether a claim is raised at all is decided here.
 */
class CoopBarAcceptanceWatcherTest {

    @Test
    void theFirstPollOnlyBaselinesAndClaimsNothing() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b");
        probe.accepts("a", "b");

        assertEquals(List.of(), watcher.poll(probe),
                "there is no previous pool to have vanished from");
    }

    @Test
    void anOfferThatVanishedWithAcceptanceEvidenceIsClaimed() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b");
        watcher.poll(probe);

        probe.pool("b");
        probe.accepts("a");

        assertEquals(List.of("a"), watcher.poll(probe));
    }

    @Test
    void anExpiredOfferIsNotClaimed() {
        // The whole reason the watcher asks the probe rather than trusting the disappearance: offers
        // time out too, and claiming on a timeout would hand the partner a mission nobody took.
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b");
        watcher.poll(probe);

        probe.pool("b");
        probe.accepts();

        assertEquals(List.of(), watcher.poll(probe));
    }

    @Test
    void anOfferIsClaimedOnceEvenIfItComesBack() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a");
        watcher.poll(probe);

        probe.pool();
        probe.accepts("a");
        assertEquals(List.of("a"), watcher.poll(probe));

        probe.pool("a");
        assertEquals(List.of(), watcher.poll(probe), "re-appearing is not a second acceptance");

        probe.pool();
        assertEquals(List.of("a"), watcher.poll(probe), "but a second real one still counts");
    }

    @Test
    void anUnreadablePoolKeepsTheBaselineInsteadOfClaimingEverything() {
        // Null means "I could not look". Treating it as an empty pool would claim every offer on the
        // board the moment the sector is momentarily unavailable.
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b");
        watcher.poll(probe);

        probe.unreadable();
        probe.accepts("a", "b");
        assertEquals(List.of(), watcher.poll(probe));

        probe.pool("a", "b");
        assertEquals(List.of(), watcher.poll(probe), "the baseline survived the failed read");
    }

    @Test
    void resyncSwallowsOurOwnBulkRewriteOfThePool() {
        // The guest's injector clears and rebuilds the managed subset on every host snapshot. Every
        // one of those removals looks exactly like an acceptance from the outside.
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b", "c");
        watcher.poll(probe);

        watcher.resync();
        probe.pool("d");
        probe.accepts("a", "b", "c");

        assertEquals(List.of(), watcher.poll(probe));
    }

    @Test
    void forgetDropsAnOfferThisClientConsumedForThePartner() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b");
        watcher.poll(probe);

        // The partner won the claim, so this client removed the offer from its own pool.
        watcher.forget("a");
        probe.pool("b");
        probe.accepts("a");

        assertEquals(List.of(), watcher.poll(probe));
    }

    @Test
    void resetClearsTheBaseline() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a");
        watcher.poll(probe);

        watcher.reset();
        probe.pool();
        probe.accepts("a");

        assertEquals(List.of(), watcher.poll(probe));
    }

    @Test
    void severalAcceptancesInOnePollAreAllReportedInBaselineOrder() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b", "c");
        watcher.poll(probe);

        probe.pool("b");
        probe.accepts("a", "c");

        assertEquals(List.of("a", "c"), watcher.poll(probe));
    }

    @Test
    void acceptanceIsOnlyAskedAboutForOffersThatActuallyVanished() {
        FakeProbe probe = new FakeProbe();
        CoopBarAcceptanceWatcher watcher = new CoopBarAcceptanceWatcher();
        probe.pool("a", "b");
        watcher.poll(probe);

        probe.pool("a");
        probe.accepts("a", "b");
        watcher.poll(probe);

        assertEquals(List.of("b"), probe.asked,
                "an offer still on the board is never classified");
        assertTrue(probe.committed >= 2, "every successful poll promotes its reading");
    }

    /** Pool ids and acceptance verdicts fed in by hand; no engine anywhere. */
    private static final class FakeProbe implements CoopBarAcceptanceWatcher.Probe {
        private List<String> ids = List.of();
        private boolean readable = true;
        private Set<String> accepted = new LinkedHashSet<>();
        private final List<String> asked = new ArrayList<>();
        private int committed;

        private void pool(String... poolIds) {
            ids = List.of(poolIds);
            readable = true;
        }

        private void unreadable() {
            readable = false;
        }

        private void accepts(String... acceptedIds) {
            accepted = new LinkedHashSet<>(List.of(acceptedIds));
        }

        @Override
        public List<String> poolIds() {
            return readable ? ids : null;
        }

        @Override
        public boolean acceptedLocally(String missionId) {
            asked.add(missionId);
            return accepted.contains(missionId);
        }

        @Override
        public void commit() {
            committed++;
        }
    }
}
