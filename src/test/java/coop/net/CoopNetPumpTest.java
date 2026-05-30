package coop.net;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import coop.handshake.CoopHandshakeManifest;
import coop.seed.CoopSeedSync;
import coop.session.CoopLobbyState;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.time.CoopTimeLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopNetPumpTest {
    @AfterEach
    void clearGlobalSector() {
        Global.setSector(null);
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
        CoopNetPump pump = new CoopNetPump(service, () -> 5000L);

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
    }

    @Test
    void guestRejectsSeedLockWhenLocalFingerprintDiffers() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.GUEST);
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        service.inbound.add(CoopMessages.seedLockRequest(
                "session-a", 4L, 13000L, 123456789L, "coop-seed", "fingerprint-host"));
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
                "session-a", 4L, 13000L, 123456789L, "MN-host", "fingerprint-host"));
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
                new CoopTimeLock.TimeSnapshot(true, false, 222333444L, 17L, 1200L));
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
                new CoopTimeLock.TimeSnapshot(true, true, 222333444L, 17L, 1500L);
        service.inbound.add(CoopMessages.timeSnapshot("session-a", 5L,
                hostSnapshot.paused(),
                hostSnapshot.fastForward(),
                hostSnapshot.timestampMillis(),
                hostSnapshot.campaignDay(),
                hostSnapshot.sentAtMillis()));
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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L)));

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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L)));

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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L));
        CoopNetPump pump = new CoopNetPump(service, session, () -> 1000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> "fingerprint-host",
                () -> "coop-seed",
                timeLock);

        pump.advance(0f);

        assertTrue(sector.paused);

        sector.paused = false;
        session.hostAcceptGuest(new CoopPlayerInfo("guest-player", "Guest"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
        pump.advance(0f);

        assertFalse(sector.paused);
    }

    @Test
    void hostBroadcastsAcceptedLocalInteractionAndReleaseOnDialogClose() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = activeHostSession();
        RecordingEntity entity = new RecordingEntity("market-1", "Jangala");
        RecordingCampaignUi ui = new RecordingCampaignUi(entity);
        Global.setSector(new RecordingSector(false, ui).proxy());
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, new RecordingTimeLock(
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L)));

        pump.advance(0f);

        assertEquals(1, service.sent.size());
        CoopMessages.Message accept = service.sent.get(0);
        assertEquals(CoopMessages.Type.INTERACTION_ACCEPT, accept.type());
        assertEquals("market-1", CoopMessages.requiredPayloadString(accept, "entityId"));
        assertEquals("host-player", CoopMessages.requiredPayloadString(accept, "playerId"));
        assertEquals("Jangala", CoopMessages.requiredPayloadString(accept, "entityName"));
        assertEquals(1L, CoopMessages.requiredPayloadLong(accept, "hostSeq"));

        ui.target = null;
        pump.advance(0f);

        assertEquals(2, service.sent.size());
        CoopMessages.Message release = service.sent.get(1);
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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L)));
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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L));
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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L));
        CoopNetPump pump = pumpWithTimeLock(service, session, () -> 1000L, timeLock);

        service.inbound.add(CoopMessages.interactionAccept(
                "session-a", 8L, 1200L, "market-1", "host-player", "Jangala", 5L));
        pump.advance(0f);
        pump.advance(0f);

        assertEquals(2, ui.disallowInteractionCount);
        assertEquals(List.of("Remote player is interacting: Jangala"), ui.messages);
        assertEquals(List.of(
                new InteractionBlock(true, "Jangala"),
                new InteractionBlock(true, "Jangala")), timeLock.interactionBlocks);

        service.inbound.add(CoopMessages.interactionRelease(
                "session-a", 9L, 1300L, "market-1", "host-player"));
        pump.advance(0f);

        assertEquals(new InteractionBlock(false, null),
                timeLock.interactionBlocks.get(timeLock.interactionBlocks.size() - 1));
    }

    private static final class RecordingNetService extends CoopNetService {
        private final CoopConnectionRole role;
        private final Queue<CoopMessages.Message> inbound = new ArrayDeque<>();
        private final List<CoopMessages.Message> sent = new ArrayList<>();

        private RecordingNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(CoopMessages.Message message) {
            sent.add(message);
        }

        @Override
        public CoopMessages.Message pollInbound() {
            return inbound.poll();
        }

        @Override
        public void flushOutbound() {
        }
    }

    private static final class RecordingSector {
        private boolean paused;
        private final RecordingCampaignUi campaignUi;

        private RecordingSector(boolean paused) {
            this(paused, null);
        }

        private RecordingSector(boolean paused, RecordingCampaignUi campaignUi) {
            this.paused = paused;
            this.campaignUi = campaignUi;
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
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    private static final class RecordingCampaignUi {
        private RecordingEntity target;
        private boolean showingMenu;
        private int disallowInteractionCount;
        private final List<String> messages = new ArrayList<>();

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
                                return null;
                            }
                            case "setDisallowPlayerInteractionsForOneFrame" -> {
                                disallowInteractionCount++;
                                return null;
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

    private record InteractionBlock(boolean blocked, String entityName) {
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
        return session;
    }

    private static CoopSessionState activeGuestSession() {
        CoopSessionState session = new CoopSessionState(() -> "guest-player");
        session.startGuest("Guest");
        session.guestAcceptLobby("lobby-a", new CoopPlayerInfo("host-player", "Host"));
        session.guestAcceptHandshake("session-a");
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
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

    private static final class RecordingTimeLock extends CoopTimeLock {
        private final CoopTimeLock.TimeSnapshot snapshot;
        private final List<CoopTimeLock.TimeSnapshot> applied = new ArrayList<>();
        private final List<Boolean> inputBlockerStates = new ArrayList<>();
        private final List<InteractionBlock> interactionBlocks = new ArrayList<>();

        private RecordingTimeLock(CoopTimeLock.TimeSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public CoopTimeLock.TimeSnapshot capture(long sentAtMillis) {
            return new CoopTimeLock.TimeSnapshot(
                    snapshot.paused(),
                    snapshot.fastForward(),
                    snapshot.timestampMillis(),
                    snapshot.campaignDay(),
                    sentAtMillis);
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
        public void setInteractionBlocked(boolean blocked, String entityName) {
            interactionBlocks.add(new InteractionBlock(blocked, entityName));
        }
    }
}
