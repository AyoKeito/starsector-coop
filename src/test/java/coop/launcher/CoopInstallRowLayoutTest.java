package coop.launcher;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The install rows have to be as tall as the text they wrap to at the width the dialog gives them.
 * Measured before the rows have a width, a wrapping detail reports one line, and everything past
 * that line is drawn over the rows above and below it.
 */
class CoopInstallRowLayoutTest {

    /** The row that was seen overlapping its neighbours: a long detail plus a fix line. */
    private static final CoopInstallCheck.Row LONG = new CoopInstallCheck.Row(
            "no leftover -Dcoop.* in vmparams",
            CoopInstallCheck.Status.WARN,
            "4 left on the line: -Dcoop.hostPort=7777 -Dcoop.newGameSeed=MN-1234567890123456789"
                    + " -Dcoop.debug.diagnostics=true -Dcoop.debug.bridge=7801 (these override what"
                    + " the launcher writes, so a launch from here keeps using the old values)",
            "Press Fix, or open vmparams and delete every -Dcoop. flag from the java line.",
            CoopInstallFixer.Target.VMPARAMS);

    private static final CoopInstallCheck.Row SHORT = new CoopInstallCheck.Row(
            "co-op enabled in mods\\enabled_mods.json", CoopInstallCheck.Status.OK, "yes", "");

    private CoopLauncherApp app;
    private JFrame frame;
    private JDialog dialog;
    private JPanel rowsPanel;

    @BeforeEach
    void buildHiddenWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a desktop for native window layout");
        SwingUtilities.invokeAndWait(() -> {
            CoopTheme.install();
            app = new CoopLauncherApp();
            invoke("buildFrame");
            frame = field("frame", JFrame.class);
            frame.addNotify();
            frame.validate();
            dialog = field("installDialog", JDialog.class);
            rowsPanel = field("rowsPanel", JPanel.class);
        });
    }

    @AfterEach
    void dispose() throws Exception {
        if (app != null) {
            SwingUtilities.invokeAndWait(() -> invoke("shutdown"));
        }
    }

    @Test
    void aLongDetailWrapsInsideItsOwnRowInsteadOfOverTheRowsAroundIt() throws Exception {
        render(List.of(SHORT, LONG, SHORT), 1400, 700);
        assertRowsOwnTheirText();
    }

    /** The same rows in the window the dialog opens at, where the detail wraps to more lines. */
    @Test
    void theSameRowsSurviveTheDefaultDialogWidth() throws Exception {
        render(List.of(SHORT, LONG, SHORT), 720, 500);
        assertRowsOwnTheirText();
    }

    private void assertRowsOwnTheirText() throws Exception {
        List<Component> rows = rows();
        assertEquals(3, rows.size(), "every check must get a row");
        for (int i = 0; i < rows.size(); i++) {
            Rectangle row = rows.get(i).getBounds();
            assertTrue(row.height > 0, "row " + i + " has no height: " + row);
            for (int j = i + 1; j < rows.size(); j++) {
                Rectangle other = rows.get(j).getBounds();
                assertFalse(row.intersects(other),
                        () -> "rows overlap: " + row + " and " + other);
            }
            Rectangle inside = new Rectangle(0, 0, row.width, row.height);
            for (Component child : ((Container) rows.get(i)).getComponents()) {
                assertTrue(inside.contains(child.getBounds()), "row " + i + " clips "
                        + child.getClass().getSimpleName() + " " + child.getBounds()
                        + " out of " + inside);
            }
        }
        assertTrue(rows.get(1).getHeight() > rows.get(0).getHeight() + 4,
                () -> "the wrapped row must be taller than a one-line row: "
                        + rows.get(1).getHeight() + " vs " + rows.get(0).getHeight());
        Rectangle last = rows.get(2).getBounds();
        assertTrue(last.y + last.height <= rowsPanel.getHeight(),
                () -> "the rows must fit the stack: " + last + " in " + rowsPanel.getBounds());

        Container wrapped = (Container) rows.get(1);
        Rectangle detail = first(wrapped, JTextArea.class).getBounds();
        Rectangle button = first(wrapped, JButton.class).getBounds();
        Rectangle fixLine = last(wrapped, JLabel.class).getBounds();
        assertTrue(button.x >= detail.x + detail.width,
                () -> "Fix belongs to the right of the detail: " + button + " after " + detail);
        assertTrue(button.y < detail.y + detail.height,
                () -> "Fix belongs on the detail's first line: " + button + " beside " + detail);
        assertTrue(fixLine.y >= detail.y + detail.height,
                () -> "the fix line belongs under the detail: " + fixLine + " under " + detail);
    }

    @Test
    void togglingAndRefreshingRerenderCleanly() throws Exception {
        render(List.of(SHORT, LONG, SHORT), 1400, 700);
        int tallRow = rows().get(1).getHeight();

        setField("showAllRows", false);
        settle(() -> invoke("renderRows"));
        List<Component> failuresOnly = rows();
        assertEquals(1, failuresOnly.size(), "hiding passed checks leaves the warning");
        assertEquals(tallRow, failuresOnly.get(0).getHeight(),
                "the same row must keep its wrapped height when it is re-rendered alone");

        setField("showAllRows", true);
        settle(() -> invoke("renderRows"));
        assertEquals(3, rows().size());
        assertEquals(tallRow, rows().get(1).getHeight());
    }

    private static <T> T first(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " on the row");
    }

    private static <T> T last(Container parent, Class<T> type) {
        T found = null;
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                found = type.cast(child);
            }
        }
        assertNotNull(found, "no " + type.getSimpleName() + " on the row");
        return found;
    }

    /** Stages the rows in the install dialog at a realistic size and lets the layout settle. */
    private void render(List<CoopInstallCheck.Row> rows, int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            setField("installRows", new ArrayList<>(rows));
            setField("showAllRows", true);
            dialog.setSize(width, height);
            dialog.addNotify();
            invoke("renderRows");
        });
        settle(() -> { });
    }

    /**
     * Lays the dialog out until the width-first measurement has converged. A row that has never
     * been given a width cannot know how tall its text wraps, so the first pass schedules a second.
     */
    private void settle(Runnable before) throws Exception {
        SwingUtilities.invokeAndWait(before::run);
        for (int pass = 0; pass < 6; pass++) {
            SwingUtilities.invokeAndWait(() -> {
                dialog.invalidate();
                dialog.validate();
            });
        }
    }

    private List<Component> rows() throws Exception {
        List<Component> found = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> found.addAll(List.of(rowsPanel.getComponents())));
        return found;
    }

    private void setField(String name, Object value) {
        try {
            Field field = CoopLauncherApp.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(app, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void invoke(String name) {
        try {
            Method method = CoopLauncherApp.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(app);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private <T> T field(String name, Class<T> type) {
        try {
            Field field = CoopLauncherApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(app));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
