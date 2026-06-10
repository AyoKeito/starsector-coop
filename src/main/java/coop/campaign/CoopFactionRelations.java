package coop.campaign;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-authoritative model of faction-to-faction relationships (Phase 12).
 *
 * <p>Inter-faction standings (who is hostile to whom) drive each client's own market access and
 * encounter outcomes, so they must agree across clients. Whenever vanilla changes an inter-faction
 * standing the host broadcasts a {@code FACTION_REL_DELTA} carrying the <em>resulting</em> value;
 * the guest {@link #applyResult(String, String, float) sets} that value rather than re-simulating.
 *
 * <p>Relationships are symmetric, so the pair key is order independent: {@code (hegemony, tritachyon)}
 * and {@code (tritachyon, hegemony)} address the same slot. Unseen pairs read as
 * {@link CoopRepDelta#BASELINE} ({@code 0}, neutral).
 */
public final class CoopFactionRelations {
    private final Map<String, Float> relations = new HashMap<>();

    /** Order-independent key for a faction pair. */
    public static String pairKey(String factionA, String factionB) {
        String a = requireText(factionA, "factionA");
        String b = requireText(factionB, "factionB");
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** Sets the relationship between two factions to the host's resulting value. Returns it. */
    public float applyResult(String factionA, String factionB, float resultingValue) {
        relations.put(pairKey(factionA, factionB), resultingValue);
        return resultingValue;
    }

    /** Current relationship value, defaulting to {@link CoopRepDelta#BASELINE} (neutral) when unseen. */
    public float relationship(String factionA, String factionB) {
        return relations.getOrDefault(pairKey(factionA, factionB), CoopRepDelta.BASELINE);
    }

    public boolean isKnown(String factionA, String factionB) {
        return relations.containsKey(pairKey(factionA, factionB));
    }

    public int size() {
        return relations.size();
    }

    public void clear() {
        relations.clear();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
