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

    @Test
    void interactionBlockConsumesWorldInputButAllowsMouseCameraInput() {
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker();
        blocker.setInteractionBlocked(true, "Jangala");
        RecordingInputEvent mouseClick = RecordingInputEvent.mouseClick();
        RecordingInputEvent keyboard = RecordingInputEvent.keyboard();
        RecordingInputEvent mouseMove = RecordingInputEvent.mouseMove();
        RecordingInputEvent mouseScroll = RecordingInputEvent.mouseScroll();

        blocker.processCampaignInputPreCore(
                List.of(mouseClick.proxy(), keyboard.proxy(), mouseMove.proxy(), mouseScroll.proxy()));

        assertTrue(mouseClick.consumed);
        assertTrue(keyboard.consumed);
        assertFalse(mouseMove.consumed);
        assertFalse(mouseScroll.consumed);
        assertTrue(blocker.isInteractionBlocked());
    }

    @Test
    void worldInputPassesThroughWhenInteractionBlockIsCleared() {
        CoopCampaignInputBlocker blocker = new CoopCampaignInputBlocker();
        blocker.setInteractionBlocked(true, "Jangala");
        blocker.setInteractionBlocked(false, null);
        RecordingInputEvent mouseClick = RecordingInputEvent.mouseClick();
        RecordingInputEvent keyboard = RecordingInputEvent.keyboard();

        blocker.processCampaignInputPreCore(List.of(mouseClick.proxy(), keyboard.proxy()));

        assertFalse(mouseClick.consumed);
        assertFalse(keyboard.consumed);
        assertFalse(blocker.isInteractionBlocked());
    }

    private static final class RecordingInputEvent {
        private final String controlName;
        private final boolean activated;
        private final boolean down;
        private final Set<String> supportedControls;
        private final boolean mouseMove;
        private final boolean mouseScroll;
        private final boolean mouseEvent;
        private final boolean keyboardEvent;
        private boolean consumed;

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed,
                                    Set<String> supportedControls) {
            this(controlName, activated, down, consumed, supportedControls, false, false, false, false);
        }

        private RecordingInputEvent(String controlName, boolean activated, boolean down, boolean consumed,
                                    Set<String> supportedControls, boolean mouseMove, boolean mouseScroll,
                                    boolean mouseEvent, boolean keyboardEvent) {
            this.controlName = controlName;
            this.activated = activated;
            this.down = down;
            this.consumed = consumed;
            this.supportedControls = supportedControls;
            this.mouseMove = mouseMove;
            this.mouseScroll = mouseScroll;
            this.mouseEvent = mouseEvent;
            this.keyboardEvent = keyboardEvent;
        }

        private static RecordingInputEvent mouseClick() {
            return new RecordingInputEvent("MOUSE_CLICK", false, false, false, Set.of(),
                    false, false, true, false);
        }

        private static RecordingInputEvent keyboard() {
            return new RecordingInputEvent("KEY", false, false, false, Set.of(),
                    false, false, false, true);
        }

        private static RecordingInputEvent mouseMove() {
            return new RecordingInputEvent("MOUSE_MOVE", false, false, false, Set.of(),
                    true, false, true, false);
        }

        private static RecordingInputEvent mouseScroll() {
            return new RecordingInputEvent("MOUSE_SCROLL", false, false, false, Set.of(),
                    false, true, true, false);
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
                            case "isMouseMoveEvent" -> {
                                return mouseMove;
                            }
                            case "isMouseScrollEvent" -> {
                                return mouseScroll;
                            }
                            case "isMouseEvent" -> {
                                return mouseEvent;
                            }
                            case "isKeyboardEvent" -> {
                                return keyboardEvent;
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
