package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.plugin.filter.EDM;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.util.*;

/**
 * The researcher's analysis as structured, validated parameters rather than a transcript: segmentation settings plus every
 * generalisable correction ("remove anything smaller than 20 µm²", "these two should be separate"). Corrections change these
 * parameters, so "apply this to the folder" replays the corrected analysis, not the first attempt. Hand edits that cannot
 * generalise (merging or deleting one particular object) are listed as not replayed instead of being silently dropped.
 */
public final class Pipeline {
    /** Tolerances tried, in order, when the researcher asserts an object is two cells: the first is ImageJ's own watershed. */
    static final double[] ASSERTED_TOLERANCES = {0.5, 0.3, 0.15, 0.05};

    private String image;
    private Integer channel;
    private String method = "Otsu";
    private boolean dark = true, watershed = true, excludeArtifacts = true;
    private double sigma = 1, minArea = 0, maxArea = Double.POSITIVE_INFINITY, minCircularity = 0;
    private double splitFactor = 0, splitTolerance = 0.5;
    private String unit = "pixel";
    private int lastCount = -1;
    private final List<String> corrections = new ArrayList<>();
    private final List<String> notReplayed = new ArrayList<>();

    boolean ready() { return image != null; }

    /** Follows the operations that define or correct a segmentation; everything else leaves the analysis unchanged. */
    synchronized void observe(String operation, JsonObject args, Map<String, Object> result, ImagePlus imp) {
        if (result == null) return;
        switch (operation) {
            case "segment_cells" -> {
                image = imp == null ? "image" : imp.getTitle();
                channel = result.get("channel") instanceof Number n ? n.intValue() : null;
                if (result.get("threshold") instanceof Map<?, ?> t) {
                    if (t.get("method") instanceof String m) method = m;
                    if (t.get("dark") instanceof Boolean d) dark = d;
                }
                watershed = Operations.bool(args, "watershed", true); excludeArtifacts = !Operations.bool(args, "keepArtifacts", false);
                sigma = Operations.bool(args, "gaussian", true) ? Operations.number(args, "sigma", 1) : 0;
                minArea = Operations.number(args, "minSize", 0); maxArea = Double.POSITIVE_INFINITY; minCircularity = 0;
                splitFactor = 0; splitTolerance = 0.5;
                unit = imp == null ? "pixel" : imp.getCalibration().getUnit();
                corrections.clear(); notReplayed.clear();
                count(result.get("count"));
            }
            case "filter_objects" -> {
                if (!ready()) return;
                if ("pointer".equals(Operations.string(args, "at", "")) || args.has("x") && args.has("y")) { notReplayed.add("removed " + result.get("removed") + " object(s) by hand"); count(result.get("kept")); return; }
                if (result.get("minArea") instanceof Number n && n.doubleValue() > minArea) minArea = n.doubleValue();
                if (result.get("maxArea") instanceof Number n && n.doubleValue() < maxArea) maxArea = n.doubleValue();
                if (result.get("minCircularity") instanceof Number n && n.doubleValue() > minCircularity) minCircularity = n.doubleValue();
                corrections.add("keep objects of " + area(minArea) + (Double.isInfinite(maxArea) ? " or more" : " to " + area(maxArea)) + (minCircularity > 0 ? ", circularity ≥ " + Operations.plain(minCircularity) : ""));
                count(result.get("kept"));
            }
            case "split_objects" -> {
                if (!ready() || !(result.get("split") instanceof Number s) || s.intValue() == 0) return;
                // A split the researcher pointed at generalises to "split objects at least this oversized, as firmly as here".
                double ratio = result.get("splitRatio") instanceof Number r ? r.doubleValue() : Operations.number(args, "factor", 1.5);
                double factor = Math.max(1.05, ratio * 0.95), tolerance = result.get("tolerance") instanceof Number t ? t.doubleValue() : 0.5;
                splitFactor = splitFactor == 0 ? factor : Math.min(splitFactor, factor);
                splitTolerance = Math.min(splitTolerance, tolerance);
                corrections.add("split objects larger than " + Operations.plain(round(splitFactor)) + "× the median area (watershed tolerance " + Operations.plain(splitTolerance) + ")");
                count(result.get("after"));
            }
            case "merge_objects" -> { if (ready()) { notReplayed.add("merged " + result.get("merged") + " objects into one by hand"); count(result.get("after")); } }
            default -> { }
        }
    }
    private void count(Object value) { if (value instanceof Number n) lastCount = n.intValue(); }
    private String area(double value) { return Operations.plain(round(value)) + " " + (unit == null || unit.isBlank() ? "pixel" : unit) + "²"; }
    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    /** The analysis as the researcher would read it, plus the exact parameters and macro that replay it. */
    synchronized Map<String, Object> describe() {
        if (!ready()) throw new IllegalStateException("Segment the cells first; there is no analysis to reuse yet.");
        var steps = new ArrayList<String>();
        steps.add("nuclei/cells from channel " + (channel == null ? "current" : channel) + ", Gaussian sigma " + Operations.plain(sigma) + ", " + method + " threshold" + (dark ? " (bright objects)" : "") + ", fill holes" + (watershed ? ", watershed" : ""));
        if (excludeArtifacts) steps.add("drop burned-in text and specks under 0.15× the median size");
        steps.addAll(corrections);
        steps.add("measure area, intensity and shape on the original pixels");
        var result = new LinkedHashMap<String, Object>();
        result.put("source", image); result.put("steps", steps); result.put("params", params()); result.put("notReplayed", List.copyOf(notReplayed));
        result.put("countOnSource", lastCount); result.put("macro", macro());
        result.put("note", "Corrections are replayed as parameters; hand edits listed in notReplayed apply only to " + image + ".");
        return result;
    }
    LinkedHashMap<String, Object> params() {
        var p = new LinkedHashMap<String, Object>();
        p.put("channel", channel == null ? 1 : channel); p.put("method", method); p.put("dark", dark); p.put("sigma", sigma); p.put("watershed", watershed);
        p.put("minArea", round(minArea)); p.put("maxArea", Double.isInfinite(maxArea) ? "Infinity" : String.valueOf(round(maxArea))); p.put("minCircularity", round(minCircularity));
        p.put("splitFactor", round(splitFactor)); p.put("splitTolerance", splitTolerance); p.put("excludeArtifacts", excludeArtifacts);
        return p;
    }
    /** ImageJ macro that reproduces the analysis; the split and the post-split filter call this class so they behave exactly as live. */
    String macro() {
        return String.join("\n",
            "// Reproduces the analysis Penny ran on " + image + " with the researcher's corrections.",
            "original = getTitle();",
            "roiManager(\"reset\"); run(\"Clear Results\");",
            "if (nSlices > 1 || is(\"composite\")) run(\"Duplicate...\", \"title=penny-mask duplicate channels=\" + channel); else run(\"Duplicate...\", \"title=penny-mask\");",
            "if (bitDepth() != 8) run(\"8-bit\");",
            "if (sigma > 0) run(\"Gaussian Blur...\", \"sigma=\" + sigma);",
            "if (dark) setAutoThreshold(method + \" dark\"); else setAutoThreshold(method);",
            "setOption(\"BlackBackground\", true);",
            "run(\"Convert to Mask\");",
            "run(\"Fill Holes\");",
            "if (watershed) run(\"Watershed\");",
            "run(\"Analyze Particles...\", \"size=0-Infinity show=Nothing add\");",
            "close(\"penny-mask\");",
            "selectImage(original);",
            "call(\"science.luma.fiji.Pipeline.refine\", \"\" + splitFactor, \"\" + splitTolerance, \"\" + minArea, \"\" + maxArea, \"\" + minCircularity, \"\" + excludeArtifacts, \"\" + channel);",
            "run(\"Set Measurements...\", \"area mean standard min integrated median perimeter shape bounding redirect=None decimal=3\");",
            "if (roiManager(\"count\") > 0) roiManager(\"Measure\");",
            "sum = 0; for (i = 0; i < nResults; i++) sum += getResult(\"Area\", i);",
            "print(\"RESULT count=\" + roiManager(\"count\") + \" meanArea=\" + d2s(sum / maxOf(1, nResults), 3));");
    }

