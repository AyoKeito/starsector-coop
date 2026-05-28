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
}
