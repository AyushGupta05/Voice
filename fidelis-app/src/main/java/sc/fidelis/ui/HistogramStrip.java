package sc.fidelis.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.UIManager;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.measure.Measurements;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * A small histogram of one plane (256 bins over the plane's value range) with two vertical markers
 * for a range (threshold bounds or display range). Drawn here; the numbers come from IJ1's own
 * {@link ImageStatistics}. Reads pixels only.
 */
final class HistogramStrip extends JComponent {
    private int[] counts = new int[0];
    private double histMin;
    private double histMax;
    private double markLow = Double.NaN;
    private double markHigh = Double.NaN;
    private Color markColor = new Color(0xd62728);
    private String message = "No image";

    HistogramStrip() {
        setPreferredSize(new Dimension(300, 96));
        setMinimumSize(new Dimension(100, 60));
    }

    /** Statistics of the plane of {@code channel} (0 = the image's current plane) at the current z and t. */
    static ImageStatistics statsOf(ImagePlus imp, int channel) {
        int idx = (imp.isHyperStack() || imp.isComposite()) && channel > 0
                ? imp.getStackIndex(channel, imp.getSlice(), imp.getFrame()) : imp.getCurrentSlice();
        ImageProcessor ip = imp.getStack().getProcessor(Math.max(1, Math.min(imp.getStackSize(), idx)));
        ip.setHistogramSize(256);
        return ImageStatistics.getStatistics(ip, Measurements.MIN_MAX | Measurements.AREA, imp.getCalibration());
    }

    void setData(ImageStatistics st) {
        if (st == null || st.histogram == null) {
            counts = new int[0];
            message = "No image";
        } else {
            counts = st.histogram.clone();
            histMin = st.histMin;
            histMax = st.histMax;
            if (st.histMax <= st.histMin) {
                histMin = st.min;
                histMax = st.max <= st.min ? st.min + 1 : st.max;
            }
            message = "";
        }
        repaint();
    }

    void setMessage(String m) {
        counts = new int[0];
        message = m;
        repaint();
    }

    void setMarks(double low, double high, Color color) {
        markLow = low;
        markHigh = high;
        if (color != null) markColor = color;
        repaint();
    }

    double histMin() {
        return histMin;
    }

    double histMax() {
        return histMax;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color bg = UIManager.getColor("TextField.background");
        Color fg = UIManager.getColor("Label.foreground");
        int w = getWidth();
        int h = getHeight();
        g.setColor(bg != null ? bg : Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Theme.panelBorder());
        g.drawRect(0, 0, w - 1, h - 1);
        FontMetrics fm = g.getFontMetrics();
        int labelH = fm.getHeight() + 2;
        int plotH = h - labelH - 4;
        int plotW = w - 8;
        if (counts.length == 0) {
            g.setColor(Theme.muted());
            g.drawString(message, 8, h / 2);
            g.dispose();
            return;
        }
        double max = 0;
        for (int c : counts) max = Math.max(max, Math.log1p(c));
        if (max <= 0) max = 1;
        g.setColor(fg != null ? new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150) : Color.GRAY);
        for (int i = 0; i < counts.length; i++) {
            int x0 = 4 + (int) Math.round(plotW * i / (double) counts.length);
            int x1 = 4 + (int) Math.round(plotW * (i + 1) / (double) counts.length);
            int bh = (int) Math.round(plotH * Math.log1p(counts[i]) / max);
            g.fillRect(x0, 2 + plotH - bh, Math.max(1, x1 - x0), bh);
        }
        if (!Double.isNaN(markLow) && !Double.isNaN(markHigh) && histMax > histMin) {
            int xl = xOf(markLow, plotW);
            int xh = xOf(markHigh, plotW);
            Color band = new Color(markColor.getRed(), markColor.getGreen(), markColor.getBlue(), 45);
            g.setColor(band);
            g.fillRect(Math.min(xl, xh), 2, Math.abs(xh - xl) + 1, plotH);
            g.setColor(markColor);
            g.drawLine(xl, 2, xl, 2 + plotH);
            g.drawLine(xh, 2, xh, 2 + plotH);
        }
        g.setColor(Theme.muted());
        String lo = fmt(histMin);
        String hi = fmt(histMax);
        int ty = h - 4;
        g.drawString(lo, 4, ty);
        g.drawString(hi, w - 4 - fm.stringWidth(hi), ty);
        g.dispose();
    }

    private int xOf(double v, int plotW) {
        double f = (v - histMin) / (histMax - histMin);
        f = Math.max(0, Math.min(1, f));
        return 4 + (int) Math.round(f * plotW);
    }

    static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? Long.toString((long) v) : String.format(Locale.ROOT, "%.4g", v);
    }

    /** The display range of {@code channel} (0 = current) as IJ1 holds it. */
    static double[] displayRange(ImagePlus imp, int channel) {
        if (imp.isComposite() && channel > 0) {
            ImageProcessor ip = ((CompositeImage) imp).getProcessor(channel);
            return new double[] {ip.getMin(), ip.getMax()};
        }
        return new double[] {imp.getDisplayRangeMin(), imp.getDisplayRangeMax()};
    }
}
