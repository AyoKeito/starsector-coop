package coop.ui;

import coop.config.CoopOptionsRegistry;
import coop.net.CoopConnectionRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 28 milestone 3: everything the coop options page decides, with no engine call in it.
 *
 * <p>Same split as {@link CoopSessionStatsView}: this class works out which rows exist, what each
 * one currently says, whether this client may change it and what pressing its button would do;
 * {@link CoopOptionsPage} maps the result onto widgets and does nothing else. If a value or a label
 * is wrong on screen, it is wrong here, and it is wrong in a unit test that needs no game.
 *
 * <h2>Who may edit what</h2>
 * <ul>
 *   <li><b>Policy</b> rows belong to the campaign and are edited by the client that owns it - which
 *   is everyone except a guest. A guest sees the value with {@link #TAG_HOST_SETTING} and no
 *   button, because "the button is absent, not an error" is the acceptance wording.</li>
 *   <li><b>Client</b> rows are local preferences and are always editable; they are written to
 *   {@code saves/common/coop_options.json.data}.</li>
 *   <li><b>Launch</b> rows are read before any session exists, so they are editable only while no
 *   session is running - changing one mid-session could not do anything but mislead.</li>
 *   <li>A key given as {@code -D} on the command line is read-only wherever the {@code -D} is the
 *   value in force, tagged {@link #TAG_COMMAND_LINE}: the command line is the top of the install
 *   precedence stack, and a page that let you "change" a value the next read would override is a
 *   lie. A policy key of a campaign that has already been seeded is the one case where the
 *   {@code -D} is <em>not</em> in force - the campaign's stored value wins over it - so that row
 *   stays a normal campaign row.</li>
 * </ul>
 *
 * <h2>What the page cannot do</h2>
 *
 * <p>The intel-button surface has buttons, not text fields, so free-text options (an address, a
 * name, a password) cannot be typed here. They render read-only with a pointer at the settings
 * file. The one exception is {@code coop.password}, which gets a single confirm-guarded
 * <em>Clear</em> button, because "I need to turn the password off" is the one text-setting change
 * that is urgent enough to be worth a button that needs no keyboard.
 */
public record CoopOptionsView(List<Section> sections) {

    public CoopOptionsView {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** What pressing a row's button does. {@link #NONE} means the row renders without one. */
    public enum Control {
        NONE,
        /** Boolean: one button that flips it. */
        TOGGLE,
        /** Enum: one button that walks the allowed values in order. */
        CYCLE,
        /** Integer: a {@code -} and a {@code +} within the registry's bounds. */
        STEPPER,
        /** Text that can only be emptied, not typed: {@code coop.password}. */
        CLEAR
    }

    /** Where a row's displayed value came from, in the words the page prints. */
    public static final String TAG_COMMAND_LINE = "(command line)";
    public static final String TAG_HOST_SETTING = "(host setting)";
    public static final String TAG_CAMPAIGN = "(this campaign)";
    public static final String TAG_LOCAL = "(your settings)";
    public static final String TAG_DEFAULT = "(default)";

    /** The note on a launch row while a session is running. */
    public static final String NOTE_NEXT_LAUNCH = "takes effect at next launch";
    /** The note on a text row the page cannot edit. */
    public static final String NOTE_FILE_ONLY = "text setting - edit saves/common/coop_options.json.data";

    /** Section titles. */
    public static final String SECTION_POLICY = "Session rules (host)";
    public static final String SECTION_CLIENT = "Your preferences";
    public static final String SECTION_LAUNCH = "Connection (read at launch)";

    /** Shown instead of the rows when the registry itself cannot be read. */
    public static final String UNAVAILABLE_LINE = "Coop options are unavailable.";

    /**
     * The keys a change to is worth stopping the player over. The set is short on purpose: a confirm
     * dialog on every row trains the player to dismiss it, which is the same as having none.
     */
    public static final Set<String> CONFIRM_REQUIRED = Set.of(
            CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS,
            CoopOptionsRegistry.PASSWORD);

    /**
     * Policy keys whose owning phase has not built, so the value is stored, synced and shown but
     * nothing reads it. Explicit rather than sniffed out of the description text, because the page
     * saying "this does nothing yet" has to be exactly right.
     */
    public static final Set<String> INERT_KEYS = Set.of(
            CoopOptionsRegistry.ALLOW_GUEST_PAUSE,
            CoopOptionsRegistry.ALLOW_MID_SESSION_JOIN,
            CoopOptionsRegistry.LOOT_SPLIT,
            CoopOptionsRegistry.INCOME_SPLIT,
            CoopOptionsRegistry.GUEST_COLONIZATION_CONSENT,
            CoopOptionsRegistry.FEED_VERBOSITY,
            CoopOptionsRegistry.PARTNER_COLOR);

    /**
     * Policy keys that are still read at launch by {@code CoopNetStartupConfig} rather than from the
     * campaign policy. They sync and display so both players can see what the session was configured
     * with, but the value in force on each install is that install's launch setting.
     */
    public static final Set<String> LAUNCH_READ_POLICY_KEYS = Set.of(
            CoopOptionsRegistry.MAX_GUESTS,
            CoopOptionsRegistry.RECONNECT_GRACE_SECONDS);

    /** Short, player-facing names. The registry's description is the long form. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    /** Stepper increments; a key that is absent (or 0) gets no stepper. */
    private static final Map<String, Integer> STEPS = new LinkedHashMap<>();

    static {
        LABELS.put(CoopOptionsRegistry.HOST_PORT, "Host port");
        LABELS.put(CoopOptionsRegistry.CONNECT_HOST, "Join address");
        LABELS.put(CoopOptionsRegistry.CONNECT_PORT, "Join port");
        LABELS.put(CoopOptionsRegistry.PORT_MAPPING, "Router port mapping");
        LABELS.put(CoopOptionsRegistry.PASSWORD, "Session password");
        LABELS.put(CoopOptionsRegistry.PLAYER_NAME, "Your display name");
        LABELS.put(CoopOptionsRegistry.MAX_GUESTS, "Maximum guests");
        LABELS.put(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, "Reconnect grace");
        LABELS.put(CoopOptionsRegistry.ALLOW_GUEST_PAUSE, "Guest may pause the world");
        LABELS.put(CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS, "Pause while a guest reads a screen");
        LABELS.put(CoopOptionsRegistry.ALLOW_MID_SESSION_JOIN, "Allow joining a session in progress");
        LABELS.put(CoopOptionsRegistry.LOOT_SPLIT, "Battle loot split");
        LABELS.put(CoopOptionsRegistry.INCOME_SPLIT, "Colony income split");
        LABELS.put(CoopOptionsRegistry.GUEST_COLONIZATION_CONSENT, "Guest asks before colonizing");
        LABELS.put(CoopOptionsRegistry.HUD_DISABLE, "Hide the link HUD");
        LABELS.put(CoopOptionsRegistry.HUD_CORNER, "Link HUD corner");
        LABELS.put(CoopOptionsRegistry.FEED_VERBOSITY, "Event feed detail");
        LABELS.put(CoopOptionsRegistry.PARTNER_COLOR, "Partner marker colour");

        // 15 s is the granularity a reconnect window is actually chosen at; 1 s steps would mean 60
        // presses to cross the default. Ports get no stepper at all - nobody finds 7777 by clicking.
        STEPS.put(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS, 15);
    }

    /** The label for {@code key}; falls back to the key itself for anything unlabelled. */
    public static String label(String key) {
        String label = LABELS.get(key);
        return label == null ? String.valueOf(key) : label;
    }

    /**
     * One line of state the page renders.
     *
     * @param key           the registry key
     * @param label         the short player-facing name
     * @param tier          which tier the row belongs to
     * @param valueText     the value as the player reads it ("on", "60 s", "not set")
     * @param rawValue      the same value as the registry stores it, for the button maths
     * @param sourceTag     where the value came from, in parentheses
     * @param editable      whether this client may change it here
     * @param control       which widget the page draws
     * @param confirm       whether the change goes behind a confirm dialog
     * @param pendingNote   "" unless a change is waiting for this key's apply boundary
     * @param note          a caveat worth printing under the row, or ""
     * @param description   the registry's long description, for the row's tooltip text
     */
    public record Row(String key, String label, CoopOptionsRegistry.Tier tier, String valueText,
                      String rawValue, String sourceTag, boolean editable, Control control,
                      boolean confirm, String pendingNote, String note, String description) {
        public Row {
            Objects.requireNonNull(key, "key");
        }
    }

    /** A tier's worth of rows, with the one-line explanation of what the tier means. */
    public record Section(String title, String subtitle, List<Row> rows) {
        public Section {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * Everything the view needs to read, as four questions. Implemented over the live policy and
     * store by the page, and over plain maps by the tests.
     */
    public interface Reader {
        /** The campaign policy's current value for a policy key, or null when there is no policy. */
        String policyValue(String key);

        /** True when a policy change is waiting for its apply boundary. */
        boolean policyPending(String key);

        /** The install-level stack's value (command line, then files, then the default). */
        String localValue(String key);

        /** True when the key is set on the command line, which outranks anything editable here. */
        boolean commandLine(String key);

        /** True when this client's value for the key comes from the user's own settings file. */
        boolean userFile(String key);
    }

    /** Builds the page's model for one client, in one role, at one moment. */
    public static CoopOptionsView of(CoopConnectionRole role, boolean sessionActive, Reader reader) {
        Objects.requireNonNull(reader, "reader");
        boolean guest = role == CoopConnectionRole.GUEST;
        List<Section> sections = new ArrayList<>();
        sections.add(new Section(SECTION_POLICY, guest
                ? "Set by the host. You see them here so you know the rules you are playing under."
                : "These travel with this campaign's save, not with your install.",
                rows(CoopOptionsRegistry.Tier.POLICY, role, sessionActive, reader)));
        sections.add(new Section(SECTION_CLIENT, "Local to this install; never sent to your partner.",
                rows(CoopOptionsRegistry.Tier.CLIENT, role, sessionActive, reader)));
        sections.add(new Section(SECTION_LAUNCH, sessionActive
                ? "Read before a session starts, so changes here apply to the next one."
                : "Read before a session starts. Editing one here sets your install default.",
                rows(CoopOptionsRegistry.Tier.LAUNCH, role, sessionActive, reader)));
        return new CoopOptionsView(sections);
    }

    private static List<Row> rows(CoopOptionsRegistry.Tier tier, CoopConnectionRole role,
                                  boolean sessionActive, Reader reader) {
        List<Row> rows = new ArrayList<>();
        for (CoopOptionsRegistry.Option option : CoopOptionsRegistry.byTier(tier)) {
            if (option.dOnly()) {
                // -D-only keys are not settings and are deliberately not discoverable as such.
                continue;
            }
            rows.add(row(option, role, sessionActive, reader));
        }
        return rows;
    }

    private static Row row(CoopOptionsRegistry.Option option, CoopConnectionRole role,
                           boolean sessionActive, Reader reader) {
        String key = option.key();
        boolean policy = option.tier() == CoopOptionsRegistry.Tier.POLICY;
        boolean guest = role == CoopConnectionRole.GUEST;
        boolean commandLine = reader.commandLine(key);

        String value;
        String tag;
        // A -D only rules the row when it is what supplied the value in force. On a policy key of a
        // campaign that has already been seeded it did not: the stored campaign value wins over the
        // install default from the seeding onwards (CoopOptionsPolicy.ensureSeeded), so tagging that
        // row "(command line)" named the wrong source, and locking it stopped the host editing a
        // campaign rule policy.set would have taken.
        boolean commandLineInForce = commandLine;
        if (policy) {
            String fromPolicy = reader.policyValue(key);
            commandLineInForce = commandLine && fromPolicy == null;
            value = fromPolicy == null ? option.coerce(reader.localValue(key)).value() : fromPolicy;
            tag = guest ? TAG_HOST_SETTING : (fromPolicy == null ? TAG_DEFAULT : TAG_CAMPAIGN);
        } else {
            value = option.coerce(reader.localValue(key)).value();
            tag = reader.userFile(key) ? TAG_LOCAL : TAG_DEFAULT;
        }
        if (commandLineInForce) {
            tag = TAG_COMMAND_LINE;
        }

        boolean editable;
        if (commandLineInForce) {
            editable = false;
        } else if (policy) {
            // Everyone except a guest owns their own campaign's rules - including a client that has
            // not started hosting yet, which is exactly when a host wants to set them.
            editable = !guest;
        } else if (option.tier() == CoopOptionsRegistry.Tier.LAUNCH) {
            editable = !sessionActive;
        } else {
            editable = true;
        }

        Control control = editable ? control(option) : Control.NONE;
        if (control == Control.NONE) {
            editable = false;
        }

        return new Row(key, label(key), option.tier(), valueText(option, value), value, tag,
                editable, control, CONFIRM_REQUIRED.contains(key),
                policy && reader.policyPending(key)
                        ? "pending - applies " + option.appliesAt() : "",
                note(option, sessionActive, control), option.description());
    }

    private static Control control(CoopOptionsRegistry.Option option) {
        if (LAUNCH_READ_POLICY_KEYS.contains(option.key())) {
            // Read-only, and the row's own note says why. These sync and display, but the value in
            // force on each install is that install's launch setting, so a working stepper here
            // would spend a snapshot and a feed line on both clients to move a number nothing reads.
            return Control.NONE;
        }
        switch (option.type()) {
            case BOOL:
                return Control.TOGGLE;
            case ENUM:
                return option.allowedValues().size() > 1 ? Control.CYCLE : Control.NONE;
            case INT: {
                Integer step = STEPS.get(option.key());
                return step != null && step > 0 && option.min() < option.max()
                        ? Control.STEPPER : Control.NONE;
            }
            case STRING:
            default:
                return CoopOptionsRegistry.PASSWORD.equals(option.key()) ? Control.CLEAR : Control.NONE;
        }
    }

    private static String note(CoopOptionsRegistry.Option option, boolean sessionActive,
                               Control control) {
        if (INERT_KEYS.contains(option.key())) {
            return "no effect in this build - " + option.owner() + " wires it";
        }
        if (LAUNCH_READ_POLICY_KEYS.contains(option.key())) {
            return "each install reads its own value at launch; shown here so both players can see it";
        }
        if (option.tier() == CoopOptionsRegistry.Tier.LAUNCH && sessionActive) {
            return NOTE_NEXT_LAUNCH;
        }
        if (control == Control.NONE && option.type() == CoopOptionsRegistry.Type.STRING) {
            return NOTE_FILE_ONLY;
        }
        if (control == Control.NONE && option.type() == CoopOptionsRegistry.Type.INT
                && !STEPS.containsKey(option.key())) {
            return option.min() == option.max()
                    ? "fixed at " + option.min() + " in this build" : NOTE_FILE_ONLY;
        }
        return "";
    }

    /** The value as a player reads it. */
    public static String valueText(CoopOptionsRegistry.Option option, String value) {
        String raw = value == null ? "" : value;
        if (CoopOptionsRegistry.PASSWORD.equals(option.key())) {
            // Never printed: the intel screen is shareable in a screenshot, and the password is the
            // one value here that a screenshot must not leak.
            return raw.isEmpty() ? "none" : "set";
        }
        switch (option.type()) {
            case BOOL:
                return Boolean.parseBoolean(raw) ? "on" : "off";
            case INT:
                if (raw.isEmpty()) {
                    return "not set";
                }
                return CoopOptionsRegistry.RECONNECT_GRACE_SECONDS.equals(option.key())
                        ? raw + " s" : raw;
            case ENUM:
            case STRING:
            default:
                return raw.isEmpty() ? "not set" : raw;
        }
    }

    /**
     * What a button press produces, or null when the press does nothing.
     *
     * @param direction {@code +1} for the toggle/cycle/plus press, {@code -1} for the stepper's
     *                  minus. Ignored by {@link Control#CLEAR}.
     */
    public static String nextValue(String key, String current, int direction) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.require(key);
        String raw = current == null ? "" : current;
        switch (option.type()) {
            case BOOL:
                return Boolean.parseBoolean(raw) ? "false" : "true";
            case ENUM: {
                List<String> allowed = option.allowedValues();
                if (allowed.size() < 2) {
                    return null;
                }
                int index = allowed.indexOf(option.coerce(raw).value());
                int step = direction < 0 ? -1 : 1;
                int next = ((index + step) % allowed.size() + allowed.size()) % allowed.size();
                return allowed.get(next);
            }
            case INT: {
                Integer step = STEPS.get(key);
                if (step == null || step <= 0) {
                    return null;
                }
                int value;
                try {
                    value = Integer.parseInt(option.coerce(raw).value());
                } catch (NumberFormatException ex) {
                    value = option.min();
                }
                int moved = value + (direction < 0 ? -step : step);
                int clamped = Math.max(option.min(), Math.min(option.max(), moved));
                return clamped == value ? null : String.valueOf(clamped);
            }
            case STRING:
            default:
                return CoopOptionsRegistry.PASSWORD.equals(key) ? "" : null;
        }
    }

    /**
     * The confirm-dialog text for a row, or "" when the row needs no confirmation.
     *
     * <p>Each one names the trade-off rather than asking "are you sure": a dialog that only says
     * "are you sure" is a dialog the player learns to click through.
     */
    public static String confirmPrompt(String key, String current) {
        if (CoopOptionsRegistry.PAUSE_ON_GUEST_SCREENS.equals(key)) {
            return Boolean.parseBoolean(current)
                    ? "Turn off the pause while a guest reads a screen?\n\nThe world moves while your"
                            + " partner reads: their map, cargo and refit screens will no longer stop"
                            + " time for either of you. Interaction dialogs and combat still pause."
                            + "\n\nThe change takes effect the next time a screen opens or closes,"
                            + " never underneath one that is already open."
                    : "Pause the world while a guest reads a screen?\n\nThis is the default: time"
                            + " stops for both players whenever either of you opens the map, cargo,"
                            + " refit or another core screen.";
        }
        if (CoopOptionsRegistry.PASSWORD.equals(key)) {
            return "Clear the session password?\n\nAnyone who can reach your host port will be able"
                    + " to join. Set a new one in saves/common/coop_options.json.data or with"
                    + " -Dcoop.password=... before you host again.";
        }
        // coop.reconnectGraceSeconds used to have a prompt here. It no longer has a control (see
        // LAUNCH_READ_POLICY_KEYS in control()), so there is nothing left to confirm.
        return "";
    }

    /**
     * The keys "Reset to defaults" drops from {@code saves/common/coop_options.json.data}.
     *
     * <p>{@link CoopOptionsRegistry.Tier#CLIENT} only. The launch tier - host port, join address,
     * join port, router port mapping, password, display name - is deliberately left alone: it is how
     * this install reaches its partner, it took typing into a file to set, and a player who presses
     * a button labelled "reset to defaults" on a page of gameplay preferences is not asking to be
     * disconnected the next time they launch. The policy tier is reset separately, on the campaign,
     * and only by the client that owns it.
     */
    public static List<String> resetKeys() {
        List<String> keys = new ArrayList<>();
        for (CoopOptionsRegistry.Option option
                : CoopOptionsRegistry.byTier(CoopOptionsRegistry.Tier.CLIENT)) {
            if (!option.dOnly()) {
                keys.add(option.key());
            }
        }
        return List.copyOf(keys);
    }

    /**
     * The confirm text for the Reset button.
     *
     * <p>Names what moves and what does not, because the button's own label ("Reset to defaults")
     * reads as "everything on this page" and that is not what it does.
     */
    public static String resetPrompt(boolean guest) {
        String local = "Your preferences - " + labelList(resetKeys())
                + " - go back to what the mod ships with. Your connection settings ("
                + labelList(launchKeys()) + ") are not touched, and neither is anything set on the"
                + " command line.";
        return guest
                ? "Reset your own preferences to the shipped defaults?\n\n" + local
                        + "\n\nThe session rules belong to the host and are not touched."
                : "Reset this campaign's session rules and your own preferences?\n\nEvery session"
                        + " rule in the first section goes back to its shipped value for this"
                        + " campaign.\n\n" + local;
    }

    private static List<String> launchKeys() {
        List<String> keys = new ArrayList<>();
        for (CoopOptionsRegistry.Option option
                : CoopOptionsRegistry.byTier(CoopOptionsRegistry.Tier.LAUNCH)) {
            if (!option.dOnly()) {
                keys.add(option.key());
            }
        }
        return keys;
    }

    private static String labelList(List<String> keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(label(key).toLowerCase(java.util.Locale.ROOT));
        }
        return text.toString();
    }

    /**
     * The feed line for a policy reset. One line for the whole sweep, and the same wording on both
     * sides: a reset moves several keys at once, so naming them would be a paragraph, and the host
     * reading "the host reset..." is clearer than a first-person line that does not say who.
     */
    public static final String RESET_LINE = "Co-op: the host reset the session rules.";

    /**
     * The feed line for a policy change, on both sides of the link.
     *
     * @param local true on the client that made the change; false on the one that was told about it
     */
    public static String changeLine(String key, String value, boolean local) {
        CoopOptionsRegistry.Option option = CoopOptionsRegistry.option(key);
        String text = option == null ? String.valueOf(value) : valueText(option, value);
        return local
                ? "Co-op: " + label(key) + " set to " + text + "."
                : "Co-op: the host set " + label(key) + " to " + text + ".";
    }
}
