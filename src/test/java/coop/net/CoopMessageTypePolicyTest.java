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
            CoopMessages.Type.OPTIONS_APPLIED, CoopMessages.Type.CREDITS_GRANT);

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
            CoopMessages.Type.SESSION_RESUME_REJECT);

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
            CoopMessages.Type.CREDITS_GRANT);

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
    void connectionScopedControlNeverSurvivesTheDropEdge() {
        // isConnectionScopedControl's javadoc: a copy of these left over from a dead connection is
        // "never an answer to anything the new peer asked". survivesTheDropEdge should agree.
        for (CoopMessages.Type type : CONNECTION_SCOPED_CONTROL) {
            assertFalse(CoopNetPump.survivesTheDropEdge(type),
                    type + " is connection-scoped control and must not survive the drop edge");
        }
    }

    @Test
    void connectionScopedControlIsAlwaysControlPlane() {
        // Both tables describe the same lobby/handshake/resume vocabulary from different angles;
        // isConnectionScopedControl's set should be a subset of isControlPlane's.
        for (CoopMessages.Type type : CONNECTION_SCOPED_CONTROL) {
            assertTrue(CONTROL_PLANE.contains(type),
                    type + " is connection-scoped control and should also be control-plane");
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
