package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.session.CoopSessionState;
import coop.storm.CoopStormControl;
import coop.storm.CoopStormSnapshot;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static coop.campaign.CoopStormCodecTest.small;
import static org.junit.jupiter.api.Assertions.*;

class CoopStormAuthorityTest {
    private static final String ID = "77032526-2d55-48e2-a9a0-7b866330ab20";

    @Test void hostPublishesBaselineThenBoundedFullGridCheckpointsAndResumeSet() {
        Fixture f = new Fixture(true); f.world.host = snapshot(1);
        f.authority.tickHost(); assertEquals(List.of(CoopMessages.Type.STORM_SET), f.types());
        f.world.host = snapshot(2); f.now.set(249); f.authority.tickHost(); assertEquals(1, f.net.sent.size());
        f.now.set(250); f.authority.tickHost(); assertEquals(CoopMessages.Type.STORM_GRID, f.net.sent.get(1).type());
        f.now.set(500); f.authority.tickHost(); assertEquals(2, f.net.sent.size(), "unchanged paused checkpoint stays quiet");
        f.authority.rearmFullBroadcast(); f.authority.tickHost();
        assertEquals(CoopMessages.Type.STORM_SET, f.net.sent.get(2).type());
        assertEquals(snapshot(2), CoopStormCodec.decodeSnapshot(CoopMessages.requiredPayloadString(f.net.sent.get(2), "storm")));
    }

    @Test void unavailableHostScanNeverInventsAnEmptyGridOrForgetsLastCheckpoint() {
        Fixture f = new Fixture(true); f.world.host = null; f.authority.tickHost(); assertTrue(f.net.sent.isEmpty());
        f.world.host = snapshot(1); f.now.set(250); f.authority.tickHost();
        f.world.host = null; f.now.set(500); f.authority.tickHost(); assertEquals(1, f.net.sent.size());
    }

