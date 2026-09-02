package coop.ui;

import coop.net.CoopConnectionRole;

/**
 * One frame's worth of link status for the Phase 20.6 HUD line: who you are, what the session is
 * doing, who is holding the clock, and (guest only) how far the guest's campaign clock sits from the
 * host's.
 *
 * <p>Immutable and engine-free by design. {@link coop.net.CoopNetPump#hudState(boolean)} builds it
 * from state the pump already owns, and {@link #formatLine} turns it into the drawn string, so both
 * the mapping and the wording are unit-testable without a sector.
 *
 * @param roleBadge            {@link #BADGE_HOST}, {@link #BADGE_GUEST} or {@link #BADGE_COOP}
 * @param status               one of the {@code STATUS_*} constants
 * @param paused               the live {@code sector.isPaused()} read; presentation only (it picks
 *                             the line's colour), never wording — the wording comes from
 *                             {@code pauseHolder}
 * @param pauseHolder          the DISPLAY wording for whoever holds the shared pause, already
 *                             resolved for the local role by {@link #displayHolder} (so the local
 *                             player reads as "you"), or null when nobody holds it
 * @param clockDriftGameHours  guest clock offset in whole game hours, positive when the guest is
 *                             BEHIND the host; null on the host, and null when it rounds to zero
 * @param rttMillis            smoothed round-trip time, or null when there is no session or no
 *                             PONG has been matched yet
 * @param lossPercent          raw datagram loss over the last 10 s, or null when there is no session
 * @param transport            {@link #TRANSPORT_UDP} or {@link #TRANSPORT_TCP_FALLBACK}; null when
 *                             there is no session, which is what suppresses the whole link segment
 */
