package sc.fidelis.penny.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import ij.ImagePlus;
import ij.gui.ImageCanvas;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.penny.PennyUi;
import sc.fidelis.ui.DocumentView;
import sc.fidelis.ui.Theme;
import sc.fidelis.ui.Workspace;
import sc.fidelis.ui.dev.StubEngine;

/**
 * Scenario for {@link PennyUiGeometryTest} (no ij.* class may load before the patcher): the workspace's {@link PennyUi}
 * on a real hosted IJ1 canvas — screen geometry at 100 % and 200 %, clipping to the viewport, zoom through the view's
 * ladder, and a flash that paints inside the canvas without touching the image's overlay or ROI.
 */
final class PennyUiGeometryScenario {
    private static Workspace ws;
    private static PennyUi ui;

    static void start() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(new StubEngine(null), null, new java.awt.Dimension(1200, 800));
            ws.frame().setVisible(true);
            ws.installPennyUi();
        });
        ui = PennyUi.current();
        assertTrue(ui instanceof WorkspacePennyUi, "Workspace registers its PennyUi: " + ui);
        waitFor("engine ready", () -> ws.engine().state() == EngineState.READY, 30);
    }

    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (ws != null) ws.frame().dispose();
        });
        waitFor("PennyUi unregistered with the workspace", () -> PennyUi.current() == PennyUi.NONE, 10);
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static <T> T edt(java.util.concurrent.Callable<T> c) throws Exception {
        Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                out[0] = c.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    static void geometryAtOneAndTwoHundredPercentAndFlash() throws Exception {
        Path fixture = Path.of(System.getProperty("fidelis.repo.dir", ".")).resolve("fixtures").resolve("two-squares.png");
        assertTrue(fixture.toFile().exists(), "fixture missing: " + fixture);
        ws.engine().open(fixture).get(30, TimeUnit.SECONDS);
        waitFor("document view", () -> ws.documents().focusedView() != null, 20);
        DocumentView view = ws.documents().focusedView();
        ImageCanvas canvas = view.canvas();
        ImagePlus imp = view.imagePlus();
        DocumentId id = view.id();
        waitFor("canvas laid out", () -> canvas.getWidth() > 0 && canvas.isShowing(), 20);

        // 100 %: one image pixel per screen point
        edt(() -> {
            view.actualPixels();
            return null;
        });
        assertEquals(1.0, ui.magnification(id), 1e-12); // called off the EDT: the hook hops to it
        Point p = edt(canvas::getLocationOnScreen);
        assertEquals(new Rectangle(p.x, p.y, 64, 64), ui.canvasOnScreen(id));
        assertEquals(new Rectangle(p.x + 10, p.y + 20, 8, 4), ui.toScreen(id, new Rectangle2D.Double(10, 20, 8, 4)));
        assertEquals(ui.canvasOnScreen(id), ui.canvasOnScreen(null), "null means the focused document");

        // 200 % through the bridge's zoom hook: two ladder steps (1 → 1.5 → 2)
        ui.zoom(id, "in");
        ui.zoom(id, "in");
        waitFor("200 %", () -> view.magnification() == 2.0, 10);
        Point p2 = edt(canvas::getLocationOnScreen);
        assertEquals(new Rectangle(p2.x, p2.y, 128, 128), ui.canvasOnScreen(id));
        assertEquals(new Rectangle(p2.x + 20, p2.y + 40, 16, 8), ui.toScreen(id, new Rectangle2D.Double(10, 20, 8, 4)));
        assertEquals(new Rectangle(p2.x + 21, p2.y + 41, 1, 1), ui.toScreen(id, new Rectangle2D.Double(10.5, 20.5, 0.5, 0.5)),
                "sub-pixel rectangles cover the screen points they touch");
        assertEquals(2.0, ui.magnification(id), 1e-12);

        // zoomed far in: the canvas is clipped to its viewport and off-screen image regions have no screen rectangle
        edt(() -> {
            view.setMagnificationPercent(3200);
            return null;
        });
        Rectangle visible = ui.canvasOnScreen(id);
        Rectangle host = edt(() -> {
            Point hp = view.hostPanel().getLocationOnScreen();
            return new Rectangle(hp.x, hp.y, view.hostPanel().getWidth(), view.hostPanel().getHeight());
        });
        assertNotNull(visible);
        assertTrue(host.contains(visible), "visible canvas " + visible + " lies inside the viewport " + host);
        Rectangle src = edt(canvas::getSrcRect);
        assertTrue(src.x > 2 && src.y > 2, "centred source rectangle " + src);
        assertNull(ui.toScreen(id, new Rectangle2D.Double(0, 0, 2, 2)), "an image corner outside the source rectangle is not on screen");
        ui.zoom(id, "fit");
        waitFor("fit", () -> view.magnification() < 32, 10);

        // flash: drawn inside the canvas; the image's overlay and ROI stay exactly as they were
        Object overlayBefore = imp.getOverlay();
        Object roiBefore = imp.getRoi();
        ui.flash(id, List.of(new Rectangle2D.Double(10, 20, 8, 4), new Rectangle2D.Double(30, 30, 10, 10)), false, "two squares");
        waitFor("flash running", () -> {
            try {
                return edt(() -> CanvasFlash.on(canvas) != null);
            } catch (Exception e) {
                return false;
            }
        }, 5);
        CanvasFlash f = edt(() -> CanvasFlash.on(canvas));
        assertEquals("two squares", f.label());
        assertEquals(2, f.targets());
        assertSame(overlayBefore, imp.getOverlay(), "the flash never touches the ImagePlus overlay");
        assertSame(roiBefore, imp.getRoi(), "nor its ROI");

        // the frame at 1.5 s, composited the way the workspace screenshot does: Penny's colour appears on the outline
        int mint = edt(() -> {
            f.hold(1500);
            BufferedImage img = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            canvas.paint(g);
            CanvasFlash.paintOver(canvas, g);
            g.dispose();
            int n = 0;
            for (int y = 0; y < img.getHeight(); y++)
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff, gr = (rgb >> 8) & 0xff, b = rgb & 0xff;
                    if (gr > 180 && b > 140 && r < 140) n++;
                }
            f.release();
            return n;
        });
        assertTrue(mint > 40, "outline pixels in Penny's colour: " + mint);
        waitFor("flash fades and ends by itself", () -> {
            try {
                return edt(() -> CanvasFlash.on(canvas) == null);
            } catch (Exception e) {
                return false;
            }
        }, 10);
        assertSame(overlayBefore, imp.getOverlay());
    }
}
