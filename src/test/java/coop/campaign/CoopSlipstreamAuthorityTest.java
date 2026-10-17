package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CoopSlipstreamAuthorityTest {
    @Test
    void initialAndResumeSetsPrecedeEveryAddAndUseCurrentFadeState() {
        Fixture f = new Fixture();
        f.world.host = List.of(stream("a", false), stream("b", true));
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_SET, CoopMessages.Type.STREAM_ADD,
                CoopMessages.Type.STREAM_ADD), f.types());
        assertEquals(List.of("a", "b"), CoopSlipstreamRecord.decodeIds(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "streams")));
        f.net.sent.clear();
        f.world.host = List.of(stream("a", true));
        f.authority.rearmFullBroadcast();
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_SET, CoopMessages.Type.STREAM_ADD), f.types());
        assertNotNull(CoopSlipstreamRecord.decode(CoopMessages.requiredPayloadString(
                f.net.sent.get(1), "stream")).fade().despawnNoise());
    }

    @Test
    void pollsEvery250msRefreshesActiveFadesAndKeepsMatureStreamsQuiet() {
        Fixture f = new Fixture();
        f.world.host = List.of(stream("a", false));
        f.authority.tickHost();
        f.net.sent.clear();
        f.world.host = List.of(stream("a", true), stream("b", false));
        f.now.set(249);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
        f.now.set(250);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_ADD, CoopMessages.Type.STREAM_ADD), f.types());
        CoopSlipstreamRecord d = CoopSlipstreamRecord.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream"));
        assertEquals("a", d.hostId());
        assertEquals(-1f, d.fade().despawnDelay());
        f.net.sent.clear();
        f.now.set(500);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_ADD), f.types());
        assertEquals("a", CoopSlipstreamRecord.decode(CoopMessages.requiredPayloadString(
                f.net.sent.get(0), "stream")).hostId());
        f.net.sent.clear();
        f.world.host = List.of(stream("b", false));
        f.now.set(750);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_REMOVE), f.types());
        assertEquals("a", CoopMessages.requiredPayloadString(f.net.sent.get(0), "streamId"));
    }

    @Test
    void unavailableScanCannotRemoveKnownStreamsButEmptyScanCan() {
        Fixture f = new Fixture();
        f.world.host = List.of(stream("a", false));
        f.authority.tickHost();
        f.net.sent.clear();
        f.world.host = null;
        f.now.set(250);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of();
        f.now.set(500);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_REMOVE), f.types());
    }

    @Test
    void despawnResetsAndProgressCarryFullNativeCheckpointsInsteadOfPartialEvents() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord initial = stream("surge", true);
        f.world.host = List.of(initial);
        f.authority.tickHost();
        f.net.sent.clear();
        var changed = new CoopSlipstreamRecord.Fade(null, 0, 0,
                new float[]{1, .1f, 0}, 0, .2f, 0);
        f.world.host = List.of(new CoopSlipstreamRecord(initial.hostId(), initial.kind(),
                initial.params(), initial.terrainTags(), initial.segments(), changed, true));
        f.now.set(250);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_ADD), f.types());
        CoopSlipstreamRecord reset = CoopSlipstreamRecord.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream"));
        assertEquals(.2f, reset.fade().despawnDays());
        assertEquals(initial.segments(), reset.segments());
        assertTrue(reset.unfinishedSurgeFadeIn());
        f.net.sent.clear();
        var progressing = new CoopSlipstreamRecord.Fade(null, 0, 0,
                new float[]{1, .1f, 0}, -.01f, .2f, .01f);
        f.world.host = List.of(new CoopSlipstreamRecord(initial.hostId(), initial.kind(),
                initial.params(), initial.terrainTags(), initial.segments(), progressing, true));
        f.now.set(500);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_ADD), f.types());
        assertEquals(progressing, CoopSlipstreamRecord.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream")).fade());
    }

    @Test
    void spawnNoiseSegmentFadersAndNativeSurgeWavesAllReceiveActiveCheckpoints() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord mature = stream("mature", false);
        CoopSlipstreamRecord spawn = withState(mature, "spawn",
                new CoopSlipstreamRecord.Fade(new float[]{0, 1}, 2, .2f, null, 0, 0, 0),
                mature.segments(), CoopSlipstreamRecord.SurgeWaveState.absent(), mature.timeline());
        var fading = new CoopSlipstreamRecord.Fader(.5f, 1, 1,
                CoopSlipstreamRecord.FaderState.OUT, false, false);
        List<CoopSlipstreamRecord.Segment> segments = new ArrayList<>(mature.segments());
        segments.set(1, new CoopSlipstreamRecord.Segment(1000, 0, 200, 1, fading));
        CoopSlipstreamRecord segment = withState(mature, "segment", CoopSlipstreamRecord.Fade.idle(),
                segments, CoopSlipstreamRecord.SurgeWaveState.absent(), mature.timeline());
        CoopSlipstreamRecord wave = withState(mature, "wave", CoopSlipstreamRecord.Fade.idle(),
                mature.segments(), new CoopSlipstreamRecord.SurgeWaveState(true, false, 1, .1f, 4),
                mature.timeline());
        f.world.host = List.of(mature, spawn, segment, wave);
        f.authority.tickHost();
        f.net.sent.clear();

        f.now.set(250);
        f.authority.tickHost();

        assertEquals(List.of(CoopMessages.Type.STREAM_ADD, CoopMessages.Type.STREAM_ADD,
                CoopMessages.Type.STREAM_ADD), f.types());
        assertEquals(List.of("spawn", "segment", "wave"), f.net.sent.stream().map(message ->
                CoopSlipstreamRecord.decode(CoopMessages.requiredPayloadString(message, "stream")).hostId()).toList());
    }

    @Test
    void finalIdleCheckpointClosesAnActiveTransitionExactlyOnce() {
        Fixture f = new Fixture();
        f.world.host = List.of(stream("settled", true));
        f.authority.tickHost();
        f.net.sent.clear();
        CoopSlipstreamRecord mature = stream("settled", false);
        f.world.host = List.of(withState(mature, mature.hostId(), CoopSlipstreamRecord.Fade.idle(),
                mature.segments(), CoopSlipstreamRecord.SurgeWaveState.absent(), mature.timeline()));

        f.now.set(250);
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_ADD), f.types());
        assertNull(CoopSlipstreamRecord.decode(CoopMessages.requiredPayloadString(
                f.net.sent.get(0), "stream")).fade().despawnNoise());
        f.net.sent.clear();
        f.now.set(500);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
    }

    @Test
    void terminalFaderEnumsStayQuietWithoutABounceInTheNextDirection() {
        Fixture f = new Fixture();
        f.world.host = List.of(
                faderStream("in", CoopSlipstreamRecord.FaderState.IN, 1, false, false),
                faderStream("out", CoopSlipstreamRecord.FaderState.OUT, 0, false, false),
                faderStream("in-wrong-bounce", CoopSlipstreamRecord.FaderState.IN, 1, true, false),
                faderStream("out-wrong-bounce", CoopSlipstreamRecord.FaderState.OUT, 0, false, true),
                faderStream("idle", CoopSlipstreamRecord.FaderState.IDLE, .5f, true, true));
        f.authority.tickHost();
        assertEquals(6, f.net.sent.size(), "the initial manifest still includes every settled stream");
        f.net.sent.clear();

        for (long time : List.of(250L, 500L, 750L)) {
            f.now.set(time);
            f.authority.tickHost();
            assertTrue(f.net.sent.isEmpty(), "terminal enum and irrelevant bounce flag cannot keep broadcasting");
        }
    }

    @Test
    void partialAndCorrectlyDirectedBouncingFadersKeepSendingCheckpoints() {
        Fixture f = new Fixture();
        f.world.host = List.of(
                faderStream("in-partial", CoopSlipstreamRecord.FaderState.IN, .999f, false, false),
                faderStream("out-partial", CoopSlipstreamRecord.FaderState.OUT, .001f, false, false),
                faderStream("in-bounce-down", CoopSlipstreamRecord.FaderState.IN, 1, false, true),
                faderStream("out-bounce-up", CoopSlipstreamRecord.FaderState.OUT, 0, true, false));
        f.authority.tickHost();
        f.net.sent.clear();

        for (long time : List.of(250L, 500L)) {
            f.now.set(time);
            f.authority.tickHost();
            assertEquals(List.of("in-partial", "out-partial", "in-bounce-down", "out-bounce-up"),
                    f.net.sent.stream().map(message -> CoopSlipstreamRecord.decode(
                            CoopMessages.requiredPayloadString(message, "stream")).hostId()).toList());
            assertTrue(f.types().stream().allMatch(type -> type == CoopMessages.Type.STREAM_ADD));
            f.net.sent.clear();
        }
    }

    @Test
    void noiseClearingAtATerminalNonIdleFaderPublishesOneFinalCheckpointThenStaysQuiet() {
        for (CoopSlipstreamRecord.FaderState state : List.of(
                CoopSlipstreamRecord.FaderState.IN, CoopSlipstreamRecord.FaderState.OUT)) {
            Fixture f = new Fixture();
            CoopSlipstreamRecord partial = faderStream("settled", state, .5f, false, false);
            CoopSlipstreamRecord.Fade noisy = state == CoopSlipstreamRecord.FaderState.IN
                    ? new CoopSlipstreamRecord.Fade(new float[]{0, 1}, 2, 1.99f, null, 0, 0, 0)
                    : new CoopSlipstreamRecord.Fade(null, 0, 0, new float[]{0, 1}, 0, 2, 1.99f);
            f.world.host = List.of(withState(partial, partial.hostId(), noisy, partial.segments(),
                    partial.surgeWave(), partial.timeline()));
            f.authority.tickHost();
            f.net.sent.clear();
            CoopSlipstreamRecord terminal = faderStream("settled", state,
                    state == CoopSlipstreamRecord.FaderState.IN ? 1 : 0, false, false);
            f.world.host = List.of(terminal);

            f.now.set(250);
            f.authority.tickHost();

            assertEquals(List.of(CoopMessages.Type.STREAM_ADD), f.types());
            CoopSlipstreamRecord sent = CoopSlipstreamRecord.decode(
                    CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream"));
            assertEquals(terminal, sent, "the final checkpoint retains the actual native terminal enum");
            f.net.sent.clear();
            for (long time : List.of(500L, 750L, 1000L)) {
                f.now.set(time);
                f.authority.tickHost();
                assertTrue(f.net.sent.isEmpty(), "the final terminal checkpoint is sent only once");
            }
        }
    }

    @Test
    void matureMetadataChangesStayQuietAndResumePreservesTheGridOrigin() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord mature = stream("grid", false);
        long origin = -1_000_000L;
        CoopSlipstreamRecord first = withState(mature, mature.hostId(), mature.fade(), mature.segments(),
                CoopSlipstreamRecord.SurgeWaveState.absent(),
                new CoopSlipstreamRecord.Timeline(origin, 2, origin + 144_999, false, true));
        f.world.host = List.of(first);
        f.authority.tickHost();
        f.net.sent.clear();
        CoopSlipstreamRecord later = withState(mature, mature.hostId(), mature.fade(), mature.segments(),
                CoopSlipstreamRecord.SurgeWaveState.absent(),
                new CoopSlipstreamRecord.Timeline(origin, 10, origin + 720_999, true, false));
        f.world.host = List.of(later);
        f.now.set(250);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty(), "capture time and host mode do not restart a settled stream");

        f.authority.rearmFullBroadcast();
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.STREAM_SET, CoopMessages.Type.STREAM_ADD), f.types());
        CoopSlipstreamRecord resumed = CoopSlipstreamRecord.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(1), "stream"));
        assertEquals(origin, resumed.timeline().originCampaignMillis());
        assertEquals(later.timeline(), resumed.timeline());
    }

    @Test
    void activeCheckpointsCarryPauseAndLocationModeChangesAtTheNextPoll() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord fading = stream("active", true);
        f.world.host = List.of(fading);
        f.authority.tickHost();
        f.net.sent.clear();
        var timeline = new CoopSlipstreamRecord.Timeline(0, 1, 72_100, true, false);
        f.world.host = List.of(withState(fading, fading.hostId(), fading.fade(), fading.segments(),
                fading.surgeWave(), timeline));

        f.now.set(250);
        f.authority.tickHost();

        assertEquals(List.of(CoopMessages.Type.STREAM_ADD), f.types());
        assertEquals(timeline, CoopSlipstreamRecord.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream")).timeline());
    }

    @Test
    void manifestReconcilesSavedTaggedStreamsWithoutRemovingLocalSurges() {
        Fixture f = new Fixture();
        f.world.guest.put("kept", stream("kept", false));
        f.world.guest.put("obsolete", stream("obsolete", false));
        f.world.localSurges.add("own-surge");
        f.authority.applySet(CoopSlipstreamRecord.encodeIds(List.of("kept")));
        assertEquals(Set.of("kept"), f.world.guest.keySet());
        assertEquals(List.of("own-surge"), f.world.localSurges);
        assertEquals(List.of("sweep", "remove:obsolete"), f.world.calls);
        f.authority.applyAdd(stream("kept", true).encode());
        assertEquals(List.of("sweep", "remove:obsolete", "upsert:kept"), f.world.calls);
        f.authority.rearmFullBroadcast();
        f.authority.prepareGuest();
        assertEquals(1, f.world.calls.stream().filter("sweep"::equals).count());
    }

    @Test
    void staleLifecycleMessagesAreHarmlessAndFreshSessionRearmsOnlyTheSweep() {
        Fixture f = new Fixture();
        CoopSlipstreamRecord.Fade fade = stream("expired", true).fade();
        f.authority.applyDespawn(new CoopSlipstreamRecord.Despawn("expired",
                fade.despawnNoise(), fade.despawnDelay(), fade.despawnDays(),
                fade.despawnElapsed()).encode());
        f.authority.applyRemove("expired");
        f.authority.applyAdd(stream("saved", false).encode());
        f.authority.reset();
        assertTrue(f.world.guest.containsKey("saved"));
        f.authority.prepareGuest();
        assertEquals(2, f.world.calls.stream().filter("sweep"::equals).count());
    }

    @Test
    void malformedPayloadCannotSweepOrMutateTerrain() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet("bad|extra"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyAdd("bad"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyDespawn("bad"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyRemove(""));
        assertTrue(f.world.calls.isEmpty());
    }

    @Test
    void frameCapAccountsForUtf8AndBothJsonEscapeLayers() {
        CoopMessages.Message small = CoopMessages.streamAdd("session", 1, 0, stream("a", true).encode());
        assertTrue(CoopSlipstreamAuthority.fitsFrame(small));
        assertFalse(CoopSlipstreamAuthority.fitsFrame(
                CoopMessages.streamAdd("session", 1, 0, "\\".repeat(300_000))));
        assertFalse(CoopSlipstreamAuthority.fitsFrame(
                CoopMessages.streamAdd("session", 1, 0, "界".repeat(400_000))));
    }

    private static CoopSlipstreamRecord stream(String id, boolean despawning) {
        var fader = new CoopSlipstreamRecord.Fader(1, .2f, .2f,
                CoopSlipstreamRecord.FaderState.IDLE, false, false);
        var segments = List.of(new CoopSlipstreamRecord.Segment(0, 0, 200, 1, fader),
                new CoopSlipstreamRecord.Segment(1000, 0, 200, 1, fader),
                new CoopSlipstreamRecord.Segment(2000, 0, 200, 1, fader));
        var fade = new CoopSlipstreamRecord.Fade(null, 0, 0,
                despawning ? new float[]{0, .5f, 1} : null, -1, 10, 2);
        return new CoopSlipstreamRecord(id, CoopSlipstreamRecord.Kind.SEASONAL,
                CoopSlipstreamRecord.Params.defaults(), List.of(), segments, fade, false);
    }

    private static CoopSlipstreamRecord faderStream(String id, CoopSlipstreamRecord.FaderState state,
                                                    float brightness, boolean bounceUp, boolean bounceDown) {
        CoopSlipstreamRecord base = stream(id, false);
        var fader = new CoopSlipstreamRecord.Fader(brightness, 1, 1, state, bounceUp, bounceDown);
        List<CoopSlipstreamRecord.Segment> segments = base.segments().stream().map(segment ->
                new CoopSlipstreamRecord.Segment(segment.x(), segment.y(), segment.width(), segment.bMult(), fader))
                .toList();
        return withState(base, id, CoopSlipstreamRecord.Fade.idle(), segments,
                CoopSlipstreamRecord.SurgeWaveState.absent(), base.timeline());
    }

    private static CoopSlipstreamRecord withState(CoopSlipstreamRecord base, String id,
                                                  CoopSlipstreamRecord.Fade fade,
                                                  List<CoopSlipstreamRecord.Segment> segments,
                                                  CoopSlipstreamRecord.SurgeWaveState wave,
                                                  CoopSlipstreamRecord.Timeline timeline) {
        return new CoopSlipstreamRecord(id, base.kind(), base.params(), base.terrainTags(),
                segments, fade, timeline, wave);
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final RecordingNetService net = new RecordingNetService(CoopConnectionRole.HOST);
        final World world = new World();
        final CoopSlipstreamAuthority authority = new CoopSlipstreamAuthority(net,
                TestSessions.activeHostSession(), now::get, world);
        List<CoopMessages.Type> types() { return net.sent.stream().map(CoopMessages.Message::type).toList(); }
    }

    private static final class World implements CoopSlipstreamAuthority.StreamWorld {
        List<CoopSlipstreamRecord> host = List.of();
        final Map<String, CoopSlipstreamRecord> guest = new LinkedHashMap<>();
        final List<String> localSurges = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        @Override public List<CoopSlipstreamRecord> captureHost() { return host; }
        @Override public Set<String> taggedIds() { return Set.copyOf(guest.keySet()); }
        @Override public void upsert(CoopSlipstreamRecord record) {
            calls.add("upsert:" + record.hostId());
            guest.put(record.hostId(), record);
        }
        @Override public void despawn(CoopSlipstreamRecord.Despawn record) {
            calls.add("despawn:" + record.hostId());
        }
        @Override public void remove(String id) { calls.add("remove:" + id); guest.remove(id); }
        @Override public void sweepSeasonal() { calls.add("sweep"); }
    }
}
