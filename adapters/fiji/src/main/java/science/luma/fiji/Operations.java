package science.luma.fiji;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.*;
import ij.io.FileInfo;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.ZProjector;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.frame.RoiManager;
import ij.process.AutoThresholder;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.imageio.ImageIO;

/** ImageJ1 image operations. Exact ImageJ values only; nothing scientific is invented here. Runs on the EDT unless noted. */
final class Operations {
    private final Journal journal;
    private RoiManager hiddenManager;

    Operations(Journal journal) { this.journal = journal; }

    // ----- state -----
    static boolean guiVisible() { var ij = IJ.getInstance(); return ij != null && ij.isShowing(); }
    ImagePlus active() {
        var imp = WindowManager.getCurrentImage();
        if (imp == null) throw new IllegalStateException("Open an image in Fiji first.");
        return imp;
    }
    ImagePlus activeOrNull() { return WindowManager.getCurrentImage(); }
    List<ImagePlus> images() {
        var result = new ArrayList<ImagePlus>();
        int[] ids = WindowManager.getIDList();
        if (ids != null) for (int id : ids) { var imp = WindowManager.getImage(id); if (imp != null) result.add(imp); }
        // Windows ImageJ's list missed (batch-mode images shown later, importer series) still count as open images.
        for (var window : java.awt.Window.getWindows()) {
            if (window instanceof ImageWindow imageWindow && window.isShowing()) { var imp = imageWindow.getImagePlus(); if (imp != null && result.stream().noneMatch(i -> i == imp)) result.add(imp); }
        }
        var current = WindowManager.getCurrentImage();
        if (current != null && result.stream().noneMatch(i -> i == current)) result.addFirst(current);
        result.sort(Comparator.comparing(imp -> imp.getWindow() instanceof PlotWindow ? 1 : 0));
        return result;
    }
    static String kind(ImagePlus imp) { return imp.getWindow() instanceof PlotWindow ? "plot" : "image"; }
    ImagePlus find(JsonObject args) {
        if (args.has("id")) { int id = args.get("id").getAsInt(); for (var imp : images()) if (imp.getID() == id) return imp; throw new IllegalArgumentException("No open image has id " + id + "."); }
        if (args.has("title")) { String title = args.get("title").getAsString(); for (var imp : images()) if (title.equals(imp.getTitle())) return imp; throw new IllegalArgumentException("No open image is titled \"" + title + "\"."); }
        return active();
    }
    void show(ImagePlus imp) { if (guiVisible()) imp.show(); else WindowManager.setTempCurrentImage(imp); }
    /** The ROI Manager: the researcher's window when Fiji is visible, otherwise a hidden instance (tests). Null only when headless. */
    RoiManager manager() {
        var manager = RoiManager.getRawInstance();
        if (manager != null) return manager;
        if (guiVisible()) return RoiManager.getRoiManager();
        if (hiddenManager == null && !GraphicsEnvironment.isHeadless()) hiddenManager = new RoiManager(true);
        return hiddenManager;
    }
    RoiManager managerOrNull() { var manager = RoiManager.getRawInstance(); return manager != null ? manager : hiddenManager; }
    static ResultsTable results() { return Analyzer.getResultsTable(); }
    static void showResults(ResultsTable table) { if (IJ.isResultsWindow() || guiVisible()) table.show("Results"); }

