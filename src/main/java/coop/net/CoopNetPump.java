package coop.net;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import coop.util.CoopLog;

import java.util.Objects;
import java.util.function.LongSupplier;

public class CoopNetPump implements EveryFrameScript {
    private static final long PING_INTERVAL_MILLIS = 3000L;
    private static final String HOST_PORT_FLAG = "coop.hostPort";
    private static final String CONNECT_HOST_FLAG = "coop.connectHost";
    private static final String CONNECT_PORT_FLAG = "coop.connectPort";

    private final CoopNetService service;
    private final LongSupplier clockMillis;
    private long nextPingAtMillis;
    private boolean startupConfigChecked;
    private boolean memoryConfigWarningLogged;

    public CoopNetPump(CoopNetService service) {
        this(service, System::currentTimeMillis);
    }

    public CoopNetPump(CoopNetService service, LongSupplier clockMillis) {
        this.service = Objects.requireNonNull(service, "service");
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
                CoopLog.info(CoopNetPump.class, "Coop host started from JVM property "
                        + CoopNetStartupConfig.HOST_PORT_PROPERTY + "=" + config.port());
            } else if (config.role() == CoopConnectionRole.GUEST) {
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
                CoopLog.info(CoopNetPump.class, "Coop host control consumed memory flag " + HOST_PORT_FLAG + "=" + port);
                return;
            }

            if (memory.contains(CONNECT_HOST_FLAG) && memory.contains(CONNECT_PORT_FLAG)) {
                String host = String.valueOf(memory.get(CONNECT_HOST_FLAG)).trim();
                int port = parsePort(memory.get(CONNECT_PORT_FLAG), CONNECT_PORT_FLAG);
                if (host.isEmpty()) {
                    throw new IllegalArgumentException(CONNECT_HOST_FLAG + " is blank");
                }
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
            if (message.type() == CoopMessages.Type.PING) {
                sendPong(message);
            }
        }
    }

    private void maybeSendPing() {
        if (service.role() != CoopConnectionRole.GUEST || !service.isConnected()) {
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
