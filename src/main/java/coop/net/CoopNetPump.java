package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.fleet.CoopFleetMirror;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopFleetSnapshotFactory;
import coop.handshake.CoopHandshakeDiff;
import coop.handshake.CoopHandshakeManifest;
import coop.interaction.CoopInteractionClaim;
import coop.interaction.CoopInteractionGate;
import coop.seed.CoopSeedSync;
import coop.session.CoopIronModeGuard;
import coop.session.CoopLobbyState;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import coop.time.CoopTimeLock;
import coop.util.CoopLog;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class CoopNetPump implements EveryFrameScript {
    private static final long PING_INTERVAL_MILLIS = 3000L;
    // Campaign fleet snapshots stream at 10 Hz over UDP (COOP_MP_DESIGN.md section 8.4).
    private static final long FLEET_SNAPSHOT_INTERVAL_MILLIS = 100L;
    private static final String DEFAULT_PLAYER_FACTION_ID = "player";
    private static final String HOST_PORT_FLAG = "coop.hostPort";
    private static final String CONNECT_HOST_FLAG = "coop.connectHost";
    private static final String CONNECT_PORT_FLAG = "coop.connectPort";
    private static final String PLAYER_NAME_PROPERTY = "coop.playerName";

    private final CoopNetService service;
    private final CoopSessionState sessionState;
    private final LongSupplier clockMillis;
    private long nextPingAtMillis;
    private boolean startupConfigChecked;
    private boolean memoryConfigWarningLogged;
    private boolean lobbyHelloSent;
    private boolean handshakeManifestSent;
    private boolean seedLockRequestSent;
    private long nextTimeSnapshotAtMillis;
    private long nextFleetSnapshotAtMillis;
    private CoopTimeLock.TimeSnapshot latestTimeSnapshot;
    private final CoopFleetMirror fleetMirror = new CoopFleetMirror();
    private final CoopInteractionGate interactionGate = new CoopInteractionGate();
    private String localInteractionEntityId;
    private String lastBlockedEntityName;
    private final Supplier<CoopHandshakeManifest> manifestSupplier;
    private final BooleanSupplier ironModeSupplier;
    private final Supplier<CoopSeedSync.SeedData> hostSeedSupplier;
    private final Supplier<String> sectorFingerprintSupplier;
    private final Supplier<String> sectorSeedStringSupplier;
    private final CoopTimeLock timeLock;
    private final CoopSharedPauseCoordinator pauseCoordinator = new CoopSharedPauseCoordinator();
    // Host: the effective pause we applied last frame, used to detect vanilla auto-pause edges (the
    // host pause key itself is captured by CoopHostPauseInputListener, not here).
    private boolean hostEffectivePauseApplied;
    private boolean hostSharedPauseInitialized;

    public CoopNetPump(CoopNetService service) {
        this(service, System::currentTimeMillis);
    }

    public CoopNetPump(CoopNetService service, LongSupplier clockMillis) {
        this(service, new CoopSessionState(), clockMillis);
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis) {
        this(service, sessionState, clockMillis, CoopHandshakeManifest::capture, CoopIronModeGuard::isIronModeActive);
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis,
                       Supplier<CoopHandshakeManifest> manifestSupplier, BooleanSupplier ironModeSupplier) {
        this(service, sessionState, clockMillis, manifestSupplier, ironModeSupplier,
                CoopSeedSync::seedForLoadedSector,
                CoopSeedSync::currentSectorFingerprint,
                CoopSeedSync::currentSectorSeedString);
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis,
                       Supplier<CoopHandshakeManifest> manifestSupplier, BooleanSupplier ironModeSupplier,
                       Supplier<CoopSeedSync.SeedData> hostSeedSupplier, Supplier<String> sectorFingerprintSupplier) {
        this(service, sessionState, clockMillis, manifestSupplier, ironModeSupplier,
                hostSeedSupplier, sectorFingerprintSupplier, CoopSeedSync::currentSectorSeedString);
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis,
                       Supplier<CoopHandshakeManifest> manifestSupplier, BooleanSupplier ironModeSupplier,
                       Supplier<CoopSeedSync.SeedData> hostSeedSupplier,
                       Supplier<String> sectorFingerprintSupplier,
                       Supplier<String> sectorSeedStringSupplier) {
        this(service, sessionState, clockMillis, manifestSupplier, ironModeSupplier,
                hostSeedSupplier, sectorFingerprintSupplier, sectorSeedStringSupplier, new CoopTimeLock());
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis,
                       Supplier<CoopHandshakeManifest> manifestSupplier, BooleanSupplier ironModeSupplier,
                       Supplier<CoopSeedSync.SeedData> hostSeedSupplier,
                       Supplier<String> sectorFingerprintSupplier,
                       Supplier<String> sectorSeedStringSupplier,
                       CoopTimeLock timeLock) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.manifestSupplier = Objects.requireNonNull(manifestSupplier, "manifestSupplier");
        this.ironModeSupplier = Objects.requireNonNull(ironModeSupplier, "ironModeSupplier");
        this.hostSeedSupplier = Objects.requireNonNull(hostSeedSupplier, "hostSeedSupplier");
        this.sectorFingerprintSupplier = Objects.requireNonNull(sectorFingerprintSupplier, "sectorFingerprintSupplier");
        this.sectorSeedStringSupplier = Objects.requireNonNull(sectorSeedStringSupplier, "sectorSeedStringSupplier");
        this.timeLock = Objects.requireNonNull(timeLock, "timeLock");
        this.timeLock.setPauseCoordinator(pauseCoordinator);
        long now = clockMillis.getAsLong();
        this.nextPingAtMillis = now + PING_INTERVAL_MILLIS;
        this.nextTimeSnapshotAtMillis = now + CoopTimeLock.SNAPSHOT_INTERVAL_MILLIS;
        this.nextFleetSnapshotAtMillis = now + FLEET_SNAPSHOT_INTERVAL_MILLIS;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        maybeStartFromSystemProperties();
        maybeStartFromMemoryFlags();
        service.flushOutbound();
        syncGuestInputBlocker();
        maybeSendLobbyHello();
        drainInbound();
        maybeSendHandshakeManifest();
        maybeSendSeedLockRequest();
        maybeHoldHostPausedUntilSessionReady();
        syncSharedPause();
        maybeApplyTimeSnapshot();
        maybeSendTimeSnapshot();
        syncFleetMirror();
        drainFleetDatagrams();
        maybeSendFleetSnapshot();
        syncInteractionGate();
        maybeSendPing();
        service.flushOutbound();
    }

    private void maybeStartFromSystemProperties() {
        if (startupConfigChecked || service.role() != CoopConnectionRole.NONE) {
            return;
        }
        startupConfigChecked = true;

        try {
            CoopNetStartupConfig config = CoopNetStartupConfig.fromSystemProperties();
            if (!config.isPresent()) {
                return;
            }
            if (config.role() == CoopConnectionRole.HOST) {
                service.startHost(config.port());
                sessionState.startHost(localPlayerName(CoopConnectionRole.HOST));
                lobbyHelloSent = false;
                handshakeManifestSent = false;
                seedLockRequestSent = false;
                CoopLog.info(CoopNetPump.class, "Coop host started from JVM property "
                        + CoopNetStartupConfig.HOST_PORT_PROPERTY + "=" + config.port());
            } else if (config.role() == CoopConnectionRole.GUEST) {
                sessionState.startGuest(localPlayerName(CoopConnectionRole.GUEST));
                lobbyHelloSent = false;
                handshakeManifestSent = false;
                seedLockRequestSent = false;
                service.connect(config.host(), config.port());
                CoopLog.info(CoopNetPump.class, "Coop guest started from JVM properties "
                        + CoopNetStartupConfig.CONNECT_HOST_PROPERTY + "=" + config.host() + ", "
                        + CoopNetStartupConfig.CONNECT_PORT_PROPERTY + "=" + config.port());
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Invalid coop networking JVM properties", ex);
        }
    }

    private void maybeStartFromMemoryFlags() {
        if (service.role() != CoopConnectionRole.NONE) {
            return;
        }

        SectorAPI sector;
        try {
            sector = Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        if (sector == null) {
            return;
        }

        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        try {
            if (memory.contains(HOST_PORT_FLAG)) {
                int port = parsePort(memory.get(HOST_PORT_FLAG), HOST_PORT_FLAG);
                service.startHost(port);
                sessionState.startHost(localPlayerName(CoopConnectionRole.HOST));
                lobbyHelloSent = false;
                handshakeManifestSent = false;
                seedLockRequestSent = false;
                CoopLog.info(CoopNetPump.class, "Coop host control consumed memory flag " + HOST_PORT_FLAG + "=" + port);
                return;
            }

            if (memory.contains(CONNECT_HOST_FLAG) && memory.contains(CONNECT_PORT_FLAG)) {
                String host = String.valueOf(memory.get(CONNECT_HOST_FLAG)).trim();
                int port = parsePort(memory.get(CONNECT_PORT_FLAG), CONNECT_PORT_FLAG);
                if (host.isEmpty()) {
                    throw new IllegalArgumentException(CONNECT_HOST_FLAG + " is blank");
                }
                sessionState.startGuest(localPlayerName(CoopConnectionRole.GUEST));
                lobbyHelloSent = false;
                handshakeManifestSent = false;
                seedLockRequestSent = false;
                service.connect(host, port);
                CoopLog.info(CoopNetPump.class,
                        "Coop guest control consumed memory flags " + CONNECT_HOST_FLAG + "=" + host
                                + ", " + CONNECT_PORT_FLAG + "=" + port);
            }
        } catch (RuntimeException ex) {
            if (!memoryConfigWarningLogged) {
                CoopLog.warn(CoopNetPump.class, "Invalid coop networking memory flags", ex);
                memoryConfigWarningLogged = true;
            }
        }
    }

    private String localPlayerName(CoopConnectionRole role) {
        String configured = System.getProperty(PLAYER_NAME_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return role == CoopConnectionRole.HOST ? "Host" : "Guest";
    }

    private int parsePort(Object value, String flagName) {
        int port;
        if (value instanceof Number number) {
            port = number.intValue();
        } else {
            port = Integer.parseInt(String.valueOf(value).trim());
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(flagName + " must be in range 1..65535");
        }
        return port;
    }

    private void drainInbound() {
        CoopMessages.Message message;
        while ((message = service.pollInbound()) != null) {
            log("inbound", message);
            switch (message.type()) {
                case LOBBY_HELLO -> handleLobbyHello(message);
                case LOBBY_ACCEPT -> handleLobbyAccept(message);
                case LOBBY_REJECT -> handleLobbyReject(message);
                case HANDSHAKE_MANIFEST -> handleHandshakeManifest(message);
                case HANDSHAKE_RESULT -> handleHandshakeResult(message);
                case SEED_LOCK_REQUEST -> handleSeedLockRequest(message);
                case SEED_LOCK_ACK -> handleSeedLockAck(message);
                case SEED_LOCK_REJECT -> handleSeedLockReject(message);
                case TIME_SNAPSHOT -> handleTimeSnapshot(message);
                case PAUSE_INTENT -> handlePauseIntent(message);
                case INTERACTION_CLAIM -> handleInteractionClaim(message);
                case INTERACTION_ACCEPT -> handleInteractionAccept(message);
                case INTERACTION_REJECT -> handleInteractionReject(message);
                case INTERACTION_RELEASE -> handleInteractionRelease(message);
                case PING -> sendPong(message);
                default -> {
                }
            }
        }
    }

    private void maybeSendLobbyHello() {
        if (lobbyHelloSent
                || service.role() != CoopConnectionRole.GUEST
                || !service.isConnected()
                || sessionState.connectionState() != CoopLobbyState.GUEST_CONNECTING) {
            return;
        }

        CoopMessages.Message hello = CoopMessages.lobbyHello(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionState.localPlayerInfo());
        service.send(hello);
        lobbyHelloSent = true;
        log("outbound", hello);
    }

    private void maybeSendHandshakeManifest() {
        if (handshakeManifestSent
                || service.role() != CoopConnectionRole.GUEST
                || !service.isConnected()
                || sessionState.connectionState() != CoopLobbyState.GUEST_CONNECTED
                || sessionState.handshakeValidated()) {
            return;
        }

        try {
            CoopMessages.Message handshake = CoopMessages.handshakeManifest(
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    manifestSupplier.get(),
                    ironModeSupplier.getAsBoolean());
            service.send(handshake);
            handshakeManifestSent = true;
            log("outbound", handshake);
        } catch (RuntimeException ex) {
            sessionState.rejectHandshake("Failed to capture handshake manifest: " + ex.getMessage());
            CoopLog.warn(CoopNetPump.class, "Failed to capture coop handshake manifest", ex);
        }
    }

    private void handleLobbyHello(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }

        CoopPlayerInfo guest = new CoopPlayerInfo(
                CoopMessages.requiredPayloadString(message, "playerId"),
                CoopMessages.requiredPayloadString(message, "playerName"));

        if (!sessionState.canAcceptGuest()) {
            String reason = sessionState.rejectReasonForGuest(guest);
            CoopMessages.Message reject = CoopMessages.lobbyReject(
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    reason);
            service.send(reject);
            log("outbound", reject);
            return;
        }

        sessionState.hostAcceptGuest(guest);
        CoopMessages.Message accept = CoopMessages.lobbyAccept(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionState.provisionalLobbyId(),
                sessionState.localPlayerInfo());
        service.send(accept);
        log("outbound", accept);
        CoopLog.info(CoopNetPump.class,
                "Coop lobby accepted provisionalLobbyId=" + sessionState.provisionalLobbyId()
                        + " hostPlayerId=" + sessionState.localPlayerId()
                        + " guestPlayerId=" + sessionState.remotePlayerId());
    }

    private void handleLobbyAccept(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }

        CoopPlayerInfo host = new CoopPlayerInfo(
                CoopMessages.requiredPayloadString(message, "hostPlayerId"),
                CoopMessages.requiredPayloadString(message, "hostName"));
        sessionState.guestAcceptLobby(
                CoopMessages.requiredPayloadString(message, "provisionalLobbyId"),
                host);
        CoopLog.info(CoopNetPump.class,
                "Coop lobby connected provisionalLobbyId=" + sessionState.provisionalLobbyId()
                        + " hostPlayerId=" + sessionState.remotePlayerId()
                        + " guestPlayerId=" + sessionState.localPlayerId());
    }

    private void handleLobbyReject(CoopMessages.Message message) {
        if (service.role() == CoopConnectionRole.GUEST) {
            sessionState.guestRejectLobby(CoopMessages.requiredPayloadString(message, "reason"));
        }
        CoopLog.warn(CoopNetPump.class,
                "Coop lobby rejected: " + CoopMessages.requiredPayloadString(message, "reason"));
    }

    private void handleHandshakeManifest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }

        String diff = handshakeDiffFor(message);
        if (!diff.isEmpty()) {
            sessionState.rejectHandshake(diff);
            CoopMessages.Message reject = CoopMessages.handshakeResultReject(
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    diff);
            service.send(reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop handshake rejected:\n" + diff);
            return;
        }

        String sessionId = sessionState.hostAcceptHandshake();
        CoopMessages.Message accept = CoopMessages.handshakeResultAccept(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionId);
        service.send(accept);
        log("outbound", accept);
        CoopLog.info(CoopNetPump.class, "Coop handshake accepted sessionId=" + sessionId);
    }

    private void maybeSendSeedLockRequest() {
        if (seedLockRequestSent
                || service.role() != CoopConnectionRole.HOST
                || !service.isConnected()
                || !sessionState.handshakeValidated()
                || sessionState.seedLong() != null) {
            return;
        }

        try {
            CoopSeedSync.SeedData seed = hostSeedSupplier.get();
            String fingerprint = seed.sectorFingerprint().isEmpty()
                    ? sectorFingerprintSupplier.get()
                    : seed.sectorFingerprint();
            CoopSeedSync.SeedData lockedSeed = seed.withFingerprint(fingerprint);
            sessionState.recordSeedLock(lockedSeed.seedLong(), lockedSeed.seedString(), lockedSeed.sectorFingerprint());
            CoopSeedSync.storeCurrentSectorPersistentData(lockedSeed);

            CoopMessages.Message request = CoopMessages.seedLockRequest(
                    sessionState.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    lockedSeed.seedLong(),
                    lockedSeed.seedString(),
                    lockedSeed.sectorFingerprint());
            service.send(request);
            seedLockRequestSent = true;
            log("outbound", request);
            CoopLog.info(CoopNetPump.class,
                    "Coop seed lock requested seedLong=" + lockedSeed.seedLong()
                            + " seedString=" + lockedSeed.seedString()
                            + " sectorFingerprint=" + lockedSeed.sectorFingerprint());
        } catch (RuntimeException ex) {
            sessionState.rejectHandshake("seedLock: " + ex.getMessage());
            CoopLog.warn(CoopNetPump.class, "Failed to create coop seed lock request", ex);
        }
    }

    private String handshakeDiffFor(CoopMessages.Message message) {
        try {
            boolean hostIronMode = ironModeSupplier.getAsBoolean();
            boolean guestIronMode = Boolean.parseBoolean(CoopMessages.requiredPayloadString(message, "ironMode"));
            if (hostIronMode) {
                return "ironMode: host=true";
            }
            if (guestIronMode) {
                return "ironMode: guest=true";
            }

            CoopHandshakeManifest hostManifest = manifestSupplier.get();
            CoopHandshakeManifest guestManifest = CoopHandshakeManifest.fromJson(
                    CoopMessages.requiredPayloadString(message, "manifestJson"));
            return CoopHandshakeDiff.compare(hostManifest, guestManifest).toDisplayString();
        } catch (RuntimeException ex) {
            return "handshakeManifest: " + ex.getMessage();
        }
    }

    private void handleHandshakeResult(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }

        boolean accepted = Boolean.parseBoolean(CoopMessages.requiredPayloadString(message, "accepted"));
        if (accepted) {
            String sessionId = CoopMessages.requiredPayloadString(message, "sessionId");
            sessionState.guestAcceptHandshake(sessionId);
            CoopLog.info(CoopNetPump.class, "Coop handshake accepted sessionId=" + sessionId);
            return;
        }

        String diff = CoopMessages.requiredPayloadString(message, "diff");
        sessionState.rejectHandshake(diff);
        CoopLog.warn(CoopNetPump.class, "Coop handshake rejected:\n" + diff);
    }

    private void handleSeedLockRequest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !sessionState.handshakeValidated()) {
            return;
        }

        long seedLong = CoopMessages.requiredPayloadLong(message, "seedLong");
        String seedString = CoopMessages.requiredPayloadString(message, "seedString");
        String hostFingerprint = CoopMessages.requiredPayloadString(message, "sectorFingerprint");
        String guestSeedString = sectorSeedStringSupplier.get();
        String guestFingerprint = sectorFingerprintSupplier.get();
        CoopLog.info(CoopNetPump.class,
                "Coop seed lock comparing hostSeedString=" + seedString
                        + " guestSeedString=" + guestSeedString
                        + " hostFingerprint=" + hostFingerprint
                        + " guestFingerprint=" + guestFingerprint);
        String seedMismatch = CoopSeedSync.seedStringMismatch(seedString, guestSeedString);
        if (!seedMismatch.isEmpty()) {
            sessionState.rejectHandshake(seedMismatch);
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    message.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    seedMismatch);
            service.send(reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + seedMismatch);
            return;
        }

        String mismatch = CoopSeedSync.fingerprintMismatch(hostFingerprint, guestFingerprint);
        if (!mismatch.isEmpty()) {
            sessionState.rejectHandshake(mismatch);
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    message.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    mismatch);
            service.send(reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + mismatch);
            return;
        }

        sessionState.recordSeedLock(seedLong, seedString, hostFingerprint);
        CoopSeedSync.storeCurrentSectorPersistentData(new CoopSeedSync.SeedData(seedLong, seedString, hostFingerprint));
        CoopMessages.Message ack = CoopMessages.seedLockAck(
                message.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                guestFingerprint);
        service.send(ack);
        log("outbound", ack);
        CoopLog.info(CoopNetPump.class,
                "Coop seed lock accepted seedLong=" + seedLong
                        + " seedString=" + seedString
                        + " sectorFingerprint=" + hostFingerprint);
    }

    private void handleSeedLockAck(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !sessionState.handshakeValidated()) {
            return;
        }

        String guestFingerprint = CoopMessages.requiredPayloadString(message, "sectorFingerprint");
        String mismatch = CoopSeedSync.fingerprintMismatch(sessionState.sectorFingerprint(), guestFingerprint);
        if (!mismatch.isEmpty()) {
            sessionState.rejectHandshake(mismatch);
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    message.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    mismatch);
            service.send(reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected after guest ACK: " + mismatch);
            return;
        }

        CoopLog.info(CoopNetPump.class,
                "Coop seed lock accepted by guest sectorFingerprint=" + guestFingerprint);
    }

    private void handleSeedLockReject(CoopMessages.Message message) {
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        sessionState.rejectHandshake(reason);
        CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + reason);
    }

    private void handleTimeSnapshot(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        latestTimeSnapshot = CoopTimeLock.fromMessage(message);
    }

    private void maybeHoldHostPausedUntilSessionReady() {
        // Hold the host paused from the moment it starts hosting until the coop session is fully
        // established (guest connected + handshake validated + seed lock done). Otherwise host time
        // advances during the multi-second connect and the guest starts several campaign days
        // behind; there is no public clock-setter or drivable fast-advance to let the guest catch
        // up afterwards, so we prevent the gap instead of closing it. Once the session is active we
        // stop forcing pause and the host's normal pause/unpause mirrors to the guest.
        if (service.role() != CoopConnectionRole.HOST || isGameplaySessionActive()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null && !sector.isPaused()) {
                sector.setPaused(true);
                CoopLog.info(CoopNetPump.class, "Coop host holding campaign paused until session is ready");
            }
        } catch (RuntimeException | LinkageError ex) {
            // No active sector yet (e.g. still on a menu); nothing to pause.
        }
    }

    /**
     * Phase 11 shared pause. Dispatches by role each frame while the session is active:
     * <ul>
     *   <li><b>Host:</b> capture the host's own (vanilla) pause edges as {@code hostPauseIntent},
     *   compute {@code effectivePaused} as the OR of host/guest/combat intents, and apply it to the
     *   sector clock. The Phase 7 {@code TIME_SNAPSHOT} then carries that pause to the guest.</li>
     *   <li><b>Guest:</b> derive the local intent from any open vanilla-blocking screen (plus the
     *   pause-key intent flipped by {@code CoopCampaignInputBlocker}) and forward it to the host via
     *   {@code PAUSE_INTENT} when it changes. The guest never drives {@code setPaused} from its own
     *   intent; its clock follows the host snapshot.</li>
     * </ul>
     */
    private void syncSharedPause() {
        if (!isGameplaySessionActive()) {
            resetSharedPauseState();
            return;
        }
        if (service.role() == CoopConnectionRole.HOST) {
            syncHostSharedPause();
        } else if (service.role() == CoopConnectionRole.GUEST) {
            syncGuestSharedPauseIntent();
        }
    }

    private void syncHostSharedPause() {
        SectorAPI sector;
        try {
            sector = Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        if (sector == null) {
            return;
        }
        try {
            boolean observed = sector.isPaused();
            if (!hostSharedPauseInitialized) {
                // First active frame: seed the host's intent from the current clock state (it may
                // still be paused from the connect-time hold) rather than treating it as an edge.
                pauseCoordinator.setHostPauseIntent(observed);
                hostSharedPauseInitialized = true;
            } else if (observed != hostEffectivePauseApplied) {
                // The clock changed without us setting it AND not via the pause key (that is consumed
                // by CoopHostPauseInputListener and routed to onHostPauseKey). So this is vanilla
                // auto-pause (combat/messages); treat it as the host's own pause intent.
                pauseCoordinator.setHostPauseIntent(observed);
            }
            boolean effective = pauseCoordinator.effectivePaused();
            if (observed != effective) {
                sector.setPaused(effective);
            }
            hostEffectivePauseApplied = effective;
            // Record what the host clock now shows so the host pause key resolves against it.
            pauseCoordinator.setObservedPaused(effective);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply host shared pause", ex);
        }
    }

    private void syncGuestSharedPauseIntent() {
        if (!service.isConnected()) {
            return;
        }
        // Screen pause: a level forwarded only when it changes (open/close of a blocking screen).
        try {
            SectorAPI sector = Global.getSector();
            boolean screenOpen = sector != null && isVanillaBlockingScreenOpen(sector);
            if (pauseCoordinator.updateGuestScreenLevel(screenOpen)) {
                sendPauseIntent(CoopMessages.PauseSource.SCREEN, screenOpen);
            }
        } catch (RuntimeException | LinkageError ex) {
            // No sector / UI yet; leave the screen level as-is.
        }
        // Manual pause: forwarded on every key press (resolved against the observed pause state) so a
        // host force-clear can never leave the two out of sync.
        if (pauseCoordinator.consumeGuestKeyPress()) {
            sendPauseIntent(CoopMessages.PauseSource.KEY, pauseCoordinator.desiredKeyPause());
        }
    }

    private void sendPauseIntent(CoopMessages.PauseSource source, boolean paused) {
        CoopMessages.Message message = CoopMessages.pauseIntent(
                sessionState.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                source,
                paused,
                pauseCoordinator.nextLocalSeq());
        service.send(message);
        log("outbound", message);
    }

    private boolean isVanillaBlockingScreenOpen(SectorAPI sector) {
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            return false;
        }
        // The core UI tabs (map/fleet/character/refit/cargo/intel) plus interaction dialogs and the
        // in-game menu are the vanilla-blocking screens that should pause the shared world so the
        // guest can read/plan without the host's world running on.
        return ui.isShowingDialog()
                || ui.isShowingMenu()
                || ui.getCurrentCoreTab() != null;
    }

    private void handlePauseIntent(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        CoopMessages.PauseSource source = CoopMessages.PauseSource.valueOf(
                CoopMessages.requiredPayloadString(message, "source"));
        boolean paused = Boolean.parseBoolean(CoopMessages.requiredPayloadString(message, "paused"));
        long intentSeq = CoopMessages.requiredPayloadLong(message, "intentSeq");
        boolean applied = source == CoopMessages.PauseSource.SCREEN
                ? pauseCoordinator.applyGuestScreenPauseIntent(paused, intentSeq)
                : pauseCoordinator.applyGuestKeyPauseIntent(paused, intentSeq);
        if (applied) {
            CoopLog.info(CoopNetPump.class, "Coop guest pause intent applied source=" + source
                    + " paused=" + paused + " intentSeq=" + intentSeq);
        }
    }

    private void resetSharedPauseState() {
        if (!hostSharedPauseInitialized
                && !pauseCoordinator.guestKeyPauseIntent()
                && !pauseCoordinator.guestScreenPauseIntent()
                && !pauseCoordinator.guestScreenLevel()
                && !pauseCoordinator.eitherInCombat()) {
            return;
        }
        pauseCoordinator.reset();
        hostSharedPauseInitialized = false;
        hostEffectivePauseApplied = false;
    }

    private void maybeApplyTimeSnapshot() {
        if (service.role() != CoopConnectionRole.GUEST
                || !isGameplaySessionActive()
                || latestTimeSnapshot == null) {
            return;
        }

        try {
            timeLock.apply(latestTimeSnapshot);
            // The guest's clock mirrors the host snapshot; record it so the guest pause key resolves
            // against the observed state instead of blindly toggling a private intent.
            pauseCoordinator.setObservedPaused(latestTimeSnapshot.paused());
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply coop time snapshot", ex);
        }
    }

    private void maybeSendTimeSnapshot() {
        if (service.role() != CoopConnectionRole.HOST || !service.isConnected() || !isGameplaySessionActive()) {
            return;
        }

        long now = clockMillis.getAsLong();
        if (now < nextTimeSnapshotAtMillis) {
            return;
        }

        try {
            CoopTimeLock.TimeSnapshot snapshot = timeLock.capture(now);
            CoopMessages.Message message = CoopMessages.timeSnapshot(
                    sessionState.sessionId(),
                    service.nextSeq(),
                    snapshot.paused(),
                    snapshot.fastForward(),
                    snapshot.timestampMillis(),
                    snapshot.campaignDay(),
                    snapshot.sentAtMillis());
            service.send(message);
            log("outbound", message);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to capture coop time snapshot", ex);
        } finally {
            nextTimeSnapshotAtMillis = now + CoopTimeLock.SNAPSHOT_INTERVAL_MILLIS;
        }
    }

    private boolean shouldStreamFleet() {
        return service.role() != CoopConnectionRole.NONE
                && service.isConnected()
                && isGameplaySessionActive();
    }

    private void syncFleetMirror() {
        // Tear the mirror fleet down the moment the session is no longer streaming (disconnect,
        // reject, session end) so a stale AI fleet is never left behind in the world.
        if (!shouldStreamFleet() && fleetMirror.hasMirrorFleet()) {
            fleetMirror.dispose();
        }
    }

    private void maybeSendFleetSnapshot() {
        if (!shouldStreamFleet()) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now < nextFleetSnapshotAtMillis) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            CoopFleetSnapshot snapshot = CoopFleetSnapshotFactory.captureLocalPlayer(
                    sector, sessionState.localPlayerId(), sessionState.localName());
            if (snapshot == null) {
                return;
            }
            String datagram = CoopMessages.datagram(
                    sessionState.sessionId(), CoopMessages.Type.FLEET_SNAPSHOT, snapshot.encode());
            service.sendDatagram(datagram);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to capture coop fleet snapshot", ex);
        } finally {
            nextFleetSnapshotAtMillis = now + FLEET_SNAPSHOT_INTERVAL_MILLIS;
        }
    }

    private void drainFleetDatagrams() {
        String raw;
        while ((raw = service.pollDatagram()) != null) {
            try {
                CoopMessages.Datagram datagram = CoopMessages.parseDatagram(raw);
                if (datagram.type() != CoopMessages.Type.FLEET_SNAPSHOT) {
                    continue;
                }
                if (!sessionMatches(datagram.sessionId())) {
                    continue;
                }
                CoopFleetSnapshot snapshot = CoopFleetSnapshot.decode(datagram.body());
                // Ignore our own echoed datagrams and any sender that is not the remote coop player.
                if (!snapshot.playerId().equals(sessionState.remotePlayerId())) {
                    continue;
                }
                fleetMirror.apply(snapshot, localPlayerFactionId());
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopNetPump.class, "Failed to apply coop fleet datagram", ex);
            }
        }
    }

    private boolean sessionMatches(String datagramSessionId) {
        String sessionId = sessionState.sessionId();
        return sessionId != null && sessionId.equals(datagramSessionId);
    }

    private String localPlayerFactionId() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null && sector.getPlayerFaction() != null) {
                return sector.getPlayerFaction().getId();
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through to the vanilla player faction id
        }
        return DEFAULT_PLAYER_FACTION_ID;
    }

    private void handleInteractionClaim(CoopMessages.Message message) {
        // Only the host arbitrates. Assign a host receive sequence and accept the first claim per
        // entity; reject later claims with already_claimed_by:<playerId> until the holder releases.
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        String entityId = CoopMessages.requiredPayloadString(message, "entityId");
        String entityName = CoopMessages.requiredPayloadString(message, "entityName");
        String playerId = CoopMessages.requiredPayloadString(message, "playerId");
        CoopInteractionGate.ClaimResult result = interactionGate.arbitrate(entityId, playerId, entityName);
        if (result.accepted()) {
            CoopMessages.Message accept = CoopMessages.interactionAccept(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    entityId, playerId, entityName, result.hostSeq());
            service.send(accept);
            log("outbound", accept);
            CoopLog.info(CoopNetPump.class, "Coop interaction claim accepted entityId=" + entityId
                    + " playerId=" + playerId + " hostSeq=" + result.hostSeq());
        } else {
            CoopMessages.Message reject = CoopMessages.interactionReject(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    entityId, result.rejectReason());
            service.send(reject);
            log("outbound", reject);
            CoopLog.info(CoopNetPump.class, "Coop interaction claim rejected entityId=" + entityId
                    + " requester=" + playerId + " " + result.rejectReason());
        }
    }

    private void handleInteractionAccept(CoopMessages.Message message) {
        // Guest mirrors the host's authoritative decision. When the accepted player is the remote
        // player, this is what locks the guest out via blockingClaimFor.
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        CoopInteractionClaim claim = new CoopInteractionClaim(
                CoopMessages.requiredPayloadString(message, "entityId"),
                CoopMessages.requiredPayloadString(message, "playerId"),
                CoopMessages.requiredPayloadString(message, "entityName"),
                CoopMessages.requiredPayloadLong(message, "hostSeq"));
        interactionGate.applyAccepted(claim);
    }

    private void handleInteractionReject(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        String entityId = CoopMessages.requiredPayloadString(message, "entityId");
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        // Our optimistic local interaction lost the race; stop tracking it so we re-claim cleanly
        // next time. Full forced-close of the guest's already-open dialog is deferred past v1.
        if (entityId.equals(localInteractionEntityId)) {
            localInteractionEntityId = null;
        }
        CoopLog.warn(CoopNetPump.class, "Coop interaction rejected entityId=" + entityId + " " + reason);
    }

    private void handleInteractionRelease(CoopMessages.Message message) {
        String entityId = CoopMessages.requiredPayloadString(message, "entityId");
        String playerId = CoopMessages.requiredPayloadString(message, "playerId");
        interactionGate.release(entityId, playerId);
    }

    private void syncInteractionGate() {
        if (service.role() == CoopConnectionRole.NONE
                || !service.isConnected()
                || !isGameplaySessionActive()) {
            resetInteractionState();
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            detectLocalInteraction(sector);
            applyLocalBlocking(sector);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync coop interaction gate", ex);
        }
    }

    private void detectLocalInteraction(SectorAPI sector) {
        String currentEntityId = null;
        String currentEntityName = null;
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui != null) {
            InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
            if (dialog != null) {
                SectorEntityToken target = dialog.getInteractionTarget();
                if (target != null) {
                    currentEntityId = target.getId();
                    currentEntityName = target.getName();
                }
            }
        }

        if (Objects.equals(currentEntityId, localInteractionEntityId)) {
            return;
        }

        // The local interaction we were tracking has ended (dialog closed or target changed):
        // release the claim and tell the remote client.
        if (localInteractionEntityId != null) {
            sendInteractionRelease(localInteractionEntityId);
            if (service.role() == CoopConnectionRole.HOST) {
                interactionGate.release(localInteractionEntityId, sessionState.localPlayerId());
            }
            localInteractionEntityId = null;
        }

        // A new local interaction just opened: host arbitrates locally and broadcasts the accept;
        // guest requests the claim from the host.
        if (currentEntityId != null) {
            beginLocalInteraction(currentEntityId, currentEntityName);
        }
    }

    private void beginLocalInteraction(String entityId, String entityName) {
        localInteractionEntityId = entityId;
        String localPlayerId = sessionState.localPlayerId();
        if (service.role() == CoopConnectionRole.HOST) {
            CoopInteractionGate.ClaimResult result = interactionGate.arbitrate(entityId, localPlayerId, entityName);
            if (result.accepted()) {
                CoopMessages.Message accept = CoopMessages.interactionAccept(
                        sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                        entityId, localPlayerId, entityName, result.hostSeq());
                service.send(accept);
                log("outbound", accept);
            } else {
                CoopLog.warn(CoopNetPump.class, "Host opened interaction on entityId=" + entityId
                        + " already claimed by " + result.rejectedByPlayerId());
            }
        } else {
            CoopMessages.Message claim = CoopMessages.interactionClaim(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    entityId, entityName, localPlayerId);
            service.send(claim);
            log("outbound", claim);
        }
    }

    private void sendInteractionRelease(String entityId) {
        CoopMessages.Message release = CoopMessages.interactionRelease(
                sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                entityId, sessionState.localPlayerId());
        service.send(release);
        log("outbound", release);
    }

    private void applyLocalBlocking(SectorAPI sector) {
        String localPlayerId = sessionState.localPlayerId();
        CoopInteractionClaim blocking = localPlayerId == null
                ? null
                : interactionGate.blockingClaimFor(localPlayerId);
        if (blocking != null) {
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui != null) {
                // Engine-level guard: stop a new interaction dialog from opening this frame.
                ui.setDisallowPlayerInteractionsForOneFrame();
            }
            // Guest-only input lock (consume world input except camera). No-op on host.
            timeLock.setInteractionBlocked(true, blocking.entityName());
            if (!blocking.entityName().equals(lastBlockedEntityName)) {
                if (ui != null) {
                    ui.addMessage("Remote player is interacting: " + blocking.entityName());
                }
                lastBlockedEntityName = blocking.entityName();
            }
        } else {
            timeLock.setInteractionBlocked(false, null);
            lastBlockedEntityName = null;
        }
    }

    private void resetInteractionState() {
        if (localInteractionEntityId == null && lastBlockedEntityName == null) {
            return;
        }
        localInteractionEntityId = null;
        lastBlockedEntityName = null;
        interactionGate.clear();
        try {
            timeLock.setInteractionBlocked(false, null);
        } catch (RuntimeException ex) {
            // No active sector to clear the blocker on; the blocker is removed by syncGuestInputBlocker.
        }
    }

    private void syncGuestInputBlocker() {
        boolean connectedActive = service.isConnected() && isGameplaySessionActive();
        boolean guest = service.role() == CoopConnectionRole.GUEST && connectedActive;
        boolean host = service.role() == CoopConnectionRole.HOST && connectedActive;
        try {
            timeLock.syncGuestInputBlocker(guest);
            // Phase 11: the host needs its pause-key interceptor so the shared clock never flickers.
            timeLock.syncHostInputListener(host);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync coop campaign input listeners", ex);
        }
    }

    private boolean isGameplaySessionActive() {
        return sessionState.handshakeValidated() && sessionState.seedLong() != null;
    }

    private void maybeSendPing() {
        if (service.role() != CoopConnectionRole.GUEST || !service.isConnected()) {
            return;
        }
        if (sessionState.connectionState() != CoopLobbyState.NONE
                && !sessionState.handshakeValidated()) {
            return;
        }
        if (sessionState.handshakeValidated() && sessionState.seedLong() == null) {
            return;
        }

        long now = clockMillis.getAsLong();
        if (now < nextPingAtMillis) {
            return;
        }

        CoopMessages.Message ping = CoopMessages.ping(sessionState.sessionId(), service.nextSeq(), now);
        service.send(ping);
        log("outbound", ping);
        nextPingAtMillis = now + PING_INTERVAL_MILLIS;
    }

    private void sendPong(CoopMessages.Message ping) {
        CoopMessages.Message pong = CoopMessages.pong(
                ping.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                ping.seq());
        service.send(pong);
        log("outbound", pong);
    }

    private void log(String direction, CoopMessages.Message message) {
        String line = "Coop net " + service.role() + " " + direction + " "
                + message.type() + " seq=" + message.seq();
        // Heartbeat/state traffic (ping/pong, 5 Hz time snapshots) is high-frequency and uninteresting
        // once a session is established, so log it at DEBUG (suppressed by Starsector's default level).
        // Lobby/handshake/seed-lock/disconnect are low-frequency, one-shot, and stay at INFO.
        if (isHighFrequency(message.type())) {
            CoopLog.debug(CoopNetPump.class, line);
        } else {
            CoopLog.info(CoopNetPump.class, line);
        }
    }

    private static boolean isHighFrequency(CoopMessages.Type type) {
        return type == CoopMessages.Type.PING
                || type == CoopMessages.Type.PONG
                || type == CoopMessages.Type.TIME_SNAPSHOT
                || type == CoopMessages.Type.FLEET_SNAPSHOT;
    }
}
