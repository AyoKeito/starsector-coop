package coop.ui;

import coop.stats.CoopSessionStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSessionStatsViewTest {

    private static final String HOST = "host-id";
    private static final String GUEST = "guest-id";

    /**
     * The markers the plan forbids. "No per-row leader highlight" is the one presentation element the
     * co-op corpus documents a behavioural pathology for, so it is asserted against the whole page
     * rather than trusted to review.
     */
    private static final List<String> LEADER_MARKERS =
            List.of("*", "★", "▲", "●", "✓", "leader", "winner", "1st", "#1");

    // ---- empty state -----------------------------------------------------------------------------

    @Test
    void nullStatsRenderTheNoDataLine() {
        CoopSessionStatsView view = CoopSessionStatsView.of(null, null);

        assertFalse(view.hasData());
        assertEquals(List.of(CoopSessionStatsView.NO_DATA_LINE), view.headline());
        assertTrue(view.cards().isEmpty());
        assertTrue(view.sections().isEmpty());
        assertTrue(view.footer().isEmpty());
    }

    @Test
    void emptyStatsRenderTheNoDataLine() {
        assertFalse(CoopSessionStatsView.of(new CoopSessionStats(), Set.of()).hasData());
    }

    // ---- columns ---------------------------------------------------------------------------------

    @Test
    void columnsAreHostFirstThenJoinOrderThenTeam() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of());

        assertEquals(List.of("Ayo", "Partner", CoopSessionStatsView.TEAM_COLUMN),
                view.columnHeaders());
    }

    @Test
    void columnOrderIgnoresWhoIsAheadOnAnyStat() {
        // The guest leads on every stat here; the column order must not budge.
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.notePlayer(GUEST, "Partner");
        stats.noteBattle(GUEST, true);
        stats.noteDistance(GUEST, 99_999f);
        stats.noteNetWorth(GUEST, 5_000_000L);

        assertEquals(List.of("Ayo", "Partner", CoopSessionStatsView.TEAM_COLUMN),
                CoopSessionStatsView.of(stats, Set.of()).columnHeaders());
    }

    @Test
    void aDisconnectedPlayerColumnSaysAwayAndKeepsItsNumbers() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of(GUEST));

        assertEquals(List.of("Ayo", "Partner" + CoopSessionStatsView.AWAY_SUFFIX,
                CoopSessionStatsView.TEAM_COLUMN), view.columnHeaders());
        // "away", never blank: the guest's battles are still shown.
        assertEquals("3", cell(view, "Combat", "Battles fought", 1));
    }

    // ---- table -----------------------------------------------------------------------------------

    @Test
    void theFourNamedSectionsAreAlwaysPresentInOrder() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of());

        assertEquals(List.of("Combat", "Travel", "Trade", "Colonies"),
                view.sections().stream().map(CoopSessionStatsView.Section::title).toList());
    }

    @Test
    void everyRowHasOneCellPerPlayerPlusTheTeamColumn() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of());
        int expected = view.columnHeaders().size();

        for (CoopSessionStatsView.Section section : view.sections()) {
            for (CoopSessionStatsView.Row row : section.rows()) {
                assertEquals(expected, row.cells().size(), row.label());
            }
        }
    }

    @Test
    void teamCellsAggregateBySumMaxOrUnionAsAppropriate() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of());

        assertEquals("10", teamCell(view, "Combat", "Battles fought"));
        assertEquals("6", teamCell(view, "Combat", "Battles won"));
        // Systems visited is a union, not a sum: corvus is shared, so 2 + 2 is 3.
        assertEquals("3", teamCell(view, "Travel", "Systems visited"));
        // Best single trade is a maximum, not a sum.
        assertEquals("250,000", teamCell(view, "Trade", "Best single trade"));
        assertEquals("1,111,110", teamCell(view, "Trade", "Net worth"));
        assertEquals("3", teamCell(view, "Colonies", "Colonies founded"));
    }

    @Test
    void teamOwnedRowsShowNoPerPlayerNumber() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of());

        assertEquals(CoopSessionStatsView.NOT_APPLICABLE, cell(view, "Combat", "Fleets destroyed", 0));
        assertEquals(CoopSessionStatsView.NOT_APPLICABLE, cell(view, "Combat", "Fleets destroyed", 1));
        assertEquals("9", teamCell(view, "Combat", "Fleets destroyed"));
        assertEquals(CoopSessionStatsView.NOT_APPLICABLE, cell(view, "Travel", "Salvage recovered", 0));
        assertEquals("11", teamCell(view, "Travel", "Salvage recovered"));
        assertEquals(CoopSessionStatsView.NOT_APPLICABLE, cell(view, "Colonies", "Colonies held", 0));
        assertEquals("4", teamCell(view, "Colonies", "Colonies held"));
    }

    // ---- headline --------------------------------------------------------------------------------

    @Test
    void theHeadlineIsTeamFirst() {
        List<String> headline = CoopSessionStatsView.of(rich(), Set.of()).headline();

        assertEquals(4, headline.size());
        assertTrue(headline.get(0).startsWith("Days elapsed: 64.3"), headline.get(0));
        assertEquals("Time flown together: 1h 12m", headline.get(1));
        assertEquals("Battles: 10 fought, 6 won", headline.get(2));
        assertEquals("Days since the last hull loss: 4.3", headline.get(3));
    }

    @Test
    void theHeadlineSaysSoWhenNothingHasBeenLost() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.noteDaysElapsed(3f);

        assertEquals("No hulls lost this session.",
                CoopSessionStatsView.of(stats, Set.of()).headline().get(3));
    }

    // ---- record cards ----------------------------------------------------------------------------

    @Test
    void allFiveCardsAppearOnceEverythingClearsItsFloorAndOneIsTeamOwned() {
        List<CoopSessionStatsView.Card> cards = CoopSessionStatsView.of(rich(), Set.of()).cards();

        assertEquals(List.of("Longest haul", "Best deal", "Explorer", "Veteran", "Together"),
                cards.stream().map(CoopSessionStatsView.Card::title).toList());
        assertEquals(1, cards.stream().filter(CoopSessionStatsView.Card::team).count());
        assertTrue(cards.stream().anyMatch(card -> card.team()
                && CoopSessionStatsView.TEAM_COLUMN.equals(card.holders())));
    }

    @Test
    void everyCardPrintsItsOwnCriterion() {
        for (CoopSessionStatsView.Card card : CoopSessionStatsView.of(rich(), Set.of()).cards()) {
            assertFalse(card.criterion().isBlank(), card.title());
            assertTrue(card.line().contains(card.criterion()), card.title());
        }
    }

    @Test
    void cardsBelowTheirFloorAreSuppressed() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.noteDistance(HOST, CoopSessionStatsView.DISTANCE_FLOOR_SU - 1f);
        stats.noteTrade(HOST, "jangala_market", CoopSessionStatsView.TRADE_FLOOR_CREDITS - 1L);
        stats.noteSystemVisited(HOST, "corvus");
        stats.noteSystemVisited(HOST, "askonia");
        stats.noteBattle(HOST, true);
        stats.noteBattle(HOST, true);
        stats.noteTogether(CoopSessionStatsView.TOGETHER_FLOOR_SECONDS - 1f);

        assertTrue(CoopSessionStatsView.of(stats, Set.of()).cards().isEmpty());
    }

    @Test
    void aCardAppearsExactlyAtItsFloor() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.noteDistance(HOST, CoopSessionStatsView.DISTANCE_FLOOR_SU);

        List<CoopSessionStatsView.Card> cards = CoopSessionStatsView.of(stats, Set.of()).cards();

        assertEquals(1, cards.size());
        assertEquals("Longest haul", cards.get(0).title());
        assertEquals("1,000 su", cards.get(0).value());
    }

    @Test
    void aTiePrintsBothNames() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.notePlayer(GUEST, "Partner");
        stats.noteDistance(HOST, 5_000f);
        stats.noteDistance(GUEST, 5_000f);

        CoopSessionStatsView.Card card = CoopSessionStatsView.of(stats, Set.of()).cards().get(0);

        assertEquals("Ayo" + CoopSessionStatsView.TIE_JOIN + "Partner", card.holders());
    }

    @Test
    void aClearWinnerIsNamedAlone() {
        CoopSessionStatsView.Card card = CoopSessionStatsView.of(rich(), Set.of()).cards().get(0);

        assertEquals("Ayo", card.holders());
        assertEquals("12,346 su", card.value());
    }

    // ---- ledger and footer -----------------------------------------------------------------------

    @Test
    void theLedgerReadsNewestFirstAndNamesTheHullSystemCauseAndOwner() {
        List<String> ledger = CoopSessionStatsView.of(rich(), Set.of()).ledger();

        assertEquals(2, ledger.size());
        assertEquals("Day 60.0 - Eagle (cruiser) lost in Hybrasil - destroyed - Partner",
                ledger.get(0));
        assertEquals("Day 12.0 - Wolf (frigate) lost in Corvus - destroyed - Ayo", ledger.get(1));
    }

    @Test
    void anEmptyLedgerStillSaysSomething() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.noteSalvage();

        assertEquals(List.of("No hulls lost this session."),
                CoopSessionStatsView.of(stats, Set.of()).ledger());
    }

    @Test
    void theFooterStatesAnAttributionRuleForEveryStatGroup() {
        List<String> footer = CoopSessionStatsView.of(rich(), Set.of()).footer();

        assertEquals(12, footer.size());
        for (String subject : List.of("Battles:", "Fleets destroyed:", "Ships lost:", "Distance:",
                "Systems visited:", "Salvage recovered:", "Net worth:", "Best single trade:",
                "Markets traded with:", "Missions claimed:", "Colonies:",
                "Time flown together:")) {
            assertTrue(footer.stream().anyMatch(line -> line.startsWith(subject)), subject);
        }
        for (String line : footer) {
            assertTrue(line.endsWith("."), line);
        }
    }

    // ---- the rule that matters -------------------------------------------------------------------

    @Test
    void noLeaderMarkerAppearsAnywhereOnThePage() {
        // A page where the host beats the guest on every single stat is the case a leader highlight
        // would show up in.
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of(GUEST));

        for (String line : view.allLines()) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            for (String marker : LEADER_MARKERS) {
                assertFalse(lower.contains(marker.toLowerCase(java.util.Locale.ROOT)),
                        "leader marker '" + marker + "' in: " + line);
            }
        }
    }

    @Test
    void everyTableCellIsThePlainValueWithNoDecoration() {
        CoopSessionStatsView view = CoopSessionStatsView.of(rich(), Set.of());

        for (CoopSessionStatsView.Section section : view.sections()) {
            for (CoopSessionStatsView.Row row : section.rows()) {
                for (String cell : row.cells()) {
                    assertEquals(cell.trim(), cell, row.label());
                    assertFalse(cell.isEmpty(), row.label());
                }
            }
        }
    }

    // ---- formatting ------------------------------------------------------------------------------

    @Test
    void durationsUseAtMostTwoUnits() {
        assertEquals("45s", CoopSessionStatsView.formatDuration(45f));
        assertEquals("12m 30s", CoopSessionStatsView.formatDuration(750f));
        assertEquals("1h 12m", CoopSessionStatsView.formatDuration(4_321.5f));
        assertEquals("0s", CoopSessionStatsView.formatDuration(-5f));
    }

    @Test
    void numbersAreGroupedAndLocaleIndependent() {
        assertEquals("1,234,567", CoopSessionStatsView.formatCredits(1_234_567L));
        assertEquals("12,346 su", CoopSessionStatsView.formatDistance(12_345.5f));
        assertEquals("0 su", CoopSessionStatsView.formatDistance(-3f));
        assertEquals("64.3", CoopSessionStatsView.formatDays(64.25f));
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static CoopSessionStats rich() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.notePlayer(HOST, "Ayo");
        stats.notePlayer(GUEST, "Partner");

        for (int i = 0; i < 7; i++) {
            stats.noteBattle(HOST, i < 5);
        }
        for (int i = 0; i < 3; i++) {
            stats.noteBattle(GUEST, i < 1);
        }
        stats.noteDistance(HOST, 12_345.5f);
        stats.noteDistance(GUEST, 2_000f);
        stats.noteNetWorth(HOST, 987_654L);
        stats.noteNetWorth(GUEST, 123_456L);
        stats.noteTrade(HOST, "jangala_market", 250_000L);
        stats.noteTrade(HOST, "kazeron_market", 1_000L);
        stats.noteTrade(GUEST, "jangala_market", 9_000L);
        for (int i = 0; i < 4; i++) {
            stats.noteMissionClaimed(HOST);
        }
        stats.noteMissionClaimed(GUEST);
        stats.noteSystemVisited(HOST, "corvus");
        stats.noteSystemVisited(HOST, "askonia");
        stats.noteSystemVisited(HOST, "hybrasil");
        stats.noteSystemVisited(GUEST, "corvus");
        stats.noteSystemVisited(GUEST, "hybrasil");
        stats.noteColonyFounded(HOST);
        stats.noteColonyFounded(HOST);
        stats.noteColonyFounded(GUEST);
        stats.noteColoniesHeld(4);
        stats.noteFleetsDestroyed(9);
        for (int i = 0; i < 11; i++) {
            stats.noteSalvage();
        }
        stats.noteTogether(4_321.5f);
        stats.noteDaysElapsed(64.25f);
        stats.noteShipLost(HOST, "Wolf", "frigate", "Corvus", 12f, "destroyed");
        stats.noteShipLost(GUEST, "Eagle", "cruiser", "Hybrasil", 60f, "destroyed");
        return stats;
    }

    private static String cell(CoopSessionStatsView view, String section, String label, int column) {
        return row(view, section, label).cells().get(column);
    }

    private static String teamCell(CoopSessionStatsView view, String section, String label) {
        List<String> cells = row(view, section, label).cells();
        return cells.get(cells.size() - 1);
    }

    private static CoopSessionStatsView.Row row(CoopSessionStatsView view, String section,
                                                String label) {
        return view.sections().stream()
                .filter(s -> s.title().equals(section))
                .flatMap(s -> s.rows().stream())
                .filter(r -> r.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + section + "/" + label));
    }
}
