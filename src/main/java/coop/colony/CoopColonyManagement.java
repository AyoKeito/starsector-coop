package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import coop.campaign.CoopDelimited;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static coop.util.CoopText.requireText;

/**
 * Phase 24 milestone 3: replication of colony <em>management</em> — the industries, construction
 * queue and colony toggles either player edits from their own client.
 *
 * <p><b>Model: poll for changes, ship absolute state.</b> Every {@link Poll} tick each player-owned
 * market is snapshotted and compared, by {@link State#contentHash()}, against the last state known to
 * be shared with the peer. A difference ships the whole current state as one {@code COLONY_MGMT} —
 * not the diff. The comparison only decides <em>whether</em> to send; the payload is absolute, which
 * makes a duplicate delivery, the host's rebroadcast and a late arrival all idempotent, and means a
 * mirror that had drifted converges on the next send instead of compounding.
 *
 * <p><b>Why a poll and not just the market-open/close callbacks.</b> The milestone shipped with only
 * {@link Diff} — a snapshot on {@code reportPlayerOpenedMarket}, a second one on
 * {@code reportPlayerClosedMarket}, send on difference — and a live session on 2026-08-25 showed that
 * trigger is structurally unreliable. Vanilla's close callback fires on some trade-screen exit paths
 * and not others (the same quirk Phase 18 recorded), so a free-port toggle produced a
 * {@code COLONY_MGMT} on roughly one docked visit in several; and the colony screen reached from the
 * command/intel UI never docks at all, so it fires <em>neither</em> callback and could never be
 * captured. The poll is now the primary capture and the open/close diff stays as the low-latency
 * assist for the case it does fire on. Both routes send through the same helper and both mark the
 * market synced.
 *
 * <p><b>Suppression is what keeps the poll from ping-ponging.</b> Engine-driven transitions — a queue
 * entry popping into a building industry, a build finishing — happen on <em>both</em> engines within a
 * frame or two of each other, so both polls see a change and both may send once. Each inbound state is
 * then content-identical to what the receiver already has: the apply is a no-op (guarded by an
 * explicit content-equality check in {@link #applyToEngine}) and it marks the market synced, so
 * neither side re-sends. "Known synced" is updated on send <em>and</em> on a <em>successful</em> apply
 * for exactly that reason.
 *
 * <p><b>An apply that failed must not be marked synced.</b> {@link #applyToEngine} returns whether the
 * reconcile actually ran; a false there means this engine still holds the state the peer has already
 * moved off. Marking that synced would be a lie in the worst direction: the next tick would see the
 * local content differ from the recorded hash, report the <em>stale</em> state as a fresh change, and
 * roll the other player's edit back. So a failed apply parks the report on the poll instead
 * ({@link Poll#markPendingApply}), which suppresses that market until the apply succeeds on a retry, a
 * later report for the market lands, or the engine reaches the reported content on its own.
 *
 * <p><b>Concurrency is not solved here, and it is not fully solved by the interaction gate either.</b>
 * The Phase 10 gate is a global first-come lockout on interaction <em>dialogs</em>, so it does keep the
 * two players out of the same <em>docked</em> colony screen. It does not cover the colony screen
 * reached from the command/intel UI, which docks nothing and claims no entity (see the paragraph
 * above on why the poll exists) — both players can be in that screen on the same colony at once.
 * There is still no merge, no conflict resolution and no locking here: two edits to one colony inside
 * one {@link Poll} interval resolve as last-writer-wins on each engine, and the losing edit is dropped
 * without a refund. Closing that hole needs either a gate on the OUTPOSTS core tab or a base-state
 * hash on the wire, neither of which lives in this class.
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

        /**
         * The comparison key the {@link Poll} suppresses on: everything {@link #sameStateAs} compares
         * and nothing else, so the report id and the acting player id — which differ on every capture
         * and on every engine — cannot make two identical colonies look different.
         *
         * <p>It is the content itself, canonically encoded, not a digest. A colony's management state
         * is a handful of short records; hashing it down to an int would buy nothing but the chance of
         * a collision silently suppressing a real change. Order-stable by construction: the industry
         * list is in market order and the queue list is in build order, both of which the wire already
         * has to preserve.
         */
        public String contentHash() {
            StringBuilder out = new StringBuilder(160);
            out.append(CoopDelimited.field(marketId))
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
        BuildState state = liveBuildState(industry);
        String upgradeId = state == BuildState.UPGRADING ? specUpgradeId(industry) : "";
        SpecialItemData special = industry.getSpecialItem();
        return new IndustryState(industry.getId(), industry.getAICoreId(), industry.isImproved(),
                state, upgradeId, special == null ? null : special.getId(),
                special == null ? null : special.getData());
    }

    /**
     * What this industry is <em>actually</em> doing, as opposed to what {@code isBuilding()} and
     * {@code isUpgrading()} say.
     *
     * <p><b>Those two predicates are not safe to read directly, and a live session proved it.</b>
     * {@code PopulationAndInfrastructure} overrides both to render the colony's growth toward its
     * maximum size as a construction bar ({@code PopulationAndInfrastructure.java:606-617}):
     * {@code isUpgrading()} returns true for <em>any</em> colony below max size, and
     * {@code isBuilding()} returns true as soon as that growth is above zero, with
     * {@code getBuildOrUpgradeProgress()} reporting the growth fraction and the panel labelling it
     * "total growth: N%". Nothing is being built. Taken at face value that made the host report
     * "population is UPGRADING" for every ordinary colony, and the mirror then called
     * {@code startUpgrading()} on a spec with no upgrade — {@code population} has an empty
     * {@code upgrade} column in {@code industries.csv} — which threw
     * {@code NullPointerException: ... because "upgrade" is null} out of
     * {@code BaseIndustry.startUpgrading} ({@code BaseIndustry.java:575-579}) and aborted the whole
     * reconcile. In the other direction, a report of {@code NONE} against that same false
     * {@code isBuilding()} called {@code finishBuildingOrUpgrading()}, which fires vanilla's
     * "Construction completed" message and pops the next construction-queue entry early
     * ({@code BaseIndustry.buildingFinished}).
     *
     * <p>The disambiguation is the spec: a genuine upgrade has a target to upgrade <em>into</em>, and
     * an industry whose spec has no {@code upgrade} can never be upgrading no matter what the
     * predicate claims. A genuine first-time build is unaffected — {@code startBuilding()} leaves
     * {@code upgradeId} null, so {@code isUpgrading()} is false and the {@code isBuilding()} reading
     * stands.
     */
    static BuildState liveBuildState(Industry industry) {
        if (industry == null) {
            return BuildState.NONE;
        }
        if (industry.isUpgrading()) {
            return specUpgradeId(industry).isEmpty() ? BuildState.NONE : BuildState.UPGRADING;
        }
        return industry.isBuilding() ? BuildState.BUILDING : BuildState.NONE;
    }

    /** The spec this industry can upgrade into, or {@code ""} when it has none. */
    static String specUpgradeId(Industry industry) {
        IndustrySpecAPI spec = industry == null ? null : industry.getSpec();
        String upgrade = spec == null ? null : spec.getUpgrade();
        return upgrade == null ? "" : upgrade.trim();
    }

    /**
     * The open/close bookkeeping. One baseline per market id, taken when the local player opens a
     * player-owned market and consumed when they close it.
     *
     * <p><b>The low-latency assist, not the capture.</b> When vanilla's close callback does fire this
     * reports an edit in the same frame the player left the screen instead of up to a {@link Poll}
     * interval later. When it does not fire — and it often does not, see the class doc — the poll
     * catches the same edit shortly after. Nothing depends on this class running.
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

    /**
     * The change poll: the primary capture since 2026-08-25.
     *
     * <p>One {@link State#contentHash()} per market id, holding the last content this engine knows the
     * peer has — written both when this engine sends a state and when it applies one. A market whose
     * live content still hashes to that value has nothing to say, which is the overwhelmingly common
     * case, so a tick over a handful of colonies costs a few list walks and a string compare.
     *
     * <p><b>The session edge is a baseline, not a resume.</b> {@link #armBaseline()} drops every hash,
     * because a hash from the previous session says nothing about what the peer on the other end of a
     * new connection holds. The first tick after that is asymmetric on purpose: the <em>host</em> sends
     * every colony unconditionally, which heals whatever diverged while the channel was down, and the
     * <em>guest</em> records the hashes without sending, because the host is canonical on reconnect and
     * a guest baseline-send would race the host's with the older of the two states. The guest's normal
     * change-driven sends resume as soon as the host's baseline has landed and marked the markets
     * synced.
     *
     * <p><b>Pending applies.</b> A market whose inbound report failed to reach the engine is parked
     * here by {@link #markPendingApply} and reports nothing until it is resolved — see that method for
     * why the alternative is a silent rollback of the other player's edit.
     */
    public static final class Poll {
        private final Map<String, String> syncedContent = new LinkedHashMap<>();
        private final Map<String, PendingApply> pendingApplies = new LinkedHashMap<>();
        private long counter;
        private boolean baselineArmed = true;

        /** Session (re)start: nothing is known-synced any more, and the next tick is the baseline. */
        public void armBaseline() {
            syncedContent.clear();
            pendingApplies.clear();
            baselineArmed = true;
        }

        /**
         * One tick.
         *
         * @param baselineSend true for the engine that owns the reconnect baseline (the host). Ignored
         *                     once the baseline tick has been consumed.
         * @return the states to send, in market order; empty when nothing changed.
         */
        public List<State> poll(String actingPlayerId, Collection<MarketAPI> markets,
                                boolean baselineSend) {
            boolean baseline = baselineArmed;
            baselineArmed = false;
            List<State> reports = new ArrayList<>();
            if (markets == null) {
                return reports;
            }
            for (MarketAPI market : markets) {
                if (!isManaged(market)) {
                    continue;
                }
                State current = capture(BASELINE_REPORT_ID, actingPlayerId, market);
                String content = current.contentHash();
                PendingApply pending = pendingApplies.get(market.getId());
                if (pending != null) {
                    // This engine is behind on purpose: an inbound report for this market never made
                    // it in, so whatever we hold is what the peer has already moved off. Reporting it
                    // would push their edit back out of their own engine. The one way out that does
                    // not need another message is the engine arriving at the reported content by
                    // itself -- the transient this guards is a market mid-teardown or a build that
                    // finishes a beat later on both sides -- and then there is nothing to report
                    // either, because that content is by definition what the peer already has.
                    if (content.equals(pending.contentHash())) {
                        pendingApplies.remove(market.getId());
                        syncedContent.put(market.getId(), content);
                    }
                    continue;
                }
                boolean send = baseline ? baselineSend : !content.equals(syncedContent.get(market.getId()));
                syncedContent.put(market.getId(), content);
                if (send) {
                    reports.add(current.withReportId(nextReportId(actingPlayerId)));
                }
            }
            return reports;
        }

        /**
         * This state is now what both engines hold: sent by us, or successfully applied from the peer.
         *
         * <p>Either way the market is no longer behind, so any parked report for it is dropped. A send
         * counts because the peer is about to take our state whatever it was holding, and a later
         * inbound report counts because an absolute state that applied cleanly supersedes the one that
         * did not.
         */
        public void markSynced(State state) {
            if (state == null) {
                return;
            }
            pendingApplies.remove(state.marketId());
            syncedContent.put(state.marketId(), state.contentHash());
        }

        /**
         * An inbound report for this market did <em>not</em> reach the engine. Park it: this market
         * reports nothing until {@link #markSynced} clears it or the engine reaches the parked content
         * on its own.
         *
         * <p>Re-parking the same content is not a new failure — the retry budget in
         * {@link #pendingApplyRetries()} owns the attempt count — but a report with different content
         * replaces the parked one, because it is the newer picture of what the peer holds.
         */
        public void markPendingApply(State state) {
            if (state == null) {
                return;
            }
            PendingApply existing = pendingApplies.get(state.marketId());
            if (existing != null && existing.contentHash().equals(state.contentHash())) {
                return;
            }
            pendingApplies.put(state.marketId(), new PendingApply(state));
        }

        /**
         * The parked reports that are still worth another apply, one attempt spent per call. The
         * inbound delivery that failed counts as the first attempt, so a report is tried
         * {@link #PENDING_APPLY_ATTEMPTS} times in total and then left parked forever: giving up on
         * the apply is not the same as giving up on the suppression, because the stale state is just
         * as wrong on the tenth tick as on the first.
         */
        public List<State> pendingApplyRetries() {
            List<State> retries = new ArrayList<>();
            for (PendingApply pending : pendingApplies.values()) {
                if (pending.attempts() < PENDING_APPLY_ATTEMPTS) {
                    pending.spendAttempt();
                    retries.add(pending.state());
                }
            }
            return retries;
        }

        /** False once this market's parked report has spent its retry budget (or has none parked). */
        public boolean canRetryPendingApply(String marketId) {
            PendingApply pending = marketId == null ? null : pendingApplies.get(marketId);
            return pending != null && pending.attempts() < PENDING_APPLY_ATTEMPTS;
        }

        /** Test/diagnostic seam: how many markets are suppressed by a failed apply. */
        public int pendingApplyCount() {
            return pendingApplies.size();
        }

        private String nextReportId(String actingPlayerId) {
            return (actingPlayerId == null || actingPlayerId.isBlank() ? "local" : actingPlayerId)
                    + ":poll:" + (++counter);
        }

        /** Session teardown: ids restart and no hash may survive into the next session. */
        public void reset() {
            counter = 0;
            syncedContent.clear();
            pendingApplies.clear();
            baselineArmed = true;
        }

        /** Test/diagnostic seam: how many markets have a known-synced hash. */
        public int syncedCount() {
            return syncedContent.size();
        }
    }

    /** How many times one inbound report is handed to the engine before the retries stop. */
    public static final int PENDING_APPLY_ATTEMPTS = 3;

    /** One market's unapplied inbound report, with the attempts already spent on it. */
    private static final class PendingApply {
        private final State state;
        private final String contentHash;
        private int attempts = 1;

        private PendingApply(State state) {
            this.state = state;
            this.contentHash = state.contentHash();
        }

        private State state() {
            return state;
        }

        private String contentHash() {
            return contentHash;
        }

        private int attempts() {
            return attempts;
        }

        private void spendAttempt() {
            attempts++;
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
     *
     * @return true when this engine is now caller-visibly in step with the report — the reconcile ran
     *         without dropping a step, it was already a no-op, or there is nothing here the report can
     *         apply to. False means the local market kept state the peer has moved off, and the caller
     *         must not record the report as synced.
     */
    public static boolean applyToEngine(State state) {
        Objects.requireNonNull(state, "state");
        MarketAPI market = resolveMarket(state.marketId());
        if (market == null) {
            // Counted as success, not failure. The suppression a failure buys only pays off for a
            // market the poll would otherwise report, and a market that does not exist here is never
            // captured at all (isManaged is false for a market that is not there). Suppressing it
            // would park a report that nothing can ever clear; the market appearing later -- a
            // COLONY_FOUNDED that lost the race with its own management report -- is picked up by the
            // poll as a new colony anyway.
            CoopLog.warn(CoopColonyManagement.class, "Coop COLONY_MGMT names market "
                    + state.marketId() + ", which does not exist here; dropped");
            return true;
        }
        // The capture side only ever reports managed colonies, so an unmanaged market here means the
        // report raced a teardown: an abandonment or a deciv has already flipped this back to the
        // planet-condition market (which resolveMarket's planet fallback still finds, because the
        // planet keeps the link). Reconciling it would add industries, a construction queue and a
        // free_market condition to an uncolonized planet, and the poll — which skips unmanaged
        // markets — would never converge it back.
        //
        // Success for the same reason a missing market is: the poll skips unmanaged markets, so there
        // is no stale re-report to suppress here either.
        if (!isManaged(market)) {
            CoopLog.warn(CoopColonyManagement.class, "Coop COLONY_MGMT names market "
                    + state.marketId() + ", which is not a player colony here; dropped");
            return true;
        }
        // The two engines run the same colony through the same vanilla code, so a queue entry popping
        // into a build lands on both within a frame or two and both polls report it. Reading the local
        // state once and comparing content is cheaper than the reconcile, and it keeps the no-op case
        // genuinely no-op rather than relying on every step's own inspect-before-write.
        try {
            if (capture(BASELINE_REPORT_ID, state.actingPlayerId(), market).contentHash()
                    .equals(state.contentHash())) {
                return true;
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonyManagement.class,
                    "Could not read local colony state for " + state.marketId() + "; applying anyway", ex);
        }
        return applyToMarket(market, state);
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
     *
     * @return true when every step ran. False means at least one was dropped, so this market may still
     *         hold state the report replaced — the caller decides what that is worth (the replicator
     *         suppresses the market's poll rather than re-report the half it kept).
     */
    public static boolean applyToMarket(MarketAPI market, State state) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(state, "state");
        boolean changed = false;
        boolean applied = true;
        try {
            Reconcile industries = applyIndustries(market, state);
            changed = industries.changed();
            applied = !industries.dropped();
        } catch (RuntimeException | LinkageError ex) {
            applied = false;
            CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony industries", ex);
        }
        try {
            applyQueue(market, state.queue());
        } catch (RuntimeException | LinkageError ex) {
            applied = false;
            CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony construction queue", ex);
        }
        try {
            applyToggles(market, state);
        } catch (RuntimeException | LinkageError ex) {
            applied = false;
            CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony toggles", ex);
        }
        if (changed) {
            try {
                market.reapplyIndustries();
            } catch (RuntimeException | LinkageError ex) {
                applied = false;
                CoopLog.warn(CoopColonyManagement.class, "Failed to reapply coop colony industries", ex);
            }
        }
        return applied;
    }

    /**
     * What one reconcile pass did: whether it wrote anything (so the market needs a re-apply) and
     * whether any part of it was dropped by a guard.
     */
    private record Reconcile(boolean changed, boolean dropped) {
        private static final Reconcile CLEAN = new Reconcile(false, false);

        private Reconcile with(boolean moreChanged, boolean moreDropped) {
            return new Reconcile(changed || moreChanged, dropped || moreDropped);
        }

        private Reconcile merge(Reconcile other) {
            return with(other.changed(), other.dropped());
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
     * @return what changed, and whether any industry was dropped by a guard.
     */
    private static Reconcile applyIndustries(MarketAPI market, State state) {
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

        Reconcile outcome = Reconcile.CLEAN;
        List<Industry> live = market.getIndustries();
        if (live != null) {
            for (Industry industry : new ArrayList<>(live)) {
                if (industry == null || industry.getId() == null
                        || wanted.contains(industry.getId())) {
                    continue;
                }
                try {
                    // null mode is the silent removal: it refunds no credits (the UI does that, never
                    // removeIndustry) and returns no AI core or special item to any cargo
                    // (BaseIndustry.getCargoForInteractionMode returns null for a null mode,
                    // BaseIndustry.java:690-696). Vanilla's own scripted removals pass null too.
                    market.removeIndustry(industry.getId(), null, false);
                    outcome = outcome.with(true, false);
                } catch (RuntimeException | LinkageError ex) {
                    outcome = outcome.with(false, true);
                    CoopLog.warn(CoopColonyManagement.class, "Failed to remove coop colony industry "
                            + industry.getId() + " from market " + state.marketId(), ex);
                }
            }
        }

        for (IndustryState reported : state.industries()) {
            if (satisfiedByUpgrade.contains(reported.industryId())) {
                continue;
            }
            // Per industry, not per market: one industry that throws must not starve the ones after
            // it. The live failure this guard exists for cost a colony its whole reconcile -- the
            // population industry threw first and the spaceport behind it was never added at all.
            try {
                outcome = outcome.merge(applyIndustry(market, state, reported));
            } catch (RuntimeException | LinkageError ex) {
                outcome = outcome.with(false, true);
                CoopLog.warn(CoopColonyManagement.class, "Failed to apply coop colony industry "
                        + reported.industryId() + " on market " + state.marketId(), ex);
            }
        }
        return outcome;
    }

    private static Reconcile applyIndustry(MarketAPI market, State state, IndustryState reported) {
        boolean freshlyAdded = false;
        boolean changed = false;
        if (!market.hasIndustry(reported.industryId())) {
            market.addIndustry(reported.industryId());
            freshlyAdded = true;
            changed = true;
        }
        Industry industry = market.getIndustry(reported.industryId());
        if (industry == null) {
            CoopLog.warn(CoopColonyManagement.class, "Coop COLONY_MGMT could not add industry "
                    + reported.industryId() + " to market " + state.marketId());
            return new Reconcile(changed, true);
        }
        changed |= applyBuildState(industry, reported, freshlyAdded);
        changed |= applyIndustryItems(industry, reported);
        return new Reconcile(changed, false);
    }

    /**
     * Converges one industry's build state.
     *
     * <ul>
     *   <li><b>Report finished, mirror still building</b> &rarr; {@code finishBuildingOrUpgrading()}.
     *       This is the only forcing direction, and it is what makes the accepted build-progress drift
     *       self-heal.</li>
     *   <li><b>Report finished, mirror still upgrading</b> &rarr; {@code cancelUpgrade()}, not finish.
     *       An upgrade that actually completed renames the industry, so it is reported under the
     *       upgrade target and never lands here; the only way the <em>same</em> id goes back to idle
     *       is the colony screen's Cancel button, which refunds the acting player. Finishing it here
     *       would hand both engines a free upgrade nobody paid for.</li>
     *   <li><b>Report building, mirror finished</b> &rarr; nothing. Restarting a finished build would
     *       unapply a working industry to re-run a timer, turning "the mirror is a few hours ahead"
     *       into a real regression.</li>
     *   <li><b>Report upgrading, mirror idle</b> &rarr; {@code startUpgrading()}. An upgrade is a
     *       player action the colony screen performs immediately, not through the queue, so unlike a
     *       new build it does not replicate for free.</li>
     *   <li><b>Freshly added and reported building</b> &rarr; {@code startBuilding()}, because
     *       {@code addIndustry} produces a <em>finished</em> industry ({@code Market.addIndustry}
     *       adds the plugin and calls {@code apply()} straight away — verified against the engine's
     *       own {@code Market.addIndustry}, which never touches {@code startBuilding}).</li>
     * </ul>
     *
     * <p>Every branch reads {@link #liveBuildState} rather than {@code isBuilding()} /
     * {@code isUpgrading()} directly, and the upgrade branch will not call {@code startUpgrading()}
     * on a spec with nothing to upgrade into: {@code BaseIndustry.canUpgrade()} returns a hardcoded
     * {@code true} ({@code BaseIndustry.java:1627}) and is therefore no guard at all, while
     * {@code startUpgrading()} dereferences the upgrade spec unconditionally and throws when there is
     * none. A report that claims an impossible upgrade is logged and treated as no transition, which
     * leaves the industry finished and functional — the closest correct state, and one the next
     * absolute report will overwrite anyway.
     */
    private static boolean applyBuildState(Industry industry, IndustryState reported,
                                           boolean freshlyAdded) {
        BuildState live = liveBuildState(industry);
        switch (reported.buildState()) {
            case NONE -> {
                // A finished upgrade never reaches this branch: it changes the industry's id, so the
                // report arrives under the upgrade target and the industry set difference handles it.
                // Same id, report NONE, mirror UPGRADING can therefore only be the acting player
                // cancelling — BaseIndustry.cancelUpgrade (BaseIndustry.java:533-537) clears building
                // and upgradeId and leaves the id alone — and the acting player was refunded for it.
                if (live == BuildState.UPGRADING) {
                    industry.cancelUpgrade();
                    return true;
                }
                if (live != BuildState.NONE) {
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
                if (specUpgradeId(industry).isEmpty()) {
                    CoopLog.warn(CoopColonyManagement.class, "Coop COLONY_MGMT reports industry "
                            + reported.industryId() + " upgrading, but its spec has no upgrade here;"
                            + " left as-is rather than upgraded into nothing");
                    return false;
                }
                if (live == BuildState.NONE && industry.canUpgrade()) {
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
