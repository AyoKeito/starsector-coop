package coop.ui;

import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.VisualPanelAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import coop.util.CoopLog;

import java.util.List;

/**
 * One line of numbers that change while a coop dialog is open, drawn as a label in the dialog's
 * visual panel instead of as text-panel content.
 *
 * <p><b>Why not the text panel.</b> Both earlier attempts went through {@code TextPanelAPI}: first
 * {@code clear()} and rebuild, then {@code replaceLastParagraph} on the last paragraph. Players
 * reported both as a flash, the second one on the reconnect dialog, which does nothing per second
 * except replace that one paragraph: in this engine the call re-lays out and re-fades the panel, and
 * with two paragraphs in it the whole panel appears to blink once a second. There is no variant of
 * "write to the text panel every second" that does not do this.
 *
 * <p><b>What this does instead.</b> {@code VisualPanelAPI.showCustomPanel(width, height, plugin)}
 * (0.98a API, verified in {@code starfarer.api.zip}) returns a {@link CustomPanelAPI}; a
 * {@code TooltipMakerAPI} element inside it holds one {@link LabelAPI}, and
 * {@link LabelAPI#setText(String)} changes the characters without touching the dialog's text panel
 * and without an appearance animation. The static text stays where it was, and the ticking number is
 * the only thing on screen that changes.
 *
 * <p><b>Failure is silence, not a broken dialog.</b> Any throw on the way in leaves the line absent:
 * one WARN, the dialog keeps its headline and its options, and the player loses a counter rather
 * than a way out. A throw from {@code setText} stops further updates for the same reason.
 */
final class CoopLiveDialogLine {

    /**
     * Size of the panel handed to {@code showCustomPanel}. One line of text with room to breathe;
     * the dialog's visual area is wider than this, so nothing is clipped, and the panel is drawn
     * where the portrait or image visual would otherwise be.
     */
    static final float PANEL_WIDTH = 400f;
    static final float PANEL_HEIGHT = 60f;

    /** Set once the label exists; null means "no live line on this dialog". */
    private LabelAPI label;
    /** What the label was last told to say, so an unchanged value costs one comparison. */
    private String text = "";
    /** Sticky: creating the panel failed, or an update threw. Either way, stop. */
    private boolean unavailable;

    /** Whether the line is on screen and can be updated. */
    boolean showing() {
        return label != null && !unavailable;
    }

    /** The text currently displayed, or {@code ""} when there is no line. */
    String text() {
        return text;
    }

    /**
     * Builds the panel and its label in the dialog's visual area.
     *
     * @return true when the line is on screen; false means the caller should hide the visual panel
     *         and carry on without live numbers
     */
    boolean install(InteractionDialogAPI dialog, String initialText) {
        label = null;
        unavailable = false;
        text = initialText == null ? "" : initialText;
        try {
            VisualPanelAPI visual = dialog == null ? null : dialog.getVisualPanel();
            if (visual == null) {
                unavailable = true;
                return false;
            }
            CustomPanelAPI panel = visual.showCustomPanel(PANEL_WIDTH, PANEL_HEIGHT, new NoInput());
            if (panel == null) {
                unavailable = true;
                return false;
            }
            TooltipMakerAPI element = panel.createUIElement(PANEL_WIDTH, PANEL_HEIGHT - 10f, false);
            if (element == null) {
                unavailable = true;
                return false;
            }
            LabelAPI made = element.addPara(text, 0f);
            // Sized once, here, rather than on every update: a label built around "0:09" and then
            // told to say "10:09 - Link: 310 ms over UDP" would draw outside its own box. Widening
            // it up front means setText never has to lay anything out again.
            if (made != null) {
                made.autoSizeToWidth(PANEL_WIDTH - 10f);
            }
            PositionAPI position = panel.addUIElement(element);
            if (position != null) {
                position.inTL(5f, 5f);
            }
            if (made == null) {
                unavailable = true;
                return false;
            }
            label = made;
            return true;
        } catch (Throwable ex) {
            label = null;
            unavailable = true;
            CoopLog.warn(CoopLiveDialogLine.class,
                    "Coop dialog could not create its live line; the dialog runs without it", ex);
            return false;
        }
    }

    /** Changes the label's characters, and nothing else, when the value actually moved. */
    void update(String next) {
        String wanted = next == null ? "" : next;
        if (label == null || unavailable || wanted.equals(text)) {
            return;
        }
        try {
            label.setText(wanted);
            text = wanted;
        } catch (Throwable ex) {
            // A label that throws once throws every frame. The last good value stays on screen.
            unavailable = true;
            CoopLog.warn(CoopLiveDialogLine.class,
                    "Coop dialog could not update its live line; it stops here", ex);
        }
    }

    /** Forgets the label, so a rebuilt dialog does not update one that is no longer shown. */
    void clear() {
        label = null;
        text = "";
        unavailable = false;
    }

    /**
     * The panel takes no input and draws nothing of its own; the label inside it draws itself. A
     * real instance rather than {@code null} because a plugin-less custom panel has known holes in
     * it (Phase 20.6 UI inventory), and an object with eight empty methods costs nothing.
     */
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