    // =====================================================================================================
    // Shared object corrections: used live by Analysis and from replayed macros through call(...)
    // =====================================================================================================

    /** Splits one object along its distance-map watershed; returns the pieces, or an empty list when it stays whole. */
    static List<Roi> splitRoi(Roi roi, double tolerance, int minPart) {
        var b = roi.getBounds(); int w = b.width + 2, h = b.height + 2;
        var mask = new ByteProcessor(w, h);
        var local = (Roi) roi.clone(); local.setLocation(local.getXBase() - b.x + 1, local.getYBase() - b.y + 1);
        mask.setColor(255); mask.fill(local);
        // One EDM and MaximumFinder per object: MaximumFinder caches direction offsets for the first width it sees.
        FloatProcessor edm = new EDM().makeFloatEDM(mask, 0, false);
        ByteProcessor segmented = new MaximumFinder().findMaxima(edm, tolerance, ImageProcessor.NO_THRESHOLD, MaximumFinder.SEGMENTED, false, true);
        if (segmented == null) return List.of();
        byte[] m = (byte[]) mask.getPixels(), s = (byte[]) segmented.getPixels();
        for (int i = 0; i < m.length; i++) if (m[i] == 0) s[i] = 0;
        int[] labels = new int[w * h]; var parts = Analysis.label(s, w, h, labels);
        var pieces = new ArrayList<Roi>();
        for (int k = 0; k < parts.size(); k++) {
            if (parts.get(k) < minPart) continue;
            var part = new ByteProcessor(w, h); byte[] pp = (byte[]) part.getPixels();
            for (int j = 0; j < labels.length; j++) if (labels[j] == k + 1) pp[j] = (byte) 255;
            Roi piece = Analysis.maskToRoi(part); if (piece == null) continue;
            piece.setLocation(piece.getXBase() + b.x - 1, piece.getYBase() + b.y - 1);
            pieces.add(piece);
        }
        return pieces.size() >= 2 ? pieces : List.of();
    }
    /** Pieces at the gentlest tolerance that separates the object (for objects the researcher said are several cells). */
    static List<Roi> splitAsserted(Roi roi, int minPart, double[] usedTolerance) {
        for (double tolerance : ASSERTED_TOLERANCES) { var pieces = splitRoi(roi, tolerance, minPart); if (!pieces.isEmpty()) { usedTolerance[0] = tolerance; return pieces; } }
        return List.of();
    }

