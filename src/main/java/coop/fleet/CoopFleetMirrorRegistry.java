package coop.fleet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Guest-side registry of NPC fleet mirrors keyed by host {@code coopFleetId} (Phase 9). Reconciles
 * idempotently against the full {@code NPC_FLEET_SET} (create mirrors present in the host set but
 * missing locally; dispose mirrors absent from the host set) and routes {@code NPC_FLEET_MOTION}
 * records to the matching mirror. Re-applying the same set is a no-op.
 *
 * <p>The mirror factory is injected so the reconcile logic can be unit-tested without the engine; in
 * production it creates {@link CoopFleetMirror} instances.
 */
public final class CoopFleetMirrorRegistry {
    private final Supplier<? extends CoopNpcMirror> mirrorFactory;
    private final Map<String, CoopNpcMirror> mirrors = new LinkedHashMap<>();

    public CoopFleetMirrorRegistry() {
        this(CoopFleetMirror::new);
    }

    public CoopFleetMirrorRegistry(Supplier<? extends CoopNpcMirror> mirrorFactory) {
        this.mirrorFactory = Objects.requireNonNull(mirrorFactory, "mirrorFactory");
    }

    /** Reconciles the local mirror set against the host's authoritative set. Idempotent. */
    public void applySet(CoopNpcFleetSetSnapshot set) {
        Objects.requireNonNull(set, "set");

        Set<String> incoming = new java.util.HashSet<>();
        for (CoopNpcFleetSnapshot snapshot : set.fleets()) {
            String id = snapshot.coopFleetId();
            incoming.add(id);
            CoopNpcMirror mirror = mirrors.get(id);
            if (mirror == null) {
                mirror = Objects.requireNonNull(mirrorFactory.get(), "mirrorFactory.get()");
                mirrors.put(id, mirror);
            }
            mirror.applySnapshot(snapshot);
        }

        // Dispose mirrors the host no longer reports.
        List<String> removed = new ArrayList<>();
        for (String id : mirrors.keySet()) {
            if (!incoming.contains(id)) {
                removed.add(id);
            }
        }
        for (String id : removed) {
            CoopNpcMirror mirror = mirrors.remove(id);
            if (mirror != null) {
                mirror.dispose();
            }
        }
    }

    /** Routes motion records to existing mirrors; records for unknown fleets are ignored (the next
     * set creates them). */
    public void applyMotion(List<CoopNpcFleetMotion> motions) {
        if (motions == null || motions.isEmpty()) {
            return;
        }
        for (CoopNpcFleetMotion motion : motions) {
            CoopNpcMirror mirror = mirrors.get(motion.coopFleetId());
            if (mirror != null) {
                mirror.applyMotion(motion);
            }
        }
    }

    /**
     * Re-asserts every mirror's engagement shield. Driven unconditionally once per frame by the pump:
     * the shield is a ~1 s fader that {@code applySnapshot}/{@code applyMotion} only refresh as a side
     * effect, so a gap in the host's stream would otherwise leave the mirrors engageable.
     */
    public void assertEngagementShields() {
        for (CoopNpcMirror mirror : mirrors.values()) {
            mirror.assertEngagementShield();
        }
    }

    /** Disposes every mirror and clears the registry (session end / teardown). */
    public void disposeAll() {
        for (CoopNpcMirror mirror : mirrors.values()) {
            mirror.dispose();
        }
        mirrors.clear();
    }

    public int size() {
        return mirrors.size();
    }

    /** The currently mirrored host fleet ids (used by the CoopDebug NPC-set dump). */
    public Set<String> fleetIds() {
        return new java.util.LinkedHashSet<>(mirrors.keySet());
    }
}
