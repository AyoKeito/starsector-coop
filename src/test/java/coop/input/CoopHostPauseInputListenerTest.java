package coop.input;

import com.fs.starfarer.api.input.InputEventAPI;
import coop.time.CoopSharedPauseCoordinator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopHostPauseInputListenerTest {

    @Test
    void pauseKeyPressSetsHostIntentFromObservedStateAndConsumes() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setObservedPaused(false); // running
        CoopHostPauseInputListener listener = new CoopHostPauseInputListener(coordinator);
        RecordingPauseEvent press = new RecordingPauseEvent(true, false);

        listener.processCampaignInputPreCore(List.of(press.proxy()));

        // Running + press -> host wants pause; event consumed so vanilla never flips the clock itself.
        assertTrue(coordinator.hostPauseIntent());
        assertTrue(press.consumed);

        // Paused + press -> host wants to run again.
        coordinator.setObservedPaused(true);
        RecordingPauseEvent pressAgain = new RecordingPauseEvent(true, false);
        listener.processCampaignInputPreCore(List.of(pressAgain.proxy()));
        assertFalse(coordinator.hostPauseIntent());
    }

    @Test
    void nonDownEdgeAndConsumedEventsAreIgnored() {
        CoopSharedPauseCoordinator coordinator = new CoopSharedPauseCoordinator();
        coordinator.setObservedPaused(false);
        CoopHostPauseInputListener listener = new CoopHostPauseInputListener(coordinator);
        RecordingPauseEvent notDown = new RecordingPauseEvent(false, false);
        RecordingPauseEvent alreadyConsumed = new RecordingPauseEvent(true, true);

        listener.processCampaignInputPreCore(List.of(notDown.proxy(), alreadyConsumed.proxy()));

        assertFalse(coordinator.hostPauseIntent());
        assertFalse(notDown.consumed);
    }

    private static final class RecordingPauseEvent {
        private final boolean down;
        private boolean consumed;

        private RecordingPauseEvent(boolean down, boolean consumed) {
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
                            case "isControlDownEvent" -> {
                                return down && "GENERAL_PAUSE".equals(args[0]);
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
