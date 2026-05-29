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
    void consumesSupportedCampaignPauseAndFastForwardControlsBeforeCoreInput() {
        RecordingInputEvent pause = new RecordingInputEvent(
                "GENERAL_PAUSE", true, false, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));
        RecordingInputEvent fastForward = new RecordingInputEvent(
                "FAST_FORWARD", false, true, false, Set.of("GENERAL_PAUSE", "FAST_FORWARD"));

        new CoopCampaignInputBlocker().processCampaignInputPreCore(List.of(pause.proxy(), fastForward.proxy()));

        assertTrue(pause.consumed);
        assertTrue(fastForward.consumed);
    }

    @Test
    void ignoresUnrelatedAndAlreadyConsumedInput() {
        RecordingInputEvent unrelated = new RecordingInputEvent("INTERACT", true, false, false, Set.of("FAST_FORWARD"));
        RecordingInputEvent alreadyConsumed = new RecordingInputEvent("PAUSE", true, false, true, Set.of());

        new CoopCampaignInputBlocker().processCampaignInputPreCore(
                List.of(unrelated.proxy(), alreadyConsumed.proxy()));

        assertFalse(unrelated.consumed);
        assertTrue(alreadyConsumed.consumed);
    }

    @Test
    void ignoresUnsupportedControlEnumNamesWithoutCrashing() {
        RecordingInputEvent mouseMove = new RecordingInputEvent("MOUSE_MOVE", false, false, false, Set.of());
        RecordingInputEvent pause = new RecordingInputEvent("PAUSE", true, false, false, Set.of("FAST_FORWARD"));

        new CoopCampaignInputBlocker().processCampaignInputPreCore(List.of(mouseMove.proxy(), pause.proxy()));

        assertFalse(mouseMove.consumed);
        assertFalse(pause.consumed);
    }

    private static final class RecordingInputEvent {
        private final String controlName;
        private final boolean activated;
        private final boolean down;
        private final Set<String> supportedControls;
        private boolean consumed;

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed,
                                    Set<String> supportedControls) {
            this.controlName = controlName;
            this.activated = activated;
            this.down = down;
            this.consumed = consumed;
            this.supportedControls = supportedControls;
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

        private void requireSupportedControl(String control) {
            if (!supportedControls.contains(control)) {
                throw new IllegalArgumentException("No enum constant Controls." + control);
            }
        }
    }
}
