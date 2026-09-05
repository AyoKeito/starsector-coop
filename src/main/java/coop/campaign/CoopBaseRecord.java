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
 * entity id can be an identity (both come from the engine's {@code Sector.genUID()} and differ per
 * client by construction). {@link #factionId()} and {@link #attr()} are mutable <em>attributes</em>
 * of that identity, not part of it — pirate bases upgrade tier over time.
 *
 * <p><b>{@link #marketId()} is provenance, not identity and not an attribute</b> (Phase 32 addition
 * A). It carries the id the <em>emitting</em> engine's market has, so the guest can pair the host's
 * base with its own copy and fill {@link CoopMarketIds}; without that pairing every
 * {@code MARKET_OPEN} / {@code MARKET_SNAPSHOT} / {@code MARKET_TXN} naming a hidden base failed to
 * resolve on the far side and the base's trade screen opened unsynced. It is deliberately invisible
 * to {@link CoopBaseAuthority#plan}: the guest's own local record carries the guest's own id, so
 * treating a difference as an attribute change would make every reconcile issue a pointless UPDATE
 * forever. Empty when the base has no market yet.
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
public record CoopBaseRecord(Kind kind, String systemId, String factionId, String attr,
                             String marketId) {

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
        marketId = CoopDelimited.normalize(marketId);
    }

    /** Convenience for the Luddic-Path {@code isLarge} boolean, with no market id. */
    public static CoopBaseRecord pather(String systemId, String factionId, boolean large) {
        return pather(systemId, factionId, large, "");
    }

    public static CoopBaseRecord pather(String systemId, String factionId, boolean large,
                                        String marketId) {
        return new CoopBaseRecord(Kind.PATHER, systemId, factionId,
                large ? ATTR_LARGE : ATTR_SMALL, marketId);
    }

    public static CoopBaseRecord pirate(String systemId, String factionId, String tierName) {
        return pirate(systemId, factionId, tierName, "");
    }

    public static CoopBaseRecord pirate(String systemId, String factionId, String tierName,
                                        String marketId) {
        return new CoopBaseRecord(Kind.PIRATE, systemId, factionId, tierName, marketId);
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
                + FIELD_SEPARATOR + CoopDelimited.field(attr)
                + FIELD_SEPARATOR + CoopDelimited.field(marketId);
    }

    public static CoopBaseRecord decode(String line) {
        Objects.requireNonNull(line, "line");
        List<String> fields = CoopDelimited.split(line);
        if (fields.size() != 5) {
            throw new IllegalArgumentException("Expected 5 base record fields, got " + fields.size());
        }
        return new CoopBaseRecord(parseKind(fields.get(0)), fields.get(1), fields.get(2),
                fields.get(3), fields.get(4));
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
            // The empty set. A single all-blank record still encodes as "||||", so this is
            // unambiguous.
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
     *
     * <p>{@link #marketId()} is folded in with the rest and costs nothing: a base's market is minted
     * once in its constructor and never replaced, so on the host the id is stable for the base's
     * whole life and adds no rebroadcast churn.
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
