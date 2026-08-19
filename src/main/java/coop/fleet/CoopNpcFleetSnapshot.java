package coop.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-authoritative state of a single non-player campaign fleet, replicated to the guest as part of
 * the {@link CoopNpcFleetSetSnapshot} carried over the reliable TCP {@code NPC_FLEET_SET} message
 * (Phase 9). Identity is the host-side {@code coopFleetId} ({@code fleet.getId()}), which is stable
 * for the lifetime of the fleet; the guest keys its mirror registry on it.
 *
 * <p>{@code sensorProfile} is the host fleet's effective detectability: the raw profile with the
 * fleet's own {@code detectedRangeMod} folded in (transponder, sustained burn, generation flats, phase
 * fields, terrain). The guest applies it to the mirror (frozen, see {@link CoopFleetMirror}) so the
 * guest detects <em>and identifies</em> the fleet at the same range the host does — without the fold, a
 * mirror carries only the engine's raw profile and both its grey sensor-contact range and its
 * faction-identification range come out far too short. It must be derived from the fleet's own stats
 * only: anything computed against the host player fleet leaks host state into guest rendering (see
 * {@code CoopNpcFleetReplicator.effectiveDetectability}).
 *
 * <p>{@code sensorStrength} is the host fleet's sensor reach as an <em>observer</em>. The guest applies
 * it to the mirror so the engine renders the fleet's detection-range ring at the correct radius — the
 * ring a hidden player reads to judge safe approach distance. The radius is computed against the guest's
 * own (real) sensor profile, so it correctly shrinks when the guest runs dark.
 *
 * <p>Reuses {@link CoopFleetSnapshot.Member} and the shared {@link CoopFleetCodec} so the per-ship
 * encoding is identical to the Phase 8 player mirror.
 */
public record CoopNpcFleetSnapshot(String coopFleetId, String factionId, String name, String locationId,
                                   float x, float y, float velocityX, float velocityY,
                                   boolean transponderOn, float sensorProfile, float sensorStrength,
                                   String aiAssignmentSummary, String fleetHash,
                                   List<CoopFleetSnapshot.Member> members) {

    private static final int HEADER_FIELD_COUNT = 14;

    public CoopNpcFleetSnapshot {
        coopFleetId = normalize(coopFleetId);
        factionId = normalize(factionId);
        name = normalize(name);
        locationId = normalize(locationId);
        aiAssignmentSummary = normalize(aiAssignmentSummary);
        fleetHash = normalize(fleetHash);
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** Builds a snapshot, computing {@link #fleetHash} from {@code members} (reusing the Phase 8 hash). */
    public static CoopNpcFleetSnapshot create(String coopFleetId, String factionId, String name,
                                              String locationId, float x, float y,
                                              float velocityX, float velocityY, boolean transponderOn,
                                              float sensorProfile, float sensorStrength,
                                              String aiAssignmentSummary,
                                              List<CoopFleetSnapshot.Member> members) {
        List<CoopFleetSnapshot.Member> safe = members == null ? List.of() : members;
        return new CoopNpcFleetSnapshot(coopFleetId, factionId, name, locationId, x, y, velocityX,
                velocityY, transponderOn, sensorProfile, sensorStrength, aiAssignmentSummary,
                CoopFleetSnapshot.computeFleetHash(safe), safe);
    }

    public String encode() {
        StringBuilder out = new StringBuilder(128 + members.size() * 48);
        out.append(CoopFleetCodec.escape(coopFleetId))
                .append('|').append(CoopFleetCodec.escape(factionId))
                .append('|').append(CoopFleetCodec.escape(name))
                .append('|').append(CoopFleetCodec.escape(locationId))
                .append('|').append(Float.toString(x))
                .append('|').append(Float.toString(y))
                .append('|').append(Float.toString(velocityX))
                .append('|').append(Float.toString(velocityY))
                .append('|').append(transponderOn ? '1' : '0')
                .append('|').append(Float.toString(sensorProfile))
                .append('|').append(Float.toString(sensorStrength))
                .append('|').append(CoopFleetCodec.escape(aiAssignmentSummary))
                .append('|').append(CoopFleetCodec.escape(fleetHash))
                .append('|').append(Integer.toString(members.size()));
        for (CoopFleetSnapshot.Member member : members) {
            out.append('\n');
            CoopFleetCodec.appendMember(out, member);
        }
        return out.toString();
    }

    public static CoopNpcFleetSnapshot decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        if (lines.length == 0) {
            throw new IllegalArgumentException("Empty NPC fleet snapshot");
        }
        List<String> header = CoopFleetCodec.split(lines[0]);
        if (header.size() != HEADER_FIELD_COUNT) {
            throw new IllegalArgumentException("Expected " + HEADER_FIELD_COUNT
                    + " header fields, got " + header.size());
        }
        int memberCount = Integer.parseInt(header.get(13));
        if (lines.length - 1 < memberCount) {
            throw new IllegalArgumentException("Declared " + memberCount + " members but only "
                    + (lines.length - 1) + " member lines present");
        }
        List<CoopFleetSnapshot.Member> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            members.add(CoopFleetCodec.parseMember(CoopFleetCodec.split(lines[i + 1])));
        }
        return new CoopNpcFleetSnapshot(header.get(0), header.get(1), header.get(2), header.get(3),
                Float.parseFloat(header.get(4)), Float.parseFloat(header.get(5)),
                Float.parseFloat(header.get(6)), Float.parseFloat(header.get(7)),
                "1".equals(header.get(8)), Float.parseFloat(header.get(9)),
                Float.parseFloat(header.get(10)), header.get(11), header.get(12), members);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
