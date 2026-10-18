package sc.fidelis.penny;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import sc.fidelis.engine.api.DocumentId;

/**
 * A stand-in workspace for bridge tests: records every {@link PennyUi} call and maps image pixels to the screen as a
 * canvas at (100, 200) shown at 2x, so the highlight payload's geometry can be checked exactly.
 */
class RecordingUi implements PennyUi {
    static final int CANVAS_X = 100, CANVAS_Y = 200;
    static final double MAG = 2.0;

    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<Plan> shown = new CopyOnWriteArrayList<>();
    volatile int flashed;

    @Override
    public void flash(DocumentId document, List<Rectangle2D> imageRects, boolean ellipses, String label) {
        flashed = imageRects.size();
        calls.add("flash " + document + " " + imageRects.size() + " " + label);
    }

    @Override
    public void showChart(DocumentId document, double[] values, String unit, String title) {
        calls.add("chart " + document + " " + values.length + " " + unit);
    }

    @Override
    public void zoom(DocumentId document, String how) {
        calls.add("zoom " + document + " " + how);
    }

    @Override
    public Rectangle canvasOnScreen(DocumentId document) {
        return new Rectangle(CANVAS_X, CANVAS_Y, 512, 508);
    }

    @Override
    public Rectangle toScreen(DocumentId document, Rectangle2D r) {
        return new Rectangle((int) Math.round(CANVAS_X + r.getX() * MAG), (int) Math.round(CANVAS_Y + r.getY() * MAG),
                (int) Math.round(r.getWidth() * MAG), (int) Math.round(r.getHeight() * MAG));
    }

    @Override
    public double magnification(DocumentId document) {
        return MAG;
    }

    @Override
    public void showPlan(Plan plan, boolean running) {
        shown.add(plan);
        calls.add("showPlan " + plan.planId() + " running=" + running + " explicit=" + plan.explicit() + " steps=" + plan.stepLabels().size());
    }

    @Override
    public void planProgress(String planId, int step, String label) {
        calls.add("progress " + planId + " " + step + " " + label);
    }

    @Override
    public void planFinished(String planId, boolean ok, String message) {
        calls.add("finished " + planId + " ok=" + ok + " " + message);
    }

    @Override
    public void clearPlan(String planId) {
        calls.add("clear " + planId);
    }

    long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }
}
