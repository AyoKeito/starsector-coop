package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyPlayerHostileActListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.econ.ShippingDisruption;
import com.fs.starfarer.api.impl.campaign.graid.CommodityGroundRaidObjectivePluginImpl;
import com.fs.starfarer.api.impl.campaign.graid.GroundRaidObjectivePlugin;
import com.fs.starfarer.api.impl.campaign.graid.SolarArrayGroundRaidObjectivePluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;
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
 * Phase 24 milestone 1: replication of player raids and bombardments against NPC colonies.
 *
 * <p><b>Model.</b> The acting player resolves the whole act locally through vanilla — marine
 * assignment, target picking, every roll — and keeps the loot, exactly like salvage. Vanilla's own
 * {@link ColonyPlayerHostileActListener} then hands us the finished act, we read the resulting
 * market state back off the live market, and ship it as one {@code RAID_RESULT}. The host applies it
 * to its canonical market and rebroadcasts; the originator's {@link Ledger} makes the echo a no-op.
 * Nothing is re-simulated on the receiving engine and no RNG crosses the wire.
 *
 * <p><b>What deliberately is not here.</b> Reputation (both the acting faction's standing hit and
 * the atrocity fallout) already travels on the Phase 12 {@code REP_DELTA}/{@code GUEST_REP_DELTA}
 * channel, fired by vanilla's own {@code adjustPlayerReputation} inside the raid code
 * ({@code MarketCMD.java:1756-1773}, {@code :2570-2584}). Loot stays with the raider. And a
 * saturation bombardment that decivilizes flows through the Phase 13 {@code WORLD_DELTA(DECIV)}
 * skeleton delta, which is why {@link Outcome#decivilized()} exists — see
 * {@link #applyToMarket} for the non-overlap rule.
 *
 * <p><b>Absolute values, scoped to what the act touched.</b> Every replicated number is read off the
 * live market <em>after</em> vanilla finished, so applying it twice is a no-op and a delayed message
 * cannot compound. But the outcome only carries industries the act actually touched (derived from
 * {@link MarketCMD.TempData}), never a full sweep of the market: a full absolute sweep from the
 * guest would overwrite host-side disruption the guest's mirror had gone stale on.
 */
public final class CoopRaidOutcomeSync {

    private CoopRaidOutcomeSync() {
    }

    /** Which of the four vanilla hostile acts produced this outcome. */
    public enum Kind {
        RAID_VALUABLES,
        RAID_DISRUPT,
        BOMBARD_TACTICAL,
        BOMBARD_SATURATION;

        public boolean isRaid() {
            return this == RAID_VALUABLES || this == RAID_DISRUPT;
        }

        public boolean isBombardment() {
            return !isRaid();
        }
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final char RECORD_SEPARATOR = '\n';
    private static final String HEADER_TAG = "H";
    private static final String INDUSTRY_TAG = "I";
    private static final String DEFICIT_TAG = "C";
    /** Disruption days closer than this are treated as already converged (see {@link #applyToMarket}). */
    private static final float DISRUPTION_EPSILON = 0.5f;
    /** Vanilla's own reason string when the player faction has no display name yet. */
    static final String DEFAULT_RAID_REASON = "Recently raided";
    static final String DEFAULT_BOMBARD_REASON = "Recently bombarded";

    /**
     * Post-act state of one industry the act touched. All three payload fields are absolute reads,
     * so re-applying writes the same values.
     *
     * @param disruptedDays {@code Industry.getDisruptedDays()} after the act. Raids and bombardments
     *                      both <em>add</em> to it ({@code MarketCMD.java:2905-2906},
     *                      {@code DisruptIndustryRaidObjectivePluginImpl.java:116-117}), so the
     *                      absolute value is the only echo-safe shape.
     * @param aiCoreId      {@code null}/empty once an AI-core raid objective stripped it
     *                      ({@code AICoreGroundRaidObjectivePluginImpl.java:91-95}).
     * @param specialItemId {@code null}/empty once a special-item objective stripped it
     *                      ({@code SpecialItemRaidObjectivePluginImpl.java:106-112}).
     */
    public record IndustryState(String industryId, float disruptedDays, String aiCoreId,
                                String specialItemId, String specialItemData) {

        public IndustryState {
            industryId = requireText(industryId, "industryId");
            aiCoreId = CoopDelimited.normalize(aiCoreId);
            specialItemId = CoopDelimited.normalize(specialItemId);
            specialItemData = CoopDelimited.normalize(specialItemData);
        }

        String encode() {
            return CoopDelimited.field(INDUSTRY_TAG)
                    + FIELD_SEPARATOR + CoopDelimited.field(industryId)
                    + FIELD_SEPARATOR + disruptedDays
                    + FIELD_SEPARATOR + CoopDelimited.field(aiCoreId)
                    + FIELD_SEPARATOR + CoopDelimited.field(specialItemId)
                    + FIELD_SEPARATOR + CoopDelimited.field(specialItemData);
        }

        static IndustryState decode(List<String> fields) {
            if (fields.size() != 6) {
                throw new IllegalArgumentException(
                        "Expected 6 raid industry fields, got " + fields.size());
            }
            return new IndustryState(fields.get(1), parseFloat(fields.get(2), "disruptedDays"),
                    fields.get(3), fields.get(4), fields.get(5));
        }
    }

    /**
     * A commodity availability deficit a raid-for-valuables caused.
     *
     * <p>The spec's wording was "commodities removed", but vanilla removes nothing: a commodity
     * objective mints the loot and instead hangs a temporary negative flat mod on the market's
     * availability stat ({@code CommodityGroundRaidObjectivePluginImpl.java:214-221}). Submarket
     * stock is untouched, so Phase 12's market snapshot channel never sees this and it has to ride
     * here or the other engine's economy never feels the raid.
     */
    public record CommodityDeficit(String commodityId, int units) {

        public CommodityDeficit {
            commodityId = requireText(commodityId, "commodityId");
        }

        String encode() {
            return CoopDelimited.field(DEFICIT_TAG)
                    + FIELD_SEPARATOR + CoopDelimited.field(commodityId)
                    + FIELD_SEPARATOR + units;
        }

        static CommodityDeficit decode(List<String> fields) {
            if (fields.size() != 3) {
                throw new IllegalArgumentException(
                        "Expected 3 raid deficit fields, got " + fields.size());
            }
            return new CommodityDeficit(fields.get(1), (int) parseFloat(fields.get(2), "units"));
        }
    }

    /**
     * One finished hostile act, in the shape the other engine needs to converge.
     *
     * @param outcomeId        dedup key, {@code actingPlayerId + ":" + monotonic counter}. Session
     *                         scoped, like the ledger that holds it.
     * @param marketSize       absolute post-act size. Only a non-destroying saturation bombardment
     *                         moves it ({@code MarketCMD.java:2648}, via
     *                         {@code CoreImmigrationPluginImpl.reduceMarketSize}).
     * @param unrestPenalty    absolute post-act {@code RecentUnrest.getPenalty}. No delta rides the
     *                         wire: the applier derives one from its own current value, which both
     *                         registers the tooltip reason and self-corrects drift. See
     *                         {@link #applyUnrest}.
     * @param unrestReason     vanilla's own reason string, for the "Recent contributing factors"
     *                         tooltip list.
     * @param pollutionAdded   bombardments add {@code Conditions.POLLUTION} to a habitable world
     *                         ({@code MarketCMD.java:2618-2620}). One-directional presence, not an
     *                         absolute condition set: we only ever observed this one condition
     *                         change, so asserting the whole set would be a claim we cannot back.
     * @param solarArrayRemoved a solar-array raid objective functionally destroyed the array
     *                         ({@code SolarArrayGroundRaidObjectivePluginImpl.java:34}). Also
     *                         one-directional.
     * @param decivilized      the act decivilized the colony. Everything else in this record is then
     *                         meaningless and is not applied; the DECIV delta owns the transition.
     */
    public record Outcome(String outcomeId, Kind kind, String marketId, String actingPlayerId,
                          int marketSize, int unrestPenalty, String unrestReason,
                          boolean pollutionAdded, boolean solarArrayRemoved, boolean decivilized,
                          List<IndustryState> industries, List<CommodityDeficit> deficits) {

        public Outcome {
            outcomeId = requireText(outcomeId, "outcomeId");
            kind = Objects.requireNonNull(kind, "kind");
            marketId = requireText(marketId, "marketId");
            actingPlayerId = CoopDelimited.normalize(actingPlayerId);
            unrestReason = CoopDelimited.normalize(unrestReason);
            industries = industries == null ? List.of() : List.copyOf(industries);
            deficits = deficits == null ? List.of() : List.copyOf(deficits);
        }

        /**
         * The whole outcome as one self-contained delimited blob: the envelope's flat JSON parser
         * has no arrays, so list-shaped bodies ship as a single opaque string (the
         * {@code CoopBaseRecord.encodeSet} convention). Header line first, then one line per touched
         * industry, then one per commodity deficit.
         */
        public String encode() {
            StringBuilder out = new StringBuilder(128);
            out.append(CoopDelimited.field(HEADER_TAG))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(outcomeId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(kind.name()))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(marketId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(actingPlayerId))
                    .append(FIELD_SEPARATOR).append(marketSize)
                    .append(FIELD_SEPARATOR).append(unrestPenalty)
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(unrestReason))
                    .append(FIELD_SEPARATOR).append(pollutionAdded)
                    .append(FIELD_SEPARATOR).append(solarArrayRemoved)
                    .append(FIELD_SEPARATOR).append(decivilized);
            for (IndustryState industry : industries) {
                out.append(RECORD_SEPARATOR).append(industry.encode());
            }
            for (CommodityDeficit deficit : deficits) {
                out.append(RECORD_SEPARATOR).append(deficit.encode());
            }
            return out.toString();
        }
    }

    public static Outcome decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split(String.valueOf(RECORD_SEPARATOR), -1);
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IllegalArgumentException("Empty raid outcome payload");
        }
        List<String> header = CoopDelimited.split(lines[0]);
        if (header.size() != 11 || !HEADER_TAG.equals(header.get(0))) {
            throw new IllegalArgumentException("Malformed raid outcome header");
        }
        List<IndustryState> industries = new ArrayList<>();
        List<CommodityDeficit> deficits = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            List<String> fields = CoopDelimited.split(lines[i]);
            switch (fields.get(0)) {
                case INDUSTRY_TAG -> industries.add(IndustryState.decode(fields));
                case DEFICIT_TAG -> deficits.add(CommodityDeficit.decode(fields));
                default -> throw new IllegalArgumentException(
                        "Unknown raid outcome record tag: " + fields.get(0));
            }
        }
        return new Outcome(header.get(1), parseKind(header.get(2)), header.get(3), header.get(4),
                (int) parseFloat(header.get(5), "marketSize"),
                (int) parseFloat(header.get(6), "unrestPenalty"),
                header.get(7), Boolean.parseBoolean(header.get(8)),
                Boolean.parseBoolean(header.get(9)), Boolean.parseBoolean(header.get(10)),
                industries, deficits);
    }

    private static Kind parseKind(String raw) {
        try {
            return Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown raid outcome kind: " + raw, ex);
        }
    }

    private static float parseFloat(String raw, String fieldName) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed raid outcome " + fieldName + ": " + raw, ex);
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
     * Session-scoped record of which outcomes have been applied, keyed by {@link Outcome#outcomeId}.
     *
     * <p>Set-based rather than latest-wins: an outcome is a one-shot event, not a value that can
     * change back, and the id is minted fresh per act. Its whole job is to absorb the host's verbatim
     * rebroadcast on the originator and any duplicate delivery.
     */
    public static final class Ledger {
        private final Set<String> applied = new LinkedHashSet<>();

        /** Returns true only the first time this outcome id is seen. */
        public boolean apply(Outcome outcome) {
            Objects.requireNonNull(outcome, "outcome");
            return applied.add(outcome.outcomeId());
        }

        public boolean isApplied(String outcomeId) {
            return applied.contains(requireText(outcomeId, "outcomeId"));
        }

        public int size() {
            return applied.size();
        }

        public void clear() {
            applied.clear();
        }
    }

    // ---- Capture -------------------------------------------------------------------------------

    /** What the capture listener needs from the replicator; the replicator implements it. */
    public interface Sink {
        /**
         * False when this client must not capture: no active session, or the applier is mid-replay of
         * a remote outcome (in which case the vanilla effects we are re-driving would otherwise be
         * captured and rebroadcast as a fresh act).
         */
        boolean shouldCaptureRaidOutcome();

        /** The local player id, used as the outcome-id prefix. */
        String raidActingPlayerId();

        void onRaidOutcomeCaptured(Outcome outcome);
    }

    /**
     * Vanilla's finished-hostile-act hook, registered through the listener manager.
     *
     * <p>{@code reportRaidToDisruptFinished} fires once <em>per disrupted industry</em>
     * ({@code MarketCMD.java:1903-1906}) while the other three fire once per act. Since a single
     * outcome already carries every industry the act touched, repeat calls carrying the same
     * {@code TempData} instance are collapsed — the alternative is N outcomes whose additive effects
     * (unrest, defender increase) would stack N times on the mirror.
     */
    public static final class HostileActCapture implements ColonyPlayerHostileActListener {
        private final Sink sink;
        private long counter;
        /** Identity of the act last captured; vanilla reuses one TempData for a whole act. */
        private Object lastActionData;

        public HostileActCapture(Sink sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        @Override
        public void reportRaidForValuablesFinishedBeforeCargoShown(InteractionDialogAPI dialog,
                                                                   MarketAPI market,
                                                                   MarketCMD.TempData actionData,
                                                                   CargoAPI cargo) {
            capture(Kind.RAID_VALUABLES, market, actionData, null);
        }

        @Override
        public void reportRaidToDisruptFinished(InteractionDialogAPI dialog, MarketAPI market,
                                                MarketCMD.TempData actionData, Industry industry) {
            capture(Kind.RAID_DISRUPT, market, actionData, industry);
        }

        @Override
        public void reportTacticalBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
                                                      MarketCMD.TempData actionData) {
            capture(Kind.BOMBARD_TACTICAL, market, actionData, null);
        }

        @Override
        public void reportSaturationBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
                                                        MarketCMD.TempData actionData) {
            capture(Kind.BOMBARD_SATURATION, market, actionData, null);
        }

        /** Test seam and the real path both land here. */
        void capture(Kind kind, MarketAPI market, MarketCMD.TempData actionData, Industry fallback) {
            if (market == null || !sink.shouldCaptureRaidOutcome()) {
                return;
            }
            if (actionData != null && actionData == lastActionData) {
                return;
            }
            lastActionData = actionData;
            try {
                String actingPlayerId = sink.raidActingPlayerId();
                String outcomeId = (actingPlayerId == null || actingPlayerId.isBlank()
                        ? "local" : actingPlayerId) + ":" + (++counter);
                sink.onRaidOutcomeCaptured(
                        captureOutcome(outcomeId, actingPlayerId, kind, market, actionData, fallback));
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to capture coop raid outcome", ex);
            }
        }

        /** Session teardown: ids restart and the collapse guard must not pin a stale act. */
        public void reset() {
            counter = 0;
            lastActionData = null;
        }
    }

    /**
     * Reads the finished act off the live market.
     *
     * <p>The touched-industry set comes from {@link MarketCMD.TempData} — {@code bombardmentTargets}
     * for a bombardment ({@code MarketCMD.java:2624-2628}), the assigned ground-raid objectives'
     * sources for a raid ({@code MarketCMD.java:1786}) — and the values for those industries are then
     * read absolute off the market.
     */
    public static Outcome captureOutcome(String outcomeId, String actingPlayerId, Kind kind,
                                         MarketAPI market, MarketCMD.TempData actionData,
                                         Industry fallback) {
        boolean decivilized = kind == Kind.BOMBARD_SATURATION && market.isPlanetConditionMarketOnly();
        List<IndustryState> industries = new ArrayList<>();
        List<CommodityDeficit> deficits = new ArrayList<>();
        boolean solarArrayRemoved = false;
        if (!decivilized) {
            Map<String, Industry> touched = new LinkedHashMap<>();
            if (kind.isBombardment()) {
                addAll(touched, actionData == null ? null : actionData.bombardmentTargets);
            } else {
                List<GroundRaidObjectivePlugin> objectives =
                        actionData == null ? null : actionData.objectives;
                if (objectives != null) {
                    for (GroundRaidObjectivePlugin objective : objectives) {
                        if (objective == null || objective.getMarinesAssigned() <= 0) {
                            continue;
                        }
                        add(touched, objective.getSource());
                        if (objective instanceof CommodityGroundRaidObjectivePluginImpl commodity) {
                            int units = commodity.getDeficitActuallyCaused();
                            if (units > 0 && commodity.getId() != null) {
                                deficits.add(new CommodityDeficit(commodity.getId(), units));
                            }
                        } else if (objective instanceof SolarArrayGroundRaidObjectivePluginImpl) {
                            solarArrayRemoved = !market.hasCondition(Conditions.SOLAR_ARRAY);
                        }
                    }
                }
                add(touched, fallback);
            }
            for (Industry industry : touched.values()) {
                SpecialItemData special = industry.getSpecialItem();
                industries.add(new IndustryState(industry.getId(), industry.getDisruptedDays(),
                        industry.getAICoreId(),
                        special == null ? null : special.getId(),
                        special == null ? null : special.getData()));
            }
        }
        return new Outcome(outcomeId, kind, market.getId(), actingPlayerId,
                market.getSize(), RecentUnrest.getPenalty(market), defaultReason(kind),
                kind.isBombardment() && market.hasCondition(Conditions.POLLUTION),
                solarArrayRemoved, decivilized, industries, deficits);
    }

    /**
     * The tooltip reason text. Vanilla prefers the player faction's display name once the player
     * faction is set up ({@code MarketCMD.java:1704-1707}, {@code :2609-2612}), which is identical on
     * both engines under the shared-faction model, so the constant fallback is only ever a fallback.
     */
    private static String defaultReason(Kind kind) {
        String reason = kind.isRaid() ? DEFAULT_RAID_REASON : DEFAULT_BOMBARD_REASON;
        try {
            if (Misc.isPlayerFactionSetUp()) {
                String name = Global.getSector().getPlayerFaction().getDisplayName();
                if (name != null && !name.isBlank()) {
                    return name + (kind.isRaid() ? " raid" : " bombardment");
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return reason;
        }
        return reason;
    }

    private static void addAll(Map<String, Industry> touched, List<Industry> industries) {
        if (industries == null) {
            return;
        }
        for (Industry industry : industries) {
            add(touched, industry);
        }
    }

    private static void add(Map<String, Industry> touched, Industry industry) {
        if (industry != null && industry.getId() != null) {
            touched.putIfAbsent(industry.getId(), industry);
        }
    }

    // ---- Apply ---------------------------------------------------------------------------------

    /**
     * Resolves the outcome's market on this engine and applies it. Milestone 1 targets NPC colonies,
     * whose markets are gen-time, so the id matches across engines; a missing market is logged and
     * dropped rather than thrown (colony lifecycle replication is milestone 2).
     */
    public static void applyToEngine(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        MarketAPI market = resolveMarket(outcome.marketId());
        if (market == null) {
            CoopLog.warn(CoopRaidOutcomeSync.class,
                    "Coop RAID_RESULT for unknown market " + outcome.marketId() + "; dropped");
            return;
        }
        applyToMarket(market, outcome);
    }

    private static MarketAPI resolveMarket(String marketId) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getEconomy() == null) {
                return null;
            }
            return sector.getEconomy().getMarket(marketId);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Could not resolve coop raid market " + marketId, ex);
            return null;
        }
    }

    /**
     * Applies a finished act to a market on this engine.
     *
     * <p><b>Deciv non-overlap.</b> When the act decivilized the colony, this applies the
     * {@code RECENTLY_BOMBARDED} memory flag and nothing else. {@code DecivTracker.decivilize}
     * strips industries, people, commodities and every {@code isDecivRemove} condition
     * ({@code DecivTracker.java:206-239}), so the industry/unrest/size/pollution state captured
     * alongside is meaningless there — and the DECIV delta arrives first (vanilla decivilizes at
     * {@code MarketCMD.java:2646}, before firing the saturation-bombardment listener at
     * {@code :2652}), so writing any of it afterwards would fight a transition that already landed.
     * The one thing decivilize does not touch is market memory, which is why the flag still rides.
     */
    public static void applyToMarket(MarketAPI market, Outcome outcome) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(outcome, "outcome");
        if (!outcome.decivilized()) {
            applyMarketState(market, outcome);
        }
        applyMemoryFlags(market, outcome);
    }

    private static void applyMarketState(MarketAPI market, Outcome outcome) {
        try {
            reduceSizeTo(market, outcome.marketSize());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid market size", ex);
        }
        try {
            applyUnrest(market, outcome.unrestPenalty(), outcome.unrestReason());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid unrest", ex);
        }
        try {
            applyConditions(market, outcome);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid conditions", ex);
        }
        try {
            applyIndustries(market, outcome);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid industry state", ex);
        }
        try {
            applyDeficits(market, outcome);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid commodity deficits", ex);
        }
    }

    /**
     * Steps the colony down to the reported size through vanilla's own reducer, which also swaps the
     * population condition and renormalizes ({@code CoreImmigrationPluginImpl.java:169-184}) — a bare
     * {@code setSize} would leave the market carrying the wrong population condition.
     *
     * <p>One-directional: nothing in this phase grows a colony, so a mirror that is already smaller
     * is left alone rather than inflated.
     */
    private static void reduceSizeTo(MarketAPI market, int targetSize) {
        int guard = 0;
        while (market.getSize() > targetSize && targetSize > 0 && guard++ < 16) {
            int before = market.getSize();
            CoreImmigrationPluginImpl.reduceMarketSize(market);
            if (market.getSize() >= before) {
                // Vanilla refuses below size 3; stop rather than spin.
                break;
            }
        }
    }

    /**
     * Converges recent-unrest on the absolute post-act penalty.
     *
     * <p>{@code RecentUnrest.add} is additive and also files the reason into the tooltip's
     * contributing-factors list; {@code setPenalty} is an absolute write with no tooltip entry
     * ({@code RecentUnrest.java:54-58}, {@code :125-127}). Neither alone is right, so the applier
     * derives the delta from <em>its own</em> current value and uses {@code add} for it — that lands
     * exactly on the reported absolute, files the reason, and self-corrects a mirror that had
     * drifted. A drifted-high mirror falls through to the absolute write. Re-applying computes a zero
     * delta and writes the same absolute, so it is a no-op.
     */
    static void applyUnrest(MarketAPI market, int absolutePenalty, String reason) {
        int current = RecentUnrest.getPenalty(market);
        if (absolutePenalty <= 0 && current <= 0) {
            // Never touch RecentUnrest.get here: it would create the condition to say "no unrest".
            return;
        }
        RecentUnrest unrest = RecentUnrest.get(market);
        if (unrest == null) {
            return;
        }
        int delta = absolutePenalty - current;
        if (delta > 0) {
            unrest.add(delta, reason == null || reason.isBlank() ? DEFAULT_RAID_REASON : reason);
        }
        if (unrest.getPenalty() != absolutePenalty) {
            unrest.setPenalty(absolutePenalty);
        }
    }

    private static void applyConditions(MarketAPI market, Outcome outcome) {
        if (outcome.pollutionAdded() && !market.hasCondition(Conditions.POLLUTION)) {
            market.addCondition(Conditions.POLLUTION);
        }
        if (outcome.solarArrayRemoved() && market.hasCondition(Conditions.SOLAR_ARRAY)) {
            market.removeCondition(Conditions.SOLAR_ARRAY);
        }
    }

    private static void applyIndustries(MarketAPI market, Outcome outcome) {
        boolean changed = false;
        for (IndustryState state : outcome.industries()) {
            Industry industry = market.getIndustry(state.industryId());
            if (industry == null) {
                CoopLog.warn(CoopRaidOutcomeSync.class, "Coop RAID_RESULT names industry "
                        + state.industryId() + " absent from market " + outcome.marketId());
                continue;
            }
            if (Math.abs(industry.getDisruptedDays() - state.disruptedDays()) > DISRUPTION_EPSILON) {
                industry.setDisrupted(state.disruptedDays());
                changed = true;
            }
            if (state.aiCoreId().isEmpty() && industry.getAICoreId() != null) {
                industry.setAICoreId(null);
                changed = true;
            }
            if (state.specialItemId().isEmpty() && industry.getSpecialItem() != null) {
                industry.setSpecialItem(null);
                changed = true;
            }
        }
        if (changed) {
            // Vanilla does the same after a raid resolves (MarketCMD.java:1788).
            market.reapplyIndustries();
        }
    }

    /**
     * Re-hangs the availability penalty a raid-for-valuables caused. Vanilla stamps the mod with
     * {@code Misc.genUID()} ({@code CommodityGroundRaidObjectivePluginImpl.java:218-221}); ours is
     * keyed by outcome id and commodity so a second apply overwrites its own mod instead of stacking
     * a second one.
     */
    private static void applyDeficits(MarketAPI market, Outcome outcome) {
        for (CommodityDeficit deficit : outcome.deficits()) {
            if (deficit.units() <= 0) {
                continue;
            }
            CommodityOnMarketAPI commodity = market.getCommodityData(deficit.commodityId());
            if (commodity == null || commodity.getAvailableStat() == null) {
                continue;
            }
            commodity.getAvailableStat().addTemporaryModFlat(ShippingDisruption.ACCESS_LOSS_DURATION,
                    "coop_raid_" + outcome.outcomeId() + "_" + deficit.commodityId(),
                    "Recent raid", -deficit.units());
        }
    }

    /**
     * The market-memory bookkeeping vanilla does alongside the state change: the recently-raided /
     * recently-bombarded flags that gate dialog options and military response
     * ({@code MarketCMD.java:1717-1719}, {@code :2668-2669}), and the escalating defender bonus that
     * makes a second raid on the same colony harder ({@code MarketCMD.java:1474-1483},
     * {@code :1828}). The defender bonus is inherently additive and bounded by vanilla's own cap;
     * the ledger is what guarantees it lands exactly once per act on each engine.
     */
    private static void applyMemoryFlags(MarketAPI market, Outcome outcome) {
        try {
            MemoryAPI memory = market.getMemoryWithoutUpdate();
            if (memory == null) {
                return;
            }
            Misc.setFlagWithReason(memory,
                    outcome.kind().isRaid() ? MemFlags.RECENTLY_RAIDED : MemFlags.RECENTLY_BOMBARDED,
                    Factions.PLAYER, true, 30f);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid market memory flags", ex);
        }
        if (!outcome.kind().isRaid()) {
            return;
        }
        // Separately guarded: the flag above is what gates dialog options and must not be lost to a
        // failure in the raid-history bookkeeping below.
        try {
            Misc.setRaidedTimestamp(market);
            MarketCMD.applyDefenderIncreaseFromRaid(market);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopRaidOutcomeSync.class, "Failed to apply coop raid history bookkeeping", ex);
        }
    }
}
