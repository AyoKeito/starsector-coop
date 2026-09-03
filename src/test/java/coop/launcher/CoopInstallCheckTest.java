package coop.launcher;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopInstallCheckTest {

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
                GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null);
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
                true, true, STOCK_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null);

        CoopInstallCheck.Row classpath = row(CoopInstallCheck.rows(in), "coop-forks.jar first");
        assertNotNull(classpath);
        assertEquals(CoopInstallCheck.Status.FAIL, classpath.status());
        assertTrue(classpath.fix().contains(CoopVmparamsText.FORKS_ENTRY), classpath.fix());
        assertTrue(CoopInstallCheck.blocked(CoopInstallCheck.rows(in)));
    }

    @Test
    void aLeftoverCoopPropertyWarnsAndDoesNotBlockLaunch() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, STALE_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null);
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
                null);

        CoopInstallCheck.Row enabled = row(CoopInstallCheck.rows(in), "enabled_mods.json");
        assertNotNull(enabled);
        assertEquals(CoopInstallCheck.Status.FAIL, enabled.status());
    }

    @Test
    void unparsableEnabledModsIsToldApartFromANotTickedMod() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{ this is not json", "0.1.0", "0.1.0", null);

        CoopInstallCheck.Row enabled = row(CoopInstallCheck.rows(in), "enabled_mods.json");
        assertNotNull(enabled);
        assertEquals(CoopInstallCheck.Status.FAIL, enabled.status());
        assertTrue(enabled.detail().contains("not valid JSON"), enabled.detail());
    }

    @Test
    void aVersionMismatchBetweenModInfoAndTheJarFails() {
        CoopInstallCheck.Inputs in = new CoopInstallCheck.Inputs("K:\\Starsector", true, true, true,
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.1", null);

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
                "Expected a ',' or '}' at character 42");
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
                true, true, GOOD_VMPARAMS, "{\"enabledMods\":[\"coop\"]}", "0.1.0", "0.1.0", null);

        CoopInstallCheck.Row jre = row(CoopInstallCheck.rows(in), "javaw.exe");
        assertNotNull(jre);
        assertEquals(CoopInstallCheck.Status.FAIL, jre.status());
        assertTrue(jre.fix().contains(".bat"), jre.fix());
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
