package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.OvalRoi;
import ij.gui.ShapeRoi;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Windowless ImageJ (NO_SHOW) checks of Analysis on synthetic images with known truth; optionally prints numbers for bundled sample images. */
public final class AnalysisContractTest {
    static ImagePlus classified; static Journal journal; static Operations ops; static Analysis analysis;

    public static void main(String[] ignored) throws Exception {
        new ImageJ(ImageJ.NO_SHOW);
        ij.Prefs.blackBackground = true;
        journal = new Journal(); ops = new Operations(journal); analysis = new Analysis(ops, journal);
        try {
            tumorGray(); tumorRgb(); colocalisation(); classification(); objectStats(); filterAndSplit(); filterBimodal(); globalManagerIndices(); centroidScope(); perImageManager(); emptyExistingObjects(); errors(); samples();
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.out.println("Analysis contract (tumor_region, colocalize, classify_intensity, object_stats, filter_objects, split_objects): passed");
        System.exit(0);
    }

    static void tumorGray() throws Exception {
        var ip = new ByteProcessor(512, 512); var random = new Random(1);
        ip.setValue(220); ip.fill();
        int r = 120; ip.setValue(70); ip.fill(new OvalRoi(256 - r, 256 - r, 2 * r, 2 * r));
        noise(ip, random, 25);
        var imp = new ImagePlus("synthetic-tumour-gray.tif", ip); activate(imp);
        double truth = Math.PI * r * r; byte[] before = ((byte[]) ip.getPixels()).clone();
        var result = edt(() -> analysis.tumorRegion(new JsonObject()));
        double area = num(result, "area");
        System.out.println("  gray tumour: area " + area + " truth " + Math.round(truth) + " stain " + result.get("stain") + " regions " + result.get("regions") + " circularity " + result.get("circularity") + " solidity " + result.get("solidity"));
        expect(Math.abs(area - truth) / truth < 0.10, "gray tumour area within 10% of truth");
        expect("brightfield".equals(result.get("stain")), "light-background grayscale is treated as brightfield");
        expect(((Number) result.get("regions")).intValue() == 1, "one region");
        expect(num(result, "circularity") > 0.8 && num(result, "solidity") > 0.9, "disc-like shape metrics");
        expect(ops.managerOrNull().getCount() == 1 && "Tumor".equals(ops.managerOrNull().getName(0)), "Tumor in the ROI Manager");
        expect(imp.getRoi() != null && "Tumor".equals(imp.getRoi().getName()), "Tumor is the active selection");
        expect(WindowManager.getImageCount() == 0, "no helper windows left open");
        expect(((List<?>) result.get("steps")).size() >= 4, "steps reported");
        expect(java.util.Arrays.equals(before, (byte[]) imp.getProcessor().getPixels()), "tumour outlining does not change pixels");
        System.out.println("PASS tumor_region on an 8-bit synthetic image");
    }

    static void tumorRgb() throws Exception {
        ops.manager().reset();
        var cp = new ColorProcessor(600, 400); var random = new Random(2);
        cp.setColor(new java.awt.Color(240, 232, 240)); cp.fill();
        // A large hematoxylin-dense ellipse, a small separate fragment (dropped by minAreaFraction), and a pink eosin-only band.
        cp.setColor(new java.awt.Color(235, 160, 200)); cp.fillRect(0, 330, 600, 40);
        cp.setColor(new java.awt.Color(90, 60, 140)); cp.fill(new OvalRoi(100, 60, 300, 220));
        cp.fill(new OvalRoi(500, 40, 20, 20));
        int[] px = (int[]) cp.getPixels();
        for (int i = 0; i < px.length; i++) { int n = (int) (random.nextGaussian() * 12); int rr = clamp(((px[i] >> 16) & 255) + n), gg = clamp(((px[i] >> 8) & 255) + n), bb = clamp((px[i] & 255) + n); px[i] = (rr << 16) | (gg << 8) | bb; }
        var imp = new ImagePlus("synthetic-he.tif", cp); activate(imp);
        double truth = Math.PI * 150 * 110;
        var args = new JsonObject(); args.addProperty("name", "Mass");
        var result = edt(() -> analysis.tumorRegion(args));
        double area = num(result, "area");
        System.out.println("  RGB tumour: area " + area + " truth " + Math.round(truth) + " stain " + result.get("stain") + " regions " + result.get("regions") + " steps " + result.get("steps"));
        expect("he".equals(result.get("stain")), "RGB is treated as H&E");
        expect(Math.abs(area - truth) / truth < 0.10, "RGB tumour area within 10% of truth");
        expect(((Number) result.get("regions")).intValue() == 1, "small fragment and eosin band excluded");
        expect("Mass".equals(ops.managerOrNull().getName(0)), "custom name used");
        expect("tissue".equals(result.get("areaFractionOf")), "fraction is of the tissue: " + result.get("areaFractionOf"));
        double tissueTruth = truth + 600 * 40 + Math.PI * 100;
        expect(Math.abs(num(result, "tissueArea") - tissueTruth) / tissueTruth < 0.10, "tissue mask = stained area, white background excluded: " + result.get("tissueArea") + " vs " + Math.round(tissueTruth));
        expect(Math.abs(num(result, "areaFraction") - area / num(result, "tissueArea")) < 1e-9 && num(result, "areaFractionOfImage") < num(result, "areaFraction"), "both fractions reported");
        // Rerun with the previous outline still selected: same result (the old outline is not taken as a restriction).
        var again = edt(() -> analysis.tumorRegion(args));
        expect(num(again, "area") == area && num(again, "areaFraction") == num(result, "areaFraction") && !again.containsKey("within"), "rerun is identical: " + again.get("area") + " vs " + area);
        // A nucleus-scale sigma is raised to the tissue scale and reported.
        var tiny = new JsonObject(); tiny.addProperty("name", "Mass"); tiny.addProperty("sigma", 0.5);
        var raised = edt(() -> analysis.tumorRegion(tiny));
        expect(raised.get("adjusted") instanceof List<?> list && !list.isEmpty() && num(raised, "sigma") >= 3, "tiny sigma raised: " + raised.get("adjusted"));
        System.out.println("PASS tumor_region on an RGB H&E-like synthetic image (tissue fraction, deterministic rerun, sigma floor)");
    }

    static void colocalisation() throws Exception {
        int w = 256, h = 256; var random = new Random(3);
        var a = new FloatProcessor(w, h);
        for (int k = 0; k < 40; k++) { a.setValue(random.nextInt(800) + 200); int r = 6 + random.nextInt(14); a.fill(new OvalRoi(random.nextInt(w - 2 * r), random.nextInt(h - 2 * r), 2 * r, 2 * r)); }
        new ij.plugin.filter.GaussianBlur().blurGaussian(a, 2, 2, 0.01);
        var b = (FloatProcessor) a.duplicate(); var anti = new FloatProcessor(w, h);
        float[] pa = (float[]) a.getPixels(), pb = (float[]) b.getPixels(), pc = (float[]) anti.getPixels();
        for (int i = 0; i < pa.length; i++) { pb[i] = (float) (pa[i] + random.nextGaussian() * 30); pc[i] = (float) (1000 - pa[i] + random.nextGaussian() * 30); }
        var stack = new ImageStack(w, h); stack.addSlice("A", a); stack.addSlice("B", b); stack.addSlice("anti", anti);
        var imp = new ImagePlus("synthetic-coloc.tif", stack); imp.setDimensions(3, 1, 1);
        activate(imp);
        float[] before = ((float[]) imp.getStack().getPixels(2)).clone();
        var result = edt(() -> analysis.colocalize(new JsonObject()));
        System.out.println("  correlated: " + result);
        expect(num(result, "pearson") > 0.9, "B = A + noise gives Pearson > 0.9");
        @SuppressWarnings("unchecked") var manders = (Map<String, Object>) result.get("manders");
        expect(num(manders, "m1") > 0.5 && num(manders, "m2") > 0.5, "Manders high for correlated channels");
        expect(String.valueOf(result.get("interpretation")).startsWith("strong positive"), "interpretation band");
        expect("Costes".equals(result.get("method")), "Costes thresholds found");
        expect(java.util.Arrays.equals(before, (float[]) imp.getStack().getPixels(2)), "colocalize does not change pixels");
        var args = new JsonObject(); args.addProperty("channelA", 1); args.addProperty("channelB", 3);
        var anticorrelated = edt(() -> analysis.colocalize(args));
        System.out.println("  anticorrelated: pearson " + anticorrelated.get("pearson") + " method " + anticorrelated.get("method") + " interpretation " + anticorrelated.get("interpretation"));
        expect(num(anticorrelated, "pearson") < 0, "anticorrelated pair gives Pearson < 0");
        // two separate images by id
        var impA = new ImagePlus("A", a.duplicate()); var impB = new ImagePlus("B", b.duplicate());
        edt(() -> { WindowManager.setTempCurrentImage(impA); WindowManager.setTempCurrentImage(impB); return Map.of(); });
        // ops.images() includes only the current temp image when no windows exist; give ids of the current one via find() fallback.
        var byId = new JsonObject(); byId.addProperty("imageA", impB.getID()); byId.addProperty("imageB", impB.getID());
        var same = edt(() -> analysis.colocalize(byId));
        expect(num(same, "pearson") > 0.999, "an image against itself gives Pearson 1");
        // roi restriction
        activate(imp); imp.setRoi(new Roi(0, 0, 128, 128));
        var roiArgs = new JsonObject(); roiArgs.addProperty("roi", true);
        var roiResult = edt(() -> analysis.colocalize(roiArgs));
        expect(((Number) roiResult.get("pixels")).intValue() == 128 * 128, "roi restricts the pixels");
        System.out.println("PASS colocalize (correlated, anticorrelated, by image id, within a selection)");
    }

    static void classification() throws Exception {
        var ip = new ByteProcessor(400, 200); ip.setValue(20); ip.fill();
        var manager = ops.manager(); manager.reset();
        for (int i = 0; i < 10; i++) {
            int x = 20 + (i % 5) * 75, y = 30 + (i / 5) * 90, r = 15 + (i % 3) * 3;
            boolean bright = i == 1 || i == 4 || i == 6 || i == 9;
            ip.setValue(bright ? 200 : 60); var roi = new OvalRoi(x, y, 2 * r, 2 * r); ip.fill(roi);
            manager.addRoi(roi);
        }
        var imp = new ImagePlus("synthetic-ki67.tif", ip); classified = imp; activate(imp);
        var args = new JsonObject(); args.addProperty("label", "Ki67");
        var result = edt(() -> analysis.classifyIntensity(args));
        System.out.println("  classify: " + result);
        expect(((Number) result.get("positive")).intValue() == 4 && ((Number) result.get("negative")).intValue() == 6, "4 of 10 positive");
        expect(num(result, "percentPositive") == 40.0, "40% positive");
        expect(result.get("positiveIndices").equals(List.of(1, 4, 6, 9)), "positive indices are the bright objects");
        expect(manager.getName(1).equals("Ki67+ 1") && manager.getName(0).equals("Ki67- 1"), "ROIs renamed");
        expect(imp.getOverlay() != null && imp.getOverlay().size() == 10, "classes pushed to the overlay");
        var manual = new JsonObject(); manual.addProperty("threshold", 250);
        var none = edt(() -> analysis.classifyIntensity(manual));
        expect(((Number) none.get("positive")).intValue() == 0, "manual threshold above all objects gives none positive");
        System.out.println("PASS classify_intensity (4 of 10 bright objects)");
    }

    static void objectStats() throws Exception {
        var manager = ops.managerOrNull();
        double expected = 0; var imp = classified;
        for (var roi : manager.getRoisAsArray()) { imp.setRoi(roi); expected += imp.getStatistics().area; }
        imp.deleteRoi(); expected /= manager.getCount();
        var result = edt(() -> analysis.regionStats(new JsonObject()));
        System.out.println("  object_stats: n " + result.get("n") + " area " + result.get("area") + " largest " + result.get("largest"));
        expect(((Number) result.get("n")).intValue() == 10, "n = 10");
        @SuppressWarnings("unchecked") var area = (Map<String, Object>) result.get("area");
        expect(Math.abs(num(area, "mean") - expected) < 1e-6, "mean area matches ImageJ measurements");
        @SuppressWarnings("unchecked") var histogram = (Map<String, Object>) result.get("areaHistogram");
        expect(((List<?>) histogram.get("counts")).stream().mapToInt(c -> ((Number) c).intValue()).sum() == 10, "histogram counts every object");
        @SuppressWarnings("unchecked") var largest = (List<Map<String, Object>>) result.get("largest");
        expect(num(largest.getFirst(), "area") == num(area, "max"), "largest first");
        imp.setRoi(new Roi(0, 0, 200, 100));
        var selectionArgs = new JsonObject(); selectionArgs.addProperty("scope", "selection"); selectionArgs.addProperty("plot", false);
        var selected = edt(() -> analysis.regionStats(selectionArgs));
        expect(num(selected, "n") == 3 && num(selected, "totalForImage") == 10 && "selection".equals(selected.get("scopeUsed")), "count existing objects in selection without resegmenting: " + selected);
        expect(manager.getCount() == 10 && imp.getRoi().getBounds().equals(new java.awt.Rectangle(0, 0, 200, 100)), "count preserves objects and selection");
        var regionArgs = new JsonObject(); var region = new JsonObject();
        region.addProperty("x", 0); region.addProperty("y", 0); region.addProperty("width", 200); region.addProperty("height", 100); regionArgs.add("region", region); regionArgs.addProperty("plot", false);
        var corner = edt(() -> analysis.regionStats(regionArgs));
        expect(num(corner, "n") == 3 && "region".equals(corner.get("scopeUsed")), "explicit corner counts the same three existing objects: " + corner);
        @SuppressWarnings("unchecked") var cornerLargest = (List<Map<String, Object>>) corner.get("largest");
        expect(num(cornerLargest.getFirst(), "index") == 2, "scoped output keeps original object index");
        region.addProperty("width", 1); region.addProperty("height", 1);
        var empty = edt(() -> analysis.regionStats(regionArgs));
        expect(num(empty, "n") == 0 && ((Map<?, ?>) empty.get("area")).isEmpty(), "an empty corner reports zero, never whole-image fallback");
        imp.deleteRoi();
        try { edt(() -> analysis.regionStats(selectionArgs)); throw new AssertionError("missing selection accepted"); }
        catch (IllegalStateException expectedError) { expect(expectedError.getMessage().contains("Select an area"), "selection scope refuses missing selection"); }
        region.addProperty("x", -1);
        try { edt(() -> analysis.regionStats(regionArgs)); throw new AssertionError("negative region accepted"); }
        catch (IllegalArgumentException expectedError) { /* invalid bounds must never silently widen scope */ }
        System.out.println("PASS object_stats");
    }

    static void filterAndSplit() throws Exception {
        // 6 normal cells, 3 specks and one touching pair (two overlapping discs = one oversized object).
        var ip = new ByteProcessor(400, 200); var manager = ops.manager(); manager.reset();
        for (int i = 0; i < 6; i++) manager.addRoi(new OvalRoi(20 + i * 60, 20, 40, 40));
        for (int i = 0; i < 3; i++) manager.addRoi(new OvalRoi(30 + i * 60, 100, 6, 6));
        var pair = new ShapeRoi(new OvalRoi(220, 100, 44, 44)).or(new ShapeRoi(new OvalRoi(256, 100, 44, 44)));
        manager.addRoi(pair);
        var imp = new ImagePlus("synthetic-touching.tif", ip); activate(imp);
        var filtered = edt(() -> analysis.filterObjects(new JsonObject()));
        System.out.println("  filter: " + filtered);
        expect(((Number) filtered.get("removed")).intValue() == 3 && manager.getCount() == 7, "3 specks removed, 7 kept");
        var split = edt(() -> analysis.splitObjects(new JsonObject()));
        System.out.println("  split: " + split);
        expect(((Number) split.get("split")).intValue() == 1 && manager.getCount() == 8, "touching pair split into two: " + manager.getCount());
        expect(imp.getOverlay() != null && imp.getOverlay().size() == 8, "overlay follows the ROI Manager");
        var pointedSplit = new JsonObject(); pointedSplit.addProperty("x", 40); pointedSplit.addProperty("y", 40);
        var none = edt(() -> analysis.splitObjects(pointedSplit));
        expect("pointer".equals(none.get("scope")) && ((Number) none.get("split")).intValue() == 0, "a single round cell under the pointer is not split: " + none);
        var between = new JsonObject(); between.addProperty("x", 260); between.addProperty("y", 122);
        var merged = edt(() -> analysis.mergeObjects(between));
        System.out.println("  merge: " + merged);
        expect(((Number) merged.get("merged")).intValue() == 2 && manager.getCount() == 7, "the split pair merged back into one: " + manager.getCount());
        var remove = new JsonObject(); remove.addProperty("x", 40); remove.addProperty("y", 40);
        var removed = edt(() -> analysis.filterObjects(remove));
        expect(((Number) removed.get("removed")).intValue() == 1 && manager.getCount() == 6, "remove-at-pointer deletes only that cell: " + removed);
        System.out.println("PASS filter_objects (3 specks removed) and split_objects (touching pair -> 2), split/merge/remove at the pointer");
    }
    /** "Remove the tiny ones" when the specks outnumber the cells: 6 cells (r 20) and 20 specks (r 3); the median is a speck. */
    static void filterBimodal() throws Exception {
        var ip = new ByteProcessor(400, 300); var manager = ops.manager(); manager.reset();
        for (int i = 0; i < 6; i++) manager.addRoi(new OvalRoi(20 + i * 60, 20, 40, 40));
        for (int i = 0; i < 20; i++) manager.addRoi(new OvalRoi(20 + (i % 10) * 35, 120 + (i / 10) * 40, 6 + (i % 3), 6 + (i % 3)));
        var imp = new ImagePlus("synthetic-specks.tif", ip); activate(imp);
        var filtered = edt(() -> analysis.filterObjects(new JsonObject()));
        System.out.println("  bimodal filter: " + filtered);
        expect(((Number) filtered.get("removed")).intValue() == 20 && manager.getCount() == 6, "all 20 specks removed, 6 cells kept: " + filtered.get("removed"));
        expect(Boolean.TRUE.equals(filtered.get("bimodal")) && String.valueOf(filtered.get("method")).startsWith("log-area Otsu split") && num(filtered, "cutoff") > 60 && num(filtered, "cutoff") < 1200, "log-area split reported with its cutoff: " + filtered.get("method") + " " + filtered.get("cutoff"));
        // Unimodal: cells from 200 to 800 px (log-uniform) are all kept.
        manager.reset();
        for (int i = 0; i < 24; i++) { double area = 200 * Math.pow(4, i / 23.0); int d = (int) Math.round(2 * Math.sqrt(area / Math.PI)); manager.addRoi(new OvalRoi(10 + (i % 8) * 48, 10 + (i / 8) * 48, d, d)); }
        var uniform = edt(() -> analysis.filterObjects(new JsonObject()));
        System.out.println("  unimodal filter: removed " + uniform.get("removed") + ", method " + uniform.get("method") + ", cutoff " + uniform.get("cutoff"));
        expect(((Number) uniform.get("removed")).intValue() == 0 && Boolean.FALSE.equals(uniform.get("bimodal")), "a continuous size range is not split: " + uniform);
        System.out.println("PASS filter_objects robust tiny cutoff (bimodal specks removed, continuous range kept)");
    }
    /** filter/split work on the active image's ROI Manager entries only; another image's tagged entries are kept as they were. */
    static void perImageManager() throws Exception {
        var manager = ops.manager(); manager.reset();
        var other = new ImagePlus("other.tif", new ByteProcessor(100, 100));
        var foreign = new OvalRoi(5, 5, 3, 3); foreign.setName("other speck"); Operations.tag(foreign, other); manager.addRoi(foreign);
        var imp = new ImagePlus("mine.tif", new ByteProcessor(400, 200)); activate(imp);
        for (int i = 0; i < 6; i++) { var roi = new OvalRoi(20 + i * 60, 20, 40, 40); Operations.tag(roi, imp); manager.addRoi(roi); }
        for (int i = 0; i < 3; i++) { var roi = new OvalRoi(30 + i * 60, 100, 6, 6); Operations.tag(roi, imp); manager.addRoi(roi); }
        var pair = new ShapeRoi(new OvalRoi(220, 100, 44, 44)).or(new ShapeRoi(new OvalRoi(256, 100, 44, 44))); Operations.tag(pair, imp); manager.addRoi(pair);
        var filtered = edt(() -> analysis.filterObjects(new JsonObject()));
        expect(((Number) filtered.get("before")).intValue() == 10 && ((Number) filtered.get("removed")).intValue() == 3, "only this image's 10 entries filtered: " + filtered);
        expect(manager.getCount() == 8 && "other speck".equals(manager.getName(0)), "the other image's entry is untouched");
        var split = edt(() -> analysis.splitObjects(new JsonObject()));
        int mine = 0; for (var roi : manager.getRoisAsArray()) if (String.valueOf(imp.getID()).equals(roi.getProperty(Operations.ROI_IMAGE_PROPERTY))) mine++;
        expect(((Number) split.get("split")).intValue() == 1 && manager.getCount() == 9 && mine == 8 && "other speck".equals(manager.getName(0)), "split pieces tagged with this image, other entry kept: " + manager.getCount() + " / " + mine);
        expect(imp.getOverlay() != null && imp.getOverlay().size() == 8, "overlay holds this image's objects only");
        System.out.println("PASS per-image ROI Manager entries (filter and split leave another image's entry, pieces tagged)");
    }
    static void errors() throws Exception {
        ops.manager().reset();
        try { edt(() -> analysis.classifyIntensity(new JsonObject())); throw new AssertionError("empty manager accepted"); }
        catch (IllegalStateException expected) { expect(expected.getMessage().equals("Segment the cells first."), "empty manager message"); }
        var single = new ImagePlus("one-channel", new ByteProcessor(64, 64)); activate(single);
        try { edt(() -> analysis.colocalize(new JsonObject())); throw new AssertionError("single channel accepted"); }
        catch (IllegalArgumentException expected) { /* speakable */ }
        System.out.println("PASS speakable errors");
    }
    static void globalManagerIndices() throws Exception {
        var manager = ops.manager(); manager.reset();
        var other = new ImagePlus("foreign-indices", new ByteProcessor(200, 120));
        for (int i = 0; i < 2; i++) { var roi = new Roi(5 + 10 * i, 5, 5, 5); Operations.tag(roi, other); manager.addRoi(roi); }
        var imp = new ImagePlus("global-indices", new ByteProcessor(200, 120)); activate(imp);
        var normal = new OvalRoi(10, 10, 20, 20); Operations.tag(normal, imp); manager.addRoi(normal);
        var pair = new ShapeRoi(new OvalRoi(60, 40, 24, 24)).or(new ShapeRoi(new OvalRoi(79, 40, 24, 24))); Operations.tag(pair, imp); manager.addRoi(pair);
        var statsArgs = new JsonObject(); statsArgs.addProperty("plot", false);
        var stats = edt(() -> analysis.regionStats(statsArgs));
        @SuppressWarnings("unchecked") var largest = (List<Map<String, Object>>) stats.get("largest");
        int global = (int) num(largest.getFirst(), "index");
        expect(global == 3, "stats exposes global manager index3 after two foreign entries: " + largest);
        var target = new JsonObject(); var indices = new com.google.gson.JsonArray(); indices.add(global); target.add("indices", indices);
        var highlightTargets = edt(() -> Map.of("rois", ops.managerRois(target)));
        expect(((List<?>) highlightTargets.get("rois")).getFirst() == manager.getRoi(global), "stats index selects the exact manager ROI used by highlight");
        var why = new JsonObject(); why.addProperty("index", global);
        expect(num(edt(() -> analysis.explainObject(why, null)), "index") == global, "explain uses manager-global index");
        var split = edt(() -> analysis.splitObjects(target));
        expect(num(split, "split") == 1 && num(split, "after") == 3 && manager.getCount() == 5, "global index3 splits the intended pair and preserves foreign entries");
        indices.set(0, new com.google.gson.JsonPrimitive(0));
        try { edt(() -> analysis.splitObjects(target)); throw new AssertionError("foreign manager index accepted"); }
        catch (IllegalArgumentException expected) { expect(expected.getMessage().contains("active image"), "foreign index refused"); }
        indices.set(0, new com.google.gson.JsonPrimitive(3)); indices.add(4);
        expect(num(edt(() -> analysis.mergeObjects(target)), "merged") == 2 && manager.getCount() == 4, "global split-piece indices merge the same pair");
        var threshold = new JsonObject(); threshold.addProperty("threshold", 0);
        expect(edt(() -> analysis.classifyIntensity(threshold)).get("positiveIndices").equals(List.of(2, 3)), "classification indices also use global manager positions");
        System.out.println("PASS manager-global indices across stats, highlight target resolution, explanation, split, merge and classification");
    }
    static void centroidScope() throws Exception {
        var shapes = List.of(
            new ShapeRoi(new Roi(10, 10, 40, 40)).or(new ShapeRoi(new Roi(50, 45, 65, 5))),
            new ShapeRoi(new Roi(10, 10, 90, 70)).not(new ShapeRoi(new Roi(60, 20, 30, 50))));
        for (var shape : shapes) {
            var ip = new ByteProcessor(160, 120); ip.setValue(255); ip.fill(shape); ip.setThreshold(255, 255, ij.process.ImageProcessor.NO_LUT_UPDATE);
            var truth = ij.process.ImageStatistics.getStatistics(ip, ij.measure.Measurements.CENTROID | ij.measure.Measurements.LIMIT, null);
            double contourX = shape.getContourCentroid()[0], boundary = (truth.xCentroid + contourX) / 2;
            expect(Math.abs(truth.xCentroid - contourX) > 2, "asymmetric/holed fixture separates filled-mask and perimeter centroids");
            var imp = new ImagePlus("centroid-scope", ip); imp.getCalibration().pixelWidth = 0.001; imp.getCalibration().pixelHeight = 0.001; imp.getCalibration().setUnit("mm"); activate(imp);
            var manager = ops.manager(); manager.reset(); Operations.tag(shape, imp); manager.addRoi(shape);
            var args = new JsonObject(); args.addProperty("plot", false); var region = new JsonObject();
            region.addProperty("x", truth.xCentroid < boundary ? 0 : boundary); region.addProperty("y", 0); region.addProperty("width", truth.xCentroid < boundary ? boundary : imp.getWidth() - boundary); region.addProperty("height", imp.getHeight()); args.add("region", region);
            expect(num(edt(() -> analysis.regionStats(args)), "n") == 1, "regional count uses filled-pixel centroid despite calibration and holes");
            int edge = (int) Math.floor(boundary); imp.setRoi(truth.xCentroid < boundary ? new Roi(0, 0, edge, imp.getHeight()) : new Roi(edge, 0, imp.getWidth() - edge, imp.getHeight()));
            args.remove("region"); args.addProperty("scope", "selection");
            expect(num(edt(() -> analysis.regionStats(args)), "n") == 1, "selection count agrees with mask-based segmentation scope");
        }
        System.out.println("PASS filled-mask centroid scope on asymmetric and holed objects, calibrated in mm");
    }
    /** After the researcher removes every current-image object, "how many are left?" must answer zero without starting over. */
    static void emptyExistingObjects() throws Exception {
        ImagePlus imp = (ImagePlus) edt(() -> Map.of("image", ops.active())).get("image"); var manager = ops.manager();
        var filter = new JsonObject(); filter.addProperty("minArea", 1e12);
        var cleared = edt(() -> analysis.filterObjects(filter));
        expect(num(cleared, "kept") == 0 && manager.getCount() == 1, "all current-image objects removed; other-image object retained");
        byte[] before = ((byte[]) imp.getProcessor().getPixels()).clone(); int rows = Operations.results().size();
        var args = new JsonObject(); args.addProperty("plot", false);
        var zero = edt(() -> analysis.regionStats(args));
        expect(num(zero, "n") == 0 && num(zero, "totalForImage") == 0 && ((Map<?, ?>) zero.get("area")).isEmpty() && ((Map<?, ?>) zero.get("mean")).isEmpty(), "empty active-image population reports zero and absent measurements: " + zero);
        expect(manager.getCount() == 1 && Operations.results().size() == rows && java.util.Arrays.equals(before, (byte[]) imp.getProcessor().getPixels()), "reading zero does not resegment or mutate the existing analysis");
        manager.reset();
        expect(num(edt(() -> analysis.regionStats(args)), "n") == 0, "an entirely empty ROI Manager also reports zero");
        imp.deleteRoi(); args.addProperty("scope", "selection");
        try { edt(() -> analysis.regionStats(args)); throw new AssertionError("empty population accepted absent selection"); }
        catch (IllegalStateException expected) { expect(expected.getMessage().contains("Select an area"), "selection remains mandatory for explicit scope even with zero objects"); }
        System.out.println("PASS object_stats after all objects removed (zero, no resegmentation, image ownership, explicit selection validation)");
    }

    static void samples() throws Exception {
        Path root = Path.of(System.getProperty("luma.root", ".")).resolve(".local/data/samples");
        for (var name : List.of("he-breast-idc", "ki67-cervical-scc")) {
            var dir = root.resolve(name); if (!Files.isDirectory(dir)) continue;
            try (var files = Files.list(dir)) {
                var file = files.filter(p -> p.toString().matches(".*\\.(jpg|jpeg|png|tif|tiff)$")).findFirst(); if (file.isEmpty()) continue;
                var imp = IJ.openImage(file.get().toString()); if (imp == null) continue;
                ops.manager().reset(); activate(imp);
                long start = System.nanoTime();
                var result = edt(() -> analysis.tumorRegion(new JsonObject()));
                System.out.println("SAMPLE tumor_region " + name + " defaults (" + imp.getWidth() + "x" + imp.getHeight() + ", " + (System.nanoTime() - start) / 1_000_000 + " ms): " + tumourLine(result));
                expect("tissue".equals(result.get("areaFractionOf")) && num(result, "areaFraction") > 0.1, name + ": a substantial tumour fraction of the tissue");
                var rerun = edt(() -> analysis.tumorRegion(new JsonObject()));
                expect(num(rerun, "area") == num(result, "area"), name + ": rerun (previous outline selected) is identical: " + rerun.get("area") + " vs " + result.get("area"));
                // The QA battery's arguments (Q19r: he, Otsu, sigma 2, minAreaFraction 0.05, keepLargest 1) gave a 0.69% sliver before.
                var qa = new JsonObject(); qa.addProperty("stain", "he"); qa.addProperty("method", "Otsu"); qa.addProperty("sigma", 2); qa.addProperty("minAreaFraction", 0.05); qa.addProperty("keepLargest", 1);
                ops.manager().reset(); imp.deleteRoi();
                var qaResult = edt(() -> analysis.tumorRegion(qa));
                System.out.println("SAMPLE tumor_region " + name + " QA arguments: " + tumourLine(qaResult) + ", adjusted " + qaResult.get("adjusted"));
                System.out.println("SAMPLE tumor_region " + name + " deterministic: rerun area " + rerun.get("area") + " = " + result.get("area"));
            }
        }
        for (var name : List.of("hela-cells", "fluorescent-cells")) {
            var dir = root.resolve(name); if (!Files.isDirectory(dir)) continue;
            try (var files = Files.list(dir)) {
                var file = files.filter(p -> p.toString().endsWith(".tif")).findFirst(); if (file.isEmpty()) continue;
                var imp = IJ.openImage(file.get().toString()); if (imp == null) continue;
                activate(imp);
                if (imp.getNChannels() < 2 && imp.getType() != ImagePlus.COLOR_RGB) continue;
                var result = edt(() -> analysis.colocalize(new JsonObject()));
                System.out.println("SAMPLE colocalize " + name + " ch1 vs ch2 (" + imp.getNChannels() + " channels, " + imp.getBitDepth() + "-bit): pearson " + fmt(num(result, "pearson")) + ", manders " + result.get("manders") + ", overlap " + fmt(num(result, "overlap")) + ", method " + result.get("method") + ", " + result.get("interpretation"));
            }
        }
    }

    static String tumourLine(Map<String, Object> r) {
        return "regions " + r.get("regions") + ", areaFraction " + fmt(num(r, "areaFraction")) + " of the " + r.get("areaFractionOf") + " (" + fmt(num(r, "areaFractionOfImage")) + " of the image; tissue " + fmt(num(r, "tissueFractionOfImage")) + " of the image)"
            + ", circularity " + fmt(num(r, "circularity")) + ", solidity " + fmt(num(r, "solidity")) + ", stain " + r.get("stain") + ", method " + r.get("method") + ", sigma " + r.get("sigma");
    }
    // ----- helpers
    static void activate(ImagePlus imp) throws Exception { java.awt.EventQueue.invokeAndWait(() -> WindowManager.setTempCurrentImage(imp)); }
    static Map<String, Object> edt(java.util.concurrent.Callable<Map<String, Object>> body) throws Exception {
        var result = new AtomicReference<Map<String, Object>>(); var failure = new AtomicReference<Exception>();
        java.awt.EventQueue.invokeAndWait(() -> { try { result.set(body.call()); } catch (Exception error) { failure.set(error); } });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    static double num(Map<String, Object> map, String key) { return ((Number) map.get(key)).doubleValue(); }
    static String fmt(double v) { return String.format(java.util.Locale.US, "%.3f", v); }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static void noise(ByteProcessor ip, Random random, double sd) { byte[] p = (byte[]) ip.getPixels(); for (int i = 0; i < p.length; i++) p[i] = (byte) clamp((p[i] & 255) + (int) (random.nextGaussian() * sd)); }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
