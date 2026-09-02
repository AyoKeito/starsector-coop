package coop.fleet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One NPC fleet's position/velocity, streamed in batches over the UDP {@code NPC_FLEET_MOTION} message
 * at 10 Hz for fleets in a location where either player currently is (Phase 9), and — since Phase 20
 * M4 — only for fleets within detection range of a player position; distant fleets ride the
 * {@code NPC_FLEET_SET} alone. Off-screen mirrors keep their last-known position from the
 * {@code NPC_FLEET_SET} apply; precise off-screen motion is unnecessary because economy/intel/encounter
 * outcomes are host-authored deltas (Phase 12).
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
 *
 * <h2>Section format (v2, Phase 20 M4)</h2>
 * A section is a mode line — {@code F} full or {@code D} delta — followed by one record per fleet:
 * {@code coopFleetId|locationId|x|y|vx|vy|mask} plus only the sensor fields whose mask bit is set.
 * The baseline of a {@code D} section is <b>the section immediately before it in the same datagram</b>,
 * which the redundancy layer already colocates there; a delta can therefore never be orphaned by
 * packet loss, which is what makes an ack-free mask safe without Quake 3's acked-baseline machinery.
 * A fleet the baseline does not contain is written with a full mask.
 */
public record CoopNpcFleetMotion(String coopFleetId, String locationId,
                                 float x, float y, float velocityX, float velocityY,
                                 CoopSensorSync.Profile sensors) {

    /** Index of the sensor change mask within a record. */
    private static final int MASK_INDEX = 6;
    /** First sensor field of a record, when the mask selects any. */
    private static final int SENSOR_FIELD_OFFSET = 7;
    /** Section mode markers. */
    static final String MODE_FULL = "F";
    static final String MODE_DELTA = "D";

    public CoopNpcFleetMotion {
        coopFleetId = coopFleetId == null ? "" : coopFleetId;
        locationId = locationId == null ? "" : locationId;
        sensors = sensors == null ? CoopSensorSync.Profile.UNKNOWN : sensors;
    }

    /**
     * A self-contained batch section: every record carries all five sensor fields. This is what the
     * redundancy layer's previous-send section is, and what a delta section is coded against.
     */
    public static String encodeFullSection(List<CoopNpcFleetMotion> motions) {
        List<CoopNpcFleetMotion> safe = motions == null ? List.of() : motions;
        StringBuilder out = new StringBuilder(8 + safe.size() * 80);
        out.append(MODE_FULL);
        for (CoopNpcFleetMotion motion : safe) {
            out.append('\n');
            appendRecord(out, motion, null);
        }
        return out.toString();
    }

    /**
     * A batch section coded against {@code baseline}. Positions are always written (they change every
     * tick by construction); the sensor terms — ~37% of a record and piecewise constant — are written
     * only where the encoded value differs.
     */
    public static String encodeDeltaSection(List<CoopNpcFleetMotion> motions,
                                            List<CoopNpcFleetMotion> baseline) {
        List<CoopNpcFleetMotion> safe = motions == null ? List.of() : motions;
        Map<String, CoopNpcFleetMotion> byId = indexById(baseline);
        StringBuilder out = new StringBuilder(8 + safe.size() * 64);
        out.append(MODE_DELTA);
        for (CoopNpcFleetMotion motion : safe) {
            out.append('\n');
            appendRecord(out, motion, byId.get(motion.coopFleetId()));
        }
        return out.toString();
    }

    /** {@code coopFleetId|locationId|x|y|vx|vy|mask} plus the sensor fields the mask selects. */
    public static void appendRecord(StringBuilder out, CoopNpcFleetMotion motion,
                                    CoopNpcFleetMotion baseline) {
        int mask = CoopSensorSync.changeMask(motion.sensors(),
                baseline == null ? null : baseline.sensors());
        out.append(CoopFleetCodec.escape(motion.coopFleetId()))
                .append('|').append(CoopFleetCodec.escape(motion.locationId()))
                .append('|').append(CoopFleetCodec.encodeFloat(motion.x(), CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.encodeFloat(motion.y(), CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.encodeFloat(motion.velocityX(),
                        CoopFleetCodec.POSITION_STEP))
                .append('|').append(CoopFleetCodec.encodeFloat(motion.velocityY(),
                        CoopFleetCodec.POSITION_STEP))
                .append('|').append(Integer.toString(mask));
        CoopSensorSync.appendMasked(out, motion.sensors(), mask);
    }

    /** One record as its own string; the chunk packer sizes candidates with this. */
    public static String encodeRecord(CoopNpcFleetMotion motion, CoopNpcFleetMotion baseline) {
        StringBuilder out = new StringBuilder(80);
        appendRecord(out, motion, baseline);
        return out.toString();
    }

    /**
     * Decodes every section of one datagram together, in wire order, each against the one before it.
     * The pump must run this <em>before</em> the epoch watermark filters sections: a datagram whose
     * first section was already applied still has to decode it, because the second section's cleared
     * mask bits mean "same as that one".
     */
    public static List<List<CoopNpcFleetMotion>> decodeDatagram(List<String> bodies) {
        Objects.requireNonNull(bodies, "bodies");
        List<List<CoopNpcFleetMotion>> decoded = new ArrayList<>(bodies.size());
        List<CoopNpcFleetMotion> previous = null;
        for (String body : bodies) {
            previous = decodeSection(body, previous);
            decoded.add(previous);
        }
        return decoded;
    }

    /** One section; {@code baseline} is the previous section of the same datagram, or null. */
    public static List<CoopNpcFleetMotion> decodeSection(String body,
                                                         List<CoopNpcFleetMotion> baseline) {
        Objects.requireNonNull(body, "body");
        String[] lines = body.split("\n", -1);
        String mode = lines[0].trim();
        if (!MODE_FULL.equals(mode) && !MODE_DELTA.equals(mode)) {
            throw new IllegalArgumentException("Unknown motion section mode: " + mode);
        }
        Map<String, CoopNpcFleetMotion> byId =
                MODE_DELTA.equals(mode) ? indexById(baseline) : Map.of();
        List<CoopNpcFleetMotion> motions = new ArrayList<>(Math.max(0, lines.length - 1));
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            List<String> fields = CoopFleetCodec.split(lines[i]);
            if (fields.size() < SENSOR_FIELD_OFFSET) {
                throw new IllegalArgumentException("Expected at least " + SENSOR_FIELD_OFFSET
                        + " motion fields, got " + fields.size());
            }
            int mask = Integer.parseInt(fields.get(MASK_INDEX));
            if (mask < 0 || mask > CoopSensorSync.MASK_ALL) {
                throw new IllegalArgumentException("Motion sensor mask out of range: " + mask);
            }
            if (fields.size() != SENSOR_FIELD_OFFSET + Integer.bitCount(mask)) {
                throw new IllegalArgumentException("Mask " + mask + " needs "
                        + (SENSOR_FIELD_OFFSET + Integer.bitCount(mask)) + " fields, got "
                        + fields.size());
            }
            CoopNpcFleetMotion previous = byId.get(fields.get(0));
            motions.add(new CoopNpcFleetMotion(fields.get(0), fields.get(1),
                    Float.parseFloat(fields.get(2)), Float.parseFloat(fields.get(3)),
                    Float.parseFloat(fields.get(4)), Float.parseFloat(fields.get(5)),
                    CoopSensorSync.parseMasked(fields, SENSOR_FIELD_OFFSET, mask,
                            previous == null ? null : previous.sensors())));
        }
        return motions;
    }

    private static Map<String, CoopNpcFleetMotion> indexById(List<CoopNpcFleetMotion> motions) {
        if (motions == null || motions.isEmpty()) {
            return Map.of();
        }
        Map<String, CoopNpcFleetMotion> byId = new HashMap<>(motions.size() * 2);
        for (CoopNpcFleetMotion motion : motions) {
            byId.put(motion.coopFleetId(), motion);
        }
        return byId;
    }
}
