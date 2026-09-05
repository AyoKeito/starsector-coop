package coop.fleet;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.fleet.FleetMemberStatusAPI;
import com.fs.starfarer.api.fleet.RepairTrackerAPI;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.net.CoopStreamClock;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.ProxyDefaults;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 20 M4: the host-side motion range filter's arithmetic and the MTU-safe chunk packer. The
 * packer is exercised through the real encoders and the real compose path, because the acceptance
 * criterion is a byte count on the wire, not a property of an estimate.
 *
 * <p>Also the two {@code NPC_FLEET_SET} send triggers (2026-09-05): the structural hash and the
 * rate-limited health hash. Those run against a narrow one-fleet engine stub rather than the pure
 * decision alone, because the defect was never in the arithmetic — it was that nothing ever asked
 * about CR and hull.
 */
class CoopNpcFleetReplicatorTest {

    private final List<String> sent = new ArrayList<>();

    private CoopNpcFleetReplicator replicator() {
        return replicator(new CoopStreamClock());
    }

    private CoopNpcFleetReplicator replicator(CoopStreamClock streamClock) {
        CoopSessionState session = new CoopSessionState();
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        return new CoopNpcFleetReplicator(new CoopNetService(), session, () -> 1000L,
                streamClock, sent::add);
    }

    /**
     * The motion smoother divides a segment's travel by the segment's duration, and the receiver's
     * Hermite multiplies the velocity that comes out by a stream-time interval. Measured on the wall
     * clock, a fast-forwarded stride is FF times shorter than the game time it covers and every
     * non-full-fidelity fleet went on the wire at FF times its true speed.
     */
    @Test
    void motionSegmentsAreMeasuredOnStreamTimeNotWallTime() {
        CoopStreamClock streamClock = new CoopStreamClock();
        CoopNpcFleetReplicator replicator = replicator(streamClock);

        streamClock.advance(0.5f, false);

        assertEquals(500L, replicator.motionSampleClockMillis(),
                "the wall clock supplier says 1000; the segment axis must be the sample axis");
    }

    private static CoopNpcFleetMotion motion(int index) {
        return new CoopNpcFleetMotion("fleet_gen_" + index + "_pirate_raider",
                "system_askonia_inner", 12345.25f + index, -9876.5f - index, 14.25f, -3.75f,
                new CoopSensorSync.Profile(220.5f + index, 130.5f, 25.5f, 0.875f, 410.5f));
    }

