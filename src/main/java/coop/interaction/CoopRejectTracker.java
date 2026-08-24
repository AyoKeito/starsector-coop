package coop.interaction;

/**
 * Phase 18 bookkeeping for a local interaction claim the host rejected.
 *
 * <p>The guest opens its dialog <em>optimistically</em>, before the {@code INTERACTION_CLAIM}
 * round-trip completes. At WAN RTT that leaves a window (host frame + TCP one-way + guest frame)
 * where both players hold a dialog, and the market model genuinely breaks there: a unique hull can
 * be bought twice, and a guest market-open re-rolls the host's open shop underneath the host's UI.
 * So when {@code INTERACTION_REJECT} arrives, the loser's dialog has to be taken away from it.
 *
 * <p>This class is the pure half of that: it remembers which entity lost the race and decides, per
 * frame, whether the pump should dismiss the dialog and whether the "already in use" message has
 * already been shown. It holds no engine references; {@code CoopNetPump} does the dismissing.
 *
 * <p>It also closes the reject re-claim loop. The old reject handler dropped the pump's tracked
 * entity id while the dialog was still open, so the per-frame detector saw "a dialog is open that I
 * am not tracking" and re-claimed — a claim/reject ping-pong at up to 60 msg/s over TCP plus one
 * warn per frame. While an entity is {@link #isRejected(String) rejected} the pump neither
 * re-claims it nor re-logs it.
 *
 * <p><b>What counts as "the dialog actually closed".</b> The authoritative signal is
 * {@link #onFrame(String)}: the frame on which the local player no longer has a dialog open on the
 * rejected entity. {@link #onDialogClosed(String)}, fed by vanilla's {@code reportPlayerClosedMarket},
 * is a secondary signal and is deliberately ignored once a dismissal has been issued — vanilla
 * reports a market close when the trade screen is left, which can happen with the interaction
 * dialog still up, and that must not cancel a forced close in flight. A session reset (disconnect,
 * session end) drops the whole thing through {@link #clear()}.
 */
public final class CoopRejectTracker {

    /** What the pump should do with the currently open dialog this frame. */
    public enum Action {
        /** Nothing to do: no rejection outstanding, or the rejected dialog is already gone. */
        NONE,
        /** Re-issue the dismissal; the message was shown on an earlier frame. */
        DISMISS,
        /** First dismissal for this rejection: dismiss and tell the player why. */
        DISMISS_AND_NOTIFY
    }

    private String entityId;
    private boolean notified;
    private int dismissAttempts;

    /**
     * Record an {@code INTERACTION_REJECT} for the local player's claim.
     *
     * @return true when this is a newly tracked rejection, i.e. the caller should log it. Repeated
     *         rejects for the same entity return false, which is what keeps the log quiet if the
     *         host answers a burst of in-flight claims.
     */
    public boolean onRejected(String entityId) {
        String normalized = normalize(entityId);
        if (normalized == null) {
            return false;
        }
        if (normalized.equals(this.entityId)) {
            return false;
        }
        this.entityId = normalized;
        this.notified = false;
        this.dismissAttempts = 0;
        return true;
    }

    /** True while {@code entityId} is the rejected entity whose dialog has not closed yet. */
    public boolean isRejected(String entityId) {
        String normalized = normalize(entityId);
        return normalized != null && normalized.equals(this.entityId);
    }

    /** The entity whose claim was rejected and whose dialog is still being closed, or null. */
    public String rejectedEntityId() {
        return entityId;
    }

    /** How many dismissals have been issued for the current rejection (0 when none is tracked). */
    public int dismissAttempts() {
        return dismissAttempts;
    }

    /**
     * Per-frame decision.
     *
     * @param openDialogEntityId the entity the local player currently has an interaction dialog
     *                           open on, or null when no dialog is open.
     */
    public Action onFrame(String openDialogEntityId) {
        if (entityId == null) {
            return Action.NONE;
        }
        if (!entityId.equals(normalize(openDialogEntityId))) {
            // The rejected dialog is gone (dismissed, or closed by the player first): stop tracking
            // so the entity becomes claimable again on the next open.
            clear();
            return Action.NONE;
        }
        dismissAttempts++;
        if (notified) {
            return Action.DISMISS;
        }
        notified = true;
        return Action.DISMISS_AND_NOTIFY;
    }

    /**
     * Secondary close signal (market close / release). Ignored once a dismissal has been issued:
     * see the class comment — a market close can fire with the interaction dialog still open.
     *
     * @return true when it cleared the tracking.
     */
    public boolean onDialogClosed(String closedEntityId) {
        if (entityId == null || dismissAttempts > 0) {
            return false;
        }
        String normalized = normalize(closedEntityId);
        if (normalized == null || !normalized.equals(entityId)) {
            return false;
        }
        clear();
        return true;
    }

    /**
     * Drop any tracked rejection (session end, disconnect, gate reset).
     *
     * @return true when something was actually tracked, so the caller can log the reset once.
     */
    public boolean clear() {
        boolean tracked = entityId != null;
        entityId = null;
        notified = false;
        dismissAttempts = 0;
        return tracked;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
