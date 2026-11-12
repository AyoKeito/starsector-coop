package coop.campaign;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Fixed campaign-calendar grid; neither frame pacing nor terrain background batching owns fades. */
public final class CoopSlipstreamFadeDriver<S> {
    public static final long QUANTUM_CAMPAIGN_MILLIS = 72_000L;
    public static final float STEP_SECONDS = 1f / 120f;
    public static final int MAX_STEPS_PER_CALL = 4096;
    private static final int RECENT_CHECKPOINTS = 64;

    public interface Port<S> {
        S capture();
        void restore(S state);
        void advance(float seconds, boolean hostInHyperspace);
    }

    private record Checkpoint<S>(long tick, S state, boolean hostInHyperspace) { }

    private final Port<S> port;
    private final NavigableMap<Long, Checkpoint<S>> history = new TreeMap<>();
    private long originCampaignMillis;
    private long committedTick;
    private boolean hostInHyperspace;
    private boolean authoritativePaused;
    private long authoritativeCaptureMillis;
    private S state;

    public CoopSlipstreamFadeDriver(Port<S> port, CoopSlipstreamRecord.Timeline timeline) {
        this.port = Objects.requireNonNull(port, "port");
        adoptCheckpoint(timeline, port.capture());
    }

    /** Restore an authoritative native state at exactly its committed grid tick. */
    public void adoptCheckpoint(CoopSlipstreamRecord.Timeline timeline, S checkpoint) {
        Objects.requireNonNull(timeline, "timeline");
        originCampaignMillis = timeline.originCampaignMillis();
        committedTick = timeline.committedTick();
        hostInHyperspace = timeline.hostInHyperspace();
        authoritativePaused = timeline.hostPaused();
        authoritativeCaptureMillis = timeline.captureCampaignMillis();
        state = Objects.requireNonNull(checkpoint, "checkpoint");
        port.restore(state);
        history.clear();
        remember();
    }

    /** Native commands are new input at the current grid tick, never an old snapshot to overwrite. */
    public void adoptCommand(long timestamp, boolean paused, boolean inHyperspace) {
        committedTick = tickAt(timestamp);
        hostInHyperspace = inHyperspace;
        authoritativePaused = paused;
        authoritativeCaptureMillis = Math.max(timestamp, committedCampaignMillis());
        state = port.capture();
        history.clear();
        remember();
    }

    /**
     * Commit whole, constant-size steps only. A remainder stays uncommitted, so 30/60/144 FPS all
     * execute the same native operations at a shared timestamp. Large jumps are bounded per call.
     */
    public int advanceTo(long timestamp, boolean holdPausedCheckpoint) {
        if (holdPausedCheckpoint && authoritativePaused) return 0;
        if (timestamp < originCampaignMillis) return 0;
        long wanted = tickAt(timestamp);
        if (wanted < committedTick) {
            Map.Entry<Long, Checkpoint<S>> entry = history.floorEntry(wanted);
            // A received future checkpoint cannot be reversed into history we never received.
            if (entry == null) return 0;
            Checkpoint<S> rewind = entry.getValue();
            port.restore(rewind.state());
            state = rewind.state();
            committedTick = rewind.tick();
            hostInHyperspace = rewind.hostInHyperspace();
            history.tailMap(committedTick, false).clear();
        }
        long remaining = wanted - committedTick;
        int count = (int) Math.min(remaining, MAX_STEPS_PER_CALL);
        for (int i = 0; i < count; i++) {
            port.advance(STEP_SECONDS, hostInHyperspace);
            committedTick++;
        }
        if (count > 0) {
            state = port.capture();
            remember();
        }
        return count;
    }

    private long tickAt(long timestamp) {
        long delta = Math.subtractExact(timestamp, originCampaignMillis);
        return delta <= 0 ? 0 : delta / QUANTUM_CAMPAIGN_MILLIS;
    }

    private void remember() {
        history.put(committedTick, new Checkpoint<>(committedTick, state, hostInHyperspace));
        // Retain the initial anchor and a short rewind window for an intra-frame calendar slew.
        while (history.size() > RECENT_CHECKPOINTS + 1) {
            Long second = history.higherKey(history.firstKey());
            history.remove(second);
        }
    }

    public CoopSlipstreamRecord.Timeline timeline(long captureTimestamp, boolean paused) {
        // A paused host checkpoint may precede the guest's calendar catch-up. Preserve its actual
        // source timestamp rather than advancing its native state through that calendar-only jump.
        long capture = Math.max(captureTimestamp, Math.max(authoritativeCaptureMillis, committedCampaignMillis()));
        return new CoopSlipstreamRecord.Timeline(originCampaignMillis, committedTick, capture,
                paused, hostInHyperspace);
    }

    public long committedCampaignMillis() {
        return Math.addExact(originCampaignMillis,
                Math.multiplyExact(committedTick, QUANTUM_CAMPAIGN_MILLIS));
    }

    public long committedTick() { return committedTick; }
    public long originCampaignMillis() { return originCampaignMillis; }
    public boolean hostInHyperspace() { return hostInHyperspace; }
    public boolean authoritativePaused() { return authoritativePaused; }
    public void setHostPauseInput(boolean paused) { authoritativePaused = paused; }
    public S state() { return state; }
}
