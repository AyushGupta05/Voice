package science.luma.fiji;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.filter.GaussianBlur;
import ij.process.AutoThresholder;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.*;

/**
 * Time-lapse analysis: object tracking (track_objects) and ROI intensity kinetics (intensity_over_time).
 * Pixels are never changed; the user's frame and selection are untouched. Values are exact ImageJ statistics. EDT only.
 */
final class Tracking {
    static final int MAX_FRAMES = 2000, MAX_OBJECTS = 5000, MAX_TABLE_ROWS = 200_000, MAX_OVERLAY_POINTS = 20_000, MAX_ROIS = 100;
    private final Operations ops;
    private final Journal journal;

    Tracking(Operations ops, Journal journal) { this.ops = ops; this.journal = journal; }

    /** One detected object in one frame (image pixel coordinates, 0-based frame index within the time axis). */
    record Spot(int frame, double x, double y, double area, double mean, double rawIntDen, Rectangle bounds) {}

    /** The time axis of an image: real frames, or slices when there is only one frame. */
    record Axis(boolean slicesAsTime, int length) {
        String name() { return slicesAsTime ? "slice" : "frame"; }
    }
    static Axis axis(ImagePlus imp) {
        if (imp.getNFrames() > 1) return new Axis(false, imp.getNFrames());
        if (imp.getNSlices() > 1) return new Axis(true, imp.getNSlices());
        throw new IllegalArgumentException("This image has only one frame; this needs a time-lapse.");
    }
    /** Stack index of time point t (1-based) on the given channel, keeping the user's current z (or frame) for the other axis. */
    static int stackIndex(ImagePlus imp, Axis axis, int channel, int t) {
        return axis.slicesAsTime() ? imp.getStackIndex(channel, t, imp.getT()) : imp.getStackIndex(channel, imp.getZ(), t);
    }
    static int channel(ImagePlus imp, JsonObject args) {
        return Operations.integer(args, "channel", imp.getC(), 1, Math.max(1, imp.getNChannels()));
    }

    // ===================================================================== track_objects

