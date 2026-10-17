package coop.combat.shared;

import coop.campaign.CoopDelimited;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Compact ship-frame body for dual-engine shared combat (Suite/BO-style).
 *
 * <p>Line format: {@code F|shipId|hullId|name|owner|x|y|facing|hull|flux|alive} — unknown tokens
 * ignored for forward compatibility.
 */
public final class CoopSharedCombatFrameCodec {

    private static final String RECORD_SEP = "\n";
    private static final char FIELD_SEP = '|';
    private static final String FRAME_TOKEN = "F";

    private CoopSharedCombatFrameCodec() {
    }

    public record ShipFrame(String shipId, String hullId, String name, int owner,
                            float x, float y, float facing, float hullFraction, float fluxLevel,
                            boolean alive) {
        public ShipFrame {
            shipId = CoopDelimited.normalize(shipId);
            hullId = CoopDelimited.normalize(hullId);
            name = CoopDelimited.normalize(name);
            hullFraction = clamp(hullFraction);
            fluxLevel = clamp(fluxLevel);
        }
    }

    public static String encode(List<ShipFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(frames.size() * 48);
        for (ShipFrame f : frames) {
            if (f == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(RECORD_SEP);
            }
            sb.append(FRAME_TOKEN).append(FIELD_SEP)
                    .append(CoopDelimited.field(f.shipId())).append(FIELD_SEP)
                    .append(CoopDelimited.field(f.hullId())).append(FIELD_SEP)
                    .append(CoopDelimited.field(f.name())).append(FIELD_SEP)
                    .append(f.owner()).append(FIELD_SEP)
                    .append(fmt(f.x())).append(FIELD_SEP)
                    .append(fmt(f.y())).append(FIELD_SEP)
                    .append(fmt(f.facing())).append(FIELD_SEP)
                    .append(fmt(f.hullFraction())).append(FIELD_SEP)
                    .append(fmt(f.fluxLevel())).append(FIELD_SEP)
                    .append(f.alive() ? '1' : '0');
        }
        return sb.toString();
    }

    public static List<ShipFrame> decode(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        List<ShipFrame> out = new ArrayList<>();
        for (String line : body.split(RECORD_SEP, -1)) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> parts = CoopDelimited.split(line);
            if (parts.size() < 11 || !FRAME_TOKEN.equals(parts.get(0))) {
                continue;
            }
            try {
                out.add(new ShipFrame(
                        parts.get(1),
                        parts.get(2),
                        parts.get(3),
                        Integer.parseInt(parts.get(4).trim()),
                        Float.parseFloat(parts.get(5).trim()),
                        Float.parseFloat(parts.get(6).trim()),
                        Float.parseFloat(parts.get(7).trim()),
                        Float.parseFloat(parts.get(8).trim()),
                        Float.parseFloat(parts.get(9).trim()),
                        !"0".equals(parts.get(10).trim())));
            } catch (RuntimeException ignored) {
                // skip malformed
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private static float clamp(float v) {
        if (Float.isNaN(v) || v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }
}
