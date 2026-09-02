package coop.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport seams the Phase 20 red-team fixes needed: the attach generation the pump watches for
 * an invisible half-open replacement (B2/C1), and the per-address password cooldown that the
 * connection throttle could not provide (A3).
 */
class CoopNetServiceRedTeamTest {

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void b2_theConnectionGenerationStartsAtZeroAndIsReadableWithoutALink() {
        CoopNetService service = new CoopNetService(() -> 0L);

        // The pump reads this every frame, including frames with no transport at all; the value only
        // has to be stable and monotonic. The attach that bumps it is covered end to end by
        // CoopNetPumpTest#b2_aHalfOpenReplacementIsTreatedAsADropEdgeSoTheResumeResolves.
        assertEquals(0L, service.connectionGeneration());
        assertEquals(0L, service.connectionGeneration());
    }

    @Test
    void a3_threeFailedProofsArmACooldownThatDoublesAndIsPerAddress() {
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetService service = new CoopNetService(now::get);
        InetAddress guesser = address("203.0.113.9");
        InetAddress innocent = address("203.0.113.10");

        service.noteFailedProof(guesser);
        service.noteFailedProof(guesser);
        assertFalse(service.isProofThrottled(guesser), "two wrong guesses are a typo, not an attack");

        service.noteFailedProof(guesser);
        assertTrue(service.isProofThrottled(guesser));
        assertFalse(service.isProofThrottled(innocent), "the cooldown is per address");

        // 30 s, then the next failure doubles it. Dropping the connection on a wrong guess frees the
        // slot, so without this the connection throttle - which is only consulted when no slot is
        // free - never engaged and guessing was limited by nothing but reconnect rate.
        now.set(1_000L + 30_001L);
        assertFalse(service.isProofThrottled(guesser));

        service.noteFailedProof(guesser);
        assertTrue(service.isProofThrottled(guesser));
        now.addAndGet(30_001L);
        assertTrue(service.isProofThrottled(guesser), "the second cooldown is 60 s, not 30");
        now.addAndGet(30_001L);
        assertFalse(service.isProofThrottled(guesser));
    }

    @Test
    void a3_aNullAddressIsNeverThrottledAndNeverRecorded() {
        CoopNetService service = new CoopNetService(() -> 0L);

        // An unattached link has no pinned address; the caller must not have to special-case it.
        service.noteFailedProof(null);
        assertFalse(service.isProofThrottled(null));
    }
}
