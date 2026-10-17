package sc.fidelis.penny.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import ij.gui.ImageCanvas;

/**
 * Penny pointing at something on the image: outlines that pop in over the hosted IJ1 canvas, hold, and fade after about
 * three seconds, with a small label chip. Drawn in the look-and-feel's accent colour over a light halo, so it reads on
 * bright and dark image regions alike, in the light (default) and the dark theme.
 *
 * <p>The IJ1 canvas is a heavyweight AWT component, so a Swing glass pane or JLayer cannot paint over it (it would be
 * painted under the canvas). The flash therefore paints inside the canvas itself: on every animation frame it repaints the
 * canvas exactly as IJ1 does ({@code ImageCanvas.paint}) and then the outlines on the same graphics, so the outlines can
 * never appear over other windows. Nothing is added to the {@code ImagePlus} (no overlay, no ROI): pixels, overlay and
 * ROI state are untouched, and the canvas is repainted clean when the flash ends. Geometry follows the canvas's current
 * source rectangle and magnification every frame (zoom and pan during a flash stay aligned). EDT only.
 */
public final class CanvasFlash {
    /** The highlight colour: the look-and-feel accent (read every frame, so a theme switch follows). */
    static Color colour() {
        return sc.fidelis.ui.Theme.accent();
    }
    static final long APPEAR_MS = 320;
    static final long STAGGER_MS = 25;
    static final int STAGGERED = 30;
    static final long FADE_START_MS = 3_000;
    static final long FADE_MS = 800;
    /** Above this many targets the outlines are drawn thin, without fill or glow. */
    static final int CROWDED = 12;

    private static final Map<ImageCanvas, CanvasFlash> ACTIVE = new WeakHashMap<>();

    private final ImageCanvas canvas;
    private final List<Rectangle2D> rects;
    private final boolean ellipses;
    private final String label;
    private final long start = System.nanoTime();
    private final Timer timer;
    private long heldAt = -1;

    private CanvasFlash(ImageCanvas canvas, List<Rectangle2D> rects, boolean ellipses, String label) {
        this.canvas = canvas;
        this.rects = List.copyOf(rects);
        this.ellipses = ellipses;
        this.label = label == null || label.isBlank() ? null : label.strip();
        this.timer = new Timer(33, e -> tick());
    }

