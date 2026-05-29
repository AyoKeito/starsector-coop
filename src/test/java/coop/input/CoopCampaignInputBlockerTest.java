package coop.input;

import com.fs.starfarer.api.input.InputEventAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopCampaignInputBlockerTest {
    @Test
    void consumesCampaignPauseAndFastForwardControlsBeforeCoreInput() {
        RecordingInputEvent pause = new RecordingInputEvent("PAUSE", true, false, false);
        RecordingInputEvent fastForward = new RecordingInputEvent("FAST_FORWARD", false, true, false);

        new CoopCampaignInputBlocker().processCampaignInputPreCore(List.of(pause.proxy(), fastForward.proxy()));

        assertTrue(pause.consumed);
        assertTrue(fastForward.consumed);
    }

    @Test
    void ignoresUnrelatedAndAlreadyConsumedInput() {
        RecordingInputEvent unrelated = new RecordingInputEvent("INTERACT", true, false, false);
        RecordingInputEvent alreadyConsumed = new RecordingInputEvent("PAUSE", true, false, true);

        new CoopCampaignInputBlocker().processCampaignInputPreCore(
                List.of(unrelated.proxy(), alreadyConsumed.proxy()));

        assertFalse(unrelated.consumed);
        assertTrue(alreadyConsumed.consumed);
    }

    @Test
    void doesNotProbeUnsupportedControlEnumAliases() {
        RecordingInputEvent mouseMove = new RecordingInputEvent("MOUSE_MOVE", false, false, false);

        new CoopCampaignInputBlocker().processCampaignInputPreCore(List.of(mouseMove.proxy()));

        assertFalse(mouseMove.consumed);
    }

    private static final class RecordingInputEvent {
        private static final Set<String> ENGINE_CONTROL_ENUM_NAMES = Set.of("PAUSE", "FAST_FORWARD");

        private final String controlName;
        private final boolean activated;
        private final boolean down;
        private boolean consumed;

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed) {
            this.controlName = controlName;
            this.activated = activated;
            this.down = down;
            this.consumed = consumed;
        }

        private InputEventAPI proxy() {
            return (InputEventAPI) Proxy.newProxyInstance(
                    InputEventAPI.class.getClassLoader(),
                    new Class<?>[]{InputEventAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isConsumed" -> {
                                return consumed;
                            }
                            case "consume" -> {
                                consumed = true;
                                return null;
                            }
                            case "isControlActivated" -> {
                                requireSupportedControl((String) args[0]);
                                return activated && controlName.equals(args[0]);
                            }
                            case "isControlDownEvent" -> {
                                requireSupportedControl((String) args[0]);
                                return down && controlName.equals(args[0]);
                            }
                            case "isControlUpEvent" -> {
                                requireSupportedControl((String) args[0]);
                                return false;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private static void requireSupportedControl(String control) {
            if (!ENGINE_CONTROL_ENUM_NAMES.contains(control)) {
                throw new IllegalArgumentException("No enum constant Controls." + control);
            }
        }
    }
}
