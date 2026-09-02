package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import coop.net.CoopReconnectCoordinator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20 live QA, finding F1: both reconnect dialogs must offer a way to keep waiting, and pressing
 * it must move the countdown the player is staring at.
 *
 * <p>Driven against a real {@link CoopReconnectCoordinator} on a fake clock rather than a stub
 * supplier, because the thing under test is the wiring — option to {@code extend} to countdown — and
 * a stub would have proved only that a lambda can be called.
 */
class CoopReconnectDialogTest {

    private static final long GRACE = 60_000L;

    @Test
    void theHostDialogWaitOptionExtendsTheHoldAndRefreshesTheCountdown() {
        AtomicLong now = new AtomicLong(1_000L);
        CoopReconnectCoordinator reconnect = new CoopReconnectCoordinator(GRACE, new NoOpListener());
        reconnect.beginHostWait("session-a", "guest-player", now.get());
        List<String> ended = new ArrayList<>();
        CoopReconnectHostDialog plugin = new CoopReconnectHostDialog("Guest",
                () -> reconnect.remainingSeconds(now.get()),
                () -> reconnect.extend(CoopReconnectCoordinator.WAIT_MORE_MILLIS, now.get()),
                () -> ended.add("ended"));

        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT, "End session now"),
                dialog.options.optionTexts(),
                "waiting comes first; the destructive option must not be what the eye lands on");
        assertTrue(dialog.text.paragraphs.get(1).contains("60"));

        // 40 s in, 20 s left, and the host decides to keep waiting.
        now.set(41_000L);
        plugin.optionSelected(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT,
                dialog.options.dataFor(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT));

        assertTrue(reconnect.hostWaiting(), "the window stays open");
        assertEquals(320, reconnect.remainingSeconds(now.get()));
        assertEquals("Holding for 320 more seconds...", dialog.text.paragraphs.get(1),
                "the countdown moves on the click, not a frame later");
        assertEquals(0, dialog.dismissCount, "waiting must not close the dialog");
        assertEquals(List.of(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT, "End session now"),
                dialog.options.optionTexts(),
                "both options must survive the press; an empty panel is the trapped-player bug");
        assertEquals(List.of(), ended);

        // And again, because the option is deliberately unlimited.
        plugin.optionSelected(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT,
                dialog.options.dataFor(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT));
        assertEquals(620, reconnect.remainingSeconds(now.get()));
    }

    @Test
    void theGuestDialogWaitOptionExtendsTheRetryWindow() {
        AtomicLong now = new AtomicLong(1_000L);
        CoopReconnectCoordinator reconnect = new CoopReconnectCoordinator(GRACE, new NoOpListener());
        reconnect.beginGuestReconnect("session-a", "guest-player", now.get());
        CoopReconnectGuestDialog plugin = new CoopReconnectGuestDialog("Host",
                () -> reconnect.remainingSeconds(now.get()),
                () -> reconnect.extend(CoopReconnectCoordinator.WAIT_MORE_MILLIS, now.get()),
                () -> reconnect.end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER));

        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT, "Give up"),
                dialog.options.optionTexts());

        now.set(51_000L);
        plugin.optionSelected(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT,
                dialog.options.dataFor(CoopReconnectDialogPlugin.WAIT_MORE_OPTION_TEXT));

        assertTrue(reconnect.guestReconnecting());
        assertEquals(310, reconnect.remainingSeconds(now.get()));
        assertEquals("Retrying for 310 more seconds...", dialog.text.paragraphs.get(1));
        assertEquals(0, dialog.dismissCount);
    }

    @Test
    void theEndOptionStillEndsTheSessionAndClosesTheDialog() {
        AtomicLong now = new AtomicLong(1_000L);
        CoopReconnectCoordinator reconnect = new CoopReconnectCoordinator(GRACE, new NoOpListener());
        reconnect.beginHostWait("session-a", "guest-player", now.get());
        CoopReconnectHostDialog plugin = new CoopReconnectHostDialog("Guest",
                () -> reconnect.remainingSeconds(now.get()),
                () -> reconnect.extend(CoopReconnectCoordinator.WAIT_MORE_MILLIS, now.get()),
                () -> reconnect.end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER));

        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        plugin.optionSelected("End session now", dialog.options.dataFor("End session now"));

        assertFalse(reconnect.active());
        assertEquals(1, dialog.dismissCount);
    }

    @Test
    void theCountdownFollowsTheClockBetweenPresses() {
        AtomicLong now = new AtomicLong(1_000L);
        CoopReconnectCoordinator reconnect = new CoopReconnectCoordinator(GRACE, new NoOpListener());
        reconnect.beginHostWait("session-a", "guest-player", now.get());
        CoopReconnectHostDialog plugin = new CoopReconnectHostDialog("Guest",
                () -> reconnect.remainingSeconds(now.get()),
                () -> reconnect.extend(CoopReconnectCoordinator.WAIT_MORE_MILLIS, now.get()),
                () -> { });

        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        now.set(31_000L);
        plugin.advance(0.016f);

        assertEquals("Holding for 30 more seconds...", dialog.text.paragraphs.get(1));
        assertEquals(2, dialog.text.paragraphs.size(),
                "the countdown is a paragraph rewrite, never a second paragraph");
    }

    private static final class NoOpListener implements CoopReconnectCoordinator.Listener {
        @Override
        public void onGraceStarted(CoopReconnectCoordinator.State state, long graceMillis) {
        }

        @Override
        public void onResumed(CoopReconnectCoordinator.State previous) {
        }

        @Override
        public void onEnded(CoopReconnectCoordinator.State previous, String reason) {
        }
    }

    /** The three engine surfaces the dialog touches, and nothing else. */
    private static final class RecordingDialog {
        private final RecordingTextPanel text = new RecordingTextPanel();
        private final RecordingOptionPanel options = new RecordingOptionPanel();
        private int dismissCount;

        private InteractionDialogAPI proxy() {
            return (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hideVisualPanel" -> null;
                        case "getTextPanel" -> text.proxy();
                        case "getOptionPanel" -> options.proxy();
                        case "dismiss" -> {
                            dismissCount++;
                            yield null;
                        }
                        case "toString" -> "RecordingDialog";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class RecordingTextPanel {
        private final List<String> paragraphs = new ArrayList<>();

        private TextPanelAPI proxy() {
            return (TextPanelAPI) Proxy.newProxyInstance(
                    TextPanelAPI.class.getClassLoader(),
                    new Class<?>[]{TextPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addPara" -> {
                            paragraphs.add((String) args[0]);
                            yield null;
                        }
                        case "replaceLastParagraph" -> {
                            paragraphs.set(paragraphs.size() - 1, (String) args[0]);
                            yield null;
                        }
                        case "toString" -> "RecordingTextPanel";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class RecordingOptionPanel {
        private final List<String> texts = new ArrayList<>();
        private final List<Object> data = new ArrayList<>();

        private List<String> optionTexts() {
            return List.copyOf(texts);
        }

        private Object dataFor(String optionText) {
            int index = texts.indexOf(optionText);
            if (index < 0) {
                throw new IllegalArgumentException("no such option: " + optionText);
            }
            return data.get(index);
        }

        private OptionPanelAPI proxy() {
            return (OptionPanelAPI) Proxy.newProxyInstance(
                    OptionPanelAPI.class.getClassLoader(),
                    new Class<?>[]{OptionPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "clearOptions" -> {
                            texts.clear();
                            data.clear();
                            yield null;
                        }
                        case "addOption" -> {
                            texts.add((String) args[0]);
                            data.add(args[1]);
                            yield null;
                        }
                        case "toString" -> "RecordingOptionPanel";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
