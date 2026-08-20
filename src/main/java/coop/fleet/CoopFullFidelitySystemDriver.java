package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import coop.util.CoopLog;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.List;

/**
 * Runs the star system the guest is parked in at the host's full frame rate, so it simulates at the
 * same fidelity as the system the host is standing in.
 *
 * <p><b>The problem.</b> {@code CampaignEngine.advance} runs {@code getCurrentLocation()} every frame
 * at the real timestep (line 1031 and 1078-1082), and every other star system once per 60 frames with
 * a 60x timestep (lines 1062-1077). The host is the NPC authority, so a guest standing in a system the
 * host is not in is watching a simulation that only moves once a second. Positions are shipped from
 * the host, so the guest sees fleets teleport: orbits render as polygons, approaches overshoot and
 * reverse. {@link CoopNpcFleetMotionSmoother} hides that by interpolating between the samples, which
 * is a rendering fix for a simulation problem -- the fleets really are only being thought about once a
 * second, so AI decisions, engagements and arrivals still land on a 1 Hz grid.
 *
 * <p><b>What this does instead.</b> It parks the guest's system on a phase slot the engine's frame
 * counter is not about to reach (see {@link CoopSystemPhaseSlots} for the arithmetic and for why a
 * position swap is the right lever), and then calls the same advance pair the engine calls for its own
 * current location, every frame, with the real per-frame delta. The engine's own loop finds the system
 * on a non-matching slot and does nothing but {@code setActiveThisFrame(false)}. The system is
 * advanced exactly once per frame, by us, at 1x.
 *
 * <p><b>Why it is safe to leave attached.</b> The system is never removed from the engine's
 * {@code starSystems} list, so nothing else in the game can tell the difference: id and name lookups,
 * tag scans, the sector and intel maps, {@code Misc.computeCoreWorldsExtent()}, and above all
 * serialization all still see it. {@code starSystems} is a non-transient field with no XStream
 * {@code omitField} and no custom converter, so a save taken while a system was missing from it would
 * be silently corrupt; this design cannot produce that state. If this class stops running for any
 * reason -- exception, session end, kill switch, a crash mid-frame -- the system is already in the
 * list on a normal slot and the engine simply resumes advancing it on the next stride. There is no
 * teardown that can be missed.
 *
 * <p><b>Backstop.</b> Before advancing, {@code activeThisFrame()} is read: the engine sets it true on
 * the frame it advances a non-current system (line 1065) and false otherwise (line 1073), so it is a
 * direct, public read of "did the engine already advance this system this frame". If it is ever true
 * we skip our advance for that frame rather than double it, and re-dodge. That makes a double advance
 * impossible even if the phase arithmetic is wrong.
 *
 * <p><b>Fail-safe.</b> Everything reachable only through {@link MethodHandles} ({@code starSystems},
 * {@code frame}, and the two-argument {@code advance} pair whose second parameter is the obfuscated
 * class {@code com.fs.starfarer.util.A.new}) is resolved lazily on first use; {@code java.lang.reflect}
 * is blocked by the script classloader, which is why this goes through {@code java.lang.invoke} -- the
 * proven {@code CoopBarSync} pattern. A resolve failure, or any
 * throw out of the drive, disables the feature permanently for the process after one warning and the
 * game falls back to stock stride advance plus the motion smoother. The kill switch
 * {@code -Dcoop.fullFidelityGuestSystem=false} does the same without a rebuild.
 *
 * <p>The second parameter of {@code advance}/{@code advanceEvenIfPaused} is passed as {@code null}:
 * bytecode inspection of {@code BaseLocation} shows neither method ever loads it (zero {@code aload_2}
 * in either body), and {@code StarSystem}'s override is a bare {@code super} forward. The engine
 * itself already passes {@code null} for it at line 1069.
 *
 * <p><b>Out of scope.</b> Hyperspace has a fixed phase slot of 0 (CampaignEngine.advance:1047-1049)
 * with no list position to move, so a guest in hyperspace keeps the smoother.
 */
public final class CoopFullFidelitySystemDriver {

    /** Kill switch. Defaults to on; set {@code -Dcoop.fullFidelityGuestSystem=false} to fall back. */
    public static final String ENABLED_PROPERTY = "coop.fullFidelityGuestSystem";

