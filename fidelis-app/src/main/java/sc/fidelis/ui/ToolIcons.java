package sc.fidelis.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.UIManager;

import ij.gui.Toolbar;

/** Simple vector glyphs for the IJ1 tools, drawn in the current foreground colour (no external assets). */
final class ToolIcons {
    /** One palette entry: IJ1 tool id, display name and hint. */
    record Tool(int id, String name, String hint) {}

    static final Tool[] TOOLS = {
        new Tool(Toolbar.RECTANGLE, "Rectangle", "Rectangle selection"),
        new Tool(Toolbar.OVAL, "Oval", "Oval selection"),
        new Tool(Toolbar.POLYGON, "Polygon", "Polygon selection"),
        new Tool(Toolbar.FREEROI, "Freehand", "Freehand selection"),
        new Tool(Toolbar.LINE, "Line", "Straight line selection"),
        new Tool(Toolbar.POINT, "Point", "Point tool"),
        new Tool(Toolbar.WAND, "Wand", "Wand (tracing) tool"),
        new Tool(Toolbar.TEXT, "Text", "Text tool"),
        new Tool(Toolbar.MAGNIFIER, "Zoom", "Magnifying glass: click zooms in, Alt-click zooms out"),
        new Tool(Toolbar.HAND, "Hand", "Scrolling tool (also: hold Space and drag)"),
    };

    private ToolIcons() {}

    static Icon of(int toolId) {
        return new Glyph(toolId);
    }

    private static final class Glyph implements Icon {
        private static final int SIZE = 20;
        private final int tool;

        Glyph(int tool) {
            this.tool = tool;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x0, int y0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x0, y0);
            Color fg = c.isEnabled() ? c.getForeground() : UIManager.getColor("Label.disabledForeground");
            if (c instanceof AbstractButton b && b.isSelected() && b.isEnabled()) fg = b.getForeground();
            g.setColor(fg);
            BasicStroke solid = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            BasicStroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 2f}, 0f);
            g.setStroke(solid);
            switch (tool) {
                case Toolbar.RECTANGLE -> {
                    g.setStroke(dashed);
                    g.draw(new RoundRectangle2D.Double(3.5, 5.5, 13, 9, 1, 1));
                }
                case Toolbar.OVAL -> {
                    g.setStroke(dashed);
                    g.draw(new Ellipse2D.Double(3.5, 4.5, 13, 11));
                }
                case Toolbar.POLYGON -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(4, 14);
                    p.lineTo(6, 5);
                    p.lineTo(15, 4);
                    p.lineTo(16, 13);
                    p.closePath();
                    g.draw(p);
                    for (double[] v : new double[][] {{4, 14}, {6, 5}, {15, 4}, {16, 13}})
                        g.fill(new Ellipse2D.Double(v[0] - 1.6, v[1] - 1.6, 3.2, 3.2));
                }
                case Toolbar.FREEROI -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(4, 12);
                    p.curveTo(2, 6, 8, 3, 11, 6);
                    p.curveTo(14, 8, 18, 5, 17, 11);
                    p.curveTo(16, 17, 8, 16, 4, 12);
                    g.draw(p);
                }
                case Toolbar.LINE -> {
                    g.draw(new Line2D.Double(4, 15, 16, 5));
                    g.fill(new Ellipse2D.Double(2.4, 13.4, 3.2, 3.2));
                    g.fill(new Ellipse2D.Double(14.4, 3.4, 3.2, 3.2));
                }
                case Toolbar.POINT -> {
                    g.draw(new Ellipse2D.Double(5, 5, 10, 10));
                    g.draw(new Line2D.Double(10, 2, 10, 7));
                    g.draw(new Line2D.Double(10, 13, 10, 18));
                    g.draw(new Line2D.Double(2, 10, 7, 10));
                    g.draw(new Line2D.Double(13, 10, 18, 10));
                    g.fill(new Ellipse2D.Double(8.8, 8.8, 2.4, 2.4));
                }
                case Toolbar.WAND -> {
                    g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(4, 16, 11, 9));
                    g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(14, 3, 14, 7));
                    g.draw(new Line2D.Double(12, 5, 16, 5));
                    g.draw(new Line2D.Double(17, 9, 17, 11));
                    g.draw(new Line2D.Double(16, 10, 18, 10));
                }
                case Toolbar.TEXT -> {
                    g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
                    FontMetrics fm = g.getFontMetrics();
                    String s = "A";
                    g.drawString(s, (SIZE - fm.stringWidth(s)) / 2f, 15.5f);
                }
                case Toolbar.MAGNIFIER -> {
                    g.draw(new Ellipse2D.Double(3.5, 3.5, 10, 10));
                    g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(12, 12, 17, 17));
                }
                case Toolbar.HAND -> {
                    g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D p = new Path2D.Double();
                    p.moveTo(5, 11);
                    p.lineTo(5, 8);
                    p.moveTo(8, 10);
                    p.lineTo(8, 4);
                    p.moveTo(11, 10);
                    p.lineTo(11, 3.5);
                    p.moveTo(14, 10);
                    p.lineTo(14, 5);
                    p.moveTo(5, 11);
                    p.curveTo(3.5, 10, 2.5, 11.5, 4, 13.5);
                    p.curveTo(6, 17, 8, 17.5, 11, 17.5);
                    p.curveTo(15, 17.5, 17, 15, 17, 11);
                    p.lineTo(17, 8);
                    g.draw(p);
                }
                default -> g.drawRect(4, 4, 12, 12);
            }
            g.dispose();
        }
    }
}
