package coop.launcher;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update check without a network. The fetcher is injected everywhere, so nothing in this file
 * can reach api.github.com even if it wanted to.
 */
class CoopUpdateCheckTest {

    /** Trimmed to the two fields the launcher reads, in GitHub's own shape. */
    private static final String RELEASE_BODY = """
            {
              "tag_name": "v0.3.0",
              "name": "0.3.0",
              "html_url": "https://github.com/AyoKeito/starsector-coop/releases/tag/v0.3.0",
              "draft": false,
              "prerelease": false
            }
            """;

    /** What GitHub answers for a repository that has never published a release. */
    private static final String NOT_FOUND_BODY = """
            {"message":"Not Found",\
            "documentation_url":"https://docs.github.com/rest/releases/releases#get-the-latest-release",\
            "status":"404"}
            """;

    // ---- the compare --------------------------------------------------------------------------

    @Test
    void versionsCompareNumericallyNotAsText() {
        assertTrue(CoopUpdateCheck.compare("0.10.0", "0.9.0") > 0,
                "10 is above 9 even though \"10\" sorts below \"9\"");
        assertTrue(CoopUpdateCheck.compare("1.0.0", "0.99.99") > 0);
        assertTrue(CoopUpdateCheck.compare("0.1.0", "0.1.1") < 0);
        assertEquals(0, CoopUpdateCheck.compare("0.1.0", "0.1.0"));
    }

    @Test
    void aMissingPartCountsAsZero() {
        assertEquals(0, CoopUpdateCheck.compare("1.2", "1.2.0"));
        assertEquals(0, CoopUpdateCheck.compare("1", "1.0.0"));
        assertTrue(CoopUpdateCheck.compare("1.2.1", "1.2") > 0);
    }

    @Test
    void aPartThatIsNotANumberCountsAsZeroRatherThanThrowing() {
        assertEquals(0, CoopUpdateCheck.compare("1.x.0", "1.0.0"));
        assertEquals(0, CoopUpdateCheck.compare("", ""));
        assertTrue(CoopUpdateCheck.compare("2.0.0", "nonsense") > 0);
    }

    @Test
    void aTagLosesItsVPrefixAndItsSuffix() {
        assertEquals("1.2.3", CoopUpdateCheck.normalise("v1.2.3"));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("V1.2.3"));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("  1.2.3  "));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("v1.2.3-rc1"));
        assertEquals("1.2.3", CoopUpdateCheck.normalise("1.2.3+build7"));
        assertEquals("", CoopUpdateCheck.normalise(null));
    }

    @Test
    void aPreReleaseOfTheVersionWeAreOnIsNotAnUpdate() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.3.0", 200,
                RELEASE_BODY.replace("v0.3.0", "v0.3.0-rc2"));

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
    }

    // ---- the outcomes -------------------------------------------------------------------------

    @Test
    void aNewerReleaseIsAnUpdateWithALink() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 200, RELEASE_BODY);

        assertEquals(CoopUpdateCheck.Kind.UPDATE_AVAILABLE, outcome.kind());
        assertEquals("0.3.0", outcome.version());
        assertEquals("https://github.com/AyoKeito/starsector-coop/releases/tag/v0.3.0",
                outcome.url());

        CoopInstallCheck.Row row = CoopUpdateCheck.row(outcome);
        assertEquals(CoopInstallCheck.Status.WARN, row.status());
        assertEquals("Update available: 0.3.0", row.label());
        assertTrue(row.detail().contains("Both players must install the same release"), row.detail());
        assertTrue(row.detail().contains(outcome.url()), row.detail());
    }

    @Test
    void theSameVersionIsUpToDate() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.3.0", 200, RELEASE_BODY);

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
        CoopInstallCheck.Row row = CoopUpdateCheck.row(outcome);
        assertEquals(CoopInstallCheck.Status.OK, row.status());
        assertEquals("Up to date: 0.3.0", row.label());
    }

    @Test
    void aLocalBuildAheadOfTheReleaseIsUpToDate() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.4.0", 200, RELEASE_BODY);

        assertEquals(CoopUpdateCheck.Kind.UP_TO_DATE, outcome.kind());
    }

    @Test
    void aRepositoryWithNoReleaseYetIsNeutralNotAWarning() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 404, NOT_FOUND_BODY);

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("no release published yet", outcome.reason());

        CoopInstallCheck.Row row = CoopUpdateCheck.row(outcome);
        assertEquals(CoopInstallCheck.Status.INFO, row.status());
        assertEquals("Update check: unavailable (no release published yet)", row.label());
        assertFalse(CoopInstallCheck.blocked(java.util.List.of(row)),
                "an update check must never block Launch");
    }

    @Test
    void aRateLimitIsNeutral() {
        assertEquals("GitHub rate limit", evaluate("0.1.0", 403, "{\"message\":\"rate limit\"}")
                .reason());
        assertEquals("GitHub rate limit", evaluate("0.1.0", 429, "").reason());
    }

    @Test
    void anyOtherStatusIsNeutralAndNamesTheCode() {
        assertEquals("HTTP 500", evaluate("0.1.0", 500, "<html>oops</html>").reason());
    }

    @Test
    void aBodyThatIsNotJsonIsNeutral() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 200, "<html>a captive portal</html>");

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("unreadable answer", outcome.reason());
    }

    @Test
    void aJsonBodyWithNoTagIsNeutral() {
        CoopUpdateCheck.Outcome outcome = evaluate("0.1.0", 200, "{\"name\":\"0.3.0\"}");

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("no tag_name in the answer", outcome.reason());
    }

    @Test
    void beingOfflineIsNeutralAndKeepsTheReason() {
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.check("0.1.0", "https://example.invalid",
                (url, agent) -> {
                    throw new IOException("example.invalid");
                });

        assertEquals(CoopUpdateCheck.Kind.UNAVAILABLE, outcome.kind());
        assertEquals("example.invalid", outcome.reason());
        assertEquals(CoopInstallCheck.Status.INFO, CoopUpdateCheck.row(outcome).status());
    }

    @Test
    void theUserAgentCarriesTheLauncherVersion() {
        String[] seen = new String[1];
        CoopUpdateCheck.check("0.1.0", CoopUpdateCheck.RELEASES_URL, (url, agent) -> {
            seen[0] = agent;
            return new CoopUpdateCheck.Response(200, RELEASE_BODY);
        });

        assertEquals("starsector-coop-launcher/0.1.0", seen[0]);
    }

    @Test
    void aVeryLongFailureMessageIsCutDownForTheRow() {
        String message = "x".repeat(500);
        CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.check("0.1.0", "https://example.invalid",
                (url, agent) -> {
                    throw new IOException(message);
                });

        assertNotEquals(message, outcome.reason());
        assertTrue(outcome.reason().length() <= 80, outcome.reason());
    }

    private static CoopUpdateCheck.Outcome evaluate(String current, int status, String body) {
        return CoopUpdateCheck.check(current, CoopUpdateCheck.RELEASES_URL,
                (url, agent) -> new CoopUpdateCheck.Response(status, body));
    }
}
