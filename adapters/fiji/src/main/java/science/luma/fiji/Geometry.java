package science.luma.fiji;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure image-pixel to global-screen mapping for one ImageJ canvas; no AWT calls, so it is unit-testable without a display. */
final class Geometry {
    private Geometry() {}
    /** Threshold under which a target is "invisible": both sides under 12% of the canvas. */
    static final double TINY_FRACTION = 0.12;
    static final double MIN_TARGET_PX = 8;

    /** A canvas as seen on screen: its top-left in global screen points, its pixel size, the displayed image rectangle and the magnification. */
    record View(double screenX, double screenY, int canvasWidth, int canvasHeight, Rectangle srcRect, double magnification) {
        double screenOfX(double imageX) { return screenX + (imageX - srcRect.x) * magnification; }
        double screenOfY(double imageY) { return screenY + (imageY - srcRect.y) * magnification; }
        Map<String, Object> json() {
            var result = new LinkedHashMap<String, Object>();
            result.put("magnification", magnification);
            result.put("srcRect", Map.of("x", srcRect.x, "y", srcRect.y, "width", srcRect.width, "height", srcRect.height));
            result.put("canvasScreen", Map.of("x", screenX, "y", screenY, "width", canvasWidth, "height", canvasHeight));
            return result;
        }
    }

    /** Screen rectangle of an image-pixel rectangle, clipped to the visible canvas; null when fully off screen. Minimum 8 px so tiny particles stay visible. */
    static Map<String, Object> screenRect(View view, double x0, double y0, double x1, double y1) {
        double left = view.screenOfX(Math.min(x0, x1)), top = view.screenOfY(Math.min(y0, y1));
        double right = view.screenOfX(Math.max(x0, x1)), bottom = view.screenOfY(Math.max(y0, y1));
        double minX = view.screenX, minY = view.screenY, maxX = view.screenX + view.canvasWidth, maxY = view.screenY + view.canvasHeight;
        if (right < minX || bottom < minY || left > maxX || top > maxY) return null;
        left = Math.max(left, minX); top = Math.max(top, minY); right = Math.min(right, maxX); bottom = Math.min(bottom, maxY);
        double cx = (left + right) / 2, cy = (top + bottom) / 2, w = Math.max(MIN_TARGET_PX, right - left), h = Math.max(MIN_TARGET_PX, bottom - top);
        var rect = new LinkedHashMap<String, Object>();
        rect.put("x", cx - w / 2); rect.put("y", cy - h / 2); rect.put("width", w); rect.put("height", h);
        return rect;
    }

    /** True when an image-pixel rectangle would be too small to notice at the current zoom or lies outside the displayed region. */
    static boolean tinyOrOffscreen(View view, double x, double y, double w, double h) {
        boolean tiny = w * view.magnification < view.canvasWidth * TINY_FRACTION && h * view.magnification < view.canvasHeight * TINY_FRACTION;
        var src = view.srcRect;
        boolean offscreen = x + w < src.x || x > src.x + src.width || y + h < src.y || y > src.y + src.height;
        return tiny || offscreen;
    }

    /** {x, y, width, height} union of rectangles, or null when empty. */
    static Rectangle union(List<Rectangle> rectangles) {
        Rectangle result = null;
        for (var rectangle : rectangles) { if (rectangle == null) continue; result = result == null ? new Rectangle(rectangle) : result.union(rectangle); }
        return result;
    }

    /** Companion highlight payload: screen targets, a pointer at their centre and the canvas bounds. */
    static Map<String, Object> payload(String label, int total, List<Map<String, Object>> targets, View view) {
        Map<String, Object> pointer = Map.of();
        if (!targets.isEmpty()) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (var t : targets) { double tx = (double) t.get("x"), ty = (double) t.get("y"); minX = Math.min(minX, tx); minY = Math.min(minY, ty); maxX = Math.max(maxX, tx + (double) t.get("width")); maxY = Math.max(maxY, ty + (double) t.get("height")); }
            pointer = Map.of("screenX", (minX + maxX) / 2, "screenY", (minY + maxY) / 2);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("label", label.length() > 80 ? label.substring(0, 80) : label); result.put("total", total); result.put("shown", targets.size());
        result.put("truncated", targets.size() < total); result.put("kind", "image"); result.put("targets", targets); result.put("pointer", pointer);
        result.put("viewer", Map.of("x", view.screenX, "y", view.screenY, "width", view.canvasWidth, "height", view.canvasHeight));
        result.put("coordinateSystem", "screen-top-left");
        result.put("note", targets.size() < total ? "Only targets inside the visible canvas are outlined; zoom or highlight again to see others." : "");
        return result;
    }

    /** Targets for image-pixel rectangles (shape "ellipse" for oval ROIs), bounded by limit. */
    static List<Map<String, Object>> targets(View view, List<Rectangle> bounds, List<Boolean> ellipses, List<String> ids, int limit) {
        var targets = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < bounds.size() && targets.size() < limit; i++) {
            var b = bounds.get(i); if (b == null) continue;
            var rect = screenRect(view, b.x, b.y, b.x + b.width, b.y + b.height);
            if (rect == null) continue;
            rect.put("shape", ellipses != null && i < ellipses.size() && ellipses.get(i) ? "ellipse" : "rect");
            if (ids != null && i < ids.size() && ids.get(i) != null) rect.put("id", ids.get(i));
            targets.add(rect);
        }
        return targets;
    }
}
