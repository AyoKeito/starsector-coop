package coop.newgame;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import coop.util.CoopLog;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The two world settings a sector cannot be asked for after the fact.
 *
 * <p>A loaded sector remembers its seed string, and the mod records that in the save index so the
 * launcher can put it back in the Seed box. Sector size and star age have no such getter: they are
 * consumed by procgen and then gone. So they are written down at the one moment they are known --
 * while {@link CoopNewGameDialogPlugin} is pinning them onto the {@code CharacterCreationData} the
 * engine is about to generate from -- and stored in the sector's persistent data once that sector
 * exists, which is how they reach the save index and, from there, the launcher.
 *
 * <p><b>Nothing is guessed.</b> A sector generated before this class existed, or through the vanilla
 * dialog with no coop launch behind it, records nothing at all; every reader below then answers
 * {@code ""} and the launcher leaves its controls where the player put them. Reading the launch
 * properties back at load time would be the guess: a saved campaign can be relaunched with any
 * {@code -Dcoop.sectorSize} at all, and that property says nothing about the sector on disk.
 *
 * <p><b>Vocabulary.</b> The stored strings are the launcher's own tokens, not the engine's: sizes
 * {@code small} / {@code normal}, ages {@code young} / {@code average} / {@code old} / {@code mixed}
 * ({@link StarAge#ANY}, which the new-game panel labels "Mixed"). That is what the drop-downs, the
 * invite and {@code coop.sectorAge} all speak, so no side has to translate.
 */
public final class CoopWorldSettings {

    /** Sector persistent data key for the size the sector was generated at. */
    public static final String PERSISTENT_SECTOR_SIZE = "coop.sectorSize";
    /** Sector persistent data key for the star age the sector was generated at. */
    public static final String PERSISTENT_SECTOR_AGE = "coop.sectorAge";

    /**
     * What the new-game dialog last pinned, held from the dialog until
     * {@code onNewGameAfterProcGen} has a sector to put it on. Process-wide because those two are in
     * different classes with nothing but the engine between them, and {@code volatile} because the
     * dialog runs on the UI thread.
     */
    private static volatile String pendingSectorSize = "";
    private static volatile String pendingSectorAge = "";

    private CoopWorldSettings() {
    }

    /** The launcher's token for a {@link StarAge}, or {@code ""} for null. */
    public static String starAgeToken(StarAge age) {
        if (age == null) {
            return "";
        }
        return age == StarAge.ANY ? "mixed" : age.name().toLowerCase(Locale.ROOT);
    }

    /** Called by the new-game dialog every time it pins the panel. Blank values are not remembered. */
    public static void rememberPending(String sectorSize, StarAge sectorAge) {
        String size = text(sectorSize).toLowerCase(Locale.ROOT);
        String age = starAgeToken(sectorAge);
        if (!size.isEmpty()) {
            pendingSectorSize = size;
        }
        if (!age.isEmpty()) {
            pendingSectorAge = age;
        }
    }

    /** Forgets whatever the last new-game dialog pinned. */
    public static void clearPending() {
        pendingSectorSize = "";
        pendingSectorAge = "";
    }

    public static String pendingSectorSize() {
        return pendingSectorSize;
    }

    public static String pendingSectorAge() {
        return pendingSectorAge;
    }

    /**
     * Writes the two settings into a persistent data map. A blank value writes nothing, and takes
     * nothing out either: "not recorded" is a state the readers below already handle, and a half
     * write is how a sector would end up claiming a size it was not generated at.
     *
     * @return true when at least one value was written
     */
    public static boolean storeInto(Map<String, Object> persistentData, String sectorSize,
                                    String sectorAge) {
        Objects.requireNonNull(persistentData, "persistentData");
        String size = text(sectorSize).toLowerCase(Locale.ROOT);
        String age = text(sectorAge).toLowerCase(Locale.ROOT);
        boolean wrote = false;
        if (!size.isEmpty()) {
            persistentData.put(PERSISTENT_SECTOR_SIZE, size);
            wrote = true;
        }
        if (!age.isEmpty()) {
            persistentData.put(PERSISTENT_SECTOR_AGE, age);
            wrote = true;
        }
        return wrote;
    }

    /**
     * Moves whatever the new-game dialog pinned onto the freshly generated sector. Call from
     * {@code onNewGameAfterProcGen} only: on a loaded game there is nothing pending, and the sector
     * already carries its own answer.
     */
    public static void storePendingIntoCurrentSector() {
        String size = pendingSectorSize;
        String age = pendingSectorAge;
        if (size.isEmpty() && age.isEmpty()) {
            // A vanilla new game, or a coop launch whose dialog never got to pin. Recording nothing
            // is the honest answer; the launcher says so rather than showing a made-up size.
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            if (storeInto(sector.getPersistentData(), size, age)) {
                CoopLog.info(CoopWorldSettings.class,
                        "Coop world settings stored sectorSize=" + size + " sectorAge=" + age);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopWorldSettings.class,
                    "Unable to store coop world settings in sector persistent data; the launcher"
                            + " will not be able to restore this campaign's sector size and star age", ex);
        } finally {
            // Whether it landed or not, it belongs to the game that has just been generated.
            clearPending();
        }
    }

    /** The size the loaded sector was generated at, or {@code ""} when it was never recorded. */
    public static String currentSectorSize() {
        return read(PERSISTENT_SECTOR_SIZE);
    }

    /** The star age the loaded sector was generated at, or {@code ""} when it was never recorded. */
    public static String currentSectorAge() {
        return read(PERSISTENT_SECTOR_AGE);
    }

    private static String read(String key) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return "";
            }
            Object value = sector.getPersistentData().get(key);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopWorldSettings.class, "Unable to read " + key + " from the sector", ex);
            return "";
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
