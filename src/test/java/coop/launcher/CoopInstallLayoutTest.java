package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopInstallLayoutTest {

    private static File install(Path root) throws IOException {
        Files.createDirectories(root.resolve("starsector-core"));
        Files.writeString(root.resolve("starsector.exe"), "");
        Files.writeString(root.resolve("vmparams"), "");
        return root.toFile();
    }

    @Test
    void everyPathHangsOffTheInstallRoot(@TempDir Path temp) throws IOException {
        File root = install(temp);
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(root);

        assertEquals(new File(root, "starsector.exe"), layout.starsectorExe());
        assertEquals(new File(root, "jre\\bin\\javaw.exe".replace('\\', File.separatorChar)),
                layout.javaw());
        assertEquals(new File(root, "starsector-core"), layout.starsectorCore());
        assertEquals(new File(root, "vmparams"), layout.vmparams());
        assertEquals(path(root, "mods", "enabled_mods.json"), layout.enabledMods());
        assertEquals(path(root, "mods", "coop"), layout.modRoot());
        assertEquals(path(root, "mods", "coop", "mod_info.json"), layout.modInfo());
        assertEquals(path(root, "mods", "coop", "jars", "coop.jar"), layout.coopJar());
        assertEquals(path(root, "mods", "coop", "jars", "coop-forks.jar"), layout.forksJar());
        assertEquals(path(root, "saves", "common", "coop_options.json.data"), layout.coopOptions());
        assertEquals(path(root, "starsector-core", "starsector.log"), layout.starsectorLog());
        assertEquals(path(root, "mods", "coop", "coop-launcher.log"), layout.launcherLog());
        assertEquals(path(root, "mods", "coop", "docs", "player", "INSTALL.md"),
                layout.installDoc());
    }

    /** The settings file the game reads carries the engine's own {@code .data} suffix. */
    @Test
    void theSettingsFileNameMatchesWhatTheStoreDocuments(@TempDir Path temp) throws IOException {
        CoopInstallLayout layout = CoopInstallLayout.ofInstallRoot(install(temp));

        assertTrue(layout.coopOptions().getPath()
                .replace(File.separatorChar, '/')
                .endsWith(coop.config.CoopOptionsStore.COMMON_PATH));
    }

    @Test
    void aFolderMissingAnyOfTheThreeMarkersIsNotAnInstall(@TempDir Path temp) throws IOException {
        assertFalse(CoopInstallLayout.looksLikeInstall(temp.toFile()));
        assertFalse(CoopInstallLayout.looksLikeInstall(null));

        Files.writeString(temp.resolve("starsector.exe"), "");
        assertFalse(CoopInstallLayout.looksLikeInstall(temp.toFile()));
        Files.writeString(temp.resolve("vmparams"), "");
        assertFalse(CoopInstallLayout.looksLikeInstall(temp.toFile()));
        Files.createDirectories(temp.resolve("starsector-core"));
        assertTrue(CoopInstallLayout.looksLikeInstall(temp.toFile()));
    }

    /** A mod folder that is not called "coop" still resolves, because the jar's own path decides. */
    @Test
    void aRenamedModFolderStillResolvesFromTheJarLocation(@TempDir Path temp) throws IOException {
        File root = install(temp);
        File mod = path(root, "mods", "starsector-coop-1.0");
        CoopInstallLayout layout = CoopInstallLayout.of(root, mod);

        assertEquals(root, layout.installRoot());
        assertEquals(new File(mod, "mod_info.json"), layout.modInfo());
        // The enabled-mods row is the one place the name "coop" is load-bearing.
        assertEquals("coop", CoopInstallLayout.MOD_ID);
    }

    private static File path(File root, String... parts) {
        File file = root;
        for (String part : parts) {
            file = new File(file, part);
        }
        return file;
    }
}
