package coop.ui;

import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import coop.net.CoopConnectionRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the engine-free half of the plugin is covered here: tags, wording, lifecycle flags and the
 * hidden-vs-feed rule. The rendering path needs a live {@code TooltipMakerAPI} and is checked by eye
 * in the smoke pass.
 *
 * <p>The Phase 21 sweep in {@code ensureRegistered} (discard every existing instance, add one fresh
 * one) is not exercised here: it needs a fake {@code IntelManagerAPI} plus a {@code SectorAPI} to
 * hand it to {@code ensureRegistered}/{@code remove}, and no such stub exists yet for either type in
 * this test file or in {@code CoopSessionStatsIntelTest}. Only the null-safety paths are covered
 * below; the sweep itself needs the manual smoke pass.
 */
class CoopSessionIntelTest {

    private final CoopSessionIntel intel = new CoopSessionIntel();

    @AfterEach
    void clearStaticHandle() {
        CoopSessionIntelFeed.uninstall();
    }

    @Test
    void lifecycleFlagsStopTheManagerSweepSoTheModControlsRemovalItself() {
        assertFalse(intel.isEnded());
        assertFalse(intel.isEnding());
        assertFalse(intel.shouldRemoveIntel());
    }

    @Test
    void registrationAndRemovalTolerateANullSector() {
        assertNull(CoopSessionIntel.ensureRegistered(null));
        assertFalse(CoopSessionIntel.remove(null));
    }

    @Test
    void isQuietAndUnpinnable() {
        assertFalse(intel.autoAddCampaignMessage());
        assertNull(intel.getCommMessageSound());
        assertFalse(intel.hasImportantButton());
        assertFalse(intel.isImportant());
        assertFalse(intel.isNew());
        assertNull(intel.getIcon());
    }

    @Test
    void namedAndSortedAsTheCoopSessionEntry() {
        assertEquals("Coop Session", CoopSessionIntel.NAME);
        assertEquals(CoopSessionIntel.NAME, intel.getName());
        assertEquals(CoopSessionIntel.NAME, intel.getSortString());
        assertEquals(IntelInfoPlugin.IntelSortTier.TIER_0, intel.getSortTier());
    }

    @Test
    void usesTheLargePageNotTheSmallOne() {
        assertTrue(intel.hasLargeDescription());
        assertFalse(intel.hasSmallDescription());
    }

    @Test
    void carriesTheCoopTag() {
        Set<String> tags = intel.getIntelTags(null);

        assertTrue(tags.contains(CoopSessionIntel.TAG_COOP));
        assertEquals("Coop", CoopSessionIntel.TAG_COOP);
    }

    @Test
    void hiddenWithNoFeedInstalled() {
        assertTrue(intel.isHidden());
    }

    @Test
    void hiddenWhileTheInstalledFeedHasNoRole() {
        CoopSessionIntelFeed feed = new CoopSessionIntelFeed(() -> 0L);
        CoopSessionIntelFeed.install(feed);

        assertTrue(intel.isHidden());
    }

    @Test
    void visibleOnceARoleIsPublishedAndHiddenAgainWhenTheSessionEnds() {
        CoopSessionIntelFeed feed = new CoopSessionIntelFeed(() -> 0L);
        CoopSessionIntelFeed.install(feed);
        feed.publishSession(CoopConnectionRole.GUEST, "session active", "Ayo");

        assertFalse(intel.isHidden());

        feed.endSession();

        assertTrue(intel.isHidden());
    }
}
