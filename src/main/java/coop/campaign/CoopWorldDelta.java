package coop.campaign;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 *
 * <p>Phase 13 adds the three host&rarr;guest skeleton mutations its runtime-content inventory found
 * beyond what Phases 9/12 already replicate: {@link Kind#DECIV}, {@link Kind#OBJECTIVE_OWNERSHIP},
 * and {@link Kind#GATE_ACTIVATED}. {@link Kind#DECIV} and {@link Kind#GATE_ACTIVATED} flow
 * host&rarr;guest only (the host owns the sims that produce them); {@link Kind#OBJECTIVE_OWNERSHIP}
 * is bidirectional since Phase 12c, because a guest can capture an objective through its own local
 * dialog. All three are captured by {@link CoopSkeletonMutationWatcher} plus a colony-deciv listener.
 *
 * <p>Phase 12c adds two more world-state values to the same poll, both bidirectional because either
 * player produces them: {@link Kind#SURVEY} (a planet's survey level) and
 * {@link Kind#RUINS_EXPLORED}.
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
        SPAWN,
        /**
         * A market on the host decivilized (Phase 13). {@code entityId} is the market id;
         * {@link #newStateJson} carries {@code fullDestroy}. Host-only capture, months-to-years
         * cadence. The guest reproduces the vanilla outcome by calling the same public static
         * {@code DecivTracker.decivilize} the host's tracker called.
         */
        DECIV,
        /**
         * A campaign objective (comm relay / nav buoy / sensor array) changed hands (Phase 13).
         * {@code entityId} is the objective's engine id — objectives are gen-time entities, so the id
         * matches across clients — and {@link #newStateJson} is the new owning faction id.
         *
         * <p>The only skeleton kind that travels both ways: the host's war sim produces most flips,
         * but the guest can also capture an objective through its own local interaction dialog, so
         * since Phase 12c both roles poll for it and report upward/outward on this kind.
         *
         * <p>{@link #latestWins() Latest-wins}: ownership oscillates (A&rarr;B&rarr;A), which a
         * set-based (kind, entity) key would swallow after the first flip.
         */
        OBJECTIVE_OWNERSHIP,
        /**
         * A story gate's activation state changed on the host (Phase 13). {@code entityId} is the
         * gate's gen-time engine id; {@link #newStateJson} is a
         * {@link CoopSkeletonMutationWatcher#encodeGateState gate-state} triple carrying the gate's
         * own {@code $gateScanned} flag plus the two sector-global gate flags.
         *
         * <p>{@link #latestWins() Latest-wins}: activation arrives in two steps (a gate is scanned
         * first, gates become usable later), so the second step must not be deduped away.
         */
        GATE_ACTIVATED,
        /**
         * A planet's {@code SurveyLevel} rose (Phase 12c). {@code entityId} is the planet's gen-time
         * engine id, so it matches across clients; {@link #newStateJson} is the
         * {@code MarketAPI.SurveyLevel} constant name.
         *
         * <p>Bidirectional: both players survey, and the level is one shared per-market field with no
         * event on four of its five mutation paths, so both roles poll for it.
         *
         * <p>{@link #latestWins() Latest-wins}: the level climbs in steps
         * (SEEN&rarr;PRELIMINARY&rarr;FULL) and a set-based (kind, entity) key would swallow every
         * step after the first, freezing the peer on the first level it heard about.
         */
        SURVEY,
        /**
         * A planet's ruins were salvaged (Phase 12c): the {@code $ruinsExplored} market-memory flag
         * that vanilla's {@code salRuins_postSalvagePerform} rule sets locally and never replicates.
         * {@code entityId} is the planet's gen-time engine id, payload {@code "true"}.
         *
         * <p><b>Not</b> latest-wins, deliberately: this is a single one-way flip
         * (false&rarr;true, never back), so the set-based (kind, entity) key is exactly right and
         * cheaper. Either dedup would behave identically here — the payload never varies.
         */
        RUINS_EXPLORED;

        /**
         * Whether the {@link Ledger} should dedup this kind by <em>payload</em> rather than by the
         * plain (kind, entity) pair. True for the Phase 13 kinds whose state is a value that can
         * change repeatedly — including changing back to a value it already held.
         */
        public boolean latestWins() {
            return this == OBJECTIVE_OWNERSHIP || this == GATE_ACTIVATED || this == SURVEY;
        }
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
         * Last applied payload for the {@link Kind#latestWins() latest-wins} kinds, keyed
         * {@code KIND:entityId}. These describe a <em>value</em> (who owns this relay, is this gate
         * usable) rather than a one-shot event, and the value can return to one it already held: a
         * comm relay flips Hegemony&rarr;pirate&rarr;Hegemony as the war sim swings, and a set-based
         * key would block every flip after the first, freezing the guest on a stale owner forever.
         * Comparing against the last payload instead applies each genuinely new state exactly once
         * while still absorbing the host's verbatim echo rebroadcast.
         */
        private final Map<String, String> latestState = new HashMap<>();

        /**
         * Applies a delta. Returns {@code true} only the first time a given delta is seen — for a
         * consuming delta that means the first time the entity is marked consumed, for a
         * latest-wins delta the first time that (kind, entity) carries this particular payload, and
         * for any other non-consuming delta (CONSTRUCT/PARLEY) the first time that (kind, entity)
         * pair arrives. Every later apply returns {@code false} (already handled: no double-loot, no
         * re-consume, and no double-apply of the host's echo rebroadcast).
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
                String key = delta.kind().name() + ":" + delta.entityId();
                if (delta.kind().latestWins()) {
                    String previous = latestState.put(key, delta.newStateJson());
                    return !Objects.equals(previous, delta.newStateJson());
                }
                return appliedNonConsuming.add(key);
            }
            return consumedEntityIds.add(delta.entityId());
        }

        /** Last payload applied for a latest-wins (kind, entity) pair, or {@code null}. */
        public String latestState(Kind kind, String entityId) {
            return latestState.get(Objects.requireNonNull(kind, "kind").name() + ":"
                    + requireText(entityId, "entityId"));
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
            latestState.clear();
        }
    }
}
