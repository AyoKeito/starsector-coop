package coop.net;

import java.util.Objects;
import java.util.Properties;

public final class CoopNetStartupConfig {
    public static final String HOST_PORT_PROPERTY = "coop.hostPort";
    public static final String CONNECT_HOST_PROPERTY = "coop.connectHost";
    public static final String CONNECT_PORT_PROPERTY = "coop.connectPort";

    private static final CoopNetStartupConfig EMPTY = new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0);

    private final boolean present;
    private final CoopConnectionRole role;
    private final String host;
    private final int port;

    private CoopNetStartupConfig(boolean present, CoopConnectionRole role, String host, int port) {
        this.present = present;
        this.role = Objects.requireNonNull(role, "role");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
    }

    public static CoopNetStartupConfig fromSystemProperties() {
        return from(System.getProperties());
    }

    public static CoopNetStartupConfig from(Properties properties) {
        Objects.requireNonNull(properties, "properties");

        String hostPort = trimToNull(properties.getProperty(HOST_PORT_PROPERTY));
        String connectHost = trimToNull(properties.getProperty(CONNECT_HOST_PROPERTY));
        String connectPort = trimToNull(properties.getProperty(CONNECT_PORT_PROPERTY));

        boolean hostConfigured = hostPort != null;
        boolean guestConfigured = connectHost != null || connectPort != null;
        if (hostConfigured && guestConfigured) {
            throw new IllegalArgumentException("Configure either host or guest coop startup properties, not both");
        }
        if (hostConfigured) {
            return new CoopNetStartupConfig(true, CoopConnectionRole.HOST, "", parsePort(hostPort, HOST_PORT_PROPERTY));
        }
        if (!guestConfigured) {
            return EMPTY;
        }
        if (connectHost == null) {
            throw new IllegalArgumentException(CONNECT_HOST_PROPERTY + " is required when connecting as guest");
        }
        if (connectPort == null) {
            throw new IllegalArgumentException(CONNECT_PORT_PROPERTY + " is required when connecting as guest");
        }
        return new CoopNetStartupConfig(true, CoopConnectionRole.GUEST, connectHost,
                parsePort(connectPort, CONNECT_PORT_PROPERTY));
    }

    public boolean isPresent() {
        return present;
    }

    public CoopConnectionRole role() {
        return role;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parsePort(String value, String propertyName) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(propertyName + " must be an integer", ex);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(propertyName + " must be in range 1..65535");
        }
        return port;
    }
}
