package coop.launcher;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.AbstractButton;
import javax.swing.Icon;

import com.formdev.flatlaf.util.UIScale;

/** Small outline icons, drawn as vectors so they work without a symbol font at any UI scale. */
final class CoopIcons implements Icon {
    enum Symbol { SETTINGS, TERMINAL, COPY, PASTE, EDIT, PLAY, HOST, LINK, CHECK, MINUS, ALERT, BUSY }

    private final Symbol symbol;
    private final int size;

    private CoopIcons(Symbol symbol, int size) {
        this.symbol = symbol;
        this.size = size;
    }

    static Icon of(Symbol symbol) {
        return of(symbol, 16);
    }

    static Icon of(Symbol symbol, int size) {
        return new CoopIcons(symbol, size);
    }

    static void apply(AbstractButton button, Symbol symbol) {
        button.setIcon(of(symbol));
        button.setIconTextGap(UIScale.scale(7));
    }

    @Override public int getIconWidth() { return UIScale.scale(size); }
    @Override public int getIconHeight() { return UIScale.scale(size); }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.scale(getIconWidth() / 24.0, getIconHeight() / 24.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(size > 16 ? 1.2f : 1.8f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(component.isEnabled() ? component.getForeground() : CoopTheme.MUTED);
            switch (symbol) {
                case SETTINGS -> {
                    path(g, 3, 6, 7, 6); path(g, 11, 6, 21, 6);
                    path(g, 3, 18, 13, 18); path(g, 17, 18, 21, 18);
                    circle(g, 9, 6, 2); circle(g, 15, 18, 2);
                }
                case TERMINAL -> { path(g, 4, 5, 10, 11, 4, 17); path(g, 13, 18, 21, 18); }
                case COPY -> {
                    g.draw(new RoundRectangle2D.Double(9, 9, 12, 12, 3, 3));
                    path(g, 5, 15, 3, 15, 3, 3, 15, 3, 15, 5);
                }
                case PASTE -> {
                    path(g, 8, 5, 4, 5, 4, 21, 20, 21, 20, 5, 16, 5);
                    g.draw(new RoundRectangle2D.Double(8, 3, 8, 4, 2, 2));
                }
                case EDIT -> {
                    path(g, 15, 4, 20, 9, 9, 20, 3, 21, 4, 15, 15, 4, 18, 1, 23, 6, 20, 9);
                }
                case PLAY -> path(g, 6, 3, 21, 12, 6, 21, 6, 3);
                case HOST -> {
                    circle(g, 12, 9, 1.5);
                    path(g, 12, 11, 7, 22); path(g, 12, 11, 17, 22); path(g, 9, 18, 15, 18);
                    path(g, 7, 5, 5, 9, 7, 13); path(g, 17, 5, 19, 9, 17, 13);
                    path(g, 4, 2, 1, 9, 4, 16); path(g, 20, 2, 23, 9, 20, 16);
                }
                case LINK -> {
                    path(g, 9, 15, 15, 9);
                    Path2D link = new Path2D.Double();
                    link.moveTo(8, 12); link.curveTo(4, 8, 0, 15, 4, 19);
                    link.curveTo(8, 23, 15, 19, 12, 16);
                    link.moveTo(12, 8); link.curveTo(8, 4, 15, 0, 19, 4);
                    link.curveTo(23, 8, 19, 15, 16, 12);
                    g.draw(link);
                }
                case CHECK, MINUS, ALERT, BUSY -> {
                    circle(g, 12, 12, 10);
                    switch (symbol) {
                        case CHECK -> path(g, 7, 12, 10, 15, 17, 8);
                        case MINUS -> path(g, 8, 12, 16, 12);
                        case ALERT -> { path(g, 12, 7, 12, 13); path(g, 12, 17, 12, 17.1); }
                        case BUSY -> { path(g, 12, 6, 12, 12, 16, 14); }
                        default -> { }
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static void circle(Graphics2D g, double x, double y, double radius) {
        g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static void path(Graphics2D g, double... points) {
        Path2D path = new Path2D.Double();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        g.draw(path);
    }
}
