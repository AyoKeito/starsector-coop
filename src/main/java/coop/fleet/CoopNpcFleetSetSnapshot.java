package coop.fleet;

import coop.handshake.CoopChecksum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The host's full authoritative set of non-player campaign fleets, carried by the reliable TCP
 * {@code NPC_FLEET_SET} message (Phase 9). The whole set is rebroadcast whenever {@link #setHash()}
 * changes; the guest reconciles against it idempotently (add fleets present here but missing locally,
 * dispose mirrors absent here). Full-set rebroadcast is chosen for v1 because it is self-correcting
 * (no add/remove delta ordering or lost-packet bugs).
 *
 * <p>{@link #setHash()} is order-independent and folds in each fleet's identity, faction, location,
 * transponder state and roster ({@code fleetHash}) so it flips on spawn/despawn, faction change, system
 * jump, transponder toggle, or roster edit — exactly the structural changes that require the guest to
 * re-apply rather than merely interpolate.
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

    /** Order-independent hash over each fleet's identity/faction/location/transponder/roster. */
    public static String computeSetHash(List<CoopNpcFleetSnapshot> fleets) {
        List<String> records = new ArrayList<>();
        if (fleets != null) {
            for (CoopNpcFleetSnapshot fleet : fleets) {
                records.add(fleet.coopFleetId() + "|" + fleet.factionId() + "|"
                        + fleet.locationId() + "|" + (fleet.transponderOn() ? "1" : "0")
                        + "|" + fleet.fleetHash());
            }
        }
        records.sort(null);
        return CoopChecksum.sha256Text(String.join("\n", records));
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
