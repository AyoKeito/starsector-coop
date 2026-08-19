package coop.fleet;

import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Guest-side registry of NPC fleet mirrors keyed by host {@code coopFleetId} (Phase 9). Reconciles
 * idempotently against the full {@code NPC_FLEET_SET} (create mirrors present in the host set but
 * missing locally; dispose mirrors absent from the host set) and routes {@code NPC_FLEET_MOTION}
 * records to the matching mirror. Re-applying the same set is a no-op.
 *
 * <p>The mirror factory is injected so the reconcile logic can be unit-tested without the engine; in
 * production it creates {@link CoopFleetMirror} instances.
 *
 * <h2>Phase 15: the post-battle resurrection window</h2>
 * The guest fights a <em>mirror</em>, so a fleet it destroys dies locally while the host's set still
 * reports it alive at full strength. Between the guest's battle ending and the host rebroadcasting a
 * reconciled {@code NPC_FLEET_SET} there is a real window — the encounter dialog's recovery/salvage
 * screens, the {@code BATTLE_RESULT} round trip, the host's 1 s set cadence — in which any set
 * arriving for an unrelated reason would call {@code applySnapshot} and cheerfully <em>recreate the
 * fleet the player just killed</em>, at full roster.
 *
 * <p>The fix is a per-fleet freeze. {@link #markPendingReconcile} is called the moment the battle
 * ends (from the battle bridge's concluded hook, before the result is even built) and, while the mark
 * stands, {@link #applySet} skips {@code applySnapshot} for that id without disposing its entry. The
 * mark is released the first time any of these happens:
 * <ul>
 *   <li>the id is <b>absent</b> from an incoming set — the host confirmed the kill, so the entry is
 *       disposed like any other departure;</li>
 *   <li>the id arrives with a <b>different {@code fleetHash}</b> than it had when the mark went on —
 *       the host applied the reported post-battle roster, so this snapshot is the truth and is
 *       applied;</li>
 *   <li>{@link #PENDING_RECONCILE_TIMEOUT_MILLIS} elapses since the <em>last</em> mark — the result
 *       was lost (disconnect), and the host's authoritative state legitimately resurrects the
 *       unreported kill, exactly as the Phase 14 disconnect rule says it should. A freeze can never
 *       become permanent divergence. The battle bridge re-marks on a ~2 s cadence for as long as the
 *       fight and then the post-battle dialog actually last (a player can browse salvage for
 *       minutes), so in practice the timeout only starts counting once the bridge stops renewing —
 *       i.e. once the result has been sent or genuinely abandoned.</li>
 * </ul>
 *
 * <p>The freeze costs nothing visually: the 10 Hz {@code NPC_FLEET_MOTION} datagrams are deliberately
 * <em>not</em> gated by it, and they carry position/velocity without touching the roster, so a
 * surviving mirror keeps moving normally while its roster waits. A destroyed mirror has no fleet left
 * for motion to drive and its {@code applyMotion} no-ops on its own.
 */
public final class CoopFleetMirrorRegistry {

    /**
     * How long a mirror may stay frozen after the battle bridge's <em>last</em> re-mark. The bridge
     * renews the mark on a ~2 s cadence through the whole fight and the post-battle dialog, so this
     * does not need to outlast a slow salvage screen — it only needs to cover the {@code
     * BATTLE_RESULT} round trip after the dialog closes, with a wide margin for a lost result
     * (disconnect) to thaw into the documented Phase 14 resurrection behavior.
     */
    static final long PENDING_RECONCILE_TIMEOUT_MILLIS = 60000L;

    private final Supplier<? extends CoopNpcMirror> mirrorFactory;
    private final LongSupplier clockMillis;
    private final Map<String, CoopNpcMirror> mirrors = new LinkedHashMap<>();
    /** The {@code fleetHash} of the last snapshot actually applied per fleet (freeze release test). */
    private final Map<String, String> lastAppliedHash = new HashMap<>();
    private final Map<String, PendingReconcile> pendingReconcile = new LinkedHashMap<>();

    public CoopFleetMirrorRegistry() {
        this(CoopFleetMirror::new);
    }

    public CoopFleetMirrorRegistry(Supplier<? extends CoopNpcMirror> mirrorFactory) {
        this(mirrorFactory, System::currentTimeMillis);
    }

    public CoopFleetMirrorRegistry(Supplier<? extends CoopNpcMirror> mirrorFactory,
                                   LongSupplier clockMillis) {
        this.mirrorFactory = Objects.requireNonNull(mirrorFactory, "mirrorFactory");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Reconciles the local mirror set against the host's authoritative set. Idempotent. */
    public void applySet(CoopNpcFleetSetSnapshot set) {
        applySet(set, clockMillis.getAsLong());
    }

    /** Clock-injected form (see {@link #applySet(CoopNpcFleetSetSnapshot)}); tests drive this one. */
    public void applySet(CoopNpcFleetSetSnapshot set, long nowMillis) {
        Objects.requireNonNull(set, "set");

        Set<String> incoming = new java.util.HashSet<>();
        for (CoopNpcFleetSnapshot snapshot : set.fleets()) {
            String id = snapshot.coopFleetId();
            incoming.add(id);
            PendingReconcile pending = pendingReconcile.get(id);
            if (pending != null) {
                if (shouldDeferReassert(pending.markHash(), pending.markedAtMillis(),
                        snapshot.fleetHash(), nowMillis)) {
                    // Frozen: the local battle changed this fleet and the host has not caught up yet.
                    continue;
                }
                pendingReconcile.remove(id);
                CoopLog.debug(CoopFleetMirrorRegistry.class, "Coop mirror released from post-battle"
                        + " freeze coopFleetId=" + id + " fleetHash=" + snapshot.fleetHash());
            }
            CoopNpcMirror mirror = mirrors.get(id);
            if (mirror == null) {
                mirror = Objects.requireNonNull(mirrorFactory.get(), "mirrorFactory.get()");
                mirrors.put(id, mirror);
            }
            mirror.applySnapshot(snapshot);
            lastAppliedHash.put(id, snapshot.fleetHash());
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
            pendingReconcile.remove(id);
            lastAppliedHash.remove(id);
            if (mirror != null) {
                mirror.dispose();
            }
        }
        pendingReconcile.keySet().retainAll(mirrors.keySet());
    }

    /**
     * Freezes a mirror the local battle just changed until the host's set reflects the outcome. See
     * the class doc for the release conditions. Re-marking an already-marked fleet only refreshes the
     * clock — the hash recorded at the first mark is the one the release test compares against.
     */
    public void markPendingReconcile(String coopFleetId, long nowMillis) {
        if (coopFleetId == null || coopFleetId.isEmpty() || !mirrors.containsKey(coopFleetId)) {
            return;
        }
        PendingReconcile existing = pendingReconcile.get(coopFleetId);
        String markHash = existing != null ? existing.markHash() : lastAppliedHash.get(coopFleetId);
        pendingReconcile.put(coopFleetId,
                new PendingReconcile(markHash == null ? "" : markHash, nowMillis));
        if (existing == null) {
            // Only the first mark is worth a line: the freeze is refreshed on the battle's status
            // cadence so a long fight cannot outlive its wall-clock timeout.
            CoopLog.info(CoopFleetMirrorRegistry.class, "Coop mirror frozen pending host battle"
                    + " reconciliation coopFleetId=" + coopFleetId);
        }
    }

    /**
     * The freeze predicate, pure so it can be unit-tested: keep skipping this fleet's snapshots while
     * the mark is inside its timeout <em>and</em> the host is still reporting the same roster it had
     * before the battle.
     */
    static boolean shouldDeferReassert(String markHash, long markedAtMillis, String incomingHash,
                                       long nowMillis) {
        if (nowMillis - markedAtMillis >= PENDING_RECONCILE_TIMEOUT_MILLIS) {
            return false;
        }
        return Objects.equals(markHash == null ? "" : markHash,
                incomingHash == null ? "" : incomingHash);
    }

    /** Fleets currently frozen pending the host's battle reconciliation (diagnostics + tests). */
    public Set<String> pendingReconcileIds() {
        return new java.util.LinkedHashSet<>(pendingReconcile.keySet());
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
                // Deliberately not gated on the post-battle freeze: motion carries no roster, and a
                // surviving mirror must keep moving while its roster waits for the host.
                mirror.applyMotion(motion);
            }
        }
    }

    /**
     * Re-asserts every mirror's engagement shield. Driven once per frame by the pump: the shield is a
     * ~1 s fader that nothing else refreshes, so skipping a frame would leave the mirrors engageable.
     *
     * <p>The one exception is the mirror the local player has targeted for interaction, whose shield
     * is cleared instead so the engine's player-combat-initiation block will actually open the
     * encounter. The decision is threaded in from the pump — the mirrors never read the sector.
     *
     * <p>Phase 14b folds the per-frame sensor re-assert into the same pass, for the same reason and at
     * the same cost profile: {@link CoopNpcMirror#assertSensorState()}.
     *
     * @param playerInteractionTarget the local player fleet's interaction target, or null for none
     */
    public void assertEngagementShields(Object playerInteractionTarget) {
        for (CoopNpcMirror mirror : mirrors.values()) {
            mirror.assertEngagementShield(playerInteractionTarget);
            mirror.assertSensorState();
        }
    }

    /** Disposes every mirror and clears the registry (session end / teardown). */
    public void disposeAll() {
        for (CoopNpcMirror mirror : mirrors.values()) {
            mirror.dispose();
        }
        mirrors.clear();
        pendingReconcile.clear();
        lastAppliedHash.clear();
    }

    public int size() {
        return mirrors.size();
    }

    /** The currently mirrored host fleet ids (used by the CoopDebug NPC-set dump). */
    public Set<String> fleetIds() {
        return new java.util.LinkedHashSet<>(mirrors.keySet());
    }

    private record PendingReconcile(String markHash, long markedAtMillis) {
    }
}
