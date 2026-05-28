package coop.handshake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopHandshakeManifestTest {
    @TempDir
    Path tempDir;

    @Test
    void matchingManifestsHaveNoDiff() {
        CoopHandshakeManifest manifest = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123"));

        CoopHandshakeDiff diff = CoopHandshakeDiff.compare(manifest, manifest);

        assertTrue(diff.isEmpty());
        assertEquals("", diff.toDisplayString());
    }

    @Test
    void gameVersionMismatchNamesExactField() {
        CoopHandshakeManifest host = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123"));
        CoopHandshakeManifest guest = manifestWithMod("0.97a", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123"));

        CoopHandshakeDiff diff = CoopHandshakeDiff.compare(host, guest);

        assertEquals(List.of("gameVersion: host=0.98a-RC8 guest=0.97a"), diff.lines());
    }

    @Test
    void missingModIsReportedById() {
        CoopHandshakeManifest host = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123"));
        CoopHandshakeManifest guest = new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of());

        CoopHandshakeDiff diff = CoopHandshakeDiff.compare(host, guest);

        assertEquals(List.of("mod utility: missing on guest"), diff.lines());
    }

    @Test
    void modVersionMismatchNamesVersionField() {
        CoopHandshakeManifest host = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123"));
        CoopHandshakeManifest guest = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.1.0",
                Map.of("mod_info.json", "abc123"));

        CoopHandshakeDiff diff = CoopHandshakeDiff.compare(host, guest);

        assertEquals(List.of("mod utility.version: host=1.0.0 guest=1.1.0"), diff.lines());
    }

    @Test
    void checksumMismatchNamesFile() {
        CoopHandshakeManifest host = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123"));
        CoopHandshakeManifest guest = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "def456"));

        CoopHandshakeDiff diff = CoopHandshakeDiff.compare(host, guest);

        assertEquals(List.of("mod utility.checksum mod_info.json: host=abc123 guest=def456"), diff.lines());
    }

    @Test
    void manifestJsonRoundTripsDeterministically() {
        CoopHandshakeManifest manifest = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123", "jars/utility.jar", "def456"));

        CoopHandshakeManifest decoded = CoopHandshakeManifest.fromJson(manifest.toJson());

        assertEquals(manifest, decoded);
        assertEquals(manifest.toJson(), decoded.toJson());
    }

    @Test
    void checksumUsesSha256HexForExistingFile() throws Exception {
        Path file = tempDir.resolve("mod_info.json");
        Files.writeString(file, "{\"id\":\"utility\"}");

        String checksum = CoopChecksum.sha256(file);

        assertEquals("18ccf9f498f9f2f65048fcf529457bf7a3df1e4bd56f0f18f9f092108bf49470", checksum);
    }

    @Test
    void missingChecksumIsStableSentinel() {
        assertEquals("MISSING", CoopChecksum.sha256IfExists(tempDir.resolve("missing.jar")));
    }

    private static CoopHandshakeManifest manifestWithMod(String gameVersion, String modId, String modName,
                                                         String modVersion, Map<String, String> checksums) {
        CoopHandshakeManifest.ModEntry mod = new CoopHandshakeManifest.ModEntry(
                modId,
                modName,
                modVersion,
                "0.98a-RC8",
                "mods/" + modId,
                List.of("jars/" + modId + ".jar"),
                checksums);
        return new CoopHandshakeManifest(gameVersion, "0.1.0", "commit-a", List.of(mod));
    }
}
