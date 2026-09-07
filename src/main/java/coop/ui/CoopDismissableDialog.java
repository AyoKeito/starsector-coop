package coop.ui;

/**
 * A coop interaction dialog the pump can take down on its own: reconnect, lobby, connecting and
 * desync dialogs all implement it so one controller (retry-until-shown, close-on-demand) can drive
 * any of them. {@link #close()} must be idempotent and must never throw.
 */
public interface CoopDismissableDialog {
    /** Dismisses the dialog if it is still on screen; safe to call repeatedly and from the pump. */
    void close();

    /**
     * One line naming this dialog for the agent bridge's {@code screen} verb, so a smoke run can tell
     * "the guest is sitting on the reconnect dialog" from "the guest is at a market" without looking
     * at the screen.
     *
     * <p>The default is the class name because that is always available and never wrong; the two
     * dialogs whose text a tester actually needs — the desync dialog's classified reason and the
     * reconnect dialog's headline — override it. Must never throw: the bridge reads it out of a
     * response builder that has no dialog of its own to fall back to.
     */
    default String bridgeTitle() {
        return getClass().getSimpleName();
    }
}