    Map<String, Object> trackObjects(JsonObject args) {
        var imp = ops.active();
        var axis = axis(imp);
        int channel = channel(imp, args);
        String method = method(args);
        boolean dark = Operations.bool(args, "dark", true);
        double minSize = Operations.number(args, "minSize", 4), maxSize = Operations.number(args, "maxSize", Double.POSITIVE_INFINITY);
        if (minSize < 0 || maxSize < minSize) throw new IllegalArgumentException("minSize must be at least 0 and no larger than maxSize.");
        int maxGap = Operations.integer(args, "maxGap", 1, 0, 50);
        int minLength = Operations.integer(args, "minLength", 3, 1, MAX_FRAMES);
        double sigma = Operations.number(args, "sigma", 1);
        if (sigma < 0 || sigma > 20) throw new IllegalArgumentException("sigma must be between 0 and 20 pixels.");
        int start = Operations.integer(args, "startFrame", 1, 1, axis.length());
        int end = Operations.integer(args, "endFrame", axis.length(), 1, axis.length());
        if (end <= start) throw new IllegalArgumentException("endFrame must come after startFrame.");
        boolean truncated = false;
        if (end - start + 1 > MAX_FRAMES) { end = start + MAX_FRAMES - 1; truncated = true; }
        boolean replace = Operations.bool(args, "replaceOverlay", true);

        // --- detection
        var stack = imp.getStack();
        String thresholdMode = Operations.string(args, "thresholdMode", "global");
        if (!thresholdMode.equals("global") && !thresholdMode.equals("frame")) throw new IllegalArgumentException("thresholdMode must be global or frame.");
        // Pass 1: the auto threshold of every smoothed frame. "global" uses their median for all frames, so a frame with few objects does not threshold its noise.
        var cuts = new double[end - start + 1];
        for (int t = start; t <= end; t++) cuts[t - start] = cut(smooth(stack.getProcessor(stackIndex(imp, axis, channel, t)), sigma), method, dark);
        var finite = new ArrayList<Double>(); for (double c : cuts) if (Double.isFinite(c)) finite.add(c);
        double globalCut = median(finite);
        var perFrame = new ArrayList<List<Spot>>();
        var counts = new ArrayList<Integer>();
        for (int t = start; t <= end; t++) {
            var original = stack.getProcessor(stackIndex(imp, axis, channel, t));
            double cut = thresholdMode.equals("global") ? globalCut : cuts[t - start];
            List<Spot> spots = finite.isEmpty() || !Double.isFinite(cut) ? new ArrayList<>() : detect(original, smooth(original, sigma), cut, dark, t - start, minSize, maxSize);
            if (spots.size() > MAX_OBJECTS) { spots = new ArrayList<>(spots.subList(0, MAX_OBJECTS)); truncated = true; }
            perFrame.add(spots); counts.add(spots.size());
        }
        int total = counts.stream().mapToInt(Integer::intValue).sum();
        if (total == 0) throw new IllegalArgumentException("No objects were found with " + method + (dark ? "; if the objects are dark on a bright background, set dark to false." : "; try another threshold method."));

        // --- linking
        double maxDistance;
        if (args.has("maxDistance") && !args.get("maxDistance").isJsonNull()) {
            maxDistance = Operations.number(args, "maxDistance", 0);
            if (maxDistance <= 0) throw new IllegalArgumentException("maxDistance must be greater than 0 pixels.");
        } else {
            var radii = new ArrayList<Double>();
            for (var spots : perFrame) for (var s : spots) radii.add(Math.sqrt(s.area() / Math.PI));
            double median = median(radii);
            maxDistance = Math.max(2, Math.min(3 * median, Math.min(imp.getWidth(), imp.getHeight()) / 4.0));
        }
        var linking = link(perFrame, maxDistance, maxGap);
        var tracks = new ArrayList<List<Spot>>();
        for (var track : linking.tracks()) if (track.size() >= minLength) tracks.add(track);
        tracks.sort(Comparator.comparingInt((List<Spot> t) -> -t.size()).thenComparingInt(t -> t.getFirst().frame()));

        // --- calibration
        Calibration cal = imp.getCalibration();
        boolean spatial = cal.scaled();
        String unit = spatial ? cal.getUnit() : "pixel";
        double pw = spatial ? cal.pixelWidth : 1, ph = spatial ? cal.pixelHeight : 1;
        boolean timed = !axis.slicesAsTime() && cal.frameInterval > 0;
        double dt = timed ? cal.frameInterval : 1;
        String timeUnit = timed ? cal.getTimeUnit() : axis.name();

        // --- table, statistics, overlay
        var table = new ResultsTable();
        var overlay = imp.getOverlay() == null ? new Overlay() : imp.getOverlay();
        int removed = 0;
        if (replace) for (int i = overlay.size() - 1; i >= 0; i--) { var r = overlay.get(i); if (r.getName() != null && r.getName().startsWith("Track ")) { overlay.remove(i); removed++; } }
        int points = 0;
        var top = new ArrayList<Map<String, Object>>();
        var bounds = new ArrayList<Rectangle>();
        double sumSpeed = 0, sumDisplacement = 0, sumPath = 0; int speedTracks = 0, longest = 0;
        for (int n = 0; n < tracks.size(); n++) {
            var track = tracks.get(n); int id = n + 1;
            double path = 0; Rectangle box = null;
            float[] xs = new float[track.size()], ys = new float[track.size()];
            Color color = Color.getHSBColor((float) ((n * 0.618033988749895) % 1.0), 0.85f, 1f);
            for (int i = 0; i < track.size(); i++) {
                var s = track.get(i);
                double speed = Double.NaN;
                if (i > 0) {
                    var p = track.get(i - 1);
                    double d = Math.hypot((s.x() - p.x()) * pw, (s.y() - p.y()) * ph);
                    path += d; speed = d / ((s.frame() - p.frame()) * dt);
                }
                xs[i] = (float) (s.x() + 0.5); ys[i] = (float) (s.y() + 0.5);
                box = box == null ? new Rectangle(s.bounds()) : box.union(s.bounds());
                if (table.size() < MAX_TABLE_ROWS) {
                    table.incrementCounter();
                    table.addValue("Track", id); table.addValue("Frame", s.frame() + start);
                    table.addValue("X", s.x() * pw); table.addValue("Y", s.y() * ph);
                    table.addValue("Area", s.area() * pw * ph); table.addValue("Mean", s.mean());
                    table.addValue("IntDen", s.area() * pw * ph * s.mean()); table.addValue("Speed", speed);
                } else truncated = true;
                if (points < MAX_OVERLAY_POINTS) {
                    var point = new PointRoi(xs[i], ys[i]);
                    point.setStrokeColor(color); point.setPointType(PointRoi.DOT); point.setSize(1); point.setName("Track " + id);
                    int t = s.frame() + start;
                    if (imp.isHyperStack()) { if (axis.slicesAsTime()) point.setPosition(0, t, 0); else point.setPosition(0, 0, t); }
                    else point.setPosition(t);
                    overlay.add(point); points++;
                } else truncated = true;
            }
            var line = track.size() == 1 ? new PointRoi(xs[0], ys[0]) : new PolygonRoi(xs, ys, Roi.POLYLINE);
            line.setStrokeColor(color); line.setStrokeWidth(1); line.setName("Track " + id); line.setPosition(0);
            overlay.add(line);
            var first = track.getFirst(); var last = track.getLast();
            double displacement = Math.hypot((last.x() - first.x()) * pw, (last.y() - first.y()) * ph);
            double duration = (last.frame() - first.frame()) * dt;
            double meanSpeed = duration > 0 ? path / duration : Double.NaN;
            if (Double.isFinite(meanSpeed)) { sumSpeed += meanSpeed; speedTracks++; }
            sumDisplacement += displacement; sumPath += path;
            longest = Math.max(longest, last.frame() - first.frame() + 1);
            if (n < 30) bounds.add(box);
            if (top.size() < 20) {
                double meanIntensity = track.stream().mapToDouble(Spot::mean).average().orElse(Double.NaN);
                var row = new LinkedHashMap<String, Object>();
                row.put("track", id); row.put("length", track.size());
                row.put("start", first.frame() + start); row.put("end", last.frame() + start);
                row.put("displacement", round(displacement)); row.put("pathLength", round(path)); row.put("meanSpeed", round(meanSpeed));
                row.put("meanIntensity", round(meanIntensity));
                row.put("intensityChangePercent", first.mean() != 0 ? round((last.mean() - first.mean()) / first.mean() * 100) : "NaN");
                top.add(row);
            }
        }
        imp.setOverlay(overlay);
        if (Operations.guiVisible() && table.size() > 0) table.show("Tracks");
        lastTable = table;

        var result = new LinkedHashMap<String, Object>();
        result.put("tracks", tracks.size());
        result.put("detections", total);
        result.put("objectsPerFrame", Map.of("min", Collections.min(counts), "max", Collections.max(counts), "mean", round(total / (double) counts.size())));
        result.put("frames", counts.size());
        result.put("timeAxis", axis.slicesAsTime() ? "slices" : "frames");
        result.put("range", Map.of("start", start, "end", end));
        result.put("channel", channel);
        result.put("longestTrack", longest);
        result.put("meanSpeed", speedTracks == 0 ? "NaN" : round(sumSpeed / speedTracks));
        result.put("meanDisplacement", tracks.isEmpty() ? "NaN" : round(sumDisplacement / tracks.size()));
        result.put("meanPathLength", tracks.isEmpty() ? "NaN" : round(sumPath / tracks.size()));
        result.put("unit", unit);
        result.put("speedUnit", unit + "/" + timeUnit);
        if (timed) { result.put("frameInterval", cal.frameInterval); result.put("timeUnit", timeUnit); }
        result.put("divisions", linking.divisions());
        result.put("shortTracksDropped", linking.tracks().size() - tracks.size());
        result.put("thresholdMode", thresholdMode);
        if (Double.isFinite(globalCut)) result.put(thresholdMode.equals("global") ? "threshold" : "medianThreshold", round(globalCut));
        result.put("parameters", parameters(method, dark, sigma, minSize, maxSize, maxDistance, maxGap, minLength));
        result.put("top", top);
        result.put("table", "Tracks");
        result.put("overlay", Map.of("tracks", tracks.size(), "points", points, "replacedTrackRois", removed));
        if (truncated) result.put("truncated", true);
        result.put("highlight", Viewer.rectHighlight(imp, bounds, tracks.size() + (tracks.size() == 1 ? " track" : " tracks"), 30, false));
        journal.record("track_objects", "// Penny track_objects: channel=" + channel + " " + axis.name() + "s " + start + "-" + end + " method=" + method + "(" + thresholdMode + ")" + (dark ? " dark" : "")
            + " sigma=" + Operations.plain(sigma) + " minSize=" + Operations.plain(minSize) + (Double.isFinite(maxSize) ? " maxSize=" + Operations.plain(maxSize) : "")
            + " maxDistance=" + Operations.plain(Math.round(maxDistance * 100) / 100.0) + " maxGap=" + maxGap + " minLength=" + minLength + " -> " + tracks.size() + " tracks (table \"Tracks\", overlay \"Track N\")");
        return result;
    }
    /** The last Tracks table, for tests and headless sessions where the table has no window. */
    ResultsTable lastTable;

