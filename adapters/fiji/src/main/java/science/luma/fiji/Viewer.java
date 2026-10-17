package science.luma.fiji;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.WindowManager;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** The ImageJ canvas as the researcher sees it: screen geometry for the companion cursor and "the view follows the work" zooming. EDT only. */
final class Viewer {
    private Viewer() {}

    static final long POINTER_MAX_AGE_MS = 30_000;
    private record PointerTrace(int imageId, int plane, int x, int y, long at) {}
    private static final Map<ImageCanvas, PointerTrace> pointers = new WeakHashMap<>();

    /** Listen to real canvas motion; ImageCanvas.getCursorLoc() defaults to (0,0) even before the first hover. */
    static void trackPointer(ImagePlus imp, ImageCanvas canvas) {
        if (pointers.containsKey(canvas)) return;
        pointers.put(canvas, null);
        var owner = new java.lang.ref.WeakReference<>(imp);
        var listener = new java.awt.event.MouseAdapter() {
            private void remember(java.awt.event.MouseEvent event) {
                var image = owner.get();
                if (image != null && event.getSource() instanceof ImageCanvas source) rememberPointer(image, source, event.getX(), event.getY(), System.currentTimeMillis());
            }
            @Override public void mouseMoved(java.awt.event.MouseEvent event) { remember(event); }
            @Override public void mouseDragged(java.awt.event.MouseEvent event) { remember(event); }
            @Override public void mouseEntered(java.awt.event.MouseEvent event) { remember(event); }
        };
        canvas.addMouseMotionListener(listener); canvas.addMouseListener(listener);
    }
    static void rememberPointer(ImagePlus imp, ImageCanvas canvas, int sx, int sy, long at) {
        int x = canvas.offScreenX(sx), y = canvas.offScreenY(sy);
        if (sx >= 0 && sy >= 0 && sx < canvas.getWidth() && sy < canvas.getHeight() && x >= 0 && y >= 0 && x < imp.getWidth() && y < imp.getHeight()) pointers.put(canvas, new PointerTrace(imp.getID(), (imp.getCurrentSlice() - 1) / Math.max(1, imp.getNChannels()), x, y, at));
    }
    static Map<String, Object> lastPointer(ImagePlus imp, ImageCanvas canvas, long now) {
        PointerTrace last = pointers.get(canvas);
        if (last == null || last.imageId() != imp.getID() || last.plane() != (imp.getCurrentSlice() - 1) / Math.max(1, imp.getNChannels()) || now < last.at() || now - last.at() > POINTER_MAX_AGE_MS) return Map.of();
        return Map.of("lastX", last.x(), "lastY", last.y(), "lastObservedAt", java.time.Instant.ofEpochMilli(last.at()).toString(), "lastAgeMs", now - last.at());
    }

    /** Global-screen view of the image's canvas, or null when the image has no visible window. */
    static Geometry.View view(ImagePlus imp) {
        if (imp == null) return null;
        ImageCanvas canvas = imp.getCanvas();
        if (canvas == null || !canvas.isShowing()) return null;
        try {
            Point origin = canvas.getLocationOnScreen(); Dimension size = canvas.getSize();
            Rectangle src = canvas.getSrcRect();
            return new Geometry.View(origin.x, origin.y, size.width, size.height, src == null ? new Rectangle(0, 0, imp.getWidth(), imp.getHeight()) : new Rectangle(src), canvas.getMagnification());
        } catch (RuntimeException error) { return null; }
    }
    static Map<String, Object> viewerJson(ImagePlus imp) { var view = view(imp); return view == null ? null : view.json(); }

