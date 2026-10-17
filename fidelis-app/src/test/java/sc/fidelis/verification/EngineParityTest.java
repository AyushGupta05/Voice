package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.HarnessSelfCheckEngine;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.PixelHash;

/**
 * L2/L3 engine parity (docs A30, D18): the typed requests of journey J-12 submitted to the product
 * {@link Engine} must reproduce, exactly, the stored reference outputs of the pinned Fiji (E1 pixels, E5
 * Results as doubles, E4 ROI vertices, E6 globals).
 *
 * <p>The tests are engine-agnostic: the engine is obtained from {@code sc.fidelis.engine.Engines.current()}
 * by reflection (the class is added by the engine agent) and the tests are skipped when it does not exist.
 * With the environment variable {@code FIDELIS_PARITY_SELFCHECK=1} a thin IJ.run adapter
 * ({@link HarnessSelfCheckEngine}) stands in, which proves this machinery but says nothing about the product.
 *
 * <p>The requests below are written independently of the engine's macro generator and carry every key
 * explicitly (booleans decided), so the expected option strings are those of docs/data/commands-core-parameters.csv.
 * BlackBackground is set by the test before the first step, exactly as the reference macro does (D16 J-12
 * step 7 records it as a global).
 */
class EngineParityTest {
    private static Engine engine;
    private static String engineKind;

    @BeforeAll
    static void acquire() {
        FijiFixture.imageJ(); // the live context must exist before any ij.* use
        if ("1".equals(System.getenv("FIDELIS_PARITY_SELFCHECK"))) {
            engine = new HarnessSelfCheckEngine();
            engineKind = "harness self-check adapter (NOT the product engine)";
            return;
        }
        try {
            Class<?> engines = Class.forName("sc.fidelis.engine.Engines");
            Object current = engines.getMethod("current").invoke(null);
            if (current instanceof Optional<?> o) current = o.orElse(null);
            if (current instanceof Engine e) {
                engine = e;
                engineKind = "product engine " + e.getClass().getName();
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            engine = null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("sc.fidelis.engine.Engines.current() failed", e);
        }
        assumeTrue(engine != null, "sc.fidelis.engine.Engines is not available yet (engine agent A1): parity tests skipped");
    }

    // ---- J-12 typed requests (explicit keys; docs/data/commands-core-parameters.csv) ----------------

    private static Map<String, Object> subtractBackground() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rolling", 50);
        for (String k : List.of("light", "separate", "create", "sliding", "disable")) p.put(k, false);
        return p;
    }

