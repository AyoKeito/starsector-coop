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
 * <p><b>"Discarded" means undeliverable, which is not the same as "never written" (0.1.1).</b> This
 * used to fire only for messages still sitting in a peer's outbound queue, on the reasoning that
 * "TCP's own retransmission owns it" past that point. That reasoning was wrong, and a live session
 * proved it four times: TCP guarantees delivery <em>on one socket</em>, and this transport replaces
 * the socket on every reconnect. A frame written whole into a connection that died before the peer
 * read it is gone, and the resume handshake carried no acknowledgement information, so nothing
 * resent it — two credit gifts died exactly that way.
 *
 * <p>What owns a written message now is the acknowledgement layer: {@code CoopPeerLink} keeps every
 * reliable one-shot it has written until the peer's {@code RELIABLE_ACK} arrives, replays the
 * remainder on a resume, and reports whatever is still unacknowledged when the session ends for good
 * ({@code session-end}) or the transport shuts down ({@code shutdown}). So the four causes below are
 * now three queue sites plus that history.
 *
 * <p><b>The accepted risk, stated plainly.</b> A grant the receiver applied whose ack died with the
 * socket, and whose session then ended without a resume, is refunded even though the partner banked
 * it. That reverses the old "rather a lost gift than a spurious refund" choice, because the losses
 * turned out to be common and the double-credit is not: the sender's replay carries the same
 * {@code ledgerId}, and the receiver's ledger dedup makes a redelivery pay nothing. The remaining
 * window is one unacknowledged frame per grant per hard session end.
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
     * @param cause   which site gave up on it, for the log line: {@code queue-cap}, {@code attach},
     *                {@code unacked-cap}, {@code session-end} or {@code shutdown}
     */
    void onOutboundDiscarded(CoopMessages.Message message, String cause);
}
