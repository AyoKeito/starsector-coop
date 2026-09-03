package coop.net;

import coop.config.CoopOptionsRegistry;
import coop.config.CoopOptionsStore;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Launch/connection settings.
 *
 * <p><b>Phase 28 milestone 1.</b> Every value here now resolves through {@link CoopOptionsStore}
 * rather than straight off {@code System.getProperty}, which puts the full precedence stack behind
 * it: {@code -Dcoop.*} beats {@code saves/common/coop_options.json} beats the shipped
 * {@code data/config/coop_options.json} beats the {@link CoopOptionsRegistry} default. Every public
 * method and constant kept its name and meaning, and a {@code -D}-configured launch resolves to
 * exactly the same values it did before, so the pump, the doctor and the launch scripts are
 * unchanged.
 *
 * <p>The parsing below is deliberately still the strict kind: a malformed port, grace window or
 * port-mapping mode is refused rather than quietly replaced, and it is refused identically whether
 * it came from a command line or from a file. That is why the reads use
 * {@link CoopOptionsStore#raw(String)} (the winning value, unvalidated) instead of the store's
 * clamping getters - two different answers for "the port is garbage" depending on which layer said
 * it would be worse than one loud one.
 */
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
    /**
     * Phase 20.4 optional lobby password. Set on both installs; unset (or blank) on the host means no
     * password is asked for and the lobby exchange is byte-identical to the pre-20.4 one.
     *
     * <p>Explicitly a gatekeeper, not encryption: the protocol is plaintext, the proof is
     * {@code SHA-256(password + nonce)} over a fresh host nonce, and what it buys is that a port
     * scanner who finds the open port cannot join. Confidentiality is the VPN tier's job.
     */
    public static final String PASSWORD_PROPERTY = "coop.password";
    /**
     * Phase 20.5 peer-table capacity. Any value other than {@link #MAX_GUESTS_V1} is clamped with a
     * warning: the transport is N-ready, the <em>gameplay</em> arbitration is not (Phase 27), and
     * silently honouring {@code coop.maxGuests=3} would produce a session that connects and then
     * misbehaves in ways no test covers.
     */
    public static final String MAX_GUESTS_PROPERTY = "coop.maxGuests";
    /**
     * Phase 20.6 HUD corner placement: {@code TR} (default), {@code TL}, {@code BR} or {@code BL},
     * case-insensitive. See {@link coop.ui.CoopHudCorner#parse(String)} for the fallback behaviour on
     * an unrecognised value.
     */
    public static final String HUD_CORNER_PROPERTY = "coop.hudCorner";

    /** The only supported guest count in v1. */
    public static final int MAX_GUESTS_V1 = 1;

    /** Long enough for a NAT rebind or a Wi-Fi roam, short enough not to strand a player. */
    public static final int DEFAULT_RECONNECT_GRACE_SECONDS = 60;
    /** Upper bound; past this the "held" world is indistinguishable from a hung game. */
    public static final int MAX_RECONNECT_GRACE_SECONDS = 3600;

    private static final String PORT_MAPPING_AUTO = "auto";
    private static final String PORT_MAPPING_OFF = "off";

    private static final CoopNetStartupConfig EMPTY =
            new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0, "", true,
                    DEFAULT_RECONNECT_GRACE_SECONDS, "", MAX_GUESTS_V1);

    private final boolean present;
    private final CoopConnectionRole role;
    private final String host;
    private final int port;
    private final String newGameSeed;
    private final boolean portMappingEnabled;
    private final int reconnectGraceSeconds;
    private final String password;
    private final int maxGuests;

    private CoopNetStartupConfig(boolean present, CoopConnectionRole role, String host, int port,
                                 String newGameSeed, boolean portMappingEnabled,
                                 int reconnectGraceSeconds, String password, int maxGuests) {
        this.present = present;
        this.role = Objects.requireNonNull(role, "role");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.newGameSeed = Objects.requireNonNull(newGameSeed, "newGameSeed");
        this.portMappingEnabled = portMappingEnabled;
        this.reconnectGraceSeconds = reconnectGraceSeconds;
        this.password = Objects.requireNonNull(password, "password");
        this.maxGuests = maxGuests;
    }

    public static CoopNetStartupConfig fromSystemProperties() {
        return from(CoopOptionsStore.system());
    }

    public static String newGameSeedFromSystemProperties() {
        // -D only, forever (a one-shot new-game gesture): the store never reads a file for it.
        return trimToEmpty(CoopOptionsStore.system().raw(NEW_GAME_SEED_PROPERTY));
    }

    /**
     * The lobby password on its own, without parsing (and possibly rejecting) the rest of the
     * startup properties. Both the pump's lobby gate and the connection doctor need it in situations
     * where the role properties may be absent or malformed, and a bad {@code coop.connectPort} must
     * not be able to turn a password-protected host into an open one.
     */
    public static String passwordFromSystemProperties() {
        return trimToEmpty(CoopOptionsStore.system().raw(PASSWORD_PROPERTY));
    }

    /** The clamped peer capacity on its own; see {@link #MAX_GUESTS_PROPERTY}. */
    public static int maxGuestsFromSystemProperties() {
        return parseMaxGuests(CoopOptionsStore.system().raw(MAX_GUESTS_PROPERTY));
    }

    /**
     * The HUD corner on its own; see {@link #HUD_CORNER_PROPERTY}. Read directly by
     * {@link coop.ui.CoopLinkHud} at install time rather than folded into the role config above: the
     * HUD is cosmetic and installed independently of whether host/guest role properties are present,
     * malformed, or absent.
     */
    public static coop.ui.CoopHudCorner hudCornerFromSystemProperties() {
        return coop.ui.CoopHudCorner.parse(CoopOptionsStore.system().raw(HUD_CORNER_PROPERTY));
    }

    /**
     * Resolves from an explicit property set layered over the shipped/user files, the same stack
     * {@link #fromSystemProperties()} uses. Unchanged for every existing caller and test: a value
     * present in {@code properties} still wins outright.
     */
    public static CoopNetStartupConfig from(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return from(CoopOptionsStore.forProperties(properties));
    }

    /**
     * The real reader. The three role keys ({@code coop.hostPort}, {@code coop.connectHost},
     * {@code coop.connectPort}) get one extra rule: if <em>any</em> of them is given as a {@code -D}
     * property, the role is decided by the {@code -D} layer alone and file-level role keys are
     * ignored. Without it, a player who put {@code coop.hostPort} in their settings file could never
     * launch as a guest from the command line - the two layers would combine into the
     * "host and guest configured together" refusal. Every other key keeps plain per-key precedence.
     */
    public static CoopNetStartupConfig from(CoopOptionsStore store) {
        Objects.requireNonNull(store, "store");

        boolean roleFromProperties = store.hasProperty(HOST_PORT_PROPERTY)
                || store.hasProperty(CONNECT_HOST_PROPERTY)
                || store.hasProperty(CONNECT_PORT_PROPERTY);

        String hostPort = roleValue(store, HOST_PORT_PROPERTY, roleFromProperties);
        String connectHost = roleValue(store, CONNECT_HOST_PROPERTY, roleFromProperties);
        String connectPort = roleValue(store, CONNECT_PORT_PROPERTY, roleFromProperties);
        String newGameSeed = trimToEmpty(store.raw(NEW_GAME_SEED_PROPERTY));
        boolean portMappingEnabled = parsePortMapping(store.raw(PORT_MAPPING_PROPERTY));
        int reconnectGrace = parseReconnectGrace(store.raw(RECONNECT_GRACE_PROPERTY));
        String password = trimToEmpty(store.raw(PASSWORD_PROPERTY));
        int maxGuests = parseMaxGuests(store.raw(MAX_GUESTS_PROPERTY));

        boolean hostConfigured = hostPort != null;
        boolean guestConfigured = connectHost != null || connectPort != null;
        if (hostConfigured && guestConfigured) {
            throw new IllegalArgumentException("Configure either host or guest coop startup properties, not both");
        }
        if (hostConfigured) {
            return new CoopNetStartupConfig(true, CoopConnectionRole.HOST, "",
                    parsePort(hostPort, HOST_PORT_PROPERTY), newGameSeed, portMappingEnabled,
                    reconnectGrace, password, maxGuests);
        }
        if (!guestConfigured) {
            if (newGameSeed.isEmpty() && portMappingEnabled
                    && reconnectGrace == DEFAULT_RECONNECT_GRACE_SECONDS
                    && password.isEmpty() && maxGuests == MAX_GUESTS_V1) {
                return EMPTY;
            }
            return new CoopNetStartupConfig(false, CoopConnectionRole.NONE, "", 0, newGameSeed,
                    portMappingEnabled, reconnectGrace, password, maxGuests);
        }
        if (connectHost == null) {
            throw new IllegalArgumentException(CONNECT_HOST_PROPERTY + " is required when connecting as guest");
        }
        if (connectPort == null) {
            throw new IllegalArgumentException(CONNECT_PORT_PROPERTY + " is required when connecting as guest");
        }
        return new CoopNetStartupConfig(true, CoopConnectionRole.GUEST, connectHost,
                parsePort(connectPort, CONNECT_PORT_PROPERTY), newGameSeed, portMappingEnabled,
                reconnectGrace, password, maxGuests);
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

    /**
     * The lobby password, trimmed; {@code ""} means none. Never logged — the doctor prints
     * "required"/"none" and nothing else, which is the only fact a log reader needs.
     */
    public String password() {
        return password;
    }

    /** Whether a password gate is configured on this install. */
    public boolean passwordRequired() {
        return !password.isEmpty();
    }

    /** Peer-table capacity, already clamped to {@link #MAX_GUESTS_V1}. */
    public int maxGuests() {
        return maxGuests;
    }

    private static String roleValue(CoopOptionsStore store, String key, boolean propertiesOnly) {
        return trimToNull(propertiesOnly ? store.property(key) : store.raw(key));
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

    /**
     * Clamps rather than throws. A launch script that asks for three guests should still start a
     * playable one-guest session — refusing to load the game over a forward-looking setting would be
     * the worse failure — but it must say so, once, loudly enough to explain why the second guest is
     * being turned away.
     */
    private static int parseMaxGuests(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return MAX_GUESTS_V1;
        }
        int requested;
        try {
            requested = Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            coop.util.CoopLog.warn(CoopNetStartupConfig.class, MAX_GUESTS_PROPERTY + "=" + trimmed
                    + " is not an integer; using " + MAX_GUESTS_V1);
            return MAX_GUESTS_V1;
        }
        if (requested != MAX_GUESTS_V1) {
            coop.util.CoopLog.warn(CoopNetStartupConfig.class, MAX_GUESTS_PROPERTY + "=" + requested
                    + " is not supported in v1 and has been clamped to " + MAX_GUESTS_V1
                    + ". The transport is N-ready; the gameplay arbitration for more than one guest"
                    + " is a later phase.");
        }
        return MAX_GUESTS_V1;
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
