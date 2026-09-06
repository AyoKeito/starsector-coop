package coop.net;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every {@link CoopMessages.Type} against the hand-written policy tables in
 * {@link CoopNetService} and {@link CoopNetPump}. Each table's javadoc says a new message type
 * must be argued onto it rather than inherit a pass from a {@code default} branch, but nothing in
 * the enum or the switch machinery enforces that - a type added to {@code Type} and left out of
 * every table silently falls into whatever the {@code default} (or, for {@code
 * survivesTheDropEdge}'s exhaustive switch, the compiler) happens to do with it.
 *
 * <p>{@link #ALL_KNOWN_TYPES} is this test's own registry, built by hand from the current enum.
 * {@link #everyTypeIsClassified()} fails the moment {@code Type.values()} grows a constant this
 * registry does not know about, naming it and pointing at the tables below. The remaining tests
 * assert that each table's answer for every known type matches what the table returns today, so
 * the pin breaks (rather than silently drifting) if a table's behaviour ever changes.
 */
class CoopMessageTypePolicyTest {

    // ---- registry: every Type this test has an opinion about --------------------------------
    // Deliberately hand-written, not EnumSet.allOf(...): allOf would auto-include a newly added
    // constant and defeat the point. A new Type must be added here by hand before it can pass.
    private static final List<CoopMessages.Type> ALL_KNOWN_TYPES = List.of(
            CoopMessages.Type.LOBBY_HELLO, CoopMessages.Type.LOBBY_CHALLENGE,
            CoopMessages.Type.LOBBY_ACCEPT, CoopMessages.Type.LOBBY_REJECT,
            CoopMessages.Type.HANDSHAKE_MANIFEST, CoopMessages.Type.HANDSHAKE_RESULT,
            CoopMessages.Type.SEED_LOCK_REQUEST, CoopMessages.Type.SEED_LOCK_ACK,
            CoopMessages.Type.SEED_LOCK_REJECT, CoopMessages.Type.TIME_SNAPSHOT,
            CoopMessages.Type.PAUSE_INTENT, CoopMessages.Type.FLEET_SNAPSHOT,
            CoopMessages.Type.FLEET_ROSTER, CoopMessages.Type.INTERACTION_CLAIM,
            CoopMessages.Type.INTERACTION_ACCEPT, CoopMessages.Type.INTERACTION_REJECT,
            CoopMessages.Type.INTERACTION_RELEASE, CoopMessages.Type.REP_DELTA,
            CoopMessages.Type.GUEST_REP_DELTA, CoopMessages.Type.PLAYER_REP_SNAPSHOT,
            CoopMessages.Type.FACTION_REL_DELTA, CoopMessages.Type.MISSION_POOL_SNAPSHOT,
            CoopMessages.Type.MISSION_CLAIM_REQUEST, CoopMessages.Type.MISSION_CLAIM_ACCEPT,
            CoopMessages.Type.MISSION_CLAIM_REJECT, CoopMessages.Type.MARKET_OPEN,
            CoopMessages.Type.MARKET_SNAPSHOT, CoopMessages.Type.MARKET_TXN,
            CoopMessages.Type.WORLD_DELTA, CoopMessages.Type.RAID_RESULT,
            CoopMessages.Type.COLONY_FOUNDED, CoopMessages.Type.COLONY_ABANDONED,
            CoopMessages.Type.COLONY_MGMT, CoopMessages.Type.COLONY_INCOME,
            CoopMessages.Type.EXPEDITION_WARNING, CoopMessages.Type.ABILITY_ACTIVATE,
            CoopMessages.Type.ORBIT_SNAPSHOT, CoopMessages.Type.NPC_FLEET_SET,
            CoopMessages.Type.NPC_FLEET_MOTION, CoopMessages.Type.BASE_SET,
            CoopMessages.Type.BATTLE_BEGIN, CoopMessages.Type.BATTLE_STATUS,
            CoopMessages.Type.BATTLE_END, CoopMessages.Type.BATTLE_RESULT,
            CoopMessages.Type.ENGAGE_GUEST, CoopMessages.Type.DIALOG_BEGIN,
            CoopMessages.Type.GUEST_SNAPSHOT, CoopMessages.Type.SAVE_CHECKPOINT,
            CoopMessages.Type.RESPAWN_PLAYER, CoopMessages.Type.PING, CoopMessages.Type.PONG,
            CoopMessages.Type.LINK_STATUS, CoopMessages.Type.STATE_DATAGRAM,
            CoopMessages.Type.SESSION_RESUME_REQUEST, CoopMessages.Type.SESSION_RESUME_ACCEPT,
            CoopMessages.Type.SESSION_RESUME_REJECT, CoopMessages.Type.UDP_PROBE,
            CoopMessages.Type.PATH_PROBE, CoopMessages.Type.STALL_NOTICE,
            CoopMessages.Type.FLEET_ROSTER_REQUEST, CoopMessages.Type.READY_STATE,
            CoopMessages.Type.LOBBY_STATUS, CoopMessages.Type.SESSION_STATS,
            CoopMessages.Type.SHIP_LOST, CoopMessages.Type.OPTIONS_SNAPSHOT,
            CoopMessages.Type.OPTIONS_APPLIED, CoopMessages.Type.CREDITS_GRANT,
            CoopMessages.Type.RELIABLE_ACK, CoopMessages.Type.SAVE_CHECKPOINT_RESULT,
            CoopMessages.Type.SESSION_LEAVE);

    // ---- table: CoopNetService.coalesceKey(Message) ------------------------------------------
    // Whitelist of whole-state snapshots that may supersede a queued copy of themselves; every
    // other type keys as null and is never coalesced. STATE_DATAGRAM keys on the wrapped
    // datagram's contents rather than its own type; a message built with an empty payload is an
    // unparseable wrapper and, per its javadoc, must key as null too.
    private static final EnumSet<CoopMessages.Type> COALESCED = EnumSet.of(
            CoopMessages.Type.TIME_SNAPSHOT, CoopMessages.Type.NPC_FLEET_SET,
            CoopMessages.Type.PLAYER_REP_SNAPSHOT, CoopMessages.Type.MISSION_POOL_SNAPSHOT,
            CoopMessages.Type.LINK_STATUS);

    // ---- table: CoopNetService.isConnectionScopedControl(Type) -------------------------------
    // The lobby round and the verdicts that end it: meaning is scoped to the TCP connection that
    // carried it, not to the peer slot, so a copy left over from a dead socket answers nothing.
    private static final EnumSet<CoopMessages.Type> CONNECTION_SCOPED_CONTROL = EnumSet.of(
            CoopMessages.Type.LOBBY_HELLO, CoopMessages.Type.LOBBY_CHALLENGE,
            CoopMessages.Type.LOBBY_ACCEPT, CoopMessages.Type.LOBBY_REJECT,
            CoopMessages.Type.HANDSHAKE_MANIFEST, CoopMessages.Type.HANDSHAKE_RESULT,
            CoopMessages.Type.SESSION_RESUME_REQUEST, CoopMessages.Type.SESSION_RESUME_ACCEPT,
            CoopMessages.Type.SESSION_RESUME_REJECT,
            // 0.1.1: not lobby vocabulary, same scope. An ack queued for a socket that died proves
            // nothing to its replacement, and losing it costs one redundant resend.
            CoopMessages.Type.RELIABLE_ACK,
            // 0.1.1: same again. A leave queued onto a link that then died would, on the socket that
            // replaces it, end the very session the partner just came back to.
            CoopMessages.Type.SESSION_LEAVE);

    /**
     * The lobby round proper: {@link #CONNECTION_SCOPED_CONTROL} minus the two 0.1.1 types. The two
     * cross-table rules at the bottom of this file were written when those two sets were the same
     * one, and they are rules about the <em>lobby vocabulary</em> - "a leftover copy answers a
     * question the new peer never asked, and it is pre-session control-plane chatter". Neither claim
     * is true of an acknowledgement or of a departure notice, which is why they are subtracted here
     * rather than the rules being weakened for everybody.
     */
    private static final EnumSet<CoopMessages.Type> CONNECTION_SCOPED_LOBBY_VOCABULARY =
            lobbyVocabulary();

    private static EnumSet<CoopMessages.Type> lobbyVocabulary() {
        EnumSet<CoopMessages.Type> vocabulary = EnumSet.copyOf(CONNECTION_SCOPED_CONTROL);
        vocabulary.remove(CoopMessages.Type.RELIABLE_ACK);
        vocabulary.remove(CoopMessages.Type.SESSION_LEAVE);
        return vocabulary;
    }

    // ---- table: CoopMessages.isReliableOneShot(Type) ------------------------------------------
    // 0.1.1: campaign events with no producer that would ever send them again, so the transport
    // acknowledges and replays them across a socket replacement. Everything else is either resent
    // by its producer, scoped to a connection or a moment, or version-based.
    private static final EnumSet<CoopMessages.Type> RELIABLE_ONE_SHOT = EnumSet.of(
            CoopMessages.Type.MARKET_TXN, CoopMessages.Type.CREDITS_GRANT,
            CoopMessages.Type.WORLD_DELTA, CoopMessages.Type.RAID_RESULT,
            CoopMessages.Type.SHIP_LOST, CoopMessages.Type.COLONY_FOUNDED,
            CoopMessages.Type.COLONY_ABANDONED, CoopMessages.Type.COLONY_MGMT,
            CoopMessages.Type.REP_DELTA, CoopMessages.Type.GUEST_REP_DELTA,
            CoopMessages.Type.FACTION_REL_DELTA);

    // ---- table: CoopNetPump.allowedDuringReconnectGrace(Type) --------------------------------
    // The only vocabulary an unproven peer may speak while a reconnect grace window is open: the
    // resume exchange, a lobby hello (a relaunched partner's only possible utterance), the
    // password challenge that can answer it, and the ping/pong heartbeat pair.
    private static final EnumSet<CoopMessages.Type> ALLOWED_DURING_RECONNECT_GRACE = EnumSet.of(
            CoopMessages.Type.SESSION_RESUME_REQUEST, CoopMessages.Type.SESSION_RESUME_ACCEPT,
            CoopMessages.Type.SESSION_RESUME_REJECT, CoopMessages.Type.LOBBY_HELLO,
            CoopMessages.Type.LOBBY_CHALLENGE, CoopMessages.Type.PING, CoopMessages.Type.PONG);

    // ---- table: CoopNetPump.survivesTheDropEdge(Type) ----------------------------------------
    // Exhaustive switch, no default: of messages parked before a drop, which still apply once the
    // drop edge has run. True = campaign deltas/events/snapshots that describe the world and stay
    // true after the drop. False = scoped to the connection or interaction state the drop edge
    // just tore down and reset.
    private static final EnumSet<CoopMessages.Type> SURVIVES_DROP_EDGE = EnumSet.of(
            CoopMessages.Type.WORLD_DELTA, CoopMessages.Type.MARKET_OPEN,
            CoopMessages.Type.MARKET_SNAPSHOT, CoopMessages.Type.MARKET_TXN,
            CoopMessages.Type.RAID_RESULT, CoopMessages.Type.SHIP_LOST,
            CoopMessages.Type.COLONY_FOUNDED, CoopMessages.Type.COLONY_ABANDONED,
            CoopMessages.Type.COLONY_MGMT, CoopMessages.Type.COLONY_INCOME,
            CoopMessages.Type.EXPEDITION_WARNING, CoopMessages.Type.MISSION_POOL_SNAPSHOT,
            CoopMessages.Type.MISSION_CLAIM_REQUEST, CoopMessages.Type.MISSION_CLAIM_ACCEPT,
            CoopMessages.Type.MISSION_CLAIM_REJECT, CoopMessages.Type.REP_DELTA,
            CoopMessages.Type.GUEST_REP_DELTA, CoopMessages.Type.PLAYER_REP_SNAPSHOT,
            CoopMessages.Type.FACTION_REL_DELTA, CoopMessages.Type.ABILITY_ACTIVATE,
            CoopMessages.Type.BATTLE_BEGIN, CoopMessages.Type.BATTLE_STATUS,
            CoopMessages.Type.BATTLE_END, CoopMessages.Type.BATTLE_RESULT,
            CoopMessages.Type.ENGAGE_GUEST, CoopMessages.Type.SAVE_CHECKPOINT,
            CoopMessages.Type.RESPAWN_PLAYER, CoopMessages.Type.STALL_NOTICE,
            CoopMessages.Type.ORBIT_SNAPSHOT, CoopMessages.Type.NPC_FLEET_SET,
            CoopMessages.Type.NPC_FLEET_MOTION, CoopMessages.Type.BASE_SET,
            CoopMessages.Type.FLEET_SNAPSHOT, CoopMessages.Type.FLEET_ROSTER,
            CoopMessages.Type.GUEST_SNAPSHOT, CoopMessages.Type.SESSION_STATS,
            CoopMessages.Type.STATE_DATAGRAM, CoopMessages.Type.TIME_SNAPSHOT,
            CoopMessages.Type.PAUSE_INTENT, CoopMessages.Type.OPTIONS_SNAPSHOT,
            CoopMessages.Type.OPTIONS_APPLIED,
            // Phase 32 addition B: the sender debited itself before this was queued, so dropping it
            // on the drop edge would destroy the money. Reliable TCP either side of the edge, and
            // the receiver's grant ledger absorbs a duplicate.
            CoopMessages.Type.CREDITS_GRANT,
            // 0.1.1: a pre-drop ack is the partner saying it applied those seqs, which is as true
            // after the drop as before it, and honouring it saves a needless resend.
            CoopMessages.Type.RELIABLE_ACK,
            // 0.1.1: informational, and its subject (whether the guest's save happened) outlives the
            // socket that carried the answer.
            CoopMessages.Type.SAVE_CHECKPOINT_RESULT,
            // 0.1.1: the whole point. A leave written a frame before the socket died, read after the
            // grace window opened, is what turns a 60 s hold into an immediate, explained ending.
            CoopMessages.Type.SESSION_LEAVE);

    // ---- table: CoopNetPump.isTerminalRejectType(Type) ---------------------------------------
    // The peer's verdicts on a join, dispatched a few lines early out of the pre-drop drain so
    // they land before the drop edge rewinds the round they are the answer to.
    private static final EnumSet<CoopMessages.Type> TERMINAL_REJECT = EnumSet.of(
            CoopMessages.Type.SEED_LOCK_REJECT, CoopMessages.Type.HANDSHAKE_RESULT);

    // ---- table: CoopNetPump.isControlPlane(Type) ---------------------------------------------
    // Lobby / handshake / seed lock / resume: the pre-session control plane, the only traffic
    // logged at INFO before a peer is accepted.
    private static final EnumSet<CoopMessages.Type> CONTROL_PLANE = EnumSet.of(
            CoopMessages.Type.LOBBY_HELLO, CoopMessages.Type.LOBBY_CHALLENGE,
            CoopMessages.Type.LOBBY_ACCEPT, CoopMessages.Type.LOBBY_REJECT,
            CoopMessages.Type.HANDSHAKE_MANIFEST, CoopMessages.Type.HANDSHAKE_RESULT,
            CoopMessages.Type.SEED_LOCK_REQUEST, CoopMessages.Type.SEED_LOCK_ACK,
            CoopMessages.Type.SEED_LOCK_REJECT, CoopMessages.Type.SESSION_RESUME_REQUEST,
            CoopMessages.Type.SESSION_RESUME_ACCEPT, CoopMessages.Type.SESSION_RESUME_REJECT);

    // ---- table: CoopNetPump.isHighFrequency(Type) --------------------------------------------
    // Heartbeat/state traffic logged at DEBUG once a session is live, rather than at INFO.
    private static final EnumSet<CoopMessages.Type> HIGH_FREQUENCY = EnumSet.of(
            CoopMessages.Type.PING, CoopMessages.Type.PONG, CoopMessages.Type.LINK_STATUS,
            CoopMessages.Type.STATE_DATAGRAM, CoopMessages.Type.TIME_SNAPSHOT,
            CoopMessages.Type.FLEET_SNAPSHOT, CoopMessages.Type.NPC_FLEET_MOTION,
            CoopMessages.Type.BATTLE_STATUS);

    private static CoopMessages.Message message(CoopMessages.Type type) {
        return new CoopMessages.Message(type, "session-a", 1L, 0L, "{}");
    }

    /**
     * The enforcement point: fails the moment {@code Type.values()} outgrows {@link
     * #ALL_KNOWN_TYPES}, which is the only place in this test a new constant must be added before
     * it can be classified onto any of the tables below.
     */
    @Test
    void everyTypeIsClassified() {
        assertEquals(EnumSet.allOf(CoopMessages.Type.class), EnumSet.copyOf(ALL_KNOWN_TYPES),
                "A CoopMessages.Type constant is missing from CoopMessageTypePolicyTest.ALL_KNOWN_TYPES. "
                        + "It must be argued onto: CoopNetService.coalesceKey, "
                        + "CoopNetService.isConnectionScopedControl, "
                        + "CoopNetPump.allowedDuringReconnectGrace, CoopNetPump.survivesTheDropEdge, "
                        + "CoopNetPump.isTerminalRejectType, CoopNetPump.isControlPlane, and "
                        + "CoopNetPump.isHighFrequency before this test can pass.");
    }

    @Test
    void coalesceKeyMatchesWhitelist() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            String key = CoopNetService.coalesceKey(message(type));
            if (COALESCED.contains(type)) {
                assertNotNull(key, type + " is on the coalesce whitelist and should key non-null");
            } else {
                assertNull(key, type + " is not on the coalesce whitelist and should key null");
            }
        }
    }

    @Test
    void connectionScopedControlMatchesWhitelist() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(CONNECTION_SCOPED_CONTROL.contains(type),
                    CoopNetService.isConnectionScopedControl(type), type.name());
        }
    }

    @Test
    void allowedDuringReconnectGraceMatchesWhitelist() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(ALLOWED_DURING_RECONNECT_GRACE.contains(type),
                    CoopNetPump.allowedDuringReconnectGrace(type), type.name());
        }
    }

    @Test
    void survivesTheDropEdgeMatchesTable() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(SURVIVES_DROP_EDGE.contains(type),
                    CoopNetPump.survivesTheDropEdge(type), type.name());
        }
    }

    @Test
    void terminalRejectMatchesWhitelist() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(TERMINAL_REJECT.contains(type),
                    CoopNetPump.isTerminalRejectType(type), type.name());
        }
    }

    @Test
    void controlPlaneMatchesWhitelist() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(CONTROL_PLANE.contains(type),
                    CoopNetPump.isControlPlane(type), type.name());
        }
    }

    @Test
    void highFrequencyMatchesWhitelist() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(HIGH_FREQUENCY.contains(type),
                    CoopNetPump.isHighFrequency(type), type.name());
        }
    }

    // ---- cross-table consistency ---------------------------------------------------------------
    // Rules the tables' own javadoc implies and the code actually satisfies today. Not exhaustive:
    // e.g. "every high-frequency type has a coalesce key or is STATE_DATAGRAM" does NOT hold
    // (PING, PONG, FLEET_SNAPSHOT, NPC_FLEET_MOTION and BATTLE_STATUS are high-frequency with no
    // coalesce key), so it is deliberately not asserted here.

    @Test
    void theLobbyVocabularyNeverSurvivesTheDropEdge() {
        // isConnectionScopedControl's javadoc: a copy of these left over from a dead connection is
        // "never an answer to anything the new peer asked". survivesTheDropEdge should agree.
        for (CoopMessages.Type type : CONNECTION_SCOPED_LOBBY_VOCABULARY) {
            assertFalse(CoopNetPump.survivesTheDropEdge(type),
                    type + " is connection-scoped lobby control and must not survive the drop edge");
        }
    }

    @Test
    void theLobbyVocabularyIsAlwaysControlPlane() {
        // Both tables describe the same lobby/handshake/resume vocabulary from different angles;
        // that vocabulary should be a subset of isControlPlane's.
        for (CoopMessages.Type type : CONNECTION_SCOPED_LOBBY_VOCABULARY) {
            assertTrue(CONTROL_PLANE.contains(type),
                    type + " is connection-scoped lobby control and should also be control-plane");
        }
    }

    /**
     * The deliberate exception to both rules above, pinned so it reads as a decision rather than as
     * drift. {@code RELIABLE_ACK} shares the connection scope (a leftover ack is written off with its
     * socket) without sharing either consequence: it is not pre-session chatter, and a pre-drop ack
     * is a fact about what the partner applied, which the drop edge does not undo.
     */
    @Test
    void theReliableAckIsAConnectionScopedTypeThatSurvivesAndIsNotControlPlane() {
        assertTrue(CoopNetService.isConnectionScopedControl(CoopMessages.Type.RELIABLE_ACK));
        assertTrue(CoopNetPump.survivesTheDropEdge(CoopMessages.Type.RELIABLE_ACK));
        assertFalse(CoopNetPump.isControlPlane(CoopMessages.Type.RELIABLE_ACK));
        assertFalse(CoopNetPump.allowedDuringReconnectGrace(CoopMessages.Type.RELIABLE_ACK));
        assertFalse(CoopMessages.isReliableOneShot(CoopMessages.Type.RELIABLE_ACK),
                "acknowledging an acknowledgement is a loop with no bottom");
    }

    /**
     * The second exception, and the more load-bearing one. {@code SESSION_LEAVE} shares the ack's
     * shape - connection-scoped, survives the drop edge, not control plane - for its own reasons,
     * and the combination is what the feature is made of: never replayed onto a socket the departed
     * player is not holding, always honoured when it came off the socket that died.
     *
     * <p>It is deliberately <b>not</b> on {@code allowedDuringReconnectGrace}. That table is "what an
     * unproven peer may say while a window is open", and a leave is the one message that ends a held
     * session outright - handing it to whoever dialled the freed slot would be a one-packet session
     * kill. The proven path is the pre-drop generation stamp instead: a leave off the connection that
     * carried the session is applied by {@code dispatchInbound}'s {@code preDropProven &&
     * survivesTheDropEdge} branch, and one from a stranger is dropped.
     */
    @Test
    void theSessionLeaveIsHonouredOnlyFromTheConnectionThatCarriedTheSession() {
        assertTrue(CoopNetService.isConnectionScopedControl(CoopMessages.Type.SESSION_LEAVE));
        assertTrue(CoopNetPump.survivesTheDropEdge(CoopMessages.Type.SESSION_LEAVE));
        assertFalse(CoopNetPump.isControlPlane(CoopMessages.Type.SESSION_LEAVE));
        assertFalse(CoopNetPump.allowedDuringReconnectGrace(CoopMessages.Type.SESSION_LEAVE),
                "a stranger on the freed slot must not be able to end a held session with one frame");
        assertFalse(CoopMessages.isReliableOneShot(CoopMessages.Type.SESSION_LEAVE),
                "the only socket a leave could be replayed on is one its sender is not holding");
    }

    /**
     * The coordinated-save answer is ordinary session traffic in every table but one: it survives the
     * drop edge, because whether the guest's save happened is a fact about two files on disk and not
     * about the socket that reported it.
     */
    @Test
    void theSaveCheckpointResultIsOrdinarySessionTrafficThatOutlivesItsSocket() {
        assertFalse(CoopNetService.isConnectionScopedControl(
                CoopMessages.Type.SAVE_CHECKPOINT_RESULT));
        assertTrue(CoopNetPump.survivesTheDropEdge(CoopMessages.Type.SAVE_CHECKPOINT_RESULT));
        assertFalse(CoopNetPump.isControlPlane(CoopMessages.Type.SAVE_CHECKPOINT_RESULT));
        assertFalse(CoopNetPump.isHighFrequency(CoopMessages.Type.SAVE_CHECKPOINT_RESULT),
                "three messages per parked save is not a stream");
        assertFalse(CoopNetPump.allowedDuringReconnectGrace(
                CoopMessages.Type.SAVE_CHECKPOINT_RESULT));
        assertFalse(CoopMessages.isReliableOneShot(CoopMessages.Type.SAVE_CHECKPOINT_RESULT),
                "informational: the next checkpoint describes the same save state again");
    }

    // ---- 0.1.1 reliable delivery ----------------------------------------------------------------

    @Test
    void reliableOneShotMatchesTheSet() {
        for (CoopMessages.Type type : ALL_KNOWN_TYPES) {
            assertEquals(RELIABLE_ONE_SHOT.contains(type),
                    CoopMessages.isReliableOneShot(type), type.name());
        }
    }

    /**
     * The two queue behaviours that would silently defeat the resend layer. A reliable message that
     * coalesces would be replaced in the queue by a newer one carrying different facts, and
     * {@code enforceQueueCapLocked} drops exactly the coalescable ones - so "keys as null" is the
     * single property that keeps both hands off it.
     */
    @Test
    void aReliableOneShotIsNeverCoalescedAndNeverDroppedByTheQueueCap() {
        for (CoopMessages.Type type : RELIABLE_ONE_SHOT) {
            assertNull(CoopNetService.coalesceKey(message(type)),
                    type + " is reliable and must never be superseded or dropped by the queue cap");
        }
    }

    /**
     * A reliable message must not be connection-scoped, or the attach that follows a link death
     * would throw the replay away at exactly the moment it is needed.
     */
    @Test
    void aReliableOneShotIsNeverConnectionScoped() {
        for (CoopMessages.Type type : RELIABLE_ONE_SHOT) {
            assertFalse(CoopNetService.isConnectionScopedControl(type),
                    type + " is reliable, so a new socket must carry it rather than drop it");
        }
    }

    /**
     * ...and it must survive the drop edge, for the same reason it is reliable at all: the fact it
     * carries stays true after the connection that carried it is gone.
     */
    @Test
    void everyReliableOneShotSurvivesTheDropEdge() {
        for (CoopMessages.Type type : RELIABLE_ONE_SHOT) {
            assertTrue(CoopNetPump.survivesTheDropEdge(type),
                    type + " is reliable and must still apply after the drop edge");
        }
    }

    @Test
    void terminalRejectTypesAreAlwaysControlPlane() {
        // isTerminalRejectType's javadoc: these are peer verdicts on a join, which is exactly what
        // isControlPlane calls the pre-session control plane.
        for (CoopMessages.Type type : TERMINAL_REJECT) {
            assertTrue(CONTROL_PLANE.contains(type),
                    type + " is a terminal reject type and should also be control-plane");
        }
    }
}
