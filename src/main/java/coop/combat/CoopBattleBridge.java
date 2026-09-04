package coop.combat;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import coop.fleet.CoopFleetSnapshot;
import coop.fleet.CoopFleetSnapshotFactory;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import coop.util.CoopLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Phase 14 solo own-fleet combat bridge. Owns both halves of a coop battle:
 *
 * <ul>
 *   <li><b>Engaging side</b> — detects battle start/end, becomes that battle's authority, and streams
 *       {@code BATTLE_BEGIN} / {@code BATTLE_STATUS} / {@code BATTLE_END} over reliable TCP.</li>
 *   <li><b>Spectator side</b> — asserts the shared combat pause and posts campaign banners for the
 *       partner's battle beginning and ending.</li>
 * </ul>
 *
 * <h2>Spectator UX: banners, not a panel (revised 2026-08-19)</h2>
 * The original spectator was a live-refreshing {@code CoopBattleStatusPanel} interaction dialog. It
 * was cancelled by user decision: the redraw-on-change rebuild still blinked, and in practice
 * spectating happens out of band (Discord screen-share) where a full-screen dialog on the watching
 * client is in the way. The spectator now gets a {@code CampaignUIAPI.addMessage} banner on
 * {@code BATTLE_BEGIN} and another on {@code BATTLE_END}, and is held by the shared combat pause
 * exactly as before. The {@code BATTLE_STATUS} stream, codec and kill feed are deliberately kept —
 * they are cheap, they work, and they are the raw material for the Phase 22 tactical-map observer;
 * the spectator just logs them at debug level now.
 *
 * <h2>Chosen detection seams (and why)</h2>
 * <ul>
 *   <li><b>Start = the combat plugin's first frame</b> ({@link #onCombatFrame}, driven by
 *       {@link CoopBattleStatusCombatPlugin}). The campaign-side alternatives do not work: campaign
 *       {@code EveryFrameScript}s do not advance in the {@code COMBAT} state, so a
 *       {@code Global.getCurrentState()} transition is unobservable from the pump; and
 *       {@code reportPlayerEngagement} fires inside {@code processEngagementResults}, i.e. <em>after</em>
 *       the battle, which is far too late to pause the partner. The combat plugin is the only seam
 *       that runs on the first frame of combat, and it hands over the {@code CombatEngineAPI} the
 *       status capture needs anyway. It is also the seam for the host-pushed {@code ENGAGE_GUEST}
 *       handoff, which since 2026-08-19 opens the <em>vanilla encounter dialog</em> rather than a
 *       battle ({@link #drivePendingEngage}): the guest may disengage or leave instead of fighting,
 *       so nothing can be announced until combat actually starts. Only the {@code startBattle}
 *       fallback, which forces a battle, still sends {@code BATTLE_BEGIN} up front.</li>
 *   <li><b>End = the first campaign frame after combat</b> ({@link #tickCampaign}), gated on having
 *       actually seen a combat frame. This catches every exit — victory, defeat, retreat, disengage —
 *       without depending on which engagement-result callback vanilla chose to fire.
 *       {@code reportBattleOccurred} / {@code reportPlayerEngagement} are wired in as
 *       <em>enrichment</em> (the outcome string in {@code BATTLE_END}), never as the trigger, because
 *       neither fires for a battle the player disengaged from before contact.</li>
 * </ul>
 *
 * <h2>Mid-combat send: flushable, and it is done here</h2>
 * The campaign pump does not advance during combat, so nothing would drain the outbound queue until
 * the battle ended (the spike observed exactly that backlog-flush on return). {@link #onCombatFrame}
 * therefore calls {@link CoopNetService#flushOutbound()} itself. That is safe rather than a thread
 * hack: combat plugin frames run on the same game thread as the campaign pump (the two states never
 * run concurrently), and independently of that {@code CoopNetService} is written for it — the
 * outbound queue is a {@code ConcurrentLinkedQueue} and every channel mutation is inside
 * {@code lifecycleLock} on non-blocking {@code java.nio} channels, so the call can neither block the
 * combat frame nor corrupt pump state. Inbound is deliberately <em>not</em> drained mid-combat: the
 * engaging client has no use for campaign traffic while fighting, and TCP holds it until return.
 *
 * <h2>Phase 15: the campaign result is built later than the battle ends, on purpose</h2>
 * {@code BATTLE_END} goes out the instant combat is over, because the spectator's banner and the
 * shared combat pause must not wait. The {@link CoopBattleResult} cannot be built there: on return
 * from combat the vanilla encounter dialog is still on screen (recovery, salvage, "engage again"),
 * and it is the dialog's <em>LEAVE</em> path that runs
 * {@code FleetEncounterContext.applyAfterBattleEffectsIfThereWasABattle} — the point where losing
 * fleets are actually despawned and rosters finalised. Reading the mirror before that reports a dead
 * fleet as a survivor. So {@link #endLocalBattle} parks a {@link PendingResult} and
 * {@link #drivePendingResult} builds and dispatches it on the first campaign frame with no dialog
 * open. <b>An open dialog is not a timeout condition:</b> a player picking through salvage and ship
 * recovery can hold the encounter dialog open for minutes, so while one is on screen the parked
 * result's clock is renewed every frame and {@link #notifyBattleConcluded} is re-fired on a
 * {@link #FREEZE_REFRESH_INTERVAL_MILLIS} cadence — keeping the guest's mirror freeze and the host's
 * engage cooldown alive for as long as the dialog actually lasts (otherwise the ~1 s
 * {@code NPC_FLEET_SET} stream would thaw and resurrect the kill mid-dialog, and the result built
 * afterwards would report it as a full-strength survivor). The
 * {@link #PENDING_ACTION_TIMEOUT_MILLIS} escape hatch therefore only measures the genuinely wedged
 * states — no dialog but the battle flag or game state never settled, or no sector to read. The
 * partner is held by the guest's screen-pause intent for that whole window, so nothing races it.
 *
 * <h2>Disconnect mid-combat</h2>
 * Finish locally, reconcile by authority. If the session dies while a local battle is running, the
 * {@code BATTLE_END} never sends; that is safe (the host's authoritative state resurrects whatever it
 * would have changed) and is logged loudly by {@link #discardedResultMessage}. The spectator gets the
 * "connection lost" banner. No freeze, no countdown, no rollback — the cancelled protocol depended on
 * programmatic save-loading that does not exist.
 */
public final class CoopBattleBridge {

    /** 2.5 Hz: inside the plan's 300-500 ms window, cheap on a 64 KB TCP frame budget. */
    static final long STATUS_INTERVAL_MILLIS = 400L;
    /**
     * Hard cap on ships in one {@code BATTLE_STATUS}. A 200-ship line-item body is ~14 KB, well under
     * the 64 KB frame limit; anything past this is a runaway and is truncated rather than dropped.
     */
    static final int MAX_STATUS_SHIPS = 200;
    /**
     * How long the spectator side keeps believing in a battle it has heard nothing about (red-team
     * B3). 75x {@link #STATUS_INTERVAL_MILLIS}: the engaging client sends a status every 400 ms for
     * the whole fight, so 30 s of silence is not a slow frame, it is a peer that died mid-combat.
     *
     * <p>Without this {@code remoteBattleActive} is set by {@code BATTLE_BEGIN} and cleared only by
     * {@code BATTLE_END} — which never arrives when the link dies during the fight. The flag is
     * exemption (a) of the Phase 20.2 link-death rule <em>and</em> the host's combat pause intent, so
     * a mid-combat death left the host paused in a phantom battle, unable to ever declare the link
     * dead, for as long as the process ran.
     */
    static final long REMOTE_BATTLE_SILENCE_TIMEOUT_MILLIS = 30_000L;
    /** Kill-feed depth carried in every snapshot (it is stateless — the newest snapshot must stand alone). */
    static final int KILL_FEED_DEPTH = 12;
    /**
     * Safety valve: if a local battle is "begun" but no combat frame ever arrives, something rejected
     * the state transition. Give up, release the partner, and log — never leave the shared clock stuck.
     */
    static final long BATTLE_START_TIMEOUT_MILLIS = 15000L;
    /**
     * Banners waiting for a campaign frame with a live UI. Bounded because a client that never gets a
     * UI (title screen, teardown) must not accumulate them; the oldest are dropped.
     */
    static final int MAX_PENDING_BANNERS = 8;
    /** A queued host-pushed action that cannot be honoured for this long is dropped. */
    static final long PENDING_ACTION_TIMEOUT_MILLIS = 60000L;
    /**
     * How often the parked battle result re-fires {@link #notifyBattleConcluded} while the post-battle
     * dialog is open, keeping the mirror freeze and engage cooldown renewed (class doc, Phase 15).
     */
    static final long FREEZE_REFRESH_INTERVAL_MILLIS = 2000L;

    private static final String NPC_MIRROR_TAG = "$coopNpcFleetId";

    /**
     * The bridge the in-combat plugin talks to. Static because {@link CoopBattleStatusCombatPlugin}
     * is instantiated by the engine per battle and has no other route back to the session. Replaced
     * whenever a new pump is built ({@code CoopModPlugin.onGameLoad}), so the last game load wins.
     */
    private static volatile CoopBattleBridge activeBridge;

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final CoopSharedPauseCoordinator pauseCoordinator;
    private final CoopPreBattleAutosave autosave = new CoopPreBattleAutosave();

    // ---- engaging side ----
    private boolean localBattleActive;
    private boolean sawCombatFrame;
    private boolean localBattleEndSent;
    private String localBattleId = "";
    private String localEnemySummary = "";
    private long localBattleBeganAtMillis;
    private long localStatusSeq;
    private long nextStatusAtMillis;
    private String pendingOutcome = "";
    /** Ships seen in the previous capture, so a vanished ship becomes a kill-feed entry. */
    private final Map<String, String> lastSeenShips = new LinkedHashMap<>();
    private final List<String> killFeed = new ArrayList<>();
    /** Host {@code coopFleetId}s this client is fighting, captured at battle start (Phase 15). */
    private final List<String> localBattleNpcFleetIds = new ArrayList<>();
    /** The finished battle whose campaign result is waiting for the encounter dialog to close. */
    private PendingResult pendingResult;
    /** Last {@link #notifyBattleConcluded} re-fire while the post-battle dialog held the result. */
    private long lastFreezeRefreshMillis;

    // ---- spectator side ----
    private boolean remoteBattleActive;
    private String remoteBattleId = "";
    /** Host {@code coopFleetId}s the partner reported fighting, from its {@code BATTLE_BEGIN}. */
    private final List<String> remoteBattleNpcFleetIds = new ArrayList<>();
    private CoopBattleStatus remoteStatus;
    /** Wall clock of the newest BATTLE_BEGIN/BATTLE_STATUS; see the silence timeout above. */
    private long remoteBattleSignalAtMillis;
    /** Last logged status digest, so the debug line is change-detected rather than per snapshot. */
    private String remoteStatusDigest = "";
    private final Deque<String> pendingBanners = new ArrayDeque<>();

    // ---- host-pushed actions queued on the guest ----
    private PendingEngage pendingEngage;
    private PendingDialog pendingDialog;
    /**
     * The {@code coopFleetId} of an {@code ENGAGE_GUEST} encounter whose vanilla dialog is on screen
     * (or about to be), empty when none. It is the handoff's "in progress" marker: it suppresses a
     * second handoff while the guest is still choosing — the host's 15 s {@code HANDOFF_GRACE} window
     * is sized for the old fire-and-fight path and lapses while a player reads an encounter — and it
     * is what tags the resulting battle {@code ENGAGE_GUEST}.
     */
    private String engageDialogFleetId = "";
    private String engageDialogFleetName = "";
    /** Set on the combat frame that the encounter produced, so its close needs no "no battle" line. */
    private boolean engageDialogBecameBattle;
    /**
     * The {@code coopFleetId} of a staged {@code DIALOG_BEGIN} encounter whose engagement shield is
     * currently down, empty when none. {@link CoopCustomsDialogStaging#stage} has to clear
     * {@code FLEET_IGNORES_OTHER_FLEETS} for the patrol-stop rules to fire, and nothing else ever
     * puts it back on a live mirror, so this is what remembers to.
     */
    private String customsDialogFleetId = "";

    private boolean sessionWasActive;

    /**
     * Where a finished local battle's {@link CoopBattleResult} goes. Wired by the pump: the guest
     * sends it as {@code BATTLE_RESULT}, the host hands it straight to
     * {@link CoopBattleResultReconciler} (never a message to itself).
     */
    private Consumer<CoopBattleResult> battleResultSink;
    /** The battle-lifecycle listener the mirror layer and the threat watcher hang off. */
    private BattleFleetListener battleFleetSink;

    /**
     * Lifecycle of the host {@code coopFleetId}s a battle involves, so the rest of the mod can act on
     * them without knowing anything about battles.
     */
    public interface BattleFleetListener {

        /**
         * A battle this client is piloting has just started against these host fleets.
         *
         * <p><b>Start, not end, and that ordering is load-bearing on the guest.</b> The campaign pump
         * does not advance during combat, so TCP piles up a backlog of {@code NPC_FLEET_SET} messages
         * that {@code CoopNetPump.drainInbound} flushes on the first frame back — <em>before</em>
         * {@code tickBattleBridge} runs. Freezing the mirrors only at battle end would therefore let
         * that backlog recreate the fleet the guest just killed, at full roster, and the result built
         * moments later would read the resurrected mirror and report the kill as a survivor. The
         * freeze has to be in place before the client ever returns to the campaign.
         */
        void onLocalBattleBegun(List<String> coopFleetIds);

        /**
         * A battle has ended — {@code localBattle} distinguishes this client's from the partner's.
         * The host restarts those fleets' engage cooldowns whichever side fought; the guest refreshes
         * the freeze on its own battles so a long fight cannot outlive it.
         */
        void onBattleConcluded(List<String> coopFleetIds, boolean localBattle);
    }

    public CoopBattleBridge(CoopNetService service, CoopSessionState session, LongSupplier clock,
                            CoopSharedPauseCoordinator pauseCoordinator) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pauseCoordinator = Objects.requireNonNull(pauseCoordinator, "pauseCoordinator");
        activeBridge = this;
    }

    /** The bridge the in-combat plugin reports to, or null when no coop pump exists. */
    public static CoopBattleBridge active() {
        return activeBridge;
    }

    public void setBattleResultSink(Consumer<CoopBattleResult> sink) {
        this.battleResultSink = sink;
    }

    public void setBattleFleetSink(BattleFleetListener sink) {
        this.battleFleetSink = sink;
    }

    // ---- pure decision helpers (unit-tested) -----------------------------------------------------

    /** The shared-pause combat intent: either side fighting holds both clocks. */
    public static boolean combatPauseIntent(boolean localBattleActive, boolean remoteBattleActive) {
        return localBattleActive || remoteBattleActive;
    }

    /** The loud log line for a battle result that can never be sent because the session died. */
    public static String discardedResultMessage(String battleId, String enemySummary) {
        return "Coop DISCARDED battle result: connection lost while battle " + battleId
                + " (" + (enemySummary == null || enemySummary.isEmpty() ? "unknown enemy" : enemySummary)
                + ") was still running. BATTLE_END was never sent; the battle finishes locally and the"
                + " host's authoritative state reconciles it on the next session (Phase 15).";
    }

    /** A local battle that never reached a combat frame within the timeout was rejected by the engine. */
    public static boolean isStartTimedOut(boolean sawCombatFrame, long ageMillis) {
        return !sawCombatFrame && ageMillis > BATTLE_START_TIMEOUT_MILLIS;
    }

    /**
     * The engaging client's battle ends on the first campaign frame after combat — but only once a
     * real combat frame has been seen, because {@code startBattle} queues the state transition and the
     * campaign pump can run one more frame first (observed in the Phase 14 spike).
     */
    public static boolean isLocalBattleOver(boolean localBattleActive, boolean sawCombatFrame,
                                            boolean inCampaignState) {
        return localBattleActive && sawCombatFrame && inCampaignState;
    }

    /** What {@code drivePendingResult} does with the parked result this frame (Phase 15). */
    enum PendingResultAction { HOLD_AND_RENEW, WAIT, BUILD, BUILD_TIMED_OUT }

    /**
     * Pure wait/build decision for the parked battle result, split out for tests. An open dialog
     * always holds-and-renews — it is never a timeout condition (class doc); the timeout only expires
     * against the genuinely wedged states (battle flag stuck, game state never back to campaign).
     */
    static PendingResultAction pendingResultAction(boolean dialogOpen, boolean battleActive,
                                                   boolean inCampaign, long queuedAtMillis,
                                                   long nowMillis) {
        if (dialogOpen) {
            return PendingResultAction.HOLD_AND_RENEW;
        }
        boolean timedOut = nowMillis - queuedAtMillis > PENDING_ACTION_TIMEOUT_MILLIS;
        if (!timedOut && (battleActive || !inCampaign)) {
            return PendingResultAction.WAIT;
        }
        return timedOut ? PendingResultAction.BUILD_TIMED_OUT : PendingResultAction.BUILD;
    }

    // ---- combat-thread entry point ---------------------------------------------------------------

    /**
     * One combat frame on the engaging client. Begins the coop battle on the first frame and then
     * captures/sends {@code BATTLE_STATUS} on the throttle. Never throws.
     */
    public void onCombatFrame(CombatEngineAPI engine, long nowMillis) {
        if (engine == null || !isSessionActive() || !service.isConnected()) {
            return;
        }
        try {
            if (!isCampaignBattle(engine)) {
                return;
            }
            if (!localBattleActive) {
                // The kind cannot be read off the engine — a handoff battle and a right-click battle
                // enter combat through the identical dialog seam — so it is carried in from the
                // encounter this client was pushed into, if any.
                boolean fromHandoff = !engageDialogFleetId.isEmpty();
                String enemy = describeEnemy(engine);
                // Phase 15: the opposing host-owned fleets have to be identified now, while the
                // battle still exists. After it, a destroyed fleet is simply gone and there is
                // nothing left to name.
                List<String> opponents = opponentCoopFleetIds(engine,
                        service.role() == CoopConnectionRole.HOST);
                if (opponents.isEmpty() && fromHandoff) {
                    opponents = List.of(engageDialogFleetId);
                }
                beginLocalBattle(engine,
                        fromHandoff ? CoopMessages.BattleKind.ENGAGE_GUEST : CoopMessages.BattleKind.PLAYER,
                        enemy.isEmpty() ? engageDialogFleetName : enemy,
                        opponents);
                engageDialogBecameBattle |= fromHandoff;
            }
            sawCombatFrame = true;
            if (nowMillis < nextStatusAtMillis) {
                return;
            }
            nextStatusAtMillis = nowMillis + STATUS_INTERVAL_MILLIS;
            sendStatus(engine);
            // Keep the guest's mirror freeze alive for the whole fight. Its timeout is wall-clock and
            // the campaign pump is not running to refresh it, so a battle longer than the timeout
            // would come back to an already-thawed mirror and the inbound set backlog would resurrect
            // the fleet before the result could be built. Re-marking is an idempotent map write.
            // Only the ids need re-marking here — the begin edge itself already fired from
            // beginLocalBattle, empty list included.
            if (!localBattleNpcFleetIds.isEmpty()) {
                notifyBattleBegun(localBattleNpcFleetIds);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle status capture failed", ex);
        }
    }

    /** Real campaign engagement only: never the refit simulator, mission sim, or title screen. */
    private static boolean isCampaignBattle(CombatEngineAPI engine) {
        try {
            return engine.isInCampaign() && !engine.isInCampaignSim() && !engine.isSimulation();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- campaign-thread entry point -------------------------------------------------------------

    /** Called once per pump frame (including while paused). Never throws. */
    public void tickCampaign(SectorAPI sector, boolean sessionActive, long nowMillis) {
        try {
            if (!sessionActive) {
                // Not a pure edge: a battle can be opened from the combat frame without this method
                // ever having seen an active session, and that battle still has to be released.
                if (sessionWasActive || localBattleActive || remoteBattleActive
                        || pendingEngage != null || pendingDialog != null || pendingResult != null
                        || !engageDialogFleetId.isEmpty() || !customsDialogFleetId.isEmpty()) {
                    onSessionEnded(sector);
                    sessionWasActive = false;
                }
                flushBanners(sector);
                return;
            }
            sessionWasActive = true;
            autosave.tick(sector);
            // Before the combat-intent write below, so a battle that timed out this frame releases
            // the shared pause on the same frame rather than one later.
            maybeExpireRemoteBattle(nowMillis);
            maybeEndLocalBattle(nowMillis);
            drivePendingResult(sector, nowMillis);
            driveEngageDialog(sector);
            driveCustomsDialog(sector);
            drivePendingEngage(sector, nowMillis);
            drivePendingDialog(sector, nowMillis);
            flushBanners(sector);
            if (service.role() == CoopConnectionRole.HOST) {
                pauseCoordinator.setEitherInCombat(combatPauseIntent(localBattleActive, remoteBattleActive));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle bridge tick failed", ex);
        }
    }

    /**
     * Red-team B3: drops a remote battle nobody has said anything about for
     * {@link #REMOTE_BATTLE_SILENCE_TIMEOUT_MILLIS}. Clearing the flag clears both things it feeds —
     * the link-death exemption and (via the caller, one statement later) the shared combat pause
     * intent — so the link is free to be declared dead and the world free to run.
     */
    private void maybeExpireRemoteBattle(long nowMillis) {
        if (!remoteBattleActive) {
            return;
        }
        long silence = nowMillis - remoteBattleSignalAtMillis;
        if (silence < REMOTE_BATTLE_SILENCE_TIMEOUT_MILLIS) {
            return;
        }
        String battleId = remoteBattleId;
        remoteBattleActive = false;
        remoteBattleSignalAtMillis = 0L;
        remoteStatus = null;
        remoteStatusDigest = "";
        remoteBattleId = "";
        List<String> concluded = List.copyOf(remoteBattleNpcFleetIds);
        remoteBattleNpcFleetIds.clear();
        notifyBattleConcluded(concluded, false);
        CoopLog.warn(CoopBattleBridge.class, "Coop no BATTLE_STATUS for " + silence
                + " ms on battleId=" + battleId + "; treating the partner's battle as over."
                + " The link-death exemption and the shared combat pause are released;"
                + " the host's authoritative state reconciles the fight itself.");
    }

    /** True while this client is piloting a coop battle (used by the threat watcher's gate). */
    public boolean isAnyCoopBattleActive() {
        return localBattleActive || remoteBattleActive;
    }

    /**
     * True while the <em>remote</em> player is in a battle, as last reported by
     * {@code BATTLE_BEGIN}/{@code BATTLE_END}. This is the truth source for "the peer's campaign pump
     * is legitimately stopped", which is exemption (a) of the Phase 20.2 link-death rule — the shared
     * combat pause intent is host-only and so cannot answer the same question on the guest.
     *
     * <p>Deliberately sticky across a dead link: the last thing a peer says before it goes quiet for a
     * battle is {@code BATTLE_BEGIN}, and that is exactly the state that must survive the silence it
     * explains.
     */
    public boolean isRemoteBattleActive() {
        return remoteBattleActive;
    }

    // ---- inbound -------------------------------------------------------------------------------

    /** Dispatches one Phase 14 message. The caller has already checked the session is active. */
    public void handle(CoopMessages.Message message) {
        switch (message.type()) {
            case BATTLE_BEGIN -> handleBattleBegin(message);
            case BATTLE_STATUS -> handleBattleStatus(message);
            case BATTLE_END -> handleBattleEnd(message);
            case ENGAGE_GUEST -> handleEngageGuest(message);
            case DIALOG_BEGIN -> handleDialogBegin(message);
            default -> {
                // not ours
            }
        }
    }

    private void handleBattleBegin(CoopMessages.Message message) {
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String battleId = payload.requiredString("battleId");
        String enemy = payload.requiredString("enemySummary");
        String location = payload.requiredString("locationName");
        remoteBattleActive = true;
        remoteBattleSignalAtMillis = clock.getAsLong();
        remoteBattleId = battleId;
        remoteBattleNpcFleetIds.clear();
        remoteBattleNpcFleetIds.addAll(
                splitIds(payload.requiredString("npcFleetIds")));
        remoteStatus = null;
        remoteStatusDigest = "";
        queueBanner(battleBeginBanner(partnerName(), enemy, location));
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_BEGIN received battleId=" + battleId
                + " enemy=" + enemy + " location=" + location);
    }

    private void handleBattleStatus(CoopMessages.Message message) {
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String battleId = payload.requiredString("battleId");
        long statusSeq = payload.requiredLong("statusSeq");
        if (!CoopBattleStatus.isNewer(battleId, statusSeq, remoteStatus)) {
            return;
        }
        remoteBattleSignalAtMillis = clock.getAsLong();
        remoteStatus = CoopBattleStatus.decode(battleId, statusSeq,
                payload.requiredLong("elapsedMillis"),
                payload.requiredString("ships"));
        if (!remoteBattleActive) {
            // Defensive: status without a begin (should be impossible over TCP) still counts as a
            // battle in progress, so the shared combat pause is asserted.
            remoteBattleActive = true;
            remoteBattleId = battleId;
        }
        logStatusIfChanged(remoteStatus);
    }

    /**
     * The stream is kept for the Phase 22 tactical-map observer but has no UI any more, so it lands as
     * a change-detected debug line. Never per snapshot: 2.5 Hz of unchanged lines is noise.
     */
    private void logStatusIfChanged(CoopBattleStatus status) {
        String digest = statusDigest(status);
        if (digest.equals(remoteStatusDigest)) {
            return;
        }
        remoteStatusDigest = digest;
        CoopLog.debug(CoopBattleBridge.class, "Coop BATTLE_STATUS " + digest);
    }

    /** Package-private + pure: the one-line summary of a snapshot ("partner 3/4 alive, enemy 1/5"). */
    static String statusDigest(CoopBattleStatus status) {
        if (status == null) {
            return "";
        }
        return "battleId=" + status.battleId()
                + " partner " + aliveCount(status.ownShips()) + "/" + status.ownShips().size()
                + " enemy " + aliveCount(status.enemyShips()) + "/" + status.enemyShips().size();
    }

    private static int aliveCount(List<CoopBattleStatus.ShipRecord> ships) {
        int alive = 0;
        for (CoopBattleStatus.ShipRecord ship : ships) {
            if (ship.state() == CoopBattleStatus.ShipState.ALIVE) {
                alive++;
            }
        }
        return alive;
    }

    private void handleBattleEnd(CoopMessages.Message message) {
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String battleId = payload.requiredString("battleId");
        String outcome = payload.requiredString("outcome");
        // The survivor line rides on the last status that arrived — no new message fields.
        queueBanner(battleEndBanner(partnerName(), outcome, survivorSummary(remoteStatus)));
        remoteBattleActive = false;
        remoteBattleSignalAtMillis = 0L;
        remoteStatus = null;
        remoteStatusDigest = "";
        remoteBattleId = "";
        // Phase 15 fold-in: restart those fleets' engage cooldowns (host) / freeze their mirrors
        // (guest) at battle END, not at BATTLE_RESULT time. The result waits for the partner's
        // encounter dialog to close, and a just-beaten fleet must not re-fire ENGAGE_GUEST into that
        // gap while reconciliation is still in flight.
        List<String> concluded = List.copyOf(remoteBattleNpcFleetIds);
        remoteBattleNpcFleetIds.clear();
        notifyBattleConcluded(concluded, false);
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_END received battleId=" + battleId
                + " outcome=" + outcome + " npcFleetIds=" + joinIds(concluded));
    }

    private void handleEngageGuest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String coopFleetId = payload.requiredString("coopFleetId");
        String fleetName = payload.requiredString("fleetName");
        if (localBattleActive || pendingEngage != null || !engageDialogFleetId.isEmpty()) {
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST ignored (battle already pending/active,"
                    + " or an earlier handoff's encounter is still open) coopFleetId=" + coopFleetId);
            return;
        }
        pendingEngage = new PendingEngage(coopFleetId, fleetName, clock.getAsLong());
        autosave.request("ENGAGE_GUEST vs " + fleetName);
        CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST queued coopFleetId=" + coopFleetId
                + " fleet=" + fleetName);
    }

    private void handleDialogBegin(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String coopFleetId = payload.requiredString("coopFleetId");
        CoopMessages.DialogKind kind = CoopMessages.DialogKind.valueOf(
                payload.requiredString("kind"));
        if (pendingDialog != null) {
            return;
        }
        pendingDialog = new PendingDialog(coopFleetId, kind, clock.getAsLong());
        CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN queued coopFleetId=" + coopFleetId
                + " kind=" + kind);
    }

    // ---- engaging side ---------------------------------------------------------------------------

    private void beginLocalBattle(CombatEngineAPI engine, CoopMessages.BattleKind kind,
                                  String enemySummary, List<String> npcFleetIds) {
        sessionWasActive = true;
        localBattleActive = true;
        localBattleEndSent = false;
        sawCombatFrame = engine != null;
        localBattleId = session.sessionId() + "-" + service.nextSeq();
        localEnemySummary = enemySummary == null ? "" : enemySummary;
        localBattleBeganAtMillis = clock.getAsLong();
        localStatusSeq = 0L;
        nextStatusAtMillis = 0L;
        pendingOutcome = "";
        lastSeenShips.clear();
        killFeed.clear();
        localBattleNpcFleetIds.clear();
        if (npcFleetIds != null) {
            localBattleNpcFleetIds.addAll(npcFleetIds);
        }
        CoopMessages.Message message = CoopMessages.battleBegin(
                session.sessionId(), service.nextSeq(), clock.getAsLong(),
                localBattleId, session.localPlayerId(), currentLocationName(),
                localEnemySummary, joinIds(localBattleNpcFleetIds), kind);
        service.send(message);
        service.flushOutbound();
        if (service.role() == CoopConnectionRole.HOST) {
            // Assert the combat pause immediately: the host pump is about to stop advancing, so the
            // frame that would otherwise compute it may never run.
            pauseCoordinator.setEitherInCombat(true);
        }
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_BEGIN sent battleId=" + localBattleId
                + " kind=" + kind + " enemy=" + localEnemySummary
                + " npcFleetIds=" + joinIds(localBattleNpcFleetIds));
        // Phase 15: freeze the guest's mirrors of these fleets now — see BattleFleetListener for why
        // waiting until battle end loses the race against the inbound NPC_FLEET_SET backlog.
        notifyBattleBegun(List.copyOf(localBattleNpcFleetIds));
    }

    private void maybeEndLocalBattle(long nowMillis) {
        if (!localBattleActive) {
            return;
        }
        long age = nowMillis - localBattleBeganAtMillis;
        boolean inCampaign = currentGameState() == GameState.CAMPAIGN;
        if (isStartTimedOut(sawCombatFrame, age)) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle " + localBattleId
                    + " never reached a combat frame within " + BATTLE_START_TIMEOUT_MILLIS
                    + "ms; releasing the shared combat pause");
            endLocalBattle("NOT_STARTED");
            return;
        }
        if (isLocalBattleOver(true, sawCombatFrame, inCampaign)) {
            endLocalBattle(pendingOutcome.isEmpty() ? "UNKNOWN" : pendingOutcome);
        }
    }

    private void endLocalBattle(String outcome) {
        String battleId = localBattleId;
        List<String> npcFleetIds = List.copyOf(localBattleNpcFleetIds);
        localBattleActive = false;
        sawCombatFrame = false;
        lastSeenShips.clear();
        localBattleNpcFleetIds.clear();
        if (service.role() == CoopConnectionRole.HOST) {
            pauseCoordinator.setEitherInCombat(combatPauseIntent(false, remoteBattleActive));
        }
        if (!isSessionActive() || !service.isConnected()) {
            CoopLog.warn(CoopBattleBridge.class, discardedResultMessage(battleId, localEnemySummary));
            return;
        }
        CoopMessages.Message message = CoopMessages.battleEnd(
                session.sessionId(), service.nextSeq(), clock.getAsLong(),
                battleId, session.localPlayerId(), outcome);
        service.send(message);
        service.flushOutbound();
        localBattleEndSent = true;
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_END sent battleId=" + battleId
                + " outcome=" + outcome);
        // Phase 15: tell the world the fight is over now (cooldowns / mirror freeze), and park the
        // campaign result until vanilla has finished applying it behind the encounter dialog.
        notifyBattleConcluded(npcFleetIds, true);
        lastFreezeRefreshMillis = clock.getAsLong();
        pendingResult = new PendingResult(battleId, outcome, npcFleetIds, clock.getAsLong());
    }

    /**
     * Builds and dispatches the parked {@link CoopBattleResult} once the encounter dialog that
     * finalises the battle has closed. See the class doc for why this cannot happen at
     * {@code BATTLE_END} time.
     */
    private void drivePendingResult(SectorAPI sector, long nowMillis) {
        if (pendingResult == null) {
            return;
        }
        if (sector == null) {
            // No world to read. Never build on the timeout path here: every fleet would look missing
            // and the host would despawn a set of live fleets on the strength of a null sector.
            if (nowMillis - pendingResult.queuedAtMillis() > PENDING_ACTION_TIMEOUT_MILLIS) {
                CoopLog.warn(CoopBattleBridge.class, "Coop discarded the battle result for battleId="
                        + pendingResult.battleId() + ": no sector to read the outcome from");
                pendingResult = null;
            }
            return;
        }
        PendingResultAction action = pendingResultAction(isDialogOpen(sector), localBattleActive,
                currentGameState() == GameState.CAMPAIGN, pendingResult.queuedAtMillis(), nowMillis);
        switch (action) {
            case HOLD_AND_RENEW:
                // The post-battle dialog is on screen — not a stuck state, however long the player
                // browses salvage. Renew the parked clock so the timeout only measures what happens
                // after the dialog closes, and keep the freeze/cooldown alive (class doc).
                pendingResult = new PendingResult(pendingResult.battleId(), pendingResult.outcome(),
                        pendingResult.npcFleetIds(), nowMillis);
                if (nowMillis - lastFreezeRefreshMillis >= FREEZE_REFRESH_INTERVAL_MILLIS) {
                    lastFreezeRefreshMillis = nowMillis;
                    notifyBattleConcluded(pendingResult.npcFleetIds(), true);
                }
                return;
            case WAIT:
                return;
            case BUILD_TIMED_OUT:
                CoopLog.warn(CoopBattleBridge.class, "Coop building the battle result for battleId="
                        + pendingResult.battleId() + " on the timeout path (no dialog open, but the"
                        + " battle flag or game state never settled); the reported rosters may be"
                        + " pre-finalisation");
                break;
            case BUILD:
                break;
        }
        PendingResult parked = pendingResult;
        pendingResult = null;
        CoopBattleResult result = buildResult(sector, parked);
        CoopLog.info(CoopBattleBridge.class, "Coop battle result ready battleId=" + result.battleId()
                + " destroyed=" + result.destroyedFleetIds().size()
                + " survivors=" + result.survivingFleets().size()
                + (result.isEmpty() ? " (nothing changed; sent anyway so pacing restarts)" : ""));
        Consumer<CoopBattleResult> sink = battleResultSink;
        if (sink == null) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle result for battleId=" + result.battleId()
                    + " has nowhere to go (no result sink wired); it is discarded");
            return;
        }
        sink.accept(result);
    }

    /**
     * Reads the post-battle state of every host fleet this battle involved.
     *
     * <p><b>Destroyed vs survived is read off the world, not off the fight.</b> A fleet that can no
     * longer be found, is not alive, or has no members left is destroyed; anything else is a survivor
     * reported with the roster it has right now, which is what makes a partial outcome (escape,
     * disengage, a fight broken off after two kills) reconcile correctly.
     *
     * <p><b>"Unreadable" is neither.</b> A fleet whose lookup or roster read threw is dropped from the
     * result entirely rather than reported as destroyed: the receiver despawns what this list names
     * ({@link CoopBattleResultReconciler}), so an engine hiccup on this side would delete a live
     * authoritative fleet. Same reasoning as {@link #isAlive} and the null-sector guard in
     * {@link #drivePendingResult} — every unknown resolves away from destruction.
     */
    private CoopBattleResult buildResult(SectorAPI sector, PendingResult parked) {
        List<String> destroyed = new ArrayList<>();
        List<CoopBattleResult.SurvivingFleet> survivors = new ArrayList<>();
        for (String coopFleetId : parked.npcFleetIds()) {
            if (coopFleetId == null || coopFleetId.isEmpty()) {
                continue;
            }
            FleetLookup lookup = lookUpBattleFleet(sector, coopFleetId);
            if (lookup.unreadable()) {
                CoopLog.warn(CoopBattleBridge.class, "Coop omitted coopFleetId=" + coopFleetId
                        + " from the battle result: the world could not be read, and an unreadable"
                        + " fleet must not be reported as destroyed");
                continue;
            }
            CampaignFleetAPI fleet = lookup.fleet();
            if (fleet == null || !isAlive(fleet)) {
                destroyed.add(coopFleetId);
                continue;
            }
            List<CoopFleetSnapshot.Member> members = safeMembers(fleet);
            if (members == null) {
                CoopLog.warn(CoopBattleBridge.class, "Coop omitted coopFleetId=" + coopFleetId
                        + " from the battle result: its roster could not be read in full off a fleet"
                        + " that is still alive (the read threw, came back empty, or came back"
                        + " short of the ships the engine lists)");
                continue;
            }
            if (members.isEmpty()) {
                destroyed.add(coopFleetId);
            } else {
                survivors.add(new CoopBattleResult.SurvivingFleet(coopFleetId, members));
            }
        }
        return new CoopBattleResult(parked.battleId(), session.localPlayerId(), parked.outcome(),
                localFleetSize(sector), destroyed, survivors);
    }

    /**
     * Fires on every battle, including one whose opponents resolved to no coop fleet ids at all (an
     * untagged fleet, a context with no other fleet). The listener does role-independent per-battle
     * work — the pre-battle roster capture the ship-loss stats diff against — that an empty list must
     * not skip; its per-id loops are already no-ops on one.
     */
    private void notifyBattleBegun(List<String> coopFleetIds) {
        BattleFleetListener sink = battleFleetSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onLocalBattleBegun(coopFleetIds == null ? List.of() : coopFleetIds);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle-begun notification failed", ex);
        }
    }

    /** See {@link #notifyBattleBegun}: an empty id list is a battle too, and the sink knows it. */
    private void notifyBattleConcluded(List<String> coopFleetIds, boolean localBattle) {
        BattleFleetListener sink = battleFleetSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onBattleConcluded(coopFleetIds == null ? List.of() : coopFleetIds, localBattle);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle-concluded notification failed", ex);
        }
    }

    /**
     * Campaign-event enrichment (never the trigger): records the outcome so the {@code BATTLE_END} the
     * next campaign frame sends carries something better than {@code UNKNOWN}.
     */
    public void onBattleOccurred(boolean playerWon) {
        if (localBattleActive) {
            pendingOutcome = playerWon ? "WIN" : "LOSS";
        }
    }

    /** Same, from {@code reportPlayerEngagement}: the engagement ran to a result. */
    public void onPlayerEngagement(String outcome) {
        if (localBattleActive && outcome != null && !outcome.isEmpty()) {
            pendingOutcome = outcome;
        }
    }

    private void sendStatus(CombatEngineAPI engine) {
        List<CoopBattleStatus.ShipRecord> ships = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        boolean truncated = false;
        for (ShipAPI ship : engine.getShips()) {
            if (ship == null || ship.isFighter() || ship.isDrone() || ship.isShuttlePod()) {
                continue;
            }
            if (ships.size() >= MAX_STATUS_SHIPS) {
                truncated = true;
                break;
            }
            boolean enemy = ship.getOwner() != 0;
            String name = shipDisplayName(ship);
            String id = safeShipId(ship);
            seen.put(id, (enemy ? "Enemy " : "") + name);
            ships.add(new CoopBattleStatus.ShipRecord(
                    id,
                    hullIdOf(ship),
                    name,
                    enemy,
                    safeHullLevel(ship),
                    safeFluxLevel(ship),
                    stateOf(ship)));
        }
        if (truncated) {
            CoopLog.warn(CoopBattleBridge.class, "Coop BATTLE_STATUS truncated at "
                    + MAX_STATUS_SHIPS + " ships");
        }
        updateKillFeed(seen);

        CoopBattleStatus status = new CoopBattleStatus(localBattleId, ++localStatusSeq,
                elapsedMillis(engine), ships, tailOfKillFeed());
        service.send(CoopMessages.battleStatus(session.sessionId(), service.nextSeq(), clock.getAsLong(),
                status.battleId(), status.statusSeq(), status.elapsedMillis(), status.encodeBody()));
        // The pump is frozen for the whole battle, so nothing else will push these bytes out.
        service.flushOutbound();
    }

    /**
     * Destroyed ships leave {@code engine.getShips()} entirely, so the kill feed is the set difference
     * against the previous capture rather than a state read.
     */
    private void updateKillFeed(Map<String, String> seen) {
        if (!lastSeenShips.isEmpty()) {
            for (Map.Entry<String, String> previous : lastSeenShips.entrySet()) {
                if (!seen.containsKey(previous.getKey())) {
                    killFeed.add(previous.getValue() + " destroyed");
                }
            }
        }
        lastSeenShips.clear();
        lastSeenShips.putAll(seen);
    }

    private List<String> tailOfKillFeed() {
        int from = Math.max(0, killFeed.size() - KILL_FEED_DEPTH);
        return new ArrayList<>(killFeed.subList(from, killFeed.size()));
    }

    // ---- host-pushed guest actions ---------------------------------------------------------------

    /**
     * The host-pushed handoff opens the <b>vanilla encounter dialog</b> against the guest's local
     * mirror, not a battle (revised 2026-08-19). Being caught by a pirate is a conversation in
     * vanilla — "the fleet moves to engage", then engage / attempt to disengage / the story-point
     * clean getaway / comm link — and the previous {@code startBattle} call skipped all of it and
     * dropped the guest onto the deployment screen with no choice, which is not the same game.
     *
     * <p>{@code showInteractionDialog(SectorEntityToken)} runs the plugin picker, which lands a plain
     * {@code CampaignFleetAPI} on {@code FleetInteractionDialogPluginImpl}
     * ({@code CoreCampaignPluginImpl}:109-111) — the same one-argument overload the customs path has
     * used in production since the Phase 14 spike. Posture staging is
     * {@link CoopEngageDialogStaging}; from there every option, every disengage roll and the pursuit
     * round are vanilla's, in vanilla's own dialog instance.
     *
     * <p><b>The engagement shield needs nothing here.</b> The pump re-asserts it the moment any
     * dialog owns the screen ({@code CoopNetPump.playerEngagementTargetOrNull} returns null), and
     * that is fine: {@code FleetInteractionDialogPluginImpl} never calls {@code canBeEngaged()} or
     * reads the {@code noCombat} fader anywhere, and its disengage/pursuit rounds are option-panel
     * swaps inside the same plugin instance ({@code ATTEMPT_TO_DISENGAGE} flips the fleet goals and
     * falls through to {@code CONTINUE_INTO_BATTLE} &rarr; {@code dialog.startBattle}) — the dialog
     * never closes and re-enters through {@code BaseLocation}'s gated initiation block. This is the
     * identical path the guest's own right-click engagement already takes in production.
     *
     * <p><b>No {@code BATTLE_BEGIN} is sent here</b>, unlike the fallback: the guest may well not
     * fight. The combat plugin's first frame sends it if a battle happens, tagged
     * {@code ENGAGE_GUEST} via {@link #engageDialogFleetId}. That also means the 15 s
     * {@link #BATTLE_START_TIMEOUT_MILLIS} watchdog cannot fire spuriously while the guest reads the
     * encounter — no local battle is open to time out. The host is held throughout by the guest's
     * screen-pause intent (an interaction dialog is a blocking screen), and if the guest fights, the
     * combat intent takes over on the same {@code BATTLE_BEGIN}.
     */
    private void drivePendingEngage(SectorAPI sector, long nowMillis) {
        if (pendingEngage == null || sector == null) {
            return;
        }
        if (nowMillis - pendingEngage.queuedAtMillis() > PENDING_ACTION_TIMEOUT_MILLIS) {
            CoopLog.warn(CoopBattleBridge.class, "Coop ENGAGE_GUEST dropped (timed out waiting for a"
                    + " clear campaign frame) coopFleetId=" + pendingEngage.coopFleetId());
            pendingEngage = null;
            autosave.cancel();
            return;
        }
        if (localBattleActive || isDialogOpen(sector) || currentGameState() != GameState.CAMPAIGN) {
            return;
        }
        // The pre-battle autosave is silently skipped while a dialog is open, so it runs here (the
        // frame we already know is dialog-free) and the battle waits one more frame for it.
        if (autosave.isPending()) {
            autosave.tick(sector);
            return;
        }
        CampaignFleetAPI mirror = findNpcMirror(sector, pendingEngage.coopFleetId());
        CampaignFleetAPI player = playerFleetOrNull(sector);
        if (mirror == null || player == null) {
            CoopLog.warn(CoopBattleBridge.class, "Coop ENGAGE_GUEST dropped: no local mirror for"
                    + " coopFleetId=" + pendingEngage.coopFleetId());
            pendingEngage = null;
            // Nothing to insure any more: a dropped handoff must not leave a save queued for the next
            // dialog-free frame (same reasoning as the timeout path above).
            autosave.cancel();
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            return;
        }
        String fleetName = pendingEngage.fleetName();
        String coopFleetId = pendingEngage.coopFleetId();
        pendingEngage = null;

        String staged = CoopEngageDialogStaging.stage(mirror);
        CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST staging "
                + CoopEngageDialogStaging.describePreconditions(mirror, player)
                + " flags: " + staged);
        boolean shown = false;
        String failure = "showInteractionDialog returned false";
        try {
            shown = ui.showInteractionDialog(mirror);
        } catch (RuntimeException | LinkageError ex) {
            failure = "showInteractionDialog threw: " + ex;
            CoopLog.warn(CoopBattleBridge.class, "Coop ENGAGE_GUEST showInteractionDialog threw", ex);
        }
        if (shown) {
            engageDialogFleetId = coopFleetId;
            engageDialogFleetName = fleetName;
            engageDialogBecameBattle = false;
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST opened the vanilla encounter dialog"
                    + " vs " + fleetName + " coopFleetId=" + coopFleetId
                    + "; the guest chooses from here (no battle is forced)");
            return;
        }
        startBattleFallback(ui, player, mirror, fleetName, coopFleetId, failure);
    }

    /**
     * Explicit, loud fallback for a handoff whose encounter dialog will not open. It is the pre-2026-08-19
     * behaviour — a direct {@code startBattle} onto the deployment screen — kept precisely so the
     * handoff never dies silently: the host has already decided this fleet caught the guest, and a
     * dropped handoff would leave a hostile sitting on top of the guest with nothing happening.
     */
    private void startBattleFallback(CampaignUIAPI ui, CampaignFleetAPI player, CampaignFleetAPI mirror,
                                     String fleetName, String coopFleetId, String failure) {
        CoopLog.warn(CoopBattleBridge.class, "Coop ENGAGE_GUEST could not open the vanilla encounter"
                + " dialog vs " + fleetName + " (" + failure + "); falling back to a direct startBattle"
                + " — the guest gets the deployment screen with no encounter options");
        // On this path BATTLE_BEGIN goes out (and is flushed) BEFORE the state transition, so the
        // partner is paused and watching for the whole battle rather than from the moment it ends.
        beginLocalBattle(null, CoopMessages.BattleKind.ENGAGE_GUEST, fleetName, List.of(coopFleetId));
        try {
            ui.startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, mirror, FleetGoal.ATTACK));
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST startBattle issued vs " + fleetName);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop ENGAGE_GUEST startBattle threw", ex);
            endLocalBattle("START_FAILED");
        }
    }

    /**
     * Watches the handoff encounter to its end. There are only two outcomes and neither needs a
     * message of its own: the guest fought (the combat seam already sent
     * {@code BATTLE_BEGIN}/{@code BATTLE_END} and tagged them {@code ENGAGE_GUEST}), or it did not —
     * it disengaged cleanly, was let go, or picked Leave. The second case resolves the handoff
     * silently, which is correct: the host sent an invitation, not an order, and its per-fleet 120 s
     * cooldown re-arms on its own so the same hostile can catch the guest again later.
     */
    private void driveEngageDialog(SectorAPI sector) {
        if (engageDialogFleetId.isEmpty()) {
            return;
        }
        if (localBattleActive || isDialogOpen(sector) || currentGameState() != GameState.CAMPAIGN) {
            // Still on screen, or the battle it produced is running / about to run.
            return;
        }
        if (engageDialogBecameBattle) {
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST encounter resolved into a battle"
                    + " coopFleetId=" + engageDialogFleetId);
        } else {
            String cleared = CoopEngageDialogStaging.clear(findNpcMirror(sector, engageDialogFleetId));
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST handoff resolved without a battle"
                    + " (disengaged or left) coopFleetId=" + engageDialogFleetId
                    + " fleet=" + engageDialogFleetName + "; no battle messages were sent, posture"
                    + " cleared: " + cleared);
        }
        engageDialogFleetId = "";
        engageDialogFleetName = "";
        engageDialogBecameBattle = false;
    }

    private void drivePendingDialog(SectorAPI sector, long nowMillis) {
        if (pendingDialog == null || sector == null) {
            return;
        }
        if (nowMillis - pendingDialog.queuedAtMillis() > PENDING_ACTION_TIMEOUT_MILLIS) {
            CoopLog.warn(CoopBattleBridge.class, "Coop DIALOG_BEGIN dropped (timed out waiting for a"
                    + " clear campaign frame) coopFleetId=" + pendingDialog.coopFleetId());
            pendingDialog = null;
            return;
        }
        if (localBattleActive || remoteBattleActive || isDialogOpen(sector)
                || currentGameState() != GameState.CAMPAIGN) {
            return;
        }
        CampaignFleetAPI mirror = findNpcMirror(sector, pendingDialog.coopFleetId());
        CampaignUIAPI ui = sector.getCampaignUI();
        if (mirror == null || ui == null) {
            CoopLog.warn(CoopBattleBridge.class, "Coop DIALOG_BEGIN dropped: no local mirror for"
                    + " coopFleetId=" + pendingDialog.coopFleetId());
            pendingDialog = null;
            return;
        }
        CoopMessages.DialogKind kind = pendingDialog.kind();
        String coopFleetId = pendingDialog.coopFleetId();
        pendingDialog = null;
        String staged = CoopCustomsDialogStaging.stage(sector, mirror, kind);
        // Staging just took the mirror's engagement shield down; own that until the encounter closes.
        customsDialogFleetId = coopFleetId;
        CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN staging kind=" + kind
                + " " + CoopCustomsDialogStaging.describePreconditions(mirror) + " flags: " + staged);
        boolean shown = false;
        try {
            shown = ui.showInteractionDialog(mirror);
            CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN showInteractionDialog returned="
                    + shown + " target=" + safeName(mirror));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop DIALOG_BEGIN showInteractionDialog threw", ex);
        }
        if (!shown) {
            // No encounter to protect: put the shield back on the same frame rather than leaving the
            // mirror eligible for battle pull-in until some later dialog-free frame.
            restoreCustomsShield(sector);
        }
    }

    /**
     * Puts back the engagement shield {@link CoopCustomsDialogStaging#stage} had to clear, once the
     * encounter it staged is off screen (whether it ended in a fight, a scan, or nothing).
     *
     * <p>Without this the mirror carries {@code FLEET_IGNORES_OTHER_FLEETS} cleared for the rest of
     * its life — {@code CoopFleetMirror} only sets that flag on the frame it creates the fleet, and a
     * live mirror takes the refresh-identity early return — so vanilla's
     * {@code FleetInteractionDialogPluginImpl.pullInNearbyFleets} would drag it into the guest's next
     * unrelated battle and the resulting {@code BATTLE_RESULT} would apply losses to a host fleet that
     * never fought. Same shape as {@link #driveEngageDialog}'s posture clear.
     */
    private void driveCustomsDialog(SectorAPI sector) {
        if (customsDialogFleetId.isEmpty() || sector == null) {
            return;
        }
        if (localBattleActive || isDialogOpen(sector) || currentGameState() != GameState.CAMPAIGN) {
            return;
        }
        restoreCustomsShield(sector);
    }

    /** Best-effort re-assert of the staged mirror's shield; clears the marker either way. */
    private void restoreCustomsShield(SectorAPI sector) {
        if (customsDialogFleetId.isEmpty()) {
            return;
        }
        String coopFleetId = customsDialogFleetId;
        customsDialogFleetId = "";
        String restored = CoopCustomsDialogStaging.restoreEngagementShield(
                sector == null ? null : findNpcMirror(sector, coopFleetId));
        CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN encounter resolved coopFleetId="
                + coopFleetId + "; engagement shield restored: " + restored);
    }

    // ---- spectator banners -----------------------------------------------------------------------

    /**
     * Posts every queued banner as soon as a campaign UI exists. Banners are queued rather than sent
     * from the inbound handler because messages arrive on pump frames that may have no sector yet
     * (load, teardown), and {@code addMessage} on a half-built UI is not worth the risk.
     */
    private void flushBanners(SectorAPI sector) {
        if (pendingBanners.isEmpty() || sector == null) {
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            return;
        }
        String banner;
        while ((banner = pendingBanners.poll()) != null) {
            addMessage(ui, banner);
        }
    }

    private void queueBanner(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        while (pendingBanners.size() >= MAX_PENDING_BANNERS) {
            pendingBanners.poll();
        }
        pendingBanners.add(text);
    }

    /** "Coop: Ayo is fighting Pirate Raiders in Corvus." */
    static String battleBeginBanner(String partnerName, String enemySummary, String locationName) {
        String enemy = enemySummary == null || enemySummary.isEmpty() ? "an enemy fleet" : enemySummary;
        String location = locationName == null || locationName.isEmpty() ? "" : " in " + locationName;
        return "Coop: " + partnerName + " is fighting " + enemy + location + ".";
    }

    /** "Coop: Ayo won the battle (last report: 3 of 4 ships standing)." */
    static String battleEndBanner(String partnerName, String outcome, String survivorSummary) {
        String tail = survivorSummary == null || survivorSummary.isEmpty()
                ? "" : " (last report: " + survivorSummary + ")";
        return "Coop: " + partnerName + " " + outcomePhrase(outcome) + tail + ".";
    }

    /**
     * The outcome string is whatever vanilla's engagement callbacks produced ({@code WIN} /
     * {@code LOSS} from {@code reportBattleOccurred}, an {@code EngagementOutcome} name from
     * {@code reportPlayerEngagement}) or one of the bridge's own fallbacks; anything unrecognised
     * degrades to a neutral phrase rather than printing an enum at the player.
     */
    private static String outcomePhrase(String outcome) {
        String normalized = outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("DISENGAGE")) {
            return "disengaged";
        }
        if (normalized.contains("WIN") || normalized.contains("VICTOR")) {
            return "won the battle";
        }
        if (normalized.contains("LOSS") || normalized.contains("LOSE") || normalized.contains("DEFEAT")) {
            return "lost the battle";
        }
        return "finished the battle";
    }

    /**
     * Partner-fleet survivors as of the newest {@code BATTLE_STATUS} that arrived — deliberately
     * approximate (the last snapshot is up to {@link #STATUS_INTERVAL_MILLIS} old and destroyed ships
     * leave the capture entirely), which is why the banner labels it "last report". Empty when no
     * status ever arrived.
     */
    static String survivorSummary(CoopBattleStatus status) {
        if (status == null || status.ownShips().isEmpty()) {
            return "";
        }
        List<CoopBattleStatus.ShipRecord> own = status.ownShips();
        return aliveCount(own) + " of " + own.size() + " ships standing";
    }

    private String partnerName() {
        String name = session.remoteName();
        return name == null || name.isEmpty() ? "Your partner" : name;
    }

    private static void addMessage(CampaignUIAPI ui, String text) {
        try {
            ui.addMessage(text);
        } catch (RuntimeException | LinkageError ignored) {
            // banner is best-effort
        }
    }

    // ---- session lifecycle -----------------------------------------------------------------------

    private void onSessionEnded(SectorAPI sector) {
        if (localBattleActive && !localBattleEndSent) {
            CoopLog.warn(CoopBattleBridge.class, discardedResultMessage(localBattleId, localEnemySummary));
        }
        localBattleActive = false;
        sawCombatFrame = false;
        lastSeenShips.clear();
        killFeed.clear();
        localBattleNpcFleetIds.clear();
        remoteBattleNpcFleetIds.clear();
        if (pendingResult != null) {
            CoopLog.warn(CoopBattleBridge.class, discardedResultMessage(
                    pendingResult.battleId(), localEnemySummary));
            pendingResult = null;
        }
        pendingEngage = null;
        pendingDialog = null;
        // The handoff that queued this save is gone with the session; without the cancel the request
        // survives into the next one and lands a multi-second "pre-battle" save with no battle
        // anywhere — and burns the throttle the next real handoff needs.
        autosave.cancel();
        engageDialogFleetId = "";
        engageDialogFleetName = "";
        engageDialogBecameBattle = false;
        restoreCustomsShield(sector);
        if (remoteBattleActive) {
            remoteBattleActive = false;
            remoteBattleSignalAtMillis = 0L;
            remoteStatus = null;
            remoteStatusDigest = "";
            queueBanner("Coop: connection lost. Your partner's battle finishes on their machine;"
                    + " the host's authoritative state reconciles it next session.");
            CoopLog.warn(CoopBattleBridge.class, "Coop session lost while the partner was fighting"
                    + " battleId=" + remoteBattleId + "; spectator released");
        }
        pauseCoordinator.setEitherInCombat(false);
    }

    // ---- Phase 15 helpers ------------------------------------------------------------------------

    /** Comma-joined for the flat envelope (it has no arrays). Empty ids are dropped. */
    static String joinIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        return joined.toString();
    }

    /** Reverses {@link #joinIds}, de-duplicating and dropping blanks. */
    static List<String> splitIds(String joined) {
        List<String> ids = new ArrayList<>();
        if (joined == null || joined.isEmpty()) {
            return ids;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : joined.split(",", -1)) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                unique.add(id);
            }
        }
        ids.addAll(unique);
        return ids;
    }

    /**
     * The host {@code coopFleetId}s on the other side of this battle.
     *
     * <p>{@code BattleAPI.getNonPlayerSide()} is the authoritative list when the campaign battle
     * exists (it covers fleets that joined the engagement, not just the one the player clicked);
     * {@code BattleCreationContext.getOtherFleet()} is the fallback, which is what the
     * {@code startBattle} path produces.
     *
     * <p><b>The guest only ever reports mirror-tagged ids.</b> A guest-side fleet that is not a
     * mirror has a locally minted id that means nothing to the host, and reporting it could name an
     * unrelated host fleet. The host has no such problem: its fleets' engine ids <em>are</em> the
     * {@code coopFleetId}s the Phase 9 set is keyed on.
     */
    static List<String> opponentCoopFleetIds(CombatEngineAPI engine, boolean isHost) {
        List<CampaignFleetAPI> side = new ArrayList<>();
        try {
            BattleCreationContext context = engine.getContext();
            CampaignFleetAPI other = context == null ? null : context.getOtherFleet();
            if (other == null) {
                return List.of();
            }
            BattleAPI battle = null;
            try {
                battle = other.getBattle();
            } catch (RuntimeException | LinkageError ignored) {
                // fall through to the single-fleet context
            }
            List<CampaignFleetAPI> nonPlayer = battle == null ? null : battle.getNonPlayerSide();
            if (nonPlayer != null && !nonPlayer.isEmpty()) {
                side.addAll(nonPlayer);
            } else {
                side.add(other);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop could not enumerate the battle's opposing"
                    + " fleets; its campaign result will report nothing", ex);
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (CampaignFleetAPI fleet : side) {
            if (fleet == null) {
                continue;
            }
            String mirrorId = mirrorCoopFleetId(fleet);
            if (!mirrorId.isEmpty()) {
                ids.add(mirrorId);
            } else if (isHost) {
                String id = safeId(fleet);
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The outcome of one fleet lookup. {@code unreadable} is the third state the caller needs:
     * "the scan threw" is not the same answer as "this fleet is gone".
     */
    record FleetLookup(CampaignFleetAPI fleet, boolean unreadable) {
    }

    /** A host fleet by {@code coopFleetId}: the guest's mirror tag first, then a raw engine id. */
    static FleetLookup lookUpBattleFleet(SectorAPI sector, String coopFleetId) {
        if (sector == null || coopFleetId == null || coopFleetId.isEmpty()) {
            return new FleetLookup(null, false);
        }
        CampaignFleetAPI mirror = findNpcMirror(sector, coopFleetId);
        if (mirror != null) {
            return new FleetLookup(mirror, false);
        }
        try {
            for (LocationAPI location : sector.getAllLocations()) {
                if (location == null) {
                    continue;
                }
                for (CampaignFleetAPI fleet : location.getFleets()) {
                    if (fleet != null && coopFleetId.equals(safeId(fleet))) {
                        return new FleetLookup(fleet, false);
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop fleet lookup failed for " + coopFleetId, ex);
            return new FleetLookup(null, true);
        }
        return new FleetLookup(null, false);
    }

    /**
     * This fleet's replicable roster, or {@code null} when reading it failed.
     *
     * <p>The distinction is load-bearing. {@code CoopFleetSnapshotFactory.captureMembers} answers both
     * "this fleet has no ships left" and "I could not read this fleet" with an empty list, and
     * {@link #buildResult} turns the first into a destruction report — so without the engine roster
     * cross-check below, one throw on this side despawns a live fleet on the other. A roster the
     * engine says is non-empty but that captured nothing is reported as unreadable, not as a loss.
     *
     * <p>Same reasoning one step further (2026-09-04): a roster that captured <em>some</em> of its
     * ships is short by the rest, and {@code CoopBattleResultReconciler.applySurvivingRoster} deletes
     * every ship the survivor list does not name. A partial read is therefore unreadable here too —
     * the omission costs one unreconciled fleet, the alternative costs real ships.
     */
    private static List<CoopFleetSnapshot.Member> safeMembers(CampaignFleetAPI fleet) {
        List<FleetMemberAPI> engineRoster;
        try {
            engineRoster = fleet.getFleetData().getMembersListCopy();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
        CoopFleetSnapshotFactory.Capture capture;
        try {
            capture = CoopFleetSnapshotFactory.captureRoster(fleet);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
        if (capture.partial()) {
            return null;
        }
        List<CoopFleetSnapshot.Member> members = capture.members();
        return members.isEmpty() && engineRoster != null && !engineRoster.isEmpty() ? null : members;
    }

    private static boolean isAlive(CampaignFleetAPI fleet) {
        try {
            return fleet.isAlive();
        } catch (RuntimeException | LinkageError ex) {
            // Unknown liveness must not report a live fleet as destroyed: the host would despawn it.
            return true;
        }
    }

    /**
     * Informational only — the partner's mirror of this fleet is refreshed by the 10 Hz Phase 8
     * {@code FLEET_SNAPSHOT} stream, which already carries the full roster. See
     * {@link CoopBattleResult} for why no own-roster payload is duplicated here.
     */
    private static int localFleetSize(SectorAPI sector) {
        try {
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            return player == null ? 0 : player.getFleetData().getMembersListCopy().size();
        } catch (RuntimeException | LinkageError ex) {
            return 0;
        }
    }

    private static String safeId(CampaignFleetAPI fleet) {
        try {
            String id = fleet.getId();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    // ---- engine helpers (all best-effort) --------------------------------------------------------

    private boolean isSessionActive() {
        return session.handshakeValidated() && session.seedLong() != null && session.sessionId() != null;
    }

    private static GameState currentGameState() {
        try {
            return Global.getCurrentState();
        } catch (RuntimeException | LinkageError ex) {
            return GameState.CAMPAIGN;
        }
    }

    private static boolean isDialogOpen(SectorAPI sector) {
        try {
            CampaignUIAPI ui = sector.getCampaignUI();
            return ui != null && (ui.isShowingDialog() || ui.getCurrentInteractionDialog() != null);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static CampaignFleetAPI playerFleetOrNull(SectorAPI sector) {
        try {
            return sector.getPlayerFleet();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static String currentLocationName() {
        try {
            CampaignFleetAPI player = Global.getSector().getPlayerFleet();
            LocationAPI location = player == null ? null : player.getContainingLocation();
            return location == null || location.getName() == null ? "" : location.getName();
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static String describeEnemy(CombatEngineAPI engine) {
        try {
            BattleCreationContext context = engine.getContext();
            CampaignFleetAPI other = context == null ? null : context.getOtherFleet();
            if (other == null) {
                return "";
            }
            String name = safeName(other);
            String faction = other.getFaction() == null ? "" : other.getFaction().getDisplayName();
            return faction == null || faction.isEmpty() ? name : faction + " " + name;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static String safeName(CampaignFleetAPI fleet) {
        try {
            String name = fleet.getName();
            return name == null ? "" : name;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    /** The guest's local mirror of a host fleet, matched on the {@code $coopNpcFleetId} tag. */
    static CampaignFleetAPI findNpcMirror(SectorAPI sector, String coopFleetId) {
        if (coopFleetId == null || coopFleetId.isEmpty()) {
            return null;
        }
        try {
            for (LocationAPI location : sector.getAllLocations()) {
                if (location == null) {
                    continue;
                }
                for (CampaignFleetAPI fleet : location.getFleets()) {
                    if (fleet != null && coopFleetId.equals(mirrorCoopFleetId(fleet))) {
                        return fleet;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop mirror lookup failed for " + coopFleetId, ex);
        }
        return null;
    }

    private static String mirrorCoopFleetId(CampaignFleetAPI fleet) {
        try {
            MemoryAPI memory = fleet.getMemoryWithoutUpdate();
            if (memory == null || !memory.contains(NPC_MIRROR_TAG)) {
                return "";
            }
            Object value = memory.get(NPC_MIRROR_TAG);
            return value == null ? "" : String.valueOf(value);
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static long elapsedMillis(CombatEngineAPI engine) {
        try {
            return (long) (engine.getTotalElapsedTime(false) * 1000f);
        } catch (RuntimeException | LinkageError ex) {
            return 0L;
        }
    }

    private static CoopBattleStatus.ShipState stateOf(ShipAPI ship) {
        try {
            if (!ship.isAlive()) {
                return CoopBattleStatus.ShipState.DESTROYED;
            }
            if (ship.isHulk()) {
                return CoopBattleStatus.ShipState.DISABLED;
            }
        } catch (RuntimeException | LinkageError ex) {
            return CoopBattleStatus.ShipState.ALIVE;
        }
        return CoopBattleStatus.ShipState.ALIVE;
    }

    private static String safeShipId(ShipAPI ship) {
        try {
            String id = ship.getId();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static String shipDisplayName(ShipAPI ship) {
        try {
            String name = ship.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through to the hull id
        }
        return hullIdOf(ship);
    }

    private static String hullIdOf(ShipAPI ship) {
        try {
            return ship.getHullSpec() == null || ship.getHullSpec().getHullId() == null
                    ? "" : ship.getHullSpec().getHullId();
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    private static float safeHullLevel(ShipAPI ship) {
        try {
            return ship.getHullLevel();
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
        }
    }

    private static float safeFluxLevel(ShipAPI ship) {
        try {
            return ship.getFluxLevel();
        } catch (RuntimeException | LinkageError ex) {
            return 0f;
        }
    }

    private record PendingEngage(String coopFleetId, String fleetName, long queuedAtMillis) {
    }

    private record PendingDialog(String coopFleetId, CoopMessages.DialogKind kind, long queuedAtMillis) {
    }

    /** A finished battle whose campaign result is waiting for vanilla to finalise it (Phase 15). */
    private record PendingResult(String battleId, String outcome, List<String> npcFleetIds,
                                 long queuedAtMillis) {
    }
}
