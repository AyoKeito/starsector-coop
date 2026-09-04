package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import coop.session.CoopJoinPhase;
import coop.util.CoopLog;

import java.util.Map;
import java.util.function.Supplier;

/**
 * What the guest looks at between "the socket connected" and "the world arrived": the same five named
 * phases the host's roster shows, with the current one marked, an elapsed counter that ticks every
 * second, and a Cancel that is always live.
 *
 * <p><b>The counter is a label, not a paragraph.</b> The view carries a raw millisecond counter, so
 * it differs on every frame and the dialog used to clear and rebuild its text panel four times a
 * second. Players reported exactly that as a flash on the reconnect dialog, which did far less. The
 * phase list is now rebuilt only when a phase or a failure actually changes, and the elapsed line is
 * a {@link CoopLiveDialogLine} label updated with {@code setText}.
 *
 * <p><b>Why the counter matters.</b> The lobby research rule is "never a static frame longer than ten
 * seconds": a screen that does not move is indistinguishable from a hung one, and a join stall with
 * no visible progress is the single most reported multiplayer support case in the corpus. The step
 * counter plus the elapsed clock is the cheapest honest answer.
 *
 * <p><b>Why the failures are named individually.</b> Elden Ring Seamless Co-op's undifferentiated
 * "no sessions found" is the cautionary tale; every distinguishable cause gets its own sentence and
 * its own remedy. The three this dialog can name on its own are a version/mod mismatch, an explicit
 * refusal from the host (with the host's own reason text), and a timeout with no {@code LOBBY_ACCEPT}
 * inside {@link #CONNECT_TIMEOUT_MILLIS}.
 *
 * <p><b>Seam for the desync dialogs.</b> Seed-lock rejects, mod-mismatch diffs and resume rejects get
 * their own dedicated dialogs (built separately). When one of those takes over, the pump closes this
 * controller and this dialog simply steps aside rather than showing a second, weaker version of the
 * same message.
 */
public final class CoopConnectingDialog implements InteractionDialogPlugin, CoopDismissableDialog {

    /** No {@code LOBBY_ACCEPT} within this long is reported as a timeout rather than left spinning. */
    public static final long CONNECT_TIMEOUT_MILLIS = 30_000L;

    private static final Object OPTION_CANCEL = new Object();

    static final String TEXT_CANCEL = "Cancel";

    /** Cap on re-renders; the phase list changes rarely and the counter only once a second. */
    static final long MIN_RENDER_INTERVAL_MILLIS = 250L;

    /** The distinguishable ways a join can fail before the lobby is reached. */
    public enum Failure {
        /** The handshake compared the two installs and they differ. */
        VERSION_MISMATCH,
        /** The host answered {@code LOBBY_REJECT} — wrong password, slot taken, grace window. */
        HOST_REFUSED,
        /** Connected, but no {@code LOBBY_ACCEPT} inside {@link #CONNECT_TIMEOUT_MILLIS}. */
        TIMED_OUT
    }

    /**
     * One frame of the connecting screen.
     *
     * @param phase   how far the join has got; null before the host has accepted the lobby at all
     * @param elapsedMillis time since the connect attempt started
     * @param failure the named cause, or null while the join is still running
     * @param detail  the host's own words for {@link Failure#HOST_REFUSED} / the handshake diff for a
     *                mismatch; "" when there is nothing more to say
     * @param retrying whether the connect loop is still running behind this screen. Only the wrong
     *                 password stops it; every other refusal ("lobby already has a guest", the grace
     *                 window, the transport's extra-connection reject) backs off five seconds and
     *                 dials again, and the remedy line used to tell all of them that nothing retries
     *                 on its own — which is the sentence that makes a player press Cancel or relaunch
     *                 five seconds before they would have been let in.
     */
    public record View(CoopJoinPhase phase, long elapsedMillis, Failure failure, String detail,
                       boolean retrying) {
        public View {
            detail = detail == null ? "" : detail;
        }

        /** The common case: the join is still being retried. */
        public View(CoopJoinPhase phase, long elapsedMillis, Failure failure, String detail) {
            this(phase, elapsedMillis, failure, detail, true);
        }
    }

    private final Supplier<View> viewSupplier;
    private final Supplier<Long> clock;
    private final Runnable onCancel;

    private InteractionDialogAPI dialog;
    /** The half of the view a rebuild is needed for; null until one has succeeded. */
    private Key renderedKey;
    /** The elapsed counter, as a label in the visual panel. */
    private final CoopLiveDialogLine liveLine = new CoopLiveDialogLine();
    private long lastRenderAtMillis = Long.MIN_VALUE;
    private boolean renderFailed;

    /** Everything in a {@link View} except the clock: what the phase list is drawn from. */
    private record Key(CoopJoinPhase phase, Failure failure, String detail, boolean retrying) {
        static Key of(View view) {
            return new Key(view.phase(), view.failure(), view.detail(), view.retrying());
        }
    }

