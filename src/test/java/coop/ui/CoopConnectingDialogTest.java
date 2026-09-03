package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import coop.session.CoopJoinPhase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 21 guest connecting screen: the phase list, the elapsed counter that keeps it from ever
 * looking hung, the always-live Cancel, and one named message per failure cause.
 */
class CoopConnectingDialogTest {

    @Test
    void thePhaseListMarksWhereTheJoinHasGotTo() {
        CoopConnectingDialog.View view = new CoopConnectingDialog.View(
                CoopJoinPhase.SEED_LOCKED, 4_000L, null, "");
        CoopConnectingDialog plugin = new CoopConnectingDialog(() -> view, () -> 0L, () -> { });
        RecordingDialog dialog = new RecordingDialog();

        plugin.init(dialog.proxy());

        assertTrue(dialog.text.paragraphs.contains("  done: Connecting (1/5)"));
        assertTrue(dialog.text.paragraphs.contains("  done: Checking versions (2/5)"));
        assertTrue(dialog.text.paragraphs.contains("> Locking the sector (3/5)"));
        assertTrue(dialog.text.paragraphs.contains("  Syncing the world (4/5)"));
        assertTrue(dialog.text.paragraphs.contains("Waiting 0:04."),
                "never a static frame: the counter is what separates slow from dead");
    }

    @Test
    void theElapsedCounterTicks() {
        AtomicReference<CoopConnectingDialog.View> view = new AtomicReference<>(
                new CoopConnectingDialog.View(CoopJoinPhase.LINK_ESTABLISHED, 1_000L, null, ""));
        AtomicLong now = new AtomicLong(1_000L);
        CoopConnectingDialog plugin = new CoopConnectingDialog(view::get, now::get, () -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        view.set(new CoopConnectingDialog.View(CoopJoinPhase.LINK_ESTABLISHED, 65_000L, null, ""));
        now.addAndGet(CoopConnectingDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);

        assertTrue(dialog.text.paragraphs.contains("Waiting 1:05."));
    }

    @Test
    void everyFailureGetsItsOwnWordsAndTheHostsOwnReasonWhenThereIsOne() {
        CoopConnectingDialog.View refused = new CoopConnectingDialog.View(
                null, 2_000L, CoopConnectingDialog.Failure.HOST_REFUSED, "Lobby already has a guest");
        CoopConnectingDialog plugin = new CoopConnectingDialog(() -> refused, () -> 0L, () -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(CoopConnectingDialog.failureHeadline(CoopConnectingDialog.Failure.HOST_REFUSED),
                dialog.text.paragraphs.get(0));
        assertTrue(dialog.text.paragraphs.contains("Lobby already has a guest"));

        // The three headlines are genuinely three, never one string with a swapped reason.
        assertEquals(3, java.util.Set.of(
                CoopConnectingDialog.failureHeadline(CoopConnectingDialog.Failure.HOST_REFUSED),
                CoopConnectingDialog.failureHeadline(CoopConnectingDialog.Failure.VERSION_MISMATCH),
                CoopConnectingDialog.failureHeadline(CoopConnectingDialog.Failure.TIMED_OUT)).size());
        assertTrue(CoopConnectingDialog.failureRemedy(CoopConnectingDialog.Failure.TIMED_OUT)
                .contains(String.valueOf(CoopConnectingDialog.CONNECT_TIMEOUT_MILLIS / 1000L)));
    }

    @Test
    void cancelIsAlwaysOfferedAndClosesTheDialog() {
        AtomicLong cancels = new AtomicLong();
        CoopConnectingDialog plugin = new CoopConnectingDialog(
                () -> new CoopConnectingDialog.View(CoopJoinPhase.VERSIONS_MATCHED, 1_000L, null, ""),
                () -> 0L, cancels::incrementAndGet);
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of(CoopConnectingDialog.TEXT_CANCEL), dialog.options.texts());
        plugin.optionSelected(CoopConnectingDialog.TEXT_CANCEL,
                dialog.options.dataFor(CoopConnectingDialog.TEXT_CANCEL));

        assertEquals(1L, cancels.get());
        assertEquals(1, dialog.dismissCount);
    }

    @Test
    void theDialogNeverSetsAnEscapeOption() {
        CoopConnectingDialog plugin = new CoopConnectingDialog(
                () -> new CoopConnectingDialog.View(null, 0L, null, ""), () -> 0L, () -> { });
        RecordingDialog dialog = new RecordingDialog();

        plugin.init(dialog.proxy());

        assertEquals(0, dialog.escapeOptionCalls);
    }

    @Test
    void anUnchangedViewIsNotReRendered() {
        AtomicReference<CoopConnectingDialog.View> view = new AtomicReference<>(
                new CoopConnectingDialog.View(CoopJoinPhase.SEED_LOCKED, 1_000L, null, ""));
        AtomicLong now = new AtomicLong(1_000L);
        CoopConnectingDialog plugin = new CoopConnectingDialog(view::get, now::get, () -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        int afterInit = dialog.text.clears;

        for (int i = 0; i < 60; i++) {
            now.addAndGet(16L);
            plugin.advance(0.016f);
        }

        assertEquals(afterInit, dialog.text.clears);
    }

    // ---- proxies -------------------------------------------------------------------------------

    private static final class RecordingDialog {
        private final RecordingText text = new RecordingText();
        private final RecordingOptions options = new RecordingOptions();
        private int dismissCount;
        private int escapeOptionCalls;

        private InteractionDialogAPI proxy() {
            return (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hideVisualPanel" -> null;
                        case "getTextPanel" -> text.proxy();
                        case "getOptionPanel" -> options.proxy();
                        case "setOptionOnEscape" -> {
                            escapeOptionCalls++;
                            yield null;
                        }
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

    private static final class RecordingText {
        private final List<String> paragraphs = new ArrayList<>();
        private int clears;

        private TextPanelAPI proxy() {
            return (TextPanelAPI) Proxy.newProxyInstance(
                    TextPanelAPI.class.getClassLoader(),
                    new Class<?>[]{TextPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addPara" -> {
                            paragraphs.add((String) args[0]);
                            yield null;
                        }
                        case "clear" -> {
                            clears++;
                            paragraphs.clear();
                            yield null;
                        }
                        case "toString" -> "RecordingText";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class RecordingOptions {
        private final List<String> optionTexts = new ArrayList<>();
        private final List<Object> data = new ArrayList<>();

        private List<String> texts() {
            return List.copyOf(optionTexts);
        }

        private Object dataFor(String optionText) {
            int index = optionTexts.indexOf(optionText);
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
                            optionTexts.clear();
                            data.clear();
                            yield null;
                        }
                        case "addOption" -> {
                            optionTexts.add((String) args[0]);
                            data.add(args[1]);
                            yield null;
                        }
                        case "toString" -> "RecordingOptions";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
