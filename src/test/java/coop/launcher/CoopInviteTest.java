package coop.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopInviteTest {

    private static CoopInvite roundTrip(String host, int port, String seed, String password) {
        String text = CoopInvite.format(host, port, seed, password);
        CoopInvite.Parsed parsed = CoopInvite.parse(text);
        assertTrue(parsed.ok(), text + " -> " + parsed.error());
        return parsed.invite();
    }

    @Test
    void anOrdinaryInviteLooksLikeTheDocumentedShape() {
        assertEquals("coop://203.0.113.9:7777/?seed=MN-42&pw=hunter2",
                CoopInvite.format("203.0.113.9", 7777, "MN-42", "hunter2"));
    }

    @Test
    void ipv4RoundTrips() {
        CoopInvite invite = roundTrip("203.0.113.9", 7777, "MN-1234567890123456789", "hunter2");

        assertEquals("203.0.113.9", invite.host());
        assertEquals(7777, invite.port());
        assertEquals("MN-1234567890123456789", invite.seed());
        assertEquals("hunter2", invite.password());
    }

    @Test
    void ipv6IsBracketedAndComesBackBare() {
        String text = CoopInvite.format("2001:db8::1", 7777, "MN-7", "");

        assertEquals("coop://[2001:db8::1]:7777/?seed=MN-7", text);
        assertEquals("2001:db8::1", roundTrip("2001:db8::1", 7777, "MN-7", "").host());
    }

    @Test
    void anAlreadyBracketedIpv6IsNotDoubleBracketed() {
        assertEquals("coop://[2001:db8::1]:7777/?seed=MN-7",
                CoopInvite.format("[2001:db8::1]", 7777, "MN-7", ""));
    }

    @Test
    void sectorSizeAndStarAgeRideAlongAndAreLeftOutWhenBlank() {
        String text = CoopInvite.format("203.0.113.9", 7777, "MN-1", "", "Small", "OLD");
        assertEquals("coop://203.0.113.9:7777/?seed=MN-1&size=small&age=old", text);
        CoopInvite.Parsed parsed = CoopInvite.parse(text);
        assertTrue(parsed.ok(), parsed.error());
        assertEquals("small", parsed.invite().sectorSize());
        assertEquals("old", parsed.invite().sectorAge());

        CoopInvite.Parsed bare = CoopInvite.parse(CoopInvite.format("h", 1, "MN-1", "x"));
        assertTrue(bare.ok(), bare.error());
        assertEquals("", bare.invite().sectorSize());
        assertEquals("", bare.invite().sectorAge());
    }

    @Test
    void anEmptySeedIsLeftOutAndComesBackEmpty() {
        String text = CoopInvite.format("host.example", 7777, "", "pw");

        assertFalse(text.contains("seed="), text);
        assertEquals("", roundTrip("host.example", 7777, "", "pw").seed());
    }

    @Test
    void noPasswordLeavesTheParameterOutEntirely() {
        assertEquals("coop://host.example:7777/?seed=MN-7",
                CoopInvite.format("host.example", 7777, "MN-7", ""));
    }

    @Test
    void neitherSeedNorPasswordLeavesABareAddress() {
        assertEquals("coop://host.example:7777/",
                CoopInvite.format("host.example", 7777, "", ""));
        assertEquals("host.example", roundTrip("host.example", 7777, "", "").host());
    }

    @Test
    void awkwardPasswordsSurviveTheRoundTrip() {
        for (String password : new String[]{
                "two words",
                "a&b",
                "key=value",
                "100%",
                "+plus+",
                "sl/ash?que",
                "ümläut 你好",
                "  leading and trailing  "}) {
            CoopInvite invite = roundTrip("203.0.113.9", 7777, "MN-9", password);
            assertEquals(password, invite.password(), "password: " + password);
        }
    }

    @Test
    void whitespaceAroundAPastedInviteIsTolerated() {
        CoopInvite.Parsed parsed = CoopInvite.parse("\n\t  coop://203.0.113.9:7777/?seed=MN-1  \r\n");

        assertTrue(parsed.ok(), parsed.error());
        assertEquals(7777, parsed.invite().port());
    }

    @Test
    void aTrailingSlashIsOptionalOnTheWayIn() {
        assertTrue(CoopInvite.parse("coop://203.0.113.9:7777?seed=MN-1").ok());
        assertTrue(CoopInvite.parse("coop://203.0.113.9:7777").ok());
    }

    @Test
    void theSchemeIsMatchedCaseInsensitively() {
        assertTrue(CoopInvite.parse("COOP://203.0.113.9:7777/").ok());
    }

    @Test
    void aNonInviteNamesTheScheme() {
        CoopInvite.Parsed parsed = CoopInvite.parse("http://example.com");

        assertFalse(parsed.ok());
        assertTrue(parsed.error().contains("coop://"), parsed.error());
    }

    @Test
    void anEmptyPasteSaysSo() {
        assertTrue(CoopInvite.parse("   ").error().contains("nothing to paste"));
    }

    @Test
    void aMissingPortNamesThePort() {
        CoopInvite.Parsed parsed = CoopInvite.parse("coop://203.0.113.9/");

        assertFalse(parsed.ok());
        assertTrue(parsed.error().contains("port"), parsed.error());
    }

    @Test
    void aNonNumericPortNamesTheValue() {
        assertTrue(CoopInvite.parse("coop://203.0.113.9:seven/").error().contains("\"seven\""));
    }

    @Test
    void anOutOfRangePortNamesTheRange() {
        assertTrue(CoopInvite.parse("coop://203.0.113.9:70000/").error().contains("1 to 65535"));
    }

    @Test
    void aBareIpv6SaysToBracketIt() {
        CoopInvite.Parsed parsed = CoopInvite.parse("coop://2001:db8::1:7777/");

        assertFalse(parsed.ok());
        assertTrue(parsed.error().contains("brackets"), parsed.error());
    }

    @Test
    void anUnclosedBracketNamesTheBracket() {
        assertTrue(CoopInvite.parse("coop://[2001:db8::1:7777/").error().contains("]"));
    }

    @Test
    void anUnknownParameterIsRefusedRatherThanIgnored() {
        CoopInvite.Parsed parsed = CoopInvite.parse("coop://203.0.113.9:7777/?mode=cheat");

        assertFalse(parsed.ok());
        assertTrue(parsed.error().contains("mode"), parsed.error());
    }

    @Test
    void anInviteCarryingASeedTheGameCannotParseIsRefused() {
        CoopInvite.Parsed parsed =
                CoopInvite.parse("coop://203.0.113.9:7777/?seed=MN-9999999999999999999");

        assertFalse(parsed.ok());
        assertTrue(parsed.error().contains("64-bit"), parsed.error());
    }

    @Test
    void abrokenPercentEscapeNamesThePartItBroke() {
        CoopInvite.Parsed parsed = CoopInvite.parse("coop://203.0.113.9:7777/?pw=%zz");

        assertFalse(parsed.ok());
        assertTrue(parsed.error().contains("pw"), parsed.error());
    }

    @Test
    void formattingRefusesAnEmptyHostOrABadPort() {
        assertThrows(() -> CoopInvite.format("", 7777, "", ""));
        assertThrows(() -> CoopInvite.format("host", 0, "", ""));
        assertThrows(() -> CoopInvite.format("host", 70000, "", ""));
    }

    @Test
    void toStringNeverLeaksThePassword() {
        String text = new CoopInvite("h", 1, "MN-1", "hunter2").toString();

        assertFalse(text.contains("hunter2"), text);
        assertTrue(text.contains("password=set"), text);
    }

    @Test
    void theValidatorAgreesWithAnEmptySeed() {
        assertNull(CoopSeeds.validate(""));
        assertNull(CoopSeeds.validate(null));
    }

    private static void assertThrows(Runnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                runnable::run);
    }
}
