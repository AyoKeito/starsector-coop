package coop.fleet;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Removes the host engine's 60-frame staircase from the replicated NPC fleet motion stream.
 *
 * <p><b>The problem.</b> {@code CampaignEngine.advance} only runs the player's <em>current</em>
 * location every frame at the real timestep. Every other star system (and hyperspace) is given a
 * phase slot by its index in the engine's private {@code starSystems} list and is advanced only on
 * frames where {@code frame % 60 == slot % 60} -- and when it is, it gets {@code dt * 60}. Total
 * simulated time is conserved; temporal resolution is 60x coarser. {@code BaseLocation.advance}
 * itself has no non-current branch: it Euler-integrates {@code location += velocity * dt} exactly as
 * usual, just with a timestep 60x larger, 60x less often.
 *
 * <p>In single player nobody ever watches a non-current system, so this is free. In co-op the guest
 * is standing in one: with the host elsewhere, the guest's system advances at ~1 Hz in ~1-second
 * jumps, and since the guest renders whatever position the host reports, every NPC fleet there
 * teleports once a second. (Fleet steering makes it worse than a clean stride -- a 1-second step
 * overshoots orbit and approach targets, so consecutive jumps can point in opposite directions.)
 * The 60 is a hard-coded local in {@code CampaignEngine.advance}, not a field, so there is nothing
 * to turn up.
 *
 * <p><b>What this does.</b> Standard interpolation buffer, applied at the sender. Per fleet it keeps
 * the last two <em>distinct</em> positions the engine reported and the wall interval between them,
 * then emits a point sliding from the older to the newer across that interval. Output is continuous
 * (each segment starts where the last one ended), never overshoots (it only ever reports positions
 * the host actually occupied), and costs one interval of latency -- about a second for a coarse
 * system, which is invisible next to the jumps it removes. The reported velocity is the segment's
 * own average, so a receiver that dead-reckons between packets stays consistent with the positions.
 *
 * <p><b>Scope and fail-safe.</b> The caller only routes fleets outside the host's current location
 * through here; anything the host is looking at keeps the untouched engine values, so the smoothing
 * cannot regress the case that already worked. A fleet that changes location, or that has not been
 * seen before, snaps -- no interpolation across a system boundary. A fleet the engine has stopped
 * moving converges on its true position and stays there. Nothing here can throw or block.
 *
 * <p>This is a source-side stand-in for the guest-side dead reckoning of Phase 29 M1; the host is
 * the right place for it because the staircase is a property of the host's simulation, not of the
 * link, and fixing it here also cleans up the positions carried by {@code NPC_FLEET_SET}.
 */
public final class CoopNpcFleetMotionSmoother {

    /** Floor on a measured segment, so a same-millisecond double sample cannot divide by ~zero. */
    static final long MIN_SEGMENT_MILLIS = 40L;

    /**
     * Ceiling on a measured segment. A fleet that sat still for a minute and then moved would
     * otherwise be given a minute-long glide; the engine's real stride is ~1 s at 60 fps, so cap
     * just above it and let the next segment correct.
     */
    static final long MAX_SEGMENT_MILLIS = 1500L;

    /** Tracks for fleets not sampled for this long are dropped (despawned, or host-local again). */
    static final long STALE_TRACK_MILLIS = 30_000L;

    private static final long PRUNE_INTERVAL_MILLIS = 5_000L;

    /** Below this the position is treated as unchanged: engine float noise is not a new segment. */
    private static final float MOVED_EPSILON = 0.05f;

    private final Map<String, Track> tracks = new HashMap<>();
    private long nextPruneAtMillis;

    /** A position/velocity pair to replicate in place of the raw engine one. */
    public record Motion(float x, float y, float velocityX, float velocityY) {
    }

