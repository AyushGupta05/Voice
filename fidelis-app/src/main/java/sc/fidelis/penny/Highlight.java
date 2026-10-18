package sc.fidelis.penny;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.RoiGeometry;

/**
 * Penny's screen-geometry highlight payload ({@code docs/FIDELIS-PROTOCOL.md}): the bridge asks the workspace
 * ({@link PennyUi}) to flash outlines inside the hosted canvas and to map image pixels to global screen points; it never
 * draws itself. Without a workspace (tests) the no-op {@link PennyUi#NONE} answers null and the targets are empty.
 */
final class Highlight {
    private Highlight() {}

    static final double MIN_TARGET = 8;

    /** Image-pixel bounds of a ROI: its rectangle/oval bounds, else the box around its vertices; null when it has none. */
    /** Outline area in pixels: the polygon's (shoelace) when it has three or more vertices, an oval's ellipse, else its bounds. */
    static double areaPixels(RoiGeometry g) {
        if (g == null) return 0;
        List<Float> xs = g.xs(), ys = g.ys();
        if (!"OVAL".equals(g.type()) && !"RECTANGLE".equals(g.type()) && xs != null && ys != null && xs.size() >= 3 && ys.size() >= xs.size()) {
            double twice = 0;
            for (int i = 0, n = xs.size(); i < n; i++) {
                int j = (i + 1) % n;
                twice += (double) xs.get(i) * ys.get(j) - (double) xs.get(j) * ys.get(i);
            }
            return Math.abs(twice) / 2;
        }
        Rectangle2D b = bounds(g);
        if (b == null) return 0;
        return "OVAL".equals(g.type()) ? Math.PI / 4 * b.getWidth() * b.getHeight() : b.getWidth() * b.getHeight();
    }

    static Rectangle2D bounds(RoiGeometry g) {
        if (g == null) return null;
        double[] b = g.bounds();
        if (b != null && b.length == 4) return new Rectangle2D.Double(b[0], b[1], b[2], b[3]);
        if (g.xs() == null || g.xs().isEmpty()) return null;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < g.xs().size() && i < g.ys().size(); i++) {
            x0 = Math.min(x0, g.xs().get(i));
            x1 = Math.max(x1, g.xs().get(i));
            y0 = Math.min(y0, g.ys().get(i));
            y1 = Math.max(y1, g.ys().get(i));
        }
        return new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
    }

    static Map<String, Object> rect(Rectangle2D r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", r.getX());
        m.put("y", r.getY());
        m.put("width", r.getWidth());
        m.put("height", r.getHeight());
        return m;
    }

    static Map<String, Object> screen(Rectangle r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", r.x);
        m.put("y", r.y);
        m.put("width", r.width);
        m.put("height", r.height);
        return m;
    }

    /** Flash the ROIs on the canvas and return the payload (targets bounded by {@code limit}). */
    static Map<String, Object> rois(DocumentId doc, List<RoiGeometry> rois, String label, int limit, boolean flash) {
        List<Rectangle2D> rects = new ArrayList<>();
        boolean ellipses = !rois.isEmpty();
        for (RoiGeometry g : rois) {
            Rectangle2D b = bounds(g);
            if (b == null) continue;
            rects.add(b);
            ellipses &= "OVAL".equals(g.type());
        }
        return rects(doc, rects, ellipses, label, limit, flash);
    }

    static Map<String, Object> rects(DocumentId doc, List<Rectangle2D> rects, boolean ellipses, String label, int limit, boolean flash) {
        PennyUi ui = PennyUi.current();
        if (flash && doc != null && !rects.isEmpty()) {
            try {
                ui.flash(doc, List.copyOf(rects.subList(0, Math.min(rects.size(), Math.max(limit, 1)))), ellipses, label);
            } catch (RuntimeException e) {
                System.err.println("[fidelis-penny] flash failed: " + e);
            }
        }
        Rectangle canvas = doc == null ? null : safe(() -> ui.canvasOnScreen(doc));
        List<Map<String, Object>> targets = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Rectangle2D r : rects) {
            if (targets.size() >= limit) break;
            Rectangle s = doc == null ? null : safe(() -> ui.toScreen(doc, r));
            if (s == null) continue;
            double w = Math.max(MIN_TARGET, s.width), h = Math.max(MIN_TARGET, s.height);
            double cx = s.x + s.width / 2.0, cy = s.y + s.height / 2.0;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("x", cx - w / 2);
            t.put("y", cy - h / 2);
            t.put("width", w);
            t.put("height", h);
            t.put("shape", ellipses ? "ellipse" : "rect");
            targets.add(t);
            minX = Math.min(minX, cx - w / 2);
            minY = Math.min(minY, cy - h / 2);
            maxX = Math.max(maxX, cx + w / 2);
            maxY = Math.max(maxY, cy + h / 2);
        }
        Map<String, Object> pointer = new LinkedHashMap<>();
        if (!targets.isEmpty()) {
            pointer.put("screenX", (minX + maxX) / 2);
            pointer.put("screenY", (minY + maxY) / 2);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label.length() > 80 ? label.substring(0, 80) : label);
        out.put("total", rects.size());
        out.put("shown", targets.size());
        out.put("truncated", targets.size() < rects.size());
        out.put("kind", "image");
        out.put("targets", targets);
        out.put("pointer", pointer);
        out.put("viewer", screen(canvas));
        out.put("coordinateSystem", "screen-top-left");
        out.put("flash", flash);
        if (canvas == null) out.put("note", "the document's canvas is not on screen (or no workspace is attached); no screen targets");
        return out;
    }

    /** The whole visible canvas as the target (opened or changed image). */
    static Map<String, Object> canvas(DocumentId doc, String label) {
        PennyUi ui = PennyUi.current();
        Rectangle canvas = doc == null ? null : safe(() -> ui.canvasOnScreen(doc));
        List<Map<String, Object>> targets = new ArrayList<>();
        Map<String, Object> pointer = new LinkedHashMap<>();
        if (canvas != null) {
            Map<String, Object> t = screen(canvas);
            t.put("shape", "rect");
            targets.add(t);
            pointer.put("screenX", canvas.x + canvas.width / 2.0);
            pointer.put("screenY", canvas.y + canvas.height / 2.0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label.length() > 80 ? label.substring(0, 80) : label);
        out.put("total", 1);
        out.put("shown", targets.size());
        out.put("truncated", false);
        out.put("kind", "image");
        out.put("targets", targets);
        out.put("pointer", pointer);
        out.put("viewer", screen(canvas));
        out.put("coordinateSystem", "screen-top-left");
        out.put("flash", false);
        return out;
    }

    private interface Call<T> {
        T get();
    }

    private static <T> T safe(Call<T> c) {
        try {
            return c.get();
        } catch (RuntimeException e) {
            System.err.println("[fidelis-penny] workspace geometry failed: " + e);
            return null;
        }
    }
}
