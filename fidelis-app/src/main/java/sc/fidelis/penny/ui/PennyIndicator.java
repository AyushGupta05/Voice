package sc.fidelis.penny.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.Timer;

import sc.fidelis.ui.Theme;

/**
 * Penny's 16 px state mark in the strip: a hollow dot when offline, a quiet dot when idle, a spinner while starting,
 * level-reactive bars while listening, a slow pulse while thinking and a ripple while speaking. The animation timer
 * runs only while an animated state is showing. EDT only.
 */
public final class PennyIndicator extends JComponent {
    public enum Mode { OFFLINE, IDLE, STARTING, LISTENING, THINKING, SPEAKING }

    private static final int SIZE = 16;
    private Mode mode = Mode.OFFLINE;
    private double level;
    private double shown;
    private final double[] bars = new double[4];
    private final long t0 = System.nanoTime();
    /** Frozen animation time for screenshots (negative: live). */
    private double frozenSeconds = -1;
    private final Timer timer = new Timer(33, e -> tick());

    public PennyIndicator() {
        setPreferredSize(new Dimension(SIZE, SIZE));
        setMinimumSize(new Dimension(SIZE, SIZE));
        setOpaque(false);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) updateTimer();
        });
    }

    /** Whether the animation timer is running (test hook: it must stop when nothing animates or nothing shows). */
    boolean animating() {
        return timer.isRunning();
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode m) {
        if (m == mode) return;
        mode = m;
        updateTimer();
        repaint();
    }

    /** Microphone RMS after gain (0..1). */
    public void setLevel(double rms) {
        level = displayLevel(rms);
    }

    /** dBFS mapped to 0..1: about -54 dB (silence) is 0 and -12 dB (loud speech) is 1. */
    static double displayLevel(double rms) {
        if (rms <= 0) return 0;
        double db = 20 * Math.log10(rms);
        return Math.max(0, Math.min(1, (db + 54) / 42));
    }

    /** Screenshots: draw the animation at a fixed time and level. */
    public void freeze(double seconds, double displayLevel) {
        frozenSeconds = seconds;
        level = displayLevel;
        shown = displayLevel;
        for (int i = 0; i < bars.length; i++) bars[i] = displayLevel * (0.55 + 0.45 * Math.abs(Math.sin(1.7 * i + 0.9)));
        updateTimer();
        repaint();
    }

    private boolean animated() {
        return mode == Mode.STARTING || mode == Mode.LISTENING || mode == Mode.THINKING || mode == Mode.SPEAKING;
    }

    private void updateTimer() {
        boolean run = animated() && isShowing() && frozenSeconds < 0;
        if (run && !timer.isRunning()) timer.start();
        else if (!run && timer.isRunning()) timer.stop();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        updateTimer();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    private void tick() {
        if (!isShowing()) {
            timer.stop();
            return;
        }
        // attack fast, release slowly, like a VU meter
        shown += (level - shown) * (level > shown ? 0.6 : 0.18);
        double t = seconds();
        for (int i = 0; i < bars.length; i++) {
            double wobble = 0.55 + 0.45 * Math.abs(Math.sin(t * (5.1 + 1.3 * i) + i * 1.1));
            double target = shown * wobble;
            bars[i] += (target - bars[i]) * 0.5;
        }
        repaint();
    }

    private double seconds() {
        return frozenSeconds >= 0 ? frozenSeconds : (System.nanoTime() - t0) / 1e9;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double cx = getWidth() / 2.0;
        double cy = getHeight() / 2.0;
        Color accent = Theme.accent();
        Color muted = Theme.muted();
        double t = seconds();
        switch (mode) {
            case OFFLINE -> {
                g.setColor(muted);
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new Ellipse2D.Double(cx - 3.5, cy - 3.5, 7, 7));
            }
            case IDLE -> {
                g.setColor(alpha(accent, 0.75));
                g.fill(new Ellipse2D.Double(cx - 3.5, cy - 3.5, 7, 7));
            }
            case STARTING -> {
                g.setColor(alpha(muted, 0.5));
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new Ellipse2D.Double(cx - 6, cy - 6, 12, 12));
                g.setColor(accent);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int start = (int) (-t * 360 * 1.1) % 360;
                g.drawArc((int) Math.round(cx - 6), (int) Math.round(cy - 6), 12, 12, start, 100);
            }
            case LISTENING -> {
                double w = 2.2;
                double gap = 1.6;
                double total = bars.length * w + (bars.length - 1) * gap;
                double x = cx - total / 2;
                g.setColor(accent);
                for (double b : bars) {
                    double h = 3 + b * 10;
                    g.fill(new RoundRectangle2D.Double(x, cy - h / 2, w, h, w, w));
                    x += w + gap;
                }
            }
            case THINKING -> {
                double p = 0.5 + 0.5 * Math.sin(t * 2 * Math.PI / 1.4);
                double r = 3 + 1.6 * p;
                g.setColor(alpha(accent, 0.18 + 0.2 * p));
                g.fill(new Ellipse2D.Double(cx - r - 2.5, cy - r - 2.5, 2 * r + 5, 2 * r + 5));
                g.setColor(alpha(accent, 0.65 + 0.35 * p));
                g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            }
            case SPEAKING -> {
                g.setStroke(new BasicStroke(1.4f));
                for (int k = 0; k < 2; k++) {
                    double ph = ((t / 1.3) + k * 0.5) % 1.0;
                    double r = 2.5 + ph * 5.5;
                    g.setColor(alpha(accent, (1 - ph) * 0.9));
                    g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                }
                g.setColor(accent);
                g.fill(new Ellipse2D.Double(cx - 2.6, cy - 2.6, 5.2, 5.2));
            }
        }
        g.dispose();
    }

    static Color alpha(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(Math.max(0, Math.min(1, a)) * 255));
    }
}
