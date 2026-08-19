package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.campaign.CoopBaseAuthority;
import coop.campaign.CoopCampaignReplicator;
import coop.combat.CoopBattleBridge;
import coop.combat.CoopCombatSpike;
import coop.combat.CoopNpcThreatWatcher;
import coop.fleet.CoopFleetMirror;
import coop.fleet.CoopFleetMirrorRegistry;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopFleetSnapshotFactory;
import coop.fleet.CoopFleetVisibilityProbe;
import coop.fleet.CoopNpcFleetMotion;
import coop.fleet.CoopNpcFleetReplicator;
import coop.fleet.CoopNpcFleetSetSnapshot;
import coop.fleet.CoopNpcFleetSuppressor;
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
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    /** Explicit-consent override: adopt the host's campaign id over a mismatching stored one (6b). */
    static final String ADOPT_CAMPAIGN_ID_PROPERTY = "coop.adoptCampaignId";

    private final CoopNetService service;
    private final CoopSessionState sessionState;
    private final LongSupplier clockMillis;
    private long nextPingAtMillis;
    private boolean startupConfigChecked;
    private boolean memoryConfigWarningLogged;
    private boolean lobbyHelloSent;
    private boolean handshakeManifestSent;
    private boolean seedLockRequestSent;
    private boolean channelWasConnected;
    private boolean preSessionCampaignDropWarned;
    private long nextTimeSnapshotAtMillis;
    private long nextFleetSnapshotAtMillis;
    private CoopTimeLock.TimeSnapshot latestTimeSnapshot;
    private final CoopFleetMirror fleetMirror = new CoopFleetMirror();
    private final CoopFleetMirrorRegistry npcFleetRegistry = new CoopFleetMirrorRegistry();
    private final CoopNpcFleetSuppressor npcFleetSuppressor = new CoopNpcFleetSuppressor();
    private final CoopNpcFleetReplicator npcFleetReplicator;
    private final CoopBaseAuthority baseAuthority;
    private boolean npcReplicationStreaming;
    private boolean baseReplicationStreaming;
    private String lastNpcDebug;
    private long nextNpcProbeAtMillis;
    private final CoopInteractionGate interactionGate = new CoopInteractionGate();
    private final CoopCombatSpike combatSpike = new CoopCombatSpike();
    private final CoopBattleBridge battleBridge;
    private final CoopNpcThreatWatcher npcThreatWatcher;
    private final CoopCampaignReplicator campaignReplicator;
    private String localInteractionEntityId;
    private String lastBlockedEntityName;
    private final Supplier<CoopHandshakeManifest> manifestSupplier;
    private final BooleanSupplier ironModeSupplier;
    private final Supplier<CoopSeedSync.SeedData> hostSeedSupplier;
    private final Supplier<String> sectorFingerprintSupplier;
    private final Supplier<String> sectorSeedStringSupplier;
    private final Supplier<String> storedCampaignIdSupplier;
    private final Consumer<String> campaignIdStore;
    private final BooleanSupplier adoptCampaignIdSupplier;
    private final Supplier<String> canonicalFingerprintSupplier;
    private final BooleanSupplier priorCoopSessionSupplier;
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
        this(service, sessionState, clockMillis, manifestSupplier, ironModeSupplier,
                hostSeedSupplier, sectorFingerprintSupplier, sectorSeedStringSupplier, timeLock,
                CoopSeedSync::currentCampaignId,
                CoopSeedSync::storeCampaignId,
                () -> Boolean.parseBoolean(System.getProperty(ADOPT_CAMPAIGN_ID_PROPERTY)),
                CoopSeedSync::currentSectorFingerprintCanonical,
                CoopSeedSync::hasStoredSeedData);
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis,
                       Supplier<CoopHandshakeManifest> manifestSupplier, BooleanSupplier ironModeSupplier,
                       Supplier<CoopSeedSync.SeedData> hostSeedSupplier,
                       Supplier<String> sectorFingerprintSupplier,
                       Supplier<String> sectorSeedStringSupplier,
                       CoopTimeLock timeLock,
                       Supplier<String> storedCampaignIdSupplier,
                       Consumer<String> campaignIdStore,
                       BooleanSupplier adoptCampaignIdSupplier,
                       Supplier<String> canonicalFingerprintSupplier,
                       BooleanSupplier priorCoopSessionSupplier) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.manifestSupplier = Objects.requireNonNull(manifestSupplier, "manifestSupplier");
        this.ironModeSupplier = Objects.requireNonNull(ironModeSupplier, "ironModeSupplier");
        this.hostSeedSupplier = Objects.requireNonNull(hostSeedSupplier, "hostSeedSupplier");
        this.sectorFingerprintSupplier = Objects.requireNonNull(sectorFingerprintSupplier, "sectorFingerprintSupplier");
        this.sectorSeedStringSupplier = Objects.requireNonNull(sectorSeedStringSupplier, "sectorSeedStringSupplier");
        this.storedCampaignIdSupplier = Objects.requireNonNull(storedCampaignIdSupplier, "storedCampaignIdSupplier");
        this.campaignIdStore = Objects.requireNonNull(campaignIdStore, "campaignIdStore");
        this.adoptCampaignIdSupplier = Objects.requireNonNull(adoptCampaignIdSupplier, "adoptCampaignIdSupplier");
        this.canonicalFingerprintSupplier = Objects.requireNonNull(canonicalFingerprintSupplier, "canonicalFingerprintSupplier");
        this.priorCoopSessionSupplier = Objects.requireNonNull(priorCoopSessionSupplier, "priorCoopSessionSupplier");
        this.timeLock = Objects.requireNonNull(timeLock, "timeLock");
        this.timeLock.setPauseCoordinator(pauseCoordinator);
        this.campaignReplicator = new CoopCampaignReplicator(service, sessionState, clockMillis);
        this.battleBridge = new CoopBattleBridge(service, sessionState, clockMillis, pauseCoordinator);
        this.npcThreatWatcher = new CoopNpcThreatWatcher(service, sessionState, clockMillis);
        // Phase 14: vanilla's battle-result callbacks only enrich the outcome string; the coop battle
        // window itself is opened/closed by the bridge's combat-frame and campaign-resume seams.
        this.campaignReplicator.setBattleObserver(new CoopCampaignReplicator.BattleObserver() {
            @Override
            public void onBattleOccurred(boolean playerWon) {
                battleBridge.onBattleOccurred(playerWon);
            }

            @Override
            public void onPlayerEngagement(String outcome) {
                battleBridge.onPlayerEngagement(outcome);
            }
        });
        this.npcFleetReplicator = new CoopNpcFleetReplicator(service, sessionState, clockMillis);
        this.baseAuthority = new CoopBaseAuthority(service, sessionState, clockMillis);
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
        detectPeerDisconnect();
        syncGuestInputBlocker();
        maybeSendLobbyHello();
        drainInbound();
        assertMirrorEngagementShields();
        maybeSendHandshakeManifest();
        maybeSendSeedLockRequest();
        maybeHoldHostPausedUntilSessionReady();
        // Phase 14 runs before syncSharedPause so a battle that began (or ended) this frame is already
        // reflected in the combat intent when the host computes its effective pause.
        tickBattleBridge();
        syncSharedPause();
        maybeApplyTimeSnapshot();
        maybeSendTimeSnapshot();
        syncFleetMirror();
        drainFleetDatagrams();
        maybeSendFleetSnapshot();
        syncNpcReplication();
        tickNpcThreatWatcher();
        syncBaseReplication();
        syncInteractionGate();
        debugDialogState();
        tickCombatSpike();
        syncCampaignReplicator();
        campaignReplicator.tickWorldDeltas();
        campaignReplicator.tickOrbitSync();
        campaignReplicator.tickPlayerRepSync();
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

    /**
     * Connected&rarr;disconnected edge (12b reconnect hygiene). Rewinds the lobby/session so a
     * reconnecting peer can rerun the full lobby/handshake/seed-lock on the new connection. Without
     * this the host kept the dead guest's slot and answered every rejoin with "Lobby already has a
     * guest" — while resuming its snapshot streams down the new, never-handshaken socket the moment
     * it attached, because the stale session state still read as active. All session-scoped
     * machinery (fleet mirror, NPC replication, campaign replicator, interaction gate, shared
     * pause) tears itself down on the same frame because {@link #isGameplaySessionActive()} goes
     * false; the host's connect-time pause hold also re-engages, so the world stops advancing while
     * the partner is gone.
     */
    private void detectPeerDisconnect() {
        boolean connected = service.isConnected();
        if (channelWasConnected && !connected && service.role() != CoopConnectionRole.NONE) {
            boolean changed = sessionState.onChannelDisconnected();
            lobbyHelloSent = false;
            handshakeManifestSent = false;
            seedLockRequestSent = false;
            latestTimeSnapshot = null;
            preSessionCampaignDropWarned = false;
            if (changed) {
                CoopLog.warn(CoopNetPump.class, "Coop peer disconnected; session reset, awaiting reconnect as "
                        + service.role());
            }
        }
        channelWasConnected = connected;
    }

    private void drainInbound() {
        CoopMessages.Message message;
        while ((message = service.pollInbound()) != null) {
            log("inbound", message);
            // Log-and-drop guard: handlers throw freely (missing payload fields, unknown enum values,
            // out-of-order lobby messages). Letting one escape kills EveryFrameScript.advance() and
            // with it the whole pump, so a version-skewed peer or a stray connection could take the
            // session down. One bad message is a bug to log, never a peer to disconnect (Phase 12b).
            try {
                dispatchInbound(message);
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopNetPump.class, "Coop dropped malformed/unexpected message type="
                        + message.type() + " seq=" + message.seq(), ex);
            }
        }
    }

    private void dispatchInbound(CoopMessages.Message message) {
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
            case NPC_FLEET_SET -> handleNpcFleetSet(message);
            case BASE_SET -> handleBaseSet(message);
            case BATTLE_BEGIN, BATTLE_STATUS, BATTLE_END, ENGAGE_GUEST, DIALOG_BEGIN ->
                    handleBattleMessage(message);
            case PING -> sendPong(message);
            default -> {
                // Session-scoped campaign traffic (snapshots, deltas) must not touch the engine or
                // the world ledger unless the full lobby/handshake/seed-lock pipeline has run on
                // THIS connection. The 12b reconnect drill caught a lobby-rejected guest still
                // applying the host's ORBIT_SNAPSHOT stream to what was effectively a solo campaign.
                if (!isGameplaySessionActive()) {
                    if (message.type() != CoopMessages.Type.PONG && !preSessionCampaignDropWarned) {
                        preSessionCampaignDropWarned = true;
                        CoopLog.warn(CoopNetPump.class,
                                "Coop ignoring pre-session campaign message type=" + message.type());
                    }
                    return;
                }
                campaignReplicator.handle(message);
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
        // Parse once: the second parse used to run after guestRejectLobby had already changed state,
        // so a malformed payload threw from the log line rather than the state transition.
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        if (service.role() == CoopConnectionRole.GUEST) {
            sessionState.guestRejectLobby(reason);
        }
        CoopLog.warn(CoopNetPump.class, "Coop lobby rejected: " + reason);
    }

    private void handleHandshakeManifest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }
        // Explicit out-of-order guard: a manifest arriving before the lobby completes would make
        // hostAcceptHandshake() throw. Handle it deliberately rather than via the dispatch catch-all.
        if (sessionState.connectionState() != CoopLobbyState.HOST_CONNECTED) {
            CoopLog.warn(CoopNetPump.class, "Coop ignoring HANDSHAKE_MANIFEST in state "
                    + sessionState.connectionState() + " (expected HOST_CONNECTED)");
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
            CampaignIdResolution campaignId = resolveHostCampaignId();
            sessionState.recordSeedLock(lockedSeed.seedLong(), lockedSeed.seedString(), lockedSeed.sectorFingerprint());
            CoopSeedSync.storeCurrentSectorPersistentData(lockedSeed);

            CoopMessages.Message request = CoopMessages.seedLockRequest(
                    sessionState.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    lockedSeed.seedLong(),
                    lockedSeed.seedString(),
                    lockedSeed.sectorFingerprint(),
                    campaignId.id(),
                    campaignId.minted());
            service.send(request);
            seedLockRequestSent = true;
            log("outbound", request);
            CoopLog.info(CoopNetPump.class,
                    "Coop seed lock requested seedLong=" + lockedSeed.seedLong()
                            + " seedString=" + lockedSeed.seedString()
                            + " sectorFingerprint=" + lockedSeed.sectorFingerprint()
                            + " campaignId=" + campaignId.id()
                            + " minted=" + campaignId.minted());
        } catch (RuntimeException ex) {
            sessionState.rejectHandshake("seedLock: " + ex.getMessage());
            CoopLog.warn(CoopNetPump.class, "Failed to create coop seed lock request", ex);
        }
    }

    /**
     * The campaign's identity for the seed lock (Phase 6b): the stored id when the campaign has one,
     * otherwise minted once and stored. The same id then rides every {@code SEED_LOCK_REQUEST} for
     * the life of the campaign, across sessions and saves — it is what distinguishes "this campaign,
     * resumed" from "a fresh re-roll of the same seed", which the seed string and structural
     * fingerprint cannot (both are pure functions of the seed).
     */
    private CampaignIdResolution resolveHostCampaignId() {
        String stored = storedCampaignIdSupplier.get();
        if (stored != null && !stored.trim().isEmpty()) {
            return new CampaignIdResolution(stored.trim(), false);
        }
        String minted = UUID.randomUUID().toString();
        campaignIdStore.accept(minted);
        CoopLog.info(CoopNetPump.class, "Coop campaign id minted campaignId=" + minted);
        return new CampaignIdResolution(minted, true);
    }

    /** {@code minted} = the id was created at this very seed lock, i.e. the campaign is being born. */
    private record CampaignIdResolution(String id, boolean minted) {
    }

    /**
     * Logs the full canonical fingerprint text (one line per entry, the exact SHA input) so the two
     * sides' logs can be diffed to find which entry diverged. Deliberately unconditional — a failed
     * session start is rare and this is the only diagnosable artifact it leaves (Phase 6b; no diff
     * protocol on purpose: the framed transport has a fixed buffer and the canonical is ~11 KB).
     */
    private void dumpCanonicalFingerprint(String context) {
        try {
            String canonical = canonicalFingerprintSupplier.get();
            if (canonical == null || canonical.trim().isEmpty()) {
                CoopLog.warn(CoopNetPump.class, "Coop fingerprint canonical unavailable (" + context + ")");
                return;
            }
            int entries = canonical.split("\n").length;
            CoopLog.info(CoopNetPump.class,
                    "Coop fingerprint canonical (" + context + ", " + entries + " entries):\n" + canonical);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to dump coop canonical fingerprint", ex);
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
            // toString(), not getMessage(): the latter is null for exceptions thrown without one
            // (e.g. NullPointerException), which would render the diff as "handshakeManifest: null".
            return "handshakeManifest: " + ex.toString();
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
        String hostCampaignId = CoopMessages.requiredPayloadString(message, "campaignId");
        boolean hostCampaignIdMinted = Boolean.parseBoolean(
                CoopMessages.requiredPayloadString(message, "campaignIdMinted"));
        String guestSeedString = sectorSeedStringSupplier.get();
        String guestFingerprint = sectorFingerprintSupplier.get();
        CoopLog.info(CoopNetPump.class,
                "Coop seed lock comparing hostSeedString=" + seedString
                        + " guestSeedString=" + guestSeedString
                        + " hostFingerprint=" + hostFingerprint
                        + " guestFingerprint=" + guestFingerprint
                        + " hostCampaignId=" + hostCampaignId);

        // Identity first (Phase 6b check order: campaignId -> seedString -> fingerprint), so a
        // wrong save produces the clear "not this campaign" message instead of a confusing state
        // diff. The id distinguishes this campaign from a fresh re-roll of the same seed, which
        // passes both downstream checks identically.
        if (!checkOrAdoptCampaignId(message, hostCampaignId, hostCampaignIdMinted)) {
            return;
        }

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
            dumpCanonicalFingerprint("guest fingerprint comparison failed");
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

    /**
     * Guest half of the campaign-identity check. Returns true when the seed-lock flow may continue.
     *
     * <ul>
     *   <li>Stored id matches the host's: continue.</li>
     *   <li>No stored id and the host <em>minted the id at this seed lock</em>: the campaign is
     *   being born — adopt and continue.</li>
     *   <li>No stored id, host id pre-existing, but this save carries pre-6b coop seed markers:
     *   a save from before campaign ids existed — adopt as migration and continue.</li>
     *   <li>No stored id, host id pre-existing, no markers: a fresh same-seed campaign trying to
     *   join a campaign already in flight — the replay hole this phase closes. Reject unless the
     *   explicit-consent override is set. (This is also the deliberate save-less-guest rejoin
     *   path: launch-guest.ps1 -AdoptCampaign sets the override.)</li>
     *   <li>Stored id differs: the save belongs to a different campaign — reject unless the
     *   override is set. Never adopt silently.</li>
     * </ul>
     */
    private boolean checkOrAdoptCampaignId(CoopMessages.Message message, String hostCampaignId,
                                           boolean hostCampaignIdMinted) {
        String stored = storedCampaignIdSupplier.get();
        stored = stored == null ? "" : stored.trim();
        if (stored.equals(hostCampaignId)) {
            return true;
        }
        if (stored.isEmpty()) {
            if (hostCampaignIdMinted) {
                campaignIdStore.accept(hostCampaignId);
                CoopLog.info(CoopNetPump.class,
                        "Coop campaign id adopted at campaign birth campaignId=" + hostCampaignId);
                return true;
            }
            if (priorCoopSessionSupplier.getAsBoolean()) {
                campaignIdStore.accept(hostCampaignId);
                CoopLog.info(CoopNetPump.class, "Coop campaign id adopted by pre-6b save migration"
                        + " campaignId=" + hostCampaignId);
                return true;
            }
            if (adoptCampaignIdSupplier.getAsBoolean()) {
                campaignIdStore.accept(hostCampaignId);
                CoopLog.warn(CoopNetPump.class, "Coop campaign id adopted by explicit override ("
                        + ADOPT_CAMPAIGN_ID_PROPERTY + "=true): fresh guest campaign joins in-flight"
                        + " campaignId=" + hostCampaignId + "; fresh-start divergence knowingly accepted");
                return true;
            }
            return rejectCampaignId(message,
                    "campaignId: host=" + hostCampaignId + " guest=<none>"
                            + "; this campaign is already in flight and this guest campaign is brand new"
                            + " (a fresh same-seed roll cannot silently rejoin it). To join anyway with a"
                            + " fresh start, relaunch the guest with -D" + ADOPT_CAMPAIGN_ID_PROPERTY
                            + "=true (launch-guest.ps1 -AdoptCampaign)");
        }
        if (adoptCampaignIdSupplier.getAsBoolean()) {
            campaignIdStore.accept(hostCampaignId);
            CoopLog.warn(CoopNetPump.class, "Coop campaign id adopted by explicit override ("
                    + ADOPT_CAMPAIGN_ID_PROPERTY + "=true): host=" + hostCampaignId
                    + " replaced stored=" + stored + "; state divergence is knowingly accepted");
            return true;
        }
        return rejectCampaignId(message,
                "campaignId: host=" + hostCampaignId + " guest=" + stored
                        + "; guest save is not from this coop campaign. To adopt the host campaign anyway,"
                        + " relaunch the guest with -D" + ADOPT_CAMPAIGN_ID_PROPERTY + "=true");
    }

    private boolean rejectCampaignId(CoopMessages.Message message, String reason) {
        sessionState.rejectHandshake(reason);
        CoopMessages.Message reject = CoopMessages.seedLockReject(
                message.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                reason);
        service.send(reject);
        log("outbound", reject);
        CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + reason);
        return false;
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
            dumpCanonicalFingerprint("host rejecting after guest ack");
            return;
        }

        CoopLog.info(CoopNetPump.class,
                "Coop seed lock accepted by guest sectorFingerprint=" + guestFingerprint);
    }

    private void handleSeedLockReject(CoopMessages.Message message) {
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        sessionState.rejectHandshake(reason);
        CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + reason);
        // Both sides dump on a fingerprint reject so the two logs can be diffed entry-for-entry.
        if (reason.contains("sectorFingerprint")) {
            dumpCanonicalFingerprint("received seed-lock reject");
        }
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

        // While the guest has its own interaction dialog open, let vanilla own the local clock. A
        // docked station dialog auto-pauses but briefly advances to process core-tab transitions
        // (e.g. closing the Trade screen and repopulating "You decide to..." options). Coop forcing
        // sector.setPaused(true) every time vanilla tries to advance froze that transition: the
        // trade tab stayed open (getCurrentCoreTab()==CARGO) and the option list never came back.
        // The host still freezes meanwhile because the guest's screen-pause INTENT keeps the host
        // paused, so the two worlds don't drift; the guest re-syncs the moment the dialog closes.
        if (isGuestInteractionDialogOpen()) {
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

    private boolean isGuestInteractionDialogOpen() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return false;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            return ui != null && ui.getCurrentInteractionDialog() != null;
        } catch (RuntimeException | LinkageError ex) {
            return false;
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

    /**
     * Keeps the mirrors unengageable, every frame, regardless of traffic. The engine's battle gate is
     * {@code canBeEngaged()}, driven by the ~1 s {@code noCombat} fader; the mirror driving paths
     * deliberately do not touch it, so this pass is the whole shield. It runs while paused too
     * ({@link #runWhilePaused()} is true), which is required: the shield must hold across dialogs and
     * pauses.
     *
     * <p>{@code fleetMirror} — the remote <em>player's</em> mirror, present on host and guest alike —
     * is shielded unconditionally: that is the PvP block, and on the host it also keeps the guest's
     * mirror out of NPC battles. The guest's NPC mirrors get the same treatment except for the single
     * fleet the player has explicitly targeted, which is released so the encounter can open at all
     * (see {@link CoopFleetMirror#assertEngagementShield(Object)}).
     */
    private void assertMirrorEngagementShields() {
        fleetMirror.assertEngagementShield();
        if (npcFleetRegistry.size() == 0) {
            // Host (or a guest before the first NPC_FLEET_SET): nothing to shield, so skip the sector
            // read entirely rather than paying for it every frame.
            return;
        }
        npcFleetRegistry.assertEngagementShields(playerEngagementTargetOrNull());
    }

    /**
     * The fleet the local player is walking into, or null. Read here rather than in the mirrors so
     * they stay engine-dumb. Returns null while any dialog owns the screen: by then the encounter has
     * already been constructed and vanilla's {@code FleetInteractionDialogPluginImpl} — which never
     * consults the fader — drives the battle, so the shield can go straight back up.
     */
    private Object playerEngagementTargetOrNull() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return null;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui != null && (ui.isShowingDialog() || ui.getCurrentInteractionDialog() != null)) {
                return null;
            }
            CampaignFleetAPI player = sector.getPlayerFleet();
            return player == null ? null : player.getInteractionTarget();
        } catch (RuntimeException | LinkageError ex) {
            // Hot path, once per frame: an unreadable sector just means "no target this frame".
            return null;
        }
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
                if (!sessionMatches(datagram.sessionId())) {
                    continue;
                }
                switch (datagram.type()) {
                    case FLEET_SNAPSHOT -> applyFleetSnapshotDatagram(datagram);
                    case NPC_FLEET_MOTION -> handleNpcFleetMotion(datagram);
                    default -> { /* ignore unknown datagram types */ }
                }
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopNetPump.class, "Failed to apply coop fleet datagram", ex);
            }
        }
    }

    private void applyFleetSnapshotDatagram(CoopMessages.Datagram datagram) {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.decode(datagram.body());
        // Ignore our own echoed datagrams and any sender that is not the remote coop player.
        if (!snapshot.playerId().equals(sessionState.remotePlayerId())) {
            return;
        }
        fleetMirror.apply(snapshot, localPlayerFactionId());
    }

    private void handleNpcFleetMotion(CoopMessages.Datagram datagram) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        List<CoopNpcFleetMotion> motions = CoopNpcFleetMotion.decodeBatch(datagram.body());
        npcFleetRegistry.applyMotion(motions);
    }

    private void handleNpcFleetSet(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        try {
            String encoded = CoopMessages.requiredPayloadString(message, "set");
            npcFleetRegistry.applySet(CoopNpcFleetSetSnapshot.decode(encoded));
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply NPC_FLEET_SET", ex);
        }
    }

    /**
     * Phase 9 NPC fleet replication. The host streams its authoritative NPC population; the guest
     * renders host mirrors and suppresses its own NPC simulation. Mirrors are created/disposed by the
     * NPC_FLEET_SET handler and motion by the datagram drain; this method drives the host sender, the
     * guest suppressor sweep, and session-edge (re)set/teardown.
     */
    private void syncNpcReplication() {
        boolean active = shouldStreamFleet();
        if (active && !npcReplicationStreaming) {
            // (Re)starting: rebroadcast the full set and re-arm the guest suppressor.
            npcFleetReplicator.reset();
            npcFleetSuppressor.reset();
            npcThreatWatcher.reset();
            npcReplicationStreaming = true;
        } else if (!active && npcReplicationStreaming) {
            // Session ended: drop all guest NPC mirrors so no stale AI fleet is left behind.
            npcFleetRegistry.disposeAll();
            npcReplicationStreaming = false;
            lastNpcDebug = null;
        }
        if (!active) {
            return;
        }
        try {
            if (service.role() == CoopConnectionRole.HOST) {
                npcFleetReplicator.tick();
            } else if (CoopNpcFleetSuppressor.activeForRole(service.role())) {
                npcFleetSuppressor.tick(Global.getSector());
            }
            maybeDumpNpcDiagnostics();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync NPC fleet replication", ex);
        }
    }

    /**
     * Phase 13 host-authoritative pirate / Luddic-Path bases. The host polls the two pollable base
     * managers and broadcasts {@code BASE_SET} on set-hash change; the guest reconciles its own intel
     * manager against the last received set (idempotently, on a low-rate tick as well as on arrival).
     *
     * <p>Session-edge behaviour mirrors {@link #syncNpcReplication()}: a (re)start re-arms the host
     * rebroadcast so the guest always gets a full set on a fresh connection. Nothing is torn down when
     * the session ends — unlike NPC mirrors, mirrored bases are ordinary campaign content the guest
     * keeps, and the suppressor's session-start cleanup handles them on the next connect.
     */
    private void syncBaseReplication() {
        boolean active = shouldStreamFleet();
        if (active && !baseReplicationStreaming) {
            baseAuthority.reset();
            baseReplicationStreaming = true;
        } else if (!active && baseReplicationStreaming) {
            baseReplicationStreaming = false;
        }
        if (!active) {
            return;
        }
        try {
            if (service.role() == CoopConnectionRole.HOST) {
                baseAuthority.tickHost();
            } else if (CoopBaseAuthority.reconcilesForRole(service.role())) {
                baseAuthority.tickGuest();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync host-authoritative bases", ex);
        }
    }

    private void handleBaseSet(CoopMessages.Message message) {
        if (!CoopBaseAuthority.reconcilesForRole(service.role()) || !isGameplaySessionActive()) {
            return;
        }
        try {
            // Take the session edge eagerly. Inbound dispatch runs before syncBaseReplication() in
            // advance(), so on the session-start frame a BASE_SET can arrive while
            // baseReplicationStreaming is still false — the lazy edge in syncBaseReplication() would
            // then reset() AFTER this apply and wipe the freshly stored set (and the host, whose set
            // hash hasn't changed, never resends). Caught live 2026-08-19.
            if (shouldStreamFleet() && !baseReplicationStreaming) {
                baseAuthority.reset();
                baseReplicationStreaming = true;
            }
            baseAuthority.applySet(CoopMessages.requiredPayloadString(message, "bases"));
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply BASE_SET", ex);
        }
    }

    private void maybeDumpNpcDiagnostics() {
        if (!CoopDebug.diagnosticsEnabled()) {
            return;
        }
        String state;
        if (service.role() == CoopConnectionRole.HOST) {
            state = "host fleets=" + npcFleetReplicator.lastFleetCount()
                    + " hash=" + shortHash(npcFleetReplicator.lastSetHash());
        } else {
            state = "guest mirrors=" + npcFleetRegistry.size() + " ids=" + npcFleetRegistry.fleetIds();
        }
        if (!state.equals(lastNpcDebug)) {
            CoopLog.info(CoopNetPump.class, "Coop NPC-set " + state);
            lastNpcDebug = state;
        }
        maybeDumpVisibilityProbe();
    }

    /**
     * Detection-parity probe (CoopDebug only, throttled). Dumps per-fleet sensor profile + the engine's
     * {@code VisibilityLevel} verdict and detection range, keyed by {@code coopFleetId} so the host and
     * guest logs can be diffed line-for-line to see exactly why a fleet visible on the host is not
     * rendered on the guest (not created vs low profile vs weak observer vs out of range).
     */
    private void maybeDumpVisibilityProbe() {
        long now = clockMillis.getAsLong();
        if (now < nextNpcProbeAtMillis) {
            return;
        }
        nextNpcProbeAtMillis = now + 2000L;
        String dump = service.role() == CoopConnectionRole.HOST
                ? CoopFleetVisibilityProbe.dumpHost(Global.getSector())
                : CoopFleetVisibilityProbe.dumpGuest(Global.getSector());
        CoopLog.info(CoopNetPump.class, dump);
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            return "(none)";
        }
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
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
            // Every open dialog claims its target, including the fleet dialog the guest opens by
            // engaging an NPC mirror: the mirror is a local entity with its own id, so the claim can
            // never collide with the host's claim on the real fleet and the gate stays out of the
            // engagement's way. (The Phase 14 spectator panel used to be excluded here; it was
            // replaced by banners on 2026-08-19, so there is nothing left to exclude.)
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

    private String lastDialogDebug = "";

    /**
     * Dormant diagnostic (guest only), off unless {@link CoopDebug#diagnosticsEnabled()}: logs the
     * interaction-dialog + coop gate/pause state whenever it changes, to investigate why a guest's
     * station dialog options fail to repopulate after exiting the trade core tab. {@code hasOptions()}
     * distinguishes a population failure (false) from a pure render glitch (true).
     */
    private void debugDialogState() {
        if (!CoopDebug.diagnosticsEnabled()
                || service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            CampaignUIAPI ui = sector.getCampaignUI();
            if (ui == null) {
                return;
            }
            InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
            String state;
            if (dialog == null) {
                state = "dialog=none"
                        + " coreTab=" + ui.getCurrentCoreTab()
                        + " showDialog=" + ui.isShowingDialog()
                        + " showMenu=" + ui.isShowingMenu()
                        + " paused=" + sector.isPaused();
            } else {
                SectorEntityToken target = dialog.getInteractionTarget();
                boolean hasOptions = false;
                try {
                    hasOptions = dialog.getOptionPanel() != null && dialog.getOptionPanel().hasOptions();
                } catch (RuntimeException ignored) {
                    // option panel not ready this frame
                }
                String localPlayerId = sessionState.localPlayerId();
                CoopInteractionClaim blocking = localPlayerId == null
                        ? null : interactionGate.blockingClaimFor(localPlayerId);
                state = "dialog=" + (target == null ? "null" : target.getId())
                        + " hasOptions=" + hasOptions
                        + " coreTab=" + ui.getCurrentCoreTab()
                        + " showDialog=" + ui.isShowingDialog()
                        + " paused=" + sector.isPaused()
                        + " inFastAdvance=" + sector.isInFastAdvance()
                        + " blocked=" + (blocking != null)
                        + " screenOwnsInput=" + isVanillaBlockingScreenOpen(sector)
                        + " trackedEntity=" + localInteractionEntityId;
            }
            if (!state.equals(lastDialogDebug)) {
                CoopLog.info(CoopNetPump.class, "Coop dialog-debug " + state);
                lastDialogDebug = state;
            }
        } catch (RuntimeException | LinkageError ex) {
            // diagnostics must never disrupt the pump
        }
    }

    /**
     * Phase 14 spike harness (throwaway). Inert unless {@link CoopDebug#diagnosticsEnabled()} and one
     * of the per-spike sector memory flags is set; see {@link CoopCombatSpike} and
     * {@code docs/PHASE14_SPIKE_NOTES.md}.
     */
    private void tickCombatSpike() {
        try {
            combatSpike.maybeRun(Global.getSector(), service.role() == CoopConnectionRole.HOST,
                    isGameplaySessionActive(), clockMillis.getAsLong());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Phase 14 spike harness failed", ex);
        }
    }

    /**
     * Phase 14 solo own-fleet combat. Drives the battle lifecycle on the engaging client, the shared
     * combat pause intent on the host, and the spectator status panel on whichever client is not
     * fighting. Runs while paused (the spectator's world is paused by definition) and while the
     * session is down, so the bridge can log a discarded battle result and release the panel.
     */
    private void tickBattleBridge() {
        try {
            battleBridge.tickCampaign(Global.getSector(), isGameplaySessionActive(), clockMillis.getAsLong());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to tick coop battle bridge", ex);
        }
    }

    private void handleBattleMessage(CoopMessages.Message message) {
        if (!isGameplaySessionActive()) {
            CoopLog.warn(CoopNetPump.class,
                    "Coop ignoring pre-session battle message type=" + message.type());
            return;
        }
        battleBridge.handle(message);
    }

    /**
     * Phase 14 host-side synthesis against the guest mirror: the {@code ENGAGE_GUEST} handoff, the
     * injected {@code INTERCEPT} chase, the customs {@code DIALOG_BEGIN}, and the load-bearing
     * battle-eject recovery. Host-only; the guest has no mirror of itself to defend.
     */
    private void tickNpcThreatWatcher() {
        if (!shouldStreamFleet() || service.role() != CoopConnectionRole.HOST) {
            return;
        }
        try {
            npcThreatWatcher.tick(Global.getSector(), clockMillis.getAsLong(),
                    battleBridge.isAnyCoopBattleActive());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to tick coop NPC threat watcher", ex);
        }
    }

    private void syncCampaignReplicator() {
        // Register the Phase 12 campaign event listener once the session is active and tear it down
        // (clearing all replicated state) when the session ends, mirroring the fleet-mirror lifecycle.
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            boolean active = service.isConnected() && isGameplaySessionActive();
            if (active && !campaignReplicator.isRegistered()) {
                campaignReplicator.registerOn(sector);
            } else if (!active && campaignReplicator.isRegistered()) {
                campaignReplicator.dispose(sector);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync coop campaign replicator", ex);
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
            // Suspend the guest blocker's consumption while a vanilla blocking screen owns the
            // keyboard, so the guest can advance/dismiss its own interaction dialog (spacebar to the
            // options, ESC to leave). The shared screen-pause already stops both clocks there.
            if (guest) {
                timeLock.setInputBlockerSuspended(isGuestScreenOwningInput());
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync coop campaign input listeners", ex);
        }
    }

    private boolean isGuestScreenOwningInput() {
        try {
            SectorAPI sector = Global.getSector();
            return sector != null && isVanillaBlockingScreenOpen(sector);
        } catch (RuntimeException | LinkageError ex) {
            return false;
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
        // No seed-lock-phase suppression: the host holds paused through seed lock, which is exactly
        // the window where a half-open connection would otherwise stay invisible. Pre-lobby
        // suppression (above) stays.

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
                || type == CoopMessages.Type.FLEET_SNAPSHOT
                || type == CoopMessages.Type.NPC_FLEET_MOTION
                || type == CoopMessages.Type.BATTLE_STATUS;
    }
}
