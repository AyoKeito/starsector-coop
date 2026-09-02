package coop.ui;

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
 * @param pauseHolder          who is holding the shared pause ("host", "guest", "guest screen",
 *                             "combat"), or null when nobody is
 * @param clockDriftGameHours  guest clock offset in whole game hours, positive when the guest is
 *                             BEHIND the host; null on the host, and null when it rounds to zero
 */
public record CoopHudState(String roleBadge,
                           String status,
                           boolean paused,
                           String pauseHolder,
                           Integer clockDriftGameHours) {

    public static final String BADGE_HOST = "HOST";
    public static final String BADGE_GUEST = "GUEST";
    public static final String BADGE_COOP = "COOP";

    public static final String STATUS_NO_SESSION = "no session";
    public static final String STATUS_WAITING_FOR_GUEST = "waiting for guest";
    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_HANDSHAKING = "handshaking";
    public static final String STATUS_SESSION_ACTIVE = "session active";
    public static final String STATUS_REJECTED = "connection rejected";
    /** Host side of a 12b reconnect hold: the peer dropped after a live session and time is held. */
    public static final String STATUS_GUEST_DISCONNECTED_HOLDING = "guest disconnected, holding";
    /** Guest side of the same drop. */
    public static final String STATUS_RECONNECTING = "reconnecting";

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
        return line.toString();
    }
}
