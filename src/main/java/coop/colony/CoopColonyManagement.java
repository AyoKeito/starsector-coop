package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import coop.campaign.CoopDelimited;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 24 milestone 3: replication of colony <em>management</em> — the industries, construction
 * queue and colony toggles either player edits from their own client.
 *
 * <p><b>Model: diff on close, ship absolute state.</b> Opening a player-owned market snapshots its
 * management state; closing it snapshots again. If nothing changed, nothing is sent. If something
 * changed, the whole post-close state ships as one {@code COLONY_MGMT} — not the diff. The diff only
 * decides <em>whether</em> to send; the payload is absolute, which makes a duplicate delivery, the
 * host's rebroadcast and a late arrival all idempotent, and means a mirror that had drifted converges
 * on the next edit instead of compounding.
 *
 * <p><b>Concurrency is solved by construction, not by this class.</b> The Phase 10 interaction gate is
 * a global first-come lockout on dialogs, so the two players are never inside colony screens at the
 * same time. There is no merge, no conflict resolution and no locking here on purpose.
 *
 * <p><b>Why the construction queue is the load-bearing field.</b> Vanilla's build button does not add
 * an industry — it appends to {@code market.getConstructionQueue()} and charges the player
 * ({@code IndustryListPanel.dialogDismissed}). The industry only appears later, when
 * {@code Market.advance} sees an idle queue and calls
 * {@code BaseIndustry.buildNextInQueue} ({@code BaseIndustry.java:606-652}). So a replicated queue
 * makes the mirror start the same construction on its own, through its own vanilla code, with its own
 * timing — which is exactly the "replicate outcomes, not RNG" rule applied to a deterministic
 * process. The credits are not replicated: the queue item's {@code cost} is what the acting player
 * already paid out of their own wallet, and wallets are per-player in this mod.
 *
 * <p><b>Build progress is deliberately not on the wire.</b> {@code Industry.getBuildOrUpgradeProgress}
 * returns a 0..1 fraction that reads {@code 0} whenever the industry is disrupted
 * ({@code BaseIndustry.java:491-498}), the absolute-days field is only reachable through a
 * {@code BaseIndustry} cast, and the {@code buildTime} field it is measured against is not readable at
 * all during an upgrade ({@code BaseIndustry.getBuildTime} returns the <em>spec</em> value, not the
 * field). Both engines start the same build from the same queue and run the same {@code buildTime},
 * so the drift is bounded by how far apart they started it — and it self-heals the moment either side
 * finishes, because a finished industry in the report forces the other side to finish too. Accepted
 * divergence, documented in {@code docs/starsector-runtime-limitations.md}.
 *
 * <p><b>Not captured: the admin.</b> {@code Market.getAdmin()} is not a pure read — it fabricates a
 * default governor when there is none and swaps in the player person on a player-owned market, so a
 * snapshot pass would itself mutate the market. Writing it back is worse: {@code setAdmin} needs a
 * live {@code PersonAPI} <em>instance</em> (ids are not accepted and {@code AdminData} compares by
 * reference), plus a matching entry in {@code CharacterDataAPI.getAdmins()} with its market
 * back-pointer set, or the admin-assignment UI desyncs and the same governor can be assigned twice.
 * This mod does not replicate the person graph, so half-replicating the admin would be worse than not
 * replicating it. Each player assigns their own governors; the market's own state is what converges.
 *
 * <p><b>Not captured: the tariff.</b> It is faction level — the player's setting lives on
 * {@code FactionSpecAPI.setTariffFraction} and every market seeds a {@code MutableStat} from it — so
 * it is not colony state at all.
 *
 * <p><b>Not captured: cargo, stockpiles and conditions.</b> Phase 12's market channel owns the first
 * two and milestone 1 / milestone 2 / the DECIV world-delta own the third.
 */
public final class CoopColonyManagement {

    private CoopColonyManagement() {
    }

