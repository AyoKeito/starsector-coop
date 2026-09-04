package coop.ui;

import coop.stats.CoopSessionStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything the "Coop Stats" page decides, with no engine in sight: which columns exist and in what
 * order, which record cards survive their floor, what each table cell says, and what the footer
 * claims about attribution. {@link CoopSessionStatsIntel} does nothing but hand these strings to
 * widgets.
 *
 * <p>The split exists because the presentation rules in the plan are the part worth testing and the
 * part that is hardest to check by eye — fixed column order, "away" instead of a blank column, ties
 * printing both names, a floor under every record card, and above all <b>no per-row leader
 * highlight</b>. That last one is the single presentation element the co-op corpus documents an
 * actual behavioural pathology for (Darktide shipped without a scoreboard citing exactly it), so it
 * is enforced here by there being no code path that can emit a marker, and asserted in the tests.
 *
 * <p>Nothing here reads the campaign clock, the sector, or the network. Give it a
 * {@link CoopSessionStats} and a set of away player ids and it is fully determined.
 */
final class CoopSessionStatsView {

    /** Rendered in place of the whole page before the host has tallied anything. */
    static final String NO_DATA_LINE = "No session statistics yet.";

    /** Suffix on the column header of a player who is not currently connected. */
    static final String AWAY_SUFFIX = " (away)";

    /** Header of the rightmost column. */
    static final String TEAM_COLUMN = "Team";

    /** Cell content for a stat that has no per-player meaning (team-owned rows). */
    static final String NOT_APPLICABLE = "-";

    /** Separator between the names on a card that came out a tie. */
    static final String TIE_JOIN = " and ";

    // ---- record-card floors ----------------------------------------------------------------------
    // A card below its floor is not shown at all. Session minute three has nothing to celebrate, and
    // "Longest haul: 12 su" reads as a joke about the feature rather than a fact about the session.

    static final float DISTANCE_FLOOR_SU = 1000f;
    static final long TRADE_FLOOR_CREDITS = 10000L;
    static final int SYSTEMS_FLOOR = 3;
    static final long BATTLES_FLOOR = 3L;
    static final float TOGETHER_FLOOR_SECONDS = 60f;

    private final boolean hasData;
    private final List<String> headline;
    private final List<Card> cards;
    private final List<String> columnHeaders;
    private final List<Section> sections;
    private final List<String> ledger;
    private final List<String> footer;

    private CoopSessionStatsView(boolean hasData, List<String> headline, List<Card> cards,
                                 List<String> columnHeaders, List<Section> sections,
                                 List<String> ledger, List<String> footer) {
        this.hasData = hasData;
        this.headline = Collections.unmodifiableList(headline);
        this.cards = Collections.unmodifiableList(cards);
        this.columnHeaders = Collections.unmodifiableList(columnHeaders);
        this.sections = Collections.unmodifiableList(sections);
        this.ledger = Collections.unmodifiableList(ledger);
        this.footer = Collections.unmodifiableList(footer);
    }

    /** One record card: its own criterion, who holds it, and the number that earned it. */
    record Card(String title, String criterion, String holders, String value, boolean team) {
        String line() {
            return title + " - " + criterion + ": " + holders + ", " + value;
        }
    }

    /** One named block of the per-player table. */
    record Section(String title, List<Row> rows) {
    }

    /** One metric. {@code cells} is one entry per player column, then the team column. */
    record Row(String label, List<String> cells) {
    }

    // ---- construction ----------------------------------------------------------------------------

    static CoopSessionStatsView of(CoopSessionStats stats, Set<String> awayPlayerIds) {
        if (stats == null || stats.isEmpty()) {
            return new CoopSessionStatsView(false, List.of(NO_DATA_LINE), List.of(), List.of(),
                    List.of(), List.of(), List.of());
        }
        Set<String> away = awayPlayerIds == null ? Set.of() : awayPlayerIds;
        List<String> players = stats.playerIds();
        return new CoopSessionStatsView(true,
                buildHeadline(stats),
                buildCards(stats),
                buildHeaders(stats, players, away),
                buildSections(stats, players),
                buildLedger(stats),
                buildFooter());
    }

    // ---- readings --------------------------------------------------------------------------------

