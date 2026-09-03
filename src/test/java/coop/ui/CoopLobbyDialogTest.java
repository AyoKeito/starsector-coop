package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 21 lobby dialog. Driven through a proxy over the four engine surfaces it touches, so the
 * option gating, the confirm-guarded override and the render-on-change rule are all provable with no
 * game running.
 */
class CoopLobbyDialogTest {

    private static CoopLobbyView hostView(boolean allReady, long countdownRemaining) {
        return new CoopLobbyView(CoopConnectionRole.HOST,
                List.of(new CoopLobbyView.Row("Alice", "Ready", true),
                        new CoopLobbyView.Row("Bob", allReady ? "Ready" : "Not ready", false)),
                List.of("Connection: direct"),
                countdownRemaining, 12_000L, false, allReady,
                allReady ? "Start session" : "Waiting for Bob...", false, false, false);
    }

    private static CoopLobbyView guestView(boolean canReady, boolean localReady, long countdownRemaining) {
        return new CoopLobbyView(CoopConnectionRole.GUEST,
                List.of(new CoopLobbyView.Row("Alice", "Ready", false),
                        new CoopLobbyView.Row("Bob", localReady ? "Ready" : "Not ready", true)),
                List.of(), countdownRemaining, 3_000L, false, localReady, "Start session",
                localReady, canReady, false);
    }

