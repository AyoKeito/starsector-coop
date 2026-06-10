package coop.campaign;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single host-authoritative player-reputation change (Phase 12, COOP_MP_DESIGN.md 4.x).
 *
 * <p>The host owns the shared reputation table. When vanilla reports a player reputation change
 * (faction standing or a specific person's disposition), the host captures it and broadcasts a
 * {@code REP_DELTA} carrying the <em>resulting</em> relationship value, not just the increment. The
 * guest applies the result by <em>setting</em> the relationship to {@link #resultingValue()} rather
 * than re-running its own reputation math, so both clients converge on the host value regardless of
 * any per-client baseline drift. The {@code delta} is carried for logging/auditing only.
 *
 * <p>The guest's conceptual personal reputation baseline is {@code 0} ({@link #BASELINE}) before any
 * host delta is applied; {@link #applyTo(Map)} therefore never depends on a pre-seeded table.
 */
public record CoopRepDelta(TargetType targetType, String targetId, float delta, float resultingValue) {

    /** Conceptual starting relationship value before any host {@code REP_DELTA} is applied. */
    public static final float BASELINE = 0f;

    public enum TargetType {
        FACTION,
        PERSON
    }

    public CoopRepDelta {
        targetType = Objects.requireNonNull(targetType, "targetType");
        targetId = requireText(targetId, "targetId");
    }

    /** Stable table key for a reputation target ({@code FACTION:hegemony}, {@code PERSON:abc123}). */
    public String relationshipKey() {
        return relationshipKey(targetType, targetId);
    }

    public static String relationshipKey(TargetType targetType, String targetId) {
        return Objects.requireNonNull(targetType, "targetType").name() + ":" + requireText(targetId, "targetId");
    }

    /**
     * Applies this delta to an in-memory reputation table by setting the target to
     * {@link #resultingValue()} (final-value convergence). Returns the value now stored.
     */
    public float applyTo(Map<String, Float> table) {
        Objects.requireNonNull(table, "table");
        table.put(relationshipKey(), resultingValue);
        return resultingValue;
    }

    /** Reads the current relationship value, defaulting to {@link #BASELINE} when unseen. */
    public static float relationship(Map<String, Float> table, TargetType targetType, String targetId) {
        Objects.requireNonNull(table, "table");
        return table.getOrDefault(relationshipKey(targetType, targetId), BASELINE);
    }

    /**
     * Encodes a full set of player-&gt;faction standings for {@code PLAYER_REP_SNAPSHOT} (a host
     * authoritative overwrite that converges the guest even for drift the host can't see, e.g.
     * transponder-off penalties applied independently in the guest's own simulation). Format mirrors
     * the other Phase 12 list payloads: a count header line, then {@code factionId|value} records.
     */
    public static String encodeFactionStandings(Map<String, Float> standings) {
        Objects.requireNonNull(standings, "standings");
        StringBuilder out = new StringBuilder(32 + standings.size() * 16);
        out.append(standings.size());
        for (Map.Entry<String, Float> entry : standings.entrySet()) {
            out.append('\n').append(CoopDelimited.field(entry.getKey())).append('|').append(entry.getValue());
        }
        return out.toString();
    }

    public static Map<String, Float> decodeFactionStandings(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] lines = encoded.split("\n", -1);
        int count = Integer.parseInt(lines[0].trim());
        if (lines.length - 1 < count) {
            throw new IllegalArgumentException("Declared " + count + " standings but only "
                    + (lines.length - 1) + " record lines present");
        }
        Map<String, Float> standings = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            List<String> f = CoopDelimited.split(lines[i + 1]);
            if (f.size() != 2) {
                throw new IllegalArgumentException("Expected 2 standing fields, got " + f.size());
            }
            standings.put(f.get(0), Float.parseFloat(f.get(1).trim()));
        }
        return standings;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
