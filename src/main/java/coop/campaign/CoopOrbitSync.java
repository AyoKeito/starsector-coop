package coop.campaign;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-authoritative orbit-angle sync (clock-drift correction).
 *
 * <p>Both clients generate the sector deterministically (same orbits, radii, periods), but their
 * campaign clocks drift slightly, so orbiting bodies sit at slightly different angles
 * ({@code angle = baseAngle + 360 * elapsedDays / period}). The host periodically broadcasts each
 * orbiting body's current {@code circularOrbitAngle}; the guest snaps its matching body to it.
 *
 * <p>Matching across instances is the subtle part: planets/jumps/stations have stable string ids
 * ({@code jangala}, {@code jangala_jump}) and match directly, but asteroids / nav buoys / the fringe
 * jump-point carry per-instance hex ids and must match on the stable orbit
 * {@link #signature(String, float, float) signature} {@code focusId|radius|period} instead.
 */
public final class CoopOrbitSync {
    private CoopOrbitSync() {
    }

    public record OrbitEntry(String entityId, String focusId, float radius, float period, float angle) {
        /**
         * A focus-less body writes its null {@code focusId} as the empty field {@link CoopDelimited}
         * emits for a null, and {@link #decode} hands that {@code ""} straight back. Normalizing it
         * to null here makes the wire round trip identity-preserving, which two callers depend on:
         * {@link #signature(String, float, float)} maps null to {@code "-"} but {@code ""} to
         * {@code ""}, so the signature fallback could never match a focus-less body; and the
         * {@code focusId != null} guard in the orbit apply would run a sector-wide
         * {@code getEntityById("")} once per entry.
         */
        public OrbitEntry {
            focusId = focusId == null || focusId.isEmpty() ? null : focusId;
        }
    }

    /** A leaf hex id (e.g. {@code 372f}, {@code 95af}) is assigned per-instance and won't match by id. */
    public static boolean isStableId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit) {
                return true;
            }
        }
        return false; // all chars are hex digits -> a per-instance generated id
    }

    /** Stable cross-instance key for hex-id bodies: focus + rounded radius + rounded period. */
    public static String signature(String focusId, float radius, float period) {
        return (focusId == null ? "-" : focusId) + "#" + Math.round(radius) + "#" + Math.round(period * 100.0);
    }

    public static String encode(List<OrbitEntry> entries) {
        // ~64 chars a record (two ids plus three floats). A star system's worth of bodies is a ~4 KB
        // payload, which an unsized builder reached through five or six array copies.
        StringBuilder sb = new StringBuilder(Math.max(64, entries.size() * 64));
        for (OrbitEntry e : entries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(CoopDelimited.field(e.entityId()))
                    .append('|').append(CoopDelimited.field(e.focusId()))
                    .append('|').append(e.radius())
                    .append('|').append(e.period())
                    .append('|').append(e.angle());
        }
        return sb.toString();
    }

    public static List<OrbitEntry> decode(String encoded) {
        List<OrbitEntry> entries = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return entries;
        }
        for (String line : encoded.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> f = CoopDelimited.split(line);
            if (f.size() < 5) {
                continue;
            }
            entries.add(new OrbitEntry(f.get(0), f.get(1),
                    Float.parseFloat(f.get(2)), Float.parseFloat(f.get(3)), Float.parseFloat(f.get(4))));
        }
        return entries;
    }
}