    /**
     * Read once at class init: it is a launch-time JVM property, so it cannot change mid-session, and
     * {@code Boolean.parseBoolean(System.getProperty(...))} on the per-frame drive was a synchronized
     * {@code Properties} lookup for an answer that never moves (perf audit #18).
     */
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));

    /** The obfuscated, unused second parameter of {@code BaseLocation.advance}. Always passed null. */
    private static final String ADVANCE_PARAM_CLASS = "com.fs.starfarer.util.A.new";

    private static boolean handlesResolved;
    private static boolean permanentlyDisabled;

    private static MethodHandle starSystemsGetter;
    private static MethodHandle frameGetter;
    private static MethodHandle advanceHandle;
    private static MethodHandle advanceEvenIfPausedHandle;
    private static MethodHandle setActiveThisFrameHandle;

    private static String drivenSystemId = "";
    private static String drivenSystemName = "";
    private static float lastFrameDelta;
    private static boolean tickedSinceFrameBoundary;
    private static boolean saveInProgress;
    private static boolean warnedNoFrameDelta;
    private static int swapCount;

    private CoopFullFidelitySystemDriver() {
    }

    // ---- Per-frame entry points -------------------------------------------------------------------

    /**
     * Advances the guest's star system for this frame. Host-only, called once per frame from
     * {@link CoopNpcFleetReplicator#tick()} while a session is streaming, before the replicator
     * samples fleet positions so the values that go on the wire are the freshest ones.
     *
     * <p>Never throws.
     */
    public static void tick(SectorAPI sector) {
        tickedSinceFrameBoundary = true;
        if (sector == null) {
            return;
        }
        try {
            runTick(sector);
        } catch (Throwable t) {
            release("drive failed");
            disablePermanently("Coop full-fidelity guest-system drive failed", t);
        }
    }

    /**
     * Called at the top of every frame by {@link CoopSystemDriveFrameHook}, whatever the session state.
     *
     * <p>Two jobs. It captures the engine's own per-frame delta -- the {@code f2} that
     * {@code CampaignEngine.advance} hands to every script (line 1094) and to its current location
     * (line 1081) -- which is the only correct timestep to hand-advance with, and is not otherwise
     * reachable from {@link CoopNpcFleetReplicator#tick()}. And it releases ownership if
     * {@link #tick(SectorAPI)} stopped being called (session ended, role changed, the pump stopped),
     * so the smoother bypass cannot latch on after the drive is gone. Releasing needs no engine work:
     * the system is still in the list and the engine picks it back up on its next stride.
     */
    public static void frameBoundary(float frameDelta) {
        if (frameDelta > 0f) {
            lastFrameDelta = frameDelta;
        }
        if (!tickedSinceFrameBoundary && !drivenSystemId.isEmpty()) {
            release("drive tick stopped");
        }
        tickedSinceFrameBoundary = false;
    }

    // ---- State queries ----------------------------------------------------------------------------

    /**
     * Id of the location this mod is advancing at full rate right now, or {@code ""}.
     *
     * <p>{@link CoopNpcFleetReplicator} uses this to bypass {@link CoopNpcFleetMotionSmoother}: a
     * driven system's positions are already full-rate truth, so interpolating them would only add a
     * frame of latency and blur. Every other non-current location keeps the smoother.
     */
    public static String drivenLocationId() {
        return drivenSystemId;
    }

    /** Number of phase-slot swaps performed. Expected to be about one per second while driving. */
    public static int swapCount() {
        return swapCount;
    }

    /** True once a resolve or drive failure has taken the feature out for this process. */
    public static boolean isPermanentlyDisabled() {
        return permanentlyDisabled;
    }

    // ---- Lifecycle --------------------------------------------------------------------------------

    /** Session (re)start: drop ownership so the next tick logs afresh. Does not clear the kill switch. */
    public static void reset() {
        release("session reset");
        swapCount = 0;
    }

    /** {@code ModPlugin.beforeGameSave()}: hold off entirely while the save is being written. */
    public static void beginSave() {
        saveInProgress = true;
        release("save in progress");
    }

    /** {@code ModPlugin.afterGameSave()}. */
    public static void endSave() {
        saveInProgress = false;
    }

    // ---- Drive ------------------------------------------------------------------------------------

    private static void runTick(SectorAPI sector) throws Throwable {
        boolean enabled = ENABLED;
        // The cheap hard stops gate the location resolve, in the same precedence order
        // CoopSystemDriveState.classify uses (kill switch, then save, then fast advance), so a frame
        // that cannot drive anything does not go looking for the guest. Ordering is load-bearing only
        // in that a stop condition must not be overtaken; the decision below still runs unchanged and
        // still emits the release transition, because beginSave() and the classifier own that.
        boolean fastAdvance = inFastAdvance(sector);
        boolean canDrive = enabled && !permanentlyDisabled && !saveInProgress && !fastAdvance;
        LocationAPI guestSystem = canDrive ? guestLocation() : null;
        boolean hyperspace = guestSystem != null && guestSystem.isHyperspace();
        String guestId = guestSystem == null || hyperspace ? "" : String.valueOf(guestSystem.getId());
        boolean engineAdvanced = !guestId.isEmpty() && activeThisFrame(guestSystem);

        CoopSystemDriveState.Decision decision = CoopSystemDriveState.decide(new CoopSystemDriveState.Input(
                enabled,
                permanentlyDisabled,
                true,
                saveInProgress,
                fastAdvance,
                guestId,
                hyperspace,
                currentLocationId(sector),
                engineAdvanced,
                drivenSystemId));

        if (decision.stoppedDriving()) {
            CoopLog.info(CoopFullFidelitySystemDriver.class,
                    "Coop full-fidelity drive released for " + drivenSystemName
                            + " (" + decision.outcome() + ")");
            drivenSystemId = "";
            drivenSystemName = "";
        }
        if (!decision.owns()) {
            return;
        }
        if (!resolveHandles(sector, guestSystem)) {
            return;
        }
        if (decision.startedDriving()) {
            drivenSystemId = decision.driveSystemId();
            drivenSystemName = nameOf(guestSystem);
            CoopLog.info(CoopFullFidelitySystemDriver.class,
                    "Coop full-fidelity drive engaged for " + drivenSystemName
                            + " (guest present, host elsewhere)");
        } else if (decision.outcome() == CoopSystemDriveState.Outcome.ENGINE_ADVANCED) {
            // The phase dodge below should make this unreachable. If it fires, the engine and this mod
            // both advanced the system this frame -- skipping ours is what keeps time from doubling.
            CoopLog.warn(CoopFullFidelitySystemDriver.class,
                    "Coop full-fidelity drive skipped a frame in " + drivenSystemName
                            + ": engine advanced it on its own phase slot");
        }

        dodgeEnginePhaseSlot(sector, guestSystem);
        // The dodge can hand the system back (it left the engine's list, or there is no free slot). If
        // it did, this frame belongs to the engine again and advancing here would double it.
        if (decision.advanceNow() && !drivenSystemId.isEmpty()) {
            advanceAsCurrentLocation(sector, guestSystem);
        }
    }

    /**
     * Moves the driven system to a list position whose phase slot the engine will not reach on this
     * frame or the next, so its stride advance never fires while we own it.
     */
    private static void dodgeEnginePhaseSlot(SectorAPI sector, LocationAPI system) throws Throwable {
        List<?> systems = (List<?>) starSystemsGetter.invoke(sector);
        if (systems == null) {
            throw new IllegalStateException("engine star system list is null");
        }
        int index = indexOfIdentity(systems, system);
        if (index < 0) {
            // Not in the engine's list at all: another mod removed it, or it was despawned. Hand it
            // back rather than advance an orphan.
            release("system left the engine list");
            return;
        }
        long frame = (long) frameGetter.invoke(sector);
        if (!CoopSystemPhaseSlots.firesSoon(index, frame)) {
            return;
        }
        int target = CoopSystemPhaseSlots.pickSafeIndex(index, systems.size(), frame);
        if (target < 0) {
            // Fewer systems than distinct slots needed. Cannot happen in a generated sector; if it
            // does, stop rather than let the stride advance land on top of ours.
            release("no free phase slot");
            return;
        }
        Collections.swap(systems, index, target);
        swapCount++;
    }

    /**
     * Replays, for one non-current system, exactly what {@code CampaignEngine.advance} does for its
     * current location: {@code advanceEvenIfPaused} unconditionally (line 1031), then -- only when
     * unpaused -- {@code setActiveThisFrame(true)} and {@code advance} (lines 1078-1082). Both take the
     * real frame delta, not the 60x stride value the non-current branch uses.
     *
     * <p>The engine splits the pair around its location loop; we issue both together at the tail of the
     * frame, which is equivalent -- a location's advance touches only its own contents, and no engine
     * code runs between the two halves that observes this system.
     */
    private static void advanceAsCurrentLocation(SectorAPI sector, LocationAPI system) throws Throwable {
        float delta = lastFrameDelta;
        if (delta <= 0f) {
            if (!warnedNoFrameDelta) {
                warnedNoFrameDelta = true;
                CoopLog.warn(CoopFullFidelitySystemDriver.class,
                        "Coop full-fidelity drive has no frame delta yet ("
                                + CoopSystemDriveFrameHook.class.getSimpleName()
                                + " not installed?); skipping until one arrives");
            }
            return;
        }
        advanceEvenIfPausedHandle.invoke(system, delta, null);
        if (!isPaused(sector)) {
            setActiveThisFrameHandle.invoke(system, true);
            advanceHandle.invoke(system, delta, null);
        }
    }

    private static void release(String reason) {
        if (!drivenSystemId.isEmpty()) {
            CoopLog.info(CoopFullFidelitySystemDriver.class,
                    "Coop full-fidelity drive released for " + drivenSystemName + " (" + reason + ")");
        }
        drivenSystemId = "";
        drivenSystemName = "";
    }

    private static void disablePermanently(String message, Throwable cause) {
        if (permanentlyDisabled) {
            return;
        }
        permanentlyDisabled = true;
        CoopLog.warn(CoopFullFidelitySystemDriver.class,
                message + "; falling back to stride advance plus motion smoothing for this session",
                cause);
    }

    // ---- MethodHandle resolution ------------------------------------------------------------------

    private static synchronized boolean resolveHandles(SectorAPI sector, LocationAPI system) {
        if (handlesResolved) {
            return !permanentlyDisabled;
        }
        handlesResolved = true;
        try {
            Class<?> engineClass = sector.getClass();
            Class<?> systemClass = system.getClass();
            Class<?> advanceParam = Class.forName(
                    ADVANCE_PARAM_CLASS, false, systemClass.getClassLoader());

            starSystemsGetter = findGetter(engineClass, "starSystems", List.class)
                    .asType(MethodType.methodType(List.class, Object.class));
            frameGetter = findGetter(engineClass, "frame", long.class)
                    .asType(MethodType.methodType(long.class, Object.class));

            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(systemClass, MethodHandles.lookup());
            MethodType advanceType = MethodType.methodType(void.class, float.class, advanceParam);
            MethodType generic = MethodType.methodType(void.class, Object.class, float.class, Object.class);
            advanceHandle = lookup.findVirtual(systemClass, "advance", advanceType).asType(generic);
            advanceEvenIfPausedHandle =
                    lookup.findVirtual(systemClass, "advanceEvenIfPaused", advanceType).asType(generic);
            setActiveThisFrameHandle = lookup
                    .findVirtual(systemClass, "setActiveThisFrame",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            return true;
        } catch (Throwable t) {
            disablePermanently("Coop could not resolve the engine handles for the full-fidelity "
                    + "guest-system drive", t);
            return false;
        }
    }

    /** Walks up from {@code start} until a class declaring {@code name} is found. */
    private static MethodHandle findGetter(Class<?> start, String name, Class<?> type) throws Throwable {
        NoSuchFieldException last = null;
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return MethodHandles.privateLookupIn(c, MethodHandles.lookup())
                        .findGetter(c, name, type);
            } catch (NoSuchFieldException ex) {
                last = ex;
            }
        }
        throw last == null ? new NoSuchFieldException(name) : last;
    }

    // ---- Engine reads (all best-effort) -----------------------------------------------------------

    /**
     * Where the guest is. Reads the O(1) mirror handle rather than scanning the sector: this runs
     * every frame, and the scan it replaces ran even on a host with no guest connected (see
     * {@link CoopGuestMirrorHandle}).
     */
    private static LocationAPI guestLocation() {
        try {
            CampaignFleetAPI mirror = CoopGuestMirrorHandle.current();
            return mirror == null ? null : mirror.getContainingLocation();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String currentLocationId(SectorAPI sector) {
        try {
            LocationAPI current = sector.getCurrentLocation();
            return current == null ? "" : String.valueOf(current.getId());
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static boolean activeThisFrame(LocationAPI location) {
        try {
            return location.activeThisFrame();
        } catch (RuntimeException | LinkageError ex) {
            // Unknown means "assume the engine did it", which costs a frame of smoothness and can
            // never cost a double advance.
            return true;
        }
    }

    private static boolean inFastAdvance(SectorAPI sector) {
        try {
            return sector.isInFastAdvance();
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    private static boolean isPaused(SectorAPI sector) {
        try {
            return sector.isPaused();
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    private static String nameOf(LocationAPI location) {
        try {
            String name = location.getName();
            return name == null || name.isEmpty() ? String.valueOf(location.getId()) : name;
        } catch (RuntimeException | LinkageError ex) {
            return "?";
        }
    }

    private static int indexOfIdentity(List<?> list, Object target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                return i;
            }
        }
        return -1;
    }
}
