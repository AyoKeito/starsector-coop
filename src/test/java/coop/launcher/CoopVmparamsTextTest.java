package coop.launcher;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the four shapes a real {@code vmparams} takes on this project: stock 0.98a-RC8, the dev
 * box's modded-JRE copy, one patched by hand per INSTALL.md, and one a launch script left
 * {@code -Dcoop.*} on.
 *
 * <p>The strings below are trimmed copies of the real files (the flag soup in the middle is elided;
 * nothing here reads it). What matters is kept verbatim: the tail of the JVM flags, the
 * {@code -classpath} marker with its single leading space, and the classpath value itself.
 */
class CoopVmparamsTextTest {

    private static final String FLAGS_TAIL =
            "java.exe -noverify -XX:+UnlockDiagnosticVMOptions -Xmx2048m -Xms2048m -Xss4m"
                    + " -Dcom.fs.starfarer.settings.paths.saves=..\\\\saves"
                    + " -Dcom.fs.starfarer.settings.paths.logs=.";

    private static final String STOCK_CLASSPATH =
            "janino.jar;commons-compiler.jar;starfarer.api.jar;starfarer_obf.jar;json.jar;lwjgl.jar;"
                    + "log4j-1.2.9.jar;xstream-1.4.10.jar com.fs.starfarer.StarfarerLauncher";

    /** A stock install: no coop entry, no coop properties. */
    private static final String STOCK =
            FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER + STOCK_CLASSPATH;

    /** Patched by hand exactly as INSTALL.md section 3 describes. */
    private static final String PATCHED = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
            + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

    /** What launch-host.ps1 leaves behind: the forks entry plus a set of coop properties. */
    private static final String LAUNCH_SCRIPT_PATCHED = FLAGS_TAIL
            + " -Dcoop.hostPort=7777 -Dcoop.newGameSeed=MN-1234567890123456789"
            + " -Dcoop.debug.diagnostics=true -Dcoop.debug.bridge=7801"
            + CoopVmparamsText.CLASSPATH_MARKER + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

    @Test
    void aStockVmparamsHasNoForksEntryAndNoCoopProperties() {
        assertTrue(CoopVmparamsText.hasClasspath(STOCK));
        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath(STOCK));
        assertFalse(CoopVmparamsText.hasForksLaterOnClasspath(STOCK));
        assertEquals(List.of(), CoopVmparamsText.staleCoopProperties(STOCK));
    }

    @Test
    void aHandPatchedVmparamsPasses() {
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(PATCHED));
        assertFalse(CoopVmparamsText.hasForksLaterOnClasspath(PATCHED));
        assertEquals(List.of(), CoopVmparamsText.staleCoopProperties(PATCHED));
    }

    @Test
    void theDevLaunchScriptsLeaveTheForksEntryAndFourCoopProperties() {
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(LAUNCH_SCRIPT_PATCHED));
        assertEquals(
                List.of("-Dcoop.hostPort=7777",
                        "-Dcoop.newGameSeed=MN-1234567890123456789",
                        "-Dcoop.debug.diagnostics=true",
                        "-Dcoop.debug.bridge=7801"),
                CoopVmparamsText.staleCoopProperties(LAUNCH_SCRIPT_PATCHED));
    }

    @Test
    void theForksEntryBehindAnotherJarIsNotFirst() {
        String behind = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "janino.jar;" + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath(behind));
        assertTrue(CoopVmparamsText.hasForksLaterOnClasspath(behind));
    }

    @Test
    void forwardSlashesAndOddCasingStillCount() {
        String slashes = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "../Mods/Coop/Jars/COOP-FORKS.JAR;" + STOCK_CLASSPATH;

        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(slashes));
    }

    @Test
    void aFileWithNoClasspathIsRejectedRatherThanGuessedAt() {
        assertFalse(CoopVmparamsText.hasClasspath("java.exe -Xmx2048m"));
        assertFalse(CoopVmparamsText.hasForksFirstOnClasspath("java.exe -Xmx2048m"));
        assertFalse(CoopVmparamsText.hasClasspath(null));
    }

    @Test
    void theFixTextNamesTheExactEntryToPaste() {
        String fix = CoopVmparamsText.forksFixText();

        assertTrue(fix.contains(CoopVmparamsText.FORKS_ENTRY), fix);
        assertTrue(fix.contains("-classpath"), fix);
        assertTrue(fix.contains("INSTALL.md"), fix);
    }

    @Test
    void theStalePropertyFixNamesEveryLeftoverProperty() {
        String fix = CoopVmparamsText.stalePropertyFixText(
                CoopVmparamsText.staleCoopProperties(LAUNCH_SCRIPT_PATCHED));

        assertTrue(fix.contains("-Dcoop.hostPort=7777"), fix);
        assertTrue(fix.contains("-Dcoop.debug.bridge=7801"), fix);
    }
}
