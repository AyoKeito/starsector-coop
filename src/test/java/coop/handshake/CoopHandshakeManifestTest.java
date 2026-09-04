package coop.handshake;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopHandshakeManifestTest {
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
    void installLocalModPathPrefixesNormalizeToSameLogicalPath() {
        CoopHandshakeManifest host = manifestWithPath(
                "K:/Starsector-coop-test/host/starsector-core/../mods/coop");
        CoopHandshakeManifest guest = manifestWithPath(
                "K:/Starsector-coop-test/guest/starsector-core/../mods/coop");

        CoopHandshakeDiff diff = CoopHandshakeDiff.compare(host, guest);

        assertTrue(diff.isEmpty());
        assertEquals("mods/coop", host.enabledMods().get(0).path());
        assertEquals("mods/coop", guest.enabledMods().get(0).path());
    }

    @Test
    void manifestJsonRoundTripsDeterministically() {
        CoopHandshakeManifest manifest = manifestWithMod("0.98a-RC8", "utility", "Utility Mod", "1.0.0",
                Map.of("mod_info.json", "abc123", "jars/utility.jar", "def456"));

        CoopHandshakeManifest decoded = CoopHandshakeManifest.fromJson(manifest.toJson());

        assertEquals(manifest, decoded);
        assertEquals(manifest.toJson(), decoded.toJson());
    }

    // ---- the second jar --------------------------------------------------------------------------

    /**
     * coop-forks.jar is the other half of the same build, loaded by the system classloader. Two
     * players can hold the same coop.jar commit and still run different forked engine classes, and
     * nothing else on the wire looks at that jar.
     */
    @Test
    void aDifferentForksBuildIsItsOwnDiffLine() {
        CoopHandshakeManifest host = manifestWithForksBuild("0.1.0/commit-a");
        CoopHandshakeManifest guest = manifestWithForksBuild("0.1.0/commit-b");

        assertEquals(List.of("coopForksBuild: host=0.1.0/commit-a guest=0.1.0/commit-b"),
                CoopHandshakeDiff.compare(host, guest).lines());
    }

    @Test
    void aMissingForksJarSaysAbsentRatherThanMatchingAnything() {
        assertEquals(CoopHandshakeManifest.FORKS_BUILD_ABSENT,
                manifestWithForksBuild("   ").coopForksBuild());
        assertEquals(CoopHandshakeManifest.FORKS_BUILD_ABSENT,
                manifestWithForksBuild(null).coopForksBuild());
        assertFalse(CoopHandshakeDiff.compare(manifestWithForksBuild("0.1.0/commit-a"),
                manifestWithForksBuild(null)).isEmpty());
    }

    /**
     * A peer built before the field existed sends a manifest without the key. That has to read as
     * "the other side did not say", not as a parse failure - a handshake that throws tells the
     * player nothing about which build they are on.
     */
    @Test
    void aManifestFromBeforeTheFieldExistedStillParsesAndShowsUpInTheDiff() {
        CoopHandshakeManifest current = manifestWithForksBuild("0.1.0/commit-a");
        String olderJson = current.toJson()
                .replace(",\"coopForksBuild\":\"0.1.0/commit-a\"", "");
        assertFalse(olderJson.contains("coopForksBuild"), olderJson);

        CoopHandshakeManifest older = CoopHandshakeManifest.fromJson(olderJson);

        assertEquals(CoopHandshakeManifest.FORKS_BUILD_NOT_REPORTED, older.coopForksBuild());
        assertEquals(List.of("coopForksBuild: host=0.1.0/commit-a guest=not-reported"),
                CoopHandshakeDiff.compare(current, older).lines());
    }

    @Test
    void theForksBuildSurvivesAJsonRoundTrip() {
        CoopHandshakeManifest manifest = manifestWithForksBuild("0.1.0/commit-a");

        CoopHandshakeManifest decoded = CoopHandshakeManifest.fromJson(manifest.toJson());

        assertEquals("0.1.0/commit-a", decoded.coopForksBuild());
        assertEquals(manifest, decoded);
        assertEquals(manifest.toJson(), decoded.toJson());
    }

    private static CoopHandshakeManifest manifestWithForksBuild(String forksBuild) {
        return new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", forksBuild, List.of());
    }

    @Test
    void checksumUsesSha256HexForTextResource() {
        String checksum = CoopChecksum.sha256Text("{\"id\":\"utility\"}");

        assertEquals("18ccf9f498f9f2f65048fcf529457bf7a3df1e4bd56f0f18f9f092108bf49470", checksum);
    }

    @Test
    void unavailableChecksumIsStableSentinelWithReason() {
        assertEquals("UNAVAILABLE:script-sandbox", CoopChecksum.unavailable("script-sandbox"));
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

    private static CoopHandshakeManifest manifestWithPath(String path) {
        CoopHandshakeManifest.ModEntry mod = new CoopHandshakeManifest.ModEntry(
                "coop",
                "Starsector Coop V1",
                "0.1.0",
                "0.98a-RC8",
                path,
                List.of("jars/coop.jar"),
                Map.of("mod_info.json", CoopChecksum.unavailable("script-sandbox"),
                        "jars/coop.jar", CoopChecksum.unavailable("script-sandbox")));
        return new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of(mod));
    }
}
