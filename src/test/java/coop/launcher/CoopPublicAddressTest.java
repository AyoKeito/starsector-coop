package coop.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopPublicAddressTest {

    @Test
    void theFirstEndpointThatAnswersWins() {
        List<String> asked = new ArrayList<>();
        CoopPublicAddress.Lookup lookup = CoopPublicAddress.lookup(
                List.of("https://a", "https://b"),
                url -> {
                    asked.add(url);
                    return "203.0.113.9\n";
                });

        assertTrue(lookup.ok());
        assertEquals("203.0.113.9", lookup.address());
        assertEquals(List.of("https://a"), asked);
    }

    @Test
    void aFailedFirstEndpointFallsThroughToTheSecond() {
        CoopPublicAddress.Lookup lookup = CoopPublicAddress.lookup(
                List.of("https://a", "https://b"),
                url -> {
                    if (url.endsWith("a")) {
                        throw new IOException("HTTP 503");
                    }
                    return "  2001:db8::1  ";
                });

        assertTrue(lookup.ok());
        assertEquals("2001:db8::1", lookup.address());
    }

    @Test
    void anEndpointThatAnswersRubbishIsSkipped() {
        CoopPublicAddress.Lookup lookup = CoopPublicAddress.lookup(
                List.of("https://a", "https://b"),
                url -> url.endsWith("a") ? "<html>captive portal</html>" : "198.51.100.7");

        assertTrue(lookup.ok());
        assertEquals("198.51.100.7", lookup.address());
    }

    @Test
    void everythingFailingProducesTheTypeItInMessage() {
        CoopPublicAddress.Lookup lookup = CoopPublicAddress.lookup(
                List.of("https://a", "https://b"),
                url -> {
                    throw new IOException("no route to host");
                });

        assertFalse(lookup.ok());
        assertTrue(lookup.error().toLowerCase(java.util.Locale.ROOT)
                        .contains("could not look up your public address, type it in"),
                lookup.error());
        assertTrue(lookup.error().contains("https://a"), lookup.error());
        assertTrue(lookup.error().contains("no route to host"), lookup.error());
    }

    @Test
    void theRealEndpointsAreBothHttps() {
        for (String endpoint : CoopPublicAddress.ENDPOINTS) {
            assertTrue(endpoint.startsWith("https://"), endpoint);
        }
        assertEquals(List.of("https://api.ipify.org", "https://icanhazip.com"),
                CoopPublicAddress.ENDPOINTS);
    }

    @Test
    void onlyRealIpLiteralsAreAccepted() {
        for (String good : new String[]{"0.0.0.0", "203.0.113.9", "255.255.255.255",
                "2001:db8::1", "::1", "fe80::1", "2001:db8::192.0.2.1"}) {
            assertTrue(CoopPublicAddress.isIpLiteral(good), good);
        }
        for (String bad : new String[]{"", "   ", "example.com", "203.0.113", "203.0.113.256",
                "203.0.113.09", "not an address", "<html>", "1.2.3.4.5",
                "203.0.113.9 and more"}) {
            assertFalse(CoopPublicAddress.isIpLiteral(bad), bad);
        }
        assertFalse(CoopPublicAddress.isIpLiteral(null));
    }

    /** A hostname must never be resolved here; that would be a DNS lookup we did not ask for. */
    @Test
    void aHostnameIsRejectedWithoutResolvingIt() {
        assertFalse(CoopPublicAddress.isIpLiteral("localhost"));
        assertFalse(CoopPublicAddress.isIpLiteral("api.ipify.org"));
    }
}
