package coop.net;

/**
 * Immutable snapshot of the UDP transport's counters (Phase 20.1). Every one of these events is
 * something the transport handles silently and rate-limits in the log — a flood of foreign packets
 * must not be allowed to write a log line each — so without counters the only evidence a session ever
 * dropped traffic would be a mirror that stopped moving.
 *
 * <p>Read by the connection doctor and, from the next milestone, by {@code LINK_STATUS} and the link
 * HUD. Deliberately a value snapshot rather than live accessors: a reader that samples eight numbers
 * across eight lock acquisitions can see a state that never existed.
 *
 * @param droppedNoToken        datagrams dropped because no session token was expected yet
 * @param droppedTokenMismatch  datagrams dropped because their token was not this session's
 * @param droppedForeignSource  datagrams dropped because the source was not the pinned TCP peer
 * @param droppedMalformed      datagrams dropped because the envelope prefix would not parse
 * @param probesSent            {@code PATH_PROBE} challenges sent to a candidate address
 * @param probeEchoesReceived   {@code PATH_PROBE} echoes received (matching or not)
 * @param pathValidations       times a candidate address passed its challenge and became the target
 * @param keepalivesSent        {@code UDP_PROBE} datagrams queued by the idle keepalive
 * @param keepalivesReceived    {@code UDP_PROBE} datagrams received from the peer
 * @param icmpTransients        transient socket/ICMP errors absorbed without closing the channel
 * @param oversized             outbound datagrams dropped for exceeding the size cap
 * @param escalatedToTcp        composed datagrams rerouted onto TCP for exceeding the size budget
 * @param connectionAttempts    TCP connections the host accepted, throttled or not (Phase 20.4)
 * @param connectionsThrottled  TCP connections closed with no reply because their source was in its
 *                              rate-limit cooldown
 * @param invalidFrames         undecodable TCP frames received across all peers
 * @param connectionsDroppedForGarbage pre-handshake connections dropped for repeated garbage frames
 * @param droppedOversizedInbound inbound datagrams dropped for exceeding the inbound size cap
 * @param droppedBadEpoch       inbound datagrams dropped for an epoch stamp outside the valid window
 * @param droppedBadChunk       inbound datagrams dropped for a chunk index outside 0..63
 * @param handshakeDeadlineDrops connections dropped for holding a slot without ever proving a session
 * @param proofThrottled        connections closed with no reply because the source is in its
 *                              failed-password cooldown
 * @param queueOverflowDrops    outbound TCP messages discarded by the queue hard cap
 * @param lastInboundDatagramAtMillis wall clock of the last accepted inbound datagram, 0 if none
 * @param validatedRemote       the current send target as text, or "" when nothing is validated
 */
public record CoopDatagramStats(
        long droppedNoToken,
        long droppedTokenMismatch,
        long droppedForeignSource,
        long droppedMalformed,
        long probesSent,
        long probeEchoesReceived,
        long pathValidations,
        long keepalivesSent,
        long keepalivesReceived,
        long icmpTransients,
        long oversized,
        long escalatedToTcp,
        long connectionAttempts,
        long connectionsThrottled,
        long invalidFrames,
        long connectionsDroppedForGarbage,
        long droppedOversizedInbound,
        long droppedBadEpoch,
        long droppedBadChunk,
        long handshakeDeadlineDrops,
        long proofThrottled,
        long queueOverflowDrops,
        long lastInboundDatagramAtMillis,
        String validatedRemote) {

    public CoopDatagramStats {
        validatedRemote = validatedRemote == null ? "" : validatedRemote;
    }

    /**
     * The pre-hardening component list, with the six red-team counters zeroed. Kept so the callers
     * that only care about the original numbers - the doctor and HUD fixtures - read unchanged; a
     * counter added to this record must never force an edit to a test about something else.
     */
    public CoopDatagramStats(long droppedNoToken,
                             long droppedTokenMismatch,
                             long droppedForeignSource,
                             long droppedMalformed,
                             long probesSent,
                             long probeEchoesReceived,
                             long pathValidations,
                             long keepalivesSent,
                             long keepalivesReceived,
                             long icmpTransients,
                             long oversized,
                             long escalatedToTcp,
                             long connectionAttempts,
                             long connectionsThrottled,
                             long invalidFrames,
                             long connectionsDroppedForGarbage,
                             long lastInboundDatagramAtMillis,
                             String validatedRemote) {
        this(droppedNoToken, droppedTokenMismatch, droppedForeignSource, droppedMalformed, probesSent,
                probeEchoesReceived, pathValidations, keepalivesSent, keepalivesReceived, icmpTransients,
                oversized, escalatedToTcp, connectionAttempts, connectionsThrottled, invalidFrames,
                connectionsDroppedForGarbage, 0L, 0L, 0L, 0L, 0L, 0L, lastInboundDatagramAtMillis,
                validatedRemote);
    }
}
