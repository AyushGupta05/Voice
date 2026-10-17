package sc.fidelis.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * Small painted glyphs. The UI font (Segoe UI) has no geometric symbols, so markers and arrows are
 * drawn rather than typed; this also keeps them crisp in both themes.
 */
final class UiIcons {
    enum Kind { DIAMOND, LEFT, RIGHT, DOWN, DOWN_SMALL }

    private UiIcons() {}

    static Icon of(Kind kind, Color fixed) {
        return new Glyph(kind, fixed);
    }

    static Icon of(Kind kind) {
        return new Glyph(kind, null);
    }

    private record Glyph(Kind kind, Color fixed) implements Icon {
        @Override
        public int getIconWidth() {
            return kind == Kind.DIAMOND ? 9 : 8;
        }

        @Override
        public int getIconHeight() {
            return kind == Kind.DIAMOND ? 9 : 8;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color col = fixed;
            if (col == null) col = c.isEnabled() ? c.getForeground() : UIManager.getColor("Label.disabledForeground");
            g.setColor(col);
            g.translate(x, y);
            Path2D p = new Path2D.Double();
            switch (kind) {
                case DIAMOND -> {
                    p.moveTo(4.5, 0.5);
                    p.lineTo(8.5, 4.5);
                    p.lineTo(4.5, 8.5);
                    p.lineTo(0.5, 4.5);
                }
                case LEFT -> {
                    p.moveTo(6, 0.5);
                    p.lineTo(6, 7.5);
                    p.lineTo(1.5, 4);
                }
                case RIGHT -> {
                    p.moveTo(2, 0.5);
                    p.lineTo(2, 7.5);
                    p.lineTo(6.5, 4);
                }
                default -> {
                    p.moveTo(0.5, 2);
                    p.lineTo(7.5, 2);
                    p.lineTo(4, 6.5);
                }
            }
            p.closePath();
            g.fill(p);
            g.dispose();
        }
    }
}
