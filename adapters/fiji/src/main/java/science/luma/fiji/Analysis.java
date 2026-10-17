package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.RankFilters;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatPolygon;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.*;

/**
 * Higher-level analyses built from exact ImageJ pixel values: tumour/tissue-mass outlining, two-channel colocalisation,
 * positive/negative intensity classification of ROI Manager objects, and summary statistics of those objects.
 * Nothing biological is inferred here; results are measurements plus neutral descriptions. EDT only.
 */
final class Analysis {
    static final int MAX_OBJECTS = 20000;
    /** Longest side the tumour-region mask is computed at; larger images are averaged down and the mask scaled back. */
    static final int WORK_SIDE = 2048;
    private static final Color POSITIVE = new Color(0, 220, 90), NEGATIVE = new Color(150, 150, 150);
    // Ruifrok & Johnston optical-density vectors, the same ones Fiji's Colour Deconvolution plugin uses for "H&E" and "H DAB".
    private static final double[][] HE = {{0.644211, 0.716556, 0.266844}, {0.092789, 0.954111, 0.283111}};
    private static final double[][] HDAB = {{0.650, 0.704, 0.286}, {0.268, 0.570, 0.776}};

    private final Operations ops;
    private final Journal journal;

    Analysis(Operations ops, Journal journal) { this.ops = ops; this.journal = journal; }

