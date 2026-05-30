package coop.input;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.time.CoopSharedPauseCoordinator;

import java.util.List;
import java.util.Objects;

/**
 * Phase 11 host-side pause capture. The host is authoritative over the shared clock, so it must not
 * let vanilla toggle its own clock directly off the pause key: when another intent (guest pause, or
 * Phase 14 combat) is forcing the world paused and the host taps pause, a raw vanilla toggle would
 * unpause the clock for a single frame before {@code CoopNetPump.syncHostSharedPause} re-applies the
 * OR-of-intents — a visible flicker.
 *
 * <p>This listener intercepts the {@code GENERAL_PAUSE} press edge, flips {@code hostPauseIntent} on
 * the shared {@link CoopSharedPauseCoordinator}, and consumes the event so vanilla never flips the
 * clock. The host clock is then driven solely by {@code setPaused(effectivePaused)}. Vanilla
 * <i>auto</i>-pause (combat warnings, messages, etc.) does not come through this control, so it still
 * reaches the clock and is picked up as host intent by the pump's edge detection.
 *
 * <p>Unlike {@link CoopCampaignInputBlocker} (guest) this does not block world input or fast-forward;
 * the host plays normally apart from the pause-key capture.
 */
public final class CoopHostPauseInputListener implements CampaignInputListener {
    private static final int INPUT_PRIORITY = Integer.MAX_VALUE;
    private static final String GENERAL_PAUSE = "GENERAL_PAUSE";

    private final CoopSharedPauseCoordinator pauseCoordinator;

    public CoopHostPauseInputListener(CoopSharedPauseCoordinator pauseCoordinator) {
        this.pauseCoordinator = Objects.requireNonNull(pauseCoordinator, "pauseCoordinator");
    }

    @Override
    public int getListenerInputPriority() {
        return INPUT_PRIORITY;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !isPauseDownEdge(event)) {
                continue;
            }
            pauseCoordinator.onHostPauseKey();
            event.consume();
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    private boolean isPauseDownEdge(InputEventAPI event) {
        try {
            return event.isControlDownEvent(GENERAL_PAUSE);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
