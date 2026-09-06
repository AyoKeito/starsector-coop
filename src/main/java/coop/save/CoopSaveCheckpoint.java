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
 * every frame until the screen clears. The same shape as {@code CoopPreBattleAutosave}, for the same
 * engine reason.
 *
 * <p><b>0.1.1: the checkpoint waits for the screen, and the host is told.</b> The 0.1.0 build gave
 * the parked autosave thirty seconds and then cancelled it with a warning in the guest's own log.
 * The live smoke found both halves of that wrong. Thirty seconds is nothing — a player reading a
 * market, an outfitting screen, a conversation all outlast it — so the host's save silently went
 * unpaired for the ordinary case rather than the wedged one. And the host, which is the machine that
 * ordered the save, had no way to know: the only trace was on the other player's disk. So the cap is
 * now {@link #SAFETY_CAP_MILLIS}, ten minutes, which is a backstop against a genuinely stuck UI
 * rather than a budget for a player who is reading something; and each parked episode reports itself
 * back over {@link coop.net.CoopMessages.Type#SAVE_CHECKPOINT_RESULT} — once when it has been
 * waiting {@link #DEFER_REPORT_MILLIS}, once when it finally saves, once if the cap runs out.
 *
 * <p>Static plumbing exists because the host's trigger is {@code CoopModPlugin.afterGameSave()} — a
 * {@code ModPlugin} callback with no handle on the campaign pump. The pump registers the live
 * instance; the plugin routes the callback to it.
 */
public final class CoopSaveCheckpoint {

    /** Two saves closer together than this are one event (autosave chasing a manual save). */
    static final long SEND_DEBOUNCE_MILLIS = 2_000L;

    /**
     * The backstop, not a budget: how long a parked autosave may keep retrying before it is written
     * off. Ten minutes because the thing it guards against is a UI that never closes — a modded
     * screen that swallowed its own exit, a dialog left open by a script that threw — and every
     * ordinary reason a screen stays open (reading a market, refitting, a long conversation, walking
     * away from the keyboard) has to fit comfortably underneath it. The 0.1.0 value was 30 s, which
     * did not, and the live smoke lost a save to a dock screen because of it.
     */
    public static final long SAFETY_CAP_MILLIS = 600_000L;

    /**
     * How long a parked autosave waits before it tells the host it is parked. Long enough that the
     * common case — a checkpoint that lands during a one-second screen transition — stays silent,
     * short enough that a player who is going to be a while is announced while the host still
     * remembers pressing save.
     */
    public static final long DEFER_REPORT_MILLIS = 5_000L;

    /** Reason string for the checkpoint sent as a session ends. */
    public static final String REASON_SESSION_END = "session end";

    /** Reason string for the checkpoint sent after an ordinary host save. */
    public static final String REASON_HOST_SAVE = "host save";

    /** How the pump puts a checkpoint on the wire. Role and session gating live on the pump side. */
    public interface Sender {
        /** @return true when the message was actually sent. */
        boolean sendCheckpoint(long checkpointId, String reason);
    }

    /**
     * How the guest tells the host what became of a checkpoint (0.1.1). Role and session gating live
     * on the pump side, exactly as they do for {@link Sender}.
     */
    public interface ResultReporter {
        /**
         * @param outcome one of {@code CoopMessages.CHECKPOINT_RESULT_*}
         * @return true when the report actually reached the wire; false makes this class try again
         *         on a later frame, which is what keeps a report from being lost to one bad frame
         */
        boolean sendCheckpointResult(long checkpointId, String outcome, long waitedMillis);
    }

    /** The engine surface the deferred autosave needs, behind a seam so the deferral is testable. */
    public interface AutosaveTarget {
        /** True when {@code autosave()} would actually be honoured this frame. */
        boolean canAutosaveNow();

        void autosave();
    }

    private static volatile CoopSaveCheckpoint active;

    private Sender sender;
    private ResultReporter resultReporter;
    private long nextCheckpointId;
    private long lastSentAtMillis = Long.MIN_VALUE;

    private boolean autosavePending;
    private String pendingReason = "";
    private long pendingSinceMillis;
    private long pendingCheckpointId;
    /**
     * Per parked <em>episode</em>, not per checkpoint id: a newer checkpoint that supersedes a
     * parked one is the same screen still being open, and telling the host twice about one open
     * screen is noise. It is also what makes the "saved" report conditional — an autosave that never
     * had to be announced does not need to announce that it stopped being announced.
     */
    private boolean deferralReported;
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

    /** Guest side of 0.1.1: where the deferred/saved/abandoned reports go. Null disables them. */
    public void setResultReporter(ResultReporter resultReporter) {
        this.resultReporter = resultReporter;
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
     * on a flaky link), and a <em>new</em> checkpoint arriving while one is still parked
     * <b>supersedes</b> it — the park now carries the newest id and reason, because that is the
     * checkpoint the host is waiting to hear about and reporting against a stale id would name a
     * save the host has already moved past. What it does not move is the deadline: the wait is
     * measured from the start of the parked episode, so a host saving on a timer cannot push
     * {@link #SAFETY_CAP_MILLIS} out forever and the reported {@code waitedMillis} is how long the
     * screen has actually been open.
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
        String parkedReason =
                reason == null || reason.trim().isEmpty() ? REASON_HOST_SAVE : reason.trim();
        if (autosavePending) {
            // Supersede, not fold: same single autosave, same episode clock, newest identity.
            pendingCheckpointId = checkpointId;
            pendingReason = parkedReason;
            CoopLog.debug(CoopSaveCheckpoint.class, "Coop save checkpoint " + checkpointId
                    + " superseded the one already waiting for a clear screen; the wait keeps"
                    + " running from " + pendingSinceMillis);
            return;
        }
        autosavePending = true;
        pendingCheckpointId = checkpointId;
        pendingReason = parkedReason;
        pendingSinceMillis = nowMillis;
        deferralReported = false;
        CoopLog.info(CoopSaveCheckpoint.class, "Coop save checkpoint " + checkpointId
                + " received (" + pendingReason + "); autosaving as soon as no screen is open");
    }

    /** True while a received checkpoint has not produced an autosave yet. */
    public boolean isAutosavePending() {
        return autosavePending;
    }

    /**
     * Drops a parked autosave without performing it (session end, disconnect, game load).
     *
     * <p>Deliberately silent: every caller is a path where the link is going away, so there is
     * nobody left to report to and a report queued against a dying socket is worse than none.
     */
    public void cancel() {
        autosavePending = false;
        pendingReason = "";
        pendingCheckpointId = 0L;
        deferralReported = false;
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
     * {@link #SAFETY_CAP_MILLIS}. Also owns the three reports the host gets (0.1.1). Never throws.
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
                long checkpointId = pendingCheckpointId;
                long waited = Math.max(0L, nowMillis - pendingSinceMillis);
                boolean owedAReport = deferralReported;
                autosavePending = false;
                pendingReason = "";
                pendingCheckpointId = 0L;
                deferralReported = false;
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
                // Only if the host was told to expect a wait. An autosave that ran on the frame it
                // was ordered is the ordinary case, and the ordinary case says nothing.
                if (owedAReport) {
                    report(checkpointId, coop.net.CoopMessages.CHECKPOINT_RESULT_SAVED, waited);
                }
                return true;
            }
            long waited = Math.max(0L, nowMillis - pendingSinceMillis);
            if (waited >= SAFETY_CAP_MILLIS) {
                CoopLog.warn(CoopSaveCheckpoint.class, "Coop coordinated autosave (" + pendingReason
                        + ") gave up after " + waited + " ms: a screen stayed open the whole time."
                        + " The two saves are out of step until the next host save.");
                report(pendingCheckpointId, coop.net.CoopMessages.CHECKPOINT_RESULT_ABANDONED,
                        waited);
                cancel();
                return false;
            }
            if (!deferralReported && waited >= DEFER_REPORT_MILLIS) {
                // Set only on a successful send, so a frame where the transport refused is retried
                // rather than swallowed - the point of the message is that the host learns.
                deferralReported = report(pendingCheckpointId,
                        coop.net.CoopMessages.CHECKPOINT_RESULT_DEFERRED, waited);
                if (deferralReported) {
                    CoopLog.info(CoopSaveCheckpoint.class, "Coop coordinated autosave ("
                            + pendingReason + ") has been waiting " + waited
                            + " ms for a clear screen; told the host it is deferred");
                }
            }
            return false;
        } catch (RuntimeException | LinkageError ex) {
            cancel();
            CoopLog.warn(CoopSaveCheckpoint.class, "Coop coordinated autosave failed", ex);
            return false;
        }
    }

    /**
     * One report to the host, swallowing whatever the transport throws.
     *
     * <p>Total by design: this is bookkeeping about a save, and a reporter that fails must never be
     * able to take the autosave down with it — {@link #tick}'s own catch cancels the checkpoint, and
     * losing a save to a failed <em>notification</em> about that save would be absurd.
     *
     * @return true when the report reached the wire
     */
    private boolean report(long checkpointId, String outcome, long waitedMillis) {
        ResultReporter reporter = resultReporter;
        if (reporter == null) {
            return false;
        }
        try {
            return reporter.sendCheckpointResult(checkpointId, outcome, waitedMillis);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSaveCheckpoint.class,
                    "Coop could not report save checkpoint " + checkpointId + " as " + outcome, ex);
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
