package coop.stats;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSessionStatsTest {

    private static final String HOST = "host-id";
    private static final String GUEST = "guest-id";

    private final CoopSessionStats stats = new CoopSessionStats();

    @Test
    void freshStatsAreEmpty() {
        assertTrue(stats.isEmpty());
        assertTrue(stats.playerIds().isEmpty());
        assertTrue(stats.shipLossLedger().isEmpty());
        assertNull(stats.daysSinceLastHullLoss());
        assertNull(stats.lastHullLossDay());
    }

    @Test
    void columnOrderIsRegistrationOrderAndRenamingDoesNotReorder() {
        stats.notePlayer(HOST, "Ayo");
        stats.notePlayer(GUEST, "Partner");
        stats.notePlayer(HOST, "Ayo Keito");

        assertEquals(List.of(HOST, GUEST), stats.playerIds());
        assertEquals("Ayo Keito", stats.playerName(HOST));
        assertEquals("Partner", stats.playerName(GUEST));
    }

    @Test
    void anUnnamedPlayerFallsBackToItsId() {
        stats.noteBattle(GUEST, true);

        assertEquals(List.of(GUEST), stats.playerIds());
        assertEquals(GUEST, stats.playerName(GUEST));
    }

    @Test
    void aBlankPlayerIdIsIgnoredEntirely() {
        stats.notePlayer(null, "nobody");
        stats.notePlayer("", "nobody");

        assertTrue(stats.playerIds().isEmpty());
    }

    @Test
    void playerIdsIsACopyNotTheLiveColumnOrder() {
        stats.notePlayer(HOST, "Ayo");

        List<String> ids = stats.playerIds();
        assertThrows(UnsupportedOperationException.class, () -> ids.add(GUEST));
        assertThrows(UnsupportedOperationException.class, () -> ids.remove(HOST));

        // Registering a new player afterwards must not retroactively change a list already handed out.
        stats.notePlayer(GUEST, "Partner");
        assertEquals(List.of(HOST), ids);
        assertEquals(List.of(HOST, GUEST), stats.playerIds());
    }

    @Test
    void battlesTallyFoughtAndWonSeparately() {
        stats.noteBattle(HOST, true);
        stats.noteBattle(HOST, false);
        stats.noteBattle(HOST, true);

        assertEquals(3L, stats.player(HOST).battlesFought());
        assertEquals(2L, stats.player(HOST).battlesWon());
    }

    @Test
    void distanceAccumulatesAndIgnoresNonsense() {
        stats.noteDistance(HOST, 100f);
        stats.noteDistance(HOST, 250.5f);
        stats.noteDistance(HOST, -10f);
        stats.noteDistance(HOST, Float.NaN);
        stats.noteDistance(HOST, Float.POSITIVE_INFINITY);

        assertEquals(350.5f, stats.player(HOST).distanceTraveledSu(), 0.001f);
    }

    @Test
    void netWorthIsAGaugeNotACounter() {
        stats.noteNetWorth(HOST, 100_000L);
        stats.noteNetWorth(HOST, 80_000L);

        assertEquals(80_000L, stats.player(HOST).netWorthCredits());
    }

    @Test
    void tradesDedupeMarketsAndKeepTheLargestByMagnitude() {
        stats.noteTrade(HOST, "jangala_market", 12_000L);
        stats.noteTrade(HOST, "jangala_market", -40_000L);
        stats.noteTrade(HOST, "chicomoztoc_market", 5_000L);
        stats.noteTrade(HOST, "", 1_000L);

        assertEquals(List.of("jangala_market", "chicomoztoc_market"),
                stats.player(HOST).marketsTradedWith());
        assertEquals(40_000L, stats.player(HOST).bestSingleTradeCredits());
    }

    @Test
    void systemsVisitedIsASet() {
        stats.noteSystemVisited(HOST, "corvus");
        stats.noteSystemVisited(HOST, "corvus");
        stats.noteSystemVisited(HOST, "askonia");
        stats.noteSystemVisited(HOST, "");
        stats.noteSystemVisited(GUEST, "corvus");
        stats.noteSystemVisited(GUEST, "hybrasil");

        assertEquals(List.of("corvus", "askonia"), stats.player(HOST).systemsVisited());
        assertEquals(3, stats.systemsVisitedUnionCount());
    }

    @Test
    void marketUnionCountsEachMarketOnce() {
        stats.noteTrade(HOST, "jangala_market", 1L);
        stats.noteTrade(GUEST, "jangala_market", 1L);
        stats.noteTrade(GUEST, "kazeron_market", 1L);

        assertEquals(2, stats.marketsTradedWithUnionCount());
    }

    @Test
    void teamCountersAccumulateIndependentlyOfPlayers() {
        stats.noteSalvage();
        stats.noteSalvage();
        stats.noteFleetsDestroyed(3);
        stats.noteFleetsDestroyed(0);
        stats.noteFleetsDestroyed(-2);
        stats.noteTogether(30f);
        stats.noteTogether(45.5f);
        stats.noteTogether(-1f);
        stats.noteColoniesHeld(4);

        assertEquals(2L, stats.salvageEventsTeam());
        assertEquals(3L, stats.fleetsDestroyedTeam());
        assertEquals(75.5f, stats.timeFlownTogetherSeconds(), 0.001f);
        assertEquals(4L, stats.coloniesHeldTeam());
    }

    @Test
    void coloniesFoundedRollUpToTheTeamTotal() {
        stats.noteColonyFounded(HOST);
        stats.noteColonyFounded(HOST);
        stats.noteColonyFounded(GUEST);

        assertEquals(2L, stats.player(HOST).coloniesFounded());
        assertEquals(1L, stats.player(GUEST).coloniesFounded());
        assertEquals(3L, stats.coloniesFoundedTeam());
    }

    @Test
    void daysElapsedNeverGoesBackwards() {
        stats.noteDaysElapsed(10f);
        stats.noteDaysElapsed(4f);
        stats.noteDaysElapsed(12.5f);

        assertEquals(12.5f, stats.daysElapsed(), 0.001f);
    }

    @Test
    void shipLossesCountPerPlayerAndCapTheLedgerKeepingTheNewest() {
        for (int i = 0; i < CoopSessionStats.LEDGER_LIMIT + 5; i++) {
            stats.noteShipLost(HOST, "hull-" + i, "frigate", "corvus", i, "destroyed");
        }

        List<CoopSessionStats.ShipLoss> ledger = stats.shipLossLedger();
        assertEquals(CoopSessionStats.LEDGER_LIMIT, ledger.size());
        assertEquals("hull-5", ledger.get(0).hullName());
        assertEquals("hull-24", ledger.get(ledger.size() - 1).hullName());
        assertEquals(CoopSessionStats.LEDGER_LIMIT + 5L, stats.player(HOST).shipsLost());
    }

    @Test
    void daysSinceLastHullLossTracksTheMostRecentLoss() {
        stats.noteDaysElapsed(50f);
        assertNull(stats.daysSinceLastHullLoss());

        stats.noteShipLost(HOST, "Wolf", "frigate", "corvus", 20f, "destroyed");
        assertEquals(30f, stats.daysSinceLastHullLoss(), 0.001f);
        assertEquals(20f, stats.lastHullLossDay(), 0.001f);

        stats.noteShipLost(GUEST, "Lasher", "frigate", "askonia", 45f, "scuttled");
        assertEquals(5f, stats.daysSinceLastHullLoss(), 0.001f);

        // An out-of-order older loss does not move the mark backwards.
        stats.noteShipLost(GUEST, "Kite", "frigate", "askonia", 3f, "destroyed");
        assertEquals(5f, stats.daysSinceLastHullLoss(), 0.001f);
    }

    @Test
    void daysSinceLastHullLossIsClampedAtZero() {
        stats.noteDaysElapsed(10f);
        stats.noteShipLost(HOST, "Wolf", "frigate", "corvus", 12f, "destroyed");

        assertEquals(0f, stats.daysSinceLastHullLoss(), 0.001f);
    }

    @Test
    void anyTallyMakesTheStatsNonEmpty() {
        stats.noteSalvage();

        assertFalse(stats.isEmpty());
    }

    @Test
    void playerReturnsTheLiveColumnNotACopy() {
        stats.noteBattle(HOST, true);

        assertSame(stats.player(HOST), stats.player(HOST));
    }

    @Test
    void persistenceRoundTripsThroughAPlainMap() {
        stats.notePlayer(HOST, "Ayo");
        stats.noteSalvage();
        Map<String, Object> persistent = new HashMap<>();

        assertTrue(stats.writeInto(persistent));
        assertSame(stats, CoopSessionStats.readFrom(persistent));
        assertTrue(persistent.containsKey(CoopSessionStats.PERSISTENT_KEY));
    }

    @Test
    void persistenceToleratesNullsAndForeignValues() {
        assertFalse(stats.writeInto(null));
        assertNull(CoopSessionStats.readFrom(null));
        assertNull(CoopSessionStats.readFrom(Map.of(CoopSessionStats.PERSISTENT_KEY, "not-stats")));
        assertNull(CoopSessionStats.readFrom(Map.of()));
    }

    // ---- XStream (the save) ----------------------------------------------------------------------

    /**
     * The aliases {@code CoopModPlugin.configureXStream} must register, on the real bundled XStream.
     * {@link DomDriver} rather than the default for the same reason {@code CoopGuestSnapshotTest}
     * gives: 1.4.10's no-arg constructor reaches for an XPP parser that is not on the test classpath.
     */
    private static XStream aliasedXStream() {
        XStream x = new XStream(new DomDriver());
        x.alias("coopStats", CoopSessionStats.class);
        x.alias("coopStatsPlayer", CoopSessionStats.PlayerStats.class);
        x.alias("coopStatsLoss", CoopSessionStats.ShipLoss.class);
        return x;
    }

    @Test
    void survivesTheSaveThroughARealXStream() {
        stats.notePlayer(HOST, "Ayo");
        stats.notePlayer(GUEST, "Partner");
        stats.noteBattle(HOST, true);
        stats.noteDistance(GUEST, 4_200f);
        stats.noteTrade(HOST, "jangala_market", 77_000L);
        stats.noteSystemVisited(GUEST, "corvus");
        stats.noteSalvage();
        stats.noteFleetsDestroyed(2);
        stats.noteTogether(600f);
        stats.noteDaysElapsed(31.5f);
        stats.noteShipLost(GUEST, "Wolf", "frigate", "Corvus", 12f, "destroyed");
        XStream x = aliasedXStream();

        CoopSessionStats restored = (CoopSessionStats) x.fromXML(x.toXML(stats));

        assertEquals(List.of(HOST, GUEST), restored.playerIds());
        assertEquals("Partner", restored.playerName(GUEST));
        assertEquals(1L, restored.player(HOST).battlesFought());
        assertEquals(4_200f, restored.player(GUEST).distanceTraveledSu(), 0.01f);
        assertEquals(77_000L, restored.player(HOST).bestSingleTradeCredits());
        assertEquals(List.of("corvus"), restored.player(GUEST).systemsVisited());
        assertEquals(1L, restored.salvageEventsTeam());
        assertEquals(2L, restored.fleetsDestroyedTeam());
        assertEquals(600f, restored.timeFlownTogetherSeconds(), 0.01f);
        assertEquals(31.5f, restored.daysElapsed(), 0.01f);
        assertEquals(19.5f, restored.daysSinceLastHullLoss(), 0.01f);
        assertEquals(1, restored.shipLossLedger().size());
        assertEquals("Wolf", restored.shipLossLedger().get(0).hullName());
    }

    @Test
    void theSaveSpellsTheAliasesNotThePackageNames() {
        stats.notePlayer(HOST, "Ayo");
        stats.noteShipLost(HOST, "Wolf", "frigate", "Corvus", 1f, "destroyed");

        String xml = aliasedXStream().toXML(stats);

        assertTrue(xml.startsWith("<coopStats>"), xml);
        assertTrue(xml.contains("<coopStatsPlayer>"), xml);
        assertTrue(xml.contains("<coopStatsLoss>"), xml);
        assertFalse(xml.contains("coop.stats.CoopSessionStats"), xml);
    }

    @Test
    void aStatsObjectXStreamBuiltWithoutRunningTheConstructorStillWorks() {
        // XStream runs neither constructors nor field initialisers, so every collection field can
        // come back null. This is the shape a save written by an older build hands us.
        XStream x = aliasedXStream();

        CoopSessionStats restored = (CoopSessionStats) x.fromXML("<coopStats/>");

        assertTrue(restored.playerIds().isEmpty());
        assertTrue(restored.shipLossLedger().isEmpty());
        assertEquals(0, restored.systemsVisitedUnionCount());
        assertEquals(0, restored.marketsTradedWithUnionCount());
        assertEquals(0L, restored.coloniesFoundedTeam());
        // The reason lastHullLossDay is a boxed Float and not a primitive with a -1 sentinel: with
        // no initialiser run, a primitive would be 0 and this would read "lost a hull on day zero".
        assertNull(restored.daysSinceLastHullLoss());
        assertNull(restored.lastHullLossDay());
        assertTrue(restored.isEmpty());
        restored.noteBattle(HOST, true);
        assertEquals(1L, restored.player(HOST).battlesFought());
    }
}
