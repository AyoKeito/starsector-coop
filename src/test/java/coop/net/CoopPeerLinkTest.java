package coop.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20.5. These are the per-peer behaviours that used to be fields on {@code CoopNetService} and
 * are now testable without a socket: frame assembly, coalescing placement, the candidate-address
 * lifecycle, and — the one that has actually bitten — what a re-attach must forget.
 */
class CoopPeerLinkTest {
    private static final String SESSION = "session-a";
    private static final String TOKEN = CoopMessages.wireToken(SESSION);

    private CoopPeerLink link() {
        return new CoopPeerLink(0, 64);
    }

    private static SocketAddress address(String host, int port) {
        return new InetSocketAddress(host, port);
    }

    private List<String> feed(CoopPeerLink link, String bytes) {
        List<String> frames = new ArrayList<>();
        for (byte value : bytes.getBytes(StandardCharsets.UTF_8)) {
            link.appendInboundByte(value, frames::add, () -> frames.add("<oversized>"));
        }
        return frames;
    }

    // ---- frame assembly --------------------------------------------------------------------------

    @Test
    void splitsFramesOnNewlinesAndTrimsCarriageReturns() {
        CoopPeerLink link = link();

        assertEquals(List.of("one", "two"), feed(link, "one\r\ntwo\n"));
    }

    @Test
    void anOversizedFrameIsDiscardedUpToItsTerminatorAndTheNextOneStillParses() {
        CoopPeerLink link = link();
        String tooLong = "x".repeat(80);

        List<String> frames = feed(link, tooLong + "\nrecovered\n");

        assertEquals(List.of("<oversized>", "recovered"), frames,
                "the framer must resynchronise on the next newline rather than wedge");
    }

    // ---- outbound queue and coalescing -----------------------------------------------------------

    @Test
    void coalescingIsOffUntilTheLinkIsBacklogged() {
        CoopPeerLink link = link();

        assertFalse(link.backlogged(2));
        link.enqueue(snapshot(1L));
        assertFalse(link.backlogged(2));
        link.enqueue(snapshot(2L));
        assertTrue(link.backlogged(2), "two queued messages reach a threshold of two");
    }

    @Test
    void replacingAQueuedSnapshotKeepsItsPlaceInTheQueue() {
        CoopPeerLink link = link();
        CoopMessages.Message first = snapshot(1L);
        CoopMessages.Message claim = CoopMessages.interactionClaim(SESSION, 2L, 1000L,
                "entity", "Entity", "player-a");
        CoopMessages.Message newest = snapshot(3L);
        link.enqueue(first);
        link.enqueue(claim);

        assertTrue(link.replaceQueued(newest, CoopNetService.coalesceKey(newest)));

        assertEquals(2, link.outboundDepth());
        assertSame(newest, link.outbound().get(0), "the newest snapshot takes the oldest one's slot");
        assertSame(claim, link.outbound().get(1), "events keep their relative order");
    }

    @Test
    void aMessageWithNoCoalescingKeyIsNeverReplaced() {
        CoopPeerLink link = link();
        CoopMessages.Message claim = CoopMessages.interactionClaim(SESSION, 1L, 1000L,
                "entity", "Entity", "player-a");
        link.enqueue(claim);

        assertFalse(link.replaceQueued(claim, CoopNetService.coalesceKey(claim)));
    }

    // ---- sender id -------------------------------------------------------------------------------

    @Test
    void theSenderIdIsLearnedOnceAndNeverOverwritten() {
        CoopPeerLink link = link();

        link.learnSenderId(null);
        assertNull(link.senderId());
        link.learnSenderId("");
        assertNull(link.senderId(), "an empty stamp is not an identity");

        link.learnSenderId("guest-player");
        link.learnSenderId("someone-else");

        assertEquals("guest-player", link.senderId());
    }

    // ---- UDP address lifecycle -------------------------------------------------------------------

    @Test
    void onlyOneCandidateIsChallengedAtATime() {
        CoopPeerLink link = link();

        assertTrue(link.beginCandidate(address("127.0.0.1", 1), "aaaa", 1_000L));
        assertFalse(link.beginCandidate(address("127.0.0.1", 2), "bbbb", 1_010L),
                "a spray of sources must not mint a nonce per packet");
        assertEquals(address("127.0.0.1", 1), link.candidateUdpAddress());
        assertEquals(1_000L, link.candidateFirstSeenAtMillis());
    }

    @Test
    void anEchoOnlyValidatesFromTheChallengedAddressWithTheIssuedNonce() {
        CoopPeerLink link = link();
        SocketAddress candidate = address("127.0.0.1", 1);
        link.beginCandidate(candidate, "abcd", 1_000L);

        assertFalse(link.completeCandidate(address("127.0.0.1", 2), "abcd"),
                "the right nonce from the wrong address proves an observer, not a peer");
        assertFalse(link.completeCandidate(candidate, "0000"), "a guessed nonce must not validate");
        assertNull(link.validatedUdpAddress());

        assertTrue(link.completeCandidate(candidate, "abcd"));
        assertEquals(candidate, link.validatedUdpAddress());
        assertNull(link.candidateUdpAddress(), "a completed candidate is forgotten");
    }

