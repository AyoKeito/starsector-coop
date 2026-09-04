package coop.launcher;

import coop.config.CoopOptionsRegistry;
import coop.util.CoopDebug;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher window itself needs a display, so what is covered here is the handful of decisions
 * that were pulled out of it: which results from a background task are still allowed to touch the
 * co-op port and the address field once they land back on the event dispatch thread.
 */
class CoopLauncherAppTest {

    // ---- the Advanced card's spinners ------------------------------------------------------------

    /**
     * The spinner's ceiling has to be the registry's, or the Advanced card offers a number the game
     * then quietly clamps - which is exactly what 65535 on the interaction-delay spinner did while
     * {@code CoopDebug} stopped at 60 s.
     */
    @Test
    void aSpinnerTakesItsCeilingFromTheRegistryNotFromThePortFallback() {
        assertEquals(CoopDebug.MAX_INTERACTION_DELAY_MILLIS,
                CoopLauncherApp.spinnerMax(CoopOptionsRegistry.DEBUG_INTERACTION_DELAY_MS));
        assertEquals(CoopOptionsRegistry.require(CoopOptionsRegistry.DEBUG_INTERACTION_DELAY_MS).max(),
                CoopLauncherApp.spinnerMax(CoopOptionsRegistry.DEBUG_INTERACTION_DELAY_MS));

        assertEquals(65535, CoopLauncherApp.spinnerMax(CoopOptionsRegistry.DEBUG_BRIDGE));
        assertEquals(3600, CoopLauncherApp.spinnerMax(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS));
    }

    /** 65535 survives only as the fallback for a key that is still deliberately unbounded. */
    @Test
    void anUnboundedKeyStillFallsBackToThePortCeiling() {
        assertEquals(Integer.MAX_VALUE,
                CoopOptionsRegistry.require(CoopOptionsRegistry.DEBUG_WIRETAP_SAMPLE).max());
        assertEquals(65535, CoopLauncherApp.spinnerMax(CoopOptionsRegistry.DEBUG_WIRETAP_SAMPLE));
        assertEquals(1, CoopLauncherApp.spinnerMin(CoopOptionsRegistry.DEBUG_WIRETAP_SAMPLE));
    }

    // ---- the connection check and the co-op port ------------------------------------------------

    @Test
    void aCheckThatIsStillTheCurrentOneMayOpenTheListener() {
        assertTrue(CoopLauncherApp.checkResultStillApplies(3, 3, false, true));
    }

    /**
     * The one that mattered: nothing disables LAUNCH while a check runs, and the check can take
     * twenty seconds. A listener bound after the game started either loses the game its bind or
     * answers the guest with a launcher banner on the co-op port.
     */
    @Test
    void aCheckThatFinishesAfterLaunchDoesNotOpenTheListener() {
        assertFalse(CoopLauncherApp.checkResultStillApplies(3, 3, true, true));
    }

    @Test
    void aCheckSupersededByANewerOneDoesNotOpenTheListener() {
        assertFalse(CoopLauncherApp.checkResultStillApplies(3, 4, false, true));
    }

    @Test
    void aCheckThatFinishesAfterASwitchToGuestDoesNotOpenAHostListener() {
        assertFalse(CoopLauncherApp.checkResultStillApplies(3, 3, false, false));
    }

    /**
     * The check maps the port and then releases the mapping. Mid-session that is the running game's
     * mapping, and deleting it stops the guest's traffic reaching the host.
     */
    @Test
    void aCheckIsRefusedWhileTheGameIsRunning() {
        String reason = CoopLauncherApp.connectionCheckBlockedReason(true);

        assertNotNull(reason);
        assertTrue(reason.contains("router mapping"), reason);
        assertNull(CoopLauncherApp.connectionCheckBlockedReason(false));
    }

    // ---- the public address lookup ---------------------------------------------------------------

