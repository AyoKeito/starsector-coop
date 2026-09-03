package coop.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-authoritative model of who is in the Phase 21 lobby and how far along they are. Pure Java: no
 * engine, no transport, no clock of its own — every method that needs the time is handed it — so the
 * whole ready/countdown/AFK rule set is unit-testable and the pump keeps its single source of time.
 *
 * <p><b>Row order is fixed: host first, then join order.</b> Never sorted by state. A roster that
 * re-orders itself as players progress is unreadable at a glance, and the presentation rules for the
 * stats page make the same call for the same reason.
 *
 * <p><b>One admission at a time.</b> {@link #admit(String, String, long)} refuses a second player
 * while another is still mid-handshake (before {@link CoopJoinPhase#SNAPSHOT_APPLIED}). v1 has a
 * single guest so this can never fire today; the rule is written now because two independent coop
 * codebases (Nitrox, RimWorld MP) hit state corruption from concurrent joins, and Phase 27 inherits
 * this class.
 */
public final class CoopLobbyRoster {

    /** How long the visible pre-start countdown runs. Long enough to read, short enough not to nag. */
    public static final long COUNTDOWN_MILLIS = 3_000L;
    /** After this long with a guest still not ready, the lobby points at the host's override. */
    public static final long AFK_HINT_MILLIS = 120_000L;

    /** No countdown is running. */
    public static final long NO_COUNTDOWN = -1L;

    /**
     * Row state words, exactly as the roster renders them.
     *
     * <p>{@link #STATE_RECONNECTING_PREFIX} is followed by the reconnect grace <em>remaining</em>,
     * counting down. It used to be followed by the time elapsed since the drop, counting up, which
     * is what the Phase 21 live smoke read as "Reconnecting 1:55" on a window that had 24 s left:
     * two different quantities under one label, and the elapsed one kept climbing after the wait it
     * belonged to was over. The remaining is the only number a player can act on, and the only one
     * this roster accepts - see {@link #markReconnecting(String, long, long)}.
     */
    public static final String STATE_CONNECTING = "Connecting...";
    public static final String STATE_READY = "Ready";
    public static final String STATE_NOT_READY = "Not ready";
    public static final String STATE_SYNCING_PREFIX = "Syncing ";
    public static final String STATE_RECONNECTING_PREFIX = "Reconnecting ";
    /** The Start option's label when nothing blocks it. */
    public static final String START_LABEL = "Start session";
    /** Prefix of the blocked label; the blocking player's name is named in the label itself. */
    public static final String START_WAITING_PREFIX = "Waiting for ";

    /** One player's row. Mutable inside the roster, read-only to everybody else. */
    public static final class Row {
        private final String playerId;
        private final boolean host;
        private String name;
        private CoopJoinPhase phase;
        private boolean ready;
        private Long reconnectingUntilMillis;
        private String reason = "";
        private long lastChangeMillis;

        private Row(String playerId, String name, boolean host, CoopJoinPhase phase, long nowMillis) {
            this.playerId = playerId;
            this.name = name;
            this.host = host;
            this.phase = phase;
            this.lastChangeMillis = nowMillis;
        }

        public String playerId() {
            return playerId;
        }

        public String name() {
            return name;
        }

        public boolean host() {
            return host;
        }

        public CoopJoinPhase phase() {
            return phase;
        }

        public boolean ready() {
            return ready;
        }

        /**
         * The instant this row's reconnect grace runs out, on the local clock, or null when the
         * player is connected. Absolute rather than a duration so the row needs no tick of its own;
         * the renderer subtracts.
         */
        public Long reconnectingUntilMillis() {
            return reconnectingUntilMillis;
        }

        /** True while this row is inside a reconnect grace window. */
        public boolean reconnecting() {
            return reconnectingUntilMillis != null;
        }

        /** A blocking reason that overrides the state word ("Mod mismatch"); "" when there is none. */
        public String reason() {
            return reason;
        }

        public long lastChangeMillis() {
            return lastChangeMillis;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private long openedAtMillis;
    private boolean opened;
    private long countdownEndsAtMillis = NO_COUNTDOWN;
    private String lastResetReason = "";

    /**
     * Marks the moment the lobby opened; the elapsed counter and the AFK hint are measured from it.
     *
     * <p>Re-based by {@link #admit(String, String, long)}: on the host this runs on the first frame of
     * hosting, which can be minutes before anybody dials in, and "Waiting 4:12" for a lobby a guest
     * reached eight seconds ago says nothing true. The clock the two readings want is the wait for
     * the player who is actually being waited on.
     */
    public void open(long nowMillis) {
        if (opened) {
            return;
        }
        opened = true;
        openedAtMillis = nowMillis;
    }

    public boolean opened() {
        return opened;
    }

    /** Milliseconds since {@link #open(long)}; 0 before the lobby opened. */
    public long elapsedMillis(long nowMillis) {
        return opened ? Math.max(0L, nowMillis - openedAtMillis) : 0L;
    }

    /**
     * Installs (or renames) the host's own row. The host is auto-ready by construction: its ready
     * <em>is</em> the Start press, which is the widely copied convention and the reason the gate has
     * two stages instead of an auto-start.
     */
    public void setHost(String playerId, String name, long nowMillis) {
        String id = requireText(playerId, "playerId");
        Row existing = row(id);
        if (existing != null) {
            existing.name = displayName(name);
            return;
        }
        Row host = new Row(id, displayName(name), true, CoopJoinPhase.READY, nowMillis);
        host.ready = true;
        rows.add(0, host);
    }

    /**
     * Takes a connecting player into the lobby at {@link CoopJoinPhase#LINK_ESTABLISHED}.
     *
     * @return true when the row exists afterwards; false when another player is still mid-handshake
     *         and this admission has to wait its turn
     */
    public boolean admit(String playerId, String name, long nowMillis) {
        String id = requireText(playerId, "playerId");
        Row existing = row(id);
        if (existing != null) {
            existing.name = displayName(name);
            return true;
        }
        for (Row row : rows) {
            if (!row.host && !row.phase.atLeast(CoopJoinPhase.SNAPSHOT_APPLIED)) {
                return false;
            }
        }
        rows.add(new Row(id, displayName(name), false, CoopJoinPhase.LINK_ESTABLISHED, nowMillis));
        // The wait that "Waiting m:ss" and the two-minute AFK hint are about starts here, with the
        // player who has to ready up, not with the host's first frame of hosting.
        opened = true;
        openedAtMillis = nowMillis;
        return true;
    }

    /**
     * Moves a row to a handshake phase. {@link CoopJoinPhase#READY} is refused here on purpose —
     * readying is a player action and goes through {@link #setReady(String, boolean, long)}, so the
     * protocol can never mark somebody ready on their behalf.
     *
     * @return true when the row's phase actually changed
     */
    public boolean setPhase(String playerId, CoopJoinPhase phase, long nowMillis) {
        Row row = row(playerId);
        if (row == null || row.host || phase == null || phase == CoopJoinPhase.READY) {
            return false;
        }
        if (row.phase == phase) {
            return false;
        }
        row.phase = phase;
        if (!phase.atLeast(CoopJoinPhase.SNAPSHOT_APPLIED)) {
            // Falling back below the ready gate cannot leave a stale ready standing.
            row.ready = false;
        }
        row.lastChangeMillis = nowMillis;
        return true;
    }

    /**
     * Sets or revokes a player's ready. Ready is revocable at any time before release (Barotrauma's
     * non-revocable ready plus auto-start is the shipped trap this avoids) and is only <em>accepted</em>
     * from {@link CoopJoinPhase#SNAPSHOT_APPLIED} on: a player who has not got the world yet has
     * nothing to be ready for.
     *
     * @return true when the roster changed
     */
    public boolean setReady(String playerId, boolean ready, long nowMillis) {
        Row row = row(playerId);
        if (row == null || row.host) {
            return false;
        }
        if (ready && !row.phase.atLeast(CoopJoinPhase.SNAPSHOT_APPLIED)) {
            return false;
        }
        boolean changed = row.ready != ready;
        row.ready = ready;
        row.phase = ready ? CoopJoinPhase.READY : CoopJoinPhase.SNAPSHOT_APPLIED;
        if (changed) {
            row.lastChangeMillis = nowMillis;
        }
        return changed;
    }

    /**
     * Marks a row as inside its reconnect grace window; the row and its ready value are kept.
     *
     * <p>{@code remainingMillis} is the window's own remaining time, which only the reconnect
     * coordinator knows - the roster deliberately cannot make this number up. Re-applied every frame
     * rather than latched at the drop, so a window the player extends shows the new number on the
     * next frame instead of a countdown that disagrees with the dialog above it.
     *
     * @param remainingMillis milliseconds left in the grace window; clamped at zero
     * @return true only on the frame the row <em>became</em> reconnecting, so a caller running this
     *         every frame still gets one event out of it
     */
    public boolean markReconnecting(String playerId, long nowMillis, long remainingMillis) {
        Row row = row(playerId);
        if (row == null) {
            return false;
        }
        boolean fresh = row.reconnectingUntilMillis == null;
        row.reconnectingUntilMillis = nowMillis + Math.max(0L, remainingMillis);
        if (fresh) {
            row.lastChangeMillis = nowMillis;
        }
        return fresh;
    }

    /** The reconnect landed: the row goes back to whatever ready value it kept through the window. */
    public boolean markReconnected(String playerId, long nowMillis) {
        Row row = row(playerId);
        if (row == null || row.reconnectingUntilMillis == null) {
            return false;
        }
        row.reconnectingUntilMillis = null;
        row.lastChangeMillis = nowMillis;
        return true;
    }

    /**
     * A player left. Before {@link CoopJoinPhase#SNAPSHOT_APPLIED} the join never completed, so the
     * partial state and the row go away entirely (Nitrox's explicit abandonment cleanup); after it,
     * the row survives so the reconnect grace has something to render.
     *
     * @return true when the row was removed
     */
    public boolean dropPartial(String playerId) {
        Row row = row(playerId);
        if (row == null || row.host || row.phase.atLeast(CoopJoinPhase.SNAPSHOT_APPLIED)) {
            return false;
        }
        rows.remove(row);
        return true;
    }

    /** Removes a row unconditionally (the player left for good). */
    public boolean remove(String playerId) {
        Row row = row(playerId);
        if (row == null || row.host) {
            return false;
        }
        rows.remove(row);
        return true;
    }

    /** Attaches a blocking reason that replaces the row's state word ("Mod mismatch"). */
    public boolean setReason(String playerId, String reason, long nowMillis) {
        Row row = row(playerId);
        if (row == null) {
            return false;
        }
        String text = reason == null ? "" : reason.trim();
        if (text.equals(row.reason)) {
            return false;
        }
        row.reason = text;
        row.lastChangeMillis = nowMillis;
        return true;
    }

    /**
     * Clears every guest's ready because a session-level setting changed, and says why.
     *
     * <p><b>No production caller yet, deliberately.</b> Every setting the host can change is a
     * launch-time JVM property read once per process ({@code CoopNetStartupConfig}); there is no
     * in-session setting to change. The rule is implemented and tested now because the ready-state
     * lifecycle is specified around it, and the first runtime setting that lands must reset ready
     * with a feed line rather than silently start a session somebody had already agreed to.
     *
     * @return the names whose ready was actually revoked, in row order
     */
    public List<String> resetReady(String reason, long nowMillis) {
        lastResetReason = reason == null ? "" : reason.trim();
        List<String> affected = new ArrayList<>();
        for (Row row : rows) {
            if (row.host || !row.ready) {
                continue;
            }
            row.ready = false;
            row.phase = CoopJoinPhase.SNAPSHOT_APPLIED;
            row.lastChangeMillis = nowMillis;
            affected.add(row.name);
        }
        cancelCountdown();
        return affected;
    }

    /** Why the last {@link #resetReady} happened; "" when there has not been one. */
    public String lastResetReason() {
        return lastResetReason;
    }

    /** Clears the recorded reset reason once it has been broadcast. */
    public void clearResetReason() {
        lastResetReason = "";
    }

    /**
     * True when the Start option may arm: every non-host row is {@link CoopJoinPhase#READY} and none
     * is reconnecting. An empty lobby (no guest at all) is <em>not</em> all-ready — starting alone is
     * exactly the hole this phase closes.
     */
    public boolean allReady() {
        boolean sawGuest = false;
        for (Row row : rows) {
            if (row.host) {
                continue;
            }
            sawGuest = true;
            if (!row.ready || row.reconnecting() || !row.reason.isEmpty()) {
                return false;
            }
        }
        return sawGuest;
    }

    /**
     * The name to put in the Start option's own label, or null when nothing blocks. Explained waits
     * read shorter than unexplained ones, which is why the blocking condition lives in the label
     * rather than in a tooltip.
     */
    public String blockingName() {
        for (Row row : rows) {
            if (row.host) {
                continue;
            }
            if (!row.ready || row.reconnecting() || !row.reason.isEmpty()) {
                return row.name;
            }
        }
        return rows.size() > 1 ? null : "a player to connect";
    }

    /** The Start option's label: named blocking condition, or the plain start. */
    public String startLabel() {
        String blocking = blockingName();
        return blocking == null ? START_LABEL : START_WAITING_PREFIX + blocking + "...";
    }

    /** The row's state word, exactly as the lobby renders it. */
    public String stateWord(Row row, long nowMillis) {
        if (row == null) {
            return "";
        }
        if (row.reconnectingUntilMillis != null) {
            return STATE_RECONNECTING_PREFIX
                    + formatClock(Math.max(0L, row.reconnectingUntilMillis - nowMillis));
        }
        if (!row.reason.isEmpty()) {
            return row.reason;
        }
        if (row.ready) {
            return STATE_READY;
        }
        if (row.phase == CoopJoinPhase.LINK_ESTABLISHED) {
            return STATE_CONNECTING;
        }
        if (row.phase.atLeast(CoopJoinPhase.SNAPSHOT_APPLIED)) {
            return STATE_NOT_READY;
        }
        return STATE_SYNCING_PREFIX + row.phase.stepIndex() + "/" + CoopJoinPhase.STEP_COUNT;
    }

    /** m:ss, the shape the roster's "Reconnecting 0:42" countdown uses. */
    public static String formatClock(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    // ---- countdown ------------------------------------------------------------------------------

    /** Arms the visible pre-start countdown. Idempotent while one is already running. */
    public boolean startCountdown(long nowMillis) {
        if (countdownEndsAtMillis != NO_COUNTDOWN) {
            return false;
        }
        countdownEndsAtMillis = nowMillis + COUNTDOWN_MILLIS;
        return true;
    }

    /** Cancels a running countdown. Any player may do this; the host is not privileged here. */
    public boolean cancelCountdown() {
        if (countdownEndsAtMillis == NO_COUNTDOWN) {
            return false;
        }
        countdownEndsAtMillis = NO_COUNTDOWN;
        return true;
    }

    public boolean countdownActive() {
        return countdownEndsAtMillis != NO_COUNTDOWN;
    }

    /** Milliseconds left, or {@link #NO_COUNTDOWN} when none is running. Never negative. */
    public long countdownRemainingMillis(long nowMillis) {
        if (countdownEndsAtMillis == NO_COUNTDOWN) {
            return NO_COUNTDOWN;
        }
        return Math.max(0L, countdownEndsAtMillis - nowMillis);
    }

    /** True on and after the frame the countdown runs out. */
    public boolean countdownElapsed(long nowMillis) {
        return countdownEndsAtMillis != NO_COUNTDOWN && nowMillis >= countdownEndsAtMillis;
    }

    /**
     * Mirrors a remaining-milliseconds value received from the host onto this side's clock, so the
     * guest can render the same countdown without either side trusting the other's wall clock.
     */
    public void applyCountdownRemaining(long remainingMillis, long nowMillis) {
        if (remainingMillis < 0L) {
            countdownEndsAtMillis = NO_COUNTDOWN;
            return;
        }
        countdownEndsAtMillis = nowMillis + remainingMillis;
    }

    /**
     * True once the lobby has been open for {@link #AFK_HINT_MILLIS} with a guest still not ready.
     * It surfaces the host's override; it never fires it. The correct outcome of a non-response here
     * is "stay paused".
     */
    public boolean afkHint(long nowMillis) {
        if (!opened || allReady() || elapsedMillis(nowMillis) < AFK_HINT_MILLIS) {
            return false;
        }
        for (Row row : rows) {
            if (!row.host && !row.ready) {
                return true;
            }
        }
        return false;
    }

    // ---- reads ----------------------------------------------------------------------------------

    /** Rows in fixed order: host first, then join order. */
    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public Row row(String playerId) {
        if (playerId == null) {
            return null;
        }
        for (Row row : rows) {
            if (row.playerId.equals(playerId)) {
                return row;
            }
        }
        return null;
    }

    public int size() {
        return rows.size();
    }

    /** Drops every row and the countdown; used on the session edge that closes the lobby. */
    public void clear() {
        rows.clear();
        countdownEndsAtMillis = NO_COUNTDOWN;
        lastResetReason = "";
        opened = false;
        openedAtMillis = 0L;
    }

    /**
     * Replaces the whole roster with the host's view of it (guest side of {@code LOBBY_STATUS}).
     * The host is authoritative, so this is a full replacement rather than a merge — the same rule
     * the market and base snapshots follow.
     */
    public void replaceAll(List<Row> incoming, long nowMillis) {
        rows.clear();
        if (incoming == null) {
            return;
        }
        for (Row row : incoming) {
            if (row != null) {
                rows.add(row);
            }
        }
        open(nowMillis);
    }

    /** Builds a detached row, for the guest's mirrored roster and for tests. */
    public static Row mirroredRow(String playerId, String name, boolean host, CoopJoinPhase phase,
                                  boolean ready, Long reconnectingUntilMillis, String reason,
                                  long nowMillis) {
        Row row = new Row(requireText(playerId, "playerId"), displayName(name), host,
                phase == null ? CoopJoinPhase.LINK_ESTABLISHED : phase, nowMillis);
        row.ready = ready;
        row.reconnectingUntilMillis = reconnectingUntilMillis;
        row.reason = reason == null ? "" : reason;
        return row;
    }

    private static String displayName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Player";
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
