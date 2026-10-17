package sc.fidelis.penny.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import sc.fidelis.ui.Theme;

/**
 * A plan's steps as a compact wrapping trail: "✓ 8-bit › ✓ Gaussian Blur › ● Auto Threshold › ○ Convert to Mask …".
 * Done steps carry a check, the running step a filled accent dot and bold label, pending steps a hollow dot, a failed
 * step a cross in the error colour. Its preferred height follows the width it is given (one 20 px row per line).
 * EDT only.
 */
final class StepTrail extends JComponent {
    enum Mark { DONE, CURRENT, PENDING, FAILED }

    record Step(String label, Mark mark) {}

    private static final int ROW = 20;
    private static final int GLYPH = 9;
    private static final int GAP = 5;
    private static final int SEP = 18;
    private final List<Step> steps = new ArrayList<>();
    private int lines = 1;

    StepTrail() {
        setOpaque(false);
    }

    void set(List<Step> s) {
        steps.clear();
        steps.addAll(s);
        relayoutIfNeeded(true);
        repaint();
    }

    List<Step> steps() {
        return List.copyOf(steps);
    }

    private Font font(Mark m) {
        Font base = getFont() != null ? getFont() : UIManager.getFont("Label.font");
        return m == Mark.CURRENT ? base.deriveFont(Font.BOLD) : base;
    }

    private int itemWidth(Step s) {
        FontMetrics fm = getFontMetrics(font(s.mark()));
        return GLYPH + GAP + fm.stringWidth(s.label());
    }

    /** Lines needed at {@code width}. */
    int linesFor(int width) {
        if (steps.isEmpty() || width <= 0) return 1;
        int n = 1;
        int x = 0;
        for (int i = 0; i < steps.size(); i++) { // the same walk as paintComponent
            int w = itemWidth(steps.get(i));
            if (x > 0 && x + w > width) {
                n++;
                x = 0;
            }
            x += w + (i < steps.size() - 1 ? SEP : 0);
        }
        return n;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : 600;
        return new Dimension(Math.min(width, totalWidth()), linesFor(width) * ROW);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private int totalWidth() {
        int t = 0;
        for (int i = 0; i < steps.size(); i++) t += itemWidth(steps.get(i)) + (i < steps.size() - 1 ? SEP : 0);
        return Math.max(1, t);
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        relayoutIfNeeded(false);
    }

    private void relayoutIfNeeded(boolean force) {
        int n = linesFor(getWidth() > 0 ? getWidth() : 600);
        if (n != lines || force) {
            lines = n;
            SwingUtilities.invokeLater(() -> {
                revalidate();
                if (getParent() != null) getParent().revalidate();
            });
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fg = UIManager.getColor("Label.foreground");
        if (fg == null) fg = Color.BLACK;
        int width = getWidth();
        int x = 0;
        int row = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            boolean last = i == steps.size() - 1;
            int w = itemWidth(s);
            if (x > 0 && x + w > width) {
                row++;
                x = 0;
            }
            int cy = row * ROW + ROW / 2;
            paintMark(g, s.mark(), x + GLYPH / 2.0, cy, fg);
            g.setFont(font(s.mark()));
            g.setColor(switch (s.mark()) {
                case DONE -> fg;
                case CURRENT -> fg;
                case PENDING -> Theme.muted();
                case FAILED -> Theme.error();
            });
            FontMetrics fm = g.getFontMetrics();
            int baseline = cy + (fm.getAscent() - fm.getDescent()) / 2;
            javax.swing.plaf.basic.BasicGraphicsUtils.drawString(this, g, s.label(), x + GLYPH + GAP, baseline);
            x += w;
            if (!last) {
                paintChevron(g, x + SEP / 2.0, cy);
                x += SEP;
            }
        }
        g.dispose();
    }

    private static void paintMark(Graphics2D g, Mark m, double cx, double cy, Color fg) {
        double r = GLYPH / 2.0;
        switch (m) {
            case DONE -> {
                g.setColor(Theme.accent());
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Double();
                p.moveTo(cx - r + 0.5, cy + 0.2);
                p.lineTo(cx - 1, cy + r - 1);
                p.lineTo(cx + r, cy - r + 1);
                g.draw(p);
            }
            case CURRENT -> {
                g.setColor(PennyIndicator.alpha(Theme.accent(), 0.3));
                g.fill(new Ellipse2D.Double(cx - r - 1.5, cy - r - 1.5, 2 * r + 3, 2 * r + 3));
                g.setColor(Theme.accent());
                g.fill(new Ellipse2D.Double(cx - r + 1, cy - r + 1, 2 * r - 2, 2 * r - 2));
            }
            case PENDING -> {
                g.setColor(Theme.muted());
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new Ellipse2D.Double(cx - r + 1, cy - r + 1, 2 * r - 2, 2 * r - 2));
            }
            case FAILED -> {
                g.setColor(Theme.error());
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double d = r - 1.5;
                Path2D p = new Path2D.Double();
                p.moveTo(cx - d, cy - d);
                p.lineTo(cx + d, cy + d);
                p.moveTo(cx + d, cy - d);
                p.lineTo(cx - d, cy + d);
                g.draw(p);
            }
        }
    }

    private static void paintChevron(Graphics2D g, double cx, double cy) {
        g.setColor(Theme.muted());
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Double();
        p.moveTo(cx - 1.5, cy - 3.5);
        p.lineTo(cx + 2, cy);
        p.lineTo(cx - 1.5, cy + 3.5);
        g.draw(p);
    }
}
