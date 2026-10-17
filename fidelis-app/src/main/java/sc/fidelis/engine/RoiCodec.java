package sc.fidelis.engine;

import java.awt.geom.Rectangle2D;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.process.FloatPolygon;
import sc.fidelis.engine.api.RoiGeometry;

/** Conversions between IJ1 {@link Roi} objects and the contract's float-precision {@link RoiGeometry}, and D07 G5 macro lines. */
final class RoiCodec {
    private RoiCodec() {}

    static String typeName(int type) {
        return switch (type) {
            case Roi.RECTANGLE -> "RECTANGLE";
            case Roi.OVAL -> "OVAL";
            case Roi.POLYGON -> "POLYGON";
            case Roi.FREEROI -> "FREEHAND";
            case Roi.TRACED_ROI -> "TRACED";
            case Roi.LINE -> "LINE";
            case Roi.POLYLINE -> "POLYLINE";
            case Roi.FREELINE -> "FREELINE";
            case Roi.ANGLE -> "ANGLE";
            case Roi.COMPOSITE -> "COMPOSITE";
            case Roi.POINT -> "POINT";
            default -> "POLYGON";
        };
    }

    static RoiGeometry toGeometry(Roi roi) {
        int type = roi.getType();
        String name = roi.getName();
        int[] pos = {roi.getCPosition(), roi.getZPosition(), roi.getTPosition()};
        if (pos[0] == 0 && pos[1] == 0 && pos[2] == 0 && roi.getPosition() > 0) pos = new int[] {0, roi.getPosition(), 0};
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        double[] bounds = null;
        byte[] encoded = null;
        if (type == Roi.RECTANGLE || type == Roi.OVAL) {
            Rectangle2D.Double b = roi.getFloatBounds();
            bounds = new double[] {b.x, b.y, b.width, b.height};
            xs.add((float) b.x);
            xs.add((float) (b.x + b.width));
            ys.add((float) b.y);
            ys.add((float) (b.y + b.height));
        } else if (roi instanceof Line l) {
            xs.add((float) l.x1d);
            xs.add((float) l.x2d);
            ys.add((float) l.y1d);
            ys.add((float) l.y2d);
        } else {
            FloatPolygon p = roi.getFloatPolygon();
            if (p != null) {
                for (int i = 0; i < p.npoints; i++) {
                    xs.add(p.xpoints[i]);
                    ys.add(p.ypoints[i]);
                }
            }
            if (type == Roi.COMPOSITE) encoded = RoiEncoder.saveAsByteArray(roi);
        }
        return new RoiGeometry(typeName(type), xs, ys, bounds, name, pos, encoded);
    }

    /** Build an IJ1 ROI through the Java API (float coordinates survive; never via macro text). */
    static Roi toRoi(RoiGeometry g) {
        Roi roi;
        String t = g.type();
        if (t.equals("COMPOSITE")) {
            if (g.encoded() == null) throw new IllegalArgumentException("COMPOSITE ROI needs encoded bytes");
            roi = RoiDecoder.openFromByteArray(g.encoded());
        } else if (t.equals("RECTANGLE") || t.equals("OVAL")) {
            double[] b = g.bounds();
            if (b == null || b.length != 4) throw new IllegalArgumentException(t + " ROI needs bounds {x, y, w, h}");
            roi = t.equals("RECTANGLE") ? new Roi(b[0], b[1], b[2], b[3]) : new OvalRoi(b[0], b[1], b[2], b[3]);
        } else {
            int n = g.xs().size();
            if (n != g.ys().size() || n == 0) throw new IllegalArgumentException("ROI needs matching non-empty xs and ys");
            float[] xs = new float[n];
            float[] ys = new float[n];
            for (int i = 0; i < n; i++) {
                xs[i] = g.xs().get(i);
                ys[i] = g.ys().get(i);
            }
            roi = switch (t) {
                case "LINE" -> {
                    if (n != 2) throw new IllegalArgumentException("LINE ROI needs two points");
                    yield new Line(xs[0], ys[0], xs[1], ys[1]);
                }
                case "POINT" -> new PointRoi(xs, ys, n);
                case "POLYGON" -> new PolygonRoi(xs, ys, n, Roi.POLYGON);
                case "FREEHAND" -> new PolygonRoi(xs, ys, n, Roi.FREEROI);
                case "TRACED" -> new PolygonRoi(xs, ys, n, Roi.TRACED_ROI);
                case "POLYLINE" -> new PolygonRoi(xs, ys, n, Roi.POLYLINE);
                case "FREELINE" -> new PolygonRoi(xs, ys, n, Roi.FREELINE);
                case "ANGLE" -> new PolygonRoi(xs, ys, n, Roi.ANGLE);
                default -> throw new IllegalArgumentException("unsupported ROI type " + t);
            };
        }
        if (g.name() != null) roi.setName(g.name());
        int[] p = g.position();
        if (p != null && p.length == 3 && (p[0] > 0 || p[1] > 0 || p[2] > 0)) roi.setPosition(p[0], p[1], p[2]);
        return roi;
    }

