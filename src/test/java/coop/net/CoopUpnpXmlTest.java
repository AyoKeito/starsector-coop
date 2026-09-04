package coop.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct tests for the scanner {@link CoopUpnpDescriptor} and {@link CoopUpnpSoap} share. */
class CoopUpnpXmlTest {
    @Test
    void readsAPlainElement() {
        assertEquals("203.0.113.7", CoopUpnpXml.elementValue("<a><ip>203.0.113.7</ip></a>", "ip"));
    }

    @Test
    void trimsWhitespaceAroundTheValue() {
        assertEquals("TR-1", CoopUpnpXml.elementValue("<modelName>\n  TR-1\n</modelName>", "modelName"));
    }

    @Test
    void matchesThroughANamespacePrefixOnEitherTag() {
        assertEquals("718", CoopUpnpXml.elementValue("<u:errorCode>718</u:errorCode>", "errorCode"));
        assertEquals("Router", CoopUpnpXml.elementValue("<dev:friendlyName>Router</dev:friendlyName>",
                "friendlyName"));
    }

    @Test
    void matchesTheLocalNameCaseInsensitively() {
        assertEquals("/ctl", CoopUpnpXml.elementValue("<CONTROLURL>/ctl</CONTROLURL>", "controlURL"));
    }

    @Test
    void attributesOnTheOpeningTagDoNotHideTheElement() {
        assertEquals("Attr Router",
                CoopUpnpXml.elementValue("<friendlyName xml:lang=\"en\">Attr Router</friendlyName>", "friendlyName"));
    }

    @Test
    void aSelfClosingElementIsPresentButEmpty() {
        assertEquals("", CoopUpnpXml.elementValue("<a><friendlyName/></a>", "friendlyName"));
        assertEquals("", CoopUpnpXml.elementValue("<a><friendlyName /></a>", "friendlyName"));
    }

    @Test
    void anEmptyElementPairIsAlsoEmpty() {
        assertEquals("", CoopUpnpXml.elementValue("<NewRemoteHost></NewRemoteHost>", "NewRemoteHost"));
    }

    @Test
    void anOpeningTagWithNoCloseTagReadsAsNull() {
        assertNull(CoopUpnpXml.elementValue("<a><ip>203.0.113.7</a>", "ip"));
    }

    @Test
    void anAbsentElementAndANullDocumentBothReadAsNull() {
        assertNull(CoopUpnpXml.elementValue("<a><other>x</other></a>", "ip"));
        assertNull(CoopUpnpXml.elementValue(null, "ip"));
    }

    @Test
    void theXmlDeclarationAndCommentsAreNotMistakenForOpeningTags() {
        assertEquals("1.2.3.4",
                CoopUpnpXml.elementValue("<?xml version=\"1.0\"?><!-- ip --><ip>1.2.3.4</ip>", "ip"));
    }

    @Test
    void fromSkipsPastAnEarlierMatchToTheNextOne() {
        String xml = "<a><ip>first</ip><ip>second</ip></a>";

        assertEquals("first", CoopUpnpXml.elementValue(xml, "ip", 0));
        assertEquals("second", CoopUpnpXml.elementValue(xml, "ip", xml.indexOf("</ip>")));
        assertNull(CoopUpnpXml.elementValue(xml, "ip", xml.length()));
        assertEquals("first", CoopUpnpXml.elementValue(xml, "ip", -5));
    }

    /**
     * net-31: {@code "İ".toLowerCase(ROOT)} is two chars, so a scanner that indexes a lower-cased
     * copy shifts every later offset by one and returns a value with closing-tag debris attached.
     */
    @Test
    void aLengthChangingCharacterDoesNotShiftLaterExtractions() {
        String xml = "<a><name>İnternet Kutusu</name><ip>203.0.113.7</ip></a>";

        assertEquals("İnternet Kutusu", CoopUpnpXml.elementValue(xml, "name"));
        assertEquals("203.0.113.7", CoopUpnpXml.elementValue(xml, "ip"));
    }

    @Test
    void closeTagIndexFindsTheMatchingCloseTagThroughAPrefix() {
        String xml = "<u:body><u:ip>1.2.3.4</u:ip></u:body>";

        assertEquals(xml.indexOf("</u:ip>"), CoopUpnpXml.closeTagIndex(xml, 0, "ip"));
        assertEquals(-1, CoopUpnpXml.closeTagIndex(xml, 0, "missing"));
    }

    @Test
    void bareNameStripsPrefixAttributesAndTheSelfClosingSlash() {
        assertEquals("service", CoopUpnpXml.bareName("dev:service"));
        assertEquals("friendlyname", CoopUpnpXml.bareName("friendlyname xml:lang=\"en\""));
        assertEquals("ip", CoopUpnpXml.bareName("ip/"));
        assertEquals("ip", CoopUpnpXml.bareName("u:ip\txmlns:u=\"urn:x\""));
    }

    @Test
    void isOpeningTagForRejectsClosingDeclarationAndCommentTags() {
        assertTrue(CoopUpnpXml.isOpeningTagFor("u:ip xmlns:u=\"urn:x\"", "ip"));
        assertFalse(CoopUpnpXml.isOpeningTagFor("/ip", "ip"));
        assertFalse(CoopUpnpXml.isOpeningTagFor("?xml version=\"1.0\"?", "xml"));
        assertFalse(CoopUpnpXml.isOpeningTagFor("!-- ip --", "ip"));
        assertFalse(CoopUpnpXml.isOpeningTagFor("", "ip"));
    }
}
