package coop.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.lwjgl.util.vector.Vector2f;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopDebug;
import coop.util.CoopFrameProfiler;
import coop.util.CoopLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Host-side Phase 9 replicator: makes the entire non-player campaign fleet population
 * host-authoritative. Each tick it enumerates every real NPC fleet (skipping the local player fleet,
 * the Phase 8 guest-player mirror, and stations) and:
 *
 * <ul>
 *   <li>emits the full {@code NPC_FLEET_SET} over reliable TCP whenever its order-independent set hash
 *       changes (existence/identity/roster parity sector-wide, including off-screen fleets);</li>
 *   <li>emits {@code NPC_FLEET_MOTION} over UDP at 10 Hz for fleets in a location where either player
 *       currently is (bounded bandwidth; off-screen mirrors keep their last set position).</li>
 * </ul>
 *
 * <p>Positions leaving here are run through {@link CoopNpcFleetMotionSmoother} unless the fleet is in
 * the host's own current location. The engine advances every other location once per 60 frames with a
 * 60x timestep, so raw positions from a guest-only system arrive as a once-a-second staircase; the
 * smoother turns that back into continuous motion at the cost of one stride of latency.
 *
 * <p>Stations are skipped: they are deterministic worldgen tied to markets and already exist
 * identically on the guest (the guest suppressor likewise preserves them).
 */
public final class CoopNpcFleetReplicator {
    // Public since Phase 30: the dormant agent bridge (coop.debug) is the second reader of these
    // memory tags — its fleets dump has to report a guest mirror's coopFleetId next to its engine id.
    public static final String PLAYER_MIRROR_TAG = CoopMirrorTags.PLAYER_MIRROR_TAG;
    public static final String NPC_MIRROR_TAG = CoopMirrorTags.NPC_MIRROR_TAG;
    private static final long SET_SYNC_INTERVAL_MILLIS = 1000L;
    private static final long MOTION_INTERVAL_MILLIS = 100L;

    /**
     * Phase 20 M4 motion range filter. A fleet gets 10 Hz motion only while it is within
     * {@code max(RANGE_FLOOR_SU, RANGE_MARGIN * observer.getMaxSensorRangeToDetect(fleet))} of a
     * player position in its own location; everything else rides the {@code NPC_FLEET_SET} alone.
     *
     * <p>Detection is observer-strength times target-profile, so the radius has to be derived from
     * the engine's own answer rather than guessed at with a constant — a fleet running dark and a
     * fleet on sustained burn differ by an order of magnitude. The 1.5x margin covers the swing
     * between two samples of that answer; the 3,000 su floor means a fleet that is about to become
     * detectable has already been streaming for a while, so it does not pop into motion at the exact
     * frame it becomes visible. Without this the hyperspace case is unbounded: one player in
     * hyperspace makes every in-transit fleet in the sector eligible, 100+ fleets with no cap.
     */
    static final float RANGE_FLOOR_SU = 3000f;
    static final float RANGE_MARGIN = 1.5f;
    /**
     * How long one {@code getMaxSensorRangeToDetect} answer is reused. The call is an engine stat
     * read per (player, fleet) pair and this path runs at 10 Hz over every fleet in a player's
     * location, so it is cached for a second; a profile can swing inside that second, which is
     * exactly what the 1.5x margin is for.
     */
    private static final long RADIUS_CACHE_MILLIS = 1000L;
    /** Diagnostic cadence for the eligible/filtered counts; {@link CoopDebug}-gated. */
    private static final long RANGE_LOG_INTERVAL_MILLIS = 60_000L;
    /**
     * Slack the chunk packer holds back for stamps that grow between ticks. A chunk's budget is
     * computed from this tick's epoch and stream time, but the invariant it enforces has to hold on
     * the next tick too, where either number may have gained a decimal digit.
     */
    private static final int STAMP_GROWTH_SLACK_BYTES = 8;

    // CoopFrameProfiler section keys, compile-time constants so the hot path never builds a string.
    // Together these account for the pump's npc.syncReplication total on the host.
    private static final String SECTION_SYSTEM_DRIVER = "npc.systemDriver";
    private static final String SECTION_GUEST_PRESENCE = "npc.guestPresence";
    private static final String SECTION_SEND_SET = "npc.sendSet";
    private static final String SECTION_SEND_MOTION = "npc.sendMotion";

