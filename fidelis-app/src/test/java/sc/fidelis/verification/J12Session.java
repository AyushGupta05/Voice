package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.PixelHash;

/**
 * Journey J-12 as typed {@link ActionRequest}s submitted to any {@link Engine}, compared step by step with
 * the GUI baseline of the pinned Fiji (E1 pixels, E5 Results as doubles, E4 ROI vertices, E6 globals).
 * Shared by EngineParityTest (product engine) and WorkspaceJourneyTest (workspace over the UI's stub engine),
 * so swapping the engine under test is one line.
 *
 * <p>The requests are written independently of any engine's macro generator and carry every key explicitly
 * (docs/data/commands-core-parameters.csv). BlackBackground is set before the first step exactly as the
 * reference macro does. Requests an engine rejects with UNSUPPORTED_IN_MODE (a development stub) are applied
 * through the same IJ1 API the engine will use, and the fact is recorded in {@link Run#notes()}.
 */
final class J12Session {
    private J12Session() {}

    /** Outcome of one scenario: comparison failures, notes on fallbacks, the document and saved file. */
    record Run(List<String> failures, List<String> notes, DocumentId document, Path tiff) {}

    // ---- J-12 typed requests (explicit keys; docs/data/commands-core-parameters.csv) ----------------

    static Map<String, Object> subtractBackground() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rolling", 50);
        for (String k : List.of("light", "separate", "create", "sliding", "disable")) p.put(k, false);
        return p;
    }

    static Map<String, Object> setMeasurements() {
        Map<String, Object> p = new LinkedHashMap<>();
        for (String k : List.of("area", "mean", "min", "integrated")) p.put(k, true);
        for (String k : List.of("standard", "modal", "centroid", "center", "perimeter", "bounding", "fit", "shape",
                "feret's", "median", "skewness", "kurtosis", "area_fraction", "stack", "limit", "display", "invert",
                "scientific", "add", "nan")) {
            p.put(k, false);
        }
        p.put("redirect", "None");
        p.put("decimal", 9);
        return p;
    }

    static Map<String, Object> analyzeParticles() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("size", "20-Infinity");
        p.put("circularity", "0.00-1.00");
        p.put("show", "Masks");
        for (String k : List.of("display", "clear", "include", "summarize", "add")) p.put(k, true);
        for (String k : List.of("exclude", "overlay", "composite", "record", "in_situ")) p.put(k, false);
        return p;
    }

    private static ActionRequest command(String id, Map<String, Object> params, DocumentId doc) {
        return ActionRequest.command(Origin.PANEL, id, params, TargetScope.of(doc));
    }

    private static ActionResult submit(Engine engine, ActionRequest request) throws Exception {
        ActionResult result = engine.submit(request).get(120, TimeUnit.SECONDS);
        assertTrue(result.succeeded(), () -> "request " + request.commandId() + request.operation() + " ended in "
                + result.state() + ": " + result.error());
        return result;
    }

    /** Submit; when the engine reports UNSUPPORTED_IN_MODE run {@code fallback} instead and record a note. */
    private static void submitOrFallback(Engine engine, ActionRequest request, Run notes, Runnable fallback) throws Exception {
        ActionResult result = engine.submit(request).get(120, TimeUnit.SECONDS);
        if (result.succeeded()) return;
        if (result.state() == OperationState.FAILED && result.error() != null
                && result.error().code() == ErrorCode.UNSUPPORTED_IN_MODE) {
            notes.notes().add(request.operation() + " rejected by the engine (" + result.error().message()
                    + "); applied through the IJ1 API instead");
            fallback.run();
            return;
        }
        throw new AssertionError("request " + request.operation() + " ended in " + result.state() + ": " + result.error());
    }

    /**
     * Run the whole journey on {@code engine}.
     *
     * @param roisFromEngine compare {@code engine.rois(doc)} with the baseline ROI list; when false the IJ1 ROI
     *                       Manager singleton is read instead (an engine that does not mirror it, such as the stub)
     * @param afterStep      called with the step tag after each comparison (UI checks hook in here)
     */
    static Run run(Engine engine, String fixtureId, Path tmp, boolean roisFromEngine, Consumer<String> afterStep)
            throws Exception {
        J12Reference.Fixture fixture = J12Reference.fixture(fixtureId);
        Path baseline = Fixtures.requireBaseline(fixtureId, "gui");
        IjState.reset();
        Prefs.blackBackground = true; // explicit global, as in the reference macro
        List<DocumentId> before = engine.documents().stream().map(d -> d.id()).toList();
        Run run = new Run(new ArrayList<>(), new ArrayList<>(), null, null);
        List<String> failures = run.failures();
        // same file name as the reference macro, because saving renames the document (E3 title rule)
        Path tiff = tmp.resolve(fixture.openFile().getFileName().toString().replaceAll("\\.[^.]+$", "") + "-mask.tif");

        ActionResult opened = submit(engine, ActionRequest.session(Origin.PANEL, Operations.OPEN,
                Map.of("path", fixture.openFile().toString()), null));
        assertEquals(1, opened.digest().added().size(), "open must add exactly one document");
        DocumentId doc = opened.digest().added().get(0);
        step("s01_open", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submit(engine, command("ij1:Process>Subtract Background...", subtractBackground(), doc));
        step("s02_subtract_background", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        Map<String, Object> gaussian = new LinkedHashMap<>();
        gaussian.put("sigma", 2);
        submit(engine, command("ij1:Process>Filters>Gaussian Blur...", gaussian, doc));
        step("s03_gaussian_blur", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submitOrFallback(engine, ActionRequest.direct(Origin.PANEL, Operations.SET_AUTO_THRESHOLD,
                Map.of("method", "Otsu", "dark", true), TargetScope.of(doc)), run,
                () -> IJ.setAutoThreshold(engine.imagePlus(doc), "Otsu dark"));
        step("s04_threshold", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submit(engine, command("ij1:Process>Binary>Convert to Mask", Map.of(), doc));
        step("s05_convert_to_mask", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submit(engine, command("ij1:Process>Binary>Watershed", Map.of(), doc));
        step("s06_watershed", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submit(engine, command("ij1:Analyze>Set Measurements...", setMeasurements(), doc));
        step("s07_set_measurements", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submit(engine, command("ij1:Analyze>Measure", Map.of(), doc));
        step("s08_measure", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submit(engine, command("ij1:Analyze>Analyze Particles...", analyzeParticles(), doc));
        step("s09_analyze_particles", engine, baseline, doc, before, failures, roisFromEngine, afterStep);

        submitOrFallback(engine, ActionRequest.session(Origin.PANEL, Operations.SAVE_AS,
                Map.of("path", tiff.toString(), "format", "tiff"), TargetScope.of(doc)), run,
                () -> IJ.saveAs(engine.imagePlus(doc), "tiff", tiff.toString()));
        step("s10_save_tiff", engine, baseline, doc, before, failures, roisFromEngine, afterStep);
        assertTrue(Files.isRegularFile(tiff), "SAVE_AS wrote no file");
        ImagePlus back = IJ.openImage(tiff.toString());
        assertNotNull(back, "saved Tiff cannot be read back");
        String want = BaselineCompare.loadStep(baseline, "s10_save_tiff").get("images").get(0).get("allPlanesSha256").asText();
        if (!PixelHash.sha256(back).equals(want)) failures.add("saved Tiff pixel SHA-256 differs from the baseline");
        run.notes().add("saved Tiff overlay ROIs: " + (back.getOverlay() == null ? 0 : back.getOverlay().size())
                + " (GUI reference stores the ROI Manager list as an overlay)");
        back.close();
        return new Run(failures, run.notes(), doc, tiff);
    }

    private static void step(String tag, Engine engine, Path baseline, DocumentId original, List<DocumentId> before,
                             List<String> failures, boolean roisFromEngine, Consumer<String> afterStep) {
        compare(tag, engine, baseline, original, before, failures, roisFromEngine);
        if (afterStep != null) afterStep.accept(tag);
    }

    /** Snapshot of the scenario's documents, Results and ROI list as reported by the engine API. */
    static void compare(String tag, Engine engine, Path baseline, DocumentId original, List<DocumentId> before,
                        List<String> failures, boolean roisFromEngine) {
        JsonNode step = BaselineCompare.loadStep(baseline, tag);
        Snapshot snap = snapshot(engine, original, before, roisFromEngine);
        // A capture log may retain rows that IJ1's `clear` dropped from the live table: tolerate extra leading rows.
        for (String d : BaselineCompare.diff(step, snap, true, true)) failures.add(tag + " " + d);
        engine.globals().forEach((k, v) -> {
            if (k.toLowerCase().contains("blackbackground")
                    && !v.equalsIgnoreCase(step.get("globals").get("blackBackground").asText())) {
                failures.add(tag + " E6 global " + k + "=" + v + " vs baseline "
                        + step.get("globals").get("blackBackground").asText());
            }
        });
    }

    /** The scenario's documents (those not in {@code before}), Results and ROI list as the engine reports them. */
    static Snapshot snapshot(Engine engine, DocumentId original, List<DocumentId> before, boolean roisFromEngine) {
        List<Snapshot.Img> images = new ArrayList<>();
        for (var info : engine.documents()) {
            if (before.contains(info.id())) continue;
            images.add(Snapshot.image(engine.imagePlus(info.id())));
        }
        List<Snapshot.RoiSnap> rois = new ArrayList<>();
        if (roisFromEngine) {
            for (RoiGeometry g : engine.rois(original)) {
                float[] xs = new float[g.xs().size()];
                float[] ys = new float[g.ys().size()];
                for (int i = 0; i < xs.length; i++) {
                    xs[i] = g.xs().get(i);
                    ys[i] = g.ys().get(i);
                }
                rois.add(new Snapshot.RoiSnap(g.name(), xs, ys, null));
            }
        } else {
            rois.addAll(Snapshot.capture().rois());
        }
        return new Snapshot(images, table(engine), rois, Map.of());
    }

    static Snapshot.Table table(Engine e) {
        List<MeasurementRow> rows = e.results();
        List<String> headings = e.resultColumns().headings();
        LinkedHashMap<String, double[]> columns = new LinkedHashMap<>();
        for (String h : headings) {
            double[] values = new double[rows.size()];
            for (int r = 0; r < rows.size(); r++) {
                Double v = rows.get(r).values().get(h);
                values[r] = v == null ? Double.NaN : v;
            }
            columns.put(h, values);
        }
        return new Snapshot.Table(rows.size(), columns, List.of());
    }
}
