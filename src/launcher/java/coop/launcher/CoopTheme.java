package coop.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import org.apache.log4j.Logger;

/**
 * The launcher's look: one dark palette, a handful of component factories, and the two custom
 * surfaces (cards and chips) that FlatLaf does not draw for us.
 *
 * <p>Everything visual is decided here so {@link CoopLauncherApp} stays about behaviour. The palette
 * follows the direction the user gave on 2026-09-03 (dark only, in the spirit of TriOS): a navy
 * background, one cyan accent for the primary action, and the four status colours used by the
 * install rows and the connection chips.
 */
final class CoopTheme {

    private static final Logger LOG = Logger.getLogger(CoopTheme.class);

    static final Color BG = hex("#111820");
    static final Color CARD = hex("#1a232d");
    static final Color CARD_BORDER = hex("#283442");
    static final Color FIELD = hex("#0e141b");
    static final Color TEXT = hex("#e6edf3");
    static final Color MUTED = hex("#a0adbc");
    static final Color ACCENT = hex("#4fd1e8");
    static final Color ACCENT_HOVER = hex("#72dcef");
    static final Color ACCENT_PRESSED = hex("#36b9d1");
    static final Color ON_ACCENT = hex("#0b1218");
    static final Color OK = hex("#3ecf8e");
    static final Color WARN = hex("#f4a63a");
    static final Color FAIL = hex("#f0584f");
    static final Color INFO = hex("#7b8794");

    static final int CARD_ARC = 14;

    private CoopTheme() {
    }

