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

    // ---- net-fix-6: the abuse gates trust a connection's own proof, not the session token ----------

    /**
     * Holding the slot is not proof of anything: attachment happens before any authentication, so a
     * stranger that connected once used to be a "known peer" and every further connection from that
     * address skipped the failed-password cooldown and the connection throttle — the two gates that
     * are supposed to make guessing cost something.
     */
    @Test
    void a3_anUnprovenOccupantsAddressIsThrottledLikeAnyOtherStranger() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService(System::currentTimeMillis);
        CoopNetService squatter = new CoopNetService();
        CoopNetService guesser = new CoopNetService();
        try {
            host.startHost(port);
            squatter.connect("127.0.0.1", port);
            waitUntil(() -> connectedBoth(host, squatter), "the squatter took the slot");

            // Three wrong lobby passwords from this address arm the cooldown; the squatter's own
            // connection is still sitting in the slot, unproven.
            host.noteFailedProof(address("127.0.0.1"));
            host.noteFailedProof(address("127.0.0.1"));
            host.noteFailedProof(address("127.0.0.1"));
            assertTrue(host.isProofThrottled(address("127.0.0.1")));

            guesser.connect("127.0.0.1", port);
            waitUntil(() -> {
                host.flushOutbound();
                guesser.flushOutbound();
                return host.datagramStats().proofThrottled() > 0L;
            }, "the next connection from the cooled-down address was refused outright");
        } finally {
            guesser.shutdown();
            squatter.shutdown();
            host.shutdown();
        }
    }

    /**
     * The other half: an unproven socket closed while a session token happens to be set must not buy
     * its address {@code KNOWN_PEER_MEMORY_MILLIS} of exemption. The token stays set for the whole
     * reconnect grace window, which is precisely when a stranger can take the vacated slot.
     */
    @Test
    void a3_closingAnUnprovenSocketDuringAGraceWindowLeavesNoKnownPeerMemory() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService(System::currentTimeMillis);
        CoopNetService stranger = new CoopNetService();
        CoopNetService guesser = new CoopNetService();
        try {
            host.startHost(port);
            // A grace window: the session token is still set while the partner is away.
            host.setExpectedSessionToken(CoopMessages.wireToken("session-a"));

            stranger.connect("127.0.0.1", port);
            waitUntil(() -> connectedBoth(host, stranger), "a stranger took the vacated slot");
            assertTrue(host.dropActiveConnection("the stranger is closed unproven"));

            host.noteFailedProof(address("127.0.0.1"));
            host.noteFailedProof(address("127.0.0.1"));
            host.noteFailedProof(address("127.0.0.1"));

            guesser.connect("127.0.0.1", port);
            waitUntil(() -> {
                host.flushOutbound();
                guesser.flushOutbound();
                return host.datagramStats().proofThrottled() > 0L;
            }, "the address earned no memory, so the cooldown still applies");
            assertFalse(host.isConnected(), "and it did not get the slot either");
        } finally {
            guesser.shutdown();
            stranger.shutdown();
            host.shutdown();
        }
    }

    /**
     * The exemption the memory exists for is untouched: a partner that authenticated and then lost
     * its link is knocking every 500 ms against a slot the OS has not finished tearing down, and must
     * not throttle itself out of its own reconnect grace.
     */
    @Test
    void a3_aProvenPeersMemoryStillExemptsItsReconnect() throws Exception {
        int port = reserveLocalPort();
        CoopNetService host = new CoopNetService(System::currentTimeMillis);
        CoopNetService guest = new CoopNetService();
        try {
            host.startHost(port);
            guest.connect("127.0.0.1", port);
            waitUntil(() -> connectedBoth(host, guest), "the partner connected");
            // The handshake was accepted for the socket in the slot: this is where it becomes proven.
            host.setExpectedSessionToken(CoopMessages.wireToken("session-a"));

            assertTrue(host.dropActiveConnection("link death"));
            host.noteFailedProof(address("127.0.0.1"));
            host.noteFailedProof(address("127.0.0.1"));
            host.noteFailedProof(address("127.0.0.1"));

            waitUntil(() -> {
                host.flushOutbound();
                guest.flushOutbound();
                return connectedBoth(host, guest);
            }, "the proven partner is let back in despite the cooldown");
            assertEquals(0L, host.datagramStats().proofThrottled(),
                    "and it was never refused on the way");
        } finally {
            guest.shutdown();
            host.shutdown();
        }
    }

    private static int reserveLocalPort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** Both ends report a live channel; asking is also what drives the host's accept poll. */
    private static boolean connectedBoth(CoopNetService host, CoopNetService guest) {
        boolean hostConnected = host.isConnected();
        boolean guestConnected = guest.isConnected();
        return hostConnected && guestConnected;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description);
    }
}
