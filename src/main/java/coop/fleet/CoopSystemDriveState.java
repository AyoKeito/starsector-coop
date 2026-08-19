package coop.fleet;

/**
 * Pure decision logic for {@link CoopFullFidelitySystemDriver}: given everything observable about the
 * frame, decide whether the coop mod should be hand-advancing the guest's star system this frame, and
 * which one.
 *
 * <p>Kept separate from the engine adapter and unit-tested exhaustively because this is the part that
 * decides whether a live star system gets advanced twice, not at all, or by the wrong owner. The
 * adapter around it only does MethodHandle plumbing.
 *
 * <p><b>Precedence.</b> The checks are ordered hard-stop first: a stop condition anywhere above wins,
 * regardless of what the ones below say. Ties matter — for example {@code inFastAdvance} must beat
 * {@code guestSystemId} because during a fast advance the engine advances <em>every</em> system every
 * frame, so hand-advancing on top of it is a straight double advance.
 */
public final class CoopSystemDriveState {

    private CoopSystemDriveState() {
    }

    /** Why {@link #decide} landed where it did. One-to-one with a log line at the transition. */
    public enum Outcome {
        /** Kill switch off, or a resolve failure disabled the feature for the process. */
        DISABLED,
        /** No coop session is streaming, so there is no guest to keep in sync. */
        NO_SESSION,
        /** A save is being written; leave the engine's data structures completely alone. */
        SAVE_IN_PROGRESS,
        /** The engine is in a fast advance, where it already advances every system every frame. */
        FAST_ADVANCE,
        /** No guest mirror was found in any star system. */
        NO_GUEST_SYSTEM,
        /** The guest is in hyperspace, which has a fixed phase slot we cannot dodge. */
        GUEST_IN_HYPERSPACE,
        /** The guest is in the host's own current location; the engine already runs it at full rate. */
        HOST_LOCATION,
        /** We own the system but the engine advanced it this frame anyway — skip, do not double up. */
        ENGINE_ADVANCED,
        /** Normal case: hand-advance the guest's system at the real timestep. */
        DRIVE
    }

    /**
     * Everything the decision depends on.
     *
     * @param featureEnabled          the {@code coop.fullFidelityGuestSystem} kill switch
     * @param permanentlyDisabled     a MethodHandle resolve failed once; never try again this process
     * @param sessionStreaming        a coop session is live and this client is the host authority
     * @param saveInProgress          between {@code beforeGameSave} and {@code afterGameSave}
     * @param inFastAdvance           {@code SectorAPI.isInFastAdvance()}
     * @param guestSystemId           id of the star system holding the guest mirror, {@code ""} if none
     * @param guestInHyperspace       the guest mirror was found, but in hyperspace
     * @param hostLocationId          id of {@code SectorAPI.getCurrentLocation()}, {@code ""} if none
     * @param engineAdvancedThisFrame the system's {@code activeThisFrame()} as the engine left it
     * @param currentlyDrivenId       system id driven on the previous frame, {@code ""} if idle
     */
    public record Input(
            boolean featureEnabled,
            boolean permanentlyDisabled,
            boolean sessionStreaming,
            boolean saveInProgress,
            boolean inFastAdvance,
            String guestSystemId,
            boolean guestInHyperspace,
            String hostLocationId,
            boolean engineAdvancedThisFrame,
            String currentlyDrivenId) {

        public Input {
            guestSystemId = guestSystemId == null ? "" : guestSystemId;
            hostLocationId = hostLocationId == null ? "" : hostLocationId;
            currentlyDrivenId = currentlyDrivenId == null ? "" : currentlyDrivenId;
        }
    }

    /**
     * @param outcome        the reason, for logging
     * @param driveSystemId  system this mod owns after this frame, {@code ""} when it owns none
     * @param advanceNow     call the location's advance pair this frame
     * @param startedDriving ownership was just taken (log an info line)
     * @param stoppedDriving ownership was just released (log an info line)
     */
    public record Decision(
            Outcome outcome,
            String driveSystemId,
            boolean advanceNow,
            boolean startedDriving,
            boolean stoppedDriving) {

        /** True while this mod is responsible for advancing {@link #driveSystemId}. */
        public boolean owns() {
            return !driveSystemId.isEmpty();
        }
    }

    public static Decision decide(Input in) {
        Outcome outcome = classify(in);
        String driven = outcome == Outcome.DRIVE || outcome == Outcome.ENGINE_ADVANCED
                ? in.guestSystemId()
                : "";
        boolean advance = outcome == Outcome.DRIVE;
        boolean started = !driven.isEmpty() && !driven.equals(in.currentlyDrivenId());
        boolean stopped = !in.currentlyDrivenId().isEmpty() && !in.currentlyDrivenId().equals(driven);
        return new Decision(outcome, driven, advance, started, stopped);
    }

    private static Outcome classify(Input in) {
        if (!in.featureEnabled() || in.permanentlyDisabled()) {
            return Outcome.DISABLED;
        }
        if (!in.sessionStreaming()) {
            return Outcome.NO_SESSION;
        }
        if (in.saveInProgress()) {
            return Outcome.SAVE_IN_PROGRESS;
        }
        if (in.inFastAdvance()) {
            return Outcome.FAST_ADVANCE;
        }
        if (in.guestInHyperspace()) {
            return Outcome.GUEST_IN_HYPERSPACE;
        }
        if (in.guestSystemId().isEmpty()) {
            return Outcome.NO_GUEST_SYSTEM;
        }
        if (in.guestSystemId().equals(in.hostLocationId())) {
            return Outcome.HOST_LOCATION;
        }
        if (in.engineAdvancedThisFrame()) {
            return Outcome.ENGINE_ADVANCED;
        }
        return Outcome.DRIVE;
    }
}
