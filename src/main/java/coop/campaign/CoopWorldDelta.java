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
        PARLEY,
        /**
         * A world entity came into existence at runtime and must be materialized on the other client
         * (Phase 12d). Today that means player-created cargo pods — jettisoned cargo, or cargo left
         * in stable orbit — which are how two players hand each other anything at all, v1 having no
         * direct trade UI. The details ride {@link #newStateJson} as a {@link CoopWorldEntitySpawn}.
         *
         * <p>Unlike every other kind, the {@code entityId} here is <em>coop-assigned</em> rather than
         * an engine id: {@code Misc.addCargoPods} calls {@code addCustomEntity(null, ...)}, so the
         * engine mints an id independently on each client and they never match.
         */
        SPAWN
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
        /** Consumed entities, keyed by raw entity id (the pre-12b scheme — do not migrate). */
        private final Set<String> consumedEntityIds = new HashSet<>();
        /**
         * Non-consuming deltas (CONSTRUCT/PARLEY), keyed {@code KIND:entityId} so a CONSTRUCT on
         * entity X neither blocks nor is blocked by a later CONSUME on the same X.
         */
        private final Set<String> appliedNonConsuming = new HashSet<>();

        /**
         * Applies a delta. Returns {@code true} only the first time a given delta is seen — for a
         * consuming delta that means the first time the entity is marked consumed, and for a
         * non-consuming delta (CONSTRUCT/PARLEY) the first time that (kind, entity) pair arrives.
         * Every later apply returns {@code false} (already handled: no double-loot, no re-consume,
         * and no double-apply of the host's echo rebroadcast).
         */
        public boolean apply(CoopWorldDelta delta) {
            Objects.requireNonNull(delta, "delta");
            if (!delta.consumed()) {
                // Kind-prefixed: previously these were never recorded at all, so the host's echo
                // rebroadcast came back to the originator looking like a first apply. Harmless while
                // only consumed=true deltas touched the engine; a real double-apply the moment a
                // CONSTRUCT effect (comm relay placement) is wired in.
                if (consumedEntityIds.contains(delta.entityId())) {
                    return false;
                }
                return appliedNonConsuming.add(delta.kind().name() + ":" + delta.entityId());
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
            appliedNonConsuming.clear();
        }
    }
}
