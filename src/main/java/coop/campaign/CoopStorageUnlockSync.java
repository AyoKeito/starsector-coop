package coop.campaign;

import java.util.List;
import java.util.Objects;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import coop.util.CoopLog;

/**
 * Phase 32: makes the 5000-credit storage unlock a coop fact rather than a per-engine one.
 *
 * <p>Vanilla charges the fee inside the dock dialog — {@code StoragePlugin.getDialogOptions}' "Pay"
 * script subtracts the credits and sets the private {@code playerPaidToUnlock}
 * ({@code StoragePlugin.java:126-134}) — and that is the whole record of it. There is no event, no
 * listener and no getter, so nothing on the wire can be driven off the payment itself. This class
 * closes that gap from the other end: while a dialog is open on this engine it re-reads the plugin
 * field once a second and reconciles it with the mod's own per-market flag
 * ({@link CoopStorageUnlock#FLAG_PREFIX}) in both directions.
 *
 * <p><b>Why polling inside the dialog and not just on {@code MARKET_OPEN}.</b> The open message is
 * sent before the player pays. A player who docks, pays and undocks would otherwise carry the unlock
 * home unshared until the <em>next</em> dock at the same market, and the partner would find the
 * locker shut on a market they were told is open. One read per second for as long as a dialog is up
 * is cheap and lands the delta while the paying player is still standing there.
 *
 * <p>The reverse direction matters just as much: the guest's engine rebuilds mirrored colonies and
 * pirate/Pather bases, and a rebuilt market gets a <em>fresh</em> {@code StoragePlugin} whose field
 * is false again. When the coop flag says the market is open and the local plugin disagrees, the
 * plugin is the thing that is wrong and gets rewritten.
 *
 * <p>All engine contact goes through {@link Engine} so the decision logic is unit-testable without a
 * running sector; {@link #liveEngine()} is the one implementation that talks to {@code Global}.
 */
public final class CoopStorageUnlockSync {

    /** How often the open dialog is re-read. One second, as the plan specifies. */
    public static final long POLL_INTERVAL_MILLIS = 1000L;

    /** Everything the reconciler reads or writes, so a test can hand it a fake sector. */
    public interface Engine {

        /**
         * The market behind the local player's open interaction dialog, or null when no dialog is
         * open or its target has no market.
         */
        MarketAPI dialogMarket();

        /** The market with this id in the local economy, or null when this engine has none. */
        MarketAPI findMarket(String marketId);

        /** Whether the local {@code StoragePlugin} reads paid for this market. */
        boolean pluginPaid(MarketAPI market);

        /** Writes {@code setPlayerPaidToUnlock(true)} on the local plugin. */
        void unlockPlugin(MarketAPI market);

        /** Whether the coop flag for this market is set in persistent data. */
        boolean flagSet(String marketId);

        /** Sets the coop flag; true when it was not set before. */
        boolean setFlag(String marketId);

        /** Every market id the coop flag is set for, for the host's session-start baseline. */
        List<String> flaggedMarketIds();
    }

    private final Engine engine;
    private long lastPollMillis;
    private boolean pollSeeded;
    private boolean baselineTaken;

