package coop.ui;

import coop.net.CoopConnectionRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one of the mod's raw reject strings into the structured facts a desync dialog needs
 * (Phase 21, "Desync dialogs - three, not one").
 *
 * <p><b>Why a classifier at all.</b> The spec forbids one dialog with a swappable reason string:
 * every detectable cause gets its own message. The reject strings, though, are wire/log text written
 * for a diff, not for a player - {@code sectorFingerprint: host=<64 hex> guest=<64 hex>} tells a
 * developer everything and a player nothing. This class is the one place that reads those shapes, so
 * the dialogs can be written in plain language against typed fields instead of re-parsing text three
 * times.
 *
 * <p><b>Pure.</b> No engine calls, no logging, no state: a string and a {@link Source} in, a value
 * object out. That is what makes the whole desync path testable without a running game, and it is why
 * the parse can afford to be defensive - an unrecognised string is never an exception, it is
 * {@link Kind#UNMAPPED} carrying the raw text, which still produces a dialog rather than a silent
 * session end.
 *
 * <p><b>Absolute sides, role-aware phrasing.</b> Every producer writes {@code host=} / {@code guest=},
 * so the parsed fields keep those names. Turning them into "you" and "the host" needs to know which
 * side is reading, and that lives in the display helpers that take a {@link CoopConnectionRole} -
 * never in the parse, which must give the same answer on both machines so the two logs match.
 *
 * <p>The exact producers this reads, so a change over there is findable from here:
 * <ul>
 *   <li>{@code CoopHandshakeDiff.compare} - {@code gameVersion: host=.. guest=..},
 *   {@code coopBuildVersion:}, {@code coopGitCommit:}, {@code mod <id>: missing on guest},
 *   {@code mod <id>: extra on guest}, {@code mod <id>.<field>: host=.. guest=..},
 *   {@code mod <id>.checksum <file>: host=.. guest=..}</li>
 *   <li>{@code CoopNetPump.handshakeDiffFor} - {@code ironMode: host=true},
 *   {@code ironMode: guest=true}, {@code handshakeManifest: <exception>}</li>
 *   <li>{@code CoopSeedSync.seedStringMismatch} - {@code seedString: host=.. guest=..}</li>
 *   <li>{@code CoopSeedSync.fingerprintMismatch} - {@code sectorFingerprint: host=.. guest=..}</li>
 *   <li>{@code CoopNetPump.checkOrAdoptCampaignId} - {@code campaignId: host=.. guest=..; <advice>}</li>
 *   <li>{@code CoopReconnectCoordinator.rejectReason} and its {@code REASON_*} constants</li>
 * </ul>
 */
public final class CoopDesyncReason {

    /** Where the pump got the string, used to break ties and to pick a fallback kind. */
    public enum Source {
        /** Manifest comparison at handshake time (either side). */
        HANDSHAKE,
        /** Seed lock: seed string, sector fingerprint, campaign id. */
        SEED_LOCK,
        /** Reconnect grace: {@code SESSION_RESUME_REJECT} and the coordinator's terminal reasons. */
        SESSION_RESUME,
        /** Anything else that ends a session - lobby rejects, transport give-ups. */
        OTHER
    }

    /** Which dialog this reason gets, and the greppable support code that goes with it. */
    public enum Kind {
        /** Different sector: seed, fingerprint, or campaign identity. */
        SEED("COOP-SEED"),
        /** Different install: mods, game version, coop build. */
        MODS("COOP-MODS"),
        /**
         * This install's Starsector is not the one the mod was built for. Local and pre-session:
         * it is decided before anything is connected to, so it has no host/guest sides at all,
         * which is why it is its own code rather than a row inside {@link #MODS}.
         */
        GAME("COOP-GAME"),
        /** The session itself could not be resumed or continued. */
        SESSION("COOP-SESSION"),
        /**
         * Nothing recognised. Shares {@link #SESSION}'s code on purpose: the support codes are a
         * greppable set of three by design, and an unmapped reason is always a session that ended.
         * The raw text is on the marker line, which is what a helper actually reads.
         */
        UNMAPPED("COOP-SESSION");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        /** The {@code COOP-*} code printed in the dialog and in the doctor marker. */
        public String code() {
            return code;
        }
    }

    /** The detectable resume-reject causes, each of which gets its own dialog body. */
    public enum SessionCause {
        /** The grace window ran out, or the host is no longer holding one. */
        GRACE_EXPIRED,
        /** The host is on a different session/campaign than the one being resumed. */
        DIFFERENT_CAMPAIGN,
        /** The place in the session belongs to a different player. */
        SLOT_TAKEN,
        /** The host is mid-grace for somebody else; the only transient one. */
        HOST_IN_GRACE,
        /** A player pressed the end/give-up option. */
        ENDED_BY_PLAYER,
        /** Session-shaped but not one of the above. */
        OTHER
    }

    /** What kind of difference a single mod row represents; sides are absolute (host/guest). */
    public enum ModVerdict {
        /** Both sides have it at different versions. */
        VERSION_DIFFERS,
        /** The host has it, the guest does not. */
        MISSING_ON_GUEST,
        /** The guest has it, the host does not. */
        MISSING_ON_HOST,
        /** Same version on both sides, different file contents - the case users refuse to believe. */
        CONTENT_DIFFERS,
        /** Same version and contents, but some other manifest field differs (path, jars, name). */
        OTHER_DIFFERS
    }

    /** Which side is behind, when the version strings are comparable enough to tell. */
    public enum StaleSide {
        HOST,
        GUEST,
        UNKNOWN
    }

    /**
     * One mod's worth of difference, collapsed from however many diff lines mentioned it.
     *
     * @param modId        the mod id from the manifest
     * @param name         the display name when the diff happened to carry one, else empty
     * @param hostVersion  the host's version string, or empty when the host does not have the mod
     * @param guestVersion the guest's version string, or empty when the guest does not have it
     * @param verdict      what the difference is
     * @param detail       a short field name for {@link ModVerdict#OTHER_DIFFERS}, else empty
     */
    public record ModRow(String modId, String name, String hostVersion, String guestVersion,
                         ModVerdict verdict, String detail) {

        public ModRow {
            modId = trim(modId);
            name = trim(name);
            hostVersion = trim(hostVersion);
            guestVersion = trim(guestVersion);
            detail = trim(detail);
        }

        /** Name when the diff carried one, otherwise the id; never blank. */
        public String displayName() {
            return name.isEmpty() ? modId : name;
        }

        /** The reading side's own version. */
        public String localVersion(CoopConnectionRole role) {
            return role == CoopConnectionRole.HOST ? hostVersion : guestVersion;
        }

        /** The other player's version. */
        public String remoteVersion(CoopConnectionRole role) {
            return role == CoopConnectionRole.HOST ? guestVersion : hostVersion;
        }

        /**
         * Which side is behind. Only answers when both version strings carry digits and the digit
         * groups order cleanly; anything else is {@link StaleSide#UNKNOWN}, because guessing wrong
         * here means telling a player to update a mod that is already newer.
         */
        public StaleSide staleSide() {
            int cmp = compareVersions(hostVersion, guestVersion);
            if (cmp == INCOMPARABLE || cmp == 0) {
                return StaleSide.UNKNOWN;
            }
            return cmp < 0 ? StaleSide.HOST : StaleSide.GUEST;
        }

        /**
         * The spec's relative verdict phrase, written for whoever is reading it. Deliberately states
         * the other player's value too: "you have 2.7" alone is not actionable.
         */
        public String verdictText(CoopConnectionRole role) {
            boolean host = role == CoopConnectionRole.HOST;
            String partner = host ? "the guest" : "the host";
            return switch (verdict) {
                case VERSION_DIFFERS -> "you have " + orUnknown(localVersion(role))
                        + " / " + partner + " has " + orUnknown(remoteVersion(role));
                case MISSING_ON_GUEST -> host
                        ? "not installed on " + partner + "'s side"
                        : "not installed";
                case MISSING_ON_HOST -> host
                        ? "not installed here, only on " + partner + "'s side"
                        : "not on host - disable it";
                case CONTENT_DIFFERS -> "same version, different contents";
                case OTHER_DIFFERS -> detail.isEmpty()
                        ? "installed differently on the two sides"
                        : "installed differently on the two sides (" + detail + ")";
            };
        }

        /** The row's own remedy verb, in the reading side's terms. */
        public String remedyText(CoopConnectionRole role) {
            boolean host = role == CoopConnectionRole.HOST;
            String partner = host ? "the guest" : "the host";
            return switch (verdict) {
                case VERSION_DIFFERS -> versionRemedy(role, partner);
                case MISSING_ON_GUEST -> host
                        ? "ask " + partner + " to install it"
                        : "install it and enable it in the launcher";
                case MISSING_ON_HOST -> host
                        ? "ask " + partner + " to disable it"
                        : "disable it in the launcher";
                case CONTENT_DIFFERS -> "reinstall it from the same download " + partner + " used";
                case OTHER_DIFFERS -> "reinstall it from the same download " + partner + " used";
            };
        }

        private String versionRemedy(CoopConnectionRole role, String partner) {
            StaleSide stale = staleSide();
            boolean localIsHost = role == CoopConnectionRole.HOST;
            boolean localIsStale = stale == (localIsHost ? StaleSide.HOST : StaleSide.GUEST);
            if (stale != StaleSide.UNKNOWN && !localIsStale) {
                // Blame routed away from the reader when the reader is the up-to-date one; a player
                // told to "update" a mod that is already newer stops trusting the whole dialog.
                return "ask " + partner + " to update it to " + orUnknown(localVersion(role));
            }
            if (localIsHost) {
                // The host is authoritative, so an unclear comparison still points at the host: the
                // guest matching the host is always a correct fix, the reverse is not.
                return stale == StaleSide.UNKNOWN
                        ? "match versions with " + partner
                        : "update it to " + orUnknown(remoteVersion(role));
            }
            return "match the host: switch to " + orUnknown(remoteVersion(role));
        }

        private static String orUnknown(String version) {
            return version.isEmpty() ? "no version" : version;
        }
    }

    /** How many mod rows a dialog is allowed to print before the overflow line takes over. */
    public static final int MAX_MOD_ROWS = 8;

    /** Sentinel from {@link #compareVersions}: the two strings cannot be ordered. */
    private static final int INCOMPARABLE = Integer.MIN_VALUE;

    private static final String NO_REASON = "no reason recorded";

    private final Kind kind;
    private final Source source;
    private final String rawReason;

    // SEED
    private final String hostSeed;
    private final String guestSeed;
    private final String hostFingerprint;
    private final String guestFingerprint;
    private final boolean campaignIdMismatch;
    private final String hostCampaignId;
    private final String guestCampaignId;

    // MODS
    private final List<ModRow> modRows;
    private final int hiddenModRows;
    private final String hostGameVersion;
    private final String guestGameVersion;
    private final String hostCoopBuild;
    private final String guestCoopBuild;
    private final String ironModeSide;
    private final boolean manifestUnreadable;

    // GAME
    private final String modGameVersion;
    private final String installedGameVersion;

    // SESSION
    private final SessionCause sessionCause;
    private final int graceSeconds;
    private final boolean retryable;

    private CoopDesyncReason(Builder builder) {
        this.kind = builder.kind;
        this.source = builder.source;
        this.rawReason = builder.rawReason;
        this.hostSeed = builder.hostSeed;
        this.guestSeed = builder.guestSeed;
        this.hostFingerprint = builder.hostFingerprint;
        this.guestFingerprint = builder.guestFingerprint;
        this.campaignIdMismatch = builder.campaignIdMismatch;
        this.hostCampaignId = builder.hostCampaignId;
        this.guestCampaignId = builder.guestCampaignId;
        this.modRows = List.copyOf(builder.modRows);
        this.hiddenModRows = builder.hiddenModRows;
        this.hostGameVersion = builder.hostGameVersion;
        this.guestGameVersion = builder.guestGameVersion;
        this.hostCoopBuild = builder.hostCoopBuild;
        this.guestCoopBuild = builder.guestCoopBuild;
        this.ironModeSide = builder.ironModeSide;
        this.manifestUnreadable = builder.manifestUnreadable;
        this.modGameVersion = builder.modGameVersion;
        this.installedGameVersion = builder.installedGameVersion;
        this.sessionCause = builder.sessionCause;
        this.graceSeconds = builder.graceSeconds;
        this.retryable = builder.retryable;
    }

    /**
     * Reads one raw reject string.
     *
     * @param rawReason the exact text a producer wrote; null or blank is tolerated
     * @param source    where it came from; only used when the text itself is not decisive
     * @return never null, never a blank-bodied result
     */
    public static CoopDesyncReason classify(String rawReason, Source source) {
        Builder builder = new Builder();
        builder.source = source == null ? Source.OTHER : source;
        String raw = trim(rawReason);
        builder.rawReason = raw.isEmpty() ? NO_REASON : raw;

        List<String> lines = splitLines(raw);
        boolean gameShaped = parseGameVersion(builder, lines);
        boolean seedShaped = parseSeed(builder, lines);
        boolean modsShaped = parseMods(builder, lines);
        boolean sessionShaped = parseSession(builder, raw);

        // Local install beats identity beats install-comparison beats session, matching the order
        // the mod checks them in: the game-version check runs at application load, before anything
        // is connected to, so nothing else can be the cause when its line is present. After that, a
        // seed reject can only happen once the manifests already matched, so seed-shaped text is
        // never ambiguous, while a session string can appear inside anything.
        if (gameShaped) {
            builder.kind = Kind.GAME;
        } else if (seedShaped) {
            builder.kind = Kind.SEED;
        } else if (modsShaped) {
            builder.kind = Kind.MODS;
        } else if (sessionShaped) {
            builder.kind = Kind.SESSION;
        } else {
            builder.kind = switch (builder.source) {
                case HANDSHAKE -> Kind.MODS;
                case SEED_LOCK -> Kind.SEED;
                case SESSION_RESUME -> Kind.SESSION;
                case OTHER -> Kind.UNMAPPED;
            };
            if (builder.kind == Kind.SESSION && builder.sessionCause == null) {
                builder.sessionCause = SessionCause.OTHER;
            }
        }
        if (builder.kind != Kind.SESSION) {
            // Retry is a session-only affordance: seed and mod rejects are deterministic, and the
            // spec's no-auto-retry rule exists precisely because retrying them wastes the player's
            // time twice.
            builder.retryable = false;
        }
        return new CoopDesyncReason(builder);
    }

    /**
     * A copy carrying the grace window length the pump knows and the reason text does not. The
     * coordinator's {@code REASON_GRACE_EXPIRED} is a bare phrase, but the dialog is required to state
     * the window as a number, so the wiring wave supplies it here rather than by rewriting the
     * constant.
     *
     * @param seconds the window length in seconds; anything below zero clears it
     */
    public CoopDesyncReason withGraceSeconds(int seconds) {
        Builder builder = toBuilder();
        builder.graceSeconds = Math.max(-1, seconds);
        return new CoopDesyncReason(builder);
    }

    public Kind kind() {
        return kind;
    }

    public Source source() {
        return source;
    }

    /** The producer's text, verbatim except for trimming; never blank. */
    public String rawReason() {
        return rawReason;
    }

    /** The greppable support code for this reason. */
    public String code() {
        return kind.code();
    }

    public String hostSeed() {
        return hostSeed;
    }

    public String guestSeed() {
        return guestSeed;
    }

    public String hostFingerprint() {
        return hostFingerprint;
    }

    public String guestFingerprint() {
        return guestFingerprint;
    }

    /** True when the reject was about campaign identity rather than the sector itself. */
    public boolean campaignIdMismatch() {
        return campaignIdMismatch;
    }

    public String hostCampaignId() {
        return hostCampaignId;
    }

    /** The guest's stored campaign id, or empty when the guest had none. */
    public String guestCampaignId() {
        return guestCampaignId;
    }

    /** At most {@link #MAX_MOD_ROWS} rows, in manifest (id) order. */
    public List<ModRow> modRows() {
        return modRows;
    }

    /** How many further mods differed beyond the cap; zero when everything fit. */
    public int hiddenModRows() {
        return hiddenModRows;
    }

    public String hostGameVersion() {
        return hostGameVersion;
    }

    public String guestGameVersion() {
        return guestGameVersion;
    }

    public boolean gameVersionMismatch() {
        return !hostGameVersion.isEmpty() || !guestGameVersion.isEmpty();
    }

    public String hostCoopBuild() {
        return hostCoopBuild;
    }

    public String guestCoopBuild() {
        return guestCoopBuild;
    }

    public boolean coopBuildMismatch() {
        return !hostCoopBuild.isEmpty() || !guestCoopBuild.isEmpty();
    }

    /** "host", "guest", or empty: which side had iron mode on, which co-op refuses outright. */
    public String ironModeSide() {
        return ironModeSide;
    }

    /** True when the manifest could not even be read, so there are no rows to show. */
    public boolean manifestUnreadable() {
        return manifestUnreadable;
    }

    /** {@link Kind#GAME}: the Starsector version the mod was built for. */
    public String modGameVersion() {
        return modGameVersion;
    }

    /** {@link Kind#GAME}: the Starsector version actually running here. */
    public String installedGameVersion() {
        return installedGameVersion;
    }

    /** True when at least one mod matched on version but not on contents. */
    public boolean hasSameVersionDifferentContents() {
        return modRows.stream().anyMatch(row -> row.verdict() == ModVerdict.CONTENT_DIFFERS);
    }

    /** Non-null only for {@link Kind#SESSION}. */
    public SessionCause sessionCause() {
        return sessionCause;
    }

    /** Grace window in seconds, or -1 when neither the text nor the pump supplied one. */
    public int graceSeconds() {
        return graceSeconds;
    }

    /** True only for causes that can plausibly succeed on a second attempt. */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Eight hex characters of a fingerprint, hyphenated 4-4 so a player can read one out loud and the
     * other can type it. Sixty-four characters of SHA-256 is unreadable and unverifiable by a human;
     * eight is enough to tell two sectors apart in a support thread.
     *
     * @return e.g. {@code a1b2-c3d4}, or empty when there is nothing usable to shorten
     */
    public static String shortFingerprint(String fingerprint) {
        String value = trim(fingerprint).toLowerCase(Locale.ROOT);
        StringBuilder hex = new StringBuilder(8);
        for (int i = 0; i < value.length() && hex.length() < 8; i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                hex.append(c);
            } else {
                // A non-hex character means this is not a fingerprint at all; better to show nothing
                // than to show four characters of something else as if it were one.
                return "";
            }
        }
        if (hex.length() < 8) {
            return "";
        }
        return hex.substring(0, 4) + "-" + hex.substring(4, 8);
    }

    // ---------------------------------------------------------------- parsing

    /**
     * The one line {@code CoopGameVersionCheck.Result.rawReason()} writes:
     * {@code installedGameVersion: mod=<a> game=<b>}.
     *
     * <p>Its own parser rather than a case in {@link #parseMods}, because it carries {@code mod=}
     * and {@code game=} instead of {@code host=} and {@code guest=} - there is no other player in
     * this failure, and reusing the two-sided parser would print one machine's version as if it
     * belonged to the partner.
     */
    private static boolean parseGameVersion(Builder builder, List<String> lines) {
        String prefix = coop.handshake.CoopGameVersionCheck.REASON_PREFIX;
        boolean matched = false;
        for (String line : lines) {
            if (!line.startsWith(prefix)) {
                continue;
            }
            String rest = line.substring(prefix.length()).trim();
            int modAt = rest.indexOf("mod=");
            int gameAt = rest.lastIndexOf(" game=");
            if (modAt >= 0 && gameAt > modAt) {
                builder.modGameVersion = rest.substring(modAt + "mod=".length(), gameAt).trim();
                builder.installedGameVersion = rest.substring(gameAt + " game=".length()).trim();
            }
            matched = true;
        }
        return matched;
    }

    private static boolean parseSeed(Builder builder, List<String> lines) {
        boolean matched = false;
        for (String line : lines) {
            if (line.startsWith("seedString:")) {
                String[] sides = parseHostGuest(line.substring("seedString:".length()));
                builder.hostSeed = sides[0];
                builder.guestSeed = sides[1];
                matched = true;
            } else if (line.startsWith("sectorFingerprint:")) {
                String[] sides = parseHostGuest(line.substring("sectorFingerprint:".length()));
                builder.hostFingerprint = sides[0];
                builder.guestFingerprint = sides[1];
                matched = true;
            } else if (line.startsWith("campaignId:")) {
                String[] sides = parseHostGuest(line.substring("campaignId:".length()));
                builder.hostCampaignId = cutAtAdvice(sides[0]);
                String guest = cutAtAdvice(sides[1]);
                builder.guestCampaignId = "<none>".equals(guest) ? "" : guest;
                builder.campaignIdMismatch = true;
                matched = true;
            }
        }
        return matched;
    }

    private static boolean parseMods(Builder builder, List<String> lines) {
        Map<String, RowDraft> drafts = new LinkedHashMap<>();
        boolean matched = false;
        for (String line : lines) {
            if (line.startsWith("gameVersion:")) {
                String[] sides = parseHostGuest(line.substring("gameVersion:".length()));
                builder.hostGameVersion = sides[0];
                builder.guestGameVersion = sides[1];
                matched = true;
            } else if (line.startsWith("coopBuildVersion:") || line.startsWith("coopGitCommit:")) {
                String[] sides = parseHostGuest(line.substring(line.indexOf(':') + 1));
                if (builder.hostCoopBuild.isEmpty()) {
                    builder.hostCoopBuild = sides[0];
                    builder.guestCoopBuild = sides[1];
                }
                matched = true;
            } else if (line.startsWith("ironMode:")) {
                builder.ironModeSide = line.contains("host=") ? "host" : "guest";
                matched = true;
            } else if (line.startsWith("handshakeManifest:")) {
                builder.manifestUnreadable = true;
                matched = true;
            } else if (line.startsWith("mod ")) {
                parseModLine(drafts, line.substring("mod ".length()));
                matched = true;
            }
        }

        List<ModRow> rows = new ArrayList<>();
        for (RowDraft draft : drafts.values()) {
            rows.add(draft.toRow());
        }
        if (rows.size() > MAX_MOD_ROWS) {
            builder.hiddenModRows = rows.size() - MAX_MOD_ROWS;
            rows = rows.subList(0, MAX_MOD_ROWS);
        }
        builder.modRows = rows;
        return matched;
    }

    /**
     * One {@code mod ...} diff line, folded into whatever draft its id already has. The field suffix
     * is matched from the right because a mod id may itself contain a dot, and splitting on the first
     * one would attribute {@code org.example.mod.version} to a mod called {@code org}.
     */
    private static void parseModLine(Map<String, RowDraft> drafts, String rest) {
        int colon = rest.indexOf(": ");
        if (colon < 0) {
            return;
        }
        String head = rest.substring(0, colon);
        String tail = rest.substring(colon + 2).trim();

        int checksumAt = head.indexOf(".checksum ");
        if (checksumAt > 0) {
            draftFor(drafts, head.substring(0, checksumAt)).contentDiffers = true;
            return;
        }
        for (String field : new String[]{"name", "version", "gameVersion", "path", "jars"}) {
            String suffix = "." + field;
            if (head.endsWith(suffix) && head.length() > suffix.length()) {
                RowDraft draft = draftFor(drafts, head.substring(0, head.length() - suffix.length()));
                String[] sides = parseHostGuest(tail);
                switch (field) {
                    case "name" -> {
                        draft.name = sides[0].isEmpty() ? sides[1] : sides[0];
                        draft.otherDiffers = true;
                        if (draft.detail.isEmpty()) {
                            draft.detail = "name";
                        }
                    }
                    case "version" -> {
                        draft.hostVersion = sides[0];
                        draft.guestVersion = sides[1];
                        draft.versionDiffers = true;
                    }
                    default -> {
                        draft.otherDiffers = true;
                        if (draft.detail.isEmpty()) {
                            draft.detail = field;
                        }
                    }
                }
                return;
            }
        }
        RowDraft draft = draftFor(drafts, head);
        if (tail.contains("missing on guest")) {
            draft.missingOnGuest = true;
        } else if (tail.contains("extra on guest")) {
            draft.missingOnHost = true;
        } else {
            draft.otherDiffers = true;
        }
    }

    private static RowDraft draftFor(Map<String, RowDraft> drafts, String modId) {
        return drafts.computeIfAbsent(modId.trim(), RowDraft::new);
    }

    private static boolean parseSession(Builder builder, String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        SessionCause cause = null;
        if (lower.contains("session in reconnect grace")) {
            cause = SessionCause.HOST_IN_GRACE;
        } else if (lower.contains("reconnect grace expired")
                || lower.contains("no reconnect grace window is open")) {
            cause = SessionCause.GRACE_EXPIRED;
        } else if (lower.contains("session id does not match")
                || lower.contains("named a different session")) {
            cause = SessionCause.DIFFERENT_CAMPAIGN;
        } else if (lower.contains("player id does not match")) {
            cause = SessionCause.SLOT_TAKEN;
        } else if (lower.contains("ended by player")) {
            cause = SessionCause.ENDED_BY_PLAYER;
        } else if (lower.contains("host rejected the resume")) {
            cause = SessionCause.OTHER;
        }
        if (cause == null) {
            return false;
        }
        builder.sessionCause = cause;
        builder.graceSeconds = parseSeconds(raw);
        // Only the transient one. A closed window, a different campaign and a taken slot are all
        // deterministic: a retry button on them is a button that is guaranteed to fail.
        builder.retryable = cause == SessionCause.HOST_IN_GRACE;
        return true;
    }

    /**
     * Finds a "&lt;n&gt; s" / "&lt;n&gt; seconds" figure, so a number the pump appends to a reason
     * ("reconnect grace expired after 120 s") reaches the dialog without changing the constant. Only a
     * whole seconds unit counts: matching a bare number would turn a session id into a countdown.
     */
    private static int parseSeconds(String raw) {
        int i = 0;
        while (i < raw.length()) {
            if (!Character.isDigit(raw.charAt(i))) {
                i++;
                continue;
            }
            int end = i;
            while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
                end++;
            }
            String digits = raw.substring(i, end);
            String after = raw.substring(end).trim().toLowerCase(Locale.ROOT);
            int unitEnd = 0;
            while (unitEnd < after.length() && Character.isLetter(after.charAt(unitEnd))) {
                unitEnd++;
            }
            String unit = after.substring(0, unitEnd);
            if (unit.equals("s") || unit.equals("sec") || unit.equals("secs")
                    || unit.equals("second") || unit.equals("seconds")) {
                try {
                    return Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            i = end;
        }
        return -1;
    }

    /**
     * Splits {@code host=X guest=Y} into its two halves. Values may contain spaces (paths, jar lists),
     * so the split is on the last {@code " guest="} rather than on whitespace.
     */
    private static String[] parseHostGuest(String text) {
        String value = text == null ? "" : text.trim();
        int hostAt = value.indexOf("host=");
        int guestAt = value.lastIndexOf(" guest=");
        if (hostAt < 0 && guestAt < 0) {
            return new String[]{"", ""};
        }
        if (guestAt < 0) {
            return new String[]{value.substring(hostAt + 5).trim(), ""};
        }
        if (hostAt < 0 || hostAt > guestAt) {
            return new String[]{"", value.substring(guestAt + " guest=".length()).trim()};
        }
        return new String[]{
                value.substring(hostAt + 5, guestAt).trim(),
                value.substring(guestAt + " guest=".length()).trim()
        };
    }

    /**
     * The campaign-id reject glues launch advice onto the value with a semicolon; drop it.
     *
     * <p>Cutting at the first {@code ;} is safe here specifically because a campaign id is a
     * {@link java.util.UUID#toString()} — 32 hex digits and 4 dashes, nothing else the format could
     * ever put a semicolon inside. A value that could legitimately contain one would need a real
     * delimiter, not this.
     */
    private static String cutAtAdvice(String value) {
        int semi = value.indexOf(';');
        return semi < 0 ? value.trim() : value.substring(0, semi).trim();
    }

    private static List<String> splitLines(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Orders two version strings by their digit groups.
     *
     * @return a negative/zero/positive comparison, or {@link #INCOMPARABLE} when either string has no
     * digits at all (a name like "dev" or "release" cannot be ordered against anything)
     */
    static int compareVersions(String left, String right) {
        List<Integer> a = digitGroups(left);
        List<Integer> b = digitGroups(right);
        if (a.isEmpty() || b.isEmpty()) {
            return INCOMPARABLE;
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Integer> digitGroups(String value) {
        List<Integer> groups = new ArrayList<>();
        String text = value == null ? "" : value;
        int i = 0;
        while (i < text.length()) {
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int end = i;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            try {
                groups.add(Integer.parseInt(text.substring(i, end)));
            } catch (NumberFormatException ignored) {
                groups.add(0);
            }
            i = end;
        }
        return groups;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private Builder toBuilder() {
        Builder builder = new Builder();
        builder.kind = kind;
        builder.source = source;
        builder.rawReason = rawReason;
        builder.hostSeed = hostSeed;
        builder.guestSeed = guestSeed;
        builder.hostFingerprint = hostFingerprint;
        builder.guestFingerprint = guestFingerprint;
        builder.campaignIdMismatch = campaignIdMismatch;
        builder.hostCampaignId = hostCampaignId;
        builder.guestCampaignId = guestCampaignId;
        builder.modRows = modRows;
        builder.hiddenModRows = hiddenModRows;
        builder.hostGameVersion = hostGameVersion;
        builder.guestGameVersion = guestGameVersion;
        builder.hostCoopBuild = hostCoopBuild;
        builder.guestCoopBuild = guestCoopBuild;
        builder.ironModeSide = ironModeSide;
        builder.manifestUnreadable = manifestUnreadable;
        builder.modGameVersion = modGameVersion;
        builder.installedGameVersion = installedGameVersion;
        builder.sessionCause = sessionCause;
        builder.graceSeconds = graceSeconds;
        builder.retryable = retryable;
        return builder;
    }

    /** Mutable scratch for the parse; never escapes this class. */
    private static final class Builder {
        private Kind kind = Kind.UNMAPPED;
        private Source source = Source.OTHER;
        private String rawReason = NO_REASON;
        private String hostSeed = "";
        private String guestSeed = "";
        private String hostFingerprint = "";
        private String guestFingerprint = "";
        private boolean campaignIdMismatch;
        private String hostCampaignId = "";
        private String guestCampaignId = "";
        private List<ModRow> modRows = List.of();
        private int hiddenModRows;
        private String hostGameVersion = "";
        private String guestGameVersion = "";
        private String hostCoopBuild = "";
        private String guestCoopBuild = "";
        private String ironModeSide = "";
        private boolean manifestUnreadable;
        private String modGameVersion = "";
        private String installedGameVersion = "";
        private SessionCause sessionCause;
        private int graceSeconds = -1;
        private boolean retryable;
    }

    /** One mod's accumulating facts before it becomes a {@link ModRow}. */
    private static final class RowDraft {
        private final String modId;
        private String name = "";
        private String hostVersion = "";
        private String guestVersion = "";
        private boolean versionDiffers;
        private boolean contentDiffers;
        private boolean otherDiffers;
        private boolean missingOnGuest;
        private boolean missingOnHost;
        private String detail = "";

        private RowDraft(String modId) {
            this.modId = modId;
        }

        private ModRow toRow() {
            ModVerdict verdict;
            if (missingOnGuest) {
                verdict = ModVerdict.MISSING_ON_GUEST;
            } else if (missingOnHost) {
                verdict = ModVerdict.MISSING_ON_HOST;
            } else if (versionDiffers) {
                // A version difference explains any checksum difference underneath it, so it wins:
                // saying "same version, different contents" about mods at 2.7 and 2.8 is just wrong.
                verdict = ModVerdict.VERSION_DIFFERS;
            } else if (contentDiffers) {
                verdict = ModVerdict.CONTENT_DIFFERS;
            } else {
                verdict = ModVerdict.OTHER_DIFFERS;
            }
            return new ModRow(modId, name, hostVersion, guestVersion, verdict, detail);
        }
    }
}
