package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 21 red-team item 11: whose salvage the stats page counts.
 *
 * <p>{@code handleWorldDelta} tallies a consumed salvageable when it arrives over the wire, which is
 * always the <em>other</em> client's. The local one goes out through
 * {@code reportLocalSalvageConsume}, and that path applied the ledger and put the delta on the socket
 * without ever telling the tally - so on a two-player session the counter only ever moved for half
 * the salvage, on whichever client did not pick it up.
 */
class CoopSalvageTallyTest {

    @Test
    void theLocalClientsOwnSalvageIsCounted() {
        AtomicInteger tallied = new AtomicInteger();
        CoopCampaignReplicator replicator = hostReplicator();
        replicator.setStatsSink(sinkCounting(tallied));

        replicator.reportLocalSalvageConsumeForTest("derelict-1");

        assertEquals(1, tallied.get(), "the client that picked it up counts it too");
    }

    @Test
    void theHostsRebroadcastEchoCannotDoubleCountTheSameSalvage() {
        AtomicInteger tallied = new AtomicInteger();
        CoopCampaignReplicator replicator = hostReplicator();
        replicator.setStatsSink(sinkCounting(tallied));

        replicator.reportLocalSalvageConsumeForTest("derelict-1");
        // The same entity coming back off the wire, which is exactly what the echo looks like.
        replicator.handle(CoopMessages.worldDelta("session-a", 4L, 5678L,
                "derelict-1", "CONSUME", true, "", "host-player"));

        assertEquals(1, tallied.get(), "the world ledger is what makes the count once-per-entity");
    }

    private static CoopCampaignReplicator.StatsSink sinkCounting(AtomicInteger salvage) {
        return new CoopCampaignReplicator.StatsSink() {
            @Override
            public void onTrade(String playerId, String marketId, long netCredits) {
            }

            @Override
            public void onMissionClaimed(String playerId) {
            }

            @Override
            public void onSalvageConsumed() {
                salvage.incrementAndGet();
            }

            @Override
            public void onColonyFounded(String playerId) {
            }
        };
    }

    private static CoopCampaignReplicator hostReplicator() {
        return new CoopCampaignReplicator(
                new SilentNetService(CoopConnectionRole.HOST), activeHostSession(), () -> 5678L);
    }

    /**
     * A strict id supplier rather than {@link TestSessions#activeHostSession()}'s repeating one: this
     * test's original copy threw {@code IndexOutOfBoundsException} on an over-draw, and
     * {@link TestSessions#strictSequencedIds} is the shared variant that keeps that loud-failure
     * behaviour (as a {@code NoSuchElementException} instead, which nothing here asserts on).
     */
    private static CoopSessionState activeHostSession() {
        CoopSessionState session = new CoopSessionState(
                TestSessions.strictSequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(TestSessions.SEED, "seed-a", "fingerprint-a");
        return session;
    }

    private static final class SilentNetService extends CoopNetService {
        private final CoopConnectionRole role;
        private final List<CoopMessages.Message> sent = new ArrayList<>();

        private SilentNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(CoopMessages.Message message) {
            sent.add(message);
        }
    }
}
