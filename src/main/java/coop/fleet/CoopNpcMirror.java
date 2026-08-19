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

    /**
     * Re-asserts the mirror's per-frame engagement shield ({@code setNoEngaging}), which is what keeps
     * it out of engine-formed battles between snapshot applies. Defaults to a no-op so headless fakes
     * (which have no engine fleet) need no implementation.
     */
    default void assertEngagementShield() {
    }

    /** Removes the mirror fleet from the world. Idempotent. */
    void dispose();
}
