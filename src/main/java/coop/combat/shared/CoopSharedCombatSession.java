package coop.combat.shared;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import coop.combat.join.CoopCombatJoinMode;
import coop.combat.join.CoopSharedBattleHooks;
import coop.net.CoopMessages;
import coop.net.CoopNetService;
import coop.session.CoopSessionState;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Dual-engine shared combat orchestrator (Suite frame-sync route).
 *
 * <p>Phase A: partner enters a real CombatEngine via {@link CoopSharedCombatEntry}.
 * Phase B: both sides exchange {@link CoopSharedCombatFrameCodec} bodies on {@code SHARED_COMBAT}.
 * Phase C: join Accept/Proximity drives entry; remote claim uses {@link CoopSharedCombatClaimCodec}.
 * Phase D: {@link #onBattleEnded} clears state for reconciler handoff.
 */
public final class CoopSharedCombatSession implements CoopSharedBattleHooks {

    public enum Phase {
        IDLE,
        OFFERED,
        ENTERING,
        ACTIVE,
        CLAIM_PENDING,
        ENDED
    }

    private final CoopNetService service;
    private final CoopSessionState session;
    private final LongSupplier clock;

    private Phase phase = Phase.IDLE;
    private String battleId = "";
    private CoopCombatJoinMode joinMode = CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE;
    private boolean localIsEngager;
    private boolean entryIssued;
    private long nextFrameAtMillis;
    private long frameSeq;
    private final List<String> enemyHullIds = new ArrayList<>();
    private final Map<String, ShipAPI> remoteShips = new LinkedHashMap<>();
    private String lastClaimTargetId = "";
    private boolean claimGranted;
    private String endOutcome = "";
    private long nextClaimProbeAtMillis;

    /**
     * Test seam: replace {@link CoopSharedCombatEntry#enterAgainstShadowEnemy} without a live engine.
     * Null = production path.
     */
    private Function<List<String>, Boolean> entryHook;

    /** Frame send cadence (~10 Hz) — denser than BATTLE_STATUS, lighter than BO UDP. */
    public static final long FRAME_INTERVAL_MILLIS = 100L;
    /** Claim probe retry cadence while ACK is outstanding. */
    public static final long CLAIM_PROBE_INTERVAL_MILLIS = 500L;

    public CoopSharedCombatSession(CoopNetService service, CoopSessionState session,
                                   LongSupplier clock) {
        this.service = Objects.requireNonNull(service);
        this.session = Objects.requireNonNull(session);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Package/test: inject partner entry success/failure without calling {@code startBattle}. */
    public void setEntryHookForTest(Function<List<String>, Boolean> hook) {
        this.entryHook = hook;
    }

    public Phase phase() {
        return phase;
    }

    public String battleId() {
        return battleId;
    }

    public boolean isActive() {
        return phase == Phase.ACTIVE || phase == Phase.CLAIM_PENDING || phase == Phase.ENTERING;
    }

    public boolean claimGranted() {
        return claimGranted;
    }

    public String endOutcome() {
        return endOutcome;
    }

    /** Engager side: after local battle begins, offer shared combat with classified join mode. */
    public void onLocalEngagerBattleBegin(String battleId, CoopCombatJoinMode mode,
                                          List<String> enemyHullIds) {
        this.battleId = battleId == null ? "" : battleId;
        this.joinMode = mode == null ? CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE : mode;
        this.localIsEngager = true;
        this.entryIssued = true; // engager already in combat
        this.phase = Phase.OFFERED;
        this.enemyHullIds.clear();
        if (enemyHullIds != null) {
            this.enemyHullIds.addAll(enemyHullIds);
        }
        sendAction(CoopMessages.SharedCombatAction.OFFER, encodeOfferBody());
        CoopLog.info(CoopSharedCombatSession.class,
                "shared combat OFFER battleId=" + this.battleId + " mode=" + this.joinMode);
    }

    /** Partner side: BATTLE_BEGIN / join coordinator classified a mode. */
    public void onPartnerBattleOffered(String battleId, CoopCombatJoinMode mode,
                                       List<String> enemyHullIds) {
        this.battleId = battleId == null ? "" : battleId;
        this.joinMode = mode == null ? CoopCombatJoinMode.UNKNOWN_FALLBACK_REMOTE : mode;
        this.localIsEngager = false;
        this.entryIssued = false;
        this.phase = Phase.OFFERED;
        this.enemyHullIds.clear();
        if (enemyHullIds != null) {
            this.enemyHullIds.addAll(enemyHullIds);
        }
        if (!this.joinMode.isRemote()) {
            // Proximity: enter immediately (side-by-side path).
            tryEnterAsPartner();
        }
    }

    @Override
    public void onProximityJoinAvailable(String battleId, String partnerName) {
        if (phase == Phase.IDLE || phase == Phase.ENDED) {
            return;
        }
        this.joinMode = CoopCombatJoinMode.PROXIMITY;
        if (!localIsEngager && !entryIssued) {
            tryEnterAsPartner();
        }
    }

    @Override
    public void onRemoteSupportAccepted(String battleId, String partnerName) {
        if (localIsEngager) {
            return;
        }
        this.joinMode = CoopCombatJoinMode.REMOTE_SUPPORT;
        if (!entryIssued) {
            tryEnterAsPartner();
        }
        // Stay CLAIM_PENDING until CLAIM_ACK — do not let entry success promote to ACTIVE early.
        if (entryIssued && !claimGranted) {
            phase = Phase.CLAIM_PENDING;
            nextClaimProbeAtMillis = 0L;
        }
        CoopLog.info(CoopSharedCombatSession.class,
                "shared combat remote ACCEPTED battleId=" + battleId + " partner=" + partnerName);
    }

    @Override
    public void onRemoteSupportUnavailable(String battleId,
                                           coop.combat.join.CoopRemoteSupportPrompt.State reason) {
        if (reason == coop.combat.join.CoopRemoteSupportPrompt.State.DECLINED
                || reason == coop.combat.join.CoopRemoteSupportPrompt.State.NO_MARKED_SHIP) {
            // Stay spectator-paused; do not enter dual engine.
            phase = Phase.IDLE;
            CoopLog.info(CoopSharedCombatSession.class,
                    "shared combat skip entry reason=" + reason);
        }
    }

    @Override
    public void onRemoteBattleEnded(String battleId) {
        onBattleEnded("REMOTE_END");
    }

    public void tryEnterAsPartner() {
        if (entryIssued || localIsEngager) {
            return;
        }
        Phase prior = phase;
        phase = Phase.ENTERING;
        List<String> hulls = List.copyOf(enemyHullIds);
        boolean ok;
        if (entryHook != null) {
            Boolean result = entryHook.apply(hulls);
            ok = Boolean.TRUE.equals(result);
        } else {
            ok = CoopSharedCombatEntry.enterAgainstShadowEnemy(hulls);
        }
        entryIssued = ok;
        if (ok) {
            sendAction(CoopMessages.SharedCombatAction.READY, battleId);
            // Remote Accept path needs CLAIM_PENDING until ACK; proximity goes ACTIVE immediately.
            if (joinMode.isRemote() && !claimGranted) {
                phase = Phase.CLAIM_PENDING;
            } else {
                phase = Phase.ACTIVE;
            }
            CoopLog.info(CoopSharedCombatSession.class, "shared combat partner ENTERED battleId=" + battleId
                    + " phase=" + phase);
        } else {
            // Preserve Accept intent so a later retry / combat frame can still claim after re-entry.
            phase = prior == Phase.CLAIM_PENDING ? Phase.CLAIM_PENDING : Phase.OFFERED;
            CoopLog.warn(CoopSharedCombatSession.class, "shared combat partner ENTRY failed");
        }
    }

    /** Both sides: each combat frame capture + send + apply. */
    public void onCombatFrame(CombatEngineAPI engine, long nowMillis) {
        if (engine == null || !isActive()) {
            return;
        }
        if (!localIsEngager && joinMode.isRemote() && phase == Phase.CLAIM_PENDING && !claimGranted
                && nowMillis >= nextClaimProbeAtMillis) {
            nextClaimProbeAtMillis = nowMillis + CLAIM_PROBE_INTERVAL_MILLIS;
            sendClaimProbe(engine);
        }
        if (nowMillis < nextFrameAtMillis) {
            return;
        }
        nextFrameAtMillis = nowMillis + FRAME_INTERVAL_MILLIS;
        List<CoopSharedCombatFrameCodec.ShipFrame> frames = captureLocalFrames(engine);
        frameSeq++;
        sendAction(CoopMessages.SharedCombatAction.FRAME,
                battleId + "\n" + frameSeq + "\n" + CoopSharedCombatFrameCodec.encode(frames));
        // Never demote CLAIM_PENDING → ACTIVE before ACK; that dropped claim probes.
        if (phase != Phase.CLAIM_PENDING) {
            phase = Phase.ACTIVE;
        }
    }

    public void handleMessage(CoopMessages.Message message) {
        if (message == null || message.type() != CoopMessages.Type.SHARED_COMBAT) {
            return;
        }
        CoopMessages.Payload payload = CoopMessages.payload(message);
        String actionRaw = payload.requiredString("action");
        CoopMessages.SharedCombatAction action;
        try {
            action = CoopMessages.SharedCombatAction.valueOf(actionRaw);
        } catch (RuntimeException ex) {
            return;
        }
        String body = payload.optionalString("body", "");
        switch (action) {
            case OFFER -> handleOffer(body);
            case READY -> {
                if (localIsEngager) {
                    phase = Phase.ACTIVE;
                    CoopLog.info(CoopSharedCombatSession.class, "shared combat peer READY");
                }
            }
            case FRAME -> handleFrame(body);
            case CLAIM -> handleClaim(body);
            case CLAIM_ACK -> handleClaimAck(body);
            case LEAVE -> onBattleEnded(body == null || body.isEmpty() ? "LEAVE" : body);
            default -> {
            }
        }
    }

    public void onBattleEnded(String outcome) {
        if (phase == Phase.IDLE) {
            return;
        }
        endOutcome = outcome == null ? "" : outcome;
        boolean disconnect = "DISCONNECT".equals(endOutcome) || "SILENCE_TIMEOUT".equals(endOutcome);
        if (!disconnect && phase != Phase.ENDED) {
            sendAction(CoopMessages.SharedCombatAction.LEAVE, endOutcome);
        }
        phase = Phase.ENDED;
        entryIssued = false;
        claimGranted = false;
        localIsEngager = false;
        nextFrameAtMillis = 0L;
        nextClaimProbeAtMillis = 0L;
        frameSeq = 0L;
        remoteShips.clear();
        enemyHullIds.clear();
        CoopLog.info(CoopSharedCombatSession.class,
                "shared combat ENDED battleId=" + battleId + " outcome=" + endOutcome);
        battleId = "";
        phase = Phase.IDLE;
    }

    // ---- internals ------------------------------------------------------------------

    private void handleOffer(String body) {
        // body: battleId\nmode\nhull1,hull2,...  (legacy: mode\nhulls without battleId)
        String offeredBattleId = battleId;
        String mode = "REMOTE_SUPPORT";
        List<String> hulls = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            String[] lines = body.split("\n", -1);
            int modeIdx = 0;
            if (lines.length >= 2 && looksLikeJoinMode(lines[1].trim())) {
                // New format: battleId \n mode \n hulls
                if (!lines[0].isBlank()) {
                    offeredBattleId = lines[0].trim();
                }
                modeIdx = 1;
            } else if (lines.length >= 1 && looksLikeJoinMode(lines[0].trim())) {
                modeIdx = 0;
            }
            if (lines.length > modeIdx && !lines[modeIdx].isBlank()) {
                mode = lines[modeIdx].trim();
            }
            int hullIdx = modeIdx + 1;
            if (lines.length > hullIdx && !lines[hullIdx].isBlank()) {
                for (String h : lines[hullIdx].split(",")) {
                    if (!h.isBlank()) {
                        hulls.add(h.trim());
                    }
                }
            }
        }
        CoopCombatJoinMode parsed;
        try {
            parsed = CoopCombatJoinMode.valueOf(mode);
        } catch (RuntimeException ex) {
            parsed = CoopCombatJoinMode.REMOTE_SUPPORT;
        }
        if (offeredBattleId == null || offeredBattleId.isEmpty()) {
            offeredBattleId = "offer";
        }
        onPartnerBattleOffered(offeredBattleId, parsed, hulls);
    }

    private static boolean looksLikeJoinMode(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            CoopCombatJoinMode.valueOf(token);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String encodeOfferBody() {
        StringBuilder hulls = new StringBuilder();
        for (String h : enemyHullIds) {
            if (hulls.length() > 0) {
                hulls.append(',');
            }
            hulls.append(h);
        }
        return battleId + "\n" + joinMode.name() + "\n" + hulls;
    }

    private void handleFrame(String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        String[] parts = body.split("\n", 3);
        if (parts.length < 3) {
            return;
        }
        List<CoopSharedCombatFrameCodec.ShipFrame> frames = CoopSharedCombatFrameCodec.decode(parts[2]);
        applyRemoteFrames(frames);
    }

    private void sendClaimProbe(CombatEngineAPI engine) {
        // Remote claim targets the engager's marked/support ship, not a local shadow NPC.
        // Empty targetShipId asks the engager to pick (Suite marked ship / first player non-flagship).
        lastClaimTargetId = "";
        CoopSharedCombatClaimCodec.ClaimRequest req = new CoopSharedCombatClaimCodec.ClaimRequest(
                battleId, "", "", "remote-claim", "");
        sendAction(CoopMessages.SharedCombatAction.CLAIM, req.encode());
    }

    private void handleClaim(String body) {
        if (!localIsEngager) {
            return;
        }
        CoopSharedCombatClaimCodec.ClaimRequest req = CoopSharedCombatClaimCodec.ClaimRequest.decode(body);
        float x = 0f;
        float y = 0f;
        float facing = 0f;
        String hull = req.hullId();
        String name = req.shipName();
        String tree = req.variantHint();
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null) {
                ShipAPI ship = findClaimableShip(engine, req.targetShipId());
                if (ship != null) {
                    x = ship.getLocation().x;
                    y = ship.getLocation().y;
                    facing = ship.getFacing();
                    if (hull == null || hull.isEmpty()) {
                        hull = ship.getHullSpec() != null ? ship.getHullSpec().getHullId() : "";
                    }
                    if (name == null || name.isEmpty() || "remote-claim".equals(name)) {
                        name = ship.getName();
                    }
                    try {
                        if ((tree == null || tree.isEmpty()) && ship.getVariant() != null) {
                            tree = ship.getVariant().getHullVariantId();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Prefer Suite full variant tree when present on the classpath.
        String suiteTree = tryEncodeSuiteClaimTree(req.targetShipId());
        if (suiteTree != null && !suiteTree.isEmpty()) {
            tree = suiteTree;
        }
        CoopSharedCombatClaimCodec.ClaimAck ack = new CoopSharedCombatClaimCodec.ClaimAck(
                true, req.battleId(), hull, name, x, y, facing, tree == null ? "" : tree);
        sendAction(CoopMessages.SharedCombatAction.CLAIM_ACK, ack.encode());
    }

    private static ShipAPI findClaimableShip(CombatEngineAPI engine, String preferredId) {
        if (preferredId != null && !preferredId.isEmpty()) {
            ShipAPI exact = findShip(engine, preferredId);
            if (exact != null) {
                return exact;
            }
        }
        ShipAPI fallback = null;
        try {
            ShipAPI player = engine.getPlayerShip();
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || !ship.isAlive() || ship.isFighter() || ship.isShuttlePod()) {
                    continue;
                }
                if (ship.getOwner() != 0) {
                    continue;
                }
                if (ship == player) {
                    continue;
                }
                fallback = ship;
                break;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String tryEncodeSuiteClaimTree(String targetShipId) {
        try {
            Class<?> net = Class.forName("suite.coop.RemoteSupportNet");
            for (java.lang.reflect.Method candidate : net.getMethods()) {
                if ("encodeClaimTreeForShipId".equals(candidate.getName())
                        && candidate.getParameterCount() == 1) {
                    Object out = candidate.invoke(null, targetShipId == null ? "" : targetShipId);
                    return out == null ? "" : out.toString();
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void handleClaimAck(String body) {
        CoopSharedCombatClaimCodec.ClaimAck ack = CoopSharedCombatClaimCodec.ClaimAck.decode(body);
        if (!ack.ok()) {
            CoopLog.warn(CoopSharedCombatSession.class, "shared combat claim denied: " + ack.treeOrReason());
            return;
        }
        claimGranted = true;
        phase = Phase.ACTIVE;
        CoopLog.info(CoopSharedCombatSession.class,
                "shared combat CLAIM_ACK hull=" + ack.hullId() + " name=" + ack.shipName());
        // Suite spawn path (if present) can consume treeOrReason; campaign spike logs grant only.
        try {
            Class<?> net = Class.forName("suite.coop.RemoteSupportNet");
            // Prefer tree rebuild when Suite is on the classpath — best-effort.
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method candidate : net.getMethods()) {
                if ("spawnLocalClaimedShip".equals(candidate.getName())
                        && candidate.getParameterCount() == 6
                        && candidate.getParameterTypes()[0] == String.class) {
                    m = candidate;
                    break;
                }
            }
            if (m != null) {
                m.invoke(null, ack.treeOrReason(), ack.hullId(), ack.shipName(),
                        ack.x(), ack.y(), ack.facing());
            }
        } catch (ClassNotFoundException ignored) {
            // Suite absent — grant recorded; partner still flying shadow battle.
        } catch (Throwable t) {
            CoopLog.warn(CoopSharedCombatSession.class, "Suite claim spawn failed", t);
        }
    }

    private List<CoopSharedCombatFrameCodec.ShipFrame> captureLocalFrames(CombatEngineAPI engine) {
        List<CoopSharedCombatFrameCodec.ShipFrame> out = new ArrayList<>();
        try {
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || ship.isFighter() || ship.isShuttlePod()) {
                    continue;
                }
                String hull = ship.getHullSpec() != null ? ship.getHullSpec().getHullId() : "";
                float hullFrac = 1f;
                float flux = 0f;
                try {
                    if (ship.getMaxHitpoints() > 0f) {
                        hullFrac = ship.getHitpoints() / ship.getMaxHitpoints();
                    }
                    if (ship.getFluxTracker() != null) {
                        flux = ship.getFluxLevel();
                    }
                } catch (Throwable ignored) {
                }
                out.add(new CoopSharedCombatFrameCodec.ShipFrame(
                        ship.getId(), hull, ship.getName(), ship.getOwner(),
                        ship.getLocation().x, ship.getLocation().y, ship.getFacing(),
                        hullFrac, flux, ship.isAlive()));
            }
        } catch (Throwable t) {
            CoopLog.warn(CoopSharedCombatSession.class, "captureLocalFrames failed", t);
        }
        return out;
    }

    private void applyRemoteFrames(List<CoopSharedCombatFrameCodec.ShipFrame> frames) {
        CombatEngineAPI engine;
        try {
            engine = Global.getCombatEngine();
        } catch (Throwable t) {
            return;
        }
        if (engine == null || frames == null) {
            return;
        }
        for (CoopSharedCombatFrameCodec.ShipFrame f : frames) {
            try {
                ShipAPI ship = findShip(engine, f.shipId());
                if (ship == null) {
                    continue; // spawn-on-demand is Phase B+; apply kinematics when present
                }
                if (!f.alive()) {
                    continue;
                }
                ship.getLocation().set(f.x(), f.y());
                ship.setFacing(f.facing());
                remoteShips.put(f.shipId(), ship);
            } catch (Throwable ignored) {
            }
        }
    }

    private static ShipAPI findShip(CombatEngineAPI engine, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            for (ShipAPI ship : engine.getShips()) {
                if (ship != null && id.equals(ship.getId())) {
                    return ship;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void sendAction(CoopMessages.SharedCombatAction action, String body) {
        if (session.sessionId() == null || !session.handshakeValidated() || !service.isConnected()) {
            return;
        }
        try {
            CoopMessages.Message msg = CoopMessages.sharedCombat(
                    session.sessionId(), service.nextSeq(), clock.getAsLong(),
                    action, body == null ? "" : body);
            service.send(msg);
            service.flushOutbound();
        } catch (Throwable t) {
            CoopLog.warn(CoopSharedCombatSession.class, "send SHARED_COMBAT failed action=" + action, t);
        }
    }
}
