package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import coop.net.CoopConnectionRole;
import coop.util.CoopLog;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Phase 21's in-campaign lobby: who is connected, how far along they are, what the connection looks
 * like, and the two-stage gate that starts the session.
 *
 * <p><b>A plain interaction dialog.</b> {@code advance()} ticks every frame while a dialog is open
 * (including while paused), the option panel can be rebuilt in place, and the text panel has
 * {@code clear()}, which is the roster. The one custom panel here holds a single label and takes no
 * input, so neither the "buttonPressed never fires for a plugin-less panel" trap nor the
 * {@code removeComponent} leak on repeated rebuild applies: it is built once and never rebuilt.
 *
 * <p><b>Inescapable by design:</b> {@code setOptionOnEscape} is never called, exactly as every vanilla
 * tutorial dialog does it. The world is held paused behind this dialog and the only ways out are
 * Start, the host's override, or the game menu.
 *
 * <p><b>Start anyway is confirm-guarded in-dialog.</b> Pressing it swaps the option panel to
 * "Yes, start anyway" / "Back" rather than opening a nested {@code showCustomDialog}: a nested dialog
 * fires {@code customDialogCancel()} on ESC no matter what, which would be an ESC that half-escapes
 * an inescapable dialog.
 *
 * <p><b>Rebuild rarely, and never for a number.</b> A player watching the countdown reported the
 * lobby as "pretty much unreadable": the view carries three numbers that move on their own (elapsed,
 * countdown, RTT), and any of them changing used to clear and rebuild both panels. Writing to the
 * text panel once a second turned out to be the flash all by itself, however little was written; the
 * same player saw the reconnect dialog blink on a bare
 * {@code replaceLastParagraph}. So a text-panel rebuild happens only when
 * {@link CoopLobbyView#structuralKey()} changes - the roster, the gate, a countdown starting or
 * being cancelled, the confirmation, a failed render being retried - and every ticking number is a
 * {@link CoopLiveDialogLine} label in the visual panel, updated with {@code setText}.
 *
 * <p><b>Total, like the rest of the coop UI.</b> Every engine call is wrapped: a dialog that cannot
 * render must never take down the pump frame that is running the session behind it.
 */
public final class CoopLobbyDialog implements InteractionDialogPlugin, CoopDismissableDialog {

    /** Option ids; object identities, so nothing else can collide with them. */
    private static final Object OPTION_START = new Object();
    private static final Object OPTION_CANCEL_COUNTDOWN = new Object();
    private static final Object OPTION_START_ANYWAY = new Object();
    private static final Object OPTION_START_ANYWAY_CONFIRM = new Object();
    private static final Object OPTION_START_ANYWAY_BACK = new Object();
    private static final Object OPTION_READY = new Object();
    private static final Object OPTION_NOT_READY = new Object();

    static final String TEXT_CANCEL_COUNTDOWN = "Cancel countdown";
    static final String TEXT_START_ANYWAY = "Start anyway (guest not ready)";
    static final String TEXT_START_ANYWAY_CONFIRM = "Yes, start anyway";
    static final String TEXT_START_ANYWAY_BACK = "Back";
    static final String TEXT_READY = "Ready";
    static final String TEXT_NOT_READY = "Not ready";

    /**
     * Cap on full rebuilds. Structural change is player-driven and rare; this only exists so a peer
     * flapping between two rosters cannot make the panel strobe. Live ticks are not rate-limited by
     * the clock at all: they are one setText on a label, and only when the value moved.
     */
    static final long MIN_RENDER_INTERVAL_MILLIS = 250L;

    private final Supplier<CoopLobbyView> viewSupplier;
    private final Supplier<Long> clock;
    private final Runnable onStart;
    private final Runnable onCancelCountdown;
    private final Runnable onStartAnyway;
    private final Consumer<Boolean> onReadyChanged;

    private InteractionDialogAPI dialog;
    /** The structural half of the last view both panels were built for; null until that succeeds. */
    private CoopLobbyView.Key renderedKey;
    /** The ticking numbers, as a label in the visual panel rather than as text-panel content. */
    private final CoopLiveDialogLine liveLine = new CoopLiveDialogLine();
    private long lastRenderAtMillis = Long.MIN_VALUE;
    private boolean confirmingStartAnyway;
    private boolean renderFailed;

    /**
     * @param viewSupplier      polled every frame; the dialog re-renders only when the value changes
     * @param clock             wall clock in millis, for the render rate limit
     * @param onStart           host pressed Start (arms the countdown)
     * @param onCancelCountdown any player cancelled the countdown
     * @param onStartAnyway     host confirmed the override
     * @param onReadyChanged    guest toggled its own ready
     */
    public CoopLobbyDialog(Supplier<CoopLobbyView> viewSupplier,
                           Supplier<Long> clock,
                           Runnable onStart,
                           Runnable onCancelCountdown,
                           Runnable onStartAnyway,
                           Consumer<Boolean> onReadyChanged) {
        this.viewSupplier = viewSupplier == null ? () -> null : viewSupplier;
        this.clock = clock == null ? () -> 0L : clock;
        this.onStart = onStart == null ? () -> { } : onStart;
        this.onCancelCountdown = onCancelCountdown == null ? () -> { } : onCancelCountdown;
        this.onStartAnyway = onStartAnyway == null ? () -> { } : onStartAnyway;
        this.onReadyChanged = onReadyChanged == null ? ready -> { } : onReadyChanged;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        // Never setOptionOnEscape: this dialog is inescapable on purpose.
        CoopLobbyView view = currentView();
        if (!liveLine.install(dialog, view == null ? "" : view.liveLine())) {
            try {
                dialog.hideVisualPanel();
            } catch (Throwable ignored) {
                // Cosmetic only: a visual panel that will not hide beats no lobby at all.
            }
        }
        render(view, true);
    }

    @Override
    public void advance(float amount) {
        CoopLobbyView view = currentView();
        if (view == null) {
            return;
        }
        if (view.structuralKey().equals(renderedKey)) {
            // Nothing a rebuild would change. The label takes the new number; no panel is touched.
            liveLine.update(view.liveLine());
            return;
        }
        long now = clock.get();
        if (lastRenderAtMillis != Long.MIN_VALUE && now - lastRenderAtMillis < MIN_RENDER_INTERVAL_MILLIS) {
            return;
        }
        render(view, false);
    }

    private CoopLobbyView currentView() {
        try {
            return viewSupplier.get();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop lobby could not read its view", ex);
            return null;
        }
    }

    private void render(CoopLobbyView view, boolean initial) {
        if (view == null) {
            return;
        }
        if (!initial && confirmationIsStale(view)) {
            // The override's confirmation is about the roster it was pressed on; a roster that moved
            // out from under it must not leave a "Yes, start anyway" standing.
            //
            // Phase 21 red-team: the old test here was "!view.allReady()", which is true on every
            // frame the override is even offered. Since the view also carries a live elapsed counter
            // and live RTT text, some field changed on nearly every render and the confirmation the
            // host had just pressed was wiped before it could be pressed again. What actually
            // invalidates it is the roster moving or the gate opening on its own - nothing else.
            confirmingStartAnyway = false;
        }
        renderText(view);
        // The label moves with the rebuild rather than a frame after it: pressing Start should
        // put the countdown up in the same frame the options change.
        liveLine.update(view.liveLine());
        if (!renderOptions(view)) {
            // A failed option render leaves the panel cleared and empty, which is the trapped-player
            // bug. Not recording the view as rendered is what makes the next frame try again.
            return;
        }
        renderedKey = view.structuralKey();
        lastRenderAtMillis = clock.get();
    }

    /**
     * Whether a standing "Yes, start anyway" is about a roster that no longer exists: the rows
     * changed, or everybody readied and the override is not the way in any more.
     */
    private boolean confirmationIsStale(CoopLobbyView view) {
        if (renderedKey == null) {
            return true;
        }
        return !renderedKey.rows().equals(view.rows())
                || (view.allReady() && !renderedKey.allReady());
    }

    private void renderText(CoopLobbyView view) {
        if (renderFailed) {
            return;
        }
        try {
            TextPanelAPI text = dialog == null ? null : dialog.getTextPanel();
            if (text == null) {
                return;
            }
            // clear()-and-rebuild: the roster is a handful of short lines and, now that the ticking
            // numbers are handled separately, it changes only on player actions.
            text.clear();
            text.addPara("Co-op lobby - the campaign is held until every player is ready.");
            for (CoopLobbyView.Row row : view.rows()) {
                text.addPara(row.line());
            }
            for (String line : view.stableVerdictLines()) {
                text.addPara(line);
            }
            if (view.afkHint()) {
                text.addPara("Still waiting after two minutes. \"" + TEXT_START_ANYWAY
                        + "\" starts without them; the guest will mirror a running world.");
            }
            // Nothing that ticks goes in here. The elapsed counter, the countdown and the link
            // sample are the label in the visual panel; a text panel written once per second is
            // what the flashing was.
            if (!liveLine.showing()) {
                // No label to put them in. Better a number frozen at the moment of the last
                // structural change than no sign of the countdown at all.
                text.addPara(view.liveLine());
            }
        } catch (Throwable ex) {
            renderFailed = true;
            CoopLog.warn(getClass(), "Coop lobby could not render its roster", ex);
        }
    }

    /** @return true when the option panel now holds a usable set of options */
    private boolean renderOptions(CoopLobbyView view) {
        try {
            OptionPanelAPI options = dialog == null ? null : dialog.getOptionPanel();
            if (options == null) {
                return false;
            }
            options.clearOptions();
            if (confirmingStartAnyway) {
                options.addOption(TEXT_START_ANYWAY_CONFIRM, OPTION_START_ANYWAY_CONFIRM,
                        "Starts the session with a player who has not readied. They will join a"
                                + " world that is already running.");
                options.addOption(TEXT_START_ANYWAY_BACK, OPTION_START_ANYWAY_BACK,
                        "Keeps waiting.");
                return true;
            }
            // The same predicate the structural key uses, so "a countdown started" always reaches
            // the option panel even though the seconds inside it never do.
            boolean counting = view.countingDown();
            if (view.localRole() == CoopConnectionRole.HOST) {
                if (counting) {
                    options.addOption(TEXT_CANCEL_COUNTDOWN, OPTION_CANCEL_COUNTDOWN,
                            "Stops the countdown; the campaign stays held.");
                    return true;
                }
                options.addOption(view.startLabel(), OPTION_START,
                        view.allReady()
                                ? "Starts the session after a short countdown either player can cancel."
                                : "Arms once every player is ready.");
                options.setEnabled(OPTION_START, view.allReady());
                if (!view.allReady()) {
                    options.addOption(TEXT_START_ANYWAY, OPTION_START_ANYWAY,
                            "Overrides the ready gate. Asks for confirmation first.");
                }
                return true;
            }
            if (counting) {
                options.addOption(TEXT_CANCEL_COUNTDOWN, OPTION_CANCEL_COUNTDOWN,
                        "Stops the countdown; the campaign stays held. Any player may cancel.");
                return true;
            }
            if (view.localReady()) {
                options.addOption(TEXT_NOT_READY, OPTION_NOT_READY,
                        "Takes your ready back. You can do this at any point before the session starts.");
            } else {
                options.addOption(TEXT_READY, OPTION_READY,
                        view.canReady()
                                ? "Tells the host you are ready to start."
                                : "Available once your campaign has the host's world.");
                options.setEnabled(OPTION_READY, view.canReady());
            }
            return true;
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop lobby could not render its options", ex);
            return false;
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        try {
            if (optionData == OPTION_START) {
                onStart.run();
            } else if (optionData == OPTION_CANCEL_COUNTDOWN) {
                onCancelCountdown.run();
            } else if (optionData == OPTION_START_ANYWAY) {
                confirmingStartAnyway = true;
            } else if (optionData == OPTION_START_ANYWAY_CONFIRM) {
                confirmingStartAnyway = false;
                onStartAnyway.run();
            } else if (optionData == OPTION_START_ANYWAY_BACK) {
                confirmingStartAnyway = false;
            } else if (optionData == OPTION_READY) {
                onReadyChanged.accept(Boolean.TRUE);
            } else if (optionData == OPTION_NOT_READY) {
                onReadyChanged.accept(Boolean.FALSE);
            } else {
                return;
            }
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop lobby option handler failed", ex);
        }
        // Re-render immediately so the press feels like it did something, and so the option panel is
        // never left empty - a modal with no options is the trapped-player bug in its purest form.
        CoopLobbyView view = currentView();
        if (view != null) {
            renderText(view);
            liveLine.update(view.liveLine());
            if (renderOptions(view)) {
                renderedKey = view.structuralKey();
                lastRenderAtMillis = clock.get();
            }
        }
    }

    /** Test read: whether the override is currently showing its confirmation pair. */
    boolean confirmingStartAnyway() {
        return confirmingStartAnyway;
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
            CoopLog.warn(getClass(), "Coop lobby could not be dismissed", ex);
        }
    }
}
