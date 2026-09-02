package coop.ui;

import java.util.function.IntSupplier;

/**
 * The host's face of the Phase 20.2 grace window: the guest's link died, the world is held, and here
 * is how long the host will wait before calling it.
 *
 * <p>Two options, both about the clock the host is watching. Ending is for a partner the host knows
 * is not coming back — a guest who said in voice chat that their router died and they are done for
 * the night — without sitting through the full window. Waiting more is the Phase 20 live-QA answer to
 * the opposite case (finding F1): a 60 s window against a 95 s outage expired with the guest four
 * seconds from being back, and the dialog offered nothing but the destructive option.
 */
public final class CoopReconnectHostDialog extends CoopReconnectDialogPlugin {

    private final String guestName;

    /**
     * @param guestName        the disconnected guest's display name; blank falls back to "The guest"
     * @param remainingSeconds live countdown from the reconnect coordinator
     * @param onWaitMore       what the "Wait 5 more minutes" option runs
     * @param onEndSession     what the "End session now" option runs
     */
    public CoopReconnectHostDialog(String guestName, IntSupplier remainingSeconds,
                                   Runnable onWaitMore, Runnable onEndSession) {
        super(remainingSeconds, onWaitMore, onEndSession);
        this.guestName = guestName == null || guestName.trim().isEmpty() ? "The guest" : guestName.trim();
    }

    @Override
    String headline() {
        return guestName + " disconnected. The game is held while they reconnect - nothing in the"
                + " sector advances until they are back or you end the session.";
    }

    @Override
    String countdownText(int seconds) {
        return seconds <= 0
                ? "Waiting time is up; ending the session."
                : "Holding for " + seconds + " more second" + (seconds == 1 ? "" : "s") + "...";
    }

    @Override
    String waitMoreOptionTooltip() {
        return "Adds five more minutes to the hold. Press it as often as you like - the sector stays"
                + " frozen while you wait, so nothing is lost by waiting longer.";
    }

    @Override
    String endOptionText() {
        return "End session now";
    }

    @Override
    String endOptionTooltip() {
        return "Stops waiting immediately and ends the co-op session. Your campaign continues;"
                + " the guest would have to start a new session to rejoin.";
    }
}