    /** The same rule as Operations.excludeArtifacts: flat saturated objects are burned-in text; with ≥ 5 objects, specks under 0.15× the median area. */
    static Roi[] withoutArtifacts(ImagePlus imp, Roi[] rois, int channel) {
        ImageProcessor ip = imp.getStackSize() > 1 ? imp.getStack().getProcessor(imp.getStackIndex(Math.min(Math.max(1, channel), Math.max(1, imp.getNChannels())), imp.getZ(), imp.getT())) : imp.getProcessor();
        double ceiling = imp.getBitDepth() == 8 || imp.getBitDepth() == 24 ? 255 : imp.getBitDepth() == 16 ? 65535 : Double.NaN;
        var global = ip.getStats(); double globalMax = global.max, range = Math.max(1, global.max - global.min);
        double[] px = new double[rois.length]; for (int i = 0; i < rois.length; i++) px[i] = rois[i].getStatistics().pixelCount;
        double[] sorted = px.clone(); Arrays.sort(sorted); double median = sorted[sorted.length / 2];
        var keep = new ArrayList<Roi>();
        for (int i = 0; i < rois.length; i++) {
            ip.setRoi(rois[i]); var stats = ij.process.ImageStatistics.getStatistics(ip, ij.measure.Measurements.STD_DEV | ij.measure.Measurements.MIN_MAX, null); ip.resetRoi();
            boolean flat = stats.stdDev <= 0.01 * range;
            boolean saturated = !Double.isNaN(ceiling) && stats.max >= ceiling - 1 || imp.getBitDepth() == 16 && stats.max >= 4095 && stats.max >= globalMax;
            boolean tiny = rois.length >= 5 && px[i] < 0.15 * median;
            if (!(flat && saturated) && !tiny) keep.add(rois[i]);
        }
        return keep.toArray(new Roi[0]);
    }
    /** Macro entry point: split oversized objects, then apply the area/circularity filter, on the macro's ROI Manager. */
    public static String refine(String factor, String tolerance, String minArea, String maxArea, String minCircularity) { return refine(factor, tolerance, minArea, maxArea, minCircularity, "false", "1"); }
    /** As live: drop artifacts (as segment_cells does), then split oversized objects, then apply the area/circularity filter. */
    public static String refine(String factor, String tolerance, String minArea, String maxArea, String minCircularity, String artifacts, String channel) {
        ImagePlus imp = WindowManager.getCurrentImage();
        RoiManager rm = RoiManager.getInstance();
        if (rm == null && Interpreter.isBatchMode()) rm = Interpreter.getBatchModeRoiManager();
        if (rm == null || imp == null) return "0";
        Roi[] rois = rm.getRoisAsArray(); if (rois.length == 0) return "0";
        if ("true".equals(artifacts) || "1".equals(artifacts)) rois = withoutArtifacts(imp, rois, (int) Math.round(Double.parseDouble(channel)));
        double f = Double.parseDouble(factor), tol = Double.parseDouble(tolerance), lo = Double.parseDouble(minArea), circ = Double.parseDouble(minCircularity);
        double hi = "Infinity".equals(maxArea) ? Double.POSITIVE_INFINITY : Double.parseDouble(maxArea);
        if (rois.length == 0) { rm.reset(); return "0"; }
        double[] px = new double[rois.length]; for (int i = 0; i < rois.length; i++) px[i] = rois[i].getStatistics().pixelCount;
        double[] sorted = px.clone(); Arrays.sort(sorted); double median = sorted[sorted.length / 2];
        int minPart = Math.max(4, (int) Math.round(median * 0.15));
        var out = new ArrayList<Roi>();
        for (int i = 0; i < rois.length; i++) {
            List<Roi> pieces = f > 0 && px[i] > f * median ? splitRoi(rois[i], tol, minPart) : List.of();
            if (pieces.isEmpty()) out.add(rois[i]); else out.addAll(pieces);
        }
        Calibration cal = imp.getCalibration(); double pxArea = cal.pixelWidth * cal.pixelHeight;
        rm.reset(); int n = 0;
        for (Roi roi : out) {
            double area = roi.getStatistics().pixelCount * pxArea;
            double c = roi.getLength() > 0 ? Math.min(1, 4 * Math.PI * roi.getStatistics().pixelCount / (roi.getLength() * roi.getLength())) : 0;
            if (area < lo || area > hi || c < circ) continue;
            roi.setName(String.format(Locale.ROOT, "Cell %d", ++n)); rm.addRoi(roi);
        }
        return String.valueOf(n);
    }
}
