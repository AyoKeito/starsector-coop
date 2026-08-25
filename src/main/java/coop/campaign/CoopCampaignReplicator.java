package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyDecivListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.GateEntityPlugin;
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.EveryFrameScript;
import coop.colony.CoopRaidOutcomeSync;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Phase 12 hub: replicates host-authoritative campaign state across the coop session.
 *
 * <p>Covers shared reputation ({@link CoopRepDelta}), faction-to-faction relations
 * ({@link CoopFactionRelations}), the shared mission/bar pool + first-come claims
 * ({@link CoopMissionBoardSync}), host-authoritative market contents + transactions
 * ({@link CoopMarketSync}), salvage/exploration/construction world deltas ({@link CoopWorldDelta}),
 * and world-affecting ability arbitration ({@link CoopAbilityArbiter}).
 *
 * <p><b>Authority model.</b> The host owns every shared model. It captures vanilla events through
 * {@link CoopCampaignEventListener} and broadcasts the resulting values to the guest, which applies
 * them by <em>setting</em> state rather than re-simulating. Guest-driven outcomes the host cannot
 * observe (a salvaged entity, a market transaction, an activated world ability) funnel up as a
 * single explicit report; the host integrates and re-broadcasts. The {@link ReplayGuard} ensures
 * applying a host-originated event never causes the applier to rebroadcast it.
 *
 * <p>The pure decision/model classes are unit tested; this orchestrator wires them to the live
 * engine (best-effort, defensive) and to the net service, and is exercised in the two-instance
 * smoke test.
 */