    // =====================================================================================================
    // tumor_region
    // =====================================================================================================
    Map<String, Object> tumorRegion(JsonObject args) {
        var imp = ops.active();
        int width = imp.getWidth(), height = imp.getHeight(), longer = Math.max(width, height);
        if (width < 16 || height < 16) throw new IllegalArgumentException("The image is too small to outline a region.");
        boolean rgb = imp.getType() == ImagePlus.COLOR_RGB;
        int channel = Operations.integer(args, "channel", imp.getC(), 1, Math.max(1, imp.getNChannels()));
        ImageProcessor source = channelProcessor(imp, channel);
        String stain = Operations.string(args, "stain", "auto").toLowerCase(Locale.ROOT);
        if (!List.of("auto", "he", "h&e", "ihc", "fluorescence", "brightfield").contains(stain)) throw new IllegalArgumentException("stain must be auto, he, ihc or fluorescence.");
        if (stain.equals("h&e")) stain = "he";
        if (stain.equals("auto")) stain = rgb ? "he" : lightBackground(source) ? "brightfield" : "fluorescence";
        if (stain.equals("brightfield") && rgb) stain = "he";
        boolean bright = !stain.equals("fluorescence");
        double sigma = Operations.number(args, "sigma", Math.max(2, 0.01 * longer));
        if (sigma <= 0) throw new IllegalArgumentException("sigma must be positive.");
        String method = thresholdMethod(Operations.string(args, "method", bright ? "Li" : "Otsu"));
        double minFraction = Operations.number(args, "minAreaFraction", 0.05);
        if (minFraction < 0 || minFraction > 1) throw new IllegalArgumentException("minAreaFraction must be between 0 and 1.");
        int keep = Operations.integer(args, "keepLargest", 3, 1, 50);
        String name = Operations.string(args, "name", "Tumor").strip(); if (name.isEmpty()) name = "Tumor";
        double eosinWeight = Operations.number(args, "eosinWeight", 0.25);

        // Restrict to the researcher's selection (or a ROI Manager region named "Tissue") when there is one.
        Roi selection = imp.getRoi(); String basis = "image"; Roi within = null;
        if (selection != null && selection.isArea() && Operations.bool(args, "withinSelection", true)) { within = selection; basis = "selection"; }
        else { var tissue = namedManagerRoi("Tissue"); if (tissue != null && tissue.isArea()) { within = tissue; basis = "tissue"; } }

        var steps = new ArrayList<String>(); var macro = new ArrayList<String>();
        // 1. signal where the mass is bright
        FloatProcessor signal;
        if (rgb && (stain.equals("he") || stain.equals("ihc"))) {
            boolean he = stain.equals("he");
            signal = deconvolve((ColorProcessor) source, he ? HE : HDAB, he ? eosinWeight : 1.0);
            String vectors = he ? "H&E" : "H DAB";
            steps.add("colour deconvolution (" + vectors + " vectors), " + (he ? "hematoxylin + " + Operations.plain(eosinWeight) + " x eosin density" : "hematoxylin + DAB density"));
            macro.add(Menus.getCommands() != null && Menus.getCommands().get("Colour Deconvolution") != null
                ? "run(\"Colour Deconvolution\", \"vectors=[" + vectors + "]\");" : "// colour deconvolution (" + vectors + " Ruifrok vectors) computed in the bridge");
        } else if (bright) {
            signal = (FloatProcessor) (rgb ? ((ColorProcessor) source).getBrightness() : source).convertToFloat().duplicate();
            float max = (float) signal.getStatistics().max; float[] p = (float[]) signal.getPixels();
            for (int i = 0; i < p.length; i++) p[i] = max - p[i];
            steps.add("8-bit, inverted so dense tissue is bright"); macro.add("run(\"8-bit\");"); macro.add("run(\"Invert\");");
        } else {
            signal = (FloatProcessor) (rgb ? ((ColorProcessor) source).getBrightness() : source).convertToFloat().duplicate();
            steps.add("channel " + channel + " intensity");
        }
        // 2. work at a bounded resolution
        int factor = Math.max(1, (int) Math.ceil(longer / (double) WORK_SIDE));
        FloatProcessor work = signal;
        if (factor > 1) { work.setInterpolationMethod(ImageProcessor.BILINEAR); work = (FloatProcessor) work.resize(Math.max(1, width / factor), Math.max(1, height / factor), true); steps.add("downsample x" + factor + " for the mask"); }
        int ww = work.getWidth(), wh = work.getHeight();
        double workSigma = Math.max(0.5, sigma / factor);
        new GaussianBlur().blurGaussian(work, workSigma, workSigma, 0.01);
        steps.add("Gaussian blur sigma " + Operations.plain(round(sigma, 2)) + " px"); macro.add("run(\"Gaussian Blur...\", \"sigma=" + Operations.plain(round(sigma, 2)) + "\");");
        ByteProcessor withinMask = null;
        if (within != null) {
            withinMask = new ByteProcessor(width, height); withinMask.setValue(255); withinMask.fill(within);
            if (factor > 1) { withinMask = (ByteProcessor) withinMask.resize(ww, wh); }
        }
        // 3. threshold
        double cut = floatThreshold(work, method, withinMask);
        var mask = new ByteProcessor(ww, wh); float[] wp = (float[]) work.getPixels(); byte[] mp = (byte[]) mask.getPixels();
        byte[] inside = withinMask == null ? null : (byte[]) withinMask.getPixels();
        for (int i = 0; i < wp.length; i++) mp[i] = (byte) (wp[i] > cut && (inside == null || inside[i] != 0) ? 255 : 0);
        steps.add("auto threshold " + method + (within == null ? "" : " within the " + basis)); macro.add("setAutoThreshold(\"" + method + " dark\");"); macro.add("run(\"Convert to Mask\");");
        // 4. clean up: close, fill holes, open
        int radius = (int) Math.max(1, Math.round(workSigma));
        var rank = new RankFilters();
        rank.rank(mask, radius, RankFilters.MAX); rank.rank(mask, radius, RankFilters.MIN);
        fillHoles(mask);
        rank.rank(mask, radius, RankFilters.MIN); rank.rank(mask, radius, RankFilters.MAX);
        if (inside != null) for (int i = 0; i < mp.length; i++) if (inside[i] == 0) mp[i] = 0;
        steps.add("close, fill holes, open (radius " + radius * factor + " px)");
        macro.add("run(\"Options...\", \"iterations=" + radius * factor + " count=1 do=Close\");"); macro.add("run(\"Fill Holes\");"); macro.add("run(\"Options...\", \"iterations=" + radius * factor + " count=1 do=Open\");");
        // 5. keep the largest components
        int[] labels = new int[ww * wh]; var areas = label(mp, ww, wh, labels);
        if (areas.isEmpty()) throw new IllegalStateException("No region stood out from the background; try another method or stain.");
        var order = new ArrayList<Integer>(); for (int i = 0; i < areas.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(areas.get(b), areas.get(a)));
        int largest = areas.get(order.getFirst());
        var kept = new ArrayList<Integer>();
        for (int index : order) { if (kept.size() >= keep) break; if (areas.get(index) >= minFraction * largest) kept.add(index); }
        steps.add("keep " + kept.size() + " of " + areas.size() + " components (largest " + keep + ", each at least " + Operations.plain(minFraction * 100) + "% of the largest)");
        // 6. selections at full resolution
        var parts = new ArrayList<Roi>(); var keptMask = new ByteProcessor(width, height); byte[] km = (byte[]) keptMask.getPixels();
        for (int k = 0; k < kept.size(); k++) {
            int labelValue = kept.get(k) + 1;
            var part = new ByteProcessor(ww, wh); byte[] pp = (byte[]) part.getPixels();
            for (int i = 0; i < labels.length; i++) if (labels[i] == labelValue) pp[i] = (byte) 255;
            ByteProcessor full = factor > 1 ? upscale(part, width, height) : part;
            byte[] fp = (byte[]) full.getPixels(); for (int i = 0; i < fp.length; i++) if (fp[i] != 0) km[i] = (byte) 255;
            Roi roi = maskToRoi(full); if (roi == null) continue;
            roi.setName(kept.size() == 1 ? name : name + " " + (k + 1)); parts.add(roi);
        }
        if (parts.isEmpty()) throw new IllegalStateException("The outlined region vanished at full resolution; try a smaller sigma.");
        Roi composite = parts.size() == 1 ? (Roi) parts.getFirst().clone() : combine(parts);
        composite.setName(name);
        steps.add("create selection" + (parts.size() > 1 ? " (" + parts.size() + " parts combined)" : "")); macro.add("run(\"Create Selection\");");
        // 7. ROI Manager + active selection
        var manager = ops.manager(); int roiIndex = -1;
        if (manager != null) {
            composite.setStrokeColor(Color.yellow); manager.addRoi(composite); roiIndex = manager.getCount() - 1;
            if (parts.size() > 1) for (var part : parts) manager.addRoi((Roi) part.clone());
            macro.add("roiManager(\"Add\");"); macro.add("roiManager(\"rename\", \"" + Journal.escape(name) + "\");");
        }
        imp.setRoi((Roi) composite.clone());
        // 8. measurements (calibrated)
        Calibration cal = imp.getCalibration(); double pixelArea = cal.pixelWidth * cal.pixelHeight;
        long pixels = 0; for (byte b : km) if (b != 0) pixels++;
        double area = pixels * pixelArea;
        double basisArea;
        if (within != null) { var wm = new ByteProcessor(width, height); wm.setValue(255); wm.fill(within); long n = 0; for (byte b : (byte[]) wm.getPixels()) if (b != 0) n++; basisArea = n * pixelArea; }
        else basisArea = (double) width * height * pixelArea;
        Roi measured = (Roi) composite.clone(); measured.setImage(imp);
        double perimeter = measured.getLength();
        double hull = polygonArea(composite.getFloatConvexHull()) * pixelArea;
        source.setRoi(composite); double meanIntensity = source.getStats().mean; source.resetRoi();
        var result = new LinkedHashMap<String, Object>();
        result.put("regions", parts.size()); result.put("area", area); result.put("areaFraction", basisArea > 0 ? area / basisArea : 0.0); result.put("areaFractionOf", basis);
        result.put("perimeter", perimeter); result.put("circularity", perimeter > 0 ? Math.min(1, 4 * Math.PI * area / (perimeter * perimeter)) : 0.0);
        result.put("solidity", hull > 0 ? Math.min(1, area / hull) : 0.0); result.put("unit", cal.getUnit());
        result.put("meanIntensity", meanIntensity); result.put("intensityOf", rgb ? "RGB brightness" : "channel " + channel);
        result.put("roiIndex", roiIndex); result.put("name", name); result.put("stain", stain); result.put("method", method); result.put("sigma", sigma);
        result.put("threshold", cut); result.put("components", areas.size());
        var partList = new ArrayList<Map<String, Object>>();
        for (var part : parts) { var m = new LinkedHashMap<String, Object>(); m.put("name", part.getName()); m.put("bounds", Operations.roiJson(part)); partList.add(m); }
        result.put("parts", partList);
        result.put("steps", steps); result.put("macro", String.join("\n", macro));
        journal.record("tumor_region", "// tumor_region: " + String.join("; ", steps));
        result.put("focused", Viewer.follow(imp, composite.getBounds()));
        result.put("highlight", Viewer.roiHighlight(imp, parts, parts.size() == 1 ? name : name + ": " + parts.size() + " regions", 20));
        return result;
    }

