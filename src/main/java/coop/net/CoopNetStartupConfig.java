package coop.net;

import java.util.Objects;
import java.util.Properties;

public final class CoopNetStartupConfig {
    public static final String HOST_PORT_PROPERTY = "coop.hostPort";
    public static final String CONNECT_HOST_PROPERTY = "coop.connectHost";
    public static final String CONNECT_PORT_PROPERTY = "coop.connectPort";
    public static final String NEW_GAME_SEED_PROPERTY = "coop.newGameSeed";

    private static final CoopNetStartupConfig EMPTY = new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0, "");

    private final boolean present;
    private final CoopConnectionRole role;
    private final String host;
    private final int port;
    private final String newGameSeed;

    private CoopNetStartupConfig(boolean present, CoopConnectionRole role, String host, int port, String newGameSeed) {
        this.present = present;
        this.role = Objects.requireNonNull(role, "role");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.newGameSeed = Objects.requireNonNull(newGameSeed, "newGameSeed");
    }

    public static CoopNetStartupConfig fromSystemProperties() {
        return from(System.getProperties());
    }

    public static String newGameSeedFromSystemProperties() {
        return trimToEmpty(System.getProperty(NEW_GAME_SEED_PROPERTY));
    }

    public static CoopNetStartupConfig from(Properties properties) {
        Objects.requireNonNull(properties, "properties");

        String hostPort = trimToNull(properties.getProperty(HOST_PORT_PROPERTY));
        String connectHost = trimToNull(properties.getProperty(CONNECT_HOST_PROPERTY));
        String connectPort = trimToNull(properties.getProperty(CONNECT_PORT_PROPERTY));
        String newGameSeed = trimToEmpty(properties.getProperty(NEW_GAME_SEED_PROPERTY));

        boolean hostConfigured = hostPort != null;
        boolean guestConfigured = connectHost != null || connectPort != null;
        if (hostConfigured && guestConfigured) {
            throw new IllegalArgumentException("Configure either host or guest coop startup properties, not both");
        }
        if (hostConfigured) {
            return new CoopNetStartupConfig(true, CoopConnectionRole.HOST, "",
                    parsePort(hostPort, HOST_PORT_PROPERTY), newGameSeed);
        }
        if (!guestConfigured) {
            if (newGameSeed.isEmpty()) {
                return EMPTY;
            }
            return new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0, newGameSeed);
        }
        if (connectHost == null) {
            throw new IllegalArgumentException(CONNECT_HOST_PROPERTY + " is required when connecting as guest");
        }
        if (connectPort == null) {
            throw new IllegalArgumentException(CONNECT_PORT_PROPERTY + " is required when connecting as guest");
        }
        return new CoopNetStartupConfig(true, CoopConnectionRole.GUEST, connectHost,
                parsePort(connectPort, CONNECT_PORT_PROPERTY), newGameSeed);
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

    public String newGameSeed() {
        return newGameSeed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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