    /** What an industry is doing right now, as far as the wire is concerned. */
    public enum BuildState {
        /** Finished and (disruption aside) functional. */
        NONE,
        /** Being built for the first time. */
        BUILDING,
        /** Being upgraded to {@link IndustryState#upgradeId()}; still functional meanwhile. */
        UPGRADING
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final char RECORD_SEPARATOR = '\n';
    private static final String HEADER_TAG = "H";
    private static final String INDUSTRY_TAG = "I";
    /**
     * Package-visible because {@link CoopColonySync} carries the same {@link QueueItem} records on its
     * {@code COLONY_FOUNDED} payload and decodes them with the same tag. One record shape, one codec.
     */
    static final String QUEUE_TAG = "Q";
    private static final int HEADER_FIELDS = 8;
    private static final int INDUSTRY_FIELDS = 7;
    private static final int QUEUE_FIELDS = 3;

    /**
     * One industry's replicated state. Every field is an absolute read, so applying twice writes the
     * same values.
     *
     * @param aiCoreId      installed core commodity id, empty for none. The colony screen's install
     *                      dialog writes exactly this one field ({@code setAICoreId}); all its other
     *                      work is moving the core between cargoes, which is the acting player's own
     *                      inventory and stays local.
     * @param improved      the story-point improvement. Only the flag replicates — the story points
     *                      are spent by whoever spent them.
     * @param buildState    see {@link BuildState}.
     * @param upgradeId     the spec this industry is upgrading <em>into</em>, empty unless
     *                      {@link BuildState#UPGRADING}. On the wire because the mirror needs it to
     *                      recognise an upgrade it has already finished: the finished upgrade is a
     *                      <em>different industry id</em>, so without this the reconcile would see the
     *                      reported id as missing, re-add it, and delete the upgraded one.
     * @param specialItemId installed special item, empty for none.
     */
    public record IndustryState(String industryId, String aiCoreId, boolean improved,
                                BuildState buildState, String upgradeId, String specialItemId,
                                String specialItemData) {

        public IndustryState {
            industryId = requireText(industryId, "industryId");
            aiCoreId = CoopDelimited.normalize(aiCoreId);
            buildState = buildState == null ? BuildState.NONE : buildState;
            upgradeId = CoopDelimited.normalize(upgradeId);
            specialItemId = CoopDelimited.normalize(specialItemId);
            specialItemData = CoopDelimited.normalize(specialItemData);
        }

        String encode() {
            return CoopDelimited.field(INDUSTRY_TAG)
                    + FIELD_SEPARATOR + CoopDelimited.field(industryId)
                    + FIELD_SEPARATOR + CoopDelimited.field(aiCoreId)
                    + FIELD_SEPARATOR + improved
                    + FIELD_SEPARATOR + CoopDelimited.field(buildState.name())
                    + FIELD_SEPARATOR + CoopDelimited.field(upgradeId)
                    + FIELD_SEPARATOR + CoopDelimited.field(specialItemId)
                    + FIELD_SEPARATOR + CoopDelimited.field(specialItemData);
        }

        static IndustryState decode(List<String> fields) {
            if (fields.size() != INDUSTRY_FIELDS + 1) {
                throw new IllegalArgumentException(
                        "Expected " + (INDUSTRY_FIELDS + 1) + " colony industry fields, got "
                                + fields.size());
            }
            return new IndustryState(fields.get(1), fields.get(2),
                    Boolean.parseBoolean(fields.get(3)), parseBuildState(fields.get(4)),
                    fields.get(5), fields.get(6), fields.get(7));
        }
    }

    /**
     * One entry of the construction queue, in order.
     *
     * @param cost what the acting player paid for it. Carried because vanilla refunds exactly this
     *             number when the queue entry is cancelled ({@code ConstructionQueue.java:11}) and
     *             because {@code buildNextInQueue} stamps it onto the industry as its build-cost
     *             override ({@code BaseIndustry.java:640}), so a mirror with the wrong cost would
     *             quote the wrong shutdown refund. It is not a credit transfer.
     */
    public record QueueItem(String industryId, int cost) {

        public QueueItem {
            industryId = requireText(industryId, "industryId");
        }

        String encode() {
            return CoopDelimited.field(QUEUE_TAG)
                    + FIELD_SEPARATOR + CoopDelimited.field(industryId)
                    + FIELD_SEPARATOR + cost;
        }