public final class CoopCampaignReplicator
        implements CoopCampaignEventListener.Sink, CoopRaidOutcomeSync.Sink {

    /**
     * Re-entrancy guard: while {@link #isReplaying()} the applier is mid-apply of a host-originated
     * event, so any vanilla event it triggers must not be captured and rebroadcast.
     */
    public static final class ReplayGuard {
        private int depth;

        public void begin() {
            depth++;
        }

        public void end() {
            if (depth > 0) {
                depth--;
            }
        }

        public boolean isReplaying() {
            return depth > 0;
        }
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;
    private final ReplayGuard replayGuard = new ReplayGuard();

    private final Map<String, Float> repTable = new HashMap<>();
    private final CoopFactionRelations factionRelations = new CoopFactionRelations();
    private final CoopMissionBoardSync missionBoard = new CoopMissionBoardSync();
    private final CoopMarketSync marketSync = new CoopMarketSync();
    private final CoopWorldDelta.Ledger worldLedger = new CoopWorldDelta.Ledger();

    /**
     * Guest-side hire detection baseline: marketId -> (personId -> the kind it was listed as) as of
     * the last applied MARKET_SNAPSHOT. There is no vanilla hire event, so a person present here and
     * absent from the market's live hireable set at close was hired by the local player.
     */
    private final Map<String, Map<String, CoopMarketSync.ItemKind>> appliedHireables = new HashMap<>();

    // Salvage watcher: salvageable entity ids present at the local player's location last pass. A
    // tracked id that vanishes means the local player salvaged/disassembled it -> WORLD_DELTA(CONSUME).
    private final Set<String> trackedSalvageables = new HashSet<>();
    private String watchedLocationId;
    /**
     * How often the watcher walks the player's location. It used to run every frame over every entity
     * there (358 in an asteroid belt), reading {@code getMemoryWithoutUpdate()} — which lazily allocates
     * a save-persisted Memory for entities that lack one — and allocating a fresh id set each time
     * (perf audit #5). The output is an event report, so seeing a salvage up to 250 ms late is not
     * observable: nothing in the session reads a CONSUME delta on a deadline.
     */
    static final long SALVAGE_SCAN_INTERVAL_MILLIS = 250L;
    private long lastSalvageScanMillis;
    /** Scratch, reused across passes: ids present at the watched location on the current pass. */
    private final Set<String> salvageScanScratch = new HashSet<>();
    /** Scratch, reused across passes: tracked ids that vanished on the current pass. */
    private final List<String> salvageConsumedScratch = new ArrayList<>();

    // Orbit-angle sync: host re-broadcasts orbiting-body angles ~1Hz so the guest can snap out the
    // small clock-drift offset that makes shared systems' planets/jumps appear at different angles.
    static final long ORBIT_SYNC_INTERVAL_MILLIS = 1000L;
    private long lastOrbitSyncMillis;
    private int lastOrbitBodyCount = -1;

    // Player faction standings: host re-broadcasts the full set on a slow cadence and the guest
    // force-matches it. Event-driven REP_DELTA covers host-side changes immediately; this snapshot is
    // the safety net that converges drift the host can't see (e.g. the guest's own transponder-off
    // penalties, applied independently in the guest's simulation).
    static final long PLAYER_REP_SYNC_INTERVAL_MILLIS = 30000L;
    private long lastPlayerRepSyncMillis;

    // Phase 13 skeleton mutations: campaign-objective ownership and story-gate activation are polled
    // on a slow cadence and broadcast as WORLD_DELTAs, joined in Phase 12c by planet survey levels
    // and ruins exploration. All are rare (war-sim swings are days apart; gates activate once a
    // campaign; a survey is a manual player act), so the poll is cheap: two tag lookups and one
    // planet list per location, every few seconds.
    static final long SKELETON_POLL_INTERVAL_MILLIS = 5000L;
    private final CoopSkeletonMutationWatcher skeletonWatcher = new CoopSkeletonMutationWatcher();
    private long lastSkeletonPollMillis;
    private DecivCapture decivCapture;

    // Phase 24 milestone 1: player raids/bombardments against colonies. Bidirectional -- whoever
    // performs the act captures the vanilla outcome and reports it; the host canonicalizes and
    // rebroadcasts, and the ledger absorbs the echo on the originator.
    private final CoopRaidOutcomeSync.Ledger raidLedger = new CoopRaidOutcomeSync.Ledger();
    private CoopRaidOutcomeSync.HostileActCapture raidCapture;

    // Phase 12c bar pool: the host polls the global portside bar pool and pushes the ordered list on
    // change. Two seconds is well inside a dock-to-bar-click, and the pool only ever changes on
    // BarEventManager's 0.4-0.6 day generation tick or when someone accepts an offer.
    static final long BAR_POOL_POLL_INTERVAL_MILLIS = 2000L;
    /** The bar pool is sector-global, so its snapshot has no owning market. */
    static final String BAR_POOL_MARKET_ID = "";
    private final CoopBarPoolCapture barPoolCapture = new CoopBarPoolCapture();
    private final CoopBarPoolInjector barPoolInjector = new CoopBarPoolInjector();
    private long lastBarPoolPollMillis;

    private CoopCampaignEventListener listener;
    private boolean factionRelationsSeeded;

    public CoopCampaignReplicator(CoopNetService service, CoopSessionState session) {
        this(service, session, System::currentTimeMillis);
    }

    public CoopCampaignReplicator(CoopNetService service, CoopSessionState session, LongSupplier clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- Listener lifecycle -------------------------------------------------------------------

    /** Registers the campaign event listener on the sector (idempotent). */
    public void registerOn(SectorAPI sector) {
        if (sector == null || listener != null) {
            return;
        }
        listener = new CoopCampaignEventListener(this);
        sector.addTransientListener(listener);
        // Session start: re-arm the bar-pool rebroadcast so a (re)joining guest gets a warm pool on
        // the first poll rather than waiting for the host's next offer to spawn or expire.
        barPoolCapture.reset();
        barPoolInjector.reset();
        lastBarPoolPollMillis = 0L;
        // CargoScreenListener is dispatched through the listener manager, not the campaign-event
        // list, so it needs its own transient registration (Phase 12d: cargo pod replication).
        try {
            sector.getListenerManager().addListener(listener, true);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop cargo-screen listener; pod replication will not fire", ex);
        }
        // Deciv capture is its own listener interface (ColonyDecivListener), dispatched through the
        // listener manager rather than the campaign-event list.
        try {
            decivCapture = new DecivCapture();
            sector.getListenerManager().addListener(decivCapture, true);
        } catch (RuntimeException | LinkageError ex) {
            decivCapture = null;
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop deciv listener; DECIV world-deltas will not fire", ex);
        }
        // Phase 24 M1: raids/bombardments arrive on their own vanilla listener interface, also via
        // the listener manager rather than the campaign-event list.
        try {
            raidCapture = new CoopRaidOutcomeSync.HostileActCapture(this);
            sector.getListenerManager().addListener(raidCapture, true);
        } catch (RuntimeException | LinkageError ex) {
            raidCapture = null;
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Could not register coop hostile-act listener; RAID_RESULT will not fire", ex);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop campaign event listener registered");
    }

    /** Removes the listener and clears replicated state on session end. */
    public void dispose(SectorAPI sector) {
        if (sector != null && listener != null) {
            try {
                sector.removeListener(listener);
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to remove coop campaign listener", ex);
            }
            try {
                sector.getListenerManager().removeListener(listener);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class,
                        "Failed to remove coop cargo-screen listener", ex);
            }
        }
        if (sector != null && decivCapture != null) {
            try {
                sector.getListenerManager().removeListener(decivCapture);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to remove coop deciv listener", ex);
            }
        }
        if (sector != null && raidCapture != null) {
            try {
                sector.getListenerManager().removeListener(raidCapture);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to remove coop hostile-act listener", ex);
            }
        }
        if (raidCapture != null) {
            raidCapture.reset();
        }
        raidCapture = null;
        raidLedger.clear();
        decivCapture = null;
        listener = null;
        factionRelationsSeeded = false;
        lastPlayerRepSyncMillis = 0L;
        lastSkeletonPollMillis = 0L;
        lastBarPoolPollMillis = 0L;
        barPoolCapture.reset();
        barPoolInjector.reset();
        skeletonWatcher.clear();
        repTable.clear();
        factionRelations.clear();
        missionBoard.clear();
        marketSync.clear();
        appliedHireables.clear();
        worldLedger.clear();
        // Salvage-watcher baseline too: leaving it populated meant that on reconnect in the same
        // system, every entity consumed last session looked "newly missing" and was re-reported as a
        // fresh WORLD_DELTA(CONSUME). Clearing forces a silent re-seed on the next tick.
        trackedSalvageables.clear();
        salvageScanScratch.clear();
        salvageConsumedScratch.clear();
        watchedLocationId = null;
        lastSalvageScanMillis = 0L;
    }

    public boolean isRegistered() {
        return listener != null;
    }

    // ---- Inbound routing ----------------------------------------------------------------------

    /** Routes a campaign-replication message. Returns true if it was a Phase 12 message type. */
    public boolean handle(CoopMessages.Message message) {
        Objects.requireNonNull(message, "message");
        switch (message.type()) {
            case REP_DELTA -> applyRepDelta(message);
            case GUEST_REP_DELTA -> handleGuestRepDelta(message);
            case PLAYER_REP_SNAPSHOT -> applyPlayerRepSnapshot(message);
            case FACTION_REL_DELTA -> applyFactionRelDelta(message);
            case MISSION_POOL_SNAPSHOT -> applyMissionPool(message);
            case MISSION_CLAIM_REQUEST -> hostHandleMissionClaim(message);
            case MISSION_CLAIM_ACCEPT -> guestApplyMissionAccept(message);
            case MISSION_CLAIM_REJECT -> guestApplyMissionReject(message);
            case MARKET_OPEN -> handleMarketOpen(message);
            case MARKET_SNAPSHOT -> applyMarketSnapshot(message);
            case MARKET_TXN -> hostApplyMarketTxn(message);
            case WORLD_DELTA -> handleWorldDelta(message);
            case RAID_RESULT -> handleRaidResult(message);
            case ABILITY_ACTIVATE -> hostHandleAbilityActivate(message);
            case ORBIT_SNAPSHOT -> applyOrbitSnapshot(message);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- Reputation (host capture -> guest apply) ---------------------------------------------

    @Override
    public void onPlayerReputationChange(String factionId, float delta) {
        if (replayGuard.isReplaying() || !isActive() || factionId == null) {
            return;
        }
        try {
            if (isHost()) {
                float resulting = playerRelationshipTo(factionId);
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, factionId), resulting);
                send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.FACTION.name(), factionId, delta, resulting));
                CoopLog.info(CoopCampaignReplicator.class, "Coop REP_DELTA faction=" + factionId
                        + " delta=" + delta + " resulting=" + resulting);
            } else if (isGuest()) {
                // The guest forwards its own earned/lost faction rep to the host, which folds the DELTA
                // (not the resulting value: per-client baselines differ) into the canonical standing and
                // rebroadcasts the authoritative result. The replayGuard check above means changes the
                // guest applied from a host message are never re-reported.
                send(CoopMessages.guestRepDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.FACTION.name(), factionId, delta));
                CoopLog.info(CoopCampaignReplicator.class, "Coop GUEST_REP_DELTA faction=" + factionId
                        + " delta=" + delta);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture faction reputation change", ex);
        }
    }

    @Override
    public void onPlayerReputationChange(PersonAPI person, float delta) {
        if (replayGuard.isReplaying() || !isActive() || person == null) {
            return;
        }
        try {
            String personId = person.getId();
            if (isHost()) {
                float resulting = person.getRelToPlayer() != null ? person.getRelToPlayer().getRel() : delta;
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.PERSON, personId), resulting);
                send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.PERSON.name(), personId, delta, resulting));
                CoopLog.info(CoopCampaignReplicator.class, "Coop REP_DELTA person=" + personId
                        + " delta=" + delta + " resulting=" + resulting);
            } else if (isGuest()) {
                send(CoopMessages.guestRepDelta(session.sessionId(), service.nextSeq(), now(),
                        CoopRepDelta.TargetType.PERSON.name(), personId, delta));
                CoopLog.info(CoopCampaignReplicator.class, "Coop GUEST_REP_DELTA person=" + personId
                        + " delta=" + delta);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture person reputation change", ex);
        }
    }

    private void applyRepDelta(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopRepDelta.TargetType type = CoopRepDelta.TargetType.valueOf(
                CoopMessages.requiredPayloadString(message, "targetType"));
        String targetId = CoopMessages.requiredPayloadString(message, "targetId");
        float resulting = CoopMessages.requiredPayloadFloat(message, "resultingValue");
        repTable.put(CoopRepDelta.relationshipKey(type, targetId), resulting);
        boolean appliedToEngine = false;
        replayGuard.begin();
        try {
            if (type == CoopRepDelta.TargetType.FACTION) {
                FactionAPI player = playerFaction();
                if (player != null) {
                    player.setRelationship(targetId, resulting);
                    appliedToEngine = true;
                }
            } else {
                PersonAPI person = findPerson(targetId);
                if (person != null && person.getRelToPlayer() != null) {
                    person.getRelToPlayer().setRel(resulting);
                    appliedToEngine = true;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply REP_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        if (appliedToEngine) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop applied REP_DELTA " + type + ":" + targetId
                    + " -> " + resulting);
        } else {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop REP_DELTA stored but target missing "
                    + type + ":" + targetId + " -> " + resulting);
        }
    }

    /**
     * Host: a guest-earned/lost reputation increment. Folds the DELTA into the canonical target
     * relationship (current + delta, clamped) and rebroadcasts the resulting value so the guest
     * converges without trusting the guest's local baseline.
     */
    private void handleGuestRepDelta(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        CoopRepDelta.TargetType type = CoopRepDelta.TargetType.valueOf(
                CoopMessages.requiredPayloadString(message, "targetType"));
        String targetId = CoopMessages.requiredPayloadString(message, "targetId");
        float delta = CoopMessages.requiredPayloadFloat(message, "delta");
        if (type == CoopRepDelta.TargetType.FACTION) {
            handleGuestFactionRepDelta(targetId, delta);
        } else {
            handleGuestPersonRepDelta(targetId, delta);
        }
    }

    private void handleGuestFactionRepDelta(String factionId, float delta) {
        FactionAPI player = playerFaction();
        if (player == null) {
            return;
        }
        float resulting = clampRelationship(player.getRelationship(factionId) + delta);
        // Suppress the host's own listener in case setRelationship fires it, so we send exactly one
        // authoritative REP_DELTA below rather than risk a duplicate.
        replayGuard.begin();
        try {
            player.setRelationship(factionId, resulting);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply GUEST_REP_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, factionId), resulting);
        send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                CoopRepDelta.TargetType.FACTION.name(), factionId, delta, resulting));
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied GUEST_REP_DELTA faction=" + factionId
                + " delta=" + delta + " -> " + resulting);
    }

    private void handleGuestPersonRepDelta(String personId, float delta) {
        PersonAPI person = findPerson(personId);
        if (person == null || person.getRelToPlayer() == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop GUEST_REP_DELTA person target missing id=" + personId);
            return;
        }
        float resulting = clampRelationship(person.getRelToPlayer().getRel() + delta);
        // Suppress the host's own listener in case setRel fires it; the host sends the one canonical
        // REP_DELTA below.
        replayGuard.begin();
        try {
            person.getRelToPlayer().setRel(resulting);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply GUEST_REP_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.PERSON, personId), resulting);
        send(CoopMessages.repDelta(session.sessionId(), service.nextSeq(), now(),
                CoopRepDelta.TargetType.PERSON.name(), personId, delta, resulting));
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied GUEST_REP_DELTA person=" + personId
                + " delta=" + delta + " -> " + resulting);
    }

    /** Host: broadcast the full set of player faction standings on a slow cadence (drift safety net). */
    public void tickPlayerRepSync() {
        if (!isHost() || replayGuard.isReplaying() || !isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastPlayerRepSyncMillis < PLAYER_REP_SYNC_INTERVAL_MILLIS) {
            return;
        }
        lastPlayerRepSyncMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            FactionAPI player = sector == null ? null : sector.getPlayerFaction();
            if (player == null) {
                return;
            }
            Map<String, Float> standings = new LinkedHashMap<>();
            for (FactionAPI faction : sector.getAllFactions()) {
                if (faction.getId().equals(player.getId())) {
                    continue; // standing to self is constant
                }
                float value = player.getRelationship(faction.getId());
                standings.put(faction.getId(), value);
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, faction.getId()), value);
            }
            send(CoopMessages.playerRepSnapshot(session.sessionId(), service.nextSeq(), nowMillis,
                    CoopRepDelta.encodeFactionStandings(standings)));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Player reputation snapshot capture failed", ex);
        }
    }

    /** Guest: force player faction standings to the host's values (overwrites any local drift). */
    private void applyPlayerRepSnapshot(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        Map<String, Float> standings = CoopRepDelta.decodeFactionStandings(
                CoopMessages.requiredPayloadString(message, "reps"));
        FactionAPI player = playerFaction();
        if (player == null) {
            return;
        }
        int changed = 0;
        replayGuard.begin();
        try {
            for (Map.Entry<String, Float> entry : standings.entrySet()) {
                String factionId = entry.getKey();
                float target = entry.getValue();
                repTable.put(CoopRepDelta.relationshipKey(CoopRepDelta.TargetType.FACTION, factionId), target);
                if (Math.abs(player.getRelationship(factionId) - target) > 0.0001f) {
                    player.setRelationship(factionId, target);
                    changed++;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply PLAYER_REP_SNAPSHOT", ex);
        } finally {
            replayGuard.end();
        }
        if (changed > 0) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop applied PLAYER_REP_SNAPSHOT corrected="
                    + changed + "/" + standings.size() + " faction standings");
        }
    }

    // ---- Faction-to-faction relations ---------------------------------------------------------
    // No vanilla event reports inter-faction standing changes, so the host diffs all faction pairs
    // on each economy tick (bounded by faction count, daily cadence) and broadcasts only changes.

    @Override
    public void onEconomyTick(int iterIndex) {
        if (!isHost() || replayGuard.isReplaying() || !isActive()) {
            return;
        }
        captureFactionRelationChanges();
    }

    // ---- Phase 14 battle-outcome enrichment ----------------------------------------------------
    // Pure pass-through to whoever is observing battles (CoopBattleBridge). Deliberately no policy
    // here: the coop battle window is opened/closed by the bridge's own seams, and these callbacks
    // only supply a nicer outcome string than "UNKNOWN".

    /** Observer of the vanilla battle-result callbacks; wired to the Phase 14 battle bridge. */
    public interface BattleObserver {
        void onBattleOccurred(boolean playerWon);

        void onPlayerEngagement(String outcome);
    }

    private BattleObserver battleObserver;

    public void setBattleObserver(BattleObserver observer) {
        this.battleObserver = observer;
    }

    @Override
    public void onBattleOccurred(boolean playerWon) {
        if (battleObserver != null) {
            battleObserver.onBattleOccurred(playerWon);
        }
    }

    @Override
    public void onPlayerEngagement(boolean playerWon, boolean playerOutBeforeEnd) {
        if (battleObserver == null) {
            return;
        }
        battleObserver.onPlayerEngagement(
                playerOutBeforeEnd ? "DISENGAGED" : (playerWon ? "WIN" : "LOSS"));
    }

    private void captureFactionRelationChanges() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            FactionAPI playerFaction = sector.getPlayerFaction();
            String playerFactionId = playerFaction == null ? null : playerFaction.getId();
            List<FactionAPI> factions = sector.getAllFactions();
            for (int i = 0; i < factions.size(); i++) {
                FactionAPI a = factions.get(i);
                if (playerFactionId != null && playerFactionId.equals(a.getId())) {
                    continue; // player standings ride REP_DELTA, not FACTION_REL_DELTA
                }
                for (int j = i + 1; j < factions.size(); j++) {
                    FactionAPI b = factions.get(j);
                    if (playerFactionId != null && playerFactionId.equals(b.getId())) {
                        continue;
                    }
                    float current = a.getRelationship(b.getId());
                    boolean known = factionRelations.isKnown(a.getId(), b.getId());
                    if (!known || factionRelations.relationship(a.getId(), b.getId()) != current) {
                        factionRelations.applyResult(a.getId(), b.getId(), current);
                        if (factionRelationsSeeded) {
                            send(CoopMessages.factionRelDelta(session.sessionId(), service.nextSeq(), now(),
                                    a.getId(), b.getId(), current));
                        }
                    }
                }
            }
            factionRelationsSeeded = true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture faction relation changes", ex);
        }
    }

    private void applyFactionRelDelta(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        String factionA = CoopMessages.requiredPayloadString(message, "factionA");
        String factionB = CoopMessages.requiredPayloadString(message, "factionB");
        float resulting = CoopMessages.requiredPayloadFloat(message, "resultingValue");
        factionRelations.applyResult(factionA, factionB, resulting);
        replayGuard.begin();
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                FactionAPI a = sector.getFaction(factionA);
                if (a != null) {
                    a.setRelationship(factionB, resulting);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply FACTION_REL_DELTA", ex);
        } finally {
            replayGuard.end();
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied FACTION_REL_DELTA " + factionA + "/"
                + factionB + " -> " + resulting);
    }

    // ---- Mission/bar pool + claims ------------------------------------------------------------

    @Override
    public void onPlayerOpenedMarket(MarketAPI market, boolean cargoUpdated) {
        if (!isActive() || replayGuard.isReplaying() || market == null) {
            return;
        }
        // Phase 12c gap 2e: the host re-snapshots when the engine says the submarket plugins just
        // restocked. Without it a market the host reopened after a 30-day ship/weapon reroll kept
        // serving the guest the stock it had captured before the reroll.
        //
        // Safe against accelerated restock: broadcastMarketSnapshot re-enters
        // updateCargoPrePlayerInteraction with a zero-day sinceLastCargoUpdate, and vanilla's own
        // sub-unit guard refuses the fractional add, so the second call adds nothing. Gated on a live
        // connection so a host docking with nobody attached emits nothing.
        if (isHost() && cargoUpdated && service.isConnected()) {
            broadcastMarketSnapshot(market);
        }
        // Host-authoritative market contents are synced once, at open (never per-frame, so the trade
        // UI is never fought mid-transaction). The host's engine market IS the canonical source: the
        // guest asks the host for a snapshot and applies it to its own market once; thereafter both
        // sides apply the same per-transaction delta, so they stay consistent with no live re-sync.
        if (isGuest()) {
            // Drop the hire baseline before asking for a fresh one. It is a claim generator: every
            // person still in it at market-close that is no longer hireable is reported to the host
            // as "the guest hired them", and the host deletes them from the canonical pool. A
            // baseline left over from an earlier snapshot (the host docking here, or a previous
            // visit) describes people this client's own OfficerManagerEvent may since have pruned,
            // so if the reply does not land before the screen closes the guest silently wipes the
            // host's pool. No snapshot, no claim.
            appliedHireables.remove(market.getId());
            send(CoopMessages.marketOpen(session.sessionId(), service.nextSeq(), now(),
                    market.getId(), session.localPlayerId()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_OPEN requested market=" + market.getId());
        }
        // When the host opens, its engine market is already canonical; the guest (if it later opens
        // the same market) pulls it via MARKET_OPEN. Simultaneous same-market use is prevented by
        // the Phase 10 gate, whose WAN race Phase 18 closes (the per-submarket mutex that this line
        // used to promise was cancelled on 2026-08-20 — see the Phase 18 banner).
    }

    /**
     * Phase 18: the local player left a market screen. Pure forwarding — the observer (the pump)
     * uses it to confirm a rejected interaction's dialog is gone; Phase 24 will diff the colony
     * state here.
     */
    @Override
    public void onPlayerClosedMarket(MarketAPI market) {
        if (market == null) {
            return;
        }
        // Phase 12c gap 2d: there is no vanilla hire event, so the guest claims its hires here by
        // diffing the market's hireable set against the set the last snapshot applied.
        if (isGuest() && isActive() && !replayGuard.isReplaying()) {
            try {
                reportHiresOnClose(market);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to diff hireable pool on market close", ex);
            }
        }
        if (marketCloseObserver == null) {
            return;
        }
        String entityId = null;
        try {
            SectorEntityToken primary = market.getPrimaryEntity();
            entityId = primary == null ? null : primary.getId();
        } catch (RuntimeException | LinkageError ex) {
            // A procgen/local market may have no primary entity; the market id alone still helps.
        }
        marketCloseObserver.onMarketClosed(entityId, market.getId());
    }

    /** Observer of the vanilla market-close callback; wired to the Phase 18 reject bookkeeping. */
    public interface MarketCloseObserver {
        /**
         * @param entityId the market's primary entity id (the id the interaction gate claims), or
         *                 null when the market has no primary entity.
         * @param marketId the market's own id.
         */
        void onMarketClosed(String entityId, String marketId);
    }

    private MarketCloseObserver marketCloseObserver;

    public void setMarketCloseObserver(MarketCloseObserver observer) {
        this.marketCloseObserver = observer;
    }

    /** Host: a player opened a market; capture the canonical open-market stock and send it. */
    private void handleMarketOpen(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        String marketId = CoopMessages.requiredPayloadString(message, "marketId");
        MarketAPI market = findMarket(marketId);
        if (market == null) {
            // Expected, not an anomaly: the guest opens uncolonized/procgen entities (derelicts,
            // survey targets, ruins) whose "market" is a local, unregistered MarketAPI with no
            // counterpart in the host's economy. There is nothing canonical to snapshot, so the
            // guest's own local one stands. Debug level so a routine exploration run does not spam
            // warnings.
            CoopLog.debug(CoopCampaignReplicator.class,
                    "Coop MARKET_OPEN skipped: no host-side market for id=" + marketId
                            + " (uncolonized/procgen entity; the guest keeps its local one)");
            return;
        }
        broadcastMarketSnapshot(market);
    }

    /** Host: capture the canonical open-market stock (all item kinds) and broadcast it. */
    private void broadcastMarketSnapshot(MarketAPI market) {
        // The host is canonical, so it must be *stocked* before it is canonical: a market the host has
        // never docked at has never had its stock generated, and snapshotting it would hand the guest
        // an empty shop. See ensureOpenMarketStocked.
        ensureOpenMarketStocked(market);
        List<CoopMarketSync.StockItem> items = captureOpenMarketStock(market);
        items.addAll(captureHireablePool(market));
        marketSync.applySnapshot(market.getId(), items);
        send(CoopMessages.marketSnapshot(session.sessionId(), service.nextSeq(), now(),
                market.getId(), CoopMarketSync.encodeStock(items)));
        CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_SNAPSHOT market=" + market.getId()
                + " items=" + items.size() + " " + kindBreakdown(items));
    }

    private String kindBreakdown(List<CoopMarketSync.StockItem> items) {
        Map<CoopMarketSync.ItemKind, Integer> byKind = new LinkedHashMap<>();
        for (CoopMarketSync.StockItem item : items) {
            byKind.merge(item.kind(), 1, Integer::sum);
        }
        return byKind.toString();
    }

    /**
     * Broadcast a captured mission/bar pool (host), along with the {@code BarEventManager} seed the
     * guest needs to shuffle it into the same shown subset ({@code 0} = not carrying one).
     */
    public void broadcastMissionPool(String marketId, List<CoopMissionBoardSync.Entry> entries, long barSeed) {
        if (!isHost() || !isActive()) {
            return;
        }
        missionBoard.applySnapshot(entries);
        send(CoopMessages.missionPoolSnapshot(session.sessionId(), service.nextSeq(), now(),
                marketId, CoopMissionBoardSync.encodePool(entries), barSeed));
    }

    /**
     * Phase 12c host bar-pool watcher: poll the global portside pool, and on any membership, seed,
     * pin or <em>order</em> change push the whole ordered list to the guest.
     *
     * <p>Push, not request/response. The pool is sector-global rather than per-market, so there is
     * nothing to fetch on market open, and a player who clicks the bar option the same frame they
     * dock would beat a round trip anyway. The poll is cheap: a walk of a list that holds a handful of
     * events, reading one field each.
     */
    public void tickBarPool() {
        if (!isHost() || !isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastBarPoolPollMillis < BAR_POOL_POLL_INTERVAL_MILLIS) {
            return;
        }
        lastBarPoolPollMillis = nowMillis;
        List<CoopMissionBoardSync.Entry> entries = barPoolCapture.capture();
        // Null means "could not read the pool", which is not the same as "the pool is empty" — an
        // empty snapshot tells the guest to clear its bar, so it must only ever be a real reading.
        if (entries == null || !barPoolCapture.markChanged(entries)) {
            return;
        }
        // The manager seed rides with the pool: it is what BarCMD shuffles the pool with, so sending
        // one without the other still shows the two players different bars.
        Long barSeed = CoopBarSync.hostSeed();
        broadcastMissionPool(BAR_POOL_MARKET_ID, entries, barSeed == null ? 0L : barSeed);
        CoopLog.info(CoopCampaignReplicator.class, "Coop MISSION_POOL_SNAPSHOT bar offers="
                + entries.size() + " barSeed=" + (barSeed == null ? "unreadable" : barSeed));
    }

    private void applyMissionPool(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        List<CoopMissionBoardSync.Entry> entries = CoopMissionBoardSync.decodePool(
                CoopMessages.requiredPayloadString(message, "pool"));
        missionBoard.applySnapshot(entries);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied MISSION_POOL_SNAPSHOT entries=" + entries.size());
        long barSeed = CoopMessages.requiredPayloadLong(message, "barSeed");
        if (barSeed != 0L) {
            CoopBarSync.applySeed(barSeed);
        }
        // Bar offers are the one pool source that has a live engine counterpart to rebuild; contact
        // and bounty entries stay per-player by design and are model-only here. Going through
        // visibleEntriesFor means an offer the host has already claimed never reaches the guest's
        // pool, which is the existing first-come machinery doing the arbitration.
        String playerId = session.localPlayerId();
        List<CoopMissionBoardSync.Entry> offers = playerId == null || playerId.trim().isEmpty()
                ? missionBoard.pool()
                : missionBoard.visibleEntriesFor(playerId);
        barPoolInjector.apply(offers, playerId);
    }

    private void hostHandleMissionClaim(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        String missionId = CoopMessages.requiredPayloadString(message, "missionId");
        String playerId = CoopMessages.requiredPayloadString(message, "playerId");
        CoopMissionBoardSync.ClaimResult result = missionBoard.arbitrate(missionId, playerId);
        if (result.accepted()) {
            send(CoopMessages.missionClaimAccept(session.sessionId(), service.nextSeq(), now(),
                    missionId, playerId, result.hostSeq()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop mission claim accepted missionId=" + missionId
                    + " playerId=" + playerId + " hostSeq=" + result.hostSeq());
        } else {
            send(CoopMessages.missionClaimReject(session.sessionId(), service.nextSeq(), now(),
                    missionId, result.rejectReason()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop mission claim rejected missionId=" + missionId
                    + " requester=" + playerId + " " + result.rejectReason());
        }
    }

    /** Host-local mission acceptance: arbitrate then broadcast the accept to the guest. */
    public boolean hostClaimMissionLocally(String missionId) {
        if (!isHost() || !isActive()) {
            return false;
        }
        CoopMissionBoardSync.ClaimResult result = missionBoard.arbitrate(missionId, session.localPlayerId());
        if (result.accepted()) {
            send(CoopMessages.missionClaimAccept(session.sessionId(), service.nextSeq(), now(),
                    missionId, session.localPlayerId(), result.hostSeq()));
        }
        return result.accepted();
    }

    /** Guest-local mission acceptance: request the claim from the host. */
    public void guestRequestMissionClaim(String missionId) {
        if (!isGuest() || !isActive()) {
            return;
        }
        send(CoopMessages.missionClaimRequest(session.sessionId(), service.nextSeq(), now(),
                missionId, session.localPlayerId()));
    }

    private void guestApplyMissionAccept(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopMissionClaim claim = new CoopMissionClaim(
                CoopMessages.requiredPayloadString(message, "missionId"),
                CoopMessages.requiredPayloadString(message, "playerId"),
                CoopMessages.requiredPayloadLong(message, "hostSeq"));
        missionBoard.applyAccepted(claim);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied MISSION_CLAIM_ACCEPT missionId="
                + claim.missionId() + " playerId=" + claim.acceptedByPlayerId());
    }

    private void guestApplyMissionReject(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        CoopLog.warn(CoopCampaignReplicator.class, "Coop mission claim rejected missionId="
                + CoopMessages.requiredPayloadString(message, "missionId") + " "
                + CoopMessages.requiredPayloadString(message, "reason"));
    }

    // ---- Market contents + transactions -------------------------------------------------------

    @Override
    public void onPlayerMarketTransaction(PlayerMarketTransaction transaction) {
        if (replayGuard.isReplaying() || !isActive() || transaction == null
                || transaction.getMarket() == null) {
            return;
        }
        // The host's engine market is canonical and was already mutated by its own vanilla
        // transaction, so the host needs no coop action here. The guest reports each commodity delta;
        // the host applies it to its canonical market. Since both sides started identical at open and
        // apply the same delta, displayed quantities stay consistent without any live re-sync.
        if (!isGuest()) {
            return;
        }
        try {
            String marketId = transaction.getMarket().getId();
            // Bought: item leaves the market (+qty removed from stock). Sold: it returns (-qty).
            reportCargoDeltas(marketId, transaction.getBought(), +1);
            reportCargoDeltas(marketId, transaction.getSold(), -1);
            reportShipDeltas(marketId, transaction.getShipsBought(), +1);
            reportShipDeltas(marketId, transaction.getShipsSold(), -1);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to report market transaction", ex);
        }
    }

    /** Report commodity/weapon/fighter deltas from one side of a transaction (sign +1 bought, -1 sold). */
    private void reportCargoDeltas(String marketId, CargoAPI cargo, int sign) {
        if (cargo == null) {
            return;
        }
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            StackRef ref = classify(stack);
            if (ref == null) {
                continue;
            }
            int qty = Math.round(stack.getSize()) * sign;
            if (qty != 0) {
                sendMarketTxn(marketId, ref.kind(), ref.id(), qty);
            }
        }
    }

    /**
     * Report ship deltas, one line per hull, keyed by the fleet member's id.
     *
     * <p>Member id, not variant id: a listing is a specific hull with its own D-mods and CR (see
     * {@link CoopShipDetail}), and the ids match across clients because the guest reconstructs each
     * listing with {@code setId} from the host's snapshot. So a <b>bought</b> ship is stripped from
     * the host's shelf by id, and a ship <b>sold back</b> carries its full detail blob so the host
     * shelves the battered hull the player actually handed over rather than a pristine reroll.
     */
    private void reportShipDeltas(String marketId, List<PlayerMarketTransaction.ShipSaleInfo> ships, int sign) {
        if (ships == null) {
            return;
        }
        for (PlayerMarketTransaction.ShipSaleInfo info : ships) {
            CoopShipDetail detail = captureShipDetail(info == null ? null : info.getMember());
            if (detail == null) {
                continue;
            }
            sendMarketTxn(marketId, CoopMarketSync.ItemKind.SHIP, detail.memberId(), sign,
                    sign < 0 ? detail.encode() : "");
        }
    }

    private void sendMarketTxn(String marketId, CoopMarketSync.ItemKind kind, String itemId, int qty) {
        sendMarketTxn(marketId, kind, itemId, qty, "");
    }

    private void sendMarketTxn(String marketId, CoopMarketSync.ItemKind kind, String itemId, int qty,
                               String detail) {
        send(CoopMessages.marketTxn(session.sessionId(), service.nextSeq(), now(),
                marketId, kind.name(), itemId, qty, 0f, session.localPlayerId(), detail));
        CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_TXN sent market=" + marketId
                + " " + kind + ":" + itemId + " qty=" + qty);
    }

    private void hostApplyMarketTxn(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        String marketId = CoopMessages.requiredPayloadString(message, "marketId");
        CoopMarketSync.ItemKind kind = CoopMarketSync.ItemKind.valueOf(
                CoopMessages.requiredPayloadString(message, "kind"));
        String itemId = CoopMessages.requiredPayloadString(message, "itemId");
        int qty = (int) CoopMessages.requiredPayloadLong(message, "qty");
        String detail = CoopMessages.requiredPayloadString(message, "detail");
        // Keep the in-memory model in step (used by tests / future assertions).
        marketSync.applyTransaction(new CoopMarketSync.Transaction(marketId, kind, itemId, qty,
                CoopMessages.requiredPayloadFloat(message, "unitPrice"), detail));
        // A hire is an availability removal on a second engine structure (the officer manager's pools),
        // not a cargo delta, so it routes past applyItemDeltaToEngine entirely. No credit deduction:
        // credits are per-player and the guest's own engine already charged the hiring bonus.
        boolean applied = CoopPersonDetail.roleOf(kind) != null
                ? applyHireToEngine(marketId, itemId)
                : applyItemDeltaToEngine(marketId, kind, itemId, qty, detail);
        // Only claim "applied" when the engine mutation ran; the previous unconditional log asserted
        // success over a silent no-op, which is how the propagation bug stayed invisible.
        if (applied) {
            CoopLog.info(CoopCampaignReplicator.class, "Coop applied MARKET_TXN market=" + marketId
                    + " " + kind + ":" + itemId + " qty=" + qty);
        }
    }

    private void applyMarketSnapshot(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        String marketId = CoopMessages.requiredPayloadString(message, "marketId");
        List<CoopMarketSync.StockItem> items = CoopMarketSync.decodeStock(
                CoopMessages.requiredPayloadString(message, "stock"));
        marketSync.applySnapshot(marketId, items);
        // One-shot apply to the guest's engine open-market so it shows the host's canonical stock.
        applySnapshotToEngine(marketId, items);
        // The hireable pool lives on the market, not in the submarket cargo, so it applies even when
        // the guest has no materialized open-market cargo to replace.
        applyHireablePool(findMarket(marketId), items);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied MARKET_SNAPSHOT market=" + marketId
                + " items=" + items.size());
    }

    /** Guest: replace the open-market stock (all kinds) with the host's canonical set. */
    private void applySnapshotToEngine(String marketId, List<CoopMarketSync.StockItem> items) {
        CargoAPI cargo = openMarketCargo(marketId);
        if (cargo == null) {
            return;
        }
        replayGuard.begin();
        try {
            // A snapshot is a full *replacement*, not a quantity-merge. Weapon/fighter/ship TYPES are
            // rolled independently per instance, so just setting the host's item quantities would
            // leave the guest's own roll behind (the host set unioned with the guest's). So strip the
            // guest's current weapons/fighters/ships and any commodity the host no longer stocks,
            // then add the host's canonical set back. After this the guest holds exactly the host set.
            Set<String> snapshotCommodities = new HashSet<>();
            for (CoopMarketSync.StockItem item : items) {
                if (item.kind() == CoopMarketSync.ItemKind.COMMODITY) {
                    snapshotCommodities.add(item.itemId());
                }
            }
            for (CargoStackAPI stack : cargo.getStacksCopy()) {
                StackRef ref = classify(stack);
                if (ref == null) {
                    continue;
                }
                int size = Math.round(stack.getSize());
                switch (ref.kind()) {
                    case WEAPON -> cargo.removeWeapons(ref.id(), size);
                    case FIGHTER -> cargo.removeFighters(ref.id(), size);
                    // Specials are rolled per instance like weapons (which nanoforge, which core,
                    // which blueprint), so they are stripped wholesale and re-added from the host set.
                    case SPECIAL -> removeSpecial(cargo, ref.id(), size);
                    case COMMODITY -> {
                        if (!snapshotCommodities.contains(ref.id())) {
                            cargo.removeCommodity(ref.id(), size);
                        }
                    }
                    default -> { /* nothing */ }
                }
            }
            clearMothballedShips(cargo);
            for (CoopMarketSync.StockItem item : items) {
                try {
                    switch (item.kind()) {
                        // Commodities survive the strip (shared list), so set them to the target.
                        case COMMODITY -> setCommodityQuantity(cargo, item.itemId(), item.quantity());
                        // Weapons/fighters/specials/ships were stripped to zero, so just add the host's.
                        case WEAPON -> cargo.addWeapons(item.itemId(), item.quantity());
                        case FIGHTER -> cargo.addFighters(item.itemId(), item.quantity());
                        case SPECIAL -> addSpecial(cargo, item.itemId(), item.quantity());
                        case SHIP -> addMothballedShipFromDetail(cargo, item.detail());
                        // Hireable people are not cargo; applyHireablePool handles them.
                        default -> { /* officers/mercs/admins */ }
                    }
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply snapshot item "
                            + item.kind() + ":" + item.itemId(), ex);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply market snapshot to engine", ex);
        } finally {
            replayGuard.end();
        }
    }

    /** Set a commodity stack to a target quantity via add/remove. */
    private void setCommodityQuantity(CargoAPI cargo, String commodityId, int target) {
        int delta = Math.round(target - cargo.getCommodityQuantity(commodityId));
        if (delta > 0) {
            cargo.addCommodity(commodityId, delta);
        } else if (delta < 0) {
            cargo.removeCommodity(commodityId, -delta);
        }
    }

    /**
     * Host/guest: change the canonical open-market stock by a signed delta for any item kind.
     * Returns {@code true} only when the engine mutation actually ran, so callers do not claim
     * success on a no-op.
     *
     * <p>Deliberately uses {@code getCargoNullOk()} and gives up when the submarket cargo has not
     * been materialized (the normal state for a market this client has never docked at). Bare
     * {@code getCargo()} is <em>not</em> the fix: that accessor only creates an empty cargo, so the
     * delta would land on stock that was never generated. {@link #ensureOpenMarketStocked} is what
     * materializes it properly, and it runs on the snapshot path where it belongs. Making a guest
     * purchase durable is a model problem regardless: open-market commodity stock is a stockpile the
     * engine refills toward {@code getStockpileLimit} on every interaction, so deltas do not survive.
     * Tracked as Phase 12c gap 2e.
     */
    private boolean applyItemDeltaToEngine(String marketId, CoopMarketSync.ItemKind kind, String itemId,
                                           int qty, String detail) {
        CargoAPI cargo = openMarketCargo(marketId);
        if (cargo == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN not applied to engine: no"
                    + " materialized open-market cargo for market=" + marketId + " " + kind + ":" + itemId
                    + " qty=" + qty + " (this client has not docked there)");
            return false;
        }
        replayGuard.begin();
        try {
            addItemToEngine(cargo, kind, itemId, qty, detail);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply market delta to engine "
                    + kind + ":" + itemId, ex);
            return false;
        } finally {
            replayGuard.end();
        }
    }

    // qty>0 means the buyer removed it from the market (stock decreases); qty<0 means it was sold back.
    private void addItemToEngine(CargoAPI cargo, CoopMarketSync.ItemKind kind, String itemId, int qty,
                                 String detail) {
        switch (kind) {
            case COMMODITY -> {
                if (qty > 0) {
                    cargo.removeCommodity(itemId, qty);
                } else {
                    cargo.addCommodity(itemId, -qty);
                }
            }
            case WEAPON -> {
                if (qty > 0) {
                    cargo.removeWeapons(itemId, qty);
                } else {
                    cargo.addWeapons(itemId, -qty);
                }
            }
            case FIGHTER -> {
                if (qty > 0) {
                    cargo.removeFighters(itemId, qty);
                } else {
                    cargo.addFighters(itemId, -qty);
                }
            }
            case SPECIAL -> {
                if (qty > 0) {
                    removeSpecial(cargo, itemId, qty);
                } else {
                    addSpecial(cargo, itemId, -qty);
                }
            }
            case SHIP -> {
                if (qty > 0) {
                    removeMothballedShipById(cargo, itemId);
                } else {
                    addMothballedShipFromDetail(cargo, detail);
                }
            }
            // Hires are an availability removal on the officer manager, not a cargo delta; they route
            // through applyHireToEngine before reaching here.
            default -> { /* officers/mercs/admins */ }
        }
    }

    // ---- SPECIAL stacks (Phase 12c gap 2c) ------------------------------------------------------
    //
    // A special is identified by SpecialItemData's (id, data) pair, which its equals() compares in
    // full, and removeItems matches by equality. Reconstructing an AI core's null data as "" -- or a
    // modspec's hullmod id as null -- yields an item that looks right and cannot be removed.

    private void addSpecial(CargoAPI cargo, String itemId, int quantity) {
        if (quantity <= 0) {
            return;
        }
        cargo.addSpecial(specialData(itemId), quantity);
    }

    private void removeSpecial(CargoAPI cargo, String itemId, int quantity) {
        if (quantity <= 0) {
            return;
        }
        cargo.removeItems(CargoAPI.CargoItemType.SPECIAL, specialData(itemId), quantity);
    }

    private static SpecialItemData specialData(String itemId) {
        return new SpecialItemData(CoopMarketSync.specialId(itemId), CoopMarketSync.specialData(itemId));
    }

    // ---- Mothballed ship listings ---------------------------------------------------------------

    /**
     * Rebuilds one listed hull from its {@link CoopShipDetail}, D-mods, refit, CR and all.
     *
     * <p>The variant is always {@code clone()}d before it is touched: {@code createFleetMember} can
     * hand back a shared stock variant, and mutating that would rewrite the hull for every ship in the
     * sector using it (vanilla does the same dance in {@code ShipRecoverySpecial}). Setting the source
     * to REFIT and clearing the original-variant link is what makes the copy a standalone,
     * independently-modifiable variant rather than a view onto the stock one — and it is also what
     * D-modding does, so a captured D-hull round-trips into the same shape it came from.
     */
    private void addMothballedShipFromDetail(CargoAPI cargo, String encodedDetail) {
        if (encodedDetail == null || encodedDetail.isEmpty()) {
            return;
        }
        CoopShipDetail detail;
        try {
            detail = CoopShipDetail.decode(encodedDetail);
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Malformed ship detail blob; listing skipped", ex);
            return;
        }
        FleetDataAPI ships = mothballedShips(cargo);
        if (ships == null) {
            return;
        }
        try {
            FleetMemberAPI member = createBaseMember(detail);
            if (member == null) {
                return;
            }
            ShipVariantAPI variant = member.getVariant().clone();
            variant.setSource(VariantSource.REFIT);
            variant.setOriginalVariant(null);
            if (!detail.hullSpecId().isEmpty() && variant.getHullSpec() != null
                    && !detail.hullSpecId().equals(variant.getHullSpec().getHullId())) {
                // The D-hull swap: DModManager.setDHull replaces the hull spec outright, so a listing
                // whose id says "hound" but whose spec says "hound_dhull" only survives if we do too.
                variant.setHullSpecAPI(Global.getSettings().getHullSpec(detail.hullSpecId()));
            }
            for (String modId : detail.suppressedMods()) {
                variant.addSuppressedMod(modId);
            }
            for (String modId : detail.permaMods()) {
                // Vanilla's DModManager order: a perma-mod is un-suppressed first, or the hull mod is
                // installed and inert.
                variant.removeSuppressedMod(modId);
                variant.addPermaMod(modId, detail.sMods().contains(modId));
            }
            for (String modId : detail.refitMods()) {
                variant.addMod(modId);
            }
            for (String modId : detail.sModdedBuiltIns()) {
                // Built-ins are already installed; s-modding one is an addPermaMod with the s-mod flag
                // (there is no dedicated setter, and the returned set is not documented as live). Best
                // effort: a built-in that fails to read back as s-modded is a small stat difference on
                // a shop hull, not a broken listing.
                try {
                    variant.addPermaMod(modId, true);
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.debug(CoopCampaignReplicator.class,
                            "Could not mark built-in hull mod as s-modded: " + modId);
                }
            }
            for (String slotId : new ArrayList<>(orEmptyList(variant.getNonBuiltInWeaponSlots()))) {
                variant.clearSlot(slotId);
            }
            for (Map.Entry<String, String> weapon : detail.weapons().entrySet()) {
                variant.addWeapon(weapon.getKey(), weapon.getValue());
            }
            for (Map.Entry<String, String> wing : detail.wings().entrySet()) {
                variant.setWingId(Integer.parseInt(wing.getKey()), wing.getValue());
            }
            variant.setNumFluxVents(detail.vents());
            variant.setNumFluxCapacitors(detail.caps());
            variant.autoGenerateWeaponGroups();

            member.setVariant(variant, false, true);
            member.setId(detail.memberId());
            if (!detail.shipName().isEmpty()) {
                member.setShipName(detail.shipName());
            }
            if (member.getRepairTracker() != null) {
                member.getRepairTracker().setMothballed(true);
                member.getRepairTracker().setCR(detail.baseCR());
            }
            ships.addFleetMember(member);
        } catch (RuntimeException | LinkageError ex) {
            // A variant, hull spec or hull mod this client cannot resolve (a mod mismatch): skip the
            // listing rather than crash the whole snapshot apply.
            CoopLog.warn(CoopCampaignReplicator.class, "Could not rebuild mothballed ship member="
                    + detail.memberId() + " variant=" + detail.baseVariantId(), ex);
        }
    }

    /**
     * The fleet member a rebuilt listing starts from, or null when even the hull cannot be resolved.
     *
     * <p><b>Why the variant id is not enough on its own.</b> A variant id only names a spec while the
     * variant is a stock one. A ship the player refitted before selling it back carries a runtime
     * variant id that exists on that member and nowhere else, so
     * {@code createFleetMember(SHIP, thatId)} throws on the receiving client and the listing used to
     * be dropped outright — i.e. the seller's hull disappeared from the shared shelf at the next
     * snapshot, which is worse than the pristine-rebuild gap this codec was written to close. Vanilla
     * hits the same wall and answers it the same way ({@code impl/campaign/CoreScript.java:639-641}
     * falls back off {@code isStockVariant()}).
     *
     * <p>The empty variant off the hull spec is the backstop rather than {@code getOriginalVariant()}
     * because that one is documented "may or may not be set". Nothing is lost by starting from empty:
     * every field that makes the listing itself — hull spec, perma/s/refit/suppressed mods, weapons,
     * wings, vents, caps, CR — is re-applied on top by the caller regardless of what it starts from.
     */
    private FleetMemberAPI createBaseMember(CoopShipDetail detail) {
        if (Global.getSettings().doesVariantExist(detail.baseVariantId())) {
            return Global.getFactory().createFleetMember(FleetMemberType.SHIP, detail.baseVariantId());
        }
        ShipHullSpecAPI hull = detail.hullSpecId().isEmpty()
                ? null : Global.getSettings().getHullSpec(detail.hullSpecId());
        if (hull == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                    + " names neither a known variant (" + detail.baseVariantId()
                    + ") nor a known hull (" + detail.hullSpecId() + "); skipped");
            return null;
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop ship listing member=" + detail.memberId()
                + " rebuilt from an empty " + hull.getHullId() + " variant (custom variant id "
                + detail.baseVariantId() + " is not a spec on this client)");
        return Global.getFactory().createFleetMember(FleetMemberType.SHIP,
                Global.getSettings().createEmptyVariant(detail.baseVariantId(), hull));
    }

    /** Legacy variant-id path, still used by cargo pods (which key contents by variant id). */
    private void addMothballedShipsByVariant(CargoAPI cargo, String variantId, int count) {
        FleetDataAPI ships = mothballedShips(cargo);
        if (ships == null || variantId == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            try {
                cargo.addMothballedShip(FleetMemberType.SHIP, variantId, null);
            } catch (RuntimeException | LinkageError ex) {
                // Variant not resolvable on this client (e.g. a custom autofit variant); skip it
                // rather than crash. Stock variants reconstruct cleanly; this is the documented gap.
                CoopLog.warn(CoopCampaignReplicator.class, "Could not add mothballed ship variant="
                        + variantId, ex);
                return;
            }
        }
    }

    /**
     * Removes the listing with this fleet-member id. There is no {@code getMemberWithId}, so the
     * members list is scanned; ids match across clients because the guest rebuilds each listing with
     * {@code setId} from the host's snapshot.
     */
    private void removeMothballedShipById(CargoAPI cargo, String memberId) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships == null || memberId == null) {
            return;
        }
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            if (memberId.equals(member.getId())) {
                ships.removeFleetMember(member);
                return;
            }
        }
        CoopLog.warn(CoopCampaignReplicator.class,
                "Coop ship delta: no mothballed listing with member id=" + memberId);
    }

    /**
     * The mothballed-ship roster, materializing it if the cargo has never held one.
     * {@code getMothballedShips()} returns null until {@code initMothballedShips} has run, and a
     * snapshot that lands on a fresh cargo would otherwise silently drop every ship listing.
     */
    private FleetDataAPI mothballedShips(CargoAPI cargo) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships != null) {
            return ships;
        }
        try {
            cargo.initMothballedShips(Factions.NEUTRAL);
            return cargo.getMothballedShips();
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Could not init mothballed ship storage", ex);
            return null;
        }
    }

    private void clearMothballedShips(CargoAPI cargo) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships == null) {
            return;
        }
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            ships.removeFleetMember(member);
        }
    }

    // ---- World deltas (salvage / explore / construct / parley) --------------------------------

    /** Report a guest-driven world mutation up to the host (and let the host integrate it). */
    public void reportWorldDelta(CoopWorldDelta delta) {
        if (!isActive() || delta == null) {
            return;
        }
        send(CoopMessages.worldDelta(session.sessionId(), service.nextSeq(), now(),
                delta.entityId(), delta.kind().name(), delta.consumed(),
                delta.newStateJson(), delta.actingPlayerId()));
    }

    // ---- Phase 24 M1: colony raids + bombardments ----------------------------------------------

    @Override
    public boolean shouldCaptureRaidOutcome() {
        // The replay guard is load-bearing here, not defensive: applying a remote outcome re-drives
        // the same vanilla effects, and without the guard the applier's own listener would capture
        // them as a fresh act and bounce it back.
        return isActive() && !replayGuard.isReplaying();
    }

    @Override
    public String raidActingPlayerId() {
        return session.localPlayerId();
    }

    /**
     * Either player finished a raid or bombardment locally. Vanilla already applied it here, so this
     * only reports it; the ledger entry taken now is what makes the host's rebroadcast a no-op when
     * it comes back.
     */
    @Override
    public void onRaidOutcomeCaptured(CoopRaidOutcomeSync.Outcome outcome) {
        if (outcome == null || !isActive()) {
            return;
        }
        if (!raidLedger.apply(outcome)) {
            return;
        }
        send(CoopMessages.raidResult(session.sessionId(), service.nextSeq(), now(), outcome.encode()));
        CoopLog.info(CoopCampaignReplicator.class, "Coop captured RAID_RESULT " + outcome.kind()
                + " market=" + outcome.marketId() + " id=" + outcome.outcomeId()
                + " industries=" + outcome.industries().size()
                + " deficits=" + outcome.deficits().size() + " deciv=" + outcome.decivilized());
    }

    private void handleRaidResult(CoopMessages.Message message) {
        CoopRaidOutcomeSync.Outcome outcome = CoopRaidOutcomeSync.decode(
                CoopMessages.requiredPayloadString(message, "outcome"));
        boolean firstApply = raidLedger.apply(outcome);
        if (firstApply) {
            replayGuard.begin();
            try {
                CoopRaidOutcomeSync.applyToEngine(outcome);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply RAID_RESULT", ex);
            } finally {
                replayGuard.end();
            }
        }
        // The host owns the canonical market: it integrates the guest's report and rebroadcasts so
        // both clients converge. The originator's ledger entry kills the echo.
        if (isHost() && isActive()) {
            send(CoopMessages.raidResult(session.sessionId(), service.nextSeq(), now(),
                    outcome.encode()));
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop RAID_RESULT " + outcome.kind() + " market="
                + outcome.marketId() + " id=" + outcome.outcomeId() + " firstApply=" + firstApply);
    }

    private void handleWorldDelta(CoopMessages.Message message) {
        CoopWorldDelta delta = new CoopWorldDelta(
                CoopMessages.requiredPayloadString(message, "entityId"),
                CoopWorldDelta.Kind.valueOf(CoopMessages.requiredPayloadString(message, "kind")),
                Boolean.parseBoolean(CoopMessages.requiredPayloadString(message, "consumed")),
                CoopMessages.requiredPayloadString(message, "newStateJson"),
                CoopMessages.requiredPayloadString(message, "actingPlayerId"));
        boolean firstApply = worldLedger.apply(delta);
        if (firstApply) {
            replayGuard.begin();
            try {
                applyWorldDeltaToEngine(delta);
            } catch (RuntimeException | LinkageError ex) {
                CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply WORLD_DELTA", ex);
            } finally {
                replayGuard.end();
            }
        }
        // Drop the entity from the local salvage baseline so applying this remote consume isn't
        // re-detected by our own watcher next tick as a fresh local salvage.
        trackedSalvageables.remove(delta.entityId());
        // Host integrates the guest report into authoritative state and rebroadcasts so both clients
        // converge; idempotency on the ledger means the echo is a no-op on the originator.
        if (isHost() && isActive()) {
            send(CoopMessages.worldDelta(session.sessionId(), service.nextSeq(), now(),
                    delta.entityId(), delta.kind().name(), delta.consumed(),
                    delta.newStateJson(), delta.actingPlayerId()));
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop WORLD_DELTA " + delta.kind() + " entity="
                + delta.entityId() + " consumed=" + delta.consumed() + " firstApply=" + firstApply);
    }

    private void applyWorldDeltaToEngine(CoopWorldDelta delta) {
        switch (delta.kind()) {
            case SPAWN -> {
                applySpawnToEngine(delta);
                return;
            }
            case DECIV -> {
                applyDecivToEngine(delta);
                return;
            }
            case OBJECTIVE_OWNERSHIP -> {
                applyObjectiveOwnershipToEngine(delta);
                return;
            }
            case GATE_ACTIVATED -> {
                applyGateStateToEngine(delta);
                return;
            }
            case SURVEY -> {
                applySurveyLevelToEngine(delta);
                return;
            }
            case RUINS_EXPLORED -> {
                applyRuinsExploredToEngine(delta);
                return;
            }
            default -> {
                // Fall through to the consume path below.
            }
        }
        if (!delta.consumed()) {
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getHyperspace() == null) {
            return;
        }
        // Remove the consumed entity wherever it lives so it cannot be re-looted on this client.
        // Resolved by coop id first for replicated entities, whose engine ids differ per client.
        SectorEntityToken entity = findEntityForDelta(sector, delta.entityId());
        if (entity != null && entity.getContainingLocation() != null) {
            entity.getContainingLocation().removeEntity(entity);
        }
    }

    // ---- Phase 13 skeleton mutations (DECIV / OBJECTIVE_OWNERSHIP / GATE_ACTIVATED) ------------

    /**
     * Host: a market decivilized. Captured through vanilla's own {@code ColonyDecivListener} rather
     * than a poll — the listener fires exactly once from inside {@code DecivTracker.decivilize}
     * (api_src {@code intel/deciv/DecivTracker.java:282}), whereas a poll would have to infer deciv
     * from a market leaving the economy, which also happens when a pirate base ends.
     */
    private void captureDeciv(MarketAPI market, boolean fullyDestroyed) {
        if (!isHost() || !isActive() || market == null || replayGuard.isReplaying()) {
            return;
        }
        try {
            CoopWorldDelta delta = new CoopWorldDelta(market.getId(), CoopWorldDelta.Kind.DECIV, false,
                    CoopSkeletonMutationWatcher.encodeDeciv(fullyDestroyed), session.localPlayerId());
            if (worldLedger.apply(delta)) {
                reportWorldDelta(delta);
                CoopLog.info(CoopCampaignReplicator.class, "Coop captured DECIV market="
                        + market.getId() + " fullDestroy=" + fullyDestroyed);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture DECIV", ex);
        }
    }

    /**
     * Guest: reproduce the host's decivilization by calling the same public static routine the host's
     * tracker called ({@code DecivTracker.decivilize(market, fullDestroy, true)}, api_src
     * {@code intel/deciv/DecivTracker.java:189}). Re-deriving its twenty-odd steps by hand would be a
     * second implementation to keep in sync with every engine update; the vanilla call is exact.
     */
    private void applyDecivToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getEconomy() == null) {
            return;
        }
        MarketAPI market = sector.getEconomy().getMarket(delta.entityId());
        CoopSkeletonMutationWatcher.DecivDecision decision = CoopSkeletonMutationWatcher.decideDeciv(
                market != null,
                market != null && market.hasCondition(Conditions.DECIVILIZED),
                market != null && market.getPrimaryEntity() != null);
        if (decision != CoopSkeletonMutationWatcher.DecivDecision.DECIVILIZE) {
            CoopLog.warn(CoopCampaignReplicator.class, "DECIV market=" + delta.entityId()
                    + " skipped: " + decision);
            return;
        }
        DecivTracker.decivilize(market, CoopSkeletonMutationWatcher.decodeDecivFullDestroy(
                delta.newStateJson()), true);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied DECIV market=" + delta.entityId());
    }

    /**
     * Flip a campaign objective's owner the way vanilla's own capture does — {@code setFaction} plus
     * clearing the non-functional memory flag ({@code rulecmd/salvage/Objectives.control}, api_src
     * lines 304-312). Runs on both roles since Phase 12c: host&rarr;guest for a war-sim flip, and
     * guest&rarr;host for an objective the guest captured in its own dialog.
     *
     * <p>Deliberately <em>not</em> mirrored: {@code ListenerUtil.reportObjectiveChangedHands}. Its
     * only core implementor is {@code WarSimScript} ({@code command/WarSimScript.java:314}), which
     * answers by spawning response fleets — the exact simulation the guest suppresses. Skipping it on
     * the host too means a guest capture draws no war-sim retaliation where the host's own capture
     * would; that is the conservative side of the trade (a missing response fleet, not a phantom one)
     * and stays consistent with the guest, whose sim could not mirror the spawn anyway. The
     * dialog-only reputation hit and the comm-sniffer unhack are likewise player-local concerns.
     */
    private void applyObjectiveOwnershipToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        String factionId = delta.newStateJson().trim();
        if (factionId.isEmpty()) {
            return;
        }
        SectorEntityToken objective = findEntityForDelta(sector, delta.entityId());
        if (objective == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "OBJECTIVE_OWNERSHIP for unknown entity "
                    + delta.entityId() + "; skipping");
            return;
        }
        FactionAPI current = objective.getFaction();
        if (!CoopSkeletonMutationWatcher.shouldSetObjectiveFaction(
                current == null ? null : current.getId(), factionId)) {
            return;
        }
        objective.setFaction(factionId);
        MemoryAPI memory = objective.getMemoryWithoutUpdate();
        if (memory != null) {
            memory.unset(MemFlags.OBJECTIVE_NON_FUNCTIONAL);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied OBJECTIVE_OWNERSHIP entity="
                + delta.entityId() + " faction=" + factionId);
    }

    /**
     * Guest: mirror the host's gate state. Vanilla's {@code madeActive} latch is private and derived
     * ({@code GateEntityPlugin.advance}, api_src lines 274-284, sets it once
     * {@code canUseGates() && isScanned(entity)}), so setting the two inputs is both sufficient and
     * reflection-free: the guest's own gate plugin latches on its next frame.
     *
     * <p>The sector-global flags are applied even when the gate itself does not resolve — they are
     * the half that makes every gate usable — and are only ever set, never unset.
     */
    private void applyGateStateToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        CoopSkeletonMutationWatcher.GateState state =
                CoopSkeletonMutationWatcher.decodeGateState(delta.newStateJson());
        MemoryAPI sectorMemory = sector.getMemoryWithoutUpdate();
        SectorEntityToken gate = findEntityForDelta(sector, delta.entityId());
        if (gate == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "GATE_ACTIVATED for unknown entity "
                    + delta.entityId() + "; applying sector gate flags only");
        }
        CoopSkeletonMutationWatcher.GateApply apply = CoopSkeletonMutationWatcher.decideGate(state,
                sectorMemory != null && sectorMemory.getBoolean(GateEntityPlugin.GATES_ACTIVE),
                sectorMemory != null && sectorMemory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES),
                gate == null || GateEntityPlugin.isScanned(gate));
        if (apply.isNoOp()) {
            return;
        }
        if (sectorMemory != null) {
            if (apply.setGatesActive()) {
                sectorMemory.set(GateEntityPlugin.GATES_ACTIVE, true);
            }
            if (apply.setCanUseGates()) {
                sectorMemory.set(GateEntityPlugin.PLAYER_CAN_USE_GATES, true);
            }
        }
        if (apply.setScanned() && gate.getMemoryWithoutUpdate() != null) {
            gate.getMemoryWithoutUpdate().set(GateEntityPlugin.GATE_SCANNED, true);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied GATE_ACTIVATED entity="
                + delta.entityId() + " " + apply);
    }

    /**
     * Raise a planet's survey level to the reported one (Phase 12c, plan gap 5). Max-wins by ordinal:
     * the level is monotonic in vanilla (nothing ever lowers it), so taking the higher of the two
     * makes the apply idempotent, commutative and reorder-proof — a SEEN arriving after a FULL, which
     * two independently polling clients can absolutely produce, is simply dropped.
     *
     * <p>FULL goes through {@code Misc.setFullySurveyed} (api_src {@code util/Misc.java:3003-3009})
     * rather than the plain setter, because the enum is only half of "fully surveyed": vanilla also
     * flips every {@code MarketConditionAPI.setSurveyed} bit, and without that the peer's planet reads
     * as FULL with its conditions still hidden. {@code withNotification=false} — the message belongs
     * to the player who actually ran the survey.
     */
    private void applySurveyLevelToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        MarketAPI market = planetMarketForDelta(sector, delta);
        if (market == null) {
            return;
        }
        MarketAPI.SurveyLevel incoming;
        try {
            incoming = MarketAPI.SurveyLevel.valueOf(delta.newStateJson().trim());
        } catch (IllegalArgumentException ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "SURVEY entity=" + delta.entityId()
                    + " carries unknown level '" + delta.newStateJson() + "'; skipping");
            return;
        }
        MarketAPI.SurveyLevel current = market.getSurveyLevel();
        if (current != null && incoming.ordinal() <= current.ordinal()) {
            return;
        }
        if (incoming == MarketAPI.SurveyLevel.FULL) {
            setFullySurveyed(market);
        } else {
            market.setSurveyLevel(incoming);
        }
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied SURVEY entity=" + delta.entityId()
                + " level=" + incoming + " (was " + current + ")");
    }

    /**
     * Vanilla's routine first, so an engine update to it is picked up for free. Its two writes are
     * repeated inline if the class will not load: every static field on {@code Misc} initializes off
     * {@code Global.getSettings()} ({@code util/Misc.java:196}), which exists in the game and not in
     * a unit test, and a {@code NoClassDefFoundError} there must not cost the guest its survey.
     */
    private static void setFullySurveyed(MarketAPI market) {
        try {
            Misc.setFullySurveyed(market, null, false);
            return;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "Misc.setFullySurveyed unavailable; applying its writes directly", ex);
        }
        List<MarketConditionAPI> conditions = market.getConditions();
        if (conditions != null) {
            for (MarketConditionAPI condition : conditions) {
                if (condition != null) {
                    condition.setSurveyed(true);
                }
            }
        }
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
    }

    /**
     * {@code Misc.hasRuins} without the {@code Misc} class dependency — the four condition ids are
     * compile-time constants ({@code util/Misc.java:5883-5889}).
     */
    private static boolean hasRuins(MarketAPI market) {
        return market.hasCondition(Conditions.RUINS_SCATTERED)
                || market.hasCondition(Conditions.RUINS_WIDESPREAD)
                || market.hasCondition(Conditions.RUINS_EXTENSIVE)
                || market.hasCondition(Conditions.RUINS_VAST);
    }

    /**
     * Mirror the {@code $ruinsExplored} flag vanilla's {@code salRuins_postSalvagePerform} rule sets
     * on the acting client only. One-way: the flag is never cleared, so a report that says anything
     * but {@code true} is ignored rather than unsetting a flag the local player earned.
     */
    private void applyRuinsExploredToEngine(CoopWorldDelta delta) {
        if (!Boolean.parseBoolean(delta.newStateJson().trim())) {
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        MarketAPI market = planetMarketForDelta(sector, delta);
        if (market == null) {
            return;
        }
        MemoryAPI memory = market.getMemoryWithoutUpdate();
        if (memory == null
                || memory.getBoolean(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG)) {
            return;
        }
        memory.set(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG, true);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied RUINS_EXPLORED entity="
                + delta.entityId());
    }

    /** Resolves the planet market a {@link CoopWorldDelta.Kind#SURVEY}-family delta targets. */
    private MarketAPI planetMarketForDelta(SectorAPI sector, CoopWorldDelta delta) {
        SectorEntityToken entity = findEntityForDelta(sector, delta.entityId());
        MarketAPI market = entity == null ? null : entity.getMarket();
        if (market == null) {
            CoopLog.warn(CoopCampaignReplicator.class, delta.kind() + " for entity "
                    + delta.entityId() + " with no market here; skipping");
        }
        return market;
    }

    /**
     * Slow poll for the skeleton mutations that have no usable capture event — campaign objective
     * ownership (the war sim's own listener is the sim we suppress guest-side), story gate activation
     * (no event at all), and since Phase 12c planet survey levels plus ruins exploration (four of the
     * five survey mutation paths fire no listener, and rules.csv sets {@code $ruinsExplored} directly).
     *
     * <p><b>Survey and ruins run on both roles</b> for the same reason objectives do: both players
     * survey, and the flip has to reach the other side either way. Apply is max-wins on the level's
     * ordinal, which makes the two independent polls converge no matter what order the deltas land in.
     *
     * <p><b>Objectives run on both roles</b> (Phase 12c, plan gap 3b). The guest can capture a comm
     * relay / nav buoy / sensor array through its own local interaction dialog — {@code
     * Objectives.control} runs entirely client-side — and before this the flip stayed guest-local
     * until the host's war sim happened to overwrite it. Reporting upward on the same
     * {@code WORLD_DELTA(OBJECTIVE_OWNERSHIP)} channel makes the guest's capture authoritative: the
     * host applies it in {@link #applyObjectiveOwnershipToEngine} and rebroadcasts, and the ledger's
     * latest-wins payload dedup absorbs both the host's echo back to the guest and the guest's own
     * next poll (which sees the value it already recorded).
     *
     * <p><b>Gates and deciv stay host-only.</b> Both of the guest's producers for those are
     * suppressed — there is no guest-side sim to observe — so a guest poll could only ever report the
     * host's own change back at it.
     */
    private void tickSkeletonMutations() {
        if (!isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastSkeletonPollMillis < SKELETON_POLL_INTERVAL_MILLIS) {
            return;
        }
        lastSkeletonPollMillis = nowMillis;
        boolean host = isHost();
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) {
                return;
            }
            MemoryAPI sectorMemory = sector.getMemoryWithoutUpdate();
            boolean gatesActive = sectorMemory != null
                    && sectorMemory.getBoolean(GateEntityPlugin.GATES_ACTIVE);
            boolean canUseGates = sectorMemory != null
                    && sectorMemory.getBoolean(GateEntityPlugin.PLAYER_CAN_USE_GATES);

            Map<String, String> objectiveOwners = new LinkedHashMap<>();
            Map<String, String> gateStates = new LinkedHashMap<>();
            Map<String, String> surveyLevels = new LinkedHashMap<>();
            Map<String, String> ruinsExplored = new LinkedHashMap<>();
            for (LocationAPI location : sector.getAllLocations()) {
                if (location == null) {
                    continue;
                }
                collectObjectiveOwners(location, objectiveOwners);
                collectSurveyState(location, surveyLevels, ruinsExplored);
                if (host) {
                    collectGateStates(location, gatesActive, canUseGates, gateStates);
                }
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffObjectiveOwners(objectiveOwners)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, flip);
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffSurveyLevels(surveyLevels)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.SURVEY, flip);
            }
            for (CoopSkeletonMutationWatcher.Flip flip
                    : skeletonWatcher.diffRuinsExplored(ruinsExplored)) {
                emitSkeletonDelta(CoopWorldDelta.Kind.RUINS_EXPLORED, flip);
            }
            if (host) {
                for (CoopSkeletonMutationWatcher.Flip flip
                        : skeletonWatcher.diffGateStates(gateStates)) {
                    emitSkeletonDelta(CoopWorldDelta.Kind.GATE_ACTIVATED, flip);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Skeleton mutation poll failed", ex);
        }
    }

    private void collectObjectiveOwners(LocationAPI location, Map<String, String> out) {
        List<SectorEntityToken> objectives = location.getEntitiesWithTag(Tags.OBJECTIVE);
        if (objectives == null) {
            return;
        }
        for (SectorEntityToken objective : objectives) {
            if (objective == null || objective.getId() == null || objective.getFaction() == null) {
                continue;
            }
            out.put(objective.getId(), objective.getFaction().getId());
        }
    }

    private void collectGateStates(LocationAPI location, boolean gatesActive, boolean canUseGates,
                                   Map<String, String> out) {
        List<SectorEntityToken> gates = location.getEntitiesWithTag(Tags.GATE);
        if (gates == null) {
            return;
        }
        for (SectorEntityToken gate : gates) {
            if (gate == null || gate.getId() == null) {
                continue;
            }
            out.put(gate.getId(), CoopSkeletonMutationWatcher.encodeGateState(
                    GateEntityPlugin.isScanned(gate), gatesActive, canUseGates));
        }
    }

    /**
     * Reads every non-star planet's survey level and, for the ones that have ruins, whether those
     * ruins have been salvaged. Both roles: either player can survey, and the survey-data special
     * item can raise the level of a planet light-years from the player who cracked the cache, so the
     * walk is sector-wide rather than scoped to the acting client's system.
     *
     * <p>Ruins-bearing planets are fed on every pass with their current {@code true}/{@code false}
     * value rather than only when explored — the watcher only reports a change to an entry it has
     * seen before, so a map that omits the unexplored planets would never notice one becoming
     * explored.
     */
    private void collectSurveyState(LocationAPI location, Map<String, String> surveyOut,
                                    Map<String, String> ruinsOut) {
        List<PlanetAPI> planets = location.getPlanets();
        if (planets == null) {
            return;
        }
        for (PlanetAPI planet : planets) {
            if (planet == null || planet.isStar() || planet.getId() == null) {
                continue;
            }
            MarketAPI market = planet.getMarket();
            if (market == null) {
                continue;
            }
            MarketAPI.SurveyLevel level = market.getSurveyLevel();
            if (level != null) {
                surveyOut.put(planet.getId(), level.name());
            }
            if (hasRuins(market)) {
                MemoryAPI memory = market.getMemoryWithoutUpdate();
                ruinsOut.put(planet.getId(), Boolean.toString(memory != null
                        && memory.getBoolean(CoopSkeletonMutationWatcher.RUINS_EXPLORED_FLAG)));
            }
        }
    }

    /**
     * Record this client's own capture in the ledger (so the peer's echo is inert) and send it. On
     * the host that is a broadcast; on the guest it is the upward report the host then integrates.
     */
    private void emitSkeletonDelta(CoopWorldDelta.Kind kind, CoopSkeletonMutationWatcher.Flip flip) {
        CoopWorldDelta delta = new CoopWorldDelta(flip.entityId(), kind, false, flip.state(),
                session.localPlayerId());
        if (worldLedger.apply(delta)) {
            reportWorldDelta(delta);
            CoopLog.info(CoopCampaignReplicator.class, "Coop captured " + kind + " entity="
                    + flip.entityId() + " state=" + flip.state());
        }
    }

    /** Vanilla colony-deciv hook; the only capture point for {@link CoopWorldDelta.Kind#DECIV}. */
    private final class DecivCapture implements ColonyDecivListener {
        @Override
        public void reportColonyAboutToBeDecivilized(MarketAPI market, boolean fullyDestroyed) {
            // No-op: the market still holds its pre-deciv state here, and the guest reproduces the
            // whole transition from the completed report below.
        }

        @Override
        public void reportColonyDecivilized(MarketAPI market, boolean fullyDestroyed) {
            captureDeciv(market, fullyDestroyed);
        }
    }

    /**
     * Per-frame salvage watcher: when a salvageable entity at the local player's location disappears
     * (salvaged/disassembled), report it as a CONSUME world-delta so the other client removes the
     * same entity. Deterministic worldgen means the entity id matches across both clients.
     */
    public void tickWorldDeltas() {
        if (!isActive()) {
            return;
        }
        // Self-throttled, and deliberately ahead of the hyperspace early-return below: objectives and
        // gates flip whether or not the polling client is sitting in a star system.
        tickSkeletonMutations();
        long nowMillis = now();
        if (!shouldScanSalvage(lastSalvageScanMillis, nowMillis)) {
            return;
        }
        lastSalvageScanMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            LocationAPI location = player == null ? null : player.getContainingLocation();
            if (location == null || location.isHyperspace()) {
                // Nothing to watch in hyperspace; drop the baseline so re-entering a system re-seeds
                // it rather than reporting the whole previous system as "salvaged".
                trackedSalvageables.clear();
                watchedLocationId = null;
                return;
            }
            // Scratch set, cleared and refilled: a fresh HashSet per pass was pure churn.
            Set<String> current = salvageScanScratch;
            current.clear();
            for (SectorEntityToken entity : location.getAllEntities()) {
                // One getMemoryWithoutUpdate() per entity, not two — the call allocates a Memory for
                // entities that lack one, so the old track-then-key pair did that twice over the whole
                // location every frame.
                String key = consumeKeyIfTracked(entity);
                if (key != null) {
                    current.add(key);
                }
            }
            // Entering a new location: re-seed the baseline silently (the old location's entities are
            // "gone" only because the player moved, not because they were salvaged).
            if (!Objects.equals(location.getId(), watchedLocationId)) {
                watchedLocationId = location.getId();
                trackedSalvageables.clear();
                trackedSalvageables.addAll(current);
                if (CoopDebug.diagnosticsEnabled()) {
                    dumpOrbitDiagnostics(location); // dormant; opt-in via CoopDebug
                }
                return;
            }
            // A tracked entity that is no longer present was consumed by the local player this pass.
            // Collected first because the report path is free to touch the tracked set.
            salvageConsumedScratch.clear();
            for (String id : trackedSalvageables) {
                if (!current.contains(id)) {
                    salvageConsumedScratch.add(id);
                }
            }
            for (int i = 0; i < salvageConsumedScratch.size(); i++) {
                String id = salvageConsumedScratch.get(i);
                trackedSalvageables.remove(id);
                reportLocalSalvageConsume(id);
            }
            salvageConsumedScratch.clear();
            trackedSalvageables.addAll(current);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Salvage watcher failed", ex);
        }
    }

    /** The salvage watcher's timer gate (pure, unit-tested). First pass after a reset always runs. */
    static boolean shouldScanSalvage(long lastScanMillis, long nowMillis) {
        if (lastScanMillis == 0L) {
            return true; // fresh session / post-reset: seed the baseline on the first frame
        }
        long elapsed = nowMillis - lastScanMillis;
        return elapsed < 0L || elapsed >= SALVAGE_SCAN_INTERVAL_MILLIS; // negative = clock stepped back
    }

    /**
     * Dormant diagnostic (off unless {@link CoopDebug#diagnosticsEnabled()}): dump the intrinsic
     * circular-orbit parameters of every orbiting entity in a location, sorted by id, so the host and
     * guest logs can be diffed line-for-line. {@code radius+period+angle} mismatch on the same id =
     * non-deterministic orbit (e.g. the fringe jump-point); matching geometry with a tiny angle delta
     * scaling with 1/period = clock drift. Logged once per system entry while enabled.
     */
    private void dumpOrbitDiagnostics(LocationAPI location) {
        try {
            List<SectorEntityToken> orbiting = new ArrayList<>();
            for (SectorEntityToken e : location.getAllEntities()) {
                if (e instanceof CampaignFleetAPI || e.getOrbit() == null) {
                    continue;
                }
                orbiting.add(e);
            }
            orbiting.sort((a, b) -> String.valueOf(a.getId()).compareTo(String.valueOf(b.getId())));
            CoopLog.info(CoopCampaignReplicator.class, "Coop orbit-dump BEGIN loc=" + location.getId()
                    + " name=" + location.getName() + " entities=" + orbiting.size()
                    + " role=" + service.role());
            for (SectorEntityToken e : orbiting) {
                CoopLog.info(CoopCampaignReplicator.class, String.format(
                        "Coop orbit-dump id=%s type=%s focus=%s r=%.1f ang=%.2f period=%.2f pos=(%.1f,%.1f)",
                        e.getId(), e.getCustomEntityType(),
                        e.getOrbitFocus() == null ? "-" : e.getOrbitFocus().getId(),
                        e.getCircularOrbitRadius(), e.getCircularOrbitAngle(), e.getCircularOrbitPeriod(),
                        e.getLocation().x, e.getLocation().y));
            }
            CoopLog.info(CoopCampaignReplicator.class, "Coop orbit-dump END loc=" + location.getId());
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "orbit-dump failed", ex);
        }
    }

    /**
     * Host: ~1Hz, broadcast the current orbit angle of every orbiting body in the host player's
     * location so the guest can snap out clock-drift. Only the host's location is sent — that is the
     * system the players share when together, which is the only time the desync is visible.
     */
    public void tickOrbitSync() {
        if (!isHost() || !isActive()) {
            return;
        }
        long nowMillis = now();
        if (nowMillis - lastOrbitSyncMillis < ORBIT_SYNC_INTERVAL_MILLIS) {
            return;
        }
        lastOrbitSyncMillis = nowMillis;
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI player = sector == null ? null : sector.getPlayerFleet();
            LocationAPI location = player == null ? null : player.getContainingLocation();
            if (location == null || location.isHyperspace()) {
                return;
            }
            List<SectorEntityToken> bodies = syncableOrbitBodies(location);
            List<CoopOrbitSync.OrbitEntry> entries = new ArrayList<>(bodies.size());
            for (SectorEntityToken e : bodies) {
                String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
                entries.add(new CoopOrbitSync.OrbitEntry(e.getId(), focusId, e.getCircularOrbitRadius(),
                        e.getCircularOrbitPeriod(), e.getCircularOrbitAngle()));
            }
            maybeDumpOrbitBreakdown(bodies);
            if (!entries.isEmpty()) {
                send(CoopMessages.orbitSnapshot(session.sessionId(), service.nextSeq(), nowMillis,
                        location.getId(), CoopOrbitSync.encode(entries)));
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Orbit sync capture failed", ex);
        }
    }

    /** Guest: snap local orbiting bodies to the host's angles (string-id first, then orbit signature). */
    private void applyOrbitSnapshot(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        String locationId = CoopMessages.requiredPayloadString(message, "locationId");
        List<CoopOrbitSync.OrbitEntry> entries = CoopOrbitSync.decode(
                CoopMessages.requiredPayloadString(message, "orbits"));
        SectorAPI sector = Global.getSector();
        if (sector == null || entries.isEmpty()) {
            return;
        }
        // Find the target system via the first resolvable stable-id body (its containing location);
        // entity ids are global but enumerating one location bounds the signature pool.
        LocationAPI location = null;
        for (CoopOrbitSync.OrbitEntry entry : entries) {
            if (CoopOrbitSync.isStableId(entry.entityId())) {
                SectorEntityToken token = sector.getEntityById(entry.entityId());
                if (token != null && token.getContainingLocation() != null) {
                    location = token.getContainingLocation();
                    break;
                }
            }
        }
        if (location == null) {
            return;
        }
        replayGuard.begin();
        try {
            // Signature index of local syncable bodies, used only as a fallback when an entity id
            // doesn't resolve. (focus|radius|period; consumed entries are removed to break co-orbit
            // ties like the planet 'barad' sharing an orbit with the hex-id nav buoy.)
            Map<String, List<SectorEntityToken>> bySignature = new HashMap<>();
            for (SectorEntityToken e : syncableOrbitBodies(location)) {
                String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
                bySignature.computeIfAbsent(
                        CoopOrbitSync.signature(focusId, e.getCircularOrbitRadius(), e.getCircularOrbitPeriod()),
                        k -> new ArrayList<>()).add(e);
            }
            // Id index of the target location's entities, built in one pass. The old code called
            // sector.getEntityById() per entry — a sector-wide scan across every location — and at
            // ~70 entries/s that was a measured 67-82 ms frame stall once per second on the guest
            // (2026-08-20 frame-profiler session). Every body in the snapshot lives in one host
            // location, and under the seed-lock fingerprint the guest's same-id bodies live in the
            // same system, so bounding the lookup to the resolved location is also the safer match.
            Map<String, SectorEntityToken> localById = new HashMap<>();
            for (SectorEntityToken e : location.getAllEntities()) {
                if (e != null && e.getId() != null) {
                    localById.putIfAbsent(e.getId(), e);
                }
            }
            int snapped = 0;
            for (CoopOrbitSync.OrbitEntry entry : entries) {
                SectorEntityToken local = resolveLocalBody(entry, localById, bySignature);
                if (local != null) {
                    applyOrbitTo(local, entry, localById, sector);
                    snapped++;
                }
            }
            // 1 Hz, and the concat ran whether or not anything would read it (perf audit #18). It
            // earned its keep in the orbit-desync smoke tests, so it stays — behind the same
            // diagnostics gate as the orbit dump it is read alongside.
            if (CoopDebug.diagnosticsEnabled()) {
                CoopLog.info(CoopCampaignReplicator.class, "Coop ORBIT_SNAPSHOT applied loc=" + locationId
                        + " entries=" + entries.size() + " snapped=" + snapped);
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply ORBIT_SNAPSHOT", ex);
        } finally {
            replayGuard.end();
        }
    }

    /**
     * Resolve the local body for a host orbit entry. ID first: it matches named bodies AND early-gen
     * hex bodies whose ids agree across instances (e.g. the fringe jump-point '1b9'), even when their
     * orbit itself differs — which is exactly why a signature-only match misses it. Falls back to the
     * orbit signature for any body whose id doesn't resolve.
     */
    private SectorEntityToken resolveLocalBody(CoopOrbitSync.OrbitEntry entry,
                                               Map<String, SectorEntityToken> localById,
                                               Map<String, List<SectorEntityToken>> bySignature) {
        SectorEntityToken byId = localById.get(entry.entityId());
        if (byId != null && isSyncableOrbit(byId)) {
            removeFromSignaturePool(bySignature, byId);
            return byId;
        }
        List<SectorEntityToken> candidates = bySignature.get(
                CoopOrbitSync.signature(entry.focusId(), entry.radius(), entry.period()));
        return candidates != null && !candidates.isEmpty() ? candidates.remove(0) : null;
    }

    /**
     * Align a local body to the host entry. If the orbit geometry matches (the common, deterministic
     * case), snap only the angle (preserves any spin / point-down orbit). If radius or period differ
     * — a non-deterministically generated orbit like the fringe jump-point — reset the whole circular
     * orbit so distance and angle both match the host.
     */
    private void applyOrbitTo(SectorEntityToken local, CoopOrbitSync.OrbitEntry entry,
                              Map<String, SectorEntityToken> localById, SectorAPI sector) {
        boolean orbitGeometryDiffers = Math.abs(local.getCircularOrbitRadius() - entry.radius()) > 1f
                || Math.abs(local.getCircularOrbitPeriod() - entry.period()) > 0.5f;
        if (orbitGeometryDiffers) {
            // Rare branch (non-deterministic orbit like the fringe jump-point), so the sector-wide
            // lookup fallback is affordable here; the location map still catches the common case.
            SectorEntityToken focus = localById.get(entry.focusId());
            if (focus == null && entry.focusId() != null) {
                focus = sector.getEntityById(entry.focusId());
            }
            if (focus != null) {
                local.setCircularOrbit(focus, entry.angle(), entry.radius(), entry.period());
                return;
            }
        }
        local.setCircularOrbitAngle(entry.angle());
    }

    /**
     * Only navigationally meaningful orbiting bodies are angle-synced: named string-id entities
     * (planets/moons/stations/relays/gates), jump points, and planets. The asteroid swarm is excluded
     * — its hundreds of near-identical orbits collide on signature and would starve the jump-point
     * match (the symptom: every named body aligned but the fringe jump-point still drifting).
     */
    /**
     * The orbit-sync body set for a location: planets/moons, jump points, and stable-id custom
     * entities (stations, relays, buoys) — the landmarks players navigate by.
     *
     * <p>Enumerated by inclusion from the engine's typed lists, NOT via {@code getAllEntities()}.
     * The all-entities sweep was a measured defect (2026-08-17): with the host's fleet parked in an
     * asteroid belt, the engine's materialized asteroids and ring-band segments passed the old
     * per-entity filter and ballooned the 1 Hz snapshot from 13 entries to 358, none of which the
     * guest could match (per-instance ids, and cosmetic anyway). The typed lists exclude asteroid
     * and ring entities structurally, whatever classes or ids the engine gives them.
     */
    private List<SectorEntityToken> syncableOrbitBodies(LocationAPI location) {
        List<SectorEntityToken> bodies = new ArrayList<>();
        addSyncableOrbitBodies(bodies, location.getPlanets());
        addSyncableOrbitBodies(bodies, location.getJumpPoints());
        addSyncableOrbitBodies(bodies, location.getCustomEntities());
        return bodies;
    }

    private void addSyncableOrbitBodies(List<SectorEntityToken> out,
                                        List<? extends SectorEntityToken> candidates) {
        if (candidates == null) {
            return;
        }
        for (SectorEntityToken e : candidates) {
            if (e != null && isSyncableOrbit(e)) {
                out.add(e);
            }
        }
    }

    private boolean isSyncableOrbit(SectorEntityToken e) {
        if (e instanceof CampaignFleetAPI || e.getOrbit() == null || e.getCircularOrbitRadius() <= 0f) {
            return false;
        }
        return CoopOrbitSync.isStableId(e.getId()) || e instanceof JumpPointAPI || e instanceof PlanetAPI;
    }

    /**
     * Diagnostics only: logs the orbit-sync body count with a per-class breakdown whenever the count
     * changes, so a future stream balloon names its culprit class directly in the log.
     */
    private void maybeDumpOrbitBreakdown(List<SectorEntityToken> bodies) {
        if (!CoopDebug.diagnosticsEnabled() || bodies.size() == lastOrbitBodyCount) {
            return;
        }
        lastOrbitBodyCount = bodies.size();
        Map<String, Integer> byClass = new LinkedHashMap<>();
        for (SectorEntityToken e : bodies) {
            byClass.merge(e.getClass().getSimpleName(), 1, Integer::sum);
        }
        CoopLog.info(CoopCampaignReplicator.class,
                "Coop orbit-sync bodies=" + bodies.size() + " byClass=" + byClass);
    }

    private void removeFromSignaturePool(Map<String, List<SectorEntityToken>> bySignature,
                                         SectorEntityToken e) {
        String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
        List<SectorEntityToken> list = bySignature.get(
                CoopOrbitSync.signature(focusId, e.getCircularOrbitRadius(), e.getCircularOrbitPeriod()));
        if (list != null) {
            list.remove(e);
        }
    }

    /**
     * The consume watcher's per-entity step: the tracking key for an entity worth watching, or null.
     *
     * <p><b>Which entities are tracked</b> (Phase 12d widening).
     *
     * <p>Was an allowlist of {@code Tags.SALVAGEABLE} only, which silently missed everything nobody
     * had thought to tag: verified in-game on 2026-08-09, {@code nav_buoy_makeshift} is tagged
     * {@code [nav_buoy, neutrino_high, objective, makeshift]} and {@code cargo_pods} is tagged
     * {@code [has_interaction_dialog, neutrino, salvage_music]} — neither carries {@code salvageable},
     * so disassembling a makeshift structure or emptying a pod reported nothing at all.
     *
     * <p>Now: anything salvage-tagged, anything coop-replicated, or any custom entity. Custom
     * entities cover pods and the makeshift structures without naming either. Planets, stars, and
     * jump points are not custom entities and so stay out; fleets are excluded outright because
     * Phase 9 owns them.
     *
     * <p><b>Which key.</b> Coop-replicated entities key on their coop-assigned id because the engine
     * mints its own per client and the two never match; everything else keys on the engine id, which
     * deterministic worldgen makes identical across clients.
     */
    private String consumeKeyIfTracked(SectorEntityToken entity) {
        if (entity == null) {
            return null;
        }
        // The one memory read the whole per-entity pass gets: getMemoryWithoutUpdate() lazily
        // allocates a save-persisted Memory for entities that lack one, so asking twice (once to
        // decide, once to key) doubled that cost across every entity in the location.
        MemoryAPI memory = entity.getMemoryWithoutUpdate();
        boolean coopReplicated = memory != null && memory.contains(CoopWorldEntitySpawn.COOP_ENTITY_TAG);
        if (!shouldTrackForConsume(
                entity instanceof CampaignFleetAPI,
                entity.hasTag(Tags.SALVAGEABLE),
                coopReplicated,
                entity instanceof CustomCampaignEntityAPI)) {
            return null;
        }
        if (coopReplicated) {
            Object coopId = memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG);
            if (coopId != null && !String.valueOf(coopId).isBlank()) {
                return String.valueOf(coopId);
            }
        }
        return entity.getId();
    }

    /** Pure decision function (unit-tested) behind {@link #consumeKeyIfTracked}. */
    static boolean shouldTrackForConsume(boolean isFleet, boolean salvageTagged,
                                         boolean coopReplicated, boolean isCustomEntity) {
        if (isFleet) {
            return false; // Phase 9 owns fleet existence
        }
        return salvageTagged || coopReplicated || isCustomEntity;
    }

    /** Finds a replicated entity by coop id, falling back to the engine id for worldgen entities. */
    private SectorEntityToken findEntityForDelta(SectorAPI sector, String entityId) {
        SectorEntityToken byEngineId = sector.getEntityById(entityId);
        if (byEngineId != null) {
            return byEngineId;
        }
        for (LocationAPI location : sector.getAllLocations()) {
            if (location == null) {
                continue;
            }
            for (SectorEntityToken entity : location.getAllEntities()) {
                MemoryAPI memory = entity == null ? null : entity.getMemoryWithoutUpdate();
                if (memory != null
                        && entityId.equals(String.valueOf(memory.get(CoopWorldEntitySpawn.COOP_ENTITY_TAG)))) {
                    return entity;
                }
            }
        }
        return null;
    }

    /**
     * The local player left cargo pods behind (jettison, or cargo left in stable orbit). Replicate
     * them so the partner can actually pick them up — v1 has no direct trade UI, so pods are the
     * only way the two players can hand each other anything (Phase 12d).
     */
    @Override
    public void onPlayerLeftCargoPods(SectorEntityToken pods) {
        if (!isActive() || pods == null || replayGuard.isReplaying()) {
            return;
        }
        try {
            LocationAPI location = pods.getContainingLocation();
            if (location == null) {
                return;
            }
            String coopEntityId = session.localPlayerId() + ":" + pods.getId();
            pods.getMemoryWithoutUpdate().set(CoopWorldEntitySpawn.COOP_ENTITY_TAG, coopEntityId);

            CoopWorldEntitySpawn spawn = new CoopWorldEntitySpawn(
                    coopEntityId,
                    Entities.CARGO_PODS,
                    location.getId(),
                    pods.getLocation() == null ? 0f : pods.getLocation().x,
                    pods.getLocation() == null ? 0f : pods.getLocation().y,
                    pods.getVelocity() == null ? 0f : pods.getVelocity().x,
                    pods.getVelocity() == null ? 0f : pods.getVelocity().y,
                    contentsOf(pods));

            CoopWorldDelta delta = new CoopWorldDelta(coopEntityId, CoopWorldDelta.Kind.SPAWN, false,
                    spawn.encode(), session.localPlayerId());
            // Mark locally applied first so the host's echo rebroadcast is a no-op here.
            if (worldLedger.apply(delta)) {
                reportWorldDelta(delta);
                CoopLog.info(CoopCampaignReplicator.class, "Coop reported cargo pod spawn id="
                        + coopEntityId + " stacks=" + spawn.contents().size());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to report cargo pods", ex);
        }
    }

    /**
     * Everything a pod holds, keyed {@code KIND:id}. Commodities alone would silently drop the
     * weapons, fighters, and ships players most want to hand each other — and a vanishing ship is a
     * far worse failure than a missing crate of supplies.
     */
    private Map<String, Integer> contentsOf(SectorEntityToken pods) {
        Map<String, Integer> out = new LinkedHashMap<>();
        CargoAPI cargo = pods instanceof CustomCampaignEntityAPI custom ? custom.getCargo() : null;
        if (cargo == null) {
            return out;
        }
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            StackRef ref = classify(stack);
            if (ref == null) {
                continue;
            }
            CoopWorldEntitySpawn.ItemKind kind = spawnKindOf(ref.kind());
            if (kind == null) {
                continue;
            }
            int size = Math.round(stack.getSize());
            if (size > 0) {
                out.merge(CoopWorldEntitySpawn.key(kind, ref.id()), size, Integer::sum);
            }
        }
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships != null) {
            for (FleetMemberAPI member : ships.getMembersListCopy()) {
                String variantId = shipVariantId(member);
                if (variantId != null) {
                    out.merge(CoopWorldEntitySpawn.key(
                            CoopWorldEntitySpawn.ItemKind.SHIP, variantId), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /**
     * The pod-content kind a cargo-stack kind maps to, or null when the stack cannot be pod content.
     *
     * <p>Deliberately no {@code default -> COMMODITY}. That default is what mangled a jettisoned AI
     * core: a SPECIAL stack fell through it and was re-materialized on the partner's client as a
     * commodity of the same id, i.e. as nothing at all. Unmapped kinds are now skipped, loudly-shaped
     * (an exhaustive switch, so a new ItemKind is a compile error here rather than a silent
     * mis-materialization).
     */
    private static CoopWorldEntitySpawn.ItemKind spawnKindOf(CoopMarketSync.ItemKind kind) {
        return switch (kind) {
            case COMMODITY -> CoopWorldEntitySpawn.ItemKind.COMMODITY;
            case WEAPON -> CoopWorldEntitySpawn.ItemKind.WEAPON;
            case FIGHTER -> CoopWorldEntitySpawn.ItemKind.FIGHTER;
            case SHIP -> CoopWorldEntitySpawn.ItemKind.SHIP;
            case SPECIAL -> CoopWorldEntitySpawn.ItemKind.SPECIAL;
            // People are not cargo and can never be in a pod.
            case OFFICER, MERC, ADMIN -> null;
        };
    }

    /** Materializes a replicated world entity on this client. */
    private void applySpawnToEngine(CoopWorldDelta delta) {
        SectorAPI sector = Global.getSector();
        if (sector == null) {
            return;
        }
        CoopWorldEntitySpawn spawn = CoopWorldEntitySpawn.decode(delta.newStateJson());
        if (findEntityForDelta(sector, spawn.coopEntityId()) != null) {
            return; // already materialized (duplicate packet, or our own echo)
        }
        LocationAPI location = locationById(sector, spawn.locationId());
        if (location == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Cannot spawn coop entity "
                    + spawn.coopEntityId() + ": unknown location " + spawn.locationId());
            return;
        }
        SectorEntityToken entity = location.addCustomEntity(null, null, spawn.entityType(),
                Factions.NEUTRAL);
        entity.getLocation().set(spawn.x(), spawn.y());
        // Velocity rides the wire because Misc.addCargoPods draws it from Math.random().
        entity.getVelocity().set(spawn.velocityX(), spawn.velocityY());
        entity.setSensorProfile(1f);
        entity.setDiscoverable(null);
        entity.setDiscoveryXP(null);
        entity.getMemoryWithoutUpdate().set(CoopWorldEntitySpawn.COOP_ENTITY_TAG, spawn.coopEntityId());
        if (entity instanceof CustomCampaignEntityAPI custom && custom.getCargo() != null) {
            for (Map.Entry<String, Integer> entry : spawn.contents().entrySet()) {
                addSpawnContent(custom.getCargo(), entry.getKey(), entry.getValue());
            }
        }
        // Deliberately no CargoPodsResponse decay script on the mirror copy: decay stays owned by
        // the creating client, which reports the removal as a CONSUME and takes the pod out on both
        // sides. Running two independent decay timers would just race to the same outcome.
        CoopLog.info(CoopCampaignReplicator.class, "Coop materialized entity " + spawn.coopEntityId()
                + " type=" + spawn.entityType() + " in " + spawn.locationId());
    }

    /** Adds one {@code KIND:id} content entry back into a materialized pod's cargo. */
    private void addSpawnContent(CargoAPI cargo, String key, int quantity) {
        int split = key.indexOf(':');
        if (split <= 0 || split == key.length() - 1) {
            CoopLog.warn(CoopCampaignReplicator.class, "Malformed spawn content key: " + key);
            return;
        }
        String id = key.substring(split + 1);
        CoopWorldEntitySpawn.ItemKind kind;
        try {
            kind = CoopWorldEntitySpawn.ItemKind.valueOf(key.substring(0, split));
        } catch (IllegalArgumentException ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Unknown spawn content kind in key: " + key);
            return;
        }
        try {
            switch (kind) {
                case COMMODITY -> cargo.addCommodity(id, quantity);
                case WEAPON -> cargo.addWeapons(id, quantity);
                case FIGHTER -> cargo.addFighters(id, quantity);
                case SPECIAL -> addSpecial(cargo, id, quantity);
                // Pod ships still key by variant id, so they still arrive pristine (see
                // CoopWorldEntitySpawn.ItemKind.SHIP).
                case SHIP -> addMothballedShipsByVariant(cargo, id, quantity);
            }
        } catch (RuntimeException | LinkageError ex) {
            // A variant or spec this client cannot resolve: skip that stack rather than lose the pod.
            CoopLog.warn(CoopCampaignReplicator.class, "Could not restore pod content " + key, ex);
        }
    }

    private LocationAPI locationById(SectorAPI sector, String locationId) {
        if (locationId == null || locationId.isBlank()) {
            return null;
        }
        for (LocationAPI location : sector.getAllLocations()) {
            if (location != null && locationId.equals(location.getId())) {
                return location;
            }
        }
        LocationAPI hyperspace = sector.getHyperspace();
        return hyperspace != null && locationId.equals(hyperspace.getId()) ? hyperspace : null;
    }

    private void reportLocalSalvageConsume(String entityId) {
        CoopWorldDelta delta = new CoopWorldDelta(entityId, CoopWorldDelta.Kind.CONSUME, true, "",
                session.localPlayerId());
        // Mark applied locally so we never re-report it and the host's rebroadcast echo is a no-op.
        if (worldLedger.apply(delta)) {
            reportWorldDelta(delta);
            CoopLog.info(CoopCampaignReplicator.class, "Coop salvage CONSUME reported entity=" + entityId);
        }
    }

    // ---- World-affecting abilities ------------------------------------------------------------

    @Override
    public void onPlayerActivatedAbility(AbilityPlugin ability, Object param) {
        if (!isActive() || ability == null) {
            return;
        }
        // Entry guard, matching every other capture path: never re-capture while applying a host
        // packet. Latent today (no replay path fires abilities) but the odd one out without it.
        if (replayGuard.isReplaying()) {
            return;
        }
        String abilityId = ability.getId();
        if (!CoopAbilityArbiter.isWorldAffecting(abilityId)) {
            return; // purely-local ability; not arbitrated
        }
        // Guest reports its world-affecting ability up to the host; the host applies/broadcasts.
        if (isGuest() && !replayGuard.isReplaying()) {
            send(CoopMessages.abilityActivate(session.sessionId(), service.nextSeq(), now(),
                    abilityId, session.localPlayerId(), ""));
            CoopLog.info(CoopCampaignReplicator.class, "Coop ABILITY_ACTIVATE (world) abilityId=" + abilityId);
        }
    }

    private void hostHandleAbilityActivate(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        String abilityId = CoopMessages.requiredPayloadString(message, "abilityId");
        String playerId = CoopMessages.requiredPayloadString(message, "playerId");
        // The host applies the world-affecting effect against its authoritative NPC fleets/world by
        // running the vanilla ability plugin on the guest's mirror fleet (Phase 12c A1). NPC fleet
        // state changes propagate back to the guest via the Phase 9 NPC_FLEET_SET rebroadcast, and
        // the interdiction standing hit rides the existing REP_DELTA capture.
        CoopAbilityEffectApplier.Decision decision = CoopAbilityEffectApplier.apply(abilityId, playerId);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied ABILITY_ACTIVATE abilityId=" + abilityId
                + " playerId=" + playerId + " decision=" + decision);
    }

    // ---- Engine helpers (defensive) -----------------------------------------------------------

    // Captures the open-market stock shown on the Trade screen: commodities, weapons, fighters and
    // specials (cargo stacks), ships (one listing per mothballed hull, carrying its full
    // CoopShipDetail), and the market's hireable officer/merc/admin pool. Other submarkets (black
    // market, military) remain a documented follow-up -- see openMarketCargo's fence.
    private List<CoopMarketSync.StockItem> captureOpenMarketStock(MarketAPI market) {
        List<CoopMarketSync.StockItem> items = new ArrayList<>();
        CargoAPI cargo = openMarketCargo(market);
        if (cargo == null) {
            return items;
        }
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            StackRef ref = classify(stack);
            if (ref == null) {
                continue;
            }
            int qty = Math.max(0, Math.round(stack.getSize()));
            if (qty > 0) {
                items.add(new CoopMarketSync.StockItem(ref.kind(), ref.id(), qty, 0f));
            }
        }
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships != null) {
            // One listing per member, keyed by the member id rather than the variant id: two hulls of
            // the same variant are not interchangeable once one of them has three D-mods and 40% CR,
            // and a per-variant count could not tell the guest which is which.
            for (FleetMemberAPI member : ships.getMembersListCopy()) {
                CoopShipDetail detail = captureShipDetail(member);
                if (detail != null) {
                    items.add(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP,
                            detail.memberId(), 1, 0f, detail.encode()));
                }
            }
        }
        return items;
    }

    /**
     * Everything about one listed hull that its variant id does not carry: the D-mod hull swap, perma
     * mods (which is what D-mods are), s-mods, the refit, suppressed mods, weapons, wings, vents/caps
     * and base CR. See {@link CoopShipDetail} for why each of those is separately load-bearing.
     *
     * <p>Multi-module hulls are captured as their parent variant only — no module recursion (accepted
     * gap, documented in {@code docs/starsector-runtime-limitations.md}).
     */
    private CoopShipDetail captureShipDetail(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return null;
        }
        try {
            ShipVariantAPI variant = member.getVariant();
            String memberId = member.getId();
            if (memberId == null || memberId.isBlank()) {
                return null;
            }
            List<String> permaMods = new ArrayList<>(orEmpty(variant.getPermaMods()));
            List<String> sMods = new ArrayList<>(orEmpty(variant.getSMods()));
            List<String> refitMods = new ArrayList<>();
            for (String modId : orEmpty(variant.getNonBuiltInHullmods())) {
                if (!permaMods.contains(modId)) {
                    refitMods.add(modId);
                }
            }
            Map<String, String> weapons = new LinkedHashMap<>();
            for (String slotId : orEmptyList(variant.getNonBuiltInWeaponSlots())) {
                String weaponId = variant.getWeaponId(slotId);
                if (weaponId != null) {
                    weapons.put(slotId, weaponId);
                }
            }
            Map<String, String> wings = new LinkedHashMap<>();
            List<String> builtInWings = variant.getHullSpec() == null
                    ? List.of() : orEmptyList(variant.getHullSpec().getBuiltInWings());
            List<String> allWings = orEmptyList(variant.getWings());
            for (int i = 0; i < allWings.size(); i++) {
                String wingId = allWings.get(i);
                if (wingId == null || wingId.isBlank()) {
                    continue;
                }
                if (i < builtInWings.size() && wingId.equals(builtInWings.get(i))) {
                    continue; // built-in bay: the hull spec puts it back on its own
                }
                wings.put(Integer.toString(i), wingId);
            }
            float baseCR = member.getRepairTracker() == null ? 0f : member.getRepairTracker().getBaseCR();
            return new CoopShipDetail(memberId,
                    member.getShipName(),
                    variant.getHullVariantId(),
                    variant.getHullSpec() == null ? "" : variant.getHullSpec().getHullId(),
                    baseCR,
                    variant.getNumFluxVents(),
                    variant.getNumFluxCapacitors(),
                    permaMods,
                    sMods,
                    new ArrayList<>(orEmpty(variant.getSModdedBuiltIns())),
                    refitMods,
                    new ArrayList<>(orEmpty(variant.getSuppressedMods())),
                    weapons,
                    wings);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture ship detail for member "
                    + (member.getId() == null ? "?" : member.getId()), ex);
            return null;
        }
    }

    private static Collection<String> orEmpty(Collection<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> orEmptyList(List<String> values) {
        return values == null ? List.of() : values;
    }

    // ---- Hireable officers / mercenaries / administrators (Phase 12c gap 2d) --------------------
    //
    // The pool is rolled per client by the sector's OfficerManagerEvent off Misc.random and
    // Math.random(), so host and guest saw different captains standing at the same bar. The host's
    // pool rides the MARKET_SNAPSHOT alongside the stock (one StockItem per person) and the guest
    // strips its own and rebuilds the host's.
    //
    // There is no vanilla hire event, so a guest hire is detected by diffing the market's hireable set
    // on close against the set the last snapshot applied; a person that vanished was hired.

    /**
     * The people at a market that are actually for hire.
     *
     * <p>The engine's own {@code available} / {@code availableAdmins} lists are protected, so the pool
     * is enumerated the way vanilla's dialog does: comm-directory PERSON entries carrying the
     * {@code $ome_hireable} memory flag.
     */
    private List<PersonAPI> hireablePeople(MarketAPI market) {
        List<PersonAPI> out = new ArrayList<>();
        if (market == null) {
            return out;
        }
        try {
            CommDirectoryAPI directory = market.getCommDirectory();
            if (directory == null || directory.getEntriesCopy() == null) {
                return out;
            }
            for (CommDirectoryEntryAPI entry : directory.getEntriesCopy()) {
                if (entry == null || entry.getType() != CommDirectoryEntryAPI.EntryType.PERSON) {
                    continue;
                }
                if (!(entry.getEntryData() instanceof PersonAPI person)) {
                    continue;
                }
                MemoryAPI memory = person.getMemoryWithoutUpdate();
                if (memory != null && memory.is(OME_HIREABLE, true)) {
                    out.add(person);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to enumerate hireable people at market "
                    + market.getId(), ex);
        }
        return out;
    }

    private static final String OME_HIREABLE = "$ome_hireable";
    private static final String OME_IS_ADMIN = "$ome_isAdmin";
    private static final String OME_ADMIN_TIER = "$ome_adminTier";

    /** Host: one StockItem per hireable person at the market. */
    private List<CoopMarketSync.StockItem> captureHireablePool(MarketAPI market) {
        List<CoopMarketSync.StockItem> items = new ArrayList<>();
        OfficerManagerEvent manager = officerManager();
        if (manager == null) {
            // The core sector-gen script always installs one, so this means something removed it. Say
            // so: the snapshot that follows claims "no hireables anywhere" and the guest will strip its
            // own pool to match it.
            CoopLog.warn(CoopCampaignReplicator.class, "No OfficerManagerEvent on the host sector;"
                    + " every market snapshot will report an empty hireable pool");
            return items;
        }
        for (PersonAPI person : hireablePeople(market)) {
            CoopPersonDetail detail = capturePersonDetail(manager, person);
            if (detail != null) {
                items.add(new CoopMarketSync.StockItem(detail.stockKind(), detail.personId(),
                        1, 0f, detail.encode()));
            }
        }
        return items;
    }

    private CoopPersonDetail capturePersonDetail(OfficerManagerEvent manager, PersonAPI person) {
        try {
            String personId = person.getId();
            if (personId == null || personId.isBlank()) {
                return null;
            }
            // hiringBonus/salary come from the engine's AvailableOfficer, never from the
            // $ome_hiringBonus / $ome_salary memory keys: those are pre-formatted display strings
            // (Misc.getWithDGS) and parsing them back would be separator-dependent.
            OfficerManagerEvent.AvailableOfficer entry = manager.getOfficer(personId);
            CoopPersonDetail.Role role;
            if (entry != null) {
                role = Misc.isMercenary(person) ? CoopPersonDetail.Role.MERC : CoopPersonDetail.Role.OFFICER;
            } else {
                entry = manager.getAdmin(personId);
                if (entry == null) {
                    CoopLog.debug(CoopCampaignReplicator.class, "Hireable person " + personId
                            + " has no OfficerManagerEvent entry; not replicated");
                    return null;
                }
                role = CoopPersonDetail.Role.ADMIN;
            }
            FullName name = person.getName();
            MemoryAPI memory = person.getMemoryWithoutUpdate();
            int adminTier = 0;
            if (role == CoopPersonDetail.Role.ADMIN && memory != null && memory.contains(OME_ADMIN_TIER)) {
                adminTier = memory.getInt(OME_ADMIN_TIER);
            }
            Map<String, Float> skills = new LinkedHashMap<>();
            if (person.getStats() != null && person.getStats().getSkillsCopy() != null) {
                for (MutableCharacterStatsAPI.SkillLevelAPI skill : person.getStats().getSkillsCopy()) {
                    if (skill != null && skill.getSkill() != null && skill.getSkill().getId() != null) {
                        skills.put(skill.getSkill().getId(), skill.getLevel());
                    }
                }
            }
            return new CoopPersonDetail(personId,
                    name == null ? "" : name.getFirst(),
                    name == null ? "" : name.getLast(),
                    person.getGender() == null ? FullName.Gender.ANY.name() : person.getGender().name(),
                    person.getPortraitSprite(),
                    person.getPersonalityAPI() == null ? "" : person.getPersonalityAPI().getId(),
                    person.getRankId(),
                    person.getPostId(),
                    person.getFaction() == null ? "" : person.getFaction().getId(),
                    person.getStats() == null ? 0 : person.getStats().getLevel(),
                    person.getStats() == null ? 0L : person.getStats().getXP(),
                    role,
                    entry.hiringBonus,
                    entry.salary,
                    adminTier,
                    // The lifetime rides along or the guest's own OfficerManagerEvent deletes the
                    // rebuilt person within its 1-3 day prune tick (see DEFAULT_LIFETIME_DAYS).
                    entry.timeRemaining,
                    skills);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to capture hireable person detail", ex);
            return null;
        }
    }

    /**
     * Guest: replace the market's hireable pool with the host's.
     *
     * <p>Strip-then-add through {@code OfficerManagerEvent} rather than the comm directory directly:
     * {@code addAvailable} is what sets {@code $ome_eventRef} to <em>this client's own</em> manager
     * script, and the hiring dialog reads that reference to complete the hire. Hand-placing a person
     * into the comm directory produces a captain who can be talked to and never hired.
     */
    private void applyHireablePool(MarketAPI market, List<CoopMarketSync.StockItem> items) {
        if (market == null) {
            return;
        }
        OfficerManagerEvent manager = officerManager();
        if (manager == null) {
            CoopLog.warn(CoopCampaignReplicator.class,
                    "No OfficerManagerEvent on this sector; hireable pool not replicated");
            return;
        }
        Map<String, CoopMarketSync.ItemKind> applied = new LinkedHashMap<>();
        try {
            for (PersonAPI person : hireablePeople(market)) {
                OfficerManagerEvent.AvailableOfficer entry = manager.getOfficer(person.getId());
                if (entry == null) {
                    entry = manager.getAdmin(person.getId());
                }
                if (entry != null) {
                    manager.removeAvailable(entry);
                }
            }
            for (CoopMarketSync.StockItem item : items) {
                if (CoopPersonDetail.roleOf(item.kind()) == null || item.detail().isEmpty()) {
                    continue;
                }
                CoopPersonDetail detail = CoopPersonDetail.decode(item.detail());
                PersonAPI person = buildPerson(detail);
                if (person == null) {
                    continue;
                }
                OfficerManagerEvent.AvailableOfficer entry = new OfficerManagerEvent.AvailableOfficer(
                        person, market.getId(), detail.hiringBonus(), detail.salary());
                // Without this the field stays at its 0f default and the local manager's own prune
                // tick deletes the person 1-3 campaign days later — comm-directory entry, hireable
                // flag and all. See CoopPersonDetail.DEFAULT_LIFETIME_DAYS.
                entry.timeRemaining = detail.timeRemainingDays() > 0f
                        ? detail.timeRemainingDays()
                        : CoopPersonDetail.DEFAULT_LIFETIME_DAYS;
                if (detail.role() == CoopPersonDetail.Role.ADMIN) {
                    manager.addAvailableAdmin(entry);
                } else {
                    manager.addAvailable(entry);
                }
                applied.put(detail.personId(), item.kind());
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply hireable pool for market "
                    + market.getId(), ex);
        }
        appliedHireables.put(market.getId(), applied);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied hireable pool market=" + market.getId()
                + " people=" + applied.size());
    }

    private PersonAPI buildPerson(CoopPersonDetail detail) {
        try {
            PersonAPI person = Global.getFactory().createPerson();
            person.setId(detail.personId());
            person.setName(new FullName(detail.first(), detail.last(), genderOf(detail.gender())));
            if (!detail.factionId().isEmpty()) {
                person.setFaction(detail.factionId());
            }
            if (!detail.portraitSprite().isEmpty()) {
                person.setPortraitSprite(detail.portraitSprite());
            }
            if (!detail.rankId().isEmpty()) {
                person.setRankId(detail.rankId());
            }
            if (!detail.postId().isEmpty()) {
                person.setPostId(detail.postId());
            }
            if (!detail.personalityId().isEmpty()) {
                person.setPersonality(detail.personalityId());
            }
            MutableCharacterStatsAPI stats = person.getStats();
            if (stats != null) {
                // Vanilla's own build order (OfficerManagerEvent.createAdmin): batch the skill writes
                // behind skipRefresh, then refresh once. Refreshing per skill is both slow and,
                // mid-build, wrong.
                stats.setSkipRefresh(true);
                stats.setLevel(detail.level());
                stats.setXP(detail.xp());
                for (Map.Entry<String, Float> skill : detail.skills().entrySet()) {
                    stats.setSkillLevel(skill.getKey(), skill.getValue());
                }
                stats.setSkipRefresh(false);
                stats.refreshCharacterStatsEffects();
            }
            if (detail.role() == CoopPersonDetail.Role.MERC) {
                Misc.setMercenary(person, true);
            }
            if (detail.role() == CoopPersonDetail.Role.ADMIN && person.getMemoryWithoutUpdate() != null) {
                person.getMemoryWithoutUpdate().set(OME_IS_ADMIN, true);
                person.getMemoryWithoutUpdate().set(OME_ADMIN_TIER, detail.adminTier());
            }
            return person;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to rebuild hireable person "
                    + detail.personId(), ex);
            return null;
        }
    }

    private static FullName.Gender genderOf(String name) {
        try {
            return FullName.Gender.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return FullName.Gender.ANY;
        }
    }

    /**
     * Guest: a person that was in the last applied pool and is no longer hireable was hired locally.
     * There is no vanilla hire event, so this close-time diff is the claim.
     *
     * <p>No credit deduction rides with it: credits are per-player and the acting client's own engine
     * already charged the hiring bonus. All the host needs is the availability removal.
     */
    private void reportHiresOnClose(MarketAPI market) {
        Map<String, CoopMarketSync.ItemKind> applied = appliedHireables.get(market.getId());
        if (applied == null || applied.isEmpty()) {
            return;
        }
        Set<String> stillHireable = new HashSet<>();
        for (PersonAPI person : hireablePeople(market)) {
            stillHireable.add(person.getId());
        }
        CoopMarketSync.HireDiff diff = CoopMarketSync.diffHires(applied, stillHireable);
        for (Map.Entry<String, CoopMarketSync.ItemKind> entry : diff.hired().entrySet()) {
            sendMarketTxn(market.getId(), entry.getValue(), entry.getKey(), 1, "");
            CoopLog.info(CoopCampaignReplicator.class, "Coop hire claim " + entry.getValue()
                    + ":" + entry.getKey() + " market=" + market.getId());
        }
        appliedHireables.put(market.getId(), diff.remaining());
    }

    /** Host: a guest hired someone; take them out of the canonical pool. */
    private boolean applyHireToEngine(String marketId, String personId) {
        OfficerManagerEvent manager = officerManager();
        if (manager == null) {
            return false;
        }
        replayGuard.begin();
        try {
            OfficerManagerEvent.AvailableOfficer entry = manager.getOfficer(personId);
            if (entry == null) {
                entry = manager.getAdmin(personId);
            }
            if (entry == null) {
                CoopLog.warn(CoopCampaignReplicator.class, "Coop hire claim for unknown person "
                        + personId + " at market=" + marketId);
                return false;
            }
            manager.removeAvailable(entry);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to apply hire claim " + personId, ex);
            return false;
        } finally {
            replayGuard.end();
        }
    }

    /**
     * The sector's officer manager. It is placed by the core sector-gen script and lives in the
     * every-frame script list; there is no registry accessor for it.
     */
    private OfficerManagerEvent officerManager() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getScripts() == null) {
                return null;
            }
            for (EveryFrameScript script : sector.getScripts()) {
                if (script instanceof OfficerManagerEvent manager) {
                    return manager;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to locate OfficerManagerEvent", ex);
        }
        return null;
    }

    /** Identifying (kind, id) for a fungible cargo stack; null for kinds we don't sync. */
    private record StackRef(CoopMarketSync.ItemKind kind, String id) {
    }

    private StackRef classify(CargoStackAPI stack) {
        if (stack == null) {
            return null;
        }
        // Specials are checked first: a special stack is not a commodity stack, but putting the check
        // last invites the same "unknown -> commodity" fallthrough that mangled jettisoned AI cores.
        if (stack.isSpecialStack() && stack.getSpecialDataIfSpecial() != null) {
            SpecialItemData data = stack.getSpecialDataIfSpecial();
            if (data.getId() == null || data.getId().isBlank()) {
                return null;
            }
            return new StackRef(CoopMarketSync.ItemKind.SPECIAL,
                    CoopMarketSync.specialItemId(data.getId(), data.getData()));
        }
        if (stack.isCommodityStack() && stack.getCommodityId() != null) {
            return new StackRef(CoopMarketSync.ItemKind.COMMODITY, stack.getCommodityId());
        }
        if (stack.isWeaponStack() && stack.getWeaponSpecIfWeapon() != null) {
            return new StackRef(CoopMarketSync.ItemKind.WEAPON, stack.getWeaponSpecIfWeapon().getWeaponId());
        }
        if (stack.isFighterWingStack() && stack.getFighterWingSpecIfWing() != null) {
            return new StackRef(CoopMarketSync.ItemKind.FIGHTER, stack.getFighterWingSpecIfWing().getId());
        }
        return null;
    }

    private String shipVariantId(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return null;
        }
        return member.getVariant().getHullVariantId();
    }

    /**
     * Host: run the same stock generation a physical dock runs, before the market is snapshotted.
     *
     * <p>Vanilla only stocks a submarket when a player is about to interact with it: the core trade UI
     * calls {@link SubmarketPlugin#updateCargoPrePlayerInteraction()} on the way in, and that call is
     * what actually fills the open market with commodities, weapons, fighters and hulls (see
     * {@code OpenMarketPlugin} in api_src; vanilla's own {@code PK_CMD} stocks a market off-screen by
     * calling exactly this on {@code SUBMARKET_OPEN}). For a market the host has never docked at that
     * call has never run, so {@code getCargoNullOk()} is null and {@link #captureOpenMarketStock}
     * would publish an empty market as canonical — which is what the guest then rendered.
     *
     * <p>Re-roll frequency stays vanilla-equivalent because the plugin self-limits and we add no
     * guard of our own: commodity restock is proportional to {@code sinceLastCargoUpdate} (zeroed on
     * every call, and vanilla explicitly refuses the sub-one-unit add so repeated re-checking cannot
     * accelerate it), and the ship/weapon re-roll is gated by {@code okToUpdateShipsAndWeapons()},
     * i.e. once per 30 campaign days. A guest opening a market N times costs what a player docking N
     * times costs, no more.
     *
     * <p>Open submarket only: that is the only submarket {@link #captureOpenMarketStock} captures and
     * the only one {@link #applySnapshotToEngine} replaces. Stocking the military/black submarkets
     * here would spend their re-roll windows on contents nobody reads (Phase 12c follow-up).
     */
    private void ensureOpenMarketStocked(MarketAPI market) {
        if (market == null || !market.hasSubmarket(Submarkets.SUBMARKET_OPEN)) {
            return;
        }
        SubmarketAPI open = market.getSubmarket(Submarkets.SUBMARKET_OPEN);
        SubmarketPlugin plugin = open == null ? null : open.getPlugin();
        if (plugin == null) {
            return;
        }
        boolean neverStocked = open.getCargoNullOk() == null;
        replayGuard.begin();
        try {
            plugin.updateCargoPrePlayerInteraction();
            CoopLog.info(CoopCampaignReplicator.class, "Coop pre-snapshot stock update market="
                    + market.getId() + " submarkets=[" + Submarkets.SUBMARKET_OPEN + "]"
                    + " neverStockedBefore=" + neverStocked
                    + " stacks=" + stackCount(open.getCargoNullOk()));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Failed to update open-market stock before"
                    + " snapshot market=" + market.getId(), ex);
        } finally {
            replayGuard.end();
        }
    }

    private int stackCount(CargoAPI cargo) {
        List<CargoStackAPI> stacks = cargo == null ? null : cargo.getStacksCopy();
        return stacks == null ? 0 : stacks.size();
    }

    private CargoAPI openMarketCargo(String marketId) {
        return openMarketCargo(findMarket(marketId));
    }

    /**
     * The one submarket the market-snapshot path is ever allowed to read or write:
     * {@link Submarkets#SUBMARKET_OPEN}.
     *
     * <p><b>Storage regression fence (Phase 18).</b> Every market snapshot in this class — capture
     * ({@link #captureOpenMarketStock}), pre-stock ({@link #ensureOpenMarketStocked}), apply
     * ({@code applySnapshotToEngine}) and per-item delta ({@code applyItemDeltaToEngine}) — goes
     * through this accessor, so these submarkets are never touched:
     *
     * <ul>
     *   <li>{@code storage} ({@link Submarkets#SUBMARKET_STORAGE}) — <b>this is the dangerous
     *       one.</b> It holds the player's own ships and cargo, and a snapshot apply is a full
     *       <em>replacement</em>, not a merge: pointing it at storage would delete whatever the
     *       player had parked there and hand back the host's open-market roll instead. Anything
     *       that widens this accessor must exclude storage explicitly.</li>
     *   <li>{@code black_market} ({@link Submarkets#SUBMARKET_BLACK}) — stock is tied to the
     *       market's own illegal-trade state and to per-player smuggling suspicion.</li>
     *   <li>{@code open_market}'s military sibling {@code generic_military}
     *       ({@link Submarkets#GENERIC_MILITARY}) — access is gated on per-player commission and
     *       standing, so it is not a shared, host-canonical shop in the first place.</li>
     *   <li>{@code local_resources} ({@link Submarkets#LOCAL_RESOURCES}) — a derived view of the
     *       colony's production, not a stocked shop.</li>
     * </ul>
     *
     * <p>Widening the snapshot to any of them is Phase 12c work and needs its own model (per-player
     * visibility, not one canonical list). {@code CoopCampaignReplicatorStorageFenceTest} asserts a
     * snapshot apply leaves storage alone.
     */
    private CargoAPI openMarketCargo(MarketAPI market) {
        if (market == null || !market.hasSubmarket(Submarkets.SUBMARKET_OPEN)) {
            return null;
        }
        SubmarketAPI open = market.getSubmarket(Submarkets.SUBMARKET_OPEN);
        return open == null ? null : open.getCargoNullOk();
    }

    private MarketAPI findMarket(String marketId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getEconomy() == null) {
            return null;
        }
        return sector.getEconomy().getMarket(marketId);
    }

    private float playerRelationshipTo(String factionId) {
        FactionAPI player = playerFaction();
        return player == null ? CoopRepDelta.BASELINE : player.getRelationship(factionId);
    }

    private static float clampRelationship(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private FactionAPI playerFaction() {
        SectorAPI sector = Global.getSector();
        return sector == null ? null : sector.getPlayerFaction();
    }

    private PersonAPI findPerson(String personId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getImportantPeople() == null) {
            return null;
        }
        return sector.getImportantPeople().getPerson(personId);
    }

    private void send(CoopMessages.Message message) {
        service.send(message);
    }

    private long now() {
        return clock.getAsLong();
    }

    private boolean isHost() {
        return service.role() == CoopConnectionRole.HOST;
    }

    private boolean isGuest() {
        return service.role() == CoopConnectionRole.GUEST;
    }

    private boolean isActive() {
        return session.handshakeValidated() && session.seedLong() != null && session.sessionId() != null;
    }

    // ---- Accessors (wiring + tests) -----------------------------------------------------------

    public ReplayGuard replayGuard() {
        return replayGuard;
    }

    public float repRelationship(CoopRepDelta.TargetType type, String targetId) {
        return CoopRepDelta.relationship(repTable, type, targetId);
    }

    public CoopFactionRelations factionRelations() {
        return factionRelations;
    }

    public CoopMissionBoardSync missionBoard() {
        return missionBoard;
    }

    public CoopMarketSync marketSync() {
        return marketSync;
    }

    public CoopWorldDelta.Ledger worldLedger() {
        return worldLedger;
    }

    public CoopRaidOutcomeSync.Ledger raidLedger() {
        return raidLedger;
    }

    public CoopSkeletonMutationWatcher skeletonWatcher() {
        return skeletonWatcher;
    }

    // ---- Phase 30 agent-bridge facades (dev tooling) -------------------------------------------
    //
    // The dormant agent bridge (coop.debug.CoopAgentBridge, -Dcoop.debug.bridge=<port>) is the
    // *second* caller of the capture and apply routines below. They stay private because the
    // replication path is their only production caller; these narrow public wrappers exist so the
    // bridge does not grow a parallel set of state readers that could drift from the wire's.
    // Nothing in the replication path calls them.

    /**
     * Bridge-only: the market stock a dock would show.
     *
     * <p>{@code stockFirst} is the host/guest split the bridge's {@code market} verb needs. On the
     * host it is {@code true}, so this runs the same {@link #ensureOpenMarketStocked} a real dock (and
     * {@link #broadcastMarketSnapshot}) runs before capturing — a market the host has never docked at
     * has no stock at all, and dumping it un-stocked would report an empty shop as canonical. That
     * generation is intended, not a bug: it is exactly what makes a host dump comparable to a guest
     * dump of the same market. On the guest it is {@code false} — the guest is not allowed to roll
     * stock, so the bridge reports its raw current cargo and lets
     * {@link #openMarketStockedForBridge} say whether there is any.
     */
    public List<CoopMarketSync.StockItem> captureMarketStockForBridge(MarketAPI market, boolean stockFirst) {
        if (market == null) {
            return new ArrayList<>();
        }
        if (stockFirst) {
            ensureOpenMarketStocked(market);
        }
        List<CoopMarketSync.StockItem> items = captureOpenMarketStock(market);
        items.addAll(captureHireablePool(market));
        return items;
    }

    /**
     * Bridge-only: whether this client's open submarket has ever been stocked. False means "never
     * docked here", which the bridge reports as {@code "stocked":false} rather than as an empty shop.
     */
    public boolean openMarketStockedForBridge(MarketAPI market) {
        if (market == null || !market.hasSubmarket(Submarkets.SUBMARKET_OPEN)) {
            return false;
        }
        SubmarketAPI open = market.getSubmarket(Submarkets.SUBMARKET_OPEN);
        return open != null && open.getCargoNullOk() != null;
    }

    /** Bridge-only: second caller of {@link #collectSurveyState}. */
    public void collectSurveyStateForBridge(LocationAPI location, Map<String, String> surveyOut,
                                            Map<String, String> ruinsOut) {
        if (location == null) {
            return;
        }
        collectSurveyState(location, surveyOut, ruinsOut);
    }

    /**
     * Bridge-only: second caller of {@link #applySurveyLevelToEngine}, so the {@code surveyset} verb
     * writes through the same max-wins/{@code setFullySurveyed} path a replicated SURVEY delta does.
     */
    public void applySurveyLevelForBridge(String planetId, String surveyLevelName) {
        applySurveyLevelToEngine(new CoopWorldDelta(planetId, CoopWorldDelta.Kind.SURVEY, false,
                surveyLevelName, session.localPlayerId()));
    }

    /**
     * Bridge-only: second caller of {@link #applyObjectiveOwnershipToEngine}, so the
     * {@code objective} verb flips ownership through the same engine writes the dialog's capture
     * ends up producing (faction set + {@code OBJECTIVE_NON_FUNCTIONAL} cleared).
     */
    public void applyObjectiveOwnershipForBridge(String entityId, String factionId) {
        applyObjectiveOwnershipToEngine(new CoopWorldDelta(entityId,
                CoopWorldDelta.Kind.OBJECTIVE_OWNERSHIP, false, factionId, session.localPlayerId()));
    }
}
