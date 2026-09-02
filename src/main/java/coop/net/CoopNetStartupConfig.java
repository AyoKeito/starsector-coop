package coop.net;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class CoopNetStartupConfig {
    public static final String HOST_PORT_PROPERTY = "coop.hostPort";
    public static final String CONNECT_HOST_PROPERTY = "coop.connectHost";
    public static final String CONNECT_PORT_PROPERTY = "coop.connectPort";
    public static final String NEW_GAME_SEED_PROPERTY = "coop.newGameSeed";
    /**
     * {@code auto} (default) lets {@link CoopPortMapper} ask the router to forward the host port;
     * {@code off} skips it. Off exists because a few routers answer UPnP badly enough to be worth
     * not talking to, and because a host on a VPN pseudo-LAN or a manual port forward has nothing to
     * gain from the attempt.
     */
    public static final String PORT_MAPPING_PROPERTY = "coop.portMapping";
    /**
     * Phase 20.2: how long a dropped socket keeps its session alive before the session really ends,
     * in seconds. Default {@link #DEFAULT_RECONNECT_GRACE_SECONDS}. {@code 0} disables the grace
     * entirely and restores the pre-20.2 "every drop ends the session" behaviour, which is worth
     * keeping reachable for anyone debugging a teardown path.
     */
    public static final String RECONNECT_GRACE_PROPERTY = "coop.reconnectGraceSeconds";

    /** Long enough for a NAT rebind or a Wi-Fi roam, short enough not to strand a player. */
    public static final int DEFAULT_RECONNECT_GRACE_SECONDS = 60;
    /** Upper bound; past this the "held" world is indistinguishable from a hung game. */
    public static final int MAX_RECONNECT_GRACE_SECONDS = 3600;

    private static final String PORT_MAPPING_AUTO = "auto";
    private static final String PORT_MAPPING_OFF = "off";

    private static final CoopNetStartupConfig EMPTY =
            new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0, "", true,
                    DEFAULT_RECONNECT_GRACE_SECONDS);

    private final boolean present;
    private final CoopConnectionRole role;
    private final String host;
    private final int port;
    private final String newGameSeed;
    private final boolean portMappingEnabled;
    private final int reconnectGraceSeconds;

    private CoopNetStartupConfig(boolean present, CoopConnectionRole role, String host, int port,
                                 String newGameSeed, boolean portMappingEnabled,
                                 int reconnectGraceSeconds) {
        this.present = present;
        this.role = Objects.requireNonNull(role, "role");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.newGameSeed = Objects.requireNonNull(newGameSeed, "newGameSeed");
        this.portMappingEnabled = portMappingEnabled;
        this.reconnectGraceSeconds = reconnectGraceSeconds;
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
        boolean portMappingEnabled = parsePortMapping(properties.getProperty(PORT_MAPPING_PROPERTY));
        int reconnectGrace = parseReconnectGrace(properties.getProperty(RECONNECT_GRACE_PROPERTY));

        boolean hostConfigured = hostPort != null;
        boolean guestConfigured = connectHost != null || connectPort != null;
        if (hostConfigured && guestConfigured) {
            throw new IllegalArgumentException("Configure either host or guest coop startup properties, not both");
        }
        if (hostConfigured) {
            return new CoopNetStartupConfig(true, CoopConnectionRole.HOST, "",
                    parsePort(hostPort, HOST_PORT_PROPERTY), newGameSeed, portMappingEnabled, reconnectGrace);
        }
        if (!guestConfigured) {
            if (newGameSeed.isEmpty() && portMappingEnabled
                    && reconnectGrace == DEFAULT_RECONNECT_GRACE_SECONDS) {
                return EMPTY;
            }
            return new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0, newGameSeed,
                    portMappingEnabled, reconnectGrace);
        }
        if (connectHost == null) {
            throw new IllegalArgumentException(CONNECT_HOST_PROPERTY + " is required when connecting as guest");
        }
        if (connectPort == null) {
            throw new IllegalArgumentException(CONNECT_PORT_PROPERTY + " is required when connecting as guest");
        }
        return new CoopNetStartupConfig(true, CoopConnectionRole.GUEST, connectHost,
                parsePort(connectPort, CONNECT_PORT_PROPERTY), newGameSeed, portMappingEnabled, reconnectGrace);
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

    /**
     * Whether the host should attempt automatic port mapping. Guests never map anything - the star
     * topology means only the host needs to be reachable - so this only has an effect on the host.
     */
    public boolean portMappingEnabled() {
        return portMappingEnabled;
    }

    /**
     * Phase 20.2 grace window in seconds; see {@link #RECONNECT_GRACE_PROPERTY}. Read by both roles —
     * each side runs its own timer, and they are configured independently on purpose: a guest that
     * gives up earlier than the host simply stops trying, which the host's own expiry then cleans up.
     */
    public int reconnectGraceSeconds() {
        return reconnectGraceSeconds;
    }

    /** The same value in milliseconds, which is what {@link CoopReconnectCoordinator} takes. */
    public long reconnectGraceMillis() {
        return reconnectGraceSeconds * 1000L;
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

    private static boolean parsePortMapping(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return true;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.equals(PORT_MAPPING_AUTO)) {
            return true;
        }
        if (normalized.equals(PORT_MAPPING_OFF)) {
            return false;
        }
        throw new IllegalArgumentException(
                PORT_MAPPING_PROPERTY + " must be \"" + PORT_MAPPING_AUTO + "\" or \"" + PORT_MAPPING_OFF + "\"");
    }

    private static int parseReconnectGrace(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return DEFAULT_RECONNECT_GRACE_SECONDS;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(RECONNECT_GRACE_PROPERTY + " must be an integer", ex);
        }
        if (seconds < 0 || seconds > MAX_RECONNECT_GRACE_SECONDS) {
            throw new IllegalArgumentException(RECONNECT_GRACE_PROPERTY + " must be in range 0.."
                    + MAX_RECONNECT_GRACE_SECONDS);
        }
        return seconds;
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
