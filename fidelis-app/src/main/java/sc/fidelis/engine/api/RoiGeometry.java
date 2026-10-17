package sc.fidelis.engine.api;

import java.util.List;

/**
 * Float-precision ROI geometry exchanged with the engine (docs A10, FR-ROI-001).
 * Coordinates follow the IJ1 conventions: area outlines use pixel-corner coordinates, lines use
 * pixel centres. The engine constructs {@code ij.gui.Roi} objects through the Java API, never
 * through macro text, so sub-pixel vertices survive.
 *
 * @param type      IJ1 type name: RECTANGLE, OVAL, POLYGON, FREEHAND, TRACED, LINE, POLYLINE,
 *                  FREELINE, ANGLE, POINT, COMPOSITE
 * @param xs        vertex x coordinates (for RECTANGLE/OVAL: x, x+w; see {@code bounds})
 * @param ys        vertex y coordinates
 * @param bounds    x, y, width, height for RECTANGLE/OVAL (null otherwise)
 * @param name      ROI name, or null
 * @param position  {channel, slice, frame} (0 = any), or null
 * @param encoded   the IJ1 {@code RoiEncoder} bytes when the ROI is not expressible by vertices
 *                  (COMPOSITE), or null
 */
public record RoiGeometry(String type, List<Float> xs, List<Float> ys, double[] bounds, String name,
                          int[] position, byte[] encoded) {
}
