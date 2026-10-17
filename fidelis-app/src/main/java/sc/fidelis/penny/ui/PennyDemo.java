package sc.fidelis.penny.ui;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.penny.PennyUi;
import sc.fidelis.penny.voice.PennyVoiceClient;
import sc.fidelis.ui.LiveVoice;
import sc.fidelis.ui.Workspace;

/**
 * {@code --penny-demo <state>} for screenshots: puts the Penny strip (and the Mic) in one state without any gateway, after
 * a real scenario has run. Numbers in the spoken answer and the highlighted outlines come from the engine (the Results
 * rows and the document's ROI list), never from this class. States: {@code listening}, {@code thinking},
 * {@code speaking}, {@code plan} (an explicit plan waiting for Confirm), {@code running} (an automatic plan at step 5 of
 * 8) and {@code answer} (the finished plan, the spoken answer and the ROIs flashed on the canvas).
 */
public final class PennyDemo {
    private PennyDemo() {}

    static final String SEGMENT = "segment the cells and tell me how many there are";
    static final List<String> SEGMENT_STEPS = List.of("8-bit", "Gaussian Blur", "Auto Threshold", "Convert to Mask", "Fill Holes",
            "Watershed", "Set Measurements", "Analyze Particles");
    static final List<String> SEGMENT_MACRO = List.of("run(\"8-bit\");", "run(\"Gaussian Blur...\", \"sigma=1\");",
            "setAutoThreshold(\"Otsu dark\");", "run(\"Convert to Mask\");", "run(\"Fill Holes\");", "run(\"Watershed\");",
            "run(\"Set Measurements...\", \"area mean centroid bounding shape redirect=None decimal=3\");",
            "run(\"Analyze Particles...\", \"display clear add\");");

    /** Apply a demo state; call off the EDT (the screenshot thread). */
    public static void apply(Workspace w, String state) throws Exception {
        Engine engine = w.engine();
        DocumentId doc = engine.focused().orElse(null);
        String title = doc == null ? null : engine.document(doc).map(DocumentInfo::title).orElse(null);
        String unit = doc == null ? "pixels" : engine.document(doc).map(DocumentInfo::unit).filter(u -> u != null && !u.isBlank()).orElse("pixels");
        List<RoiGeometry> rois = doc == null ? List.of() : engine.rois(doc);
        List<MeasurementRow> rows = engine.results();
        double sum = 0;
        int n = 0;
        for (MeasurementRow r : rows) {
            Double a = r.values().get("Area");
            if (a != null && !a.isNaN()) {
                sum += a;
                n++;
            }
        }
        final String answer = n == 0 ? "There are no measured objects yet."
                : String.format(Locale.ROOT, "I found %d cells; their average area is %.0f %s².", n, sum / n, "pixel".equals(unit) || "pixels".equals(unit) ? "px" : unit);
        final int cells = n;
        LiveVoice.Phase phase = switch (state) {
            case "listening", "thinking", "speaking", "running", "answer" -> LiveVoice.Phase.LISTENING;
            default -> LiveVoice.Phase.OFF;
        };
        PennyPanel p = w.penny();
        Map<String, String> ctx = title == null ? Map.of() : Map.of("document", title);
        SwingUtilities.invokeAndWait(() -> {
            w.setLiveVoice(new FixedVoice(phase));
            p.setConnected(true, null);
            p.setVoice(phase == LiveVoice.Phase.LISTENING ? PennyVoiceClient.Voice.LISTENING : PennyVoiceClient.Voice.OFF);
            switch (state) {
                case "listening" -> {
                    p.heard("segment the cells and tell me how", true, true);
                    p.indicator().freeze(0.4, 0.72);
                }
                case "thinking" -> {
                    p.userSaid(SEGMENT);
                    p.setWorking(true);
                    p.acknowledged("Segmenting the cells on " + (title == null ? "the image" : title) + " now.");
                    p.indicator().freeze(0.35, 0);
                }
                case "speaking" -> {
                    p.userSaid(SEGMENT);
                    p.answered(answer, false);
                    p.setSpeaking(true);
                    p.indicator().freeze(0.5, 0);
                }
                case "plan" -> {
                    p.userSaid("convert it to a mask with a black background");
                    p.acknowledged("That changes a global setting, so it needs your confirmation.");
                    p.showPlan(new PennyUi.Plan("plan-demo-1", "convert it to a mask with a black background", true, true,
                            List.of("step 1 (Convert to Mask) changes the global BlackBackground preference"),
                            List.of("Convert to Mask"), List.of("setOption(\"BlackBackground\", true);", "run(\"Convert to Mask\");"), ctx), false);
                }
                case "running" -> {
                    p.userSaid(SEGMENT);
                    p.setWorking(true);
                    p.acknowledged("Segmenting the cells now.");
                    p.showPlan(new PennyUi.Plan("plan-demo-2", SEGMENT, true, false, List.of(), SEGMENT_STEPS, SEGMENT_MACRO, ctx), true);
                    p.planProgress("plan-demo-2", 4, "Fill Holes");
                    p.indicator().freeze(0.35, 0);
                }
                case "answer" -> {
                    p.userSaid(SEGMENT);
                    p.showPlan(new PennyUi.Plan("plan-demo-3", SEGMENT, true, false, List.of(), SEGMENT_STEPS, SEGMENT_MACRO, ctx), true);
                    p.planFinished("plan-demo-3", true, null);
                    p.answered(answer, false);
                    p.setSpeaking(true);
                    p.indicator().freeze(0.5, 0);
                }
                default -> throw new IllegalArgumentException("unknown --penny-demo state " + state);
            }
        });
        if ("answer".equals(state) && doc != null && !rois.isEmpty()) {
            List<Rectangle2D> rects = new ArrayList<>();
            for (RoiGeometry g : rois) {
                Rectangle2D b = bounds(g);
                if (b != null) rects.add(b);
            }
            PennyUi.current().flash(doc, rects, true, cells + " cells");
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                var v = w.documents().view(doc);
                CanvasFlash f = v == null ? null : CanvasFlash.on(v.canvas());
                if (f != null) f.hold(1500);
            });
        }
        System.out.println("[penny] demo '" + state + "': " + p.stateText() + " | " + p.lineText()
                + (p.planShowing() ? " | plan: " + p.planStatusText() + " " + p.planStepTexts() : "") + " | rois " + rois.size()
                + ", results " + rows.size());
    }

    /** Image-pixel bounds of an ROI from its geometry. */
    static Rectangle2D bounds(RoiGeometry g) {
        if (g.bounds() != null && g.bounds().length == 4) return new Rectangle2D.Double(g.bounds()[0], g.bounds()[1], g.bounds()[2], g.bounds()[3]);
        if (g.xs() == null || g.xs().isEmpty() || g.ys() == null || g.ys().size() != g.xs().size()) return null;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < g.xs().size(); i++) {
            x0 = Math.min(x0, g.xs().get(i));
            x1 = Math.max(x1, g.xs().get(i));
            y0 = Math.min(y0, g.ys().get(i));
            y1 = Math.max(y1, g.ys().get(i));
        }
        return new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0);
    }

    /** A live voice frozen in one phase (no gateway). */
    private record FixedVoice(LiveVoice.Phase fixed) implements LiveVoice {
        @Override public Phase phase() { return fixed; }
        @Override public String unavailableReason() { return null; }
        @Override public void toggle() {}
        @Override public boolean sendText(String text, boolean spoken) { return false; }
        @Override public void addChangeListener(Runnable r) {}
        @Override public void removeChangeListener(Runnable r) {}
    }
}
