package coop.campaign;

import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.session.CoopSessionState;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static coop.campaign.CoopLightCodecTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CoopLightAuthorityTest {
    @Test void hostPublishesOneCompleteSortedManifestAndRefreshesAfterFiveSeconds() {
        Fixture f = new Fixture(true);
        f.world.host = List.of(light(OTHER), light(ID));
        f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.LIGHT_SET), f.types());
        assertEquals(List.of(light(ID), light(OTHER)), CoopLightCodec.decodeSet(f.payload("lights")));
        f.net.sent.clear();
        f.now.set(250); f.world.host = List.of(withLifetime(light(ID), 29f), light(OTHER));
        f.authority.tickHost(); assertTrue(f.net.sent.isEmpty());
        f.now.set(4_999); f.authority.tickHost(); assertTrue(f.net.sent.isEmpty());
        f.now.set(5_249); f.authority.tickHost();
        assertEquals(List.of(CoopMessages.Type.LIGHT_SET), f.types());
        assertEquals(29f, CoopLightCodec.decodeSet(f.payload("lights")).get(0).remainingDays());
    }

    @Test void pollIsBoundedAndStaticChangesPublishOnNextPoll() {
        Fixture f = new Fixture(true); f.world.host = List.of(light(ID));
        f.authority.tickHost(); f.net.sent.clear();
        f.world.host = List.of(light(OTHER)); f.now.set(249); f.authority.tickHost();
        assertTrue(f.net.sent.isEmpty()); assertEquals(1, f.world.captures);
        f.now.set(250); f.authority.tickHost();
        assertEquals(List.of(light(OTHER)), CoopLightCodec.decodeSet(f.payload("lights")));
    }

    @Test void nullUnavailableCaptureNeverMeansEmptyAndInvalidCaptureNeverPublishes() {
        Fixture f = new Fixture(true); f.world.host = List.of(light(ID)); f.authority.tickHost(); f.net.sent.clear();
        f.world.host = null; f.now.set(250); f.authority.tickHost(); assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of(light(ID), light(ID)); f.now.set(500);
        assertThrows(IllegalArgumentException.class, f.authority::tickHost); assertTrue(f.net.sent.isEmpty());
        f.world.host = List.of(); f.now.set(750); f.authority.tickHost();
        assertEquals(List.of(), CoopLightCodec.decodeSet(f.payload("lights")));
    }

    @Test void rearmAndResetRepublishButNeverClearPersistentWorld() {
        Fixture f = new Fixture(true); f.world.host = List.of(light(ID)); f.authority.tickHost(); f.net.sent.clear();
        f.authority.rearmFullBroadcast(); f.authority.tickHost(); assertEquals(1, f.net.sent.size());
        f.world.tombstones.add(OTHER); f.net.sent.clear();
        f.authority.reset(); f.authority.tickHost();
        assertEquals(1, f.net.sent.size()); assertEquals(Set.of(OTHER), f.world.tombstones);
        assertTrue(f.world.calls.isEmpty());
    }

    @Test void malformedCompleteManifestCannotMutateExistingOrLegacyState() {
        Fixture f = new Fixture(false); f.world.guest.put(OTHER, light(OTHER));
        f.world.legacy.add("old-native");
        String valid = CoopLightCodec.encodeSet(List.of(light(ID)));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet(valid + "0:"));
        assertEquals(Set.of(OTHER), f.world.guest.keySet());
        assertEquals(List.of("old-native"), f.world.legacy); assertTrue(f.world.calls.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> f.authority.applySet("garbage"));
        assertTrue(f.world.calls.isEmpty());
    }

    @Test void completeManifestMustAlsoFitTheExactlySerializedTransportFrame() {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby", new coop.session.CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("s".repeat(coop.net.CoopNetService.MAX_FRAME_BYTES));
        RecordingNetService net = new RecordingNetService(CoopConnectionRole.GUEST);
        FakeWorld world = new FakeWorld();
        CoopLightAuthority authority = new CoopLightAuthority(net, session, () -> 0L, world);
        String validSmallSet = CoopLightCodec.encodeSet(List.of(light(ID)));
        assertThrows(IllegalArgumentException.class, () -> authority.applySet(validSmallSet));
        assertTrue(world.calls.isEmpty()); assertTrue(world.guest.isEmpty());
    }

    @Test void failedMemberApplyRetainsOldReplicasAndLegacyUntilWholeSetRetries() {
        Fixture f = new Fixture(false);
        String old = "light:00000000-0000-0000-0000-000000000003";
        f.world.guest.put(old, light(old)); f.world.legacy.add("old-native"); f.world.failUpsert = OTHER;
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(CoopLightCodec.encodeSet(List.of(light(ID), light(OTHER)))));
        assertTrue(f.world.guest.containsKey(old)); assertEquals(List.of("old-native"), f.world.legacy);
        assertFalse(f.world.calls.contains("remove:" + old)); assertFalse(f.world.calls.contains("legacy"));
        f.world.failUpsert = null; f.authority.tickGuest();
        assertEquals(Set.of(ID, OTHER), f.world.guest.keySet()); assertTrue(f.world.legacy.isEmpty());
        assertTrue(f.world.calls.indexOf("upsert:" + OTHER) < f.world.calls.indexOf("remove:" + old));
    }

    @Test void emptyCompleteManifestRemovesReplicasAndReconcilesLegacy() {
        Fixture f = new Fixture(false); f.world.guest.put(ID, light(ID)); f.world.legacy.add("old-native");
        f.authority.applySet(CoopLightCodec.encodeSet(List.of()));
        assertTrue(f.world.guest.isEmpty()); assertTrue(f.world.legacy.isEmpty());
        assertEquals(List.of("observe", "remove:" + ID, "legacy"), f.world.calls);
        f.authority.tickGuest(); assertTrue(f.net.sent.isEmpty());
    }

    @Test void failedNativeManifestDoesNotBlockAnUnrelatedSavedPendingClaim() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID)); f.world.failUpsert = OTHER;
        assertThrows(IllegalStateException.class, () -> f.authority.applySet(CoopLightCodec.encodeSet(List.of(light(OTHER)))));
        assertThrows(IllegalStateException.class, f.authority::tickGuest);
        assertEquals(List.of(CoopMessages.Type.LIGHT_REQUEST), f.types());
        f.now.set(1_000); assertThrows(IllegalStateException.class, f.authority::tickGuest);
        assertEquals(1, f.net.sent.size());
        f.now.set(2_000); assertThrows(IllegalStateException.class, f.authority::tickGuest);
        assertEquals(2, f.net.sent.size()); assertEquals(1, f.world.pending.size());
    }

    @Test void pendingClaimsAreObservedBeforeManifestCanOverwriteNativeState() {
        Fixture f = new Fixture(false); f.world.tombstones.add(ID); f.world.pending.add(request(ID));
        f.authority.applySet(CoopLightCodec.encodeSet(List.of(light(ID))));
        assertEquals("observe", f.world.calls.get(0)); assertTrue(f.world.guest.isEmpty());
        assertEquals(List.of(request(ID)), f.world.pending);
        f.authority.tickGuest(); assertEquals(List.of(CoopMessages.Type.LIGHT_REQUEST), f.types());
    }

    @Test void guestRetriesDurableRequestEveryTwoSecondsAndImmediatelyAfterRearm() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID));
        f.authority.tickGuest(); assertEquals(1, f.net.sent.size());
        assertEquals(request(ID), CoopLightCodec.decodeRequest(f.payload("request")));
        f.now.set(1_999); f.authority.tickGuest(); assertEquals(1, f.net.sent.size());
        f.now.set(2_000); f.authority.tickGuest(); assertEquals(2, f.net.sent.size());
        f.authority.rearmFullBroadcast(); f.authority.tickGuest(); assertEquals(3, f.net.sent.size());
        f.authority.reset(); f.authority.tickGuest(); assertEquals(4, f.net.sent.size());
        assertEquals(List.of(request(ID)), f.world.pending);
    }

    @Test void observedPendingStateIsRetainedWhileSessionIsUnavailable() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID));
        f.session.reset(); f.authority.observeGuest(); f.authority.tickGuest();
        assertEquals(List.of("observe"), f.world.calls);
        assertEquals(List.of(request(ID)), f.world.pending); assertTrue(f.net.sent.isEmpty());
    }

    @Test void hostBindsRequestToActiveGuestAndReturnsNativeOutcome() {
        Fixture f = new Fixture(true); f.world.host = List.of(light(ID));
        f.authority.applyRequest(request(ID).encode(), "guest-player");
        CoopLightCodec.Outcome result = CoopLightCodec.decodeOutcome(f.payload("result"));
        assertEquals("guest-player", result.actorId()); assertEquals(0.75f, result.bonus()); assertEquals(25, result.points());
        assertTrue(result.accepted()); assertEquals(Set.of(ID), f.world.tombstones);
        assertEquals(1, f.world.effects);
        f.authority.applyRequest(request(ID).encode(), "guest-player");
        assertEquals(1, f.world.effects); assertEquals(2, f.net.sent.size());
    }

    @Test void unavailableHostActorRetainsGuestRequestAndRetriesTheSameIdentityUntilOneCommittedResult() {
        Fixture host = new Fixture(true), guest = new Fixture(false);
        CoopLightCodec.Request pending = request(ID);
        guest.world.pending.add(pending);
        host.world.actorUnavailable = true;

        guest.authority.tickGuest();
        String first = guest.payload("request");
        assertEquals(pending, CoopLightCodec.decodeRequest(first));
        assertThrows(IllegalStateException.class, () -> host.authority.applyRequest(first, "guest-player"));
        assertTrue(host.net.sent.isEmpty(), "temporary actor absence must not become a definitive denial");
        assertTrue(host.world.results.isEmpty()); assertTrue(host.world.tombstones.isEmpty());
        assertEquals(0, host.world.effects); assertEquals(List.of(pending), guest.world.pending);
        assertEquals(0, guest.world.effects);

        guest.now.set(1_999); guest.authority.tickGuest(); assertEquals(1, guest.net.sent.size());
        guest.now.set(2_000); guest.authority.tickGuest(); assertEquals(2, guest.net.sent.size());
        assertEquals(first, guest.payload("request"), "retry must retain the saved request UUID and action");
        host.world.actorUnavailable = false;
        host.authority.applyRequest(guest.payload("request"), "guest-player");
        String committed = host.payload("result");
        assertTrue(CoopLightCodec.decodeOutcome(committed).accepted());
        assertEquals(1, host.world.effects); assertEquals(1, host.world.results.size());
        assertEquals(Set.of(ID), host.world.tombstones);

        // Lose that result, then retry after transport rearm with the actor temporarily absent again.
        host.world.actorUnavailable = true;
        host.authority.rearmFullBroadcast(); guest.authority.rearmFullBroadcast();
        guest.authority.tickGuest(); assertEquals(3, guest.net.sent.size());
        assertEquals(first, guest.payload("request"));
        host.authority.applyRequest(guest.payload("request"), "guest-player");
        assertEquals(committed, host.payload("result"));
        assertEquals(1, host.world.effects); assertEquals(1, host.world.results.size());
        assertEquals(List.of(pending), guest.world.pending);

        guest.authority.applyResult(host.payload("result"));
        guest.authority.applyResult(committed);
        assertTrue(guest.world.pending.isEmpty()); assertEquals(1, guest.world.effects);
        assertEquals(0.75f, guest.world.awardedBonus); assertEquals(25, guest.world.awardedPoints);
        guest.now.set(4_000); guest.authority.tickGuest(); assertEquals(3, guest.net.sent.size());
        assertEquals(3, host.world.requestCalls);
    }

    @Test void wrongActorOrMalformedRequestCannotReachNativeClaim() {
        Fixture f = new Fixture(true);
        f.world.actorUnavailable = true;
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyRequest(request(ID).encode(), "host-player"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyRequest(request(ID).encode(), "forged-player"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyRequest(request(ID).encode(), null));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyRequest("broken", "guest-player"));
        assertTrue(f.world.results.isEmpty()); assertTrue(f.world.tombstones.isEmpty()); assertTrue(f.net.sent.isEmpty());
        assertEquals(0, f.world.requestCalls, "seat binding and decoding must happen before native authorization");
    }

    @Test void nativeOutcomeMustMatchEveryAuthenticatedRequestFieldBeforeSending() {
        Fixture f = new Fixture(true);
        f.world.overrideOutcome = outcome("wrong-player", ID, REQUEST, CoopLightCodec.Action.PULSE);
        assertThrows(IllegalStateException.class, () -> f.authority.applyRequest(request(ID).encode(), "guest-player"));
        assertTrue(f.net.sent.isEmpty());
    }

    @Test void guestAppliesResolvedResultOnlyOnceAndClearsRetryAfterDurableAck() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID));
        f.authority.tickGuest(); f.net.sent.clear();
        String accepted = outcome("guest-player", ID, REQUEST, CoopLightCodec.Action.PULSE).encode();
        f.authority.applyResult(accepted); f.authority.applyResult(accepted);
        assertEquals(1, f.world.effects); assertEquals(0.75f, f.world.awardedBonus); assertEquals(25, f.world.awardedPoints);
        assertTrue(f.world.pending.isEmpty()); f.now.set(4_000); f.authority.tickGuest(); assertTrue(f.net.sent.isEmpty());
        f.authority.reset(); f.authority.applyResult(accepted); assertEquals(1, f.world.effects);
    }

    @Test void malformedWrongActorAndMismatchedResultCannotApplyReward() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyResult("broken"));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyResult(outcome("host-player", ID, REQUEST, CoopLightCodec.Action.PULSE).encode()));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyResult(outcome("guest-player", OTHER, REQUEST, CoopLightCodec.Action.PULSE).encode()));
        assertThrows(IllegalArgumentException.class, () -> f.authority.applyResult(outcome("guest-player", ID, REQUEST, CoopLightCodec.Action.SENSOR_BURST).encode()));
        assertEquals(0, f.world.effects); assertEquals(List.of(request(ID)), f.world.pending);
    }

    @Test void failedGuestEffectRetainsPendingRequestForNativeIdempotentRetry() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID)); f.world.failOutcome = true;
        String accepted = outcome("guest-player", ID, REQUEST, CoopLightCodec.Action.PULSE).encode();
        assertThrows(IllegalStateException.class, () -> f.authority.applyResult(accepted));
        assertEquals(List.of(request(ID)), f.world.pending);
        f.world.failOutcome = false; f.authority.applyResult(accepted); assertEquals(1, f.world.effects);
    }

    @Test void expiredOrOtherActorWonDenialIsNotInventedIntoAConsumeReward() {
        Fixture f = new Fixture(false); f.world.pending.add(request(ID));
        CoopLightCodec.Outcome denied = new CoopLightCodec.Outcome(REQUEST, ID, "guest-player", CoopLightCodec.Action.PULSE,
                false, null, 0f, 0, "already expired or consumed");
        f.authority.applyResult(denied.encode());
        assertTrue(f.world.pending.isEmpty()); assertEquals(0f, f.world.awardedBonus); assertEquals(0, f.world.awardedPoints);
    }

    @Test void roleGuardsPreventOppositeSideTrafficAndMutations() {
        Fixture host = new Fixture(true), guest = new Fixture(false);
        host.world.pending.add(request(ID)); host.authority.tickGuest(); assertTrue(host.net.sent.isEmpty());
        guest.world.host = List.of(light(ID)); guest.authority.tickHost(); assertTrue(guest.net.sent.isEmpty());
        assertThrows(IllegalStateException.class, () -> host.authority.applySet(CoopLightCodec.encodeSet(List.of())));
        assertThrows(IllegalStateException.class, () -> guest.authority.applyRequest(request(ID).encode(), "guest-player"));
        assertThrows(IllegalStateException.class, () -> host.authority.applyResult(outcome("guest-player", ID, REQUEST, CoopLightCodec.Action.PULSE).encode()));
    }

    private static CoopLightCodec.Request request(String id) {
        return new CoopLightCodec.Request(id, REQUEST, CoopLightCodec.Action.PULSE);
    }

    private static CoopLightCodec.Outcome outcome(String actor, String id, String request, CoopLightCodec.Action action) {
        return new CoopLightCodec.Outcome(request, id, actor, action, true, null, 0.75f, 25, "committed");
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final CoopSessionState session;
        final RecordingNetService net;
        final FakeWorld world = new FakeWorld();
        final CoopLightAuthority authority;
        Fixture(boolean host) {
            session = host ? TestSessions.activeHostSession() : TestSessions.activeGuestSession();
            net = new RecordingNetService(host ? CoopConnectionRole.HOST : CoopConnectionRole.GUEST);
            authority = new CoopLightAuthority(net, session, now::get, world);
        }
        List<CoopMessages.Type> types() { return net.sent.stream().map(CoopMessages.Message::type).toList(); }
        String payload(String field) { return CoopMessages.requiredPayloadString(net.sent.get(net.sent.size() - 1), field); }
    }

    private static final class FakeWorld implements CoopLightAuthority.LightWorld {
        List<CoopLightRecord> host = List.of();
        final Map<String, CoopLightRecord> guest = new LinkedHashMap<>();
        final Set<String> tombstones = new LinkedHashSet<>();
        final List<String> legacy = new ArrayList<>(), calls = new ArrayList<>();
        final List<CoopLightCodec.Request> pending = new ArrayList<>();
        final Map<String, CoopLightCodec.Outcome> results = new LinkedHashMap<>();
        String failUpsert;
        CoopLightCodec.Outcome overrideOutcome;
        boolean failOutcome, actorUnavailable;
        int captures, effects, awardedPoints, requestCalls;
        float awardedBonus;
        public List<CoopLightRecord> captureHost() { captures++; return host; }
        public Set<String> taggedIds() { return Set.copyOf(guest.keySet()); }
        public void upsert(CoopLightRecord record) {
            if (record.id().equals(failUpsert)) throw new IllegalStateException("native construction failed");
            calls.add("upsert:" + record.id());
            if (!tombstones.contains(record.id())) guest.put(record.id(), record);
        }
        public void remove(String id) { calls.add("remove:" + id); guest.remove(id); }
        public void reconcileLegacy(List<CoopLightRecord> complete) { calls.add("legacy"); legacy.clear(); }
        public List<CoopLightCodec.Request> pendingRequests() { return List.copyOf(pending); }
        public CoopLightCodec.Outcome applyRequest(CoopLightCodec.Request request, String actor) {
            requestCalls++;
            if (overrideOutcome != null) return overrideOutcome;
            CoopLightCodec.Outcome committed = results.get(actor + ":" + request.requestId());
            if (committed != null) return committed;
            if (actorUnavailable) throw new IllegalStateException("native light actor is not ready");
            return results.computeIfAbsent(actor + ":" + request.requestId(), ignored -> {
                effects++; tombstones.add(request.lightId());
                return outcome(actor, request.lightId(), request.requestId(), request.action());
            });
        }
        public void acceptOutcome(CoopLightCodec.Outcome outcome) {
            if (failOutcome) throw new IllegalStateException("native effect unavailable");
            effects++; awardedBonus += outcome.bonus(); awardedPoints += outcome.points();
            pending.removeIf(r -> r.requestId().equals(outcome.requestId()));
        }
        public void observeGuest() { calls.add("observe"); }
    }
}