    static String method(JsonObject args) {
        String requested = Operations.string(args, "method", "Otsu");
        for (var known : AutoThresholder.getMethods()) if (known.equalsIgnoreCase(requested)) return known;
        throw new IllegalArgumentException("Unknown threshold method " + requested + ". Try Otsu, Triangle, Li or Default.");
    }

    static FloatProcessor smooth(ImageProcessor original, double sigma) {
        FloatProcessor smooth = original.convertToFloatProcessor();
        if (sigma > 0) new GaussianBlur().blurGaussian(smooth, sigma, sigma, 0.002);
        return smooth;
    }
    /** The auto-threshold cut in intensity units (objects are >= cut when dark, < cut otherwise); NaN for a flat plane. */
    static double cut(FloatProcessor smooth, String method, boolean dark) {
        float[] pixels = (float[]) smooth.getPixels();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (float v : pixels) { if (v < min) min = v; if (v > max) max = v; }
        if (!(max > min)) return Double.NaN;
        int[] histogram = new int[256];
        double scale = 255.0 / (max - min);
        for (float v : pixels) histogram[(int) Math.min(255, Math.max(0, (v - min) * scale))]++;
        int level = new AutoThresholder().getThreshold(method, histogram);
        // ImageJ convention: with a dark background the objects are the bins above the level; otherwise the bins up to it.
        return min + (level + 1) / scale;
    }
    /** 8-connected components of the thresholded smoothed plane, measured on the original plane. */
    static List<Spot> detect(ImageProcessor original, FloatProcessor smooth, double cut, boolean dark, int frame, double minSize, double maxSize) {
        int w = original.getWidth(), h = original.getHeight();
        float[] pixels = (float[]) smooth.getPixels();
        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < pixels.length; i++) mask[i] = dark ? pixels[i] >= cut : pixels[i] < cut;
        int[] queue = new int[w * h];
        var spots = new ArrayList<Spot>();
        for (int seed = 0; seed < mask.length; seed++) {
            if (!mask[seed]) continue;
            mask[seed] = false;
            int head = 0, tail = 0; queue[tail++] = seed;
            double sx = 0, sy = 0, sum = 0; int count = 0, x0 = w, y0 = h, x1 = -1, y1 = -1;
            while (head < tail) {
                int p = queue[head++], px = p % w, py = p / w;
                sx += px; sy += py; sum += original.getPixelValue(px, py); count++;
                x0 = Math.min(x0, px); y0 = Math.min(y0, py); x1 = Math.max(x1, px); y1 = Math.max(y1, py);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int q = ny * w + nx;
                    if (mask[q]) { mask[q] = false; queue[tail++] = q; }
                }
            }
            if (count < minSize || count > maxSize) continue;
            spots.add(new Spot(frame, sx / count, sy / count, count, sum / count, sum, new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1)));
        }
        return spots;
    }

    record Linking(List<List<Spot>> tracks, int divisions) {}

    /** Greedy global nearest-neighbour linking (shortest links first) with gap closing up to maxGap missing frames. */
    static Linking link(List<List<Spot>> perFrame, double maxDistance, int maxGap) {
        var tracks = new ArrayList<List<Spot>>();
        var open = new ArrayList<List<Spot>>();
        int divisions = 0;
        for (int f = 0; f < perFrame.size(); f++) {
            var spots = perFrame.get(f);
            final int frame = f;
            open.removeIf(track -> frame - track.getLast().frame() > maxGap + 1);
            record Candidate(int track, int spot, double cost) {}
            var candidates = new ArrayList<Candidate>();
            for (int i = 0; i < open.size(); i++) {
                var last = open.get(i).getLast(); int gap = f - last.frame();
                double reach = maxDistance * gap;
                for (int j = 0; j < spots.size(); j++) {
                    var s = spots.get(j);
                    double dx = s.x() - last.x(); if (Math.abs(dx) > reach) continue;
                    double d = Math.hypot(dx, s.y() - last.y());
                    // Prefer continuous links: a gap link costs its distance per frame plus a full maxDistance per missed frame.
                    if (d <= reach) candidates.add(new Candidate(i, j, d / gap + (gap - 1) * maxDistance));
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::cost));
            boolean[] trackUsed = new boolean[open.size()], spotUsed = new boolean[spots.size()];
            var parents = new HashMap<Integer, Spot>();
            for (var c : candidates) {
                if (trackUsed[c.track()] || spotUsed[c.spot()]) continue;
                trackUsed[c.track()] = true; spotUsed[c.spot()] = true;
                var track = open.get(c.track());
                if (track.getLast().frame() == f - 1) parents.put(c.spot(), track.getLast());
                track.add(spots.get(c.spot()));
            }
            var started = new ArrayList<List<Spot>>();
            for (int j = 0; j < spots.size(); j++) {
                if (spotUsed[j]) continue;
                var s = spots.get(j);
                // A simple division: a new object next to a sibling that was just linked to a larger parent.
                for (var entry : parents.entrySet()) {
                    var parent = entry.getValue(); var sibling = spots.get(entry.getKey());
                    if (Math.hypot(s.x() - parent.x(), s.y() - parent.y()) <= maxDistance && s.area() < 0.8 * parent.area() && sibling.area() < 0.8 * parent.area()) { divisions++; parents.remove(entry.getKey()); break; }
                }
                var track = new ArrayList<Spot>(); track.add(s); started.add(track); tracks.add(track);
            }
            open.addAll(started);
        }
        // tracks were appended when started; their spots were added in place.
        return new Linking(tracks, divisions);
    }

    static Map<String, Object> parameters(String method, boolean dark, double sigma, double minSize, double maxSize, double maxDistance, int maxGap, int minLength) {
        var p = new LinkedHashMap<String, Object>();
        p.put("method", method); p.put("dark", dark); p.put("sigma", sigma); p.put("minSize", minSize);
        p.put("maxSize", Double.isFinite(maxSize) ? maxSize : "Infinity"); p.put("maxDistance", round(maxDistance));
        p.put("maxGap", maxGap); p.put("minLength", minLength);
        return p;
    }

    // ===================================================================== intensity_over_time

    Map<String, Object> intensityOverTime(JsonObject args) {
        var imp = ops.active();
        var axis = axis(imp);
        int channel = channel(imp, args);
        String scope = Operations.string(args, "scope", "active");
        var rois = new ArrayList<Roi>(); var names = new ArrayList<String>();
        boolean wholeImage = false;
        switch (scope) {
            case "active" -> {
                var roi = imp.getRoi();
                if (roi == null || !roi.isArea()) { roi = new Roi(0, 0, imp.getWidth(), imp.getHeight()); wholeImage = true; }
                rois.add(roi); names.add(wholeImage ? "Whole image" : roi.getName() == null ? "Selection" : roi.getName());
            }
            case "manager" -> {
                var manager = ops.managerOrNull();
                var all = manager == null ? new Roi[0] : manager.getRoisAsArray();
                if (all.length == 0) throw new IllegalArgumentException("The ROI Manager is empty; add regions first or use the current selection.");
                var indices = new ArrayList<Integer>();
                if (args.has("indices") && args.get("indices").isJsonArray()) {
                    for (JsonElement e : args.getAsJsonArray("indices")) {
                        int i = e.getAsInt();
                        if (i < 0 || i >= all.length) throw new IllegalArgumentException("ROI index " + i + " is not in the ROI Manager (it has " + all.length + ").");
                        indices.add(i);
                    }
                } else for (int i = 0; i < all.length; i++) indices.add(i);
                for (int i : indices) {
                    if (!all[i].isArea()) continue;
                    if (rois.size() >= MAX_ROIS) break;
                    rois.add(all[i]); names.add(all[i].getName() == null ? "ROI " + (i + 1) : all[i].getName());
                }
                if (rois.isEmpty()) throw new IllegalArgumentException("None of those ROIs is an area to measure.");
            }
            default -> throw new IllegalArgumentException("scope must be active or manager.");
        }
        int frames = Math.min(axis.length(), MAX_FRAMES);
        boolean truncated = frames < axis.length() || ("manager".equals(scope) && rois.size() >= MAX_ROIS);
        Calibration cal = imp.getCalibration();
        var stack = imp.getStack();
        int flags = Measurements.MEAN | Measurements.AREA;
        double[][] means = new double[rois.size()][frames], intDens = new double[rois.size()][frames];
        double[] whole = new double[frames];
        for (int t = 1; t <= frames; t++) {
            ImageProcessor ip = stack.getProcessor(stackIndex(imp, axis, channel, t));
            ip.setCalibrationTable(cal.getCTable());
            for (int r = 0; r < rois.size(); r++) {
                ip.setRoi(rois.get(r));
                var stats = ImageStatistics.getStatistics(ip, flags, cal);
                means[r][t - 1] = stats.pixelCount == 0 ? Double.NaN : stats.mean;
                intDens[r][t - 1] = stats.pixelCount == 0 ? Double.NaN : stats.area * stats.mean;
            }
            ip.resetRoi();
            whole[t - 1] = ImageStatistics.getStatistics(ip, Measurements.MEAN, cal).mean;
        }
        var table = new ResultsTable();
        for (int t = 0; t < frames; t++) for (int r = 0; r < rois.size(); r++) {
            table.incrementCounter();
            table.addValue("Frame", t + 1); table.addValue("ROI", names.get(r));
            table.addValue("Mean", means[r][t]); table.addValue("IntDen", intDens[r][t]);
        }
        if (Operations.guiVisible()) table.show("Intensity over time");
        lastTable = table;

        int step = Math.max(1, (int) Math.ceil(frames / 200.0));
        var series = new ArrayList<Map<String, Object>>();
        for (int r = 0; r < Math.min(20, rois.size()); r++) {
            double[] v = means[r];
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY; int peak = 1;
            for (int t = 0; t < frames; t++) { if (v[t] < lo) lo = v[t]; if (v[t] > hi) { hi = v[t]; peak = t + 1; } }
            var values = new ArrayList<Object>();
            for (int t = 0; t < frames; t += step) values.add(round(v[t]));
            var row = new LinkedHashMap<String, Object>();
            row.put("roi", names.get(r)); row.put("first", round(v[0])); row.put("last", round(v[frames - 1]));
            row.put("min", round(lo)); row.put("max", round(hi));
            row.put("changePercent", v[0] != 0 && Double.isFinite(v[0]) ? round((v[frames - 1] - v[0]) / v[0] * 100) : "NaN");
            row.put("peakFrame", peak); row.put("values", values);
            if (step > 1) row.put("valuesEvery", step);
            series.add(row);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("frames", frames);
        result.put("timeAxis", axis.slicesAsTime() ? "slices" : "frames");
        result.put("channel", channel);
        result.put("rois", rois.size());
        if (wholeImage) result.put("wholeImage", true);
        result.put("series", series);
        if (whole[0] != 0) result.put("bleachingEstimate", Map.of("firstMean", round(whole[0]), "lastMean", round(whole[frames - 1]), "changePercent", round((whole[frames - 1] - whole[0]) / whole[0] * 100)));
        if (!axis.slicesAsTime() && cal.frameInterval > 0) { result.put("frameInterval", cal.frameInterval); result.put("timeUnit", cal.getTimeUnit()); }
        result.put("table", "Intensity over time");
        if (truncated) result.put("truncated", true);
        result.put("highlight", wholeImage ? Viewer.imageHighlight(imp, "Intensity over " + frames + " " + axis.name() + "s")
            : Viewer.roiHighlight(imp, rois, "Intensity over " + frames + " " + axis.name() + "s", 30));
        journal.record("intensity_over_time", "// Penny intensity_over_time: channel=" + channel + " scope=" + scope + " rois=" + rois.size() + " " + axis.name() + "s=" + frames + " (table \"Intensity over time\"; equivalent to Plot Z-axis Profile / ROI Manager Multi Measure)");
        return result;
    }

    // ===================================================================== helpers

    static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        var sorted = new ArrayList<>(values); Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
    static Object round(double value) {
        if (!Double.isFinite(value)) return "NaN";
        return Math.round(value * 10000.0) / 10000.0;
    }
}
