package coop.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One NPC fleet's position/velocity, streamed in batches over the UDP {@code NPC_FLEET_MOTION} message
 * at 10 Hz for fleets in a location where either player currently is (Phase 9). Off-screen mirrors
 * keep their last-known position from the {@code NPC_FLEET_SET} apply; precise off-screen motion is
 * unnecessary because economy/intel/encounter outcomes are host-authored deltas (Phase 12).
 *
 * <p>Identity is the same {@code coopFleetId} as {@link CoopNpcFleetSnapshot}; {@code locationId} lets
 * the guest detect (and apply) a cross-location move that arrives via motion before the next set.
 *
 * <p>{@code sensors} ({@link CoopSensorSync.Profile}) is streamed live rather than only in the set
 * because a fleet's detectability swings with its abilities and terrain within a second: sustained burn
 * is +100% detected range, going dark is x0.5, an active sensor burst is +5000 flat, and terrain
 * re-applies its own 0.1-day temporary mods every frame. A mirror stuck on the fleet's last-set values
 * would drift in and out of the identification bands. The strength term rides along so the fleet's
 * detection-range ring — the radius a hidden player reads to judge safe distance — stays correct too.
 */
public record CoopNpcFleetMotion(String coopFleetId, String locationId,
                                 float x, float y, float velocityX, float velocityY,
                                 CoopSensorSync.Profile sensors) {

    private static final int FIELD_COUNT = 6 + CoopSensorSync.FIELD_COUNT;
    private static final int SENSOR_FIELD_OFFSET = 6;

    public CoopNpcFleetMotion {
        coopFleetId = coopFleetId == null ? "" : coopFleetId;
        locationId = locationId == null ? "" : locationId;
        sensors = sensors == null ? CoopSensorSync.Profile.UNKNOWN : sensors;
    }

    public static String encodeBatch(List<CoopNpcFleetMotion> motions) {
        List<CoopNpcFleetMotion> safe = motions == null ? List.of() : motions;
        StringBuilder out = new StringBuilder(16 + safe.size() * 48);
        out.append(Integer.toString(safe.size()));
        for (CoopNpcFleetMotion motion : safe) {
            out.append('\n')
                    .append(CoopFleetCodec.escape(motion.coopFleetId()))
                    .append('|').append(CoopFleetCodec.escape(motion.locationId()))
                    .append('|').append(Float.toString(motion.x()))
                    .append('|').append(Float.toString(motion.y()))
                    .append('|').append(Float.toString(motion.velocityX()))
                    .append('|').append(Float.toString(motion.velocityY()));
            CoopSensorSync.append(out, motion.sensors());
        }
        return out.toString();
    }

    public static List<CoopNpcFleetMotion> decodeBatch(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        if (lines.length == 0) {
            return List.of();
        }
        int count = Integer.parseInt(lines[0].trim());
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " motions but only "
                    + (lines.length - 1) + " records present");
        }
        List<CoopNpcFleetMotion> motions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            List<String> fields = CoopFleetCodec.split(lines[i + 1]);
            if (fields.size() != FIELD_COUNT) {
                throw new IllegalArgumentException("Expected " + FIELD_COUNT
                        + " motion fields, got " + fields.size());
            }
            motions.add(new CoopNpcFleetMotion(fields.get(0), fields.get(1),
                    Float.parseFloat(fields.get(2)), Float.parseFloat(fields.get(3)),
                    Float.parseFloat(fields.get(4)), Float.parseFloat(fields.get(5)),
                    CoopSensorSync.parse(fields, SENSOR_FIELD_OFFSET)));
        }
        return motions;
    }
}