    /** Installs FlatLaf dark with the launcher's palette. Call once, before any component exists. */
    static void install() {
        Map<String, String> extra = new HashMap<>();
        extra.put("@background", "#111820");
        extra.put("@foreground", "#e6edf3");
        extra.put("@accentColor", "#4fd1e8");
        FlatLaf.setGlobalExtraDefaults(extra);
        if (!FlatDarkLaf.setup()) {
            LOG.warn("FlatLaf did not install; the window will use the default look and feel");
        }
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ComboBox.arc", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.minimumHeight", 32);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.borderColor", CARD_BORDER);
        UIManager.put("Component.disabledBorderColor", CARD_BORDER);
        UIManager.put("TextField.background", FIELD);
        UIManager.put("PasswordField.background", FIELD);
        UIManager.put("FormattedTextField.background", FIELD);
        UIManager.put("Spinner.background", FIELD);
        UIManager.put("ComboBox.background", FIELD);
        UIManager.put("ComboBox.buttonBackground", FIELD);
        UIManager.put("TextArea.background", FIELD);
        UIManager.put("PasswordField.showRevealButton", true);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("ToolTip.background", CARD);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(CARD_BORDER));
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("Panel.background", BG);
        Font base = UIManager.getFont("defaultFont");
        if (base != null) {
            UIManager.put("defaultFont", base.deriveFont((float) base.getSize() + 1f));
        }
    }

    // ---- surfaces -------------------------------------------------------------------------------

    /** A rounded card with a title row and a body panel the caller fills. */
    static final class Card extends JPanel {

        final JPanel header = new JPanel(new GridBagLayout());
        final JPanel body = new JPanel();
        final JLabel titleLabel;
        final JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        Card(String title) {
            this(title, false);
        }

        Card(String title, boolean compact) {
            setOpaque(false);
            setLayout(new GridBagLayout());
            setBorder(compact ? BorderFactory.createEmptyBorder(10, 14, 10, 14)
                    : BorderFactory.createEmptyBorder(16, 18, 16, 18));

            header.setOpaque(false);
            titleLabel = new JLabel(title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize() + 3f));
            titleLabel.setForeground(TEXT);
            trailing.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.anchor = GridBagConstraints.WEST;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            header.add(titleLabel, c);
            c.gridx = 1;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            header.add(trailing, c);

            body.setOpaque(false);
            body.setLayout(new GridBagLayout());

            c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, compact ? 6 : 12, 0);
            add(header, c);
            c.gridy = 1;
            c.insets = new Insets(0, 0, 0, 0);
            add(body, c);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, CARD_ARC, CARD_ARC);
                g2.setColor(CARD_BORDER);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, CARD_ARC, CARD_ARC);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** An inline icon and label for the persistent installation status. */
    static final class StatusLabel extends JLabel {
        StatusLabel(String text, Color color) {
            setFont(getFont().deriveFont(getFont().getSize() - 1f));
            setIconTextGap(com.formdev.flatlaf.util.UIScale.scale(6));
            set(text, color);
        }

        void set(String text, Color color) {
            setText(text);
            setForeground(color);
            setIcon(CoopIcons.of(color.equals(OK) ? CoopIcons.Symbol.CHECK
                    : color.equals(WARN) || color.equals(FAIL) ? CoopIcons.Symbol.ALERT
                    : CoopIcons.Symbol.MINUS));
        }
    }

    /** A pill with a coloured dot and a short connection result. */
    static final class Chip extends JLabel {

        private Color dot = INFO;

        Chip(String text, Color dot) {
            super(text);
            this.dot = dot;
            setOpaque(false);
            setForeground(TEXT);
            setBorder(BorderFactory.createEmptyBorder(4, 22, 4, 12));
            setFont(getFont().deriveFont((float) getFont().getSize() - 0.5f));
        }

        void set(String text, Color color) {
            setText(text);
            this.dot = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = getHeight() - 1;
                g2.setColor(FIELD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.setColor(CARD_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                int d = 8;
                int y = (getHeight() - d) / 2;
                g2.setColor(dot);
                g2.fillOval(9, y, d, d);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** A small coloured circle used in front of each install row. */
    static final class Dot extends JComponent {

        private Color color;

        Dot(Color color) {
            this.color = color;
            setPreferredSize(new Dimension(10, 10));
            setMinimumSize(new Dimension(10, 10));
        }

        void set(Color color) {
            this.color = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                int d = Math.min(getWidth(), getHeight());
                g2.fillOval((getWidth() - d) / 2, (getHeight() - d) / 2, d, d);
            } finally {
                g2.dispose();
            }
        }
    }

    // ---- factories ------------------------------------------------------------------------------

    static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        return label;
    }

    static JLabel small(String text) {
        JLabel label = muted(text);
        label.setFont(label.getFont().deriveFont((float) label.getFont().getSize() - 1f));
        return label;
    }

    /** Sentence-case labels remain readable at the default desktop scale. */
    static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    static JTextField textField(String placeholder) {
        JTextField field = new JTextField();
        field.setColumns(8);
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        inputHeight(field);
        return field;
    }

    static JPasswordField passwordField(String placeholder) {
        JPasswordField field = new JPasswordField();
        field.setColumns(8);
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        inputHeight(field);
        return field;
    }

    static void inputHeight(JComponent field) {
        Dimension preferred = field.getPreferredSize();
        preferred.height = Math.max(preferred.height, com.formdev.flatlaf.util.UIScale.scale(32));
        field.setPreferredSize(preferred);
        Dimension minimum = field.getMinimumSize();
        minimum.height = preferred.height;
        field.setMinimumSize(minimum);
    }

    private static Component labelTarget(Component component) {
        if (component instanceof javax.swing.text.JTextComponent
                || component instanceof javax.swing.JComboBox<?> || component instanceof javax.swing.JSpinner) {
            return component;
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                Component target = labelTarget(child);
                if (target != null) {
                    return target;
                }
            }
        }
        return null;
    }

    /** The one cyan button: Launch. */
    static JButton primary(String text) {
        JButton button = new JButton(text);
        button.putClientProperty("FlatLaf.style",
                "background: #4fd1e8; foreground: #0b1218; hoverBackground: #72dcef;"
                        + " pressedBackground: #36b9d1; disabledBackground: #2a3644;"
                        + " disabledText: #a0adbc; borderWidth: 0; focusWidth: 1; arc: 8;"
                        + " margin: 9,20,9,20");
        button.setFont(button.getFont().deriveFont(Font.BOLD, (float) button.getFont().getSize() + 1f));
        return button;
    }

    /** A bordered secondary button. */
    static JButton secondary(String text) {
        JButton button = new JButton(text);
        button.putClientProperty("FlatLaf.style",
                "background: #1f2a36; hoverBackground: #283442; pressedBackground: #16202a;"
                        + " borderColor: #34424f; focusedBorderColor: #4fd1e8; arc: 8;"
                        + " margin: 6,16,6,16");
        return button;
    }

    /** A borderless button for the low-key actions (refresh, open folder, toggles). */
    static JButton ghost(String text) {
        JButton button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty("FlatLaf.style",
                "toolbar.hoverBackground: #283442; toolbar.pressedBackground: #16202a;"
                        + " margin: 6,12,6,12; arc: 8");
        button.setForeground(MUTED);
        return button;
    }

    /** A button that lives inside a text field's trailing slot. */
    static JButton inline(String text) {
        JButton button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty("FlatLaf.style",
                "toolbar.hoverBackground: #283442; margin: 2,8,2,8; arc: 8");
        button.setForeground(ACCENT);
        return button;
    }

    static JToggleButton segment(String text) {
        JToggleButton button = new JToggleButton(text);
        button.putClientProperty("FlatLaf.style",
                "background: #0e141b; foreground: #a0adbc; hoverBackground: #283442;"
                        + " selectedBackground: #233441; selectedForeground: #e6edf3;"
                        + " pressedBackground: #35495b; borderWidth: 0; focusWidth: 1; arc: 8;"
                        + " margin: 8,22,8,22");
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setForeground(MUTED);
        button.addItemListener(event -> button.setForeground(button.isSelected() ? TEXT : MUTED));
        return button;
    }

    /** A wrapping, read-only, muted paragraph that never clips a long sentence. */
    static JTextArea paragraph(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setForeground(MUTED);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }

    static void trailing(JTextField field, JComponent component) {
        field.putClientProperty("JTextField.trailingComponent", component);
    }

    static Component vgap(int px) {
        return Box.createVerticalStrut(px);
    }

    static Component hgap(int px) {
        return Box.createHorizontalStrut(px);
    }

    static Color statusColor(CoopInstallCheck.Status status) {
        return switch (status) {
            case OK -> OK;
            case INFO -> INFO;
            case WARN -> WARN;
            case FAIL -> FAIL;
        };
    }

    static Color hex(String value) {
        return Color.decode(value);
    }

    /** A {@link FlowLayout} whose preferred height accounts for wrapped rows. */
    static final class WrapLayout extends FlowLayout {

        WrapLayout(int hgap, int vgap) {
            super(FlowLayout.LEFT, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= getHgap() + 1;
            return minimum;
        }

        private Dimension layoutSize(java.awt.Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                java.awt.Container container = target;
                while (container.getSize().width == 0 && container.getParent() != null) {
                    container = container.getParent();
                }
                int targetWidth = container.getSize().width;
                if (targetWidth == 0) {
                    targetWidth = Integer.MAX_VALUE;
                }
                Insets insets = target.getInsets();
                int horizontalInsetsAndGap = insets.left + insets.right + getHgap() * 2;
                int maxWidth = targetWidth - horizontalInsetsAndGap;
                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) {
                        continue;
                    }
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth != 0) {
                        rowWidth += getHgap();
                    }
                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }
                addRow(dim, rowWidth, rowHeight);
                dim.width += horizontalInsetsAndGap;
                dim.height += insets.top + insets.bottom + getVgap() * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) {
                dim.height += getVgap();
            }
            dim.height += rowHeight;
        }
    }

    /** A panel for a vertical scroll pane: as wide as the viewport, as tall as it needs. */
    static final class ScrollColumn extends JPanel implements javax.swing.Scrollable {

        ScrollColumn() {
            super(new java.awt.BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int dir) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int dir) {
            return visible.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ---- form grid ------------------------------------------------------------------------------

    /**
     * Two-column form layout over a {@link GridBagLayout}: each cell is a label above its field, and
     * a cell may span both columns. Weights are set so fields stretch and never collapse.
     */
    static final class Form {

        private final JPanel target;
        private int row;

        Form(JPanel target) {
            this.target = target;
            target.setLayout(new GridBagLayout());
        }

        void pair(String leftLabel, JComponent left, String rightLabel, JComponent right) {
            add(cell(leftLabel, left), 0, 1, 0.5, new Insets(0, 0, 10, 6));
            add(cell(rightLabel, right), 1, 1, 0.5, new Insets(0, 6, 10, 0));
            row++;
        }

        void full(String label, JComponent component) {
            add(cell(label, component), 0, 2, 1.0, new Insets(0, 0, 10, 0));
            row++;
        }

        void raw(JComponent component) {
            add(component, 0, 2, 1.0, new Insets(0, 0, 0, 0));
            row++;
        }

        private JPanel cell(String label, JComponent component) {
            JPanel cell = new JPanel(new GridBagLayout());
            cell.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 2, 4, 0);
            if (label != null) {
                JLabel fieldLabel = fieldLabel(label);
                Component target = labelTarget(component);
                fieldLabel.setLabelFor(target == null ? component : target);
                cell.add(fieldLabel, c);
                c.gridy = 1;
            }
            c.insets = new Insets(0, 0, 0, 0);
            cell.add(component, c);
            return cell;
        }

        private void add(JComponent component, int x, int span, double weight, Insets insets) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = x;
            c.gridy = row;
            c.gridwidth = span;
            c.weightx = weight;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.insets = insets;
            target.add(component, c);
        }
    }
}
