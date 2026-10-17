package sc.fidelis.verification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import sc.fidelis.testkit.PixelHash;

/**
 * State of a session as the tests compare it: images (pixel hashes), the Results table at full double
 * precision, ROI geometry and result-affecting globals (docs A30 E1, E3-E6). It is captured by Java code
 * that shares nothing with verification/dumper.js, so a dumper bug cannot hide a difference.
 */
public record Snapshot(List<Img> images, Table results, List<RoiSnap> rois, Map<String, Object> globals) {

    public record Img(String title, int bitDepth, int width, int height, String allPlanesSha256,
                      List<String> planeSha256) {}

    /** Column name to values, in table order; {@code labels} is the optional Label column. */
    public record Table(int rows, LinkedHashMap<String, double[]> columns, List<String> labels) {}

    public record RoiSnap(String name, float[] xs, float[] ys, String maskSha256) {}

    /** Capture the live ImageJ session (images in creation order, system Results table, ROI Manager). */
    public static Snapshot capture() {
        List<Img> images = new ArrayList<>();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            Integer[] sorted = Arrays.stream(ids).boxed().sorted((a, b) -> b - a).toArray(Integer[]::new);
            for (int id : sorted) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) images.add(image(imp));
            }
        }
        List<RoiSnap> rois = new ArrayList<>();
        RoiManager rm = RoiManager.getInstance2();
        if (rm != null) for (Roi roi : rm.getRoisAsArray()) rois.add(roi(roi));
        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("blackBackground", Prefs.blackBackground);
        globals.put("measurements", Analyzer.getMeasurements());
        globals.put("precision", Analyzer.getPrecision());
        return new Snapshot(images, table(Analyzer.getResultsTable()), rois, globals);
    }

    /** Rebuild a snapshot from a dumper JSON step (verification/dumper.js), so two dumps can be compared. */
    public static Snapshot fromDump(JsonNode step) {
        List<Img> images = new ArrayList<>();
        for (JsonNode i : step.get("images")) {
            List<String> planes = new ArrayList<>();
            i.get("planeSha256").forEach(p -> planes.add(p.asText()));
            images.add(new Img(i.get("title").asText(), i.get("bitDepth").asInt(), i.get("width").asInt(),
                    i.get("height").asInt(), i.get("allPlanesSha256").asText(), planes));
        }
        LinkedHashMap<String, double[]> columns = new LinkedHashMap<>();
        int rows = 0;
        JsonNode results = step.get("results");
        if (results != null && !results.isNull()) {
            rows = results.get("rows").asInt();
            for (JsonNode c : results.get("columns")) {
                if (c.has("labels")) continue;
                double[] v = new double[rows];
                for (int r = 0; r < rows; r++) v[r] = Double.parseDouble(c.get("values").get(r).asText());
                columns.put(c.get("name").asText(), v);
            }
        }
        List<RoiSnap> rois = new ArrayList<>();
        JsonNode manager = step.get("roiManager");
        if (manager != null && manager.isArray()) {
            for (JsonNode r : manager) {
                float[] xs = new float[r.get("xs").size()];
                float[] ys = new float[xs.length];
                for (int k = 0; k < xs.length; k++) {
                    xs[k] = Float.parseFloat(r.get("xs").get(k).asText());
                    ys[k] = Float.parseFloat(r.get("ys").get(k).asText());
                }
                rois.add(new RoiSnap(r.get("name").isNull() ? null : r.get("name").asText(), xs, ys,
                        r.get("maskSha256").isNull() ? null : r.get("maskSha256").asText()));
            }
        }
        Map<String, Object> globals = new LinkedHashMap<>();
        step.get("globals").fields().forEachRemaining(e -> globals.put(e.getKey(), e.getValue().asText()));
        return new Snapshot(images, new Table(rows, columns, List.of()), rois, globals);
    }

    public static Img image(ImagePlus imp) {
        return new Img(imp.getTitle(), imp.getBitDepth(), imp.getWidth(), imp.getHeight(),
                PixelHash.sha256(imp), PixelHash.planeSha256(imp));
    }

    public static Table table(ResultsTable rt) {
        LinkedHashMap<String, double[]> columns = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        int rows = rt.size();
        for (String heading : rt.getHeadings()) {
            if (heading.equals("Label")) {
                for (int r = 0; r < rows; r++) labels.add(rt.getLabel(r));
                continue;
            }
            int col = rt.getColumnIndex(heading);
            double[] values = new double[rows];
            for (int r = 0; r < rows; r++) values[r] = rt.getValueAsDouble(col, r);
            columns.put(heading, values);
        }
        return new Table(rows, columns, labels);
    }

    public static RoiSnap roi(Roi roi) {
        FloatPolygon fp = roi.getFloatPolygon();
        ImageProcessor mask = roi.getMask();
        return new RoiSnap(roi.getName(), Arrays.copyOf(fp.xpoints, fp.npoints), Arrays.copyOf(fp.ypoints, fp.npoints),
                mask == null ? null : PixelHash.sha256((byte[]) mask.getPixels()));
    }
}
