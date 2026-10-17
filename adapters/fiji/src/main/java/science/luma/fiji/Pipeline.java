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

    /** Private checkpoint copy: a later correction must not mutate the analysis restored by undo. */
    synchronized Pipeline copy() {
        var copy = new Pipeline();
        copy.image = image; copy.channel = channel; copy.method = method; copy.dark = dark; copy.watershed = watershed; copy.excludeArtifacts = excludeArtifacts;
        copy.sigma = sigma; copy.minArea = minArea; copy.maxArea = maxArea; copy.minCircularity = minCircularity;
        copy.splitFactor = splitFactor; copy.splitTolerance = splitTolerance; copy.unit = unit; copy.lastCount = lastCount;
        copy.corrections.addAll(corrections); copy.notReplayed.addAll(notReplayed);
        return copy;
    }

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
                minArea = Operations.number(args, "minSize", 0); maxArea = Operations.number(args, "maxSize", Double.POSITIVE_INFINITY); minCircularity = Operations.number(args, "minCircularity", 0);
                splitFactor = 0; splitTolerance = 0.5;
                unit = imp == null ? "pixel" : imp.getCalibration().getUnit();
                corrections.clear(); notReplayed.clear();
                if ("selection".equals(result.get("scope"))) notReplayed.add("the source-image selection; folder replay analyses the full field of each image");
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
    private String area(double value) { return Double.toString(value) + " " + (unit == null || unit.isBlank() ? "pixel" : unit) + "²"; }
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
        p.put("hematoxylin", channel == null);
        p.put("minArea", minArea); p.put("maxArea", Double.isInfinite(maxArea) ? "Infinity" : Double.toString(maxArea)); p.put("minCircularity", minCircularity);
        p.put("splitFactor", splitFactor); p.put("splitTolerance", splitTolerance); p.put("excludeArtifacts", excludeArtifacts);
        return p;
    }
    /** ImageJ macro that reproduces the analysis; the split and the post-split filter call this class so they behave exactly as live. */
    String macro() {
        return String.join("\n",
            "// Reproduces the analysis Penny ran on " + image + " with the researcher's corrections.",
            "original = getImageID(); Stack.getPosition(sourceC, sourceZ, sourceT);",
            "roiManager(\"reset\"); run(\"Clear Results\");",
            "// Brightfield RGB (H&E, IHC): nuclei from the hematoxylin optical density, as live segment_cells does.",
            "call(\"science.luma.fiji.Pipeline.prepareMask\", \"\" + channel, \"\" + hematoxylin); selectImage(\"penny-mask\"); if (hematoxylin) dark = true;",
            "if (sigma > 0) run(\"Gaussian Blur...\", \"sigma=\" + sigma);",
            "if (dark) setAutoThreshold(method + \" dark\"); else setAutoThreshold(method);",
            "setOption(\"BlackBackground\", true);",
            "run(\"Convert to Mask\");",
            "run(\"Fill Holes\");",
            "if (watershed) run(\"Watershed\");",
            "run(\"Analyze Particles...\", \"size=0-Infinity show=Nothing add\");",
            "close(\"penny-mask\");",
            "selectImage(original);",
            "call(\"science.luma.fiji.Pipeline.refine\", " + exactParameter("splitFactor", splitFactor) + ", " + exactParameter("splitTolerance", splitTolerance) + ", " + exactParameter("minArea", minArea) + ", " + exactParameter("maxArea", maxArea) + ", " + exactParameter("minCircularity", minCircularity) + ", \"\" + excludeArtifacts, \"\" + channel);",
            "Stack.setChannel(channel);",
            "run(\"Set Measurements...\", \"area mean standard min integrated median perimeter shape bounding redirect=None decimal=3\");",
            "if (roiManager(\"count\") > 0) roiManager(\"Measure\");",
            "sum = 0; for (i = 0; i < nResults; i++) sum += getResult(\"Area\", i);",
            "Stack.setPosition(sourceC, sourceZ, sourceT);",
            "print(\"RESULT count=\" + roiManager(\"count\") + \" meanArea=\" + d2s(sum / maxOf(1, nResults), 3));");
    }
    /** Pass the exact parameter text to Java; IJM numeric string concatenation can round tiny calibrated areas. */
    private static String exactParameter(String name, double value) { return "pennyParam(\"" + name + "\", \"" + Double.toString(value) + "\")"; }

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
        boolean[] text = new boolean[rois.length]; var remaining = new ArrayList<Double>();
        for (int i = 0; i < rois.length; i++) {
            ip.setRoi(rois[i]); var stats = ij.process.ImageStatistics.getStatistics(ip, ij.measure.Measurements.STD_DEV | ij.measure.Measurements.MIN_MAX, null); ip.resetRoi();
            boolean flat = stats.stdDev <= 0.01 * range;
            boolean saturated = !Double.isNaN(ceiling) && stats.max >= ceiling - 1 || imp.getBitDepth() == 16 && stats.max >= 4095 && stats.max >= globalMax;
            text[i] = flat && saturated || whiteAnnotation(imp, rois[i]);
            if (!text[i]) remaining.add(px[i]);
        }
        Collections.sort(remaining); double median = remaining.isEmpty() ? 0 : remaining.get(remaining.size() / 2);
        var keep = new ArrayList<Roi>();
        for (int i = 0; i < rois.length; i++) if (!text[i] && !(remaining.size() >= 5 && px[i] < 0.15 * median)) keep.add(rois[i]);
        return keep.toArray(new Roi[0]);
    }
    /**
     * Only this image's own outlines: ImageJ's ROI Manager "Show All" mode (left on by macros and recipes) paints every
     * image's manager entries, numbered, onto whichever image is in front, which buries the result under other analyses.
     */
    static void ownOverlayOnly(ImagePlus imp, RoiManager manager) {
        try { if (manager != null) manager.runCommand("show none"); } catch (RuntimeException ignored) { /* manager without a window */ }
        if (imp != null && imp.getCanvas() != null) { imp.getCanvas().setShowAllList(null); imp.setHideOverlay(false); imp.updateAndDraw(); }
    }
    /**
     * Shows a result window (plot, table, macro) next to the image instead of on top of it, and keeps the image the
     * current one: a plot is itself an ImageWindow and would otherwise become the target of the next request.
     */
    static void placeBeside(ImagePlus imp, java.awt.Window window) {
        var image = imp == null ? null : imp.getWindow();
        if (window != null && image != null) {
            var screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            var b = image.getBounds(); int w = window.getWidth(), h = window.getHeight(), gap = 10;
            int clampY = Math.max(screen.y, Math.min(b.y, screen.y + screen.height - h)), clampX = Math.max(screen.x, Math.min(b.x, screen.x + screen.width - w));
            // Beside the image if there is room (right, left), else below or above it; never on top of the cells.
            java.awt.Point[] candidates = { new java.awt.Point(b.x + b.width + gap, clampY), new java.awt.Point(b.x - w - gap, clampY),
                new java.awt.Point(clampX, b.y + b.height + gap), new java.awt.Point(clampX, b.y - h - gap) };
            java.awt.Point chosen = null;
            for (var c : candidates) if (screen.contains(new java.awt.Rectangle(c.x, c.y, w, h))) { chosen = c; break; }
            if (chosen == null) {
                // No full-size free side: shrink into the widest free column beside the image (charts and tables reflow).
                int right = screen.x + screen.width - (b.x + b.width + gap), left = b.x - gap - screen.x;
                int room = Math.max(right, left);
                if (room >= 260) {
                    int width = Math.min(w, room), height = Math.max(160, h * width / Math.max(1, w));
                    window.setSize(width, height);
                    chosen = new java.awt.Point(right >= left ? b.x + b.width + gap : b.x - gap - width, Math.max(screen.y, Math.min(b.y, screen.y + screen.height - height)));
                }
            }
            if (chosen == null) {
                // No free side: the screen corner farthest from the image, overlapping it as little as possible.
                java.awt.Point[] corners = { new java.awt.Point(screen.x + screen.width - w, screen.y + screen.height - h), new java.awt.Point(screen.x, screen.y + screen.height - h),
                    new java.awt.Point(screen.x + screen.width - w, screen.y), new java.awt.Point(screen.x, screen.y) };
                double least = Double.MAX_VALUE;
                for (var c : corners) { var overlap = new java.awt.Rectangle(c.x, c.y, w, h).intersection(b); double a = overlap.isEmpty() ? 0 : (double) overlap.width * overlap.height; if (a < least) { least = a; chosen = c; } }
            }
            window.setLocation(chosen);
        }
        if (image != null) {
            // After the new window's own activation event has run, hand "current image" back to the researcher's image.
            java.awt.EventQueue.invokeLater(() -> java.awt.EventQueue.invokeLater(() -> WindowManager.setCurrentWindow(image)));
        }
    }
    /** Prefer below the batch table, then above it; keep the editor's complete bounds in the usable screen area. */
    static java.awt.Rectangle batchEditorBounds(java.awt.Rectangle table, int width, int height, java.awt.Rectangle screen) {
        int w = Math.min(width, screen.width), h = Math.min(height, screen.height);
        int below = table.y + table.height + 8, above = table.y - h - 8;
        int y = below + h <= screen.y + screen.height ? below : above >= screen.y ? above : below;
        int x = Math.max(screen.x, Math.min(table.x, screen.x + screen.width - w));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - h));
        return new java.awt.Rectangle(x, y, w, h);
    }
    /**
     * Keeps the cells in view: ImageJ's side windows (ROI Manager, Results, Log, Summary, tables, plots) that cover the image
     * window move to a free spot beside it, stacked so they don't cover each other; with no room left they are minimised.
     */
    static void tidyAround(ImagePlus imp) {
        var image = imp == null ? null : imp.getWindow();
        if (image == null || !image.isShowing()) return;
        var screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        var taken = new ArrayList<java.awt.Rectangle>(); taken.add(image.getBounds());
        var movers = new ArrayList<java.awt.Window>();
        for (var window : java.awt.Window.getWindows()) {
            if (window == image || !window.isShowing() || window instanceof java.awt.Dialog && ((java.awt.Dialog) window).isModal()) continue;
            if (window instanceof ij.gui.ImageWindow && !(window instanceof ij.gui.PlotWindow)) continue; // other images stay put
            if (window instanceof ij.ImageJ) continue; // the toolbar
            String title = window instanceof java.awt.Frame f ? f.getTitle() : "";
            boolean side = window instanceof RoiManager || window instanceof ij.text.TextWindow || window instanceof ij.gui.PlotWindow
                || window instanceof javax.swing.JFrame && title.startsWith("Size distribution") || title.equals("Console") || title.equals("Log");
            if (!side) continue;
            if (window.getBounds().intersects(image.getBounds())) movers.add(window); else taken.add(window.getBounds());
        }
        for (var window : movers) {
            var b = image.getBounds(); int w = window.getWidth(), h = window.getHeight(), gap = 10;
            java.awt.Rectangle spot = null;
            // Columns right and left of the image, top to bottom, skipping places already used.
            outer:
            for (int x : new int[]{b.x + b.width + gap, b.x - w - gap}) {
                for (int y = Math.max(screen.y, b.y); y + h <= screen.y + screen.height; y += 24) {
                    var candidate = new java.awt.Rectangle(x, y, w, h);
                    if (!screen.contains(candidate)) break;
                    boolean free = true; for (var t : taken) if (t.intersects(candidate)) { free = false; break; }
                    if (free) { spot = candidate; break outer; }
                }
            }
            if (spot != null) { window.setLocation(spot.x, spot.y); taken.add(spot); }
            else if (window instanceof java.awt.Frame frame) frame.setState(java.awt.Frame.ICONIFIED);
        }
        if (!movers.isEmpty()) image.toFront();
    }
    /** After "apply this to the folder": a per-image summary table and the generated macro, side by side with the image. */
    @SuppressWarnings("unchecked")
    static void showBatch(ImagePlus imp, Map<String, Object> applied) {
        if (!(applied.get("run") instanceof Map<?, ?> run)) return;
        var table = new ij.measure.ResultsTable();
        if (run.get("perImage") instanceof List<?> images) for (var entry : images) {
            if (!(entry instanceof Map<?, ?> image)) continue;
            table.addRow(); table.addValue("Image", String.valueOf(image.get("image")));
            table.addValue("Objects", image.get("resultsRows") instanceof Number n ? n.doubleValue() : Double.NaN);
            if (image.get("summary") instanceof Map<?, ?> summary && summary.get("Area") instanceof Number area) table.addValue("Mean area", area.doubleValue());
            table.addValue("OK", Boolean.TRUE.equals(image.get("ok")) ? "yes" : "no");
        }
        if (table.size() > 0) {
            table.show("Penny batch results");
            var window = WindowManager.getWindow("Penny batch results");
            if (window != null) placeBeside(imp, window);
        }
        if (applied.get("workflow") instanceof Map<?, ?> workflow && workflow.get("path") instanceof String path) {
            try {
                var editor = new ij.plugin.frame.Editor(18, 70, 0, ij.plugin.frame.Editor.MONOSPACED);
                editor.create(new java.io.File(path).getName(), java.nio.file.Files.readString(java.nio.file.Path.of(path)));
                var tableWindow = WindowManager.getWindow("Penny batch results");
                if (tableWindow != null) {
                    var screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                    editor.setBounds(batchEditorBounds(tableWindow.getBounds(), editor.getWidth(), editor.getHeight(), screen));
                } else placeBeside(imp, editor);
                if (imp != null && imp.getWindow() != null) java.awt.EventQueue.invokeLater(() -> WindowManager.setCurrentWindow(imp.getWindow()));
            } catch (Exception ignored) { /* the macro is still saved; showing it is a courtesy */ }
        }
    }
    /** Macro entry point: opens the active RGB image's hematoxylin channel (Operations.hematoxylin) as "penny-mask". */
    public static String hematoxylin() {
        ImagePlus imp = WindowManager.getCurrentImage(); if (imp == null) return "";
        var h = Operations.hematoxylin(imp);
        ImagePlus mask = h.image(); mask.setTitle("penny-mask"); mask.show();
        return h.vectors();
    }
    /** Macro entry point: the same single-plane, source-channel input as live segment_cells, never a whole hyperstack. */
    public static String prepareMask(String channel, String hematoxylin) {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) throw new IllegalStateException("Open an image first.");
        int c = (int) Math.round(Double.parseDouble(channel));
        if (c < 1 || c > imp.getNChannels()) throw new IllegalArgumentException("Analysis channel " + c + " is missing from " + imp.getTitle() + ".");
        Roi saved = imp.getRoi(); imp.deleteRoi();
        ImagePlus mask;
        try {
            if ("true".equals(hematoxylin) || "1".equals(hematoxylin)) {
                if (imp.getType() != ImagePlus.COLOR_RGB) throw new IllegalArgumentException("Hematoxylin replay requires an RGB image: " + imp.getTitle());
                mask = Operations.hematoxylin(imp).image();
            } else {
                mask = new ij.plugin.Duplicator().run(imp, c, c, imp.getZ(), imp.getZ(), imp.getT(), imp.getT());
                if (mask.getBitDepth() != 8) ij.IJ.run(mask, "8-bit", "");
            }
        } finally { if (saved != null) imp.setRoi(saved); }
        mask.setTitle("penny-mask"); mask.show();
        // Bind Analyze Particles to the same manager used by roiManager() and refine(), including hidden batch managers.
        var manager = RoiManager.getInstance();
        if (manager != null) ij.plugin.filter.ParticleAnalyzer.setRoiManager(manager);
        return String.valueOf(mask.getID());
    }
    /**
     * Burned-in captions in multichannel images ("Press 'I' for information", scale-bar text) are white: equally bright in
     * every channel, while a stained structure is bright in its own channel only. Flags objects whose dimmest channel mean is at
     * least 85% of the brightest, with a bright core (peak in the upper half of the range).
     */
    static boolean whiteAnnotation(ImagePlus imp, Roi roi) {
        int channels = imp.getNChannels(); if (channels < 2 || roi == null) return false;
        double low = Double.POSITIVE_INFINITY, high = 0, peak = 0, range = 1;
        for (int c = 1; c <= channels; c++) {
            ImageProcessor ip = imp.getStack().getProcessor(imp.getStackIndex(c, imp.getZ(), imp.getT()));
            var whole = ip.getStats(); ip.setRoi(roi); var inside = ip.getStats(); ip.resetRoi();
            low = Math.min(low, inside.mean);
            if (inside.mean > high) { high = inside.mean; range = Math.max(1, whole.max - whole.min); }
            peak = Math.max(peak, inside.max / Math.max(1, whole.max - whole.min));
        }
        // Anti-aliased letters: equal channel means (within 15%) and a bright core (peak in the upper half), even if the mean is lower.
        return high > 0 && low / high >= 0.85 && peak >= 0.5 && high >= 0.25 * range;
    }

    /**
     * Watershed over-splits large irregular nuclei (a kidney-shaped HeLa nucleus became "1" and "3"). Two touching pieces are
     * one object again when their union is convex (solidity ≥ 0.90), not elongated (Feret / min Feret ≤ 1.7: two touching
     * round nuclei give ~2) and not oversized (≤ 2.2× the median area) — or when together they make a normal-sized object
     * (≤ 1.3× median) from an undersized piece (< 0.6× median), as concave nuclei do. Returns pairs to merge, best first.
     */
    static List<int[]> oversplitPairs(Roi[] rois) {
        int n = rois.length; var pairs = new ArrayList<int[]>(); if (n < 2) return pairs;
        double[] px = new double[n]; for (int i = 0; i < n; i++) px[i] = rois[i].getStatistics().pixelCount;
        double[] sorted = px.clone(); Arrays.sort(sorted); double median = sorted[n / 2];
        var scored = new ArrayList<double[]>();
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            var a = rois[i].getBounds(); var b = rois[j].getBounds();
            if (!a.intersects(new java.awt.Rectangle(b.x - 3, b.y - 3, b.width + 6, b.height + 6))) continue;
            if (px[i] + px[j] > 2.2 * median) continue;
            if (!touching(rois[i], rois[j])) continue;
            Roi union = union(List.of(rois[i], rois[j])); if (union == null) continue;
            double area = union.getStatistics().pixelCount, solidity = Operations.solidity(union, area);
            double[] feret = union.getFeretValues(); double aspect = feret[2] > 0 ? feret[0] / feret[2] : 99;
            // Convex and compact, or (kidney-shaped nuclei are concave) a normal-sized object made of undersized pieces.
            boolean convex = solidity >= 0.90 && aspect <= 1.7;
            boolean fragments = px[i] + px[j] <= 1.3 * median && Math.min(px[i], px[j]) < 0.6 * median && aspect <= 2.0 && solidity >= 0.75;
            if (convex || fragments) scored.add(new double[]{solidity - 0.05 * aspect, i, j});
        }
        scored.sort((x, y) -> Double.compare(y[0], x[0]));
        var used = new HashSet<Integer>();
        for (var s : scored) { int i = (int) s[1], j = (int) s[2]; if (used.add(i) && used.add(j)) pairs.add(new int[]{i, j}); else { used.remove(i); used.remove(j); } }
        return pairs;
    }
    private static boolean touching(Roi a, Roi b) {
        var grown = ij.plugin.RoiEnlarger.enlarge((Roi) a.clone(), 2);
        var overlap = new ij.gui.ShapeRoi(grown).and(new ij.gui.ShapeRoi((Roi) b.clone()));
        return overlap != null && overlap.getBounds().width > 0 && overlap.getStatistics().pixelCount > 0;
    }
    /** One closed outline covering the pieces (the 1 px watershed line between them is closed). */
    static Roi union(List<Roi> parts) {
        var box = new java.awt.Rectangle(parts.get(0).getBounds()); for (var p : parts) box.add(p.getBounds());
        var mask = new ByteProcessor(box.width + 6, box.height + 6); mask.setColor(255);
        for (var part : parts) { var local = (Roi) part.clone(); local.setLocation(local.getXBase() - box.x + 3, local.getYBase() - box.y + 3); mask.fill(local); }
        mask.dilate(); mask.dilate(); mask.erode(); mask.erode();
        Roi merged = Analysis.maskToRoi(mask); if (merged == null) return null;
        merged.setLocation(merged.getXBase() + box.x - 3, merged.getYBase() + box.y - 3);
        return merged;
    }
    /** Applies {@link #oversplitPairs}: merged outlines replace their pieces, order kept. */
    static List<Roi> mergeOversplit(Roi[] rois) {
        var pairs = oversplitPairs(rois); var out = new ArrayList<Roi>();
        if (pairs.isEmpty()) { out.addAll(Arrays.asList(rois)); return out; }
        var partner = new HashMap<Integer, Integer>(); for (var p : pairs) { partner.put(p[0], p[1]); partner.put(p[1], p[0]); }
        for (int i = 0; i < rois.length; i++) {
            Integer j = partner.get(i);
            if (j == null) out.add(rois[i]);
            else if (j > i) { var merged = union(List.of(rois[i], rois[j])); if (merged != null) { var tag = rois[i].getProperty(Operations.ROI_IMAGE_PROPERTY); if (tag != null) merged.setProperty(Operations.ROI_IMAGE_PROPERTY, tag); out.add(merged); } else { out.add(rois[i]); out.add(rois[j]); } }
        }
        return out;
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
        // As live segment_cells: pieces the watershed cut out of one nucleus are joined again.
        rois = mergeOversplit(rois).toArray(new Roi[0]);
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
            roi.setName(String.format(Locale.ROOT, "Cell %d", ++n)); Operations.tag(roi, imp); rm.addRoi(roi);
        }
        return String.valueOf(n);
    }
}