    boolean hasData() {
        return hasData;
    }

    /** The team-first band at the top of the page. */
    List<String> headline() {
        return headline;
    }

    /** The record cards that cleared their floor, in fixed order. */
    List<Card> cards() {
        return cards;
    }

    /** Player names in fixed order (host first, then join order), then {@link #TEAM_COLUMN}. */
    List<String> columnHeaders() {
        return columnHeaders;
    }

    List<Section> sections() {
        return sections;
    }

    /** The ship-loss ledger as a story list, newest first. */
    List<String> ledger() {
        return ledger;
    }

    /** One line per stat saying how it is attributed. */
    List<String> footer() {
        return footer;
    }

    /**
     * Every line the page will print, flattened. Exists for the tests that have to assert something
     * about the <em>whole</em> page — chiefly that no leader marker appears anywhere in it.
     */
    List<String> allLines() {
        List<String> lines = new ArrayList<>(headline);
        for (Card card : cards) {
            lines.add(card.line());
        }
        lines.add(String.join(" | ", columnHeaders));
        for (Section section : sections) {
            lines.add(section.title());
            for (Row row : section.rows()) {
                lines.add(row.label() + " | " + String.join(" | ", row.cells()));
            }
        }
        lines.addAll(ledger);
        lines.addAll(footer);
        return lines;
    }

    // ---- headline --------------------------------------------------------------------------------

    private static List<String> buildHeadline(CoopSessionStats stats) {
        List<String> lines = new ArrayList<>(4);
        lines.add("Days elapsed: " + formatDays(stats.daysElapsed()));
        lines.add("Time flown together: " + (stats.timeFlownTogetherSeconds() <= 0f
                ? "not yet" : formatDuration(stats.timeFlownTogetherSeconds())));
        long fought = 0L;
        long won = 0L;
        for (String playerId : stats.playerIds()) {
            fought += stats.player(playerId).battlesFought();
            won += stats.player(playerId).battlesWon();
        }
        lines.add("Battles: " + fought + " fought, " + won + " won");
        // "No hulls lost" already has a home in the ledger below; saying it twice on one page reads as
        // a bug, not emphasis. The headline only earns a line here once there is a day count to show.
        Float sinceLoss = stats.daysSinceLastHullLoss();
        if (sinceLoss != null) {
            lines.add("Days since the last hull loss: " + formatDays(sinceLoss));
        }
        return lines;
    }

    // ---- record cards ----------------------------------------------------------------------------

    private static List<Card> buildCards(CoopSessionStats stats) {
        List<Card> built = new ArrayList<>(5);
        addCard(built, stats, "Longest haul", "most distance traveled",
                id -> stats.player(id).distanceTraveledSu(), DISTANCE_FLOOR_SU,
                CoopSessionStatsView::formatDistance);
        addCard(built, stats, "Best deal", "largest single trade",
                id -> stats.player(id).bestSingleTradeCredits(), TRADE_FLOOR_CREDITS,
                value -> formatCredits((long) value) + " credits");
        addCard(built, stats, "Explorer", "most systems visited",
                id -> stats.player(id).systemsVisited().size(), SYSTEMS_FLOOR,
                value -> ((long) value) + " systems");
        addCard(built, stats, "Veteran", "most battles fought",
                id -> stats.player(id).battlesFought(), BATTLES_FLOOR,
                value -> ((long) value) + " battles");
        // The one team-owned card, and the page's signature stat: there is no single-player number
        // to compare it to, which is exactly why it earns its place among the per-player records.
        if (stats.timeFlownTogetherSeconds() >= TOGETHER_FLOOR_SECONDS) {
            built.add(new Card("Together", "time flown together", TEAM_COLUMN,
                    formatDuration(stats.timeFlownTogetherSeconds()), true));
        }
        return built;
    }

    /** Reads one player's number for a card. Named rather than {@code Function} for readability. */
    private interface Metric {
        float valueOf(String playerId);
    }

    /** Turns a card's winning number into its printed form. */
    private interface Formatter {
        String format(float value);
    }

