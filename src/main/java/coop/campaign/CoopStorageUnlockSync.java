package coop.campaign;

import java.util.ArrayList;
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

        /** Clears the coop flag; true when it was set. */
        boolean clearFlag(String marketId);

        /** Every market id the coop flag is set for, for the host's session-start baseline. */
        List<String> flaggedMarketIds();
    }

    private final Engine engine;
    private long lastPollMillis;
    private boolean pollSeeded;
    private boolean baselineTaken;
    /**
     * The market the rebuilt-plugin repair was last applied to, so it runs once per market per
     * dialog rather than once a second (red-team P2-7). The repair normally sticks and the next poll
     * reads {@code paid}; it does not stick when {@code StoragePlugin.playerPaidToUnlock} cannot be
     * read at all, or when the storage submarket's plugin is not a {@code StoragePlugin} and
     * {@code unlockPlugin} is a silent no-op. In that state the old code emitted one INFO line per
     * second for as long as the dialog stayed open. Cleared when the dialog closes, so the next dock
     * gets its one attempt.
     */
    private String repairedMarketId;

    public CoopStorageUnlockSync(Engine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Forgets the poll timer and the once-per-session baseline. Called on session teardown. */
    public void reset() {
        lastPollMillis = 0L;
        pollSeeded = false;
        baselineTaken = false;
        repairedMarketId = null;
    }

    /**
     * The host's once-per-session baseline: every market the coop flag is already set for, so a
     * guest that joins a campaign with unlocks already paid inherits them instead of finding every
     * locker shut. Returns the list exactly once per session, and {@link #reset()} — which a
     * reconnect resume now calls — is what makes the next session's first poll say it all again.
     *
     * <p>Two things the caller owns rather than this class. It <b>prunes</b>: a flag whose market
     * this engine cannot resolve is not broadcast (red-team P2-6/P1-3), because the receiver would
     * add its own copy of a dead key and both saves would grow forever. And it sends each surviving
     * entry <b>past the world ledger</b> (red-team P0-1), because after a resume the ledger holds
     * exactly the entries whose sends went into a dead link.
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
            // The dialog is gone (or its target has no market), so the next dock is a fresh chance
            // to repair whatever it lands on.
            repairedMarketId = null;
            return null;
        }
        if (!marketId.equals(repairedMarketId)) {
            repairedMarketId = null;
        }
        boolean flagged = engine.flagSet(marketId);
        boolean paid = engine.pluginPaid(market);
        if (paid && !flagged) {
            engine.setFlag(marketId);
            return marketId;
        }
        if (flagged && !paid && repairedMarketId == null) {
            // The coop crew paid for this locker, but this engine's plugin instance does not know:
            // either the delta arrived while the market did not exist here yet, or the market was
            // rebuilt (mirrored colony / hidden base) and came back with a fresh plugin.
            //
            // Once per market per dialog (P2-7). When the repair works the next poll reads paid and
            // never comes back here; when it cannot work -- an unreadable playerPaidToUnlock, or a
            // storage submarket whose plugin is not a StoragePlugin, both of which make unlockPlugin
            // a no-op -- retrying every second only bought one log line per second.
            repairedMarketId = marketId;
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

    /**
     * Phase 32 addition A: {@code CoopMarketIds} has just learned that the host's
     * {@code hostMarketId} is this engine's {@code localMarketId}, so a flag parked under the host's
     * id can now find its market.
     *
     * <p>The parked flag is the documented outcome of {@link #applyRemote(String)} for a market that
     * does not exist here yet ("flagged unknown market"), and for a hidden base that state can
     * persist: the base <em>does</em> exist locally, it just answers to a different id, so no later
     * rebuild would ever pick the flag up. Moving it is what makes the locker open. No-op unless a
     * flag is actually parked, which is the normal case.
     */
    public void onMarketIdMapped(String hostMarketId, String localMarketId) {
        onMarketIdMapped(hostMarketId, localMarketId, null);
    }

    /**
     * The full form, which also takes the local id this mapping displaced (red-team P1-4).
     *
     * <p>A hidden base destroyed and rebuilt in the same system keeps its {@code (kind, systemId)}
     * identity, so {@link CoopMarketIds#learn} takes its remap branch and hands the same host id a
     * <em>new</em> local id. The flag for that base is by then parked under the <em>old local</em>
     * id, not under the host's — it was moved there the first time the mapping was learned.
     * Migrating only from the host id therefore did nothing: the rebuilt base's locker stayed shut,
     * a dead {@code coop.storageUnlocked:<old local>} key stayed in the save, and the 1 Hz repair
     * branch in {@link #pollDockedUnlock(long)} could not help because it reads the flag under the
     * new id. Nothing short of a fresh session healed it.
     *
     * @param previousLocalMarketId the local id this mapping displaced, or null when it displaced
     *                              none
     */
    public void onMarketIdMapped(String hostMarketId, String localMarketId,
                                 String previousLocalMarketId) {
        if (localMarketId == null || localMarketId.isEmpty()) {
            return;
        }
        List<String> parked = new ArrayList<>(2);
        addParkedFlag(parked, hostMarketId, localMarketId);
        addParkedFlag(parked, previousLocalMarketId, localMarketId);
        if (parked.isEmpty()) {
            return;
        }
        for (String stale : parked) {
            engine.clearFlag(stale);
        }
        engine.setFlag(localMarketId);
        MarketAPI market = engine.findMarket(localMarketId);
        if (market != null) {
            engine.unlockPlugin(market);
        }
        CoopLog.info(CoopStorageUnlockSync.class, "Coop moved STORAGE_UNLOCK flag from market="
                + String.join(",", parked) + " to local market=" + localMarketId
                + (market == null ? " (no local market to open yet)" : ""));
    }

    /** Adds {@code candidate} when it is a real, different id this engine really holds a flag for. */
    private void addParkedFlag(List<String> parked, String candidate, String localMarketId) {
        if (candidate == null || candidate.isEmpty() || candidate.equals(localMarketId)
                || parked.contains(candidate) || !engine.flagSet(candidate)) {
            return;
        }
        parked.add(candidate);
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
            public boolean clearFlag(String marketId) {
                return CoopStorageUnlock.clearFlag(Global.getSector(), marketId);
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
