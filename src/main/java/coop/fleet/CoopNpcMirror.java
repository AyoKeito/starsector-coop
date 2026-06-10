package coop.fleet;

/**
 * A guest-side mirror of one host NPC fleet, driven by replicated Phase 9 packets. Extracted as an
 * interface so {@link CoopFleetMirrorRegistry}'s reconcile logic can be unit-tested with a fake
 * implementation (the real {@link CoopFleetMirror} touches the engine and cannot run headless).
 */
public interface CoopNpcMirror {
    /** Full apply from an {@code NPC_FLEET_SET} entry: create-if-needed, place, move, refresh roster. */
    void applySnapshot(CoopNpcFleetSnapshot snapshot);

    /** Lightweight position/velocity update from an {@code NPC_FLEET_MOTION} record. */
    void applyMotion(CoopNpcFleetMotion motion);

    /** Removes the mirror fleet from the world. Idempotent. */
    void dispose();
}
