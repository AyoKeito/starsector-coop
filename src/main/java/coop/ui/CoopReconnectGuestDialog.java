package coop.ui;

import java.util.function.IntSupplier;

/**
 * The guest's face of the Phase 20.2 grace window. The transport is already retrying the socket every
 * 500 ms underneath; this is what tells the player that, rather than leaving them staring at a frozen
 * partner mirror wondering whether the game has hung.
 *
 * <p>Giving up runs the ordinary session teardown immediately instead of waiting out the window;
 * waiting more buys another five minutes of retrying (Phase 20 live QA, finding F1). The two sides
 * extend independently — a guest whose host already gave up simply finds the socket refused and falls
 * back to the ordinary lobby rejoin, which is the supported path anyway.
 */
public final class CoopReconnectGuestDialog extends CoopReconnectDialogPlugin {

    private final String hostName;

    /**
     * @param hostName         the host's display name; blank falls back to "the host"
     * @param remainingSeconds live countdown from the reconnect coordinator
     * @param onWaitMore       what the "Wait 5 more minutes" option runs
     * @param onGiveUp         what the "Give up" option runs
     */
    public CoopReconnectGuestDialog(String hostName, IntSupplier remainingSeconds,
                                    Runnable onWaitMore, Runnable onGiveUp) {
        super(remainingSeconds, onWaitMore, onGiveUp);
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
    String waitMoreOptionTooltip() {
        return "Keeps retrying for five more minutes. Press it as often as you like - your campaign"
                + " stays held meanwhile, so nothing drifts while you wait.";
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
