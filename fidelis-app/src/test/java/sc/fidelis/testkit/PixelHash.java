package sc.fidelis.testkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import ij.ImagePlus;
import ij.ImageStack;

/**
 * SHA-256 of pixel data, identical to the Python and JavaScript side (verification/dumper.js): raw
 * little-endian bytes of every plane in stack order. 8-bit planes are the stored bytes, 16-bit are
 * {@code short} little-endian, 32-bit are IEEE-754 float bits little-endian (NaN payloads preserved),
 * RGB is the packed ARGB {@code int} little-endian. Only the pixel buffers are hashed, never display
 * state, so it is the E1 criterion of docs A30.
 */
public final class PixelHash {
    private PixelHash() {}

    /** Digest over all planes, concatenated in stack order. */
    public static String sha256(ImagePlus imp) {
        MessageDigest md = digest();
        ImageStack stack = imp.getStack();
        int bits = imp.getBitDepth();
        for (int i = 1; i <= stack.getSize(); i++) md.update(planeBytes(stack.getPixels(i), bits));
        return HexFormat.of().formatHex(md.digest());
    }

    /** One digest per plane, in stack order. */
    public static List<String> planeSha256(ImagePlus imp) {
        List<String> out = new ArrayList<>();
        ImageStack stack = imp.getStack();
        int bits = imp.getBitDepth();
        for (int i = 1; i <= stack.getSize(); i++) {
            out.add(HexFormat.of().formatHex(digest().digest(planeBytes(stack.getPixels(i), bits))));
        }
        return out;
    }

    /** SHA-256 of arbitrary bytes (used for ROI masks and files). */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /** Raw little-endian bytes of one plane buffer ({@code byte[]}, {@code short[]}, {@code float[]}, {@code int[]}). */
    public static byte[] planeBytes(Object pixels, int bitDepth) {
        if (pixels instanceof byte[] b) return b;
        if (pixels instanceof short[] s) {
            ByteBuffer bb = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.asShortBuffer().put(s);
            return bb.array();
        }
        if (pixels instanceof float[] f) {
            ByteBuffer bb = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            bb.asFloatBuffer().put(f);
            return bb.array();
        }
        if (pixels instanceof int[] n) {
            ByteBuffer bb = ByteBuffer.allocate(n.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            bb.asIntBuffer().put(n);
            return bb.array();
        }
        throw new IllegalArgumentException("unsupported pixel buffer for " + bitDepth + "-bit data: "
                + (pixels == null ? "null" : pixels.getClass()));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