    static boolean lightBackground(ImageProcessor ip) {
        var stats = ImageStatistics.getStatistics(ip, Measurements.MEDIAN | Measurements.MIN_MAX, null);
        return stats.median > stats.min + (stats.max - stats.min) / 2;
    }
    /** Weighted stain concentration image (first stain + weight x second) from colour deconvolution of an RGB processor. */
    static FloatProcessor deconvolve(ColorProcessor cp, double[][] stains, double secondWeight) {
        double[][] m = new double[3][];
        m[0] = normal(stains[0]); m[1] = normal(stains[1]);
        double[] third = {m[0][1] * m[1][2] - m[0][2] * m[1][1], m[0][2] * m[1][0] - m[0][0] * m[1][2], m[0][0] * m[1][1] - m[0][1] * m[1][0]};
        m[2] = normal(third);
        double[][] inv = invert(m);
        int w = cp.getWidth(), h = cp.getHeight(); int[] px = (int[]) cp.getPixels(); float[] out = new float[w * h];
        double[] lut = new double[256]; for (int v = 0; v < 256; v++) lut[v] = -Math.log10((v + 1) / 256.0);
        for (int i = 0; i < px.length; i++) {
            int c = px[i]; double r = lut[(c >> 16) & 0xff], g = lut[(c >> 8) & 0xff], b = lut[c & 0xff];
            double s0 = r * inv[0][0] + g * inv[1][0] + b * inv[2][0];
            double s1 = r * inv[0][1] + g * inv[1][1] + b * inv[2][1];
            out[i] = (float) (Math.max(0, s0) + secondWeight * Math.max(0, s1));
        }
        return new FloatProcessor(w, h, out);
    }
    private static double[] normal(double[] v) { double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return n == 0 ? new double[]{0, 0, 0} : new double[]{v[0] / n, v[1] / n, v[2] / n}; }
    private static double[][] invert(double[][] a) {
        double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        if (Math.abs(det) < 1e-12) throw new IllegalStateException("The stain vectors are degenerate.");
        double[][] r = new double[3][3];
        r[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det; r[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det; r[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det;
        r[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det; r[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det; r[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det;
        r[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det; r[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det; r[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;
        return r;
    }
    /** Auto threshold of a float image over a 256-bin histogram (optionally inside a mask); returns the value above which pixels are foreground. */
    static double floatThreshold(FloatProcessor fp, String method, ByteProcessor mask) {
        float[] p = (float[]) fp.getPixels(); byte[] m = mask == null ? null : (byte[]) mask.getPixels();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < p.length; i++) { if (m != null && m[i] == 0) continue; min = Math.min(min, p[i]); max = Math.max(max, p[i]); }
        if (!(max > min)) throw new IllegalStateException("The image is uniform here; nothing to outline.");
        int[] histogram = new int[256]; double scale = 255.0 / (max - min);
        for (int i = 0; i < p.length; i++) { if (m != null && m[i] == 0) continue; histogram[(int) Math.min(255, (p[i] - min) * scale)]++; }
        int level = new AutoThresholder().getThreshold(method, histogram);
        return min + (level + 1) / scale;
    }
    static String thresholdMethod(String method) {
        for (String known : AutoThresholder.getMethods()) if (known.equalsIgnoreCase(method)) return known;
        throw new IllegalArgumentException("Unknown threshold method '" + method + "'. Known: " + String.join(", ", AutoThresholder.getMethods()));
    }
    /** Fills background regions not connected to the image border (ImageJ's Fill Holes on a 255-foreground mask). */
    static void fillHoles(ByteProcessor mask) {
        int w = mask.getWidth(), h = mask.getHeight(); byte[] p = (byte[]) mask.getPixels();
        boolean[] outside = new boolean[w * h]; int[] queue = new int[w * h]; int head = 0, tail = 0;
        for (int x = 0; x < w; x++) for (int y : new int[]{0, h - 1}) { int i = y * w + x; if (p[i] == 0 && !outside[i]) { outside[i] = true; queue[tail++] = i; } }
        for (int y = 0; y < h; y++) for (int x : new int[]{0, w - 1}) { int i = y * w + x; if (p[i] == 0 && !outside[i]) { outside[i] = true; queue[tail++] = i; } }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            if (x > 0 && p[i - 1] == 0 && !outside[i - 1]) { outside[i - 1] = true; queue[tail++] = i - 1; }
            if (x < w - 1 && p[i + 1] == 0 && !outside[i + 1]) { outside[i + 1] = true; queue[tail++] = i + 1; }
            if (y > 0 && p[i - w] == 0 && !outside[i - w]) { outside[i - w] = true; queue[tail++] = i - w; }
            if (y < h - 1 && p[i + w] == 0 && !outside[i + w]) { outside[i + w] = true; queue[tail++] = i + w; }
        }
        for (int i = 0; i < p.length; i++) if (p[i] == 0 && !outside[i]) p[i] = (byte) 255;
    }
    /** 8-connected labelling of non-zero pixels; labels are 1-based, returns component areas by label-1. */
    static List<Integer> label(byte[] p, int w, int h, int[] labels) {
        var areas = new ArrayList<Integer>(); int[] queue = new int[w * h];
        for (int start = 0; start < p.length; start++) {
            if (p[start] == 0 || labels[start] != 0) continue;
            int id = areas.size() + 1, head = 0, tail = 0; queue[tail++] = start; labels[start] = id;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy; if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = ny * w + nx; if (p[j] != 0 && labels[j] == 0) { labels[j] = id; queue[tail++] = j; }
                }
            }
            areas.add(tail);
        }
        return areas;
    }
    static ByteProcessor upscale(ByteProcessor small, int width, int height) {
        small.setInterpolationMethod(ImageProcessor.BILINEAR);
        var big = (ByteProcessor) small.resize(width, height); byte[] p = (byte[]) big.getPixels();
        for (int i = 0; i < p.length; i++) p[i] = (byte) ((p[i] & 0xff) >= 128 ? 255 : 0);
        return big;
    }
    static Roi maskToRoi(ByteProcessor mask) {
        mask.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        return new ThresholdToSelection().convert(mask);
    }
    static Roi combine(List<Roi> parts) {
        ShapeRoi shape = new ShapeRoi(parts.getFirst());
        for (int i = 1; i < parts.size(); i++) shape = shape.or(new ShapeRoi(parts.get(i)));
        return shape;
    }
    static double polygonArea(FloatPolygon polygon) {
        if (polygon == null || polygon.npoints < 3) return 0;
        double sum = 0; for (int i = 0, j = polygon.npoints - 1; i < polygon.npoints; j = i++) sum += (double) polygon.xpoints[j] * polygon.ypoints[i] - (double) polygon.xpoints[i] * polygon.ypoints[j];
        return Math.abs(sum) / 2;
    }
    private Roi namedManagerRoi(String name) {
        var manager = ops.managerOrNull(); if (manager == null) return null;
        for (var roi : manager.getRoisAsArray()) if (roi != null && name.equalsIgnoreCase(roi.getName())) return roi;
        return null;
    }

    // =====================================================================================================
    // colocalize
    // =====================================================================================================
    Map<String, Object> colocalize(JsonObject args) {
        ImagePlus impA, impB; int channelA, channelB; String sourceText;
        if (args.has("imageA") || args.has("imageB")) {
            if (!(args.has("imageA") && args.has("imageB"))) throw new IllegalArgumentException("Give both imageA and imageB.");
            impA = byId(args.get("imageA").getAsInt()); impB = byId(args.get("imageB").getAsInt());
            channelA = Operations.integer(args, "channelA", impA.getC(), 1, maxChannel(impA));
            channelB = Operations.integer(args, "channelB", impB.getC(), 1, maxChannel(impB));
            sourceText = impA.getTitle() + " vs " + impB.getTitle();
        } else {
            impA = impB = ops.active();
            if (maxChannel(impA) < 2) throw new IllegalArgumentException("Colocalisation needs two channels; this image has one. Name a second image with imageA and imageB.");
            channelA = Operations.integer(args, "channelA", 1, 1, maxChannel(impA));
            channelB = Operations.integer(args, "channelB", 2, 1, maxChannel(impA));
            if (channelA == channelB) throw new IllegalArgumentException("Choose two different channels.");
            sourceText = impA.getTitle() + " channels " + channelA + " and " + channelB;
        }
        ImageProcessor a = colocChannel(impA, channelA), b = colocChannel(impB, channelB);
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) throw new IllegalArgumentException("The two images differ in size.");
        int w = a.getWidth(), h = a.getHeight();
        Roi roi = Operations.bool(args, "roi", false) ? impA.getRoi() : null;
        if (Operations.bool(args, "roi", false) && (roi == null || !roi.isArea())) throw new IllegalArgumentException("Draw an area selection first, or leave roi off.");
        boolean[] mask = null;
        if (roi != null) { var mp = new ByteProcessor(w, h); mp.setValue(255); mp.fill(roi); byte[] m = (byte[]) mp.getPixels(); mask = new boolean[m.length]; for (int i = 0; i < m.length; i++) mask[i] = m[i] != 0; }
        int n = 0; for (int i = 0; i < w * h; i++) if (mask == null || mask[i]) n++;
        if (n < 10) throw new IllegalArgumentException("Too few pixels to compare.");
        float[] va = new float[n], vb = new float[n];
        for (int i = 0, k = 0; i < w * h; i++) if (mask == null || mask[i]) { va[k] = a.getf(i); vb[k] = b.getf(i); k++; }

        double pearson = pearson(va, vb, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, false);
        String method = Operations.string(args, "method", "Costes");
        double ta, tb; String used;
        if (args.has("thresholdA") || args.has("thresholdB")) {
            ta = Operations.number(args, "thresholdA", 0); tb = Operations.number(args, "thresholdB", 0); used = "manual";
        } else if (method.equalsIgnoreCase("Costes")) {
            double[] costes = costes(va, vb);
            if (costes == null) { ta = otsu(va); tb = otsu(vb); used = "Otsu (Costes needs a positive correlation)"; }
            else { ta = costes[0]; tb = costes[1]; used = "Costes"; }
        } else if (method.equalsIgnoreCase("none")) { ta = Double.NEGATIVE_INFINITY; tb = Double.NEGATIVE_INFINITY; used = "none"; }
        else { String known = thresholdMethod(method); ta = autoValue(va, known); tb = autoValue(vb, known); used = known; }

        double sumA = 0, sumB = 0, sumAcol = 0, sumBcol = 0, ab = 0, aa = 0, bb = 0, meanA = 0, meanB = 0; int above = 0;
        for (int i = 0; i < n; i++) { meanA += va[i]; meanB += vb[i]; }
        meanA /= n; meanB /= n; int icqPositive = 0;
        for (int i = 0; i < n; i++) {
            double x = va[i], y = vb[i];
            ab += x * y; aa += x * x; bb += y * y;
            if ((x - meanA) * (y - meanB) > 0) icqPositive++;
            boolean inA = x > ta, inB = y > tb;
            if (inA) sumA += x; if (inB) sumB += y;
            if (inA && inB) { sumAcol += x; sumBcol += y; above++; }
        }
        double overlap = aa > 0 && bb > 0 ? ab / Math.sqrt(aa * bb) : 0;
        double pearsonAbove = pearson(va, vb, ta, tb, true);
        var result = new LinkedHashMap<String, Object>();
        result.put("pearson", Operations.finite(pearson));
        result.put("pearsonAboveThreshold", Operations.finite(pearsonAbove));
        result.put("manders", Map.of("m1", sumA > 0 ? sumAcol / sumA : 0.0, "m2", sumB > 0 ? sumBcol / sumB : 0.0));
        result.put("overlap", overlap);
        result.put("icq", (double) icqPositive / n - 0.5);
        var thresholds = new LinkedHashMap<String, Object>(); thresholds.put("a", Operations.finite(ta)); thresholds.put("b", Operations.finite(tb)); result.put("thresholds", thresholds);
        result.put("method", used); result.put("pixels", n); result.put("pixelsAboveBoth", above);
        result.put("channels", Map.of("a", channelA, "b", channelB)); result.put("source", sourceText); result.put("region", roi == null ? "whole image" : "selection");
        result.put("interpretation", interpretation(pearson));
        result.put("note", "Manders coefficients use the thresholds; Pearson covers every pixel in the region.");
        journal.record("colocalize", "// colocalize: " + sourceText + ", " + used + " thresholds, Pearson " + String.format(Locale.US, "%.3f", pearson));
        result.put("highlight", roi == null ? Viewer.imageHighlight(impA, "Pearson r = " + String.format(Locale.US, "%.2f", pearson)) : Viewer.roiHighlight(impA, List.of(roi), "Pearson r = " + String.format(Locale.US, "%.2f", pearson), 1));
        return result;
    }
    static String interpretation(double r) {
        if (!Double.isFinite(r)) return "no correlation could be computed";
        double m = Math.abs(r); String sign = r >= 0 ? "positive" : "negative";
        if (m >= 0.7) return "strong " + sign + " correlation";
        if (m >= 0.4) return "moderate " + sign + " correlation";
        if (m >= 0.1) return "weak " + sign + " correlation";
        return "negligible correlation";
    }
    static double pearson(float[] a, float[] b, double ta, double tb, boolean aboveOnly) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0; long n = 0;
        for (int i = 0; i < a.length; i++) {
            if (aboveOnly && !(a[i] > ta && b[i] > tb)) continue;
            double x = a[i], y = b[i]; sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; n++;
        }
        if (n < 3) return Double.NaN;
        double cov = sab - sa * sb / n, va = saa - sa * sa / n, vb = sbb - sb * sb / n;
        return va > 0 && vb > 0 ? cov / Math.sqrt(va * vb) : Double.NaN;
    }
    /** Costes auto-threshold: regress B on A, then bisect the A threshold until pixels below both thresholds no longer correlate. Null when the slope is not positive. */
    static double[] costes(float[] a, float[] b) {
        int n = a.length; double sa = 0, sb = 0, saa = 0, sab = 0, minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { sa += a[i]; sb += b[i]; saa += (double) a[i] * a[i]; sab += (double) a[i] * b[i]; minA = Math.min(minA, a[i]); maxA = Math.max(maxA, a[i]); }
        double varA = saa - sa * sa / n; if (varA <= 0) return null;
        double slope = (sab - sa * sb / n) / varA, intercept = (sb - slope * sa) / n;
        if (!(slope > 0)) return null;
        double lo = minA, hi = maxA;
        for (int iteration = 0; iteration < 40 && hi - lo > 1e-6 * Math.max(1, Math.abs(maxA)); iteration++) {
            double mid = (lo + hi) / 2, tbMid = slope * mid + intercept;
            double r = belowPearson(a, b, mid, tbMid);
            if (Double.isNaN(r) || r <= 0) lo = mid; else hi = mid;
        }
        return new double[]{hi, slope * hi + intercept};
    }
    private static double belowPearson(float[] a, float[] b, double ta, double tb) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0; long n = 0;
        for (int i = 0; i < a.length; i++) {
            if (!(a[i] < ta && b[i] < tb)) continue;
            double x = a[i], y = b[i]; sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; n++;
        }
        if (n < 3) return Double.NaN;
        double cov = sab - sa * sb / n, va = saa - sa * sa / n, vb = sbb - sb * sb / n;
        return va > 0 && vb > 0 ? cov / Math.sqrt(va * vb) : Double.NaN;
    }
    static double otsu(float[] values) { return autoValue(values, "Otsu"); }
    /** ImageJ auto-threshold over a 256-bin histogram of the values; returns the cut value (foreground is strictly above). */
    static double autoValue(float[] values, String method) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (float v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        if (!(max > min)) return min;
        int[] histogram = new int[256]; double scale = 255.0 / (max - min);
        for (float v : values) histogram[(int) Math.min(255, (v - min) * scale)]++;
        int level = new AutoThresholder().getThreshold(method, histogram);
        return min + (level + 1) / scale;
    }
    private ImagePlus byId(int id) { var args = new JsonObject(); args.addProperty("id", id); return ops.find(args); }
    private static int maxChannel(ImagePlus imp) { return imp.getType() == ImagePlus.COLOR_RGB && imp.getNChannels() == 1 ? 3 : Math.max(1, imp.getNChannels()); }
    /** The processor for one channel at the current slice/frame. For a single-channel RGB image, channels 1-3 are red, green and blue only when asked for by colocalisation; otherwise the RGB processor itself. */
    static ImageProcessor channelProcessor(ImagePlus imp, int channel) {
        if (imp.getType() == ImagePlus.COLOR_RGB && imp.getNChannels() == 1) return imp.getProcessor();
        int index = imp.getStackIndex(channel, imp.getZ(), imp.getT());
        var ip = imp.getStack().getProcessor(index);
        ip.setCalibrationTable(imp.getCalibration().getCTable());
        return ip;
    }
    /** Grayscale data for colocalisation: an RGB image's red, green or blue component, otherwise the channel itself. */
    static ImageProcessor colocChannel(ImagePlus imp, int channel) {
        ImageProcessor ip = channelProcessor(imp, channel);
        if (ip instanceof ColorProcessor cp) return imp.getNChannels() == 1 ? cp.getChannel(channel, null) : cp.convertToByteProcessor();
        return ip;
    }

    // =====================================================================================================
    // classify_intensity
    // =====================================================================================================
    Map<String, Object> classifyIntensity(JsonObject args) {
        var imp = ops.active();
        var manager = ops.managerOrNull();
        if (manager == null || manager.getCount() == 0) throw new IllegalStateException("Segment the cells first.");
        Roi[] rois = manager.getRoisAsArray();
        if (rois.length > MAX_OBJECTS) throw new IllegalArgumentException("The ROI Manager holds " + rois.length + " objects; the limit is " + MAX_OBJECTS + ".");
        int channel = Operations.integer(args, "channel", imp.getC(), 1, Math.max(1, imp.getNChannels()));
        String measurement = Operations.string(args, "measurement", "mean").toLowerCase(Locale.ROOT);
        if (!List.of("mean", "intdens", "median", "max").contains(measurement)) throw new IllegalArgumentException("measurement must be mean, intdens, median or max.");
        String label = Operations.string(args, "label", "Positive").strip(); if (label.isEmpty()) label = "Positive";
        ImageProcessor ip = measurementProcessor(imp, channel);
        Calibration cal = imp.getCalibration();
        double[] values = new double[rois.length];
        for (int i = 0; i < rois.length; i++) values[i] = measureOne(ip, rois[i], cal, measurement);
        double threshold; String method;
        if (args.has("threshold")) { threshold = Operations.number(args, "threshold", 0); method = "manual"; }
        else {
            method = thresholdMethod(Operations.string(args, "method", "Otsu"));
            double min = Arrays.stream(values).min().orElse(0), max = Arrays.stream(values).max().orElse(0);
            if (!(max > min)) throw new IllegalStateException("Every object has the same " + measurement + "; give a threshold.");
            float[] f = new float[values.length]; for (int i = 0; i < f.length; i++) f[i] = (float) values[i];
            threshold = autoValue(f, method);
        }
        int positive = 0, negative = 0; var positiveIndices = new ArrayList<Integer>(); var positiveRois = new ArrayList<Roi>();
        var table = new ResultsTable(); var overlay = new Overlay();
        for (int i = 0; i < rois.length; i++) {
            boolean pos = values[i] >= threshold;
            String newName = label + (pos ? "+ " + (++positive) : "- " + (++negative));
            Roi roi = rois[i];
            roi.setStrokeColor(pos ? POSITIVE : NEGATIVE);
            manager.rename(i, newName);
            if (pos) { if (positiveIndices.size() < 500) positiveIndices.add(i); positiveRois.add(roi); }
            var copy = (Roi) roi.clone(); copy.setName(newName); overlay.add(copy);
            table.addRow(); table.addValue("Index", i); table.addLabel(newName); table.addValue("Class", pos ? 1 : 0); table.addValue(measurementHeading(measurement), values[i]);
        }
        imp.setOverlay(overlay);
        if (Operations.guiVisible()) table.show("Classification");
        int total = rois.length;
        var result = new LinkedHashMap<String, Object>();
        result.put("total", total); result.put("positive", positive); result.put("negative", negative);
        result.put("percentPositive", total == 0 ? 0.0 : 100.0 * positive / total);
        result.put("threshold", threshold); result.put("thresholdMethod", method); result.put("measurement", measurement); result.put("channel", channel); result.put("label", label);
        result.put("positiveIndices", positiveIndices); result.put("positiveIndicesTruncated", positive > positiveIndices.size());
        result.put("table", "Classification");
        journal.record("classify_intensity", "// classify_intensity: " + label + " if " + measurement + " >= " + Operations.plain(round(threshold, 4)) + " on channel " + channel + " (" + method + "): " + positive + " of " + total);
        result.put("highlight", Viewer.roiHighlight(imp, positiveRois, positive + " " + label + "+ of " + total, 120));
        return result;
    }
    private static String measurementHeading(String measurement) { return switch (measurement) { case "intdens" -> "IntDen"; case "median" -> "Median"; case "max" -> "Max"; default -> "Mean"; }; }
    /** Grayscale processor for measuring: the channel at the current slice/frame, or RGB brightness (ImageJ's weighted RGB conversion) for RGB images. */
    static ImageProcessor measurementProcessor(ImagePlus imp, int channel) {
        ImageProcessor ip = channelProcessor(imp, channel);
        if (ip instanceof ColorProcessor cp) return cp.convertToByteProcessor();
        return ip;
    }
    static double measureOne(ImageProcessor ip, Roi roi, Calibration cal, String measurement) {
        ip.setRoi(roi);
        var stats = ImageStatistics.getStatistics(ip, Measurements.AREA | Measurements.MEAN | Measurements.MEDIAN | Measurements.MIN_MAX, cal);
        ip.resetRoi();
        return switch (measurement) { case "intdens" -> stats.area * stats.mean; case "median" -> stats.median; case "max" -> stats.max; default -> stats.mean; };
    }

    // =====================================================================================================
    // object_stats
    // =====================================================================================================
    Map<String, Object> regionStats(JsonObject args) {
        var imp = ops.active();
        var manager = ops.managerOrNull();
        if (manager == null || manager.getCount() == 0) throw new IllegalStateException("The ROI Manager is empty; segment the cells first.");
        Roi[] rois = manager.getRoisAsArray();
        if (rois.length > MAX_OBJECTS) throw new IllegalArgumentException("The ROI Manager holds " + rois.length + " objects; the limit is " + MAX_OBJECTS + ".");
        int channel = Operations.integer(args, "channel", imp.getC(), 1, Math.max(1, imp.getNChannels()));
        ImageProcessor ip = measurementProcessor(imp, channel); Calibration cal = imp.getCalibration();
        int n = rois.length; double[] area = new double[n], mean = new double[n], circ = new double[n], feret = new double[n];
        for (int i = 0; i < n; i++) {
            Roi roi = (Roi) rois[i].clone(); roi.setImage(imp);
            ip.setRoi(roi);
            var stats = ImageStatistics.getStatistics(ip, Measurements.AREA | Measurements.MEAN, cal); ip.resetRoi();
            area[i] = stats.area; mean[i] = stats.mean;
            double perimeter = roi.getLength();
            circ[i] = perimeter > 0 ? Math.min(1, 4 * Math.PI * stats.area / (perimeter * perimeter)) : 0;
            feret[i] = roi.getFeretValues()[0];
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("n", n); result.put("area", describeValues(area)); result.put("mean", describeValues(mean)); result.put("circularity", describeValues(circ)); result.put("feret", describeValues(feret));
        result.put("areaHistogram", histogram(area, 10));
        Integer[] order = new Integer[n]; for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(area[y], area[x]));
        var largest = new ArrayList<Map<String, Object>>(); var smallest = new ArrayList<Map<String, Object>>(); var largestRois = new ArrayList<Roi>();
        for (int k = 0; k < Math.min(5, n); k++) { int i = order[k]; largest.add(entry(i, rois[i], area[i], mean[i])); largestRois.add(rois[i]); }
        for (int k = 0; k < Math.min(5, n); k++) { int i = order[n - 1 - k]; smallest.add(entry(i, rois[i], area[i], mean[i])); }
        result.put("largest", largest); result.put("smallest", smallest);
        result.put("unit", cal.getUnit()); result.put("areaUnit", cal.getUnit() + "^2"); result.put("channel", channel);
        result.put("highlight", Viewer.roiHighlight(imp, largestRois, largestRois.size() == 1 ? "Largest object" : largestRois.size() + " largest objects", 5));
        return result;
    }
    private static Map<String, Object> entry(int index, Roi roi, double area, double mean) {
        var m = new LinkedHashMap<String, Object>(); m.put("index", index); m.put("name", roi.getName()); m.put("area", area); m.put("mean", mean);
        var b = roi.getBounds(); m.put("x", b.x); m.put("y", b.y); m.put("width", b.width); m.put("height", b.height);
        return m;
    }
    static Map<String, Object> describeValues(double[] values) {
        var m = new LinkedHashMap<String, Object>();
        int n = values.length; if (n == 0) return m;
        double[] sorted = values.clone(); Arrays.sort(sorted);
        double sum = 0; for (double v : values) sum += v; double mean = sum / n;
        double ss = 0; for (double v : values) ss += (v - mean) * (v - mean);
        m.put("mean", mean); m.put("median", n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2);
        m.put("sd", n > 1 ? Math.sqrt(ss / (n - 1)) : 0.0); m.put("min", sorted[0]); m.put("max", sorted[n - 1]);
        return m;
    }
    static Map<String, Object> histogram(double[] values, int bins) {
        double min = Arrays.stream(values).min().orElse(0), max = Arrays.stream(values).max().orElse(0);
        int[] counts = new int[bins]; double width = (max - min) / bins;
        for (double v : values) counts[width > 0 ? (int) Math.min(bins - 1, (v - min) / width) : 0]++;
        var m = new LinkedHashMap<String, Object>(); m.put("min", min); m.put("max", max); m.put("binWidth", width);
        var list = new ArrayList<Integer>(); for (int c : counts) list.add(c); m.put("counts", list);
        return m;
    }
    private static double round(double value, int places) { double f = Math.pow(10, places); return Math.round(value * f) / f; }
}
