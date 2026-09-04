package coop.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

/**
 * Pre-battle autosave (Phase 14): crash/disconnect insurance, never a rollback mechanism. Nothing in
 * the coop protocol loads it — {@code cmdLoad()} only opens the picker and {@code LoadGameDialog} is
 * fully obfuscated, so there is no programmatic load (engine facts, plan line ~1322).
 *
 * <p><b>Deferred execution is mandatory, not a convenience.</b> {@code CampaignUIAPI.autosave()}
 * silently does nothing while any dialog is open (engine facts). A request is therefore parked and
 * performed on the first campaign frame where the UI exists, no dialog is open, and the game is in
 * the campaign state.
 *
 * <p><b>Coverage limitation (documented, not a bug).</b> The only path where a genuinely
 * <em>pre</em>-battle autosave is reachable is the host-pushed {@code ENGAGE_GUEST} flow, where the
 * mod owns the moment between "no dialog open" and {@code startBattle}. For a player-initiated
 * engagement the player is inside the vanilla encounter dialog right up to the instant combat
 * starts, so no autosave can land before the fight — and by the time the mod sees the battle (the
 * combat plugin's first frame) the state transition has already happened. No request is made on that
 * path: an autosave that could only run <em>after</em> the battle is not insurance, it is a
 * multi-second freeze for nothing.
 *
 * <p><b>Throttled (2026-08-19, user review).</b> The save is a multi-second hitch right as a hostile
 * bears down — un-vanilla, and per-battle frequency buys little extra insurance. A request is skipped
 * when this class performed an autosave within the last {@link #MIN_INTERVAL_MILLIS}. Imprecision
 * accepted: a manual save doesn't reset the timer (no cheap hook here), so at worst one redundant
 * autosave runs early. Revisit when Phase 16's coordinated saves provide the regular cadence.
 */
public final class CoopPreBattleAutosave {

    /** Skip a requested autosave when one ran this recently. */
    static final long MIN_INTERVAL_MILLIS = 10L * 60L * 1000L;

    private String pendingReason;
    private long lastAutosaveAtMillis = Long.MIN_VALUE;

    /** Pure throttle predicate, split out for tests; the MIN_VALUE sentinel means "never saved". */
    public static boolean isRecentEnough(long lastAtMillis, long nowMillis) {
        return lastAtMillis != Long.MIN_VALUE && nowMillis - lastAtMillis < MIN_INTERVAL_MILLIS;
    }

    /** Queue an autosave. Repeat requests before the save runs collapse into one. */
    public void request(String reason) {
        request(reason, System.currentTimeMillis());
    }

    /** Clock-injectable form: skipped entirely while the throttle window is open. */
    public void request(String reason, long nowMillis) {
        if (isRecentEnough(lastAutosaveAtMillis, nowMillis)) {
            CoopLog.info(CoopPreBattleAutosave.class,
                    "Coop pre-battle autosave skipped (saved recently): " + reason);
            return;
        }
        pendingReason = reason == null || reason.trim().isEmpty() ? "coop battle" : reason.trim();
    }

    /** True while a requested autosave has not been performed yet. */
    public boolean isPending() {
        return pendingReason != null;
    }

    /** Drops a queued autosave without performing it (session end / battle cancelled). */
    public void cancel() {
        pendingReason = null;
    }

    /**
     * Pure precondition, split out for tests: the engine silently skips {@code autosave()} unless we
     * are in the campaign state with a UI and no dialog covering it.
     */
    public static boolean canAutosaveNow(boolean uiAvailable, boolean dialogOpen, boolean inCampaign) {
        return uiAvailable && !dialogOpen && inCampaign;
    }

    /**
     * Performs a pending autosave when the engine will actually honour it. Returns true when a save
     * was performed this call. Never throws.
     */
    public boolean tick(SectorAPI sector) {
        if (pendingReason == null || sector == null) {
            return false;
        }
        try {
            CampaignUIAPI ui = sector.getCampaignUI();
            boolean dialogOpen = ui != null
                    && (ui.isShowingDialog() || ui.getCurrentInteractionDialog() != null);
            if (!canAutosaveNow(ui != null, dialogOpen, Global.getCurrentState() == GameState.CAMPAIGN)) {
                return false;
            }
            String reason = pendingReason;
            pendingReason = null;
            // Tells CoopSaveIndex that the row this produces is an autosave; see its
            // beginCoopAutosave doc for why that cannot be worked out from the hooks themselves.
            coop.save.CoopSaveIndex.beginCoopAutosave();
            try {
                ui.autosave();
            } finally {
                coop.save.CoopSaveIndex.endCoopAutosave();
            }
            lastAutosaveAtMillis = System.currentTimeMillis();
            CoopLog.info(CoopPreBattleAutosave.class, "Coop pre-battle autosave performed: " + reason);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            pendingReason = null;
            CoopLog.warn(CoopPreBattleAutosave.class, "Coop pre-battle autosave failed", ex);
            return false;
        }
    }
}
