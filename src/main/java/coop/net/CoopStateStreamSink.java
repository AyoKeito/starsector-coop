package coop.net;

/**
 * Where a composed state datagram goes (Phase 20.1 M2 UDP-blocked fallback).
 *
 * <p>Before this existed, both state-stream producers — {@link CoopNetPump}'s {@code FLEET_SNAPSHOT}
 * and {@link coop.fleet.CoopNpcFleetReplicator}'s {@code NPC_FLEET_MOTION} — called
 * {@link CoopNetService#sendDatagram(String)} directly, which hard-wired the stream to UDP. The
 * fallback needs one place that decides "UDP, or wrapped in a {@code STATE_DATAGRAM} TCP message",
 * and that decision belongs to the pump (it owns the link supervision that makes it). Producers take
 * this instead of the transport, and stay ignorant of which wire they are on.
 */
public interface CoopStateStreamSink {

    /** Hands one fully composed datagram string to whichever transport is currently in use. */
    void send(String datagram);
}
