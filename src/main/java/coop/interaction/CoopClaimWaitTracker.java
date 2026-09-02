package coop.interaction;

/**
 * Phase 20 M6: the "waiting for the host" affordance for an unanswered {@code INTERACTION_CLAIM}.
 *
 * <p><b>What problem this solves, and what it deliberately does not.</b> The guest opens its dialog
 * optimistically and sends the claim in the same frame; the host's {@code INTERACTION_ACCEPT} or
 * {@code INTERACTION_REJECT} comes back a round trip later. On loopback that is invisible. On a WAN
 * link with a retransmitted frame it is half a second or more, and if the host is mid-battle or the
 * TCP leg is retrying it can be several seconds — during which the guest is looking at a dialog that
 * may be about to be taken away from it with no explanation. This class decides <em>when to say so</em>.
 * It changes nothing about the optimistic-open model: the dialog still opens immediately, the claim
 * is still arbitrated by the host, and a late answer is still honoured exactly as before.
 *
 * <p><b>Why the threshold is {@code max(1000, 4 x p95Rtt)}.</b> Two legs is the round trip a healthy
 * claim needs; four is the round trip after one TCP retransmit, which is the ordinary bad case on a
 * lossy link rather than a fault. Warning earlier than that would fire on every claim over a mediocre
 * connection and train the player to ignore it. The 1 s floor keeps a fast link (or an unmeasured
 * one, where p95 reads 0) from posting a notice a human would experience as instantaneous anyway.
 *
 * <p><b>Once per claim, and nothing on the late answer.</b> The notice is posted at most once per
 * claim; when the answer finally arrives the tracker simply forgets the claim. There is no
 * "connected again" follow-up, because the answer itself is the feedback: an accept leaves the dialog
 * open (nothing to say) and a reject already closes the dialog with its own message.
 *
 * <p>Pure state, no engine references — the pump owns the feed post and the intel event, the same
 * split as {@link CoopRejectTracker}.
 */
public final class CoopClaimWaitTracker {

    /** Floor on the wait before the player is told anything. */
    public static final long MIN_WAIT_MILLIS = 1000L;

    /** Round trips of headroom granted before a claim counts as slow (one retransmit's worth). */
    public static final int RTT_MULTIPLIER = 4;

    private String entityId;
    private String entityName;
    private long sentAtMillis;
    private boolean warned;

    /**
     * The guest just sent a claim. Replaces any previous outstanding claim: only one dialog can be
     * open at a time, so an unanswered older claim belongs to a dialog that is already gone.
     */
    public void onClaimSent(String entityId, String entityName, long nowMillis) {
        if (entityId == null) {
            return;
        }
        this.entityId = entityId;
        this.entityName = entityName == null || entityName.isEmpty() ? "this location" : entityName;
        this.sentAtMillis = nowMillis;
        this.warned = false;
    }

    /**
     * An {@code INTERACTION_ACCEPT}, {@code INTERACTION_REJECT} or local dialog close resolved the
     * wait. Matching on the entity id and not just "any answer" matters because the host also
     * broadcasts accepts for the <em>other</em> player's claims, which say nothing about ours.
     *
     * @return true when this call actually cleared an outstanding claim.
     */
    public boolean onAnswered(String entityId) {
        if (this.entityId == null || entityId == null || !this.entityId.equals(entityId)) {
            return false;
        }
        clear();
        return true;
    }

    /** The wait threshold for a link with this measured p95 RTT ({@code <= 0} = unmeasured). */
    public static long waitThresholdMillis(int p95RttMillis) {
        return Math.max(MIN_WAIT_MILLIS, (long) RTT_MULTIPLIER * Math.max(0, p95RttMillis));
    }

    /**
     * Per-frame poll.
     *
     * @return the notice to show, exactly once per claim, or null when there is nothing to say
     *         (no outstanding claim, still inside the threshold, or already warned).
     */
    public String pollWarning(long nowMillis, int p95RttMillis) {
        if (entityId == null || warned) {
            return null;
        }
        if (nowMillis - sentAtMillis < waitThresholdMillis(p95RttMillis)) {
            return null;
        }
        warned = true;
        return "Waiting for the host to confirm the interaction...";
    }

    /** The entity whose claim is outstanding, or null. Diagnostics and tests. */
    public String pendingEntityId() {
        return entityId;
    }

    /** The name carried by the outstanding claim, for a log line that names the place. */
    public String pendingEntityName() {
        return entityName;
    }

    /** True once the notice for the current claim has been handed out. */
    public boolean warned() {
        return warned;
    }

    /** Session reset / disconnect: an unanswered claim belongs to a session that no longer exists. */
    public void clear() {
        entityId = null;
        entityName = null;
        sentAtMillis = 0L;
        warned = false;
    }
}
