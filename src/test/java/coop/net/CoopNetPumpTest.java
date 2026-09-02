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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                new CoopTimeLock.TimeSnapshot(false, false, 222333444L, 17L, 1000L, "")));

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
        assertEquals(List.of(
                new InteractionBlock(true, "Jangala"),
                new InteractionBlock(true, "Jangala")), timeLock.interactionBlocks);

        service.inbound.add(CoopMessages.interactionRelease(
                "session-a", 9L, 1300L, "market-1", "host-player"));
        pump.advance(0f);

        assertEquals(new InteractionBlock(false, null),
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
        CoopNetPump pump = new CoopNetPump(service, () -> 1000L);

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
        CoopNetPump pump = new CoopNetPump(service, () -> 1000L);

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
        service.inbound.add(CoopMessages.ping("session-a", 2L, 1000L));

        pump.advance(0f);

        assertTrue(service.sent.stream().noneMatch(m -> m.type() == CoopMessages.Type.HANDSHAKE_RESULT),
                "an out-of-order manifest must not produce a handshake result");
        assertTrue(service.sent.stream().anyMatch(m -> m.type() == CoopMessages.Type.PONG),
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
        pumpForHostSeedLock(first, hostSessionReadyForSeedLock("session-a"), stored).advance(0f);
        CoopMessages.Message firstRequest = seedLockRequestIn(first);
        String minted = CoopMessages.requiredPayloadString(firstRequest, "campaignId");
        assertFalse(minted.isBlank());
        assertEquals(minted, stored.get(), "the minted id must be stored for the campaign's lifetime");
        assertEquals("true", CoopMessages.requiredPayloadString(firstRequest, "campaignIdMinted"),
                "the birth seed lock must announce the id as freshly minted");

        // A later session of the same campaign (fresh pump + session state) reuses the stored id.
        RecordingNetService second = new RecordingNetService(CoopConnectionRole.HOST);
        pumpForHostSeedLock(second, hostSessionReadyForSeedLock("session-b"), stored).advance(0f);
        CoopMessages.Message secondRequest = seedLockRequestIn(second);
        assertEquals(minted, CoopMessages.requiredPayloadString(secondRequest, "campaignId"));
        assertEquals("false", CoopMessages.requiredPayloadString(secondRequest, "campaignIdMinted"),
                "an in-flight campaign must not present as being born");
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

        service.inboundDatagrams.add(CoopMessages.datagram(token, "sender-udp",
                CoopMessages.Type.NPC_FLEET_MOTION, 4L, 0L, EMPTY_MOTION_BODY));
        service.inbound.add(CoopMessages.stateDatagram("session-a", 8L, 1_000L,
                CoopMessages.datagram(token, "sender-tcp",
                        CoopMessages.Type.NPC_FLEET_MOTION, 7L, 0L, EMPTY_MOTION_BODY)));
        pump.advance(0f);

        assertEquals(4L, pump.datagramWatermark()
                .watermarkFor("sender-udp", CoopMessages.Type.NPC_FLEET_MOTION));
        assertEquals(7L, pump.datagramWatermark()
                .watermarkFor("sender-tcp", CoopMessages.Type.NPC_FLEET_MOTION));
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
    void udpComingBackReturnsTheStreamToUdpAtFullCadenceAfterTheHysteresis() {
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

        for (long t = 12_500L; t <= 16_500L; t += 1_000L) {
            service.noteUdpInboundAt(t);
            now.set(t);
            pump.advance(0f);
        }
        assertTrue(pump.stateStreamFallbackActive(), "4 s of clear evidence is not yet enough");

        service.noteUdpInboundAt(17_600L);
        now.set(17_600L);
        pump.advance(0f);

        assertFalse(pump.stateStreamFallbackActive());
        assertEquals(100L, pump.stateStreamIntervalMillis());
        service.sent.clear();
        pump.sendStateDatagram(CoopMessages.datagram(CoopMessages.wireToken("session-a"), "host",
                CoopMessages.Type.FLEET_SNAPSHOT, 1L, 0L, "body"));
        assertEquals(1, service.datagrams.size());
        assertEquals(0, countOf(service, CoopMessages.Type.STATE_DATAGRAM));
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

    /** The host end of the same case: an empty proof is a wrong proof. */
    @Test
    void anEmptyProofFromAPasswordlessGuestIsRejected() {
        RecordingNetService service = new RecordingNetService(CoopConnectionRole.HOST);
        CoopSessionState session = new CoopSessionState(new SequencedIds("lobby-a", "host-player"));
        session.startHost("Host");
        service.inbound.add(CoopMessages.lobbyHello(1L, 7000L, new CoopPlayerInfo("guest-player", "Guest")));
        CoopNetPump pump = hostPumpWithPassword(service, session, PASSWORD);
        pump.advance(0f);

        // An explicit empty-string proof reads as "no proof" and gets challenged again rather than
        // rejected: the host cannot distinguish it from a first hello, and re-challenging is the
        // cheaper of the two mistakes.
        service.inbound.add(CoopMessages.lobbyHello(2L, 7100L,
                new CoopPlayerInfo("guest-player", "Guest"), ""));
        pump.advance(0f);

        assertEquals(2, countOfType(service, CoopMessages.Type.LOBBY_CHALLENGE));
        assertEquals(0, countOfType(service, CoopMessages.Type.LOBBY_ACCEPT));
        assertEquals(CoopLobbyState.HOST_WAITING, session.connectionState());
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
        return new CoopNetPump(service, session, () -> 14000L,
                () -> emptyManifest("0.98a-RC8", "commit-a"), () -> false,
                () -> new CoopSeedSync.SeedData(1L, "unused", "unused"),
                () -> guestFingerprint,
                () -> "coop-seed",
                new CoopTimeLock(),
                campaignIdStore::get, campaignIdStore::set, () -> adoptFlag, canonicalSupplier,
                () -> priorCoopSession);
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
        pump.pauseCoordinatorForBridge().applyGuestKeyPauseIntent(true, 1L);

        pump.advance(0f);
        now.set(1200L);
        pump.advance(0f);

        CoopMessages.Message snapshot = service.sent.stream()
                .filter(m -> m.type() == CoopMessages.Type.TIME_SNAPSHOT)
                .findFirst()
                .orElseThrow();
        assertEquals("guest", CoopMessages.requiredPayloadString(snapshot, "pausedBy"));
        assertEquals(List.of("guest"), timeLock.capturedPauseHolders);
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

        // Past the grace, the pre-20.2 teardown runs and the lobby rewinds to HOST_WAITING; only the
        // disconnect edge knows a live session was lost here.
        now.set(1000L + 61_000L);
        pump.advance(0f);

        assertEquals(CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING, pump.hudState(true).status());
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

        assertEquals(CoopHudState.STATUS_REJECTED, pump.hudState(false).status());
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
        service.inbound.add(CoopMessages.lobbyHello(11L, 2000L, new CoopPlayerInfo("guest-z", "Stranger")));
        pump.advance(0f);

        assertTrue(pump.reconnectCoordinatorForTest().hostWaiting(),
                "a stranger must not be able to end the wait early");
        assertEquals(2, countOf(service, CoopMessages.Type.SESSION_RESUME_REJECT));
        assertEquals(0, countOf(service, CoopMessages.Type.SESSION_RESUME_ACCEPT));
        assertEquals(1, countOf(service, CoopMessages.Type.LOBBY_REJECT));
        assertTrue(onlyOf(service, CoopMessages.Type.LOBBY_REJECT).payloadJson()
                .contains(CoopReconnectCoordinator.LOBBY_REJECT_IN_GRACE));
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
        service.connected = true;
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

        for (long t = 2_000L; t <= 120_000L; t += 1_000L) {
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
    private static final class StartupNetService extends CoopNetService {
        private CoopConnectionRole role = CoopConnectionRole.NONE;
        private int hostPort;

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public void startHost(int port) {
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
        public CoopMessages.Message pollInbound() {
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

        @Override
        public void setLocalSenderId(String playerId) {
        }
    }

    private static CoopNetPump activeHostPump(RecordingNetService service, java.util.function.LongSupplier clock) {
        CoopSessionState session = new CoopSessionState(
                new SequencedIds("lobby-a", "host-player", "session-a", "session-b"));
        session.startHost("Host");
        session.hostAcceptGuest(new CoopPlayerInfo("guest-a", "Guest A"));
        session.hostAcceptHandshake();
        session.recordSeedLock(123456789L, "coop-seed", "fingerprint-host");
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

    private static final String PLAYER_NAME_PROPERTY = "coop.playerName";

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

    private static class RecordingNetService extends CoopNetService {
        private final CoopConnectionRole role;
        final Queue<CoopMessages.Message> inbound = new ArrayDeque<>();
        private final List<CoopMessages.Message> sent = new ArrayList<>();
        /** Every setExpectedSessionToken call, nulls included — the clear is as load-bearing as the set. */
        private final List<String> expectedTokens = new ArrayList<>();
        /** Datagrams that went out over the real UDP path (Phase 20.1 M2 fallback tests). */
        private final List<String> datagrams = new ArrayList<>();
        private final Queue<String> inboundDatagrams = new ArrayDeque<>();
        private CoopDatagramStats stats = new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "");
        boolean connected = true;

        RecordingNetService(CoopConnectionRole role) {
            this.role = role;
        }

        @Override
        public CoopConnectionRole role() {
            return role;
        }

        @Override
        public boolean isConnected() {
            return connected;
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
        public void setExpectedSessionToken(String token) {
            expectedTokens.add(token);
        }

        @Override
        public void flushOutbound() {
        }

        @Override
        public void sendDatagram(String payload) {
            datagrams.add(payload);
        }

        @Override
        public String pollDatagram() {
            return inboundDatagrams.poll();
        }

        @Override
        public CoopDatagramStats datagramStats() {
            return stats;
        }

        private void noteUdpInboundAt(long atMillis) {
            stats = new CoopDatagramStats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, atMillis, "");
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
        private int dismissCount;
        /**
         * Phase 18: whether a {@code dismiss()} takes the dialog off screen. True models the engine
         * closing it; false models the frame(s) where the dialog is still up, which is what the
         * forced close has to keep re-asserting against.
         */
        private boolean dismissClosesDialog = true;
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

    private record InteractionBlock(boolean blocked, String entityName) {
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
        public void setInteractionBlocked(boolean blocked, String entityName) {
            interactionBlocks.add(new InteractionBlock(blocked, entityName));
        }
    }
}
