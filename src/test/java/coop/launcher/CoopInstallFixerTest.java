package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two edits the Fix button makes. The transforms are covered as text functions - which is the
 * whole reason they are text functions - and the disk half is exercised against a fake install tree
 * under a temporary folder, never a real Starsector install.
 *
 * <p>The strings mirror {@link CoopVmparamsTextTest}: the tail of the JVM flags, the
 * {@code -classpath} marker with its single leading space, and a real classpath value with the main
 * class on the end of it.
 */
class CoopInstallFixerTest {

    private static final String FLAGS_TAIL =
            "java.exe -noverify -XX:+UnlockDiagnosticVMOptions -Xmx2048m -Xms2048m -Xss4m"
                    + " -Dcom.fs.starfarer.settings.paths.saves=..\\\\saves"
                    + " -Dcom.fs.starfarer.settings.paths.logs=.";

    private static final String STOCK_CLASSPATH =
            "janino.jar;commons-compiler.jar;starfarer.api.jar;starfarer_obf.jar;json.jar;lwjgl.jar;"
                    + "log4j-1.2.9.jar;xstream-1.4.10.jar com.fs.starfarer.StarfarerLauncher";

    private static final String STOCK = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
            + STOCK_CLASSPATH;

    private static final String PATCHED = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
            + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH;

    @TempDir
    Path temp;

    private File root;
    private File modRoot;
    private CoopInstallLayout layout;

    @BeforeEach
    void buildTree() throws IOException {
        root = temp.resolve("install").toFile();
        modRoot = new File(new File(root, "mods"), "coop");
        layout = CoopInstallLayout.of(root, modRoot);
        assertTrue(new File(new File(root, "jre"), "bin").mkdirs());
        Files.writeString(layout.javaw().toPath(), "not really a javaw");
    }

    // ---- the vmparams transform -----------------------------------------------------------------

