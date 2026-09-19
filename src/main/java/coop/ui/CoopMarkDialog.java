package coop.ui;

import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.VisualPanelAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import coop.mark.CoopMarkFormat;
import coop.util.CoopLog;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The note box for a log marker: one line of text, OK, Cancel.
 *
 * <p><b>An interaction dialog, not a nested custom dialog.</b> {@code showCustomDialog} only exists
 * on an interaction dialog that is already open, and the lobby dialog's comment records the second
 * reason: a nested dialog fires {@code customDialogCancel()} on ESC regardless of what the outer one
 * wants. So this is a plain {@link InteractionDialogPlugin} driven by {@link CoopDialogController},
 * exactly like the reconnect, lobby, connecting and desync dialogs.
 *
 * <p><b>That choice is also what suspends the guest's input blocker.</b> The pump asks
 * {@code CampaignUIAPI.isShowingDialog()} every frame and suspends {@code CoopCampaignInputBlocker}
 * while the answer is yes; an interaction dialog is what makes it yes. No explicit
 * {@code setInputBlockerSuspended} call is needed here, and adding one would give the suspend two
 * owners.
 *
 * <p><b>The pause is vanilla's.</b> Opening an interaction dialog pauses the campaign by itself and
 * the coop screen-pause carries that to the partner. Nothing here calls {@code setPaused} - on the
 * guest that is the "froze the trade tab" bug.
 *
 * <p><b>ESC cancels.</b> Unlike the lobby, this dialog is not holding anything together: the marker
 * is already in the log by the time it opens, and a player who changes their mind must be able to
 * get out with the key they expect.
 */
public final class CoopMarkDialog implements InteractionDialogPlugin, CoopDismissableDialog {

    private static final Object OPTION_OK = new Object();
    private static final Object OPTION_CANCEL = new Object();

    static final String TEXT_OK = "OK";
    static final String TEXT_CANCEL = "Cancel";

    /** Panel handed to {@code showCustomPanel}; one field with room around it. */
    static final float PANEL_WIDTH = 400f;
    static final float PANEL_HEIGHT = 70f;

    private final String markerId;
    /** {@code (markerId, note)}; the note is {@code ""} when the player cancelled. */
    private final BiConsumer<String, String> onDone;

    private InteractionDialogAPI dialog;
    private TextFieldAPI field;
    /** Guards the callback: OK then a close, or a close on its own, must report exactly once. */
    private boolean reported;

    public CoopMarkDialog(String markerId, BiConsumer<String, String> onDone) {
        this.markerId = markerId == null ? "" : markerId;
        this.onDone = onDone == null ? (id, note) -> { } : onDone;
    }

    /** The marker this box is attached to. */
    public String markerId() {
        return markerId;
    }

    @Override
    public String bridgeTitle() {
        return "Log marker " + markerId;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        reported = false;
        try {
            dialog.getTextPanel().addParagraph("Log marker " + markerId
                    + " is written. Add a note for both logs, or cancel.");
        } catch (Throwable ex) {
            logOnce("could not write its headline", ex);
        }
        if (!installField(dialog)) {
            try {
                dialog.hideVisualPanel();
            } catch (Throwable ignored) {
                // Cosmetic: a visual panel that will not hide beats no note box at all.
            }
        }
        renderOptions();
        try {
            dialog.setOptionOnEscape(TEXT_CANCEL, OPTION_CANCEL);
        } catch (Throwable ex) {
            logOnce("could not bind ESC to Cancel", ex);
        }
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionData == OPTION_OK) {
            finish(typedText());
            return;
        }
        if (optionData == OPTION_CANCEL) {
            finish("");
            return;
        }
        // Anything else would leave the player looking at an empty option panel.
        renderOptions();
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void advance(float amount) {
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return null;
    }

    @Override
    public Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> getMemoryMap() {
        return null;
    }

    /**
     * Dismisses the box. Idempotent, and reports a cancel if nothing has been reported yet, so the
     * service is never left waiting on a note for a dialog that is no longer on screen.
     */
    @Override
    public void close() {
        InteractionDialogAPI open = dialog;
        dialog = null;
        field = null;
        report("");
        if (open == null) {
            return;
        }
        try {
            open.dismiss();
        } catch (Throwable ex) {
            CoopLog.warn(CoopMarkDialog.class, "Coop marker note box could not be dismissed", ex);
        }
    }

    /** What is in the field right now; {@code ""} when the widget never built or will not answer. */
    String typedText() {
        TextFieldAPI current = field;
        if (current == null) {
            return "";
        }
        try {
            String text = current.getText();
            return text == null ? "" : text;
        } catch (Throwable ex) {
            logOnce("could not read its note field", ex);
            return "";
        }
    }

    private void finish(String note) {
        report(note);
        close();
    }

    private void report(String note) {
        if (reported) {
            return;
        }
        reported = true;
        try {
            onDone.accept(markerId, CoopMarkFormat.note(note));
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopMarkDialog.class, "Coop marker note callback failed", ex);
        }
    }

    private void renderOptions() {
        try {
            dialog.getOptionPanel().clearOptions();
            dialog.getOptionPanel().addOption(TEXT_OK, OPTION_OK);
            dialog.getOptionPanel().addOption(TEXT_CANCEL, OPTION_CANCEL);
        } catch (Throwable ex) {
            logOnce("could not build its options", ex);
        }
    }

    /**
     * The text field, in the dialog's visual area. {@code grabFocus} is what makes typing land in the
     * field rather than in the option panel's shortcut handling.
     *
     * @return true when the field is on screen
     */
    private boolean installField(InteractionDialogAPI dialog) {
        field = null;
        try {
            VisualPanelAPI visual = dialog.getVisualPanel();
            if (visual == null) {
                return false;
            }
            CustomPanelAPI panel = visual.showCustomPanel(PANEL_WIDTH, PANEL_HEIGHT, new NoInput());
            if (panel == null) {
                return false;
            }
            TooltipMakerAPI element = panel.createUIElement(PANEL_WIDTH, PANEL_HEIGHT - 10f, false);
            if (element == null) {
                return false;
            }
            element.addPara("Note (optional):", 0f);
            TextFieldAPI made = element.addTextField(PANEL_WIDTH - 20f, 4f);
            if (made == null) {
                return false;
            }
            made.setMaxChars(CoopMarkFormat.MAX_NOTE_CHARS);
            // Deliberately false: ESC is bound to Cancel above, and an undo-on-escape field would
            // swallow the first press and leave the player pressing it twice.
            made.setUndoOnEscape(false);
            made.setHandleCtrlV(true);
            made.grabFocus();
            PositionAPI position = panel.addUIElement(element);
            if (position != null) {
                position.inTL(5f, 5f);
            }
            field = made;
            return true;
        } catch (Throwable ex) {
            logOnce("could not build its note field", ex);
            field = null;
            return false;
        }
    }

    private void logOnce(String what, Throwable ex) {
        CoopLog.warn(CoopMarkDialog.class, "Coop marker note box " + what, ex);
    }

    /** The panel draws nothing of its own; the element inside it draws itself. */
    private static final class NoInput implements CustomUIPanelPlugin {
        @Override
        public void positionChanged(PositionAPI position) {
        }

        @Override
        public void renderBelow(float alphaMult) {
        }

        @Override
        public void render(float alphaMult) {
        }

        @Override
        public void advance(float amount) {
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
        }

        @Override
        public void buttonPressed(Object buttonId) {
        }
    }
}
