package coop.colony;

import coop.campaign.CoopDelimited;
import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One NPC threat aimed at a player colony (Phase 24 milestone 3), as carried by the reliable TCP
 * {@code EXPEDITION_WARNING} message.
 *
 * <p><b>Identity is {@code (kind, factionId, targetMarketId)}.</b> The intel objects themselves carry
 * no cross-engine id — they are minted by host-side managers the guest has suppressed — so the
 * warning is identified by what it means rather than by what object produced it. Two simultaneous
 * punitive expeditions from the same faction against the same colony collapse into one warning, which
 * is the right answer for a countdown the player reads.
 *
 * <p>{@link #etaDays()}, {@link #status()} and {@link #goal()} are mutable <em>attributes</em> of that
 * identity. <b>The ETA is bucketed to whole days on capture</b>, and that is load-bearing rather than
 * cosmetic: the underlying value is a float that changes every frame, so an unbucketed set hash would
 * make the host rebroadcast the whole set at 60 Hz for the entire life of an expedition.
 *
 * <p>{@link #goal()} is resolved to display text <em>host-side</em> ("saturation bombardment",
 * "raid to disrupt Heavy Industry", "expedition") rather than shipped as an enum the guest maps back:
 * the two vanilla hierarchies express their objective in incompatible ways (a {@code PunExGoal} plus an
 * {@code Industry} on one side, a free-form noun on the other), and only the host can read either. An
 * unresolvable goal ships as the empty string and the guest omits the line rather than printing
 * "unknown".
 *
 * <p>The envelope JSON parser is flat (no arrays), so a whole set ships as one string: records are
 * newline separated, fields are {@code |} separated, and every field goes through
 * {@link CoopDelimited#field(String)} so a faction or market id containing a delimiter round-trips
 * exactly. Same shape as {@code CoopBaseRecord}, for the same reason.
 */
public record CoopExpeditionWarning(Kind kind, String factionId, String targetMarketId,
                                    String targetName, int etaDays, Status status, String goal) {

    /**
     * Which vanilla threat produced the warning.
     *
     * <p>The two hierarchies are disjoint in 0.98a: {@link #PUNITIVE_EXPEDITION} and
     * {@link #INSPECTION} are {@code RaidIntel} subclasses, while {@link #HOSTILE_ACTIVITY} covers
     * everything the colony-crisis system spawns, which descends from {@code FleetGroupIntel} and is
     * invisible to a {@code RaidIntel} scan. {@link #RAID} exists for a plain {@code RaidIntel} that
     * somehow resolves onto a player colony — in unmodded 0.98a it cannot, because
     * {@code PirateBaseIntel.startRaid} refuses any system containing a player market, but a mod that
     * lifts that is not a reason to drop the warning on the floor.
     */
    public enum Kind {
        PUNITIVE_EXPEDITION,
        INSPECTION,
        HOSTILE_ACTIVITY,
        RAID
    }

    /** How close the threat is. Resolution is expressed by removal from the set, not by a status. */
    public enum Status {
        /** Still travelling; {@link #etaDays()} is the estimate. */
        INBOUND,
        /** In the target system and acting. */
        ARRIVED
    }

    private static final char FIELD_SEPARATOR = '|';
    private static final char RECORD_SEPARATOR = '\n';
    private static final int FIELDS = 7;
    /** Only ever used for in-memory map keys, never encoded, so it may be a control character. */
    private static final String KEY_SEPARATOR = String.valueOf((char) 31);

    public CoopExpeditionWarning {
        kind = Objects.requireNonNull(kind, "kind");
        factionId = CoopDelimited.normalize(factionId);
        targetMarketId = CoopDelimited.normalize(targetMarketId);
        targetName = CoopDelimited.normalize(targetName);
        goal = CoopDelimited.normalize(goal).trim();
        status = status == null ? Status.INBOUND : status;
        if (etaDays < 0) {
            etaDays = 0;
        }
    }

    /**
     * A warning with no resolvable goal. Kept as a real constructor rather than a null-tolerant
     * canonical one so "there is no goal" is spelled the same way everywhere.
     */
    public CoopExpeditionWarning(Kind kind, String factionId, String targetMarketId,
                                 String targetName, int etaDays, Status status) {
        this(kind, factionId, targetMarketId, targetName, etaDays, status, "");
    }

    /**
     * The cross-client identity key. Never encoded — the separator is a control character.
     */
    public String identityKey() {
        return kind.name() + KEY_SEPARATOR + factionId + KEY_SEPARATOR + targetMarketId;
    }

    /**
     * True when the two records name the same threat but may disagree on any attribute (ETA, status,
     * target name, goal). Attribute-only differences are what the reconciler turns into an UPDATE,
     * which is why none of them are part of the identity.
     */
    public boolean sameIdentity(CoopExpeditionWarning other) {
        return other != null && kind == other.kind && factionId.equals(other.factionId)
                && targetMarketId.equals(other.targetMarketId);
    }

    public String encode() {
        return CoopDelimited.field(kind.name())
                + FIELD_SEPARATOR + CoopDelimited.field(factionId)
                + FIELD_SEPARATOR + CoopDelimited.field(targetMarketId)
                + FIELD_SEPARATOR + CoopDelimited.field(targetName)
                + FIELD_SEPARATOR + etaDays
                + FIELD_SEPARATOR + CoopDelimited.field(status.name())
                + FIELD_SEPARATOR + CoopDelimited.field(goal);
    }

    public static CoopExpeditionWarning decode(String line) {
        Objects.requireNonNull(line, "line");
        List<String> fields = CoopDelimited.split(line);
        if (fields.size() != FIELDS) {
            throw new IllegalArgumentException(
                    "Expected " + FIELDS + " expedition warning fields, got " + fields.size());
        }
        return new CoopExpeditionWarning(parseKind(fields.get(0)), fields.get(1), fields.get(2),
                fields.get(3), parseEta(fields.get(4)), parseStatus(fields.get(5)), fields.get(6));
    }

    private static Kind parseKind(String raw) {
        try {
            return Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown expedition warning kind: " + raw, ex);
        }
    }

    private static Status parseStatus(String raw) {
        try {
            return Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown expedition warning status: " + raw, ex);
        }
    }

    private static int parseEta(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed expedition warning ETA: " + raw, ex);
        }
    }

    /**
     * Buckets a live float ETA to the whole days that ride the wire. Rounds <em>up</em>: "3 days out"
     * should not become "2" the instant the countdown crosses 2.5, and a threat that is 0.2 days away
     * is still a day the player has, not zero.
     */
    public static int bucketEta(float days) {
        if (days <= 0f || Float.isNaN(days)) {
            return 0;
        }
        return (int) Math.ceil(days);
    }

    /**
     * Encodes a whole set as one newline-joined blob, in {@link #encode()} sort order so the payload
     * is byte-stable for a given set (the hash is order-independent regardless, but a stable payload
     * keeps logs and packet diffs readable).
     */
    public static String encodeSet(Collection<CoopExpeditionWarning> records) {
        return String.join(String.valueOf(RECORD_SEPARATOR), encodedLines(records));
    }

    public static List<CoopExpeditionWarning> decodeSet(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        List<CoopExpeditionWarning> records = new ArrayList<>();
        if (encoded.isEmpty()) {
            // The empty set, which is a legitimate value: it clears every mirrored warning. A single
            // all-blank record would still encode as "||||||", so this is unambiguous.
            return records;
        }
        for (String line : encoded.split(String.valueOf(RECORD_SEPARATOR), -1)) {
            records.add(decode(line));
        }
        return records;
    }

    /**
     * Order-independent hash over the whole set; the host rebroadcasts only when this changes. Folds
     * in every field, so an ETA ticking down a whole day resends just like an expedition launching or
     * resolving does, and so does a goal that changes mid-flight (a punitive expedition re-picking its
     * target industry). Nothing else moves on its own, which is what keeps the rebroadcast rare.
     */
    public static String setHash(Collection<CoopExpeditionWarning> records) {
        return CoopChecksum.sha256Text(String.join("\n", encodedLines(records)));
    }

    private static List<String> encodedLines(Collection<CoopExpeditionWarning> records) {
        List<String> lines = new ArrayList<>();
        if (records != null) {
            for (CoopExpeditionWarning record : records) {
                if (record != null) {
                    lines.add(record.encode());
                }
            }
        }
        lines.sort(null);
        return lines;
    }
}
