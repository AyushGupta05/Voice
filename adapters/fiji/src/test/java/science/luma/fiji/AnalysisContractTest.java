package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.OvalRoi;
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
            tumorGray(); tumorRgb(); colocalisation(); classification(); objectStats(); errors(); samples();
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.out.println("Analysis contract (tumor_region, colocalize, classify_intensity, object_stats): passed");
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
        System.out.println("PASS tumor_region on an RGB H&E-like synthetic image");
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
        System.out.println("PASS object_stats");
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
                System.out.println("SAMPLE tumor_region " + name + " (" + imp.getWidth() + "x" + imp.getHeight() + ", " + (System.nanoTime() - start) / 1_000_000 + " ms): regions " + result.get("regions") + ", areaFraction " + fmt(num(result, "areaFraction")) + ", circularity " + fmt(num(result, "circularity")) + ", solidity " + fmt(num(result, "solidity")) + ", stain " + result.get("stain") + ", method " + result.get("method"));
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
