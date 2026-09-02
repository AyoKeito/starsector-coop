package coop.session;

import coop.net.CoopConnectionRole;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class CoopSessionState {
    private final Supplier<String> idSupplier;

    private String sessionId;
    private String provisionalLobbyId;
    private String localPlayerId;
    private String remotePlayerId;
    private String localName;
    private String remoteName;
    private CoopConnectionRole role = CoopConnectionRole.NONE;
    private CoopLobbyState connectionState = CoopLobbyState.NONE;
    private boolean handshakeValidated;
    private Long seedLong;
    private String seedString;
    private String sectorFingerprint;
    /** Why the last lobby reject happened; the HUD says it out loud. Null when nothing was rejected. */
    private String rejectReason;
    /** True when that reject can never be retried in this launch (wrong lobby password). */
    private boolean rejectTerminal;

    public CoopSessionState() {
        this(() -> UUID.randomUUID().toString());
    }

    public CoopSessionState(Supplier<String> idSupplier) {
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public synchronized void startHost(String name) {
        reset();
        role = CoopConnectionRole.HOST;
        connectionState = CoopLobbyState.HOST_WAITING;
        provisionalLobbyId = nextId("provisionalLobbyId");
        localPlayerId = nextId("localPlayerId");
        localName = normalizeName(name, "Host");
    }

    public synchronized void startGuest(String name) {
        reset();
        role = CoopConnectionRole.GUEST;
        connectionState = CoopLobbyState.GUEST_CONNECTING;
        localPlayerId = nextId("localPlayerId");
        localName = normalizeName(name, "Guest");
    }

    public synchronized boolean canAcceptGuest() {
        return role == CoopConnectionRole.HOST
                && connectionState == CoopLobbyState.HOST_WAITING
                && remotePlayerId == null;
    }

    public synchronized void hostAcceptGuest(CoopPlayerInfo guest) {
        Objects.requireNonNull(guest, "guest");
        if (!canAcceptGuest()) {
            throw new IllegalStateException(rejectReasonForGuest(guest));
        }
        remotePlayerId = guest.playerId();
        remoteName = guest.name();
        connectionState = CoopLobbyState.HOST_CONNECTED;
        clearCanonicalSession();
    }

    public synchronized String rejectReasonForGuest(CoopPlayerInfo guest) {
        Objects.requireNonNull(guest, "guest");
        if (role != CoopConnectionRole.HOST) {
            return "Host lobby is not active";
        }
        if (remotePlayerId != null || connectionState == CoopLobbyState.HOST_CONNECTED) {
            return "Lobby already has a guest";
        }
        return "Host lobby is not accepting guests";
    }

    public synchronized void guestAcceptLobby(String acceptedProvisionalLobbyId, CoopPlayerInfo host) {
        Objects.requireNonNull(host, "host");
        if (role != CoopConnectionRole.GUEST || connectionState != CoopLobbyState.GUEST_CONNECTING) {
            throw new IllegalStateException("Guest lobby is not waiting for accept");
        }
        provisionalLobbyId = requireText(acceptedProvisionalLobbyId, "provisionalLobbyId");
        remotePlayerId = host.playerId();
        remoteName = host.name();
        connectionState = CoopLobbyState.GUEST_CONNECTED;
        clearCanonicalSession();
    }

    /** A retryable reject: the guest reconnects and runs a fresh lobby round. */
    public synchronized void guestRejectLobby(String reason) {
        guestRejectLobby(reason, false);
    }

    /**
     * The host answered {@code LOBBY_REJECT}. The reason is kept ({@link #rejectReason()}) so the HUD
     * can say <em>which</em> reject this was instead of a bare "connection rejected".
     *
     * @param terminal true when this launch can never be accepted no matter how often it retries —
     *                 today only a wrong lobby password, which is a launch-time JVM property. A
     *                 terminal reject sticks: {@link #onChannelDisconnected()} stops rewinding out of
     *                 {@link CoopLobbyState#REJECTED}, so the state (and the HUD line explaining it)
     *                 survives the drop the host sends right behind the reject.
     */
    public synchronized void guestRejectLobby(String reason, boolean terminal) {
        if (role != CoopConnectionRole.GUEST) {
            throw new IllegalStateException("Only a guest lobby can be rejected");
        }
        connectionState = CoopLobbyState.REJECTED;
        rejectReason = normalizeReason(reason);
        rejectTerminal = terminal;
        clearCanonicalSession();
    }

    /**
     * Connect edge (F5): a guest that reconnected while still holding a retryable
     * {@link CoopLobbyState#REJECTED} rearms for a fresh lobby round.
     *
     * <p>Needed because the reject and the drop that follows it race, and the drop usually wins: the
     * host writes {@code LOBBY_REJECT} and closes, so the guest's transport reports the close on the
     * same frame it queues the message, {@link #onChannelDisconnected()} runs <em>before</em> the
     * inbound drain, and REJECTED is entered a few microseconds after the only edge that clears it.
     * The guest then reconnected in that dead state and sent nothing at all, holding the host's one
     * lobby slot until the 15 s handshake deadline killed it. Rearming at the connect edge closes
     * that hole from the other side: every new connection either sends a hello or is terminal.
     *
     * @return true when the state actually moved back to {@link CoopLobbyState#GUEST_CONNECTING}
     */
    public synchronized boolean guestRearmLobby() {
        if (role != CoopConnectionRole.GUEST
                || connectionState != CoopLobbyState.REJECTED
                || rejectTerminal) {
            return false;
        }
        connectionState = CoopLobbyState.GUEST_CONNECTING;
        rejectReason = null;
        remotePlayerId = null;
        remoteName = null;
        provisionalLobbyId = null;
        clearCanonicalSession();
        return true;
    }

    /** The last reject's reason text, or null when nothing was rejected. */
    public synchronized String rejectReason() {
        return rejectReason;
    }

    /** Whether the last reject was terminal for this launch; see {@link #guestRejectLobby(String, boolean)}. */
    public synchronized boolean rejectTerminal() {
        return rejectTerminal;
    }

    /**
     * The transport channel to the peer died (peer quit, network drop). Frees the peer slot and
     * rewinds to this role's pre-lobby state so a reconnecting peer runs the full
     * lobby/handshake/seed-lock sequence again on the new connection. The 12b reconnect drill found
     * the host answering every rejoin with "Lobby already has a guest" forever, because nothing ever
     * released the slot. Local identity survives (stable player id across reconnects); the canonical
     * session and seed lock are dropped because they belonged to the dead connection. Also recovers
     * from {@link CoopLobbyState#REJECTED}: the rejected peer is gone, and staying dead would block
     * a corrected peer (e.g. fixed mod list) from ever retrying. Returns true when any state was
     * actually dropped.
     *
     * <p>Exception: a <em>terminal</em> reject (wrong lobby password) is left standing. There is
     * nothing to recover to — the guest is not reconnecting — and clearing it would wipe the only
     * on-screen explanation of why the session never came up.
     */
    public synchronized boolean onChannelDisconnected() {
        if (role == CoopConnectionRole.NONE || rejectTerminal) {
            return false;
        }
        CoopLobbyState target = role == CoopConnectionRole.HOST
                ? CoopLobbyState.HOST_WAITING
                : CoopLobbyState.GUEST_CONNECTING;
        boolean changed = remotePlayerId != null || sessionId != null
                || handshakeValidated || seedLong != null || connectionState != target;
        remotePlayerId = null;
        remoteName = null;
        if (role == CoopConnectionRole.GUEST) {
            // Host-minted; the next lobby accept supplies a fresh one.
            provisionalLobbyId = null;
        }
        connectionState = target;
        clearCanonicalSession();
        return changed;
    }

    public synchronized String hostAcceptHandshake() {
        if (role != CoopConnectionRole.HOST || connectionState != CoopLobbyState.HOST_CONNECTED) {
            throw new IllegalStateException("Host lobby is not ready for handshake acceptance");
        }
        if (handshakeValidated) {
            return sessionId;
        }
        sessionId = nextId("sessionId");
        handshakeValidated = true;
        return sessionId;
    }

    public synchronized void guestAcceptHandshake(String acceptedSessionId) {
        if (role != CoopConnectionRole.GUEST || connectionState != CoopLobbyState.GUEST_CONNECTED) {
            throw new IllegalStateException("Guest lobby is not ready for handshake acceptance");
        }
        sessionId = requireText(acceptedSessionId, "sessionId");
        handshakeValidated = true;
    }

    public synchronized void rejectHandshake(String reason) {
        if (role == CoopConnectionRole.NONE) {
            throw new IllegalStateException("No active lobby can reject a handshake");
        }
        connectionState = CoopLobbyState.REJECTED;
        clearCanonicalSession();
    }

    public synchronized void recordSeedLock(long acceptedSeedLong, String acceptedSeedString,
                                            String acceptedSectorFingerprint) {
        if (!handshakeValidated || sessionId == null) {
            throw new IllegalStateException("Seed lock requires an accepted handshake");
        }
        seedLong = acceptedSeedLong;
        seedString = requireText(acceptedSeedString, "seedString");
        sectorFingerprint = requireText(acceptedSectorFingerprint, "sectorFingerprint");
    }

    public synchronized CoopPlayerInfo localPlayerInfo() {
        if (localPlayerId == null || localName == null) {
            throw new IllegalStateException("Local player info is not initialized");
        }
        return new CoopPlayerInfo(localPlayerId, localName);
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized String provisionalLobbyId() {
        return provisionalLobbyId;
    }

    public synchronized String localPlayerId() {
        return localPlayerId;
    }

    public synchronized String remotePlayerId() {
        return remotePlayerId;
    }

    public synchronized String localName() {
        return localName;
    }

    public synchronized String remoteName() {
        return remoteName;
    }

    public synchronized CoopConnectionRole role() {
        return role;
    }

    public synchronized CoopLobbyState connectionState() {
        return connectionState;
    }

    public synchronized boolean handshakeValidated() {
        return handshakeValidated;
    }

    public synchronized Long seedLong() {
        return seedLong;
    }

    public synchronized String seedString() {
        return seedString;
    }

    public synchronized String sectorFingerprint() {
        return sectorFingerprint;
    }

    public synchronized void reset() {
        sessionId = null;
        provisionalLobbyId = null;
        localPlayerId = null;
        remotePlayerId = null;
        localName = null;
        remoteName = null;
        role = CoopConnectionRole.NONE;
        connectionState = CoopLobbyState.NONE;
        handshakeValidated = false;
        seedLong = null;
        seedString = null;
        sectorFingerprint = null;
        rejectReason = null;
        rejectTerminal = false;
    }

    private static String normalizeReason(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void clearCanonicalSession() {
        sessionId = null;
        handshakeValidated = false;
        seedLong = null;
        seedString = null;
        sectorFingerprint = null;
    }

    private String nextId(String fieldName) {
        return requireText(idSupplier.get(), fieldName);
    }

    private static String normalizeName(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return normalized;
    }
}