    private final CoopNetService service;
    private final CoopSessionState sessionState;
    private final LongSupplier clockMillis;
    /** Pump-owned (Phase 29 M1): outbound epoch + stream-time stamps for motion datagrams and sets. */
    private final coop.net.CoopStreamClock streamClock;
    /** Phase 20.1 M2: the transport router, not the raw UDP send — see {@link coop.net.CoopStateStreamSink}. */
    private final coop.net.CoopStateStreamSink stateStreamSink;
    /**
     * Phase 20 M4: what each chunk index carried on its last {@link #redundancyDepth} ticks, oldest
     * first. The newest is that chunk's delta baseline; all of them ride along as redundant full
     * sections. Keyed by chunk index rather than by fleet because a chunk's membership is whatever
     * the packer put there — the receiver resolves a delta against the section physically before it
     * in the same datagram, so the two must agree on the same slice.
     */
    private final Map<Integer, ArrayDeque<MotionChunk>> previousChunks = new HashMap<>();
    /**
     * Phase 29 M2: how many previous sends of each chunk ride along as redundancy. 1 normally, 2
     * while the pump's cadence controller is holding the floor tier for a loss reason on a UDP path
     * (see {@link coop.net.CoopDatagramRedundancy}). Depth is part of the chunk-sizing invariant, so
     * changing it drops the held baselines and re-packs from scratch.
     */
    private int redundancyDepth = coop.net.CoopDatagramRedundancy.DEFAULT_DEPTH;
    /** Cached {@code getMaxSensorRangeToDetect} answers, keyed observer-then-target; see the constants. */
    private final Map<RadiusKey, Float> radiusCache = new HashMap<>();
    private long radiusCacheExpiresAtMillis;
    private long nextRangeLogAtMillis;
    private boolean oversizedRecordWarned;
    private final CoopGuestPresence guestPresence = new CoopGuestPresence();
    private final CoopNpcFleetMotionSmoother motionSmoother = new CoopNpcFleetMotionSmoother();

    private long nextSetAtMillis;
    /**
     * Phase 29 line item (landed with 7b): NPC_FLEET_MOTION is a UDP stream stamped with stream time
     * and consumed by the receiver's interpolation buffer, so its cadence is measured in game time.
     * The NPC_FLEET_SET timer above stays on the wall clock — reliable TCP, not interpolated.
     */
    private final coop.net.CoopStreamCadence motionCadence =
            new coop.net.CoopStreamCadence(MOTION_INTERVAL_MILLIS);
    private String lastSetHash = "";
    private int lastFleetCount;
    /** Per-fleet {@code fleetHash} last printed by the {@link CoopDebug} roster diagnostic. */
    private final Map<String, String> loggedFleetHashes = new HashMap<>();

