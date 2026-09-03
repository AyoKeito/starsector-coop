package coop.ui;

import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import coop.stats.CoopSessionStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same coverage line {@code CoopSessionIntelTest} draws: the engine-free half only — tags, wording,
 * lifecycle flags, the source handle and the hidden rule. Anything that needs a live
 * {@code TooltipMakerAPI} is checked by eye in the smoke pass, and the content it would render is
 * already covered by {@link CoopSessionStatsViewTest}.
 */
class CoopSessionStatsIntelTest {

    private final CoopSessionStatsIntel intel = new CoopSessionStatsIntel();

    @AfterEach
    void clearStaticHandle() {
        CoopSessionStatsIntel.clearSource();
    }

    @Test
    void isPermanentAndNeverSweptAway() {
        assertFalse(intel.isEnded());
        assertFalse(intel.isEnding());
        assertFalse(intel.shouldRemoveIntel());
    }

    @Test
    void isQuietAndUnpinnable() {
        assertFalse(intel.autoAddCampaignMessage());
        assertNull(intel.getCommMessageSound());
        assertFalse(intel.hasImportantButton());
        assertFalse(intel.isNew());
        assertNull(intel.getIcon());
    }

    @Test
    void namedAndSortedAsTheSecondCoopEntry() {
        assertEquals("Coop Stats", CoopSessionStatsIntel.NAME);
        assertEquals(CoopSessionStatsIntel.NAME, intel.getName());
        assertEquals(CoopSessionStatsIntel.NAME, intel.getSortString());
        // Below CoopSessionIntel's TIER_0 so the diagnostics page stays on top.
        assertEquals(IntelInfoPlugin.IntelSortTier.TIER_1, intel.getSortTier());
    }

    @Test
    void usesTheLargePageNotTheSmallOne() {
        assertTrue(intel.hasLargeDescription());
        assertFalse(intel.hasSmallDescription());
    }

    @Test
    void sharesTheCoopFilterBucketWithTheSessionEntry() {
        assertTrue(intel.getIntelTags(null).contains(CoopSessionIntel.TAG_COOP));
    }

    @Test
    void thePinKeyIsTheAgreedSectorMemoryFlag() {
        assertEquals("$coopStatsPinned", CoopSessionStatsIntel.PIN_MEMORY_KEY);
    }

    @Test
    void isImportantIsFalseWithNoSector() {
        // Reading the pin flag must never throw outside a running game; the wiring wave calls this
        // from the intel screen, but a solo/headless path can reach it too.
        assertFalse(intel.isImportant());
        intel.setImportant(Boolean.TRUE);
        assertFalse(intel.isImportant());
    }

    // ---- source handle ---------------------------------------------------------------------------

    @Test
    void hiddenWithNoSourceInstalled() {
        assertTrue(intel.isHidden());
        assertNull(CoopSessionStatsIntel.currentStats());
        assertEquals(Set.of(), CoopSessionStatsIntel.currentAwayPlayerIds());
    }

    @Test
    void visibleOnceASourceIsInstalledAndHiddenAgainWhenItIsCleared() {
        CoopSessionStats stats = new CoopSessionStats();
        CoopSessionStatsIntel.setSource(() -> stats);

        assertFalse(intel.isHidden());
        assertSame(stats, CoopSessionStatsIntel.currentStats());

        CoopSessionStatsIntel.clearSource();

        assertTrue(intel.isHidden());
    }

    @Test
    void theAwaySetIsOptionalAndDefaultsToEmpty() {
        CoopSessionStatsIntel.setSource(CoopSessionStats::new);

        assertEquals(Set.of(), CoopSessionStatsIntel.currentAwayPlayerIds());
    }

    @Test
    void theAwaySetIsPolledNotCached() {
        Set<String>[] away = new Set[]{Set.of("guest-id")};
        CoopSessionStatsIntel.setSource(CoopSessionStats::new, () -> away[0]);

        assertEquals(Set.of("guest-id"), CoopSessionStatsIntel.currentAwayPlayerIds());

        away[0] = Set.of();

        assertEquals(Set.of(), CoopSessionStatsIntel.currentAwayPlayerIds());
    }

    @Test
    void aThrowingSourceDegradesInsteadOfTakingTheIntelScreenWithIt() {
        CoopSessionStatsIntel.setSource(() -> {
            throw new IllegalStateException("pump is mid-teardown");
        }, () -> {
            throw new IllegalStateException("roster is mid-teardown");
        });

        assertNull(CoopSessionStatsIntel.currentStats());
        assertEquals(Set.of(), CoopSessionStatsIntel.currentAwayPlayerIds());
    }

    @Test
    void aNullReturningAwaySupplierIsTreatedAsEmpty() {
        CoopSessionStatsIntel.setSource(CoopSessionStats::new, () -> null);

        assertEquals(Set.of(), CoopSessionStatsIntel.currentAwayPlayerIds());
    }

    // ---- list row --------------------------------------------------------------------------------

    @Test
    void theListLineSaysThereIsNothingYetBeforeAnythingIsTallied() {
        assertEquals(CoopSessionStatsView.NO_DATA_LINE, CoopSessionStatsIntel.listLine());

        CoopSessionStatsIntel.setSource(CoopSessionStats::new);

        assertEquals(CoopSessionStatsView.NO_DATA_LINE, CoopSessionStatsIntel.listLine());
    }

    @Test
    void theListLineSummarisesTheSessionOnceItHasNumbers() {
        CoopSessionStats stats = new CoopSessionStats();
        stats.noteDaysElapsed(64.25f);
        stats.noteTogether(4_321.5f);
        CoopSessionStatsIntel.setSource(() -> stats);

        assertEquals("Day 64.3, flown together 1h 12m", CoopSessionStatsIntel.listLine());
    }

    // ---- registration ----------------------------------------------------------------------------

    @Test
    void registrationAndRemovalTolerateANullSector() {
        assertNull(CoopSessionStatsIntel.ensureRegistered(null));
        assertFalse(CoopSessionStatsIntel.remove(null));
    }

    @Test
    void refreshIsTheOnlyButtonAndItNeedsNoConfirmation() {
        assertFalse(intel.doesButtonHaveConfirmDialog(CoopSessionStatsIntel.BUTTON_REFRESH));
        // A null IntelUIAPI must not throw: the button handler runs on the engine's thread and a
        // throwable there takes the intel tab with it.
        intel.buttonPressConfirmed(CoopSessionStatsIntel.BUTTON_REFRESH, null);
    }
}
