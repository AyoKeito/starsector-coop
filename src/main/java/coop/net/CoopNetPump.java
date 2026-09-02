package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.campaign.CoopBarGenerationSuppressor;
import coop.campaign.CoopBaseAuthority;
import coop.campaign.CoopCampaignReplicator;
import coop.combat.CoopBattleBridge;
import coop.combat.CoopBattleResult;
import coop.combat.CoopBattleResultReconciler;
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
import coop.fleet.CoopRespawnNotifier;
import coop.handshake.CoopHandshakeDiff;
import coop.handshake.CoopHandshakeManifest;
import coop.interaction.CoopInteractionClaim;
import coop.interaction.CoopInteractionGate;
import coop.interaction.CoopRejectTracker;
import coop.save.CoopGuestSnapshot;
import coop.save.CoopGuestSnapshotFactory;
import coop.save.CoopGuestSnapshotStore;
import coop.save.CoopSaveCheckpoint;
import coop.seed.CoopSeedSync;
import coop.session.CoopIronModeGuard;
import coop.session.CoopLobbyState;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.time.CoopClockReconciler;
import coop.time.CoopFastForwardLock;
import coop.time.CoopSharedPauseCoordinator;
import coop.time.CoopTimeLock;
import coop.util.CoopDebug;
import coop.util.CoopFrameProfiler;
import coop.util.CoopLog;

