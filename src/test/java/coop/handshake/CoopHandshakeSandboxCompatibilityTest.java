package coop.handshake;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopHandshakeSandboxCompatibilityTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void runtimeHandshakeCaptureDoesNotUseFileOrReflectionApisBlockedByStarsector() throws IOException {
        Path sourceRoot = PROJECT_ROOT.resolve("src/main/java/coop/handshake");
        List<Path> sourceFiles;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sourceFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        List<String> offenders = new ArrayList<>();
        for (Path path : sourceFiles) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("java.io.")
                    || source.contains("java.nio.file")
                    || source.contains("java.net.URL")
                    || source.contains("openStream")
                    || source.contains("getProtectionDomain")) {
                offenders.add(PROJECT_ROOT.relativize(path).toString());
            }
        }

        assertEquals(List.of(), offenders,
                "Starsector scripts block file access and reflection while capturing the runtime handshake");
    }

    @Test
    void settingsTextLoaderIsWrappedSoOneUnreadableModCannotFailCapture() throws IOException {
        // Phase 6b: the 12b CoopChecksumProbe drill run (2026-08-17) logged SUCCESS on both clients,
        // so SettingsAPI.loadText is proven in-game and the real mod_info.json hash is wired into
        // the manifest; the probe itself was deleted. This test pins the safety conditions the
        // wiring must keep: the call catches Throwable (a sandbox rejection surfaces as an Error,
        // and naming the loader's checked exception type would make the verifier resolve a blocked
        // i/o class), and a per-mod failure degrades to a placeholder instead of throwing out of
        // capture().
        String source = Files.readString(
                PROJECT_ROOT.resolve("src/main/java/coop/handshake/CoopHandshakeManifest.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("loadText("),
                "the manifest is the sanctioned loadText call site since the 6b promotion");
        assertTrue(source.contains("catch (Throwable"),
                "the loadText call must catch Throwable, never a named checked exception");
        assertFalse(source.contains("IOException"),
                "house rule: no named java.io type in a sandboxed class, even one the loader's"
                        + " allow-list happens to pass");
        assertFalse(source.contains("openStream("),
                "Open stream calls trip Starsector's script sandbox");
    }
}
