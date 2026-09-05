package coop.net;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.config.CoopOptionsRegistry;
import coop.handshake.CoopHandshakeManifest;
import coop.seed.CoopSeedSync;
import coop.session.CoopLobbyState;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.time.CoopTimeLock;
import coop.ui.CoopHudState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import coop.combat.CoopBattleResult;
import coop.stats.CoopSessionStats;
import coop.stats.CoopSessionStatsCodec;
import coop.stats.CoopSessionStatsStore;
import coop.ui.CoopDesyncReason;
import coop.ui.CoopDoctorMarker;
import coop.testing.LogCapture;
import coop.testing.RecordingNetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static coop.testing.ProxyDefaults.defaultValue;

class CoopNetPumpTest {
    /** A valid, empty {@code NPC_FLEET_MOTION} section body (Phase 20 M4 v2 format). */
    private static final String EMPTY_MOTION_BODY =
            coop.fleet.CoopNpcFleetMotion.encodeFullSection(java.util.List.of());

    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
        // Phase 20.6: every pump installs itself as the static intel feed, so one test's session
        // would otherwise still be on the page when the next one asks.
        coop.ui.CoopSessionIntelFeed.uninstall();
    }

    @Test
    void pumpRunsWhilePausedAndDoesNotComplete() {
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.GUEST), () -> 1000L);

        assertTrue(pump.runWhilePaused());
        assertFalse(pump.isDone());
    }

    @Test
    void guestSendsPingAfterTimerElapses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = new CoopNetPump(service, now::get);

        pump.advance(0f);
        now.set(4001L);
        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message message = service.sent.get(0);
        assertEquals(CoopMessages.Type.PING, message.type());
        assertEquals(1L, message.seq());
        assertEquals(4001L, message.sentAtMillis());
    }

    @Test
    void hostRepliesWithPongForInboundPing() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        service.inbound.add(CoopMessages.ping("session-a", 42L, 3000L));
        // Accepted peer: red-team A4 refuses to answer a ping from one that is neither in the
        // session nor in a grace window (see a4PingFromAnUnprovenPeerIsNotAnswered).
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 5000L);

        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message reply = service.sent.get(0);
        assertEquals(CoopMessages.Type.PONG, reply.type());
        assertEquals("session-a", reply.sessionId());
        assertEquals("{\"pingSeq\":42}", reply.payloadJson());
    }

    @Test
    void guestSendsLobbyHelloOnceConnected() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 7000L);

        pump.advance(0f);
        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message hello = service.sent.get(0);
        assertEquals(CoopMessages.Type.LOBBY_HELLO, hello.type());
        assertEquals("{\"playerId\":\"guest-player\",\"playerName\":\"Guest\"}", hello.payloadJson());
        assertEquals(CoopLobbyState.GUEST_CONNECTING, session.connectionState());
    }

    @Test
    void hostAcceptsFirstLobbyHelloAndRecordsRemoteGuest() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 8000L);

        pump.advance(0f);

        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
        assertEquals("guest-player", session.remotePlayerId());
        assertEquals(1, service.sent.size());
        CoopMessages.Message accept = service.sent.get(0);
        assertEquals(CoopMessages.Type.LOBBY_ACCEPT, accept.type());
        assertEquals("{\"provisionalLobbyId\":\"lobby-a\",\"hostPlayerId\":\"host-player\",\"hostName\":\"Host\"}",
                accept.payloadJson());
    }

    @Test
    void guestRecordsLobbyAcceptWithoutCanonicalSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        service.inbound.add(CoopMessages.lobbyAccept(1L, 8000L, "lobby-a",
                new CoopPlayerInfo("host-player", "Host")));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 9000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false);

        pump.advance(0f);

        assertEquals(CoopLobbyState.GUEST_CONNECTED, session.connectionState());
        assertEquals("lobby-a", session.provisionalLobbyId());
        assertEquals("host-player", session.remotePlayerId());
        assertEquals(2, service.sent.size());
        assertEquals(CoopMessages.Type.LOBBY_HELLO, service.sent.get(0).type());
        assertEquals(CoopMessages.Type.HANDSHAKE_MANIFEST, service.sent.get(1).type());
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
    }

    @Test
    void guestSendsHandshakeManifestAfterLobbyAccept() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        service.inbound.add(CoopMessages.lobbyAccept(1L, 8000L, "lobby-a",
                new CoopPlayerInfo("host-player", "Host")));
        CoopHandshakeManifest manifest = emptyManifest("0.98a-RC8", "commit-a");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 9000L, () -> manifest, () -> false);

        pump.advance(0f);

        assertEquals(CoopLobbyState.GUEST_CONNECTED, session.connectionState());
        assertEquals(2, service.sent.size());
        assertEquals(CoopMessages.Type.LOBBY_HELLO, service.sent.get(0).type());
        CoopMessages.Message handshake = service.sent.get(1);
        assertEquals(CoopMessages.Type.HANDSHAKE_MANIFEST, handshake.type());
        assertEquals(manifest.toJson(), CoopMessages.requiredPayloadString(handshake, "manifestJson"));
        assertEquals("false", CoopMessages.requiredPayloadString(handshake, "ironMode"));
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
    }

    @Test
    void hostAcceptsMatchingHandshakeAndAllocatesCanonicalSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopHandshakeManifest manifest = emptyManifest("0.98a-RC8", "commit-a");
        service.inbound.add(CoopMessages.handshakeManifest(2L, 9000L, manifest, false));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 10000L, () -> manifest, () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host");

        pump.advance(0f);

        assertEquals("session-a", session.sessionId());
        assertTrue(session.handshakeValidated());
        CoopMessages.Message result = service.sent.get(0);
        assertEquals(CoopMessages.Type.HANDSHAKE_RESULT, result.type());
        assertEquals("session-a", result.sessionId());
        assertEquals("true", CoopMessages.requiredPayloadString(result, "accepted"));
        assertEquals("session-a", CoopMessages.requiredPayloadString(result, "sessionId"));
    }

    @Test
    void hostSendsSeedLockRequestAfterHandshakeAcceptance() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopHandshakeManifest manifest = emptyManifest("0.98a-RC8", "commit-a");
        service.inbound.add(CoopMessages.handshakeManifest(2L, 9000L, manifest, false));
        CoopSeedSync.SeedData seed = new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 10000L, () -> manifest, () -> false,
                () -> seed, () -> "fingerprint-host");

        pump.advance(0f);

        assertEquals("session-a", session.sessionId());
        assertEquals(123456789L, session.seedLong());
        assertEquals("coop-seed", session.seedString());
        assertEquals("fingerprint-host", session.sectorFingerprint());
        assertEquals(2, service.sent.size());
        assertEquals(CoopMessages.Type.HANDSHAKE_RESULT, service.sent.get(0).type());
        CoopMessages.Message request = service.sent.get(1);
        assertEquals(CoopMessages.Type.SEED_LOCK_REQUEST, request.type());
        assertEquals("session-a", request.sessionId());
        assertEquals(123456789L, CoopMessages.requiredPayloadLong(request, "seedLong"));
        assertEquals("coop-seed", CoopMessages.requiredPayloadString(request, "seedString"));
        assertEquals("fingerprint-host", CoopMessages.requiredPayloadString(request, "sectorFingerprint"));
        assertFalse(CoopMessages.requiredPayloadString(request, "campaignId").isBlank(),
                "the host must mint and send a campaign id with the seed lock");
    }

    @Test
    void guestRejectsSeedLockWhenLocalFingerprintDiffers() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-1", true));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-guest",
                () -> "coop-seed");

        pump.advance(0f);

        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
        assertEquals(1, service.sent.size());
        CoopMessages.Message reject = service.sent.get(0);
        assertEquals(CoopMessages.Type.SEED_LOCK_REJECT, reject.type());
        assertEquals("sectorFingerprint: host=fingerprint-host guest=fingerprint-guest",
                CoopMessages.requiredPayloadString(reject, "reason"));
    }

    @Test
    void guestRejectsSeedLockWhenLocalSeedStringDiffers() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "MN-host", "fingerprint-host", "campaign-1", true));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "MN-guest");

        pump.advance(0f);

        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
        assertEquals(1, service.sent.size());
        CoopMessages.Message reject = service.sent.get(0);
        assertEquals(CoopMessages.Type.SEED_LOCK_REJECT, reject.type());
        assertEquals("seedString: host=MN-host guest=MN-guest",
                CoopMessages.requiredPayloadString(reject, "reason"));
    }

    @Test
    void hostRejectsMismatchedHandshakeBeforeSessionAllocation() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopHandshakeManifest hostManifest = emptyManifest("0.98a-RC8", "commit-a");
        CoopHandshakeManifest guestManifest = emptyManifest("0.97a", "commit-a");
        service.inbound.add(CoopMessages.handshakeManifest(2L, 9000L, guestManifest, false));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 10000L, () -> hostManifest, () -> false);

        pump.advance(0f);

        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
        CoopMessages.Message result = service.sent.get(0);
        assertEquals(CoopMessages.Type.HANDSHAKE_RESULT, result.type());
        assertNull(result.sessionId());
        assertEquals("false", CoopMessages.requiredPayloadString(result, "accepted"));
        assertEquals("gameVersion: host=0.98a-RC8 guest=0.97a",
                CoopMessages.requiredPayloadString(result, "diff"));
    }

    @Test
    void hostRejectsIronModeHandshakeBeforeSessionAllocation() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopHandshakeManifest manifest = emptyManifest("0.98a-RC8", "commit-a");
        service.inbound.add(CoopMessages.handshakeManifest(2L, 9000L, manifest, true));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 10000L, () -> manifest, () -> false);

        pump.advance(0f);

        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
        CoopMessages.Message result = service.sent.get(0);
        assertEquals("false", CoopMessages.requiredPayloadString(result, "accepted"));
        assertEquals("ironMode: guest=true", CoopMessages.requiredPayloadString(result, "diff"));
    }

    @Test
    void guestRecordsHandshakeAcceptSessionId() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        service.inbound.add(CoopMessages.handshakeResultAccept(3L, 11000L, "session-a"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 12000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false);

        pump.advance(0f);

        assertEquals("session-a", session.sessionId());
        assertTrue(session.handshakeValidated());
    }

    @Test
    void hostRejectsLobbyHelloAfterFirstGuestConnected() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        service.inbound.add(CoopMessages.lobbyHello(2L, 9000L, new CoopPlayerInfo("guest-b", "Guest B")));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 10000L);

        pump.advance(0f);

        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
        assertEquals("guest-a", session.remotePlayerId());
        assertEquals(1, service.sent.size());
        CoopMessages.Message reject = service.sent.get(0);
        assertEquals(CoopMessages.Type.LOBBY_REJECT, reject.type());
        assertEquals("{\"reason\":\"Lobby already has a guest\"}", reject.payloadJson());
    }

    @Test
    void hostSendsTimeSnapshotAtFiveHzAfterSeedLock() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        AtomicLong now = new AtomicLong(1000L);
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(true, false, 222333444L, 17L, 1200L, ""));
        CoopNetPump pump = new CoopNetPump(service, session, now::get,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                timeLock);

        pump.advance(0f);
        now.set(1199L);
        pump.advance(0f);
        now.set(1200L);
        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message snapshot = service.sent.get(0);
        assertEquals(CoopMessages.Type.TIME_SNAPSHOT, snapshot.type());
        assertEquals("session-a", snapshot.sessionId());
        assertEquals("true", CoopMessages.requiredPayloadString(snapshot, "paused"));
        assertEquals("false", CoopMessages.requiredPayloadString(snapshot, "fastForward"));
        assertEquals(222333444L, CoopMessages.requiredPayloadLong(snapshot, "timestampMillis"));
        assertEquals(17L, CoopMessages.requiredPayloadLong(snapshot, "campaignDay"));
        assertEquals(1200L, CoopMessages.requiredPayloadLong(snapshot, "sentAtMillis"));
    }

    @Test
    void guestAppliesLatestHostTimeSnapshotEveryFrame() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        CoopTimeLock.TimeSnapshot hostSnapshot =
                new CoopTimeLock.TimeSnapshot(true, true, 222333444L, 17L, 1500L, "");
        service.inbound.add(CoopMessages.timeSnapshot("session-a", 5L,
                hostSnapshot.paused(),
                hostSnapshot.fastForward(),
                hostSnapshot.timestampMillis(),
                hostSnapshot.campaignDay(),
                hostSnapshot.sentAtMillis(),
                hostSnapshot.pausedBy()));
        RecordingTimeLock timeLock = new RecordingTimeLock(hostSnapshot);
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1600L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                timeLock);

        pump.advance(0f);
        pump.advance(0f);

        assertEquals(List.of(hostSnapshot, hostSnapshot), timeLock.applied);
        assertEquals(List.of(true, true), timeLock.inputBlockerStates);
    }

    @Test
    void guestSendsScreenPauseIntentWhenBlockingScreenOpensAndCloses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        ui.showingMenu = true;
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message open = service.sent.get(0);
        assertEquals(CoopMessages.Type.PAUSE_INTENT, open.type());
        assertEquals("SCREEN", CoopMessages.requiredPayloadString(open, "source"));
        assertEquals("true", CoopMessages.requiredPayloadString(open, "paused"));
        assertEquals(1L, CoopMessages.requiredPayloadLong(open, "intentSeq"));

        ui.showingMenu = false;
        pump.advance(0f);

        assertEquals(2, service.sent.size());
        CoopMessages.Message close = service.sent.get(1);
        assertEquals(CoopMessages.Type.PAUSE_INTENT, close.type());
        assertEquals("SCREEN", CoopMessages.requiredPayloadString(close, "source"));
        assertEquals("false", CoopMessages.requiredPayloadString(close, "paused"));
        assertEquals(2L, CoopMessages.requiredPayloadLong(close, "intentSeq"));
    }

    @Test
    void hostAppliesGuestScreenPauseIntentToAuthoritativeClock() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        service.inbound.add(CoopMessages.pauseIntent(
                "session-a", 8L, 1200L, CoopMessages.PauseSource.SCREEN, true, 1L));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1300L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);

        assertTrue(sector.paused);

        service.inbound.add(CoopMessages.pauseIntent(
                "session-a", 9L, 1400L, CoopMessages.PauseSource.SCREEN, false, 2L));
        pump.advance(0f);

        assertFalse(sector.paused);
    }

    @Test
    void hostHoldsCampaignPausedUntilGameplaySessionIsActive() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, ""));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                timeLock);

        pump.advance(0f);

        assertTrue(sector.paused);

        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        advancePastTheHeadlessLobbyValve(pump);

        // Phase 20 live QA (F2): coop releases what coop paused, with no key press from the host.
        // This assertion used to be reachable only because the test unpaused the sector by hand.
        assertFalse(sector.paused);
        assertFalse(pump.pauseCoordinatorForBridge().hostPauseIntent(),
                "the connect-time hold is not the host player pressing pause");
        assertFalse(pump.pauseCoordinatorForBridge().effectivePaused());
    }

    @Test
    void hostBroadcastsAcceptedLocalInteractionAndReleaseOnDialogClose() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);

        // Phase 28 M2: a live host session also owes the guest one OPTIONS_SNAPSHOT on its first
        // frame, so this counts the interaction traffic by type rather than counting everything.
        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_ACCEPT));
        CoopMessages.Message accept = lastOfType(service, CoopMessages.Type.INTERACTION_ACCEPT);
        assertEquals("market-1", CoopMessages.requiredPayloadString(accept, "entityId"));
        assertEquals("host-player", CoopMessages.requiredPayloadString(accept, "playerId"));
        assertEquals("Jangala", CoopMessages.requiredPayloadString(accept, "entityName"));
        assertEquals(1L, CoopMessages.requiredPayloadLong(accept, "hostSeq"));

        ui.target = null;
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_RELEASE));
        CoopMessages.Message release = lastOfType(service, CoopMessages.Type.INTERACTION_RELEASE);
        assertEquals(CoopMessages.Type.INTERACTION_RELEASE, release.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(release, "entityId"));
        assertEquals("host-player", CoopMessages.requiredPayloadString(release, "playerId"));
    }

    @Test
    void hostRejectsGuestClaimForEntityAlreadyHeldByHost() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));
        pump.advance(0f);
        service.sent.clear();

        service.inbound.add(CoopMessages.interactionClaim(
                "session-a", 7L, 1100L, "market-1", "Jangala", "guest-player"));
        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message reject = service.sent.get(0);
        assertEquals(CoopMessages.Type.INTERACTION_REJECT, reject.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(reject, "entityId"));
        assertEquals("already_claimed_by:host-player", CoopMessages.requiredPayloadString(reject, "reason"));
    }

    @Test
    void guestSendsInteractionClaimAndReleaseForLocalDialog() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, ""));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, timeLock);

        pump.advance(0f);

        assertEquals(2, service.sent.size());
        CoopMessages.Message pauseOpen = service.sent.get(0);
        assertEquals(CoopMessages.Type.PAUSE_INTENT, pauseOpen.type());
        assertEquals("SCREEN", CoopMessages.requiredPayloadString(pauseOpen, "source"));
        assertEquals("true", CoopMessages.requiredPayloadString(pauseOpen, "paused"));
        CoopMessages.Message claim = service.sent.get(1);
        assertEquals(CoopMessages.Type.INTERACTION_CLAIM, claim.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(claim, "entityId"));
        assertEquals("Jangala", CoopMessages.requiredPayloadString(claim, "entityName"));
        assertEquals("guest-player", CoopMessages.requiredPayloadString(claim, "playerId"));

        ui.target = null;
        pump.advance(0f);

        assertEquals(4, service.sent.size());
        CoopMessages.Message pauseClose = service.sent.get(2);
        assertEquals(CoopMessages.Type.PAUSE_INTENT, pauseClose.type());
        assertEquals("SCREEN", CoopMessages.requiredPayloadString(pauseClose, "source"));
        assertEquals("false", CoopMessages.requiredPayloadString(pauseClose, "paused"));
        CoopMessages.Message release = service.sent.get(3);
        assertEquals(CoopMessages.Type.INTERACTION_RELEASE, release.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(release, "entityId"));
        assertEquals("guest-player", CoopMessages.requiredPayloadString(release, "playerId"));
        assertTrue(timeLock.inputBlockerStates.contains(true));
    }

    @Test
    void guestBlocksWorldInteractionWhileHostClaimIsActive() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, ""));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, timeLock);

        service.inbound.add(CoopMessages.interactionAccept(
                "session-a", 8L, 1200L, "market-1", "host-player", "Jangala", 5L));
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(2, ui.disallowInteractionCount);
        assertEquals(List.of("Remote player is interacting: Jangala"), ui.messages);
        assertEquals(List.of(true, true), timeLock.interactionBlocks);

        service.inbound.add(CoopMessages.interactionRelease(
                "session-a", 9L, 1300L, "market-1", "host-player"));
        pump.advance(0f);

        assertEquals(false,
                timeLock.interactionBlocks.get(timeLock.interactionBlocks.size() - 1));
    }

    // ---- Phase 18: interaction-gate WAN race ---------------------------------------------------

    @Test
    void guestDialogIsForceClosedWhenTheHostRejectsTheClaim() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        // The guest opens optimistically and claims; the host says no.
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_CLAIM));
        service.inbound.add(CoopMessages.interactionReject(
                "session-a", 9L, 1100L, "market-1", "already_claimed_by:host-player"));

        pump.advance(0f);

        assertEquals(1, ui.dismissCount, "the losing dialog must be dismissed");
        assertTrue(ui.messages.stream().anyMatch(m -> m.contains("Host is using Jangala")),
                "the guest must be told why its dialog vanished: " + ui.messages);
    }

    @Test
    void aRejectedClaimIsNeverReclaimedWhileItsDialogIsStillOpen() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        // The dialog outlives the first dismiss, which is the state that used to produce a
        // claim/reject ping-pong at up to 60 msg/s plus one warn per frame.
        ui.dismissClosesDialog = false;
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        service.inbound.add(CoopMessages.interactionReject(
                "session-a", 9L, 1100L, "market-1", "already_claimed_by:host-player"));
        pump.advance(0f);
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_CLAIM),
                "exactly one claim per lost race");
        assertEquals(3, ui.dismissCount, "the dismissal is re-asserted until the dialog is gone");
        assertEquals(1, ui.messages.stream().filter(m -> m.contains("Host is using")).count(),
                "the in-use message is shown once, not once per frame: " + ui.messages);
    }

    @Test
    void theEntityBecomesClaimableAgainOnceTheDialogIsActuallyClosed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        service.inbound.add(CoopMessages.interactionReject(
                "session-a", 9L, 1100L, "market-1", "already_claimed_by:host-player"));
        pump.advance(0f);
        // The dismissal took: the next frame sees no dialog, releases and stops tracking.
        pump.advance(0f);

        // The player re-docks after the host let go.
        ui.target = entity;
        pump.advance(0f);

        assertEquals(2, countOfType(service, CoopMessages.Type.INTERACTION_CLAIM),
                "a closed rejection must not lock the entity out for the rest of the session");
    }

    @Test
    void aRejectForAnotherEntityNeverTouchesTheOpenDialog() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        service.inbound.add(CoopMessages.interactionReject(
                "session-a", 9L, 1100L, "derelict-7", "already_claimed_by:host-player"));
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(0, ui.dismissCount, "a rejection for another entity must not close this dialog");
        assertTrue(ui.messages.isEmpty(), "no message for an unrelated rejection: " + ui.messages);
    }

    // ---- Bug hunt: claims across a session edge, and the host's own lost race -------------------

    /**
     * A NAT drop whose replacement socket is accepted inside one poll never shows the pump a
     * disconnected frame, so the claim the peer held at drop time used to survive into the resumed
     * session: "Remote player is interacting: X" every frame, and no local interaction possible at
     * all, until the peer happened to open and close X again.
     */
    @Test
    void aStaleRemoteClaimIsDroppedWhenTheSocketIsReplacedWithoutADisconnect() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, ""));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, timeLock);

        service.inbound.add(CoopMessages.interactionClaim(
                "session-a", 7L, 1000L, "market-1", "Jangala", "guest-player"));
        pump.advance(0f);
        assertTrue(ui.messages.stream().anyMatch(m -> m.contains("Remote player is interacting")),
                "the guest holds the market: " + ui.messages);
        ui.messages.clear();
        int blockedFrames = ui.disallowInteractionCount;

        // The half-open link is replaced inside one poll: isConnected() reads true either side of it.
        service.connectionGeneration++;
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(blockedFrames, ui.disallowInteractionCount,
                "the claim died with the socket that made it; the host is free to interact");
        assertTrue(ui.messages.stream().noneMatch(m -> m.contains("Remote player is interacting")),
                "no lockout banner after the replacement: " + ui.messages);
        assertEquals(false,
                timeLock.interactionBlocks.get(timeLock.interactionBlocks.size() - 1));
    }

    /**
     * The same claim, recorded by the drain on the very frame the socket died — the interleaving the
     * transport really produces, since one poll both queues the frames it read and closes the link on
     * the read that hit the end of the stream. None of the reset's four side flags has seen that
     * claim (applyLocalBlocking never ran for it), so the reset used to skip the gate entirely and
     * the claim blocked every host interaction in the resumed session.
     */
    @Test
    void aRemoteClaimAcceptedOnTheFrameTheLinkDiedDoesNotOutliveIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, ""));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, timeLock);
        pump.advance(0f);

        service.dropWhenDrained = true;
        service.inbound.add(CoopMessages.interactionClaim(
                "session-a", 7L, 1100L, "market-1", "Jangala", "guest-player"));
        pump.advance(0f);
        pump.advance(0f);
        ui.messages.clear();
        int blockedFrames = ui.disallowInteractionCount;

        // The guest comes back inside the grace window.
        service.dropWhenDrained = false;
        service.connected = true;
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(blockedFrames, ui.disallowInteractionCount,
                "a claim from the dead connection must not lock the host out of the resumed session");
        assertTrue(ui.messages.stream().noneMatch(m -> m.contains("Remote player is interacting")),
                "no lockout banner after the resume: " + ui.messages);
    }

    /**
     * Phase 18 for the host's own dialog. The guest's claim is drained before the host's freshly
     * opened dialog is detected, so the host can lose the race on its own machine — and logging it
     * while leaving the dialog up left both players in one shop, the state the gate exists to
     * prevent.
     */
    @Test
    void theHostsOwnDialogIsForceClosedWhenItLosesTheClaimRace() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        service.inbound.add(CoopMessages.interactionClaim(
                "session-a", 7L, 1000L, "market-1", "Jangala", "guest-player"));
        pump.advance(0f);

        // The engine opened the host's dialog on the same market between frames.
        ui.target = entity;
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(1, ui.dismissCount, "the host's losing dialog must be dismissed too");
        assertTrue(ui.messages.stream().anyMatch(m -> m.contains("Guest is using Jangala")),
                "and the host told why: " + ui.messages);
        assertEquals("guest-player", CoopMessages.requiredPayloadString(
                        lastOfType(service, CoopMessages.Type.INTERACTION_ACCEPT), "playerId"),
                "the only accept on the wire is the guest's; the host's own claim was refused");
    }

    // ---- colony-3: the colony editor is a core tab, not a dialog --------------------------------

    @Test
    void theColonyEditorIsClaimedThroughTheGateWhileTheOutpostsTabIsOpen() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        ui.coreTab = com.fs.starfarer.api.campaign.CoreUITabId.OUTPOSTS;
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_CLAIM),
                "the colony screen edits a colony; it has to hold the claim while it is open");
        CoopMessages.Message claim = lastOfType(service, CoopMessages.Type.INTERACTION_CLAIM);
        assertEquals(CoopNetPump.COLONY_MANAGEMENT_ENTITY_ID,
                CoopMessages.requiredPayloadString(claim, "entityId"));
        assertEquals(CoopNetPump.COLONY_MANAGEMENT_ENTITY_NAME,
                CoopMessages.requiredPayloadString(claim, "entityName"));

        ui.coreTab = null;
        pump.advance(0f);

        CoopMessages.Message release = lastOfType(service, CoopMessages.Type.INTERACTION_RELEASE);
        assertEquals(CoopNetPump.COLONY_MANAGEMENT_ENTITY_ID,
                CoopMessages.requiredPayloadString(release, "entityId"),
                "closing the tab lets the other player in");
    }

    @Test
    void theSecondPlayerIntoTheColonyEditorIsBouncedOutOfIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        service.inbound.add(CoopMessages.interactionClaim("session-a", 7L, 1000L,
                CoopNetPump.COLONY_MANAGEMENT_ENTITY_ID, CoopNetPump.COLONY_MANAGEMENT_ENTITY_NAME,
                "guest-player"));
        pump.advance(0f);

        // The host opens its own colony screen while the guest is still in one.
        ui.coreTab = com.fs.starfarer.api.campaign.CoreUITabId.OUTPOSTS;
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(List.of(com.fs.starfarer.api.campaign.CoreUITabId.INTEL), ui.coreTabSwitches,
                "there is no dialog to dismiss, so the editor is taken away by leaving the tab");
        assertTrue(ui.messages.stream().anyMatch(m -> m.contains("Guest is using colony management")),
                "and the player is told why: " + ui.messages);
    }

    // ---- Bug hunt: stats, the terminal-stop linger, grace and the bridge lever ------------------

    /**
     * "Time flown together" is sampled on a wall-clock cadence, so a stopped clock has to earn
     * nothing: twenty minutes parked in a market screen used to add twenty minutes of flight to a
     * counter that is persisted into the coordinated save.
     */
    @Test
    void timeFlownTogetherDoesNotAccrueWhileTheClockIsStopped() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingFleet hostFleet = new RecordingFleet();
        hostFleet.locationId = "corvus";
        RecordingSector sector = new RecordingSector(true, ui, hostFleet);
        Global.setSector(sector.proxy());
        RecordingFleet guestMirror = new RecordingFleet();
        guestMirror.locationId = "corvus";
        coop.fleet.CoopGuestMirrorHandle.publish(guestMirror.proxy());
        try {
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                    new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

            pump.advance(0f);
            now.addAndGet(1000L);
            pump.advance(0f);

            coop.stats.CoopSessionStats stats = coop.stats.CoopSessionStatsStore.current();
            assertEquals(0f, stats.timeFlownTogetherSeconds(), 0.001f,
                    "the two fleets share a system, but nobody is flying");

            sector.paused = false;
            now.addAndGet(1000L);
            pump.advance(0f);

            assertEquals(1f, stats.timeFlownTogetherSeconds(), 0.001f,
                    "one sample of a running clock in the same system is one second together");
        } finally {
            coop.fleet.CoopGuestMirrorHandle.clear();
            coop.stats.CoopSessionStatsStore.clear();
        }
    }

    /**
     * The linger waits for the reject to leave the socket. A frame the kernel took only part of is
     * parked in the peer's pendingWrite, where the queue depth cannot see it — closing there is the
     * truncated frame the linger was written to prevent.
     */
    @Test
    void theTerminalStopWaitsForAFrameTheKernelOnlyTookPartOf() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(hostSeedLockRequest());
        service.partialWritePending = true;
        CoopNetPump pump = pumpForGuestSeedLock(service, session,
                new java.util.concurrent.atomic.AtomicReference<>("campaign-a"),
                false, false, "fingerprint-guest", () -> "");

        pump.advance(0f);

        assertEquals(1, countOf(service, CoopMessages.Type.SEED_LOCK_REJECT));
        assertTrue(service.stopReconnectingReasons.isEmpty(),
                "the reject is still half-written; closing now truncates it");

        service.partialWritePending = false;
        pump.advance(0f);

        assertEquals(1, service.stopReconnectingReasons.size(),
                "and the loop closes as soon as the socket has really taken it");
    }

    /**
     * A reconnect window is not a session end. Disposing the campaign replicator for a link blip
     * ended every mirrored expedition warning the guest was reading and handed them back on resume
     * as fresh, re-starred interrupts.
     */
    @Test
    void theCampaignReplicatorIsKeptThroughAReconnectGraceWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));
        pump.advance(0f);
        assertTrue(pump.campaignReplicatorShouldBeActive());

        service.connected = false;
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(), "the window is open");
        assertTrue(pump.campaignReplicatorShouldBeActive(),
                "the session record is alive for the whole window, and so is its replicated state");
    }

    /**
     * Phase 30 bridge lever, guest side. Writing the coordinator's screen latch looked like it
     * worked and did nothing: the pump recomputes that level from the real UI every frame, so the
     * intent was reverted before it was ever sent.
     */
    @Test
    void theBridgePauseLeverShipsAGuestScreenIntentTheNextFrameKeeps() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        assertEquals(0, countOfType(service, CoopMessages.Type.PAUSE_INTENT),
                "nothing is open, so nothing is asserted");

        assertTrue(pump.setBridgePauseIntent(true));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.PAUSE_INTENT));
        CoopMessages.Message intent = lastOfType(service, CoopMessages.Type.PAUSE_INTENT);
        assertEquals("SCREEN", CoopMessages.requiredPayloadString(intent, "source"));
        assertEquals("true", CoopMessages.requiredPayloadString(intent, "paused"));

        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.PAUSE_INTENT),
                "the per-frame recompute must not revert the lever underneath the host");

        assertTrue(pump.setBridgePauseIntent(false));
        pump.advance(0f);
        assertEquals("false", CoopMessages.requiredPayloadString(
                lastOfType(service, CoopMessages.Type.PAUSE_INTENT), "paused"));
    }

    @Test
    void hostHoldsInboundClaimsForTheDebugLatencyLever() {
        String saved = System.getProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY);
        System.setProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY, "500");
        try {
            forceDebugToggleRefresh();
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = activeHostSession();
            RecordingCampaignUi ui = new RecordingCampaignUi(null);
            Global.setSector(new RecordingSector(false, ui).proxy());
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                    new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

            service.inbound.add(CoopMessages.interactionClaim(
                    "session-a", 7L, 1000L, "market-1", "Jangala", "guest-player"));
            pump.advance(0f);
            assertEquals(0, countOfType(service, CoopMessages.Type.INTERACTION_ACCEPT),
                    "the claim must be held, not arbitrated on arrival");

            now.set(1499L);
            pump.advance(0f);
            assertEquals(0, countOfType(service, CoopMessages.Type.INTERACTION_ACCEPT),
                    "still inside the induced delay");

            now.set(1500L);
            pump.advance(0f);
            assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_ACCEPT),
                    "the claim must be arbitrated once the delay elapses");
        } finally {
            if (saved == null) {
                System.clearProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY);
            } else {
                System.setProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY, saved);
            }
            forceDebugToggleRefresh();
        }
    }

    @Test
    void hostArbitratesImmediatelyWhenTheLeverIsDormant() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        service.inbound.add(CoopMessages.interactionClaim(
                "session-a", 7L, 1000L, "market-1", "Jangala", "guest-player"));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_ACCEPT));
    }

    @Test
    void hostHoldsGuestPauseIntentsForTheDebugLatencyLever() {
        // The pause intent rides the same guest->host leg as the interaction claim. If the lever
        // delayed only the claim, the guest's screen pause would freeze the host instantly on
        // localhost and the claim race would be impossible to reach by hand — the lever must
        // simulate the whole leg.
        String saved = System.getProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY);
        System.setProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY, "500");
        try {
            forceDebugToggleRefresh();
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = activeHostSession();
            RecordingSector sector = new RecordingSector(false);
            Global.setSector(sector.proxy());
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                    new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

            service.inbound.add(CoopMessages.pauseIntent(
                    "session-a", 8L, 1000L, CoopMessages.PauseSource.SCREEN, true, 1L));
            pump.advance(0f);
            assertFalse(sector.paused, "the pause intent must be held, not applied on arrival");

            now.set(1499L);
            pump.advance(0f);
            assertFalse(sector.paused, "still inside the induced delay");

            now.set(1500L);
            pump.advance(0f);
            assertTrue(sector.paused, "the pause intent must apply once the delay elapses");
        } finally {
            if (saved == null) {
                System.clearProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY);
            } else {
                System.setProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY, saved);
            }
            forceDebugToggleRefresh();
        }
    }

    /** Drives {@link coop.util.CoopDebug}'s frame poll far enough to re-read the JVM properties. */
    private static void forceDebugToggleRefresh() {
        for (int i = 0; i <= 300; i++) {
            coop.util.CoopDebug.pollFrame();
        }
    }

    private static long countOfType(RecordingNetService service, CoopMessages.Type type) {
        return service.sent.stream().filter(m -> m.type() == type).count();
    }

    // ---- Phase 12b: inbound dispatch guard ---------------------------------------------------

    @Test
    void malformedMessageIsDroppedAndLaterMessagesStillProcess() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        // Lobby-accepted, because since red-team A4 a PONG is only owed to an accepted peer and the
        // PONG is this test's proof that dispatch survived the bad message.
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        // INTERACTION_RELEASE without the required "entityId" field: the handler's
        // requiredPayloadString throws, which pre-12b escaped advance() and killed the pump.
        service.inbound.add(new CoopMessages.Message(
                CoopMessages.Type.INTERACTION_RELEASE, "session-a", 1L, 1000L, "{}"));
        service.inbound.add(CoopMessages.ping("session-a", 2L, 1000L));

        pump.advance(0f);

        // The PING queued behind the bad message still produced its PONG.
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.PONG),
                "expected the message after the malformed one to still be dispatched");
    }

    @Test
    void unknownPauseIntentSourceIsDroppedWithoutKillingThePump() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        // Lobby-accepted; see malformedMessageIsDroppedAndLaterMessagesStillProcess.
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        // PauseSource.valueOf throws IllegalArgumentException on an unknown value.
        service.inbound.add(new CoopMessages.Message(
                CoopMessages.Type.PAUSE_INTENT, "session-a", 1L, 1000L,
                "{\"source\":\"NOT_A_REAL_SOURCE\",\"paused\":\"true\",\"intentSeq\":\"1\"}"));
        service.inbound.add(CoopMessages.ping("session-a", 2L, 1000L));

        pump.advance(0f);

        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.PONG),
                "expected dispatch to continue after an unknown PAUSE_INTENT source");
    }

    @Test
    void handshakeManifestOutsideHostConnectedIsIgnored() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = new CoopNetPump(service, () -> 1000L);

        // Host is in HOST_WAITING (no lobby yet); pre-12b this reached hostAcceptHandshake() and
        // threw IllegalStateException.
        service.inbound.add(new CoopMessages.Message(
                CoopMessages.Type.HANDSHAKE_MANIFEST, "session-a", 1L, 1000L,
                "{\"manifestJson\":\"{}\"}"));
        // The "dispatch continued" probe is a lobby hello rather than a ping: this host is
        // deliberately still HOST_WAITING, and since red-team A4 an unaccepted peer gets no PONG.
        service.inbound.add(CoopMessages.lobbyHello(2L, 1000L,
                new CoopPlayerInfo("guest-player", "Guest")));

        pump.advance(0f);

        assertTrue(service.sent.stream().noneMatch(m -> m.type() == CoopMessages.Type.HANDSHAKE_RESULT),
                "an out-of-order manifest must not produce a handshake result");
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.LOBBY_ACCEPT
                        || m.type() == CoopMessages.Type.LOBBY_REJECT),
                "expected dispatch to continue after an out-of-order manifest");
    }

    // ---- Phase 12b: reconnect hygiene --------------------------------------------------------

    @Test
    void hostAcceptsRejoinAfterGuestChannelDies() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a", "session-b"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = new CoopNetPump(service, session, now::get,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host",
                () -> "coop-seed");
        pump.advance(0f);

        // The in-game failure: guest quit, host detected the dead channel, but the guest slot was
        // never freed, so the rejoin's LOBBY_HELLO got "Lobby already has a guest" forever. Since
        // Phase 20.2 the slot is held for the grace window first; this is the post-expiry behaviour.
        service.connected = false;
        pump.advance(0f);
        now.set(1000L + 61_000L);
        pump.advance(0f);

        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
        assertNull(session.remotePlayerId());
        assertNull(session.sessionId());
        assertFalse(session.handshakeValidated());
        assertNull(session.seedLong());

        service.connected = true;
        service.inbound.add(CoopMessages.lobbyHello(1L, 2000L, new CoopPlayerInfo("guest-b", "Guest B")));
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
        assertEquals("guest-b", session.remotePlayerId());
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.LOBBY_ACCEPT),
                "the rejoining guest must be accepted, not rejected");
        assertTrue(service.sent.stream().noneMatch(m -> m.type() == CoopMessages.Type.LOBBY_REJECT));
    }

    @Test
    void guestResendsLobbyHelloAfterChannelDropAndReconnect() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = new CoopNetPump(service, session, now::get,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed");
        pump.advance(0f);
        service.sent.clear();

        // Network blip: the service auto-reconnects TCP, but pre-fix the pump never resent the
        // hello (lobbyHelloSent stayed true) and the session state stayed post-lock, deadlocking
        // both sides. Since Phase 20.2 the guest first spends the grace window asking for a resume;
        // this is what it does once that window has expired.
        service.connected = false;
        pump.advance(0f);
        now.set(1000L + 61_000L);
        pump.advance(0f);

        assertEquals(CoopLobbyState.GUEST_CONNECTING, session.connectionState());
        assertNull(session.sessionId());

        service.connected = true;
        pump.advance(0f);

        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.LOBBY_HELLO),
                "the reconnected guest must restart the lobby sequence");
    }

    @Test
    void preSessionCampaignTrafficIsIgnored() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        // The in-game failure's second half: a lobby-rejected peer (or any unauthenticated socket)
        // streaming session traffic. Pre-fix this WORLD_DELTA reached the replicator and poisoned
        // the consume ledger of what is effectively a solo campaign.
        service.inbound.add(CoopMessages.worldDelta("stale-session", 5L, 1000L,
                "entity-1", "CONSUME", true, "", "guest-a"));
        service.inbound.add(CoopMessages.ping("stale-session", 6L, 1000L));
        pump.advance(0f);

        // Establish a real session afterwards and report the same entity consumed: if the ledger
        // had been poisoned, this first legitimate apply would come back false.
        session.hostAcceptGuest(new CoopPlayerInfo("guest-b", "Guest B"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        service.inbound.add(CoopMessages.worldDelta("session-a", 7L, 2000L,
                "entity-1", "CONSUME", true, "", "guest-b"));
        pump.advance(0f);

        // The post-session delta is a first apply, so the host echoes it back out.
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.WORLD_DELTA),
                "a fresh session's first consume must still apply (ledger was not poisoned pre-session)");
    }

    // ---- Phase 6b: campaign identity + diagnosable fingerprint -------------------------------

    @Test
    void hostMintsCampaignIdOnceAndReusesTheStoredIdAcrossPumpRestarts() {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("");

        RecordingNetService first = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump firstPump = pumpForHostSeedLock(first, hostSessionReadyForSeedLock("session-a"), stored);
        firstPump.advance(0f);
        CoopMessages.Message firstRequest = seedLockRequestIn(first);
        String minted = CoopMessages.requiredPayloadString(firstRequest, "campaignId");
        assertFalse(minted.isBlank());
        assertEquals("", stored.get(),
                "the id is written to the save only once a guest has accepted the lock it rode on");
        assertEquals("true", CoopMessages.requiredPayloadString(firstRequest, "campaignIdMinted"),
                "the birth seed lock must announce the id as freshly minted");

        first.inbound.add(CoopMessages.seedLockAck("session-a", 9L, 14100L, "fingerprint-host"));
        firstPump.advance(0f);
        assertEquals(minted, stored.get(), "the accepted id is stored for the campaign's lifetime");

        // A later session of the same campaign (fresh pump + session state) reuses the stored id.
        RecordingNetService second = new RecordingNetService(CoopConnectionRole.HOST);
        pumpForHostSeedLock(second, hostSessionReadyForSeedLock("session-b"), stored).advance(0f);
        CoopMessages.Message secondRequest = seedLockRequestIn(second);
        assertEquals(minted, CoopMessages.requiredPayloadString(secondRequest, "campaignId"));
        assertEquals("false", CoopMessages.requiredPayloadString(secondRequest, "campaignIdMinted"),
                "an in-flight campaign must not present as being born");
    }

    /**
     * net-15: a birth seed lock that no guest ever accepted must not leave the campaign looking
     * "already in flight". Persisting at request time made the corrected guest's fresh campaign
     * unjoinable without -AdoptCampaign, for a campaign that had never run a session.
     */
    @Test
    void aRejectedBirthSeedLockStillPresentsAsTheBirthLockToTheNextGuest() {
        java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>("");

        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = hostSessionReadyForSeedLock("session-a");
        CoopNetPump pump = pumpForHostSeedLock(service, session, stored);
        pump.advance(0f);
        String minted = CoopMessages.requiredPayloadString(seedLockRequestIn(service), "campaignId");

        // The guest typed the wrong seed and refuses the lock, then quits.
        service.inbound.add(CoopMessages.seedLockReject("session-a", 9L, 14100L,
                "seedString: host=coop-seed guest=other-seed"));
        pump.advance(0f);
        assertEquals("", stored.get(), "nothing accepted the id, so nothing was written to the save");

        // A corrected fresh guest reconnects and the host runs a second lock in the same process.
        RecordingNetService second = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump secondPump = pumpForHostSeedLock(second, hostSessionReadyForSeedLock("session-b"), stored);
        secondPump.advance(0f);
        CoopMessages.Message secondRequest = seedLockRequestIn(second);
        assertEquals("true", CoopMessages.requiredPayloadString(secondRequest, "campaignIdMinted"),
                "a campaign nobody ever joined is still being born");

        second.inbound.add(CoopMessages.seedLockAck("session-b", 11L, 14200L, "fingerprint-host"));
        secondPump.advance(0f);
        assertEquals(CoopMessages.requiredPayloadString(secondRequest, "campaignId"), stored.get());
        assertFalse(minted.isEmpty());
    }

    @Test
    void guestAdoptsHostCampaignIdAtCampaignBirth() {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", true));

        pumpForGuestSeedLock(service, session, stored, false, false, "fingerprint-host", () -> "").advance(0f);

        assertEquals("campaign-host", stored.get(), "campaign being born: adopt the host's id and continue");
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_ACK),
                "adoption must not block the seed lock");
        assertEquals(123456789L, session.seedLong());
    }

    @Test
    void freshCampaignCannotSilentlyJoinAnInFlightCampaign() {
        // The replay hole itself, found live in the 6b smoke test: a fresh same-seed New Game has no
        // stored id, and the original adopt-on-absent policy waved it straight into a mid-flight
        // campaign it was never part of.
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, false, false, "fingerprint-host", () -> "").advance(0f);

        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertEquals("", stored.get(), "a rejected fresh campaign must not adopt the id");
        CoopMessages.Message reject = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.SEED_LOCK_REJECT).findFirst().orElseThrow();
        String reason = CoopMessages.requiredPayloadString(reject, "reason");
        assertTrue(reason.startsWith("campaignId: host=campaign-host guest=<none>"), reason);
        assertTrue(reason.contains("-Dcoop.adoptCampaignId=true"), reason);
        assertTrue(service.sent.stream().noneMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_ACK));
    }

    @Test
    void adoptFlagLetsAFreshCampaignJoinInFlightAsTheSavelessRejoinPath() {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, true, false, "fingerprint-host", () -> "").advance(0f);

        assertEquals("campaign-host", stored.get());
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_ACK),
                "the explicit-consent flag is the sanctioned save-less rejoin path");
    }

    @Test
    void pre6bCoopSaveMigratesByAdoption() {
        // Markers-without-id = a save from before campaign ids existed; it joins by adoption
        // without needing the consent flag.
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, false, true, "fingerprint-host", () -> "").advance(0f);

        assertEquals("campaign-host", stored.get());
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_ACK));
    }

    @Test
    void guestRejectsCampaignIdMismatchNamingTheAdoptFlag() {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("campaign-old");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, false, false, "fingerprint-host", () -> "").advance(0f);

        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertEquals("campaign-old", stored.get(), "no silent adoption on mismatch");
        CoopMessages.Message reject = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.SEED_LOCK_REJECT).findFirst().orElseThrow();
        String reason = CoopMessages.requiredPayloadString(reject, "reason");
        assertTrue(reason.startsWith("campaignId: host=campaign-host guest=campaign-old"), reason);
        assertTrue(reason.contains("-Dcoop.adoptCampaignId=true"),
                "the reject must name the explicit-consent flag");
        assertTrue(service.sent.stream().noneMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_ACK));
    }

    @Test
    void adoptFlagOverridesACampaignIdMismatch() {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("campaign-old");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, true, false, "fingerprint-host", () -> "").advance(0f);

        assertEquals("campaign-host", stored.get(), "the adopt flag overwrites the stored id");
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_ACK));
        assertEquals(123456789L, session.seedLong());
    }

    @Test
    void campaignIdMismatchWinsOverASimultaneousFingerprintMismatch() {
        // Check order: identity first, so "wrong save" produces the clear message instead of an
        // undiagnosable pair of fingerprint hashes.
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>("campaign-old");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, false, false, "fingerprint-guest", () -> "").advance(0f);

        CoopMessages.Message reject = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.SEED_LOCK_REJECT).findFirst().orElseThrow();
        assertTrue(CoopMessages.requiredPayloadString(reject, "reason").startsWith("campaignId:"));
    }

    @Test
    void canonicalFingerprintIsDumpedOnMismatchAndNotOnSuccess() {
        java.util.concurrent.atomic.AtomicLong canonicalReads = new java.util.concurrent.atomic.AtomicLong();
        Supplier<String> canonical = () -> {
            canonicalReads.incrementAndGet();
            return "entry-1\nentry-2";
        };

        // Fingerprint mismatch: the guest dumps its canonical text for log diffing.
        RecordingNetService mismatch = new RecordingNetService(CoopConnectionRole.GUEST);
        mismatch.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", true));
        pumpForGuestSeedLock(mismatch, guestSessionReadyForSeedLock(),
                new java.util.concurrent.atomic.AtomicReference<>(""), false, false, "fingerprint-guest", canonical)
                .advance(0f);
        assertEquals(1L, canonicalReads.get(), "a fingerprint mismatch must dump the canonical text");

        // Matching fingerprints: no dump.
        canonicalReads.set(0L);
        RecordingNetService match = new RecordingNetService(CoopConnectionRole.GUEST);
        match.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", true));
        pumpForGuestSeedLock(match, guestSessionReadyForSeedLock(),
                new java.util.concurrent.atomic.AtomicReference<>(""), false, false, "fingerprint-host", canonical)
                .advance(0f);
        assertEquals(0L, canonicalReads.get(), "a clean seed lock must not spam the canonical text");
    }

    // ---- Phase 20.1 M2: link supervision, TCP fallback, HUD link fields -------------------------

    @Test
    void hostPingsThePeerOnTheSameThreeSecondCadenceTheGuestUses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);

        pump.advance(0f);
        now.set(3_999L);
        pump.advance(0f);
        assertEquals(0, countOf(service, CoopMessages.Type.PING), "not due yet");

        now.set(4_001L);
        pump.advance(0f);
        assertEquals(1, countOf(service, CoopMessages.Type.PING),
                "the host measures its own RTT now, so it pings too");
    }

    @Test
    void aPongIsTimedAndSurfacesAsTheHudRoundTripTime() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);

        pump.advance(0f);
        now.set(4_001L);
        pump.advance(0f);
        CoopMessages.Message ping = onlyOf(service, CoopMessages.Type.PING);

        now.set(4_121L);
        service.inbound.add(CoopMessages.pong("session-a", 99L, 4_121L, ping.seq()));
        pump.advance(0f);

        CoopHudState hud = pump.hudState(false);
        assertEquals(120, hud.rttMillis());
        assertEquals(0, hud.lossPercent());
        assertEquals(CoopHudState.TRANSPORT_UDP, hud.transport());
        String line = CoopHudState.formatLine(hud, CoopHudState.SEPARATOR_PIPE);
        assertTrue(line.endsWith("120 ms | loss 0% | udp"), line);
    }

    /** A PONG that answers no PING we sent must not become a fabricated RTT sample. */
    @Test
    void anUnmatchedPongProducesNoRoundTripTime() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        service.inbound.add(CoopMessages.pong("session-a", 99L, 1_500L, 4242L));
        now.set(1_500L);
        pump.advance(0f);

        assertNull(pump.hudState(false).rttMillis());
    }

    @Test
    void linkStatusGoesOutEveryFiveSecondsWithWhatThisSideIsReceiving() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);

        pump.advance(0f);
        now.set(5_999L);
        pump.advance(0f);
        assertEquals(0, countOf(service, CoopMessages.Type.LINK_STATUS));

        service.noteUdpInboundAt(5_500L);
        now.set(6_001L);
        pump.advance(0f);

        CoopMessages.LinkStatus status = CoopMessages.parseLinkStatus(
                onlyOf(service, CoopMessages.Type.LINK_STATUS));
        assertEquals(CoopLinkQuality.TRANSPORT_UDP, status.transport());
        assertTrue(status.udpInboundOk(), "a datagram arrived 500 ms ago");
        assertEquals(-1, status.rttMillis(), "no pong has been matched yet");
        assertEquals(0, status.lossPercent());
    }

    @Test
    void anInboundLinkStatusIsStoredAsThePeersHalfOfTheEvidence() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);

        service.inbound.add(CoopMessages.linkStatus("session-a", 7L, 1_000L,
                new CoopLinkQuality.Snapshot(42, 60, 3, false, 120L, 11_000L),
                CoopLinkQuality.TRANSPORT_UDP,
                new CoopDatagramStats(0L, 1L, 2L, 0L, 0L, 0L, 3L, 0L, 0L, 4L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
        pump.advance(0f);

        CoopMessages.LinkStatus peer = pump.peerLinkStatus();
        assertNotNull(peer);
        assertEquals(42, peer.rttMillis());
        assertEquals(60, peer.p95RttMillis());
        assertEquals(3, peer.lossPercent());
        assertFalse(peer.udpInboundOk());
        assertEquals(1L, peer.droppedTokenMismatch());
        assertEquals(2L, peer.droppedForeignSource());
        assertEquals(3L, peer.pathValidations());
        assertEquals(4L, peer.icmpTransients());
    }

    /**
     * The fallback exists so a UDP-blocked network still plays. That is only true if a TCP-carried
     * datagram lands on the identical parse/token/watermark path a UDP one takes.
     */
    @Test
    void aStateDatagramCarriedOnTcpReachesTheSameWatermarkAUdpDatagramWould() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        String token = CoopMessages.wireToken("session-a");
        // The session-start edge resets the watermark table (syncNpcReplication); stream afterwards.
        pump.advance(0f);

        // One sender, because red-team A6 drops any datagram whose senderId is not the partner's
        // token whichever wire carried it. The two wires are therefore separated in time instead:
        // UDP raises the mark, then TCP raises the same mark further.
        String sender = CoopMessages.wireToken("guest-player");
        service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                CoopMessages.Type.NPC_FLEET_MOTION, 4L, 0L, EMPTY_MOTION_BODY));
        pump.advance(0f);

        assertEquals(4L, pump.datagramWatermark()
                .watermarkFor(sender, CoopMessages.Type.NPC_FLEET_MOTION),
                "the UDP datagram must reach the watermark");

        service.inbound.add(CoopMessages.stateDatagram("session-a", 8L, 1_000L,
                CoopMessages.datagram(token, sender,
                        CoopMessages.Type.NPC_FLEET_MOTION, 7L, 0L, EMPTY_MOTION_BODY)));
        pump.advance(0f);

        assertEquals(7L, pump.datagramWatermark()
                .watermarkFor(sender, CoopMessages.Type.NPC_FLEET_MOTION),
                "a TCP-carried datagram must reach the identical watermark");
    }

    @Test
    void aStateDatagramForAnotherSessionIsDroppedByTheSameTokenCheck() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        service.inbound.add(CoopMessages.stateDatagram("session-a", 8L, 1_000L,
                CoopMessages.datagram(CoopMessages.wireToken("someone-else"), "sender-x",
                        CoopMessages.Type.NPC_FLEET_MOTION, 7L, 0L, EMPTY_MOTION_BODY)));
        pump.advance(0f);

        assertEquals(Long.MIN_VALUE, pump.datagramWatermark()
                .watermarkFor("sender-x", CoopMessages.Type.NPC_FLEET_MOTION));
    }

    @Test
    void udpSilenceWithLiveTcpMovesTheStateStreamOntoTcpAtHalfCadence() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        assertEquals(100L, pump.stateStreamIntervalMillis());

        // TCP keeps arriving; no datagram ever does. That is a blocked path, not a peer in combat.
        for (long t = 2_000L; t <= 11_500L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            now.set(t);
            pump.advance(0f);
        }

        assertTrue(pump.stateStreamFallbackActive());
        assertEquals(200L, pump.stateStreamIntervalMillis(), "5 Hz while the stream rides TCP");
        assertEquals(CoopHudState.TRANSPORT_TCP_FALLBACK, pump.hudState(false).transport());

        // The sink now wraps instead of sending UDP.
        pump.sendStateDatagram(CoopMessages.datagram(CoopMessages.wireToken("session-a"), "host",
                CoopMessages.Type.FLEET_SNAPSHOT, 1L, 0L, "body"));
        assertEquals(0, service.datagrams.size(), "nothing may go out over the blocked path");
        assertEquals(1, countOf(service, CoopMessages.Type.STATE_DATAGRAM));
    }

    @Test
    void aPeerReportingNoInboundUdpAlsoMovesTheStreamOntoTcp() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        // This side's own UDP is healthy; only the peer's report says the path is one-way broken.
        service.noteUdpInboundAt(2_000L);
        service.inbound.add(CoopMessages.linkStatus("session-a", 7L, 2_000L,
                new CoopLinkQuality.Snapshot(40, 50, 0, false, 0L, 30_000L),
                CoopLinkQuality.TRANSPORT_UDP,
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
        now.set(2_500L);
        pump.advance(0f);

        assertTrue(pump.stateStreamFallbackActive());
    }

    @Test
    void udpComingBackReturnsTheStreamToUdpButHoldsTheFloorTierUntilTheCleanWindowCompletes() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        for (long t = 2_000L; t <= 11_500L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            now.set(t);
            pump.advance(0f);
        }
        assertTrue(pump.stateStreamFallbackActive());
        assertEquals(200L, pump.stateStreamIntervalMillis());

        for (long t = 12_500L; t <= 16_500L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            service.noteUdpInboundAt(t);
            now.set(t);
            pump.advance(0f);
        }
        assertTrue(pump.stateStreamFallbackActive(), "4 s of clear evidence is not yet enough");

        service.inbound.add(CoopMessages.ping("session-a", 900L, 17_600L));
        service.noteUdpInboundAt(17_600L);
        now.set(17_600L);
        pump.advance(0f);

        assertFalse(pump.stateStreamFallbackActive());
        // Phase 29 M2: the transport and the rate are now separate decisions. Datagrams go back on
        // the UDP wire the instant the path is believed, while the rate unpins into the ordinary
        // thirty-second clean window rather than jumping straight back to the default tier.
        assertEquals(200L, pump.stateStreamIntervalMillis(),
                "leaving the fallback unpins the floor into the clean window, not to default");
        service.sent.clear();
        pump.sendStateDatagram(CoopMessages.datagram(CoopMessages.wireToken("session-a"), "host",
                CoopMessages.Type.FLEET_SNAPSHOT, 1L, 0L, "body"));
        assertEquals(1, service.datagrams.size());
        assertEquals(0, countOf(service, CoopMessages.Type.STATE_DATAGRAM));

        for (long t = 18_600L; t <= 47_000L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            service.noteUdpInboundAt(t);
            now.set(t);
            pump.advance(0f);
        }
        assertEquals(200L, pump.stateStreamIntervalMillis(), "29 s clean is not yet 30");

        for (long t = 48_000L; t <= 49_000L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            service.noteUdpInboundAt(t);
            now.set(t);
            pump.advance(0f);
        }
        assertEquals(100L, pump.stateStreamIntervalMillis(),
                "thirty continuously clean seconds put the default tier back");
    }

    @Test
    void losingThePeerClearsTheFallbackAndTheMeasurements() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        for (long t = 2_000L; t <= 11_500L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            now.set(t);
            pump.advance(0f);
        }
        assertTrue(pump.stateStreamFallbackActive());

        service.connected = false;
        now.set(12_000L);
        pump.advance(0f);

        assertFalse(pump.stateStreamFallbackActive(),
                "the next connection's transport must not be decided by the dead one's silence");
        assertEquals(100L, pump.stateStreamIntervalMillis());
        assertNull(pump.peerLinkStatus());

        // Phase 20.2 holds the session open for the grace window, so the link readout only goes away
        // once the window closes and the session really ends.
        now.set(12_000L + 61_000L);
        pump.advance(0f);

        assertNull(pump.hudState(false).transport(), "no session, no link readout");
    }

    // ---- Phase 29 M2: adaptive state-stream cadence ---------------------------------------------

    /** Runs the 1 s supervision tick from {@code from} to {@code to}, keeping the link alive. */
    private static void tickSeconds(RecordingNetService service, CoopNetPump pump, AtomicLong now,
                                    long from, long to) {
        for (long t = from; t <= to; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 500_000L + t, t));
            service.noteUdpInboundAt(t);
            now.set(t);
            pump.advance(0f);
        }
    }

    private static CoopMessages.Message lastOf(RecordingNetService service, CoopMessages.Type type) {
        CoopMessages.Message last = null;
        for (CoopMessages.Message message : service.sent) {
            if (message.type() == type) {
                last = message;
            }
        }
        assertNotNull(last, "expected at least one " + type);
        return last;
    }

    @Test
    void aCleanHostSessionNeverLeavesTheDefaultTier() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        tickSeconds(service, pump, now, 2_000L, 120_000L);

        assertEquals(CoopCadenceTier.DEFAULT, pump.stateStreamTier());
        assertEquals(100L, pump.stateStreamIntervalMillis());
        assertEquals(100L, pump.npcMotionIntervalMillis());
        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, pump.stateStreamRedundancyDepth());
        assertEquals(200L, pump.interpolationDelayMillis(),
                "a clean link at the default tier lands on exactly the M1 value");
    }

    @Test
    void aHostTierChangeReachesBothCadencesAndShipsAnImmediateLinkStatus() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        tickSeconds(service, pump, now, 2_000L, 6_000L);
        service.sent.clear();

        // The socket stops draining: the transport is coalescing, so sending faster makes it worse.
        service.outboundDepth = 64;
        tickSeconds(service, pump, now, 7_000L, 7_000L);

        assertEquals(CoopCadenceTier.FLOOR, pump.stateStreamTier());
        assertEquals(200L, pump.stateStreamIntervalMillis());
        assertEquals(200L, pump.npcMotionIntervalMillis(), "both UDP state streams move together");
        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, pump.stateStreamRedundancyDepth(),
                "a backlog floor must not deepen redundancy");

        CoopMessages.Message status = lastOf(service, CoopMessages.Type.LINK_STATUS);
        assertEquals(5, CoopMessages.parseLinkStatus(status).cadenceHz(),
                "the change is announced now, not up to five seconds from now");
    }

    @Test
    void aLossDrivenFloorDeepensTheRedundancyAndRecoveryUndoesIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        // The peer reports 20% loss on what it receives from us; redundancy protects that direction.
        service.inbound.add(CoopMessages.linkStatus("session-a", 7L, 2_000L,
                new CoopLinkQuality.Snapshot(40, 50, 20, true, 0L, 30_000L),
                CoopLinkQuality.TRANSPORT_UDP, CoopCadenceTier.DEFAULT.hz(),
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
        service.outboundDepth = 64;
        tickSeconds(service, pump, now, 2_000L, 3_000L);
        service.outboundDepth = 0;

        assertEquals(CoopCadenceTier.FLOOR, pump.stateStreamTier());
        assertEquals(CoopDatagramRedundancy.MAX_DEPTH, pump.stateStreamRedundancyDepth());

        // The peer's report ages out; nothing measures loss any more, so the extra section goes away.
        tickSeconds(service, pump, now, 4_000L, 20_000L);

        assertEquals(CoopDatagramRedundancy.DEFAULT_DEPTH, pump.stateStreamRedundancyDepth());
    }

    @Test
    void theGuestAppliesTheTierTheHostAnnounces() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeGuestSession(), now::get);
        pump.advance(0f);
        assertEquals(100L, pump.stateStreamIntervalMillis());

        service.inbound.add(CoopMessages.linkStatus("session-a", 7L, 2_000L,
                new CoopLinkQuality.Snapshot(40, 50, 0, true, 0L, 30_000L),
                CoopLinkQuality.TRANSPORT_UDP, CoopCadenceTier.FLOOR.hz(),
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
        now.set(2_000L);
        pump.advance(0f);

        assertEquals(CoopCadenceTier.FLOOR, pump.peerCadenceTier());
        assertEquals(CoopCadenceTier.FLOOR, pump.stateStreamTier());
        assertEquals(200L, pump.stateStreamIntervalMillis());
        assertEquals(200L, pump.npcMotionIntervalMillis());
        assertEquals(400L, pump.interpolationDelayMillis(),
                "the delay is sized in the intervals the peer is actually sending at");

        // And back up when the host says so.
        service.inbound.add(CoopMessages.linkStatus("session-a", 8L, 3_000L,
                new CoopLinkQuality.Snapshot(40, 50, 0, true, 0L, 30_000L),
                CoopLinkQuality.TRANSPORT_UDP, CoopCadenceTier.DEFAULT.hz(),
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
        now.set(3_000L);
        pump.advance(0f);

        assertEquals(CoopCadenceTier.DEFAULT, pump.stateStreamTier());
        assertEquals(100L, pump.stateStreamIntervalMillis());
    }

    @Test
    void theGuestsOwnFallbackPinsTheFloorOverTheAnnouncedTier() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeGuestSession(), now::get);
        pump.advance(0f);

        // The host is fine and says so; this side is receiving no UDP at all.
        for (long t = 2_000L; t <= 11_500L; t += 1_000L) {
            service.inbound.add(CoopMessages.linkStatus("session-a", 100 + t, t,
                    new CoopLinkQuality.Snapshot(40, 50, 0, true, 0L, 30_000L),
                    CoopLinkQuality.TRANSPORT_UDP, CoopCadenceTier.DEFAULT.hz(),
                    new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
            now.set(t);
            pump.advance(0f);
        }

        assertTrue(pump.stateStreamFallbackActive());
        assertEquals(CoopCadenceTier.DEFAULT, pump.peerCadenceTier(), "the host still says 10 Hz");
        assertEquals(CoopCadenceTier.FLOOR, pump.stateStreamTier(),
                "a stream wrapped in TCP on this side is at the floor whatever the host believes");
        assertEquals(200L, pump.stateStreamIntervalMillis());
    }

    @Test
    void aSessionEdgePutsTheTierAndTheDelayBackToDefaultSilently() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        service.outboundDepth = 64;
        tickSeconds(service, pump, now, 2_000L, 3_000L);
        assertEquals(CoopCadenceTier.FLOOR, pump.stateStreamTier());

        service.connected = false;
        now.set(4_000L);
        pump.advance(0f);

        assertEquals(CoopCadenceTier.DEFAULT, pump.stateStreamTier(),
                "the next session must not inherit the dead link's floor");
        assertEquals(100L, pump.stateStreamIntervalMillis());
        assertEquals(100L, pump.npcMotionIntervalMillis());
        assertEquals(CoopCadenceTier.DEFAULT, pump.peerCadenceTier());
        assertEquals(200L, pump.interpolationDelayMillis());
    }

    @Test
    void everyTierChangeIsRecordedOnTheIntelPageEventLog() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        tickSeconds(service, pump, now, 2_000L, 3_000L);

        service.outboundDepth = 64;
        tickSeconds(service, pump, now, 4_000L, 4_000L);
        service.outboundDepth = 0;
        tickSeconds(service, pump, now, 5_000L, 40_000L);

        assertEquals(CoopCadenceTier.DEFAULT, pump.stateStreamTier());
        List<String> cadenceEvents = coop.ui.CoopSessionIntelFeed.currentModel().events().stream()
                .map(coop.ui.CoopSessionIntelModel.Event::line)
                .filter(line -> line.contains("state stream"))
                .toList();
        assertEquals(List.of(
                "Co-op: state stream 10 Hz - " + CoopCadenceController.REASON_CLEAN,
                "Co-op: state stream 5 Hz - " + CoopCadenceController.REASON_BACKLOG),
                cadenceEvents, "newest first: one line per tier change, with its reason");
    }

    /**
     * Feeds {@code count} FLEET_SNAPSHOT datagrams whose arrivals alternate between the two gaps,
     * starting at epoch {@code startEpoch} — epochs have to keep climbing across calls or the
     * watermark drops the datagrams and the jitter estimator never sees them.
     */
    private static long feedAlternatingArrivals(RecordingNetService service, CoopNetPump pump,
                                                AtomicLong now, long startAt, long startEpoch,
                                                int count, long shortGap, long longGap) {
        String token = CoopMessages.wireToken("session-a");
        String sender = CoopMessages.wireToken("guest-player");
        long at = startAt;
        long epoch = startEpoch;
        for (int i = 0; i < count; i++) {
            at += (i % 2 == 0) ? shortGap : longGap;
            service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                    CoopMessages.Type.FLEET_SNAPSHOT, epoch++, at, "x"));
            now.set(at);
            pump.advance(0f);
        }
        return at;
    }

    @Test
    void theDelayIsTwoIntervalsPlusJitterWithNoRoundingUpToWholeIntervals() {
        assertEquals(200L, CoopNetPump.interpolationDelayTargetMillis(100L, 0L),
                "a clean default tier is exactly the M1 constant");
        assertEquals(210L, CoopNetPump.interpolationDelayTargetMillis(100L, 10L),
                "the sigma a loopback session has by construction costs ten ms, not a whole interval");
        assertEquals(250L, CoopNetPump.interpolationDelayTargetMillis(100L, 50L));
        assertEquals(400L, CoopNetPump.interpolationDelayTargetMillis(200L, 0L),
                "a clean floor tier is two of the floor's intervals");
        assertEquals(410L, CoopNetPump.interpolationDelayTargetMillis(200L, 10L));
        assertEquals(150L, CoopNetPump.interpolationDelayTargetMillis(
                        CoopCadenceTier.TOP.intervalMillis(), 0L),
                "clamped up: below the timeline's minimum nothing buffers");
        assertEquals(500L, CoopNetPump.interpolationDelayTargetMillis(200L, 250L),
                "clamped down: past half a second the mirror is visibly behind");
    }

    @Test
    void theFirstDelayOfASessionAppliesAtOnceAndSigmaNoiseDoesNotMoveItAgain() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        assertEquals(200L, pump.interpolationDelayMillis());

        pump.applyInterpolationDelayTarget(1_000L, 210L);
        assertEquals(210L, pump.interpolationDelayMillis(),
                "the first measured value of a session applies immediately");

        // A sigma wandering between 8 and 11 ms is the estimator breathing, not a link that changed.
        pump.applyInterpolationDelayTarget(20_000L, 208L);
        assertEquals(210L, pump.interpolationDelayMillis());
        pump.applyInterpolationDelayTarget(30_000L, 211L);
        assertEquals(210L, pump.interpolationDelayMillis(), "no flap on either side of the applied value");
    }

    @Test
    void aDelayChangeNeedsBothATwentyFiveMillisecondStepAndTheFiveSecondHold() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        pump.applyInterpolationDelayTarget(1_000L, 210L);

        pump.applyInterpolationDelayTarget(4_000L, 236L);
        assertEquals(210L, pump.interpolationDelayMillis(), "big enough, but inside the five-second hold");

        pump.applyInterpolationDelayTarget(7_000L, 234L);
        assertEquals(210L, pump.interpolationDelayMillis(), "hold over, but a 24 ms step is estimator noise");

        pump.applyInterpolationDelayTarget(7_000L, 236L);
        assertEquals(236L, pump.interpolationDelayMillis(), "26 ms past the hold is a link that changed");
    }

    @Test
    void loopbackFrameJitterCostsMillisecondsNotAWholeInterval() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        // Sends ride ~16 ms campaign frames, so even a clean loopback session's FLEET_SNAPSHOT
        // arrivals wander about ten milliseconds either side of the 100 ms send interval. The first
        // M2 build rounded that up to a third whole interval and put a clean session at 300 ms.
        feedAlternatingArrivals(service, pump, now, 1_000L, 1L, 400, 90L, 110L);

        assertEquals(209L, pump.interpolationDelayMillis(),
                "a clean link stays within ten ms of the M1 200, not a whole interval above it");
    }

    @Test
    void aJitteryLinkWidensTheInterpolationDelayAndACalmOneNarrowsItBack() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        assertEquals(200L, pump.interpolationDelayMillis());

        // FLEET_SNAPSHOT datagrams from the partner arriving 50/150 ms apart: half a send interval
        // of measured jitter, which the formula pays for in milliseconds of extra buffer.
        long at = feedAlternatingArrivals(service, pump, now, 1_000L, 1L, 400, 50L, 150L);

        // 44, not the 50 the estimator converges on: the first value of a session applies on the
        // spot, part-way up the EMA's ramp, and the rest of the climb is inside one 25 ms step.
        assertEquals(244L, pump.interpolationDelayMillis(),
                "two intervals plus the measured sigma, no rounding up");

        // The stream goes perfectly regular; the estimator decays and the buffer is given back, in
        // steps no closer together than the hold.
        feedAlternatingArrivals(service, pump, now, at, 401L, 200, 100L, 100L);

        // Not all the way to 200: the last 18 ms is inside one step, and the deadband is the point.
        assertEquals(218L, pump.interpolationDelayMillis(),
                "a link that calmed down gets its buffer back to within one step of the clean value");
    }

    private static int countOf(RecordingNetService service, CoopMessages.Type type) {
        return (int) service.sent.stream().filter(m -> m.type() == type).count();
    }

    private static CoopMessages.Message onlyOf(RecordingNetService service, CoopMessages.Type type) {
        List<CoopMessages.Message> matches = service.sent.stream()
                .filter(m -> m.type() == type)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one " + type);
        return matches.get(0);
    }

    // ---- Phase 20.4: optional lobby password -----------------------------------------------------

    private static final String PASSWORD = "hunter2";

    private static CoopNetPump hostPumpWithPassword(RecordingNetService service,
                                                    CoopSessionState session, String password) {
        CoopNetPump pump = new CoopNetPump(service, session, () -> 8000L);
        pump.setLobbyPasswordForTest(password);
        return pump;
    }

    @Test
    void aHostWithNoPasswordAcceptsTheFirstHelloWithNoChallengeAtAll() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, "");

        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_CHALLENGE),
                "an unconfigured password must leave the lobby exchange byte-identical");
        assertEquals(CoopMessages.Type.LOBBY_ACCEPT, onlyOf(service, CoopMessages.Type.LOBBY_ACCEPT).type());
        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
    }

    @Test
    void aHostWithAPasswordChallengesTheFirstHelloWithoutTakingTheGuestSlot() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);

        pump.advance(0f);

        CoopMessages.Message challenge = onlyOf(service, CoopMessages.Type.LOBBY_CHALLENGE);
        assertEquals(16, CoopMessages.parseLobbyChallengeNonce(challenge).length(),
                "the nonce is 64 bits of hex");
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_ACCEPT));
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState(),
                "a guess must not be able to occupy the session while it guesses");
        assertNull(session.remotePlayerId());
    }

    @Test
    void aHostAcceptsTheSecondHelloCarryingTheCorrectProof() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);
        pump.advance(0f);
        String nonce = CoopMessages.parseLobbyChallengeNonce(onlyOf(service, CoopMessages.Type.LOBBY_CHALLENGE));

        service.inbound.add(CoopMessages.lobbyHello(2L, 7100L,
                new CoopPlayerInfo("guest-player", "Guest"),
                CoopMessages.passwordProof(PASSWORD, nonce)));
        pump.advance(0f);

        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
        assertEquals("guest-player", session.remotePlayerId());
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_REJECT));
        assertEquals(CoopMessages.Type.LOBBY_ACCEPT, onlyOf(service, CoopMessages.Type.LOBBY_ACCEPT).type());
    }

    @Test
    void aWrongProofIsRejectedAndTheNonceIsNotReusable() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);
        pump.advance(0f);
        String nonce = CoopMessages.parseLobbyChallengeNonce(onlyOf(service, CoopMessages.Type.LOBBY_CHALLENGE));

        service.inbound.add(CoopMessages.lobbyHello(2L, 7100L,
                new CoopPlayerInfo("guest-player", "Guest"),
                CoopMessages.passwordProof("wrong-password", nonce)));
        pump.advance(0f);

        CoopMessages.Message reject = onlyOf(service, CoopMessages.Type.LOBBY_REJECT);
        assertEquals("{\"reason\":\"password rejected\"}", reject.payloadJson());
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_ACCEPT));

        // Replaying the round the attacker just watched must not work: the nonce is consumed.
        service.inbound.add(CoopMessages.lobbyHello(3L, 7200L,
                new CoopPlayerInfo("guest-player", "Guest"),
                CoopMessages.passwordProof(PASSWORD, nonce)));
        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_ACCEPT),
                "a proof with no outstanding challenge is a replay, not a login");
        assertEquals(2, countOfType(service, CoopMessages.Type.LOBBY_REJECT));
    }

    @Test
    void aGuestAnswersAChallengeWithASecondHelloCarryingTheProof() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 9000L);
        pump.setLobbyPasswordForTest(PASSWORD);
        pump.advance(0f);

        service.inbound.add(CoopMessages.lobbyChallenge(5L, 9100L, "abcdef0123456789"));
        pump.advance(0f);

        List<CoopMessages.Message> hellos = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.LOBBY_HELLO)
                .toList();
        assertEquals(2, hellos.size(), "the challenge is answered with a second hello");
        assertEquals("", CoopMessages.parseLobbyProof(hellos.get(0)),
                "the first hello cannot carry a proof: no nonce exists yet");
        assertEquals(CoopMessages.passwordProof(PASSWORD, "abcdef0123456789"),
                CoopMessages.parseLobbyProof(hellos.get(1)));
    }

    @Test
    void aGuestWithNoPasswordAnswersAChallengeWithAnEmptyProofRatherThanGoingSilent() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 9000L);
        pump.setLobbyPasswordForTest("");
        pump.advance(0f);

        service.inbound.add(CoopMessages.lobbyChallenge(5L, 9100L, "abcdef0123456789"));
        pump.advance(0f);

        List<CoopMessages.Message> hellos = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.LOBBY_HELLO)
                .toList();
        assertEquals(2, hellos.size());
        assertEquals("", CoopMessages.parseLobbyProof(hellos.get(1)),
                "an empty proof earns the reject that tells the player why");
    }

    /**
     * The host end of the same case: an empty proof answering an outstanding challenge is a wrong
     * proof (net-1). Re-challenging it instead was an endless ping-pong - the passwordless guest
     * answers every challenge with another empty proof, one round per frame, until the handshake
     * deadline drops the socket and the retry loop starts it over - and the reject that names the
     * cause never fired.
     */
    @Test
    void anEmptyProofFromAPasswordlessGuestIsRejected() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.LOBBY_CHALLENGE),
                "the first hello cannot carry a proof, so it earns the one challenge");

        service.inbound.add(CoopMessages.lobbyHello(2L, 7100L,
                new CoopPlayerInfo("guest-player", "Guest"), ""));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.LOBBY_CHALLENGE),
                "the challenge is not re-issued to a peer that already failed to answer it");
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_ACCEPT));
        assertEquals(1, countOfType(service, CoopMessages.Type.LOBBY_REJECT));
        assertEquals(CoopNetPump.LOBBY_REJECT_PASSWORD, CoopMessages.requiredPayloadString(
                service.sent.stream()
                        .filter(m -> m.type() == CoopMessages.Type.LOBBY_REJECT)
                        .findFirst().orElseThrow(),
                "reason"));
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
    }

    /**
     * net-1, the other half: a fresh connection after that reject still gets its own first
     * challenge. The nonce is cleared on the disconnect edge, so the guard cannot strand a guest
     * that does have the password.
     */
    @Test
    void aNewConnectionAfterAFailedProofStillGetsItsOwnChallenge() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);
        pump.advance(0f);

        // The wrong-password guest goes away and a correctly configured one dials in.
        service.connected = false;
        pump.advance(0f);
        service.connected = true;
        service.inbound.add(CoopMessages.lobbyHello(3L, 7200L, new CoopPlayerInfo("guest-two", "Guest")));
        pump.advance(0f);

        assertEquals(2, countOfType(service, CoopMessages.Type.LOBBY_CHALLENGE));
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_REJECT));
    }

    // ---- F4/F5: what a guest does with a LOBBY_REJECT --------------------------------------------

    private static final String RETRYABLE_REJECT = "Lobby already has a guest";

    private static CoopNetPump guestPumpWithPassword(RecordingNetService service,
                                                     CoopSessionState session, String password) {
        CoopNetPump pump = new CoopNetPump(service, session, () -> 9000L);
        pump.setLobbyPasswordForTest(password);
        return pump;
    }

    private static CoopSessionState connectingGuestSession() {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        return session;
    }

    @Test
    void aPasswordRejectEndsTheConnectLoopAndSaysWhyOnTheHud() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = guestPumpWithPassword(service, session, "wrong-password");
        pump.advance(0f);

        service.inbound.add(CoopMessages.lobbyReject(6L, 9100L, CoopNetPump.LOBBY_REJECT_PASSWORD));
        pump.advance(0f);

        assertEquals(List.of(CoopNetPump.LOBBY_REJECT_PASSWORD), service.stopReconnectingReasons,
                "the password is a launch property; retrying can only feed the host's cooldown");
        assertEquals(0, service.lobbyRejectBackoffs, "a terminal reject schedules no retry at all");
        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertTrue(session.rejectTerminal());
        assertEquals(CoopHudState.STATUS_REJECTED_PREFIX + CoopNetPump.LOBBY_REJECT_PASSWORD_HELP,
                pump.hudState(false).status());
    }

    @Test
    void aTerminalPasswordRejectSurvivesTheDropTheHostSendsBehindIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = guestPumpWithPassword(service, session, "wrong-password");
        pump.advance(0f);
        service.inbound.add(CoopMessages.lobbyReject(6L, 9100L, CoopNetPump.LOBBY_REJECT_PASSWORD));
        pump.advance(0f);
        service.sent.clear();

        // The disconnect edge the closed socket produces must not rewind the explanation away.
        pump.advance(0f);
        pump.advance(0f);
        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertEquals(CoopHudState.STATUS_REJECTED_PREFIX + CoopNetPump.LOBBY_REJECT_PASSWORD_HELP,
                pump.hudState(false).status());

        // And a socket that somehow came back is not a second chance: no rearm, no hello.
        service.connected = true;
        pump.advance(0f);
        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_HELLO));
    }

    @Test
    void aRetryableRejectBacksTheLoopOffAndCarriesItsReasonToTheHud() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = guestPumpWithPassword(service, session, "");
        pump.advance(0f);

        service.inbound.add(CoopMessages.lobbyReject(6L, 9100L, RETRYABLE_REJECT));
        pump.advance(0f);

        assertEquals(1, service.lobbyRejectBackoffs, "the host said no; stop knocking twice a second");
        assertTrue(service.stopReconnectingReasons.isEmpty(),
                "the host's answer can change without a relaunch, so the loop must survive");
        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertFalse(session.rejectTerminal());
        assertEquals(CoopHudState.STATUS_REJECTED_PREFIX + RETRYABLE_REJECT,
                pump.hudState(false).status());
    }

    @Test
    void aPlainDropWithNoRejectDoesNotSlowTheRetryLoop() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = guestPumpWithPassword(service, session, "");
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);

        assertEquals(0, service.lobbyRejectBackoffs,
                "the 500 ms retry is what the 20.2 grace and the grace-expiry rejoin ride on");
        assertTrue(service.stopReconnectingReasons.isEmpty());
    }

    /**
     * F5 regression. The host writes {@code LOBBY_REJECT} and closes immediately behind it, so the
     * guest's transport reports the close on the same frame the message is queued — and the
     * disconnect edge runs before the inbound drain, so REJECTED is entered right after the only
     * edge that used to clear it. Pre-fix the next connection then sent nothing at all and sat on the
     * host's single lobby slot until the 15 s handshake deadline killed it.
     */
    @Test
    void aRejectThatLandsAfterTheDropEdgeStillLeavesTheNextConnectionTalking() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = guestPumpWithPassword(service, session, "");
        pump.advance(0f);

        service.connected = false;
        service.inbound.add(CoopMessages.lobbyReject(6L, 9100L, RETRYABLE_REJECT));
        pump.advance(0f);
        assertEquals(CoopLobbyState.REJECTED, session.connectionState(),
                "the reject is applied after the rewind, which is the whole trap");

        service.sent.clear();
        service.connected = true;
        pump.advance(0f);

        assertEquals(CoopLobbyState.GUEST_CONNECTING, session.connectionState());
        assertEquals(1, countOf(service, CoopMessages.Type.LOBBY_HELLO),
                "a new connection either opens a lobby round or is terminal — never silent");
        pump.advance(0f);
        pump.advance(0f);
        assertEquals(1, countOf(service, CoopMessages.Type.LOBBY_HELLO),
                "and the rearm fires once per connect edge, not once per frame");
    }

    /**
     * F5's other half: two hellos on one connection is the Phase 20.4 password round (the second
     * carries the proof), not a repeated lobby attempt. This pins that count so a future rearm bug
     * cannot hide inside it.
     */
    @Test
    void theOnlySecondHelloOnAConnectionIsTheProofAnswer() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = guestPumpWithPassword(service, session, PASSWORD);
        pump.advance(0f);
        pump.advance(0f);
        pump.advance(0f);
        assertEquals(1, countOf(service, CoopMessages.Type.LOBBY_HELLO),
                "one unsolicited hello per connection, however many frames run");

        service.inbound.add(CoopMessages.lobbyChallenge(5L, 9100L, "abcdef0123456789"));
        pump.advance(0f);
        pump.advance(0f);
        List<CoopMessages.Message> hellos = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.LOBBY_HELLO)
                .toList();
        assertEquals(2, hellos.size(), "exactly one answer to one challenge");
        assertEquals("", CoopMessages.parseLobbyProof(hellos.get(0)));
        assertEquals(CoopMessages.passwordProof(PASSWORD, "abcdef0123456789"),
                CoopMessages.parseLobbyProof(hellos.get(1)));

        // A rejected round, a drop, a reconnect: one fresh hello, not a second round on top.
        service.connected = false;
        service.inbound.add(CoopMessages.lobbyReject(6L, 9200L, RETRYABLE_REJECT));
        pump.advance(0f);
        service.connected = true;
        pump.advance(0f);
        pump.advance(0f);
        assertEquals(3, countOf(service, CoopMessages.Type.LOBBY_HELLO));
    }

    /** A reject arriving during the 20.2 grace window is the coordinator's business, not F4's. */
    @Test
    void aRejectDuringTheReconnectGraceLeavesTheWindowAndTheRetryRateAlone() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        assertTrue(pump.reconnectCoordinatorForTest().guestReconnecting());

        service.connected = true;
        service.inbound.add(CoopMessages.lobbyReject(7L, 1100L,
                CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE));
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().guestReconnecting(),
                "the host is holding the slot for us; the window is still the right answer");
        assertEquals(0, service.lobbyRejectBackoffs,
                "the resume knocking must not be slowed while the window is running");
        assertTrue(service.stopReconnectingReasons.isEmpty());
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_HELLO),
                "while reconnecting the guest owes a resume request, never a hello");
    }

    // ---- Phase 20.6: session intel feed ----------------------------------------------------------

    @Test
    void theIntelFeedIsPublishedOnTheLinkStatusCadence() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);

        pump.advance(0f);
        assertEquals(CoopConnectionRole.NONE,
                coop.ui.CoopSessionIntelFeed.currentModel().localRole(),
                "nothing is published before the first LINK_STATUS interval elapses");

        now.set(6_001L);
        pump.advance(0f);

        coop.ui.CoopSessionIntelModel model = coop.ui.CoopSessionIntelFeed.currentModel();
        assertEquals(CoopConnectionRole.HOST, model.localRole());
        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, model.sessionState());
        assertNotNull(model.localLink(), "the page's own reading comes from the same snapshot");
        assertEquals(1, model.history().size(), "one sample per interval, not one per frame");
    }

    @Test
    void anInboundLinkStatusReachesTheIntelFeedAsThePartnersReading() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);

        service.inbound.add(CoopMessages.linkStatus("session-a", 7L, 1_000L,
                new CoopLinkQuality.Snapshot(42, 60, 3, false, 120L, 11_000L),
                CoopLinkQuality.TRANSPORT_TCP_FALLBACK,
                new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "")));
        pump.advance(0f);

        coop.ui.CoopSessionIntelModel.LinkSample peer =
                coop.ui.CoopSessionIntelFeed.currentModel().peerLink();
        assertNotNull(peer);
        assertEquals(42, peer.rttMillis());
        assertEquals(3, peer.lossPercent());
        assertTrue(peer.onFallback(), "the partner reports it is on the TCP fallback");
    }

    @Test
    void everyFeedBannerIsAlsoRecordedAsAnIntelEvent() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        // Live TCP and no datagram ever: that is the UDP-blocked rule, and it posts a banner.
        for (long t = 2_000L; t <= 11_500L; t += 1_000L) {
            service.inbound.add(CoopMessages.ping("session-a", 100 + t, t));
            now.set(t);
            pump.advance(0f);
        }
        assertTrue(pump.stateStreamFallbackActive());

        List<coop.ui.CoopSessionIntelModel.Event> events =
                coop.ui.CoopSessionIntelFeed.currentModel().events();
        assertTrue(events.stream().anyMatch(e -> e.line().contains("UDP blocked")),
                "the transition posted a banner, so it must also be in the event log: " + events);
    }


    // ---- Phase 20 M4: roster split, chunked motion, oversize escalation ---------------------------

    private static coop.fleet.CoopFleetSnapshot playerSnapshot(String playerId, int ships) {
        java.util.List<coop.fleet.CoopFleetSnapshot.Member> members = new ArrayList<>();
        for (int i = 0; i < ships; i++) {
            members.add(new coop.fleet.CoopFleetSnapshot.Member("m" + i, "wolf", "wolf_Assault",
                    "Ship " + i, "Captain", 0.7f, 0.9f));
        }
        return coop.fleet.CoopFleetSnapshot.create(playerId, "Guest", "corvus", 10f, 20f, 1f, 2f,
                "player", true, new coop.fleet.CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f),
                members);
    }

    private static long rosterCount(RecordingNetService service) {
        return service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.FLEET_ROSTER).count();
    }

    @Test
    void theFleetRosterGoesOutOnceAndThenOnlyWhenTheShipsChange() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = livePump(service, activeHostSession(), () -> 1_000L);

        assertTrue(pump.maybeSendFleetRoster(playerSnapshot("host-player", 3)));
        assertFalse(pump.maybeSendFleetRoster(playerSnapshot("host-player", 3)),
                "an unchanged roster is 10 Hz of bytes nobody reads");
        assertTrue(pump.maybeSendFleetRoster(playerSnapshot("host-player", 4)));

        assertEquals(2L, rosterCount(service));
        CoopMessages.Message roster = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.FLEET_ROSTER)
                .reduce((a, b) -> b).orElseThrow();
        coop.fleet.CoopFleetRoster decoded =
                coop.fleet.CoopFleetRoster.decode(CoopMessages.parseFleetRoster(roster));
        assertEquals(4, decoded.members().size());
        assertEquals("wolf_Assault", decoded.members().get(0).variantId());
    }

    @Test
    void aSessionEdgeMakesThisSideOweTheRosterAgain() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = livePump(service, activeHostSession(), () -> 1_000L);
        pump.maybeSendFleetRoster(playerSnapshot("host-player", 3));
        assertEquals(16, pump.lastSentRosterHash().length());

        // The peer went away: whatever comes back has no roster, so the memory of having sent one
        // is exactly the thing that would leave its mirror empty.
        service.connected = false;
        pump.advance(0f);

        assertEquals("", pump.lastSentRosterHash());
        assertTrue(pump.maybeSendFleetRoster(playerSnapshot("host-player", 3)));
    }

    @Test
    void anInboundRosterIsCachedAndTheNextTickRidesIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = livePump(service, activeHostSession(), () -> 1_000L);
        pump.advance(0f);
        coop.fleet.CoopFleetSnapshot remote = playerSnapshot("guest-player", 2);

        service.inbound.add(CoopMessages.fleetRoster("session-a", 1L, 1_000L,
                coop.fleet.CoopFleetRoster.of(remote).encode()));
        service.inboundDatagrams.add(CoopMessages.datagram(CoopMessages.wireToken("session-a"),
                CoopMessages.wireToken("guest-player"), CoopMessages.Type.FLEET_SNAPSHOT,
                11L, 500L, coop.fleet.CoopFleetSnapshot.Tick.of(remote).encode()));
        pump.advance(0f);

        assertNotNull(pump.rosterCache().current());
        assertEquals(2, pump.rosterCache().current().members().size());
        assertTrue(pump.rosterCache().matches(remote.fleetHash16()));
        assertEquals(11L, pump.datagramWatermark()
                        .watermarkFor(CoopMessages.wireToken("guest-player"),
                                CoopMessages.Type.FLEET_SNAPSHOT),
                "the tick reached the apply path");
    }

    @Test
    void aTickFromSomeoneOtherThanTheRemotePlayerNeverReachesTheRosterCache() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);
        service.inbound.add(CoopMessages.fleetRoster("session-a", 1L, 1_000L,
                coop.fleet.CoopFleetRoster.of(playerSnapshot("guest-player", 2)).encode()));
        pump.advance(0f);

        // A tick naming a roster this side does not hold, from a sender that is not the coop peer.
        // If the sender check read the body instead of the validated envelope, the cache would enter
        // (and eventually log) its hold window on a stranger's packet.
        String foreign = CoopMessages.wireToken("someone-else");
        coop.fleet.CoopFleetSnapshot other = playerSnapshot("guest-player", 7);
        for (long at : new long[] {2_000L, 20_000L}) {
            now.set(at);
            service.inboundDatagrams.add(CoopMessages.datagram(CoopMessages.wireToken("session-a"),
                    foreign, CoopMessages.Type.FLEET_SNAPSHOT, 20L + at, at,
                    coop.fleet.CoopFleetSnapshot.Tick.of(other).encode()));
            pump.advance(0f);
        }

        assertTrue(pump.rosterCache().matches(playerSnapshot("guest-player", 2).fleetHash16()));
        assertFalse(pump.rosterCache().mismatchLogged());
    }

    @Test
    void bothSectionsOfAChunkedMotionDatagramDecodeEvenWhenTheFirstIsStale() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "guest-player", "session-a"));
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        CoopNetPump pump = livePump(service, session, () -> 1_000L);
        pump.advance(0f);
        String token = CoopMessages.wireToken("session-a");
        String sender = CoopMessages.wireToken("host-player");
        java.util.List<coop.fleet.CoopNpcFleetMotion> first = java.util.List.of(
                new coop.fleet.CoopNpcFleetMotion("npc-1", "corvus", 10f, 20f, 1f, 2f,
                        new coop.fleet.CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f)));
        java.util.List<coop.fleet.CoopNpcFleetMotion> second = java.util.List.of(
                new coop.fleet.CoopNpcFleetMotion("npc-1", "corvus", 30f, 40f, 1f, 2f,
                        new coop.fleet.CoopSensorSync.Profile(300f, 0f, 0f, 1f, 200f)));

        // Tick 1, chunk 0.
        service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                CoopMessages.Type.NPC_FLEET_MOTION, 5L, 500L, 0,
                coop.fleet.CoopNpcFleetMotion.encodeFullSection(first)));
        pump.advance(0f);
        // Tick 2, chunk 0: section 1 is the copy the guest already applied, section 2 is a delta that
        // only means anything relative to it.
        service.inboundDatagrams.add(coop.net.CoopDatagramRedundancy.composeWithBaseline(token,
                sender, CoopMessages.Type.NPC_FLEET_MOTION, 5L, 500L, 0,
                coop.fleet.CoopNpcFleetMotion.encodeFullSection(first),
                6L, 600L, coop.fleet.CoopNpcFleetMotion.encodeDeltaSection(second, first)));
        pump.advance(0f);

        assertEquals(6L, pump.datagramWatermark()
                        .watermarkFor(sender, CoopMessages.Type.NPC_FLEET_MOTION),
                "a decode failure on the stale section would have aborted before the watermark");
    }

    @Test
    void anOverBudgetDatagramIsEscalatedOntoTcpInsteadOfBeingFragmented() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = livePump(service, activeHostSession(), () -> 1_000L);
        String oversized = CoopMessages.datagram(CoopMessages.wireToken("session-a"),
                CoopMessages.wireToken("host-player"), CoopMessages.Type.FLEET_SNAPSHOT,
                1L, 100L, "x".repeat(CoopNetService.MAX_DATAGRAM_BYTES));

        pump.sendStateDatagram(oversized);

        assertEquals(0, service.datagrams.size(), "nothing above the budget may hit the UDP socket");
        assertEquals(1, service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.STATE_DATAGRAM).count());
        assertEquals(oversized, CoopMessages.parseStateDatagram(service.sent.stream()
                        .filter(m -> m.type() == CoopMessages.Type.STATE_DATAGRAM)
                        .findFirst().orElseThrow()),
                "the escalated bytes are the datagram verbatim, so the receiver runs one path");
    }

    @Test
    void aDatagramInsideTheBudgetStillTakesTheUdpPath() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = livePump(service, activeHostSession(), () -> 1_000L);
        String small = CoopMessages.datagram(CoopMessages.wireToken("session-a"),
                CoopMessages.wireToken("host-player"), CoopMessages.Type.FLEET_SNAPSHOT,
                1L, 100L, "small");

        pump.sendStateDatagram(small);

        assertEquals(List.of(small), service.datagrams);
        assertEquals(0, service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.STATE_DATAGRAM).count());
    }

    // ---- Phase 21: the lobby gate ----------------------------------------------------------------

    private static CoopSessionState lobbyHostSession() {
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        // Deliberately NOT released: this is a session sitting in its lobby.
        return session;
    }

    @Test
    void theHoldStaysWhileTheSessionIsActiveButTheLobbyIsNotReleased() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);

        assertTrue(sector.paused, "the lobby holds the world; that is the whole point of the gate");
        assertFalse(session.lobbyReleased());
        assertEquals(CoopHudState.STATUS_IN_LOBBY, pump.hudState(true).status());
        assertTrue(pump.lobbyDialogRequestedForTest());
    }

    @Test
    void theHostStartsTheSessionAfterTheCountdownAndTheReleaseFrameUnpauses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        // The guest reports itself ready.
        service.inbound.add(CoopMessages.readyState("session-a", 5L, 1000L, "SNAPSHOT_APPLIED", true));
        pump.advance(0f);
        assertTrue(pump.lobbyRosterForTest().allReady());
        assertTrue(sector.paused);

        pump.lobbyStartForTest();
        now.set(2000L);
        pump.advance(0f);
        assertTrue(pump.lobbyRosterForTest().countdownActive());
        assertTrue(sector.paused, "the countdown is a telegraph, not the release");

        now.set(1000L + coop.session.CoopLobbyRoster.COUNTDOWN_MILLIS);
        pump.advance(0f);

        assertTrue(session.lobbyReleased());
        assertFalse(sector.paused, "the frame that releases the lobby is the frame that unpauses");
        assertFalse(pump.pauseCoordinatorForBridge().hostPauseIntent(),
                "the lobby hold is coop's, never the host player's pause");
        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, pump.hudState(false).status());
        CoopMessages.Message released = lastOfType(service, CoopMessages.Type.LOBBY_STATUS);
        assertTrue(CoopMessages.parseLobbyStatus(released).released(),
                "the guest learns about the start from the roster it is already reading");
    }

    @Test
    void aGuestThatTakesItsReadyBackCancelsTheCountdown() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        service.inbound.add(CoopMessages.readyState("session-a", 5L, 1000L, "SNAPSHOT_APPLIED", true));
        pump.advance(0f);
        pump.lobbyStartForTest();
        assertTrue(pump.lobbyRosterForTest().countdownActive());

        service.inbound.add(CoopMessages.readyState("session-a", 6L, 1100L, "SNAPSHOT_APPLIED", false));
        now.set(1100L);
        pump.advance(0f);

        assertFalse(pump.lobbyRosterForTest().countdownActive(),
                "un-readying is also a cancel; the two are one press on the guest's side");
        assertFalse(pump.lobbyRosterForTest().allReady());

        now.set(1000L + coop.session.CoopLobbyRoster.COUNTDOWN_MILLIS + 1000L);
        pump.advance(0f);
        assertFalse(session.lobbyReleased(), "a cancelled countdown must never start the session");
        assertTrue(sector.paused);
    }

    @Test
    void startAnywayReleasesWithAGuestThatNeverReadied() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        pump.advance(0f);
        assertFalse(pump.lobbyRosterForTest().allReady());

        pump.lobbyStartAnywayForTest();
        now.set(1000L + coop.session.CoopLobbyRoster.COUNTDOWN_MILLIS);
        pump.advance(0f);

        assertTrue(session.lobbyReleased());
        assertFalse(sector.paused);
    }

    @Test
    void theHostBroadcastsTheRosterOnChangeAndAtLeastOnceASecond() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.LOBBY_STATUS));

        // A quiet second changes nothing and sends nothing.
        for (int i = 0; i < 30; i++) {
            now.addAndGet(16L);
            pump.advance(0f);
        }
        assertEquals(1, countOfType(service, CoopMessages.Type.LOBBY_STATUS),
                "an unchanged roster inside the interval costs no traffic");

        // Past the interval, one keepalive frame: never a static screen for more than a second.
        now.addAndGet(1_000L);
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.LOBBY_STATUS));

        // And a change goes out immediately rather than waiting for the timer.
        service.inbound.add(CoopMessages.readyState("session-a", 5L, now.get(), "SNAPSHOT_APPLIED", true));
        now.addAndGet(16L);
        pump.advance(0f);
        assertEquals(3, countOfType(service, CoopMessages.Type.LOBBY_STATUS));
        CoopMessages.LobbyStatus status = CoopMessages.parseLobbyStatus(
                lastOfType(service, CoopMessages.Type.LOBBY_STATUS));
        assertEquals(2, status.players().size());
        assertEquals("Host", status.players().get(0).name(), "host first, then join order");
        assertTrue(status.players().get(1).ready());
    }

    @Test
    void theGuestSendsOneReadyStatePerChangeAndMirrorsTheHostsRoster() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(true, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.READY_STATE));
        assertEquals("SEED_LOCKED",
                CoopMessages.parseReadyState(lastOfType(service, CoopMessages.Type.READY_STATE)).phase());
        assertTrue(pump.connectingDialogRequestedForTest(),
                "before the world arrives the guest is still on the connecting screen");

        // The host's world clock lands: that is SNAPSHOT_APPLIED, and the lobby replaces it.
        service.inbound.add(CoopMessages.timeSnapshot("session-a", 9L, true, false, 222333444L, 17L,
                now.get(), ""));
        now.addAndGet(16L);
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.READY_STATE));
        assertEquals("SNAPSHOT_APPLIED",
                CoopMessages.parseReadyState(lastOfType(service, CoopMessages.Type.READY_STATE)).phase());
        assertTrue(pump.lobbyDialogRequestedForTest());
        assertFalse(pump.connectingDialogRequestedForTest());

        // Nothing changed, so nothing is re-sent.
        now.addAndGet(16L);
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.READY_STATE));

        pump.lobbyReadyToggleForTest(true);
        now.addAndGet(16L);
        pump.advance(0f);
        assertEquals(3, countOfType(service, CoopMessages.Type.READY_STATE));
        assertTrue(CoopMessages.parseReadyState(
                lastOfType(service, CoopMessages.Type.READY_STATE)).ready());

        // The host's roster arrives and is taken wholesale.
        service.inbound.add(CoopMessages.lobbyStatus("session-a", 20L, now.get(), List.of(
                        new CoopMessages.LobbyPlayer("host-player", "Host", "READY", true, -1L, ""),
                        new CoopMessages.LobbyPlayer("guest-player", "Guest", "READY", true, -1L, "")),
                2_000L, false, "", 30_000L));
        now.addAndGet(16L);
        pump.advance(0f);
        assertEquals(2, pump.lobbyRosterForTest().size());
        assertTrue(pump.lobbyRosterForTest().countdownActive());
        assertFalse(session.lobbyReleased());

        // And the released flag is what closes the guest's lobby.
        service.inbound.add(CoopMessages.lobbyStatus("session-a", 21L, now.get(), List.of(
                        new CoopMessages.LobbyPlayer("host-player", "Host", "READY", true, -1L, ""),
                        new CoopMessages.LobbyPlayer("guest-player", "Guest", "READY", true, -1L, "")),
                -1L, true, "", 33_000L));
        now.addAndGet(16L);
        pump.advance(0f);
        assertTrue(session.lobbyReleased());
        assertFalse(pump.lobbyDialogRequestedForTest());
    }

    /**
     * The lobby's cancel option tells the guest "any player may cancel". A "start anyway" countdown
     * runs over a guest that never readied, so taking its ready back changed nothing the change
     * detection could see: the press cancelled the local mirror for one status interval and the host
     * released the session anyway.
     */
    @Test
    void theGuestsCancelDuringAStartAnywayCountdownIsSentToTheHost() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(true, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        service.inbound.add(CoopMessages.timeSnapshot("session-a", 9L, true, false, 222333444L, 17L,
                now.get(), ""));
        now.addAndGet(16L);
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.READY_STATE),
                "SEED_LOCKED then SNAPSHOT_APPLIED, and this guest never readies");

        // The host starts anyway: the countdown runs over a guest whose row says not ready.
        service.inbound.add(CoopMessages.lobbyStatus("session-a", 20L, now.get(), List.of(
                        new CoopMessages.LobbyPlayer("host-player", "Host", "READY", true, -1L, ""),
                        new CoopMessages.LobbyPlayer("guest-player", "Guest", "SNAPSHOT_APPLIED",
                                false, -1L, "")),
                3_000L, false, "", 30_000L));
        now.addAndGet(16L);
        pump.advance(0f);
        assertTrue(pump.lobbyRosterForTest().countdownActive());
        assertEquals(2, countOfType(service, CoopMessages.Type.READY_STATE));

        pump.lobbyCancelCountdownForTest();
        now.addAndGet(16L);
        pump.advance(0f);

        assertEquals(3, countOfType(service, CoopMessages.Type.READY_STATE),
                "the press has to leave the guest's machine or the host never cancels");
        CoopMessages.ReadyState sent = CoopMessages.parseReadyState(
                lastOfType(service, CoopMessages.Type.READY_STATE));
        assertFalse(sent.ready());
        assertEquals("SNAPSHOT_APPLIED", sent.phase());
        assertFalse(pump.lobbyRosterForTest().countdownActive(),
                "and the local mirror stops counting while the host's answer is on its way");
    }

    @Test
    void aGuestThatCancelsTheJoinStopsTheRetryLoopAndDisturbsNothingElse() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);
        assertTrue(pump.connectingDialogRequestedForTest());

        pump.connectingCancelForTest();
        pump.advance(0f);

        assertEquals(1, service.stopReconnectingReasons.size());
        assertFalse(pump.connectingDialogRequestedForTest(), "a cancelled join leaves no screen up");
        assertTrue(sector.paused, "and leaves the guest sitting on a held campaign");
    }

    @Test
    void aGuestWithNoLobbyAcceptInsideThirtySecondsIsToldItTimedOut() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        pump.advance(0f);
        now.addAndGet(coop.ui.CoopConnectingDialog.CONNECT_TIMEOUT_MILLIS);
        pump.advance(0f);

        assertTrue(pump.connectingDialogRequestedForTest(),
                "the screen stays up and names the cause rather than spinning forever");
    }

    @Test
    void aGuestThatDropsMidHandshakeLeavesNoRowOnTheHost() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        // Mid-handshake means exactly that: accepted into the lobby, versions not yet compared, so
        // the drop gets no reconnect grace and there is nothing to hold a row for. (The host records
        // its seed lock when it SENDS the request, not on the guest's ack, so anything past
        // hostAcceptHandshake is already a live session and gets a grace window instead.)
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        pump.advance(0f);
        assertEquals(2, pump.lobbyRosterForTest().size());
        assertEquals(coop.session.CoopJoinPhase.LINK_ESTABLISHED,
                pump.lobbyRosterForTest().row("guest-player").phase());

        service.connected = false;
        now.addAndGet(16L);
        pump.advance(0f);

        assertEquals(1, pump.lobbyRosterForTest().size(), "only the host is left");
        assertNull(pump.lobbyRosterForTest().row("guest-player"));
        assertFalse(pump.reconnectCoordinatorForTest().active());
    }

    @Test
    void aLobbyGateWithNoCampaignUiToShowItOnReleasesItselfRatherThanHangingTheWorld() {
        // Holding a world paused for a lobby that has no surface would be a hang with no way out.
        // In a loaded campaign the UI is always there, so this only ever fires headless.
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);

        assertFalse(session.lobbyReleased(),
                "one null-UI frame is a load frame, not a UI that is never coming");
        assertTrue(sector.paused, "and the gate keeps holding while it waits to find out");

        advancePastTheHeadlessLobbyValve(pump);

        assertTrue(session.lobbyReleased());
        assertFalse(sector.paused);
    }

    /**
     * Phase 21 red-team item 5: the headless "no campaign UI" release now needs a whole second of
     * consecutive null-UI frames, because a single one of those is what a load frame looks like. A
     * test with no UI has to run the pump across that valve before its session is playable.
     */
    private static void advancePastTheHeadlessLobbyValve(CoopNetPump pump) {
        for (int frame = 0; frame < 65; frame++) {
            pump.advance(0f);
        }
    }

    private static CoopMessages.Message lastOfType(RecordingNetService service, CoopMessages.Type type) {
        CoopMessages.Message found = null;
        for (CoopMessages.Message message : service.sent) {
            if (message.type() == type) {
                found = message;
            }
        }
        assertNotNull(found, "no " + type + " was sent");
        return found;
    }

    private static CoopNetPump livePump(RecordingNetService service, CoopSessionState session,
                                        java.util.function.LongSupplier clock) {
        return new CoopNetPump(service, session, clock::getAsLong,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host",
                () -> "coop-seed");
    }

    private static CoopSessionState hostSessionReadyForSeedLock(String sessionIdToMint) {
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", sessionIdToMint));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        return session;
    }

    private static CoopSessionState guestSessionReadyForSeedLock() {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        return session;
    }

    private static CoopNetPump pumpForHostSeedLock(RecordingNetService service, CoopSessionState session,
                                                   java.util.concurrent.atomic.AtomicReference<String> campaignIdStore) {
        return new CoopNetPump(service, session, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                new CoopTimeLock(),
                campaignIdStore::get, campaignIdStore::set, () -> false, () -> "", () -> false);
    }

    private static CoopNetPump pumpForGuestSeedLock(RecordingNetService service, CoopSessionState session,
                                                    java.util.concurrent.atomic.AtomicReference<String> campaignIdStore,
                                                    boolean adoptFlag, boolean priorCoopSession,
                                                    String guestFingerprint,
                                                    Supplier<String> canonicalSupplier) {
        return pumpForGuestSeedLock(service, session, campaignIdStore, adoptFlag, priorCoopSession,
                guestFingerprint, canonicalSupplier, () -> { });
    }

    /** net-fix-10: the same pump with a watchable consumer for the one-shot adopt consent. */
    private static CoopNetPump pumpForGuestSeedLock(RecordingNetService service, CoopSessionState session,
                                                    java.util.concurrent.atomic.AtomicReference<String> campaignIdStore,
                                                    boolean adoptFlag, boolean priorCoopSession,
                                                    String guestFingerprint,
                                                    Supplier<String> canonicalSupplier,
                                                    Runnable adoptConsentConsumer) {
        return new CoopNetPump(service, session, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> guestFingerprint,
                () -> "coop-seed",
                new CoopTimeLock(),
                campaignIdStore::get, campaignIdStore::set, () -> adoptFlag, canonicalSupplier,
                () -> priorCoopSession, adoptConsentConsumer);
    }

    private static CoopMessages.Message seedLockRequestIn(RecordingNetService service) {
        return service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.SEED_LOCK_REQUEST)
                .findFirst().orElseThrow();
    }

    @Test
    void guestPingsFlowDuringTheSeedLockWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        // handshakeValidated == true, seedLong == null: the window the host spends paused waiting
        // for seed lock, and exactly where a half-open connection used to go unnoticed because
        // pings were suppressed.
        assertTrue(session.handshakeValidated());
        assertNull(session.seedLong());

        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = new CoopNetPump(service, session, now::get,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-guest",
                () -> "coop-seed");

        pump.advance(0f);
        now.set(4001L);
        pump.advance(0f);

        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.PING),
                "pings must flow through the seed-lock window");
    }

    // ---- Phase 20.6: link HUD state ----------------------------------------------------------

    @Test
    void hudStateReportsNoSessionBeforeAnyLobby() {
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.NONE), () -> 1000L);

        CoopHudState state = pump.hudState(false);

        assertEquals(CoopHudState.BADGE_COOP, state.roleBadge());
        assertEquals(CoopHudState.STATUS_NO_SESSION, state.status());
        assertNull(state.pauseHolder());
        assertNull(state.clockDriftGameHours());
    }

    @Test
    void hudStateReportsHostWaitingForGuest() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(() -> "host-player");
        session.startHost("Host");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        CoopHudState state = pump.hudState(true);

        assertEquals(CoopHudState.BADGE_HOST, state.roleBadge());
        assertEquals(CoopHudState.STATUS_WAITING_FOR_GUEST, state.status());
        assertTrue(state.paused());
        // Pre-session the host is force-held paused by the pump, not by anyone's intent.
        assertNull(state.pauseHolder());
    }

    @Test
    void hudStateReportsGuestConnecting() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        assertEquals(CoopHudState.STATUS_CONNECTING, pump.hudState(false).status());
        assertEquals(CoopHudState.BADGE_GUEST, pump.hudState(false).roleBadge());
    }

    @Test
    void hudStateReportsHandshakingBeforeTheSeedLockLands() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        session.hostAcceptHandshake();
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        assertEquals(CoopHudState.STATUS_HANDSHAKING, pump.hudState(false).status());
    }

    @Test
    void hudStateReportsSessionActiveAndTheHostPauseHolder() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = activeHostPump(service, () -> 1000L);

        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, pump.hudState(false).status());
        assertNull(pump.hudState(false).pauseHolder());

        pump.pauseCoordinatorForBridge().setHostPauseIntent(true);
        // The host reading its own intent sees "you", not "host".
        assertEquals("you", pump.hudState(true).pauseHolder());
    }

    @Test
    void hudStatePauseHolderFollowsTheCoordinatorPrecedence() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = activeHostPump(service, () -> 1000L);

        pump.pauseCoordinatorForBridge().setEitherInCombat(true);
        assertEquals("combat", pump.hudState(true).pauseHolder());

        pump.pauseCoordinatorForBridge().applyGuestScreenPauseIntent(true, 1L);
        assertEquals("guest's screen", pump.hudState(true).pauseHolder());

        pump.pauseCoordinatorForBridge().applyGuestKeyPauseIntent(true, 2L);
        assertEquals("guest", pump.hudState(true).pauseHolder());

        pump.pauseCoordinatorForBridge().setHostPauseIntent(true);
        assertEquals("you", pump.hudState(true).pauseHolder());
    }

    @Test
    void hudStateAttributesTheGuestSidePauseToTheHostWithoutASnapshotHolder() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = activeGuestPump(service, () -> 1000L);

        assertNull(pump.hudState(false).pauseHolder());

        pump.pauseCoordinatorForBridge().setObservedPaused(true);
        assertEquals("host", pump.hudState(true).pauseHolder());
    }

    @Test
    void hudStateOnTheGuestNamesTheGuestsOwnPauseAsYou() {
        // Regression: the guest's own pause-key press used to read "paused by host", because the
        // guest deliberately does not store its own intent locally. The holder now rides the
        // host's TIME_SNAPSHOT.
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = activeGuestPump(service, () -> 1000L);

        service.inbound.add(CoopMessages.timeSnapshot("session-a", 7L,
                true, false, 222333444L, 17L, 900L, "guest"));
        pump.advance(0f);

        assertEquals("you", pump.hudState(true).pauseHolder());
    }

    @Test
    void hudStateOnTheGuestNamesTheGuestsScreenPauseAsYourScreen() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = activeGuestPump(service, () -> 1000L);

        service.inbound.add(CoopMessages.timeSnapshot("session-a", 7L,
                true, false, 222333444L, 17L, 900L, "guest screen"));
        pump.advance(0f);

        assertEquals("your screen", pump.hudState(true).pauseHolder());
    }

    @Test
    void hostShipsThePauseHolderInTheTimeSnapshot() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(true, false, 222333444L, 17L, 1200L, ""));
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = new CoopNetPump(service, session, now::get,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                timeLock);
        // The intent is applied after the headless valve, not before: while the lobby gate is still
        // holding the world the shared-pause state is deliberately parked and reset every frame.
        advancePastTheHeadlessLobbyValve(pump);
        pump.pauseCoordinatorForBridge().applyGuestKeyPauseIntent(true, 1L);
        now.set(5000L);
        pump.advance(0f);

        CoopMessages.Message snapshot = lastOfType(service, CoopMessages.Type.TIME_SNAPSHOT);
        assertEquals("guest", CoopMessages.requiredPayloadString(snapshot, "pausedBy"));
        assertEquals("guest",
                timeLock.capturedPauseHolders.get(timeLock.capturedPauseHolders.size() - 1));
    }

    @Test
    void hudStateReportsNoDriftWithoutClockSamples() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = activeGuestPump(service, () -> 1000L);

        // No TIME_SNAPSHOT has ever reached the reconciler, so there is nothing to claim.
        assertNull(pump.hudState(false).clockDriftGameHours());
    }

    @Test
    void hudStateNeverReportsDriftOnTheHost() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = activeHostPump(service, () -> 1000L);

        assertNull(pump.hudState(false).clockDriftGameHours());
    }

    @Test
    void hudStateDistinguishesAReconnectHoldFromAFirstConnect() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);
        pump.advance(0f);

        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, pump.hudState(false).status());

        service.connected = false;
        pump.advance(0f);

        // Phase 20.2: the drop opens a grace window rather than ending the session, and the HUD says
        // so with the same wording the 12b hold used.
        assertEquals(CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING, pump.hudState(true).status());
        assertEquals("reconnect", pump.hudState(true).pauseHolder(),
                "the session, not a player, is what is holding the clock");

        // Past the grace, the pre-20.2 teardown runs and the lobby rewinds to HOST_WAITING.
        // Red-team B7: the badge goes back to "waiting for guest" there, because nothing is being
        // held any more - it used to stay on "disconnected, holding" for the life of the process.
        now.set(1000L + 61_000L);
        pump.advance(0f);

        assertEquals(CoopHudState.STATUS_WAITING_FOR_GUEST, pump.hudState(true).status());
        assertNull(pump.hudState(true).pauseHolder(), "no session, so nobody owns the shared pause");
    }

    @Test
    void hudStateReportsReconnectingOnTheGuestAfterADrop() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump pump = activeGuestPump(service, () -> 1000L);
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);

        assertEquals(CoopHudState.STATUS_RECONNECTING, pump.hudState(false).status());
    }

    @Test
    void hudStateReportsARejectedHandshake() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.rejectHandshake("mod list mismatch");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        // Phase 21: a handshake reject stores its reason in handshakeRejectReason, not rejectReason,
        // so the HUD used to fall back to a bare "connection rejected" for exactly the failures that
        // need explaining. It now names the support code the dialog and the feed banner also carry.
        assertEquals(CoopHudState.STATUS_REJECTED_PREFIX + "COOP-MODS, mod mismatch",
                pump.hudState(false).status());
    }

    // ---- Phase 20.2: in-session reconnect grace ---------------------------------------------------

    @Test
    void aLiveSessionDropOpensTheGraceWindowInsteadOfTearingTheSessionDown() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);

        // The whole point: the session record survives the socket.
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());
        assertEquals("session-a", session.sessionId());
        assertEquals("guest-player", session.remotePlayerId());
        assertTrue(session.handshakeValidated());
        assertEquals(Long.valueOf(123456789L), session.seedLong());
        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
        // ...but no datagram stamped with its token is accepted while the window is open.
        assertNull(service.expectedTokens.get(service.expectedTokens.size() - 1));
        assertTrue(pump.pauseCoordinatorForBridge().reconnectHold());
        assertTrue(pump.pauseCoordinatorForBridge().effectivePaused());
    }

    @Test
    void aDropBeforeTheSessionIsLiveKeepsThePreGraceTeardown() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active(),
                "no handshake and no seed lock, so there is no session to hold");
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
        assertNull(session.sessionId());
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
    }

    @Test
    void aSessionThatNeverDropsNeverTouchesTheGraceMachinery() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);

        for (int frame = 0; frame < 200; frame++) {
            now.addAndGet(100L);
            pump.advance(0f);
        }

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, pump.hudState(false).status());
        assertEquals(0, countOf(service, CoopMessages.Type.SESSION_RESUME_REQUEST));
        assertEquals(0, countOf(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
    }

    @Test
    void aMatchingResumeRequestIsAcceptedRestoresTheTokenAndForcesARebroadcast() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        RecordingTimeLock timeLock = new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 5_000L, 3L, 1000L, ""));
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, timeLock);
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);
        service.sent.clear();

        service.connected = true;
        now.addAndGet(4_000L);
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 9L, now.get(), "guest-player"));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertEquals(1, countOf(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertEquals(0, countOf(service, CoopMessages.Type.SESSION_RESUME_REJECT));
        // The token is back, so the guest's datagrams are accepted again on the same session.
        assertEquals(CoopMessages.wireToken("session-a"),
                service.expectedTokens.get(service.expectedTokens.size() - 1));
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, pump.hudState(false).status());
        // The forced rebroadcast pulls the 5 Hz clock stream forward rather than making the
        // returning guest wait out the cadence with a frozen clock.
        assertTrue(countOf(service, CoopMessages.Type.TIME_SNAPSHOT) >= 1,
                "a resume must re-seed the guest's clock immediately");
    }

    @Test
    void aStrangerIsRejectedAndTheHostKeepsWaitingForTheRealPartner() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.sent.clear();

        service.connected = true;
        service.inbound.add(CoopMessages.sessionResumeRequest("session-b", 9L, 2000L, "guest-player"));
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 10L, 2000L, "someone-else"));
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "a stranger must not be able to end the wait early");
        assertEquals(2, countOf(service, CoopMessages.Type.SESSION_RESUME_REJECT));
        assertEquals(0, countOf(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_ACCEPT),
                "and no lobby round is opened behind the held session's back");
        assertEquals("guest-player", session.remotePlayerId(), "the slot still belongs to the partner");
    }

    @Test
    void campaignTrafficFromAnUnprovenPeerIsIgnoredForTheWholeGraceWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.sent.clear();

        // The session record is still live, so without the whitelist every campaign handler would
        // run for whoever happens to be on the far end of this socket.
        //
        // net-fix-5: the generation bump is what makes this a DIFFERENT socket. The real transport
        // cannot produce a reconnect without one - every attach increments the counter - and the
        // stamp it puts on inbound messages is now what tells the departing partner's last word apart
        // from the first thing a stranger says.
        service.connected = true;
        service.connectionGeneration++;
        service.inbound.add(CoopMessages.worldDelta("session-a", 5L, 2000L,
                "entity-1", "CONSUME", true, "", "guest-player"));
        pump.advance(0f);

        assertEquals(0, countOf(service, CoopMessages.Type.WORLD_DELTA),
                "an unproven peer's campaign traffic must not reach the replicator");

        // After a matching resume the same message is ordinary session traffic again.
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 6L, 2000L, "guest-player"));
        pump.advance(0f);
        service.inbound.add(CoopMessages.worldDelta("session-a", 7L, 2000L,
                "entity-1", "CONSUME", true, "", "guest-player"));
        pump.advance(0f);

        assertEquals(1, countOf(service, CoopMessages.Type.WORLD_DELTA));
    }

    /**
     * net-fix-5. The failure this closes: inbound messages carried no record of which socket produced
     * them, so the pre-drain labelled them by position — "everything drained before the drop edge came
     * from the partner". One {@code pollNetworkLocked} closes a stale link and accepts its replacement,
     * so that drain routinely mixes the dead connection's tail with the first frames of a socket that
     * has authenticated nothing, and tagged both proven. A {@code WORLD_DELTA} from the replacement
     * then bypassed the grace whitelist and mutated the host's campaign before any password or resume
     * proof.
     */
    @Test
    void aReplacementConnectionsTrafficIsIgnoredWhileTheDeadTailIsStillApplied() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.sent.clear();

        // The half-open link was closed and its replacement accepted inside one poll, so isConnected()
        // reads true either side of the edge and both sockets' frames sit in the same queue. Generation
        // 0 is the partner's dying connection; generation 1 is whoever just took the slot.
        service.connectionGeneration = 1L;
        service.inbound.add(CoopMessages.worldDelta("session-a", 5L, 2000L,
                "entity-predrop", "CONSUME", true, "", "guest-player"));
        service.inboundGenerations.add(0L);
        service.inbound.add(CoopMessages.worldDelta("session-a", 6L, 2000L,
                "entity-stranger", "CONSUME", true, "", "someone-else"));
        service.inboundGenerations.add(1L);
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "the replacement is a drop edge and opens the window");
        assertEquals(1L, pump.preDropMessagesAppliedForTest(),
                "exactly one of the two came off the connection that carried the session");
        List<CoopMessages.Message> echoed = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.WORLD_DELTA)
                .toList();
        assertEquals(1, echoed.size(), "only the dead connection's tail may be applied: " + echoed);
        assertTrue(echoed.get(0).payloadJson().contains("entity-predrop"),
                "the applied delta is the partner's last word, not the stranger's: "
                        + echoed.get(0).payloadJson());
    }

    /**
     * The same rule when the drop is an ordinary disconnect with no replacement behind it: the
     * generation does not move, the queued tail is still the proven connection's, and it is applied.
     * The guard against over-correcting the fix above.
     */
    @Test
    void aPlainDisconnectStillAppliesTheDeadConnectionsQueuedTail() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.sent.clear();

        service.inbound.add(CoopMessages.worldDelta("session-a", 5L, 2000L,
                "entity-predrop", "CONSUME", true, "", "guest-player"));
        service.connected = false;
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(), "the window opened");
        assertEquals(1L, pump.preDropMessagesAppliedForTest());
        assertEquals(1, countOf(service, CoopMessages.Type.WORLD_DELTA),
                "no socket replaced this one, so the tail in the queue is the partner's");
    }

    /**
     * net-fix-7: the transport reports which snapshot the queue cap threw away, and the pump forces
     * the producers that suppress unchanged content to send it again. Without this a dropped
     * {@code NPC_FLEET_SET} was permanent — {@code sendSetIfChanged} skips every send while the roster
     * hash is unchanged — and the guest kept rendering fleets the host no longer had.
     */
    @Test
    void aSnapshotTheQueueCapDiscardedIsForcedBackOntoTheWire() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);

        service.overflowDroppedSnapshotTypes.add(CoopMessages.Type.NPC_FLEET_SET);
        service.overflowDroppedSnapshotTypes.add(CoopMessages.Type.MISSION_POOL_SNAPSHOT);
        // TIME_SNAPSHOT goes out again every few frames on its own, so it must NOT force anything.
        service.overflowDroppedSnapshotTypes.add(CoopMessages.Type.TIME_SNAPSHOT);
        pump.advance(0f);

        assertEquals(java.util.Set.of(CoopMessages.Type.NPC_FLEET_SET,
                        CoopMessages.Type.MISSION_POOL_SNAPSHOT),
                pump.overflowSnapshotResendsForcedForTest(),
                "only the producers that suppress an unchanged hash owe a forced resend");
        assertTrue(service.overflowDroppedSnapshotTypes.isEmpty(),
                "the report is drained, so one drop cannot force a resend every frame after it");
    }

    @Test
    void graceExpiryOnTheHostRunsTheOrdinaryTeardownAndReleasesTheHold() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());

        now.set(1000L + 60_000L);
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
        assertNull(session.sessionId());
        assertNull(session.remotePlayerId());
        assertFalse(session.handshakeValidated());
        assertNull(session.seedLong());
    }

    /**
     * The Phase 21 live-smoke defect. The guest JVM was killed mid-countdown, relaunched, loaded its
     * co-op save and knocked every 20 s; the host answered "session in reconnect grace" every time
     * while its own player pressed "wait longer", so the wait outlived the thing it was waiting for.
     * A relaunched guest cannot resume - it holds neither the session id nor the player id a
     * SESSION_RESUME_REQUEST is matched on - so the hello IS the rejoin.
     */
    @Test
    void aRelaunchedPartnerEndsTheHostWaitOnItsFirstHelloAndGetsALobbyRound() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        CoopNetPump pump = hostInGraceWindow(service, session, now);
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());
        assertTrue(sector.paused, "the window holds the world");

        // A fresh process: a new player id, no session id, and nothing it can say but a hello.
        now.addAndGet(20_000L);
        service.inbound.add(CoopMessages.lobbyHello(1L, now.get(),
                new CoopPlayerInfo("guest-relaunched", "Guest")));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active(), "the knock ends the wait");
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_REJECT),
                "the partner is served on its first attempt, not told to retry forever");
        assertEquals(1, countOf(service, CoopMessages.Type.LOBBY_ACCEPT));
        // The held session is cleared by the same teardown the grace expiry runs, which is what
        // frees the guest slot this frame's fall-through needs.
        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());
        assertEquals("guest-relaunched", session.remotePlayerId());
        assertNull(session.sessionId(), "a fresh session id is minted at the next handshake");
        assertFalse(session.lobbyReleased());
        assertNotNull(pump.lobbyRosterForTest().row("guest-relaunched"),
                "the returning partner is in the lobby roster on the same frame");
        // Not a desync: no dialog, and no [COOP-DOCTOR] marker behind it.
        assertFalse(pump.desyncDialogRequestedForTest());
        assertNull(pump.desyncReasonForTest());
        // F2: the window's hold hands the clock straight over to the no-session hold, still owned
        // by coop, so the world stays still until the new session is actually released.
        assertTrue(sector.paused);
    }

    /** The same frame, in the log: one INFO line and nothing that reads as a failure. */
    @Test
    void theRelaunchRejoinSaysWhatHappenedWithoutCallingItASessionFailure() {
        LogCapture appender = LogCapture.attach(CoopNetPump.class);
        try {
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = activeHostSession();
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = hostInGraceWindow(service, session, now);

            now.addAndGet(20_000L);
            service.inbound.add(CoopMessages.lobbyHello(1L, now.get(),
                    new CoopPlayerInfo("guest-relaunched", "Guest")));
            pump.advance(0f);

            List<String> lines = appender.messages();
            assertEquals(1, lines.stream()
                            .filter(line -> line.startsWith("Coop reconnect wait ended: "
                                    + CoopReconnectCoordinator.REASON_PARTNER_RELAUNCHED))
                            .count(),
                    "one line, and it names the reason the wait stopped");
            assertTrue(lines.stream().anyMatch(line -> line.contains("were left on session session-a")),
                    "with the number the player was staring at and the id it belonged to");
            assertTrue(lines.stream().anyMatch(line -> line.contains("Coop lobby opened as HOST")),
                    "and the lobby it opened on the same frame");
            assertEquals(0, lines.stream()
                            .filter(line -> line.contains("closed without a resume")).count(),
                    "a partner walking back in is not a grace expiry");
        } finally {
            appender.detach();
        }
    }

    /**
     * The gate that keeps the relaunch rejoin from being a way in: the password round runs on a
     * mid-grace hello exactly as it does on a first connection, so only a peer that proves itself
     * can end the wait.
     */
    @Test
    void aWrongPasswordHelloDuringTheGraceIsRejectedAndTheWaitKeepsRunning() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);
        pump.setLobbyPasswordForTest(PASSWORD);

        service.inbound.add(CoopMessages.lobbyHello(1L, now.get(),
                new CoopPlayerInfo("guest-z", "Stranger")));
        pump.advance(0f);

        String nonce = CoopMessages.parseLobbyChallengeNonce(
                onlyOf(service, CoopMessages.Type.LOBBY_CHALLENGE));
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "an unproven hello is challenged, and a challenge is not an end to the wait");
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_ACCEPT));

        service.sent.clear();
        service.inbound.add(CoopMessages.lobbyHello(2L, now.get(),
                new CoopPlayerInfo("guest-z", "Stranger"),
                CoopMessages.passwordProof("wrong-password", nonce)));
        pump.advance(0f);

        assertTrue(onlyOf(service, CoopMessages.Type.LOBBY_REJECT).payloadJson()
                .contains(CoopNetPump.LOBBY_REJECT_PASSWORD));
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_ACCEPT));
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "the wait keeps running for the partner that is actually coming back");
        assertEquals("guest-player", session.remotePlayerId(), "the slot still belongs to the partner");
        assertEquals("session-a", session.sessionId(), "and the held session is untouched");
    }

    /**
     * Regression guard for the in-JVM path the relaunch rejoin sits next to: a guest whose process
     * survived still resumes the held session, and must not be handed a fresh lobby round instead.
     */
    @Test
    void aMatchingResumeStillResumesTheHeldSessionRatherThanOpeningALobbyRound() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);

        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 9L, now.get(),
                "guest-player"));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertEquals(1, countOf(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_ACCEPT));
        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_REJECT));
        // Resumed, not rejoined: the session id, the handshake and the released lobby all stand.
        assertEquals("session-a", session.sessionId());
        assertEquals("guest-player", session.remotePlayerId());
        assertTrue(session.handshakeValidated());
        assertTrue(session.lobbyReleased());
        assertEquals(CoopMessages.wireToken("session-a"),
                service.expectedTokens.get(service.expectedTokens.size() - 1));
    }

    /**
     * The guest's release line used to print a hardcoded {@code start anyway=false}. LOBBY_STATUS
     * does not carry the flag, so the only honest line is one without it.
     */
    @Test
    void theGuestsLobbyReleaseLineDoesNotInventAStartAnywayFlag() {
        LogCapture appender = LogCapture.attach(CoopNetPump.class);
        try {
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
            CoopSessionState session = new CoopSessionState(() -> "guest-player");
            session.startGuest("Guest");
            session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
            session.guestAcceptHandshake("session-a");
            session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
            RecordingCampaignUi ui = new RecordingCampaignUi(null);
            Global.setSector(new RecordingSector(false, ui).proxy());
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = livePump(service, session, now::get);
            pump.advance(0f);

            now.addAndGet(4_000L);
            service.inbound.add(CoopMessages.lobbyStatus("session-a", 21L, now.get(), List.of(
                            new CoopMessages.LobbyPlayer("host-player", "Host", "READY", true, -1L, ""),
                            new CoopMessages.LobbyPlayer("guest-player", "Guest", "READY", true, -1L, "")),
                    -1L, true, "", 4_000L));
            pump.advance(0f);

            assertTrue(session.lobbyReleased());
            List<String> released = appender.messages().stream()
                    .filter(line -> line.startsWith("Coop lobby released"))
                    .toList();
            assertEquals(List.of("Coop lobby released after 4 s"), released,
                    "no flag the guest cannot know");
        } finally {
            appender.detach();
        }
    }

    @Test
    void theEndSessionOptionClosesTheWindowImmediately() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);

        // Exactly what the dialog's one option runs.
        pump.reconnectCoordinatorForTest().end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
    }

    @Test
    void theGuestAsksToResumeInsteadOfStartingAFreshLobbyRound() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.sent.clear();

        service.connected = true;
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(0, countOf(service, CoopMessages.Type.LOBBY_HELLO),
                "the whole point is not re-running the lobby for a blip");
        CoopMessages.Message request = onlyOf(service, CoopMessages.Type.SESSION_RESUME_REQUEST);
        assertEquals("session-a", CoopMessages.parseResumeSessionId(request));
        assertEquals("guest-player", CoopMessages.parseResumePlayerId(request));
        assertEquals(CoopHudState.STATUS_RECONNECTING, pump.hudState(false).status());
        assertEquals("reconnect", pump.hudState(false).pauseHolder());
    }

    @Test
    void theGuestResumesOnAcceptAndRestoresItsDatagramToken() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);

        service.connected = true;
        pump.advance(0f);
        service.inbound.add(CoopMessages.sessionResumeAccept("session-a", 3L, 2000L));
        pump.advance(0f);
        advancePastTheHeadlessLobbyValve(pump);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertEquals(CoopMessages.wireToken("session-a"),
                service.expectedTokens.get(service.expectedTokens.size() - 1));
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        assertEquals(CoopHudState.STATUS_SESSION_ACTIVE, pump.hudState(false).status());
        assertEquals("session-a", pump.sessionStateForBridge().sessionId());
    }

    @Test
    void theGuestEndsTheSessionWhenTheHostRejectsTheResume() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        CoopSessionState session = pump.sessionStateForBridge();
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);

        service.connected = true;
        pump.advance(0f);
        service.inbound.add(CoopMessages.sessionResumeReject("session-a", 3L, 2000L,
                "session id does not match the held session"));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertEquals(CoopLobbyState.GUEST_CONNECTING, session.connectionState());
        assertNull(session.sessionId());
        // Back to ordinary lobby behaviour on the next frame.
        pump.advance(0f);
        assertEquals(1, countOf(service, CoopMessages.Type.LOBBY_HELLO));
    }

    @Test
    void anAcceptNamingADifferentSessionIsRefusedRatherThanAdopted() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        CoopSessionState session = pump.sessionStateForBridge();
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.connected = true;
        pump.advance(0f);

        service.inbound.add(CoopMessages.sessionResumeAccept("session-zzz", 3L, 2000L));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertNull(session.sessionId());
    }

    // ---- Phase 20 live QA: the coop pause hold (findings F2/F3) -----------------------------------

    @Test
    void aPauseTheHostSetItselfBeforeTheHoldSurvivesTheSessionGoingLive() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        // The player paused before hosting: coop never applied that pause and must not take it away.
        RecordingSector sector = new RecordingSector(true);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);
        assertTrue(sector.paused);

        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        advancePastTheHeadlessLobbyValve(pump);

        assertTrue(sector.paused, "coop must only release the pause it applied itself");
        assertTrue(pump.pauseCoordinatorForBridge().hostPauseIntent(),
                "a pause coop did not apply is the host's, and the guest is told so");
    }

    /**
     * The live QA sequence behind F2, end to end: a 95 s outage against a 60 s window, the host tears
     * down, the guest comes back through the ordinary lobby, and the new session went active with the
     * host still paused because the grace hold had been promoted to the host's own pause intent.
     */
    @Test
    void aHostThatOutlastsTheGraceReleasesItsOwnHoldWhenTheGuestRejoins() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a", "session-b"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        advancePastTheHeadlessLobbyValve(pump);
        assertFalse(sector.paused, "a live session runs");

        service.connected = false;
        pump.advance(0f);
        assertTrue(sector.paused, "the grace window holds the world");

        now.set(1000L + 60_000L);
        pump.advance(0f);
        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertTrue(sector.paused, "and the no-session hold takes the clock straight over");

        // The supported rejoin: a fresh lobby round on a new connection, new session id.
        service.connected = true;
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        advancePastTheHeadlessLobbyValve(pump);

        assertFalse(sector.paused, "F2: the rejoined session must not stay paused waiting for a key press");
        assertFalse(pump.pauseCoordinatorForBridge().hostPauseIntent());
        assertFalse(pump.pauseCoordinatorForBridge().effectivePaused());
    }

    @Test
    void aGuestWithNoSessionHoldsItsCampaignPausedInsteadOfRunningOnAlone() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        // Connecting: role taken, nothing handshaken. F3's guest ran 1.4 game-days ahead here.
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);

        assertTrue(sector.paused);
    }

    @Test
    void theGuestHoldNeverForcesPauseUnderAnOpenInteractionDialog() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        RecordingCampaignUi ui = new RecordingCampaignUi(new RecordingEntity("market-1", "Jangala"));
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);

        assertFalse(sector.paused,
                "vanilla owns the clock under a dialog; forcing pause there is the trade-tab freeze");

        // The moment the dialog closes the hold applies as usual.
        ui.target = null;
        pump.advance(0f);
        assertTrue(sector.paused);
    }

    @Test
    void theGuestStopsHoldingOnceItsSessionIsActive() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        assertTrue(sector.paused);

        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        advancePastTheHeadlessLobbyValve(pump);

        // The guest's clock is the host snapshot's to drive (RecordingTimeLock does not touch the
        // sector), so what the release has to prove is that the pump stops re-asserting the hold.
        sector.paused = false;
        pump.advance(0f);
        assertFalse(sector.paused, "F3: an active session is the host's to pause, not the hold's");
    }

    @Test
    void aGuestHoldSurvivesTheReconnectWindowAndIsReleasedOnResume() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());

        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        assertTrue(pump.reconnectCoordinatorForTest().guestReconnecting());
        assertTrue(pump.pauseCoordinatorForBridge().reconnectHold());

        service.connected = true;
        pump.advance(0f);
        service.inbound.add(CoopMessages.sessionResumeAccept("session-a", 3L, 2000L));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
        // Released: the resumed session's clock follows the host again, so nothing re-pauses locally.
        advancePastTheHeadlessLobbyValve(pump);
        sector.paused = false;
        pump.advance(0f);
        assertFalse(sector.paused);
    }

    // ---- Phase 20.2: link death -------------------------------------------------------------------

    @Test
    void fifteenSecondsOfTcpSilenceDropsTheConnectionDeliberately() {
        DroppingNetService service = new DroppingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);
        pump.advance(0f);

        for (long t = 2_000L; t <= 17_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }

        assertEquals(1, service.drops.size(), "one deliberate drop, not one per frame");
        assertTrue(service.drops.get(0).contains("tcpSilence="), service.drops.get(0));
        assertTrue(service.drops.get(0).contains("peerInCombat=false"), service.drops.get(0));
    }

    @Test
    void aPeerInCombatIsNeverDroppedForGoingQuiet() {
        DroppingNetService service = new DroppingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);
        pump.advance(0f);

        // The partner's last word before it went quiet: it is in a battle, so its campaign pump is
        // stopped. That is the state the exemption reads.
        service.inbound.add(CoopMessages.battleBegin("session-a", 7L, 1000L, "battle-1",
                "guest-a", "Corvus", "a pirate armada", "", CoopMessages.BattleKind.PLAYER));
        pump.advance(0f);

        // Bounded below the red-team B3 timeout: the exemption is real, but it is not eternal
        // (see b3ARemoteBattleWithNoStatusForThirtySecondsIsAgedOut).
        for (long t = 2_000L; t <= 25_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }

        assertEquals(List.of(), service.drops, "a battle stops the peer's pump, not the link");
    }

    @Test
    void aCoordinatedSaveCheckpointExemptsTheSilenceItCauses() {
        DroppingNetService service = new DroppingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        pump.advance(0f);

        // The host says it is saving, then both processes go quiet while the saves are written.
        service.inbound.add(CoopMessages.saveCheckpoint("session-a", 4L, 1000L, 1L, "host save"));
        pump.advance(0f);
        for (long t = 2_000L; t <= 50_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }

        assertEquals(List.of(), service.drops);

        // Past the exempt window the same silence is a verdict again.
        for (long t = 51_000L; t <= 70_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }
        assertEquals(1, service.drops.size());
    }

    @Test
    void aLocalStallDoesNotCountAsThePeerGoingSilent() {
        DroppingNetService service = new DroppingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);
        pump.advance(0f);

        // This process fought its own battle for 40 s: no frames ran, and the silence it comes back
        // to is its own doing.
        now.set(41_000L);
        pump.advance(0f);
        for (long t = 42_000L; t <= 55_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }

        assertEquals(List.of(), service.drops);
    }

    // ---- Phase 20.3: port mapper wiring -----------------------------------------------------------

    @Test
    void aHostStartCreatesTicksAndTearsDownThePortMapper() {
        String saved = System.getProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "27015");
        try {
            StartupNetService service = new StartupNetService();
            AtomicLong now = new AtomicLong(1_000_000L);
            CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), now::get);
            // Offline seam: the whole negotiation runs without a packet reaching the LAN.
            pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, now::get));

            pump.advance(0f);

            assertEquals(27015, service.hostPort);
            CoopPortMapper mapper = pump.portMapperForTest();
            assertNotNull(mapper, "a host start must create the mapper");
            assertEquals(27015, mapper.port());

            for (int frame = 0; frame < 500 && !mapper.result().finished(); frame++) {
                now.addAndGet(100L);
                pump.advance(0f);
            }

            assertTrue(mapper.result().finished(), "the pump's frame tick drives it to a verdict");
            assertSame(mapper, pump.portMapperForTest(), "the mapper is created once, not per frame");

            pump.shutdownPortMapper();
            assertNull(pump.portMapperForTest());
        } finally {
            restoreProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, saved);
        }
    }

    @Test
    void aGuestStartNeverMapsAnything() {
        String savedHost = System.getProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY);
        String savedPort = System.getProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, "127.0.0.1");
        System.setProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, "27015");
        try {
            StartupNetService service = new StartupNetService();
            AtomicLong now = new AtomicLong(1_000_000L);
            CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), now::get);
            pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, now::get));

            pump.advance(0f);

            // Star topology: only the host needs to be reachable.
            assertNull(pump.portMapperForTest());
            assertEquals(CoopConnectionRole.GUEST, service.role());
        } finally {
            restoreProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, savedHost);
            restoreProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, savedPort);
        }
    }

    // ---- Phase 31: game-version refusal ----------------------------------------------------------

    @Test
    void aRefusedGameVersionOpensNoPortAndCreatesNoMapper() {
        String saved = System.getProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "27015");
        coop.handshake.CoopGameVersionCheck.remember(
                coop.handshake.CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", false));
        try {
            StartupNetService service = new StartupNetService();
            AtomicLong now = new AtomicLong(1_000_000L);
            CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), now::get);
            pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, now::get));

            for (int frame = 0; frame < 10; frame++) {
                now.addAndGet(100L);
                pump.advance(0f);
            }

            assertEquals(0, service.hostPort, "no port may be opened on a refused install");
            assertEquals(CoopConnectionRole.NONE, service.role());
            assertNull(pump.portMapperForTest(), "and nothing may be asked of the router either");
        } finally {
            coop.handshake.CoopGameVersionCheck.forget();
            restoreProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, saved);
        }
    }

    @Test
    void aRefusedGameVersionNeverDialsTheHost() {
        String savedHost = System.getProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY);
        String savedPort = System.getProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, "127.0.0.1");
        System.setProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, "27015");
        coop.handshake.CoopGameVersionCheck.remember(
                coop.handshake.CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", false));
        try {
            StartupNetService service = new StartupNetService();
            AtomicLong now = new AtomicLong(1_000_000L);
            CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), now::get);
            pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, now::get));

            pump.advance(0f);

            assertEquals(CoopConnectionRole.NONE, service.role());
        } finally {
            coop.handshake.CoopGameVersionCheck.forget();
            restoreProperty(CoopNetStartupConfig.CONNECT_HOST_PROPERTY, savedHost);
            restoreProperty(CoopNetStartupConfig.CONNECT_PORT_PROPERTY, savedPort);
        }
    }

    @Test
    void anAllowedGameVersionMismatchStartsTheHostAnyway() {
        String saved = System.getProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "27015");
        // The developer flag: still a mismatch, still logged as one, but not a refusal.
        coop.handshake.CoopGameVersionCheck.remember(
                coop.handshake.CoopGameVersionCheck.check("0.98a-RC8", "0.99a-RC1", true));
        try {
            StartupNetService service = new StartupNetService();
            AtomicLong now = new AtomicLong(1_000_000L);
            CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), now::get);
            pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, now::get));

            pump.advance(0f);

            assertEquals(27015, service.hostPort);
            assertEquals(CoopConnectionRole.HOST, service.role());
        } finally {
            coop.handshake.CoopGameVersionCheck.forget();
            restoreProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, saved);
        }
    }

    /**
     * net-48: LOBBY_STATUS carries how long the host's lobby has been open and the guest used to
     * throw it away, starting its own counter at its first status frame. A slow handshake then had
     * the host reading 1:30 and the guest 0:00, with the two-minute AFK hint 90 seconds apart.
     */
    @Test
    void theGuestLobbyCounterIsRebasedOntoTheHostsElapsedTime() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        // Seed-locked but not released: the lobby is still on screen, which is what the counter is on.
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        AtomicLong now = new AtomicLong(500_000L);
        CoopNetPump pump = livePump(service, session, now::get);

        service.inbound.add(CoopMessages.lobbyStatus("session-a", 20L, now.get(), List.of(
                        new CoopMessages.LobbyPlayer("host-player", "Host", "READY", true, -1L, ""),
                        new CoopMessages.LobbyPlayer("guest-player", "Guest", "SNAPSHOT_APPLIED", false, -1L, "")),
                -1L, false, "", 90_000L));
        pump.advance(0f);

        assertEquals(90_000L, pump.lobbyRosterForTest().elapsedMillis(now.get()),
                "both screens read the same clock, so the AFK hint fires on both at once");
    }

    // ---- bug-hunt fixes: startup, identity and grace -------------------------------------------

    /**
     * net-12: the port was taken. That used to be reported as "Invalid coop networking JVM
     * properties" and nothing else - the host loaded into a campaign with no lobby, no pause hold and
     * no explanation, while the guest's connect loop hammered a closed port.
     */
    @Test
    void aHostPortThatCannotBeBoundIsNamedOnScreenInsteadOfBlamingTheProperties() {
        String saved = System.getProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "27015");
        try {
            StartupNetService service = new StartupNetService();
            service.startHostFailure = new IllegalStateException("Unable to start coop TCP host on port 27015");
            CoopSessionState session = new CoopSessionState();
            CoopNetPump pump = new CoopNetPump(service, session, () -> 1_000_000L);
            pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, () -> 1_000_000L));

            pump.advance(0f);

            assertEquals(CoopConnectionRole.NONE, session.role(), "no session may look started");
            assertTrue(pump.desyncDialogRequestedForTest(),
                    "the player has to be told the port would not open");
            assertTrue(pump.desyncReasonForTest().rawReason().contains("27015"),
                    pump.desyncReasonForTest().rawReason());
        } finally {
            restoreProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, saved);
        }
    }

    /** A genuinely unparseable property is still reported as one, and raises no dialog. */
    @Test
    void anUnparseablePortIsStillReportedAsABadProperty() {
        String saved = System.getProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "not-a-port");
        try {
            StartupNetService service = new StartupNetService();
            CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), () -> 1_000_000L);

            pump.advance(0f);

            assertEquals(CoopConnectionRole.NONE, service.role());
            assertFalse(pump.desyncDialogRequestedForTest());
        } finally {
            restoreProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, saved);
        }
    }

    /**
     * net-3: the same human must keep one player id across launches of the same save. A per-process
     * UUID gave the Phase 21 stats tally a fresh, zeroed column on every reload beside the old one,
     * which kept the counters and was never marked away.
     */
    @Test
    void theLocalPlayerIdIsStableAcrossLaunchesOfTheSameSave() {
        String saved = System.getProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY);
        System.setProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, "27015");
        try {
            java.util.concurrent.atomic.AtomicReference<String> stored =
                    new java.util.concurrent.atomic.AtomicReference<>("");

            StartupNetService first = new StartupNetService();
            CoopSessionState firstSession = new CoopSessionState();
            CoopNetPump firstPump = new CoopNetPump(first, firstSession, () -> 1_000_000L);
            firstPump.setLocalPlayerIdStoreForTest(stored::get, stored::set);
            firstPump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, () -> 1_000_000L));
            firstPump.advance(0f);

            String minted = firstSession.localPlayerId();
            assertFalse(minted == null || minted.isBlank());
            assertEquals(minted, stored.get(), "the first launch writes its id into the save");
            assertEquals(minted, first.localSenderId, "and it is the id the wire carries");

            // The save is reloaded: a brand-new pump and session state, same persistent data.
            StartupNetService second = new StartupNetService();
            CoopSessionState secondSession = new CoopSessionState();
            CoopNetPump secondPump = new CoopNetPump(second, secondSession, () -> 1_000_000L);
            secondPump.setLocalPlayerIdStoreForTest(stored::get, stored::set);
            secondPump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, () -> 1_000_000L));
            secondPump.advance(0f);

            assertEquals(minted, secondSession.localPlayerId(),
                    "a reload is the same player, not a second column in the stats tally");
            assertEquals(minted, second.localSenderId);
        } finally {
            restoreProperty(CoopNetStartupConfig.HOST_PORT_PROPERTY, saved);
        }
    }

    /**
     * net-10: sector memory is serialized into the save, and the flag reader runs every frame while
     * the role is NONE. A flag left standing turned a bind failure into an open/bind/close cycle at
     * frame rate and re-fired the join on every later load of that save.
     */
    @Test
    void memoryLaunchFlagsAreConsumedOnceEvenWhenTheHostPortWillNotBind() {
        java.util.Map<String, Object> memory = new java.util.HashMap<>();
        memory.put("coop.hostPort", 27015);
        StartupNetService service = new StartupNetService();
        service.startHostFailure = new IllegalStateException("Unable to start coop TCP host on port 27015");
        CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), () -> 1_000_000L);
        pump.setPortMapperFactory(port -> CoopPortMapper.startOffline(port, () -> 1_000_000L));

        pump.consumeMemoryFlags(fakeMemory(memory));
        pump.consumeMemoryFlags(fakeMemory(memory));

        assertTrue(memory.isEmpty(), "a consumed flag is gone from the save, not read again next frame");
        assertEquals(1, service.startHostAttempts, "a failed bind is not retried at frame rate");
    }

    @Test
    void memoryJoinFlagsAreClearedSoTheyCannotRedialOnALaterLoad() {
        java.util.Map<String, Object> memory = new java.util.HashMap<>();
        memory.put("coop.connectHost", "10.0.0.5");
        memory.put("coop.connectPort", 27015);
        StartupNetService service = new StartupNetService();
        CoopNetPump pump = new CoopNetPump(service, new CoopSessionState(), () -> 1_000_000L);

        pump.consumeMemoryFlags(fakeMemory(memory));

        assertEquals(CoopConnectionRole.GUEST, service.role());
        assertTrue(memory.isEmpty());
    }

    /** A {@link MemoryAPI} over a map; only the methods the flag reader uses do anything. */
    private static MemoryAPI fakeMemory(java.util.Map<String, Object> backing) {
        return (MemoryAPI) java.lang.reflect.Proxy.newProxyInstance(
                MemoryAPI.class.getClassLoader(), new Class<?>[]{MemoryAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "contains" -> backing.containsKey(args[0]);
                    case "get" -> backing.get(args[0]);
                    case "unset" -> {
                        backing.remove(args[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "fakeMemory";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * net-16 / ui-session-1: a retryable reject drained BEFORE the drop leaves REJECTED behind, and
     * the rewind out of it means guestRearmLobby - the only other place that cleared the reason -
     * never runs. The stale reason then read as a live HOST_REFUSED over a lobby that was open, so
     * the guest sat on the connecting screen with Cancel as its only option.
     */
    @Test
    void aRejectDrainedBeforeTheDropDoesNotHauntTheNextAcceptedLobbyRound() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = connectingGuestSession();
        CoopNetPump pump = new CoopNetPump(service, session, () -> 9000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-guest",
                () -> "coop-seed");
        pump.setLobbyPasswordForTest("");
        pump.advance(0f);

        // Frame N: the reject lands while the socket is still up.
        service.inbound.add(CoopMessages.lobbyReject(6L, 9100L, RETRYABLE_REJECT));
        pump.advance(0f);
        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
        assertEquals(coop.ui.CoopConnectingDialog.Failure.HOST_REFUSED,
                pump.connectingViewForTest().failure());

        // Frame N+k: the host's handshake deadline closes the link.
        service.connected = false;
        pump.advance(0f);
        assertEquals(CoopLobbyState.GUEST_CONNECTING, session.connectionState());
        assertNull(session.rejectReason(), "the reason died with the connection that carried it");

        // The next round is accepted.
        service.connected = true;
        pump.advance(0f);
        service.inbound.add(CoopMessages.lobbyAccept(
                7L, 9200L, "lobby-a", new CoopPlayerInfo("host-player", "Host")));
        pump.advance(0f);

        assertEquals(CoopLobbyState.GUEST_CONNECTED, session.connectionState());
        assertNull(session.rejectReason());
        assertNull(pump.connectingViewForTest().failure(),
                "an admitted guest is not looking at a host-refused screen");
    }

    /**
     * net-17 / save-seed-handshake-3: the guest's CoopSaveCheckpoint lives for the life of the pump
     * while the host's is rebuilt on every game load and numbers from 1 again. Without a reset on the
     * session edge, a new session's first SAVE_CHECKPOINT read as "already handled" and the guest
     * quietly skipped the coordinated autosave that keeps the two saves in step.
     */
    @Test
    void aNewSessionGivesTheGuestACleanCheckpointHistory() {
        java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>("campaign-host");
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        CoopNetPump pump = pumpForGuestSeedLock(
                service, session, stored, false, false, "fingerprint-host", () -> "");

        coop.save.CoopSaveCheckpoint checkpoint = pump.saveCheckpointForTest();
        checkpoint.onCheckpointReceived(1L, "host save", 1000L);
        assertTrue(checkpoint.isAutosavePending());
        checkpoint.cancel();

        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host", "campaign-host", false));
        pump.advance(0f);
        assertEquals(Long.valueOf(123456789L), session.seedLong(), "the session really was established");

        checkpoint.onCheckpointReceived(1L, "host save", 2000L);
        assertTrue(checkpoint.isAutosavePending(),
                "the new host's checkpoint 1 is not the previous session's checkpoint 1");
    }

    /**
     * net-19: the host could not build its seed lock. It used to reject locally and go silent - and
     * because the accepted handshake had already set the expected session token, no deadline applied,
     * so the guest waited for a request that would never come on a socket nothing would ever drop.
     */
    @Test
    void aHostSeedLockThatCannotBeBuiltTellsTheGuestAndClosesTheConnection() {
        DroppingNetService service = new DroppingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = hostSessionReadyForSeedLock("session-a");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> {
                    throw new IllegalStateException("sector fingerprint unavailable");
                },
                () -> "fingerprint-host",
                () -> "coop-seed",
                new CoopTimeLock(),
                () -> "", id -> { }, () -> false, () -> "", () -> false);

        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.SEED_LOCK_REJECT),
                "every other reject site tells the peer; this one used to be the exception");
        assertFalse(service.drops.isEmpty(), "and the socket goes, so the deadline rules apply again");
        assertEquals(CoopLobbyState.REJECTED, session.connectionState());
    }

    /**
     * net-45: SEED_LOCK_REJECT was the one seed type with no state gate, so any peer that reached the
     * port could flip a waiting host to REJECTED and raise a modal dialog about a session that never
     * existed - while holding the single v1 lobby slot.
     */
    @Test
    void aSeedLockRejectBeforeAnyHandshakeIsIgnored() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L);

        service.inbound.add(CoopMessages.seedLockReject("session-x", 1L, 1000L, "sectorFingerprint: nope"));
        pump.advance(0f);

        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
        assertFalse(pump.desyncDialogRequestedForTest());
    }

    /**
     * net-46: the outbound twin of the grace-window inbound whitelist. During a grace window the
     * session record is live but the socket in the freed peer slot has proved nothing, and the host
     * used to hand it the campaign's options policy and a session-stamped TIME_SNAPSHOT unasked.
     */
    @Test
    void nothingSessionShapedLeavesTheHostWhileTheGraceWindowIsOpen() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());

        // A stranger dials the freed slot.
        service.sent.clear();
        service.connected = true;
        for (int frame = 0; frame < 4; frame++) {
            now.addAndGet(1_000L);
            pump.advance(0f);
        }

        assertEquals(0, countOfType(service, CoopMessages.Type.OPTIONS_SNAPSHOT));
        assertEquals(0, countOfType(service, CoopMessages.Type.TIME_SNAPSHOT));
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_STATUS));
        assertEquals(0, countOfType(service, CoopMessages.Type.SESSION_STATS));
    }

    private static void restoreProperty(String key, String saved) {
        if (saved == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, saved);
        }
    }

    /** A transport that records deliberate drops instead of owning a socket. */
    private static final class DroppingNetService extends RecordingNetService {
        private final List<String> drops = new ArrayList<>();

        private DroppingNetService(CoopConnectionRole role) {
            super(role);
        }

        @Override
        public boolean dropActiveConnection(String reason) {
            drops.add(reason == null ? "" : reason);
            connected = false;
            return true;
        }
    }

    /** Minimal transport for the startup paths: records what they asked for, opens nothing. */
    private static class StartupNetService extends CoopNetService {
        private CoopConnectionRole role = CoopConnectionRole.NONE;
        private int hostPort;
        private int startHostAttempts;
        private String localSenderId;
        private RuntimeException startHostFailure;

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public void setLocalSenderId(String senderId) {
            localSenderId = senderId;
        }

        @Override
        public void startHost(int port) {
            startHostAttempts++;
            if (startHostFailure != null) {
                throw startHostFailure;
            }
            hostPort = port;
            role = CoopConnectionRole.HOST;
        }

        @Override
        public void connect(String host, int port) {
            role = CoopConnectionRole.GUEST;
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public void flushOutbound() {
        }

        @Override
        public Inbound pollInboundEntry() {
            return null;
        }

        @Override
        public String pollDatagram() {
            return null;
        }

        @Override
        public void send(CoopMessages.Message message) {
        }

        @Override
        public void setExpectedSessionToken(String token) {
        }
    }

    private static CoopNetPump activeHostPump(RecordingNetService service, java.util.function.LongSupplier clock) {
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a", "session-b"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        // Phase 21: a session already in play, so the lobby gate is open.
        session.releaseLobby();
        return new CoopNetPump(service, session, clock,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host",
                () -> "coop-seed");
    }

    private static CoopNetPump activeGuestPump(RecordingNetService service, java.util.function.LongSupplier clock) {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        return new CoopNetPump(service, session, clock,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed");
    }

    @Test
    void localPlayerNameFallsBackToTheRoleLiteralWithoutACharacterName() {
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.HOST), () -> 1000L);
        pump.setCharacterNameSupplier(() -> "");

        withoutPlayerNameProperty(() -> {
            assertEquals("Host", pump.localPlayerName(CoopConnectionRole.HOST));
            assertEquals("Guest", pump.localPlayerName(CoopConnectionRole.GUEST));
        });
    }

    @Test
    void localPlayerNameUsesTheLocalCharacterName() {
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.GUEST), () -> 1000L);
        pump.setCharacterNameSupplier(() -> "  Ayo Keito  ");

        withoutPlayerNameProperty(() ->
                assertEquals("Ayo Keito", pump.localPlayerName(CoopConnectionRole.GUEST)));
    }

    @Test
    void playerNamePropertyBeatsTheCharacterName() {
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.GUEST), () -> 1000L);
        pump.setCharacterNameSupplier(() -> "Ayo Keito");

        String previous = System.getProperty(PLAYER_NAME_PROPERTY);
        System.setProperty(PLAYER_NAME_PROPERTY, "  Override  ");
        try {
            assertEquals("Override", pump.localPlayerName(CoopConnectionRole.GUEST));
        } finally {
            restorePlayerNameProperty(previous);
        }
    }

    @Test
    void productionCharacterNameLookupSurvivesAMissingSector() {
        // Default supplier reads Global.getSector().getPlayerPerson(); with no sector it must fall
        // through to the role literal rather than throw.
        Global.setSector(null);
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.HOST), () -> 1000L);

        withoutPlayerNameProperty(() -> assertEquals("Host", pump.localPlayerName(CoopConnectionRole.HOST)));
    }

    @Test
    void productionCharacterNameLookupSurvivesASectorThatCannotAnswer() {
        // RecordingSector's proxy throws for getPlayerPerson, which stands in for any engine failure.
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = new CoopNetPump(new RecordingNetService(CoopConnectionRole.GUEST), () -> 1000L);

        withoutPlayerNameProperty(() -> assertEquals("Guest", pump.localPlayerName(CoopConnectionRole.GUEST)));
    }

    private static final String PLAYER_NAME_PROPERTY = CoopOptionsRegistry.PLAYER_NAME;

    private static void withoutPlayerNameProperty(Runnable body) {
        String previous = System.getProperty(PLAYER_NAME_PROPERTY);
        System.clearProperty(PLAYER_NAME_PROPERTY);
        try {
            body.run();
        } finally {
            restorePlayerNameProperty(previous);
        }
    }

    private static void restorePlayerNameProperty(String previous) {
        if (previous == null) {
            System.clearProperty(PLAYER_NAME_PROPERTY);
        } else {
            System.setProperty(PLAYER_NAME_PROPERTY, previous);
        }
    }

    // ---- Phase 20.1: the transport's session token follows the session -------------------------

    /**
     * The transport drops every datagram until it knows this session's token, so the handshake accept
     * — the instant the session id exists — is the only correct moment to hand it over.
     */
    @Test
    void hostHandshakeAcceptGivesTheTransportTheSessionToken() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopHandshakeManifest manifest = emptyManifest("0.98a-RC8", "commit-a");
        service.inbound.add(CoopMessages.handshakeManifest(2L, 9000L, manifest, false));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 10000L, () -> manifest, () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host");

        pump.advance(0f);

        assertEquals(List.of(CoopMessages.wireToken("session-a")), service.expectedTokens);
    }

    @Test
    void guestHandshakeAcceptGivesTheTransportTheSameSessionToken() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        service.inbound.add(CoopMessages.handshakeResultAccept(3L, 11000L, "session-a"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 12000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false);

        pump.advance(0f);

        // Both ends derive the token from the same session id, so the datagram filter matches.
        assertEquals(List.of(CoopMessages.wireToken("session-a")), service.expectedTokens);
    }

    /**
     * The session id dies with the connection. Leaving its token armed would let a reconnecting peer's
     * stale in-flight datagrams apply to the next session.
     */
    @Test
    void peerDisconnectClearsTheTransportSessionToken() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a", "session-b"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(123456789L, "coop-seed", "fingerprint-host"),
                () -> "fingerprint-host",
                () -> "coop-seed");
        pump.advance(0f);

        service.connected = false;
        pump.advance(0f);

        assertEquals(1, service.expectedTokens.size());
        assertNull(service.expectedTokens.get(0), "the token must be cleared, not left armed");
    }

    private static final class RecordingSector {
        private boolean paused;
        private final RecordingCampaignUi campaignUi;
        private final RecordingFleet playerFleet;
        /** Phase 28: where the campaign's option policy is stored, beside coop.campaignId. */
        private final java.util.Map<String, Object> persistentData = new java.util.LinkedHashMap<>();

        private RecordingSector(boolean paused) {
            this(paused, null, null);
        }

        private RecordingSector(boolean paused, RecordingCampaignUi campaignUi) {
            this(paused, campaignUi, null);
        }

        private RecordingSector(boolean paused, RecordingCampaignUi campaignUi, RecordingFleet playerFleet) {
            this.paused = paused;
            this.campaignUi = campaignUi;
            this.playerFleet = playerFleet;
        }

        private SectorAPI proxy() {
            return (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(),
                    new Class<?>[]{SectorAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        switch (method.getName()) {
                            case "isPaused" -> {
                                return paused;
                            }
                            case "setPaused" -> {
                                paused = (boolean) args[0];
                                return null;
                            }
                            case "getCampaignUI" -> {
                                return campaignUi == null ? null : campaignUi.proxy();
                            }
                            case "getPlayerFleet" -> {
                                return playerFleet == null ? null : playerFleet.proxy();
                            }
                            // Phase 21 red-team item 13 re-registers the intel pages off the stats
                            // tick; no manager is the headless answer and the pump has to take it.
                            case "getIntelManager", "getClock" -> {
                                return null;
                            }
                            case "getPersistentData" -> {
                                return persistentData;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    private static final class RecordingCampaignUi {
        private RecordingEntity target;
        private boolean showingMenu;
        /** Phase 28: a vanilla core tab (map/fleet/cargo/...) being open, or null for none. */
        private Object coreTab;
        private int disallowInteractionCount;
        private int dismissCount;
        /** Every {@code showCoreUITab} the pump asked for, in order. */
        private final List<Object> coreTabSwitches = new ArrayList<>();
        /**
         * Phase 18: whether a {@code dismiss()} takes the dialog off screen. True models the engine
         * closing it; false models the frame(s) where the dialog is still up, which is what the
         * forced close has to keep re-asserting against.
         */
        private boolean dismissClosesDialog = true;
        private final List<String> messages = new ArrayList<>();
        /** Phase 21: every plugin handed to {@code showInteractionDialog}. */
        private final List<Object> shownDialogs = new ArrayList<>();
        /** False models another dialog holding the exclusive slot. */
        private boolean acceptInteractionDialogs = true;
        /** True models a campaign UI that throws out of the show call on every frame. */
        private boolean throwOnShowInteractionDialog;

        private RecordingCampaignUi(RecordingEntity target) {
            this.target = target;
        }

        private CampaignUIAPI proxy() {
            return (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[]{CampaignUIAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        switch (method.getName()) {
                            case "getCurrentInteractionDialog" -> {
                                return target == null ? null : interactionDialogProxy(target);
                            }
                            case "isShowingDialog" -> {
                                return target != null;
                            }
                            case "isShowingMenu" -> {
                                return showingMenu;
                            }
                            case "getCurrentCoreTab" -> {
                                return coreTab;
                            }
                            // colony-3: the only way to take the colony editor away from a player
                            // who lost the claim race is to switch the core UI off that tab.
                            case "showCoreUITab" -> {
                                coreTab = args[0];
                                coreTabSwitches.add(args[0]);
                                return null;
                            }
                            case "setDisallowPlayerInteractionsForOneFrame" -> {
                                disallowInteractionCount++;
                                return null;
                            }
                            // Phase 21: the coop dialog controllers open through this. Returning
                            // true models the engine taking the dialog; the plugin is recorded so a
                            // test can assert WHICH coop dialog went up.
                            case "showInteractionDialog" -> {
                                shownDialogs.add(args[0]);
                                if (throwOnShowInteractionDialog) {
                                    throw new IllegalStateException("no dialog for you");
                                }
                                return acceptInteractionDialogs;
                            }
                            case "addMessage" -> {
                                messages.add((String) args[0]);
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private InteractionDialogAPI interactionDialogProxy(RecordingEntity entity) {
            return (InteractionDialogAPI) Proxy.newProxyInstance(
                    InteractionDialogAPI.class.getClassLoader(),
                    new Class<?>[]{InteractionDialogAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        if ("getInteractionTarget".equals(method.getName())) {
                            return entity.proxy();
                        }
                        if ("dismiss".equals(method.getName())) {
                            dismissCount++;
                            if (dismissClosesDialog) {
                                target = null;
                            }
                            return null;
                        }
                        // Phase 14: the interaction gate asks the dialog for its plugin so it can skip
                        // the coop battle status panel. A vanilla dialog's plugin is not a coop one.
                        if ("getPlugin".equals(method.getName())) {
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class RecordingEntity {
        private final String id;
        private final String name;

        private RecordingEntity(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private SectorEntityToken proxy() {
            return (SectorEntityToken) Proxy.newProxyInstance(
                    SectorEntityToken.class.getClassLoader(),
                    new Class<?>[]{SectorEntityToken.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        return switch (method.getName()) {
                            case "getId" -> id;
                            case "getName" -> name;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }

    private static final Object UNHANDLED = new Object();

    private static Object objectMethodResult(Object proxy, String methodName, Object[] args) {
        return switch (methodName) {
            case "toString" -> proxy.getClass().getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> UNHANDLED;
        };
    }

    // ---- Phase 16: coordinated saves + guest snapshot -------------------------------------------

    @Test
    void hostStoresAnInboundGuestSnapshotForTheNextSave() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        Global.setSector(new RecordingSector(false).proxy());
        coop.save.CoopGuestSnapshot snapshot = new coop.save.CoopGuestSnapshot();
        snapshot.setPlayerId("guest-player");
        snapshot.setCredits(4242d);
        service.inbound.add(CoopMessages.guestSnapshot("session-a", 9L, 1000L, snapshot.encodeBody()));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));
        try {
            pump.advance(0f);

            coop.save.CoopGuestSnapshot stored = coop.save.CoopGuestSnapshotStore.latest();
            assertNotNull(stored);
            assertEquals("guest-player", stored.getPlayerId());
            assertEquals(4242d, stored.getCredits(), 0.0001d);
        } finally {
            coop.save.CoopGuestSnapshotStore.clear();
        }
    }

    @Test
    void aCompletedHostSaveSendsASaveCheckpoint() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        Global.setSector(new RecordingSector(false).proxy());
        // The pump registers itself as the checkpoint sink in its constructor, which is how the
        // ModPlugin's afterGameSave() reaches it.
        pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        coop.save.CoopSaveCheckpoint.notifyLocalGameSaved("host save");

        assertEquals(1, service.sent.size());
        CoopMessages.Message checkpoint = service.sent.get(0);
        assertEquals(CoopMessages.Type.SAVE_CHECKPOINT, checkpoint.type());
        assertEquals("session-a", checkpoint.sessionId());
        assertEquals(1L, CoopMessages.requiredPayloadLong(checkpoint, "checkpointId"));
        assertEquals("host save", CoopMessages.requiredPayloadString(checkpoint, "reason"));
    }

    @Test
    void aGuestsOwnSaveNeverEchoesACheckpointBackAtTheHost() {
        // The plugin's afterGameSave() fires on both clients. Without the role gate in the pump's
        // sender, the guest's coordinated autosave would trigger a checkpoint of its own and the two
        // clients would save each other in a loop.
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        Global.setSector(new RecordingSector(false).proxy());
        pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        coop.save.CoopSaveCheckpoint.notifyLocalGameSaved("host save");

        assertTrue(service.sent.isEmpty());
    }

    private static final class SequencedIds implements java.util.function.Supplier<String> {
        private final Queue<String> ids;

        private SequencedIds(String... ids) {
            this.ids = new ArrayDeque<>(List.of(ids));
        }

        @Override
        public String get() {
            return ids.remove();
        }
    }

    private static CoopHandshakeManifest emptyManifest(String gameVersion, String commit) {
        return new CoopHandshakeManifest(gameVersion, "0.1.0", commit, List.of());
    }

    private static CoopSessionState activeHostSession() {
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        // Phase 21: "active" now also means the players started the session. These helpers stand for
        // a session already in play, so they open the lobby gate; the lobby's own tests are the ones
        // that leave it shut.
        session.releaseLobby();
        return session;
    }

    // ---- Phase 20 M6: the unanswered-claim affordance ------------------------------------------

    @Test
    void theGuestIsToldOnceWhenTheHostDoesNotAnswerItsClaim() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.INTERACTION_CLAIM));
        assertEquals(0, waitingNotices(ui));

        now.set(1999L);
        pump.advance(0f);
        assertEquals(0, waitingNotices(ui),
                "still inside max(1000, 4 x p95); an unmeasured link uses the 1 s floor");

        now.set(2000L);
        pump.advance(0f);
        assertEquals(1, waitingNotices(ui));
        // The dialog is untouched: the optimistic-open model is unchanged by the notice.
        assertEquals(0, ui.dismissCount);

        now.set(5000L);
        pump.advance(0f);
        assertEquals(1, waitingNotices(ui), "the notice is once per claim, not once per frame");
    }

    @Test
    void aLateAcceptAddsNothingToTheFeed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        now.set(2000L);
        pump.advance(0f);
        int afterWarning = ui.messages.size();

        now.set(2400L);
        service.inbound.add(CoopMessages.interactionAccept(
                "session-a", 9L, 2300L, "market-1", "guest-player", "Jangala", 1L));
        pump.advance(0f);
        now.set(3000L);
        pump.advance(0f);

        assertEquals(afterWarning, ui.messages.size(),
                "a late answer says nothing more: the answer itself is the feedback");
    }

    @Test
    void aPromptAcceptNeverWarnsAtAll() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        pump.advance(0f);
        now.set(1200L);
        service.inbound.add(CoopMessages.interactionAccept(
                "session-a", 9L, 1100L, "market-1", "guest-player", "Jangala", 1L));
        pump.advance(0f);
        now.set(9000L);
        pump.advance(0f);

        assertEquals(0, waitingNotices(ui));
    }

    private static long waitingNotices(RecordingCampaignUi ui) {
        return ui.messages.stream().filter(m -> m.startsWith("Waiting for the host")).count();
    }

    // ---- Phase 20 M6: PAUSE_INTENT under a delayed, never-reordered link ------------------------

    @Test
    void delayedPauseIntentsStillApplyInOrderAndLeaveTheClockWhereTheGuestPutIt() {
        // The 200 ms + 2% loss walk: TCP turns loss into retransmission, so the guest's intents reach
        // the host late but never out of order. Replayed here through the Phase 18 latency lever,
        // which parks PAUSE_INTENT in the same FIFO as the claims and releases in receive order.
        String saved = System.getProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY);
        System.setProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY, "400");
        try {
            forceDebugToggleRefresh();
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = activeHostSession();
            RecordingSector sector = new RecordingSector(false);
            Global.setSector(sector.proxy());
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                    new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

            // Guest opens a screen, then closes it again before either intent has been delivered.
            service.inbound.add(CoopMessages.pauseIntent(
                    "session-a", 8L, 1000L, CoopMessages.PauseSource.SCREEN, true, 1L));
            pump.advance(0f);
            assertFalse(sector.paused, "the pause is still in flight");

            service.inbound.add(CoopMessages.pauseIntent(
                    "session-a", 9L, 1100L, CoopMessages.PauseSource.SCREEN, false, 2L));
            now.set(1200L);
            pump.advance(0f);

            now.set(1400L);
            pump.advance(0f);
            assertTrue(sector.paused, "the first intent lands first, and only the first");

            now.set(1600L);
            pump.advance(0f);
            assertFalse(sector.paused,
                    "the unpause must win: a delayed link may not strand the host paused");
        } finally {
            if (saved == null) {
                System.clearProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY);
            } else {
                System.setProperty(coop.util.CoopDebug.INTERACTION_DELAY_PROPERTY, saved);
            }
            forceDebugToggleRefresh();
        }
    }

    @Test
    void aSlowHandshakeNeverTimesOutTheConnectTimePauseHold() {
        // The hold has no deadline on purpose: at 200 ms RTT with retransmits the lobby + handshake +
        // seed-lock exchange is several round trips, and any fixed budget would be the thing that
        // broke. Ten simulated minutes of an incomplete handshake must still hold the clock.
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player", "session-a"));
        session.startHost("Host");
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = pumpWithTimeLock(service, session, now::get, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

        for (long t = 1000L; t <= 601_000L; t += 60_000L) {
            now.set(t);
            sector.paused = false; // the player keeps trying to unpause; the hold keeps winning
            pump.advance(0f);
            assertTrue(sector.paused, "the hold must still be asserted at t=" + t);
        }
    }

    private static CoopSessionState activeGuestSession() {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        // Phase 21: see activeHostSession().
        session.releaseLobby();
        return session;
    }

    private static CoopNetPump pumpWithTimeLock(RecordingNetService service, CoopSessionState session,
                                                java.util.function.LongSupplier clockMillis,
                                                RecordingTimeLock timeLock) {
        return new CoopNetPump(service, session, clockMillis,
                () -> emptyManifest("0.98a-RC8", "commit-a"),
                () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                timeLock);
    }

    // ---- Phase 20 red-team regressions -----------------------------------------------------------
    // One test per finding, named after it. Everything below reproduces a defect the review found in
    // this pump at 64bef68; each one failed before the corresponding fix.

    /** A transport whose peer address, proof throttle and attach generation the test drives. */
    private static class RedTeamNetService extends RecordingNetService {
        private final java.net.InetAddress peerAddress;
        private final List<java.net.InetAddress> failedProofs = new ArrayList<>();
        private boolean proofThrottled;
        private long generation;
        private int linkageErrorsToThrow;

        private RedTeamNetService(CoopConnectionRole role) {
            super(role);
            java.net.InetAddress address = null;
            try {
                address = java.net.InetAddress.getByName("203.0.113.7");
            } catch (java.net.UnknownHostException ignored) {
                // fixed literal; cannot happen
            }
            this.peerAddress = address;
        }

        @Override
        public java.net.InetAddress activePeerAddress() {
            return peerAddress;
        }

        @Override
        public void noteFailedProof(java.net.InetAddress source) {
            failedProofs.add(source);
        }

        @Override
        public boolean isProofThrottled(java.net.InetAddress source) {
            return proofThrottled;
        }

        @Override
        public long connectionGeneration() {
            return generation;
        }

        @Override
        public void sendTo(String senderId, CoopMessages.Message message) {
            if (linkageErrorsToThrow > 0) {
                linkageErrorsToThrow--;
                throw new NoSuchMethodError("simulated mod-load skew inside a handler");
            }
            super.sendTo(senderId, message);
        }
    }

    /**
     * Host whose session went live but is still sitting in its lobby (the Phase 21 shape), now
     * holding a grace window: the lobby stays up with the dropped player's row marked reconnecting,
     * which is the state the live smoke's three-row roster grew out of.
     */
    private static CoopNetPump hostInGraceWindowWithOpenLobby(RecordingNetService service,
                                                              CoopSessionState session,
                                                              AtomicLong now) {
        // A campaign UI, or the headless valve releases the lobby before a roster is ever built.
        Global.setSector(new RecordingSector(false, new RecordingCampaignUi(null)).proxy());
        CoopNetPump pump = livePump(service, session, now::get);
        // The guest's seed-lock ack, which in play is what put it in this lobby at all. Without it
        // the host has no evidence the guest ever reached SEED_LOCKED, and the drop below is a join
        // that fell over rather than a session worth holding.
        service.inbound.add(CoopMessages.seedLockAck("session-a", 2L, now.get(), "fingerprint-host"));
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.connected = true;
        service.sent.clear();
        return pump;
    }

    /**
     * Live smoke, second finding: the row that stood in for the dropped partner survived the wait
     * ending and sat beside the returning guest's new row, so the host saw three rows, all-ready was
     * false, and it was offered "Start anyway" for a partner who was ready.
     */
    @Test
    void theRelaunchRejoinLeavesExactlyTheHostAndTheReturningGuestInTheRoster() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindowWithOpenLobby(service, session, now);
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());
        assertEquals(2, pump.lobbyRosterForTest().size());
        assertTrue(pump.lobbyRosterForTest().row("guest-player").reconnecting());

        now.addAndGet(20_000L);
        service.inbound.add(CoopMessages.lobbyHello(1L, now.get(),
                new CoopPlayerInfo("guest-relaunched", "Cybele Unuk")));
        pump.advance(0f);

        assertEquals(List.of("host-player", "guest-relaunched"),
                pump.lobbyRosterForTest().rows().stream()
                        .map(coop.session.CoopLobbyRoster.Row::playerId).toList(),
                "the ghost of the old connection must not sit beside the returning guest");
        assertNull(pump.lobbyRosterForTest().row("guest-player"));
        assertFalse(pump.lobbyRosterForTest().countdownActive(),
                "the countdown belonged to a roster that no longer exists");

        // The returning guest readies up: nothing is left blocking the Start gate.
        now.addAndGet(1000L);
        pump.lobbyRosterForTest().setPhase("guest-relaunched",
                coop.session.CoopJoinPhase.SNAPSHOT_APPLIED, now.get());
        pump.lobbyRosterForTest().setReady("guest-relaunched", true, now.get());
        pump.advance(0f);

        assertTrue(pump.lobbyRosterForTest().allReady(),
                "one guest, ready: the gate has nothing left to wait for");
        assertEquals(coop.session.CoopLobbyRoster.START_LABEL,
                pump.lobbyRosterForTest().startLabel(),
                "so the host is offered Start, not 'Waiting for ...' with a Start anyway beside it");
        assertEquals(2, pump.lobbyRosterForTest().size(), "and no row grew back on a later frame");
    }

    /** The same cleanup on the exit the relaunch rejoin replaced: the wait simply running out. */
    @Test
    void aGraceExpiryTakesTheReconnectingRowWithIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindowWithOpenLobby(service, session, now);
        assertNotNull(pump.lobbyRosterForTest().row("guest-player"));

        now.addAndGet(CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS * 1000L + 1000L);
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        // Guaranteed by the drop inside onEnded, which does not depend on the session record having
        // already lost its guest - that is exactly what the relaunch path takes away.
        assertNull(pump.lobbyRosterForTest().row("guest-player"));
        assertEquals(1, pump.lobbyRosterForTest().size(), "the host is all that is left");
        assertFalse(pump.lobbyRosterForTest().countdownActive());
    }

    /**
     * The row's clock is the coordinator's window, counting down. It used to be the time elapsed
     * since the drop, counting up, which is how a 60 s window rendered "Reconnecting 1:55".
     */
    @Test
    void theReconnectingRowCountsDownTheCoordinatorsWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindowWithOpenLobby(service, session, now);

        now.addAndGet(20_000L);
        pump.advance(0f);

        coop.session.CoopLobbyRoster roster = pump.lobbyRosterForTest();
        coop.session.CoopLobbyRoster.Row row = roster.row("guest-player");
        int remaining = pump.reconnectCoordinatorForTest().remainingSeconds(now.get());
        assertEquals(CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS - 20, remaining);
        assertEquals("Reconnecting " + coop.session.CoopLobbyRoster.formatClock(remaining * 1000L),
                roster.stateWord(row, now.get()),
                "the row shows what the coordinator has left, not how long the player has been gone");
        assertEquals("Reconnecting 0:40", roster.stateWord(row, now.get()));

        // The wire carries the same remaining, so the guest's mirrored row counts down with it.
        pump.extendReconnectWaitForTest();
        pump.advance(0f);
        assertEquals("Reconnecting 5:40", roster.stateWord(roster.row("guest-player"), now.get()),
                "and an extension is visible on the next frame, not latched at the drop");
    }

    /**
     * Phase 21 live smoke, wrong-seed guest. The guest sends {@code SEED_LOCK_REJECT} and closes in
     * the same millisecond, so the bytes and the FIN reach the host in one poll and
     * {@code detectPeerDisconnect} runs first. The host used to drop the reject on the floor and open
     * a 60 s grace for a session the guest had just refused, ending in a COOP-SESSION expiry dialog
     * for what was a deterministic seed failure.
     */
    @Test
    void aSeedRejectArrivingWithTheDropIsAnsweredRatherThanHeldForAGrace() {
        LogCapture marker = LogCapture.attach(CoopDoctorMarker.class);
        try {
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = lobbyHostSession();
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = livePump(service, session, now::get);
            pump.advance(0f);

            // One poll, both events: the reject the guest queued and the socket it closed behind it.
            now.addAndGet(1000L);
            service.inbound.add(CoopMessages.seedLockReject("session-a", 3L, now.get(),
                    "campaignId: host=c2de8c21 guest=<none>; this campaign is already in flight"));
            service.connected = false;
            pump.advance(0f);

            assertNotNull(pump.desyncReasonForTest(), "the host learns why the guest left");
            assertEquals("COOP-SEED", pump.desyncReasonForTest().code());
            assertTrue(pump.desyncDialogRequestedForTest());
            assertEquals(1, marker.messages().stream()
                            .filter(line -> line.contains("code=COOP-SEED")
                                    && line.contains("sessionId=session-a")).count(),
                    "and its marker carries the session id the guest stamped on its own line");
            assertEquals(1, feedLinesSaying(
                            coop.ui.CoopDesyncDialog.feedLine(pump.desyncReasonForTest())),
                    "the feed keeps saying it after the dialog is dismissed");
            assertFalse(pump.reconnectCoordinatorForTest().active(),
                    "a refused seed is not a link blip: there is no session to hold");
            assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
            assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState(),
                    "the lobby is back to waiting for a guest immediately");
            assertNull(session.sessionId());
        } finally {
            marker.detach();
        }
    }

    /** The same drop with no reject at all: still no grace, because no session was ever joined. */
    @Test
    void aGuestThatVanishesBeforeTheSeedLockGetsNoGraceAndOneWarning() {
        LogCapture log = LogCapture.attach(CoopNetPump.class);
        try {
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = lobbyHostSession();
            AtomicLong now = new AtomicLong(1000L);
            CoopNetPump pump = livePump(service, session, now::get);
            pump.advance(0f);

            service.connected = false;
            now.addAndGet(1000L);
            pump.advance(0f);

            assertFalse(pump.reconnectCoordinatorForTest().active(),
                    "the host recorded its own seed when it sent the request; the guest never"
                            + " accepted it, so there is nothing a resume could restore");
            assertFalse(pump.pauseCoordinatorForBridge().reconnectHold());
            assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
            assertNull(session.sessionId());
            assertEquals(1, log.messages().stream()
                            .filter(line -> line.contains("left during the join handshake")).count(),
                    "one WARN, not a dialog: nothing failed that needs explaining as a desync");
            assertFalse(pump.desyncDialogRequestedForTest());
        } finally {
            log.detach();
        }
    }

    /** Regression: past the seed lock the drop is a real session drop and keeps its grace window. */
    @Test
    void aDropAfterTheGuestAcceptedTheSeedLockStillOpensTheGraceWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);
        // The ack is the threshold: SEED_LOCKED, from the guest's own mouth.
        service.inbound.add(CoopMessages.seedLockAck("session-a", 2L, now.get(), "fingerprint-host"));
        pump.advance(0f);

        service.connected = false;
        now.addAndGet(1000L);
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());
        assertEquals("session-a", session.sessionId(), "the held session survives the socket");
        assertTrue(pump.pauseCoordinatorForBridge().reconnectHold());
    }

    /**
     * The guest half of the same race: {@code stopReconnecting} closes every link and a closing link
     * discards its queue, so a reject sent and stopped in one handler never reached the wire at all.
     */
    @Test
    void aGuestSeedRejectReachesTheWireBeforeTheConnectLoopCloses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(hostSeedLockRequest());
        CoopNetPump pump = pumpForGuestSeedLock(service, session,
                new java.util.concurrent.atomic.AtomicReference<>("campaign-a"),
                false, false, "fingerprint-guest", () -> "");

        pump.advance(0f);

        assertEquals(1, countOf(service, CoopMessages.Type.SEED_LOCK_REJECT), "the reject was queued");
        assertTrue(service.flushed.stream()
                        .anyMatch(m -> m.type() == CoopMessages.Type.SEED_LOCK_REJECT),
                "and flushed: a reject the host never receives leaves it holding a phantom session");
        assertEquals(1, service.stopReconnectingReasons.size(),
                "the linger delays the close, it does not cancel it");
        assertTrue(session.rejectTerminal());
    }

    /** Host holding a live session that has just lost its socket: the grace window is open. */
    private static CoopNetPump hostInGraceWindow(RecordingNetService service, CoopSessionState session,
                                                 AtomicLong now) {
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.connected = true;
        service.sent.clear();
        return pump;
    }

    @Test
    void a2_aResumeRequestIsChallengedAndOnlyAcceptedWithThePasswordProof() {
        RedTeamNetService service = new RedTeamNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);
        pump.setLobbyPasswordForTest(PASSWORD);

        // Exactly what a passive listener could replay off the wire: session id and player id, both
        // of which travelled in cleartext before the drop. Pre-fix this was ACCEPTED outright.
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 9L, now.get(),
                "guest-player"));
        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.SESSION_RESUME_ACCEPT),
                "a resume must not bypass the lobby password");
        CoopMessages.Message challenge = onlyOf(service, CoopMessages.Type.LOBBY_CHALLENGE);
        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "an unproven request must not end the wait");

        String nonce = CoopMessages.parseLobbyChallengeNonce(challenge);
        service.sent.clear();
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 10L, now.get(),
                "guest-player", CoopMessages.passwordProof(PASSWORD, nonce)));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertFalse(pump.reconnectCoordinatorForTest().active());
    }

    @Test
    void a2_aReconnectingGuestAnswersTheChallengeWithAProofCarryingResumeRequest() {
        RedTeamNetService service = new RedTeamNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = activeGuestPump(service, now::get);
        pump.setLobbyPasswordForTest(PASSWORD);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        service.connected = true;
        service.sent.clear();

        service.inbound.add(CoopMessages.lobbyChallenge(3L, now.get(), "abcdef0123456789"));
        pump.advance(0f);

        // A hello here would be answered "session in reconnect grace" and the resume would never
        // happen; the challenge has to be answered in the vocabulary the host is waiting for.
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_HELLO));
        List<CoopMessages.Message> requests = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.SESSION_RESUME_REQUEST)
                .toList();
        assertFalse(requests.isEmpty(), "the guest owes a proof-carrying resume request");
        CoopMessages.Message proven = requests.get(requests.size() - 1);
        assertEquals(CoopMessages.passwordProof(PASSWORD, "abcdef0123456789"),
                CoopMessages.parseResumeProof(proven));
    }

    @Test
    void a3_aWrongPasswordIsReportedToTheThrottleAndAThrottledAddressIsNotEvenChallenged() {
        RedTeamNetService service = new RedTeamNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);

        service.inbound.add(CoopMessages.lobbyHello(1L, 7_000L,
                new CoopPlayerInfo("guest-player", "Guest")));
        pump.advance(0f);
        String nonce = CoopMessages.parseLobbyChallengeNonce(
                onlyOf(service, CoopMessages.Type.LOBBY_CHALLENGE));
        assertNotNull(nonce);

        service.inbound.add(CoopMessages.lobbyHello(2L, 7_000L,
                new CoopPlayerInfo("guest-player", "Guest"),
                CoopMessages.passwordProof("wrong", nonce)));
        pump.advance(0f);

        assertEquals(1, service.failedProofs.size(),
                "a wrong guess must reach the per-address cooldown, not just close the socket");
        assertEquals(service.activePeerAddress(), service.failedProofs.get(0));

        // Once the address is in its cooldown the host does no work at all for it: no nonce minted,
        // no challenge, no reject. Answering is the resource the guesser is after.
        service.proofThrottled = true;
        service.sent.clear();
        service.inbound.add(CoopMessages.lobbyHello(3L, 7_000L,
                new CoopPlayerInfo("guest-player", "Guest")));
        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_CHALLENGE));
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_REJECT));
    }

    @Test
    void a4_pingAndResumeFromAPeerWithNoSessionAndNoGraceWindowAreNotAnswered() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1_000L);

        for (int i = 0; i < 20; i++) {
            service.inbound.add(CoopMessages.ping("session-a", i, 1_000L));
        }
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 99L, 1_000L, "stranger"));
        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.PONG),
                "a connected stranger must not get a free reply channel");
        assertEquals(0, countOfType(service, CoopMessages.Type.SESSION_RESUME_REJECT));
        assertEquals(21L, pump.unprovenPeerAnswersRefused());
    }

    @Test
    void a4_anAcceptedPeerIsStillAnsweredThroughTheSeedLockWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1_000L);

        // Lobby-accepted but not yet seed-locked: the host holds paused through this window, and it
        // is exactly where a half-open connection would otherwise stay invisible.
        service.inbound.add(CoopMessages.ping("session-a", 1L, 1_000L));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.PONG));
        assertEquals(0L, pump.unprovenPeerAnswersRefused());
    }

    @Test
    void a6_aDatagramFromAForeignSenderIdNeverReachesTheWatermarkOrTheLinkQuality() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        String token = CoopMessages.wireToken("session-a");
        String foreign = CoopMessages.wireToken("someone-else");
        service.inboundDatagrams.add(CoopMessages.datagram(token, foreign,
                CoopMessages.Type.NPC_FLEET_MOTION, 5L, 0L, EMPTY_MOTION_BODY));
        pump.advance(0f);

        assertEquals(Long.MIN_VALUE, pump.datagramWatermark()
                .watermarkFor(foreign, CoopMessages.Type.NPC_FLEET_MOTION),
                "an attacker-chosen senderId must not create a per-stream watermark entry");
        assertEquals(1L, pump.datagramSenderMismatchCount());
    }

    @Test
    void a8_aSampleStampFarInTheFutureIsRejectedAndTheMirrorsKeepAdvancing() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        String token = CoopMessages.wireToken("session-a");
        String sender = CoopMessages.wireToken("guest-player");
        String tick = coop.fleet.CoopFleetSnapshot.Tick
                .of(playerSnapshot("guest-player", 2)).encode();

        service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                CoopMessages.Type.FLEET_SNAPSHOT, 1L, 1_000L, tick));
        pump.advance(0f);
        assertEquals(0L, pump.rejectedSampleStampCount());

        // The stamp CoopMotionTimeline would latch forever, because noteSample only ever rises.
        service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                CoopMessages.Type.FLEET_SNAPSHOT, 2L, Long.MAX_VALUE, tick));
        pump.advance(0f);
        assertEquals(1L, pump.rejectedSampleStampCount());

        // And the stream is not poisoned: an ordinary sample after it is still accepted.
        service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                CoopMessages.Type.FLEET_SNAPSHOT, 3L, 1_100L, tick));
        pump.advance(0f);
        assertEquals(1L, pump.rejectedSampleStampCount());
    }

    @Test
    void a11_datagramDecodeFailuresAreCountedRatherThanLoggedPerPacket() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        String token = CoopMessages.wireToken("session-a");
        String sender = CoopMessages.wireToken("guest-player");
        for (int i = 1; i <= 50; i++) {
            service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                    CoopMessages.Type.FLEET_SNAPSHOT, i, 1_000L, "this is not a tick"));
        }
        pump.advance(0f);

        assertEquals(50L, pump.datagramDecodeFailureCount(),
                "every failure is counted even though at most one may be logged per 10 s");
    }

    @Test
    void a14_aFrameThatThrowsIsCaughtAndThePumpKeepsRunning() {
        ThrowingPollNetService service = new ThrowingPollNetService(CoopConnectionRole.HOST);
        CoopNetPump pump = activeHostPump(service, () -> 1_000L);

        service.throwOnNextPoll = true;
        pump.advance(0f);
        assertEquals(1L, pump.frameFailureCount());

        // Still alive: an EveryFrameScript that lets an exception out is removed by the engine, and
        // that would end the session silently, mid-game.
        pump.advance(0f);
        assertEquals(1L, pump.frameFailureCount());
        assertFalse(pump.isDone());
    }

    @Test
    void c9_aHandlerThrowingALinkageErrorDoesNotSkipTheRestOfTheInboundQueue() {
        RedTeamNetService service = new RedTeamNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1_000L);

        service.linkageErrorsToThrow = 1;
        service.inbound.add(CoopMessages.ping("session-a", 1L, 1_000L));
        service.inbound.add(CoopMessages.ping("session-a", 2L, 1_000L));
        pump.advance(0f);

        assertEquals(0L, pump.frameFailureCount(),
                "the per-message catch must handle it, not the frame guard");
        assertEquals(1, countOfType(service, CoopMessages.Type.PONG),
                "the message behind the throwing one must still be dispatched");
    }

    @Test
    void b1_aSecondDropInsideTheGraceWindowKeepsTheHeldSession() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);

        // The guest's socket comes back and dies again inside the same window - a flapping link, the
        // ordinary case this feature exists for. Pre-fix the second edge ran the full teardown while
        // the coordinator went on holding the ids, and the resume below was then ACCEPTED against a
        // session this side no longer had.
        now.addAndGet(1_000L);
        pump.advance(0f);
        service.connected = false;
        now.addAndGet(1_000L);
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "the window must keep running across a second drop");
        assertEquals("session-a", session.sessionId(),
                "the held session must survive the second drop");
        assertEquals(CoopLobbyState.HOST_CONNECTED, session.connectionState());

        service.connected = true;
        service.sent.clear();
        now.addAndGet(1_000L);
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 20L, now.get(),
                "guest-player"));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertEquals("session-a", session.sessionId());
    }

    @Test
    void b1_aResumeIsRefusedWhenThisSideHoldsNoSessionId() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);

        // Whatever put this side into "grace window open, but no session id" - the second-drop bug
        // was one route - a resume must not be granted against the coordinator's cached ids alone.
        session.onChannelDisconnected();
        assertNull(session.sessionId());

        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 21L, now.get(),
                "guest-player"));
        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertEquals(1, countOfType(service, CoopMessages.Type.SESSION_RESUME_REJECT));
    }

    @Test
    void b2_aHalfOpenReplacementIsTreatedAsADropEdgeSoTheResumeResolves() {
        RedTeamNetService service = new RedTeamNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, session, now::get);
        pump.advance(0f);
        service.sent.clear();

        // The transport closed the stale slot and accepted the returning guest inside one poll, so
        // isConnected() never went false. Pre-fix the host saw no edge at all: it stayed
        // HOST_CONNECTED, answered the resume "not waiting", and the guest was locked out for good.
        service.generation++;
        now.addAndGet(1_000L);
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 30L, now.get(),
                "guest-player"));
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.SESSION_RESUME_ACCEPT),
                "the drop edge must open the window before the resume request is dispatched");
        assertEquals(0, countOfType(service, CoopMessages.Type.SESSION_RESUME_REJECT));
        assertEquals("session-a", session.sessionId());
    }

    @Test
    void b4_aStallNoticeStampsTheSameExemptionASaveCheckpointDoes() {
        DroppingNetService service = new DroppingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = activeHostPump(service, now::get);
        pump.advance(0f);

        // The guest is saving. Pre-fix nothing said so - SAVE_CHECKPOINT is host-only - and the host
        // declared the link dead on a partner sitting at a save.
        service.inbound.add(CoopMessages.stallNotice("session-a", 4L, 1_000L, "local save", 15_000L));
        pump.advance(0f);
        for (long t = 2_000L; t <= 50_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }

        assertEquals(List.of(), service.drops, "a peer that announced a stall is not a dead peer");

        for (long t = 51_000L; t <= 75_000L; t += 1_000L) {
            now.set(t);
            pump.advance(0f);
        }
        assertEquals(1, service.drops.size(), "past the exempt window the silence is a verdict again");
    }

    @Test
    void b4_bothRolesAnnounceTheirOwnStallAndFlushItImmediately() {
        for (CoopConnectionRole role : List.of(CoopConnectionRole.HOST, CoopConnectionRole.GUEST)) {
            RecordingNetService service = new RecordingNetService(role);
            CoopNetPump pump = role == CoopConnectionRole.HOST
                    ? activeHostPump(service, () -> 1_000L)
                    : activeGuestPump(service, () -> 1_000L);
            pump.advance(0f);
            service.sent.clear();

            coop.net.CoopStallNotice.notifyLocalStall(coop.net.CoopStallNotice.REASON_LOCAL_SAVE,
                    coop.net.CoopStallNotice.SAVE_EXPECTED_MILLIS);

            CoopMessages.Message notice = onlyOf(service, CoopMessages.Type.STALL_NOTICE);
            assertEquals(coop.net.CoopStallNotice.REASON_LOCAL_SAVE,
                    CoopMessages.parseStallReason(notice));
            assertEquals(coop.net.CoopStallNotice.SAVE_EXPECTED_MILLIS,
                    CoopMessages.parseStallExpectedMillis(notice));
        }
    }

    @Test
    void b7_thePeerDroppedBadgeIsClearedWhenTheGraceWindowCloses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = activeHostPump(service, now::get);
        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        assertEquals(CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING, pump.hudState(true).status());

        now.addAndGet(61_000L);
        pump.advance(0f);

        // Pre-fix the flag was set and never cleared, so this host advertised a hold it was not
        // performing for the rest of the process - including through the next lobby it opened.
        assertEquals(CoopHudState.STATUS_WAITING_FOR_GUEST, pump.hudState(true).status());
    }

    @Test
    void c6_aStuckRosterMismatchAsksThePeerToResendAndTheRequestForcesOne() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1_000L);
        CoopNetPump pump = livePump(service, activeHostSession(), now::get);
        pump.advance(0f);

        // A roster arrives, then the peer's fleet changes and the new roster is lost. Ticks now name
        // a hash this side does not hold, for good: the sender only re-sends on a hash change.
        service.inbound.add(CoopMessages.fleetRoster("session-a", 1L, 1_000L,
                coop.fleet.CoopFleetRoster.of(playerSnapshot("guest-player", 2)).encode()));
        pump.advance(0f);

        String token = CoopMessages.wireToken("session-a");
        String sender = CoopMessages.wireToken("guest-player");
        String tick = coop.fleet.CoopFleetSnapshot.Tick
                .of(playerSnapshot("guest-player", 7)).encode();
        long epoch = 10L;
        for (long at : new long[] {2_000L, 3_000L, 20_000L}) {
            now.set(at);
            service.inboundDatagrams.add(CoopMessages.datagram(token, sender,
                    CoopMessages.Type.FLEET_SNAPSHOT, epoch++, at, tick));
            pump.advance(0f);
        }

        assertTrue(pump.rosterCache().mismatchLogged(), "the cache must have detected the stuck hold");
        // The mismatch is detected in the datagram drain, which runs after syncFleetMirror; the ask
        // therefore goes out on the following frame.
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.FLEET_ROSTER_REQUEST));

        // Rate limited: the mismatch persists but the ask does not repeat every frame.
        now.set(21_000L);
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.FLEET_ROSTER_REQUEST));
        now.set(30_000L);
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.FLEET_ROSTER_REQUEST));

        // And the answering side clears its hash so the next fleet tick re-sends the roster.
        RecordingNetService peer = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopNetPump peerPump = activeGuestPump(peer, () -> 1_000L);
        peerPump.advance(0f);
        peerPump.maybeSendFleetRoster(playerSnapshot("guest-player", 2));
        assertFalse(peerPump.lastSentRosterHash().isEmpty());
        peer.inbound.add(CoopMessages.fleetRosterRequest("session-a", 5L, 1_000L));
        peerPump.advance(0f);
        assertEquals("", peerPump.lastSentRosterHash());
    }

    @Test
    void c10_theWireTokensAreCachedAndRecomputedWhenTheIdBehindThemChanges() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        MutableSessionState session = new MutableSessionState();
        CoopNetPump pump = livePump(service, session, new AtomicLong(1_000L)::get);

        assertEquals(CoopMessages.wireToken("session-a"), pump.sessionToken());
        assertSame(pump.sessionToken(), pump.sessionToken(), "a second read must not re-hash");
        assertEquals(CoopMessages.wireToken("host-player"), pump.localSenderToken());
        assertEquals(CoopMessages.wireToken("guest-player"), pump.remoteSenderToken());

        session.overrideSessionId = "session-b";
        assertEquals(CoopMessages.wireToken("session-b"), pump.sessionToken(),
                "the cache keys on the id, so a session edge invalidates it by itself");

        session.overrideSessionId = null;
        assertEquals("", pump.sessionToken(), "no session, no token");
    }

    /** A live host session whose session id the test can move, which the real one cannot. */
    private static final class MutableSessionState extends CoopSessionState {
        private String overrideSessionId = "session-a";
        private boolean overrideActive = true;

        private MutableSessionState() {
            super(new SequencedIds("lobby-a", "host-player", "session-a"));
            startHost("Host");
            hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
            hostAcceptHandshake();
            recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        }

        @Override
        public synchronized String sessionId() {
            return overrideActive ? overrideSessionId : super.sessionId();
        }
    }

    /** Makes one frame body throw, from outside every per-message guard the pump already has. */
    private static final class ThrowingPollNetService extends RecordingNetService {
        private boolean throwOnNextPoll;

        private ThrowingPollNetService(CoopConnectionRole role) {
            super(role);
        }

        @Override
        public Inbound pollInboundEntry() {
            if (throwOnNextPoll) {
                throwOnNextPoll = false;
                throw new IllegalStateException("simulated transport failure mid-frame");
            }
            return super.pollInboundEntry();
        }
    }

    private static final class RecordingTimeLock extends CoopTimeLock {
        private final CoopTimeLock.TimeSnapshot snapshot;
        private final List<CoopTimeLock.TimeSnapshot> applied = new ArrayList<>();
        private final List<Boolean> inputBlockerStates = new ArrayList<>();
        private final List<Boolean> interactionBlocks = new ArrayList<>();
        private final List<String> capturedPauseHolders = new ArrayList<>();

        private RecordingTimeLock(CoopTimeLock.TimeSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public CoopTimeLock.TimeSnapshot capture(long sentAtMillis, String pausedBy) {
            capturedPauseHolders.add(pausedBy);
            return new CoopTimeLock.TimeSnapshot(
                    snapshot.paused(),
                    snapshot.fastForward(),
                    snapshot.timestampMillis(),
                    snapshot.campaignDay(),
                    sentAtMillis,
                    pausedBy);
        }

        @Override
        public void apply(CoopTimeLock.TimeSnapshot snapshot) {
            applied.add(snapshot);
        }

        @Override
        public void syncGuestInputBlocker(boolean active) {
            inputBlockerStates.add(active);
        }

        @Override
        public void setInteractionBlocked(boolean blocked) {
            interactionBlocks.add(blocked);
        }
    }
    // ---- Phase 21: desync dialogs ------------------------------------------------------------------

    /** The seed-lock request a host sends; the guest's fingerprint is what decides the outcome. */
    private static CoopMessages.Message hostSeedLockRequest() {
        return CoopMessages.seedLockRequest("session-a", 4L, 14000L, 123456789L, "coop-seed",
                "fingerprint-host", "campaign-a", true);
    }

    @Test
    void aGuestSeedRejectRaisesTheDesyncDialogAndStopsTheRetryLoop() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(hostSeedLockRequest());
        CoopNetPump pump = pumpForGuestSeedLock(service, session,
                new java.util.concurrent.atomic.AtomicReference<>("campaign-a"),
                false, false, "fingerprint-guest", () -> "");

        pump.advance(0f);

        assertTrue(pump.desyncDialogRequestedForTest(), "the guest is shown why its sector differs");
        assertNotNull(pump.desyncReasonForTest());
        assertEquals("COOP-SEED", pump.desyncReasonForTest().code());
        // No auto-retry on a deterministic reject: the loop is stopped and the state is terminal, so
        // the guest cannot reconnect every 5 s and bury its own dialog under fresh ones.
        assertEquals(1, service.stopReconnectingReasons.size());
        assertTrue(session.rejectTerminal());
        assertFalse(session.guestRearmLobby(), "a terminal reject must not rearm on the next connect");
        // The connecting dialog steps aside: showing "version mismatch / host refused / timed out"
        // next to the dialog that names the actual sector difference is two weaker answers, not one
        // good one.
        assertFalse(pump.connectingDialogRequestedForTest());
    }

    @Test
    void aHostSeedRejectShowsTheHostWhyAndTheLobbyYieldsUntilItIsClosed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.advance(0f);
        assertTrue(pump.lobbyDialogRequestedForTest(), "the host sits in its lobby to begin with");

        service.inbound.add(CoopMessages.seedLockAck("session-a", 6L, 1000L, "fingerprint-guest"));
        pump.advance(0f);

        assertTrue(pump.desyncDialogRequestedForTest());
        assertEquals("COOP-SEED", pump.desyncReasonForTest().code());
        // Arbiter precedence: the lobby must not take the slot back off the only thing telling the
        // host why the guest never arrived.
        assertFalse(pump.lobbyDialogRequestedForTest());
        // The host is NOT terminal: it rewinds and keeps waiting for a corrected guest.
        assertFalse(session.rejectTerminal());
        assertTrue(service.stopReconnectingReasons.isEmpty(), "only a guest stops dialling");

        pump.desyncCloseForTest();
        pump.advance(0f);

        assertFalse(pump.desyncDialogRequestedForTest());
        assertTrue(pump.lobbyDialogRequestedForTest(), "the lobby comes back once the dialog is gone");
    }

    @Test
    void bothSidesWriteTheSameDoctorMarkerCorrelationIdForOneSeedReject() {
        // The whole point of the marker is that a support thread with two pastes can be matched up,
        // which only works if the sessionId field is byte-identical on the two machines.
        CoopDesyncReason hostSide = CoopDesyncReason.classify(
                "sectorFingerprint: host=fingerprint-host guest=fingerprint-guest",
                CoopDesyncReason.Source.SEED_LOCK);
        CoopDesyncReason guestSide = CoopDesyncReason.classify(
                "sectorFingerprint: host=fingerprint-host guest=fingerprint-guest",
                CoopDesyncReason.Source.SEED_LOCK);

        String hostLine = CoopDoctorMarker.format(hostSide, "session-a", CoopConnectionRole.HOST,
                "Host", "Guest");
        String guestLine = CoopDoctorMarker.format(guestSide, "session-a", CoopConnectionRole.GUEST,
                "Guest", "Host");

        assertTrue(hostLine.startsWith("[COOP-DOCTOR] code=COOP-SEED sessionId=session-a"), hostLine);
        assertTrue(guestLine.startsWith("[COOP-DOCTOR] code=COOP-SEED sessionId=session-a"), guestLine);
        assertFalse(hostLine.contains("\n"), "the marker has to be selectable in one drag");
        assertFalse(guestLine.contains("\n"));
    }

    @Test
    void theSessionIdIsCapturedBeforeTheRejectClearsIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        Global.setSector(new RecordingSector(false, new RecordingCampaignUi(null)).proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        service.inbound.add(CoopMessages.seedLockAck("session-a", 6L, 1000L, "fingerprint-guest"));
        pump.advance(0f);

        // rejectHandshake runs clearCanonicalSession, so reading the id after the reject would give
        // <none> on both machines - the one value the correlation cannot afford to lose.
        assertNull(session.sessionId());
        assertTrue(pump.desyncDialogRequestedForTest());
    }

    @Test
    void aGraceExpiryRaisesTheSessionDialogAndStatesTheWindowAsANumber() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);

        now.addAndGet(CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS * 1000L + 1000L);
        pump.advance(0f);

        assertTrue(pump.desyncDialogRequestedForTest());
        assertEquals("COOP-SESSION", pump.desyncReasonForTest().code());
        assertEquals(CoopDesyncReason.SessionCause.GRACE_EXPIRED,
                pump.desyncReasonForTest().sessionCause());
        // The window length is a number the dialog is required to state and only the pump knows.
        assertEquals(CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS,
                pump.desyncReasonForTest().graceSeconds());
    }

    /**
     * "Wait longer" moves the deadline and leaves the configured length alone, so a world a player
     * held for six and a half minutes told them it had been held for sixty seconds.
     */
    @Test
    void anExtendedWindowStatesTheTimeItWasActuallyHeldNotTheConfiguredLength() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);

        // The window opened at 1000 and was due at 61000; the extension pushes it 300 s past that.
        now.addAndGet(30_000L);
        pump.extendReconnectWaitForTest();
        pump.advance(0f);
        now.set(1000L + CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS * 1000L
                + CoopReconnectCoordinator.WAIT_MORE_MILLIS);
        pump.advance(0f);

        assertEquals(CoopDesyncReason.SessionCause.GRACE_EXPIRED,
                pump.desyncReasonForTest().sessionCause());
        assertEquals(360, pump.desyncReasonForTest().graceSeconds(),
                "60 s configured plus the 300 s the player asked for is what the world was held for");
    }

    @Test
    void aPlayerEndingTheWaitGetsNoSecondDialogExplainingTheirOwnButton() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = hostInGraceWindow(service, session, now);

        pump.endReconnectWaitForTest();
        pump.advance(0f);

        assertFalse(pump.desyncDialogRequestedForTest(),
                "answering a button press with a modal explaining what the button did is a loop");
    }

    // ---- Phase 21: session stats -------------------------------------------------------------------

    @Test
    void theHostTalliesAGuestBattleFromTheBattleResultItReceives() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);
        // One warm-up frame: the session-start edge inside syncNpcReplication resets the reconciler's
        // applied-battle ledger, and in play that edge is long past by the time a battle happens.
        pump.advance(0f);

        CoopBattleResult result = new CoopBattleResult("battle-1", "guest-player", "WIN", 5,
                List.of("npc-1", "npc-2"), List.of());
        service.inbound.add(CoopMessages.battleResult("session-a", 7L, 1000L, result.battleId(),
                result.engagingPlayerId(), result.outcome(), result.engagingFleetSize(),
                result.encodeBody()));
        pump.advance(0f);

        CoopSessionStats stats = pump.sessionStatsForTest();
        assertEquals(1L, stats.player("guest-player").battlesFought());
        assertEquals(1L, stats.player("guest-player").battlesWon());
        assertEquals(2L, stats.fleetsDestroyedTeam());

        // The reconciler's battle-id ledger is the dedup: a resend must not count twice.
        service.inbound.add(CoopMessages.battleResult("session-a", 8L, 1000L, result.battleId(),
                result.engagingPlayerId(), result.outcome(), result.engagingFleetSize(),
                result.encodeBody()));
        pump.advance(0f);
        assertEquals(1L, stats.player("guest-player").battlesFought());
        assertEquals(2L, stats.fleetsDestroyedTeam());
    }

    @Test
    void aGuestMarketTransactionJoinsTheHostsMarketsTradedWithSet() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        service.inbound.add(CoopMessages.marketTxn("session-a", 9L, 1000L, "jangala", "open_market",
                "COMMODITY", "supplies", 10, 0f, "guest-player"));
        pump.advance(0f);

        assertEquals(List.of("jangala"),
                pump.sessionStatsForTest().player("guest-player").marketsTradedWith());
        assertEquals(1, pump.sessionStatsForTest().marketsTradedWithUnionCount());
    }

    @Test
    void theHostTalliesAMissionClaimItAcceptsAndNotTheOneItRejects() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        service.inbound.add(CoopMessages.missionClaimRequest("session-a", 10L, 1000L, "mission-1",
                "guest-player"));
        pump.advance(0f);
        assertEquals(1L, pump.sessionStatsForTest().player("guest-player").missionsClaimed());

        // First-come arbitration is the dedup: the second claim on the same mission is rejected, so
        // nothing behind it counts.
        service.inbound.add(CoopMessages.missionClaimRequest("session-a", 11L, 1000L, "mission-1",
                "guest-player"));
        pump.advance(0f);
        assertEquals(1L, pump.sessionStatsForTest().player("guest-player").missionsClaimed());
    }

    @Test
    void theGuestReplacesItsTallyWithTheHostsSessionStatsBroadcast() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        CoopSessionStats sent = new CoopSessionStats();
        sent.notePlayer("host-player", "Host");
        sent.notePlayer("guest-player", "Guest");
        sent.noteBattle("host-player", true);
        sent.noteFleetsDestroyed(3);
        sent.noteTogether(120f);
        service.inbound.add(CoopMessages.sessionStats("session-a", 12L, 1000L,
                CoopSessionStatsCodec.encodePayload(sent)));

        pump.advance(0f);

        CoopSessionStats applied = pump.sessionStatsForTest();
        assertEquals(List.of("host-player", "guest-player"), applied.playerIds());
        assertEquals(1L, applied.player("host-player").battlesWon());
        assertEquals(3L, applied.fleetsDestroyedTeam());
        assertEquals(120f, applied.timeFlownTogetherSeconds(), 0.01f);
        // The page reads whatever the pump holds, and the guest's save carries the same copy.
        assertSame(applied, CoopSessionStatsStore.current());
    }

    @Test
    void theHostBroadcastsTheStatsOnceAtReleaseAndThenOnTheThirtySecondCadence() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        Global.setSector(new RecordingSector(false, new RecordingCampaignUi(null)).proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        service.inbound.add(CoopMessages.readyState("session-a", 5L, 1000L, "SNAPSHOT_APPLIED", true));
        pump.advance(0f);
        pump.lobbyStartForTest();
        now.addAndGet(coop.session.CoopLobbyRoster.COUNTDOWN_MILLIS + 100L);
        pump.advance(0f);

        assertTrue(session.lobbyReleased());
        assertEquals(1, countSent(service, CoopMessages.Type.SESSION_STATS),
                "one send behind the release, so the page is populated from the first minute");

        // Nothing more until the interval is up.
        now.addAndGet(29_000L);
        pump.advance(0f);
        assertEquals(1, countSent(service, CoopMessages.Type.SESSION_STATS));

        now.addAndGet(2_000L);
        pump.advance(0f);
        assertEquals(2, countSent(service, CoopMessages.Type.SESSION_STATS));
    }

    @Test
    void theHostRecordsAHullTheGuestReportsLosing() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        service.inbound.add(CoopMessages.shipLost("session-a", 13L, 1000L, "guest-player",
                "ISS Bad Idea", "Wolf", "Corvus", 42.5f, "battle"));
        pump.advance(0f);

        CoopSessionStats stats = pump.sessionStatsForTest();
        assertEquals(1L, stats.player("guest-player").shipsLost());
        assertEquals(1, stats.shipLossLedger().size());
        assertEquals("ISS Bad Idea", stats.shipLossLedger().get(0).hullName());
        assertEquals(42.5f, stats.lastHullLossDay(), 0.01f);
    }


    // ---- Phase 21 red-team ------------------------------------------------------------------------

    /**
     * Blocker 1. A guest that drops two seconds into the three-second countdown used to leave the
     * countdown running, so the host released alone: into a session with no guest, with the
     * {@code LOBBY_STATUS(released)} dropped on the floor by the dead socket and no later one to
     * replace it, and with the coop pause hold promoted to a host pause intent nothing could clear.
     */
    @Test
    void aGuestDroppingMidCountdownCancelsItInsteadOfStartingTheHostAlone() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        RecordingSector sector = new RecordingSector(false, ui);
        Global.setSector(sector.proxy());
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = livePump(service, session, now::get);

        service.inbound.add(CoopMessages.readyState("session-a", 5L, 1000L, "SNAPSHOT_APPLIED", true));
        pump.advance(0f);
        pump.lobbyStartForTest();
        pump.advance(0f);
        assertTrue(pump.lobbyRosterForTest().countdownActive());

        // Two seconds into a three-second countdown.
        now.set(3000L);
        service.connected = false;
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting());
        assertFalse(pump.lobbyRosterForTest().countdownActive(),
                "the drop is the countdown's answer");

        // Well past the moment it would have fired.
        now.set(1000L + coop.session.CoopLobbyRoster.COUNTDOWN_MILLIS + 5_000L);
        pump.advance(0f);
        assertFalse(session.lobbyReleased(), "the host must not start the session by itself");
        assertTrue(sector.paused, "and the world stays held while the window runs");

        // The guest comes back inside the window.
        service.connected = true;
        service.sent.clear();
        service.inbound.add(CoopMessages.sessionResumeRequest("session-a", 9L, now.get(), "guest-player"));
        pump.advance(0f);

        assertFalse(pump.reconnectCoordinatorForTest().active());
        assertFalse(session.lobbyReleased(), "the lobby is still the lobby; nobody pressed Start");
        assertFalse(pump.pauseCoordinatorForBridge().hostPauseIntent(),
                "F2: the lobby hold is coop's, and it must never latch as the host player's pause");
        assertNotNull(lastOfType(service, CoopMessages.Type.LOBBY_STATUS),
                "the returning guest is sent the roster again");
    }

    /**
     * Item 4. {@code READY_STATE} carries the phase and the ready flag together, so a guest that
     * reaches SNAPSHOT_APPLIED and readies on the same frame leaves only the READY report behind. The
     * roster refuses READY as a phase, so the row used to stay at SEED_LOCKED - and the ready report
     * was then thrown away for arriving "too early" against the phase it was itself carrying.
     */
    @Test
    void aReadyReportThatOutrunsThePhaseFloorStillReadiesTheRow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = lobbyHostSession();
        Global.setSector(new RecordingSector(false, new RecordingCampaignUi(null)).proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        // Both reports in one drain; only the last one survives into the pending slot.
        service.inbound.add(CoopMessages.readyState("session-a", 5L, 1000L, "SNAPSHOT_APPLIED", false));
        service.inbound.add(CoopMessages.readyState("session-a", 6L, 1000L, "READY", true));
        pump.advance(0f);

        assertTrue(pump.lobbyRosterForTest().allReady(),
                "the ready the guest sent is the ready the host gates on");
        // READY reaches the row through setReady, which is the only writer allowed to put it there;
        // the derived phase floor clamps to SNAPSHOT_APPLIED so it can never do it on the guest's
        // behalf, and so the ready report is not refused for arriving with the phase it carries.
        assertEquals(coop.session.CoopJoinPhase.READY,
                pump.lobbyRosterForTest().row("guest-player").phase());
    }

    /**
     * Item 7. A campaign UI that throws out of {@code showInteractionDialog} used to make the pump
     * rebuild the dialog plugin and re-request it every frame, which reset the once-only WARN and
     * turned a broken UI into a per-frame log flood.
     */
    @Test
    void aUiThatWillNotOpenTheDesyncDialogIsAskedOncePerSecondAndLoggedOnce() {
        LogCapture appender = LogCapture.attach(coop.ui.CoopDialogController.class);
        try {
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            CoopSessionState session = lobbyHostSession();
            RecordingCampaignUi ui = new RecordingCampaignUi(null);
            ui.throwOnShowInteractionDialog = true;
            Global.setSector(new RecordingSector(false, ui).proxy());
            CoopNetPump pump = livePump(service, session, () -> 1000L);

            service.inbound.add(CoopMessages.seedLockAck("session-a", 6L, 1000L, "fingerprint-guest"));
            for (int frame = 0; frame < 100; frame++) {
                pump.advance(0f);
            }

            assertTrue(pump.desyncDialogRequestedForTest(),
                    "the reason is still owed to the player, so the request is kept");
            List<Object> desyncAttempts = ui.shownDialogs.stream()
                    .filter(shown -> shown instanceof coop.ui.CoopDesyncDialog)
                    .toList();
            assertEquals(1, desyncAttempts.size(),
                    "a hundred frames inside the one-second backoff is one attempt");
            assertEquals(1, new java.util.HashSet<>(desyncAttempts).size(),
                    "and one plugin instance, never rebuilt per frame");
            assertEquals(1, appender.messages().stream()
                            .filter(line -> line.contains("could not open the desync dialog")).count(),
                    "one WARN per request, not one per frame");
        } finally {
            appender.detach();
        }
    }

    /**
     * Item 8. A manifest the mod cannot capture used to reject the handshake with no marker, no
     * dialog and no brake, leaving the guest dialling every five seconds against a local failure that
     * retrying cannot fix.
     */
    @Test
    void aManifestThatCannotBeCapturedIsADesyncAndNotASilentRetryLoop() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        Global.setSector(new RecordingSector(false, new RecordingCampaignUi(null)).proxy());
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L,
                () -> {
                    throw new IllegalStateException("mod list unreadable");
                },
                () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed");

        pump.advance(0f);

        assertNotNull(pump.desyncReasonForTest(), "the player is told why the join stopped");
        assertTrue(pump.desyncDialogRequestedForTest());
        assertEquals(1, service.stopReconnectingReasons.size(), "no 5 s retry against a local failure");
        assertTrue(session.rejectTerminal());
    }

    /**
     * Item 10. {@code sendShipLost} is a guest-to-host message, and the only call site sat behind the
     * stats tick's HOST gate - so a guest could lose a fleet and the losses page never heard of it.
     */
    @Test
    void theGuestSettlesItsOwnHullLossesAndReportsThem() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        RecordingFleet fleet = new RecordingFleet();
        fleet.add("member-1", "ISS Bad Idea");
        fleet.add("member-2", "ISS Survivor");
        Global.setSector(new RecordingSector(false, null, fleet).proxy());
        CoopNetPump pump = activeGuestPump(service, now::get);
        advancePastTheHeadlessLobbyValve(pump);

        pump.battleBegunForTest();
        fleet.remove("member-1");
        pump.battleConcludedForTest();
        now.addAndGet(2_000L);
        pump.advance(0f);

        CoopMessages.Message lost = lastOfType(service, CoopMessages.Type.SHIP_LOST);
        assertEquals("ISS Bad Idea", CoopMessages.parseShipLost(lost).hullName());
        assertEquals("guest-player", CoopMessages.parseShipLost(lost).playerId());
    }

    /**
     * Item 14. The pre-battle capture cleared the roster unconditionally, so a battle that began
     * before the previous one had settled took the previous one's losses with it.
     */
    @Test
    void aBattleThatBeginsBeforeTheLastOneSettlesKeepsTheFirstBattlesLosses() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingFleet fleet = new RecordingFleet();
        fleet.add("member-1", "ISS Bad Idea");
        fleet.add("member-2", "ISS Survivor");
        Global.setSector(new RecordingSector(false, null, fleet).proxy());
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        pump.battleBegunForTest();
        fleet.remove("member-1");
        pump.battleConcludedForTest();
        // No frame in between: the next engagement starts before the diff has been taken.
        pump.battleBegunForTest();

        CoopSessionStats stats = pump.sessionStatsForTest();
        assertEquals(1L, stats.player("host-player").shipsLost(),
                "the first battle's losses are settled, not discarded");
        assertEquals("ISS Bad Idea", stats.shipLossLedger().get(0).hullName());
    }

    /**
     * Item 12. There is no net-worth API and no way to value the guest's fleet from the host, so the
     * column is credits - and the guest's credits only ever reach the host inside GUEST_SNAPSHOT.
     * Sampling only the host's fleet left the guest's column reading zero for the whole session.
     */
    @Test
    void theGuestsNetWorthIsTakenFromTheSnapshotThatCarriesIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);
        coop.save.CoopGuestSnapshot snapshot = new coop.save.CoopGuestSnapshot();
        snapshot.setSessionId("session-a");
        snapshot.setPlayerId("guest-player");
        snapshot.setPlayerName("Guest");
        snapshot.setCredits(4242d);

        service.inbound.add(CoopMessages.guestSnapshot("session-a", 8L, 1000L, snapshot.encodeBody()));
        pump.advance(0f);

        assertEquals(4242L, pump.sessionStatsForTest().player("guest-player").netWorthCredits());
    }

    /** A player fleet whose membership a test can change between the two edges of a battle. */
    private static final class RecordingFleet {
        private final List<Object[]> members = new ArrayList<>();
        /** Containing-location id, or null for a fleet the stats sampler cannot place. */
        private String locationId;

        private void add(String id, String shipName) {
            members.add(new Object[]{id, shipName});
        }

        private void remove(String id) {
            members.removeIf(member -> member[0].equals(id));
        }

        private com.fs.starfarer.api.campaign.CampaignFleetAPI proxy() {
            return (com.fs.starfarer.api.campaign.CampaignFleetAPI) Proxy.newProxyInstance(
                    com.fs.starfarer.api.campaign.CampaignFleetAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.campaign.CampaignFleetAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        return switch (method.getName()) {
                            case "getFleetData" -> fleetDataProxy();
                            case "getContainingLocation" -> locationProxy(locationId);
                            case "isAlive" -> true;
                            case "getLocation", "getCargo" -> null;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        /** A containing location with nothing but an id — all the stats sampler reads. */
        private static com.fs.starfarer.api.campaign.LocationAPI locationProxy(String locationId) {
            if (locationId == null) {
                return null;
            }
            return (com.fs.starfarer.api.campaign.LocationAPI) Proxy.newProxyInstance(
                    com.fs.starfarer.api.campaign.LocationAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.campaign.LocationAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        if ("getId".equals(method.getName())) {
                            return locationId;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private com.fs.starfarer.api.campaign.FleetDataAPI fleetDataProxy() {
            return (com.fs.starfarer.api.campaign.FleetDataAPI) Proxy.newProxyInstance(
                    com.fs.starfarer.api.campaign.FleetDataAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.campaign.FleetDataAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        if ("getMembersListCopy".equals(method.getName())) {
                            List<com.fs.starfarer.api.fleet.FleetMemberAPI> copy = new ArrayList<>();
                            for (Object[] member : members) {
                                copy.add(memberProxy((String) member[0], (String) member[1]));
                            }
                            return copy;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private static com.fs.starfarer.api.fleet.FleetMemberAPI memberProxy(String id, String shipName) {
            return (com.fs.starfarer.api.fleet.FleetMemberAPI) Proxy.newProxyInstance(
                    com.fs.starfarer.api.fleet.FleetMemberAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class},
                    (proxy, method, args) -> {
                        Object objectMethod = objectMethodResult(proxy, method.getName(), args);
                        if (objectMethod != UNHANDLED) {
                            return objectMethod;
                        }
                        return switch (method.getName()) {
                            case "getId" -> id;
                            case "getShipName" -> shipName;
                            // Null hull spec: describeHull already treats an undescribable member as
                            // a hull with no class, which is all this test needs it to be.
                            case "getHullSpec" -> null;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }

    private static int countSent(RecordingNetService service, CoopMessages.Type type) {
        int found = 0;
        for (CoopMessages.Message message : service.sent) {
            if (message.type() == type) {
                found++;
            }
        }
        return found;
    }

    // ---- Phase 28 milestone 2: host policy options -----------------------------------------------

    /** The core tab the fake UI reports as open; any non-null value is "a core tab is up". */
    private static final Object CORE_TAB = com.fs.starfarer.api.campaign.CoreUITabId.MAP;

    private static CoopMessages.Message lastPauseIntent(RecordingNetService service) {
        return lastOfType(service, CoopMessages.Type.PAUSE_INTENT);
    }

    private static CoopMessages.Message optionsSnapshotFor(String key, String value, int version) {
        return CoopMessages.optionsSnapshot("session-a", 99L, 1000L,
                java.util.Map.of(key, value), version, key);
    }

    @Test
    void theHostBroadcastsThePolicyOnceTheSessionIsPlayable() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeHostSession(), new AtomicLong(1000L)::get);

        pump.advance(0f);
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.OPTIONS_SNAPSHOT),
                "one snapshot behind the release, and not one per frame after it");
        CoopMessages.OptionsSnapshot snapshot = CoopMessages.parseOptionsSnapshot(
                lastOfType(service, CoopMessages.Type.OPTIONS_SNAPSHOT));
        assertEquals(coop.config.CoopOptionsPolicy.FIRST_VERSION, snapshot.policyVersion());
        assertEquals("true",
                snapshot.values().get(coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));
        assertEquals("", snapshot.changedKey(), "seeding a campaign is not a change to narrate");
    }

    @Test
    void theHostSeedsThisCampaignsPolicyIntoItsPersistentData() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        RecordingSector sector = new RecordingSector(false);
        Global.setSector(sector.proxy());
        CoopNetPump pump = livePump(service, activeHostSession(), new AtomicLong(1000L)::get);

        pump.advance(0f);

        assertEquals("true", sector.persistentData.get(
                coop.config.CoopOptionsPolicy.PERSIST_PREFIX
                        + coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));
        assertEquals("1", sector.persistentData.get(
                coop.config.CoopOptionsPolicy.PERSIST_VERSION_KEY));
    }

    @Test
    void aHostChangeSendsANewSnapshotNamingTheChangedKey() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeHostSession(), new AtomicLong(1000L)::get);
        pump.advance(0f);

        assertTrue(pump.optionsPolicy().set(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false"));
        pump.advance(0f);

        assertEquals(2, countOfType(service, CoopMessages.Type.OPTIONS_SNAPSHOT));
        CoopMessages.OptionsSnapshot snapshot = CoopMessages.parseOptionsSnapshot(
                lastOfType(service, CoopMessages.Type.OPTIONS_SNAPSHOT));
        assertEquals(coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, snapshot.changedKey());
        assertEquals("false",
                snapshot.values().get(coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));
        assertEquals(coop.config.CoopOptionsPolicy.FIRST_VERSION + 1, snapshot.policyVersion());
    }

    @Test
    void theGuestAdoptsTheHostPolicyAndRefusesAStaleOne() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);
        service.inbound.add(optionsSnapshotFor(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false", 4));

        pump.advance(0f);

        assertEquals("false", pump.optionsPolicy().effective(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));
        assertEquals(4, pump.optionsPolicy().version());

        service.inbound.add(optionsSnapshotFor(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "true", 3));
        pump.advance(0f);

        assertEquals("false", pump.optionsPolicy().effective(
                        coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS),
                "a stale snapshot after a resume must not walk the policy backwards");
        assertEquals(4, pump.optionsPolicy().version());
    }

    @Test
    void aGuestNeverSendsAnOptionsSnapshot() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);

        pump.advance(0f);
        pump.advance(0f);

        assertEquals(0, countOfType(service, CoopMessages.Type.OPTIONS_SNAPSHOT));
    }

    // ---- Phase 28 milestone 2: coop.pauseOnGuestScreens -------------------------------------------

    @Test
    void byDefaultAGuestsCoreTabStillPausesTheSharedWorld() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);
        pump.advance(0f);
        assertEquals(0, countOfType(service, CoopMessages.Type.PAUSE_INTENT));

        ui.coreTab = CORE_TAB;
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.PAUSE_INTENT));
        assertEquals("true", CoopMessages.requiredPayloadString(lastPauseIntent(service), "paused"));
        assertEquals("SCREEN", CoopMessages.requiredPayloadString(lastPauseIntent(service), "source"));
    }

    @Test
    void aPolicyFlipNeverYanksThePauseOutFromUnderAnOpenScreen() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);

        // The guest opens a core tab under the default policy: the shared world stops.
        ui.coreTab = CORE_TAB;
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.PAUSE_INTENT));
        assertEquals("true", CoopMessages.requiredPayloadString(lastPauseIntent(service), "paused"));

        // The host turns the option off while that tab is still open.
        service.inbound.add(optionsSnapshotFor(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false", 2));
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.PAUSE_INTENT),
                "the pause the guest is already holding must survive the flip");
        assertTrue(pump.optionsPolicy().hasPendingChange(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));

        // Closing the tab is the boundary: the pause lifts and the new value takes effect.
        ui.coreTab = null;
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.PAUSE_INTENT));
        assertEquals("false", CoopMessages.requiredPayloadString(lastPauseIntent(service), "paused"));
        assertFalse(pump.optionsPolicy().hasPendingChange(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));

        // And the next open does not pause at all.
        ui.coreTab = CORE_TAB;
        pump.advance(0f);
        pump.advance(0f);
        assertEquals(2, countOfType(service, CoopMessages.Type.PAUSE_INTENT),
                "with the option off a core tab is a local view, not a reason to stop the world");
    }

    @Test
    void interactionDialogsStillPauseWhateverThePolicySays() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);
        service.inbound.add(optionsSnapshotFor(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false", 2));
        pump.advance(0f);
        assertEquals(0, countOfType(service, CoopMessages.Type.PAUSE_INTENT));

        // The in-game menu is the other hardwired intent; neither is behind the option.
        ui.showingMenu = true;
        pump.advance(0f);

        assertEquals(1, countOfType(service, CoopMessages.Type.PAUSE_INTENT));
        assertEquals("true", CoopMessages.requiredPayloadString(lastPauseIntent(service), "paused"));
    }

    @Test
    void aGuestGoesBackToTheSafeDefaultWhenTheSessionEnds() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = activeGuestSession();
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, session, new AtomicLong(1000L)::get);
        service.inbound.add(optionsSnapshotFor(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "false", 2));
        pump.advance(0f);
        assertEquals("false", pump.optionsPolicy().effective(
                coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));

        session.reset();
        pump.advance(0f);

        assertEquals("true", pump.optionsPolicy().effective(
                        coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS),
                "a campaign that keeps running solo is not still under the host's rules");
        assertEquals(0, pump.optionsPolicy().version());
    }

    // ---- review item 2: the host's pending is "sent, not yet acknowledged" ------------------------

    private static final String PAUSE_KEY = coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS;

    @Test
    void theGuestAcknowledgesOncePerVersionWhenItsBoundaryHasBeenCrossed() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingCampaignUi ui = new RecordingCampaignUi(null);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);

        // The guest is in a core tab when the flip arrives: the boundary has not come round.
        ui.coreTab = CORE_TAB;
        service.inbound.add(optionsSnapshotFor(PAUSE_KEY, "false", 2));
        pump.advance(0f);
        pump.advance(0f);
        assertEquals(0, countOfType(service, CoopMessages.Type.OPTIONS_APPLIED),
                "nothing to acknowledge while the change is still pending here");

        // Closing the tab crosses it, and that is what the host is waiting to hear.
        ui.coreTab = null;
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.OPTIONS_APPLIED));
        assertEquals(2, CoopMessages.parseOptionsApplied(
                lastOfType(service, CoopMessages.Type.OPTIONS_APPLIED)).policyVersion());

        pump.advance(0f);
        pump.advance(0f);
        assertEquals(1, countOfType(service, CoopMessages.Type.OPTIONS_APPLIED),
                "once per version, not once per frame");
    }

    @Test
    void theHostHoldsItsPendingUntilTheGuestAcknowledgesInEitherDirection() {
        for (String from : new String[]{"true", "false"}) {
            String to = "true".equals(from) ? "false" : "true";
            RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
            Global.setSector(new RecordingSector(false).proxy());
            CoopNetPump pump = livePump(service, activeHostSession(), new AtomicLong(1000L)::get);
            pump.advance(0f);

            // Establish the starting value and let it settle (the guest acknowledges v1/v2).
            pump.optionsPolicy().set(PAUSE_KEY, from);
            pump.advance(0f);
            service.inbound.add(CoopMessages.optionsApplied("session-a", 50L, 1000L,
                    pump.optionsPolicy().version()));
            pump.advance(0f);
            assertFalse(pump.optionsPolicy().hasPendingChanges(), from + " did not settle");

            // The flip the guest has not answered yet. Nothing about the guest's own screen state
            // may promote this: with the option off the guest holds no screen pause at all, and
            // that used to be read as "the guest has nothing open, promote now".
            pump.optionsPolicy().set(PAUSE_KEY, to);
            pump.advance(0f);
            pump.advance(0f);
            assertTrue(pump.optionsPolicy().hasPendingChange(PAUSE_KEY),
                    from + " -> " + to + " must stay pending until the guest says otherwise");
            assertEquals(from, pump.optionsPolicy().applied(PAUSE_KEY));

            service.inbound.add(CoopMessages.optionsApplied("session-a", 51L, 1000L,
                    pump.optionsPolicy().version()));
            pump.advance(0f);
            assertFalse(pump.optionsPolicy().hasPendingChange(PAUSE_KEY), from + " -> " + to);
            assertEquals(to, pump.optionsPolicy().applied(PAUSE_KEY));
        }
    }

    @Test
    void aHostWithNoGuestPromotesItsOwnChangeRatherThanHangingOnPending() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        service.connected = false;
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeHostSession(), new AtomicLong(1000L)::get);
        pump.advance(0f);

        pump.optionsPolicy().set(PAUSE_KEY, "false");
        pump.advance(0f);

        assertFalse(pump.optionsPolicy().hasPendingChange(PAUSE_KEY),
                "with nobody to acknowledge, a pending change would sit on the page forever");
        assertEquals("false", pump.optionsPolicy().applied(PAUSE_KEY));
    }

    // ---- review item 7: the guest's boundary is core tabs, not "anything at all" ------------------

    @Test
    void aLongInteractionDialogDoesNotHoldTheBoundaryOpen() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);

        service.inbound.add(optionsSnapshotFor(PAUSE_KEY, "false", 2));
        pump.advance(0f);
        pump.advance(0f);

        assertFalse(pump.optionsPolicy().hasPendingChange(PAUSE_KEY),
                "no core tab is open, so the boundary is crossed; the dialog is not what this key"
                        + " governs and it pauses regardless");
        assertEquals("true", CoopMessages.requiredPayloadString(lastPauseIntent(service), "paused"),
                "the dialog's own pause is hardwired and survives the flip");
    }

    // ---- review item 5: a policy reset is narrated on both sides ----------------------------------

    @Test
    void aHostSideResetPostsOneFeedLine() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeHostSession(), new AtomicLong(1000L)::get);
        pump.advance(0f);
        pump.optionsPolicy().set(PAUSE_KEY, "false");
        pump.optionsPolicy().set(coop.config.CoopOptionsRegistry.ALLOW_GUEST_PAUSE, "false");
        pump.advance(0f);

        pump.optionsPolicy().resetToDefaults();
        pump.advance(0f);

        assertEquals(1, feedLinesSaying(coop.ui.CoopOptionsView.RESET_LINE),
                "the one action on the page that changes the most used to be the only silent one");
    }

    @Test
    void aGuestNarratesTheHostsResetOnceAndStaysQuietOnTheResumeResend() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        Global.setSector(new RecordingSector(false).proxy());
        CoopNetPump pump = livePump(service, activeGuestSession(), new AtomicLong(1000L)::get);
        service.inbound.add(optionsSnapshotFor(PAUSE_KEY, "false", 2));
        pump.advance(0f);

        service.inbound.add(CoopMessages.optionsSnapshot("session-a", 98L, 1000L,
                java.util.Map.of(PAUSE_KEY, "true"), 3,
                coop.config.CoopOptionsPolicy.RESET_MARKER));
        pump.advance(0f);
        assertEquals(1, feedLinesSaying(coop.ui.CoopOptionsView.RESET_LINE));

        // The resume re-send carries the same marker but changes nothing here.
        service.inbound.add(CoopMessages.optionsSnapshot("session-a", 99L, 1000L,
                java.util.Map.of(PAUSE_KEY, "true"), 3,
                coop.config.CoopOptionsPolicy.RESET_MARKER));
        pump.advance(0f);
        assertEquals(1, feedLinesSaying(coop.ui.CoopOptionsView.RESET_LINE),
                "a re-send of values we already hold is not a new event");
    }

    private static long feedLinesSaying(String text) {
        return coop.ui.CoopSessionIntelFeed.currentModel().events().stream()
                .filter(e -> e.line().equals(text))
                .count();
    }

    // ---- net-fix-2: the drop edge and the messages that crossed it -------------------------------

    /**
     * A resume accept can reach the guest with no socket under it: it arrived on the pre-drop
     * connection, was parked by {@code drainTerminalRejectsBeforeDrop}, and is dispatched after the
     * drop edge that killed the link. Resuming on it unpauses the guest against a host that cannot
     * hear it, until the host's 15 s handshake deadline forces a fresh drop edge.
     */
    @Test
    void aResumeAcceptWithNoLiveSocketDoesNotEndTheReconnectWindow() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeGuestPump(service, now::get);

        pump.advance(0f);
        service.connected = false;
        pump.advance(0f);
        assertTrue(pump.reconnectCoordinatorForTest().guestReconnecting(), "the window opened");

        service.inbound.add(CoopMessages.sessionResumeAccept("session-a", 3L, 2000L));
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().guestReconnecting(),
                "an accept with no connection under it must not resume the session");
        assertTrue(pump.pauseCoordinatorForBridge().reconnectHold(), "the hold stands");

        // ...and the next real socket gets the request again, which is what actually proves the link.
        service.connected = true;
        pump.advance(0f);
        service.inbound.add(CoopMessages.sessionResumeAccept("session-a", 4L, 3000L));
        pump.advance(0f);
        assertFalse(pump.reconnectCoordinatorForTest().active(),
                "the same accept on a live socket resumes normally");
    }

    /**
     * The parked messages arrived over the connection that WAS the partner, before the drop edge
     * existed. The grace whitelist exists to filter whoever holds the slot after it, and used to
     * swallow the partner's last campaign deltas — MARKET_TXN, COLONY_*, WORLD_DELTA consume — which
     * nothing else heals, in either direction.
     */
    @Test
    void aPreDropCampaignMessageIsStillAppliedAfterTheGraceWindowOpens() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);

        pump.advance(0f);
        // Queued before the drop is noticed, so the pre-drain parks it as proven.
        service.inbound.add(new CoopMessages.Message(
                CoopMessages.Type.MARKET_TXN, "session-a", 9L, 1500L, "{}"));
        service.connected = false;
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(), "the window opened");
        assertEquals(1L, pump.preDropMessagesAppliedForTest(),
                "the partner's last campaign message must survive the drop edge");
        assertEquals(0L, pump.preDropMessagesDiscardedForTest());
    }

    /**
     * The other side of the same rule: the drop edge runs {@code resetInteractionState()}, so a claim
     * parked before it would re-create the permanent "Remote player is interacting" lock that reset
     * exists to clear.
     */
    @Test
    void aPreDropInteractionMessageIsDiscardedBecauseTheDropEdgeAlreadyResetIt() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        AtomicLong now = new AtomicLong(1000L);
        CoopNetPump pump = activeHostPump(service, now::get);

        pump.advance(0f);
        service.inbound.add(CoopMessages.interactionClaim("session-a", 9L, 1500L, "market-a",
                "guest-player", "Guest"));
        service.connected = false;
        pump.advance(0f);

        assertEquals(0L, pump.preDropMessagesAppliedForTest());
        assertEquals(1L, pump.preDropMessagesDiscardedForTest(),
                "a claim the drop edge already released must not come back");
    }

    // ---- net-fix-4: SHIP_LOST is not an open column factory --------------------------------------

    @Test
    void shipLostIsIgnoredBeforeTheSessionIsActive() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        // A host with no session at all: the listener is open, so this is what a stranger reaches.
        CoopNetPump pump = new CoopNetPump(service, () -> 1000L);

        service.inbound.add(CoopMessages.shipLost("session-a", 13L, 1000L, "guest-player",
                "ISS Bad Idea", "Wolf", "Corvus", 42.5f, "battle"));
        pump.advance(0f);

        assertTrue(pump.sessionStatsForTest().playerIds().isEmpty(),
                "a pre-session SHIP_LOST must not mint a stats column");
    }

    @Test
    void shipLostForAnUnknownPlayerIdIsIgnored() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        CoopNetPump pump = livePump(service, session, () -> 1000L);

        service.inbound.add(CoopMessages.shipLost("session-a", 13L, 1000L, "not-in-this-session",
                "ISS Bad Idea", "Wolf", "Corvus", 42.5f, "battle"));
        pump.advance(0f);

        assertFalse(pump.sessionStatsForTest().playerIds().contains("not-in-this-session"),
                "only the session's own player ids may open a column");
        assertTrue(pump.sessionStatsForTest().shipLossLedger().isEmpty());
    }

    // ---- net-fix-10: the adopt-campaign gesture is one-shot --------------------------------------

    @Test
    void adoptingTheHostCampaignIdConsumesTheConsent() {
        java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>("campaign-old");
        java.util.concurrent.atomic.AtomicInteger consumed = new java.util.concurrent.atomic.AtomicInteger();
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host",
                "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, true, false, "fingerprint-host", () -> "",
                consumed::incrementAndGet).advance(0f);

        assertEquals("campaign-host", stored.get());
        assertEquals(1, consumed.get(),
                "the player consented to one adoption, not to a process-lifetime licence");
    }

    @Test
    void aLockThatDoesNotAdoptLeavesTheConsentIntact() {
        java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>("campaign-host");
        java.util.concurrent.atomic.AtomicInteger consumed = new java.util.concurrent.atomic.AtomicInteger();
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = guestSessionReadyForSeedLock();
        // The stored id already matches, so checkOrAdoptCampaignId returns before any adoption.
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host",
                "campaign-host", false));

        pumpForGuestSeedLock(service, session, stored, true, false, "fingerprint-host", () -> "",
                consumed::incrementAndGet).advance(0f);

        assertEquals(0, consumed.get(), "a lock that adopted nothing must not burn the gesture");
    }

    @Test
    void aSecondPumpAfterTheConsentWasConsumedDoesNotAdopt() {
        // The property is the gesture and a new pump is built on every game load, so this stands for
        // "adopt once, then load a different co-op save from the main menu".
        java.util.concurrent.atomic.AtomicReference<Boolean> consent =
                new java.util.concurrent.atomic.AtomicReference<>(Boolean.TRUE);
        java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>("campaign-old");

        RecordingNetService first = new RecordingNetService(CoopConnectionRole.GUEST);
        first.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host",
                "campaign-host", false));
        new CoopNetPump(first, guestSessionReadyForSeedLock(), () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host", () -> "coop-seed", new CoopTimeLock(),
                stored::get, stored::set, consent::get, () -> "", () -> false,
                () -> consent.set(Boolean.FALSE)).advance(0f);
        assertEquals("campaign-host", stored.get(), "the first load adopts");

        // A different save, loaded in the same process.
        java.util.concurrent.atomic.AtomicReference<String> otherSave =
                new java.util.concurrent.atomic.AtomicReference<>("campaign-somewhere-else");
        RecordingNetService second = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState secondSession = guestSessionReadyForSeedLock();
        second.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host",
                "campaign-host", false));
        new CoopNetPump(second, secondSession, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host", () -> "coop-seed", new CoopTimeLock(),
                otherSave::get, otherSave::set, consent::get, () -> "", () -> false,
                () -> consent.set(Boolean.FALSE)).advance(0f);

        assertEquals("campaign-somewhere-else", otherSave.get(),
                "the consumed gesture must not silently re-adopt onto the next save");
        assertEquals(CoopLobbyState.REJECTED, secondSession.connectionState());
    }
}
