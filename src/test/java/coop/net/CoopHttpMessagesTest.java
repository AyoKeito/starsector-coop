package coop.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void urlWithoutPortDefaultsTo80AndUrlWithoutPathDefaultsToRoot() {
        CoopHttpMessages.Url url = CoopHttpMessages.parseUrl("http://gateway.local");

        assertEquals("gateway.local", url.host());
        assertEquals(80, url.port());
        assertEquals("/", url.path());
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
}
