package coop.campaign;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The single guest&rarr;host channel for any guest-driven interaction that mutates shared / world /
 * host-owned-fleet state (Phase 12).
 *
 * <p>When either player salvages or explores a world entity (derelict, ruin, debris field, cargo
 * pod, domain probe, research station, sensor-ghost cache), the interacting client resolves it
 * locally via vanilla and keeps the loot in its own per-player cargo (same rule as the solo fighter
 * keeping combat spoils). It then sends a {@code WORLD_DELTA} reporting the entity's consumed/looted
 * state. The host integrates it into authoritative world state and re-broadcasts; every client
 * applies it idempotently so the entity is consumed on <em>both</em> clients and cannot be re-looted
 * by the other. Loot RNG is per-player and need not match (it only fills the actor's cargo), so no
 * determinism fork is needed.
 *
 * <p>The same channel carries stable-location construction ({@link Kind#CONSTRUCT}: comm relay / nav
 * buoy / sensor array), rare shared-world dialog mutations ({@link Kind#PARLEY}: spend SP to make a
 * fleet leave, etc.), and generic consumption ({@link Kind#CONSUME}). Anything not explicitly wired
 * relies on the host self-healing rebroadcast backstop.
 */
public record CoopWorldDelta(String entityId, Kind kind, boolean consumed,
                             String newStateJson, String actingPlayerId) {

    public enum Kind {
        SALVAGE,
        EXPLORE,
        CONSUME,
        CONSTRUCT,
        PARLEY
    }

    public CoopWorldDelta {
        entityId = requireText(entityId, "entityId");
        kind = Objects.requireNonNull(kind, "kind");
        newStateJson = newStateJson == null ? "" : newStateJson;
        actingPlayerId = CoopDelimited.normalize(actingPlayerId);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    /**
     * Idempotent record of which world entities have been consumed, so re-applying the same
     * {@code WORLD_DELTA} (host rebroadcast, duplicate packet, both-clients apply) is a no-op and an
     * already-consumed entity can never be looted twice.
     */
    public static final class Ledger {
        private final Set<String> consumedEntityIds = new HashSet<>();

        /**
         * Applies a delta. Returns {@code true} only the first time an entity is marked consumed
         * (i.e. when the caller should actually mutate world state); subsequent applies return
         * {@code false} (already handled — no double-loot, no re-consume).
         */
        public boolean apply(CoopWorldDelta delta) {
            Objects.requireNonNull(delta, "delta");
            if (!delta.consumed()) {
                // A non-consuming delta (e.g. a construct that does not remove an entity) is applied
                // every time it is new; we track by entity id only for the consumed case.
                return !consumedEntityIds.contains(delta.entityId());
            }
            return consumedEntityIds.add(delta.entityId());
        }

        public boolean isConsumed(String entityId) {
            return consumedEntityIds.contains(requireText(entityId, "entityId"));
        }

        public int size() {
            return consumedEntityIds.size();
        }

        public void clear() {
            consumedEntityIds.clear();
        }
    }
}
