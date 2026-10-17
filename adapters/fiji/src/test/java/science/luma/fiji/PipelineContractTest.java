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
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && arguments[0].equals("--fixture")) {
            writeFixture(Path.of(arguments[1]));
            System.out.println("Synthetic 0.5 µm/pixel nuclei fields written to " + arguments[1]); return;
        }
        new ImageJ(ImageJ.NO_SHOW);
        System.setProperty("luma.root", Files.createTempDirectory("penny-pipeline-root").toString());
        ij.Prefs.blackBackground = true;
        var bridge = new LumaBridge();
        // A real Fiji session already has the shared ROI Manager. Register its hidden equivalent in this disposable process
        // so folder macros exercise the same global-manager path, without opening a native window.
        java.awt.EventQueue.invokeAndWait(() -> {
            try { var field = ij.plugin.frame.RoiManager.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null, bridge.ops.manager()); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
        bridge.start(HeadlessContractTest.TOKEN, HeadlessContractTest.PORT);
        try { run(); visibleManagerSelection(); } catch (Throwable error) { error.printStackTrace(); System.exit(1); } finally { bridge.stop(); }
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
        writeFixture(folder);
        ImagePlus imp = IJ.openImage(folder.resolve("field-1.tif").toString());
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp));

        var segmentArgs = new JsonObject(); segmentArgs.addProperty("watershed", false);
        // keepArtifacts: the specks must survive segmentation here so the explicit "remove < 20 µm²" correction is exercised;
        // the folder replay then reproduces the same choice (no artifact exclusion), so counts must still match.
        segmentArgs.addProperty("keepArtifacts", true);
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
        var undoFilter = call("undo", new JsonObject(), true, null);
        var undoneWorkflow = call("analysis_workflow", new JsonObject(), false, null);
        expect(undoFilter.getAsJsonObject("counts").get("roiManagerForImage").getAsInt() == 13 && undoneWorkflow.getAsJsonObject("params").get("minArea").getAsDouble() == 0 && undoneWorkflow.get("countOnSource").getAsInt() == 13, "undo restores objects AND replay parameters, keeping the earlier split correction: " + undoneWorkflow);
        expect(undoneWorkflow.getAsJsonObject("params").get("splitFactor").getAsDouble() > 1, "undo of filter retains prior split correction");
        call("filter_objects", filter, true, null);

        var apply = new JsonObject(); apply.addProperty("name", "pipeline test");
        int sourceResults = Operations.results().size();
        var applied = call("apply_analysis", apply, true, null);
        var run = applied.getAsJsonObject("run");
        expect(run.get("processed").getAsInt() == 3 && run.getAsJsonArray("failed").isEmpty(), "all three images processed: " + run);
        for (var image : run.getAsJsonArray("perImage")) {
            int rows = image.getAsJsonObject().get("resultsRows").getAsInt();
            expect(rows == 10, "each folder image reproduces the corrected count of 10: " + image);
        }
        expect(Files.isRegularFile(Path.of(applied.getAsJsonObject("workflow").get("path").getAsString())), "macro saved");
        System.out.println("PASS segment 12 → split at pointer 13 → remove < 20 µm² 10 → apply to folder: 3 × 10, csv " + run.get("csv"));
        var statsArgs = new JsonObject(); statsArgs.addProperty("plot", false);
        var sourceStats = call("object_stats", statsArgs, false, null);
        expect(sourceStats.get("n").getAsInt() == 10 && Operations.results().size() == sourceResults, "folder run preserves source objects and existing Results rows (" + sourceResults + "): " + sourceStats + ", rows=" + Operations.results().size());
        var fresh = new ImagePlus("fresh-after-folder", new ByteProcessor(240, 160));
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(fresh));
        expect(call("object_stats", statsArgs, false, null).get("n").getAsInt() == 0, "folder objects never leak into a newly opened image");
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp));
        expect(call("object_stats", statsArgs, false, null).get("n").getAsInt() == 10, "source count survives switching to the fresh image and back");
        var stricter = new JsonObject(); stricter.addProperty("name", applied.getAsJsonObject("workflow").get("slug").getAsString()); stricter.addProperty("target", "folder"); stricter.addProperty("folder", folder.toString());
        var override = new JsonObject(); override.addProperty("minArea", 100); stricter.add("params", override);
        var stricterRun = call("run_workflow", stricter, true, null);
        expect(stricterRun.getAsJsonObject("params").get("minArea").getAsDouble() == 100 && stricterRun.get("processed").getAsInt() == 3, "saved pipeline reports effective stricter override");
        for (var image : stricterRun.getAsJsonArray("perImage")) expect(image.getAsJsonObject().get("resultsRows").getAsInt() == 0, "saved 20µm² pipeline honours overridden 100µm² in exact Java arguments: " + image);
        // Default segmentation drops the specks itself; the folder replay must drop them the same way.
        var plain = call("segment_cells", new JsonObject(), true, null);
        int live = plain.get("count").getAsInt();
        expect(plain.getAsJsonObject("excluded").get("tiny").getAsInt() == 3, "specks excluded as artifacts: " + plain.get("excluded"));
        var again = new JsonObject(); again.addProperty("name", "pipeline default");
        var replay = call("apply_analysis", again, true, null).getAsJsonObject("run");
        for (var image : replay.getAsJsonArray("perImage")) expect(image.getAsJsonObject().get("resultsRows").getAsInt() == live, "artifact exclusion replays to the live count " + live + ": " + image);
        System.out.println("PASS default artifact exclusion: live " + live + " = folder replay on 3 images");
        // H&E: nuclei from the hematoxylin channel, live and in the folder replay.
        Path he = Files.createTempDirectory("penny-pipeline-he");
        for (int i = 1; i <= 2; i++) {
            var cp = new ij.process.ColorProcessor(200, 140); cp.setColor(new java.awt.Color(235, 180, 205)); cp.fill();
            cp.setColor(new java.awt.Color(80, 50, 140));
            for (int k = 0; k < 7; k++) cp.fill(new OvalRoi(15 + (k % 4) * 45, 20 + (k / 4) * 60, 22, 22));
            expect(new FileSaver(new ImagePlus("he-" + i + ".tif", cp)).saveAsTiff(he.resolve("he-" + i + ".tif").toString()), "H&E fixture saved");
        }
        ImagePlus rgb = IJ.openImage(he.resolve("he-1.tif").toString());
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(rgb));
        var noForeignWorkflow = HeadlessContractTest.rawCall("analysis_workflow", new JsonObject(), false, null);
        expect(noForeignWorkflow.statusCode() == 409 && noForeignWorkflow.body().contains("Segment the cells first"), "a newly opened image cannot inherit another image's analysis");
        var heSegmented = call("segment_cells", new JsonObject(), true, null);
        int heLive = heSegmented.get("count").getAsInt();
        expect(heLive == 7 && "hematoxylin".equals(heSegmented.get("channelChosenBy").getAsString()), "7 purple nuclei from hematoxylin: " + heSegmented.get("count") + " " + heSegmented.get("channelChosenBy"));
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp));
        var remembered = call("analysis_workflow", new JsonObject(), false, null);
        expect(remembered.get("source").getAsString().equals(imp.getTitle()) && remembered.get("countOnSource").getAsInt() == live, "switching images restores each image's own workflow");
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(rgb));
        var heApply = new JsonObject(); heApply.addProperty("name", "pipeline he");
        var heRun = call("apply_analysis", heApply, true, null).getAsJsonObject("run");
        for (var image : heRun.getAsJsonArray("perImage")) expect(image.getAsJsonObject().get("resultsRows").getAsInt() == heLive, "H&E replay reproduces " + heLive + ": " + image);
        System.out.println("PASS H&E hematoxylin nuclei: live " + heLive + " = folder replay on 2 images");
        Path channels = Files.createTempDirectory("penny-pipeline-channels");
        for (int i = 1; i <= 2; i++) {
            var nuclear = nuclei("nuclear"); var unrelated = new ByteProcessor(240, 160); unrelated.setValue(33); unrelated.fill();
            var stack = new ij.ImageStack(240, 160); stack.addSlice("GFP", unrelated); stack.addSlice("DAPI", nuclear.getProcessor());
            var field = new ImagePlus("multichannel-" + i + ".tif", stack); field.setDimensions(2, 1, 1); field.setOpenAsHyperStack(true); field.setCalibration(nuclear.getCalibration().copy());
            expect(new FileSaver(field).saveAsTiffStack(channels.resolve(field.getTitle()).toString()), "multichannel fixture saved");
        }
        ImagePlus multichannel = IJ.openImage(channels.resolve("multichannel-1.tif").toString()); multichannel.setC(1);
        java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(multichannel));
        var channelArgs = new JsonObject(); channelArgs.addProperty("channel", 2); channelArgs.addProperty("gaussian", false); channelArgs.addProperty("watershed", false); channelArgs.addProperty("keepArtifacts", true);
        var channelLive = call("segment_cells", channelArgs, true, null);
        expect(channelLive.get("count").getAsInt() == 12 && channelLive.getAsJsonObject("summary").getAsJsonObject("mean").get("mean").getAsDouble() == 210, "live analysis measures twelve DAPI objects at source intensity210");
        var channelApply = new JsonObject(); channelApply.addProperty("name", "pipeline channels");
        var channelRun = call("apply_analysis", channelApply, true, null).getAsJsonObject("run");
        expect(channelRun.get("processed").getAsInt() == 2 && channelRun.getAsJsonArray("failed").isEmpty(), "multichannel images replay without prompts: " + channelRun);
        for (var image : channelRun.getAsJsonArray("perImage")) {
            var row = image.getAsJsonObject();
            expect(row.get("resultsRows").getAsInt() == 12 && row.getAsJsonObject("summary").get("Mean").getAsDouble() == 210, "folder replay measures chosen DAPI channel, not active GFP33: " + row);
        }
        expect(multichannel.getC() == 1, "folder replay leaves visible source channel unchanged");
        System.out.println("PASS multichannel source measurement: live and 2 folder images = 12 objects / intensity210 on DAPI, rather than active GFP33");
        tinyCalibratedAreas();
    }
    private static void tinyCalibratedAreas() throws Exception {
        Path folder = Files.createTempDirectory("penny-pipeline-mm");
        for (int i = 1; i <= 2; i++) {
            var field = nuclei("millimetres-" + i + ".tif"); field.getCalibration().pixelWidth = 0.0005; field.getCalibration().pixelHeight = 0.0005; field.getCalibration().setUnit("mm");
            expect(new FileSaver(field).saveAsTiff(folder.resolve(field.getTitle()).toString()), "mm fixture saved");
        }
        var source = IJ.openImage(folder.resolve("millimetres-1.tif").toString()); java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(source));
        var segment = new JsonObject(); segment.addProperty("watershed", false); segment.addProperty("keepArtifacts", true);
        expect(call("segment_cells", segment, true, null).get("count").getAsInt() == 12, "mm image begins with twelve objects");
        var filter = new JsonObject(); filter.addProperty("minArea", 0.00002);
        expect(call("filter_objects", filter, true, null).get("kept").getAsInt() == 9, "twenty square microns in mm removes the three specks");
        var workflow = call("analysis_workflow", new JsonObject(), false, null);
        expect(workflow.getAsJsonObject("params").get("minArea").getAsDouble() == 0.00002, "workflow parameters preserve converted submillimetre precision");
        var apply = new JsonObject(); apply.addProperty("name", "pipeline tiny calibrated areas");
        var applied = call("apply_analysis", apply, true, null); var replay = applied.getAsJsonObject("run");
        String saved = Files.readString(Path.of(applied.getAsJsonObject("workflow").get("path").getAsString()));
        expect(Macros.declaredParams(saved).get("minArea").getAsDouble() == 0.00002 && saved.contains("pennyParam(\"minArea\", \"2.0E-5\")"), "saved macro defaults and Java call preserve the exact area text");
        expect(replay.get("processed").getAsInt() == 2 && replay.getAsJsonArray("failed").isEmpty(), "both mm-calibrated images replay successfully: " + replay);
        for (var image : replay.getAsJsonArray("perImage")) expect(image.getAsJsonObject().get("resultsRows").getAsInt() == 9, "folder replay preserves the live twenty-square-micron cutoff: " + image);
        var rerun = new JsonObject(); rerun.addProperty("name", applied.getAsJsonObject("workflow").get("slug").getAsString()); rerun.addProperty("target", "folder"); rerun.addProperty("folder", folder.toString());
        var override = new JsonObject(); override.addProperty("minArea", 0.000001); rerun.add("params", override);
        var loosened = call("run_workflow", rerun, true, null);
        for (var image : loosened.getAsJsonArray("perImage")) expect(image.getAsJsonObject().get("resultsRows").getAsInt() == 12, "tiny precise override retains all twelve objects: " + image);
        override.addProperty("minArea", 0.00002);
        var restored = call("run_workflow", rerun, true, null);
        for (var image : restored.getAsJsonArray("perImage")) expect(image.getAsJsonObject().get("resultsRows").getAsInt() == 9, "precise override back to20µm² restores the cutoff: " + image);
        System.out.println("PASS calibrated area precision: live, saved defaults and folder replay retain minArea=0.00002 mm²; 2 × 9 objects");
    }
    /** An ordinary manager's real selection listener can restore an old ROI/plane; cleanup must preserve the researcher's newer view. */
    private static void visibleManagerSelection() throws Exception {
        var rawField = ij.plugin.frame.RoiManager.class.getDeclaredField("instance"); rawField.setAccessible(true);
        var previous = ij.plugin.frame.RoiManager.getRawInstance();
        var managerRef = new java.util.concurrent.atomic.AtomicReference<ij.plugin.frame.RoiManager>();
        var stack = new ij.ImageStack(80, 60); var first = new ByteProcessor(80, 60); first.setValue(31); first.fill(); var second = new ByteProcessor(80, 60); second.setValue(79); second.fill(); stack.addSlice(first); stack.addSlice(second);
        var image = new ImagePlus("interactive-position", stack); image.setDimensions(1, 2, 1);
        var currentRoi = new ij.gui.Roi(35, 25, 12, 10);
        try {
            java.awt.EventQueue.invokeAndWait(() -> {
                try { rawField.set(null, null); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
                var manager = new ij.plugin.frame.RoiManager(); manager.setVisible(false); managerRef.set(manager);
                WindowManager.setTempCurrentImage(image); image.setSlice(1);
                var earlier = new ij.gui.Roi(5, 5, 15, 15); earlier.setName("linear Z1"); earlier.setPosition(1); earlier.setProperty("fixture", "linear"); Operations.tag(earlier, image); manager.add((ImagePlus) null, earlier, -1);
                var unpositioned = new ij.gui.Roi(25, 5, 10, 10); unpositioned.setName("all planes internal name"); unpositioned.setPosition(0); unpositioned.setProperty("fixture", "unpositioned"); Operations.tag(unpositioned, image);
                var unpositionedLabel = (ij.gui.Roi) unpositioned.clone(); unpositionedLabel.setName("0002-original manager label"); manager.add((ImagePlus) null, unpositionedLabel, -1); manager.setRoi(unpositioned, 1);
                var hyper = new ij.gui.Roi(50, 5, 12, 12); hyper.setName("hyper internal name"); hyper.setPosition(1, 2, 1); hyper.setProperty("fixture", "hyperstack"); Operations.tag(hyper, image);
                var hyperLabel = (ij.gui.Roi) hyper.clone(); hyperLabel.setName("hyper manager label"); manager.add((ImagePlus) null, hyperLabel, -1); manager.setRoi(hyper, 2);
                expect(manager.getRoi(1).getPosition() == 0 && !manager.getRoi(1).hasHyperStackPosition(), "fixture has a truly unpositioned manager ROI");
                manager.setSelectedIndexes(new int[]{0}); image.setSlice(2); image.setRoi(currentRoi);
                manager.deselect(); manager.setSelectedIndexes(new int[]{0});
                expect(image.getCurrentSlice() == 1 && image.getRoi().getBounds().equals(earlier.getBounds()), "fixture exercises the normal manager selection listener restoring Z1 and its ROI");
                image.setSlice(2); image.setRoi(currentRoi);
            });
            byte[] pixels = ((byte[]) second.getPixels()).clone();
            Path folder = Files.createTempDirectory("penny-manager-selection"); expect(new FileSaver(nuclei("field.tif")).saveAsTiff(folder.resolve("field.tif").toString()), "manager selection fixture saved");
            var make = new JsonObject(); make.addProperty("name", "selection restoration fixture"); make.addProperty("code", "roiManager(\"reset\"); run(\"Clear Results\");"); make.addProperty("test", false); call("make_macro", make, true, null);
            var run = new JsonObject(); run.addProperty("name", "selection restoration fixture"); run.addProperty("target", "folder"); run.addProperty("folder", folder.toString());
            expect(call("run_workflow", run, true, null).get("processed").getAsInt() == 1, "folder macro executes with the ordinary manager");
            java.awt.EventQueue.invokeAndWait(() -> {
                var manager = managerRef.get();
                expect(image.getCurrentSlice() == 2 && image.getRoi().getBounds().equals(currentRoi.getBounds()), "folder cleanup preserves newer Z2 and active ROI after selection callbacks");
                expect(manager.getCount() == 3 && manager.getSelectedIndex() == 0 && manager.getRoi(0).getPosition() == 1, "original manager entries, saved linear plane and selection retained");
                var linear = manager.getRoi(0); var allPlanes = manager.getRoi(1); var hyper = manager.getRoi(2);
                expect("linear Z1".equals(manager.getName(0)) && "linear Z1".equals(linear.getName()) && "linear".equals(linear.getProperty("fixture")), "linear entry label, name and properties preserved");
                expect(allPlanes.getPosition() == 0 && !allPlanes.hasHyperStackPosition() && "0002-original manager label".equals(manager.getName(1)) && "all planes internal name".equals(allPlanes.getName()) && "unpositioned".equals(allPlanes.getProperty("fixture")), "unpositioned entry remains on all planes with its distinct label and name");
                expect(hyper.hasHyperStackPosition() && hyper.getCPosition() == 1 && hyper.getZPosition() == 2 && hyper.getTPosition() == 1 && "hyper manager label".equals(manager.getName(2)) && "hyper internal name".equals(hyper.getName()) && "hyperstack".equals(hyper.getProperty("fixture")), "hyperstack binding, label, name and properties preserved");
                for (var roi : manager.getRoisAsArray()) expect(String.valueOf(image.getID()).equals(roi.getProperty(Operations.ROI_IMAGE_PROPERTY)), "manager image ownership preserved");
                expect(java.util.Arrays.equals(pixels, (byte[]) image.getStack().getPixels(2)), "interactive image pixels unchanged");
            });
            System.out.println("PASS ordinary ROI Manager selection callback: folder replay preserves interactive Z2, active ROI, pixels and selected Z1 manager entry");
        } finally {
            java.awt.EventQueue.invokeAndWait(() -> {
                var manager = managerRef.get(); if (manager != null) { manager.reset(); manager.close(); }
                try { rawField.set(null, previous); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
            });
        }
    }
    private static void writeFixture(Path folder) throws Exception {
        Files.createDirectories(folder);
        for (int i = 1; i <= 3; i++) {
            Path file = folder.resolve("field-" + i + ".tif");
            if (Files.exists(file)) throw new IllegalArgumentException("Refusing to overwrite an existing fixture: " + file);
            expect(new FileSaver(nuclei(file.getFileName().toString())).saveAsTiff(file.toString()), "fixture saved");
        }
    }
}
