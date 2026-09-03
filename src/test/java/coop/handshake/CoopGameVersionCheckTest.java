package coop.handshake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopGameVersionCheckTest {

    @AfterEach
    void forget() {
        CoopGameVersionCheck.forget();
    }

    @Test
    void theSameVersionOnBothSidesMatches() {
        CoopGameVersionCheck.Result result =
                CoopGameVersionCheck.check("0.98a-RC8", "0.98a-RC8", false);

        assertEquals(CoopGameVersionCheck.Verdict.MATCH, result.verdict());
        assertFalse(result.mismatch());
        assertFalse(result.refuses());
    }

    @Test
    void surroundingWhitespaceIsNotADifference() {
        assertEquals(CoopGameVersionCheck.Verdict.MATCH,
                CoopGameVersionCheck.check("  0.98a-RC8 ", "0.98a-RC8\n", false).verdict());
    }

    @Test
    void aDifferentReleaseCandidateRefuses() {
        CoopGameVersionCheck.Result result =
                CoopGameVersionCheck.check("0.98a-RC8", "0.98a-RC9", false);

        assertEquals(CoopGameVersionCheck.Verdict.REFUSED, result.verdict());
        assertTrue(result.refuses());
        assertTrue(result.mismatch());
    }

    @Test
    void theDeveloperFlagTurnsARefusalIntoAnAllowedMismatch() {
        CoopGameVersionCheck.Result result =
                CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", true);

        assertEquals(CoopGameVersionCheck.Verdict.ALLOWED, result.verdict());
        assertFalse(result.refuses(), "the flag is the whole point: the session must be allowed");
        assertTrue(result.mismatch(), "it is still a mismatch, and still worth logging as one");
    }

    @Test
    void theFlagChangesNothingWhenTheVersionsAlreadyMatch() {
        assertEquals(CoopGameVersionCheck.Verdict.MATCH,
                CoopGameVersionCheck.check("0.98a-RC8", "0.98a-RC8", true).verdict());
    }

    @Test
    void aBlankVersionOnEitherSideNeverRefuses() {
        for (CoopGameVersionCheck.Result result : new CoopGameVersionCheck.Result[]{
                CoopGameVersionCheck.check(null, "0.98a-RC8", false),
                CoopGameVersionCheck.check("", "0.98a-RC8", false),
                CoopGameVersionCheck.check("0.98a-RC8", null, false),
                CoopGameVersionCheck.check("0.98a-RC8", "   ", false)}) {
            assertEquals(CoopGameVersionCheck.Verdict.UNKNOWN, result.verdict());
            assertFalse(result.refuses(), "a read that failed must not stop a session");
            assertFalse(result.mismatch());
        }
    }

    @Test
    void theMismatchMessageNamesBothVersionsInTheOrderTheLogLineIsGreppedFor() {
        assertEquals("Coop game version mismatch: mod built for 0.98a-RC8, game is 0.99a-RC1",
                CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", false).mismatchMessage());
    }

    @Test
    void theRawReasonUsesModAndGameRatherThanHostAndGuest() {
        String reason = CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", false).rawReason();

        assertEquals("installedGameVersion: mod=0.98a-RC8 game=0.99a-RC1", reason);
        assertFalse(reason.contains("host="), "there is no other player in this failure");
        assertFalse(reason.contains("guest="));
    }

    @Test
    void anUnknownVersionIsSpelledOutRatherThanLeftBlank() {
        assertEquals("installedGameVersion: mod=unknown game=0.98a-RC8",
                CoopGameVersionCheck.check("", "0.98a-RC8", false).rawReason());
    }

    @Test
    void theRememberedResultStartsAbsentAndCanBeCleared() {
        assertNull(CoopGameVersionCheck.remembered());

        CoopGameVersionCheck.Result result =
                CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", false);
        CoopGameVersionCheck.remember(result);
        assertEquals(result, CoopGameVersionCheck.remembered());

        CoopGameVersionCheck.forget();
        assertNull(CoopGameVersionCheck.remembered());
    }
}