    @Test
    void forgettingACandidateLeavesTheValidatedTargetAlone() {
        CoopPeerLink link = link();
        link.setValidatedUdpAddress(address("127.0.0.1", 9));
        link.beginCandidate(address("127.0.0.1", 1), "abcd", 1_000L);

        link.forgetCandidate();

        assertNull(link.candidateUdpAddress());
        assertEquals(address("127.0.0.1", 9), link.validatedUdpAddress(),
                "an unproven candidate expiring must not cost us the working path");
    }

    @Test
    void anUnpinnedLinkAcceptsAnySourceAndAPinnedOneOnlyItsOwn() throws Exception {
        CoopPeerLink link = link();

        assertTrue(link.acceptsSource(address("203.0.113.9", 1)), "no TCP peer pinned yet");

        link.attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false);

        assertTrue(link.acceptsSource(address("127.0.0.1", 65_000)),
                "the peer's UDP port legitimately differs from its TCP port");
        assertFalse(link.acceptsSource(address("203.0.113.9", 65_000)));
    }

    // ---- re-attach -------------------------------------------------------------------------------

    @Test
    void reAttachingResetsTheIdentityStrikesAndFrameAssemblyButKeepsTheQueues() throws Exception {
        CoopPeerLink link = link();
        link.enqueue(snapshot(1L));
        link.enqueueDatagram("queued-datagram");
        link.attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false);
        link.learnSenderId("guest-a");
        link.noteInvalidFrame();
        link.noteInvalidFrame();
        feed(link, "half-a-frame-with-no-newline");

        link.attach(null, InetAddress.getByName("127.0.0.2"), 5_000L, false);

        assertNull(link.senderId(), "a new connection is a new identity until it stamps a message");
        assertEquals(0, link.invalidFrames(), "strikes belong to the connection that earned them");
        assertEquals(5_000L, link.lastInboundFrameAtMillis(),
                "the silence clock restarts, so the fresh channel is not itself half-open-eligible");
        assertEquals(List.of("carried-over"), feed(link, "carried-over\n"),
                "the previous connection's half-frame must not prefix the new one");
        assertEquals(1, link.outboundDepth(), "queued traffic survives a reconnect");
        assertEquals(1, link.outboundDatagrams().size());
    }

    @Test
    void aHostReAttachDropsTheValidatedUdpAddressAndAGuestReAttachKeepsIt() throws Exception {
        CoopPeerLink host = link();
        host.setValidatedUdpAddress(address("127.0.0.1", 1));
        host.attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, true);
        assertNull(host.validatedUdpAddress(),
                "a reconnecting guest behind NAT almost always comes back on a different port");

        CoopPeerLink guest = link();
        guest.setValidatedUdpAddress(address("127.0.0.1", 1));
        guest.attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false);
        assertEquals(address("127.0.0.1", 1), guest.validatedUdpAddress(),
                "the guest's target is configured, not learned");
    }

    @Test
    void resetClearsEverythingIncludingTheQueues() throws Exception {
        CoopPeerLink link = link();
        link.attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false);
        link.learnSenderId("guest-a");
        link.enqueue(snapshot(1L));
        link.enqueueDatagram("stale");
        link.setValidatedUdpAddress(address("127.0.0.1", 1));

        link.reset();

        assertNull(link.senderId());
        assertNull(link.pinnedPeerAddress());
        assertNull(link.validatedUdpAddress());
        assertEquals(0, link.outboundDepth(), "a restarted session must not replay the previous one");
        assertTrue(link.outboundDatagrams().isEmpty());
    }

    // ---- warn-once flags -------------------------------------------------------------------------

    @Test
    void warnOnceFlagsFireExactlyOncePerConnection() throws Exception {
        CoopPeerLink link = link();

        assertTrue(link.shouldWarnForeignSource());
        assertFalse(link.shouldWarnForeignSource());
        assertTrue(link.shouldLogCandidateTimeout());
        assertFalse(link.shouldLogCandidateTimeout());
        assertTrue(link.shouldWarnQueueDepth());
        assertFalse(link.shouldWarnQueueDepth());

        assertTrue(link.shouldWarnDatagramSendFailure());
        assertFalse(link.shouldWarnDatagramSendFailure());

        link.attach(null, InetAddress.getByName("127.0.0.1"), 1_000L, false);

        assertTrue(link.shouldWarnForeignSource(), "a fresh connection deserves its own warning");
        assertTrue(link.shouldLogCandidateTimeout());
        // Updated for red-team C8. These two used to survive attach, on the reasoning that the queue
        // survives a reconnect. But the thing being warned about is a socket that will not drain and
        // a UDP path that will not send - both properties of the connection, not of the queue - so
        // leaving the flags set meant the first bad connection of a session silenced the warning for
        // every connection after it, which is the run where the evidence was wanted.
        assertTrue(link.shouldWarnQueueDepth(), "a fresh connection re-arms the queue-depth warning");
        assertTrue(link.shouldWarnDatagramSendFailure());
    }

    private static CoopMessages.Message snapshot(long seq) {
        return CoopMessages.timeSnapshot(SESSION, seq, false, false, 1000L + seq, 1L, 1000L + seq, "");
    }

    /** Guards the assumption the datagram helpers above rely on. */
    @Test
    void theSessionTokenIsStableForAGivenSessionId() {
        assertEquals(TOKEN, CoopMessages.wireToken(SESSION));
    }
}
