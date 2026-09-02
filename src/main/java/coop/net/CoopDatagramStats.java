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
        long lastInboundDatagramAtMillis,
        String validatedRemote) {

    public CoopDatagramStats {
        validatedRemote = validatedRemote == null ? "" : validatedRemote;
    }
}
