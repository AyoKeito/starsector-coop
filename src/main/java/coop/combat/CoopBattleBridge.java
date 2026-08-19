package coop.combat;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.time.CoopSharedPauseCoordinator;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Phase 14 solo own-fleet combat bridge. Owns both halves of a coop battle:
 *
 * <ul>
 *   <li><b>Engaging side</b> — detects battle start/end, becomes that battle's authority, and streams
 *       {@code BATTLE_BEGIN} / {@code BATTLE_STATUS} / {@code BATTLE_END} over reliable TCP.</li>
 *   <li><b>Spectator side</b> — asserts the shared combat pause, opens {@link CoopBattleStatusPanel},
 *       and closes it when the battle ends or the session dies.</li>
 * </ul>
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
 *       status capture needs anyway. For the host-pushed {@code ENGAGE_GUEST} path the mod owns the
 *       moment before {@code startBattle}, so {@code BATTLE_BEGIN} goes out there instead — earlier
 *       still, and after the pre-battle autosave.</li>
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
 * <h2>Disconnect mid-combat</h2>
 * Finish locally, reconcile by authority. If the session dies while a local battle is running, the
 * {@code BATTLE_END} never sends; that is safe (the host's authoritative state resurrects whatever it
 * would have changed) and is logged loudly by {@link #discardedResultMessage}. The spectator's panel
 * flips to a dismissable "connection lost" state. No freeze, no countdown, no rollback — the
 * cancelled protocol depended on programmatic save-loading that does not exist.
 */
public final class CoopBattleBridge {

    /** 2.5 Hz: inside the plan's 300-500 ms window, cheap on a 64 KB TCP frame budget. */
    static final long STATUS_INTERVAL_MILLIS = 400L;
    /**
     * Hard cap on ships in one {@code BATTLE_STATUS}. A 200-ship line-item body is ~14 KB, well under
     * the 64 KB frame limit; anything past this is a runaway and is truncated rather than dropped.
     */
    static final int MAX_STATUS_SHIPS = 200;
    /** Kill-feed depth carried in every snapshot (it is stateless — the newest snapshot must stand alone). */
    static final int KILL_FEED_DEPTH = 12;
    /**
     * Safety valve: if a local battle is "begun" but no combat frame ever arrives, something rejected
     * the state transition. Give up, release the partner, and log — never leave the shared clock stuck.
     */
    static final long BATTLE_START_TIMEOUT_MILLIS = 15000L;
    /**
     * Escape hatch for the spectator: if a remote battle somehow never ends while the connection
     * stays up (TCP guarantees {@code BATTLE_END} delivery, so this should be unreachable), let the
     * spectator out rather than trapping them in an optionless dialog forever.
     */
    static final long REMOTE_BATTLE_ESCAPE_HATCH_MILLIS = 10L * 60L * 1000L;
    /** A queued host-pushed action that cannot be honoured for this long is dropped. */
    static final long PENDING_ACTION_TIMEOUT_MILLIS = 60000L;

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

    // ---- spectator side ----
    private boolean remoteBattleActive;
    private String remoteBattleId = "";
    private String remoteHeader = "";
    private CoopBattleStatus remoteStatus;
    private long remoteBattleBeganAtMillis;
    private CoopBattleStatusPanel panel;
    private String panelOpenedForBattleId = "";
    private boolean panelBannerShown;
    private boolean panelFailureLogged;
    private long nextPanelAttemptAtMillis;
    private boolean sessionLost;
    private boolean connectionLostBannerPending;

    // ---- host-pushed actions queued on the guest ----
    private PendingEngage pendingEngage;
    private PendingDialog pendingDialog;

    private boolean sessionWasActive;

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

    /** The spectator may leave the panel only when the session is gone or the battle is impossibly long. */
    public static boolean canDismissPanel(boolean sessionActive, long remoteBattleAgeMillis) {
        return !sessionActive || remoteBattleAgeMillis > REMOTE_BATTLE_ESCAPE_HATCH_MILLIS;
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
                beginLocalBattle(engine, CoopMessages.BattleKind.PLAYER, describeEnemy(engine), "");
            }
            sawCombatFrame = true;
            if (nowMillis < nextStatusAtMillis) {
                return;
            }
            nextStatusAtMillis = nowMillis + STATUS_INTERVAL_MILLIS;
            sendStatus(engine);
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
                        || pendingEngage != null || pendingDialog != null) {
                    onSessionEnded();
                    sessionWasActive = false;
                }
                syncPanel(sector, nowMillis);
                return;
            }
            sessionWasActive = true;
            sessionLost = false;
            autosave.tick(sector);
            maybeEndLocalBattle(nowMillis);
            drivePendingEngage(sector, nowMillis);
            drivePendingDialog(sector, nowMillis);
            syncPanel(sector, nowMillis);
            if (service.role() == CoopConnectionRole.HOST) {
                pauseCoordinator.setEitherInCombat(combatPauseIntent(localBattleActive, remoteBattleActive));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle bridge tick failed", ex);
        }
    }

    /** True while this client is piloting a coop battle (used by the threat watcher's gate). */
    public boolean isAnyCoopBattleActive() {
        return localBattleActive || remoteBattleActive;
    }

    /** The status panel plugin currently open, or null. Lets the pump skip it in the interaction gate. */
    public boolean isStatusPanel(InteractionDialogPlugin plugin) {
        return plugin instanceof CoopBattleStatusPanel;
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
        String battleId = CoopMessages.requiredPayloadString(message, "battleId");
        String enemy = CoopMessages.requiredPayloadString(message, "enemySummary");
        String location = CoopMessages.requiredPayloadString(message, "locationName");
        remoteBattleActive = true;
        remoteBattleId = battleId;
        remoteStatus = null;
        remoteBattleBeganAtMillis = clock.getAsLong();
        panelOpenedForBattleId = "";
        panelBannerShown = false;
        remoteHeader = (session.remoteName() == null || session.remoteName().isEmpty()
                ? "Your partner" : session.remoteName())
                + " is fighting " + (enemy.isEmpty() ? "an enemy fleet" : enemy)
                + (location.isEmpty() ? "" : " in " + location) + ".";
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_BEGIN received battleId=" + battleId
                + " enemy=" + enemy + " location=" + location);
    }

    private void handleBattleStatus(CoopMessages.Message message) {
        String battleId = CoopMessages.requiredPayloadString(message, "battleId");
        long statusSeq = CoopMessages.requiredPayloadLong(message, "statusSeq");
        if (!CoopBattleStatus.isNewer(battleId, statusSeq, remoteStatus)) {
            return;
        }
        remoteStatus = CoopBattleStatus.decode(battleId, statusSeq,
                CoopMessages.requiredPayloadLong(message, "elapsedMillis"),
                CoopMessages.requiredPayloadString(message, "ships"));
        if (!remoteBattleActive) {
            // Defensive: status without a begin (should be impossible over TCP) still opens the panel.
            remoteBattleActive = true;
            remoteBattleId = battleId;
            remoteBattleBeganAtMillis = clock.getAsLong();
        }
    }

    private void handleBattleEnd(CoopMessages.Message message) {
        String battleId = CoopMessages.requiredPayloadString(message, "battleId");
        String outcome = CoopMessages.requiredPayloadString(message, "outcome");
        remoteBattleActive = false;
        remoteStatus = null;
        remoteBattleId = "";
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_END received battleId=" + battleId
                + " outcome=" + outcome);
    }

    private void handleEngageGuest(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }
        String coopFleetId = CoopMessages.requiredPayloadString(message, "coopFleetId");
        String fleetName = CoopMessages.requiredPayloadString(message, "fleetName");
        if (localBattleActive || pendingEngage != null) {
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST ignored (battle already pending/active)"
                    + " coopFleetId=" + coopFleetId);
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
        String coopFleetId = CoopMessages.requiredPayloadString(message, "coopFleetId");
        CoopMessages.DialogKind kind = CoopMessages.DialogKind.valueOf(
                CoopMessages.requiredPayloadString(message, "kind"));
        if (pendingDialog != null) {
            return;
        }
        pendingDialog = new PendingDialog(coopFleetId, kind, clock.getAsLong());
        CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN queued coopFleetId=" + coopFleetId
                + " kind=" + kind);
    }

    // ---- engaging side ---------------------------------------------------------------------------

    private void beginLocalBattle(CombatEngineAPI engine, CoopMessages.BattleKind kind,
                                  String enemySummary, String npcFleetIds) {
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
        CoopMessages.Message message = CoopMessages.battleBegin(
                session.sessionId(), service.nextSeq(), clock.getAsLong(),
                localBattleId, session.localPlayerId(), currentLocationName(),
                localEnemySummary, npcFleetIds, kind);
        service.send(message);
        service.flushOutbound();
        if (service.role() == CoopConnectionRole.HOST) {
            // Assert the combat pause immediately: the host pump is about to stop advancing, so the
            // frame that would otherwise compute it may never run.
            pauseCoordinator.setEitherInCombat(true);
        }
        CoopLog.info(CoopBattleBridge.class, "Coop BATTLE_BEGIN sent battleId=" + localBattleId
                + " kind=" + kind + " enemy=" + localEnemySummary);
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
        localBattleActive = false;
        sawCombatFrame = false;
        lastSeenShips.clear();
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
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            return;
        }
        String fleetName = pendingEngage.fleetName();
        pendingEngage = null;
        // BATTLE_BEGIN goes out (and is flushed) BEFORE the state transition, so the partner is paused
        // and watching for the whole battle rather than from the moment it happens to end.
        beginLocalBattle(null, CoopMessages.BattleKind.ENGAGE_GUEST, fleetName,
                mirrorCoopFleetId(mirror));
        try {
            ui.startBattle(new BattleCreationContext(player, FleetGoal.ATTACK, mirror, FleetGoal.ATTACK));
            CoopLog.info(CoopBattleBridge.class, "Coop ENGAGE_GUEST startBattle issued vs " + fleetName);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop ENGAGE_GUEST startBattle threw", ex);
            endLocalBattle("START_FAILED");
        }
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
        pendingDialog = null;
        String staged = CoopCustomsDialogStaging.stage(sector, mirror, kind);
        CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN staging kind=" + kind
                + " " + CoopCustomsDialogStaging.describePreconditions(mirror) + " flags: " + staged);
        try {
            boolean shown = ui.showInteractionDialog(mirror);
            CoopLog.info(CoopBattleBridge.class, "Coop DIALOG_BEGIN showInteractionDialog returned="
                    + shown + " target=" + safeName(mirror));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop DIALOG_BEGIN showInteractionDialog threw", ex);
        }
    }

    // ---- spectator panel -------------------------------------------------------------------------

    private void syncPanel(SectorAPI sector, long nowMillis) {
        if (sector == null) {
            return;
        }
        CampaignUIAPI ui = sector.getCampaignUI();
        if (ui == null) {
            return;
        }
        // Forget the panel once the player (or the engine) closed it.
        if (panel != null && !isPanelCurrentDialog(ui)) {
            panel = null;
        }
        if (!remoteBattleActive) {
            if (panel != null) {
                panel.requestDismiss();
                panel = null;
            }
            if (connectionLostBannerPending) {
                connectionLostBannerPending = false;
                addMessage(ui, "Coop: connection lost. Your partner's battle finishes on their machine;"
                        + " the host's authoritative state reconciles it next session.");
            }
            panelOpenedForBattleId = "";
            panelBannerShown = false;
            panelFailureLogged = false;
            return;
        }
        if (panel != null || panelOpenedForBattleId.equals(remoteBattleId)) {
            return;
        }
        if (ui.getCurrentInteractionDialog() != null || ui.isShowingDialog()) {
            // Another dialog owns the screen (docked at a market, mid-encounter). Fall back to the
            // banner the plan names, and try again on a later frame — the local dialog will close.
            if (!panelBannerShown) {
                panelBannerShown = true;
                addMessage(ui, remoteHeader);
            }
            return;
        }
        if (nowMillis < nextPanelAttemptAtMillis) {
            return;
        }
        nextPanelAttemptAtMillis = nowMillis + 1000L;
        openPanel(sector, ui);
    }

    private void openPanel(SectorAPI sector, CampaignUIAPI ui) {
        CampaignFleetAPI player = playerFleetOrNull(sector);
        if (player == null) {
            return;
        }
        CoopBattleStatusPanel created = new CoopBattleStatusPanel(
                () -> remoteStatus,
                () -> remoteHeader,
                () -> !remoteBattleActive,
                () -> canDismissPanel(isSessionActive() && service.isConnected(),
                        clock.getAsLong() - remoteBattleBeganAtMillis),
                () -> sessionLost
                        ? "Connection to your partner was lost. The battle finishes on their machine."
                        : "This engagement has run unusually long; you may close this panel.");
        boolean shown;
        try {
            shown = ui.showInteractionDialog(created, player);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopBattleBridge.class, "Coop battle status panel failed to open", ex);
            shown = false;
        }
        if (shown) {
            panel = created;
            panelOpenedForBattleId = remoteBattleId;
            CoopLog.info(CoopBattleBridge.class, "Coop battle status panel opened for battleId="
                    + remoteBattleId);
            return;
        }
        // Deliberately NOT marked as opened: the refusal is usually transient (a screen took the UI
        // between the check and the call), so the 1 Hz retry above gets another go. The banner and the
        // warning are one-shot so a permanent refusal cannot spam either.
        if (!panelBannerShown) {
            panelBannerShown = true;
            addMessage(ui, remoteHeader);
        }
        if (!panelFailureLogged) {
            panelFailureLogged = true;
            CoopLog.warn(CoopBattleBridge.class, "Coop battle status panel refused to open; fell back"
                    + " to the message banner for battleId=" + remoteBattleId);
        }
    }

    private static void addMessage(CampaignUIAPI ui, String text) {
        try {
            ui.addMessage(text);
        } catch (RuntimeException | LinkageError ignored) {
            // banner is best-effort
        }
    }

    private boolean isPanelCurrentDialog(CampaignUIAPI ui) {
        try {
            InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
            return dialog != null && dialog.getPlugin() == panel;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ---- session lifecycle -----------------------------------------------------------------------

    private void onSessionEnded() {
        if (localBattleActive && !localBattleEndSent) {
            CoopLog.warn(CoopBattleBridge.class, discardedResultMessage(localBattleId, localEnemySummary));
        }
        localBattleActive = false;
        sawCombatFrame = false;
        lastSeenShips.clear();
        killFeed.clear();
        pendingEngage = null;
        pendingDialog = null;
        if (remoteBattleActive) {
            sessionLost = true;
            remoteBattleActive = false;
            connectionLostBannerPending = true;
            CoopLog.warn(CoopBattleBridge.class, "Coop session lost while the partner was fighting"
                    + " battleId=" + remoteBattleId + "; spectator panel released");
        }
        pauseCoordinator.setEitherInCombat(false);
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
}
