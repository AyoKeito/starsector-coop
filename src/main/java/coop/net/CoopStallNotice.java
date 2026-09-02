package coop.net;

/**
 * The static hook that lets a {@code ModPlugin} save callback tell the peer "I am about to stop
 * pumping" (Phase 20 red-team B4).
 *
 * <h2>The hole this closes</h2>
 * The Phase 20.2 link-death rule declares a peer dead after sustained silence, vetoed by three
 * exemptions ({@link CoopLinkQuality}). One of them is "a coordinated save happened recently", and it
 * is stamped by {@code SAVE_CHECKPOINT} — which only the <b>host</b> ever sends, because that message
 * does not merely announce a save, it <em>orders</em> the guest to take one. A guest's own manual
 * save is therefore invisible: the guest's process stops pumping for as long as a late-game sector
 * save takes, the host measures the silence against a 15 s threshold with no exemption in sight, and
 * declares the link dead on a partner who is sitting at a save dialog.
 *
 * <p>{@code STALL_NOTICE} is the missing half: a bare "expect silence" both roles send, with none of
 * {@code SAVE_CHECKPOINT}'s semantics. Nothing about the coordinated-save protocol changes.
 *
 * <h2>Why it is static</h2>
 * Same reason {@link coop.save.CoopSaveCheckpoint} is: the trigger is
 * {@code CoopModPlugin.beforeGameSave()}, a callback with no handle on the campaign pump. The pump
 * registers itself here on construction, so the newest game load wins, and the callback routes to it.
 */
public final class CoopStallNotice {

    /** Reason string for the stall a save causes; the only producer today. */
    public static final String REASON_LOCAL_SAVE = "local save";

    /**
     * What the sender tells the peer to expect. Advisory only — the receiver stamps its own fixed
     * exemption window rather than trusting a number off the wire — but it is what a log reader needs
     * to tell "the partner is saving" from "the partner crashed mid-save".
     */
    public static final long SAVE_EXPECTED_MILLIS = 15_000L;

    /** How the pump puts a notice on the wire. Role and session gating live on the pump side. */
    public interface Sender {
        void sendStallNotice(String reason, long expectedMillis);
    }

    private static volatile Sender active;

    private CoopStallNotice() {
    }

    /** The pump registers itself on construction; a null argument unregisters. */
    public static void setActive(Sender sender) {
        active = sender;
    }

    /**
     * Announces an imminent local stall. Safe to call with no session, no pump and no network: the
     * whole point is that it sits on a {@code ModPlugin} callback that also fires in solo play.
     */
    public static void notifyLocalStall(String reason, long expectedMillis) {
        Sender sender = active;
        if (sender == null) {
            return;
        }
        try {
            sender.sendStallNotice(reason, expectedMillis);
        } catch (RuntimeException | LinkageError ex) {
            coop.util.CoopLog.warn(CoopStallNotice.class, "Coop could not announce a local stall", ex);
        }
    }
}
