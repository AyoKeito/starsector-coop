package coop.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.EveryFrameScript;
import coop.util.CoopLog;

/**
 * One dismissable message, shown on the first campaign frame that will take it, then gone.
 *
 * <p>Carries the Phase 31 wrong-campaign warning ({@code coop.save.CoopCampaignGuard}). It is
 * deliberately <em>not</em> one of the {@link CoopDismissableDialog} interaction dialogs the pump
 * drives through {@link CoopDialogController}: those exist because a session-level dialog has options,
 * has to survive being refused the slot for minutes, and has to be closable from the pump. This has
 * one button and one sentence to say once, and {@code CampaignUIAPI.showMessageDialog} is exactly that
 * widget.
 *
 * <p><b>Deferral is mandatory.</b> {@code onGameLoad} runs before the campaign UI will accept
 * anything, and the same rule the mod's autosave paths learned applies here: the UI must exist, no
 * dialog may be covering it, and the game must be in the campaign state. Vanilla's own load-time
 * deferral burns a couple of frames first, so this does too.
 *
 * <p><b>It gives up.</b> A player who loads straight into a conversation, or into combat, must not be
 * shown a load-time warning ten minutes later with no context. After {@link #GIVE_UP_MILLIS} the
 * script retires and leaves the WARN in the log as the record.
 *
 * <p>Total: every engine call is wrapped, and a message that cannot be shown never takes down the
 * frame.
 */
public final class CoopCampaignNotice implements EveryFrameScript {

    /** Frames burned before the first attempt, matching vanilla's load-time deferral recipe. */
    public static final int FRAMES_BEFORE_FIRST_SHOW = 3;

    /** After this long unshown, the notice is stale enough to be worse than nothing. */
    public static final long GIVE_UP_MILLIS = 120_000L;

    private final String message;
    private final long startedAtMillis;

    private int ticks;
    private boolean done;

    CoopCampaignNotice(String message, long startedAtMillis) {
        this.message = message == null ? "" : message;
        this.startedAtMillis = startedAtMillis;
    }

    /**
     * Replaces any pending notice with this one. A blank message installs nothing, so the caller can
     * hand over whatever the guard returned without checking it first.
     */
    public static void install(SectorAPI sector, String message) {
        if (sector == null || message == null || message.trim().isEmpty()) {
            return;
        }
        try {
            sector.removeScriptsOfClass(CoopCampaignNotice.class);
            sector.removeTransientScriptsOfClass(CoopCampaignNotice.class);
            sector.addTransientScript(new CoopCampaignNotice(message, System.currentTimeMillis()));
        } catch (Exception | LinkageError ex) {
            CoopLog.warn(CoopCampaignNotice.class,
                    "Coop could not queue the campaign notice; it is in the log above instead", ex);
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean runWhilePaused() {
        // A fresh load can sit paused, and that is exactly when this should be read.
        return true;
    }

    @Override
    public void advance(float amount) {
        if (done) {
            return;
        }
        if (ticks <= FRAMES_BEFORE_FIRST_SHOW) {
            ticks++;
            return;
        }
        try {
            if (System.currentTimeMillis() - startedAtMillis >= GIVE_UP_MILLIS) {
                done = true;
                CoopLog.warn(CoopCampaignNotice.class,
                        "Coop gave up showing the campaign notice: nothing let a message dialog"
                                + " through in " + (GIVE_UP_MILLIS / 1000L) + " seconds");
                return;
            }
            CampaignUIAPI ui = Global.getSector() == null ? null : Global.getSector().getCampaignUI();
            if (!canShowNow(ui != null,
                    ui != null && (ui.isShowingDialog() || ui.getCurrentInteractionDialog() != null),
                    Global.getCurrentState() == GameState.CAMPAIGN)) {
                return;
            }
            done = true;
            ui.showMessageDialog(message);
        } catch (Exception | LinkageError ex) {
            done = true;
            CoopLog.warn(CoopCampaignNotice.class,
                    "Coop could not show the campaign notice; it is in the log above instead", ex);
        }
    }

    /** The engine's precondition for a message dialog, split out so it can be tested without one. */
    public static boolean canShowNow(boolean uiAvailable, boolean dialogOpen, boolean inCampaign) {
        return uiAvailable && !dialogOpen && inCampaign;
    }

    /** The text this notice is carrying. */
    public String message() {
        return message;
    }
}
