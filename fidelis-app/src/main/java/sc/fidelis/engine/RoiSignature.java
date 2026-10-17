package sc.fidelis.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import sc.fidelis.engine.api.RoiGeometry;

/**
 * The signature of a document's active selection, for the reserved request key {@code expectedRoi} (implementation
 * only, like {@link DefaultEngine#exportMacroSince}; not part of {@code sc.fidelis.engine.api}). A client computes it
 * when a step is approved, from {@code Engine.activeRoi(document)}:
 *
 * <pre>    String expected = RoiSignature.of(engine.activeRoi(doc).orElse(null));</pre>
 *
 * and sends it with the step; just before the engine worker runs the step it computes the same signature of the live
 * selection (on the EDT, after canvas events already queued) and refuses the step with PRECONDITION_FAILED, subcode
 * {@code STATE_CHANGED}, when the two differ. Requests without the key behave as before.
 *
 * <p>Format: {@code none} when the document has no active selection, otherwise {@code TYPE:x,y,w,h:HASH} where
 * <ul>
 *   <li>{@code TYPE} is the IJ1 type name of {@link RoiGeometry#type()} (RECTANGLE, OVAL, POLYGON, FREEHAND, TRACED,
 *       LINE, POLYLINE, FREELINE, ANGLE, POINT, COMPOSITE);</li>
 *   <li>{@code x,y,w,h} are the float bounds: {@link RoiGeometry#bounds()} for RECTANGLE and OVAL, otherwise the extent
 *       of the vertices (min x, min y, max x - min x, max y - min y), each written as the shortest plain decimal that
 *       round-trips the double (no exponent, no trailing ".0"; an empty selection gives {@code 0,0,0,0});</li>
 *   <li>{@code HASH} is the first 16 lower-case hex digits of the SHA-256 of: the UTF-8 bytes of TYPE; the vertex count
 *       (int); every vertex as the IEEE-754 bits of x then y (float, int each); a 0/1 byte for bounds absent/present and
 *       then the four bounds (double bits, long each); the plane position c, z, t (int each, 0 = any, 0,0,0 when
 *       absent); the length of the encoded bytes (int, 0 when absent) and the bytes (COMPOSITE). All big-endian.</li>
 * </ul>
 * The ROI name is not part of it (renaming does not change what a step operates on).
 */
public final class RoiSignature {
    /** The signature of no active selection. */
    public static final String NONE = "none";

    private RoiSignature() {}

    /** Signature of {@code g}, or {@link #NONE} for null. */
    public static String of(RoiGeometry g) {
        if (g == null) return NONE;
        double[] b = boundsOf(g);
        return g.type() + ":" + Options.formatNumber(b[0]) + "," + Options.formatNumber(b[1]) + "," + Options.formatNumber(b[2])
                + "," + Options.formatNumber(b[3]) + ":" + hash(g);
    }

    private static double[] boundsOf(RoiGeometry g) {
        if (g.bounds() != null && g.bounds().length == 4) return g.bounds().clone();
        List<Float> xs = g.xs();
        List<Float> ys = g.ys();
        int n = Math.min(xs == null ? 0 : xs.size(), ys == null ? 0 : ys.size());
        if (n == 0) return new double[] {0, 0, 0, 0};
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double x = xs.get(i), y = ys.get(i);
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return new double[] {x0, y0, x1 - x0, y1 - y0};
    }

    private static String hash(RoiGeometry g) {
        List<Float> xs = g.xs() == null ? List.of() : g.xs();
        List<Float> ys = g.ys() == null ? List.of() : g.ys();
        int n = Math.min(xs.size(), ys.size());
        byte[] type = String.valueOf(g.type()).getBytes(StandardCharsets.UTF_8);
        byte[] enc = g.encoded() == null ? new byte[0] : g.encoded();
        ByteBuffer buf = ByteBuffer.allocate(type.length + 4 + n * 8 + 1 + 32 + 12 + 4 + enc.length);
        buf.put(type).putInt(n);
        for (int i = 0; i < n; i++) buf.putInt(Float.floatToIntBits(xs.get(i))).putInt(Float.floatToIntBits(ys.get(i)));
        double[] bounds = g.bounds();
        if (bounds != null && bounds.length == 4) {
            buf.put((byte) 1);
            for (double d : bounds) buf.putLong(Double.doubleToLongBits(d));
        } else {
            buf.put((byte) 0);
        }
        int[] pos = g.position() != null && g.position().length == 3 ? g.position() : new int[3];
        buf.putInt(pos[0]).putInt(pos[1]).putInt(pos[2]);
        buf.putInt(enc.length).put(enc);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(buf.array(), buf.position()));
            return Registry.hex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