        static QueueItem decode(List<String> fields) {
            if (fields.size() != QUEUE_FIELDS) {
                throw new IllegalArgumentException(
                        "Expected " + QUEUE_FIELDS + " colony queue fields, got " + fields.size());
            }
            return new QueueItem(fields.get(1), parseInt(fields.get(2), "queue cost"));
        }
    }

    /**
     * The whole management state of one player-owned market.
     *
     * @param reportId dedup key, {@code actingPlayerId + ":" + monotonic counter}, session scoped like
     *                 the {@link Ledger} that holds it.
     */
    public record State(String reportId, String marketId, String actingPlayerId, boolean freePort,
                        boolean immigrationClosed, boolean immigrationIncentives,
                        boolean useStockpilesForShortages, List<IndustryState> industries,
                        List<QueueItem> queue) {

        public State {
            reportId = requireText(reportId, "reportId");
            marketId = requireText(marketId, "marketId");
            actingPlayerId = CoopDelimited.normalize(actingPlayerId);
            industries = industries == null ? List.of() : List.copyOf(industries);
            queue = queue == null ? List.of() : List.copyOf(queue);
        }

        /**
         * True when this describes the same colony state as {@code other}, ignoring who reported it
         * and when. This is the diff: the open-time snapshot is compared against the close-time one
         * and only a difference produces a message.
         */
        public boolean sameStateAs(State other) {
            return other != null
                    && marketId.equals(other.marketId)
                    && freePort == other.freePort
                    && immigrationClosed == other.immigrationClosed
                    && immigrationIncentives == other.immigrationIncentives
                    && useStockpilesForShortages == other.useStockpilesForShortages
                    && industries.equals(other.industries)
                    && queue.equals(other.queue);
        }

        /** The same state under a new report id; used to mint the outbound report from a snapshot. */
        public State withReportId(String newReportId) {
            return new State(newReportId, marketId, actingPlayerId, freePort, immigrationClosed,
                    immigrationIncentives, useStockpilesForShortages, industries, queue);
        }

        /**
         * The whole state as one self-contained delimited blob: the envelope's flat JSON parser has no
         * arrays, so list-shaped bodies ship as a single opaque string (the
         * {@code CoopBaseRecord.encodeSet} convention). Header line first, then one line per industry,
         * then one per queue entry <em>in queue order</em> — the order is the build order and has to
         * survive the wire.
         */
        public String encode() {
            StringBuilder out = new StringBuilder(160);
            out.append(CoopDelimited.field(HEADER_TAG))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(reportId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(marketId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(actingPlayerId))
                    .append(FIELD_SEPARATOR).append(freePort)
                    .append(FIELD_SEPARATOR).append(immigrationClosed)
                    .append(FIELD_SEPARATOR).append(immigrationIncentives)
                    .append(FIELD_SEPARATOR).append(useStockpilesForShortages);
            for (IndustryState industry : industries) {
                out.append(RECORD_SEPARATOR).append(industry.encode());
            }
            for (QueueItem item : queue) {
                out.append(RECORD_SEPARATOR).append(item.encode());
            }
            return out.toString();
        }
    }

    public static State decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split(String.valueOf(RECORD_SEPARATOR), -1);
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IllegalArgumentException("Empty colony management payload");
        }
        List<String> header = CoopDelimited.split(lines[0]);
        if (header.size() != HEADER_FIELDS || !HEADER_TAG.equals(header.get(0))) {
            throw new IllegalArgumentException("Malformed colony management header");
        }
        List<IndustryState> industries = new ArrayList<>();
        List<QueueItem> queue = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            List<String> fields = CoopDelimited.split(lines[i]);
            switch (fields.get(0)) {
                case INDUSTRY_TAG -> industries.add(IndustryState.decode(fields));
                case QUEUE_TAG -> queue.add(QueueItem.decode(fields));
                default -> throw new IllegalArgumentException(
                        "Unknown colony management record tag: " + fields.get(0));
            }
        }
        return new State(header.get(1), header.get(2), header.get(3),
                Boolean.parseBoolean(header.get(4)), Boolean.parseBoolean(header.get(5)),
                Boolean.parseBoolean(header.get(6)), Boolean.parseBoolean(header.get(7)),
                industries, queue);
    }

    private static BuildState parseBuildState(String raw) {
        try {
            return BuildState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown colony build state: " + raw, ex);
        }
    }

    private static int parseInt(String raw, String fieldName) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed colony " + fieldName + ": " + raw, ex);
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    // ---- Dedup ---------------------------------------------------------------------------------

    /**
     * Session-scoped record of which management reports have been applied, keyed by
     * {@link State#reportId}.
     *
     * <p>Set-based like the raid ledger rather than latest-wins like the lifecycle one. A management
     * report is not a transition between two states a planet oscillates through; it is a fresh
     * absolute snapshot with a fresh id every time, so "have I already applied this exact report" is
     * the whole question. Its job is to absorb the host's verbatim rebroadcast on the originator and
     * any duplicate delivery — and even if it failed to, applying an absolute state twice is a no-op.
     */
    public static final class Ledger {
        private final Set<String> applied = new LinkedHashSet<>();

        /** Returns true only the first time this report id is seen. */
        public boolean apply(State state) {
            Objects.requireNonNull(state, "state");
            return applied.add(state.reportId());
        }

        public boolean isApplied(String reportId) {
            return applied.contains(requireText(reportId, "reportId"));
        }

        public int size() {
            return applied.size();
        }

        public void clear() {
            applied.clear();
        }
    }

    // ---- Capture -------------------------------------------------------------------------------

    /**
     * Snapshots a market's management state. The {@code reportId} is a caller-supplied label: an
     * open-time baseline uses {@link #BASELINE_REPORT_ID} and never leaves this client.
     */
    public static State capture(String reportId, String actingPlayerId, MarketAPI market) {
        Objects.requireNonNull(market, "market");
        List<IndustryState> industries = new ArrayList<>();
        List<Industry> live = market.getIndustries();
        if (live != null) {
            for (Industry industry : live) {
                if (industry == null || industry.getId() == null) {
                    continue;
                }
                industries.add(captureIndustry(industry));
            }
        }
        return new State(reportId, market.getId(), actingPlayerId, market.isFreePort(),
                market.isImmigrationClosed(), market.isImmigrationIncentivesOn(),
                market.isUseStockpilesForShortages(), industries, captureQueue(market));
    }

    /**
     * The market's construction queue as ordered wire records.
     *
     * <p>Shared with {@link CoopColonySync}: vanilla colonization auto-queues a spaceport the player
     * never ordered, so a {@code COLONY_FOUNDED} has to carry a queue too and reads it with this.
     */
    static List<QueueItem> captureQueue(MarketAPI market) {
        List<QueueItem> queue = new ArrayList<>();
        ConstructionQueue constructionQueue = market.getConstructionQueue();
        if (constructionQueue != null && constructionQueue.getItems() != null) {
            for (ConstructionQueue.ConstructionQueueItem item : constructionQueue.getItems()) {
                if (item != null && item.id != null && !item.id.isBlank()) {
                    queue.add(new QueueItem(item.id, item.cost));
                }
            }
        }
        return queue;
    }

    /** The report id an open-time baseline carries; it is local-only and never encoded. */
    public static final String BASELINE_REPORT_ID = "baseline";

    private static IndustryState captureIndustry(Industry industry) {
        BuildState state;
        if (industry.isUpgrading()) {
            state = BuildState.UPGRADING;
        } else if (industry.isBuilding()) {
            state = BuildState.BUILDING;
        } else {
            state = BuildState.NONE;
        }
        String upgradeId = "";
        if (state == BuildState.UPGRADING && industry.getSpec() != null) {
            upgradeId = industry.getSpec().getUpgrade();
        }
        SpecialItemData special = industry.getSpecialItem();
        return new IndustryState(industry.getId(), industry.getAICoreId(), industry.isImproved(),
                state, upgradeId, special == null ? null : special.getId(),
                special == null ? null : special.getData());
    }

    /**
     * The open/close bookkeeping. One baseline per market id, taken when the local player opens a
     * player-owned market and consumed when they close it.
     *
     * <p>No baseline means no report. That is deliberate: without an open-time snapshot there is
     * nothing to diff against, and shipping the state anyway would let any close of a colony screen
     * this client never opened (a reconnect mid-screen, a synthesised dialog) overwrite the peer's
     * canonical market with whatever this client happened to hold.
     */
    public static final class Diff {
        private final Map<String, State> baselines = new LinkedHashMap<>();
        private long counter;

        /** Local player opened a market; remember what it looked like. */
        public void onOpened(String actingPlayerId, MarketAPI market) {
            if (!isManaged(market)) {
                return;
            }
            baselines.put(market.getId(), capture(BASELINE_REPORT_ID, actingPlayerId, market));
        }

        /**
         * Local player closed a market. Returns the report to send, or {@code null} when nothing
         * changed, the market is not a player colony, or there was no baseline.
         */
        public State onClosed(String actingPlayerId, MarketAPI market) {
            if (market == null || market.getId() == null) {
                return null;
            }
            State baseline = baselines.remove(market.getId());
            if (baseline == null || !isManaged(market)) {
                return null;
            }
            State current = capture(BASELINE_REPORT_ID, actingPlayerId, market);
            if (current.sameStateAs(baseline)) {
                return null;
            }
            return current.withReportId(nextReportId(actingPlayerId));
        }

        private String nextReportId(String actingPlayerId) {
            return (actingPlayerId == null || actingPlayerId.isBlank() ? "local" : actingPlayerId)
                    + ":" + (++counter);
        }

        /** Session teardown: ids restart and no baseline may survive into the next session. */
        public void reset() {
            counter = 0;
            baselines.clear();
        }

        /** Test/diagnostic seam: how many markets are open with a baseline held. */
        public int baselineCount() {
            return baselines.size();
        }
    }

    /** Only player colonies are managed; every other market is Phase 12's business. */
    static boolean isManaged(MarketAPI market) {
        return market != null && market.getId() != null && market.isPlayerOwned()
                && !market.isPlanetConditionMarketOnly();
    }

    // ---- Apply ---------------------------------------------------------------------------------

    /**
     * Resolves the report's market on this engine and applies it. A missing market is logged and
     * dropped, never thrown: one malformed message must not kill the net pump.
     */
    public static void applyToEngine(State state) {
        Objects.requireNonNull(state, "state");
        MarketAPI market = resolveMarket(state.marketId());
        if (market == null) {
            CoopLog.warn(CoopColonyManagement.class, "Coop COLONY_MGMT names market "
                    + state.marketId() + ", which does not exist here; dropped");
            return;
        }
        applyToMarket(market, state);
    }

    /**
     * Economy first, then the planet entity. The inverse of the lifecycle resolver's order, and for
     * the inverse reason: a managed colony is by definition in the economy, and the fallback is only
     * there for the frame between a {@code COLONY_FOUNDED} landing and the economy registration it
     * performs.
     */
    static MarketAPI resolveMarket(String marketId) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return null;
            }
            MarketAPI market = sector.getEconomy() == null ? null : sector.getEconomy().getMarket(marketId);
            if (market != null) {
                return market;
            }
            // "market_<planetId>" is the gen-time naming every planet-condition market follows
            // (PlanetConditionGenerator.java:134), so this recovers a colony the economy has not
            // registered yet without needing the planet id on the wire.
            if (marketId != null && marketId.startsWith("market_")) {
                SectorEntityToken entity = sector.getEntityById(marketId.substring("market_".length()));
                if (entity != null) {
                    return entity.getMarket();
                }
            }
            return null;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyManagement.class,
                    "Could not resolve coop colony market " + marketId, ex);
            return null;
        }
    }

    /**
     * Reconciles a market to the reported state. Idempotent: applying the same report twice performs
     * no writes the second time, because every decision is taken by comparing the live market against
     * the report rather than by replaying an action.
     *
     * <p>Every step is guarded on its own. A colony that converged on four of five fields is strictly
     * better than an exception thrown out of the net pump, and the next edit re-sends the whole
     * absolute state anyway.
     */
    public static void applyToMarket(MarketAPI market, State state) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(state, "state");
        boolean changed = false;
        try {
            changed = applyIndustries(market, state);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony industries", ex);
        }
        try {
            applyQueue(market, state.queue());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony construction queue", ex);
        }
        try {
            applyToggles(market, state);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony toggles", ex);
        }
        if (changed) {
            try {
                market.reapplyIndustries();
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopColonyManagement.class, "Failed to reapply coop colony industries", ex);
            }
        }
    }

    /**
     * The industry set, absolutely: add what the report has and this engine lacks, remove what this
     * engine has and the report does not, and align cores, improvements, special items and build
     * state on the rest.
     *
     * <p><b>The upgrade race is why this is not a plain set difference.</b> A finished upgrade is a
     * <em>different industry id</em> than the one that was upgrading. If the report says
     * "heavyindustry, UPGRADING &rarr; orbitalworks" and this engine already finished it, a naive diff
     * would re-add heavy industry and delete the orbital works — a downgrade caused purely by the
     * mirror being ahead. {@link IndustryState#upgradeId} exists for that case: a reported upgrading
     * industry whose target is already present here is treated as satisfied, and the target is
     * protected from removal.
     *
     * @return true when something changed and the market needs {@code reapplyIndustries}.
     */
    private static boolean applyIndustries(MarketAPI market, State state) {
        Set<String> wanted = new LinkedHashSet<>();
        Set<String> satisfiedByUpgrade = new LinkedHashSet<>();
        for (IndustryState reported : state.industries()) {
            wanted.add(reported.industryId());
            if (reported.buildState() == BuildState.UPGRADING && !reported.upgradeId().isEmpty()
                    && market.hasIndustry(reported.upgradeId())) {
                satisfiedByUpgrade.add(reported.industryId());
                wanted.add(reported.upgradeId());
            }
        }

        boolean changed = false;
        List<Industry> live = market.getIndustries();
        if (live != null) {
            for (Industry industry : new ArrayList<>(live)) {
                if (industry == null || industry.getId() == null
                        || wanted.contains(industry.getId())) {
                    continue;
                }
                // null mode is the silent removal: it refunds no credits (the UI does that, never
                // removeIndustry) and returns no AI core or special item to any cargo
                // (BaseIndustry.getCargoForInteractionMode returns null for a null mode,
                // BaseIndustry.java:690-696). Vanilla's own scripted removals pass null too.
                market.removeIndustry(industry.getId(), null, false);
                changed = true;
            }
        }

        for (IndustryState reported : state.industries()) {
            if (satisfiedByUpgrade.contains(reported.industryId())) {
                continue;
            }
            boolean freshlyAdded = false;
            if (!market.hasIndustry(reported.industryId())) {
                market.addIndustry(reported.industryId());
                freshlyAdded = true;
                changed = true;
            }
            Industry industry = market.getIndustry(reported.industryId());
            if (industry == null) {
                CoopLog.warn(CoopColonyManagement.class, "Coop COLONY_MGMT could not add industry "
                        + reported.industryId() + " to market " + state.marketId());
                continue;
            }
            changed |= applyBuildState(industry, reported, freshlyAdded);
            changed |= applyIndustryItems(industry, reported);
        }
        return changed;
    }

    /**
     * Converges one industry's build state.
     *
     * <ul>
     *   <li><b>Report finished, mirror still building</b> &rarr; {@code finishBuildingOrUpgrading()}.
     *       This is the only forcing direction, and it is what makes the accepted build-progress drift
     *       self-heal.</li>
     *   <li><b>Report building, mirror finished</b> &rarr; nothing. Restarting a finished build would
     *       unapply a working industry to re-run a timer, turning "the mirror is a few hours ahead"
     *       into a real regression.</li>
     *   <li><b>Report upgrading, mirror idle</b> &rarr; {@code startUpgrading()}. An upgrade is a
     *       player action the colony screen performs immediately, not through the queue, so unlike a
     *       new build it does not replicate for free.</li>
     *   <li><b>Freshly added and reported building</b> &rarr; {@code startBuilding()}, because
     *       {@code addIndustry} produces a <em>finished</em> industry ({@code Market.addIndustry}
     *       calls {@code apply()} straight away).</li>
     * </ul>
     */
    private static boolean applyBuildState(Industry industry, IndustryState reported,
                                           boolean freshlyAdded) {
        switch (reported.buildState()) {
            case NONE -> {
                if (industry.isBuilding()) {
                    industry.finishBuildingOrUpgrading();
                    return true;
                }
            }
            case BUILDING -> {
                if (freshlyAdded) {
                    industry.startBuilding();
                    return true;
                }
            }
            case UPGRADING -> {
                if (!industry.isBuilding() && industry.canUpgrade()) {
                    industry.startUpgrading();
                    return true;
                }
            }
        }
        return false;
    }

    /** AI core, improvement and special item — three absolute writes with a re-apply if any bit. */
    private static boolean applyIndustryItems(Industry industry, IndustryState reported) {
        boolean changed = false;
        String wantedCore = reported.aiCoreId().isEmpty() ? null : reported.aiCoreId();
        if (!Objects.equals(industry.getAICoreId(), wantedCore)) {
            industry.setAICoreId(wantedCore);
            changed = true;
        }
        if (industry.isImproved() != reported.improved()) {
            industry.setImproved(reported.improved());
            changed = true;
        }
        SpecialItemData current = industry.getSpecialItem();
        String currentId = current == null ? "" : CoopDelimited.normalize(current.getId());
        String currentData = current == null ? "" : CoopDelimited.normalize(current.getData());
        if (!currentId.equals(reported.specialItemId())
                || !currentData.equals(reported.specialItemData())) {
            industry.setSpecialItem(reported.specialItemId().isEmpty() ? null
                    : new SpecialItemData(reported.specialItemId(),
                            reported.specialItemData().isEmpty() ? null : reported.specialItemData()));
            changed = true;
        }
        return changed;
    }

    /**
     * The construction queue, as an ordered list.
     *
     * <p>Replaced wholesale rather than diffed, because the order <em>is</em> the state: moving an
     * entry up the queue is a management edit with no other observable effect. Rewriting an identical
     * queue is a no-op by inspection first, so the common case (a report that changed something else)
     * does not touch it. Clearing before rewriting is also what keeps a re-applied report — or a
     * {@code COLONY_FOUNDED} landing on a market that already has entries — from appending duplicates.
     *
     * <p>Package-visible on the list rather than the report because {@link CoopColonySync} reconciles
     * a freshly built mirror's queue through the same code.
     */
    static void applyQueue(MarketAPI market, List<QueueItem> wanted) {
        ConstructionQueue queue = market.getConstructionQueue();
        if (queue == null) {
            return;
        }
        List<ConstructionQueue.ConstructionQueueItem> items = queue.getItems();
        if (items == null) {
            return;
        }
        if (matches(items, wanted)) {
            return;
        }
        items.clear();
        for (QueueItem item : wanted) {
            queue.addToEnd(item.industryId(), item.cost());
        }
    }

    private static boolean matches(List<ConstructionQueue.ConstructionQueueItem> items,
                                   List<QueueItem> wanted) {
        if (items.size() != wanted.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            ConstructionQueue.ConstructionQueueItem item = items.get(i);
            QueueItem expected = wanted.get(i);
            if (item == null || !expected.industryId().equals(item.id) || expected.cost() != item.cost) {
                return false;
            }
        }
        return true;
    }

    private static void applyToggles(MarketAPI market, State state) {
        if (market.isFreePort() != state.freePort()) {
            market.setFreePort(state.freePort());
        }
        if (market.isImmigrationClosed() != state.immigrationClosed()) {
            market.setImmigrationClosed(state.immigrationClosed());
        }
        if (market.isImmigrationIncentivesOn() != state.immigrationIncentives()) {
            market.setImmigrationIncentivesOn(state.immigrationIncentives());
        }
        if (market.isUseStockpilesForShortages() != state.useStockpilesForShortages()) {
            market.setUseStockpilesForShortages(state.useStockpilesForShortages());
        }
    }
}
