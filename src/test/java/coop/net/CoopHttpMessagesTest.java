package coop.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopHttpMessagesTest {
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsContentLengthFramedBody() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/xml\r\n\r\nhello");

        assertTrue(CoopHttpMessages.isComplete(data, data.length));
        CoopHttpMessages.Response response = CoopHttpMessages.parse(data, data.length);

        assertEquals(200, response.statusCode());
        assertEquals("OK", response.reasonPhrase());
        assertEquals("hello", response.body());
        assertEquals("text/xml", response.header("CONTENT-TYPE"));
    }

    @Test
    void contentLengthResponseIsIncompleteUntilTheWholeBodyArrives() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhel");

        assertFalse(CoopHttpMessages.isComplete(data, data.length));
    }

    @Test
    void readsChunkedBodyBecauseManyIgdsChunkTheDeviceDescriptor() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n"
                + "6\r\n world\r\n"
                + "0\r\n\r\n");

        assertTrue(CoopHttpMessages.isComplete(data, data.length));
        assertEquals("hello world", CoopHttpMessages.parse(data, data.length).body());
    }

    @Test
    void chunkedBodyIsIncompleteWithoutTheTerminatingChunk() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n");

        assertFalse(CoopHttpMessages.isComplete(data, data.length));
    }

    @Test
    void chunkExtensionsAreIgnored() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3;name=value\r\nabc\r\n0\r\n\r\n");

        assertEquals("abc", CoopHttpMessages.parse(data, data.length).body());
    }

    @Test
    void connectionCloseFramedBodyIsNeverCompleteButStillParses() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n<root/>");

        assertFalse(CoopHttpMessages.isComplete(data, data.length));
        assertEquals("<root/>", CoopHttpMessages.parse(data, data.length).body());
    }

    @Test
    void acceptsBareLineFeedHeaderTerminators() {
        byte[] data = bytes("HTTP/1.1 500 Internal Server Error\nContent-Length: 2\n\nno");

        CoopHttpMessages.Response response = CoopHttpMessages.parse(data, data.length);

        assertEquals(500, response.statusCode());
        assertEquals("no", response.body());
        assertEquals("2", response.header("content-length"));
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:5000/rootDesc.xml\r\n\r\n");

        CoopHttpMessages.Response response = CoopHttpMessages.parse(data, data.length);

        assertEquals("http://192.168.1.1:5000/rootDesc.xml", response.header("Location"));
    }

    @Test
    void rejectsResponseWithoutHeaderTerminator() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n");

        assertThrows(IllegalArgumentException.class, () -> CoopHttpMessages.parse(data, data.length));
    }

    @Test
    void rejectsMalformedStatusLine() {
        byte[] data = bytes("NOT-HTTP\r\n\r\n");

        assertThrows(IllegalArgumentException.class, () -> CoopHttpMessages.parse(data, data.length));
    }

    @Test
    void parsesUrlWithExplicitPort() {
        CoopHttpMessages.Url url = CoopHttpMessages.parseUrl("http://192.168.1.1:5000/rootDesc.xml");

        assertEquals("192.168.1.1", url.host());
        assertEquals(5000, url.port());
        assertEquals("/rootDesc.xml", url.path());
        assertEquals("192.168.1.1:5000", url.authority());
    }

    /** Updated for red-team C4: the host used to be "gateway.local", which is no longer accepted. */
    @Test
    void urlWithoutPortDefaultsTo80AndUrlWithoutPathDefaultsToRoot() {
        CoopHttpMessages.Url url = CoopHttpMessages.parseUrl("http://192.168.1.1");

        assertEquals("192.168.1.1", url.host());
        assertEquals(80, url.port());
        assertEquals("/", url.path());
    }

    /**
     * Every URL the mapper parses arrives in an unauthenticated LAN packet. A name in one would be
     * resolved on the campaign thread, where the resolver's timeout is a visible freeze of the game
     * that anything on the LAN can trigger.
     */
    @Test
    void c4_aUrlWithANameHostIsRejectedSoNoDnsRunsOnTheCampaignThread() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopHttpMessages.parseUrl("http://gateway.local/rootDesc.xml"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopHttpMessages.parseUrl("http://evil.example.com:5000/rootDesc.xml"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopHttpMessages.parseUrl("http://localhost:8080/x"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopHttpMessages.parseUrl("http://999.1.1.1/x"), "not a dotted quad either");

        // Literals of both families keep working, including a scoped IPv6 one.
        assertEquals("10.0.0.138", CoopHttpMessages.parseUrl("http://10.0.0.138:49152/d.xml").host());
        assertEquals("fe80::1%eth0", CoopHttpMessages.parseUrl("http://[fe80::1%eth0]:80/d.xml").host());
        assertEquals("::ffff:192.168.0.1",
                CoopHttpMessages.parseUrl("http://[::ffff:192.168.0.1]/d.xml").host());
    }

    @Test
    void parsesIpv6LiteralUrlAndRebracketsItForTheHostHeader() {
        CoopHttpMessages.Url url = CoopHttpMessages.parseUrl("http://[fe80::1]:8080/desc.xml");

        assertEquals("fe80::1", url.host());
        assertEquals(8080, url.port());
        assertEquals("[fe80::1]:8080", url.authority());
    }

    @Test
    void rejectsNonHttpUrl() {
        assertThrows(IllegalArgumentException.class, () -> CoopHttpMessages.parseUrl("ftp://192.168.1.1/x"));
    }

    /**
     * net-6: the chunk size comes from whatever answered the SSDP search, and 0x7FFFFFFF used to
     * overflow the scan cursor negative — after which the LF search clamped back to byte 0 and the
     * chunk loop repeated the same two lines forever, freezing the campaign thread the mapper ticks
     * on. Preemptive timeout because the failure mode is a hang, not a wrong answer.
     */
    @Test
    void aChunkSizeThatOverflowsTheCursorIsRejectedInsteadOfLoopingForever() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\n7FFFFFFF\r\nTransfer-Encoding: chunked\r\n\r\n7FFFFFFF\r\n");

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertFalse(CoopHttpMessages.isComplete(data, data.length)));
    }

    @Test
    void aChunkLongerThanWhatArrivedIsIncompleteRatherThanComplete() {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n7fffffff\r\nhi\r\n0\r\n\r\n");

        assertFalse(CoopHttpMessages.isComplete(data, data.length));
    }
}