    @Test
    void anAutomaticLookupFillsAFieldThePlayerLeftAlone() {
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(true, "", ""));
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(true, "  ", "   "));
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(true, "10.8.0.2", "10.8.0.2"));
    }

    /**
     * The lookup takes up to ten seconds on a slow network, which is long enough for the host to
     * type a LAN or VPN address and copy an invite from it. Overwriting it changed the invite after
     * it was sent.
     */
    @Test
    void anAutomaticLookupKeepsAnAddressTypedWhileItRan() {
        assertFalse(CoopLauncherApp.shouldApplyLookedUpAddress(true, "", "10.8.0.2"));
        assertFalse(CoopLauncherApp.shouldApplyLookedUpAddress(true, "10.8.0.2", "192.168.1.5"));
    }

    /** Pressing "Look up" is an explicit request to replace whatever is in the field. */
    @Test
    void anExplicitLookupOverwritesWhateverIsThere() {
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(false, "", "10.8.0.2"));
        assertTrue(CoopLauncherApp.shouldApplyLookedUpAddress(false, "10.8.0.2", "192.168.1.5"));
    }

    // ---- the elevated relaunch --------------------------------------------------------------------

    /**
     * The command is rebuilt out of the running JVM rather than hardcoded, so it follows whichever
     * install the player double-clicked.
     */
    @Test
    void theElevatedCommandIsBuiltFromTheRunningJvm() {
        List<String> command = CoopLauncherApp.elevatedRelaunchCommand(
                "C:\\Games\\Starsector\\jre", "C:\\Games\\Starsector\\mods\\coop\\jars\\a.jar",
                "C:\\Games\\Starsector");
        assertEquals(List.of("powershell", "-NoProfile", "-Command"), command.subList(0, 3));
        String script = command.get(3);
        assertTrue(script.startsWith("Start-Process -FilePath 'C:\\Games\\Starsector\\jre"
                + File.separator + "bin" + File.separator + "javaw.exe'"), script);
        assertTrue(script.contains("-WorkingDirectory 'C:\\Games\\Starsector'"), script);
        assertTrue(script.endsWith("-Verb RunAs -ErrorAction Stop"), script);
        assertTrue(script.contains("coop.launcher.CoopLauncherApp "
                + CoopLauncherApp.APPLY_FIX_FLAG), script);
    }

    /**
     * The quoting rule that had to be got right: an install under {@code Program Files} puts spaces
     * in both the classpath and the working directory. The double quotes around the classpath are
     * built PowerShell-side with {@code [char]34} so that no double quote is ever handed to
     * {@code ProcessBuilder}, which would wrap the whole {@code -Command} value in quotes of its own
     * and swallow them.
     */
    @Test
    void everyPathIsQuotedAndNoDoubleQuoteReachesProcessBuilder() {
        List<String> command = CoopLauncherApp.elevatedRelaunchCommand(
                "C:\\Program Files (x86)\\Fractal Softworks\\Starsector\\jre",
                "C:\\Program Files (x86)\\Fractal Softworks\\Starsector\\mods\\coop\\jars\\a.jar;"
                        + "C:\\Program Files (x86)\\Fractal Softworks\\Starsector\\starsector-core"
                        + "\\json.jar",
                "C:\\Program Files (x86)\\Fractal Softworks\\Starsector");
        String script = command.get(3);
        assertFalse(script.contains("\""), script);
        assertTrue(script.contains("-ArgumentList ('-cp ' + [char]34 + 'C:\\Program Files (x86)"
                + "\\Fractal Softworks\\Starsector\\mods\\coop\\jars\\a.jar;"), script);
        assertTrue(script.contains("json.jar' + [char]34 + ' coop.launcher.CoopLauncherApp "
                + CoopLauncherApp.APPLY_FIX_FLAG + "')"), script);
    }

    // ---- the one-shot adopt consent ---------------------------------------------------------------

    /**
     * The tick is written for one launch. Every way that launch can end has to take it back out,
     * including the way that has no game to exit: {@code CoopGameProcess.launch} throwing, which
     * used to return straight out of the launch method and leave the consent behind for the next
     * plain double-click of starsector.exe.
     */
    @Test
    void aLaunchThatNeverStartedStillClearsTheConsent(@TempDir Path temp) throws IOException {
        File file = temp.resolve("coop_options.json.data").toFile();
        CoopLauncherConfig.read(file).write(file, true, java.util.Map.of(
                CoopLauncherConfig.HOST_PORT, "7777",
                CoopLauncherConfig.ADOPT_CAMPAIGN_ID, "true"));

        assertTrue(CoopLauncherApp.clearAdoptConsent(file, "the launch failed to start"));

        assertFalse(CoopLauncherConfig.read(file).keys().contains(
                CoopLauncherConfig.ADOPT_CAMPAIGN_ID));
        assertEquals("7777", CoopLauncherConfig.read(file).value(CoopLauncherConfig.HOST_PORT));
    }

    /** Whatever went wrong, clearing the consent must not throw a second problem on top of it. */
    @Test
    void clearingAConsentThatIsNotThereIsQuiet(@TempDir Path temp) {
        assertFalse(CoopLauncherApp.clearAdoptConsent(
                temp.resolve("not-there.json.data").toFile(), "the launch failed to start"));
        assertFalse(CoopLauncherApp.clearAdoptConsent(null, "there is no install"));
    }

    // ---- what a finished bug report says on screen -------------------------------------------------

    /**
     * The notes are the warnings about what did NOT get scrubbed out of the archive. They were
     * written into report.txt inside the zip, which the player only reads after posting it.
     */
    @Test
    void everyBugReportNoteReachesTheStatusPane() {
        CoopBugReport.Result result = new CoopBugReport.Result(new File("C:\\zips\\coop.zip"),
                List.of("report.txt"), List.of(),
                List.of("saves/common/coop_options.json.data does not parse as JSON, so it was"
                        + " packed exactly as it is."), true);

        List<String> lines = CoopLauncherApp.bugReportStatusLines(result);

        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("Saved C:\\zips\\coop.zip"), lines.get(0));
        assertTrue(lines.get(1).contains("does not parse as JSON"), lines.get(1));
        assertTrue(lines.get(2).contains("still being written"), lines.get(2));
    }

    @Test
    void aCleanBugReportSaysOnlyThatItWasSaved() {
        CoopBugReport.Result result = new CoopBugReport.Result(new File("C:\\zips\\coop.zip"),
                List.of("report.txt"), List.of(), List.of(), false);

        assertEquals(1, CoopLauncherApp.bugReportStatusLines(result).size());
    }

    /** An apostrophe in a folder name is PowerShell's own escape, so it has to be doubled. */
    @Test
    void anApostropheInAPathIsDoubled() {
        List<String> command = CoopLauncherApp.elevatedRelaunchCommand(
                "D:\\Bob's Games\\Starsector\\jre", "D:\\Bob's Games\\Starsector\\a.jar",
                "D:\\Bob's Games\\Starsector");
        String script = command.get(3);
        assertTrue(script.contains("-WorkingDirectory 'D:\\Bob''s Games\\Starsector'"), script);
        assertFalse(script.contains("Bob's"), script);
    }
}
