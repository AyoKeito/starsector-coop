package coop.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSsdpMessagesTest {
    @Test
    void searchRequestMatchesTheSsdpWireFormat() {
        String request = new String(CoopSsdpMessages.searchRequest(CoopSsdpMessages.ST_IGD_V1),
                StandardCharsets.UTF_8);

        assertEquals("M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"
                + "\r\n", request);
    }

    @Test
    void searchRequestCarriesTheRequestedSearchTarget() {
        String request = new String(CoopSsdpMessages.searchRequest(CoopSsdpMessages.ST_IGD_V2),
                StandardCharsets.UTF_8);

        assertTrue(request.contains("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n"));
    }

    @Test
    void parsesHeadersCaseInsensitivelyFromACrlfResponse() {
        Map<String, String> headers = CoopSsdpMessages.parseHeaders("HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "Location: http://192.168.1.1:5000/rootDesc.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"
                + "USN: uuid:abcd::urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n");

        assertEquals("http://192.168.1.1:5000/rootDesc.xml", CoopSsdpMessages.location(headers));
        assertEquals("max-age=1800", headers.get("cache-control"));
        assertTrue(CoopSsdpMessages.isInternetGatewayDevice(headers));
    }

    @Test
    void parsesHeadersFromABareLineFeedResponse() {
        Map<String, String> headers = CoopSsdpMessages.parseHeaders("HTTP/1.1 200 OK\n"
                + "LOCATION: http://10.0.0.138:49152/desc.xml\n"
                + "ST: urn:schemas-upnp-org:service:WANPPPConnection:1\n");

        assertEquals("http://10.0.0.138:49152/desc.xml", CoopSsdpMessages.location(headers));
        assertTrue(CoopSsdpMessages.isInternetGatewayDevice(headers));
    }

    @Test
    void acceptsAReplyThatOnlyNamesTheGatewayInItsUsn() {
        Map<String, String> headers = CoopSsdpMessages.parseHeaders("HTTP/1.1 200 OK\r\n"
                + "LOCATION: http://192.168.0.1/igd.xml\r\n"
                + "ST: upnp:rootdevice\r\n"
                + "USN: uuid:x::urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n");

        assertTrue(CoopSsdpMessages.isInternetGatewayDevice(headers));
    }

    @Test
    void rejectsNonGatewayDevices() {
        Map<String, String> headers = CoopSsdpMessages.parseHeaders("HTTP/1.1 200 OK\r\n"
                + "LOCATION: http://192.168.1.44:8060/\r\n"
                + "ST: roku:ecp\r\n"
                + "USN: uuid:roku:ecp:1GU48T017973\r\n");

        assertFalse(CoopSsdpMessages.isInternetGatewayDevice(headers));
    }

    @Test
    void reportsMissingLocationAsNull() {
        Map<String, String> headers = CoopSsdpMessages.parseHeaders("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n");

        assertNull(CoopSsdpMessages.location(headers));
    }
}