import java.util.ArrayDeque;
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
    /**
     * Phase 16: how often the guest ships its save-recovery snapshot to the host. It only has to be
     * fresher than the host's save cadence, and it carries the whole cargo manifest, so it is a slow
     * timer rather than a stream.
     */
    private static final long GUEST_SNAPSHOT_INTERVAL_MILLIS = 30_000L;
    private static final String DEFAULT_PLAYER_FACTION_ID = "player";
    /**
     * Ceiling on the Phase 18 debug delay queue. Only reachable with the lever on and a peer
     * spamming claims; past it the host arbitrates immediately rather than growing without bound.
     */
    private static final int MAX_DELAYED_INTERACTION_CLAIMS = 256;
    private static final String HOST_PORT_FLAG = "coop.hostPort";
    private static final String CONNECT_HOST_FLAG = "coop.connectHost";
    private static final String CONNECT_PORT_FLAG = "coop.connectPort";
    private static final String PLAYER_NAME_PROPERTY = "coop.playerName";
    /** Explicit-consent override: adopt the host's campaign id over a mismatching stored one (6b). */
    static final String ADOPT_CAMPAIGN_ID_PROPERTY = "coop.adoptCampaignId";

    // CoopFrameProfiler section keys. Compile-time constants so the hot path never builds a string.
    private static final String SECTION_CFG_PROPERTIES = "cfg.systemProperties";
    private static final String SECTION_CFG_MEMORY_FLAGS = "cfg.memoryFlags";
    private static final String SECTION_FLUSH_OUTBOUND_PRE = "net.flushOutbound.pre";
    private static final String SECTION_DETECT_DISCONNECT = "net.detectPeerDisconnect";
    private static final String SECTION_GUEST_INPUT_BLOCKER = "input.guestBlocker";
    private static final String SECTION_LOBBY_HELLO = "lobby.hello";
    private static final String SECTION_DRAIN_INBOUND = "net.drainInbound";
    private static final String SECTION_MIRROR_SHIELDS = "fleet.mirrorShields";
    private static final String SECTION_HANDSHAKE_MANIFEST = "handshake.manifest";
    private static final String SECTION_SEED_LOCK_REQUEST = "seed.lockRequest";
    private static final String SECTION_HOLD_HOST_PAUSED = "session.holdHostPaused";
    private static final String SECTION_BATTLE_BRIDGE = "battle.bridge";
    private static final String SECTION_SHARED_PAUSE = "time.sharedPause";
    private static final String SECTION_TIME_APPLY = "time.applySnapshot";
    private static final String SECTION_TIME_FAST_FORWARD = "time.fastForwardLock";
    private static final String SECTION_TIME_CLOCK_RECONCILE = "time.clockReconcile";
    private static final String SECTION_TIME_SEND = "time.sendSnapshot";
    private static final String SECTION_FLEET_MIRROR = "fleet.syncMirror";
    private static final String SECTION_FLEET_DATAGRAMS = "fleet.drainDatagrams";
    private static final String SECTION_FLEET_SNAPSHOT_SEND = "fleet.sendSnapshot";
    private static final String SECTION_RESPAWN_NOTIFIER = "fleet.respawnNotifier";
    private static final String SECTION_GUEST_SNAPSHOT_SEND = "save.sendGuestSnapshot";
    private static final String SECTION_SAVE_CHECKPOINT = "save.tickCheckpoint";
    /**
     * The whole NPC replication step. Its components are timed separately: the guest's sweep as
     * {@link #SECTION_NPC_SUPPRESSOR} here, the host's four steps from inside
     * {@link CoopNpcFleetReplicator} through the profiler's static seam.
     */
    private static final String SECTION_NPC_REPLICATION = "npc.syncReplication";
    private static final String SECTION_NPC_SUPPRESSOR = "npc.suppressor";
    private static final String SECTION_NPC_THREAT_WATCHER = "npc.threatWatcher";
    private static final String SECTION_BASE_REPLICATION = "base.syncReplication";
    private static final String SECTION_BAR_SUPPRESSOR = "bar.suppressGeneration";
    private static final String SECTION_INTERACTION_GATE = "interaction.gate";
    private static final String SECTION_DEBUG_DIALOG_STATE = "debug.dialogState";
    private static final String SECTION_COMBAT_SPIKE = "combat.spike";
    private static final String SECTION_REPLICATOR_SYNC = "replicator.sync";
    private static final String SECTION_REPLICATOR_WORLD_DELTAS = "replicator.worldDeltas";
    private static final String SECTION_REPLICATOR_ORBIT_SYNC = "replicator.orbitSync";
    private static final String SECTION_REPLICATOR_REP_SYNC = "replicator.playerRepSync";
    private static final String SECTION_REPLICATOR_BAR_POOL = "replicator.barPool";
    private static final String SECTION_REPLICATOR_COLONY = "replicator.colonyLifecycle";
    private static final String SECTION_REPLICATOR_COLONY_MGMT = "replicator.colonyManagement";
    private static final String SECTION_REPLICATOR_COLONY_INCOME = "replicator.colonyIncome";
    private static final String SECTION_REPLICATOR_EXPEDITIONS = "replicator.expeditionWarnings";
    private static final String SECTION_PING = "net.sendPing";
    private static final String SECTION_FLUSH_OUTBOUND_POST = "net.flushOutbound.post";
    /**
     * Per-message-type section keys, precomputed so the inbound drain never concatenates a string:
     * indexed by {@link CoopMessages.Type#ordinal()}.
     */
    private static final String[] SECTION_BY_MESSAGE_TYPE = buildMessageTypeSections();

    private static String[] buildMessageTypeSections() {
        CoopMessages.Type[] types = CoopMessages.Type.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = "msg." + types[i].name();
        }
        return names;
    }

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
    /**
     * Phase 29 line item (landed with 7b): the FLEET_SNAPSHOT stream is stamped with stream time and
     * consumed by the interpolation buffer, so its cadence is measured in game time — under 2x
     * fast-forward the wall send rate doubles and the buffer keeps the same sample depth.
     */
    private final CoopStreamCadence fleetSnapshotCadence =
            new CoopStreamCadence(FLEET_SNAPSHOT_INTERVAL_MILLIS);
    private long nextGuestSnapshotAtMillis;
    private CoopTimeLock.TimeSnapshot latestTimeSnapshot;
    private final CoopSaveCheckpoint saveCheckpoint = new CoopSaveCheckpoint();
    /**
     * Phase 29 M1 wire prerequisite: outbound stream stamping, sender-side redundancy, and the
     * receive-side epoch watermark for the UDP state streams. The stream clock advances by campaign
     * dt (0 while paused) at the top of every frame; the watermark and redundancy reset on the
     * session edges in {@link #syncNpcReplication} so a new session's restarted epochs are accepted
     * and no stale section leaks across sessions.
     */
    private final CoopStreamClock streamClock = new CoopStreamClock();
    private final CoopDatagramRedundancy datagramRedundancy = new CoopDatagramRedundancy();
    private final CoopDatagramWatermark datagramWatermark = new CoopDatagramWatermark();
    /**
     * Phase 29 M1: the remote peer's render cursor, shared by the player mirror and every NPC mirror.
     * Fed by every accepted datagram section and set stamp; advanced by campaign dt each frame in
     * {@link #advanceMirrorMotion}; reset on the session edges beside the watermark.
     */
    private final coop.fleet.CoopMotionTimeline motionTimeline = new coop.fleet.CoopMotionTimeline();
    private final CoopFleetMirror fleetMirror = new CoopFleetMirror();
    /**
     * Assigned in the constructor rather than inline so it shares the pump's injected clock: the
     * Phase 15 mirror freeze compares a mark stamped with {@code clockMillis} against a timeout
     * measured inside {@code applySet}, and two different clocks there would silently break it.
     */
    private final CoopFleetMirrorRegistry npcFleetRegistry;
    private final CoopNpcFleetSuppressor npcFleetSuppressor = new CoopNpcFleetSuppressor();
    private final CoopBarGenerationSuppressor barGenerationSuppressor = new CoopBarGenerationSuppressor();
    private final CoopNpcFleetReplicator npcFleetReplicator;
    private final CoopBaseAuthority baseAuthority;
    private boolean npcReplicationStreaming;
    private boolean baseReplicationStreaming;
    private boolean barSuppressionArmed;
    private String lastNpcDebug;
    /** Guest-side pre-check for the diagnostics dump: -1 means "nothing seen yet, always build". */
    private int lastNpcMirrorCount = -1;
    private int lastNpcMirrorIdsHash;
    private long nextNpcProbeAtMillis;
    private final CoopInteractionGate interactionGate = new CoopInteractionGate();
    private final CoopCombatSpike combatSpike = new CoopCombatSpike();
    private final CoopBattleBridge battleBridge;
    private final CoopNpcThreatWatcher npcThreatWatcher;
    private final CoopBattleResultReconciler battleResultReconciler;
    private final CoopCampaignReplicator campaignReplicator;
    /** Phase 17: watches the local player fleet for the vanilla wipe respawn's object swap. */
    private final CoopRespawnNotifier respawnNotifier = new CoopRespawnNotifier();
    private String localInteractionEntityId;
    private String lastBlockedEntityName;
    /** Phase 18: the local claim the host rejected, until its dialog is actually closed. */
    private final CoopRejectTracker rejectTracker = new CoopRejectTracker();
    /**
     * Phase 18 debug lever ({@link CoopDebug#INTERACTION_DELAY_PROPERTY}): inbound claims held on
     * the host until their release stamp. Empty and untouched unless the property is set.
     */
    private final ArrayDeque<DelayedInteractionClaim> delayedInteractionClaims = new ArrayDeque<>();
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
    /**
     * Phase 7b: forces vanilla's toggle fast-forward mode + the shared 2x multiplier for the life of
     * the session, and owns the field write that mirrors the host's fast-forward onto the guest.
     * Constructing it touches no engine state; it resolves its handles lazily on first enforcement.
     */
    private final CoopFastForwardLock fastForwardLock = new CoopFastForwardLock();
    /**
     * Phase 7c: guest-side campaign-clock drift reconciler. Owns every write to the campaign clock
     * (cal + the cached timestamp field) and nothing else — in particular it never touches the dt
     * handed to {@code streamClock.advance} or {@link #advanceMirrorMotion}, which would couple it to
     * the Phase 29 M1 motion pipeline. Constructing it touches no engine state; the timestamp handle
     * resolves lazily on the first guest snapshot. No-op for the host role (never ticked below).
     */
    private final CoopClockReconciler clockReconciler = new CoopClockReconciler();
    /** Session edge for the reconciler's sample ring: pre-session samples are meaningless. */
    private boolean clockReconcilerArmed;
    /** Guest dialog-open edge; the ring is cleared on open -> closed (pre-dialog samples are stale). */
    private boolean clockReconcilerDialogWasOpen;
    /** The snapshot object last fed to the reconciler; each TIME_SNAPSHOT message is one sample. */
    private CoopTimeLock.TimeSnapshot lastReconciledTimeSnapshot;
    // Host: the effective pause we applied last frame, used to detect vanilla auto-pause edges (the
    // host pause key itself is captured by CoopHostPauseInputListener, not here).
    private boolean hostEffectivePauseApplied;
    private boolean hostSharedPauseInitialized;
    /**
     * Frame profiler (diagnostic only, dormant unless {@code coop.debug.frameProfile} is on). Freshly
     * installed per pump so a game reload starts from clean accumulators rather than folding a dead
     * session's numbers into the new one.
     */
    private final CoopFrameProfiler profiler = CoopFrameProfiler.installFresh();
    /**
     * Dev wiretap (diagnostic only, dormant unless {@code coop.debug.wiretap} is on). Freshly
     * installed per pump for the same reason the profiler is; assigned in the constructor because it
     * rides the pump's injected clock.
     */
    private final CoopWiretap wiretap;

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
        this.timeLock.setFastForwardLock(fastForwardLock);
        this.npcFleetRegistry = new CoopFleetMirrorRegistry(CoopFleetMirror::new, clockMillis);
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
        // Phase 18: vanilla's market-close callback, forwarded so a rejected claim stops being
        // tracked once the screen it referred to is confirmed gone.
        this.campaignReplicator.setMarketCloseObserver(this::onLocalMarketClosed);
        this.npcFleetReplicator = new CoopNpcFleetReplicator(service, sessionState, clockMillis,
                streamClock);
        this.baseAuthority = new CoopBaseAuthority(service, sessionState, clockMillis);
        // Phase 15: the host integrates every battle's campaign deltas through this one reconciler,
        // whether they arrived as a guest BATTLE_RESULT or came from the host's own battle bridge.
        this.battleResultReconciler = new CoopBattleResultReconciler(
                new CoopBattleResultReconciler.EngineFleets(
                        CoopNetPump::sectorOrNull,
                        npcFleetReplicator::forceResendSet,
                        coopFleetId -> npcThreatWatcher.noteBattleConcluded(
                                coopFleetId, clockMillis.getAsLong())));
        this.battleBridge.setBattleResultSink(this::onLocalBattleResult);
        this.battleBridge.setBattleFleetSink(new CoopBattleBridge.BattleFleetListener() {
            @Override
            public void onLocalBattleBegun(List<String> coopFleetIds) {
                CoopNetPump.this.onLocalBattleBegun(coopFleetIds);
            }

            @Override
            public void onBattleConcluded(List<String> coopFleetIds, boolean localBattle) {
                CoopNetPump.this.onBattleConcluded(coopFleetIds, localBattle);
            }
        });
        // Phase 16: the host's save callbacks arrive through the ModPlugin, which has no handle on
        // this pump; the static registration is how they reach it. The newest pump wins, so a game
        // load replaces the previous session's instance.
        this.saveCheckpoint.setSender(this::sendSaveCheckpoint);
        CoopSaveCheckpoint.setActive(this.saveCheckpoint);
        // Same static-seam deal as the profiler: the transport's send hook has no handle on the pump.
        this.wiretap = CoopWiretap.installFresh(clockMillis);
        long now = clockMillis.getAsLong();
        this.nextPingAtMillis = now + PING_INTERVAL_MILLIS;
        this.nextTimeSnapshotAtMillis = now + CoopTimeLock.SNAPSHOT_INTERVAL_MILLIS;
        this.nextGuestSnapshotAtMillis = now;
    }

    // ---- Phase 30 agent-bridge accessors (dev tooling) -----------------------------------------
    //
    // The dormant agent bridge (coop.debug.CoopAgentBridge, -Dcoop.debug.bridge=<port>) is installed
    // as its own transient script, deliberately outside this pump's session lifecycle: it has to
    // answer before and without an active coop session. When a session *is* up it still needs the
    // pump's collaborators to answer queries through the replication code rather than a parallel set
    // of readers, and it finds this pump by scanning the sector's transient scripts. These are plain
    // getters; nothing here mutates pump state.

    /** Bridge-only: the live session record (role, handshake, seed). */
    public CoopSessionState sessionStateForBridge() {
        return sessionState;
    }

    /** Bridge-only: the transport, for its connection role. */
    public CoopNetService netServiceForBridge() {
        return service;
    }

    /** Bridge-only: the owner of the market/survey/objective capture and apply facades. */
    public CoopCampaignReplicator campaignReplicatorForBridge() {
        return campaignReplicator;
    }

    /** Bridge-only: the shared pause coordinator behind the bridge's {@code pause} verb. */
    public CoopSharedPauseCoordinator pauseCoordinatorForBridge() {
        return pauseCoordinator;
    }

    /** Bridge-only: the same predicate the pump gates gameplay replication on. */
    public boolean gameplaySessionActiveForBridge() {
        return isGameplaySessionActive();
    }

    /**
     * Bridge-only: the very predicate the guest's screen-pause intent is driven by, so the bridge's
     * {@code status} verb reports the screen state that actually holds the shared clock rather than a
     * second opinion about what counts as a blocking screen.
     */
    public static boolean blockingScreenOpenForBridge(SectorAPI sector) {
        return sector != null && isVanillaBlockingScreenOpen(sector);
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
        // Instrumentation only (CoopFrameProfiler): dormant unless -Dcoop.debug.frameProfile=true or
        // the $coopFrameProfile memory flag is set, in which case each split() below is one clock read.
        // Disabled, every call here is a static boolean read and a return.
        profiler.beginFrame();
        // Same shape: CoopDebug.diagnosticsEnabled() is read 3-4x a frame from the hot paths, so the
        // property + sector-memory lookup behind it runs here on a 300-frame poll instead.
        CoopDebug.pollFrame();
        // Same again for the datagram wiretap, which also emits its size summary from this poll.
        CoopWiretap.pollFrame();
        // Phase 29 M1: stream time advances by campaign dt, frozen while paused, before anything
        // this frame stamps an outbound datagram with it.
        streamClock.advance(amount, isSectorPausedForStream());
        long t = profiler.start();
        maybeStartFromSystemProperties();
        t = profiler.split(SECTION_CFG_PROPERTIES, t);
        maybeStartFromMemoryFlags();
        t = profiler.split(SECTION_CFG_MEMORY_FLAGS, t);
        service.flushOutbound();
        t = profiler.split(SECTION_FLUSH_OUTBOUND_PRE, t);
        detectPeerDisconnect();
        t = profiler.split(SECTION_DETECT_DISCONNECT, t);
        syncGuestInputBlocker();
        t = profiler.split(SECTION_GUEST_INPUT_BLOCKER, t);
        maybeSendLobbyHello();
        t = profiler.split(SECTION_LOBBY_HELLO, t);
        drainInbound();
        // Immediately after the real drain, so a message released from the debug latency queue takes
        // the exact frame path a just-arrived one would: claims before the interaction gate runs,
        // pause intents before syncSharedPause computes the effective pause.
        drainDelayedGuestMessages();
        t = profiler.split(SECTION_DRAIN_INBOUND, t);
        assertMirrorEngagementShields();
        t = profiler.split(SECTION_MIRROR_SHIELDS, t);
        maybeSendHandshakeManifest();
        t = profiler.split(SECTION_HANDSHAKE_MANIFEST, t);
        maybeSendSeedLockRequest();
        t = profiler.split(SECTION_SEED_LOCK_REQUEST, t);
        maybeHoldHostPausedUntilSessionReady();
        t = profiler.split(SECTION_HOLD_HOST_PAUSED, t);
        // Phase 14 runs before syncSharedPause so a battle that began (or ended) this frame is already
        // reflected in the combat intent when the host computes its effective pause.
        tickBattleBridge();
        t = profiler.split(SECTION_BATTLE_BRIDGE, t);
        syncSharedPause();
        t = profiler.split(SECTION_SHARED_PAUSE, t);
        syncFastForwardLock();
        t = profiler.split(SECTION_TIME_FAST_FORWARD, t);
        maybeApplyTimeSnapshot();
        t = profiler.split(SECTION_TIME_APPLY, t);
        tickClockReconciler(amount);
        t = profiler.split(SECTION_TIME_CLOCK_RECONCILE, t);
        maybeSendTimeSnapshot();
        t = profiler.split(SECTION_TIME_SEND, t);
        syncFleetMirror();
        t = profiler.split(SECTION_FLEET_MIRROR, t);
        drainFleetDatagrams();
        t = profiler.split(SECTION_FLEET_DATAGRAMS, t);
        // Immediately after the drain so this frame renders this frame's samples: advance the shared
        // render cursor by campaign dt and place every mirror on its buffered trajectory (Phase 29 M1).
        advanceMirrorMotion(amount);
        maybeSendFleetSnapshot();
        t = profiler.split(SECTION_FLEET_SNAPSHOT_SEND, t);
        tickRespawnNotifier();
        t = profiler.split(SECTION_RESPAWN_NOTIFIER, t);
        maybeSendGuestSnapshot();
        t = profiler.split(SECTION_GUEST_SNAPSHOT_SEND, t);
        tickSaveCheckpoint();
        t = profiler.split(SECTION_SAVE_CHECKPOINT, t);
        syncNpcReplication();
        t = profiler.split(SECTION_NPC_REPLICATION, t);
        tickNpcThreatWatcher();
        t = profiler.split(SECTION_NPC_THREAT_WATCHER, t);
        syncBaseReplication();
        t = profiler.split(SECTION_BASE_REPLICATION, t);
        syncBarGeneration();
        t = profiler.split(SECTION_BAR_SUPPRESSOR, t);
        syncInteractionGate();
        t = profiler.split(SECTION_INTERACTION_GATE, t);
        debugDialogState();
        t = profiler.split(SECTION_DEBUG_DIALOG_STATE, t);
        tickCombatSpike();
        t = profiler.split(SECTION_COMBAT_SPIKE, t);
        syncCampaignReplicator();
        t = profiler.split(SECTION_REPLICATOR_SYNC, t);
        campaignReplicator.tickWorldDeltas();
        t = profiler.split(SECTION_REPLICATOR_WORLD_DELTAS, t);
        campaignReplicator.tickOrbitSync();
        t = profiler.split(SECTION_REPLICATOR_ORBIT_SYNC, t);
        campaignReplicator.tickPlayerRepSync();
        t = profiler.split(SECTION_REPLICATOR_REP_SYNC, t);
        campaignReplicator.tickBarPool();
        t = profiler.split(SECTION_REPLICATOR_BAR_POOL, t);
        campaignReplicator.tickColonyLifecycle();
        t = profiler.split(SECTION_REPLICATOR_COLONY, t);
        campaignReplicator.tickColonyManagement();
        t = profiler.split(SECTION_REPLICATOR_COLONY_MGMT, t);
        campaignReplicator.tickColonyIncome();
        t = profiler.split(SECTION_REPLICATOR_COLONY_INCOME, t);
        campaignReplicator.tickExpeditionWarnings();
        t = profiler.split(SECTION_REPLICATOR_EXPEDITIONS, t);
        maybeSendPing();
        t = profiler.split(SECTION_PING, t);
        service.flushOutbound();
        profiler.record(SECTION_FLUSH_OUTBOUND_POST, t);
        profiler.endFrame();
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
            long dispatchStart = profiler.start();
            try {
                dispatchInbound(message);
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopNetPump.class, "Coop dropped malformed/unexpected message type="
                        + message.type() + " seq=" + message.seq(), ex);
            }
            // Per-type so one expensive handler stands out in the summary rather than hiding inside
            // the aggregate drain cost. Runs on the throwing path too: the catch above swallows.
            profiler.record(SECTION_BY_MESSAGE_TYPE[message.type().ordinal()], dispatchStart);
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
            case BATTLE_RESULT -> handleBattleResult(message);
            case GUEST_SNAPSHOT -> handleGuestSnapshot(message);
            case SAVE_CHECKPOINT -> handleSaveCheckpoint(message);
            case RESPAWN_PLAYER -> handleRespawnPlayer(message);
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

    private static boolean isVanillaBlockingScreenOpen(SectorAPI sector) {
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
        // Phase 18 latency lever: the pause intent rides the same guest->host leg as the interaction
        // claim, so a faithful WAN simulation must delay both. Without this the guest's screen-pause
        // freezes the host within a TIME_SNAPSHOT cadence and the claim race is unreachable by hand
        // on localhost — the exact gap the lever exists to close. Same queue as the claims, so the
        // two release in receive order relative to each other, which is what a real link preserves.
        int delayMillis = CoopDebug.interactionClaimDelayMillis();
        if (delayMillis > 0 && queueDelayedGuestMessage(message, delayMillis)) {
            return;
        }
        applyPauseIntent(message);
    }

    private void applyPauseIntent(CoopMessages.Message message) {
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

        // Phase 7c: read the LOCAL pause state before applying the snapshot. The reconciler's
        // pause-agreement gate discards samples taken across a pause mirror edge (the two clocks are
        // legitimately running at different rates for those frames), and timeLock.apply() below is
        // exactly what closes that edge — reading after it would make the gate a no-op.
        boolean guestPausedAtMeasurement = isSectorPausedForStream();

        try {
            timeLock.apply(latestTimeSnapshot);
            // The guest's clock mirrors the host snapshot; record it so the guest pause key resolves
            // against the observed state instead of blindly toggling a private intent.
            pauseCoordinator.setObservedPaused(latestTimeSnapshot.paused());
            // The snapshot is re-applied every frame, but it is one measurement: feed it to the
            // reconciler once, or the ring fills at frame rate with one stale host stamp against an
            // advancing guest clock and the estimate biases negative by up to a snapshot interval.
            if (latestTimeSnapshot != lastReconciledTimeSnapshot) {
                lastReconciledTimeSnapshot = latestTimeSnapshot;
                clockReconciler.onSnapshot(latestTimeSnapshot.timestampMillis(),
                        latestTimeSnapshot.paused(), latestTimeSnapshot.fastForward(),
                        guestPausedAtMeasurement);
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply coop time snapshot", ex);
        }
    }

    /**
     * Phase 7c frame entry, guest-only. Also owns the reconciler's two ring-invalidating edges: the
     * session edge (pre-session samples are meaningless) and the guest's interaction-dialog
     * open&rarr;closed edge (vanilla owned the local clock while the dialog was up, so every sample
     * taken before it is stale). The host never ticks: its clock is the authority.
     */
    private void tickClockReconciler(float amount) {
        boolean active = isGameplaySessionActive();
        if (active != clockReconcilerArmed) {
            clockReconcilerArmed = active;
            clockReconciler.clearSamples();
            clockReconcilerDialogWasOpen = false;
        }
        if (!active || service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        boolean dialogOpen = isGuestInteractionDialogOpen();
        if (clockReconcilerDialogWasOpen && !dialogOpen) {
            clockReconciler.clearSamples();
        }
        clockReconcilerDialogWasOpen = dialogOpen;
        try {
            clockReconciler.tick(amount, isSectorPausedForStream());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to reconcile the guest campaign clock", ex);
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
        // One clock read for the whole pass: the mirrors rate-limit their engine call against it (the
        // noEngaging fader lasts ~1 s, so refreshing it every frame per mirror was pure allocation).
        long now = clockMillis.getAsLong();
        fleetMirror.assertEngagementShield(now);
        // Phase 14b: the remote player's sensor identity has to be re-pinned every frame — the engine
        // rewrites sensor strength from the roster each frame and local terrain re-applies its own
        // detectability mods each frame. See CoopSensorSync.
        fleetMirror.assertSensorState();
        if (npcFleetRegistry.size() == 0) {
            // Host (or a guest before the first NPC_FLEET_SET): nothing to shield, so skip the sector
            // read entirely rather than paying for it every frame.
            return;
        }
        npcFleetRegistry.assertEngagementShields(playerEngagementTargetOrNull(), now);
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
        if (!fleetSnapshotCadence.shouldSend(streamClock.gameTimeMillis(),
                clockMillis.getAsLong(), streamClock.isFrozen())) {
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
            String datagram = datagramRedundancy.compose(
                    sessionState.sessionId(), CoopMessages.Type.FLEET_SNAPSHOT,
                    streamClock.nextEpoch(), streamClock.gameTimeMillis(), snapshot.encode());
            service.sendDatagram(datagram);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to capture coop fleet snapshot", ex);
        }
    }

    /**
     * Phase 7b: while a session is live BOTH roles run vanilla's toggle fast-forward mode and the
     * same {@code campaignSpeedupMult}; when it ends, the local client goes back to whatever the
     * player had. Two branches only — the lock tracks the was-enforcing edge itself.
     */
    private void syncFastForwardLock() {
        if (service.role() != CoopConnectionRole.NONE && isGameplaySessionActive()) {
            fastForwardLock.enforceSessionState();
        } else {
            fastForwardLock.restoreDefaultsIfEnforcing();
        }
    }

    // ---- Phase 17: fleet wipe --------------------------------------------------------------------

    /**
     * Wiped client: notice vanilla's respawn swapping the player fleet out from under us and tell the
     * partner. The mod builds none of the respawn — {@code CampaignState.showShuttleDialog()} already
     * hands back two ships, 80% of the credits, the officers, the skills and the reputation — this is
     * the notification the partner would otherwise never get.
     *
     * <p>The detection is reset whenever the session stops streaming, so a reconnect (or a session that
     * has not started yet) re-seeds the tracked reference instead of banner-ing on the first swap it
     * happens to see.
     */
    private void tickRespawnNotifier() {
        if (!shouldStreamFleet()) {
            respawnNotifier.reset();
            return;
        }
        CoopRespawnNotifier.Respawn respawn =
                respawnNotifier.onFrame(CoopRespawnNotifier.engineProbe(sectorOrNull()));
        if (respawn == null) {
            return;
        }
        String localPlayerId = sessionState.localPlayerId();
        if (localPlayerId == null) {
            return;
        }
        CoopLog.info(CoopNetPump.class, "Coop local fleet was destroyed; vanilla respawn placed it at "
                + (respawn.destinationName().isEmpty() ? "an unknown destination" : respawn.destinationName()));
        try {
            CoopMessages.Message message = CoopMessages.respawnPlayer(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    localPlayerId, respawn.destinationName());
            service.send(message);
            log("outbound", message);
            service.flushOutbound();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to send RESPAWN_PLAYER", ex);
        }
    }

    /** Partner: banner the wipe, since the only other cue is the mirror teleporting across the sector. */
    private void handleRespawnPlayer(CoopMessages.Message message) {
        if (!isGameplaySessionActive()) {
            return;
        }
        try {
            String playerId = CoopMessages.requiredPayloadString(message, "playerId");
            String destination = CoopMessages.requiredPayloadString(message, "destinationName");
            String name = playerId.equals(sessionState.remotePlayerId()) ? sessionState.remoteName() : null;
            if (name == null || name.isEmpty()) {
                name = "Remote player";
            }
            // ASCII only: no vanilla string in data/strings uses an em dash, so the campaign font is
            // not guaranteed to have the glyph and a missing one renders as a box.
            String banner = destination.isEmpty()
                    ? name + "'s fleet was destroyed - respawned elsewhere in the sector"
                    : name + "'s fleet was destroyed - respawned at " + destination;
            CoopLog.info(CoopNetPump.class, "Coop " + banner);
            SectorAPI sector = sectorOrNull();
            CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
            if (ui != null) {
                ui.addMessage(banner);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply RESPAWN_PLAYER", ex);
        }
    }

    // ---- Phase 16: coordinated saves + guest snapshot -------------------------------------------

    /**
     * Guest &rarr; host, every {@link #GUEST_SNAPSHOT_INTERVAL_MILLIS}: the guest's own campaign state,
     * so the host always has something current to embed when it saves. The first send is due
     * immediately once the session is live, so a host that saves early still gets a real snapshot.
     */
    private void maybeSendGuestSnapshot() {
        if (service.role() != CoopConnectionRole.GUEST || !service.isConnected()
                || !isGameplaySessionActive()) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now < nextGuestSnapshotAtMillis) {
            return;
        }
        try {
            CoopGuestSnapshot snapshot = CoopGuestSnapshotFactory.capture(
                    sectorOrNull(),
                    sessionState.sessionId(),
                    sessionState.localPlayerId(),
                    sessionState.localName(),
                    storedCampaignIdSupplier.get(),
                    sessionState.seedString(),
                    now);
            if (snapshot == null) {
                return;
            }
            CoopMessages.Message message = CoopMessages.guestSnapshot(
                    sessionState.sessionId(), service.nextSeq(), now, snapshot.encodeBody());
            service.send(message);
            log("outbound", message);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to send the coop guest snapshot", ex);
        } finally {
            nextGuestSnapshotAtMillis = now + GUEST_SNAPSHOT_INTERVAL_MILLIS;
        }
    }

    /** Host: hold the guest's latest state for the next {@code beforeGameSave()}. */
    private void handleGuestSnapshot(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        try {
            CoopGuestSnapshot snapshot = CoopGuestSnapshot.decodeBody(
                    CoopMessages.requiredPayloadString(message, "body"));
            CoopGuestSnapshotStore.publish(snapshot);
            if (CoopDebug.diagnosticsEnabled()) {
                CoopLog.info(CoopNetPump.class,
                        "Coop guest snapshot received: " + snapshot.summary());
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply GUEST_SNAPSHOT", ex);
        }
    }

    /**
     * Host side of {@link CoopSaveCheckpoint}: puts a checkpoint on the wire and flushes it straight
     * away. The flush is load-bearing for the session-end checkpoint, which is sent moments before the
     * transport is torn down and would otherwise die in the outbound queue.
     */
    private boolean sendSaveCheckpoint(long checkpointId, String reason) {
        if (service.role() != CoopConnectionRole.HOST || !service.isConnected()
                || !isGameplaySessionActive()) {
            return false;
        }
        try {
            CoopMessages.Message message = CoopMessages.saveCheckpoint(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    checkpointId, reason);
            service.send(message);
            log("outbound", message);
            service.flushOutbound();
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to send SAVE_CHECKPOINT", ex);
            return false;
        }
    }

    private void handleSaveCheckpoint(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        try {
            saveCheckpoint.onCheckpointReceived(
                    CoopMessages.requiredPayloadLong(message, "checkpointId"),
                    CoopMessages.requiredPayloadString(message, "reason"),
                    clockMillis.getAsLong());
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply SAVE_CHECKPOINT", ex);
        }
    }

    /** Guest: retry the parked coordinated autosave until a frame the engine will honour it. */
    private void tickSaveCheckpoint() {
        if (!saveCheckpoint.isAutosavePending()) {
            return;
        }
        if (service.role() != CoopConnectionRole.GUEST) {
            saveCheckpoint.cancel();
            return;
        }
        saveCheckpoint.tick(CoopSaveCheckpoint.engineTarget(sectorOrNull()), clockMillis.getAsLong());
    }

    private void drainFleetDatagrams() {
        String raw;
        while ((raw = service.pollDatagram()) != null) {
            try {
                CoopMessages.Datagram datagram = CoopMessages.parseDatagram(raw);
                // Wiretap before the session filter and the watermark: a datagram this side decoded
                // but then discarded is exactly what a desync investigation wants to see. Dormant
                // unless -Dcoop.debug.wiretap=true / $coopWiretap.
                wiretap.recordReceive(raw, datagram);
                if (!sessionMatches(datagram.sessionId())) {
                    continue;
                }
                // Sections at or below the per-type epoch watermark are dropped here: a reordered
                // datagram applies nothing, a redundant section that already arrived applies nothing,
                // and a redundant section covering a lost packet applies normally (oldest first).
                for (CoopMessages.DatagramSection section : datagramWatermark.accept(datagram)) {
                    switch (datagram.type()) {
                        case FLEET_SNAPSHOT -> applyFleetSnapshotSection(section);
                        case NPC_FLEET_MOTION -> handleNpcFleetMotion(section);
                        default -> { /* ignore unknown datagram types */ }
                    }
                }
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopNetPump.class, "Failed to apply coop fleet datagram", ex);
            }
        }
    }

    private void applyFleetSnapshotSection(CoopMessages.DatagramSection section) {
        CoopFleetSnapshot snapshot = CoopFleetSnapshot.decode(section.body());
        // Ignore our own echoed datagrams and any sender that is not the remote coop player.
        if (!snapshot.playerId().equals(sessionState.remotePlayerId())) {
            return;
        }
        double sampleTimeSeconds = section.sentGameTimeMillis() / 1000.0;
        motionTimeline.noteSample(sampleTimeSeconds);
        fleetMirror.apply(snapshot, localPlayerFactionId(), sampleTimeSeconds);
    }

    private void handleNpcFleetMotion(CoopMessages.DatagramSection section) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        List<CoopNpcFleetMotion> motions = CoopNpcFleetMotion.decodeBatch(section.body());
        double sampleTimeSeconds = section.sentGameTimeMillis() / 1000.0;
        motionTimeline.noteSample(sampleTimeSeconds);
        npcFleetRegistry.applyMotion(motions, sampleTimeSeconds);
    }

    /**
     * Phase 29 M1: one cursor read per frame, then every mirror (partner player mirror + NPC mirrors)
     * renders its buffered trajectory at it. The cursor advances by campaign dt — zero while paused,
     * so a shared pause freezes all mirrors in place and unpause resumes without a hop.
     */
    private void advanceMirrorMotion(float amount) {
        double gameDt = isSectorPausedForStream() ? 0.0 : amount;
        double cursor = motionTimeline.advance(gameDt);
        if (Double.isNaN(cursor)) {
            return;
        }
        fleetMirror.advanceMotion(cursor);
        npcFleetRegistry.advanceMotion(cursor);
        if (CoopDebug.diagnosticsEnabled()) {
            String report = coop.fleet.CoopMotionSpeedProbe.INSTANCE.maybeReport(clockMillis.getAsLong());
            if (report != null) {
                CoopLog.info(CoopNetPump.class, report);
            }
        }
    }

    private void handleNpcFleetSet(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        try {
            // One payload parse for both fields — the set blob is large and this path is profiled.
            java.util.Map<String, Object> payload = CoopMessages.decodePayload(message);
            String encoded = String.valueOf(payload.getOrDefault("set", ""));
            // The sender's stream-time stamp (Phase 29 M1) so set-fed positions land in the same
            // interpolation buffers the UDP motion sections fill.
            double sampleTimeSeconds =
                    payload.get("gameTimeMillis") instanceof Number n ? n.longValue() / 1000.0 : 0.0;
            motionTimeline.noteSample(sampleTimeSeconds);
            // Split-stamped so the profiler can separate decode cost from apply cost; both stamps are
            // 0 and noteNpcSetApply is a no-op when profiling is off.
            long decodeStart = profiler.start();
            CoopNpcFleetSetSnapshot set = CoopNpcFleetSetSnapshot.decode(encoded);
            long applyStart = profiler.start();
            npcFleetRegistry.applySet(set, sampleTimeSeconds);
            profiler.noteNpcSetApply(encoded.length(), decodeStart, applyStart);
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
            battleResultReconciler.reset();
            datagramWatermark.reset();
            datagramRedundancy.reset();
            motionTimeline.reset();
            coop.fleet.CoopMotionSpeedProbe.INSTANCE.reset();
            wiretap.sessionStarted();
            npcReplicationStreaming = true;
        } else if (!active && npcReplicationStreaming) {
            // Session ended: drop all guest NPC mirrors so no stale AI fleet is left behind.
            npcFleetRegistry.disposeAll();
            datagramWatermark.reset();
            datagramRedundancy.reset();
            motionTimeline.reset();
            coop.fleet.CoopMotionSpeedProbe.INSTANCE.reset();
            // Final size summary while the numbers still exist — this is the Phase 20.1 histogram.
            wiretap.sessionEnded();
            npcReplicationStreaming = false;
            lastNpcDebug = null;
            lastNpcMirrorCount = -1;
            lastNpcMirrorIdsHash = 0;
        }
        if (!active) {
            return;
        }
        try {
            if (service.role() == CoopConnectionRole.HOST) {
                // Sub-sections (npc.systemDriver / npc.guestPresence / npc.sendSet / npc.sendMotion)
                // are recorded from inside the replicator through the profiler's static seam.
                npcFleetReplicator.tick();
            } else if (CoopNpcFleetSuppressor.activeForRole(service.role())) {
                long suppressorStart = profiler.start();
                npcFleetSuppressor.tick(Global.getSector(), clockMillis.getAsLong());
                profiler.record(SECTION_NPC_SUPPRESSOR, suppressorStart);
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

    /**
     * Phase 12c guest bar-offer suppression. The host's portside bar pool is the only pool, so the
     * guest's {@code BarEventManager} script registration is removed once per session (its
     * sector-memory instance stays — see {@link CoopBarGenerationSuppressor}). Vanilla re-adds the
     * script on every game load, so the session edge re-arms the removal, exactly like the Phase 9
     * fleet spawner suppression.
     */
    private void syncBarGeneration() {
        boolean active = service.isConnected() && isGameplaySessionActive();
        if (active && !barSuppressionArmed) {
            barGenerationSuppressor.reset();
            barSuppressionArmed = true;
        } else if (!active && barSuppressionArmed) {
            barSuppressionArmed = false;
        }
        if (!active || !CoopBarGenerationSuppressor.activeForRole(service.role())) {
            return;
        }
        try {
            barGenerationSuppressor.tick(Global.getSector());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to suppress guest bar event generation", ex);
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
            // Pre-check before building anything: size plus a rolling hash of the ids. The set copy
            // and the concat below are only worth paying for when one of them moved (perf audit #16).
            int size = npcFleetRegistry.size();
            int idsHash = npcFleetRegistry.fleetIdsHash();
            if (size == lastNpcMirrorCount && idsHash == lastNpcMirrorIdsHash) {
                maybeDumpVisibilityProbe();
                return;
            }
            lastNpcMirrorCount = size;
            lastNpcMirrorIdsHash = idsHash;
            state = "guest mirrors=" + size + " ids=" + npcFleetRegistry.fleetIds();
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
        int delayMillis = CoopDebug.interactionClaimDelayMillis();
        if (delayMillis > 0 && queueDelayedGuestMessage(message, delayMillis)) {
            return;
        }
        arbitrateInteractionClaim(message);
    }

    /**
     * Phase 18 latency lever. Parks the message with a release stamp instead of sleeping: the pump
     * thread is the campaign thread, so blocking here would stall the whole game rather than
     * simulate a slow link. {@link #drainDelayedGuestMessages()} releases in receive order, which is
     * exactly the ordering {@code CoopInteractionGate} arbitrates on — and, because
     * {@code PAUSE_INTENT} shares the queue, the same relative order a real link would deliver the
     * guest's pause alongside its claim.
     *
     * @return true when the message was parked (the caller must not process it now).
     */
    private boolean queueDelayedGuestMessage(CoopMessages.Message message, int delayMillis) {
        if (delayedInteractionClaims.size() >= MAX_DELAYED_INTERACTION_CLAIMS) {
            CoopLog.warn(CoopNetPump.class, "Coop debug interaction-delay queue is full ("
                    + MAX_DELAYED_INTERACTION_CLAIMS + "); processing " + message.type() + " seq="
                    + message.seq() + " immediately");
            return false;
        }
        long releaseAt = clockMillis.getAsLong() + delayMillis;
        delayedInteractionClaims.addLast(new DelayedInteractionClaim(message, releaseAt));
        CoopLog.info(CoopNetPump.class, "Coop debug delaying " + message.type() + " seq="
                + message.seq() + " by " + delayMillis + "ms ("
                + CoopDebug.INTERACTION_DELAY_PROPERTY + ")");
        return true;
    }

    /** Releases every parked message whose stamp has passed. No-op (one size check) when dormant. */
    private void drainDelayedGuestMessages() {
        if (delayedInteractionClaims.isEmpty()) {
            return;
        }
        long now = clockMillis.getAsLong();
        while (!delayedInteractionClaims.isEmpty()
                && delayedInteractionClaims.peekFirst().releaseAtMillis() <= now) {
            DelayedInteractionClaim due = delayedInteractionClaims.pollFirst();
            try {
                if (due.message().type() == CoopMessages.Type.PAUSE_INTENT) {
                    applyPauseIntent(due.message());
                } else {
                    arbitrateInteractionClaim(due.message());
                }
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNetPump.class, "Coop dropped delayed " + due.message().type()
                        + " seq=" + due.message().seq(), ex);
            }
        }
    }

    private void arbitrateInteractionClaim(CoopMessages.Message message) {
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

    /**
     * Our optimistic local interaction lost the race (Phase 18).
     *
     * <p>Two things deliberately do <em>not</em> happen here. The dialog is not dismissed on this
     * call: we are inside the inbound drain, several pump steps before the interaction gate runs,
     * and tearing the screen down from under a half-processed frame is exactly the kind of
     * mid-frame engine surgery that has bitten this mod before. It is deferred one frame to
     * {@link #forceCloseRejectedDialog}, which re-issues until the dialog is really gone while
     * {@link #applyLocalBlocking} re-asserts the block every frame in the meantime.
     *
     * <p>And {@code localInteractionEntityId} is not cleared. Clearing it was the reject re-claim
     * loop: the per-frame detector then saw an untracked open dialog and claimed it again, every
     * frame, at up to 60 msg/s plus a warn per frame. Keeping it, plus the reject tracker, means
     * one claim and one reject per lost race.
     */
    private void handleInteractionReject(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        String entityId = CoopMessages.requiredPayloadString(message, "entityId");
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        if (rejectTracker.onRejected(entityId)) {
            CoopLog.warn(CoopNetPump.class, "Coop interaction rejected entityId=" + entityId + " "
                    + reason + "; closing the local dialog");
        }
    }

    private void handleInteractionRelease(CoopMessages.Message message) {
        String entityId = CoopMessages.requiredPayloadString(message, "entityId");
        String playerId = CoopMessages.requiredPayloadString(message, "playerId");
        interactionGate.release(entityId, playerId);
        // Deliberately does NOT cancel a pending forced close. If the winner releases the entity in
        // the same breath as our reject, letting our dialog stay would leave the guest holding an
        // open, unclaimed shop the host is now free to open too - the exact both-in-one-shop state
        // this phase exists to prevent. The dialog closes; re-docking re-claims it cleanly.
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
        InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
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

        // Phase 18: a claim the host rejected takes the dialog away again, before any of the
        // claim/release bookkeeping below runs for it.
        if (forceCloseRejectedDialog(ui, dialog, currentEntityId, currentEntityName)) {
            return;
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
        // guest requests the claim from the host. A still-rejected entity is skipped so a dialog
        // that reopens inside the forced-close window cannot restart the claim/reject ping-pong.
        if (currentEntityId != null && !rejectTracker.isRejected(currentEntityId)) {
            beginLocalInteraction(currentEntityId, currentEntityName);
        }
    }

    /**
     * Phase 18 forced close: dismiss the local player's dialog for an entity whose claim the host
     * rejected, on the frame after the reject arrived, and keep re-issuing until it is gone.
     *
     * <p>Narrow by construction — {@link CoopRejectTracker#onFrame} only fires while the dialog
     * currently open is the rejected one, so a dialog on any other entity is never touched.
     *
     * <p>Interaction with the rest of the guest machinery is passive: the input blocker is
     * suspended/unsuspended by {@code syncGuestInputBlocker} from whether a blocking screen is
     * open, so it releases by itself on the frame after the dialog goes; and nothing here touches
     * {@code setPaused} — the guest's screen-pause intent simply stops being sent once the dialog
     * closes, and the host's snapshot resumes driving the guest clock.
     *
     * @return true when a dismissal was issued this frame (the caller skips its claim/release
     *         bookkeeping while the close is in flight).
     */
    private boolean forceCloseRejectedDialog(CampaignUIAPI ui, InteractionDialogAPI dialog,
                                             String openEntityId, String openEntityName) {
        CoopRejectTracker.Action action = rejectTracker.onFrame(openEntityId);
        if (action == CoopRejectTracker.Action.NONE) {
            return false;
        }
        try {
            if (action == CoopRejectTracker.Action.DISMISS_AND_NOTIFY) {
                String name = openEntityName == null || openEntityName.isEmpty()
                        ? "this location" : openEntityName;
                // ASCII only: the campaign font is not guaranteed to carry an em dash (see the
                // Phase 17 respawn banner).
                String banner = remoteDisplayName() + " is using " + name + " - try again shortly";
                if (ui != null) {
                    ui.addMessage(banner);
                }
                CoopLog.info(CoopNetPump.class, "Coop force-closing rejected interaction dialog"
                        + " entityId=" + openEntityId);
                if (ui != null && ui.getCurrentCoreTab() != null) {
                    // Only reachable if the reject landed after the player already opened a core
                    // tab (trade/refit) from the dialog, which the ~100 ms race window makes very
                    // unlikely. Dismiss anyway: an open shop on the losing client is the defect
                    // this phase exists to close. Logged so a live occurrence is not a mystery.
                    CoopLog.warn(CoopNetPump.class, "Coop force-closing a rejected dialog while core"
                            + " tab " + ui.getCurrentCoreTab() + " is open entityId=" + openEntityId);
                }
            }
            if (dialog != null) {
                dialog.dismiss();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to force-close rejected interaction dialog"
                    + " entityId=" + openEntityId, ex);
        }
        return true;
    }

    /** Display name for the player holding the claim we lost; falls back to a neutral label. */
    private String remoteDisplayName() {
        String name = sessionState.remoteName();
        return name == null || name.isEmpty() ? "The other player" : name;
    }

    /**
     * Phase 18: {@code reportPlayerClosedMarket} routed through the campaign replicator. Only a
     * confirmation signal for the reject bookkeeping — see {@link CoopRejectTracker} for why the
     * per-frame observation stays authoritative — and the hook Phase 24's diff-on-close will use.
     */
    private void onLocalMarketClosed(String entityId, String marketId) {
        if (rejectTracker.onDialogClosed(entityId) || rejectTracker.onDialogClosed(marketId)) {
            CoopLog.info(CoopNetPump.class, "Coop rejected interaction cleared by market close"
                    + " marketId=" + marketId);
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
        // Session end / disconnect also ends any pending forced close and drops parked claims: the
        // dialog the rejection referred to belongs to a session that no longer exists.
        boolean hadRejection = rejectTracker.clear();
        boolean hadDelayedClaims = !delayedInteractionClaims.isEmpty();
        delayedInteractionClaims.clear();
        if (localInteractionEntityId == null && lastBlockedEntityName == null
                && !hadRejection && !hadDelayedClaims) {
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
     * Phase 15 host side: the guest fought a battle and is reporting its campaign consequences. Only
     * the host integrates — {@code BATTLE_RESULT} is a guest&rarr;host report, never a broadcast.
     */
    private void handleBattleResult(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        try {
            CoopBattleResult result = CoopBattleResult.decode(
                    CoopMessages.requiredPayloadString(message, "battleId"),
                    CoopMessages.requiredPayloadString(message, "engagingPlayerId"),
                    CoopMessages.requiredPayloadString(message, "outcome"),
                    (int) CoopMessages.requiredPayloadLong(message, "engagingFleetSize"),
                    CoopMessages.requiredPayloadString(message, "body"));
            battleResultReconciler.apply(result);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply BATTLE_RESULT", ex);
        }
    }

    /**
     * The local client just finished piloting a battle. The host integrates it in-process; the guest
     * freezes the mirrors it beat and reports to the host. There is deliberately no path where a
     * client sends itself a {@code BATTLE_RESULT}.
     */
    private void onLocalBattleResult(CoopBattleResult result) {
        try {
            if (service.role() == CoopConnectionRole.HOST) {
                battleResultReconciler.apply(result);
                return;
            }
            long now = clockMillis.getAsLong();
            for (String coopFleetId : result.involvedFleetIds()) {
                npcFleetRegistry.markPendingReconcile(coopFleetId, now);
            }
            service.send(CoopMessages.battleResult(sessionState.sessionId(), service.nextSeq(), now,
                    result.battleId(), result.engagingPlayerId(), result.outcome(),
                    result.engagingFleetSize(), result.encodeBody()));
            service.flushOutbound();
            CoopLog.info(CoopNetPump.class, "Coop BATTLE_RESULT sent battleId=" + result.battleId()
                    + " destroyed=" + result.destroyedFleetIds().size()
                    + " survivors=" + result.survivingFleets().size());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to dispatch the local coop battle result", ex);
        }
    }

    /**
     * The local client has just started piloting a battle against these host fleets. The guest
     * freezes their mirrors immediately — see {@code CoopBattleBridge.BattleFleetListener} for the
     * drain-ordering reason this cannot wait for the battle to end. The host has nothing to do here:
     * it fights the real fleets and vanilla keeps them correct.
     */
    private void onLocalBattleBegun(List<String> coopFleetIds) {
        if (service.role() == CoopConnectionRole.HOST) {
            return;
        }
        long now = clockMillis.getAsLong();
        for (String coopFleetId : coopFleetIds) {
            try {
                npcFleetRegistry.markPendingReconcile(coopFleetId, now);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNetPump.class,
                        "Failed to freeze the mirror of coopFleetId=" + coopFleetId, ex);
            }
        }
    }

    /**
     * A battle just ended, with the host {@code coopFleetId}s it involved. Fired well ahead of the
     * result itself so neither side acts on stale pacing state while reconciliation is in flight.
     *
     * <p>The host restarts those fleets' engage cooldowns whichever side fought — a fleet the guest
     * just beat must not re-fire {@code ENGAGE_GUEST} into the reconciliation gap. The guest freezes
     * mirrors only for battles <em>it</em> fought: a fleet the host fought is already reconciled in
     * the host's own world, so its next {@code NPC_FLEET_SET} is the truth and must be applied, not
     * held back.
     */
    private void onBattleConcluded(List<String> coopFleetIds, boolean localBattle) {
        long now = clockMillis.getAsLong();
        boolean host = service.role() == CoopConnectionRole.HOST;
        if (!host && !localBattle) {
            return;
        }
        for (String coopFleetId : coopFleetIds) {
            try {
                if (host) {
                    npcThreatWatcher.noteBattleConcluded(coopFleetId, now);
                } else {
                    npcFleetRegistry.markPendingReconcile(coopFleetId, now);
                }
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopNetPump.class,
                        "Failed to note the end of a coop battle for coopFleetId=" + coopFleetId, ex);
            }
        }
    }

    private static SectorAPI sectorOrNull() {
        try {
            return Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /**
     * Whether stream time should hold this frame. No sector (title screen, teardown) counts as
     * paused: stamps must only advance while the campaign world actually moves, or the receiver's
     * interpolation cursor would chase time that no fleet motion ever filled.
     */
    private static boolean isSectorPausedForStream() {
        SectorAPI sector = sectorOrNull();
        if (sector == null) {
            return true;
        }
        try {
            return sector.isPaused();
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
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

    /** An inbound claim parked by the Phase 18 latency lever, with the frame stamp it is due at. */
    private record DelayedInteractionClaim(CoopMessages.Message message, long releaseAtMillis) {
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
