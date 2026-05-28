package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.session.CoopLobbyState;
import coop.session.CoopPlayerInfo;
import coop.session.CoopSessionState;
import coop.util.CoopLog;

import java.util.Objects;
import java.util.function.LongSupplier;

public class CoopNetPump implements EveryFrameScript {
    private static final long PING_INTERVAL_MILLIS = 3000L;
    private static final String HOST_PORT_FLAG = "coop.hostPort";
    private static final String CONNECT_HOST_FLAG = "coop.connectHost";
    private static final String CONNECT_PORT_FLAG = "coop.connectPort";
    private static final String PLAYER_NAME_PROPERTY = "coop.playerName";

    private final CoopNetService service;
    private final CoopSessionState sessionState;
    private final LongSupplier clockMillis;
    private long nextPingAtMillis;
    private boolean startupConfigChecked;
    private boolean memoryConfigWarningLogged;
    private boolean lobbyHelloSent;

    public CoopNetPump(CoopNetService service) {
        this(service, System::currentTimeMillis);
    }

    public CoopNetPump(CoopNetService service, LongSupplier clockMillis) {
        this(service, new CoopSessionState(), clockMillis);
    }

    public CoopNetPump(CoopNetService service, CoopSessionState sessionState, LongSupplier clockMillis) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessionState = Objects.requireNonNull(sessionState, "sessionState");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.nextPingAtMillis = clockMillis.getAsLong() + PING_INTERVAL_MILLIS;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        maybeStartFromSystemProperties();
        maybeStartFromMemoryFlags();
        service.flushOutbound();
        maybeSendLobbyHello();
        drainInbound();
        maybeSendPing();
        service.flushOutbound();
    }

    private void maybeStartFromSystemProperties() {
        if (startupConfigChecked || service.role() != CoopConnectionRole.NONE) {
            return;
        }
        startupConfigChecked = true;

        try {
            CoopNetStartupConfig config = CoopNetStartupConfig.fromSystemProperties();
            if (!config.isPresent()) {
                return;
            }
            if (config.role() == CoopConnectionRole.HOST) {
                service.startHost(config.port());
                sessionState.startHost(localPlayerName(CoopConnectionRole.HOST));
                lobbyHelloSent = false;
                CoopLog.info(CoopNetPump.class, "Coop host started from JVM property "
                        + CoopNetStartupConfig.HOST_PORT_PROPERTY + "=" + config.port());
            } else if (config.role() == CoopConnectionRole.GUEST) {
                sessionState.startGuest(localPlayerName(CoopConnectionRole.GUEST));
                lobbyHelloSent = false;
                service.connect(config.host(), config.port());
                CoopLog.info(CoopNetPump.class, "Coop guest started from JVM properties "
                        + CoopNetStartupConfig.CONNECT_HOST_PROPERTY + "=" + config.host() + ", "
                        + CoopNetStartupConfig.CONNECT_PORT_PROPERTY + "=" + config.port());
            }
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetPump.class, "Invalid coop networking JVM properties", ex);
        }
    }

    private void maybeStartFromMemoryFlags() {
        if (service.role() != CoopConnectionRole.NONE) {
            return;
        }

        SectorAPI sector;
        try {
            sector = Global.getSector();
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        if (sector == null) {
            return;
        }

        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        try {
            if (memory.contains(HOST_PORT_FLAG)) {
                int port = parsePort(memory.get(HOST_PORT_FLAG), HOST_PORT_FLAG);
                service.startHost(port);
                sessionState.startHost(localPlayerName(CoopConnectionRole.HOST));
                lobbyHelloSent = false;
                CoopLog.info(CoopNetPump.class, "Coop host control consumed memory flag " + HOST_PORT_FLAG + "=" + port);
                return;
            }

            if (memory.contains(CONNECT_HOST_FLAG) && memory.contains(CONNECT_PORT_FLAG)) {
                String host = String.valueOf(memory.get(CONNECT_HOST_FLAG)).trim();
                int port = parsePort(memory.get(CONNECT_PORT_FLAG), CONNECT_PORT_FLAG);
                if (host.isEmpty()) {
                    throw new IllegalArgumentException(CONNECT_HOST_FLAG + " is blank");
                }
                sessionState.startGuest(localPlayerName(CoopConnectionRole.GUEST));
                lobbyHelloSent = false;
                service.connect(host, port);
                CoopLog.info(CoopNetPump.class,
                        "Coop guest control consumed memory flags " + CONNECT_HOST_FLAG + "=" + host
                                + ", " + CONNECT_PORT_FLAG + "=" + port);
            }
        } catch (RuntimeException ex) {
            if (!memoryConfigWarningLogged) {
                CoopLog.warn(CoopNetPump.class, "Invalid coop networking memory flags", ex);
                memoryConfigWarningLogged = true;
            }
        }
    }

    private String localPlayerName(CoopConnectionRole role) {
        String configured = System.getProperty(PLAYER_NAME_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return role == CoopConnectionRole.HOST ? "Host" : "Guest";
    }

    private int parsePort(Object value, String flagName) {
        int port;
        if (value instanceof Number number) {
            port = number.intValue();
        } else {
            port = Integer.parseInt(String.valueOf(value).trim());
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(flagName + " must be in range 1..65535");
        }
        return port;
    }

    private void drainInbound() {
        CoopMessages.Message message;
        while ((message = service.pollInbound()) != null) {
            log("inbound", message);
            switch (message.type()) {
                case LOBBY_HELLO -> handleLobbyHello(message);
                case LOBBY_ACCEPT -> handleLobbyAccept(message);
                case LOBBY_REJECT -> handleLobbyReject(message);
                case PING -> sendPong(message);
                default -> {
                }
            }
        }
    }

    private void maybeSendLobbyHello() {
        if (lobbyHelloSent
                || service.role() != CoopConnectionRole.GUEST
                || !service.isConnected()
                || sessionState.connectionState() != CoopLobbyState.GUEST_CONNECTING) {
            return;
        }

        CoopMessages.Message hello = CoopMessages.lobbyHello(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionState.localPlayerInfo());
        service.send(hello);
        lobbyHelloSent = true;
        log("outbound", hello);
    }

    private void handleLobbyHello(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.HOST) {
            return;
        }

        CoopPlayerInfo guest = new CoopPlayerInfo(
                CoopMessages.requiredPayloadString(message, "playerId"),
                CoopMessages.requiredPayloadString(message, "playerName"));

        if (!sessionState.canAcceptGuest()) {
            String reason = sessionState.rejectReasonForGuest(guest);
            CoopMessages.Message reject = CoopMessages.lobbyReject(
                    service.nextSeq(),
                    clockMillis.getAsLong(),
                    reason);
            service.send(reject);
            log("outbound", reject);
            return;
        }

        sessionState.hostAcceptGuest(guest);
        CoopMessages.Message accept = CoopMessages.lobbyAccept(
                service.nextSeq(),
                clockMillis.getAsLong(),
                sessionState.provisionalLobbyId(),
                sessionState.localPlayerInfo());
        service.send(accept);
        log("outbound", accept);
        CoopLog.info(CoopNetPump.class,
                "Coop lobby accepted provisionalLobbyId=" + sessionState.provisionalLobbyId()
                        + " hostPlayerId=" + sessionState.localPlayerId()
                        + " guestPlayerId=" + sessionState.remotePlayerId());
    }

    private void handleLobbyAccept(CoopMessages.Message message) {
        if (service.role() != CoopConnectionRole.GUEST) {
            return;
        }

        CoopPlayerInfo host = new CoopPlayerInfo(
                CoopMessages.requiredPayloadString(message, "hostPlayerId"),
                CoopMessages.requiredPayloadString(message, "hostName"));
        sessionState.guestAcceptLobby(
                CoopMessages.requiredPayloadString(message, "provisionalLobbyId"),
                host);
        CoopLog.info(CoopNetPump.class,
                "Coop lobby connected provisionalLobbyId=" + sessionState.provisionalLobbyId()
                        + " hostPlayerId=" + sessionState.remotePlayerId()
                        + " guestPlayerId=" + sessionState.localPlayerId());
    }

    private void handleLobbyReject(CoopMessages.Message message) {
        if (service.role() == CoopConnectionRole.GUEST) {
            sessionState.guestRejectLobby(CoopMessages.requiredPayloadString(message, "reason"));
        }
        CoopLog.warn(CoopNetPump.class,
                "Coop lobby rejected: " + CoopMessages.requiredPayloadString(message, "reason"));
    }

    private void maybeSendPing() {
        if (service.role() != CoopConnectionRole.GUEST || !service.isConnected()) {
            return;
        }
        if (sessionState.connectionState() != CoopLobbyState.NONE
                && sessionState.connectionState() != CoopLobbyState.GUEST_CONNECTED) {
            return;
        }

        long now = clockMillis.getAsLong();
        if (now < nextPingAtMillis) {
            return;
        }

        CoopMessages.Message ping = CoopMessages.ping(null, service.nextSeq(), now);
        service.send(ping);
        log("outbound", ping);
        nextPingAtMillis = now + PING_INTERVAL_MILLIS;
    }

    private void sendPong(CoopMessages.Message ping) {
        CoopMessages.Message pong = CoopMessages.pong(
                ping.sessionId(),
                service.nextSeq(),
                clockMillis.getAsLong(),
                ping.seq());
        service.send(pong);
        log("outbound", pong);
    }

    private void log(String direction, CoopMessages.Message message) {
        CoopLog.info(CoopNetPump.class,
                "Coop net " + service.role() + " " + direction + " " + message.type() + " seq=" + message.seq());
    }
}
