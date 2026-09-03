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
        assertTrue(dialog.live.text().contains("Starting in 2"));

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


    /**
     * Phase 21 red-team blocker 2. The confirmation used to be cleared by
     * {@code if (!initial && !view.allReady())}, which is true on every frame the override is even
     * offered - and since the view carries a live elapsed counter and live RTT text, "every frame the
     * override is offered" meant "every render". The host pressed "Start anyway", the next re-render
     * put the panel back, and the confirm pair was unreachable.
     */
    @Test
    void theStartAnywayConfirmSurvivesTheElapsedCounterTicking() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(false, -1L, 12_000L));
        AtomicLong overrides = new AtomicLong();
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, overrides::incrementAndGet, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY));
        assertTrue(plugin.confirmingStartAnyway());
        int afterConfirm = dialog.text.clears;

        for (int frame = 1; frame <= 4; frame++) {
            now.addAndGet(250L);
            view.set(hostView(false, -1L, 12_000L + 250L * frame));
            plugin.advance(0f);
        }

        assertEquals(afterConfirm, dialog.text.clears,
                "and the question is not even redrawn: a tick never reaches the option panel");
        assertEquals(List.of(CoopLobbyDialog.TEXT_START_ANYWAY_CONFIRM,
                        CoopLobbyDialog.TEXT_START_ANYWAY_BACK), dialog.options.texts(),
                "a ticking clock is not a reason to take the question back");

        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY_CONFIRM,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY_CONFIRM));
        assertEquals(1L, overrides.get());
    }

    /** The other half of blocker 2: what genuinely does invalidate the standing confirmation. */
    @Test
    void aRosterThatMovesUnderTheConfirmTakesItBack() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(false, -1L, 12_000L));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());

        plugin.optionSelected(CoopLobbyDialog.TEXT_START_ANYWAY,
                dialog.options.dataFor(CoopLobbyDialog.TEXT_START_ANYWAY));
        assertTrue(plugin.confirmingStartAnyway());

        // The guest readied while the question was on screen: different rows, different question.
        now.addAndGet(250L);
        view.set(hostView(true, -1L, 12_250L));
        plugin.advance(0f);

        assertFalse(plugin.confirmingStartAnyway());
        assertEquals(List.of("Start session"), dialog.options.texts());
    }

    /**
     * Phase 21 red-team item 6: {@code clearOptions()} followed by a throwing {@code addOption} left
     * an inescapable dialog with no options in it, and the view had already been marked rendered, so
     * no later frame ever tried again.
     */
    @Test
    void anOptionPanelThatThrowsIsRetriedRatherThanLeftEmpty() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(hostView(true, -1L, 12_000L));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        dialog.options.throwOnAddOption = true;
        plugin.init(dialog.proxy());

        assertTrue(dialog.options.texts().isEmpty(), "the panel really is empty at this point");

        dialog.options.throwOnAddOption = false;
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);

        assertEquals(List.of("Start session"), dialog.options.texts(),
                "the same unchanged view is re-rendered, because it was never recorded as rendered");
    }

    // ---- flicker: what rebuilds and what does not -----------------------------------------------

    /**
     * The live report behind this: vanilla dialogs redraw whole, so a panel rebuilt on every tick of
     * a 1 Hz counter reads as a flash. Elapsed time moving is not a reason to rebuild anything.
     */
    @Test
    void aTickingElapsedCounterRewritesOneParagraphInsteadOfRebuilding() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(linkedHostView(-1L, 12_000L, 42));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        int afterInit = dialog.text.clears;
        int paragraphs = dialog.text.paragraphs.size();

        assertEquals(1, dialog.live.panelsShown, "one custom panel, built once at init");
        assertEquals("Waiting 0:12. - Link: 42 ms over UDP", dialog.live.text(),
                "everything that ticks lives in the label, not in the text panel");
        assertFalse(dialog.text.paragraphs.contains("Waiting 0:12. - Link: 42 ms over UDP"),
                "and nothing that ticks is written into the text panel at all");

        for (int second = 13; second <= 16; second++) {
            now.addAndGet(1_000L);
            view.set(linkedHostView(-1L, second * 1_000L, 42));
            plugin.advance(0f);
        }

        assertEquals(afterInit, dialog.text.clears, "a ticking clock never rebuilds the panel");
        assertEquals(paragraphs, dialog.text.paragraphs.size());
        assertEquals(List.of(), dialog.text.replacements,
                "replaceLastParagraph is what the player saw flashing; it is not used any more");
        assertEquals(List.of("Waiting 0:13. - Link: 42 ms over UDP",
                        "Waiting 0:14. - Link: 42 ms over UDP",
                        "Waiting 0:15. - Link: 42 ms over UDP",
                        "Waiting 0:16. - Link: 42 ms over UDP"),
                dialog.live.setTexts);
        assertEquals(1, dialog.live.panelsShown, "and the panel is never rebuilt either");
    }

    /** The RTT sample is the other number that moves on its own, and it moves out of phase. */
    @Test
    void aChangingRttRewritesTheSameLineRatherThanTheBlock() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(linkedHostView(-1L, 12_000L, 42));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        int afterInit = dialog.text.clears;

        now.addAndGet(1_000L);
        view.set(linkedHostView(-1L, 12_000L, 57));
        plugin.advance(0f);

        assertEquals(afterInit, dialog.text.clears);
        assertEquals(List.of(), dialog.text.replacements);
        assertEquals(List.of("Waiting 0:12. - Link: 57 ms over UDP"), dialog.live.setTexts);
        assertTrue(dialog.text.paragraphs.stream().noneMatch(line -> line.startsWith("Link: ")),
                "the RTT belongs to the live line, not to the verdict block above it");
    }

    /**
     * The countdown is the case the player called unreadable: two 1 Hz fields out of phase. The
     * elapsed counter is dropped while one runs, so the line changes exactly once a second - and the
     * frames in between, which are most of them, touch the panel not at all.
     */
    @Test
    void theCountdownRewritesItsLineOncePerSecondAndNothingElse() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(linkedHostView(3_000L, 12_000L, 42));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        int afterInit = dialog.text.clears;

        // Four frames a second for three seconds; the elapsed counter keeps ticking underneath.
        for (int frame = 1; frame <= 12; frame++) {
            now.addAndGet(250L);
            view.set(linkedHostView(3_000L - frame * 250L, 12_000L + frame * 250L, 42));
            plugin.advance(0f);
        }

        assertEquals(afterInit, dialog.text.clears, "the countdown never rebuilds the panel");
        assertEquals(List.of(), dialog.text.replacements, "and never touches the text panel");
        assertEquals(List.of("Starting in 2... - Link: 42 ms over UDP",
                        "Starting in 1... - Link: 42 ms over UDP",
                        "Starting... - Link: 42 ms over UDP"),
                dialog.live.setTexts,
                "one setText per whole second, and the elapsed counter contributes none of them");
        assertEquals(List.of(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN), dialog.options.texts());
    }

    /** The countdown starting or being cancelled changes the options, so that half is structural. */
    @Test
    void armingAndCancellingTheCountdownStillRebuilds() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(linkedHostView(-1L, 12_000L, 42));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        int afterInit = dialog.text.clears;

        view.set(linkedHostView(3_000L, 12_000L, 42));
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);
        assertEquals(afterInit + 1, dialog.text.clears);
        assertEquals(List.of(CoopLobbyDialog.TEXT_CANCEL_COUNTDOWN), dialog.options.texts());

        view.set(linkedHostView(-1L, 15_000L, 42));
        now.addAndGet(CoopLobbyDialog.MIN_RENDER_INTERVAL_MILLIS);
        plugin.advance(0f);
        assertEquals(afterInit + 2, dialog.text.clears);
        assertEquals(List.of("Start session"), dialog.options.texts());
    }

    /**
     * {@code setText} is the only engine call on the live path, so it is the only one that can throw
     * there. When it does, the lobby that is already on screen has to stay usable.
     */
    @Test
    void aRefusedLabelUpdateStopsTickingRatherThanRepeatingTheThrow() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(linkedHostView(-1L, 12_000L, 42));
        AtomicLong now = new AtomicLong(1_000L);
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                () -> { }, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        plugin.init(dialog.proxy());
        dialog.live.throwOnSetText = true;

        for (int second = 13; second <= 16; second++) {
            now.addAndGet(1_000L);
            view.set(linkedHostView(-1L, second * 1_000L, 42));
            plugin.advance(0f);
        }

        assertEquals(List.of(), dialog.live.setTexts, "it gave up after the first throw");
        assertTrue(dialog.text.paragraphs.stream().anyMatch(line -> line.contains("Alice")),
                "the roster stays on screen without a live counter");
        assertEquals(List.of("Start session"), dialog.options.texts(),
                "and the way out of the dialog is still there");
    }

    /**
     * A visual panel the engine will not give out means no live numbers, and nothing else: the
     * roster, the options and the way out of the dialog all have to survive it.
     */
    @Test
    void aDialogWithNoCustomPanelStillRendersAndStaysUsable() {
        AtomicReference<CoopLobbyView> view = new AtomicReference<>(linkedHostView(-1L, 12_000L, 42));
        AtomicLong now = new AtomicLong(1_000L);
        AtomicLong started = new AtomicLong();
        CoopLobbyDialog plugin = new CoopLobbyDialog(view::get, now::get,
                started::incrementAndGet, () -> { }, () -> { }, ready -> { });
        RecordingDialog dialog = new RecordingDialog();
        dialog.live.throwOnShowCustomPanel = true;

        plugin.init(dialog.proxy());

        assertEquals(1, dialog.hideVisualCalls, "the visual area goes back to empty");
        assertEquals(List.of("Start session"), dialog.options.texts());
        assertEquals("Waiting 0:12. - Link: 42 ms over UDP", dialog.text.lastParagraph(),
                "with no label to put it in, the line falls back into the text panel");

        // And it stops there: no label means no per-second anything.
        for (int second = 13; second <= 16; second++) {
            now.addAndGet(1_000L);
            view.set(linkedHostView(-1L, second * 1_000L, 42));
            plugin.advance(0f);
        }
        assertEquals(1, dialog.text.clears, "still one render, not one a second");
        assertEquals(List.of(), dialog.text.replacements);

        plugin.optionSelected("Start session", dialog.options.dataFor("Start session"));
        assertEquals(1L, started.get());
    }

    /** A view with the pump's live RTT line in its verdict block. */
    private static CoopLobbyView linkedHostView(long countdownRemaining, long elapsedMillis, int rtt) {
        return new CoopLobbyView(CoopConnectionRole.HOST,
                List.of(new CoopLobbyView.Row("Alice", "Ready", true),
                        new CoopLobbyView.Row("Bob", "Ready", false)),
                List.of("Connection: direct", "Link: " + rtt + " ms over UDP"),
                countdownRemaining, elapsedMillis, false, true, "Start session", false, false, false);
    }

    private static CoopLobbyView hostView(boolean allReady, long countdownRemaining, long elapsedMillis) {
        return new CoopLobbyView(CoopConnectionRole.HOST,
                List.of(new CoopLobbyView.Row("Alice", "Ready", true),
                        new CoopLobbyView.Row("Bob", allReady ? "Ready" : "Not ready", false)),
                List.of("Connection: direct"),
                countdownRemaining, elapsedMillis, false, allReady,
                allReady ? "Start session" : "Waiting for Bob...", false, false, false);
    }

    private static final class RecordingDialog {
        private final RecordingTextPanel text = new RecordingTextPanel();
        private final RecordingOptionPanel options = new RecordingOptionPanel();
        private final RecordingLiveLine live = new RecordingLiveLine();
        private int dismissCount;
        private int escapeOptionCalls;
        private int hideVisualCalls;

        private InteractionDialogAPI proxy() {
            return (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hideVisualPanel" -> {
                            hideVisualCalls++;
                            yield null;
                        }
                        case "getVisualPanel" -> live.proxy();
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
        /** Every string handed to {@code replaceLastParagraph}, in order. */
        final List<String> replacements = new ArrayList<>();
        int clears;

        String lastParagraph() {
            return paragraphs.isEmpty() ? "" : paragraphs.get(paragraphs.size() - 1);
        }

        TextPanelAPI proxy() {
            return (TextPanelAPI) Proxy.newProxyInstance(
                    TextPanelAPI.class.getClassLoader(),
                    new Class<?>[]{TextPanelAPI.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addPara" -> {
                            paragraphs.add((String) args[0]);
                            yield null;
                        }
                        case "replaceLastParagraph" -> {
                            replacements.add((String) args[0]);
                            if (paragraphs.isEmpty()) {
                                paragraphs.add((String) args[0]);
                            } else {
                                paragraphs.set(paragraphs.size() - 1, (String) args[0]);
                            }
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
        /** Models an engine option panel that clears and then refuses to take the new options. */
        boolean throwOnAddOption;
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
                            if (throwOnAddOption) {
                                throw new IllegalStateException("no options for you");
                            }
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
