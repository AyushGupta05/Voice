package sc.fidelis.engine;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import ij.ImagePlus;
import ij.ImageStack;

/** Pixel hashes for postconditions (D06 §5): SHA-256 over the raw sample values. */
final class Pixels {
    private Pixels() {}

    /** Hash of the current plane, or of every plane when {@code allPlanes} (stacks only). */
    static String hash(ImagePlus imp, boolean allPlanes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((imp.getType() + ":" + imp.getWidth() + "x" + imp.getHeight() + ":" + imp.getStackSize()).getBytes());
            int n = imp.getStackSize();
            if (allPlanes && n > 1) {
                ImageStack st = imp.getStack();
                for (int i = 1; i <= n; i++) update(md, st.getPixels(i));
            } else {
                update(md, imp.getProcessor().getPixels());
            }
            return Registry.hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest md, Object pixels) {
        if (pixels instanceof byte[] b) {
            md.update(b);
        } else if (pixels instanceof short[] s) {
            ByteBuffer bb = ByteBuffer.allocate(Math.min(s.length, 1 << 16) * 2);
            for (int i = 0; i < s.length; ) {
                bb.clear();
                int end = Math.min(s.length, i + (1 << 16));
                for (; i < end; i++) bb.putShort(s[i]);
                md.update(bb.array(), 0, bb.position());
            }
        } else if (pixels instanceof float[] f) {
            ByteBuffer bb = ByteBuffer.allocate(Math.min(f.length, 1 << 16) * 4);
            for (int i = 0; i < f.length; ) {
                bb.clear();
                int end = Math.min(f.length, i + (1 << 16));
                for (; i < end; i++) bb.putInt(Float.floatToRawIntBits(f[i]));
                md.update(bb.array(), 0, bb.position());
            }
        } else if (pixels instanceof int[] a) {
            ByteBuffer bb = ByteBuffer.allocate(Math.min(a.length, 1 << 16) * 4);
            for (int i = 0; i < a.length; ) {
                bb.clear();
                int end = Math.min(a.length, i + (1 << 16));
                for (; i < end; i++) bb.putInt(a[i]);
                md.update(bb.array(), 0, bb.position());
            }
        } else if (pixels != null) {
            md.update(String.valueOf(pixels.hashCode()).getBytes());
        }
    }
}
