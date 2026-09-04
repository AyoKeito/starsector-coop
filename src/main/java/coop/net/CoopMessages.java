package coop.net;

import coop.handshake.CoopHandshakeManifest;
import coop.session.CoopPlayerInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CoopMessages {
    private CoopMessages() {
    }

    public enum Type {
        LOBBY_HELLO,
        /**
         * Phase 20.4: host &rarr; guest, "this session wants a password; prove it against this
         * nonce". Sent <em>instead of</em> {@code LOBBY_ACCEPT} and without taking a session slot, so
         * a wrong guess costs the host nothing but a nonce.
         */
        LOBBY_CHALLENGE,
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
        /**
         * Phase 20 M4 roster split: the immutable half of a player fleet, on reliable TCP, sent on
         * {@code fleetHash16} change / session start / resume. The 10 Hz {@code FLEET_SNAPSHOT}
         * datagram carries only the volatile tick that references it.
         */
        FLEET_ROSTER,
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
        /** Phase 20.1: each side's ~5 s report of what it is actually receiving (RTT, loss, transport). */
        LINK_STATUS,
        /**
         * Phase 20.1 UDP-blocked fallback: a composed state datagram carried on TCP verbatim, so the
         * receiver runs the identical parse/token/watermark/apply path either wire delivered it.
         */
        STATE_DATAGRAM,
        /**
         * Phase 20.2: guest &rarr; host on a reconnected socket, asking to pick the held session back
         * up instead of starting a new lobby round. Carries the session id and the guest's player id;
         * both must match what the host is holding.
         */
        SESSION_RESUME_REQUEST,
        /** Phase 20.2: host &rarr; guest, the session is yours again — a full rebroadcast follows. */
        SESSION_RESUME_ACCEPT,
        /** Phase 20.2: host &rarr; guest, that request does not match the held session (with reason). */
        SESSION_RESUME_REJECT,
        /** Datagram-only: idle-path UDP keepalive so NAT bindings and link liveness survive quiet stretches. */
        UDP_PROBE,
        /** Datagram-only: QUIC-style path challenge/echo that proves a new UDP source before it is streamed to. */
        PATH_PROBE,
        /**
         * Phase 20 red-team B4: "my process is about to stop pumping, and it is not a battle." Sent
         * by <em>both</em> roles from {@code beforeGameSave} and flushed inline, so the peer's
         * link-death rule gets the same exemption a {@code SAVE_CHECKPOINT} gives it. Deliberately
         * separate from {@code SAVE_CHECKPOINT}: that message <em>orders a coordinated autosave</em>
         * and only the host may send it, whereas this one only says "expect silence".
         */
        STALL_NOTICE,
        /**
         * Phase 20 red-team C6: "re-send me your {@code FLEET_ROSTER}." A roster that was dropped or
         * rejected is otherwise never retried — the sender only re-sends on a hash change — leaving
         * the receiving mirror stuck on a tick whose roster never arrives.
         */
        FLEET_ROSTER_REQUEST,
        /**
         * Phase 21 lobby: guest &rarr; host, "this is the join phase I have reached and whether I am
         * ready". Sent on every local phase/ready change and re-sent on a reconnect, so the host's
         * roster never depends on a message it might have missed.
         */
        READY_STATE,
        /**
         * Phase 21 lobby: host &rarr; guest, the whole roster plus the countdown and the released
         * flag. The host is authoritative; this is a full replacement, sent on every roster change
         * and at least once a second so the lobby never shows a static frame.
         */
        LOBBY_STATUS,
        /**
         * Host &rarr; guest, cosmetic: the host's {@code CoopSessionStats} tally, encoded whole by
         * {@link coop.stats.CoopSessionStatsCodec#encodePayload}. Not on the reconnect-grace
         * whitelist ({@code CoopNetPump#allowedDuringReconnectGrace}) — a cosmetic tally must not be
         * a channel an unproven peer can speak on.
         */
        SESSION_STATS,
        /**
         * Guest &rarr; host: one hull the guest lost, feeding {@code CoopSessionStats#noteShipLost}
         * on the host, which is the tally owner.
         */
        SHIP_LOST,
        /**
         * Phase 28 milestone 2: host &rarr; guest, the whole host-authoritative policy for this
         * campaign - every {@code CoopOptionsRegistry.Tier#POLICY} key, a monotonic
         * {@code policyVersion}, and the {@code changedKey} that caused this send (empty for the
         * establish/resume broadcasts). A full replacement, so a guest can never be left holding a
         * key from an older session.
         *
         * <p>Sent when the lobby releases, again on every resume, and on every host change.
         * Deliberately <b>not</b> on the reconnect-grace whitelist
         * ({@code CoopNetPump#allowedDuringReconnectGrace}): an unproven peer must not be able to
         * rewrite the rules of a session it has not yet proved it belongs to.
         */
        OPTIONS_SNAPSHOT,
        /**
         * Phase 28 milestone 2: guest &rarr; host, "my applied policy has caught up to version N".
         *
         * <p>The acknowledgement half of the apply-boundary contract. A key whose boundary is not
         * {@code IMMEDIATE} is <em>pending</em> on the host until the guest - the only client with a
         * boundary to cross for it - says it has crossed it. Before this the host guessed, by
         * looking at whether the guest was currently holding a screen pause, and the guess was
         * degenerate in the direction that matters: with the option already off the guest holds no
         * pause, so turning it back ON while the guest was reading a tab promoted the host's applied
         * value on the spot while the guest correctly stayed pending.
         *
         * <p>Cheaper than an {@code appliedVersion} field on {@code LINK_STATUS}: one small message
         * per policy change instead of a fifteenth field on a record with a dozen call sites and a
         * compatibility constructor, and it acknowledges within a frame rather than within the 5 s
         * {@code LINK_STATUS} tick.
         *
         * <p>Not on the reconnect-grace whitelist, for the same reason as {@code OPTIONS_SNAPSHOT}.
         */
        OPTIONS_APPLIED
    }

    /**
     * TCP control message. {@code senderId} (Phase 20.5) is the full player id of the originator and
     * is nullable: the ~50 factories deliberately do not take it, because stamping it at the factory
     * would mean threading the local id through every call site. {@link CoopNetService#send} stamps it
     * on the way out instead, so one seam owns it. Nothing routes on it yet — it exists now because
     * the wire format is the expensive thing to change once two installs must agree on it.
     */
    public record Message(Type type, String sessionId, long seq, long sentAtMillis, String payloadJson,
                          String senderId) {
        public Message {
            Objects.requireNonNull(type, "type");
            payloadJson = payloadJson == null ? "{}" : payloadJson;
        }

        /** Unstamped message (every factory below); the sending service fills {@code senderId} in. */
        public Message(Type type, String sessionId, long seq, long sentAtMillis, String payloadJson) {
            this(type, sessionId, seq, sentAtMillis, payloadJson, null);
        }

        /** Copy stamped with {@code senderId}; returns {@code this} when it already carries one. */
        public Message withSenderId(String senderId) {
            if (this.senderId != null || senderId == null) {
                return this;
            }
            return new Message(type, sessionId, seq, sentAtMillis, payloadJson, senderId);
        }
    }

    /**
     * Short wire id: the first 16 hex characters (64 bits) of the SHA-256 of {@code fullId}.
     *
     * <p>Datagrams pay their envelope on every packet at 10 Hz, where a 36-character UUID is ~9% of a
     * 1,200-byte MTU budget for a field that only ever has to answer "is this ours?". 64 bits keeps
     * blind spoofing infeasible for that drop-foreign-traffic role, and the address-hijack case is
     * covered by the {@code PATH_PROBE} challenge-echo rather than by this token's width. The full
     * UUID still travels on TCP, where one control message per second costs nothing.
     */
    public static String wireToken(String fullId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((fullId == null ? "" : fullId).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(WIRE_TOKEN_CHARS);
            for (int i = 0; out.length() < WIRE_TOKEN_CHARS; i++) {
                out.append(Character.forDigit((hash[i] >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(hash[i] & 0x0f, 16));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive coop wire token", ex);
        }
    }

    /** Characters of hex in a {@link #wireToken(String)}; 16 hex = 64 bits. */
    public static final int WIRE_TOKEN_CHARS = 16;

    public static Message ping(String sessionId, long seq, long sentAtMillis) {
        return new Message(Type.PING, sessionId, seq, sentAtMillis, "{}");
    }

    public static Message pong(String sessionId, long seq, long sentAtMillis, long pingSeq) {
        return new Message(Type.PONG, sessionId, seq, sentAtMillis, "{\"pingSeq\":" + pingSeq + "}");
    }

    /**
     * Phase 20.1 link report, sent by both roles every ~5 s while a gameplay session is live. It is
     * how each side learns what the <em>other</em> side is receiving: the local peer can measure its
     * own inbound UDP silence but has no way to tell whether its outbound datagrams are landing, and
     * {@code udpInboundOk} here is exactly that missing half of the UDP-blocked decision.
     *
     * <p>{@code rttMillis}/{@code p95RttMillis} are -1 when no PONG has been matched yet; a null on
     * the wire would cost a nullable type for a field that is a plain number 99% of a session.
     */
    public static Message linkStatus(String sessionId, long seq, long sentAtMillis,
                                     CoopLinkQuality.Snapshot link, String transport,
                                     CoopDatagramStats stats) {
        return linkStatus(sessionId, seq, sentAtMillis, link, transport,
                CoopCadenceTier.DEFAULT.hz(), stats);
    }

    /**
     * As above, carrying the Phase 29 M2 {@code cadenceHz}: the {@link CoopCadenceTier} this side's
     * UDP state streams are currently sending at. The host's announcement is what the guest applies;
     * the guest echoes back what it applied, which is how each side learns the interval its
     * <em>peer</em> is sending at and can size its interpolation delay against that interval.
     */
    public static Message linkStatus(String sessionId, long seq, long sentAtMillis,
                                     CoopLinkQuality.Snapshot link, String transport,
                                     int cadenceHz, CoopDatagramStats stats) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(stats, "stats");
        long rtt = link.rttMillis() == null ? -1L : link.rttMillis();
        long p95 = link.p95RttMillis() == null ? -1L : link.p95RttMillis();
        return new Message(Type.LINK_STATUS, sessionId, seq, sentAtMillis,
                "{\"rttMillis\":" + rtt
                        + ",\"p95RttMillis\":" + p95
                        + ",\"lossPercent\":" + link.lossPercent()
                        + ",\"udpInboundOk\":\"" + link.udpInboundOk() + "\""
                        + ",\"transport\":\"" + escapeJson(transport == null ? "" : transport) + "\""
                        + ",\"cadenceHz\":" + cadenceHz
                        + ",\"tcpSilenceMillis\":" + link.tcpSilenceMillis()
                        + ",\"droppedTokenMismatch\":" + stats.droppedTokenMismatch()
                        + ",\"droppedForeignSource\":" + stats.droppedForeignSource()
                        + ",\"pathValidations\":" + stats.pathValidations()
                        + ",\"icmpTransients\":" + stats.icmpTransients()
                        + ",\"escalatedToTcp\":" + stats.escalatedToTcp()
                        + ",\"connectionsThrottled\":" + stats.connectionsThrottled()
                        + ",\"invalidFrames\":" + stats.invalidFrames() + "}");
    }

    /** Decoded {@link Type#LINK_STATUS} payload; -1 rtt/p95 mean "the peer had no sample yet". */
    public record LinkStatus(int rttMillis,
                             int p95RttMillis,
                             int lossPercent,
                             boolean udpInboundOk,
                             String transport,
                             long tcpSilenceMillis,
                             long droppedTokenMismatch,
                             long droppedForeignSource,
                             long pathValidations,
                             long icmpTransients,
                             long escalatedToTcp,
                             long connectionsThrottled,
                             long invalidFrames,
                             int cadenceHz) {
        public LinkStatus {
            transport = transport == null ? "" : transport;
        }

        /**
         * Pre-Phase-29-M2 shape. Kept so the many call sites that never cared about the cadence keep
         * compiling; the value they get is the tier every build before M2 sent at.
         */
        public LinkStatus(int rttMillis, int p95RttMillis, int lossPercent, boolean udpInboundOk,
                          String transport, long tcpSilenceMillis, long droppedTokenMismatch,
                          long droppedForeignSource, long pathValidations, long icmpTransients,
                          long escalatedToTcp, long connectionsThrottled, long invalidFrames) {
            this(rttMillis, p95RttMillis, lossPercent, udpInboundOk, transport, tcpSilenceMillis,
                    droppedTokenMismatch, droppedForeignSource, pathValidations, icmpTransients,
                    escalatedToTcp, connectionsThrottled, invalidFrames,
                    CoopCadenceTier.DEFAULT.hz());
        }

        /** The tier the peer announced, refused back to the default when it is not one of ours. */
        public CoopCadenceTier cadenceTier() {
            return CoopCadenceTier.fromHz(cadenceHz);
        }
    }

    public static LinkStatus parseLinkStatus(Message message) {
        return new LinkStatus(
                (int) requiredPayloadLong(message, "rttMillis"),
                (int) requiredPayloadLong(message, "p95RttMillis"),
                (int) requiredPayloadLong(message, "lossPercent"),
                Boolean.parseBoolean(requiredPayloadString(message, "udpInboundOk")),
                requiredPayloadString(message, "transport"),
                requiredPayloadLong(message, "tcpSilenceMillis"),
                requiredPayloadLong(message, "droppedTokenMismatch"),
                requiredPayloadLong(message, "droppedForeignSource"),
                requiredPayloadLong(message, "pathValidations"),
                requiredPayloadLong(message, "icmpTransients"),
                requiredPayloadLong(message, "escalatedToTcp"),
                // Optional: a peer built before 20.4 has no such field, and a link report is not
                // worth throwing away over a counter.
                optionalPayloadLong(message, "connectionsThrottled", 0L),
                optionalPayloadLong(message, "invalidFrames", 0L),
                // Optional for the same reason, and its default is load-bearing rather than cosmetic:
                // a peer built before Phase 29 M2 sends at 10 Hz unconditionally, so "field absent"
                // and "10 Hz" are the same statement about that peer.
                (int) optionalPayloadLong(message, "cadenceHz", CoopCadenceTier.DEFAULT.hz()));
    }

    /**
     * Phase 20.1 UDP-blocked fallback: one composed datagram, carried verbatim on TCP. The payload is
     * deliberately the exact string the UDP path would have sent — the receiver unwraps it and feeds
     * it through the same parse/token/watermark/apply pipeline, so the fallback cannot develop its own
     * subtly different apply semantics.
     */
    public static Message stateDatagram(String sessionId, long seq, long sentAtMillis, String datagram) {
        return new Message(Type.STATE_DATAGRAM, sessionId, seq, sentAtMillis,
                "{\"datagram\":\"" + escapeJson(datagram == null ? "" : datagram) + "\"}");
    }

    /** The composed datagram carried by a {@link Type#STATE_DATAGRAM} message. */
    public static String parseStateDatagram(Message message) {
        return requiredPayloadString(message, "datagram");
    }

    // ---- Phase 20.2: in-session reconnect grace ---------------------------------------------------

    /**
     * Guest &rarr; host on a freshly reconnected socket: "I am still {@code playerId} and I still hold
     * session {@code sessionId}; give it back." Sent <em>instead of</em> {@code LOBBY_HELLO} while the
     * guest's reconnect coordinator is running, which is what keeps the seed lock and the whole
     * lobby/handshake round from being re-run for a two-second blip.
     *
     * <p>The session id travels in the envelope <em>and</em> the payload. The envelope field is what
     * every other message uses and what {@code sessionMatches} reads; the payload copy is what the
     * host's grace check compares, and keeping it explicit means the check cannot silently start
     * passing if the envelope handling ever changes.
     */
    public static Message sessionResumeRequest(String sessionId, long seq, long sentAtMillis,
                                               String playerId) {
        String session = requireText(sessionId, "sessionId");
        return new Message(Type.SESSION_RESUME_REQUEST, session, seq, sentAtMillis,
                "{\"sessionId\":\"" + escapeJson(session) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\"}");
    }

    /** Host &rarr; guest: the resume was accepted; the full session-start rebroadcast follows it. */
    public static Message sessionResumeAccept(String sessionId, long seq, long sentAtMillis) {
        String session = requireText(sessionId, "sessionId");
        return new Message(Type.SESSION_RESUME_ACCEPT, session, seq, sentAtMillis,
                "{\"sessionId\":\"" + escapeJson(session) + "\"}");
    }

    /**
     * Host &rarr; guest: that request does not match the held session. Deliberately not fatal to the
     * <em>host's</em> grace window — a stranger must not be able to end the wait early — but the guest
     * that receives one gives up and runs its ordinary teardown.
     *
     * <p>The session id is nullable here: a reject may be answering a request for a session the host
     * has never heard of, and there is no envelope session to quote back.
     */
    public static Message sessionResumeReject(String sessionId, long seq, long sentAtMillis,
                                              String reason) {
        return new Message(Type.SESSION_RESUME_REJECT, trimToNullable(sessionId), seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\"}");
    }

    private static String trimToNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The {@code sessionId} payload field of a {@code SESSION_RESUME_REQUEST}/{@code _ACCEPT}. */
    public static String parseResumeSessionId(Message message) {
        return requiredPayloadString(message, "sessionId");
    }

    /** The {@code playerId} payload field of a {@code SESSION_RESUME_REQUEST}. */
    public static String parseResumePlayerId(Message message) {
        return requiredPayloadString(message, "playerId");
    }

    /** The reject text, or "" when the sender did not supply one. */
    public static String parseResumeRejectReason(Message message) {
        return optionalPayloadString(message, "reason", "");
    }

    public static Message lobbyHello(long seq, long sentAtMillis, CoopPlayerInfo playerInfo) {
        Objects.requireNonNull(playerInfo, "playerInfo");
        return new Message(Type.LOBBY_HELLO, null, seq, sentAtMillis,
                "{\"playerId\":\"" + escapeJson(playerInfo.playerId()) + "\","
                        + "\"playerName\":\"" + escapeJson(playerInfo.name()) + "\"}");
    }

    /**
     * Phase 20.4: the second hello, answering a {@link Type#LOBBY_CHALLENGE}. The proof field is
     * absent from the first hello on purpose — the guest cannot know whether the host wants a
     * password until it is asked, and a host with no password configured must see byte-identical
     * traffic to the pre-20.4 build.
     */
    public static Message lobbyHello(long seq, long sentAtMillis, CoopPlayerInfo playerInfo, String proof) {
        Objects.requireNonNull(playerInfo, "playerInfo");
        return new Message(Type.LOBBY_HELLO, null, seq, sentAtMillis,
                "{\"playerId\":\"" + escapeJson(playerInfo.playerId()) + "\","
                        + "\"playerName\":\"" + escapeJson(playerInfo.name()) + "\","
                        + "\"proof\":\"" + escapeJson(proof == null ? "" : proof) + "\"}");
    }

    /** Host &rarr; guest password challenge; {@code nonce} is fresh per attempt. */
    public static Message lobbyChallenge(long seq, long sentAtMillis, String nonce) {
        return new Message(Type.LOBBY_CHALLENGE, null, seq, sentAtMillis,
                "{\"nonce\":\"" + escapeJson(requireText(nonce, "nonce")) + "\"}");
    }

    public static String parseLobbyChallengeNonce(Message message) {
        return requiredPayloadString(message, "nonce");
    }

    /** The proof a hello carries, or "" for the first hello (and for every pre-20.4 sender). */
    public static String parseLobbyProof(Message message) {
        return optionalPayloadString(message, "proof", "");
    }

    /**
     * The password proof: full 64-hex SHA-256 of {@code password + nonce}.
     *
     * <p>Full width, unlike {@link #wireToken(String)} — this one rides the wire once per lobby
     * attempt rather than 10 times a second, so there is no budget reason to truncate it, and a
     * proof is exactly the field where truncation is the wrong trade.
     */
    public static String passwordProof(String password, String nonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(((password == null ? "" : password)
                    + (nonce == null ? "" : nonce)).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(b & 0x0f, 16));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive coop password proof", ex);
        }
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

    /**
     * Phase 20 M4: one player's immutable fleet roster ({@link coop.fleet.CoopFleetRoster#encode()}),
     * on reliable TCP because losing it is not a dropped frame of motion — it is a mirror built from
     * the wrong ships. The matching volatile state rides the UDP {@code FLEET_SNAPSHOT} tick and
     * joins to this by {@code fleetHash16}.
     */
    public static Message fleetRoster(String sessionId, long seq, long sentAtMillis,
                                      String encodedRoster) {
        return new Message(Type.FLEET_ROSTER, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"roster\":\"" + escapeJson(encodedRoster == null ? "" : encodedRoster) + "\"}");
    }

    /** The encoded roster carried by a {@link Type#FLEET_ROSTER} message. */
    public static String parseFleetRoster(Message message) {
        return requiredPayloadString(message, "roster");
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

    // ---- Phase 21: lobby / ready-up ---------------------------------------------------------------
    //
    // Both types ride the reliable TCP control plane and are deliberately NOT on the reconnect-grace
    // whitelist: during a grace window the lobby is closed (the reconnect dialog owns that surface),
    // and a peer that has not yet proved it is the partner we are holding the session for has no
    // business writing into the roster. Nothing in the lobby needs them to pass that window.

    /**
     * Phase 21: the guest's own view of its join progress.
     *
     * @param phase the {@code CoopJoinPhase} name; a name this build does not know decodes to the
     *              caller's fallback rather than failing the frame
     * @param ready whether the guest has pressed Ready (revocable at any time before release)
     */
    public static Message readyState(String sessionId, long seq, long sentAtMillis,
                                     String phase, boolean ready) {
        return new Message(Type.READY_STATE, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"phase\":\"" + escapeJson(phase == null ? "" : phase) + "\""
                        + ",\"ready\":\"" + ready + "\"}");
    }

    /** Decoded {@link Type#READY_STATE}. */
    public record ReadyState(String phase, boolean ready) {
        public ReadyState {
            phase = phase == null ? "" : phase;
        }
    }

    public static ReadyState parseReadyState(Message message) {
        Map<String, Object> payload = decodePayload(message);
        return new ReadyState(requiredString(payload, "phase"),
                Boolean.parseBoolean(requiredString(payload, "ready")));
    }

    /**
     * One roster row on the wire.
     *
     * <p>{@code reconnectingMillis} is the reconnect grace <em>remaining</em> for this player, or -1
     * when the player is connected. Remaining rather than elapsed-since-drop so the guest's row
     * counts down to the same instant the host's does; see {@code CoopLobbyRoster.markReconnecting}.
     */
    public record LobbyPlayer(String playerId,
                              String name,
                              String phase,
                              boolean ready,
                              long reconnectingMillis,
                              String reason) {
        public LobbyPlayer {
            playerId = requireText(playerId, "playerId");
            name = name == null ? "" : name;
            phase = phase == null ? "" : phase;
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * Decoded {@link Type#LOBBY_STATUS}.
     *
     * @param countdownRemainingMillis milliseconds left on the start countdown, -1 when none. Sent as
     *                                 a <em>remaining</em> value rather than an absolute deadline
     *                                 precisely because the two wall clocks differ; the guest rebases
     *                                 it onto its own clock on arrival.
     * @param elapsedMillis            how long the lobby has been open, so the guest can tick a
     *                                 counter and "slow" stays distinguishable from "dead"
     */
    public record LobbyStatus(List<LobbyPlayer> players,
                              long countdownRemainingMillis,
                              boolean released,
                              String resetReason,
                              long elapsedMillis) {
        public LobbyStatus {
            players = players == null ? List.of() : List.copyOf(players);
            resetReason = resetReason == null ? "" : resetReason;
        }
    }

    /**
     * Phase 21: the host's authoritative roster. The player list is a delimited blob inside one
     * string field, because the envelope's JSON parser is flat and has no arrays - the same encoding
     * choice every list-bearing coop payload makes.
     */
    public static Message lobbyStatus(String sessionId, long seq, long sentAtMillis,
                                      List<LobbyPlayer> players, long countdownRemainingMillis,
                                      boolean released, String resetReason, long elapsedMillis) {
        return new Message(Type.LOBBY_STATUS, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"players\":\"" + escapeJson(encodeLobbyPlayers(players)) + "\""
                        + ",\"countdownRemainingMillis\":" + countdownRemainingMillis
                        + ",\"released\":\"" + released + "\""
                        + ",\"resetReason\":\"" + escapeJson(resetReason == null ? "" : resetReason) + "\""
                        + ",\"elapsedMillis\":" + elapsedMillis + "}");
    }

    public static LobbyStatus parseLobbyStatus(Message message) {
        Map<String, Object> payload = decodePayload(message);
        Object countdown = payload.get("countdownRemainingMillis");
        Object elapsed = payload.get("elapsedMillis");
        return new LobbyStatus(
                decodeLobbyPlayers(requiredString(payload, "players")),
                countdown instanceof Long value ? value : -1L,
                Boolean.parseBoolean(requiredString(payload, "released")),
                requiredString(payload, "resetReason"),
                elapsed instanceof Long value ? value : 0L);
    }

    static String encodeLobbyPlayers(List<LobbyPlayer> players) {
        if (players == null || players.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (LobbyPlayer player : players) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(coop.campaign.CoopDelimited.field(player.playerId())).append('|')
                    .append(coop.campaign.CoopDelimited.field(player.name())).append('|')
                    .append(coop.campaign.CoopDelimited.field(player.phase())).append('|')
                    .append(player.ready()).append('|')
                    .append(player.reconnectingMillis()).append('|')
                    .append(coop.campaign.CoopDelimited.field(player.reason()));
        }
        return out.toString();
    }

    static List<LobbyPlayer> decodeLobbyPlayers(String encoded) {
        List<LobbyPlayer> players = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return players;
        }
        for (String line : encoded.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            List<String> fields = coop.campaign.CoopDelimited.split(line);
            if (fields.size() < 6 || fields.get(0).isEmpty()) {
                // One malformed row must not cost the whole roster; the rest still renders.
                continue;
            }
            long reconnecting;
            try {
                reconnecting = Long.parseLong(fields.get(4));
            } catch (NumberFormatException ex) {
                reconnecting = -1L;
            }
            players.add(new LobbyPlayer(fields.get(0), fields.get(1), fields.get(2),
                    Boolean.parseBoolean(fields.get(3)), reconnecting, fields.get(5)));
        }
        return players;
    }

    // ---- Phase 28: host policy options -------------------------------------------------------------

    /**
     * Decoded {@link Type#OPTIONS_SNAPSHOT}.
     *
     * @param values        every policy key the host sent, in wire order. Values are raw strings;
     *                      the registry validates them on the way in, so a key this build does not
     *                      know about is simply ignored rather than being able to break the parse.
     * @param policyVersion the host's monotonic counter for this campaign. A guest refuses a
     *                      snapshot older than the one it holds, which is what stops a late
     *                      duplicate after a resume from walking the policy backwards.
     * @param changedKey    the single key whose change caused this send, or "" for the periodic
     *                      establish/resume broadcasts
     */
    public record OptionsSnapshot(Map<String, String> values, int policyVersion, String changedKey) {
        public OptionsSnapshot {
            values = values == null ? Map.of() : Map.copyOf(values);
            changedKey = changedKey == null ? "" : changedKey;
        }
    }

    /**
     * Host &rarr; guest: the whole policy, flat.
     *
     * <p>Flat because the envelope's parser is flat by design and because the payload <em>is</em> a
     * key/value map - there is nothing here that wants a nested shape. The two bookkeeping fields
     * cannot collide with an option: every registry key starts with {@code coop.}, and these two do
     * not.
     */
    public static Message optionsSnapshot(String sessionId, long seq, long sentAtMillis,
                                          Map<String, String> values, int policyVersion,
                                          String changedKey) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                json.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                        .append(escapeJson(entry.getValue() == null ? "" : entry.getValue()))
                        .append("\",");
            }
        }
        json.append("\"policyVersion\":").append(policyVersion).append(',');
        json.append("\"changedKey\":\"")
                .append(escapeJson(changedKey == null ? "" : changedKey)).append("\"}");
        return new Message(Type.OPTIONS_SNAPSHOT, requireText(sessionId, "sessionId"), seq,
                sentAtMillis, json.toString());
    }

    /**
     * Guest &rarr; host: "everything the host has sent me is now in force here, as of version N".
     *
     * <p>One field. The host only ever compares it against its own current version, so naming the
     * keys would be data nobody reads.
     */
    public static Message optionsApplied(String sessionId, long seq, long sentAtMillis,
                                         int policyVersion) {
        return new Message(Type.OPTIONS_APPLIED, requireText(sessionId, "sessionId"), seq,
                sentAtMillis, "{\"policyVersion\":" + policyVersion + "}");
    }

    /** Decoded {@link Type#OPTIONS_APPLIED}. */
    public record OptionsApplied(int policyVersion) {
    }

    public static OptionsApplied parseOptionsApplied(Message message) {
        return new OptionsApplied((int) Math.max(0L,
                requiredPayloadLong(message, "policyVersion")));
    }

    public static OptionsSnapshot parseOptionsSnapshot(Message message) {
        Map<String, Object> payload = decodePayload(message);
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith("coop.")) {
                continue;
            }
            Object value = entry.getValue();
            values.put(key, value == null ? "" : String.valueOf(value));
        }
        Object version = payload.get("policyVersion");
        int policyVersion = version instanceof Long value ? (int) Math.max(0L, (long) value) : 0;
        Object changed = payload.get("changedKey");
        return new OptionsSnapshot(values, policyVersion,
                changed instanceof String text ? text : "");
    }

    // ---- Session stats + ship-loss reporting -------------------------------------------------------

    /**
     * Host &rarr; guest: the whole {@code CoopSessionStats} tally, low rate, cosmetic. {@code
     * payloadJson} must be exactly what {@link coop.stats.CoopSessionStatsCodec#encodePayload}
     * produced and is carried <b>verbatim, not re-wrapped or re-escaped</b> — the codec owns the
     * payload's shape (it is already the complete flat JSON object this message's payload is), and
     * running it through another layer of {@code escapeJson} is the classic double-escaping bug: the
     * codec's own {@code body} field would come back with every backslash doubled.
     *
     * <p>A null or blank payload becomes {@code "{}"} instead of throwing: a stats message is
     * cosmetic (session-end tallies, not gameplay state), and it must never be able to fail a send or
     * kill a session over a formatting slip.
     *
     * <p>Deliberately <b>not</b> on the reconnect-grace whitelist
     * ({@code CoopNetPump#allowedDuringReconnectGrace}): during a grace window the peer on the other
     * end has not yet proved it is the partner the session is held for, and a cosmetic tally is not
     * worth opening a channel an unproven peer can speak on.
     */
    public static Message sessionStats(String sessionId, long seq, long sentAtMillis, String payloadJson) {
        String session = requireText(sessionId, "sessionId");
        String payload = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        return new Message(Type.SESSION_STATS, session, seq, sentAtMillis, payload);
    }

    /**
     * Guest &rarr; host: one hull the guest lost, so the host's {@code CoopSessionStats} — the tally
     * owner — can fold it in via {@code noteShipLost}. One message per hull lost; the guest reports
     * rather than tallies because the host is the single source of truth for the shared session
     * stats, the same rule {@code GUEST_REP_DELTA} follows for reputation.
     */
    public static Message shipLost(String sessionId, long seq, long sentAtMillis, String playerId,
                                   String hullName, String hullClass, String systemName, float day,
                                   String cause) {
        return new Message(Type.SHIP_LOST, requireText(sessionId, "sessionId"), seq, sentAtMillis,
                "{\"playerId\":\"" + escapeJson(playerId == null ? "" : playerId) + "\","
                        + "\"hullName\":\"" + escapeJson(hullName == null ? "" : hullName) + "\","
                        + "\"hullClass\":\"" + escapeJson(hullClass == null ? "" : hullClass) + "\","
                        + "\"systemName\":\"" + escapeJson(systemName == null ? "" : systemName) + "\","
                        + "\"day\":\"" + day + "\","
                        + "\"cause\":\"" + escapeJson(cause == null ? "" : cause) + "\"}");
    }

    /** Decoded {@link Type#SHIP_LOST}; a malformed {@code day} falls back to 0f rather than throwing. */
    public record ShipLost(String playerId, String hullName, String hullClass, String systemName,
                           float day, String cause) {
        public ShipLost {
            playerId = playerId == null ? "" : playerId;
            hullName = hullName == null ? "" : hullName;
            hullClass = hullClass == null ? "" : hullClass;
            systemName = systemName == null ? "" : systemName;
            cause = cause == null ? "" : cause;
        }
    }

    public static ShipLost parseShipLost(Message message) {
        float day;
        try {
            day = requiredPayloadFloat(message, "day");
        } catch (RuntimeException ex) {
            day = 0f;
        }
        return new ShipLost(
                optionalPayloadString(message, "playerId", ""),
                optionalPayloadString(message, "hullName", ""),
                optionalPayloadString(message, "hullClass", ""),
                optionalPayloadString(message, "systemName", ""),
                day,
                optionalPayloadString(message, "cause", ""));
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

    /**
     * UDP datagram envelope for high-frequency state streams (Phase 8 fleet snapshots, later combat
     * snapshots). Unlike the TCP {@link Message} line protocol this is not JSON: each section body
     * carries its own compact encoding (e.g. {@link coop.fleet.CoopFleetSnapshot#encode()}), and the
     * envelope is fields joined by a unit-separator that never appears in those encodings. Datagrams
     * are framed one-per-packet by UDP itself.
     *
     * <p><b>Wire shape (Phase 20.1/20.5):</b> {@code token}, {@code senderId}, {@code TYPE}, then per
     * section {@code epoch}, {@code sentGameTimeMillis}, {@code chunk}, {@code body} — all
     * unit-separator joined, token count {@code 3 + 4n}.
     *
     * <ul>
     *   <li>{@code token} is {@link #wireToken(String)} of the session id, not the UUID: this field
     *       rides every packet at 10 Hz and only has to answer "is this ours?".</li>
     *   <li>{@code senderId} is {@link #wireToken(String)} of the sending player id. It exists so the
     *       receiver's watermark can be keyed per sender — with one guest that is invisible, with
     *       three it is the difference between a working stream and one guest's epochs silently
     *       censoring another's.</li>
     *   <li>Sections carry the sender's monotonic {@code epoch} and stream time
     *       ({@link CoopStreamClock}), oldest first. The current send plus the previous one ride in
     *       the same packet ({@link CoopDatagramRedundancy}), so a single lost datagram costs nothing.
     *       The receiver drops sections at or below its {@code (senderId, type)} epoch watermark
     *       ({@link CoopDatagramWatermark}), which is also what makes a reordered datagram inert
     *       instead of a stale-position apply.</li>
     *   <li>{@code chunk} is 0 everywhere today. It is on the wire now because Phase 20 M4 splits an
     *       oversized batch into self-contained chunk datagrams sharing one epoch, and adding a field
     *       to a shipped envelope is the expensive kind of change.</li>
     * </ul>
     */
    public record Datagram(String token, String senderId, Type type, List<DatagramSection> sections) {
        public Datagram {
            type = Objects.requireNonNull(type, "type");
            token = token == null ? "" : token;
            senderId = senderId == null ? "" : senderId;
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /**
     * One stamped body within a {@link Datagram}; {@code sentGameTimeMillis} is sender stream time and
     * {@code chunk} is the 0-based index of this piece within its epoch (0 until Phase 20 M4 chunks).
     */
    public record DatagramSection(long epoch, long sentGameTimeMillis, int chunk, String body) {
        public DatagramSection {
            body = body == null ? "" : body;
        }

        /** Unchunked section — everything today. */
        public DatagramSection(long epoch, long sentGameTimeMillis, String body) {
            this(epoch, sentGameTimeMillis, 0, body);
        }
    }

    /**
     * Envelope prefix only: what the transport needs to decide whether a datagram is ours, before any
     * body is parsed and before anything can be learned from the packet. Kept separate from
     * {@link #parseDatagram} because {@link CoopNetService} runs this on every inbound packet
     * including hostile ones, and parsing N section bodies to answer "wrong session" is work an
     * attacker would get to choose the size of.
     */
    public record DatagramHeader(String token, String senderId, Type type, long epoch, int chunk) {
        public DatagramHeader {
            Objects.requireNonNull(type, "type");
            token = token == null ? "" : token;
            senderId = senderId == null ? "" : senderId;
        }

        /** Header without the first section's stamps; kept so older call sites read unchanged. */
        public DatagramHeader(String token, String senderId, Type type) {
            this(token, senderId, type, 0L, 0);
        }
    }

    private static final char DATAGRAM_SEPARATOR = '\u001f';
    /** Envelope fields before the first section: token, senderId, type. */
    private static final int DATAGRAM_HEADER_TOKENS = 3;
    /** Fields per section: epoch, sentGameTimeMillis, chunk, body. */
    private static final int DATAGRAM_SECTION_TOKENS = 4;
    /**
     * Chunk indices a datagram may claim, exclusive upper bound (red-team A5). Producers split a
     * batch into at most a handful of MTU-sized chunks, and the receiver keys per-chunk state on this
     * number - so an unbounded chunk id is an unbounded map. 64 is far above anything the packer can
     * emit and small enough that a crafted packet buys nothing.
     */
    public static final int MAX_DATAGRAM_CHUNKS = 64;
    /**
     * Sections one datagram may carry (red-team A5). Redundancy composes exactly two - previous plus
     * current - so anything past a handful is a crafted packet asking the receiver to do work per
     * section at a size the sender chose.
     */
    public static final int MAX_DATAGRAM_SECTIONS = 8;

    public static String datagram(String token, String senderId, Type type,
                                  List<DatagramSection> sections) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sections, "sections");
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("Datagram needs at least one section");
        }
        StringBuilder out = new StringBuilder(48 + sections.size() * 32);
        out.append(token == null ? "" : token).append(DATAGRAM_SEPARATOR)
                .append(senderId == null ? "" : senderId).append(DATAGRAM_SEPARATOR)
                .append(type.name());
        for (DatagramSection section : sections) {
            out.append(DATAGRAM_SEPARATOR).append(section.epoch())
                    .append(DATAGRAM_SEPARATOR).append(section.sentGameTimeMillis())
                    .append(DATAGRAM_SEPARATOR).append(section.chunk())
                    .append(DATAGRAM_SEPARATOR).append(section.body());
        }
        return out.toString();
    }

    /**
     * The UTF-8 byte length {@link #datagram(String, String, Type, List)} would produce, computed
     * arithmetically instead of by building the string (Phase 20 M4).
     *
     * <p>The chunk packer asks "does this record still fit" once per record per tick, and answering
     * it by composing the whole datagram again is quadratic in the batch size at 10 Hz. This is exact
     * rather than an estimate — the budget is a hard cap, and an estimate that is one byte optimistic
     * is a fragmented packet.
     */
    public static int datagramBytes(String token, String senderId, Type type,
                                    List<DatagramSection> sections) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sections, "sections");
        int bytes = utf8Length(token) + 1 + utf8Length(senderId) + 1 + type.name().length();
        for (DatagramSection section : sections) {
            bytes += 4
                    + Long.toString(section.epoch()).length()
                    + Long.toString(section.sentGameTimeMillis()).length()
                    + Integer.toString(section.chunk()).length()
                    + utf8Length(section.body());
        }
        return bytes;
    }

    /** UTF-8 byte length without allocating the array; the packer calls this per candidate record. */
    public static int utf8Length(String text) {
        if (text == null) {
            return 0;
        }
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** Single-section convenience for chunk 0 (every call site today). */
    public static String datagram(String token, String senderId, Type type, long epoch,
                                  long sentGameTimeMillis, String body) {
        return datagram(token, senderId, type, epoch, sentGameTimeMillis, 0, body);
    }

    /** Single-section convenience with an explicit chunk index. */
    public static String datagram(String token, String senderId, Type type, long epoch,
                                  long sentGameTimeMillis, int chunk, String body) {
        return datagram(token, senderId, type,
                List.of(new DatagramSection(epoch, sentGameTimeMillis, chunk, body)));
    }

    public static Datagram parseDatagram(String raw) {
        Objects.requireNonNull(raw, "raw");
        String[] tokens = splitDatagram(raw);
        if (tokens.length < DATAGRAM_HEADER_TOKENS + DATAGRAM_SECTION_TOKENS
                || (tokens.length - DATAGRAM_HEADER_TOKENS) % DATAGRAM_SECTION_TOKENS != 0) {
            throw new IllegalArgumentException("Malformed coop datagram envelope");
        }
        String token = tokens[0];
        String senderId = tokens[1];
        Type type = Type.valueOf(tokens[2]);
        int sectionCount = (tokens.length - DATAGRAM_HEADER_TOKENS) / DATAGRAM_SECTION_TOKENS;
        if (sectionCount > MAX_DATAGRAM_SECTIONS) {
            // Red-team A5: the section count is attacker-chosen and every section is per-packet work
            // the receiver does. Nothing this transport composes exceeds two.
            throw new IllegalArgumentException("Coop datagram carries " + sectionCount
                    + " sections, cap " + MAX_DATAGRAM_SECTIONS);
        }
        List<DatagramSection> sections = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            int base = DATAGRAM_HEADER_TOKENS + i * DATAGRAM_SECTION_TOKENS;
            int chunk;
            try {
                chunk = Integer.parseInt(tokens[base + 2]);
                sections.add(new DatagramSection(Long.parseLong(tokens[base]),
                        Long.parseLong(tokens[base + 1]), chunk, tokens[base + 3]));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Malformed coop datagram section stamp", ex);
            }
            // Red-team A5: the chunk index keys receiver-side per-chunk state, so it is bounded at
            // the one point every inbound datagram passes through.
            if (chunk < 0 || chunk >= MAX_DATAGRAM_CHUNKS) {
                throw new IllegalArgumentException("Coop datagram section chunk out of range: " + chunk);
            }
        }
        return new Datagram(token, senderId, type, sections);
    }

    /**
     * Reads the envelope prefix and nothing else. Throws {@link IllegalArgumentException} — and only
     * that — for anything malformed, so the transport's inbound filter has exactly one failure mode
     * to classify and can never be knocked over by a crafted packet.
     */
    public static DatagramHeader parseDatagramHeader(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Missing coop datagram");
        }
        int first = raw.indexOf(DATAGRAM_SEPARATOR);
        int second = first < 0 ? -1 : raw.indexOf(DATAGRAM_SEPARATOR, first + 1);
        int third = second < 0 ? -1 : raw.indexOf(DATAGRAM_SEPARATOR, second + 1);
        if (third < 0) {
            // No third separator means no section follows, which is not a datagram this transport
            // emits — reject it here rather than let a header-only packet through the filter.
            throw new IllegalArgumentException("Malformed coop datagram envelope");
        }
        // The first section's epoch and chunk are part of the prefix (red-team C2/A7): the TCP
        // fallback's coalescing key needs the chunk or it censors every chunk but the last, and the
        // transport's epoch sanity check needs the stamp before any body is looked at. Three more
        // separator scans and two integer parses - still nothing an attacker chooses the size of.
        int fourth = raw.indexOf(DATAGRAM_SEPARATOR, third + 1);
        int fifth = fourth < 0 ? -1 : raw.indexOf(DATAGRAM_SEPARATOR, fourth + 1);
        int sixth = fifth < 0 ? -1 : raw.indexOf(DATAGRAM_SEPARATOR, fifth + 1);
        if (sixth < 0) {
            throw new IllegalArgumentException("Malformed coop datagram envelope");
        }
        long epoch;
        int chunk;
        try {
            epoch = Long.parseLong(raw.substring(third + 1, fourth));
            chunk = Integer.parseInt(raw.substring(fifth + 1, sixth));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed coop datagram section stamp", ex);
        }
        String typeName = raw.substring(second + 1, third);
        try {
            return new DatagramHeader(raw.substring(0, first), raw.substring(first + 1, second),
                    Type.valueOf(typeName), epoch, chunk);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown coop datagram type: " + typeName, ex);
        }
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
        json.append("\"payloadJson\":\"").append(escapeJson(message.payloadJson())).append("\",");
        json.append("\"senderId\":");
        appendNullableString(json, message.senderId());
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
        // Tolerant: a message from a factory that never stamps a sender, or from before the
        // field existed, decodes to null rather than failing the whole frame.
        String senderId = nullableString(fields, "senderId");
        return new Message(type, sessionId, seq, sentAtMillis, payloadJson, senderId);
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

    /** Long counterpart of {@link #optionalPayloadString}, for counters added to shipped messages. */
    public static long optionalPayloadLong(Message message, String name, long fallback) {
        Object value = decodePayload(message).get(name);
        return value instanceof Long longValue ? longValue : fallback;
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

    // ---- Phase 20 red-team additions -------------------------------------------------------------
    // Deliberately at the end of the file rather than beside their thematic neighbours: this block
    // was written on a branch that runs alongside a transport rewrite of the same file, and a purely
    // appended block is the one shape that cannot collide with edits made higher up.

    /**
     * Both roles: "this process is about to stop pumping for {@code expectedMillis}, because
     * {@code reason}." Flushed inline by the sender, so it is on the wire before the stall starts.
     *
     * <p>{@code expectedMillis} is advisory — a save takes as long as it takes — and the receiver
     * uses a fixed exemption window rather than trusting it, so a hostile value buys nothing.
     */
    public static Message stallNotice(String sessionId, long seq, long sentAtMillis,
                                      String reason, long expectedMillis) {
        return new Message(Type.STALL_NOTICE, trimToNullable(sessionId), seq, sentAtMillis,
                "{\"reason\":\"" + escapeJson(reason == null ? "" : reason) + "\","
                        + "\"expectedMillis\":" + Math.max(0L, expectedMillis) + "}");
    }

    /** Why the peer is about to go quiet, or "" when it did not say. */
    public static String parseStallReason(Message message) {
        return optionalPayloadString(message, "reason", "");
    }

    /** The peer's own estimate of the stall, in wall-clock milliseconds; 0 when absent or negative. */
    public static long parseStallExpectedMillis(Message message) {
        Object value = decodePayload(message).get("expectedMillis");
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    /** "Re-send me your fleet roster." Carries nothing: there is exactly one roster per peer in v1. */
    public static Message fleetRosterRequest(String sessionId, long seq, long sentAtMillis) {
        return new Message(Type.FLEET_ROSTER_REQUEST, trimToNullable(sessionId), seq, sentAtMillis,
                "{}");
    }

    /**
     * The proof-carrying second {@code SESSION_RESUME_REQUEST} (red-team A2). A resume otherwise
     * bypasses the lobby password entirely: session id and player id travel in cleartext, so a
     * captured request replays. The host answers the first request with the ordinary
     * {@link Type#LOBBY_CHALLENGE} and accepts only a request whose proof matches the nonce it
     * issued — the same machinery {@code LOBBY_HELLO} already uses, so there is one password
     * protocol rather than two.
     *
     * <p>A host with no password never challenges, and the wire is byte-identical to the four-field
     * request above.
     */
    public static Message sessionResumeRequest(String sessionId, long seq, long sentAtMillis,
                                               String playerId, String proof) {
        String session = requireText(sessionId, "sessionId");
        return new Message(Type.SESSION_RESUME_REQUEST, session, seq, sentAtMillis,
                "{\"sessionId\":\"" + escapeJson(session) + "\","
                        + "\"playerId\":\"" + escapeJson(requireText(playerId, "playerId")) + "\","
                        + "\"proof\":\"" + escapeJson(proof == null ? "" : proof) + "\"}");
    }

    /** The proof a resume request carries, or "" for the first (and for any pre-fix sender). */
    public static String parseResumeProof(Message message) {
        return optionalPayloadString(message, "proof", "");
    }
}
