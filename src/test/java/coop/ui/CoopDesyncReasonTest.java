package coop.ui;

import coop.handshake.CoopHandshakeDiff;
import coop.handshake.CoopHandshakeManifest;
import coop.net.CoopConnectionRole;
import coop.net.CoopReconnectCoordinator;
import coop.seed.CoopSeedSync;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classifier is only worth anything if it reads the strings the mod actually produces, so every
 * input here is built by calling a real producer - {@link CoopSeedSync}, {@link CoopHandshakeDiff},
 * {@link CoopReconnectCoordinator} - rather than by pasting a shape that might already be stale. A
 * producer that changes its wording breaks these tests, which is the point.
 */
class CoopDesyncReasonTest {

    private static final String HOST_FP = "a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff0";
    private static final String GUEST_FP = "f00dcafe11223344556677889900aabbccddeeff00112233445566778899aabb";

    // ------------------------------------------------------------------ seed

    @Test
    void aSeedStringMismatchFromTheRealProducerClassifiesAsSeed() {
        String raw = CoopSeedSync.seedStringMismatch(
                CoopSeedSync.formatSeedString(0x1122334455667788L),
                CoopSeedSync.formatSeedString(0x99aabbccddeeff00L));

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.SEED_LOCK);

        assertEquals(CoopDesyncReason.Kind.SEED, reason.kind());
        assertEquals("COOP-SEED", reason.code());
        assertEquals("coop-1122334455667788", reason.hostSeed());
        assertEquals("coop-99aabbccddeeff00", reason.guestSeed());
        assertFalse(reason.campaignIdMismatch());
        assertFalse(reason.retryable(), "a seed mismatch is deterministic; retrying it wastes a launch");
    }

    @Test
    void aFingerprintMismatchFromTheRealProducerKeepsBothSides() {
        String raw = CoopSeedSync.fingerprintMismatch(HOST_FP, GUEST_FP);

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.SEED_LOCK);

        assertEquals(CoopDesyncReason.Kind.SEED, reason.kind());
        assertEquals(HOST_FP, reason.hostFingerprint());
        assertEquals(GUEST_FP, reason.guestFingerprint());
    }

    @Test
    void theCampaignIdRejectDropsTheLaunchAdviceFromTheValues() {
        // CoopNetPump.checkOrAdoptCampaignId, the "already in flight" branch.
        String raw = "campaignId: host=camp-7f3a guest=<none>"
                + "; this campaign is already in flight and this guest campaign is brand new"
                + " (a fresh same-seed roll cannot silently rejoin it). To join anyway with a"
                + " fresh start, relaunch the guest with -Dcoop.adoptCampaignId=true"
                + " (launch-guest.ps1 -AdoptCampaign)";

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.SEED_LOCK);

        assertEquals(CoopDesyncReason.Kind.SEED, reason.kind());
        assertTrue(reason.campaignIdMismatch());
        assertEquals("camp-7f3a", reason.hostCampaignId());
        assertEquals("", reason.guestCampaignId(), "<none> means the guest had no id, not an id of \"<none>\"");
    }

    @Test
    void theOtherCampaignIdRejectKeepsTheStoredId() {
        String raw = "campaignId: host=camp-7f3a guest=camp-0001"
                + "; guest save is not from this coop campaign. To adopt the host campaign anyway,"
                + " relaunch the guest with -Dcoop.adoptCampaignId=true";

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.SEED_LOCK);

        assertTrue(reason.campaignIdMismatch());
        assertEquals("camp-7f3a", reason.hostCampaignId());
        assertEquals("camp-0001", reason.guestCampaignId());
    }

    @Test
    void aSeedLockExceptionStillLandsOnTheSeedDialog() {
        // CoopNetPump:2274 - "seedLock: " + ex.getMessage().
        CoopDesyncReason reason = CoopDesyncReason.classify(
                "seedLock: sectorFingerprint is blank", CoopDesyncReason.Source.SEED_LOCK);

        assertEquals(CoopDesyncReason.Kind.SEED, reason.kind());
        assertEquals("", reason.hostFingerprint(), "nothing structured to parse, and that is fine");
    }

    @Test
    void fingerprintsShortenToEightHexHyphenated() {
        assertEquals("a1b2-c3d4", CoopDesyncReason.shortFingerprint(HOST_FP));
        assertEquals("f00d-cafe", CoopDesyncReason.shortFingerprint(GUEST_FP));
        assertEquals("a1b2-c3d4", CoopDesyncReason.shortFingerprint("  A1B2C3D4E5  "));
        assertEquals("", CoopDesyncReason.shortFingerprint("short"));
        assertEquals("", CoopDesyncReason.shortFingerprint(""));
        assertEquals("", CoopDesyncReason.shortFingerprint(null));
    }

    // ------------------------------------------------------------------ mods

    @Test
    void aVersionDiffFromTheRealDiffBecomesOneRowWithBothVersions() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("mod_info.json", "aaa"))),
                manifest(mod("utility", "Utility Mod", "2.7", Map.of("mod_info.json", "bbb"))))
                .toDisplayString();

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);

        assertEquals(CoopDesyncReason.Kind.MODS, reason.kind());
        assertEquals("COOP-MODS", reason.code());
        assertEquals(1, reason.modRows().size(), "one row per mod, however many diff lines it produced");
        CoopDesyncReason.ModRow row = reason.modRows().get(0);
        assertEquals("utility", row.modId());
        assertEquals(CoopDesyncReason.ModVerdict.VERSION_DIFFERS, row.verdict());
        assertEquals("2.8", row.hostVersion());
        assertEquals("2.7", row.guestVersion());
        assertEquals(CoopDesyncReason.StaleSide.GUEST, row.staleSide());
        assertEquals("you have 2.7 / the host has 2.8", row.verdictText(CoopConnectionRole.GUEST));
        assertFalse(reason.hasSameVersionDifferentContents(),
                "a version difference explains the checksum difference under it");
    }

    @Test
    void whenTheHostIsTheStaleSideTheBlameGoesToTheHost() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.7", Map.of("mod_info.json", "aaa"))),
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("mod_info.json", "aaa"))))
                .toDisplayString();

        CoopDesyncReason.ModRow row = CoopDesyncReason
                .classify(raw, CoopDesyncReason.Source.HANDSHAKE)
                .modRows().get(0);

        assertEquals(CoopDesyncReason.StaleSide.HOST, row.staleSide());
        assertTrue(row.remedyText(CoopConnectionRole.GUEST).startsWith("ask the host to update it to 2.8"),
                "actual: " + row.remedyText(CoopConnectionRole.GUEST));
        assertTrue(row.remedyText(CoopConnectionRole.HOST).startsWith("update it to 2.8"),
                "actual: " + row.remedyText(CoopConnectionRole.HOST));
    }

    @Test
    void unorderableVersionsNeverGuessWhoIsStale() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "dev", Map.of("mod_info.json", "aaa"))),
                manifest(mod("utility", "Utility Mod", "nightly", Map.of("mod_info.json", "aaa"))))
                .toDisplayString();

        CoopDesyncReason.ModRow row = CoopDesyncReason
                .classify(raw, CoopDesyncReason.Source.HANDSHAKE)
                .modRows().get(0);

        assertEquals(CoopDesyncReason.StaleSide.UNKNOWN, row.staleSide());
        assertEquals("match the host: switch to dev", row.remedyText(CoopConnectionRole.GUEST));
    }

    @Test
    void aModMissingOnOneSideCarriesItsOwnRemedyVerb() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8", Map.of())),
                manifest())
                .toDisplayString();

        CoopDesyncReason.ModRow row = CoopDesyncReason
                .classify(raw, CoopDesyncReason.Source.HANDSHAKE)
                .modRows().get(0);

        assertEquals(CoopDesyncReason.ModVerdict.MISSING_ON_GUEST, row.verdict());
        assertEquals("not installed", row.verdictText(CoopConnectionRole.GUEST));
        assertEquals("install it and enable it in the launcher", row.remedyText(CoopConnectionRole.GUEST));
    }

    @Test
    void aModOnlyTheGuestHasIsTheDisableItCase() {
        String raw = CoopHandshakeDiff.compare(
                manifest(),
                manifest(mod("extra", "Extra Mod", "1.0", Map.of())))
                .toDisplayString();

        CoopDesyncReason.ModRow row = CoopDesyncReason
                .classify(raw, CoopDesyncReason.Source.HANDSHAKE)
                .modRows().get(0);

        assertEquals(CoopDesyncReason.ModVerdict.MISSING_ON_HOST, row.verdict());
        assertEquals("not on host - disable it", row.verdictText(CoopConnectionRole.GUEST));
        assertEquals("disable it in the launcher", row.remedyText(CoopConnectionRole.GUEST));
    }

    @Test
    void sameVersionDifferentChecksumsIsItsOwnVerdict() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("data/x.csv", "aaa"))),
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("data/x.csv", "bbb"))))
                .toDisplayString();

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);

        assertEquals(CoopDesyncReason.ModVerdict.CONTENT_DIFFERS, reason.modRows().get(0).verdict());
        assertEquals("same version, different contents",
                reason.modRows().get(0).verdictText(CoopConnectionRole.GUEST));
        assertTrue(reason.hasSameVersionDifferentContents(),
                "the dialog's dedicated sentence hangs off this flag");
    }

    @Test
    void aChecksumFileMissingOnOneSideStillFoldsIntoOneRow() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("data/x.csv", "aaa", "data/y.csv", "ccc"))),
                manifest(mod("utility", "Utility Mod", "2.8", Map.of("data/x.csv", "aaa"))))
                .toDisplayString();

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);

        assertEquals(1, reason.modRows().size());
        assertEquals(CoopDesyncReason.ModVerdict.CONTENT_DIFFERS, reason.modRows().get(0).verdict());
    }

    @Test
    void aModIdContainingDotsIsNotSplitOnTheWrongDot() {
        String raw = CoopHandshakeDiff.compare(
                manifest(mod("org.example.mod", "Dotted", "2.8", Map.of())),
                manifest(mod("org.example.mod", "Dotted", "2.7", Map.of())))
                .toDisplayString();

        CoopDesyncReason.ModRow row = CoopDesyncReason
                .classify(raw, CoopDesyncReason.Source.HANDSHAKE)
                .modRows().get(0);

        assertEquals("org.example.mod", row.modId());
        assertEquals(CoopDesyncReason.ModVerdict.VERSION_DIFFERS, row.verdict());
    }

    @Test
    void theRowListIsCappedAtEightWithTheRestCounted() {
        List<CoopHandshakeManifest.ModEntry> hostMods = new ArrayList<>();
        List<CoopHandshakeManifest.ModEntry> guestMods = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            hostMods.add(mod("mod" + i, "Mod " + i, "2.0", Map.of()));
            guestMods.add(mod("mod" + i, "Mod " + i, "1.0", Map.of()));
        }
        String raw = CoopHandshakeDiff.compare(
                new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", hostMods),
                new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", guestMods))
                .toDisplayString();

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);

        assertEquals(CoopDesyncReason.MAX_MOD_ROWS, reason.modRows().size());
        assertEquals(3, reason.hiddenModRows());
    }

    @Test
    void gameAndCoopBuildDifferencesAreReadAsInstallFacts() {
        String raw = CoopHandshakeDiff.compare(
                new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of()),
                new CoopHandshakeManifest("0.97a-RC11", "0.1.1", "commit-b", List.of()))
                .toDisplayString();

        CoopDesyncReason reason = CoopDesyncReason.classify(raw, CoopDesyncReason.Source.HANDSHAKE);

        assertEquals(CoopDesyncReason.Kind.MODS, reason.kind());
        assertTrue(reason.gameVersionMismatch());
        assertEquals("0.98a-RC8", reason.hostGameVersion());
        assertEquals("0.97a-RC11", reason.guestGameVersion());
        assertTrue(reason.coopBuildMismatch());
        assertEquals("0.1.0", reason.hostCoopBuild());
        assertEquals("0.1.1", reason.guestCoopBuild());
    }

    @Test
    void ironModeAndUnreadableManifestsAreBothHandshakeShaped() {
        // Both from CoopNetPump.handshakeDiffFor.
        CoopDesyncReason iron = CoopDesyncReason.classify("ironMode: guest=true",
                CoopDesyncReason.Source.HANDSHAKE);
        assertEquals(CoopDesyncReason.Kind.MODS, iron.kind());
        assertEquals("guest", iron.ironModeSide());

        CoopDesyncReason unreadable = CoopDesyncReason.classify(
                "handshakeManifest: java.lang.IllegalArgumentException: gameVersion is blank",
                CoopDesyncReason.Source.HANDSHAKE);
        assertEquals(CoopDesyncReason.Kind.MODS, unreadable.kind());
        assertTrue(unreadable.manifestUnreadable());
        assertTrue(unreadable.modRows().isEmpty());
    }

    // --------------------------------------------------------------- session

    @Test
    void everyCoordinatorReasonMapsToItsOwnCause() {
        assertEquals(CoopDesyncReason.SessionCause.GRACE_EXPIRED,
                classifyResume(CoopReconnectCoordinator.REASON_GRACE_EXPIRED).sessionCause());
        assertEquals(CoopDesyncReason.SessionCause.ENDED_BY_PLAYER,
                classifyResume(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER).sessionCause());
        assertEquals(CoopDesyncReason.SessionCause.HOST_IN_GRACE,
                classifyResume(CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE).sessionCause());
    }

    @Test
    void everyResumeRejectTextMapsToItsOwnCause() {
        // CoopNetPump wraps the host's reject text behind REASON_HOST_REJECTED before handing it on.
        assertEquals(CoopDesyncReason.SessionCause.GRACE_EXPIRED,
                classifyHostReject(CoopReconnectCoordinator.ResumeDecision.REJECT_NOT_WAITING).sessionCause());
        assertEquals(CoopDesyncReason.SessionCause.DIFFERENT_CAMPAIGN,
                classifyHostReject(CoopReconnectCoordinator.ResumeDecision.REJECT_SESSION_MISMATCH).sessionCause());
        assertEquals(CoopDesyncReason.SessionCause.SLOT_TAKEN,
                classifyHostReject(CoopReconnectCoordinator.ResumeDecision.REJECT_PLAYER_MISMATCH).sessionCause());
    }

    @Test
    void onlyTheTransientCauseIsRetryable() {
        assertTrue(classifyResume(CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE).retryable(),
                "the host's window closes on its own, so a second attempt can genuinely work");
        assertFalse(classifyResume(CoopReconnectCoordinator.REASON_GRACE_EXPIRED).retryable());
        assertFalse(classifyHostReject(CoopReconnectCoordinator.ResumeDecision.REJECT_SESSION_MISMATCH)
                .retryable());
        assertFalse(classifyHostReject(CoopReconnectCoordinator.ResumeDecision.REJECT_PLAYER_MISMATCH)
                .retryable());
    }

    @Test
    void theGraceWindowIsReadFromTheTextWhenTheProducerStatesIt() {
        assertEquals(120, CoopDesyncReason.classify(
                CoopReconnectCoordinator.REASON_GRACE_EXPIRED + " after 120 s",
                CoopDesyncReason.Source.SESSION_RESUME).graceSeconds());
        assertEquals(90, CoopDesyncReason.classify(
                CoopReconnectCoordinator.REASON_GRACE_EXPIRED + " after 90 seconds",
                CoopDesyncReason.Source.SESSION_RESUME).graceSeconds());
        assertEquals(-1, classifyResume(CoopReconnectCoordinator.REASON_GRACE_EXPIRED).graceSeconds(),
                "today's constant carries no number, which is what withGraceSeconds is for");
    }

    @Test
    void aBareNumberIsNeverMistakenForASecondsFigure() {
        assertEquals(-1, CoopDesyncReason.classify(
                "session id does not match the held session (session-4471)",
                CoopDesyncReason.Source.SESSION_RESUME).graceSeconds());
    }

    @Test
    void thePumpCanSupplyTheGraceWindowTheTextLacks() {
        CoopDesyncReason reason = classifyResume(CoopReconnectCoordinator.REASON_GRACE_EXPIRED)
                .withGraceSeconds(300);

        assertEquals(300, reason.graceSeconds());
        assertEquals(CoopDesyncReason.SessionCause.GRACE_EXPIRED, reason.sessionCause());
        assertEquals(CoopDesyncReason.Kind.SESSION, reason.kind(), "everything else survives the copy");
    }

    // -------------------------------------------------------------- fallback

    @Test
    void garbageStillProducesADialogWorthOfReason() {
        CoopDesyncReason reason = CoopDesyncReason.classify("the modem exploded",
                CoopDesyncReason.Source.OTHER);

        assertEquals(CoopDesyncReason.Kind.UNMAPPED, reason.kind());
        assertEquals("the modem exploded", reason.rawReason());
        assertFalse(reason.retryable());
    }

    @Test
    void nothingAtAllStillProducesAReason() {
        for (String empty : new String[]{null, "", "   "}) {
            CoopDesyncReason reason = CoopDesyncReason.classify(empty, null);
            assertNotNull(reason);
            assertEquals(CoopDesyncReason.Kind.UNMAPPED, reason.kind());
            assertFalse(reason.rawReason().isEmpty(), "a blank body is the bug this fallback prevents");
        }
    }

    @Test
    void theSourceOnlyDecidesWhenTheTextDoesNot() {
        assertEquals(CoopDesyncReason.Kind.MODS,
                CoopDesyncReason.classify("something odd", CoopDesyncReason.Source.HANDSHAKE).kind());
        assertEquals(CoopDesyncReason.Kind.SEED,
                CoopDesyncReason.classify("something odd", CoopDesyncReason.Source.SEED_LOCK).kind());
        assertEquals(CoopDesyncReason.Kind.SESSION,
                CoopDesyncReason.classify("something odd", CoopDesyncReason.Source.SESSION_RESUME).kind());
        assertEquals(CoopDesyncReason.SessionCause.OTHER,
                CoopDesyncReason.classify("something odd", CoopDesyncReason.Source.SESSION_RESUME)
                        .sessionCause());
        // ... and never when it does: a seed-shaped string arriving on the handshake path is still
        // a seed problem.
        assertEquals(CoopDesyncReason.Kind.SEED,
                CoopDesyncReason.classify(CoopSeedSync.fingerprintMismatch(HOST_FP, GUEST_FP),
                        CoopDesyncReason.Source.HANDSHAKE).kind());
    }

    // ----------------------------------------------------------------- setup

    private static CoopDesyncReason classifyResume(String raw) {
        return CoopDesyncReason.classify(raw, CoopDesyncReason.Source.SESSION_RESUME);
    }

    private static CoopDesyncReason classifyHostReject(CoopReconnectCoordinator.ResumeDecision decision) {
        String raw = CoopReconnectCoordinator.REASON_HOST_REJECTED + ": "
                + CoopReconnectCoordinator.rejectReason(decision);
        return classifyResume(raw);
    }

    private static CoopHandshakeManifest manifest(CoopHandshakeManifest.ModEntry... mods) {
        return new CoopHandshakeManifest("0.98a-RC8", "0.1.0", "commit-a", List.of(mods));
    }

    private static CoopHandshakeManifest.ModEntry mod(String id, String name, String version,
                                                      Map<String, String> checksums) {
        return new CoopHandshakeManifest.ModEntry(id, name, version, "0.98a-RC8", "mods/" + id,
                List.of("jars/" + id + ".jar"), checksums);
    }
}