    /**
     * Returns the position to replicate for one fleet this instant.
     *
     * @param fleetId    stable host-side fleet id (the mirror registry key)
     * @param locationId id of the fleet's containing location; a change snaps rather than interpolates
     * @param x          raw engine position
     * @param y          raw engine position
     * @param velocityX  raw engine velocity, used when there is no segment to average
     * @param velocityY  raw engine velocity, used when there is no segment to average
     * @param nowMillis  monotonic sample time
     */
    public Motion smooth(String fleetId, String locationId, float x, float y,
                         float velocityX, float velocityY, long nowMillis) {
        if (fleetId == null || fleetId.isEmpty()) {
            return new Motion(x, y, velocityX, velocityY);
        }
        maybePrune(nowMillis);

        String location = locationId == null ? "" : locationId;
        Track track = tracks.get(fleetId);
        if (track == null || !track.locationId.equals(location)) {
            tracks.put(fleetId, new Track(location, x, y, nowMillis));
            return new Motion(x, y, velocityX, velocityY);
        }

        track.lastSeenMillis = nowMillis;
        if (moved(track.curX, track.curY, x, y)) {
            track.segmentMillis = clampSegment(nowMillis - track.curAtMillis);
            track.prevX = track.curX;
            track.prevY = track.curY;
            track.curX = x;
            track.curY = y;
            track.curAtMillis = nowMillis;
        }

        float progress = progress(nowMillis - track.curAtMillis, track.segmentMillis);
        float outX = track.prevX + (track.curX - track.prevX) * progress;
        float outY = track.prevY + (track.curY - track.prevY) * progress;

        if (!moved(track.prevX, track.prevY, track.curX, track.curY)) {
            // No segment to average over (fleet is parked): keep the engine's own velocity.
            return new Motion(outX, outY, velocityX, velocityY);
        }
        float seconds = track.segmentMillis / 1000f;
        return new Motion(outX, outY,
                (track.curX - track.prevX) / seconds,
                (track.curY - track.prevY) / seconds);
    }

    /** Session (re)start or stream stop: forget every track so the next sample snaps. */
    public void reset() {
        tracks.clear();
        nextPruneAtMillis = 0L;
    }

    /** Number of fleets currently being interpolated. Diagnostics. */
    public int trackedCount() {
        return tracks.size();
    }

    // ---- Pure helpers (unit-tested) ---------------------------------------------------------------

    static long clampSegment(long measuredMillis) {
        if (measuredMillis < MIN_SEGMENT_MILLIS) {
            return MIN_SEGMENT_MILLIS;
        }
        return Math.min(measuredMillis, MAX_SEGMENT_MILLIS);
    }

    static float progress(long sinceMillis, long segmentMillis) {
        if (segmentMillis <= 0L || sinceMillis >= segmentMillis) {
            return 1f;
        }
        if (sinceMillis <= 0L) {
            return 0f;
        }
        return (float) sinceMillis / (float) segmentMillis;
    }

    private static boolean moved(float fromX, float fromY, float toX, float toY) {
        return Math.abs(toX - fromX) > MOVED_EPSILON || Math.abs(toY - fromY) > MOVED_EPSILON;
    }

    private void maybePrune(long nowMillis) {
        if (nowMillis < nextPruneAtMillis) {
            return;
        }
        nextPruneAtMillis = nowMillis + PRUNE_INTERVAL_MILLIS;
        Iterator<Map.Entry<String, Track>> iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().getValue().lastSeenMillis > STALE_TRACK_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static final class Track {
        private final String locationId;
        private float prevX;
        private float prevY;
        private float curX;
        private float curY;
        private long curAtMillis;
        private long segmentMillis = MIN_SEGMENT_MILLIS;
        private long lastSeenMillis;

        private Track(String locationId, float x, float y, long nowMillis) {
            this.locationId = locationId;
            this.prevX = x;
            this.prevY = y;
            this.curX = x;
            this.curY = y;
            this.curAtMillis = nowMillis;
            this.lastSeenMillis = nowMillis;
        }
    }
}
