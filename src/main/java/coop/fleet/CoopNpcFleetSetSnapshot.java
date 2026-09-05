package coop.fleet;

import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The host's full authoritative set of non-player campaign fleets, carried by the reliable TCP
 * {@code NPC_FLEET_SET} message (Phase 9). The whole set is rebroadcast whenever {@link #setHash()}
 * changes, and — since 2026-09-05 — also when {@link #computeHealthHash} changes, at most once every
 * {@code CoopNpcFleetReplicator.HEALTH_RESYNC_INTERVAL_MILLIS}; the guest reconciles against it
 * idempotently (add fleets present here but missing locally, dispose mirrors absent here). Full-set
 * rebroadcast is chosen for v1 because it is self-correcting (no add/remove delta ordering or
 * lost-packet bugs).
 *
 * <p>{@link #setHash()} is order-independent and folds in each fleet's identity, name, faction,
 * location, transponder state, roster ({@code fleetHash}) and action text
 * ({@code aiAssignmentSummary}) so it flips on spawn/despawn, rename, faction change, system jump,
 * transponder toggle, roster edit, or tooltip-text change — everything the 1 Hz set is the sole
 * carrier of and the guest must re-apply rather than merely interpolate.
 *
 * <p>Transponder state is in the hash because it is the only place it travels: the 10 Hz
 * {@code NPC_FLEET_MOTION} datagram does not carry it, and on the guest a mirror's transponder flag is
 * the difference between full faction identification across the entire detection range and
 * identification only inside 10% of it ({@code BaseCampaignEntity.getVisibilityLevelTo}, engine line
 * 1206/1217). Without it a mirror kept whatever transponder state it had at the last structural change
 * and rendered at the wrong tier indefinitely.
 *
 * <p>Encoding packs each (multi-line) per-fleet encoding onto a single line via
 * {@link CoopFleetCodec#escape}, joined by newlines, so the flat envelope can carry the whole set as
 * one body string.
 */
public record CoopNpcFleetSetSnapshot(List<CoopNpcFleetSnapshot> fleets, String setHash) {

    public CoopNpcFleetSetSnapshot {
        fleets = fleets == null ? List.of() : List.copyOf(fleets);
        setHash = setHash == null ? "" : setHash;
    }

    /** Builds a set, computing the order-independent {@link #setHash()}. */
    public static CoopNpcFleetSetSnapshot create(List<CoopNpcFleetSnapshot> fleets) {
        List<CoopNpcFleetSnapshot> safe = fleets == null ? List.of() : fleets;
        return new CoopNpcFleetSetSnapshot(safe, computeSetHash(safe));
    }

    /**
     * Order-independent hash over each fleet's identity/name/faction/location/transponder/roster/
     * action text. Name and action text are in the hash for the same reason transponder state is: the
     * 1 Hz set is their only carrier, and {@code sendSetIfChanged} only rebroadcasts when this hash
     * moves — a rename (the 2026-08-19 identity fix) or a "traveling to X" → "pursuing Y" flip
     * (Phase 9b) that does not flip the hash would sit on the host until an unrelated structural
     * change happened to flush it.
     *
     * <p><b>Health is deliberately absent</b> — CR and hull fraction are not in {@code fleetHash}
     * (see {@link CoopFleetSnapshot#computeFleetHash}) and so are not in this hash either, because a
     * flip here means the guest re-applies structure and the guest's freeze-release logic
     * ({@code CoopFleetMirrorRegistry}) reads {@code fleetHash} as "the ship set changed". Health
     * reaches the guest through the separate, rate-limited {@link #computeHealthHash} trigger in
     * {@code CoopNpcFleetReplicator}, which sends the very same set message without disturbing the
     * meaning of either structural hash.
     */
    public static String computeSetHash(List<CoopNpcFleetSnapshot> fleets) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                records.add(fleet.coopFleetId() + "|" + fleet.factionId() + "|"
                        + fleet.locationId() + "|" + (fleet.transponderOn() ? "1" : "0")
                        + "|" + fleet.fleetHash() + "|" + fleet.name()
                        + "|" + fleet.aiAssignmentSummary());
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(String.join("\n", records));
    }

    /**
     * Order-independent hash over every member's CR and hull fraction, bucketed to 5%. The second
     * send trigger for {@code NPC_FLEET_SET}: {@link #computeSetHash} is structural on purpose, and
     * the 10 Hz {@code NPC_FLEET_MOTION} datagram carries neither CR nor hull, so before this existed
     * a fleet that repaired from 30% hull to full produced no wire traffic at all and the guest's
     * mirror showed the damage until some unrelated field of some fleet happened to move.
     *
     * <p>The 5% buckets and the replicator's 10 s floor are what keep this from re-creating the
     * 2026-08-17 rebuild storm the structural hash was carved out to stop: this hash only decides
     * <em>whether to send</em>, and the guest's receive path treats the arriving set as unchanged
     * structure and paints CR/hull onto the existing members in place
     * ({@code CoopFleetMirror#updateMemberState}). A percent-accurate hash would fire every second on
     * any repairing fleet; a 5% step on a fleet under repair fires a few times per recovery.
     */
    public static String computeHealthHash(List<CoopNpcFleetSnapshot> fleets) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                List<String> members = new ArrayList<>();
                for (CoopFleetSnapshot.Member member : fleet.members()) {
                    members.add(member.fleetMemberId() + ":" + healthBucket(member.cr())
                            + "/" + healthBucket(member.hullFraction()));
                }
                members.sort(null);
                records.add(fleet.coopFleetId() + "|" + String.join(",", members));
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(String.join("\n", records));
    }

    /** A 0..1 fraction onto 5% steps; a NaN reading buckets as 0 rather than poisoning the hash. */
    private static int healthBucket(float value) {
        return Float.isNaN(value) ? 0 : Math.round(value * 20f);
    }

    public String encode() {
        StringBuilder out = new StringBuilder(64 + fleets.size() * 96);
        out.append(Integer.toString(fleets.size())).append('|').append(CoopFleetCodec.escape(setHash));
        for (CoopNpcFleetSnapshot fleet : fleets) {
            out.append('\n').append(CoopFleetCodec.escape(fleet.encode()));
        }
        return out.toString();
    }

    public static CoopNpcFleetSetSnapshot decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty NPC fleet set");
        }
        List<String> header = CoopFleetCodec.split(lines[0]);
        if (header.size() != 2) {
            throw new IllegalArgumentException("Expected 2 set header fields, got " + header.size());
        }
        int count = Integer.parseInt(header.get(0));
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " fleets but only "
                    + (lines.length - 1) + " fleet lines present");
        }
        List<CoopNpcFleetSnapshot> fleets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fleets.add(CoopNpcFleetSnapshot.decode(CoopFleetCodec.unescape(lines[i + 1])));
        }
        return new CoopNpcFleetSetSnapshot(fleets, header.get(1));
    }
}
