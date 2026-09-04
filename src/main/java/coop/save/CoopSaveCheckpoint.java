package coop.save;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

/**
 * Coordinated saves (Phase 16). The host owns the canonical save; every time it saves, the guest is
 * told to take its own vanilla autosave, so the two saves stay temporally aligned and an abrupt host
 * crash loses at most the progress since the last host save <em>on both clients</em>.
 *
 * <p>This replaces the cancelled file-export design outright. There is no custom artifact: the guest
 * already owns a real save, and {@code CampaignUIAPI.autosave()} is public, so aligning the two
 * vanilla saves is strictly better than inventing a third format — and the only file surface the
 * script sandbox permits is the {@code SettingsAPI} common folder anyway.
 *
 * <p><b>Deferred execution on the guest is mandatory, not a nicety.</b> {@code autosave()} silently
 * does nothing while a dialog is open, so a checkpoint that lands mid-dialog is parked and retried
 * every frame until the screen clears, then abandoned with a log after {@link #GIVE_UP_MILLIS}. The
 * same shape as {@code CoopPreBattleAutosave}, for the same engine reason.
 *
 * <p>Static plumbing exists because the host's trigger is {@code CoopModPlugin.afterGameSave()} — a
 * {@code ModPlugin} callback with no handle on the campaign pump. The pump registers the live
 * instance; the plugin routes the callback to it.
 */
public final class CoopSaveCheckpoint {

    /** Two saves closer together than this are one event (autosave chasing a manual save). */
    static final long SEND_DEBOUNCE_MILLIS = 2_000L;

    /** How long the guest keeps retrying a checkpoint behind a dialog before giving up. */
    static final long GIVE_UP_MILLIS = 30_000L;

    /** Reason string for the checkpoint sent as a session ends. */
    public static final String REASON_SESSION_END = "session end";

    /** Reason string for the checkpoint sent after an ordinary host save. */
    public static final String REASON_HOST_SAVE = "host save";

    /** How the pump puts a checkpoint on the wire. Role and session gating live on the pump side. */
    public interface Sender {
        /** @return true when the message was actually sent. */
        boolean sendCheckpoint(long checkpointId, String reason);
    }

    /** The engine surface the deferred autosave needs, behind a seam so the deferral is testable. */
    public interface AutosaveTarget {
        /** True when {@code autosave()} would actually be honoured this frame. */
        boolean canAutosaveNow();

        void autosave();
    }

    private static volatile CoopSaveCheckpoint active;

    private Sender sender;
    private long nextCheckpointId;
    private long lastSentAtMillis = Long.MIN_VALUE;

    private boolean autosavePending;
    private String pendingReason = "";
    private long pendingSinceMillis;
    private long lastHandledCheckpointId = Long.MIN_VALUE;

    /** The instance the {@code ModPlugin} save callbacks route to; the newest pump wins. */
    public static void setActive(CoopSaveCheckpoint checkpoint) {
        active = checkpoint;
    }

    /**
     * Host: a save just completed (manual or autosave). Fires on the guest too — the role gate lives
     * in the {@link Sender}, which is what stops a guest's coordinated autosave from echoing a
     * checkpoint straight back at the host.
     */
    public static void notifyLocalGameSaved(String reason) {
        CoopSaveCheckpoint checkpoint = active;
        if (checkpoint != null) {
            checkpoint.onLocalGameSaved(reason, System.currentTimeMillis());
        }
    }

    /**
     * Host: the session is ending gracefully. This is a <em>notice</em>, not a save order — the guest
     * deliberately does not autosave on it (see {@link #onCheckpointReceived}), because the host has
     * not saved either: the engine replaces the sector when another game is loaded and never writes
     * the campaign being left behind.
     */
    public static void notifySessionEnding() {
        CoopSaveCheckpoint checkpoint = active;
        if (checkpoint != null) {
            checkpoint.onLocalGameSaved(REASON_SESSION_END, System.currentTimeMillis());
        }
    }

    public void setSender(Sender sender) {
        this.sender = sender;
    }

    /**
     * Sends a checkpoint unless one went out inside the debounce window.
     *
     * @return true when a checkpoint was sent.
     */
    public boolean onLocalGameSaved(String reason, long nowMillis) {
        if (sender == null) {
            return false;
        }
        if (!shouldSend(lastSentAtMillis, nowMillis)) {
            CoopLog.debug(CoopSaveCheckpoint.class,
                    "Coop save checkpoint suppressed as a duplicate: " + reason);
            return false;
        }
        long checkpointId = ++nextCheckpointId;
        boolean sent = sender.sendCheckpoint(checkpointId, reason == null ? REASON_HOST_SAVE : reason);
        if (sent) {
            lastSentAtMillis = nowMillis;
        } else {
            nextCheckpointId--;
        }
        return sent;
    }

    /** Pure debounce predicate; the MIN_VALUE sentinel means "never sent". */
    static boolean shouldSend(long lastSentAtMillis, long nowMillis) {
        return lastSentAtMillis == Long.MIN_VALUE
                || nowMillis - lastSentAtMillis >= SEND_DEBOUNCE_MILLIS;
    }

