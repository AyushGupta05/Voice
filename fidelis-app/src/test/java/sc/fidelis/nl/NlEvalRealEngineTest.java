package sc.fidelis.nl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.ProductEngine;

import org.junit.jupiter.api.AfterAll;

/**
 * The same 124 labelled cases against the product engine: the real catalogue (953 commands, 53 with
 * complete schemas) and real documents of the same shape as {@link FakeEngine}'s (opened through
 * {@code Engine.open} from TIFFs written here, so calibration, dimensions and the ROI are real).
 */
class NlEvalRealEngineTest extends NlEvalSupport {
    private final Map<String, DocumentId> docs = new HashMap<>();

    @Override protected Engine createEngine() {
        Engine e = ProductEngine.get();
        IjState.reset();
        try {
            Path dir = Files.createTempDirectory("fidelis-nl-eval");
            open(e, dir, "uncal", "blobs.tif", image("blobs", "8-bit ramp", 256, 256, 1, "pixel", 1.0, false));
            open(e, dir, "cal", "cells.tif", image("cells", "8-bit ramp", 512, 512, 1, "\u00b5m", 0.65, false));
            open(e, dir, "stack", "stack.tif", image("stack", "8-bit ramp", 256, 256, 5, "\u00b5m", 0.5, false));
            open(e, dir, "rgb", "photo.tif", image("photo", "RGB ramp", 256, 256, 1, "pixel", 1.0, false));
            ImagePlus r = image("withroi", "8-bit ramp", 256, 256, 1, "pixel", 1.0, false);
            r.setRoi(new Roi(10, 10, 50, 50));
            open(e, dir, "roi", "withroi.tif", r);
            open(e, dir, "comp", "composite.tif", image("composite", "16-bit ramp", 256, 256, 3, "\u00b5m", 0.325, true));
        } catch (IOException | java.util.concurrent.ExecutionException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    private static ImagePlus image(String title, String spec, int w, int h, int n, String unit, double pw, boolean composite) {
        ImagePlus imp = IJ.createImage(title, spec, w, h, n);
        imp.getCalibration().setUnit(unit);
        imp.getCalibration().pixelWidth = pw;
        imp.getCalibration().pixelHeight = pw;
        if (composite) {
            imp.setDimensions(n, 1, 1);
            imp = new CompositeImage(imp, CompositeImage.COMPOSITE);
        }
        return imp;
    }

    private void open(Engine e, Path dir, String state, String file, ImagePlus imp)
            throws IOException, java.util.concurrent.ExecutionException, InterruptedException {
        Path p = dir.resolve(file);
        IJ.saveAsTiff(imp, p.toString());
        imp.close();
        ActionResult res = e.open(p).get();
        if (res.state() != sc.fidelis.engine.api.Enums.OperationState.COMPLETED) throw new IllegalStateException("open " + file + ": " + res);
        docs.put(state, e.focused().orElseThrow());
    }

    @Override protected DocumentId stateId(String state) { return "none".equals(state) ? null : docs.get(state); }

    @AfterAll
    void cleanUp() { IjState.reset(); }
}
