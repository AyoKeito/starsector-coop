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
    void pollsEvery250msAndSendsOnlyLifecycleTransitions() {
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
        assertEquals(List.of(CoopMessages.Type.STREAM_DESPAWN, CoopMessages.Type.STREAM_ADD), f.types());
        CoopSlipstreamRecord.Despawn d = CoopSlipstreamRecord.Despawn.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream"));
        assertEquals("a", d.hostId());
        assertEquals(-1f, d.delay());
        f.net.sent.clear();
        f.now.set(500);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
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
    void aSurgeCanReplaceItsAlreadyRunningDespawnScheduleWithoutPeriodicSpam() {
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
        assertEquals(List.of(CoopMessages.Type.STREAM_DESPAWN), f.types());
        assertEquals(.2f, CoopSlipstreamRecord.Despawn.decode(
                CoopMessages.requiredPayloadString(f.net.sent.get(0), "stream")).days());
        f.net.sent.clear();
        var progressing = new CoopSlipstreamRecord.Fade(null, 0, 0,
                new float[]{1, .1f, 0}, -.01f, .2f, .01f);
        f.world.host = List.of(new CoopSlipstreamRecord(initial.hostId(), initial.kind(),
                initial.params(), initial.terrainTags(), initial.segments(), progressing, true));
        f.now.set(500);
        f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty());
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
