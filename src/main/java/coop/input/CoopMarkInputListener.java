package coop.input;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import coop.mark.CoopMarkKey;
import coop.util.CoopLog;

import java.util.List;
import java.util.Objects;

/**
 * The log-marker hotkey, installed on both roles for the length of a coop session.
 *
 * <p>Plain key detection rather than a named control: the marker key is a coop setting
 * ({@code coop.markKey}), not one of the engine's bindable controls, so there is no control name to
 * ask {@code isControlDownEvent} about. {@link InputEventAPI#getEventValue()} on a key-down edge is
 * the LWJGL key code, which is exactly what {@link CoopMarkKey} resolved the configured name to.
 *
 * <p><b>Down edge only, and consumed.</b> Vanilla must never see the key - F11 is unbound in a
 * stock install, but a player may have bound it to something, and a marker that also toggles a
 * screen would be a nasty surprise at exactly the wrong moment. {@code isRepeat()} is filtered so
 * holding the key writes one marker rather than sixty a second.
 *
 * <p><b>The guest's blocker is the other half of this.</b> {@link CoopCampaignInputBlocker} consumes
 * all keyboard input while the partner holds an interaction, and listeners at equal priority have no
 * defined order, so "install this one first" is not a guarantee. The blocker therefore asks
 * {@link #passThroughKeyCode()} and leaves this key alone - see
 * {@code CoopCampaignInputBlocker.isWorldInput}. That makes the pass-through a fact about the
 * blocker rather than a race between two listeners.
 */
public final class CoopMarkInputListener implements CampaignInputListener {

    private static final int INPUT_PRIORITY = Integer.MAX_VALUE;

    /**
     * The key code the installed listener is watching, or {@link CoopMarkKey#KEY_NONE} when no
     * marker listener is installed. Static because the guest input blocker has to know it and the
     * two listeners are installed independently by {@code CoopTimeLock}.
     */
    private static volatile int passThroughKeyCode = CoopMarkKey.KEY_NONE;

    private final CoopMarkKey key;
    private final Runnable onMark;

    public CoopMarkInputListener(CoopMarkKey key, Runnable onMark) {
        this.key = Objects.requireNonNull(key, "key");
        this.onMark = Objects.requireNonNull(onMark, "onMark");
    }

    /** The key the guest input blocker must not eat; {@link CoopMarkKey#KEY_NONE} when none. */
    public static int passThroughKeyCode() {
        return passThroughKeyCode;
    }

    /** Called by {@code CoopTimeLock} on install/remove; the blocker reads the result. */
    public static void setPassThroughKeyCode(int code) {
        passThroughKeyCode = code;
    }

    /** The key this listener is watching. */
    public CoopMarkKey key() {
        return key;
    }

    @Override
    public int getListenerInputPriority() {
        return INPUT_PRIORITY;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (events == null || key.code() == CoopMarkKey.KEY_NONE) {
            return;
        }
        for (InputEventAPI event : events) {
            if (event == null || event.isConsumed() || !isMarkDownEdge(event)) {
                continue;
            }
            event.consume();
            try {
                onMark.run();
            } catch (RuntimeException | LinkageError ex) {
                // A marker that throws must not take the campaign frame down with it.
                CoopLog.warn(CoopMarkInputListener.class, "Coop log marker failed", ex);
            }
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    private boolean isMarkDownEdge(InputEventAPI event) {
        try {
            return event.isKeyDownEvent() && !event.isRepeat() && event.getEventValue() == key.code();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }
}
