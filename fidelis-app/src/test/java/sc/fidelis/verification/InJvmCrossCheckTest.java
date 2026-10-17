package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.PixelHash;

/**
 * Independent cross-check that does not involve the Fidelis engine (verification brief T4): the J-12
 * reference macro, step by step, run in this JVM through {@code IJ.runMacro}, compared with the stored
 * baselines produced by the pinned Fiji launched as a separate process (verification/baseline.py).
 *
 * <p>It validates both the runner and the dumper: the Java-side state capture (Snapshot, PixelHash) shares
 * no code with verification/dumper.js. The GUI baseline is the authoritative reference (same mode as this
 * non-headless JVM). The headless baseline is compared too, for every step it has: any difference between
 * the two modes would be a finding (docs A30 constraints; P39).
 *
 * <p>Each step is exact (E1 pixel SHA-256, E5 Results as doubles, E4 ROI vertices, E6 globals); there is no
 * tolerance.
 */
class InJvmCrossCheckTest {

    @BeforeAll
    static void context() {
        FijiFixture.imageJ(); // must precede any use of ij.* classes
    }

    @ParameterizedTest(name = "J-12 on {0}: in-JVM macro equals the pinned-Fiji baseline at every step")
    @ValueSource(strings = {"FX-001", "FX-003", "FX-030"})
    void j12MatchesBaseline(String fixtureId, @TempDir Path tmp) throws Exception {
        J12Reference.Fixture fixture = J12Reference.fixture(fixtureId);
        Path gui = Fixtures.requireBaseline(fixtureId, "gui");
        Optional<Path> headless = Fixtures.baselineDir(fixtureId, "headless");
        List<J12Reference.Step> steps = J12Reference.steps(gui);
        Path tiff = tmp.resolve(fixture.openFile().getFileName().toString().replaceAll("\\.[^.]+$", "") + "-mask.tif");

        IjState.reset();
        List<String> failures = new ArrayList<>();
        IjState.runMacro("setOption(\"BlackBackground\", true);", 30);
        check("s00_globals", gui, headless, failures, false);

        int id1 = 0;
        for (J12Reference.Step step : steps) {
            String macro = J12Reference.localise(step.macro(), fixture.openFile(), tiff);
            if (!step.tag().equals("s01_open")) macro = "id1 = " + id1 + "; " + macro;
            IjState.runMacro(macro, 90);
            if (step.tag().equals("s01_open")) {
                ImagePlus imp = WindowManager.getCurrentImage();
                assertTrue(imp != null, "open produced no image");
                id1 = imp.getID();
            }
            check(step.tag(), gui, headless, failures, compareGlobalMeasurements(step.tag()));
        }

        // The Tiff written by saveAs. Its pixel data and the stored overlay are compared exactly. The file
        // bytes are not: in GUI mode TIFF saving also stores the ROI Manager "show all" list as an overlay,
        // and ImageCanvas recolours the ROI under the mouse pointer (observed: 3 bytes of one ROI's stroke
        // colour differed between the launcher run and this visible-window run; display state, not data).
        assertTrue(Files.isRegularFile(tiff), "saveAs wrote no file");
        ImagePlus back = IJ.openImage(tiff.toString());
        assertTrue(back != null, "saved Tiff cannot be read back");
        JsonNode lastGui = BaselineCompare.loadStep(gui, "s10_save_tiff");
        String wantPixels = lastGui.get("images").get(0).get("allPlanesSha256").asText();
        if (!PixelHash.sha256(back).equals(wantPixels)) {
            failures.add("saved Tiff pixel SHA-256 " + PixelHash.sha256(back) + " vs baseline " + wantPixels);
        }
        JsonNode baselineRois = BaselineCompare.loadStep(gui, "s09_analyze_particles").get("roiManager");
        int overlaySize = back.getOverlay() == null ? 0 : back.getOverlay().size();
        if (overlaySize != baselineRois.size()) {
            failures.add("saved Tiff overlay holds " + overlaySize + " ROIs, baseline ROI Manager " + baselineRois.size());
        } else {
            List<Snapshot.RoiSnap> stored = new ArrayList<>();
            for (ij.gui.Roi roi : back.getOverlay().toArray()) stored.add(Snapshot.roi(roi));
            BaselineCompare.diffRois(baselineRois, new Snapshot(List.of(), null, stored, java.util.Map.of()), failures);
        }
        back.close();
        System.out.println("[cross-check] " + fixtureId + " saved Tiff bytes: " + Fixtures.sha256(tiff) + " ("
                + Files.size(tiff) + " B); launcher baseline: " + Fixtures.readJson(gui.resolve("run.json"))
                .get("saved_tiff_sha256").asText());
        assertEquals(List.of(), failures, () -> "in-JVM macro differs from the pinned-Fiji baseline:\n"
                + String.join("\n", failures));
        IjState.reset();
    }

    private static boolean compareGlobalMeasurements(String tag) {
        return tag.compareTo("s07_set_measurements") >= 0;
    }

    private static void check(String tag, Path gui, Optional<Path> headless, List<String> failures,
                              boolean measurementGlobals) {
        Snapshot snap = Snapshot.capture();
        JsonNode guiStep = BaselineCompare.loadStep(gui, tag);
        for (String d : BaselineCompare.diff(guiStep, snap, false, true)) failures.add(tag + " [gui baseline] " + d);
        List<String> globals = measurementGlobals ? List.of("blackBackground", "measurements", "precision")
                : List.of("blackBackground");
        for (String d : BaselineCompare.diffGlobals(guiStep, snap.globals(), globals)) failures.add(tag + " [gui baseline] " + d);
        headless.ifPresent(h -> {
            if (!Files.exists(h.resolve(tag + ".json"))) return;
            JsonNode step = BaselineCompare.loadStep(h, tag);
            for (String d : BaselineCompare.diff(step, snap, false, true)) failures.add(tag + " [headless baseline] " + d);
        });
    }
}
