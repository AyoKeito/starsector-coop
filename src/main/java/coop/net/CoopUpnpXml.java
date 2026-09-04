package coop.net;

/**
 * The one XML element scanner the UPnP code uses, shared by {@link CoopUpnpDescriptor} (device
 * descriptors) and {@link CoopUpnpSoap} (SOAP responses and fault bodies).
 *
 * <p>Why a string scan and not an XML parser: the mod cannot bundle an XML library and the JDK's
 * parsers all want {@code java.io} streams, which the Starsector script classloader blocks. The
 * documents involved are tiny and rigid, so a scan is sufficient.
 *
 * <p><b>Every index here is an index into {@code xml} itself.</b> Both scanners used to search a
 * lower-cased copy and apply those offsets to the original (red-team net-31), which silently
 * misaligns as soon as lower-casing changes the string's length — {@code "İ"} (U+0130) folds to two
 * chars — and then extracts a value with a fragment of the closing tag glued on, or misses the
 * element entirely. Case-insensitivity here comes from {@code equalsIgnoreCase} on the tag name
 * instead, which never moves an index.
 *
 * <p>What the scan tolerates:
 * <ul>
 *   <li>namespace prefixes: {@code <u:errorCode>} and {@code <dev:friendlyName>} match the bare
 *       local name;</li>
 *   <li>attributes on the opening tag: {@code <friendlyName xml:lang="en">};</li>
 *   <li>whitespace inside the tag, and around the value (the returned text is trimmed);</li>
 *   <li>the XML declaration and comments, which are never mistaken for an opening tag.</li>
 * </ul>
 *
 * <p>What it deliberately does not do: nesting. The close tag paired with an opening tag is the
 * first later close tag with that local name, so a same-named child would end the parent's value
 * early. No UPnP document we read nests a repeated name.
 *
 * <p>Union note: the two callers' private scanners differed on the self-closing case. The
 * descriptor's returned {@code ""} for {@code <friendlyName/>} ("present but empty"); the SOAP one
 * matched the tag and then returned {@code null} because no close tag exists. This class keeps the
 * descriptor's answer, {@code ""}, for both. Nothing in the SOAP callers is sensitive to it —
 * {@code errorDescription} maps {@code null} to {@code ""} anyway and {@code errorCode} reports
 * {@code -1} for an unparseable value either way.
 */
final class CoopUpnpXml {
    private CoopUpnpXml() {
    }

    /** Text of the first {@code <name>} element in {@code xml}, or {@code null}. */
    static String elementValue(String xml, String name) {
        return elementValue(xml, name, 0);
    }

    /**
     * Text of the first {@code <name>} element at or after {@code from}, trimmed.
     *
     * @return {@code ""} for an empty or self-closing element; {@code null} when {@code xml} is
     *         {@code null}, when no opening tag matches, or when the opening tag has no close tag
     */
    static String elementValue(String xml, String name, int from) {
        if (xml == null) {
            return null;
        }
        int cursor = Math.max(0, from);
        while (true) {
            int open = xml.indexOf('<', cursor);
            if (open < 0) {
                return null;
            }
            int tagEnd = xml.indexOf('>', open);
            if (tagEnd < 0) {
                return null;
            }
            String tag = xml.substring(open + 1, tagEnd).trim();
            if (isOpeningTagFor(tag, name)) {
                if (tag.endsWith("/")) {
                    return ""; // <friendlyName/>: present but empty.
                }
                int close = closeTagIndex(xml, tagEnd + 1, name);
                return close < 0 ? null : xml.substring(tagEnd + 1, close).trim();
            }
            cursor = tagEnd + 1;
        }
    }

    /** Index of the {@code <} of the matching {@code </name>} at or after {@code from}, or {@code -1}. */
    static int closeTagIndex(String xml, int from, String name) {
        int cursor = Math.max(0, from);
        while (true) {
            cursor = xml.indexOf("</", cursor);
            if (cursor < 0) {
                return -1;
            }
            int tagEnd = xml.indexOf('>', cursor);
            if (tagEnd < 0) {
                return -1;
            }
            if (bareName(xml.substring(cursor + 2, tagEnd).trim()).equalsIgnoreCase(name)) {
                return cursor;
            }
            cursor = tagEnd + 1;
        }
    }

    /** True for an opening (not closing, not declaration, not comment) tag whose local name is {@code name}. */
    static boolean isOpeningTagFor(String tag, String name) {
        if (tag.isEmpty() || tag.charAt(0) == '/' || tag.charAt(0) == '?' || tag.charAt(0) == '!') {
            return false;
        }
        return bareName(tag).equalsIgnoreCase(name);
    }

    /** Strips a namespace prefix, attributes and any self-closing slash from a tag body. */
    static String bareName(String tag) {
        String name = tag.endsWith("/") ? tag.substring(0, tag.length() - 1).trim() : tag;
        int cut = name.length();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                cut = i;
                break;
            }
        }
        name = name.substring(0, cut);
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
