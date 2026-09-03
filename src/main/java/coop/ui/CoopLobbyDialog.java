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
 * <p><b>A plain interaction dialog, no custom panel.</b> {@code advance()} ticks every frame while a
 * dialog is open (including while paused), the option panel can be rebuilt in place, and the text
 * panel has {@code clear()} — which is the whole lobby. A {@code CustomPanelAPI} would buy widget
 * layout at the cost of the undocumented "buttonPressed never fires for a plugin-less panel" trap and
 * a documented {@code removeComponent} leak on repeated rebuild. Nothing here needs either.
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

    /** Cap on re-renders. The view only changes on player actions and a 1 Hz elapsed counter. */
    static final long MIN_RENDER_INTERVAL_MILLIS = 250L;

    private final Supplier<CoopLobbyView> viewSupplier;
    private final Supplier<Long> clock;
    private final Runnable onStart;
    private final Runnable onCancelCountdown;
    private final Runnable onStartAnyway;
    private final Consumer<Boolean> onReadyChanged;

    private InteractionDialogAPI dialog;
    private CoopLobbyView rendered;
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
        try {
            dialog.hideVisualPanel();
        } catch (Throwable ignored) {
            // Cosmetic only: a visual panel that will not hide beats no lobby at all.
        }
        // Never setOptionOnEscape: this dialog is inescapable on purpose.
        render(currentView(), true);
    }

    @Override
    public void advance(float amount) {
        CoopLobbyView view = currentView();
        if (view == null || view.equals(rendered)) {
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
        rendered = view;
        lastRenderAtMillis = clock.get();
        if (!initial && !view.allReady()) {
            // The override's confirmation is about the roster it was pressed on; a roster that moved
            // out from under it must not leave a "Yes, start anyway" standing.
            confirmingStartAnyway = false;
        }
        renderText(view);
        renderOptions(view);
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
            // clear()-and-rebuild: the roster is a handful of short lines and it changes only on
            // player actions, so a full rebuild costs nothing and keeps the ordering honest.
            text.clear();
            text.addPara("Co-op lobby - the campaign is held until every player is ready.");
            for (CoopLobbyView.Row row : view.rows()) {
                text.addPara(row.line());
            }
            for (String line : view.verdictLines()) {
                text.addPara(line);
            }
            if (view.afkHint()) {
                text.addPara("Still waiting after two minutes. \"" + TEXT_START_ANYWAY
                        + "\" starts without them; the guest will mirror a running world.");
            }
            int seconds = view.countdownSeconds();
            if (seconds > 0) {
                text.addPara("Starting in " + seconds + "...");
            }
            text.addPara("Waiting " + view.elapsedText() + ".");
        } catch (Throwable ex) {
            renderFailed = true;
            CoopLog.warn(getClass(), "Coop lobby could not render its roster", ex);
        }
    }

    private void renderOptions(CoopLobbyView view) {
        try {
            OptionPanelAPI options = dialog == null ? null : dialog.getOptionPanel();
            if (options == null) {
                return;
            }
            options.clearOptions();
            if (confirmingStartAnyway) {
                options.addOption(TEXT_START_ANYWAY_CONFIRM, OPTION_START_ANYWAY_CONFIRM,
                        "Starts the session with a player who has not readied. They will join a"
                                + " world that is already running.");
                options.addOption(TEXT_START_ANYWAY_BACK, OPTION_START_ANYWAY_BACK,
                        "Keeps waiting.");
                return;
            }
            boolean counting = view.countdownRemainingMillis() >= 0L;
            if (view.localRole() == CoopConnectionRole.HOST) {
                if (counting) {
                    options.addOption(TEXT_CANCEL_COUNTDOWN, OPTION_CANCEL_COUNTDOWN,
                            "Stops the countdown; the campaign stays held.");
                    return;
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
                return;
            }
            if (counting) {
                options.addOption(TEXT_CANCEL_COUNTDOWN, OPTION_CANCEL_COUNTDOWN,
                        "Stops the countdown; the campaign stays held. Any player may cancel.");
                return;
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
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop lobby could not render its options", ex);
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
            rendered = view;
            lastRenderAtMillis = clock.get();
            renderText(view);
            renderOptions(view);
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