    /**
     * @param stateStreamSink where composed motion datagrams go. Mandatory since Phase 20.5: the
     *                        convenience constructor that defaulted it to {@code service::sendDatagram}
     *                        bypassed the pump's escalation router, so a caller who took it got a
     *                        motion stream that ignored the UDP-blocked fallback <em>and</em> the
     *                        over-budget TCP escalation. There is exactly one correct sink and the
     *                        pump owns it, so it is now passed rather than defaulted.
     */
    public CoopNpcFleetReplicator(CoopNetService service, CoopSessionState sessionState,
                                  LongSupplier clockMillis, coop.net.CoopStreamClock streamClock,
                                  coop.net.CoopStateStreamSink stateStreamSink) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.streamClock = Objects.requireNonNull(streamClock, "streamClock");
        this.stateStreamSink = Objects.requireNonNull(stateStreamSink, "stateStreamSink");
    }

    /**
     * Retunes the motion cadence: 100 ms at the default cadence tier, 200 ms at the floor. The pump
     * owns the decision ({@link coop.net.CoopCadenceController}) and drives both UDP state-stream
     * cadences together, so the receiver's interpolation delay — measured in send intervals — means
     * the same thing for both streams.
     */
    public void setMotionIntervalMillis(long intervalMillis) {
        motionCadence.setIntervalMillis(intervalMillis);
    }

    /**
     * Sets the motion stream's redundancy depth (Phase 29 M2), clamped to
     * [{@link coop.net.CoopDatagramRedundancy#DEFAULT_DEPTH},
     * {@link coop.net.CoopDatagramRedundancy#MAX_DEPTH}].
     *
     * <p>A change discards every held baseline. It has to: each chunk was packed against the sizing
     * invariant of the depth in force when it was packed, and keeping a depth-1-sized batch as one of
     * three sections is exactly the over-budget datagram the invariant exists to prevent. The cost is
     * one tick of full-form, no-redundancy sends while the chunks re-pack — cheaper than the
     * fragmentation it avoids, and depth changes are minutes apart by the controller's hysteresis.
     */
    public void setRedundancyDepth(int depth) {
        int clamped = Math.max(coop.net.CoopDatagramRedundancy.DEFAULT_DEPTH,
                Math.min(coop.net.CoopDatagramRedundancy.MAX_DEPTH, depth));
        if (clamped == redundancyDepth) {
            return;
        }
        redundancyDepth = clamped;
        previousChunks.clear();
    }

    /** The motion stream's redundancy depth currently in force. */
    public int redundancyDepth() {
        return redundancyDepth;
    }

    /** The motion stream's send interval in stream-time milliseconds; diagnostics and tests. */
    public long motionIntervalMillis() {
        return motionCadence.intervalMillis();
    }

    /**
     * Called every frame on the host while the session is streaming.
     *
     * <p>The four steps are separately profiled through {@link CoopFrameProfiler}'s static seam: the
     * pump can only time the whole call, and "NPC replication costs 6 ms" is not an actionable
     * measurement when the four things inside it have nothing to do with each other. Every
     * {@code section*} call below is a static boolean read and a return when profiling is off.
     */
    public void tick() {
        long now = clockMillis.getAsLong();
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return;
        }
        long t = CoopFrameProfiler.sectionStart();
        // First, before anything samples a position: run the guest's star system at the host's real
        // frame rate instead of the engine's once-per-60-frames stride, so what we ship below is a
        // full-fidelity simulation rather than a 1 Hz one. Falls back silently to the stride (and the
        // smoother in replicatedMotion) whenever it cannot or should not run.
        CoopFullFidelitySystemDriver.tick(sector);
        t = CoopFrameProfiler.sectionSplit(SECTION_SYSTEM_DRIVER, t);
        // Before sampling the population: vanilla only turns RouteManager routes into real fleets near
        // the *player* fleet, which on the host is never the guest. Publish the guest mirror as a
        // second presence so the forked RouteManager spawns (and keeps) fleets around it natively,
        // before this tick snapshots and ships the set.
        guestPresence.tick(sector, now);
        CoopFrameProfiler.sectionRecord(SECTION_GUEST_PRESENCE, t);
        if (now >= nextSetAtMillis) {
            long setStart = CoopFrameProfiler.sectionStart();
            try {
                sendSetIfChanged(sector, now);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetReplicator.class, "Failed to send NPC_FLEET_SET", ex);
            } finally {
                nextSetAtMillis = now + SET_SYNC_INTERVAL_MILLIS;
                CoopFrameProfiler.sectionRecord(SECTION_SEND_SET, setStart);
            }
        }
        if (motionCadence.shouldSend(streamClock.gameTimeMillis(), now, streamClock.isFrozen())) {
            long motionStart = CoopFrameProfiler.sectionStart();
            try {
                sendMotion(sector, now);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNpcFleetReplicator.class, "Failed to send NPC_FLEET_MOTION", ex);
            } finally {
                CoopFrameProfiler.sectionRecord(SECTION_SEND_MOTION, motionStart);
            }
        }
    }

    /** Forget the last-sent hash so the next tick rebroadcasts the full set (session (re)start). */
    public void reset() {
        lastSetHash = "";
        lastFleetCount = 0;
        loggedFleetHashes.clear();
        guestPresence.reset();
        motionSmoother.reset();
        motionCadence.reset();
        previousChunks.clear();
        redundancyDepth = coop.net.CoopDatagramRedundancy.DEFAULT_DEPTH;
        radiusCache.clear();
        radiusCacheExpiresAtMillis = 0L;
        nextRangeLogAtMillis = 0L;
        oversizedRecordWarned = false;
        CoopFullFidelitySystemDriver.reset();
    }

    /**
     * Phase 15: forget the last-sent hash so the next tick rebroadcasts the full set even if nothing
     * structural changed. Called after a {@code BATTLE_RESULT} is reconciled — a set that still
     * hashes the same (a clean disengage with no losses) is exactly the signal the guest needs to
     * release the mirrors it froze pending reconciliation
     * ({@link CoopFleetMirrorRegistry#markPendingReconcile}), so the send must not be optimised away.
     */
    public void forceResendSet() {
        lastSetHash = "";
        nextSetAtMillis = 0L;
    }

    public String lastSetHash() {
        return lastSetHash;
    }

    public int lastFleetCount() {
        return lastFleetCount;
    }

    private void sendSetIfChanged(SectorAPI sector, long now) {
        List<CoopNpcFleetSnapshot> fleets = new ArrayList<>();
        LocationAPI hostLocation = hostCurrentLocation(sector);
        // Resolved once per send, not per fleet: the two fleets the captured action text has to
        // rewrite (see CoopNpcActionTextCapture's observer-rewrite section) plus the label the guest
        // has painted on its mirror of the host player.
        CampaignFleetAPI hostPlayerFleet = sector.getPlayerFleet();
        CampaignFleetAPI guestMirror = CoopGuestMirrorHandle.current();
        String hostPlayerLabel = CoopPresenceIndicator.presenceLabel(sessionState.localName());
        forEachReplicatedFleet(sector, fleet -> fleets.add(
                toSnapshot(fleet, hostLocation, hostPlayerFleet, guestMirror, hostPlayerLabel)));
        CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.create(fleets);
        if (set.setHash().equals(lastSetHash)) {
            return;
        }
        service.send(CoopMessages.npcFleetSet(
                sessionState.sessionId(), service.nextSeq(), now,
                streamClock.gameTimeMillis(), set.encode()));
        lastSetHash = set.setHash();
        lastFleetCount = fleets.size();
        CoopLog.info(CoopNpcFleetReplicator.class,
                "Coop sent NPC_FLEET_SET fleets=" + fleets.size());
        reportRosterChanges(fleets);
    }

    /**
     * {@link CoopDebug}-gated roster diagnostic: one line per fleet whose {@code fleetHash} changed
     * (and one on first capture), naming what the host actually read off the live {@code FleetData}.
     * The guest prints the matching line from {@code CoopFleetMirror#rebuildRoster}, so a divergence
     * between "what the host says the fleet is" and "what the guest built" is a two-line diff in the
     * logs rather than an in-game errand.
     *
     * <p>Cheap by construction: it only runs on the branch that just decided the whole set changed,
     * and inside that it only prints the fleets whose own roster moved. Dormant otherwise — the
     * per-fleet hash map is not even maintained when diagnostics are off.
     */
    private void reportRosterChanges(List<CoopNpcFleetSnapshot> fleets) {
        if (!CoopDebug.diagnosticsEnabled()) {
            if (!loggedFleetHashes.isEmpty()) {
                loggedFleetHashes.clear();
            }
            return;
        }
        Set<String> present = new HashSet<>();
        for (CoopNpcFleetSnapshot fleet : fleets) {
            String id = fleet.coopFleetId();
            present.add(id);
            String previous = loggedFleetHashes.put(id, fleet.fleetHash());
            if (fleet.fleetHash().equals(previous)) {
                continue;
            }
            CoopLog.info(CoopNpcFleetReplicator.class, "Coop host fleet roster coopFleetId=" + id
                    + " name=" + fleet.name() + " faction=" + fleet.factionId()
                    + " ships=" + fleet.members().size()
                    + " [" + CoopRosterSummary.ofMembers(fleet.members()) + "]"
                    + " fleetHash=" + fleet.fleetHash());
        }
        loggedFleetHashes.keySet().retainAll(present);
    }

    private void sendMotion(SectorAPI sector, long now) {
        // O(1) handle, not a sector scan: this runs at 10 Hz and wants the same fleet the presence
        // pass already resolved (see CoopGuestMirrorHandle).
        CampaignFleetAPI guestMirror = CoopGuestMirrorHandle.current();
        List<CampaignFleetAPI> observers = playerObservers(sector, guestMirror);
        if (observers.isEmpty()) {
            return;
        }
        Set<String> playerLocations = observerLocationIds(observers);
        if (playerLocations.isEmpty()) {
            return;
        }
        expireRadiusCache(now);
        List<CoopNpcFleetMotion> motions = new ArrayList<>();
        LocationAPI hostLocation = hostCurrentLocation(sector);
        int[] filtered = new int[1];
        forEachReplicatedFleet(sector, fleet -> {
            LocationAPI loc = fleet.getContainingLocation();
            if (loc == null || !playerLocations.contains(loc.getId())) {
                return;
            }
            if (!withinStreamRange(observers, fleet, loc)) {
                filtered[0]++;
                return;
            }
            CoopNpcFleetMotionSmoother.Motion motion = replicatedMotion(fleet, loc, hostLocation);
            motions.add(new CoopNpcFleetMotion(fleet.getId(), loc.getId(),
                    motion.x(), motion.y(), motion.velocityX(), motion.velocityY(),
                    CoopSensorSync.capture(fleet)));
        });
        reportRangeFilter(now, motions.size(), filtered[0]);
        if (motions.isEmpty()) {
            previousChunks.clear();
            return;
        }
        sendMotionChunks(motions);
    }

    // ---- Phase 20 M4: MTU-safe chunking ----------------------------------------------------------

    /**
     * One chunk index's previous send: its batch, the already-encoded full section that batch will
     * ride as next tick's redundant copy, and the stamps that section went out under.
     *
     * <p>{@code fullBody} is carried rather than recomputed (red-team C10). The packer below already
     * encodes every record in full form to size it -- that is what {@code fullCost} measures -- and
     * the old code threw those strings away, then called {@code encodeFullSection} on the same batch
     * one tick later, encoding every replicated fleet twice per tick for nothing.
     */
    private record MotionChunk(List<CoopNpcFleetMotion> motions, String fullBody,
                               long epoch, long gameTimeMillis) {
    }

    /**
     * Packs the tick's motion records into as many chunk datagrams as it takes for every
     * <em>composed</em> datagram — envelope, the redundant full section, and the delta section
     * together — to stay within {@link CoopNetService#MAX_DATAGRAM_BYTES}.
     *
     * <p>Fit is measured, not estimated ({@link CoopMessages#datagramBytes}): the budget exists to
     * keep a datagram inside one IP packet, and an estimate that is one byte optimistic fragments the
     * packet it was supposed to protect. All chunks of a tick share one epoch — the receiver's
     * watermark accepts an equal epoch for a chunk it has not seen — so they may arrive in any order.
     *
     * <p><b>Why a chunk is packed to a fraction of the budget in FULL form.</b> Sizing a chunk against
     * only the datagram it is going out in is a trap that takes a tick to spring: this tick's batch is
     * <em>next</em> tick's redundant full section, so a chunk packed to fill the budget on its own
     * guarantees an over-budget datagram the moment it acquires a baseline. The stable invariant is
     * therefore per chunk — {@code overhead + (depth + 1) * fullFormBytes <= budget} — which makes any
     * {@code depth + 1} consecutive sends of a chunk fit together, since a delta section is never
     * larger than the full form of the same records. At the default depth of 1 that is the original
     * half-budget rule; at the Phase 29 M2 loss depth of 2 it is a third, so a chunk simply carries
     * fewer fleets rather than the datagram escalating onto TCP.
     */
    void sendMotionChunks(List<CoopNpcFleetMotion> motions) {
        String token = CoopMessages.wireToken(sessionState.sessionId());
        String senderId = CoopMessages.wireToken(sessionState.localPlayerId());
        long epoch = streamClock.nextEpoch();
        long gameTimeMillis = streamClock.gameTimeMillis();
        Map<Integer, ArrayDeque<MotionChunk>> next = new HashMap<>();
        int index = 0;
        int chunk = 0;
        while (index < motions.size()) {
            ArrayDeque<MotionChunk> held = previousChunks.get(chunk);
            List<MotionChunk> previousSends = held == null ? List.of() : new ArrayList<>(held);
            // The delta baseline is the newest held send: the section physically before the current
            // one in the packet, which is what the receiver resolves the delta against.
            MotionChunk previous = previousSends.isEmpty()
                    ? null : previousSends.get(previousSends.size() - 1);
            Map<String, CoopNpcFleetMotion> baseline = previous == null
                    ? Map.of() : indexById(previous.motions());
            int overhead = composedOverheadBytes(token, senderId, chunk, previousSends, epoch,
                    gameTimeMillis) + STAMP_GROWTH_SLACK_BYTES;
            int used = overhead + CoopNpcFleetMotion.MODE_DELTA.length();
            for (MotionChunk heldSend : previousSends) {
                used += CoopMessages.utf8Length(heldSend.fullBody());
            }
            // The full-form size of what this chunk is taking, which is what it will cost as next
            // tick's baseline section.
            int fullForm = CoopNpcFleetMotion.MODE_FULL.length();
            StringBuilder delta = new StringBuilder(512).append(CoopNpcFleetMotion.MODE_DELTA);
            // Built alongside the delta out of the full-form records the sizing pass already
            // encodes; this is what the chunk hands the next tick as its baseline section.
            StringBuilder full = new StringBuilder(512).append(CoopNpcFleetMotion.MODE_FULL);
            List<CoopNpcFleetMotion> taken = new ArrayList<>();
            while (index < motions.size()) {
                CoopNpcFleetMotion motion = motions.get(index);
                String record = CoopNpcFleetMotion.encodeRecord(motion,
                        baseline.get(motion.coopFleetId()));
                String fullRecord = CoopNpcFleetMotion.encodeRecord(motion, null);
                int cost = 1 + CoopMessages.utf8Length(record);
                int fullCost = 1 + CoopMessages.utf8Length(fullRecord);
                boolean overNow = used + cost > CoopNetService.MAX_DATAGRAM_BYTES;
                boolean overNextTick = overhead + (redundancyDepth + 1) * (fullForm + fullCost)
                        > CoopNetService.MAX_DATAGRAM_BYTES;
                if (!taken.isEmpty() && (overNow || overNextTick)) {
                    break;
                }
                delta.append('\n').append(record);
                full.append('\n').append(fullRecord);
                used += cost;
                fullForm += fullCost;
                taken.add(motion);
                index++;
                if (used > CoopNetService.MAX_DATAGRAM_BYTES) {
                    // A single record that does not fit on its own. Impossible with today's field
                    // set (a record is ~70 B), so this is a format change nobody sized — ship it and
                    // let the transport's own cap and the escalation path decide, but say so once.
                    warnOversizedMotionRecord(used);
                    break;
                }
            }
            stateStreamSink.send(coop.net.CoopDatagramRedundancy.composeWithBaselines(
                    token, senderId, CoopMessages.Type.NPC_FLEET_MOTION,
                    baselineSections(previousSends, chunk),
                    epoch, gameTimeMillis, chunk, delta.toString()));
            ArrayDeque<MotionChunk> updated = new ArrayDeque<>(previousSends);
            updated.addLast(new MotionChunk(taken, full.toString(), epoch, gameTimeMillis));
            while (updated.size() > redundancyDepth) {
                updated.removeFirst();
            }
            next.put(chunk, updated);
            chunk++;
        }
        // Chunks the tick no longer fills must not keep a stale baseline: the next tick that reaches
        // that index would delta-code against a batch from an arbitrary point in the past.
        previousChunks.clear();
        previousChunks.putAll(next);
    }

    /** The held sends as wire sections carrying their full bodies, oldest first. */
    private static List<CoopMessages.DatagramSection> baselineSections(List<MotionChunk> previousSends,
                                                                      int chunk) {
        List<CoopMessages.DatagramSection> sections = new ArrayList<>(previousSends.size());
        for (MotionChunk send : previousSends) {
            sections.add(new CoopMessages.DatagramSection(send.epoch(), send.gameTimeMillis(),
                    chunk, send.fullBody()));
        }
        return sections;
    }

    /** The composed datagram's size with every body empty; adding the body lengths is then exact. */
    private static int composedOverheadBytes(String token, String senderId, int chunk,
                                             List<MotionChunk> previousSends, long epoch,
                                             long gameTimeMillis) {
        List<CoopMessages.DatagramSection> probe = new ArrayList<>(previousSends.size() + 1);
        for (MotionChunk send : previousSends) {
            probe.add(new CoopMessages.DatagramSection(send.epoch(), send.gameTimeMillis(), chunk, ""));
        }
        probe.add(new CoopMessages.DatagramSection(epoch, gameTimeMillis, chunk, ""));
        return CoopMessages.datagramBytes(token, senderId,
                CoopMessages.Type.NPC_FLEET_MOTION, probe);
    }

    private void warnOversizedMotionRecord(int bytes) {
        if (oversizedRecordWarned) {
            return;
        }
        oversizedRecordWarned = true;
        CoopLog.warn(CoopNpcFleetReplicator.class, "Coop a single NPC_FLEET_MOTION record composes to "
                + bytes + " B, above the " + CoopNetService.MAX_DATAGRAM_BYTES
                + " B budget; it cannot be chunked further");
    }

    private static Map<String, CoopNpcFleetMotion> indexById(List<CoopNpcFleetMotion> motions) {
        Map<String, CoopNpcFleetMotion> byId = new HashMap<>(Math.max(4, motions.size() * 2));
        for (CoopNpcFleetMotion motion : motions) {
            byId.put(motion.coopFleetId(), motion);
        }
        return byId;
    }

    // ---- Phase 20 M4: motion range filter --------------------------------------------------------

    /** True when any observer in the fleet's own location is close enough to be streamed its motion. */
    private boolean withinStreamRange(List<CampaignFleetAPI> observers, CampaignFleetAPI fleet,
                                      LocationAPI loc) {
        Vector2f target = safeLocation(fleet);
        if (target == null) {
            // No position to compare: stream it rather than silently freeze a mirror.
            return true;
        }
        for (CampaignFleetAPI observer : observers) {
            LocationAPI observerLoc = safeContainingLocation(observer);
            if (observerLoc == null || !loc.getId().equals(observerLoc.getId())) {
                continue;
            }
            Vector2f eye = safeLocation(observer);
            if (eye == null) {
                continue;
            }
            if (withinRange(eye.x, eye.y, target.x, target.y, cachedRadius(observer, fleet))) {
                return true;
            }
        }
        return false;
    }

    /** The streaming radius for one detection range; see {@link #RANGE_FLOOR_SU}. */
    static float streamRadius(float maxSensorRangeToDetect) {
        if (!Float.isFinite(maxSensorRangeToDetect) || maxSensorRangeToDetect <= 0f) {
            return RANGE_FLOOR_SU;
        }
        return Math.max(RANGE_FLOOR_SU, RANGE_MARGIN * maxSensorRangeToDetect);
    }

    /** Squared-distance test; pure so the filter's arithmetic is testable without a sector. */
    static boolean withinRange(float observerX, float observerY, float targetX, float targetY,
                               float radius) {
        float dx = observerX - targetX;
        float dy = observerY - targetY;
        return dx * dx + dy * dy <= radius * radius;
    }

    /**
     * The (observer, target) pair this cache is keyed by. A record rather than the concatenated
     * {@code "a->b"} string it replaced (red-team C10): this runs at 10 Hz for every observer-fleet
     * pair in a location, and the string form allocated a builder plus a String per lookup, on the
     * hot path, purely to be hashed and thrown away.
     */
    private record RadiusKey(String observerId, String targetId) {
    }

    private float cachedRadius(CampaignFleetAPI observer, CampaignFleetAPI fleet) {
        RadiusKey key = new RadiusKey(safeId(observer), safeId(fleet));
        Float cached = radiusCache.get(key);
        if (cached != null) {
            return cached;
        }
        float radius = streamRadius(maxSensorRangeToDetect(observer, fleet));
        radiusCache.put(key, radius);
        return radius;
    }

    /** Whole-cache expiry rather than per-entry: one comparison per tick, and it cannot leak. */
    private void expireRadiusCache(long now) {
        if (now < radiusCacheExpiresAtMillis) {
            return;
        }
        radiusCache.clear();
        radiusCacheExpiresAtMillis = now + RADIUS_CACHE_MILLIS;
    }

    private static float maxSensorRangeToDetect(CampaignFleetAPI observer, CampaignFleetAPI target) {
        try {
            return observer.getMaxSensorRangeToDetect(target);
        } catch (RuntimeException | LinkageError ex) {
            return -1f;
        }
    }

    /**
     * One line a minute naming how much the filter is actually saving; {@link CoopDebug}-gated
     * because this runs at 10 Hz and a per-tick count would be a log flood.
     */
    private void reportRangeFilter(long now, int eligible, int filtered) {
        if (!CoopDebug.diagnosticsEnabled()) {
            nextRangeLogAtMillis = 0L;
            return;
        }
        if (now < nextRangeLogAtMillis) {
            return;
        }
        nextRangeLogAtMillis = now + RANGE_LOG_INTERVAL_MILLIS;
        CoopLog.info(CoopNpcFleetReplicator.class, "Coop motion range filter eligible=" + eligible
                + " filtered=" + filtered + " (floor=" + (int) RANGE_FLOOR_SU + "su margin="
                + RANGE_MARGIN + "x)");
    }

    private CoopNpcFleetSnapshot toSnapshot(CampaignFleetAPI fleet, LocationAPI hostLocation,
                                            CampaignFleetAPI hostPlayerFleet,
                                            CampaignFleetAPI guestMirror, String hostPlayerLabel) {
        LocationAPI loc = fleet.getContainingLocation();
        CoopNpcFleetMotionSmoother.Motion motion = replicatedMotion(fleet, loc, hostLocation);
        return CoopNpcFleetSnapshot.create(
                fleet.getId(),
                factionId(fleet),
                fleet.getName() == null ? "" : fleet.getName(),
                loc == null ? "" : loc.getId(),
                motion.x(), motion.y(),
                motion.velocityX(), motion.velocityY(),
                transponderOn(fleet),
                CoopSensorSync.capture(fleet),
                // Phase 9b: the tooltip's action line ("travelling to Jangala", "pursuing your
                // fleet"). Stubbed "" from Phase 9 until 2026-08-20 — the guest mirror has no AI
                // state to derive it from, so it has to ride the wire.
                CoopNpcActionTextCapture.capture(fleet, hostPlayerFleet, guestMirror, hostPlayerLabel),
                CoopFleetSnapshotFactory.captureMembers(fleet));
    }

    /**
     * The axis NPC motion segments are measured on: stream (game) time, the same axis the samples are
     * stamped with and the same one the receiver's cursor runs on.
     *
     * <p>It used to be the pump's wall clock, and that was a unit error (2026-09-04). The smoother
     * derives a velocity as {@code segment delta / segment seconds}, and the receiver's Hermite
     * multiplies that velocity by a stream-time interval to build its tangents. Under fast-forward a
     * wall-measured stride is FF times shorter than the game-time stride it covers, so every
     * non-full-fidelity fleet went on the wire at FF times its true speed: tangents several times the
     * chord, a mirror that runs ahead early in each segment and behind late, and a {@code setVelocity}
     * that pulses at the sample rate. Measuring both ends on the same clock removes the factor, and
     * makes {@link CoopNpcFleetMotionSmoother}'s MIN/MAX segment constants game-time values matching
     * the engine's own stride.
     */
    long motionSampleClockMillis() {
        return streamClock.gameTimeMillis();
    }

    /**
     * The position/velocity to put on the wire for one fleet.
     *
     * <p>Fleets in the host's current location are reported verbatim: the engine advances that
     * location every frame at the real timestep, so the raw values are already smooth and adding an
     * interpolation delay would only cost latency. Everywhere else the engine advances the location
     * once every 60 frames with a 60x timestep (see {@link CoopNpcFleetMotionSmoother}), which is
     * what makes NPC fleets teleport on a guest standing in a system the host is not in — those go
     * through the smoother.
     *
     * <p>A system {@link CoopFullFidelitySystemDriver} is currently driving is reported verbatim for
     * the same reason the host's own location is: it is being advanced every frame at the real
     * timestep, so there is no staircase left to interpolate away and smoothing would only cost a
     * stride of latency. The smoother stays in place for every case the driver does not cover — kill
     * switch off, resolve failure, guest in hyperspace, or any other non-current location.
     *
     * <p>The smoother is clocked on {@link #motionSampleClockMillis()}, not on the wall clock.
     */
    private CoopNpcFleetMotionSmoother.Motion replicatedMotion(CampaignFleetAPI fleet, LocationAPI loc,
                                                               LocationAPI hostLocation) {
        Vector2f pos = fleet.getLocation();
        Vector2f vel = fleet.getVelocity();
        float x = pos == null ? 0f : pos.x;
        float y = pos == null ? 0f : pos.y;
        float vx = vel == null ? 0f : vel.x;
        float vy = vel == null ? 0f : vel.y;
        if (loc == null || (hostLocation != null && loc == hostLocation) || isFullFidelityDriven(loc)) {
            return new CoopNpcFleetMotionSmoother.Motion(x, y, vx, vy);
        }
        try {
            return motionSmoother.smooth(fleet.getId(), loc.getId(), x, y, vx, vy,
                    motionSampleClockMillis());
        } catch (RuntimeException | LinkageError ex) {
            return new CoopNpcFleetMotionSmoother.Motion(x, y, vx, vy);
        }
    }

    /** True while {@link CoopFullFidelitySystemDriver} is advancing this location at the real rate. */
    private static boolean isFullFidelityDriven(LocationAPI loc) {
        String driven = CoopFullFidelitySystemDriver.drivenLocationId();
        return !driven.isEmpty() && driven.equals(loc.getId());
    }

    private static LocationAPI hostCurrentLocation(SectorAPI sector) {
        try {
            return sector.getCurrentLocation();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * The two fleets that define "where a player is": the host's own fleet and the Phase 8 guest
     * mirror. Since Phase 20 M4 the motion filter needs the fleets themselves, not just their
     * location ids — the streaming radius is derived per (observer, target) pair from the engine's
     * detection math.
     */
    private static List<CampaignFleetAPI> playerObservers(SectorAPI sector,
                                                          CampaignFleetAPI guestMirror) {
        List<CampaignFleetAPI> observers = new ArrayList<>(2);
        try {
            CampaignFleetAPI player = sector.getPlayerFleet();
            if (player != null) {
                observers.add(player);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // best-effort
        }
        if (guestMirror != null) {
            observers.add(guestMirror);
        }
        return observers;
    }

    private static Set<String> observerLocationIds(List<CampaignFleetAPI> observers) {
        Set<String> ids = new HashSet<>();
        for (CampaignFleetAPI observer : observers) {
            LocationAPI loc = safeContainingLocation(observer);
            if (loc != null) {
                ids.add(loc.getId());
            }
        }
        return ids;
    }

    private static LocationAPI safeContainingLocation(CampaignFleetAPI fleet) {
        try {
            return fleet.getContainingLocation();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static Vector2f safeLocation(CampaignFleetAPI fleet) {
        try {
            return fleet.getLocation();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String safeId(CampaignFleetAPI fleet) {
        try {
            String id = fleet.getId();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    /** Iterates every real NPC fleet: not the local player, not a coop mirror, not a station. */
    private void forEachReplicatedFleet(SectorAPI sector, Consumer<CampaignFleetAPI> consumer) {
        CampaignFleetAPI player = sector.getPlayerFleet();
        CoopLocations.forEach(sector, loc -> {
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet == null || fleet == player) {
                    continue;
                }
                if (isCoopMirror(fleet) || fleet.isStationMode()) {
                    continue;
                }
                consumer.accept(fleet);
            }
        });
    }

    private static boolean isCoopMirror(CampaignFleetAPI fleet) {
        return isPlayerMirror(fleet) || hasTag(fleet, NPC_MIRROR_TAG);
    }

    private static boolean isPlayerMirror(CampaignFleetAPI fleet) {
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        return memory != null && memory.getBoolean(PLAYER_MIRROR_TAG);
    }

    private static boolean hasTag(CampaignFleetAPI fleet, String key) {
        MemoryAPI memory = fleet.getMemoryWithoutUpdate();
        return memory != null && memory.contains(key);
    }

    private static String factionId(CampaignFleetAPI fleet) {
        try {
            if (fleet.getFaction() != null) {
                return fleet.getFaction().getId();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
    }

    private static boolean transponderOn(CampaignFleetAPI fleet) {
        try {
            return fleet.isTransponderOn();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private SectorAPI sectorOrNull() {
        try {
            return Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
