package sc.fidelis.penny.ui;

import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import ij.gui.ImageCanvas;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.penny.PennyUi;
import sc.fidelis.ui.DocumentView;
import sc.fidelis.ui.Workspace;

/**
 * The workspace's {@link PennyUi} (docs/FIDELIS-PROTOCOL.md "Highlight and the workspace"), registered by
 * {@code Workspace.launch}. Flashes are drawn inside the hosted canvas ({@link CanvasFlash}); zoom uses the document
 * view's own controls (the IJ1 zoom ladder, never the canvas's native zoom); geometry is computed from the canvas's
 * screen location, source rectangle and magnification, clipped to the part of the canvas that is visible in its viewport.
 * Plan hooks drive the {@link PennyPanel} card. Every method may be called from any thread: actions are posted to the
 * EDT, queries wait for the EDT at most {@value #QUERY_TIMEOUT_MS} ms and answer null/NaN otherwise.
 */
public final class WorkspacePennyUi implements PennyUi {
    static final long QUERY_TIMEOUT_MS = 500;
    private final Workspace workspace;
    private final PennyPanel panel;

    public WorkspacePennyUi(Workspace workspace, PennyPanel panel) {
        this.workspace = workspace;
        this.panel = panel;
    }

    // ---- canvas --------------------------------------------------------------------------------

    @Override
    public void flash(DocumentId document, List<Rectangle2D> imageRects, boolean ellipses, String label) {
        if (imageRects == null || imageRects.isEmpty()) return;
        List<Rectangle2D> copy = new ArrayList<>();
        for (Rectangle2D r : imageRects) if (r != null) copy.add((Rectangle2D) r.clone());
        SwingUtilities.invokeLater(() -> {
            DocumentView v = view(document);
            if (v == null || !v.isShowing() || v.canvas() == null) return;
            CanvasFlash.show(v.canvas(), copy, ellipses, label);
        });
    }

    @Override
    public void zoom(DocumentId document, String how) {
        String h = how == null ? "" : how.trim().toLowerCase(Locale.ROOT);
        SwingUtilities.invokeLater(() -> {
            DocumentView v = view(document);
            if (v == null) return;
            switch (h) {
                case "in" -> v.zoomStep(true, null);
                case "out" -> v.zoomStep(false, null);
                case "fit" -> v.fit();
                case "selection" -> v.zoomToSelection();
                case "actual", "100", "100%", "1:1" -> v.actualPixels();
                default -> System.err.println("[penny] unknown zoom '" + how + "'");
            }
        });
    }

    @Override
    public Rectangle canvasOnScreen(DocumentId document) {
        return query(() -> visibleCanvas(view(document)));
    }

    @Override
    public Rectangle toScreen(DocumentId document, Rectangle2D imageRect) {
        if (imageRect == null) return null;
        Rectangle2D r = (Rectangle2D) imageRect.clone();
        return query(() -> toScreen(view(document), r));
    }

    @Override
    public double magnification(DocumentId document) {
        Double m = query(() -> {
            DocumentView v = view(document);
            return v == null || v.canvas() == null ? Double.NaN : v.magnification();
        });
        return m == null ? Double.NaN : m;
    }

    /** Global screen rectangle of the canvas part that is visible in its viewport (EDT). */
    static Rectangle visibleCanvas(DocumentView v) {
        if (v == null || !v.isShowing()) return null;
        ImageCanvas c = v.canvas();
        if (c == null || !c.isShowing() || c.getWidth() <= 0 || c.getHeight() <= 0) return null;
        Point p = c.getLocationOnScreen();
        Rectangle canvas = new Rectangle(p.x, p.y, c.getWidth(), c.getHeight());
        Container host = v.hostPanel();
        if (host != null && host.isShowing()) {
            Rectangle vis = host instanceof javax.swing.JComponent j ? j.getVisibleRect() : new Rectangle(host.getSize());
            Point hp = host.getLocationOnScreen();
            canvas = canvas.intersection(new Rectangle(hp.x + vis.x, hp.y + vis.y, vis.width, vis.height));
        }
        return canvas.isEmpty() ? null : canvas;
    }

    /** Global screen rectangle of an image-pixel rectangle, clipped to the visible canvas (EDT). */
    static Rectangle toScreen(DocumentView v, Rectangle2D image) {
        Rectangle visible = visibleCanvas(v);
        if (visible == null) return null;
        ImageCanvas c = v.canvas();
        double mag = c.getMagnification();
        Rectangle src = c.getSrcRect();
        Point p = c.getLocationOnScreen();
        double x0 = p.x + (image.getX() - src.x) * mag;
        double y0 = p.y + (image.getY() - src.y) * mag;
        double x1 = p.x + (image.getMaxX() - src.x) * mag;
        double y1 = p.y + (image.getMaxY() - src.y) * mag;
        int ix = (int) Math.floor(x0 + 1e-9);
        int iy = (int) Math.floor(y0 + 1e-9);
        Rectangle r = new Rectangle(ix, iy, (int) Math.ceil(x1 - 1e-9) - ix, (int) Math.ceil(y1 - 1e-9) - iy);
        Rectangle clipped = r.intersection(visible);
        return clipped.isEmpty() ? null : clipped;
    }

    private DocumentView view(DocumentId document) {
        if (workspace.documents() == null) return null;
        return document == null ? workspace.documents().focusedView() : workspace.documents().view(document);
    }

    private static <T> T query(Callable<T> c) {
        try {
            if (SwingUtilities.isEventDispatchThread()) return c.call();
            FutureTask<T> f = new FutureTask<>(c);
            SwingUtilities.invokeLater(f);
            return f.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- plan card -----------------------------------------------------------------------------

    @Override
    public void showPlan(Plan plan, boolean running) {
        if (plan != null) SwingUtilities.invokeLater(() -> panel.showPlan(plan, running));
    }

    @Override
    public void planProgress(String planId, int step, String label) {
        SwingUtilities.invokeLater(() -> panel.planProgress(planId, step, label));
    }

    @Override
    public void planFinished(String planId, boolean ok, String message) {
        SwingUtilities.invokeLater(() -> panel.planFinished(planId, ok, message));
    }

    @Override
    public void clearPlan(String planId) {
        SwingUtilities.invokeLater(() -> panel.clearPlan(planId));
    }
}
