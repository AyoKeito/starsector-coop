package coop.newgame;

import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of {@link CoopWorldSettings} that has no engine in it: the token vocabulary, the
 * persistent-data write, and the handover slot the new-game dialog fills for
 * {@code onNewGameAfterProcGen} to empty.
 *
 * <p>The persistent data map is the real thing as far as this code is concerned - {@code
 * SectorAPI.getPersistentData()} hands back a plain {@code Map<String, Object>}, which is why
 * {@link CoopWorldSettings#storeInto} takes one.
 */
class CoopWorldSettingsTest {

    @AfterEach
    void forgetPending() {
        CoopWorldSettings.clearPending();
    }

    // ---- vocabulary ----------------------------------------------------------------------------

    /** The new-game panel labels {@link StarAge#ANY} "Mixed", and so do the launcher's drop-downs. */
    @Test
    void anyIsWrittenDownAsMixedAndTheRestAreTheirOwnNamesInLowerCase() {
        assertEquals("mixed", CoopWorldSettings.starAgeToken(StarAge.ANY));
        assertEquals("young", CoopWorldSettings.starAgeToken(StarAge.YOUNG));
        assertEquals("average", CoopWorldSettings.starAgeToken(StarAge.AVERAGE));
        assertEquals("old", CoopWorldSettings.starAgeToken(StarAge.OLD));
        assertEquals("", CoopWorldSettings.starAgeToken(null));
    }

    // ---- the persistent data write --------------------------------------------------------------

    @Test
    void bothSettingsSurviveTheRoundTripThroughPersistentData() {
        Map<String, Object> persistentData = new HashMap<>();

        assertTrue(CoopWorldSettings.storeInto(persistentData, "small", "young"));

        assertEquals("small", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_SIZE));
        assertEquals("young", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_AGE));
    }

    @Test
    void whatIsStoredIsTrimmedAndLowerCased() {
        Map<String, Object> persistentData = new HashMap<>();

        CoopWorldSettings.storeInto(persistentData, "  NORMAL ", " Mixed ");

        assertEquals("normal", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_SIZE));
        assertEquals("mixed", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_AGE));
    }

    /**
     * A sector generated through the vanilla dialog knows neither value. Writing a default here is
     * what would make the launcher show a size the player never chose.
     */
    @Test
    void aBlankValueWritesNothingAtAll() {
        Map<String, Object> persistentData = new HashMap<>();

        assertFalse(CoopWorldSettings.storeInto(persistentData, "", null));

        assertTrue(persistentData.isEmpty());
    }

    @Test
    void oneBlankValueDoesNotStopTheOtherBeingWritten() {
        Map<String, Object> persistentData = new HashMap<>();

        assertTrue(CoopWorldSettings.storeInto(persistentData, "small", "  "));

        assertEquals("small", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_SIZE));
        assertFalse(persistentData.containsKey(CoopWorldSettings.PERSISTENT_SECTOR_AGE));
    }

    // ---- the handover slot ----------------------------------------------------------------------

    @Test
    void whatTheDialogPinsIsWhatTheSectorGets() {
        CoopWorldSettings.rememberPending("SMALL", StarAge.ANY);

        Map<String, Object> persistentData = new HashMap<>();
        CoopWorldSettings.storeInto(persistentData, CoopWorldSettings.pendingSectorSize(),
                CoopWorldSettings.pendingSectorAge());

        assertEquals("small", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_SIZE));
        assertEquals("mixed", persistentData.get(CoopWorldSettings.PERSISTENT_SECTOR_AGE));
    }

    @Test
    void aDialogOpenedAndAbandonedLeavesNothingBehind() {
        CoopWorldSettings.rememberPending("small", StarAge.OLD);

        CoopWorldSettings.clearPending();

        assertEquals("", CoopWorldSettings.pendingSectorSize());
        assertEquals("", CoopWorldSettings.pendingSectorAge());
    }

    /** The dialog pins on every frame, so the last pin before Continue is the one that counts. */
    @Test
    void thePinsAfterTheFirstOverwriteIt() {
        CoopWorldSettings.rememberPending("small", StarAge.OLD);
        CoopWorldSettings.rememberPending("normal", StarAge.YOUNG);

        assertEquals("normal", CoopWorldSettings.pendingSectorSize());
        assertEquals("young", CoopWorldSettings.pendingSectorAge());
    }

    @Test
    void aPinWithNothingInItDoesNotErasePendingSettings() {
        CoopWorldSettings.rememberPending("small", StarAge.OLD);

        CoopWorldSettings.rememberPending("  ", null);

        assertEquals("small", CoopWorldSettings.pendingSectorSize());
        assertEquals("old", CoopWorldSettings.pendingSectorAge());
    }
}
