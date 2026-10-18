package sc.fidelis.penny;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.filter.EDM;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.FloatPolygon;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.RoiGeometry;

/**
 * "These should be separate": one outline split along its distance-map watershed (the rule Analyze Particles' watershed
 * uses, applied to this object alone), at the gentlest tolerance that gives at least two pieces of the required size.
 * Pure geometry: no pixels of the document are read or changed; the engine applies the result as ROI-list steps.
 */
final class Split {
    /** Gentlest first: a clear waist separates at 0.5; touching nuclei with a shallow waist need less. */
    static final double[] TOLERANCES = {0.5, 0.3, 0.15};

    private Split() {}

    /** The pieces at the first tolerance that separates the outline, or empty; {@code used[0]} receives that tolerance. */
    static List<RoiGeometry> pieces(RoiGeometry g, int minPart, double[] used) {
        Roi roi = outline(g);
        if (roi == null) return List.of();
        for (double tolerance : TOLERANCES) {
            List<RoiGeometry> parts = split(roi, tolerance, minPart, g.position());
            if (parts.size() >= 2) {
                used[0] = tolerance;
                return parts;
            }
        }
        return List.of();
    }

    /** The outline as IJ1 draws it: rectangles and ovals from their bounds, polygons from their vertices; a composite (holes,
     *  several blobs) or a line is not one outline and gives null. */
    static Roi outline(RoiGeometry g) {
        if (g == null) return null;
        String t = g.type();
        if ("RECTANGLE".equals(t) || "OVAL".equals(t)) {
            double[] b = g.bounds();
            if (b == null || b.length != 4) return null;
            return "OVAL".equals(t) ? new ij.gui.OvalRoi(b[0], b[1], b[2], b[3]) : new Roi(b[0], b[1], b[2], b[3]);
        }
        if (!("TRACED".equals(t) || "POLYGON".equals(t) || "FREEHAND".equals(t))) return null;
        if (g.xs() == null || g.ys() == null || g.xs().size() < 3) return null;
        int n = g.xs().size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) { xs[i] = g.xs().get(i); ys[i] = g.ys().get(i); }
        return new PolygonRoi(xs, ys, n, "TRACED".equals(t) ? Roi.TRACED_ROI : "FREEHAND".equals(t) ? Roi.FREEROI : Roi.POLYGON);
    }

    private static List<RoiGeometry> split(Roi roi, double tolerance, int minPart, int[] position) {
        Rectangle b = roi.getBounds();
        int w = b.width + 2, h = b.height + 2;
        ByteProcessor mask = new ByteProcessor(w, h);
        Roi local = (Roi) roi.clone();
        local.setLocation(local.getXBase() - b.x + 1, local.getYBase() - b.y + 1);
        mask.setColor(255);
        mask.fill(local);
        FloatProcessor edm = new EDM().makeFloatEDM(mask, 0, false);
        ByteProcessor segmented = new MaximumFinder().findMaxima(edm, tolerance, ImageProcessor.NO_THRESHOLD, MaximumFinder.SEGMENTED, false, true);
        if (segmented == null) return List.of();
        byte[] m = (byte[]) mask.getPixels(), s = (byte[]) segmented.getPixels();
        for (int i = 0; i < m.length; i++) if (m[i] == 0) s[i] = 0;
        int[] labels = new int[w * h];
        List<Integer> areas = label(s, w, h, labels);
        List<RoiGeometry> pieces = new ArrayList<>();
        for (int k = 0; k < areas.size(); k++) {
            if (areas.get(k) < minPart) continue;
            ByteProcessor part = new ByteProcessor(w, h);
            byte[] pp = (byte[]) part.getPixels();
            for (int j = 0; j < labels.length; j++) if (labels[j] == k + 1) pp[j] = (byte) 255;
            part.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
            Roi piece = new ThresholdToSelection().convert(part);
            if (piece == null) continue;
            piece.setLocation(piece.getXBase() + b.x - 1, piece.getYBase() + b.y - 1);
            FloatPolygon p = piece.getFloatPolygon();
            if (p == null || p.npoints < 3) continue;
            List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int i = 0; i < p.npoints; i++) { xs.add(p.xpoints[i]); ys.add(p.ypoints[i]); }
            // a composite (holes, several blobs) is kept as its outer polygon: the pieces of one object are simple shapes
            // the pieces stay on the object's plane (channel, slice, frame), as the object was
            pieces.add(new RoiGeometry("TRACED", xs, ys, null, null, position == null ? null : position.clone(), null));
        }
        return pieces.size() >= 2 ? pieces : List.of();
    }

    /** The union of two outlines as one traced outline, or null when they do not touch (within {@code gap} pixels). */
    static RoiGeometry union(RoiGeometry a, RoiGeometry b, int gap) {
        Roi ra = outline(a), rb = outline(b);
        if (ra == null || rb == null) return null;
        Rectangle bounds = ra.getBounds().union(rb.getBounds());
        int w = bounds.width + 2 * gap + 2, h = bounds.height + 2 * gap + 2;
        ByteProcessor mask = new ByteProcessor(w, h);
        mask.setColor(255);
        for (Roi r : new Roi[] {ra, rb}) {
            Roi local = (Roi) r.clone();
            local.setLocation(local.getXBase() - bounds.x + gap + 1, local.getYBase() - bounds.y + gap + 1);
            mask.fill(local);
        }
        // touching: one connected component once each outline is grown by the gap
        ByteProcessor grown = (ByteProcessor) mask.duplicate();
        for (int i = 0; i < gap; i++) grown.filter(ImageProcessor.MAX); // a 3 x 3 maximum: grows by one pixel, whatever the background convention
        int[] labels = new int[w * h];
        if (label((byte[]) grown.getPixels(), w, h, labels).size() != 1) return null;
        // the merged object: the outlines plus the gap between them filled (the grown mask eroded back)
        ByteProcessor merged = grown;
        for (int i = 0; i < gap; i++) merged.filter(ImageProcessor.MIN);
        byte[] m = (byte[]) mask.getPixels(), g = (byte[]) merged.getPixels();
        for (int i = 0; i < m.length; i++) if (m[i] != 0) g[i] = (byte) 255;
        merged.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        Roi one = new ThresholdToSelection().convert(merged);
        if (one == null) return null;
        one.setLocation(one.getXBase() + bounds.x - gap - 1, one.getYBase() + bounds.y - gap - 1);
        FloatPolygon p = one.getFloatPolygon();
        if (p == null || p.npoints < 3) return null;
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (int i = 0; i < p.npoints; i++) { xs.add(p.xpoints[i]); ys.add(p.ypoints[i]); }
        return new RoiGeometry("TRACED", xs, ys, null, a.name(), a.position(), null);
    }

    /** 8-connected labelling of a binary byte image; returns each label's pixel count, labels start at 1. */
    static List<Integer> label(byte[] p, int w, int h, int[] labels) {
        List<Integer> areas = new ArrayList<>();
        int[] queue = new int[w * h];
        for (int start = 0; start < p.length; start++) {
            if (p[start] == 0 || labels[start] != 0) continue;
            int id = areas.size() + 1, head = 0, tail = 0;
            queue[tail++] = start;
            labels[start] = id;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = ny * w + nx;
                    if (p[j] != 0 && labels[j] == 0) { labels[j] = id; queue[tail++] = j; }
                }
            }
            areas.add(tail);
        }
        return areas;
    }
}
