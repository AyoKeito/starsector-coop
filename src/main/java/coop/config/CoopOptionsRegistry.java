package coop.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 28 milestone 1: the single typed schema for every player-facing {@code coop.*} setting.
 *
 * <p>Governing principle, from the plan: <b>expose preferences, never correctness</b>. Anything the
 * sync design depends on - cadences, gate semantics, fingerprint checks - is deliberately absent,
 * and {@link #notConfigurable()} records that list by name so it cannot erode one knob at a time.
 *
 * <p>The registry is pure data plus validation. It touches no engine class, so it loads and tests
 * without a running game; {@link CoopOptionsStore} is the half that reads files. Entries for options
 * whose owning phase has not built yet ship <em>inert</em>: the schema is here (so the shipped
 * defaults file, and later the options page, are complete), but nothing reads them until that phase
 * wires its key.
 *
 * <h2>Tiers</h2>
 * <ul>
 *   <li>{@link Tier#LAUNCH} - per-client, read before any session exists. File stack plus {@code -D}
 *   only; the title screen has no API, so there is no pre-campaign UI, ever.</li>
 *   <li>{@link Tier#POLICY} - host-authoritative gameplay rules. A value in the launch stack only
 *   <em>seeds</em> a new campaign; from then on {@link CoopOptionsPolicy} holds them in the
 *   campaign's own save and broadcasts them to the guest.</li>
 *   <li>{@link Tier#CLIENT} - local presentation preferences, never synced.</li>
 * </ul>
 */
public final class CoopOptionsRegistry {

    private CoopOptionsRegistry() {
    }

    /** Where an option lives and who owns its value. See the class javadoc. */
    public enum Tier {
        LAUNCH,
        POLICY,
        CLIENT
    }

    /**
     * When a changed value starts counting - the machine-readable half of {@code appliesAt}.
     *
     * <p>The rule the whole phase rests on: <b>nothing applies retroactively</b>. A consumer reads
     * {@link CoopOptionsPolicy#applied(String)}, never the pending value, and calls
     * {@link CoopOptionsPolicy#advanceBoundary(String)} at the moment named here - which is what
     * stops a mid-screen {@code pauseOnGuestScreens} flip from yanking the pause out from under a
     * screen the guest already has open.
     *
     * <p>{@link #IMMEDIATE} means there is no boundary to wait for: pending and applied are the same
     * value, and the policy promotes it on the spot.
     */
    public enum ApplyBoundary {
        /** Takes effect the moment it changes (presentation, and the Phase 25 pause strictness). */
        IMMEDIATE,
        /** The next time a vanilla core tab / dialog / menu opens or closes. */
        NEXT_SCREEN_TOGGLE,
        /** The next connection attempt - including "next launch", which is a connection attempt. */
        NEXT_CONNECTION,
        /** The next link drop, i.e. the next time a grace window opens. */
        NEXT_DROP,
        /** The next battle result to divide (Phase 22). */
        NEXT_BATTLE_RESULT,
        /** The next monthly income tick (Phase 24). */
        NEXT_MONTH_TICK,
        /** The next colony founding (Phase 24). */
        NEXT_COLONIZATION
    }

    /** How a raw string is validated. */
    public enum Type {
        BOOL,
        INT,
        STRING,
        ENUM
    }

    /**
     * The outcome of validating one raw value: always a usable {@link #value()}, plus a
     * {@link #warning()} when the raw input had to be clamped or discarded. Callers log the warning
     * once per key and carry on - a typo in a settings file must never stop the game from starting.
     */
    public record Coercion(String value, String warning) {
        public Coercion {
            Objects.requireNonNull(value, "value");
        }

        /** True when the raw value was accepted as-is (modulo trimming and enum canonicalisation). */
        public boolean clean() {
            return warning == null;
        }
    }

    /**
     * One schema entry.
     *
     * @param key           the {@code coop.*} name, identical as a JVM property and as a JSON field
     * @param type          validation kind
     * @param tier          where the value lives
     * @param defaultValue  the value used when no layer supplies one; {@code ""} means "unset"
     * @param allowsEmpty   whether an explicit empty value is meaningful (an unset host port, no
     *                      password) rather than a mistake
     * @param dOnly         {@code true} for the keys that stay {@code -D}-only forever: one-shot
     *                      consent gestures whose friction is the feature, and debug escape hatches
     *                      that must not be discoverable as ordinary settings.
     *                      {@link CoopOptionsStore} never reads a file for these.
     * @param min           inclusive lower bound for {@link Type#INT}
     * @param max           inclusive upper bound for {@link Type#INT}
     * @param allowedValues canonical values for {@link Type#ENUM}, matched case-insensitively
     * @param owner         the phase that owns the behaviour behind the key
     * @param appliesAt     the apply boundary in words, for the file and the page
     * @param boundary      the same boundary the policy layer enforces; see {@link ApplyBoundary}
     * @param description   one line for the shipped defaults file and the options page
     */
    public record Option(String key, Type type, Tier tier, String defaultValue, boolean allowsEmpty,
                         boolean dOnly, int min, int max, List<String> allowedValues,
                         String owner, String appliesAt, ApplyBoundary boundary, String description) {

        public Option {
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(appliesAt, "appliesAt");
            Objects.requireNonNull(description, "description");
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            if (type == Type.ENUM && allowedValues.isEmpty()) {
                throw new IllegalArgumentException(key + ": an ENUM option needs allowed values");
            }
            if (type == Type.INT && min > max) {
                throw new IllegalArgumentException(key + ": min " + min + " > max " + max);
            }
        }

        /**
         * Validates one raw value from any layer. Never throws and never returns null: an
         * out-of-range integer clamps to the nearest bound, anything else unusable falls back to
         * {@link #defaultValue()}, and either way the reason comes back in
         * {@link Coercion#warning()}.
         *
         * <p>A blank value is read as "not set" rather than as a bad value, because that is what an
         * empty field in a JSON file or a bare {@code -Dcoop.password=} actually means.
         */
        public Coercion coerce(String raw) {
            if (raw == null) {
                return new Coercion(defaultValue, null);
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return new Coercion(allowsEmpty ? "" : defaultValue, null);
            }
            switch (type) {
                case BOOL:
                    if (trimmed.equalsIgnoreCase("true")) {
                        return new Coercion("true", null);
                    }
                    if (trimmed.equalsIgnoreCase("false")) {
                        return new Coercion("false", null);
                    }
                    return new Coercion(defaultValue,
                            key + "=" + trimmed + " is not true or false; using " + describeDefault());
                case INT: {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(trimmed);
                    } catch (NumberFormatException ex) {
                        return new Coercion(defaultValue,
                                key + "=" + trimmed + " is not an integer; using " + describeDefault());
                    }
                    if (parsed < min) {
                        return new Coercion(String.valueOf(min),
                                key + "=" + parsed + " is below the minimum " + min
                                        + "; clamped to " + min);
                    }
                    if (parsed > max) {
                        return new Coercion(String.valueOf(max),
                                key + "=" + parsed + " is above the maximum " + max
                                        + "; clamped to " + max);
                    }
                    return new Coercion(String.valueOf(parsed), null);
                }
                case ENUM:
                    for (String allowed : allowedValues) {
                        if (allowed.equalsIgnoreCase(trimmed)) {
                            return new Coercion(allowed, null);
                        }
                    }
                    return new Coercion(defaultValue, key + "=" + trimmed + " is not one of "
                            + String.join("/", allowedValues) + "; using " + describeDefault());
                case STRING:
                default:
                    return new Coercion(trimmed, null);
            }
        }

        private String describeDefault() {
            return defaultValue.isEmpty() ? "the default (unset)" : defaultValue;
        }

        /** Human-readable type plus constraints, for the shipped file's comments. */
        public String constraintText() {
            switch (type) {
                case BOOL:
                    return "true|false";
                case INT:
                    return "integer " + min + ".." + max;
                case ENUM:
                    return String.join("|", allowedValues);
                case STRING:
                default:
                    return "text";
            }
        }
    }

    /** One entry of the correctness list: a knob that deliberately does not exist. */
    public record NotConfigurable(String name, String reason) {
        public NotConfigurable {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reason, "reason");
        }
    }

    // ---- launch / connection -----------------------------------------------------------------

    public static final String HOST_PORT = "coop.hostPort";
    public static final String CONNECT_HOST = "coop.connectHost";
    public static final String CONNECT_PORT = "coop.connectPort";
    public static final String PORT_MAPPING = "coop.portMapping";
    public static final String PASSWORD = "coop.password";
    public static final String PLAYER_NAME = "coop.playerName";

    // ---- host policy -------------------------------------------------------------------------

    public static final String MAX_GUESTS = "coop.maxGuests";
    public static final String RECONNECT_GRACE_SECONDS = "coop.reconnectGraceSeconds";
    public static final String ALLOW_GUEST_PAUSE = "coop.allowGuestPause";
    public static final String PAUSE_ON_GUEST_SCREENS = "coop.pauseOnGuestScreens";
    public static final String ALLOW_MID_SESSION_JOIN = "coop.allowMidSessionJoin";
    public static final String LOOT_SPLIT = "coop.lootSplit";
    public static final String INCOME_SPLIT = "coop.incomeSplit";
    public static final String GUEST_COLONIZATION_CONSENT = "coop.guestColonizationConsent";

    // ---- client preferences ------------------------------------------------------------------

    public static final String HUD_DISABLE = "coop.hud.disable";
    public static final String HUD_CORNER = "coop.hudCorner";
    public static final String FEED_VERBOSITY = "coop.feedVerbosity";
    public static final String PARTNER_COLOR = "coop.partnerColor";

    // ---- -D only, forever --------------------------------------------------------------------

    public static final String ADOPT_CAMPAIGN_ID = "coop.adoptCampaignId";
    public static final String ALLOW_GAME_VERSION_MISMATCH = "coop.allowGameVersionMismatch";
    public static final String NEW_GAME_SEED = "coop.newGameSeed";
    public static final String SECTOR_SIZE = "coop.sectorSize";
    public static final String SECTOR_AGE = "coop.sectorAge";
    public static final String FULL_FIDELITY_GUEST_SYSTEM = "coop.fullFidelityGuestSystem";
    public static final String FF_DISABLE = "coop.ff.disable";
    public static final String CLOCK_DISABLE = "coop.clock.disable";
    public static final String DEBUG_DIAGNOSTICS = "coop.debug.diagnostics";
    public static final String DEBUG_BRIDGE = "coop.debug.bridge";
    public static final String DEBUG_WIRETAP = "coop.debug.wiretap";
    public static final String DEBUG_WIRETAP_SAMPLE = "coop.debug.wiretapSample";
    public static final String DEBUG_FRAME_PROFILE = "coop.debug.frameProfile";
    public static final String DEBUG_INTERACTION_DELAY_MS = "coop.debug.interactionDelayMs";

    private static final Map<String, Option> BY_KEY;
    private static final List<Option> ORDERED;
    private static final List<NotConfigurable> NOT_CONFIGURABLE;

    static {
        List<Option> options = new ArrayList<>();

        // -- Tier 1: launch / connection -------------------------------------------------------
        options.add(intOption(HOST_PORT, Tier.LAUNCH, "", true, 1, 65535, "Phase 2",
                "next launch", ApplyBoundary.NEXT_CONNECTION,
                "TCP/UDP port this install listens on as HOST. Empty means \"not hosting\"."
                        + " Setting this and the connect keys together is refused, not guessed."));
        options.add(stringOption(CONNECT_HOST, Tier.LAUNCH, "", true, "Phase 2 / 20",
                "next connect", ApplyBoundary.NEXT_CONNECTION,
                "Host address to join as GUEST (IPv4, IPv6 or name). Empty means \"not joining\"."));
        options.add(intOption(CONNECT_PORT, Tier.LAUNCH, "", true, 1, 65535, "Phase 2 / 20",
                "next connect", ApplyBoundary.NEXT_CONNECTION,
                "Host port to join as GUEST. Required whenever coop.connectHost is set."));
        options.add(enumOption(PORT_MAPPING, Tier.LAUNCH, "auto", List.of("auto", "off"), "Phase 20",
                "next launch", ApplyBoundary.NEXT_CONNECTION,
                "auto asks the router to forward the host port over UPnP; off skips the attempt."
                        + " Guests never map anything - only the host needs to be reachable."));
        options.add(stringOption(PASSWORD, Tier.LAUNCH, "", true, "Phase 20.4",
                "next connection attempt", ApplyBoundary.NEXT_CONNECTION,
                "Lobby password; empty means none. A gatekeeper, not encryption - the proof is"
                        + " SHA-256(password + host nonce) over a plaintext protocol, and what it"
                        + " buys is that a port scanner cannot join. Set it identically on both"
                        + " installs. Becomes a policy-tier value too once milestone 2 syncs"
                        + " policy."));
        options.add(stringOption(PLAYER_NAME, Tier.LAUNCH, "", true, "Phase 20.4",
                "next connection attempt", ApplyBoundary.NEXT_CONNECTION,
                "Name shown to your partner. Empty falls back to the local character's own name."));

        // -- Tier 2: host gameplay policy ------------------------------------------------------
        options.add(intOption(MAX_GUESTS, Tier.POLICY, "1", false, 1, 1, "Phase 20.5 / 27",
                "next connection attempt", ApplyBoundary.NEXT_CONNECTION,
                "Peer-table capacity. v1 supports exactly one guest: the transport is N-ready, the"
                        + " gameplay arbitration is not, so anything else is clamped with a warning."
                        + " Phase 27 raises the bound."));
        options.add(intOption(RECONNECT_GRACE_SECONDS, Tier.POLICY, "60", false, 0, 3600,
                "Phase 20.2", "next drop", ApplyBoundary.NEXT_DROP,
                "How long a dropped socket keeps the session alive before it really ends. 0 restores"
                        + " the pre-20.2 \"every drop ends the session\" behaviour. Each side runs"
                        + " its own timer and they are configured independently on purpose."));
        options.add(boolOption(ALLOW_GUEST_PAUSE, Tier.POLICY, "true", "Phase 25",
                "immediately", ApplyBoundary.IMMEDIATE,
                "Whether the guest may pause and unpause the shared world. INERT until Phase 25"
                        + " builds. Never gates the screen pause below - that would punish the guest"
                        + " silently."));
        options.add(boolOption(PAUSE_ON_GUEST_SCREENS, Tier.POLICY, "true",
                "Phase 28 (Phase 11 lever)", "next screen open/close", ApplyBoundary.NEXT_SCREEN_TOGGLE,
                "true is exact Phase 11 behaviour: the world stops while a guest browses the vanilla"
                        + " auto-pause screens (map/fleet/character/refit/cargo/intel). false lets"
                        + " the world keep running - your partner reads while time passes. Does NOT"
                        + " touch the two hardwired pause intents: interaction dialogs (the market"
                        + " open-snapshot model trades against open-time state, so that pause is"
                        + " correctness) and combat auto-pause. Wired in milestone 2."));
        options.add(boolOption(ALLOW_MID_SESSION_JOIN, Tier.POLICY, "true", "Phase 27",
                "next connection attempt", ApplyBoundary.NEXT_CONNECTION,
                "Whether a guest may join a campaign already in progress. INERT until Phase 27"
                        + " builds."));
        options.add(enumOption(LOOT_SPLIT, Tier.POLICY, "equal", List.of("equal"), "Phase 22",
                "next battle result", ApplyBoundary.NEXT_BATTLE_RESULT,
                "How salvage from a jointly fought battle is divided. INERT until Phase 22 builds,"
                        + " which is also what defines any value other than equal."));
        options.add(enumOption(INCOME_SPLIT, Tier.POLICY, "equal", List.of("equal", "host-banks"),
                "Phase 24", "next month tick", ApplyBoundary.NEXT_MONTH_TICK,
                "equal splits shared-faction colony income 50/50 (local upkeep stays with the"
                        + " owner); host-banks pays it all to the host. INERT until Phase 24 wires"
                        + " the key."));
        options.add(boolOption(GUEST_COLONIZATION_CONSENT, Tier.POLICY, "false", "Phase 24",
                "next colonization", ApplyBoundary.NEXT_COLONIZATION,
                "true makes a guest founding a colony ask the host first. Default false is the"
                        + " shipped trusted model. INERT until Phase 24 wires the key."));

        // -- Tier 3: per-client preferences ----------------------------------------------------
        options.add(boolOption(HUD_DISABLE, Tier.CLIENT, "false", "Phase 20.6",
                "immediately", ApplyBoundary.IMMEDIATE,
                "true hides the one-line link HUD. Purely local, and live since Phase 28 milestone"
                        + " 3: the HUD re-reads this on its own refresh tick, so a change from the"
                        + " options page shows up without a relaunch."));
        options.add(enumOption(HUD_CORNER, Tier.CLIENT, "TR", List.of("TR", "TL", "BR", "BL"),
                "Phase 20.6 / 21", "immediately", ApplyBoundary.IMMEDIATE,
                "Which screen corner the link HUD anchors to. Purely local, and live: re-read on"
                        + " the HUD's refresh tick."));
        options.add(enumOption(FEED_VERBOSITY, Tier.CLIENT, "all",
                List.of("all", "important", "minimal"), "Phase 20.6", "immediately", ApplyBoundary.IMMEDIATE,
                "How much of the coop event feed is shown. INERT until the feed reads the key."));
        options.add(stringOption(PARTNER_COLOR, Tier.CLIENT, "", true, "Phase 8", "immediately", ApplyBoundary.IMMEDIATE,
                "Colour used for your partner's presence marker. Empty is the built-in preset;"
                        + " Phase 8 defines the named vocabulary when it wires the key."));

        // -- -D only, forever ------------------------------------------------------------------
        // One-shot consent gestures (the friction IS the feature) and debug escape hatches. They
        // are listed here so the inventory is complete and the options page can explain them. They
        // are never in the shipped defaults and never writable through CoopOptionsStore.writeOverrides:
        // a standing entry would turn a deliberate one-time gesture into a setting, and would make a
        // debug hatch look like an ordinary preference.
        //
        // Since Phase 31 they ARE read out of the player's own saves/common/coop_options.json.data,
        // because the launcher cannot set a -D and has no other channel. CoopOptionsStore.rawOneShot
        // is that seam and CoopModPlugin republishes what it finds as system properties; what keeps a
        // gesture from becoming a setting is CoopOptionsStore.consumeOneShot, which strikes a consent
        // key out of the file as soon as it has been published for the launch that asked for it.
        // Their defaults below are documentation - the owning class still reads its own property
        // directly.
        options.add(dOnly(ADOPT_CAMPAIGN_ID, Type.BOOL, "false", "Phase 6b",
                "next new game", ApplyBoundary.NEXT_CONNECTION,
                "Overrides the seed lock and adopts an in-flight campaign id. One-shot explicit"
                        + " consent: it loses the other player's progress, so it must be typed."));
        options.add(dOnly(ALLOW_GAME_VERSION_MISMATCH, Type.BOOL, "false", "Phase 31",
                "next launch", ApplyBoundary.NEXT_CONNECTION,
                "Lets the mod run on a Starsector version other than the one it was built for. For"
                        + " testing a new release candidate before the forks are updated."));
        options.add(dOnly(NEW_GAME_SEED, Type.STRING, "", "Phase 6",
                "next new game", ApplyBoundary.NEXT_CONNECTION,
                "Pins the sector seed so both installs generate the same sector. One-shot."));
        options.add(dOnly(SECTOR_SIZE, Type.STRING, "", "Phase 21",
                "next new game", ApplyBoundary.NEXT_CONNECTION,
                "small|normal - pins the new-game sector size on both installs. One-shot."));
        options.add(dOnly(SECTOR_AGE, Type.STRING, "", "Phase 21",
                "next new game", ApplyBoundary.NEXT_CONNECTION,
                "Star age (a StarAge constant, or mixed) pinned on both installs. One-shot."));
        options.add(dOnly(FULL_FIDELITY_GUEST_SYSTEM, Type.BOOL, "true", "Phase 29",
                "next launch", ApplyBoundary.NEXT_CONNECTION,
                "Kill switch for the full-fidelity guest-system driver. A fidelity lever, not a"
                        + " preference - see the correctness list."));
        options.add(dOnly(FF_DISABLE, Type.BOOL, "false", "Phase 7b",
                "next launch", ApplyBoundary.NEXT_CONNECTION,
                "Forces the shared fast-forward lock sticky-unavailable (pre-7b behaviour)."));
        options.add(dOnly(CLOCK_DISABLE, Type.BOOL, "false", "Phase 7c",
                "next launch", ApplyBoundary.NEXT_CONNECTION,
                "Disables the clock reconciler and restores uncorrected drift (pre-7c behaviour)."));
        options.add(dOnly(DEBUG_DIAGNOSTICS, Type.BOOL, "false", "Phase 8",
                "immediately", ApplyBoundary.IMMEDIATE,
                "Master switch for the dormant diagnostics (orbit dumps, dialog state, probes)."));
        options.add(dOnly(DEBUG_BRIDGE, Type.INT, "0", "Phase 30",
                "next campaign load", ApplyBoundary.NEXT_CONNECTION,
                "Port for the 127.0.0.1 agent bridge. Absent, 0 or unparsable means no socket"
                        + " ever."));
        options.add(dOnly(DEBUG_WIRETAP, Type.BOOL, "false", "Phase 20.1",
                "immediately", ApplyBoundary.IMMEDIATE,
                "Datagram wiretap: per-type size histograms against the 1200 B WAN budget."));
        options.add(dOnly(DEBUG_WIRETAP_SAMPLE, Type.INT, "10", "Phase 20.1",
                "immediately", ApplyBoundary.IMMEDIATE,
                "Wiretap sampling interval: log every Nth datagram per (direction, type)."));
        options.add(dOnly(DEBUG_FRAME_PROFILE, Type.BOOL, "false", "Phase 29",
                "immediately", ApplyBoundary.IMMEDIATE,
                "Per-frame pump profiler."));
        options.add(dOnly(DEBUG_INTERACTION_DELAY_MS, Type.INT, "0", "Phase 18",
                "immediately", ApplyBoundary.IMMEDIATE,
                "Makes the host hold every inbound INTERACTION_CLAIM this many ms, widening the"
                        + " claim race to something a human can hit. A test instrument."));

        Map<String, Option> byKey = new LinkedHashMap<>();
        for (Option option : options) {
            Option previous = byKey.put(option.key(), option);
            if (previous != null) {
                throw new IllegalStateException("Duplicate coop option key: " + option.key());
            }
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
        ORDERED = List.copyOf(options);

        NOT_CONFIGURABLE = List.of(
                new NotConfigurable("snapshot and stream cadences (10 Hz fleet mirrors, 5 Hz time,"
                        + " 2-5 Hz battle status)",
                        "QA'd rates, not preferences. Phase 29 adapts the fleet-motion streams"
                                + " automatically between certified tiers - the link picks, never"
                                + " the player."),
                new NotConfigurable("fast-forward AND-over-intents semantics",
                        "Forcing fast-forward on a player skips content they cannot get back."),
                new NotConfigurable("combat auto-pause",
                        "The campaign does not advance during a battle on either side; making that"
                                + " optional desynchronises the world by construction."),
                new NotConfigurable("interaction-gate and claim arbitration",
                        "Single-authority arbitration is what stops two players trading against the"
                                + " same market state. A knob here is a duplication bug."),
                new NotConfigurable("seed lock, sector fingerprint and campaign-identity checks",
                        "These are the checks that catch two clients playing different worlds."
                                + " coop.adoptCampaignId is the one sanctioned override and it stays"
                                + " -D-only."),
                new NotConfigurable("the NPC fleet suppressor",
                        "The guest's own spawners must stay off or both sectors populate"
                                + " independently."),
                new NotConfigurable("iron-mode refusal",
                        "Ironman's single-save contract cannot be honoured across two installs;"
                                + " allowing it would silently corrupt one of them."),
                new NotConfigurable("host authority itself",
                        "There is no guest-authority mode to switch to - the whole replication model"
                                + " is built on one authoritative engine."));
    }

    /** Every option, in registration order (launch, policy, client, then the -D-only set). */
    public static List<Option> options() {
        return ORDERED;
    }

    /** The option for {@code key}, or {@code null} if the key is not registered. */
    public static Option option(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /**
     * The option for {@code key}; throws when it is not registered. Reading an unregistered key is a
     * programming error (a typo in mod code), not a user mistake, so it fails loudly.
     */
    public static Option require(String key) {
        Option option = option(key);
        if (option == null) {
            throw new IllegalArgumentException("Unknown coop option key: " + key);
        }
        return option;
    }

    public static boolean isRegistered(String key) {
        return option(key) != null;
    }

    /** Options in one tier, in registration order. */
    public static List<Option> byTier(Tier tier) {
        List<Option> result = new ArrayList<>();
        for (Option option : ORDERED) {
            if (option.tier() == tier) {
                result.add(option);
            }
        }
        return List.copyOf(result);
    }

    /** The options that appear in the shipped defaults file: everything except the -D-only set. */
    public static List<Option> fileBackedOptions() {
        List<Option> result = new ArrayList<>();
        for (Option option : ORDERED) {
            if (!option.dOnly()) {
                result.add(option);
            }
        }
        return List.copyOf(result);
    }

    /**
     * The correctness list: things that are deliberately not options, with the reason for each.
     * Documentation only - nothing reads it at runtime. A request to make one of these a knob is a
     * design change to its owning phase, not a registry entry.
     */
    public static List<NotConfigurable> notConfigurable() {
        return NOT_CONFIGURABLE;
    }

    // ---- entry helpers -----------------------------------------------------------------------

    private static Option boolOption(String key, Tier tier, String defaultValue, String owner,
                                     String appliesAt, ApplyBoundary boundary, String description) {
        return new Option(key, Type.BOOL, tier, defaultValue, false, false, 0, 0, List.of(), owner,
                appliesAt, boundary, description);
    }

    private static Option intOption(String key, Tier tier, String defaultValue, boolean allowsEmpty,
                                    int min, int max, String owner, String appliesAt,
                                    ApplyBoundary boundary, String description) {
        return new Option(key, Type.INT, tier, defaultValue, allowsEmpty, false, min, max, List.of(),
                owner, appliesAt, boundary, description);
    }

    private static Option stringOption(String key, Tier tier, String defaultValue,
                                       boolean allowsEmpty, String owner, String appliesAt,
                                       ApplyBoundary boundary, String description) {
        return new Option(key, Type.STRING, tier, defaultValue, allowsEmpty, false, 0, 0, List.of(),
                owner, appliesAt, boundary, description);
    }

    private static Option enumOption(String key, Tier tier, String defaultValue,
                                     List<String> allowedValues, String owner, String appliesAt,
                                     ApplyBoundary boundary, String description) {
        return new Option(key, Type.ENUM, tier, defaultValue, false, false, 0, 0, allowedValues,
                owner, appliesAt, boundary, description);
    }

    private static Option dOnly(String key, Type type, String defaultValue, String owner,
                                String appliesAt, ApplyBoundary boundary, String description) {
        boolean allowsEmpty = type == Type.STRING;
        int max = type == Type.INT ? Integer.MAX_VALUE : 0;
        return new Option(key, type, Tier.LAUNCH, defaultValue, allowsEmpty, true, 0, max,
                List.of(), owner, appliesAt, boundary, description);
    }
}
