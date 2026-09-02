package coop.ui;

import java.util.function.IntSupplier;

/**
 * The guest's face of the Phase 20.2 grace window. The transport is already retrying the socket every
 * 500 ms underneath; this is what tells the player that, rather than leaving them staring at a frozen
 * partner mirror wondering whether the game has hung.
 *
 * <p>The one option gives up: it runs the ordinary session teardown immediately instead of waiting
 * out the window. Same asymmetry as the host dialog — retrying is the default and needs no button.
 */
public final class CoopReconnectGuestDialog extends CoopReconnectDialogPlugin {

    private final String hostName;

    /**
     * @param hostName         the host's display name; blank falls back to "the host"
     * @param remainingSeconds live countdown from the reconnect coordinator
     * @param onGiveUp         what the "Give up" option runs
     */
    public CoopReconnectGuestDialog(String hostName, IntSupplier remainingSeconds, Runnable onGiveUp) {
        super(remainingSeconds, onGiveUp);
        this.hostName = hostName == null || hostName.trim().isEmpty() ? "the host" : hostName.trim();
    }

    @Override
    String headline() {
        return "Connection to " + hostName + " lost. Reconnecting - your campaign is held until the"
                + " link comes back, and the session picks up exactly where it stopped.";
    }

    @Override
    String countdownText(int seconds) {
        return seconds <= 0
                ? "Out of time; ending the session."
                : "Retrying for " + seconds + " more second" + (seconds == 1 ? "" : "s") + "...";
    }

    @Override
    String endOptionText() {
        return "Give up";
    }

    @Override
    String endOptionTooltip() {
        return "Stops retrying and ends the co-op session now. Your campaign stays loaded, but"
                + " rejoining would mean a new session.";
    }
}
