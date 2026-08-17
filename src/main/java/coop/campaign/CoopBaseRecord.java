package coop.campaign;

import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One host-authoritative dynamic base (Phase 13), as carried by the reliable TCP {@code BASE_SET}
 * message.
 *
 * <p><b>Identity is {@code (kind, systemId)}</b>: vanilla places at most one pirate base and one
 * Luddic-Path base per star system per manager, and neither the base's market id nor its station
 * entity id can cross the wire (both come from the engine's {@code Sector.genUID()} and differ per
 * client by construction). {@link #factionId()} and {@link #attr()} are mutable <em>attributes</em>
 * of that identity, not part of it — pirate bases upgrade tier over time.
 *
 * <p>{@link #attr()} is kind-specific:
 * <ul>
 *   <li>{@link Kind#PIRATE} — the {@code PirateBaseIntel.PirateBaseTier} enum name
 *       ({@code TIER_1_1MODULE} … {@code TIER_5_3MODULE}).</li>
 *   <li>{@link Kind#PATHER} — {@link #ATTR_LARGE} or {@link #ATTR_SMALL}. Luddic-Path bases have no
 *       tier; their strength is the private {@code large} boolean rolled inside the constructor.</li>
 * </ul>
 *
 * <p>The envelope JSON parser is flat (no arrays), so a whole set ships as one string: records are
 * newline separated, fields are {@code |} separated, and every field goes through
 * {@link CoopDelimited#field(String)} so a system or faction id containing a delimiter round-trips
 * exactly.
 */
public record CoopBaseRecord(Kind kind, String systemId, String factionId, String attr) {

    /** Which vanilla base manager owns this base. */
    public enum Kind {
        PIRATE,
        PATHER
    }

    public static final String ATTR_LARGE = "large";
    public static final String ATTR_SMALL = "small";

    private static final char FIELD_SEPARATOR = '|';
    private static final char RECORD_SEPARATOR = '\n';
    /** Only ever used for in-memory map keys, never encoded, so it may be a control character. */
    private static final String KEY_SEPARATOR = String.valueOf((char) 31);

    public CoopBaseRecord {
        kind = Objects.requireNonNull(kind, "kind");
        systemId = CoopDelimited.normalize(systemId);
        factionId = CoopDelimited.normalize(factionId);
        attr = CoopDelimited.normalize(attr);
    }

    /** Convenience for the Luddic-Path {@code isLarge} boolean. */
    public static CoopBaseRecord pather(String systemId, String factionId, boolean large) {
        return new CoopBaseRecord(Kind.PATHER, systemId, factionId, large ? ATTR_LARGE : ATTR_SMALL);
    }

    public static CoopBaseRecord pirate(String systemId, String factionId, String tierName) {
        return new CoopBaseRecord(Kind.PIRATE, systemId, factionId, tierName);
    }

    /** True when a {@link Kind#PATHER} record describes a large base. Meaningless for pirate bases. */
    public boolean isLarge() {
        return ATTR_LARGE.equalsIgnoreCase(attr);
    }

    /**
     * The cross-client identity key {@code (kind, systemId)} used to match a host record against a
     * base the guest already mirrors. Never encoded — the separator is a control character.
     */
    public String identityKey() {
        return kind.name() + KEY_SEPARATOR + systemId;
    }

    /** True when the two records name the same base but disagree on a replicated attribute. */
    public boolean sameIdentity(CoopBaseRecord other) {
        return other != null && kind == other.kind && systemId.equals(other.systemId);
    }

    public String encode() {
        return CoopDelimited.field(kind.name())
                + FIELD_SEPARATOR + CoopDelimited.field(systemId)
                + FIELD_SEPARATOR + CoopDelimited.field(factionId)
                + FIELD_SEPARATOR + CoopDelimited.field(attr);
    }

    public static CoopBaseRecord decode(String line) {
        Objects.requireNonNull(line, "line");
        List<String> fields = CoopDelimited.split(line);
        if (fields.size() != 4) {
            throw new IllegalArgumentException("Expected 4 base record fields, got " + fields.size());
        }
        return new CoopBaseRecord(parseKind(fields.get(0)), fields.get(1), fields.get(2), fields.get(3));
    }

    private static Kind parseKind(String raw) {
        try {
            return Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown base kind: " + raw, ex);
        }
    }

    /**
     * Encodes a whole set as one newline-joined blob. Records are emitted in {@link #encode()} sort
     * order so the payload itself is deterministic (the {@link #setHash(Collection)} is
     * order-independent regardless, but a stable payload keeps logs and packet diffs readable).
     */
    public static String encodeSet(Collection<CoopBaseRecord> records) {
        List<String> lines = encodedLines(records);
        return String.join(String.valueOf(RECORD_SEPARATOR), lines);
    }

    public static List<CoopBaseRecord> decodeSet(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        List<CoopBaseRecord> records = new ArrayList<>();
        if (encoded.isEmpty()) {
            // The empty set. A single all-blank record still encodes as "|||", so this is unambiguous.
            return records;
        }
        for (String line : encoded.split(String.valueOf(RECORD_SEPARATOR), -1)) {
            records.add(decode(line));
        }
        return records;
    }

    /**
     * Order-independent hash over the whole set; the host rebroadcasts only when this changes. Folds
     * in every field, so a tier upgrade or an {@code isLarge} flip triggers a resend just like a
     * spawn or despawn does.
     */
    public static String setHash(Collection<CoopBaseRecord> records) {
        return CoopChecksum.sha256Text(String.join("\n", encodedLines(records)));
    }

    private static List<String> encodedLines(Collection<CoopBaseRecord> records) {
        List<String> lines = new ArrayList<>();
        if (records != null) {
            for (CoopBaseRecord record : records) {
                if (record != null) {
                    lines.add(record.encode());
                }
            }
        }
        lines.sort(null);
        return lines;
    }
}