    @Test
    void theHostStartOptionIsDisabledUntilEverybodyIsReady() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(false, -1L));
        AtomicLong started = new AtomicLong();
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                started::incrementAndGet, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of("Waiting for Bob...", CoopLobbyDialog.TEXT_START_ANYWAY),
                dialog.options.texts());
        assertEquals(Boolean.FALSE, dialog.options.enabled.get("Waiting for Bob..."));

        view.set(hostView(true, -1L));
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);

        assertEquals(List.of("Start session"), dialog.options.texts(),
                "all-ready arms Start and retires the override");
        assertEquals(Boolean.TRUE, dialog.options.enabled.get("Start session"));

        plugin.optionSelected("Start session", dialog.options.dataFor("Start session"));
        assertEquals(1L, started.get());
    }

    @Test
    void startAnywayIsConfirmGuardedInTheSameDialog() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(false, -1L));
        AtomicLong overrides = new AtomicLong();
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, () -> 0L,
                () -> { }, () -> { }, overrides::incrementAndGet, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY));

        assertTrue(plugin.confirmingStartAnyway());
        assertEquals(List.of(CoopLobbyDialog.TEXT_START_ANYWAY_CONFIRM, CoopLobbyDialog.TEXT_START_ANYWAY_BACK),
                dialog.options.texts(), "an in-dialog two-step, not a nested dialog ESC could cancel");
        assertEquals(0L, overrides.get(), "pressing the override alone must not start anything");

        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY_BACK,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY_BACK));
        assertFalse(plugin.confirmingStartAnyway());
        assertEquals(0L, overrides.get());

        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY));
        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY_CONFIRM,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY_CONFIRM));
        assertEquals(1L, overrides.get());
    }

    @Test
    void aCountdownReplacesEveryOptionWithCancel() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(true, 2_000L));
        AtomicLong cancels = new AtomicLong();
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, () -> 0L,
                () -> { }, cancels::incrementAndGet, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN), dialog.options.texts());
        assertTrue(dialog.text.paragraphs.stream().anyMatch(line -> line.contains("Starting in 2")));

        plugin.optionSelected(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN));
        assertEquals(1L, cancels.get());
    }

    @Test
    void theGuestReadyOptionIsGatedOnHavingTheWorld() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(guestView(false, false, -1L));
        AtomicBoolean ready = new AtomicBoolean();
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready::set);
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of(CoopLobbyDialog.TEXT_READY), dialog.options.texts());
        assertEquals(Boolean.FALSE, dialog.options.enabled.get(CoopLobbyDialog.TEXT_READY));

        view.set(guestView(true, false, -1L));
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);
        assertEquals(Boolean.TRUE, dialog.options.enabled.get(CoopLobbyDialog.TEXT_READY));

        plugin.optionSelected(CoopLobbyDialog.TEXT_READY, dialog.options.dataFor(CoopLobbyDialog.TEXT_READY));
        assertTrue(ready.get());

        // And the toggle is revocable: the option becomes its own opposite.
        view.set(guestView(true, true, -1L));
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);
        assertEquals(List.of(CoopLobbyDialog.TEXT_NOT_READY), dialog.options.texts());
        plugin.optionSelected(CoopLobbyDialog.TEXT_NOT_READY,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_NOT_READY));
        assertFalse(ready.get());
    }

    @Test
    void theGuestCanCancelTheCountdownToo() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(guestView(true, true, 1_200L));
        AtomicLong cancels = new AtomicLong();
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, () -> 0L,
                () -> { }, cancels::incrementAndGet, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        assertEquals(List.of(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN), dialog.options.texts());
        plugin.optionSelected(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN));
        assertEquals(1L, cancels.get());
    }

    @Test
    void theDialogNeverSetsAnEscapeOption() {
        CoopLobbyDialog plugin = new CoopLobbyDialog(() -> hostView(false, -1L), () -> 0L,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();

        plugin.init(dialog.proxy());

        assertEquals(0, dialog.escapeOptionCalls,
                "the lobby is inescapable by design; the world behind it is held paused");
    }

    @Test
    void anUnchangedViewIsNotReRendered() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(false, -1L));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        int afterInit = dialog.text.clears;

        for (int i = 0; i < 100; i++) {
            now.addAndGet(16L);
            plugin.advance(0.016f);
        }
        assertEquals(afterInit, dialog.text.clears, "a static roster costs nothing per frame");

        // A change inside the rate-limit window waits for it; past it, it renders. The window is
        // measured from the last render, so this pair starts from one.
        view.set(hostView(true, -1L));
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0.016f);
        assertEquals(afterInit + 1, dialog.text.clears);

        view.set(hostView(false, -1L));
        now.addAndGet(10L);
        plugin.advance(0.016f);
        assertEquals(afterInit + 1, dialog.text.clears, "capped at ~4 refreshes a second");
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0.016f);
        assertEquals(afterInit + 2, dialog.text.clears);
    }

    @Test
    void theAfkHintPointsAtTheOverrideWithoutFiringIt() {
        CoopLobbyView view = new CoopLobbyView(CoopConnectionRole.HOST,
                List.of(new CoopLobbyView.Row("Bob", "Not ready", false)), List.of(), -1L,
                130_000L, true, false, "Waiting for Bob...", false, false, false);
        AtomicLong overrides = new AtomicLong();
        CoopLobbyDialog plugin = new CoopLobbyDialog(() -> view, () -> 0L,
                () -> { }, () -> { }, overrides::incrementAndGet, ready -> { });
        RecordingDialog dialog = new RecordingDialog();

        plugin.init(dialog.proxy());

        assertTrue(dialog.text.paragraphs.stream()
                .anyMatch(line -> line.contains(CoopLobbyDialog.TEXT_START_ANYWAY)));
        assertEquals(0L, overrides.get());
    }

    @Test
    void closeDismissesOnceAndIsIdempotent() {
        CoopLobbyDialog plugin = new CoopLobbyDialog(() -> hostView(false, -1L), () -> 0L,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        plugin.close();
        plugin.close();

        assertEquals(1, dialog.dismissCount);
    }

    // ---- proxies -------------------------------------------------------------------------------

    private static final class RecordingDialog {
        private final RecordingTextPanel text = new RecordingTextPanel();
        private final RecordingOptionPanel options = new RecordingOptionPanel();
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

    static final class RecordingTextPanel {
        final List<String> paragraphs = new ArrayList<>();
        int clears;

        TextPanelAPI proxy() {
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
                        case "toString" -> "RecordingTextPanel";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    static final class RecordingOptionPanel {
        private final List<String> optionTexts = new ArrayList<>();
        private final List<Object> data = new ArrayList<>();
        final Map<Object, Boolean> enabledByData = new HashMap<>();
        final Map<String, Boolean> enabled = new HashMap<>();

        List<String> texts() {
            return List.copyOf(optionTexts);
        }

        Object dataFor(String optionText) {
            int index = optionTexts.indexOf(optionText);
            if (index < 0) {
                throw new IllegalArgumentException("no such option: " + optionText);
            }
            return data.get(index);
        }

        OptionPanelAPI proxy() {
            return (OptionPanelAPI) Proxy.newProxyInstance(
                    OptionPanelAPI.class.getClassLoader(),
                    new Class<?>[]{OptionPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "clearOptions" -> {
                            optionTexts.clear();
                            data.clear();
                            enabledByData.clear();
                            enabled.clear();
                            yield null;
                        }
                        case "addOption" -> {
                            optionTexts.add((String) args[0]);
                            data.add(args[1]);
                            enabledByData.put(args[1], Boolean.TRUE);
                            enabled.put((String) args[0], Boolean.TRUE);
                            yield null;
                        }
                        case "setEnabled" -> {
                            enabledByData.put(args[0], (Boolean) args[1]);
                            int index = data.indexOf(args[0]);
                            if (index >= 0) {
                                enabled.put(optionTexts.get(index), (Boolean) args[1]);
                            }
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