    /**
     * @param viewSupplier polled every frame; re-renders only when the value changes
     * @param clock        wall clock in millis, for the render rate limit
     * @param onCancel     stops the reconnect loop and leaves the guest sitting paused. Always live
     *                     and always safe: a cancelled join must not disturb the host beyond the
     *                     ordinary disconnect it would see anyway.
     */
    public CoopConnectingDialog(Supplier<View> viewSupplier, Supplier<Long> clock, Runnable onCancel) {
        this.viewSupplier = viewSupplier == null ? () -> null : viewSupplier;
        this.clock = clock == null ? () -> 0L : clock;
        this.onCancel = onCancel == null ? () -> { } : onCancel;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        // Never setOptionOnEscape: Cancel is the only exit, and it is explicit.
        View view = currentView();
        if (!liveLine.install(dialog, view == null ? "" : elapsedLine(view))) {
            try {
                dialog.hideVisualPanel();
            } catch (Throwable ignored) {
                // Cosmetic only.
            }
        }
        render(view);
    }

    @Override
    public void advance(float amount) {
        View view = currentView();
        if (view == null) {
            return;
        }
        if (Key.of(view).equals(renderedKey)) {
            // Only the clock moved. One setText, no panel touched.
            liveLine.update(elapsedLine(view));
            return;
        }
        long now = clock.get();
        if (lastRenderAtMillis != Long.MIN_VALUE && now - lastRenderAtMillis < MIN_RENDER_INTERVAL_MILLIS) {
            return;
        }
        render(view);
    }

    private View currentView() {
        try {
            return viewSupplier.get();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop connecting dialog could not read its view", ex);
            return null;
        }
    }

    private void render(View view) {
        if (view == null) {
            return;
        }
        renderText(view);
        liveLine.update(elapsedLine(view));
        if (!renderOptions()) {
            // The panel was cleared and then nothing went into it. Leaving the view unrecorded is
            // what makes the next frame retry instead of settling on an empty, inescapable dialog.
            return;
        }
        renderedKey = Key.of(view);
        lastRenderAtMillis = clock.get();
    }

    private void renderText(View view) {
        if (renderFailed) {
            return;
        }
        try {
            TextPanelAPI text = dialog == null ? null : dialog.getTextPanel();
            if (text == null) {
                return;
            }
            text.clear();
            if (view.failure() != null) {
                text.addPara(failureHeadline(view.failure()));
                text.addPara(failureRemedy(view.failure(), view.retrying()));
                if (!view.detail().isEmpty()) {
                    text.addPara(view.detail());
                }
                if (!liveLine.showing()) {
                    text.addPara(elapsedLine(view));
                }
                return;
            }
            text.addPara("Joining the co-op session.");
            for (CoopJoinPhase phase : CoopJoinPhase.values()) {
                text.addPara(phaseLine(phase, view.phase()));
            }
            if (!liveLine.showing()) {
                // No label to put it in: the counter freezes at the last phase change rather than
                // disappearing, which still reads better than a screen with no clock on it.
                text.addPara(elapsedLine(view));
            }
        } catch (Throwable ex) {
            renderFailed = true;
            CoopLog.warn(getClass(), "Coop connecting dialog could not render its text", ex);
        }
    }

    /** The one line that moves on its own. */
    static String elapsedLine(View view) {
        return "Waiting " + coop.session.CoopLobbyRoster.formatClock(view.elapsedMillis()) + ".";
    }

    /** The phase list line: done, current, or still ahead. */
    static String phaseLine(CoopJoinPhase phase, CoopJoinPhase current) {
        String counter = " (" + phase.stepIndex() + "/" + CoopJoinPhase.STEP_COUNT + ")";
        if (current == null) {
            return "  " + phase.displayWord() + counter;
        }
        if (current.atLeast(phase) && current != phase) {
            return "  done: " + phase.displayWord() + counter;
        }
        if (current == phase) {
            return "> " + phase.displayWord() + counter;
        }
        return "  " + phase.displayWord() + counter;
    }

    static String failureHeadline(Failure failure) {
        return switch (failure) {
            case VERSION_MISMATCH -> "This install and the host's do not match, so the session cannot start.";
            case HOST_REFUSED -> "The host turned this connection down.";
            case TIMED_OUT -> "The host's port answered but the session never started.";
        };
    }

    static String failureRemedy(Failure failure, boolean retrying) {
        return switch (failure) {
            case VERSION_MISMATCH -> "Match the host's game version and mod list, then reconnect.";
            case HOST_REFUSED -> retrying
                    ? "The host's own words are below. This keeps retrying every 5 seconds; Cancel to stop."
                    : "The host's own words are below. Nothing here retries on its own -"
                            + " fix it and relaunch.";
            case TIMED_OUT -> "Nothing arrived in " + (CONNECT_TIMEOUT_MILLIS / 1000L)
                    + " seconds. Check that the host is still on the lobby screen, then try again.";
        };
    }

    /** @return true when the option panel now holds a usable set of options */
    private boolean renderOptions() {
        try {
            OptionPanelAPI options = dialog == null ? null : dialog.getOptionPanel();
            if (options == null) {
                return false;
            }
            options.clearOptions();
            options.addOption(TEXT_CANCEL, OPTION_CANCEL,
                    "Stops trying to join. Your campaign stays loaded and paused; the host sees an"
                            + " ordinary disconnect and nothing more.");
            return true;
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop connecting dialog could not render its options", ex);
            return false;
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionData != OPTION_CANCEL) {
            return;
        }
        try {
            onCancel.run();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop connecting dialog cancel action failed", ex);
        }
        close();
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return null;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return Map.of();
    }

    @Override
    public void close() {
        InteractionDialogAPI open = dialog;
        dialog = null;
        liveLine.clear();
        if (open == null) {
            return;
        }
        try {
            open.dismiss();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop connecting dialog could not be dismissed", ex);
        }
    }
}
