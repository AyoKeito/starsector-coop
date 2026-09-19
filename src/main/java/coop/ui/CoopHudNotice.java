package coop.ui;

/**
 * One transient line on the link HUD: something happened, say so for a few seconds, then stop.
 *
 * <p>Added for the log marker, which needs both players to see that a marker landed without either
 * of them reading a log mid-session. A slot on the existing {@link CoopLinkHud} rather than a second
 * renderer: the HUD already owns the font, the corner and the failure policy, and a second
 * {@code CampaignUIRenderingListener} would have to duplicate all three to draw one line.
 *
 * <p>Deliberately one slot, last-writer-wins. Two markers three seconds apart is one line replaced,
 * not a stack that grows down the screen - the log is where the history lives, and this is only the
 * acknowledgement that the key did something.
 *
 * <p>Pure and static: no engine, no clock of its own. The caller passes the time in, which is what
 * makes the expiry testable.
 */
public final class CoopHudNotice {

    /** How long a notice stays up. Long enough to read a marker id, short enough not to linger. */
    public static final long TTL_MILLIS = 5_000L;

    private static volatile String text = "";
    private static volatile long expiresAtMillis;

    private CoopHudNotice() {
    }

    /** Shows {@code line} for {@link #TTL_MILLIS} from {@code nowMillis}. Blank clears the slot. */
    public static void show(String line, long nowMillis) {
        String wanted = line == null ? "" : line.trim();
        if (wanted.isEmpty()) {
            clear();
            return;
        }
        text = wanted;
        expiresAtMillis = nowMillis + TTL_MILLIS;
    }

    /** Same, against the wall clock; the production call site. */
    public static void show(String line) {
        show(line, System.currentTimeMillis());
    }

    /** What to draw at {@code nowMillis}, or {@code ""} when the slot is empty or expired. */
    public static String current(long nowMillis) {
        String current = text;
        if (current.isEmpty() || nowMillis >= expiresAtMillis) {
            return "";
        }
        return current;
    }

    /** Empties the slot; a session ending should not leave a line hanging over the title screen. */
    public static void clear() {
        text = "";
        expiresAtMillis = 0L;
    }
}
