package coop.net;

import coop.handshake.CoopHandshakeManifest;
import coop.session.CoopPlayerInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CoopMessages {
    private CoopMessages() {
    }

    public enum Type {
        HELLO,
        LOBBY_HELLO,
        LOBBY_ACCEPT,
        LOBBY_REJECT,
        HANDSHAKE_MANIFEST,
        HANDSHAKE_RESULT,
        SEED_LOCK_REQUEST,
        SEED_LOCK_ACK,
        SEED_LOCK_REJECT,
        TIME_SNAPSHOT,
        PAUSE_INTENT,
        FLEET_SNAPSHOT,
        INTERACTION_CLAIM,
        INTERACTION_ACCEPT,
        INTERACTION_REJECT,
        INTERACTION_RELEASE,
        REP_DELTA,
        GUEST_REP_DELTA,
        PLAYER_REP_SNAPSHOT,
        FACTION_REL_DELTA,
        MISSION_POOL_SNAPSHOT,
        MISSION_CLAIM_REQUEST,
        MISSION_CLAIM_ACCEPT,
        MISSION_CLAIM_REJECT,
        MARKET_OPEN,
        MARKET_SNAPSHOT,
        MARKET_TXN,
        WORLD_DELTA,
        RAID_RESULT,
        COLONY_FOUNDED,
        COLONY_ABANDONED,
        COLONY_MGMT,
        COLONY_INCOME,
        EXPEDITION_WARNING,
        ABILITY_ACTIVATE,
        ORBIT_SNAPSHOT,
        NPC_FLEET_SET,
        NPC_FLEET_MOTION,
        BASE_SET,
        BATTLE_BEGIN,
        BATTLE_STATUS,
        BATTLE_END,
        BATTLE_RESULT,
        ENGAGE_GUEST,
        DIALOG_BEGIN,
        GUEST_SNAPSHOT,
        SAVE_CHECKPOINT,
        RESPAWN_PLAYER,
        PING,
        PONG,
        DISCONNECT
    }

    public record Message(Type type, String sessionId, long seq, long sentAtMillis, String payloadJson) {
        public Message {
            Objects.requireNonNull(type, "type");
            payloadJson = payloadJson == null ? "{}" : payloadJson;
        }
    }

    public static Message hello(String sessionId, long seq, long sentAtMillis, CoopConnectionRole role) {
        Objects.requireNonNull(role, "role");
        return new Message(Type.HELLO, sessionId, seq, sentAtMillis, "{\"role\":\"" + role.name() + "\"}");
    }

    public static Message ping(String sessionId, long seq, long sentAtMillis) {
        return new Message(Type.PING, sessionId, seq, sentAtMillis, "{}");
    }

    public static Message pong(String sessionId, long seq, long sentAtMillis, long pingSeq) {
        return new Message(Type.PONG, sessionId, seq, sentAtMillis, "{\"pingSeq\":" + pingSeq + "}");
    }

    public static Message lobbyHello(long seq, long sentAtMillis, CoopPlayerInfo playerInfo) {
        Objects.requireNonNull(playerInfo, "playerInfo");
        return new Message(Type.LOBBY_HELLO, null, seq, sentAtMillis,
                "{\"playerId\":\"" + escapeJson(playerInfo.playerId()) + "\","
                        + "\"playerName\":\"" + escapeJson(playerInfo.name()) + "\"}");
    }

    public static Message lobbyAccept(long seq, long sentAtMillis, String provisionalLobbyId,
                                      CoopPlayerInfo hostInfo) {
        Objects.requireNonNull(hostInfo, "hostInfo");
        return new Message(Type.LOBBY_ACCEPT, null, seq, sentAtMillis,
                "{\"provisionalLobbyId\":\"" + escapeJson(requireText(provisionalLobbyId, "provisionalLobbyId")) + "\","
                        + "\"hostPlayerId\":\"" + escapeJson(hostInfo.playerId()) + "\","
                        + "\"hostName\":\"" + escapeJson(hostInfo.name()) + "\"}");
    }

    public static Message lobbyReject(long seq, long sentAtMillis, String reason) {
        return new Message(Type.LOBBY_REJECT, null, seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static Message handshakeManifest(long seq, long sentAtMillis, CoopHandshakeManifest manifest,
                                             boolean ironMode) {
        Objects.requireNonNull(manifest, "manifest");
        return new Message(Type.HANDSHAKE_MANIFEST, null, seq, sentAtMillis,
                "{\"manifestJson\":\"" + escapeJson(manifest.toJson()) + "\","
                        + "\"ironMode\":\"" + ironMode + "\"}");
    }

    public static Message handshakeResultAccept(long seq, long sentAtMillis, String sessionId) {
        String acceptedSessionId = requireText(sessionId, "sessionId");
        return new Message(Type.HANDSHAKE_RESULT, acceptedSessionId, seq, sentAtMillis,
                "{\"accepted\":\"true\","
                        + "\"sessionId\":\"" + escapeJson(acceptedSessionId) + "\","
                        + "\"diff\":\"\"}");
    }

    public static Message handshakeResultReject(long seq, long sentAtMillis, String diff) {
        return new Message(Type.HANDSHAKE_RESULT, null, seq, sentAtMillis,
                "{\"accepted\":\"false\","
                        + "\"sessionId\":\"\","
                        + "\"diff\":\"" + escapeJson(diff == null ? "" : diff) + "\"}");
    }

    /**
     * {@code campaignIdMinted} distinguishes a campaign being born (host minted the id at this very
     * seed lock) from an in-flight campaign (id pre-existing). The guest needs it to tell a
     * legitimate first-ever session apart from a fresh same-seed re-roll trying to join a campaign
     * it was never part of — both present as "guest has no stored id" (Phase 6b).
     */
    public static Message seedLockRequest(String sessionId, long seq, long sentAtMillis, long seedLong,
                                          String seedString, String sectorFingerprint, String campaignId,
                                          boolean campaignIdMinted) {
        return new Message(Type.SEED_LOCK_REQUEST, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"seedLong\":" + seedLong + ","
                        + "\"seedString\":\"" + escapeJson(requireText(seedString, "seedString")) + "\","
                        + "\"sectorFingerprint\":\""
                        + escapeJson(requireText(sectorFingerprint, "sectorFingerprint")) + "\","
                        + "\"campaignId\":\"" + escapeJson(requireText(campaignId, "campaignId")) + "\","
                        + "\"campaignIdMinted\":\"" + campaignIdMinted + "\"}");
    }

    public static Message seedLockAck(String sessionId, long seq, long sentAtMillis, String sectorFingerprint) {
        return new Message(Type.SEED_LOCK_ACK, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"sectorFingerprint\":\""
                        + escapeJson(requireText(sectorFingerprint, "sectorFingerprint")) + "\"}");
    }

    public static Message seedLockReject(String sessionId, long seq, long sentAtMillis, String reason) {
        return new Message(Type.SEED_LOCK_REJECT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    /**
     * Host&rarr;guest 5 Hz clock mirror. {@code pausedBy} carries the shared-pause holder, which only
     * the host can compute (the guest deliberately does not store its own key-press intent locally,
     * see {@code CoopSharedPauseCoordinator#recordGuestPauseKeyPress}). Values are the raw holder
     * tokens {@code host}, {@code guest}, {@code guest screen}, {@code combat}, or {@code ""} for
     * "nobody"; the reader maps them to display wording per its own role.
     */
    public static Message timeSnapshot(String sessionId, long seq, boolean paused, boolean fastForward,
                                       long timestampMillis, long campaignDay, long sentAtMillis,
                                       String pausedBy) {
        return new Message(Type.TIME_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"paused\":\"" + paused + "\","
                        + "\"fastForward\":\"" + fastForward + "\","
                        + "\"timestampMillis\":" + timestampMillis + ","
                        + "\"campaignDay\":" + campaignDay + ","
                        + "\"sentAtMillis\":" + sentAtMillis + ","
                        + "\"pausedBy\":\"" + escapeJson(pausedBy == null ? "" : pausedBy) + "\"}");
    }

    /** Source of a guest pause intent: {@code KEY} = manual pause-key, {@code SCREEN} = open screen. */
    public enum PauseSource {
        KEY,
        SCREEN
    }

    /**
     * Phase 11 guest&rarr;host shared-pause intent (reliable TCP). {@code source} distinguishes the
     * overridable manual key pause from the non-overridable screen pause. {@code intentSeq} is a
     * monotonic per-guest sequence used by the host for last-writer-wins debounce, independent of the
     * network envelope {@code seq}.
     */
    public static Message pauseIntent(String sessionId, long seq, long sentAtMillis,
                                      PauseSource source, boolean paused, long intentSeq) {
        Objects.requireNonNull(source, "source");
        return new Message(Type.PAUSE_INTENT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"source\":\"" + source.name() + "\","
                        + "\"paused\":\"" + paused + "\","
                        + "\"intentSeq\":" + intentSeq + "}");
    }

    public static Message interactionClaim(String sessionId, long seq, long sentAtMillis,
                                           String entityId, String entityName, String playerId) {
        return new Message(Type.INTERACTION_CLAIM, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"entityName\":\"" + escapeJson(entityName == null ? "" : entityName) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    public static Message interactionAccept(String sessionId, long seq, long sentAtMillis,
                                            String entityId, String playerId, String entityName, long hostSeq) {
        return new Message(Type.INTERACTION_ACCEPT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\","
                        + "\"entityName\":\"" + escapeJson(entityName == null ? "" : entityName) + "\","
                        + "\"hostSeq\":" + hostSeq + "}");
    }

    public static Message interactionReject(String sessionId, long seq, long sentAtMillis,
                                            String entityId, String reason) {
        return new Message(Type.INTERACTION_REJECT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static Message interactionRelease(String sessionId, long seq, long sentAtMillis,
                                             String entityId, String playerId) {
        return new Message(Type.INTERACTION_RELEASE, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    // ---- Phase 12: campaign state replication -------------------------------------------------
    // Floats ride as quoted strings because the flat envelope parser only understands integral
    // longs and strings; the campaign layer parses them back with requiredPayloadFloat.

    public static Message repDelta(String sessionId, long seq, long sentAtMillis,
                                   String targetType, String targetId, float delta, float resultingValue) {
        return new Message(Type.REP_DELTA, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"targetType\":\"" + escapeJson(requireText(targetType, "targetType")) + "\","
                        + "\"targetId\":\"" + escapeJson(requireText(targetId, "targetId")) + "\","
                        + "\"delta\":\"" + delta + "\","
                        + "\"resultingValue\":\"" + resultingValue + "\"}");
    }

    /** Guest -> host an earned/lost reputation increment; the host folds the DELTA into canonical. */
    public static Message guestRepDelta(String sessionId, long seq, long sentAtMillis,
                                        String targetType, String targetId, float delta) {
        return new Message(Type.GUEST_REP_DELTA, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"targetType\":\"" + escapeJson(requireText(targetType, "targetType")) + "\","
                        + "\"targetId\":\"" + escapeJson(requireText(targetId, "targetId")) + "\","
                        + "\"delta\":\"" + delta + "\"}");
    }

    /** Host -> guest full set of player faction standings; the guest force-matches them (overwrite). */
    public static Message playerRepSnapshot(String sessionId, long seq, long sentAtMillis, String encodedStandings) {
        return new Message(Type.PLAYER_REP_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"reps\":\"" + escapeJson(encodedStandings == null ? "" : encodedStandings) + "\"}");
    }

    public static Message factionRelDelta(String sessionId, long seq, long sentAtMillis,
                                          String factionA, String factionB, float resultingValue) {
        return new Message(Type.FACTION_REL_DELTA, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"factionA\":\"" + escapeJson(requireText(factionA, "factionA")) + "\","
                        + "\"factionB\":\"" + escapeJson(requireText(factionB, "factionB")) + "\","
                        + "\"resultingValue\":\"" + resultingValue + "\"}");
    }

    /**
     * Host -&gt; guest shared offer pool.
     *
     * <p>{@code barSeed} is the host's {@code BarEventManager} seed, which decides how many offers a
     * market shows and the shuffle that picks which ones (Phase 12c). It rides here rather than in its
     * own message because it is only meaningful alongside the pool it shuffles — the same seed over a
     * different pool shows a different bar. {@code 0} means "not carrying a seed"; the engine never
     * holds 0 (it re-rolls on load if it ever is).
     */
    public static Message missionPoolSnapshot(String sessionId, long seq, long sentAtMillis,
                                              String marketId, String encodedPool, long barSeed) {
        return new Message(Type.MISSION_POOL_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"marketId\":\"" + escapeJson(marketId == null ? "" : marketId) + "\","
                        + "\"pool\":\"" + escapeJson(encodedPool == null ? "" : encodedPool) + "\","
                        + "\"barSeed\":" + barSeed + "}");
    }

    public static Message missionClaimRequest(String sessionId, long seq, long sentAtMillis,
                                              String missionId, String playerId) {
        return new Message(Type.MISSION_CLAIM_REQUEST, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"missionId\":\"" + escapeJson(requireText(missionId, "missionId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    public static Message missionClaimAccept(String sessionId, long seq, long sentAtMillis,
                                             String missionId, String playerId, long hostSeq) {
        return new Message(Type.MISSION_CLAIM_ACCEPT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"missionId\":\"" + escapeJson(requireText(missionId, "missionId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\","
                        + "\"hostSeq\":" + hostSeq + "}");
    }

    public static Message missionClaimReject(String sessionId, long seq, long sentAtMillis,
                                             String missionId, String reason) {
        return new Message(Type.MISSION_CLAIM_REJECT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"missionId\":\"" + escapeJson(requireText(missionId, "missionId")) + "\","
                        + "\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    public static Message marketOpen(String sessionId, long seq, long sentAtMillis,
                                     String marketId, String playerId) {
        return new Message(Type.MARKET_OPEN, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"marketId\":\"" + escapeJson(requireText(marketId, "marketId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    public static Message marketSnapshot(String sessionId, long seq, long sentAtMillis,
                                         String marketId, String encodedStock) {
        return new Message(Type.MARKET_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"marketId\":\"" + escapeJson(requireText(marketId, "marketId")) + "\","
                        + "\"stock\":\"" + escapeJson(encodedStock == null ? "" : encodedStock) + "\"}");
    }

    public static Message marketTxn(String sessionId, long seq, long sentAtMillis,
                                    String marketId, String kind, String itemId, int qty, float unitPrice,
                                    String actingPlayerId) {
        return marketTxn(sessionId, seq, sentAtMillis, marketId, kind, itemId, qty, unitPrice,
                actingPlayerId, "");
    }

    /**
     * @param detail kind-specific blob mirroring {@code CoopMarketSync.StockItem.detail} — a
     *               {@code CoopShipDetail} for a ship sold back to the market, empty otherwise. The
     *               payload stays flat JSON: the blob is one opaque string value, and its own
     *               delimited structure is the mod's business, not the envelope's.
     */
    public static Message marketTxn(String sessionId, long seq, long sentAtMillis,
                                    String marketId, String kind, String itemId, int qty, float unitPrice,
                                    String actingPlayerId, String detail) {
        return new Message(Type.MARKET_TXN, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"marketId\":\"" + escapeJson(requireText(marketId, "marketId")) + "\","
                        + "\"kind\":\"" + escapeJson(requireText(kind, "kind")) + "\","
                        + "\"itemId\":\"" + escapeJson(requireText(itemId, "itemId")) + "\","
                        + "\"qty\":" + qty + ","
                        + "\"unitPrice\":\"" + unitPrice + "\","
                        + "\"actingPlayerId\":\"" + escapeJson(actingPlayerId == null ? "" : actingPlayerId) + "\","
                        + "\"detail\":\"" + escapeJson(detail == null ? "" : detail) + "\"}");
    }

    public static Message worldDelta(String sessionId, long seq, long sentAtMillis,
                                     String entityId, String kind, boolean consumed,
                                     String newStateJson, String actingPlayerId) {
        return new Message(Type.WORLD_DELTA, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"entityId\":\"" + escapeJson(requireText(entityId, "entityId")) + "\","
                        + "\"kind\":\"" + escapeJson(requireText(kind, "kind")) + "\","
                        + "\"consumed\":\"" + consumed + "\","
                        + "\"newStateJson\":\"" + escapeJson(newStateJson == null ? "" : newStateJson) + "\","
                        + "\"actingPlayerId\":\"" + escapeJson(actingPlayerId == null ? "" : actingPlayerId) + "\"}");
    }

    /**
     * Phase 24 milestone 1: one finished player raid or bombardment against a colony (reliable TCP,
     * bidirectional). {@code outcome} is the self-contained delimited blob from
     * {@link coop.colony.CoopRaidOutcomeSync.Outcome#encode()} — header line plus one line per
     * touched industry and per commodity deficit, because the envelope parser has no arrays.
     *
     * <p>It deliberately carries no reputation, no loot, and no decivilization. Rep already reaches
     * the peer on the {@code REP_DELTA}/{@code GUEST_REP_DELTA} channel, loot is the raider's own by
     * the same rule salvage follows, and a saturation bombardment that razes the colony travels as
     * {@code WORLD_DELTA(DECIV)}; see {@link coop.colony.CoopRaidOutcomeSync} for the argument.
     */
    public static Message raidResult(String sessionId, long seq, long sentAtMillis, String outcome) {
        return new Message(Type.RAID_RESULT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"outcome\":\"" + escapeJson(requireText(outcome, "outcome")) + "\"}");
    }

    /**
     * Phase 24 milestone 2: a colony either player just founded (reliable TCP, bidirectional).
     * {@code colony} is the self-contained delimited blob from
     * {@link coop.colony.CoopColonySync.Event#encode()} — header line plus one line per condition,
     * industry, submarket and construction-queue entry, because the envelope parser has no arrays.
     *
     * <p>It carries both the planet's entity id and the market's id. The market is the planet's
     * gen-time planet-condition market, promoted in place rather than created, so its id
     * ({@code "market_" + planetId}) matches across seed-locked engines — but that market is not
     * registered with the economy until it is colonized, so the receiving engine has to reach it
     * through the planet.
     */
    public static Message colonyFounded(String sessionId, long seq, long sentAtMillis, String colony) {
        return new Message(Type.COLONY_FOUNDED, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"colony\":\"" + escapeJson(requireText(colony, "colony")) + "\"}");
    }

    /**
     * Phase 24 milestone 2: a colony either player just abandoned (reliable TCP, bidirectional). Same
     * body shape as {@link #colonyFounded}, but identity only: vanilla reports abandonment
     * <em>after</em> its own teardown has run ({@code AbandonMarketPluginImpl.java:121-123}), so
     * there is no colony state left to read and none is needed — the applier re-runs the same vanilla
     * teardown on its own copy.
     *
     * <p>The evacuation cost and shutdown refund deliberately do not ride along: they are the
     * abandoning player's own credits, the same rule salvage and raid loot follow.
     */
    public static Message colonyAbandoned(String sessionId, long seq, long sentAtMillis, String colony) {
        return new Message(Type.COLONY_ABANDONED, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"colony\":\"" + escapeJson(requireText(colony, "colony")) + "\"}");
    }

    /**
     * Phase 24 milestone 3: the colony-management state of one player-owned market, as it stood when
     * a player closed its screen (reliable TCP, bidirectional). {@code mgmt} is the self-contained
     * delimited blob from {@link coop.colony.CoopColonyManagement.State#encode()} — header line plus
     * one line per industry and per construction-queue entry.
     *
     * <p>The body is <b>absolute post-close state</b>, not a diff: the diff only decides whether to
     * send at all. That makes a duplicate delivery, a host rebroadcast and a late arrival all
     * idempotent, and it is why no ordering guarantee beyond TCP's is needed. Concurrency needs none
     * either — the Phase 10 interaction gate is a global first-come lockout, so two players are never
     * inside colony screens at the same time.
     */
    public static Message colonyMgmt(String sessionId, long seq, long sentAtMillis, String mgmt) {
        return new Message(Type.COLONY_MGMT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"mgmt\":\"" + escapeJson(requireText(mgmt, "mgmt")) + "\"}");
    }

    /**
     * Phase 24 milestone 3: the host's canonical colony net for a finished economy month (reliable
     * TCP, host&rarr;guest). <b>Carries no money.</b> Credits are per-player local state and are never
     * transferred: both engines run the same replicated colonies, each pays its own player the full
     * local net at month end, and each deducts its own half through {@code CoopRewardSplitter}. This
     * message exists so the guest can log how far its own local net drifted from the host's — drift
     * detection, never correction.
     *
     * @param netCredits the host's colony income minus colony upkeep for the month.
     * @param colonyCount how many player-owned markets the host counted, so a drift line can say
     *                    whether the two engines even agree on the colony set.
     */
    public static Message colonyIncome(String sessionId, long seq, long sentAtMillis,
                                       float netCredits, long colonyCount) {
        return new Message(Type.COLONY_INCOME, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"netCredits\":\"" + netCredits + "\","
                        + "\"colonyCount\":" + colonyCount + "}");
    }

    /**
     * Phase 24 milestone 3: the host's full set of live NPC threats aimed at player colonies
     * (reliable TCP, host&rarr;guest). The body is a
     * {@link coop.colony.CoopExpeditionWarning#encodeSet} blob — one
     * {@code kind|factionId|targetMarketId|targetName|etaDays|status|goal} record per line, because the
     * flat envelope parser has no arrays. {@code goal} is display text the host already resolved
     * ("saturation bombardment", "raid to disrupt Heavy Industry"), empty when there is none.
     *
     * <p>Set-reconciled exactly like {@code BASE_SET}: rebroadcast only when the order-independent set
     * hash changes, and an empty set is a legitimate value that clears every mirrored warning. The
     * guest turns the records into its own {@code CoopExpeditionWarningIntel} entries; the vanilla
     * intel objects themselves are never replicated.
     */
    public static Message expeditionWarning(String sessionId, long seq, long sentAtMillis,
                                            String encodedSet) {
        return new Message(Type.EXPEDITION_WARNING, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"warnings\":\"" + escapeJson(encodedSet == null ? "" : encodedSet) + "\"}");
    }

    public static Message abilityActivate(String sessionId, long seq, long sentAtMillis,
                                          String abilityId, String playerId, String targetJson) {
        return new Message(Type.ABILITY_ACTIVATE, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"abilityId\":\"" + escapeJson(requireText(abilityId, "abilityId")) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\","
                        + "\"targetJson\":\"" + escapeJson(targetJson == null ? "" : targetJson) + "\"}");
    }

    public static Message orbitSnapshot(String sessionId, long seq, long sentAtMillis,
                                        String locationId, String encodedOrbits) {
        return new Message(Type.ORBIT_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"locationId\":\"" + escapeJson(requireText(locationId, "locationId")) + "\","
                        + "\"orbits\":\"" + escapeJson(encodedOrbits == null ? "" : encodedOrbits) + "\"}");
    }

    /**
     * Phase 9 host&rarr;guest full authoritative NPC fleet set (reliable TCP). The body is a
     * {@link coop.fleet.CoopNpcFleetSetSnapshot#encode()} blob; the guest reconciles its mirror
     * registry against it. Rebroadcast whenever the set hash changes. (NPC fleet *motion* rides the
     * separate high-frequency UDP {@code NPC_FLEET_MOTION} datagram, built via {@link #datagram}.)
     */
    public static Message npcFleetSet(String sessionId, long seq, long sentAtMillis,
                                      long gameTimeMillis, String encodedSet) {
        // gameTimeMillis is the sender's stream time (CoopStreamClock) at capture, so the guest can
        // feed set positions into the same interpolation buffers the UDP motion sections fill
        // (Phase 29 M1) — a TCP Message has no datagram section stamp to carry it otherwise.
        return new Message(Type.NPC_FLEET_SET, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"gameTimeMillis\":" + gameTimeMillis + ","
                        + "\"set\":\"" + escapeJson(encodedSet == null ? "" : encodedSet) + "\"}");
    }

    /**
     * Phase 13 host&rarr;guest full authoritative set of dynamic pirate / Luddic-Path bases (reliable
     * TCP). The body is a {@link coop.campaign.CoopBaseRecord#encodeSet} blob — one
     * {@code kind|systemId|factionId|attr} record per line, because the flat envelope parser has no
     * arrays. Rebroadcast whenever the order-independent set hash changes; the guest reconciles
     * idempotently by {@code (kind, systemId)}.
     */
    public static Message baseSet(String sessionId, long seq, long sentAtMillis, String encodedSet) {
        return new Message(Type.BASE_SET, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"bases\":\"" + escapeJson(encodedSet == null ? "" : encodedSet) + "\"}");
    }

    // ---- Phase 14/15: solo own-fleet combat + spectator bridge + result reconciliation -----------
    // All of them ride reliable TCP. There is no UDP combat stream and no disconnect protocol: combat
    // is entirely local to whoever fights, and a dropped connection is detected locally by each side.

    /** Which client-side path opened a coop battle (diagnostics + spectator wording). */
    public enum BattleKind {
        /** The local player engaged through the normal vanilla interaction dialog. */
        PLAYER,
        /** The host's threat watcher pushed {@code ENGAGE_GUEST} and the guest opened it. */
        ENGAGE_GUEST
    }

    /** What a host-synthesized {@code DIALOG_BEGIN} wants the guest to stage locally. */
    public enum DialogKind {
        CUSTOMS,
        INSPECTION
    }

    /**
     * Engaging client &rarr; spectator: a coop battle just started, and the sender is its authority.
     * {@code npcFleetIds} is a comma-joined list of host-owned {@code coopFleetId}s taking part (the
     * envelope parser has no arrays), empty when none could be resolved.
     */
    public static Message battleBegin(String sessionId, long seq, long sentAtMillis,
                                      String battleId, String playerId, String locationName,
                                      String enemySummary, String npcFleetIds, BattleKind kind) {
        Objects.requireNonNull(kind, "kind");
        return new Message(Type.BATTLE_BEGIN, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"battleId\":\"" + escapeJson(requireText(battleId, "battleId")) + "\","
                        + "\"playerId\":\"" + escapeJson(playerId == null ? "" : playerId) + "\","
                        + "\"locationName\":\"" + escapeJson(locationName == null ? "" : locationName) + "\","
                        + "\"enemySummary\":\"" + escapeJson(enemySummary == null ? "" : enemySummary) + "\","
                        + "\"npcFleetIds\":\"" + escapeJson(npcFleetIds == null ? "" : npcFleetIds) + "\","
                        + "\"kind\":\"" + kind.name() + "\"}");
    }

    /**
     * Engaging client &rarr; spectator, 2-5 Hz: the current ship states + kill feed. Stateless and
     * latest-wins by {@code statusSeq} ({@link coop.combat.CoopBattleStatus#isNewer}), so a dropped
     * or reordered frame costs nothing. {@code ships} is the self-contained delimited body from
     * {@link coop.combat.CoopBattleStatus#encodeBody()}.
     */
    public static Message battleStatus(String sessionId, long seq, long sentAtMillis,
                                       String battleId, long statusSeq, long elapsedMillis, String ships) {
        return new Message(Type.BATTLE_STATUS, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"battleId\":\"" + escapeJson(requireText(battleId, "battleId")) + "\","
                        + "\"statusSeq\":" + statusSeq + ","
                        + "\"elapsedMillis\":" + elapsedMillis + ","
                        + "\"ships\":\"" + escapeJson(ships == null ? "" : ships) + "\"}");
    }

    /** Engaging client &rarr; spectator: the battle is over; release the combat pause, close the panel. */
    public static Message battleEnd(String sessionId, long seq, long sentAtMillis,
                                    String battleId, String playerId, String outcome) {
        return new Message(Type.BATTLE_END, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"battleId\":\"" + escapeJson(requireText(battleId, "battleId")) + "\","
                        + "\"playerId\":\"" + escapeJson(playerId == null ? "" : playerId) + "\","
                        + "\"outcome\":\"" + escapeJson(outcome == null ? "" : outcome) + "\"}");
    }

    /**
     * Phase 15 engaging client &rarr; host: the <em>campaign-level</em> consequences of a finished
     * battle, so the host's authoritative Phase 9 NPC fleet set can absorb them. Reliable TCP,
     * one-shot per battle, idempotent on {@code battleId}.
     *
     * <p>{@code body} is the self-contained delimited blob from
     * {@link coop.combat.CoopBattleResult#encodeBody()} (destroyed {@code coopFleetId}s + surviving
     * fleets with their post-battle rosters) — the envelope parser has no arrays.
     *
     * <p><b>It deliberately carries no reputation and no spoils.</b> Faction rep already reaches the
     * host on the Phase 12 {@code GUEST_REP_DELTA} path, and spoils are the fighter's own by the v1
     * reward rule; see {@link coop.combat.CoopBattleResult} for the full argument.
     */
    public static Message battleResult(String sessionId, long seq, long sentAtMillis,
                                       String battleId, String engagingPlayerId, String outcome,
                                       int engagingFleetSize, String body) {
        return new Message(Type.BATTLE_RESULT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"battleId\":\"" + escapeJson(requireText(battleId, "battleId")) + "\","
                        + "\"engagingPlayerId\":\""
                        + escapeJson(engagingPlayerId == null ? "" : engagingPlayerId) + "\","
                        + "\"outcome\":\"" + escapeJson(outcome == null ? "" : outcome) + "\","
                        + "\"engagingFleetSize\":" + engagingFleetSize + ","
                        + "\"body\":\"" + escapeJson(body == null ? "" : body) + "\"}");
    }

    /**
     * Host &rarr; guest: a hostile host-owned NPC fleet has closed on the guest mirror and picked
     * ENGAGE. The guest opens and pilots that battle locally against its own mirror of
     * {@code coopFleetId} (never PvP). See {@link coop.combat.CoopNpcThreatWatcher}.
     */
    public static Message engageGuest(String sessionId, long seq, long sentAtMillis,
                                      String coopFleetId, String fleetName, String factionId) {
        return new Message(Type.ENGAGE_GUEST, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"coopFleetId\":\"" + escapeJson(requireText(coopFleetId, "coopFleetId")) + "\","
                        + "\"fleetName\":\"" + escapeJson(fleetName == null ? "" : fleetName) + "\","
                        + "\"factionId\":\"" + escapeJson(factionId == null ? "" : factionId) + "\","
                        + "\"kind\":\"COMBAT\"}");
    }

    /**
     * Host &rarr; guest: a host-owned patrol wants to stop the guest (running dark / contraband
     * suspicion). The guest stages the vanilla posture flags on its own mirror of
     * {@code coopFleetId} and opens the standard fleet interaction against it, so vanilla rules run
     * the scan against the guest's own cargo. This is the Phase 9 transponder-reactions gap fix.
     */
    public static Message dialogBegin(String sessionId, long seq, long sentAtMillis,
                                      String coopFleetId, String factionId, DialogKind kind) {
        Objects.requireNonNull(kind, "kind");
        return new Message(Type.DIALOG_BEGIN, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"coopFleetId\":\"" + escapeJson(requireText(coopFleetId, "coopFleetId")) + "\","
                        + "\"factionId\":\"" + escapeJson(factionId == null ? "" : factionId) + "\","
                        + "\"kind\":\"" + kind.name() + "\"}");
    }

    // ---- Phase 16: coordinated saves + guest snapshot -------------------------------------------

    /**
     * Guest &rarr; host, low rate: the guest's own fleet/cargo/credits/officers, so the host has
     * something current to embed in its save. {@code body} is the self-contained delimited blob from
     * {@link coop.save.CoopGuestSnapshot#encodeBody()} — the envelope parser has no arrays.
     *
     * <p>It is not folded into the 10 Hz {@code FLEET_SNAPSHOT} datagram: this is cargo-and-credits
     * sized, needed once per host save rather than per frame, and must arrive reliably (TCP) because
     * a dropped copy is a stale recovery artifact rather than a dropped animation frame.
     */
    public static Message guestSnapshot(String sessionId, long seq, long sentAtMillis, String body) {
        return new Message(Type.GUEST_SNAPSHOT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"body\":\"" + escapeJson(body == null ? "" : body) + "\"}");
    }

    /**
     * Host &rarr; guest: the host just saved (manual, autosave, or session end). The guest takes its
     * own vanilla autosave as soon as no screen is open, keeping the two saves temporally aligned.
     * {@code checkpointId} is a host-local monotonic counter used for duplicate suppression, not the
     * envelope {@code seq}.
     */
    public static Message saveCheckpoint(String sessionId, long seq, long sentAtMillis,
                                         long checkpointId, String reason) {
        return new Message(Type.SAVE_CHECKPOINT, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"checkpointId\":" + checkpointId + ","
                        + "\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    // ---- Phase 17: fleet wipe ---------------------------------------------------------------------

    /**
     * Wiped client &rarr; partner: vanilla's {@code CampaignState.showShuttleDialog()} just swapped
     * this client's player fleet for the two-ship starter set and teleported it to a random friendly
     * market. Reliable TCP, one-shot per wipe; the mod builds none of the respawn itself.
     *
     * <p>Purely a notification. Without it the survivor's only cue is the partner mirror silently
     * jumping across the sector, and the mirror carries no "what happened" channel.
     * {@code destinationName} is best-effort ("" when the destination could not be resolved) and the
     * receiver resolves the player's display name from its own session state.
     */
    public static Message respawnPlayer(String sessionId, long seq, long sentAtMillis,
                                        String playerId, String destinationName) {
        return new Message(Type.RESPAWN_PLAYER, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\","
                        + "\"destinationName\":\""
                        + escapeJson(destinationName == null ? "" : destinationName) + "\"}");
    }

    public static Message disconnect(String sessionId, long seq, long sentAtMillis, String reason) {
        return new Message(Type.DISCONNECT, sessionId, seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    /**
     * UDP datagram envelope for high-frequency state streams (Phase 8 fleet snapshots, later combat
     * snapshots). Unlike the TCP {@link Message} line protocol this is not JSON: each section body
     * carries its own compact encoding (e.g. {@link coop.fleet.CoopFleetSnapshot#encode()}), and the
     * envelope is fields joined by a unit-separator that never appears in those encodings. Datagrams
     * are framed one-per-packet by UDP itself.
     *
     * <p><b>Phase 29 M1 wire prerequisite (2026-08-24; the Phase 20.1 epoch guard pulled forward):</b>
     * the envelope carries one or more <em>sections</em>, oldest first, each stamped with the sender's
     * monotonic {@code epoch} and stream time ({@link CoopStreamClock}). The current send plus the
     * previous one ride in the same packet ({@link CoopDatagramRedundancy}), so a single lost datagram
     * costs nothing — its sections arrive again in the next one. The receiver drops sections at or
     * below its per-type epoch watermark ({@link CoopDatagramWatermark}), which is also what makes a
     * reordered datagram inert instead of a stale-position apply. Wire shape: {@code sessionId},
     * {@code TYPE}, then per section {@code epoch}, {@code sentGameTimeMillis}, {@code body}, all
     * unit-separator joined — token count {@code 2 + 3n}.
     */
    public record Datagram(String sessionId, Type type, List<DatagramSection> sections) {
        public Datagram {
            type = Objects.requireNonNull(type, "type");
            sessionId = sessionId == null ? "" : sessionId;
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /** One stamped body within a {@link Datagram}; {@code sentGameTimeMillis} is sender stream time. */
    public record DatagramSection(long epoch, long sentGameTimeMillis, String body) {
        public DatagramSection {
            body = body == null ? "" : body;
        }
    }

    private static final char DATAGRAM_SEPARATOR = '\u001f';

    public static String datagram(String sessionId, Type type, List<DatagramSection> sections) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sections, "sections");
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("Datagram needs at least one section");
        }
        StringBuilder out = new StringBuilder(64 + sections.size() * 32);
        out.append(sessionId == null ? "" : sessionId).append(DATAGRAM_SEPARATOR).append(type.name());
        for (DatagramSection section : sections) {
            out.append(DATAGRAM_SEPARATOR).append(section.epoch())
                    .append(DATAGRAM_SEPARATOR).append(section.sentGameTimeMillis())
                    .append(DATAGRAM_SEPARATOR).append(section.body());
        }
        return out.toString();
    }

    /** Single-section convenience (transport tests and call sites without redundancy). */
    public static String datagram(String sessionId, Type type, long epoch, long sentGameTimeMillis,
                                  String body) {
        return datagram(sessionId, type, List.of(new DatagramSection(epoch, sentGameTimeMillis, body)));
    }

    public static Datagram parseDatagram(String raw) {
        Objects.requireNonNull(raw, "raw");
        String[] tokens = splitDatagram(raw);
        if (tokens.length < 5 || (tokens.length - 2) % 3 != 0) {
            throw new IllegalArgumentException("Malformed coop datagram envelope");
        }
        String sessionId = tokens[0];
        Type type = Type.valueOf(tokens[1]);
        int sectionCount = (tokens.length - 2) / 3;
        List<DatagramSection> sections = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            int base = 2 + i * 3;
            try {
                sections.add(new DatagramSection(Long.parseLong(tokens[base]),
                        Long.parseLong(tokens[base + 1]), tokens[base + 2]));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Malformed coop datagram section stamp", ex);
            }
        }
        return new Datagram(sessionId, type, sections);
    }

    /**
     * Splits on the unit separator without regex. Bodies never contain the separator (each body
     * encoding predates the envelope and was chosen for exactly that), so a flat split is exact.
     */
    private static String[] splitDatagram(String raw) {
        List<String> tokens = new ArrayList<>(8);
        int start = 0;
        while (true) {
            int idx = raw.indexOf(DATAGRAM_SEPARATOR, start);
            if (idx < 0) {
                tokens.add(raw.substring(start));
                return tokens.toArray(new String[0]);
            }
            tokens.add(raw.substring(start, idx));
            start = idx + 1;
        }
    }

    public static String encode(Message message) {
        Objects.requireNonNull(message, "message");
        StringBuilder json = new StringBuilder(128);
        json.append('{');
        json.append("\"type\":\"").append(message.type().name()).append("\",");
        json.append("\"sessionId\":");
        appendNullableString(json, message.sessionId());
        json.append(',');
        json.append("\"seq\":").append(message.seq()).append(',');
        json.append("\"sentAtMillis\":").append(message.sentAtMillis()).append(',');
        json.append("\"payloadJson\":\"").append(escapeJson(message.payloadJson())).append("\"");
        json.append('}');
        return json.toString();
    }

    public static Message decode(String json) {
        Map<String, Object> fields = new Parser(json).parseObject();
        Type type = Type.valueOf(requiredString(fields, "type"));
        String sessionId = nullableString(fields, "sessionId");
        long seq = requiredLong(fields, "seq");
        long sentAtMillis = requiredLong(fields, "sentAtMillis");
        String payloadJson = requiredString(fields, "payloadJson");
        return new Message(type, sessionId, seq, sentAtMillis, payloadJson);
    }

    public static Map<String, Object> decodePayload(Message message) {
        Objects.requireNonNull(message, "message");
        return new Parser(message.payloadJson()).parseObject();
    }

    private static void appendNullableString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"').append(escapeJson(value)).append('"');
    }

    private static String requiredString(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalArgumentException("Missing string field: " + name);
    }

    public static String requiredPayloadString(Message message, String name) {
        return requiredString(decodePayload(message), name);
    }

    /**
     * Reads a string payload field that may be absent, so a message built by an older peer (before
     * the field existed) still parses. Returns {@code fallback} when the field is missing or is not
     * a string.
     */
    public static String optionalPayloadString(Message message, String name, String fallback) {
        Object value = decodePayload(message).get(name);
        return value instanceof String stringValue ? stringValue : fallback;
    }

    public static long requiredPayloadLong(Message message, String name) {
        return requiredLong(decodePayload(message), name);
    }

    /** Reads a float field that was encoded as a quoted string (see Phase 12 builders). */
    public static float requiredPayloadFloat(Message message, String name) {
        return Float.parseFloat(requiredString(decodePayload(message), name));
    }

    private static String nullableString(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalArgumentException("Expected nullable string field: " + name);
    }

    private static long requiredLong(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Long longValue) {
            return longValue;
        }
        throw new IllegalArgumentException("Missing long field: " + name);
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = Objects.requireNonNull(json, "json");
        }

        private Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return fields;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                fields.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                expect('}');
                skipWhitespace();
                if (index != json.length()) {
                    throw error("Trailing content");
                }
                return fields;
            }
        }

        private Object parseValue() {
            if (peek('"')) {
                return parseString();
            }
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            return parseLong();
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (index >= json.length()) {
                    throw error("Unterminated escape sequence");
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseUnicodeEscape());
                    default -> throw error("Unsupported escape sequence: \\" + escaped);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > json.length()) {
                throw error("Incomplete unicode escape");
            }
            String digits = json.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException ex) {
                throw error("Invalid unicode escape: " + digits);
            }
        }

        private Long parseLong() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
            if (start == index || (json.charAt(start) == '-' && start + 1 == index)) {
                throw error("Expected number");
            }
            try {
                return Long.parseLong(json.substring(start, index));
            } catch (NumberFormatException ex) {
                throw error("Invalid long value");
            }
        }

        private void expect(char expected) {
            if (index >= json.length() || json.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private boolean startsWith(String value) {
            return json.startsWith(value, index);
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }
    }
}
