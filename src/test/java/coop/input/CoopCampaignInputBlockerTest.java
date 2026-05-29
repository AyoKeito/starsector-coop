package coop.input;

import com.fs.starfarer.api.input.InputEventAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

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

    private static final class RecordingInputEvent {
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
                                return activated && controlName.equals(args[0]);
                            }
                            case "isControlDownEvent" -> {
                                return down && controlName.equals(args[0]);
                            }
                            case "isControlUpEvent" -> {
                                return false;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
