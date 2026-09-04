package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug report against a fake install tree. Nothing here touches a real Starsector folder, a real
 * desktop or the clock: the output folder and the clock are both injected.
 */
class CoopBugReportTest {

    /** A fixed instant so the file name and the save-in-flight window are both predictable. */
    private static final long NOW = 1_756_900_000_000L;

    private static final String DOCTOR_HEADER =
            "33291 [Thread-2] INFO  coop.net.CoopNetPump  - Coop connection doctor:";
    private static final String DOCTOR_BODY_1 =
            "  role              host, listening on port 7777 (TCP+UDP)";
    private static final String DOCTOR_BODY_2 =
            "  share with guest  91.77.160.252:7777";
    private static final String DOCTOR_MARKER =
            "183534 [Thread-2] WARN  coop.ui.CoopDoctorMarker  - [COOP-DOCTOR] code=COOP-SESSION"
                    + " role=HOST cause=GRACE_EXPIRED retryable=false";
    private static final String ORDINARY_LINE =
            "12 [Thread-4] INFO  com.fs.starfarer.campaign.Q  - loading something";

    @TempDir
    Path temp;

    private File root;
    private File modRoot;
    private File out;
    private CoopInstallLayout layout;

    @BeforeEach
    void buildTree() throws IOException {
        root = temp.resolve("install").toFile();
        modRoot = new File(new File(root, "mods"), "coop");
        out = temp.resolve("out").toFile();
        assertTrue(out.mkdirs());
        layout = CoopInstallLayout.of(root, modRoot);

        write(layout.starsectorLog(), String.join("\n",
                ORDINARY_LINE, DOCTOR_HEADER, DOCTOR_BODY_1, DOCTOR_BODY_2, ORDINARY_LINE,
                DOCTOR_MARKER));
        write(CoopBugReport.rolledLog(layout), ORDINARY_LINE);
        write(layout.launcherLog(), "12:04:31.220 INFO  CoopLauncherApp - Launch pressed");
        write(layout.modInfo(), "{\"id\":\"coop\",\"version\":\"0.1.0\"}");
        write(layout.enabledMods(), "{\"enabledMods\":[\"coop\"]}");
        write(layout.coopOptions(),
                "{\"coop.password\":\"hunter2\",\"coop.hostPort\":7777}");
        write(layout.vmparams(),
                "java.exe -Xms8192m -Dcoop.password=hunter2 -Dcoop.role=host"
                        + " -classpath ..\\mods\\coop\\jars\\coop-forks.jar;janino.jar"
                        + " com.fs.starfarer.StarfarerLauncher");
    }

    // ---- the archive ---------------------------------------------------------------------------

    @Test
    void theArchiveHoldsEveryFileUnderItsInstallRelativeName() throws IOException {
        quietSave("save_1_777");

        CoopBugReport.Result result = write(true);

        assertTrue(result.zip().isFile(), "the zip was not written");
        assertEquals("coop-report-host-" + stamp() + ".zip", result.zip().getName());
        List<String> entries = entries(result.zip());
        assertTrue(entries.contains("report.txt"), entries.toString());
        assertTrue(entries.contains("starsector-core/starsector.log"), entries.toString());
        assertTrue(entries.contains("starsector-core/starsector.log.1"), entries.toString());
        assertTrue(entries.contains("mods/coop/coop-launcher.log"), entries.toString());
        assertTrue(entries.contains("mods/coop/mod_info.json"), entries.toString());
        assertTrue(entries.contains("mods/enabled_mods.json"), entries.toString());
        assertTrue(entries.contains("saves/common/coop_options.json.data"), entries.toString());
        assertTrue(entries.contains("vmparams"), entries.toString());
        assertTrue(entries.contains("saves/save_1_777/descriptor.xml"), entries.toString());
        assertTrue(entries.contains("saves/save_1_777/campaign.xml"), entries.toString());
        assertFalse(entries.contains("saves/save_1_777/campaign.xml.bak"),
                "the previous-save .bak copies double the archive and are left out: " + entries);
        assertEquals(entries, result.entries(), "the result has to list what is actually in the zip");
    }

