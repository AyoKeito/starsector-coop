package coop.campaign;

import java.util.Objects;
import java.util.Set;

/**
 * Classifies fleet abilities into host-arbitrated (world-affecting) vs purely-local (Phase 12).
 *
 * <p>Abilities that touch shared / NPC state — interdiction pulse, distress call — are
 * host-arbitrated: the activating client sends an {@code ABILITY_ACTIVATE} intent and the host
 * applies the effect to the authoritative NPC fleets/world ({@link CoopAbilityEffectApplier}) and
 * broadcasts the result. Purely-local abilities — emergency burn, sustained burn, transponder
 * toggle, go-dark and the sensor abilities, all of which only move the activating fleet's own
 * detection numbers — stay local and are not arbitrated.
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
            "go_dark",
            // Its whole effect is a detectedRangeMod / sensorRangeMod spike on the activating fleet,
            // and Phase 14b's CoopSensorSync already pins the mirror's detected-range total from the
            // real fleet every frame. Arbitrating it would ask the host to activate a second source
            // of the same number, which the very next sensor-sync frame cancels.
            "sensor_burst",
            // Reads the activating fleet's own surroundings into its own map/intel. Nothing shared
            // is written, so the pre-12c world-affecting default was simply wrong.
            "gravitic_scan",
            // It does write shared state — every planet in the system goes to PRELIMINARY survey
            // level — but that outcome replicates on its own now, through the Phase 12c survey poll
            // (WORLD_DELTA(SURVEY)), which catches all five of the level's mutation paths rather than
            // just this one. Routing the ability to the host applier only produced log noise: there
            // is no host-side effect wired for it, and the ability's own once-per-system flag and
            // CR cost are charged on the activating fleet regardless.
            "remote_survey");

    /**
     * Vanilla ability ids that touch shared / NPC / world state and must be host-arbitrated. Kept as
     * documentation of the arbitrated set — {@link #isWorldAffecting} routes by the local set plus
     * the unknown-is-world-affecting default, so anything not listed in {@link #LOCAL_ABILITIES}
     * (notably {@code generate_slipsurge}) also arbitrates.
     */
    public static final Set<String> WORLD_AFFECTING_ABILITIES = Set.of(
            "interdiction_pulse",
            "distress_call");

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