    /**
     * Guest: the host saved. Parks an autosave for the first frame the engine will honour it.
     *
     * <p>Duplicates collapse two ways: a checkpoint id already handled is dropped outright (a resend
     * on a flaky link), and a <em>new</em> checkpoint arriving while one is still parked keeps the
     * original deadline rather than extending it — one autosave, one 30-second budget.
     *
     * <p><b>{@link #REASON_SESSION_END} is the one checkpoint that must not save.</b> It is sent from
     * {@code CoopModPlugin.onGameLoad}, after the engine has already swapped the sector out, and the
     * campaign the host just left was never written — Starsector does not autosave the current game
     * when you load another one. Saving here would overwrite the guest's coordinated autosave with one
     * that is <em>ahead</em> of the host's last real save, and the rejoin model has the guest come back
     * by loading exactly that file. Keeping the older, paired save is what keeps the two clients on the
     * same state. It still counts as handled, so a resend is deduplicated, and it never cancels an
     * autosave already parked from a real host save — that one still pairs with a save that exists.
     */
    public void onCheckpointReceived(long checkpointId, String reason, long nowMillis) {
        if (checkpointId == lastHandledCheckpointId) {
            CoopLog.debug(CoopSaveCheckpoint.class,
                    "Coop save checkpoint " + checkpointId + " already handled, ignoring the repeat");
            return;
        }
        lastHandledCheckpointId = checkpointId;
        if (REASON_SESSION_END.equals(reason == null ? "" : reason.trim())) {
            CoopLog.info(CoopSaveCheckpoint.class, "Coop save checkpoint " + checkpointId
                    + " received (" + REASON_SESSION_END + "): the host left the campaign without"
                    + " saving it, so no coordinated autosave is taken. The last coordinated autosave"
                    + " stays as the rejoin point."
                    + (autosavePending ? " The autosave already parked from a real host save is"
                            + " untouched and will still run." : ""));
            return;
        }
        if (autosavePending) {
            CoopLog.debug(CoopSaveCheckpoint.class, "Coop save checkpoint " + checkpointId
                    + " folded into the autosave already waiting for a clear screen");
            return;
        }
        autosavePending = true;
        pendingReason = reason == null || reason.trim().isEmpty() ? REASON_HOST_SAVE : reason.trim();
        pendingSinceMillis = nowMillis;
        CoopLog.info(CoopSaveCheckpoint.class, "Coop save checkpoint " + checkpointId
                + " received (" + pendingReason + "); autosaving as soon as no screen is open");
    }

    /** True while a received checkpoint has not produced an autosave yet. */
    public boolean isAutosavePending() {
        return autosavePending;
    }

    /** Drops a parked autosave without performing it (session end, disconnect, game load). */
    public void cancel() {
        autosavePending = false;
        pendingReason = "";
    }

    /** Forgets everything, including the debounce and duplicate history. */
    public void reset() {
        cancel();
        nextCheckpointId = 0L;
        lastSentAtMillis = Long.MIN_VALUE;
        lastHandledCheckpointId = Long.MIN_VALUE;
    }

    /**
     * Performs a parked autosave once the engine will honour it, or abandons it after
     * {@link #GIVE_UP_MILLIS}. Never throws.
     *
     * @return true when an autosave was performed this call.
     */
    public boolean tick(AutosaveTarget target, long nowMillis) {
        if (!autosavePending || target == null) {
            return false;
        }
        try {
            if (target.canAutosaveNow()) {
                String reason = pendingReason;
                autosavePending = false;
                pendingReason = "";
                // The save index cannot otherwise tell an autosave from a manual one - the engine
                // keeps its autosave flag to itself and hands the mod hooks nothing - and this is one
                // of the two autosaves the mod asks for itself. autosave() runs the whole save inline,
                // so the scope really does bracket beforeGameSave/afterGameSave.
                CoopSaveIndex.beginCoopAutosave();
                try {
                    target.autosave();
                } finally {
                    CoopSaveIndex.endCoopAutosave();
                }
                CoopLog.info(CoopSaveCheckpoint.class,
                        "Coop coordinated autosave performed (" + reason + ")");
                return true;
            }
            if (nowMillis - pendingSinceMillis >= GIVE_UP_MILLIS) {
                CoopLog.warn(CoopSaveCheckpoint.class, "Coop coordinated autosave (" + pendingReason
                        + ") gave up after " + (nowMillis - pendingSinceMillis)
                        + " ms: a screen stayed open the whole time. The two saves are out of step"
                        + " until the next host save.");
                cancel();
            }
            return false;
        } catch (RuntimeException | LinkageError ex) {
            cancel();
            CoopLog.warn(CoopSaveCheckpoint.class, "Coop coordinated autosave failed", ex);
            return false;
        }
    }

    /**
     * The engine-backed target. Split from {@link #tick} so the deferral logic is unit-testable, and
     * the precondition is the same one {@code CoopPreBattleAutosave} learned: the engine silently
     * skips {@code autosave()} unless we are in the campaign state with a UI and no dialog over it.
     */
    public static AutosaveTarget engineTarget(SectorAPI sector) {
        return new AutosaveTarget() {
            @Override
            public boolean canAutosaveNow() {
                CampaignUIAPI ui = uiOrNull();
                if (ui == null) {
                    return false;
                }
                boolean dialogOpen = ui.isShowingDialog() || ui.getCurrentInteractionDialog() != null;
                return !dialogOpen && Global.getCurrentState() == GameState.CAMPAIGN;
            }

            @Override
            public void autosave() {
                CampaignUIAPI ui = uiOrNull();
                if (ui != null) {
                    ui.autosave();
                }
            }

            private CampaignUIAPI uiOrNull() {
                try {
                    return sector == null ? null : sector.getCampaignUI();
                } catch (RuntimeException | LinkageError ignored) {
                    return null;
                }
            }
        };
    }
}
