package coop.colony;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.listeners.PlayerColonizationListener;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import coop.campaign.CoopDelimited;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 24 milestone 2: replication of colony <em>lifecycle</em> — a colony founded or abandoned by
 * either player exists, or stops existing, on both engines.
 *
 * <p><b>Model.</b> The colonizing player resolves colonization locally through the vanilla dialog
 * (cost, confirmation, whatever skills and settings add). Vanilla's
 * {@link PlayerColonizationListener} then tells us it happened, we read the <em>resulting</em> market
 * off the engine, and ship it as one {@code COLONY_FOUNDED}. The host canonicalizes and rebroadcasts;
 * the other engine replays the same public-API recipe onto its own copy of the same planet.
 * Abandonment ships the inverse, and the applier runs vanilla's own teardown rather than a
 * hand-written guess at it.
 *
 * <p><b>Why the market id needs no mapping.</b> Colonization does not create a market. Every planet
 * already carries a gen-time planet-condition market built as
 * {@code createMarket("market_" + planet.getId(), ...)} ({@code PlanetConditionGenerator.java:134},
 * {@code Misc.java:3581}), and colonizing flips that same object into a real colony. Seed-locked
 * worldgen means both engines minted the same id from the same planet id, so ids match by
 * construction. The catch is that a planet-condition market is <em>not in the economy</em> — nothing
 * in the gen path calls {@code addMarket} — so {@code economy.getMarket(id)} cannot find it before
 * colonization and cannot find it again after abandonment. Both ids therefore ride the wire and
 * {@link #resolveMarket} goes through the planet entity first.
 *
 * <p><b>Captured state, not the static recipe.</b> {@code Misc.createColonyStatic}
 * ({@code Misc.java:6510-6543}, commented out) shows the <em>shape</em> of colony creation, but the
 * live closed-source flow can add different starting industries (skills, settings) and the player can
 * name the colony. So the payload carries what the colonizing engine actually ended up with — size,
 * name, faction, conditions with their surveyed flags, industries, submarkets, construction queue,
 * survey level, free port — and the applier replays that through the recipe's ordering.
 *
 * <p><b>The construction queue is founding state, not management state.</b> This was assumed away when
 * the milestone was built and corrected on 2026-08-25 after a live session: vanilla colonization
 * auto-queues a {@code SPACEPORT} that the player never ordered, so a colony is founded with one
 * industry (population) and a non-empty queue. Leaving the queue off the payload left the mirror's
 * spaceport unbuilt until someone happened to open and close the colony screen and milestone 3's
 * diff-on-close shipped it. The queue therefore rides {@code COLONY_FOUNDED}, using
 * {@link CoopColonyManagement.QueueItem} and its codec and reconciler verbatim rather than a second
 * format for the same thing.
 *
 * <p><b>Deliberately not here (milestone 3).</b> AI cores, industry improvements, the admin, and any
 * post-founding management edit. A just-founded colony has none of those, and they belong to the
 * diff-on-close {@code COLONY_MGMT} channel. The payload extends by adding fields to the industry
 * record line or new record tags; both engines run the same build (the handshake pins the build hash),
 * so there is no wire-compatibility window to preserve.
 */
public final class CoopColonySync {

    private CoopColonySync() {
    }

    /** Which end of the lifecycle this event reports. */
    public enum Kind {
        FOUNDED,
        ABANDONED
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final char RECORD_SEPARATOR = '\n';
    private static final String HEADER_TAG = "H";
    private static final String CONDITION_TAG = "C";
    private static final String INDUSTRY_TAG = "I";
    private static final String SUBMARKET_TAG = "S";
    /** Milestone 3's tag, reused verbatim: same record shape, same codec, same reconciler. */
    private static final String QUEUE_TAG = CoopColonyManagement.QUEUE_TAG;
    private static final int HEADER_FIELDS = 12;

    /**
     * How many drains a pending colonization gets before it is given up on. See
     * {@link ColonizationCapture#drainPending()} — one drain is one campaign frame, so this is about
     * ten seconds of real time at 60 fps, orders of magnitude more than the flow needs.
     */
    static final int MAX_DRAIN_ATTEMPTS = 600;

    /**
     * One market condition and whether the colonizing engine had it surveyed.
     *
     * <p>Conditions are gen-time on both engines <em>except</em> the population condition
     * colonization adds and the {@code DECIVILIZED} &rarr; {@code DECIVILIZED_SUBPOP} swap it
     * performs, so the list is applied as an absolute set (add missing, drop extra) rather than as
     * one-directional presence assertions. The surveyed flag rides along because colonization marks
     * every condition surveyed ({@code Misc.setFullySurveyed}, {@code Misc.java:3003-3009}) and the
     * mirror's copy of the same planet may never have been surveyed at all.
     */
    public record ConditionState(String conditionId, boolean surveyed) {

        public ConditionState {
            conditionId = requireText(conditionId, "conditionId");
        }

        String encode() {
            return CoopDelimited.field(CONDITION_TAG)
                    + FIELD_SEPARATOR + CoopDelimited.field(conditionId)
                    + FIELD_SEPARATOR + surveyed;
        }

        static ConditionState decode(List<String> fields) {
            if (fields.size() != 3) {
                throw new IllegalArgumentException(
                        "Expected 3 colony condition fields, got " + fields.size());
            }
            return new ConditionState(fields.get(1), Boolean.parseBoolean(fields.get(2)));
        }
    }

    /**
     * One colony lifecycle transition.
     *
     * @param eventId        dedup key, {@code actingPlayerId + ":" + monotonic counter}, session
     *                       scoped like the {@link Ledger} that holds it.
     * @param planetId       the colonized entity's gen-time engine id. The primary resolution key:
     *                       the market is outside the economy on both sides of the lifecycle.
     * @param marketId       the market's id, {@code "market_" + planetId} for every gen-time planet.
     *                       Carried as a fallback rather than assumed, because the abandoned-station
     *                       and station-market paths do not follow that convention.
     * @param name           the colony's display name. The player may have named it.
     * @param size           absolute post-founding size, normally 3.
     * @param surveyLevel    {@code MarketAPI.SurveyLevel} constant name.
     * @param storageUnlocked founding pays off the storage submarket
     *                       ({@code Misc.java:6540-6542}). There is no getter for it —
     *                       {@code StoragePlugin.playerPaidToUnlock} is private with a setter only —
     *                       so this is captured as "the colony has a storage submarket" rather than
     *                       read back, which is the same thing at founding time.
     * @param queue          the construction queue as it stands at founding time, in build order.
     *                       Never empty in practice: vanilla auto-queues a spaceport. Decoding
     *                       tolerates zero {@code Q} lines all the same, for a colony founded with a
     *                       genuinely empty queue.
     *                       <p>Every state field is meaningless for {@link Kind#ABANDONED}: vanilla
     *                       fires that report <em>after</em> the teardown has already run, so there is
     *                       nothing left to read, and the applier does not need it — it re-runs the
     *                       same vanilla teardown.
     */
    public record Event(String eventId, Kind kind, String planetId, String marketId,
                        String actingPlayerId, String name, String factionId, int size,
                        boolean freePort, String surveyLevel, boolean storageUnlocked,
                        List<ConditionState> conditions, List<String> industries,
                        List<String> submarkets, List<CoopColonyManagement.QueueItem> queue) {

        public Event {
            eventId = requireText(eventId, "eventId");
            kind = Objects.requireNonNull(kind, "kind");
            planetId = requireText(planetId, "planetId");
            marketId = CoopDelimited.normalize(marketId);
            actingPlayerId = CoopDelimited.normalize(actingPlayerId);
            name = CoopDelimited.normalize(name);
            factionId = CoopDelimited.normalize(factionId);
            surveyLevel = CoopDelimited.normalize(surveyLevel);
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            industries = industries == null ? List.of() : List.copyOf(industries);
            submarkets = submarkets == null ? List.of() : List.copyOf(submarkets);
            queue = queue == null ? List.of() : List.copyOf(queue);
        }

        /** An abandonment: identity only, because vanilla reports it post-teardown. */
        public static Event abandoned(String eventId, String actingPlayerId, String planetId,
                                      String marketId) {
            return new Event(eventId, Kind.ABANDONED, planetId, marketId, actingPlayerId, "", "", 0,
                    false, "", false, List.of(), List.of(), List.of(), List.of());
        }

        /**
         * The whole event as one self-contained delimited blob: the envelope's flat JSON parser has
         * no arrays, so list-shaped bodies ship as a single opaque string (the
         * {@code CoopBaseRecord.encodeSet} convention). Header line first, then one line per
         * condition, industry and submarket, then one per construction-queue entry <em>in queue
         * order</em> — the order is the build order and has to survive the wire.
         */
        public String encode() {
            StringBuilder out = new StringBuilder(192);
            out.append(CoopDelimited.field(HEADER_TAG))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(eventId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(kind.name()))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(planetId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(marketId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(actingPlayerId))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(name))
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(factionId))
                    .append(FIELD_SEPARATOR).append(size)
                    .append(FIELD_SEPARATOR).append(freePort)
                    .append(FIELD_SEPARATOR).append(CoopDelimited.field(surveyLevel))
                    .append(FIELD_SEPARATOR).append(storageUnlocked);
            for (ConditionState condition : conditions) {
                out.append(RECORD_SEPARATOR).append(condition.encode());
            }
            for (String industry : industries) {
                out.append(RECORD_SEPARATOR).append(CoopDelimited.field(INDUSTRY_TAG))
                        .append(FIELD_SEPARATOR).append(CoopDelimited.field(industry));
            }
            for (String submarket : submarkets) {
                out.append(RECORD_SEPARATOR).append(CoopDelimited.field(SUBMARKET_TAG))
                        .append(FIELD_SEPARATOR).append(CoopDelimited.field(submarket));
            }
            for (CoopColonyManagement.QueueItem item : queue) {
                out.append(RECORD_SEPARATOR).append(item.encode());
            }
            return out.toString();
        }
    }

    public static Event decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split(String.valueOf(RECORD_SEPARATOR), -1);
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new IllegalArgumentException("Empty colony lifecycle payload");
        }
        List<String> header = CoopDelimited.split(lines[0]);
        if (header.size() != HEADER_FIELDS || !HEADER_TAG.equals(header.get(0))) {
            throw new IllegalArgumentException("Malformed colony lifecycle header");
        }
        List<ConditionState> conditions = new ArrayList<>();
        List<String> industries = new ArrayList<>();
        List<String> submarkets = new ArrayList<>();
        List<CoopColonyManagement.QueueItem> queue = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            List<String> fields = CoopDelimited.split(lines[i]);
            switch (fields.get(0)) {
                case CONDITION_TAG -> conditions.add(ConditionState.decode(fields));
                case INDUSTRY_TAG -> industries.add(requireSingleValue(fields, "industry"));
                case SUBMARKET_TAG -> submarkets.add(requireSingleValue(fields, "submarket"));
                case QUEUE_TAG -> queue.add(CoopColonyManagement.QueueItem.decode(fields));
                default -> throw new IllegalArgumentException(
                        "Unknown colony lifecycle record tag: " + fields.get(0));
            }
        }
        return new Event(header.get(1), parseKind(header.get(2)), header.get(3), header.get(4),
                header.get(5), header.get(6), header.get(7), parseInt(header.get(8), "size"),
                Boolean.parseBoolean(header.get(9)), header.get(10),
                Boolean.parseBoolean(header.get(11)), conditions, industries, submarkets, queue);
    }

    private static String requireSingleValue(List<String> fields, String what) {
        if (fields.size() != 2) {
            throw new IllegalArgumentException(
                    "Expected 2 colony " + what + " fields, got " + fields.size());
        }
        return fields.get(1);
    }

    private static Kind parseKind(String raw) {
        try {
            return Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown colony lifecycle kind: " + raw, ex);
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
     * Session-scoped record of which lifecycle events have been applied.
     *
     * <p>Two stores, because two different things have to be true at once.
     *
     * <ul>
     *   <li><b>Applied ids</b> kill the echo. The host rebroadcasts the originator's own event
     *       verbatim, and the id it carries was minted once by the originator, so the second sighting
     *       is dropped there and on any duplicate delivery.</li>
     *   <li><b>Latest kind per planet</b> makes this latest-wins rather than once-only. A colony is a
     *       <em>value</em> that oscillates — founded, abandoned, founded again on the same planet —
     *       so a set keyed by planet would swallow everything after the first transition, exactly the
     *       failure {@code CoopWorldDelta.Kind.latestWins()} exists to avoid. Keying by event id and
     *       comparing the kind instead applies each genuine transition once and makes a redundant one
     *       (a stale FOUNDED arriving at an already-founded colony) a no-op.</li>
     * </ul>
     */
    public static final class Ledger {
        private final Set<String> appliedEventIds = new LinkedHashSet<>();
        private final Map<String, Kind> latestByPlanet = new HashMap<>();

        /** Returns true only for an event that is both new and a genuine state change. */
        public boolean apply(Event event) {
            Objects.requireNonNull(event, "event");
            if (!appliedEventIds.add(event.eventId())) {
                return false;
            }
            Kind previous = latestByPlanet.put(event.planetId(), event.kind());
            return previous != event.kind();
        }

        public boolean isApplied(String eventId) {
            return appliedEventIds.contains(requireText(eventId, "eventId"));
        }

        /** The last lifecycle state applied for a planet, or {@code null} if none. */
        public Kind latestKind(String planetId) {
            return latestByPlanet.get(requireText(planetId, "planetId"));
        }

        public int size() {
            return appliedEventIds.size();
        }

        public void clear() {
            appliedEventIds.clear();
            latestByPlanet.clear();
        }
    }

    // ---- Capture -------------------------------------------------------------------------------

    /** What the capture listener needs from the replicator; the replicator implements it. */
    public interface Sink {
        /**
         * False when this client must not capture: no active session, or the applier is mid-replay of
         * a remote event (in which case the market mutations we are re-driving would be captured and
         * bounced back as a fresh colonization).
         */
        boolean shouldCaptureColonyLifecycle();

        /** The local player id, used as the event-id prefix. */
        String colonyActingPlayerId();

        void onColonyLifecycleCaptured(Event event);
    }

    /**
     * Vanilla's colonization hook, registered through the listener manager.
     *
     * <p><b>Founding is captured a frame late, on purpose.</b> The listener hands over a
     * {@link PlanetAPI} and nothing else, and it is fired from the closed campaign code, so we cannot
     * see where in the colony-construction sequence it sits. The abandonment half of the same
     * interface is fired <em>after</em> its whole teardown ({@code AbandonMarketPluginImpl.java:121-123}),
     * which suggests the same for founding, but "suggests" is not good enough to read a market's
     * industry list off. So a colonization report only marks the planet pending, and
     * {@link #drainPending()} takes the snapshot from the replicator's next frame, once the engine has
     * definitely finished. Nothing else runs in between: the campaign is single threaded.
     *
     * <p><b>Abandonment is captured inline</b> — vanilla has already destroyed everything worth
     * reading by the time it reports, and there is nothing to wait for.
     */
    public static final class ColonizationCapture implements PlayerColonizationListener {
        private final Sink sink;
        private long counter;
        /** Planet id to remaining drain attempts, in report order. */
        private final Map<String, Integer> pending = new LinkedHashMap<>();

        public ColonizationCapture(Sink sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        @Override
        public void reportPlayerColonizedPlanet(PlanetAPI planet) {
            if (planet == null || planet.getId() == null || planet.getId().isBlank()) {
                return;
            }
            if (!sink.shouldCaptureColonyLifecycle()) {
                return;
            }
            pending.putIfAbsent(planet.getId(), MAX_DRAIN_ATTEMPTS);
        }

        @Override
        public void reportPlayerAbandonedColony(MarketAPI colony) {
            if (colony == null || !sink.shouldCaptureColonyLifecycle()) {
                return;
            }
            try {
                String planetId = primaryEntityId(colony);
                if (planetId.isEmpty()) {
                    CoopLog.warn(CoopColonySync.class, "Coop cannot report abandoned colony "
                            + colony.getId() + ": it has no primary entity id");
                    return;
                }
                // A pending colonization for the same planet is now moot; dropping it also stops the
                // drain from resurrecting a colony that no longer exists.
                pending.remove(planetId);
                String actingPlayerId = sink.colonyActingPlayerId();
                sink.onColonyLifecycleCaptured(Event.abandoned(nextEventId(actingPlayerId),
                        actingPlayerId, planetId, colony.getId()));
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopColonySync.class, "Failed to capture coop colony abandonment", ex);
            }
        }

        /**
         * Called once per frame by the replicator. Emits a {@code COLONY_FOUNDED} for every pending
         * planet whose market the engine has finished turning into a colony, and gives up on one that
         * never gets there rather than retrying forever.
         */
        public void drainPending() {
            if (pending.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<String, Integer>> pendingEntries = pending.entrySet().iterator();
            while (pendingEntries.hasNext()) {
                Map.Entry<String, Integer> entry = pendingEntries.next();
                String planetId = entry.getKey();
                try {
                    MarketAPI market = resolveMarket(planetId, null);
                    if (market != null && isColonized(market)) {
                        pendingEntries.remove();
                        emitFounded(planetId, market);
                        continue;
                    }
                } catch (RuntimeException | LinkageError ex) {
                    pendingEntries.remove();
                    CoopLog.warn(CoopColonySync.class,
                            "Failed to capture coop colonization of " + planetId, ex);
                    continue;
                }
                if (entry.getValue() <= 1) {
                    pendingEntries.remove();
                    CoopLog.warn(CoopColonySync.class, "Coop saw a colonization of " + planetId
                            + " but its market never became a player colony; nothing was replicated");
                } else {
                    entry.setValue(entry.getValue() - 1);
                }
            }
        }

        private void emitFounded(String planetId, MarketAPI market) {
            if (!sink.shouldCaptureColonyLifecycle()) {
                return;
            }
            String actingPlayerId = sink.colonyActingPlayerId();
            sink.onColonyLifecycleCaptured(captureFounded(nextEventId(actingPlayerId),
                    actingPlayerId, planetId, market));
        }

        private String nextEventId(String actingPlayerId) {
            return (actingPlayerId == null || actingPlayerId.isBlank() ? "local" : actingPlayerId)
                    + ":" + (++counter);
        }

        /** Session teardown: ids restart and no colonization may survive into the next session. */
        public void reset() {
            counter = 0;
            pending.clear();
        }

        /** Test/diagnostic seam: how many colonizations are still waiting for their market. */
        public int pendingCount() {
            return pending.size();
        }
    }

    /** A planet-condition market has not been colonized; a player colony is both flags at once. */
    static boolean isColonized(MarketAPI market) {
        return market != null && !market.isPlanetConditionMarketOnly() && market.isPlayerOwned();
    }

    private static String primaryEntityId(MarketAPI market) {
        SectorEntityToken primary = market.getPrimaryEntity();
        String id = primary == null ? null : primary.getId();
        return id == null ? "" : id.trim();
    }

    /** Reads the finished colony off the live market. */
    public static Event captureFounded(String eventId, String actingPlayerId, String planetId,
                                       MarketAPI market) {
        Objects.requireNonNull(market, "market");
        List<ConditionState> conditions = new ArrayList<>();
        List<MarketConditionAPI> live = market.getConditions();
        if (live != null) {
            for (MarketConditionAPI condition : live) {
                if (condition == null || condition.getId() == null) {
                    continue;
                }
                conditions.add(new ConditionState(condition.getId(), condition.isSurveyed()));
            }
        }
        List<String> industries = new ArrayList<>();
        List<Industry> liveIndustries = market.getIndustries();
        if (liveIndustries != null) {
            for (Industry industry : liveIndustries) {
                if (industry != null && industry.getId() != null) {
                    industries.add(industry.getId());
                }
            }
        }
        List<String> submarkets = new ArrayList<>();
        List<SubmarketAPI> liveSubmarkets = market.getSubmarketsCopy();
        if (liveSubmarkets != null) {
            for (SubmarketAPI submarket : liveSubmarkets) {
                if (submarket != null && submarket.getSpecId() != null) {
                    submarkets.add(submarket.getSpecId());
                }
            }
        }
        // Read in the same pass as the industries, off the same finished market: colonization's
        // auto-queued spaceport is in place by the time the drain gets here.
        List<CoopColonyManagement.QueueItem> queue = CoopColonyManagement.captureQueue(market);
        MarketAPI.SurveyLevel level = market.getSurveyLevel();
        return new Event(eventId, Kind.FOUNDED, planetId, market.getId(), actingPlayerId,
                market.getName(), market.getFactionId(), market.getSize(), market.isFreePort(),
                level == null ? "" : level.name(),
                submarkets.contains(Submarkets.SUBMARKET_STORAGE),
                conditions, industries, submarkets, queue);
    }

    // ---- Apply ---------------------------------------------------------------------------------

    /**
     * Resolves the event's market on this engine and applies it. A missing planet or market is logged
     * and dropped, never thrown: the alternative is one malformed message killing the net pump.
     */
    public static void applyToEngine(Event event) {
        Objects.requireNonNull(event, "event");
        MarketAPI market = resolveMarket(event.planetId(), event.marketId());
        if (market == null) {
            CoopLog.warn(CoopColonySync.class, "Coop " + event.kind() + " names planet "
                    + event.planetId() + " / market " + event.marketId()
                    + ", neither of which exists here; dropped");
            return;
        }
        applyToMarket(market, event);
    }

    /**
     * Planet entity first, economy second. A planet-condition market — which is what this planet
     * carries before a {@code COLONY_FOUNDED} and again after a {@code COLONY_ABANDONED} — is never
     * registered with the economy, so the economy lookup only ever answers for a live colony.
     */
    static MarketAPI resolveMarket(String planetId, String marketId) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return null;
            }
            if (planetId != null && !planetId.isBlank()) {
                SectorEntityToken entity = sector.getEntityById(planetId);
                if (entity != null && entity.getMarket() != null) {
                    return entity.getMarket();
                }
            }
            if (marketId != null && !marketId.isBlank() && sector.getEconomy() != null) {
                return sector.getEconomy().getMarket(marketId);
            }
            return null;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonySync.class,
                    "Could not resolve coop colony market for planet " + planetId, ex);
            return null;
        }
    }

    public static void applyToMarket(MarketAPI market, Event event) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(event, "event");
        if (event.kind() == Kind.FOUNDED) {
            applyFounded(market, event);
        } else {
            applyAbandoned(market, event);
        }
    }

    /**
     * Turns this engine's copy of the planet-condition market into the same colony, driven by the
     * captured state rather than by hardcoded constants.
     *
     * <p><b>Ordering follows the live recipe, not the commented one.</b>
     * {@code Misc.createColonyStatic} ({@code Misc.java:6510-6543}) calls {@code setSize(3)}
     * <em>before</em> {@code setPlanetConditionMarketOnly(false)}, which cannot work: while that flag
     * is set the engine's market reports size 1 and ignores {@code setSize} outright
     * ({@code PlanetConditionMarket.getSize} returns a constant, {@code setSize} is an empty method).
     * The live promotion in {@code PK_CMD.convertSentinelToColony} ({@code PK_CMD.java:81-148}) clears
     * the flag first, and so does this. The commented recipe is a sketch, not a working sequence.
     *
     * <p>Every step is guarded on its own, because a half-built colony that is registered with the
     * economy is far better than an exception thrown out of the net pump: the market channel and
     * milestone 3's management deltas can still converge the rest.
     */
    private static void applyFounded(MarketAPI market, Event event) {
        step(event, "flags", () -> {
            market.setPlanetConditionMarketOnly(false);
            if (!event.name().isEmpty()) {
                market.setName(event.name());
            }
            if (!event.factionId().isEmpty()) {
                market.setFactionId(event.factionId());
            }
        });
        step(event, "conditions", () -> applyConditions(market, event));
        // Population condition in place first, then the size that matches it, then vanilla's own
        // re-application pass -- the same order CoreImmigrationPluginImpl.increaseMarketSize uses
        // when it grows a colony a tier (CoreImmigrationPluginImpl.java:151-162).
        step(event, "size", () -> {
            if (event.size() > 0 && market.getSize() != event.size()) {
                market.setSize(event.size());
            }
            market.reapplyConditions();
        });
        step(event, "industries", () -> {
            for (String industryId : event.industries()) {
                if (!market.hasIndustry(industryId)) {
                    market.addIndustry(industryId);
                }
            }
        });
        // Vanilla colonization auto-queues a spaceport, so a founding is not queue-free. Reconciled
        // through milestone 3's own applier: it rewrites the queue to the reported list only when the
        // two differ, which is what keeps a re-applied founding from appending the same entry twice.
        step(event, "construction queue",
                () -> CoopColonyManagement.applyQueue(market, event.queue()));
        step(event, "submarkets", () -> {
            for (String specId : event.submarkets()) {
                if (!market.hasSubmarket(specId)) {
                    market.addSubmarket(specId);
                }
            }
        });
        step(event, "survey level", () -> applySurveyLevel(market, event.surveyLevel()));
        step(event, "economy registration", () -> {
            if (!market.isInEconomy() && Global.getSector() != null
                    && Global.getSector().getEconomy() != null) {
                Global.getSector().getEconomy().addMarket(market, true);
            }
        });
        step(event, "ownership", () -> {
            SectorEntityToken primary = market.getPrimaryEntity();
            if (primary != null && !event.factionId().isEmpty()) {
                primary.setFaction(event.factionId());
            }
            market.setPlayerOwned(true);
            market.setFreePort(event.freePort());
            market.reapplyIndustries();
        });
        step(event, "storage unlock", () -> {
            if (event.storageUnlocked()) {
                unlockStorage(market);
            }
        });
        CoopLog.info(CoopColonySync.class, "Coop built mirrored colony " + market.getId()
                + " name=" + event.name() + " size=" + event.size()
                + " industries=" + event.industries().size()
                + " submarkets=" + event.submarkets().size()
                + " queued=" + event.queue().size());
    }

    /**
     * Applies the captured condition set absolutely: add what is missing, drop what this engine has
     * and the colonizing engine does not, and align the surveyed flags.
     *
     * <p>Absolute rather than additive because colonization performs a <em>swap</em> —
     * {@code DECIVILIZED} out, {@code DECIVILIZED_SUBPOP} in ({@code Misc.java:6519-6523}) — which
     * one-directional presence assertions cannot express. Both engines start from the same
     * seed-locked planet conditions, so the removals this computes are exactly the ones colonization
     * made.
     */
    private static void applyConditions(MarketAPI market, Event event) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ConditionState condition : event.conditions()) {
            wanted.add(condition.conditionId());
        }
        List<MarketConditionAPI> live = market.getConditions();
        if (live != null) {
            for (MarketConditionAPI condition : new ArrayList<>(live)) {
                if (condition != null && condition.getId() != null
                        && !wanted.contains(condition.getId())) {
                    market.removeCondition(condition.getId());
                }
            }
        }
        for (ConditionState condition : event.conditions()) {
            if (!market.hasCondition(condition.conditionId())) {
                market.addCondition(condition.conditionId());
            }
        }
        List<MarketConditionAPI> applied = market.getConditions();
        if (applied == null) {
            return;
        }
        Map<String, Boolean> surveyedById = new HashMap<>();
        for (ConditionState condition : event.conditions()) {
            surveyedById.put(condition.conditionId(), condition.surveyed());
        }
        for (MarketConditionAPI condition : applied) {
            Boolean surveyed = condition == null ? null : surveyedById.get(condition.getId());
            if (surveyed != null && condition.isSurveyed() != surveyed) {
                condition.setSurveyed(surveyed);
            }
        }
    }

    private static void applySurveyLevel(MarketAPI market, String surveyLevel) {
        if (surveyLevel.isEmpty()) {
            return;
        }
        MarketAPI.SurveyLevel level;
        try {
            level = MarketAPI.SurveyLevel.valueOf(surveyLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            CoopLog.warn(CoopColonySync.class, "Unknown coop colony survey level " + surveyLevel);
            return;
        }
        if (market.getSurveyLevel() != level) {
            market.setSurveyLevel(level);
        }
    }

    /**
     * {@code StoragePlugin.playerPaidToUnlock} is private with a setter and no getter
     * ({@code StoragePlugin.java:19}, {@code :90-92}), so this is write-only and unconditional — the
     * same call vanilla's own colony recipe makes.
     */
    private static void unlockStorage(MarketAPI market) {
        SubmarketAPI storage = market.getSubmarket(Submarkets.SUBMARKET_STORAGE);
        if (storage != null && storage.getPlugin() instanceof StoragePlugin plugin) {
            plugin.setPlayerPaidToUnlock(true);
        }
    }

    /**
     * Runs vanilla's own colony teardown, which is what abandonment <em>is</em>:
     * {@code AbandonMarketPluginImpl.abandonConfirmed} charges the evacuation cost and then calls
     * {@code DecivTracker.removeColony(market, false)} ({@code AbandonMarketPluginImpl.java:121}).
     * Calling the same public static rather than reimplementing it is the choice the DECIV world-delta
     * already made for {@code DecivTracker.decivilize}.
     *
     * <p>That single call is the whole inverse recipe: admin cleared, connected entities set neutral,
     * {@code setPlanetConditionMarketOnly(true)}, faction neutral, comm directory and people cleared,
     * commodities cleared, every {@code isDecivRemove} condition removed (the population condition
     * among them), every industry removed, every submarket removed — storage contents included, which
     * is why vanilla's confirmation warns that they are lost — {@code $wasCivilized} set, size forced
     * to 1 with the population weight renormalized, {@code setPlayerOwned(false)}, and the market
     * dropped from the economy ({@code DecivTracker.java:291-360}). {@code withRuins} is
     * {@code false}, matching abandonment: no ruins condition is minted.
     *
     * <p>Deliberately <em>not</em> mirrored: the credit cost and the shutdown refund. Those are the
     * abandoning player's own wallet, the same rule loot follows, and vanilla has already charged them
     * on the originating engine.
     */
    private static void applyAbandoned(MarketAPI market, Event event) {
        if (market.isPlanetConditionMarketOnly()) {
            // Already torn down here — a stale or duplicate report. Re-running the teardown on a
            // planet-condition market would strip its gen-time planet conditions for nothing.
            CoopLog.info(CoopColonySync.class, "Coop COLONY_ABANDONED for " + event.planetId()
                    + " is already a planet-condition market; nothing to do");
            return;
        }
        step(event, "teardown", () -> DecivTracker.removeColony(market, false));
        CoopLog.info(CoopColonySync.class, "Coop tore down mirrored colony " + market.getId()
                + " on " + event.planetId());
    }

    private static void step(Event event, String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopColonySync.class, "Failed to apply coop " + event.kind() + " " + what
                    + " for " + event.planetId(), ex);
        }
    }
}
