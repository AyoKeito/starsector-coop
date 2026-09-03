package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.util.function.LongSupplier;

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

    /**
     * How long a controller waits before trying again after {@code showInteractionDialog} threw.
     *
     * <p>Phase 21 red-team: the old code dropped {@code pending} on the first throw, which retired
     * the dialog for the rest of the session - and for the lobby that is a world held paused behind a
     * screen that will never come back, because the pump only rebuilt the plugin while its field was
     * null. The request now survives the throw; this backoff is what keeps a UI that throws every
     * frame from being asked sixty times a second.
     */
    public static final long RETRY_BACKOFF_MILLIS = 1_000L;

    private final String kind;
    private final LongSupplier clock;

    private InteractionDialogPlugin pending;
    private boolean shown;
    private boolean openFailureLogged;
    private int ticks;
    /** {@link Long#MIN_VALUE} = no backoff running. */
    private long nextAttemptAtMillis = Long.MIN_VALUE;

    /** @param kind one word for the log lines ("reconnect", "lobby", "connecting") */
    public CoopDialogController(String kind) {
        this(kind, System::currentTimeMillis);
    }

    /**
     * @param kind  one word for the log lines ("reconnect", "lobby", "connecting")
     * @param clock wall clock in millis, for the post-throw retry backoff; the pump passes its own
     */
    public CoopDialogController(String kind, LongSupplier clock) {
        this.kind = kind == null || kind.trim().isEmpty() ? "coop" : kind.trim();
        this.clock = clock == null ? System::currentTimeMillis : clock;
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
        nextAttemptAtMillis = Long.MIN_VALUE;
    }

    /** Frame tick: one {@code showInteractionDialog} attempt while a dialog is wanted but not up. */
    public void tick() {
        if (ticks <= FRAMES_BEFORE_FIRST_SHOW) {
            ticks++;
        }
        if (pending == null || ticks <= FRAMES_BEFORE_FIRST_SHOW) {
            return;
        }
        long now = clock.getAsLong();
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui == null) {
                return;
            }
            if (shown) {
                if (!lostTheSlot(ui)) {
                    return;
                }
                // Something else took the slot out from under us. Fall through and start asking for
                // it back, exactly as if the first show had been refused.
                shown = false;
            }
            if (nextAttemptAtMillis != Long.MIN_VALUE && now < nextAttemptAtMillis) {
                return;
            }
            // Null interaction target: these dialogs are about the session, not about anything in the
            // sector, and there is no entity it would make sense to point at.
            shown = ui.showInteractionDialog(pending, null);
        } catch (Throwable ex) {
            // The request is kept: dropping it retired the dialog for the whole session, and the
            // lobby's is the one holding the world paused. Backed off so a UI that throws every frame
            // is asked once a second, and the WARN stays once per request either way - the caller has
            // already posted a feed banner carrying the same message.
            nextAttemptAtMillis = now + RETRY_BACKOFF_MILLIS;
            if (!openFailureLogged) {
                openFailureLogged = true;
                CoopLog.warn(CoopDialogController.class,
                        "Coop could not open the " + kind + " dialog; retrying behind the feed banner", ex);
            }
        }
    }

    /**
     * Whether the engine is showing somebody else's dialog in the slot we believe we hold.
     *
     * <p>Deliberately conservative: a null current dialog, a null plugin, or an API that will not
     * answer all read as "no evidence". {@code showInteractionDialog} returning true a frame before
     * the engine publishes the dialog is a real ordering, and treating that as a lost slot would
     * re-show every frame.
     */
    private boolean lostTheSlot(CampaignUIAPI ui) {
        try {
            InteractionDialogAPI current = ui.getCurrentInteractionDialog();
            if (current == null) {
                return false;
            }
            InteractionDialogPlugin plugin = current.getPlugin();
            return plugin != null && plugin != pending;
        } catch (Throwable ignored) {
            return false;
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
        nextAttemptAtMillis = Long.MIN_VALUE;
        if (open instanceof CoopDismissableDialog dismissable) {
            dismissable.close();
        }
    }
}
