package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MonthlyReport;
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.util.MutableValue;
import coop.rewards.CoopRewardSplitter;
import coop.util.CoopLog;

import java.util.Locale;
import java.util.Objects;

/**
 * Phase 24 milestone 3: the monthly colony-income split.
 *
 * <h2>The trap, and the model that avoids it</h2>
 *
 * <p>Both engines run their own complete economy. Since milestone 2 the colonies exist identically on
 * both, and {@code CoreScript.reportEconomyMonthEnd} pays <em>its own local player</em> the whole
 * report total — it filters on {@code market.isPlayerOwned()}, which is a flag on the market, not
 * "owned by this client" ({@code CoreScript.java:1322}, {@code :1119-1127}). So with no intervention
 * each player already banks ~100% of the colony net locally, and a naive "host wires the guest half"
 * would pay the guest 150%.
 *
 * <p><b>Local-half deduction</b> is therefore the model, and no money crosses the wire at all. Each
 * engine reads its own settled monthly report, computes its own colony net, splits it 50/50 through
 * {@link CoopRewardSplitter}, and deducts the half it does not keep from its own player's credits.
 * Credits are per-player local state under the v1 reward rule (salvage, loot and raid spoils already
 * work this way), so there is nothing to transfer; the two clients agree because
 * {@link CoopRewardSplitter} rounds identically and the colonies are replicated. Milestone 2's
 * fingerprint pass verified the determinism this leans on: colony growth and immigration contain no
 * RNG at all.
 *
 * <p>{@code COLONY_INCOME} rides the wire anyway, carrying the host's canonical figure, and the guest
 * uses it for exactly one thing: a log line comparing the two totals. Drift detection, never
 * correction — correcting would mean transferring credits, which is the design this replaces.
 *
 * <h2>Where the number comes from</h2>
 *
 * <p>At the moment {@link EconomyTickListener#reportEconomyMonthEnd()} fires, {@code CoreScript} has
 * already rolled the report over and paid the player ({@code CoreScript.java:1113-1127}), so the
 * settled month is {@link SharedData#getPreviousReport()} and its
 * {@link MonthlyReport#computeTotals()} has already run. {@link #settledColonyTotals} sums the
 * {@code OUTPOSTS} subtree's per-market nodes, identified the same way vanilla's own income panel
 * identifies them — {@code node.custom instanceof MarketAPI} — and skips anything else living there.
 *
 * <p><b>Read-only on purpose.</b> {@code MonthlyReport.getNode}/{@code getColoniesNode}/
 * {@code getMarketNode} <em>create</em> the node they cannot find ({@code MonthlyReport.java:169-176}),
 * so a reader that used them would silently grow the report tree. This walks
 * {@code getRoot().getChildren()} instead.
 *
 * <h2>What is deliberately not counted</h2>
 *
 * <ul>
 *   <li><b>Fleet upkeep</b> (crew, marines, officer salaries) — a sibling {@code FLEET} subtree, and
 *       per-player by construction: it is each client's own fleet.</li>
 *   <li><b>Storage fees at NPC markets</b> — those nodes also carry a {@code MarketAPI} in
 *       {@code custom}, which is exactly why the walk is anchored at {@code OUTPOSTS} and re-checks
 *       {@code isPlayerOwned()}.</li>
 *   <li><b>Admin salaries</b> ({@code MonthlyReport.ADMIN}) — the admins are on each client's own
 *       character data, so this is a personal cost like fleet upkeep, not a colony cost.</li>
 *   <li><b>Custom production and last month's debt</b> — production picks run off a per-engine
 *       {@code Random} and debt is a consequence of the local wallet.</li>
 * </ul>
 */
public final class CoopColonyIncome {

    private CoopColonyIncome() {
    }

    /**
     * One settled month's colony figures.
     *
     * @param colonyCount how many player-owned market nodes were counted. On the wire so a drift line
     *                    can say whether the two engines even agree on the colony set.
     */
    public record MonthTotals(float income, float upkeep, int colonyCount) {

        public static final MonthTotals EMPTY = new MonthTotals(0f, 0f, 0);

        public float net() {
            return income - upkeep;
        }

        /** True when there is nothing to split and nothing worth telling the player about. */
        public boolean isSilent() {
            return colonyCount == 0 && Math.round(net()) == 0L;
        }
    }

    // ---- Pure computation ----------------------------------------------------------------------

    /**
     * Sums the player-colony nodes of a settled monthly report. Never mutates the report.
     *
     * <p>{@code computeTotals} has already run by the time this is called on the live path, but a
     * report handed in by a test may not have had it; calling it again is idempotent
     * ({@code MonthlyReport.java:129-141} resets each node's totals before recursing), so this does
     * not call it and instead documents that the caller owns that.
     */
    public static MonthTotals settledColonyTotals(MonthlyReport report) {
        if (report == null || report.getRoot() == null) {
            return MonthTotals.EMPTY;
        }
        MonthlyReport.FDNode colonies = report.getRoot().getChildren().get(MonthlyReport.OUTPOSTS);
        if (colonies == null) {
            return MonthTotals.EMPTY;
        }
        float income = 0f;
        float upkeep = 0f;
        int count = 0;
        for (MonthlyReport.FDNode node : colonies.getChildren().values()) {
            if (node == null || !(node.custom instanceof MarketAPI market)) {
                // ADMIN, PRODUCTION, RESTOCKING and the intel payment nodes live here too, tagged
                // with a String rather than a market. None of them is colony income.
                continue;
            }
            if (!market.isPlayerOwned()) {
                continue;
            }
            income += node.totalIncome;
            upkeep += node.totalUpkeep;
            count++;
        }
        return new MonthTotals(income, upkeep, count);
    }

