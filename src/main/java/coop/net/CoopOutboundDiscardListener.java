package coop.net;

/**
 * Told about every queued outbound message the transport throws away <em>before</em> it reached a
 * socket (Phase 32 addition B, credit red-team P1-1/P1-2/P1-4).
 *
 * <p><b>Why this exists.</b> Almost everything on the wire is a snapshot or an event whose producer
 * sends another one, so a discarded copy costs nothing and the transport has always dropped queued
 * messages silently. {@code CREDITS_GRANT} is the exception: the sender debited its own wallet the
 * instant it handed the grant over, and there is no producer that will ever send it again. A
 * discarded grant is money that stopped existing. This callback is what turns that into a refund.
 *
 * <p><b>"Discarded" means never written.</b> The listener fires only for messages still sitting in a
 * peer's outbound queue. A message already handed to the OS socket — even one whose frame was only
 * partly accepted by the kernel, since {@code CoopPeerLink.detach} requeues that one — is considered
 * delivered and is never reported here. TCP's own retransmission owns it from that point; the coop
 * transport cannot know whether the far side applied it, and a refund on a grant the peer credited
 * would mint money out of nothing. The asymmetry is deliberate: a lost grant that this hook misses
 * costs the sender its gift, whereas a spurious refund costs the pair a duplicate of it.
 *
 * <p>Invoked with the service's lifecycle lock held, on the campaign thread. Implementations must be
 * short and must not call back into {@link CoopNetService}.
 */
@FunctionalInterface
public interface CoopOutboundDiscardListener {

    /** A listener that ignores everything; the default, so no site has to null-check. */
    CoopOutboundDiscardListener NONE = (message, cause) -> {
    };

    /**
     * One queued message that will never be written.
     *
     * @param message the message as it sat in the queue, never null
     * @param cause   which site dropped it, for the log line: {@code queue-cap}, {@code attach},
     *                {@code session-end} or {@code shutdown}
     */
    void onOutboundDiscarded(CoopMessages.Message message, String cause);
}