    @Test
    void aMissingFileIsListedInTheReportInsteadOfFailingTheRun() throws IOException {
        assertTrue(CoopBugReport.rolledLog(layout).delete());
        assertTrue(layout.enabledMods().delete());

        CoopBugReport.Result result = write(false);

        assertTrue(result.missing().contains("starsector-core/starsector.log.1"),
                result.missing().toString());
        assertTrue(result.missing().contains("mods/enabled_mods.json"), result.missing().toString());
        assertFalse(entries(result.zip()).contains("mods/enabled_mods.json"));
        String report = entryText(result.zip(), "report.txt");
        assertTrue(report.contains("Not packed, not on this install"), report);
        assertTrue(report.contains("mods/enabled_mods.json"), report);
    }

    @Test
    void theGuestRoleGoesInTheFileNameAndTheReport() throws IOException {
        CoopBugReport.Result result = CoopBugReport.write(layout, "Guest", false, out, () -> NOW);

        assertEquals("coop-report-guest-" + stamp() + ".zip", result.zip().getName());
        assertTrue(roleLine(entryText(result.zip(), "report.txt")).endsWith("guest"));
    }

    // ---- scrubbing ----------------------------------------------------------------------------

    @Test
    void thePasswordIsBlankedInThePackedSettingsFile() throws IOException {
        CoopBugReport.Result result = write(false);

        String packed = entryText(result.zip(), "saves/common/coop_options.json.data");
        assertFalse(packed.contains("hunter2"), packed);
        assertTrue(packed.contains("\"coop.password\""), packed);
        assertTrue(packed.contains("7777"), "the other settings have to survive: " + packed);
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("coop.password was blanked")),
                result.notes().toString());
        assertTrue(entryText(result.zip(), "report.txt").contains("coop.password was blanked"));
    }

    /**
     * The leak this file used to ship. A settings file that will not parse cannot be blanked, and
     * the password in it is stored in plain text, so packing it verbatim under a note put the
     * password in an archive the player is about to post in public. It is left out instead.
     */
    @Test
    void aSettingsFileThatDoesNotParseIsLeftOutOfTheArchiveEntirely() throws IOException {
        write(layout.coopOptions(), "# a hand-edited file with a comment\ncoop.password=hunter2");

        CoopBugReport.Result result = write(false);

        assertFalse(entries(result.zip()).contains("saves/common/coop_options.json.data"),
                entries(result.zip()).toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("was LEFT OUT")
                        && note.contains("does not parse as JSON")),
                result.notes().toString());
        String report = entryText(result.zip(), "report.txt");
        assertTrue(report.contains("was LEFT OUT"), report);
        assertFalse(report.contains("hunter2"), "the report must not quote the file back: " + report);
    }

    /** A settings file with no password key at all has nothing to hide and is packed as it is. */
    @Test
    void aSettingsFileWithNoPasswordIsPackedUntouched() throws IOException {
        write(layout.coopOptions(), "{\"coop.hostPort\":7777}");

        CoopBugReport.Result result = write(false);

        assertEquals("{\"coop.hostPort\":7777}",
                entryText(result.zip(), "saves/common/coop_options.json.data"));
        assertTrue(result.notes().stream().noneMatch(note -> note.contains("coop_options")),
                result.notes().toString());
    }

    /**
     * A password with a space in it is quoted in {@code vmparams}, and the old whitespace-delimited
     * scrub stopped at the space: {@code words"} stayed on the line, which is the second half of
     * the password sitting in an archive under a note claiming it had been removed.
     */
    @Test
    void aQuotedPasswordWithSpacesIsRemovedWholeInBothQuoteStyles() throws IOException {
        write(layout.coopOptions(), "{\"coop.password\":\"two words\",\"coop.hostPort\":7777}");
        write(layout.vmparams(),
                "java.exe -Xms8192m -Dcoop.password=\"two words\" -Dcoop.role=host"
                        + " -classpath ..\\mods\\coop\\jars\\coop-forks.jar;janino.jar"
                        + " com.fs.starfarer.StarfarerLauncher");

        CoopBugReport.Result result = write(false);

        String packed = entryText(result.zip(), "vmparams");
        assertFalse(packed.contains("two words"), packed);
        assertFalse(packed.contains("words"), "not even the tail of the value: " + packed);
        assertFalse(packed.contains("coop.password"), packed);
        assertTrue(packed.contains("-Dcoop.role=host"), packed);

        CoopBugReport.VmparamsScrub single = CoopBugReport.scrubVmparams(
                "a -Dcoop.password='two words' b");
        assertEquals(1, single.removed());
        assertEquals("a b", single.text());
    }

    /**
     * The cross-check. The same secret pasted into some other property is not a token this class
     * knows how to remove, so the file is left out rather than packed with the password in it.
     */
    @Test
    void aVmparamsHoldingThePasswordUnderAnotherNameIsLeftOut() throws IOException {
        write(layout.vmparams(),
                "java.exe -Xms8192m -Dcoop.password=hunter2 -Dcoop.playerName=hunter2"
                        + " -classpath janino.jar com.fs.starfarer.StarfarerLauncher");

        CoopBugReport.Result result = write(false);

        assertFalse(entries(result.zip()).contains("vmparams"), entries(result.zip()).toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("vmparams was LEFT OUT")
                        && note.contains("still appears in it")),
                result.notes().toString());
        assertFalse(entryText(result.zip(), "report.txt").contains("hunter2"));
    }

    /** A spelling the scrub does not know leaves the key behind, and that is enough to omit. */
    @Test
    void aVmparamsStillNamingCoopPasswordAfterTheScrubIsLeftOut() throws IOException {
        write(layout.coopOptions(), "{\"coop.hostPort\":7777}");
        write(layout.vmparams(),
                "java.exe -Dcoop.passwordFile=C:\\secrets\\pw.txt"
                        + " -classpath janino.jar com.fs.starfarer.StarfarerLauncher");

        CoopBugReport.Result result = write(false);

        assertFalse(entries(result.zip()).contains("vmparams"), entries(result.zip()).toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("vmparams was LEFT OUT")),
                result.notes().toString());
    }

    /**
     * Packing a blanked settings file is not "the rewrite did not throw": the copy is re-parsed and
     * asserted to hold no password. Nothing outside this class can produce a rewrite that fails
     * that, which is the point - the guard is what keeps a future one from being packed in silence.
     */
    @Test
    void aBlankedCopyIsOnlyAcceptedWhenItProvablyHasNoPasswordLeft() {
        assertTrue(CoopBugReport.passwordIsBlanked("{\"coop.password\":\"\"}"));
        assertTrue(CoopBugReport.passwordIsBlanked("{\"coop.hostPort\":7777}"));
        assertFalse(CoopBugReport.passwordIsBlanked("{\"coop.password\":\"hunter2\"}"));
        assertFalse(CoopBugReport.passwordIsBlanked("not json at all"));
        assertFalse(CoopBugReport.passwordIsBlanked(null));
    }

    @Test
    void theLeakCheckAnswersWithTheReasonAndPassesACleanLine() {
        assertNull(CoopBugReport.vmparamsLeak("java.exe -Xms8192m -classpath janino.jar", "hunter2"));
        assertNotNull(CoopBugReport.vmparamsLeak("java.exe -Dcoop.password=x", ""));
        assertNotNull(CoopBugReport.vmparamsLeak("java.exe -Dcoop.playerName=hunter2", "hunter2"));
        assertNull(CoopBugReport.vmparamsLeak("java.exe -Dcoop.playerName=hunter2", ""));
    }

    @Test
    void aParseErrorIsReportedForTheNoteAndNullForGoodJson() {
        assertNull(CoopBugReport.jsonParseError("{\"coop.hostPort\":7777}"));
        assertNotNull(CoopBugReport.jsonParseError("# not json"));
        assertNotNull(CoopBugReport.jsonParseError(null));
    }

    /**
     * A settings file that is there but cannot be read is not a malformed one: packing it produces
     * an empty entry under a note telling the player to check it for a password by hand.
     */
    @Test
    void aSettingsFileThatCannotBeReadIsListedAsMissingRatherThanPackedEmpty() throws IOException {
        // Bytes that are not valid UTF-8, which is what an unreadable file looks like from here.
        Files.write(layout.coopOptions().toPath(), new byte[]{'{', (byte) 0xFF, (byte) 0xFE, '}'});

        CoopBugReport.Result result = write(false);

        assertTrue(result.missing().stream()
                        .anyMatch(name -> name.contains("coop_options.json.data")
                                && name.contains("unreadable")),
                result.missing().toString());
        assertFalse(result.notes().stream().anyMatch(note -> note.contains("packed exactly as it is")),
                result.notes().toString());
        assertFalse(entries(result.zip()).contains("saves/common/coop_options.json.data"),
                entries(result.zip()).toString());
    }

    @Test
    void theVmparamsPasswordTokenIsRemovedAndTheRestOfTheLineSurvives() throws IOException {
        CoopBugReport.Result result = write(false);

        String packed = entryText(result.zip(), "vmparams");
        assertFalse(packed.contains("hunter2"), packed);
        assertFalse(packed.contains("-Dcoop.password"), packed);
        assertTrue(packed.contains("-Dcoop.role=host"), "only the password token goes: " + packed);
        assertTrue(packed.contains("-Xms8192m"), packed);
        assertTrue(packed.contains(" -classpath ..\\mods\\coop\\jars\\coop-forks.jar;"), packed);
        assertFalse(packed.contains("  "), "no double space left behind: " + packed);
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("-Dcoop.password token")),
                result.notes().toString());
    }

    @Test
    void aCleanVmparamsIsPackedUntouchedAndNotComplainedAbout() throws IOException {
        String clean = "java.exe -Xms8192m -classpath janino.jar com.fs.starfarer.StarfarerLauncher";
        write(layout.vmparams(), clean);

        CoopBugReport.Result result = write(false);

        assertEquals(clean, entryText(result.zip(), "vmparams"));
        assertTrue(result.notes().stream().noneMatch(note -> note.contains("vmparams")),
                result.notes().toString());
    }

    @Test
    void theVmparamsScrubIsWhitespaceDelimited() {
        CoopBugReport.VmparamsScrub scrub = CoopBugReport.scrubVmparams(
                "a -Dcoop.password=one b -Dcoop.passwordOther=keep c -Dcoop.password= d");

        assertEquals(2, scrub.removed());
        assertEquals("a b -Dcoop.passwordOther=keep c d", scrub.text());
    }

    @Test
    void blankingLeavesAFileWithNoPasswordAlone() {
        assertNull(CoopBugReport.blankPassword("{\"coop.hostPort\":7777}"));
        assertNull(CoopBugReport.blankPassword("not json at all"));
        assertNotNull(CoopBugReport.blankPassword("{\"coop.password\":\"x\"}"));
    }

    /**
     * Blanking answers {@code null} both when there was no password and when the rewrite failed.
     * Only one of those is safe to pack in silence, and the password is stored in plain text, so
     * what the file actually holds is read separately.
     */
    @Test
    void aFileWithNothingToBlankIsToldApartFromOneThatHasAPassword() {
        assertEquals("hunter2", CoopBugReport.passwordIn("{\"coop.password\":\"hunter2\"}"));
        assertEquals("", CoopBugReport.passwordIn("{\"coop.password\":\"\"}"));
        assertEquals("", CoopBugReport.passwordIn("{\"coop.hostPort\":7777}"));
        assertEquals("", CoopBugReport.passwordIn("not json at all"));
        assertEquals("", CoopBugReport.passwordIn(null));
    }

    // ---- the save guard -------------------------------------------------------------------------

    @Test
    void aSaveThatIsStillBeingWrittenIsSkippedAndSaidSo() throws IOException {
        File save = quietSave("save_1_777");
        // Two seconds ago is inside the five second window, so the engine may still be writing.
        assertTrue(new File(save, "campaign.xml").setLastModified(NOW - 2000L));

        CoopBugReport.Result result = write(true);

        assertTrue(result.saveInFlight());
        assertFalse(entries(result.zip()).stream().anyMatch(name -> name.startsWith("saves/save_")));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("still being written")),
                result.notes().toString());
        assertTrue(entryText(result.zip(), "report.txt").contains("still being written"));
    }

    @Test
    void theNewestSaveIsTheOneThatGetsPacked() throws IOException {
        File older = quietSave("save_1_111");
        File newer = quietSave("save_1_222");
        assertTrue(older.setLastModified(NOW - 600_000L));
        assertTrue(newer.setLastModified(NOW - 60_000L));

        CoopBugReport.Result result = write(true);

        List<String> entries = entries(result.zip());
        assertTrue(entries.contains("saves/save_1_222/campaign.xml"), entries.toString());
        assertFalse(entries.contains("saves/save_1_111/campaign.xml"), entries.toString());
    }

    @Test
    void anUntickedSaveIsLeftOutWithoutBeingCalledMissing() throws IOException {
        quietSave("save_1_777");

        CoopBugReport.Result result = write(false);

        assertFalse(result.saveInFlight());
        assertFalse(entries(result.zip()).stream().anyMatch(name -> name.startsWith("saves/save_")));
        assertTrue(result.missing().stream().noneMatch(name -> name.startsWith("saves/save_")),
                result.missing().toString());
    }

    @Test
    void aTreeWithNoSaveAtAllStillProducesAReport() throws IOException {
        CoopBugReport.Result result = write(true);

        assertTrue(result.zip().isFile());
        assertTrue(result.missing().stream().anyMatch(name -> name.contains("saves/save_*")),
                result.missing().toString());
    }

    // ---- the doctor lines -------------------------------------------------------------------

    @Test
    void theLastDoctorLineAndBlockAreLiftedOutOfTheCurrentLog() throws IOException {
        CoopBugReport.Result result = write(false);

        String report = entryText(result.zip(), "report.txt");
        assertTrue(report.contains("[COOP-DOCTOR] code=COOP-SESSION"), report);
        assertTrue(report.contains("share with guest  91.77.160.252:7777"), report);
        assertTrue(report.contains("(from starsector.log)"), report);
        assertFalse(report.contains("loading something"),
                "the block has to end at the next ordinary log line: " + report);
    }

    @Test
    void theLastOfSeveralDoctorBlocksWins() {
        CoopBugReport.Scan scan = new CoopBugReport.Scan();
        scan.accept(DOCTOR_HEADER);
        scan.accept("  share with guest  1.1.1.1:7777");
        scan.accept(ORDINARY_LINE);
        scan.accept(DOCTOR_HEADER);
        scan.accept("  share with guest  2.2.2.2:7777");
        scan.finish();

        assertEquals(List.of(DOCTOR_HEADER, "  share with guest  2.2.2.2:7777"), scan.lastBlock());
    }

    @Test
    void theRolledLogIsScannedForWhateverTheCurrentOneDoesNotHave() throws IOException {
        write(layout.starsectorLog(), ORDINARY_LINE);
        write(CoopBugReport.rolledLog(layout), String.join("\n",
                DOCTOR_HEADER, DOCTOR_BODY_1, "", DOCTOR_MARKER));

        CoopBugReport.Result result = write(false);

        String report = entryText(result.zip(), "report.txt");
        assertTrue(report.contains("[COOP-DOCTOR] code=COOP-SESSION"), report);
        assertTrue(report.contains("role              host, listening on port 7777"), report);
        assertTrue(report.contains("(from starsector.log.1)"), report);
    }

    @Test
    void noDoctorLinesAnywhereIsSaidPlainly() throws IOException {
        write(layout.starsectorLog(), ORDINARY_LINE);
        write(CoopBugReport.rolledLog(layout), ORDINARY_LINE);

        String report = entryText(write(false).zip(), "report.txt");

        assertTrue(report.contains("not found in starsector.log or starsector.log.1"), report);
    }

    // ---- report header ---------------------------------------------------------------------

    @Test
    void theReportNamesTheInstallAndTheRuntime() throws IOException {
        String report = entryText(write(false).zip(), "report.txt");

        assertTrue(report.contains("Install root"), report);
        assertTrue(report.contains(root.getPath()), report);
        assertTrue(report.contains("Launcher version"), report);
        assertTrue(report.contains("Windows"), report);
        assertTrue(report.contains("Java"), report);
        assertTrue(report.contains("Mod version"), report);
        assertTrue(roleLine(report).endsWith("host"), report);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private CoopBugReport.Result write(boolean includeSave) throws IOException {
        return CoopBugReport.write(layout, "host", includeSave, out, () -> NOW);
    }

    /** A save folder whose two marker files are well outside the in-flight window. */
    private File quietSave(String name) throws IOException {
        File save = new File(new File(root, "saves"), name);
        for (String marker : CoopBugReport.SAVE_MARKER_FILES) {
            File file = new File(save, marker);
            write(file, "<xml/>");
            assertTrue(file.setLastModified(NOW - 60_000L));
            File backup = new File(save, marker + ".bak");
            write(backup, "<xml/>");
            assertTrue(backup.setLastModified(NOW - 60_000L));
        }
        return save;
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
    }

    /** The single {@code Role ...} line out of report.txt, trimmed of its padding. */
    private static String roleLine(String report) {
        for (String line : report.split("\r\n")) {
            if (line.startsWith("Role ")) {
                return line.trim();
            }
        }
        return "";
    }

    private static String stamp() {
        return java.time.LocalDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(NOW), java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static List<String> entries(File zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip)) {
            for (java.util.Enumeration<? extends ZipEntry> it = file.entries();
                 it.hasMoreElements(); ) {
                names.add(it.nextElement().getName());
            }
        }
        return names;
    }

    private static String entryText(File zip, String entry) throws IOException {
        try (ZipFile file = new ZipFile(zip)) {
            ZipEntry found = file.getEntry(entry);
            assertNotNull(found, "no entry " + entry + " in " + zip);
            try (InputStream stream = file.getInputStream(found)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
