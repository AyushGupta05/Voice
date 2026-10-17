package science.luma.fiji;

import java.awt.Rectangle;
import java.util.List;
import java.util.Map;

/** Pure screen-geometry checks: no display, no ImageJ. */
public final class GeometryContractTest {
    public static void main(String[] ignored) {
        // Canvas at screen (100, 200), 400x300 px, showing image region (50, 60, 200, 150) at 2x.
        var view = new Geometry.View(100, 200, 400, 300, new Rectangle(50, 60, 200, 150), 2.0);
        var rect = Geometry.screenRect(view, 60, 70, 110, 120);
        expect((double) rect.get("x") == 120 && (double) rect.get("y") == 220 && (double) rect.get("width") == 100 && (double) rect.get("height") == 100, "image (60,70)-(110,120) at 2x maps to screen (120,220) 100x100: " + rect);
        expect(Geometry.screenRect(view, 0, 0, 40, 40) == null, "a rectangle left of the displayed region is off screen");
        var clipped = Geometry.screenRect(view, 240, 200, 400, 400);
        expect((double) clipped.get("x") + (double) clipped.get("width") <= 500.0001 && (double) clipped.get("y") + (double) clipped.get("height") <= 500.0001, "targets are clipped to the canvas: " + clipped);
        var tiny = Geometry.screenRect(view, 60, 70, 61, 71);
        expect((double) tiny.get("width") == Geometry.MIN_TARGET_PX && (double) tiny.get("height") == Geometry.MIN_TARGET_PX, "one-pixel targets keep an 8 px minimum");
        expect(Geometry.tinyOrOffscreen(view, 60, 70, 10, 10), "a 20x20 screen-px target in a 400x300 canvas is tiny (<12%)");
        expect(!Geometry.tinyOrOffscreen(view, 60, 70, 100, 10), "a target wider than 12% of the canvas is not tiny");
        expect(Geometry.tinyOrOffscreen(view, 300, 60, 100, 100), "a target outside srcRect is off screen");
        expect(!Geometry.tinyOrOffscreen(view, 100, 100, 50, 50), "a 100x100 screen-px target inside the view needs no zoom");
        var payload = Geometry.payload("label", 3, Geometry.targets(view, List.of(new Rectangle(60, 70, 50, 50), new Rectangle(0, 0, 10, 10), new Rectangle(100, 100, 20, 20)), List.of(true, false, false), List.of("a", "b", "c"), 10), view);
        expect(payload.get("shown").equals(2) && payload.get("total").equals(3) && Boolean.TRUE.equals(payload.get("truncated")), "off-screen targets are counted but not shown: " + payload);
        @SuppressWarnings("unchecked") var pointer = (Map<String, Object>) payload.get("pointer");
        expect((double) pointer.get("screenX") == 180 && (double) pointer.get("screenY") == 270, "pointer sits at the centre of the shown targets' union: " + pointer);
        @SuppressWarnings("unchecked") var first = (Map<String, Object>) ((List<Object>) payload.get("targets")).getFirst();
        expect("ellipse".equals(first.get("shape")) && "a".equals(first.get("id")), "oval ROIs become ellipse targets with their ids");
        expect("screen-top-left".equals(payload.get("coordinateSystem")), "global screen coordinates, top-left origin");
        expect(Geometry.union(List.of(new Rectangle(0, 0, 10, 10), new Rectangle(20, 20, 5, 5))).equals(new Rectangle(0, 0, 25, 25)), "union bounds");
        pointerContext();
        batchEditorPlacement();
        System.out.println("Screen geometry (mapping, clipping, 12% visibility rule, payload shape): passed");
    }
    static void batchEditorPlacement() {
        var screen = new Rectangle(0, 24, 1440, 876);
        var bottomTable = new Rectangle(950, 700, 480, 200);
        var above = Pipeline.batchEditorBounds(bottomTable, 700, 400, screen);
        expect(screen.contains(above) && above.y + above.height <= bottomTable.y - 8, "bottom-clamped batch table places the full editor above it: " + above);
        var topTable = new Rectangle(900, 100, 400, 200);
        var below = Pipeline.batchEditorBounds(topTable, 500, 300, screen);
        expect(screen.contains(below) && below.y == topTable.y + topTable.height + 8, "editor remains below the table when it fits: " + below);
        var oversized = Pipeline.batchEditorBounds(new Rectangle(100, 100, 800, 650), 1800, 1100, screen);
        expect(oversized.equals(screen), "oversized editor is resized into the usable screen rather than leaving controls offscreen: " + oversized);
        var negativeScreen = new Rectangle(-1600, -900, 1600, 900);
        var negative = Pipeline.batchEditorBounds(new Rectangle(-300, -150, 290, 150), 600, 400, negativeScreen);
        expect(negativeScreen.contains(negative), "editor placement supports screen bounds with negative origins: " + negative);
    }
    static void pointerContext() {
        var stack = new ij.ImageStack(100, 80); stack.addSlice(new ij.process.ByteProcessor(100, 80)); stack.addSlice(new ij.process.ByteProcessor(100, 80));
        var imp = new ij.ImagePlus("pointer-image", stack); var canvas = new ij.gui.ImageCanvas(imp);
        canvas.setSize(100, 80); Viewer.trackPointer(imp, canvas);
        expect(Viewer.lastPointer(imp, canvas, 1000).isEmpty(), "unvisited canvas has no fabricated (0,0) pointer");
        // Drive a genuine registered mouse-motion listener without opening a native window or moving the system pointer.
        var event = new java.awt.event.MouseEvent(canvas, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 35, 25, 0, false);
        for (var listener : canvas.getMouseMotionListeners()) listener.mouseMoved(event);
        var observed = Viewer.lastPointer(imp, canvas, System.currentTimeMillis());
        expect(observed.get("lastX").equals(35) && observed.get("lastY").equals(25), "canvas mouse event records actual image pixels");
        Viewer.rememberPointer(imp, canvas, 35, 25, 1000);
        expect(!Viewer.lastPointer(imp, canvas, 1000 + Viewer.POINTER_MAX_AGE_MS).isEmpty(), "recently pointed position survives moving to the microphone");
        expect(Viewer.lastPointer(imp, canvas, 1001 + Viewer.POINTER_MAX_AGE_MS).isEmpty(), "old point expires");
        imp.setSlice(2);
        expect(Viewer.lastPointer(imp, canvas, 1001).isEmpty(), "point from a previous image plane cannot target this slice");
        var other = new ij.ImagePlus("other", new ij.process.ByteProcessor(100, 80));
        expect(Viewer.lastPointer(other, canvas, 1001).isEmpty(), "point from another image is refused");
    }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