    private static void addCard(List<Card> into, CoopSessionStats stats, String title,
                                String criterion, Metric metric, float floor, Formatter formatter) {
        float best = 0f;
        List<String> holders = new ArrayList<>(2);
        for (String playerId : stats.playerIds()) {
            float value = metric.valueOf(playerId);
            if (value > best) {
                best = value;
                holders.clear();
                holders.add(stats.playerName(playerId));
            } else if (value == best && value > 0f) {
                holders.add(stats.playerName(playerId));
            }
        }
        if (best < floor || holders.isEmpty()) {
            return;
        }
        into.add(new Card(title, criterion, String.join(TIE_JOIN, holders), formatter.format(best),
                false));
    }

    // ---- table -----------------------------------------------------------------------------------

    private static List<String> buildHeaders(CoopSessionStats stats, List<String> players,
                                             Set<String> away) {
        List<String> headers = new ArrayList<>(players.size() + 1);
        for (String playerId : players) {
            // "away", never blank: an empty column reads as "this player did nothing", which is the
            // opposite of what a dropped link means.
            headers.add(stats.playerName(playerId) + (away.contains(playerId) ? AWAY_SUFFIX : ""));
        }
        headers.add(TEAM_COLUMN);
        return headers;
    }

    private static List<Section> buildSections(CoopSessionStats stats, List<String> players) {
        List<Section> sections = new ArrayList<>(4);

        List<Row> combat = new ArrayList<>(4);
        combat.add(row(players, "Battles fought",
                id -> Long.toString(stats.player(id).battlesFought()),
                Long.toString(sumLong(players, id -> stats.player(id).battlesFought()))));
        combat.add(row(players, "Battles won",
                id -> Long.toString(stats.player(id).battlesWon()),
                Long.toString(sumLong(players, id -> stats.player(id).battlesWon()))));
        combat.add(row(players, "Ships lost",
                id -> Long.toString(stats.player(id).shipsLost()),
                Long.toString(sumLong(players, id -> stats.player(id).shipsLost()))));
        combat.add(teamOnlyRow(players, "Fleets destroyed",
                Long.toString(stats.fleetsDestroyedTeam())));
        sections.add(new Section("Combat", combat));

        List<Row> travel = new ArrayList<>(3);
        travel.add(row(players, "Distance traveled",
                id -> formatDistance(stats.player(id).distanceTraveledSu()),
                formatDistance(sumFloat(players, id -> stats.player(id).distanceTraveledSu()))));
        travel.add(row(players, "Systems visited",
                id -> Integer.toString(stats.player(id).systemsVisited().size()),
                Integer.toString(stats.systemsVisitedUnionCount())));
        travel.add(teamOnlyRow(players, "Salvage recovered",
                Long.toString(stats.salvageEventsTeam())));
        sections.add(new Section("Travel", travel));

        List<Row> trade = new ArrayList<>(4);
        trade.add(row(players, "Net worth",
                id -> formatCredits(stats.player(id).netWorthCredits()),
                formatCredits(sumLong(players, id -> stats.player(id).netWorthCredits()))));
        trade.add(row(players, "Best single trade",
                id -> formatCredits(stats.player(id).bestSingleTradeCredits()),
                formatCredits(maxLong(players,
                        id -> stats.player(id).bestSingleTradeCredits()))));
        trade.add(row(players, "Markets traded with",
                id -> Integer.toString(stats.player(id).marketsTradedWith().size()),
                Integer.toString(stats.marketsTradedWithUnionCount())));
        trade.add(row(players, "Missions claimed",
                id -> Long.toString(stats.player(id).missionsClaimed()),
                Long.toString(sumLong(players, id -> stats.player(id).missionsClaimed()))));
        sections.add(new Section("Trade", trade));

        List<Row> colonies = new ArrayList<>(2);
        colonies.add(row(players, "Colonies founded",
                id -> Long.toString(stats.player(id).coloniesFounded()),
                Long.toString(stats.coloniesFoundedTeam())));
        colonies.add(teamOnlyRow(players, "Colonies held",
                Long.toString(stats.coloniesHeldTeam())));
        sections.add(new Section("Colonies", colonies));

        return sections;
    }

    /** Reads one player's cell text. */
    private interface Cell {
        String of(String playerId);
    }

