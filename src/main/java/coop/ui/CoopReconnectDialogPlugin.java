package coop.ui;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import coop.util.CoopLog;

import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Shared body of the two Phase 20.2 reconnect dialogs: a headline, a live countdown paragraph, an
 * option that buys more waiting time, and one that ends the session immediately.
 *
 * <p><b>Why a real interaction dialog.</b> The 20.6 integration rule (corrected 2026-08-26): a real
 * {@link InteractionDialogPlugin} shown through {@code CampaignUIAPI.showInteractionDialog} is the
 * only coop surface that auto-suspends the guest's {@code CoopCampaignInputBlocker}, because that
 * suspend flag is recomputed every frame from {@code ui.isShowingDialog()}. Building this as anything
 * else — a rendering listener, a custom panel — brings back the trapped-guest bug, where the player
 * has a modal on screen and no key that does anything. It also means the engine's own pause applies
 * while the dialog is up, which is precisely the hold this dialog exists to announce.
 *
 * <p><b>Why the countdown is a paragraph rewrite.</b> There is no API to mutate a rendered label's
 * text, so the countdown is {@link TextPanelAPI#replaceLastParagraph(String)} on the last paragraph,
 * driven from {@link #advance(float)} and rate-limited to one rewrite per whole second — the number
 * only changes that often, and rewriting at frame rate would churn the panel for nothing.
 *
 * <p><b>Total, like the rest of the coop UI.</b> Every engine call is wrapped: a dialog that cannot
 * render must never be able to take down the frame that is trying to save the session. The reconnect
 * coordinator behind it works with no dialog at all, and the feed banner is the fallback.
 */
public abstract class CoopReconnectDialogPlugin
        implements InteractionDialogPlugin, CoopDismissableDialog {

    /** The options' ids; object identities, so nothing else can collide with them. */
    private static final Object OPTION_END = new Object();
    private static final Object OPTION_WAIT_MORE = new Object();

    /**
     * Label of the "wait more" option, both roles. The number matches
     * {@code CoopReconnectCoordinator.WAIT_MORE_MILLIS}, which is what the press actually adds; the
     * two are stated in different modules on purpose (the dialogs must not depend on the net layer)
     * so keep them in step by hand.
     */
    static final String WAIT_MORE_OPTION_TEXT = "Wait 5 more minutes";

    private final IntSupplier remainingSeconds;
    private final Runnable onWaitMore;
    private final Runnable onEndSession;

    private InteractionDialogAPI dialog;
    private int lastRenderedSeconds = Integer.MIN_VALUE;
    private boolean bodyRendered;

    CoopReconnectDialogPlugin(IntSupplier remainingSeconds, Runnable onWaitMore, Runnable onEndSession) {
        this.remainingSeconds = remainingSeconds == null ? () -> 0 : remainingSeconds;
        this.onWaitMore = onWaitMore == null ? () -> { } : onWaitMore;
        this.onEndSession = onEndSession == null ? () -> { } : onEndSession;
    }

    /** First line: what happened, in the local player's terms. */
    abstract String headline();

    /** Second line's template; {@code seconds} is the live countdown. */
    abstract String countdownText(int seconds);

    /** Tooltip for the "wait more" option, in the local role's terms. */
    abstract String waitMoreOptionTooltip();

    /** Label of the terminal option. */
    abstract String endOptionText();

    /** Tooltip explaining that the option is irreversible. */
    abstract String endOptionTooltip();

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.hideVisualPanel();
        } catch (Throwable ignored) {
            // Cosmetic only: a visual panel that will not hide is a strictly better outcome than no
            // dialog at all.
        }
        renderBody();
        renderOptions();
    }

    @Override
    public void advance(float amount) {
        refreshCountdown();
    }

    /** Rewrites the countdown paragraph, at most once per whole second of change. */
    private void refreshCountdown() {
        int seconds = remainingSeconds.getAsInt();
        if (seconds == lastRenderedSeconds) {
            return;
        }
        lastRenderedSeconds = seconds;
        try {
            TextPanelAPI text = dialog == null ? null : dialog.getTextPanel();
            if (text != null && bodyRendered) {
                text.replaceLastParagraph(countdownText(seconds));
            }
        } catch (Throwable ex) {
            // Stop trying: a panel that throws once will throw every frame, and the headline that is
            // already on screen carries the message even without the countdown.
            bodyRendered = false;
            CoopLog.warn(getClass(), "Coop reconnect dialog could not refresh its countdown", ex);
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionData == OPTION_WAIT_MORE) {
            try {
                onWaitMore.run();
            } catch (Throwable ex) {
                CoopLog.warn(getClass(), "Coop reconnect dialog could not extend the wait", ex);
            }
            // Stays open: the whole point is to keep waiting, and the next advance() picks the new
            // countdown up. Refresh here too so the number moves on the click rather than a frame
            // later, which is what makes the press feel like it did something.
            refreshCountdown();
            // Re-assert the options. This is the only coop dialog a player can leave open after
            // pressing something, and a dialog with an empty option panel is the trapped-player bug
            // in its purest form: modal on screen, no key that does anything. Cheap insurance
            // against any engine build that clears the panel on selection.
            renderOptions();
            return;
        }
        if (optionData != OPTION_END) {
            return;
        }
        // The coordinator's terminal transition runs first: it is what actually ends the session, and
        // dismissing before it would leave the caller's close path racing the option handler.
        try {
            onEndSession.run();
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop reconnect dialog end-session action failed", ex);
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

    /** Closes the dialog if it is still up; idempotent and safe to call from the pump. */
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
            CoopLog.warn(getClass(), "Coop reconnect dialog could not be dismissed", ex);
        }
    }

    private void renderBody() {
        try {
            TextPanelAPI text = dialog.getTextPanel();
            if (text == null) {
                return;
            }
            text.addPara(headline());
            // The countdown must be the LAST paragraph, because that is the only one replaceable.
            lastRenderedSeconds = remainingSeconds.getAsInt();
            text.addPara(countdownText(lastRenderedSeconds));
            bodyRendered = true;
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop reconnect dialog could not render its text", ex);
        }
    }

    private void renderOptions() {
        try {
            OptionPanelAPI options = dialog.getOptionPanel();
            if (options == null) {
                return;
            }
            options.clearOptions();
            // Waiting first: it is the reversible one, and the destructive option should never be
            // what the eye lands on first in a dialog that appeared unannounced.
            options.addOption(WAIT_MORE_OPTION_TEXT, OPTION_WAIT_MORE, waitMoreOptionTooltip());
            options.addOption(endOptionText(), OPTION_END, endOptionTooltip());
        } catch (Throwable ex) {
            CoopLog.warn(getClass(), "Coop reconnect dialog could not render its options", ex);
        }
    }
}
