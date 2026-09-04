package coop.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopUpnpSoapTest {
    private static final String SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:1";
    private static final String CONTROL_URL = "http://192.168.1.1:5000/ctl/IPConn";

    @Test
    void getExternalIpAddressEnvelopeHasNoArguments() {
        String body = CoopUpnpSoap.getExternalIpAddressBody(SERVICE);

        assertEquals("<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>"
                + "<u:GetExternalIPAddress xmlns:u=\"" + SERVICE + "\">"
                + "</u:GetExternalIPAddress>"
                + "</s:Body>"
                + "</s:Envelope>", body);
    }

    @Test
    void addPortMappingEnvelopeCarriesEveryRequiredArgumentInOrder() {
        String body = CoopUpnpSoap.addPortMappingBody(SERVICE, "TCP", 27015, 27015,
                "192.168.1.5", "Starsector coop", 3600);

        assertTrue(body.contains("<u:AddPortMapping xmlns:u=\"" + SERVICE + "\">"), body);
        assertTrue(body.contains("<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>27015</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>27015</NewInternalPort>"
                + "<NewInternalClient>192.168.1.5</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>Starsector coop</NewPortMappingDescription>"
                + "<NewLeaseDuration>3600</NewLeaseDuration>"), body);
    }

    @Test
    void addPortMappingNormalizesProtocolCase() {
        String body = CoopUpnpSoap.addPortMappingBody(SERVICE, "udp", 1, 1, "10.0.0.2", "x", 0);

        assertTrue(body.contains("<NewProtocol>UDP</NewProtocol>"));
        assertTrue(body.contains("<NewLeaseDuration>0</NewLeaseDuration>"));
    }

    @Test
    void addPortMappingRejectsAnUnknownProtocol() {
        assertThrows(IllegalArgumentException.class,
                () -> CoopUpnpSoap.addPortMappingBody(SERVICE, "SCTP", 1, 1, "10.0.0.2", "x", 0));
    }

    @Test
    void deletePortMappingEnvelopeCarriesOnlyTheThreeIdentifyingArguments() {
        String body = CoopUpnpSoap.deletePortMappingBody(SERVICE, "UDP", 27015);

        assertTrue(body.contains("<u:DeletePortMapping xmlns:u=\"" + SERVICE + "\">"), body);
        assertTrue(body.contains("<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>27015</NewExternalPort>"
                + "<NewProtocol>UDP</NewProtocol>"), body);
    }

    @Test
    void httpRequestCarriesSoapActionHostAndContentLength() {
        String body = CoopUpnpSoap.getExternalIpAddressBody(SERVICE);
        String request = new String(CoopUpnpSoap.httpRequest(CONTROL_URL, SERVICE, "GetExternalIPAddress", body),
                StandardCharsets.UTF_8);

        assertTrue(request.startsWith("POST /ctl/IPConn HTTP/1.1\r\n"), request);
        assertTrue(request.contains("HOST: 192.168.1.1:5000\r\n"), request);
        assertTrue(request.contains("SOAPACTION: \"" + SERVICE + "#GetExternalIPAddress\"\r\n"), request);
        assertTrue(request.contains("CONTENT-LENGTH: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"),
                request);
        assertTrue(request.contains("CONNECTION: close\r\n"), request);
        assertTrue(request.endsWith("\r\n\r\n" + body), request);
    }

    @Test
    void httpGetAsksForConnectionCloseSoTheReaderCanReadToEndOfStream() {
        String request = new String(CoopUpnpSoap.httpGet("http://192.168.1.1:5000/rootDesc.xml"),
                StandardCharsets.UTF_8);

        assertTrue(request.startsWith("GET /rootDesc.xml HTTP/1.1\r\n"), request);
        assertTrue(request.contains("HOST: 192.168.1.1:5000\r\n"), request);
        assertTrue(request.contains("CONNECTION: close\r\n"), request);
    }

    @Test
    void readsExternalIpAddressFromANamespacedResponse() {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><u:GetExternalIPAddressResponse xmlns:u=\"" + SERVICE + "\">"
                + "<NewExternalIPAddress>203.0.113.7</NewExternalIPAddress>"
                + "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>";

        assertEquals("203.0.113.7", CoopUpnpSoap.externalIpAddress(body));
        assertEquals(-1, CoopUpnpSoap.errorCode(body));
    }

    @Test
    void readsUpnpErrorCodeAndDescriptionFromAFaultBody() {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>718</errorCode><errorDescription>ConflictInMappingEntry</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";

        assertEquals(CoopUpnpSoap.ERROR_CONFLICT_IN_MAPPING_ENTRY, CoopUpnpSoap.errorCode(body));
        assertEquals("ConflictInMappingEntry", CoopUpnpSoap.errorDescription(body));
        assertNull(CoopUpnpSoap.externalIpAddress(body));
    }

    @Test
    void readsThePermanentLeaseErrorCode() {
        String body = "<detail><UPnPError><errorCode>725</errorCode>"
                + "<errorDescription>OnlyPermanentLeasesSupported</errorDescription></UPnPError></detail>";

        assertEquals(CoopUpnpSoap.ERROR_ONLY_PERMANENT_LEASES, CoopUpnpSoap.errorCode(body));
    }

    @Test
    void missingErrorCodeReadsAsMinusOne() {
        assertEquals(-1, CoopUpnpSoap.errorCode("<s:Envelope><s:Body/></s:Envelope>"));
        assertEquals(-1, CoopUpnpSoap.errorCode(null));
        assertEquals("", CoopUpnpSoap.errorDescription("<x/>"));
    }

    /**
     * net-31, SOAP side: the scan searched a lower-cased copy and applied those offsets to the
     * original. {@code "İ".toLowerCase(ROOT)} is two chars, so every index past such a character was
     * shifted and the fault below reported no error code at all — the mapper would have treated a
     * 718 conflict as an unexplained failure instead of checking who owns the port.
     */
    @Test
    void net31_aFaultCarryingALengthChangingCharacterStillParses() {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><s:Fault><faultcode>s:Client</faultcode>"
                + "<faultstring>İstek reddedildi</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>718</errorCode><errorDescription>ConflictInMappingEntry</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";

        assertEquals(CoopUpnpSoap.ERROR_CONFLICT_IN_MAPPING_ENTRY, CoopUpnpSoap.errorCode(body));
        assertEquals("ConflictInMappingEntry", CoopUpnpSoap.errorDescription(body));
    }

    @Test
    void net31_anIpResponseAfterALengthChangingCharacterStillParses() {
        String body = "<s:Envelope><s:Body><u:GetExternalIPAddressResponse xmlns:u=\"" + SERVICE + "\">"
                + "<NewNote>İnternet</NewNote>"
                + "<NewExternalIPAddress>203.0.113.7</NewExternalIPAddress>"
                + "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>";

        assertEquals("203.0.113.7", CoopUpnpSoap.externalIpAddress(body));
    }

    @Test
    void escapesXmlSpecialCharactersInTheMappingDescription() {
        String body = CoopUpnpSoap.addPortMappingBody(SERVICE, "TCP", 1, 1, "10.0.0.2", "a<b>&\"c\"", 60);

        assertTrue(body.contains("<NewPortMappingDescription>a&lt;b&gt;&amp;&quot;c&quot;</NewPortMappingDescription>"),
                body);
    }
}
