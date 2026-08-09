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
    void runtimeHandshakeCaptureDoesNotCallSettingsFileLoaders() throws IOException {
        String source = Files.readString(
                PROJECT_ROOT.resolve("src/main/java/coop/handshake/CoopHandshakeManifest.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("loadText("),
                "SettingsAPI file loaders are not proven safe for binary jar checksums in campaign scripts");
        assertFalse(source.contains("openStream("),
                "Open stream calls trip Starsector's script sandbox");
    }

    @Test
    void theOnlySettingsLoaderCallSitsInTheDiagnosticProbeAndIsGated() throws IOException {
        // Phase 12b spike: whether SettingsAPI.loadText survives the script sandbox can only be
        // answered in-game, so the call lives in CoopChecksumProbe behind CoopDebug rather than on
        // the handshake path. This test pins that arrangement: if someone later promotes the call
        // into the manifest, the test above fails and they have to justify it with a session log.
        String probe = Files.readString(
                PROJECT_ROOT.resolve("src/main/java/coop/handshake/CoopChecksumProbe.java"),
                StandardCharsets.UTF_8);

        assertTrue(probe.contains("loadText("), "the probe is the one sanctioned loadText call site");
        assertTrue(probe.contains("CoopDebug.diagnosticsEnabled()"),
                "an unproven sandbox call must never run in a normal session");
        assertTrue(probe.contains("catch (Throwable"),
                "a sandbox rejection can surface as an Error, so the probe must catch Throwable");
    }
}
