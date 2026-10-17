package coop.combat.shared;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import coop.combat.join.CoopCombatJoinMode;
import coop.combat.join.CoopRemoteSupportPrompt;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.testing.RecordingNetService;
import coop.testing.TestSessions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopSharedCombatSessionTest {

    @Test
    void engagerOfferSendsSharedCombatOfferWithBattleId() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1000L);

        shared.onLocalEngagerBattleBegin("battle-x", CoopCombatJoinMode.PROXIMITY,
                List.of("onslaught", "wolf"));

        assertEquals(CoopSharedCombatSession.Phase.OFFERED, shared.phase());
        assertEquals(1, service.sent.size());
        assertEquals(CoopMessages.Type.SHARED_COMBAT, service.sent.get(0).type());
        assertEquals("OFFER", CoopMessages.payload(service.sent.get(0)).requiredString("action"));
        String body = CoopMessages.payload(service.sent.get(0)).optionalString("body", "");
        assertTrue(body.startsWith("battle-x\nPROXIMITY\n"), body);
        assertTrue(body.contains("onslaught"));
    }

    @Test
    void remoteDeclineKeepsSessionIdleWithoutEntry() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> {
            throw new AssertionError("decline must not enter");
        });
        shared.onPartnerBattleOffered("battle-r", CoopCombatJoinMode.REMOTE_SUPPORT, List.of());
        assertEquals(CoopSharedCombatSession.Phase.OFFERED, shared.phase());

        shared.onRemoteSupportUnavailable("battle-r", CoopRemoteSupportPrompt.State.DECLINED);

        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertFalse(shared.isActive());
    }

    @Test
    void noMarkedShipSkipsDualEngineEntry() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        AtomicInteger entries = new AtomicInteger();
        shared.setEntryHookForTest(hulls -> {
            entries.incrementAndGet();
            return true;
        });
        shared.onPartnerBattleOffered("battle-n", CoopCombatJoinMode.REMOTE_SUPPORT, List.of("wolf"));
        shared.onRemoteSupportUnavailable("battle-n", CoopRemoteSupportPrompt.State.NO_MARKED_SHIP);

        assertEquals(0, entries.get());
        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
    }

    @Test
    void proximityPartnerEntersAndSendsReady() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        AtomicInteger entries = new AtomicInteger();
        shared.setEntryHookForTest(hulls -> {
            entries.incrementAndGet();
            assertEquals(List.of("onslaught"), hulls);
            return true;
        });

        shared.onPartnerBattleOffered("battle-p", CoopCombatJoinMode.PROXIMITY, List.of("onslaught"));

        assertEquals(1, entries.get());
        assertEquals(CoopSharedCombatSession.Phase.ACTIVE, shared.phase());
        assertTrue(shared.isActive());
        assertEquals(1, service.sent.size());
        assertEquals("READY", CoopMessages.payload(service.sent.get(0)).requiredString("action"));
    }

    @Test
    void remoteAcceptStaysClaimPendingUntilAck() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> true);
        shared.onPartnerBattleOffered("battle-c", CoopCombatJoinMode.REMOTE_SUPPORT, List.of("legion"));

        shared.onRemoteSupportAccepted("battle-c", "Host");

        assertEquals(CoopSharedCombatSession.Phase.CLAIM_PENDING, shared.phase(),
                "entry success must not promote past CLAIM_PENDING before ACK");
        assertTrue(shared.isActive());
        assertFalse(shared.claimGranted());
    }

    @Test
    void combatFrameWhileClaimPendingSendsClaimAndDoesNotDropPending() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> true);
        shared.onPartnerBattleOffered("battle-c", CoopCombatJoinMode.REMOTE_SUPPORT, List.of());
        shared.onRemoteSupportAccepted("battle-c", "Host");
        service.sent.clear();

        shared.onCombatFrame(engine(List.of()), 1000L);

        assertEquals(CoopSharedCombatSession.Phase.CLAIM_PENDING, shared.phase());
        assertTrue(service.sent.stream().anyMatch(m ->
                "CLAIM".equals(CoopMessages.payload(m).requiredString("action"))),
                "CLAIM probe must fire while pending");
        assertTrue(service.sent.stream().anyMatch(m ->
                "FRAME".equals(CoopMessages.payload(m).requiredString("action"))));
    }

    @Test
    void claimProbeIsThrottledAcrossFrames() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> true);
        shared.onPartnerBattleOffered("battle-c", CoopCombatJoinMode.REMOTE_SUPPORT, List.of());
        shared.onRemoteSupportAccepted("battle-c", "Host");
        CombatEngineAPI engine = engine(List.of());

        shared.onCombatFrame(engine, 1000L);
        shared.onCombatFrame(engine, 1100L); // within claim probe interval
        long claims = service.sent.stream()
                .filter(m -> "CLAIM".equals(CoopMessages.payload(m).requiredString("action")))
                .count();
        assertEquals(1, claims, "second frame inside claim interval must not re-probe");

        shared.onCombatFrame(engine, 1000L + CoopSharedCombatSession.CLAIM_PROBE_INTERVAL_MILLIS);
        claims = service.sent.stream()
                .filter(m -> "CLAIM".equals(CoopMessages.payload(m).requiredString("action")))
                .count();
        assertEquals(2, claims);
    }

    @Test
    void claimAckPromotesToActive() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> true);
        shared.onPartnerBattleOffered("battle-c", CoopCombatJoinMode.REMOTE_SUPPORT, List.of());
        shared.onRemoteSupportAccepted("battle-c", "Host");

        CoopSharedCombatClaimCodec.ClaimAck ack = new CoopSharedCombatClaimCodec.ClaimAck(
                true, "battle-c", "legion", "Mark", 10f, 20f, 90f, "legion_Standard");
        shared.handleMessage(CoopMessages.sharedCombat(
                "session-a", 2L, 1L, CoopMessages.SharedCombatAction.CLAIM_ACK, ack.encode()));

        assertTrue(shared.claimGranted());
        assertEquals(CoopSharedCombatSession.Phase.ACTIVE, shared.phase());
    }

    @Test
    void engagerBecomesActiveOnPeerReadyAndSendsFrames() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-e", CoopCombatJoinMode.PROXIMITY, List.of("wolf"));
        assertEquals(CoopSharedCombatSession.Phase.OFFERED, shared.phase());
        assertFalse(shared.isActive());

        shared.handleMessage(CoopMessages.sharedCombat(
                "session-a", 3L, 1L, CoopMessages.SharedCombatAction.READY, "battle-e"));

        assertEquals(CoopSharedCombatSession.Phase.ACTIVE, shared.phase());
        service.sent.clear();
        shared.onCombatFrame(engine(List.of(ship("s1", "wolf", "Wolf", 0))), 5000L);

        assertEquals(1, service.sent.size());
        assertEquals("FRAME", CoopMessages.payload(service.sent.get(0)).requiredString("action"));
        String body = CoopMessages.payload(service.sent.get(0)).optionalString("body", "");
        assertTrue(body.startsWith("battle-e\n1\n"));
        assertTrue(body.contains("F|s1|wolf|"));
    }

    @Test
    void frameBodiesWithNewlinesSurviveJsonRoundTrip() {
        String rawBody = "battle-z\n7\n"
                + CoopSharedCombatFrameCodec.encode(List.of(
                new CoopSharedCombatFrameCodec.ShipFrame(
                        "a", "onslaught", "Atlas", 0, 1.5f, -2f, 45f, 0.9f, 0.1f, true)));
        CoopMessages.Message msg = CoopMessages.sharedCombat(
                "session-a", 1L, 0L, CoopMessages.SharedCombatAction.FRAME, rawBody);
        String roundTripped = CoopMessages.payload(msg).optionalString("body", "");
        assertEquals(rawBody, roundTripped);

        String[] parts = roundTripped.split("\n", 3);
        assertEquals(3, parts.length);
        assertEquals(1, CoopSharedCombatFrameCodec.decode(parts[2]).size());
    }

    @Test
    void offerMessageCarriesBattleIdToPartner() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> true);

        shared.handleMessage(CoopMessages.sharedCombat(
                "session-a", 1L, 0L, CoopMessages.SharedCombatAction.OFFER,
                "battle-wire\nPROXIMITY\nonslaught,wolf"));

        assertEquals("battle-wire", shared.battleId());
        assertEquals(CoopSharedCombatSession.Phase.ACTIVE, shared.phase());
    }

    @Test
    void disconnectEndsWithoutLeaveWire() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-d", CoopCombatJoinMode.PROXIMITY, List.of());
        service.sent.clear();

        shared.onBattleEnded("DISCONNECT");

        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertEquals("DISCONNECT", shared.endOutcome());
        assertTrue(service.sent.isEmpty(), "disconnect must not try to LEAVE on a dead link");
    }

    @Test
    void normalEndSendsLeaveThenIdles() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-n", CoopCombatJoinMode.PROXIMITY, List.of());
        service.sent.clear();

        shared.onBattleEnded("WIN");

        assertEquals(1, service.sent.size());
        assertEquals("LEAVE", CoopMessages.payload(service.sent.get(0)).requiredString("action"));
        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertEquals("WIN", shared.endOutcome());
    }

    @Test
    void leaveFromPeerClearsActiveSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-l", CoopCombatJoinMode.PROXIMITY, List.of());
        service.sent.clear();

        shared.handleMessage(CoopMessages.sharedCombat(
                "session-a", 9L, 1L, CoopMessages.SharedCombatAction.LEAVE, "PEER_END"));

        assertEquals(CoopSharedCombatSession.Phase.IDLE, shared.phase());
        assertEquals("PEER_END", shared.endOutcome());
    }

    @Test
    void entryFailureOnProximityLeavesOfferedWithoutReady() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.setEntryHookForTest(hulls -> false);

        shared.onPartnerBattleOffered("battle-f", CoopCombatJoinMode.PROXIMITY, List.of("wolf"));

        assertEquals(CoopSharedCombatSession.Phase.OFFERED, shared.phase());
        assertTrue(service.sent.isEmpty());
        assertFalse(shared.isActive());
    }

    @Test
    void frameCadenceThrottlesOutbound() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSharedCombatSession shared = new CoopSharedCombatSession(service, liveSession(), () -> 1L);
        shared.onLocalEngagerBattleBegin("battle-t", CoopCombatJoinMode.PROXIMITY, List.of());
        shared.handleMessage(CoopMessages.sharedCombat(
                "session-a", 1L, 0L, CoopMessages.SharedCombatAction.READY, "battle-t"));
        service.sent.clear();
        CombatEngineAPI engine = engine(List.of());

        shared.onCombatFrame(engine, 10_000L);
        shared.onCombatFrame(engine, 10_050L);
        assertEquals(1, service.sent.size());

        shared.onCombatFrame(engine, 10_000L + CoopSharedCombatSession.FRAME_INTERVAL_MILLIS);
        assertEquals(2, service.sent.size());
    }

    private static CoopSessionState liveSession() {
        CoopSessionState session = new CoopSessionState(
                TestSessions.sequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(1234L, "seed", "fingerprint");
        return session;
    }

    private static CombatEngineAPI engine(List<Object> ships) {
        return (CombatEngineAPI) Proxy.newProxyInstance(
                CombatEngineAPI.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getShips" -> ships;
                    case "getPlayerShip" -> ships.isEmpty() ? null : ships.get(0);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "sharedCombatEngineProxy";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object ship(String id, String hullId, String name, int owner) {
        ShipHullSpecAPI hullSpec = (ShipHullSpecAPI) Proxy.newProxyInstance(
                ShipHullSpecAPI.class.getClassLoader(),
                new Class<?>[]{ShipHullSpecAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHullId" -> hullId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "hull:" + hullId;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Vector2f loc = new Vector2f(1f, 2f);
        return Proxy.newProxyInstance(
                ShipAPI.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getName" -> name;
                    case "getOwner" -> owner;
                    case "getHullSpec" -> hullSpec;
                    case "getLocation" -> loc;
                    case "getFacing" -> 90f;
                    case "getHitpoints" -> 100f;
                    case "getMaxHitpoints" -> 100f;
                    case "getFluxTracker" -> null;
                    case "isAlive" -> true;
                    case "isFighter", "isShuttlePod" -> false;
                    case "getVariant" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ship:" + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