    private static Map<String, Object> setMeasurements() {
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

    private static Map<String, Object> analyzeParticles() {
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

    private static ActionResult submit(ActionRequest request) throws Exception {
        ActionResult result = engine.submit(request).get(120, TimeUnit.SECONDS);
        assertTrue(result.succeeded(), () -> "request " + request.commandId() + request.operation() + " ended in "
                + result.state() + ": " + result.error());
        return result;
    }

    @ParameterizedTest(name = "J-12 on {0}: engine output equals the pinned-Fiji baseline at every step")
    @ValueSource(strings = {"FX-001", "FX-003"})
    void j12Parity(String fixtureId, @TempDir Path tmp) throws Exception {
        J12Reference.Fixture fixture = J12Reference.fixture(fixtureId);
        Path baseline = Fixtures.requireBaseline(fixtureId, "gui");
        System.out.println("[parity] " + fixtureId + " via " + engineKind);
        IjState.reset();
        Prefs.blackBackground = true; // explicit global, as in the reference macro
        List<DocumentId> before = engine.documents().stream().map(d -> d.id()).toList();
        List<String> failures = new ArrayList<>();
        // same file name as the reference macro, because saving renames the document (E3 title rule)
        Path tiff = tmp.resolve(fixture.openFile().getFileName().toString().replaceAll("\\.[^.]+$", "") + "-mask.tif");

        ActionResult opened = submit(ActionRequest.session(Origin.PANEL, Operations.OPEN,
                Map.of("path", fixture.openFile().toString()), TargetScope.of(new DocumentId("none"))));
        assertEquals(1, opened.digest().added().size(), "open must add exactly one document");
        DocumentId doc = opened.digest().added().get(0);
        compare("s01_open", baseline, doc, before, failures);

        submit(command("ij1:Process>Subtract Background...", subtractBackground(), doc));
        compare("s02_subtract_background", baseline, doc, before, failures);

        Map<String, Object> gaussian = new LinkedHashMap<>();
        gaussian.put("sigma", 2);
        submit(command("ij1:Process>Filters>Gaussian Blur...", gaussian, doc));
        compare("s03_gaussian_blur", baseline, doc, before, failures);

        submit(ActionRequest.direct(Origin.PANEL, Operations.SET_AUTO_THRESHOLD,
                Map.of("method", "Otsu", "dark", true), TargetScope.of(doc)));
        compare("s04_threshold", baseline, doc, before, failures);

        submit(command("ij1:Process>Binary>Convert to Mask", Map.of(), doc));
        compare("s05_convert_to_mask", baseline, doc, before, failures);

        submit(command("ij1:Process>Binary>Watershed", Map.of(), doc));
        compare("s06_watershed", baseline, doc, before, failures);

        submit(command("ij1:Analyze>Set Measurements...", setMeasurements(), doc));
        compare("s07_set_measurements", baseline, doc, before, failures);

        submit(command("ij1:Analyze>Measure", Map.of(), doc));
        compare("s08_measure", baseline, doc, before, failures);

        submit(command("ij1:Analyze>Analyze Particles...", analyzeParticles(), doc));
        compare("s09_analyze_particles", baseline, doc, before, failures);

        submit(ActionRequest.session(Origin.PANEL, Operations.SAVE_AS,
                Map.of("path", tiff.toString(), "format", "tiff"), TargetScope.of(doc)));
        compare("s10_save_tiff", baseline, doc, before, failures);
        assertTrue(Files.isRegularFile(tiff), "SAVE_AS wrote no file");
        ImagePlus back = IJ.openImage(tiff.toString());
        assertNotNull(back, "saved Tiff cannot be read back");
        String want = BaselineCompare.loadStep(baseline, "s10_save_tiff").get("images").get(0).get("allPlanesSha256").asText();
        if (!PixelHash.sha256(back).equals(want)) failures.add("saved Tiff pixel SHA-256 differs from the baseline");
        System.out.println("[parity] saved Tiff overlay ROIs: " + (back.getOverlay() == null ? 0 : back.getOverlay().size())
                + " (GUI reference stores the ROI Manager list as an overlay)");
        back.close();

        assertEquals(List.of(), failures, () -> "engine differs from the pinned-Fiji reference:\n" + String.join("\n", failures));
        IjState.reset();
    }

    /** Snapshot of the scenario's documents, Results and ROI list as reported by the engine API. */
    private static void compare(String tag, Path baseline, DocumentId original, List<DocumentId> before,
                                List<String> failures) {
        JsonNode step = BaselineCompare.loadStep(baseline, tag);
        List<Snapshot.Img> images = new ArrayList<>();
        for (var info : engine.documents()) {
            if (before.contains(info.id())) continue;
            images.add(Snapshot.image(engine.imagePlus(info.id())));
        }
        Snapshot.Table results = table(engine);
        List<Snapshot.RoiSnap> rois = new ArrayList<>();
        for (RoiGeometry g : engine.rois(original)) {
            float[] xs = new float[g.xs().size()];
            float[] ys = new float[g.ys().size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = g.xs().get(i);
                ys[i] = g.ys().get(i);
            }
            rois.add(new Snapshot.RoiSnap(g.name(), xs, ys, null));
        }
        Snapshot snap = new Snapshot(images, results, rois, Map.of());
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

    private static Snapshot.Table table(Engine e) {
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