    /** Highlight for ROIs on the image; empty map when the image is not on screen. Never throws. */
    static Map<String, Object> roiHighlight(ImagePlus imp, List<Roi> rois, String label, int limit) {
        try {
            var view = view(imp); if (view == null) return Map.of();
            var bounds = new ArrayList<Rectangle>(); var ellipses = new ArrayList<Boolean>(); var ids = new ArrayList<String>();
            for (var roi : rois) { if (roi == null) continue; bounds.add(roi.getBounds()); ellipses.add(roi.getType() == Roi.OVAL); ids.add(roi.getName()); }
            return Geometry.payload(label, bounds.size(), Geometry.targets(view, bounds, ellipses, ids, limit), view);
        } catch (RuntimeException error) { return Map.of(); }
    }
    /** Highlight for image-pixel rectangles (e.g. particle bounds from the Results table). */
    static Map<String, Object> rectHighlight(ImagePlus imp, List<Rectangle> bounds, String label, int limit, boolean ellipses) {
        try {
            var view = view(imp); if (view == null) return Map.of();
            var shapes = new ArrayList<Boolean>(); for (int i = 0; i < bounds.size(); i++) shapes.add(ellipses);
            return Geometry.payload(label, bounds.size(), Geometry.targets(view, bounds, shapes, null, limit), view);
        } catch (RuntimeException error) { return Map.of(); }
    }
    static Map<String, Object> imageHighlight(ImagePlus imp, String label) {
        return rectHighlight(imp, List.of(new Rectangle(0, 0, imp.getWidth(), imp.getHeight())), label, 1, false);
    }
    /** Highlight for a visible AWT component. */
    static Map<String, Object> componentHighlight(java.awt.Component component, String label) {
        try {
            if (!component.isShowing()) return Map.of();
            Point p = component.getLocationOnScreen(); Dimension d = component.getSize();
            var target = new java.util.LinkedHashMap<String, Object>(); target.put("x", (double) p.x); target.put("y", (double) p.y); target.put("width", (double) d.width); target.put("height", (double) d.height); target.put("shape", "rect");
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("label", label.length() > 80 ? label.substring(0, 80) : label); result.put("total", 1); result.put("shown", 1); result.put("truncated", false); result.put("kind", "control");
            result.put("targets", List.of(target)); result.put("pointer", Map.of("screenX", p.x + d.width / 2.0, "screenY", p.y + d.height / 2.0)); result.put("coordinateSystem", "screen-top-left"); result.put("flash", true);
            return result;
        } catch (RuntimeException error) { return Map.of(); }
    }

    /** Where the researcher is pointing: the live mouse position mapped into image pixels when it is over the canvas, plus the canvas's last known cursor location. */
    static Map<String, Object> pointer(ImagePlus imp) {
        var result = new java.util.LinkedHashMap<String, Object>();
        Point screen = null;
        try { var info = java.awt.MouseInfo.getPointerInfo(); if (info != null) screen = info.getLocation(); } catch (RuntimeException ignored) { /* no pointer device */ }
        result.put("screenX", screen == null ? null : screen.x); result.put("screenY", screen == null ? null : screen.y);
        ImageCanvas ic = imp == null ? null : imp.getCanvas();
        if (ic != null) trackPointer(imp, ic);
        boolean over = false; Integer x = null, y = null;
        if (ic != null && ic.isShowing() && screen != null) {
            try {
                Point origin = ic.getLocationOnScreen(); Dimension size = ic.getSize();
                int sx = screen.x - origin.x, sy = screen.y - origin.y;
                over = sx >= 0 && sy >= 0 && sx < size.width && sy < size.height;
                if (over) {
                    x = ic.offScreenX(sx); y = ic.offScreenY(sy);
                    over = x >= 0 && y >= 0 && x < imp.getWidth() && y < imp.getHeight();
                    if (over) rememberPointer(imp, ic, sx, sy, System.currentTimeMillis()); else { x = null; y = null; }
                }
            } catch (RuntimeException ignored) { /* canvas not realised */ }
        }
        result.put("x", x); result.put("y", y); result.put("overCanvas", over);
        if (imp != null) { result.put("imageId", imp.getID()); result.put("z", imp.getZ()); result.put("t", imp.getT()); }
        if (ic != null) result.putAll(lastPointer(imp, ic, System.currentTimeMillis()));
        result.put("note", over ? "pointer is over the image canvas; x/y are image pixels" : "pointer is not over the canvas; lastX/lastY are present only for an observed hover on this image plane within 30 seconds");
        return result;
    }

    /** Zooms so the rectangle fills the window when it would otherwise be invisible. Returns true when the view moved. */
    static boolean follow(ImagePlus imp, Rectangle target) {
        var view = view(imp);
        if (view == null || target == null) return false;
        if (!Geometry.tinyOrOffscreen(view, target.x, target.y, target.width, target.height)) return false;
        zoomTo(imp, target);
        return true;
    }

