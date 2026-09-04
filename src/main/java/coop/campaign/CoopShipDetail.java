package coop.campaign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static coop.util.CoopText.requireText;

/**
 * Everything that makes one market ship listing <em>that</em> ship rather than a pristine copy of its
 * base variant (Phase 12c gaps 2a + 2b).
 *
 * <p>Capturing {@code getHullVariantId()} alone loses two independent things. D-mods are not part of
 * the variant id: {@code DModManager.addDMods} writes them as <em>perma-mods</em> on a REFIT-source
 * variant ({@code removeSuppressedMod} + {@code addPermaMod}), and {@code DModManager.setDHull}
 * additionally swaps the hull spec to the {@code _dhull} variety. And combat readiness lives on the
 * fleet member's repair tracker, not the variant at all. A guest that rebuilt a listing from the
 * variant id got a clean hull at full CR, priced by its own engine accordingly — a visible,
 * exploitable divergence.
 *
 * <h2>Wire format (three nesting levels)</h2>
 *
 * <p>The record is one {@code |}-separated {@link CoopDelimited} line, carried inside a single field
 * of the enclosing {@link CoopMarketSync} stock line (level 1) or of a {@code MARKET_TXN} payload.
 * {@code CoopDelimited.field}/{@code split} round-trip exactly one nesting level, so:
 *
 * <ul>
 *   <li><b>level 1</b> — the stock line, fields joined by {@code |};</li>
 *   <li><b>level 2</b> — this record, fields joined by {@code |}, the whole thing escaped once by the
 *       level-1 {@code field()} call;</li>
 *   <li><b>level 3</b> — the inner lists/maps, which {@code |} can no longer separate. Elements are
 *       joined by {@code ,} and map entries written {@code key=value}, with a local escape of
 *       {@code \} &rarr; {@code \\}, {@code ,} &rarr; {@code \c} and {@code =} &rarr; {@code \e}.
 *       That escape nests cleanly under the two {@code CoopDelimited} passes above because each pass
 *       escapes backslashes it finds and unescapes them symmetrically.</li>
 * </ul>
 *
 * <p><b>Accepted gap: multi-module ships.</b> Station/multi-module hulls carry their modules as
 * separate variants referenced by the parent's module slots; this codec captures the parent variant
 * only, so a modular hull arrives with pristine modules. Recursion is deliberately not attempted —
 * see {@code docs/starsector-runtime-limitations.md}.
 */
public record CoopShipDetail(String memberId,
                             String shipName,
                             String baseVariantId,
                             String hullSpecId,
                             float baseCR,
                             int vents,
                             int caps,
                             List<String> permaMods,
                             List<String> sMods,
                             List<String> sModdedBuiltIns,
                             List<String> refitMods,
                             List<String> suppressedMods,
                             Map<String, String> weapons,
                             Map<String, String> wings) {

    /** Number of {@code |}-separated fields in the encoded form. */
    public static final int FIELD_COUNT = 14;

    public CoopShipDetail {
        memberId = requireText(memberId, "memberId");
        shipName = CoopDelimited.normalize(shipName);
        baseVariantId = requireText(baseVariantId, "baseVariantId");
        hullSpecId = CoopDelimited.normalize(hullSpecId);
        permaMods = copyList(permaMods);
        sMods = copyList(sMods);
        sModdedBuiltIns = copyList(sModdedBuiltIns);
        refitMods = copyList(refitMods);
        suppressedMods = copyList(suppressedMods);
        weapons = copyMap(weapons);
        wings = copyMap(wings);
    }

    public String encode() {
        return CoopDelimited.field(memberId)
                + '|' + CoopDelimited.field(shipName)
                + '|' + CoopDelimited.field(baseVariantId)
                + '|' + CoopDelimited.field(hullSpecId)
                + '|' + floatText(baseCR)
                + '|' + vents
                + '|' + caps
                + '|' + CoopDelimited.field(joinList(permaMods))
                + '|' + CoopDelimited.field(joinList(sMods))
                + '|' + CoopDelimited.field(joinList(sModdedBuiltIns))
                + '|' + CoopDelimited.field(joinList(refitMods))
                + '|' + CoopDelimited.field(joinList(suppressedMods))
                + '|' + CoopDelimited.field(joinMap(weapons))
                + '|' + CoopDelimited.field(joinMap(wings));
    }

    public static CoopShipDetail decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        List<String> f = CoopDelimited.split(encoded);
        if (f.size() != FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + FIELD_COUNT + " ship detail fields, got " + f.size());
        }
        return new CoopShipDetail(f.get(0), f.get(1), f.get(2), f.get(3),
                Float.parseFloat(f.get(4).trim()),
                Integer.parseInt(f.get(5).trim()),
                Integer.parseInt(f.get(6).trim()),
                splitList(f.get(7)), splitList(f.get(8)), splitList(f.get(9)),
                splitList(f.get(10)), splitList(f.get(11)),
                splitMap(f.get(12)), splitMap(f.get(13)));
    }

    // ---- Level-3 encoding ----------------------------------------------------------------------

    static String joinList(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(cell(value));
        }
        return out.toString();
    }

    static List<String> splitList(String encoded) {
        List<String> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String raw : splitCells(encoded)) {
            if (!raw.isEmpty()) {
                out.add(uncell(raw));
            }
        }
        return out;
    }

    static String joinMap(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(cell(entry.getKey())).append('=').append(cell(entry.getValue()));
        }
        return out.toString();
    }

    static Map<String, String> splitMap(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String pair : splitCells(encoded)) {
            if (pair.isEmpty()) {
                continue;
            }
            int split = indexOfUnescaped(pair, '=');
            if (split < 0) {
                throw new IllegalArgumentException("Malformed key=value cell: " + pair);
            }
            out.put(uncell(pair.substring(0, split)), uncell(pair.substring(split + 1)));
        }
        return out;
    }

    /** Escapes one level-3 element so {@code ,} and {@code =} stay structural. */
    private static String cell(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ',' -> out.append("\\c");
                case '=' -> out.append("\\e");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String uncell(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                switch (c) {
                    case '\\' -> out.append('\\');
                    case 'c' -> out.append(',');
                    case 'e' -> out.append('=');
                    default -> out.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Splits on {@code ,} that are not part of an escape sequence; cells stay escaped. */
    private static List<String> splitCells(String encoded) {
        List<String> cells = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (escaped) {
                token.append(c);
                escaped = false;
            } else if (c == '\\') {
                token.append(c);
                escaped = true;
            } else if (c == ',') {
                cells.add(token.toString());
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        cells.add(token.toString());
        // Cells come back still escaped: list values are unescaped by the caller, and map cells have
        // to be split on their unescaped '=' before either half can be unescaped.
        return cells;
    }

    private static int indexOfUnescaped(String text, char target) {
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> copyList(List<String> values) {
        List<String> copy = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    copy.add(value);
                }
            }
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> copyMap(Map<String, String> values) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Locale-independent, so a comma-decimal locale cannot corrupt the wire format. */
    private static String floatText(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

}
