package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import coop.campaign.CoopBarGenerationSuppressor;
import coop.campaign.CoopBaseAuthority;
import coop.campaign.CoopCampaignReplicator;
import coop.combat.CoopBattleBridge;
import coop.combat.CoopBattleResult;
import coop.combat.CoopBattleResultReconciler;
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
import coop.ui.CoopHudState;
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
    // Campaign fleet snapshots stream at 10 Hz over UDP (COOP_MP_DESIGN.md section 8.4). Since
    // Phase 29 M2 the rate is one of CoopCadenceTier's certified tiers rather than a fixed constant;
    // this is the tier every session starts and re-starts at.
    private static final long FLEET_SNAPSHOT_INTERVAL_MILLIS =
            CoopCadenceTier.DEFAULT.intervalMillis();
    /**
     * How long a chosen interpolation delay is held before another change is allowed. Five seconds is
     * one {@code LINK_STATUS} interval: long enough that the delay is a setting rather than a signal
     * the cursor is chasing, short enough that a link that genuinely turned jittery is accommodated
     * inside the first few seconds of it.
     */
    private static final long INTERP_DELAY_HOLD_MILLIS = 5_000L;
    /**
     * Smallest change in the interpolation delay worth applying: a quarter of the default tier's send
     * interval. The delay is sized from an estimator whose own output moves a few milliseconds either
     * way on a link that is doing nothing unusual, and every applied change moves the timeline's
     * target; a step below this is estimator noise, not a link that changed.
     */
    private static final long INTERP_DELAY_STEP_MILLIS = 25L;
    /** How often each side reports what it is receiving. */
    private static final long LINK_STATUS_INTERVAL_MILLIS = 5_000L;
    /** How often the fallback/degraded rules are evaluated. Cheap; the rules are all time thresholds. */
    private static final long LINK_EVAL_INTERVAL_MILLIS = 1_000L;
    /** A peer LINK_STATUS older than this is no longer evidence about the peer's UDP path. */
    private static final long PEER_LINK_STATUS_FRESH_MILLIS = 10_000L;
    /** The guest logs its connection doctor block this long after session start even if no UDP came. */
    private static final long GUEST_DOCTOR_DEADLINE_MILLIS = 15_000L;
    /** Per-kind rate limit for the campaign-feed connection notices. */
    private static final long FEED_MIN_INTERVAL_MILLIS = 30_000L;
    private static final String FEED_FALLBACK = "fallback";
    private static final String FEED_CADENCE_DOWN = "cadenceDown";
    private static final String FEED_CADENCE_UP = "cadenceUp";
    private static final String FEED_FALLBACK_RECOVERED = "fallbackRecovered";
    private static final String FEED_DEGRADED = "degraded";
    private static final String FEED_DEGRADED_RECOVERED = "degradedRecovered";
    private static final String FEED_RECONNECT_WAIT = "reconnectWait";
    private static final String FEED_RECONNECT_RESUMED = "reconnectResumed";
    private static final String FEED_RECONNECT_ENDED = "reconnectEnded";
    private static final String FEED_RECONNECT_EXTENDED = "reconnectExtended";
    private static final String FEED_RECONNECT_RELAUNCHED = "reconnectRelaunched";
    /**
     * How long a guest that has just sent a deterministic reject holds its socket open so the reject
     * can leave it. Two seconds is far past any local flush and still short enough that a player
     * staring at the failure dialog never notices it; the ordinary case exits in the same frame.
     */
    private static final long TERMINAL_STOP_LINGER_MILLIS = 2_000L;
    private static final String FEED_LOBBY_JOINED = "lobbyJoined";
    private static final String FEED_LOBBY_LEFT = "lobbyLeft";
    private static final String FEED_LOBBY_READY = "lobbyReady";
    private static final String FEED_LOBBY_UNREADY = "lobbyUnready";
    private static final String FEED_LOBBY_RESET = "lobbyReset";
    private static final String FEED_LOBBY_COUNTDOWN = "lobbyCountdown";
    private static final String FEED_LOBBY_CANCELLED = "lobbyCancelled";
    private static final String FEED_LOBBY_STARTED = "lobbyStarted";
    private static final String FEED_LOBBY_CANCELLED_JOIN = "lobbyCancelledJoin";
    private static final String FEED_DESYNC = "desync";
    private static final String FEED_OPTIONS = "options";
    private static final java.awt.Color FEED_WARN_COLOR = new java.awt.Color(255, 220, 120);
    private static final java.awt.Color FEED_BAD_COLOR = new java.awt.Color(255, 170, 90);
    private static final java.awt.Color FEED_GOOD_COLOR = new java.awt.Color(150, 230, 150);
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
    /**
     * Synthetic claim id for the colony-management core tab (Phase 24 / the gate's blind spot). Not
     * an entity id: it is prefixed so it can never collide with one, and both players claim the same
     * one so the second player into the colony editor is refused the way a dialog race is.
     */
    static final String COLONY_MANAGEMENT_ENTITY_ID = "coop:colony-management";
    /** What the banners call {@link #COLONY_MANAGEMENT_ENTITY_ID}. */
    static final String COLONY_MANAGEMENT_ENTITY_NAME = "colony management";
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
    private static final String SECTION_HOLD_PAUSED = "session.holdPaused";
    private static final String SECTION_LOBBY = "session.lobby";
    private static final String SECTION_OPTIONS_POLICY = "session.optionsPolicy";
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
    private static final String SECTION_REPLICATOR_SYNC = "replicator.sync";
    private static final String SECTION_REPLICATOR_WORLD_DELTAS = "replicator.worldDeltas";
    private static final String SECTION_REPLICATOR_ORBIT_SYNC = "replicator.orbitSync";
    private static final String SECTION_REPLICATOR_REP_SYNC = "replicator.playerRepSync";
    private static final String SECTION_REPLICATOR_BAR_POOL = "replicator.barPool";
    private static final String SECTION_REPLICATOR_BAR_ACCEPT = "replicator.barAccept";
    private static final String SECTION_REPLICATOR_COLONY = "replicator.colonyLifecycle";
    private static final String SECTION_REPLICATOR_COLONY_MGMT = "replicator.colonyManagement";
    private static final String SECTION_REPLICATOR_COLONY_INCOME = "replicator.colonyIncome";
    private static final String SECTION_REPLICATOR_EXPEDITIONS = "replicator.expeditionWarnings";
    private static final String SECTION_REPLICATOR_MARKET_GATE = "replicator.marketSyncGate";
    private static final String SECTION_PING = "net.sendPing";
    private static final String SECTION_LINK_SUPERVISION = "net.linkSupervision";
    private static final String SECTION_RECONNECT = "net.reconnectGrace";
    private static final String SECTION_PORT_MAPPER = "net.portMapper";
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
    /** Phase 31: whether the game-version refusal has already produced its marker and dialog. */
    private boolean gameVersionRefusalRaised;
    private boolean memoryConfigWarningLogged;
    private boolean lobbyHelloSent;
    private boolean handshakeManifestSent;
    private boolean seedLockRequestSent;
    private boolean channelWasConnected;
    /**
     * Phase 20.6 HUD only. True when the last transport drop happened while a gameplay session was
     * live, i.e. we are in a 12b reconnect hold rather than a first connect. Set on the disconnect
     * edge and read nowhere else; no replication decision depends on it.
     */
    private boolean peerDroppedAfterLiveSession;
    /**
     * Host: the guest accepted this session's seed lock, i.e. it reached
     * {@link coop.session.CoopJoinPhase#SEED_LOCKED}. The host's own
     * {@link #isGameplaySessionActive()} cannot answer that question - it goes true when the host
     * records its own seed at {@code SEED_LOCK_REQUEST} time, several round trips earlier.
     */
    private boolean guestSeedLockAcked;
    /** Guest: the reject reason a terminal stop is waiting to send; null when none. See tickTerminalStop. */
    private String pendingTerminalStopReason;
    /** When that wait gives up and closes the loop regardless. */
    private long terminalStopDeadlineMillis;
    // ---- Phase 20 red-team state -----------------------------------------------------------------
    /** Shared throttle for every "this could be a packet flood" log line below. */
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 10_000L;
    /** A. A14: frames that threw out of the body, and the next frame one may be logged. */
    private long frameFailureCount;
    private long nextFrameFailureLogAtMillis;
    /** A11: state datagrams that failed to parse/apply, and the next frame one may be logged. */
    private long datagramDecodeFailureCount;
    private long nextDatagramDecodeLogAtMillis;
    /** A6/A9: datagrams whose senderId was not the remote coop player's token. */
    private long datagramSenderMismatchCount;
    private long nextSenderMismatchLogAtMillis;
    /**
     * A8: the newest accepted sender stream-time stamp, in the sender's own milliseconds.
     * {@link Long#MIN_VALUE} means "nothing accepted yet", which is the one sample that cannot be
     * bounded -- the two processes' stream clocks start at their own process starts and have no
     * agreed origin, so the first stamp from a peer is definitionally arbitrary.
     */
    private long latestAcceptedSampleMillis = Long.MIN_VALUE;
    private long rejectedSampleStampCount;
    private long nextSampleStampLogAtMillis;
    /** A4: messages refused because the sender had neither a session nor a grace window. */
    private long unprovenPeerAnswersRefused;
    private boolean unprovenPeerRefusalLogged;
    /** B2/C1: the transport's attach counter as of the last frame. */
    private long lastConnectionGeneration;
    private boolean connectionGenerationInitialized;
    /**
     * The generation {@link #consumeConnectionReplaced()} saw replaced this frame, or
     * {@link #NO_CONNECTION_GENERATION} when nothing was. Set only for the frame it happens on.
     */
    private long replacedConnectionGeneration = NO_CONNECTION_GENERATION;
    /**
     * The connection that carried the live session up to the most recent drop edge (net-fix-5), or
     * {@link #NO_CONNECTION_GENERATION}. A message stamped with this generation is the departing
     * partner's last word; a message stamped with anything else came from whoever holds the slot now,
     * which during a reconnect grace window is a socket that has proved nothing.
     *
     * <p>Never cleared once set, and it does not need to be: generations are monotonic, so a value
     * belonging to a dead socket can never match a live one again.
     */
    private long preDropConnectionGeneration = NO_CONNECTION_GENERATION;
    /** B5: the port mapper result version last published to the log and the intel page. */
    private long lastPublishedPortMapperVersion = -1L;
    /** C6: next frame a FLEET_ROSTER_REQUEST may go out. */
    private long nextRosterRequestAtMillis;
    /** C10: wireToken caches, re-derived whenever the id behind them changes. */
    private String cachedSessionId;
    private String cachedSessionToken = "";
    private String cachedLocalPlayerId;
    private String cachedLocalSenderToken = "";
    private String cachedRemotePlayerId;
    private String cachedRemoteSenderToken = "";
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
     * Phase 20 M4 roster split: the remote player's last {@code FLEET_ROSTER}, recombined with each
     * UDP tick into the full snapshot the mirror consumes. See {@link coop.fleet.CoopRosterCache}.
     */
    private final coop.fleet.CoopRosterCache rosterCache = new coop.fleet.CoopRosterCache();
    /** The 16-hex fleet hash of the last roster this side put on the wire; "" means "send one". */
    private String lastSentRosterHash = "";
    /** Types already warned about for exceeding the UDP budget; a 10 Hz stream may log once. */
    private final java.util.EnumSet<CoopMessages.Type> escalationLoggedTypes =
            java.util.EnumSet.noneOf(CoopMessages.Type.class);
    /**
     * Phase 20.1 M2 link supervision: RTT/loss/silence measurement and the UDP-blocked decision. It
     * only ever measures and reports — the TCP socket closing stays the sole disconnect trigger,
     * because a peer in combat or writing a coordinated autosave is legitimately silent for minutes
     * (its pump is not running) and no silence threshold can tell that apart from a dead link.
     */
    private final CoopLinkQuality linkQuality = new CoopLinkQuality();
    /** True once a gameplay session has armed the supervision; drives the session-edge reset. */
    private boolean linkSupervisionArmed;
    /** Whether the state streams are currently wrapped in {@code STATE_DATAGRAM} TCP messages. */
    private boolean stateStreamFallbackActive;
    /**
     * Phase 29 M2 adaptive cadence. The host runs the controller and announces its answer on
     * {@code LINK_STATUS}; the guest never evaluates one and applies what it is told, except that
     * its own TCP fallback pins the floor locally. One decision-maker per link is what keeps both
     * ends sending at the same rate, which is what makes an interpolation delay measured in send
     * intervals mean the same thing on both sides.
     */
    private final CoopCadenceController cadenceController = new CoopCadenceController();
    /** The tier this side's UDP state streams are sending at right now. */
    private CoopCadenceTier stateStreamTier = CoopCadenceTier.DEFAULT;
    /** The tier the peer last announced; the receive-side interval the interpolation delay is sized in. */
    private CoopCadenceTier peerCadenceTier = CoopCadenceTier.DEFAULT;
    /** The interpolation delay currently pushed into {@link #motionTimeline}, in milliseconds. */
    private long interpolationDelayMillis =
            Math.round(coop.fleet.CoopMotionTimeline.DELAY_SECONDS * 1000.0);
    /** Wall clock of the last delay change; the hold half of the delay hysteresis. */
    private long interpolationDelayChangedAtMillis;
    private long nextLinkStatusAtMillis;
    private long nextLinkEvalAtMillis;
    /** The peer's latest {@code LINK_STATUS}; the other half of the UDP-blocked evidence. */
    private CoopMessages.LinkStatus peerLinkStatus;
    private long peerLinkStatusAtMillis;
    private boolean guestDoctorLogged;
    /** Per-kind next-allowed stamp for the campaign feed notices (see {@link #postFeed}). */
    private final java.util.Map<String, Long> feedNextAtMillis = new java.util.HashMap<>();

    // ---- Phase 20.2 reconnect grace / 20.3 port mapper ------------------------------------------
    private final CoopReconnectCoordinator reconnect;
    private final coop.ui.CoopDialogController reconnectDialogs =
            new coop.ui.CoopDialogController("reconnect", this::nowMillis);

    // ---- Phase 21 lobby --------------------------------------------------------------------------
    /** How often the host re-sends {@code LOBBY_STATUS} even when nothing changed. */
    private static final long LOBBY_STATUS_INTERVAL_MILLIS = 1_000L;
    /**
     * Consecutive frames with no campaign UI before the lobby gate gives up and releases itself.
     * Sixty frames is about a second at the engine's frame rate, which is long enough that a load
     * frame or a between-screens null read cannot trip it.
     */
    private static final int LOBBY_NO_UI_RELEASE_FRAMES = 60;
    /** Host-authoritative roster; on the guest, the mirror of the host's. */
    private final coop.session.CoopLobbyRoster lobbyRoster = new coop.session.CoopLobbyRoster();
    /** Inbound messages the pre-drain pulled off the transport but left for {@link #drainInbound()}. */
    private final java.util.ArrayDeque<DeferredInbound> deferredInbound = new java.util.ArrayDeque<>();
    /**
     * "No connection generation". Deliberately not 0: the transport's counter starts there, and a
     * sentinel a real value can equal is a comparison that silently passes.
     */
    private static final long NO_CONNECTION_GENERATION = Long.MIN_VALUE;
    /** net-fix-2 counters: pre-drop messages the grace window let through, and ones it discarded. */
    private long preDropMessagesApplied;
    private long preDropMessagesDiscarded;
    /**
     * net-fix-7: every snapshot type this pump has forced a producer to re-send after the transport's
     * queue cap discarded one. Bounded by the number of message types, and read by tests — the forced
     * resend itself is a hash reset inside a producer and has no other observable edge.
     */
    private final java.util.EnumSet<CoopMessages.Type> overflowSnapshotResendsForced =
            java.util.EnumSet.noneOf(CoopMessages.Type.class);
    private final coop.ui.CoopDialogController lobbyDialogs =
            new coop.ui.CoopDialogController("lobby", this::nowMillis);
    private final coop.ui.CoopDialogController connectingDialogs =
            new coop.ui.CoopDialogController("connecting", this::nowMillis);
    private coop.ui.CoopLobbyDialog lobbyDialog;
    private coop.ui.CoopConnectingDialog connectingDialog;
    /** Wall clock of the frame this client's lobby opened; the "released after n s" log reads it. */
    private long lobbyOpenedAtMillis;
    private boolean lobbyOpenLogged;
    /** True when the release that is running came from the host's confirm-guarded override. */
    private boolean lobbyStartAnyway;
    /** Host: coalescing key for {@code LOBBY_STATUS}; a frame that changes nothing sends nothing. */
    private String lastLobbyStatusKey;
    private long nextLobbyStatusAtMillis;
    /** Host: the phase the guest last reported over {@code READY_STATE}. */
    private coop.session.CoopJoinPhase guestReportedPhase;
    /** Host: the ready value that came with it, and whether it still has to be applied. */
    private boolean guestReportedReady;
    private boolean guestReadyStatePending;
    /** Guest: the local Ready toggle. Revocable at any time before release. */
    private boolean guestReadyRequested;
    /** Guest: change detection for {@code READY_STATE}; cleared on every disconnect edge. */
    private coop.session.CoopJoinPhase lastSentJoinPhase;
    private Boolean lastSentReady;
    /** Guest: when the current connect attempt started, for the elapsed counter and the timeout. */
    private long guestConnectStartedAtMillis;
    /** Guest: the player pressed Cancel on the connecting dialog; no more join UI this connection. */
    private boolean guestJoinCancelled;
    /**
     * Seam for the Phase 21 desync dialogs: when one of them owns the failure — seed lock, mod
     * mismatch, resume reject — the connecting dialog steps aside instead of showing a second,
     * weaker version of the same message. Always the pump's own live predicate
     * ({@link #desyncOwnsReason}); the field exists so the check reads as one seam.
     */
    private java.util.function.Predicate<String> desyncOwnsFailure = this::desyncOwnsReason;

    // ---- Phase 21 desync dialogs ------------------------------------------------------------------
    /**
     * The one that outranks the lobby and the connecting screen and yields only to reconnect
     * (see {@link coop.ui.CoopDialogArbiter}).
     */
    private final coop.ui.CoopDialogController desyncDialogs =
            new coop.ui.CoopDialogController("desync", this::nowMillis);
    /**
     * The classified reason the dialog on screen is about, or null when none is up. Also what the HUD
     * reads to say the {@code COOP-*} code out loud, and what {@link #desyncOwnsReason} matches the
     * connecting dialog's raw failure text against.
     */
    private coop.ui.CoopDesyncReason desyncReason;

    // ---- Phase 21 session stats -------------------------------------------------------------------
    /** Host broadcast cadence; the same 30 s the guest snapshot uses, for the same reason. */
    private static final long STATS_BROADCAST_INTERVAL_MILLIS = 30_000L;
    /** How often the host samples positions, systems and the day count. Once a second is free. */
    private static final long STATS_SAMPLE_INTERVAL_MILLIS = 1_000L;
    private static final long STATS_NET_WORTH_INTERVAL_MILLIS = 30_000L;
    /** The live tally; loaded from the save on first use. See {@link #sessionStats()}. */
    private coop.stats.CoopSessionStats sessionStats;
    private long nextStatsSampleAtMillis;
    private long nextStatsBroadcastAtMillis;
    private long nextStatsNetWorthAtMillis;
    /** Day count carried out of the save, so a reload does not freeze the monotonic gauge. */
    private float statsDayOffset;
    /** {@code getTimestamp()} at the first sample after a load; 0 means "not taken yet". */
    private long statsBaseTimestamp;
    /** Distance anchors: {x, y, valid}. Primitive arrays because there are exactly two of them. */
    private final float[] hostTrack = new float[3];
    private final float[] guestTrack = new float[3];
    /** Last containing-location id per player, so a jump resets the anchor instead of counting. */
    private final java.util.Map<String, String> lastLocationIds = new java.util.HashMap<>();
    /** Campaign day the colonies-held gauge was last taken on; negative means never. */
    private float lastColonyCountDay = -1f;
    /** The peer's id, remembered across the disconnect that clears it; drives the "away" column. */
    private String lastRemotePlayerId = "";
    /** Fleet-member id to {@code name|hullClass}, captured when a coop battle starts. */
    private final java.util.Map<String, Hull> preBattleRoster = new java.util.LinkedHashMap<>();
    /** True between a battle concluding and the first frame with no screen open to diff on. */
    private boolean shipLossDiffPending;
    private boolean lobbyNoUiReleaseLogged;
    /** Consecutive lobby frames with no campaign UI to show the gate on; see {@link #tickLobby()}. */
    private int lobbyNoUiFrames;
    /** Wall clock of the last SAVE_CHECKPOINT sent or received; exemption (b) of the death rule. */
    private long lastSaveCheckpointAtMillis;
    /** Log-once flag for traffic dropped while an unproven peer is on the line during a grace window. */
    private boolean graceTrafficDropWarned;
    /** Host: the router mapping negotiated for this session's port, or null when not hosting. */
    private CoopPortMapper portMapper;
    private boolean portMapperReportLogged;
    private java.util.function.IntFunction<CoopPortMapper> portMapperFactory;
    /** Guest: what it dialled, so the connection-doctor block can name it. */
    private String guestConnectHost = "";
    private int guestConnectPort;

    // ---- Phase 20.4 lobby password ---------------------------------------------------------------
    /**
     * Reject text for a failed password. Deliberately says nothing about which half was wrong and
     * never quotes the attempt: the log line and the wire both carry the same three words.
     */
    static final String LOBBY_REJECT_PASSWORD = "password rejected";
    /**
     * What the guest's HUD says after that reject (F4). The wire reason plus the only thing the
     * player can actually do about it, because the password is fixed at launch.
     */
    static final String LOBBY_REJECT_PASSWORD_HELP =
            LOBBY_REJECT_PASSWORD + ", relaunch with the host's password";
    /**
     * The configured lobby password, "" for none. Read once per pump: it is a launch-time setting,
     * and re-reading it mid-session would let a property change split the two ends of a live lobby.
     */
    private String lobbyPassword = readLobbyPassword();
    /** Host: the nonce of the challenge currently outstanding, or null. No session slot is held. */
    private String pendingLobbyNonce;
    /** Guest: whether this connection was ever challenged, so a password with no gate is reported. */
    private boolean lobbyChallengeSeen;
    /** Guest: one warning per pump for "you set a password and the host does not want one". */
    private boolean lobbyPasswordUnusedWarned;
    /** Source of the challenge nonces; the same 64-bit width the UDP path challenge uses. */
    private final java.security.SecureRandom lobbyNonceSource = new java.security.SecureRandom();

    /** This save's stable player id; see {@link #adoptStableLocalPlayerId()}. */
    private Supplier<String> storedLocalPlayerIdSupplier = CoopSeedSync::currentLocalPlayerId;
    private Consumer<String> localPlayerIdStore = CoopSeedSync::storeLocalPlayerId;

    // ---- Phase 20.6 session intel ----------------------------------------------------------------
    /**
     * Feed behind the "Coop Session" intel page. Owned by the pump because everything the page shows
     * is something the pump already computes for {@code LINK_STATUS}, the connection doctor or the
     * feed banners; the page itself holds no state (see {@link coop.ui.CoopSessionIntel}).
     */
    private final coop.ui.CoopSessionIntelFeed intelFeed = new coop.ui.CoopSessionIntelFeed();
    /**
     * Phase 28 milestone 2: this campaign's host-authoritative policy. The lambda reads the role
     * live rather than capturing it, because a pump is built before the role is decided (a host
     * started from the sector-memory flags is {@code NONE} for its first frames).
     */
    private final coop.config.CoopOptionsPolicy optionsPolicy =
            new coop.config.CoopOptionsPolicy(this::isPolicyAuthority);
    /**
     * The policy version the peer has been told about, or -1 when it has been told nothing on this
     * session. Reset on both session edges and on a resume, which is what makes the establish and
     * resume broadcasts fall out of the same one comparison instead of needing their own call sites.
     */
    private int lastSentOptionsVersion = -1;
    /** The policy version this client last posted a feed line for; -1 before the first read. */
    private int lastAnnouncedOptionsVersion = -1;
    /**
     * Guest: the policy version this client has already acknowledged with {@code OPTIONS_APPLIED},
     * or -1 when it has acknowledged nothing on this session. Reset on the session edge so a
     * returning guest re-acknowledges rather than leaving the host pending forever.
     */
    private int lastSentOptionsAppliedVersion = -1;
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
    private final CoopBattleBridge battleBridge;
    private final CoopNpcThreatWatcher npcThreatWatcher;
    private final CoopBattleResultReconciler battleResultReconciler;
    private final CoopCampaignReplicator campaignReplicator;
    /** Phase 17: watches the local player fleet for the vanilla wipe respawn's object swap. */
    private final CoopRespawnNotifier respawnNotifier = new CoopRespawnNotifier();
    private String localInteractionEntityId;
    private String lastBlockedEntityName;
    /** Phase 30 bridge lever: a guest-side screen-level pause intent asserted by the agent bridge. */
    private boolean bridgePauseIntent;
    /** Phase 18: the local claim the host rejected, until its dialog is actually closed. */
    private final CoopRejectTracker rejectTracker = new CoopRejectTracker();
    /** Phase 20 M6: the guest's own unanswered-claim timer (the "waiting for the host" notice). */
    private final coop.interaction.CoopClaimWaitTracker claimWaitTracker =
            new coop.interaction.CoopClaimWaitTracker();
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
    /**
     * Burns the adopt-campaign consent at the moment it is acted on (net-fix-10). The gesture is a
     * system property, and a new pump is built on every game load, so without this one
     * {@code -Dcoop.adoptCampaignId=true} silently re-adopted the host's campaign id onto every
     * further save loaded from the main menu in the same process.
     */
    private final Runnable adoptCampaignIdConsumer;
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
     * Phase 20 live QA (F2/F3): true while the pause currently on this client's clock is one the pump
     * itself applied because no gameplay session was active. It is the difference between "coop is
     * holding the world" and "the player pressed pause", and only the first is coop's to release.
     */
    private boolean coopHoldPaused;
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
    /**
     * Local character name for {@link #localPlayerName(CoopConnectionRole)}. A field rather than a
     * constructor parameter so the existing eight constructor overloads stay as they are; tests
     * replace it through {@link #setCharacterNameSupplier(Supplier)}.
     */
    private Supplier<String> characterNameSupplier = CoopNetPump::characterNameFromSector;

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
        this(service, sessionState, clockMillis, manifestSupplier, ironModeSupplier,
                hostSeedSupplier, sectorFingerprintSupplier, sectorSeedStringSupplier, timeLock,
                storedCampaignIdSupplier, campaignIdStore, adoptCampaignIdSupplier,
                canonicalFingerprintSupplier, priorCoopSessionSupplier,
                () -> System.clearProperty(ADOPT_CAMPAIGN_ID_PROPERTY));
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
                       BooleanSupplier priorCoopSessionSupplier,
                       Runnable adoptCampaignIdConsumer) {
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
        this.adoptCampaignIdConsumer = Objects.requireNonNull(adoptCampaignIdConsumer, "adoptCampaignIdConsumer");
        this.canonicalFingerprintSupplier = Objects.requireNonNull(canonicalFingerprintSupplier, "canonicalFingerprintSupplier");
        this.priorCoopSessionSupplier = Objects.requireNonNull(priorCoopSessionSupplier, "priorCoopSessionSupplier");
        this.timeLock = Objects.requireNonNull(timeLock, "timeLock");
        this.timeLock.setPauseCoordinator(pauseCoordinator);
        this.timeLock.setFastForwardLock(fastForwardLock);
        this.npcFleetRegistry = new CoopFleetMirrorRegistry(CoopFleetMirror::new, clockMillis);
        this.campaignReplicator = new CoopCampaignReplicator(service, sessionState, clockMillis);
        this.battleBridge = new CoopBattleBridge(service, sessionState, clockMillis, pauseCoordinator);
        // Phase 20 M6: the pre-contact handoff band is derived against the measured link, so the
        // watcher reads p95 RTT from the same place the HUD does. Null (no PONG yet) maps to 0, which
        // the watcher treats as "unmeasured" and answers with Phase 14's flat loopback geometry.
        this.npcThreatWatcher = new CoopNpcThreatWatcher(service, sessionState,
                this::p95RttMillisOrZero);
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
        // Phase 21: the trade / mission / salvage / colony tallies. The replicator owns those
        // handlers and their de-duplication ledgers; the pump owns the counters they feed.
        this.campaignReplicator.setStatsSink(new StatsSink());
        this.npcFleetReplicator = new CoopNpcFleetReplicator(service, sessionState, clockMillis,
                streamClock, this::sendStateDatagram);
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
        // Red-team B4: the other half of the save exemption, which both roles send.
        CoopStallNotice.setActive(this::sendStallNotice);
        // Same static-seam deal as the profiler: the transport's send hook has no handle on the pump.
        this.wiretap = CoopWiretap.installFresh(clockMillis);
        // Phase 20.2. Built here (not lazily on the first drop) so the configured window is parsed and
        // logged while a bad property is still a startup problem rather than a mid-session surprise.
        this.reconnect = new CoopReconnectCoordinator(configuredReconnectGraceMillis(),
                new ReconnectListener());
        // Phase 20.6: the intel page is constructed by XStream on load and holds no state, so the
        // static handle is how it reaches this pump's feed. Newest pump wins, exactly like the save
        // checkpoint above — a game load replaces the previous session's feed.
        coop.ui.CoopSessionIntelFeed.install(this.intelFeed);
        // Phase 28: same static-handle argument. The options page is constructed by the intel screen
        // and has no way to be handed this pump's policy, so the newest pump publishes it.
        coop.config.CoopOptionsPolicy.install(this.optionsPolicy);
        long now = clockMillis.getAsLong();
        this.nextPingAtMillis = now + PING_INTERVAL_MILLIS;
        this.nextLinkStatusAtMillis = now + LINK_STATUS_INTERVAL_MILLIS;
        this.nextLinkEvalAtMillis = now + LINK_EVAL_INTERVAL_MILLIS;
        this.linkQuality.reset(now);
        this.nextTimeSnapshotAtMillis = now + CoopTimeLock.SNAPSHOT_INTERVAL_MILLIS;
        this.nextGuestSnapshotAtMillis = now;
        // A full interval out, not zero: the one send that belongs at the start of a session is the
        // forced one behind the lobby release (releaseLobbyNow), and letting the periodic timer also
        // fire on the first frame would put two identical payloads on the wire back to back.
        this.nextStatsBroadcastAtMillis = now + STATS_BROADCAST_INTERVAL_MILLIS;
        // net-fix-1: the outbound twin of dispatchInbound's grace whitelist, installed on the
        // transport itself. Last, so every field the two predicates read is assigned. Semantic
        // traffic written onto an unproven socket during a grace window is not delivered - the
        // receiver's own whitelist drops it - so it has to stay queued rather than be written.
        service.setOutboundWriteGate(
                type -> peerProvenForOutbound() || allowedDuringReconnectGrace(type));
    }

    /**
     * The configured grace window, defaulting when the property is unusable. Bad networking
     * properties already log once from {@code maybeStartFromSystemProperties}; a pump that refused to
     * construct over one would take the whole mod down instead.
     */
    private static long configuredReconnectGraceMillis() {
        try {
            return CoopNetStartupConfig.fromSystemProperties().reconnectGraceMillis();
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Unusable "
                    + CoopNetStartupConfig.RECONNECT_GRACE_PROPERTY + "; using the "
                    + CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS + " s default", ex);
            return CoopNetStartupConfig.DEFAULT_RECONNECT_GRACE_SECONDS * 1000L;
        }
    }

    /** Test-only read of the grace state machine. */
    CoopReconnectCoordinator reconnectCoordinatorForTest() {
        return reconnect;
    }

    /** Test read: pre-drop messages applied after a grace window opened; see {@link #survivesTheDropEdge}. */
    long preDropMessagesAppliedForTest() {
        return preDropMessagesApplied;
    }

    /** Test read: pre-drop messages the drop edge had already invalidated. */
    long preDropMessagesDiscardedForTest() {
        return preDropMessagesDiscarded;
    }

    /** Test read: snapshot types re-sent after a queue-cap drop; see {@link #resendSnapshotsDroppedByQueueOverflow}. */
    java.util.Set<CoopMessages.Type> overflowSnapshotResendsForcedForTest() {
        return java.util.Collections.unmodifiableSet(overflowSnapshotResendsForced);
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
     * Bridge-only guest pause lever (Phase 30). The guest has no authority over the clock, so the
     * only honest thing its {@code pause} verb can do is raise the same screen-level intent an open
     * blocking screen raises and let the host decide — which means the intent has to live somewhere
     * the pump's own per-frame recompute ORs in, not in the coordinator's latch that recompute
     * overwrites.
     *
     * @return true when the lever moved (nothing is sent for a repeat of the level it already held)
     */
    public boolean setBridgePauseIntent(boolean on) {
        if (bridgePauseIntent == on) {
            return false;
        }
        bridgePauseIntent = on;
        return true;
    }

    /** Bridge-only: the lever's current level, for {@code status} and for tests. */
    public boolean bridgePauseIntent() {
        return bridgePauseIntent;
    }

    /**
     * Bridge-only: the very predicate the guest's screen-pause intent is driven by, so the bridge's
     * {@code status} verb reports the screen state that actually holds the shared clock rather than a
     * second opinion about what counts as a blocking screen.
     */
    public static boolean blockingScreenOpenForBridge(SectorAPI sector) {
        return sector != null && isVanillaBlockingScreenOpen(sector);
    }

    // ---- Phase 21: in-campaign lobby --------------------------------------------------------------

    /**
     * The predicate the campaign clock is gated on: a session that exists <em>and</em> that both
     * players have agreed to start.
     *
     * <p>Used in exactly two places - the early return of {@link #maybeHoldPausedUntilSessionReady()}
     * and the top of {@link #syncSharedPause()} - and nowhere else. Every other
     * {@link #isGameplaySessionActive()} caller keeps the old predicate, because the state streams
     * are what carry the guest from "seed locked" to "snapshot applied", and gating them behind the
     * lobby would make the ready gate unreachable.
     */
    private boolean isSessionPlayable() {
        return isGameplaySessionActive() && sessionState.lobbyReleased();
    }

    /**
     * The outbound twin of {@link #dispatchInbound}'s grace whitelist (Phase 20.2).
     *
     * <p>During a reconnect grace window the session record is deliberately still live, so every
     * "is there a session?" gate stays true — while the socket in the peer slot may belong to
     * anybody, the transport having freed the slot on the drop. Inbound has always been filtered
     * down to the messages that can prove identity; outbound was not, so a stranger who dialled the
     * freed slot was handed the campaign's options policy and a {@code TIME_SNAPSHOT} stamped with
     * the held session id before saying a word. Nothing but the challenge/resume exchange leaves
     * this client until the window closes on an accepted resume.
     */
    private boolean peerProvenForOutbound() {
        return !reconnect.active();
    }

    /**
     * The pump's own wall clock, as an instance method so the dialog controllers can be handed it
     * from their field initialisers (a lambda over the final field cannot be, it is not assigned yet).
     */
    private long nowMillis() {
        return clockMillis.getAsLong();
    }

    /** Test/bridge read: the lobby roster this client is rendering. */
    coop.session.CoopLobbyRoster lobbyRosterForTest() {
        return lobbyRoster;
    }

    /** Test read: whether the lobby dialog is being asked for. */
    boolean lobbyDialogRequestedForTest() {
        return lobbyDialogs.isRequested();
    }

    /** Test read: whether the connecting dialog is being asked for. */
    coop.ui.CoopConnectingDialog.View connectingViewForTest() {
        return connectingView();
    }

    /** Test read: the guest's coordinated-save checkpoint state. */
    coop.save.CoopSaveCheckpoint saveCheckpointForTest() {
        return saveCheckpoint;
    }

    boolean connectingDialogRequestedForTest() {
        return connectingDialogs.isRequested();
    }

    /** Test read: whether a desync dialog is being asked for. */
    boolean desyncDialogRequestedForTest() {
        return desyncDialogs.isRequested();
    }

    /** Test read: the classified reason behind the desync dialog, or null. */
    coop.ui.CoopDesyncReason desyncReasonForTest() {
        return desyncReason;
    }

    /** Test seam: the player pressed Close on the desync dialog. */
    void desyncCloseForTest() {
        onDesyncDialogClosed();
    }

    /** Test seam: the player pressed the reconnect dialog's give-up option. */
    void endReconnectWaitForTest() {
        reconnect.end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER);
    }

    /** Test seam: the player pressed the reconnect dialog's "wait longer" option. */
    void extendReconnectWaitForTest() {
        extendReconnectWait();
    }

    // ---- Phase 21 desync dialogs -------------------------------------------------------------------

    /**
     * The single funnel every loud failure goes through: classify, write the {@code [COOP-DOCTOR]}
     * marker, post the feed line, and ask for the dialog.
     *
     * <p><b>Why one funnel and not a call at each of the eight reject sites.</b> The sites differ in
     * exactly two things — the raw text and which {@link coop.ui.CoopDesyncReason.Source} it came
     * from — and everything after that must be identical on both machines or the two marker lines
     * cannot be matched up in a support thread. Eight copies of the same six calls is eight chances
     * for one of them to drift.
     *
     * <p><b>{@code sessionIdAtReject} is a parameter, not a read.</b> Every reject site calls
     * {@code sessionState.rejectHandshake} or ends the reconnect window, and both of those clear the
     * canonical session — including the id. The correlation id has to be captured <em>before</em>
     * that, so the caller passes it in. For a lobby-stage reject there is no session id yet (the
     * handshake is what mints it), and the callers pass the provisional lobby id instead; it is
     * host-minted and therefore also the same string on both machines, which is the only property
     * the correlation actually needs.
     *
     * @param rawReason        the producer's exact text
     * @param source           where it came from; decides the fallback kind and the phrasing
     * @param sessionIdAtReject the session id captured before the state cleared it
     */
    /**
     * The correlation id the marker line carries, read <em>before</em> the reject clears it.
     *
     * <p>{@code rejectHandshake} and every reconnect terminal end run
     * {@code clearCanonicalSession()}, which drops the session id. Reading it afterwards yields
     * {@code <none>} on both machines, which is exactly the field a support thread needs to match
     * two pastes. Hence the read-first rule, and hence this being a separate method the reject sites
     * call on their first line.
     *
     * <p>Lobby-stage rejects (a mod mismatch, a seed mismatch) happen before the handshake mints a
     * session id at all. Those fall back to the provisional lobby id, which is host-minted and echoed
     * to the guest in {@code LOBBY_ACCEPT} — so it is the same string on both machines, which is the
     * only property the correlation actually needs.
     */
    private String desyncCorrelationId() {
        String sessionId = sessionState.sessionId();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return sessionId;
        }
        return sessionState.provisionalLobbyId();
    }

    /**
     * What a reject site calls once it has finished doing its own work: stop a guest that would
     * otherwise loop on a deterministic reject, then classify, log the marker, post the feed line and
     * ask for the dialog.
     *
     * <p>Called <em>after</em> the site's wire reply and log line, not before. Everything in here is
     * total — {@link coop.ui.CoopDoctorMarker#log} and {@link coop.ui.CoopFeed#post} both swallow —
     * but the reject the peer is waiting for still belongs on the socket first.
     */
    private void noteDesync(String rawReason, coop.ui.CoopDesyncReason.Source source,
                            String correlationId) {
        if (source != coop.ui.CoopDesyncReason.Source.SESSION_RESUME) {
            // Seed and mod rejects only: a resume reject leaves the guest's connect loop alone on
            // purpose, because a fresh lobby round is the documented way back in after a grace expiry.
            goTerminalOnGuestReject(rawReason);
        }
        raiseDesyncDialog(rawReason, source, correlationId);
    }

    /**
     * Marker and feed line without the dialog, for the host's side of a resume reject.
     *
     * <p>The host turning a resume request away is not a session ending for the host: its grace window
     * is still running and the real guest may still come back, and the reconnect dialog explaining
     * that is already the right thing on its screen. What the host owes the support thread is the
     * matching {@code [COOP-DOCTOR]} line, so the two logs can be lined up on the same id — and that
     * is all this writes.
     */
    private void noteDesyncMarkerOnly(String rawReason, coop.ui.CoopDesyncReason.Source source,
                                      String correlationId) {
        coop.ui.CoopDesyncReason reason;
        try {
            reason = withWindowSeconds(coop.ui.CoopDesyncReason.classify(rawReason, source));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not classify a desync reason: " + rawReason, ex);
            return;
        }
        coop.ui.CoopDoctorMarker.log(reason, correlationId, service.role(),
                sessionState.localName(), sessionState.remoteName());
    }

    /**
     * Stamps the window length the dialog prints. The coordinator's reason strings are bare phrases
     * and this is the only place that knows the number.
     *
     * <p>For a window that ran out, the number is how long it was actually open: "wait longer" moves
     * the deadline without changing the configured length, so a world held for three minutes used to
     * be reported to the player as the sixty seconds it was launched with. Everything else still
     * reports the configured length, which is all there is to say about a window that has not run.
     */
    private coop.ui.CoopDesyncReason withWindowSeconds(coop.ui.CoopDesyncReason reason) {
        long millis = reconnect.graceMillis();
        if (reason.sessionCause() == coop.ui.CoopDesyncReason.SessionCause.GRACE_EXPIRED
                && reconnect.expiredWindowMillis() >= 0L) {
            millis = reconnect.expiredWindowMillis();
        }
        return reason.withGraceSeconds((int) (millis / 1000L));
    }

    private void raiseDesyncDialog(String rawReason, coop.ui.CoopDesyncReason.Source source,
                                   String sessionIdAtReject) {
        coop.ui.CoopDesyncReason reason;
        try {
            reason = coop.ui.CoopDesyncReason.classify(rawReason, source);
            if (source == coop.ui.CoopDesyncReason.Source.SESSION_RESUME) {
                reason = withWindowSeconds(reason);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not classify a desync reason: " + rawReason, ex);
            return;
        }
        desyncReason = reason;
        // The marker first, before anything that touches the UI: it is the artifact a support thread
        // is built on, and it has to be in the log even on a frame where every dialog call throws.
        coop.ui.CoopDoctorMarker.log(reason, sessionIdAtReject, service.role(),
                sessionState.localName(), sessionState.remoteName());
        // postFeed also records the line on the intel page's event log (see postFeed), so the page
        // still names the code after the banner has scrolled away and the dialog has been dismissed.
        postFeed(FEED_DESYNC, clockMillis.getAsLong(), coop.ui.CoopDesyncDialog.feedLine(reason),
                FEED_WARN_COLOR);
        requestDesyncDialog(reason);
    }

    /**
     * Guest half of the no-auto-retry rule. The three deterministic rejects cannot become acceptable
     * without a relaunch on one side or the other, so the retry loop is stopped and the reject is
     * marked terminal — otherwise the guest reconnects every 5 s, earns the identical reject, and
     * buries its own dialog under a stream of new ones. Exactly the brake the wrong-password path
     * already applies (see {@link #handleLobbyReject}).
     *
     * <p>The host is deliberately untouched: it rewinds to {@code HOST_WAITING} on the drop and keeps
     * its lobby open for a corrected guest, which is the pre-Phase-21 behaviour and the right one.
     */
    private void goTerminalOnGuestReject(String rawReason) {
        if (service.role() != CoopConnectionRole.GUEST || pendingTerminalStopReason != null) {
            return;
        }
        // Armed, not executed. See tickTerminalStop.
        pendingTerminalStopReason = rawReason == null ? "" : rawReason;
        terminalStopDeadlineMillis = clockMillis.getAsLong() + TERMINAL_STOP_LINGER_MILLIS;
    }

    /**
     * Closes the guest's connect loop once the reject that ended it has actually left the socket
     * (Phase 21 live smoke, wrong-seed guest).
     *
     * <p><b>The defect.</b> {@code stopReconnecting} closes every link, and a link closing drops
     * whatever is still queued on it. The rejects are queued by {@code sendTo} from inside an inbound
     * handler and only reach the wire on the frame's closing {@code flushOutbound}, so calling
     * {@code stopReconnecting} from the same handler threw the reject away before it was ever
     * written. The host then saw a bare disconnect: no {@code SEED_LOCK_REJECT}, no matching
     * {@code [COOP-DOCTOR]} line, and - until the eligibility rule below - a 60 s reconnect grace for
     * a session the guest had just refused.
     *
     * <p><b>The linger.</b> Called immediately after the frame's closing flush, so in the ordinary
     * case the wait is a few microseconds: the flush hands the bytes to the kernel, the queue reads
     * empty, and the loop stops on the same frame the reject was queued. The deadline is the backstop
     * for a socket that is not draining ({@link #TERMINAL_STOP_LINGER_MILLIS}), and a peer that
     * closed first needs no linger at all - there is nothing left to write to.
     *
     * <p>Deliberately not a "wait N frames" counter: frames are however long the game makes them, and
     * what this is waiting for is the queue, which the transport can already answer.
     */
    private void tickTerminalStop() {
        if (pendingTerminalStopReason == null) {
            return;
        }
        long now = clockMillis.getAsLong();
        // Idle, not depth-zero: a reject the kernel took only the first bytes of sits in the peer's
        // pendingWrite, where the depth cannot see it, and closing on that frame truncates the very
        // frame the linger exists to deliver.
        boolean queueEmpty = service.outboundIdle();
        boolean expired = now >= terminalStopDeadlineMillis;
        boolean linkAlreadyGone = !service.isConnected();
        if (!queueEmpty && !expired && !linkAlreadyGone) {
            return;
        }
        String reason = pendingTerminalStopReason;
        pendingTerminalStopReason = null;
        if (expired && !queueEmpty) {
            CoopLog.warn(CoopNetPump.class, "Coop could not flush the reject to the host within "
                    + (TERMINAL_STOP_LINGER_MILLIS / 1000L) + " s; closing the connect loop anyway."
                    + " The host will see a bare disconnect rather than the reason.");
        }
        try {
            service.stopReconnecting(reason);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not stop the connect loop after a reject", ex);
        }
    }

    private void requestDesyncDialog(coop.ui.CoopDesyncReason reason) {
        if (!coop.ui.CoopDialogArbiter.mayRequest(coop.ui.CoopDialogArbiter.DESYNC,
                reconnectDialogs.isRequested() ? coop.ui.CoopDialogArbiter.RECONNECT : null)) {
            // Only reconnect outranks this, and it is on screen exactly while the link might still
            // come back. The reason is kept, so the next tick re-offers the dialog once it is gone.
            return;
        }
        // The two lower-ranked screens are asking about a join that has already failed.
        lobbyDialogs.close();
        lobbyDialog = null;
        connectingDialogs.close();
        connectingDialog = null;
        desyncDialogs.request(coop.ui.CoopDesyncDialog.forReason(reason, service.role(),
                this::onDesyncDialogClosed, desyncRetryAction(reason)));
    }

    /**
     * What "Try again" does. Always null today, and the reason is worth writing down rather than
     * rediscovering.
     *
     * <p>{@link coop.ui.CoopDesyncReason#retryable()} is false for every seed and mod reason by
     * construction, so the only candidate is a transient session cause —
     * {@link coop.ui.CoopDesyncReason.SessionCause#HOST_IN_GRACE}, the host being mid-grace for
     * somebody else. And that is exactly the case where nothing needs re-arming: this path never
     * calls {@link #goTerminalOnGuestReject}, so the guest's TCP connect loop was never stopped, it
     * is still dialling every few seconds, and {@link #tickDesyncDialog()} drops this dialog by
     * itself the moment a fresh handshake validates. A button labelled "Try again" that starts
     * nothing is worse than no button: it invites the player to press it and conclude the mod is
     * broken when nothing visibly happens.
     *
     * <p>The one re-arm the transport does offer, {@code CoopNetService.connect}, is not usable here:
     * it runs a full {@code shutdownLocked} first, which would tear down a link that may already be
     * back up by the time the player reads the dialog.
     */
    private Runnable desyncRetryAction(coop.ui.CoopDesyncReason reason) {
        return null;
    }

    /** Close: log it and drop the dialog. Nothing else — the session ended when the reject landed. */
    private void onDesyncDialogClosed() {
        if (desyncReason != null) {
            CoopLog.info(CoopNetPump.class, "Coop desync dialog closed (" + desyncReason.code() + ")");
        }
        desyncReason = null;
        desyncDialogs.close();
    }

    /**
     * The HUD line for {@link CoopLobbyState#REJECTED}.
     *
     * <p>Two different rejects land in that state and only one of them used to be readable.
     * {@code guestRejectLobby} stores {@link coop.session.CoopSessionState#rejectReason()}, which the
     * HUD has always printed. {@code rejectHandshake} — every mod mismatch, every seed and campaign
     * mismatch — stores its text in a different field, so the HUD fell back to a bare "connection
     * rejected" for precisely the failures that need the most explaining.
     *
     * <p>So: the lobby reason when there is one, otherwise the desync code and its three-word cause,
     * which is what the dialog and the feed banner beside it are both already saying. The player ends
     * up with "rejected: COOP-SEED, seed mismatch" on the HUD after the dialog has been dismissed,
     * which is enough to find the {@code [COOP-DOCTOR]} line in the log.
     */
    private String rejectedHudStatus() {
        String lobbyReason = sessionState.rejectReason();
        if (lobbyReason != null && !lobbyReason.trim().isEmpty()) {
            return CoopHudState.rejectedStatus(lobbyReason);
        }
        coop.ui.CoopDesyncReason reason = desyncReason;
        if (reason == null) {
            String raw = sessionState.handshakeRejectReason();
            if (raw == null || raw.trim().isEmpty()) {
                return CoopHudState.rejectedStatus(null);
            }
            // No classified reason yet (the HUD can render on the same frame the reject lands, before
            // the funnel has run): classify defensively rather than print the raw diff, which is a
            // multi-line developer artifact and would run off the HUD line.
            reason = coop.ui.CoopDesyncReason.classify(raw, coop.ui.CoopDesyncReason.Source.OTHER);
        }
        return CoopHudState.rejectedStatus(
                reason.code() + ", " + coop.ui.CoopDesyncDialog.shortCause(reason));
    }

    /** True when a desync dialog already owns this exact failure text; see {@link #desyncOwnsFailure}. */
    private boolean desyncOwnsReason(String rawReason) {
        coop.ui.CoopDesyncReason current = desyncReason;
        if (current == null || rawReason == null) {
            return false;
        }
        return current.rawReason().equals(rawReason.trim());
    }

    /**
     * Frame tick, run before {@link #tickLobby()} so the lower-ranked controllers see this one's
     * request on the same frame. Outside {@code tickLobby} on purpose: that method returns early for
     * a released lobby and for {@code role == NONE}, and both are states a desync dialog has to
     * survive — a resume reject arrives precisely when the session is being torn down.
     */
    private void tickDesyncDialog() {
        if (desyncReason != null && sessionState.handshakeValidated()) {
            // A later handshake succeeded, so whatever this dialog is about has been superseded. Only
            // reachable on the host, which stays in its lobby and can admit a corrected guest.
            onDesyncDialogClosed();
            return;
        }
        if (desyncReason != null && !desyncDialogs.isRequested()) {
            // Held back by the reconnect dialog on an earlier frame; re-offer now that it is gone.
            requestDesyncDialog(desyncReason);
        }
        desyncDialogs.tick();
    }

    // ---- Phase 21 session stats --------------------------------------------------------------------

    /**
     * The live tally, loaded from the save on first use.
     *
     * <p>Lazily rather than in the constructor: the pump is built during {@code onGameLoad}, and
     * reading {@code getPersistentData()} there would race the engine's own restore on some paths.
     * The first frame that asks is always after the sector is fully up.
     *
     * <p>Installing the intel page's source here rather than at construction is the same idea — the
     * page renders from whatever this returns, and a page pointed at a tally that was about to be
     * replaced by the saved one would show zeroes for a frame.
     */
    private coop.stats.CoopSessionStats sessionStats() {
        if (sessionStats == null) {
            coop.stats.CoopSessionStats loaded = coop.stats.CoopSessionStatsStore.readFromCurrentSector();
            sessionStats = loaded == null ? new coop.stats.CoopSessionStats() : loaded;
            // Carry the saved day count forward: noteDaysElapsed is a monotonic gauge, so a reload
            // that restarted the base timestamp would otherwise freeze the counter at its old value
            // for however long the session had already run.
            statsDayOffset = sessionStats.daysElapsed();
            statsBaseTimestamp = 0L;
            coop.stats.CoopSessionStatsStore.publish(sessionStats);
            coop.ui.CoopSessionStatsIntel.setSource(() -> sessionStats, this::awayPlayerIds);
        }
        return sessionStats;
    }

    /**
     * Whose column renders as "away": the peer, while it is disconnected or inside a reconnect grace
     * window. Read from a remembered id because {@code sessionState.remotePlayerId()} is cleared by
     * the very disconnect that makes the peer away, so reading it live would always answer "nobody".
     */
    private java.util.Set<String> awayPlayerIds() {
        String remote = lastRemotePlayerId;
        if (remote == null || remote.isEmpty()) {
            return java.util.Set.of();
        }
        boolean present = service.isConnected() && !reconnect.active();
        return present ? java.util.Set.of() : java.util.Set.of(remote);
    }

    /**
     * Campaign days since this tally started, on the one scale everything in {@link
     * coop.stats.CoopSessionStats} shares (the days-elapsed gauge and every ship-loss entry's day).
     *
     * <p>Built from a base {@code getTimestamp()} plus the offset carried out of the save rather than
     * from an absolute campaign day, because there is no absolute-day API that survives a cycle
     * rollover without hand-rolling the calendar arithmetic.
     */
    private float statsDay() {
        try {
            SectorAPI sector = sectorOrNull();
            if (sector == null || sector.getClock() == null) {
                return statsDayOffset;
            }
            if (statsBaseTimestamp == 0L) {
                statsBaseTimestamp = sector.getClock().getTimestamp();
                return statsDayOffset;
            }
            return statsDayOffset + Math.max(0f, sector.getClock().getElapsedDaysSince(statsBaseTimestamp));
        } catch (RuntimeException | LinkageError ex) {
            return statsDayOffset;
        }
    }

    /**
     * Frame entry for the tally. Both roles run it — the guest needs the intel page's source
     * installed and its own received copy stored — but only the host samples and broadcasts.
     */
    private void tickSessionStats(long now) {
        if (service.role() == CoopConnectionRole.NONE && sessionStats == null) {
            // Solo, or a campaign that has not started coop yet. The stats page hides itself while no
            // source is installed, and that is the whole solo-load answer -- creating the tally here
            // would put an empty "Coop Stats" entry in the intel screen of a campaign that has never
            // had a session. Once a role exists the tally stays for the life of the pump, so an ended
            // session keeps its counters and its page.
            return;
        }
        coop.stats.CoopSessionStats stats = sessionStats();
        String remote = sessionState.remotePlayerId();
        if (remote != null && !remote.isEmpty()) {
            lastRemotePlayerId = remote;
        }
        if (now >= nextStatsSampleAtMillis) {
            nextStatsSampleAtMillis = now + STATS_SAMPLE_INTERVAL_MILLIS;
            // Ahead of the playable gate, because the failure this covers is a save, not a session:
            // both intel entries are removed by beforeGameSave and re-added by afterGameSave, so a
            // throw in between leaves the player with no coop pages at all until the next load. Two
            // map lookups a second, and the registration itself only runs when one is missing.
            reRegisterMissingIntelPages();
            if (isSessionPlayable()) {
                if (service.role() == CoopConnectionRole.HOST) {
                    sampleHostStats(stats, now);
                } else {
                    // Phase 21 red-team item 10. The guest tallies nothing -- the host owns the
                    // numbers -- but its own hulls still have to be *reported*, and sendShipLost is
                    // only reachable from the settle. Behind the HOST gate it was unreachable, so a
                    // guest could lose a fleet and the losses page would never hear about it.
                    settleGuestShipLosses(stats);
                }
            }
        }
        if (isSessionPlayable()) {
            maybeSendSessionStats(now, false);
        }
    }

    /** Guest half of the ship-loss diff; the host does this inside {@link #sampleHostStats}. */
    private void settleGuestShipLosses(coop.stats.CoopSessionStats stats) {
        try {
            SectorAPI sector = sectorOrNull();
            if (sector == null) {
                return;
            }
            maybeSettleShipLosses(stats, sector, statsDay());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not settle the guest's hull losses", ex);
        }
    }

    /**
     * Re-adds either coop intel entry if it is not on the intel manager. A map lookup per entry per
     * second; the registration itself only runs when something is actually missing.
     */
    private void reRegisterMissingIntelPages() {
        try {
            SectorAPI sector = sectorOrNull();
            if (sector == null) {
                return;
            }
            com.fs.starfarer.api.campaign.comm.IntelManagerAPI manager = sector.getIntelManager();
            if (manager == null) {
                return;
            }
            if (isIntelMissing(manager, coop.ui.CoopSessionIntel.class)) {
                coop.ui.CoopSessionIntel.ensureRegistered(sector);
            }
            if (isIntelMissing(manager, coop.ui.CoopSessionStatsIntel.class)) {
                coop.ui.CoopSessionStatsIntel.ensureRegistered(sector);
            }
        } catch (RuntimeException | LinkageError ex) {
            // An intel manager that will not answer is not a reason to lose the frame.
        }
    }

    private static boolean isIntelMissing(com.fs.starfarer.api.campaign.comm.IntelManagerAPI manager,
                                          Class<?> type) {
        java.util.List<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin> found = manager.getIntel(type);
        return found == null || found.isEmpty();
    }

    /**
     * The once-per-second host sample: the gauges and the two per-frame-shaped counters (distance,
     * systems, time flown together) that would cost a frame's work to measure at 60 Hz and cost
     * nothing at 1 Hz. Every engine call is wrapped; a sample that fails is skipped, not retried.
     */
    private void sampleHostStats(coop.stats.CoopSessionStats stats, long now) {
        try {
            // The roster. notePlayer is add-or-rename, so calling it every second is idempotent by
            // construction, and doing it here rather than at session start is what makes the columns
            // survive a reload with no separate restore step.
            String hostId = sessionState.localPlayerId();
            if (hostId != null && !hostId.isEmpty()) {
                stats.notePlayer(hostId, sessionState.localName());
            }
            String guestId = sessionState.remotePlayerId();
            if (guestId != null && !guestId.isEmpty()) {
                stats.notePlayer(guestId, sessionState.remoteName());
            }

            stats.noteDaysElapsed(statsDay());

            SectorAPI sector = sectorOrNull();
            if (sector == null) {
                return;
            }
            com.fs.starfarer.api.campaign.CampaignFleetAPI hostFleet = sector.getPlayerFleet();
            com.fs.starfarer.api.campaign.CampaignFleetAPI guestMirror =
                    coop.fleet.CoopGuestMirrorHandle.current();

            String hostLocation = sampleFleet(stats, hostId, hostFleet, hostTrack);
            String guestLocation = sampleFleet(stats, guestId, guestMirror, guestTrack);

            // "Together" = the same containing location, for v1. The spec allows "or within mutual
            // sensor range"; same-location is the half that needs no per-frame range maths and no
            // agreement about whose sensor strength counts, and in a game where a system is the unit
            // of shared activity it is also the half a player would recognise. Mutual-sensor-range is
            // recorded as the refinement, not built.
            //
            // The credit is a wall second of *flying*, so a stopped clock earns nothing: the sample
            // is on a wall-clock cadence, and without this gate twenty minutes parked in a market
            // screen (shared pause, both fleets frozen in the same system) added twenty minutes of
            // "flown together" to a stat whose own card reads as time in flight.
            if (hostLocation != null && hostLocation.equals(guestLocation) && !sector.isPaused()) {
                stats.noteTogether(STATS_SAMPLE_INTERVAL_MILLIS / 1000f);
            }

            if (now >= nextStatsNetWorthAtMillis) {
                nextStatsNetWorthAtMillis = now + STATS_NET_WORTH_INTERVAL_MILLIS;
                sampleHostNetWorth(stats, hostId, sector);
            }

            float day = statsDay();
            if (lastColonyCountDay < 0f || day - lastColonyCountDay >= 1f) {
                lastColonyCountDay = day;
                stats.noteColoniesHeld(countPlayerFactionMarkets(sector));
            }

            maybeSettleShipLosses(stats, sector, day);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop session stats sample failed", ex);
        }
    }

    /**
     * Distance and systems-visited for one fleet. Returns the fleet's containing-location id, or null
     * when there is no fleet to sample.
     *
     * <p>The distance anchor is reset (not differenced) whenever the containing location changes: a
     * jump into hyperspace moves a fleet thousands of units in one frame in a different coordinate
     * space, and counting that as travel would make the number meaningless.
     */
    private String sampleFleet(coop.stats.CoopSessionStats stats, String playerId,
                               com.fs.starfarer.api.campaign.CampaignFleetAPI fleet,
                               float[] anchor) {
        if (fleet == null || playerId == null || playerId.isEmpty()) {
            return null;
        }
        com.fs.starfarer.api.campaign.LocationAPI location = fleet.getContainingLocation();
        String locationId = location == null ? null : location.getId();
        if (locationId != null && !locationId.isEmpty()) {
            stats.noteSystemVisited(playerId, locationId);
        }
        org.lwjgl.util.vector.Vector2f position = fleet.getLocation();
        if (position == null) {
            return locationId;
        }
        boolean sameLocation = locationId != null && locationId.equals(lastLocationIds.get(playerId));
        if (sameLocation && anchor[2] != 0f) {
            float dx = position.x - anchor[0];
            float dy = position.y - anchor[1];
            stats.noteDistance(playerId, (float) Math.sqrt(dx * dx + dy * dy));
        }
        anchor[0] = position.x;
        anchor[1] = position.y;
        anchor[2] = 1f;
        lastLocationIds.put(playerId, locationId == null ? "" : locationId);
        return locationId;
    }

    /**
     * Net worth, as liquid credits, for both players.
     *
     * <p>There is no net-worth API in 0.98a, and the two obvious extensions — summing
     * {@code FleetMemberAPI.getBaseValue()} and cargo base values — are only available for the host's
     * own fleet: the guest half arrives as {@code GUEST_SNAPSHOT}, which carries credits and hull ids
     * but no valuations. Measuring the two columns differently would be worse than measuring both
     * narrowly, so both are credits and the page's footer says so.
     */
    private void sampleHostNetWorth(coop.stats.CoopSessionStats stats, String hostId, SectorAPI sector) {
        if (hostId == null || hostId.isEmpty()) {
            return;
        }
        try {
            if (sector.getPlayerFleet() != null && sector.getPlayerFleet().getCargo() != null
                    && sector.getPlayerFleet().getCargo().getCredits() != null) {
                stats.noteNetWorth(hostId,
                        (long) sector.getPlayerFleet().getCargo().getCredits().get());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not read the host's credits for the stats page", ex);
        }
    }

    /** How many markets the shared player faction holds right now; the colonies-held gauge. */
    private int countPlayerFactionMarkets(SectorAPI sector) {
        try {
            if (sector.getEconomy() == null) {
                return 0;
            }
            int held = 0;
            for (com.fs.starfarer.api.campaign.econ.MarketAPI market : sector.getEconomy().getMarketsCopy()) {
                if (market != null && market.isPlayerOwned()) {
                    held++;
                }
            }
            return held;
        } catch (RuntimeException | LinkageError ex) {
            return (int) sessionStats().coloniesHeldTeam();
        }
    }

    /**
     * Host broadcast of the whole tally on the {@code GUEST_SNAPSHOT} cadence, plus one immediately
     * after the lobby releases so the page is populated from the first minute of play rather than
     * from the first 30-second boundary.
     *
     * @param force true for the post-release send, which ignores the interval
     */
    private void maybeSendSessionStats(long now, boolean force) {
        if (service.role() != CoopConnectionRole.HOST || !service.isConnected()
                || !peerProvenForOutbound()
                || !isGameplaySessionActive() || sessionState.sessionId() == null) {
            return;
        }
        if (!force && now < nextStatsBroadcastAtMillis) {
            return;
        }
        nextStatsBroadcastAtMillis = now + STATS_BROADCAST_INTERVAL_MILLIS;
        try {
            CoopMessages.Message message = CoopMessages.sessionStats(sessionState.sessionId(),
                    service.nextSeq(), now,
                    coop.stats.CoopSessionStatsCodec.encodePayload(sessionStats()));
            service.send(message);
            log("outbound", message);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not send SESSION_STATS", ex);
        }
    }

    /**
     * Guest: replace the local copy with the host's. Wholesale, not merged — the host is the only
     * tallier, so anything the guest holds is a strictly older copy of the same thing.
     */
    private void handleSessionStats(CoopMessages.Message message) {
        // net-fix-4: the session gate the default dispatch arm applies to every other campaign
        // message. SESSION_STATS and SHIP_LOST are routed around it by their own switch arms, so
        // they have to carry it themselves.
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        try {
            coop.stats.CoopSessionStats received =
                    coop.stats.CoopSessionStatsCodec.decodePayload(message.payloadJson());
            sessionStats = received;
            statsDayOffset = received.daysElapsed();
            statsBaseTimestamp = 0L;
            coop.stats.CoopSessionStatsStore.publish(received);
            coop.ui.CoopSessionStatsIntel.setSource(() -> sessionStats, this::awayPlayerIds);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply SESSION_STATS", ex);
        }
    }

    /**
     * Host: one hull the guest reported losing.
     *
     * <p>net-fix-4: gated three ways, because this arm of the dispatch switch bypasses the default
     * arm's {@code isGameplaySessionActive()} check and the host's listener is open to the Internet.
     * Any client that completes a TCP connect could otherwise send a well-formed SHIP_LOST and have
     * {@code CoopSessionStats.player(id)} mint a column for a name it chose, indefinitely.
     */
    private void handleShipLost(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        try {
            CoopMessages.ShipLost loss = CoopMessages.parseShipLost(message);
            if (!isKnownSessionPlayerId(loss.playerId())) {
                CoopLog.warn(CoopNetPump.class, "Coop ignoring SHIP_LOST for playerId="
                        + loss.playerId() + ": not a player in this session");
                return;
            }
            // Dedup guarantee: the guest sends exactly one SHIP_LOST per hull it detected missing,
            // from a roster diff it then clears (see settleShipLosses). There is no rebroadcast and
            // no echo path, so the host counts what arrives.
            sessionStats().noteShipLost(loss.playerId(), loss.hullName(), loss.hullClass(),
                    loss.systemName(), loss.day(), loss.cause());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to apply SHIP_LOST", ex);
        }
    }

    /** The two ids this session knows about; anything else is a stranger naming itself. */
    private boolean isKnownSessionPlayerId(String playerId) {
        if (playerId == null || playerId.trim().isEmpty()) {
            return false;
        }
        String id = playerId.trim();
        return id.equals(sessionState.localPlayerId()) || id.equals(sessionState.remotePlayerId());
    }

    // ---- Phase 21 ship-loss detection --------------------------------------------------------------

    /**
     * Roster snapshot taken when a coop battle starts, so the hulls that are gone when it settles can
     * be named.
     *
     * <p><b>Why a battle-scoped diff and not a continuous one.</b> A fleet loses members for half a
     * dozen reasons that are not losses — sold, stored, mothballed, scuttled, transferred — and a
     * continuous watcher would have to tell them apart. Between "a battle began" and "the battle
     * settled" there is only one reason a hull can vanish, and the bridge already tells both roles
     * when those two moments are ({@code CoopBattleBridge.BattleFleetListener}).
     */
    private void captureRosterBeforeBattle() {
        try {
            SectorAPI sector = sectorOrNull();
            if (sector == null || sector.getPlayerFleet() == null
                    || sector.getPlayerFleet().getFleetData() == null) {
                return;
            }
            if (shipLossDiffPending) {
                // A second battle beginning before the first one settled (a chained engagement, or a
                // recovery screen that outlived the arming frame). Settling first is what keeps the
                // first battle's losses; the old unconditional clear threw them away.
                shipLossDiffPending = false;
                settleShipLosses(sessionStats(), sector, statsDay());
            }
            preBattleRoster.clear();
            for (com.fs.starfarer.api.fleet.FleetMemberAPI member
                    : sector.getPlayerFleet().getFleetData().getMembersListCopy()) {
                if (member == null || member.getId() == null) {
                    continue;
                }
                preBattleRoster.put(member.getId(), describeHull(member));
            }
            shipLossDiffPending = false;
        } catch (RuntimeException | LinkageError ex) {
            preBattleRoster.clear();
        }
    }

    /** Arms the diff; the settle itself waits for a frame with no screen open (see below). */
    private void armShipLossDiff() {
        shipLossDiffPending = !preBattleRoster.isEmpty();
    }

    /**
     * Takes the diff, but only on a frame where no blocking screen is open.
     *
     * <p>Post-battle salvage <em>recovers</em> hulls, and the recovery screen is the player deciding
     * which ones. Diffing while it is up would report every recoverable hull as lost and then never
     * take it back. Waiting for the screen to close costs a second of latency on a cosmetic counter
     * and removes the whole class of false positives.
     */
    private void maybeSettleShipLosses(coop.stats.CoopSessionStats stats, SectorAPI sector, float day) {
        if (!shipLossDiffPending || isVanillaBlockingScreenOpen(sector)) {
            return;
        }
        shipLossDiffPending = false;
        settleShipLosses(stats, sector, day);
    }

    private void settleShipLosses(coop.stats.CoopSessionStats stats, SectorAPI sector, float day) {
        try {
            if (preBattleRoster.isEmpty() || sector.getPlayerFleet() == null
                    || sector.getPlayerFleet().getFleetData() == null) {
                return;
            }
            java.util.Set<String> survivors = new java.util.HashSet<>();
            for (com.fs.starfarer.api.fleet.FleetMemberAPI member
                    : sector.getPlayerFleet().getFleetData().getMembersListCopy()) {
                if (member != null && member.getId() != null) {
                    survivors.add(member.getId());
                }
            }
            String playerId = sessionState.localPlayerId();
            com.fs.starfarer.api.campaign.LocationAPI location =
                    sector.getPlayerFleet().getContainingLocation();
            String systemName = location == null ? "" : location.getName();
            for (java.util.Map.Entry<String, Hull> entry : preBattleRoster.entrySet()) {
                if (survivors.contains(entry.getKey())) {
                    continue;
                }
                String hullName = entry.getValue().name();
                String hullClass = entry.getValue().hullClass();
                if (service.role() == CoopConnectionRole.HOST) {
                    // Dedup guarantee: the roster map is cleared below, so each captured member can
                    // be reported at most once per battle.
                    stats.noteShipLost(playerId, hullName, hullClass, systemName, day, "battle");
                } else {
                    sendShipLost(playerId, hullName, hullClass, systemName, day);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not settle the post-battle hull losses", ex);
        } finally {
            preBattleRoster.clear();
        }
    }

    private void sendShipLost(String playerId, String hullName, String hullClass, String systemName,
                              float day) {
        if (!service.isConnected() || sessionState.sessionId() == null) {
            return;
        }
        try {
            CoopMessages.Message message = CoopMessages.shipLost(sessionState.sessionId(),
                    service.nextSeq(), clockMillis.getAsLong(), playerId, hullName, hullClass,
                    systemName, day, "battle");
            service.send(message);
            log("outbound", message);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not send SHIP_LOST", ex);
        }
    }

    /**
     * One captured hull, as two fields.
     *
     * <p>Two fields and not one {@code "name|class"} string: a player is perfectly entitled to name a
     * ship {@code ISS Bar|Baz}, and the old join-then-split reported that as the hull class "Baz".
     */
    private record Hull(String name, String hullClass) {
        private Hull {
            name = name == null ? "" : name;
            hullClass = hullClass == null ? "" : hullClass;
        }
    }

    private static Hull describeHull(com.fs.starfarer.api.fleet.FleetMemberAPI member) {
        String name = "";
        String hullClass = "";
        try {
            name = member.getShipName() == null ? "" : member.getShipName();
            if (member.getHullSpec() != null) {
                hullClass = member.getHullSpec().getHullName() == null
                        ? "" : member.getHullSpec().getHullName();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A member the engine will not describe still counts as a hull; it just goes unnamed.
        }
        return new Hull(name, hullClass);
    }

    /**
     * Host: one finished battle, from either engine.
     *
     * <p>Dedup guarantee: called only when {@code battleResultReconciler.apply} returned true, and
     * that method is the mod's battle-id ledger — a repeat of the same {@code battleId} (a resend, a
     * host applying its own result twice) returns false and never reaches here.
     */
    private void noteBattleForStats(CoopBattleResult result) {
        try {
            String outcome = result.outcome() == null
                    ? "" : result.outcome().toUpperCase(java.util.Locale.ROOT);
            boolean won = outcome.contains("WIN") || outcome.contains("VICTOR");
            sessionStats().noteBattle(result.engagingPlayerId(), won);
            sessionStats().noteFleetsDestroyed(result.destroyedFleetIds().size());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not tally a battle result", ex);
        }
    }

    /** The replicator's tally hooks; see {@link coop.campaign.CoopCampaignReplicator.StatsSink}. */
    private final class StatsSink implements coop.campaign.CoopCampaignReplicator.StatsSink {

        @Override
        public void onTrade(String playerId, String marketId, long netCredits) {
            hostTally(stats -> stats.noteTrade(playerId, marketId, netCredits));
        }

        @Override
        public void onMissionClaimed(String playerId) {
            hostTally(stats -> stats.noteMissionClaimed(playerId));
        }

        @Override
        public void onSalvageConsumed() {
            hostTally(coop.stats.CoopSessionStats::noteSalvage);
        }

        @Override
        public void onColonyFounded(String playerId) {
            hostTally(stats -> stats.noteColonyFounded(playerId));
        }
    }

    /** Runs a tally on the host only, wrapped: a cosmetic counter must never fail a frame. */
    private void hostTally(java.util.function.Consumer<coop.stats.CoopSessionStats> tally) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }
        try {
            tally.accept(sessionStats());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop session stats tally failed", ex);
        }
    }

    /** Test read: the live tally. */
    coop.stats.CoopSessionStats sessionStatsForTest() {
        return sessionStats();
    }

    // Test seams for the lobby's option callbacks. In play these are Runnables the dialog holds;
    // a unit test has no engine to press an option on, so it presses them here instead.

    void lobbyStartForTest() {
        onLobbyStartPressed();
    }

    void lobbyStartAnywayForTest() {
        onLobbyStartAnyway();
    }

    void lobbyCancelCountdownForTest() {
        onLobbyCancelCountdown();
    }

    void lobbyReadyToggleForTest(boolean ready) {
        onLobbyReadyToggled(ready);
    }

    void connectingCancelForTest() {
        onConnectingCancelled();
    }

    // Test seams for the battle bridge's two edges. In play these arrive from
    // CoopBattleBridge.BattleFleetListener; a unit test has no engine to fight a battle in.

    void battleBegunForTest() {
        onLocalBattleBegun(java.util.List.of());
    }

    void battleConcludedForTest() {
        onBattleConcluded(java.util.List.of(), true);
    }

    /**
     * Frame entry for the lobby. Runs after the inbound drain and before the pause hold, so a release
     * decided on this frame is applied by this frame's {@code syncHostSharedPause}.
     */
    private void tickLobby() {
        CoopConnectionRole role = service.role();
        long now = clockMillis.getAsLong();
        if (role == CoopConnectionRole.NONE) {
            closeLobbyUi();
            resetLobbyState();
            return;
        }
        if (sessionState.lobbyReleased()) {
            // Playing. Nothing to render, and the roster is dropped so a later handshake starts clean.
            if (lobbyOpenLogged || lobbyRoster.size() > 0) {
                closeLobbyUi();
                resetLobbyState();
            }
            return;
        }
        if (!lobbyOpenLogged) {
            lobbyOpenLogged = true;
            lobbyOpenedAtMillis = now;
            lobbyRoster.open(now);
            CoopLog.info(CoopNetPump.class, "Coop lobby opened as " + role);
        }

        // Safety valve, evaluated before anything else can hold the clock for a lobby that has no
        // surface: without a campaign UI there is nothing to show, and holding a world paused with no
        // way to release it is strictly worse than falling back to the pre-lobby behaviour. In a
        // loaded campaign the UI is always there, so this never fires in play.
        //
        // Phase 21 red-team: a *single* null-UI frame used to be enough, and a null read is exactly
        // what the engine hands back on a load frame, between screens, or on any frame the getter
        // throws. Releasing the gate on one of those would start the session behind the players'
        // backs. A whole second of them is a UI that is genuinely not coming.
        if (isGameplaySessionActive() && !campaignUiAvailable()) {
            lobbyNoUiFrames++;
            if (lobbyNoUiFrames >= LOBBY_NO_UI_RELEASE_FRAMES && sessionState.releaseLobby()
                    && !lobbyNoUiReleaseLogged) {
                lobbyNoUiReleaseLogged = true;
                CoopLog.warn(CoopNetPump.class, "Coop lobby released without opening: no campaign UI"
                        + " to show it on for " + lobbyNoUiFrames + " consecutive frames");
            }
            closeLobbyUi();
            return;
        }
        lobbyNoUiFrames = 0;

        if (role == CoopConnectionRole.HOST) {
            tickHostLobby(now);
        } else {
            tickGuestLobby(now);
        }

        // Precedence: reconnect > desync (future) > lobby > connecting. A controller never requests
        // while a higher-ranked one is on screen, so two coop dialogs can never fight for the slot.
        lobbyDialogs.tick();
        connectingDialogs.tick();
    }

    /** True when there is a campaign UI to put a dialog on. */
    private static boolean campaignUiAvailable() {
        try {
            SectorAPI sector = Global.getSector();
            return sector != null && sector.getCampaignUI() != null;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private void resetLobbyState() {
        lobbyRoster.clear();
        lobbyOpenLogged = false;
        lobbyStartAnyway = false;
        lastLobbyStatusKey = null;
        nextLobbyStatusAtMillis = 0L;
        lobbyNoUiFrames = 0;
        guestReportedPhase = null;
        guestReportedReady = false;
        guestReadyStatePending = false;
        guestReadyRequested = false;
        lastSentJoinPhase = null;
        lastSentReady = null;
        guestConnectStartedAtMillis = 0L;
        guestJoinCancelled = false;
    }

    private void closeLobbyUi() {
        lobbyDialogs.close();
        connectingDialogs.close();
        lobbyDialog = null;
        connectingDialog = null;
    }

    // ---- host side ---------------------------------------------------------------------------------

    private void tickHostLobby(long now) {
        String hostId = sessionState.localPlayerId();
        if (hostId == null) {
            return;
        }
        lobbyRoster.setHost(hostId, sessionState.localName(), now);

        String guestId = sessionState.remotePlayerId();
        if (guestId != null) {
            boolean fresh = lobbyRoster.row(guestId) == null;
            if (lobbyRoster.admit(guestId, sessionState.remoteName(), now) && fresh) {
                postFeed(FEED_LOBBY_JOINED, now,
                        "Co-op: " + remoteDisplayName() + " joined the lobby.", FEED_GOOD_COLOR);
            }
            applyDerivedGuestPhase(guestId, now);
            applyReportedGuestReady(guestId, now);
            if (reconnect.hostWaiting()) {
                // Phase 21 red-team blocker 1. The countdown used to run straight through a drop, so
                // a link that died two seconds into it released the host alone -- into a session with
                // no guest, with the LOBBY_STATUS(released) dropped on the floor by the dead socket
                // and no later one to replace it, and with the pause hold promoted to a host pause
                // intent nobody could explain. The drop is the countdown's answer: cancel it.
                if (lobbyRoster.cancelCountdown()) {
                    CoopLog.info(CoopNetPump.class, "Coop lobby countdown cancelled: "
                            + remoteDisplayName() + " dropped mid-countdown");
                    postFeed(FEED_LOBBY_CANCELLED, now, "Co-op: countdown cancelled - "
                            + remoteDisplayName() + " dropped.", FEED_WARN_COLOR);
                }
                lobbyStartAnyway = false;
                // The number on the row is the window's remaining, read from the coordinator every
                // frame: the roster has no window of its own and must not invent one.
                lobbyRoster.markReconnecting(guestId, now, reconnect.remainingMillis(now));
            } else {
                lobbyRoster.markReconnected(guestId, now);
            }
        } else {
            dropDepartedGuestRows(now);
        }

        // Never while a grace window is open: the guest cannot be told about a release it is not
        // connected to hear, and the whole point of the window is that it is coming back.
        if (!reconnect.active() && lobbyRoster.countdownElapsed(now)) {
            releaseLobbyNow(now, lobbyStartAnyway);
            return;
        }

        maybeSendLobbyStatus(now, false);
        requestLobbyDialog();
    }

    /**
     * The phase the host can work out on its own (link, handshake, seed lock), merged with whatever
     * the guest reported. A guest on an older build that never sends {@code READY_STATE} still shows
     * real progress up to {@link coop.session.CoopJoinPhase#SEED_LOCKED} this way.
     *
     * <p>Only ever moves forward: a derived floor must never demote a row that has already readied.
     */
    private void applyDerivedGuestPhase(String guestId, long now) {
        coop.session.CoopJoinPhase derived = coop.session.CoopJoinPhase.LINK_ESTABLISHED;
        if (sessionState.handshakeValidated()) {
            derived = coop.session.CoopJoinPhase.VERSIONS_MATCHED;
        }
        if (sessionState.seedLong() != null) {
            derived = coop.session.CoopJoinPhase.SEED_LOCKED;
        }
        coop.session.CoopJoinPhase target = coop.session.CoopJoinPhase.max(derived, guestReportedPhase);
        if (target == coop.session.CoopJoinPhase.READY) {
            // The roster refuses READY as a phase on purpose - readying is a player action carried by
            // the row's ready flag, not by the protocol. Clamped rather than dropped: a guest whose
            // SNAPSHOT_APPLIED and READY reports arrive in the same drain leaves only the READY one
            // behind, and dropping it stranded the row at SEED_LOCKED with the ready report then
            // refused for being too early (Phase 21 red-team, item 4).
            target = coop.session.CoopJoinPhase.SNAPSHOT_APPLIED;
        }
        coop.session.CoopLobbyRoster.Row row = lobbyRoster.row(guestId);
        if (row == null || row.phase().atLeast(target)) {
            return;
        }
        lobbyRoster.setPhase(guestId, target, now);
    }

    /**
     * Applies the ready value the guest last reported, once there is a row to apply it to.
     *
     * <p>Deferred rather than applied on arrival because the inbound drain runs before the roster is
     * built: a {@code READY_STATE} that lands on the same frame the guest is admitted would otherwise
     * be written into a row that does not exist yet and silently lost. Applied exactly once per
     * report, so a stale "not ready" cannot cancel a countdown the host armed afterwards.
     */
    private void applyReportedGuestReady(String guestId, long now) {
        if (!guestReadyStatePending) {
            return;
        }
        coop.session.CoopLobbyRoster.Row row = lobbyRoster.row(guestId);
        if (row == null) {
            return;
        }
        if (!row.phase().atLeast(coop.session.CoopJoinPhase.SNAPSHOT_APPLIED)) {
            // Nothing to be ready for yet. The flag stays pending: clearing it here consumed the only
            // Ready the guest ever sends whenever the phase floor had not caught up on that frame.
            return;
        }
        guestReadyStatePending = false;
        boolean changed = lobbyRoster.setReady(guestId, guestReportedReady, now);
        String name = remoteDisplayName();
        if (!guestReportedReady && lobbyRoster.cancelCountdown()) {
            lobbyStartAnyway = false;
            CoopLog.info(CoopNetPump.class, "Coop lobby countdown cancelled by " + name);
            postFeed(FEED_LOBBY_CANCELLED, now, "Co-op: countdown cancelled by " + name + ".",
                    FEED_WARN_COLOR);
        }
        if (!changed) {
            return;
        }
        if (guestReportedReady) {
            postFeed(FEED_LOBBY_READY, now, "Co-op: " + name + " is ready.", FEED_GOOD_COLOR);
        } else {
            postFeed(FEED_LOBBY_UNREADY, now, "Co-op: " + name + " is no longer ready.",
                    FEED_WARN_COLOR);
        }
    }

    /**
     * The guest is gone from the session record. A join that never reached
     * {@link coop.session.CoopJoinPhase#SNAPSHOT_APPLIED} leaves no partial state and no stuck row;
     * anything later has already had its chance at the reconnect grace by the time we get here.
     */
    private void dropDepartedGuestRows(long now) {
        for (coop.session.CoopLobbyRoster.Row row : lobbyRoster.rows()) {
            if (row.host()) {
                continue;
            }
            boolean partial = !row.phase().atLeast(coop.session.CoopJoinPhase.SNAPSHOT_APPLIED);
            String name = row.name();
            if (lobbyRoster.dropPartial(row.playerId()) || lobbyRoster.remove(row.playerId())) {
                lobbyRoster.cancelCountdown();
                postFeed(FEED_LOBBY_LEFT, now, "Co-op: " + name
                        + (partial ? " dropped during the handshake." : " left the lobby."),
                        FEED_WARN_COLOR);
            }
        }
    }

    /**
     * Clears out what a departed partner left in the lobby (Phase 21 live smoke, second finding).
     *
     * <p><b>The bug.</b> The countdown-cancel path keeps the lobby open with the dropped player's row
     * in its reconnecting state, which is right while the wait is running and wrong the moment it
     * stops. Nothing removed that row when the wait ended, and {@link #dropDepartedGuestRows} could
     * not: it only runs while the session record has no guest at all, and a relaunch rejoin fills
     * that slot on the same frame the wait ends. The host lobby then showed three rows - the host,
     * the ghost of the old connection still counting, and the real returning guest - so
     * {@code allReady()} was false and the host was offered "Start anyway" for a partner who was
     * ready.
     *
     * <p>Also drops the phase and ready the departed guest last reported. Those describe a player who
     * is gone; carried into the next round they show the newcomer further along than it is, and a
     * still-pending ready would apply to it the moment its row reached SNAPSHOT_APPLIED.
     *
     * @param keepPlayerId the one guest allowed to stay (the peer being admitted), or null to drop
     *                     every guest row; v1 has exactly one guest slot, so two guest rows is always
     *                     wrong and the survivor is whoever this side is actually talking to
     * @return true when anything was dropped
     */
    private boolean forgetDepartedGuestLobbyRows(String keepPlayerId, String why) {
        boolean dropped = false;
        for (coop.session.CoopLobbyRoster.Row row : lobbyRoster.rows()) {
            if (row.host() || (keepPlayerId != null && keepPlayerId.equals(row.playerId()))) {
                continue;
            }
            String name = row.name();
            String id = row.playerId();
            if (lobbyRoster.remove(id)) {
                dropped = true;
                CoopLog.info(CoopNetPump.class, "Coop lobby row dropped for " + name
                        + " (playerId=" + id + "): " + why);
            }
        }
        if (!dropped) {
            return false;
        }
        // The countdown belonged to a roster that no longer exists, and so did the override the host
        // may have armed against it.
        lobbyRoster.cancelCountdown();
        lobbyStartAnyway = false;
        guestReportedPhase = null;
        guestReportedReady = false;
        guestReadyStatePending = false;
        return true;
    }

    /** Host: the roster on the wire, in the fixed order the lobby renders it. */
    private java.util.List<CoopMessages.LobbyPlayer> lobbyWirePlayers(long now) {
        java.util.List<CoopMessages.LobbyPlayer> players = new java.util.ArrayList<>();
        for (coop.session.CoopLobbyRoster.Row row : lobbyRoster.rows()) {
            // Remaining grace, not elapsed-since-drop: the guest renders the same countdown the
            // host does, off the same coordinator, without either side trusting the other's clock.
            long reconnecting = row.reconnectingUntilMillis() == null
                    ? -1L
                    : Math.max(0L, row.reconnectingUntilMillis() - now);
            players.add(new CoopMessages.LobbyPlayer(row.playerId(), row.name(), row.phase().name(),
                    row.ready(), reconnecting, row.reason()));
        }
        return players;
    }

    /**
     * Host: broadcasts the roster on every change and at least once a second while the lobby is open.
     * The countdown's remaining milliseconds are deliberately left out of the coalescing key - they
     * change every frame, and including them would turn a three-second countdown into a per-frame
     * broadcast for no extra information.
     */
    private void maybeSendLobbyStatus(long now, boolean released) {
        if (!isGameplaySessionActive() || !service.isConnected() || !peerProvenForOutbound()
                || sessionState.remotePlayerId() == null || sessionState.sessionId() == null) {
            return;
        }
        java.util.List<CoopMessages.LobbyPlayer> players = lobbyWirePlayers(now);
        String resetReason = lobbyRoster.lastResetReason();
        String key = CoopMessages.encodeLobbyPlayers(players) + "|" + lobbyRoster.countdownActive()
                + "|" + released + "|" + resetReason;
        if (!released && key.equals(lastLobbyStatusKey) && now < nextLobbyStatusAtMillis) {
            return;
        }
        lastLobbyStatusKey = key;
        nextLobbyStatusAtMillis = now + LOBBY_STATUS_INTERVAL_MILLIS;
        CoopMessages.Message message = CoopMessages.lobbyStatus(
                sessionState.sessionId(), service.nextSeq(), now, players,
                lobbyRoster.countdownRemainingMillis(now), released, resetReason,
                lobbyRoster.elapsedMillis(now));
        service.send(message);
        log("outbound", message);
        lobbyRoster.clearResetReason();
    }

    // ---- Phase 28 milestone 2: host policy options -------------------------------------------------

    /** Test/page read: this campaign's policy, as this client sees it. */
    public coop.config.CoopOptionsPolicy optionsPolicy() {
        return optionsPolicy;
    }

    /**
     * Who owns this campaign's policy: everyone except a guest.
     *
     * <p>Not "is the host", deliberately. A client that has not started hosting yet is
     * {@link CoopConnectionRole#NONE} - which is precisely when a player sets the rules for the
     * session they are about to open - and its campaign's rules are nobody else's. A guest is the
     * one role whose policy comes off the wire, and it is the one role refused here.
     */
    private boolean isPolicyAuthority() {
        return service.role() != CoopConnectionRole.GUEST;
    }

    /**
     * One frame of the policy layer, host side only: seed the campaign if it has never been seeded,
     * advance the boundary of the one key that has a consumer, announce a local change, and send the
     * snapshot the guest is owed.
     *
     * <p>The guest half is not here - it is two lines in {@link #syncGuestSharedPauseIntent()} and
     * the {@code OPTIONS_SNAPSHOT} handler - because there is nothing for a guest to decide.
     */
    private void tickOptionsPolicy() {
        if (!isPolicyAuthority()) {
            // The guest half runs after syncSharedPause (see advanceFrame): that is where its
            // boundary is crossed, and acknowledging from here would report it a frame late.
            return;
        }
        optionsPolicy.ensureSeeded(coop.config.CoopOptionsStore.system());
        // The host's pending means "sent, not yet acknowledged", and the acknowledgement is the
        // guest's OPTIONS_APPLIED (see handleOptionsApplied). With nobody to acknowledge - no guest
        // connected, or the session not playable - there is no boundary to wait for and a pending
        // change would hang on the page forever, so it promotes on the spot.
        //
        // This used to be an advanceBoundary gated on !guestScreenPauseIntent(), which read "the
        // guest has nothing open". It is degenerate whenever the option is already off: the guest
        // then holds no screen pause even while it sits in a core tab, so turning the option back ON
        // promoted the host's applied value immediately while the guest - correctly - stayed
        // pending, and the two disagreed about the rule in force.
        if (!hasAcknowledgingGuest()) {
            optionsPolicy.acknowledgeAllApplied();
        }
        announceLocalPolicyChange();
        maybeSendOptionsSnapshot();
    }

    /** True when there is a guest that owes this host an {@code OPTIONS_APPLIED}. */
    private boolean hasAcknowledgingGuest() {
        return service.role() == CoopConnectionRole.HOST && service.isConnected()
                && isSessionPlayable();
    }

    /**
     * Guest: tell the host once per version that every boundary has been crossed here.
     *
     * <p>Sent only when nothing is pending locally, which is the whole content of the message: the
     * guest's own {@code advanceBoundary} calls (see {@link #syncGuestSharedPauseIntent()}) are what
     * clear the pending state, and this is the report that they have.
     */
    private void maybeSendOptionsApplied() {
        if (service.role() != CoopConnectionRole.GUEST || !service.isConnected()
                || !isGameplaySessionActive()) {
            // A guest that drops re-acknowledges on the way back in; the host's own no-guest rule
            // above has already promoted anything that was pending while it was gone.
            lastSentOptionsAppliedVersion = -1;
            return;
        }
        int version = optionsPolicy.version();
        if (version <= 0 || version == lastSentOptionsAppliedVersion
                || optionsPolicy.hasPendingChanges()) {
            return;
        }
        String sessionId = sessionState.sessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        CoopMessages.Message message = CoopMessages.optionsApplied(sessionId, service.nextSeq(),
                clockMillis.getAsLong(), version);
        service.send(message);
        log("outbound", message);
        lastSentOptionsAppliedVersion = version;
    }

    /**
     * Host: the guest has crossed its boundaries for {@code policyVersion}, so the host's pending
     * values may promote.
     *
     * <p>An acknowledgement of an older version is ignored rather than applied: the host has changed
     * something since, and that newer change is still owed its own boundary.
     */
    private void handleOptionsApplied(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        int version;
        try {
            version = CoopMessages.parseOptionsApplied(message).policyVersion();
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not read an OPTIONS_APPLIED", ex);
            return;
        }
        optionsPolicy.acknowledgeApplied(version);
    }

    /**
     * The host's own banner for a change it just made. Version-driven rather than called from the
     * options page, so a change made from anywhere - the page today, a console command later -
     * announces itself exactly once.
     */
    private void announceLocalPolicyChange() {
        int version = optionsPolicy.version();
        if (lastAnnouncedOptionsVersion < 0) {
            // First read of this campaign's policy: seeding is not a change and gets no banner.
            lastAnnouncedOptionsVersion = version;
            return;
        }
        if (version <= lastAnnouncedOptionsVersion) {
            return;
        }
        lastAnnouncedOptionsVersion = version;
        String key = optionsPolicy.lastChangedKey();
        if (coop.config.CoopOptionsPolicy.RESET_MARKER.equals(key)) {
            // A reset moves several keys at once and so names none of them. Without this the one
            // player action on the page that changes the most produced the only silence.
            postOptionsResetFeedLine(version);
            return;
        }
        if (key.isEmpty() || !coop.config.CoopOptionsPolicy.isPolicyKey(key)) {
            return;
        }
        postOptionsFeedLine(key, optionsPolicy.effective(key), true);
    }

    /**
     * Host: the whole policy, whenever the guest's copy could be behind ours.
     *
     * <p>One comparison covers all three cases the plan names - the send behind the lobby release,
     * the re-send on a resume, and the send after a change - because each of them either bumps the
     * version or resets {@link #lastSentOptionsVersion} to -1.
     */
    private void maybeSendOptionsSnapshot() {
        if (service.role() != CoopConnectionRole.HOST || !service.isConnected()
                || !peerProvenForOutbound() || !isSessionPlayable()) {
            return;
        }
        int version = optionsPolicy.version();
        if (version == lastSentOptionsVersion) {
            return;
        }
        String sessionId = sessionState.sessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        CoopMessages.Message message = CoopMessages.optionsSnapshot(sessionId, service.nextSeq(),
                clockMillis.getAsLong(), optionsPolicy.values(), version,
                optionsPolicy.lastChangedKey());
        service.send(message);
        log("outbound", message);
        lastSentOptionsVersion = version;
    }

    /**
     * Guest: adopt the host's policy wholesale.
     *
     * <p>Session-gated like every other campaign message: a snapshot that arrives before the
     * lobby/handshake/seed-lock pipeline has run on this connection would be a stranger setting the
     * rules of a campaign it has not joined.
     */
    private void handleOptionsSnapshot(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        CoopMessages.OptionsSnapshot snapshot;
        try {
            snapshot = CoopMessages.parseOptionsSnapshot(message);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not read an OPTIONS_SNAPSHOT", ex);
            return;
        }
        coop.config.CoopOptionsPolicy.SnapshotResult result = optionsPolicy.applySnapshot(
                snapshot.values(), snapshot.policyVersion(), snapshot.changedKey());
        if (result.stale()) {
            return;
        }
        String changed = snapshot.changedKey();
        if (coop.config.CoopOptionsPolicy.RESET_MARKER.equals(changed)) {
            // Gated on something having actually moved here, which is also what keeps the resume
            // re-send quiet: it carries the same marker, but by then this client already holds the
            // reset values and changedKeys() is empty.
            if (!result.changedKeys().isEmpty()) {
                postOptionsResetFeedLine(result.version());
            }
            return;
        }
        if (changed.isEmpty() || !result.changedKeys().contains(changed)) {
            // An establish/resume broadcast, or a re-send of something we already hold: the values
            // are now right either way, and there is no event to narrate.
            return;
        }
        postOptionsFeedLine(changed, optionsPolicy.effective(changed), false);
    }

    /**
     * One banner and one intel-page event for a policy change.
     *
     * <p>Throttled per key <em>and value</em>: a player flipping a toggle back and forth gets both
     * lines, while a peer that alternates a value at wire speed gets one line per direction per 30 s
     * instead of a scrolling wall. Feed lines are the one part of this that a remote peer can drive.
     */
    private void postOptionsFeedLine(String key, String value, boolean local) {
        postFeed(FEED_OPTIONS + ":" + key + "=" + value, clockMillis.getAsLong(),
                coop.ui.CoopOptionsView.changeLine(key, value, local), FEED_WARN_COLOR);
    }

    /**
     * The one line a whole-policy reset gets, identical on both sides. Keyed by version rather than
     * by key/value so two resets in a row are two lines instead of one throttled away.
     */
    private void postOptionsResetFeedLine(int version) {
        postFeed(FEED_OPTIONS + ":reset=" + version, clockMillis.getAsLong(),
                coop.ui.CoopOptionsView.RESET_LINE, FEED_WARN_COLOR);
    }

    /** Host: the countdown ran out, so the session starts. */
    private void releaseLobbyNow(long now, boolean startAnyway) {
        long seconds = Math.max(0L, now - lobbyOpenedAtMillis) / 1000L;
        lobbyRoster.cancelCountdown();
        sessionState.releaseLobby();
        CoopLog.info(CoopNetPump.class, "Coop lobby released (start anyway=" + startAnyway
                + ") after " + seconds + " s");
        postFeed(FEED_LOBBY_STARTED, now, startAnyway
                        ? "Co-op: session started without every player ready."
                        : "Co-op: session started.",
                FEED_GOOD_COLOR);
        // Told to the guest before the roster is dropped; the release flag is what closes its lobby.
        maybeSendLobbyStatus(now, true);
        // Phase 21: one stats broadcast right behind the release, so the page is populated from the
        // first minute rather than from the first 30-second boundary after it.
        maybeSendSessionStats(now, true);
        closeLobbyUi();
        resetLobbyState();
    }

    private void requestLobbyDialog() {
        if (!coop.ui.CoopDialogArbiter.mayRequest(coop.ui.CoopDialogArbiter.LOBBY,
                reconnectDialogs.isRequested() ? coop.ui.CoopDialogArbiter.RECONNECT : null,
                desyncDialogs.isRequested() ? coop.ui.CoopDialogArbiter.DESYNC : null)) {
            // Precedence lives in CoopDialogArbiter: reconnect and desync both outrank the lobby. The
            // host case is the one this exists for — a rejected guest leaves the host in its lobby, so
            // without this the lobby would immediately take the slot back off the desync dialog that
            // is the only thing telling the host why the guest never arrived.
            lobbyDialogs.close();
            lobbyDialog = null;
            return;
        }
        connectingDialogs.close();
        connectingDialog = null;
        if (lobbyDialog == null || !lobbyDialogs.isRequested()) {
            lobbyDialog = new coop.ui.CoopLobbyDialog(this::lobbyView, clockMillis::getAsLong,
                    this::onLobbyStartPressed, this::onLobbyCancelCountdown,
                    this::onLobbyStartAnyway, this::onLobbyReadyToggled);
            lobbyDialogs.request(lobbyDialog);
        }
    }

    // ---- lobby option callbacks ---------------------------------------------------------------------

    private void onLobbyStartPressed() {
        long now = clockMillis.getAsLong();
        if (!lobbyRoster.allReady() || !lobbyRoster.startCountdown(now)) {
            return;
        }
        lobbyStartAnyway = false;
        CoopLog.info(CoopNetPump.class, "Coop lobby countdown started by " + sessionState.localName());
        postFeed(FEED_LOBBY_COUNTDOWN, now, "Co-op: starting in "
                + (coop.session.CoopLobbyRoster.COUNTDOWN_MILLIS / 1000L) + "s.", FEED_GOOD_COLOR);
    }

    private void onLobbyStartAnyway() {
        long now = clockMillis.getAsLong();
        if (!lobbyRoster.startCountdown(now)) {
            return;
        }
        lobbyStartAnyway = true;
        CoopLog.info(CoopNetPump.class, "Coop lobby countdown started by " + sessionState.localName()
                + " (start anyway)");
        postFeed(FEED_LOBBY_COUNTDOWN, now, "Co-op: starting without every player ready.",
                FEED_WARN_COLOR);
    }

    private void onLobbyCancelCountdown() {
        long now = clockMillis.getAsLong();
        if (service.role() == CoopConnectionRole.GUEST) {
            // The guest cancels by taking its ready back; the host treats that as both.
            guestReadyRequested = false;
            lobbyRoster.cancelCountdown();
            // A "start anyway" countdown runs over a guest that is already not ready, so taking a
            // ready back changes nothing maybeSendReadyState would notice and the press reached the
            // host as silence: the local mirror flipped for one status interval and the next
            // LOBBY_STATUS put the countdown straight back. Clearing the change-detection state is
            // what turns the press into a READY_STATE the host's cancel path actually runs on.
            lastSentJoinPhase = null;
            lastSentReady = null;
            return;
        }
        if (!lobbyRoster.cancelCountdown()) {
            return;
        }
        lobbyStartAnyway = false;
        CoopLog.info(CoopNetPump.class, "Coop lobby countdown cancelled by " + sessionState.localName());
        postFeed(FEED_LOBBY_CANCELLED, now, "Co-op: countdown cancelled.", FEED_WARN_COLOR);
    }

    private void onLobbyReadyToggled(Boolean ready) {
        guestReadyRequested = Boolean.TRUE.equals(ready);
        if (!guestReadyRequested) {
            lobbyRoster.cancelCountdown();
        }
    }

    // ---- guest side --------------------------------------------------------------------------------

    private void tickGuestLobby(long now) {
        if (!service.isConnected()) {
            guestConnectStartedAtMillis = 0L;
            lastSentJoinPhase = null;
            lastSentReady = null;
            closeLobbyUi();
            return;
        }
        if (guestConnectStartedAtMillis == 0L) {
            guestConnectStartedAtMillis = now;
        }
        coop.session.CoopJoinPhase phase = guestJoinPhase();
        maybeSendReadyState(now, phase);

        if (guestJoinCancelled) {
            closeLobbyUi();
            return;
        }
        coop.ui.CoopConnectingDialog.Failure failure = guestJoinFailure(now);
        if (failure == null && phase != null
                && phase.atLeast(coop.session.CoopJoinPhase.SNAPSHOT_APPLIED)) {
            requestLobbyDialog();
            return;
        }
        requestConnectingDialog(failure);
    }

    /**
     * How far this guest has got. {@code SNAPSHOT_APPLIED} is "the host's world clock has arrived":
     * see {@link coop.session.CoopJoinPhase} for why that message and not {@code BASE_SET}.
     */
    private coop.session.CoopJoinPhase guestJoinPhase() {
        if (sessionState.connectionState() != CoopLobbyState.GUEST_CONNECTED) {
            return null;
        }
        coop.session.CoopJoinPhase phase = coop.session.CoopJoinPhase.LINK_ESTABLISHED;
        if (sessionState.handshakeValidated()) {
            phase = coop.session.CoopJoinPhase.VERSIONS_MATCHED;
        }
        if (sessionState.seedLong() != null) {
            phase = coop.session.CoopJoinPhase.SEED_LOCKED;
        }
        if (latestTimeSnapshot != null) {
            phase = coop.session.CoopJoinPhase.SNAPSHOT_APPLIED;
        }
        if (guestReadyRequested && phase == coop.session.CoopJoinPhase.SNAPSHOT_APPLIED) {
            phase = coop.session.CoopJoinPhase.READY;
        }
        return phase;
    }

    /** Guest: one {@code READY_STATE} per phase/ready change, and one on every reconnect. */
    private void maybeSendReadyState(long now, coop.session.CoopJoinPhase phase) {
        if (phase == null || sessionState.sessionId() == null) {
            return;
        }
        boolean ready = phase == coop.session.CoopJoinPhase.READY;
        if (phase == lastSentJoinPhase && lastSentReady != null && lastSentReady == ready) {
            return;
        }
        lastSentJoinPhase = phase;
        lastSentReady = ready;
        CoopMessages.Message message = CoopMessages.readyState(
                sessionState.sessionId(), service.nextSeq(), now, phase.name(), ready);
        service.send(message);
        log("outbound", message);
    }

    /** The named cause of a failed join, or null while it is still running. */
    private coop.ui.CoopConnectingDialog.Failure guestJoinFailure(long now) {
        String handshakeReason = sessionState.handshakeRejectReason();
        if (handshakeReason != null) {
            return desyncOwnsFailure.test(handshakeReason)
                    ? null
                    : coop.ui.CoopConnectingDialog.Failure.VERSION_MISMATCH;
        }
        String rejectReason = sessionState.rejectReason();
        if (rejectReason != null) {
            return desyncOwnsFailure.test(rejectReason)
                    ? null
                    : coop.ui.CoopConnectingDialog.Failure.HOST_REFUSED;
        }
        if (sessionState.connectionState() != CoopLobbyState.GUEST_CONNECTED
                && guestConnectStartedAtMillis > 0L
                && now - guestConnectStartedAtMillis >= coop.ui.CoopConnectingDialog.CONNECT_TIMEOUT_MILLIS) {
            return coop.ui.CoopConnectingDialog.Failure.TIMED_OUT;
        }
        return null;
    }

    private void requestConnectingDialog(coop.ui.CoopConnectingDialog.Failure failure) {
        // The lobby is not consulted here: the caller has already decided which of the two belongs on
        // screen (they are mutually exclusive join states), so this side of the precedence is only
        // about the reconnect dialog, which outranks both.
        boolean blocked = reconnect.active() || !coop.ui.CoopDialogArbiter.mayRequest(
                coop.ui.CoopDialogArbiter.CONNECTING,
                reconnectDialogs.isRequested() ? coop.ui.CoopDialogArbiter.RECONNECT : null,
                desyncDialogs.isRequested() ? coop.ui.CoopDialogArbiter.DESYNC : null);
        if (blocked) {
            connectingDialogs.close();
            connectingDialog = null;
            return;
        }
        lobbyDialogs.close();
        lobbyDialog = null;
        if (connectingDialog == null || !connectingDialogs.isRequested()) {
            connectingDialog = new coop.ui.CoopConnectingDialog(
                    this::connectingView, clockMillis::getAsLong, this::onConnectingCancelled);
            connectingDialogs.request(connectingDialog);
        }
    }

    private coop.ui.CoopConnectingDialog.View connectingView() {
        long now = clockMillis.getAsLong();
        coop.ui.CoopConnectingDialog.Failure failure = guestJoinFailure(now);
        String detail = "";
        if (failure == coop.ui.CoopConnectingDialog.Failure.VERSION_MISMATCH) {
            detail = String.valueOf(sessionState.handshakeRejectReason());
        } else if (failure == coop.ui.CoopConnectingDialog.Failure.HOST_REFUSED) {
            detail = String.valueOf(sessionState.rejectReason());
        }
        long elapsed = guestConnectStartedAtMillis == 0L ? 0L : Math.max(0L, now - guestConnectStartedAtMillis);
        // Only a terminal reject (the wrong lobby password) stops the connect loop; everything else
        // is back off five seconds and dial again, and the remedy line has to say so.
        boolean retrying = !sessionState.rejectTerminal();
        return new coop.ui.CoopConnectingDialog.View(guestJoinPhase(), elapsed, failure, detail, retrying);
    }

    /**
     * Guest: Cancel on the connecting dialog. Stops the retry loop and leaves the guest sitting on a
     * paused campaign; the host sees the ordinary disconnect it would have seen anyway, and nothing
     * about the cancel reaches further than that.
     */
    private void onConnectingCancelled() {
        guestJoinCancelled = true;
        long now = clockMillis.getAsLong();
        try {
            service.stopReconnecting("join cancelled by the player");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not stop the connect loop after a cancel", ex);
        }
        CoopLog.info(CoopNetPump.class, "Coop join cancelled by the player");
        postFeed(FEED_LOBBY_CANCELLED_JOIN, now,
                "Co-op: joining cancelled. Your campaign stays paused.", FEED_WARN_COLOR);
    }

    // ---- shared view --------------------------------------------------------------------------------

    private coop.ui.CoopLobbyView lobbyView() {
        long now = clockMillis.getAsLong();
        String localId = sessionState.localPlayerId();
        java.util.List<coop.ui.CoopLobbyView.Row> rows = new java.util.ArrayList<>();
        for (coop.session.CoopLobbyRoster.Row row : lobbyRoster.rows()) {
            rows.add(new coop.ui.CoopLobbyView.Row(row.name(), lobbyRoster.stateWord(row, now),
                    row.playerId().equals(localId)));
        }
        coop.session.CoopJoinPhase phase = guestJoinPhase();
        boolean canReady = service.role() == CoopConnectionRole.GUEST
                && phase != null && phase.atLeast(coop.session.CoopJoinPhase.SNAPSHOT_APPLIED);
        // Quantised to whole seconds because the dialog's "did anything change?" guard is
        // record equality: a raw millisecond counter differs on every frame, which turned the guard
        // off entirely and rebuilt the option panel underneath the host's own confirm press.
        long elapsedSeconds = (lobbyRoster.elapsedMillis(now) / 1000L) * 1000L;
        return new coop.ui.CoopLobbyView(service.role(), rows, lobbyVerdictLines(),
                lobbyRoster.countdownRemainingMillis(now), elapsedSeconds,
                lobbyRoster.afkHint(now), lobbyRoster.allReady(), lobbyRoster.startLabel(),
                guestReadyRequested, canReady, sessionState.lobbyReleased());
    }

    /** The Phase 20 connection-doctor verdict as dialog text rather than log lines. */
    private java.util.List<String> lobbyVerdictLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        coop.ui.CoopSessionIntelModel model = coop.ui.CoopSessionIntelFeed.currentModel();
        coop.ui.CoopSessionIntelModel.Reachability reach = model.reachability();
        if (reach != null && !reach.tierText().isEmpty()) {
            lines.add("Connection: " + reach.tierText());
        }
        if (reach != null && !reach.externalEndpoint().isEmpty()) {
            lines.add("Endpoint: " + reach.externalEndpoint());
        }
        coop.ui.CoopSessionIntelModel.LinkSample link = model.localLink();
        if (link != null) {
            lines.add("Link: " + coop.ui.CoopSessionIntelModel.formatRtt(link.rttMillis())
                    + " over " + coop.ui.CoopSessionIntelModel.describeTransport(link.transport()));
        }
        return lines;
    }

    // ---- inbound ------------------------------------------------------------------------------------

    /** Host: the guest's own view of its progress and its ready toggle. */
    private void handleReadyState(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !isGameplaySessionActive()) {
            return;
        }
        String guestId = sessionState.remotePlayerId();
        if (guestId == null) {
            return;
        }
        CoopMessages.ReadyState state = CoopMessages.parseReadyState(message);
        coop.session.CoopJoinPhase phase = coop.session.CoopJoinPhase.parse(
                state.phase(), coop.session.CoopJoinPhase.LINK_ESTABLISHED);
        guestReportedPhase = phase;
        guestReportedReady = state.ready();
        guestReadyStatePending = true;
        CoopLog.info(CoopNetPump.class, "Coop lobby ready-state " + remoteDisplayName()
                + " ready=" + state.ready() + " phase=" + phase);
    }

    /** Guest: the host's authoritative roster. Full replacement; the host owns this list. */
    private void handleLobbyStatus(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        CoopMessages.LobbyStatus status = CoopMessages.parseLobbyStatus(message);
        long now = clockMillis.getAsLong();
        java.util.List<coop.session.CoopLobbyRoster.Row> rows = new java.util.ArrayList<>();
        boolean first = true;
        for (CoopMessages.LobbyPlayer player : status.players()) {
            rows.add(coop.session.CoopLobbyRoster.mirroredRow(player.playerId(), player.name(), first,
                    coop.session.CoopJoinPhase.parse(player.phase(),
                            coop.session.CoopJoinPhase.LINK_ESTABLISHED),
                    player.ready(),
                    player.reconnectingMillis() < 0L ? null : now + player.reconnectingMillis(),
                    player.reason(), now));
            first = false;
        }
        lobbyRoster.replaceAll(rows, now);
        lobbyRoster.applyElapsed(status.elapsedMillis(), now);
        lobbyRoster.applyCountdownRemaining(status.countdownRemainingMillis(), now);
        if (!status.resetReason().isEmpty()) {
            guestReadyRequested = false;
            postFeed(FEED_LOBBY_RESET, now, "Co-op: ready reset - " + status.resetReason() + ".",
                    FEED_WARN_COLOR);
        }
        if (status.released()) {
            if (sessionState.releaseLobby()) {
                long seconds = Math.max(0L, now - lobbyOpenedAtMillis) / 1000L;
                // No start-anyway flag: LOBBY_STATUS does not carry one, and printing a constant
                // false was a wrong answer dressed as a real reading. Only the host knows.
                CoopLog.info(CoopNetPump.class, "Coop lobby released after " + seconds + " s");
                postFeed(FEED_LOBBY_STARTED, now, "Co-op: session started.", FEED_GOOD_COLOR);
            }
            closeLobbyUi();
            resetLobbyState();
        }
    }

    // ---- Phase 20.6 link HUD accessor ------------------------------------------------------------

    /**
     * Read-only snapshot of the link for {@link coop.ui.CoopLinkHud}. Pure: it reads the session
     * record, the pause coordinator and the clock reconciler, and mutates nothing.
     *
     * <p>Deliberately engine-free — the caller passes its own {@code sector.isPaused()} read in
     * rather than this method taking one — so the whole role/status/holder/drift mapping can be
     * tested against a fake transport with no {@code Global.getSector()} in sight.
     *
     * @param paused the caller's live {@code sector.isPaused()}; drives the HUD's colour only
     */
    public CoopHudState hudState(boolean paused) {
        CoopConnectionRole role = service.role();
        CoopLobbyState lobby = sessionState.connectionState();
        boolean active = isGameplaySessionActive();

        String badge;
        String status;
        if (role == CoopConnectionRole.HOST) {
            badge = CoopHudState.BADGE_HOST;
            // Phase 20.2 outranks the lobby state: during a grace window the session record is
            // deliberately still HOST_CONNECTED, which would otherwise read as "session active".
            status = reconnect.hostWaiting() ? CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING
                    : switch (lobby) {
                case HOST_CONNECTED -> active
                        ? (sessionState.lobbyReleased()
                                ? CoopHudState.STATUS_SESSION_ACTIVE
                                : CoopHudState.STATUS_IN_LOBBY)
                        : CoopHudState.STATUS_HANDSHAKING;
                case REJECTED -> rejectedHudStatus();
                case NONE -> CoopHudState.STATUS_NO_SESSION;
                // HOST_WAITING covers both "never had a guest" and the 12b post-drop rewind; the
                // pump's disconnect edge is the only thing that can tell them apart.
                default -> peerDroppedAfterLiveSession
                        ? CoopHudState.STATUS_GUEST_DISCONNECTED_HOLDING
                        : CoopHudState.STATUS_WAITING_FOR_GUEST;
            };
        } else if (role == CoopConnectionRole.GUEST) {
            badge = CoopHudState.BADGE_GUEST;
            status = reconnect.guestReconnecting() ? CoopHudState.STATUS_RECONNECTING
                    : switch (lobby) {
                case GUEST_CONNECTED -> active
                        ? (sessionState.lobbyReleased()
                                ? CoopHudState.STATUS_SESSION_ACTIVE
                                : CoopHudState.STATUS_IN_LOBBY)
                        : CoopHudState.STATUS_HANDSHAKING;
                case REJECTED -> rejectedHudStatus();
                case NONE -> CoopHudState.STATUS_NO_SESSION;
                default -> peerDroppedAfterLiveSession
                        ? CoopHudState.STATUS_RECONNECTING
                        : CoopHudState.STATUS_CONNECTING;
            };
        } else {
            badge = CoopHudState.BADGE_COOP;
            status = CoopHudState.STATUS_NO_SESSION;
        }

        String rawHolder = "";
        if (active && pauseCoordinator.reconnectHold()) {
            // Both roles, ahead of the host/guest split: during a grace window the holder is the
            // session itself, and the guest has no host snapshot arriving to tell it so.
            rawHolder = CoopHudState.HOLDER_RECONNECT;
        } else if (active) {
            if (role == CoopConnectionRole.HOST) {
                rawHolder = hostPauseHolder();
            } else if (role == CoopConnectionRole.GUEST) {
                // Only the host can name the holder: the guest deliberately does not store its own
                // pause-key intent locally (see CoopSharedPauseCoordinator#recordGuestPauseKeyPress),
                // so it would otherwise attribute its own press to the host. The host ships the
                // holder in the 5 Hz TIME_SNAPSHOT; the pre-field fallback is "it came from the host".
                String fromHost = latestTimeSnapshot == null ? "" : latestTimeSnapshot.pausedBy();
                if (fromHost != null && !fromHost.isEmpty()) {
                    rawHolder = fromHost;
                } else if (pauseCoordinator.observedPaused()) {
                    rawHolder = CoopHudState.HOLDER_HOST;
                }
            }
        }
        String display = CoopHudState.displayHolder(rawHolder, role);
        String pauseHolder = display.isEmpty() ? null : display;

        Integer driftGameHours = null;
        if (role == CoopConnectionRole.GUEST && active) {
            long driftMillis = clockReconciler.driftEstimateMillisForHud();
            long hours = Math.round(driftMillis / 3_600_000.0);
            if (hours != 0L) {
                driftGameHours = (int) hours;
            }
        }

        // Phase 20.6: link numbers only while a session is live — outside one they would be stale
        // readings of a link that no longer exists.
        Integer rttMillis = null;
        Integer lossPercent = null;
        String transport = null;
        Integer cadenceHz = null;
        if (active) {
            long now = clockMillis.getAsLong();
            rttMillis = linkQuality.rttMillis();
            lossPercent = linkQuality.lossPercent(now);
            transport = stateStreamFallbackActive
                    ? CoopHudState.TRANSPORT_TCP_FALLBACK
                    : CoopHudState.TRANSPORT_UDP;
            // Phase 29 M2: the line only grows a cadence segment when the tier has left default;
            // formatLine owns that rule, so what goes in here is simply the current tier.
            cadenceHz = stateStreamTier.hz();
        }

        return new CoopHudState(badge, status, paused, pauseHolder, driftGameHours,
                rttMillis, lossPercent, transport, cadenceHz);
    }

    /**
     * Raw shared-pause holder token as only the host can compute it, in coordinator precedence order.
     * Feeds both the host's own HUD line and the {@code pausedBy} field of the outbound
     * {@code TIME_SNAPSHOT} that lets the guest label its HUD correctly. Empty string = nobody.
     */
    private String hostPauseHolder() {
        // Phase 20.2 first: nobody chose this pause and no pause key clears it, so naming any player
        // as the holder while the world is held for a reconnect would be a lie.
        if (pauseCoordinator.reconnectHold()) {
            return CoopHudState.HOLDER_RECONNECT;
        }
        if (pauseCoordinator.hostPauseIntent()) {
            return CoopHudState.HOLDER_HOST;
        }
        if (pauseCoordinator.guestKeyPauseIntent()) {
            return CoopHudState.HOLDER_GUEST;
        }
        if (pauseCoordinator.guestScreenPauseIntent()) {
            return CoopHudState.HOLDER_GUEST_SCREEN;
        }
        if (pauseCoordinator.eitherInCombat()) {
            return CoopHudState.HOLDER_COMBAT;
        }
        return "";
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
        // Red-team A14: nothing below may take the pump down. An EveryFrameScript that throws is
        // removed by the engine, so one unhandled RuntimeException anywhere in the frame body -- or
        // one LinkageError from a mod-load skew that only shows up on a rarely-taken branch -- ends
        // the whole session silently, mid-game, with the world already half-synchronised. The frame
        // is the right granularity: a frame that fails part way through is recoverable (every
        // subsystem below is idempotent per frame and re-runs next frame), a dead pump is not.
        try {
            advanceFrame(amount);
        } catch (RuntimeException | LinkageError ex) {
            noteFrameFailure(ex);
        }
    }

    /**
     * Log-throttled record of a frame that threw. Rate-limited because the failure that reaches here
     * is by definition one this code did not anticipate, and an every-frame stack trace at 60 Hz is
     * how a log file becomes a gigabyte.
     */
    private void noteFrameFailure(Throwable ex) {
        frameFailureCount++;
        long now = clockMillis.getAsLong();
        if (now < nextFrameFailureLogAtMillis) {
            return;
        }
        nextFrameFailureLogAtMillis = now + FAILURE_LOG_INTERVAL_MILLIS;
        CoopLog.warn(CoopNetPump.class, "Coop pump frame failed (" + frameFailureCount
                + " total); the pump keeps running", ex);
    }

    /** How many frames have thrown out of the body; diagnostics and tests. */
    long frameFailureCount() {
        return frameFailureCount;
    }

    private void advanceFrame(float amount) {
        // net-fix-3: the transport's inbound frame and byte budgets are per campaign frame, and this
        // is the one call per frame that opens them. First, before any flushOutbound() below.
        service.beginFrame();
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
        // Phase 20.2 exemption (c): the gap since the previous frame is how the link-death rule knows
        // whether it was the peer that went quiet or this process that stopped running.
        linkQuality.noteFrame(clockMillis.getAsLong());
        long t = profiler.start();
        // Phase 31: before either start path, because this is the frame that would otherwise open a
        // port or dial the host.
        maybeRefuseForGameVersion();
        maybeStartFromSystemProperties();
        t = profiler.split(SECTION_CFG_PROPERTIES, t);
        maybeStartFromMemoryFlags();
        t = profiler.split(SECTION_CFG_MEMORY_FLAGS, t);
        service.flushOutbound();
        // net-fix-7: before the producers below tick, so a snapshot the queue cap threw away is
        // re-sent on this frame rather than the next one.
        resendSnapshotsDroppedByQueueOverflow();
        t = profiler.split(SECTION_FLUSH_OUTBOUND_PRE, t);
        detectPeerDisconnect();
        // Before the inbound drain: the session edge resets the link measurements, and a LINK_STATUS
        // that lands on the same frame the session goes live must survive that reset.
        syncLinkSupervisionArming();
        t = profiler.split(SECTION_DETECT_DISCONNECT, t);
        // Phase 20.2: the grace timer and the dialog's retry-to-open loop, right after the edge that
        // opens the window and before anything downstream reads the session as live or dead.
        tickReconnect();
        t = profiler.split(SECTION_RECONNECT, t);
        // Phase 20.3: the router mapping, cheap once it has finished.
        tickPortMapper();
        t = profiler.split(SECTION_PORT_MAPPER, t);
        syncGuestInputBlocker();
        t = profiler.split(SECTION_GUEST_INPUT_BLOCKER, t);
        maybeSendSessionResumeRequest();
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
        // Phase 21: before tickLobby so the lower-ranked lobby and connecting controllers see this
        // one's request on the same frame rather than one frame late.
        tickDesyncDialog();
        // Phase 21: after the inbound drain (so a LOBBY_STATUS or READY_STATE that landed this
        // frame is already in the roster) and before the hold, so the frame that releases the lobby
        // is the frame that unpauses.
        tickLobby();
        t = profiler.split(SECTION_LOBBY, t);
        // Phase 21: after the lobby, so the release that starts the session is already visible and
        // isSessionPlayable() is true on the very frame the first sample is taken.
        tickSessionStats(clockMillis.getAsLong());
        // Phase 28 M2: after the drain (a snapshot that landed this frame is already applied) and
        // before syncSharedPause, which is the one consumer that reads the policy this frame.
        tickOptionsPolicy();
        t = profiler.split(SECTION_OPTIONS_POLICY, t);
        maybeHoldPausedUntilSessionReady();
        t = profiler.split(SECTION_HOLD_PAUSED, t);
        // Phase 14 runs before syncSharedPause so a battle that began (or ended) this frame is already
        // reflected in the combat intent when the host computes its effective pause.
        tickBattleBridge();
        t = profiler.split(SECTION_BATTLE_BRIDGE, t);
        syncSharedPause();
        // Phase 28 M2: immediately behind it, because syncGuestSharedPauseIntent is where the
        // guest's apply boundary is crossed and the host is waiting to be told that it was.
        maybeSendOptionsApplied();
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
        syncCampaignReplicator();
        t = profiler.split(SECTION_REPLICATOR_SYNC, t);
        campaignReplicator.tickWorldDeltas();
        t = profiler.split(SECTION_REPLICATOR_WORLD_DELTAS, t);
        campaignReplicator.tickOrbitSync();
        t = profiler.split(SECTION_REPLICATOR_ORBIT_SYNC, t);
        campaignReplicator.tickPlayerRepSync();
        t = profiler.split(SECTION_REPLICATOR_REP_SYNC, t);
        // Before tickBarPool on purpose: the acceptance watcher names the offer that vanished
        // from the local pool, and on the host the claim it raises has to land while that
        // offer is still in the mission board's pool — the very next capture drops it.
        campaignReplicator.tickBarAcceptance();
        t = profiler.split(SECTION_REPLICATOR_BAR_ACCEPT, t);
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
        // Phase 20 M6: runs after the inbound drain that would have applied the snapshot, so a
        // snapshot that arrived this frame releases the gate before the options are re-asserted.
        campaignReplicator.tickMarketSyncGate();
        t = profiler.split(SECTION_REPLICATOR_MARKET_GATE, t);
        maybeSendPing();
        t = profiler.split(SECTION_PING, t);
        tickLinkSupervision();
        t = profiler.split(SECTION_LINK_SUPERVISION, t);
        service.flushOutbound();
        // Immediately behind the flush that carries a deterministic reject onto the wire: the guest's
        // connect loop may only close once its reason has left the socket.
        tickTerminalStop();
        profiler.record(SECTION_FLUSH_OUTBOUND_POST, t);
        profiler.endFrame();
    }

    // ---- Phase 20.3: port mapper + connection doctor ----------------------------------------------

    /**
     * Starts the router mapping for a host port, once per session. Nothing touches the network until
     * the first {@link #tickPortMapper()}, so this is safe on the frame the host socket opens.
     *
     * <p><b>Both host start paths read the same JVM property.</b> {@code coop.portMapping} is a
     * launch-time setting, not a per-campaign one: a host started from the sector-memory flags (the
     * in-game control path) still reads {@code -Dcoop.portMapping} rather than looking for a memory
     * flag of its own. There is no second place to configure it, and adding one would mean two answers
     * to "will this host try UPnP".
     */
    private void startPortMapper(int port) {
        if (portMapper != null) {
            return;
        }
        boolean enabled = true;
        try {
            enabled = CoopNetStartupConfig.fromSystemProperties().portMappingEnabled();
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class,
                    "Unusable coop port-mapping property; attempting automatic mapping anyway", ex);
        }
        try {
            portMapper = portMapperFactory == null
                    ? CoopPortMapper.start(port, enabled, clockMillis)
                    : portMapperFactory.apply(port);
            portMapperReportLogged = false;
        } catch (RuntimeException | LinkageError ex) {
            portMapper = null;
            CoopLog.warn(CoopNetPump.class, "Coop port mapping could not start; the host is reachable"
                    + " only through whatever the router already allows", ex);
        }
    }

    /**
     * One slice of the mapping negotiation per frame, then the connection-doctor block exactly once,
     * when the mapper reports it has stopped trying. Cheap after that: {@code tick} on a finished
     * mapper is a switch that falls through to "no work left", and the report flag short-circuits.
     */
    private void tickPortMapper() {
        CoopPortMapper mapper = portMapper;
        if (mapper == null) {
            return;
        }
        try {
            mapper.tick(clockMillis.getAsLong());
            // Red-team B5: not once per session, once per distinct verdict. The mapping is renewed
            // for as long as the host runs, and a renewal can fail or repair it - after which the
            // log line and the intel page both still showed the verdict from the first minute of the
            // session. resultVersion() moves exactly when result() would read differently.
            long version = mapper.resultVersion();
            if (mapper.result().finished()
                    && (!portMapperReportLogged || version != lastPublishedPortMapperVersion)) {
                portMapperReportLogged = true;
                lastPublishedPortMapperVersion = version;
                CoopLog.info(CoopNetPump.class,
                        CoopConnectionDoctor.hostReport(mapper.port(), mapper.result(),
                                !lobbyPassword.isEmpty(), service.peerCapacity()));
                // Phase 20.6: the same verdict, on the intel page, where a host can read it without
                // opening starsector.log.
                intelFeed.noteReachability(mapper.result());
            }
        } catch (RuntimeException | LinkageError ex) {
            // The mapper swallows its own failures, so reaching here means something structural.
            // Drop it rather than log every frame; the host stays reachable by manual forwarding.
            portMapper = null;
            CoopLog.warn(CoopNetPump.class, "Coop port mapper failed; giving up on automatic mapping", ex);
        }
    }

    /**
     * Releases the router mapping. Called from {@code CoopModPlugin.onGameLoad}, which is the only
     * teardown hook the engine gives a mod — there is no quit-to-menu or exit callback. A process that
     * exits without reaching it leaves the mapping to expire on its own: the lease
     * ({@link CoopPortMapper#LEASE_SECONDS}) is an hour, and both UPnP and NAT-PMP drop it then.
     */
    public void shutdownPortMapper() {
        CoopPortMapper mapper = portMapper;
        portMapper = null;
        portMapperReportLogged = false;
        if (mapper == null) {
            return;
        }
        try {
            mapper.shutdown();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop port mapper did not shut down cleanly", ex);
        }
    }

    /** Test seam: production talks to the real router; tests pass {@code CoopPortMapper::startOffline}. */
    void setPortMapperFactory(java.util.function.IntFunction<CoopPortMapper> factory) {
        this.portMapperFactory = factory;
    }

    /** Test-only read of the mapper the host start paths created. */
    CoopPortMapper portMapperForTest() {
        return portMapper;
    }

    /**
     * The Phase 31 game-version refusal, or null when this install may start a session.
     *
     * <p>One choke point for all three ways a session begins - the lobby that opens on a game load,
     * a new game started from the co-op New Game dialog, and a guest connect - because all three go
     * through {@link #maybeStartFromSystemProperties()} or {@link #maybeStartFromMemoryFlags()}, and
     * those two are the only places in the mod that call {@code startHost} or {@code connect}.
     * Gating them means no port is opened and no connection is attempted, which is stronger than
     * gating the lobby: {@code CoopSessionState.lobbyReleased} only decides when a session that is
     * already connected starts playing.
     */
    private coop.handshake.CoopGameVersionCheck.Result gameVersionRefusal() {
        coop.handshake.CoopGameVersionCheck.Result result;
        try {
            result = coop.handshake.CoopGameVersionCheck.remembered();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
        return result != null && result.refuses() ? result : null;
    }

    /**
     * Says so, once, on the first campaign frame after a refused load: the doctor marker, the feed
     * line and the dialog, exactly as a wire reject would.
     *
     * <p>Deliberately not raised from {@code onApplicationLoad}, where the check runs: there is no
     * campaign UI at that point, and a dialog is the only surface a player actually reads. Waiting
     * for the first frame costs nothing, because {@link #gameVersionRefusal()} has already stopped
     * the two start paths below from doing anything.
     */
    private void maybeRefuseForGameVersion() {
        if (gameVersionRefusalRaised) {
            return;
        }
        coop.handshake.CoopGameVersionCheck.Result result = gameVersionRefusal();
        if (result == null) {
            return;
        }
        gameVersionRefusalRaised = true;
        CoopLog.error(CoopNetPump.class, result.mismatchMessage()
                + "; refusing to start a co-op session (no port opened, no connection attempted)");
        raiseDesyncDialog(result.rawReason(), coop.ui.CoopDesyncReason.Source.OTHER,
                desyncCorrelationId());
    }

    private void maybeStartFromSystemProperties() {
        if (gameVersionRefusal() != null) {
            return;
        }
        if (startupConfigChecked || service.role() != CoopConnectionRole.NONE) {
            return;
        }
        startupConfigChecked = true;

        CoopNetStartupConfig config;
        try {
            config = CoopNetStartupConfig.fromSystemProperties();
        } catch (RuntimeException ex) {
            // Parse failures only. Opening the socket is a separate try below, because a port that
            // cannot be bound used to land here and be reported as "invalid properties" — the one
            // explanation that is certainly wrong, on the one failure the player has to act on.
            CoopLog.warn(CoopNetPump.class, "Invalid coop networking JVM properties", ex);
            return;
        }
        if (!config.isPresent()) {
            return;
        }
        try {
            if (config.role() == CoopConnectionRole.HOST) {
                service.startHost(config.port());
                beginLocalRole(CoopConnectionRole.HOST);
                startPortMapper(config.port());
                CoopLog.info(CoopNetPump.class, "Coop host started from JVM property "
                        + CoopNetStartupConfig.HOST_PORT_PROPERTY + "=" + config.port());
            } else if (config.role() == CoopConnectionRole.GUEST) {
                beginLocalRole(CoopConnectionRole.GUEST);
                guestConnectHost = config.host();
                guestConnectPort = config.port();
                service.connect(config.host(), config.port());
                CoopLog.info(CoopNetPump.class, "Coop guest started from JVM properties "
                        + CoopNetStartupConfig.CONNECT_HOST_PROPERTY + "=" + config.host() + ", "
                        + CoopNetStartupConfig.CONNECT_PORT_PROPERTY + "=" + config.port());
            }
        } catch (RuntimeException ex) {
            reportStartupFailure(config, ex);
        }
    }

    /**
     * The socket would not open. Says so on screen instead of leaving the player on a campaign with
     * no lobby, no pause hold and a log line blaming the launch properties: {@code startHost} rolls
     * its own transport back and rethrows, so the only trace used to be that one misleading WARN
     * while the partner's connect loop retried forever against a closed port.
     */
    private void reportStartupFailure(CoopNetStartupConfig config, RuntimeException ex) {
        String what = config.role() == CoopConnectionRole.HOST
                ? "could not open the co-op host port " + config.port()
                : "could not start the co-op connection to " + config.host() + ":" + config.port();
        String detail = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        CoopLog.error(CoopNetPump.class, "Coop " + what + "; no session will start", ex);
        raiseDesyncDialog(what + " (" + detail + "). Another program - often a game instance that has"
                        + " not finished shutting down - may already hold it. Free the port or pick"
                        + " another, then relaunch.",
                coop.ui.CoopDesyncReason.Source.OTHER, desyncCorrelationId());
    }

    /**
     * Takes the local role and settles this client's identity before anything is sent: a stable
     * player id (see {@link #adoptStableLocalPlayerId()}) has to be in place before it becomes the
     * transport's sender id and rides the first {@code LOBBY_HELLO}.
     */
    private void beginLocalRole(CoopConnectionRole role) {
        if (role == CoopConnectionRole.HOST) {
            sessionState.startHost(localPlayerName(CoopConnectionRole.HOST));
        } else {
            sessionState.startGuest(localPlayerName(CoopConnectionRole.GUEST));
        }
        adoptStableLocalPlayerId();
        service.setLocalSenderId(sessionState.localPlayerId());
        lobbyHelloSent = false;
        handshakeManifestSent = false;
        seedLockRequestSent = false;
    }

    /**
     * Replaces the per-process {@code UUID} with the one this save has used since its first coop
     * launch, minting and storing it when there is none.
     *
     * <p>Player id is the key of everything per-player that rides the save, and the Phase 21 stats
     * tally is where a per-launch id shows: {@code notePlayer} matches on id, so every reload — and
     * the documented rejoin-by-loading-the-autosave — appended a fresh column with zeroed counters
     * beside the old one, which kept the numbers and was never marked away. Never fatal: a save that
     * will not answer keeps the freshly minted id and the launch proceeds.
     */
    private void adoptStableLocalPlayerId() {
        try {
            String stored = storedLocalPlayerIdSupplier.get();
            if (stored != null && !stored.trim().isEmpty()) {
                sessionState.adoptLocalPlayerId(stored.trim());
                return;
            }
            localPlayerIdStore.accept(sessionState.localPlayerId());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not settle a stable local player id;"
                    + " this launch uses a fresh one", ex);
        }
    }

    private void maybeStartFromMemoryFlags() {
        if (gameVersionRefusal() != null) {
            return;
        }
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
        consumeMemoryFlags(sector.getMemoryWithoutUpdate());
    }

    /** The flag half of {@link #maybeStartFromMemoryFlags()}, separated so it can be driven in tests. */
    void consumeMemoryFlags(MemoryAPI memory) {
        boolean hosting = memory.contains(HOST_PORT_FLAG);
        boolean joining = memory.contains(CONNECT_HOST_FLAG) && memory.contains(CONNECT_PORT_FLAG);
        if (!hosting && !joining) {
            return;
        }
        // Read once, then clear, before anything can throw. Sector memory is serialized into the
        // save, and this method runs every frame while the role is NONE: a flag left standing made
        // "consumed" a lie twice over - a bind failure repeated the open/bind/close cycle at frame
        // rate behind a single WARN, and the flags re-fired on every later load of that save, so a
        // one-off console join silently redialled the recorded address months later.
        Object hostPortValue = hosting ? memory.get(HOST_PORT_FLAG) : null;
        Object connectHostValue = joining ? memory.get(CONNECT_HOST_FLAG) : null;
        Object connectPortValue = joining ? memory.get(CONNECT_PORT_FLAG) : null;
        memory.unset(HOST_PORT_FLAG);
        memory.unset(CONNECT_HOST_FLAG);
        memory.unset(CONNECT_PORT_FLAG);

        try {
            if (hosting) {
                int port = parsePort(hostPortValue, HOST_PORT_FLAG);
                service.startHost(port);
                beginLocalRole(CoopConnectionRole.HOST);
                // Same launch-time -Dcoop.portMapping property as the JVM-property path; see
                // startPortMapper for why there is no memory-flag equivalent.
                startPortMapper(port);
                CoopLog.info(CoopNetPump.class, "Coop host control consumed memory flag " + HOST_PORT_FLAG + "=" + port);
                return;
            }

            String host = String.valueOf(connectHostValue).trim();
            int port = parsePort(connectPortValue, CONNECT_PORT_FLAG);
            if (host.isEmpty()) {
                throw new IllegalArgumentException(CONNECT_HOST_FLAG + " is blank");
            }
            beginLocalRole(CoopConnectionRole.GUEST);
            guestConnectHost = host;
            guestConnectPort = port;
            service.connect(host, port);
            CoopLog.info(CoopNetPump.class,
                    "Coop guest control consumed memory flags " + CONNECT_HOST_FLAG + "=" + host
                            + ", " + CONNECT_PORT_FLAG + "=" + port);
        } catch (RuntimeException ex) {
            if (!memoryConfigWarningLogged) {
                CoopLog.warn(CoopNetPump.class, "Invalid coop networking memory flags", ex);
                memoryConfigWarningLogged = true;
            }
        }
    }

    /**
     * Name this client announces in {@code LOBBY_HELLO}; it becomes {@link CoopPlayerInfo#name()} and
     * is what the presence indicator and the partner's mirror fleet are labelled with.
     *
     * <p>Resolution order: {@code -Dcoop.playerName}, then the local character's own name, then the
     * role literal. The character name is only readable once the sector exists, which it does at
     * every call site -- both {@code maybeStartFrom*} paths run from {@link #advance(float)}, i.e.
     * after the campaign is up. The result is not cached here; it is captured once by
     * {@code CoopSessionState.startHost/startGuest} at session start, which is the same moment.
     */
    String localPlayerName(CoopConnectionRole role) {
        String configured = configuredPlayerName();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String characterName = characterNameSupplier.get();
        if (characterName != null && !characterName.trim().isEmpty()) {
            return characterName.trim();
        }
        return role == CoopConnectionRole.HOST ? "Host" : "Guest";
    }

    /**
     * The {@code coop.playerName} option, through the options store rather than
     * {@code System.getProperty}: that is what lets it be set from the settings file as well as from
     * a {@code -D}, on the same precedence every other coop option follows.
     */
    private static String configuredPlayerName() {
        try {
            return coop.config.CoopOptionsStore.system()
                    .raw(coop.config.CoopOptionsRegistry.PLAYER_NAME);
        } catch (RuntimeException | LinkageError ex) {
            // No engine settings surface (a unit test, a very early frame). The -D layer is still the
            // one that matters here, and it is readable without any of that.
            return System.getProperty(PLAYER_NAME_PROPERTY);
        }
    }

    /** Test seam: the production supplier reads the sector, which a unit test does not have. */
    void setCharacterNameSupplier(Supplier<String> supplier) {
        this.characterNameSupplier = Objects.requireNonNull(supplier, "characterNameSupplier");
    }

    private static String characterNameFromSector() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return "";
            }
            PersonAPI player = sector.getPlayerPerson();
            if (player == null) {
                return "";
            }
            String name = player.getNameString();
            return name == null ? "" : name.trim();
        } catch (Throwable ex) {
            return "";
        }
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
        // Unconditionally, every frame: the generation has to be tracked even across frames where
        // there is no link, or the first attach after a quiet stretch reads as a replacement.
        boolean replaced = consumeConnectionReplaced();
        if (channelWasConnected && (!connected || replaced)
                && service.role() != CoopConnectionRole.NONE) {
            if (replaced && connected) {
                CoopLog.warn(CoopNetPump.class, "Coop TCP link was replaced without a visible"
                        + " disconnect (connection generation " + lastConnectionGeneration
                        + "); treating it as a drop edge");
            }
            // net-fix-5: name the connection that just died, BEFORE anything drains the queue. That
            // generation is the only thing that distinguishes the departing partner's last campaign
            // deltas from the opening frames of whatever socket took the slot - which on the
            // half-open replacement path is a peer that has authenticated nothing, and whose frames
            // land in the same drain as the dead connection's tail.
            //
            // A drop that happens while a grace window is ALREADY open names nothing: the socket that
            // just died never resumed the session (a resume closes the window), so it was never the
            // proven one and its tail earns no exemption. Red-team B1 already treats that case as
            // hygiene only.
            preDropConnectionGeneration = reconnect.active()
                    ? NO_CONNECTION_GENERATION
                    : (replaced ? replacedConnectionGeneration : lastConnectionGeneration);
            // The peer's reason before the drop's consequences; see drainTerminalRejectsBeforeDrop.
            // A reject dispatched here has already ended the session by the time the flags below are
            // read, which is exactly what makes them read it as a refused join rather than a blip.
            drainTerminalRejectsBeforeDrop();
            // Read BEFORE anything rewinds: once the lobby is back at HOST_WAITING/GUEST_CONNECTING
            // nothing distinguishes "never had a partner" from "lost the one we had". The HUD
            // needs that distinction to say "guest disconnected, holding" rather than "waiting for
            // guest". Assignment, not OR: a drop during handshake really is not a lost session.
            //
            // Phase 21 live smoke: "mid-session" has to mean the GUEST reached a session, not that
            // this host recorded its own seed when it sent the request. isGameplaySessionActive()
            // goes true on the host at SEED_LOCK_REQUEST time, several round trips before the guest
            // has accepted anything - so a guest that rejected the seed, or vanished during the
            // handshake, used to buy a 60 s grace, a reconnect dialog and then a COOP-SESSION expiry
            // dialog for a session that never existed. SEED_LOCKED is the threshold: below it there
            // is nothing a resume could restore.
            boolean guestReachedSeedLock = service.role() != CoopConnectionRole.HOST
                    || guestPassedSeedLock();
            peerDroppedAfterLiveSession = isGameplaySessionActive() && guestReachedSeedLock;
            boolean droppedDuringJoin = isGameplaySessionActive() && !guestReachedSeedLock;
            // Read before the teardown takes the name away.
            String departingName = remoteDisplayName();
            // The session id died with the connection: keep accepting datagrams stamped with its token
            // and a reconnecting peer's stale in-flight traffic would apply to the next session. It
            // stays cleared for the whole grace window and is re-set only on an accepted resume.
            service.setExpectedSessionToken(null);
            lobbyHelloSent = false;
            handshakeManifestSent = false;
            seedLockRequestSent = false;
            preSessionCampaignDropWarned = false;
            // Red-team A4: one refusal line per connection, so the next stranger gets its own.
            unprovenPeerRefusalLogged = false;
            unprovenPeerAnswersRefused = 0L;
            // Phase 20.4: the next connection runs its own password round from scratch. An
            // outstanding nonce belonged to the socket that just died and must not be reusable.
            pendingLobbyNonce = null;
            lobbyChallengeSeen = false;
            // The link measurements belonged to the dead connection; carrying them into the next one
            // would let a pre-drop RTT sample or UDP silence decide the new link's transport.
            linkSupervisionArmed = false;
            applyStateStreamFallback(false, "peer disconnected", false, clockMillis.getAsLong());
            resetLinkSupervision(clockMillis.getAsLong());
            // The claims the dead connection was holding go with it. syncInteractionGate() resets on
            // a frame that reads !isConnected(), and on the half-open replacement path there is no
            // such frame: the stale socket is closed and the returning peer accepted inside one
            // pollNetworkLocked, so connected reads true before and after and the peer's claim from
            // drop time survived into the resumed session - a permanent "Remote player is
            // interacting: X" that disallowed every local interaction until the peer happened to
            // re-open and close X. The drop edge is the one place that sees every case.
            resetInteractionState();
            // Phase 20.2: a live session gets a grace window instead of a teardown. Everything above
            // is transport hygiene that applies either way; the session record itself survives only
            // on the grace path.
            //
            // Red-team B1: a SECOND drop while a window is already open is hygiene and nothing else.
            // beginReconnectGrace() answers false for an already-active window, which the old code
            // read as "declined, tear down" - so a guest that reconnected and dropped again inside
            // the same window had its session ended underneath a coordinator that kept holding the
            // ids. The next resume request then matched the coordinator's cached ids and was
            // ACCEPTED while sessionState.sessionId() was null: two machines, one believing it had
            // resumed, the other with no session at all.
            if (reconnect.active()) {
                CoopLog.info(CoopNetPump.class, "Coop link dropped again inside the reconnect grace"
                        + " window; the held session stands and the window keeps running");
            } else if (!beginReconnectGrace()) {
                // Red-team B7: nothing else clears this, and the HUD reads it forever after.
                peerDroppedAfterLiveSession = false;
                if (droppedDuringJoin) {
                    CoopLog.warn(CoopNetPump.class, "Coop guest left during the join handshake before"
                            + " it accepted the seed lock; no reconnect grace is owed and the lobby"
                            + " goes back to waiting for a guest");
                    // Same feed key as the roster's own departure line, so the two collapse into one
                    // notice rather than telling the player the same thing twice.
                    postFeed(FEED_LOBBY_LEFT, clockMillis.getAsLong(), "Co-op: " + departingName
                            + " left during the join handshake.", FEED_WARN_COLOR);
                }
                endSessionAfterDrop();
            }
        }
        if (connected && !channelWasConnected) {
            noteChannelConnected();
        }
        channelWasConnected = connected;
        if (!connected) {
            // Each new socket owes a fresh resume request; while there is no socket, none is pending.
            reconnect.noteChannelDown();
        }
    }

    /**
     * Disconnected&rarr;connected edge (F5). Guarantees the guest never sits silent on the host's one
     * lobby slot: a new connection either opens a lobby round or is terminal.
     *
     * <p>The hole it closes: the host writes {@code LOBBY_REJECT} and closes the socket immediately
     * behind it, so the guest's transport reports the close on the same frame the message lands in
     * the inbound queue. {@link #detectPeerDisconnect} runs before {@link #drainInbound}, so the drop
     * edge rewinds the lobby and <em>then</em> the reject sets {@link CoopLobbyState#REJECTED} — a
     * state nothing else clears while disconnected. The guest reconnected 500 ms later,
     * {@link #maybeSendLobbyHello} refused to send in REJECTED, and the connection sat mute until the
     * host's 15 s handshake deadline killed it. Twice per reject cycle, for as long as the player
     * left the game running.
     *
     * <p>A terminal reject (wrong password) does not rearm — there is no reconnect to rearm for, and
     * the state is the HUD's only explanation.
     */
    private void noteChannelConnected() {
        if (service.role() != CoopConnectionRole.GUEST || reconnect.guestReconnecting()) {
            return;
        }
        if (!sessionState.guestRearmLobby()) {
            return;
        }
        lobbyHelloSent = false;
        handshakeManifestSent = false;
        seedLockRequestSent = false;
        CoopLog.info(CoopNetPump.class, "Coop guest reconnected after a lobby reject; starting a fresh"
                + " lobby round on the new connection");
    }

    /**
     * Whether the transport attached a <em>different</em> socket since the last frame (red-team
     * B2/C1).
     *
     * <p>{@link CoopNetService#isConnected()} cannot express this. When a half-open link is replaced
     * inside one poll - the stale slot is closed and the returning peer's connection accepted in the
     * same {@code pollNetworkLocked} - the flag reads true before and after, so the pump sees no
     * edge: no token clear, no grace window, and the host stays {@code HOST_CONNECTED}. The guest's
     * resume request, dispatched later in this very frame, is then answered {@code REJECT_NOT_WAITING}
     * ("the host is not in a grace window"), the guest tears down, and the host goes on believing it
     * has a partner. That is a permanent lockout on the exact failure - an RST-style drop - the
     * reconnect grace exists for.
     *
     * <p>Reading it here, before {@link #drainInbound()}, is what makes the ordering work: the drop
     * edge opens the window, and the resume request already sitting in the inbound queue resolves it
     * on the same frame.
     *
     * <p>It also records which generation went away ({@link #replacedConnectionGeneration}), because
     * "the socket changed" and "which socket the messages in the queue came from" are different
     * questions and the drop edge below has to answer the second one (net-fix-5).
     */
    private boolean consumeConnectionReplaced() {
        long generation = service.connectionGeneration();
        replacedConnectionGeneration = NO_CONNECTION_GENERATION;
        if (!connectionGenerationInitialized) {
            connectionGenerationInitialized = true;
            lastConnectionGeneration = generation;
            return false;
        }
        if (generation == lastConnectionGeneration) {
            return false;
        }
        replacedConnectionGeneration = lastConnectionGeneration;
        lastConnectionGeneration = generation;
        return true;
    }

    /**
     * Whether an inbound message stamped {@code generation} came over the connection that carried the
     * live session up to the most recent drop edge; see {@link #preDropConnectionGeneration}.
     *
     * <p>This replaced a positional rule — "everything drained before the edge is the partner's,
     * everything after it is a stranger's" — which is not something the drain can observe. One
     * {@code pollNetworkLocked} closes a stale link and accepts its replacement, so the pre-drain
     * loop routinely pulls the dead socket's tail and the replacement's opening frames out of the
     * same queue, and it used to tag both as proven. That let a {@code WORLD_DELTA} or a
     * {@code MARKET_TXN} from an unauthenticated socket bypass the grace whitelist and mutate the
     * host's campaign before any password or resume proof.
     */
    private boolean isPreDropProven(long generation) {
        return preDropConnectionGeneration != NO_CONNECTION_GENERATION
                && generation == preDropConnectionGeneration;
    }

    /**
     * Re-sends the snapshots the transport's outbound queue cap discarded (net-fix-7).
     *
     * <p>The cap drops the oldest coalescable message when a peer's socket has stopped draining, on
     * the reasoning that a superseded snapshot is the one thing whose loss changes nothing. That is
     * only true if the producer sends another one — and two of them will not. {@code NPC_FLEET_SET}
     * is suppressed while the roster's content hash is unchanged, and the bar pool's
     * {@code MISSION_POOL_SNAPSHOT} the same way, so a dropped copy stayed dropped until the roster
     * actually changed or the link reconnected. On the guest that reads as NPC fleets that are there
     * and should not be, or gone and should not be, for as long as the sector stays quiet.
     *
     * <p>The other coalescable types are genuinely periodic and heal themselves:
     * {@code TIME_SNAPSHOT}, {@code LINK_STATUS} and {@code STATE_DATAGRAM} are re-sent every few
     * frames. {@code PLAYER_REP_SNAPSHOT} is on a 30 s timer rather than a hash, which heals but
     * slowly, so it is pulled forward here too rather than left to drift for half a minute.
     */
    private void resendSnapshotsDroppedByQueueOverflow() {
        java.util.Set<CoopMessages.Type> dropped = service.drainOverflowDroppedSnapshotTypes();
        if (dropped.isEmpty()) {
            return;
        }
        for (CoopMessages.Type type : dropped) {
            switch (type) {
                case NPC_FLEET_SET -> {
                    npcFleetReplicator.forceResendSet();
                    overflowSnapshotResendsForced.add(type);
                }
                case MISSION_POOL_SNAPSHOT -> {
                    campaignReplicator.forceResendMissionPool();
                    overflowSnapshotResendsForced.add(type);
                }
                case PLAYER_REP_SNAPSHOT -> {
                    campaignReplicator.forceResendPlayerRepSnapshot();
                    overflowSnapshotResendsForced.add(type);
                }
                default -> {
                    // TIME_SNAPSHOT, LINK_STATUS, STATE_DATAGRAM: the next tick sends another.
                }
            }
        }
        CoopLog.warn(CoopNetPump.class, "Coop outbound queue overflow discarded snapshot types "
                + dropped + "; forcing a resend of the ones whose producer suppresses unchanged"
                + " content, because for those the discarded copy was the only one");
    }

    // ---- Phase 20.2: in-session reconnect grace ---------------------------------------------------

    /**
     * Today's full session teardown, unchanged: the lobby rewinds so a reconnecting peer runs the
     * whole lobby/handshake/seed-lock sequence on the new connection, and every session-scoped
     * subsystem tears itself down on the same frame because {@link #isGameplaySessionActive()} goes
     * false. Reached on a pre-session drop, on grace expiry, and whenever the grace is declined.
     */
    private void endSessionAfterDrop() {
        boolean changed = sessionState.onChannelDisconnected();
        latestTimeSnapshot = null;
        // The slot is free, so the next guest owes its own seed-lock ack before it counts as one.
        guestSeedLockAcked = false;
        // Phase 20.6: drop every live reading but keep the event log, so the page still explains what
        // happened after the session it described is gone.
        intelFeed.endSession();
        if (changed) {
            CoopLog.warn(CoopNetPump.class, "Coop peer disconnected; session reset, awaiting reconnect as "
                    + service.role());
        }
    }

    /**
     * Opens the grace window if this drop deserves one, i.e. the session was live and the identity a
     * resume has to be matched against still exists. A drop before the session went live keeps the
     * pre-20.2 behaviour exactly: there is nothing to hold.
     *
     * @return true when a window opened and the session record must be kept
     */
    private boolean beginReconnectGrace() {
        if (!peerDroppedAfterLiveSession || reconnect.graceMillis() <= 0L || reconnect.active()) {
            return false;
        }
        String sessionId = sessionState.sessionId();
        long now = clockMillis.getAsLong();
        if (service.role() == CoopConnectionRole.HOST) {
            reconnect.beginHostWait(sessionId, sessionState.remotePlayerId(), now);
        } else if (service.role() == CoopConnectionRole.GUEST) {
            // The guest matches on its OWN id: that is what the host remembers about the peer it lost.
            reconnect.beginGuestReconnect(sessionId, sessionState.localPlayerId(), now);
        }
        return reconnect.active();
    }

    /**
     * Host: has the guest reached {@link coop.session.CoopJoinPhase#SEED_LOCKED}? Three sources, any
     * of which is proof: the {@code SEED_LOCK_ACK} this host accepted, the phase the guest reports in
     * {@code READY_STATE} (which covers a guest whose ack this host somehow missed), and a released
     * lobby. The host's own seed state is deliberately not one of them - that is the value that made
     * a never-joined guest look mid-session.
     */
    private boolean guestPassedSeedLock() {
        return guestSeedLockAcked
                || (guestReportedPhase != null
                        && guestReportedPhase.atLeast(coop.session.CoopJoinPhase.SEED_LOCKED))
                // A released lobby is proof by construction: the gate cannot open without a guest
                // that reached SNAPSHOT_APPLIED and pressed Ready, which is two phases past the seed
                // lock. It is listed because the ack flag is per-connection state and this one is
                // per-session - a session in play must never lose its grace to a bookkeeping gap.
                || sessionState.lobbyReleased();
    }

    /** Frame tick for the grace window and the dialog's retry-until-it-opens loop. */
    private void tickReconnect() {
        if (reconnect.active() && service.role() == CoopConnectionRole.NONE) {
            // The transport was torn down under us (game unload, explicit shutdown): there is no
            // session left to hold and no teardown left to run.
            reconnect.abandon();
            releaseReconnectHold();
            return;
        }
        reconnect.tick(clockMillis.getAsLong());
        reconnectDialogs.tick();
    }

    /** Guest: one {@code SESSION_RESUME_REQUEST} per reconnected socket, in place of the lobby hello. */
    private void maybeSendSessionResumeRequest() {
        if (!reconnect.resumeRequestDue()
                || service.role() != CoopConnectionRole.GUEST
                || !service.isConnected()) {
            return;
        }
        String sessionId = sessionState.sessionId();
        String playerId = sessionState.localPlayerId();
        if (sessionId == null || playerId == null) {
            // Nothing to ask for: fall back to the ordinary lobby round.
            reconnect.end("local session identity was lost");
            return;
        }
        CoopMessages.Message request = CoopMessages.sessionResumeRequest(
                sessionId, service.nextSeq(), clockMillis.getAsLong(), playerId);
        service.send(request);
        reconnect.markResumeRequestSent();
        log("outbound", request);
    }

    /**
     * Host: a returning guest is asking for its session back. A mismatch is rejected and the window
     * keeps running — a stranger connecting mid-grace must not be able to end the wait early, which
     * is exactly what would happen if a bad request were treated as "the peer is not coming back".
     */
    private void handleSessionResumeRequest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }
        // Red-team B1: with no session id there is nothing a resume could restore. The coordinator
        // may still be holding cached ids from a window that was torn down underneath it, and
        // matching a request against those alone would hand the session back to a peer while this
        // side has none - the exact divergence the second-drop bug produced.
        if (sessionState.sessionId() == null) {
            CoopMessages.Message reject = CoopMessages.sessionResumeReject(
                    null, service.nextSeq(), clockMillis.getAsLong(), "no session to resume");
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop rejected SESSION_RESUME_REQUEST: this side holds"
                    + " no session id, so there is nothing to give back");
            noteDesyncMarkerOnly("no session to resume",
                    coop.ui.CoopDesyncReason.Source.SESSION_RESUME, desyncCorrelationId());
            return;
        }
        if (!checkResumePassword(message)) {
            return;
        }
        String requestSession = CoopMessages.parseResumeSessionId(message);
        String requestPlayer = CoopMessages.parseResumePlayerId(message);
        CoopReconnectCoordinator.ResumeDecision decision =
                reconnect.evaluateResumeRequest(requestSession, requestPlayer);
        if (!decision.accepted()) {
            String reason = CoopReconnectCoordinator.rejectReason(decision);
            CoopMessages.Message reject = CoopMessages.sessionResumeReject(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(), reason);
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop rejected SESSION_RESUME_REQUEST (" + reason
                    + "); the grace window keeps running");
            // Marker only: the guest that just got this will raise the dialog on its own side, and
            // this host's window is still open for the peer it is actually waiting for.
            noteDesyncMarkerOnly(reason, coop.ui.CoopDesyncReason.Source.SESSION_RESUME,
                    desyncCorrelationId());
            return;
        }
        CoopMessages.Message accept = CoopMessages.sessionResumeAccept(
                sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong());
        service.sendTo(message.senderId(), accept);
        log("outbound", accept);
        // Only after the accept is queued: the resume re-sets the datagram token and forces the
        // rebroadcast, and both belong strictly after the guest has been told it may keep the session.
        reconnect.resume();
    }

    /** Guest: the host gave the session back. */
    private void handleSessionResumeAccept(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !reconnect.guestReconnecting()) {
            return;
        }
        // net-fix-2: an accept can reach this handler with no socket under it - it arrived on the
        // pre-drop connection, was parked by drainTerminalRejectsBeforeDrop, and is dispatched after
        // the drop edge that killed the link. Resuming on it unpauses the guest against a host that
        // cannot hear it, for as long as it takes the host's handshake deadline to force a fresh
        // drop edge. The window stays open instead, and resumeRequestDue() re-asks on the next
        // socket - which is the one exchange that can prove the link is really back.
        if (!service.isConnected()) {
            CoopLog.info(CoopNetPump.class, "Coop ignoring SESSION_RESUME_ACCEPT for session "
                    + sessionState.sessionId() + ": there is no live connection to resume on;"
                    + " the reconnect window keeps running and the request goes out again");
            return;
        }
        String accepted = CoopMessages.parseResumeSessionId(message);
        if (!accepted.equals(sessionState.sessionId())) {
            CoopLog.warn(CoopNetPump.class, "Coop SESSION_RESUME_ACCEPT named session " + accepted
                    + " but this guest holds " + sessionState.sessionId() + "; ending the session");
            reconnect.end("resume accept named a different session");
            return;
        }
        reconnect.resume();
    }

    /** Guest: the host will not take us back, so the session is over now rather than at expiry. */
    private void handleSessionResumeReject(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !reconnect.guestReconnecting()) {
            return;
        }
        String reason = CoopMessages.parseResumeRejectReason(message);
        reconnect.end(CoopReconnectCoordinator.REASON_HOST_REJECTED
                + (reason.isEmpty() ? "" : ": " + reason));
    }

    /**
     * The only vocabulary an unproven peer may speak while a grace window is open: the resume
     * exchange itself, a lobby hello, and the heartbeat that keeps the half-open detector honest.
     * Deliberately a whitelist — a new message type must be argued into this window rather than fall
     * into it.
     *
     * <p>The hello is on this list because it is the <em>only</em> thing a relaunched partner can
     * say, and since the Phase 21 live smoke it is served rather than refused: it runs the host's
     * password gate and, if it clears, ends the wait and opens a fresh lobby round
     * ({@link #endHostWaitForReturningPartner}). {@code LOBBY_CHALLENGE} below is what makes the
     * proof round possible in both directions.
     */
    private static boolean allowedDuringReconnectGrace(CoopMessages.Type type) {
        return type == CoopMessages.Type.SESSION_RESUME_REQUEST
                || type == CoopMessages.Type.SESSION_RESUME_ACCEPT
                || type == CoopMessages.Type.SESSION_RESUME_REJECT
                || type == CoopMessages.Type.LOBBY_HELLO
                // The answer to the hello above, when the host runs a password gate. Without it a
                // guest that reconnects on a password-protected host would be told to prove itself
                // and then have its own proof round silently dropped.
                || type == CoopMessages.Type.LOBBY_CHALLENGE
                || type == CoopMessages.Type.PING
                || type == CoopMessages.Type.PONG;
    }

    /**
     * Of the messages that arrived over the <em>proven</em> pre-drop connection and were parked by
     * {@link #drainTerminalRejectsBeforeDrop()}, the ones still worth applying once the drop edge has
     * run (net-fix-2).
     *
     * <p>The rule is not "is it safe from a stranger" - these did not come from a stranger, they came
     * from the partner, before the link died. The rule is "did the drop edge already destroy what
     * this would have been applied to". {@link #detectPeerDisconnect()} clears the session token,
     * rewinds the lobby round (hello/manifest/seed-lock flags, the outstanding password nonce), and
     * runs {@link #resetInteractionState()}. So:
     *
     * <ul>
     *   <li><b>Applied</b> - campaign deltas, events and snapshots. They describe the world, not this
     *   connection: a MARKET_TXN the partner made a frame before its router died is as true after the
     *   drop as before it, and dropping it is a silent divergence that survives the resume (the
     *   forced rebroadcast heals host-to-guest snapshot state only, and nothing at all heals
     *   guest-to-host events).</li>
     *   <li><b>Discarded</b> - INTERACTION_* and DIALOG_BEGIN, because resetInteractionState() has
     *   already released every claim they refer to and applying one re-creates the permanent
     *   "Remote player is interacting" lock that reset exists to clear; the lobby, handshake,
     *   seed-lock and resume vocabularies, because the drop edge rewound the round they belong to and
     *   the new connection runs its own; LINK_STATUS/PING/PONG and READY_STATE, which measure or
     *   describe a connection that no longer exists.</li>
     * </ul>
     *
     * <p>Enumerated in full, with no {@code default -> true}: a message type added later must be
     * argued onto this list rather than inherit a pass.
     */
    private static boolean survivesTheDropEdge(CoopMessages.Type type) {
        return switch (type) {
            // Campaign deltas and events: the world moved, and it stays moved.
            case WORLD_DELTA, MARKET_OPEN, MARKET_SNAPSHOT, MARKET_TXN,
                 RAID_RESULT, SHIP_LOST,
                 COLONY_FOUNDED, COLONY_ABANDONED, COLONY_MGMT, COLONY_INCOME, EXPEDITION_WARNING,
                 MISSION_POOL_SNAPSHOT, MISSION_CLAIM_REQUEST, MISSION_CLAIM_ACCEPT,
                 MISSION_CLAIM_REJECT,
                 REP_DELTA, GUEST_REP_DELTA, PLAYER_REP_SNAPSHOT, FACTION_REL_DELTA,
                 ABILITY_ACTIVATE,
                 BATTLE_BEGIN, BATTLE_STATUS, BATTLE_END, BATTLE_RESULT, ENGAGE_GUEST,
                 SAVE_CHECKPOINT, RESPAWN_PLAYER, STALL_NOTICE,
                 // Snapshots. Superseded by the resume's forced rebroadcast when one comes, and the
                 // best available picture of the world when it does not.
                 ORBIT_SNAPSHOT, NPC_FLEET_SET, NPC_FLEET_MOTION, BASE_SET, FLEET_SNAPSHOT,
                 FLEET_ROSTER, GUEST_SNAPSHOT, SESSION_STATS, STATE_DATAGRAM,
                 TIME_SNAPSHOT, PAUSE_INTENT,
                 OPTIONS_SNAPSHOT, OPTIONS_APPLIED -> true;
            // Scoped to the connection the drop edge just tore down, or to state it just reset.
            case INTERACTION_CLAIM, INTERACTION_ACCEPT, INTERACTION_REJECT, INTERACTION_RELEASE,
                 DIALOG_BEGIN,
                 LOBBY_HELLO, LOBBY_CHALLENGE, LOBBY_ACCEPT, LOBBY_REJECT, LOBBY_STATUS,
                 HANDSHAKE_MANIFEST, HANDSHAKE_RESULT,
                 SEED_LOCK_REQUEST, SEED_LOCK_ACK, SEED_LOCK_REJECT,
                 SESSION_RESUME_REQUEST, SESSION_RESUME_ACCEPT, SESSION_RESUME_REJECT,
                 READY_STATE, FLEET_ROSTER_REQUEST, LINK_STATUS,
                 PING, PONG, UDP_PROBE, PATH_PROBE -> false;
        };
    }

    /**
     * The dialogs' "wait more" option (Phase 20 live QA, finding F1). Pushes the deadline back by
     * {@link CoopReconnectCoordinator#WAIT_MORE_MILLIS} and leaves everything else exactly as it was:
     * the hold stands, the dialog stays up, and its countdown picks the new number up on its own.
     *
     * <p>Called from the engine's option handler rather than from {@code advance()}, so like every
     * other UI callback it swallows what it might throw — a dialog press must never be able to take
     * the pump down.
     */
    private void extendReconnectWait() {
        try {
            long now = clockMillis.getAsLong();
            if (!reconnect.extend(CoopReconnectCoordinator.WAIT_MORE_MILLIS, now)) {
                return;
            }
            CoopLog.info(CoopNetPump.class, "Coop reconnect wait extended by "
                    + (CoopReconnectCoordinator.WAIT_MORE_MILLIS / 1000L) + " s as " + service.role()
                    + "; " + reconnect.remainingSeconds(now) + " s left on session "
                    + sessionState.sessionId());
            postFeed(FEED_RECONNECT_EXTENDED, now, "Co-op: still waiting - "
                    + (CoopReconnectCoordinator.WAIT_MORE_MILLIS / 60_000L)
                    + " more minutes on the clock.", FEED_WARN_COLOR);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not extend the reconnect wait", ex);
        }
    }

    /** Clears the shared hold and takes the dialog down; safe to call when neither is engaged. */
    private void releaseReconnectHold() {
        pauseCoordinator.setReconnectHold(false);
        reconnectDialogs.close();
    }

    /**
     * Guest-side hold. The guest's clock follows the host's {@code TIME_SNAPSHOT}, and there will be
     * no more of those until the link is back — so the last one is re-stamped as paused and the
     * every-frame re-apply that already exists becomes the hold. Reusing that path rather than adding
     * a second {@code setPaused} caller is what keeps the guest's own interaction-dialog exemption
     * (see {@link #maybeApplyTimeSnapshot()}) intact: with the reconnect dialog open, vanilla owns the
     * clock and pauses it anyway, and this is the backstop for the frames before it manages to open.
     */
    private void holdGuestClockForReconnect() {
        if (latestTimeSnapshot == null) {
            return;
        }
        latestTimeSnapshot = new CoopTimeLock.TimeSnapshot(
                true,
                false,
                latestTimeSnapshot.timestampMillis(),
                latestTimeSnapshot.campaignDay(),
                latestTimeSnapshot.sentAtMillis(),
                CoopHudState.HOLDER_RECONNECT);
    }

    /**
     * The forced full rebroadcast a resume owes the returning peer. It does not build a second
     * broadcast path: the session-start rebroadcast <em>is</em> the streaming edges in
     * {@link #syncNpcReplication()}, {@link #syncBaseReplication()}, {@link #syncBarGeneration()} and
     * {@link #syncCampaignReplicator()}, all gated on {@code service.isConnected()}. They already went
     * inactive on the drop, so the resume frame takes the active edge and everything — NPC set, base
     * set, bar/mission pool, faction relations, player rep snapshot, colony state — is re-sent from
     * scratch. Clearing the flags here makes that guarantee explicit rather than incidental, and the
     * two cadenced streams that are not edge-driven are pulled forward by hand.
     */
    private void forceFullRebroadcast() {
        npcReplicationStreaming = false;
        baseReplicationStreaming = false;
        barSuppressionArmed = false;
        npcFleetReplicator.reset();
        baseAuthority.reset();
        datagramWatermark.reset();
        datagramRedundancy.reset();
        // Phase 20 M4: the returning peer holds no roster, so this side owes it one before its next
        // tick can be applied to anything.
        lastSentRosterHash = "";
        rosterCache.reset();
        // Phase 28 M2: the returning peer may have missed a policy change while it was gone, and
        // the resume broadcast the plan asks for is exactly this one line - the periodic comparison
        // in maybeSendOptionsSnapshot does the rest.
        lastSentOptionsVersion = -1;
        // ...and the returning guest owes the host a fresh acknowledgement of it.
        lastSentOptionsAppliedVersion = -1;
        // The returning guest's clock is frozen until the first snapshot lands, so it does not wait
        // out the 5 Hz cadence; the guest snapshot is cheap and re-establishes the save material.
        nextTimeSnapshotAtMillis = 0L;
        nextGuestSnapshotAtMillis = 0L;
    }

    /** The grace window's side effects; see {@link CoopReconnectCoordinator}. */
    private final class ReconnectListener implements CoopReconnectCoordinator.Listener {

        @Override
        public void onGraceStarted(CoopReconnectCoordinator.State state, long graceMillis) {
            long now = clockMillis.getAsLong();
            long seconds = graceMillis / 1000L;
            // RECONNECT is the top of the precedence order and the arbiter is only consulted by the
            // lower-ranked requesters, so the lower-ranked dialogs are closed here rather than left
            // to lose a race for the exclusive slot on some later frame.
            closeLobbyUi();
            desyncDialogs.close();
            pauseCoordinator.setReconnectHold(true);
            // Before the hold reaches the clock: from here the pause on screen is coop's, and it has
            // to still be coop's after an expiry hands the clock to the no-session hold (F2).
            claimCoopPauseHoldForReconnect();
            // One warning per window, not one per session: a second blip deserves its own line.
            graceTrafficDropWarned = false;
            if (state == CoopReconnectCoordinator.State.HOST_WAIT) {
                CoopLog.warn(CoopNetPump.class, "Coop guest link died mid-session; holding the world"
                        + " for " + seconds + " s awaiting a resume of session " + sessionState.sessionId());
                postFeed(FEED_RECONNECT_WAIT, now, "Co-op: " + remoteDisplayName()
                        + " disconnected - holding the game for " + seconds + "s.", FEED_WARN_COLOR);
                reconnectDialogs.request(new coop.ui.CoopReconnectHostDialog(
                        sessionState.remoteName(),
                        () -> reconnect.remainingSeconds(clockMillis.getAsLong()),
                        CoopNetPump.this::extendReconnectWait,
                        () -> reconnect.end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER)));
            } else {
                holdGuestClockForReconnect();
                CoopLog.warn(CoopNetPump.class, "Coop host link died mid-session; reconnecting for "
                        + seconds + " s to resume session " + sessionState.sessionId());
                postFeed(FEED_RECONNECT_WAIT, now, "Co-op: connection to the host lost -"
                        + " reconnecting for " + seconds + "s.", FEED_WARN_COLOR);
                reconnectDialogs.request(new coop.ui.CoopReconnectGuestDialog(
                        sessionState.remoteName(),
                        () -> reconnect.remainingSeconds(clockMillis.getAsLong()),
                        CoopNetPump.this::extendReconnectWait,
                        () -> reconnect.end(CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER)));
            }
        }

        @Override
        public void onResumed(CoopReconnectCoordinator.State previous) {
            long now = clockMillis.getAsLong();
            releaseReconnectHold();
            // Same session, same token: the datagram watermark is session-scoped by that token, so
            // epochs continue where they left off and M1's re-attach has already invalidated the
            // validated UDP address for the challenge to re-earn.
            String sessionId = sessionState.sessionId();
            if (sessionId != null) {
                service.setExpectedSessionToken(CoopMessages.wireToken(sessionId));
            }
            // Silence timers only: the RTT history is the same two machines on the same path.
            linkQuality.resetSilence(now);
            // Phase 20 M6: the guest may be a fresh process counting PAUSE_INTENT seqs from zero
            // again, and a stale high-water mark would eat its first several pause presses. Nothing
            // older can still be in flight -- the connection that carried them is gone.
            pauseCoordinator.resetGuestIntentWatermark();
            // Every clock sample spans a stalled link, so none of them says anything about drift.
            clockReconciler.clearSamples();
            // Phase 20 M4: both roles owe the peer a roster on a resume, and neither may trust the
            // one it cached across the outage. The host's forceFullRebroadcast below repeats this;
            // it is here so the guest branch gets it too.
            lastSentRosterHash = "";
            rosterCache.reset();
            if (previous == CoopReconnectCoordinator.State.HOST_WAIT) {
                forceFullRebroadcast();
            } else {
                // Drop the synthesized hold snapshot; the host's next real one is the truth.
                latestTimeSnapshot = null;
                lastReconciledTimeSnapshot = null;
            }
            CoopLog.info(CoopNetPump.class, "Coop session " + sessionId + " resumed after a link drop as "
                    + service.role() + "; forcing a full rebroadcast");
            postFeed(FEED_RECONNECT_RESUMED, now, "Co-op: connection restored - session resumed.",
                    FEED_GOOD_COLOR);
        }

        @Override
        public void onEnded(CoopReconnectCoordinator.State previous, String reason) {
            long now = clockMillis.getAsLong();
            // Before endSessionAfterDrop, which runs onChannelDisconnected and with it the clear that
            // takes the session id away.
            String correlationId = desyncCorrelationId();
            releaseReconnectHold();
            service.setExpectedSessionToken(null);
            // Red-team B7: the flag that told the HUD "we lost a partner, we are holding" is only
            // ever set. Once the window has closed there is nothing left to hold, and leaving it set
            // makes every later pre-session state render as a reconnect hold for the rest of the
            // process - including the fresh lobby the player opens next.
            peerDroppedAfterLiveSession = false;
            endSessionAfterDrop();
            if (previous == CoopReconnectCoordinator.State.HOST_WAIT) {
                // In onEnded rather than at any one of the three exits (relaunch, expiry, the
                // player's give-up), so they cannot diverge: whichever way the wait ended, the held
                // session is gone and the row standing in for its guest goes with it.
                forgetDepartedGuestLobbyRows(null, "the reconnect wait ended (" + reason + ")");
            }
            // Phase 21 live smoke: the partner knocking on the lobby takes the whole teardown above
            // and none of the failure furniture below. endHostWaitForReturningPartner already wrote
            // the one line this deserves, on the frame it still knew the numbers, and the lobby it
            // is opening is the explanation - not a WARN, not a "session ended" banner, and not a
            // [COOP-DOCTOR] marker, because nothing here is a desync.
            if (CoopReconnectCoordinator.REASON_PARTNER_RELAUNCHED.equals(reason)) {
                CoopLog.info(CoopNetPump.class, "Coop held session cleared as " + service.role()
                        + "; the returning partner runs an ordinary lobby round on this connection");
                return;
            }
            CoopLog.warn(CoopNetPump.class, "Coop reconnect grace closed without a resume as "
                    + service.role() + " (" + reason + "); the session is over");
            postFeed(FEED_RECONNECT_ENDED, now, "Co-op: session ended - " + reason + ".", FEED_BAD_COLOR);
            // Phase 21: the two terminal ends a player did not choose get a dialog. "Ended by player"
            // does not — that one is somebody pressing the reconnect dialog's own give-up option, and
            // answering a button press with a second modal explaining what the button did is the
            // shape of an inescapable dialog loop, not an explanation.
            if (!CoopReconnectCoordinator.REASON_ENDED_BY_PLAYER.equals(reason)) {
                noteDesync(reason, coop.ui.CoopDesyncReason.Source.SESSION_RESUME, correlationId);
            }
        }
    }

    /**
     * The peer's last word, dispatched before this frame acts on the drop that came with it (Phase 21
     * live smoke, wrong-seed guest).
     *
     * <p>A guest that rejects deterministically sends the reject and closes, so the bytes and the FIN
     * land in the same poll on this side. {@link #detectPeerDisconnect()} runs before
     * {@link #drainInbound()} for a reason that must not be undone - the drop edge has to open the
     * reconnect window before the resume request already sitting in the queue is dispatched, or a
     * returning guest is answered "no window is open" - so this cannot be a blanket pre-drain. It is
     * a whitelist of the three "here is why this is over" messages instead: everything else is put
     * aside in {@link #deferredInbound} and reaches {@code drainInbound} in its original order, a few
     * lines later, exactly as before.
     *
     * <p>Dispatching them here rather than after the teardown is what makes the two logs match: the
     * session record is still whole, so {@code handleSeedLockReject} reads the same session id the
     * guest stamped on its own {@code [COOP-DOCTOR]} line, and the drop that follows is then judged
     * against a session the reject has already ended.
     */
    private void drainTerminalRejectsBeforeDrop() {
        CoopNetService.Inbound entry;
        while ((entry = service.pollInboundEntry()) != null) {
            if (isTerminalRejectType(entry.message().type())) {
                dispatchOneInbound(entry.message(), isPreDropProven(entry.connectionGeneration()));
            } else {
                // net-fix-2/net-fix-5: parked with the generation of the socket that produced it, not
                // with a blanket "proven". Bytes off the connection that WAS the partner have to
                // survive the grace whitelist a few lines later - it exists to filter whoever holds
                // the slot AFTER the edge - but this same loop also drains frames from a replacement
                // socket that attached inside the very poll that killed the old one, and those are
                // exactly what the whitelist is for. Only the stamp tells them apart.
                deferredInbound.add(new DeferredInbound(entry.message(), entry.connectionGeneration()));
            }
        }
    }

    /**
     * An inbound message plus the connection generation it was framed off; see
     * {@link #drainTerminalRejectsBeforeDrop()}, {@link #isPreDropProven(long)} and
     * {@link #survivesTheDropEdge}.
     */
    private record DeferredInbound(CoopMessages.Message message, long connectionGeneration) {
    }

    /**
     * The peer's verdicts on a join; see {@link #drainTerminalRejectsBeforeDrop()}.
     *
     * <p>{@code HANDSHAKE_RESULT} carries both the accept and the reject - the distinction is in the
     * payload - so it is whitelisted whole rather than parsed here. Dispatching an accept a few lines
     * early costs nothing: it validates a handshake the drop is about to tear down anyway, exactly as
     * it would have from the ordinary drain.
     *
     * <p><b>{@code LOBBY_REJECT} is deliberately not on this list</b>, symmetrical as it would look.
     * The guest's answer to it is F5's: enter {@code REJECTED} <em>after</em> the drop edge has
     * rewound the lobby, so the reason survives on the HUD and {@code noteChannelConnected} rearms on
     * the next connection. Pulling it forward puts the rewind last, which wipes the reason the player
     * is owed. The seed and handshake rejects have no such ordering to protect - what they owe is a
     * doctor marker carrying the session id, and that only exists before the teardown.
     */
    private static boolean isTerminalRejectType(CoopMessages.Type type) {
        return type == CoopMessages.Type.SEED_LOCK_REJECT
                || type == CoopMessages.Type.HANDSHAKE_RESULT;
    }

    private void drainInbound() {
        DeferredInbound next;
        while ((next = nextInbound()) != null) {
            dispatchOneInbound(next.message(), isPreDropProven(next.connectionGeneration()));
        }
    }

    /** Anything the pre-drain set aside comes first, so the peer's order is the order we apply. */
    private DeferredInbound nextInbound() {
        DeferredInbound deferred = deferredInbound.poll();
        if (deferred != null) {
            return deferred;
        }
        CoopNetService.Inbound entry = service.pollInboundEntry();
        // net-fix-5: judged by which socket produced it, not by when this loop happened to read it.
        // "Anything read now arrived after the drop edge" was the old assumption, and it is false in
        // both directions: the dead connection's tail can still be sitting in the queue here, and a
        // replacement socket's frames can already have been read before the edge was even noticed.
        return entry == null ? null : new DeferredInbound(entry.message(), entry.connectionGeneration());
    }

    private void dispatchOneInbound(CoopMessages.Message message, boolean preDropProven) {
        logInbound(message);
        // Any inbound TCP message proves the peer's process is alive and its pump is running.
        // That is what lets the UDP-blocked rule tell "the network eats UDP" apart from "the peer
        // is in combat", where both transports go quiet together.
        linkQuality.noteInboundTcp(clockMillis.getAsLong());
        // Log-and-drop guard: handlers throw freely (missing payload fields, unknown enum values,
        // out-of-order lobby messages). Letting one escape kills EveryFrameScript.advance() and
        // with it the whole pump, so a version-skewed peer or a stray connection could take the
        // session down. One bad message is a bug to log, never a peer to disconnect (Phase 12b).
        long dispatchStart = profiler.start();
        try {
            dispatchInbound(message, preDropProven);
        } catch (RuntimeException | LinkageError ex) {
            // LinkageError too (red-team C9): a handler that reaches an engine class this build
            // does not have throws Error, not Exception, and the whole point of this guard is
            // that no single inbound message may take the pump down.
            CoopLog.warn(CoopNetPump.class, "Coop dropped malformed/unexpected message type="
                    + message.type() + " seq=" + message.seq(), ex);
        }
        // Per-type so one expensive handler stands out in the summary rather than hiding inside
        // the aggregate drain cost. Runs on the throwing path too: the catch above swallows.
        profiler.record(SECTION_BY_MESSAGE_TYPE[message.type().ordinal()], dispatchStart);
    }

    private void dispatchInbound(CoopMessages.Message message, boolean preDropProven) {
        // Phase 20.2. During a grace window the session record is deliberately still live, so
        // isGameplaySessionActive() is true and every campaign handler below would happily run — for
        // whoever happens to be on the far end of this socket, which has not yet proved it is the
        // partner we are holding the session for. Until it does, only the messages that CAN prove it
        // are dispatched. Pre-20.2 the teardown made this impossible by making the session inactive;
        // keeping the session is what re-opens the question.
        if (reconnect.active() && !allowedDuringReconnectGrace(message.type())) {
            if (preDropProven && survivesTheDropEdge(message.type())) {
                // net-fix-2: not an unproven peer at all - this one arrived over the connection that
                // died, and is the partner's last word on the campaign. Applied, and logged so the
                // two machines' logs can be lined up over exactly what crossed the edge.
                preDropMessagesApplied++;
                CoopLog.info(CoopNetPump.class, "Coop applying pre-drop type=" + message.type()
                        + " seq=" + message.seq() + " after the reconnect window opened");
            } else {
                if (preDropProven) {
                    preDropMessagesDiscarded++;
                    CoopLog.info(CoopNetPump.class, "Coop discarding pre-drop type=" + message.type()
                            + " seq=" + message.seq()
                            + ": the drop edge already reset what it would have applied to");
                } else if (!graceTrafficDropWarned) {
                    graceTrafficDropWarned = true;
                    CoopLog.warn(CoopNetPump.class, "Coop ignoring type=" + message.type()
                            + " from an unproven peer during the reconnect grace window");
                }
                return;
            }
        }
        switch (message.type()) {
            case LOBBY_HELLO -> handleLobbyHello(message);
            case LOBBY_CHALLENGE -> handleLobbyChallenge(message);
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
            case PING -> {
                // Red-team A4: a connected stranger must not get a free reply channel.
                if (mayAnswerUnprovenPeer(message.type())) {
                    sendPong(message);
                }
            }
            case PONG -> handlePong(message);
            case STALL_NOTICE -> handleStallNotice(message);
            case FLEET_ROSTER_REQUEST -> handleFleetRosterRequest(message);
            case LINK_STATUS -> handleLinkStatus(message);
            case FLEET_ROSTER -> handleFleetRoster(message);
            case SESSION_RESUME_REQUEST -> {
                if (mayAnswerUnprovenPeer(message.type())) {
                    handleSessionResumeRequest(message);
                }
            }
            case READY_STATE -> handleReadyState(message);
            case SESSION_STATS -> handleSessionStats(message);
            case SHIP_LOST -> handleShipLost(message);
            case OPTIONS_SNAPSHOT -> handleOptionsSnapshot(message);
            case OPTIONS_APPLIED -> handleOptionsApplied(message);
            case LOBBY_STATUS -> handleLobbyStatus(message);
            case SESSION_RESUME_ACCEPT -> handleSessionResumeAccept(message);
            case SESSION_RESUME_REJECT -> handleSessionResumeReject(message);
            case STATE_DATAGRAM -> {
                // Same session gate the UDP path gets from its token check: pre-session state must
                // never reach the mirrors, whichever wire carried it.
                if (isGameplaySessionActive()) {
                    ingestStateDatagram(CoopMessages.parseStateDatagram(message));
                }
            }
            default -> {
                // Session-scoped campaign traffic (snapshots, deltas) must not touch the engine or
                // the world ledger unless the full lobby/handshake/seed-lock pipeline has run on
                // THIS connection. The 12b reconnect drill caught a lobby-rejected guest still
                // applying the host's ORBIT_SNAPSHOT stream to what was effectively a solo campaign.
                if (!isGameplaySessionActive()) {
                    if (!preSessionCampaignDropWarned) {
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
        // Phase 20.2: while the grace window is open the guest asks for its session back instead of
        // starting a fresh lobby round; see maybeSendSessionResumeRequest.
        if (reconnect.guestReconnecting()) {
            return;
        }
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
            // The id first: rejectHandshake is what moves the session record, and the marker has to
            // carry the id the two logs are lined up on.
            String correlationId = desyncCorrelationId();
            String reason = "Failed to capture handshake manifest: " + ex.getMessage();
            // Terminal: retrying cannot make an unreadable mod list readable, and a guest that keeps
            // dialling buries its own dialog under a fresh reject every five seconds.
            sessionState.rejectHandshake(reason, true);
            CoopLog.warn(CoopNetPump.class, "Failed to capture coop handshake manifest", ex);
            // Routed like every other handshake reject: marker, feed line, dialog, and terminal on
            // the guest. Without it this was a silent 5 s retry loop against a local failure that
            // retrying cannot fix.
            noteDesync(reason, coop.ui.CoopDesyncReason.Source.HANDSHAKE, correlationId);
        }
    }

    private void handleLobbyHello(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }

        // Phase 20.4: the password gate runs before anything touches the session record, so a wrong
        // guess never takes the guest slot and never has to be rolled back out of it. It also runs
        // before the reconnect-grace branch below, which is the whole reason that branch can trust
        // a hello at all: an unproven stranger is challenged or rejected here and never reaches it.
        if (!checkLobbyPassword(message)) {
            return;
        }

        // Phase 20.2 + the Phase 21 live smoke. A hello arriving mid-grace used to be answered
        // "session in reconnect grace" on the theory that a returning guest should retry rather than
        // be adopted. It cannot retry its way in: a relaunched guest holds neither the session id nor
        // the player id a SESSION_RESUME_REQUEST is matched on (the id is minted per session at lobby
        // accept, and its save predates the held one), so LOBBY_HELLO is the only thing it can say.
        // With the dialog's "wait longer" option in the host player's hands, that was a loop with no
        // exit: the partner knocked every 20 s and was refused every 20 s while the host extended the
        // wait. So an authenticated hello ends the wait instead, and this frame goes on to serve it.
        if (reconnect.hostWaiting()) {
            endHostWaitForReturningPartner();
        }

        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopPlayerInfo guest = new CoopPlayerInfo(
                payload.requiredString("playerId"),
                payload.requiredString("playerName"));

        if (!sessionState.canAcceptGuest()) {
            String reason = sessionState.rejectReasonForGuest(guest);
            CoopMessages.Message reject = CoopMessages.lobbyReject(
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    reason);
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            return;
        }

        // Defence in depth for the same finding: whatever put a second guest row in this roster,
        // the peer being admitted is the only guest this build can have, so anybody else's row is
        // stale by definition and must not be left to block the Start gate.
        forgetDepartedGuestLobbyRows(guest.playerId(),
                "a new guest was accepted into the single v1 guest slot");
        sessionState.hostAcceptGuest(guest);
        CoopMessages.Message accept = CoopMessages.lobbyAccept(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionState.provisionalLobbyId(),
                sessionState.localPlayerInfo());
        service.sendTo(message.senderId(), accept);
        log("outbound", accept);
        CoopLog.info(CoopNetPump.class,
                "Coop lobby accepted provisionalLobbyId=" + sessionState.provisionalLobbyId()
                        + " hostPlayerId=" + sessionState.localPlayerId()
                        + " guestPlayerId=" + sessionState.remotePlayerId());
    }

    /**
     * Ends a host grace window because the partner came back through a relaunch (Phase 21 live
     * smoke). Called from {@link #handleLobbyHello} only, and only for a hello that has already
     * cleared {@link #checkLobbyPassword}.
     *
     * <p><b>The teardown is the grace expiry's, deliberately.</b> This runs
     * {@code reconnect.end(...)}, which is the same call {@link CoopReconnectCoordinator#tick} makes
     * when the window runs out, so it reaches the same {@link ReconnectListener#onEnded}: the
     * reconnect hold is released and the dialog closed through the arbiter, the datagram token is
     * cleared, {@link #peerDroppedAfterLiveSession} is reset, and {@code endSessionAfterDrop()}
     * rewinds the lobby to {@code HOST_WAITING} with the guest slot free — which is exactly what
     * the fall-through in the caller needs before it can accept this hello. Building a second,
     * narrower teardown here is how half-torn state gets shipped.
     *
     * <p><b>What it does not inherit.</b> {@code onEnded} branches on the reason so this path writes
     * no {@code [COOP-DOCTOR]} marker and raises no desync dialog: a partner walking back in is not
     * a desync, and a modal explaining a session failure over a lobby that is about to open is the
     * dialog loop Phase 21 spent its red-team budget removing.
     *
     * <p><b>Ordering.</b> The log line and the feed line come first, while the window still knows how
     * much time was left and the session record still knows the id and the partner's name; both are
     * gone the instant {@code end} returns. The pause is untouched on purpose: the reconnect hold is
     * released inside {@code onEnded} and {@link #maybeHoldPausedUntilSessionReady()} takes the same
     * clock straight back over as the no-session hold later in this frame, with
     * {@link #coopHoldPaused} still recording the pause as coop's own (F2).
     */
    private void endHostWaitForReturningPartner() {
        long now = clockMillis.getAsLong();
        int secondsLeft = reconnect.remainingSeconds(now);
        String heldSession = sessionState.sessionId();
        CoopLog.info(CoopNetPump.class, "Coop reconnect wait ended: "
                + CoopReconnectCoordinator.REASON_PARTNER_RELAUNCHED + "; opening the lobby ("
                + secondsLeft + " s were left on session " + heldSession + ")");
        postFeed(FEED_RECONNECT_RELAUNCHED, now, "Co-op: " + remoteDisplayName()
                + " is back after a relaunch - starting a fresh lobby round.", FEED_GOOD_COLOR);
        reconnect.end(CoopReconnectCoordinator.REASON_PARTNER_RELAUNCHED);
    }

    // ---- Phase 20.4: optional lobby password -----------------------------------------------------

    /**
     * Host side of the password gate. Returns true when the hello may proceed to the accept path.
     *
     * <p>The flow exists in this shape because {@code LOBBY_HELLO} is the <em>first</em> message on a
     * fresh connection: there is no host-issued id yet for a proof to be bound to, so the proof
     * cannot ride the first hello. The host answers a challenge carrying a fresh nonce instead,
     * without touching {@link CoopSessionState} — no slot is taken, so an attacker spraying guesses
     * cannot occupy the session while it guesses — and the guest sends a <em>second</em> hello with
     * {@code SHA-256(password + nonce)}. A wrong proof gets the ordinary {@code LOBBY_REJECT} and the
     * connection is dropped, which puts the guesser back at the transport's rate limiter.
     *
     * <p>With no password configured this method returns true immediately and the wire is
     * byte-identical to the pre-20.4 build: no challenge, no extra field, no extra round trip.
     */
    private boolean checkLobbyPassword(CoopMessages.Message message) {
        if (lobbyPassword.isEmpty()) {
            return true;
        }
        // Red-team A3: a throttled address gets no challenge and no reject. Dropping the connection
        // on a wrong guess frees the slot, and the transport's connection throttle is only consulted
        // when no slot is free - so without this, guessing was limited by nothing but reconnect rate.
        java.net.InetAddress peer = service.activePeerAddress();
        if (service.isProofThrottled(peer)) {
            return false;
        }
        String proof = CoopMessages.parseLobbyProof(message);
        // Only the FIRST hello of a connection reads as "no proof yet"; the pending nonce is what
        // distinguishes it. Without that guard a guest launched with no password answers every
        // challenge with an empty proof and gets challenged again, one round per frame, until the
        // 15 s handshake deadline drops the socket and its retry loop starts the same ping-pong over
        // - the LOBBY_REJECT_PASSWORD that names the cause never fires. Same shape as
        // checkResumePassword. The nonce is cleared on the disconnect edge, so a fresh connection
        // still gets its own first challenge.
        if (proof.isEmpty() && pendingLobbyNonce == null) {
            issueLobbyChallenge(message.senderId());
            return false;
        }
        if (verifyPasswordProof(proof)) {
            return true;
        }
        service.noteFailedProof(peer);

        CoopMessages.Message reject = CoopMessages.lobbyReject(
                service.nextSeq(), clockMillis.getAsLong(), LOBBY_REJECT_PASSWORD);
        service.sendTo(message.senderId(), reject);
        log("outbound", reject);
        // Push the reject onto the wire before the socket goes, then close: leaving the connection
        // open would let one guesser hold the single v1 slot open indefinitely.
        service.flushOutbound();
        service.dropActiveConnection(LOBBY_REJECT_PASSWORD);
        // DEBUG, not WARN (red-team A3): this line is one per guess, i.e. written by the attacker.
        // The once-per-cooldown WARN in CoopNetService is the one a log reader wants.
        CoopLog.debug(CoopNetPump.class, "Coop lobby rejected a guest with the wrong password"
                + " and closed the connection");
        return false;
    }

    /**
     * Host side of the password gate on the <em>resume</em> path (red-team A2).
     *
     * <p>A {@code SESSION_RESUME_REQUEST} carries a session id and a player id, both of which
     * travelled in cleartext on the connection that just died. Without this, a captured request
     * replays: an attacker who watched the lobby can wait for a blip and take the session, and on a
     * password-protected host the password never enters the exchange at all. It is the same
     * challenge/nonce/proof round {@code LOBBY_HELLO} already runs, deliberately reusing
     * {@code LOBBY_CHALLENGE} rather than inventing a second protocol.
     *
     * <p>A host with no password configured returns true immediately and the resume exchange is
     * byte-identical to the pre-fix build.
     *
     * @return true when the request may proceed to the coordinator's identity check
     */
    private boolean checkResumePassword(CoopMessages.Message message) {
        if (lobbyPassword.isEmpty()) {
            return true;
        }
        java.net.InetAddress peer = service.activePeerAddress();
        if (service.isProofThrottled(peer)) {
            // Silently: the transport logs once per cooldown, and answering at all is the resource
            // a guesser is after.
            return false;
        }
        String proof = CoopMessages.parseResumeProof(message);
        if (proof.isEmpty() && pendingLobbyNonce == null) {
            issueLobbyChallenge(message.senderId());
            return false;
        }
        if (verifyPasswordProof(proof)) {
            return true;
        }
        service.noteFailedProof(peer);
        CoopMessages.Message reject = CoopMessages.sessionResumeReject(
                sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                LOBBY_REJECT_PASSWORD);
        service.sendTo(message.senderId(), reject);
        log("outbound", reject);
        service.flushOutbound();
        service.dropActiveConnection(LOBBY_REJECT_PASSWORD);
        // Marker only, and deliberately after the drop: whoever failed the proof is not necessarily
        // the partner, and the host's grace window is still running for the one that is.
        noteDesyncMarkerOnly(LOBBY_REJECT_PASSWORD, coop.ui.CoopDesyncReason.Source.SESSION_RESUME,
                desyncCorrelationId());
        return false;
    }

    /** Mints a nonce and sends the challenge; shared by the hello and resume gates. */
    private void issueLobbyChallenge(String senderId) {
        pendingLobbyNonce = newLobbyNonce();
        CoopMessages.Message challenge = CoopMessages.lobbyChallenge(
                service.nextSeq(), clockMillis.getAsLong(), pendingLobbyNonce);
        service.sendTo(senderId, challenge);
        log("outbound", challenge);
    }

    /**
     * Constant-time proof check against the nonce this host issued, consuming it either way. A proof
     * arriving with no outstanding challenge is a replay of somebody else's round and fails.
     */
    private boolean verifyPasswordProof(String proof) {
        String expected = CoopMessages.passwordProof(lobbyPassword,
                pendingLobbyNonce == null ? "" : pendingLobbyNonce);
        boolean accepted = pendingLobbyNonce != null && java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                proof.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        pendingLobbyNonce = null;
        return accepted;
    }

    /**
     * Guest side: answer the challenge with a second hello — or, while reconnecting, a second resume
     * request (red-team A2), because that is the message the host is waiting for and a hello during
     * a grace window is answered "session in reconnect grace". A guest with no password configured
     * answers with an empty proof rather than going silent — the reject that comes back is what tells
     * the player why, and silence would look like a hung connection.
     */
    private void handleLobbyChallenge(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        lobbyChallengeSeen = true;
        String nonce = CoopMessages.parseLobbyChallengeNonce(message);
        if (reconnect.guestReconnecting()) {
            answerResumeChallenge(nonce);
            return;
        }
        if (lobbyPassword.isEmpty()) {
            CoopLog.warn(CoopNetPump.class, "Coop host asked for a lobby password and none is"
                    + " configured here; launch with -D" + CoopNetStartupConfig.PASSWORD_PROPERTY
                    + "=<the host's password>. This connection will be rejected.");
        }
        CoopMessages.Message hello = CoopMessages.lobbyHello(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionState.localPlayerInfo(),
                lobbyPassword.isEmpty() ? "" : CoopMessages.passwordProof(lobbyPassword, nonce));
        service.send(hello);
        log("outbound", hello);
    }

    /** The proof-carrying second {@code SESSION_RESUME_REQUEST}; see {@link #checkResumePassword}. */
    private void answerResumeChallenge(String nonce) {
        String sessionId = sessionState.sessionId();
        String playerId = sessionState.localPlayerId();
        if (sessionId == null || playerId == null) {
            reconnect.end("local session identity was lost");
            return;
        }
        if (lobbyPassword.isEmpty()) {
            CoopLog.warn(CoopNetPump.class, "Coop host asked for a lobby password to resume and none"
                    + " is configured here; launch with -D" + CoopNetStartupConfig.PASSWORD_PROPERTY
                    + "=<the host's password>. This resume will be rejected.");
        }
        CoopMessages.Message request = CoopMessages.sessionResumeRequest(
                sessionId, service.nextSeq(), clockMillis.getAsLong(), playerId,
                lobbyPassword.isEmpty() ? "" : CoopMessages.passwordProof(lobbyPassword, nonce));
        service.send(request);
        log("outbound", request);
    }

    private String newLobbyNonce() {
        byte[] bytes = new byte[8];
        lobbyNonceSource.nextBytes(bytes);
        StringBuilder nonce = new StringBuilder(16);
        for (byte b : bytes) {
            nonce.append(Character.forDigit((b >>> 4) & 0x0f, 16));
            nonce.append(Character.forDigit(b & 0x0f, 16));
        }
        return nonce.toString();
    }

    /** Launch-time read, defaulting to "no password" if the property stack is unusable. */
    private static String readLobbyPassword() {
        try {
            return CoopNetStartupConfig.passwordFromSystemProperties();
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Unusable "
                    + CoopNetStartupConfig.PASSWORD_PROPERTY + "; hosting without a password", ex);
            return "";
        }
    }

    /** Test seam: the password is a launch property everywhere else. */
    void setLobbyPasswordForTest(String password) {
        this.lobbyPassword = password == null ? "" : password.trim();
    }

    /** Test seam: the save's persistent data everywhere else. See {@link #adoptStableLocalPlayerId()}. */
    void setLocalPlayerIdStoreForTest(Supplier<String> reader, Consumer<String> writer) {
        this.storedLocalPlayerIdSupplier = reader == null ? () -> "" : reader;
        this.localPlayerIdStore = writer == null ? id -> { } : writer;
    }

    private void handleLobbyAccept(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        if (!lobbyPassword.isEmpty() && !lobbyChallengeSeen && !lobbyPasswordUnusedWarned) {
            lobbyPasswordUnusedWarned = true;
            CoopLog.warn(CoopNetPump.class, "Coop host did not ask for a password; the one configured"
                    + " here was not used. The session is unprotected on the host's port.");
        }

        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopPlayerInfo host = new CoopPlayerInfo(
                payload.requiredString("hostPlayerId"),
                payload.requiredString("hostName"));
        sessionState.guestAcceptLobby(
                payload.requiredString("provisionalLobbyId"),
                host);
        CoopLog.info(CoopNetPump.class,
                "Coop lobby connected provisionalLobbyId=" + sessionState.provisionalLobbyId()
                        + " hostPlayerId=" + sessionState.remotePlayerId()
                        + " guestPlayerId=" + sessionState.localPlayerId());
    }

    /**
     * Guest side of {@code LOBBY_REJECT}, in two flavours (F4).
     *
     * <p>A <b>password</b> reject is terminal for this launch: the password is a JVM property read at
     * startup, so no amount of retrying can turn it into an accept, and every retry costs the host a
     * connection slot and pushes its proof throttle further out (three failures buy a 30 s silent
     * refusal, doubling to 10 minutes). The connect loop stops, one WARN says what to do about it,
     * and the reason reaches the HUD line and the campaign feed — before this, the only symptom a
     * player saw was a guest that never connected and never said why.
     *
     * <p>Every other reason — "Lobby already has a guest", a seed or handshake mismatch, the grace
     * window's {@link CoopReconnectCoordinator#LOBBY_REJECT_IN_GRACE} — is retryable, because the
     * host's answer can change without either side relaunching. Those keep the retry loop but back it
     * off to 5 s; a plain TCP drop with no reject still retries at 500 ms.
     *
     * <p>{@code LOBBY_REJECT_IN_GRACE} is no longer sent by a host on this build — an authenticated
     * hello ends the wait instead of being turned away — but it stays retryable here on purpose. It
     * arrives before the handshake that would have caught a version skew, so a host on an older build
     * is exactly the peer that still sends it, and the correct answer to it has always been "ask
     * again in 5 s": that host's window does expire on its own. The related v1 limitation lives on
     * the host side and cannot be fixed from here: a relaunched process mints a new player id, so the
     * host cannot tell the returning partner from a different guest who knows the password, and on a
     * passwordless host it cannot tell it from anyone at all.
     */
    private void handleLobbyReject(CoopMessages.Message message) {
        // Parse once: the second parse used to run after guestRejectLobby had already changed state,
        // so a malformed payload threw from the log line rather than the state transition.
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        if (service.role() != CoopConnectionRole.GUEST) {
            CoopLog.warn(CoopNetPump.class, "Coop lobby rejected: " + reason);
            return;
        }
        if (LOBBY_REJECT_PASSWORD.equals(reason)) {
            sessionState.guestRejectLobby(LOBBY_REJECT_PASSWORD_HELP, true);
            service.stopReconnecting(LOBBY_REJECT_PASSWORD);
            CoopLog.warn(CoopNetPump.class, "Coop lobby rejected: the host's lobby password did not"
                    + " match. Not retrying; relaunch this client with -D"
                    + CoopNetStartupConfig.PASSWORD_PROPERTY + "=<the host's password>.");
            coop.ui.CoopFeed.post("Co-op: wrong lobby password - relaunch with the host's password.",
                    FEED_WARN_COLOR);
            intelFeed.noteEvent("lobby rejected: " + LOBBY_REJECT_PASSWORD_HELP);
            return;
        }
        sessionState.guestRejectLobby(reason);
        if (!reconnect.guestReconnecting()) {
            service.noteLobbyRejected();
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
            // Read the correlation id first: rejectHandshake clears the canonical session. There is no
            // session id at this point anyway (hostAcceptHandshake below is what mints it), so this is
            // the provisional lobby id, which the guest holds under the same value.
            String correlationId = desyncCorrelationId();
            // Not terminal on the host: the host stays in its lobby and a guest that fixes its mod
            // list must be able to come back without the host relaunching.
            sessionState.rejectHandshake(diff);
            CoopMessages.Message reject = CoopMessages.handshakeResultReject(
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    diff);
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop handshake rejected:\n" + diff);
            noteDesync(diff, coop.ui.CoopDesyncReason.Source.HANDSHAKE, correlationId);
            return;
        }

        String sessionId = sessionState.hostAcceptHandshake();
        // The transport drops every datagram until it knows what this session's token looks like, so
        // it has to learn it at the same instant the session id exists — not a frame later.
        service.setExpectedSessionToken(CoopMessages.wireToken(sessionId));
        CoopMessages.Message accept = CoopMessages.handshakeResultAccept(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionId);
        service.sendTo(message.senderId(), accept);
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
            String reason = "seedLock: " + ex.getMessage();
            String correlationId = desyncCorrelationId();
            // Read before the reject clears the canonical session out from under it.
            String rejectedSessionId = sessionState.sessionId();
            sessionState.rejectHandshake(reason);
            CoopLog.warn(CoopNetPump.class, "Failed to create coop seed lock request", ex);
            // Tell the guest and close the socket. Every other reject site does; this one used to
            // reject locally and go quiet, and because hostAcceptHandshake had already set the
            // expected session token the handshake deadline no longer applied - the guest sat
            // waiting for a SEED_LOCK_REQUEST that would never come, on a connection nothing would
            // ever drop, while the host's lobby stayed REJECTED and admitted nobody else.
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    rejectedSessionId,
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    reason);
            service.send(reject);
            log("outbound", reject);
            service.flushOutbound();
            service.setExpectedSessionToken(null);
            service.dropActiveConnection(reason);
            noteDesync(reason, coop.ui.CoopDesyncReason.Source.SEED_LOCK, correlationId);
        }
    }

    /**
     * The campaign's identity for the seed lock (Phase 6b): the stored id when the campaign has one,
     * otherwise minted once and stored. The same id then rides every {@code SEED_LOCK_REQUEST} for
     * the life of the campaign, across sessions and saves — it is what distinguishes "this campaign,
     * resumed" from "a fresh re-roll of the same seed", which the seed string and structural
     * fingerprint cannot (both are pure functions of the seed).
     *
     * <p><b>Minted here, written to the save only when a guest acks it.</b> Storing at request time
     * made a failed birth lock permanent: the guest that mistyped its seed was rejected, the id it
     * never accepted stayed in the host's persistent data, and every later request carried
     * {@code minted=false} — so the corrected fresh guest was turned away as joining a campaign
     * "already in flight" that had never run one, recoverable only with {@code -AdoptCampaign}. The
     * id is held in this field meanwhile, so retries inside the same process reuse it and still
     * present as the birth lock.
     */
    private CampaignIdResolution resolveHostCampaignId() {
        String stored = storedCampaignIdSupplier.get();
        if (stored != null && !stored.trim().isEmpty()) {
            return new CampaignIdResolution(stored.trim(), false);
        }
        if (mintedCampaignId == null) {
            mintedCampaignId = UUID.randomUUID().toString();
            CoopLog.info(CoopNetPump.class, "Coop campaign id minted campaignId=" + mintedCampaignId);
        }
        return new CampaignIdResolution(mintedCampaignId, true);
    }

    /** Persists a minted campaign id once the guest has accepted the lock it rode on. */
    private void commitMintedCampaignId() {
        String minted = mintedCampaignId;
        if (minted == null) {
            return;
        }
        mintedCampaignId = null;
        campaignIdStore.accept(minted);
        CoopLog.info(CoopNetPump.class, "Coop campaign id confirmed by the guest campaignId=" + minted);
    }

    /** {@code minted} = the id was created at this very seed lock, i.e. the campaign is being born. */
    private record CampaignIdResolution(String id, boolean minted) {
    }

    /** Host: a campaign id minted this process and not yet accepted by any guest. */
    private String mintedCampaignId;

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
            CoopMessages.Payload payload = CoopMessages.payload(message);
            boolean guestIronMode = Boolean.parseBoolean(payload.requiredString("ironMode"));
            if (hostIronMode) {
                return "ironMode: host=true";
            }
            if (guestIronMode) {
                return "ironMode: guest=true";
            }

            CoopHandshakeManifest hostManifest = manifestSupplier.get();
            CoopHandshakeManifest guestManifest = CoopHandshakeManifest.fromJson(
                    payload.requiredString("manifestJson"));
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

        CoopMessages.Payload payload = CoopMessages.payload(message);
        boolean accepted = Boolean.parseBoolean(payload.requiredString("accepted"));
        if (accepted) {
            String sessionId = payload.requiredString("sessionId");
            sessionState.guestAcceptHandshake(sessionId);
            service.setExpectedSessionToken(CoopMessages.wireToken(sessionId));
            CoopLog.info(CoopNetPump.class, "Coop handshake accepted sessionId=" + sessionId);
            return;
        }

        String diff = payload.requiredString("diff");
        String correlationId = desyncCorrelationId();
        // Terminal on the guest: a mod list is fixed by relaunching, so retrying every 5 s can only
        // earn the identical reject and bury the dialog that explains it.
        sessionState.rejectHandshake(diff, true);
        CoopLog.warn(CoopNetPump.class, "Coop handshake rejected:\n" + diff);
        noteDesync(diff, coop.ui.CoopDesyncReason.Source.HANDSHAKE, correlationId);
    }

    private void handleSeedLockRequest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !sessionState.handshakeValidated()) {
            return;
        }

        CoopMessages.Payload payload = CoopMessages.payload(message);
        long seedLong = payload.requiredLong("seedLong");
        String seedString = payload.requiredString("seedString");
        String hostFingerprint = payload.requiredString("sectorFingerprint");
        String hostCampaignId = payload.requiredString("campaignId");
        boolean hostCampaignIdMinted = Boolean.parseBoolean(
                payload.requiredString("campaignIdMinted"));
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
            // The correlation id first, and it is the session id here: the handshake has already
            // validated by the time a seed lock runs, so both machines hold the same minted id.
            String correlationId = desyncCorrelationId();
            // Terminal: a different seed is fixed by rolling a new campaign or loading the
            // coordinated save, never by reconnecting.
            sessionState.rejectHandshake(seedMismatch, true);
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    message.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    seedMismatch);
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + seedMismatch);
            noteDesync(seedMismatch, coop.ui.CoopDesyncReason.Source.SEED_LOCK, correlationId);
            return;
        }

        String mismatch = CoopSeedSync.fingerprintMismatch(hostFingerprint, guestFingerprint);
        if (!mismatch.isEmpty()) {
            String correlationId = desyncCorrelationId();
            sessionState.rejectHandshake(mismatch, true);
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    message.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    mismatch);
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + mismatch);
            dumpCanonicalFingerprint("guest fingerprint comparison failed");
            noteDesync(mismatch, coop.ui.CoopDesyncReason.Source.SEED_LOCK, correlationId);
            return;
        }

        sessionState.recordSeedLock(seedLong, seedString, hostFingerprint);
        // A new session on the same guest process gets a clean checkpoint history. The host's
        // CoopSaveCheckpoint is rebuilt with every game load and numbers from 1 again, while this
        // guest's lives for the life of the pump: if the last id it handled in the previous session
        // was 1, the new session's first SAVE_CHECKPOINT read as "already handled" and the guest
        // silently skipped the coordinated autosave that keeps the two saves in step.
        saveCheckpoint.reset();
        CoopSeedSync.storeCurrentSectorPersistentData(new CoopSeedSync.SeedData(seedLong, seedString, hostFingerprint));
        CoopMessages.Message ack = CoopMessages.seedLockAck(
                message.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                guestFingerprint);
        service.sendTo(message.senderId(), ack);
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
                consumeAdoptCampaignIdConsent();
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
            consumeAdoptCampaignIdConsent();
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

    /**
     * Burns the one-shot adopt consent, at the point it was actually acted on and nowhere else
     * (net-fix-10). A lock that was rejected, or aborted before the adoption branch, must leave the
     * gesture intact - the player asked for one adoption, not for a process-lifetime licence.
     */
    private void consumeAdoptCampaignIdConsent() {
        try {
            adoptCampaignIdConsumer.run();
            CoopLog.info(CoopNetPump.class, "Coop consumed the -D" + ADOPT_CAMPAIGN_ID_PROPERTY
                    + " consent; a further save loaded in this process will not adopt silently");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Coop could not consume the -D"
                    + ADOPT_CAMPAIGN_ID_PROPERTY + " consent", ex);
        }
    }

    private boolean rejectCampaignId(CoopMessages.Message message, String reason) {
        String correlationId = desyncCorrelationId();
        // Terminal, and the reason text already names the two remedies (a fresh campaign from the
        // host's seed, or -DcoopAdoptCampaignId); both need a relaunch, so retrying cannot help.
        sessionState.rejectHandshake(reason, true);
        CoopMessages.Message reject = CoopMessages.seedLockReject(
                message.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                reason);
        service.sendTo(message.senderId(), reject);
        log("outbound", reject);
        CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + reason);
        noteDesync(reason, coop.ui.CoopDesyncReason.Source.SEED_LOCK, correlationId);
        return false;
    }

    private void handleSeedLockAck(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST || !sessionState.handshakeValidated()) {
            return;
        }

        String guestFingerprint = CoopMessages.requiredPayloadString(message, "sectorFingerprint");
        String mismatch = CoopSeedSync.fingerprintMismatch(sessionState.sectorFingerprint(), guestFingerprint);
        if (!mismatch.isEmpty()) {
            String correlationId = desyncCorrelationId();
            // Host side, so not terminal: the host rewinds to HOST_WAITING and keeps its lobby open.
            // The dialog still shows, because otherwise the host learns nothing about why the guest
            // it was waiting for disappeared.
            sessionState.rejectHandshake(mismatch);
            CoopMessages.Message reject = CoopMessages.seedLockReject(
                    message.sessionId(),
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    mismatch);
            service.sendTo(message.senderId(), reject);
            log("outbound", reject);
            CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected after guest ACK: " + mismatch);
            dumpCanonicalFingerprint("host rejecting after guest ack");
            noteDesync(mismatch, coop.ui.CoopDesyncReason.Source.SEED_LOCK, correlationId);
            return;
        }

        // The one unambiguous record that the guest reached SEED_LOCKED; read by the grace
        // eligibility rule in detectPeerDisconnect and cleared with the session in
        // endSessionAfterDrop. Not cleared on a drop: a session held through a grace window is still
        // a session whose guest passed the seed lock.
        guestSeedLockAcked = true;
        // The campaign's birth is only real now that somebody else has accepted the id.
        commitMintedCampaignId();
        CoopLog.info(CoopNetPump.class,
                "Coop seed lock accepted by guest sectorFingerprint=" + guestFingerprint);
    }

    private void handleSeedLockReject(CoopMessages.Message message) {
        // Same state gate the request and ack handlers carry. Without it this was the one seed-lock
        // type any connected peer could send before saying hello: a scanner or an old build speaking
        // the protocol flipped a waiting host to REJECTED, earned it a doctor marker and a modal
        // dialog about a session that never existed, and held the single lobby slot until its socket
        // closed.
        if (!sessionState.handshakeValidated()) {
            CoopLog.warn(CoopNetPump.class, "Coop ignoring a SEED_LOCK_REJECT that arrived before a"
                    + " validated handshake");
            return;
        }
        String reason = CoopMessages.requiredPayloadString(message, "reason");
        String correlationId = desyncCorrelationId();
        // Reached by both roles (the guest rejects the host's request, the host rejects the guest's
        // ack), and terminal only on the guest — same asymmetry as every other seed site.
        sessionState.rejectHandshake(reason, service.role() == CoopConnectionRole.GUEST);
        CoopLog.warn(CoopNetPump.class, "Coop seed lock rejected: " + reason);
        // Both sides dump on a fingerprint reject so the two logs can be diffed entry-for-entry.
        if (reason.contains("sectorFingerprint")) {
            dumpCanonicalFingerprint("received seed-lock reject");
        }
        noteDesync(reason, coop.ui.CoopDesyncReason.Source.SEED_LOCK, correlationId);
    }

    private void handleTimeSnapshot(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        latestTimeSnapshot = CoopTimeLock.fromMessage(message);
    }

    /**
     * <b>Phase 20 M6 latency audit — the hold has no deadline, and that is the design.</b> The one
     * failure a slow handshake could produce here would be a timeout that gave up on the hold and let
     * the host's clock run while the guest was still negotiating; at 200 ms RTT with 2% loss the
     * lobby/handshake/seed-lock exchange is several round trips of TCP with retransmits, so any fixed
     * budget would eventually be the thing that broke. There is no such budget: the predicate is
     * purely {@code role != NONE && !isGameplaySessionActive()}, evaluated every frame, and the only
     * exit is the session actually becoming active. A handshake that never completes leaves the host
     * paused forever, which is the correct outcome — there is no public clock setter and no drivable
     * fast-advance, so campaign time the guest missed cannot be given back.
     *
     * <p><b>The counterpart risk — the hold outliving its reason — is what the live QA caught
     * (finding F2).</b> The old comment claimed the hold could not become a permanent pause intent
     * because {@link #syncHostSharedPause()} seeds {@code hostPauseIntent} from whatever it observes
     * on the first active frame. That seeding was the bug: the thing it observed was <em>this
     * method's own {@code setPaused(true)}</em>, so the moment the session went live the hold was
     * promoted to "the host player pressed pause" and nothing but a key press could clear it. On a
     * fresh start that merely cost the host one keystroke; after a reconnect-grace expiry and a
     * lobby rejoin it stranded a live session paused with no player able to say why.
     *
     * <p>So the hold is now <em>owned</em>: {@link #coopHoldPaused} records that this pump, not the
     * player, put the clock where it is, and the release in {@code syncHostSharedPause} both skips
     * the intent seeding and lets {@code effectivePaused} unpause the sector. A pause the pump did
     * not apply is never claimed and therefore never released — a host who paused before hosting, or
     * a vanilla auto-pause, still owns its own pause across the whole hold.
     *
     * <p><b>Guest half (finding F3).</b> Solo play is not supported (mod enabled = coop rules), and a
     * guest between sessions was the one client still free-running: during the host's 95 s hold the
     * guest's clock ran 1.4 game-days ahead and the Phase 7c reconciler had to slew it back after the
     * rejoin. It holds on the same terms now, with one exemption that is not negotiable: never force
     * {@code setPaused} while an interaction dialog is open. That is the memory rule behind the
     * blank-options trade-tab freeze, and it covers the reconnect dialog too — that dialog is itself
     * an interaction dialog, so vanilla is already pausing the game while it is up.
     */
    private void maybeHoldPausedUntilSessionReady() {
        // Hold the local clock from the moment this client takes a coop role until the session is
        // fully established (peer connected + handshake validated + seed lock done). Otherwise time
        // advances during the multi-second connect and the two campaigns start days apart; there is
        // no public clock-setter or drivable fast-advance to let either side catch up afterwards, so
        // we prevent the gap instead of closing it. Once the session is active the hold is released
        // and the host's normal pause/unpause mirrors to the guest.
        CoopConnectionRole role = service.role();
        // Phase 21: isSessionPlayable(), not isGameplaySessionActive(). The lobby sits between "the
        // session exists" and "the players agreed to start it", and the hold is what keeps the world
        // still for it. Everything else - state streams, BASE_SET, fleet mirrors, TIME_SNAPSHOT -
        // keeps its old predicate on purpose: that traffic is how the guest reaches the phase where
        // it is allowed to press Ready at all.
        if (role == CoopConnectionRole.NONE || isSessionPlayable()) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            if (role == CoopConnectionRole.GUEST && isGuestInteractionDialogOpen()) {
                // Vanilla owns the clock while a dialog is up and pauses it anyway; forcing pause
                // underneath one is what froze the guest's station dialog with no options.
                return;
            }
            if (sector.isPaused()) {
                // Either our own hold from an earlier frame, or somebody else's pause. Both mean
                // there is nothing to do; the ownership flag already says which it is.
                return;
            }
            sector.setPaused(true);
            if (!coopHoldPaused) {
                coopHoldPaused = true;
                CoopLog.info(CoopNetPump.class, "Coop " + role
                        + " holding the campaign paused: no gameplay session is active");
            }
        } catch (RuntimeException | LinkageError ex) {
            // No active sector yet (e.g. still on a menu); nothing to pause.
        }
    }

    /**
     * The other half of {@link #maybeHoldPausedUntilSessionReady()}: the session is live, so the
     * pump's own hold is over. Clears the ownership flag and reports whether this frame is the one
     * that ended it, which is what tells the host's shared-pause seeding not to mistake the hold for
     * a pause the player asked for.
     *
     * <p>Deliberately does not touch the clock itself. On the host the release is
     * {@code setPaused(effectivePaused)} in {@link #syncHostSharedPause()}, which is the one place
     * allowed to drive that clock; on the guest it is the host's {@code TIME_SNAPSHOT}, re-applied
     * every frame by {@link #maybeApplyTimeSnapshot()}. Unpausing the guest here instead would let it
     * run for the 0-200 ms before the first snapshot lands — the exact free-running the guest hold
     * exists to stop.
     */
    private boolean consumeCoopHoldRelease() {
        if (!coopHoldPaused || pauseCoordinator.reconnectHold()) {
            // A reconnect window is the other reason coop holds the clock, and on the host the
            // session stays active right through it — so "the session is live" is not on its own
            // enough to say the hold is over.
            return false;
        }
        coopHoldPaused = false;
        CoopLog.info(CoopNetPump.class, "Coop " + service.role()
                + " releasing its pause hold: the gameplay session is active");
        return true;
    }

    /**
     * Claims the pause a reconnect window is about to put on the clock (Phase 20 live QA, F2). The
     * window's hold is coop's, exactly like the no-session hold, and it has to be recorded as such
     * <em>before</em> it is applied: after an expiry the sector is left paused by it, the no-session
     * hold then finds the clock already stopped and applies nothing, and without this claim nothing
     * would remember whose pause it is. That is the live path that stranded a rejoined session paused
     * with {@code hostIntent=true} and no player able to say why.
     *
     * <p>A clock that is <em>already</em> paused when the window opens is deliberately not claimed:
     * that pause belongs to the player (or to vanilla), and coop must not release what it did not
     * take.
     */
    private void claimCoopPauseHoldForReconnect() {
        if (coopHoldPaused) {
            return;
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.isPaused()) {
                return;
            }
            coopHoldPaused = true;
        } catch (RuntimeException | LinkageError ex) {
            // No sector to hold; the window still runs, there is just no clock to own.
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
        // Phase 21 gate: while the lobby is open the shared-pause machinery stays parked. The clock
        // is held by the owned hold above, and running the intent exchange here would let the guest's
        // own lobby dialog (a blocking screen) publish a PAUSE_INTENT for a world nobody is playing
        // yet. resetSharedPauseState() is safe to reach every lobby frame: it early-returns as soon
        // as there is nothing to clear, and it sends nothing in any case.
        if (!isSessionPlayable()) {
            resetSharedPauseState();
            return;
        }
        if (service.role() == CoopConnectionRole.HOST) {
            syncHostSharedPause();
        } else if (service.role() == CoopConnectionRole.GUEST) {
            // The guest's clock is the host snapshot's to drive; all the release does here is stop
            // this pump from re-asserting the hold (see consumeCoopHoldRelease).
            consumeCoopHoldRelease();
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
            // Phase 20 live QA (F2): the clock may still be paused by the pump's own no-session hold,
            // and that pause belongs to coop, not to the host player. Consumed here, on the first
            // active frame, because this is the only place that decides what the pause means.
            boolean releasedCoopHold = consumeCoopHoldRelease();
            if (!hostSharedPauseInitialized) {
                // First active frame: seed the host's intent from the current clock state rather than
                // treating it as an edge — unless what we are looking at is our own hold, in which
                // case seeding it would turn "waiting for the guest" into a permanent host pause that
                // only a key press could clear. Anything the player actually asserted during the hold
                // (a pre-hold pause, a vanilla auto-pause) is already in hostPauseIntent or is a
                // pause the hold never claimed, so it survives either way.
                //
                // coopHoldPaused is read as well as the release, because the release can be deferred:
                // consumeCoopHoldRelease refuses while a reconnect window still owns the clock, and on
                // that frame the pause on screen is just as much coop's as it is on the frame the hold
                // ends (Phase 21 red-team blocker 1).
                //
                // By design, a pause the *host player* pressed while the lobby was up is dropped on
                // the release frame. The pause key is consumed by CoopHostPauseInputListener and
                // routed into a shared-pause path that does not run until the session is playable, so
                // there is nothing left to seed from; the host presses it again on a live world. The
                // alternative -- seeding true -- is the F2 latch, and a session stuck paused with no
                // player able to say why is far worse than a lost keystroke.
                pauseCoordinator.setHostPauseIntent(
                        observed && !releasedCoopHold && !coopHoldPaused);
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
            CampaignUIAPI ui = sector == null ? null : sector.getCampaignUI();
            boolean dialogOrMenu = ui != null && isBlockingDialogOrMenuOpen(ui);
            boolean coreTab = ui != null && isCoreTabOpen(ui);
            if (!coreTab) {
                // Phase 28 M2, the NEXT_SCREEN_TOGGLE boundary. No core tab is open, so a flip that
                // arrived while one WAS open takes effect now - before the next open, and never
                // underneath one. This is the whole of "a flip does not yank the pause out from
                // under a screen the guest already has open".
                //
                // Core tabs only, not "nothing at all is open": the core tabs are the only thing
                // this key governs. Waiting for an interaction dialog to close as well would park
                // the boundary behind a docked-station conversation that can run for minutes, and
                // that dialog pauses regardless of the option - so crossing the boundary underneath
                // it changes nothing the player can see.
                optionsPolicy.advanceBoundary(coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS);
            }
            // The two hardwired intents are not negotiable: an interaction dialog pauses because the
            // Phase 12 market model trades against open-time state (correctness, not comfort), and
            // the in-game menu pauses because the player has left the game. Only the core tabs -
            // map/fleet/character/refit/cargo/intel, all local-only views - are behind the policy.
            // The bridge lever rides the same level as a screen (Phase 30). Without it the verb only
            // flipped the coordinator's latch and this line recomputed it from the real UI on the
            // very next frame, so a scripted "pause" on the guest was a no-op that reported success.
            boolean screenOpen = bridgePauseIntent || dialogOrMenu || (coreTab && optionsPolicy.appliedBool(
                    coop.config.CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS));
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

    /**
     * The full predicate: every vanilla screen that takes the keyboard away from the campaign.
     *
     * <p><b>Still the whole thing, deliberately.</b> Phase 28 split the shared-pause intent into two
     * terms so {@code coop.pauseOnGuestScreens} can drop the core-tab half, but the guest input
     * blocker's suspend logic ({@code syncGuestInputBlocker}) must keep using all of it: what it
     * asks is "does a screen own the keyboard", which is true whatever the pause policy says. Wiring
     * the option in here instead would trap the guest inside its own dialogs the moment the world
     * was allowed to keep running - the exact failure the suspend logic exists to prevent.
     */
    private static boolean isVanillaBlockingScreenOpen(SectorAPI sector) {
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            return false;
        }
        return isBlockingDialogOrMenuOpen(ui) || isCoreTabOpen(ui);
    }

    /**
     * The half of the predicate that pauses the shared world no matter what the policy says: an
     * interaction dialog (Phase 12 market correctness) or the in-game menu.
     */
    private static boolean isBlockingDialogOrMenuOpen(CampaignUIAPI ui) {
        // Phase 28 review (decompiled CampaignState, 0.98a): opening a core tab from the button bar
        // sets the same dialogType field that isShowingDialog() reads, so a core tab alone reports
        // as "showing dialog". Subtract exactly that case, or coop.pauseOnGuestScreens=false could
        // never stop the guest's screen pause. Keyed on getCurrentInteractionDialog(): a docked
        // market dialog reports a core tab too, and that one must keep pausing (Phase 12 open-time
        // snapshot model). isVanillaBlockingScreenOpen keeps the full union, so the input blocker's
        // suspend logic is unaffected.
        boolean coreTabOnly = ui.getCurrentCoreTab() != null && ui.getCurrentInteractionDialog() == null;
        return (ui.isShowingDialog() && !coreTabOnly) || ui.isShowingMenu();
    }

    /**
     * The half {@code coop.pauseOnGuestScreens} governs: the vanilla core tabs
     * (map/fleet/character/refit/cargo/intel), all of them local-only views.
     */
    private static boolean isCoreTabOpen(CampaignUIAPI ui) {
        return ui.getCurrentCoreTab() != null;
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
        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopMessages.PauseSource source = CoopMessages.PauseSource.valueOf(
                payload.requiredString("source"));
        boolean paused = Boolean.parseBoolean(payload.requiredString("paused"));
        long intentSeq = payload.requiredLong("intentSeq");
        boolean applied = source == CoopMessages.PauseSource.SCREEN
                ? pauseCoordinator.applyGuestScreenPauseIntent(paused, intentSeq)
                : pauseCoordinator.applyGuestKeyPauseIntent(paused, intentSeq);
        if (applied) {
            CoopLog.info(CoopNetPump.class, "Coop guest pause intent applied source=" + source
                    + " paused=" + paused + " intentSeq=" + intentSeq);
        }
    }

    private void resetSharedPauseState() {
        if (pauseCoordinator.reconnectHold()) {
            // Phase 21 made this reachable with a hold standing: a peer that drops while the lobby is
            // still open opens a grace window AND parks the shared-pause machinery on the same frame.
            // The grace hold is the session's, not this frame's, and clearing it here would drop the
            // world's only pause in the middle of a reconnect.
            return;
        }
        // The bridge lever belongs to the session it was pulled in; carrying it across the edge would
        // pause the next one with nothing on screen to explain it.
        bridgePauseIntent = false;
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
        if (service.role() != CoopConnectionRole.HOST || !service.isConnected()
                || !peerProvenForOutbound() || !isGameplaySessionActive()) {
            return;
        }

        long now = clockMillis.getAsLong();
        if (now < nextTimeSnapshotAtMillis) {
            return;
        }

        try {
            CoopTimeLock.TimeSnapshot snapshot = timeLock.capture(now, hostPauseHolder());
            CoopMessages.Message message = CoopMessages.timeSnapshot(
                    sessionState.sessionId(),
                    service.nextSeq(),
                    snapshot.paused(),
                    snapshot.fastForward(),
                    snapshot.timestampMillis(),
                    snapshot.campaignDay(),
                    snapshot.sentAtMillis(),
                    snapshot.pausedBy());
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
        // Red-team C6: before the teardown check, because a stuck roster is a live-session condition.
        maybeRequestFleetRoster();
        // Tear the mirror fleet down the moment the session is no longer streaming (disconnect,
        // reject, session end) so a stale AI fleet is never left behind in the world.
        if (!shouldStreamFleet()) {
            // Both halves of the roster split reset on the same edge (Phase 20 M4): a new session
            // owes the peer a fresh FLEET_ROSTER, and a cached roster from the last one would be
            // matched against ticks from a different fleet entirely.
            lastSentRosterHash = "";
            rosterCache.reset();
            nextRosterRequestAtMillis = 0L;
            if (fleetMirror.hasMirrorFleet()) {
                fleetMirror.dispose();
            }
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
            // Reliable half first: a tick naming a roster the peer has never seen is held, so sending
            // the roster on the same frame the hash changes keeps that window to one round trip.
            maybeSendFleetRoster(snapshot);
            String datagram = datagramRedundancy.compose(
                    sessionToken(),
                    localSenderToken(),
                    CoopMessages.Type.FLEET_SNAPSHOT,
                    streamClock.nextEpoch(), streamClock.gameTimeMillis(),
                    CoopFleetSnapshot.Tick.of(snapshot).encode());
            sendStateDatagram(datagram);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to capture coop fleet snapshot", ex);
        }
    }

    /**
     * Phase 20 M4 roster split, send side: the immutable half of the local fleet goes out on reliable
     * TCP when it changes, and only then. "Changes" covers the session edges too, because
     * {@link #lastSentRosterHash} is cleared whenever this side stops streaming and on an accepted
     * resume — a returning peer has no roster and would otherwise hold its mirror until the local
     * player happened to gain or lose a ship.
     *
     * @return true when a roster went out (test seam)
     */
    boolean maybeSendFleetRoster(CoopFleetSnapshot snapshot) {
        if (snapshot == null || snapshot.fleetHash16().equals(lastSentRosterHash)) {
            return false;
        }
        CoopMessages.Message roster = CoopMessages.fleetRoster(sessionState.sessionId(),
                service.nextSeq(), clockMillis.getAsLong(),
                coop.fleet.CoopFleetRoster.of(snapshot).encode());
        service.send(roster);
        log("outbound", roster);
        lastSentRosterHash = snapshot.fleetHash16();
        CoopLog.info(CoopNetPump.class, "Coop sent FLEET_ROSTER ships=" + snapshot.members().size()
                + " fleetHash16=" + lastSentRosterHash);
        return true;
    }

    /** Host or guest: the peer's roster, cached until its hash changes. */
    private void handleFleetRoster(CoopMessages.Message message) {
        if (!isGameplaySessionActive()) {
            return;
        }
        coop.fleet.CoopFleetRoster roster;
        try {
            roster = coop.fleet.CoopFleetRoster.decode(CoopMessages.parseFleetRoster(message));
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to decode coop FLEET_ROSTER", ex);
            return;
        }
        if (!roster.playerId().equals(sessionState.remotePlayerId())) {
            return;
        }
        rosterCache.accept(roster);
        CoopLog.info(CoopNetPump.class, "Coop cached FLEET_ROSTER from " + roster.playerId()
                + " ships=" + roster.members().size() + " fleetHash16=" + roster.fleetHash16());
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

    /**
     * Red-team B4: tells the peer this process is about to stop pumping, and stamps the same
     * exemption locally. Flushed inline, because the stall starts as soon as this returns - a notice
     * sitting in the outbound queue while the engine writes a save is worth nothing.
     *
     * <p>Both roles send it; that is the whole point (see {@link CoopStallNotice}). It is separate
     * from {@code SAVE_CHECKPOINT}, whose semantics are untouched: that one orders a coordinated
     * autosave and stays host-only.
     */
    void sendStallNotice(String reason, long expectedMillis) {
        if (service.role() == CoopConnectionRole.NONE || !service.isConnected()
                || !isGameplaySessionActive()) {
            return;
        }
        try {
            CoopMessages.Message notice = CoopMessages.stallNotice(sessionState.sessionId(),
                    service.nextSeq(), clockMillis.getAsLong(), reason, expectedMillis);
            service.send(notice);
            log("outbound", notice);
            service.flushOutbound();
            // Our own silence measurement is about to be an artefact of our own stall as well.
            lastSaveCheckpointAtMillis = clockMillis.getAsLong();
            CoopLog.info(CoopNetPump.class, "Coop announced a local stall (" + reason + ", ~"
                    + expectedMillis + " ms) so the partner does not read it as a dead link");
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to send STALL_NOTICE", ex);
        }
    }

    /** Peer: it is about to go quiet on purpose. Same exemption stamp a SAVE_CHECKPOINT gives. */
    private void handleStallNotice(CoopMessages.Message message) {
        if (!isGameplaySessionActive()) {
            return;
        }
        lastSaveCheckpointAtMillis = clockMillis.getAsLong();
        CoopLog.info(CoopNetPump.class, "Coop partner announced a stall ("
                + CoopMessages.parseStallReason(message) + ", ~"
                + CoopMessages.parseStallExpectedMillis(message) + " ms); the link-death rule is"
                + " exempt for " + (CoopLinkQuality.DEATH_SAVE_EXEMPT_MILLIS / 1000L) + " s");
    }

    // ---- Phase 20 red-team C6: roster retry -------------------------------------------------------

    /** How often a stuck roster mismatch may ask the peer to re-send. */
    private static final long ROSTER_REQUEST_INTERVAL_MILLIS = 5_000L;

    /**
     * Asks the peer for its {@code FLEET_ROSTER} when the local cache has been stuck on a hash it
     * does not hold (red-team C6).
     *
     * <p>The send side only ever transmits a roster when {@code fleetHash16} changes, so a roster
     * that was dropped - rejected pre-session, lost to a frame that threw, refused by an outbound
     * queue - is never re-sent, and the receiving mirror holds its last roster until the peer happens
     * to gain or lose a ship. {@link coop.fleet.CoopRosterCache} already detects exactly that state
     * (5 s of ticks naming a roster it does not have); this turns the detection into a repair.
     */
    private void maybeRequestFleetRoster() {
        if (!isGameplaySessionActive() || !rosterCache.mismatchLogged()) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now < nextRosterRequestAtMillis) {
            return;
        }
        nextRosterRequestAtMillis = now + ROSTER_REQUEST_INTERVAL_MILLIS;
        CoopMessages.Message request = CoopMessages.fleetRosterRequest(
                sessionState.sessionId(), service.nextSeq(), now);
        service.send(request);
        log("outbound", request);
        CoopLog.info(CoopNetPump.class, "Coop asked the partner to re-send its FLEET_ROSTER;"
                + " the local cache has been holding a stale roster");
    }

    /**
     * Peer: re-send the roster. Clearing the hash is the whole implementation - the ordinary send
     * path fires on the next fleet tick because it compares against this field.
     */
    private void handleFleetRosterRequest(CoopMessages.Message message) {
        if (!isGameplaySessionActive()) {
            return;
        }
        CoopLog.info(CoopNetPump.class, "Coop partner asked for a FLEET_ROSTER re-send;"
                + " forcing it on the next fleet tick");
        lastSentRosterHash = "";
    }

    /** Partner: banner the wipe, since the only other cue is the mirror teleporting across the sector. */
    private void handleRespawnPlayer(CoopMessages.Message message) {
        if (!isGameplaySessionActive()) {
            return;
        }
        try {
            CoopMessages.Payload payload = CoopMessages.payload(message);
            String playerId = payload.requiredString("playerId");
            String destination = payload.requiredString("destinationName");
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
                || !peerProvenForOutbound() || !isGameplaySessionActive()) {
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
            // Phase 21 red-team item 12: the stats page's net-worth column is credits, and this is the
            // only place the guest's credits ever reach the host. Sampled from the snapshot cadence
            // rather than from the 1 Hz host sample, which has nothing to read them from.
            String guestId = sessionState.remotePlayerId();
            if (guestId != null && !guestId.isEmpty()) {
                sessionStats().noteNetWorth(guestId, (long) snapshot.getCredits());
            }
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
            // Exemption (b) of the link-death rule: both processes stop to write a save around a
            // checkpoint, and a late-game sector save runs well past the 15 s silence threshold.
            lastSaveCheckpointAtMillis = clockMillis.getAsLong();
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
            // Same exemption stamp as the sender's: the guest is about to stop pumping to write its
            // own coordinated autosave, and so, right now, is the host.
            lastSaveCheckpointAtMillis = clockMillis.getAsLong();
            CoopMessages.Payload payload = CoopMessages.payload(message);
            saveCheckpoint.onCheckpointReceived(
                    payload.requiredLong("checkpointId"),
                    payload.requiredString("reason"),
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
            ingestStateDatagram(raw);
        }
    }

    /**
     * The one apply path for a composed state datagram, whichever transport delivered it: UDP from
     * {@link #drainFleetDatagrams}, or a {@code STATE_DATAGRAM} TCP message while the Phase 20.1
     * fallback is on. Sharing it is the point — a fallback with its own parse/filter/apply code is a
     * second set of semantics that only runs on broken networks, i.e. the one place nobody tests.
     */
    private void ingestStateDatagram(String raw) {
        try {
            CoopMessages.Datagram datagram = CoopMessages.parseDatagram(raw);
            // Wiretap before the session filter and the watermark: a datagram this side decoded
            // but then discarded is exactly what a desync investigation wants to see. Dormant
            // unless -Dcoop.debug.wiretap=true / $coopWiretap.
            wiretap.recordReceive(raw, datagram);
            if (!sessionMatches(datagram.token())) {
                return;
            }
            // Red-team A6/A9: the sender check moved up here from applyFleetSnapshotSection, and it
            // now covers every datagram type rather than only FLEET_SNAPSHOT. Everything below this
            // line stores state under the senderId - CoopDatagramWatermark's per-stream table and
            // CoopLinkQuality's per-sender loss estimate both key on it - so running the check
            // afterwards let anyone who knew the session token grow two unbounded maps under keys of
            // their choosing, and corrupt the real peer's loss figures on the way past.
            if (!isRemoteSender(datagram.senderId())) {
                noteSenderMismatch(datagram.senderId());
                return;
            }
            // The LAST section carries this datagram's own epoch; the earlier one is the redundant
            // copy of the previous send. Counting distinct last-epochs against their span is the raw
            // loss estimate (see CoopLinkQuality).
            if (!datagram.sections().isEmpty()) {
                linkQuality.noteInboundDatagram(datagram.senderId(),
                        datagram.sections().get(datagram.sections().size() - 1).epoch(),
                        clockMillis.getAsLong());
            }
            // Decode BEFORE the watermark filter (Phase 20 M4): an NPC_FLEET_MOTION delta section is
            // coded against the section physically before it in this same datagram, so section 2 only
            // decodes if section 1 was decoded too — even when section 1 was already applied and the
            // watermark is about to drop it.
            List<List<CoopNpcFleetMotion>> motionSections =
                    datagram.type() == CoopMessages.Type.NPC_FLEET_MOTION
                            ? CoopNpcFleetMotion.decodeDatagram(sectionBodies(datagram))
                            : null;
            // Sections at or below the (senderId, type) epoch watermark are dropped here: a reordered
            // datagram applies nothing, a redundant section that already arrived applies nothing,
            // and a redundant section covering a lost packet applies normally (oldest first). Chunks
            // of one tick share an epoch and are accepted once each.
            boolean[] fresh = datagramWatermark.acceptedMask(datagram);
            // Phase 29 M2 jitter sample: one per FLEET_SNAPSHOT datagram whose own (last) section is
            // new. The redundant earlier section is the previous send arriving a second time and
            // would double-count every interval; a whole datagram that is pure duplicate contributes
            // nothing, which is what makes the spacing a measurement of the path.
            if (datagram.type() == CoopMessages.Type.FLEET_SNAPSHOT
                    && fresh.length > 0 && fresh[fresh.length - 1]) {
                linkQuality.noteStateSampleArrival(clockMillis.getAsLong());
            }
            for (int i = 0; i < fresh.length; i++) {
                if (!fresh[i]) {
                    continue;
                }
                CoopMessages.DatagramSection section = datagram.sections().get(i);
                switch (datagram.type()) {
                    case FLEET_SNAPSHOT -> applyFleetSnapshotSection(datagram.senderId(), section);
                    case NPC_FLEET_MOTION -> handleNpcFleetMotion(section, motionSections.get(i));
                    default -> { /* ignore unknown datagram types */ }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            // Red-team A11: rate-limited. This used to be a WARN with a full stack trace per packet,
            // which turns one malformed-datagram flood into a log-write denial of service on the
            // campaign thread. The running count is what keeps the rate limit honest.
            datagramDecodeFailureCount++;
            long now = clockMillis.getAsLong();
            if (now >= nextDatagramDecodeLogAtMillis) {
                nextDatagramDecodeLogAtMillis = now + FAILURE_LOG_INTERVAL_MILLIS;
                CoopLog.warn(CoopNetPump.class, "Failed to apply coop fleet datagram ("
                        + datagramDecodeFailureCount + " total)", ex);
            }
        }
    }

    /** Whether {@code senderId} is the remote coop player's wire token (red-team A6/A9). */
    private boolean isRemoteSender(String senderId) {
        String expected = remoteSenderToken();
        return !expected.isEmpty() && expected.equals(senderId);
    }

    private void noteSenderMismatch(String senderId) {
        datagramSenderMismatchCount++;
        long now = clockMillis.getAsLong();
        if (now < nextSenderMismatchLogAtMillis) {
            return;
        }
        nextSenderMismatchLogAtMillis = now + FAILURE_LOG_INTERVAL_MILLIS;
        CoopLog.warn(CoopNetPump.class, "Coop dropped a state datagram from senderId=" + senderId
                + ", which is not this session's partner (" + datagramSenderMismatchCount + " total)");
    }

    /** State datagrams dropped on the sender check; test/bridge read. */
    long datagramSenderMismatchCount() {
        return datagramSenderMismatchCount;
    }

    /** State datagrams that failed to decode; test/bridge read. */
    long datagramDecodeFailureCount() {
        return datagramDecodeFailureCount;
    }

    // ---- Phase 20 red-team A8: sender stream-time validation --------------------------------------

    /**
     * How far ahead of everything already known a sender stream-time stamp may jump: five game
     * minutes. Generous by three orders of magnitude against the 100 ms cadence these stamps arrive
     * on, which is the point - it is a sanity bound, not a jitter filter.
     */
    private static final long MAX_SAMPLE_ADVANCE_MILLIS = 300_000L;

    /**
     * Validates one {@code sentGameTimeMillis} before it is allowed to move the render cursor
     * (red-team A8). {@link coop.fleet.CoopMotionTimeline#noteSample} only ever raises its high-water
     * mark, so a single stamp of {@code Long.MAX_VALUE} parks the cursor at the end of time and every
     * real sample after it is silently older - every mirror freezes for the rest of the session, with
     * nothing in the log. The same field is read off the TCP {@code NPC_FLEET_SET} payload, so the
     * check lives here, at each read, rather than inside the timeline.
     *
     * <p>The <em>first</em> stamp of a session is accepted whatever it says, and cannot be otherwise:
     * the two processes' stream clocks start when their processes did and share no origin, so a host
     * that has been running for three hours legitimately stamps three hours ahead of a guest that
     * just launched. Every stamp after it is bounded against what has already been accepted.
     *
     * @return true when the stamp may be used
     */
    private boolean acceptSampleStamp(long sentGameTimeMillis) {
        if (sentGameTimeMillis < 0L) {
            noteRejectedSampleStamp(sentGameTimeMillis, "negative");
            return false;
        }
        if (latestAcceptedSampleMillis != Long.MIN_VALUE) {
            long ceiling = Math.max(latestAcceptedSampleMillis, streamClock.gameTimeMillis())
                    + MAX_SAMPLE_ADVANCE_MILLIS;
            if (sentGameTimeMillis > ceiling) {
                noteRejectedSampleStamp(sentGameTimeMillis, "more than "
                        + (MAX_SAMPLE_ADVANCE_MILLIS / 1000L) + " s of game time past " + ceiling);
                return false;
            }
        }
        latestAcceptedSampleMillis = Math.max(latestAcceptedSampleMillis, sentGameTimeMillis);
        return true;
    }

    private void noteRejectedSampleStamp(long stamp, String why) {
        rejectedSampleStampCount++;
        long now = clockMillis.getAsLong();
        if (now < nextSampleStampLogAtMillis) {
            return;
        }
        nextSampleStampLogAtMillis = now + FAILURE_LOG_INTERVAL_MILLIS;
        CoopLog.warn(CoopNetPump.class, "Coop dropped a state sample stamped " + stamp
                + " ms (" + why + "); " + rejectedSampleStampCount + " so far this session");
    }

    /** Sample stamps refused by {@link #acceptSampleStamp}; test read. */
    long rejectedSampleStampCount() {
        return rejectedSampleStampCount;
    }

    /**
     * The state-stream router both producers send through ({@link CoopStateStreamSink}): UDP
     * normally, wrapped in a TCP {@code STATE_DATAGRAM} while the fallback is on. The wrapped bytes
     * are the datagram verbatim, so the receiving side is the same code either way.
     */
    void sendStateDatagram(String datagram) {
        if (datagram == null) {
            return;
        }
        if (!stateStreamFallbackActive) {
            int bytes = CoopMessages.utf8Length(datagram);
            if (bytes <= CoopNetService.MAX_DATAGRAM_BYTES) {
                service.sendDatagram(datagram);
                return;
            }
            escalateOversizedDatagram(datagram, bytes);
            return;
        }
        // The wiretap's send hook lives in the transport's UDP flush, which this path bypasses; call
        // it here so a fallback session still log-diffs against the peer's receive side.
        wiretap.recordSend(datagram, datagram.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        service.send(CoopMessages.stateDatagram(sessionState.sessionId(), service.nextSeq(),
                clockMillis.getAsLong(), datagram));
    }

    /**
     * Phase 20 M4 escalation: a composed datagram above {@link CoopNetService#MAX_DATAGRAM_BYTES}
     * goes out wrapped in TCP rather than as an IP-fragmented UDP packet, which on a lossy path is
     * strictly worse than the reliable wire. This should never fire — the producers pack to fit — so
     * it is logged once per type and counted, which is what turns "never" into evidence.
     *
     * <p>No Deflate layer: the plan lists compression as conditional, and the reliable path is
     * already there. Compressing to sneak under an MTU would trade a bounded, observable behaviour
     * for a size that depends on the payload's entropy.
     */
    private void escalateOversizedDatagram(String datagram, int bytes) {
        CoopMessages.Type type;
        try {
            type = CoopMessages.parseDatagramHeader(datagram).type();
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Coop cannot escalate an unparseable datagram", ex);
            return;
        }
        service.noteDatagramEscalatedToTcp();
        wiretap.recordEscalation(type);
        if (escalationLoggedTypes.add(type)) {
            CoopLog.warn(CoopNetPump.class, "Coop " + type + " composed to " + bytes + " B, above the "
                    + CoopNetService.MAX_DATAGRAM_BYTES
                    + " B UDP budget; routing it over TCP (logged once per type)");
        }
        wiretap.recordSend(datagram, bytes);
        service.send(CoopMessages.stateDatagram(sessionState.sessionId(), service.nextSeq(),
                clockMillis.getAsLong(), datagram));
    }

    /** The section bodies of one datagram, in wire order; the joint motion decode consumes these. */
    private static List<String> sectionBodies(CoopMessages.Datagram datagram) {
        List<String> bodies = new java.util.ArrayList<>(datagram.sections().size());
        for (CoopMessages.DatagramSection section : datagram.sections()) {
            bodies.add(section.body());
        }
        return bodies;
    }

    /**
     * Phase 20 M4: the tick carries no identity of its own, so the sender is the envelope's
     * {@code senderId} — the field the transport validated — rather than a body field a spoofer would
     * get to choose. The roster cache turns the tick back into the snapshot the mirror has always
     * taken; mirror semantics are unchanged by this phase.
     */
    private void applyFleetSnapshotSection(String senderId, CoopMessages.DatagramSection section) {
        // The sender check that used to live here is now in ingestStateDatagram, ahead of the
        // watermark and the loss estimate (red-team A6/A9); by this point the senderId has been
        // matched against the remote player's token for every datagram type.
        String remoteId = sessionState.remotePlayerId();
        if (remoteId == null) {
            return;
        }
        if (!acceptSampleStamp(section.sentGameTimeMillis())) {
            return;
        }
        CoopFleetSnapshot.Tick tick = CoopFleetSnapshot.Tick.decode(section.body());
        CoopFleetSnapshot snapshot = rosterCache.compose(tick, remoteId, sessionState.remoteName(),
                clockMillis.getAsLong());
        double sampleTimeSeconds = section.sentGameTimeMillis() / 1000.0;
        motionTimeline.noteSample(sampleTimeSeconds);
        fleetMirror.apply(snapshot, localPlayerFactionId(), sampleTimeSeconds);
    }

    private void handleNpcFleetMotion(CoopMessages.DatagramSection section,
                                      List<CoopNpcFleetMotion> motions) {
        if (service.role() != CoopConnectionRole.GUEST || !isGameplaySessionActive()) {
            return;
        }
        if (!acceptSampleStamp(section.sentGameTimeMillis())) {
            return;
        }
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
            // interpolation buffers the UDP motion sections fill. Validated exactly as the datagram
            // sections' stamps are (red-team A8) - same field, same failure, different wire.
            long stampMillis =
                    payload.get("gameTimeMillis") instanceof Number n ? n.longValue() : 0L;
            if (!acceptSampleStamp(stampMillis)) {
                return;
            }
            double sampleTimeSeconds = stampMillis / 1000.0;
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
            latestAcceptedSampleMillis = Long.MIN_VALUE;
            coop.fleet.CoopMotionSpeedProbe.INSTANCE.reset();
            wiretap.sessionStarted();
            npcReplicationStreaming = true;
        } else if (!active && npcReplicationStreaming) {
            // Session ended: drop all guest NPC mirrors so no stale AI fleet is left behind.
            npcFleetRegistry.disposeAll();
            datagramWatermark.reset();
            datagramRedundancy.reset();
            motionTimeline.reset();
            latestAcceptedSampleMillis = Long.MIN_VALUE;
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

    /**
     * Belt-and-braces: {@link CoopNetService} already drops datagrams whose token is not this
     * session's, so nothing that reaches here should fail this. It stays because the pump's own view
     * of the session id is what the apply paths trust, and the two must never disagree silently.
     */
    private boolean sessionMatches(String datagramToken) {
        String token = sessionToken();
        return !token.isEmpty() && token.equals(datagramToken);
    }

    // ---- Phase 20 red-team C10: cached wire tokens ------------------------------------------------
    // wireToken is a SHA-256; these three were recomputed per outbound datagram AND per received
    // section, i.e. tens of hashes a second, for three strings that change only on a session edge.
    // The cache keys on the id itself, so a session edge invalidates it without anyone remembering to.

    /** {@code wireToken(sessionId)}, or "" when there is no session. */
    String sessionToken() {
        String sessionId = sessionState.sessionId();
        if (sessionId == null) {
            cachedSessionId = null;
            cachedSessionToken = "";
            return "";
        }
        if (!sessionId.equals(cachedSessionId)) {
            cachedSessionId = sessionId;
            cachedSessionToken = CoopMessages.wireToken(sessionId);
        }
        return cachedSessionToken;
    }

    /** {@code wireToken(localPlayerId)}, or "" before a role is started. */
    String localSenderToken() {
        String playerId = sessionState.localPlayerId();
        if (playerId == null) {
            cachedLocalPlayerId = null;
            cachedLocalSenderToken = "";
            return "";
        }
        if (!playerId.equals(cachedLocalPlayerId)) {
            cachedLocalPlayerId = playerId;
            cachedLocalSenderToken = CoopMessages.wireToken(playerId);
        }
        return cachedLocalSenderToken;
    }

    /** {@code wireToken(remotePlayerId)}, or "" before a partner is known. */
    String remoteSenderToken() {
        String playerId = sessionState.remotePlayerId();
        if (playerId == null) {
            cachedRemotePlayerId = null;
            cachedRemoteSenderToken = "";
            return "";
        }
        if (!playerId.equals(cachedRemotePlayerId)) {
            cachedRemotePlayerId = playerId;
            cachedRemoteSenderToken = CoopMessages.wireToken(playerId);
        }
        return cachedRemoteSenderToken;
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
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String entityId = payload.requiredString("entityId");
        String entityName = payload.requiredString("entityName");
        String playerId = payload.requiredString("playerId");
        CoopInteractionGate.ClaimResult result = interactionGate.arbitrate(entityId, playerId, entityName);
        if (result.accepted()) {
            CoopMessages.Message accept = CoopMessages.interactionAccept(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    entityId, playerId, entityName, result.hostSeq());
            service.sendTo(message.senderId(), accept);
            log("outbound", accept);
            CoopLog.info(CoopNetPump.class, "Coop interaction claim accepted entityId=" + entityId
                    + " playerId=" + playerId + " hostSeq=" + result.hostSeq());
        } else {
            CoopMessages.Message reject = CoopMessages.interactionReject(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    entityId, result.rejectReason());
            service.sendTo(message.senderId(), reject);
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
        CoopMessages.Payload payload = CoopMessages.payload(message);
        CoopInteractionClaim claim = new CoopInteractionClaim(
                payload.requiredString("entityId"),
                payload.requiredString("playerId"),
                payload.requiredString("entityName"),
                payload.requiredLong("hostSeq"));
        interactionGate.applyAccepted(claim);
        // The host has ruled on this entity, so the wait is over whichever way it went. Nothing is
        // posted for an answer that arrives late: the accept simply leaves the dialog where it is.
        claimWaitTracker.onAnswered(claim.entityId());
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
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String entityId = payload.requiredString("entityId");
        String reason = payload.requiredString("reason");
        claimWaitTracker.onAnswered(entityId);
        if (rejectTracker.onRejected(entityId)) {
            CoopLog.warn(CoopNetPump.class, "Coop interaction rejected entityId=" + entityId + " "
                    + reason + "; closing the local dialog");
        }
    }

    private void handleInteractionRelease(CoopMessages.Message message) {
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String entityId = payload.requiredString("entityId");
        String playerId = payload.requiredString("playerId");
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
            pollClaimWait();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync coop interaction gate", ex);
        }
    }

    /**
     * Phase 20 M6: tell the guest once when the host has not answered its claim within
     * {@code max(1000, 4 x p95 RTT)} ms. The dialog stays open and usable either way — this is an
     * affordance, not a gate. Bypasses {@link #postFeed}'s per-kind rate limit on purpose: the tracker
     * already emits at most one line per claim, and two different docks a few seconds apart are two
     * things the player wants to hear about, not a flap.
     */
    private void pollClaimWait() {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        String entityName = claimWaitTracker.pendingEntityName();
        String warning = claimWaitTracker.pollWarning(clockMillis.getAsLong(), p95RttMillisOrZero());
        if (warning == null) {
            return;
        }
        coop.ui.CoopFeed.post(warning, FEED_WARN_COLOR);
        intelFeed.noteEvent(warning + " (" + entityName + ")");
        CoopLog.info(CoopNetPump.class, "Coop interaction claim unanswered after "
                + coop.interaction.CoopClaimWaitTracker.waitThresholdMillis(p95RttMillisOrZero())
                + " ms entity=" + entityName);
    }

    /** The measured p95 RTT with "no sample yet" folded to 0 (the unmeasured/loopback contract). */
    private int p95RttMillisOrZero() {
        Integer p95 = linkQuality.p95RttMillis();
        return p95 == null ? 0 : p95;
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
        // Phase 24 colony management. The colony screen is a CORE TAB, not a dialog: reached from the
        // command UI it docks nothing and has no interaction target at all, so the gate never saw it
        // and both players could edit one colony at once - and COLONY_MGMT ships absolute state, so
        // the loser's edit is overwritten with no refund for the credits it charged. One id for both
        // players, deliberately: with no API for "which colony is on screen" the whole editor is the
        // resource, and serialising it is what the plan's "the interaction gate prevents simultaneous
        // editing" criterion asks for. It outranks the dialog target because the tab is what the
        // player is actually editing; the dialog's own claim is re-taken when the tab closes.
        if (isColonyManagementOpen(ui)) {
            currentEntityId = COLONY_MANAGEMENT_ENTITY_ID;
            currentEntityName = COLONY_MANAGEMENT_ENTITY_NAME;
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
                if (dialog != null && ui != null && ui.getCurrentCoreTab() != null) {
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
            } else if (COLONY_MANAGEMENT_ENTITY_ID.equals(openEntityId)) {
                // The colony editor has no dialog to dismiss, so the equivalent of taking the screen
                // away is switching the core UI off that tab. Re-issued every frame like the dismiss
                // until the tab is really gone, and the player can walk straight back in once the
                // other side lets go.
                leaveColonyManagementTab(ui);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to force-close rejected interaction dialog"
                    + " entityId=" + openEntityId, ex);
        }
        return true;
    }

    /** Whether the vanilla colony-management (OUTPOSTS) core tab is on screen. */
    private static boolean isColonyManagementOpen(CampaignUIAPI ui) {
        return ui != null && ui.getCurrentCoreTab() == com.fs.starfarer.api.campaign.CoreUITabId.OUTPOSTS;
    }

    /**
     * Bounces the local player out of the colony editor after a lost claim. There is no "close the
     * core UI" API, so this switches to the intel tab - the screen the colony list is reached from -
     * rather than leaving the player somewhere they cannot spend credits the peer's state is about
     * to overwrite.
     */
    private void leaveColonyManagementTab(CampaignUIAPI ui) {
        if (!isColonyManagementOpen(ui)) {
            return;
        }
        ui.showCoreUITab(com.fs.starfarer.api.campaign.CoreUITabId.INTEL);
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
            } else if (rejectTracker.onRejected(entityId)) {
                // Phase 18 applied to the host's own lost race. Logging it and leaving the dialog up
                // put both players in the same shop - exactly the state this gate exists to prevent,
                // and the host is the side whose open market screen the guest's re-open re-rolls.
                // The same tracker the guest uses closes it on the next frame, banner included, and
                // localInteractionEntityId stays set so no re-claim loop starts.
                CoopLog.warn(CoopNetPump.class, "Host opened interaction on entityId=" + entityId
                        + " already claimed by " + result.rejectedByPlayerId()
                        + "; closing the local dialog");
            }
        } else {
            CoopMessages.Message claim = CoopMessages.interactionClaim(
                    sessionState.sessionId(), service.nextSeq(), clockMillis.getAsLong(),
                    entityId, entityName, localPlayerId);
            service.send(claim);
            log("outbound", claim);
            // Phase 20 M6: start the round-trip clock. The dialog is already open (optimistic model);
            // this only decides when the player is told that the confirmation is taking a while.
            claimWaitTracker.onClaimSent(entityId, entityName, clockMillis.getAsLong());
        }
    }

    private void sendInteractionRelease(String entityId) {
        // The player closed the dialog. Whatever the host was going to say about it no longer needs
        // a "still waiting" notice attached to a screen that is gone.
        claimWaitTracker.onAnswered(entityId);
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
            timeLock.setInteractionBlocked(true);
            if (!blocking.entityName().equals(lastBlockedEntityName)) {
                if (ui != null) {
                    ui.addMessage("Remote player is interacting: " + blocking.entityName());
                }
                lastBlockedEntityName = blocking.entityName();
            }
        } else {
            timeLock.setInteractionBlocked(false);
            lastBlockedEntityName = null;
        }
    }

    private void resetInteractionState() {
        // Session end / disconnect also ends any pending forced close and drops parked claims: the
        // dialog the rejection referred to belongs to a session that no longer exists.
        boolean hadRejection = rejectTracker.clear();
        claimWaitTracker.clear();
        boolean hadDelayedClaims = !delayedInteractionClaims.isEmpty();
        delayedInteractionClaims.clear();
        // The gate itself is asked, not just the four side flags. A remote claim accepted inside
        // this frame's inbound drain is recorded in the gate and in none of them (applyLocalBlocking
        // has not run for it yet), so a link that dies on that same frame used to skip the clear and
        // carry the claim into the next session, where it blocked every local interaction.
        if (localInteractionEntityId == null && lastBlockedEntityName == null
                && !hadRejection && !hadDelayedClaims && interactionGate.isEmpty()) {
            return;
        }
        localInteractionEntityId = null;
        lastBlockedEntityName = null;
        interactionGate.clear();
        try {
            timeLock.setInteractionBlocked(false);
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
            CoopMessages.Payload payload = CoopMessages.payload(message);
            CoopBattleResult result = CoopBattleResult.decode(
                    payload.requiredString("battleId"),
                    payload.requiredString("engagingPlayerId"),
                    payload.requiredString("outcome"),
                    (int) payload.requiredLong("engagingFleetSize"),
                    payload.requiredString("body"));
            // Dedup guarantee: apply() is the battle-id ledger and returns false for a repeat, so a
            // resent BATTLE_RESULT cannot double-count the guest's battle.
            if (battleResultReconciler.apply(result)) {
                noteBattleForStats(result);
            }
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
                // Dedup guarantee: apply() is the battle-id ledger and returns false for a repeat.
                if (battleResultReconciler.apply(result)) {
                    noteBattleForStats(result);
                }
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
        // Phase 21, both roles: the pre-battle roster is the only unambiguous point to read the
        // fleet from, and the host fights its own battles here too.
        captureRosterBeforeBattle();
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
        // Phase 21: arm the hull-loss diff. It settles on a later frame, once the recovery screen is
        // gone -- see maybeSettleShipLosses.
        armShipLossDiff();
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
            boolean active = campaignReplicatorShouldBeActive();
            if (active && !campaignReplicator.isRegistered()) {
                campaignReplicator.registerOn(sector);
            } else if (!active && campaignReplicator.isRegistered()) {
                campaignReplicator.dispose(sector);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopNetPump.class, "Failed to sync coop campaign replicator", ex);
        }
    }

    /**
     * Whether the campaign replicator belongs on the sector this frame; package private as the test
     * seam for the grace rule.
     *
     * <p>A reconnect window is deliberately <em>not</em> a teardown. {@code dispose} is session-end
     * work — it drops the world/colony/salvage baselines and ends every mirrored expedition warning —
     * and running it for a three-second blip took the guest's read, unstarred warning entries away and
     * gave them back on resume as fresh, re-starred interrupts. The session record is alive for the
     * whole window (that is what the window is), so the replicator stays with it.
     */
    boolean campaignReplicatorShouldBeActive() {
        return (service.isConnected() || reconnect.active()) && isGameplaySessionActive();
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

    /**
     * Heartbeat, both roles (Phase 20.1 M2 added the host half). The guest's ping has always been the
     * half-open-connection detector; the host now pings too because RTT is measured by whoever sent
     * the ping, and a host with no RTT sample cannot fill in its own HUD or {@code LINK_STATUS}.
     */
    private void maybeSendPing() {
        if (service.role() == CoopConnectionRole.NONE || !service.isConnected()) {
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
        linkQuality.notePingSent(ping.seq(), now);
        log("outbound", ping);
        nextPingAtMillis = now + PING_INTERVAL_MILLIS;
    }

    /** Times the PONG against the PING it answers; an unmatched seq contributes no sample. */
    private void handlePong(CoopMessages.Message message) {
        linkQuality.notePongReceived(CoopMessages.requiredPayloadLong(message, "pingSeq"),
                clockMillis.getAsLong());
    }

    /** Stores the peer's report; read by the fallback rule and the connection doctor. */
    private void handleLinkStatus(CoopMessages.Message message) {
        peerLinkStatus = CoopMessages.parseLinkStatus(message);
        peerLinkStatusAtMillis = clockMillis.getAsLong();
        peerCadenceTier = peerLinkStatus.cadenceTier();
        // Applied on arrival rather than at the next 1 s tick: the host pulls a LINK_STATUS forward
        // precisely so the guest can follow the tier immediately, and waiting out the tick would
        // spend most of the saving it was sent to buy. The guest's own fallback still outranks it.
        if (service.role() == CoopConnectionRole.GUEST && !stateStreamFallbackActive) {
            applyCadenceTier(peerCadenceTier, "host set " + peerCadenceTier.hz() + " Hz", true,
                    peerLinkStatusAtMillis);
        }
        // Phase 20.6: the partner's own reading, so the page can answer "does my partner see the
        // same numbers" without either player having to read a log.
        intelFeed.notePeerLink(peerLinkStatus);
    }

    /** The peer's latest report, or null when none has arrived; test/bridge read. */
    CoopMessages.LinkStatus peerLinkStatus() {
        return peerLinkStatus;
    }

    /** Whether the state streams are currently wrapped in TCP; test/bridge read. */
    boolean stateStreamFallbackActive() {
        return stateStreamFallbackActive;
    }

    /** The interval both state streams are currently sending at; test read. */
    long stateStreamIntervalMillis() {
        return fleetSnapshotCadence.intervalMillis();
    }

    /** The receive-side epoch watermark, so tests can assert a datagram reached the apply path. */
    CoopDatagramWatermark datagramWatermark() {
        return datagramWatermark;
    }

    /** The peer's cached fleet roster (Phase 20 M4); test/bridge read. */
    coop.fleet.CoopRosterCache rosterCache() {
        return rosterCache;
    }

    /** The 16-hex hash of the last roster this side sent; test read. */
    String lastSentRosterHash() {
        return lastSentRosterHash;
    }

    private void sendPong(CoopMessages.Message ping) {
        CoopMessages.Message pong = CoopMessages.pong(
                ping.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                ping.seq());
        // A pong answers one ping: it is only meaningful to the peer whose RTT sample it closes.
        service.sendTo(ping.senderId(), pong);
        log("outbound", pong);
    }

    // ---- Phase 20.1 M2 link supervision ---------------------------------------------------------

    /**
     * Once a frame: feed the transport's UDP stamp into the measurement, then on a 1 s tick run the
     * UDP-blocked and degraded rules, and on a 5 s tick ship a {@code LINK_STATUS}.
     *
     * <p>Nothing here can end a session. See {@link CoopLinkQuality} for why silence is not evidence
     * of death in this codebase.
     */
    private void tickLinkSupervision() {
        if (!linkSupervisionArmed) {
            return;
        }
        long now = clockMillis.getAsLong();
        CoopDatagramStats stats = service.datagramStats();
        if (stats.lastInboundDatagramAtMillis() > 0L) {
            // Only the transport knows which datagrams actually came off the UDP socket; ones that
            // arrived wrapped in TCP must not count as evidence that UDP works, or the fallback could
            // never be left.
            linkQuality.noteUdpInbound(stats.lastInboundDatagramAtMillis());
        }

        if (now >= nextLinkEvalAtMillis) {
            nextLinkEvalAtMillis = now + LINK_EVAL_INTERVAL_MILLIS;
            boolean fallback = linkQuality.evaluateFallback(now, peerUdpInboundOkOrNull(now));
            applyStateStreamFallback(fallback, linkQuality.fallbackReason(), true, now);
            // Order matters: the fallback flag is an input to the tier, and the tier is what actually
            // sets the intervals. Running the cadence pick in the same tick is what makes the
            // TCP-wrapped mode present as the pinned floor tier rather than as a second mechanism.
            tickCadence(now, fallback);
            tickInterpolationDelay(now);
            tickDegradedNotice(now);
            maybeLogGuestDoctor(now, stats);
            maybeDeclareLinkDead(now);
        }

        if (now >= nextLinkStatusAtMillis) {
            nextLinkStatusAtMillis = now + LINK_STATUS_INTERVAL_MILLIS;
            sendLinkStatus(now, stats);
        }
    }

    /**
     * Arms and disarms the supervision on the gameplay-session edge. Runs near the TOP of the frame,
     * before the inbound drain: arming clears the peer's last report and the measurements, and doing
     * that after the drain would throw away a {@code LINK_STATUS} that arrived on the very frame the
     * session went live.
     */
    private void syncLinkSupervisionArming() {
        boolean active = service.role() != CoopConnectionRole.NONE && isGameplaySessionActive();
        if (active == linkSupervisionArmed) {
            return;
        }
        long now = clockMillis.getAsLong();
        linkSupervisionArmed = active;
        // Phase 28 M2: the peer on the other side of this edge has been told nothing, so the next
        // frame owes it the whole policy. On the way out, a guest also forgets the host's rules -
        // a campaign that keeps running solo must not still be living under a policy nobody is
        // broadcasting (and whose default is the safe, pre-Phase-28 behaviour).
        lastSentOptionsVersion = -1;
        lastSentOptionsAppliedVersion = -1;
        if (!active && service.role() != CoopConnectionRole.HOST) {
            optionsPolicy.clearSyncedView();
            lastAnnouncedOptionsVersion = -1;
        }
        // Silent on both edges: a session starting or ending is not a connection event the player
        // needs a banner for.
        applyStateStreamFallback(false, active ? "session started" : "session ended", false, now);
        resetLinkSupervision(now);
        if (active) {
            nextLinkStatusAtMillis = now + LINK_STATUS_INTERVAL_MILLIS;
            nextLinkEvalAtMillis = now + LINK_EVAL_INTERVAL_MILLIS;
        }
    }

    private void resetLinkSupervision(long now) {
        linkQuality.reset(now);
        peerLinkStatus = null;
        peerLinkStatusAtMillis = 0L;
        guestDoctorLogged = false;
        feedNextAtMillis.clear();
        // Phase 29 M2: a session edge is not a connection event, so the tier goes back to default
        // silently. Without this the next session would inherit the dead link's floor and spend its
        // first thirty seconds at 5 Hz for no reason.
        cadenceController.reset();
        peerCadenceTier = CoopCadenceTier.DEFAULT;
        applyCadenceTier(CoopCadenceTier.DEFAULT, "session edge", false, now);
        interpolationDelayMillis = Math.round(coop.fleet.CoopMotionTimeline.DELAY_SECONDS * 1000.0);
        interpolationDelayChangedAtMillis = 0L;
        motionTimeline.setDelaySeconds(coop.fleet.CoopMotionTimeline.DELAY_SECONDS);
    }

    /**
     * Phase 29 M2 cadence pick, on the 1 s supervision tick.
     *
     * <p>The host evaluates and announces; the guest applies. A tier change on the host pulls the
     * next {@code LINK_STATUS} forward to <em>now</em> rather than waiting up to five seconds for the
     * regular one: for those seconds the two ends would otherwise be sending at different rates, and
     * the whole symmetry argument rests on them not doing that.
     *
     * <p>The guest's own fallback outranks the announcement. It is the only tier decision the guest
     * makes, and it is not really a decision — a stream wrapped in TCP on this side is at the floor
     * whatever the host believes about its own path.
     */
    private void tickCadence(long now, boolean fallbackActive) {
        if (service.role() == CoopConnectionRole.HOST) {
            CoopCadenceController.Decision decision = cadenceController.evaluate(now,
                    linkQuality.medianRttMillis(), linkQuality.lossPercent(now),
                    service.outboundBacklogged(), fallbackActive);
            if (decision.changed()) {
                applyCadenceTier(decision.tier(), decision.reason(), true, now);
                nextLinkStatusAtMillis = now;
            }
        } else if (service.role() == CoopConnectionRole.GUEST) {
            if (fallbackActive) {
                applyCadenceTier(CoopCadenceTier.FLOOR, CoopCadenceController.REASON_FALLBACK,
                        true, now);
            } else {
                applyCadenceTier(peerCadenceTier, "host set " + peerCadenceTier.hz() + " Hz",
                        true, now);
            }
        }
        applyRedundancyDepth(now);
    }

    /**
     * Phase 29 M2 redundancy-depth escape hatch. A second previous section goes on the wire only
     * while all three hold: the floor tier is in force, the loss that justifies it has actually been
     * measured, and the path is still UDP.
     *
     * <p>Loss is taken as the worse of this side's measurement and the peer's reported one, because
     * redundancy protects the <em>outbound</em> direction and only the peer measures that. A missing
     * or stale peer report leaves the local figure, which on a symmetric path is the same story.
     *
     * <p>The two exclusions are not symmetry for its own sake. On the TCP fallback the transport is
     * already reliable, so a third copy is pure duplicated bytes through a head-of-line-blocked
     * socket; and a floor tier chosen because the outbound queue is backed up would be made strictly
     * worse by sending more per datagram.
     */
    private void applyRedundancyDepth(long now) {
        int loss = linkQuality.lossPercent(now);
        if (peerLinkStatus != null && now - peerLinkStatusAtMillis <= PEER_LINK_STATUS_FRESH_MILLIS) {
            loss = Math.max(loss, peerLinkStatus.lossPercent());
        }
        boolean deep = stateStreamTier == CoopCadenceTier.FLOOR
                && !stateStreamFallbackActive
                && loss >= CoopCadenceController.DOWNSHIFT_LOSS_PERCENT;
        int depth = deep ? CoopDatagramRedundancy.MAX_DEPTH : CoopDatagramRedundancy.DEFAULT_DEPTH;
        datagramRedundancy.setDepth(depth);
        npcFleetReplicator.setRedundancyDepth(depth);
    }

    /**
     * The single point where a cadence tier becomes the intervals both state streams send at
     * (Phase 29 M2). Idempotent: the guest calls it every tick with whatever the host announced, and
     * a tier that has not moved costs one comparison.
     *
     * @param announce false on the session edges, where the change is bookkeeping rather than an
     *                 event a player wants a banner for
     */
    private void applyCadenceTier(CoopCadenceTier tier, String reason, boolean announce, long now) {
        if (tier == null || tier == stateStreamTier) {
            return;
        }
        boolean up = tier.ordinal() > stateStreamTier.ordinal();
        stateStreamTier = tier;
        fleetSnapshotCadence.setIntervalMillis(tier.intervalMillis());
        npcFleetReplicator.setMotionIntervalMillis(tier.intervalMillis());
        if (!announce) {
            return;
        }
        CoopLog.info(CoopNetPump.class, "Coop state stream cadence -> " + tier.hz() + " Hz ("
                + tier.intervalMillis() + " ms, " + tier + ") as " + service.role()
                + "; reason=" + reason
                + " rttP50=" + linkQuality.medianRttMillis()
                + " loss=" + linkQuality.lossPercent(now) + "%"
                + " backlog=" + service.outboundQueueDepth()
                + " fallback=" + stateStreamFallbackActive);
        postFeed(up ? FEED_CADENCE_UP : FEED_CADENCE_DOWN, now,
                "Co-op: state stream " + tier.hz() + " Hz - " + reason,
                up ? FEED_GOOD_COLOR : FEED_WARN_COLOR);
    }

    /**
     * Phase 29 M2 adaptive interpolation delay, on the same 1 s tick and on both roles. Mirror's
     * dynamic-adjustment form without its ceiling: two of the peer's send intervals — the Valve rule
     * the M1 constant came from — plus one standard deviation of measured inter-arrival jitter,
     * clamped to [{@link coop.fleet.CoopMotionTimeline#MIN_DELAY_SECONDS},
     * {@link coop.fleet.CoopMotionTimeline#MAX_DELAY_SECONDS}]. Both terms are whole milliseconds
     * already, so no rounding is involved.
     *
     * <p>The interval is the <em>peer's</em> announced tier, because the delay sizes a buffer of what
     * the peer sends. On a clean link at the default tier that is 200 ms plus whatever residual sigma
     * the estimator is reading, and at the floor tier 400 ms plus the same.
     *
     * <p><b>Why there is no ceiling.</b> The first M2 build rounded the buffer up to whole send
     * intervals. Sends happen on ~16 ms campaign frames, so even a loopback session measures 8-11 ms
     * of sigma by construction, and rounding up turned that into a third whole interval: a clean
     * session went 200 -> 300 ms within a second of starting, on both sides. Jitter is paid for in
     * milliseconds now, not in intervals.
     *
     * <p>Two hysteresis rules keep the cursor from chasing the target it is measured against: a new
     * value has to differ from the applied one by at least {@link #INTERP_DELAY_STEP_MILLIS}, and any
     * value is held {@link #INTERP_DELAY_HOLD_MILLIS}. The first value of a session is exempt from
     * both, so a session settles on its measured buffer immediately instead of sitting on the initial
     * constant until something moves a full step. With no ceiling there is no clamp-bound exception
     * either: the targets are continuous in sigma, so a delay parked on the floor tier's 400 ms is
     * one 25 ms step from moving, where whole-interval targets left it 100 ms — half an interval —
     * from the nearest reachable neighbour and pinned there.
     */
    private void tickInterpolationDelay(long now) {
        applyInterpolationDelayTarget(now, interpolationDelayTargetMillis(
                peerCadenceTier.intervalMillis(), linkQuality.jitterStdDevMillis()));
    }

    /**
     * The delay the peer's send {@code intervalMillis} and the measured {@code jitterMillis} ask for,
     * clamped. Pure, and the test seam for the formula.
     *
     * <p><b>Unit note.</b> The interval is stream (game) milliseconds and the sigma is wall
     * milliseconds, and the sum is spent on the cursor's game-time axis. Under fast-forward that
     * makes the sigma term buy proportionally less buffer than a wall-time reading of it suggests.
     * Left as it is on purpose: the certified Phase 29 M2 formula is {@code 2*interval + sigma}, the
     * conversion would need the campaign speed multiplier in force at sample time (which moves every
     * frame and is not carried on the arrival path), and the term it would scale is the small one.
     */
    static long interpolationDelayTargetMillis(long intervalMillis, long jitterMillis) {
        long minDelay = Math.round(coop.fleet.CoopMotionTimeline.MIN_DELAY_SECONDS * 1000.0);
        long maxDelay = Math.round(coop.fleet.CoopMotionTimeline.MAX_DELAY_SECONDS * 1000.0);
        long target = 2L * intervalMillis + Math.max(0L, jitterMillis);
        return Math.max(minDelay, Math.min(maxDelay, target));
    }

    /**
     * The hysteresis half: adopts {@code target} when the step and the hold both allow it. Package
     * private so the hysteresis can be driven with exact targets in tests.
     */
    void applyInterpolationDelayTarget(long now, long target) {
        if (target == interpolationDelayMillis) {
            return;
        }
        boolean firstOfSession = interpolationDelayChangedAtMillis == 0L;
        if (!firstOfSession) {
            if (Math.abs(target - interpolationDelayMillis) < INTERP_DELAY_STEP_MILLIS) {
                return;
            }
            if (now - interpolationDelayChangedAtMillis < INTERP_DELAY_HOLD_MILLIS) {
                return;
            }
        }
        interpolationDelayMillis = target;
        interpolationDelayChangedAtMillis = now;
        motionTimeline.setDelaySeconds(target / 1000.0);
        CoopLog.info(CoopNetPump.class, "Coop interpolation delay -> " + target
                + " ms (peer " + peerCadenceTier.hz() + " Hz, jitter sigma "
                + linkQuality.jitterStdDevMillis() + " ms)");
    }

    /** The interpolation delay currently in force, in milliseconds; test/bridge read. */
    long interpolationDelayMillis() {
        return interpolationDelayMillis;
    }

    /** The tier this side's state streams are sending at; test/bridge read. */
    CoopCadenceTier stateStreamTier() {
        return stateStreamTier;
    }

    /** The tier the peer last announced; test read. */
    CoopCadenceTier peerCadenceTier() {
        return peerCadenceTier;
    }

    /** The NPC motion stream's send interval; test read that the two cadences move together. */
    long npcMotionIntervalMillis() {
        return npcFleetReplicator.motionIntervalMillis();
    }

    /** The state streams' redundancy depth; test read. */
    int stateStreamRedundancyDepth() {
        return datagramRedundancy.depth();
    }

    /** The peer's UDP reading while it is still fresh enough to mean anything, else null. */
    private Boolean peerUdpInboundOkOrNull(long now) {
        if (peerLinkStatus == null || now - peerLinkStatusAtMillis > PEER_LINK_STATUS_FRESH_MILLIS) {
            return null;
        }
        return peerLinkStatus.udpInboundOk();
    }

    /**
     * Applies a fallback transition: the transport the state streams ride on changes, the transition
     * is logged with its numbers, and the player gets one feed line.
     *
     * <p><b>What this method no longer does (Phase 29 M2): set the send intervals.</b> The reduced
     * rate on the TCP path used to be a second cadence mechanism living here, with its own pair of
     * constants and its own recovery edge. It is now the floor tier of
     * {@link CoopCadenceController}, pinned for as long as {@link #stateStreamFallbackActive} holds
     * and released through the same thirty-second clean window as any other downshift — so the
     * degraded mode has no code path of its own to develop its own bugs in. All that is left here is
     * the transport and the announcement.
     */
    private void applyStateStreamFallback(boolean active, String reason, boolean announce, long now) {
        if (active == stateStreamFallbackActive) {
            return;
        }
        stateStreamFallbackActive = active;
        if (!announce) {
            return;
        }
        CoopLog.info(CoopNetPump.class, "Coop state stream "
                + (active ? "switching to TCP fallback" : "returning to UDP")
                + " (" + reason
                + "; udpSilence=" + linkQuality.udpSilenceMillis(now)
                + " ms tcpSilence=" + linkQuality.tcpSilenceMillis(now)
                + " ms peerUdpOk=" + peerUdpInboundOkOrNull(now) + ")");
        if (active) {
            postFeed(FEED_FALLBACK, now,
                    "Co-op: UDP blocked on this connection - partner updates now travel over TCP.",
                    FEED_WARN_COLOR);
        } else {
            postFeed(FEED_FALLBACK_RECOVERED, now,
                    "Co-op: UDP path recovered - partner updates back to normal.", FEED_GOOD_COLOR);
        }
    }

    /** Degraded/recovered banner for the feed; the sustain windows live in {@link CoopLinkQuality}. */
    private void tickDegradedNotice(long now) {
        boolean was = linkQuality.degraded();
        boolean isDegraded = linkQuality.evaluateDegraded(now);
        if (was == isDegraded) {
            return;
        }
        if (isDegraded) {
            Integer rtt = linkQuality.rttMillis();
            postFeed(FEED_DEGRADED, now, "Co-op: connection degraded ("
                    + (rtt == null ? "rtt unknown" : rtt + " ms")
                    + ", " + linkQuality.lossPercent(now) + "% loss).", FEED_BAD_COLOR);
        } else {
            postFeed(FEED_DEGRADED_RECOVERED, now, "Co-op: connection recovered.", FEED_GOOD_COLOR);
        }
    }

    /**
     * The guest's one-shot connection doctor: logged as soon as inbound UDP is observed, or after
     * {@link #GUEST_DOCTOR_DEADLINE_MILLIS} without any. A guest whose router eats UDP otherwise sees
     * nothing but a partner mirror that never moves.
     */
    private void maybeLogGuestDoctor(long now, CoopDatagramStats stats) {
        if (guestDoctorLogged || service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        boolean udpObserved = stats.lastInboundDatagramAtMillis() >= linkQuality.resetAtMillis()
                && stats.lastInboundDatagramAtMillis() > 0L;
        if (!udpObserved && now - linkQuality.resetAtMillis() < GUEST_DOCTOR_DEADLINE_MILLIS) {
            return;
        }
        guestDoctorLogged = true;
        CoopLog.info(CoopNetPump.class, CoopConnectionDoctor.guestReport(
                guestConnectHost, guestConnectPort, service.isConnected(), udpObserved,
                linkQuality.snapshot(now), stats));
    }

    /**
     * Phase 20.2 link death. A half-open TCP socket after a NAT drop is not reported by the OS for one
     * to two minutes; that is a minute of a session that looks alive, sends into a black hole, and (on
     * the host) blocks the guest's reconnect with "Host already has an active connection". Declaring
     * it dead ourselves and closing it turns that into the ordinary disconnect edge: the guest's
     * 500 ms retry starts now, and the host opens its grace window while there is still a session
     * worth resuming.
     *
     * <p>The exemptions live in {@link CoopLinkQuality#evaluateLinkDeath}; what this supplies is the
     * evidence. The peer's combat state comes from the battle bridge rather than the shared combat
     * pause intent, because that intent is computed host-side only and the guest needs the same
     * answer. Runs on the 1 s supervision cadence, so the log line cannot repeat at frame rate.
     */
    private void maybeDeclareLinkDead(long now) {
        if (!service.isConnected() || !isGameplaySessionActive() || reconnect.active()) {
            return;
        }
        boolean peerInCombat = battleBridge.isRemoteBattleActive() || pauseCoordinator.eitherInCombat();
        CoopLinkQuality.DeathVerdict verdict =
                linkQuality.evaluateLinkDeath(now, peerInCombat, lastSaveCheckpointAtMillis);
        if (!verdict.dead()) {
            return;
        }
        CoopLog.info(CoopNetPump.class,
                "Coop declaring the TCP link dead and closing it as " + service.role()
                        + "; " + verdict.describe());
        service.dropActiveConnection(verdict.describe());
    }

    private void sendLinkStatus(long now, CoopDatagramStats stats) {
        // Phase 20.6: the intel page is fed on the LINK_STATUS cadence rather than per frame, which
        // is the whole reason 20.6 was scheduled to ride 20.1 — the data source already exists at
        // exactly the rate a page wants to be refreshed at.
        intelFeed.publishSession(service.role(), hudState(false).status(), remoteDisplayName());
        intelFeed.publishLink(linkQuality.snapshot(now), linkQuality.transport(),
                stateStreamTier.hz());
        if (!service.isConnected()) {
            return;
        }
        CoopMessages.Message status = CoopMessages.linkStatus(sessionState.sessionId(),
                service.nextSeq(), now, linkQuality.snapshot(now), linkQuality.transport(),
                stateStreamTier.hz(), stats);
        service.send(status);
        log("outbound", status);
    }

    /** Posts one feed notice per kind per {@link #FEED_MIN_INTERVAL_MILLIS}; a flapping link cannot spam. */
    private void postFeed(String kind, long now, String text, java.awt.Color color) {
        Long allowedAt = feedNextAtMillis.get(kind);
        if (allowedAt != null && now < allowedAt) {
            return;
        }
        feedNextAtMillis.put(kind, now + FEED_MIN_INTERVAL_MILLIS);
        coop.ui.CoopFeed.post(text, color);
        // Phase 20.6: the feed line scrolls away, the intel page's event log does not. Hooked here
        // rather than at each call site so a transition can never post a banner without also being
        // recorded — the two are the same event by construction.
        intelFeed.noteEvent(text);
    }

    /**
     * Inbound logging, level-gated by how much the sender has proved (red-team A4).
     *
     * <p>Before a peer is accepted, <em>anything</em> on this socket came from whoever managed to
     * connect: one INFO line per message is a log-write amplifier a stranger drives for free, on the
     * campaign thread, at whatever rate it can send frames. So the pre-acceptance default is DEBUG
     * (suppressed at Starsector's default level), with an exception for the control-plane types that
     * are the only things worth reading in that window and that arrive a handful of times per
     * connection. Once the session is live nothing changes: the existing high-frequency rule applies
     * and every INFO line the smoke tests read is still there.
     */
    private void logInbound(CoopMessages.Message message) {
        if (isGameplaySessionActive() || (peerAccepted() && isControlPlane(message.type()))) {
            log("inbound", message);
            return;
        }
        CoopLog.debug(CoopNetPump.class, "Coop net " + service.role() + " inbound "
                + message.type() + " seq=" + message.seq());
    }

    /** True once the lobby has taken this peer into the session's guest/host slot. */
    private boolean peerAccepted() {
        CoopLobbyState state = sessionState.connectionState();
        return state == CoopLobbyState.HOST_CONNECTED || state == CoopLobbyState.GUEST_CONNECTED;
    }

    /** Lobby / handshake / seed lock / resume: the pre-session control plane. */
    private static boolean isControlPlane(CoopMessages.Type type) {
        return switch (type) {
            case LOBBY_HELLO, LOBBY_CHALLENGE, LOBBY_ACCEPT, LOBBY_REJECT,
                 HANDSHAKE_MANIFEST, HANDSHAKE_RESULT,
                 SEED_LOCK_REQUEST, SEED_LOCK_ACK, SEED_LOCK_REJECT,
                 SESSION_RESUME_REQUEST, SESSION_RESUME_ACCEPT,
                 SESSION_RESUME_REJECT -> true;
            default -> false;
        };
    }

    /**
     * Whether a message that would make this side <em>answer</em> may be handled (red-team A4).
     *
     * <p>{@code PING} and {@code SESSION_RESUME_REQUEST} are the two types whose handlers reply
     * unconditionally, with no coalesce key, to whoever sent them. From a peer that has neither
     * proved itself in the lobby nor a grace window to resume into, that is a reply channel a
     * stranger opens by connecting, and every reply it earns is a permanent entry in an outbound
     * queue that never drops.
     *
     * <p>The gate is "has the lobby accepted this peer", not "is the session live": the seed-lock
     * window sits between those two, both roles legitimately ping through it, and it is precisely
     * the window where a half-open connection would otherwise stay invisible.
     */
    private boolean mayAnswerUnprovenPeer(CoopMessages.Type type) {
        if (peerAccepted() || isGameplaySessionActive() || reconnect.active()) {
            return true;
        }
        unprovenPeerAnswersRefused++;
        if (!unprovenPeerRefusalLogged) {
            unprovenPeerRefusalLogged = true;
            CoopLog.warn(CoopNetPump.class, "Coop refusing to answer " + type
                    + " from a peer with no session and no reconnect grace window"
                    + " (logged once per connection)");
        }
        return false;
    }

    /** Answers refused by {@link #mayAnswerUnprovenPeer}; test read. */
    long unprovenPeerAnswersRefused() {
        return unprovenPeerAnswersRefused;
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
                || type == CoopMessages.Type.LINK_STATUS
                || type == CoopMessages.Type.STATE_DATAGRAM
                || type == CoopMessages.Type.TIME_SNAPSHOT
                || type == CoopMessages.Type.FLEET_SNAPSHOT
                || type == CoopMessages.Type.NPC_FLEET_MOTION
                || type == CoopMessages.Type.BATTLE_STATUS;
    }
}
