package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.OvalRoi;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import java.nio.file.Files;
import java.nio.file.Path;

import static science.luma.fiji.HeadlessContractTest.call;
import static science.luma.fiji.HeadlessContractTest.expect;

/**
 * The Hey Fiji demonstration as a contract, through the real HTTP bridge: find the nuclei, point at two touching nuclei and say
 * they are separate, "remove anything smaller than 20 square microns", then apply the corrected analysis to the whole folder.
 * The folder run must reproduce the corrected count on every image, because corrections change the workflow parameters.
 * Writes only to a temporary folder.
 */
public final class PipelineContractTest {
    public static void main(String[] ignored) throws Exception {
        new ImageJ(ImageJ.NO_SHOW);
        System.setProperty("luma.root", Files.createTempDirectory("penny-pipeline-root").toString());
        ij.Prefs.blackBackground = true;
        var bridge = new LumaBridge();
        bridge.start(HeadlessContractTest.TOKEN, HeadlessContractTest.PORT);
        try { run(); } catch (Throwable error) { error.printStackTrace(); System.exit(1); } finally { bridge.stop(); }
        System.out.println("Pipeline contract (segment, split at the pointer, remove < 20 µm², explain, analysis_workflow, apply_analysis to a folder): passed");
        System.exit(0);
    }

    /** 0.5 µm pixels: eight round nuclei (r 10 px ≈ 78.5 µm²), one touching pair, three specks (r 2 px ≈ 3 µm²). */
    static ImagePlus nuclei(String title) {
        var ip = new ByteProcessor(240, 160); ip.setValue(15); ip.fill(); ip.setValue(210);
        for (int i = 0; i < 8; i++) ip.fill(new OvalRoi(15 + (i % 4) * 55, 15 + (i / 4) * 55, 20, 20));
        ip.fill(new OvalRoi(30, 120, 20, 20)); ip.fill(new OvalRoi(46, 120, 20, 20));
        for (int i = 0; i < 3; i++) ip.fill(new OvalRoi(150 + i * 25, 130, 4, 4));
        var imp = new ImagePlus(title, ip);
        var cal = imp.getCalibration(); cal.pixelWidth = 0.5; cal.pixelHeight = 0.5; cal.setUnit("micron");
        return imp;
    }

    static void run() throws Exception {
        Path folder = Files.createTempDirectory("penny-pipeline-test");
        for (int i = 1; i <= 3; i++) expect(new FileSaver(nuclei("field-" + i + ".tif")).saveAsTiff(folder.resolve("field-" + i + ".tif").toString()), "fixture saved");
        ImagePlus imp = IJ.openImage(folder.resolve("field-1.tif").toString());
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp));

        var segmentArgs = new JsonObject(); segmentArgs.addProperty("watershed", false);
        var segmented = call("segment_cells", segmentArgs, true, null);
        expect(segmented.get("count").getAsInt() == 12, "8 nuclei + 1 touching pair + 3 specks: " + segmented.get("count"));

        var split = new JsonObject(); split.addProperty("x", 48); split.addProperty("y", 130);
        var separated = call("split_objects", split, true, null);
        expect(separated.get("split").getAsInt() == 1 && separated.get("after").getAsInt() == 13 && "pointer".equals(separated.get("scope").getAsString()), "the pointed pair is now two: " + separated);

        var why = new JsonObject(); why.addProperty("x", 152); why.addProperty("y", 132);
        var explained = call("explain_object", why, false, null);
        expect(explained.getAsJsonArray("reasons").toString().contains("debris"), "a speck is explained as unusually small: " + explained);

        var filter = new JsonObject(); filter.addProperty("minArea", 20);
        var filtered = call("filter_objects", filter, true, null);
        expect(filtered.get("removed").getAsInt() == 3 && filtered.get("kept").getAsInt() == 10, "three specks under 20 µm² removed: " + filtered);

        var workflow = call("analysis_workflow", new JsonObject(), false, null);
        var params = workflow.getAsJsonObject("params");
        expect(params.get("minArea").getAsDouble() == 20 && params.get("splitFactor").getAsDouble() > 1 && workflow.get("countOnSource").getAsInt() == 10, "corrections are workflow parameters: " + workflow);
        System.out.println("  workflow steps: " + workflow.get("steps"));

        var apply = new JsonObject(); apply.addProperty("name", "pipeline test");
        var applied = call("apply_analysis", apply, true, null);
        var run = applied.getAsJsonObject("run");
        expect(run.get("processed").getAsInt() == 3 && run.getAsJsonArray("failed").isEmpty(), "all three images processed: " + run);
        for (var image : run.getAsJsonArray("perImage")) {
            int rows = image.getAsJsonObject().get("resultsRows").getAsInt();
            expect(rows == 10, "each folder image reproduces the corrected count of 10: " + image);
        }
        expect(Files.isRegularFile(Path.of(applied.getAsJsonObject("workflow").get("path").getAsString())), "macro saved");
        System.out.println("PASS segment 12 → split at pointer 13 → remove < 20 µm² 10 → apply to folder: 3 × 10, csv " + run.get("csv"));
    }
}
