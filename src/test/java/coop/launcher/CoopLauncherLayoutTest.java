package coop.launcher;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real Swing layout regressions; no game, clipboard, file writes or network probes. */
class CoopLauncherLayoutTest {
    private CoopLauncherApp app;
    private JFrame frame;

    @BeforeEach
    void buildHiddenWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a desktop for native window layout");
        SwingUtilities.invokeAndWait(() -> {
            CoopTheme.install();
            app = new CoopLauncherApp();
            invoke("buildFrame");
            frame = field("frame", JFrame.class);
            field("hostSegment", JToggleButton.class).setSelected(true);
            invoke("onRoleChanged");
            frame.addNotify();
            frame.validate();
        });
        // Campaign selection applies restored world fields on the following event-loop turn.
        SwingUtilities.invokeAndWait(() -> frame.validate());
    }

    @AfterEach
    void dispose() throws Exception {
        if (app != null) {
            SwingUtilities.invokeAndWait(() -> invoke("shutdown"));
        }
    }

    @Test
    void bothRolesFitTheDefaultWindowAndShareTheSameLaunchBar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = (JScrollPane) find(frame, "setupScroll");
            assertFalse(scroll.getVerticalScrollBar().isVisible(), () -> "Host setup scrolls: content "
                    + scroll.getViewport().getView().getPreferredSize() + ", viewport "
                    + scroll.getViewport().getExtentSize());
            int spareHeight = scroll.getViewport().getExtentSize().height
                    - scroll.getViewport().getView().getPreferredSize().height;
            assertTrue(spareHeight <= com.formdev.flatlaf.util.UIScale.scale(12),
                    () -> "Default window leaves excess vertical space: " + spareHeight);
            Rectangle footer = find(frame, "launchFooter").getBounds();
            field("guestSegment", JToggleButton.class).doClick();
            frame.validate();
            assertFalse(scroll.getVerticalScrollBar().isVisible(), "Empty join setup must fit");
            field("guestInviteField", JTextField.class).setText(
                    "coop://203.0.113.9:7777/?seed=MN-8402913377120455081&size=normal&age=mixed");
            frame.validate();
            assertFalse(scroll.getVerticalScrollBar().isVisible(), "Accepted invite must still fit");
            assertEquals(footer, find(frame, "launchFooter").getBounds());
        });
    }

    @Test
    void logsAndChangingFeedbackDoNotMoveSetupOrResizeActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Rectangle footer = find(frame, "launchFooter").getBounds();
            Rectangle setup = find(frame, "setupScroll").getBounds();
            JButton copy = field("copyInviteButton", JButton.class);
            int width = copy.getPreferredSize().width;
            copy.setText("Copied");
            JDialog logs = field("logDialog", JDialog.class);
            logs.setFocusableWindowState(false);
            logs.setLocation(-10000, -10000);
            logs.setVisible(true);
            frame.validate();
            assertEquals(footer, find(frame, "launchFooter").getBounds());
            assertEquals(setup, find(frame, "setupScroll").getBounds());
            assertEquals(width, copy.getPreferredSize().width);
            logs.setVisible(false);
        });
    }

    @Test
    void prototypeBaselinesIconsAndActionAlignmentArePreserved() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel title = (JLabel) find(frame, "launcherTitle");
            JLabel version = (JLabel) find(frame, "launcherVersion");
            assertEquals(bounds(title).y + title.getBaseline(title.getWidth(), title.getHeight()),
                    bounds(version).y + version.getBaseline(version.getWidth(), version.getHeight()),
                    "Version must share the title's text baseline");
            JButton launch = field("launchButton", JButton.class);
            assertCentered(bounds(find(frame, "launchStatus")), bounds(launch));
            assertCentered(bounds(find(frame, "installStatusRow"))
                    .union(bounds(field("footerHint", JTextArea.class))), bounds(launch));
            JButton copy = field("copyInviteButton", JButton.class);
            JButton edit = (JButton) find(frame, "editHostConnection");
            assertCentered(bounds(copy), bounds(edit));
            assertTrue(bounds(edit).x + edit.getWidth() <= bounds(copy).x,
                    "Edit belongs beside Copy invite");
            JLabel connectionTitle = field("connectionTitle", JLabel.class);
            JTextArea summary = field("connectionSummary", JTextArea.class);
            Rectangle text = bounds(connectionTitle).union(bounds(summary));
            assertEquals(bounds(connectionTitle).x, bounds(summary).x);
            assertCentered(text, bounds(field("connectionButton", JButton.class)));
            assertCentered(text, bounds(field("connectionDetailsButton", JButton.class)));
            for (String name : new String[] { "advancedToggle", "logToggle", "copyInviteButton",
                    "launchButton", "connectionDetailsButton" }) {
                assertNotNull(field(name, JButton.class).getIcon(), name + " needs its prototype icon");
            }
            assertNotNull(field("hostSegment", JToggleButton.class).getIcon());
            assertNotNull(field("guestSegment", JToggleButton.class).getIcon());
            assertNotNull(edit.getIcon());
            assertEquals("Edit connection", edit.getAccessibleContext().getAccessibleName());
            assertNotNull(field("installSummary", JLabel.class).getIcon());
        });
    }

    private Rectangle bounds(Component component) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), frame);
    }

    private static void assertCentered(Rectangle content, Rectangle action) {
        assertTrue(Math.abs(content.getCenterY() - action.getCenterY()) <= 1,
                () -> "Action " + action + " must be vertically centered beside " + content);
    }

    @Test
    void smallWindowsCanScrollSetupWhileLaunchRemainsVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(frame.getWidth(), frame.getMinimumSize().height);
            frame.validate();
            JScrollPane scroll = (JScrollPane) find(frame, "setupScroll");
            assertTrue(scroll.getVerticalScrollBar().isVisible(), "Small windows must not hide setup content");
            Rectangle footer = find(frame, "launchFooter").getBounds();
            assertTrue(footer.y >= 0);
            assertTrue(footer.y + footer.height <= frame.getContentPane().getHeight());
        });
    }

    @Test
    void invalidInvitesCannotLaunchUsingThePreviouslyAcceptedConnection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            setField("layout", CoopInstallLayout.ofInstallRoot(new File("build/layout-test-install")));
            field("guestSegment", JToggleButton.class).doClick();
            JTextField invite = field("guestInviteField", JTextField.class);
            invite.setText("coop://203.0.113.9:7777/?seed=MN-8402913377120455081");
            assertTrue(field("launchButton", JButton.class).isEnabled());
            invite.setText("not an invite");
            assertFalse(field("launchButton", JButton.class).isEnabled());
            invite.setText("");
            assertTrue(field("launchButton", JButton.class).isEnabled(), "Clearing permits manual setup");
        });
    }

    @Test
    void editingTheEndpointDiscardsTheOldConnectionResult() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            field("guestSegment", JToggleButton.class).doClick();
            setField("connectionChecked", true);
            field("connectionTitle", JLabel.class).setText("Host reachable");
            field("guestHostField", JTextField.class).setText("203.0.113.10");
            assertEquals("Connection not checked", field("connectionTitle", JLabel.class).getText());
            assertFalse(field("connectionChecked", Boolean.class));
        });
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

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                Component match = find(nested, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
