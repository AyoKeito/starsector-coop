package coop.storm;

import java.util.Objects;
import java.util.UUID;

/** Only native stream-clearing and surge-window intents; never an arbitrary weather edit. */
public record CoopStormControl(String requestId, long baseRevision, Action action, float x, float y,
        float radius, float waitDays, float minSignalDays, float maxSignalDays,
        float extraRadius, float timeScale) {
    public static final float MAX_RADIUS = 100_000f;
    public enum Action { TILE_OFF, SURGE_WINDOW }

    public CoopStormControl {
        Objects.requireNonNull(requestId, "storm control request id");
        try {
            if (!UUID.fromString(requestId).toString().equals(requestId))
                throw new IllegalArgumentException("Noncanonical storm control UUID");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid storm control UUID", failure);
        }
        Objects.requireNonNull(action, "storm control action");
        if (baseRevision < 0 || !Float.isFinite(x) || !Float.isFinite(y)
                || Math.abs(x) > 1_000_000_000f || Math.abs(y) > 1_000_000_000f)
            throw new IllegalArgumentException("Invalid storm control position/revision");
        for (float value : new float[]{radius, waitDays, minSignalDays, maxSignalDays, extraRadius, timeScale})
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite storm control value");
        if (minSignalDays != -1f || maxSignalDays != -1f)
            throw new IllegalArgumentException("Storm controls cannot create arbitrary SIGNAL phases");
        if (action == Action.TILE_OFF) {
            if (radius <= 0f || radius > MAX_RADIUS || waitDays < 0f || waitDays > 1f
                    || extraRadius != 0f || timeScale != 1f)
                throw new IllegalArgumentException("Invalid native TILE_OFF control");
        } else {
            boolean ended = extraRadius == 0f && timeScale == 1f;
            boolean expanded = extraRadius > 0f && extraRadius <= 16_000f && timeScale > 0f && timeScale <= 5f;
            if (radius != 0f || waitDays != 0f || (!ended && !expanded))
                throw new IllegalArgumentException("Invalid native SURGE_WINDOW control");
        }
    }

    public static CoopStormControl tileOff(String requestId, long revision, float x, float y,
            float radius, float waitDays) {
        return new CoopStormControl(requestId, revision, Action.TILE_OFF, x, y, radius, waitDays,
                -1f, -1f, 0f, 1f);
    }

    public static CoopStormControl surgeWindow(String requestId, long revision, float x, float y,
            float extraRadius, float timeScale) {
        return new CoopStormControl(requestId, revision, Action.SURGE_WINDOW, x, y, 0f, 0f,
                -1f, -1f, extraRadius, timeScale);
    }
}
