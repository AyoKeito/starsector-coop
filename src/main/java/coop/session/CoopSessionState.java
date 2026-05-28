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

    public synchronized void guestRejectLobby(String reason) {
        if (role != CoopConnectionRole.GUEST) {
            throw new IllegalStateException("Only a guest lobby can be rejected");
        }
        connectionState = CoopLobbyState.REJECTED;
        clearCanonicalSession();
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
    }

    private void clearCanonicalSession() {
        sessionId = null;
        handshakeValidated = false;
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
