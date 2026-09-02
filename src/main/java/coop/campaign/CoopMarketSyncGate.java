package coop.campaign;

import java.util.List;

/**
 * Phase 20 M6: the guest's "this shop is not canonical yet" gate.
 *
 * <h2>The defect this closes</h2>
 * The guest's market model is snapshot-on-open: vanilla's {@code reportPlayerOpenedMarket} fires when
 * the dock dialog appears, the guest sends {@code MARKET_OPEN}, and the host answers with a
 * {@code MARKET_SNAPSHOT} that <b>replaces</b> the guest's open-submarket stock wholesale
 * ({@code CoopCampaignReplicator.applySnapshotToEngine}). On loopback that reply is back before the
 * player's hand leaves the key. At 200 ms RTT with one retransmitted frame it is 400-600 ms, and the
 * trade tab is one keystroke away from the dock dialog ({@code marketOpenCoreUI} carries the "I"
 * shortcut in vanilla's {@code rules.csv}). Two things go wrong inside that window:
 *
 * <ul>
 *   <li><b>Trading against un-synced stock.</b> The guest's own engine rolled this market's inventory
 *       independently and has been diverging from the host's ever since the host last traded here. A
 *       purchase made before the snapshot buys a hull or a commodity quantity that does not exist on
 *       the host, and the {@code MARKET_TXN} that reports it decrements stock the host never had.</li>
 *   <li><b>The snapshot landing under an open trade screen.</b> The whole reason the model is
 *       snapshot-on-<em>open</em> and never per-frame is so the trade UI is never fought
 *       mid-transaction. A late reply breaks exactly that promise: the replacement strips and re-adds
 *       the submarket's cargo stacks while the player is looking at them.</li>
 * </ul>
 *
 * <h2>The fix, and its limits</h2>
 * While a snapshot is outstanding the dock dialog's trade options are disabled and the dialog says
 * why. Everything else in the dialog still works, the interaction claim is untouched, and the
 * optimistic-open model is unchanged — this gates one screen transition, not the interaction.
 *
 * <p><b>It always ends.</b> {@link #TIMEOUT_MILLIS} releases the options and logs, because a host
 * that has no counterpart market for the id (derelicts, ruins, survey targets — the ordinary case for
 * an unregistered procgen {@code MarketAPI}) never sends a snapshot at all, and a player must never be
 * locked out of a shop by a message that is not coming. The arming predicate keeps that case rare on
 * its own: the gate only arms for a market with an open submarket, i.e. one with stock to be wrong
 * about.
 *
 * <p>Pure state plus the option-id list; {@code CoopCampaignReplicator} owns every engine call.
 */
public final class CoopMarketSyncGate {

    /**
     * How long the trade options stay disabled before the player is let through regardless. Sized as
     * "longer than any plausible round trip, shorter than a player's patience": ~8x a 200 ms WAN RTT
     * with a retransmit, and the same order as the reconnect grace the rest of the stack uses.
     */
    public static final long TIMEOUT_MILLIS = 5000L;

    /**
     * The vanilla dock-dialog options that lead to a submarket screen, from
     * {@code starsector-core/data/campaign/rules.csv} (rules {@code marketOpenCoreUISel},
     * {@code marketOpenCargoSel}, {@code marketOpenFleetSel}, {@code marketOptionTrade*}).
     *
     * <p>Refit is on the list because vanilla's refit screen sells weapons and fighter wings out of
     * the same open-submarket cargo the snapshot replaces. Anything not on this list (leave, comms,
     * bar, colony info) is deliberately left enabled: none of it spends the un-synced stock.
     *
     * <p>An id that is missing from a particular dialog is simply skipped — a modded or colony dialog
     * that does not offer trade has nothing for this gate to disable, which is also how the
     * no-submarket procgen case stays invisible.
     */
    public static final List<String> TRADE_OPTION_IDS = List.of(
            "marketOpenCoreUI",
            "marketOpenCargo",
            "marketOpenFleet",
            "marketOpenRefit",
            "marketTradeCargo",
            "marketTradeShips",
            "marketRefit");

    /** What the message panel says while the snapshot is in flight. */
    public static final String SYNCING_TEXT =
            "Syncing market inventory with the host...";

    private String marketId;
    private long armedAtMillis;
    private boolean announced;
    private boolean timedOut;

    /**
     * The guest just sent {@code MARKET_OPEN} for a market that has stock worth gating.
     *
     * <p>Re-arming for a market that is already pending is a no-op so the elapsed clock keeps running:
     * vanilla can report a market open more than once for one dock (a cargo update re-fires it), and
     * restarting the timeout there would extend the lockout past its budget.
     */
    public void onOpenRequested(String marketId, long nowMillis) {
        if (marketId == null || marketId.equals(this.marketId)) {
            return;
        }
        this.marketId = marketId;
        this.armedAtMillis = nowMillis;
        this.announced = false;
        this.timedOut = false;
    }

    /**
     * The host's snapshot for this market landed (or the dialog closed, or the session ended).
     *
     * @return true when this cleared a live gate, i.e. the caller should re-enable the options.
     */
    public boolean onResolved(String marketId) {
        if (this.marketId == null || marketId == null || !this.marketId.equals(marketId)) {
            return false;
        }
        clear();
        return true;
    }

    /** True while the trade options should be held disabled. */
    public boolean isBlocking(long nowMillis) {
        return marketId != null && nowMillis - armedAtMillis < TIMEOUT_MILLIS;
    }

    /** The market whose snapshot is outstanding, or null. */
    public String pendingMarketId() {
        return marketId;
    }

    /**
     * Per-frame: has this gate just run out of patience? True exactly once per arming, so the caller
     * logs one line and re-enables one time. The gate stays armed after that (so a snapshot arriving
     * later still resolves cleanly) but {@link #isBlocking} is already false.
     */
    public boolean pollTimedOut(long nowMillis) {
        if (marketId == null || timedOut || nowMillis - armedAtMillis < TIMEOUT_MILLIS) {
            return false;
        }
        timedOut = true;
        return true;
    }

    /**
     * Per-frame: should the caller print {@link #SYNCING_TEXT} into the dialog now? True exactly once
     * per arming, and only while the gate is actually blocking — a snapshot that beat the first tick
     * must not leave a stale "syncing" line in a dialog that is already canonical.
     */
    public boolean pollAnnounce(long nowMillis) {
        if (marketId == null || announced || !isBlocking(nowMillis)) {
            return false;
        }
        announced = true;
        return true;
    }

    /** True once the notice has been printed for the current arming. */
    public boolean announced() {
        return announced;
    }

    /** Session reset / disconnect / dialog close. */
    public void clear() {
        marketId = null;
        armedAtMillis = 0L;
        announced = false;
        timedOut = false;
    }
}
