package coop.campaign;

import java.util.Objects;
import java.util.Set;

/**
 * Classifies fleet abilities into host-arbitrated (world-affecting) vs purely-local (Phase 12).
 *
 * <p>Abilities that touch shared / NPC state — interdiction pulse, distress call, active sensor
 * burst — are host-arbitrated: the activating client sends an {@code ABILITY_ACTIVATE} intent and
 * the host applies the effect to the authoritative NPC fleets/world and broadcasts the result.
 * Purely-local abilities — emergency burn, sustained burn, transponder toggle, go-dark affecting
 * only the user's own detection — stay local and are not arbitrated.
 *
 * <p>Unknown ability ids default to <em>world-affecting</em>: arbitrating an ability that turns out
 * to be harmless is cheap, while failing to arbitrate one that mutates shared state would desync the
 * clients, so the safe default routes through the host.
 */
public final class CoopAbilityArbiter {

    /** Vanilla ability ids whose effect is confined to the activating fleet's own state. */
    public static final Set<String> LOCAL_ABILITIES = Set.of(
            "emergency_burn",
            "sustained_burn",
            "transponder",
            "go_dark");

    /** Vanilla ability ids that touch shared / NPC / world state and must be host-arbitrated. */
    public static final Set<String> WORLD_AFFECTING_ABILITIES = Set.of(
            "interdiction_pulse",
            "distress_call",
            "sensor_burst",
            "active_sensor_burst");

    private CoopAbilityArbiter() {
    }

    public static boolean isWorldAffecting(String abilityId) {
        String norm = normalize(abilityId);
        if (norm.isEmpty()) {
            return true;
        }
        if (LOCAL_ABILITIES.contains(norm)) {
            return false;
        }
        // Known world-affecting, or unknown -> arbitrate (safe default).
        return true;
    }

    public static boolean isLocal(String abilityId) {
        return !isWorldAffecting(abilityId);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "abilityId").trim();
    }
}
