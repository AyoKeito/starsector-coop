package coop.net;

import com.fs.starfarer.api.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that turns "the campaign state changed" into "tell the partner they are on their own".
 *
 * <p>Everything worth testing here is {@link CoopSessionLeaveWatchdog#observe}, which is deliberately
 * a pure function of one sample so the decision can be checked without a thread, a clock or an
 * engine. The thread around it is four lines: sample, decide, send, stop.
 *
 * <p>The one that would actually ship a bug is {@code COMBAT}. The campaign state leaves
 * {@code CAMPAIGN} every time a battle starts, and a watchdog that fired on "not CAMPAIGN" rather
 * than on "TITLE" would end the co-op session the first time either player got into a fight.
 */
class CoopSessionLeaveWatchdogTest {

    @Test
    void theTitleScreenFiresExactlyOnce() {
        RecordingSender sender = new RecordingSender();
        CoopSessionLeaveWatchdog watchdog = new CoopSessionLeaveWatchdog(sender);

        assertTrue(watchdog.observe(GameState.TITLE));
        assertTrue(watchdog.fired());
        assertFalse(watchdog.observe(GameState.TITLE), "there is only one departure to report");
        assertFalse(watchdog.observe(GameState.CAMPAIGN));
    }

    @Test
    void combatIsNotADeparture() {
        CoopSessionLeaveWatchdog watchdog = new CoopSessionLeaveWatchdog(new RecordingSender());

        // A battle: CAMPAIGN -> COMBAT -> CAMPAIGN, sampled four times a second throughout.
        for (int i = 0; i < 40; i++) {
            assertFalse(watchdog.observe(GameState.COMBAT),
                    "a battle must never read as the player quitting");
        }
        assertFalse(watchdog.observe(GameState.CAMPAIGN));
        assertFalse(watchdog.fired());

        // And the latch is still armed for the real thing afterwards.
        assertTrue(watchdog.observe(GameState.TITLE));
    }

    @Test
    void aStateTheEngineWillNotYetGiveUsIsNotADeparture() {
        // currentStateOrNull() answers null when Global throws, which it does during shutdown and
        // before the first campaign frame. Neither is somebody leaving.
        CoopSessionLeaveWatchdog watchdog = new CoopSessionLeaveWatchdog(new RecordingSender());

        assertFalse(watchdog.observe(null));
        assertFalse(watchdog.fired());
    }

    @Test
    void theCampaignItselfNeverFires() {
        CoopSessionLeaveWatchdog watchdog = new CoopSessionLeaveWatchdog(new RecordingSender());

        for (int i = 0; i < 1_000; i++) {
            assertFalse(watchdog.observe(GameState.CAMPAIGN));
        }
        assertFalse(watchdog.fired());
    }

    @Test
    void startAndStopAreIdempotent() throws Exception {
        RecordingSender sender = new RecordingSender();
        CoopSessionLeaveWatchdog watchdog = new CoopSessionLeaveWatchdog(sender);

        watchdog.start();
        watchdog.start();
        try {
            assertTrue(watchdog.polling());
        } finally {
            watchdog.stop();
            watchdog.stop();
        }

        // The poll thread exits on the interrupt; give it a moment rather than asserting instantly.
        for (int i = 0; i < 200 && watchdog.polling(); i++) {
            Thread.sleep(10L);
        }
        assertFalse(watchdog.polling(), "stop() must actually stop it");
        assertEquals(List.of(), sender.reasons,
                "nothing ever reported TITLE, so nothing was ever sent");
    }

    /**
     * "Newest wins", the same rule the save checkpoint and the intel feed already use for their
     * static seams. It is what bounds this class's two process-lifetime resources: a game load that
     * builds a second pump supersedes the first pump's watchdog, whose poll loop then exits on its
     * own rather than sitting there for the life of the process.
     */
    @Test
    void asecondWatchdogSupersedesTheFirstAndTheFirstThreadRetires() throws Exception {
        CoopSessionLeaveWatchdog first = new CoopSessionLeaveWatchdog(new RecordingSender());
        CoopSessionLeaveWatchdog second = new CoopSessionLeaveWatchdog(new RecordingSender());
        try {
            first.start();
            assertTrue(first.polling());

            second.start();
            for (int i = 0; i < 200 && first.polling(); i++) {
                Thread.sleep(10L);
            }
            assertFalse(first.polling(), "a superseded watchdog must not keep polling forever");
            assertTrue(second.polling());
        } finally {
            first.stop();
            second.stop();
        }
    }

    private static final class RecordingSender implements CoopSessionLeaveWatchdog.LeaveSender {
        private final List<String> reasons = new CopyOnWriteArrayList<>();

        @Override
        public void sendSessionLeave(String reason) {
            reasons.add(reason);
        }
    }
}
