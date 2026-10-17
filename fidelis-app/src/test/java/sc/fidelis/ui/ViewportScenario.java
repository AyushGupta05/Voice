package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;


import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.gui.Toolbar;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.ui.dev.StubEngine;

/**
 * Scenario for {@link WorkspaceViewportTest}. Kept out of the test class so that no ij.* class is loaded
 * before {@code FijiFixture} has run the ImageJ1 patcher (ContractSmokeTest rule).
 *
 * Hosts the real IJ1 canvas in the workspace (EXP-01 strategy S3) with the development stub engine,
 * zooms through IJ1's ladder, and checks that a native rectangle-tool drag creates the ROI at the
 * image coordinates given by {@code offScreenX/Y} at magnification 2.
 */
final class ViewportScenario {
    private static Workspace ws;
    private static final List<Throwable> errors = new CopyOnWriteArrayList<>();

    static void start() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> errors.add(e));
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            StubEngine engine = new StubEngine(null);
            ws = Workspace.create(engine, null, new java.awt.Dimension(1200, 800));
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> ws.engine().state() == EngineState.READY, 30);
    }

    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (ws != null) ws.frame().dispose();
        });
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static void fire(ImageCanvas c, int id, int x, int y, int mods) throws Exception {
        int ex = id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_MOVED ? mods & ~InputEvent.BUTTON1_DOWN_MASK : mods | InputEvent.BUTTON1_DOWN_MASK;
        MouseEvent ev = new MouseEvent(c, id, System.currentTimeMillis(), ex, x, y, x, y, 1, false,
                id == MouseEvent.MOUSE_MOVED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
        edt(() -> c.dispatchEvent(ev));
    }

    private static void drag(ImageCanvas c, int x0, int y0, int x1, int y1) throws Exception {
        fire(c, MouseEvent.MOUSE_MOVED, x0, y0, 0);
        fire(c, MouseEvent.MOUSE_PRESSED, x0, y0, 0);
        for (int i = 1; i <= 6; i++) fire(c, MouseEvent.MOUSE_DRAGGED, x0 + (x1 - x0) * i / 6, y0 + (y1 - y0) * i / 6, 0);
        fire(c, MouseEvent.MOUSE_RELEASED, x1, y1, 0);
    }

    static void zoomLadderAndNativeRectangleDragAtMagnificationTwo() throws Exception {
        Path fixture = Path.of(System.getProperty("fidelis.repo.dir", ".")).resolve("fixtures").resolve("two-squares.png");
        assertTrue(fixture.toFile().exists(), "fixture missing: " + fixture);
        ws.engine().open(fixture).get(30, TimeUnit.SECONDS);
        waitFor("document view", () -> ws.documents().focusedView() != null, 20);
        DocumentView view = ws.documents().focusedView();
        ImageCanvas canvas = view.canvas();
        ImagePlus imp = view.imagePlus();
        waitFor("canvas laid out", () -> canvas.getWidth() > 0 && canvas.isShowing(), 20);
        assertNotNull(Toolbar.getInstance(), "IJ1 toolbar must exist");
        assertEquals(64, imp.getWidth());

        // the canvas lives in the workspace, not in the hidden window
        assertTrue(SwingUtilities.isDescendingFrom(canvas, ws.frame()));
        assertTrue(!ws.engine().hostWindow(view.id()).isVisible(), "host window must stay hidden");

        // zoom ladder: every step is IJ1's next level
        edt(view::actualPixels);
        assertEquals(1.0, view.magnification());
        double m = 1.0;
        for (int i = 0; i < 4; i++) {
            double expected = ImageCanvas.getHigherZoomLevel(m);
            edt(() -> view.zoomStep(true, null));
            assertEquals(expected, view.magnification(), 1e-12, "zoom in from " + m);
            m = expected;
        }
        assertEquals(4.0, m);
        for (int i = 0; i < 2; i++) {
            double expected = ImageCanvas.getLowerZoomLevel(m);
            edt(() -> view.zoomStep(false, null));
            assertEquals(expected, view.magnification(), 1e-12, "zoom out from " + m);
            m = expected;
        }
        assertEquals(2.0, m);
        assertEquals(2.0, canvas.getMagnification());
        assertEquals(128, canvas.getWidth(), "image 64 px at 200 % is 128 px wide");
        Rectangle src = canvas.getSrcRect();
        assertEquals(new Rectangle(0, 0, 64, 64), src);

        // native rectangle tool, even screen coordinates: no rounding ambiguity
        edt(() -> Toolbar.getInstance().setTool(Toolbar.RECTANGLE));
        edt(imp::deleteRoi);
        int[] expectX = new int[2];
        int[] expectY = new int[2];
        edt(() -> {
            expectX[0] = canvas.offScreenX(20);
            expectY[0] = canvas.offScreenY(30);
            expectX[1] = canvas.offScreenX(100);
            expectY[1] = canvas.offScreenY(80);
        });
        assertEquals(10, expectX[0]);
        assertEquals(15, expectY[0]);
        assertEquals(50, expectX[1]);
        assertEquals(40, expectY[1]);
        drag(canvas, 20, 30, 100, 80);
        Roi roi = imp.getRoi();
        assertNotNull(roi, "rectangle tool drag must create a ROI");
        assertEquals(new Rectangle(10, 15, 40, 25), roi.getBounds());

        // odd coordinates: IJ1 rounds to the nearest pixel boundary; allow the one-pixel rounding band
        edt(imp::deleteRoi);
        drag(canvas, 21, 31, 101, 81);
        Roi odd = imp.getRoi();
        assertNotNull(odd);
        Rectangle b = odd.getBounds();
        assertTrue(Math.abs(b.x - 10.5) <= 1.0 && Math.abs(b.y - 15.5) <= 1.0, "origin " + b);
        assertTrue(Math.abs(b.x + b.width - 50.5) <= 1.0 && Math.abs(b.y + b.height - 40.5) <= 1.0, "far corner " + b);

        // the ROI belongs to the focused document and the status bar read-out uses IJ1 formatting
        fire(canvas, MouseEvent.MOUSE_MOVED, 20, 30, 0);
        assertTrue(ws.status().readoutText().startsWith("x=10, y=15"), "read-out was: " + ws.status().readoutText());
        assertTrue(ws.status().readoutText().contains("value="), "read-out was: " + ws.status().readoutText());

        // ctrl+wheel zooms around the cursor and keeps the image point under it
        edt(() -> {
            view.zoomStep(true, new java.awt.Point(64, 64));
        });
        assertEquals(3.0, view.magnification());

        // fit and 1:1 do not throw and are consistent
        edt(view::fit);
        assertEquals(DocumentView.Mode.FIT, view.mode());
        edt(view::actualPixels);
        assertEquals(1.0, view.magnification());

        assertTrue(errors.isEmpty(), "uncaught exceptions: " + errors);
        Component c = canvas;
        assertNotNull(c.getParent());
    }
}
