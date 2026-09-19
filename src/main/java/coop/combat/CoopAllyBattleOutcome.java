package coop.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 33: what a battle did to the partner's mirror on the piloting engine, read once when the
 * mirror's {@code getBattle()} goes back to null and before the owner's next snapshot is allowed to
 * touch the mirror again (the spike of 2026-09-20 showed the per-snapshot hull and CR write erases
 * the engine's numbers within a frame). Carried to the owner as {@code ALLY_BATTLE_RESULT} and applied
 * to the owner's real fleet by {@link CoopAllyLossApplier}.
 *
 * <p>Member ids are the owner's own {@code FleetMemberAPI.getId()} values as the roster snapshot
 * carried them, so the owner can address its ships directly. A ship named in both lists is destroyed;
 * the survivors entry is ignored.
 *
 * @param ownerPlayerId      the player whose real fleet pays for this
 * @param destroyedMemberIds owner-side ids of ships that did not come back
 * @param survivors          owner-side ids of the ships that did, with the hull fraction and CR the
 *                           engine left them at
 */
public record CoopAllyBattleOutcome(String ownerPlayerId, List<String> destroyedMemberIds,
                                    List<Survivor> survivors) {

    /** One surviving ship. Hull fraction and CR are clamped to {@code [0, 1]}; NaN reads as 0. */
    public record Survivor(String memberId, float hullFraction, float cr) {
        public Survivor {
            memberId = memberId == null ? "" : memberId.trim();
            hullFraction = clampUnit(hullFraction);
            cr = clampUnit(cr);
        }
    }

    public CoopAllyBattleOutcome {
        ownerPlayerId = ownerPlayerId == null ? "" : ownerPlayerId.trim();
        destroyedMemberIds = List.copyOf(withoutBlanks(destroyedMemberIds));
        survivors = List.copyOf(survivors == null ? List.of() : survivors);
    }

    /** True when the battle left nothing to apply: no ship lost and no survivor listed. */
    public boolean isEmpty() {
        return destroyedMemberIds.isEmpty() && survivors.isEmpty();
    }

    static float clampUnit(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static List<String> withoutBlanks(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id != null && !id.trim().isEmpty()) {
                out.add(id.trim());
            }
        }
        return out;
    }
}