    /** Same algorithm as ImageJ's Image>Zoom>To Selection, for an arbitrary rectangle padded by 30%: unzoom, then zoom in at the centre while it still fits. */
    static void zoomTo(ImagePlus imp, Rectangle target) {
        ImageCanvas ic = imp.getCanvas(); ImageWindow win = imp.getWindow();
        if (ic == null || win == null) throw new IllegalStateException("The image has no window to zoom.");
        ic.unzoom();
        double mag = ic.getMagnification(); Rectangle w = win.getBounds();
        int marginw = (int) (w.width - mag * imp.getWidth()), marginh = (int) (w.height - mag * imp.getHeight());
        int cx = target.x + target.width / 2, cy = target.y + target.height / 2;
        double pw = Math.max(8, target.width) * 1.3, ph = Math.max(8, target.height) * 1.3;
        mag = ImageCanvas.getHigherZoomLevel(mag);
        for (int guard = 0; guard < 24 && pw * mag < w.width - marginw && ph * mag < w.height - marginh; guard++) {
            ic.zoomIn(ic.screenX(cx), ic.screenY(cy));
            double current = ic.getMagnification();
            if (current >= 32.0) break;
            mag = ImageCanvas.getHigherZoomLevel(current);
            w = win.getBounds();
        }
        centre(imp, cx, cy);
    }
    /** Centres the displayed region on an image point when the image is larger than the canvas. */
    static void centre(ImagePlus imp, int cx, int cy) {
        ImageCanvas ic = imp.getCanvas(); if (ic == null) return;
        Rectangle src = ic.getSrcRect();
        if (src.width >= imp.getWidth() && src.height >= imp.getHeight()) return;
        ic.setSourceRect(new Rectangle(cx - src.width / 2, cy - src.height / 2, src.width, src.height));
        ic.repaint();
    }
    /** Presents a freshly opened image: zoomed so it takes at least 60% of the screen height (never above 32x) and centred on screen. */
    static void present(ImagePlus imp) {
        ImageWindow win = imp.getWindow(); ImageCanvas ic = imp.getCanvas();
        if (win == null || ic == null) return;
        try {
            Dimension screen = IJ.getScreenSize();
            double wanted = Math.min(0.80 * screen.width / imp.getWidth(), 0.72 * screen.height / imp.getHeight());
            double mag = ic.getMagnification();
            if (imp.getHeight() * mag < 0.6 * screen.height && wanted > mag) {
                double level = mag; while (ImageCanvas.getHigherZoomLevel(level) <= wanted && level < 32) level = ImageCanvas.getHigherZoomLevel(level);
                if (level > mag) magnify(imp, level, null, null);
            }
            Rectangle bounds = win.getBounds();
            win.setLocation(Math.max(0, (screen.width - bounds.width) / 2), Math.max(0, (screen.height - bounds.height) / 2));
            WindowManager.setCurrentWindow(win); WindowManager.toFront(win);
        } catch (RuntimeException ignored) { /* display only */ }
    }
    static void fit(ImagePlus imp) {
        ImageCanvas ic = imp.getCanvas(); if (ic == null || imp.getWindow() == null) throw new IllegalStateException("The image has no window to zoom.");
        ic.unzoom();
    }
    static void step(ImagePlus imp, boolean in, Integer x, Integer y) {
        ImageCanvas ic = imp.getCanvas(); if (ic == null) throw new IllegalStateException("The image has no window to zoom.");
        Rectangle src = ic.getSrcRect();
        int ix = x == null ? src.x + src.width / 2 : x, iy = y == null ? src.y + src.height / 2 : y;
        if (in) ic.zoomIn(ic.screenX(ix), ic.screenY(iy)); else ic.zoomOut(ic.screenX(ix), ic.screenY(iy));
    }
    /** Sets an exact magnification (clamped to ImageJ's 1/72–32 range) centred on an image point. */
    static void magnify(ImagePlus imp, double magnification, Integer x, Integer y) {
        ImageCanvas ic = imp.getCanvas(); ImageWindow win = imp.getWindow();
        if (ic == null || win == null) throw new IllegalStateException("The image has no window to zoom.");
        double m = Math.max(1 / 72.0, Math.min(32.0, magnification));
        Rectangle src = ic.getSrcRect();
        int cx = x == null ? src.x + src.width / 2 : x, cy = y == null ? src.y + src.height / 2 : y;
        int w = (int) (imp.getWidth() * m), h = (int) (imp.getHeight() * m);
        Dimension screen = IJ.getScreenSize();
        if (w <= screen.width * 0.85 && h <= screen.height * 0.8) {
            ic.setSourceRect(new Rectangle(0, 0, imp.getWidth(), imp.getHeight()));
            ic.setMagnification(m); ic.setSize(w, h); win.pack();
        } else {
            Dimension d = ic.getSize();
            int sw = (int) Math.max(1, d.width / m), sh = (int) Math.max(1, d.height / m);
            ic.setMagnification(m);
            ic.setSourceRect(new Rectangle(cx - sw / 2, cy - sh / 2, sw, sh));
        }
        ic.repaint();
    }
}