    /** ROI Manager entries the bridge adds are tagged with the image they came from, so measures, highlights, checkpoints and undo stay per image. */
    static final String ROI_IMAGE_PROPERTY = "luma.imageId";
    static void tag(Roi roi, ImagePlus owner) { if (roi != null && owner != null) roi.setProperty(ROI_IMAGE_PROPERTY, String.valueOf(owner.getID())); }
    /** Untagged entries (added by the researcher) belong to every image; tagged ones only to theirs. */
    static boolean belongsTo(Roi roi, int imageId) { String tag = roi == null ? null : roi.getProperty(ROI_IMAGE_PROPERTY); return tag == null || tag.isBlank() || tag.equals(String.valueOf(imageId)); }
    Map<String, Object> counts(ImagePlus imp) {
        var result = new LinkedHashMap<String, Object>();
        var overlay = imp == null ? null : imp.getOverlay();
        var manager = managerOrNull();
        result.put("overlay", overlay == null ? 0 : overlay.size());
        result.put("roiManager", manager == null ? 0 : manager.getCount());
        int forImage = 0; if (manager != null && imp != null) for (var roi : manager.getRoisAsArray()) if (belongsTo(roi, imp.getID())) forImage++;
        result.put("roiManagerForImage", forImage);
        result.put("resultsRows", results().size());
        return result;
    }
    static Map<String, Object> describe(ImagePlus imp) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", imp.getID()); result.put("title", imp.getTitle()); result.put("kind", kind(imp)); result.put("width", imp.getWidth()); result.put("height", imp.getHeight());
        result.put("channels", imp.getNChannels()); result.put("slices", imp.getNSlices()); result.put("frames", imp.getNFrames()); result.put("bitDepth", imp.getBitDepth());
        result.put("type", typeName(imp.getType()));
        Calibration cal = imp.getCalibration();
        result.put("calibration", Map.of("pixelWidth", cal.pixelWidth, "pixelHeight", cal.pixelHeight, "unit", cal.getUnit()));
        result.put("path", path(imp)); result.put("changes", imp.changes);
        result.put("position", Map.of("channel", imp.getC(), "slice", imp.getZ(), "frame", imp.getT()));
        result.put("displayRange", List.of(imp.getDisplayRangeMin(), imp.getDisplayRangeMax()));
        var ip = imp.getProcessor();
        result.put("threshold", ip == null || ip.getMinThreshold() == ImageProcessor.NO_THRESHOLD ? null : Map.of("lower", ip.getMinThreshold(), "upper", ip.getMaxThreshold()));
        result.put("invertedLut", ip != null && ip.isInvertedLut());
        result.put("channels", channelInfo(imp));
        return result;
    }
    /** Per-channel LUT colour name and slice label, so "the nuclei channel" can be resolved without guessing. */
    static List<Map<String, Object>> channelInfo(ImagePlus imp) {
        var result = new ArrayList<Map<String, Object>>();
        int n = Math.max(1, imp.getNChannels());
        ij.process.LUT[] luts = null;
        try { luts = imp.getType() == ImagePlus.COLOR_RGB ? null : imp.getLuts(); } catch (RuntimeException ignored) { /* no LUTs */ }
        for (int c = 1; c <= n; c++) {
            var row = new LinkedHashMap<String, Object>(); row.put("index", c);
            row.put("lut", imp.getType() == ImagePlus.COLOR_RGB ? "rgb" : luts != null && luts.length >= c && luts[c - 1] != null ? lutName(luts[c - 1]) : "unknown");
            String label = null;
            try { label = imp.getStack().getSliceLabel(imp.getStackIndex(c, imp.getZ(), imp.getT())); } catch (RuntimeException ignored) { /* no labels */ }
            row.put("label", label == null ? "" : label.lines().findFirst().orElse("").strip());
            result.add(row);
        }
        return result;
    }
    static String lutName(ij.process.LUT lut) {
        int r = lut.getRed(255), g = lut.getGreen(255), b = lut.getBlue(255); int max = Math.max(r, Math.max(g, b));
        if (max < 32) return "black";
        boolean red = r > 0.6 * max, green = g > 0.6 * max, blue = b > 0.6 * max;
        if (red && green && blue) return "grays"; if (red && green) return "yellow"; if (red && blue) return "magenta"; if (green && blue) return "cyan";
        return red ? "red" : green ? "green" : blue ? "blue" : "other";
    }
    // Ruifrok & Johnston optical-density vectors (hematoxylin first), as in Fiji's Colour Deconvolution "H&E" and "H DAB".
    static final double[][] HE_VECTORS = {{0.644211, 0.716556, 0.266844}, {0.092789, 0.954111, 0.283111}};
    static final double[][] HDAB_VECTORS = {{0.650, 0.704, 0.286}, {0.268, 0.570, 0.776}};
    record Hematoxylin(ImagePlus image, String vectors, double dabFraction) {}
    /** Fraction of stained pixels (total OD > 0.15) that look DAB-brown (blue absorbed much more than red). */
    static double dabFraction(ColorProcessor cp) {
        int[] px = (int[]) cp.getPixels(); long stained = 0, brown = 0;
        for (int i = 0; i < px.length; i += 1 + px.length / 400_000) {
            int c = px[i]; double r = -Math.log10((((c >> 16) & 0xff) + 1) / 256.0), g = -Math.log10((((c >> 8) & 0xff) + 1) / 256.0), b = -Math.log10(((c & 0xff) + 1) / 256.0);
            if (r + g + b < 0.15) continue; stained++;
            // DAB absorbs blue far more than red and green; eosin (pink) absorbs green most, hematoxylin (blue/purple) absorbs red most.
            if (b > 1.25 * r && b > 1.2 * g && b > 0.25) brown++;
        }
        return stained == 0 ? 0 : (double) brown / stained;
    }
    /** "Find the nuclei" on an RGB brightfield image: an 8-bit hematoxylin optical-density image (bright = more hematoxylin) of the current
     *  plane, deconvolved with H-DAB vectors when at least 10% of the stained pixels look DAB-brown, else H&E. Calibration is copied. */
    static Hematoxylin hematoxylin(ImagePlus imp) {
        if (!(imp.getProcessor() instanceof ColorProcessor cp)) throw new IllegalArgumentException("Colour deconvolution needs an RGB image.");
        double dab = dabFraction(cp); boolean hdab = dab >= 0.10;
        var od = Analysis.deconvolve(cp, hdab ? HDAB_VECTORS : HE_VECTORS, 0);
        od.resetMinAndMax(); od.setMinAndMax(0, Math.max(0.5, od.getMax()));
        var out = new ImagePlus("luma-hematoxylin", od.convertToByte(true));
        out.setCalibration(imp.getCalibration().copy());
        return new Hematoxylin(out, hdab ? "H DAB" : "H&E", dab);
    }
    record ChannelChoice(int channel, String by) {}
    /** Nuclei first: an explicit channel, else a blue-LUT channel, else a DAPI/Hoechst/nuc label, else the current channel. */
    static ChannelChoice nucleiChannel(ImagePlus imp, JsonObject args) {
        int n = Math.max(1, imp.getNChannels());
        if (args.has("channel") && !args.get("channel").isJsonNull()) return new ChannelChoice(integer(args, "channel", imp.getC(), 1, n), "argument");
        if (n == 1) return new ChannelChoice(1, "current");
        var channels = channelInfo(imp);
        for (var c : channels) if ("blue".equals(c.get("lut"))) return new ChannelChoice((Integer) c.get("index"), "lut-blue");
        for (var c : channels) if (String.valueOf(c.get("label")).matches("(?i).*(dapi|hoechst|nuc).*")) return new ChannelChoice((Integer) c.get("index"), "name");
        return new ChannelChoice(imp.getC(), "current");
    }
    static String typeName(int type) {
        return switch (type) { case ImagePlus.GRAY8 -> "GRAY8"; case ImagePlus.GRAY16 -> "GRAY16"; case ImagePlus.GRAY32 -> "GRAY32"; case ImagePlus.COLOR_256 -> "COLOR_256"; case ImagePlus.COLOR_RGB -> "COLOR_RGB"; default -> "UNKNOWN"; };
    }
    static String path(ImagePlus imp) {
        FileInfo info = imp.getOriginalFileInfo();
        if (info == null || info.fileName == null || info.fileName.isBlank()) return null;
        return info.directory == null ? info.fileName : info.directory + info.fileName;
    }
    static Map<String, Object> roiJson(Roi roi) {
        if (roi == null) return null;
        var result = new LinkedHashMap<String, Object>();
        var b = roi.getBounds();
        result.put("type", roi.getTypeAsString()); result.put("x", b.x); result.put("y", b.y); result.put("width", b.width); result.put("height", b.height);
        result.put("name", roi.getName());
        if (roi.getType() == Roi.POLYGON || roi.getType() == Roi.LINE || roi.getType() == Roi.POINT || roi.getType() == Roi.POLYLINE || roi.getType() == Roi.FREEROI || roi.getType() == Roi.TRACED_ROI) {
            var polygon = roi.getFloatPolygon(); var points = new ArrayList<List<Double>>();
            for (int i = 0; i < Math.min(500, polygon.npoints); i++) points.add(List.of((double) polygon.xpoints[i], (double) polygon.ypoints[i]));
            result.put("points", points); result.put("pointCount", polygon.npoints);
        }
        return result;
    }
    static String roiSignature(Roi roi) { if (roi == null) return "none"; var b = roi.getBounds(); return roi.getTypeAsString() + ":" + b.x + "," + b.y + "," + b.width + "," + b.height + ":" + roi.getName(); }

    // ----- open / activate / close -----
    static Path openablePath(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Provide an absolute path to an image file.");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) throw new IllegalArgumentException("Choose an existing readable absolute local file path.");
        try { return path.toRealPath(); } catch (IOException error) { throw new IllegalArgumentException("Cannot resolve path: " + error.getMessage()); }
    }
    /** Reads the file (ImageJ, else Bio-Formats). Runs on a worker thread: whole-slide imports take minutes and must not block the event thread. */
    record Opened(ImagePlus image, String reader) {}
    Opened openOffThread(Path path) {
        ImagePlus imp = IJ.openImage(path.toString());
        if (imp != null) return new Opened(imp, "ImageJ");
        journal.record("open_path", "run(\"Bio-Formats Importer\", \"open=[" + Journal.escape(path.toString()) + "] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT\");");
        IJ.run("Bio-Formats Importer", "open=[" + path + "] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT");
        var current = WindowManager.getCurrentImage();
        if (current == null || path(current) == null || !path(current).equals(path.toString())) throw new IllegalStateException("Neither ImageJ nor Bio-Formats opened " + path.getFileName() + ". Inspect Fiji for a reader dialog.");
        return new Opened(current, "Bio-Formats");
    }
    /** Shows and presents an image read by {@link #openOffThread}; event thread only. */
    Map<String, Object> present(Opened opened, Path path) {
        var imp = opened.image();
        if (opened.reader().equals("ImageJ")) { journal.record("open_path", "open(\"" + Journal.escape(path.toString()) + "\");"); show(imp); }
        else if (imp.getWindow() != null) WindowManager.setCurrentWindow(imp.getWindow());
        Viewer.present(imp);
        var result = new LinkedHashMap<String, Object>();
        result.put("opened", true); result.put("reader", opened.reader()); result.put("path", path.toString()); result.put("image", describe(imp)); result.put("highlight", Viewer.imageHighlight(imp, "Opened " + imp.getTitle()));
        return result;
    }
    /** Synchronous variant for callers already off the event thread (tests, scripts). */
    Map<String, Object> openPath(JsonObject args) {
        var path = openablePath(string(args, "path", ""));
        return present(openOffThread(path), path);
    }
    Map<String, Object> activate(JsonObject args) {
        var imp = find(args);
        var window = imp.getWindow();
        if (window != null) { WindowManager.setCurrentWindow(window); WindowManager.toFront(window); } else WindowManager.setTempCurrentImage(imp);
        journal.record("activate_image", "selectImage(\"" + Journal.escape(imp.getTitle()) + "\");");
        return Map.of("image", describe(imp), "highlight", Viewer.imageHighlight(imp, imp.getTitle()));
    }
    Map<String, Object> close(JsonObject args) {
        var targets = args.has("all") && args.get("all").getAsBoolean() ? images() : List.of(find(args));
        var closed = new ArrayList<Map<String, Object>>();
        for (var imp : targets) {
            closed.add(Map.of("id", imp.getID(), "title", imp.getTitle(), "unsavedChangesDiscarded", imp.changes));
            imp.changes = false; imp.close();
            journal.record("close", "close(\"" + Journal.escape(imp.getTitle()) + "\");");
        }
        return Map.of("closed", closed, "note", "Closed without saving; file contents are untouched.");
    }

    // ----- ROIs -----
    Map<String, Object> selectRoi(JsonObject args) {
        var imp = active();
        String type = string(args, "type", "rectangle").toLowerCase(Locale.ROOT);
        Roi roi;
        switch (type) {
            case "none" -> { imp.deleteRoi(); journal.record("select_roi", "run(\"Select None\");"); return Map.of("roi", Map.of(), "highlight", Viewer.imageHighlight(imp, "Selection cleared"), "focused", false); }
            case "rectangle", "oval" -> {
                double x = number(args, "x", 0), y = number(args, "y", 0), w = number(args, "width", 0), h = number(args, "height", 0);
                if (w <= 0 || h <= 0) throw new IllegalArgumentException("width and height must be positive.");
                roi = type.equals("oval") ? new OvalRoi(x, y, w, h) : new Roi(x, y, w, h);
                journal.record("select_roi", (type.equals("oval") ? "makeOval(" : "makeRectangle(") + plain(x) + ", " + plain(y) + ", " + plain(w) + ", " + plain(h) + ");");
            }
            case "polygon", "line", "point" -> {
                var xs = new ArrayList<Float>(); var ys = new ArrayList<Float>();
                if (args.has("points")) for (var point : args.getAsJsonArray("points")) { var pair = point.getAsJsonArray(); xs.add(pair.get(0).getAsFloat()); ys.add(pair.get(1).getAsFloat()); }
                else if (args.has("x") && args.has("y")) { xs.add((float) number(args, "x", 0)); ys.add((float) number(args, "y", 0)); if (args.has("width")) { xs.add((float) (number(args, "x", 0) + number(args, "width", 0))); ys.add((float) (number(args, "y", 0) + number(args, "height", 0))); } }
                if (xs.isEmpty()) throw new IllegalArgumentException("Provide points:[[x,y],…].");
                float[] fx = new float[xs.size()], fy = new float[ys.size()]; for (int i = 0; i < fx.length; i++) { fx[i] = xs.get(i); fy[i] = ys.get(i); }
                if (type.equals("polygon")) { if (fx.length < 3) throw new IllegalArgumentException("A polygon needs at least three points."); roi = new PolygonRoi(fx, fy, fx.length, Roi.POLYGON); }
                else if (type.equals("line")) { if (fx.length < 2) throw new IllegalArgumentException("A line needs two points."); roi = new Line(fx[0], fy[0], fx[1], fy[1]); }
                else roi = new PointRoi(fx, fy, fx.length);
                var macro = new StringBuilder(type.equals("polygon") ? "makePolygon(" : type.equals("line") ? "makeLine(" : "makePoint(");
                for (int i = 0; i < fx.length; i++) { if (i > 0) macro.append(", "); macro.append(plain(fx[i])).append(", ").append(plain(fy[i])); }
                journal.record("select_roi", macro.append(");").toString());
            }
            default -> throw new IllegalArgumentException("type must be rectangle, oval, polygon, line, point or none.");
        }
        if (args.has("name")) roi.setName(args.get("name").getAsString());
        imp.setRoi(roi);
        boolean focused = Viewer.follow(imp, roi.getBounds());
        return Map.of("roi", roiJson(roi), "highlight", Viewer.roiHighlight(imp, List.of(roi), roi.getName() == null ? "Selected region" : roi.getName(), 1), "focused", focused);
    }
    Map<String, Object> roiManager(JsonObject args) {
        var manager = managerOrNull();
        var rois = manager == null ? new Roi[0] : manager.getRoisAsArray();
        int limit = integer(args, "limit", 200, 1, 2000);
        var rows = new ArrayList<Map<String, Object>>();
        var active = activeOrNull(); int forImage = 0;
        for (int i = 0; i < rois.length; i++) {
            boolean mine = active == null || belongsTo(rois[i], active.getID()); if (mine) forImage++;
            if (rows.size() >= limit || (!mine && !bool(args, "allImages", false))) continue;
            var row = new LinkedHashMap<String, Object>(); row.put("index", i); row.putAll(roiJson(rois[i])); row.remove("points");
            String tag = rois[i].getProperty(ROI_IMAGE_PROPERTY); row.put("imageId", tag == null || tag.isBlank() ? null : Integer.parseInt(tag)); rows.add(row);
        }
        return Map.of("total", rois.length, "forImage", forImage, "rois", rows, "truncated", rows.size() < (bool(args, "allImages", false) ? rois.length : forImage));
    }
    Map<String, Object> addToManager(JsonObject args) {
        var imp = active(); var roi = imp.getRoi();
        if (roi == null) throw new IllegalStateException("Select a region first.");
        var manager = manager(); if (manager == null) throw new IllegalStateException("The ROI Manager is unavailable in headless mode.");
        tag(roi, imp); manager.addRoi(roi);
        journal.record("add_to_manager", "roiManager(\"Add\");");
        return Map.of("index", manager.getCount() - 1, "total", manager.getCount(), "highlight", Viewer.roiHighlight(imp, List.of(roi), "Added to ROI Manager", 1));
    }
    List<Roi> managerRois(JsonObject args) {
        var manager = managerOrNull(); if (manager == null) return List.of();
        var all = manager.getRoisAsArray(); var result = new ArrayList<Roi>();
        if (args.has("indices")) { for (var index : args.getAsJsonArray("indices")) { int i = index.getAsInt(); if (i >= 0 && i < all.length) result.add(all[i]); } }
        else result.addAll(Arrays.asList(all));
        var active = activeOrNull();
        if (active != null && !bool(args, "allImages", false)) result.removeIf(roi -> !belongsTo(roi, active.getID()));
        return result;
    }

    // ----- measurements -----
    private static final Map<String, Integer> MEASUREMENTS = new LinkedHashMap<>();
    static {
        MEASUREMENTS.put("area", Measurements.AREA); MEASUREMENTS.put("mean", Measurements.MEAN); MEASUREMENTS.put("std", Measurements.STD_DEV); MEASUREMENTS.put("standard_deviation", Measurements.STD_DEV);
        MEASUREMENTS.put("mode", Measurements.MODE); MEASUREMENTS.put("min", Measurements.MIN_MAX); MEASUREMENTS.put("max", Measurements.MIN_MAX); MEASUREMENTS.put("min_max", Measurements.MIN_MAX);
        MEASUREMENTS.put("centroid", Measurements.CENTROID); MEASUREMENTS.put("center_of_mass", Measurements.CENTER_OF_MASS); MEASUREMENTS.put("perimeter", Measurements.PERIMETER);
        MEASUREMENTS.put("bounding", Measurements.RECT); MEASUREMENTS.put("rect", Measurements.RECT); MEASUREMENTS.put("ellipse", Measurements.ELLIPSE); MEASUREMENTS.put("shape", Measurements.SHAPE_DESCRIPTORS);
        MEASUREMENTS.put("feret", Measurements.FERET); MEASUREMENTS.put("integrated", Measurements.INTEGRATED_DENSITY); MEASUREMENTS.put("median", Measurements.MEDIAN); MEASUREMENTS.put("skewness", Measurements.SKEWNESS);
        MEASUREMENTS.put("kurtosis", Measurements.KURTOSIS); MEASUREMENTS.put("area_fraction", Measurements.AREA_FRACTION); MEASUREMENTS.put("stack_position", Measurements.STACK_POSITION);
        MEASUREMENTS.put("limit", Measurements.LIMIT); MEASUREMENTS.put("labels", Measurements.LABELS); MEASUREMENTS.put("display", Measurements.ADD_TO_OVERLAY);
    }
    static int measurementFlags(JsonObject args) {
        int flags = Analyzer.getMeasurements();
        if (args.has("measurements")) {
            flags = 0;
            for (var element : args.getAsJsonArray("measurements")) {
                String name = element.getAsString().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
                var flag = MEASUREMENTS.get(name);
                if (flag == null) throw new IllegalArgumentException("Unknown measurement '" + element.getAsString() + "'. Known: " + String.join(", ", MEASUREMENTS.keySet()));
                flags |= flag;
            }
        }
        // The companion draws its own highlights; ImageJ's "Add to overlay" measurement option would silently grow the overlay per row.
        return (flags | Measurements.AREA | Measurements.MEAN) & ~Measurements.ADD_TO_OVERLAY;
    }
    static String setMeasurementsLine(int flags) {
        var words = new ArrayList<String>();
        for (var entry : Map.of("area", Measurements.AREA, "mean", Measurements.MEAN, "standard", Measurements.STD_DEV, "modal", Measurements.MODE, "min", Measurements.MIN_MAX, "centroid", Measurements.CENTROID, "center", Measurements.CENTER_OF_MASS, "perimeter", Measurements.PERIMETER, "bounding", Measurements.RECT, "fit", Measurements.ELLIPSE).entrySet()) if ((flags & entry.getValue()) != 0) words.add(entry.getKey());
        for (var entry : Map.of("shape", Measurements.SHAPE_DESCRIPTORS, "feret's", Measurements.FERET, "integrated", Measurements.INTEGRATED_DENSITY, "median", Measurements.MEDIAN, "skewness", Measurements.SKEWNESS, "kurtosis", Measurements.KURTOSIS, "area_fraction", Measurements.AREA_FRACTION, "stack", Measurements.STACK_POSITION, "limit", Measurements.LIMIT, "display", Measurements.LABELS).entrySet()) if ((flags & entry.getValue()) != 0) words.add(entry.getKey());
        Collections.sort(words);
        return "run(\"Set Measurements...\", \"" + String.join(" ", words) + " redirect=None decimal=3\");";
    }
    Map<String, Object> measure(JsonObject args) {
        var imp = active(); String scope = string(args, "scope", "roi");
        int flags = measurementFlags(args);
        if (scope.equals("manager")) flags |= Measurements.LABELS;
        Analyzer.setMeasurements(flags);
        journal.record("measure", setMeasurementsLine(flags));
        var table = results(); int before = table.size();
        Roi saved = imp.getRoi();
        List<Roi> measured = new ArrayList<>();
        switch (scope) {
            case "roi" -> { measureInto(imp, flags, table); journal.record("measure", "run(\"Measure\");"); if (saved != null) measured.add(saved); }
            case "all" -> { imp.deleteRoi(); try { measureInto(imp, flags, table); } finally { if (saved != null) imp.setRoi(saved); } journal.record("measure", "run(\"Select None\");\nrun(\"Measure\");"); }
            case "manager" -> {
                var rois = managerRois(args); if (rois.isEmpty()) throw new IllegalStateException("The ROI Manager is empty; segment or add regions first.");
                // Measure on the processor directly: imp.setRoi() per ROI repaints the canvas and the ROI Manager each time,
                // which took over a minute for 67 ROIs on the event thread.
                var ip = imp.getProcessor(); var calibration = imp.getCalibration();
                try { for (var roi : rois) { ip.setRoi(roi); new Analyzer(imp, flags, table).saveResults(ImageStatistics.getStatistics(ip, flags, calibration), roi); } }
                finally { ip.setRoi(saved); }
                measured.addAll(rois); journal.record("measure", "roiManager(\"Measure\");");
            }
            default -> throw new IllegalArgumentException("scope must be roi, manager or all.");
        }
        showResults(table);
        var result = summary(table, before, flags);
        result.put("scope", scope);
        Rectangle union = measured.isEmpty() ? new Rectangle(0, 0, imp.getWidth(), imp.getHeight()) : Geometry.union(measured.stream().map(Roi::getBounds).toList());
        result.put("focused", Viewer.follow(imp, union));
        result.put("highlight", measured.isEmpty() ? Viewer.imageHighlight(imp, "Measured the whole image") : Viewer.roiHighlight(imp, measured, "Measured " + measured.size() + (measured.size() == 1 ? " region" : " regions"), 120));
        return result;
    }
    /** Appends one Measure row. ImageJ's Analyzer.measure() resets the table when no Results window exists, so area/line statistics are saved directly. */
    static void measureInto(ImagePlus imp, int flags, ResultsTable table) {
        // A changed column set makes Analyzer reset the table and, when measurements are "unsaved", show a modal
        // "Save N measurements?" dialog that would park this call inside a nested event loop. The bridge checkpointed
        // the Results table before this operation, so nothing is lost by declining up front.
        Analyzer.setUnsavedMeasurements(false);
        var analyzer = new Analyzer(imp, flags, table); Roi roi = imp.getRoi();
        if (roi != null && (roi.isLine() || roi.getType() == Roi.POINT || roi.getType() == Roi.ANGLE)) { analyzer.measure(); return; }
        analyzer.saveResults(imp.getStatistics(flags), roi);
    }
    private static final Map<String, Integer> SUMMARY_COLUMNS = new LinkedHashMap<>();
    static { SUMMARY_COLUMNS.put("Area", Measurements.AREA); SUMMARY_COLUMNS.put("Mean", Measurements.MEAN); SUMMARY_COLUMNS.put("Min", Measurements.MIN_MAX); SUMMARY_COLUMNS.put("Max", Measurements.MIN_MAX); SUMMARY_COLUMNS.put("IntDen", Measurements.INTEGRATED_DENSITY); SUMMARY_COLUMNS.put("Median", Measurements.MEDIAN); SUMMARY_COLUMNS.put("Perim.", Measurements.PERIMETER); SUMMARY_COLUMNS.put("Circ.", Measurements.SHAPE_DESCRIPTORS); }
    /** Min/max/mean over the new rows, only for columns the flags actually measured (unmeasured columns hold zeros in ImageJ's table). */
    static Map<String, Object> summary(ResultsTable table, int before, int flags) {
        var result = new LinkedHashMap<String, Object>();
        int rows = table.size(); result.put("rows", rows); result.put("newRows", rows - before);
        result.put("columns", List.of(table.getHeadings()));
        result.put("lastRow", rows == 0 ? Map.of() : row(table, rows - 1));
        var summary = new LinkedHashMap<String, Object>();
        for (var entry : SUMMARY_COLUMNS.entrySet()) {
            String column = entry.getKey(); if ((flags & entry.getValue()) == 0) continue;
            int index = table.getColumnIndex(column); if (index == ResultsTable.COLUMN_NOT_FOUND || !table.columnExists(index)) continue;
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0; int n = 0;
            for (int r = before; r < rows; r++) { double v = table.getValueAsDouble(index, r); if (!Double.isFinite(v)) continue; n++; sum += v; min = Math.min(min, v); max = Math.max(max, v); }
            if (n > 0) summary.put(column.toLowerCase(Locale.ROOT).replace(".", ""), Map.of("min", min, "max", max, "mean", sum / n, "count", n));
        }
        result.put("summary", summary);
        return result;
    }
    static Map<String, Object> row(ResultsTable table, int r) {
        var values = new LinkedHashMap<String, Object>();
        String label = table.getLabel(r); if (label != null) values.put("Label", label);
        for (String heading : table.getHeadings()) { if (heading.equals("Label")) continue; int index = table.getColumnIndex(heading); if (index != ResultsTable.COLUMN_NOT_FOUND && table.columnExists(index)) values.put(heading, finite(table.getValueAsDouble(index, r))); }
        return values;
    }
    /** Column index only when the column holds data; ImageJ reserves indices for standard headings that may be empty. */
    static int column(ResultsTable table, String heading) { int index = table.getColumnIndex(heading); return index == ResultsTable.COLUMN_NOT_FOUND || !table.columnExists(index) ? -1 : index; }
    static Object finite(double value) { return Double.isFinite(value) ? value : "NaN"; }
    Map<String, Object> resultsRows(JsonObject args) {
        var table = results(); int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE), limit = integer(args, "limit", 50, 1, 500);
        var rows = new ArrayList<Map<String, Object>>();
        for (int r = offset; r < Math.min(table.size(), offset + limit); r++) { var row = new LinkedHashMap<String, Object>(); row.put("row", r); row.putAll(row(table, r)); rows.add(row); }
        return Map.of("total", table.size(), "offset", offset, "columns", List.of(table.getHeadings()), "rows", rows, "truncated", offset + limit < table.size());
    }
    Map<String, Object> clearResults() {
        var table = results(); int rows = table.size(); table.reset();
        if (IJ.isResultsWindow()) table.show("Results");
        journal.record("clear_results", "run(\"Clear Results\");");
        return Map.of("cleared", rows);
    }
    Map<String, Object> exportResults(JsonObject args) throws IOException {
        var table = results(); if (table.size() == 0) throw new IllegalStateException("The Results table is empty; measure first.");
        Path path = args.has("path") ? targetPath(string(args, "path", ""), "csv") : exportPath("results", "csv");
        table.saveAs(path.toString());
        journal.record("export_results", "saveAs(\"Results\", \"" + Journal.escape(path.toString()) + "\");");
        return Map.of("path", path.toString(), "rows", table.size(), "columns", List.of(table.getHeadings()));
    }

    // ----- threshold / particles / segmentation -----
    static String thresholdMethod(JsonObject args) {
        String method = string(args, "method", "Default");
        for (String known : AutoThresholder.getMethods()) if (known.equalsIgnoreCase(method)) return known;
        throw new IllegalArgumentException("Unknown threshold method '" + method + "'. Known: " + String.join(", ", AutoThresholder.getMethods()));
    }
    Map<String, Object> threshold(JsonObject args) {
        var imp = active(); var ip = imp.getProcessor();
        if (ip instanceof ColorProcessor) throw new IllegalStateException("Thresholding needs a grayscale image; convert RGB to 8-bit first (fiji_convert).");
        var result = new LinkedHashMap<String, Object>();
        if (args.has("lower") || args.has("upper")) {
            double lower = number(args, "lower", ip.getMinThreshold() == ImageProcessor.NO_THRESHOLD ? ip.getMin() : ip.getMinThreshold());
            double upper = number(args, "upper", ip.getMaxThreshold() == ImageProcessor.NO_THRESHOLD ? ip.getMax() : ip.getMaxThreshold());
            if (lower > upper) throw new IllegalArgumentException("lower must not exceed upper.");
            IJ.setThreshold(imp, lower, upper);
            journal.record("threshold", "setThreshold(" + plain(lower) + ", " + plain(upper) + ");");
            result.put("method", "manual");
        } else {
            String method = thresholdMethod(args);
            boolean dark = args.has("dark") ? args.get("dark").getAsBoolean() : !ip.isInvertedLut();
            IJ.setAutoThreshold(imp, method + (dark ? " dark" : ""));
            journal.record("threshold", "setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");");
            result.put("method", method); result.put("dark", dark);
        }
        ip = imp.getProcessor();
        if (ip.getMinThreshold() == ImageProcessor.NO_THRESHOLD) throw new IllegalStateException("ImageJ did not set a threshold; check the Log window.");
        result.put("lower", ip.getMinThreshold()); result.put("upper", ip.getMaxThreshold());
        Roi roi = imp.getRoi(); Rectangle bounds = roi == null ? new Rectangle(0, 0, imp.getWidth(), imp.getHeight()) : roi.getBounds();
        // A threshold concerns the whole image (or a real region); never zoom into a single tiny particle ROI.
        boolean regionSized = roi != null && bounds.getWidth() * bounds.getHeight() >= 0.05 * imp.getWidth() * imp.getHeight();
        result.put("focused", Viewer.follow(imp, regionSized ? bounds : new Rectangle(0, 0, imp.getWidth(), imp.getHeight())));
        result.put("highlight", roi == null ? Viewer.imageHighlight(imp, "Thresholded " + plain(ip.getMinThreshold()) + "–" + plain(ip.getMaxThreshold())) : Viewer.roiHighlight(imp, List.of(roi), "Thresholded in the region", 1));
        return result;
    }
    static boolean thresholdedOrBinary(ImagePlus imp) { var ip = imp.getProcessor(); return ip.getMinThreshold() != ImageProcessor.NO_THRESHOLD || ip.isBinary(); }
    Map<String, Object> analyzeParticles(JsonObject args) {
        var imp = active();
        if (!thresholdedOrBinary(imp)) throw new IllegalStateException("Threshold the image first (fiji_threshold) or use a binary mask; Analyze Particles needs one.");
        return particles(imp, imp, args, "analyze_particles");
    }
    /** Runs Analyze Particles on {@code imp}; new ROI Manager entries are tagged as belonging to {@code owner} (the original image when a scratch mask is analysed). */
    private Map<String, Object> particles(ImagePlus imp, ImagePlus owner, JsonObject args, String operation) {
        double minSize = number(args, "minSize", 0), maxSize = args.has("maxSize") ? number(args, "maxSize", Double.POSITIVE_INFINITY) : Double.POSITIVE_INFINITY;
        double minCirc = number(args, "minCircularity", 0), maxCirc = number(args, "maxCircularity", 1);
        String show = string(args, "show", "Nothing");
        boolean exclude = bool(args, "exclude", false), include = bool(args, "include", false), summarize = bool(args, "summarize", false), addToManager = bool(args, "addToManager", true);
        var manager = addToManager ? manager() : null;
        int options = 0;
        options |= switch (show.toLowerCase(Locale.ROOT)) { case "outlines" -> ParticleAnalyzer.SHOW_OUTLINES; case "masks" -> ParticleAnalyzer.SHOW_MASKS; default -> ParticleAnalyzer.SHOW_NONE; };
        if (exclude) options |= ParticleAnalyzer.EXCLUDE_EDGE_PARTICLES;
        if (include) options |= ParticleAnalyzer.INCLUDE_HOLES;
        if (summarize) options |= ParticleAnalyzer.DISPLAY_SUMMARY;
        if (manager != null) { options |= ParticleAnalyzer.ADD_TO_MANAGER; ParticleAnalyzer.setRoiManager(manager); }
        int measurements = (Analyzer.getMeasurements() | Measurements.AREA | Measurements.MEAN | Measurements.RECT | Measurements.CENTROID) & ~Measurements.ADD_TO_OVERLAY;
        var table = results(); int before = table.size(); int managerBefore = manager == null ? 0 : manager.getCount();
        var analyzer = new ParticleAnalyzer(options, measurements, table, minSize, maxSize, minCirc, maxCirc);
        analyzer.setHideOutputImage(show.equalsIgnoreCase("Nothing"));
        Analyzer.setUnsavedMeasurements(false);
        if (!analyzer.analyze(imp)) throw new IllegalStateException("Analyze Particles did not run; check the Log window.");
        showResults(table); 
        String sizeText = plain(minSize) + "-" + (Double.isInfinite(maxSize) ? "Infinity" : plain(maxSize));
        journal.record(operation, "run(\"Analyze Particles...\", \"size=" + sizeText + " circularity=" + String.format(Locale.US, "%.2f-%.2f", minCirc, maxCirc) + " show=" + (show.isBlank() ? "Nothing" : show) + " display" + (exclude ? " exclude" : "") + (include ? " include" : "") + (summarize ? " summarize" : "") + (manager != null ? " add" : "") + "\");");
        int count = table.size() - before;
        if (manager != null) { var rois = manager.getRoisAsArray(); for (int i = managerBefore; i < rois.length; i++) tag(rois[i], owner); }
        var cal = imp.getCalibration();
        var bounds = new ArrayList<Rectangle>(); double totalArea = 0;
        int areaColumn = column(table, "Area"), bx = column(table, "BX"), by = column(table, "BY"), bw = column(table, "Width"), bh = column(table, "Height");
        for (int r = before; r < table.size(); r++) {
            if (areaColumn >= 0) totalArea += table.getValueAsDouble(areaColumn, r);
            if (bx >= 0 && by >= 0 && bw >= 0 && bh >= 0) bounds.add(new Rectangle((int) Math.round(cal.getRawX(table.getValueAsDouble(bx, r))), (int) Math.round(cal.getRawY(table.getValueAsDouble(by, r))), (int) Math.round(table.getValueAsDouble(bw, r) / cal.pixelWidth), (int) Math.round(table.getValueAsDouble(bh, r) / cal.pixelHeight)));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("count", count); result.put("totalArea", totalArea); result.put("averageSize", count == 0 ? 0.0 : totalArea / count); result.put("unit", cal.getUnit());
        result.put("resultsRows", table.size()); result.put("addedToManager", manager == null ? 0 : manager.getCount() - managerBefore); result.put("managerTotal", manager == null ? 0 : manager.getCount());
        result.put("summary", summary(table, before, measurements).get("summary"));
        outline(imp, manager, managerBefore, bounds, count); 
        var union = Geometry.union(bounds); 
        result.put("focused", union != null && Viewer.follow(imp, union)); 
        result.put("highlight", Viewer.rectHighlight(imp, bounds, count + (count == 1 ? " particle" : " particles"), 120, true)); 
        return result;
    }
    /** Removes the just-added particles that are not in {@code keep} (offsets within the added range) from the Results table and the ROI Manager, and recomputes the totals. */
    void dropParticles(Map<String, Object> result, List<Integer> keep) {
        var table = results(); int count = ((Number) result.get("count")).intValue(); if (count == 0) return;
        int first = table.size() - count, areaColumn = column(table, "Area");
        for (int r = table.size() - 1; r >= first; r--) if (!keep.contains(r - first)) table.deleteRows(r, r);
        var manager = managerOrNull(); int added = ((Number) result.get("addedToManager")).intValue(); int managerBefore = ((Number) result.get("managerTotal")).intValue() - added;
        if (manager != null && added == count) {
            var rois = manager.getRoisAsArray(); manager.reset();
            for (int i = 0; i < managerBefore; i++) manager.addRoi(rois[i]);
            for (int k : keep) manager.addRoi(rois[managerBefore + k]);
            for (int i = rois.length - 1; i >= managerBefore + count && i < rois.length; i--) manager.addRoi(rois[i]);
        }
        double total = 0; for (int r = first; r < table.size() && areaColumn >= 0; r++) total += table.getValueAsDouble(areaColumn, r);
        result.put("count", keep.size()); result.put("totalArea", total); result.put("averageSize", keep.isEmpty() ? 0.0 : total / keep.size());
        result.put("addedToManager", manager == null ? 0 : keep.size()); result.put("managerTotal", manager == null ? 0 : manager.getCount()); result.put("resultsRows", table.size());
        result.put("summary", summary(table, first, (Analyzer.getMeasurements() | Measurements.AREA | Measurements.MEAN | Measurements.RECT | Measurements.CENTROID) & ~Measurements.ADD_TO_OVERLAY).get("summary"));
        if (IJ.isResultsWindow()) table.show("Results");
    }
    /** "Only this area": keeps the particles just added whose centroid lies inside the ROI. */
    void restrictToSelection(Map<String, Object> result, Roi selection, Calibration cal) {
        var table = results(); int count = ((Number) result.get("count")).intValue(); if (count == 0) return;
        int first = table.size() - count, xc = column(table, "X"), yc = column(table, "Y");
        if (xc < 0 || yc < 0) throw new IllegalStateException("Centroid columns are missing; cannot scope to the selection.");
        var keep = new ArrayList<Integer>();
        for (int r = first; r < table.size(); r++) { double x = cal.getRawX(table.getValueAsDouble(xc, r)), y = cal.getRawY(table.getValueAsDouble(yc, r)); if (selection.contains((int) Math.floor(x), (int) Math.floor(y))) keep.add(r - first); }
        dropParticles(result, keep);
        result.put("excludedOutsideSelection", count - keep.size()); result.put("scope", "selection"); result.put("selection", roiJson(selection));
    }
    /** Burned-in annotation text (flat, saturated) and specks far below the typical object size are not cells; drop them and say so. */
    void excludeArtifacts(Map<String, Object> result, ImagePlus imp, int channel) {
        var table = results(); int count = ((Number) result.get("count")).intValue(); if (count == 0) { result.put("excluded", Map.of("text", 0, "tiny", 0)); return; }
        int first = table.size() - count, areaColumn = column(table, "Area");
        var manager = managerOrNull(); int added = ((Number) result.get("addedToManager")).intValue(); int managerBefore = ((Number) result.get("managerTotal")).intValue() - added;
        var rois = manager != null && added == count ? manager.getRoisAsArray() : null;
        ImageProcessor ip = imp.getStackSize() > 1 ? imp.getStack().getProcessor(imp.getStackIndex(Math.min(channel, Math.max(1, imp.getNChannels())), imp.getZ(), imp.getT())) : imp.getProcessor();
        double ceiling = imp.getBitDepth() == 8 || imp.getBitDepth() == 24 ? 255 : imp.getBitDepth() == 16 ? 65535 : Double.NaN;
        double globalMax = ip.getStats().max, range = Math.max(1, globalMax - ip.getStats().min);
        var areas = new ArrayList<Double>(); for (int r = first; r < table.size(); r++) areas.add(areaColumn >= 0 ? table.getValueAsDouble(areaColumn, r) : 0.0);
        var sorted = new ArrayList<>(areas); Collections.sort(sorted); double median = sorted.get(sorted.size() / 2);
        var keep = new ArrayList<Integer>(); int text = 0, tiny = 0;
        for (int i = 0; i < count; i++) {
            Roi roi = rois != null ? rois[managerBefore + i] : null;
            double area = areas.get(i); boolean isText = false;
            if (roi != null) {
                ip.setRoi(roi); var stats = ij.process.ImageStatistics.getStatistics(ip, Measurements.MEAN | Measurements.STD_DEV | Measurements.MIN_MAX | Measurements.AREA, null); ip.resetRoi();
                // Annotation text is flat (no texture) and saturated: at the bit-depth ceiling, or for 16-bit data at a
                // 12-bit-or-higher ceiling that is also the channel maximum. Flat objects below that are kept: real data can be flat too.
                boolean flat = stats.stdDev <= 0.01 * range;
                boolean saturated = !Double.isNaN(ceiling) && stats.max >= ceiling - 1 || imp.getBitDepth() == 16 && stats.max >= 4095 && stats.max >= globalMax;
                isText = flat && saturated;
            }
            boolean isTiny = count >= 5 && area < 0.15 * median;
            if (isText) text++; else if (isTiny) tiny++; else keep.add(i);
        }
        if (text + tiny > 0) dropParticles(result, keep);
        result.put("excluded", Map.of("text", text, "tiny", tiny));
    }
    static double solidity(Roi roi, double area) {
        try { var hull = roi.getConvexHull(); if (hull == null) return 1; double hullArea = Math.abs(polygonArea(hull)); return hullArea <= 0 ? 1 : Math.min(1, area / hullArea); }
        catch (RuntimeException error) { return 1; }
    }
    static double polygonArea(java.awt.Polygon polygon) {
        double sum = 0; for (int i = 0; i < polygon.npoints; i++) { int j = (i + 1) % polygon.npoints; sum += (double) polygon.xpoints[i] * polygon.ypoints[j] - (double) polygon.xpoints[j] * polygon.ypoints[i]; }
        return sum / 2;
    }
    /** Detections stay visible even with the ROI Manager hidden: bright magenta outlines in the overlay, numbered when there are at most 200. */
    static void outline(ImagePlus imp, RoiManager manager, int managerBefore, List<Rectangle> bounds, int count) {
        if (count <= 0) return;
        var overlay = new Overlay();
        var rois = manager == null ? new Roi[0] : manager.getRoisAsArray();
        if (rois.length >= managerBefore + count) { for (int i = managerBefore; i < managerBefore + count; i++) overlay.add((Roi) rois[i].clone()); }
        else for (var b : bounds) overlay.add(new OvalRoi(b.x, b.y, b.width, b.height));
        for (int i = 0; i < overlay.size(); i++) { var roi = overlay.get(i); roi.setStrokeColor(java.awt.Color.MAGENTA); roi.setStrokeWidth(2); roi.setName(String.valueOf(i + 1)); }
        overlay.drawLabels(count <= 200); overlay.drawNames(count <= 200); overlay.setLabelColor(java.awt.Color.WHITE); overlay.drawBackgrounds(true);
        imp.setOverlay(overlay); imp.setHideOverlay(false); imp.updateAndDraw();
    }
    Map<String, Object> segmentCells(JsonObject args) {
        var imp = active();
        boolean deconvolve = imp.getType() == ImagePlus.COLOR_RGB && !args.has("channel");
        var choice = deconvolve ? new ChannelChoice(1, "hematoxylin") : nucleiChannel(imp, args); int channel = choice.channel();
        String method = thresholdMethod(args); boolean watershed = bool(args, "watershed", true), gaussian = bool(args, "gaussian", true);
        double sigma = number(args, "sigma", 1);
        var steps = new ArrayList<String>(); var macro = new ArrayList<String>();
        Roi saved = imp.getRoi(); imp.deleteRoi();
        boolean blackBackground = ij.Prefs.blackBackground;
        ImagePlus work = null;
        try {
            Hematoxylin hema = null;
            if (deconvolve) {
                hema = hematoxylin(imp); work = hema.image(); work.setTitle("luma-segmentation-mask");
                steps.add("colour deconvolution (" + hema.vectors() + " vectors): hematoxylin optical density of " + imp.getTitle());
                macro.add("selectImage(\"" + Journal.escape(imp.getTitle()) + "\");\nrun(\"Colour Deconvolution\", \"vectors=[" + hema.vectors() + "] hide\");\nselectImage(\"" + Journal.escape(imp.getTitle()) + "-(Colour_1)\");\nrun(\"Invert\");\nrename(\"luma-segmentation-mask\");");
            } else {
                work = new Duplicator().run(imp, channel, channel, imp.getZ(), imp.getZ(), imp.getT(), imp.getT());
                work.setTitle("luma-segmentation-mask");
                steps.add("duplicate channel " + channel + " of " + imp.getTitle()); macro.add("selectImage(\"" + Journal.escape(imp.getTitle()) + "\");\nrun(\"Duplicate...\", \"title=luma-segmentation-mask duplicate channels=" + channel + "\");");
                if (work.getBitDepth() != 8) { IJ.run(work, "8-bit", ""); steps.add("convert to 8-bit"); macro.add("run(\"8-bit\");"); }
            }
            if (gaussian) { IJ.run(work, "Gaussian Blur...", "sigma=" + plain(sigma)); steps.add("Gaussian blur sigma " + plain(sigma)); macro.add("run(\"Gaussian Blur...\", \"sigma=" + plain(sigma) + "\");"); }
            boolean dark = deconvolve ? true : args.has("dark") ? args.get("dark").getAsBoolean() : !work.getProcessor().isInvertedLut();
            IJ.setAutoThreshold(work, method + (dark ? " dark" : "")); steps.add("auto threshold " + method + (dark ? " (dark background)" : "")); macro.add("setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");");
            double lower = work.getProcessor().getMinThreshold(), upper = work.getProcessor().getMaxThreshold();
            ij.Prefs.blackBackground = true; macro.add("setOption(\"BlackBackground\", true);");
            IJ.run(work, "Convert to Mask", ""); steps.add("convert to mask"); macro.add("run(\"Convert to Mask\");");
            IJ.run(work, "Fill Holes", ""); steps.add("fill holes"); macro.add("run(\"Fill Holes\");");
            if (watershed) { IJ.run(work, "Watershed", ""); steps.add("watershed"); macro.add("run(\"Watershed\");"); }
            var particleArgs = args.deepCopy(); particleArgs.addProperty("show", "Nothing"); if (!particleArgs.has("exclude")) particleArgs.addProperty("exclude", false);
            if (!particleArgs.has("minSize")) particleArgs.addProperty("minSize", 0);
            // Each segmentation answers for the current image only: previous ROIs and rows must not inflate the count.
            var existing = managerOrNull();
            if (existing != null && existing.getCount() > 0) { existing.reset(); steps.add("reset ROI Manager"); macro.add("roiManager(\"reset\");"); }
            if (results().size() > 0) { results().reset(); steps.add("clear results"); macro.add("run(\"Clear Results\");"); }
            for (var line : macro) journal.record("segment_cells", line);
            int journalBefore = journal.size();
            var result = particles(work, imp, particleArgs, "segment_cells");
            result.put("channel", deconvolve ? null : channel); result.put("channelChosenBy", choice.by());
            if (hema != null) result.put("deconvolution", Map.of("vectors", hema.vectors(), "dabFraction", hema.dabFraction(), "note", "hematoxylin optical density segmented bright-on-dark; intensities measured on the RGB image"));
            steps.add("analyze particles (" + result.get("count") + " found)");
            result.put("scope", "image");
            if (!bool(args, "keepArtifacts", false)) { excludeArtifacts(result, imp, channel); var excluded = (Map<?, ?>) result.get("excluded"); if (((Number) excluded.get("text")).intValue() + ((Number) excluded.get("tiny")).intValue() > 0) steps.add("excluded annotation text (" + excluded.get("text") + ") and specks (" + excluded.get("tiny") + ")"); }
            if (saved != null && saved.isArea()) { restrictToSelection(result, saved, imp.getCalibration()); steps.add("kept particles whose centroid lies inside the selection (" + result.get("count") + ")"); }
            for (var step : journal.steps(journal.size() - journalBefore)) macro.add(String.valueOf(step.get("macro"))); 
            macro.add("close(\"luma-segmentation-mask\");\nselectImage(\"" + Journal.escape(imp.getTitle()) + "\");\nroiManager(\"Show All without labels\");");
            journal.record("segment_cells", macro.getLast());
            result.put("threshold", Map.of("lower", lower, "upper", upper, "method", method, "dark", dark));
            // Intensities must come from the original pixels, not the binary mask: re-measure the new ROIs on the image.
            var measuredManager = managerOrNull();
            if (measuredManager != null && measuredManager.getCount() > 0) {
                results().reset();
                IJ.run("Set Measurements...", "area mean standard min integrated median perimeter shape bounding redirect=None decimal=3");
                measuredManager.runCommand(imp, "Measure");
                var measured = summary(results(), 0, Measurements.AREA | Measurements.MEAN | Measurements.MIN_MAX | Measurements.INTEGRATED_DENSITY | Measurements.MEDIAN | Measurements.PERIMETER | Measurements.SHAPE_DESCRIPTORS);
                result.put("summary", measured.get("summary")); result.put("resultsRows", results().size());
                steps.add("measure the ROIs on the original image");
                macro.add("run(\"Set Measurements...\", \"area mean standard min integrated median perimeter shape bounding redirect=None decimal=3\");\nroiManager(\"Measure\");");
            }
            result.put("steps", steps); result.put("macro", String.join("\n", macro)); result.put("maskClosed", true); 
            // The mask is scratch; the ROIs live in the manager and refer to the original image's pixels.
            work.changes = false; work.close(); work = null;
            if (saved != null) imp.setRoi(saved);
            // Closing the mask clears ImageJ's per-thread "current image" when it pointed at the mask; put the original back.
            if (imp.getWindow() != null) WindowManager.setCurrentWindow(imp.getWindow()); else WindowManager.setTempCurrentImage(imp);
            var bounds = new ArrayList<Rectangle>();
            var table = results(); int bx = column(table, "BX"), by = column(table, "BY"), bw = column(table, "Width"), bh = column(table, "Height"); var cal = imp.getCalibration();
            int count = ((Number) result.get("count")).intValue();
            for (int r = Math.max(0, table.size() - count); r < table.size() && bx >= 0 && by >= 0 && bw >= 0 && bh >= 0; r++) bounds.add(new Rectangle((int) Math.round(cal.getRawX(table.getValueAsDouble(bx, r))), (int) Math.round(cal.getRawY(table.getValueAsDouble(by, r))), (int) Math.round(table.getValueAsDouble(bw, r) / cal.pixelWidth), (int) Math.round(table.getValueAsDouble(bh, r) / cal.pixelHeight)));
            var union = Geometry.union(bounds);
            result.put("focused", union != null && Viewer.follow(imp, union));
            outline(imp, managerOrNull(), ((Number) result.get("managerTotal")).intValue() - ((Number) result.get("addedToManager")).intValue(), bounds, count);
            result.put("highlight", Viewer.rectHighlight(imp, bounds, count + (count == 1 ? " cell" : " cells"), 120, true));
            result.put("image", describe(imp));
            return result;
        } finally {
            ij.Prefs.blackBackground = blackBackground;
            if (work != null) { work.changes = false; work.close(); }
            if (imp.getRoi() == null && saved != null) imp.setRoi(saved);
        }
    }

    // ----- filters / conversions / duplicates -----
    Map<String, Object> filter(JsonObject args) {
        var imp = active(); String name = string(args, "name", "").toLowerCase(Locale.ROOT).replace(' ', '_');
        boolean stack = bool(args, "stack", false) && imp.getStackSize() > 1;
        String command, options;
        switch (name) {
            case "gaussian" -> { command = "Gaussian Blur..."; options = "sigma=" + plain(number(args, "sigma", number(args, "radius", 2))); }
            case "median" -> { command = "Median..."; options = "radius=" + plain(number(args, "radius", 2)); }
            case "mean" -> { command = "Mean..."; options = "radius=" + plain(number(args, "radius", 2)); }
            case "unsharp" -> { command = "Unsharp Mask..."; options = "radius=" + plain(number(args, "radius", 1)) + " mask=" + plain(number(args, "weight", 0.6)); }
            case "subtract_background" -> { command = "Subtract Background..."; options = "rolling=" + plain(number(args, "rolling", 50)) + (bool(args, "light", false) ? " light" : ""); }
            case "enhance_contrast" -> { command = "Enhance Contrast"; options = "saturated=" + plain(number(args, "saturated", 0.35)) + (bool(args, "equalize", false) ? " equalize" : ""); }
            case "invert" -> { command = "Invert"; options = ""; }
            case "smooth" -> { command = "Smooth"; options = ""; }
            case "sharpen" -> { command = "Sharpen"; options = ""; }
            case "find_edges" -> { command = "Find Edges"; options = ""; }
            default -> throw new IllegalArgumentException("Unknown filter '" + name + "'. Known: gaussian, median, mean, unsharp, subtract_background, enhance_contrast, invert, smooth, sharpen, find_edges");
        }
        if (stack) options = (options + " stack").strip();
        IJ.run(imp, command, options);
        journal.record("filter", Journal.runLine(command, options));
        Roi roi = imp.getRoi();
        return Map.of("command", command, "options", options, "image", describe(imp), "highlight", roi == null ? Viewer.imageHighlight(imp, command) : Viewer.roiHighlight(imp, List.of(roi), command, 1));
    }
    Map<String, Object> convert(JsonObject args) {
        var imp = active(); String to = string(args, "to", "8-bit");
        String command = switch (to.toLowerCase(Locale.ROOT)) {
            case "8-bit", "8bit", "grayscale", "gray" -> "8-bit"; case "16-bit", "16bit" -> "16-bit"; case "32-bit", "32bit" -> "32-bit";
            case "rgb", "rgb color" -> "RGB Color"; case "composite" -> "Make Composite";
            default -> throw new IllegalArgumentException("to must be 8-bit, 16-bit, 32-bit, RGB, Composite or Grayscale.");
        };
        if (bool(args, "splitChannels", false)) { IJ.run(imp, "Split Channels", ""); journal.record("convert", "run(\"Split Channels\");"); return Map.of("split", true, "images", images().stream().map(Operations::describe).toList()); }
        IJ.run(imp, command, ""); journal.record("convert", Journal.runLine(command, ""));
        var current = activeOrNull() == null ? imp : activeOrNull();
        return Map.of("command", command, "image", describe(current), "highlight", Viewer.imageHighlight(current, command));
    }
    Map<String, Object> duplicate(JsonObject args) {
        var imp = active(); boolean roiOnly = bool(args, "roiOnly", imp.getRoi() != null);
        Roi saved = imp.getRoi(); if (!roiOnly) imp.deleteRoi();
        ImagePlus copy;
        try { copy = new Duplicator().run(imp); } finally { if (saved != null) imp.setRoi(saved); }
        String title = string(args, "title", imp.getTitle().replaceAll("\\.[A-Za-z0-9]+$", "") + "-copy");
        copy.setTitle(title); show(copy);
        journal.record("duplicate", "run(\"Duplicate...\", \"title=[" + Journal.escape(title) + "]" + (imp.getStackSize() > 1 ? " duplicate" : "") + "\");");
        return Map.of("image", describe(copy), "roiOnly", roiOnly && saved != null, "highlight", Viewer.imageHighlight(copy, "Duplicated"));
    }

    // ----- view -----
    Map<String, Object> zoom(JsonObject args) {
        var imp = active();
        Integer x = args.has("x") ? (int) number(args, "x", 0) : null, y = args.has("y") ? (int) number(args, "y", 0) : null;
        if (bool(args, "fit", false)) { Viewer.fit(imp); journal.record("zoom", "run(\"Original Scale\");"); }
        else if (args.has("magnification")) { double m = number(args, "magnification", 1); if (m <= 0) throw new IllegalArgumentException("magnification must be positive."); Viewer.magnify(imp, m, x, y); journal.record("zoom", "run(\"Set... \", \"zoom=" + plain(m * 100) + (x == null ? "" : " x=" + x + " y=" + y) + "\");"); }
        else if (args.has("in") || args.has("out") || string(args, "direction", "").matches("in|out")) {
            boolean in = bool(args, "in", false) || string(args, "direction", "").equals("in");
            Viewer.step(imp, in, x, y); journal.record("zoom", in ? "run(\"In [+]\");" : "run(\"Out [-]\");");
        } else throw new IllegalArgumentException("Provide fit:true, magnification, or in/out.");
        var view = Viewer.view(imp);
        var src = view == null ? new Rectangle(0, 0, imp.getWidth(), imp.getHeight()) : view.srcRect();
        return Map.of("viewer", Optional.ofNullable(Viewer.viewerJson(imp)).orElse(Map.of()), "highlight", Viewer.rectHighlight(imp, List.of(src), "Zoomed", 1, false));
    }
    Map<String, Object> highlight(JsonObject args) {
        var imp = active(); String label = string(args, "label", ""); int limit = integer(args, "limit", 40, 1, 120);
        var rois = new ArrayList<Roi>(); var rects = new ArrayList<Rectangle>();
        if (args.has("width") && args.has("height")) {
            double x = number(args, "x", 0), y = number(args, "y", 0), w = number(args, "width", 0), h = number(args, "height", 0);
            if (w <= 0 || h <= 0) throw new IllegalArgumentException("Region width and height must be positive.");
            rects.add(new Rectangle((int) x, (int) y, (int) Math.ceil(w), (int) Math.ceil(h)));
        } else {
            String source = string(args, "rois", "active");
            switch (source) {
                case "active" -> { if (imp.getRoi() != null) rois.add(imp.getRoi()); }
                case "manager" -> rois.addAll(managerRois(args));
                case "overlay" -> { if (imp.getOverlay() != null) rois.addAll(Arrays.asList(imp.getOverlay().toArray())); }
                case "all" -> { if (imp.getRoi() != null) rois.add(imp.getRoi()); rois.addAll(managerRois(args)); if (imp.getOverlay() != null) rois.addAll(Arrays.asList(imp.getOverlay().toArray())); }
                default -> throw new IllegalArgumentException("rois must be active, manager, overlay or all.");
            }
            if (rois.isEmpty()) { rects.add(new Rectangle(0, 0, imp.getWidth(), imp.getHeight())); if (label.isEmpty()) label = imp.getTitle(); }
        }
        var bounds = rects.isEmpty() ? rois.stream().map(Roi::getBounds).toList() : rects;
        var union = Geometry.union(bounds);
        boolean focused;
        if (args.has("focus")) focused = bool(args, "focus", false) && union != null && focusNow(imp, union);
        else {
            // The view follows the work: refocus when the union is invisible or when fewer than half of the targets are on screen.
            var view = Viewer.view(imp); long visible = 0;
            if (view != null) for (var b : bounds.stream().limit(400).toList()) if (Geometry.screenRect(view, b.x, b.y, b.x + b.width, b.y + b.height) != null) visible++;
            boolean fewVisible = view != null && bounds.size() > 1 && visible * 2 < Math.min(400, bounds.size());
            focused = union != null && (fewVisible ? focusNow(imp, union) : Viewer.follow(imp, union));
        }
        if (label.isEmpty()) label = rois.isEmpty() ? "Region" : rois.size() + (rois.size() == 1 ? " region" : " regions");
        var highlight = rects.isEmpty() ? Viewer.roiHighlight(imp, rois, label, limit) : Viewer.rectHighlight(imp, rects, label, limit, false);
        if (highlight.isEmpty()) throw new IllegalStateException("The image is not shown in a Fiji window; nothing to point at.");
        return Map.of("highlight", highlight, "focused", focused);
    }
    private static boolean focusNow(ImagePlus imp, Rectangle union) { Viewer.zoomTo(imp, union); return true; }
    Map<String, Object> overlay(JsonObject args) {
        var imp = active();
        if (bool(args, "clear", false)) { imp.setOverlay(null); journal.record("overlay", "run(\"Remove Overlay\");"); }
        if (bool(args, "fromManager", false)) {
            var rois = managerRois(args); if (rois.isEmpty()) throw new IllegalStateException("The ROI Manager is empty.");
            var overlay = new Overlay(); for (var roi : rois) overlay.add((Roi) roi.clone());
            imp.setOverlay(overlay); journal.record("overlay", "run(\"From ROI Manager\");");
        }
        if (args.has("labels") && imp.getOverlay() != null) { boolean labels = args.get("labels").getAsBoolean(); imp.getOverlay().drawLabels(labels); imp.getOverlay().drawNames(labels); }
        if (args.has("show")) { boolean show = args.get("show").getAsBoolean(); imp.setHideOverlay(!show); journal.record("overlay", show ? "run(\"Show Overlay\");" : "run(\"Hide Overlay\");"); }
        imp.updateAndDraw();
        var overlay = imp.getOverlay();
        return Map.of("overlay", overlay == null ? 0 : overlay.size(), "hidden", imp.getHideOverlay(), "highlight", overlay == null || overlay.size() == 0 ? Viewer.imageHighlight(imp, "Overlay") : Viewer.roiHighlight(imp, Arrays.asList(overlay.toArray()), "Overlay: " + overlay.size(), 60));
    }
    Map<String, Object> display(JsonObject args) {
        var imp = active(); var applied = new ArrayList<String>();
        if (args.has("channel") && imp.getNChannels() > 1) { imp.setC(integer(args, "channel", 1, 1, imp.getNChannels())); applied.add("channel " + imp.getC()); journal.record("display", "Stack.setChannel(" + imp.getC() + ");"); }
        if (bool(args, "auto", false)) { IJ.run(imp, "Enhance Contrast", "saturated=0.35"); applied.add("auto contrast"); journal.record("display", "run(\"Enhance Contrast\", \"saturated=0.35\");"); }
        if (args.has("min") || args.has("max")) {
            double min = number(args, "min", imp.getDisplayRangeMin()), max = number(args, "max", imp.getDisplayRangeMax());
            if (min > max) throw new IllegalArgumentException("min must not exceed max.");
            imp.setDisplayRange(min, max); imp.updateAndDraw(); applied.add("range " + plain(min) + "–" + plain(max)); journal.record("display", "setMinAndMax(" + plain(min) + ", " + plain(max) + ");");
        }
        if (args.has("lut")) {
            String lut = args.get("lut").getAsString();
            if (imp.getType() == ImagePlus.COLOR_RGB) throw new IllegalStateException("RGB images have no LUT; convert to 8-bit first.");
            var commands = ij.Menus.getCommands(); String command = null;
            if (commands != null) for (var key : commands.keySet()) if (String.valueOf(key).equalsIgnoreCase(lut)) command = String.valueOf(key);
            if (command == null) throw new IllegalArgumentException("Unknown LUT '" + lut + "'; use a name from Image > Lookup Tables (e.g. Grays, Fire, Green).");
            IJ.run(imp, command, ""); applied.add("LUT " + command); journal.record("display", Journal.runLine(command, ""));
        }
        if (applied.isEmpty()) throw new IllegalArgumentException("Provide min/max, auto, lut or channel.");
        return Map.of("applied", applied, "image", describe(imp), "highlight", Viewer.imageHighlight(imp, String.join(", ", applied)));
    }
    Map<String, Object> stack(JsonObject args) {
        var imp = active();
        if (args.has("project")) {
            if (imp.getNSlices() < 2) throw new IllegalStateException("The image has a single slice; nothing to project.");
            String method = string(args, "project", "max").toLowerCase(Locale.ROOT);
            String ij = switch (method) { case "max" -> "max"; case "sum" -> "sum"; case "avg", "average", "mean" -> "avg"; case "min" -> "min"; case "sd" -> "sd"; case "median" -> "median"; default -> throw new IllegalArgumentException("project must be max, sum, avg, min, sd or median."); };
            var projected = ZProjector.run(imp, ij);
            if (projected == null) throw new IllegalStateException("Projection failed.");
            show(projected);
            journal.record("stack", "run(\"Z Project...\", \"projection=[" + switch (ij) { case "max" -> "Max Intensity"; case "sum" -> "Sum Slices"; case "avg" -> "Average Intensity"; case "min" -> "Min Intensity"; case "sd" -> "Standard Deviation"; default -> "Median"; } + "]\");");
            return Map.of("projected", true, "method", ij, "image", describe(projected), "highlight", Viewer.imageHighlight(projected, "Projection"));
        }
        int c = position(args, "channel", imp.getC(), imp.getNChannels()), z = position(args, "slice", imp.getZ(), imp.getNSlices()), t = position(args, "frame", imp.getT(), imp.getNFrames());
        imp.setPosition(c, z, t); imp.updateAndDraw();
        journal.record("stack", "Stack.setPosition(" + c + ", " + z + ", " + t + ");");
        return Map.of("position", Map.of("channel", imp.getC(), "slice", imp.getZ(), "frame", imp.getT()), "image", describe(imp), "highlight", Viewer.imageHighlight(imp, "Slice " + imp.getZ()));
    }

    /** A 1-based position from a number or the words next, previous, first, last (clamped to the axis). */
    static int position(JsonObject args, String name, int current, int length) {
        int max = Math.max(1, length);
        if (!args.has(name) || args.get(name).isJsonNull()) return current;
        var value = args.get(name);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String word = value.getAsString().strip().toLowerCase(Locale.ROOT);
            if (word.matches("-?\\d+")) return Math.max(1, Math.min(max, Integer.parseInt(word)));
            return switch (word) { case "next", "+1" -> Math.min(max, current + 1); case "previous", "prev", "-1" -> Math.max(1, current - 1); case "first" -> 1; case "last" -> max; default -> throw new IllegalArgumentException(name + " must be a number or next/previous/first/last"); };
        }
        return integer(args, name, current, 1, max);
    }

    // ----- files -----
    static Path exportDirectory() throws IOException {
        String root = LumaBridge.root();
        Path directory = root != null ? Path.of(root, ".local", "exports") : Path.of(System.getProperty("user.home"), "Luma exports");
        return Files.createDirectories(directory);
    }
    static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "image" : slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
    static Path exportPath(String base, String extension) throws IOException {
        var directory = exportDirectory();
        String name = slug(base) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var path = directory.resolve(name + "." + extension);
        for (int suffix = 2; Files.exists(path); suffix++) path = directory.resolve(name + "-" + suffix + "." + extension);
        return path;
    }
    static Path targetPath(String value, String extension) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Provide an absolute output path.");
        if (!Files.isDirectory(path.getParent())) throw new IllegalArgumentException("Output directory does not exist: " + path.getParent());
        return path;
    }
    Map<String, Object> saveImage(JsonObject args) throws IOException {
        var imp = active();
        String format = string(args, "format", "tiff").toLowerCase(Locale.ROOT);
        String extension = switch (format) { case "tiff", "tif" -> "tif"; case "png" -> "png"; case "jpeg", "jpg" -> "jpg"; default -> throw new IllegalArgumentException("format must be tiff, png or jpeg."); };
        Path path = args.has("path") ? targetPath(string(args, "path", ""), extension) : exportPath(imp.getTitle().replaceAll("\\.[A-Za-z0-9]+$", ""), extension);
        var saver = new FileSaver(imp);
        boolean ok = switch (extension) { case "tif" -> imp.getStackSize() > 1 ? saver.saveAsTiffStack(path.toString()) : saver.saveAsTiff(path.toString()); case "png" -> saver.saveAsPng(path.toString()); default -> saver.saveAsJpeg(path.toString()); };
        if (!ok) throw new IllegalStateException("ImageJ could not write " + path);
        journal.record("save_image", "saveAs(\"" + (extension.equals("tif") ? "Tiff" : extension.equals("png") ? "PNG" : "Jpeg") + "\", \"" + Journal.escape(path.toString()) + "\");");
        return Map.of("path", path.toString(), "format", extension, "bytes", Files.size(path), "image", describe(imp));
    }

    // ----- snapshot / log -----
    Map<String, Object> snapshot() throws IOException {
        var imp = active(); ImageCanvas canvas = imp.getCanvas(); BufferedImage image; String source;
        if (canvas != null && canvas.isShowing() && canvas.getWidth() > 0 && canvas.getHeight() > 0) {
            image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); try { canvas.paint(graphics); } finally { graphics.dispose(); }
            source = "Fiji canvas (visible region, overlay and selection)";
        } else { image = imp.flatten().getBufferedImage(); source = "flattened image (no window)"; }
        double factor = Math.min(1, 1600.0 / Math.max(1, image.getWidth()));
        if (factor < 1) {
            var scaled = new BufferedImage((int) (image.getWidth() * factor), (int) (image.getHeight() * factor), BufferedImage.TYPE_INT_RGB);
            var graphics = scaled.createGraphics(); try { graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(image, 0, 0, scaled.getWidth(), scaled.getHeight(), null); } finally { graphics.dispose(); }
            image = scaled;
        }
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out);
        return Map.of("mimeType", "image/png", "data", Base64.getEncoder().encodeToString(out.toByteArray()), "width", image.getWidth(), "height", image.getHeight(), "source", source);
    }
    static Map<String, Object> log(JsonObject args) {
        String log = IJ.getLog(); int limit = integer(args, "limit", 100, 1, 2000);
        if (log == null) return Map.of("lines", List.of(), "total", 0);
        var lines = log.split("\n", -1);
        return Map.of("lines", Arrays.asList(lines).subList(Math.max(0, lines.length - limit), lines.length), "total", lines.length);
    }

    // ----- argument helpers -----
    static String string(JsonObject object, String name, String fallback) { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback; }
    static boolean bool(JsonObject object, String name, boolean fallback) { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsBoolean() : fallback; }
    static double number(JsonObject object, String name, double fallback) { double value = object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsDouble() : fallback; if (Double.isNaN(value)) throw new IllegalArgumentException(name + " must be a number"); return value; }
    static int integer(JsonObject object, String name, int fallback, int min, int max) { double value = number(object, name, fallback); if (value != Math.rint(value) || value < min || value > max) throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max); return (int) value; }
    static String plain(double value) { return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.format(Locale.US, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }
}
