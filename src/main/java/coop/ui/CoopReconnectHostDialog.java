package coop.ui;

import java.util.function.IntSupplier;

/**
 * The host's face of the Phase 20.2 grace window: the guest's link died, the world is held, and here
 * is how long the host will wait before calling it.
 *
 * <p>Only one option, and it is the destructive one. There is deliberately no "keep waiting" button:
 * waiting is the default and the countdown already shows it. What the host needs is a way to stop
 * waiting for a partner they know is not coming back — a guest who said in voice chat that their
 * router died and they are done for the night — without sitting through the full window.
 */
public final class CoopReconnectHostDialog extends CoopReconnectDialogPlugin {

    private final String guestName;

    /**
     * @param guestName        the disconnected guest's display name; blank falls back to "The guest"
     * @param remainingSeconds live countdown from the reconnect coordinator
     * @param onEndSession     what the "End session now" option runs
     */
    public CoopReconnectHostDialog(String guestName, IntSupplier remainingSeconds, Runnable onEndSession) {
        super(remainingSeconds, onEndSession);
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
    String endOptionText() {
        return "End session now";
    }

    @Override
    String endOptionTooltip() {
        return "Stops waiting immediately and ends the co-op session. Your campaign continues;"
                + " the guest would have to start a new session to rejoin.";
    }
}
