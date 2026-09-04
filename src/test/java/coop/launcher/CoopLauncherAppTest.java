package coop.launcher;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

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
