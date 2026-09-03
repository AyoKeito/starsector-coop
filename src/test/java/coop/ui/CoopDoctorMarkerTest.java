package coop.ui;

import coop.handshake.CoopHandshakeDiff;
import coop.handshake.CoopHandshakeManifest;
import coop.net.CoopConnectionRole;
import coop.net.CoopReconnectCoordinator;
import coop.seed.CoopSeedSync;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The marker exists to be pasted into a support thread from two machines and lined up by
 * {@code sessionId}. Everything asserted here is about that job: one line, every field present, and
 * nothing in a value that could break the line in half or truncate a paste.
 */
class CoopDoctorMarkerTest {

    private static final String HOST_FP = "a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff0";
    private static final String GUEST_FP = "f00dcafe11223344556677889900aabbccddeeff00112233445566778899aabb";

    @Test
    void aSeedMarkerIsOneLineWithEveryFieldAHelperNeeds() {
        CoopDesyncReason reason = CoopDesyncReason.classify(
                CoopSeedSync.fingerprintMismatch(HOST_FP, GUEST_FP), CoopDesyncReason.Source.SEED_LOCK);

        String line = CoopDoctorMarker.format(reason, "session-4471", CoopConnectionRole.GUEST,
                "Ayo", "Partner");

        assertFalse(line.contains("\n"), "one line, because nothing here can copy text for the player");
        assertTrue(line.startsWith("[COOP-DOCTOR] code=COOP-SEED "), line);
        assertTrue(line.contains(" sessionId=session-4471 "), line);
        assertTrue(line.contains(" role=GUEST "), line);
        assertTrue(line.contains(" source=SEED_LOCK "), line);
        assertTrue(line.contains(" local=\"Ayo\" "), line);
        assertTrue(line.contains(" remote=\"Partner\" "), line);
        assertTrue(line.contains(" hostFingerprint=" + HOST_FP + " "), line);
        assertTrue(line.contains(" guestFingerprint=" + GUEST_FP), line);
        assertTrue(line.contains(" campaignIdMismatch=false "), line);
        assertTrue(line.contains(" reason=\"sectorFingerprint: host=" + HOST_FP
                + " guest=" + GUEST_FP + "\""), line);
    }

    @Test
    void theSearchStringIsWhatTheLineActuallyContains() {
        CoopDesyncReason seed = CoopDesyncReason.classify(
                CoopSeedSync.seedStringMismatch("coop-a", "coop-b"), CoopDesyncReason.Source.SEED_LOCK);

        String search = CoopDoctorMarker.searchString(seed);

        assertEquals("[COOP-DOCTOR] code=COOP-SEED", search);
        assertTrue(CoopDoctorMarker.format(seed, "s", CoopConnectionRole.HOST, "a", "b").contains(search),
                "a search string that does not match its own line is worse than none");
    }

    @Test
    void aMultiLineModDiffIsFoldedOntoOneEscapedLine() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8"), mod("other", "Other", "1.0")),
                manifest(mod("utility", "Utility Mod", "2.7")))
                .toDisplayString();
        assertTrue(raw.contains("\n"), "precondition: the real diff is multi-line");
        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);

        String line = CoopDoctorMarker.format(reason, "session-4471", CoopConnectionRole.HOST,
                "Host", "Guest");

        assertFalse(line.contains("\n"));
        assertTrue(line.contains("\\n"), "the newlines are escaped, not dropped");
        assertTrue(line.contains(" code=COOP-MODS "), line);
        assertTrue(line.contains(" modsShown=2 "), line);
        assertTrue(line.contains(" modsHidden=0 "), line);
        assertTrue(line.contains("utility=VERSION_DIFFERS:2.8>2.7"), line);
        assertTrue(line.contains("other=MISSING_ON_GUEST"), line);
    }

    @Test
    void quotesAndControlCharactersInAReasonCannotBreakTheLine() {
        CoopDesyncReason reason = CoopDesyncReason.classify(
                "handshakeManifest: java.lang.RuntimeException: bad \"value\"\tat C:\\mods\nsecond line",
                CoopDesyncReason.Source.HANDSHAKE);

        String line = CoopDoctorMarker.format(reason, "s", CoopConnectionRole.GUEST, "a", "b");

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\t"));
        assertTrue(line.contains("bad \\\"value\\\""), line);
        assertTrue(line.contains("C:\\\\mods"), line);
        // Exactly one unescaped quote pair per quoted field: local, remote, reason, mods.
        assertEquals(8, countUnescapedQuotes(line), line);
    }

    @Test
    void aSessionMarkerCarriesCauseGraceAndRetryability() {
        CoopDesyncReason reason = CoopDesyncReason
                .classify(CoopReconnectCoordinator.REASON_GRACE_EXPIRED,
                        CoopDesyncReason.Source.SESSION_RESUME)
                .withGraceSeconds(300);

        String line = CoopDoctorMarker.format(reason, "session-1", CoopConnectionRole.HOST, "H", "G");

        assertTrue(line.contains(" code=COOP-SESSION "), line);
        assertTrue(line.contains(" cause=GRACE_EXPIRED "), line);
        assertTrue(line.contains(" graceSeconds=300 "), line);
        assertTrue(line.contains(" retryable=false"), line);
    }

    @Test
    void missingInputsBecomeNoneRatherThanNullText() {
        String line = CoopDoctorMarker.format(null, null, null, null, null);

        assertTrue(line.startsWith("[COOP-DOCTOR] code=COOP-SESSION "), line);
        assertTrue(line.contains(" sessionId=<none> "), line);
        assertTrue(line.contains(" role=NONE "), line);
        assertTrue(line.contains(" local=\"<none>\" "), line);
        assertFalse(line.contains("null"), line);
    }

    @Test
    void logDoesNotThrowWithoutARunningGame() {
        CoopDesyncReason reason = CoopDesyncReason.classify("anything", CoopDesyncReason.Source.OTHER);
        CoopDoctorMarker.log(reason, "session-1", CoopConnectionRole.GUEST, "a", "b");
    }

    private static int countUnescapedQuotes(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                count++;
            }
        }
        return count;
    }

    private static CoopHandshakeManifest manifest(CoopHandshakeManifest.ModEntry... mods) {
        return new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of(mods));
    }

    private static CoopHandshakeManifest.ModEntry mod(String id, String name, String version) {
        return new CoopHandshakeManifest.ModEntry(id, name, version, "0.98a-RC8", "mods/" + id,
                List.of("jars/" + id + ".jar"), Map.of());
    }
}