public record CoopHudState(String roleBadge,
                           String status,
                           boolean paused,
                           String pauseHolder,
                           Integer clockDriftGameHours,
                           Integer rttMillis,
                           Integer lossPercent,
                           String transport) {

    /**
     * Pre-20.6-M2 shape: role, status, pause and drift with no link readout. Kept because the link
     * fields are exactly the ones that are absent outside a session, and every caller that does not
     * have them should not have to write three nulls.
     */
    public CoopHudState(String roleBadge, String status, boolean paused, String pauseHolder,
                        Integer clockDriftGameHours) {
        this(roleBadge, status, paused, pauseHolder, clockDriftGameHours, null, null, null);
    }

    public static final String BADGE_HOST = "HOST";
    public static final String BADGE_GUEST = "GUEST";
    public static final String BADGE_COOP = "COOP";

    public static final String STATUS_NO_SESSION = "no session";
    public static final String STATUS_WAITING_FOR_GUEST = "waiting for guest";
    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_HANDSHAKING = "handshaking";
    public static final String STATUS_SESSION_ACTIVE = "session active";
    public static final String STATUS_REJECTED = "connection rejected";
    /** Prefix of the reason-carrying form; see {@link #rejectedStatus}. */
    public static final String STATUS_REJECTED_PREFIX = "rejected: ";
    /** Host side of a 12b reconnect hold: the peer dropped after a live session and time is held. */
    public static final String STATUS_GUEST_DISCONNECTED_HOLDING = "guest disconnected, holding";
    /** Guest side of the same drop. */
    public static final String STATUS_RECONNECTING = "reconnecting";

    /** Raw holder token: the host's own pause intent. */
    public static final String HOLDER_HOST = "host";
    /** Raw holder token: the guest's manual pause-key intent. */
    public static final String HOLDER_GUEST = "guest";
    /** Raw holder token: the guest has a blocking screen open. */
    public static final String HOLDER_GUEST_SCREEN = "guest screen";
    /** Raw holder token: either player is in combat. */
    public static final String HOLDER_COMBAT = "combat";
    /**
     * Raw holder token: the Phase 20.2 grace window is holding the world while a dropped partner is
     * given a chance to come back. Reads the same on both clients — neither player is holding this
     * one, the session is.
     */
    public static final String HOLDER_RECONNECT = "reconnect";

    /** Transport wording: the state stream is on UDP, which is the normal case. */
    public static final String TRANSPORT_UDP = "udp";
    /** Transport wording: UDP is blocked and the state stream is wrapped in TCP. */
    public static final String TRANSPORT_TCP_FALLBACK = "tcp fallback";

    /**
     * The rejected status, carrying the host's reason when there is one.
     *
     * <p>"connection rejected" on its own sent players to the log to find out which reject they had
     * hit — and the one that matters most, a wrong lobby password, cannot be fixed without a
     * relaunch, so it has to be readable on screen.
     *
     * @param reason the host's reason text, or null/blank for the bare wording
     */
    public static String rejectedStatus(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return STATUS_REJECTED;
        }
        return STATUS_REJECTED_PREFIX + reason.trim();
    }

    /**
     * Maps a raw holder token (the wire/coordinator vocabulary, always host-relative) to the wording
     * the local player should read, so whoever is looking at the HUD sees themselves as "you".
     *
     * <table>
     *   <caption>raw &rarr; display</caption>
     *   <tr><th>raw</th><th>on HOST</th><th>on GUEST</th></tr>
     *   <tr><td>host</td><td>you</td><td>host</td></tr>
     *   <tr><td>guest</td><td>guest</td><td>you</td></tr>
     *   <tr><td>guest screen</td><td>guest's screen</td><td>your screen</td></tr>
     *   <tr><td>combat</td><td>combat</td><td>combat</td></tr>
     * </table>
     *
     * <p>A null/blank raw holder maps to {@code ""} (nobody). An unrecognised token is passed
     * through unchanged so a newer peer's vocabulary degrades to literal text rather than vanishing.
     *
     * @param rawHolder raw token, or null/blank for nobody
     * @param localRole the role of the client doing the reading; null or
     *                  {@link CoopConnectionRole#NONE} passes the raw token through
     */
    public static String displayHolder(String rawHolder, CoopConnectionRole localRole) {
        if (rawHolder == null) {
            return "";
        }
        String raw = rawHolder.trim();
        if (raw.isEmpty()) {
            return "";
        }
        if (localRole == CoopConnectionRole.HOST) {
            return switch (raw) {
                case HOLDER_HOST -> "you";
                case HOLDER_GUEST -> "guest";
                case HOLDER_GUEST_SCREEN -> "guest's screen";
                default -> raw;
            };
        }
        if (localRole == CoopConnectionRole.GUEST) {
            return switch (raw) {
                case HOLDER_GUEST -> "you";
                case HOLDER_GUEST_SCREEN -> "your screen";
                default -> raw;
            };
        }
        return raw;
    }

    /** Preferred separator: U+00B7 MIDDLE DOT, used when the loaded font actually has that glyph. */
    public static final String SEPARATOR_DOT = " · ";
    /** Fallback for fonts without the middle dot. */
    public static final String SEPARATOR_PIPE = " | ";
    /** Code point checked against the font to decide between the two separators. */
    public static final int SEPARATOR_DOT_CODE_POINT = 0x00B7;

    /**
     * Renders the state as the single line the HUD draws. Always starts with the role badge, so the
     * HUD can colour the badge separately by splitting at {@code roleBadge().length()}.
     */
    public static String formatLine(CoopHudState state, String separator) {
        if (state == null) {
            return "";
        }
        String sep = separator == null ? SEPARATOR_PIPE : separator;
        StringBuilder line = new StringBuilder();
        line.append(state.roleBadge() == null ? BADGE_COOP : state.roleBadge());
        line.append(sep).append(state.status() == null ? STATUS_NO_SESSION : state.status());

        String holder = state.pauseHolder();
        if (holder != null && !holder.isEmpty()) {
            line.append(sep).append("paused by ").append(holder);
        }

        Integer drift = state.clockDriftGameHours();
        if (drift != null && drift != 0) {
            line.append(sep).append("guest ").append(Math.abs(drift)).append('h')
                    .append(drift > 0 ? " behind" : " ahead");
        }

        // Link segment, session only. The transport is the thing that changes how the game feels, so
        // it is always shown once there is a session; RTT and loss are omitted only when unmeasured.
        String transport = state.transport();
        if (transport != null && !transport.isEmpty()) {
            Integer rtt = state.rttMillis();
            if (rtt != null) {
                line.append(sep).append(rtt).append(" ms");
            }
            Integer loss = state.lossPercent();
            if (loss != null) {
                line.append(sep).append("loss ").append(loss).append('%');
            }
            line.append(sep).append(transport);
        }
        return line.toString();
    }
}
