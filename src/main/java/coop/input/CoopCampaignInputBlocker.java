package coop.input;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.time.CoopSharedPauseCoordinator;

import java.util.List;
import java.util.Set;

public class CoopCampaignInputBlocker implements CampaignInputListener {
    private static final int INPUT_PRIORITY = Integer.MAX_VALUE;
    // Control enum-constant names passed to InputEventAPI.isControlActivated/Down/Up(String).
    // The engine resolves these via Enum.valueOf on the obfuscated control enum
    // (com.fs.starfarer.title.<obf>$oo in starfarer_obf.jar, Starsector 0.98a-RC8); an
    // unknown name throws IllegalArgumentException ("No enum constant ...<name>"). The campaign
    // pause control is GENERAL_PAUSE, not PAUSE - passing "PAUSE" crashed the client.
    private static final String GENERAL_PAUSE = "GENERAL_PAUSE";
    private static final Set<String> LOCKED_CONTROLS = Set.of(
            GENERAL_PAUSE,
            "FAST_FORWARD");

    // Phase 11 shared pause: instead of unconditionally swallowing GENERAL_PAUSE, a guest pause-key
    // press flips the shared-pause intent here; the host folds it into effectivePaused and the
    // guest's clock follows the host snapshot (the guest never setPaused locally). The event is
    // still consumed so vanilla never pauses the guest's clock directly. Null in legacy/unit paths.
    private final CoopSharedPauseCoordinator pauseCoordinator;

    public CoopCampaignInputBlocker() {
        this(null);
    }

    public CoopCampaignInputBlocker(CoopSharedPauseCoordinator pauseCoordinator) {
        this.pauseCoordinator = pauseCoordinator;
    }

    // Phase 9 interaction gate: while the remote player holds an interaction claim, the local
    // player is locked out of the world (COOP_MP_DESIGN.md 8.5). This is layered on top of the
    // always-on pause/fast-forward lock above. Toggled per-frame by CoopNetPump.
    private volatile boolean interactionBlocked;
    private volatile String blockingEntityName = "";

    public void setInteractionBlocked(boolean blocked, String entityName) {
        this.interactionBlocked = blocked;
        this.blockingEntityName = entityName == null ? "" : entityName;
    }

    public boolean isInteractionBlocked() {
        return interactionBlocked;
    }

    public String blockingEntityName() {
        return blockingEntityName;
    }

    @Override
    public int getListenerInputPriority() {
        return INPUT_PRIORITY;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !shouldConsume(event)) {
                continue;
            }
            // On the press edge of GENERAL_PAUSE, record a guest pause-key press; the pump forwards it
            // to the host next frame (resolved against the observed pause state). Consume on all edges
            // so vanilla never pauses the guest's clock locally.
            if (pauseCoordinator != null && matchesControlDown(event, GENERAL_PAUSE)) {
                pauseCoordinator.recordGuestPauseKeyPress();
            }
            event.consume();
        }
    }

    private boolean shouldConsume(InputEventAPI event) {
        if (isLockedControl(event)) {
            return true;
        }
        return interactionBlocked && isWorldInput(event);
    }

    private boolean isWorldInput(InputEventAPI event) {
        // Camera movement stays free so a locked-out player can still look around the campaign map:
        // edge-scroll panning (mouse move) and zoom (scroll wheel) are allowed through. Everything
        // else that drives the world - mouse clicks (move/interact orders) and key presses - is
        // consumed while the remote player holds the interaction. Keyboard camera-pan keys are
        // consumed too in v1; mouse edge-pan and zoom remain the available camera controls.
        if (event.isMouseMoveEvent() || event.isMouseScrollEvent()) {
            return false;
        }
        return event.isMouseEvent() || event.isKeyboardEvent();
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    private boolean isLockedControl(InputEventAPI event) {
        for (String control : LOCKED_CONTROLS) {
            if (matchesControl(event, control)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesControl(InputEventAPI event, String control) {
        try {
            return event.isControlActivated(control)
                    || event.isControlDownEvent(control)
                    || event.isControlUpEvent(control);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean matchesControlDown(InputEventAPI event, String control) {
        try {
            return event.isControlDownEvent(control);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