    public CoopStorageUnlockSync(Engine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Forgets the poll timer and the once-per-session baseline. Called on session teardown. */
    public void reset() {
        lastPollMillis = 0L;
        pollSeeded = false;
        baselineTaken = false;
    }

    /**
     * The host's once-per-session baseline: every market the coop flag is already set for, so a
     * guest that joins a campaign with unlocks already paid inherits them instead of finding every
     * locker shut. Returns the list exactly once per session; the receiver's ledger dedups it
     * against anything it already knows.
     */
    public List<String> takeBaseline() {
        if (baselineTaken) {
            return List.of();
        }
        baselineTaken = true;
        List<String> flagged = engine.flaggedMarketIds();
        if (!flagged.isEmpty()) {
            CoopLog.info(CoopStorageUnlockSync.class,
                    "Coop sending STORAGE_UNLOCK baseline markets=" + flagged.size());
        }
        return flagged;
    }

    /**
     * One reconcile pass over the market behind the open dialog, throttled to
     * {@link #POLL_INTERVAL_MILLIS}.
     *
     * @return the market id whose unlock this engine just captured and must report, or null when
     *         there is nothing to send (no dialog, no market, already flagged, or the pass only
     *         repaired a local plugin)
     */
    public String pollDockedUnlock(long nowMillis) {
        if (pollSeeded && nowMillis - lastPollMillis < POLL_INTERVAL_MILLIS) {
            return null;
        }
        pollSeeded = true;
        lastPollMillis = nowMillis;
        MarketAPI market = engine.dialogMarket();
        String marketId = market == null ? null : market.getId();
        if (marketId == null || marketId.isEmpty()) {
            return null;
        }
        boolean flagged = engine.flagSet(marketId);
        boolean paid = engine.pluginPaid(market);
        if (paid && !flagged) {
            engine.setFlag(marketId);
            return marketId;
        }
        if (flagged && !paid) {
            // The coop crew paid for this locker, but this engine's plugin instance does not know:
            // either the delta arrived while the market did not exist here yet, or the market was
            // rebuilt (mirrored colony / hidden base) and came back with a fresh plugin.
            engine.unlockPlugin(market);
            CoopLog.info(CoopStorageUnlockSync.class,
                    "Coop re-opened rebuilt storage plugin market=" + marketId);
        }
        return null;
    }

    /**
     * Applies a received {@code STORAGE_UNLOCK}. The flag is set whether or not the market resolves
     * here: a hidden base or mirrored colony that is rebuilt later reads the flag on its next dialog
     * poll and opens itself, which is the whole reason the flag lives in persistent data rather than
     * on the plugin.
     */
    public void applyRemote(String marketId) {
        if (marketId == null || marketId.isEmpty()) {
            CoopLog.warn(CoopStorageUnlockSync.class, "Coop STORAGE_UNLOCK with no market id");
            return;
        }
        engine.setFlag(marketId);
        MarketAPI market = engine.findMarket(marketId);
        if (market == null) {
            CoopLog.info(CoopStorageUnlockSync.class,
                    "Coop STORAGE_UNLOCK flagged unknown market=" + marketId
                            + " (no such market here yet; a rebuild picks the flag up)");
            return;
        }
        engine.unlockPlugin(market);
        CoopLog.info(CoopStorageUnlockSync.class, "Coop applied STORAGE_UNLOCK market=" + marketId);
    }

    /** The engine seam wired to {@code Global}; every read is null-safe at each step. */
    public static Engine liveEngine() {
        return new Engine() {
            @Override
            public MarketAPI dialogMarket() {
                return currentDialogMarket();
            }

            @Override
            public MarketAPI findMarket(String marketId) {
                SectorAPI sector = Global.getSector();
                if (sector == null || sector.getEconomy() == null || marketId == null) {
                    return null;
                }
                return sector.getEconomy().getMarket(marketId);
            }

            @Override
            public boolean pluginPaid(MarketAPI market) {
                return CoopStorageUnlock.pluginPaid(market);
            }

            @Override
            public void unlockPlugin(MarketAPI market) {
                CoopStorageUnlock.unlockPlugin(market);
            }

            @Override
            public boolean flagSet(String marketId) {
                return CoopStorageUnlock.flagSet(Global.getSector(), marketId);
            }

            @Override
            public boolean setFlag(String marketId) {
                return CoopStorageUnlock.setFlag(Global.getSector(), marketId);
            }

            @Override
            public List<String> flaggedMarketIds() {
                return CoopStorageUnlock.flaggedMarketIds(Global.getSector());
            }
        };
    }

    /**
     * The market behind the local player's open interaction dialog, or null.
     *
     * <p>{@code SectorAPI.getCampaignUI} ({@code SectorAPI.java:80}) &rarr;
     * {@code CampaignUIAPI.getCurrentInteractionDialog} ({@code CampaignUIAPI.java:62}) &rarr;
     * {@code InteractionDialogAPI.getInteractionTarget} ({@code InteractionDialogAPI.java:37}) &rarr;
     * {@code SectorEntityToken.getMarket} ({@code SectorEntityToken.java:37}). Every hop can be null
     * — no UI outside the campaign, no dialog when undocked, no market on a derelict — and a poll
     * that runs every second on both engines is the wrong place to find that out the hard way.
     */
    static MarketAPI currentDialogMarket() {
        try {
            SectorAPI sector = Global.getSector();
            CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
            InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
            SectorEntityToken target = dialog == null ? null : dialog.getInteractionTarget();
            return target == null ? null : target.getMarket();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopStorageUnlockSync.class, "Could not read the open interaction dialog", ex);
            return null;
        }
    }
}
