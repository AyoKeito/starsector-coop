package coop.ui;

/**
 * A coop interaction dialog the pump can take down on its own: reconnect, lobby, connecting and
 * desync dialogs all implement it so one controller (retry-until-shown, close-on-demand) can drive
 * any of them. {@link #close()} must be idempotent and must never throw.
 */
public interface CoopDismissableDialog {
    /** Dismisses the dialog if it is still on screen; safe to call repeatedly and from the pump. */
    void close();
}
