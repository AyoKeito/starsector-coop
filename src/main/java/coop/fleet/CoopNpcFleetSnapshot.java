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
 * <p>{@code sensors} is the host fleet's sensor identity as {@link CoopSensorSync} defines it: the raw
 * profile plus the three {@code detectedRangeMod} aggregates plus the sensor strength. The guest pins
 * all of it onto the mirror so the fleet is detected <em>and identified</em> at the same range the host
 * uses. <b>Revised 2026-08-19 (Phase 14b):</b> this replaced a single folded "effective detectability"
 * float, which double-counted the guest's own terrain on top of the host's and made fleets render grey;
 * see {@link CoopSensorSync}'s class doc for the full mechanism.
 *
 * <p>Reuses {@link CoopFleetSnapshot.Member} and the shared {@link CoopFleetCodec} so the per-ship
 * encoding is identical to the Phase 8 player mirror.
 */
public record CoopNpcFleetSnapshot(String coopFleetId, String factionId, String name, String locationId,
                                   float x, float y, float velocityX, float velocityY,
                                   boolean transponderOn, CoopSensorSync.Profile sensors,
                                   String aiAssignmentSummary, String fleetHash,
                                   List<CoopFleetSnapshot.Member> members) {

    private static final int HEADER_FIELD_COUNT = 12 + CoopSensorSync.FIELD_COUNT;
    private static final int SENSOR_FIELD_OFFSET = 9;
    private static final int MEMBER_COUNT_INDEX = HEADER_FIELD_COUNT - 1;

    public CoopNpcFleetSnapshot {
        coopFleetId = normalize(coopFleetId);
        factionId = normalize(factionId);
        name = normalize(name);
        locationId = normalize(locationId);
        aiAssignmentSummary = normalize(aiAssignmentSummary);
        fleetHash = normalize(fleetHash);
        sensors = sensors == null ? CoopSensorSync.Profile.UNKNOWN : sensors;
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** Builds a snapshot, computing {@link #fleetHash} from {@code members} (reusing the Phase 8 hash). */
    public static CoopNpcFleetSnapshot create(String coopFleetId, String factionId, String name,
                                              String locationId, float x, float y,
                                              float velocityX, float velocityY, boolean transponderOn,
                                              CoopSensorSync.Profile sensors,
                                              String aiAssignmentSummary,
                                              List<CoopFleetSnapshot.Member> members) {
        List<CoopFleetSnapshot.Member> safe = members == null ? List.of() : members;
        return new CoopNpcFleetSnapshot(coopFleetId, factionId, name, locationId, x, y, velocityX,
                velocityY, transponderOn, sensors, aiAssignmentSummary,
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
                .append('|').append(transponderOn ? '1' : '0');
        CoopSensorSync.append(out, sensors);
        out.append('|').append(CoopFleetCodec.escape(aiAssignmentSummary))
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
        int memberCount = Integer.parseInt(header.get(MEMBER_COUNT_INDEX));
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
                "1".equals(header.get(8)),
                CoopSensorSync.parse(header, SENSOR_FIELD_OFFSET),
                header.get(SENSOR_FIELD_OFFSET + CoopSensorSync.FIELD_COUNT),
                header.get(SENSOR_FIELD_OFFSET + CoopSensorSync.FIELD_COUNT + 1), members);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
