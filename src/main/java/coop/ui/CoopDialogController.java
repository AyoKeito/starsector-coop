package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

/**
 * Owns the one awkward part of showing a coop dialog: {@code showInteractionDialog} is exclusive, so
 * it returns {@code false} whenever another dialog already has the slot, and there is no callback for
 * "the slot is free now".
 *
 * <p>The 20.6 integration rule therefore says: retry every frame until it takes, and never force-close
 * whatever is in the way. That matters most in exactly the situation these dialogs exist for — the
 * link can perfectly well die while the player is mid-conversation at a market, and stealing that
 * dialog would abort a trade the player was in the middle of. So a coop dialog waits its turn; the
 * feed banner posted alongside it carries the message until it gets one.
 *
 * <p>Generalised in Phase 21 from the reconnect-only version: the pump now owns one controller per
 * dialog kind (reconnect, lobby, connecting) and any {@link InteractionDialogPlugin} that also
 * implements {@link CoopDismissableDialog} can be driven by it.
 *
 * <p><b>Opening at load needs deferral, and vanilla ships the recipe:</b> gate on
 * {@code frames > 2 && !ui.isShowingDialog()} and retry until the show returns true
 * ({@code CoreLifecyclePluginImpl}). The frame counter lives here — {@link #FRAMES_BEFORE_FIRST_SHOW}
 * ticks are burned before the first attempt — so no caller has to remember it.
 *
 * <p>Engine-free when there is no engine: with no sector or no campaign UI every method is a no-op,
 * which is what lets the callers' unit tests run with no game at all.
 */
public final class CoopDialogController {

    /**
     * How many ticks pass before the first {@code showInteractionDialog} attempt. The engine reports
     * a usable campaign UI a frame or two before it will actually accept a dialog on a fresh load;
     * vanilla's own deferral recipe waits the same couple of frames.
     */
    public static final int FRAMES_BEFORE_FIRST_SHOW = 2;

    private final String kind;

    private InteractionDialogPlugin pending;
    private boolean shown;
    private boolean openFailureLogged;
    private int ticks;

    /** @param kind one word for the log lines ("reconnect", "lobby", "connecting") */
    public CoopDialogController(String kind) {
        this.kind = kind == null || kind.trim().isEmpty() ? "coop" : kind.trim();
    }

    /**
     * Asks for this dialog to be on screen. Idempotent per plugin instance: calling it again while the
     * same one is already up does nothing, and calling it with a new one replaces what we are trying
     * to show.
     */
    public void request(InteractionDialogPlugin plugin) {
        if (plugin == null || plugin == pending) {
            return;
        }
        close();
        pending = plugin;
        shown = false;
        openFailureLogged = false;
    }

    /** Frame tick: one {@code showInteractionDialog} attempt while a dialog is wanted but not up. */
    public void tick() {
        if (ticks <= FRAMES_BEFORE_FIRST_SHOW) {
            ticks++;
        }
        if (pending == null || shown || ticks <= FRAMES_BEFORE_FIRST_SHOW) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui == null) {
                return;
            }
            // Null interaction target: these dialogs are about the session, not about anything in the
            // sector, and there is no entity it would make sense to point at.
            shown = ui.showInteractionDialog(pending, null);
        } catch (Throwable ex) {
            // Once. A UI that throws here will throw every frame, and the caller already posted a feed
            // banner carrying the same message.
            if (!openFailureLogged) {
                openFailureLogged = true;
                CoopLog.warn(CoopDialogController.class,
                        "Coop could not open the " + kind + " dialog; falling back to the feed banner", ex);
            }
            pending = null;
        }
    }

    /** True once the engine has actually put the requested dialog on screen. */
    public boolean isShown() {
        return shown;
    }

    /** True while a dialog is wanted, whether or not it has managed to open yet. */
    public boolean isRequested() {
        return pending != null;
    }

    /** The plugin currently requested, or null. */
    public InteractionDialogPlugin pending() {
        return pending;
    }

    /** Dismisses whatever is up and stops trying. Idempotent. */
    public void close() {
        InteractionDialogPlugin open = pending;
        pending = null;
        shown = false;
        if (open instanceof CoopDismissableDialog dismissable) {
            dismissable.close();
        }
    }
}
