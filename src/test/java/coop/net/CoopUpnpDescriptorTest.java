package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoopUpnpDescriptorTest {
    private static final String DESCRIPTOR_URL = "http://192.168.1.1:5000/rootDesc.xml";

    private static String descriptor(String urlBase, String serviceType, String controlUrl) {
        return "<?xml version=\"1.0\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + (urlBase == null ? "" : "<URLBase>" + urlBase + "</URLBase>")
                + "<device>"
                + "<deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>"
                + "<friendlyName>Test Router</friendlyName>"
                + "<modelName>TR-9000</modelName>"
                + "<serviceList>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>"
                + "<controlURL>/ctl/L3F</controlURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>" + serviceType + "</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:WANConn</serviceId>"
                + "<controlURL>" + controlUrl + "</controlURL>"
                + "<eventSubURL>/evt/WANConn</eventSubURL>"
                + "</service>"
                + "</serviceList>"
                + "</device>"
                + "</root>";
    }

    @Test
    void readsFriendlyNameAndModelName() {
        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(
                descriptor(null, "urn:schemas-upnp-org:service:WANIPConnection:1", "/ctl/IPConn"),
                DESCRIPTOR_URL);

        assertEquals("Test Router", parsed.friendlyName());
        assertEquals("TR-9000", parsed.modelName());
        assertEquals("Test Router (TR-9000)", parsed.displayName());
    }

    @Test
    void resolvesRootRelativeControlUrlAgainstTheDescriptorUrl() {
        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(
                descriptor(null, "urn:schemas-upnp-org:service:WANIPConnection:1", "/ctl/IPConn"),
                DESCRIPTOR_URL);

        assertNotNull(parsed.service());
        assertEquals("http://192.168.1.1:5000/ctl/IPConn", parsed.service().controlUrl());
    }

    @Test
    void urlBaseOverridesTheDescriptorUrlWhenPresent() {
        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(
                descriptor("http://192.168.1.1:49152/", "urn:schemas-upnp-org:service:WANIPConnection:1",
                        "/upnp/control/WANIPConn1"),
                DESCRIPTOR_URL);

        assertEquals("http://192.168.1.1:49152/upnp/control/WANIPConn1", parsed.service().controlUrl());
    }

    @Test
    void resolvesBaseRelativeControlUrlAgainstTheContainingDirectory() {
        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(
                descriptor(null, "urn:schemas-upnp-org:service:WANIPConnection:1", "ctl/IPConn"),
                "http://192.168.1.1:5000/upnp/rootDesc.xml");

        assertEquals("http://192.168.1.1:5000/upnp/ctl/IPConn", parsed.service().controlUrl());
    }

    @Test
    void keepsAnAlreadyAbsoluteControlUrl() {
        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(
                descriptor(null, "urn:schemas-upnp-org:service:WANIPConnection:1",
                        "http://192.168.1.1:80/soap"),
                DESCRIPTOR_URL);

        assertEquals("http://192.168.1.1:80/soap", parsed.service().controlUrl());
    }

    @Test
    void picksTheWanPppConnectionServiceOnADslGateway() {
        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(
                descriptor(null, "urn:schemas-upnp-org:service:WANPPPConnection:1", "/ctl/PPPConn"),
                DESCRIPTOR_URL);

        assertEquals("urn:schemas-upnp-org:service:WANPPPConnection:1", parsed.service().serviceType());
        assertEquals("http://192.168.1.1:5000/ctl/PPPConn", parsed.service().controlUrl());
    }

    @Test
    void picksTheFirstWanServiceInDocumentOrderWhenBothArePresent() {
        String xml = "<root><device><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>"
                + "<controlURL>/ppp</controlURL></service>"
                + "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
                + "<controlURL>/ip</controlURL></service>"
                + "</serviceList></device></root>";

        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(xml, DESCRIPTOR_URL);

        assertEquals("urn:schemas-upnp-org:service:WANPPPConnection:1", parsed.service().serviceType());
        assertEquals("http://192.168.1.1:5000/ppp", parsed.service().controlUrl());
    }

    @Test
    void reportsNoServiceWhenTheDeviceExposesNoWanConnection() {
        String xml = "<root><device><friendlyName>Printer</friendlyName><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:PrintBasic:1</serviceType>"
                + "<controlURL>/ctl/print</controlURL></service>"
                + "</serviceList></device></root>";

        CoopUpnpDescriptor.Descriptor parsed = CoopUpnpDescriptor.parse(xml, DESCRIPTOR_URL);

        assertNull(parsed.service());
        assertEquals("Printer", parsed.displayName());
    }

    @Test
    void reportsNoServiceWhenTheWanServiceHasNoControlUrl() {
        String xml = "<root><device><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
                + "</service></serviceList></device></root>";

        assertNull(CoopUpnpDescriptor.parse(xml, DESCRIPTOR_URL).service());
    }

    @Test
    void displayNameFallsBackToWhicheverNameThePresentedDescriptorHas() {
        String xml = "<root><device><modelName>TR-9000</modelName><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
                + "<controlURL>/ip</controlURL></service></serviceList></device></root>";

        assertEquals("TR-9000", CoopUpnpDescriptor.parse(xml, DESCRIPTOR_URL).displayName());
    }
}
