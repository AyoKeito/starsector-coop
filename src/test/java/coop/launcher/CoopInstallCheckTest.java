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

    /** The {@code Coop-Git-Commit} both jars of one build carry. */
    private static final String COMMIT = "1a2b3c4d5e6f";

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
                GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");
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
                true, true, STOCK_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");

        CoopInstallCheck.Row classpath = row(CoopInstallCheck.rows(in), "coop-forks.jar first");
        assertNotNull(classpath);
        assertEquals(CoopInstallCheck.Status.FAIL, classpath.status());
        assertTrue(classpath.fix().contains(CoopVmparamsText.FORKS_ENTRY), classpath.fix());
        assertTrue(CoopInstallCheck.blocked(CoopInstallCheck.rows(in)));
    }

    @Test
    void aLeftoverCoopPropertyWarnsAndDoesNotBlockLaunch() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, STALE_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");
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
                null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");

        CoopInstallCheck.Row enabled = row(CoopInstallCheck.rows(in), "enabled_mods.json");
        assertNotNull(enabled);
        assertEquals(CoopInstallCheck.Status.FAIL, enabled.status());
    }

    @Test
    void unparsableEnabledModsIsToldApartFromANotTickedMod() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{ this is not json", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");

        CoopInstallCheck.Row enabled = row(CoopInstallCheck.rows(in), "enabled_mods.json");
        assertNotNull(enabled);
        assertEquals(CoopInstallCheck.Status.FAIL, enabled.status());
        assertTrue(enabled.detail().contains("not valid JSON"), enabled.detail());
    }

    @Test
    void aVersionMismatchBetweenModInfoAndTheJarFails() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.1", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");

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
                "Expected a ',' or '}' at character 42", MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");
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
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null, MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");

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
                modGameVersion, gameVersion, allowed, COMMIT, "0.1.0", COMMIT, true, "", true, "", "");
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
        assertTrue(row.detail().startsWith("unknown until the game has run once"), row.detail());
        assertTrue(row.detail().contains("newer than the last log"), row.detail());
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

    // ---- a log that is older than the game it describes -------------------------------------------

    @Test
    void aLogOlderThanTheEngineJarIsStaleAndAMissingTimestampIsNot() {
        assertTrue(CoopInstallCheck.logPredatesTheGame(2_000L, 1_000L));
        assertFalse(CoopInstallCheck.logPredatesTheGame(1_000L, 2_000L));
        assertFalse(CoopInstallCheck.logPredatesTheGame(1_000L, 1_000L));
        assertFalse(CoopInstallCheck.logPredatesTheGame(0L, 1_000L),
                "no engine jar is no evidence of an update");
        assertFalse(CoopInstallCheck.logPredatesTheGame(2_000L, 0L));
    }

    /**
     * The lockout this exists for: the player updates Starsector in place, the log still names the
     * version from before the update, and the game-version row blocks Launch over a mismatch that
     * is not real. A stale answer becomes no answer, which is an INFO row.
     */
    @Test
    void aGameUpdatedSinceTheLastRunReportsAnUnknownVersionRatherThanTheOldOne(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws java.io.IOException {
        java.io.File root = temp.toFile();
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(root);
        java.nio.file.Files.createDirectories(layout.starsectorCore().toPath());
        java.nio.file.Files.writeString(layout.starsectorLog().toPath(), LOG_HEAD);
        java.nio.file.Files.writeString(layout.starfarerObfJar().toPath(), "not really a jar");

        assertTrue(layout.starsectorLog().setLastModified(1_700_000_000_000L));
        assertTrue(layout.starfarerObfJar().setLastModified(1_700_000_000_000L - 60_000L));
        assertEquals("0.98a-RC8", CoopInstallCheck.readGameVersion(layout),
                "a log written after the game files is the ordinary case");

        assertTrue(layout.starfarerObfJar().setLastModified(1_700_000_000_000L + 60_000L));
        assertNull(CoopInstallCheck.readGameVersion(layout),
                "the game was updated after that log was written, so its version is not the"
                        + " installed one");
    }

    /** No {@code starfarer_obf.jar} on a partial tree: the API jar is the fallback timestamp. */
    @Test
    void theApiJarIsUsedWhenTheObfuscatedOneIsNotThere(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws java.io.IOException {
        java.io.File root = temp.toFile();
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(root);
        java.nio.file.Files.createDirectories(layout.starsectorCore().toPath());
        java.nio.file.Files.writeString(layout.starsectorLog().toPath(), LOG_HEAD);
        assertTrue(layout.starsectorLog().setLastModified(1_700_000_000_000L));

        assertEquals(0L, CoopInstallCheck.engineJarModified(layout),
                "neither jar is there, so there is no update timestamp to compare against");
        assertEquals("0.98a-RC8", CoopInstallCheck.readGameVersion(layout));

        java.nio.file.Files.writeString(layout.starfarerApiJar().toPath(), "not really a jar");
        assertTrue(layout.starfarerApiJar().setLastModified(1_700_000_000_000L + 60_000L));
        assertEquals(1_700_000_000_000L + 60_000L, CoopInstallCheck.engineJarModified(layout));
        assertNull(CoopInstallCheck.readGameVersion(layout));
    }

    // ---- the two jars of one build ---------------------------------------------------------------

    private static CoopInstallCheck.Inputs withJars(String jarVersion, String jarCommit,
                                                    String forksVersion, String forksCommit) {
        return new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true, true, true,
                GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", jarVersion, null,
                MOD_GAME_VERSION, MOD_GAME_VERSION, false, jarCommit, forksVersion, forksCommit,
                true, "", true, "", "");
    }

    @Test
    void twoJarsOfTheSameBuildPassAndNameTheCommit() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withJars("0.1.0", COMMIT, "0.1.0", COMMIT)),
                "coop-forks.jar matches");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.OK, row.status());
        assertTrue(row.detail().contains(COMMIT), row.detail());
    }

    /**
     * The one this row exists for: the version is 0.1.0 on every build, so only the commit tells a
     * mixed folder apart - and nothing at runtime would, because the handshake reports coop.jar's
     * identity on behalf of both jars.
     */
    @Test
    void twoJarsFromDifferentCommitsFailAndBlockLaunch() {
        List<CoopInstallCheck.Row> rows =
                CoopInstallCheck.rows(withJars("0.1.0", COMMIT, "0.1.0", "9f9f9f9f9f9f"));
        CoopInstallCheck.Row row = row(rows, "coop-forks.jar matches");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.FAIL, row.status());
        assertTrue(row.detail().contains(COMMIT), row.detail());
        assertTrue(row.detail().contains("9f9f9f9f9f9f"), row.detail());
        assertTrue(row.fix().contains("Two builds got mixed in one folder"), row.fix());
        assertTrue(CoopInstallCheck.blocked(rows));
    }

    @Test
    void aForksJarFromAnotherVersionFails() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withJars("0.1.0", COMMIT, "0.1.1", COMMIT)),
                "coop-forks.jar matches");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.FAIL, row.status());
    }

    @Test
    void aForksJarWithNoManifestAtAllFails() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withJars("0.1.0", COMMIT, null, null)),
                "coop-forks.jar matches");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.FAIL, row.status());
        assertTrue(row.detail().contains("coop-forks.jar has no version"), row.detail());
    }

    // ---- where the classpath entry actually points -----------------------------------------------

    /** An entry that leads to a jar that is there, whose manifest matches this folder's coop.jar. */
    private static CoopInstallCheck.Inputs withEntry(boolean resolved, String target,
                                                     String forksJarPath) {
        return withEntry(resolved, target, true, forksJarPath, forksJarPath, "0.1.0", COMMIT);
    }

    private static CoopInstallCheck.Inputs withEntry(boolean resolved, String target,
                                                     boolean targetExists, String forksJarPath,
                                                     String identitySource, String forksVersion,
                                                     String forksCommit) {
        return new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true, true, true,
                GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null,
                MOD_GAME_VERSION, MOD_GAME_VERSION, false, COMMIT, forksVersion, forksCommit,
                resolved, target, targetExists, forksJarPath, identitySource);
    }

    @Test
    void anEntryThatResolvesToThisModFoldersJarIsSimplyYes() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withEntry(true,
                        "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar",
                        "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar")),
                "coop-forks.jar first");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.OK, row.status());
        assertEquals("yes", row.detail());
    }

    /**
     * A warning, never a failure. The last audit's version of this check failed a working install
     * whose entry was spelled differently, and a red row over a Launch button that works is how
     * people learn to ignore red rows.
     */
    @Test
    void anEntryPointingAtAnotherFoldersJarWarnsWithBothPathsAndDoesNotBlockLaunch() {
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(withEntry(false,
                "D:\\Dev\\coop\\jars\\coop-forks.jar",
                "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar"));
        CoopInstallCheck.Row row = row(rows, "coop-forks.jar first");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.WARN, row.status());
        assertTrue(row.detail().contains("D:\\Dev\\coop\\jars\\coop-forks.jar"), row.detail());
        assertTrue(row.detail().contains("K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar"),
                row.detail());
        assertFalse(CoopInstallCheck.blocked(rows));
        assertNull(row.fixable(), "the fixer only moves an entry that is not already first");
    }

    /** Nothing was resolved (no vmparams read, or the entry is not a forks jar). Say nothing. */
    @Test
    void anUnresolvedEntryIsNotTurnedIntoAComplaint() {
        CoopInstallCheck.Row row = row(
                CoopInstallCheck.rows(withEntry(false, "", false, "", "", "0.1.0", COMMIT)),
                "coop-forks.jar first");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.OK, row.status());
    }

    /**
     * The failure the WARN above must not swallow. A classpath entry pointing at a jar that is not
     * there loads no forked engine classes at all - the JVM skips a missing entry in silence - so
     * the game starts and every deterministic hook co-op needs is absent. Launch has to stop.
     */
    @Test
    void anEntryPointingAtAJarThatIsNotThereFailsAndBlocksLaunch() {
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(withEntry(false,
                "D:\\Dev\\coop\\jars\\coop-forks.jar", false,
                "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar",
                "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar", "0.1.0", COMMIT));
        CoopInstallCheck.Row row = row(rows, "coop-forks.jar first");

        assertNotNull(row);
        assertEquals(CoopInstallCheck.Status.FAIL, row.status());
        assertTrue(row.detail().contains("D:\\Dev\\coop\\jars\\coop-forks.jar"), row.detail());
        assertTrue(row.detail().contains("does not exist"), row.detail());
        assertTrue(row.fix().contains("K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar"),
                row.fix());
        assertEquals(CoopInstallFixer.Target.VMPARAMS, row.fixable(),
                "the fixer replaces every forks entry with the canonical one, so it fixes this");
        assertTrue(CoopInstallCheck.blocked(rows));
    }

    /**
     * The identity row reads the jar the JVM will load. Two builds sharing one mods folder used to
     * pass this row by comparing the local jar against coop.jar while the game ran the other one.
     */
    @Test
    void aDifferentTargetJarIsWhatTheIdentityRowComparesAndItNamesTheFileItRead() {
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(withEntry(false,
                "D:\\Dev\\coop\\jars\\coop-forks.jar", true,
                "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar",
                "D:\\Dev\\coop\\jars\\coop-forks.jar", "0.1.0", "9f9f9f9f9f9f"));
        CoopInstallCheck.Row identity = row(rows, "coop-forks.jar matches");

        assertNotNull(identity);
        assertEquals(CoopInstallCheck.Status.FAIL, identity.status());
        assertTrue(identity.detail().contains("9f9f9f9f9f9f"), identity.detail());
        assertTrue(identity.detail().contains("read from D:\\Dev\\coop\\jars\\coop-forks.jar"),
                identity.detail());
        assertTrue(identity.fix().contains("D:\\Dev\\coop\\jars\\coop-forks.jar"), identity.fix());
        assertEquals(CoopInstallCheck.Status.WARN, row(rows, "coop-forks.jar first").status(),
                "a target that exists is still only a warning; the identity row is what fails");
        assertTrue(CoopInstallCheck.blocked(rows));
    }

    /** A target that exists and matches says so without dragging a second path into the row. */
    @Test
    void aTargetJarOfTheSameBuildLeavesTheIdentityRowClean() {
        List<CoopInstallCheck.Row> rows = CoopInstallCheck.rows(withEntry(false,
                "D:\\Dev\\coop\\jars\\coop-forks.jar", true,
                "K:\\Starsector\\mods\\coop\\jars\\coop-forks.jar",
                "D:\\Dev\\coop\\jars\\coop-forks.jar", "0.1.0", COMMIT));
        CoopInstallCheck.Row identity = row(rows, "coop-forks.jar matches");

        assertNotNull(identity);
        assertEquals(CoopInstallCheck.Status.OK, identity.status());
        assertTrue(identity.detail().contains("read from D:\\Dev\\coop\\jars\\coop-forks.jar"),
                identity.detail());
        assertFalse(CoopInstallCheck.blocked(rows));
    }

    /**
     * vmparams paths are relative to starsector-core, because that is the working directory
     * starsector.exe gives the JVM - which is why the entry the launcher writes starts with
     * {@code ..\}.
     */
    @Test
    void aRelativeEntryResolvesAgainstStarsectorCoreAndAnAbsoluteOneStandsAlone(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws java.io.IOException {
        java.io.File root = temp.toFile();
        java.nio.file.Files.createDirectories(temp.resolve("starsector-core"));
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(root);

        java.io.File relative = CoopInstallCheck.resolveForksEntry(layout, GOOD_VMPARAMS);
        assertNotNull(relative);
        assertEquals(layout.forksJar().getCanonicalFile(), relative.getCanonicalFile());

        java.io.File absolute = CoopInstallCheck.resolveForksEntry(layout,
                "java.exe" + CoopVmparamsText.CLASSPATH_MARKER
                        + "D:\\Dev\\coop\\jars\\coop-forks.jar;" + CLASSPATH_TAIL);
        assertNotNull(absolute);
        assertEquals(new java.io.File("D:\\Dev\\coop\\jars\\coop-forks.jar"), absolute);

        assertNull(CoopInstallCheck.resolveForksEntry(layout, STOCK_VMPARAMS),
                "a stock first entry is the classpath row's business, not this one's");
        assertNull(CoopInstallCheck.resolveForksEntry(layout, null));
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
