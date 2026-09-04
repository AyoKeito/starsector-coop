package coop.stats;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 21: the host-tallied counters behind the "Coop Stats" intel page, and the shape they take in
 * the save.
 *
 * <h2>Why this class looks the way it does</h2>
 *
 * <p><b>It is a plain mutable bean, not a record.</b> It rides into the save inside
 * {@code Global.getSector().getPersistentData()} and comes back out through XStream 1.4.10, which
 * runs neither constructors nor field initialisers on load. So: no records, no {@code final} fields,
 * no lambdas held in fields, no {@code Optional}, and nothing but {@code long}/{@code float}/
 * {@code String}/{@code ArrayList}/{@code HashMap} on the wire into the save. Every collection getter
 * lazily replaces a {@code null} the deserialiser left behind, which is what makes a save written by
 * an older build load without a migration step.
 *
 * <p><b>Sets are {@link ArrayList}s.</b> {@code marketsTradedWith} and {@code systemsVisited} are
 * conceptually sets, but a list with a linear {@code contains} check on insert serialises to
 * something a human can read in a save file and cannot surprise anyone with a hash-order change
 * between builds. The cardinalities are tens, not thousands.
 *
 * <p><b>The tally hooks are on the DTO.</b> There is no separate tally object: the counters and the
 * ten-odd methods that bump them are the same thing, and splitting them would only add a layer for
 * the pump to hold. Every hook is pure state mutation with no engine access, so the whole class is
 * unit-testable with no sector.
 *
 * <h2>Idempotency is the caller's problem</h2>
 *
 * Except where set semantics are inherent ({@link #noteSystemVisited} and the market half of
 * {@link #noteTrade}), every hook is a blind increment. The pump owns de-duplication — it already
 * runs a ledger for {@code WORLD_DELTA} echoes and a {@code hostSeq} check for mission claims, and
 * duplicating those here would give two places to get it wrong. The gauges
 * ({@link #noteNetWorth}, {@link #noteColoniesHeld}, {@link #noteDaysElapsed}) are idempotent by
 * construction because they set rather than add.
 *
 * <h2>What the wire can actually pay for</h2>
 *
 * The plan's stat set claimed "team-total ships destroyed" was free on today's wire. It is not:
 * {@code CoopMessages.battleResult} (CoopMessages.java:934-944) carries {@code battleId},
 * {@code engagingPlayerId}, {@code outcome}, {@code engagingFleetSize} and an opaque {@code body},
 * and the body decodes to {@link coop.combat.CoopBattleResult} whose only casualty data is
 * {@code destroyedFleetIds} — whole NPC <em>fleets</em> that no longer exist, plus surviving rosters
 * with no pre-battle roster to diff them against. Per-ship kills would need new replication, which
 * this phase is not allowed to add. Ships destroyed is therefore dropped and
 * {@link #noteFleetsDestroyed(int)} takes its place: {@code destroyedFleetIds().size()} is genuinely
 * free, and {@code CoopBattleBridge.buildResult} builds that record on <em>both</em> roles, so one
 * hook on the existing battle-result sink covers host and guest battles alike.
 */
public class CoopSessionStats {

    /** Key under which the wiring wave stores this in {@code sector.getPersistentData()}. */
    public static final String PERSISTENT_KEY = "coop.sessionStats";

    /** Ship losses kept in the ledger; older entries fall off the front. */
    public static final int LEDGER_LIMIT = 20;

    /**
     * Hardest bound on the number of player columns (net-fix-4, defence in depth).
     *
     * <p>Columns are minted on demand by {@link #player(String)} from an id that arrives over the
     * wire, and the lookup behind them is a linear scan of an unbounded list. The pump's own gates
     * are what should keep a stranger's id from ever reaching here; this is what stops the damage at
     * a fixed size if one ever does. Eight is four times the co-op capacity this build ships.
     */
    public static final int MAX_PLAYER_COLUMNS = 8;

    private ArrayList<String> playerOrder;
    private ArrayList<String> playerNames;
    private ArrayList<PlayerStats> playerStats;

    private long fleetsDestroyedTeam;
    private long salvageEventsTeam;
    private long coloniesHeldTeam;
    private float timeFlownTogetherSeconds;
    private float daysElapsed;
    /**
     * Nullable on purpose rather than a negative sentinel: XStream runs no field initialisers, so a
     * primitive here would come back as 0 from a save written before the field existed and read as
     * "a hull was lost on day zero". A boxed field comes back null, which is the truth.
     */
    private Float lastHullLossDay;

    private ArrayList<ShipLoss> shipLosses;

    public CoopSessionStats() {
    }

    // ---- roster ----------------------------------------------------------------------------------

    /**
     * Registers a player column, or renames one that already exists. Call order <em>is</em> the
     * column order, so the wiring wave must announce the host first and each guest as it is admitted.
     * Renaming never reorders: a player who changes their display name keeps their column.
     */
    public void notePlayer(String playerId, String name) {
        String id = normalize(playerId);
        if (id.isEmpty()) {
            return;
        }
        int index = playerOrder().indexOf(id);
        if (index < 0) {
            if (playerOrder().size() >= MAX_PLAYER_COLUMNS) {
                coop.util.CoopLog.warn(CoopSessionStats.class, "Coop refusing a "
                        + (MAX_PLAYER_COLUMNS + 1) + "th session-stats player column for playerId="
                        + id + " (cap " + MAX_PLAYER_COLUMNS + "); something is announcing players"
                        + " that are not in this session");
                return;
            }
            playerOrder().add(id);
            playerNames().add(normalize(name));
            playerStats().add(new PlayerStats(id));
            return;
        }
        String newName = normalize(name);
        if (!newName.isEmpty()) {
            playerNames().set(index, newName);
        }
    }

    /**
     * Player ids in fixed display order: host first, then join order. Never null.
     *
     * @return an unmodifiable copy; the live column order is internal, so nothing outside this class
     * can insert or reorder a player without going through {@link #notePlayer}
     */
    public List<String> playerIds() {
        return List.copyOf(playerOrder());
    }

    /** The display name for {@code playerId}, falling back to the id when none was announced. */
    public String playerName(String playerId) {
        int index = playerOrder().indexOf(normalize(playerId));
        if (index < 0) {
            return normalize(playerId);
        }
        String name = playerNames().get(index);
        return name.isEmpty() ? normalize(playerId) : name;
    }

    /**
     * The counters for {@code playerId}, creating the column if it is new. Returns a live object:
     * the hooks below all go through it, and so does the codec.
     */
    public PlayerStats player(String playerId) {
        String id = normalize(playerId);
        int index = playerOrder().indexOf(id);
        if (index >= 0) {
            return playerStats().get(index);
        }
        notePlayer(id, "");
        int created = playerOrder().indexOf(id);
        if (created < 0) {
            // The column cap refused it (or the id was blank). Loudly, not with a throwaway object
            // whose counters go nowhere: every caller here is inside a guarded handler, and a
            // silently discarded tally is exactly the kind of bug this project does not want.
            throw new IllegalStateException("no session-stats column for playerId=" + id
                    + " (cap " + MAX_PLAYER_COLUMNS + ", blank ids are refused)");
        }
        return playerStats().get(created);
    }

    /** True when nothing has been recorded at all — the "no session statistics yet" case. */
    public boolean isEmpty() {
        return playerOrder().isEmpty()
                && fleetsDestroyedTeam == 0L
                && salvageEventsTeam == 0L
                && coloniesHeldTeam == 0L
                && timeFlownTogetherSeconds <= 0f
                && daysElapsed <= 0f
                && lastHullLossDay == null
                && shipLosses().isEmpty();
    }

    // ---- tally hooks -----------------------------------------------------------------------------

    /** One finished engagement, credited to the player whose fleet entered it. */
    public void noteBattle(String playerId, boolean won) {
        PlayerStats stats = player(playerId);
        stats.battlesFought++;
        if (won) {
            stats.battlesWon++;
        }
    }

    /** Campaign-map movement in sector units, measured host-side from each fleet's own position. */
    public void noteDistance(String playerId, float su) {
        if (su <= 0f || Float.isNaN(su) || Float.isInfinite(su)) {
            return;
        }
        player(playerId).distanceTraveledSu += su;
    }

    /** Gauge, not a counter: the player's net worth as most recently observed. */
    public void noteNetWorth(String playerId, long credits) {
        player(playerId).netWorthCredits = credits;
    }

    /**
     * One market transaction. {@code marketId} joins the player's traded-with set (inherently
     * idempotent); {@code netCredits} updates the best-single-trade record on its absolute value, so
     * a spectacular purchase counts the same as a spectacular sale.
     */
    public void noteTrade(String playerId, String marketId, long netCredits) {
        PlayerStats stats = player(playerId);
        String market = normalize(marketId);
        if (!market.isEmpty() && !stats.marketsTradedWith().contains(market)) {
            stats.marketsTradedWith().add(market);
        }
        long magnitude = Math.abs(netCredits);
        if (magnitude > stats.bestSingleTradeCredits) {
            stats.bestSingleTradeCredits = magnitude;
        }
    }

    /** One mission claim the host accepted. */
    public void noteMissionClaimed(String playerId) {
        player(playerId).missionsClaimed++;
    }

    /** Set semantics: a system already visited by this player is a no-op. */
    public void noteSystemVisited(String playerId, String systemId) {
        String system = normalize(systemId);
        if (system.isEmpty()) {
            return;
        }
        PlayerStats stats = player(playerId);
        if (!stats.systemsVisited().contains(system)) {
            stats.systemsVisited().add(system);
        }
    }

    /**
     * Seconds of running clock during which both fleets shared a system or saw each other.
     * Team-owned. Wall seconds, not game seconds: the caller samples on a wall-clock cadence and
     * skips a paused sector, so this is "time the two of you spent flying in the same place", which
     * is what the card claims and what a shared save can add up across sessions.
     */
    public void noteTogether(float seconds) {
        if (seconds <= 0f || Float.isNaN(seconds) || Float.isInfinite(seconds)) {
            return;
        }
        timeFlownTogetherSeconds += seconds;
    }

    /** One consumed salvageable entity, from the world-delta ledger. Team-owned. */
    public void noteSalvage() {
        salvageEventsTeam++;
    }

    /** One colony founded, credited to the founder; the team total is the sum of the columns. */
    public void noteColonyFounded(String playerId) {
        player(playerId).coloniesFounded++;
    }

    /** Gauge: how many colonies the shared player faction holds right now. */
    public void noteColoniesHeld(int colonies) {
        coloniesHeldTeam = Math.max(0, colonies);
    }

    /**
     * Team-owned, and the honest replacement for the plan's "ships destroyed": the count of whole
     * NPC fleets a finished battle removed. See the class doc for why per-ship data is not available.
     */
    public void noteFleetsDestroyed(int fleets) {
        if (fleets > 0) {
            fleetsDestroyedTeam += fleets;
        }
    }

    /**
     * One hull lost. Bumps the owner's loss counter, appends to the ledger (dropping the oldest entry
     * past {@link #LEDGER_LIMIT}), and moves the "days since the last hull loss" mark forward.
     *
     * <p>{@code day} is the campaign day the loss happened on, on the same scale as
     * {@link #noteDaysElapsed}; the two are compared to derive {@link #daysSinceLastHullLoss()}.
     */
    public void noteShipLost(String playerId, String hullName, String hullClass, String systemName,
                             float day, String cause) {
        PlayerStats stats = player(playerId);
        stats.shipsLost++;
        ShipLoss loss = new ShipLoss(normalize(playerId), normalize(hullName), normalize(hullClass),
                normalize(systemName), day, normalize(cause));
        shipLosses().add(loss);
        while (shipLosses().size() > LEDGER_LIMIT) {
            shipLosses().remove(0);
        }
        if (lastHullLossDay == null || day > lastHullLossDay) {
            lastHullLossDay = day;
        }
    }

    /**
     * Gauge: campaign days since the session started. Monotonic — a smaller value is ignored, because
     * the only ways to produce one are a clock reconciler correction and a load of an older save, and
     * neither should make the page count backwards.
     */
    public void noteDaysElapsed(float days) {
        if (days > daysElapsed) {
            daysElapsed = days;
        }
    }

    // ---- team readings ---------------------------------------------------------------------------

    public long fleetsDestroyedTeam() {
        return fleetsDestroyedTeam;
    }

    public long salvageEventsTeam() {
        return salvageEventsTeam;
    }

    public long coloniesHeldTeam() {
        return coloniesHeldTeam;
    }

    public float timeFlownTogetherSeconds() {
        return timeFlownTogetherSeconds;
    }

    public float daysElapsed() {
        return daysElapsed;
    }

    /** The campaign day of the most recent hull loss, or null when nothing has been lost. */
    public Float lastHullLossDay() {
        return lastHullLossDay;
    }

    /**
     * Days since the last hull loss, or null when no hull has been lost. Clamped at zero so a loss
     * recorded a fraction of a day ahead of the last {@link #noteDaysElapsed} does not read negative.
     */
    public Float daysSinceLastHullLoss() {
        if (lastHullLossDay == null) {
            return null;
        }
        return Math.max(0f, daysElapsed - lastHullLossDay);
    }

    /** Newest last, capped at {@link #LEDGER_LIMIT}. Never null. */
    public List<ShipLoss> shipLossLedger() {
        return shipLosses();
    }

    /** Sum of every player's colonies-founded column. */
    public long coloniesFoundedTeam() {
        long total = 0L;
        for (PlayerStats stats : playerStats()) {
            total += stats.coloniesFounded;
        }
        return total;
    }

    /** Distinct systems any player has entered — the union, not the sum. */
    public int systemsVisitedUnionCount() {
        Set<String> union = new LinkedHashSet<>();
        for (PlayerStats stats : playerStats()) {
            union.addAll(stats.systemsVisited());
        }
        return union.size();
    }

    /** Distinct markets any player has traded with — the union, not the sum. */
    public int marketsTradedWithUnionCount() {
        Set<String> union = new LinkedHashSet<>();
        for (PlayerStats stats : playerStats()) {
            union.addAll(stats.marketsTradedWith());
        }
        return union.size();
    }

    // ---- persistence -----------------------------------------------------------------------------

    /**
     * Stores this under {@link #PERSISTENT_KEY}. Takes the map rather than the sector so it stays
     * unit-testable; the wiring wave passes {@code Global.getSector().getPersistentData()} from
     * {@code CoopModPlugin.beforeGameSave()}.
     *
     * @return true when it was written
     */
    public boolean writeInto(Map<String, Object> persistentData) {
        if (persistentData == null) {
            return false;
        }
        persistentData.put(PERSISTENT_KEY, this);
        return true;
    }

    /**
     * Reads back what {@link #writeInto} stored, or null when the save simply has nothing under
     * {@link #PERSISTENT_KEY}.
     *
     * <p>This is not a tolerant load: XStream deserialises the whole persistent-data map before this
     * method ever runs, so a save written by a build whose class shape does not map onto this one
     * throws during that deserialisation, not here. The real constraint this class has to honour
     * across builds is upstream of this method — fields may be <em>added</em>, but never removed or
     * renamed, or an older save's XML stops mapping onto this class entirely.
     */
    public static CoopSessionStats readFrom(Map<String, Object> persistentData) {
        if (persistentData == null) {
            return null;
        }
        Object stored = persistentData.get(PERSISTENT_KEY);
        return stored instanceof CoopSessionStats stats ? stats : null;
    }

    // ---- nested state ----------------------------------------------------------------------------

    /**
     * One player's column. Public fields with public accessors: the fields are what XStream writes,
     * the accessors are what the view and the codec read, and a bean with getters and setters for
     * nine counters would be three times the size for no protection XStream respects anyway.
     */
    public static class PlayerStats {
        private String playerId;
        private long battlesFought;
        private long battlesWon;
        private long shipsLost;
        private float distanceTraveledSu;
        private long netWorthCredits;
        private long bestSingleTradeCredits;
        private long missionsClaimed;
        private long coloniesFounded;
        private ArrayList<String> marketsTradedWith;
        private ArrayList<String> systemsVisited;

        public PlayerStats() {
        }

        PlayerStats(String playerId) {
            this.playerId = playerId;
        }

        public String playerId() {
            return normalize(playerId);
        }

        public long battlesFought() {
            return battlesFought;
        }

        public long battlesWon() {
            return battlesWon;
        }

        public long shipsLost() {
            return shipsLost;
        }

        public float distanceTraveledSu() {
            return distanceTraveledSu;
        }

        public long netWorthCredits() {
            return netWorthCredits;
        }

        public long bestSingleTradeCredits() {
            return bestSingleTradeCredits;
        }

        public long missionsClaimed() {
            return missionsClaimed;
        }

        public long coloniesFounded() {
            return coloniesFounded;
        }

        public List<String> marketsTradedWith() {
            if (marketsTradedWith == null) {
                marketsTradedWith = new ArrayList<>();
            }
            return marketsTradedWith;
        }

        public List<String> systemsVisited() {
            if (systemsVisited == null) {
                systemsVisited = new ArrayList<>();
            }
            return systemsVisited;
        }

        // Codec-facing setters. Package-private on purpose: nothing outside coop.stats has any
        // business writing a counter without going through a note* hook.
        void restore(long battlesFought, long battlesWon, long shipsLost, float distanceTraveledSu,
                     long netWorthCredits, long bestSingleTradeCredits, long missionsClaimed,
                     long coloniesFounded) {
            this.battlesFought = battlesFought;
            this.battlesWon = battlesWon;
            this.shipsLost = shipsLost;
            this.distanceTraveledSu = distanceTraveledSu;
            this.netWorthCredits = netWorthCredits;
            this.bestSingleTradeCredits = bestSingleTradeCredits;
            this.missionsClaimed = missionsClaimed;
            this.coloniesFounded = coloniesFounded;
        }
    }

    /** One entry of the ship-loss ledger: a hull, where and when it died, and whose it was. */
    public static class ShipLoss {
        private String playerId;
        private String hullName;
        private String hullClass;
        private String systemName;
        private float day;
        private String cause;

        public ShipLoss() {
        }

        ShipLoss(String playerId, String hullName, String hullClass, String systemName, float day,
                 String cause) {
            this.playerId = playerId;
            this.hullName = hullName;
            this.hullClass = hullClass;
            this.systemName = systemName;
            this.day = day;
            this.cause = cause;
        }

        public String playerId() {
            return normalize(playerId);
        }

        public String hullName() {
            return normalize(hullName);
        }

        public String hullClass() {
            return normalize(hullClass);
        }

        public String systemName() {
            return normalize(systemName);
        }

        public float day() {
            return day;
        }

        public String cause() {
            return normalize(cause);
        }
    }

    // ---- lazy collections ------------------------------------------------------------------------

    private ArrayList<String> playerOrder() {
        if (playerOrder == null) {
            playerOrder = new ArrayList<>();
        }
        return playerOrder;
    }

    private ArrayList<String> playerNames() {
        if (playerNames == null) {
            playerNames = new ArrayList<>();
        }
        return playerNames;
    }

    private ArrayList<PlayerStats> playerStats() {
        if (playerStats == null) {
            playerStats = new ArrayList<>();
        }
        return playerStats;
    }

    private ArrayList<ShipLoss> shipLosses() {
        if (shipLosses == null) {
            shipLosses = new ArrayList<>();
        }
        return shipLosses;
    }

    /** Codec seam: restore the team-level gauges in one call. */
    void restoreTeam(long fleetsDestroyed, long salvageEvents, long coloniesHeld,
                     float timeFlownTogetherSeconds, float daysElapsed, float lastHullLossDay) {
        this.fleetsDestroyedTeam = fleetsDestroyed;
        this.salvageEventsTeam = salvageEvents;
        this.coloniesHeldTeam = coloniesHeld;
        this.timeFlownTogetherSeconds = timeFlownTogetherSeconds;
        this.daysElapsed = daysElapsed;
        this.lastHullLossDay = lastHullLossDay < 0f ? null : lastHullLossDay;
    }

    /** Codec seam: append a ledger entry verbatim, without touching any counter. */
    void restoreShipLoss(ShipLoss loss) {
        if (loss == null) {
            return;
        }
        shipLosses().add(loss);
        while (shipLosses().size() > LEDGER_LIMIT) {
            shipLosses().remove(0);
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value;
    }
}
