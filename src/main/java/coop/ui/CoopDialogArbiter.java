package coop.ui;

/**
 * Decides which coop dialog may ask for the (exclusive) interaction-dialog slot on a given frame.
 *
 * <p>Dialogs are exclusive in Starsector, and coop now has more than one thing that wants to be
 * modal. Without an order, two controllers retrying against each other every frame produce a dialog
 * that flickers between two messages — the shape of Barotrauma's inescapable reopen loop. So there is
 * exactly one order, it is fixed, and it is stated once:
 *
 * <ol>
 *   <li>{@link #RECONNECT} — the link is gone; nothing else the player could read matters yet.</li>
 *   <li>{@link #DESYNC} — the session ended for a named, unrecoverable reason.</li>
 *   <li>{@link #LOBBY} — the session exists and is waiting for the players to start it.</li>
 *   <li>{@link #CONNECTING} — the session does not exist yet.</li>
 * </ol>
 *
 * <p>Higher in that list wins: a lower-priority controller must not <em>request</em> while a
 * higher-priority one is requested, and this class is the only place that knows the order.
 */
public enum CoopDialogArbiter {
    RECONNECT,
    DESYNC,
    LOBBY,
    CONNECTING;

    /** True when {@code kind} outranks {@code other}. */
    public boolean outranks(CoopDialogArbiter other) {
        return other != null && ordinal() < other.ordinal();
    }

    /**
     * Whether {@code kind} may request the slot given which kinds are currently requesting it.
     *
     * @param kind      the dialog kind that wants to open
     * @param requested the kinds whose controllers report {@code isRequested()}; nulls are ignored
     */
    public static boolean mayRequest(CoopDialogArbiter kind, CoopDialogArbiter... requested) {
        if (kind == null) {
            return false;
        }
        if (requested == null) {
            return true;
        }
        for (CoopDialogArbiter other : requested) {
            if (other != null && other != kind && other.outranks(kind)) {
                return false;
            }
        }
        return true;
    }
}
