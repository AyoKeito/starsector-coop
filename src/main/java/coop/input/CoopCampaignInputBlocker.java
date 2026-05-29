package coop.input;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;
import java.util.Set;

public class CoopCampaignInputBlocker implements CampaignInputListener {
    private static final int INPUT_PRIORITY = Integer.MAX_VALUE;
    private static final Set<String> LOCKED_CONTROLS = Set.of(
            "PAUSE",
            "CAMPAIGN_PAUSE",
            "TOGGLE_PAUSE",
            "FAST_FORWARD");

    @Override
    public int getListenerInputPriority() {
        return INPUT_PRIORITY;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !isLockedControl(event)) {
                continue;
            }
            event.consume();
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    private boolean isLockedControl(InputEventAPI event) {
        for (String control : LOCKED_CONTROLS) {
            if (event.isControlActivated(control)
                    || event.isControlDownEvent(control)
                    || event.isControlUpEvent(control)) {
                return true;
            }
        }
        return false;
    }
}