    @Test
    void aStockLineGetsTheForksEntryAtTheFrontAndNothingElseChanges() {
        String patched = CoopInstallFixer.patchVmparams(STOCK);
        assertEquals(PATCHED, patched);
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(patched));
        assertFalse(patched.endsWith("\n"));
    }

    @Test
    void anAlreadyPatchedLineIsLeftExactlyAsItIs() {
        assertEquals(PATCHED, CoopInstallFixer.patchVmparams(PATCHED));
    }

    @Test
    void patchingTwiceProducesTheSameLine() {
        String once = CoopInstallFixer.patchVmparams(STOCK);
        assertEquals(once, CoopInstallFixer.patchVmparams(once));
    }

    /**
     * The failure mode with its own row: the entry is on the line but behind another jar, where the
     * JVM never reaches it. It has to move, not gain a second copy.
     */
    @Test
    void anEntryThatIsNotFirstIsMovedRatherThanDuplicated() {
        String later = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "janino.jar;" + CoopVmparamsText.FORKS_ENTRY + STOCK_CLASSPATH
                        .substring("janino.jar;".length());
        String patched = CoopInstallFixer.patchVmparams(later);
        assertEquals(PATCHED, patched);
        assertEquals(1, countOccurrences(patched, "coop-forks.jar"));
    }

    /**
     * Every spelling {@link CoopVmparamsText} accepts as a forks entry is also one the fixer knows
     * to remove, or a player who typed an absolute path would end up with two entries.
     */
    @Test
    void aDifferentlySpelledEntryIsReplacedByTheCanonicalOne() {
        String odd = FLAGS_TAIL + CoopVmparamsText.CLASSPATH_MARKER
                + "janino.jar;K:/Starsector/mods/coop/jars/COOP-FORKS.JAR;" + STOCK_CLASSPATH
                        .substring("janino.jar;".length());
        String patched = CoopInstallFixer.patchVmparams(odd);
        assertEquals(PATCHED, patched);
        assertEquals(1, countOccurrences(patched.toLowerCase(java.util.Locale.ROOT),
                "coop-forks.jar"));
    }

    /** {@code -Dcoop.*} is a warning about someone's deliberate flags, not something to delete. */
    @Test
    void coopPropertiesAreLeftOnTheLine() {
        String withProperties = FLAGS_TAIL + " -Dcoop.hostPort=7777 -Dcoop.debug.diagnostics=true"
                + CoopVmparamsText.CLASSPATH_MARKER + STOCK_CLASSPATH;
        String patched = CoopInstallFixer.patchVmparams(withProperties);
        assertEquals(2, CoopVmparamsText.staleCoopProperties(patched).size());
        assertTrue(CoopVmparamsText.hasForksFirstOnClasspath(patched));
    }

    @Test
    void aTrailingNewlineIsDroppedRatherThanCarriedIntoTheNewFile() {
        assertEquals(PATCHED, CoopInstallFixer.patchVmparams(STOCK + "\r\n"));
    }

    @Test
    void aFileWithNoClasspathIsRefused() {
        String refusal = CoopInstallFixer.vmparamsRefusal("java.exe -Xmx2048m");
        assertNotNull(refusal);
        assertTrue(refusal.contains("-classpath"), refusal);
        assertThrows(IllegalArgumentException.class,
                () -> CoopInstallFixer.patchVmparams("java.exe -Xmx2048m"));
    }

    @Test
    void aFileSplitOverSeveralLinesIsRefused() {
        String refusal = CoopInstallFixer.vmparamsRefusal(FLAGS_TAIL + "\n"
                + CoopVmparamsText.CLASSPATH_MARKER + STOCK_CLASSPATH);
        assertNotNull(refusal);
        assertTrue(refusal.contains("several lines"), refusal);
    }

    @Test
    void anUnreadableFileIsRefused() {
        assertNotNull(CoopInstallFixer.vmparamsRefusal(null));
    }

    @Test
    void aStockLineIsNotRefused() {
        assertNull(CoopInstallFixer.vmparamsRefusal(STOCK));
    }

    // ---- the enabled_mods transform -------------------------------------------------------------

    @Test
    void theModIsAddedWhenItIsAbsentAndTheOthersKeepTheirOrder() {
        String before = "{\n  \"enabledMods\": [\n    \"lw_lazylib\",\n    \"nexerelin\"\n  ]\n}\n";
        String after = CoopInstallFixer.addEnabledMod(before, "coop");
        assertEquals("{\n  \"enabledMods\": [\n    \"lw_lazylib\",\n    \"nexerelin\",\n"
                + "    \"coop\"\n  ]\n}\n", after);
    }

    @Test
    void aFileThatAlreadyHasTheModIsNotRewritten() {
        assertNull(CoopInstallFixer.addEnabledMod(
                "{\n  \"enabledMods\": [\n    \"coop\"\n  ]\n}\n", "coop"));
    }

    @Test
    void aMissingFileProducesADocumentHoldingOnlyTheMod() {
        assertEquals("{\n  \"enabledMods\": [\n    \"coop\"\n  ]\n}\n",
                CoopInstallFixer.addEnabledMod(null, "coop"));
        assertEquals("{\n  \"enabledMods\": [\n    \"coop\"\n  ]\n}\n",
                CoopInstallFixer.addEnabledMod("   ", "coop"));
    }

    @Test
    void aDocumentWithNoEnabledModsKeyGetsOne() {
        assertEquals("{\n  \"enabledMods\": [\n    \"coop\"\n  ]\n}\n",
                CoopInstallFixer.addEnabledMod("{}", "coop"));
    }

    /** Vanilla writes only {@code enabledMods}, but nothing else in the file may be thrown away. */
    @Test
    void anyOtherTopLevelKeyIsCarriedAcross() {
        String after = CoopInstallFixer.addEnabledMod(
                "{\"enabledMods\": [\"nexerelin\"], \"somethingElse\": 7}", "coop");
        assertEquals("{\n  \"enabledMods\": [\n    \"nexerelin\",\n    \"coop\"\n  ],\n"
                + "  \"somethingElse\": 7\n}\n", after);
    }

    @Test
    void aFileThatIsNotJsonIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopInstallFixer.addEnabledMod("not json at all", "coop"));
    }

    @Test
    void anEnabledModsThatIsNotAListIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopInstallFixer.addEnabledMod("{\"enabledMods\": \"coop\"}", "coop"));
    }

    // ---- the disk half --------------------------------------------------------------------------

    @Test
    void applyingTheVmparamsFixWritesTheLineAndTakesABackup() throws IOException {
        writeVmparams(STOCK);
        CoopInstallFixer.Result result = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.VMPARAMS);
        assertTrue(result.changed(), result.message());
        assertFalse(result.accessDenied());
        assertEquals(PATCHED, readVmparams());
        assertEquals(STOCK, Files.readString(
                CoopInstallFixer.vmparamsBackup(layout.vmparams()).toPath(),
                StandardCharsets.ISO_8859_1));
    }

    /** The file on disk has to stay one ASCII line with nothing on the end of it. */
    @Test
    void theWrittenFileIsOneAsciiLineWithNoTrailingNewline() throws IOException {
        writeVmparams(STOCK);
        CoopInstallFixer.apply(layout, CoopInstallFixer.Target.VMPARAMS);
        byte[] bytes = Files.readAllBytes(layout.vmparams().toPath());
        assertEquals(PATCHED.length(), bytes.length);
        for (byte b : bytes) {
            assertTrue(b > 0 && b < 0x7f, "non-ASCII byte " + b + " in the written vmparams");
        }
    }

    /**
     * The rule that matters most on a second press: the backup is the file as it was before the
     * launcher ever touched it, and a later run must not replace it with an already-patched copy.
     */
    @Test
    void anExistingBackupIsNeverOverwritten() throws IOException {
        writeVmparams(STOCK);
        File backup = CoopInstallFixer.vmparamsBackup(layout.vmparams());
        Files.writeString(backup.toPath(), "the player's own backup");
        CoopInstallFixer.apply(layout, CoopInstallFixer.Target.VMPARAMS);
        assertEquals("the player's own backup",
                Files.readString(backup.toPath(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void applyingTheVmparamsFixTwiceChangesNothingTheSecondTime() throws IOException {
        writeVmparams(STOCK);
        CoopInstallFixer.apply(layout, CoopInstallFixer.Target.VMPARAMS);
        CoopInstallFixer.Result second = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.VMPARAMS);
        assertFalse(second.changed(), second.message());
        assertEquals(PATCHED, readVmparams());
    }

    /** A modded-JRE install runs the game from a {@code .bat}; its vmparams is not read at all. */
    @Test
    void anInstallWithNoBundledJreIsRefusedWithTheManualText() throws IOException {
        writeVmparams(STOCK);
        assertTrue(layout.javaw().delete());
        CoopInstallFixer.Result result = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.VMPARAMS);
        assertFalse(result.changed());
        assertFalse(result.accessDenied());
        assertTrue(result.message().contains(".bat"), result.message());
        assertTrue(result.message().contains("INSTALL.md"), result.message());
        assertEquals(STOCK, readVmparams());
    }

    @Test
    void aMissingVmparamsIsRefusedWithTheManualText() {
        CoopInstallFixer.Result result = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.VMPARAMS);
        assertFalse(result.changed());
        assertTrue(result.message().contains("INSTALL.md"), result.message());
    }

    @Test
    void applyingTheEnabledModsFixCreatesTheFileWhenThereIsNone() throws IOException {
        CoopInstallFixer.Result result = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.ENABLED_MODS);
        assertTrue(result.changed(), result.message());
        assertEquals("{\n  \"enabledMods\": [\n    \"coop\"\n  ]\n}\n",
                Files.readString(layout.enabledMods().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void applyingTheEnabledModsFixKeepsTheOtherMods() throws IOException {
        Files.createDirectories(layout.enabledMods().toPath().getParent());
        Files.writeString(layout.enabledMods().toPath(),
                "{\n  \"enabledMods\": [\n    \"lw_lazylib\"\n  ]\n}\n");
        CoopInstallFixer.Result result = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.ENABLED_MODS);
        assertTrue(result.changed(), result.message());
        assertEquals("{\n  \"enabledMods\": [\n    \"lw_lazylib\",\n    \"coop\"\n  ]\n}\n",
                Files.readString(layout.enabledMods().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void applyingTheEnabledModsFixTwiceChangesNothingTheSecondTime() throws IOException {
        CoopInstallFixer.apply(layout, CoopInstallFixer.Target.ENABLED_MODS);
        CoopInstallFixer.Result second = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.ENABLED_MODS);
        assertFalse(second.changed(), second.message());
    }

    @Test
    void anUnparseableEnabledModsIsRefusedRatherThanReplaced() throws IOException {
        Files.createDirectories(layout.enabledMods().toPath().getParent());
        Files.writeString(layout.enabledMods().toPath(), "{ this is not json");
        CoopInstallFixer.Result result = CoopInstallFixer.apply(layout,
                CoopInstallFixer.Target.ENABLED_MODS);
        assertFalse(result.changed());
        assertEquals("{ this is not json",
                Files.readString(layout.enabledMods().toPath(), StandardCharsets.UTF_8));
    }

    /** The rows that carry a Fix button are exactly the ones the fixer knows how to act on. */
    @Test
    void theClasspathAndEnabledModsRowsAreTheOnlyFixableOnes() {
        CoopInstallCheck.Inputs inputs = new CoopInstallCheck.Inputs(
                root.getPath(), true, true, true, true, true, STOCK, "{\"enabledMods\": []}",
                "0.1.0", "0.1.0", null, "0.98a-RC8", "0.98a-RC8", false,
                "commit-a", "0.1.0", "commit-a", true, "", "");
        int vmparams = 0;
        int enabledMods = 0;
        for (CoopInstallCheck.Row row : CoopInstallCheck.rows(inputs)) {
            if (row.fixable() == CoopInstallFixer.Target.VMPARAMS) {
                vmparams++;
            } else if (row.fixable() == CoopInstallFixer.Target.ENABLED_MODS) {
                enabledMods++;
            }
        }
        assertEquals(1, vmparams);
        assertEquals(1, enabledMods);
    }

    @Test
    void aPassingInstallOffersNoFixButtonAtAll() {
        CoopInstallCheck.Inputs inputs = new CoopInstallCheck.Inputs(
                root.getPath(), true, true, true, true, true, PATCHED, "{\"enabledMods\":[\"coop\"]}",
                "0.1.0", "0.1.0", null, "0.98a-RC8", "0.98a-RC8", false,
                "commit-a", "0.1.0", "commit-a", true, "", "");
        for (CoopInstallCheck.Row row : CoopInstallCheck.rows(inputs)) {
            assertNull(row.fixable(), row.toString());
        }
    }

    private void writeVmparams(String text) throws IOException {
        Files.createDirectories(root.toPath());
        Files.write(layout.vmparams().toPath(), text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private String readVmparams() throws IOException {
        return Files.readString(layout.vmparams().toPath(), StandardCharsets.ISO_8859_1);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