    /**
     * The campaign banner both clients post. ASCII only: the campaign message font is not a place to
     * discover an encoding problem.
     *
     * <p>Worded from the local player's point of view, because that is what the number means — the
     * peer's engine is doing the identical arithmetic on its identical colonies at the same moment.
     */
    public static String splitBanner(CoopRewardSplitter.Split split) {
        Objects.requireNonNull(split, "split");
        long total = split.total();
        if (total >= 0) {
            return "Coop: colony income split - kept " + credits(split.localShare())
                    + " of " + credits(total) + " credits.";
        }
        return "Coop: colony losses split - paid " + credits(-split.localShare())
                + " of " + credits(-total) + " credits.";
    }

    /** Grouping in the ROOT locale, so the separator is always an ASCII comma. */
    static String credits(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** The drift line the guest logs when it can compare its own month against the host's. */
    public static String driftLine(MonthTotals local, float hostNet, long hostColonyCount) {
        float localNet = local.net();
        return "Coop colony income: local net=" + Math.round(localNet)
                + " colonies=" + local.colonyCount()
                + " host net=" + Math.round(hostNet) + " colonies=" + hostColonyCount
                + " drift=" + Math.round(localNet - hostNet);
    }

    // ---- Capture -------------------------------------------------------------------------------

    /** What the month-end listener needs from the replicator; the replicator implements it. */
    public interface Sink {
        /**
         * False when this client must not split: no active session, or the applier is mid-replay. The
         * replay guard matters less here than elsewhere (nothing we apply drives an economy month) but
         * the gate is the same one every other Phase 24 capture uses, and keeping it uniform is worth
         * more than the one branch it saves.
         */
        boolean shouldSplitColonyIncome();

        void onColonyMonthEnd(MonthTotals totals);
    }

    /**
     * Vanilla's month-end hook, registered through the listener manager.
     *
     * <p>The replicator already sees economy <em>ticks</em> through the {@code CampaignEventListener}
     * path, but that interface has no month-end callback at all — {@code reportEconomyMonthEnd} exists
     * only on {@link EconomyTickListener} ({@code ListenerUtil.java:126-129}), which dispatches through
     * the listener manager. Hence the separate registration.
     *
     * <p><b>The timestamp guard is not decoration.</b> {@code CoreScript} returns early without
     * rolling the report over while the tutorial is in progress ({@code CoreScript.java:1074-1076}),
     * so the "settled" report can be a month that was already settled and split. Every rollover stamps
     * the report with the campaign clock ({@code CoreScript.java:1116}), so refusing to process a
     * timestamp twice makes the double-split impossible.
     */
    public static final class MonthEndCapture implements EconomyTickListener {
        private final Sink sink;
        private long lastProcessedTimestamp;
        private boolean seenAny;

        public MonthEndCapture(Sink sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        @Override
        public void reportEconomyTick(int iterIndex) {
            // Nothing: mid-month iterations accumulate into a report that has not been settled yet.
        }

        @Override
        public void reportEconomyMonthEnd() {
            if (!sink.shouldSplitColonyIncome()) {
                return;
            }
            try {
                MonthlyReport report = settledReport();
                if (report == null) {
                    return;
                }
                long timestamp = report.getTimestamp();
                if (seenAny && timestamp == lastProcessedTimestamp) {
                    CoopLog.info(CoopColonyIncome.class,
                            "Coop skipped a colony-income split: the settled report is the one already"
                                    + " split (timestamp " + timestamp + ")");
                    return;
                }
                seenAny = true;
                lastProcessedTimestamp = timestamp;
                sink.onColonyMonthEnd(settledColonyTotals(report));
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopColonyIncome.class, "Failed to capture coop colony month end", ex);
            }
        }

        /** Session teardown: the next session must be free to split its first month. */
        public void reset() {
            lastProcessedTimestamp = 0L;
            seenAny = false;
        }
    }

    /** The month vanilla just settled and paid out. Null when there is no campaign to read. */
    static MonthlyReport settledReport() {
        try {
            if (Global.getSector() == null) {
                return null;
            }
            SharedData data = SharedData.getData();
            return data == null ? null : data.getPreviousReport();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyIncome.class, "Could not read the settled coop monthly report", ex);
            return null;
        }
    }

    // ---- Apply ---------------------------------------------------------------------------------

    /**
     * Takes the peer's half out of the local player's wallet.
     *
     * <p>A negative amount is a refund: vanilla already charged this client the whole of a losing
     * colony month, so half of it comes back and each player eats half the loss.
     *
     * <p>Clamped at zero, exactly as vanilla clamps its own month-end payment
     * ({@code CoreScript.java:1123-1127}). Vanilla never leaves the player with negative credits, and
     * a mod that did would be a visible break rather than a balance choice. The clamp costs nothing:
     * no credits are being transferred anywhere, so a broke player paying less than half is money that
     * simply is not created.
     *
     * @return the amount actually removed, which is less than {@code amount} only when the clamp bit.
     */
    public static long deductFromLocalPlayer(long amount) {
        if (amount == 0L) {
            return 0L;
        }
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI fleet = sector == null ? null : sector.getPlayerFleet();
            if (fleet == null || fleet.getCargo() == null) {
                return 0L;
            }
            MutableValue credits = fleet.getCargo().getCredits();
            if (credits == null) {
                return 0L;
            }
            float before = credits.get();
            float after = before - amount;
            if (after < 0f) {
                after = 0f;
            }
            credits.set(after);
            return Math.round(before - after);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyIncome.class, "Failed to apply the coop colony income split", ex);
            return 0L;
        }
    }
}
