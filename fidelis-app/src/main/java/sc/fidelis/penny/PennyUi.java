package sc.fidelis.penny;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Map;
import sc.fidelis.engine.api.DocumentId;

/**
 * What the Penny bridge asks of the workspace (docs/FIDELIS-PROTOCOL.md). The bridge never touches Swing directly; the
 * workspace registers an implementation at launch. Every method may be called from any thread; implementations hop to the
 * EDT themselves and must not block for long. Without a workspace (tests, smoke) the no-op implementation is used.
 */
public interface PennyUi {
    /** Flash outlines over the hosted canvas of {@code document}: image-pixel rectangles, ellipses when {@code ellipses}. */
    default void flash(DocumentId document, List<Rectangle2D> imageRects, boolean ellipses, String label) {}
    /** Zoom the document's view: "in", "out", "fit" or "selection". */
    default void zoom(DocumentId document, String how) {}
    /** Screen rectangle (global points, top-left origin) of the visible hosted canvas, or null when not shown. */
    default Rectangle canvasOnScreen(DocumentId document) { return null; }
    /** Screen rectangle for an image-pixel rectangle on the visible canvas, or null. */
    default Rectangle toScreen(DocumentId document, Rectangle2D imageRect) { return null; }
    /** Current magnification of the document's view (1.0 = 100 %), or NaN. */
    default double magnification(DocumentId document) { return Double.NaN; }

    /** A plan the bridge is about to run (auto) or holds for confirmation (explicit). */
    record Plan(String planId, String utterance, boolean spoken, boolean explicit, List<String> reasons,
                List<String> stepLabels, List<String> macroPreview, Map<String, String> context) {}
    /** Show the plan card; {@code running} false = waiting for Confirm. */
    default void showPlan(Plan plan, boolean running) {}
    /** Progress on the shown plan: step index (0-based) and its label. */
    default void planProgress(String planId, int step, String label) {}
    /** The plan finished (ok) or stopped (error message). */
    default void planFinished(String planId, boolean ok, String message) {}
    /** Remove the plan card. */
    default void clearPlan(String planId) {}

    PennyUi NONE = new PennyUi() {};

    final class Holder { private static volatile PennyUi current = NONE; private Holder() {} }
    static void register(PennyUi ui) { Holder.current = ui == null ? NONE : ui; }
    static PennyUi current() { return Holder.current; }
}
