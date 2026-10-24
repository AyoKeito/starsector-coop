package coop.ambient;

import java.util.Objects;
import java.util.Random;

/**
 * Campaign-thread replay slot shared by the native EPEC fork and the mod. Only primitive values
 * cross the classloader boundary; the slot is transient and is never attached to a saved object.
 */
public final class CoopEncounterReplayBridge {
    public static final String SEED_KEY = "$coopEncounterSeed";
    public static final String POINT_ID_KEY = "$coopEncounterPointId";
    public static final String POINT_X_KEY = "$coopEncounterPointX";
    public static final String POINT_Y_KEY = "$coopEncounterPointY";
    public static final String DEPTH_KEY = "$coopEncounterDepth";
    public static final String THREAT_ROLL_RAN_KEY = "$coopEncounterThreatRollRan";
    public static final String CREATOR_KEY = "$coopEncounterCreator";

    public static final String PARENT_CREATOR =
            "com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectEPEC";
    public static final String DIRE_HINTS_CREATOR =
            "com.fs.starfarer.api.impl.campaign.enc.AbyssalRogueStellarObjectDireHintsEPEC";

    private static Replay replay;
    private static Long qaSeed;

    private CoopEncounterReplayBridge() { }

    private record Replay(long seed, boolean threatRollRan, boolean threatResolved) { }

    /** Caller must always release a successfully opened slot in a finally block. */
    public static void beginReplay(long seed, boolean threatRollRan, boolean threatResolved) {
        if (replay != null || qaSeed != null) throw new IllegalStateException("An abyssal encounter seed slot is already active");
        replay = new Replay(seed, threatRollRan, threatResolved);
    }

    public static void endReplay() { replay = null; }

    /** Campaign load/teardown cannot retain a previous campaign's replay input. */
    public static void clear() { replay = null; qaSeed = null; }

    /** Exact effective seed for one QA native creation; does not enable replay or skip specials. */
    public static void beginQaSeed(long seed) {
        if (replay != null || qaSeed != null) throw new IllegalStateException("An abyssal encounter seed slot is already active");
        qaSeed = seed;
    }

    public static void endQaSeed() { qaSeed = null; }
    public static boolean hasQaSeed() { return qaSeed != null; }
    public static long qaSeed() {
        if (qaSeed == null) throw new IllegalStateException("No abyssal encounter QA seed is active");
        return qaSeed;
    }

    public static boolean isReplaying() { return replay != null; }

    public static long replaySeed() { return requireReplay().seed(); }
    public static boolean replayThreatRollRan() { return requireReplay().threatRollRan(); }
    public static boolean replayThreatResolved() { return requireReplay().threatResolved(); }

    public static boolean supportsCreator(String creatorClass) {
        return PARENT_CREATOR.equals(creatorClass) || DIRE_HINTS_CREATOR.equals(creatorClass);
    }

    /** Exactly the host's optional native threat draw, followed by its resolved outcome. */
    public static boolean applyReplayThreat(Random random) {
        Replay input = requireReplay();
        Objects.requireNonNull(random, "encounter random");
        if (input.threatRollRan()) random.nextFloat();
        return input.threatResolved();
    }

    private static Replay requireReplay() {
        Replay input = replay;
        if (input == null) throw new IllegalStateException("No abyssal encounter replay is active");
        return input;
    }
}