    private static List<CoopNpcFleetMotion> batch(int count) {
        List<CoopNpcFleetMotion> motions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            motions.add(motion(i));
        }
        return motions;
    }

    private static int bytes(String datagram) {
        return datagram.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- chunk packing ---------------------------------------------------------------------------

    @Test
    void aBusyCoreSystemIsSplitIntoChunksThatAllFitTheBudget() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.sendMotionChunks(batch(150));

        assertTrue(sent.size() > 1, "150 fleets cannot fit one MTU-safe datagram");
        for (String datagram : sent) {
            assertTrue(bytes(datagram) <= CoopNetService.MAX_DATAGRAM_BYTES,
                    "composed datagram was " + bytes(datagram) + " B");
        }
    }

    @Test
    void everyFleetLandsInExactlyOneChunkAndTheIndicesAreDense() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.sendMotionChunks(batch(150));

        List<CoopNpcFleetMotion> rebuilt = new ArrayList<>();
        Set<Integer> chunks = new HashSet<>();
        Set<Long> epochs = new HashSet<>();
        for (String raw : sent) {
            CoopMessages.Datagram datagram = CoopMessages.parseDatagram(raw);
            assertEquals(1, datagram.sections().size(), "a first send has no redundant section");
            CoopMessages.DatagramSection section = datagram.sections().get(0);
            chunks.add(section.chunk());
            epochs.add(section.epoch());
            rebuilt.addAll(CoopNpcFleetMotion.decodeSection(section.body(), null));
        }

        assertEquals(batch(150), rebuilt, "in order, no duplicates, nothing dropped");
        assertEquals(1, epochs.size(), "all chunks of one tick share one epoch");
        for (int i = 0; i < sent.size(); i++) {
            assertTrue(chunks.contains(i), "chunk " + i + " is missing");
        }
    }

    @Test
    void theSecondTickShipsAFullBaselineAndADeltaThatStillFitsTheBudget() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.sendMotionChunks(batch(150));
        int firstTickChunks = sent.size();
        sent.clear();
        replicator.sendMotionChunks(batch(150));

        assertFalse(sent.isEmpty());
        List<CoopNpcFleetMotion> rebuilt = new ArrayList<>();
        for (String raw : sent) {
            assertTrue(bytes(raw) <= CoopNetService.MAX_DATAGRAM_BYTES,
                    "composed datagram was " + bytes(raw) + " B");
            CoopMessages.Datagram datagram = CoopMessages.parseDatagram(raw);
            assertEquals(2, datagram.sections().size(), "previous full section plus this tick's delta");
            List<List<CoopNpcFleetMotion>> decoded = CoopNpcFleetMotion.decodeDatagram(
                    List.of(datagram.sections().get(0).body(), datagram.sections().get(1).body()));
            rebuilt.addAll(decoded.get(1));
        }
        assertEquals(batch(150), rebuilt);
        assertTrue(sent.size() <= firstTickChunks + 1,
                "delta coding must not need materially more chunks than the full first tick");
    }

    @Test
    void aShrinkingBatchDropsTheChunksItNoLongerFills() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.sendMotionChunks(batch(150));
        int wide = sent.size();
        sent.clear();
        replicator.sendMotionChunks(batch(3));
        sent.clear();
        // Chunk 1 was abandoned a tick ago; if its stale baseline survived, this send would delta-code
        // against a batch from two ticks back and the receiver would resolve masks against the wrong
        // section. Every chunk beyond 0 must therefore be a first send again.
        replicator.sendMotionChunks(batch(150));

        assertTrue(wide > 1);
        for (int i = 1; i < sent.size(); i++) {
            assertEquals(1, CoopMessages.parseDatagram(sent.get(i)).sections().size(),
                    "chunk " + i + " kept a stale baseline");
        }
    }

    // ---- Phase 29 M2: redundancy depth 2 ---------------------------------------------------------

    @Test
    void atDepthTwoAFullChunkStillFitsTheBudgetOnceBothBaselinesAreCarried() {
        CoopNpcFleetReplicator replicator = replicator();
        replicator.setRedundancyDepth(2);

        // Three ticks: the third is the first one that carries two previous full sections plus its
        // own delta, which is the largest datagram this configuration can produce.
        replicator.sendMotionChunks(batch(150));
        replicator.sendMotionChunks(batch(150));
        sent.clear();
        replicator.sendMotionChunks(batch(150));

        assertFalse(sent.isEmpty());
        boolean sawThreeSections = false;
        List<CoopNpcFleetMotion> rebuilt = new ArrayList<>();
        for (String raw : sent) {
            assertTrue(bytes(raw) <= CoopNetService.MAX_DATAGRAM_BYTES,
                    "composed datagram was " + bytes(raw) + " B at depth 2");
            CoopMessages.Datagram datagram = CoopMessages.parseDatagram(raw);
            if (datagram.sections().size() == 3) {
                sawThreeSections = true;
            }
            List<String> bodies = new ArrayList<>();
            for (CoopMessages.DatagramSection section : datagram.sections()) {
                bodies.add(section.body());
            }
            List<List<CoopNpcFleetMotion>> decoded = CoopNpcFleetMotion.decodeDatagram(bodies);
            rebuilt.addAll(decoded.get(decoded.size() - 1));
        }

        assertTrue(sawThreeSections, "depth 2 must actually put three sections on the wire");
        assertEquals(batch(150), rebuilt, "the delta still resolves against the section before it");
    }

    @Test
    void raisingTheDepthDropsTheBaselinesSoNothingIsSizedUnderTheOldInvariant() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.sendMotionChunks(batch(150));
        replicator.sendMotionChunks(batch(150));
        replicator.setRedundancyDepth(2);
        sent.clear();
        replicator.sendMotionChunks(batch(150));

        for (String raw : sent) {
            assertEquals(1, CoopMessages.parseDatagram(raw).sections().size(),
                    "a depth change must re-pack from scratch, not reuse a differently sized batch");
        }
        assertEquals(2, replicator.redundancyDepth());
    }

    @Test
    void theDepthIsClampedAndAResetPutsItBack() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.setRedundancyDepth(7);
        assertEquals(coop.net.CoopDatagramRedundancy.MAX_DEPTH, replicator.redundancyDepth());
        replicator.setRedundancyDepth(0);
        assertEquals(coop.net.CoopDatagramRedundancy.DEFAULT_DEPTH, replicator.redundancyDepth());

        replicator.setRedundancyDepth(2);
        replicator.reset();
        assertEquals(coop.net.CoopDatagramRedundancy.DEFAULT_DEPTH, replicator.redundancyDepth());
    }

    @Test
    void aSmallBatchStillGoesOutAsOneChunk() {
        CoopNpcFleetReplicator replicator = replicator();

        replicator.sendMotionChunks(batch(4));

        assertEquals(1, sent.size());
        assertEquals(0, CoopMessages.parseDatagram(sent.get(0)).sections().get(0).chunk());
    }

    // ---- range filter ----------------------------------------------------------------------------

    @Test
    void theStreamingRadiusIsTheDetectionRangeWithMarginAndAFloor() {
        // Far inside the floor: a fleet nobody can see yet still streams, so that when it becomes
        // visible its interpolation buffer is already full.
        assertEquals(CoopNpcFleetReplicator.RANGE_FLOOR_SU, CoopNpcFleetReplicator.streamRadius(100f));
        assertEquals(CoopNpcFleetReplicator.RANGE_FLOOR_SU, CoopNpcFleetReplicator.streamRadius(2000f));
        // Above the floor the margin takes over.
        assertEquals(6000f, CoopNpcFleetReplicator.streamRadius(4000f));
    }

    @Test
    void anUnreadableDetectionRangeFallsBackToTheFloorRatherThanToZero() {
        assertEquals(CoopNpcFleetReplicator.RANGE_FLOOR_SU, CoopNpcFleetReplicator.streamRadius(-1f));
        assertEquals(CoopNpcFleetReplicator.RANGE_FLOOR_SU, CoopNpcFleetReplicator.streamRadius(0f));
        assertEquals(CoopNpcFleetReplicator.RANGE_FLOOR_SU,
                CoopNpcFleetReplicator.streamRadius(Float.NaN));
    }

    @Test
    void theRangeTestIsInclusiveAtTheEdge() {
        assertTrue(CoopNpcFleetReplicator.withinRange(0f, 0f, 300f, 400f, 500f));
        assertTrue(CoopNpcFleetReplicator.withinRange(0f, 0f, 3000f, 0f, 3000f));
        assertFalse(CoopNpcFleetReplicator.withinRange(0f, 0f, 3000.5f, 0f, 3000f));
        assertFalse(CoopNpcFleetReplicator.withinRange(-1000f, -1000f, 20000f, 20000f, 3000f));
    }

    // ---- set send triggers -----------------------------------------------------------------------

    @Test
    void aStructuralChangeSendsRegardlessOfTheHealthFloor() {
        // Phase 9's contract: spawn/despawn/rename/roster edits reconcile on arrival, and the guest's
        // post-battle freeze release waits on one. Rate-limiting those would be a regression.
        assertTrue(CoopNpcFleetReplicator.shouldSendSet(true, false, 0L, 10_000L));
        assertTrue(CoopNpcFleetReplicator.shouldSendSet(true, true, 0L, 10_000L));
    }

    @Test
    void aHealthOnlyChangeWaitsForTheFloorAndThenSends() {
        assertFalse(CoopNpcFleetReplicator.shouldSendSet(false, true, 9_999L, 10_000L));
        assertTrue(CoopNpcFleetReplicator.shouldSendSet(false, true, 10_000L, 10_000L));
        assertTrue(CoopNpcFleetReplicator.shouldSendSet(false, true, 60_000L, 10_000L));
    }

    @Test
    void nothingMovingSendsNothingEvenLongAfterTheFloor() {
        assertFalse(CoopNpcFleetReplicator.shouldSendSet(false, false, 10_000L, 10_000L));
        assertFalse(CoopNpcFleetReplicator.shouldSendSet(false, false, 999_000L, 0L));
    }

    /**
     * The defect this trigger exists for, end to end: a fleet whose only change is member CR. The
     * structural hash deliberately excludes CR (the 2026-08-17 rebuild storm) and the 10 Hz motion
     * datagram does not carry it, so before the health trigger this fleet produced no wire traffic at
     * all and the guest's mirror showed the damage indefinitely.
     */
    @Test
    void aCrOnlyChangeResendsTheSetOnceTheFloorHasPassed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableHealth health = new MutableHealth(0.20f, 0.30f);
        SectorAPI sector = sectorWithOneDamagedFleet(health);
        CoopNpcFleetReplicator replicator = new CoopNpcFleetReplicator(service,
                TestSessions.activeHostSession(), () -> 0L, new CoopStreamClock(), sent::add);

        replicator.sendSetIfChanged(sector, 0L);
        assertEquals(1, service.sent.size(), "the first set is always structural");
        assertEquals(CoopMessages.Type.NPC_FLEET_SET, service.sent.get(0).type());

        health.cr = 0.70f;
        replicator.sendSetIfChanged(sector, 9_999L);
        assertEquals(1, service.sent.size(), "a health-only change must respect the 10 s floor");

        replicator.sendSetIfChanged(sector, 10_000L);
        assertEquals(2, service.sent.size(), "past the floor the repaired CR has to reach the guest");

        replicator.sendSetIfChanged(sector, 90_000L);
        assertEquals(2, service.sent.size(), "health settled: no further sends, floor or not");
    }

    @Test
    void aHullOnlyChangeAlsoResendsAndTheSetCarriesTheNewValue() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableHealth health = new MutableHealth(0.90f, 0.30f);
        SectorAPI sector = sectorWithOneDamagedFleet(health);
        CoopNpcFleetReplicator replicator = new CoopNpcFleetReplicator(service,
                TestSessions.activeHostSession(), () -> 0L, new CoopStreamClock(), sent::add);

        replicator.sendSetIfChanged(sector, 0L);
        health.hullFraction = 1.0f;
        replicator.sendSetIfChanged(sector, 10_000L);

        assertEquals(2, service.sent.size());
        CoopNpcFleetSetSnapshot decoded = decodeSet(service.sent.get(1));
        assertEquals(1, decoded.fleets().size());
        assertEquals(1.0f,
                decoded.fleets().get(0).members().get(0).hullFraction(), 0.002f);
        // ...and the structural hash the guest's roster rebuild watches did not move.
        assertEquals(decodeSet(service.sent.get(0)).fleets().get(0).fleetHash(),
                decoded.fleets().get(0).fleetHash(),
                "repair must never look like a roster change to the guest");
    }

    /** The {@code set} blob out of an {@code NPC_FLEET_SET} payload. */
    private static CoopNpcFleetSetSnapshot decodeSet(CoopMessages.Message message) {
        return CoopNpcFleetSetSnapshot.decode(
                String.valueOf(CoopMessages.decodePayload(message).getOrDefault("set", "")));
    }

    // ---- one-fleet engine stub -------------------------------------------------------------------

    /** The CR and hull the stubbed ship reports; a test moves these between sends. */
    private static final class MutableHealth {
        private float cr;
        private float hullFraction;

        private MutableHealth(float cr, float hullFraction) {
            this.cr = cr;
            this.hullFraction = hullFraction;
        }
    }

    /**
     * A sector holding exactly one location holding exactly one one-ship NPC fleet. Everything the
     * replicator asks that this does not answer falls through to {@link ProxyDefaults}; the capture
     * path is defensive about all of it, which is what makes a stub this narrow viable.
     */
    private static SectorAPI sectorWithOneDamagedFleet(MutableHealth health) {
        Object repairTracker = stub(RepairTrackerAPI.class, (name, args) ->
                "getCR".equals(name) ? health.cr : null);
        Object status = stub(FleetMemberStatusAPI.class, (name, args) ->
                "getHullFraction".equals(name) ? health.hullFraction : null);
        FleetMemberAPI member = (FleetMemberAPI) stub(FleetMemberAPI.class, (name, args) -> switch (name) {
            case "getId" -> "member-1";
            case "getShipName" -> "ISS Stub";
            case "isFighterWing" -> false;
            case "getRepairTracker" -> repairTracker;
            case "getStatus" -> status;
            default -> null;
        });
        Object fleetData = stub(FleetDataAPI.class, (name, args) ->
                "getMembersListCopy".equals(name) ? new ArrayList<>(List.of(member)) : null);

        Object[] location = new Object[1];
        CampaignFleetAPI fleet = (CampaignFleetAPI) stub(CampaignFleetAPI.class, (name, args) -> switch (name) {
            case "getId" -> "fleet-1";
            case "getName" -> "Patrol";
            case "getContainingLocation" -> location[0];
            case "getLocation" -> new Vector2f(100f, 200f);
            case "getVelocity" -> new Vector2f(0f, 0f);
            case "getFleetData" -> fleetData;
            default -> null;
        });
        location[0] = stub(LocationAPI.class, (name, args) -> switch (name) {
            case "getId" -> "corvus";
            case "getFleets" -> new ArrayList<>(List.of(fleet));
            default -> null;
        });

        return (SectorAPI) stub(SectorAPI.class, (name, args) -> switch (name) {
            // Same object as the fleet's location, so the motion smoother is bypassed and the
            // position on the wire is the raw one - this test is about health, not interpolation.
            case "getCurrentLocation" -> location[0];
            case "getAllLocations" -> new ArrayList<>(List.of(location[0]));
            default -> null;
        });
    }

    private static Object stub(Class<?> type, java.util.function.BiFunction<String, Object[], Object> answers) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object answer = answers.apply(method.getName(), args);
                    return answer == null ? ProxyDefaults.defaultValue(method.getReturnType()) : answer;
                });
    }
}
