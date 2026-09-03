package coop.launcher;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopInstallCheckTest {

    private static final String MOD_GAME_VERSION = "0.98a-RC8";

    /**
     * A real log head, down to the log4j uptime column and the two spaces after the level. The
     * launcher line is the only thing in a Starsector install that names the installed version.
     */
    private static final String LOG_HEAD = String.join("\n",
            "0    [main] INFO  com.fs.starfarer.StarfarerLauncher  - Starting Starsector 0.98a-RC8"
                    + " launcher",
            "0    [main] INFO  com.fs.starfarer.StarfarerLauncher  - Running in D:\\Games\\Starsector",
            "17   [main] INFO  com.fs.starfarer.settings.StarfarerSettings  - Reading settings");

    private static final String CLASSPATH_TAIL =
            "janino.jar;starfarer.api.jar;json.jar com.fs.starfarer.StarfarerLauncher";

    private static final String GOOD_VMPARAMS =
            "java.exe -Xmx2048m" + CoopVmparamsText.CLASSPATH_MARKER
                    + CoopVmparamsText.FORKS_ENTRY + CLASSPATH_TAIL;

    private static final String STOCK_VMPARAMS =
            "java.exe -Xmx2048m" + CoopVmparamsText.CLASSPATH_MARKER + CLASSPATH_TAIL;

    private static final String STALE_VMPARAMS =
            "java.exe -Xmx2048m -Dcoop.hostPort=7777" + CoopVmparamsText.CLASSPATH_MARKER
                    + CoopVmparamsText.FORKS_ENTRY + CLASSPATH_TAIL;

    private static CoopInstallCheck.Inputs healthy() {
        return new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true, true, true,
                GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);
    }

    private static CoopInstallCheck.Row row(List<CoopInstallCheck.Row> rows, String labelFragment) {
        for (CoopInstallCheck.Row row : rows) {
            if (row.label().contains(labelFragment)) {
                return row;
            }
        }
        return null;
    }

    @Test
    void aHealthyInstallProducesNothingButOkRows() {
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(healthy());

        for (CoopInstallCheck.Row row : rows) {
            assertEquals(CoopInstallCheck.Status.OK, row.status(), row.toString());
            assertEquals("", row.fix(), row.toString());
        }
        assertFalse(CoopInstallCheck.blocked(rows));
    }

    @Test
    void theFirstRowNamesTheInstallThatWasFound() {
        assertEquals("K:\\Starsector", CoopInstallCheck.rows(healthy()).get(0).detail());
    }

    @Test
    void aStockVmparamsFailsTheClasspathRowAndBlocksLaunch() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, STOCK_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);

        CoopInstallCheck.Row classpath = row(CoopInstallCheck.rows(in), "coop-forks.jar first");
        assertNotNull(classpath);
        assertEquals(CoopInstallCheck.Status.FAIL, classpath.status());
        assertTrue(classpath.fix().contains(CoopVmparamsText.FORKS_ENTRY), classpath.fix());
        assertTrue(CoopInstallCheck.blocked(CoopInstallCheck.rows(in)));
    }

    @Test
    void aLeftoverCoopPropertyWarnsAndDoesNotBlockLaunch() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, STALE_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(in);

        CoopInstallCheck.Row stale = row(rows, "no leftover -Dcoop.*");
        assertNotNull(stale);
        assertEquals(CoopInstallCheck.Status.WARN, stale.status());
        assertTrue(stale.detail().contains("-Dcoop.hostPort=7777"), stale.detail());
        assertFalse(CoopInstallCheck.blocked(rows), "a stale -D must never stop a launch");
    }

    @Test
    void aModThatIsNotTickedFails() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"lw_lazylib\"]}", "0.1.0", "0.1.0",
                null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);

        CoopInstallCheck.Row enabled = row(CoopInstallCheck.rows(in), "enabled_mods.json");
        assertNotNull(enabled);
        assertEquals(CoopInstallCheck.Status.FAIL, enabled.status());
    }

    @Test
    void unparsableEnabledModsIsToldApartFromANotTickedMod() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{ this is not json", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);

        CoopInstallCheck.Row enabled = row(CoopInstallCheck.rows(in), "enabled_mods.json");
        assertNotNull(enabled);
        assertEquals(CoopInstallCheck.Status.FAIL, enabled.status());
        assertTrue(enabled.detail().contains("not valid JSON"), enabled.detail());
    }

    @Test
    void aVersionMismatchBetweenModInfoAndTheJarFails() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.1", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);

        CoopInstallCheck.Row version = row(CoopInstallCheck.rows(in), "mod_info.json version");
        assertNotNull(version);
        assertEquals(CoopInstallCheck.Status.FAIL, version.status());
        assertTrue(version.detail().contains("0.1.0"), version.detail());
        assertTrue(version.detail().contains("0.1.1"), version.detail());
    }

    @Test
    void anUnreadableSettingsFileFailsAndBlocksLaunch() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0",
                "Expected a ',' or '}' at character 42", MOD_GAME_VERSION, MOD_GAME_VERSION, false);
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(in);

        CoopInstallCheck.Row settings = row(rows, "coop_options.json.data");
        assertNotNull(settings);
        assertEquals(CoopInstallCheck.Status.FAIL, settings.status());
        assertTrue(settings.detail().contains("settings file unreadable"), settings.detail());
        assertTrue(CoopInstallCheck.blocked(rows));
    }

    @Test
    void aMissingJreIsReportedWithoutPretendingTheInstallIsBroken() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, false, true,
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false);

        CoopInstallCheck.Row jre = row(CoopInstallCheck.rows(in), "javaw.exe");
        assertNotNull(jre);
        assertEquals(CoopInstallCheck.Status.FAIL, jre.status());
        assertTrue(jre.fix().contains(".bat"), jre.fix());
    }

    // ---- game version ---------------------------------------------------------------------------

    private static CoopInstallCheck.Inputs withGameVersion(String modGameVersion,
                                                           String gameVersion, boolean allowed) {
        return new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true, true, true,
                GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null,
                modGameVersion, gameVersion, allowed);
    }

    @Test
    void aMatchingGameVersionSaysSoAndNamesTheVersion() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withGameVersion(MOD_GAME_VERSION, MOD_GAME_VERSION, false)),
                "Game version");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.OK, row.status());
        assertEquals("matches the mod: 0.98a-RC8", row.detail());
    }

    @Test
    void aDifferentGameVersionFailsAndBlocksLaunch() {
        List<CoopInstallCheck.Row> rows =
                CoopInstallCheck.rows(withGameVersion(MOD_GAME_VERSION, "0.99a-RC1", false));
        CoopInstallCheck.Row row = row(rows, "Game version");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.FAIL, row.status());
        assertEquals("game is 0.99a-RC1, the mod was built for 0.98a-RC8", row.detail());
        assertTrue(row.fix().contains("Install Starsector 0.98a-RC8 on both PCs"), row.fix());
        assertTrue(row.fix().contains("Allow game version mismatch"), row.fix());
        assertTrue(CoopInstallCheck.blocked(rows));
    }

    @Test
    void theDeveloperFlagDowngradesTheRowSoLaunchStaysAvailable() {
        List<CoopInstallCheck.Row> rows =
                CoopInstallCheck.rows(withGameVersion(MOD_GAME_VERSION, "0.99a-RC1", true));
        CoopInstallCheck.Row row = row(rows, "Game version");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.WARN, row.status(),
                "the row is downgraded rather than special-cased in blocked(), so what the player"
                        + " sees matches what the button does");
        assertFalse(CoopInstallCheck.blocked(rows));
    }

    @Test
    void anUnknownGameVersionIsInfoRatherThanAProblem() {
        List<CoopInstallCheck.Row> rows =
                CoopInstallCheck.rows(withGameVersion(MOD_GAME_VERSION, null, false));
        CoopInstallCheck.Row row = row(rows, "Game version");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.INFO, row.status());
        assertEquals("unknown until the game has run once", row.detail());
        assertFalse(CoopInstallCheck.blocked(rows));
    }

    @Test
    void aModInfoWithNoGameVersionIsAlsoInfo() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withGameVersion(null, MOD_GAME_VERSION, false)),
                "Game version");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.INFO, row.status());
    }

    @Test
    void theVersionIsReadOffTheLauncherLineOfARealLogHead() {
        assertEquals("0.98a-RC8", CoopInstallCheck.gameVersionInLogText(LOG_HEAD));
    }

    @Test
    void theLastLauncherLineWinsBecauseTheLogIsAppendedToAcrossRuns() {
        String twoRuns = LOG_HEAD + "\n"
                + "0    [main] INFO  com.fs.starfarer.StarfarerLauncher  - Starting Starsector"
                + " 0.99a-RC1 launcher\n";

        assertEquals("0.99a-RC1", CoopInstallCheck.gameVersionInLogText(twoRuns));
    }

    @Test
    void aLogWithNoLauncherLineYieldsNothingRatherThanAGuess() {
        assertNull(CoopInstallCheck.gameVersionInLogText(""));
        assertNull(CoopInstallCheck.gameVersionInLogText(null));
        assertNull(CoopInstallCheck.gameVersionInLogText(
                "123542 [Thread-2] INFO  coop.net.CoopNetPump  - Coop host started"));
        assertNull(CoopInstallCheck.gameVersionInLogText(
                "0    [main] INFO  x  - Starting Starsector with no suffix"));
    }

    @Test
    void windowsLineEndingsAreNotPartOfTheVersion() {
        assertEquals("0.98a-RC8", CoopInstallCheck.gameVersionInLogText(
                "0    [main] INFO  x  - Starting Starsector 0.98a-RC8 launcher\r\nnext line\r\n"));
    }

    @Test
    void enabledModsParsingAnswersThreeWays() {
        assertEquals(Boolean.TRUE,
                CoopInstallCheck.enabledModsContains("{\"enabledMods\":[\"coop\",\"nex\"]}", "coop"));
        assertEquals(Boolean.FALSE,
                CoopInstallCheck.enabledModsContains("{\"enabledMods\":[\"nex\"]}", "coop"));
        assertEquals(Boolean.FALSE, CoopInstallCheck.enabledModsContains("{}", "coop"));
        assertNull(CoopInstallCheck.enabledModsContains("nonsense", "coop"));
    }
}
