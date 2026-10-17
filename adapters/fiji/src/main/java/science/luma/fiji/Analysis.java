package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
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
        var adjusted = new ArrayList<String>();
        // A tumour mass is a tissue-scale structure: a nucleus-scale blur (e.g. sigma 2 on a 2048 px field) outlines single nuclei and
        // the largest of those is a sliver, so the blur never goes below 0.5% of the longer side.
        double sigmaFloor = Math.max(2, 0.005 * longer);
        double sigma = Operations.number(args, "sigma", Math.max(2, 0.01 * longer));
        if (sigma <= 0) throw new IllegalArgumentException("sigma must be positive.");
        if (sigma < sigmaFloor) { adjusted.add("sigma " + Operations.plain(round(sigma, 2)) + " raised to " + Operations.plain(round(sigmaFloor, 2)) + " px (tissue scale)"); sigma = sigmaFloor; }
        String method = thresholdMethod(Operations.string(args, "method", bright ? "Li" : "Otsu"));
        double minFraction = Operations.number(args, "minAreaFraction", 0.01);
        if (minFraction < 0 || minFraction > 1) throw new IllegalArgumentException("minAreaFraction must be between 0 and 1.");
        int keep = Operations.integer(args, "keepLargest", 10, 1, 50);
        String name = Operations.string(args, "name", "Tumor").strip(); if (name.isEmpty()) name = "Tumor";
        double eosinWeight = Operations.number(args, "eosinWeight", 0.25);

        // Restrict to the researcher's selection (or a ROI Manager region named "Tissue") when there is one. A selection that is
        // this tool's own previous outline is not a restriction: a rerun would otherwise outline inside the last result and shrink.
        Roi selection = imp.getRoi(); String basis = "image"; Roi within = null;
        boolean ownOutline = selection != null && selection.getName() != null && (selection.getName().equals(name) || selection.getName().startsWith(name + " "));
        if (selection != null && selection.isArea() && !ownOutline && Operations.bool(args, "withinSelection", true)) { within = selection; basis = "selection"; }
        else { var tissue = namedManagerRoi("Tissue", imp); if (tissue != null && tissue.isArea()) { within = tissue; basis = "tissue"; } }

        final String withinBasis = basis;
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
        // 2b. tissue mask (RGB brightfield): glass, fat and other near-white background are not tissue, so neither the threshold nor
        // the reported fraction counts them.
        ByteProcessor tissueMask = null; double tissueCut = Double.NaN;
        if (rgb && (stain.equals("he") || stain.equals("ihc"))) {
            FloatProcessor od = opticalDensity((ColorProcessor) source);
            if (factor > 1) { od.setInterpolationMethod(ImageProcessor.BILINEAR); od = (FloatProcessor) od.resize(ww, wh, true); }
            double tissueSigma = Math.max(1, workSigma / 2);
            new GaussianBlur().blurGaussian(od, tissueSigma, tissueSigma, 0.01);
            tissueCut = tissueThreshold(od);
            tissueMask = new ByteProcessor(ww, wh); byte[] tm = (byte[]) tissueMask.getPixels(); float[] op = (float[]) od.getPixels();
            byte[] wm = withinMask == null ? null : (byte[]) withinMask.getPixels();
            long tissuePixels = 0;
            for (int i = 0; i < op.length; i++) if (op[i] > tissueCut && (wm == null || wm[i] != 0)) { tm[i] = (byte) 255; tissuePixels++; }
            if (tissuePixels < 0.01 * op.length) throw new IllegalStateException("Almost no tissue stands out from the white background here.");
            steps.add("tissue mask: optical density above " + Operations.plain(round(tissueCut, 3)) + " (white glass and fat excluded, " + Operations.plain(round(100.0 * tissuePixels / op.length, 1)) + "% of the " + basis + ")");
            macro.add("// tissue mask: summed RGB optical density > " + Operations.plain(round(tissueCut, 3)) + " (computed in the bridge)");
        }
        ByteProcessor restrict = tissueMask != null ? tissueMask : withinMask;
        // 3. threshold
        double cut = floatThreshold(work, method, restrict);
        var mask = new ByteProcessor(ww, wh); float[] wp = (float[]) work.getPixels(); byte[] mp = (byte[]) mask.getPixels();
        byte[] inside = restrict == null ? null : (byte[]) restrict.getPixels();
        for (int i = 0; i < wp.length; i++) mp[i] = (byte) (wp[i] > cut && (inside == null || inside[i] != 0) ? 255 : 0);
        steps.add("auto threshold " + method + (tissueMask != null ? " within the tissue" : within == null ? "" : " within the " + basis)); macro.add("setAutoThreshold(\"" + method + " dark\");"); macro.add("run(\"Convert to Mask\");");
        // 4. clean up: close, fill holes, open
        int radius = (int) Math.max(1, Math.round(workSigma));
        var rank = new RankFilters();
        rank.rank(mask, radius, RankFilters.MAX); rank.rank(mask, radius, RankFilters.MIN);
        fillHoles(mask);
        rank.rank(mask, radius, RankFilters.MIN); rank.rank(mask, radius, RankFilters.MAX);
        if (inside != null) for (int i = 0; i < mp.length; i++) if (inside[i] == 0) mp[i] = 0;
        steps.add("close, fill holes, open (radius " + radius * factor + " px)");
        macro.add("run(\"Options...\", \"iterations=" + radius * factor + " count=1 do=Close\");"); macro.add("run(\"Fill Holes\");"); macro.add("run(\"Options...\", \"iterations=" + radius * factor + " count=1 do=Open\");");
        // 5. keep the largest components, each at least minAreaFraction of the basis (tissue, selection or image)
        long basisWork = 0;
        if (inside == null) basisWork = (long) ww * wh; else for (byte b : inside) if (b != 0) basisWork++;
        int[] labels = new int[ww * wh]; var areas = label(mp, ww, wh, labels);
        if (areas.isEmpty()) throw new IllegalStateException("No region stood out from the background; try another method or stain.");
        var order = new ArrayList<Integer>(); for (int i = 0; i < areas.size(); i++) order.add(i);
        // Ties are broken by label (raster order), so the same image and arguments always keep the same regions.
        order.sort((a, b) -> areas.get(b).equals(areas.get(a)) ? Integer.compare(a, b) : Integer.compare(areas.get(b), areas.get(a)));
        var kept = new ArrayList<Integer>();
        for (int index : order) { if (kept.size() >= keep) break; if (areas.get(index) >= minFraction * basisWork) kept.add(index); }
        // Never return nothing when something stood out: the single largest component is kept even below the fraction.
        if (kept.isEmpty()) kept.add(order.getFirst());
        String basisNoun = tissueMask != null ? "tissue" : basis;
        steps.add("keep " + kept.size() + " of " + areas.size() + " components (up to " + keep + ", each at least " + Operations.plain(round(minFraction * 100, 2)) + "% of the " + basisNoun + ")");
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
            composite.setStrokeColor(Color.yellow); Operations.tag(composite, imp); manager.addRoi(composite); roiIndex = manager.getCount() - 1;
            if (parts.size() > 1) for (var part : parts) { var copy = (Roi) part.clone(); Operations.tag(copy, imp); manager.addRoi(copy); }
            macro.add("roiManager(\"Add\");"); macro.add("roiManager(\"rename\", \"" + Journal.escape(name) + "\");");
        }
        imp.setRoi((Roi) composite.clone());
        // 8. measurements (calibrated)
        Calibration cal = imp.getCalibration(); double pixelArea = cal.pixelWidth * cal.pixelHeight;
        long pixels = 0; for (byte b : km) if (b != 0) pixels++;
        double area = pixels * pixelArea;
        double basisArea, imageArea = (double) width * height * pixelArea, tissueArea = Double.NaN;
        if (tissueMask != null) {
            // The tissue area at full resolution: the work-resolution mask scaled back like the regions, so both fractions share one grid.
            ByteProcessor full = factor > 1 ? upscale((ByteProcessor) tissueMask.duplicate(), width, height) : tissueMask;
            long n = 0; for (byte b : (byte[]) full.getPixels()) if (b != 0) n++;
            tissueArea = n * pixelArea; basisArea = tissueArea; basis = "tissue";
        }
        else if (within != null) { var wm = new ByteProcessor(width, height); wm.setValue(255); wm.fill(within); long n = 0; for (byte b : (byte[]) wm.getPixels()) if (b != 0) n++; basisArea = n * pixelArea; }
        else basisArea = imageArea;
        Roi measured = (Roi) composite.clone(); measured.setImage(imp);
        double perimeter = measured.getLength();
        double hull = polygonArea(composite.getFloatConvexHull()) * pixelArea;
        source.setRoi(composite); double meanIntensity = source.getStats().mean; source.resetRoi();
        var result = new LinkedHashMap<String, Object>();
        result.put("regions", parts.size()); result.put("area", area); result.put("areaFraction", basisArea > 0 ? Math.min(1, area / basisArea) : 0.0); result.put("areaFractionOf", basis);
        result.put("areaFractionOfImage", imageArea > 0 ? area / imageArea : 0.0);
        if (tissueMask != null) { result.put("tissueArea", tissueArea); result.put("tissueFractionOfImage", imageArea > 0 ? tissueArea / imageArea : 0.0); result.put("tissueThreshold", tissueCut); }
        result.put("keepLargest", keep); result.put("minAreaFraction", minFraction);
        if (within != null) result.put("within", withinBasis);
        if (!adjusted.isEmpty()) result.put("adjusted", adjusted);
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
    /** Summed red + green + blue optical density (-log10 of transmitted light): about 0.1 for white glass or fat, higher for any stain. */
    static FloatProcessor opticalDensity(ColorProcessor cp) {
        int w = cp.getWidth(), h = cp.getHeight(); int[] px = (int[]) cp.getPixels(); float[] out = new float[w * h];
        double[] lut = new double[256]; for (int v = 0; v < 256; v++) lut[v] = -Math.log10((v + 1) / 256.0);
        for (int i = 0; i < px.length; i++) { int c = px[i]; out[i] = (float) (lut[(c >> 16) & 0xff] + lut[(c >> 8) & 0xff] + lut[c & 0xff]); }
        return new FloatProcessor(w, h, out);
    }
    /** Tissue/background cut on smoothed optical density: Triangle (a white-background peak with a stained tail), kept within 0.15-0.35 so a field of solid tissue is not split into "background" stroma. */
    static double tissueThreshold(FloatProcessor od) {
        float[] p = (float[]) od.getPixels();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (float v : p) { min = Math.min(min, v); max = Math.max(max, v); }
        if (!(max > min)) return 0.15;
        int[] histogram = new int[256]; double scale = 255.0 / (max - min);
        for (float v : p) histogram[(int) Math.min(255, (v - min) * scale)]++;
        double cut = min + (new AutoThresholder().getThreshold("Triangle", histogram) + 1) / scale;
        return Math.max(0.15, Math.min(0.35, cut));
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
    private Roi namedManagerRoi(String name, ImagePlus imp) {
        var manager = ops.managerOrNull(); if (manager == null) return null;
        for (var roi : manager.getRoisAsArray()) if (roi != null && name.equalsIgnoreCase(roi.getName()) && Operations.belongsTo(roi, imp.getID())) return roi;
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
        var mine = imageEntries(manager, imp, args);
        if (mine.rois().length == 0) throw new IllegalStateException("Segment the cells first.");
        Roi[] rois = mine.rois();
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
            manager.rename(mine.index()[i], newName);
            if (pos) { if (positiveIndices.size() < 500) positiveIndices.add(mine.index()[i]); positiveRois.add(roi); }
            var copy = (Roi) roi.clone(); copy.setName(newName); overlay.add(copy);
            table.addRow(); table.addValue("Index", mine.index()[i]); table.addLabel(newName); table.addValue("Class", pos ? 1 : 0); table.addValue(measurementHeading(measurement), values[i]);
        }
        imp.setOverlay(overlay);
        Pipeline.ownOverlayOnly(imp, manager);
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
        var entries = imageEntries(ops.managerOrNull(), imp, args);
        Roi[] all = entries.rois();
        if (all.length > MAX_OBJECTS) throw new IllegalArgumentException("The ROI Manager holds " + all.length + " objects; the limit is " + MAX_OBJECTS + ".");
        String scope = Operations.string(args, "scope", "image");
        if (!scope.equals("image") && !scope.equals("selection")) throw new IllegalArgumentException("scope must be image or selection.");
        Roi within = null;
        java.awt.geom.Rectangle2D.Double regionBounds = null;
        if (scope.equals("selection")) {
            within = imp.getRoi();
            if (within == null || !within.isArea()) throw new IllegalStateException("Select an area on the image first.");
        }
        if (args.has("region") && !args.get("region").isJsonNull()) {
            if (within != null) throw new IllegalArgumentException("Use either scope selection or region, not both.");
            JsonObject region = args.getAsJsonObject("region");
            double x = requiredFinite(region, "x"), y = requiredFinite(region, "y"), width = requiredFinite(region, "width"), height = requiredFinite(region, "height");
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > imp.getWidth() || y + height > imp.getHeight()) throw new IllegalArgumentException("region must be a positive rectangle inside the image, in pixels.");
            regionBounds = new java.awt.geom.Rectangle2D.Double(x, y, width, height);
            within = new Roi(x, y, width, height); scope = "region";
        }
        var included = new ArrayList<Roi>(); var originalIndices = new ArrayList<Integer>();
        for (int i = 0; i < all.length; i++) {
            // Use the actual mask centroid, not the bounding-box centre (which can lie outside a crescent-shaped object).
            double[] centroid = maskCentroid(all[i]);
            // Roi.containsPoint() in the installed ImageJ returns false for rectangles with floating bounds.
            boolean inside = regionBounds != null ? regionBounds.contains(centroid[0], centroid[1]) : within == null || within.contains((int) Math.floor(centroid[0]), (int) Math.floor(centroid[1]));
            if (inside) { included.add(all[i]); originalIndices.add(entries.index()[i]); }
        }
        Roi[] rois = included.toArray(Roi[]::new);
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
        for (int k = 0; k < Math.min(5, n); k++) { int i = order[k]; largest.add(entry(originalIndices.get(i), rois[i], area[i], mean[i])); largestRois.add(rois[i]); }
        for (int k = 0; k < Math.min(5, n); k++) { int i = order[n - 1 - k]; smallest.add(entry(originalIndices.get(i), rois[i], area[i], mean[i])); }
        result.put("largest", largest); result.put("smallest", smallest);
        result.put("scopeUsed", scope); result.put("totalForImage", all.length);
        if (within != null) result.put("region", Operations.roiJson(within));
        result.put("unit", cal.getUnit()); result.put("areaUnit", cal.getUnit() + "^2"); result.put("channel", channel);
        result.put("highlight", Viewer.roiHighlight(imp, largestRois, largestRois.size() == 1 ? "Largest object" : largestRois.size() + " largest objects", 5));
        if (n > 0 && Operations.bool(args, "plot", true) && Operations.guiVisible()) {
            showHistogram(imp, area, cal.getUnit()); result.put("plot", "Size distribution");
            // The plot window must not become the active image: the next "segment"/"count" still means the cells' image.
            if (imp.getWindow() != null) { WindowManager.setCurrentWindow(imp.getWindow()); imp.getWindow().toFront(); }
        }
        return result;
    }
    private static double requiredFinite(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("region requires a numeric " + key + ".");
        double value = args.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("region requires a finite " + key + ".");
        return value;
    }
    /** Centroid of the filled ROI mask, in uncalibrated image pixels, matching Analyze Particles' centroid scope rule. */
    static double[] maskCentroid(Roi roi) {
        Roi local = (Roi) roi.clone(); local.setImage(null);
        var stats = local.getStatistics();
        return new double[]{stats.xCentroid, stats.yCentroid};
    }
    /** "Show me the size distribution": an ImageJ histogram plot window of object areas, drawn from the same values as the result. */
    static void showHistogram(ImagePlus imp, double[] area, String unit) {
        // A small, crisp chart in Penny's colours in a non-focusable window. Not a PlotWindow: that is an ImageWindow and becomes
        // ImageJ's current image, so the next "remove the tiny ones" would act on the plot instead of the cells.
        String areaUnit = unit == null || unit.isBlank() || unit.startsWith("pixel") ? "px²" : unit.replace("micron", "µm") + "²";
        double[] values = area.clone();
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (histogramFrame == null || !histogramFrame.isDisplayable()) {
                histogramFrame = new javax.swing.JFrame("Size distribution");
                histogramFrame.setType(java.awt.Window.Type.UTILITY);
                histogramFrame.setFocusableWindowState(false);
                histogramFrame.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
                histogramChart = new SizeChart();
                histogramFrame.getContentPane().add(histogramChart);
                histogramFrame.getContentPane().setBackground(SizeChart.BACKGROUND);
            }
            histogramFrame.setTitle("Size distribution — " + (imp == null ? "" : imp.getTitle()));
            histogramChart.set(values, areaUnit);
            histogramFrame.pack();
            if (!histogramFrame.isVisible()) Pipeline.placeBeside(imp, histogramFrame);
            histogramFrame.setVisible(true);
            histogramChart.repaint();
        });
    }
    private static javax.swing.JFrame histogramFrame;
    private static SizeChart histogramChart;

    /** The size chart: histogram with rounded bars for 12+ objects, a dot plot below that; median marked; vector-drawn. */
    static final class SizeChart extends javax.swing.JComponent {
        static final Color BACKGROUND = new Color(17, 20, 24), MINT = new Color(92, 235, 199), GRID = new Color(255, 255, 255, 28), TEXT = new Color(235, 240, 238), MUTED = new Color(150, 160, 158);
        private double[] values = new double[0]; private String unit = "px²";
        SizeChart() { setPreferredSize(new java.awt.Dimension(420, 250)); setOpaque(true); }
        void set(double[] values, String unit) { this.values = values.clone(); Arrays.sort(this.values); this.unit = unit; }
        static double niceStep(double range, int target) {
            double raw = range / Math.max(1, target), power = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1e-12)))), f = raw / power;
            return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * power;
        }
        static String format(double v) { if (v == Math.rint(v) && Math.abs(v) < 1e7) return String.format(Locale.ROOT, Math.abs(v) >= 1000 ? "%,.0f" : "%.0f", v); return Math.abs(v) >= 1000 ? String.format(Locale.ROOT, "%,.0f", v) : Math.abs(v) >= 10 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v); }
        @Override protected void paintComponent(java.awt.Graphics graphics) {
            var g = (java.awt.Graphics2D) graphics.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(); g.setColor(BACKGROUND); g.fillRect(0, 0, w, h);
            int n = values.length; if (n == 0) { g.dispose(); return; }
            double median = n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2, min = values[0], max = values[n - 1];
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 15)); g.setColor(TEXT);
            g.drawString(n + (n == 1 ? " object" : " objects"), 18, 28);
            int titleWidth = g.getFontMetrics().stringWidth(n + (n == 1 ? " object" : " objects"));
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13)); g.setColor(MUTED);
            g.drawString("  ·  median " + format(median) + " " + unit + "  ·  " + format(min) + "–" + format(max), 18 + titleWidth, 28);
            int left = 46, right = w - 18, top = 48, bottom = h - 38;
            double step = niceStep(Math.max(1e-9, max - min), 5), lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step; if (hi <= lo) hi = lo + step;
            final double fLo = lo, fHi = hi;
            java.util.function.DoubleUnaryOperator x = v -> left + (v - fLo) / (fHi - fLo) * (right - left);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
            for (double t = lo; t <= hi + step / 2; t += step) {
                int px = (int) Math.round(x.applyAsDouble(t)); g.setColor(GRID); g.drawLine(px, top, px, bottom);
                g.setColor(MUTED); String label = format(t); g.drawString(label, px - g.getFontMetrics().stringWidth(label) / 2, bottom + 16);
            }
            g.setColor(MUTED); g.drawString("area (" + unit + ")", right - g.getFontMetrics().stringWidth("area (" + unit + ")"), h - 8);
            g.setColor(new Color(255, 255, 255, 60)); g.drawLine(left, bottom, right, bottom);
            if (n >= 12) {
                int bins = Math.max(6, Math.min(24, (int) Math.round(Math.sqrt(n) * 1.5)));
                int[] counts = new int[bins]; double width = (hi - lo) / bins;
                for (double v : values) counts[(int) Math.min(bins - 1, Math.max(0, (v - lo) / width))]++;
                int peak = Arrays.stream(counts).max().orElse(1);
                double barWidth = (right - left) / (double) bins;
                for (int i = 0; i < bins; i++) {
                    if (counts[i] == 0) continue;
                    double barHeight = (bottom - top) * counts[i] / (double) peak;
                    var bar = new java.awt.geom.RoundRectangle2D.Double(left + i * barWidth + 1.5, bottom - barHeight, barWidth - 3, barHeight + 4, 6, 6);
                    g.setPaint(new java.awt.GradientPaint(0, (float) (bottom - barHeight), MINT, 0, bottom, new Color(92, 235, 199, 120)));
                    var clip = g.getClip(); g.clipRect(left, top - 2, right - left, bottom - top + 2); g.fill(bar); g.setClip(clip);
                }
                g.setColor(MUTED); g.drawString(String.valueOf(peak), 18, top + 10); g.drawString("0", 30, bottom);
            } else {
                // Few objects: every object is a dot (a histogram of four values says nothing).
                int lane = (top + bottom) / 2;
                for (int i = 0; i < n; i++) {
                    double px = x.applyAsDouble(values[i]); double py = lane + ((i % 3) - 1) * 14;
                    g.setColor(new Color(92, 235, 199, 70)); g.fill(new java.awt.geom.Ellipse2D.Double(px - 11, py - 11, 22, 22));
                    g.setColor(MINT); g.fill(new java.awt.geom.Ellipse2D.Double(px - 6, py - 6, 12, 12));
                }
            }
            int mx = (int) Math.round(x.applyAsDouble(median));
            g.setColor(new Color(255, 255, 255, 170)); g.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND, 1, new float[]{4, 4}, 0));
            g.drawLine(mx, top - 4, mx, bottom);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11)); g.drawString("median", Math.min(right - 40, mx + 5), top + 6);
            g.dispose();
        }
    }

    /** The image pixel the researcher means by "this/these": explicit x/y, else the live pointer over the canvas, else its last position there. */
    static java.awt.Point pointedAt(ImagePlus imp, JsonObject args) {
        if (args.has("x") && args.has("y")) return new java.awt.Point((int) Operations.number(args, "x", 0), (int) Operations.number(args, "y", 0));
        var pointer = Viewer.pointer(imp);
        Object x = Boolean.TRUE.equals(pointer.get("overCanvas")) ? pointer.get("x") : pointer.get("lastX"), y = Boolean.TRUE.equals(pointer.get("overCanvas")) ? pointer.get("y") : pointer.get("lastY");
        if (x instanceof Number px && y instanceof Number py) return new java.awt.Point(px.intValue(), py.intValue());
        throw new IllegalStateException("Point at the cells on the image first.");
    }
    /** Objects at the point, else the nearest centroid within twice the typical radius; "these two" also takes the next nearest touching neighbour. */
    static List<Integer> objectsAt(Roi[] rois, java.awt.Point point, double medianArea, int count) {
        var hits = new ArrayList<Integer>();
        for (int i = 0; i < rois.length; i++) if (rois[i].contains(point.x, point.y)) hits.add(i);
        double reach = 2 * Math.sqrt(medianArea / Math.PI) + 3;
        Integer[] order = new Integer[rois.length]; for (int i = 0; i < order.length; i++) order[i] = i;
        double[] distance = new double[rois.length];
        for (int i = 0; i < rois.length; i++) { var b = rois[i].getBounds(); distance[i] = Math.hypot(b.getCenterX() - point.x, b.getCenterY() - point.y); }
        Arrays.sort(order, Comparator.comparingDouble(i -> distance[i]));
        for (int i : order) { if (hits.size() >= count) break; if (!hits.contains(i) && distance[i] <= reach) hits.add(i); }
        return hits;
    }

    // =====================================================================================================
    // filter_objects: "remove the tiny ones" without re-segmenting
    // =====================================================================================================
    Map<String, Object> filterObjects(JsonObject args) {
        var imp = ops.active();
        var manager = ops.managerOrNull();
        var entries = imageEntries(manager, imp, args); Roi[] rois = entries.rois(); int n = rois.length;
        if (n == 0) throw new IllegalStateException("Segment the cells first.");
        if (n > MAX_OBJECTS) throw new IllegalArgumentException("The ROI Manager holds " + n + " objects; the limit is " + MAX_OBJECTS + ".");
        ImageProcessor ip = measurementProcessor(imp, imp.getC()); Calibration cal = imp.getCalibration();
        double[] area = new double[n], circ = new double[n];
        for (int i = 0; i < n; i++) {
            Roi roi = (Roi) rois[i].clone(); roi.setImage(imp); ip.setRoi(roi);
            area[i] = ImageStatistics.getStatistics(ip, Measurements.AREA, cal).area; ip.resetRoi();
            double perimeter = roi.getLength(); circ[i] = perimeter > 0 ? Math.min(1, 4 * Math.PI * area[i] / (perimeter * perimeter)) : 0;
        }
        double median = (double) describeValues(area).get("median");
        String preset = Operations.string(args, "remove", "tiny").toLowerCase(Locale.ROOT);
        // The typical size is the robust size mode, not the median: when specks outnumber the cells the median is a speck.
        boolean fractionGiven = args.has("fraction") && !args.get("fraction").isJsonNull();
        var sizes = sizeMode(area, Operations.number(args, "fraction", 0.35), fractionGiven);
        boolean smallPreset = preset.equals("tiny") || preset.equals("small"), largePreset = preset.equals("huge") || preset.equals("large");
        double minArea = Operations.number(args, "minArea", smallPreset ? sizes.cutoff() : 0);
        double maxArea = Operations.number(args, "maxArea", largePreset ? sizes.mode() * 3 : Double.POSITIVE_INFINITY);
        String cutMethod = args.has("minArea") || args.has("maxArea") ? "explicit" : smallPreset ? sizes.method() : largePreset ? "3 x size mode (" + sizes.modeMethod() + ")" : "none";
        double minCirc = Operations.number(args, "minCircularity", 0);
        var keep = new ArrayList<Roi>(); var removedIndices = new ArrayList<Integer>();
        if ("pointer".equals(Operations.string(args, "at", "")) || args.has("x") && args.has("y")) {
            // "Remove this one": only the object(s) under the pointer.
            var hits = objectsAt(rois, pointedAt(imp, args), median, Operations.integer(args, "count", 1, 1, 20));
            if (hits.isEmpty()) throw new IllegalStateException("No cell under the pointer; point at the one to remove.");
            for (int i = 0; i < n; i++) if (hits.contains(i)) removedIndices.add(i); else keep.add(rois[i]);
            minArea = 0; maxArea = Double.POSITIVE_INFINITY; cutMethod = "pointer";
        }
        else for (int i = 0; i < n; i++) { if (area[i] >= minArea && area[i] <= maxArea && circ[i] >= minCirc) keep.add(rois[i]); else removedIndices.add(i); }
        replaceManager(imp, manager, rois, keep);
        var result = new LinkedHashMap<String, Object>();
        result.put("before", n); result.put("removed", removedIndices.size()); result.put("kept", keep.size());
        result.put("minArea", minArea); result.put("maxArea", Double.isInfinite(maxArea) ? "Infinity" : maxArea); result.put("minCircularity", minCirc);
        result.put("medianArea", median); result.put("sizeMode", sizes.mode()); result.put("bimodal", sizes.bimodal());
        result.put("cutoff", smallPreset || args.has("minArea") ? minArea : largePreset || args.has("maxArea") ? (Double.isInfinite(maxArea) ? "Infinity" : maxArea) : 0.0);
        result.put("method", cutMethod); result.put("unit", cal.getUnit());
        journal.record("filter_objects", "// filter_objects: keep area " + Operations.plain(round(minArea, 2)) + "-" + (Double.isInfinite(maxArea) ? "Infinity" : Operations.plain(round(maxArea, 2))) + ", circularity >= " + Operations.plain(minCirc) + ": " + keep.size() + " of " + n
            + "\nrun(\"Analyze Particles...\", \"size=" + Operations.plain(round(minArea, 2)) + "-" + (Double.isInfinite(maxArea) ? "Infinity" : Operations.plain(round(maxArea, 2))) + " circularity=" + Operations.plain(minCirc) + "-1.00 add\");");
        result.put("highlight", Viewer.roiHighlight(imp, keep, keep.size() + " kept, " + removedIndices.size() + " removed", 120));
        return result;
    }

    // =====================================================================================================
    // split_objects: "those cells are touching, separate them" (watershed inside oversized objects)
    // =====================================================================================================
    Map<String, Object> splitObjects(JsonObject args) {
        var imp = ops.active();
        var manager = ops.managerOrNull();
        var entries = imageEntries(manager, imp, args); Roi[] rois = entries.rois(); int n = rois.length;
        if (n == 0) throw new IllegalStateException("Segment the cells first.");
        if (n > MAX_OBJECTS) throw new IllegalArgumentException("The ROI Manager holds " + n + " objects; the limit is " + MAX_OBJECTS + ".");
        double[] area = new double[n];
        for (int i = 0; i < n; i++) area[i] = rois[i].getStatistics().pixelCount;
        double median = (double) describeValues(area).get("median");
        double factor = Operations.number(args, "factor", 1.5);
        Set<Integer> targets = new TreeSet<>();
        Roi selection = imp.getRoi();
        boolean pointed = "pointer".equals(Operations.string(args, "at", "")) || args.has("x") && args.has("y");
        if (args.has("indices")) for (var e : args.getAsJsonArray("indices")) targets.add(entries.requireLocalIndex(e.getAsInt()));
        else if (pointed) { var hits = objectsAt(rois, pointedAt(imp, args), median, 1); if (hits.isEmpty()) throw new IllegalStateException("No cell under the pointer; point at the cells to separate."); targets.addAll(hits); }
        else if (selection != null && selection.isArea() && !Operations.bool(args, "all", false)) {
            for (int i = 0; i < n; i++) { var b = rois[i].getBounds(); if (selection.contains((int) b.getCenterX(), (int) b.getCenterY())) targets.add(i); }
        }
        else for (int i = 0; i < n; i++) if (area[i] > factor * median) targets.add(i);
        var out = new ArrayList<Roi>(); var newParts = new ArrayList<Roi>(); int split = 0;
        // Objects the researcher pointed at, selected or listed are asserted to be several cells: split as gently as works.
        boolean asserted = pointed || args.has("indices") || selection != null && selection.isArea() && !Operations.bool(args, "all", false);
        int minPart = Math.max(4, (int) Math.round(median * 0.15));
        double usedTolerance = 0.5, splitRatio = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!targets.contains(i)) { out.add(rois[i]); continue; }
            double[] tolerance = {0.5};
            var pieces = asserted ? Pipeline.splitAsserted(rois[i], minPart, tolerance) : Pipeline.splitRoi(rois[i], 0.5, minPart);
            if (pieces.isEmpty()) { out.add(rois[i]); continue; }
            for (var piece : pieces) Operations.tag(piece, imp);
            split++; out.addAll(pieces); newParts.addAll(pieces);
            usedTolerance = Math.min(usedTolerance, tolerance[0]); splitRatio = Math.min(splitRatio, area[i] / median);
        }
        replaceManager(imp, manager, rois, out);
        var result = new LinkedHashMap<String, Object>();
        result.put("before", n); result.put("after", out.size()); result.put("checked", targets.size()); result.put("split", split);
        result.put("scope", args.has("indices") ? "indices" : pointed ? "pointer" : selection != null && selection.isArea() && !Operations.bool(args, "all", false) ? "selection" : "oversized");
        result.put("medianArea", median); result.put("tolerance", usedTolerance); if (split > 0) result.put("splitRatio", splitRatio);
        journal.record("split_objects", "// split_objects: watershed inside " + targets.size() + " objects (" + result.get("scope") + "), " + split + " split: " + n + " -> " + out.size() + "\nrun(\"Watershed\");");
        result.put("highlight", Viewer.roiHighlight(imp, newParts.isEmpty() ? out : newParts, split == 0 ? "Nothing to split" : split + " split, " + out.size() + " cells", 120));
        return result;
    }
    // =====================================================================================================
    // explain_object: "why did you count this as a cell?"
    // =====================================================================================================
    Map<String, Object> explainObject(JsonObject args, Map<String, Object> analysis) {
        var imp = ops.active();
        var manager = ops.managerOrNull();
        var entries = imageEntries(manager, imp, args); Roi[] rois = entries.rois(); int n = rois.length;
        if (n == 0) throw new IllegalStateException("Segment the cells first.");
        double[] px = new double[n]; for (int i = 0; i < n; i++) px[i] = rois[i].getStatistics().pixelCount;
        double median = (double) describeValues(px).get("median");
        int index;
        if (args.has("index")) index = entries.requireLocalIndex(Operations.integer(args, "index", 0, 0, Integer.MAX_VALUE));
        else { var hits = objectsAt(rois, pointedAt(imp, args), median, 1); if (hits.isEmpty()) throw new IllegalStateException("No detected object under the pointer, so it was not counted."); index = hits.getFirst(); }
        Roi roi = (Roi) rois[index].clone(); roi.setImage(imp);
        Calibration cal = imp.getCalibration();
        @SuppressWarnings("unchecked") var params = analysis == null ? Map.<String, Object>of() : (Map<String, Object>) analysis.get("params");
        int channel = params.get("channel") instanceof Number c ? Math.min(c.intValue(), Math.max(1, imp.getNChannels())) : imp.getC();
        ImageProcessor ip = measurementProcessor(imp, channel); ip.setRoi(roi);
        var stats = ImageStatistics.getStatistics(ip, Measurements.AREA | Measurements.MEAN | Measurements.MIN_MAX, cal); ip.resetRoi();
        double perimeter = roi.getLength(), circularity = perimeter > 0 ? Math.min(1, 4 * Math.PI * px[index] / (perimeter * perimeter)) : 0;
        int smaller = 0; for (double v : px) if (v < px[index]) smaller++;
        var result = new LinkedHashMap<String, Object>();
        result.put("index", entries.index()[index]); result.put("name", rois[index].getName()); result.put("channel", channel);
        result.put("area", stats.area); result.put("unit", cal.getUnit()); result.put("mean", stats.mean); result.put("min", stats.min); result.put("max", stats.max);
        result.put("circularity", circularity); result.put("areaPercentile", Math.round(100.0 * smaller / Math.max(1, n - 1)));
        result.put("relativeToMedian", px[index] / median);
        var reasons = new ArrayList<String>();
        if (!params.isEmpty()) {
            reasons.add("its pixels in channel " + channel + " were above the " + params.get("method") + " auto threshold" + (Boolean.TRUE.equals(params.get("dark")) ? " (bright on dark)" : ""));
            reasons.add("its area " + Operations.plain(round(stats.area, 2)) + " " + cal.getUnit() + "² passed the minimum of " + params.get("minArea") + " " + cal.getUnit() + "²");
            if (params.get("minCircularity") instanceof Number c && c.doubleValue() > 0) reasons.add("circularity " + Operations.plain(round(circularity, 2)) + " ≥ " + c);
        } else reasons.add("it is an object in the ROI Manager");
        if (px[index] > 1.8 * median) reasons.add("it is " + Operations.plain(round(px[index] / median, 1)) + "× the median size, so it may be touching cells");
        if (px[index] < 0.4 * median) reasons.add("it is much smaller than typical (" + Operations.plain(round(px[index] / median, 2)) + "× the median), possibly debris");
        result.put("reasons", reasons);
        result.put("highlight", Viewer.roiHighlight(imp, List.of(rois[index]), "This object", 1));
        return result;
    }

    // =====================================================================================================
    // merge_objects: "these two are one cell" (the inverse correction)
    // =====================================================================================================
    Map<String, Object> mergeObjects(JsonObject args) {
        var imp = ops.active();
        var manager = ops.managerOrNull();
        var entries = imageEntries(manager, imp, args); Roi[] rois = entries.rois(); int n = rois.length;
        if (n < 2) throw new IllegalStateException("Segment the cells first.");
        double[] area = new double[n]; for (int i = 0; i < n; i++) area[i] = rois[i].getStatistics().pixelCount;
        double median = (double) describeValues(area).get("median");
        List<Integer> targets = new ArrayList<>();
        Roi selection = imp.getRoi();
        if (args.has("indices")) for (var e : args.getAsJsonArray("indices")) targets.add(entries.requireLocalIndex(e.getAsInt()));
        else if (selection != null && selection.isArea() && !"pointer".equals(Operations.string(args, "at", ""))) { for (int i = 0; i < n; i++) { var b = rois[i].getBounds(); if (selection.contains((int) b.getCenterX(), (int) b.getCenterY())) targets.add(i); } }
        else targets = objectsAt(rois, pointedAt(imp, args), median, Operations.integer(args, "count", 2, 2, 20));
        if (targets.size() < 2) throw new IllegalStateException("Point between the two cells to merge, or select them.");
        var parts = new ArrayList<Roi>(); for (int i : targets) parts.add(rois[i]);
        // Close the gap between touching pieces (watershed lines are 1 px) so the merged cell is one solid outline.
        var union = combine(parts); var b = union.getBounds();
        var mask = new ByteProcessor(b.width + 4, b.height + 4); mask.setColor(255);
        for (var part : parts) { var local = (Roi) part.clone(); local.setLocation(local.getXBase() - b.x + 2, local.getYBase() - b.y + 2); mask.fill(local); }
        mask.dilate(); mask.erode();
        Roi merged = maskToRoi(mask); merged.setLocation(merged.getXBase() + b.x - 2, merged.getYBase() + b.y - 2); Operations.tag(merged, imp);
        var out = new ArrayList<Roi>(); boolean placed = false;
        for (int i = 0; i < n; i++) { if (!targets.contains(i)) out.add(rois[i]); else if (!placed) { out.add(merged); placed = true; } }
        replaceManager(imp, manager, rois, out);
        var result = new LinkedHashMap<String, Object>();
        result.put("before", n); result.put("after", out.size()); result.put("merged", targets.size());
        journal.record("merge_objects", "// merge_objects: " + targets.size() + " objects joined into one: " + n + " -> " + out.size());
        result.put("highlight", Viewer.roiHighlight(imp, List.of(merged), "Merged into one cell", 1));
        return result;
    }
    /** Rewrites the ROI Manager and the image overlay with the given objects, numbered in order. */
    /** The ROI Manager entries that belong to {@code imp} (untagged entries belong to every image), with their manager indices; allImages:true takes every entry. */
    record Entries(Roi[] rois, int[] index) {
        int requireLocalIndex(int managerIndex) {
            int local = Arrays.binarySearch(index, managerIndex);
            if (local < 0) throw new IllegalArgumentException("ROI Manager object " + managerIndex + " does not belong to the active image.");
            return local;
        }
    }
    static Entries imageEntries(RoiManager manager, ImagePlus imp, JsonObject args) {
        if (manager == null) return new Entries(new Roi[0], new int[0]);
        Roi[] all = manager.getRoisAsArray(); boolean every = imp == null || Operations.bool(args, "allImages", false);
        var rois = new ArrayList<Roi>(); var index = new ArrayList<Integer>();
        for (int i = 0; i < all.length; i++) if (every || Operations.belongsTo(all[i], imp.getID())) { rois.add(all[i]); index.add(i); }
        return new Entries(rois.toArray(new Roi[0]), index.stream().mapToInt(Integer::intValue).toArray());
    }
    /** Rewrites this image's ROI Manager entries and its overlay with the given objects, numbered in order; other images' entries stay, first and unchanged. */
    private static void replaceManager(ImagePlus imp, RoiManager manager, Roi[] processed, List<Roi> rois) {
        var replaced = Collections.newSetFromMap(new IdentityHashMap<Roi, Boolean>()); replaced.addAll(Arrays.asList(processed));
        var others = new ArrayList<Operations.ManagerRoi>();
        var current = manager.getRoisAsArray();
        for (int i = 0; i < current.length; i++) if (!replaced.contains(current[i])) others.add(Operations.captureManagerRoi(manager, i));
        manager.reset();
        for (var entry : others) entry.appendTo(manager);
        var overlay = new Overlay();
        for (int i = 0; i < rois.size(); i++) {
            Roi roi = rois.get(i); roi.setName(String.format(Locale.ROOT, "Cell %d", i + 1));
            if (roi.getStrokeColor() == null) roi.setStrokeColor(Color.CYAN);
            if (replaced.contains(roi)) new Operations.ManagerRoi(roi, roi.getName()).appendTo(manager); else manager.addRoi(roi);
            var copy = (Roi) roi.clone(); overlay.add(copy);
        }
        imp.setOverlay(overlay);
        Pipeline.ownOverlayOnly(imp, manager);
    }
    private static Map<String, Object> entry(int index, Roi roi, double area, double mean) {
        var m = new LinkedHashMap<String, Object>(); m.put("index", index); m.put("name", roi.getName()); m.put("area", area); m.put("mean", mean);
        var b = roi.getBounds(); m.put("x", b.x); m.put("y", b.y); m.put("width", b.width); m.put("height", b.height);
        return m;
    }
    /**
     * The typical object size and the "tiny" cutoff below it. A log-area Otsu split decides whether the sizes are bimodal (specks and
     * cells): the two class medians at least 3x apart, a near-empty valley between them (the middle third of that log interval holds
     * under half its share of objects), and at least max(2, 10%) objects in the upper class. Bimodal: the mode is the upper class
     * median and the cutoff the split (at most half the mode). Otherwise the mode is the median of the largest 60% and the cutoff
     * {@code fraction} x mode. An explicit fraction always uses fraction x mode.
     */
    record SizeMode(double mode, double cutoff, boolean bimodal, String method, String modeMethod) {}
    static SizeMode sizeMode(double[] areas, double fraction, boolean fractionGiven) {
        double[] sorted = Arrays.stream(areas).filter(v -> v > 0 && Double.isFinite(v)).sorted().toArray();
        if (sorted.length == 0) return new SizeMode(0, 0, false, "no sizes", "no sizes");
        int m = sorted.length;
        double upperMedian = medianOf(sorted, (int) Math.floor(m * 0.4), m);
        if (m >= 4) {
            double[] logs = new double[m]; for (int i = 0; i < m; i++) logs[i] = Math.log(sorted[i]);
            double[] prefix = new double[m + 1]; for (int i = 0; i < m; i++) prefix[i + 1] = prefix[i] + logs[i];
            int split = -1; double best = -1;
            for (int k = 1; k < m; k++) {
                if (logs[k] == logs[k - 1]) continue;
                double w0 = k / (double) m, w1 = 1 - w0, m0 = prefix[k] / k, m1 = (prefix[m] - prefix[k]) / (m - k);
                double between = w0 * w1 * (m0 - m1) * (m0 - m1);
                if (between > best) { best = between; split = k; }
            }
            if (split > 0) {
                int upper = m - split;
                double lowMedian = Math.log(medianOf(sorted, 0, split)), highMedian = Math.log(medianOf(sorted, split, m));
                double third = (highMedian - lowMedian) / 3; int band = 0, span = 0;
                for (double v : logs) { if (v >= lowMedian && v <= highMedian) span++; if (v > lowMedian + third && v < highMedian - third) band++; }
                boolean valley = band < 0.5 * span / 3.0;
                if (highMedian - lowMedian >= Math.log(3) && valley && upper >= Math.max(2, Math.ceil(0.1 * m))) {
                    double mode = Math.exp(highMedian), cut = Math.min(Math.sqrt(sorted[split - 1] * sorted[split]), 0.5 * mode);
                    if (fractionGiven) return new SizeMode(mode, fraction * mode, true, Operations.plain(fraction) + " x size mode (upper size class median)", "upper size class median");
                    return new SizeMode(mode, cut, true, "log-area Otsu split (bimodal: " + split + " small, " + upper + " typical)", "upper size class median");
                }
            }
        }
        return new SizeMode(upperMedian, fraction * upperMedian, false, Operations.plain(fraction) + " x median of the largest 60%", "median of the largest 60%");
    }
    private static double medianOf(double[] sorted, int from, int to) {
        int n = to - from; if (n <= 0) return 0;
        return n % 2 == 1 ? sorted[from + n / 2] : (sorted[from + n / 2 - 1] + sorted[from + n / 2]) / 2;
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
