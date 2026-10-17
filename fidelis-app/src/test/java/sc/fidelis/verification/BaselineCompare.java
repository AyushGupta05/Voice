package sc.fidelis.verification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import sc.fidelis.testkit.Fixtures;

/**
 * Exact comparison of a {@link Snapshot} with a stored reference step (JSON written by
 * verification/dumper.js; docs A30 E1, E3-E6). All values are bit-exact: pixel hashes, Results cells as
 * doubles (the baseline stores Java's {@code Double.toString}, which round-trips), ROI vertices as floats.
 * Differences are returned as readable strings; an empty list means equal.
 */
public final class BaselineCompare {
    private BaselineCompare() {}

    public static JsonNode loadStep(Path baselineDir, String tag) {
        return Fixtures.readJson(baselineDir.resolve(tag + ".json"));
    }

    /**
     * @param tolerateRetainedRows when true the live Results table may hold extra leading rows (a capture log
     *                             that keeps rows an IJ1 {@code clear} dropped); the last N rows must equal the baseline
     * @param checkTitles          compare image titles (E3 title rule)
     */
    public static List<String> diff(JsonNode step, Snapshot snap, boolean tolerateRetainedRows, boolean checkTitles) {
        List<String> out = new ArrayList<>();
        diffImages(step, snap, checkTitles, out);
        diffResults(step.get("results"), snap.results(), tolerateRetainedRows, out);
        diffRois(step.get("roiManager"), snap, out);
        return out;
    }

    public static void diffImages(JsonNode step, Snapshot snap, boolean checkTitles, List<String> out) {
        JsonNode images = step.get("images");
        if (images.size() != snap.images().size()) {
            out.add("E1 image count: baseline " + images.size() + " vs actual " + snap.images().size());
            return;
        }
        for (int i = 0; i < images.size(); i++) {
            JsonNode b = images.get(i);
            Snapshot.Img a = snap.images().get(i);
            String where = "image " + i + " (" + b.get("title").asText() + ")";
            if (checkTitles && !b.get("title").asText().equals(a.title())) {
                out.add("E3 " + where + " title: actual '" + a.title() + "'");
            }
            if (b.get("bitDepth").asInt() != a.bitDepth() || b.get("width").asInt() != a.width()
                    || b.get("height").asInt() != a.height()) {
                out.add("E3 " + where + " type/size: baseline " + b.get("bitDepth") + "-bit " + b.get("width") + "x"
                        + b.get("height") + " vs " + a.bitDepth() + "-bit " + a.width() + "x" + a.height());
            }
            if (!b.get("allPlanesSha256").asText().equals(a.allPlanesSha256())) {
                out.add("E1 " + where + " pixel SHA-256: baseline " + b.get("allPlanesSha256").asText() + " vs actual "
                        + a.allPlanesSha256());
            }
        }
    }

    public static void diffResults(JsonNode baseline, Snapshot.Table actual, boolean tolerateRetainedRows, List<String> out) {
        if (baseline == null || baseline.isNull()) return;
        int rows = baseline.get("rows").asInt();
        boolean rowsOk = tolerateRetainedRows ? actual.rows() >= rows : actual.rows() == rows;
        if (!rowsOk) {
            out.add("E5 row count: baseline " + rows + " vs actual " + actual.rows());
            return;
        }
        int offset = actual.rows() - rows;
        List<String> baselineNames = new ArrayList<>();
        for (JsonNode col : baseline.get("columns")) {
            String name = col.get("name").asText();
            if (col.has("labels")) continue;
            baselineNames.add(name);
            double[] values = actual.columns().get(name);
            if (values == null) {
                out.add("E5 column '" + name + "' missing from actual Results");
                continue;
            }
            JsonNode expected = col.get("values");
            for (int r = 0; r < rows; r++) {
                double want = Double.parseDouble(expected.get(r).asText());
                double got = values[r + offset];
                if (Double.doubleToLongBits(want) != Double.doubleToLongBits(got)) {
                    out.add("E5 " + name + "[" + r + "]: baseline " + expected.get(r).asText() + " vs actual " + got);
                    break;
                }
            }
        }
        List<String> actualNames = new ArrayList<>(actual.columns().keySet());
        if (!actualNames.equals(baselineNames)) {
            out.add("E5 column order/names: baseline " + baselineNames + " vs actual " + actualNames);
        }
    }

    public static void diffRois(JsonNode baseline, Snapshot snap, List<String> out) {
        if (baseline == null || baseline.isNull()) return;
        if (baseline.size() != snap.rois().size()) {
            out.add("E4 ROI count: baseline " + baseline.size() + " vs actual " + snap.rois().size());
            return;
        }
        for (int i = 0; i < baseline.size(); i++) {
            JsonNode b = baseline.get(i);
            Snapshot.RoiSnap a = snap.rois().get(i);
            if (!sameVertices(b.get("xs"), a.xs()) || !sameVertices(b.get("ys"), a.ys())) {
                out.add("E4 ROI " + i + " vertices differ");
            }
            if (a.name() != null && !b.get("name").isNull() && !a.name().equals(b.get("name").asText())) {
                out.add("E4 ROI " + i + " name: baseline " + b.get("name").asText() + " vs actual " + a.name());
            }
            if (a.maskSha256() != null && !b.get("maskSha256").isNull()
                    && !a.maskSha256().equals(b.get("maskSha256").asText())) {
                out.add("E4 ROI " + i + " mask hash differs");
            }
        }
    }

    private static boolean sameVertices(JsonNode expected, float[] actual) {
        if (expected.size() != actual.length) return false;
        Iterator<JsonNode> it = expected.iterator();
        for (float v : actual) {
            if (Float.floatToIntBits(Float.parseFloat(it.next().asText())) != Float.floatToIntBits(v)) return false;
        }
        return true;
    }

    /** E6: globals present in both the baseline step and the actual map must be equal. */
    public static List<String> diffGlobals(JsonNode step, Map<String, Object> actual, List<String> only) {
        List<String> out = new ArrayList<>();
        JsonNode b = step.get("globals");
        for (String key : only) {
            if (!actual.containsKey(key) || !b.has(key)) continue;
            String want = b.get(key).asText();
            if (!want.equals(String.valueOf(actual.get(key)))) {
                out.add("E6 global " + key + ": baseline " + want + " vs actual " + actual.get(key));
            }
        }
        return out;
    }
}