    /** Start a flash on {@code canvas} (image-pixel rectangles), replacing the one already showing there. EDT only. */
    public static CanvasFlash show(ImageCanvas canvas, List<Rectangle2D> imageRects, boolean ellipses, String label) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("CanvasFlash.show off the EDT");
        CanvasFlash old = ACTIVE.remove(canvas);
        if (old != null) old.timer.stop();
        CanvasFlash f = new CanvasFlash(canvas, imageRects, ellipses, label);
        ACTIVE.put(canvas, f);
        f.timer.start();
        f.paintNow();
        return f;
    }

    /** The flash showing on {@code canvas}, or null. */
    public static CanvasFlash on(ImageCanvas canvas) {
        return ACTIVE.get(canvas);
    }

    /** Paint the current frame of the canvas's flash over {@code g} (the workspace screenshot composites canvases). */
    public static void paintOver(ImageCanvas canvas, Graphics2D g) {
        CanvasFlash f = ACTIVE.get(canvas);
        if (f != null) f.paintFrame(g, f.elapsed());
    }

    /** Freeze the animation at {@code millis} (screenshots); the flash stays until {@link #release}. */
    public void hold(long millis) {
        heldAt = millis;
        paintNow();
    }

    public void release() {
        heldAt = -1;
    }

    /** Stop now and repaint the canvas clean. */
    public void stop() {
        timer.stop();
        if (ACTIVE.get(canvas) == this) ACTIVE.remove(canvas);
        canvas.repaint();
    }

    public boolean running() {
        return timer.isRunning();
    }

    public String label() {
        return label;
    }

    public int targets() {
        return rects.size();
    }

    long elapsed() {
        return heldAt >= 0 ? heldAt : (System.nanoTime() - start) / 1_000_000L;
    }

    long duration() {
        return FADE_START_MS + Math.min(rects.size(), STAGGERED) * STAGGER_MS + FADE_MS;
    }

    private void tick() {
        if (heldAt < 0 && elapsed() > duration()) {
            stop();
            return;
        }
        paintNow();
    }

    private void paintNow() {
        if (!canvas.isShowing() || canvas.getWidth() <= 0) return;
        Graphics g = canvas.getGraphics();
        if (g == null) return;
        try {
            canvas.paint(g); // IJ1's own painting (image, overlay, ROI), then the outlines on top
            paintFrame((Graphics2D) g, elapsed());
        } catch (RuntimeException e) {
            stop();
        } finally {
            g.dispose();
        }
    }

    /** One frame at {@code t} ms; canvas coordinates. */
    void paintFrame(Graphics2D g0, long t) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double mag = canvas.getMagnification();
            Rectangle src = canvas.getSrcRect();
            int cw = canvas.getWidth();
            int ch = canvas.getHeight();
            Rectangle2D union = null;
            double pulse = 0.5 + 0.5 * Math.sin(t / 1000.0 * Math.PI / 0.9);
            boolean crowded = rects.size() > CROWDED; // many objects: thin outlines only, so the image stays readable
            Color accent = colour();
            for (int i = 0; i < rects.size(); i++) {
                long delay = Math.min(i, STAGGERED) * STAGGER_MS;
                double lt = t - delay;
                if (lt <= 0) continue;
                double appear = clamp(lt / APPEAR_MS);
                double fade = t < FADE_START_MS + delay ? 1 : 1 - clamp((t - FADE_START_MS - delay) / (double) FADE_MS);
                double a = Math.min(1, appear * 1.6) * fade;
                if (a <= 0.01) continue;
                Rectangle2D r = rects.get(i);
                double pad = crowded ? 1.5 : 3;
                double x = (r.getX() - src.x) * mag - pad;
                double y = (r.getY() - src.y) * mag - pad;
                double w = r.getWidth() * mag + 2 * pad;
                double h = r.getHeight() * mag + 2 * pad;
                if (x > cw || y > ch || x + w < 0 || y + h < 0) continue;
                Rectangle2D base = new Rectangle2D.Double(x, y, w, h);
                union = union == null ? base : union.createUnion(base);
                double s = 1.35 - 0.35 * easeOutBack(appear);
                double sx = x + w / 2 - w * s / 2;
                double sy = y + h / 2 - h * s / 2;
                Shape shape = ellipses ? new Ellipse2D.Double(sx, sy, w * s, h * s)
                        : new RoundRectangle2D.Double(sx, sy, w * s, h * s, 7, 7);
                if (!crowded) {
                    // a few targets: a soft fill and a pulsing glow, like the companion's spotlight
                    g.setColor(PennyIndicator.alpha(accent, 0.12 * a));
                    g.fill(shape);
                    g.setStroke(new BasicStroke(6f));
                    g.setColor(PennyIndicator.alpha(accent, (0.14 + 0.12 * pulse) * a));
                    g.draw(shape);
                }
                g.setStroke(new BasicStroke(crowded ? 3.0f : 3.8f));
                g.setColor(new Color(255, 255, 255, (int) Math.round((crowded ? 150 : 190) * a)));
                g.draw(shape);
                g.setStroke(new BasicStroke(crowded ? 1.5f : 2.0f));
                g.setColor(PennyIndicator.alpha(accent, a));
                g.draw(shape);
            }
            if (label != null && union != null) paintChip(g, union, t, cw, ch);
        } finally {
            g.dispose();
        }
    }

    private void paintChip(Graphics2D g, Rectangle2D union, long t, int cw, int ch) {
        double appear = clamp(t / (double) APPEAR_MS);
        double fade = t < FADE_START_MS ? 1 : 1 - clamp((t - FADE_START_MS) / (double) FADE_MS);
        double a = appear * fade;
        if (a <= 0.01) return;
        Font f = canvas.getFont() != null ? canvas.getFont() : new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        f = f.deriveFont(Font.BOLD, 11f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label);
        double w = tw + 14;
        double h = 18;
        double x = Math.max(4, Math.min(cw - w - 4, union.getX()));
        double y = union.getY() - h - 4;
        if (y < 4) y = Math.min(ch - h - 4, union.getMaxY() + 4);
        if (y < 4) y = 4;
        y += (1 - easeOutBack(appear)) * 6;
        RoundRectangle2D chip = new RoundRectangle2D.Double(x, y, w, h, h, h);
        g.setColor(new Color(0, 0, 0, (int) Math.round(60 * a)));
        g.fill(new RoundRectangle2D.Double(x, y + 1.5, w, h, h, h));
        g.setColor(PennyIndicator.alpha(colour(), 0.96 * a));
        g.fill(chip);
        g.setColor(new Color(255, 255, 255, (int) Math.round(140 * a)));
        g.setStroke(new BasicStroke(1f));
        g.draw(chip);
        g.setColor(new Color(255, 255, 255, (int) Math.round(250 * a)));
        g.drawString(label, (float) (x + 7), (float) (y + (h + fm.getAscent() - fm.getDescent()) / 2));
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** A small overshoot, like the companion's spring. */
    static double easeOutBack(double x) {
        double c1 = 1.4;
        double c3 = c1 + 1;
        double u = x - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