    // ---- macro lines (D07 G5) ----------------------------------------------------------------------

    /** Exact macro lines reproducing the ROI, or an empty list when it has no exact macro form (composites). */
    static List<String> macroLines(RoiGeometry g) {
        List<String> out = new ArrayList<>();
        switch (g.type()) {
            case "RECTANGLE", "OVAL" -> {
                double[] b = g.bounds();
                out.add((g.type().equals("RECTANGLE") ? "makeRectangle(" : "makeOval(") + num(b[0]) + ", " + num(b[1]) + ", "
                        + num(b[2]) + ", " + num(b[3]) + ");");
            }
            case "LINE" -> out.add("makeLine(" + num(g.xs().get(0)) + ", " + num(g.ys().get(0)) + ", " + num(g.xs().get(1)) + ", "
                    + num(g.ys().get(1)) + ");");
            case "POINT" -> {
                if (g.xs().size() == 1 && isInt(g.xs().get(0)) && isInt(g.ys().get(0)))
                    out.add("makePoint(" + num(g.xs().get(0)) + ", " + num(g.ys().get(0)) + ");");
                else out.add(selection("point", g));
            }
            case "POLYGON" -> {
                if (allInt(g)) {
                    StringBuilder sb = new StringBuilder("makePolygon(");
                    for (int i = 0; i < g.xs().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(num(g.xs().get(i))).append(", ").append(num(g.ys().get(i)));
                    }
                    out.add(sb.append(");").toString());
                } else out.add(selection("polygon", g));
            }
            case "FREEHAND" -> out.add(selection("freehand", g));
            case "TRACED" -> out.add(selection("traced", g));
            case "POLYLINE" -> out.add(selection("polyline", g));
            case "FREELINE" -> out.add(selection("freeline", g));
            case "ANGLE" -> out.add(selection("angle", g));
            default -> {
                // COMPOSITE and anything else: no exact macro form
            }
        }
        return out;
    }

    private static String selection(String type, RoiGeometry g) {
        StringBuilder sb = new StringBuilder("makeSelection(\"" + type + "\", newArray(");
        for (int i = 0; i < g.xs().size(); i++) sb.append(i > 0 ? ", " : "").append(num(g.xs().get(i)));
        sb.append("), newArray(");
        for (int i = 0; i < g.ys().size(); i++) sb.append(i > 0 ? ", " : "").append(num(g.ys().get(i)));
        return sb.append("));").toString();
    }

    private static boolean allInt(RoiGeometry g) {
        for (float f : g.xs()) if (!isInt(f)) return false;
        for (float f : g.ys()) if (!isInt(f)) return false;
        return true;
    }

    private static boolean isInt(double d) {
        return d == Math.rint(d);
    }

    /** Shortest decimal text that round-trips the double (at least 9 significant digits for float32 values). */
    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }
}
