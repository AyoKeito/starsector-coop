package coop.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import coop.net.CoopConnectionRole;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopDebug;
import coop.util.CoopLog;

import java.util.ArrayList;
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
public final class CoopCampaignReplicator implements CoopCampaignEventListener.Sink {

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

    // Salvage watcher: salvageable entity ids present at the local player's location last tick. A
    // tracked id that vanishes means the local player salvaged/disassembled it -> WORLD_DELTA(CONSUME).
    private final Set<String> trackedSalvageables = new HashSet<>();
    private String watchedLocationId;

    // Orbit-angle sync: host re-broadcasts orbiting-body angles ~1Hz so the guest can snap out the
    // small clock-drift offset that makes shared systems' planets/jumps appear at different angles.
    static final long ORBIT_SYNC_INTERVAL_MILLIS = 1000L;
    private long lastOrbitSyncMillis;

    // Player faction standings: host re-broadcasts the full set on a slow cadence and the guest
    // force-matches it. Event-driven REP_DELTA covers host-side changes immediately; this snapshot is
    // the safety net that converges drift the host can't see (e.g. the guest's own transponder-off
    // penalties, applied independently in the guest's simulation).
    static final long PLAYER_REP_SYNC_INTERVAL_MILLIS = 30000L;
    private long lastPlayerRepSyncMillis;

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
        }
        listener = null;
        factionRelationsSeeded = false;
        lastPlayerRepSyncMillis = 0L;
        repTable.clear();
        factionRelations.clear();
        missionBoard.clear();
        marketSync.clear();
        worldLedger.clear();
        // Salvage-watcher baseline too: leaving it populated meant that on reconnect in the same
        // system, every entity consumed last session looked "newly missing" and was re-reported as a
        // fresh WORLD_DELTA(CONSUME). Clearing forces a silent re-seed on the next tick.
        trackedSalvageables.clear();
        watchedLocationId = null;
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
    public void onPlayerOpenedMarket(MarketAPI market) {
        if (!isActive() || replayGuard.isReplaying() || market == null) {
            return;
        }
        // Host-authoritative market contents are synced once, at open (never per-frame, so the trade
        // UI is never fought mid-transaction). The host's engine market IS the canonical source: the
        // guest asks the host for a snapshot and applies it to its own market once; thereafter both
        // sides apply the same per-transaction delta, so they stay consistent with no live re-sync.
        if (isGuest()) {
            send(CoopMessages.marketOpen(session.sessionId(), service.nextSeq(), now(),
                    market.getId(), session.localPlayerId()));
            CoopLog.info(CoopCampaignReplicator.class, "Coop MARKET_OPEN requested market=" + market.getId());
        }
        // When the host opens, its engine market is already canonical; the guest (if it later opens
        // the same market) pulls it via MARKET_OPEN. Phase 18 mutexes simultaneous same-market use.
    }

    /** Host: a player opened a market; capture the canonical open-market stock and send it. */
    private void handleMarketOpen(CoopMessages.Message message) {
        if (!isHost() || !isActive()) {
            return;
        }
        String marketId = CoopMessages.requiredPayloadString(message, "marketId");
        MarketAPI market = findMarket(marketId);
        if (market == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_OPEN for unknown market=" + marketId);
            return;
        }
        broadcastMarketSnapshot(market);
    }

    /** Host: capture the canonical open-market stock (all item kinds) and broadcast it. */
    private void broadcastMarketSnapshot(MarketAPI market) {
        List<CoopMarketSync.StockItem> items = captureOpenMarketStock(market);
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

    /** Broadcast a captured mission/bar pool for a market (host). */
    public void broadcastMissionPool(String marketId, List<CoopMissionBoardSync.Entry> entries) {
        if (!isHost() || !isActive()) {
            return;
        }
        missionBoard.applySnapshot(entries);
        send(CoopMessages.missionPoolSnapshot(session.sessionId(), service.nextSeq(), now(),
                marketId, CoopMissionBoardSync.encodePool(entries)));
    }

    private void applyMissionPool(CoopMessages.Message message) {
        if (!isGuest()) {
            return;
        }
        List<CoopMissionBoardSync.Entry> entries = CoopMissionBoardSync.decodePool(
                CoopMessages.requiredPayloadString(message, "pool"));
        missionBoard.applySnapshot(entries);
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied MISSION_POOL_SNAPSHOT entries=" + entries.size());
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

    /** Report ship deltas (each sale is one hull, keyed by variant id). */
    private void reportShipDeltas(String marketId, List<PlayerMarketTransaction.ShipSaleInfo> ships, int sign) {
        if (ships == null) {
            return;
        }
        Map<String, Integer> byVariant = new LinkedHashMap<>();
        for (PlayerMarketTransaction.ShipSaleInfo info : ships) {
            String variantId = shipVariantId(info == null ? null : info.getMember());
            if (variantId != null) {
                byVariant.merge(variantId, sign, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : byVariant.entrySet()) {
            if (e.getValue() != 0) {
                sendMarketTxn(marketId, CoopMarketSync.ItemKind.SHIP, e.getKey(), e.getValue());
            }
        }
    }

    private void sendMarketTxn(String marketId, CoopMarketSync.ItemKind kind, String itemId, int qty) {
        send(CoopMessages.marketTxn(session.sessionId(), service.nextSeq(), now(),
                marketId, kind.name(), itemId, qty, 0f, session.localPlayerId()));
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
        // Keep the in-memory model in step (used by tests / future assertions).
        marketSync.applyTransaction(new CoopMarketSync.Transaction(marketId, kind, itemId, qty,
                CoopMessages.requiredPayloadFloat(message, "unitPrice")));
        // Apply the delta to the host's canonical engine market so its stock actually changes.
        // Only claim "applied" when the engine mutation ran; the previous unconditional log asserted
        // success over a silent no-op, which is how the propagation bug stayed invisible.
        if (applyItemDeltaToEngine(marketId, kind, itemId, qty)) {
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
                        // Weapons/fighters/ships were stripped to zero, so just add the host's count.
                        case WEAPON -> cargo.addWeapons(item.itemId(), item.quantity());
                        case FIGHTER -> cargo.addFighters(item.itemId(), item.quantity());
                        case SHIP -> addMothballedShips(cargo, item.itemId(), item.quantity());
                        default -> { /* officers/mercs/special not synced yet */ }
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
     * been materialized (the normal state for a market this client has never docked at). Forcing
     * materialization with {@code getCargo()} is <em>not</em> the fix: that accessor creates an empty
     * cargo, and {@link #captureOpenMarketStock} reads the same one, so the next {@code MARKET_OPEN}
     * would snapshot an empty market to the guest as canonical. Making a guest purchase durable is a
     * model problem, not an accessor problem: open-market commodity stock is a stockpile the engine
     * refills toward {@code getStockpileLimit} on every dock, so deltas do not survive regardless.
     * Tracked as Phase 12c gap 2e.
     */
    private boolean applyItemDeltaToEngine(String marketId, CoopMarketSync.ItemKind kind, String itemId, int qty) {
        CargoAPI cargo = openMarketCargo(marketId);
        if (cargo == null) {
            CoopLog.warn(CoopCampaignReplicator.class, "Coop MARKET_TXN not applied to engine: no"
                    + " materialized open-market cargo for market=" + marketId + " " + kind + ":" + itemId
                    + " qty=" + qty + " (this client has not docked there)");
            return false;
        }
        replayGuard.begin();
        try {
            addItemToEngine(cargo, kind, itemId, qty);
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
    private void addItemToEngine(CargoAPI cargo, CoopMarketSync.ItemKind kind, String itemId, int qty) {
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
            case SHIP -> {
                if (qty > 0) {
                    removeMothballedShips(cargo, itemId, qty);
                } else {
                    addMothballedShips(cargo, itemId, -qty);
                }
            }
            default -> { /* officers/mercs/special not synced yet */ }
        }
    }

    private void addMothballedShips(CargoAPI cargo, String variantId, int count) {
        FleetDataAPI ships = cargo.getMothballedShips();
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

    private void removeMothballedShips(CargoAPI cargo, String variantId, int count) {
        FleetDataAPI ships = cargo.getMothballedShips();
        if (ships == null || variantId == null) {
            return;
        }
        int removed = 0;
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            if (removed >= count) {
                break;
            }
            if (variantId.equals(shipVariantId(member))) {
                ships.removeFleetMember(member);
                removed++;
            }
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
        if (!delta.consumed()) {
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getHyperspace() == null) {
            return;
        }
        // Remove the consumed entity wherever it lives so it cannot be re-looted on this client.
        var entity = sector.getEntityById(delta.entityId());
        if (entity != null && entity.getContainingLocation() != null) {
            entity.getContainingLocation().removeEntity(entity);
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
            Set<String> current = new HashSet<>();
            for (SectorEntityToken entity : location.getEntitiesWithTag(Tags.SALVAGEABLE)) {
                if (entity.getId() != null) {
                    current.add(entity.getId());
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
            // A tracked entity that is no longer present was consumed by the local player this tick.
            for (String id : new ArrayList<>(trackedSalvageables)) {
                if (!current.contains(id)) {
                    trackedSalvageables.remove(id);
                    reportLocalSalvageConsume(id);
                }
            }
            trackedSalvageables.addAll(current);
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopCampaignReplicator.class, "Salvage watcher failed", ex);
        }
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
            List<CoopOrbitSync.OrbitEntry> entries = new ArrayList<>();
            for (SectorEntityToken e : location.getAllEntities()) {
                if (!isSyncableOrbit(e)) {
                    continue;
                }
                String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
                entries.add(new CoopOrbitSync.OrbitEntry(e.getId(), focusId, e.getCircularOrbitRadius(),
                        e.getCircularOrbitPeriod(), e.getCircularOrbitAngle()));
            }
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
            for (SectorEntityToken e : location.getAllEntities()) {
                if (!isSyncableOrbit(e)) {
                    continue;
                }
                String focusId = e.getOrbitFocus() == null ? null : e.getOrbitFocus().getId();
                bySignature.computeIfAbsent(
                        CoopOrbitSync.signature(focusId, e.getCircularOrbitRadius(), e.getCircularOrbitPeriod()),
                        k -> new ArrayList<>()).add(e);
            }
            int snapped = 0;
            for (CoopOrbitSync.OrbitEntry entry : entries) {
                SectorEntityToken local = resolveLocalBody(entry, sector, bySignature);
                if (local != null) {
                    applyOrbitTo(local, entry, sector);
                    snapped++;
                }
            }
            CoopLog.info(CoopCampaignReplicator.class, "Coop ORBIT_SNAPSHOT applied loc=" + locationId
                    + " entries=" + entries.size() + " snapped=" + snapped);
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
    private SectorEntityToken resolveLocalBody(CoopOrbitSync.OrbitEntry entry, SectorAPI sector,
                                               Map<String, List<SectorEntityToken>> bySignature) {
        SectorEntityToken byId = sector.getEntityById(entry.entityId());
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
    private void applyOrbitTo(SectorEntityToken local, CoopOrbitSync.OrbitEntry entry, SectorAPI sector) {
        boolean orbitGeometryDiffers = Math.abs(local.getCircularOrbitRadius() - entry.radius()) > 1f
                || Math.abs(local.getCircularOrbitPeriod() - entry.period()) > 0.5f;
        if (orbitGeometryDiffers) {
            SectorEntityToken focus = sector.getEntityById(entry.focusId());
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
    private boolean isSyncableOrbit(SectorEntityToken e) {
        if (e instanceof CampaignFleetAPI || e.getOrbit() == null || e.getCircularOrbitRadius() <= 0f) {
            return false;
        }
        return CoopOrbitSync.isStableId(e.getId()) || e instanceof JumpPointAPI || e instanceof PlanetAPI;
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
        // The host applies the world-affecting effect against its authoritative NPC fleets/world.
        // The concrete per-ability effect is applied in-game; v1 routes + logs it explicitly. NPC
        // fleet state changes propagate back to the guest via Phase 9 NPC_FLEET_SET rebroadcast.
        CoopLog.info(CoopCampaignReplicator.class, "Coop applied ABILITY_ACTIVATE abilityId=" + abilityId
                + " playerId=" + playerId);
    }

    // ---- Engine helpers (defensive) -----------------------------------------------------------

    // Captures the open-market stock shown on the Trade screen: commodities, weapons and fighters
    // (cargo stacks) plus ships (mothballed hulls, keyed by variant id). Officers/mercs and other
    // submarkets (black market, military) remain a documented follow-up.
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
            Map<String, Integer> byVariant = new LinkedHashMap<>();
            for (FleetMemberAPI member : ships.getMembersListCopy()) {
                String variantId = shipVariantId(member);
                if (variantId != null) {
                    byVariant.merge(variantId, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : byVariant.entrySet()) {
                items.add(new CoopMarketSync.StockItem(CoopMarketSync.ItemKind.SHIP,
                        e.getKey(), e.getValue(), 0f));
            }
        }
        return items;
    }

    /** Identifying (kind, id) for a fungible cargo stack; null for kinds we don't sync. */
    private record StackRef(CoopMarketSync.ItemKind kind, String id) {
    }

    private StackRef classify(CargoStackAPI stack) {
        if (stack == null) {
            return null;
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

    private CargoAPI openMarketCargo(String marketId) {
        return openMarketCargo(findMarket(marketId));
    }

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
}
