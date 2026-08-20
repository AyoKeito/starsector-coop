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
     * it out of engine-formed battles — the driving paths never touch it, so this pass is the whole
     * shield. Defaults to a no-op so headless fakes (which have no engine fleet) need no implementation.
     *
     * <p>{@code playerInteractionTarget} is the local player fleet's current interaction target,
     * supplied by the pump (the mirrors never read {@code Global} themselves). The one mirror that
     * <em>is</em> that target has its shield cleared instead of asserted, which is what lets the guest
     * engage a host-owned NPC fleet at all; see
     * {@link CoopFleetMirror#assertEngagementShield(Object, long)}.
     *
     * <p>{@code nowMillis} is the pump's wall clock. The pass runs every frame; the engine call it
     * makes is rate-limited against this clock, because the fader it refreshes lasts ~1 s and building
     * a new one every frame per mirror is pure allocation.
     */
    default void assertEngagementShield(Object playerInteractionTarget, long nowMillis) {
    }

    /**
     * Re-asserts the mirror's replicated sensor identity (Phase 14b). Like the shield this has to run
     * every frame, because the engine rewrites a fleet's sensor strength from its roster every frame
     * and the local terrain plugins re-apply their own detectability mods every frame — see
     * {@link CoopSensorSync}. Defaults to a no-op for headless fakes.
     */
    default void assertSensorState() {
    }

    /** Removes the mirror fleet from the world. Idempotent. */
    void dispose();
}