    @Test void freshAuthenticatedSetReplacesAheadSavedGuestButGridCannotPrecedeBaseline() {
        Fixture f = new Fixture(false); f.world.saved = snapshot(99);
        assertThrows(IllegalStateException.class, () -> f.authority.applyGrid(encoded(1)));
        assertEquals(snapshot(99), f.world.saved);
        f.authority.applySet(encoded(1)); assertEquals(snapshot(1), f.world.saved);
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyGrid(encoded(0)));
        assertEquals(snapshot(1), f.world.saved);
    }

    @Test void duplicateGridIsIdempotentButConflictingDuplicateAndGenerationRewindCannotMutateWorld() {
        Fixture f = new Fixture(false); f.authority.applySet(encoded(2));
        f.authority.applyGrid(encoded(2)); assertEquals(1, f.world.applies);
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyGrid(CoopStormCodec.encodeSnapshot(
                small(2, 1, -8997, List.of()))));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyGrid(CoopStormCodec.encodeSnapshot(
                small(3, 0, -8996, List.of()))));
        assertEquals(1, f.world.applies);
    }

    @Test void malformedSnapshotIsAtomicAndNativeFailureRetriesWithoutAnotherWireSet() {
        Fixture f = new Fixture(false); f.world.saved = snapshot(0);
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet("STORM1:12:broken"));
        assertEquals(snapshot(0), f.world.saved); assertEquals(0, f.world.applies);
        f.world.failApply = true;
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(encoded(1)));
        assertEquals(snapshot(0), f.world.saved);
        f.world.failApply = false; f.authority.tickGuest(); assertEquals(snapshot(1), f.world.saved);
    }

    @Test void resetClearsTransportCursorsAndEphemeralControlsButRetainsNativeSavedWeather() {
        Fixture f = new Fixture(false); f.authority.applySet(encoded(4));
        f.world.controls.add(control()); f.authority.reset();
        assertEquals(snapshot(4), f.world.saved); assertEquals(1, f.world.resets);
        assertTrue(f.world.controls.isEmpty());
        assertThrows(IllegalStateException.class, () -> f.authority.applyGrid(encoded(5)));
        f.authority.applySet(encoded(1)); assertEquals(snapshot(1), f.world.saved);
    }

    @Test void nativeControlsDrainOncePerPollAndNoPartialBatchSendsOnDuplicateIdentity() {
        Fixture f = new Fixture(false); f.world.controls.add(control()); f.authority.tickGuest();
        assertEquals(List.of(CoopMessages.Type.STORM_CONTROL), f.types()); assertTrue(f.world.controls.isEmpty());
        f.world.controls.add(control()); f.now.set(249); f.authority.tickGuest(); assertEquals(1, f.net.sent.size());
        f.now.set(250); f.authority.tickGuest(); assertEquals(2, f.net.sent.size());
        f.world.controls.addAll(List.of(control(), control())); f.now.set(500);
        assertThrows(IllegalArgumentException.class, f.authority::tickGuest); assertEquals(2, f.net.sent.size());
    }

    @Test void nativeApplyFailureDoesNotPreventUnrelatedWeatherControlFromReachingHost() {
        Fixture f = new Fixture(false); f.world.failApply = true;
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(encoded(1)));
        f.world.controls.add(control()); assertThrows(IllegalStateException.class, f.authority::tickGuest);
        assertEquals(List.of(CoopMessages.Type.STORM_CONTROL), f.types());
    }

    @Test void overCapControlBatchCannotSendEvenItsValidPrefix() {
        Fixture f = new Fixture(false);
        for (int i = 0; i <= CoopStormAuthority.MAX_CONTROLS_PER_POLL; i++)
            f.world.controls.add(CoopStormControl.tileOff(new java.util.UUID(1, i).toString(), 1, 0, 0, 500, 0));
        assertThrows(IllegalArgumentException.class, f.authority::tickGuest);
        assertTrue(f.net.sent.isEmpty());
    }

    @Test void controlsRequireAuthenticatedGuestAndWorldRevalidation() {
        Fixture f = new Fixture(true); String control = CoopStormCodec.encodeControl(control());
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyControl(control, "wrong-actor"));
        assertEquals(0, f.world.controlCalls);
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyControl("broken", f.session.remotePlayerId()));
        assertEquals(0, f.world.controlCalls);
        f.authority.applyControl(control, f.session.remotePlayerId()); assertEquals(1, f.world.controlCalls);
        f.world.rejectControl = true;
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyControl(control, f.session.remotePlayerId()));
    }

    @Test void interruptedFragmentedSetNeverMutatesNativeStateAndResumeRepairsIt() {
        Fixture host = new Fixture(true), guest = new Fixture(false);
        var snapshot = CoopStormCodecTest.large(8, 20_000); host.world.host = snapshot;
        host.authority.tickHost(); assertTrue(host.net.sent.size() > 1);
        String first = CoopMessages.requiredPayloadString(host.net.sent.get(0), "storm");
        guest.authority.applySet(first); assertNull(guest.world.saved);
        guest.authority.rearmFullBroadcast(); host.authority.rearmFullBroadcast(); host.net.sent.clear();
        host.authority.tickHost();
        for (CoopMessages.Message message : host.net.sent)
            guest.authority.applySet(CoopMessages.requiredPayloadString(message, "storm"));
        assertEquals(snapshot, guest.world.saved); assertEquals(1, guest.world.applies);
    }

    @Test void fragmentedGridBeforeBaselineAndResetPartialCannotApply() {
        Fixture f = new Fixture(false);
        var parts = CoopStormCodec.encodeSnapshotParts(CoopStormCodecTest.large(8, 20_000));
        assertThrows(IllegalStateException.class, () -> f.authority.applyGrid(parts.get(0)));
        f.authority.applySet(parts.get(0)); f.authority.reset();
        for (int i = 1; i < parts.size(); i++) f.authority.applySet(parts.get(i));
        assertNull(f.world.saved);
        for (String part : parts) f.authority.applySet(part);
        assertEquals(1, f.world.applies);
    }

    @Test void completeFragmentedSetRetainsNativeRetryAfterTerrainTemporarilyUnavailable() {
        Fixture f = new Fixture(false); f.world.failApply = true;
        var parts = CoopStormCodec.encodeSnapshotParts(CoopStormCodecTest.large(8, 20_000));
        for (int i = 0; i < parts.size() - 1; i++) f.authority.applySet(parts.get(i));
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(parts.get(parts.size() - 1)));
        assertNull(f.world.saved);
        f.world.failApply = false; f.authority.tickGuest();
        assertEquals(CoopStormCodecTest.large(8, 20_000), f.world.saved);
        assertEquals(1, f.world.applies);
    }

    @Test void resumeAcceptsFreshSetOfSameRevisionAfterGridWithoutResettingNativeWeather() {
        Fixture f = new Fixture(false);
        for (String part : CoopStormCodec.encodeSnapshotParts(CoopStormCodecTest.large(8, 20_000))) f.authority.applySet(part);
        var current = CoopStormCodecTest.large(9, 20_000);
        var parts = CoopStormCodec.encodeSnapshotParts(current);
        f.authority.applyGrid(parts.get(0));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet(parts.get(1)), "partial assembly cannot mix message types");
        for (String part : parts) f.authority.applyGrid(part);
        assertEquals(2, f.world.applies);
        for (String part : parts) f.authority.applySet(part);
        assertEquals(2, f.world.applies, "completed cross-type duplicate is idempotent");
        f.authority.rearmFullBroadcast();
        for (String part : parts) f.authority.applySet(part);
        assertEquals(current, f.world.saved);
        assertEquals(2, f.world.applies, "resume metadata reset retains duplicate native checkpoint guard");
    }

    @Test void wrongRoleNeverPublishesOrAppliesOppositeDirectionOperations() {
        Fixture host = new Fixture(true), guest = new Fixture(false);
        host.world.controls.add(control()); host.authority.tickGuest(); assertEquals(0, host.world.drains);
        guest.world.host = snapshot(1); guest.authority.tickHost(); assertEquals(0, guest.world.captures);
        assertThrows(IllegalStateException.class, () -> host.authority.applySet(encoded(1)));
        assertThrows(IllegalStateException.class, () -> guest.authority.applyControl(CoopStormCodec.encodeControl(control()), "guest-player"));
        assertTrue(host.net.sent.isEmpty()); assertTrue(guest.net.sent.isEmpty());
    }

    private static CoopStormControl control() { return CoopStormControl.tileOff(ID, 1, 0, 0, 500, 0); }
    private static CoopStormSnapshot snapshot(long revision) { return small(revision, 1, -9000 + revision, List.of()); }
    private static String encoded(long revision) { return CoopStormCodec.encodeSnapshot(snapshot(revision)); }
    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final CoopSessionState session;
        final RecordingNetService net;
        final FakeWorld world = new FakeWorld();
        final CoopStormAuthority authority;
        Fixture(boolean host) {
            session = host ? TestSessions.activeHostSession() : TestSessions.activeGuestSession();
            net = new RecordingNetService(host ? CoopConnectionRole.HOST : CoopConnectionRole.GUEST);
            authority = new CoopStormAuthority(net, session, now::get, world);
        }
        List<CoopMessages.Type> types() { return net.sent.stream().map(CoopMessages.Message::type).toList(); }
    }
    private static final class FakeWorld implements CoopStormAuthority.World {
        CoopStormSnapshot host = snapshot(1), saved;
        final List<CoopStormControl> controls = new ArrayList<>();
        boolean failApply, rejectControl;
        int captures, applies, observations, drains, resets, controlCalls;
        public CoopStormSnapshot captureHost() { captures++; return host; }
        public void applySnapshot(CoopStormSnapshot snapshot) {
            if (failApply) throw new IllegalStateException("native terrain not ready");
            saved = snapshot; applies++;
        }
        public void observeGuest() { observations++; }
        public List<CoopStormControl> pendingControls() { drains++; List<CoopStormControl> copy = List.copyOf(controls); controls.clear(); return copy; }
        public void applyControl(CoopStormControl control, String actor) {
            controlCalls++; if (rejectControl) throw new IllegalArgumentException("native source proximity refused");
        }
        public void resetTransport() { resets++; controls.clear(); }
    }
}