    /** Reads one player's number for a team aggregate. */
    private interface LongMetric {
        long of(String playerId);
    }

    private static Row row(List<String> players, String label, Cell cell, String teamCell) {
        List<String> cells = new ArrayList<>(players.size() + 1);
        for (String playerId : players) {
            cells.add(cell.of(playerId));
        }
        cells.add(teamCell);
        return new Row(label, cells);
    }

    private static Row teamOnlyRow(List<String> players, String label, String teamCell) {
        return row(players, label, id -> NOT_APPLICABLE, teamCell);
    }

    private static long sumLong(List<String> players, LongMetric metric) {
        long total = 0L;
        for (String playerId : players) {
            total += metric.of(playerId);
        }
        return total;
    }

    private static long maxLong(List<String> players, LongMetric metric) {
        long best = 0L;
        for (String playerId : players) {
            best = Math.max(best, metric.of(playerId));
        }
        return best;
    }

    private static float sumFloat(List<String> players, Metric metric) {
        float total = 0f;
        for (String playerId : players) {
            total += metric.valueOf(playerId);
        }
        return total;
    }

    // ---- ledger ----------------------------------------------------------------------------------

    private static List<String> buildLedger(CoopSessionStats stats) {
        List<CoopSessionStats.ShipLoss> losses = stats.shipLossLedger();
        if (losses.isEmpty()) {
            return List.of("No hulls lost this session.");
        }
        List<String> lines = new ArrayList<>(losses.size());
        for (int i = losses.size() - 1; i >= 0; i--) {
            CoopSessionStats.ShipLoss loss = losses.get(i);
            StringBuilder line = new StringBuilder(64);
            line.append("Day ").append(formatDays(loss.day())).append(" - ");
            line.append(loss.hullName().isEmpty() ? "an unnamed hull" : loss.hullName());
            if (!loss.hullClass().isEmpty()) {
                line.append(" (").append(loss.hullClass()).append(')');
            }
            if (!loss.systemName().isEmpty()) {
                line.append(" lost in ").append(loss.systemName());
            } else {
                line.append(" lost");
            }
            if (!loss.cause().isEmpty()) {
                line.append(" - ").append(loss.cause());
            }
            String owner = stats.playerName(loss.playerId());
            if (!owner.isEmpty()) {
                line.append(" - ").append(owner);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // ---- footer ----------------------------------------------------------------------------------

    /**
     * Every documented stat argument in the co-op corpus traces to ambiguous attribution rather than
     * to the stat existing, so the rules are printed rather than left to be inferred.
     */
    private static List<String> buildFooter() {
        return List.of(
                "Battles: credited to the player whose fleet entered the engagement.",
                "Fleets destroyed: team-wide, counted from the battle report of whoever fought it.",
                "Ships lost: credited to the fleet the hull was serving in.",
                "Distance: measured on the host from each fleet's own position, sampled once a second.",
                "Systems visited: credited to each player who entered; the team figure is the union.",
                "Salvage recovered: team-wide, one count per entity the host saw consumed.",
                "Net worth: the player's own liquid credits as last reported, not fleet value.",
                "Best single trade: the largest transaction, bought or sold.",
                "Best single trade: the guest's is not measured this release; the wire carries no price.",
                "Markets traded with: credited to the player who opened the market screen.",
                "Missions claimed: credited to the player whose claim the host accepted.",
                "Colonies: founding is credited to the founder; held is what the shared faction owns.",
                "Time flown together: team-wide, counted while both fleets are in the same location, "
                        + "hyperspace included, and only while the shared clock is running.");
    }

    // ---- formatting ------------------------------------------------------------------------------

    static String formatDays(float days) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0f, days));
    }

    static String formatCredits(long credits) {
        return String.format(Locale.ROOT, "%,d", credits);
    }

    static String formatDistance(float su) {
        return formatCredits(Math.round(Math.max(0f, su))) + " su";
    }

    /**
     * Game-seconds as something a player reads at a glance. Deliberately two units at most: "3h 12m"
     * says everything "3h 12m 07s" does and is a third the width in a table cell.
     */
    static String formatDuration(float seconds) {
        long total = Math.max(0L, Math.round((double) seconds));
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long secs = total % 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }
}
