package coop.launcher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;

import com.formdev.flatlaf.util.UIScale;

/** Small layout primitives shared by the setup screen and its owned detail windows. */
final class CoopLauncherUi {
    private CoopLauncherUi() {
    }

    static JPanel panel() {
        JPanel panel = new JPanel(new BorderLayout(12, 10));
        panel.setOpaque(false);
        return panel;
    }

    static JPanel beside(JComponent content, JComponent action) {
        JPanel panel = panel();
        panel.add(content, BorderLayout.CENTER);
        panel.add(action, BorderLayout.EAST);
        return panel;
    }

    /** Keep controls at their natural height and centered beside multiline content. */
    static JPanel centered(JComponent component) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.add(component);
        return panel;
    }

    /** Reserve room for wrapping, but center the visible status text beside its action. */
    static JPanel statusBlock(JComponent heading, JTextArea detail, int lines) {
        JPanel panel = new JPanel(null) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(1, heading.getPreferredSize().height + UIScale.scale(4)
                        + detail.getFontMetrics(detail.getFont()).getHeight() * lines);
            }

            @Override
            public void doLayout() {
                int gap = UIScale.scale(4);
                int headingHeight = heading.getPreferredSize().height;
                int available = Math.max(0, getHeight() - headingHeight - gap);
                detail.setSize(getWidth(), available);
                int detailHeight = Math.min(available, detail.getPreferredSize().height);
                int top = Math.max(0, (getHeight() - headingHeight - gap - detailHeight) / 2);
                heading.setBounds(0, top, getWidth(), headingHeight);
                detail.setBounds(0, top + headingHeight + gap, getWidth(), detailHeight);
            }
        };
        panel.setOpaque(false);
        panel.add(heading);
        panel.add(detail);
        return panel;
    }

    static javax.swing.JButton iconButton(String label, CoopIcons.Symbol symbol) {
        javax.swing.JButton button = CoopTheme.ghost("");
        CoopIcons.apply(button, symbol);
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
        button.setMargin(new java.awt.Insets(6, 6, 6, 6));
        return button;
    }

    /** Reserve the largest label so progress and success never move neighboring controls. */
    static void stableWidth(AbstractButton button, String... labels) {
        Dimension size = button.getPreferredSize();
        FontMetrics metrics = button.getFontMetrics(button.getFont());
        int padding = size.width - metrics.stringWidth(button.getText());
        for (String label : labels) {
            size.width = Math.max(size.width, metrics.stringWidth(label) + padding);
        }
        button.setPreferredSize(size);
        button.setMinimumSize(size);
    }

    /** A reserved status slot. Full detail belongs in the associated detail window. */
    static JTextArea summary(int lines) {
        JTextArea area = new JTextArea() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(1, getFontMetrics(getFont()).getHeight() * lines
                        + getInsets().top + getInsets().bottom + 4);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(CoopTheme.MUTED);
        area.setFont(javax.swing.UIManager.getFont("Label.font"));
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    static JScrollPane scroll(JComponent content) {
        CoopTheme.ScrollColumn column = new CoopTheme.ScrollColumn();
        column.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(column,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(CoopTheme.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(24));
        return scroll;
    }

    static JDialog dialog(JFrame owner, String title, JComponent content,
                          int width, int height, boolean modal, String hint) {
        JDialog dialog = new JDialog(owner, title, modal);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        JPanel root = panel();
        root.setOpaque(true);
        root.setBackground(CoopTheme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        root.add(content, BorderLayout.CENTER);
        javax.swing.JButton done = CoopTheme.secondary("Done");
        done.addActionListener(event -> dialog.setVisible(false));
        root.add(beside(CoopTheme.small(hint), done), BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close-panel");
        dialog.getRootPane().getActionMap().put("close-panel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dialog.setVisible(false);
            }
        });
        Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        dialog.setSize(Math.min(UIScale.scale(width), screen.width - 24),
                Math.min(UIScale.scale(height), screen.height - 24));
        dialog.setMinimumSize(new Dimension(Math.min(UIScale.scale(Math.min(width, 620)), screen.width - 24),
                Math.min(UIScale.scale(260), screen.height - 24)));
        return dialog;
    }

    static String brief(String text, int limit) {
        String line = text.replaceAll("\\s+", " ").trim();
        if (line.length() <= limit) {
            return line;
        }
        int end = line.lastIndexOf(' ', limit - 1);
        return line.substring(0, end > 0 ? end : limit - 1) + "…";
    }
}
