package sc.fidelis.penny.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import javax.swing.JComponent;

/**
 * Penny's size chart (ported from the stand-alone Fiji adapter's {@code Analysis.SizeChart}): a histogram with rounded
 * bars for 12+ objects, a dot plot below that, the median marked; vector-drawn in Penny's light palette (white surface,
 * #1D2125 text, #5E646C labels, hairline grid, deep teal accent #0B7D66). Shown by {@link WorkspacePennyUi#showChart}.
 */
final class SizeChart extends JComponent {
    static final Color BACKGROUND = Color.WHITE, ACCENT = new Color(11, 125, 102), ACCENT_FADE = new Color(11, 125, 102, 115),
            ACCENT_HALO = new Color(11, 125, 102, 46), GRID = new Color(231, 233, 236), AXIS = new Color(196, 201, 207),
            TEXT = new Color(29, 33, 37), MUTED = new Color(94, 100, 108), MEDIAN = new Color(55, 62, 71);
    private double[] values = new double[0];
    private String unit = "px²";

    SizeChart() {
        setPreferredSize(new Dimension(420, 250));
        setOpaque(true);
    }

    void set(double[] values, String unit) {
        this.values = values.clone();
        Arrays.sort(this.values);
        this.unit = unit;
    }

    /** The values the chart shows, sorted (for tests). */
    double[] values() {
        return values.clone();
    }

    static double niceStep(double range, int target) {
        double raw = range / Math.max(1, target), power = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1e-12)))), f = raw / power;
        return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * power;
    }

    static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return String.format(Locale.ROOT, Math.abs(v) >= 1000 ? "%,.0f" : "%.0f", v);
        return Math.abs(v) >= 1000 ? String.format(Locale.ROOT, "%,.0f", v) : Math.abs(v) >= 10 ? String.format(Locale.ROOT, "%.0f", v)
                : String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);
        int n = values.length;
        if (n == 0) {
            g.dispose();
            return;
        }
        double median = n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2, min = values[0], max = values[n - 1];
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(TEXT);
        String head = n + (n == 1 ? " object" : " objects");
        g.drawString(head, 18, 28);
        int titleWidth = g.getFontMetrics().stringWidth(head);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(MUTED);
        g.drawString("  ·  median " + format(median) + " " + unit + "  ·  " + format(min) + "–" + format(max), 18 + titleWidth, 28);
        int left = 46, right = w - 18, top = 48, bottom = h - 38;
        double step = niceStep(Math.max(1e-9, max - min), 5), lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
        if (hi <= lo) hi = lo + step;
        final double fLo = lo, fHi = hi;
        DoubleUnaryOperator x = v -> left + (v - fLo) / (fHi - fLo) * (right - left);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (double t = lo; t <= hi + step / 2; t += step) {
            int px = (int) Math.round(x.applyAsDouble(t));
            g.setColor(GRID);
            g.drawLine(px, top, px, bottom);
            g.setColor(MUTED);
            String label = format(t);
            g.drawString(label, px - g.getFontMetrics().stringWidth(label) / 2, bottom + 16);
        }
        g.setColor(MUTED);
        String axis = "area (" + unit + ")";
        g.drawString(axis, right - g.getFontMetrics().stringWidth(axis), h - 8);
        g.setColor(AXIS);
        g.drawLine(left, bottom, right, bottom);
        if (n >= 12) {
            int bins = Math.max(6, Math.min(24, (int) Math.round(Math.sqrt(n) * 1.5)));
            int[] counts = new int[bins];
            double width = (hi - lo) / bins;
            for (double v : values) counts[(int) Math.min(bins - 1, Math.max(0, (v - lo) / width))]++;
            int peak = Arrays.stream(counts).max().orElse(1);
            double barWidth = (right - left) / (double) bins;
            for (int i = 0; i < bins; i++) {
                if (counts[i] == 0) continue;
                double barHeight = (bottom - top) * counts[i] / (double) peak;
                RoundRectangle2D bar = new RoundRectangle2D.Double(left + i * barWidth + 1.5, bottom - barHeight, barWidth - 3, barHeight + 4, 6, 6);
                g.setPaint(new GradientPaint(0, (float) (bottom - barHeight), ACCENT, 0, bottom, ACCENT_FADE));
                Shape clip = g.getClip();
                g.clipRect(left, top - 2, right - left, bottom - top + 2);
                g.fill(bar);
                g.setClip(clip);
            }
            g.setColor(MUTED);
            g.drawString(String.valueOf(peak), 18, top + 10);
            g.drawString("0", 30, bottom);
        } else {
            // few objects: every object is a dot (a histogram of four values says nothing)
            int lane = (top + bottom) / 2;
            for (int i = 0; i < n; i++) {
                double px = x.applyAsDouble(values[i]);
                double py = lane + ((i % 3) - 1) * 14;
                g.setColor(ACCENT_HALO);
                g.fill(new Ellipse2D.Double(px - 11, py - 11, 22, 22));
                g.setColor(ACCENT);
                g.fill(new Ellipse2D.Double(px - 6, py - 6, 12, 12));
            }
        }
        int mx = (int) Math.round(x.applyAsDouble(median));
        g.setColor(MEDIAN);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {4, 4}, 0));
        g.drawLine(mx, top - 4, mx, bottom);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString("median", Math.min(right - 40, mx + 5), top + 6);
        g.dispose();
    }
}
